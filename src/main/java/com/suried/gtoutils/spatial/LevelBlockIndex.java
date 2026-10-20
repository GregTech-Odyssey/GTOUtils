package com.suried.gtoutils.spatial;

import net.minecraft.world.level.LevelAccessor;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一类对象的按维度索引：每个维度实例各有一份 {@link BlockIndex}，通常声明成静态常量。功能：
 * <ul>
 * <li>{@link #create()}：新建一类索引并登记到全局列表</li>
 * <li>{@link #get}：取该维度的索引，没有就创建；{@link #getIfPresent}：只读，没有时返回 null，不创建</li>
 * <li>{@link #release}：丢弃该维度的索引；维度卸载时 GTOUtils 会对所有已创建的索引统一调用 {@link #releaseAll}，使用方不用自己清理</li>
 * <li>按维度实例区分，客户端和服务端的维度互不影响；维度表本身可跨线程访问，单个 {@link BlockIndex} 只在拥有该维度的线程上使用</li>
 * </ul>
 */
public final class LevelBlockIndex<T> {

    private static final List<LevelBlockIndex<?>> ALL = new CopyOnWriteArrayList<>();

    private final Map<Object, BlockIndex<T>> byLevel = new ConcurrentHashMap<>();

    private LevelBlockIndex() {}

    public static <T> LevelBlockIndex<T> create() {
        LevelBlockIndex<T> index = new LevelBlockIndex<>();
        ALL.add(index);
        return index;
    }

    public BlockIndex<T> get(LevelAccessor level) {
        return forKey(level);
    }

    public @Nullable BlockIndex<T> getIfPresent(LevelAccessor level) {
        return byLevel.get(level);
    }

    public void release(LevelAccessor level) {
        byLevel.remove(level);
    }

    public static void releaseAll(LevelAccessor level) {
        releaseKey(level);
    }

    BlockIndex<T> forKey(Object key) {
        return byLevel.computeIfAbsent(key, k -> new BlockIndex<>());
    }

    @Nullable
    BlockIndex<T> forKeyIfPresent(Object key) {
        return byLevel.get(key);
    }

    static void releaseKey(Object key) {
        for (LevelBlockIndex<?> index : ALL) {
            index.byLevel.remove(key);
        }
    }

    int levelCount() {
        return byLevel.size();
    }
}
