package com.cendreal.accessory.api;

import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Keeps accessory attributes on the item without activating vanilla equipment modifiers. */
public final class AccessoryItemAttributes {
    private static final NamespacedKey KEY = new NamespacedKey("bla", "accessory_attributes");
    private AccessoryItemAttributes() { }

    /** Also migrates existing copies in place; their names, lore, prices and identity are retained. */
    public static boolean isolate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        var modifiers = meta.getAttributeModifiers();
        if (modifiers == null || modifiers.isEmpty()) return false;
        ItemStack carrier = new ItemStack(Material.PAPER);
        var carrierMeta = carrier.getItemMeta();
        carrierMeta.setAttributeModifiers(modifiers);
        carrier.setItemMeta(carrierMeta);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE_ARRAY, carrier.serializeAsBytes());
        meta.setAttributeModifiers(null);
        item.setItemMeta(meta);
        return true;
    }

    public static Multimap<Attribute, AttributeModifier> modifiers(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return null;
        byte[] stored = meta.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE_ARRAY);
        return stored == null ? meta.getAttributeModifiers()
                : ItemStack.deserializeBytes(stored).getItemMeta().getAttributeModifiers();
    }
}
