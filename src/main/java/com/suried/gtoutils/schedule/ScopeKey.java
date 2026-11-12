package com.suried.gtoutils.schedule;

/**
 * 作用域里一项带类型的服务的键。功能：
 * <ul>
 * <li>{@link #of}：建一个键；同名的两个键互不相同，按对象身份区分</li>
 * <li>{@link Scope#provide}/{@link Scope#lookup}：在作用域里放入服务、沿父作用域向上查找；在派生里查找即自动依赖经过的实例，实例上放入或撤下服务时重算</li>
 * </ul>
 */
public final class ScopeKey<V> {

    private final String name;

    private ScopeKey(String name) {
        this.name = name;
    }

    public static <V> ScopeKey<V> of(String name) {
        return new ScopeKey<>(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "ScopeKey[" + name + "]";
    }
}
