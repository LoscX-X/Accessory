package com.cendreal.accessory.core.menu;

import java.util.*;

/** Request identity prevents delayed loads from reopening a replaced viewer/owner session. */
public final class MenuRequests {
    public static final class Request {
        private final UUID viewer, owner;
        private Request(UUID viewer, UUID owner) { this.viewer = viewer; this.owner = owner; }
    }
    private final Map<UUID, Request> viewers = new HashMap<>(), owners = new HashMap<>();
    public synchronized Request begin(UUID viewer, UUID owner) {
        cancelViewer(viewer); cancelOwner(owner);
        Request request = new Request(viewer, owner);
        viewers.put(viewer, request); owners.put(owner, request); return request;
    }
    public synchronized boolean current(Request request) {
        return viewers.get(request.viewer) == request && owners.get(request.owner) == request;
    }
    public synchronized boolean complete(Request request) {
        boolean viewer = viewers.remove(request.viewer, request);
        boolean owner = owners.remove(request.owner, request);
        return viewer && owner;
    }
    public synchronized void cancelViewer(UUID viewer) { Request request = viewers.get(viewer); if (request != null) complete(request); }
    public synchronized void cancelOwner(UUID owner) { Request request = owners.get(owner); if (request != null) complete(request); }
    public synchronized void clear() { viewers.clear(); owners.clear(); }
    public synchronized int pendingCount() { return viewers.size(); }
}
