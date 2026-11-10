package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickBatchTest {

    private static TickScheduler started() {
        TickScheduler s = new TickScheduler(true, (error, owner) -> {
            throw new AssertionError(error);
        });
        s.runRound();
        return s;
    }

    @Test
    void duplicatesAreFlushedOnceInFirstAddOrder() {
        TickScheduler s = started();
        List<String> flushed = new ArrayList<>();
        TickBatch.Queue<String> q = new TickBatch.Queue<>(flushed::add, s);
        String a = "a", b = "b", c = "c";
        q.add(b);
        q.add(a);
        q.add(b);
        q.add(c);
        q.add(a);
        assertTrue(flushed.isEmpty());
        s.runRound();
        assertEquals(List.of(b, a, c), flushed);
        s.runRound();
        assertEquals(3, flushed.size());
        assertEquals(0, s.linkedCount());
    }

    @Test
    void itemsAddedDuringAFlushGoToTheNextBatch() {
        TickScheduler s = started();
        List<String> flushed = new ArrayList<>();
        TickBatch.Queue<String>[] holder = new TickBatch.Queue[1];
        holder[0] = new TickBatch.Queue<>(item -> {
            flushed.add(item + "@" + s.round());
            if (item.equals("x")) holder[0].add("y");
        }, s);
        holder[0].add("x");
        long r = s.round();
        s.runRound();
        s.runRound();
        s.runRound();
        assertEquals(List.of("x@" + (r + 1), "y@" + (r + 2)), flushed);
    }

    @Test
    void removedItemsAreNotFlushed() {
        TickScheduler s = started();
        List<String> flushed = new ArrayList<>();
        TickBatch.Queue<String> q = new TickBatch.Queue<>(flushed::add, s);
        q.add("a");
        q.add("b");
        assertTrue(q.pending.remove("a"));
        assertFalse(q.pending.contains("a"));
        s.runRound();
        assertEquals(List.of("b"), flushed);
    }

    @Test
    void idleBatchSchedulesNothing() {
        TickScheduler s = started();
        new TickBatch.Queue<String>(item -> {}, s);
        assertEquals(0, s.linkedCount());
        assertTrue(s.advanceIfIdle());
    }
}
