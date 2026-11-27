package com.cendreal.accessory.api;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
/** Owner-region quick equip. For writes competing with a remote editor, use AccessoryService.onOwner. */
public interface AccessoryQuickEquipService {
    boolean tryEquipMainHand(Player player);
    boolean equipToSlot(Player player, int page, int slot);
}
