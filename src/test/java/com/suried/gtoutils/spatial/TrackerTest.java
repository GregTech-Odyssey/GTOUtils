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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TrackerTest {

    private static Tracker<FakeEntity> tracker() {
        return new Tracker<>(FakeEntity.ACCESS);
    }

    private static <T> List<T> all(TypedSink<FakeEntity, T> sink, FakeLevel level) {
        List<T> out = new ArrayList<>();
        BlockIndex<T> index = sink.index(level);
        if (index != null) index.forEach(out::add);
        return out;
    }

    @Test
    void typedSinkCollectsSubtypesAndInterfaces() {
        Tracker<FakeEntity> tracker = tracker();
        TypedSink<FakeEntity, FakeEntity.Jar> jars = tracker.register(new TypedSink<>(FakeEntity.Jar.class, null));
        TypedSink<FakeEntity, FakeEntity.Marker> markers = tracker.register(new TypedSink<>(FakeEntity.Marker.class, null));
        FakeLevel level = new FakeLevel();
        FakeEntity.Jar jar = new FakeEntity.Jar(level, 0, 0, 0);
        FakeEntity.BigJar big = new FakeEntity.BigJar(level, 1, 0, 0);
        FakeEntity.Pipe pipe = new FakeEntity.Pipe(level, 2, 0, 0);
        FakeEntity plain = new FakeEntity(level, 3, 0, 0);
        for (FakeEntity e : List.of(jar, big, pipe, plain)) tracker.added(e);
        assertEquals(Set.of(jar, big), new HashSet<>(all(jars, level)));
        assertEquals(Set.<Object>of(big, pipe), new HashSet<Object>(all(markers, level)));
    }

    @Test
    void admitIsCheckedOnceAtRegistration() {
        Tracker<FakeEntity> tracker = tracker();
        AtomicInteger calls = new AtomicInteger();
        TypedSink<FakeEntity, FakeEntity.Jar> capable = tracker.register(new TypedSink<>(FakeEntity.Jar.class, j -> {
            calls.incrementAndGet();
            return j.capable;
        }));
        FakeLevel level = new FakeLevel();
        FakeEntity.Jar yes = new FakeEntity.Jar(level, 0, 0, 0);
        yes.capable = true;
        FakeEntity.Jar no = new FakeEntity.Jar(level, 1, 0, 0);
        tracker.added(yes);
        tracker.added(no);
        no.capable = true;
        assertEquals(List.of(yes), all(capable, level));
        tracker.removed(no);
        tracker.removed(yes);
        assertTrue(all(capable, level).isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    void removeOnlyDropsThatObject() {
        Tracker<FakeEntity> tracker = tracker();
        TypedSink<FakeEntity, FakeEntity.Jar> jars = tracker.register(new TypedSink<>(FakeEntity.Jar.class, null));
        FakeLevel level = new FakeLevel();
        FakeEntity.Jar old = new FakeEntity.Jar(level, 4, 5, 6);
        FakeEntity.Jar fresh = new FakeEntity.Jar(level, 4, 5, 6);
        tracker.added(old);
        tracker.added(fresh);
        tracker.removed(old);
        assertEquals(List.of(fresh), all(jars, level));
        tracker.removed(fresh);
        assertTrue(all(jars, level).isEmpty());
        tracker.removed(fresh);
        assertTrue(all(jars, level).isEmpty());
    }

    @Test
    void levelsAreIndependentAndUnknownLevelIsIgnored() {
        Tracker<FakeEntity> tracker = tracker();
        TypedSink<FakeEntity, FakeEntity.Jar> jars = tracker.register(new TypedSink<>(FakeEntity.Jar.class, null));
        FakeLevel server = new FakeLevel();
        FakeLevel client = new FakeLevel();
        FakeEntity.Jar a = new FakeEntity.Jar(server, 0, 0, 0);
        FakeEntity.Jar b = new FakeEntity.Jar(client, 0, 0, 0);
        tracker.added(a);
        tracker.added(b);
        assertEquals(List.of(a), all(jars, server));
        assertEquals(List.of(b), all(jars, client));
        tracker.added(new FakeEntity.Jar(null, 9, 9, 9));
        tracker.removed(new FakeEntity.Jar(null, 9, 9, 9));
        assertNull(jars.index(new FakeLevel()));
    }

    @Test
    void registeringAfterTheFirstDispatchFails() {
        Tracker<FakeEntity> tracker = tracker();
        assertFalse(tracker.started());
        tracker.register(new TypedSink<>(FakeEntity.Jar.class, null));
        tracker.removed(new FakeEntity(new FakeLevel(), 0, 0, 0));
        assertTrue(tracker.started());
        assertThrows(IllegalStateException.class, () -> tracker.register(new TypedSink<>(FakeEntity.Pipe.class, null)));
    }

    @Test
    void probeBitsAreDistinctAndLimitedTo32() {
        Tracker<FakeEntity> tracker = tracker();
        int seen = 0;
        for (int i = 0; i < 32; i++) {
            int bit = tracker.allocateProbeBit();
            assertEquals(1, Integer.bitCount(bit));
            assertEquals(0, seen & bit);
            seen |= bit;
        }
        assertEquals(-1, seen);
        assertThrows(IllegalStateException.class, tracker::allocateProbeBit);
        assertEquals(1, tracker().allocateProbeBit());
    }

    @Test
    void uninterestingClassesAreIgnored() {
        Tracker<FakeEntity> tracker = tracker();
        TypedSink<FakeEntity, FakeEntity.Pipe> pipes = tracker.register(new TypedSink<>(FakeEntity.Pipe.class, null));
        FakeLevel level = new FakeLevel();
        tracker.added(new FakeEntity.Jar(level, 0, 0, 0));
        assertNull(pipes.index(level));
        assertNull(level.gtoutils$slots());
    }

    @Test
    void randomChurnMatchesBruteForce() {
        Tracker<FakeEntity> tracker = tracker();
        TypedSink<FakeEntity, FakeEntity.Jar> jars = tracker.register(new TypedSink<>(FakeEntity.Jar.class, j -> j.pos.getY() % 3 != 0));
        FakeLevel level = new FakeLevel();
        Map<BlockPos, FakeEntity> chunk = new HashMap<>();
        Random random = new Random(42);
        for (int step = 0; step < 20000; step++) {
            BlockPos pos = new BlockPos(random.nextInt(40) - 20, random.nextInt(30) - 15, random.nextInt(40) - 20);
            FakeEntity old = chunk.get(pos);
            if (random.nextInt(3) == 0) {
                if (old != null) {
                    chunk.remove(pos);
                    tracker.removed(old);
                }
            } else {
                FakeEntity fresh = random.nextBoolean() ? new FakeEntity.Jar(level, pos.getX(), pos.getY(), pos.getZ())
                        : new FakeEntity.Pipe(level, pos.getX(), pos.getY(), pos.getZ());
                if (old != null) tracker.removed(old);
                chunk.put(pos, fresh);
                tracker.added(fresh);
            }
        }
        BlockIndex<FakeEntity.Jar> index = jars.index(level);
        for (int q = 0; q < 300; q++) {
            int cx = random.nextInt(40) - 20, cy = random.nextInt(30) - 15, cz = random.nextInt(40) - 20;
            BlockRange range = random.nextBoolean() ? BlockRange.sphere(random.nextInt(12)) : BlockRange.box(random.nextInt(8), random.nextInt(8), random.nextInt(8));
            Set<FakeEntity> expected = new HashSet<>();
            for (FakeEntity e : chunk.values()) {
                if (e instanceof FakeEntity.Jar && e.pos.getY() % 3 != 0 &&
                        range.contains(e.pos.getX() - cx, e.pos.getY() - cy, e.pos.getZ() - cz)) {
                    expected.add(e);
                }
            }
            Set<FakeEntity> actual = new HashSet<>();
            index.query(cx, cy, cz, range, null, actual);
            assertEquals(expected, actual);
        }
    }
}
