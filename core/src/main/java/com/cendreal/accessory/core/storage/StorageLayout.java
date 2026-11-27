package com.cendreal.accessory.core.storage;

import java.util.Objects;
import java.util.function.IntSupplier;

/** Fixed 54-slot pages addressed by absolute index; the page count follows the largest configured profile. */
public final class StorageLayout {
    public static final int PAGE_SLOTS = 54;
    private final IntSupplier pageCount;

    public StorageLayout(IntSupplier pageCount) { this.pageCount = Objects.requireNonNull(pageCount); }

    public int pageStart(int page) { return (page - 1) * PAGE_SLOTS; }

    public int totalSize() { return Math.max(1, pageCount.getAsInt()) * PAGE_SLOTS; }
}