package com.suried.gtoutils.schedule;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 一种作用域：每个拥有者（玩家、维度、屏幕……）各有一个实例，模块登记一次、每个实例建立时自动挂上。功能：
 * <ul>
 * <li>{@link #byIdentity}/{@link #byEquality}：建一种作用域，拥有者按对象身份或按 equals 区分；返回的 {@link Control} 只交给负责开关的一方</li>
 * <li>{@link #install}：登记一个模块；之后建立的每个实例都会挂上它，已经存在的实例立即补挂；同名重复登记报错</li>
 * <li>每个模块挂在实例下自己的子作用域里，它用 {@link Scope#provide} 提供的服务对整个实例可见；某个模块挂载时抛异常只记日志，它已挂上的部分整个撤回，不影响其余模块</li>
 * <li>{@link #get}：拥有者当前的实例，没有时为 null；在派生里调用会随实例的建立与结束自动重算</li>
 * <li>{@link #lookup}/{@link #require}：直接取拥有者实例上的服务；没有实例或没有该服务时，lookup 为 null，require 报错</li>
 * <li>{@link #forEach}/{@link #size}：遍历当前全部实例</li>
 * <li>{@link Control#open}：为拥有者建一个根实例，按登记顺序挂上全部模块</li>
 * <li>{@link Control#bind}：把一个已有的作用域认作拥有者的实例（例如派生里建的子作用域），它结束时自动除名</li>
 * <li>open 与 bind 都会先结束该拥有者此前由 open 建立的实例</li>
 * <li>{@link Control#close}/{@link Control#closeAll}：结束 open 建立的实例</li>
 * </ul>
 */
public final class ScopeKind<T> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @FunctionalInterface
    public interface Module<T> {

        void mount(Scope scope, T owner);
    }

    private record Installed<T>(String id, Module<? super T> module) {}

    private final String name;
    private final Map<T, Scope> live;
    private final Map<T, OwnedScope> roots;
    private final ArrayList<Installed<T>> modules = new ArrayList<>();
    private final Signal changed = new Signal();

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
        modules.add(installed);
        if (live.isEmpty()) return;
        for (var owner : new ArrayList<>(live.keySet())) {
            var scope = live.get(owner);
            if (scope != null) mountSafely(installed, scope, owner);
        }
    }

    @Nullable
    public Scope get(T owner) {
        changed.track();
        return live.get(owner);
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
        OwnedScope room = scope.child(installed.id);
        try {
            installed.module.mount(room, owner);
        } catch (Throwable t) {
            LOGGER.error("GTOUtils: module {} failed to mount on {} {}", installed.id, name, owner, t);
            room.dispose();
        }
    }

    private void attach(T owner, Scope scope) {
        scope.markAnchor();
        live.put(owner, scope);
        scope.onDispose(() -> {
            if (live.get(owner) == scope) {
                live.remove(owner);
                changed.emit();
            }
        });
        changed.emit();
        for (var installed : new ArrayList<>(modules)) mountSafely(installed, scope, owner);
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
            close(owner);
            var root = OwnedScope.root(kind.name + " " + owner, lane);
            kind.roots.put(owner, root);
            kind.attach(owner, root);
            return root;
        }

        public void bind(T owner, Scope scope) {
            if (!scope.isActive()) return;
            close(owner);
            kind.attach(owner, scope);
        }

        public void close(T owner) {
            var root = kind.roots.remove(owner);
            if (root != null) root.dispose();
        }

        public void closeAll() {
            for (var root : new ArrayList<>(kind.roots.values())) root.dispose();
            kind.roots.clear();
        }
    }
}
