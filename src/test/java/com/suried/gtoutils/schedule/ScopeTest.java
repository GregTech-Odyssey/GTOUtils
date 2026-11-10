package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScopeTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    @Test
    void disposeRunsChildrenFirstThenUndoInReverseOrder() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        root.onDispose(() -> log.add("root-1"));
        var child = root.child("child");
        child.onDispose(() -> log.add("child-1"));
        child.onDispose(() -> log.add("child-2"));
        root.onDispose(() -> log.add("root-2"));
        root.dispose();
        root.dispose();
        assertEquals(List.of("child-2", "child-1", "root-2", "root-1"), log);
        assertFalse(child.isActive());
    }

    @Test
    void wholeSubtreeIsInactiveBeforeAnyUndoRuns() {
        var root = OwnedScope.root("root", lane());
        var a = root.child("a");
        var b = a.child("b");
        List<Boolean> seen = new ArrayList<>();
        b.onDispose(() -> seen.add(root.isActive() || a.isActive()));
        root.dispose();
        assertEquals(List.of(false), seen);
    }

    @Test
    void failingUndoDoesNotStopOthers() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        root.onDispose(() -> log.add("first"));
        root.onDispose(() -> {
            throw new IllegalStateException("boom");
        });
        root.onDispose(() -> log.add("third"));
        root.dispose();
        assertEquals(List.of("third", "first"), log);
    }

    @Test
    void registeringOnEndedScopeIsANoOpOrImmediateUndo() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        root.dispose();
        List<String> log = new ArrayList<>();
        root.effect(() -> log.add("apply"), () -> log.add("revert"));
        root.onDispose(() -> log.add("undo"));
        var task = root.repeat(Cadence.EVERY_TICK, () -> log.add("tick"));
        var child = root.child("late");
        lane.runRound();
        assertEquals(List.of("undo"), log);
        assertFalse(child.isActive());
        assertFalse(task.isActive());
    }

    @Test
    void effectAppliesNowAndRevertsOnDispose() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        root.effect(() -> log.add("apply"), () -> log.add("revert"));
        assertEquals(List.of("apply"), log);
        root.dispose();
        assertEquals(List.of("apply", "revert"), log);
    }

    @Test
    void tasksStopWhenScopeEnds() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger once = new AtomicInteger();
        root.repeat(Cadence.EVERY_TICK, ticks::incrementAndGet);
        root.schedule(5, once::incrementAndGet);
        for (int i = 0; i < 3; i++) lane.runRound();
        root.dispose();
        for (int i = 0; i < 10; i++) lane.runRound();
        assertEquals(3, ticks.get());
        assertEquals(0, once.get());
    }

    @Test
    void triggerCoalescesAndStopsAfterDispose() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        AtomicInteger runs = new AtomicInteger();
        var trigger = root.trigger(0, runs::incrementAndGet);
        trigger.fire();
        trigger.fire();
        lane.runRound();
        assertEquals(1, runs.get());
        trigger.fire();
        root.dispose();
        lane.runRound();
        assertEquals(1, runs.get());
    }

    @Test
    void listenRunsSynchronouslyAndUnsubscribesOnDispose() {
        var root = OwnedScope.root("root", lane());
        var signal = new Signal();
        AtomicInteger hits = new AtomicInteger();
        root.listen(signal, hits::incrementAndGet);
        signal.emit();
        assertEquals(1, hits.get());
        assertEquals(1, signal.subscriberCount());
        root.dispose();
        signal.emit();
        assertEquals(1, hits.get());
        assertEquals(0, signal.subscriberCount());
    }

    @Test
    void listenerRemovedDuringDispatchIsSkippedAndAddedOneWaits() {
        var signal = new Signal();
        var lane = lane();
        var a = OwnedScope.root("a", lane);
        var b = OwnedScope.root("b", lane);
        var c = OwnedScope.root("c", lane);
        List<String> log = new ArrayList<>();
        a.listen(signal, () -> {
            log.add("a");
            b.dispose();
            c.listen(signal, () -> log.add("c"));
        });
        b.listen(signal, () -> log.add("b"));
        signal.emit();
        assertEquals(List.of("a"), log);
        assertEquals(2, signal.subscriberCount());
        log.clear();
        a.dispose();
        signal.emit();
        assertEquals(List.of("c"), log);
    }

    @Test
    void selectTracksDependenciesAndRebuildsOnlyOnValueChange() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        int[] source = { 1 };
        List<String> log = new ArrayList<>();
        root.select(() -> {
            signal.track();
            return source[0] > 0;
        }, (scope, positive) -> {
            log.add("mount " + positive);
            scope.onDispose(() -> log.add("unmount " + positive));
        });
        assertEquals(List.of("mount true"), log);
        source[0] = 2;
        signal.emit();
        lane.runRound();
        assertEquals(List.of("mount true"), log);
        source[0] = -1;
        signal.emit();
        signal.emit();
        assertEquals(List.of("mount true"), log);
        lane.runRound();
        assertEquals(List.of("mount true", "mount false", "unmount true"), log);
        root.dispose();
        assertEquals("unmount false", log.get(log.size() - 1));
        assertEquals(0, signal.subscriberCount());
    }

    @Test
    void selectDropsDependenciesNoLongerRead() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var a = new Signal();
        var b = new Signal();
        boolean[] useB = { true };
        AtomicInteger evaluations = new AtomicInteger();
        root.select(() -> {
            evaluations.incrementAndGet();
            a.track();
            if (useB[0]) b.track();
            return evaluations.get();
        }, (s, v) -> {});
        assertEquals(1, b.subscriberCount());
        useB[0] = false;
        a.emit();
        lane.runRound();
        assertEquals(0, b.subscriberCount());
        assertEquals(1, a.subscriberCount());
        b.emit();
        lane.runRound();
        assertEquals(2, evaluations.get());
    }

    @Test
    void watchRunsOnceThenOnEachChange() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        int[] source = { 1 };
        List<Integer> seen = new ArrayList<>();
        root.watch(() -> {
            signal.track();
            return source[0] / 2;
        }, seen::add);
        assertEquals(List.of(0), seen);
        source[0] = 2;
        signal.emit();
        signal.emit();
        assertEquals(List.of(0), seen);
        lane.runRound();
        assertEquals(List.of(0, 1), seen);
        source[0] = 3;
        signal.emit();
        lane.runRound();
        assertEquals(List.of(0, 1), seen);
        root.dispose();
        source[0] = 8;
        signal.emit();
        lane.runRound();
        assertEquals(List.of(0, 1), seen);
        assertEquals(0, signal.subscriberCount());
    }

    @Test
    void cellTracksReadsAndEmitsOnlyOnChange() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of("a");
        List<String> derived = new ArrayList<>();
        AtomicInteger heard = new AtomicInteger();
        root.watch(cell::get, derived::add);
        root.listen(cell.changed(), heard::incrementAndGet);
        assertFalse(cell.set("a"));
        assertEquals(0, heard.get());
        assertTrue(cell.set("b"));
        assertEquals(1, heard.get());
        assertEquals("b", cell.peek());
        lane.runRound();
        assertEquals(List.of("a", "b"), derived);
        var untracked = Cell.of(1);
        root.watch(untracked::peek, v -> derived.add("peek " + v));
        untracked.set(2);
        lane.runRound();
        assertEquals(List.of("a", "b", "peek 1"), derived);
        root.dispose();
        assertEquals(0, cell.changed().subscriberCount());
    }

    @Test
    void whenOpensAndClosesChild() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        boolean[] open = { false };
        AtomicInteger live = new AtomicInteger();
        root.when(() -> {
            signal.track();
            return open[0];
        }, scope -> {
            live.incrementAndGet();
            scope.onDispose(live::decrementAndGet);
        });
        assertEquals(0, live.get());
        open[0] = true;
        signal.emit();
        lane.runRound();
        assertEquals(1, live.get());
        open[0] = false;
        signal.emit();
        lane.runRound();
        assertEquals(0, live.get());
    }

    @Test
    void eachReconcilesByKeyAndValueCreatingBeforeDisposing() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        var table = new LinkedHashMap<String, Integer>();
        table.put("a", 1);
        table.put("b", 1);
        List<String> log = new ArrayList<>();
        root.each(() -> {
            signal.track();
            return new LinkedHashMap<>(table);
        }, (scope, key, value) -> {
            log.add("+" + key + value);
            scope.onDispose(() -> log.add("-" + key + value));
        });
        assertEquals(List.of("+a1", "+b1"), log);
        log.clear();
        table.put("a", 2);
        table.remove("b");
        table.put("c", 1);
        signal.emit();
        lane.runRound();
        assertEquals(List.of("+a2", "+c1", "-a1", "-b1"), log);
        log.clear();
        signal.emit();
        lane.runRound();
        assertEquals(List.of(), log);
        root.dispose();
        assertEquals(Set.of("-a2", "-c1"), new HashSet<>(log));
    }

    @Test
    void derivationFailureKeepsPreviousState() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        boolean[] fail = { false };
        List<String> log = new ArrayList<>();
        root.select(() -> {
            signal.track();
            if (fail[0]) throw new IllegalStateException("boom");
            return "ok";
        }, (s, v) -> log.add(v));
        fail[0] = true;
        signal.emit();
        lane.runRound();
        fail[0] = false;
        signal.emit();
        lane.runRound();
        assertEquals(List.of("ok"), log);
        assertEquals(1, signal.subscriberCount());
    }

    @Test
    void pollReevaluatesPeriodicallyOnlyWhileSubscribed() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        AtomicInteger evaluations = new AtomicInteger();
        root.select(() -> {
            Poll.every(3);
            return evaluations.incrementAndGet();
        }, (s, v) -> {});
        for (int i = 0; i < 9; i++) lane.runRound();
        int during = evaluations.get();
        assertTrue(during >= 3 && during <= 5, "evaluations " + during);
        root.dispose();
        assertEquals(0, Poll.signal(lane, 3).subscriberCount());
        for (int i = 0; i < 9; i++) lane.runRound();
        assertEquals(during, evaluations.get());
    }

    @Test
    void pollOutsideDerivationIsIgnored() {
        Poll.every(5);
        new Signal().track();
    }

    @Test
    void poolSharesByKeyAndReleasesAfterGrace() {
        var lane = lane();
        var host = OwnedScope.root("host", lane);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        var pool = host.<String, int[]>pool("pool", 3, (p, key) -> {
            created.incrementAndGet();
            p.scope().onDispose(released::incrementAndGet);
            return new int[1];
        });
        var a = OwnedScope.root("a", lane);
        var b = OwnedScope.root("b", lane);
        var la = a.lease(pool, "k");
        var lb = b.lease(pool, "k");
        assertSame(la.get(), lb.get());
        assertEquals(1, created.get());
        a.dispose();
        b.dispose();
        lane.runRound();
        assertEquals(0, released.get());
        assertEquals(1, pool.size());
        for (int i = 0; i < 3; i++) lane.runRound();
        assertEquals(1, released.get());
        assertEquals(0, pool.size());
    }

    @Test
    void poolReuseWithinGraceKeepsResource() {
        var lane = lane();
        var host = OwnedScope.root("host", lane);
        AtomicInteger created = new AtomicInteger();
        var pool = host.<String, Object>pool("pool", 5, (p, key) -> {
            created.incrementAndGet();
            return new Object();
        });
        var a = OwnedScope.root("a", lane);
        var first = a.lease(pool, "k");
        a.dispose();
        lane.runRound();
        var b = OwnedScope.root("b", lane);
        var second = b.lease(pool, "k");
        for (int i = 0; i < 10; i++) lane.runRound();
        assertSame(first.get(), second.get());
        assertEquals(1, created.get());
        assertEquals(1, pool.size());
    }

    @Test
    void poolWithZeroGraceReleasesImmediatelyAndEndsWithItsScope() {
        var lane = lane();
        var host = OwnedScope.root("host", lane);
        AtomicInteger released = new AtomicInteger();
        var pool = host.<Integer, Object>pool("pool", 0, (p, key) -> {
            p.scope().onDispose(released::incrementAndGet);
            return new Object();
        });
        var a = OwnedScope.root("a", lane);
        a.lease(pool, 1);
        a.dispose();
        assertEquals(1, released.get());
        var b = OwnedScope.root("b", lane);
        b.lease(pool, 2);
        host.dispose();
        assertEquals(2, released.get());
        assertEquals(0, pool.size());
    }

    @Test
    void demandIsAggregatedAndTracked() {
        var lane = lane();
        var host = OwnedScope.root("host", lane);
        AtomicInteger working = new AtomicInteger();
        var pool = host.<String, AtomicInteger>pool("pool", 0, (p, key) -> {
            p.scope().when(p::demanded, s -> {
                working.incrementAndGet();
                s.onDispose(working::decrementAndGet);
            });
            return new AtomicInteger();
        });
        var a = OwnedScope.root("a", lane);
        var b = OwnedScope.root("b", lane);
        var la = a.lease(pool, "k");
        var lb = b.lease(pool, "k");
        la.demand(true);
        lb.demand(true);
        lane.runRound();
        assertEquals(1, working.get());
        la.demand(false);
        lane.runRound();
        assertEquals(1, working.get());
        b.dispose();
        lane.runRound();
        assertEquals(0, working.get());
    }

    @Test
    void handoverInsideSelectDoesNotRecreatePooledResource() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        AtomicInteger created = new AtomicInteger();
        var pool = root.<String, Object>pool("pool", 0, (p, key) -> {
            created.incrementAndGet();
            return new Object();
        });
        var signal = new Signal();
        int[] version = { 0 };
        root.select(() -> {
            signal.track();
            return version[0];
        }, (s, v) -> s.lease(pool, "k"));
        version[0] = 1;
        signal.emit();
        lane.runRound();
        assertEquals(1, created.get());
        assertEquals(1, pool.size());
    }

    @Test
    void randomOperationsMatchModelAndLeaveNoLiveScopes() {
        var random = new Random(20261109);
        for (int round = 0; round < 30; round++) {
            int baseline = Scope.liveCount();
            var lane = lane();
            var root = OwnedScope.root("root", lane);
            var signals = new Signal[4];
            for (int i = 0; i < signals.length; i++) signals[i] = new Signal();
            Map<String, Integer> table = new HashMap<>();
            Map<String, Integer> mounted = new HashMap<>();
            List<OwnedScope> scopes = new ArrayList<>();
            scopes.add(root);
            AtomicInteger mounts = new AtomicInteger();
            root.each(() -> {
                for (var s : signals) s.track();
                return new LinkedHashMap<>(table);
            }, (scope, key, value) -> {
                mounted.put(key, value);
                mounts.incrementAndGet();
                scope.onDispose(() -> mounted.remove(key, value));
            });
            for (int step = 0; step < 400; step++) {
                switch (random.nextInt(6)) {
                    case 0 -> table.put("k" + random.nextInt(8), random.nextInt(3));
                    case 1 -> table.remove("k" + random.nextInt(8));
                    case 2 -> signals[random.nextInt(signals.length)].emit();
                    case 3 -> lane.runRound();
                    case 4 -> scopes.add(scopes.get(random.nextInt(scopes.size())).child("c"));
                    default -> {
                        if (scopes.size() > 1) scopes.remove(1 + random.nextInt(scopes.size() - 1)).dispose();
                    }
                }
            }
            signals[0].emit();
            lane.runRound();
            assertEquals(table, mounted);
            root.dispose();
            for (var s : scopes) assertFalse(s.isActive());
            assertTrue(mounted.isEmpty());
            for (var s : signals) assertEquals(0, s.subscriberCount());
            assertEquals(baseline, Scope.liveCount());
        }
    }
}
