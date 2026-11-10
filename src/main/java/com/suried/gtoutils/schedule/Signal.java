package com.suried.gtoutils.schedule;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无载荷的依赖信号，作用域派生（{@link Scope#select}、{@link Scope#when}、{@link Scope#each}）据此自动重算。功能：
 * <ul>
 * <li>{@link #track}：在派生函数求值期间调用时，把本信号记为该派生的依赖；不在求值期间调用时什么也不做，开销是一次原子读；求值用的记录按线程复用，重算不分配</li>
 * <li>{@link #emit}：依赖本信号的派生标脏，合并到其车道的下一轮重算；经 {@link Scope#listen} 挂上的监听立即同步执行；没有订阅时直接返回</li>
 * <li>某个订阅抛异常只记日志，不影响其余订阅，也不会抛给触发方</li>
 * <li>分发期间新增的订阅本次不执行，移除的订阅本次不再执行；订阅的增删都是 O(1)</li>
 * <li>{@link #subscriberCount}：当前订阅数</li>
 * </ul>
 */
public sealed class Signal permits Poll.PollSignal {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger FRAMES = new AtomicInteger();
    private static final ThreadLocal<Tracker> TRACKERS = new ThreadLocal<>();
    private static final Sub[] NONE = new Sub[0];

    private Sub[] subs = NONE;
    private int size;
    private int live;
    private int dispatching;
    private boolean holes;

    public final void track() {
        if (FRAMES.get() == 0) return;
        Tracker tracker = TRACKERS.get();
        if (tracker != null && tracker.depth > 0) tracker.frames[tracker.depth - 1].add(this);
    }

    public final void emit() {
        int n = size;
        if (n == 0) return;
        dispatching++;
        try {
            for (int i = 0; i < n; i++) {
                Sub s = subs[i];
                if (s != null && s.live) deliver(s.action);
            }
        } finally {
            if (--dispatching == 0 && holes) compact();
        }
    }

    private static void deliver(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOGGER.error("GTOUtils: signal subscriber failed", t);
        }
    }

    public final int subscriberCount() {
        return live;
    }

    void firstSubscribed() {}

    void lastUnsubscribed() {}

    final Sub subscribe(Runnable action) {
        Sub s = new Sub(this, action);
        if (size == subs.length) subs = Arrays.copyOf(subs, Math.max(4, size * 2));
        s.index = size;
        subs[size++] = s;
        if (live++ == 0) firstSubscribed();
        return s;
    }

    private void remove(Sub s) {
        if (!s.live) return;
        s.live = false;
        if (dispatching > 0) {
            subs[s.index] = null;
            holes = true;
        } else {
            int last = --size;
            Sub moved = subs[last];
            subs[last] = null;
            if (moved != s) {
                subs[s.index] = moved;
                moved.index = s.index;
            }
        }
        if (--live == 0) lastUnsubscribed();
    }

    private void compact() {
        holes = false;
        int j = 0;
        for (int i = 0; i < size; i++) {
            Sub s = subs[i];
            if (s == null) continue;
            s.index = j;
            subs[j++] = s;
        }
        Arrays.fill(subs, j, size, null);
        size = j;
    }

    @Nullable
    static Frame currentFrame() {
        if (FRAMES.get() == 0) return null;
        Tracker tracker = TRACKERS.get();
        return tracker == null || tracker.depth == 0 ? null : tracker.frames[tracker.depth - 1];
    }

    static Frame enter(TickScheduler lane) {
        Tracker tracker = TRACKERS.get();
        if (tracker == null) TRACKERS.set(tracker = new Tracker());
        Frame frame = tracker.push();
        frame.lane = lane;
        FRAMES.incrementAndGet();
        return frame;
    }

    static void exit(Frame frame) {
        FRAMES.decrementAndGet();
        Arrays.fill(frame.deps, 0, frame.count, null);
        frame.count = 0;
        frame.lane = null;
        frame.tracker.depth--;
    }

    private static final class Tracker {

        Frame[] frames = new Frame[4];
        int depth;

        Frame push() {
            if (depth == frames.length) frames = Arrays.copyOf(frames, depth * 2);
            Frame frame = frames[depth];
            if (frame == null) frames[depth] = frame = new Frame(this);
            depth++;
            return frame;
        }
    }

    static final class Sub {

        final Signal signal;
        final Runnable action;
        int index;
        boolean live = true;

        Sub(Signal signal, Runnable action) {
            this.signal = signal;
            this.action = action;
        }

        void remove() {
            signal.remove(this);
        }
    }

    static final class Frame {

        private final Tracker tracker;
        TickScheduler lane;
        Signal[] deps = new Signal[4];
        int count;

        Frame(Tracker tracker) {
            this.tracker = tracker;
        }

        void add(Signal signal) {
            for (int i = 0; i < count; i++) if (deps[i] == signal) return;
            if (count == deps.length) deps = Arrays.copyOf(deps, count * 2);
            deps[count++] = signal;
        }
    }
}
