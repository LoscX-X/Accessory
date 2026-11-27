package com.cendreal.accessory.paper.hook.mythicmobs;

import com.cendreal.accessory.core.skill.*;
import com.cendreal.accessory.paper.listener.*;
import com.cendreal.accessory.core.skill.trigger.SkillTrigger;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicSkillEvent;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import com.cendreal.accessory.api.AccessorySkills;

/** Translates external casts without feeding accessory casts back into the trigger chain. */
public final class MythicSkillListener implements Listener {
    private final java.util.function.Supplier<AccessorySkills> skills;
    public MythicSkillListener(java.util.function.Supplier<AccessorySkills> skills) { this.skills = skills; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkillCast(MythicSkillEvent event) {
        var metadata = event.getSkillMetadata();
        if (metadata.getMetadata(MythicSkillBackend.ACCESSORY_CAST_META).isPresent() || metadata.getCaster() == null
                || metadata.getCaster().getEntity() == null) return;
        if (!(BukkitAdapter.adapt(metadata.getCaster().getEntity()) instanceof Player player)) return;
        Entity target = null;
        if (metadata.hasEntityTargets()) for (var candidate : metadata.getEntityTargets()) {
            target = BukkitAdapter.adapt(candidate); if (target != null) break;
        }
        if (target == null && metadata.getTrigger() != null) target = BukkitAdapter.adapt(metadata.getTrigger());
        skills.get().trigger(player, "onSkillCast", target, target);
    }
}
