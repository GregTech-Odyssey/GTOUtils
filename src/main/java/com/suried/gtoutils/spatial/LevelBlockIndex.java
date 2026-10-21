package com.suried.gtoutils.spatial;

import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * 由使用方自己登记、注销的按维度索引：每个维度实例各有一份 {@link BlockIndex}，存放在 {@link LevelStore} 里，通常声明成静态常量。
 * 成员由条件决定（成型、入网等）时用它；成员只由方块实体类型决定时改用自动维护的 {@link BlockEntityIndex}。功能：
 * <ul>
 * <li>{@link #create()}：新建一类索引</li>
 * <li>{@link #get}：取该维度的索引，没有就创建；{@link #getIfPresent}：只读，没有时返回 null，不创建</li>
 * <li>{@link #release}：丢弃该维度的索引；维度卸载时 GTOUtils 通过 {@link LevelStore#releaseAll} 统一丢弃，使用方不用自己清理</li>
 * <li>索引随维度实例一起回收，不经过全局表；单个 {@link BlockIndex} 只在拥有该维度的线程上使用</li>
 * </ul>
 */
public final class LevelBlockIndex<T> {

    private final LevelStore<BlockIndex<T>> store = LevelStore.create(BlockIndex::new);

    private LevelBlockIndex() {}

    public static <T> LevelBlockIndex<T> create() {
        return new LevelBlockIndex<>();
    }

    public BlockIndex<T> get(Level level) {
        return store.get(level);
    }

    public @Nullable BlockIndex<T> getIfPresent(Level level) {
        return store.getIfPresent(level);
    }

    public void release(Level level) {
        store.release(level);
    }

    BlockIndex<T> forHolder(LevelIndexHolder holder) {
        return store.forHolder(holder);
    }

    @Nullable
    BlockIndex<T> forHolderIfPresent(LevelIndexHolder holder) {
        return store.forHolderIfPresent(holder);
    }

    void releaseHolder(LevelIndexHolder holder) {
        store.releaseHolder(holder);
    }
}
