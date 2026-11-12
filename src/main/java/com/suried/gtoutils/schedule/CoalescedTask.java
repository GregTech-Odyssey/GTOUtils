package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

public final class CoalescedTask {

    private static final VarHandle QUEUED;

    static {
        try {
            QUEUED = MethodHandles.lookup().findVarHandle(CoalescedTask.class, "queuedIn", TickScheduler.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final @Nullable Object owner;
    private final Runnable action;
    volatile TickScheduler.Entry entry;
    boolean lagging;
    @SuppressWarnings("unused")
    private volatile @Nullable TickScheduler queuedIn;

    public CoalescedTask(Runnable action) {
        this(null, action);
    }

    public CoalescedTask(@Nullable Object owner, Runnable action) {
        this.owner = owner;
        this.action = Objects.requireNonNull(action, "action");
        this.entry = TickScheduler.coalescedEntry(this, owner, action);
    }

    public boolean isQueued() {
        TickScheduler s = queuedIn();
        return s != null && !s.isClosed();
    }

    public void cancel() {
        TickScheduler s = queuedIn();
        if (s == null) return;
        TickScheduler.Entry old = entry;
        entry = TickScheduler.coalescedEntry(this, owner, action);
        old.cancel();
        QUEUED.compareAndSet(this, s, null);
    }

    void retire() {
        entry.cancel();
    }

    void submitTo(TickScheduler scheduler, int delay) {
        while (true) {
            TickScheduler current = queuedIn();
            if (current != null && !current.isClosed()) return;
            if (scheduler.isClosed()) return;
            if (QUEUED.compareAndSet(this, current, scheduler)) break;
        }
        scheduler.enqueueCoalesced(this, delay);
    }

    @Nullable
    TickScheduler queuedIn() {
        return (TickScheduler) QUEUED.getVolatile(this);
    }

    boolean dequeue(TickScheduler scheduler) {
        return QUEUED.compareAndSet(this, scheduler, null);
    }

    TickScheduler.Entry entryForLink(TickScheduler scheduler) {
        TickScheduler.Entry e = entry;
        if (e.linked || !e.active) {
            e = TickScheduler.coalescedEntry(this, owner, action);
            entry = e;
        }
        e.scheduler = scheduler;
        return e;
    }
}
