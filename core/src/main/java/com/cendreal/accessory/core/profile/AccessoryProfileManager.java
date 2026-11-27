package com.cendreal.accessory.core.profile;

import com.cendreal.accessory.api.*;
import com.cendreal.accessory.core.configuration.AccessoryPageManager;
import com.cendreal.accessory.core.slot.SlotRestrictions;
import com.cendreal.accessory.core.storage.StorageLayout;
import com.cendreal.accessory.core.util.DebugLog;
import org.bukkit.Bukkit;
import com.cendreal.accessory.core.util.TaskScheduler;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.Consumer;

/** Session overrides plus current permission/world selection. It never moves or deletes stored items. */
public final class AccessoryProfileManager implements AccessoryProfiles {
    private final SlotRestrictions restrictions;
    private final StorageLayout layout;
    private final DebugLog debug;
    private final TaskScheduler scheduler;
    private record Profiles(ProfileDefinitions definitions, Map<String, AccessoryPageManager> pages) { }
    private volatile Profiles current;
    private final Map<UUID, String> assignments = new ConcurrentHashMap<>();
    private final Map<UUID, Set<AccessoryAction>> blocked = new ConcurrentHashMap<>();
    private final Map<UUID, AccessoryProfile> observed = new ConcurrentHashMap<>();
    private Consumer<Player> changed = player -> { };

    public AccessoryProfileManager(TaskScheduler scheduler, ProfileDefinitions definitions, AccessoryPageManager base, SlotRestrictions restrictions, StorageLayout layout, DebugLog debug) {
        this.scheduler = Objects.requireNonNull(scheduler);
        replace(definitions, base);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.layout = Objects.requireNonNull(layout);
        this.debug = Objects.requireNonNull(debug);
    }

    /** Called on the owner region after the selected profile or its permissions changed for an online player. */
    public void onChange(Consumer<Player> listener) { changed = Objects.requireNonNull(listener); }

    public AccessoryPageManager pages(Player owner) {
        Profiles snapshot = current;
        return snapshot.pages().get(resolve(owner, snapshot).id());
    }
    public int storagePages() { return current.pages().values().stream().mapToInt(AccessoryPageManager::pageCount).max().orElse(1); }

    public void replace(ProfileDefinitions definitions, AccessoryPageManager base) {
        Map<String, AccessoryPageManager> pages = new HashMap<>();
        for (var profile : definitions.profiles())
            pages.put(profile.id(), base.forProfile(profile.inventory().layouts(), profile.inventory().title()));
        current = new Profiles(definitions, Map.copyOf(pages));
        assignments.values().removeIf(id -> !definitions.contains(id));
        observed.clear();
    }
    @Override public AccessoryProfile resolve(Player owner) { return resolve(owner, current); }
    private AccessoryProfile resolve(Player owner, Profiles snapshot) {
        scheduler.requireOwner(owner);
        return snapshot.definitions().select(owner.getWorld().getName(), owner::hasPermission, assignments.get(owner.getUniqueId()));
    }
    @Override public void assign(UUID owner, String id) {
        scheduler.requireOwner(owner);
        if (!current.definitions().contains(id)) throw new IllegalArgumentException("Unknown profile: " + id);
        assignments.put(owner, id); refreshOnline(owner);
    }
    @Override public void clearAssignment(UUID owner) { scheduler.requireOwner(owner); assignments.remove(owner); refreshOnline(owner); }
    @Override public void setBlocked(UUID owner, AccessoryAction action, boolean value) {
        scheduler.requireOwner(owner);
        if (value) blocked.computeIfAbsent(owner, k -> EnumSet.noneOf(AccessoryAction.class)).add(action);
        else if (blocked.containsKey(owner)) { blocked.get(owner).remove(action); if (blocked.get(owner).isEmpty()) blocked.remove(owner); }
        refreshOnline(owner);
    }
    @Override public boolean isAllowed(Player owner, AccessoryAction action) {
        return resolve(owner).allows(action) && !blocked.getOrDefault(owner.getUniqueId(), Set.of()).contains(action);
    }
    @Override public boolean isSlotAvailable(Player owner, int page, int slot) {
        UUID id = owner.getUniqueId();
        return resolve(owner).allowsSlot(page, slot) && !restrictions.isSlotDisabled(id, slot)
                && !restrictions.isEquipmentSealed(id, layout.pageStart(page) + slot)
                && pages(owner).isSlotConfigured(page, slot);
    }
    /** Joining establishes the baseline before players can open a window. */
    public void observe(Player owner) { observed.put(owner.getUniqueId(), resolve(owner)); }

    public void poll(Player owner) {
        AccessoryProfile current = resolve(owner);
        if (!current.equals(observed.put(owner.getUniqueId(), current))) refresh(owner);
    }
    @Override public void refresh(Player owner) {
        scheduler.requireOwner(owner);
        observed.put(owner.getUniqueId(), resolve(owner));
        debug.trace("inventory", "profile:" + owner.getUniqueId(), () -> "profile player=" + owner.getName() + " selected=" + resolve(owner).id());
        changed.accept(owner);
    }
    private void refreshOnline(UUID owner) { Player player = Bukkit.getPlayer(owner); if (player != null) refresh(player); }
    public void forget(UUID owner) { assignments.remove(owner); blocked.remove(owner); observed.remove(owner); }
}
