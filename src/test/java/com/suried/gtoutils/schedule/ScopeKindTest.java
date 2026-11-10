package com.suried.gtoutils.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScopeKindTest {

    private static final TickScheduler.FailureHandler RETHROW = (error, owner) -> {
        throw new AssertionError("task failed", error);
    };

    private static TickScheduler lane() {
        TickScheduler s = new TickScheduler(true, RETHROW);
        s.runRound();
        return s;
    }

    @Test
    void openMountsModulesInOrderAndCloseEndsInstance() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        var kind = control.kind();
        List<String> log = new ArrayList<>();
        kind.install("a", (scope, owner) -> {
            log.add("a:" + owner);
            scope.onDispose(() -> log.add("-a:" + owner));
        });
        kind.install("b", (scope, owner) -> log.add("b:" + owner));
        var scope = control.open("steve", lane);
        assertEquals(List.of("a:steve", "b:steve"), log);
        assertSame(scope, kind.get("steve"));
        assertEquals(1, kind.size());
        control.close("steve");
        assertNull(kind.get("steve"));
        assertFalse(scope.isActive());
        assertEquals("-a:steve", log.get(log.size() - 1));
    }

    @Test
    void installAfterOpenMountsOnExistingInstances() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("level");
        control.open("overworld", lane);
        control.open("nether", lane);
        List<String> mounted = new ArrayList<>();
        control.kind().install("late", (scope, owner) -> mounted.add(owner));
        assertEquals(List.of("overworld", "nether"), mounted);
    }

    @Test
    void reopeningSameOwnerEndsThePreviousInstance() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        var first = control.open("alex", lane);
        var second = control.open("alex", lane);
        assertFalse(first.isActive());
        assertTrue(second.isActive());
        assertSame(second, control.kind().get("alex"));
        assertEquals(1, control.kind().size());
    }

    @Test
    void bindAdoptsAScopeAndForgetsItWhenItEnds() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("screen");
        AtomicInteger mounts = new AtomicInteger();
        control.kind().install("cards", (scope, owner) -> mounts.incrementAndGet());
        var parent = OwnedScope.root("level", lane);
        var child = parent.child("screen");
        control.bind("0,64,0", child);
        assertEquals(1, mounts.get());
        assertSame(child, control.kind().get("0,64,0"));
        parent.dispose();
        assertNull(control.kind().get("0,64,0"));
        assertEquals(0, control.kind().size());
    }

    @Test
    void bindEndsThePreviouslyOpenedInstance() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        var opened = control.open("steve", lane);
        var parent = OwnedScope.root("level", lane);
        var first = parent.child("first");
        control.bind("steve", first);
        assertFalse(opened.isActive());
        assertSame(first, control.kind().get("steve"));
        var second = parent.child("second");
        control.bind("steve", second);
        assertTrue(first.isActive());
        assertSame(second, control.kind().get("steve"));
        first.dispose();
        assertSame(second, control.kind().get("steve"));
        parent.dispose();
        assertNull(control.kind().get("steve"));
    }

    @Test
    void identityKindSeparatesEqualOwners() {
        var lane = lane();
        var control = ScopeKind.<String>byIdentity("entity");
        String a = new String("same");
        String b = new String("same");
        control.open(a, lane);
        control.open(b, lane);
        assertEquals(2, control.kind().size());
        assertNotSame(control.kind().get(a), control.kind().get(b));
    }

    @Test
    void duplicateModuleIdIsRejected() {
        var kind = ScopeKind.<String>byEquality("x").kind();
        kind.install("m", (scope, owner) -> {});
        assertThrows(IllegalStateException.class, () -> kind.install("m", (scope, owner) -> {}));
    }

    @Test
    void failingModuleDoesNotStopOthers() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("x");
        List<String> log = new ArrayList<>();
        control.kind().install("boom", (scope, owner) -> {
            throw new IllegalStateException("boom");
        });
        control.kind().install("ok", (scope, owner) -> log.add(owner));
        control.open("p", lane);
        assertEquals(List.of("p"), log);
    }

    @Test
    void getInsideDerivationFollowsOpenAndClose() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        var watcher = OwnedScope.root("watcher", lane);
        List<Boolean> seen = new ArrayList<>();
        watcher.select(() -> control.kind().get("steve") != null, (s, online) -> seen.add(online));
        control.open("steve", lane);
        lane.runRound();
        control.close("steve");
        lane.runRound();
        assertEquals(List.of(false, true, false), seen);
    }

    @Test
    void closeAllEndsEveryOpenedInstance() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        var a = control.open("a", lane);
        var b = control.open("b", lane);
        control.closeAll();
        assertFalse(a.isActive());
        assertFalse(b.isActive());
        assertEquals(0, control.kind().size());
    }

    @Test
    void forEachVisitsLiveInstances() {
        var lane = lane();
        var control = ScopeKind.<String>byEquality("player");
        control.open("a", lane);
        control.open("b", lane);
        control.close("a");
        List<String> owners = new ArrayList<>();
        control.kind().forEach((owner, scope) -> owners.add(owner));
        assertEquals(List.of("b"), owners);
    }

    @Test
    void provideReachesTheOwningInstanceAndLookupWalksUpParents() {
        var lane = lane();
        var key = ScopeKey.<String>of("svc");
        var other = ScopeKey.<String>of("svc");
        var root = OwnedScope.root("root", lane);
        var child = root.child("child");
        assertNull(child.lookup(key));
        root.provide(key, "from-root");
        assertEquals("from-root", child.lookup(key));
        assertNull(child.lookup(other));
        child.provide(key, "from-child");
        assertEquals("from-child", root.lookup(key));
        assertEquals("from-child", child.lookup(key));
        child.dispose();
        assertEquals("from-root", root.lookup(key));
        assertEquals("from-root", root.require(key));
        assertThrows(IllegalStateException.class, () -> root.require(other));
    }

    @Test
    void providedServiceIsWithdrawnWithItsProviderAndTracked() {
        var lane = lane();
        var key = ScopeKey.<Integer>of("count");
        var root = OwnedScope.root("root", lane);
        var provider = root.child("provider");
        List<Integer> seen = new ArrayList<>();
        root.select(() -> {
            Integer v = root.lookup(key);
            return v == null ? -1 : v;
        }, (s, v) -> seen.add(v));
        provider.provide(key, 5);
        lane.runRound();
        assertEquals(List.of(-1, 5), seen);
        provider.dispose();
        lane.runRound();
        assertEquals(List.of(-1, 5, -1), seen);
        var late = root.child("late");
        late.provide(key, 7);
        root.dispose();
        assertNull(late.lookup(key));
    }

    @Test
    void instanceServicesAreFoundThroughTheKind() {
        var lane = lane();
        var key = ScopeKey.<String>of("hud");
        var control = ScopeKind.<String>byEquality("player");
        Scope[] hud = new Scope[1];
        control.kind().install("hud", (scope, owner) -> {
            var open = scope.child("open");
            open.provide(key, "host of " + owner);
            hud[0] = open;
        });
        assertNull(control.kind().lookup("steve", key));
        assertThrows(IllegalStateException.class, () -> control.kind().require("steve", key));
        var root = control.open("steve", lane);
        assertEquals("host of steve", root.lookup(key));
        assertEquals("host of steve", control.kind().lookup("steve", key));
        assertEquals("host of steve", control.kind().require("steve", key));
        ((OwnedScope) hud[0]).dispose();
        assertNull(control.kind().lookup("steve", key));
        var parent = OwnedScope.root("level", lane);
        var screen = parent.child("screen");
        control.bind("screen", screen);
        screen.child("inner").provide(key, "panel");
        assertEquals("panel", control.kind().lookup("screen", key));
        assertNull(parent.lookup(key));
        control.close("steve");
        parent.dispose();
    }

    @Test
    void provideOnEndedScopeIsIgnored() {
        var lane = lane();
        var key = ScopeKey.<String>of("svc");
        var root = OwnedScope.root("root", lane);
        root.dispose();
        root.provide(key, "x");
        assertNull(root.lookup(key));
    }

    @Test
    void randomOpenCloseBindMatchesModel() {
        var random = new Random(20261109);
        for (int round = 0; round < 20; round++) {
            int baseline = Scope.liveCount();
            var lane = lane();
            var control = ScopeKind.<Integer>byEquality("k");
            Map<Integer, Integer> mounted = new HashMap<>();
            control.kind().install("count", (scope, owner) -> {
                mounted.merge(owner, 1, Integer::sum);
                scope.onDispose(() -> mounted.merge(owner, -1, Integer::sum));
            });
            var parent = OwnedScope.root("parent", lane);
            Map<Integer, Scope> model = new HashMap<>();
            for (int step = 0; step < 300; step++) {
                int owner = random.nextInt(10);
                switch (random.nextInt(4)) {
                    case 0 -> model.put(owner, control.open(owner, lane));
                    case 1 -> {
                        control.close(owner);
                        if (model.get(owner) != null && !model.get(owner).isActive()) model.remove(owner);
                    }
                    case 2 -> {
                        var child = parent.child("b");
                        control.bind(owner, child);
                        model.put(owner, child);
                    }
                    default -> lane.runRound();
                }
                model.values().removeIf(s -> !s.isActive());
                for (var e : model.entrySet()) assertSame(e.getValue(), control.kind().get(e.getKey()));
            }
            control.closeAll();
            parent.dispose();
            assertEquals(0, control.kind().size());
            for (var v : mounted.values()) assertEquals(0, v);
            assertEquals(baseline, Scope.liveCount());
        }
    }
}
