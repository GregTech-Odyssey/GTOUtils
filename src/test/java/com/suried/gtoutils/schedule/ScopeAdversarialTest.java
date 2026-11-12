package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScopeAdversarialTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    private static void onOtherThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var t = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.start();
        t.join();
        if (failure.get() instanceof RuntimeException e) throw e;
        if (failure.get() instanceof Error e) throw e;
    }

    @Test
    void postRacingTheFirstSubscriptionIsNeverLost() throws InterruptedException {
        int iterations = 100_000;
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(-1);
        AtomicInteger go = new AtomicInteger(-1);
        AtomicInteger posted = new AtomicInteger(-1);
        var writer = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                while (go.get() != i) Thread.onSpinWait();
                cell.post(i);
                posted.set(i);
            }
        });
        writer.setDaemon(true);
        writer.start();
        int[] seen = { -1 };
        int lost = 0;
        int live = Scope.liveCount();
        int anomalies = Scope.anomalyCount();
        for (int i = 0; i < iterations; i++) {
            go.set(i);
            var s = root.child("w");
            s.watch(cell::get, v -> seen[0] = v);
            while (posted.get() != i) Thread.onSpinWait();
            lane.runRound();
            lane.runRound();
            if (seen[0] != i) lost++;
            s.dispose();
        }
        writer.join();
        assertEquals(0, lost);
        assertEquals(0, cell.changed().subscriberCount());
        assertEquals(live, Scope.liveCount());
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(0, lane.linkedCount());
        root.dispose();
    }

    @Test
    void pendingPostStrandedByAClosedLaneLandsOnTheNextFirstSubscription() throws InterruptedException {
        var first = lane();
        var cell = Cell.of(0);
        OwnedScope.root("first", first).watch(cell::get, v -> {});
        onOtherThread(() -> cell.post(1));
        first.close();
        assertEquals(0, cell.peek());
        var second = lane();
        List<Integer> seen = new ArrayList<>();
        OwnedScope.root("second", second).watch(cell::get, seen::add);
        assertEquals(1, cell.peek());
        second.runRound();
        assertEquals(List.of(0, 1), seen);
        second.close();
    }

    @Test
    void postFromAnotherThreadBeforeAnySubscriberIsSeenByTheFirstSubscriber() throws InterruptedException {
        var lane = lane();
        var cell = Cell.of(0);
        onOtherThread(() -> cell.post(4));
        List<Integer> seen = new ArrayList<>();
        var root = OwnedScope.root("root", lane);
        root.watch(cell::get, seen::add);
        lane.runRound();
        assertEquals(4, seen.get(seen.size() - 1));
        root.dispose();
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void scopesHandedToCallbacksCannotBeCastToOwnedScope() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        List<Scope> handed = new ArrayList<>();
        root.select(() -> "v", (s, v) -> handed.add(s));
        root.when(() -> true, handed::add);
        Map<String, String> map = new HashMap<>();
        map.put("k", "v");
        root.each(() -> map, (s, k, v) -> handed.add(s));
        var control = ScopeKind.<String>byIdentity("adv-kind");
        control.kind().install("module", (s, owner) -> handed.add(s));
        handed.add(control.open("owner", lane));
        handed.add(control.kind().get("owner"));
        SharedPool<String, Scope> pool = root.pool("pool", 0, (pooled, key) -> pooled.scope());
        handed.add(root.lease(pool, "a").get());
        var slot = new ScopeSlot();
        handed.add(slot.open("slot", lane, handed::add));
        handed.add(slot.current());
        handed.add(slot.open("slot2", lane, "arg", (s, arg) -> handed.add(s)));
        assertEquals(12, handed.size());
        for (Scope s : handed) {
            assertNotNull(s);
            assertFalse(s instanceof OwnedScope, s.toString());
        }
        assertInstanceOf(OwnedScope.class, root.child("mine"));
        slot.close();
        control.closeAll();
        root.dispose();
    }

    @Test
    void registeringInsideADerivationFunctionCountsMisuseButStillWorks() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var pool = root.<String, String>pool("pool", 0, (pooled, key) -> key);
        var key = ScopeKey.<String>of("adv-service");
        var signal = new Signal();
        var slot = new ScopeSlot();
        List<Runnable> registrations = List.of(
                () -> root.onDispose(() -> {}),
                () -> root.listen(signal, () -> {}),
                () -> root.watch(() -> 1, v -> {}),
                () -> root.select(() -> 1, (s, v) -> {}),
                () -> root.when(() -> true, s -> {}),
                () -> root.each(HashMap::new, (s, k, v) -> {}),
                () -> root.effect(() -> {}, () -> {}),
                () -> root.effect(() -> "x", v -> {}, v -> {}),
                () -> root.effectWhile(() -> true, () -> {}, () -> {}),
                () -> root.provide(key, "service"),
                () -> root.lease(pool, "a"),
                () -> root.child("c"),
                () -> root.pool("p", 0, (pooled, k) -> k),
                () -> OwnedScope.root("orphan", lane).dispose(),
                () -> slot.open("slot", lane, s -> {}));
        for (Runnable registration : registrations) {
            int anomalies = Scope.anomalyCount();
            int[] ran = { 0 };
            var host = root.child("host");
            host.watch(() -> {
                registration.run();
                return ++ran[0];
            }, v -> {});
            assertEquals(1, ran[0]);
            assertEquals(anomalies + 1, Scope.anomalyCount());
            host.dispose();
        }
        slot.close();
        root.dispose();
    }

    @Test
    void bodiesMountsAndMutedFramesAreNotDerivations() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var cell = Cell.of(1);
        var signal = new Signal();
        int anomalies = Scope.anomalyCount();
        root.select(cell::get, (s, v) -> {
            s.listen(signal, () -> {});
            s.onDispose(() -> {});
            s.child("c");
            s.effect(() -> {}, () -> {});
        });
        root.when(() -> cell.get() > 0, s -> s.watch(cell::get, v -> s.onDispose(() -> {})));
        root.effect(cell::get, v -> root.child("applied"), v -> {});
        root.watch(() -> {
            Signal.Frame muted = Signal.mute();
            try {
                root.listen(signal, () -> {});
            } finally {
                Signal.unmute(muted);
            }
            return cell.get();
        }, v -> {});
        var control = ScopeKind.<String>byIdentity("adv-mount");
        control.kind().install("module", (s, owner) -> {
            s.listen(signal, () -> {});
            s.child("m");
        });
        control.open("owner", lane);
        cell.set(2);
        lane.runRound();
        assertEquals(anomalies, Scope.anomalyCount());
        control.closeAll();
        root.dispose();
    }

    @Test
    void effectWhileAppliesWhileTrueAndRevertsWhenFalseOrEnded() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var on = Cell.of(false);
        List<String> log = new ArrayList<>();
        int live = Scope.liveCount();
        int anomalies = Scope.anomalyCount();
        root.effectWhile(on::get, () -> log.add("+"), () -> log.add("-"));
        assertTrue(log.isEmpty());
        on.set(true);
        lane.runRound();
        on.set(true);
        lane.runRound();
        on.set(false);
        lane.runRound();
        on.set(true);
        lane.runRound();
        assertEquals(live, Scope.liveCount());
        assertEquals(1, root.size());
        root.dispose();
        assertEquals(List.of("+", "-", "+", "-"), log);
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(0, on.changed().subscriberCount());
    }

    @Test
    void effectWhileRetriesAFailedApplyAndKeepsRevertFailuresInside() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var on = Cell.of(true);
        var poke = new Signal();
        int failures = Scope.failureCount();
        int[] attempts = { 0 };
        List<String> log = new ArrayList<>();
        root.effectWhile(() -> {
            poke.track();
            return on.get();
        }, () -> {
            if (++attempts[0] == 1) throw new IllegalStateException("not ready");
            log.add("+");
        }, () -> {
            log.add("-");
            throw new IllegalStateException("revert boom");
        });
        assertEquals(failures + 1, Scope.failureCount());
        poke.emit();
        lane.runRound();
        assertEquals(List.of("+"), log);
        root.dispose();
        assertEquals(List.of("+", "-"), log);
        assertEquals(failures + 2, Scope.failureCount());
    }

    @Test
    void effectWhileMatchesABruteForceModel() {
        var random = new Random(17);
        for (int round = 0; round < 30; round++) {
            var lane = lane();
            var root = OwnedScope.root("root", lane);
            int n = 10;
            List<Cell<Boolean>> conditions = new ArrayList<>();
            Set<Integer> applied = new HashSet<>();
            for (int i = 0; i < n; i++) {
                var cell = Cell.of(random.nextBoolean());
                conditions.add(cell);
                int k = i;
                root.effectWhile(cell::get, () -> assertTrue(applied.add(k)), () -> assertTrue(applied.remove(k)));
            }
            for (int step = 0; step < 300; step++) {
                conditions.get(random.nextInt(n)).set(random.nextBoolean());
                if (random.nextInt(3) != 0) continue;
                lane.runRound();
                Set<Integer> model = new HashSet<>();
                for (int i = 0; i < n; i++) if (conditions.get(i).peek()) model.add(i);
                assertEquals(model, applied);
            }
            root.dispose();
            assertTrue(applied.isEmpty());
        }
    }

    @Test
    void derivedEffectOfABooleanOrOptionalCountsMisuseWhenTheValueChanges() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var flag = Cell.of(false);
        int anomalies = Scope.anomalyCount();
        root.effect(flag::get, v -> {}, v -> {});
        assertEquals(anomalies + 1, Scope.anomalyCount());
        flag.set(true);
        lane.runRound();
        assertEquals(anomalies + 2, Scope.anomalyCount());
        var holder = Cell.<Optional<String>>of(Optional.empty());
        root.effect(holder::get, v -> {}, v -> {});
        assertEquals(anomalies + 3, Scope.anomalyCount());
        root.effect(() -> "plain", v -> {}, v -> {});
        root.effect(() -> null, v -> {}, v -> {});
        assertEquals(anomalies + 3, Scope.anomalyCount());
        root.dispose();
    }

    @Test
    void revertThrowingAfterTheScopeEndedDuringApplyDoesNotEscape() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        int failures = Scope.failureCount();
        int live = Scope.liveCount();
        assertDoesNotThrow(() -> root.effect(() -> "x", v -> root.dispose(), v -> {
            throw new IllegalStateException("revert boom");
        }));
        assertFalse(root.isActive());
        assertEquals(failures + 1, Scope.failureCount());
        assertEquals(live - 1, Scope.liveCount());
    }

    @Test
    void repeatedIdenticalFailuresKeepCountingAndRemembering() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        int failures = Scope.failureCount();
        var cell = Cell.of(0);
        root.watch(cell::get, v -> {
            if (v > 0) throw new IllegalStateException("same failure");
        });
        for (int i = 1; i <= 2500; i++) {
            cell.set(i);
            lane.runRound();
        }
        assertEquals(failures + 2500, Scope.failureCount());
        List<String> recent = Scope.recentFailures();
        assertEquals(32, recent.size());
        for (String line : recent) assertTrue(line.contains("same failure"), line);
        root.dispose();
    }

    @Test
    void foreignDisposeOnAClosedLaneLeavesTeardownToTheLaneThread() throws InterruptedException {
        var lane = lane();
        Thread laneThread = Thread.currentThread();
        int live = Scope.liveCount();
        var root = OwnedScope.root("root", lane);
        List<Thread> undone = new ArrayList<>();
        root.onDispose(() -> undone.add(Thread.currentThread()));
        int anomalies = Scope.anomalyCount();
        onOtherThread(() -> {
            lane.close();
            root.dispose();
            root.dispose();
        });
        assertEquals(anomalies + 1, Scope.anomalyCount());
        assertTrue(root.isActive());
        assertTrue(undone.isEmpty());
        lane.runRound();
        assertFalse(root.isActive());
        assertEquals(List.of(laneThread), undone);
        lane.runRound();
        assertEquals(1, undone.size());
        assertEquals(live, Scope.liveCount());
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void closingFromTheLaneThreadOrDuringARoundStillClearsAtOnce() throws InterruptedException {
        int live = Scope.liveCount();
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        int anomalies = Scope.anomalyCount();
        lane.close();
        assertFalse(root.isActive());
        assertEquals(anomalies, Scope.anomalyCount());
        var other = lane();
        var held = OwnedScope.root("held", other);
        other.schedule(0, null, () -> {
            try {
                onOtherThread(other::close);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        other.runRound();
        assertFalse(held.isActive());
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(live, Scope.liveCount());
    }

    @Test
    void laneClaimedForAThreadIsForeignToEveryOtherThreadBeforeItEverRuns() throws InterruptedException {
        var lane = new TickScheduler(true, RETHROW);
        assertFalse(lane.foreign());
        Thread owner = new Thread(() -> {});
        lane.claim(owner);
        assertTrue(lane.foreign());
        boolean[] fromProbe = { false };
        Thread probe = new Thread(() -> fromProbe[0] = lane.foreign());
        lane.claim(probe);
        probe.start();
        probe.join();
        assertTrue(fromProbe[0]);
        assertThrows(IllegalStateException.class, () -> OwnedScope.root("off", lane));
        lane.runRound();
        assertFalse(lane.foreign());
        lane.close();
    }

    @Test
    void slotOpenWithAnArgumentBehavesLikeTheConsumerForm() {
        int live = Scope.liveCount();
        var lane = lane();
        var slot = new ScopeSlot();
        List<String> log = new ArrayList<>();
        BiConsumer<Scope, List<String>> body = (s, out) -> {
            out.add("+" + s.name());
            s.onDispose(() -> out.add("-" + s.name()));
        };
        var first = slot.open("first", lane, log, body);
        assertSame(first, slot.current());
        var second = slot.open("second", lane, log, body);
        assertFalse(first.isActive());
        assertSame(second, slot.current());
        int failures = Scope.failureCount();
        var broken = slot.open("broken", lane, log, (s, out) -> {
            body.accept(s, out);
            throw new IllegalStateException("body boom");
        });
        assertFalse(broken.isActive());
        assertFalse(slot.isOpen());
        assertEquals(failures + 1, Scope.failureCount());
        lane.close();
        var late = slot.open("late", lane, log, body);
        assertFalse(late.isActive());
        assertEquals(List.of("+first", "-first", "+second", "-second", "+broken", "-broken"), log);
        assertEquals(live, Scope.liveCount());
    }

    @Test
    void slotOpenOnAnAsyncLaneFailsBeforeTouchingTheHeldScope() {
        var lane = lane();
        var async = new TickScheduler(false, RETHROW);
        var slot = new ScopeSlot();
        var held = slot.open("held", lane, s -> {});
        assertThrows(IllegalStateException.class, () -> slot.open("async", async, s -> {}));
        assertSame(held, slot.current());
        assertTrue(held.isActive());
        slot.close();
        assertFalse(held.isActive());
    }

    @Test
    void keyedSignalSlotWhoseSubscriptionFailedIsNotLeft() throws InterruptedException {
        var lane = lane();
        var signal = new KeyedSignal<String>();
        var root = OwnedScope.root("root", lane);
        root.watch(() -> {
            signal.track("shared");
            return 0;
        }, v -> {});
        assertEquals(1, signal.keyCount());
        int failures = Scope.failureCount();
        int live = Scope.liveCount();
        onOtherThread(() -> {
            var other = lane();
            var stranger = OwnedScope.root("stranger", other);
            stranger.watch(() -> {
                signal.track("shared");
                signal.track("fresh");
                return 0;
            }, v -> {});
            stranger.dispose();
            other.close();
        });
        assertEquals(failures + 1, Scope.failureCount());
        assertEquals(1, signal.keyCount());
        assertEquals(live, Scope.liveCount());
        root.dispose();
        assertEquals(0, signal.keyCount());
    }

    @Test
    void keyedEmitFromAnotherThreadReachesASlotRebuiltBeforeTheRelayRuns() throws InterruptedException {
        var lane = lane();
        var signal = new KeyedSignal<String>();
        var root = OwnedScope.root("root", lane);
        var before = root.child("before");
        before.watch(() -> {
            signal.track("k");
            return 0;
        }, v -> {});
        onOtherThread(() -> signal.emit("k"));
        before.dispose();
        assertEquals(0, signal.keyCount());
        int[] evaluations = { 0 };
        root.watch(() -> {
            signal.track("k");
            return ++evaluations[0];
        }, v -> {});
        lane.runRound();
        assertEquals(2, evaluations[0]);
        root.dispose();
        assertEquals(0, signal.keyCount());
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void waitListUsedFromAnotherThreadFailsAnAssertion() throws InterruptedException {
        assumeTrue(WaitList.class.desiredAssertionStatus());
        var list = new WaitList<String>();
        var mine = new WaitList.Waiter<>("mine");
        list.await(mine);
        List<String> caught = new ArrayList<>();
        onOtherThread(() -> {
            var theirs = new WaitList.Waiter<>("theirs");
            try {
                list.await(theirs);
            } catch (AssertionError e) {
                caught.add("await");
            }
            try {
                mine.cancel();
            } catch (AssertionError e) {
                caught.add("cancel");
            }
            try {
                list.fire(s -> {});
            } catch (AssertionError e) {
                caught.add("fire");
            }
        });
        assertEquals(List.of("await", "cancel", "fire"), caught);
        assertEquals(1, list.size());
        assertTrue(mine.waiting());
        list.fire(s -> {});
        assertTrue(list.isEmpty());
    }

    @Test
    void strictModeTurnsMisuseIntoAnException() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        root.dispose();
        Scope.strictOverride = true;
        try {
            var e = assertThrows(IllegalStateException.class, () -> root.listen(new Signal(), () -> {}));
            assertTrue(e.getMessage().contains(Scope.ON_ENDED), e.getMessage());
            assertTrue(e.getMessage().contains("root"), e.getMessage());
        } finally {
            Scope.strictOverride = false;
        }
        int anomalies = Scope.anomalyCount();
        assertDoesNotThrow(() -> root.listen(new Signal(), () -> {}));
        assertEquals(anomalies + 1, Scope.anomalyCount());
    }

    @Test
    void registeringOnAnEndedScopeCountsMisuseUnlessTheLaneClosed() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var pool = root.<String, String>pool("pool", 0, (pooled, key) -> key);
        var ended = OwnedScope.root("ended", lane);
        ended.dispose();
        var key = ScopeKey.<String>of("adv-ended");
        var signal = new Signal();
        List<Runnable> flagged = List.of(
                () -> ended.listen(signal, () -> {}),
                () -> ended.watch(() -> 1, v -> {}),
                () -> ended.select(() -> 1, (s, v) -> {}),
                () -> ended.when(() -> true, s -> {}),
                () -> ended.each(HashMap::new, (s, k, v) -> {}),
                () -> ended.effect(() -> {}, () -> {}),
                () -> ended.effect(() -> "x", v -> {}, v -> {}),
                () -> ended.effectWhile(() -> true, () -> {}, () -> {}),
                () -> ended.provide(key, "v"));
        for (Runnable r : flagged) {
            int anomalies = Scope.anomalyCount();
            r.run();
            assertEquals(anomalies + 1, Scope.anomalyCount());
        }
        int[] undone = { 0 };
        List<Runnable> quiet = List.of(
                () -> ended.onDispose(() -> undone[0]++),
                () -> ended.repeat(Cadence.EVERY_TICK, () -> {}),
                () -> ended.schedule(1, () -> {}),
                () -> ended.trigger(0, () -> {}).fire(),
                () -> ended.lease(pool, "a"));
        int anomalies = Scope.anomalyCount();
        for (Runnable r : quiet) r.run();
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(1, undone[0]);
        assertEquals(0, signal.subscriberCount());
        var survivor = OwnedScope.root("survivor", lane);
        lane.close();
        assertFalse(survivor.isActive());
        for (Runnable r : flagged) r.run();
        survivor.listen(signal, () -> {});
        assertEquals(anomalies, Scope.anomalyCount());
        assertEquals(0, signal.subscriberCount());
    }

    @Test
    void halfAppliedEffectThatThrowsIsNeverRevertedAndIsRetried() {
        var lane = lane();
        int live = Scope.liveCount();
        int failures = Scope.failureCount();
        var root = OwnedScope.root("root", lane);
        var poke = new Signal();
        Set<String> registry = new HashSet<>();
        int[] attempts = { 0 };
        List<String> reverted = new ArrayList<>();
        root.effect(() -> {
            poke.track();
            return "port";
        }, v -> {
            registry.add(v + "#" + attempts[0]);
            if (attempts[0]++ == 0) throw new IllegalStateException("half applied");
        }, reverted::add);
        assertEquals(Set.of("port#0"), registry);
        assertTrue(reverted.isEmpty());
        poke.emit();
        lane.runRound();
        assertEquals(Set.of("port#0", "port#1"), registry);
        poke.emit();
        lane.runRound();
        assertEquals(2, attempts[0]);
        root.dispose();
        assertEquals(List.of("port"), reverted);
        assertEquals(failures + 1, Scope.failureCount());
        assertEquals(live, Scope.liveCount());
        assertEquals(0, poke.subscriberCount());
    }

    @Test
    void revertThatRepostsItsCellAndEndsASiblingSettlesInTheSameRound() {
        int live = Scope.liveCount();
        int failures = Scope.failureCount();
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var sibling = root.child("sibling");
        List<String> siblingLog = new ArrayList<>();
        sibling.onDispose(() -> siblingLog.add("ended"));
        var cell = Cell.of(1);
        Set<Integer> applied = new HashSet<>();
        root.effect(cell::get, v -> assertTrue(applied.add(v)), v -> {
            assertTrue(applied.remove(v));
            if (v == 2) {
                cell.post(3);
                sibling.dispose();
            }
        });
        cell.set(2);
        lane.runRound();
        assertEquals(Set.of(2), applied);
        cell.set(5);
        lane.runRound();
        assertEquals(3, cell.peek());
        assertEquals(Set.of(3), applied);
        assertEquals(List.of("ended"), siblingLog);
        assertFalse(sibling.isActive());
        lane.runRound();
        assertEquals(Set.of(3), applied);
        root.dispose();
        assertTrue(applied.isEmpty());
        assertEquals(failures, Scope.failureCount());
        assertEquals(live, Scope.liveCount());
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void derivationThatSpilledPastTheChainLimitStaysOneRoundBehindForGood() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        int length = TickScheduler.SETTLE_DEPTH + 2;
        List<Cell<Integer>> cells = new ArrayList<>();
        for (int i = 0; i <= length; i++) cells.add(Cell.of(0));
        boolean[] chaining = { true };
        long[] evaluatedIn = new long[length];
        for (int i = 0; i < length; i++) {
            int k = i;
            root.watch(() -> cells.get(k).get(), v -> {
                evaluatedIn[k] = lane.currentTick();
                if (chaining[0]) cells.get(k + 1).set(v);
            });
        }
        int anomalies = Scope.anomalyCount();
        lane.schedule(0, null, () -> cells.get(0).set(1));
        lane.runRound();
        lane.runRound();
        assertTrue(Scope.anomalyCount() > anomalies);
        chaining[0] = false;
        int spilled = TickScheduler.SETTLE_DEPTH;
        lane.schedule(0, null, () -> {
            cells.get(1).set(9);
            cells.get(spilled).set(9);
        });
        lane.runRound();
        long round = lane.round();
        assertEquals(round, evaluatedIn[1]);
        assertNotEquals(round, evaluatedIn[spilled]);
        lane.runRound();
        assertEquals(round + 1, evaluatedIn[spilled]);
        root.dispose();
        assertEquals(0, lane.linkedCount());
    }
}
