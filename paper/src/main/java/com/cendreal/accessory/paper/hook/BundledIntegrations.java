package com.cendreal.accessory.paper.hook;

import com.cendreal.accessory.api.AccessorySkills;
import com.cendreal.accessory.api.extension.*;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.util.DebugLog;
import com.cendreal.accessory.paper.command.AccessoryCommands;
import com.cendreal.accessory.paper.hook.auraskills.*;
import com.cendreal.accessory.paper.hook.mythicmobs.*;
import com.cendreal.accessory.paper.hook.placeholderapi.PlaceholderBridge;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.*;

/** Optional API types live behind these entry points. The server installs only the selected target plugins. */
public final class BundledIntegrations implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ExtensionRegistry registry;
    private final Supplier<AccessorySettings> settings;
    private final Supplier<Lang> lang;
    private final DebugLog debug;
    private final AccessoryCommands commands;
    private final Supplier<AccessorySkills> skills;
    private final Map<Plugin, Runnable> installed = new LinkedHashMap<>();
    private volatile Consumer<Player> ticks = ignored -> {};
    private Runnable refreshPlaceholders = () -> {};

    public BundledIntegrations(JavaPlugin plugin, ExtensionRegistry registry, Supplier<AccessorySettings> settings,
            Supplier<Lang> lang, DebugLog debug, AccessoryCommands commands, Supplier<AccessorySkills> skills) {
        this.plugin = plugin; this.registry = registry; this.settings = settings; this.lang = lang;
        this.debug = debug; this.commands = commands; this.skills = skills;
    }
    public static String targetFor(String id) {
        return switch (id) {
            case "mythic" -> "MythicMobs";
            case "auraskills" -> "AuraSkills";
            case "attributeplus" -> "AttributePlus";
            case "placeholder" -> "PlaceholderAPI";
            default -> null;
        };
    }
    public static String legacyAttributeProvider() {
        return Bukkit.getPluginManager().isPluginEnabled("AuraSkills") ? "auraskills" : "none";
    }
    public void start() {
        for (Plugin target : Bukkit.getPluginManager().getPlugins()) if (target.isEnabled()) install(target);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    private void install(Plugin target) {
        if (installed.containsKey(target)) return;
        try {
            Runnable cleanup = switch (target.getName()) {
                case "AuraSkills" -> aura(target);
                case "MythicMobs" -> mythic(target);
                case "PlaceholderAPI" -> PlaceholderBridge.install(target, registry);
                case "AttributePlus" -> {
                    registry.attributes(target, new com.cendreal.accessory.paper.hook.attributeplus.AttributePlusLoader(target));
                    yield () -> {};
                }
                default -> null;
            };
            if (cleanup != null) installed.put(target, cleanup);
        } catch (RuntimeException | LinkageError failure) {
            registry.unregister(target);
            debug.failure("hooks", "Could not initialize " + target.getName() + " integration", failure);
        }
    }
    private Runnable aura(Plugin target) {
        var levels = new AuraLevels(registry);
        registry.levels(target, levels);
        registry.attributes(target, new AuraSkillsLoader(dev.aurelium.auraskills.api.AuraSkillsApi.get()));
        Bukkit.getPluginManager().registerEvents(levels, plugin);
        var hook = new AuraSkillsHook(plugin, settings, lang, debug, commands);
        ticks = hook.load(target, registry);
        return () -> {
            ticks = ignored -> {};
            HandlerList.unregisterAll(levels);
            hook.close();
            commands.configureShields(null, null, null, null);
        };
    }
    private Runnable mythic(Plugin target) {
        if (registry.executor("mythic") != null) return null;
        registry.skills(target, new MythicSkillBackend());
        registry.items(target, identity("mythic", new NamespacedKey("mythicmobs", "type")));
        var mechanics = new MythicMechanicListener(plugin);
        var listener = new MythicSkillListener(skills);
        Bukkit.getPluginManager().registerEvents(mechanics, plugin);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        var bridge = new MythicPlaceholders(registry);
        refreshPlaceholders = bridge::refresh;
        return () -> { refreshPlaceholders = () -> {}; HandlerList.unregisterAll(mechanics); HandlerList.unregisterAll(listener); };
    }
    public static AccessoryItemIdentity identity(String namespace, NamespacedKey key) {
        return new AccessoryItemIdentity() {
            public String namespace() { return namespace; }
            public Optional<String> id(ItemStack item) { return Optional.ofNullable(item.getPersistentDataContainer().get(key, PersistentDataType.STRING)); }
        };
    }
    @EventHandler public void enabled(PluginEnableEvent event) { install(event.getPlugin()); }
    @EventHandler public void disabled(PluginDisableEvent event) {
        var target = event.getPlugin();
        if (target == plugin) return;
        var cleanup = installed.remove(target);
        if (cleanup != null) cleanup.run();
        registry.unregister(target);
    }
    public void refresh() { refreshPlaceholders.run(); }
    public void tick(Player player) { ticks.accept(player); }
    @Override public void close() {
        installed.values().forEach(Runnable::run);
        installed.clear();
        HandlerList.unregisterAll(this);
    }
}
