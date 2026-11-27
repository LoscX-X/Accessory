package com.cendreal.accessory.api.extension;

@FunctionalInterface
public interface AccessoryRegistration extends AutoCloseable {
    @Override void close();
}
