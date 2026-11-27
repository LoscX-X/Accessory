package com.cendreal.accessory.core.service;

import com.cendreal.accessory.api.AccessorySlotTypes;
import com.cendreal.accessory.core.requirement.SlotTypeIds;
import com.cendreal.accessory.core.configuration.ConfigFiles;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.storage.StorageLayout;
import com.cendreal.accessory.core.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.io.File;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DefaultAccessorySlotTypes implements AccessorySlotTypes {
    private static final NamespacedKey TAG = AccessoryKeys.key("slot_types");
    private final AccessoryProfileManager profiles;
    private final TaskScheduler scheduler;
    private final ExtensionRegistry extensions;
    private final Supplier<Map<String, Set<String>>> mappings;
    public DefaultAccessorySlotTypes(AccessoryProfileManager profiles, TaskScheduler scheduler, ExtensionRegistry extensions,
                                     Supplier<Map<String, Set<String>>> mappings) {
        this.profiles = profiles; this.scheduler = scheduler; this.extensions = extensions; this.mappings = mappings;
    }
    public static Map<String, Set<String>> read(File file, Logger logger) {
        if (!file.exists()) return Map.of();
        var yaml = ConfigFiles.load(file, logger);
        if (yaml == null) throw new IllegalArgumentException("Invalid file: " + file);
        var items = yaml.getConfigurationSection("items");
        if (items == null) return Map.of();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        items.getValues(false).forEach((key, value) -> {
            try {
                if (!key.matches("[a-z0-9_-]+:[^\\s]+") || !(value instanceof List<?> list))
                    throw new IllegalArgumentException("expected a qualified item ID and a list of types");
                result.put(key, SlotTypeIds.read(list, problem -> logger.warning(file + " items." + key + ": " + problem)));
            } catch (IllegalArgumentException ex) { logger.warning(file + " items." + key + ": " + ex.getMessage()); }
        });
        return Map.copyOf(result);
    }
    @Override public Set<String> typesOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return Set.of();
        String tag = item.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        if (tag != null && !tag.isBlank()) {
            return SlotTypeIds.read(Arrays.asList(tag.split(",")), ignored -> {});
        }
        var mappingsNow = mappings.get();
        return mappingsNow.isEmpty() ? Set.of() : extensions.identify(item).map(mappingsNow::get).orElse(Set.of());
    }
    @Override public ItemStack tagItem(ItemStack item, Collection<String> types) {
        var copy = item.clone();
        var ids = SlotTypeIds.read(types, problem -> { throw new IllegalArgumentException(problem); });
        copy.editPersistentDataContainer(pdc -> {
            if (ids.isEmpty()) pdc.remove(TAG);
            else pdc.set(TAG, PersistentDataType.STRING, String.join(",", ids));
        });
        return copy;
    }
    @Override public Set<String> slotTypesOf(Player owner, int storageSlot) {
        scheduler.requireOwner(owner);
        if (storageSlot < 0) return Set.of();
        var pages = profiles.pages(owner);
        var rule = pages.slotRule(storageSlot / StorageLayout.PAGE_SLOTS + 1, storageSlot % StorageLayout.PAGE_SLOTS);
        return rule == null ? Set.of() : rule.types();
    }
}
