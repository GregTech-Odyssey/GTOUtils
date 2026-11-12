package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * 带值的依赖信号：读即追踪、写即触发，不必手写成对的 {@link Signal#track} 与 {@link Signal#emit}。功能：
 * <ul>
 * <li>{@link #get}：取值；在派生函数求值期间调用时自动成为该派生的依赖；派生以外的每 tick 热路径用 {@link #peek}</li>
 * <li>{@link #peek}：取值但不成为依赖</li>
 * <li>{@link #set}：按 equals 比较，值变了才写入并触发，返回是否变了；在订阅方车道以外的线程上调用时照 {@link Signal#emit} 的规矩转投并记一次误用</li>
 * <li>{@link #post}：可从任意线程写入：在订阅方车道的线程上等同 {@link #set}；在别的线程上把值暂存并转投到车道，同一轮里多次写入只生效最后一个，不记误用；车道线程上的 post 会作废还没生效的暂存值；车道已关闭时直接写入、不触发</li>
 * <li>还没有订阅者时任何线程上的 post 都直接写入；与另一条车道上"先读值、后订阅"的派生同时发生时不会丢：订阅方在首次订阅时发现被别的线程写过，就在下一轮补一次触发，写入方在写完后发现已有订阅，就向订阅方车道转投一次触发</li>
 * <li>暂存后车道关闭、暂存值没来得及生效时，下一个首次订阅把它落进值里并在下一轮触发</li>
 * <li>{@link #changed}：值变化时触发的信号，供 {@link Scope#listen} 使用</li>
 * </ul>
 */
public final class Cell<T> {

    private static final Object NONE = new Object();
    private static final VarHandle PENDING;

    static {
        try {
            PENDING = MethodHandles.lookup().findVarHandle(Cell.class, "pending", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Changed changed = new Changed(this);
    private T value;
    @SuppressWarnings("unused")
    private volatile Object pending = NONE;
    private volatile @Nullable TickScheduler relay;
    private volatile boolean unseen;

    private Cell(T value) {
        this.value = value;
    }

    public static <T> Cell<T> of(T initial) {
        return new Cell<>(initial);
    }

    public T get() {
        changed.track();
        return value;
    }

    public T peek() {
        return value;
    }

    public boolean set(T next) {
        if (Objects.equals(value, next)) return false;
        value = next;
        changed.emit();
        return true;
    }

    public void post(T next) {
        TickScheduler lane = changed.home();
        if (lane == null) {
            postUnsubscribed(next);
            return;
        }
        if (!lane.foreign()) {
            if (pending != NONE) PENDING.setVolatile(this, NONE);
            set(next);
            return;
        }
        if (lane.isClosed()) {
            PENDING.setVolatile(this, NONE);
            value = next;
            return;
        }
        if (PENDING.getAndSet(this, next) != NONE) {
            TickScheduler queued = relay;
            if (queued != null && !queued.isClosed()) return;
        }
        relay = lane;
        lane.schedule(0, null, this::flush);
    }

    private void postUnsubscribed(T next) {
        if (pending != NONE) PENDING.setVolatile(this, NONE);
        if (Objects.equals(value, next)) return;
        value = next;
        unseen = true;
        TickScheduler lane = changed.home();
        if (lane == null) return;
        if (lane.foreign()) lane.schedule(0, null, changed::emit);
        else changed.emit();
    }

    @SuppressWarnings("unchecked")
    private void flush() {
        Object next = PENDING.getAndSet(this, NONE);
        if (next != NONE) set((T) next);
    }

    @SuppressWarnings("unchecked")
    private void subscribed(TickScheduler lane) {
        boolean dirty = false;
        if (unseen) {
            unseen = false;
            dirty = true;
        }
        if (pending != NONE) {
            Object next = PENDING.getAndSet(this, NONE);
            if (next != NONE && !Objects.equals(value, next)) {
                value = (T) next;
                dirty = true;
            }
        }
        if (dirty) lane.schedule(0, null, changed::emit);
    }

    public Signal changed() {
        return changed;
    }

    static final class Changed extends Signal {

        private final Cell<?> cell;

        Changed(Cell<?> cell) {
            this.cell = cell;
        }

        @Override
        void firstSubscribed() {
            TickScheduler lane = home();
            if (lane != null) cell.subscribed(lane);
        }
    }
}
