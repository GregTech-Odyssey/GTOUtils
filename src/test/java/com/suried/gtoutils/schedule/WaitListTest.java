package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WaitListTest {

    private static List<WaitList.Waiter<Integer>> waiters(int n) {
        List<WaitList.Waiter<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new WaitList.Waiter<>(i));
        return out;
    }

    @Test
    void awaitAndCancelKeepCountsAndAreIdempotent() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(3);
        assertTrue(list.isEmpty());
        assertFalse(w.get(0).waiting());
        w.get(0).cancel();
        list.await(w.get(0));
        list.await(w.get(0));
        list.await(w.get(1));
        assertEquals(2, list.size());
        assertTrue(w.get(0).waiting());
        assertSame(list, w.get(0).list());
        w.get(0).cancel();
        w.get(0).cancel();
        assertEquals(1, list.size());
        assertFalse(w.get(0).waiting());
        assertNull(w.get(0).list());
        w.get(1).cancel();
        assertTrue(list.isEmpty());
    }

    @Test
    void fireRunsLatestFirstAndEmptiesTheList() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(5);
        for (var waiter : w) list.await(waiter);
        List<Integer> fired = new ArrayList<>();
        list.fire(fired::add);
        assertEquals(List.of(4, 3, 2, 1, 0), fired);
        assertTrue(list.isEmpty());
        for (var waiter : w) assertFalse(waiter.waiting());
        list.fire(fired::add);
        assertEquals(5, fired.size());
    }

    @Test
    void waiterIsReusableAfterFireAndAfterCancel() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(2);
        List<Integer> fired = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            list.await(w.get(0));
            list.await(w.get(1));
            w.get(1).cancel();
            list.await(w.get(1));
            list.fire(fired::add);
        }
        assertEquals(List.of(1, 0, 1, 0, 1, 0, 1, 0), fired);
    }

    @Test
    void awaitMovesAWaiterBetweenLists() {
        WaitList<Integer> a = new WaitList<>(), b = new WaitList<>();
        var w = waiters(3);
        for (var waiter : w) a.await(waiter);
        b.await(w.get(1));
        assertEquals(2, a.size());
        assertEquals(1, b.size());
        assertSame(b, w.get(1).list());
        List<Integer> fromA = new ArrayList<>(), fromB = new ArrayList<>();
        a.fire(fromA::add);
        b.fire(fromB::add);
        assertEquals(Set.of(0, 2), new HashSet<>(fromA));
        assertEquals(List.of(1), fromB);
    }

    @Test
    void waitersAddedDuringAFireStayForTheNextOne() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(6);
        for (int i = 0; i < 3; i++) list.await(w.get(i));
        List<Integer> fired = new ArrayList<>();
        list.fire(id -> {
            fired.add(id);
            list.await(w.get(id + 3));
            list.await(w.get(id));
        });
        assertEquals(List.of(2, 1, 0), fired);
        assertEquals(6, list.size());
        fired.clear();
        list.fire(fired::add);
        assertEquals(6, fired.size());
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), new HashSet<>(fired));
        assertTrue(list.isEmpty());
    }

    @Test
    void cancellingAPendingWaiterDuringAFireSkipsIt() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(6);
        for (var waiter : w) list.await(waiter);
        List<Integer> fired = new ArrayList<>();
        list.fire(id -> {
            fired.add(id);
            if (id == 5) {
                w.get(1).cancel();
                w.get(3).cancel();
            }
        });
        assertEquals(Set.of(5, 4, 2, 0), new HashSet<>(fired));
        assertEquals(4, fired.size());
        assertTrue(list.isEmpty());
        assertFalse(w.get(1).waiting());
    }

    @Test
    void cancellingANewcomerDuringAFireLeavesTheRestIntact() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(8);
        for (int i = 0; i < 4; i++) list.await(w.get(i));
        List<Integer> fired = new ArrayList<>();
        list.fire(id -> {
            fired.add(id);
            list.await(w.get(id + 4));
            if (id == 1) {
                w.get(7).cancel();
                w.get(0).cancel();
            }
        });
        assertEquals(List.of(3, 2, 1), fired);
        assertEquals(2, list.size());
        assertTrue(w.get(6).waiting());
        assertTrue(w.get(5).waiting());
        assertFalse(w.get(7).waiting());
        assertFalse(w.get(4).waiting());
    }

    @Test
    void nestedFireRunsEverythingPresentAtThatMoment() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(6);
        for (int i = 0; i < 4; i++) list.await(w.get(i));
        List<String> fired = new ArrayList<>();
        list.fire(id -> {
            fired.add("outer" + id);
            if (id == 3) {
                list.await(w.get(4));
                list.fire(inner -> {
                    fired.add("inner" + inner);
                    if (inner == 0) list.await(w.get(5));
                });
            }
        });
        assertEquals("outer3", fired.get(0));
        assertEquals(Set.of("outer3", "inner4", "inner2", "inner1", "inner0"), new HashSet<>(fired));
        assertEquals(5, fired.size());
        assertEquals(1, list.size());
        assertTrue(w.get(5).waiting());
    }

    @Test
    void exceptionInActionLeavesPendingWaitersRegistered() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(4);
        for (var waiter : w) list.await(waiter);
        List<Integer> fired = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> list.fire(id -> {
            fired.add(id);
            if (id == 2) throw new IllegalStateException("boom");
        }));
        assertEquals(List.of(3, 2), fired);
        assertEquals(2, list.size());
        assertFalse(w.get(2).waiting());
        assertTrue(w.get(1).waiting());
        list.fire(fired::add);
        assertEquals(List.of(3, 2, 1, 0), fired);
    }

    @Test
    void trimOnlyDropsTheArrayWhenEmpty() {
        WaitList<Integer> list = new WaitList<>();
        var w = waiters(100);
        for (var waiter : w) list.await(waiter);
        list.trim();
        assertEquals(100, list.size());
        list.fire(id -> {});
        list.trim();
        assertTrue(list.isEmpty());
        list.await(w.get(7));
        List<Integer> fired = new ArrayList<>();
        list.fire(fired::add);
        assertEquals(List.of(7), fired);
    }

    @Test
    void wrapAroundOfTheFireCounterKeepsSemantics() throws Exception {
        WaitList<Integer> list = new WaitList<>();
        var field = WaitList.class.getDeclaredField("fireId");
        field.setAccessible(true);
        field.setInt(list, Integer.MAX_VALUE - 1);
        var w = waiters(4);
        List<Integer> fired = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            fired.clear();
            list.await(w.get(0));
            list.await(w.get(1));
            list.fire(id -> {
                fired.add(id);
                list.await(w.get(id + 2));
            });
            assertEquals(List.of(1, 0), fired);
            assertEquals(2, list.size());
            fired.clear();
            list.fire(fired::add);
            assertEquals(Set.of(2, 3), new HashSet<>(fired));
            assertTrue(list.isEmpty());
        }
    }

    @Test
    void randomOperationsMatchABruteForceModel() {
        Random random = new Random(20261112L);
        for (int trial = 0; trial < 300; trial++) {
            int lists = 1 + random.nextInt(3);
            int count = 1 + random.nextInt(24);
            List<WaitList<Integer>> real = new ArrayList<>();
            List<Set<Integer>> model = new ArrayList<>();
            for (int i = 0; i < lists; i++) {
                real.add(new WaitList<>());
                model.add(new HashSet<>());
            }
            var w = waiters(count);
            for (int step = 0; step < 200; step++) {
                int op = random.nextInt(10);
                int id = random.nextInt(count);
                int at = random.nextInt(lists);
                if (op < 5) {
                    real.get(at).await(w.get(id));
                    for (var set : model) set.remove(id);
                    model.get(at).add(id);
                } else if (op < 8) {
                    w.get(id).cancel();
                    for (var set : model) set.remove(id);
                } else {
                    Set<Integer> expected = new HashSet<>(model.get(at));
                    Set<Integer> cancelled = new HashSet<>();
                    Set<Integer> added = new HashSet<>();
                    Set<Integer> fired = new HashSet<>();
                    long seed = random.nextLong();
                    Random inner = new Random(seed);
                    var list = real.get(at);
                    list.fire(x -> {
                        assertTrue(fired.add(x));
                        assertFalse(w.get(x).waiting());
                        int action = inner.nextInt(4);
                        int other = inner.nextInt(count);
                        if (action == 0) {
                            if (w.get(other).list() == list && !fired.contains(other) && !added.contains(other)) cancelled.add(other);
                            if (w.get(other).list() == list) added.remove(other);
                            w.get(other).cancel();
                            for (var set : model) set.remove(other);
                        } else if (action == 1) {
                            boolean moved = w.get(other).list() != list;
                            list.await(w.get(other));
                            if (moved) {
                                for (var set : model) set.remove(other);
                                added.add(other);
                            }
                        }
                    });
                    expected.removeAll(cancelled);
                    assertEquals(expected, fired, "trial " + trial + " step " + step);
                    model.get(at).clear();
                    model.get(at).addAll(added);
                }
                for (int i = 0; i < lists; i++) {
                    assertEquals(model.get(i).size(), real.get(i).size(), "trial " + trial + " step " + step);
                    for (int k = 0; k < count; k++) {
                        boolean in = w.get(k).list() == real.get(i);
                        assertEquals(model.get(i).contains(k), in, "trial " + trial + " step " + step + " waiter " + k);
                    }
                }
            }
        }
    }
}
