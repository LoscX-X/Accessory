package com.cendreal.accessory.paper.listener;

import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.skill.SkillEngine;
import java.util.function.Supplier;

import com.cendreal.accessory.core.skill.trigger.SkillTrigger;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

/** Bukkit trigger adapter. Equipment/session events are exclusively handled by AccessoryLifecycle. */
public final class SkillTriggerListener implements Listener {
    private final SkillEngine engine;
    private final Supplier<AccessorySettings> settings;
    public SkillTriggerListener(SkillEngine engine, Supplier<AccessorySettings> settings) { this.engine = engine; this.settings = settings; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        engine.trigger(player, SkillTrigger.ON_ATTACK, event.getEntity(), null);
        if (event.isCritical()) engine.trigger(player, SkillTrigger.ON_CRITICAL_HIT, event.getEntity(), event.getEntity());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void damaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled() && !settings.get().skills().cancelledDamage()) return;
        Entity attacker = event instanceof EntityDamageByEntityEvent damage ? damage.getDamager() : null;
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        engine.trigger(player, SkillTrigger.ON_DAMAGED, attacker, null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kill(EntityDeathEvent event) {
        if (event.getEntity().hasMetadata("cendreal.unpreventable-death")) return;
        Player player = event.getEntity().getKiller();
        if (player != null) engine.trigger(player, SkillTrigger.ON_KILL, event.getEntity(), event.getEntity());
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void death(PlayerDeathEvent event) {
        if (event.getEntity().hasMetadata("cendreal.unpreventable-death")) return;
        // Preserve the live-player context needed by death auras, rolling back if the cast fails.
        boolean reserved = engine.shouldCancelDeath(event.getEntity());
        if (reserved) event.setCancelled(true);
        boolean executed = engine.death(event.getEntity());
        if (reserved && !executed) event.setCancelled(false);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) engine.shoot(player, event.getEntity());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == Event.Result.DENY) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        engine.trigger(event.getPlayer(), SkillTrigger.ON_INTERACT, event.getPlayer(), null);
    }
}
