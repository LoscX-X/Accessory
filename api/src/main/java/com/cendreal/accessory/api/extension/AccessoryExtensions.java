package com.cendreal.accessory.api.extension;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Register and close contributions on the global thread. Each registration belongs to its plugin. */
public interface AccessoryExtensions {
    AccessoryRegistration attributes(Plugin owner, AccessoryAttributeProvider provider);
    AccessoryRegistration levels(Plugin owner, AccessoryLevelSource source);
    AccessoryRegistration classes(Plugin owner, AccessoryClassSource source);
    AccessoryRegistration items(Plugin owner, AccessoryItemIdentity identity);
    AccessoryRegistration skills(Plugin owner, AccessorySkillExecutor executor);
    AccessoryRegistration placeholder(Plugin owner, String key, AccessoryPlaceholder placeholder);
    Map<String, AccessoryPlaceholder> placeholders();
    /** May be requested from any thread. Rechecks occupied slots on the owner's region. */
    void invalidate(Player player);
}
