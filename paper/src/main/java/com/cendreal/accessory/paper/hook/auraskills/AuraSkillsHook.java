package com.cendreal.accessory.paper.hook.auraskills;

import com.cendreal.accessory.paper.hook.auraskills.AccessoryStats;
import com.cendreal.accessory.paper.hook.auraskills.AccessoryTraits;
import com.cendreal.accessory.paper.hook.auraskills.trait.ShieldTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.DefenceTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.HealingReductionTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.HealthRegenerationTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.HealthTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.LifeStealTrait;
import com.cendreal.accessory.paper.hook.auraskills.trait.MagicShieldTrait;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.util.DebugLog;
import com.cendreal.accessory.paper.command.AccessoryCommands;
import java.util.function.Supplier;

public final class AuraSkillsHook implements AutoCloseable {
    private final java.util.List<org.bukkit.event.Listener> listeners = new java.util.ArrayList<>();

    private final JavaPlugin plugin;
    private final Supplier<AccessorySettings> settings;
    private final Supplier<Lang> lang;
    private final DebugLog debug;
    private final AccessoryCommands commands;

    /** @param commands binds /shield and /magicshield to the created traits */
    public AuraSkillsHook(JavaPlugin plugin, Supplier<AccessorySettings> settings, Supplier<Lang> lang, DebugLog debug,
                          AccessoryCommands commands) {
        this.plugin = plugin;
        this.settings = settings;
        this.lang = lang;
        this.debug = debug;
        this.commands = commands;
    }

    public java.util.function.Consumer<org.bukkit.entity.Player> load(org.bukkit.plugin.Plugin target, com.cendreal.accessory.api.extension.AccessoryExtensions extensions) {
        try {
            AuraSkillsApi api = AuraSkillsApi.get();

            if (api == null) {
                plugin.getLogger().warning("AuraSkills API unavailable; custom traits are inactive.");
                return player -> {};
            }

            var registry = api.useRegistry("accessory", plugin.getDataFolder());
            registry.registerTrait(AccessoryTraits.HEAL_REGENERATION);
            registry.registerTrait(AccessoryTraits.DEFENCE);
            registry.registerTrait(AccessoryTraits.HEALTH);
            registry.registerTrait(AccessoryTraits.ABSORB);
            registry.registerTrait(AccessoryTraits.HEAL_DECREASE);
            registry.registerTrait(AccessoryTraits.LIFE_STEAL);
            registry.registerStat(AccessoryStats.CUSTOM_STAT);
            registry.registerTrait(AccessoryTraits.MAGICABSORB);

            HealthRegenerationTrait hr = new HealthRegenerationTrait(plugin, api);
            HealingReductionTrait dec = new HealingReductionTrait(plugin, api);
            DefenceTrait def = new DefenceTrait(plugin, api);
            HealthTrait health = new HealthTrait(plugin, api);
            LifeStealTrait life = new LifeStealTrait(plugin, api, settings, lang);
            ShieldTrait absorb = new ShieldTrait(plugin, api, settings);
            MagicShieldTrait ms = new MagicShieldTrait(plugin, api, settings);

            commands.configureShields(absorb::addShield, absorb::addShieldPercent, ms::addShield, ms::addShieldPercent);

            var shield = new ShieldValues(absorb::getCurrentShield, absorb::getMaxShield);
            var magic = new ShieldValues(ms::getCurrentShield, ms::getMaxShield);
            extensions.placeholder(target, "shield", shield);
            extensions.placeholder(target, "magicshield", magic);
            listen(shield);
            listen(magic);

            var handlers = api.getHandlers();
            handlers.registerTraitHandler(ms);
            handlers.registerTraitHandler(life);
            handlers.registerTraitHandler(hr);
            handlers.registerTraitHandler(dec);
            handlers.registerTraitHandler(def);
            handlers.registerTraitHandler(health);
            handlers.registerTraitHandler(absorb);

            listen(ms);
            listen(absorb);
            listen(health);
            listen(dec);

            plugin.getLogger().info("AuraSkills hook enabled.");
            return player -> { absorb.tick(player); ms.tick(player); hr.tick(player); shield.sample(player); magic.sample(player); };
        } catch (RuntimeException | LinkageError error) {
            debug.failure("hooks", "AuraSkills custom traits could not initialize", error);
            return player -> {};
        }
    }

    private void listen(org.bukkit.event.Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }
    @Override public void close() {
        listeners.forEach(org.bukkit.event.HandlerList::unregisterAll);
        listeners.clear();
    }
}
