package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BlockOctreeTest {

    @Test
    void entriesSurviveLeafSplits() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        int n = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    assertTrue(t.insert(x, y, z, n++));
                }
            }
        }
        n = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    assertEquals(n++, t.get(x, y, z));
                }
            }
        }
        assertEquals(64, t.size());
    }

    @Test
    void everyYLayerIsQueryable() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        for (int y = 0; y < 16; y++) t.insert(0, y, 0, y);
        for (int y = 0; y < 16; y++) {
            assertEquals(1, t.query(0, y, 0, BlockRange.sphere(0), null, new ArrayList<>()));
            assertEquals(1, t.query(0, y, 0, BlockRange.box(0), null, new ArrayList<>()));
        }
    }

    @Test
    void flatAndTallRegionsWork() {
        BlockOctree<Integer> flat = new BlockOctree<>(0, 15, 0, 0);
        for (int i = 0; i < 8; i++) flat.insert(i, 0, i, i);
        for (int i = 0; i < 8; i++) assertEquals(1, flat.query(i, 0, i, BlockRange.sphere(0), null, new ArrayList<>()));

        BlockOctree<Integer> tall = new BlockOctree<>(0, 1 << 20, 0, 8192);
        int[][] pts = { { 0, 0, 0 }, { 1000, 4000, 1000 }, { 5, 8192, 5 }, { 7, 1, 9 } };
        for (int i = 0; i < pts.length; i++) assertTrue(tall.insert(pts[i][0], pts[i][1], pts[i][2], i));
        for (int i = 0; i < pts.length; i++) {
            assertEquals(i, tall.findAny(pts[i][0], pts[i][1], pts[i][2], BlockRange.box(0), null));
        }
    }

    @Test
    void negativeCoordinatesKeepEveryEntry() {
        BlockOctree<Integer> t = new BlockOctree<>(-8, 7, -8, 7);
        for (int x = -8; x <= 7; x += 3) {
            for (int z = -8; z <= 7; z += 3) t.insert(x, 0, z, x * 100 + z);
        }
        for (int x = -8; x <= 7; x += 3) {
            for (int z = -8; z <= 7; z += 3) assertEquals(x * 100 + z, t.get(x, 0, z));
        }
        assertEquals(36, t.size());
    }

    @Test
    void worldSizedTreeAcceptsExtremeCoordinates() {
        BlockOctree<String> t = new BlockOctree<>(BlockIndex.MIN_XZ, BlockIndex.MAX_XZ, BlockIndex.MIN_Y, BlockIndex.MAX_Y);
        assertTrue(t.insert(-30_000_000, -64, 30_000_000, "a"));
        assertTrue(t.insert(30_000_000, 319, -30_000_000, "b"));
        assertTrue(t.insert(0, 0, 0, "c"));
        assertEquals("a", t.get(-30_000_000, -64, 30_000_000));
        assertEquals("b", t.findAny(30_000_000, 319, -30_000_000, BlockRange.sphere(1), null));
        assertEquals(1, t.query(0, 0, 0, BlockRange.box(5), null, new ArrayList<>()));
    }

    @Test
    void pointOperationsFollowContract() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        assertTrue(t.insert(1, 2, 3, 10));
        assertFalse(t.insert(1, 2, 3, 11));
        assertEquals(10, t.get(1, 2, 3));
        assertTrue(t.contains(1, 2, 3));
        assertEquals(1, t.size());
        assertFalse(t.isEmpty());
        assertFalse(t.insert(99, 0, 0, 1));
        assertNull(t.get(99, 0, 0));
        assertNull(t.remove(99, 0, 0));
        assertNull(t.get(5, 5, 5));
        assertFalse(t.contains(5, 5, 5));
        assertEquals(10, t.remove(1, 2, 3));
        assertNull(t.remove(1, 2, 3));
        assertEquals(0, t.size());
        assertTrue(t.isEmpty());
        assertTrue(t.insert(1, 2, 3, 12));
        assertThrows(NullPointerException.class, () -> t.insert(4, 4, 4, null));
    }

    @Test
    void inBoundsCoversThePowerOfTwoCube() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 10, 0, 10);
        assertTrue(t.inBounds(0, 0, 0));
        assertTrue(t.inBounds(15, 15, 15));
        assertFalse(t.inBounds(16, 0, 0));
        assertFalse(t.inBounds(-1, 0, 0));
    }

    @Test
    void constructorRejectsBadBounds() {
        assertThrows(IllegalArgumentException.class, () -> new BlockOctree<Integer>(5, 0, 0, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockOctree<Integer>(0, 0, 0, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockOctree<Integer>(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
    }

    @Test
    void singleCellTree() {
        BlockOctree<Integer> one = new BlockOctree<>(7, 7, 7, 7, 7, 7);
        assertTrue(one.insert(7, 7, 7, 1));
        assertFalse(one.insert(7, 7, 7, 2));
        assertEquals(1, one.size());
        assertEquals(1, one.query(7, 7, 7, BlockRange.sphere(0), null, new ArrayList<>()));
        assertEquals(1, one.findNearest(7, 7, 7, BlockRange.box(0), null));
    }

    @Test
    void clearEmptiesAndTreeStaysUsable() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        for (int i = 0; i < 100; i++) t.insert(i % 16, i / 16, 3, i);
        t.clear();
        assertEquals(0, t.size());
        assertNull(t.get(1, 0, 3));
        assertNull(t.findAny(v -> true));
        assertTrue(t.insert(1, 2, 3, 13));
        assertEquals(13, t.get(1, 2, 3));
    }

    @Test
    void emptyRangeOrEmptyTreeYieldsNothing() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        assertNull(t.findAny(0, 0, 0, BlockRange.sphere(5), null));
        assertNull(t.findNearest(0, 0, 0, BlockRange.sphere(5), null));
        assertNull(t.findAny(v -> true));
        t.insert(0, 0, 0, 1);
        assertEquals(0, t.query(0, 0, 0, BlockRange.sphere(-1), null, new ArrayList<>()));
        assertEquals(0, t.query(0, 0, 0, BlockRange.box(-1), null, new ArrayList<>()));
        assertNull(t.findAny(0, 0, 0, BlockRange.box(-1), null));
        assertNull(t.findNearest(0, 0, 0, BlockRange.sphere(-1), null));
    }

    @Test
    void compactPreservesContentAndStaysUsable() {
        assertEquals(0, new BlockOctree<Integer>(0, 15, 0, 15).compact());
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) t.insert(x, 7, z, x * 100 + z);
        }
        assertEquals(256, t.compact());
        assertEquals(256, t.size());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) assertEquals(x * 100 + z, t.get(x, 7, z));
        }
        assertEquals(256, t.query(8, 7, 8, BlockRange.sphere(64), null, new ArrayList<>()));
        assertFalse(t.insert(3, 7, 3, 4242));
        assertTrue(t.insert(3, 8, 3, 4242));
        assertEquals(4242, t.remove(3, 8, 3));
        assertEquals(256, t.compact());
    }

    @Test
    void sphereAndBoxQueriesDifferAtCorners() {
        BlockOctree<String> t = new BlockOctree<>(-16, 15, -16, 15);
        t.insert(0, 0, 0, "center");
        t.insert(3, 0, 0, "edge");
        t.insert(3, 3, 3, "corner");
        t.insert(4, 0, 0, "outside");
        Set<String> sphere = new HashSet<>();
        t.query(0, 0, 0, BlockRange.sphere(3), null, sphere);
        assertEquals(Set.of("center", "edge"), sphere);
        Set<String> box = new HashSet<>();
        t.query(0, 0, 0, BlockRange.box(3), null, box);
        assertEquals(Set.of("center", "edge", "corner"), box);
        Set<String> slab = new HashSet<>();
        t.query(0, 0, 0, BlockRange.box(4, 0, 4), null, slab);
        assertEquals(Set.of("center", "edge", "outside"), slab);
    }

    @Test
    void consumerQueryMatchesCollectionQuery() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 63, 0, 63);
        for (int i = 0; i < 500; i++) t.insert((i * 7) % 64, (i * 13) % 64, (i * 29) % 64, i);
        List<Integer> viaConsumer = new ArrayList<>();
        t.query(30, 30, 30, BlockRange.box(12, 5, 20), v -> v % 3 != 0, viaConsumer::add);
        List<Integer> viaCollection = new ArrayList<>();
        int n = t.query(30, 30, 30, BlockRange.box(12, 5, 20), v -> v % 3 != 0, viaCollection);
        assertEquals(new HashSet<>(viaCollection), new HashSet<>(viaConsumer));
        assertEquals(viaCollection.size(), n);
    }

    @Test
    void queryAppendsToExistingCollectionAndReturnsAddedCount() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        t.insert(1, 1, 1, 1);
        t.insert(2, 2, 2, 2);
        List<Integer> out = new ArrayList<>(List.of(99));
        assertEquals(2, t.query(1, 1, 1, BlockRange.box(1), null, out));
        assertEquals(3, out.size());
    }

    @Test
    void filterAndFindAny() {
        BlockOctree<String> t = new BlockOctree<>(0, 31, 0, 31);
        String[] names = { "stone", "dirt", "grass", "ore_iron", "ore_gold", "water", "lava", "air" };
        int[][] pos = { { 0, 0, 0 }, { 1, 0, 0 }, { 2, 0, 0 }, { 5, 5, 5 }, { 6, 5, 5 }, { 10, 0, 0 }, { 11, 0, 0 }, { 20, 20, 20 } };
        for (int i = 0; i < names.length; i++) t.insert(pos[i][0], pos[i][1], pos[i][2], names[i]);

        Predicate<String> isOre = v -> v.startsWith("ore_");
        List<String> out = new ArrayList<>();
        assertEquals(2, t.query(5, 5, 5, BlockRange.sphere(3), isOre, out));
        assertTrue(out.containsAll(List.of("ore_iron", "ore_gold")));
        assertEquals(2, t.query(5, 5, 5, BlockRange.sphere(3), null, new ArrayList<>()));

        String anyOre = t.findAny(5, 5, 5, BlockRange.sphere(3), isOre);
        assertTrue("ore_iron".equals(anyOre) || "ore_gold".equals(anyOre));
        assertNotNull(t.findAny(0, 0, 0, BlockRange.box(0), null));
        assertNull(t.findAny(5, 5, 5, BlockRange.sphere(3), v -> v.equals("bedrock")));
        assertNull(t.findAny(20, 20, 20, BlockRange.box(2), isOre));
        assertEquals("water", t.findAny(v -> v.equals("water")));
        assertNull(t.findAny(v -> v.equals("bedrock")));

        int[] calls = { 0 };
        t.findAny(5, 5, 5, BlockRange.sphere(3), v -> {
            calls[0]++;
            return true;
        });
        assertEquals(1, calls[0]);

        Set<String> seen = new HashSet<>();
        t.forEach(seen::add);
        assertEquals(Set.of(names), seen);
    }

    @Test
    void findNearestPicksClosestByEuclideanDistance() {
        BlockOctree<String> t = new BlockOctree<>(-32, 31, -32, 31);
        t.insert(5, 0, 0, "far");
        t.insert(0, 3, 0, "near");
        t.insert(2, 2, 2, "diag");
        assertEquals("near", t.findNearest(0, 0, 0, BlockRange.sphere(10), null));
        assertEquals("diag", t.findNearest(0, 0, 0, BlockRange.sphere(10), v -> !v.equals("near")));
        assertEquals("far", t.findNearest(0, 0, 0, BlockRange.sphere(10), v -> v.equals("far")));
        assertNull(t.findNearest(0, 0, 0, BlockRange.sphere(2), null));
        assertEquals("diag", t.findNearest(0, 0, 0, BlockRange.box(2), null));
        assertNull(t.findNearest(0, 0, 0, BlockRange.sphere(10), v -> v.equals("missing")));
    }

    @Test
    void findNearestBreaksTiesByLowestCoordinate() {
        BlockOctree<String> t = new BlockOctree<>(-32, 31, -32, 31);
        t.insert(1, 0, 0, "+x");
        t.insert(0, 0, -1, "-z");
        t.insert(0, 1, 0, "+y");
        t.insert(-1, 0, 0, "-x");
        assertEquals("-x", t.findNearest(0, 0, 0, BlockRange.sphere(1), null));
        assertEquals("-z", t.findNearest(0, 0, 0, BlockRange.sphere(1), v -> !v.equals("-x")));
        assertEquals("+y", t.findNearest(0, 0, 0, BlockRange.sphere(1), v -> v.startsWith("+")));
    }

    @Test
    void queryCenterOutsideTreeStillFindsReachableEntries() {
        BlockOctree<Integer> t = new BlockOctree<>(0, 15, 0, 15);
        t.insert(0, 0, 0, 1);
        t.insert(15, 15, 15, 2);
        assertEquals(1, t.findAny(-3, 0, 0, BlockRange.box(3), null));
        assertEquals(2, t.findNearest(18, 15, 15, BlockRange.sphere(3), null));
        assertEquals(0, t.query(-4, 0, 0, BlockRange.box(3), null, new ArrayList<>()));
        assertEquals(0, t.query(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, BlockRange.sphere(100), null, new ArrayList<>()));
    }

    static Stream<Arguments> randomizedCases() {
        int[][] regions = {
                { 0, 0, 0, 7, 7, 7 },
                { 0, 0, 0, 31, 31, 31 },
                { -16, -16, -16, 15, 15, 15 },
                { -512, 0, -512, 511, 63, 511 },
                { 0, 12, 0, 15, 12, 15 },
                { -4, -1000, -4, 3, 1000, 3 },
                { 1000, 2000, 1000, 1015, 2015, 1015 },
        };
        int[] capacities = { 1, 2, 4, 8, 16, 32 };
        List<Arguments> out = new ArrayList<>();
        for (int[] r : regions) {
            for (int cap : capacities) out.add(Arguments.of(r, cap));
        }
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("randomizedCases")
    void randomizedAgainstReference(int[] r, int capacity) {
        int minX = r[0], minY = r[1], minZ = r[2], maxX = r[3], maxY = r[4], maxZ = r[5];
        BlockOctree<Integer> tree = new BlockOctree<>(minX, minY, minZ, maxX, maxY, maxZ, capacity);
        Map<Long, Integer> ref = new HashMap<>();
        Map<Integer, int[]> posOf = new HashMap<>();
        SplittableRandom rnd = new SplittableRandom(0xBEAD + capacity * 31L + minX * 7L + minZ);
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;

        for (int step = 0; step < 800; step++) {
            int x = minX - 2 + rnd.nextInt(sx + 4);
            int y = minY - 2 + rnd.nextInt(sy + 4);
            int z = minZ - 2 + rnd.nextInt(sz + 4);
            boolean in = tree.inBounds(x, y, z);
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) assertTrue(in);
            long key = key(x, y, z);
            switch (rnd.nextInt(12)) {
                case 0, 1, 2, 3 -> {
                    boolean expected = in && !ref.containsKey(key);
                    assertEquals(expected, tree.insert(x, y, z, step));
                    if (expected) {
                        ref.put(key, step);
                        posOf.put(step, new int[] { x, y, z });
                    }
                }
                case 4 -> {
                    Integer expect = ref.remove(key);
                    assertEquals(expect, tree.remove(x, y, z));
                }
                case 5 -> assertEquals(ref.get(key), tree.get(x, y, z));
                case 6, 7 -> {
                    BlockRange range = randomRange(rnd);
                    Predicate<Integer> filter = rnd.nextBoolean() ? v -> (v & 1) == 0 : null;
                    List<Integer> got = new ArrayList<>();
                    tree.query(x, y, z, range, filter, got);
                    assertEquals(got.size(), new HashSet<>(got).size());
                    assertEquals(reference(posOf, ref, x, y, z, range, filter), new HashSet<>(got), range.toString());
                }
                case 8, 9 -> {
                    BlockRange range = randomRange(rnd);
                    Predicate<Integer> filter = rnd.nextBoolean() ? v -> (v & 3) == 0 : null;
                    Integer got = tree.findAny(x, y, z, range, filter);
                    Set<Integer> expected = reference(posOf, ref, x, y, z, range, filter);
                    if (expected.isEmpty()) assertNull(got);
                    else assertTrue(expected.contains(got));
                }
                default -> {
                    BlockRange range = randomRange(rnd);
                    Predicate<Integer> filter = rnd.nextBoolean() ? v -> (v % 3) != 0 : null;
                    assertEquals(referenceNearest(posOf, ref, x, y, z, range, filter), tree.findNearest(x, y, z, range, filter), range.toString());
                }
            }
            assertEquals(ref.size(), tree.size());
            if (step % 200 == 199) assertEquals(ref.size(), tree.compact());
        }

        for (Map.Entry<Long, Integer> e : ref.entrySet()) {
            int[] p = posOf.get(e.getValue());
            assertEquals(e.getValue(), tree.get(p[0], p[1], p[2]));
        }
        Set<Integer> visited = new HashSet<>();
        tree.forEach(visited::add);
        assertEquals(new HashSet<>(ref.values()), visited);
    }

    private static BlockRange randomRange(SplittableRandom rnd) {
        return switch (rnd.nextInt(4)) {
            case 0 -> BlockRange.sphere(rnd.nextInt(7) - 1);
            case 1 -> BlockRange.sphereSq(rnd.nextInt(40) - 1);
            case 2 -> BlockRange.box(rnd.nextInt(7) - 1);
            default -> BlockRange.box(rnd.nextInt(6), rnd.nextInt(6), rnd.nextInt(6));
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (z & 0x1FFFFF) << 21) | (y & 0x1FFFFF);
    }

    private static Set<Integer> reference(Map<Integer, int[]> posOf, Map<Long, Integer> ref, int cx, int cy, int cz,
                                          BlockRange range, Predicate<Integer> filter) {
        Set<Integer> out = new HashSet<>();
        for (Integer v : ref.values()) {
            int[] p = posOf.get(v);
            if (!range.contains(p[0] - cx, p[1] - cy, p[2] - cz)) continue;
            if (filter == null || filter.test(v)) out.add(v);
        }
        return out;
    }

    private static Integer referenceNearest(Map<Integer, int[]> posOf, Map<Long, Integer> ref, int cx, int cy, int cz,
                                            BlockRange range, Predicate<Integer> filter) {
        Integer best = null;
        long bestD2 = Long.MAX_VALUE;
        int[] bestPos = null;
        for (Integer v : ref.values()) {
            int[] p = posOf.get(v);
            int dx = p[0] - cx, dy = p[1] - cy, dz = p[2] - cz;
            if (!range.contains(dx, dy, dz)) continue;
            if (filter != null && !filter.test(v)) continue;
            long d2 = (long) dx * dx + (long) dy * dy + (long) dz * dz;
            if (d2 < bestD2 || (d2 == bestD2 && lexLess(p, bestPos))) {
                best = v;
                bestD2 = d2;
                bestPos = p;
            }
        }
        return best;
    }

    private static boolean lexLess(int[] a, int[] b) {
        if (a[0] != b[0]) return a[0] < b[0];
        if (a[1] != b[1]) return a[1] < b[1];
        return a[2] < b[2];
    }
}
