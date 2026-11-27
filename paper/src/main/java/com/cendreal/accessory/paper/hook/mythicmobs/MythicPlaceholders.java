package com.cendreal.accessory.paper.hook.mythicmobs;

import com.cendreal.accessory.api.extension.AccessoryExtensions;
import io.lumine.mythic.api.MythicProvider;
import io.lumine.mythic.core.skills.placeholders.all.FunctionalEntityPlaceholder;
import org.bukkit.entity.Player;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MythicPlaceholders {
    private final Set<String> registered = new HashSet<>();
    private final AccessoryExtensions extensions;
    public MythicPlaceholders(AccessoryExtensions extensions) { this.extensions = extensions; }
    public void refresh() {
        var manager = MythicProvider.get().getPlaceholderManager();
        for (String key : extensions.placeholders().keySet()) {
            if (!registered.add(key)) continue;
            manager.register("caster." + key, new FunctionalEntityPlaceholder((entity, argument) -> {
                var provider = extensions.placeholders().get(key);
                if (provider == null || entity == null || !(entity.getBukkitEntity() instanceof Player player)) return "0";
                String result = provider.resolve(player, Objects.toString(argument, ""));
                return Objects.toString(result, "0");
            }));
        }
    }
}
