package com.cendreal.accessory.core.configuration;

import com.cendreal.accessory.core.configuration.AccessoryLayout;
import com.cendreal.accessory.core.configuration.AccessoryLayout.FrameItem;
import com.cendreal.accessory.core.configuration.AccessoryLayout.SlotRule;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.ConfigFiles;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Named layout registry and resolved page table; menu actions belong to the menu controller. */
public final class AccessoryPageManager {
    private final File directory;
    private final Logger logger;
    private Map<String, AccessoryLayout> layouts = Map.of();
    private List<AccessoryLayout> pages = List.of();
    private boolean legacy;
    public boolean legacy() { return legacy; }

    public AccessoryPageManager(File dataFolder, Logger logger) {
        directory = new File(dataFolder, "layouts");
        this.logger = logger;
    }

    public void reload(AccessorySettings.Gui defaults) {
        Map<String, AccessoryLayout> loaded = readLayouts(defaults);
        AccessoryLayout fallback = loaded.get("default");
        if (fallback == null) throw new IllegalArgumentException("layouts/default.yml is required");
        layouts = Map.copyOf(loaded);
        pages = List.of(fallback);
        legacy = false;
    }

    private Map<String, AccessoryLayout> readLayouts(AccessorySettings.Gui defaults) {
        Map<String, AccessoryLayout> loaded = new LinkedHashMap<>();
        for (File file : ConfigFiles.yamlFiles(directory)) {
            var config = ConfigFiles.load(file, logger);
            if (config == null) throw new IllegalArgumentException("Invalid layout: " + file);
            String name = file.getName();
            String id = name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (loaded.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate layout id '" + id + "': " + file.getName());
            }
            loaded.put(id, AccessoryLayout.read(id, config, defaults, logger));
        }

        return loaded;
    }

    public void reloadLegacy(AccessorySettings.Gui defaults, int count) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("Legacy pages must be between 1 and 100");
        Map<String, AccessoryLayout> loaded = readLayouts(defaults);
        Map<Integer, AccessoryLayout> byPage = new java.util.TreeMap<>();
        File legacyDirectory = new File(directory.getParentFile(), "page");
        for (File file : ConfigFiles.yamlFiles(legacyDirectory)) {
            var config = ConfigFiles.load(file, logger);
            if (config == null) throw new IllegalArgumentException("Invalid legacy page: " + file);
            int page = config.getInt("page", 0);
            if (page < 1 || page > count) throw new IllegalArgumentException("Legacy page number outside configured pages: " + file.getName());
            String id = "legacy-page-" + page;
            AccessoryLayout layout = AccessoryLayout.read(id,
                com.cendreal.accessory.core.configuration.legacy.LegacyConfiguration.page(config), defaults, logger);
            if (byPage.putIfAbsent(page, layout) != null) throw new IllegalArgumentException("Duplicate legacy page number: " + page);
            loaded.put(id, layout);
        }
        if (byPage.size() != count) throw new IllegalArgumentException("Legacy page/ must define every page from 1 through " + count);
        layouts = Map.copyOf(loaded);
        pages = List.copyOf(byPage.values());
        legacy = true;
        logger.info("Loaded legacy page/ layouts in memory: " + pages.stream().map(AccessoryLayout::size).toList() + "; original files unchanged.");
    }

    public AccessoryPageManager forProfile(List<String> names, String title) {
        AccessoryPageManager selected = new AccessoryPageManager(directory.getParentFile(), logger);
        selected.layouts = layouts;
        selected.legacy = legacy;
        List<AccessoryLayout> base = names.isEmpty() ? pages : names.stream().map(name -> {
            AccessoryLayout layout = layouts.get(name.toLowerCase(Locale.ROOT));
            if (layout == null) throw new IllegalArgumentException("Unknown profile layout: " + name);
            return layout;
        }).toList();
        selected.pages = title.isBlank() ? base : base.stream().map(layout -> new AccessoryLayout(layout.id(), title,
                layout.size(), layout.slots(), layout.frames(), layout.disabledItem())).toList();
        return selected;
    }

    public int pageCount() { return pages.size(); }
    public int normalizePage(int page) { return Math.clamp(page, 1, pageCount()); }
    public AccessoryLayout layout(int page) { return pages.get(normalizePage(page) - 1); }
    public Map<String, AccessoryLayout> layouts() { return layouts; }
    public int pageSize(int page) { return layout(page).size(); }
    public String pageTitle(int page) { return layout(page).title(); }
    public int maxPageSize() { return pages.stream().mapToInt(AccessoryLayout::size).max().orElse(9); }
    public List<Integer> configuredSlots(int page) { return List.copyOf(layout(page).slots().keySet()); }
    public boolean isSlotConfigured(int page, int slot) { return slotRule(page, slot) != null; }

    public List<String> requiredLore(int page, int slot) {
        SlotRule rule = slotRule(page, slot);
        return rule == null ? List.of() : rule.lore();
    }

    public String requiredPermission(int page, int slot) {
        SlotRule rule = slotRule(page, slot);
        return rule == null ? null : rule.permission();
    }

    public List<Integer> frameSlots(int page, int size) {
        return frameItems(page, size).stream().flatMap(item -> item.slots().stream()).filter(slot -> slot < size).distinct().toList();
    }

    public List<FrameItem> frameItems(int page, int size) { return layout(page).frames(); }

    public FrameItem frameItemAt(int page, int slot, int size) {
        if (slot < 0 || slot >= size) return null;
        return frameItems(page, size).stream().filter(item -> item.slots().contains(slot)).findFirst().orElse(null);
    }

    public ConfigurationSection disabledSlotItemSection(int page) { return layout(page).disabledItem(); }

    public SlotRule slotRule(int page, int slot) {
        return page < 1 || page > pageCount() ? null : layout(page).slots().get(slot);
    }
}
