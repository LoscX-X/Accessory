package com.cendreal.accessory.api.extension;

import org.bukkit.inventory.ItemStack;

public record AccessoryActiveItem(int storageSlot, ItemStack item) {
    public AccessoryActiveItem { item = item.clone(); }
    @Override public ItemStack item() { return item.clone(); }
}
