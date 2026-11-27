package com.cendreal.accessory.paper.hook.auraskills;

import com.cendreal.accessory.api.extension.*;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.event.skill.SkillLevelUpEvent;
import dev.aurelium.auraskills.api.event.user.UserLoadEvent;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import java.util.OptionalDouble;

public final class AuraLevels implements AccessoryLevelSource, Listener {
    private final AccessoryExtensions extensions;
    public AuraLevels(AccessoryExtensions extensions) { this.extensions = extensions; }
    @Override public String id() { return "auraskills"; }
    @Override public OptionalDouble level(Player player, String argument) {
        var api = AuraSkillsApi.get();
        var user = api.getUser(player.getUniqueId());
        if (user == null || !user.isLoaded() || argument.isBlank()) return OptionalDouble.empty();
        if (argument.equalsIgnoreCase("power")) return OptionalDouble.of(user.getPowerLevel());
        var skill = api.getGlobalRegistry().getSkill(key(argument));
        return skill == null ? OptionalDouble.empty() : OptionalDouble.of(user.getSkillLevel(skill));
    }
    static NamespacedId key(String raw) {
        String[] parts = raw.split(":", 2);
        return parts.length == 2 ? NamespacedId.of(parts[0], parts[1]) : NamespacedId.fromDefault(raw);
    }
    @EventHandler public void level(SkillLevelUpEvent event) { extensions.invalidate(event.getPlayer()); }
    @EventHandler public void loaded(UserLoadEvent event) { extensions.invalidate(event.getPlayer()); }
}
