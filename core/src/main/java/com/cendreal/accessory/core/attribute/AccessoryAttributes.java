package com.cendreal.accessory.core.attribute;

import com.cendreal.accessory.api.extension.*;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Tracks the actual providers used by each session so switches and unregisters remove their old sources. */
public final class AccessoryAttributes {
    private final ExtensionRegistry registry;
    private final Supplier<AccessorySettings.Attributes> settings;
    private final Map<UUID, List<AccessoryAttributeProvider>> applied = new ConcurrentHashMap<>();
    public AccessoryAttributes(ExtensionRegistry registry, Supplier<AccessorySettings.Attributes> settings) {
        this.registry = registry; this.settings = settings;
    }
    private void clear(Player player, AccessoryAttributeProvider provider) {
        registry.guarded("attribute/" + provider.id(), () -> { provider.clear(player); return true; }, false);
    }
    public void retiring(Player player, AccessoryAttributeProvider provider) {
        List<AccessoryAttributeProvider> current = applied.get(player.getUniqueId());
        if (current != null && current.contains(provider)) {
            clear(player, provider);
            applied.put(player.getUniqueId(), current.stream().filter(value -> value != provider).toList());
        }
    }
    public boolean isReady(Player player) {
        var provider = registry.attribute(settings.get().provider());
        return provider == null || registry.guarded("attribute/" + provider.id(), () -> provider.isReady(player), false);
    }
    public void rebuild(Player player, AccessoryEffects effects) {
        var config = settings.get();
        var selected = new LinkedHashSet<AccessoryAttributeProvider>();
        if (config.vanilla()) selected.add(registry.attribute("vanilla"));
        if (!config.provider().equals("none")) selected.add(registry.attribute(config.provider()));
        selected.remove(null);
        var before = applied.put(player.getUniqueId(), List.copyOf(selected));
        if (before != null) for (var provider : before) if (!selected.contains(provider)) clear(player, provider);
        // Clean persistent vanilla modifiers from earlier sessions even if vanilla was turned off while offline.
        var vanilla = registry.attribute("vanilla");
        if (before == null && vanilla != null && !selected.contains(vanilla)) clear(player, vanilla);
        for (var provider : selected) registry.guarded("attribute/" + provider.id(), () -> {
            if (provider.isReady(player)) provider.rebuild(player, effects);
            return true;
        }, false);
    }
    public void clear(Player player) {
        var previous = applied.remove(player.getUniqueId());
        if (previous != null) previous.forEach(provider -> clear(player, provider));
    }
}
