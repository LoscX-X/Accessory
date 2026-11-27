package com.cendreal.accessory.core.menu;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** The only UI-to-storage conversion. Decorations never enter persisted contents. */
public final class MenuSnapshot {
    private final NamespacedKey locked, disabled;
    public MenuSnapshot() { locked = com.cendreal.accessory.core.util.AccessoryKeys.key("locked"); disabled = com.cendreal.accessory.core.util.AccessoryKeys.key("disabled"); }
    public boolean decoration(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(locked, PersistentDataType.BYTE) || pdc.has(disabled, PersistentDataType.BYTE);
    }
    public ItemStack[] read(Inventory inventory) {
        ItemStack[] source = inventory.getContents(), result = new ItemStack[source.length];
        AccessoryInventoryHolder holder = inventory.getHolder() instanceof AccessoryInventoryHolder accessory ? accessory : null;
        for (int i = 0; i < source.length; i++) {
            // A seal is only a window overlay. Its real stack, never the BARRIER, is committed.
            if (holder != null && holder.hasSealedContents(i)) result[i] = holder.sealedContents(i);
            else if (source[i] != null && !decoration(source[i])) result[i] = source[i].clone();
        }
        return result;
    }
}
