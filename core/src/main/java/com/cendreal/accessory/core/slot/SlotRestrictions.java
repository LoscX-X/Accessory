package com.cendreal.accessory.core.slot;

import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * In-memory slot state: global and per-source disabled slots, item locks and equipment seals.
 * Only stores state and answers queries; callers own window commits, effect refreshes and timers.
 */
public final class SlotRestrictions {
    /** One sealed stack; the cancel action releases its expiry timer. */
    public static final class Seal {
        private final ItemStack expected;
        private Runnable cancel = () -> { };
        public Seal(ItemStack expected) { this.expected = expected.clone(); }
        public void onCancel(Runnable cancel) { this.cancel = Objects.requireNonNull(cancel); }
        public void cancel() { cancel.run(); }
    }
    private record ItemLock(ItemStack expected, long expiresAt) { }

    private final Function<UUID, ItemStack[]> contents;
    private final IntSupplier storageSize;
    private volatile Set<Integer> disabled = Set.of();
    private final Map<UUID, Map<String, Set<Integer>>> transientDisabled = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<Integer, ItemLock>>> itemLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<Integer, Seal>>> seals = new java.util.concurrent.ConcurrentHashMap<>();

    /** @param contents current stored contents of an owner, read on the server thread */
    public SlotRestrictions(Function<UUID, ItemStack[]> contents, IntSupplier storageSize) {
        this.contents = Objects.requireNonNull(contents);
        this.storageSize = Objects.requireNonNull(storageSize);
    }

    public Set<Integer> globalDisabled() { return disabled; }
    public void replaceGlobalDisabled(Set<Integer> next) { disabled = Collections.unmodifiableSet(new LinkedHashSet<>(next)); }
    public boolean isSlotDisabled(int slot) { return disabled.contains(slot); }

    public List<Integer> disabledSlots(UUID player) {
        Set<Integer> merged = new LinkedHashSet<>(disabled);
        transientDisabled.getOrDefault(Objects.requireNonNull(player), Map.of()).values().forEach(merged::addAll);
        return List.copyOf(merged);
    }
    public boolean isSlotDisabled(UUID player, int slot) {
        return disabled.contains(slot) || transientDisabled.getOrDefault(Objects.requireNonNull(player), Map.of())
                .values().stream().anyMatch(slots -> slots.contains(slot));
    }
    /** Effective disabled set if the given source's slots were replaced by {@code next}. */
    public Set<Integer> disabledWith(UUID player, String source, Set<Integer> next) {
        Set<Integer> effective = new LinkedHashSet<>(disabled);
        transientDisabled.getOrDefault(player, Map.of()).forEach((owner, values) -> { if (!owner.equals(source)) effective.addAll(values); });
        effective.addAll(next);
        return effective;
    }
    public Set<Integer> transientSlots(UUID player, String source) {
        return transientDisabled.getOrDefault(player, Map.of()).getOrDefault(source, Set.of());
    }
    public void putTransient(UUID player, String source, Set<Integer> slots) {
        if (slots.isEmpty()) {
            Map<String, Set<Integer>> current = transientDisabled.get(player);
            if (current == null) return;
            current.remove(source);
            if (current.isEmpty()) transientDisabled.remove(player);
        } else transientDisabled.computeIfAbsent(player, key -> new LinkedHashMap<>()).put(source, Set.copyOf(slots));
    }

    public void lock(UUID player, String source, int storageSlot, ItemStack expected, long expiresAt) {
        purgeLocks(player);
        itemLocks.computeIfAbsent(player, key -> new HashMap<>()).computeIfAbsent(source, key -> new HashMap<>())
                .put(storageSlot, new ItemLock(expected.clone(), expiresAt));
    }
    public void clearLocks(UUID player, String source) {
        Map<String, Map<Integer, ItemLock>> sources = itemLocks.get(player);
        if (sources == null) return;
        sources.remove(source);
        if (sources.isEmpty()) itemLocks.remove(player);
    }
    public void clearLocks(UUID player) { itemLocks.remove(player); }
    public boolean isItemLocked(UUID player, int storageSlot) {
        purgeLocks(player);
        Map<String, Map<Integer, ItemLock>> sources = itemLocks.get(Objects.requireNonNull(player));
        if (sources == null || storageSlot < 0 || storageSlot >= storageSize.getAsInt()) return false;
        ItemStack current = contents.apply(player)[storageSlot];
        return sources.values().stream().anyMatch(slots -> {
            ItemLock lock = slots.get(storageSlot);
            return lock != null && lock.expected().equals(current);
        });
    }
    private void purgeLocks(UUID player) {
        var sources = itemLocks.get(player);
        if (sources == null) return;
        long now = System.nanoTime();
        sources.values().forEach(slots -> slots.values().removeIf(lock -> now - lock.expiresAt() >= 0));
        sources.values().removeIf(Map::isEmpty);
        if (sources.isEmpty()) itemLocks.remove(player);
    }

    /** Stores a seal, cancelling any previous seal of the same source and slot. */
    public void seal(UUID player, String source, int storageSlot, Seal seal) {
        Seal previous = seals.computeIfAbsent(player, key -> new HashMap<>()).computeIfAbsent(source, key -> new HashMap<>())
                .put(storageSlot, seal);
        if (previous != null) previous.cancel();
    }
    public boolean hasSeals(UUID player, String source) {
        return seals.getOrDefault(player, Map.of()).containsKey(source);
    }
    public boolean isCurrentSeal(UUID player, String source, int storageSlot, Seal seal) {
        return seals.getOrDefault(player, Map.of()).getOrDefault(source, Map.of()).get(storageSlot) == seal;
    }
    /** Removes one seal without cancelling it (used by its own expiry). */
    public void unseal(UUID player, String source, int storageSlot) {
        Map<String, Map<Integer, Seal>> sources = seals.get(player);
        Map<Integer, Seal> slots = sources == null ? null : sources.get(source);
        if (slots == null) return;
        slots.remove(storageSlot);
        if (slots.isEmpty()) sources.remove(source);
        if (sources.isEmpty()) seals.remove(player);
    }
    /** Removes and cancels all seals of one source; returns whether any existed. */
    public boolean unseal(UUID player, String source) {
        Map<String, Map<Integer, Seal>> sources = seals.get(player);
        Map<Integer, Seal> removed = sources == null ? null : sources.remove(source);
        if (removed == null) return false;
        removed.values().forEach(Seal::cancel);
        if (sources.isEmpty()) seals.remove(player);
        return true;
    }
    public void forgetSeals(UUID player) {
        Map<String, Map<Integer, Seal>> sources = seals.remove(player);
        if (sources != null) sources.values().forEach(slots -> slots.values().forEach(Seal::cancel));
    }
    public void forgetAllSeals() {
        seals.values().forEach(sources -> sources.values().forEach(slots -> slots.values().forEach(Seal::cancel)));
        seals.clear();
    }
    public boolean isEquipmentSealed(UUID player, int storageSlot) {
        Map<String, Map<Integer, Seal>> sources = seals.get(Objects.requireNonNull(player));
        if (sources == null || storageSlot < 0 || storageSlot >= storageSize.getAsInt()) return false;
        ItemStack current = contents.apply(player)[storageSlot];
        return sources.values().stream().anyMatch(slots -> {
            Seal seal = slots.get(storageSlot);
            return seal != null && seal.expected.equals(current);
        });
    }
}