package com.cendreal.accessory.paper.hook.placeholderapi;

import com.cendreal.accessory.api.extension.*;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;

public final class PlaceholderBridge extends PlaceholderExpansion {
    private final AccessoryExtensions extensions;
    private final String name;
    public PlaceholderBridge(AccessoryExtensions extensions, String name) { this.extensions = extensions; this.name = name; }
    @Override public String getIdentifier() { return name; }
    @Override public String getAuthor() { return "Accessory+"; }
    @Override public String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }
    @Override public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        String lower = identifier.toLowerCase(Locale.ROOT);
        var placeholders = extensions.placeholders();
        if (!name.equals("acc")) {
            var shield = placeholders.get(name.equals("absorb") ? "shield" : "magicshield");
            return shield == null ? null : shield.resolve(player, lower);
        }
        String key = lower;
        while (!key.isEmpty()) {
            var placeholder = placeholders.get(key);
            if (placeholder != null) return placeholder.resolve(player, identifier.length() == key.length() ? "" : identifier.substring(key.length() + 1));
            int end = key.lastIndexOf('_');
            if (end < 0) break;
            key = key.substring(0, end);
        }
        return null;
    }
    private static Optional<String> value(Player owner, String argument) {
        if (argument.isBlank()) return Optional.empty();
        String parsed = PlaceholderAPI.setPlaceholders(owner, argument);
        if (parsed == null || parsed.equals(argument) || parsed.matches(".*%[^%]+%.*")) return Optional.empty();
        return Optional.of(parsed.trim());
    }
    public static Runnable install(Plugin target, AccessoryExtensions extensions) {
        extensions.levels(target, new AccessoryLevelSource() {
            public String id() { return "placeholder"; }
            public boolean polled() { return true; }
            public OptionalDouble level(Player owner, String arg) {
                try {
                    double number = Double.parseDouble(value(owner, arg).orElse(""));
                    return Double.isFinite(number) ? OptionalDouble.of(number) : OptionalDouble.empty();
                } catch (NumberFormatException invalid) { return OptionalDouble.empty(); }
            }
        });
        extensions.classes(target, new AccessoryClassSource() {
            public String id() { return "placeholder"; }
            public boolean polled() { return true; }
            public Optional<Set<String>> classes(Player owner, String arg) {
                return value(owner, arg).map(raw -> {
                    Set<String> classes = new HashSet<>();
                    for (var name : raw.split(",")) if (!name.isBlank()) classes.add(name.trim().toLowerCase(Locale.ROOT));
                    return Set.copyOf(classes);
                });
            }
        });
        List<PlaceholderBridge> bridges = new ArrayList<>();
        for (String id : List.of("acc", "absorb", "magicabsorb")) {
            var bridge = new PlaceholderBridge(extensions, id);
            if (bridge.register()) bridges.add(bridge);
        }
        return () -> bridges.forEach(PlaceholderBridge::unregister);
    }
}
