package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScopeHardeningTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    private static int roots(TickScheduler lane) {
        int[] n = { 0 };
        lane.forEachScope(s -> n[0]++);
        return n[0];
    }

    @Test
    void repeatedDisposeFromEverywhereTearsDownOnce() throws InterruptedException {
        int baseline = Scope.liveCount();
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var child = root.child("child");
        AtomicInteger undone = new AtomicInteger();
        child.onDispose(undone::incrementAndGet);
        child.onDispose(child::dispose);
        child.onDispose(root::dispose);
        var other = new Thread(() -> {
            for (int i = 0; i < 1000; i++) child.dispose();
        });
        other.start();
        other.join();
        assertTrue(child.isActive());
        child.dispose();
        child.dispose();
        lane.runRound();
        lane.runRound();
        assertEquals(1, undone.get());
        assertFalse(root.isActive());
        assertEquals(baseline, Scope.liveCount());
        assertEquals(0, roots(lane));
        assertEquals(0, lane.linkedCount());
    }

    @Test
    void effectWithoutARevertNeverApplies() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        assertThrows(NullPointerException.class, () -> root.effect(() -> log.add("apply"), null));
        assertTrue(log.isEmpty());
        root.dispose();
        assertTrue(log.isEmpty());
    }

    @Test
    void effectWhoseApplyThrowsInsideABodyWindsBackWhatTheBodyHadRegistered() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var on = Cell.of(true);
        List<String> log = new ArrayList<>();
        boolean[] fail = { true };
        int failures = Scope.failureCount();
        root.when(on::get, s -> {
            s.effect(() -> log.add("+a"), () -> log.add("-a"));
            s.effect(() -> {
                if (fail[0]) throw new IllegalStateException("apply boom");
                log.add("+b");
            }, () -> log.add("-b"));
        });
        assertEquals(List.of("+a", "-a"), log);
        assertEquals(failures + 1, Scope.failureCount());
        assertTrue(Scope.recentFailures().get(Scope.recentFailures().size() - 1).contains("apply boom"));
        fail[0] = false;
        on.set(false);
        lane.runRound();
        on.set(true);
        lane.runRound();
        assertEquals(List.of("+a", "-a", "+a", "+b"), log);
        root.dispose();
        assertEquals(List.of("+a", "-a", "+a", "+b", "-b", "-a"), log);
    }

    @Test
    void effectApplyThrowingOnARootReachesTheCallerAndRegistersNothing() {
        var root = OwnedScope.root("root", lane());
        List<String> log = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> root.effect(() -> {
            throw new IllegalStateException("apply boom");
        }, () -> log.add("revert")));
        root.effect(() -> log.add("+ok"), () -> log.add("-ok"));
        root.dispose();
        assertEquals(List.of("+ok", "-ok"), log);
    }

    @Test
    void throwingRevertsDoNotStopTheRestWhenTheLaneCloses() {
        int baseline = Scope.liveCount();
        var lane = lane();
        List<String> log = new ArrayList<>();
        int failures = Scope.failureCount();
        for (int i = 0; i < 4; i++) {
            int n = i;
            var root = OwnedScope.root("m" + i, lane);
            root.effect(() -> {}, () -> log.add("first" + n));
            root.effect(() -> {}, () -> {
                throw new IllegalStateException("revert boom");
            });
            root.child("c").effect(() -> {}, () -> log.add("child" + n));
        }
        lane.close();
        assertEquals(4 * 2, log.size());
        assertEquals(failures + 4, Scope.failureCount());
        assertEquals(baseline, Scope.liveCount());
    }

    @Test
    void listenerOfAScopeBeingTornDownIsNotCalledByAnEmitFromItsOwnUndo() {
        var lane = lane();
        var root = OwnedScope.root("root", lane);
        var signal = new Signal();
        List<String> log = new ArrayList<>();
        var watcher = root.child("watcher");
        watcher.listen(signal, () -> log.add("heard while " + (watcher.isActive() ? "active" : "ended")));
        watcher.onDispose(signal::emit);
        var bystander = root.child("bystander");
        bystander.listen(signal, () -> log.add("bystander"));
        watcher.dispose();
        assertEquals(List.of("bystander"), log);
        assertEquals(1, signal.subscriberCount());
    }

    @Test
    void undoMayDisposeSiblingsAndParentsEmitAndOpenScopes() {
        int baseline = Scope.liveCount();
        var lane = lane();
        var parent = OwnedScope.root("parent", lane);
        var a = parent.child("a");
        var b = parent.child("b");
        var signal = new Signal();
        var cell = Cell.of(0);
        List<String> log = new ArrayList<>();
        parent.watch(cell::get, v -> log.add("watch " + v));
        var survivor = OwnedScope.root("survivor", lane);
        survivor.watch(cell::get, v -> log.add("survivor " + v));
        survivor.listen(signal, () -> log.add("survivor heard"));
        List<OwnedScope> opened = new ArrayList<>();
        b.onDispose(() -> log.add("b"));
        a.onDispose(() -> {
            b.dispose();
            signal.emit();
            cell.set(1);
            opened.add(OwnedScope.root("opened in undo", lane));
            opened.add(parent.child("late child"));
            parent.dispose();
            log.add("a");
        });
        a.dispose();
        lane.runRound();
        assertEquals(List.of("watch 0", "survivor 0", "b", "survivor heard", "a", "survivor 1"), log);
        assertTrue(opened.get(0).isActive());
        assertFalse(opened.get(1).isActive());
        assertFalse(parent.isActive());
        opened.get(0).dispose();
        survivor.dispose();
        assertEquals(baseline, Scope.liveCount());
        assertEquals(0, roots(lane));
        assertEquals(0, signal.subscriberCount());
        assertEquals(0, cell.changed().subscriberCount());
    }

    @Test
    void undoThatClosesTheLaneEndsEverythingOnce() {
        int baseline = Scope.liveCount();
        var lane = lane();
        AtomicInteger undone = new AtomicInteger();
        var first = OwnedScope.root("first", lane);
        for (int i = 0; i < 10; i++) OwnedScope.root("r" + i, lane).onDispose(undone::incrementAndGet);
        first.onDispose(lane::close);
        first.onDispose(() -> OwnedScope.root("reopened", lane).onDispose(undone::incrementAndGet));
        first.dispose();
        assertEquals(11, undone.get());
        assertEquals(baseline, Scope.liveCount());
        assertEquals(0, roots(lane));
    }

    @Test
    void openingAndClosingManyMachinesReturnsEverythingToBaseline() {
        int baseline = Scope.liveCount();
        int failures = Scope.failureCount();
        var lane = lane();
        var teams = new Signal();
        var random = new Random(7);
        int machines = 1000;
        @SuppressWarnings("unchecked")
        Cell<Boolean>[] formed = new Cell[machines];
        @SuppressWarnings("unchecked")
        Cell<UUID>[] owners = new Cell[machines];
        @SuppressWarnings("unchecked")
        Cell<Integer>[] controllers = new Cell[machines];
        OwnedScope[] open = new OwnedScope[machines];
        for (int i = 0; i < machines; i++) {
            formed[i] = Cell.of(true);
            owners[i] = Cell.of(new UUID(0, i % 13));
            controllers[i] = Cell.of(i % 5);
        }
        int[] applied = { 0 };
        for (int step = 0; step < 100_000; step++) {
            int i = random.nextInt(machines);
            if (open[i] != null && random.nextInt(3) > 0) {
                open[i].dispose();
                open[i] = null;
            } else if (open[i] == null) {
                var s = OwnedScope.root(i, lane);
                int n = i;
                switch (i % 3) {
                    case 0 -> s.effect(() -> applied[0]++, () -> applied[0]--);
                    case 1 -> s.when(formed[n]::get, w -> w.effect(() -> applied[0]++, () -> applied[0]--));
                    default -> s.select(() -> {
                        teams.track();
                        return owners[n].get();
                    }, (t, team) -> t.select(controllers[n]::get, (c, ctrl) -> c.effect(() -> applied[0]++, () -> applied[0]--)));
                }
                open[i] = s;
            }
            switch (random.nextInt(16)) {
                case 0 -> teams.emit();
                case 1 -> formed[random.nextInt(machines)].set(random.nextBoolean());
                case 2 -> controllers[random.nextInt(machines)].set(random.nextInt(5));
                case 3 -> owners[random.nextInt(machines)].set(new UUID(0, random.nextInt(13)));
                case 4 -> lane.runRound();
                default -> {}
            }
        }
        for (int i = 0; i < machines; i++) if (open[i] != null) open[i].dispose();
        for (int i = 0; i < TickScheduler.WHEEL_SIZE; i++) lane.runRound();
        assertEquals(0, applied[0]);
        assertEquals(baseline, Scope.liveCount());
        assertEquals(failures, Scope.failureCount());
        assertEquals(0, roots(lane));
        assertEquals(0, lane.linkedCount());
        assertEquals(0, teams.subscriberCount());
        for (int i = 0; i < machines; i++) {
            assertEquals(0, formed[i].changed().subscriberCount());
            assertEquals(0, owners[i].changed().subscriberCount());
            assertEquals(0, controllers[i].changed().subscriberCount());
        }
    }

    @Test
    void hundredThousandOpenCloseCyclesOfEveryBodyReturnToBaseline() {
        int baseline = Scope.liveCount();
        int failures = Scope.failureCount();
        var lane = lane();
        var teams = new KeyedSignal<Integer>();
        var formed = Cell.of(true);
        var controller = Cell.of(1);
        var slot = new ScopeSlot();
        int[] applied = { 0 };
        for (int i = 0; i < 100_000; i++) {
            int team = i % 97;
            switch (i % 5) {
                case 0 -> OwnedScope.root(i, lane).dispose();
                case 1 -> {
                    var s = OwnedScope.root(i, lane);
                    s.when(formed::get, w -> w.effect(() -> applied[0]++, () -> applied[0]--));
                    formed.set((i & 8) == 0);
                    s.dispose();
                }
                case 2 -> {
                    var s = OwnedScope.root(i, lane);
                    s.effect(() -> {
                        teams.track(team);
                        return controller.get();
                    }, c -> applied[0]++, c -> applied[0]--);
                    teams.emit(team);
                    s.dispose();
                }
                case 3 -> slot.open(i, lane, s -> s.effect(() -> applied[0]++, () -> applied[0]--));
                default -> {
                    var s = OwnedScope.root(i, lane);
                    s.select(() -> {
                        teams.track(team);
                        return team;
                    }, (t, v) -> t.select(controller::get, (c, ctrl) -> c.effect(() -> applied[0]++, () -> applied[0]--)));
                    controller.set(i & 3);
                    s.dispose();
                }
            }
            if (i % 64 == 0) lane.runRound();
        }
        slot.close();
        for (int i = 0; i < TickScheduler.WHEEL_SIZE; i++) lane.runRound();
        assertEquals(0, applied[0]);
        assertEquals(baseline, Scope.liveCount());
        assertEquals(failures, Scope.failureCount());
        assertEquals(0, roots(lane));
        assertEquals(0, lane.linkedCount());
        assertEquals(0, teams.keyCount());
        assertEquals(0, formed.changed().subscriberCount());
        assertEquals(0, controller.changed().subscriberCount());
    }

    @Test
    void scopeOpenedOnALaneThatClosedMeanwhileLeavesNothingBehind() {
        int baseline = Scope.liveCount();
        var lane = lane();
        lane.close();
        var cell = Cell.of(true);
        List<String> log = new ArrayList<>();
        var late = OwnedScope.root("late", lane);
        late.when(cell::get, s -> s.effect(() -> log.add("apply"), () -> log.add("revert")));
        late.dispose();
        assertTrue(log.isEmpty());
        assertEquals(0, cell.changed().subscriberCount());
        assertEquals(baseline, Scope.liveCount());
        assertEquals(0, roots(lane));
    }
}
