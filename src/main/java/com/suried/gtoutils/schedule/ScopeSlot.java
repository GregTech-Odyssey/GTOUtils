package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 可重开的无父作用域槽，给"加载时开、卸载时关"的拥有者用，省掉自己存字段、判空、先关后开。功能：
 * <ul>
 * <li>{@link #open(Object, TickScheduler, Consumer)}：先结束槽里上一个作用域，再在车道上建一个新的无父作用域并在里面执行体；体抛异常记入失败记录，这次挂到一半的作用域整个撤回，槽变空；车道已关闭时不执行体，返回已结束的作用域，槽变空；在车道以外的线程上调用、或车道是异步车道时直接报错，槽里原有的作用域不受影响；在派生函数求值期间调用记一次误用</li>
 * <li>{@link #open(Object, TickScheduler, Object, BiConsumer)}：同上，体额外收一个实参，体可以写成不捕获的静态常量，省掉每次打开新建的转接 lambda</li>
 * <li>返回的作用域与 {@link #current} 都不是 {@link OwnedScope}，强转后结束不了，只能经 {@link #close} 或再次 open 结束</li>
 * <li>{@link #close}：结束槽里的作用域；幂等，槽空、作用域已自行结束、车道已关闭时什么也不做；在别的线程上调用按 {@link OwnedScope#dispose} 的规矩转投</li>
 * <li>{@link #current}/{@link #isOpen}：槽里仍在活动的作用域，没有时为 null；槽字段是 volatile，别的线程上读到的是车道线程最近一次写入的作用域（写入用 release，不加全屏障）</li>
 * </ul>
 */
public final class ScopeSlot {

    private static final BiConsumer<Scope, Consumer<Scope>> PLAIN = (s, body) -> body.accept(s);
    private static final VarHandle SCOPE;

    static {
        try {
            SCOPE = MethodHandles.lookup().findVarHandle(ScopeSlot.class, "scope", Scope.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile @Nullable Scope scope;

    public Scope open(Object label, TickScheduler lane, Consumer<Scope> body) {
        Objects.requireNonNull(body, "body");
        return open(label, lane, body, PLAIN);
    }

    public <A> Scope open(Object label, TickScheduler lane, A arg, BiConsumer<Scope, ? super A> body) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(body, "body");
        Scope opened = begin(label, lane);
        if (!opened.isActive()) return opened;
        try {
            body.accept(opened, arg);
        } catch (Throwable t) {
            Scope.fail("slot body", opened, t);
            opened.end();
        }
        if (!opened.isActive() && SCOPE.get(this) == opened) SCOPE.setRelease(this, null);
        return opened;
    }

    private Scope begin(Object label, TickScheduler lane) {
        if (lane.foreign() || !lane.sticky() && !lane.isClosed()) refuse(label, lane);
        Scope.checkFrame(label);
        Scope previous = (Scope) SCOPE.get(this);
        if (previous != null) {
            SCOPE.setRelease(this, null);
            previous.end();
        }
        Scope opened = Scope.innerRoot(label, lane);
        if (opened.isActive()) SCOPE.setRelease(this, opened);
        return opened;
    }

    private static void refuse(Object label, TickScheduler lane) {
        if (lane.foreign()) throw new IllegalStateException("Scope slot opened from thread " + Thread.currentThread().getName() + " instead of its lane thread");
        throw new IllegalStateException("Scope slot " + label + " needs a lane driven by one thread, async lanes are not supported");
    }

    public void close() {
        Scope current = scope;
        if (current == null) return;
        SCOPE.setRelease(this, null);
        current.end();
    }

    @Nullable
    public Scope current() {
        Scope current = scope;
        return current != null && current.isActive() ? current : null;
    }

    public boolean isOpen() {
        return current() != null;
    }
}
