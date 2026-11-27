package com.cendreal.accessory.paper.hook.attributeplus;

import com.cendreal.accessory.api.extension.AccessoryEffects.BonusLine;
import com.cendreal.accessory.core.attribute.loader.AbstractAttributeLoader;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AttributePlusLoader extends AbstractAttributeLoader<AttributePlusLoader.Context> {
    @Override public String id() { return "attributeplus"; }


    private static final String SOURCE_PREFIX = "accessory:attribute_plus/slot";
    private static final String PLAYER_SOURCES_PDC_KEY = "attribute_plus_sources";

    private final Method getData;
    private final Method addItem;
    private final Method addLines;
    private final Method removeSource;
    private final Method update;
    record Context(Player player, Object data, Set<String> sources) { }

    /** Resolve the public API once, using the installed plugin's own classes. */
    public AttributePlusLoader(Plugin target) {
        try {
            Class<?> api = target.getClass().getClassLoader().loadClass("org.serverct.ersha.api.AttributeAPI");
            getData = api.getMethod("getAttrData", LivingEntity.class);
            Class<?> data = getData.getReturnType();
            addItem = api.getMethod("addSourceAttribute", data, String.class, ItemStack.class);
            addLines = api.getMethod("addSourceAttribute", data, String.class, List.class);
            removeSource = api.getMethod("takeSourceAttribute", data, String.class);
            update = api.getMethod("updateAttribute", LivingEntity.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported AttributePlus API", e);
        }
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AttributePlus call failed: " + method.getName(),
                    e.getCause() == null ? e : e.getCause());
        }
    }

    @Override
    protected Context begin(Player player) {
        Object data = invoke(getData, player);
        if (data == null) return null;
        clearAppliedSources(player, data);
        return new Context(player, data, new LinkedHashSet<>());
    }

    @Override
    protected void apply(Context context, ItemStack item, int slot) {
        String source = SOURCE_PREFIX + slot;
        invoke(addItem, context.data(), source, item);
        context.sources().add(source);
    }

    @Override
    protected void finish(Context context) {
        saveAppliedSources(context.player(), context.sources());
        invoke(update, context.player());
    }

    @Override protected void applyBonus(Context context, java.util.List<BonusLine> lines) {
        if (lines.isEmpty()) return;
        String source = "accessory:attribute_plus/set";
        invoke(addLines, context.data(), source, lines.stream().map(BonusLine::text).toList());
        context.sources().add(source);
    }

    private void clearAppliedSources(Player player, Object data) {
        NamespacedKey key = com.cendreal.accessory.core.util.AccessoryKeys.key(PLAYER_SOURCES_PDC_KEY);
        String raw = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return;

        for (String source : raw.split("\\n")) {
            String cleaned = source.trim();
            if (!cleaned.isEmpty()) {
                invoke(removeSource, data, cleaned);
            }
        }
    }

    private void saveAppliedSources(Player player, Set<String> currentSources) {
        NamespacedKey key = com.cendreal.accessory.core.util.AccessoryKeys.key(PLAYER_SOURCES_PDC_KEY);
        if (currentSources.isEmpty()) {
            player.getPersistentDataContainer().remove(key);
            return;
        }

        player.getPersistentDataContainer().set(
                key,
                PersistentDataType.STRING,
                String.join("\n", currentSources)
        );
    }
}
