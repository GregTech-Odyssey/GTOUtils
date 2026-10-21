package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SectionScanTest {

    private static final int[] LIMITS = { 1, 2, 3, 7, 50, Integer.MAX_VALUE };

    private static final class FakeSections implements SectionScan.Sections<Integer> {

        final int minSy, maxSy;
        final Integer outside, background;
        final Map<Long, Integer> uniform = new HashMap<>();
        final Map<Long, int[]> dense = new HashMap<>();
        final Map<Long, Set<Integer>> palettes = new HashMap<>();
        int seeks, gets, paletteChecks;
        Set<Long> seeked = new HashSet<>();
        Set<Long> read = new HashSet<>();
        private long current;
        private int[] data;
        private Set<Integer> palette;

        FakeSections(int minSy, int maxSy, int outside, int background) {
            this.minSy = minSy;
            this.maxSy = maxSy;
            this.outside = outside;
            this.background = background;
        }

        static long key(int sx, int sy, int sz) {
            return BlockPos.asLong(sx, sy, sz);
        }

        void setUniform(int sx, int sy, int sz, int value) {
            uniform.put(key(sx, sy, sz), value);
        }

        void setDense(int sx, int sy, int sz, int[] values, Set<Integer> stale) {
            long k = key(sx, sy, sz);
            dense.put(k, values);
            Set<Integer> p = new HashSet<>(stale);
            for (int v : values) p.add(v);
            palettes.put(k, p);
        }

        int valueAt(int x, int y, int z) {
            int sy = y >> 4;
            if (sy < minSy || sy > maxSy) return outside;
            long k = key(x >> 4, sy, z >> 4);
            int[] d = dense.get(k);
            if (d == null) return uniform.getOrDefault(k, background);
            return d[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }

        void resetCounters() {
            seeks = gets = paletteChecks = 0;
            seeked.clear();
            read.clear();
        }

        @Override
        public Integer seek(int sx, int sy, int sz) {
            seeks++;
            long k = key(sx, sy, sz);
            seeked.add(k);
            if (sy < minSy || sy > maxSy) return outside;
            int[] d = dense.get(k);
            if (d == null) return uniform.getOrDefault(k, background);
            current = k;
            data = d;
            palette = palettes.get(k);
            return null;
        }

        @Override
        public boolean mayContain(Predicate<? super Integer> p) {
            paletteChecks++;
            for (Integer v : palette) {
                if (p.test(v)) return true;
            }
            return false;
        }

        @Override
        public Integer get(int lx, int ly, int lz) {
            gets++;
            read.add(current);
            return data[(ly << 8) | (lz << 4) | lx];
        }
    }

    private static FakeSections randomWorld(Random random, int values) {
        FakeSections s = new FakeSections(-2, 2, 99, 0);
        for (int sx = -3; sx <= 2; sx++) {
            for (int sy = -2; sy <= 2; sy++) {
                for (int sz = -3; sz <= 2; sz++) {
                    int kind = random.nextInt(10);
                    if (kind < 2) {
                        s.setUniform(sx, sy, sz, random.nextInt(values));
                    } else if (kind < 9) {
                        int[] d = new int[4096];
                        int rare = 1 + random.nextInt(values - 1);
                        double density = random.nextDouble() * 0.05;
                        for (int i = 0; i < d.length; i++) {
                            d[i] = random.nextDouble() < density ? rare : random.nextInt(4) == 0 ? random.nextInt(values) : 0;
                        }
                        Set<Integer> stale = new HashSet<>();
                        if (random.nextInt(4) == 0) stale.add(random.nextInt(values));
                        s.setDense(sx, sy, sz, d, stale);
                    }
                }
            }
        }
        return s;
    }

    private static Set<Integer> randomTargets(Random random, int values) {
        Set<Integer> targets = new HashSet<>();
        int n = 1 + random.nextInt(3);
        for (int i = 0; i < n; i++) targets.add(random.nextInt(values));
        if (random.nextInt(5) == 0) targets.add(99);
        return targets;
    }

    private static SectionScan.CellTest<Integer> hashedTest(Set<Integer> targets) {
        return (v, p) -> {
            assertTrue(targets.contains(v), "test called with a value rejected by palette");
            return ((p.getX() * 31 + p.getY() * 17 + p.getZ() * 7 + v) & 3) != 0;
        };
    }

    private static List<BlockPos> bruteBetweenClosed(FakeSections s, int x0, int y0, int z0, int x1, int y1, int z1,
                                                     Predicate<Integer> palette, SectionScan.CellTest<Integer> test, int limit) {
        List<BlockPos> out = new ArrayList<>();
        if (x0 > x1 || y0 > y1 || z0 > z1) return out;
        for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
            int v = s.valueAt(p.getX(), p.getY(), p.getZ());
            if (palette.test(v) && test.test(v, p)) {
                out.add(p.immutable());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private static List<BlockPos> bruteWithinManhattan(FakeSections s, BlockPos c, int rx, int ry, int rz,
                                                       Predicate<Integer> palette, SectionScan.CellTest<Integer> test, int limit) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.withinManhattan(c, rx, ry, rz)) {
            int v = s.valueAt(p.getX(), p.getY(), p.getZ());
            if (palette.test(v) && test.test(v, p)) {
                out.add(p.immutable());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    @Test
    void betweenClosedMatchesVanillaOrderAndLimit() {
        Random random = new Random(11);
        for (int round = 0; round < 300; round++) {
            FakeSections s = randomWorld(random, 6);
            Set<Integer> targets = randomTargets(random, 6);
            Predicate<Integer> palette = targets::contains;
            SectionScan.CellTest<Integer> test = hashedTest(targets);
            int x0 = random.nextInt(80) - 50, y0 = random.nextInt(90) - 55, z0 = random.nextInt(80) - 50;
            int x1 = x0 + random.nextInt(36), y1 = y0 + random.nextInt(50), z1 = z0 + random.nextInt(36);
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            List<BlockPos> expected = bruteBetweenClosed(s, x0, y0, z0, x1, y1, z1, palette, test, limit);
            List<BlockPos> actual = new ArrayList<>();
            int n = SectionScan.betweenClosed(s, x0, y0, z0, x1, y1, z1, palette, test, limit, actual);
            assertEquals(expected, actual, "round " + round);
            assertEquals(expected.size(), n);
        }
    }

    @Test
    void withinManhattanMatchesVanillaOrderAndLimit() {
        Random random = new Random(12);
        for (int round = 0; round < 300; round++) {
            FakeSections s = randomWorld(random, 6);
            Set<Integer> targets = randomTargets(random, 6);
            Predicate<Integer> palette = targets::contains;
            SectionScan.CellTest<Integer> test = hashedTest(targets);
            BlockPos c = new BlockPos(random.nextInt(80) - 45, random.nextInt(90) - 45, random.nextInt(80) - 45);
            int rx = random.nextInt(18), ry = random.nextInt(30), rz = random.nextInt(18);
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            List<BlockPos> expected = bruteWithinManhattan(s, c, rx, ry, rz, palette, test, limit);
            List<BlockPos> actual = new ArrayList<>();
            int n = SectionScan.withinManhattan(s, c.getX(), c.getY(), c.getZ(), rx, ry, rz, palette, test, limit, actual);
            assertEquals(expected, actual, "round " + round);
            assertEquals(expected.size(), n);
        }
    }

    @Test
    void sectionsWithoutPaletteMatchAreNotRead() {
        FakeSections s = new FakeSections(-4, 4, 99, 0);
        int[] plain = new int[4096];
        for (int i = 0; i < plain.length; i++) plain[i] = i % 3 == 0 ? 1 : 0;
        int[] withTarget = plain.clone();
        withTarget[123] = 5;
        for (int sx = 0; sx < 3; sx++) {
            for (int sz = 0; sz < 3; sz++) {
                s.setDense(sx, 0, sz, plain, Set.of());
            }
        }
        s.setDense(1, 0, 1, withTarget, Set.of());
        s.setDense(2, 0, 2, plain, Set.of(5));
        List<BlockPos> out = new ArrayList<>();
        SectionScan.betweenClosed(s, 0, 0, 0, 47, 15, 47, v -> v == 5, (v, p) -> true, Integer.MAX_VALUE, out);
        assertEquals(List.of(new BlockPos(16 + 11, 0, 16 + 7)), out);
        assertEquals(Set.of(FakeSections.key(1, 0, 1), FakeSections.key(2, 0, 2)), s.read);
        assertEquals(2 * 4096, s.gets);
        s.resetCounters();
        out.clear();
        SectionScan.withinManhattan(s, 24, 8, 24, 24, 8, 24, v -> v == 5, (v, p) -> true, Integer.MAX_VALUE, out);
        assertEquals(List.of(new BlockPos(16 + 11, 0, 16 + 7)), out);
        assertEquals(Set.of(FakeSections.key(1, 0, 1), FakeSections.key(2, 0, 2)), s.read);
    }

    @Test
    void uniformSectionsAreJudgedOnceWithoutReading() {
        FakeSections s = new FakeSections(0, 0, 99, 7);
        s.setUniform(0, 0, 0, 3);
        int[] paletteCalls = { 0 };
        int[] testCalls = { 0 };
        Predicate<Integer> palette = v -> {
            paletteCalls[0]++;
            return v == 3 || v == 99;
        };
        SectionScan.CellTest<Integer> test = (v, p) -> {
            testCalls[0]++;
            return v == 3 && p.getX() == 5;
        };
        List<BlockPos> out = new ArrayList<>();
        SectionScan.betweenClosed(s, 0, -16, 0, 31, 15, 15, palette, test, Integer.MAX_VALUE, out);
        assertEquals(0, s.gets);
        assertEquals(4, s.seeks);
        assertEquals(4, paletteCalls[0]);
        assertEquals(16 * 16 * 16 * 3, testCalls[0]);
        assertEquals(256, out.size());
        for (BlockPos p : out) assertEquals(5, p.getX());
    }

    @Test
    void testIsNotCalledWhenPaletteRejectsCellValue() {
        Random random = new Random(13);
        for (int round = 0; round < 40; round++) {
            FakeSections s = randomWorld(random, 5);
            Set<Integer> targets = randomTargets(random, 5);
            List<BlockPos> out = new ArrayList<>();
            SectionScan.betweenClosed(s, -40, -40, -40, 30, 40, 30, targets::contains, hashedTest(targets), Integer.MAX_VALUE, out);
            SectionScan.withinManhattan(s, 0, 0, 0, 30, 40, 30, targets::contains, hashedTest(targets), Integer.MAX_VALUE, out);
        }
    }

    @Test
    void smallLimitsStopWithinTheFirstCellsLikeVanilla() {
        FakeSections s = new FakeSections(-8, 8, 99, 1);
        List<BlockPos> out = new ArrayList<>();
        SectionScan.withinManhattan(s, 0, 0, 0, 64, 64, 64, v -> v == 1, (v, p) -> true, 10, out);
        assertEquals(bruteWithinManhattan(s, BlockPos.ZERO, 64, 64, 64, v -> v == 1, (v, p) -> true, 10), out);
        assertTrue(s.seeks <= 10, "seeked " + s.seeks);
        s.resetCounters();
        out.clear();
        SectionScan.betweenClosed(s, 0, 0, 0, 63, 63, 63, v -> v == 1, (v, p) -> true, 5, out);
        assertEquals(bruteBetweenClosed(s, 0, 0, 0, 63, 63, 63, v -> v == 1, (v, p) -> true, 5), out);
        assertTrue(s.seeks <= 5, "seeked " + s.seeks);
        s.resetCounters();
        out.clear();
        SectionScan.inRange(s, 0, 0, 0, BlockRange.sphere(40), v -> v == 1, (v, p) -> true, 7, out);
        assertEquals(7, out.size());
        assertTrue(s.seeks <= 7, "seeked " + s.seeks);
    }

    @Test
    void withinManhattanReadsOnlyTheNearestBandForCommonTargets() {
        FakeSections s = new FakeSections(-8, 8, 99, 0);
        int[] ones = new int[4096];
        Arrays.fill(ones, 1);
        for (int sx = -1; sx <= 1; sx++) for (int sy = -1; sy <= 1; sy++) for (int sz = -1; sz <= 1; sz++) s.setDense(sx, sy, sz, ones, Set.of());
        List<BlockPos> out = new ArrayList<>();
        SectionScan.withinManhattan(s, 8, 8, 8, 20, 20, 20, v -> v == 1, (v, p) -> true, 50, out);
        assertEquals(bruteWithinManhattan(s, new BlockPos(8, 8, 8), 20, 20, 20, v -> v == 1, (v, p) -> true, 50), out);
        assertTrue(s.gets <= 833, "read " + s.gets + " cells");
        s.resetCounters();
        out.clear();
        SectionScan.withinManhattan(s, 8, 8, 8, 20, 20, 20, v -> v == 1, (v, p) -> p.getY() == 20, 5, out);
        assertEquals(bruteWithinManhattan(s, new BlockPos(8, 8, 8), 20, 20, 20, v -> v == 1, (v, p) -> p.getY() == 20, 5), out);
        assertTrue(s.gets < 9 * 4096, "read " + s.gets + " cells");
    }

    @Test
    void withinManhattanStopsBeforeFartherSections() {
        FakeSections s = new FakeSections(-8, 8, 99, 0);
        for (int sx = -5; sx <= 5; sx++) for (int sz = -5; sz <= 5; sz++) s.setUniform(sx, 2, sz, 1);
        List<BlockPos> out = new ArrayList<>();
        SectionScan.withinManhattan(s, 0, 0, 0, 64, 64, 64, v -> v == 1, (v, p) -> true, 10, out);
        assertEquals(bruteWithinManhattan(s, BlockPos.ZERO, 64, 64, 64, v -> v == 1, (v, p) -> true, 10), out);
        long distinct = s.seeked.size();
        assertTrue(distinct < 9 * 9 * 9 / 4, "seeked " + distinct + " sections");
        s.resetCounters();
        out.clear();
        SectionScan.withinManhattan(s, 0, 0, 0, 64, 64, 64, v -> v == 1, (v, p) -> p.getY() == 40, 3, out);
        assertEquals(bruteWithinManhattan(s, BlockPos.ZERO, 64, 64, 64, v -> v == 1, (v, p) -> p.getY() == 40, 3), out);
        assertTrue(s.seeked.size() < 9 * 9 * 9, "seeked " + s.seeked.size() + " sections");
    }

    @Test
    void betweenClosedStopsBeforeLaterZSlabs() {
        FakeSections s = new FakeSections(-8, 8, 99, 0);
        for (int sx = 0; sx < 4; sx++) for (int sz = 0; sz < 4; sz++) s.setUniform(sx, 1, sz, 1);
        List<BlockPos> out = new ArrayList<>();
        SectionScan.betweenClosed(s, 0, 0, 0, 63, 63, 63, v -> v == 1, (v, p) -> true, 5, out);
        assertEquals(bruteBetweenClosed(s, 0, 0, 0, 63, 63, 63, v -> v == 1, (v, p) -> true, 5), out);
        assertTrue(s.seeked.stream().allMatch(k -> BlockPos.getZ(k) == 0), "seeked " + s.seeked.size());
    }

    @Test
    void headCellsAreNotReadTwice() {
        FakeSections s = new FakeSections(-8, 8, 99, 0);
        int[] d = new int[4096];
        d[4095] = 1;
        s.setDense(1, 1, 1, d, Set.of());
        int[] calls = { 0 };
        List<BlockPos> out = new ArrayList<>();
        SectionScan.withinManhattan(s, 8, 8, 8, 30, 30, 30, v -> v == 1, (v, p) -> {
            calls[0]++;
            return true;
        }, 3, out);
        assertEquals(List.of(new BlockPos(31, 31, 31)), out);
        assertEquals(1, calls[0]);
        calls[0] = 0;
        out.clear();
        SectionScan.betweenClosed(s, 0, 0, 0, 40, 40, 40, v -> v == 1, (v, p) -> {
            calls[0]++;
            return true;
        }, 2, out);
        assertEquals(List.of(new BlockPos(31, 31, 31)), out);
        assertEquals(1, calls[0]);
    }

    @Test
    void emptyInputsCollectNothing() {
        FakeSections s = new FakeSections(-8, 8, 99, 1);
        List<BlockPos> out = new ArrayList<>(List.of(BlockPos.ZERO));
        assertEquals(0, SectionScan.betweenClosed(s, 0, 0, 0, 5, 5, 5, v -> true, (v, p) -> true, 0, out));
        assertEquals(0, SectionScan.betweenClosed(s, 0, 0, 0, 5, 5, 5, v -> true, (v, p) -> true, -3, out));
        assertEquals(0, SectionScan.betweenClosed(s, 6, 0, 0, 5, 5, 5, v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.betweenClosed(s, 0, 6, 0, 5, 5, 5, v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.betweenClosed(s, 0, 0, 6, 5, 5, 5, v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.withinManhattan(s, 0, 0, 0, 3, 3, 3, v -> true, (v, p) -> true, 0, out));
        assertEquals(0, SectionScan.withinManhattan(s, 0, 0, 0, -1, 3, 3, v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.withinManhattan(s, 0, 0, 0, 3, -1, 3, v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.withinManhattan(s, 0, 0, 0, 3, 3, -1, v -> true, (v, p) -> true, 10, out));
        assertEquals(List.of(BlockPos.ZERO), out);
        assertEquals(0, s.seeks);
        assertEquals(List.of(new BlockPos(2, 3, 4)), collect(s, 2, 3, 4, 0, 0, 0));
    }

    private static List<BlockPos> collect(FakeSections s, int x, int y, int z, int rx, int ry, int rz) {
        List<BlockPos> out = new ArrayList<>();
        SectionScan.withinManhattan(s, x, y, z, rx, ry, rz, v -> v == 1, (v, p) -> true, Integer.MAX_VALUE, out);
        return out;
    }

    @Test
    void appendsInOrderAndReturnsCount() {
        FakeSections s = new FakeSections(-8, 8, 99, 1);
        BlockPos marker = new BlockPos(1000, 1000, 1000);
        List<BlockPos> out = new ArrayList<>(List.of(marker));
        int n = SectionScan.withinManhattan(s, 0, 0, 0, 1, 1, 1, v -> v == 1, (v, p) -> true, Integer.MAX_VALUE, out);
        assertEquals(27, n);
        assertEquals(28, out.size());
        assertEquals(marker, out.get(0));
        assertEquals(bruteWithinManhattan(s, BlockPos.ZERO, 1, 1, 1, v -> v == 1, (v, p) -> true, Integer.MAX_VALUE), out.subList(1, 28));
    }

    @Test
    void cursorIsReusedAndResultsAreImmutable() {
        FakeSections s = new FakeSections(-8, 8, 99, 1);
        Set<BlockPos> cursors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<BlockPos> out = new ArrayList<>();
        SectionScan.betweenClosed(s, -20, -20, -20, 20, 20, 20, v -> v == 1, (v, p) -> cursors.add(p) || true, Integer.MAX_VALUE, out);
        SectionScan.withinManhattan(s, 0, 0, 0, 20, 20, 20, v -> v == 1, (v, p) -> cursors.add(p) || true, Integer.MAX_VALUE, out);
        assertEquals(2, cursors.size());
        for (BlockPos p : out) assertFalse(p instanceof BlockPos.MutableBlockPos);
        assertEquals(41 * 41 * 41 * 2, out.size());
    }

    @Test
    void bruteForcePathsMatchFastPaths() {
        Random random = new Random(14);
        for (int round = 0; round < 120; round++) {
            FakeSections s = randomWorld(random, 6);
            Set<Integer> targets = randomTargets(random, 6);
            SectionScan.CellTest<Integer> test = hashedTest(targets);
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            int x0 = random.nextInt(60) - 40, y0 = random.nextInt(60) - 40, z0 = random.nextInt(60) - 40;
            int x1 = x0 + random.nextInt(30), y1 = y0 + random.nextInt(30), z1 = z0 + random.nextInt(30);
            List<BlockPos> a = new ArrayList<>(), b = new ArrayList<>();
            SectionScan.betweenClosed(s, x0, y0, z0, x1, y1, z1, targets::contains, test, limit, a);
            SectionScan.betweenClosedBrute(s, x0, y0, z0, x1, y1, z1, targets::contains, test, limit, b);
            assertEquals(a, b);
            a.clear();
            b.clear();
            int rx = random.nextInt(15), ry = random.nextInt(15), rz = random.nextInt(15);
            SectionScan.withinManhattan(s, x0, y0, z0, rx, ry, rz, targets::contains, test, limit, a);
            SectionScan.withinManhattanBrute(s, x0, y0, z0, rx, ry, rz, targets::contains, test, limit, b);
            assertEquals(a, b);
        }
    }

    private static BlockRange randomRange(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> BlockRange.sphereSq(random.nextInt(400));
            case 1 -> BlockRange.sphere(random.nextInt(14));
            default -> BlockRange.box(random.nextInt(14), random.nextInt(20), random.nextInt(14));
        };
    }

    @Test
    void inRangeMatchesBoundingBoxScanFilteredByRange() {
        Random random = new Random(15);
        for (int round = 0; round < 300; round++) {
            FakeSections s = randomWorld(random, 6);
            Set<Integer> targets = randomTargets(random, 6);
            BlockPos c = new BlockPos(random.nextInt(80) - 45, random.nextInt(90) - 45, random.nextInt(80) - 45);
            BlockRange range = randomRange(random);
            SectionScan.CellTest<Integer> inner = hashedTest(targets);
            SectionScan.CellTest<Integer> test = (v, p) -> {
                assertTrue(range.contains(p.getX() - c.getX(), p.getY() - c.getY(), p.getZ() - c.getZ()), "tested a cell outside the range");
                return inner.test(v, p);
            };
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            List<BlockPos> expected = bruteBetweenClosed(s, c.getX() - range.radiusX(), c.getY() - range.radiusY(), c.getZ() - range.radiusZ(),
                    c.getX() + range.radiusX(), c.getY() + range.radiusY(), c.getZ() + range.radiusZ(), targets::contains,
                    (v, p) -> range.contains(p.getX() - c.getX(), p.getY() - c.getY(), p.getZ() - c.getZ()) && inner.test(v, p), limit);
            List<BlockPos> actual = new ArrayList<>();
            int n = SectionScan.inRange(s, c.getX(), c.getY(), c.getZ(), range, targets::contains, test, limit, actual);
            assertEquals(expected, actual, "round " + round + " " + range);
            assertEquals(expected.size(), n);
        }
    }

    @Test
    void inRangeSkipsSectionsOutsideTheRange() {
        FakeSections s = new FakeSections(-8, 8, 99, 1);
        BlockRange sphere = BlockRange.sphere(40);
        List<BlockPos> out = new ArrayList<>();
        SectionScan.inRange(s, 8, 8, 8, sphere, v -> v == 1, (v, p) -> p.getY() == 48, Integer.MAX_VALUE, out);
        assertEquals(List.of(new BlockPos(8, 48, 8)), out);
        assertTrue(s.seeks < 6 * 6 * 6, "seeked " + s.seeks);
        for (long k : s.seeked) {
            int sx = BlockPos.getX(k), sy = BlockPos.getY(k), sz = BlockPos.getZ(k);
            long dx = Math.max(0, Math.max((sx << 4) - 8L, 8L - ((sx << 4) + 15)));
            long dy = Math.max(0, Math.max((sy << 4) - 8L, 8L - ((sy << 4) + 15)));
            long dz = Math.max(0, Math.max((sz << 4) - 8L, 8L - ((sz << 4) + 15)));
            assertTrue(sphere.reaches(dx, dy, dz));
        }
        out.clear();
        assertEquals(0, SectionScan.inRange(s, 0, 0, 0, BlockRange.sphere(-1), v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.inRange(s, 0, 0, 0, BlockRange.box(-1), v -> true, (v, p) -> true, 10, out));
        assertEquals(0, SectionScan.inRange(s, 0, 0, 0, BlockRange.sphere(3), v -> true, (v, p) -> true, 0, out));
        assertEquals(List.of(BlockPos.ZERO), collectRange(s, BlockRange.sphere(0)));
        assertEquals(7, collectRange(s, BlockRange.sphere(1)).size());
        assertEquals(19, collectRange(s, BlockRange.sphereSq(2)).size());
    }

    private static List<BlockPos> collectRange(FakeSections s, BlockRange range) {
        List<BlockPos> out = new ArrayList<>();
        SectionScan.inRange(s, 0, 0, 0, range, v -> v == 1, (v, p) -> true, Integer.MAX_VALUE, out);
        return out;
    }

    @Test
    void inRangeBruteForceMatchesFastPath() {
        Random random = new Random(16);
        for (int round = 0; round < 120; round++) {
            FakeSections s = randomWorld(random, 6);
            Set<Integer> targets = randomTargets(random, 6);
            SectionScan.CellTest<Integer> test = hashedTest(targets);
            BlockRange range = randomRange(random);
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            int x = random.nextInt(60) - 30, y = random.nextInt(60) - 30, z = random.nextInt(60) - 30;
            List<BlockPos> a = new ArrayList<>(), b = new ArrayList<>();
            SectionScan.inRange(s, x, y, z, range, targets::contains, test, limit, a);
            SectionScan.inRangeBrute(s, x, y, z, range, targets::contains, test, limit, b);
            assertEquals(a, b);
        }
    }

    @Test
    void hugeRangesFallBackToCellIteration() {
        FakeSections s = new FakeSections(-1 << 25, 1 << 25, 99, 0);
        int[] d = new int[4096];
        d[0] = 1;
        s.setDense(0, 0, 0, d, Set.of());
        List<BlockPos> out = new ArrayList<>();
        assertEquals(1, SectionScan.withinManhattan(s, 0, 0, 0, 1 << 20, 1 << 20, 1 << 20, v -> v == 1, (v, p) -> true, 1, out));
        assertEquals(List.of(BlockPos.ZERO), out);
        out.clear();
        FakeSections corner = new FakeSections(-1 << 27, 1 << 27, 99, 0);
        int[] e = new int[4096];
        e[0] = 1;
        corner.setDense(-(1 << 26), -(1 << 26), -(1 << 26), e, Set.of());
        int lo = -(1 << 30);
        assertEquals(1, SectionScan.betweenClosed(corner, lo, lo, lo, 1 << 30, 1 << 30, 1 << 30, v -> v == 1, (v, p) -> true, 1, out));
        assertEquals(List.of(new BlockPos(lo, lo, lo)), out);
    }
}
