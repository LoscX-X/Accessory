package com.cendreal.accessory.core.inventory;

import com.cendreal.accessory.api.AccessoryAction;
import com.cendreal.accessory.core.requirement.SlotCheck;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.StorageLayout;
import com.cendreal.accessory.core.util.LoreUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.function.Supplier;
import com.cendreal.accessory.core.requirement.SlotRequirements;

/** Owner rules are shared by manual transfers and quick equip. A drag is validated as one candidate. */
public final class InventoryRules {
    private final AccessoryProfileManager profiles;
    private final SlotRestrictions restrictions;
    private final StorageLayout layout;
    private final ItemLimitManager limits;
    private final SlotRequirements requirements;
    private final Supplier<List<String>> antiUnequipLore;

    public InventoryRules(AccessoryProfileManager profiles, SlotRestrictions restrictions, StorageLayout layout,
                          ItemLimitManager limits, Supplier<List<String>> antiUnequipLore, SlotRequirements requirements) {
        this.profiles = Objects.requireNonNull(profiles);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.layout = Objects.requireNonNull(layout);
        this.limits = Objects.requireNonNull(limits);
        this.requirements = requirements;
        this.antiUnequipLore = Objects.requireNonNull(antiUnequipLore);
    }

    public boolean mayRemove(Player owner, int page, int slot, ItemStack item) {
        int index = layout.pageStart(page) + slot;
        if (restrictions.isEquipmentSealed(owner.getUniqueId(), index)) return false;
        List<String> tags = antiUnequipLore.get();
        return empty(item) || profiles.isAllowed(owner, AccessoryAction.UNEQUIP)
                && !restrictions.isItemLocked(owner.getUniqueId(), index)
                && (tags.isEmpty() || !LoreUtils.matchesAnyKeyword(LoreUtils.plainLore(item), tags));
    }
    public boolean mayPlace(Player owner, int page, int slot, ItemStack item) {
        return placementDenial(owner, page, slot, item).result() == SlotCheck.Result.MET;
    }
    public SlotCheck placementDenial(Player owner, int page, int slot, ItemStack item) {
        if (!profiles.isAllowed(owner, AccessoryAction.EQUIP) || restrictions.isItemLocked(owner.getUniqueId(), layout.pageStart(page) + slot))
            return SlotCheck.denied(SlotCheck.Denial.FRAME);
        return requirements.check(owner, page, slot, item);
    }
    public boolean candidateAllowed(Player owner, ItemStack[] full, Map<Integer, ItemStack> replacements) {
        ItemStack[] next = full.clone();
        replacements.forEach((slot, item) -> next[slot] = item);
        // Hidden profile slots do not count towards equipped limits.
        var pages = profiles.pages(owner);
        for (int i = 0; i < next.length; i++) {
            int page = i / StorageLayout.PAGE_SLOTS + 1, slot = i % StorageLayout.PAGE_SLOTS;
            if (!profiles.isSlotAvailable(owner, page, slot)
                    || page > pages.pageCount() || slot >= pages.pageSize(page)) next[i] = null;
        }
        for (var change : replacements.entrySet())
            if (limits.wouldExceedLimit(next, change.getKey(), change.getValue())) return false;
        return true;
    }
    public static boolean empty(ItemStack item) { return item == null || item.getType().isAir(); }
}
