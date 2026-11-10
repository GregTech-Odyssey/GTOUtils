package com.suried.gtoutils.level;

import net.minecraft.world.level.Level;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 按维度实例存放一份对象：对象直接挂在 {@link Level} 实例上的槽位数组里，随维度实例一起回收，不经过任何全局表。功能：
 * <ul>
 * <li>{@link #create}/{@link #createWithLevel}：新建一种存放，分配一个全局唯一的槽位号，可带释放回调；通常声明成静态常量，任何时候都能创建</li>
 * <li>{@link #get}：取该维度的对象，没有就用工厂创建，多个线程同时首次访问也只创建一份；{@link #getIfPresent}：只读，没有时返回 null，不创建</li>
 * <li>{@link #set}：直接放入或清空（传 null）该维度的对象，不调用释放回调</li>
 * <li>{@link #release}：丢弃该维度的这一份并调用释放回调；{@link #releaseAll}：维度卸载时由 GTOUtils 统一调用，逐个调用释放回调后丢弃全部，之后 {@link #isReleased} 为真</li>
 * <li>按维度实例区分，客户端和服务端的维度互不影响；任何线程都能读写，存放的对象本身是否线程安全由使用方负责</li>
 * </ul>
 */
public final class LevelStore<T> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final AtomicInteger SLOTS = new AtomicInteger();
    private static volatile LevelStore<?>[] stores = new LevelStore<?>[0];

    private final int slot;
    private final Function<Object, ? extends T> factory;
    private final @Nullable Consumer<? super T> onRelease;

    private LevelStore(Function<Object, ? extends T> factory, @Nullable Consumer<? super T> onRelease) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.onRelease = onRelease;
        synchronized (LevelStore.class) {
            this.slot = SLOTS.getAndIncrement();
            LevelStore<?>[] grown = Arrays.copyOf(stores, slot + 1);
            grown[slot] = this;
            stores = grown;
        }
    }

    public static <T> LevelStore<T> create(Supplier<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return new LevelStore<>(holder -> factory.get(), null);
    }

    public static <T> LevelStore<T> create(Supplier<? extends T> factory, Consumer<? super T> onRelease) {
        Objects.requireNonNull(factory, "factory");
        return new LevelStore<>(holder -> factory.get(), Objects.requireNonNull(onRelease, "onRelease"));
    }

    public static <T> LevelStore<T> createWithLevel(Function<? super Level, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return new LevelStore<>(holder -> factory.apply((Level) holder), null);
    }

    public static <T> LevelStore<T> createWithLevel(Function<? super Level, ? extends T> factory, Consumer<? super T> onRelease) {
        Objects.requireNonNull(factory, "factory");
        return new LevelStore<>(holder -> factory.apply((Level) holder), Objects.requireNonNull(onRelease, "onRelease"));
    }

    public T get(Level level) {
        return forHolder((LevelSlotHolder) level);
    }

    public @Nullable T getIfPresent(Level level) {
        return forHolderIfPresent((LevelSlotHolder) level);
    }

    public void set(Level level, @Nullable T value) {
        setHolder((LevelSlotHolder) level, value);
    }

    public void release(Level level) {
        releaseHolder((LevelSlotHolder) level);
    }

    public static void releaseAll(Level level) {
        releaseAllHolder((LevelSlotHolder) level);
    }

    public static boolean isReleased(Level level) {
        return ((LevelSlotHolder) level).gtoutils$released();
    }

    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    public T forHolder(LevelSlotHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        if (slots != null && slot < slots.length) {
            Object value = SLOT.getAcquire(slots, slot);
            if (value != null) return (T) value;
        }
        return createIn(holder);
    }

    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    public @Nullable T forHolderIfPresent(LevelSlotHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        return slots != null && slot < slots.length ? (T) SLOT.getAcquire(slots, slot) : null;
    }

    @ApiStatus.Internal
    public void setHolder(LevelSlotHolder holder, @Nullable T value) {
        synchronized (holder) {
            if (value == null) {
                Object[] slots = holder.gtoutils$slots();
                if (slots != null && slot < slots.length) SLOT.setRelease(slots, slot, (Object) null);
                return;
            }
            SLOT.setRelease(capacity(holder), slot, (Object) value);
        }
    }

    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    public void releaseHolder(LevelSlotHolder holder) {
        Object value;
        synchronized (holder) {
            Object[] slots = holder.gtoutils$slots();
            if (slots == null || slot >= slots.length) return;
            value = SLOT.getAcquire(slots, slot);
            SLOT.setRelease(slots, slot, (Object) null);
        }
        if (value != null) notifyRelease((T) value);
    }

    @ApiStatus.Internal
    public static void releaseAllHolder(LevelSlotHolder holder) {
        Object[] slots;
        synchronized (holder) {
            slots = holder.gtoutils$slots();
            holder.gtoutils$setSlots(null);
            holder.gtoutils$setReleased(true);
        }
        if (slots == null) return;
        LevelStore<?>[] all = stores;
        for (int i = 0; i < slots.length && i < all.length; i++) {
            Object value = SLOT.getAcquire(slots, i);
            if (value != null) all[i].notifyReleaseUnchecked(value);
        }
    }

    @SuppressWarnings("unchecked")
    private T createIn(LevelSlotHolder holder) {
        synchronized (holder) {
            Object[] slots = capacity(holder);
            Object value = SLOT.getAcquire(slots, slot);
            if (value != null) return (T) value;
            T created = Objects.requireNonNull(factory.apply(holder), "factory returned null");
            SLOT.setRelease(capacity(holder), slot, (Object) created);
            return created;
        }
    }

    private Object[] capacity(LevelSlotHolder holder) {
        Object[] slots = holder.gtoutils$slots();
        if (slots != null && slot < slots.length) return slots;
        Object[] grown = new Object[Math.max(slot + 1, SLOTS.get())];
        if (slots != null) System.arraycopy(slots, 0, grown, 0, slots.length);
        holder.gtoutils$setSlots(grown);
        return grown;
    }

    @SuppressWarnings("unchecked")
    private void notifyReleaseUnchecked(Object value) {
        notifyRelease((T) value);
    }

    private void notifyRelease(T value) {
        if (onRelease == null) return;
        try {
            onRelease.accept(value);
        } catch (Throwable t) {
            LOGGER.error("GTOUtils: level store release callback failed", t);
        }
    }
}
