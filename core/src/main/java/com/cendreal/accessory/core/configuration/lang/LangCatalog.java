package com.cendreal.accessory.core.configuration.lang;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One disk file per locale. The old catalogs are imported only when that file is first created. */
final class LangCatalog {
    private static final Map<String,String> LEGACY_KEYS = Map.ofEntries(
            Map.entry("TraitsCommand.75858abb0a01", "Shield_missing_permission"),
            Map.entry("TraitsCommand.dcf376644b4d", "Shield_unavailable"),
            Map.entry("TraitsCommand.fcd0d5399b35", "Shield_invalid_number"),
            Map.entry("TraitsCommand.fb6c572c0bc3", "Shield_unknown_player"),
            Map.entry("TraitsCommand.8234b381c950", "Shield_unknown_mode"),
            Map.entry("TraitsCommand.62224b6886a8", "Shield_valid_modes"),
            Map.entry("TraitsCommand.9932697d6580", "Shield_applied"),
            Map.entry("TraitsCommand.b03c1383714a", "Shield_mode"),
            Map.entry("TraitsCommand.c75253a709b5", "Shield_target"),
            Map.entry("TraitsCommand.2a89d50ee805", "Shield_value"),
            Map.entry("TraitsCommand.73ded8de2aee", "Shield_usage"),
            Map.entry("TraitsCommand.0ab782aa0de9", "Shield_give_arguments"),
            Map.entry("TraitsCommand.d14d5e5e1722", "Shield_givep_arguments"),
            Map.entry("AccessoryInventoryItem.77e0ea910065", "Slot_locked_name"));
    private final Map<String,YamlConfiguration> catalogs = new HashMap<>();
    LangCatalog(JavaPlugin plugin) {
        catalogs.put("zh_CN", load(plugin, "zh_CN"));
        for (String locale : ClientLanguage.translationCodes()) catalogs.put(locale, load(plugin, locale));
    }
    private static YamlConfiguration load(JavaPlugin plugin, String locale) {
        String resource = "lang/" + locale + ".yml";
        Path root = plugin.getDataFolder().toPath(), target = root.resolve(resource);
        try (var input = Objects.requireNonNull(plugin.getResource(resource), resource)) {
            var defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!Files.exists(target)) {
                String prefix = locale.equals("zh_CN") ? "lang/" : "i18n/" + locale + "/lang/";
                boolean imported = false;
                for (String old : List.of("Language/" + locale + ".yml", prefix + "code.yml", prefix + "templates.yml")) {
                    Path file = root.resolve(old);
                    if (!Files.isRegularFile(file)) continue;
                    imported = true;
                    var legacy = new YamlConfiguration(); legacy.load(file.toFile());
                    if (old.startsWith("Language/")) {
                        var messages = legacy.getConfigurationSection("Message");
                        if (messages != null) copy(messages, defaults, "messages.");
                    } else if (old.endsWith("code.yml")) {
                        LEGACY_KEYS.forEach((key, name) -> {
                            if (legacy.contains(key)) defaults.set("messages." + name, legacy.get(key));
                        });
                    } else if (!locale.equals("zh_CN")) copy(legacy, defaults, "templates.");
                }
                if (imported) {
                    Files.createDirectories(target.getParent());
                    Path temp = Files.createTempFile(target.getParent(), locale, ".tmp");
                    try {
                        Files.writeString(temp, defaults.saveToString(), StandardCharsets.UTF_8);
                        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target); }
                    } finally { Files.deleteIfExists(temp); }
                    plugin.getLogger().info("已合并到 " + resource + "，旧语言文件可以删除。");
                } else plugin.saveResource(resource, false);
            }
            var disk = new YamlConfiguration(); disk.load(target.toFile()); disk.setDefaults(defaults);
            return disk;
        } catch (Exception e) { throw new IllegalStateException("Cannot load " + target, e); }
    }
    private static void copy(ConfigurationSection from, YamlConfiguration to, String prefix) {
        for (String k : from.getKeys(true)) if (!from.isConfigurationSection(k)) to.set(prefix + k, from.get(k));
    }
    Object value(CommandSender viewer, String key) {
        Object base = catalogs.get("zh_CN").get(key);
        if (base instanceof String s && s.isEmpty() || base instanceof List<?> l && l.isEmpty()) return base;
        var selected = catalogs.get(ClientLanguage.code(viewer));
        Object result = selected.get(key);
        return result == null ? base : result;
    }
    String text(CommandSender viewer, String key, String fallback) {
        Object value = value(viewer, key);
        if (value instanceof ConfigurationSection s && "text".equalsIgnoreCase(s.getString("type", "text"))) value = s.get("text");
        return ClientLanguage.punctuation(value instanceof String s ? s : fallback);
    }
    List<String> lines(CommandSender viewer, String key) {
        if (!(value(viewer, key) instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).map(ClientLanguage::punctuation).toList();
    }
}
