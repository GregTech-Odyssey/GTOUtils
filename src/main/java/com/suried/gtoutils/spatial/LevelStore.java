package com.suried.gtoutils.spatial;

import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 按维度实例存放一份对象：对象直接挂在 {@link Level} 实例上的槽位数组里，随维度实例一起回收，不经过任何全局表。功能：
 * <ul>
 * <li>{@link #create}：新建一种存放，分配一个全局唯一的槽位号；通常声明成静态常量</li>
 * <li>{@link #get}：取该维度的对象，没有就用工厂创建；{@link #getIfPresent}：只读，没有时返回 null，不创建</li>
 * <li>{@link #release}：丢弃该维度的这一份；{@link #releaseAll}：丢弃该维度的全部存放，维度卸载时由 GTOUtils 统一调用</li>
 * <li>按维度实例区分，客户端和服务端的维度互不影响；单个维度的槽位只应在拥有该维度的线程上读写</li>
 * </ul>
 */
public final class LevelStore<T> {

    private static final AtomicInteger SLOTS = new AtomicInteger();

    private final int slot;
    private final Supplier<? extends T> factory;

    private LevelStore(Supplier<? extends T> factory) {
        this.slot = SLOTS.getAndIncrement();
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static <T> LevelStore<T> create(Supplier<? extends T> factory) {
        return new LevelStore<>(factory);
    }

    public T get(Level level) {
        return forHolder((LevelIndexHolder) level);
    }

    public @Nullable T getIfPresent(Level level) {
        return forHolderIfPresent((LevelIndexHolder) level);
    }

    public void release(Level level) {
        releaseHolder((LevelIndexHolder) level);
    }

    public static void releaseAll(Level level) {
        ((LevelIndexHolder) level).gtoutils$setSlots(null);
    }

    @SuppressWarnings("unchecked")
    T forHolder(LevelIndexHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        if (slots != null && slot < slots.length) {
            Object value = slots[slot];
            if (value != null) return (T) value;
        }
        if (slots == null || slot >= slots.length) {
            Object[] grown = new Object[Math.max(slot + 1, SLOTS.get())];
            if (slots != null) System.arraycopy(slots, 0, grown, 0, slots.length);
            slots = grown;
            holder.gtoutils$setSlots(slots);
        }
        T created = Objects.requireNonNull(factory.get(), "factory returned null");
        slots[slot] = created;
        return created;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    T forHolderIfPresent(LevelIndexHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        return slots != null && slot < slots.length ? (T) slots[slot] : null;
    }

    void releaseHolder(LevelIndexHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        if (slots != null && slot < slots.length) slots[slot] = null;
    }
}
