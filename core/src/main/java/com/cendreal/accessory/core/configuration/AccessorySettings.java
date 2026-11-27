package com.cendreal.accessory.core.configuration;

import com.cendreal.accessory.api.AccessoryDeathPolicy;


import com.cendreal.accessory.core.storage.AccessoryStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import com.cendreal.accessory.core.extension.ExtensionRegistry;

/** Main config values, read once at startup/reload instead of throughout the event pipeline. */
public record AccessorySettings(
        String language, Gui gui, DebugSettings debug, SkillSettings skills,
        boolean quickEquip, List<String> antiUnequipLore, boolean demonstrateLifeSteal,
        Shield physicalShield, Shield magicShield, Storage storage, Attributes attributes, AccessoryDeathPolicy deathPolicy) {

    public AccessorySettings {
        antiUnequipLore = List.copyOf(antiUnequipLore);
    }

    

    public static AccessorySettings read(ConfigurationSection config) {
        for (String obsolete : List.of("Lang", "debug-mode", "title", "size", "pages", "layout", "quick-equip", "anti-unequip",
                "item-limits", "skill-debug", "vanilla-lore-attributes", "database", "inventory.pages", "inventory.layout", "general.debug", "skills.debug")) {
            if (config.contains(obsolete, true)) throw new IllegalArgumentException("Unsupported config key " + obsolete + "; use the current config.yml and profiles/ format");
        }
        if (config.contains("attribute.provider") && !config.isString("attribute.provider")) {
            throw new IllegalArgumentException("attribute.provider must be a single provider name, not a list");
        }
        var storageType = AccessoryStore.StorageType.fromConfig(config.contains("storage.type", true) ? config.getString("storage.type") : null);
        List<String> tags = config.getStringList("inventory.anti-unequip.lore");
        return new AccessorySettings(
                config.getString("general.language", "en_US"),
                new Gui(config.getString("inventory.title", "<green>Cendreal-Accessory"),
                        normalizeSize(config.getInt("inventory.size", 9))),
                DebugSettings.read(config), SkillSettings.read(config),
                config.getBoolean("inventory.quick-equip.shift-right-click", true), tags,
                config.getBoolean("aura.life-steal.show-heal-message", false),
                new Shield(config.getInt("aura.physical-shield.out-of-combat-seconds", 12), config.getDouble("aura.physical-shield.regen-percent", 0.1)),
                new Shield(config.getInt("aura.magic-shield.out-of-combat-seconds", 12), config.getDouble("aura.magic-shield.regen-percent", 0.1)),
                new Storage(storageType,
                        new Mysql(config.getString("storage.mysql.host", "127.0.0.1"),
                                config.getInt("storage.mysql.port", 3306),
                                config.getString("storage.mysql.database", "minecraft"),
                                config.getString("storage.mysql.username", "root"),
                                config.getString("storage.mysql.password", "password"),
                                config.getInt("storage.mysql.pool-size", 10),
                                config.getInt("storage.mysql.min-idle", 2),
                                config.getInt("storage.mysql.max-lifetime", 1800000),
                                config.getInt("storage.mysql.connection-timeout", 10000),
                                config.getInt("storage.mysql.idle-timeout", 600000)),
                        storageType == AccessoryStore.StorageType.REDIS ? Redis.read(config) : null,
                        config.getString("storage.sqlite.file", "accessory.db"),
                        config.getBoolean("storage.cross-server.enabled", false) ? new CrossServer(
                                config.getString("storage.cross-server.server-id", ""), config.getInt("storage.cross-server.lease-seconds", 30)) : null),
                new Attributes(providerId(config.getString("attribute.provider", "none")),
                        config.getBoolean("attribute.vanilla", true), VanillaLoreSettings.read(config)),
                AccessoryDeathPolicy.parse(config.getString("lifecycle.death", "keep")));
    }

    /** Retains support for trait handlers constructed with a plain JavaPlugin by API callers. */
    public static AccessorySettings current(JavaPlugin plugin) {
        return read(plugin.getConfig());
    }

    public static int normalizeSize(int size) {
        int bounded = Math.max(9, Math.min(54, size));
        return bounded - bounded % 9;
    }

    public record Gui(String title, int defaultSize) { }
    public record Shield(int outOfCombatSeconds, double regenPercent) { }
    private static String providerId(String raw) {
        String id = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return ExtensionRegistry.identifier(id);
    }

    public record Attributes(String provider, boolean vanilla, VanillaLoreSettings lore) { }
    public record CrossServer(String serverId, int seconds) {
        public CrossServer {
            if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,64}") || seconds < 10 || seconds > 300)
                throw new IllegalArgumentException("Invalid storage.cross-server.server-id or lease-seconds (10..300)");
        }
    }
    public record Storage(AccessoryStore.StorageType type, Mysql mysql, Redis redis, String sqliteFile, CrossServer cross) {
        public Storage {
            if (cross != null && type != AccessoryStore.StorageType.MYSQL && type != AccessoryStore.StorageType.REDIS)
                throw new IllegalArgumentException("storage.cross-server requires mysql or redis");
        }
        public Storage(AccessoryStore.StorageType type, Mysql mysql, Redis redis) { this(type,mysql,redis,"accessory.db",null); }
        public Storage(AccessoryStore.StorageType type, Mysql mysql) { this(type,mysql,null); }
    }
    public record Redis(java.net.URI uri, String keyPrefix) {
        public Redis {
            if (uri == null || !("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getUserInfo() != null && !uri.getUserInfo().contains(":"))
                throw new IllegalArgumentException("Invalid storage.redis.uri; use redis[s]://[user:password@]host[:port][/database]");
            String path = uri.getPath();
            try {
                if (path != null && !path.isEmpty() && !path.equals("/")
                        && (!path.matches("/[0-9]+") || Integer.parseInt(path.substring(1)) < 0))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("storage.redis.uri must contain a non-negative database number");
            }
            if (keyPrefix == null || keyPrefix.isBlank()) throw new IllegalArgumentException("storage.redis.key-prefix must not be blank");
        }
        public static Redis read(ConfigurationSection config) {
            java.net.URI uri;
            try { uri = new java.net.URI(config.getString("storage.redis.uri", "redis://127.0.0.1:6379/0")); }
            catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("Invalid storage.redis.uri"); }
            return new Redis(uri, config.getString("storage.redis.key-prefix", "cendreal:accessory:inventory:"));
        }
        @Override public String toString() { return "Redis[host=" + uri.getHost() + ", keyPrefix=" + keyPrefix + "]"; }
    }
    public record Mysql(String host, int port, String database, String username, String password,
                        int poolSize, int minIdle, int maxLifetime, int connectionTimeout, int idleTimeout) {
        @Override
        public String toString() {
            return "Mysql[host=" + host + ", port=" + port + ", database=" + database + "]";
        }
    }
}
