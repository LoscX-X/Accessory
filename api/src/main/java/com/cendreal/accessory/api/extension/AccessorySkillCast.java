package com.cendreal.accessory.api.extension;

import com.cendreal.accessory.api.AccessorySkillSource;
import org.bukkit.entity.Entity;

public record AccessorySkillCast(String skill, Entity target, Entity trigger, AccessorySkillSource source,
                                 boolean afterDeath, boolean forceSync) { }
