package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockRangeTest {

    @Test
    void sphereContainsUpToRadiusInclusive() {
        BlockRange r = BlockRange.sphere(3);
        assertEquals(BlockRange.Shape.SPHERE, r.shape());
        assertTrue(r.contains(0, 0, 0));
        assertTrue(r.contains(3, 0, 0));
        assertTrue(r.contains(0, -3, 0));
        assertTrue(r.contains(2, 2, 1));
        assertFalse(r.contains(2, 2, 2));
        assertFalse(r.contains(4, 0, 0));
        assertFalse(r.contains(3, 1, 0));
    }

    @Test
    void sphereSqExpressesStrictRadius() {
        BlockRange strict = BlockRange.sphereSq(5L * 5 - 1);
        assertFalse(strict.contains(5, 0, 0));
        assertFalse(strict.contains(3, 4, 0));
        assertTrue(strict.contains(4, 2, 0));
        assertEquals(4, strict.radiusX());
        assertEquals(24, strict.radiusSq());
    }

    @Test
    void sphereSqComputesBoundingRadius() {
        assertEquals(0, BlockRange.sphereSq(0).radiusX());
        assertEquals(1, BlockRange.sphereSq(3).radiusX());
        assertEquals(2, BlockRange.sphereSq(4).radiusX());
        assertEquals(10, BlockRange.sphereSq(120).radiusX());
        assertEquals(11, BlockRange.sphereSq(121).radiusX());
        assertEquals(46340, BlockRange.sphereSq((long) Integer.MAX_VALUE).radiusX());
    }

    @Test
    void cubeContainsWholeCube() {
        BlockRange r = BlockRange.box(2);
        assertEquals(BlockRange.Shape.BOX, r.shape());
        assertTrue(r.contains(2, 2, 2));
        assertTrue(r.contains(-2, 2, -2));
        assertFalse(r.contains(3, 0, 0));
        assertFalse(r.contains(0, 0, -3));
    }

    @Test
    void boxSupportsPerAxisRadius() {
        BlockRange r = BlockRange.box(5, 2, 1);
        assertEquals(5, r.radiusX());
        assertEquals(2, r.radiusY());
        assertEquals(1, r.radiusZ());
        assertTrue(r.contains(5, -2, 1));
        assertFalse(r.contains(5, 3, 0));
        assertFalse(r.contains(0, 0, 2));
        assertFalse(r.contains(6, 0, 0));
    }

    @Test
    void negativeRadiusIsEmpty() {
        for (BlockRange r : new BlockRange[] { BlockRange.sphere(-1), BlockRange.sphereSq(-1), BlockRange.box(-1), BlockRange.box(3, -1, 3) }) {
            assertTrue(r.isEmpty(), r.toString());
            assertFalse(r.contains(0, 0, 0), r.toString());
            assertFalse(r.reaches(0, 0, 0), r.toString());
        }
        assertFalse(BlockRange.sphere(0).isEmpty());
        assertTrue(BlockRange.sphere(0).contains(0, 0, 0));
        assertFalse(BlockRange.sphere(0).contains(1, 0, 0));
    }

    @Test
    void reachesMatchesContainsOnAbsoluteDeltas() {
        BlockRange[] ranges = { BlockRange.sphere(4), BlockRange.sphereSq(17), BlockRange.box(3), BlockRange.box(4, 1, 2) };
        for (BlockRange r : ranges) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dy = -6; dy <= 6; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        assertEquals(r.contains(dx, dy, dz), r.reaches(Math.abs(dx), Math.abs(dy), Math.abs(dz)), r + " at " + dx + "," + dy + "," + dz);
                    }
                }
            }
        }
    }

    @Test
    void containsHandlesExtremeDeltasWithoutOverflow() {
        assertFalse(BlockRange.sphere(10).contains(Integer.MIN_VALUE, 0, 0));
        assertFalse(BlockRange.box(10).contains(0, Integer.MAX_VALUE, 0));
        assertTrue(BlockRange.box(Integer.MAX_VALUE).contains(Integer.MAX_VALUE, -Integer.MAX_VALUE, 0));
    }

    @Test
    void equalsAndHashCodeFollowShapeAndRadius() {
        assertEquals(BlockRange.sphere(3), BlockRange.sphereSq(9));
        assertEquals(BlockRange.sphere(3).hashCode(), BlockRange.sphereSq(9).hashCode());
        assertEquals(BlockRange.box(2), BlockRange.box(2, 2, 2));
        assertNotEquals(BlockRange.box(3), BlockRange.sphere(3));
        assertNotEquals(BlockRange.box(3, 2, 3), BlockRange.box(3));
    }

    @Test
    void smallRadiiReturnSharedInstances() {
        assertSame(BlockRange.sphere(5), BlockRange.sphere(5));
        assertSame(BlockRange.box(3), BlockRange.box(3));
        assertSame(BlockRange.box(3), BlockRange.box(3, 3, 3));
        assertSame(BlockRange.sphere(-4), BlockRange.sphereSq(-1));
        assertEquals(BlockRange.sphere(64), BlockRange.sphereSq(64L * 64));
        assertEquals(BlockRange.sphere(65), BlockRange.sphereSq(65L * 65));
        assertNotSame(BlockRange.box(1, 2, 3), BlockRange.box(1, 2, 3));
        assertEquals(BlockRange.box(1, 2, 3), BlockRange.box(1, 2, 3));
    }
}
