package com.cendreal.accessory.core.lifecycle;

import com.cendreal.accessory.api.event.AccessoryChangeCause;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.inventory.ItemDelivery;
import com.cendreal.accessory.core.menu.AccessoryMenuController;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.requirement.*;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.*;
import com.cendreal.accessory.core.util.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Logger;

public final class AccessoryReturns {
    private final TaskScheduler scheduler;
    private final AccessoryProfileManager profiles;
    private final SlotRequirements requirements;
    private final SlotRestrictions restrictions;
    private final ExtensionRegistry extensions;
    private final Supplier<AccessoryMenuController> menus;
    private final Supplier<AccessoryStore> store;
    private final Supplier<Lang> lang;
    private final BooleanSupplier running;
    private final Logger logger;
    private final Set<Player> pending = ConcurrentHashMap.newKeySet();

    public AccessoryReturns(TaskScheduler scheduler, AccessoryProfileManager profiles, SlotRequirements requirements,
                            SlotRestrictions restrictions, ExtensionRegistry extensions, Supplier<AccessoryMenuController> menus,
                            Supplier<AccessoryStore> store, Supplier<Lang> lang, BooleanSupplier running, Logger logger) {
        this.scheduler = scheduler; this.profiles = profiles; this.requirements = requirements; this.restrictions = restrictions;
        this.extensions = extensions; this.menus = menus; this.store = store; this.lang = lang; this.running = running; this.logger = logger;
    }
    private boolean live(Player owner) {
        return running.getAsBoolean() && owner.isOnline() && !owner.isDead() && owner.isValid()
                && Bukkit.getPlayer(owner.getUniqueId()) == owner;
    }
    private Map<Integer, SlotCheck> candidates(Player owner, Map<Integer, SlotCheck> gates) {
        var result = new LinkedHashMap<Integer, SlotCheck>();
        var pages = profiles.pages(owner);
        for (var entry : gates.entrySet()) {
            var check = entry.getValue();
            if (!check.returnsItem()) continue;
            int i = entry.getKey();
            int page = i / StorageLayout.PAGE_SLOTS + 1, slot = i % StorageLayout.PAGE_SLOTS;
            if (!profiles.isSlotAvailable(owner, page, slot)
                    || pages.frameItemAt(page, slot, pages.pageSize(page)) != null || restrictions.isItemLocked(owner.getUniqueId(), i)) continue;
            result.put(i, check);
        }
        return result;
    }
    public boolean request(Player owner, Map<Integer, SlotCheck> gates, AccessoryChangeCause cause, Runnable refreshed) {
        if (cause == AccessoryChangeCause.QUIT || cause == AccessoryChangeCause.DEATH || cause == AccessoryChangeCause.PLUGIN_DISABLE
                || gates.isEmpty() || !live(owner) || candidates(owner, gates).isEmpty()) return false;
        if (pending.add(owner)) scheduler.forPlayer(owner, () -> menus.get().operations().submit(owner.getUniqueId(),
                () -> settle(owner, refreshed)).whenComplete((done, error) -> {
            pending.remove(owner);
            if (error != null && running.getAsBoolean()) logger.warning("Accessory return for " + owner.getUniqueId() + " failed: " + error);
        }), () -> pending.remove(owner));
        return true;
    }
    private CompletionStage<Void> settle(Player owner, Runnable refreshed) {
        if (!live(owner)) return CompletableFuture.completedFuture(null);
        UUID id = owner.getUniqueId();
        var windows = menus.get();
        windows.commitForOwner(id);
        var session = store.get();
        var before = session.cached(id);
        if (before == null) return CompletableFuture.completedFuture(null);
        var rejected = candidates(owner, requirements.equippedConditions(owner, before));
        if (rejected.isEmpty()) return CompletableFuture.completedFuture(null);
        ItemStack[] next = before.clone();
        rejected.keySet().forEach(i -> next[i] = null);
        return session.stageReturn(id, before, next).thenCompose(write -> {
            if (write == null) return CompletableFuture.completedFuture(null);
            return windows.operations().<Void>on(owner, () -> {
                if (live(owner)) windows.commitForOwner(id);
                boolean allowed = live(owner) && store.get() == session && rejected.equals(candidates(owner, requirements.equippedConditions(owner, before)));
                if (!write.finish(allowed)) {
                    if (live(owner)) scheduler.forPlayer(owner, refreshed, null);
                    return null;
                }
                boolean dropped = false;
                for (var entry : rejected.entrySet()) {
                    int slot = entry.getKey();
                    var item = before[slot];
                    boolean ground = ItemDelivery.give(owner, item);
                    dropped |= ground;
                    logger.info("Accessory return " + owner.getName() + " " + id + " slot=" + slot + " item="
                            + extensions.identify(item).orElse(item.getType().name()) + " condition=" + entry.getValue().denial()
                            + " destination=" + (ground ? "ground" : "inventory"));
                }
                windows.redrawContents(id);
                owner.sendMessage(lang.get().langComponent(owner, dropped ? "Accessory_returned_dropped" : "Accessory_returned"));
                refreshed.run();
                return null;
            }).whenComplete((ok, failure) -> { if (failure != null) write.finish(false); });
        });
    }
}
