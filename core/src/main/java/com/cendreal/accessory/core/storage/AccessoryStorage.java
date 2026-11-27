package com.cendreal.accessory.core.storage;

import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.storage.mysql.SqlManager;
import com.cendreal.accessory.core.storage.redis.RedisManager;
import com.cendreal.accessory.core.storage.sqlite.SqliteManager;
import com.cendreal.accessory.core.util.DebugLog;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.BiConsumer;

public final class AccessoryStorage {
    private final AccessorySettings.Storage settings;
    private final InventoryBackend backend;
    private final AccessoryStore store;

    public AccessoryStorage(JavaPlugin plugin, AccessorySettings.Storage settings, List<Integer> legacySizes, DebugLog debug) {
        this(plugin, settings, legacySizes, debug, (id,key) -> {});
    }
    public AccessoryStorage(JavaPlugin plugin, AccessorySettings.Storage settings, List<Integer> legacySizes, DebugLog debug,
                            BiConsumer<UUID,String> notification) {
        this.settings = settings;
        backend = open(plugin,settings,settings.type());
        try {
            if (backend != null) backend.initialize();
            if (backend instanceof SqliteManager sqlite) plugin.getLogger().info(sqlite.driverDescription());
            if (settings.cross() != null) ((InventoryLease)backend).initializeLeases();
            store = new AccessoryStore(plugin, settings.type(), backend, true, legacySizes,
                    (id,size) -> debug.trace("storage",id.toString(),() -> "saved owner="+id+" slots="+size),
                    settings.cross(),notification);
            plugin.getLogger().info("Storage mode: "+settings.type());
        } catch (Exception | Error e) {
            if (backend != null) backend.close();
            throw new IllegalStateException("Cannot initialize accessory storage",e);
        }
    }
    /** Inspection opens connections without creating tables, files or directories. */
    public static InventoryBackend open(JavaPlugin plugin, AccessorySettings.Storage settings, AccessoryStore.StorageType type) {
        try {
            return switch(type) {
                case YML -> new YamlInventoryBackend(plugin.getDataFolder().toPath());
                case SQLITE -> new SqliteManager(plugin.getDataFolder().toPath(),settings.sqliteFile());
                case ONLY_RAM -> null;
                case REDIS -> {
                    var cfg=settings.redis()==null?AccessorySettings.Redis.read(plugin.getConfig()):settings.redis();
                    yield new RedisManager(cfg.uri(),cfg.keyPrefix());
                }
                case MYSQL -> {
                    var cfg=settings.mysql();
                    var manager=new SqlManager();
                    manager.init(cfg.host(),cfg.port(),cfg.database(),cfg.username(),cfg.password(),cfg.poolSize(),cfg.minIdle(),cfg.maxLifetime(),cfg.connectionTimeout(),cfg.idleTimeout());
                    yield manager;
                }
            };
        } catch(Exception e) {throw new IllegalStateException("Cannot open "+type+" inventory",e);}
    }
    public AccessoryStore store() {return store;}
    public boolean uses(AccessorySettings.Storage other) {
        if(settings.type()!=other.type() || !Objects.equals(settings.cross(),other.cross()))return false;
        return switch(settings.type()) {
            case MYSQL -> settings.mysql().equals(other.mysql());
            case REDIS -> settings.redis().equals(other.redis());
            case SQLITE -> settings.sqliteFile().equals(other.sqliteFile());
            default -> true;
        };
    }
    public void flush(int size){store.flushAllAsync(size).join();}
    public void shutdown(){try{store.shutdown();}finally{if(backend!=null)backend.close();}}
    public void close(int size){try{flush(size);}finally{shutdown();}}
}
