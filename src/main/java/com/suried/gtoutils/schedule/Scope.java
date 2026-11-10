package com.suried.gtoutils.schedule;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 作用域：持有副作用，结束时全部收回；绑定一条车道，只在该车道线程上使用。功能：
 * <ul>
 * <li>{@link #child}：子作用域，父结束时连带结束</li>
 * <li>{@link #onDispose}/{@link #effect}：登记撤销；结束时先把整棵子树标为不活动，再自叶到根、按登记倒序执行；撤销抛异常记日志并继续</li>
 * <li>{@link #repeat}/{@link #schedule}/{@link #trigger}：以本作用域为所有者的周期、延迟与合并任务，结束后不再执行，{@link Task#isActive} 随之为假；不占撤销登记，可在长寿命作用域上反复排一次性任务</li>
 * <li>{@link #listen}：信号触发时同步执行，结束时退订</li>
 * <li>{@link #watch}：派生一个值，首次以及之后每次按 equals 变化时把新值交给动作；派生期间读到的 {@link Signal}、{@link Cell} 自动成为依赖，触发后合并到下一轮重算</li>
 * <li>{@link #select}：同样派生一个值，值变化时先建新子作用域并在里面挂载，再结束旧的</li>
 * <li>{@link #when}：派生一个布尔值，为真时建子作用域，为假时结束；为假期间不占子作用域</li>
 * <li>{@link #each}：派生一张表，按键对账：键同值等保留，值变重建，键消失结束；先建后拆；表没变时不改动也不分配</li>
 * <li>所有挂载体的第一个参数都是它所在的作用域；派生函数拿不到作用域，只读</li>
 * <li>派生函数抛异常记日志并保持原状；挂载体抛异常记日志，这次挂到一半的子作用域整个撤回，值再变化时重新挂</li>
 * <li>{@link #pool}/{@link #lease}：建一个随本作用域结束的 {@link SharedPool}；向池借用共享资源，借用方结束时归还</li>
 * <li>{@link #provide}/{@link #lookup}/{@link #require}：按 {@link ScopeKey} 向所属实例（最近的 {@link ScopeKind} 实例或无父作用域）提供带类型的服务，提供方结束时撤下；查找沿父作用域向上，在派生里查找会自动依赖该键；require 找不到时报错</li>
 * <li>在已结束的作用域上登记：副作用不发生，撤销立即执行，任务直接是已取消状态</li>
 * <li>{@link #liveCount}：全进程存活作用域数，用于查泄漏</li>
 * </ul>
 */
public abstract sealed class Scope permits OwnedScope {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger LIVE = new AtomicInteger();
    private static final Runnable[] NO_UNDO = new Runnable[0];
    private static final Scope[] NO_CHILDREN = new Scope[0];
    private static final Object[] NO_SERVICES = new Object[0];
    private static final Signal.Sub[] NO_SUBS = new Signal.Sub[0];

    private Object label;
    private final TickScheduler lane;
    private final @Nullable Scope parent;
    private boolean anchor;
    private boolean active = true;
    private int slot = -1;
    private Scope[] children = NO_CHILDREN;
    private int childCount;
    private Runnable[] undo = NO_UNDO;
    private int undoCount;
    private Object[] services = NO_SERVICES;
    private int serviceCount;

    Scope(Object label, TickScheduler lane, @Nullable Scope parent) {
        this.label = label;
        this.lane = lane;
        this.parent = parent;
        this.anchor = parent == null;
        LIVE.incrementAndGet();
        if (parent != null) {
            if (parent.active) parent.attach(this);
            else end();
        }
    }

    public static int liveCount() {
        return LIVE.get();
    }

    public final String name() {
        Object current = label;
        if (current instanceof String name) return name;
        String name = String.valueOf(current);
        label = name;
        return name;
    }

    final TickScheduler lane() {
        return lane;
    }

    public final boolean isActive() {
        return active;
    }

    public final OwnedScope child(String name) {
        return new OwnedScope(name, lane, this);
    }

    final OwnedScope labeled(Object label) {
        return new OwnedScope(label, lane, this);
    }

    final void markAnchor() {
        anchor = true;
    }

    public final void onDispose(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!active) {
            runQuietly(action);
            return;
        }
        if (undoCount == undo.length) undo = Arrays.copyOf(undo, Math.max(4, undoCount * 2));
        undo[undoCount++] = action;
    }

    public final void effect(Runnable apply, Runnable revert) {
        if (!active) return;
        apply.run();
        onDispose(revert);
    }

    public final Task repeat(Cadence cadence, Runnable action) {
        Task task = lane.repeat(cadence, this, action);
        if (!active) task.cancel();
        return task;
    }

    public final Task schedule(int delay, Runnable action) {
        Task task = lane.schedule(delay, this, action);
        if (!active) task.cancel();
        return task;
    }

    public final Trigger trigger(int delay, Runnable action) {
        CoalescedTask task = new CoalescedTask(this, action);
        int d = Math.max(0, delay);
        return () -> {
            if (active) lane.submit(task, d);
        };
    }

    public final void listen(Signal signal, Runnable listener) {
        if (!active) return;
        onDispose(signal.subscribe(listener)::remove);
    }

    public final <T> void watch(Supplier<? extends T> derive, Consumer<? super T> action) {
        if (!active) return;
        new Watch<T>(this, derive, action).run();
    }

    public final <T> void select(Supplier<? extends T> derive, Body<? super T> body) {
        if (!active) return;
        new Select<T>(this, derive, body).run();
    }

    public final void when(BooleanSupplier condition, Consumer<Scope> body) {
        if (!active) return;
        new When(this, condition, body).run();
    }

    public final <K, V> void each(Supplier<? extends Map<K, ? extends V>> derive, KeyedBody<K, V> body) {
        if (!active) return;
        new Each<K, V>(this, derive, body).run();
    }

    public final <K, V> SharedPool<K, V> pool(String name, int grace, SharedPool.Factory<K, V> factory) {
        return new SharedPool<>(child(name), grace, factory);
    }

    public final <K, V> SharedPool.Lease<V> lease(SharedPool<K, V> pool, K key) {
        return pool.lease(this, key);
    }

    public final <V> void provide(ScopeKey<V> key, V value) {
        Objects.requireNonNull(value, "value");
        if (!active) return;
        Scope host = this;
        while (!host.anchor) host = host.parent;
        host.put(key, value);
        if (host == this) return;
        Scope instance = host;
        onDispose(() -> instance.withdraw(key, value));
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public final <V> V lookup(ScopeKey<V> key) {
        key.changed.track();
        for (Scope s = this; s != null; s = s.parent) {
            for (int i = s.serviceCount - 1; i >= 0; i--) {
                if (s.services[i * 2] == key) return (V) s.services[i * 2 + 1];
            }
        }
        return null;
    }

    public final <V> V require(ScopeKey<V> key) {
        V value = lookup(key);
        if (value == null) throw new IllegalStateException("No " + key + " in scope " + this);
        return value;
    }

    private void put(ScopeKey<?> key, Object value) {
        if (serviceCount * 2 == services.length) services = Arrays.copyOf(services, Math.max(4, services.length * 2));
        services[serviceCount * 2] = key;
        services[serviceCount * 2 + 1] = value;
        serviceCount++;
        key.changed.emit();
    }

    private void withdraw(ScopeKey<?> key, Object value) {
        for (int i = serviceCount - 1; i >= 0; i--) {
            if (services[i * 2] != key || services[i * 2 + 1] != value) continue;
            int tail = (serviceCount - 1 - i) * 2;
            System.arraycopy(services, i * 2 + 2, services, i * 2, tail);
            serviceCount--;
            services[serviceCount * 2] = null;
            services[serviceCount * 2 + 1] = null;
            key.changed.emit();
            return;
        }
    }

    @Override
    public String toString() {
        return parent == null ? name() : parent + "/" + name();
    }

    final void end() {
        if (!active) return;
        deactivate();
        teardown();
        if (parent != null) parent.detach(this);
    }

    private void deactivate() {
        active = false;
        for (int i = 0; i < childCount; i++) children[i].deactivate();
    }

    private void teardown() {
        for (int i = childCount - 1; i >= 0; i--) {
            Scope c = children[i];
            c.teardown();
            c.slot = -1;
            children[i] = null;
        }
        childCount = 0;
        children = NO_CHILDREN;
        for (int i = undoCount - 1; i >= 0; i--) {
            Runnable r = undo[i];
            undo[i] = null;
            runQuietly(r);
        }
        undoCount = 0;
        undo = NO_UNDO;
        while (serviceCount > 0) {
            int i = --serviceCount;
            ScopeKey<?> key = (ScopeKey<?>) services[i * 2];
            services[i * 2] = null;
            services[i * 2 + 1] = null;
            key.changed.emit();
        }
        services = NO_SERVICES;
        LIVE.decrementAndGet();
    }

    private void attach(Scope child) {
        if (childCount == children.length) children = Arrays.copyOf(children, Math.max(4, childCount * 2));
        child.slot = childCount;
        children[childCount++] = child;
    }

    private void detach(Scope child) {
        int i = child.slot;
        if (i < 0 || i >= childCount || children[i] != child) return;
        int last = --childCount;
        Scope moved = children[last];
        children[last] = null;
        if (moved != child) {
            children[i] = moved;
            moved.slot = i;
        }
        child.slot = -1;
    }

    private void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOGGER.error("GTOUtils: dispose action of scope {} failed", this, t);
        }
    }

    @FunctionalInterface
    public interface Trigger {

        void fire();
    }

    @FunctionalInterface
    public interface Body<T> {

        void mount(Scope scope, T value);
    }

    @FunctionalInterface
    public interface KeyedBody<K, V> {

        void mount(Scope scope, K key, V value);
    }

    private abstract static class Derivation implements Runnable {

        final Scope owner;
        private final CoalescedTask task;
        private final Runnable invalidate;
        private Signal.Sub[] subs = NO_SUBS;
        private int subCount;

        Derivation(Scope owner) {
            this.owner = owner;
            this.task = new CoalescedTask(owner, this);
            this.invalidate = () -> {
                if (owner.active) owner.lane.submit(task);
            };
            owner.onDispose(this::stop);
        }

        @Override
        public final void run() {
            if (owner.active) evaluate();
        }

        abstract void evaluate();

        final <T> T derive(Supplier<T> supplier) {
            Signal.Frame frame = Signal.enter(owner.lane);
            try {
                return supplier.get();
            } finally {
                try {
                    rewire(frame);
                } finally {
                    Signal.exit(frame);
                }
            }
        }

        final void failed(String what, Throwable error) {
            LOGGER.error("GTOUtils: {} in scope {} failed", what, owner, error);
        }

        private void rewire(Signal.Frame frame) {
            int n = frame.count;
            Signal[] deps = frame.deps;
            boolean same = n == subCount;
            for (int i = 0; same && i < n; i++) same = subs[i].signal == deps[i];
            if (same) return;
            Signal.Sub[] next = n == 0 ? NO_SUBS : new Signal.Sub[n];
            boolean[] kept = new boolean[subCount];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < subCount; j++) {
                    if (!kept[j] && subs[j].signal == deps[i]) {
                        next[i] = subs[j];
                        kept[j] = true;
                        break;
                    }
                }
                if (next[i] == null) next[i] = deps[i].subscribe(invalidate);
            }
            for (int j = 0; j < subCount; j++) if (!kept[j]) subs[j].remove();
            subs = next;
            subCount = n;
        }

        private void stop() {
            task.cancel();
            for (int i = 0; i < subCount; i++) subs[i].remove();
            subCount = 0;
            subs = NO_SUBS;
        }
    }

    private static final class Watch<T> extends Derivation {

        private final Supplier<? extends T> source;
        private final Consumer<? super T> action;
        private boolean seen;
        private @Nullable T value;

        Watch(Scope owner, Supplier<? extends T> source, Consumer<? super T> action) {
            super(owner);
            this.source = source;
            this.action = action;
        }

        @Override
        void evaluate() {
            T next;
            try {
                next = derive(source);
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            if (seen && Objects.equals(next, value)) return;
            seen = true;
            value = next;
            try {
                action.accept(next);
            } catch (Throwable t) {
                failed("action", t);
            }
        }
    }

    private static final class Select<T> extends Derivation {

        private final Supplier<? extends T> source;
        private final Body<? super T> body;
        private @Nullable OwnedScope child;
        private @Nullable T value;

        Select(Scope owner, Supplier<? extends T> source, Body<? super T> body) {
            super(owner);
            this.source = source;
            this.body = body;
        }

        @Override
        void evaluate() {
            T next;
            try {
                next = derive(source);
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            OwnedScope previous = child;
            if (previous != null && Objects.equals(next, value)) return;
            OwnedScope built = owner.child("select");
            value = next;
            child = built;
            try {
                body.mount(built, next);
            } catch (Throwable t) {
                failed("body", t);
                built.end();
            }
            if (previous != null) previous.end();
        }
    }

    private static final class When extends Derivation {

        private final Supplier<Boolean> source;
        private final Consumer<Scope> body;
        private @Nullable OwnedScope child;

        When(Scope owner, BooleanSupplier condition, Consumer<Scope> body) {
            super(owner);
            this.source = condition::getAsBoolean;
            this.body = body;
        }

        @Override
        void evaluate() {
            boolean open;
            try {
                open = derive(source);
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            OwnedScope current = child;
            if (open == (current != null)) return;
            if (current != null) {
                child = null;
                current.end();
                return;
            }
            OwnedScope built = owner.child("when");
            child = built;
            try {
                body.accept(built);
            } catch (Throwable t) {
                failed("body", t);
                built.end();
            }
        }
    }

    private static final class Each<K, V> extends Derivation {

        private final Supplier<? extends Map<K, ? extends V>> source;
        private final KeyedBody<K, V> body;
        private final HashMap<K, Entry<V>> entries = new HashMap<>();

        Each(Scope owner, Supplier<? extends Map<K, ? extends V>> source, KeyedBody<K, V> body) {
            super(owner);
            this.source = source;
            this.body = body;
        }

        @Override
        void evaluate() {
            Map<K, ? extends V> next;
            try {
                next = derive(source);
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            ArrayList<OwnedScope> retired = null;
            try {
                for (var e : next.entrySet()) {
                    K key = e.getKey();
                    V value = e.getValue();
                    Entry<V> current = entries.get(key);
                    if (current != null && Objects.equals(current.value, value)) continue;
                    OwnedScope scope = owner.child("each");
                    entries.put(key, new Entry<>(value, scope));
                    if (current != null) {
                        if (retired == null) retired = new ArrayList<>();
                        retired.add(current.scope);
                    }
                    try {
                        body.mount(scope, key, value);
                    } catch (Throwable t) {
                        LOGGER.error("GTOUtils: body for key {} in scope {} failed", key, owner, t);
                        scope.end();
                    }
                }
                if (entries.size() > next.size()) {
                    var stale = entries.entrySet().iterator();
                    while (stale.hasNext()) {
                        var e = stale.next();
                        if (next.containsKey(e.getKey())) continue;
                        stale.remove();
                        if (retired == null) retired = new ArrayList<>();
                        retired.add(e.getValue().scope);
                    }
                }
            } catch (Throwable t) {
                failed("reconciliation", t);
            } finally {
                if (retired != null) {
                    for (var scope : retired) scope.end();
                }
            }
        }
    }

    private record Entry<V>(V value, OwnedScope scope) {}
}
