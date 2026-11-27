package com.cendreal.accessory.core.storage.sqlite;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.Properties;
import java.util.function.Supplier;

/** Paper also exposes org.sqlite in its parent loader. Keep the packaged driver and its JNI resources together. */
public final class BundledSqliteDriver implements AutoCloseable {
    private final URLClassLoader loader;
    private final Driver driver;
    private final AutoCloseable registration;

    BundledSqliteDriver() throws Exception {
        var pluginLoader = getClass().getClassLoader();
        URL jar = getClass().getProtectionDomain().getCodeSource().getLocation();
        loader = new URLClassLoader(new URL[]{jar}, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.slf4j.")) return pluginLoader.loadClass(name);
                return super.loadClass(name, resolve);
            }
        };
        try {
            Class<?> binding = loader.loadClass(Registration.class.getName());
            Object instance = binding.getConstructor().newInstance();
            registration = (AutoCloseable) instance;
            driver = (Driver) ((Supplier<?>) instance).get();
        } catch (Exception | Error failure) {
            loader.close();
            throw failure;
        }
    }
    Connection connect(String url) throws SQLException { return driver.connect(url, new Properties()); }
    String description() { return registration.toString(); }
    @Override public void close() throws Exception {
        try { registration.close(); } finally { loader.close(); }
    }

    /** DriverManager permits deregistration only from a caller which can see the registered driver. */
    public static final class Registration implements Supplier<Driver>, AutoCloseable {
        private final Driver driver = new org.sqlite.JDBC();
        @Override public Driver get() { return driver; }
        @Override public String toString() {
            return "SQLite JDBC " + org.sqlite.SQLiteJDBCLoader.getVersion() + " from "
                    + driver.getClass().getProtectionDomain().getCodeSource().getLocation();
        }
        @Override public void close() throws SQLException {
            var drivers = DriverManager.drivers().filter(d -> d.getClass().getClassLoader() == getClass().getClassLoader()).toList();
            for (Driver registered : drivers) DriverManager.deregisterDriver(registered);
        }
    }
}
