package com.suried.gtoutils.level;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
    void setReplacesAndClearsWithoutCallingRelease() {
        List<Object> released = new ArrayList<>();
        LevelStore<Object> store = LevelStore.create(Object::new, released::add);
        FakeLevel level = new FakeLevel();
        Object put = new Object();
        store.setHolder(level, put);
        assertSame(put, store.forHolderIfPresent(level));
        store.setHolder(level, null);
        assertNull(store.forHolderIfPresent(level));
        assertTrue(released.isEmpty());
    }

    @Test
    void releaseCallsTheCallbackOnce() {
        List<Object> released = new ArrayList<>();
        LevelStore<Object> store = LevelStore.create(Object::new, released::add);
        FakeLevel level = new FakeLevel();
        Object value = store.forHolder(level);
        store.releaseHolder(level);
        store.releaseHolder(level);
        assertEquals(List.of(value), released);
    }

    @Test
    void releaseAllCallsEveryCallbackAndMarksTheLevel() {
        List<Object> released = new ArrayList<>();
        LevelStore<Object> a = LevelStore.create(Object::new, released::add);
        LevelStore<Object> b = LevelStore.create(Object::new);
        LevelStore<Object> c = LevelStore.create(() -> {
            throw new AssertionError("never created");
        }, released::add);
        LevelStore<Object> failing = LevelStore.create(Object::new, v -> {
            throw new IllegalStateException("callback failure is logged");
        });
        FakeLevel level = new FakeLevel();
        Object va = a.forHolder(level);
        b.forHolder(level);
        failing.forHolder(level);
        assertFalse(level.gtoutils$released());
        LevelStore.releaseAllHolder(level);
        assertEquals(List.of(va), released);
        assertTrue(level.gtoutils$released());
        assertNull(a.forHolderIfPresent(level));
        assertNull(c.forHolderIfPresent(level));
    }

    @Test
    void concurrentFirstAccessCreatesOneValue() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            AtomicInteger made = new AtomicInteger();
            LevelStore<Object> store = LevelStore.create(() -> {
                made.incrementAndGet();
                return new Object();
            });
            List<LevelStore<Object>> others = new ArrayList<>();
            for (int i = 0; i < 4; i++) others.add(LevelStore.create(Object::new));
            FakeLevel level = new FakeLevel();
            int threads = 8;
            Object[] seen = new Object[threads];
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int idx = t;
                Thread w = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    others.get(idx % others.size()).forHolder(level);
                    seen[idx] = store.forHolder(level);
                });
                workers.add(w);
                w.start();
            }
            go.countDown();
            for (Thread w : workers) w.join();
            assertEquals(1, made.get());
            for (Object o : seen) assertSame(seen[0], o);
            for (LevelStore<Object> other : others) assertNotNull(other.forHolderIfPresent(level));
        }
    }

    @Test
    void factoryMustNotReturnNull() {
        LevelStore<Object> store = LevelStore.create(() -> null);
        assertThrows(NullPointerException.class, () -> store.forHolder(new FakeLevel()));
        assertThrows(NullPointerException.class, () -> LevelStore.create(null));
    }
}
