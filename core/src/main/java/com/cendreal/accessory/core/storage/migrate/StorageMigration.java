package com.cendreal.accessory.core.storage.migrate;

import com.cendreal.accessory.core.storage.*;
import com.cendreal.accessory.core.storage.AccessoryStore.StorageType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

public final class StorageMigration {
    public record Request(StorageType from, StorageType to, boolean dryRun, boolean overwrite) {
        public static Request parse(String[] args) {
            if (args.length < 3 || args.length > 5) throw new IllegalArgumentException();
            StorageType from = StorageType.fromConfig(args[1]), to = StorageType.fromConfig(args[2]);
            Set<String> flags = new HashSet<>(Arrays.asList(args).subList(3, args.length));
            if (from == to || from == StorageType.ONLY_RAM || to == StorageType.ONLY_RAM ||
                    flags.size() != args.length - 3 || !Set.of("--dry-run", "--overwrite").containsAll(flags))
                throw new IllegalArgumentException();
            return new Request(from, to, flags.contains("--dry-run"), flags.contains("--overwrite"));
        }
    }

    public static boolean copy(Request request, InventoryBackend source, InventoryBackend target, Path dataFolder,
                               BiConsumer<String,Map<String,String>> report) throws Exception {
        long start = System.nanoTime();
        Set<String> owners = new TreeSet<>(source.activeLeaseOwners());
        owners.addAll(target.activeLeaseOwners());
        if (!owners.isEmpty()) {
            report.accept("Migrate_leases", Map.of("detail", String.join(", ", owners)));
            return false;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String from = request.from.name().toLowerCase(Locale.ROOT), to = request.to.name().toLowerCase(Locale.ROOT);
        Path dir = Files.createDirectories(dataFolder.resolve("migrations"));
        Path file = dir.resolve(stamp + "-" + from + "-to-" + to + ".log");
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            log.println("Migration " + from + " -> " + to + " dry-run=" + request.dryRun + " overwrite=" + request.overwrite);
            Set<String> invalid = new LinkedHashSet<>();
            Set<UUID> players = source.players(invalid::add);
            invalid.forEach(key -> log.println("SKIP source " + key + " (invalid UUID)"));
            Set<UUID> existing = target.players(key -> log.println("SKIP target " + key + " (invalid UUID)"));
            List<UUID> conflicts = players.stream().filter(existing::contains).sorted().toList();
            if (!conflicts.isEmpty()) {
                String ids = String.join(", ", conflicts.stream().limit(20).map(UUID::toString).toList());
                report.accept("Migrate_conflicts", Map.of("count", Integer.toString(conflicts.size()), "detail", ids));
            }
            int processed = 0, copied = 0, overwritten = 0, failed = 0;
            boolean blocked = !conflicts.isEmpty() && !request.overwrite;
            if (!request.dryRun && !blocked && !players.isEmpty()) {
                if (target instanceof YamlInventoryBackend yml && !yml.directoryExists()) {
                    report.accept("Migrate_enable_yml", Map.of());
                    return false;
                }
                target.initialize();
                for (UUID id : players) {
                    try {
                        String data = Objects.requireNonNull(source.loadInventory(id), "Source record disappeared");
                        target.saveInventory(id, data);
                        if (!data.equals(target.loadInventory(id))) throw new IOException("Read-back mismatch");
                        if (existing.contains(id)) overwritten++; else copied++;
                    } catch (Exception ex) {
                        failed++;
                        log.println("FAIL " + id + " " + ex.getMessage());
                    }
                    if (++processed % 100 == 0) report.accept("Migrate_progress", Map.of("count", Integer.toString(processed)));
                }
            }
            var totals = Map.of("total", Integer.toString(players.size() + invalid.size()), "copied", Integer.toString(copied),
                    "overwritten", Integer.toString(overwritten), "skipped", Integer.toString(invalid.size()),
                    "failed", Integer.toString(failed), "ms", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)));
            log.println(totals);
            if (log.checkError()) throw new IOException("Cannot write migration log " + file);
            report.accept("Migrate_summary", totals);
            if (request.dryRun) report.accept("Migrate_dry", Map.of());
            else if (failed != 0) report.accept("Migrate_failed", Map.of("detail", file.toString()));
            else if (!blocked) report.accept("Migrate_switch", Map.of("target", to));
            return failed == 0 && !blocked;
        }
    }
}
