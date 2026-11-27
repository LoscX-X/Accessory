package com.cendreal.accessory.paper;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.api.event.AccessoryChangeCause;
import com.cendreal.accessory.core.attribute.AccessoryAttributes;
import com.cendreal.accessory.core.configuration.*;
import com.cendreal.accessory.core.configuration.lang.Lang;
import com.cendreal.accessory.core.configuration.legacy.LegacyConfiguration;
import com.cendreal.accessory.core.inventory.*;
import com.cendreal.accessory.core.lifecycle.AccessoryLifecycle;
import com.cendreal.accessory.core.menu.AccessoryMenuController;
import com.cendreal.accessory.core.profile.*;
import com.cendreal.accessory.core.service.*;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.*;
import com.cendreal.accessory.core.util.DebugLog;
import com.cendreal.accessory.api.extension.*;
import com.cendreal.accessory.core.attribute.loader.VanillaAttributeLoader;
import com.cendreal.accessory.core.extension.ExtensionRegistry;
import com.cendreal.accessory.core.requirement.SlotRequirements;
import com.cendreal.accessory.core.lifecycle.AccessoryReturns;
import com.cendreal.accessory.core.util.AccessoryKeys;
import com.cendreal.accessory.paper.hook.BundledIntegrations;
import com.cendreal.accessory.paper.command.AccessoryCommands;
import com.cendreal.accessory.paper.listener.*;
import com.cendreal.accessory.paper.skill.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import com.cendreal.accessory.core.util.ScheduledTask;
import com.cendreal.accessory.paper.scheduler.PaperTaskScheduler;
import java.util.concurrent.*;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.IntStream;

/** Composition root: builds every component with its dependencies, publishes the API, reloads and shuts down. */
public final class AccessoryRuntime {
    private final JavaPlugin plugin;
    private final AccessoryCommands commands;
    private final DebugLog debug;
    private final com.cendreal.accessory.paper.command.MigrationCommand migration;
    private final StorageLayout layout;
    private volatile boolean closed;
    private volatile boolean reloading;
    private final PaperTaskScheduler scheduler;
    private volatile AccessoryConfiguration configuration;
    private volatile AccessoryStorage storage;
    private SlotRestrictions restrictions;
    private AccessoryProfileManager profiles;
    private ItemLimitManager limits;
    private SkillManager skills;
    private AccessoryLifecycle lifecycle;
    private AccessoryMenuController menus;
    private DefaultAccessoryService service;
    private DefaultQuickEquipService quickEquip;
    private AccessoryApi api;
    private PlayerSessionListener sessions;
    private ScheduledTask autoSave;
    private final Map<UUID, ScheduledTask> polls = new ConcurrentHashMap<>();
    private ExtensionRegistry extensions;
    private DefaultAccessorySlotTypes slotTypes;
    private VanillaAttributeLoader vanilla;
    private AccessoryAttributes attributes;
    private BundledIntegrations hooks;
    private boolean ready, changeScheduled;

    /** @param commands binds /shield and /magicshield once the AuraSkills traits exist */
    public AccessoryRuntime(JavaPlugin plugin, AccessoryCommands commands) {
        this.plugin = Objects.requireNonNull(plugin);
        scheduler = new PaperTaskScheduler(plugin);
        migration = new com.cendreal.accessory.paper.command.MigrationCommand(plugin, this::lang);
        this.commands = Objects.requireNonNull(commands);
        debug = new DebugLog(plugin.getLogger(), this::settings);
        layout = new StorageLayout(() -> profiles == null ? configuration.pages().pageCount() : profiles.storagePages());
    }

    public void enable() {
        ConfigFiles.initialize(plugin);
        configuration = AccessoryConfiguration.read(plugin, BundledIntegrations::legacyAttributeProvider);
        storage = new AccessoryStorage(plugin, settings().storage(), legacyPageSizes(), debug, this::storageNotice);
        restrictions = new SlotRestrictions(owner -> store().getOrLoad(owner, layout.totalSize()), layout::totalSize);
        profiles = new AccessoryProfileManager(scheduler, readProfiles(configuration), configuration.pages(), restrictions, layout, debug);
        extensions = new ExtensionRegistry(scheduler, plugin.getLogger(), player -> {
            if (lifecycle != null && !closed) lifecycle.invalidate(player);
        }, this::extensionsChanged, provider -> {
            if (attributes != null) for (Player online : Bukkit.getOnlinePlayers())
                scheduler.dispatch(online, () -> attributes.retiring(online, provider));
        });
        slotTypes = new DefaultAccessorySlotTypes(profiles, scheduler, extensions, () -> configuration.itemTypes());
        vanilla = new VanillaAttributeLoader(plugin, settings().attributes().lore());
        extensions.attributes(plugin, vanilla);
        extensions.levels(plugin, new AccessoryLevelSource() {
            public String id() { return "vanilla"; }
            public java.util.OptionalDouble level(Player owner, String argument) {
                return argument.isEmpty() ? java.util.OptionalDouble.of(owner.getLevel()) : java.util.OptionalDouble.empty();
            }
        });
        extensions.items(plugin, BundledIntegrations.identity("accessory", AccessoryKeys.key("acc_item_id")));
        limits = new ItemLimitManager(extensions);
        limits.reload(LegacyConfiguration.itemLimits(plugin), plugin.getConfig().getStringList("inventory.item-limits.shared-lore"));
        skills = new SkillManager(plugin, scheduler, profiles, this::settings, debug, () -> loadOnline(AccessoryChangeCause.RELOAD), extensions);
        var skillCatalog = skills.prepare();
        var requirements = new SlotRequirements(profiles, extensions, slotTypes);
        var returns = new AccessoryReturns(scheduler, profiles, requirements, restrictions, extensions, () -> menus, this::store,
                this::lang, () -> !closed && !reloading, plugin.getLogger());
        attributes = new AccessoryAttributes(extensions, () -> settings().attributes());
        lifecycle = new AccessoryLifecycle(scheduler, plugin.getLogger(), this::store, layout, profiles, limits,
                attributes, skills, debug, extensions, requirements, () -> configuration.sets(), returns);
        menus = new AccessoryMenuController(scheduler, this::store, layout, profiles, restrictions, lifecycle, this::lang);
        service = new DefaultAccessoryService(scheduler, this::store, layout, profiles, restrictions, menus, lifecycle);
        var rules = new InventoryRules(profiles, restrictions, layout, limits, () -> settings().antiUnequipLore(), requirements);
        quickEquip = new DefaultQuickEquipService(scheduler, this::store, layout, profiles, menus, lifecycle, rules, skills, this::lang);
        // A profile change closes the owner's windows before effects are rebuilt for the new selection.
        profiles.onChange(player -> service.onOwner(player.getUniqueId(), ignored -> {
            menus.closeForOwner(player.getUniqueId());
            lifecycle.load(player, AccessoryChangeCause.PROFILE);
            return null;
        }));
        registerListeners(rules);
        publishApi();
        autoSave = scheduler.repeatAsync(this::autoSave, 300);
        extensions.placeholder(plugin, "cd", (owner, argument) -> {
            try { return skills.effective().formatCooldown(owner, Integer.parseInt(argument)); }
            catch (NumberFormatException invalid) { return null; }
        });
        extensions.placeholder(plugin, "set_pieces", (owner, set) -> Integer.toString(lifecycle.pieces(owner, set)));
        extensions.placeholder(plugin, "set_name", (owner, set) -> configuration.sets().name(set));
        hooks = new BundledIntegrations(plugin, extensions, this::settings, this::lang, debug, commands, skills::effective);
        hooks.start();
        scheduler.onGlobal(() -> {
            extensions.globalTickStarted();
            ready = true;
            skills.enable(skillCatalog);
            hooks.refresh();
            checkReferences();
            for (Player player : Bukkit.getOnlinePlayers()) scheduler.dispatch(player, () -> {
                startPoll(player);
                lifecycle.load(player, AccessoryChangeCause.JOIN);
            });
        });
    }

    /** Parses everything first; nothing is replaced unless the whole new configuration is valid. */
    public CompletableFuture<Void> reload() {
        return menus.operations().on(null, this::beginReload).thenCompose(stage -> stage);
    }

    public synchronized void migrate(org.bukkit.command.CommandSender sender, String[] args) {
        migration.execute(sender, args, store(), layout.totalSize(), settings().storage(), closed || reloading);
    }

    private synchronized CompletionStage<Void> beginReload() {
        if (closed || reloading || migration.busy()) throw new IllegalStateException("Accessory reload already in progress or stopped");
        plugin.reloadConfig();
        AccessoryConfiguration next = AccessoryConfiguration.read(plugin, BundledIntegrations::legacyAttributeProvider);
        var nextProfiles = readProfiles(next);
        var nextSkills = skills.prepare();
        AccessoryStorage replacement = storage.uses(next.settings().storage()) ? null
                : new AccessoryStorage(plugin, next.settings().storage(), legacyPageSizes(), debug, this::storageNotice);
        reloading = true;
        return menus.withClosedWindows(() -> {
            synchronized (this) {
                if (closed) throw new CancellationException("Accessory stopped");
                if (replacement != null) {
                    storage.flush(layout.totalSize());
                    storage.shutdown();
                    storage = replacement;
                }
            }
            vanilla.configure(next.settings().attributes().lore());
            configuration = next;
            profiles.replace(nextProfiles, next.pages());
            limits.reload(LegacyConfiguration.itemLimits(plugin), plugin.getConfig().getStringList("inventory.item-limits.shared-lore"));
            skills.reload(nextSkills);
            loadOnline(AccessoryChangeCause.RELOAD);
            checkReferences();
        }).whenComplete((ignored, failure) -> {
            if (failure != null && replacement != null && storage != replacement) replacement.shutdown();
            reloading = false;
        });
    }

    private void storageNotice(UUID id, String key) {
        Player player = Bukkit.getPlayer(id);
        if (closed || player == null) return;
        scheduler.dispatch(player, () -> {
            if (!player.isOnline() || closed) return;
            if ("Storage_lease_lost".equals(key)) {
                menus.discardForOwner(id);
                lifecycle.unload(player, AccessoryChangeCause.SLOT_STATE);
                lifecycle.load(player, AccessoryChangeCause.JOIN);
            } else player.sendMessage(lang().langComponent(player, key));
        });
    }

    private synchronized void autoSave() {
        if (!closed && !migration.busy()) store().flushAllAsync(layout.totalSize());
    }

    private void startPoll(Player player) {
        profiles.observe(player);
        polls.computeIfAbsent(player.getUniqueId(), id -> scheduler.repeat(player, () -> {
            profiles.poll(player);
            sessions.resumeIfAlive(player);
            lifecycle.pollRequirements(player);
            hooks.tick(player);
        }, 20, 20));
    }
    private void stopPoll(Player player) {
        ScheduledTask task = polls.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        failure = release(migration::close, failure);
        if (autoSave != null) failure = release(autoSave::cancel, failure);
        failure = release(() -> polls.values().forEach(ScheduledTask::cancel), failure);
        polls.clear();
        failure = release(scheduler::close, failure);
        if (api != null) failure = release(() -> Bukkit.getServicesManager().unregister(AccessoryApi.class, api), failure);
        if (menus != null) failure = release(menus::stop, failure);
        if (lifecycle != null) for (Player player : Bukkit.getOnlinePlayers())
            if (scheduler.owns(player)) failure = release(() -> lifecycle.unload(player, AccessoryChangeCause.PLUGIN_DISABLE), failure);
        if (service != null) failure = release(service::close, failure);
        if (hooks != null) failure = release(hooks::close, failure);
        if (skills != null) failure = release(skills::close, failure);
        failure = release(() -> HandlerList.unregisterAll(plugin), failure);
        if (storage != null) failure = release(() -> storage.close(layout.totalSize()), failure);
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException problem) throw problem;
    }

    private Throwable release(Runnable close, Throwable previous) {
        try { close.run(); }
        catch (RuntimeException | Error failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not release Accessory resource", failure);
            if (previous == null) return failure;
            if (previous != failure) previous.addSuppressed(failure);
        }
        return previous;
    }

    private void registerListeners(InventoryRules rules) {
        var manager = Bukkit.getPluginManager();
        sessions = new PlayerSessionListener(scheduler, this::startPoll, this::stopPoll, this::store, layout, profiles, menus, lifecycle, service, this::settings);
        manager.registerEvents(sessions, plugin);
        manager.registerEvents(migration, plugin);
        manager.registerEvents(new MenuListener(scheduler, this::store, layout, profiles, menus, rules, this::lang), plugin);
        manager.registerEvents(new QuickEquipListener(quickEquip, () -> settings().quickEquip()), plugin);
    }

    private void publishApi() {
        AccessorySkillOptions options = new AccessorySkillOptions() {
            @Override public boolean enabled() { return settings().skills().enabled(); }
            @Override public boolean resetCooldownsOnQuit() { return settings().skills().resetCooldownsOnQuit(); }
        };
        api = new AccessoryApi() {
            public String localeCode(org.bukkit.command.CommandSender viewer) { return com.cendreal.accessory.core.configuration.lang.ClientLanguage.code(viewer); }
            @Override public AccessoryExtensions extensions() { return extensions; }
            @Override public AccessorySlotTypes slotTypes() { return slotTypes; }
            @Override public AccessoryService service() { return service; }
            @Override public AccessoryProfiles profiles() { return profiles; }
            @Override public AccessorySkills skills() { return skills.effective(); }
            @Override public AccessoryQuickEquipService quickEquip() { return quickEquip; }
            @Override public AccessorySkillOptions skillOptions() { return options; }
        };
        Bukkit.getServicesManager().register(AccessoryApi.class, api, plugin, ServicePriority.Normal);
    }

    private ProfileDefinitions readProfiles(AccessoryConfiguration config) {
        var definitions = ProfileDefinitions.load(new File(plugin.getDataFolder(), "profiles"),
                plugin.getLogger(), config.pages().pageCount(), config.pages()::pageSize,
                LegacyConfiguration.profileInventory(config.pages()));
        for (var profile : definitions.profiles()) {
            var pages = config.pages().forProfile(profile.inventory().layouts(), profile.inventory().title());
            profile.disabledSlots().forEach((page, slots) -> {
                if (page > pages.pageCount() || slots.stream().anyMatch(slot -> slot >= pages.pageSize(page)))
                    throw new IllegalArgumentException("Profile " + profile.id() + ": disabled slot outside its layout");
            });
        }
        return definitions;
    }

    /** Page sizes of the format before stable 54-slot pages, used to migrate old saves on read. */
    private List<Integer> legacyPageSizes() {
        List<Integer> explicit = plugin.getConfig().getIntegerList("storage.legacy-page-sizes");
        if (!explicit.isEmpty()) return explicit;
        var pages = configuration.pages();
        return IntStream.rangeClosed(1, pages.pageCount()).mapToObj(pages::pageSize).toList();
    }

    private void loadOnline(AccessoryChangeCause cause) {
        for (Player player : Bukkit.getOnlinePlayers()) scheduler.dispatch(player, () -> lifecycle.load(player, cause));
    }

    private void extensionsChanged() {
        if (!ready || closed || Bukkit.isStopping() || changeScheduled) return;
        changeScheduled = true;
        scheduler.onGlobal(() -> {
            changeScheduled = false;
            if (closed) return;
            skills.extensionsChanged();
            hooks.refresh();
            checkReferences();
            loadOnline(AccessoryChangeCause.RELOAD);
        });
    }
    private void missing(String path, String id) {
        String target = BundledIntegrations.targetFor(id);
        extensions.warn("missing:" + path + "/" + id, path + " references unavailable accessory extension " + id
                + (target == null ? "" : " (requires " + target + ")"));
    }
    private void checkReferences() {
        String provider = settings().attributes().provider();
        if (!provider.equals("none") && extensions.attribute(provider) == null) missing("attribute.provider", provider);
        String executor = settings().skills().executor();
        if (settings().skills().enabled() && extensions.executor(executor) == null) missing("skills.executor", executor);
        configuration.pages().layouts().forEach((name, page) -> page.slots().forEach((slot, rule) -> {
            String path = "layouts/" + name + ".yml slots." + slot + ".requirements";
            for (var level : rule.requirements().levels()) if (extensions.level(level.source().id()) == null) missing(path + ".level", level.source().id());
            var role = rule.requirements().role();
            if (role != null && extensions.role(role.source().id()) == null) missing(path + ".class", role.source().id());
        }));
        configuration.sets().validateSkills(skills::hasSkill, (path, id) ->
                extensions.warn(path + "/" + id, path + " references missing or ambiguous skill " + id));
        configuration.itemTypes().keySet().forEach(key -> {
            String namespace = key.substring(0, key.indexOf(':'));
            if (!extensions.hasIdentity(namespace)) missing("item-types.yml", namespace);
        });
        plugin.getLogger().info("Attributes: provider=" + (extensions.attribute(provider) != null ? provider : "none") + ", vanilla=" + settings().attributes().vanilla());
    }

    public AccessorySettings settings() { return configuration.settings(); }
    public Lang lang() { return configuration.language(); }
    public AccessoryStore store() { return storage.store(); }
    public AccessoryMenuController menus() { return menus; }
    public AccessoryService service() { return service; }
    public AccessoryQuickEquipService quickEquip() { return quickEquip; }
}
