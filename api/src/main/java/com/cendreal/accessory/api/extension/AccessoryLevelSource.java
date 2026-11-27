package com.cendreal.accessory.api.extension;

import java.util.OptionalDouble;
import org.bukkit.entity.Player;

public interface AccessoryLevelSource {
    String id();
    OptionalDouble level(Player owner, String argument);
    default boolean polled() { return false; }
}
