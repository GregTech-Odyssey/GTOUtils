package com.suried.gtoutils.level;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * 最近取过的 4 个区块的缓存，供多个线程无锁共用。功能：
 * <ul>
 * <li>{@link #get}：按打包坐标找，命中返回缓存的值，否则返回 null</li>
 * <li>{@link #put}：轮流覆盖 4 个槽位；坐标和值放在同一个不可变对象里，并发读写时只会读到某一次完整写入的坐标和值，不会读到错位的一对</li>
 * <li>{@link #clear}：清空全部槽位</li>
 * </ul>
 */
@ApiStatus.Internal
public final class RecentChunks<C> {

    private record Slot<C>(long key, C value) {}

    @SuppressWarnings("unchecked")
    private final Slot<C>[] slots = new Slot[4];
    private int next;

    public @Nullable C get(long key) {
        Slot<C>[] s = slots;
        for (int i = 0; i < 4; i++) {
            Slot<C> slot = s[i];
            if (slot != null && slot.key == key) return slot.value;
        }
        return null;
    }

    public void put(long key, C value) {
        int i = next;
        next = (i + 1) & 3;
        slots[i] = new Slot<>(key, value);
    }

    public void clear() {
        Arrays.fill(slots, null);
    }
}
