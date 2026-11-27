package com.cendreal.accessory.core.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class YamlInventoryBackend implements InventoryBackend {
    private final Path directory;
    private final Path legacy;

    public YamlInventoryBackend(Path root) {
        legacy = root.resolve("contains");
        Path current = root.resolve("contents");
        directory = Files.isDirectory(legacy) && !Files.exists(current) ? legacy : current;
    }
    public boolean directoryExists() { return Files.isDirectory(directory); }
    @Override public void initialize() throws IOException { Files.createDirectories(directory); }
    private Path file(UUID owner) {
        String name = owner + ".yml";
        Path preferred = directory.resolve(name);
        return !Files.exists(preferred) && Files.isRegularFile(legacy.resolve(name)) ? legacy.resolve(name) : preferred;
    }
    @Override public String loadInventory(UUID owner) throws IOException {
        Path path = file(owner);
        return Files.notExists(path) ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }
    @Override public void saveInventory(UUID owner, String encoded) throws IOException {
        write(file(owner), Base64.getDecoder().decode(encoded));
    }
    public static void write(Path destination, byte[] data) throws IOException {
        Files.createDirectories(destination.getParent());
        Path tmp = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, data);
            try { Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    @Override public void deleteInventory(UUID owner) throws IOException { Files.deleteIfExists(file(owner)); }
    @Override public Set<UUID> players(Consumer<String> skipped) throws IOException {
        Set<UUID> result = new TreeSet<>();
        for (Path dir : new LinkedHashSet<>(List.of(directory, legacy))) {
            if (Files.notExists(dir)) continue;
            try (var files = Files.newDirectoryStream(dir, "*.yml")) {
                for (Path file : files) if (Files.isRegularFile(file)) {
                    String name = file.getFileName().toString();
                    InventoryBackend.addPlayer(result, name.substring(0, name.length() - 4), skipped);
                }
            }
        }
        return result;
    }
    @Override public void close() { }
}
