package com.cendreal.accessory.paper;

import com.cendreal.accessory.paper.command.AccessoryCommands;

/** Plugin entry point: declares commands, then starts and stops the runtime. Other plugins use {@code AccessoryApi}. */
public final class AccessoryPlus extends org.bukkit.plugin.java.JavaPlugin {
    private final AccessoryCommands commands = new AccessoryCommands(this, () -> this.runtime);
    private AccessoryRuntime runtime;

    public AccessoryPlus() { commands.register(); }

    @Override public void onEnable() {
        try {
            runtime = new AccessoryRuntime(this, commands);
            runtime.enable();
        } catch (RuntimeException | Error failure) {
            try { onDisable(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void onDisable() {
        AccessoryRuntime current = runtime;
        runtime = null;
        if (current != null) current.close();
    }
}