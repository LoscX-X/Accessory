package com.cendreal.accessory.paper.hook.auraskills.trait;

import com.cendreal.accessory.paper.hook.auraskills.AccessoryTraits;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.bukkit.BukkitTraitHandler;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.lang.Lang;
import java.util.function.Supplier;

@SuppressWarnings("deprecation")
public class LifeStealTrait implements BukkitTraitHandler, Listener {

    private final AuraSkillsApi auraSkills;
    private final JavaPlugin plugin;
    private final Supplier<AccessorySettings> settings;
    private final Supplier<Lang> lang;
    private static final String META_ACCESSORY_LIFESTEAL = "accessory_lifesteal";

    public LifeStealTrait(JavaPlugin plugin, AuraSkillsApi auraSkills, Supplier<AccessorySettings> settings, Supplier<Lang> lang) {
        this.settings = settings;
        this.lang = lang;
        this.auraSkills = auraSkills;
        this.plugin = plugin;
    }

    @Override
    public Trait[] getTraits() {
        return new Trait[]{ AccessoryTraits.LIFE_STEAL };
    }

    @Override
    public double getBaseLevel(Player player, Trait trait) {
        return 0.0;
    }

    @Override
    public void onReload(Player player, SkillsUser user, Trait trait) {}

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        // 如果你只想近战吸血，取消注释：
        // var c = event.getCause();
        // if (c != EntityDamageEvent.DamageCause.ENTITY_ATTACK && c != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0) return;

        SkillsUser user = auraSkills.getUser(attacker.getUniqueId());
        if (user == null || !user.isLoaded()) return;

        double pct = user.getEffectiveTraitLevel(AccessoryTraits.LIFE_STEAL); // 例：5 = 5%
        if (pct <= 0) return;

        double healAmount = finalDamage * (pct / 100.0);
        if (healAmount <= 0) return;

        var attr = attacker.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        double max = attr.getValue();
        double cur = attacker.getHealth();
        if (cur >= max) return;

        healAmount = Math.min(healAmount, max - cur);

        // 走事件：方便和 HEAL_DECREASE 等系统联动
        attacker.setMetadata(META_ACCESSORY_LIFESTEAL, new FixedMetadataValue(plugin, true));
        try {
            EntityRegainHealthEvent regain =
                    new EntityRegainHealthEvent(attacker, healAmount, EntityRegainHealthEvent.RegainReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(regain);

            if (!regain.isCancelled() && regain.getAmount() > 0) {
                attacker.setHealth(Math.min(cur + regain.getAmount(), max));
            }
        } finally {
            attacker.removeMetadata(META_ACCESSORY_LIFESTEAL, plugin);
        }

        if (settings.get().demonstrateLifeSteal()) {
            attacker.sendMessage(lang.get().lang(attacker, "Life_steal_success") + String.format("%.1f", healAmount));
        }
    }
}
