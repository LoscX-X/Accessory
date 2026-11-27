package com.cendreal.accessory.core.requirement;

import com.cendreal.accessory.core.extension.ExtensionRegistry;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;
import java.util.logging.Logger;

public record SlotRequirementSpec(List<Level> levels, Role role, boolean invalid) {
    public static final SlotRequirementSpec NONE = new SlotRequirementSpec(List.of(), null, false);
    public record Source(String id, String argument) {
        static Source parse(Object value) {
            if (!(value instanceof String raw)) throw new IllegalArgumentException("source is required");
            String[] parts = raw.trim().split(":", 2);
            return new Source(ExtensionRegistry.identifier(parts[0]), parts.length == 2 ? parts[1] : "");
        }
    }
    public record Level(Source source, double min) { }
    public record Role(Source source, Set<String> anyOf) { }

    private static Map<?, ?> fields(Object value) {
        if (value instanceof ConfigurationSection section) return section.getValues(false);
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("expected a map");
    }
    public static SlotRequirementSpec read(ConfigurationSection slot, String path, Logger logger) {
        if (!slot.contains("requirements")) return NONE;
        try {
            var requirements = fields(slot.get("requirements"));
            if (!Set.of("level", "class").containsAll(requirements.keySet()))
                throw new IllegalArgumentException("unknown requirement");
            List<Level> levels = new ArrayList<>();
            if (requirements.containsKey("level")) {
                var raw = requirements.get("level");
                var entries = raw instanceof List<?> list ? list : List.of(raw);
                if (entries.isEmpty()) throw new IllegalArgumentException("empty level list");
                for (var entry : entries) {
                    var values = fields(entry);
                    if (!(values.get("min") instanceof Number min) || !Double.isFinite(min.doubleValue()) || min.doubleValue() < 0)
                        throw new IllegalArgumentException("level.min must be a finite nonnegative number");
                    levels.add(new Level(Source.parse(values.get("source")), min.doubleValue()));
                }
            }
            Role role = null;
            if (requirements.containsKey("class")) {
                var values = fields(requirements.get("class"));
                if (!(values.get("any-of") instanceof List<?> list) || list.isEmpty())
                    throw new IllegalArgumentException("class.any-of must be a nonempty list");
                Set<String> ids = new LinkedHashSet<>();
                for (Object id : list) {
                    if (!(id instanceof String name) || name.isBlank()) throw new IllegalArgumentException("invalid class name");
                    ids.add(name.trim().toLowerCase(Locale.ROOT));
                }
                role = new Role(Source.parse(values.get("source")), Set.copyOf(ids));
            }
            return levels.isEmpty() && role == null ? NONE : new SlotRequirementSpec(List.copyOf(levels), role, false);
        } catch (IllegalArgumentException ex) {
            logger.warning(path + ".requirements: " + ex.getMessage() + " (UNKNOWN)");
            return new SlotRequirementSpec(List.of(), null, true);
        }
    }
}
