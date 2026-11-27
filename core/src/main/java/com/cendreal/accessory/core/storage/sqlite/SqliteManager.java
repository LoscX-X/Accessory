package com.cendreal.accessory.core.storage.sqlite;

import com.cendreal.accessory.core.storage.InventoryBackend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** One connection per store. Inspection opens an existing file read-only and never creates a database. */
public final class SqliteManager implements InventoryBackend {
    private final Path file;
    private final BundledSqliteDriver driver;
    private Connection connection;
    private boolean writable;
    public SqliteManager(Path root, String name) throws Exception {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.matches("^[A-Za-z]:.*") || Arrays.asList(name.replace('\\', '/').split("/")).contains(".."))
            throw new IllegalArgumentException("storage.sqlite.file must be relative to the plugin directory without '..'");
        file = root.resolve(name).toAbsolutePath().normalize();
        if (!file.startsWith(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("Invalid storage.sqlite.file");
        driver = new BundledSqliteDriver();
        try { if (Files.exists(file)) connection = connect(true); }
        catch (Exception | Error ex) { driver.close(); throw ex; }
    }
    public String driverDescription() {
        return driver.description();
    }
    private Connection connect(boolean readOnly) throws SQLException {
        return driver.connect("jdbc:sqlite:" + file.toUri().toASCIIString() + (readOnly ? "?mode=ro" : ""));
    }
    @Override public void initialize() throws Exception {
        if (writable) return;
        if (connection != null) connection.close();
        Files.createDirectories(file.getParent());
        connection = connect(false);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS accessory_inventory (player_uuid TEXT NOT NULL PRIMARY KEY,inventory_data TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        }
        writable = true;
    }
    private boolean hasTable() throws SQLException {
        if (writable) return true;
        if (connection == null) return false;
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='accessory_inventory'")) { return r.next(); }
    }
    @Override public String loadInventory(UUID owner) throws SQLException {
        if (!hasTable()) return null;
        try (var s = connection.prepareStatement("SELECT inventory_data FROM accessory_inventory WHERE player_uuid=?")) {
            s.setString(1, owner.toString());
            try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    @Override public void saveInventory(UUID owner, String encoded) throws SQLException {
        try (var s = connection.prepareStatement("""
                INSERT INTO accessory_inventory VALUES(?,?,?) ON CONFLICT(player_uuid)
                DO UPDATE SET inventory_data=excluded.inventory_data, updated_at=excluded.updated_at
                """)) {
            s.setString(1, owner.toString()); s.setString(2, encoded); s.setLong(3, System.currentTimeMillis()); s.executeUpdate();
        }
    }
    @Override public void deleteInventory(UUID owner) throws SQLException {
        try (var s = connection.prepareStatement("DELETE FROM accessory_inventory WHERE player_uuid=?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
    }
    @Override public Set<UUID> players(Consumer<String> skipped) throws SQLException {
        Set<UUID> ids = new TreeSet<>();
        if (hasTable()) try (var s = connection.createStatement(); var r = s.executeQuery("SELECT player_uuid FROM accessory_inventory")) {
            while (r.next()) InventoryBackend.addPlayer(ids, r.getString(1), skipped);
        }
        return ids;
    }
    @Override public void close() {
        try (driver) { if (connection != null) connection.close(); }
        catch (Exception e) { throw new IllegalStateException("Cannot close SQLite", e); }
    }
}
