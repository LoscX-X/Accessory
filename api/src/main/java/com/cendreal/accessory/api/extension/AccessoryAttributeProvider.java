package com.cendreal.accessory.api.extension;

import org.bukkit.entity.Player;

public interface AccessoryAttributeProvider {
    String id();
    default boolean isReady(Player owner) { return true; }
    void rebuild(Player owner, AccessoryEffects effects);
    void clear(Player owner);
}
