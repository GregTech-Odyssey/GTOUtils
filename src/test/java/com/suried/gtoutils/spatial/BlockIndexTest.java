package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockIndexTest {

    @Test
    void putRegistersAndReplacesAtSamePosition() {
        BlockIndex<String> index = new BlockIndex<>();
        assertTrue(index.put(1, 2, 3, "a"));
        assertEquals("a", index.get(1, 2, 3));
        assertTrue(index.put(1, 2, 3, "b"));
        assertEquals("b", index.get(1, 2, 3));
        assertEquals(1, index.size());
    }

    @Test
    void versionChangesOnlyWhenContentChanges() {
        BlockIndex<String> index = new BlockIndex<>();
        int v0 = index.version();
        index.put(0, 0, 0, "a");
        int v1 = index.version();
        assertNotEquals(v0, v1);
        index.put(0, 0, 0, "a");
        assertEquals(v1, index.version());
        index.put(0, 0, 0, "b");
        int v2 = index.version();
        assertNotEquals(v1, v2);
        assertNull(index.remove(9, 9, 9));
        assertFalse(index.remove(0, 0, 0, "a"));
        assertEquals(v2, index.version());
        assertEquals("b", index.remove(0, 0, 0));
        int v3 = index.version();
        assertNotEquals(v2, v3);
        index.clear();
        assertEquals(v3, index.version());
        index.put(5, 5, 5, "c");
        int v4 = index.version();
        index.clear();
        assertNotEquals(v4, index.version());
        assertTrue(index.isEmpty());
    }

    @Test
    void removeWithExpectedOnlyRemovesThatObject() {
        BlockIndex<Object> index = new BlockIndex<>();
        Object oldTile = new Object();
        Object newTile = new Object();
        index.put(4, 5, 6, oldTile);
        index.put(4, 5, 6, newTile);
        assertFalse(index.remove(4, 5, 6, oldTile));
        assertSame(newTile, index.get(4, 5, 6));
        assertFalse(index.remove(4, 5, 6, null));
        assertTrue(index.remove(4, 5, 6, newTile));
        assertNull(index.get(4, 5, 6));
        assertFalse(index.remove(4, 5, 6, newTile));
    }

    @Test
    void acceptsWholeWorldAndRejectsOutside() {
        BlockIndex<String> index = new BlockIndex<>();
        assertTrue(index.put(-30_000_000, -64, -30_000_000, "min"));
        assertTrue(index.put(30_000_000, 319, 30_000_000, "max"));
        assertTrue(index.put(0, -2048, 0, "lowY"));
        assertTrue(index.put(0, 2047, 0, "highY"));
        assertFalse(index.put(BlockIndex.MAX_XZ + 1, 0, 0, "x"));
        assertFalse(index.put(0, BlockIndex.MIN_Y - 1, 0, "y"));
        assertFalse(index.put(0, 0, BlockIndex.MIN_XZ - 1, "z"));
        assertFalse(index.inBounds(0, 4000, 0));
        assertEquals(4, index.size());
        assertEquals("max", index.get(30_000_000, 319, 30_000_000));
    }

    @Test
    void rangeQueriesUseSelectedShape() {
        BlockIndex<String> index = new BlockIndex<>();
        index.put(100, 64, 100, "center");
        index.put(103, 67, 103, "corner");
        index.put(104, 64, 100, "side");
        Set<String> box = new HashSet<>();
        index.query(100, 64, 100, BlockRange.box(3), null, box);
        assertEquals(Set.of("center", "corner"), box);
        Set<String> sphere = new HashSet<>();
        index.query(100, 64, 100, BlockRange.sphere(4), null, (java.util.function.Consumer<String>) sphere::add);
        assertEquals(Set.of("center", "side"), sphere);
        assertEquals("side", index.findAny(100, 64, 100, BlockRange.box(4, 0, 0), v -> !v.equals("center")));
        assertEquals("center", index.findNearest(101, 64, 100, BlockRange.sphere(5), null));
        assertEquals("side", index.findNearest(101, 64, 100, BlockRange.sphere(5), v -> !v.equals("center")));
    }

    @Test
    void blockPosOverloadsMatchIntOverloads() {
        BlockIndex<String> index = new BlockIndex<>();
        BlockPos pos = new BlockPos(-7, 70, 12);
        assertTrue(index.put(pos, "p"));
        assertEquals("p", index.get(pos));
        assertEquals("p", index.get(-7, 70, 12));
        List<String> out = new ArrayList<>();
        assertEquals(1, index.query(pos.offset(2, 0, 0), BlockRange.box(2), null, out));
        assertEquals("p", index.findAny(pos.above(), BlockRange.sphere(1), null));
        assertEquals("p", index.findNearest(pos.below(3), BlockRange.box(3), null));
        assertFalse(index.remove(pos, "other"));
        assertTrue(index.remove(pos, "p"));
        assertTrue(index.put(pos, "q"));
        assertEquals("q", index.remove(pos));
        assertTrue(index.isEmpty());
    }

    @Test
    void forEachVisitsEveryRegisteredObject() {
        BlockIndex<Integer> index = new BlockIndex<>();
        Set<Integer> expected = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            index.put(i * 37 - 5000, (i % 50) - 20, i * 11, i);
            expected.add(i);
        }
        Set<Integer> seen = new HashSet<>();
        index.forEach(seen::add);
        assertEquals(expected, seen);
        assertEquals(300, index.size());
    }

    @Test
    void queriesAcrossRegionBoundariesMatchBruteForce() {
        BlockIndex<int[]> index = new BlockIndex<>();
        List<int[]> all = new ArrayList<>();
        int[] corners = { -257, -256, -255, -1, 0, 1, 254, 255, 256, 257, 511, 512 };
        for (int x : corners) {
            for (int y : new int[] { -1, 0, 255, 256 }) {
                for (int z : corners) {
                    int[] p = { x, y, z };
                    index.put(x, y, z, p);
                    all.add(p);
                }
            }
        }
        BlockRange[] ranges = { BlockRange.box(2), BlockRange.sphere(3), BlockRange.box(300, 2, 300), BlockRange.sphere(260) };
        int[][] centers = { { 0, 0, 0 }, { 256, 256, 256 }, { -256, 0, 255 }, { 255, -1, -1 }, { 511, 255, 512 } };
        for (BlockRange r : ranges) {
            for (int[] c : centers) {
                Set<int[]> expected = new HashSet<>();
                int[] nearest = null;
                long nd = Long.MAX_VALUE;
                for (int[] p : all) {
                    int dx = p[0] - c[0], dy = p[1] - c[1], dz = p[2] - c[2];
                    if (!r.contains(dx, dy, dz)) continue;
                    expected.add(p);
                    long d2 = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (nearest == null || d2 < nd || (d2 == nd && (p[0] != nearest[0] ? p[0] < nearest[0] : p[1] != nearest[1] ? p[1] < nearest[1] : p[2] < nearest[2]))) {
                        nearest = p;
                        nd = d2;
                    }
                }
                List<int[]> got = new ArrayList<>();
                index.query(c[0], c[1], c[2], r, null, got);
                assertEquals(expected, new HashSet<>(got), r + " at " + c[0] + "," + c[1] + "," + c[2]);
                assertEquals(expected.size(), got.size());
                assertSame(nearest, index.findNearest(c[0], c[1], c[2], r, null), r + " nearest");
                int[] any = index.findAny(c[0], c[1], c[2], r, null);
                if (expected.isEmpty()) assertNull(any);
                else assertTrue(expected.contains(any));
            }
        }
    }

    @Test
    void hugeRangeVisitsOnlyExistingRegions() {
        BlockIndex<String> index = new BlockIndex<>();
        index.put(-20_000_000, 10, 5, "a");
        index.put(20_000_000, 10, 5, "b");
        index.put(0, 10, 5, "c");
        List<String> out = new ArrayList<>();
        index.query(0, 10, 5, BlockRange.box(30_000_000, 100, 10), null, out);
        assertEquals(Set.of("a", "b", "c"), new HashSet<>(out));
        assertEquals("c", index.findNearest(5, 10, 5, BlockRange.box(30_000_000, 100, 10), null));
    }

    @Test
    void nearestTieAcrossRegionsUsesLowestCoordinate() {
        BlockIndex<String> index = new BlockIndex<>();
        index.put(255, 64, 0, "west");
        index.put(257, 64, 0, "east");
        index.put(256, 63, 0, "below");
        assertEquals("west", index.findNearest(256, 64, 0, BlockRange.sphere(1), null));
        assertEquals("below", index.findNearest(256, 64, 0, BlockRange.sphere(1), v -> !v.equals("west")));
    }

    @Test
    void emptyRegionsAreReleased() {
        BlockIndex<String> index = new BlockIndex<>();
        index.put(0, 0, 0, "a");
        index.put(1000, 0, 0, "b");
        assertEquals(2, index.regionCount());
        index.remove(0, 0, 0);
        assertEquals(1, index.regionCount());
        assertFalse(index.remove(1000, 0, 0, "x"));
        assertTrue(index.remove(1000, 0, 0, "b"));
        assertEquals(0, index.regionCount());
        assertTrue(index.isEmpty());
        index.put(5, 5, 5, "c");
        index.clear();
        assertEquals(0, index.regionCount());
    }

    @Test
    void nestedQueryFromFilterIsSafe() {
        BlockIndex<Integer> index = new BlockIndex<>();
        for (int i = 0; i < 20; i++) index.put(i * 100, 0, 0, i);
        List<Integer> out = new ArrayList<>();
        index.query(1000, 0, 0, BlockRange.box(1000, 0, 0), v -> index.findAny(v * 100, 0, 0, BlockRange.box(0), null) != null, out);
        assertEquals(new HashSet<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19)), new HashSet<>(out));
    }

    @Test
    void sparseRegionsStaySmall() {
        BlockIndex<Integer> index = new BlockIndex<>();
        for (int i = 0; i < 200; i++) index.put(i * 1000, 64, -i * 1000, i);
        assertEquals(200, index.regionCount());
        for (int i = 0; i < 200; i++) assertEquals(i, index.get(i * 1000, 64, -i * 1000));
        assertEquals(1, index.query(5000, 64, -5000, BlockRange.box(2), null, new ArrayList<>()));
    }
}
