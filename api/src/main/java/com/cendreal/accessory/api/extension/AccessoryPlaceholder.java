package com.cendreal.accessory.api.extension;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface AccessoryPlaceholder {
    /** A read-only callback. Return null for an unknown argument. */
    String resolve(Player player, String argument);
}
