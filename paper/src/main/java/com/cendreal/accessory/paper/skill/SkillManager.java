package com.cendreal.accessory.paper.skill;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.api.event.AccessoryChangeCause;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.legacy.LegacyConfiguration;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.skill.*;
import com.cendreal.accessory.core.util.DebugLog;
import com.cendreal.accessory.paper.listener.SkillTriggerListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import com.cendreal.accessory.core.util.TaskScheduler;
import com.cendreal.accessory.core.util.ScheduledTask;
import java.io.File;
import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.api.extension.AccessorySkillCast;
import com.cendreal.accessory.api.extension.AccessorySkillExecutor;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.skill.trigger.SkillTrigger;

/**
 * Accessory's own skill provider. Owns dependency availability, event registration, timer and cleanup.
 * Uses a registered {@link AccessorySkills} service when one is available.
 */
public final class SkillManager implements AccessorySkills, SkillLoadout, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final AccessoryProfileManager profiles;
    private final Supplier<AccessorySettings> settings;
    private final DebugLog debug;
    private final Runnable reloadPlayers;
    private volatile SkillCatalog catalog = new SkillCatalog(Map.of(), Map.of());
    private volatile SkillEngine engine;
    private final TaskScheduler scheduler;
    private volatile long tick;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask clock;
    private final Map<UUID, ScheduledTask> timers = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExtensionRegistry extensions;
    private AccessorySkillExecutor activeExecutor;
    private boolean started;
    private final List<Listener> listeners = new ArrayList<>();

    /** @param reloadPlayers reloads online players' equipment after the skill engine becomes available */
    public SkillManager(JavaPlugin plugin, TaskScheduler scheduler, AccessoryProfileManager profiles, Supplier<AccessorySettings> settings,
                        DebugLog debug, Runnable reloadPlayers, ExtensionRegistry extensions) {
        this.extensions = extensions;
        this.plugin = Objects.requireNonNull(plugin);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.profiles = Objects.requireNonNull(profiles);
        this.settings = Objects.requireNonNull(settings);
        this.debug = Objects.requireNonNull(debug);
        this.reloadPlayers = Objects.requireNonNull(reloadPlayers);
    }

    public boolean hasSkill(String id) { return catalog.resolve(id) != null; }
    public SkillCatalog prepare() {
        File directory = LegacyConfiguration.skillDirectory(plugin);
        return directory.getName().equals("skill") ? SkillCatalog.loadLegacy(directory, plugin.getLogger())
            : SkillCatalog.load(directory, plugin.getLogger());
    }
    public void enable(SkillCatalog catalog) {
        this.catalog = catalog;
        started = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        start();
    }
    public void reload(SkillCatalog catalog) {
        this.catalog = catalog;
        if (!settings.get().skills().enabled()) { stop(); return; }
        extensionsChanged();
        if (engine == null) start(); else engine.catalog(catalog);
    }
    /** The active Bukkit service takes precedence over the built-in engine. */
    public AccessorySkills effective() {
        AccessorySkills external = Bukkit.getServicesManager().load(AccessorySkills.class);
        return external == null ? this : external;
    }

    private void start() {
        if (!started || Bukkit.getServicesManager().load(AccessorySkills.class) != null || engine != null || !settings.get().skills().enabled()) return;
        String id = settings.get().skills().executor();
        activeExecutor = extensions.executor(id);
        if (activeExecutor == null) return;
        try {
            var safe = new AccessorySkillExecutor() {
                public String id() { return id; }
                public boolean cast(Player owner, AccessorySkillCast cast) { return extensions.cast(id, owner, cast); }
            };
            engine = new SkillEngine(scheduler, profiles, settings, debug, safe, catalog, () -> tick);
            register(new SkillTriggerListener(engine, settings));
            SkillEngine active = engine;
            clock = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                if (++tick % 200 == 0) active.expire(tick);
            }, 1, 1);
            plugin.getLogger().info("Accessory skills enabled: " + id + ".");
        } catch (RuntimeException | LinkageError ex) {
            stop(); debug.failure("hooks", "Could not start accessory skills", ex);
        }
    }
    private void register(Listener listener) { listeners.add(listener); Bukkit.getPluginManager().registerEvents(listener, plugin); }
    public void extensionsChanged() {
        if (!started) return;
        if (extensions.executor(settings.get().skills().executor()) != activeExecutor) { stopEngine(); start(); }
    }
    private void stopEngine() {
        activeExecutor = null;
        if (clock != null) { clock.cancel(); clock = null; }
        timers.values().forEach(ScheduledTask::cancel);
        timers.clear();
        listeners.forEach(HandlerList::unregisterAll); listeners.clear();
        if (engine != null) { engine.close(); engine = null; }
    }
    private void stop() { stopEngine(); }

    @EventHandler public void providerRegistered(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == AccessorySkills.class) {
            stopEngine();
            plugin.getLogger().info("Accessory skills provided by " + event.getProvider().getPlugin().getName() + ".");
        }
    }
    @EventHandler public void providerUnregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == AccessorySkills.class && plugin.isEnabled()) { start(); reloadPlayers.run(); }
    }
    @Override public void trigger(Player owner, String name, org.bukkit.entity.Entity target, org.bukkit.entity.Entity source) {
        SkillEngine current = engine;
        if (current != null) current.trigger(owner, SkillTrigger.parse(name), target, source);
    }
    @Override public void equip(Player owner, ItemStack[] active, List<String> setSkills) { SkillEngine current = engine;
        if (current == null) return;
        current.equip(owner, active, setSkills);
        timers.computeIfAbsent(owner.getUniqueId(), id -> {
            long[] localTick = {tick};
            return scheduler.repeat(owner, () -> current.tick(owner, ++localTick[0]), 1, 1);
        }); }
    @Override public void detach(Player owner, AccessoryChangeCause cause) {
        ScheduledTask timer = timers.remove(owner.getUniqueId());
        if (timer != null) timer.cancel();
        if (engine != null) engine.detach(owner, cause == AccessoryChangeCause.QUIT || cause == AccessoryChangeCause.PLUGIN_DISABLE);
    }
    /** External providers receive changes here. The built-in engine reads loadouts directly. */
    @Override public void refreshEquipment(Player owner) {
        AccessorySkills external = Bukkit.getServicesManager().load(AccessorySkills.class);
        if (external != null && external != this) external.refreshEquipment(owner);
    }
    @Override public boolean stamp(ItemStack item) { return engine != null && engine.stamp(item); }
    @Override public boolean enabled() { return engine != null; }
    @Override public List<AccessorySkillInfo> getSkills(Player owner) { return engine == null ? List.of() : engine.skills(owner); }
    @Override public Optional<AccessorySkillInfo> getSkill(Player owner, String skill) {
        return skill == null ? Optional.empty() : getSkills(owner).stream().filter(info -> info.skill().equalsIgnoreCase(skill)).findFirst();
    }
    @Override public String formatCooldown(Player owner, int slot) { return engine == null ? "" : engine.format(owner, slot); }
    @Override public void resetCooldowns(Player owner) { if (engine != null) engine.reset(owner); }
    @Override public void resetCooldowns() { if (engine != null) engine.reset(); }
    @Override public void close() { started = false; stop(); HandlerList.unregisterAll(this); }
}
