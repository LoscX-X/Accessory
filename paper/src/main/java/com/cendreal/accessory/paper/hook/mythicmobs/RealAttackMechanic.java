package com.cendreal.accessory.paper.hook.mythicmobs;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.utils.annotations.MythicMechanic;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

@MythicMechanic(name = "realattack")
public class RealAttackMechanic implements ITargetedEntitySkill {

    private final Plugin plugin;

    public RealAttackMechanic(Plugin plugin, MythicMechanicLoadEvent loader) {
        this.plugin = plugin;
    }

    @Override
    public SkillResult castAtEntity(SkillMetadata meta, AbstractEntity target) {
        if (meta == null || target == null) return SkillResult.CONDITION_FAILED;
        if (meta.getCaster() == null) return SkillResult.CONDITION_FAILED;

        Entity caster = BukkitAdapter.adapt(meta.getCaster().getEntity());
        Entity victim = BukkitAdapter.adapt(target);

        if (!(caster instanceof LivingEntity mob)) return SkillResult.CONDITION_FAILED;
        if (victim == null) return SkillResult.CONDITION_FAILED;
        Runnable attack = () -> {
            if (!Bukkit.isOwnedByCurrentRegion(mob) || !mob.isValid() || mob.isDead()
                    || !victim.isValid() || mob.getWorld() != victim.getWorld()) return;
            mob.swingMainHand();
            mob.attack(victim);
        };
        if (Bukkit.isOwnedByCurrentRegion(victim)) {
            if (!Bukkit.isOwnedByCurrentRegion(mob) || !mob.isValid() || mob.isDead()
                    || mob.getWorld() != victim.getWorld()) return SkillResult.CONDITION_FAILED;
            attack.run();
        } else if (!victim.getScheduler().execute(plugin, attack, null, 1)) return SkillResult.CONDITION_FAILED;

        return SkillResult.SUCCESS;
    }
}
