package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 按类型自动维护的方块实体索引：区块放入、替换、移除方块实体和区块卸载时由 GTOUtils 登记注销，使用方不用在方块实体里写钩子。
 * 必须在模组加载阶段创建（声明成静态常量，并在模组构造时触发所在类初始化），维度开始加载方块实体后再创建会抛异常。功能：
 * <ul>
 * <li>{@link #create(Class)}：收录运行时类是该类型子类型的方块实体；{@link #create(Class, Predicate)}：另在登记时判一次条件，只收录通过的</li>
 * <li>{@link #all()}：全部方块实体共用的一份索引，供只能逐个判断能力的查找和 {@link ProbedBlockEntityIndex} 的待探测部分使用；第一次调用时创建，同样必须在加载阶段调用</li>
 * <li>{@link #query}/{@link #forEach}/{@link #findAny}/{@link #findNearest}：按 {@link BlockRange} 查某维度范围内的方块实体，语义同 {@link BlockIndex}</li>
 * <li>{@link #findAny(Level, Predicate)}/{@link #forEach(Level, Consumer)}：遍历某维度全部收录的方块实体</li>
 * <li>{@link #get}/{@link #size}/{@link #version}：按坐标取、数量、内容变化计数</li>
 * <li>索引存放在维度实例上，随维度回收；只在拥有该维度的线程上查询</li>
 * </ul>
 */
public final class BlockEntityIndex<T> {

    private final TypedSink<BlockEntity, T> sink;

    private BlockEntityIndex(TypedSink<BlockEntity, T> sink) {
        this.sink = sink;
    }

    public static <T> BlockEntityIndex<T> create(Class<T> type) {
        return create(type, null);
    }

    public static <T> BlockEntityIndex<T> create(Class<T> type, @Nullable Predicate<? super T> admit) {
        return new BlockEntityIndex<>(BlockEntityTracking.TRACKER.register(new TypedSink<>(type, admit)));
    }

    public static BlockEntityIndex<BlockEntity> all() {
        return All.INDEX;
    }

    private static final class All {

        static final BlockEntityIndex<BlockEntity> INDEX = create(BlockEntity.class);
    }

    TypedSink<BlockEntity, T> sink() {
        return sink;
    }

    private @Nullable BlockIndex<T> index(Level level) {
        return sink.index((LevelIndexHolder) level);
    }

    public int query(Level level, int x, int y, int z, BlockRange range, @Nullable Predicate<? super T> filter, Collection<? super T> out) {
        BlockIndex<T> index = index(level);
        return index == null ? 0 : index.query(x, y, z, range, filter, out);
    }

    public int query(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super T> filter, Collection<? super T> out) {
        return query(level, center.getX(), center.getY(), center.getZ(), range, filter, out);
    }

    public void forEach(Level level, int x, int y, int z, BlockRange range, @Nullable Predicate<? super T> filter, Consumer<? super T> action) {
        BlockIndex<T> index = index(level);
        if (index != null) index.query(x, y, z, range, filter, action);
    }

    public void forEach(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super T> filter, Consumer<? super T> action) {
        forEach(level, center.getX(), center.getY(), center.getZ(), range, filter, action);
    }

    public @Nullable T findAny(Level level, int x, int y, int z, BlockRange range, @Nullable Predicate<? super T> filter) {
        BlockIndex<T> index = index(level);
        return index == null ? null : index.findAny(x, y, z, range, filter);
    }

    public @Nullable T findAny(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super T> filter) {
        return findAny(level, center.getX(), center.getY(), center.getZ(), range, filter);
    }

    public @Nullable T findNearest(Level level, int x, int y, int z, BlockRange range, @Nullable Predicate<? super T> filter) {
        BlockIndex<T> index = index(level);
        return index == null ? null : index.findNearest(x, y, z, range, filter);
    }

    public @Nullable T findNearest(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super T> filter) {
        return findNearest(level, center.getX(), center.getY(), center.getZ(), range, filter);
    }

    public @Nullable T findAny(Level level, Predicate<? super T> filter) {
        BlockIndex<T> index = index(level);
        return index == null ? null : index.findAny(filter);
    }

    public void forEach(Level level, Consumer<? super T> action) {
        BlockIndex<T> index = index(level);
        if (index != null) index.forEach(action);
    }

    public @Nullable T get(Level level, BlockPos pos) {
        BlockIndex<T> index = index(level);
        return index == null ? null : index.get(pos);
    }

    public int size(Level level) {
        BlockIndex<T> index = index(level);
        return index == null ? 0 : index.size();
    }

    public int version(Level level) {
        BlockIndex<T> index = index(level);
        return index == null ? 0 : index.version();
    }
}
