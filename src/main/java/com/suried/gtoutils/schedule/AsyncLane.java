package com.suried.gtoutils.schedule;

import com.suried.gtoutils.level.LevelStore;

import net.minecraft.world.level.Level;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncLane {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile AsyncLane[] lanes = new AsyncLane[0];

    public static final AsyncLane DEFAULT = create("gtoutils:default", Executors.newFixedThreadPool(defaultThreads(), threadFactory("GTOUtils-async")));

    private final String id;
    private final Executor executor;
    private final LevelStore<Runner> runners;

    private AsyncLane(String id, Executor executor) {
        this.id = id;
        this.executor = executor;
        this.runners = LevelStore.createWithLevel(level -> new Runner(this), Runner::close);
    }

    public static synchronized AsyncLane create(String id, Executor executor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executor, "executor");
        for (AsyncLane lane : lanes) {
            if (lane.id.equals(id)) throw new IllegalArgumentException("duplicate async lane " + id);
        }
        AsyncLane lane = new AsyncLane(id, executor);
        AsyncLane[] grown = Arrays.copyOf(lanes, lanes.length + 1);
        grown[lanes.length] = lane;
        lanes = grown;
        return lane;
    }

    public static ThreadFactory threadFactory(String name) {
        AtomicInteger count = new AtomicInteger();
        return r -> {
            Thread t = new Worker(r, name + "-" + count.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }

    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof Worker;
    }

    public String id() {
        return id;
    }

    TickScheduler scheduler(Level level) {
        if (LevelStore.isReleased(level)) return TickScheduler.CLOSED;
        return runners.get(level).scheduler;
    }

    static void kickAll(Level level) {
        for (AsyncLane lane : lanes) {
            Runner runner = lane.runners.getIfPresent(level);
            if (runner != null) runner.kick();
        }
    }

    private static int defaultThreads() {
        return Math.max(Runtime.getRuntime().availableProcessors() - (FMLEnvironment.dist != null && FMLEnvironment.dist.isClient() ? 2 : 1), 1);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final class Worker extends Thread {

        Worker(Runnable r, String name) {
            super(r, name);
        }
    }

    private static final class Runner implements Runnable {

        final AsyncLane lane;
        final TickScheduler scheduler;
        final AtomicBoolean running = new AtomicBoolean();
        long skipped;

        Runner(AsyncLane lane) {
            this.lane = lane;
            this.scheduler = new TickScheduler(false, (error, owner) -> LOGGER.error("GTOUtils: async task on lane {} failed, owner {}", lane.id, owner, error));
        }

        void kick() {
            if (scheduler.isClosed() || !running.compareAndSet(false, true)) {
                if (!scheduler.isClosed()) skipped++;
                return;
            }
            if (scheduler.advanceIfIdle()) {
                running.set(false);
                return;
            }
            try {
                lane.executor.execute(this);
            } catch (RejectedExecutionException e) {
                running.set(false);
            }
        }

        @Override
        public void run() {
            try {
                scheduler.runRound();
            } finally {
                running.set(false);
            }
        }

        void close() {
            scheduler.close();
            if (running.compareAndSet(false, true)) {
                scheduler.clear();
                running.set(false);
            }
        }

        @Override
        public String toString() {
            return lane.id + " skipped=" + skipped;
        }
    }
}
