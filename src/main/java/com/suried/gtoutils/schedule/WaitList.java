package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 侵入式等待表：等待者自带节点，登记与撤销都是 O(1) 且不分配，触发时同步执行并移出。功能：
 * <ul>
 * <li>{@link Waiter}：一个等待者的节点，由等待方长期持有、反复使用；同一时刻最多在一张表里</li>
 * <li>{@link #await}：把节点登记到本表；已在本表时不动，在别的表里时先从那张表撤销</li>
 * <li>{@link Waiter#cancel}：撤销登记，没在等待时什么也不做；{@link Waiter#waiting} 是否在等待，{@link Waiter#list} 在哪张表里</li>
 * <li>{@link #fire}：把触发开始时在表里的节点逐个移出并交给动作，后登记的先执行；动作里新登记的节点留到下一次触发，动作里撤销了尚未轮到的节点就不再执行它，动作里再次触发本表是安全的；动作抛异常时异常交给调用方，尚未轮到的节点留在表里</li>
 * <li>{@link #isEmpty}/{@link #size}：当前登记数；{@link #trim}：表空时放掉峰值时撑大的数组</li>
 * <li>不是线程安全的，只在拥有它的线程上使用；开断言（-ea）时表在首次使用时记下线程，之后别的线程上 await、cancel、对非空表 fire 直接断言失败，生产环境不开断言时零成本</li>
 * <li>用法：一个 Waiter 只服务同一种表，要同时挂在两种表上就持有两个 Waiter；fire 的动作用无捕获的静态常量，调用前先判 {@link #isEmpty}</li>
 * </ul>
 */
public final class WaitList<T> {

    private static final Waiter<?>[] NONE = new Waiter<?>[0];

    private Waiter<T>[] items = none();
    private int size;
    private int fireId;
    private @Nullable Thread home;

    @SuppressWarnings("unchecked")
    private static <T> Waiter<T>[] none() {
        return (Waiter<T>[]) NONE;
    }

    private void own() {
        assert owned() : "WaitList used from thread " + Thread.currentThread().getName() + " but it belongs to " + home.getName();
    }

    private boolean owned() {
        Thread t = Thread.currentThread();
        if (home == null) home = t;
        return home == t;
    }

    public void await(Waiter<T> waiter) {
        own();
        WaitList<T> at = waiter.list;
        if (at == this) return;
        if (at != null) {
            at.own();
            at.remove(waiter);
        }
        int n = size;
        if (n == items.length) items = Arrays.copyOf(items, Math.max(4, n << 1));
        items[n] = waiter;
        waiter.slot = n;
        waiter.stamp = fireId;
        waiter.list = this;
        size = n + 1;
    }

    public void fire(Consumer<? super T> action) {
        if (size == 0) return;
        own();
        int mark = ++fireId;
        for (int i = size - 1; i >= 0; i--) {
            if (i >= size) {
                i = size;
                continue;
            }
            Waiter<T> waiter = items[i];
            if (waiter.stamp - mark >= 0) continue;
            remove(waiter);
            action.accept(waiter.owner);
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void trim() {
        if (size == 0) items = none();
    }

    private void remove(Waiter<T> waiter) {
        int slot = waiter.slot;
        int last = --size;
        Waiter<T> tail = items[last];
        items[last] = null;
        if (tail != waiter) {
            items[slot] = tail;
            tail.slot = slot;
        }
        waiter.list = null;
    }

    public static final class Waiter<T> {

        private final T owner;
        private @Nullable WaitList<T> list;
        private int slot;
        private int stamp;

        public Waiter(T owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public boolean waiting() {
            return list != null;
        }

        @Nullable
        public WaitList<T> list() {
            return list;
        }

        public void cancel() {
            WaitList<T> at = list;
            if (at == null) return;
            at.own();
            at.remove(this);
        }
    }
}
