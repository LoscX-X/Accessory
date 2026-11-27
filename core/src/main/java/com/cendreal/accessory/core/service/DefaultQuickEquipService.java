package com.cendreal.accessory.core.service;

import com.cendreal.accessory.api.AccessoryQuickEquipService;
import com.cendreal.accessory.api.event.*;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.inventory.InventoryRules;
import com.cendreal.accessory.core.lifecycle.AccessoryLifecycle;
import com.cendreal.accessory.core.menu.AccessoryMenuController;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.skill.SkillLoadout;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import net.kyori.adventure.text.event.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.core.inventory.ItemDelivery;
import com.cendreal.accessory.core.requirement.SlotCheck;
import com.cendreal.accessory.core.util.TaskScheduler;

/** One-item main-hand transfer with a shared owner policy and reentrancy protection. */
public final class DefaultQuickEquipService implements AccessoryQuickEquipService {
    private final TaskScheduler scheduler;
    private record Target(int page, int slot) { }
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final AccessoryMenuController menus;
    private final AccessoryLifecycle lifecycle;
    private final InventoryRules rules;
    private final SkillLoadout skills;
    private final Supplier<Lang> lang;
    private final Set<UUID> transfers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DefaultQuickEquipService(TaskScheduler scheduler, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                                    AccessoryMenuController menus, AccessoryLifecycle lifecycle, InventoryRules rules,
                                    SkillLoadout skills, Supplier<Lang> lang) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.menus = Objects.requireNonNull(menus);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.rules = Objects.requireNonNull(rules);
        this.skills = Objects.requireNonNull(skills);
        this.lang = Objects.requireNonNull(lang);
    }

    @Override public boolean tryEquipMainHand(Player player) {
        scheduler.requireOwner(player);
        if (!store.get().ready(player.getUniqueId())) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (InventoryRules.empty(item)) return false;
        List<Target> targets = new ArrayList<>();
        SlotCheck refused = null;
        var pages = profiles.pages(player);
        for (int page = 1; page <= pages.pageCount(); page++)
            for (int slot : pages.configuredSlots(page)) {
                var check = rules.placementDenial(player, page, slot, item);
                if (check.result() == SlotCheck.Result.MET) targets.add(new Target(page, slot));
                else if (refused == null && !check.message().equals("Item_not_match")) refused = check;
            }
        if (targets.isEmpty()) {
            if (refused != null) refused.tell(player, lang.get());
            return false;
        }
        if (targets.size() == 1 && menus.hasPendingEdit(player.getUniqueId())) {
            ItemStack expected = item.clone();
            Target target = targets.getFirst();
            menus.operations().submit(player.getUniqueId(), () -> java.util.concurrent.CompletableFuture.completedFuture(
                    expected.equals(player.getInventory().getItemInMainHand()) && equipToSlot(player, target.page(), target.slot())));
            return true;
        }
        if (targets.size() == 1) return equipToSlot(player, targets.getFirst().page(), targets.getFirst().slot());
        Lang messages = lang.get();
        player.sendMessage(messages.langComponent(player, "Quick_equip_select_header"));
        for (Target target : targets) player.sendMessage(messages.langComponent(player, "Quick_equip_select_option",
                Map.of("page", String.valueOf(target.page()), "slot", String.valueOf(target.slot())))
                .clickEvent(ClickEvent.runCommand("/accessory quickequip " + target.page() + " " + target.slot()))
                .hoverEvent(HoverEvent.showText(messages.langComponent(player, "Quick_equip_select_hover"))));
        return true;
    }

    @Override public boolean equipToSlot(Player player, int page, int slot) {
        scheduler.requireOwner(player);
        if (!store.get().ready(player.getUniqueId())) return false;
        menus.requireTurn(player.getUniqueId());
        if (!transfers.add(player.getUniqueId())) return false;
        try {
            menus.closeForOwner(player.getUniqueId());
            var pages = profiles.pages(player);
            if (page < 1 || page > pages.pageCount() || slot < 0 || slot >= pages.pageSize(page)) return false;
            ItemStack hand = player.getInventory().getItemInMainHand().clone();
            if (InventoryRules.empty(hand)) return false;
            var check = rules.placementDenial(player, page, slot, hand);
            if (check.result() != SlotCheck.Result.MET) {
                check.tell(player, lang.get()); return false;
            }
            int size = layout.totalSize();
            ItemStack[] contents = store.get().getOrLoad(player.getUniqueId(), size);
            int index = layout.pageStart(page) + slot;
            ItemStack old = contents[index], placed = hand.clone();
            placed.setAmount(1); skills.stamp(placed);
            if (!rules.mayRemove(player, page, slot, old)) { player.sendMessage(lang.get().langComponent(player, "Item_cannot_unequip")); return false; }
            if (!rules.candidateAllowed(player, contents, Map.of(index, placed))) {
                player.sendMessage(lang.get().langComponent(player, "Item_limit_reached")); return false;
            }
            var event = new AccessoryPlaceEvent(player, player.getUniqueId(), page, slot, placed, old, AccessoryChangeCause.QUICK_EQUIP);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !hand.equals(player.getInventory().getItemInMainHand())
                    || pages != profiles.pages(player) || !rules.mayPlace(player, page, slot, placed)
                    || !rules.mayRemove(player, page, slot, old) || !Arrays.equals(contents, store.get().getOrLoad(player.getUniqueId(), size)))
                return false;
            contents[index] = placed;
            store.get().update(player.getUniqueId(), contents, size);
            if (hand.getAmount() == 1) player.getInventory().setItemInMainHand(old == null ? null : old.clone());
            else {
                hand.setAmount(hand.getAmount() - 1); player.getInventory().setItemInMainHand(hand);
                if (!InventoryRules.empty(old)) ItemDelivery.give(player, old);
            }
            store.get().flush(player.getUniqueId(), size);
            lifecycle.refresh(player, contents, player, AccessoryChangeCause.QUICK_EQUIP);
            player.sendActionBar(lang.get().langComponent(player, "Cendreal-Accessory_equipped", Map.of("page", "" + page, "slot", "" + slot)));
            return true;
        } finally { transfers.remove(player.getUniqueId()); }
    }

}
