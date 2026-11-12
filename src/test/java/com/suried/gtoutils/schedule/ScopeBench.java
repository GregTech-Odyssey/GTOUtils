package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "GTOUTILS_BENCH", matches = "1")
class ScopeBench {

    private static final int N = 10_000;
    private static final int WARMUP = 12;
    private static final int ROUNDS = 15;
    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };
    private static final Object[] LABELS = new Object[N];
    private static final int[] ORDER = new int[N];

    static {
        for (int i = 0; i < N; i++) LABELS[i] = new Object();
        for (int i = 0; i < N; i++) ORDER[i] = i;
        Random random = new Random(42);
        for (int i = N - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = ORDER[i];
            ORDER[i] = ORDER[j];
            ORDER[j] = t;
        }
    }

    private final StringBuilder out = new StringBuilder();
    private static int sink;

    interface Case {

        default void prepare() {}

        void run();

        default void cleanup() {}
    }

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    private void measure(String name, int ops, Case c) {
        double[] ns = new double[ROUNDS];
        double[] bytes = new double[ROUNDS];
        for (int r = 0; r < WARMUP + ROUNDS; r++) {
            c.prepare();
            long a0 = MX.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            c.run();
            long t1 = System.nanoTime();
            long a1 = MX.getCurrentThreadAllocatedBytes();
            c.cleanup();
            if (r < WARMUP) continue;
            ns[r - WARMUP] = (double) (t1 - t0) / ops;
            bytes[r - WARMUP] = (double) (a1 - a0) / ops;
        }
        Arrays.sort(ns);
        Arrays.sort(bytes);
        out.append(String.format("%-48s %12.1f ns/op %10.1f B/op%n", name, ns[ROUNDS / 2], bytes[ROUNDS / 2]));
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {}
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    interface Opener {

        OwnedScope open(TickScheduler lane, int i);
    }

    private void retained(String name, Opener opener) {
        long[] samples = new long[5];
        for (int r = 0; r < samples.length + 1; r++) {
            TickScheduler lane = lane();
            OwnedScope[] held = new OwnedScope[N];
            long before = used();
            for (int i = 0; i < N; i++) held[i] = opener.open(lane, i);
            long after = used();
            sink += held[N - 1].isActive() ? 1 : 0;
            lane.close();
            if (r > 0) samples[r - 1] = after - before;
        }
        Arrays.sort(samples);
        out.append(String.format("%-48s %12.1f B/live scope%n", name, (double) samples[samples.length / 2] / N));
    }

    static final class Team {}

    static final class Fixture {

        final Signal teams = new Signal();
        final Team team = new Team();
        final Cell<Boolean>[] formed;
        final Cell<UUID>[] owners;
        final Cell<Object>[] controllers;
        int effects;

        @SuppressWarnings("unchecked")
        Fixture() {
            formed = new Cell[N];
            owners = new Cell[N];
            controllers = new Cell[N];
            for (int i = 0; i < N; i++) {
                formed[i] = Cell.of(true);
                owners[i] = Cell.of(new UUID(0, i));
                controllers[i] = Cell.of(LABELS[i]);
            }
        }

        Team teamOf(UUID owner) {
            teams.track();
            return owner == null ? null : team;
        }

        final Runnable inc = () -> effects++;
        final Runnable dec = () -> effects--;
        final java.util.function.Consumer<Object> incOf = c -> effects++;
        final java.util.function.Consumer<Object> decOf = c -> effects--;
        final java.util.function.Consumer<Scope> effectBody = this::effect;
        final Scope.Body<Object> effectBodyOf = (linked, c) -> effect(linked);

        void effect(Scope s) {
            s.effect(inc, dec);
        }

        OwnedScope empty(TickScheduler lane, int i) {
            return OwnedScope.root(LABELS[i], lane);
        }

        OwnedScope withEffect(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            effect(s);
            return s;
        }

        OwnedScope withWhen(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            Cell<Boolean> c = formed[i];
            s.when(c::get, effectBody);
            return s;
        }

        OwnedScope withTwoSelects(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            Cell<UUID> owner = owners[i];
            Cell<Object> controller = controllers[i];
            s.select(() -> teamOf(owner.get()), (inTeam, team) -> inTeam.select(controller::get, effectBodyOf));
            return s;
        }

        final KeyedSignal<UUID> keyedTeams = new KeyedSignal<>();
        final UUID[] players = new UUID[100];

        {
            for (int i = 0; i < players.length; i++) players[i] = new UUID(1, i);
        }

        OwnedScope withDerivedEffect(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            Cell<UUID> owner = owners[i];
            Cell<Object> controller = controllers[i];
            s.effect(() -> teamOf(owner.get()) == null ? null : controller.get(), incOf, decOf);
            return s;
        }

        OwnedScope withEffectWhile(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            Cell<Boolean> c = formed[i];
            s.effectWhile(c::get, inc, dec);
            return s;
        }

        OwnedScope withKeyedEffect(TickScheduler lane, int i) {
            var s = OwnedScope.root(LABELS[i], lane);
            Cell<UUID> owner = owners[i];
            Cell<Object> controller = controllers[i];
            s.effect(() -> {
                UUID player = owner.get();
                keyedTeams.track(players[(int) (player.getLeastSignificantBits() % 100)]);
                return controller.get();
            }, incOf, decOf);
            return s;
        }
    }

    private void openClose(String name, Opener opener) {
        TickScheduler[] lane = new TickScheduler[1];
        OwnedScope[] held = new OwnedScope[N];
        measure(name, N, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
            }

            @Override
            public void run() {
                TickScheduler l = lane[0];
                for (int i = 0; i < N; i++) held[i] = opener.open(l, i);
                for (int i = 0; i < N; i++) held[ORDER[i]].dispose();
            }

            @Override
            public void cleanup() {
                Arrays.fill(held, null);
            }
        });
    }

    @Test
    void run() throws IOException {
        Fixture f = new Fixture();
        out.append("N = ").append(N).append(", median of ").append(ROUNDS).append(" rounds after ").append(WARMUP).append(" warm-up rounds\n");
        openClose("open+close root, empty", f::empty);
        openClose("open+close root, effect", f::withEffect);
        openClose("open+close root, when(cell){effect}", f::withWhen);
        openClose("open+close root, select(team){select(ctrl){effect}}", f::withTwoSelects);
        fanOut();
        cellSet();
        closeLane("lane close with 10k live, effect", f::withEffect);
        closeLane("lane close with 10k live, when(cell){effect}", f::withWhen);
        retained("retained, empty root", f::empty);
        retained("retained, effect", f::withEffect);
        retained("retained, when(cell){effect}", f::withWhen);
        retained("retained, select(team){select(ctrl){effect}}", f::withTwoSelects);
        extra(f);
        String tag = System.getenv().getOrDefault("GTOUTILS_BENCH_TAG", "run");
        Path file = Path.of("build", "scope-bench-" + tag + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    void extra(Fixture f) {
        out.append("-- new API vs old pattern --\n");
        openClose("open+close root, effect(derive,apply,revert) 2-level", f::withDerivedEffect);
        retained("retained, effect(derive,apply,revert) 2-level", f::withDerivedEffect);
        openClose("open+close root, keyed 2-level effect", f::withKeyedEffect);
        ScopeSlot[] slots = new ScopeSlot[N];
        java.util.function.Consumer<Scope> body = f::effect;
        for (int i = 0; i < N; i++) slots[i] = new ScopeSlot();
        TickScheduler[] lane = new TickScheduler[1];
        measure("ScopeSlot open+close, effect", N, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
            }

            @Override
            public void run() {
                TickScheduler l = lane[0];
                for (int i = 0; i < N; i++) slots[i].open(LABELS[i], l, body);
                for (int i = 0; i < N; i++) slots[ORDER[i]].close();
            }
        });
        teamChange(f);
        posts();
        micro();
        out.append("-- effectWhile --\n");
        openClose("open+close root, effectWhile(cell)", f::withEffectWhile);
        retained("retained, effectWhile(cell)", f::withEffectWhile);
    }

    private static final int MICRO = 1_000_000;
    private static final java.util.function.Consumer<Object> COUNT = o -> sink++;

    @SuppressWarnings("unchecked")
    private void micro() {
        out.append("-- micro --\n");
        Cell<Integer>[] cells = new Cell[1024];
        for (int i = 0; i < cells.length; i++) cells[i] = Cell.of(i);
        measure("Cell.get outside derivation", MICRO, () -> {
            int s = 0;
            for (int i = 0; i < MICRO; i++) s += cells[i & 1023].get();
            sink += s;
        });
        measure("Cell.peek", MICRO, () -> {
            int s = 0;
            for (int i = 0; i < MICRO; i++) s += cells[i & 1023].peek();
            sink += s;
        });
        Signal[] signals = new Signal[1024];
        for (int i = 0; i < signals.length; i++) signals[i] = new Signal();
        measure("Signal.emit, no subscriber", MICRO, () -> {
            for (int i = 0; i < MICRO; i++) signals[i & 1023].emit();
        });
        WaitList<Object> list = new WaitList<>();
        WaitList.Waiter<Object>[] waiters = new WaitList.Waiter[1024];
        for (int i = 0; i < waiters.length; i++) waiters[i] = new WaitList.Waiter<>(LABELS[i]);
        measure("WaitList await+cancel", MICRO, () -> {
            for (int i = 0; i < MICRO; i++) {
                var w = waiters[i & 1023];
                list.await(w);
                w.cancel();
            }
        });
        WaitList<Object> empty = new WaitList<>();
        measure("WaitList fire, empty", MICRO, () -> {
            for (int i = 0; i < MICRO; i++) empty.fire(COUNT);
        });
        measure("WaitList await+fire, 1 waiter", MICRO, () -> {
            for (int i = 0; i < MICRO; i++) {
                list.await(waiters[i & 1023]);
                list.fire(COUNT);
            }
        });
    }

    private void teamChange(Fixture f) {
        int teams = 1000;
        TickScheduler[] lane = new TickScheduler[1];
        Signal global = new Signal();
        KeyedSignal<Integer> keyed = new KeyedSignal<>();
        int[] version = { 0 };
        int emits = 50;
        measure("team change, global Signal, 10k machines (per emit)", emits, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                for (int i = 0; i < N; i++) {
                    OwnedScope.root(LABELS[i], lane[0]).watch(() -> {
                        global.track();
                        return (version[0] & 1) == 0;
                    }, v -> sink++);
                }
            }

            @Override
            public void run() {
                for (int k = 0; k < emits; k++) {
                    version[0]++;
                    global.emit();
                    lane[0].runRound();
                }
            }

            @Override
            public void cleanup() {
                lane[0].close();
            }
        });
        measure("team change, KeyedSignal, 10k machines (per emit)", emits, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                for (int i = 0; i < N; i++) {
                    int team = i % teams;
                    OwnedScope.root(LABELS[i], lane[0]).watch(() -> {
                        keyed.track(team);
                        return (version[0] & 1) == 0;
                    }, v -> sink++);
                }
            }

            @Override
            public void run() {
                for (int k = 0; k < emits; k++) {
                    version[0]++;
                    keyed.emit(k % teams);
                    lane[0].runRound();
                }
            }

            @Override
            public void cleanup() {
                lane[0].close();
            }
        });
    }

    private void posts() {
        TickScheduler lane = lane();
        Cell<Integer> cell = Cell.of(0);
        OwnedScope.root("w", lane).watch(cell::get, v -> sink++);
        Integer[] values = new Integer[N];
        for (int i = 0; i < N; i++) values[i] = i + 1000;
        measure("Cell.post on lane thread, 1 watch (no round)", N, () -> {
            for (int i = 0; i < N; i++) cell.post(values[i]);
        });
        lane.runRound();
        measure("Cell.set on lane thread, 1 watch (no round)", N, () -> {
            for (int i = 0; i < N; i++) cell.set(values[N - 1 - i]);
        });
        lane.runRound();
        foreign("Cell.set from other thread (relay+misuse)", lane, () -> {
            for (int i = 0; i < N; i++) cell.set(values[i]);
        });
        foreign("Cell.post from other thread", lane, () -> {
            for (int i = 0; i < N; i++) cell.post(values[i]);
        });
        lane.close();
    }

    private void foreign(String name, TickScheduler lane, Runnable writes) {
        double[] ns = new double[ROUNDS];
        double[] bytes = new double[ROUNDS];
        double[] drain = new double[ROUNDS];
        for (int r = 0; r < WARMUP + ROUNDS; r++) {
            long[] result = new long[2];
            Thread t = new Thread(() -> {
                long a0 = MX.getCurrentThreadAllocatedBytes();
                long t0 = System.nanoTime();
                writes.run();
                long t1 = System.nanoTime();
                result[0] = t1 - t0;
                result[1] = MX.getCurrentThreadAllocatedBytes() - a0;
            });
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            long d0 = System.nanoTime();
            lane.runRound();
            long d1 = System.nanoTime();
            if (r < WARMUP) continue;
            ns[r - WARMUP] = (double) result[0] / N;
            bytes[r - WARMUP] = (double) result[1] / N;
            drain[r - WARMUP] = (d1 - d0) / 1000.0;
        }
        Arrays.sort(ns);
        Arrays.sort(bytes);
        Arrays.sort(drain);
        out.append(String.format("%-48s %12.1f ns/op %10.1f B/op  lane drain %8.1f us per 10k%n", name, ns[ROUNDS / 2], bytes[ROUNDS / 2], drain[ROUNDS / 2]));
    }

    private void fanOut() {
        fanOut("emit shared signal, 10k watch, first emit", 1, false);
        fanOut("emit shared signal, 10k watch, steady (per emit)", 5, true);
    }

    private void fanOut(String name, int emits, boolean primed) {
        TickScheduler[] lane = new TickScheduler[1];
        Signal shared = new Signal();
        int[] version = { 0 };
        measure(name, emits, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                for (int i = 0; i < N; i++) {
                    OwnedScope.root(LABELS[i], lane[0]).watch(() -> {
                        shared.track();
                        return (version[0] & 1) == 0;
                    }, v -> sink++);
                }
                if (primed) {
                    version[0]++;
                    shared.emit();
                    lane[0].runRound();
                }
            }

            @Override
            public void run() {
                for (int k = 0; k < emits; k++) {
                    version[0]++;
                    shared.emit();
                    lane[0].runRound();
                }
            }

            @Override
            public void cleanup() {
                lane[0].close();
            }
        });
    }

    private void cellSet() {
        TickScheduler[] lane = new TickScheduler[1];
        Cell<Integer> cell = Cell.of(0);
        Integer one = 1;
        Integer two = 2;
        measure("Cell.set unchanged, 1 derivation", N, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                cell.set(one);
                OwnedScope.root("w", lane[0]).watch(cell::get, v -> sink++);
            }

            @Override
            public void run() {
                for (int i = 0; i < N; i++) cell.set(one);
            }

            @Override
            public void cleanup() {
                lane[0].close();
            }
        });
        Cell<Integer> bare = Cell.of(0);
        measure("Cell.set changed, no subscriber", N, () -> {
            for (int i = 0; i < N; i++) bare.set((i & 1) == 0 ? one : two);
        });
        measure("Cell.set changed + round, 1 watch", N, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                OwnedScope.root("w", lane[0]).watch(cell::get, v -> sink++);
            }

            @Override
            public void run() {
                TickScheduler l = lane[0];
                for (int i = 0; i < N; i++) {
                    cell.set((i & 1) == 0 ? two : one);
                    l.runRound();
                }
            }

            @Override
            public void cleanup() {
                lane[0].close();
            }
        });
    }

    private void closeLane(String name, Opener opener) {
        TickScheduler[] lane = new TickScheduler[1];
        measure(name, N, new Case() {

            @Override
            public void prepare() {
                lane[0] = lane();
                for (int i = 0; i < N; i++) opener.open(lane[0], i);
            }

            @Override
            public void run() {
                lane[0].close();
            }
        });
    }
}
