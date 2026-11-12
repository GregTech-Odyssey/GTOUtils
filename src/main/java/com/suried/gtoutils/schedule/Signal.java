package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无载荷的依赖信号，作用域派生（{@link Scope#watch}、{@link Scope#select}、{@link Scope#when}、{@link Scope#each}）据此自动重算。功能：
 * <ul>
 * <li>{@link #track}：在派生函数求值期间调用时，把本信号记为该派生的依赖；不在求值期间调用时什么也不做，开销是一次原子读；求值用的记录按线程复用，重算不分配</li>
 * <li>{@link #emit}：依赖本信号的派生标脏并合并重算，在车道的一轮里触发的当轮就重算；经 {@link Scope#listen} 挂上的监听立即同步执行；没有订阅时直接返回</li>
 * <li>在订阅方车道以外的线程上触发：不在那个线程上分发，转投到订阅方的车道、下一轮开头再触发，并在首次发生时带调用栈告警；转投用的车道关闭后，改用仍有订阅的另一条车道</li>
 * <li>同一个信号只能被同一个线程上的作用域订阅，另一个线程来订阅直接报错</li>
 * <li>在派生函数求值期间触发信号属于误用：照常分发，记一次异常并在首次发生时带调用栈告警</li>
 * <li>某个订阅抛异常只记入失败记录，不影响其余订阅，也不会抛给触发方</li>
 * <li>分发期间新增的订阅本次不执行，移除的订阅本次不再执行；订阅的增删都是 O(1)</li>
 * <li>{@link #subscriberCount}：当前订阅数</li>
 * </ul>
 */
public sealed class Signal permits Poll.PollSignal, KeyedSignal.Slot, Cell.Changed {

    private static final AtomicInteger FRAMES = new AtomicInteger();
    private static final ThreadLocal<Tracker> TRACKERS = new ThreadLocal<>();
    private static final Sub[] NONE = new Sub[0];

    private Sub[] subs = NONE;
    private int size;
    private int live;
    private int dispatching;
    private boolean holes;
    private volatile @Nullable TickScheduler home;

    public final void track() {
        if (FRAMES.get() == 0) return;
        Tracker tracker = TRACKERS.get();
        if (tracker != null && tracker.depth > 0) tracker.frames[tracker.depth - 1].add(this);
    }

    public final void emit() {
        TickScheduler lane = home;
        if (lane == null) return;
        if (lane.foreign()) {
            relay(lane);
            return;
        }
        if (FRAMES.get() != 0 && currentFrame() != null) Scope.anomaly("signal emitted inside a derivation function", null);
        int n = size;
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

    private void relay(TickScheduler lane) {
        Scope.anomaly("signal emitted off its lane thread", null);
        lane.schedule(0, null, this::emit);
    }

    private static void deliver(Object action) {
        try {
            if (action instanceof Scope.Hook hook) hook.fire();
            else ((Runnable) action).run();
        } catch (Throwable t) {
            Scope.fail("signal subscriber", null, t);
        }
    }

    public final int subscriberCount() {
        return live;
    }

    @Nullable
    final TickScheduler home() {
        return home;
    }

    void firstSubscribed() {}

    void lastUnsubscribed() {}

    void unclaimed() {}

    final Sub subscribe(TickScheduler lane, Object action) {
        TickScheduler current = home;
        if (current == null || current.isClosed()) home = lane;
        else if (current.foreign()) throw new IllegalStateException("Signal is already subscribed from another thread");
        Sub s = new Sub(this, lane, action);
        if (size == subs.length) subs = Arrays.copyOf(subs, Math.max(2, size * 2));
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
            if (last < subs.length >> 2 && subs.length > 16) subs = Arrays.copyOf(subs, subs.length >> 1);
        }
        if (--live == 0) {
            home = null;
            lastUnsubscribed();
            return;
        }
        TickScheduler lane = home;
        if (lane != null && lane.isClosed()) rehome();
    }

    private void rehome() {
        for (int i = 0; i < size; i++) {
            Sub s = subs[i];
            if (s == null || !s.live || s.lane.isClosed()) continue;
            home = s.lane;
            return;
        }
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

    static boolean tracking() {
        return currentFrame() != null;
    }

    @Nullable
    static Frame currentFrame() {
        if (FRAMES.get() == 0) return null;
        Tracker tracker = TRACKERS.get();
        if (tracker == null || tracker.depth == 0) return null;
        Frame frame = tracker.frames[tracker.depth - 1];
        return frame.muted ? null : frame;
    }

    static Frame enter(TickScheduler lane, int salt) {
        Tracker tracker = TRACKERS.get();
        if (tracker == null) TRACKERS.set(tracker = new Tracker());
        Frame frame = tracker.push();
        frame.lane = lane;
        frame.salt = salt;
        FRAMES.incrementAndGet();
        return frame;
    }

    static void exit(Frame frame) {
        FRAMES.decrementAndGet();
        Arrays.fill(frame.deps, 0, frame.count, null);
        frame.count = 0;
        frame.lane = null;
        frame.muted = false;
        frame.tracker.depth--;
    }

    @Nullable
    static Frame mute() {
        if (FRAMES.get() == 0) return null;
        Tracker tracker = TRACKERS.get();
        if (tracker == null || tracker.depth == 0) return null;
        Frame frame = tracker.push();
        frame.muted = true;
        FRAMES.incrementAndGet();
        return frame;
    }

    static void unmute(@Nullable Frame frame) {
        if (frame != null) exit(frame);
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
        final TickScheduler lane;
        final Object action;
        int index;
        boolean live = true;

        Sub(Signal signal, TickScheduler lane, Object action) {
            this.signal = signal;
            this.lane = lane;
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
        int salt;
        boolean muted;

        Frame(Tracker tracker) {
            this.tracker = tracker;
        }

        void add(Signal signal) {
            if (muted) return;
            for (int i = 0; i < count; i++) if (deps[i] == signal) return;
            if (count == deps.length) deps = Arrays.copyOf(deps, count * 2);
            deps[count++] = signal;
        }
    }
}
