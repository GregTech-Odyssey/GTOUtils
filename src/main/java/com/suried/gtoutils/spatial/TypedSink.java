package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 按类型收录对象的自动索引：对象的运行时类是 {@code type} 的子类型、且登记时通过 {@code admit} 的对象进入所在维度的 {@link BlockIndex}。功能：
 * <ul>
 * <li>{@link #accepts}：按类判断是否关心，供 {@link Tracker} 缓存</li>
 * <li>{@link #added}：登记时判一次 {@code admit}，通过就放进该维度的索引，同一坐标已有对象时替换</li>
 * <li>{@link #removed}：只有该坐标登记的正是这个对象时才注销</li>
 * <li>{@link #index}：取某维度的索引，没有登记过时返回 null</li>
 * </ul>
 */
final class TypedSink<E, T> extends Tracker.Sink<E> {

    private final Class<T> type;
    private final @Nullable Predicate<? super T> admit;
    private final LevelBlockIndex<T> store = LevelBlockIndex.create();

    TypedSink(Class<T> type, @Nullable Predicate<? super T> admit) {
        this.type = type;
        this.admit = admit;
    }

    @Override
    boolean accepts(Class<?> c) {
        return type.isAssignableFrom(c);
    }

    @Override
    void added(LevelIndexHolder holder, E e, BlockPos pos) {
        T t = type.cast(e);
        if (admit != null && !admit.test(t)) return;
        store.forHolder(holder).put(pos, t);
    }

    @Override
    void removed(LevelIndexHolder holder, E e, BlockPos pos) {
        BlockIndex<T> index = store.forHolderIfPresent(holder);
        if (index != null) index.remove(pos, type.cast(e));
    }

    @Nullable
    BlockIndex<T> index(LevelIndexHolder holder) {
        return store.forHolderIfPresent(holder);
    }
}
