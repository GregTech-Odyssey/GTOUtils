package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler started() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    @Test
    void delayZeroRunsInTheComingRound() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        s.schedule(() -> ran.add(s.round()));
        s.schedule(3, () -> ran.add(s.round()));
        long r = s.round();
        for (int i = 0; i < 5; i++) s.runRound();
        assertEquals(List.of(r + 1, r + 4), ran);
    }

    @Test
    void submitsBeforeTheFirstRoundGoThroughTheInbox() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        List<Long> ran = new ArrayList<>();
        s.schedule(() -> ran.add(s.round()));
        s.schedule(2, () -> ran.add(s.round()));
        for (int i = 0; i < 4; i++) s.runRound();
        assertEquals(List.of(1L, 3L), ran);
    }

    @Test
    void tasksSubmittedInsideARoundRunNoEarlierThanTheNextRound() {
        TickScheduler s = started();
        List<String> ran = new ArrayList<>();
        s.schedule(() -> {
            ran.add("outer@" + s.round());
            s.schedule(() -> ran.add("inner@" + s.round()));
            s.repeat(Cadence.EVERY_TICK, () -> ran.add("rep@" + s.round()));
        });
        long r = s.round();
        s.runRound();
        s.runRound();
        assertEquals(List.of("outer@" + (r + 1), "rep@" + (r + 2), "inner@" + (r + 2)), ran);
    }

    @Test
    void scheduleAtUsesAbsoluteRoundsAndClampsToTheEarliestRound() {
        TickScheduler s = started();
        List<String> ran = new ArrayList<>();
        long now = s.currentTick();
        assertEquals(s.round() + 1, now);
        s.scheduleAt(now + 5, null, () -> ran.add("a@" + s.round()));
        s.scheduleAt(now - 3, null, () -> ran.add("late@" + s.round()));
        s.schedule(() -> {
            long inside = s.currentTick();
            ran.add("inside=" + (inside == s.round()));
            s.scheduleAt(inside, null, () -> ran.add("same@" + s.round()));
            s.scheduleAt(inside + 2, null, () -> ran.add("b@" + s.round()));
        });
        for (int i = 0; i < 8; i++) s.runRound();
        assertEquals(List.of("late@" + now, "inside=true", "same@" + (now + 1), "b@" + (now + 2), "a@" + (now + 5)), ran);
    }

    @Test
    void oneShotsInTheSameRoundRunInSubmissionOrder() {
        TickScheduler s = started();
        List<Integer> ran = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int id = i;
            s.schedule(7, () -> ran.add(id));
        }
        for (int i = 0; i < 9; i++) s.runRound();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) expected.add(i);
        assertEquals(expected, ran);
    }

    @Test
    void farDelaysPassThroughTheWheelUntilDue() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        long r = s.round();
        int[] delays = {255, 256, 257, 511, 512, 1000, 5000};
        for (int d : delays) s.schedule(d, () -> ran.add(s.round()));
        for (int i = 0; i < 5002; i++) s.runRound();
        List<Long> expected = new ArrayList<>();
        for (int d : delays) expected.add(r + 1 + d);
        assertEquals(expected, ran);
        assertEquals(0, s.linkedCount());
    }

    @Test
    void cancelledTasksNeverRunAndReportInactive() {
        TickScheduler s = started();
        AtomicInteger runs = new AtomicInteger();
        Task once = s.schedule(2, runs::incrementAndGet);
        Task rep = s.repeat(Cadence.every(3), runs::incrementAndGet);
        assertTrue(once.isActive());
        assertTrue(rep.isActive());
        once.cancel();
        assertNull(Task.cancel(rep));
        assertFalse(once.isActive());
        assertFalse(rep.isActive());
        for (int i = 0; i < 10; i++) s.runRound();
        assertEquals(0, runs.get());
        assertEquals(0, s.linkedCount());
    }

    @Test
    void oneShotBecomesInactiveAfterRunning() {
        TickScheduler s = started();
        Task t = s.schedule(() -> {});
        s.runRound();
        assertFalse(t.isActive());
        assertEquals(0, t.period());
        assertThrows(IllegalStateException.class, () -> t.setPeriod(5));
    }

    @Test
    void fixedPhaseRunsWhenRoundMatches() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        s.repeat(Cadence.every(20).phase(8), () -> ran.add(s.round()));
        for (int i = 0; i < 100; i++) s.runRound();
        assertFalse(ran.isEmpty());
        for (long r : ran) assertEquals(8, r % 20);
        for (int i = 1; i < ran.size(); i++) assertEquals(20, ran.get(i) - ran.get(i - 1));
    }

    @Test
    void autoPhasesSpreadEvenlyOverTheBuckets() {
        TickScheduler s = started();
        int period = 20;
        int[] perRound = new int[period];
        for (int i = 0; i < 1000; i++) s.repeat(Cadence.every(period), () -> perRound[(int) (s.round() % period)]++);
        for (int i = 0; i < period; i++) s.runRound();
        int min = Integer.MAX_VALUE, max = 0;
        for (int c : perRound) {
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        assertEquals(50, min);
        assertEquals(50, max);
    }

    @Test
    void periodicFirstRunFallsWithinOnePeriod() {
        TickScheduler s = started();
        long r = s.round();
        List<Long> ran = new ArrayList<>();
        s.repeat(Cadence.every(7), () -> ran.add(s.round()));
        for (int i = 0; i < 30; i++) s.runRound();
        assertTrue(ran.get(0) >= r + 1 && ran.get(0) <= r + 7);
        for (int i = 1; i < ran.size(); i++) assertEquals(7, ran.get(i) - ran.get(i - 1));
    }

    @Test
    void setPeriodTakesEffectAfterTheNextRun() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        Task t = s.repeat(Cadence.every(10).phase(5), () -> ran.add(s.round()));
        while (ran.isEmpty()) s.runRound();
        long first = ran.get(0);
        t.setPeriod(3);
        assertEquals(3, t.period());
        while (ran.size() < 4) s.runRound();
        assertEquals(List.of(first, first + 10, first + 13, first + 16), ran);
        assertEquals(1, s.groupCount() - 1);
    }

    @Test
    void setPeriodBackToTheCurrentValueDropsThePendingChange() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        Task t = s.repeat(Cadence.every(4).phase(1), () -> ran.add(s.round()));
        t.setPeriod(9);
        t.setPeriod(4);
        assertEquals(4, t.period());
        for (int i = 0; i < 20; i++) s.runRound();
        for (int i = 1; i < ran.size(); i++) assertEquals(4, ran.get(i) - ran.get(i - 1));
    }

    @Test
    void zeroAndNegativePeriodsMeanEveryRound() {
        TickScheduler s = started();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        s.repeat(Cadence.every(0), a::incrementAndGet);
        s.repeat(Cadence.every(-5), b::incrementAndGet);
        for (int i = 0; i < 10; i++) s.runRound();
        assertEquals(10, a.get());
        assertEquals(10, b.get());
    }

    @Test
    void untilStopsTheTaskBeforeItRuns() {
        TickScheduler s = started();
        int[] counter = {0};
        Task t = s.repeat(Cadence.EVERY_TICK, counter, c -> c[0]++, c -> c[0] >= 3);
        for (int i = 0; i < 10; i++) s.runRound();
        assertEquals(3, counter[0]);
        assertFalse(t.isActive());
        assertEquals(0, s.linkedCount());
    }

    @Test
    void periodsBeyondTheGroupLimitUseTheWheel() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        int p = TickScheduler.MAX_GROUP_PERIOD + 7;
        s.repeat(Cadence.every(p).phase(3), () -> ran.add(s.round()));
        for (int i = 0; i < 3 * p; i++) s.runRound();
        assertTrue(ran.size() >= 2);
        for (long r : ran) assertEquals(3, r % p);
        assertEquals(0, s.groupCount());
    }

    @Test
    void coalescedTaskRunsOncePerQueueing() {
        TickScheduler s = started();
        AtomicInteger runs = new AtomicInteger();
        CoalescedTask t = new CoalescedTask(runs::incrementAndGet);
        for (int i = 0; i < 10; i++) s.submit(t);
        assertTrue(t.isQueued());
        s.runRound();
        assertEquals(1, runs.get());
        assertFalse(t.isQueued());
        s.submit(t);
        s.runRound();
        assertEquals(2, runs.get());
    }

    @Test
    void coalescedTaskResubmittedWhileRunningRunsNextRound() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        CoalescedTask[] holder = new CoalescedTask[1];
        holder[0] = new CoalescedTask(() -> {
            ran.add(s.round());
            if (ran.size() < 3) s.submit(holder[0]);
        });
        s.submit(holder[0]);
        long r = s.round();
        for (int i = 0; i < 6; i++) s.runRound();
        assertEquals(List.of(r + 1, r + 2, r + 3), ran);
    }

    @Test
    void delayedCoalescedTaskIsATrailingWindow() {
        TickScheduler s = started();
        List<Long> ran = new ArrayList<>();
        CoalescedTask t = new CoalescedTask(() -> ran.add(s.round()));
        long r = s.round();
        s.submit(t, 10);
        for (int i = 0; i < 5; i++) {
            s.runRound();
            s.submit(t, 10);
        }
        while (ran.isEmpty()) s.runRound();
        s.submit(t, 10);
        for (int i = 0; i < 15; i++) s.runRound();
        assertEquals(List.of(r + 11, r + 22), ran);
    }

    @Test
    void cadenceNormalizesAndCaches() {
        assertSame(Cadence.every(20), Cadence.every(20));
        assertSame(Cadence.EVERY_TICK, Cadence.every(0));
        assertTrue(Cadence.every(20).isAutoPhase());
        assertEquals(3, Cadence.every(5).phase(13).phase());
        assertEquals(4, Cadence.every(5).phase(-1).phase());
        assertFalse(Cadence.every(5).phase(1).isAutoPhase());
        assertEquals(Cadence.every(5).phase(2), Cadence.every(5).phase(7));
        assertEquals(100000, Cadence.every(100000).period());
    }

    @Test
    void cancelledCoalescedTaskCanBeQueuedAgain() {
        TickScheduler s = started();
        AtomicInteger runs = new AtomicInteger();
        CoalescedTask t = new CoalescedTask(runs::incrementAndGet);
        s.submit(t, 5);
        t.cancel();
        assertFalse(t.isQueued());
        s.submit(t, 1);
        for (int i = 0; i < 10; i++) s.runRound();
        assertEquals(1, runs.get());
        assertEquals(0, s.linkedCount());
    }

    @Test
    void coalescedTaskMovesOnFromAClosedScheduler() {
        TickScheduler a = started();
        AtomicInteger runs = new AtomicInteger();
        CoalescedTask t = new CoalescedTask(runs::incrementAndGet);
        a.submit(t, 3);
        a.close();
        assertFalse(t.isQueued());
        TickScheduler b = started();
        b.submit(t);
        b.runRound();
        assertEquals(1, runs.get());
    }

    @Test
    void closedSchedulerRejectsAndDeactivatesEverything() {
        TickScheduler s = started();
        AtomicInteger runs = new AtomicInteger();
        Task pending = s.repeat(Cadence.EVERY_TICK, runs::incrementAndGet);
        s.close();
        assertTrue(s.isClosed());
        assertFalse(pending.isActive());
        Task late = s.schedule(runs::incrementAndGet);
        assertFalse(late.isActive());
        s.runRound();
        assertEquals(0, runs.get());
        assertFalse(TickScheduler.CLOSED.schedule(() -> {}).isActive());
    }

    @Test
    void closingFromInsideARoundStopsTheRound() {
        TickScheduler s = started();
        AtomicInteger runs = new AtomicInteger();
        s.schedule(s::close);
        s.schedule(runs::incrementAndGet);
        s.runRound();
        assertEquals(0, runs.get());
        assertTrue(s.isClosed());
    }

    @Test
    void failuresGoToTheHandlerAndOtherTasksContinue() {
        List<Throwable> errors = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        TickScheduler s = new TickScheduler(true, (error, owner) -> {
            errors.add(error);
            owners.add(owner);
        });
        s.runRound();
        AtomicInteger runs = new AtomicInteger();
        Object owner = new Object();
        s.repeat(Cadence.EVERY_TICK, owner, () -> {
            throw new IllegalStateException("boom");
        });
        s.repeat(Cadence.EVERY_TICK, runs::incrementAndGet);
        s.runRound();
        s.runRound();
        assertEquals(2, errors.size());
        assertSame(owner, owners.get(0));
        assertEquals(2, runs.get());
    }

    @Test
    void rethrowingHandlerPropagatesAndTheSchedulerStaysUsable() {
        TickScheduler s = started();
        s.schedule(() -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(AssertionError.class, s::runRound);
        AtomicInteger runs = new AtomicInteger();
        s.schedule(runs::incrementAndGet);
        s.runRound();
        assertEquals(1, runs.get());
    }

    @Test
    void advanceIfIdleOnlySkipsRoundsWithNothingDue() {
        TickScheduler s = new TickScheduler(false, RETHROW);
        assertTrue(s.advanceIfIdle());
        assertEquals(1, s.round());
        s.schedule(() -> {});
        assertFalse(s.advanceIfIdle());
        s.runRound();
        assertEquals(2, s.round());
        s.repeat(Cadence.every(5).phase(0), () -> {});
        s.runRound();
        int skipped = 0;
        for (int i = 0; i < 20; i++) {
            if (s.advanceIfIdle()) skipped++;
            else s.runRound();
        }
        assertEquals(16, skipped);
    }

    @Test
    void nonStickySchedulerOnlyLinksDirectlyInsideItsRound() throws Exception {
        TickScheduler s = new TickScheduler(false, RETHROW);
        List<Long> ran = Collections.synchronizedList(new ArrayList<>());
        s.runRound();
        Thread other = new Thread(() -> s.schedule(() -> ran.add(s.round())));
        other.start();
        other.join();
        s.schedule(() -> ran.add(s.round()));
        s.runRound();
        assertEquals(List.of(2L, 2L), ran);
    }

    @Test
    void concurrentSubmissionsRunExactlyOnce() throws Exception {
        TickScheduler s = started();
        int threads = 6;
        int perThread = 5000;
        AtomicIntegerArray runs = new AtomicIntegerArray(threads * perThread);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            Thread w = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                Random rnd = new Random(base);
                for (int i = 0; i < perThread; i++) {
                    int id = base + i;
                    s.schedule(rnd.nextInt(4), () -> runs.incrementAndGet(id));
                }
            });
            workers.add(w);
            w.start();
        }
        go.countDown();
        while (workers.stream().anyMatch(Thread::isAlive)) s.runRound();
        for (Thread w : workers) w.join();
        for (int i = 0; i < 10; i++) s.runRound();
        for (int i = 0; i < runs.length(); i++) assertEquals(1, runs.get(i), "task " + i);
        assertEquals(0, s.linkedCount());
    }

    @Test
    void randomOperationsMatchTheReferenceModel() {
        for (long seed = 1; seed <= 12; seed++) runDifferential(seed);
    }

    private static void runDifferential(long seed) {
        Random rnd = new Random(seed);
        TickScheduler s = started();
        Model model = new Model(s.round());
        Map<Integer, List<Long>> actual = new HashMap<>();
        Map<Integer, Task> handles = new HashMap<>();
        int[] periods = {1, 2, 3, 5, 20, 64, 100, 257, TickScheduler.MAX_GROUP_PERIOD, TickScheduler.MAX_GROUP_PERIOD + 1, 6000};
        int nextId = 0;
        for (int step = 0; step < 4000; step++) {
            int ops = rnd.nextInt(4);
            for (int o = 0; o < ops; o++) {
                int kind = rnd.nextInt(10);
                if (kind < 4) {
                    int id = nextId++;
                    int delay = rnd.nextInt(10) == 0 ? rnd.nextInt(3000) : rnd.nextInt(40);
                    handles.put(id, s.schedule(delay, () -> actual.computeIfAbsent(id, k -> new ArrayList<>()).add(s.round())));
                    model.once(id, delay);
                } else if (kind < 7) {
                    int id = nextId++;
                    int p = periods[rnd.nextInt(periods.length)];
                    int phase = rnd.nextInt(p);
                    handles.put(id, s.repeat(Cadence.every(p).phase(phase), () -> actual.computeIfAbsent(id, k -> new ArrayList<>()).add(s.round())));
                    model.repeat(id, p, phase);
                } else if (kind < 9 && !handles.isEmpty()) {
                    int id = rnd.nextInt(nextId);
                    Task t = handles.get(id);
                    if (t != null && t.period() != 0) {
                        int p = periods[rnd.nextInt(periods.length)];
                        t.setPeriod(p);
                        model.setPeriod(id, p);
                    }
                } else if (!handles.isEmpty()) {
                    int id = rnd.nextInt(nextId);
                    Task t = handles.get(id);
                    if (t != null) {
                        t.cancel();
                        model.cancel(id);
                    }
                }
            }
            s.runRound();
            model.advance();
        }
        Map<Integer, List<Long>> expected = model.runs;
        for (int id = 0; id < nextId; id++) {
            assertEquals(expected.getOrDefault(id, List.of()), actual.getOrDefault(id, List.of()), "seed " + seed + " task " + id);
        }
    }

    private static final class Model {

        private static final class Item {

            final boolean repeat;
            long nextDue;
            int period;
            int pending;
            boolean cancelled;

            Item(boolean repeat) {
                this.repeat = repeat;
            }
        }

        final Map<Integer, List<Long>> runs = new HashMap<>();
        private final TreeMap<Integer, Item> items = new TreeMap<>();
        private long round;

        Model(long round) {
            this.round = round;
        }

        void once(int id, int delay) {
            Item it = new Item(false);
            it.nextDue = round + 1 + delay;
            items.put(id, it);
        }

        void repeat(int id, int period, int phase) {
            Item it = new Item(true);
            it.period = period;
            long base = round + 1;
            it.nextDue = base + Math.floorMod(phase - base, (long) period);
            items.put(id, it);
        }

        void setPeriod(int id, int period) {
            Item it = items.get(id);
            if (it == null || !it.repeat || it.cancelled) return;
            it.pending = period == it.period ? 0 : period;
        }

        void cancel(int id) {
            Item it = items.get(id);
            if (it != null) it.cancelled = true;
        }

        void advance() {
            long r = ++round;
            for (Map.Entry<Integer, Item> e : items.entrySet()) {
                Item it = e.getValue();
                if (it.cancelled || it.nextDue != r) continue;
                runs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r);
                if (!it.repeat) {
                    it.cancelled = true;
                    continue;
                }
                if (it.pending != 0) {
                    it.period = it.pending;
                    it.pending = 0;
                }
                it.nextDue = r + it.period;
            }
        }
    }
}
