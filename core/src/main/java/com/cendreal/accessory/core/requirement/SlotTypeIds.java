package com.cendreal.accessory.core.requirement;

import java.util.*;
import java.util.function.Consumer;

public final class SlotTypeIds {
    private SlotTypeIds() { }
    public static Set<String> read(Collection<?> values, Consumer<String> invalid) {
        Set<String> ids = new LinkedHashSet<>();
        for (var value : values) {
            String id = value instanceof String raw ? raw.trim().toLowerCase(Locale.ROOT) : "";
            if (id.matches("[a-z0-9_]+")) ids.add(id);
            else invalid.accept("Invalid slot type: " + value);
        }
        return Collections.unmodifiableSet(ids);
    }
}
