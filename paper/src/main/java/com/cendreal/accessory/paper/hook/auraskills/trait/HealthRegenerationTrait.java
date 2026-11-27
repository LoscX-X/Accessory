package com.cendreal.accessory.paper.hook.auraskills.trait;

import com.cendreal.accessory.paper.hook.auraskills.AccessoryTraits;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.bukkit.BukkitTraitHandler;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

public class HealthRegenerationTrait implements BukkitTraitHandler {
    private final AuraSkillsApi auraSkills;
    private final JavaPlugin plugin;
    private static final String META_ACCESSORY_REGEN = "accessory_regen";
    // Inject API dependency in constructor
    public HealthRegenerationTrait(JavaPlugin plugin, AuraSkillsApi auraSkills) {
        this.auraSkills = auraSkills;
        this.plugin = plugin;
    }

    @Override
    public Trait[] getTraits() {
        // An array containing your CustomTrait instance
        return new Trait[]{AccessoryTraits.HEAL_REGENERATION};
    }

    @Override
    public double getBaseLevel(Player player, Trait trait) {
        // The base value of your trait when its stat is at level 0, could be a
        // Minecraft default value or values from other plugins
        return 0;
    }

    @Override
    public void onReload(Player player, SkillsUser user, Trait trait) {
        // Method called when the value of the trait's parent stat changes
    }

    public void tick(Player p) {
        if (p.isDead()) return;

        SkillsUser user = auraSkills.getUser(p.getUniqueId());
        if (user == null || !user.isLoaded()) return;

        double hps = user.getEffectiveTraitLevel(AccessoryTraits.HEAL_REGENERATION);
        if (hps <= 0) return;

        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        double cur = p.getHealth();
        if (cur >= max) return;

        double healAmount = Math.min(hps, max - cur);

        // 标记：这是我们插件的 CUSTOM 回血
        p.setMetadata(META_ACCESSORY_REGEN, new FixedMetadataValue(plugin, true));
        try {
            EntityRegainHealthEvent event =
                    new EntityRegainHealthEvent(p, healAmount, EntityRegainHealthEvent.RegainReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                p.setHealth(Math.min(cur + event.getAmount(), max));
            }
        } finally {
            p.removeMetadata(META_ACCESSORY_REGEN, plugin);
        }
    }
}
