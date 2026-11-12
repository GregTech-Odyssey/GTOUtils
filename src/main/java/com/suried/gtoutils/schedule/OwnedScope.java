package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 拥有者手里的作用域：比 {@link Scope} 多一个结束的能力。功能：
 * <ul>
 * <li>{@link #root}：建一个无父作用域，绑定一条车道；名字可以给任意对象，用到时才转成字符串，作用域结束后不再引用它；在派生函数求值期间建立记一次误用</li>
 * <li>{@link #dispose}：结束本作用域；幂等</li>
 * <li>只有 {@link Scope#child} 和 root 建出 OwnedScope；回调里拿到的作用域都不是它的实例，强转结束不了</li>
 * </ul>
 */
public final class OwnedScope extends Scope {

    OwnedScope(Object label, TickScheduler lane, @Nullable Scope parent) {
        super(label, lane, parent);
    }

    public static OwnedScope root(String name, TickScheduler lane) {
        checkFrame(name);
        return new OwnedScope(name, lane, null);
    }

    public static OwnedScope root(Object label, TickScheduler lane) {
        Objects.requireNonNull(label, "label");
        checkFrame(label);
        return new OwnedScope(label, lane, null);
    }

    public void dispose() {
        end();
    }
}
