package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelBlockIndexTest {

    @Test
    void createsOneIndexPerLevelLazily() {
        LevelBlockIndex<String> type = LevelBlockIndex.create();
        Object level = new Object();
        assertNull(type.forKeyIfPresent(level));
        BlockIndex<String> first = type.forKey(level);
        assertSame(first, type.forKey(level));
        assertSame(first, type.forKeyIfPresent(level));
        assertEquals(1, type.levelCount());
    }

    @Test
    void levelsAreIndependent() {
        LevelBlockIndex<String> type = LevelBlockIndex.create();
        Object server = new Object();
        Object client = new Object();
        type.forKey(server).put(0, 0, 0, "server");
        assertNull(type.forKey(client).get(0, 0, 0));
        assertNotSame(type.forKey(server), type.forKey(client));
        assertEquals(2, type.levelCount());
    }

    @Test
    void indexTypesAreIndependent() {
        LevelBlockIndex<String> jars = LevelBlockIndex.create();
        LevelBlockIndex<String> links = LevelBlockIndex.create();
        Object level = new Object();
        jars.forKey(level).put(1, 1, 1, "jar");
        assertNull(links.forKey(level).get(1, 1, 1));
    }

    @Test
    void releaseAllDropsTheLevelFromEveryIndexType() {
        LevelBlockIndex<String> a = LevelBlockIndex.create();
        LevelBlockIndex<Integer> b = LevelBlockIndex.create();
        Object unloaded = new Object();
        Object kept = new Object();
        a.forKey(unloaded).put(0, 0, 0, "x");
        b.forKey(unloaded).put(0, 0, 0, 1);
        a.forKey(kept).put(0, 0, 0, "y");
        LevelBlockIndex.releaseKey(unloaded);
        assertNull(a.forKeyIfPresent(unloaded));
        assertNull(b.forKeyIfPresent(unloaded));
        assertEquals("y", a.forKeyIfPresent(kept).get(0, 0, 0));
        assertTrue(a.forKey(unloaded).isEmpty());
    }
}
