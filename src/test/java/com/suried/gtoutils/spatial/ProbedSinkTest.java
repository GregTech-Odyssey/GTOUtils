package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ProbedSinkTest {

    private static ProbedBlockEntityIndex.Kind classify(Class<?> type) {
        if (FakeEntity.Jar.class.isAssignableFrom(type)) return ProbedBlockEntityIndex.Kind.CANDIDATE;
        if (FakeEntity.Pipe.class.isAssignableFrom(type)) return ProbedBlockEntityIndex.Kind.PROBE;
        return null;
    }

    private static final class Setup {

        final Tracker<FakeEntity> tracker = new Tracker<>(FakeEntity.ACCESS);
        final TypedSink<FakeEntity, FakeEntity> all = tracker.register(new TypedSink<>(FakeEntity.class, null));
        final ProbedSink<FakeEntity> sink;
        final FakeLevel level = new FakeLevel();

        Setup() {
            this(e -> {
                e.probes++;
                return e.capable;
            }, null);
        }

        Setup(Predicate<FakeEntity> probe, Predicate<FakeEntity> admit) {
            sink = tracker.register(new ProbedSink<>(tracker, all, ProbedSinkTest::classify, probe, admit));
        }

        Set<FakeEntity> around(int x, int y, int z, int r) {
            Set<FakeEntity> out = new HashSet<>();
            sink.query(level, x, y, z, BlockRange.box(r), null, out);
            return out;
        }

        void replace(FakeEntity old, FakeEntity fresh) {
            if (old != null) {
                old.removed = true;
                tracker.removed(old);
            }
            tracker.added(fresh);
        }
    }

    @Test
    void candidatesAreStoredAndProbeClassesTakeNoStorage() {
        Setup s = new Setup();
        s.tracker.added(new FakeEntity.Jar(s.level, 0, 0, 0));
        s.tracker.added(new FakeEntity.Pipe(s.level, 1, 0, 0));
        s.tracker.added(new FakeEntity(s.level, 2, 0, 0));
        assertEquals(1, s.sink.candidateCount(s.level));
        assertEquals(0, s.sink.sweptSectionCount(s.level));
        FakeLevel other = new FakeLevel();
        s.tracker.added(new FakeEntity.Pipe(other, 0, 0, 0));
        assertEquals(0, s.sink.candidateCount(other));
    }

    @Test
    void probeClassesAreProbedOncePerInstance() {
        Setup s = new Setup();
        FakeEntity.Pipe yes = new FakeEntity.Pipe(s.level, 0, 0, 0);
        yes.capable = true;
        FakeEntity.Pipe no = new FakeEntity.Pipe(s.level, 1, 0, 0);
        FakeEntity.Pipe far = new FakeEntity.Pipe(s.level, 100, 0, 0);
        far.capable = true;
        s.tracker.added(yes);
        s.tracker.added(no);
        s.tracker.added(far);
        assertEquals(Set.of(yes), s.around(0, 0, 0, 2));
        assertEquals(Set.of(yes), s.around(0, 0, 0, 2));
        assertEquals(Set.of(yes), s.around(1, 0, 0, 3));
        assertEquals(1, yes.probes);
        assertEquals(1, no.probes);
        assertEquals(0, far.probes);
        no.capable = true;
        assertEquals(Set.of(yes), s.around(0, 0, 0, 2));
        assertTrue(s.sink.sweptSectionCount(s.level) > 0);
    }

    @Test
    void wholeSectionIsSweptButOnlyTheRangeIsReturned() {
        Setup s = new Setup();
        FakeEntity.Pipe inside = new FakeEntity.Pipe(s.level, 1, 1, 1);
        FakeEntity.Pipe sameSection = new FakeEntity.Pipe(s.level, 14, 14, 14);
        inside.capable = sameSection.capable = true;
        s.tracker.added(inside);
        s.tracker.added(sameSection);
        assertEquals(Set.of(inside), s.around(0, 0, 0, 2));
        assertEquals(1, sameSection.probes);
        assertEquals(Set.of(sameSection), s.around(14, 14, 14, 0));
        assertEquals(1, sameSection.probes);
    }

    @Test
    void entityAddedToSweptSectionIsProbedWithoutReprobingOthers() {
        Setup s = new Setup();
        FakeEntity.Pipe first = new FakeEntity.Pipe(s.level, 0, 0, 0);
        first.capable = true;
        s.tracker.added(first);
        s.around(0, 0, 0, 3);
        FakeEntity.Pipe later = new FakeEntity.Pipe(s.level, 2, 0, 0);
        later.capable = true;
        s.tracker.added(later);
        assertEquals(Set.of(first, later), s.around(0, 0, 0, 3));
        assertEquals(1, first.probes);
        assertEquals(1, later.probes);
    }

    @Test
    void sweptSectionsStayValidAndOnlyNewEntitiesWait() {
        Setup s = new Setup();
        FakeEntity.Pipe first = new FakeEntity.Pipe(s.level, 0, 0, 0);
        s.tracker.added(first);
        s.tracker.added(new FakeEntity.Pipe(s.level, 40, 0, 0));
        s.around(0, 0, 0, 3);
        int swept = s.sink.sweptSectionCount(s.level);
        assertEquals(0, s.sink.freshCount(s.level));
        FakeEntity.Pipe near = new FakeEntity.Pipe(s.level, 1, 1, 1);
        FakeEntity.Pipe edge = new FakeEntity.Pipe(s.level, 12, 12, 12);
        FakeEntity.Pipe unswept = new FakeEntity.Pipe(s.level, 41, 0, 0);
        near.capable = edge.capable = unswept.capable = true;
        s.tracker.added(near);
        s.tracker.added(edge);
        s.tracker.added(unswept);
        assertEquals(2, s.sink.freshCount(s.level));
        first.removed = true;
        s.tracker.removed(first);
        assertEquals(swept, s.sink.sweptSectionCount(s.level));
        assertEquals(Set.of(near), s.around(0, 0, 0, 3));
        assertEquals(1, s.sink.freshCount(s.level));
        assertEquals(0, edge.probes);
        assertEquals(1, first.probes);
        edge.removed = true;
        s.tracker.removed(edge);
        assertEquals(0, s.sink.freshCount(s.level));
        assertEquals(0, edge.probes);
    }

    @Test
    void hugeRangeAlsoProbesNewEntities() {
        Setup s = new Setup();
        s.tracker.added(new FakeEntity.Pipe(s.level, 0, 0, 0));
        s.around(0, 0, 0, 1);
        FakeEntity.Pipe later = new FakeEntity.Pipe(s.level, 2, 2, 2);
        later.capable = true;
        s.tracker.added(later);
        assertEquals(1, s.sink.freshCount(s.level));
        assertEquals(Set.of(later), s.around(0, 0, 0, 4000));
        assertEquals(0, s.sink.freshCount(s.level));
        assertEquals(Set.of(later), s.around(0, 0, 0, 3));
        assertEquals(1, later.probes);
    }

    @Test
    void removedEntityLeavesAndIsProbedAgainIfReadded() {
        Setup s = new Setup();
        FakeEntity.Pipe pipe = new FakeEntity.Pipe(s.level, 5, 5, 5);
        pipe.capable = true;
        s.tracker.added(pipe);
        assertEquals(Set.of(pipe), s.around(5, 5, 5, 1));
        s.tracker.removed(pipe);
        assertTrue(s.around(5, 5, 5, 1).isEmpty());
        s.tracker.added(pipe);
        assertEquals(Set.of(pipe), s.around(5, 5, 5, 1));
        assertEquals(2, pipe.probes);
    }

    @Test
    void staleEntitiesAreNotPromoted() {
        Setup s = new Setup();
        FakeEntity.Pipe stale = new FakeEntity.Pipe(s.level, 0, 0, 0);
        stale.capable = true;
        s.tracker.added(stale);
        stale.removed = true;
        assertTrue(s.around(0, 0, 0, 1).isEmpty());
        assertEquals(0, stale.probes);
    }

    @Test
    void replacingAcrossKindsKeepsOnlyTheCurrentEntity() {
        Setup s = new Setup();
        FakeEntity.Pipe pipe = new FakeEntity.Pipe(s.level, 0, 0, 0);
        pipe.capable = true;
        s.tracker.added(pipe);
        assertEquals(Set.of(pipe), s.around(0, 0, 0, 1));
        FakeEntity.Jar jar = new FakeEntity.Jar(s.level, 0, 0, 0);
        s.replace(pipe, jar);
        assertEquals(Set.of(jar), s.around(0, 0, 0, 1));
        FakeEntity.Pipe back = new FakeEntity.Pipe(s.level, 0, 0, 0);
        s.replace(jar, back);
        assertTrue(s.around(0, 0, 0, 1).isEmpty());
        assertEquals(1, back.probes);
    }

    @Test
    void separateIndexesUseSeparateMarks() {
        Tracker<FakeEntity> tracker = new Tracker<>(FakeEntity.ACCESS);
        TypedSink<FakeEntity, FakeEntity> all = tracker.register(new TypedSink<>(FakeEntity.class, null));
        int[] calls = new int[2];
        ProbedSink<FakeEntity> even = tracker.register(new ProbedSink<>(tracker, all, ProbedSinkTest::classify, e -> {
            calls[0]++;
            return e.pos.getX() % 2 == 0;
        }, null));
        ProbedSink<FakeEntity> odd = tracker.register(new ProbedSink<>(tracker, all, ProbedSinkTest::classify, e -> {
            calls[1]++;
            return e.pos.getX() % 2 != 0;
        }, null));
        FakeLevel level = new FakeLevel();
        for (int x = 0; x < 6; x++) tracker.added(new FakeEntity.Pipe(level, x, 0, 0));
        Set<FakeEntity> a = new HashSet<>(), b = new HashSet<>();
        even.query(level, 0, 0, 0, BlockRange.box(8), null, a);
        odd.query(level, 0, 0, 0, BlockRange.box(8), null, b);
        even.query(level, 0, 0, 0, BlockRange.box(8), null, a);
        assertEquals(3, a.size());
        assertEquals(3, b.size());
        assertArrayEquals(new int[] { 6, 6 }, calls);
    }

    @Test
    void hugeRangeChecksEntitiesWithoutRecordingSections() {
        Setup s = new Setup();
        FakeEntity.Pipe near = new FakeEntity.Pipe(s.level, 0, 0, 0);
        FakeEntity.Pipe far = new FakeEntity.Pipe(s.level, 3000, 10, -3000);
        near.capable = far.capable = true;
        s.tracker.added(near);
        s.tracker.added(far);
        assertEquals(Set.of(near, far), s.around(0, 0, 0, 4000));
        assertEquals(0, s.sink.sweptSectionCount(s.level));
        assertEquals(Set.of(near, far), s.around(0, 0, 0, 4000));
        assertEquals(1, near.probes);
        assertEquals(1, far.probes);
    }

    @Test
    void findAnyAndForEachProbeFirst() {
        Setup s = new Setup();
        FakeEntity.Pipe pipe = new FakeEntity.Pipe(s.level, 3, 3, 3);
        pipe.capable = true;
        s.tracker.added(pipe);
        assertSame(pipe, s.sink.findAny(s.level, 0, 0, 0, BlockRange.box(3), null));
        assertNull(s.sink.findAny(s.level, 0, 0, 0, BlockRange.box(3), e -> false));
        List<FakeEntity> seen = new ArrayList<>();
        s.sink.forEach(s.level, 0, 0, 0, BlockRange.box(3), null, seen::add);
        assertEquals(List.of(pipe), seen);
        assertEquals(1, pipe.probes);
        assertNull(s.sink.findAny(new FakeLevel(), 0, 0, 0, BlockRange.box(3), null));
    }

    @Test
    void nestedQueryFromProbeIsSafe() {
        Tracker<FakeEntity> tracker = new Tracker<>(FakeEntity.ACCESS);
        TypedSink<FakeEntity, FakeEntity> all = tracker.register(new TypedSink<>(FakeEntity.class, null));
        FakeLevel level = new FakeLevel();
        @SuppressWarnings("unchecked")
        ProbedSink<FakeEntity>[] self = new ProbedSink[1];
        self[0] = tracker.register(new ProbedSink<>(tracker, all, ProbedSinkTest::classify, e -> {
            Set<FakeEntity> inner = new HashSet<>();
            self[0].query(level, 0, 0, 0, BlockRange.box(40), null, inner);
            e.probes++;
            return e.capable;
        }, null));
        for (int i = 0; i < 40; i++) {
            FakeEntity.Pipe p = new FakeEntity.Pipe(level, i, 0, 0);
            p.capable = i % 2 == 0;
            tracker.added(p);
        }
        Set<FakeEntity> out = new HashSet<>();
        self[0].query(level, 0, 0, 0, BlockRange.box(40), null, out);
        assertEquals(20, out.size());
        for (FakeEntity e : out) assertEquals(1, e.probes);
    }

    @Test
    void admitFiltersCandidatesAndProbes() {
        Setup s = new Setup(e -> true, e -> e.pos.getY() >= 0);
        s.tracker.added(new FakeEntity.Jar(s.level, 0, -1, 0));
        s.tracker.added(new FakeEntity.Pipe(s.level, 1, -2, 0));
        FakeEntity.Jar jar = new FakeEntity.Jar(s.level, 0, 1, 0);
        FakeEntity.Pipe pipe = new FakeEntity.Pipe(s.level, 1, 2, 0);
        s.tracker.added(jar);
        s.tracker.added(pipe);
        assertEquals(Set.of(jar, pipe), s.around(0, 0, 0, 3));
    }

    @Test
    void randomChurnMatchesBruteForce() {
        Setup s = new Setup();
        Map<BlockPos, FakeEntity> chunk = new HashMap<>();
        Random random = new Random(7);
        for (int step = 0; step < 40000; step++) {
            BlockPos pos = new BlockPos(random.nextInt(60) - 30, random.nextInt(40) - 20, random.nextInt(60) - 30);
            FakeEntity old = chunk.get(pos);
            int op = random.nextInt(10);
            if (op < 3) {
                if (old != null) {
                    chunk.remove(pos);
                    old.removed = true;
                    s.tracker.removed(old);
                }
            } else if (op < 9) {
                FakeEntity fresh = switch (random.nextInt(3)) {
                    case 0 -> new FakeEntity.Jar(s.level, pos.getX(), pos.getY(), pos.getZ());
                    case 1 -> new FakeEntity.Pipe(s.level, pos.getX(), pos.getY(), pos.getZ());
                    default -> new FakeEntity(s.level, pos.getX(), pos.getY(), pos.getZ());
                };
                fresh.capable = random.nextBoolean();
                chunk.put(pos, fresh);
                s.replace(old, fresh);
            } else {
                int cx = random.nextInt(60) - 30, cy = random.nextInt(40) - 20, cz = random.nextInt(60) - 30, r = random.nextInt(9);
                Set<FakeEntity> expected = new HashSet<>();
                for (FakeEntity e : chunk.values()) {
                    boolean member = e instanceof FakeEntity.Jar || (e instanceof FakeEntity.Pipe && e.capable);
                    if (member && Math.abs(e.pos.getX() - cx) <= r && Math.abs(e.pos.getY() - cy) <= r && Math.abs(e.pos.getZ() - cz) <= r) {
                        expected.add(e);
                    }
                }
                assertEquals(expected, s.around(cx, cy, cz, r));
            }
        }
        for (FakeEntity e : chunk.values()) assertTrue(e.probes <= 1, e::toString);
        long pipes = chunk.values().stream().filter(e -> e instanceof FakeEntity.Pipe).count();
        assertTrue(s.sink.freshCount(s.level) <= pipes);
        Set<FakeEntity> expected = new HashSet<>();
        for (FakeEntity e : chunk.values()) if (e instanceof FakeEntity.Jar || (e instanceof FakeEntity.Pipe && e.capable)) expected.add(e);
        assertEquals(expected, s.around(0, 0, 0, 40));
        assertEquals(0, s.sink.freshCount(s.level));
        for (FakeEntity e : chunk.values()) assertTrue(e.probes <= 1, e::toString);
    }
}
