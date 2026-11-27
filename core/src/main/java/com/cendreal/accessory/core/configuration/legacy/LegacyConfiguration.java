package com.cendreal.accessory.core.configuration.legacy;

import com.cendreal.accessory.core.configuration.AccessoryPageManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.stream.IntStream;

/** Reads the former config/page/skill boundary without rewriting the administrator's files. */
public final class LegacyConfiguration {
    private LegacyConfiguration() { }

    public static boolean isLegacy(ConfigurationSection config) {
        return List.of("Lang", "title", "size", "pages", "database", "attribute-plus",
            "debug-mode", "skill-debug", "item-limits", "quick-equip", "anti-unequip",
            "vanilla-lore-attributes").stream().anyMatch(key -> config.contains(key, true));
    }

    public static ConfigurationSection main(JavaPlugin plugin, java.util.function.Supplier<String> legacyAttributeProvider) {
        ConfigurationSection source = plugin.getConfig();
        if (!isLegacy(source)) return source;
        YamlConfiguration result = copy(source);
        if (plugin.getConfig().getDefaults() != null) result.setDefaults(plugin.getConfig().getDefaults());
        alias(source, result, "Lang", "general.language");
        alias(source, result, "title", "inventory.title");
        alias(source, result, "size", "inventory.size");
        alias(source, result, "quick-equip", "inventory.quick-equip");
        alias(source, result, "anti-unequip", "inventory.anti-unequip");
        alias(source, result, "item-limits", "inventory.item-limits");
        alias(source, result, "vanilla-lore-attributes", "attribute.lore");
        alias(source, result, "database.mysql", "storage.mysql");
        if (!source.contains("storage.type", true) && source.contains("database.type", true)) {
            String type = source.getString("database.type", "yml").trim();
            result.set("storage.type", type.equalsIgnoreCase("ram") ? "only-ram" : type);
        }
        if (!source.contains("debug.enabled", true)) {
            result.set("debug.enabled", source.getBoolean("debug-mode", false) || source.getBoolean("skill-debug", false));
        }
        if (!source.contains("debug.categories", true)) {
            result.set("debug.categories", source.getBoolean("debug-mode", false)
                ? List.of("lifecycle", "inventory", "skills", "hooks", "storage") : List.of("skills"));
        }
        if (!source.contains("attribute.provider", true)) {
            String provider = source.getBoolean("attribute-plus.enable", false) ? "AttributePlus"
                : legacyAttributeProvider.get();
            result.set("attribute.provider", provider);
            if (!source.contains("attribute.vanilla", true)) result.set("attribute.vanilla", provider.equals("none"));
        }
        alias(source, result, "OUT_OF_COMBAT_SECONDS", "aura.physical-shield.out-of-combat-seconds");
        alias(source, result, "SHIELD_REGEN_PERCENT", "aura.physical-shield.regen-percent");
        alias(source, result, "MAGIC_SHIELD_OUT_OF_COMBAT_SECONDS", "aura.magic-shield.out-of-combat-seconds");
        alias(source, result, "MAGIC_SHIELD_REGEN_PERCENT", "aura.magic-shield.regen-percent");
        alias(source, result, "Life_Steal_demonstrate", "aura.life-steal.show-heal-message");
        // The old onDamaged contract included cancelled damage events.
        if (!source.contains("skills.triggers.cancelled-damage", true)) result.set("skills.triggers.cancelled-damage", true);
        for (String key : List.of("Lang", "title", "size", "pages", "database", "attribute-plus", "debug-mode",
            "skill-debug", "quick-equip", "anti-unequip", "item-limits", "vanilla-lore-attributes",
            "OUT_OF_COMBAT_SECONDS", "SHIELD_REGEN_PERCENT", "MAGIC_SHIELD_OUT_OF_COMBAT_SECONDS",
            "MAGIC_SHIELD_REGEN_PERCENT", "Life_Steal_demonstrate")) result.set(key, null);
        return result;
    }

    public static YamlConfiguration page(ConfigurationSection source) {
        YamlConfiguration result = copy(source);
        if (!source.contains("slots", true)) {
            if (source.isConfigurationSection("Accessory")) result.createSection("slots", values(source.getConfigurationSection("Accessory")));
            else if (source.isConfigurationSection("Cendreal-Accessory")) result.createSection("slots", values(source.getConfigurationSection("Cendreal-Accessory")));
        }
        result.set("Accessory", null);
        result.set("Cendreal-Accessory", null);
        ConfigurationSection frames = result.getConfigurationSection("frame");
        if (frames != null) for (String key : frames.getKeys(false)) {
            ConfigurationSection frame = frames.getConfigurationSection(key);
            if (frame == null || frame.contains("action", true)) continue;
            String drag = frame.getString("drag", "none");
            frame.set("action", switch (drag.toLowerCase(java.util.Locale.ROOT)) {
                case "pre_page", "previous_page" -> "previous_page";
                case "next_page" -> "next_page";
                default -> drag;
            });
        }
        return result;
    }

    public static ConfigurationSection profileInventory(AccessoryPageManager pages) {
        if (!pages.legacy()) return null;
        YamlConfiguration inventory = new YamlConfiguration();
        inventory.set("inventory.pages", IntStream.rangeClosed(1, pages.pageCount()).mapToObj(page -> pages.layout(page).id()).toList());
        inventory.set("inventory.title", ""); // Preserve each legacy page's own title/default.
        return inventory;
    }

    public static File skillDirectory(JavaPlugin plugin) {
        File legacy = new File(plugin.getDataFolder(), "skill");
        if (isLegacy(plugin.getConfig()) && !com.cendreal.accessory.core.configuration.ConfigFiles.yamlFiles(legacy).isEmpty()) return legacy;
        return new File(plugin.getDataFolder(), "skills");
    }

    public static ConfigurationSection itemLimits(JavaPlugin plugin) {
        ConfigurationSection source = plugin.getConfig();
        String path = isLegacy(source) && !source.contains("inventory.item-limits", true)
            ? "item-limits" : "inventory.item-limits";
        return source.getConfigurationSection(path);
    }

    private static void alias(ConfigurationSection source, YamlConfiguration target, String oldKey, String newKey) {
        if (!source.contains(oldKey, true)) return;
        ConfigurationSection section = source.getConfigurationSection(oldKey);
        if (section != null) {
            for (String child : section.getKeys(false)) alias(source, target, oldKey + "." + child, newKey + "." + child);
        } else if (!source.contains(newKey, true)) target.set(newKey, source.get(oldKey));
    }

    private static java.util.Map<String, Object> values(ConfigurationSection source) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String key : source.getKeys(false)) {
            if (!source.contains(key, true)) continue;
            ConfigurationSection child = source.getConfigurationSection(key);
            values.put(key, child == null ? source.get(key) : values(child));
        }
        return values;
    }

    private static YamlConfiguration copy(ConfigurationSection source) {
        YamlConfiguration result = new YamlConfiguration();
        values(source).forEach((key, value) -> {
            if (value instanceof java.util.Map<?, ?> section) result.createSection(key, section);
            else result.set(key, value);
        });
        return result;
    }
}
