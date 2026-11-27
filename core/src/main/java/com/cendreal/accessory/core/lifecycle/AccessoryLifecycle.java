package com.cendreal.accessory.core.lifecycle;

import com.cendreal.accessory.api.AccessoryAction;
import com.cendreal.accessory.api.extension.*;
import com.cendreal.accessory.core.requirement.*;
import com.cendreal.accessory.core.set.SetCatalog;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.api.event.*;
import com.cendreal.accessory.core.attribute.AccessoryAttributes;
import com.cendreal.accessory.core.inventory.ItemLimitManager;
import com.cendreal.accessory.core.lifecycle.EquipmentChanges.Slot;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.skill.SkillLoadout;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import com.cendreal.accessory.core.util.DebugLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;

import java.util.*;
import java.util.function.Supplier;

/** Owns active equipment and effect lifetimes. Persistence and menu transfers remain separate. */
public final class AccessoryLifecycle {
    private final TaskScheduler scheduler;
    private final Logger logger;
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final SkillLoadout skills;
    private final DebugLog debug;
    private final AccessoryEquipment equipment;
    private final AccessoryTags tags = new AccessoryTags();
    private final Map<UUID, Map<Slot, ItemStack>> equipped = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loads = new ConcurrentHashMap<>();
    private final AccessoryAttributes attributes;
    private final ExtensionRegistry registry;
    private final SlotRequirements requirements;
    private final Supplier<SetCatalog> sets;
    private final AccessoryReturns returns;
    private final Map<UUID, SetCatalog.Active> activeSets = new ConcurrentHashMap<>();
    private record Watch(Map<Integer, SlotRequirementSpec> slots, Map<Integer, SlotCheck.Result> results, boolean polled) { }
    private final Map<UUID, Watch> watched = new ConcurrentHashMap<>();
    private final Set<Player> invalidations = ConcurrentHashMap.newKeySet();

    /** The store supplier follows storage replacement on reload. */
    public AccessoryLifecycle(TaskScheduler scheduler, Logger logger, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                              ItemLimitManager limits, AccessoryAttributes attributes, SkillLoadout skills, DebugLog debug,
                              ExtensionRegistry registry, SlotRequirements requirements, Supplier<SetCatalog> sets, AccessoryReturns returns) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.logger = Objects.requireNonNull(logger);
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.attributes = Objects.requireNonNull(attributes);
        this.skills = Objects.requireNonNull(skills);
        this.debug = Objects.requireNonNull(debug);
        this.registry = registry; this.requirements = requirements; this.sets = sets; this.returns = returns;
        equipment = new AccessoryEquipment(requirements, limits);
    }

    public void load(Player player, AccessoryChangeCause cause) {
        scheduler.requireOwner(player);
        Object request = new Object();
        loads.put(player.getUniqueId(), request);
        int size = layout.totalSize();
        store.get().getSliceOrLoadAsync(player.getUniqueId(), 0, size, size, task -> scheduler.forPlayer(player, task, null), ignored -> finishLoad(player, cause, request, 0));
    }

    private void finishLoad(Player player, AccessoryChangeCause cause, Object request, int attempt) {
        if (Bukkit.getPlayer(player.getUniqueId()) != player || loads.get(player.getUniqueId()) != request || !store.get().ready(player.getUniqueId())) return;
        if (player.isDead()) { loads.remove(player.getUniqueId(), request); return; }
        if (!attributes.isReady(player) && attempt < 100) {
            if (attempt == 0) refresh(player, store.get().getOrLoad(player.getUniqueId(), layout.totalSize()), null, cause);
            scheduler.later(player, () -> finishLoad(player, cause, request, attempt + 1), 2L);
            return;
        }
        loads.remove(player.getUniqueId(), request);
        // Read the current cache; the asynchronous loading snapshot may predate clear/equip.
        refresh(player, store.get().getOrLoad(player.getUniqueId(), layout.totalSize()), null, cause);
    }

    public void refresh(Player player, ItemStack[] stored, Player actor, AccessoryChangeCause cause) {
        scheduler.requireOwner(player);
        if (stored == null) stored = new ItemStack[0];
        var gates = requirements.equippedConditions(player, stored);
        if (returns.request(player, gates, cause, () -> refreshCurrent(player))) cause = AccessoryChangeCause.REQUIREMENT;
        updateWatch(player, gates);
        ItemStack[] active = equipment.activeContents(player, stored, gates);
        List<AccessoryActiveItem> items = new ArrayList<>();
        for (int i = 0; i < active.length; i++) if (active[i] != null) items.add(new AccessoryActiveItem(i, active[i]));
        var setState = sets.get().count(items, registry);
        var lastSets = activeSets.put(player.getUniqueId(), setState);
        Map<Slot, ItemStack> next = snapshot(active);
        Map<Slot, ItemStack> previous = equipped.getOrDefault(player.getUniqueId(), Map.of());
        attributes.rebuild(player, profiles.isAllowed(player, AccessoryAction.ATTRIBUTES)
                ? new AccessoryEffects(items, setState.attributes()) : AccessoryEffects.EMPTY);
        tags.rebuild(player, active);
        boolean useSkills = profiles.isAllowed(player, AccessoryAction.SKILLS);
        skills.equip(player, useSkills ? active : new ItemStack[0], useSkills ? setState.skills() : List.of());
        equipped.put(player.getUniqueId(), next);
        skills.refreshEquipment(player);
        debug.trace("lifecycle", player.getUniqueId().toString(), () -> "refresh player=" + player.getName() + " active=" + next.size());
        notifyChanges(player, actor, previous, next, cause);
        notifySets(player, lastSets, setState);
    }

    public void unload(Player player, AccessoryChangeCause cause) {
        scheduler.requireOwner(player);
        loads.remove(player.getUniqueId());
        Map<Slot, ItemStack> previous = equipped.remove(player.getUniqueId());
        watched.remove(player.getUniqueId());
        invalidations.remove(player);
        var lastSets = activeSets.remove(player.getUniqueId());
        attributes.clear(player);
        tags.rebuild(player, new ItemStack[0]);
        skills.detach(player, cause);
        skills.refreshEquipment(player);
        notifyChanges(player, null, previous == null ? Map.of() : previous, Map.of(), cause);
        notifySets(player, lastSets, SetCatalog.Active.EMPTY);
    }

    public int pieces(Player owner, String set) {
        return activeSets.getOrDefault(owner.getUniqueId(), SetCatalog.Active.EMPTY).counts().getOrDefault(set, SetCatalog.Count.ZERO).pieces();
    }
    private void notifySets(Player owner, SetCatalog.Active before, SetCatalog.Active after) {
        var previous = before == null ? Map.<String, SetCatalog.Count>of() : before.counts();
        var ids = new LinkedHashSet<>(previous.keySet());
        ids.addAll(after.counts().keySet());
        for (var id : ids) {
            var old = previous.getOrDefault(id, SetCatalog.Count.ZERO);
            var next = after.counts().getOrDefault(id, SetCatalog.Count.ZERO);
            if (!old.equals(next)) Bukkit.getPluginManager().callEvent(new AccessorySetChangeEvent(owner, id, old.pieces(), next.pieces(), next.tiers()));
        }
    }
    private Map<Integer, SlotCheck.Result> fingerprint(Player owner, Map<Integer, SlotRequirementSpec> slots) {
        Map<Integer, SlotCheck.Result> result = new LinkedHashMap<>();
        slots.forEach((index, spec) -> result.put(index, requirements.evaluate(owner, spec).result()));
        return result;
    }
    private void updateWatch(Player owner, Map<Integer, SlotCheck> gates) {
        if (gates.isEmpty()) { watched.remove(owner.getUniqueId()); return; }
        var pages = profiles.pages(owner);
        Map<Integer, SlotRequirementSpec> slots = new LinkedHashMap<>();
        Map<Integer, SlotCheck.Result> results = new LinkedHashMap<>();
        gates.forEach((i, check) -> {
            slots.put(i, pages.slotRule(i / StorageLayout.PAGE_SLOTS + 1, i % StorageLayout.PAGE_SLOTS).requirements());
            results.put(i, check.result());
        });
        watched.put(owner.getUniqueId(), new Watch(slots, results, slots.values().stream().anyMatch(requirements::polled)));
    }
    public void invalidate(Player owner) {
        if (!watched.containsKey(owner.getUniqueId()) || !invalidations.add(owner)) return;
        scheduler.forPlayer(owner, () -> {
            invalidations.remove(owner);
            checkWatch(owner);
        }, () -> invalidations.remove(owner));
    }
    public void pollRequirements(Player owner) {
        var state = watched.get(owner.getUniqueId());
        if (state != null && state.polled()) checkWatch(owner);
    }
    private void checkWatch(Player owner) {
        var state = watched.get(owner.getUniqueId());
        if (state != null && !owner.isDead() && !state.results().equals(fingerprint(owner, state.slots()))) refreshCurrent(owner);
    }
    private void refreshCurrent(Player owner) {
        var current = store.get().cached(owner.getUniqueId());
        if (current != null && owner.isOnline() && !owner.isDead()) refresh(owner, current, null, AccessoryChangeCause.REQUIREMENT);
    }

    public void invalidateLoad(UUID owner) { loads.remove(owner); }

    public Map<Integer, ItemStack> activeEquipment(Player owner) {
        scheduler.requireOwner(owner);
        if (!profiles.isAllowed(owner, AccessoryAction.SKILLS)) return Map.of();
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        equipped.getOrDefault(owner.getUniqueId(), Map.of()).forEach((slot, item) ->
                result.put(layout.pageStart(slot.page()) + slot.slot(), item.clone()));
        return Collections.unmodifiableMap(result);
    }

    private Map<Slot, ItemStack> snapshot(ItemStack[] active) {
        Map<Slot, ItemStack> out = new LinkedHashMap<>();
        for (int i = 0; i < active.length; i++) if (active[i] != null)
            out.put(new Slot(i / StorageLayout.PAGE_SLOTS + 1, i % StorageLayout.PAGE_SLOTS), active[i].clone());
        return out;
    }

    private void notifyChanges(Player owner, Player actor, Map<Slot, ItemStack> previous,
                               Map<Slot, ItemStack> next, AccessoryChangeCause cause) {
        EquipmentChanges.between(owner, actor, previous, next, cause).forEach(Bukkit.getPluginManager()::callEvent);
    }

}
