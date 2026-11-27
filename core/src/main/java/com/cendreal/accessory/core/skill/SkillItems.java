package com.cendreal.accessory.core.skill;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** One current identifier: acc_item_id; display name is the explicit optional matching rule. */
public final class SkillItems {
    private final NamespacedKey key;
    private final NamespacedKey legacyKey;
    public SkillItems() {
        key = com.cendreal.accessory.core.util.AccessoryKeys.key("acc_item_id");
        legacyKey = com.cendreal.accessory.core.util.AccessoryKeys.key("dun_item_id");
    }
    public String identify(ItemStack item, SkillCatalog catalog) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        var meta = item.getItemMeta();
        String id = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id != null && !id.isBlank() && catalog.items().containsKey(id)) return id;
        String legacyId = meta.getPersistentDataContainer().get(legacyKey, PersistentDataType.STRING);
        if (legacyId != null && catalog.items().containsKey(legacyId)) return legacyId;
        return meta.displayName() == null ? null : catalog.names().get(SkillCatalog.normalize(PlainTextComponentSerializer.plainText().serialize(meta.displayName())));
    }
    public boolean stamp(ItemStack item, SkillCatalog catalog) {
        String id = identify(item, catalog);
        if (id == null || !catalog.items().containsKey(id)) return false;
        var meta = item.getItemMeta(); meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta); return true;
    }
}
