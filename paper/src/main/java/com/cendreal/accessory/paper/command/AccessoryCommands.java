package com.cendreal.accessory.paper.command;

import com.cendreal.accessory.paper.AccessoryRuntime;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.Supplier;

import java.util.function.BiConsumer;
import org.bukkit.entity.Player;

/** Command registration and optional shield command bindings. */
public final class AccessoryCommands {
    private final JavaPlugin plugin;
    private final Supplier<AccessoryRuntime> runtime;
    private final ShieldCommand shield;
    private final ShieldCommand magicShield;

    /** Declared while the plugin is constructed; {@code runtime} is null until the plugin is enabled. */
    public AccessoryCommands(JavaPlugin plugin, Supplier<AccessoryRuntime> runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
        shield = new ShieldCommand(plugin, "accessory.shield", "shield", () -> runtime.get().lang());
        magicShield = new ShieldCommand(plugin, "accessory.magicshield", "magicshield", () -> runtime.get().lang());
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();

            registrar.register("accessory", "打开饰品背包或执行管理命令", java.util.List.of("acc", "acre"), new AccessoryInventoryCommand(plugin, runtime));
            registrar.register("shield", "调整当前物理护盾", shield);
            registrar.register("magicshield", "调整当前魔法护盾", magicShield);
        });
    }

    public void configureShields(BiConsumer<Player, Double> add, BiConsumer<Player, Double> percent,
                                BiConsumer<Player, Double> addMagic, BiConsumer<Player, Double> percentMagic) {
        shield.configure(add, percent);
        magicShield.configure(addMagic, percentMagic);
    }
}
