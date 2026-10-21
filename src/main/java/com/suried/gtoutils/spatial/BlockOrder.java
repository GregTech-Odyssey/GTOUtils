package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 把带坐标的对象排成原版逐格遍历的顺序，用索引替代逐格扫描时保持"先找到谁"的结果不变。功能：
 * <ul>
 * <li>{@link #betweenClosed}/{@link #sortBetweenClosed}：{@code BlockPos.betweenClosed} 的顺序，x 最快、其次 y、z 最慢，都从小到大</li>
 * <li>{@link #withinManhattan}/{@link #sortWithinManhattan}：{@code BlockPos.withinManhattan} 的顺序，先按到中心的曼哈顿距离，
 * 再按 x、y 从小到大，z 偏移为正的排在为负的前面</li>
 * <li>{@link #sortWithinManhattan} 在条目不超过 2^20 个且坐标都在中心 ±63 格内时用打包长整数排序，不比较对象；
 * {@link #sortBetweenClosed} 在条目不超过 2^20 个且三轴跨度合计不超过 43 位时同样打包排序；其余情况退回比较器</li>
 * <li>排序稳定性：坐标相同的条目保持原有相对顺序</li>
 * </ul>
 */
public final class BlockOrder {

    private BlockOrder() {}

    public static <T> Comparator<T> betweenClosed(Function<? super T, ? extends BlockPos> pos) {
        return (a, b) -> {
            BlockPos pa = pos.apply(a), pb = pos.apply(b);
            int c = Integer.compare(pa.getZ(), pb.getZ());
            if (c != 0) return c;
            c = Integer.compare(pa.getY(), pb.getY());
            if (c != 0) return c;
            return Integer.compare(pa.getX(), pb.getX());
        };
    }

    public static <T> void sortBetweenClosed(List<T> list, Function<? super T, ? extends BlockPos> pos) {
        int n = list.size();
        if (n < 2) return;
        if (n > (1 << 20) || !sortClosedPacked(list, pos)) list.sort(betweenClosed(pos));
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean sortClosedPacked(List<T> list, Function<? super T, ? extends BlockPos> pos) {
        int n = list.size();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            BlockPos p = pos.apply(list.get(i));
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        int bx = bits((long) maxX - minX), by = bits((long) maxY - minY), bz = bits((long) maxZ - minZ);
        if (bx + by + bz > 43) return false;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = pos.apply(list.get(i));
            long k = ((long) (p.getZ() - minZ) << (by + bx)) | ((long) (p.getY() - minY) << bx) | (p.getX() - minX);
            keys[i] = k << 20 | i;
        }
        Arrays.sort(keys);
        Object[] items = list.toArray();
        for (int i = 0; i < n; i++) {
            list.set(i, (T) items[(int) (keys[i] & 0xFFFFF)]);
        }
        return true;
    }

    private static int bits(long span) {
        return 64 - Long.numberOfLeadingZeros(span);
    }

    public static <T> Comparator<T> withinManhattan(BlockPos center, Function<? super T, ? extends BlockPos> pos) {
        long cx = center.getX(), cy = center.getY(), cz = center.getZ();
        return (a, b) -> {
            BlockPos pa = pos.apply(a), pb = pos.apply(b);
            long ax = pa.getX() - cx, ay = pa.getY() - cy, az = pa.getZ() - cz;
            long bx = pb.getX() - cx, by = pb.getY() - cy, bz = pb.getZ() - cz;
            int c = Long.compare(Math.abs(ax) + Math.abs(ay) + Math.abs(az), Math.abs(bx) + Math.abs(by) + Math.abs(bz));
            if (c != 0) return c;
            c = Long.compare(ax, bx);
            if (c != 0) return c;
            c = Long.compare(ay, by);
            if (c != 0) return c;
            return Long.compare(bz, az);
        };
    }

    public static <T> void sortWithinManhattan(List<T> list, BlockPos center, Function<? super T, ? extends BlockPos> pos) {
        int n = list.size();
        if (n < 2) return;
        if (n <= (1 << 20) && withinPackedRange(list, center, pos)) {
            sortPacked(list, center, pos);
        } else {
            list.sort(withinManhattan(center, pos));
        }
    }

    private static <T> boolean withinPackedRange(List<T> list, BlockPos center, Function<? super T, ? extends BlockPos> pos) {
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int i = 0, n = list.size(); i < n; i++) {
            BlockPos p = pos.apply(list.get(i));
            if (Math.abs((long) p.getX() - cx) > 63 || Math.abs((long) p.getY() - cy) > 63 || Math.abs((long) p.getZ() - cz) > 63) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static <T> void sortPacked(List<T> list, BlockPos center, Function<? super T, ? extends BlockPos> pos) {
        int n = list.size();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = pos.apply(list.get(i));
            int dx = p.getX() - cx, dy = p.getY() - cy, dz = p.getZ() - cz;
            long d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            keys[i] = d << 41 | (long) (dx + 64) << 34 | (long) (dy + 64) << 27 | (long) (64 - dz) << 20 | i;
        }
        Arrays.sort(keys);
        Object[] items = list.toArray();
        for (int i = 0; i < n; i++) {
            list.set(i, (T) items[(int) (keys[i] & 0xFFFFF)]);
        }
    }
}
