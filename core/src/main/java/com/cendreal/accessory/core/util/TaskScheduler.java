package com.cendreal.accessory.core.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.UUID;

/** Schedules work on a player's owning region or on the global region. */
public interface TaskScheduler {
    boolean owns(Player player);
    boolean global();
    void forPlayer(Player player, Runnable work, Runnable retired);
    ScheduledTask later(Player player, Runnable work, long ticks);
    ScheduledTask repeat(Player player, Runnable work, long delay, long period);
    void onGlobal(Runnable work);

    default void requireOwner(Player player) {
        if (!owns(player)) throw new IllegalStateException("Accessory operation requires the player's owning region");
    }

    default void requireOwner(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) requireOwner(online);
        else requireGlobal();
    }

    default void requireGlobal() {
        if (!global()) throw new IllegalStateException("Accessory operation requires the global region");
    }

    default void dispatch(Player player, Runnable work) {
        if (owns(player)) work.run();
        else forPlayer(player, work, null);
    }
}

