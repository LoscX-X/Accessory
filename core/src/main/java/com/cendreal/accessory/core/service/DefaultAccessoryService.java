package com.cendreal.accessory.core.service;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.api.event.*;
import com.cendreal.accessory.core.lifecycle.AccessoryLifecycle;
import com.cendreal.accessory.core.menu.AccessoryMenuController;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Public inventory operations. Coordinates state owners in one direction; none of them call back into it. */
public final class DefaultAccessoryService implements AccessoryService, AutoCloseable {
    private final TaskScheduler scheduler;
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final SlotRestrictions restrictions;
    private final AccessoryMenuController menus;
    private final AccessoryLifecycle lifecycle;
    private CompletableFuture<Void> globalChanges = CompletableFuture.completedFuture(null);
    private final ThreadLocal<Boolean> changingSlots = ThreadLocal.withInitial(() -> false);

    public DefaultAccessoryService(TaskScheduler scheduler, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                                   SlotRestrictions restrictions, AccessoryMenuController menus, AccessoryLifecycle lifecycle) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.menus = Objects.requireNonNull(menus);
        this.lifecycle = Objects.requireNonNull(lifecycle);
    }

    @Override public <T> CompletionStage<T> onOwner(UUID owner, java.util.function.Function<AccessoryService, T> operation) {
        Objects.requireNonNull(operation);
        return menus.operations().submit(Objects.requireNonNull(owner), () -> CompletableFuture.completedFuture(operation.apply(this)));
    }
    @Override public CompletionStage<ItemStack[]> read(UUID owner) {
        return store.get().readAsync(Objects.requireNonNull(owner), layout.totalSize());
    }
    @Override public Map<Integer, ItemStack> activeEquipment(Player owner) {
        scheduler.requireOwner(owner);
        return lifecycle.activeEquipment(Objects.requireNonNull(owner));
    }
    @Override public void open(Player viewer, Player owner, AccessoryViewMode mode) { menus.openFor(viewer, owner, mode); }
    @Override public void setBlocked(UUID owner, AccessoryAction action, boolean blocked) { profiles.setBlocked(owner, action, blocked); }
    @Override public boolean isAllowed(Player owner, AccessoryAction action) { return profiles.isAllowed(owner, action); }

    @Override public List<Integer> getDisabledSlots() { return List.copyOf(restrictions.globalDisabled()); }
    @Override public boolean isSlotDisabled(int slot) { return restrictions.isSlotDisabled(slot); }
    @Override public List<Integer> getDisabledSlots(UUID player) { scheduler.requireOwner(player); return restrictions.disabledSlots(player); }
    @Override public boolean isSlotDisabled(UUID player, int slot) { scheduler.requireOwner(player); return restrictions.isSlotDisabled(player, slot); }

    @Override public void setTransientDisabledSlots(UUID player, String owner, Set<Integer> slots) {
        scheduler.requireOwner(player);
        menus.requireTurn(player);
        Objects.requireNonNull(player);
        if (Objects.requireNonNull(owner).isBlank()) throw new IllegalArgumentException("Slot restriction owner must not be blank");
        Set<Integer> next = validSlots(slots);
        if (changingSlots.get()) throw new IllegalStateException("Cannot change slots from inside a slot state change");
        if (restrictions.transientSlots(player, owner).equals(next)) return;
        boolean changesEligibility = !restrictions.disabledWith(player, owner, next)
                .equals(new LinkedHashSet<>(restrictions.disabledSlots(player)));
        changingSlots.set(true);
        try {
            if (changesEligibility) menus.closeForOwner(player);
            Player online = Bukkit.getPlayer(player);
            boolean refresh = changesEligibility && online != null && online.isOnline() && !online.isDead();
            // Commit open windows and obtain current stored items before changing eligibility.
            ItemStack[] contents = refresh ? store.get().getOrLoad(player, layout.totalSize()) : null;
            restrictions.putTransient(player, owner, next);
            if (refresh) lifecycle.refresh(online, contents, null, AccessoryChangeCause.SLOT_STATE);
        } finally { changingSlots.remove(); }
    }
    @Override public void clearTransientDisabledSlots(UUID player, String owner) { setTransientDisabledSlots(player, owner, Set.of()); }

    @Override public boolean lockItem(UUID player, String owner, int storageSlot, ItemStack expected, int durationTicks) {
        scheduler.requireOwner(player);
        menus.requireTurn(player);
        Objects.requireNonNull(player);
        if (Objects.requireNonNull(owner).isBlank()) throw new IllegalArgumentException("Item lock owner must not be blank");
        if (!validTarget(storageSlot, expected, durationTicks)) return false;
        // Finish any pending GUI transfer before binding a lock to the current stack.
        menus.closeForOwner(player);
        if (!expected.equals(store.get().getOrLoad(player, layout.totalSize())[storageSlot])) return false;
        restrictions.lock(player, owner, storageSlot, expected, System.nanoTime() + durationTicks * 50_000_000L);
        return true;
    }
    @Override public void clearItemLocks(UUID player, String owner) {
        scheduler.requireOwner(player);
        menus.requireTurn(player);
        restrictions.clearLocks(Objects.requireNonNull(player), Objects.requireNonNull(owner));
    }
    @Override public boolean isItemLocked(UUID player, int storageSlot) { scheduler.requireOwner(player); return restrictions.isItemLocked(player, storageSlot); }

    @Override public boolean sealEquipmentSlot(UUID player, String owner, int storageSlot, ItemStack expected, int durationTicks) {
        scheduler.requireOwner(player);
        menus.requireTurn(player);
        Objects.requireNonNull(player);
        if (Objects.requireNonNull(owner).isBlank()) throw new IllegalArgumentException("Equipment seal owner must not be blank");
        if (!validTarget(storageSlot, expected, durationTicks)) return false;
        Player online = Bukkit.getPlayer(player);
        if (online == null || !online.isOnline() || online.isDead()) return false;
        // Commit current GUI transfers without replacing the window or touching its cursor.
        menus.commitForOwner(player);
        if (!expected.equals(store.get().getOrLoad(player, layout.totalSize())[storageSlot])) return false;
        if (!restrictions.isEquipmentSealed(player, storageSlot) && !expected.equals(activeEquipment(online).get(storageSlot))) return false;
        var seal = new SlotRestrictions.Seal(expected);
        var expiry = scheduler.later(online, () -> onOwner(player, ignored -> {
            expireSeal(player, owner, storageSlot, seal);
            return null;
        }), durationTicks);
        seal.onCancel(expiry::cancel);
        restrictions.seal(player, owner, storageSlot, seal);
        refreshSeals(player);
        return true;
    }
    @Override public void clearEquipmentSeals(UUID player, String owner) {
        scheduler.requireOwner(player);
        menus.requireTurn(player);
        Objects.requireNonNull(player);
        Objects.requireNonNull(owner);
        if (!restrictions.hasSeals(player, owner)) return;
        menus.commitForOwner(player);
        if (restrictions.unseal(player, owner)) refreshSeals(player);
    }
    @Override public boolean isEquipmentSealed(UUID player, int storageSlot) {
        scheduler.requireOwner(player);
        return restrictions.isEquipmentSealed(player, storageSlot);
    }
    private void expireSeal(UUID player, String owner, int storageSlot, SlotRestrictions.Seal seal) {
        if (!restrictions.isCurrentSeal(player, owner, storageSlot, seal)) return;
        menus.commitForOwner(player);
        if (!restrictions.isCurrentSeal(player, owner, storageSlot, seal)) return;
        restrictions.unseal(player, owner, storageSlot);
        refreshSeals(player);
    }
    private void refreshSeals(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null && online.isOnline() && !online.isDead())
            lifecycle.refresh(online, store.get().getOrLoad(player, layout.totalSize()), null, AccessoryChangeCause.SLOT_STATE);
        menus.refreshForOwner(player);
    }
    /** Session shutdown must release tasks without briefly reapplying equipment effects. */
    public void forgetSession(UUID player) {
        scheduler.requireOwner(player);
        restrictions.forgetSeals(Objects.requireNonNull(player));
    }
    @Override public void close() {
        restrictions.forgetAllSeals();
    }

    @Override public void setSlotEnabled(int slot, boolean enabled) {
        scheduler.requireGlobal();
        if (slot < 0 || slot >= StorageLayout.PAGE_SLOTS) throw new IllegalArgumentException("Slot must be 0..53");
        queueGlobalChange(() -> {
            Set<Integer> next = new LinkedHashSet<>(restrictions.globalDisabled());
            if (enabled) next.remove(slot); else next.add(slot);
            return next;
        });
    }
    @Override public void setDisabledSlots(Collection<Integer> slots) {
        scheduler.requireGlobal();
        Set<Integer> next = validSlots(slots);
        queueGlobalChange(() -> next);
    }
    private void queueGlobalChange(Supplier<Set<Integer>> next) {
        if (changingSlots.get()) throw new IllegalStateException("Cannot change slots from inside a slot state event");
        globalChanges = globalChanges.handle((previous, error) -> null)
                .thenCompose(ignored -> menus.operations().on(null, () -> changeGlobal(next.get())))
                .thenCompose(stage -> stage);
        globalChanges.exceptionally(error -> {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING, "Could not update accessory slots", error);
            return null;
        });
    }
    private CompletableFuture<Void> changeGlobal(Set<Integer> next) {
        Set<Integer> current = restrictions.globalDisabled();
        if (next.equals(current)) return CompletableFuture.completedFuture(null);
        changingSlots.set(true);
        try {
            Set<Integer> changed = new LinkedHashSet<>(current);
            changed.addAll(next);
            for (int slot : changed) {
                if (current.contains(slot) == next.contains(slot)) continue;
                var event = new AccessorySlotStateChangeEvent(slot, !next.contains(slot));
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) return CompletableFuture.completedFuture(null);
            }
        } finally { changingSlots.remove(); }
        return menus.withClosedWindows(() -> {
            restrictions.replaceGlobalDisabled(next);
            for (Player player : Bukkit.getOnlinePlayers())
                scheduler.dispatch(player, () -> lifecycle.load(player, AccessoryChangeCause.SLOT_STATE));
        });
    }

    @Override public boolean clear(UUID owner) { return clear(owner, null, AccessoryChangeCause.CLEAR); }
    @Override public boolean clear(UUID owner, Player actor, AccessoryChangeCause cause) {
        if (owner == null) return false;
        scheduler.requireOwner(owner);
        if (cause != AccessoryChangeCause.DEATH) menus.requireTurn(owner);
        menus.closeForOwner(owner);
        restrictions.clearLocks(owner);
        restrictions.forgetSeals(owner);
        lifecycle.invalidateLoad(owner);
        store.get().clear(owner, layout.totalSize());
        Player online = Bukkit.getPlayer(owner);
        if (online != null) lifecycle.refresh(online, new ItemStack[0], actor, cause);
        return true; // Accepted in memory; persistence errors remain observable and retryable.
    }

    @Override public int storageSize() { return layout.totalSize(); }
    @Override public ItemStack[] contents(UUID owner) {
        ItemStack[] stored = store.get().getOrLoad(Objects.requireNonNull(owner), layout.totalSize());
        ItemStack[] copy = new ItemStack[stored.length];
        for (int i = 0; i < stored.length; i++) copy[i] = stored[i] == null ? null : stored[i].clone();
        return copy;
    }
    @Override public void replace(UUID owner, ItemStack[] contents) {
        scheduler.requireOwner(owner);
        menus.requireTurn(owner);
        Objects.requireNonNull(owner);
        int size = layout.totalSize();
        ItemStack[] next = new ItemStack[size];
        for (int i = 0; i < Math.min(size, contents.length); i++) next[i] = contents[i] == null ? null : contents[i].clone();
        menus.closeForOwner(owner);
        store.get().update(owner, next, size);
        Player online = Bukkit.getPlayer(owner);
        if (online != null) lifecycle.refresh(online, next, null, AccessoryChangeCause.API);
    }
    @Override public void flush(UUID owner) { store.get().flush(Objects.requireNonNull(owner), layout.totalSize()); }
    @Override public void refresh(Player owner) {
        scheduler.requireOwner(owner);
        lifecycle.invalidateLoad(owner.getUniqueId());
        lifecycle.refresh(owner, store.get().getOrLoad(owner.getUniqueId(), layout.totalSize()), null, AccessoryChangeCause.API);
    }

    private boolean validTarget(int storageSlot, ItemStack expected, int durationTicks) {
        return storageSlot >= 0 && storageSlot < layout.totalSize() && durationTicks > 0 && expected != null && !expected.getType().isAir();
    }
    private static Set<Integer> validSlots(Collection<Integer> slots) {
        Set<Integer> next = new LinkedHashSet<>(Objects.requireNonNull(slots));
        for (Integer slot : next) if (slot == null || slot < 0 || slot >= StorageLayout.PAGE_SLOTS) throw new IllegalArgumentException("Slot must be 0..53");
        return next;
    }
}
