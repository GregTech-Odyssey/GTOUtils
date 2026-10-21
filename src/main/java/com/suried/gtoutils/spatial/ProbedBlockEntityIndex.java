package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 按能力自动维护的方块实体索引：分类器按类把方块实体分为不收录、候选（确定提供）、待探测（可能提供）三种，
 * 待探测的不另外存放，查询第一次覆盖到所在区段时从 {@link BlockEntityIndex#all()} 取出来用探测条件判一次，结果按实例记在方块实体上，
 * 之后只走候选。适合能力挂在任意方块实体上、逐个判断很贵的查找；能力会随时变化的，查询结果使用前仍要再取一次能力。
 * 创建时机要求同 {@link BlockEntityIndex}，最多 32 个。功能：
 * <ul>
 * <li>{@link #create(Function, Predicate)}：分类器每个类只调用一次，返回 null 视为不收录；{@link #create(Function, Predicate, Predicate)}：另在登记时判一次条件</li>
 * <li>{@link #query}/{@link #forEach}/{@link #findAny}：先探测范围内还没探测过的方块实体，再按 {@link BlockRange} 查候选</li>
 * <li>{@link #candidateCount}/{@link #sweptSectionCount}：某维度候选数与已扫过的区段数</li>
 * </ul>
 */
public final class ProbedBlockEntityIndex {

    public enum Kind {
        NONE,
        CANDIDATE,
        PROBE
    }

    private final ProbedSink<BlockEntity> sink;

    private ProbedBlockEntityIndex(ProbedSink<BlockEntity> sink) {
        this.sink = sink;
    }

    public static ProbedBlockEntityIndex create(Function<Class<?>, Kind> classifier, Predicate<? super BlockEntity> probe) {
        return create(classifier, probe, null);
    }

    public static ProbedBlockEntityIndex create(Function<Class<?>, Kind> classifier, Predicate<? super BlockEntity> probe,
                                                @Nullable Predicate<? super BlockEntity> admit) {
        Tracker<BlockEntity> tracker = BlockEntityTracking.TRACKER;
        TypedSink<BlockEntity, BlockEntity> all = BlockEntityIndex.all().sink();
        return new ProbedBlockEntityIndex(tracker.register(new ProbedSink<>(tracker, all, classifier, probe, admit)));
    }

    public int query(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super BlockEntity> filter, Collection<? super BlockEntity> out) {
        return sink.query((LevelIndexHolder) level, center.getX(), center.getY(), center.getZ(), range, filter, out);
    }

    public void forEach(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super BlockEntity> filter, Consumer<? super BlockEntity> action) {
        sink.forEach((LevelIndexHolder) level, center.getX(), center.getY(), center.getZ(), range, filter, action);
    }

    public @Nullable BlockEntity findAny(Level level, BlockPos center, BlockRange range, @Nullable Predicate<? super BlockEntity> filter) {
        return sink.findAny((LevelIndexHolder) level, center.getX(), center.getY(), center.getZ(), range, filter);
    }

    public int candidateCount(Level level) {
        return sink.candidateCount((LevelIndexHolder) level);
    }

    public int sweptSectionCount(Level level) {
        return sink.sweptSectionCount((LevelIndexHolder) level);
    }
}
