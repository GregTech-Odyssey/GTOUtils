package com.suried.gtoutils.level;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecentChunksTest {

    private record Chunk(long key) {}

    @Test
    void keepsTheLastFourEntries() {
        RecentChunks<Chunk> cache = new RecentChunks<>();
        for (long k = 0; k < 6; k++) cache.put(k, new Chunk(k));
        assertNull(cache.get(0));
        assertNull(cache.get(1));
        for (long k = 2; k < 6; k++) assertEquals(k, cache.get(k).key());
        assertNull(cache.get(99));
    }

    @Test
    void clearDropsEverything() {
        RecentChunks<Chunk> cache = new RecentChunks<>();
        cache.put(7, new Chunk(7));
        cache.clear();
        assertNull(cache.get(7));
    }

    @Test
    void keyZeroIsNotAFalseHitOnAnEmptyCache() {
        RecentChunks<Chunk> cache = new RecentChunks<>();
        assertNull(cache.get(0));
    }

    @Test
    void concurrentReadersNeverSeeAMismatchedPair() throws Exception {
        RecentChunks<Chunk> cache = new RecentChunks<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int seed = t;
            Thread w = new Thread(() -> {
                Random rnd = new Random(seed);
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    long key = rnd.nextInt(16);
                    if (rnd.nextBoolean()) {
                        cache.put(key, new Chunk(key));
                    } else {
                        Chunk c = cache.get(key);
                        if (c != null && c.key() != key) failure.set("key " + key + " got " + c.key());
                    }
                    if (rnd.nextInt(1000) == 0) cache.clear();
                }
            });
            threads.add(w);
            w.start();
        }
        go.countDown();
        Thread.sleep(300);
        stop.set(true);
        for (Thread w : threads) w.join();
        assertNull(failure.get());
    }
}
