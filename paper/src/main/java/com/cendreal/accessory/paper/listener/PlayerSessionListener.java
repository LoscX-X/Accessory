package com.cendreal.accessory.paper.listener;

import com.cendreal.accessory.api.AccessoryDeathPolicy;
import com.cendreal.accessory.api.event.AccessoryChangeCause;
import com.cendreal.accessory.api.event.AccessoryDeathEvent;
import com.cendreal.accessory.core.configuration.AccessorySettings;
import com.cendreal.accessory.core.lifecycle.AccessoryLifecycle;
import com.cendreal.accessory.core.menu.AccessoryMenuController;
import com.cendreal.accessory.core.profile.AccessoryProfileManager;
import com.cendreal.accessory.core.service.DefaultAccessoryService;
import com.cendreal.accessory.core.storage.AccessoryStore;
import com.cendreal.accessory.core.storage.StorageLayout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.function.Consumer;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Player session and death policy adapter; contains no attribute-provider implementation. */
public final class PlayerSessionListener implements Listener {
    private final Set<UUID> awaitingRespawn = ConcurrentHashMap.newKeySet();
    private final TaskScheduler scheduler;
    private final Consumer<Player> join, quit;
    private final Supplier<AccessoryStore> store;
    private final StorageLayout layout;
    private final AccessoryProfileManager profiles;
    private final AccessoryMenuController menus;
    private final AccessoryLifecycle lifecycle;
    private final DefaultAccessoryService service;
    private final Supplier<AccessorySettings> settings;

    public PlayerSessionListener(TaskScheduler scheduler, Consumer<Player> join, Consumer<Player> quit, Supplier<AccessoryStore> store, StorageLayout layout, AccessoryProfileManager profiles,
                                 AccessoryMenuController menus, AccessoryLifecycle lifecycle, DefaultAccessoryService service,
                                 Supplier<AccessorySettings> settings) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.join = join;
        this.quit = quit;
        this.store = Objects.requireNonNull(store);
        this.layout = Objects.requireNonNull(layout);
        this.profiles = Objects.requireNonNull(profiles);
        this.menus = Objects.requireNonNull(menus);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.service = Objects.requireNonNull(service);
        this.settings = Objects.requireNonNull(settings);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { join.accept(event.getPlayer()); lifecycle.load(event.getPlayer(), AccessoryChangeCause.JOIN); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        awaitingRespawn.remove(player.getUniqueId());
        quit.accept(player);
        menus.closeForOwner(player.getUniqueId());
        try { lifecycle.unload(player, AccessoryChangeCause.QUIT); }
        finally {
            menus.forgetViewer(player.getUniqueId());
            service.forgetSession(player.getUniqueId());
            profiles.forget(player.getUniqueId());
            store.get().saveAndRemove(player.getUniqueId(), layout.totalSize());
        }
    }

    @EventHandler public void levelChanged(org.bukkit.event.player.PlayerLevelChangeEvent event) {
        lifecycle.invalidate(event.getPlayer());
    }
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { profiles.refresh(event.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        awaitingRespawn.add(player.getUniqueId());
        scheduler.later(player, () -> resumeIfAlive(player), 1);
    }

    /** Folia can complete the respawn transfer without a Bukkit respawn event. */
    public void resumeIfAlive(Player player) {
        if (awaitingRespawn.contains(player.getUniqueId()) && !player.isDead() && player.isValid()
                && awaitingRespawn.remove(player.getUniqueId()))
            lifecycle.load(player, AccessoryChangeCause.RESPAWN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        menus.closeForOwner(player.getUniqueId());
        if (!store.get().ready(player.getUniqueId())) {
            lifecycle.unload(player, AccessoryChangeCause.DEATH);
            awaitingRespawn.add(player.getUniqueId());
            return;
        }
        ItemStack[] contents = store.get().getOrLoad(player.getUniqueId(), layout.totalSize());
        AccessoryDeathEvent policyEvent = new AccessoryDeathEvent(player, contents, settings.get().deathPolicy());
        Bukkit.getPluginManager().callEvent(policyEvent);
        boolean drop = policyEvent.getPolicy() == AccessoryDeathPolicy.DROP
                || policyEvent.getPolicy() == AccessoryDeathPolicy.FOLLOW_KEEP_INVENTORY && !event.getKeepInventory();
        if (drop) {
            service.clear(player.getUniqueId(), player, AccessoryChangeCause.DEATH);
            for (ItemStack item : contents) if (item != null && !item.getType().isAir()) event.getDrops().add(item.clone());
        }
        lifecycle.unload(player, AccessoryChangeCause.DEATH);
        awaitingRespawn.add(player.getUniqueId());
    }
}
