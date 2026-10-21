package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 按能力收录对象的自动索引：类确定提供的直接进候选树；类可能提供的不另存，查询第一次覆盖到某个 16³ 区段时，从共用的全量索引里取出
 * 区段内还没被本索引探测过的对象探测一次，通过的进候选树，区段记为已扫过；之后落进已扫过区段的对象记进新增树，被查询覆盖时再探测。功能：
 * <ul>
 * <li>{@link #accepts}：按类分为不收录、候选、待探测三种，每个类只判一次；同一分发器下最多 32 个本类索引，每个占对象上探测标记的一位</li>
 * <li>{@link #added}：候选类登记时判一次条件后进候选树；待探测类只有落进已扫过的区段时才记进新增树，其余不占任何存储</li>
 * <li>{@link #removed}：从候选树和新增树注销这个对象本身，并清掉它的探测标记；已扫过的区段保持有效</li>
 * <li>{@link #forEach}/{@link #findAny}/{@link #query}：先扫范围内未扫过的区段、探测范围内的新增对象（已失效的不探测），再查候选树；
 * 范围覆盖的区段超过 {@link #MAX_SECTIONS} 个时直接逐个检查范围内的对象，不记区段；探测中再次查询也安全</li>
 * <li>{@link #candidateCount}/{@link #sweptSectionCount}/{@link #freshCount}：某维度候选数、已扫过的区段数与待探测的新增数</li>
 * </ul>
 */
final class ProbedSink<E> extends Tracker.Sink<E> {

    static final int MAX_SECTIONS = 4096;

    static final class State<E> {

        final BlockIndex<E> candidates = new BlockIndex<>();
        final BlockIndex<E> fresh = new BlockIndex<>();
        final LongOpenHashSet swept = new LongOpenHashSet();
        final ArrayList<E> scratch = new ArrayList<>();
        int depth;
    }

    private final ClassValue<ProbedBlockEntityIndex.Kind> kinds;
    private final Predicate<? super E> probe;
    private final @Nullable Predicate<? super E> admit;
    private final Tracker.Access<E> access;
    private final TypedSink<E, E> all;
    private final int bit;
    private final LevelStore<State<E>> store = LevelStore.create(State::new);

    ProbedSink(Tracker<E> tracker, TypedSink<E, E> all, Function<Class<?>, ProbedBlockEntityIndex.Kind> classifier,
               Predicate<? super E> probe, @Nullable Predicate<? super E> admit) {
        this.bit = tracker.allocateProbeBit();
        this.access = tracker.access();
        this.all = all;
        this.probe = probe;
        this.admit = admit;
        this.kinds = new ClassValue<>() {

            @Override
            protected ProbedBlockEntityIndex.Kind computeValue(Class<?> type) {
                ProbedBlockEntityIndex.Kind kind = classifier.apply(type);
                return kind == null ? ProbedBlockEntityIndex.Kind.NONE : kind;
            }
        };
    }

    @Override
    boolean accepts(Class<?> type) {
        return kinds.get(type) != ProbedBlockEntityIndex.Kind.NONE;
    }

    @Override
    void added(LevelIndexHolder holder, E e, BlockPos pos) {
        if (kinds.get(e.getClass()) == ProbedBlockEntityIndex.Kind.CANDIDATE) {
            if (admit != null && !admit.test(e)) return;
            store.forHolder(holder).candidates.put(pos, e);
        } else {
            State<E> state = store.forHolderIfPresent(holder);
            if (state != null && state.swept.contains(SectionPos.asLong(pos))) state.fresh.put(pos, e);
        }
    }

    @Override
    void removed(LevelIndexHolder holder, E e, BlockPos pos) {
        int mask = access.probedMask(e);
        if ((mask & bit) != 0) access.setProbedMask(e, mask & ~bit);
        State<E> state = store.forHolderIfPresent(holder);
        if (state == null) return;
        state.candidates.remove(pos, e);
        state.fresh.remove(pos, e);
    }

    void forEach(LevelIndexHolder holder, int x, int y, int z, BlockRange range, @Nullable Predicate<? super E> filter, Consumer<? super E> action) {
        State<E> state = prepare(holder, x, y, z, range);
        if (state != null && !state.candidates.isEmpty()) state.candidates.query(x, y, z, range, filter, action);
    }

    int query(LevelIndexHolder holder, int x, int y, int z, BlockRange range, @Nullable Predicate<? super E> filter, Collection<? super E> out) {
        State<E> state = prepare(holder, x, y, z, range);
        return state == null || state.candidates.isEmpty() ? 0 : state.candidates.query(x, y, z, range, filter, out);
    }

    @Nullable
    E findAny(LevelIndexHolder holder, int x, int y, int z, BlockRange range, @Nullable Predicate<? super E> filter) {
        State<E> state = prepare(holder, x, y, z, range);
        return state == null || state.candidates.isEmpty() ? null : state.candidates.findAny(x, y, z, range, filter);
    }

    int candidateCount(LevelIndexHolder holder) {
        State<E> state = store.forHolderIfPresent(holder);
        return state == null ? 0 : state.candidates.size();
    }

    int sweptSectionCount(LevelIndexHolder holder) {
        State<E> state = store.forHolderIfPresent(holder);
        return state == null ? 0 : state.swept.size();
    }

    int freshCount(LevelIndexHolder holder) {
        State<E> state = store.forHolderIfPresent(holder);
        return state == null ? 0 : state.fresh.size();
    }

    @Nullable
    private State<E> prepare(LevelIndexHolder holder, int x, int y, int z, BlockRange range) {
        BlockIndex<E> source = all.index(holder);
        if (source == null || source.isEmpty() || range.isEmpty()) return store.forHolderIfPresent(holder);
        State<E> state = store.forHolder(holder);
        long minX = Math.max((long) x - range.radiusX(), BlockIndex.MIN_XZ), maxX = Math.min((long) x + range.radiusX(), BlockIndex.MAX_XZ);
        long minY = Math.max((long) y - range.radiusY(), BlockIndex.MIN_Y), maxY = Math.min((long) y + range.radiusY(), BlockIndex.MAX_Y);
        long minZ = Math.max((long) z - range.radiusZ(), BlockIndex.MIN_XZ), maxZ = Math.min((long) z + range.radiusZ(), BlockIndex.MAX_XZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) return state;
        int sx0 = (int) minX >> 4, sx1 = (int) maxX >> 4, sy0 = (int) minY >> 4, sy1 = (int) maxY >> 4, sz0 = (int) minZ >> 4, sz1 = (int) maxZ >> 4;
        long sections = (long) (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
        if (sections > MAX_SECTIONS) {
            sweep(state, source, x, y, z, range, Integer.MIN_VALUE, 0, 0);
            return state;
        }
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    if (!state.swept.add(SectionPos.asLong(sx, sy, sz))) continue;
                    sweep(state, source, (sx << 4) + 8, (sy << 4) + 8, (sz << 4) + 8, BlockRange.box(8), sx, sy, sz);
                }
            }
        }
        if (!state.fresh.isEmpty()) sweep(state, state.fresh, x, y, z, range, Integer.MIN_VALUE, 0, 0);
        return state;
    }

    private void sweep(State<E> state, BlockIndex<E> source, int x, int y, int z, BlockRange range, int sx, int sy, int sz) {
        boolean section = sx != Integer.MIN_VALUE;
        List<E> found = state.depth == 0 ? state.scratch : new ArrayList<>();
        found.clear();
        source.query(x, y, z, range, null, found);
        if (found.isEmpty()) return;
        state.depth++;
        try {
            for (int i = 0, n = found.size(); i < n; i++) {
                E e = found.get(i);
                if (kinds.get(e.getClass()) != ProbedBlockEntityIndex.Kind.PROBE) continue;
                BlockPos pos = access.pos(e);
                if (section && ((pos.getX() >> 4) != sx || (pos.getY() >> 4) != sy || (pos.getZ() >> 4) != sz)) continue;
                int mask = access.probedMask(e);
                if ((mask & bit) != 0) continue;
                access.setProbedMask(e, mask | bit);
                if (!section && !state.fresh.isEmpty()) state.fresh.remove(pos, e);
                if (admit != null && !admit.test(e)) continue;
                if (access.live(e) && probe.test(e)) state.candidates.put(pos, e);
            }
        } finally {
            state.depth--;
            found.clear();
        }
    }
}
