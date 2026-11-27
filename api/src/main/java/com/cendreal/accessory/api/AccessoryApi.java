package com.cendreal.accessory.api;

import org.bukkit.Bukkit;
import com.cendreal.accessory.api.extension.AccessoryExtensions;

/**
 * Entry point for other plugins. Registered in Bukkit's ServicesManager while accessory+ is enabled.
 * Follow the owner, viewer and global region contracts of each service.
 */
public interface AccessoryApi {
    String localeCode(org.bukkit.command.CommandSender viewer);
    AccessoryExtensions extensions();
    AccessorySlotTypes slotTypes();
    AccessoryService service();
    AccessoryProfiles profiles();
    /** Returns the registered skill provider or the built-in engine. */
    AccessorySkills skills();
    AccessoryQuickEquipService quickEquip();
    AccessorySkillOptions skillOptions();

    /** The enabled API, or {@code null} while accessory+ is absent or disabled. */
    static AccessoryApi get() {
        return Bukkit.getServicesManager().load(AccessoryApi.class);
    }
}
