package com.cendreal.accessory.paper.hook.auraskills;

import com.cendreal.accessory.api.extension.AccessoryPlaceholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/** Read live shield values on the owning region, and the last owner snapshot for asynchronous placeholders. */
final class ShieldValues implements AccessoryPlaceholder, Listener {
    private record Values(double current, double maximum) { }
    private final Map<UUID, Values> latest = new ConcurrentHashMap<>();
    private final ToDoubleFunction<Player> current, maximum;
    ShieldValues(ToDoubleFunction<Player> current, ToDoubleFunction<Player> maximum) { this.current = current; this.maximum = maximum; }
    void sample(Player player) { latest.put(player.getUniqueId(), new Values(current.applyAsDouble(player), maximum.applyAsDouble(player))); }
    @EventHandler public void quit(PlayerQuitEvent event) { latest.remove(event.getPlayer().getUniqueId()); }
    @Override public String resolve(Player player, String argument) {
        if (Bukkit.isOwnedByCurrentRegion(player)) sample(player);
        var value = latest.getOrDefault(player.getUniqueId(), new Values(0, 0));
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "", "shield", "magicshield" -> String.valueOf(value.current());
            case "current_shield" -> String.format("%.1f", value.current());
            case "max_shield" -> String.format("%.1f", value.maximum());
            case "shield_percent" -> value.maximum() <= 0 ? "0%" : String.format("%.1f%%", value.current() * 100 / value.maximum());
            default -> null;
        };
    }
}
