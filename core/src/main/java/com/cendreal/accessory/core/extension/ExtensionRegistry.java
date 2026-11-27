package com.cendreal.accessory.core.extension;

import com.cendreal.accessory.api.extension.*;
import com.cendreal.accessory.core.util.TaskScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.logging.Logger;

public final class ExtensionRegistry implements AccessoryExtensions {
    private record Entry<T>(Plugin owner, T value) { }
    private final Map<String, Entry<AccessoryAttributeProvider>> attributes = new ConcurrentHashMap<>();
    private final Map<String, Entry<AccessoryLevelSource>> levels = new ConcurrentHashMap<>();
    private final Map<String, Entry<AccessoryClassSource>> classes = new ConcurrentHashMap<>();
    private final Map<String, Entry<AccessoryItemIdentity>> items = new ConcurrentHashMap<>();
    private final Map<String, Entry<AccessorySkillExecutor>> skills = new ConcurrentHashMap<>();
    private final Map<String, Entry<AccessoryPlaceholder>> placeholders = new ConcurrentHashMap<>();
    private final List<Map<String, ? extends Entry<?>>> groups = List.of(attributes, levels, classes, items, skills, placeholders);
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final TaskScheduler scheduler;
    private final Logger logger;
    // Folia enables plugins on the server bootstrap thread before the global region starts ticking.
    private final Thread bootstrap = Thread.currentThread();
    private volatile boolean ticking;
    public void globalTickStarted() { scheduler.requireGlobal(); ticking = true; }
    private void requireRegistrationThread() {
        if (Thread.currentThread() == bootstrap && (!ticking || org.bukkit.Bukkit.isStopping())) return;
        scheduler.requireGlobal();
    }
    private final Consumer<Player> invalidate;
    private final Runnable changed;
    private final Consumer<AccessoryAttributeProvider> retiring;
    private volatile List<AccessoryItemIdentity> identities = List.of();

    public ExtensionRegistry(TaskScheduler scheduler, Logger logger, Consumer<Player> invalidate, Runnable changed, Consumer<AccessoryAttributeProvider> retiring) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.invalidate = invalidate;
        this.changed = changed;
        this.retiring = retiring;
    }

    public static String identifier(String id) {
        if (id == null || !id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid extension ID: " + id);
        return id;
    }

    private <T> AccessoryRegistration add(Map<String, Entry<T>> group, Plugin owner, String id, T value) {
        return add(group, owner, id, value, () -> {});
    }
    private <T> AccessoryRegistration add(Map<String, Entry<T>> group, Plugin owner, String id, T value, Runnable beforeRemoval) {
        requireRegistrationThread();
        identifier(id);
        var entry = new Entry<>(Objects.requireNonNull(owner), Objects.requireNonNull(value));
        if (group.putIfAbsent(id, entry) != null) {
            logger.warning(owner.getName() + " attempted to register duplicate accessory extension " + id);
            throw new IllegalArgumentException("Duplicate accessory extension: " + id);
        }
        edited();
        return () -> {
            requireRegistrationThread();
            if (group.get(id) != entry) return;
            beforeRemoval.run();
            if (group.remove(id, entry)) edited();
        };
    }

    private void edited() {
        // An externally supplied identity takes precedence over the built-in fallback.
        identities = items.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<String, Entry<AccessoryItemIdentity>> e) -> e.getKey().equals("accessory"))
                .thenComparing(Map.Entry::getKey)).map(entry -> entry.getValue().value()).toList();
        changed.run();
    }
    public void unregister(Plugin plugin) {
        requireRegistrationThread();
        attributes.values().forEach(entry -> { if (entry.owner() == plugin) retiring.accept(entry.value()); });
        boolean removed = false;
        for (var group : groups) removed |= group.values().removeIf(entry -> entry.owner() == plugin);
        if (removed) edited();
    }
    public void warn(String key, String reason) { if (warned.add(key)) logger.warning(reason); }
    public <T> T guarded(String key, Supplier<T> query, T fallback) {
        try { return Objects.requireNonNullElse(query.get(), fallback); }
        catch (RuntimeException | LinkageError failure) {
            warn("failure:" + key, "Accessory extension " + key + " failed: " + failure); return fallback;
        }
    }
    private static <T> T value(Map<String, Entry<T>> group, String id) {
        var entry = group.get(id);
        return entry == null ? null : entry.value();
    }
    public AccessoryAttributeProvider attribute(String id) { return value(attributes, id); }
    public AccessoryLevelSource level(String id) { return value(levels, id); }
    public AccessoryClassSource role(String id) { return value(classes, id); }
    public AccessorySkillExecutor executor(String id) { return value(skills, id); }
    public boolean hasIdentity(String namespace) { return items.containsKey(namespace); }
    public Optional<String> identify(ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.empty();
        for (var source : identities) {
            var result = guarded("items/" + source.namespace(), () -> source.id(item), Optional.<String>empty());
            if (result.isPresent() && !result.get().isBlank()) return Optional.of(source.namespace() + ":" + result.get());
        }
        return Optional.empty();
    }
    public boolean cast(String id, Player player, AccessorySkillCast cast) {
        var executor = executor(id);
        return executor != null && guarded("skills/" + id, () -> executor.cast(player, cast), false);
    }
    @Override public AccessoryRegistration levels(Plugin p, AccessoryLevelSource s) { return add(levels, p, s.id(), s); }
    @Override public AccessoryRegistration classes(Plugin p, AccessoryClassSource s) { return add(classes, p, s.id(), s); }
    @Override public AccessoryRegistration attributes(Plugin p, AccessoryAttributeProvider s) { return add(attributes, p, s.id(), s, () -> retiring.accept(s)); }
    @Override public AccessoryRegistration skills(Plugin p, AccessorySkillExecutor s) { return add(skills, p, s.id(), s); }
    @Override public AccessoryRegistration items(Plugin p, AccessoryItemIdentity s) { return add(items, p, s.namespace(), s); }
    @Override public AccessoryRegistration placeholder(Plugin p, String key, AccessoryPlaceholder ph) {
        return add(placeholders, p, key, (player, arg) -> guarded("placeholder/" + key,
                () -> Optional.ofNullable(ph.resolve(player, arg)), Optional.<String>empty()).orElse(null));
    }
    @Override public Map<String, AccessoryPlaceholder> placeholders() {
        Map<String, AccessoryPlaceholder> result = new LinkedHashMap<>();
        placeholders.forEach((key, entry) -> result.put(key, entry.value()));
        return Map.copyOf(result);
    }
    @Override public void invalidate(Player player) { invalidate.accept(player); }
}
