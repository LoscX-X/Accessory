package com.cendreal.accessory.api;

import org.bukkit.inventory.ItemStack;
import java.util.Objects;

/** The equipped stack that started a skill, before any later equipment changes. */
public record AccessorySkillSource(int storageSlot, ItemStack item) {
    public static final String METADATA_KEY = "cendreal.accessory.skill-source";
    public AccessorySkillSource {
        if (storageSlot < 0) throw new IllegalArgumentException("Storage slot must be nonnegative");
        item = Objects.requireNonNull(item).clone();
    }
    @Override public ItemStack item() { return item.clone(); }
}
