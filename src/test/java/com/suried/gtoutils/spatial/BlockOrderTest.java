package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class BlockOrderTest {

    private static List<BlockPos> sample(Random random, BlockPos center, int rx, int ry, int rz, double density) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = -rx; x <= rx; x++) {
            for (int y = -ry; y <= ry; y++) {
                for (int z = -rz; z <= rz; z++) {
                    if (random.nextDouble() < density) out.add(center.offset(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void betweenClosedMatchesVanillaIteration() {
        Random random = new Random(1);
        for (int round = 0; round < 50; round++) {
            BlockPos a = new BlockPos(random.nextInt(200) - 100, random.nextInt(200) - 100, random.nextInt(200) - 100);
            BlockPos b = a.offset(random.nextInt(13) - 6, random.nextInt(9) - 4, random.nextInt(13) - 6);
            Set<BlockPos> chosen = new HashSet<>();
            List<BlockPos> expected = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(a, b)) {
                if (random.nextInt(3) == 0) {
                    chosen.add(p.immutable());
                    expected.add(p.immutable());
                }
            }
            List<BlockPos> shuffled = new ArrayList<>(chosen);
            Collections.shuffle(shuffled, random);
            BlockOrder.sortBetweenClosed(shuffled, Function.identity());
            assertEquals(expected, shuffled);
        }
    }

    @Test
    void withinManhattanMatchesVanillaIterationOnBothPaths() {
        Random random = new Random(2);
        for (int round = 0; round < 60; round++) {
            BlockPos center = new BlockPos(random.nextInt(2000) - 1000, random.nextInt(300) - 64, random.nextInt(2000) - 1000);
            int rx = random.nextInt(11), ry = random.nextInt(11), rz = random.nextInt(11);
            Set<BlockPos> chosen = new HashSet<>(sample(random, center, rx, ry, rz, 0.3));
            List<BlockPos> expected = new ArrayList<>();
            for (BlockPos p : BlockPos.withinManhattan(center, rx, ry, rz)) {
                if (chosen.contains(p)) expected.add(p.immutable());
            }
            assertEquals(chosen.size(), expected.size());
            List<BlockPos> packed = new ArrayList<>(chosen);
            Collections.shuffle(packed, random);
            BlockOrder.sortWithinManhattan(packed, center, Function.identity());
            assertEquals(expected, packed);
            List<BlockPos> compared = new ArrayList<>(chosen);
            Collections.shuffle(compared, random);
            compared.sort(BlockOrder.withinManhattan(center, Function.identity()));
            assertEquals(expected, compared);
        }
    }

    @Test
    void betweenClosedPackedAndComparatorPathsAgree() {
        Random random = new Random(3);
        for (int round = 0; round < 40; round++) {
            boolean wide = round % 2 == 1;
            List<BlockPos> list = new ArrayList<>();
            int n = 1 + random.nextInt(300);
            for (int i = 0; i < n; i++) {
                int spread = wide ? 30_000_000 : 64;
                list.add(new BlockPos(random.nextInt(2 * spread) - spread, random.nextInt(wide ? 4000 : 20) - (wide ? 2000 : 10), random.nextInt(2 * spread) - spread));
                if (random.nextInt(10) == 0) list.add(list.get(random.nextInt(list.size())));
            }
            List<BlockPos> expected = new ArrayList<>(list);
            expected.sort(BlockOrder.betweenClosed(Function.identity()));
            Collections.shuffle(list, random);
            List<BlockPos> sorted = new ArrayList<>(list);
            sorted.sort(BlockOrder.betweenClosed(Function.identity()));
            BlockOrder.sortBetweenClosed(list, Function.identity());
            assertEquals(expected, list);
            assertEquals(sorted, list);
        }
    }

    @Test
    void farEntriesFallBackToComparator() {
        BlockPos center = new BlockPos(0, 64, 0);
        List<BlockPos> list = new ArrayList<>(List.of(new BlockPos(100, 64, 0), new BlockPos(0, 64, 1), new BlockPos(0, 64, -1), new BlockPos(-64, 64, 0)));
        BlockOrder.sortWithinManhattan(list, center, Function.identity());
        assertEquals(List.of(new BlockPos(0, 64, 1), new BlockPos(0, 64, -1), new BlockPos(-64, 64, 0), new BlockPos(100, 64, 0)), list);
    }

    @Test
    void equalPositionsKeepTheirOrder() {
        record Tagged(BlockPos pos, int tag) {}
        BlockPos p = new BlockPos(1, 2, 3);
        List<Tagged> list = new ArrayList<>(List.of(new Tagged(p, 0), new Tagged(BlockPos.ZERO, 9), new Tagged(p, 1), new Tagged(p, 2)));
        BlockOrder.sortWithinManhattan(list, BlockPos.ZERO, Tagged::pos);
        assertEquals(List.of(9, 0, 1, 2), list.stream().map(Tagged::tag).toList());
        List<Tagged> closed = new ArrayList<>(List.of(new Tagged(p, 0), new Tagged(BlockPos.ZERO, 9), new Tagged(p, 1)));
        BlockOrder.sortBetweenClosed(closed, Tagged::pos);
        assertEquals(List.of(9, 0, 1), closed.stream().map(Tagged::tag).toList());
    }

    @Test
    void emptyAndSingletonListsAreUntouched() {
        List<BlockPos> empty = new ArrayList<>();
        BlockOrder.sortWithinManhattan(empty, BlockPos.ZERO, Function.identity());
        BlockOrder.sortBetweenClosed(empty, Function.identity());
        assertTrue(empty.isEmpty());
        List<BlockPos> one = new ArrayList<>(List.of(new BlockPos(5, 5, 5)));
        BlockOrder.sortWithinManhattan(one, BlockPos.ZERO, Function.identity());
        assertEquals(List.of(new BlockPos(5, 5, 5)), one);
    }
}
