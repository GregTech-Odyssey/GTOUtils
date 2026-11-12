package com.suried.gtoutils.schedule;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 作用域：持有副作用，结束时全部收回；绑定一条车道，只在该车道线程上使用。功能：
 * <ul>
 * <li>{@link #child}：子作用域，父结束时连带结束；只有 child 与 {@link OwnedScope#root} 返回能结束的 {@link OwnedScope}，select、when、each、模块挂载、共享池、{@link ScopeSlot} 交给回调的作用域都不是 OwnedScope，强转后结束不了</li>
 * <li>{@link #onDispose}/{@link #effect}：登记撤销；结束时先把整棵子树标为不活动，再自叶到根、按登记倒序执行，子作用域之间也按建立的倒序；每个作用域只拆一次，撤销里结束祖先或别的作用域、触发信号、新建作用域都是安全的；撤销抛异常记入失败记录并继续；effect 先检查撤销非空再施加，施加抛异常时异常交给调用方、不登记撤销</li>
 * <li>{@link #effectWhile}：条件为真期间施加，变假或作用域结束时撤销；不建子作用域；施加与撤销的失败处理同三参 effect</li>
 * <li>{@link #repeat}/{@link #schedule}/{@link #trigger}：以本作用域为所有者的周期、延迟与合并任务，结束后不再执行，{@link Task#isActive} 随之为假；不占撤销登记，可在长寿命作用域上反复排一次性任务</li>
 * <li>{@link #listen}：信号触发时同步执行，结束时退订；作用域一旦不活动（包括正在拆除、撤销里触发了这个信号）就不再执行</li>
 * <li>{@link #watch}：派生一个值，首次以及之后每次按 equals 变化时把新值交给动作；派生期间读到的 {@link Signal}、{@link Cell} 自动成为依赖，触发后合并重算：在车道的一轮里触发的当轮就重算，一轮里最多连锁 16 层，超出的那个派生此后一律顺延到下一轮（互相触发的派生因此每轮只跑一次）；在轮外触发的在即将到来的那一轮重算</li>
 * <li>{@link #select}：同样派生一个值，值变化时先结束旧的子作用域，再建新的并在里面挂载，新旧不会同时存在</li>
 * <li>{@link #when}：派生一个布尔值，为真时建子作用域，为假时结束；为假期间不占子作用域</li>
 * <li>{@link #each}：派生一张表，按键对账：键同值等保留，值变重建，键消失结束；先结束消失的和值变了的，再给新增的和值变了的建子作用域；键按 equals 判等，源表要用同样的判等方式；表没变时不改动</li>
 * <li>{@link #effect(Supplier, Consumer, Consumer)}：派生一个值并对它施加效果，值按 equals 变化时先撤旧值的效果再施加新值的，值为 null 时没有效果，作用域结束时撤掉当前效果；不建子作用域；施加抛异常记入失败记录、当作没有施加也没见过这个值，任何一个依赖再触发时重试；撤销抛异常（包括作用域已不活动时的撤销）记入失败记录、不外抛，并照常施加新值；派生值是 Boolean 或 Optional 时在值变化时记一次误用（只有 null 表示没有效果，布尔条件用 effectWhile）；effect 默认自己是外部登记的唯一持有者，登记会被第三方清掉时清除方要触发一个效果追踪了的信号</li>
 * <li>所有挂载体的第一个参数都是它所在的作用域；派生函数拿不到作用域，只读</li>
 * <li>派生函数抛异常记日志并保持原状；挂载体抛异常记日志，这次挂到一半的子作用域整个撤回，值再变化时重新挂</li>
 * <li>{@link #pool}/{@link #lease}：建一个随本作用域结束的 {@link SharedPool}；向池借用共享资源，借用方结束时归还</li>
 * <li>{@link #provide}/{@link #lookup}/{@link #require}：按 {@link ScopeKey} 向所属实例（最近的 {@link ScopeKind} 实例或无父作用域）提供带类型的服务，提供方结束时撤下；查找沿父作用域向上，在派生里查找会自动依赖经过的实例，别的实例上的增减不会让它重算；require 找不到时报错</li>
 * <li>在已结束的作用域上登记：副作用不发生，撤销立即执行，任务直接是已取消状态；listen、watch、select、when、each、effect、effectWhile、provide 此时记一次误用，作用域是因车道关闭而结束的不记</li>
 * <li>在派生函数求值期间登记（onDispose、listen、watch、select、when、each、effect、effectWhile、provide、lease、child、pool、建无父作用域）记一次误用，照常执行；挂载体与模块挂载不在派生求值期间，不算</li>
 * <li>线程：登记、建子作用域、借用、提供服务只能在车道线程上做，别的线程上调用直接报错；别的线程上结束作用域会转投到车道、下一轮开头再结束，并在首次发生时带调用栈告警，车道已关闭时直接返回、由车道自己的清理结束它；只能建在由固定线程驱动的车道上，异步车道上建作用域直接报错</li>
 * <li>车道关闭时，挂在它上面的无父作用域全部结束；在已关闭的车道上建的无父作用域一建立就是结束状态</li>
 * <li>{@link #name}：标签的字符串形式；标签不是字符串时每次现算、不缓存；作用域结束后不再引用标签对象</li>
 * <li>{@link #liveCount}：全进程存活作用域数，用于查泄漏；{@link #size}：本作用域连同全部子孙的个数；{@link #dump}：按缩进导出本作用域这棵树，每层最多列 24 个子作用域；{@link #label}：建立时给的标签对象，供诊断按拥有者归类，结束后是字符串 ended</li>
 * <li>{@link #failureCount}/{@link #recentFailures}：全进程累计的失败次数、最近 32 条摘要；撤销、派生、挂载体、模块挂载、信号监听抛出的异常都记在这里；日志按"种类、异常类、抛出处第一帧"去重，首次带完整堆栈，之后只计数，每满 1000 次打一行带计数的摘要</li>
 * <li>{@link #anomalyCount}：全进程累计的误用次数：被转投的跨线程调用、在派生函数里触发信号、登记或建立实例、在已结束的作用域上登记、effect 派生出布尔值、派生一轮里连锁超过 16 层；每一种在首次发生时带调用栈告警并记进最近摘要，之后只计数；系统属性 gtoutils.strictScopes 为 true 时误用直接抛 IllegalStateException</li>
 * </ul>
 */
public abstract sealed class Scope permits OwnedScope, Scope.Inner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger LIVE = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final AtomicInteger ANOMALIES = new AtomicInteger();
    private static final ConcurrentHashMap<String, Boolean> REPORTED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> FAILURE_SITES = new ConcurrentHashMap<>();
    private static final int FAILURE_SITE_LIMIT = 4096;
    private static final int FAILURE_SUMMARY_EVERY = 1000;
    private static final boolean STRICT = Boolean.getBoolean("gtoutils.strictScopes");
    static volatile boolean strictOverride;
    static final String IN_DERIVATION = "scope registration inside a derivation function";
    static final String ON_ENDED = "registration on an ended scope";
    static final String TRUTHY_EFFECT = "effect derived a Boolean or Optional; only null means no effect, use effectWhile for conditions";
    private static final String ENDED = "ended";
    private static final String[] RECENT = new String[32];
    private static final int DUMP_SIBLINGS = 24;
    private static int recentCount;
    private static final Object[] NO_UNDO = new Object[0];
    private static final Scope[] NO_CHILDREN = new Scope[0];
    private static final Object[] NO_SERVICES = new Object[0];
    private static final Signal.Sub[] NO_SUBS = new Signal.Sub[0];

    private Object label;
    private final TickScheduler lane;
    private final @Nullable Scope parent;
    private boolean anchor;
    private boolean active = true;
    private boolean torn;
    int slot = -1;
    private Scope[] children = NO_CHILDREN;
    private int childCount;
    private Object[] undo = NO_UNDO;
    private int undoCount;
    private @Nullable Extras extras;

    Scope(Object label, TickScheduler lane, @Nullable Scope parent) {
        this.label = label;
        this.lane = lane;
        this.parent = parent;
        this.anchor = parent == null;
        guard();
        if (!lane.sticky() && !lane.isClosed()) throw new IllegalStateException("Scope " + this + " needs a lane driven by one thread, async lanes are not supported");
        LIVE.incrementAndGet();
        if (parent == null) {
            if (!lane.adopt(this)) end();
        } else if (parent.active) parent.attach(this);
        else end();
    }

    public static int liveCount() {
        return LIVE.get();
    }

    public static int failureCount() {
        return FAILURES.get();
    }

    public static int anomalyCount() {
        return ANOMALIES.get();
    }

    public static List<String> recentFailures() {
        synchronized (RECENT) {
            int n = Math.min(recentCount, RECENT.length);
            ArrayList<String> out = new ArrayList<>(n);
            for (int i = recentCount - n; i < recentCount; i++) out.add(RECENT[i & RECENT.length - 1]);
            return out;
        }
    }

    static void fail(String what, @Nullable Object where, Throwable error) {
        FAILURES.incrementAndGet();
        String place = String.valueOf(where);
        remember(what + " @ " + place + ": " + error);
        String site = failureSite(what, error);
        AtomicInteger seen = FAILURE_SITES.get(site);
        if (seen == null) {
            if (FAILURE_SITES.size() >= FAILURE_SITE_LIMIT) {
                LOGGER.error("GTOUtils: {} failed in {}", what, place, error);
                return;
            }
            AtomicInteger made = new AtomicInteger();
            seen = FAILURE_SITES.putIfAbsent(site, made);
            if (seen == null) seen = made;
        }
        int n = seen.incrementAndGet();
        if (n == 1) LOGGER.error("GTOUtils: {} failed in {}; later failures of this kind are only counted", what, place, error);
        else if (n % FAILURE_SUMMARY_EVERY == 0) LOGGER.error("GTOUtils: {} failed {} times so far, latest in {}: {}", what, n, place, String.valueOf(error));
    }

    private static String failureSite(String what, Throwable error) {
        StackTraceElement[] trace = error.getStackTrace();
        String top = trace.length == 0 ? "" : String.valueOf(trace[0]);
        return what + '|' + error.getClass().getName() + '|' + top;
    }

    static void anomaly(String what, @Nullable Object where) {
        ANOMALIES.incrementAndGet();
        if (STRICT || strictOverride) throw new IllegalStateException("GTOUtils scope misuse: " + what + " @ " + where);
        if (REPORTED.putIfAbsent(what, Boolean.TRUE) != null) return;
        String place = String.valueOf(where);
        LOGGER.warn("GTOUtils: {} ({}) on thread {}; later occurrences are only counted", what, place, Thread.currentThread().getName(), new Throwable("call site"));
        remember(what + " @ " + place);
    }

    private static void remember(String line) {
        synchronized (RECENT) {
            RECENT[recentCount++ & RECENT.length - 1] = line;
        }
    }

    final void guard() {
        if (lane.foreign()) throw new IllegalStateException("Scope " + this + " is used from thread " + Thread.currentThread().getName() + " instead of its lane thread");
    }

    static void checkFrame(@Nullable Object where) {
        if (Signal.tracking()) anomaly(IN_DERIVATION, where);
    }

    private boolean refuse() {
        if (active) return false;
        if (!lane.isClosed()) anomaly(ON_ENDED, this);
        return true;
    }

    public final String name() {
        Object current = label;
        return current instanceof String name ? name : String.valueOf(current);
    }

    public final Object label() {
        return label;
    }

    final TickScheduler lane() {
        return lane;
    }

    public final boolean isActive() {
        return active;
    }

    public final OwnedScope child(String name) {
        checkFrame(this);
        return new OwnedScope(name, lane, this);
    }

    final Scope inner(Object label) {
        return new Inner(label, lane, this);
    }

    static Scope innerRoot(Object label, TickScheduler lane) {
        return new Inner(label, lane, null);
    }

    final void markAnchor() {
        if (anchor) return;
        anchor = true;
        if (childCount == 0 && undoCount == 0) return;
        for (Scope s = parent; s != null; s = s.parent) {
            if (!s.anchor) continue;
            s.instanceChanged();
            return;
        }
    }

    final void trackInstance() {
        if (!Signal.tracking()) return;
        Extras x = extras();
        Signal s = x.changed;
        if (s == null) x.changed = s = new Signal();
        s.track();
    }

    final void instanceChanged() {
        Extras x = extras;
        if (x != null && x.changed != null) x.changed.emit();
    }

    private Extras extras() {
        Extras x = extras;
        if (x == null) extras = x = new Extras();
        return x;
    }

    public final int size() {
        int n = 1;
        for (int i = 0; i < childCount; i++) {
            Scope c = children[i];
            if (c != null) n += c.size();
        }
        return n;
    }

    public final void dump(StringBuilder out, int maxDepth) {
        dump(out, 0, maxDepth);
    }

    private void dump(StringBuilder out, int depth, int maxDepth) {
        indent(out, depth).append(name()).append(" [");
        if (childCount > 0) out.append(size() - 1).append(" below, ");
        out.append(undoCount).append(" undo");
        int services = extras == null ? 0 : extras.serviceCount;
        if (services > 0) out.append(", ").append(services).append(" services");
        out.append("]\n");
        if (depth >= maxDepth) return;
        int shown = 0;
        int hidden = 0;
        for (int i = 0; i < childCount; i++) {
            Scope c = children[i];
            if (c == null) continue;
            if (shown == DUMP_SIBLINGS) {
                hidden++;
                continue;
            }
            shown++;
            c.dump(out, depth + 1, maxDepth);
        }
        if (hidden > 0) indent(out, depth + 1).append("+ ").append(hidden).append(" more\n");
    }

    private static StringBuilder indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) out.append("  ");
        return out;
    }

    public final void onDispose(Runnable action) {
        Objects.requireNonNull(action, "action");
        guard();
        checkFrame(this);
        undo(action);
    }

    final void undo(Runnable action) {
        if (!active) {
            runQuietly(action);
            return;
        }
        push(action);
    }

    private void push(Object action) {
        int n = undoCount;
        if (n == undo.length) undo = Arrays.copyOf(undo, Math.max(2, n * 2));
        undo[n] = action;
        undoCount = n + 1;
    }

    public final void effect(Runnable apply, Runnable revert) {
        Objects.requireNonNull(revert, "revert");
        guard();
        checkFrame(this);
        if (refuse()) return;
        apply.run();
        if (active) push(revert);
        else runQuietly(revert);
    }

    public final <T> void effect(Supplier<? extends T> derive, Consumer<? super T> apply, Consumer<? super T> revert) {
        Objects.requireNonNull(derive, "derive");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(revert, "revert");
        guard();
        checkFrame(this);
        if (refuse()) return;
        new Applied<T>(this, derive, apply, revert).run();
    }

    public final void effectWhile(BooleanSupplier condition, Runnable apply, Runnable revert) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(revert, "revert");
        guard();
        checkFrame(this);
        if (refuse()) return;
        new Toggled(this, condition, apply, revert).run();
    }

    public final Task repeat(Cadence cadence, Runnable action) {
        Task task = lane.repeat(cadence, this, action);
        if (!active) task.cancel();
        return task;
    }

    public final Task schedule(int delay, Runnable action) {
        Task task = lane.schedule(delay, this, action);
        if (!active) task.cancel();
        return task;
    }

    public final Trigger trigger(int delay, Runnable action) {
        CoalescedTask task = new CoalescedTask(this, action);
        int d = Math.max(0, delay);
        return () -> {
            if (active) lane.submit(task, d);
        };
    }

    public final void listen(Signal signal, Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        guard();
        checkFrame(this);
        if (refuse()) return;
        Listening hook = new Listening(this, listener);
        hook.sub = signal.subscribe(lane, hook);
        push(hook);
    }

    public final <T> void watch(Supplier<? extends T> derive, Consumer<? super T> action) {
        guard();
        checkFrame(this);
        if (refuse()) return;
        new Watch<T>(this, derive, action).run();
    }

    public final <T> void select(Supplier<? extends T> derive, Body<? super T> body) {
        guard();
        checkFrame(this);
        if (refuse()) return;
        new Select<T>(this, derive, body).run();
    }

    public final void when(BooleanSupplier condition, Consumer<Scope> body) {
        guard();
        checkFrame(this);
        if (refuse()) return;
        new When(this, condition, body).run();
    }

    public final <K, V> void each(Supplier<? extends Map<K, ? extends V>> derive, KeyedBody<K, V> body) {
        guard();
        checkFrame(this);
        if (refuse()) return;
        new Each<K, V>(this, derive, body).run();
    }

    public final <K, V> SharedPool<K, V> pool(String name, int grace, SharedPool.Factory<K, V> factory) {
        checkFrame(this);
        return new SharedPool<>(inner(name), grace, factory);
    }

    public final <K, V> SharedPool.Lease<V> lease(SharedPool<K, V> pool, K key) {
        guard();
        checkFrame(this);
        return pool.lease(this, key);
    }

    public final <V> void provide(ScopeKey<V> key, V value) {
        Objects.requireNonNull(value, "value");
        guard();
        checkFrame(this);
        if (refuse()) return;
        Scope host = this;
        while (!host.anchor) host = host.parent;
        host.put(key, value);
        if (host == this) return;
        Scope instance = host;
        push((Runnable) () -> instance.withdraw(key, value));
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public final <V> V lookup(ScopeKey<V> key) {
        for (Scope s = this; s != null; s = s.parent) {
            if (!s.anchor) continue;
            s.trackInstance();
            Extras x = s.extras;
            if (x == null) continue;
            for (int i = x.serviceCount - 1; i >= 0; i--) {
                if (x.services[i * 2] == key) return (V) x.services[i * 2 + 1];
            }
        }
        return null;
    }

    public final <V> V require(ScopeKey<V> key) {
        V value = lookup(key);
        if (value == null) throw new IllegalStateException("No " + key + " in scope " + this);
        return value;
    }

    private void put(ScopeKey<?> key, Object value) {
        Extras x = extras();
        if (x.serviceCount * 2 == x.services.length) x.services = Arrays.copyOf(x.services, Math.max(4, x.services.length * 2));
        x.services[x.serviceCount * 2] = key;
        x.services[x.serviceCount * 2 + 1] = value;
        x.serviceCount++;
        instanceChanged();
    }

    private void withdraw(ScopeKey<?> key, Object value) {
        Extras x = extras;
        if (x == null) return;
        for (int i = x.serviceCount - 1; i >= 0; i--) {
            if (x.services[i * 2] != key || x.services[i * 2 + 1] != value) continue;
            int tail = (x.serviceCount - 1 - i) * 2;
            System.arraycopy(x.services, i * 2 + 2, x.services, i * 2, tail);
            x.serviceCount--;
            x.services[x.serviceCount * 2] = null;
            x.services[x.serviceCount * 2 + 1] = null;
            instanceChanged();
            return;
        }
    }

    @Override
    public String toString() {
        return parent == null ? name() : parent + "/" + name();
    }

    final void end() {
        if (!active) return;
        if (lane.foreign()) {
            if (lane.isClosed()) return;
            anomaly("scope ended off its lane thread", this);
            lane.schedule(0, null, this::end);
            return;
        }
        deactivate();
        teardown();
        if (parent != null) parent.detach(this);
        else lane.release(this);
    }

    private void deactivate() {
        active = false;
        for (int i = 0; i < childCount; i++) {
            Scope c = children[i];
            if (c != null) c.deactivate();
        }
    }

    private void teardown() {
        if (torn) return;
        torn = true;
        Scope[] ending = children;
        int endingCount = childCount;
        children = NO_CHILDREN;
        childCount = 0;
        for (int i = endingCount - 1; i >= 0; i--) {
            Scope c = ending[i];
            if (c == null) continue;
            ending[i] = null;
            c.slot = -1;
            c.teardown();
        }
        Object[] undoing = undo;
        int undoingCount = undoCount;
        undo = NO_UNDO;
        undoCount = 0;
        for (int i = undoingCount - 1; i >= 0; i--) {
            Object r = undoing[i];
            undoing[i] = null;
            runQuietly(r);
        }
        Extras x = extras;
        if (x != null) {
            extras = null;
            Signal s = x.changed;
            if (s != null) s.emit();
        }
        if (!(label instanceof String)) label = ENDED;
        LIVE.decrementAndGet();
    }

    private void attach(Scope child) {
        if (childCount == children.length) makeRoom();
        child.slot = childCount;
        children[childCount++] = child;
    }

    private void makeRoom() {
        int kept = 0;
        for (int i = 0; i < childCount; i++) {
            Scope c = children[i];
            if (c == null) continue;
            if (kept != i) {
                children[kept] = c;
                children[i] = null;
                c.slot = kept;
            }
            kept++;
        }
        childCount = kept;
        if (kept >= children.length - (children.length >> 2)) children = Arrays.copyOf(children, Math.max(2, children.length * 2));
    }

    private void detach(Scope child) {
        int i = child.slot;
        if (i < 0 || i >= childCount || children[i] != child) return;
        children[i] = null;
        child.slot = -1;
        while (childCount > 0 && children[childCount - 1] == null) childCount--;
    }

    private void runQuietly(Object r) {
        try {
            if (r instanceof Hook hook) hook.stop();
            else ((Runnable) r).run();
        } catch (Throwable t) {
            fail("dispose action", this, t);
        }
    }

    @FunctionalInterface
    public interface Trigger {

        void fire();
    }

    @FunctionalInterface
    public interface Body<T> {

        void mount(Scope scope, T value);
    }

    @FunctionalInterface
    public interface KeyedBody<K, V> {

        void mount(Scope scope, K key, V value);
    }

    static final class Inner extends Scope {

        Inner(Object label, TickScheduler lane, @Nullable Scope parent) {
            super(label, lane, parent);
        }
    }

    private static final class Extras {

        Object[] services = NO_SERVICES;
        int serviceCount;
        @Nullable
        Signal changed;
    }

    abstract static class Hook {

        abstract void fire();

        abstract void stop();
    }

    private static final class Listening extends Hook {

        private final Scope owner;
        private final Runnable listener;
        Signal.Sub sub;

        Listening(Scope owner, Runnable listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override
        void fire() {
            if (owner.active) listener.run();
        }

        @Override
        void stop() {
            sub.remove();
        }
    }

    private abstract static class Derivation extends Hook implements Runnable {

        final Scope owner;
        private final int salt;
        private @Nullable CoalescedTask task;
        private Signal.Sub[] subs = NO_SUBS;
        private int subCount;

        Derivation(Scope owner) {
            this.owner = owner;
            this.salt = owner.lane.nextSalt();
            owner.push(this);
        }

        @Override
        public final void run() {
            if (owner.active) evaluate();
        }

        @Override
        final void fire() {
            if (!owner.active) return;
            CoalescedTask t = task;
            if (t == null) task = t = new CoalescedTask(owner, this);
            owner.lane.settle(t);
        }

        @Override
        final void stop() {
            CoalescedTask t = task;
            if (t != null) t.retire();
            for (int i = 0; i < subCount; i++) subs[i].remove();
            subCount = 0;
            subs = NO_SUBS;
            stopped();
        }

        void stopped() {}

        abstract void evaluate();

        abstract Object compute();

        final Object derive() {
            Signal.Frame frame = Signal.enter(owner.lane, salt);
            try {
                return compute();
            } finally {
                try {
                    rewire(frame);
                } finally {
                    Signal.exit(frame);
                }
            }
        }

        final void failed(String what, Throwable error) {
            fail(what, owner, error);
        }

        private void rewire(Signal.Frame frame) {
            int n = frame.count;
            Signal[] deps = frame.deps;
            if (subCount == 0) {
                if (n == 0) return;
                Signal.Sub[] next = new Signal.Sub[n];
                int made = 0;
                try {
                    for (; made < n; made++) next[made] = deps[made].subscribe(owner.lane, this);
                } catch (Throwable t) {
                    for (int i = 0; i < made; i++) next[i].remove();
                    for (int i = 0; i < n; i++) deps[i].unclaimed();
                    throw t;
                }
                subs = next;
                subCount = n;
                return;
            }
            boolean same = n == subCount;
            for (int i = 0; same && i < n; i++) same = subs[i].signal == deps[i];
            if (same) return;
            Signal.Sub[] next = n == 0 ? NO_SUBS : new Signal.Sub[n];
            boolean[] kept = new boolean[subCount];
            boolean[] fresh = new boolean[n];
            try {
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < subCount; j++) {
                        if (!kept[j] && subs[j].signal == deps[i]) {
                            next[i] = subs[j];
                            kept[j] = true;
                            break;
                        }
                    }
                    if (next[i] != null) continue;
                    next[i] = deps[i].subscribe(owner.lane, this);
                    fresh[i] = true;
                }
            } catch (Throwable t) {
                for (int i = 0; i < n; i++) if (fresh[i]) next[i].remove();
                for (int i = 0; i < n; i++) deps[i].unclaimed();
                throw t;
            }
            for (int j = 0; j < subCount; j++) if (!kept[j]) subs[j].remove();
            subs = next;
            subCount = n;
        }
    }

    private static final class Watch<T> extends Derivation {

        private final Supplier<? extends T> source;
        private final Consumer<? super T> action;
        private boolean seen;
        private @Nullable T value;

        Watch(Scope owner, Supplier<? extends T> source, Consumer<? super T> action) {
            super(owner);
            this.source = source;
            this.action = action;
        }

        @Override
        Object compute() {
            return source.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        void evaluate() {
            T next;
            try {
                next = (T) derive();
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            if (seen && Objects.equals(next, value)) return;
            seen = true;
            value = next;
            try {
                action.accept(next);
            } catch (Throwable t) {
                failed("action", t);
            }
        }
    }

    private static class Applied<T> extends Derivation {

        private final @Nullable Supplier<? extends T> source;
        private final @Nullable Consumer<? super T> apply;
        private final @Nullable Consumer<? super T> revert;
        private boolean seen;
        private @Nullable T value;
        private @Nullable T held;

        Applied(Scope owner, @Nullable Supplier<? extends T> source, @Nullable Consumer<? super T> apply, @Nullable Consumer<? super T> revert) {
            super(owner);
            this.source = source;
            this.apply = apply;
            this.revert = revert;
        }

        @Override
        Object compute() {
            return source.get();
        }

        void applyValue(T next) {
            apply.accept(next);
        }

        void revertValue(T old) {
            revert.accept(old);
        }

        void inspect(Object next) {
            if (next instanceof Boolean || next instanceof Optional) anomaly(TRUTHY_EFFECT, owner);
        }

        @Override
        @SuppressWarnings("unchecked")
        final void evaluate() {
            T next;
            try {
                next = (T) derive();
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            if (seen && Objects.equals(next, value)) return;
            seen = true;
            value = next;
            if (next != null) inspect(next);
            T old = held;
            if (old != null) {
                held = null;
                try {
                    revertValue(old);
                } catch (Throwable t) {
                    failed("revert", t);
                }
                if (!owner.active) return;
            }
            if (next == null) return;
            try {
                applyValue(next);
                held = next;
            } catch (Throwable t) {
                failed("apply", t);
                seen = false;
            }
            if (owner.active) return;
            try {
                stopped();
            } catch (Throwable t) {
                failed("revert", t);
            }
        }

        @Override
        final void stopped() {
            T old = held;
            if (old == null) return;
            held = null;
            revertValue(old);
        }
    }

    private static final class Toggled extends Applied<Boolean> {

        private final BooleanSupplier condition;
        private final Runnable on;
        private final Runnable off;

        Toggled(Scope owner, BooleanSupplier condition, Runnable on, Runnable off) {
            super(owner, null, null, null);
            this.condition = condition;
            this.on = on;
            this.off = off;
        }

        @Override
        Object compute() {
            return condition.getAsBoolean() ? Boolean.TRUE : null;
        }

        @Override
        void applyValue(Boolean next) {
            on.run();
        }

        @Override
        void revertValue(Boolean old) {
            off.run();
        }

        @Override
        void inspect(Object next) {}
    }

    private static final class Select<T> extends Derivation {

        private final Supplier<? extends T> source;
        private final Body<? super T> body;
        private @Nullable Scope child;
        private @Nullable T value;

        Select(Scope owner, Supplier<? extends T> source, Body<? super T> body) {
            super(owner);
            this.source = source;
            this.body = body;
        }

        @Override
        Object compute() {
            return source.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        void evaluate() {
            T next;
            try {
                next = (T) derive();
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            Scope previous = child;
            if (previous != null && Objects.equals(next, value)) return;
            value = next;
            if (previous != null) {
                previous.end();
                if (!owner.active) return;
            }
            Scope built = owner.inner("select");
            child = built;
            try {
                body.mount(built, next);
            } catch (Throwable t) {
                failed("body", t);
                built.end();
            }
        }
    }

    private static final class When extends Derivation {

        private final BooleanSupplier condition;
        private final Consumer<Scope> body;
        private @Nullable Scope child;

        When(Scope owner, BooleanSupplier condition, Consumer<Scope> body) {
            super(owner);
            this.condition = condition;
            this.body = body;
        }

        @Override
        Object compute() {
            return condition.getAsBoolean();
        }

        @Override
        void evaluate() {
            boolean open;
            try {
                open = (Boolean) derive();
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            Scope current = child;
            if (open == (current != null)) return;
            if (current != null) {
                child = null;
                current.end();
                return;
            }
            Scope built = owner.inner("when");
            child = built;
            try {
                body.accept(built);
            } catch (Throwable t) {
                failed("body", t);
                built.end();
            }
        }
    }

    private static final class Each<K, V> extends Derivation {

        private final Supplier<? extends Map<K, ? extends V>> source;
        private final KeyedBody<K, V> body;
        private final HashMap<K, Entry<V>> entries = new HashMap<>();

        Each(Scope owner, Supplier<? extends Map<K, ? extends V>> source, KeyedBody<K, V> body) {
            super(owner);
            this.source = source;
            this.body = body;
        }

        @Override
        Object compute() {
            return source.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        void evaluate() {
            Map<K, ? extends V> next;
            try {
                next = (Map<K, ? extends V>) derive();
            } catch (Throwable t) {
                failed("derivation", t);
                return;
            }
            try {
                if (!entries.isEmpty()) {
                    var held = entries.entrySet().iterator();
                    while (held.hasNext()) {
                        var e = held.next();
                        K key = e.getKey();
                        Entry<V> entry = e.getValue();
                        V now = next.get(key);
                        if (Objects.equals(now, entry.value) && (now != null || next.containsKey(key))) continue;
                        held.remove();
                        entry.scope.end();
                        if (!owner.active) return;
                    }
                }
                if (entries.size() == next.size()) return;
                for (var e : next.entrySet()) {
                    K key = e.getKey();
                    if (entries.containsKey(key)) continue;
                    V value = e.getValue();
                    Scope scope = owner.inner("each");
                    entries.put(key, new Entry<>(value, scope));
                    try {
                        body.mount(scope, key, value);
                    } catch (Throwable t) {
                        fail("body", owner + " [key " + key + "]", t);
                        scope.end();
                    }
                    if (!owner.active) return;
                }
            } catch (Throwable t) {
                failed("reconciliation", t);
            }
        }
    }

    private record Entry<V>(V value, Scope scope) {}
}
