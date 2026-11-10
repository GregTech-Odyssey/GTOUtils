package com.suried.gtoutils.schedule;

import com.suried.gtoutils.level.LevelStore;

import net.minecraft.world.level.Level;

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 每个维度一份的去重批量队列，在下一轮主线程车道上统一冲刷。功能：
 * <ul>
 * <li>{@link #create}：新建一种批量，给定逐个处理的回调；通常声明成静态常量</li>
 * <li>{@link #add}：加入一个对象，同一轮内重复加入只算一次，按首次加入的顺序冲刷；队列从空变非空时才排一次冲刷任务，空闲时没有开销</li>
 * <li>{@link #remove}/{@link #contains}：从待冲刷的对象里去掉、查询</li>
 * <li>冲刷时先把整批换出再逐个处理，处理中再加入的对象进下一批；维度卸载时整批丢弃</li>
 * <li>只能在该维度的主线程上调用</li>
 * </ul>
 */
public final class TickBatch<T> {

    private final Consumer<? super T> flush;
    private final LevelStore<Queue<T>> queues;

    private TickBatch(Consumer<? super T> flush) {
        this.flush = Objects.requireNonNull(flush, "flush");
        this.queues = LevelStore.createWithLevel(level -> new Queue<>(this.flush, Schedulers.of(level)));
    }

    public static <T> TickBatch<T> create(Consumer<? super T> flush) {
        return new TickBatch<>(flush);
    }

    public void add(Level level, T item) {
        if (LevelStore.isReleased(level)) return;
        queues.get(level).add(Objects.requireNonNull(item, "item"));
    }

    public boolean remove(Level level, T item) {
        Queue<T> q = queues.getIfPresent(level);
        return q != null && q.pending.remove(item);
    }

    public boolean contains(Level level, T item) {
        Queue<T> q = queues.getIfPresent(level);
        return q != null && q.pending.contains(item);
    }

    static final class Queue<T> implements Runnable {

        private final Consumer<? super T> flush;
        private final TickScheduler scheduler;
        private final CoalescedTask task = new CoalescedTask(this);
        ReferenceLinkedOpenHashSet<T> pending = new ReferenceLinkedOpenHashSet<>();
        private ReferenceLinkedOpenHashSet<T> spare = new ReferenceLinkedOpenHashSet<>();

        Queue(Consumer<? super T> flush, TickScheduler scheduler) {
            this.flush = flush;
            this.scheduler = scheduler;
        }

        void add(T item) {
            if (pending.add(item) && !task.isQueued()) scheduler.submit(task);
        }

        @Override
        public void run() {
            ReferenceLinkedOpenHashSet<T> batch = pending;
            pending = spare;
            spare = batch;
            try {
                for (T item : batch) flush.accept(item);
            } finally {
                batch.clear();
                if (!pending.isEmpty() && !task.isQueued()) scheduler.submit(task);
            }
        }
    }
}
