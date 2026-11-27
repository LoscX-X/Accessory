package com.cendreal.accessory.api.extension;

import java.util.*;

public record AccessoryEffects(List<AccessoryActiveItem> items, Map<String, List<BonusLine>> bonuses) {
    /** Stable set ID, tier and line index are encoded in key, independent of other active sets. */
    public record BonusLine(String key, String text) { }
    public static final AccessoryEffects EMPTY = new AccessoryEffects(List.of(), Map.of());
    public AccessoryEffects {
        items = List.copyOf(items);
        Map<String, List<BonusLine>> copy = new LinkedHashMap<>();
        bonuses.forEach((key, lines) -> copy.put(key, List.copyOf(lines)));
        bonuses = Map.copyOf(copy);
    }
    public List<BonusLine> entries(String providerId) { return bonuses.getOrDefault(providerId, List.of()); }
    public List<String> bonusLines(String providerId) { return entries(providerId).stream().map(BonusLine::text).toList(); }
}
