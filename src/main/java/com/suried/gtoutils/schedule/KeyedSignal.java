package com.suried.gtoutils.schedule;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按键分片的依赖信号：派生只依赖读到的那几个键，触发一个键只让追踪了它的派生重算。功能：
 * <ul>
 * <li>{@link #track}：在派生函数求值期间调用时，把这个键记为该派生的依赖；不在求值期间调用时什么也不做、不建任何东西</li>
 * <li>{@link #emit}：只让追踪了这个键的派生重算、监听执行；没有追踪者的键直接返回；线程规矩与 {@link Signal#emit} 相同；在别的线程上触发时转投到车道，到车道线程上再按键重新查分片，期间分片被摘除又重建也能送到新的追踪者</li>
 * <li>{@link #listen}：把监听挂在作用域上，这个键被触发时立即同步执行（顺序与规矩同 {@link Scope#listen}），作用域结束时自动摘除；作用域已结束或订阅失败时不留分片</li>
 * <li>{@link #emitAll}：触发全部有追踪者的键</li>
 * <li>每个键的分片在第一个派生追踪时建立，最后一个追踪者退订时摘除，键大量出现又消失后不留东西；追踪后订阅失败（例如另一个线程上的车道来订阅同一个键）时分片也会摘除；{@link #keyCount} 是当前有分片的键数</li>
 * <li>键按 equals 区分，不得为 null</li>
 * </ul>
 */
public final class KeyedSignal<K> {

    private final ConcurrentHashMap<K, Slot> slots = new ConcurrentHashMap<>();

    public void track(K key) {
        Objects.requireNonNull(key, "key");
        if (!Signal.tracking()) return;
        Slot slot = slots.get(key);
        if (slot == null) {
            Slot made = new Slot(this, key);
            slot = slots.putIfAbsent(key, made);
            if (slot == null) slot = made;
        }
        slot.track();
    }

    public void listen(Scope scope, K key, Runnable listener) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Slot slot = slots.get(key);
        if (slot == null) {
            Slot made = new Slot(this, key);
            slot = slots.putIfAbsent(key, made);
            if (slot == null) slot = made;
        }
        try {
            scope.listen(slot, listener);
        } finally {
            slot.unclaimed();
        }
    }

    public void emit(K key) {
        Slot slot = slots.get(Objects.requireNonNull(key, "key"));
        if (slot == null) return;
        TickScheduler lane = slot.home();
        if (lane != null && lane.foreign()) {
            Scope.anomaly("signal emitted off its lane thread", null);
            lane.schedule(0, null, () -> emit(key));
            return;
        }
        slot.emit();
    }

    public void emitAll() {
        for (Slot slot : slots.values()) slot.emit();
    }

    public int keyCount() {
        return slots.size();
    }

    static final class Slot extends Signal {

        private final KeyedSignal<?> owner;
        private final Object key;

        Slot(KeyedSignal<?> owner, Object key) {
            this.owner = owner;
            this.key = key;
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        void firstSubscribed() {
            ((ConcurrentHashMap) owner.slots).putIfAbsent(key, this);
        }

        @Override
        void lastUnsubscribed() {
            owner.slots.remove(key, this);
        }

        @Override
        void unclaimed() {
            if (subscriberCount() == 0) owner.slots.remove(key, this);
        }
    }
}
