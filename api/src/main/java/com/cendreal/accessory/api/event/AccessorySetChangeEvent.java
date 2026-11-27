package com.cendreal.accessory.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import java.util.Set;

public final class AccessorySetChangeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String setId;
    private final int previousPieces, pieces;
    private final Set<Integer> activeTiers;
    public AccessorySetChangeEvent(Player owner, String setId, int previousPieces, int pieces, Set<Integer> activeTiers) {
        super(owner);
        this.setId = setId; this.previousPieces = previousPieces; this.pieces = pieces;
        this.activeTiers = Set.copyOf(activeTiers);
    }
    public String getSetId() { return setId; }
    public int getPreviousPieces() { return previousPieces; }
    public int getPieces() { return pieces; }
    public Set<Integer> getActiveTiers() { return activeTiers; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
