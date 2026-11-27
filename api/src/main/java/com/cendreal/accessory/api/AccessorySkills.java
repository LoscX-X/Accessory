package com.cendreal.accessory.api;
import org.bukkit.entity.Player;
import java.util.*;
/** Owner-region skill query/control contract; no MythicMobs types leak into the API. */
public interface AccessorySkills {
    boolean enabled();
    /** Effective equipment or its skill permissions changed; called on the owner region after the snapshot changes. */
    default void refreshEquipment(Player player) { }
    default void trigger(Player owner, String trigger, org.bukkit.entity.Entity target, org.bukkit.entity.Entity source) { }
    List<AccessorySkillInfo> getSkills(Player player);
    Optional<AccessorySkillInfo> getSkill(Player player, String skill);
    String formatCooldown(Player player, int displaySlot);
    void resetCooldowns(Player player);
    void resetCooldowns();
}
