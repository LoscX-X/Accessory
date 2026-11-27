package com.cendreal.accessory.core.menu;

import com.cendreal.accessory.core.util.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** FIFO per owner. A viewer handoff can finish on a different region without blocking a tick thread. */
public final class OwnerOperations {
    private final TaskScheduler scheduler;
    private final Map<UUID, CompletableFuture<?>> tails = new HashMap<>();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private boolean closed;
    private final ThreadLocal<UUID> executing = new ThreadLocal<>();

    public boolean executing(UUID owner) { return owner.equals(executing.get()); }

    public OwnerOperations(TaskScheduler scheduler) { this.scheduler = Objects.requireNonNull(scheduler); }

    public <T> CompletableFuture<T> on(Player player, Supplier<T> action) { return dispatch(player, action, false); }
    public <T> CompletableFuture<T> next(Player player, Supplier<T> action) { return dispatch(player, action, true); }
    private <T> CompletableFuture<T> dispatch(Player player, Supplier<T> action, boolean nextTick) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable run = () -> {
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        try {
            if (player == null) {
                if (scheduler.global()) run.run(); else scheduler.onGlobal(run);
            } else if (!nextTick && scheduler.owns(player)) run.run();
            else scheduler.forPlayer(player, run, () -> result.completeExceptionally(new CancellationException("Player left")));
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    public <T> CompletableFuture<T> submit(UUID owner, Supplier<CompletionStage<T>> action) {
        CompletableFuture<T> entry = new CompletableFuture<>();
        CompletableFuture<?> previous;
        Player session = Bukkit.getPlayer(owner);
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new CancellationException("Accessory stopped"));
            previous = tails.put(owner, entry);
            pending.add(entry);
        }
        entry.whenComplete((value, failure) -> {
            synchronized (this) { tails.remove(owner, entry); pending.remove(entry); }
        });
        Runnable start = () -> on(session, () -> {
            if (entry.isDone() || Bukkit.getPlayer(owner) != session) throw new CancellationException("Session changed");
            UUID outer = executing.get();
            executing.set(owner);
            try { return action.get(); }
            finally { if (outer == null) executing.remove(); else executing.set(outer); }
        }).thenCompose(stage -> stage).whenComplete((value, failure) -> {
            if (failure == null) entry.complete(value); else entry.completeExceptionally(failure);
        });
        if (previous == null) start.run();
        else previous.whenComplete((ignored, failure) -> start.run());
        // A caller cancelling its observation must not release the following operation early.
        return entry.copy();
    }

    public void close() {
        List<CompletableFuture<?>> entries;
        synchronized (this) { closed = true; entries = List.copyOf(pending); }
        entries.forEach(entry -> entry.completeExceptionally(new CancellationException("Accessory stopped")));
    }
}
