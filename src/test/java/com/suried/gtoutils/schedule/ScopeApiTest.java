package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScopeApiTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    private static void onOtherThread(Runnable action) throws InterruptedException {
        var t = new Thread(action);
        t.start();
        t.join();
    }

    @Test
    void postOnTheLaneThreadTakesEffectImmediately() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(0);
        List<Integer> seen = new ArrayList<>();
        root.watch(cell::get, seen::add);
        int anomalies = Scope.anomalyCount();
        cell.post(1);
        assertEquals(1, cell.peek());
        lane.runRound();
        assertEquals(List.of(0, 1), seen);
        assertEquals(anomalies, Scope.anomalyCount());
    }

    @Test
    void postWithoutSubscribersWritesDirectlyFromAnyThread() throws InterruptedException {
        var cell = Cell.of(0);
        onOtherThread(() -> cell.post(5));
        assertEquals(5, cell.peek());
    }

    @Test
    void postFromAnotherThreadIsRelayedKeepingOnlyTheLastValueAndCountsNoMisuse() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(0);
        List<Integer> seen = new ArrayList<>();
        root.watch(cell::get, seen::add);
        int anomalies = Scope.anomalyCount();
        onOtherThread(() -> {
            for (int i = 1; i <= 100_000; i++) cell.post(i);
        });
        assertEquals(0, cell.peek());
        lane.runRound();
        assertEquals(List.of(0, 100_000), seen);
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void floodOfPostsWhileTheLaneRunsNeverLosesTheLastValue() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(-1);
        int[] evaluations = { 0 };
        int[] last = { -1 };
        root.watch(cell::get, v -> {
            evaluations[0]++;
            last[0] = v;
        });
        int anomalies = Scope.anomalyCount();
        AtomicBoolean done = new AtomicBoolean();
        var writer = new Thread(() -> {
            for (int i = 0; i < 500_000; i++) cell.post(i);
            done.set(true);
        });
        writer.start();
        int rounds = 0;
        while (!done.get()) {
            lane.runRound();
            rounds++;
        }
        writer.join();
        lane.runRound();
        rounds++;
        assertEquals(499_999, last[0]);
        assertEquals(499_999, cell.peek());
        assertTrue(evaluations[0] <= rounds + 1);
        assertEquals(anomalies, Scope.anomalyCount());
    }

    @Test
    void postOnTheLaneDiscardsAnOlderPendingValue() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(0);
        List<Integer> seen = new ArrayList<>();
        root.watch(cell::get, seen::add);
        onOtherThread(() -> cell.post(5));
        cell.post(7);
        lane.runRound();
        lane.runRound();
        assertEquals(List.of(0, 7), seen);
        assertEquals(7, cell.peek());
    }

    @Test
    void pendingPostToAClosedLaneMovesToTheNextSubscriber() throws InterruptedException {
        var first = lane();
        var cell = Cell.of(0);
        OwnedScope.root("first", first).watch(cell::get, v -> {});
        onOtherThread(() -> cell.post(1));
        first.close();
        assertEquals(0, cell.changed().subscriberCount());
        var second = lane();
        List<Integer> seen = new ArrayList<>();
        OwnedScope.root("second", second).watch(cell::get, seen::add);
        onOtherThread(() -> cell.post(2));
        second.runRound();
        assertEquals(List.of(0, 2), seen);
    }

    @Test
    void setFromAnotherThreadStillCountsMisuse() throws InterruptedException {
        var lane = lane();
        var cell = Cell.of(0);
        OwnedScope.root("root", lane).watch(cell::get, v -> {});
        int anomalies = Scope.anomalyCount();
        onOtherThread(() -> cell.set(3));
        assertEquals(anomalies + 1, Scope.anomalyCount());
    }

    @Test
    void keyedSignalRecomputesOnlyDerivationsOfTheEmittedKey() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var teams = new KeyedSignal<String>();
        int[] a = { 0 };
        int[] b = { 0 };
        root.watch(() -> {
            teams.track("a");
            return ++a[0];
        }, v -> {});
        root.watch(() -> {
            teams.track("b");
            return ++b[0];
        }, v -> {});
        assertEquals(2, teams.keyCount());
        teams.emit("a");
        lane.runRound();
        assertEquals(2, a[0]);
        assertEquals(1, b[0]);
        teams.emit("nobody");
        assertEquals(2, teams.keyCount());
        teams.emitAll();
        lane.runRound();
        assertEquals(3, a[0]);
        assertEquals(2, b[0]);
        teams.track("outside");
        assertEquals(2, teams.keyCount());
        root.dispose();
        assertEquals(0, teams.keyCount());
    }

    @Test
    void keyedSignalListenRunsAtOnceOnlyForItsKeyAndEndsWithTheScope() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var child = root.child("listener");
        var positions = new KeyedSignal<String>();
        List<String> log = new ArrayList<>();
        positions.listen(child, "a", () -> log.add("a"));
        positions.listen(child, "a", () -> log.add("a2"));
        positions.listen(root, "b", () -> log.add("b"));
        assertEquals(2, positions.keyCount());
        positions.emit("a");
        assertEquals(List.of("a", "a2"), log);
        positions.emit("nobody");
        positions.emit("b");
        assertEquals(List.of("a", "a2", "b"), log);
        child.dispose();
        assertEquals(1, positions.keyCount());
        positions.emit("a");
        assertEquals(3, log.size());
        positions.listen(root, "a", () -> log.add("again"));
        positions.emit("a");
        assertEquals(List.of("a", "a2", "b", "again"), log);
        root.dispose();
        assertEquals(0, positions.keyCount());
        positions.emitAll();
        assertEquals(4, log.size());
    }

    @Test
    void keyedSignalListenOnAnEndedScopeLeavesNoSlot() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var ended = root.child("ended");
        ended.dispose();
        var positions = new KeyedSignal<String>();
        int[] heard = { 0 };
        int anomalies = Scope.anomalyCount();
        positions.listen(ended, "a", () -> heard[0]++);
        assertEquals(anomalies + 1, Scope.anomalyCount());
        assertEquals(0, positions.keyCount());
        positions.listen(root, "a", () -> heard[0] += 10);
        positions.listen(ended, "a", () -> heard[0]++);
        assertEquals(1, positions.keyCount());
        positions.emit("a");
        assertEquals(10, heard[0]);
        root.dispose();
        assertEquals(0, positions.keyCount());
    }

    @Test
    void keyedSignalListenAndTrackShareAKeyAndOffThreadEmitsAreRelayed() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new KeyedSignal<Integer>();
        int[] derived = { 0 };
        int[] heard = { 0 };
        root.watch(() -> {
            signal.track(7);
            return ++derived[0];
        }, v -> {});
        signal.listen(root, 7, () -> heard[0]++);
        assertEquals(1, signal.keyCount());
        signal.emit(7);
        assertEquals(1, heard[0]);
        lane.runRound();
        assertEquals(2, derived[0]);
        onOtherThread(() -> signal.emit(7));
        assertEquals(1, heard[0]);
        lane.runRound();
        lane.runRound();
        assertEquals(2, heard[0]);
        assertEquals(3, derived[0]);
        assertThrows(IllegalStateException.class, () -> {
            Throwable[] thrown = new Throwable[1];
            onOtherThread(() -> {
                try {
                    signal.listen(root, 7, () -> {});
                } catch (Throwable t) {
                    thrown[0] = t;
                }
            });
            if (thrown[0] instanceof IllegalStateException e) throw e;
        });
        assertEquals(1, signal.keyCount());
        root.dispose();
        assertEquals(0, signal.keyCount());
    }

    @Test
    void keyedSignalListenerMayEmitTheSameKeyAgainWithoutLosingOthers() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new KeyedSignal<String>();
        int[] depth = { 0 };
        List<String> log = new ArrayList<>();
        signal.listen(root, "a", () -> {
            log.add("first" + depth[0]);
            if (depth[0]++ == 0) signal.emit("a");
        });
        signal.listen(root, "a", () -> log.add("second"));
        signal.emit("a");
        assertEquals(List.of("first0", "first1", "second", "second"), log);
        root.dispose();
        assertEquals(0, signal.keyCount());
    }

    @Test
    void keyedSignalKeysThatComeAndGoLeaveNothingBehind() throws InterruptedException {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new KeyedSignal<Integer>();
        var random = new Random(3);
        List<Cell<Integer>> keys = new ArrayList<>();
        List<OwnedScope> scopes = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            var key = Cell.of(random.nextInt(100_000));
            keys.add(key);
            var s = root.child("m");
            s.watch(() -> {
                int k = key.get();
                signal.track(k);
                return k;
            }, v -> {});
            scopes.add(s);
        }
        for (int step = 0; step < 20_000; step++) {
            keys.get(random.nextInt(keys.size())).set(random.nextInt(100_000));
            signal.emit(random.nextInt(100_000));
            if (step % 50 == 0) lane.runRound();
        }
        lane.runRound();
        Set<Integer> expected = new HashSet<>();
        for (var k : keys) expected.add(k.peek());
        assertEquals(expected.size(), signal.keyCount());
        onOtherThread(() -> {
            for (int i = 0; i < 10_000; i++) signal.emit(-i - 1);
        });
        assertEquals(expected.size(), signal.keyCount());
        for (var s : scopes) s.dispose();
        assertEquals(0, signal.keyCount());
    }

    @Test
    void keyedSignalMatchesABruteForceModel() {
        var random = new Random(11);
        for (int round = 0; round < 20; round++) {
            var lane = lane();
            var root = OwnedScope.root("root", lane);
            var signal = new KeyedSignal<Integer>();
            int derivations = 30;
            int[][] tracked = new int[derivations][];
            int[] evaluations = new int[derivations];
            int[] expected = new int[derivations];
            for (int d = 0; d < derivations; d++) {
                int[] keys = new int[1 + random.nextInt(3)];
                for (int i = 0; i < keys.length; i++) keys[i] = random.nextInt(10);
                tracked[d] = keys;
                int n = d;
                root.watch(() -> {
                    for (int k : keys) signal.track(k);
                    return ++evaluations[n];
                }, v -> {});
                expected[d] = 1;
            }
            boolean[] dirty = new boolean[derivations];
            for (int step = 0; step < 200; step++) {
                if (random.nextInt(4) == 0) {
                    lane.runRound();
                    for (int d = 0; d < derivations; d++) {
                        if (dirty[d]) expected[d]++;
                        dirty[d] = false;
                    }
                    assertArrayEquals(expected, evaluations);
                    continue;
                }
                int key = random.nextInt(12);
                signal.emit(key);
                for (int d = 0; d < derivations; d++) for (int k : tracked[d]) if (k == key) dirty[d] = true;
            }
            root.dispose();
            assertEquals(0, signal.keyCount());
        }
    }

    @Test
    void derivedEffectRevertsTheOldValueBeforeApplyingTheNewOne() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.<String>of("a");
        List<String> log = new ArrayList<>();
        int live = Scope.liveCount();
        root.effect(cell::get, v -> log.add("+" + v), v -> log.add("-" + v));
        assertEquals(List.of("+a"), log);
        assertEquals(live, Scope.liveCount());
        assertEquals(1, root.size());
        cell.set("b");
        lane.runRound();
        cell.set(null);
        lane.runRound();
        cell.set(null);
        lane.runRound();
        cell.set("c");
        lane.runRound();
        root.dispose();
        assertEquals(List.of("+a", "-a", "+b", "-b", "+c", "-c"), log);
        assertEquals(0, cell.changed().subscriberCount());
    }

    @Test
    void derivedEffectStartingFromNullAppliesNothing() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        root.<String>effect(() -> null, v -> log.add("+" + v), v -> log.add("-" + v));
        root.dispose();
        assertTrue(log.isEmpty());
    }

    @Test
    void derivedEffectRecordsFailuresAndRetriesAFailedApplyWhenAnyDependencyTriggers() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(1);
        var poke = new Signal();
        List<String> log = new ArrayList<>();
        int failures = Scope.failureCount();
        int[] applies = { 0 };
        root.effect(() -> {
            poke.track();
            return cell.get();
        }, v -> {
            applies[0]++;
            if (v == 1) throw new IllegalStateException("apply boom");
            log.add("+" + v);
        }, v -> {
            log.add("-" + v);
            if (v == 2) throw new IllegalStateException("revert boom");
        });
        assertEquals(failures + 1, Scope.failureCount());
        poke.emit();
        lane.runRound();
        assertEquals(2, applies[0]);
        assertEquals(failures + 2, Scope.failureCount());
        cell.set(2);
        lane.runRound();
        assertEquals(3, applies[0]);
        poke.emit();
        lane.runRound();
        assertEquals(3, applies[0]);
        cell.set(3);
        lane.runRound();
        assertEquals(failures + 3, Scope.failureCount());
        root.dispose();
        assertEquals(List.of("+2", "-2", "+3", "-3"), log);
    }

    @Test
    void derivedEffectWhoseApplyDisposesItsScopeIsReverted() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        List<String> log = new ArrayList<>();
        root.effect(() -> "x", v -> {
            log.add("+" + v);
            root.dispose();
        }, v -> log.add("-" + v));
        assertEquals(List.of("+x", "-x"), log);
        assertFalse(root.isActive());
    }

    @Test
    void derivedEffectReplacesTwoNestedSelects() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var teams = new KeyedSignal<String>();
        var owner = Cell.of("steve");
        var controllers = new java.util.HashMap<String, String>();
        controllers.put("steve", "c1");
        controllers.put("alex", "c2");
        List<String> log = new ArrayList<>();
        root.effect(() -> {
            String player = owner.get();
            teams.track(player);
            return controllers.get(player);
        }, c -> log.add("+" + c), c -> log.add("-" + c));
        controllers.put("steve", "c3");
        teams.emit("alex");
        lane.runRound();
        teams.emit("steve");
        lane.runRound();
        owner.set("alex");
        lane.runRound();
        assertEquals(1, teams.keyCount());
        root.dispose();
        assertEquals(List.of("+c1", "-c1", "+c3", "-c3", "+c2", "-c2"), log);
        assertEquals(0, teams.keyCount());
    }

    @Test
    void derivedEffectMatchesABruteForceModel() {
        var random = new Random(5);
        for (int round = 0; round < 30; round++) {
            var lane = lane();
            var root = OwnedScope.root("root", lane);
            var cells = new ArrayList<Cell<Integer>>();
            Set<String> applied = new HashSet<>();
            for (int i = 0; i < 8; i++) {
                var cell = Cell.<Integer>of(random.nextInt(4) == 0 ? null : random.nextInt(3));
                cells.add(cell);
                int n = i;
                root.effect(cell::get, v -> assertTrue(applied.add(n + ":" + v)), v -> assertTrue(applied.remove(n + ":" + v)));
            }
            for (int step = 0; step < 300; step++) {
                var cell = cells.get(random.nextInt(cells.size()));
                cell.set(random.nextInt(4) == 0 ? null : random.nextInt(3));
                if (random.nextInt(3) == 0) {
                    lane.runRound();
                    Set<String> model = new HashSet<>();
                    for (int i = 0; i < cells.size(); i++) if (cells.get(i).peek() != null) model.add(i + ":" + cells.get(i).peek());
                    assertEquals(model, applied);
                }
            }
            root.dispose();
            assertTrue(applied.isEmpty());
        }
    }

    @Test
    void slotEndsThePreviousScopeBeforeOpeningTheNextAndClosesIdempotently() {
        int baseline = Scope.liveCount();
        var lane = lane();
        var slot = new ScopeSlot();
        List<String> log = new ArrayList<>();
        assertFalse(slot.isOpen());
        slot.close();
        var first = slot.open("first", lane, s -> {
            log.add("+first");
            s.onDispose(() -> log.add("-first"));
        });
        assertSame(first, slot.current());
        var second = slot.open("second", lane, s -> {
            log.add("+second");
            s.onDispose(() -> log.add("-second"));
        });
        assertFalse(first.isActive());
        assertSame(second, slot.current());
        slot.close();
        slot.close();
        assertNull(slot.current());
        assertEquals(List.of("+first", "-first", "+second", "-second"), log);
        assertEquals(baseline, Scope.liveCount());
    }

    @Test
    void slotOnAClosedLaneRunsNoBodyAndStaysEmpty() {
        int baseline = Scope.liveCount();
        var lane = lane();
        var slot = new ScopeSlot();
        List<String> log = new ArrayList<>();
        slot.open("before", lane, s -> s.onDispose(() -> log.add("-before")));
        lane.close();
        assertEquals(List.of("-before"), log);
        assertFalse(slot.isOpen());
        var late = slot.open("late", lane, s -> log.add("+late"));
        assertFalse(late.isActive());
        assertFalse(slot.isOpen());
        slot.close();
        assertEquals(List.of("-before"), log);
        assertEquals(baseline, Scope.liveCount());
    }

    @Test
    void slotBodyFailureIsRecordedAndWoundBack() {
        var lane = lane();
        var slot = new ScopeSlot();
        List<String> log = new ArrayList<>();
        int failures = Scope.failureCount();
        var opened = slot.open("broken", lane, s -> {
            s.effect(() -> log.add("+a"), () -> log.add("-a"));
            s.effect(() -> {
                throw new IllegalStateException("apply boom");
            }, () -> log.add("-b"));
        });
        assertFalse(opened.isActive());
        assertFalse(slot.isOpen());
        assertEquals(List.of("+a", "-a"), log);
        assertEquals(failures + 1, Scope.failureCount());
    }

    @Test
    void slotRejectsOpeningFromAnotherThreadAndRelaysClosing() throws InterruptedException {
        var lane = lane();
        var slot = new ScopeSlot();
        var held = slot.open("held", lane, s -> {});
        List<String> rejected = new ArrayList<>();
        onOtherThread(() -> {
            try {
                slot.open("stray", lane, s -> {});
            } catch (IllegalStateException e) {
                rejected.add("open");
            }
        });
        assertEquals(List.of("open"), rejected);
        assertSame(held, slot.current());
        onOtherThread(slot::close);
        assertTrue(held.isActive());
        lane.runRound();
        assertFalse(held.isActive());
        assertFalse(slot.isOpen());
    }

    @Test
    void slotReopenedFromItsOwnBodyKeepsOnlyTheNewest() {
        var lane = lane();
        var slot = new ScopeSlot();
        AtomicInteger opened = new AtomicInteger();
        var outer = slot.open("outer", lane, s -> {
            opened.incrementAndGet();
            slot.open("inner", lane, inner -> opened.incrementAndGet());
        });
        assertFalse(outer.isActive());
        assertTrue(slot.isOpen());
        assertEquals("inner", slot.current().name());
        assertEquals(2, opened.get());
    }
}
