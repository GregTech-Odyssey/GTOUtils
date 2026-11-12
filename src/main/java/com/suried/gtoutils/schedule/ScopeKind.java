package com.suried.gtoutils.schedule;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 一种作用域：每个拥有者（玩家、维度、屏幕……）各有一个实例，模块登记一次、每个实例建立时自动挂上。功能：
 * <ul>
 * <li>{@link #byIdentity}/{@link #byEquality}：建一种作用域，拥有者按对象身份或按 equals 区分；返回的 {@link Control} 只交给负责开关的一方</li>
 * <li>{@link #install}：登记一个模块；之后建立的每个实例都会挂上它，已经存在的实例立即补挂；同名重复登记报错</li>
 * <li>open 建的实例与模块的子作用域都不是 {@link OwnedScope}，拿到的一方强转后结束不了；每个模块挂在实例下自己的子作用域里，它用 {@link Scope#provide} 提供的服务对整个实例可见；某个模块挂载时抛异常记入失败记录，它已挂上的部分整个撤回，不影响其余模块；模块挂载期间读到的信号不会记成外层派生的依赖</li>
 * <li>{@link #get}：拥有者当前的实例，没有时为 null；在派生里调用会随这个拥有者的实例建立、更换与结束自动重算，别的拥有者的实例结束不会让它重算</li>
 * <li>{@link #lookup}/{@link #require}：直接取拥有者实例上的服务；没有实例或没有该服务时，lookup 为 null，require 报错</li>
 * <li>{@link #forEach}/{@link #size}：遍历当前全部实例</li>
 * <li>{@link Control#open}：为拥有者建一个根实例，按登记顺序挂上全部模块；车道已关闭时得到的是已结束的实例，不留下任何登记</li>
 * <li>{@link Control#bind}：把一个已有的作用域认作拥有者的实例（例如派生里建的子作用域），它结束时自动除名；同一个作用域重复认领什么也不做</li>
 * <li>一个拥有者同一时刻只有一个实例：open 与 bind 都会先结束该拥有者此前的实例，不论它是 open 建的还是 bind 认的</li>
 * <li>{@link Control#close}/{@link Control#closeAll}：结束 open 建立的实例；closeAll 期间又被 open 出来的实例也一并结束</li>
 * <li>同一种作用域的实例只能在同一个线程上建立，另一个线程来建立直接报错；在派生函数里建立实例记一次误用</li>
 * </ul>
 */
public final class ScopeKind<T> {

    @FunctionalInterface
    public interface Module<T> {

        void mount(Scope scope, T owner);
    }

    private record Installed<T>(String id, Module<? super T> module) {}

    private record Label(String kind, Object owner) {

        @Override
        public String toString() {
            return kind + " " + owner;
        }
    }

    private final String name;
    private final Map<T, Scope> live;
    private final Map<T, Scope> roots;
    private final Signal arrived = new Signal();
    @SuppressWarnings("unchecked")
    private Installed<T>[] modules = new Installed[0];
    private @Nullable Thread home;

    private ScopeKind(String name, boolean identity) {
        this.name = name;
        this.live = identity ? new Reference2ObjectLinkedOpenHashMap<>() : new Object2ObjectLinkedOpenHashMap<>();
        this.roots = identity ? new Reference2ObjectLinkedOpenHashMap<>() : new Object2ObjectLinkedOpenHashMap<>();
    }

    public static <T> Control<T> byIdentity(String name) {
        return new Control<>(new ScopeKind<>(name, true));
    }

    public static <T> Control<T> byEquality(String name) {
        return new Control<>(new ScopeKind<>(name, false));
    }

    public String name() {
        return name;
    }

    public void install(String id, Module<? super T> module) {
        for (var m : modules) if (m.id.equals(id)) throw new IllegalStateException("Module " + id + " already installed on scope kind " + name);
        var installed = new Installed<T>(id, module);
        var grown = Arrays.copyOf(modules, modules.length + 1);
        grown[modules.length] = installed;
        modules = grown;
        if (live.isEmpty()) return;
        for (var owner : new ArrayList<>(live.keySet())) {
            var scope = live.get(owner);
            if (scope != null) mountSafely(installed, scope, owner);
        }
    }

    @Nullable
    public Scope get(T owner) {
        Scope scope = live.get(owner);
        if (scope != null) scope.trackInstance();
        else arrived.track();
        return scope;
    }

    @Nullable
    public <V> V lookup(T owner, ScopeKey<V> key) {
        Scope scope = get(owner);
        return scope == null ? null : scope.lookup(key);
    }

    public <V> V require(T owner, ScopeKey<V> key) {
        Scope scope = get(owner);
        if (scope == null) throw new IllegalStateException("No " + name + " scope for " + owner);
        return scope.require(key);
    }

    public void forEach(BiConsumer<? super T, ? super Scope> action) {
        for (var owner : new ArrayList<>(live.keySet())) {
            var scope = live.get(owner);
            if (scope != null) action.accept(owner, scope);
        }
    }

    public int size() {
        return live.size();
    }

    @Override
    public String toString() {
        return "ScopeKind[" + name + "]";
    }

    private void mountSafely(Installed<T> installed, Scope scope, T owner) {
        if (!scope.isActive()) return;
        Scope room = scope.inner(installed.id);
        Signal.Frame muted = Signal.mute();
        try {
            installed.module.mount(room, owner);
        } catch (Throwable t) {
            Scope.fail("module " + installed.id + " mount", room, t);
            room.end();
        } finally {
            Signal.unmute(muted);
        }
    }

    private void enter() {
        Thread thread = Thread.currentThread();
        if (live.isEmpty()) home = thread;
        else if (home != thread) throw new IllegalStateException("Scope kind " + name + " is used from thread " + thread.getName() + " while its instances live on another thread");
        if (Signal.tracking()) Scope.anomaly("scope instance opened inside a derivation function", name);
    }

    private void attach(T owner, Scope scope) {
        scope.markAnchor();
        Scope previous = live.put(owner, scope);
        scope.undo(() -> {
            if (live.get(owner) == scope) live.remove(owner);
            if (roots.get(owner) == scope) roots.remove(owner);
        });
        if (previous == null) arrived.emit();
        else if (previous != scope) previous.end();
        Installed<T>[] mounting = modules;
        for (var installed : mounting) mountSafely(installed, scope, owner);
    }

    public static final class Control<T> {

        private final ScopeKind<T> kind;

        private Control(ScopeKind<T> kind) {
            this.kind = kind;
        }

        public ScopeKind<T> kind() {
            return kind;
        }

        public Scope open(T owner, TickScheduler lane) {
            kind.enter();
            lane.claim();
            close(owner);
            var root = Scope.innerRoot(new Label(kind.name, owner), lane);
            Scope reentered = kind.roots.put(owner, root);
            if (reentered != null) reentered.end();
            kind.attach(owner, root);
            return root;
        }

        public void bind(T owner, Scope scope) {
            if (!scope.isActive() || kind.live.get(owner) == scope) return;
            scope.guard();
            kind.enter();
            close(owner);
            kind.attach(owner, scope);
        }

        public void close(T owner) {
            var root = kind.roots.remove(owner);
            if (root != null) root.end();
        }

        public void closeAll() {
            for (int budget = kind.roots.size() + 16; budget > 0 && !kind.roots.isEmpty(); budget--) {
                var first = kind.roots.values().iterator();
                Scope root = first.next();
                first.remove();
                root.end();
            }
        }
    }
}
