package com.cendreal.accessory.core.storage;

import java.util.*;

/** Every decision is made against the backend's clock. A token fences every data mutation. */
public interface InventoryLease {
    record Claim(UUID token, String holder, long remainingMillis) {
        public Claim(UUID token, String holder) { this(token, holder, Long.MAX_VALUE); }
        public boolean acquired() { return token != null; }
    }
    void initializeLeases() throws Exception;
    Claim acquire(UUID player, String server, UUID proposed, int seconds) throws Exception;
    Set<UUID> renew(Map<UUID,UUID> owners, int seconds) throws Exception;
    void write(UUID player, UUID token, String encoded, boolean delete) throws Exception;
    String holder(UUID player) throws Exception;
    void release(UUID player, UUID token) throws Exception;

    final class Lost extends Exception {
        public Lost(String holder) { super(holder == null ? "(expired)" : holder); }
    }
}
