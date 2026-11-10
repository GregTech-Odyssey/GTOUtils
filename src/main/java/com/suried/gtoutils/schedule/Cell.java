package com.suried.gtoutils.schedule;

import java.util.Objects;

/**
 * 带值的依赖信号：读即追踪、写即触发，不必手写成对的 {@link Signal#track} 与 {@link Signal#emit}。功能：
 * <ul>
 * <li>{@link #get}：取值；在派生函数求值期间调用时自动成为该派生的依赖</li>
 * <li>{@link #peek}：取值但不成为依赖</li>
 * <li>{@link #set}：按 equals 比较，值变了才写入并触发，返回是否变了</li>
 * <li>{@link #changed}：值变化时触发的信号，供 {@link Scope#listen} 使用</li>
 * </ul>
 */
public final class Cell<T> {

    private final Signal changed = new Signal();
    private T value;

    private Cell(T value) {
        this.value = value;
    }

    public static <T> Cell<T> of(T initial) {
        return new Cell<>(initial);
    }

    public T get() {
        changed.track();
        return value;
    }

    public T peek() {
        return value;
    }

    public boolean set(T next) {
        if (Objects.equals(value, next)) return false;
        value = next;
        changed.emit();
        return true;
    }

    public Signal changed() {
        return changed;
    }
}
