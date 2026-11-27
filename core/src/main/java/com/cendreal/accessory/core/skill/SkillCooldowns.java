package com.cendreal.accessory.core.skill;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Reserves before casting to block reentrant damage/skill chains, including zero-cooldown skills. */
public final class SkillCooldowns {
    private final Map<UUID, Map<String, Long>> ready = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> casting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public long remaining(UUID owner, String key, long tick) { return Math.max(0, ready.getOrDefault(owner, Map.of()).getOrDefault(key, 0L) - tick); }
    public boolean cast(UUID owner, String key, int seconds, long tick, BooleanSupplier action) {
        if (remaining(owner, key, tick) > 0 || !casting.add(owner)) return false;
        try {
            if (!action.getAsBoolean()) return false;
            if (seconds > 0) ready.compute(owner, (ignored, current) -> {
                var deadlines = current == null ? new java.util.concurrent.ConcurrentHashMap<String, Long>() : current;
                deadlines.put(key, tick + seconds * 20L);
                return deadlines;
            });
            return true;
        } finally { casting.remove(owner); }
    }
    public void clear(UUID owner) { ready.remove(owner); }
    public void clear() { ready.clear(); }
    public void expire(long tick) {
        ready.keySet().forEach(owner -> ready.computeIfPresent(owner, (ignored, values) -> {
            values.values().removeIf(deadline -> deadline <= tick);
            return values.isEmpty() ? null : values;
        }));
    }
}
