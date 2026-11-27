package com.cendreal.accessory.core.util;

/** A scheduled operation that can be cancelled during a player's or plugin's lifetime. */
@FunctionalInterface
public interface ScheduledTask {
    void cancel();
}

