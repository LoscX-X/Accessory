package com.cendreal.accessory.api;

import java.util.Collection;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface AccessorySlotTypes {
    /** Reads a PDC tag first, then the mapping keyed by item identity. */
    Set<String> typesOf(ItemStack item);
    /** Tags a copy. An empty collection clears the tag. */
    ItemStack tagItem(ItemStack item, Collection<String> types);
    /** Call on the owner's region. */
    Set<String> slotTypesOf(Player owner, int storageSlot);
}
