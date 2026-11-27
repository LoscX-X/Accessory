package com.cendreal.accessory.core.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ItemDelivery {
    private ItemDelivery() { }
    public static boolean give(Player owner, ItemStack item) {
        var remainder = owner.getInventory().addItem(item.clone());
        remainder.values().forEach(left -> owner.getWorld().dropItemNaturally(owner.getLocation(), left));
        return !remainder.isEmpty();
    }
}
