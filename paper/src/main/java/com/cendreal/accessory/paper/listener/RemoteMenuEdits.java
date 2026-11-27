package com.cendreal.accessory.paper.listener;

import com.cendreal.accessory.api.AccessoryAction;
import com.cendreal.accessory.api.event.*;
import com.cendreal.accessory.core.inventory.InventoryRules;
import com.cendreal.accessory.core.menu.*;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.storage.*;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.requirement.SlotCheck;
import com.cendreal.accessory.core.util.TaskScheduler;

/** Saves the viewer's proposed transfer until the owner has checked the current inventory and rules. */
final class RemoteMenuEdits {
    private final AccessoryMenuController menus;
    private final InventoryRules rules;
    private final AccessoryProfileManager profiles;
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final TaskScheduler scheduler;
    private final Supplier<Lang> lang;
    private final MenuSnapshot snapshot = new MenuSnapshot();

    RemoteMenuEdits(AccessoryMenuController menus, InventoryRules rules, AccessoryProfileManager profiles,
                    Supplier<AccessoryStore> store, StorageLayout layout, TaskScheduler scheduler,
                    Supplier<Lang> lang) {
        this.scheduler = scheduler; this.lang = lang;
        this.menus = menus;
        this.rules = rules;
        this.profiles = profiles;
        this.store = store;
        this.layout = layout;
    }

    void click(InventoryClickEvent event, Player actor, Player owner, AccessoryInventoryHolder holder) {
        int slot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();
        var action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.UNKNOWN || (slot >= top.getSize() && action == InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
            event.setCancelled(true);
            return;
        }
        if (slot < 0 || slot >= top.getSize() || action == InventoryAction.NOTHING) return;
        event.setCancelled(true);
        ContainerInput input;
        int button;
        switch (event.getClick()) {
            case LEFT, RIGHT -> { input = ContainerInput.PICKUP; button = event.isRightClick() ? 1 : 0; }
            case SHIFT_LEFT, SHIFT_RIGHT -> { input = ContainerInput.QUICK_MOVE; button = event.isRightClick() ? 1 : 0; }
            case NUMBER_KEY, SWAP_OFFHAND -> {
                input = ContainerInput.SWAP;
                button = event.getClick() == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
            }
            case DROP, CONTROL_DROP -> { input = ContainerInput.THROW; button = event.getClick() == ClickType.DROP ? 0 : 1; }
            default -> { return; }
        }
        ItemStack incoming = switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> button == 40 ? actor.getInventory().getItemInOffHand() : actor.getInventory().getItem(button);
            default -> null;
        };
        var removes = switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY,
                 SWAP_WITH_CURSOR, HOTBAR_SWAP, DROP_ONE_SLOT, DROP_ALL_SLOT -> Set.of(slot);
            default -> Set.<Integer>of();
        };
        var placed = InventoryRules.empty(incoming) ? Map.<Integer, ItemStack>of() : Map.of(slot, incoming.clone());
        submit(actor, owner, holder, removes, placed, actor.getItemOnCursor(), () -> {
            var player = ((CraftPlayer) actor).getHandle();
            // Paper has already fired the Bukkit click event. Apply the same underlying vanilla operation once.
            player.containerMenu.clicked(slot, button, input, player);
            player.containerMenu.broadcastChanges();
        });
    }

    void drag(InventoryDragEvent event, Player actor, Player owner, AccessoryInventoryHolder holder) {
        event.setCancelled(true);
        var view = event.getView();
        Map<Integer, ItemStack> all = new LinkedHashMap<>(), placed = new LinkedHashMap<>();
        for (var entry : event.getNewItems().entrySet()) {
            int slot = entry.getKey();
            ItemStack item = entry.getValue().clone();
            all.put(slot, item);
            if (slot < view.getTopInventory().getSize()) {
                if (snapshot.decoration(view.getItem(slot))) return;
                placed.put(slot, item);
            }
        }
        ItemStack cursor = copy(event.getCursor());
        submit(actor, owner, holder, placed.keySet(), placed, event.getOldCursor(), () -> {
            all.forEach(view::setItem);
            actor.setItemOnCursor(cursor);
        });
    }

    private void submit(Player actor, Player owner, AccessoryInventoryHolder holder, Set<Integer> removes,
                        Map<Integer, ItemStack> placed, ItemStack cursor, Runnable transfer) {
        Inventory top = holder.getInventory();
        int page = holder.currentPage(), offset = layout.pageStart(page);
        ItemStack[] clean = snapshot.read(top);
        ItemStack[] before = copy(top.getContents()), bottom = copy(actor.getInventory().getContents());
        ItemStack hand = copy(cursor);
        menus.edit(actor, top, () -> {
            if (!owner.isOnline() || holder.pages() != profiles.pages(owner) || !profiles.isAllowed(owner, AccessoryAction.OPEN)) return false;
            ItemStack[] full = store.get().getOrLoad(owner.getUniqueId(), layout.totalSize());
            for (int i = 0; i < clean.length; i++) if (!Objects.equals(clean[i], full[offset + i])) return false;
            for (int slot : removes) if (!rules.mayRemove(owner, page, slot, clean[slot])) return false;
            var changes = new LinkedHashMap<Integer, ItemStack>();
            for (var entry : placed.entrySet()) {
                var check = rules.placementDenial(owner, page, entry.getKey(), entry.getValue());
                if (check.result() != SlotCheck.Result.MET) {
                    scheduler.dispatch(actor, () -> check.tell(actor, lang.get()));
                    return false;
                }
                changes.put(offset + entry.getKey(), entry.getValue());
            }
            return changes.isEmpty() || rules.candidateAllowed(owner, full, changes);
        }, () -> {
            if (!unchanged(actor, top, before, bottom, hand)) return false;
            for (var entry : placed.entrySet()) {
                int slot = entry.getKey();
                var event = new AccessoryPlaceEvent(actor, owner.getUniqueId(), page, slot, entry.getValue(), clean[slot], AccessoryChangeCause.GUI);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) return false;
            }
            return unchanged(actor, top, before, bottom, hand);
        }, transfer);
    }

    private static boolean unchanged(Player actor, Inventory top, ItemStack[] before, ItemStack[] bottom, ItemStack cursor) {
        return actor.getOpenInventory().getTopInventory() == top && Arrays.equals(before, top.getContents())
                && Arrays.equals(bottom, actor.getInventory().getContents()) && Objects.equals(cursor, copy(actor.getItemOnCursor()));
    }
    private static ItemStack copy(ItemStack item) { return InventoryRules.empty(item) ? null : item.clone(); }
    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
}
