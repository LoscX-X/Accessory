package com.cendreal.accessory.core.menu;

import com.cendreal.accessory.core.configuration.AccessoryLayout.FrameItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessoryInventoryMenu {
    private final java.util.function.Supplier<com.cendreal.accessory.core.configuration.lang.Lang> lang;
    public AccessoryInventoryMenu(java.util.function.Supplier<com.cendreal.accessory.core.configuration.lang.Lang> lang) {
        this.lang = lang;
        this.itemFactory = new AccessoryInventoryItem(lang);
    }
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final AccessoryInventoryItem itemFactory;

    public Inventory create(org.bukkit.entity.Player viewer, UUID ownerId, int page, int totalPages, ItemStack[] contents,
                            Collection<Integer> disabledSlots, Collection<Integer> sealedSlots, com.cendreal.accessory.api.AccessoryViewMode mode,
                            com.cendreal.accessory.core.configuration.AccessoryPageManager pages) {
        AccessoryInventoryHolder holder = new AccessoryInventoryHolder(ownerId, page, totalPages, mode, pages);

        if (viewer != null) holder.viewer(viewer.getUniqueId());
        int size = pages.pageSize(holder.currentPage());
        Component title = title(viewer, pages.pageTitle(holder.currentPage()), holder.currentPage(), holder.totalPages());

        Inventory inventory = org.bukkit.Bukkit.createInventory(holder, size, title);
        holder.bindInventory(inventory);

        inventory.setContents(normalizeContents(contents, size));
        decorate(inventory, holder, disabledSlots, sealedSlots);

        return inventory;
    }

    public void decorate(Inventory inventory,
                         AccessoryInventoryHolder holder,
                         Collection<Integer> disabledSlots, Collection<Integer> sealedSlots) {
        holder.restoreConcealedContents(inventory);
        // Remove only our old decoration. Occupied slots always retain their real items.
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isDecoration(item)) inventory.setItem(slot, null);
        }
        applyFrames(inventory, holder);
        applyDisabledSlots(inventory, holder, disabledSlots);
        applyEquipmentSeals(inventory, holder, sealedSlots);
    }

    private void applyEquipmentSeals(Inventory inventory, AccessoryInventoryHolder holder, Collection<Integer> slots) {
        for (int slot : slots) {
            ItemStack original = inventory.getItem(slot);
            if (isEmpty(original) || isDecoration(original)) continue;
            holder.conceal(slot, original);
            inventory.setItem(slot, itemFactory.sealedItem(holder.viewer()));
        }
    }

    private void applyFrames(Inventory inventory, AccessoryInventoryHolder holder) {
        List<FrameItem> frameItems =
                holder.pages().frameItems(holder.currentPage(), inventory.getSize());

        for (FrameItem frameItem : frameItems) {
            ItemStack frame = itemFactory.frameItem(holder.viewer(),
                    frameItem.section(),
                    holder.currentPage(),
                    holder.totalPages()
            );

            for (int slot : frameItem.slots()) {
                if (isValidSlot(inventory, slot) && isEmpty(inventory.getItem(slot))) {
                    inventory.setItem(slot, frame.clone());
                }
            }
        }
    }

    private void applyDisabledSlots(Inventory inventory,
                                    AccessoryInventoryHolder holder,
                                    Collection<Integer> disabledSlots) {
        if (disabledSlots == null || disabledSlots.isEmpty()) {
            return;
        }

        ItemStack disabled = itemFactory.disabledItem(holder.viewer(),
                holder.pages().disabledSlotItemSection(holder.currentPage()),
                holder.currentPage(),
                holder.totalPages()
        );

        for (Integer slot : disabledSlots) {
            if (slot != null && isValidSlot(inventory, slot) && isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, disabled.clone());
            }
        }
    }

    private Component title(org.bukkit.entity.Player viewer, String raw, int currentPage, int totalPages) {

        return MINI_MESSAGE.deserialize(
                lang.get().template(viewer, raw).replace("{page}", String.valueOf(currentPage))
                        .replace("{max_page}", String.valueOf(totalPages))
        );
    }

    private ItemStack[] normalizeContents(ItemStack[] contents, int size) {
        ItemStack[] out = new ItemStack[size];

        if (contents == null || contents.length == 0) {
            return out;
        }

        int limit = Math.min(size, contents.length);
        for (int i = 0; i < limit; i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }

        return out;
    }

    private boolean isValidSlot(Inventory inventory, int slot) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private boolean isEmpty(ItemStack item) { return item == null || item.getType().isAir(); }

    private boolean isDecoration(ItemStack item) {
        if (isEmpty(item) || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(com.cendreal.accessory.core.util.AccessoryKeys.key("locked"), PersistentDataType.BYTE)
                || pdc.has(com.cendreal.accessory.core.util.AccessoryKeys.key("disabled"), PersistentDataType.BYTE);
    }
}
