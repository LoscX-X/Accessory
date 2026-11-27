package com.cendreal.accessory.paper.hook.mythicmobs;

import com.cendreal.accessory.paper.hook.mythicmobs.RealAttackMechanic;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class MythicMechanicListener implements Listener {

    private final JavaPlugin plugin;

    public MythicMechanicListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        if (event.getMechanicName().equalsIgnoreCase("realattack")) {
            event.register(new RealAttackMechanic(plugin, event));
        }
    }
}
