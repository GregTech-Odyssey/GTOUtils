package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

/**
 * 拥有者手里的作用域：比 {@link Scope} 多一个结束的能力。功能：
 * <ul>
 * <li>{@link #root}：建一个无父作用域，绑定一条车道</li>
 * <li>{@link #dispose}：结束本作用域；幂等</li>
 * </ul>
 */
public final class OwnedScope extends Scope {

    OwnedScope(Object label, TickScheduler lane, @Nullable Scope parent) {
        super(label, lane, parent);
    }

    public static OwnedScope root(String name, TickScheduler lane) {
        return new OwnedScope(name, lane, null);
    }

    public void dispose() {
        end();
    }
}
