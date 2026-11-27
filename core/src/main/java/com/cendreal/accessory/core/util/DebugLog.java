package com.cendreal.accessory.core.util;

import com.cendreal.accessory.core.configuration.AccessorySettings;

import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Category filtering, lazy messages, and per-event throttling. Errors are always visible. */
public final class DebugLog {
    private final Logger logger;
    private final Supplier<AccessorySettings> settings;
    private final Map<String, Long> last = new HashMap<>();

    public DebugLog(Logger logger, Supplier<AccessorySettings> settings) {
        this.logger = Objects.requireNonNull(logger);
        this.settings = Objects.requireNonNull(settings);
    }

    public synchronized void trace(String category, String key, Supplier<String> message) {
        var debug = settings.get().debug();
        if (!debug.enabled() || !debug.categories().contains(category)) return;
        long now = System.nanoTime(), interval = debug.intervalTicks() * 50_000_000L;
        String token = category + ":" + key;
        if (now - last.getOrDefault(token, Long.MIN_VALUE / 2) < interval) return;
        if (last.size() > 2048) last.clear();
        last.put(token, now);
        logger.info("[" + category + "] " + message.get());
    }

    public void failure(String category, String message, Throwable error) {
        if (settings.get().debug().enabled()) logger.log(Level.WARNING, "[" + category + "] " + message, error);
        else logger.warning("[" + category + "] " + message + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }
}