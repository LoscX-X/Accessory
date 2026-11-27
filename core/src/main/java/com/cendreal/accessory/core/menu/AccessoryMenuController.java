package com.cendreal.accessory.core.menu;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.api.event.AccessoryChangeCause;
import com.cendreal.accessory.core.lifecycle.AccessoryLifecycle;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.*;
import java.util.function.Supplier;

/** Owns window requests and commits. Exactly one window per owner, including asynchronous opens. */
public final class AccessoryMenuController {
    private final TaskScheduler scheduler;
    private final OwnerOperations operations;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private final java.util.concurrent.atomic.AtomicInteger pauses = new java.util.concurrent.atomic.AtomicInteger();

    private static final class Window {
        final Player owner, viewer;
        final int page;
        volatile Inventory inventory;
        boolean valid = true;
        long revision;
        ItemStack[] confirmed;
        int pendingEdits;
        Window(Player owner, Player viewer, int page) { this.owner = owner; this.viewer = viewer; this.page = page; }
    }
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final SlotRestrictions restrictions;
    private final AccessoryLifecycle lifecycle;
    private final AccessoryInventoryMenu menu;
    private final MenuSnapshot snapshot = new MenuSnapshot();
    private final MenuRequests requests = new MenuRequests();

    public AccessoryMenuController(TaskScheduler scheduler, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                                   SlotRestrictions restrictions, AccessoryLifecycle lifecycle, Supplier<com.cendreal.accessory.core.configuration.lang.Lang> lang) {
        this.scheduler = Objects.requireNonNull(scheduler);
        operations = new OwnerOperations(scheduler);
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        menu = new AccessoryInventoryMenu(lang);
    }
    public void openFor(Player viewer, Player owner) { openFor(viewer, owner, AccessoryViewMode.EDIT); }
    public void openFor(Player viewer, Player owner, AccessoryViewMode mode) { openPage(viewer, owner, 1, mode); }
    public OwnerOperations operations() { return operations; }
    private boolean accepting() { return !stopped && pauses.get() == 0; }

    public void requireTurn(UUID owner) {
        if (hasPendingEdit(owner))
            throw new IllegalStateException("A remote edit is pending; enqueue this operation with AccessoryService.onOwner");
    }
    public boolean hasPendingEdit(UUID owner) {
        Window window = windows.get(owner);
        if (window == null || operations.executing(owner)) return false;
        synchronized (window) { return window.pendingEdits != 0; }
    }

    private void openPage(Player viewer, Player owner, int requested, AccessoryViewMode mode) {
        scheduler.requireOwner(viewer);
        if (!accepting() || !viewer.isOnline()) return;
        if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof AccessoryInventoryHolder) viewer.closeInventory();
        Inventory previous = viewer.getOpenInventory().getTopInventory();
        UUID id = owner.getUniqueId();
        var token = requests.begin(viewer.getUniqueId(), id);
        operations.submit(id, () -> {
            if (!requests.current(token) || !owner.isOnline() || !profiles.isAllowed(owner, AccessoryAction.OPEN))
                return CompletableFuture.completedFuture(null);
            closeWindow(id, true);
            var pages = profiles.pages(owner);
            var profile = profiles.resolve(owner);
            int selected = pages.normalizePage(requested);
            if (!profile.allowsPage(selected))
                selected = java.util.stream.IntStream.rangeClosed(1, pages.pageCount()).filter(profile::allowsPage).findFirst().orElse(0);
            if (selected == 0) return CompletableFuture.completedFuture(null);
            int page = selected;
            Window window = new Window(owner, viewer, page);
            return store.get().readAsync(id, layout.totalSize()).thenCompose(ignored -> operations.on(owner, () -> {
                if (!requests.current(token) || !owner.isOnline() || pages != profiles.pages(owner)
                        || !profiles.isAllowed(owner, AccessoryAction.OPEN)) return CompletableFuture.<Void>completedFuture(null);
                ItemStack[] current = store.get().getSliceOrLoad(id, layout.pageStart(page), pages.pageSize(page), layout.totalSize());
                var disabled = disabled(owner, page);
                var seals = sealed(owner, page);
                windows.put(id, window);
                return operations.<Void>on(viewer, () -> {
                    if (!requests.current(token) || !viewer.isOnline() || viewer.getOpenInventory().getTopInventory() != previous)
                        return null;
                    var inventory = menu.create(viewer, id, page, pages.pageCount(), current, disabled, seals, mode, pages);
                    synchronized (window) {
                        if (!window.valid || !requests.current(token)) return null;
                        window.inventory = inventory;
                    }
                    viewer.openInventory(inventory);
                    if (viewer.getOpenInventory().getTopInventory() != inventory) windows.remove(id, window);
                    return null;
                }).whenComplete((done, error) -> {
                    if (window.inventory == null || error != null) windows.remove(id, window);
                });
            }).thenCompose(stage -> stage));
        }).whenComplete((ignored, failure) -> {
            requests.complete(token);
            if (failure != null && !(failure instanceof CancellationException))
                Bukkit.getLogger().log(java.util.logging.Level.FINE, "Accessory window request ended", failure);
        });
    }

    private List<Integer> sealed(Player owner, int page) {
        return java.util.stream.IntStream.range(0, profiles.pages(owner).pageSize(page))
            .filter(slot -> restrictions.isEquipmentSealed(owner.getUniqueId(), layout.pageStart(page) + slot)).boxed().toList();
    }
    public List<Integer> disabled(Player owner, int page) {
        var pages = profiles.pages(owner);
        var profile = profiles.resolve(owner);
        return java.util.stream.IntStream.range(0, pages.pageSize(page))
                .filter(slot -> restrictions.isSlotDisabled(owner.getUniqueId(), slot) || !profile.allowsSlot(page, slot)).boxed().toList();
    }
    private void write(Window window, ItemStack[] items) {
        UUID id = window.owner.getUniqueId();
        if (!store.get().ready(id)) return;
        if (Arrays.equals(items, store.get().getSliceOrLoad(id, layout.pageStart(window.page), items.length, layout.totalSize()))) return;
        store.get().updateSlice(id, layout.pageStart(window.page), items, items.length, layout.totalSize());
        if (window.owner.isOnline() && !window.owner.isDead())
            lifecycle.refresh(window.owner, store.get().getOrLoad(id, layout.totalSize()), window.viewer, AccessoryChangeCause.GUI);
    }
    private void drain(Window window) {
        ItemStack[] items;
        synchronized (window) { items = window.confirmed; window.confirmed = null; }
        if (items != null) write(window, items);
    }
    public void commit(Player viewer, Inventory inventory) {
        if (!(inventory.getHolder() instanceof AccessoryInventoryHolder holder) || holder.mode() == AccessoryViewMode.READ_ONLY) return;
        Window window = windows.get(holder.getOwnerId());
        if (window == null || window.inventory != inventory || window.owner != viewer) return;
        write(window, snapshot.read(inventory));
    }
    public void closed(Player viewer, Inventory inventory) {
        if (!(inventory.getHolder() instanceof AccessoryInventoryHolder holder)) return;
        Window window = windows.get(holder.getOwnerId());
        if (window == null || window.inventory != inventory) return;
        if (window.owner == viewer) {
            commit(viewer, inventory);
            windows.remove(holder.getOwnerId(), window);
        } else {
            synchronized (window) { window.valid = false; }
            operations.submit(holder.getOwnerId(), () -> {
                if (windows.remove(holder.getOwnerId(), window)) drain(window);
                return CompletableFuture.completedFuture(null);
            });
        }
    }

    /** Validates on the owner, applies the native transfer on the viewer, then settles back on the owner. */
    public void edit(Player viewer, Inventory inventory, BooleanSupplier ownerAllows, BooleanSupplier viewerAllows, Runnable transfer) {
        var holder = (AccessoryInventoryHolder) inventory.getHolder();
        UUID id = holder.getOwnerId();
        Window window = windows.get(id);
        if (!accepting() || !store.get().ready(id) || window == null || window.inventory != inventory || window.viewer != viewer) return;
        synchronized (window) {
            if (!window.valid) return;
            window.pendingEdits++;
        }
        operations.submit(id, () -> {
            if (windows.get(id) != window || !ownerAllows.getAsBoolean()) return CompletableFuture.completedFuture(null);
            long revision;
            synchronized (window) { if (!window.valid) return CompletableFuture.completedFuture(null); revision = window.revision; }
            return operations.next(viewer, () -> {
                if (viewer.getOpenInventory().getTopInventory() != inventory || !viewerAllows.getAsBoolean()) return false;
                synchronized (window) {
                    if (!window.valid || revision != window.revision || !store.get().ready(id)
                            || viewer.getOpenInventory().getTopInventory() != inventory) return false;
                    transfer.run();
                    window.confirmed = snapshot.read(inventory);
                    return true;
                }
            }).thenCompose(changed -> operations.on(window.owner, () -> {
                if (changed && windows.get(id) == window) drain(window);
                return null;
            }));
        }).whenComplete((ignored, failure) -> {
            synchronized (window) { window.pendingEdits--; }
            if (failure != null && !(failure instanceof CancellationException)
                    && !(failure.getCause() instanceof CancellationException))
                Bukkit.getLogger().log(java.util.logging.Level.WARNING, "Could not settle accessory edit for " + id, failure);
        });
    }

    public void invalidateOwner(UUID owner) { requests.cancelOwner(owner); }
    public void forgetViewer(UUID viewer) { requests.cancelViewer(viewer); }
    public void commitForOwner(UUID owner) {
        Window window = windows.get(owner);
        if (window == null || window.inventory == null) return;
        if (window.viewer == window.owner) commit(window.viewer, window.inventory);
        else { synchronized (window) { window.revision++; } drain(window); }
    }
    public void refreshForOwner(UUID id) {
        Window window = windows.get(id);
        if (window == null || window.inventory == null) return;
        var disabled = disabled(window.owner, window.page);
        var sealed = sealed(window.owner, window.page);
        scheduler.dispatch(window.viewer, () -> {
            Inventory inventory = window.inventory;
            if (window.viewer.getOpenInventory().getTopInventory() == inventory)
                menu.decorate(inventory, (AccessoryInventoryHolder) inventory.getHolder(), disabled, sealed);
        });
    }
    /** Called on the owner after a confirmed return. Invalidate edits based on the previous display. */
    public void redrawContents(UUID id) {
        Window window = windows.get(id);
        if (window == null || window.inventory == null) return;
        int size = profiles.pages(window.owner).pageSize(window.page);
        var items = store.get().getSliceOrLoad(id, layout.pageStart(window.page), size, layout.totalSize());
        var disabled = disabled(window.owner, window.page);
        var seals = sealed(window.owner, window.page);
        synchronized (window) { window.revision++; }
        scheduler.dispatch(window.viewer, () -> {
            if (windows.get(id) != window || window.viewer.getOpenInventory().getTopInventory() != window.inventory) return;
            var holder = (AccessoryInventoryHolder) window.inventory.getHolder();
            holder.restoreConcealedContents(window.inventory);
            window.inventory.setContents(items);
            menu.decorate(window.inventory, holder, disabled, seals);
        });
    }
    public void closeForOwner(UUID owner) { invalidateOwner(owner); closeWindow(owner, true); }
    public void discardForOwner(UUID owner) { invalidateOwner(owner); closeWindow(owner, false); }
    private void closeWindow(UUID owner, boolean settle) {
        Window window = windows.get(owner);
        if (window == null) return;
        synchronized (window) { window.valid = false; }
        if (settle) {
            if (window.viewer == window.owner && window.inventory != null) commit(window.viewer, window.inventory);
            else drain(window);
        }
        windows.remove(owner, window);
        scheduler.dispatch(window.viewer, () -> {
            if (window.viewer.getOpenInventory().getTopInventory() == window.inventory) window.viewer.closeInventory();
        });
    }
    public CompletableFuture<Void> withClosedWindows(Runnable change) {
        pauses.incrementAndGet();
        requests.clear();
        Set<UUID> owners = new HashSet<>(windows.keySet());
        for (Player player : Bukkit.getOnlinePlayers()) owners.add(player.getUniqueId());
        var closedWindows = CompletableFuture.allOf(owners.stream().map(id -> operations.submit(id, () -> {
            closeForOwner(id);
            return CompletableFuture.completedFuture(null);
        }).exceptionally(error -> {
            if (error instanceof CancellationException || error.getCause() instanceof CancellationException) return null;
            throw new CompletionException(error);
        })).toArray(CompletableFuture[]::new));
        return closedWindows.thenCompose(done -> operations.<Void>on(null, () -> {
            if (stopped) throw new CancellationException("Accessory stopped");
            change.run();
            return null;
        })).whenComplete((done, error) -> pauses.decrementAndGet());
    }
    /** During disable only owned viewers may be touched; remote confirmed copies contain no live inventory references. */
    public void stop() {
        stopped = true;
        requests.clear();
        operations.close();
        for (var window : List.copyOf(windows.values())) {
            if (scheduler.owns(window.owner) && scheduler.owns(window.viewer)) closeWindow(window.owner.getUniqueId(), true);
            else synchronized (window) {
                window.valid = false;
                if (window.confirmed != null) store.get().updateSlice(window.owner.getUniqueId(), layout.pageStart(window.page),
                        window.confirmed, window.confirmed.length, layout.totalSize());
            }
        }
        windows.clear();
    }
    public void handleFrameClick(Player player, InventoryView view, int slot) {
        if (!(view.getTopInventory().getHolder() instanceof AccessoryInventoryHolder holder)) return;
        var pages = holder.pages();
        var item = pages.frameItemAt(holder.currentPage(), slot, view.getTopInventory().getSize());
        if (item == null) return;
        switch (item.action()) {
            case PREVIOUS_PAGE -> changePage(player, view, -1);
            case NEXT_PAGE -> changePage(player, view, 1);
            case COMMAND -> {
                // Read-only windows must not expose configured mutation commands.
                if (holder.mode() == AccessoryViewMode.READ_ONLY) return;
                scheduler.forPlayer(player, () -> {
                    if (!player.isOnline() || player.getOpenInventory().getTopInventory() != view.getTopInventory()) return;
                    Runnable playerCommands = () -> MenuCommandExecutor.execute(player, holder.currentPage(), slot, item.playerCommands(), false);
                    if (item.consoleCommands().isEmpty()) playerCommands.run();
                    else operations.on(null, () -> {
                        MenuCommandExecutor.execute(player, holder.currentPage(), slot, item.consoleCommands(), true);
                        scheduler.dispatch(player, playerCommands);
                        return null;
                    });
                }, null);
            }
            case NONE -> { }
        }
    }
    private void changePage(Player viewer, InventoryView view, int direction) {
        var holder = (AccessoryInventoryHolder) view.getTopInventory().getHolder();
        Player owner = Bukkit.getPlayer(holder.getOwnerId());
        if (owner == null) return;
        int page = holder.currentPage() + direction;
        operations.on(owner, () -> {
            int next = page;
            var profile = profiles.resolve(owner);
            while (next >= 1 && next <= holder.totalPages() && !profile.allowsPage(next)) next += direction;
            return next;
        }).thenAccept(next -> {
            if (next < 1 || next > holder.totalPages()) return;
            scheduler.forPlayer(viewer, () -> {
                if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory() == view.getTopInventory())
                    openPage(viewer, owner, next, holder.mode());
            }, null);
        });
    }
}
