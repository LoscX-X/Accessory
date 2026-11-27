package com.cendreal.accessory.core.attribute.loader;

import com.cendreal.accessory.api.extension.AccessoryEffects.BonusLine;
import com.cendreal.accessory.core.configuration.VanillaLoreSettings;
import com.cendreal.accessory.core.attribute.parser.VanillaLoreParser;
import com.cendreal.accessory.core.util.LoreUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies only Bukkit modifiers. Text parsing and configuration are separate components. */
public final class VanillaAttributeLoader extends AbstractAttributeLoader<VanillaAttributeLoader.Context> {
    private final JavaPlugin plugin;
    private volatile VanillaLoreParser lore;
    @Override public String id() { return "vanilla"; }
    public void configure(VanillaLoreSettings settings) { lore = new VanillaLoreParser(settings); }
    record Context(Player player, Map<Attribute, Map<NamespacedKey, AttributeModifier>> desired) { }

    public VanillaAttributeLoader(JavaPlugin plugin, VanillaLoreSettings settings) {
        this.plugin = plugin;
        lore = new VanillaLoreParser(settings);
    }

    @Override
    protected Context begin(Player player) {
        return new Context(player, new LinkedHashMap<>());
    }

    @Override
    protected void apply(Context context, ItemStack item, int slot) {
        var attributes = com.cendreal.accessory.api.AccessoryItemAttributes.modifiers(item);
        if (attributes != null) for (var entry : attributes.entries()) {
            AttributeModifier original = entry.getValue();
            String path = "accessory/slot" + slot + "/" + entry.getKey().getKey().asString().replace(':', '/')
                    + "/" + original.getKey().asString().replace(':', '/');
            add(context, entry.getKey(), path, original.getAmount(), original.getOperation());
        }
        for (var value : lore.parse(LoreUtils.plainLore(item))) {
            Attribute attribute = Registry.ATTRIBUTE.get(value.attribute());
            if (attribute == null) continue;
            String path = "accessory/lore/slot" + slot + "/" + Integer.toHexString(value.id().hashCode())
                    + "/" + value.attribute().asString().replace(':', '/');
            add(context, attribute, path, value.amount(), value.operation());
        }
    }

    @Override protected void applyBonus(Context context, java.util.List<BonusLine> lines) {
        for (var line : lines) for (var value : lore.parse(java.util.List.of(line.text()))) {
            Attribute attribute = Registry.ATTRIBUTE.get(value.attribute());
            if (attribute != null) add(context, attribute, "accessory/set/" + line.key() + "/" + Integer.toHexString(value.id().hashCode()) + "/" + value.attribute().asString().replace(':', '/'),
                    value.amount(), value.operation());
        }
    }

    private void add(Context context, Attribute attribute, String path, double amount, AttributeModifier.Operation operation) {
        NamespacedKey key = com.cendreal.accessory.core.util.AccessoryKeys.key(path);
        context.desired().computeIfAbsent(attribute, ignored -> new LinkedHashMap<>())
                .put(key, new AttributeModifier(key, amount, operation));
    }

    @Override
    protected void finish(Context context) {
        String namespace = com.cendreal.accessory.core.util.AccessoryKeys.NAMESPACE;
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = context.player().getAttribute(attribute);
            if (instance == null) continue;
            Map<NamespacedKey, AttributeModifier> desired = context.desired().getOrDefault(attribute, Map.of());
            for (AttributeModifier current : instance.getModifiers().stream().toList()) {
                NamespacedKey key = current.getKey();
                if (!key.getKey().startsWith("accessory/") || !(key.getNamespace().equalsIgnoreCase(namespace)
                        || key.getNamespace().equalsIgnoreCase(plugin.getName()))) continue;
                AttributeModifier next = desired.get(key);
                if (next == null || current.getAmount() != next.getAmount()
                        || current.getOperation() != next.getOperation()) instance.removeModifier(current);
            }
            for (AttributeModifier next : desired.values()) {
                if (instance.getModifier(next.getKey()) == null) instance.addModifier(next);
            }
        }
    }
}
