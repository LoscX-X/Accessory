package com.cendreal.accessory.paper.listener;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.api.event.*;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.inventory.InventoryRules;
import com.cendreal.accessory.core.menu.*;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.core.requirement.SlotCheck;

/** Event translation only: owner policy, proposed transfer, cancellable event, deferred commit. */
public final class MenuListener implements Listener {
    private final TaskScheduler scheduler;
    private final RemoteMenuEdits remote;
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final AccessoryMenuController menus;
    private final InventoryRules rules;
    private final Supplier<Lang> lang;
    private final MenuSnapshot snapshot = new MenuSnapshot();
    private final Set<Inventory> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MenuListener(TaskScheduler scheduler, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                        AccessoryMenuController menus, InventoryRules rules, Supplier<Lang> lang) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.remote = new RemoteMenuEdits(menus, rules, profiles, store, layout, scheduler, lang);
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.menus = Objects.requireNonNull(menus);
        this.rules = Objects.requireNonNull(rules);
        this.lang = Objects.requireNonNull(lang);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player actor)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AccessoryInventoryHolder holder)) return;
        if (!store.get().ready(holder.getOwnerId())) { event.setCancelled(true); return; }
        Player owner = Bukkit.getPlayer(holder.getOwnerId());
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < top.getSize() && snapshot.decoration(top.getItem(slot))) {
            event.setCancelled(true); menus.handleFrameClick(actor, event.getView(), slot); return;
        }
        if (owner != null && owner != actor && holder.mode() == AccessoryViewMode.EDIT) {
            remote.click(event, actor, owner, holder);
            return;
        }
        if (owner == null || holder.mode() == AccessoryViewMode.READ_ONLY || holder.pages() != profiles.pages(owner)
                || !profiles.isAllowed(owner, AccessoryAction.OPEN)) { event.setCancelled(true); return; }
        // These actions affect an unknown set of top slots, or manufacture items.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getAction() == InventoryAction.CLONE_STACK
                || event.getAction() == InventoryAction.UNKNOWN) { event.setCancelled(true); return; }
        if (slot >= top.getSize()) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) event.setCancelled(true);
            return;
        }
        if (slot < 0) return; // Dropping the cursor outside does not mutate the accessory inventory.
        ItemStack old = event.getCurrentItem();
        boolean removes = switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY,
                 SWAP_WITH_CURSOR, HOTBAR_SWAP, DROP_ONE_SLOT, DROP_ALL_SLOT -> true;
            default -> false;
        };
        if (removes && !rules.mayRemove(owner, holder.currentPage(), slot, old)) { deny(event, actor, "Item_cannot_unequip"); return; }
        ItemStack incoming = switch (event.getAction()) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> event.getClick() == ClickType.SWAP_OFFHAND ? actor.getInventory().getItemInOffHand()
                    : event.getHotbarButton() >= 0 && event.getHotbarButton() < 9 ? actor.getInventory().getItem(event.getHotbarButton()) : null;
            default -> null;
        };
        if (!InventoryRules.empty(incoming)) {
            var check = rules.placementDenial(owner, holder.currentPage(), slot, incoming);
            if (check.result() != SlotCheck.Result.MET) {
                event.setCancelled(true); check.tell(actor, lang.get()); return;
            }
            if (!rules.candidateAllowed(owner, full(owner, top, holder), Map.of(layout.pageStart(holder.currentPage()) + slot, incoming))) {
                deny(event, actor, "Item_limit_reached"); return;
            }
            ItemStack[] before = top.getContents();
            ItemStack cursor = event.getCursor() == null ? null : event.getCursor().clone();
            if (!place(actor, holder, slot, incoming, old) || !Arrays.equals(before, top.getContents())
                    || !Objects.equals(cursor, event.getCursor()) || holder.pages() != profiles.pages(owner)
                    || actor.getOpenInventory().getTopInventory() != top
                    || !rules.mayPlace(owner, holder.currentPage(), slot, incoming)
                    || removes && !rules.mayRemove(owner, holder.currentPage(), slot, old)) { event.setCancelled(true); return; }
        }
        schedule(actor, top);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player actor)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AccessoryInventoryHolder holder)) return;
        if (!store.get().ready(holder.getOwnerId())) { event.setCancelled(true); return; }
        Map<Integer, ItemStack> changes = new LinkedHashMap<>();
        event.getNewItems().forEach((slot, item) -> { if (slot >= 0 && slot < top.getSize()) changes.put(slot, item); });
        if (changes.isEmpty()) return;
        Player owner = Bukkit.getPlayer(holder.getOwnerId());
        if (owner != null && owner != actor && holder.mode() == AccessoryViewMode.EDIT) {
            remote.drag(event, actor, owner, holder);
            return;
        }
        if (owner == null || holder.mode() == AccessoryViewMode.READ_ONLY || holder.pages() != profiles.pages(owner)
                || !profiles.isAllowed(owner, AccessoryAction.OPEN)) { event.setCancelled(true); return; }
        Map<Integer, ItemStack> absolute = new LinkedHashMap<>();
        for (var change : changes.entrySet()) {
            int slot = change.getKey();
            if (snapshot.decoration(top.getItem(slot)) || !rules.mayRemove(owner, holder.currentPage(), slot, top.getItem(slot))) { event.setCancelled(true); return; }
            var check = rules.placementDenial(owner, holder.currentPage(), slot, change.getValue());
            if (check.result() != SlotCheck.Result.MET) { event.setCancelled(true); check.tell(actor, lang.get()); return; }
            absolute.put(layout.pageStart(holder.currentPage()) + slot, change.getValue());
        }
        if (!rules.candidateAllowed(owner, full(owner, top, holder), absolute)) {
            event.setCancelled(true); actor.sendMessage(lang.get().langComponent(actor, "Item_limit_reached")); return;
        }
        ItemStack[] before = top.getContents();
        for (var change : changes.entrySet()) if (!place(actor, holder, change.getKey(), change.getValue(), top.getItem(change.getKey()))) {
            event.setCancelled(true); return;
        }
        if (!Arrays.equals(before, top.getContents()) || holder.pages() != profiles.pages(owner)
                || actor.getOpenInventory().getTopInventory() != top
                || changes.entrySet().stream().anyMatch(change -> !rules.mayPlace(owner, holder.currentPage(), change.getKey(), change.getValue())
                    || !rules.mayRemove(owner, holder.currentPage(), change.getKey(), top.getItem(change.getKey())))) {
            event.setCancelled(true); return;
        }
        schedule(actor, top);
    }

    /** Window lifecycle: closing a window commits it. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        pending.remove(top);
        if (event.getPlayer() instanceof Player player) menus.closed(player, top);
    }

    private ItemStack[] full(Player owner, Inventory top, AccessoryInventoryHolder holder) {
        ItemStack[] contents = store.get().getOrLoad(owner.getUniqueId(), layout.totalSize());
        ItemStack[] page = snapshot.read(top);
        System.arraycopy(page, 0, contents, layout.pageStart(holder.currentPage()), page.length);
        return contents;
    }
    private boolean place(Player actor, AccessoryInventoryHolder holder, int slot, ItemStack item, ItemStack old) {
        var event = new AccessoryPlaceEvent(actor, holder.getOwnerId(), holder.currentPage(), slot, item, old, AccessoryChangeCause.GUI);
        Bukkit.getPluginManager().callEvent(event); return !event.isCancelled();
    }
    private void schedule(Player actor, Inventory top) {
        if (!pending.add(top)) return;
        scheduler.forPlayer(actor, () -> {
            pending.remove(top);
            if (actor.isOnline() && actor.getOpenInventory().getTopInventory() == top) menus.commit(actor, top);
        }, () -> pending.remove(top));
    }
    private void deny(InventoryClickEvent event, Player actor, String message) {
        event.setCancelled(true); actor.sendMessage(lang.get().langComponent(actor, message));
    }
}
