package com.cendreal.accessory.core.util;

import org.bukkit.NamespacedKey;

import java.util.Locale;

/** Persistent item keys keep the original "accessory" namespace so existing items stay recognised. */
public final class AccessoryKeys {
    public static final String NAMESPACE = "accessory";

    private AccessoryKeys() { }

    public static NamespacedKey key(String key) {
        return new NamespacedKey(NAMESPACE, key.toLowerCase(Locale.ROOT));
    }
}