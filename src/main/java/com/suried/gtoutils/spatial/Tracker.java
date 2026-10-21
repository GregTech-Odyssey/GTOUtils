package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把带坐标对象的登记、注销分发给关心它的索引：按对象的运行时类缓存一次关心它的索引列表，没有索引关心的类只做一次缓存查找。功能：
 * <ul>
 * <li>{@link #register}：登记一个索引；第一次分发之后再登记会抛 {@link IllegalStateException}，保证类缓存与已登记的对象一致</li>
 * <li>{@link #added}/{@link #removed}：把对象交给按类缓存的索引，对象不在任何维度时忽略</li>
 * <li>{@link #started}：是否已经分发过；{@link #allocateProbeBit}：给按能力收录的索引分配对象探测标记里的一位，最多 32 位</li>
 * </ul>
 */
final class Tracker<E> {

    interface Access<E> {

        @Nullable
        LevelIndexHolder holder(E e);

        BlockPos pos(E e);

        boolean live(E e);

        int probedMask(E e);

        void setProbedMask(E e, int mask);
    }

    abstract static class Sink<E> {

        abstract boolean accepts(Class<?> type);

        abstract void added(LevelIndexHolder holder, E e, BlockPos pos);

        abstract void removed(LevelIndexHolder holder, E e, BlockPos pos);
    }

    private final Access<E> access;
    private final List<Sink<E>> sinks = new CopyOnWriteArrayList<>();
    private final ClassValue<Sink<E>[]> byClass = new ClassValue<>() {

        @Override
        @SuppressWarnings("unchecked")
        protected Sink<E>[] computeValue(Class<?> type) {
            List<Sink<E>> list = new ArrayList<>();
            for (Sink<E> sink : sinks) {
                if (sink.accepts(type)) list.add(sink);
            }
            return list.toArray(new Sink[0]);
        }
    };
    private volatile boolean started;
    private int probeBits;

    Tracker(Access<E> access) {
        this.access = access;
    }

    Access<E> access() {
        return access;
    }

    synchronized <S extends Sink<E>> S register(S sink) {
        if (started) {
            throw new IllegalStateException("block entity indexes must be created during mod loading, before any level loads block entities");
        }
        sinks.add(sink);
        return sink;
    }

    synchronized int allocateProbeBit() {
        if (probeBits >= 32) throw new IllegalStateException("too many probed indexes (max 32)");
        return 1 << probeBits++;
    }

    boolean started() {
        return started;
    }

    private void start() {
        synchronized (this) {
            started = true;
        }
    }

    void added(E e) {
        if (!started) start();
        Sink<E>[] targets = byClass.get(e.getClass());
        if (targets.length == 0) return;
        LevelIndexHolder holder = access.holder(e);
        if (holder == null) return;
        BlockPos pos = access.pos(e);
        for (Sink<E> sink : targets) sink.added(holder, e, pos);
    }

    void removed(E e) {
        if (!started) start();
        Sink<E>[] targets = byClass.get(e.getClass());
        if (targets.length == 0) return;
        LevelIndexHolder holder = access.holder(e);
        if (holder == null) return;
        BlockPos pos = access.pos(e);
        for (Sink<E> sink : targets) sink.removed(holder, e, pos);
    }
}
