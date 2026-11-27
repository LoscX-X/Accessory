package com.cendreal.accessory.core.skill;

import com.cendreal.accessory.core.configuration.ConfigFiles;
import com.cendreal.accessory.core.skill.trigger.SkillTrigger;
import org.bukkit.configuration.ConfigurationSection;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Validates complete files before publication. Conditions belong inside MythicMobs skill definitions. */
public record SkillCatalog(Map<String, List<SkillDefinition>> items, Map<String, String> names) {
    public SkillCatalog {
        Map<String, List<SkillDefinition>> copy = new LinkedHashMap<>();
        items.forEach((id, entries) -> copy.put(id, List.copyOf(entries)));
        items = Collections.unmodifiableMap(copy); names = Map.copyOf(names);
    }
    public record Reference(String key, SkillDefinition skill) { }
    /** An unqualified ID must be unique across items. item:ability resolves ambiguities explicitly. */
    public Reference resolve(String reference) {
        Reference found = null;
        for (var item : items.entrySet()) for (var skill : item.getValue()) {
            String key = item.getKey() + ":" + skill.id();
            if (key.equals(reference)) return new Reference(key, skill);
            if (skill.id().equals(reference)) {
                if (found != null) return null;
                found = new Reference(key, skill);
            }
        }
        return found;
    }
    public static SkillCatalog load(File directory, Logger logger) {
        return load(directory, logger, false);
    }
    public static SkillCatalog loadLegacy(File directory, Logger logger) {
        return load(directory, logger, true);
    }
    private static SkillCatalog load(File directory, Logger logger, boolean legacy) {
        Map<String, List<SkillDefinition>> items = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (File file : ConfigFiles.yamlFiles(directory)) {
            var config = ConfigFiles.load(file, logger);
            if (config == null) throw new IllegalArgumentException("Invalid skill file: " + file);
            read(config, file.getName(), items, names, legacy);
        }
        return new SkillCatalog(items, names);
    }
    public static SkillCatalog parse(ConfigurationSection config) {
        Map<String, List<SkillDefinition>> items = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        read(config, "skills", items, names, false); return new SkillCatalog(items, names);
    }
    private static void read(ConfigurationSection config, String file, Map<String, List<SkillDefinition>> items, Map<String, String> names, boolean legacy) {
        var section = config.getConfigurationSection("items");
        if (section == null) return;
        for (String item : section.getKeys(false)) {
            if (items.containsKey(item)) throw new IllegalArgumentException(file + ": duplicate item id " + item);
            String name = normalize(section.getString(item + ".name", ""));
            if (!name.isBlank() && names.putIfAbsent(name, item) != null) throw new IllegalArgumentException(file + ": duplicate item name " + name);
            List<SkillDefinition> parsed = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Object raw : section.getList(item + ".skills", List.of())) {
                if (!(raw instanceof Map<?, ?> entry)) throw new IllegalArgumentException(file + ": " + item + ".skills must contain objects");
                try {
                    String skill = Objects.toString(entry.get("skill"), "").trim();
                    if (skill.isEmpty()) throw new IllegalArgumentException("skill is required");
                    SkillTrigger trigger = SkillTrigger.parse(Objects.toString(entry.get("trigger"), ""));
                    String id = Objects.toString(entry.get("id"), skill + "@" + trigger.configName());
                    if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("duplicate/empty skill id " + id);
                    var target = entry.containsKey("target") ? SkillDefinition.Target.valueOf(entry.get("target").toString().toUpperCase(Locale.ROOT)) : SkillDefinition.Target.defaultFor(trigger);
                    if (!target.supports(trigger)) throw new IllegalArgumentException("target " + target + " is incompatible with " + trigger.configName());
                    int period = number(entry, "period", 20, 1, Integer.MAX_VALUE);
                    int cooldown = number(entry, "cooldown", 0, 0, Integer.MAX_VALUE);
                    int slot = number(entry, "cd", 0, 0, 10);
                    if (entry.containsKey("conditions")
                        && !(legacy && entry.get("conditions") instanceof Map<?, ?> conditions && conditions.isEmpty())) {
                        throw new IllegalArgumentException("non-empty conditions belong in the configured skill executor; cannot migrate them automatically");
                    }
                    boolean forceSync = bool(entry, "forcesync"), cancel = bool(entry, "cancelevent");
                    if ((forceSync || cancel) && trigger != SkillTrigger.ON_DEATH) throw new IllegalArgumentException("forcesync/cancelevent require onDeath");
                    parsed.add(new SkillDefinition(id, skill, trigger, target, period, cooldown, forceSync, cancel, slot,
                            Objects.toString(entry.get("cd-format"), "{cd}s")));
                } catch (IllegalArgumentException ex) { throw new IllegalArgumentException(file + ": items." + item + ": " + ex.getMessage(), ex); }
            }
            items.put(item, parsed);
        }
    }
    private static int number(Map<?, ?> entry, String key, int fallback, int min, int max) {
        if (!entry.containsKey(key)) return fallback;
        int value;
        try { value = Integer.parseInt(entry.get(key).toString()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return value;
    }
    private static boolean bool(Map<?, ?> entry, String key) {
        if (!entry.containsKey(key)) return false;
        if (!(entry.get(key) instanceof Boolean value)) throw new IllegalArgumentException(key + " must be true/false");
        return value;
    }
    public static String normalize(String name) {
        return name.replaceAll("<[^>]*>", "").replaceAll("(?i)[&§][0-9a-fk-orx]", "").trim();
    }
}
