package com.cendreal.accessory.api.extension;

import org.bukkit.entity.Player;

public interface AccessorySkillExecutor {
    String id();
    boolean cast(Player caster, AccessorySkillCast cast);
}
