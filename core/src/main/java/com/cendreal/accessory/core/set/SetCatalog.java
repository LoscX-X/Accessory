package com.cendreal.accessory.core.set;

import com.cendreal.accessory.api.extension.AccessoryActiveItem;
import com.cendreal.accessory.api.extension.AccessoryEffects.BonusLine;
import com.cendreal.accessory.core.configuration.ConfigFiles;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.util.AccessoryKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

public final class SetCatalog {
    private static final NamespacedKey SET_ID = AccessoryKeys.key("set_id");
    private record Bonus(Map<String, List<String>> lines, List<String> skills) { }
    private record Definition(String file, String name, Set<String> members, NavigableMap<Integer, Bonus> tiers) { }
    public record Count(int pieces, Set<Integer> tiers) {
        public static final Count ZERO = new Count(0, Set.of());
    }
    public record Active(Map<String, Count> counts, Map<String, List<BonusLine>> attributes, List<String> skills) {
        public static final Active EMPTY = new Active(Map.of(), Map.of(), List.of());
    }
    private final Map<String, Definition> sets;
    private SetCatalog(Map<String, Definition> sets) { this.sets = Collections.unmodifiableMap(sets); }
    public void validateSkills(java.util.function.Predicate<String> exists, java.util.function.BiConsumer<String, String> warn) {
        sets.forEach((id, def) -> def.tiers().forEach((tier, bonus) -> {
            for (String skill : bonus.skills()) if (!exists.test(skill))
                warn.accept("sets/" + def.file() + " " + id + ".bonuses." + tier + ".skills", skill);
        }));
    }
    public String name(String id) { var set = sets.get(id); return set == null ? null : set.name(); }

    public static SetCatalog read(File directory, Logger logger) {
        Map<String, Definition> sets = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (var file : ConfigFiles.yamlFiles(directory)) {
            var yaml = ConfigFiles.load(file, logger);
            if (yaml == null) throw new IllegalArgumentException("Invalid set file " + file);
            for (String id : yaml.getKeys(false)) {
                try {
                    ExtensionRegistry.identifier(id);
                    if (!seen.add(id)) throw new IllegalArgumentException("duplicate ID");
                    var section = Objects.requireNonNull(yaml.getConfigurationSection(id), "expected a set");
                    var members = Set.copyOf(strings(section, "members"));
                    if (members.isEmpty() || members.stream().anyMatch(value -> !value.matches("[a-z0-9_-]+:[^\\s]+")))
                        throw new IllegalArgumentException("members must contain qualified item IDs");
                    var bonuses = Objects.requireNonNull(section.getConfigurationSection("bonuses"), "expected bonuses");
                    var tiers = new TreeMap<Integer, Bonus>();
                    for (var key : bonuses.getKeys(false)) {
                        int tier = Integer.parseInt(key);
                        if (tier <= 0 || tiers.containsKey(tier)) throw new IllegalArgumentException("invalid tier " + key);
                        var bonus = Objects.requireNonNull(bonuses.getConfigurationSection(key), "expected a tier");
                        Map<String, List<String>> attributes = new LinkedHashMap<>();
                        var groups = bonus.getConfigurationSection("attributes");
                        if (groups != null) for (var provider : groups.getKeys(false))
                            attributes.put(ExtensionRegistry.identifier(provider), strings(groups, provider));
                        tiers.put(tier, new Bonus(Map.copyOf(attributes), strings(bonus, "skills")));
                    }
                    sets.put(id, new Definition(file.getName(), section.getString("name", id), members, tiers));
                } catch (IllegalArgumentException | NullPointerException ex) {
                    logger.warning(file + " set " + id + " skipped: " + ex.getMessage());
                }
            }
        }
        return new SetCatalog(sets);
    }
    private static List<String> strings(ConfigurationSection section, String key) {
        if (!section.contains(key)) return List.of();
        if (!(section.get(key) instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank()))
            throw new IllegalArgumentException(key + " must be a list of nonempty strings");
        return section.getStringList(key).stream().map(String::trim).toList();
    }

    public Active count(List<AccessoryActiveItem> items, ExtensionRegistry registry) {
        if (sets.isEmpty() || items.isEmpty()) return Active.EMPTY;
        var pieces = new LinkedHashMap<String, Set<Object>>();
        for (var entry : items) {
            ItemStack item = entry.item();
            String tagged = item.getPersistentDataContainer().get(SET_ID, PersistentDataType.STRING);
            var identity = registry.identify(item);
            // For a tagged vanilla item use its complete, single-item identity (including components).
            Object distinct = identity.<Object>map(id -> id).orElseGet(item::asOne);
            if (tagged != null) {
                if (sets.containsKey(tagged)) pieces.computeIfAbsent(tagged, ignored -> new HashSet<>()).add(distinct);
            } else identity.ifPresent(id -> sets.forEach((set, definition) -> {
                if (definition.members().contains(id)) pieces.computeIfAbsent(set, ignored -> new HashSet<>()).add(distinct);
            }));
        }
        Map<String, Count> counts = new LinkedHashMap<>();
        Map<String, List<BonusLine>> attributes = new LinkedHashMap<>();
        Set<String> skills = new LinkedHashSet<>();
        sets.forEach((id, definition) -> {
            int n = pieces.getOrDefault(id, Set.of()).size();
            if (n == 0) return;
            var active = definition.tiers().headMap(n, true);
            counts.put(id, new Count(n, Set.copyOf(active.keySet())));
            active.forEach((tier, bonus) -> {
                bonus.lines().forEach((provider, lines) -> {
                    var entries = attributes.computeIfAbsent(provider, ignored -> new ArrayList<>());
                    for (int i = 0; i < lines.size(); i++) entries.add(new BonusLine(id + "/" + tier + "/" + i, lines.get(i)));
                });
                skills.addAll(bonus.skills());
            });
        });
        attributes.replaceAll((key, lines) -> List.copyOf(lines));
        return new Active(Map.copyOf(counts), Map.copyOf(attributes), List.copyOf(skills));
    }
}
