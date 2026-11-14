package com.suried.gtoutils.schedule;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 按轮推进的任务调度器；每个维度的主线程、各异步车道以及全服、客户端车道各有一个，经 {@link Schedulers} 取得。功能：
 * <ul>
 * <li>{@link #schedule}：一次性任务，delay 轮后执行；在轮外提交时 delay 为 0 指即将到来的那一轮，在轮内提交时最早下一轮；同一轮按提交顺序执行</li>
 * <li>{@link #scheduleAt}/{@link #currentTick}：按绝对轮次排一次性任务，早于可执行的最早一轮时顺延到那一轮；currentTick 在轮内是当前轮，在轮外是即将到来的那一轮，只在调度线程上有意义</li>
 * <li>{@link #submit}：合并任务 {@link CoalescedTask}，排队期间再提交不重复加入；带延迟时窗口内多次提交只在窗口末尾执行一次</li>
 * <li>{@link #repeat}：周期任务，按 {@link Cadence} 的周期与相位执行；自动相位的任务第一次在挂上后最早可执行的那一轮执行，之后在同周期的各相位之间轮流分配；所有者是方块实体或实体时随其移除停止，是作用域时随其结束停止，until 为真时停止</li>
 * <li>{@link Task#setPeriod}：改周期，在该任务下一次执行之后生效</li>
 * <li>任何线程都能提交和取消：在调度线程上直接挂入，其它线程经收件箱在下一轮开头并入；任务只在调度线程上执行</li>
 * <li>{@link #runRound}：推进一轮，只访问本轮到期的条目；周期任务按周期分组、组内按相位分桶，条目常驻不动；一次性任务与超长周期走 256 槽时间轮</li>
 * <li>{@link #close}：关闭后已排的任务全部失效，之后提交的直接是已取消状态，挂在本车道上的无父作用域全部结束；任务抛出的异常交给构造时给的处理器；在车道线程以外、又不在一轮里关闭时记一次误用，作用域的清理推迟到车道线程下一次推进时执行，之后再也不推进的车道上，这些作用域不会结束</li>
 * <li>{@link #foreign}：当前线程是否不是驱动本车道的线程；车道还没被任何线程认领时对所有线程都为假；{@link Schedulers} 建维度与全局车道时就认领服务端或客户端主线程</li>
 * <li>所有者是作用域的任务在作用域结束后最多留在时间轮里 256 轮就被摘掉，周期任务最多留一个周期</li>
 * <li>{@link #forEachScope}：遍历挂在本车道上的无父作用域，只在调度线程上调用</li>
 * </ul>
 */
public final class TickScheduler {

    @FunctionalInterface
    public interface FailureHandler {

        void failed(Throwable error, @Nullable Object owner);
    }

    static final int WHEEL_SIZE = 256;
    static final int MAX_GROUP_PERIOD = 4096;
    static final int SETTLE_DEPTH = 16;
    static final int SETTLE = -2;
    private static final int WHEEL_MASK = WHEEL_SIZE - 1;

    private static final byte ONCE = 0;
    private static final byte REPEAT = 1;
    private static final byte COALESCED = 2;
    private static final byte OWNER_NONE = 0;
    private static final byte OWNER_BLOCK_ENTITY = 1;
    private static final byte OWNER_ENTITY = 2;
    private static final byte OWNER_SCOPE = 3;
    private static final Scope[] NO_ROOTS = new Scope[0];
    private static final String SPILLED = "derivations re-triggered each other more than " + SETTLE_DEPTH + " times in one round";
    private static final String CLOSED_OFF_LANE = "lane closed off its lane thread outside a round; its scopes end when the lane thread next drives it";

    static final TickScheduler CLOSED = closedScheduler();

    private final boolean sticky;
    private final FailureHandler failures;
    private volatile @Nullable Thread affinity;
    private volatile @Nullable Thread roundThread;
    private volatile long round;
    private volatile boolean closed;
    private volatile boolean cleared;
    private int linked;
    private boolean inRound;
    private int depth;
    private long largeCursor;

    private final Entry[] wheelHead = new Entry[WHEEL_SIZE];
    private final Entry[] wheelTail = new Entry[WHEEL_SIZE];
    private Group[] groups = new Group[0];
    private final Int2ObjectOpenHashMap<Group> groupByPeriod = new Int2ObjectOpenHashMap<>();
    private final Object inboxLock = new Object();
    private @Nullable Entry inboxHead;
    private @Nullable Entry inboxTail;
    private @Nullable Long2ObjectOpenHashMap<Poll.PollSignal> polls;
    private int salts;
    private Scope[] roots = NO_ROOTS;
    private int rootCount;

    TickScheduler(boolean sticky, FailureHandler failures) {
        this.sticky = sticky;
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    private static TickScheduler closedScheduler() {
        TickScheduler s = new TickScheduler(false, (error, owner) -> {});
        s.closed = true;
        return s;
    }

    public Task schedule(Runnable action) {
        return schedule(0, null, action);
    }

    public Task schedule(int delay, Runnable action) {
        return schedule(delay, null, action);
    }

    public Task schedule(int delay, @Nullable Object owner, Runnable action) {
        Entry e = new Entry(this, ONCE, owner, Objects.requireNonNull(action, "action"), null);
        e.delay = Math.max(0, delay);
        enqueue(e);
        return e;
    }

    public Task scheduleAt(long tick, @Nullable Object owner, Runnable action) {
        Entry e = new Entry(this, ONCE, owner, Objects.requireNonNull(action, "action"), null);
        e.delay = -1;
        e.due = tick;
        enqueue(e);
        return e;
    }

    public long currentTick() {
        return inRound ? round : round + 1;
    }

    public Task repeat(Cadence cadence, Runnable action) {
        return repeat(cadence, null, action);
    }

    public Task repeat(Cadence cadence, @Nullable Object owner, Runnable action) {
        RepeatEntry e = new RepeatEntry(this, owner, Objects.requireNonNull(action, "action"), null, null, cadence);
        enqueue(e);
        return e;
    }

    @SuppressWarnings("unchecked")
    public <O> Task repeat(Cadence cadence, O owner, Consumer<? super O> action, @Nullable Predicate<? super O> until) {
        RepeatEntry e = new RepeatEntry(this, Objects.requireNonNull(owner, "owner"), null, (Consumer<Object>) Objects.requireNonNull(action, "action"),
                (Predicate<Object>) until, cadence);
        enqueue(e);
        return e;
    }

    public void submit(CoalescedTask task) {
        task.submitTo(this, 0);
    }

    public void submit(CoalescedTask task, int delay) {
        task.submitTo(this, Math.max(0, delay));
    }

    void settle(CoalescedTask task) {
        task.submitTo(this, SETTLE);
    }

    boolean sticky() {
        return sticky;
    }

    public boolean foreign() {
        Thread driver = sticky ? affinity : roundThread;
        return driver != null && driver != Thread.currentThread();
    }

    public long round() {
        return round;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        if (!sticky || inRound && onDriverThread()) return;
        Thread driver = affinity;
        if (driver == null || driver == Thread.currentThread()) {
            clear();
            return;
        }
        if (roundThread == null && !cleared) Scope.anomaly(CLOSED_OFF_LANE, this);
    }

    void runRound() {
        if (closed) {
            if (!cleared) clear();
            return;
        }
        Thread t = Thread.currentThread();
        if (sticky && affinity != t) affinity = t;
        roundThread = t;
        inRound = true;
        long r = round + 1;
        round = r;
        try {
            drainInbox(r);
            Group[] gs = groups;
            for (Group g : gs) {
                if (closed) break;
                runBucket(g, (int) (r % g.period), r);
            }
            if (!closed) runWheel((int) (r & WHEEL_MASK), r);
        } finally {
            inRound = false;
            depth = 0;
            roundThread = null;
            if (closed) clear();
        }
    }

    boolean advanceIfIdle() {
        if (closed) return true;
        long r = round + 1;
        synchronized (inboxLock) {
            if (inboxHead != null) return false;
        }
        if (linked != 0) {
            for (Group g : groups) {
                if (g.head[(int) (r % g.period)] != null) return false;
            }
            for (Entry e = wheelHead[(int) (r & WHEEL_MASK)]; e != null; e = e.next) {
                if (e.due <= r) return false;
            }
        }
        round = r;
        return true;
    }

    void clear() {
        cleared = true;
        Arrays.fill(wheelHead, null);
        Arrays.fill(wheelTail, null);
        groups = new Group[0];
        groupByPeriod.clear();
        synchronized (inboxLock) {
            inboxHead = null;
            inboxTail = null;
        }
        linked = 0;
        polls = null;
        Scope[] ending = roots;
        int n = rootCount;
        roots = NO_ROOTS;
        rootCount = 0;
        for (int i = n - 1; i >= 0; i--) {
            ending[i].slot = -1;
            ending[i].end();
        }
    }

    boolean adopt(Scope root) {
        if (closed) return false;
        if (rootCount == roots.length) roots = Arrays.copyOf(roots, Math.max(4, rootCount * 2));
        root.slot = rootCount;
        roots[rootCount++] = root;
        return true;
    }

    void release(Scope root) {
        int i = root.slot;
        if (i < 0 || i >= rootCount || roots[i] != root) return;
        int last = --rootCount;
        Scope moved = roots[last];
        roots[last] = null;
        if (moved != root) {
            roots[i] = moved;
            moved.slot = i;
        }
        root.slot = -1;
        if (last < roots.length >> 2 && roots.length > 64) roots = Arrays.copyOf(roots, roots.length >> 1);
    }

    Poll.PollSignal poll(int period, int phase) {
        if (closed) return new Poll.PollSignal(this, period, phase);
        var signals = polls;
        if (signals == null) polls = signals = new Long2ObjectOpenHashMap<>();
        long key = (long) period << 32 | phase;
        var signal = signals.get(key);
        if (signal == null) signals.put(key, signal = new Poll.PollSignal(this, period, phase));
        return signal;
    }

    int nextSalt() {
        return salts++;
    }

    void claim() {
        claim(Thread.currentThread());
    }

    void claim(Thread thread) {
        if (sticky && affinity == null) affinity = thread;
    }

    public void forEachScope(Consumer<? super Scope> action) {
        Scope[] snapshot = Arrays.copyOf(roots, rootCount);
        for (Scope root : snapshot) action.accept(root);
    }

    int linkedCount() {
        return linked;
    }

    int groupCount() {
        return groups.length;
    }

    private boolean onDriverThread() {
        Thread t = Thread.currentThread();
        return sticky ? t == affinity : t == roundThread;
    }

    private void enqueue(Entry e) {
        if (closed) {
            e.active = false;
            return;
        }
        if (onDriverThread()) {
            link(e, round + 1);
            return;
        }
        pushInbox(e);
    }

    private void pushInbox(Entry e) {
        synchronized (inboxLock) {
            e.inboxNext = null;
            if (inboxTail == null) inboxHead = e;
            else inboxTail.inboxNext = e;
            inboxTail = e;
        }
    }

    void enqueueCoalesced(CoalescedTask task, int delay) {
        boolean settle = delay == SETTLE;
        if (onDriverThread()) {
            Entry e = task.entryForLink(this);
            e.delay = settle ? 0 : delay;
            int hop = depth + 1;
            if (settle && inRound && !task.lagging) {
                if (hop <= SETTLE_DEPTH) {
                    e.hop = (byte) hop;
                    link(e, round);
                    return;
                }
                task.lagging = true;
                e.hop = 0;
                link(e, round + 1);
                Scope.anomaly(SPILLED, e.owner);
                return;
            }
            e.hop = 0;
            link(e, round + 1);
            return;
        }
        Entry e = task.entry;
        e.delay = settle ? 0 : delay;
        pushInbox(e);
    }

    private void drainInbox(long r) {
        Entry e;
        synchronized (inboxLock) {
            e = inboxHead;
            inboxHead = null;
            inboxTail = null;
        }
        while (e != null) {
            Entry next = e.inboxNext;
            e.inboxNext = null;
            if (e.kind == COALESCED) {
                CoalescedTask task = e.coalesced;
                if (task != null && task.queuedIn() == this && task.entry == e) {
                    int delay = e.delay;
                    Entry linkable = task.entryForLink(this);
                    linkable.delay = delay;
                    linkable.hop = 0;
                    link(linkable, r);
                }
            } else if (e.active) {
                link(e, r);
            }
            e = next;
        }
    }

    private void link(Entry e, long base) {
        e.scheduler = this;
        if (e.kind == REPEAT) {
            RepeatEntry re = (RepeatEntry) e;
            re.notBefore = base;
            placeRepeat(re, base);
        } else {
            e.due = e.delay < 0 ? Math.max(e.due, base) : base + e.delay;
            wheelAppend(e);
        }
    }

    private void placeRepeat(RepeatEntry e, long base) {
        int p = e.period;
        if (e.fresh) {
            e.fresh = false;
            if (e.autoPhase && p > 1) {
                e.group = null;
                e.due = base;
                wheelAppend(e);
                return;
            }
        }
        if (p > MAX_GROUP_PERIOD) {
            long phase = e.autoPhase ? Math.floorMod(largeCursor++, p) : e.phase;
            e.autoPhase = false;
            e.phase = (int) phase;
            e.group = null;
            e.due = base + Math.floorMod(phase - base, (long) p);
            wheelAppend(e);
            return;
        }
        Group g = groupByPeriod.get(p);
        if (g == null) {
            g = new Group(p);
            groupByPeriod.put(p, g);
            Group[] grown = Arrays.copyOf(groups, groups.length + 1);
            grown[groups.length] = g;
            Arrays.sort(grown, (a, b) -> Integer.compare(a.period, b.period));
            groups = grown;
        }
        int phase = e.autoPhase ? g.nextPhase() : e.phase;
        e.autoPhase = false;
        e.group = g;
        g.append(e, phase);
        e.linked = true;
        linked++;
    }

    private void wheelAppend(Entry e) {
        int slot = (int) (e.due & WHEEL_MASK);
        Entry tail = wheelTail[slot];
        e.next = null;
        if (tail == null) wheelHead[slot] = e;
        else tail.next = e;
        wheelTail[slot] = e;
        e.linked = true;
        linked++;
    }

    private void runBucket(Group g, int bucket, long r) {
        RepeatEntry prev = null;
        RepeatEntry e = g.head[bucket];
        while (e != null) {
            if (e.notBefore > r) {
                prev = e;
                e = (RepeatEntry) e.next;
                continue;
            }
            if (!e.active || ownerGone(e) || e.until != null && e.until.test(e.owner)) {
                e.active = false;
                e = g.unlink(bucket, prev, e);
                linked--;
                continue;
            }
            run(e);
            if (closed) return;
            if (!e.active) {
                e = g.unlink(bucket, prev, e);
                linked--;
                continue;
            }
            int np = e.pendingPeriod;
            if (np != 0) {
                e.pendingPeriod = 0;
                RepeatEntry next = g.unlink(bucket, prev, e);
                linked--;
                e.period = np;
                e.autoPhase = false;
                e.phase = (int) Math.floorMod(r, (long) np);
                e.notBefore = r + 1;
                placeRepeat(e, r + 1);
                e = next;
                continue;
            }
            prev = e;
            e = (RepeatEntry) e.next;
        }
    }

    private void runWheel(int slot, long r) {
        Entry prev = null;
        Entry e = wheelHead[slot];
        while (e != null) {
            if (e.due > r) {
                if (!e.active || e.ownerKind == OWNER_SCOPE && !((Scope) e.owner).isActive()) {
                    e = discard(slot, prev, e);
                    continue;
                }
                prev = e;
                e = e.next;
                continue;
            }
            Entry next = e.next;
            if (prev == null) wheelHead[slot] = next;
            else prev.next = next;
            if (wheelTail[slot] == e) wheelTail[slot] = prev;
            e.next = null;
            e.linked = false;
            linked--;
            switch (e.kind) {
                case ONCE -> {
                    if (e.active) {
                        e.active = false;
                        if (!ownerGone(e)) run(e);
                    }
                }
                case COALESCED -> {
                    CoalescedTask task = e.coalesced;
                    if (e.active && task != null && task.entry == e && task.dequeue(this) && !ownerGone(e)) run(e);
                }
                default -> runLarge((RepeatEntry) e, r);
            }
            if (closed) return;
            e = prev == null ? wheelHead[slot] : prev.next;
        }
    }

    private Entry discard(int slot, @Nullable Entry prev, Entry e) {
        Entry next = e.next;
        if (prev == null) wheelHead[slot] = next;
        else prev.next = next;
        if (wheelTail[slot] == e) wheelTail[slot] = prev;
        e.next = null;
        e.linked = false;
        linked--;
        CoalescedTask task = e.coalesced;
        if (task != null && task.entry == e) task.dequeue(this);
        e.active = false;
        return next;
    }

    private void runLarge(RepeatEntry e, long r) {
        if (!e.active || ownerGone(e) || e.until != null && e.until.test(e.owner)) {
            e.active = false;
            return;
        }
        run(e);
        if (!e.active || closed) return;
        int np = e.pendingPeriod;
        if (np != 0) {
            e.pendingPeriod = 0;
            e.period = np;
            e.autoPhase = false;
            e.phase = (int) Math.floorMod(r, (long) np);
            e.notBefore = r + 1;
            placeRepeat(e, r + 1);
            return;
        }
        if (e.autoPhase) {
            e.notBefore = r + 1;
            placeRepeat(e, r + 1);
            return;
        }
        e.due = r + e.period;
        wheelAppend(e);
    }

    private void run(Entry e) {
        depth = e.hop;
        try {
            e.invoke();
        } catch (Throwable t) {
            failures.failed(t, e.owner);
        }
    }

    private static boolean ownerGone(Entry e) {
        return switch (e.ownerKind) {
            case OWNER_BLOCK_ENTITY -> ((BlockEntity) e.owner).isRemoved();
            case OWNER_ENTITY -> ((Entity) e.owner).isRemoved();
            case OWNER_SCOPE -> !((Scope) e.owner).isActive();
            default -> false;
        };
    }

    private static byte ownerKind(@Nullable Object owner) {
        if (owner instanceof BlockEntity) return OWNER_BLOCK_ENTITY;
        if (owner instanceof Entity) return OWNER_ENTITY;
        if (owner instanceof Scope) return OWNER_SCOPE;
        return OWNER_NONE;
    }

    static Entry coalescedEntry(CoalescedTask task, @Nullable Object owner, Runnable action) {
        return new Entry(CLOSED, COALESCED, owner, action, task);
    }

    static final class Group {

        final int period;
        final RepeatEntry[] head;
        final RepeatEntry[] tail;
        private int cursor;

        Group(int period) {
            this.period = period;
            this.head = new RepeatEntry[period];
            this.tail = new RepeatEntry[period];
        }

        int nextPhase() {
            int p = cursor;
            cursor = p + 1 == period ? 0 : p + 1;
            return p;
        }

        void append(RepeatEntry e, int bucket) {
            e.phase = bucket;
            e.next = null;
            RepeatEntry t = tail[bucket];
            if (t == null) head[bucket] = e;
            else t.next = e;
            tail[bucket] = e;
        }

        RepeatEntry unlink(int bucket, @Nullable RepeatEntry prev, RepeatEntry e) {
            RepeatEntry next = (RepeatEntry) e.next;
            if (prev == null) head[bucket] = next;
            else prev.next = next;
            if (tail[bucket] == e) tail[bucket] = prev;
            e.next = null;
            e.linked = false;
            e.group = null;
            return next;
        }
    }

    static class Entry implements Task {

        private static final VarHandle ACTIVE;

        static {
            try {
                ACTIVE = MethodHandles.lookup().findVarHandle(Entry.class, "active", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final byte kind;
        final byte ownerKind;
        byte hop;
        boolean active = true;
        boolean linked;
        final @Nullable Object owner;
        final @Nullable Runnable action;
        final @Nullable CoalescedTask coalesced;
        TickScheduler scheduler;
        int delay;
        long due;
        @Nullable
        Entry next;
        @Nullable
        Entry inboxNext;

        Entry(TickScheduler scheduler, byte kind, @Nullable Object owner, @Nullable Runnable action, @Nullable CoalescedTask coalesced) {
            this.scheduler = scheduler;
            this.kind = kind;
            this.owner = owner;
            this.ownerKind = ownerKind(owner);
            this.action = action;
            this.coalesced = coalesced;
        }

        void invoke() {
            action.run();
        }

        @Override
        public boolean isActive() {
            return (boolean) ACTIVE.getAcquire(this) && !scheduler.closed && (ownerKind != OWNER_SCOPE || ((Scope) owner).isActive());
        }

        @Override
        public void cancel() {
            ACTIVE.setRelease(this, false);
        }

        @Override
        public int period() {
            return 0;
        }

        @Override
        public void setPeriod(int period) {
            throw new IllegalStateException("not a repeating task");
        }
    }

    static final class RepeatEntry extends Entry {

        final @Nullable Consumer<Object> consumer;
        final @Nullable Predicate<Object> until;
        volatile int pendingPeriod;
        int period;
        int phase;
        boolean autoPhase;
        boolean fresh = true;
        long notBefore;
        @Nullable
        Group group;

        RepeatEntry(TickScheduler scheduler, @Nullable Object owner, @Nullable Runnable action, @Nullable Consumer<Object> consumer,
                    @Nullable Predicate<Object> until, Cadence cadence) {
            super(scheduler, REPEAT, owner, action, null);
            this.consumer = consumer;
            this.until = until;
            this.period = cadence.period();
            this.autoPhase = cadence.isAutoPhase();
            this.phase = autoPhase ? 0 : cadence.phase();
        }

        @Override
        void invoke() {
            if (consumer != null) consumer.accept(owner);
            else action.run();
        }

        @Override
        public int period() {
            int p = pendingPeriod;
            return p != 0 ? p : period;
        }

        @Override
        public void setPeriod(int period) {
            int p = Math.max(1, period);
            pendingPeriod = p == this.period ? 0 : p;
        }
    }
}
