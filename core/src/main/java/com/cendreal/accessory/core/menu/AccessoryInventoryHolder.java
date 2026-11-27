package com.cendreal.accessory.core.menu;

import com.cendreal.accessory.api.AccessoryViewMode;
import com.cendreal.accessory.core.configuration.AccessoryPageManager;
import org.bukkit.inventory.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** One window's owner, layout and edit mode, plus real stacks hidden by temporary overlays. */
public final class AccessoryInventoryHolder implements InventoryHolder {
    private final UUID ownerId;
    private UUID viewerId;
    public void viewer(UUID viewer) { viewerId = viewer; }
    public org.bukkit.entity.Player viewer() { return org.bukkit.Bukkit.getPlayer(viewerId == null ? ownerId : viewerId); }
    private final int currentPage, totalPages;
    private final AccessoryViewMode mode;
    private final AccessoryPageManager pages;
    private final Map<Integer, ItemStack> sealedContents = new HashMap<>();
    private Inventory inventory;
    public AccessoryInventoryHolder(UUID owner, int page, int count, AccessoryViewMode mode, AccessoryPageManager pages) {
        ownerId = Objects.requireNonNull(owner); currentPage = Math.clamp(page, 1, Math.max(1, count));
        totalPages = Math.max(1, count); this.mode = Objects.requireNonNull(mode); this.pages = pages;
    }
    public UUID getOwnerId() { return ownerId; }
    public int currentPage() { return currentPage; }
    public int totalPages() { return totalPages; }
    public AccessoryViewMode mode() { return mode; }
    public AccessoryPageManager pages() { return pages; }
    public boolean hasSealedContents(int slot) { return sealedContents.containsKey(slot); }
    public ItemStack sealedContents(int slot) {
        ItemStack item = sealedContents.get(slot);
        return item == null ? null : item.clone();
    }
    public void conceal(int slot, ItemStack item) { sealedContents.put(slot, item.clone()); }
    public void restoreConcealedContents(Inventory target) {
        sealedContents.forEach((slot, item) -> target.setItem(slot, item.clone()));
        sealedContents.clear();
    }
    public void bindInventory(Inventory value) { inventory = value; }
    @Override public @NotNull Inventory getInventory() { return Objects.requireNonNull(inventory); }
}
