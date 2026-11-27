package com.cendreal.accessory.core.skill;

import com.cendreal.accessory.api.event.AccessoryChangeCause;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** What equipment lifecycle needs from the skill system; the platform layer supplies the implementation. */
public interface SkillLoadout {
    void equip(Player owner, ItemStack[] active, java.util.List<String> setSkills);
    void detach(Player owner, AccessoryChangeCause cause);
    /** Notifies the active skill provider after the equipment snapshot changes. */
    void refreshEquipment(Player owner);
    /** Writes the skill item id onto a stack placed by quick equip; returns whether one was written. */
    boolean stamp(ItemStack item);
}
