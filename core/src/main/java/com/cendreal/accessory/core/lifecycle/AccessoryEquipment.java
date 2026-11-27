package com.cendreal.accessory.core.lifecycle;

import com.cendreal.accessory.core.inventory.ItemLimitManager;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.storage.StorageLayout;
import com.cendreal.accessory.core.requirement.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** One eligibility rule for attributes, tags and skills. Never changes stored items. */
final class AccessoryEquipment {
    private final SlotRequirements requirements;
    private final ItemLimitManager limits;

    AccessoryEquipment(SlotRequirements requirements, ItemLimitManager limits) {
        this.requirements = Objects.requireNonNull(requirements);
        this.limits = Objects.requireNonNull(limits);
    }

    ItemStack[] activeContents(Player owner, ItemStack[] stored, java.util.Map<Integer, SlotCheck> gates) {
        ItemStack[] active = new ItemStack[stored.length];
        for (int index = 0; index < stored.length; index++) {
            ItemStack item = stored[index];
            if (item == null || item.getType().isAir()) continue;
            int page = index / StorageLayout.PAGE_SLOTS + 1, slot = index % StorageLayout.PAGE_SLOTS;
            if (requirements.check(owner, page, slot, item, gates.getOrDefault(index, SlotCheck.PASS)).result() != SlotCheck.Result.MET) continue;
            active[index] = item.clone();
        }
        // Disabled/invalid slots must not consume the limit of an otherwise valid slot.
        var allowed = limits.allowedSlots(active);
        for (int index = 0; index < active.length; index++) if (!allowed.contains(index)) active[index] = null;
        return active;
    }
}
