package com.cendreal.accessory.core.configuration;


import com.cendreal.accessory.core.configuration.AccessoryPageManager;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.service.DefaultAccessorySlotTypes;
import com.cendreal.accessory.core.set.SetCatalog;

/** A completely parsed configuration, published only after validation succeeds. */
public record AccessoryConfiguration(AccessorySettings settings, AccessoryPageManager pages, Lang language,
                                     java.util.Map<String, java.util.Set<String>> itemTypes,
                                     SetCatalog sets) {
    public static AccessoryConfiguration read(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Supplier<String> legacyAttributeProvider) {
        AccessorySettings settings = AccessorySettings.read(
            com.cendreal.accessory.core.configuration.legacy.LegacyConfiguration.main(plugin, legacyAttributeProvider));
        AccessoryPageManager pages = new AccessoryPageManager(plugin.getDataFolder(), plugin.getLogger());
        if (com.cendreal.accessory.core.configuration.legacy.LegacyConfiguration.isLegacy(plugin.getConfig())
            && plugin.getConfig().contains("pages", true)) {
            pages.reloadLegacy(settings.gui(), plugin.getConfig().getInt("pages", 1));
        } else pages.reload(settings.gui());
        var types = DefaultAccessorySlotTypes.read(new java.io.File(plugin.getDataFolder(), "item-types.yml"), plugin.getLogger());
        var sets = SetCatalog.read(new java.io.File(plugin.getDataFolder(), "sets"), plugin.getLogger());
        return new AccessoryConfiguration(settings, pages, new Lang(plugin, settings.language()), types, sets);
    }
}
