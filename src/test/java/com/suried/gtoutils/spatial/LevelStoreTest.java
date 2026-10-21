package com.suried.gtoutils.spatial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LevelStoreTest {

    @Test
    void createsOnePerLevelLazily() {
        AtomicInteger made = new AtomicInteger();
        LevelStore<Object> store = LevelStore.create(() -> {
            made.incrementAndGet();
            return new Object();
        });
        FakeLevel level = new FakeLevel();
        assertNull(store.forHolderIfPresent(level));
        assertEquals(0, made.get());
        Object first = store.forHolder(level);
        assertSame(first, store.forHolder(level));
        assertSame(first, store.forHolderIfPresent(level));
        assertEquals(1, made.get());
    }

    @Test
    void levelsAndStoresAreIndependent() {
        LevelStore<List<String>> a = LevelStore.create(ArrayList::new);
        LevelStore<List<String>> b = LevelStore.create(ArrayList::new);
        FakeLevel server = new FakeLevel();
        FakeLevel client = new FakeLevel();
        a.forHolder(server).add("x");
        assertTrue(a.forHolder(client).isEmpty());
        assertTrue(b.forHolder(server).isEmpty());
        assertNotSame(a.forHolder(server), a.forHolder(client));
    }

    @Test
    void storesCreatedLaterGrowTheSlotArray() {
        LevelStore<Object> early = LevelStore.create(Object::new);
        FakeLevel level = new FakeLevel();
        Object kept = early.forHolder(level);
        LevelStore<Object> late = LevelStore.create(Object::new);
        assertNull(late.forHolderIfPresent(level));
        assertNotNull(late.forHolder(level));
        assertSame(kept, early.forHolder(level));
    }

    @Test
    void releaseDropsOnlyThatStore() {
        LevelStore<Object> a = LevelStore.create(Object::new);
        LevelStore<Object> b = LevelStore.create(Object::new);
        FakeLevel level = new FakeLevel();
        Object old = a.forHolder(level);
        Object other = b.forHolder(level);
        a.releaseHolder(level);
        assertNull(a.forHolderIfPresent(level));
        assertSame(other, b.forHolderIfPresent(level));
        assertNotSame(old, a.forHolder(level));
        FakeLevel untouched = new FakeLevel();
        a.releaseHolder(untouched);
        assertNull(untouched.gtoutils$slots());
    }

    @Test
    void releasingAllSlotsDropsEveryStore() {
        LevelStore<Object> a = LevelStore.create(Object::new);
        LevelStore<Object> b = LevelStore.create(Object::new);
        FakeLevel level = new FakeLevel();
        a.forHolder(level);
        b.forHolder(level);
        level.gtoutils$setSlots(null);
        assertNull(a.forHolderIfPresent(level));
        assertNull(b.forHolderIfPresent(level));
    }

    @Test
    void factoryMustNotReturnNull() {
        LevelStore<Object> store = LevelStore.create(() -> null);
        assertThrows(NullPointerException.class, () -> store.forHolder(new FakeLevel()));
        assertThrows(NullPointerException.class, () -> LevelStore.create(null));
    }
}
