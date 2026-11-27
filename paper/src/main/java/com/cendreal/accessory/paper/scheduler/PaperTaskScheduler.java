package com.cendreal.accessory.paper.scheduler;

import com.cendreal.accessory.core.util.ScheduledTask;
import com.cendreal.accessory.core.util.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class PaperTaskScheduler implements TaskScheduler {
    private final Plugin plugin;

    public PaperTaskScheduler(Plugin plugin) { this.plugin = Objects.requireNonNull(plugin); }
    @Override public boolean owns(Player player) { return Bukkit.isOwnedByCurrentRegion(player); }
    @Override public boolean global() { return Bukkit.isGlobalTickThread(); }

    @Override public void forPlayer(Player player, Runnable work, Runnable retired) {
        if (!plugin.isEnabled() || !player.getScheduler().execute(plugin, work, retired, 1)) {
            if (retired != null) retired.run();
        }
    }
    @Override public ScheduledTask later(Player player, Runnable work, long ticks) {
        var task = player.getScheduler().runDelayed(plugin, ignored -> work.run(), null, ticks);
        return task == null ? () -> {} : task::cancel;
    }
    @Override public ScheduledTask repeat(Player player, Runnable work, long delay, long period) {
        var task = player.getScheduler().runAtFixedRate(plugin, ignored -> work.run(), null, delay, period);
        return task == null ? () -> {} : task::cancel;
    }
    @Override public void onGlobal(Runnable work) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, work);
    }
    public ScheduledTask repeatAsync(Runnable work, long seconds) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> work.run(), seconds, seconds, TimeUnit.SECONDS)::cancel;
    }
    public void close() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }
}

