package com.cendreal.accessory.paper.command;

import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.storage.*;
import com.cendreal.accessory.core.storage.migrate.StorageMigration;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class MigrationCommand implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<Lang> lang;
    private final Set<PlayerConnection> joining = new HashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "accessory-migration");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean busy, blockLogins;
    public MigrationCommand(JavaPlugin plugin, Supplier<Lang> lang) { this.plugin = plugin; this.lang = lang; }
    public boolean busy() { return busy; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public synchronized void preLogin(AsyncPlayerPreLoginEvent event) {
        if (blockLogins) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang.get().langComponent("Storage_migrating"));
        else if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) joining.add(event.getConnection());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void validate(PlayerConnectionValidateLoginEvent event) {
        if (blockLogins) event.kickMessage(lang.get().langComponent("Storage_migrating"));
    }
    @EventHandler public synchronized void disconnected(PlayerConnectionCloseEvent event) {
        joining.removeIf(c -> !c.isConnected() || Objects.equals(c.getAddress(), event.getSocketAddress()));
    }

    public synchronized void execute(CommandSender sender, String[] args, AccessoryStore store, int size,
                                     AccessorySettings.Storage settings, boolean unavailable) {
        if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage(lang.get().langComponent(sender, "Migrate_console")); return; }
        StorageMigration.Request request;
        try { request = StorageMigration.Request.parse(args); }
        catch (IllegalArgumentException ex) { tell("Migrate_usage", Map.of()); return; }
        if (busy || unavailable) { tell("Migrate_busy", Map.of()); return; }
        joining.removeIf(c -> !c.isConnected());
        if (!Bukkit.getOnlinePlayers().isEmpty() || !joining.isEmpty()) { tell("Migrate_online", Map.of()); return; }
        busy = true;
        blockLogins = !request.dryRun();
        if (blockLogins) store.beginMaintenance();
        executor.execute(() -> {
            boolean targetOpened = false;
            try {
                try {
                    if (request.dryRun()) store.awaitWrites().join();
                    else store.flushAllAsync(size).join();
                } catch (CompletionException failure) {
                    if (store.pendingIds().isEmpty()) throw failure;
                }
                if (!store.pendingIds().isEmpty()) {
                    tell("Migrate_pending", Map.of("detail", store.pendingIds().toString()));
                    return;
                }
                try (var source = AccessoryStorage.open(plugin, settings, request.from());
                     var target = AccessoryStorage.open(plugin, settings, request.to())) {
                    targetOpened = true;
                    StorageMigration.copy(request, source, target, plugin.getDataFolder().toPath(), this::tell);
                }
            } catch (Exception ex) {
                Throwable root = ex;
                while (root.getCause() != null) root = root.getCause();
                tell("Migrate_failed", Map.of("detail", Objects.toString(root.getMessage(), root.getClass().getSimpleName())));
            } finally {
                synchronized (this) {
                    if (blockLogins) store.endMaintenance(targetOpened && settings.type() == request.to());
                    blockLogins = false;
                    busy = false;
                }
            }
        });
    }
    private void tell(String key, Map<String,String> values) {
        Bukkit.getConsoleSender().sendMessage(lang.get().langComponent(null, key, values));
    }
    @Override public void close() { executor.close(); }
}
