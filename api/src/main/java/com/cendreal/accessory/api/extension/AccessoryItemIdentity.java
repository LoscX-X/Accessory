package com.cendreal.accessory.api.extension;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** A read-only lookup. Return an unqualified ID, preserving case. */
public interface AccessoryItemIdentity {
    String namespace();
    Optional<String> id(ItemStack item);
}
