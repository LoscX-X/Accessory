package com.cendreal.accessory.core.attribute.loader;

import com.cendreal.accessory.api.extension.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.List;

/** Adapts the item and set contributions to providers which rebuild one owner's sources in a batch. */
public abstract class AbstractAttributeLoader<C> implements AccessoryAttributeProvider {
    @Override public final void rebuild(Player player, AccessoryEffects effects) {
        C context = begin(player);
        if (context == null) return;
        try {
            for (var entry : effects.items()) apply(context, entry.item(), entry.storageSlot());
            applyBonus(context, effects.entries(id()));
        } finally { finish(context); }
    }
    @Override public final void clear(Player player) { rebuild(player, AccessoryEffects.EMPTY); }
    protected abstract C begin(Player player);
    protected abstract void apply(C context, ItemStack item, int absoluteSlot);
    protected abstract void applyBonus(C context, List<AccessoryEffects.BonusLine> lines);
    protected void finish(C context) { }
}
