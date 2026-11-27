package com.cendreal.accessory.core.storage;


import com.cendreal.accessory.core.configuration.AccessorySettings.CrossServer;
import java.util.function.BiConsumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Serial persistence, session cache, failed-write retention, and defensive snapshots. */
public final class AccessoryStore {
    public enum StorageType {
        MYSQL, REDIS, SQLITE, YML, ONLY_RAM;
        public static StorageType fromConfig(String raw) {
            if (raw == null) return YML;
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "mysql" -> MYSQL;
                case "redis" -> REDIS;
                case "yml" -> YML;
                case "sqlite" -> SQLITE;
                case "only-ram" -> ONLY_RAM;
                default -> throw new IllegalArgumentException("Unknown storage.type: " + raw);
            };
        }
    }
    private record Write(ItemStack[] contents, boolean delete, UUID token) { }
    private final JavaPlugin plugin;
    private final StorageType storageType;
    private final InventoryBackend backend;
    private final LeaseSessions sessions;
    private final BiConsumer<UUID,String> notification;
    private boolean maintenance;
    private final Set<UUID> cleared = new HashSet<>();
    private final Object lock = new Object();
    private final Map<UUID, ItemStack[]> cache = new HashMap<>();
    private static final class ReadSession {
        int pending;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
    }
    private final Map<UUID, ReadSession> reads = new HashMap<>();
    /** A tentative return leaves the authoritative cache intact until the owning region can deliver. */
    public final class ReturnWrite {
        private final UUID owner;
        private final ItemStack[] before, after;
        private boolean superseded;
        private ReturnWrite(UUID owner, ItemStack[] before, ItemStack[] after) {
            this.owner = owner; this.before = before; this.after = after;
        }
        public boolean finish(boolean deliver) {
            synchronized (lock) {
                if (!returns.remove(owner, this)) return false;
                var current = cache.get(owner);
                if (deliver && !superseded && current == before) {
                    cache.put(owner, after);
                    return true;
                }
                // A quit/clear already enqueued its own authoritative snapshot.
                if (current != null && !superseded) enqueue(owner, snapshot(owner, current, current.length));
                return false;
            }
        }
    }
    private final Map<UUID, ReturnWrite> returns = new HashMap<>();
    public ItemStack[] cached(UUID owner) {
        synchronized (lock) {
            var current = cache.get(owner);
            return current == null ? null : copy(current, current.length);
        }
    }
    public CompletableFuture<ReturnWrite> stageReturn(UUID owner, ItemStack[] expected, ItemStack[] replacement) {
        synchronized (lock) {
            if (maintenance) throw new IllegalStateException("Accessory storage migration in progress");
            var current = cache.get(owner);
            if (current == null || returns.containsKey(owner) || !Arrays.equals(current, expected))
                return CompletableFuture.completedFuture(null);
            var stage = new ReturnWrite(owner, current, copy(replacement, replacement.length));
            returns.put(owner, stage);
            if (storageType == StorageType.ONLY_RAM) return CompletableFuture.completedFuture(stage);
            Write proposed = snapshot(owner, stage.after, stage.after.length);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    persist(owner, proposed);
                    return stage;
                } catch (RuntimeException ex) {
                    synchronized (lock) { returns.remove(owner, stage); }
                    throw ex;
                }
            }, ioExecutor);
        }
    }
    private final Map<UUID, Write> pendingWrites = new HashMap<>();
    private final List<Integer> legacySizes;
    private final boolean stableSlots;
    private final ScheduledThreadPoolExecutor ioExecutor = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "accessory-io");
        thread.setDaemon(true);
        return thread;
    });
    private final BiConsumer<UUID,Integer> saved;

    public AccessoryStore(JavaPlugin plugin, StorageType type, InventoryBackend backend, boolean stableSlots,
                          List<Integer> legacySizes, BiConsumer<UUID,Integer> saved, CrossServer cross,
                          BiConsumer<UUID,String> notification) {
        this.plugin = Objects.requireNonNull(plugin);
        this.storageType = type;
        this.backend = type == StorageType.ONLY_RAM ? null : Objects.requireNonNull(backend);
        this.stableSlots = stableSlots;
        this.legacySizes = List.copyOf(legacySizes);
        this.saved = saved;
        this.notification = notification;
        ioExecutor.setRemoveOnCancelPolicy(true);
        ioExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        sessions = cross == null ? null : new LeaseSessions((InventoryLease) backend, cross, ioExecutor,
                this::active, notification, this::leaseExpired, plugin.getLogger());
    }

    private boolean active(UUID id) {
        synchronized (lock) { return cache.containsKey(id) || reads.containsKey(id); }
    }

    public boolean ready(UUID id) {
        synchronized (lock) { return sessions == null || cache.containsKey(id) && sessions.token(id) != null; }
    }

    private Write snapshot(UUID id, ItemStack[] contents, int size) {
        return new Write(copy(contents, size), cleared.contains(id) && storageType != StorageType.YML,
                sessions == null ? null : sessions.token(id));
    }

    public void preload(UUID player, int size) { readAsync(player, size).exceptionally(ex -> { log("load", player, ex); return null; }); }

    public CompletableFuture<ItemStack[]> readAsync(UUID player, int size) {
        final ReadSession session;
        synchronized (lock) {
            if (maintenance) throw new IllegalStateException("Accessory storage migration in progress");
            var cached = cache.get(player);
            if (cached != null) return CompletableFuture.completedFuture(copy(cached, size));
            session = reads.computeIfAbsent(player, ignored -> new ReadSession());
            if (session.pending++ == 0) {
                if (sessions == null) session.ready.complete(null);
                else sessions.ensure(player, () -> { synchronized (lock) { return reads.get(player) == session; } }, session.ready);
            }
        }
        try {
            var future = session.ready.thenApplyAsync(ignored -> {
                synchronized (lock) {
                    ItemStack[] current = cache.get(player);
                    if (current != null) return copy(current, size);
                }
                Write retained;
                synchronized (lock) { retained = pendingWrites.get(player); }
                if (sessions != null && retained != null) {
                    // Retry under the original token before exposing a snapshot from an earlier session.
                    try { persist(player, retained); }
                    catch (CompletionException ex) { if (!(ex.getCause() instanceof InventoryLease.Lost)) throw ex; }
                    synchronized (lock) { pendingWrites.remove(player, retained); }
                }
                ItemStack[] loaded;
                synchronized (lock) {
                    Write pending = pendingWrites.get(player);
                    loaded = pending == null ? null : copy(pending.contents(), size);
                }
                if (loaded == null) loaded = load(player, size); // A failed read never becomes an empty inventory.
                synchronized (lock) {
                    if (reads.get(player) == session) {
                        cache.putIfAbsent(player, loaded);
                        return copy(cache.get(player), size);
                    }
                    return copy(loaded, size); // Session consumer also validates its request token.
                }
            }, ioExecutor);
            // Observe the source future, so cancelling the returned read also releases its token.
            future.whenComplete((ignored, failure) -> finishRead(player, session));
            return future;
        } catch (RuntimeException failure) {
            finishRead(player, session);
            throw failure;
        }
    }

    private void finishRead(UUID player, ReadSession session) {
        synchronized (lock) {
            if (--session.pending == 0 && reads.remove(player, session) && !cache.containsKey(player))
                releaseWhenIdle(player);
        }
    }

    public void getSliceOrLoadAsync(UUID player, int start, int size, int total, Executor callbacks, Consumer<ItemStack[]> callback) {
        readAsync(player, total).thenAccept(full -> {
            if (plugin.isEnabled()) callbacks.execute(() -> callback.accept(slice(full, start, size)));
        }).exceptionally(ex -> { log("load", player, ex); return null; });
    }
    public ItemStack[] getOrLoad(UUID player, int size) {
        if (sessions == null) return readAsync(player, size).join();
        synchronized (lock) {
            if (maintenance) throw new IllegalStateException("Accessory storage migration in progress");
            if (cache.containsKey(player)) return copy(cache.get(player), size);
            if (reads.containsKey(player)) throw new IllegalStateException("Accessory inventory is synchronizing");
        }
        return offline(player, token -> load(player, size));
    }

    /** An offline synchronous API operation tries the lease once and releases it on success. */
    private <T> T offline(UUID id, java.util.function.Function<UUID,T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID token = sessions.acquireOnce(id);
                if (token == null) throw new IllegalStateException("Accessory inventory is held by another server");
                Write retained;
                synchronized (lock) { retained = pendingWrites.get(id); }
                if (retained != null) {
                    try { persist(id, retained); }
                    catch (CompletionException ex) { if (!(ex.getCause() instanceof InventoryLease.Lost)) throw ex; }
                    synchronized (lock) { pendingWrites.remove(id, retained); }
                }
                T result = action.apply(token);
                sessions.releaseIdle(id);
                return result;
            } catch (Exception ex) { throw new CompletionException(ex); }
        }, ioExecutor).join();
    }

    public ItemStack[] getSliceOrLoad(UUID player, int start, int size, int total) { return slice(getOrLoad(player, total), start, size); }

    public void update(UUID player, ItemStack[] contents, int size) {
        synchronized (lock) {
            if (maintenance) throw new IllegalStateException("Accessory storage migration in progress");
            if (sessions == null || cache.containsKey(player)) {
                cleared.remove(player);
                cache.put(player, copy(contents, size));
                return;
            }
            if (reads.containsKey(player)) throw new IllegalStateException("Accessory inventory is synchronizing");
        }
        offline(player, token -> { persist(player, new Write(copy(contents, size), false, token)); return null; });
    }
    public void updateSlice(UUID player, int start, ItemStack[] contents, int size, int total) {
        ItemStack[] full = getOrLoad(player, total);
        int safeStart = Math.clamp(start, 0, full.length);
        int length = Math.min(size, full.length - safeStart);
        for (int i = 0; i < length; i++) full[safeStart + i] = i < contents.length && contents[i] != null ? contents[i].clone() : null;
        update(player, full, total);
    }
    public void clear(UUID player, int size) {
        ItemStack[] empty = new ItemStack[size];
        synchronized (lock) {
            if (maintenance) throw new IllegalStateException("Accessory storage migration in progress");
            if (sessions == null || cache.containsKey(player)) {
                cancelRead(player);
                cleared.add(player);
                cache.put(player, empty);
                enqueue(player, snapshot(player, empty, size));
                return;
            }
            if (reads.containsKey(player)) throw new IllegalStateException("Accessory inventory is synchronizing");
        }
        offline(player, token -> { persist(player, new Write(empty, true, token)); return null; });
    }

    private void cancelRead(UUID player) {
        ReadSession read = reads.remove(player);
        if (read != null && sessions != null) read.ready.cancel(false);
    }

    public void saveAndRemove(UUID player, int size) {
        synchronized (lock) {
            cancelRead(player);
            var removed = cache.remove(player);
            if (removed != null) enqueue(player, snapshot(player, removed, size));
            cleared.remove(player);
            releaseWhenIdle(player);
        }
    }

    private void releaseWhenIdle(UUID id) {
        if (sessions == null || ioExecutor.isShutdown()) return;
        ioExecutor.execute(() -> {
            synchronized (lock) { if (pendingWrites.containsKey(id)) return; }
            try { sessions.releaseIdle(id); } catch (Exception ex) { log("release", id, ex); }
        });
    }

    public void flush(UUID player, int size) {
        getOrLoad(player, size);
        synchronized (lock) {
            ItemStack[] current = cache.get(player);
            if (current != null) enqueue(player, snapshot(player, current, size));
        }
    }
    public CompletableFuture<Void> flushAllAsync(int size) {
        synchronized (lock) {
            Map<UUID, Write> writes = new HashMap<>(pendingWrites);
            cache.forEach((id, contents) -> {
                Write pending = writes.get(id);
                if (pending == null || !pending.delete() || Arrays.stream(contents).anyMatch(Objects::nonNull))
                    writes.put(id, snapshot(id, contents, size));
            });
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            writes.forEach((id, write) -> tasks.add(enqueue(id, write)));
            tasks.add(CompletableFuture.runAsync(() -> { }, ioExecutor));
            return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
        }
    }
    public void beginMaintenance() { synchronized (lock) { maintenance = true; } }

    public void endMaintenance(boolean invalidate) {
        synchronized (lock) {
            if (invalidate) {
                for (UUID id : List.copyOf(reads.keySet())) cancelRead(id);
                cache.clear();
                cleared.clear();
            }
            maintenance = false;
        }
    }

    public CompletableFuture<Void> awaitWrites() { return CompletableFuture.runAsync(() -> {}, ioExecutor); }

    public Set<UUID> pendingIds() {
        synchronized (lock) { return Set.copyOf(pendingWrites.keySet()); }
    }

    public void shutdown() {
        if (ioExecutor.isShutdown()) return;
        synchronized (lock) { for (UUID id : List.copyOf(reads.keySet())) cancelRead(id); }
        if (sessions != null) CompletableFuture.runAsync(() -> sessions.releaseAll(pendingIds()), ioExecutor).join();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Storage is still flushing");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private CompletableFuture<Void> enqueue(UUID player, Write write) {
        var returning = returns.get(player); // All callers hold the store lock.
        if (returning != null) returning.superseded = true;
        if (storageType == StorageType.ONLY_RAM) return CompletableFuture.completedFuture(null);
        synchronized (lock) { pendingWrites.put(player, write); }
        var task = CompletableFuture.runAsync(() -> {
            synchronized (lock) { if (sessions != null && pendingWrites.get(player) != write) return; }
            persist(player, write);
            saved.accept(player, write.contents().length);
            synchronized (lock) { pendingWrites.remove(player, write); }
        }, ioExecutor);
        task.whenComplete((ignored, ex) -> { if (ex != null && !(ex.getCause() instanceof InventoryLease.Lost)) log("save (retained for retry)", player, ex); });
        return task;
    }
    private ItemStack[] load(UUID player, int size) {
        if (storageType == StorageType.ONLY_RAM) return new ItemStack[size];
        try {
            YamlConfiguration config = new YamlConfiguration();
            String encoded = backend.loadInventory(player);
            if (encoded == null) return new ItemStack[size];
            config.loadFromString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            List<?> raw = config.getList("contents");
            if (raw == null) throw new IllegalArgumentException("Missing contents list");
            ItemStack[] contents = new ItemStack[raw.size()];
            for (int i = 0; i < contents.length; i++) {
                Object value = raw.get(i);
                if (value != null && !(value instanceof ItemStack)) throw new IllegalArgumentException("Invalid item at slot " + i);
                contents[i] = value == null ? null : ((ItemStack) value).clone();
            }
            int format = config.getInt("format", 1);
            if (format != 1 && format != 2) throw new IllegalArgumentException("Unsupported inventory format: " + format);
            return stableSlots && format == 1 ? InventorySlotLayout.migrateLegacy(contents, legacySizes, size) : copy(contents, size);
        } catch (Exception ex) { throw new CompletionException("Cannot load accessory inventory " + player, ex); }
    }
    private byte[] serialize(Write write) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("format", stableSlots ? 2 : 1);
        config.set("contents", Arrays.asList(write.contents()));
        return config.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    private void persist(UUID id, Write write) {
        try {
            String encoded = write.delete() ? null : Base64.getEncoder().encodeToString(serialize(write));
            if (sessions != null) ((InventoryLease) backend).write(id, write.token(), encoded, write.delete());
            else if (write.delete()) backend.deleteInventory(id);
            else backend.saveInventory(id, encoded);
        } catch (InventoryLease.Lost lost) {
            archive(id, write, lost.getMessage());
            throw new CompletionException(lost);
        } catch (Exception ex) { throw new CompletionException("Cannot save accessory inventory " + id, ex); }
    }

    private void leaseExpired(UUID id, String holder) {
        UUID token = sessions.token(id);
        if (token == null) return;
        Write endangered;
        synchronized (lock) {
            var current = cache.get(id);
            endangered = current == null ? pendingWrites.get(id) : snapshot(id, current, current.length);
        }
        if (endangered != null) archive(id, endangered, holder);
        else sessions.forget(id, token);
    }

    private void archive(UUID id, Write failed, String holder) {
        boolean invalidate;
        synchronized (lock) {
            var pending = pendingWrites.get(id);
            var current = cache.get(id);
            boolean sameToken = Objects.equals(failed.token(), sessions.token(id));
            var latest = sameToken && current != null ? snapshot(id, current, current.length)
                    : pending != null && Objects.equals(failed.token(), pending.token()) ? pending : failed;
            Path dir = plugin.getDataFolder().toPath().resolve("lost-writes");
            try {
                Files.createDirectories(dir);
                Path path = Files.createTempFile(dir, id + "-" + System.currentTimeMillis() + "-", ".yml");
                YamlInventoryBackend.write(path, serialize(latest));
                plugin.getLogger().severe("Accessory lease lost for " + id + ", holder=" + holder + ", recovered snapshot=" + path);
            } catch (IOException ex) { throw new CompletionException("Cannot archive lost write for " + id, ex); }
            if (pending != null && Objects.equals(pending.token(), failed.token())) pendingWrites.remove(id);
            invalidate = sameToken && current != null;
            if (invalidate) {
                cache.remove(id);
                returns.remove(id);
                cleared.remove(id);
                cancelRead(id);
            }
            sessions.forget(id, failed.token());
        }
        if (invalidate) notification.accept(id, "Storage_lease_lost");
    }
    private static ItemStack[] slice(ItemStack[] full, int start, int size) {
        ItemStack[] result = new ItemStack[Math.max(0, size)];
        for (int i = 0; i < result.length && start + i < full.length; i++) if (start + i >= 0 && full[start + i] != null) result[i] = full[start + i].clone();
        return result;
    }
    private static ItemStack[] copy(ItemStack[] source, int minimum) {
        // Hidden pages survive profile/layout shrinkage.
        ItemStack[] result = new ItemStack[Math.max(minimum, source == null ? 0 : source.length)];
        if (source != null) for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }
    private void log(String operation, UUID player, Throwable ex) {
        Throwable root = ex; while (root.getCause() != null) root = root.getCause();
        plugin.getLogger().warning("Accessory " + operation + " failed for " + player + ": " + root.getMessage());
    }
}
