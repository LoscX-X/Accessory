package com.cendreal.accessory.core.storage;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** All backends exchange the same opaque Base64 inventory; only YML unwraps it on disk. */
public interface InventoryBackend extends AutoCloseable {
    String loadInventory(UUID owner) throws Exception;
    void saveInventory(UUID owner, String encoded) throws Exception;
    void deleteInventory(UUID owner) throws Exception;
    Set<UUID> players(Consumer<String> skipped) throws Exception;
    default void initialize() throws Exception { }
    default Set<String> activeLeaseOwners() throws Exception { return Set.of(); }
    @Override void close();

    static void addPlayer(Set<UUID> result, String key, Consumer<String> skipped) {
        try {
            UUID id = UUID.fromString(key);
            if (id.toString().equalsIgnoreCase(key)) { result.add(id); return; }
        } catch (IllegalArgumentException ignored) { }
        skipped.accept(key);
    }
}
