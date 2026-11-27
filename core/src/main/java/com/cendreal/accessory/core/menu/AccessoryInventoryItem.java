package com.cendreal.accessory.core.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class AccessoryInventoryItem {
    private final java.util.function.Supplier<com.cendreal.accessory.core.configuration.lang.Lang> lang;
    public AccessoryInventoryItem(java.util.function.Supplier<com.cendreal.accessory.core.configuration.lang.Lang> lang) {
        this.lang = lang;
    }
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public ItemStack frameItem(ConfigurationSection section, int currentPage, int totalPages) {
        return frameItem(null, section, currentPage, totalPages);
    }
    public ItemStack disabledItem(ConfigurationSection section, int currentPage, int totalPages) {
        return disabledItem(null, section, currentPage, totalPages);
    }
    public ItemStack sealedItem() { return sealedItem(null); }
    public ItemStack markedItem(ConfigurationSection section, int currentPage, int totalPages, String... markers) {
        return markedItem(null, section, currentPage, totalPages, markers);
    }

    public ItemStack frameItem(org.bukkit.entity.Player viewer, ConfigurationSection section, int currentPage, int totalPages) {
        return markedItem(viewer, section, currentPage, totalPages, "locked");
    }

    public ItemStack disabledItem(org.bukkit.entity.Player viewer, ConfigurationSection section, int currentPage, int totalPages) {
        return markedItem(viewer, section, currentPage, totalPages, "disabled", "locked");
    }

    public ItemStack sealedItem(org.bukkit.entity.Player viewer) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI_MESSAGE.deserialize(lang.get().text(viewer, "Slot_locked_name")));
        meta.getPersistentDataContainer().set(com.cendreal.accessory.core.util.AccessoryKeys.key("locked"),
                PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack markedItem(org.bukkit.entity.Player viewer, ConfigurationSection section,
                                int currentPage,
                                int totalPages,
                                String... markers) {
        String typeName = section == null
                ? "BLACK_STAINED_GLASS_PANE"
                : section.getString("type", "BLACK_STAINED_GLASS_PANE");

        Material material = Material.matchMaterial(typeName, false);
        if (material == null || !material.isItem()) {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        boolean hideTooltip = section == null || section.getBoolean("hide-tooltip", true);
        meta.setHideTooltip(hideTooltip);

        int customModelData = section == null ? -1 : section.getInt("custom-model-data", -1);
        if (customModelData > 0) {
            var customModelDataComponent = meta.getCustomModelDataComponent();
            customModelDataComponent.setFloats(List.of((float) customModelData));
            meta.setCustomModelDataComponent(customModelDataComponent);
        }

        String name = section == null ? null : section.getString("name");
        if (name != null && !name.isBlank()) {
            meta.displayName(parse(viewer, name, currentPage, totalPages));
        }

        List<String> rawLore = section == null ? List.of() : section.getStringList("lore");
        if (!rawLore.isEmpty()) {
            List<Component> lore = new ArrayList<>(rawLore.size());
            for (String line : rawLore) {
                lore.add(parse(viewer, line, currentPage, totalPages));
            }
            meta.lore(lore);
        }

        for (String marker : markers) {
            if (marker == null || marker.isBlank()) {
                continue;
            }

            meta.getPersistentDataContainer().set(
                    com.cendreal.accessory.core.util.AccessoryKeys.key(marker),
                    PersistentDataType.BYTE,
                    (byte) 1
            );
        }

        item.setItemMeta(meta);
        return item;
    }

    private Component parse(org.bukkit.entity.Player viewer, String raw, int currentPage, int totalPages) {
        return MINI_MESSAGE.deserialize(
                lang.get().template(viewer, raw).replace("{page}", String.valueOf(currentPage))
                        .replace("{max_page}", String.valueOf(totalPages))
        );
    }
}
