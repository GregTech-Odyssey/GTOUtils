package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelBlockIndexTest {

    @Test
    void createsOneIndexPerLevelLazily() {
        LevelBlockIndex<String> type = LevelBlockIndex.create();
        FakeLevel level = new FakeLevel();
        assertNull(type.forHolderIfPresent(level));
        BlockIndex<String> first = type.forHolder(level);
        assertSame(first, type.forHolder(level));
        assertSame(first, type.forHolderIfPresent(level));
    }

    @Test
    void levelsAreIndependent() {
        LevelBlockIndex<String> type = LevelBlockIndex.create();
        FakeLevel server = new FakeLevel();
        FakeLevel client = new FakeLevel();
        type.forHolder(server).put(0, 0, 0, "server");
        assertNull(type.forHolder(client).get(0, 0, 0));
        assertNotSame(type.forHolder(server), type.forHolder(client));
    }

    @Test
    void indexTypesAreIndependent() {
        LevelBlockIndex<String> jars = LevelBlockIndex.create();
        LevelBlockIndex<String> links = LevelBlockIndex.create();
        FakeLevel level = new FakeLevel();
        jars.forHolder(level).put(1, 1, 1, "jar");
        assertNull(links.forHolder(level).get(1, 1, 1));
    }

    @Test
    void releaseDropsTheLevelIndex() {
        LevelBlockIndex<String> a = LevelBlockIndex.create();
        LevelBlockIndex<Integer> b = LevelBlockIndex.create();
        FakeLevel level = new FakeLevel();
        a.forHolder(level).put(0, 0, 0, "x");
        b.forHolder(level).put(0, 0, 0, 1);
        a.releaseHolder(level);
        assertNull(a.forHolderIfPresent(level));
        assertEquals(1, b.forHolderIfPresent(level).get(0, 0, 0));
        assertTrue(a.forHolder(level).isEmpty());
    }

    @Test
    void releasingAllSlotsDropsEveryIndexType() {
        LevelBlockIndex<String> a = LevelBlockIndex.create();
        LevelBlockIndex<Integer> b = LevelBlockIndex.create();
        FakeLevel unloaded = new FakeLevel();
        FakeLevel kept = new FakeLevel();
        a.forHolder(unloaded).put(0, 0, 0, "x");
        b.forHolder(unloaded).put(0, 0, 0, 1);
        a.forHolder(kept).put(0, 0, 0, "y");
        unloaded.gtoutils$setSlots(null);
        assertNull(a.forHolderIfPresent(unloaded));
        assertNull(b.forHolderIfPresent(unloaded));
        assertEquals("y", a.forHolderIfPresent(kept).get(0, 0, 0));
    }
}
