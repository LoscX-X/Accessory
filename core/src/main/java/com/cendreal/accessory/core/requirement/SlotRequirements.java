package com.cendreal.accessory.core.requirement;

import com.cendreal.accessory.api.AccessorySlotTypes;
import com.cendreal.accessory.core.configuration.AccessoryLayout;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.util.LoreUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import static com.cendreal.accessory.core.requirement.SlotCheck.*;
import static com.cendreal.accessory.core.storage.StorageLayout.PAGE_SLOTS;

public final class SlotRequirements {
    private final AccessoryProfileManager profiles;
    private final ExtensionRegistry sources;
    private final AccessorySlotTypes types;
    public SlotRequirements(AccessoryProfileManager profiles, ExtensionRegistry sources, AccessorySlotTypes types) {
        this.profiles = profiles; this.sources = sources; this.types = types;
    }

    public SlotCheck check(Player owner, int page, int slot, ItemStack item) {
        var rule = profiles.pages(owner).slotRule(page, slot);
        return check(owner, page, slot, item, rule == null ? PASS : evaluate(owner, rule.requirements()));
    }
    /** Per-refresh results are shared by returns, effect selection and the next change fingerprint. */
    public Map<Integer, SlotCheck> equippedConditions(Player owner, ItemStack[] stored) {
        Map<Integer, SlotCheck> result = new LinkedHashMap<>();
        var pages = profiles.pages(owner);
        for (int i = 0; i < stored.length; i++) {
            if (stored[i] == null || stored[i].getType().isAir()) continue;
            var rule = pages.slotRule(i / PAGE_SLOTS + 1, i % PAGE_SLOTS);
            if (rule != null && rule.requirements() != SlotRequirementSpec.NONE) result.put(i, evaluate(owner, rule.requirements()));
        }
        return result;
    }
    public SlotCheck check(Player owner, int page, int slot, ItemStack item, SlotCheck conditions) {
        var pages = profiles.pages(owner);
        var rule = pages.slotRule(page, slot);
        if (rule == null || !profiles.isSlotAvailable(owner, page, slot) || pages.frameItemAt(page, slot, pages.pageSize(page)) != null)
            return denied(Denial.FRAME);
        SlotCheck placement = PASS;
        if (rule.permission() != null && !owner.hasPermission(rule.permission())) placement = denied(Denial.PERMISSION);
        if (placement == PASS) placement = matchesItem(rule, item);
        return placement.and(conditions);
    }
    private SlotCheck matchesItem(AccessoryLayout.SlotRule rule, ItemStack item) {
        if (!rule.types().isEmpty()) {
            var actual = types.typesOf(item);
            if (!actual.isEmpty()) return Collections.disjoint(rule.types(), actual) ? denied(Denial.TYPE) : PASS;
            if (rule.lore().isEmpty()) return denied(Denial.TYPE);
        }
        return rule.lore().isEmpty() || LoreUtils.matchesAnyKeyword(LoreUtils.plainLore(item), rule.lore()) ? PASS : denied(Denial.LORE);
    }
    public SlotCheck evaluate(Player owner, SlotRequirementSpec spec) {
        if (spec.invalid()) return unknown(Denial.LEVEL);
        SlotCheck check = PASS;
        for (var level : spec.levels()) {
            var ref = level.source();
            var source = sources.level(ref.id());
            var number = source == null ? OptionalDouble.empty() : sources.guarded("levels/" + ref.id(),
                    () -> source.level(owner, ref.argument()), OptionalDouble.empty());
            if (number.isEmpty() || !Double.isFinite(number.getAsDouble())) check = check.and(unknown(Denial.LEVEL));
            else if (number.getAsDouble() < level.min()) {
                String min = java.math.BigDecimal.valueOf(level.min()).stripTrailingZeros().toPlainString();
                return new SlotCheck(Result.NOT_MET, Denial.LEVEL, min);
            }
        }
        var role = spec.role();
        if (role == null) return check;
        var ref = role.source();
        var source = sources.role(ref.id());
        var actual = source == null ? Optional.<Set<String>>empty() : sources.guarded("classes/" + ref.id(),
                () -> source.classes(owner, ref.argument()).map(Set::copyOf), Optional.<Set<String>>empty());
        if (actual.isEmpty()) return check.and(unknown(Denial.CLASS));
        return actual.get().stream().map(id -> id.trim().toLowerCase(Locale.ROOT)).anyMatch(role.anyOf()::contains)
                ? check : check.and(denied(Denial.CLASS));
    }
    public boolean polled(SlotRequirementSpec spec) {
        for (var level : spec.levels()) {
            var src = sources.level(level.source().id());
            if (src != null && sources.guarded("levels/" + level.source().id(), src::polled, false)) return true;
        }
        if (spec.role() == null) return false;
        var src = sources.role(spec.role().source().id());
        return src != null && sources.guarded("classes/" + spec.role().source().id(), src::polled, false);
    }
}
