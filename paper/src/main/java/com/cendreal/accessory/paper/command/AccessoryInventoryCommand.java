package com.cendreal.accessory.paper.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import com.cendreal.accessory.paper.AccessoryRuntime;
import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AccessoryInventoryCommand implements BasicCommand {

    private static final List<String> ROOT_COMMANDS = List.of("open", "reload", "clear", "view");

    private final JavaPlugin plugin;
    private final Supplier<AccessoryRuntime> runtime;

    public AccessoryInventoryCommand(JavaPlugin plugin, Supplier<AccessoryRuntime> runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    @Override
    public void execute(@NotNull CommandSourceStack commandSource, @NotNull String[] args) {
        CommandSender sender = commandSource.getSender();
        if (runtime.get() == null) return; // Plugin is not enabled.
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "migrate" -> runtime.get().migrate(sender, args);
            case "open" -> executeOpen(sender);
            case "reload" -> executeReload(sender, args);
            case "clear" -> executeClear(sender, args);
            case "view" -> executeView(sender, args);
            case "quickequip" -> executeQuickEquip(sender, args);
            default -> sender.sendMessage(runtime.get().lang().langComponent(sender, "Cendreal-Accessory_unknown"));
        }
    }

    private boolean executeOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Only_player_open"));
            return true;
        }

        runtime.get().menus().openFor(player, player);
        return true;
    }

    private boolean executeReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("accessory.reload")) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "No_permission"));
            return true;
        }

        if (args.length > 1) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Reload_usage"));
            return true;
        }

        var current = runtime.get();
        current.reload().whenComplete((ignored, failure) -> {
            if (failure != null) plugin.getLogger().warning("Reload failed: " + failure.getMessage());
            reply(sender, current, failure == null ? "Reload_success" : "Reload_failed");
        });
        return true;
    }

    private boolean executeClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("accessory.clear")) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "No_permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Clear_usage"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        if (target.getName() == null && !target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Player_not_found"));
            return true;
        }

        var current = runtime.get();
        var id = target.getUniqueId();
        current.service().onOwner(id, service -> service.clear(id)).whenComplete((success, failure) ->
                reply(sender, current, failure == null && Boolean.TRUE.equals(success) ? "Clear_success" : "Clear_failed"));

        return true;
    }

    private void reply(CommandSender sender, AccessoryRuntime current, String key) {
        Runnable reply = () -> sender.sendMessage(current.lang().langComponent(sender, key));
        if (sender instanceof Player player && !Bukkit.isOwnedByCurrentRegion(player))
            player.getScheduler().execute(plugin, reply, null, 1);
        else reply.run();
    }

    private boolean executeView(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Only_player_open"));
            return true;
        }

        if (!viewer.hasPermission("accessory.view")) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "No_permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "View_usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);

        if (target == null) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Player_not_found"));
            return true;
        }

        runtime.get().service().open(viewer, target, com.cendreal.accessory.api.AccessoryViewMode.READ_ONLY);
        return true;
    }

    private boolean executeQuickEquip(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(runtime.get().lang().langComponent(sender, "Only_player_open"));
            return true;
        }

        if (args.length < 3) {
            return true;
        }

        try {
            int page = Integer.parseInt(args[1]);
            int slot = Integer.parseInt(args[2]);
            var current = runtime.get();
            current.service().onOwner(player.getUniqueId(), ignored -> current.quickEquip().equipToSlot(player, page, slot));
        } catch (NumberFormatException ignored) {
        }
        return true;
    }

    @Override
    public List<String> suggest(@NotNull CommandSourceStack commandSource, @NotNull String[] args) {
        if (args.length == 1) {
            var options = new ArrayList<>(ROOT_COMMANDS);
            if (commandSource.getSender() instanceof org.bukkit.command.ConsoleCommandSender) options.add("migrate");
            return filterByPrefix(args[0], options);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("migrate")
                && commandSource.getSender() instanceof org.bukkit.command.ConsoleCommandSender) {
            var options = new ArrayList<>(args.length <= 3 ? List.of("yml", "sqlite", "mysql", "redis") : List.of("--dry-run", "--overwrite"));
            if (args.length > 5) return List.of();
            options.removeAll(java.util.Arrays.asList(args).subList(1, args.length - 1));
            return filterByPrefix(args[args.length - 1], options);
        }
        if (args.length == 2 && ("clear".equalsIgnoreCase(args[0]) || "view".equalsIgnoreCase(args[0]))) {
            List<String> players = new ArrayList<>();

            for (Player online : Bukkit.getOnlinePlayers()) {
                players.add(online.getName());
            }

            return filterByPrefix(args[1], players);
        }

        return List.of();
    }

    private List<String> filterByPrefix(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }

        return out;
    }
}
