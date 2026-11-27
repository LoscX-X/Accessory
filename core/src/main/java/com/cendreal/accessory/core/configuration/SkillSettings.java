package com.cendreal.accessory.core.configuration;
import org.bukkit.configuration.ConfigurationSection;
import com.cendreal.accessory.core.extension.ExtensionRegistry;

public record SkillSettings(boolean enabled, boolean cancelledDamage, boolean resetCooldownsOnQuit, String executor) {
    public static SkillSettings read(ConfigurationSection config) {
        return new SkillSettings(config.getBoolean("skills.enabled", true), config.getBoolean("skills.triggers.cancelled-damage", false),
                config.getBoolean("skills.cooldowns.reset-on-quit", true),
                ExtensionRegistry.identifier(java.util.Objects.toString(config.getString("skills.executor"), "").trim().toLowerCase(java.util.Locale.ROOT)));
    }
}
