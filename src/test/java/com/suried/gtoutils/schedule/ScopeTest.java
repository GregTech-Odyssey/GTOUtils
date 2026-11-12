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
        assertEquals(List.of("mount true", "unmount true", "mount false"), log);
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
    void eachReconcilesByKeyAndValueEndingBeforeCreating() {
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
        assertEquals(List.of("-a1", "-b1", "+a2", "+c1"), log);
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
        assertEquals(0, Poll.signal(lane, 3, 0).subscriberCount());
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
    void handoverInsideSelectKeepsPooledResourceWithinGrace() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        AtomicInteger created = new AtomicInteger();
        var pool = root.<String, Object>pool("pool", 1, (p, key) -> {
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
    void chainedDerivationsSettleInTheSameRound() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var a = Cell.of(0);
        var b = Cell.of(0);
        var c = Cell.of(0);
        List<Integer> seen = new ArrayList<>();
        root.watch(a::get, v -> b.set(v * 2));
        root.watch(b::get, v -> c.set(v + 1));
        root.watch(c::get, seen::add);
        root.schedule(0, () -> a.set(5));
        lane.runRound();
        assertEquals(List.of(1, 11), seen);
        a.set(7);
        assertEquals(List.of(1, 11), seen);
        lane.runRound();
        assertEquals(List.of(1, 11, 15), seen);
    }

    @Test
    void derivationTriggeredSeveralTimesInOneRoundRunsOnce() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        AtomicInteger evaluations = new AtomicInteger();
        root.watch(() -> {
            signal.track();
            return evaluations.incrementAndGet();
        }, v -> {});
        for (int i = 0; i < 3; i++) root.schedule(0, signal::emit);
        lane.runRound();
        assertEquals(2, evaluations.get());
    }

    @Test
    void cyclicDerivationsStopAtTheDepthLimitAndContinueNextRound() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var a = Cell.of(0);
        var b = Cell.of(0);
        int anomalies = Scope.anomalyCount();
        root.watch(a::get, v -> b.set(v + 1));
        root.watch(b::get, v -> a.set(v + 1));
        lane.runRound();
        int afterFirst = a.peek();
        assertTrue(afterFirst <= 2 * (TickScheduler.SETTLE_DEPTH + 2), "one round ran " + afterFirst + " steps");
        assertTrue(Scope.anomalyCount() > anomalies);
        lane.runRound();
        assertTrue(a.peek() > afterFirst && a.peek() <= afterFirst + 4, "later rounds run each derivation once: " + afterFirst + " -> " + a.peek());
        int afterSecond = a.peek();
        lane.runRound();
        assertTrue(a.peek() > afterSecond && a.peek() <= afterSecond + 4, "and keep doing so: " + afterSecond + " -> " + a.peek());
        root.dispose();
        int stopped = a.peek();
        lane.runRound();
        assertEquals(stopped, a.peek());
    }

    @Test
    void emitFromAnotherThreadIsHandedOverToTheLane() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        var cell = Cell.of(0);
        AtomicInteger heard = new AtomicInteger();
        List<Thread> ranOn = new ArrayList<>();
        root.listen(signal, () -> {
            heard.incrementAndGet();
            ranOn.add(Thread.currentThread());
        });
        root.listen(cell.changed(), heard::incrementAndGet);
        int relayed = Scope.anomalyCount();
        var other = new Thread(() -> {
            signal.emit();
            cell.set(1);
        });
        other.start();
        other.join();
        assertEquals(0, heard.get());
        assertEquals(relayed + 2, Scope.anomalyCount());
        lane.runRound();
        assertEquals(2, heard.get());
        assertEquals(List.of(Thread.currentThread()), ranOn);
        new Signal().emit();
    }

    @Test
    void registeringFromAnotherThreadIsRejectedAndLeavesTheScopeIntact() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        int baseline = Scope.liveCount();
        List<String> rejected = new ArrayList<>();
        List<String> log = new ArrayList<>();
        var other = new Thread(() -> {
            try {
                root.onDispose(() -> log.add("undo"));
            } catch (IllegalStateException e) {
                rejected.add("onDispose");
            }
            try {
                root.child("child");
            } catch (IllegalStateException e) {
                rejected.add("child");
            }
            try {
                root.listen(signal, () -> log.add("heard"));
            } catch (IllegalStateException e) {
                rejected.add("listen");
            }
            try {
                root.watch(() -> 1, v -> log.add("watched"));
            } catch (IllegalStateException e) {
                rejected.add("watch");
            }
            try {
                OwnedScope.root("stray", lane);
            } catch (IllegalStateException e) {
                rejected.add("root");
            }
        });
        other.start();
        other.join();
        assertEquals(List.of("onDispose", "child", "listen", "watch", "root"), rejected);
        assertEquals(baseline, Scope.liveCount());
        assertEquals(0, signal.subscriberCount());
        root.dispose();
        assertTrue(log.isEmpty());
    }

    @Test
    void signalCannotBeSharedByScopesOfTwoThreads() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        root.listen(signal, () -> {});
        List<String> rejected = new ArrayList<>();
        var other = new Thread(() -> {
            var theirs = new TickScheduler(true, RETHROW);
            theirs.runRound();
            var scope = OwnedScope.root("theirs", theirs);
            try {
                scope.listen(signal, () -> {});
            } catch (IllegalStateException e) {
                rejected.add("listen");
            }
            scope.dispose();
        });
        other.start();
        other.join();
        assertEquals(List.of("listen"), rejected);
        assertEquals(1, signal.subscriberCount());
    }

    @Test
    void disposeFromAnotherThreadHappensOnTheLane() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        List<Thread> undoneOn = new ArrayList<>();
        root.onDispose(() -> undoneOn.add(Thread.currentThread()));
        var other = new Thread(root::dispose);
        other.start();
        other.join();
        assertTrue(root.isActive());
        lane.runRound();
        assertFalse(root.isActive());
        assertEquals(List.of(Thread.currentThread()), undoneOn);
    }

    @Test
    void closingTheLaneEndsItsRootScopes() {
        int baseline = Scope.liveCount();
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var child = root.child("child");
        var second = OwnedScope.root("second", lane);
        List<String> log = new ArrayList<>();
        child.onDispose(() -> log.add("child"));
        root.onDispose(() -> log.add("root"));
        second.onDispose(() -> log.add("second"));
        second.dispose();
        lane.close();
        assertEquals(List.of("second", "child", "root"), log);
        assertFalse(root.isActive());
        assertFalse(child.isActive());
        var late = OwnedScope.root("late", lane);
        assertFalse(late.isActive());
        late.onDispose(() -> log.add("late"));
        assertEquals(List.of("second", "child", "root", "late"), log);
        assertEquals(baseline, Scope.liveCount());
    }

    @Test
    void laneClosedDuringARoundEndsItsRootsWhenTheRoundFinishes() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        List<String> log = new ArrayList<>();
        root.onDispose(() -> log.add("root"));
        root.schedule(0, () -> {
            lane.close();
            log.add("closing");
        });
        lane.runRound();
        assertEquals(List.of("closing", "root"), log);
        assertFalse(root.isActive());
    }

    @Test
    void failuresAreCountedAndKeptAsRecentSummaries() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        int before = Scope.failureCount();
        root.listen(signal, () -> {
            throw new IllegalStateException("listener boom");
        });
        root.watch(() -> {
            throw new IllegalStateException("derive boom");
        }, v -> {});
        root.onDispose(() -> {
            throw new IllegalStateException("undo boom");
        });
        signal.emit();
        root.dispose();
        assertEquals(before + 3, Scope.failureCount());
        var recent = Scope.recentFailures();
        assertTrue(recent.size() >= 3 && recent.size() <= 32);
        var tail = recent.subList(recent.size() - 3, recent.size());
        assertTrue(tail.get(0).contains("derive boom") && tail.get(0).contains("root"));
        assertTrue(tail.get(1).contains("listener boom"));
        assertTrue(tail.get(2).contains("undo boom") && tail.get(2).startsWith("dispose action"));
        for (int i = 0; i < 40; i++) {
            var extra = OwnedScope.root("extra" + i, lane);
            extra.onDispose(() -> {
                throw new IllegalStateException("again");
            });
            extra.dispose();
        }
        assertEquals(32, Scope.recentFailures().size());
        assertTrue(Scope.recentFailures().get(31).contains("extra39"));
    }

    @Test
    void sizeCountsTheSubtreeAndDumpListsItWithLimits() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var a = root.child("a");
        a.child("deep").child("deeper");
        root.child("b").onDispose(() -> {});
        root.provide(ScopeKey.<String>of("svc"), "x");
        assertEquals(5, root.size());
        assertEquals(3, a.size());
        var out = new StringBuilder();
        root.dump(out, 2);
        assertEquals("root [4 below, 0 undo, 1 services]\n  a [2 below, 0 undo]\n    deep [1 below, 0 undo]\n  b [1 undo]\n", out.toString());
        var wide = OwnedScope.root("wide", lane);
        for (int i = 0; i < 30; i++) wide.child("c" + i);
        out.setLength(0);
        wide.dump(out, 1);
        assertEquals(26, out.toString().split("\n").length);
        assertTrue(out.toString().endsWith("  + 6 more\n"));
    }

    @Test
    void endingAnAncestorFromADescendantsUndoTearsEachScopeDownOnce() {
        int baseline = Scope.liveCount();
        var lane = lane();
        var parent = OwnedScope.root("parent", lane);
        var child = parent.child("child");
        var sibling = parent.child("sibling");
        List<String> log = new ArrayList<>();
        parent.onDispose(() -> log.add("parent"));
        sibling.onDispose(() -> log.add("sibling"));
        child.onDispose(() -> log.add("child-first"));
        child.onDispose(parent::dispose);
        child.onDispose(() -> log.add("child-last"));
        int failures = Scope.failureCount();
        child.dispose();
        assertEquals(List.of("child-last", "sibling", "parent", "child-first"), log);
        assertFalse(parent.isActive());
        assertEquals(failures, Scope.failureCount());
        assertEquals(baseline, Scope.liveCount());
    }

    @Test
    void selectEndsTheOldChildBeforeMountingTheNewOne() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of("a");
        List<String> log = new ArrayList<>();
        root.select(cell::get, (scope, v) -> {
            log.add("+" + v);
            scope.onDispose(() -> log.add("-" + v));
        });
        cell.set("b");
        lane.runRound();
        assertEquals(List.of("+a", "-a", "+b"), log);
    }

    @Test
    void selectStopsWhenEndingTheOldChildEndsItsOwner() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var owner = root.child("owner");
        var cell = Cell.of("a");
        List<String> log = new ArrayList<>();
        owner.select(cell::get, (scope, v) -> {
            log.add("+" + v);
            scope.onDispose(owner::dispose);
        });
        cell.set("b");
        lane.runRound();
        assertEquals(List.of("+a"), log);
        assertFalse(owner.isActive());
    }

    @Test
    void siblingsEndInReverseOrderEvenAfterOneWasRemoved() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        List<String> log = new ArrayList<>();
        Map<String, OwnedScope> children = new HashMap<>();
        for (var name : List.of("a", "b", "c", "d")) {
            var child = root.child(name);
            child.onDispose(() -> log.add(name));
            children.put(name, child);
        }
        children.get("b").dispose();
        log.clear();
        root.child("e").onDispose(() -> log.add("e"));
        assertEquals(5, root.size());
        root.dispose();
        assertEquals(List.of("e", "d", "c", "a"), log);
    }

    @Test
    void tasksOfAnEndedScopeLeaveTheWheelWithinOneTurn() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        root.schedule(5000, () -> {});
        root.schedule(9000, () -> {});
        assertEquals(2, lane.linkedCount());
        root.dispose();
        for (int i = 0; i < TickScheduler.WHEEL_SIZE; i++) lane.runRound();
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void pollSpreadsDerivationsAcrossPhases() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        int period = 4;
        int[] evaluations = { 0 };
        for (int i = 0; i < 8; i++) {
            root.watch(() -> {
                Poll.every(period);
                return ++evaluations[0];
            }, v -> {});
        }
        int[] perRound = new int[period * 2];
        for (int r = 0; r < perRound.length; r++) {
            int before = evaluations[0];
            lane.runRound();
            perRound[r] = evaluations[0] - before;
        }
        assertArrayEquals(new int[] { 2, 2, 2, 2, 2, 2, 2, 2 }, perRound);
    }

    @Test
    void emittingInsideADerivationIsCountedAsMisuse() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        root.listen(signal, () -> {});
        int before = Scope.anomalyCount();
        root.watch(() -> {
            signal.emit();
            return 0;
        }, v -> {});
        assertEquals(before + 1, Scope.anomalyCount());
        signal.emit();
        assertEquals(before + 1, Scope.anomalyCount());
    }

    @Test
    void leaseAfterThePoolEndedStillRejectsAReentrantFactory() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var owner = root.child("owner");
        @SuppressWarnings("unchecked")
        SharedPool<String, Object>[] holder = new SharedPool[1];
        holder[0] = owner.<String, Object>pool("pool", 0, (p, key) -> root.lease(holder[0], key).get());
        owner.dispose();
        assertThrows(IllegalStateException.class, () -> root.lease(holder[0], "k"));
        assertEquals(0, holder[0].size());
    }

    @Test
    void rootWithALazyLabelNamesItselfOnEveryDemandWithoutCachingAndDropsTheLabelWhenEnded() {
        var lane = lane();
        int[] rendered = { 0 };
        Object label = new Object() {

            @Override
            public String toString() {
                rendered[0]++;
                return "machine at 1,2,3";
            }
        };
        var root = OwnedScope.root(label, lane);
        assertEquals(0, rendered[0]);
        assertSame(label, root.label());
        assertEquals(0, rendered[0]);
        assertEquals("machine at 1,2,3", root.name());
        assertEquals(1, rendered[0]);
        assertEquals("machine at 1,2,3", root.name());
        assertEquals(2, rendered[0]);
        root.dispose();
        assertEquals("ended", root.name());
        assertEquals("ended", root.label());
        assertEquals(2, rendered[0]);
        var silent = OwnedScope.root(label, lane);
        silent.dispose();
        assertEquals("ended", silent.name());
        assertEquals(2, rendered[0]);
    }

    @Test
    void failedSubscriptionLeavesNoStraySubscriptions() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var free = new Signal();
        var taken = new Signal();
        var other = new Thread(() -> {
            var theirs = new TickScheduler(true, RETHROW);
            theirs.runRound();
            OwnedScope.root("theirs", theirs).listen(taken, () -> {});
        });
        other.start();
        other.join();
        int failures = Scope.failureCount();
        int[] evaluations = { 0 };
        root.watch(() -> {
            evaluations[0]++;
            free.track();
            taken.track();
            return 0;
        }, v -> {});
        assertEquals(failures + 1, Scope.failureCount());
        assertEquals(0, free.subscriberCount());
        assertEquals(1, taken.subscriberCount());
        free.emit();
        lane.runRound();
        assertEquals(1, evaluations[0]);
    }

    @Test
    void signalMovesToALiveLaneWhenItsFirstLaneCloses() throws InterruptedException {
        var first = lane();
        var second = lane();
        var signal = new Signal();
        AtomicInteger heard = new AtomicInteger();
        OwnedScope.root("first", first).listen(signal, () -> {});
        OwnedScope.root("second", second).listen(signal, heard::incrementAndGet);
        first.close();
        assertEquals(1, signal.subscriberCount());
        var other = new Thread(signal::emit);
        other.start();
        other.join();
        assertEquals(0, heard.get());
        second.runRound();
        assertEquals(1, heard.get());
    }

    @Test
    void scopesAreRefusedOnALaneWithoutAFixedThread() {
        int baseline = Scope.liveCount();
        var async = new TickScheduler(false, RETHROW);
        assertThrows(IllegalStateException.class, () -> OwnedScope.root("async", async));
        assertFalse(OwnedScope.root("closed", TickScheduler.CLOSED).isActive());
        assertEquals(baseline, Scope.liveCount());
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
