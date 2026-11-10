package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Objects;

/**
 * 按键共享、引用计数的资源池，由 {@link Scope#pool} 建立并随那个作用域结束；资源活在自己的作用域里。功能：
 * <ul>
 * <li>建池时给定唯一的工厂；{@link Scope#lease} 借用：第一个借用者到来时用工厂在资源作用域里创建资源，之后的借用者共用；借用者的作用域结束即归还</li>
 * <li>工厂抛异常或返回 null 时，这次建到一半的资源作用域整个撤回、该键不留下任何东西，异常照常抛给借用方，下次借用重新创建；工厂里再借同一个键直接报错</li>
 * <li>最后一个借用归还后等 grace 轮才结束资源作用域，期间再借用则继续沿用；grace 为 0 时立即结束</li>
 * <li>{@link Lease#demand}：借用者声明是否需要资源工作；{@link Pooled#demanded} 汇总为"至少一个借用者需要"，在派生里读取时自动追踪</li>
 * <li>{@link #peek}/{@link #size}：查看已有资源与资源数</li>
 * <li>池所在的作用域结束后全部资源结束，之后的借用拿到的是已结束作用域里的资源</li>
 * </ul>
 */
public final class SharedPool<K, V> {

    @FunctionalInterface
    public interface Factory<K, V> {

        V create(Pooled pooled, K key);
    }

    private final OwnedScope root;
    private final int grace;
    private final Factory<K, V> factory;
    private final HashMap<K, Pooled> slots = new HashMap<>();

    SharedPool(OwnedScope root, int grace, Factory<K, V> factory) {
        this.root = root;
        this.grace = Math.max(0, grace);
        this.factory = Objects.requireNonNull(factory, "factory");
        root.onDispose(slots::clear);
    }

    @SuppressWarnings("unchecked")
    Lease<V> lease(Scope borrower, K key) {
        Pooled slot = slots.get(key);
        if (slot == null || !slot.scope.isActive()) slot = create(key);
        else if (slot.value == null) throw new IllegalStateException("Re-entrant lease of " + key + " in pool " + root.name());
        slot.release = Task.cancel(slot.release);
        slot.refs++;
        var lease = new Lease<V>(this, key, slot, (V) slot.value);
        borrower.onDispose(lease::release);
        return lease;
    }

    private Pooled create(K key) {
        Pooled slot = new Pooled(root.labeled(key));
        boolean kept = root.isActive();
        if (kept) slots.put(key, slot);
        try {
            slot.value = Objects.requireNonNull(factory.create(slot, key), "pooled value");
        } catch (Throwable t) {
            if (kept) slots.remove(key, slot);
            slot.scope.end();
            throw t;
        }
        return slot;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public V peek(K key) {
        Pooled slot = slots.get(key);
        return slot == null ? null : (V) slot.value;
    }

    public int size() {
        return slots.size();
    }

    private void returned(K key, Pooled slot) {
        if (--slot.refs > 0) return;
        if (grace == 0 || !root.isActive()) {
            retire(key, slot);
            return;
        }
        slot.release = root.lane().schedule(grace, root, () -> {
            slot.release = null;
            if (slot.refs == 0) retire(key, slot);
        });
    }

    private void retire(K key, Pooled slot) {
        slots.remove(key, slot);
        slot.scope.end();
    }

    public static final class Pooled {

        private final OwnedScope scope;
        private final Signal demand = new Signal();
        private @Nullable Object value;
        private @Nullable Task release;
        private int refs;
        private int demands;

        private Pooled(OwnedScope scope) {
            this.scope = scope;
        }

        public Scope scope() {
            return scope;
        }

        public boolean demanded() {
            demand.track();
            return demands > 0;
        }

        private void demandChanged(int delta) {
            int before = demands;
            demands += delta;
            if ((before > 0) != (demands > 0)) demand.emit();
        }
    }

    public static final class Lease<V> {

        private final SharedPool<?, V> pool;
        private final Object key;
        private final Pooled slot;
        private final V value;
        private boolean demanding;
        private boolean released;

        private Lease(SharedPool<?, V> pool, Object key, Pooled slot, V value) {
            this.pool = pool;
            this.key = key;
            this.slot = slot;
            this.value = Objects.requireNonNull(value, "value");
        }

        public V get() {
            return value;
        }

        public void demand(boolean wanted) {
            if (released || wanted == demanding) return;
            demanding = wanted;
            slot.demandChanged(wanted ? 1 : -1);
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private void release() {
            if (released) return;
            demand(false);
            released = true;
            ((SharedPool) pool).returned(key, slot);
        }
    }
}
