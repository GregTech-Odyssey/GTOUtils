package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class BlockIndexStressTest {

    private record Entry(int x, int y, int z, int id) {}

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 25)) << 38) | ((long) (z + (1 << 25)) << 12) | (y + 2048);
    }

    @Test
    void worldScaleRandomAgainstBruteForce() {
        SplittableRandom rnd = new SplittableRandom(0x5EED5EEDL);
        BlockIndex<Entry> index = new BlockIndex<>();
        Map<Long, Entry> ref = new HashMap<>();
        int[][] hubs = new int[24][];
        for (int i = 0; i < hubs.length; i++) {
            hubs[i] = new int[] { rnd.nextInt(-29_000_000, 29_000_000), rnd.nextInt(-64, 320), rnd.nextInt(-29_000_000, 29_000_000) };
        }
        hubs[0] = new int[] { 0, 64, 0 };
        hubs[1] = new int[] { -1, -1, -1 };
        int id = 0;
        for (int step = 0; step < 60_000; step++) {
            int[] h = hubs[rnd.nextInt(hubs.length)];
            int spread = rnd.nextInt(4) == 0 ? 200 : 24;
            int x = h[0] + rnd.nextInt(-spread, spread + 1);
            int y = Math.max(-64, Math.min(319, h[1] + rnd.nextInt(-spread, spread + 1)));
            int z = h[2] + rnd.nextInt(-spread, spread + 1);
            long k = key(x, y, z);
            if (rnd.nextInt(5) == 0) {
                Entry removed = ref.remove(k);
                assertEquals(removed, index.remove(x, y, z));
            } else {
                Entry e = new Entry(x, y, z, id++);
                ref.put(k, e);
                assertTrue(index.put(x, y, z, e));
            }
        }
        assertEquals(ref.size(), index.size());
        assertTrue(ref.size() > 30_000, "size=" + ref.size());

        List<Entry> all = new ArrayList<>(ref.values());
        for (int q = 0; q < 1500; q++) {
            int[] h = hubs[rnd.nextInt(hubs.length)];
            int cx = h[0] + rnd.nextInt(-40, 41), cy = h[1] + rnd.nextInt(-40, 41), cz = h[2] + rnd.nextInt(-40, 41);
            BlockRange range = switch (q % 4) {
                case 0 -> BlockRange.sphere(rnd.nextInt(0, 33));
                case 1 -> BlockRange.box(rnd.nextInt(0, 17));
                case 2 -> BlockRange.box(rnd.nextInt(0, 17), rnd.nextInt(0, 9), rnd.nextInt(0, 17));
                default -> BlockRange.sphereSq(rnd.nextInt(0, 600));
            };
            Set<Integer> expected = new HashSet<>();
            Entry nearest = null;
            long nearestD2 = Long.MAX_VALUE;
            for (Entry e : all) {
                int dx = e.x - cx, dy = e.y - cy, dz = e.z - cz;
                if (!range.contains(dx, dy, dz)) continue;
                if ((e.id & 1) == 0) expected.add(e.id);
                long d2 = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                if (d2 < nearestD2 || (d2 == nearestD2 && (e.x < nearest.x || (e.x == nearest.x && (e.y < nearest.y || (e.y == nearest.y && e.z < nearest.z)))))) {
                    nearest = e;
                    nearestD2 = d2;
                }
            }
            List<Entry> got = new ArrayList<>();
            index.query(cx, cy, cz, range, e -> (e.id & 1) == 0, got);
            Set<Integer> gotIds = new HashSet<>();
            for (Entry e : got) gotIds.add(e.id);
            assertEquals(got.size(), gotIds.size());
            assertEquals(expected, gotIds, range + " at " + cx + "," + cy + "," + cz);
            assertSame(nearest, index.findNearest(cx, cy, cz, range, null), range + " nearest at " + cx + "," + cy + "," + cz);
            Entry any = index.findAny(cx, cy, cz, range, e -> (e.id & 1) == 0);
            if (expected.isEmpty()) assertNull(any);
            else assertTrue(expected.contains(any.id));
        }
    }

    @Test
    void denseClusterAndChurnKeepsTreeConsistent() {
        BlockIndex<Integer> index = new BlockIndex<>();
        SplittableRandom rnd = new SplittableRandom(42);
        Map<Long, Integer> ref = new HashMap<>();
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 4000; i++) {
                int x = 1000 + rnd.nextInt(32), y = 60 + rnd.nextInt(16), z = -1000 + rnd.nextInt(32);
                long k = key(x, y, z);
                if (rnd.nextBoolean()) {
                    ref.put(k, i);
                    index.put(x, y, z, i);
                } else {
                    assertEquals(ref.remove(k), index.remove(x, y, z));
                }
            }
            assertEquals(ref.size(), index.size());
            List<Integer> out = new ArrayList<>();
            index.query(1016, 68, -984, BlockRange.box(16, 8, 16), null, out);
            assertEquals(ref.size(), out.size());
        }
        Set<Integer> seen = new HashSet<>();
        index.forEach(v -> seen.add(v));
        assertEquals(new HashSet<>(ref.values()), seen);
    }
}
