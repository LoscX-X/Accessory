package com.cendreal.accessory.api;

import com.cendreal.accessory.api.event.AccessoryChangeCause;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Inventory control contract. Online mutations require the owner region, offline mutations and global settings the global region. Reads return independent clones. */
public interface AccessoryService {
    /**
     * Enqueues a synchronous operation behind earlier window edits for this owner. Callable from any thread.
     * Use this for writes that may overlap a remote editor. A synchronous mutation with an outstanding remote
     * transfer fails before it mutates anything. Do not block a region thread waiting for the returned stage.
     */
    <T> CompletionStage<T> onOwner(UUID owner, java.util.function.Function<AccessoryService, T> operation);
    CompletionStage<ItemStack[]> read(UUID owner);
    /** Owner-region snapshot of currently effective equipment, keyed by absolute storage slot. */
    Map<Integer, ItemStack> activeEquipment(Player owner);
    /** Called on the viewer region. Both edit and read-only windows may target a remote owner. */
    void open(Player viewer, Player owner, AccessoryViewMode mode);
    void setBlocked(UUID owner, AccessoryAction action, boolean blocked);
    boolean isAllowed(Player owner, AccessoryAction action);
    void setSlotEnabled(int slot, boolean enabled);
    void setDisabledSlots(Collection<Integer> slots);
    List<Integer> getDisabledSlots();
    boolean isSlotDisabled(int slot);
    /** 合并全局限制与该玩家所有临时来源，不包含 Profile 自身的页规则。 */
    List<Integer> getDisabledSlots(UUID player);
    boolean isSlotDisabled(UUID player, int slot);
    /** 替换该玩家指定来源的临时槽位限制，不修改全局状态、物品或持久化数据。 */
    void setTransientDisabledSlots(UUID player, String owner, Set<Integer> slots);
    /** 只撤销该玩家的指定来源，其他玩家和来源保持不变。 */
    void clearTransientDisabledSlots(UUID player, String owner);
    /** Locks this exact equipped stack in its absolute storage slot, in memory only. */
    boolean lockItem(UUID player, String owner, int storageSlot, ItemStack expected, int durationTicks);
    /** Removes only the named owner's locks; administrative clear/restore never depends on these locks. */
    void clearItemLocks(UUID player, String owner);
    boolean isItemLocked(UUID player, int storageSlot);
    /** Temporarily disables this exact equipped stack in one absolute storage slot, without changing storage. */
    boolean sealEquipmentSlot(UUID player, String owner, int storageSlot, ItemStack expected, int durationTicks);
    /** Releases only this owner's equipment seals; other owners' seals remain effective. */
    void clearEquipmentSeals(UUID player, String owner);
    boolean isEquipmentSealed(UUID player, int storageSlot);
    boolean clear(UUID owner);
    boolean clear(UUID owner, Player actor, AccessoryChangeCause cause);
    /** Absolute slot count across all pages, as used by {@link #contents} and {@link #replace}. */
    int storageSize();
    /** Independent copy of the stored contents; loads synchronously when not cached. Any thread. */
    ItemStack[] contents(UUID owner);
    /** Replaces the stored contents; an online owner's effects are refreshed, which requires the owner region. */
    void replace(UUID owner, ItemStack[] contents);
    /** Queues any pending write for this owner immediately. Any thread. */
    void flush(UUID owner);
    /** Cancels a pending load, re-reads stored contents and reapplies attributes, tags and skills. */
    void refresh(Player owner);
}
