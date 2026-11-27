package com.cendreal.accessory.core.skill;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.skill.trigger.SkillTrigger;
import com.cendreal.accessory.core.util.DebugLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.api.extension.AccessorySkillCast;
import com.cendreal.accessory.api.extension.AccessorySkillExecutor;
import com.cendreal.accessory.core.util.TaskScheduler;

/** Active loadouts and trigger dispatch; knows neither YAML parsing nor MythicMobs classes. */
public final class SkillEngine {
    private record Equipped(String key, SkillDefinition definition, AccessorySkillSource source) { }
    private final AccessoryProfileManager profiles;
    private final Supplier<AccessorySettings> settings;
    private final DebugLog debug;
    private final TaskScheduler scheduler;
    private final AccessorySkillExecutor backend;
    private final SkillItems items;
    private volatile SkillCatalog catalog;
    private final SkillCooldowns cooldowns = new SkillCooldowns();
    private final Map<UUID, List<Equipped>> loadouts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, AccessoryProfile> equippedProfiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> projectiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    public SkillEngine(TaskScheduler scheduler, AccessoryProfileManager profiles, Supplier<AccessorySettings> settings, DebugLog debug, AccessorySkillExecutor backend, SkillCatalog catalog, java.util.function.LongSupplier clock) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.profiles = profiles; this.settings = settings; this.debug = debug; this.backend = backend; this.catalog = catalog; items = new SkillItems();
    }
    public void catalog(SkillCatalog catalog) { this.catalog = catalog; loadouts.clear(); }
    public boolean stamp(ItemStack item) { return items.stamp(item, catalog); }
    public void equip(Player owner, ItemStack[] active, List<String> setSkills) {
        List<Equipped> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int slot = 0; slot < active.length; slot++) {
            ItemStack item = active[slot];
            String id = items.identify(item, catalog);
            if (id == null) continue;
            for (var skill : catalog.items().getOrDefault(id, List.of())) {
                String key = id + ":" + skill.id();
                // Copies of the same accessory share one ability and cooldown.
                if (seen.add(key)) resolved.add(new Equipped(key, skill, new AccessorySkillSource(slot, item)));
            }
        }
        for (String reference : setSkills) {
            var match = catalog.resolve(reference);
            if (match != null && seen.add(match.key())) resolved.add(new Equipped(match.key(), match.skill(), null));
        }
        loadouts.put(owner.getUniqueId(), List.copyOf(resolved));
        equippedProfiles.put(owner.getUniqueId(), profiles.resolve(owner));
    }
    public void detach(Player owner, boolean endSession) {
        loadouts.remove(owner.getUniqueId());
        equippedProfiles.remove(owner.getUniqueId());
        if (endSession && settings.get().skills().resetCooldownsOnQuit()) cooldowns.clear(owner.getUniqueId());
    }
    public void close() { loadouts.clear(); equippedProfiles.clear(); cooldowns.clear(); projectiles.clear(); }
    public void expire(long tick) {
        projectiles.values().removeIf(expiry -> expiry <= tick);
        cooldowns.expire(tick);
    }
    public void tick(Player owner, long tick) {
        if (owner.isDead()) return;
        for (Equipped ability : loadouts.getOrDefault(owner.getUniqueId(), List.of()))
            if (ability.definition().trigger() == SkillTrigger.ON_TIMER && tick % ability.definition().period() == 0)
                cast(owner, ability, owner, null);
    }
    public void trigger(Player owner, SkillTrigger trigger, Entity target, Entity triggerEntity) {
        scheduler.dispatch(owner, () -> {
            for (Equipped ability : loadouts.getOrDefault(owner.getUniqueId(), List.of()))
                if (ability.definition().trigger() == trigger) cast(owner, ability, target, triggerEntity);
        });
    }
    public boolean shouldCancelDeath(Player owner) {
        return allowed(owner) && loadouts.getOrDefault(owner.getUniqueId(), List.of()).stream().anyMatch(ability ->
                ability.definition().trigger() == SkillTrigger.ON_DEATH && ability.definition().cancelEvent()
                        && cooldowns.remaining(owner.getUniqueId(), ability.key(), clock.getAsLong()) == 0);
    }
    public boolean death(Player owner) {
        boolean cancelled = false;
        for (Equipped ability : loadouts.getOrDefault(owner.getUniqueId(), List.of()))
            if (ability.definition().trigger() == SkillTrigger.ON_DEATH)
                cancelled |= cast(owner, ability, owner, null) && ability.definition().cancelEvent();
        return cancelled;
    }
    public void shoot(Player owner, Entity projectile) {
        if (projectiles.putIfAbsent(projectile.getUniqueId(), clock.getAsLong() + 200) == null)
            trigger(owner, SkillTrigger.ON_SHOOT, projectile, null);
    }
    private boolean allowed(Player owner) { return owner.isOnline() && profiles.isAllowed(owner, AccessoryAction.SKILLS)
            && profiles.resolve(owner).equals(equippedProfiles.get(owner.getUniqueId())); }
    private boolean cast(Player owner, Equipped ability, Entity eventTarget, Entity trigger) {
        if (!allowed(owner)) return false;
        SkillDefinition skill = ability.definition();
        Entity target = switch (skill.target()) { case SELF -> owner; case NONE -> null; default -> eventTarget; };
        if (target != null && !Bukkit.isOwnedByCurrentRegion(target) || trigger != null && !Bukkit.isOwnedByCurrentRegion(trigger)) return false;
        try {
            boolean success = cooldowns.cast(owner.getUniqueId(), ability.key(), skill.cooldown(), clock.getAsLong(),
                    () -> backend.cast(owner, new AccessorySkillCast(skill.skill(), target, trigger, ability.source(), skill.trigger() == SkillTrigger.ON_DEATH, skill.forceSync())));
            if (success) debug.trace("skills", owner.getUniqueId() + ":" + skill.skill(),
                    () -> "cast player=" + owner.getName() + " skill=" + skill.skill() + " trigger=" + skill.trigger().configName());
            return success;
        } catch (RuntimeException | LinkageError ex) {
            debug.failure("skills", "Failed to cast " + skill.skill(), ex); return false;
        }
    }
    public List<AccessorySkillInfo> skills(Player owner) {
        if (owner == null) return List.of();
        return loadouts.getOrDefault(owner.getUniqueId(), List.of()).stream().map(ability -> {
            int remaining = (int) Math.ceil(cooldowns.remaining(owner.getUniqueId(), ability.key(), clock.getAsLong()) / 20.0);
            var skill = ability.definition();
            return new AccessorySkillInfo(skill.skill(), skill.trigger().configName(), skill.cooldown(), remaining, remaining == 0);
        }).toList();
    }
    public String format(Player owner, int slot) {
        if (owner == null || slot < 1 || slot > 10) return "";
        Map<Integer, Equipped> display = new LinkedHashMap<>();
        List<Equipped> automatic = new ArrayList<>();
        for (Equipped ability : loadouts.getOrDefault(owner.getUniqueId(), List.of())) {
            int fixed = ability.definition().displaySlot();
            if (fixed == 0) automatic.add(ability);
            else display.merge(fixed, ability, (a, b) -> a.definition().cooldown() >= b.definition().cooldown() ? a : b);
        }
        automatic.sort(Comparator.comparingInt(ability -> ability.definition().cooldown()));
        int index = 1;
        for (Equipped ability : automatic) {
            while (display.containsKey(index)) index++;
            if (index > 10) break;
            display.put(index++, ability);
        }
        Equipped ability = display.get(slot);
        if (ability == null) return "";
        var definition = ability.definition();
        return definition.displayFormat().replace("{cd}", String.valueOf((int) Math.ceil(cooldowns.remaining(owner.getUniqueId(), ability.key(), clock.getAsLong()) / 20.0)))
                .replace("{max}", String.valueOf(definition.cooldown())).replace("{skill}", definition.skill());
    }
    public void reset(Player owner) { if (owner != null) cooldowns.clear(owner.getUniqueId()); }
    public void reset() { cooldowns.clear(); }
}
