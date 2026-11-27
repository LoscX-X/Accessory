package com.cendreal.accessory.api.extension;

import java.util.Optional;
import java.util.Set;
import org.bukkit.entity.Player;

public interface AccessoryClassSource {
    String id();
    Optional<Set<String>> classes(Player owner, String argument);
    default boolean polled() { return false; }
}
