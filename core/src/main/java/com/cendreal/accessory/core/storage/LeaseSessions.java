package com.cendreal.accessory.core.storage;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Logger;
import com.cendreal.accessory.core.configuration.AccessorySettings.CrossServer;

/** Backend operations run on the same serial executor as inventory writes. Retrying one player leaves it free for others. */
final class LeaseSessions {
    private final InventoryLease lease;
    private final CrossServer config;
    private final ScheduledExecutorService io;
    private final Map<UUID,UUID> tokens = new ConcurrentHashMap<>();
    private final Predicate<UUID> active;
    private final BiConsumer<UUID,String> notify;
    private final BiConsumer<UUID,String> expired;
    private final Logger logger;
    private final ScheduledFuture<?> heartbeat;
    LeaseSessions(InventoryLease lease, CrossServer config, ScheduledExecutorService io, Predicate<UUID> active,
                  BiConsumer<UUID,String> notify, BiConsumer<UUID,String> expired, Logger logger) {
        this.lease=lease; this.config=config; this.io=io; this.active=active; this.notify=notify; this.expired=expired; this.logger=logger;
        long interval=config.seconds()*1000L/3;
        heartbeat=io.scheduleWithFixedDelay(this::renew,interval,interval,TimeUnit.MILLISECONDS);
    }
    UUID token(UUID id){return tokens.get(id);}
    void ensure(UUID id, BooleanSupplier current, CompletableFuture<Void> result) {
        long start=System.nanoTime();
        class Attempt implements Runnable {
            boolean syncing,locked;
            public void run(){
                if(result.isDone())return;
                if(!current.getAsBoolean()){result.cancel(false);return;}
                try {
                    var claim=lease.acquire(id,config.serverId(),UUID.randomUUID(),config.seconds());
                    UUID previous = tokens.get(id);
                    if (previous != null && !previous.equals(claim.token())) expired.accept(id, claim.holder());
                    if(claim.acquired()){
                        tokens.put(id,claim.token());
                        if(current.getAsBoolean())result.complete(null);
                        else {result.cancel(false);releaseIdle(id);}
                        return;
                    }
                    long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                    if(ms>=3000 && !syncing){syncing=true;notify.accept(id,"Storage_syncing");}
                    if(ms>=(config.seconds()+5L)*1000 && !locked){
                        locked=true;notify.accept(id,"Storage_locked");logger.warning("Accessory inventory "+id+" is held by "+claim.holder());
                    }
                    // Wake at expiry when it falls before the next poll. The backend clock decides ownership.
                    long delay = Math.min(locked ? 5000L : 500L, Math.max(1L, claim.remainingMillis()));
                    io.schedule(this, delay, TimeUnit.MILLISECONDS);
                }catch(Exception e){result.completeExceptionally(e);}
            }
        }
        io.execute(new Attempt());
    }
    UUID acquireOnce(UUID id) throws Exception {
        var claim = lease.acquire(id, config.serverId(), UUID.randomUUID(), config.seconds());
        UUID previous = tokens.get(id);
        if (previous != null && !previous.equals(claim.token())) expired.accept(id, claim.holder());
        if (claim.acquired()) tokens.put(id, claim.token());
        return claim.token();
    }
    void releaseIdle(UUID id) throws Exception {
        UUID token=tokens.get(id);
        if(token!=null && !active.test(id)){lease.release(id,token);tokens.remove(id,token);}
    }
    void releaseAll(Set<UUID> failed) {
        heartbeat.cancel(false);
        for (var entry : Map.copyOf(tokens).entrySet()) if(!failed.contains(entry.getKey())) {
            try {lease.release(entry.getKey(),entry.getValue());}
            catch(Exception e){logger.warning("Could not release accessory lease "+entry.getKey()+": "+e);}
        }
        tokens.clear();
    }
    private void renew(){
        Map<UUID,UUID> online=new HashMap<>();
        tokens.forEach((id,token)->{if(active.test(id))online.put(id,token);});
        if(online.isEmpty())return;
        try{
            var retained=lease.renew(online,config.seconds());
            online.forEach((id,token)->{if(!retained.contains(id)) expired.accept(id, leaseHolder(id));});
        }catch(Exception e){logger.warning("Could not renew accessory leases: "+e);}
    }
    private String leaseHolder(UUID id) {
        try {return Objects.toString(lease.holder(id), "(expired)");}
        catch (Exception e) {return "(unknown)";}
    }
    void forget(UUID id,UUID token){if(token!=null)tokens.remove(id,token);}
}
