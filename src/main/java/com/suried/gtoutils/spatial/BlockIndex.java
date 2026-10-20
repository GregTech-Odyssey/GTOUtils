package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一个维度内按方块坐标登记对象的索引：世界按 256³ 的区域分片放进哈希表，每个区域一棵 {@link BlockOctree}，
 * 查询只访问与范围相交的区域，树深 8 层而不是覆盖全世界的 26 层。功能：
 * <ul>
 * <li>{@link #put}：登记，同一坐标已有对象时替换；世界范围外的坐标不登记并返回 false</li>
 * <li>{@link #remove(int, int, int)}：按坐标注销；{@link #remove(int, int, int, Object)}：只有该坐标登记的正是这个对象时才注销，
 * 防止方块替换或区块重载时旧对象把新对象注销掉；区域清空后释放</li>
 * <li>{@link #get}/{@link #query}/{@link #findAny}/{@link #findNearest}/{@link #forEach}：按坐标或按 {@link BlockRange}（球形或长方体）查询，
 * 跨区域的结果与单棵树完全一致，最近查询的并列规则同 {@link BlockOctree#findNearest}</li>
 * <li>{@link #version()}：每次登记内容变化加一，使用方据此判断自己缓存的查询结果是否过期</li>
 * <li>不是线程安全的，只在拥有该维度的线程上使用</li>
 * </ul>
 */
public final class BlockIndex<T> {

    public static final int MIN_XZ = -(1 << 25);
    public static final int MAX_XZ = (1 << 25) - 1;
    public static final int MIN_Y = -2048;
    public static final int MAX_Y = 2047;

    static final int REGION_SHIFT = 8;
    static final int REGION_SIZE = 1 << REGION_SHIFT;

    private final Long2ObjectOpenHashMap<BlockOctree<T>> regions = new Long2ObjectOpenHashMap<>();
    private final ArrayList<BlockOctree<T>> scratch = new ArrayList<>();
    private int depth;
    private int size;
    private int version;

    private static long regionKey(int rx, int ry, int rz) {
        return ((long) (rx + (1 << 17)) << 26) | ((long) (rz + (1 << 17)) << 8) | (ry + 128);
    }

    private BlockOctree<T> region(int x, int y, int z) {
        return regions.get(regionKey(x >> REGION_SHIFT, y >> REGION_SHIFT, z >> REGION_SHIFT));
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= MIN_XZ && x <= MAX_XZ && z >= MIN_XZ && z <= MAX_XZ && y >= MIN_Y && y <= MAX_Y;
    }

    public boolean put(int x, int y, int z, T value) {
        if (!inBounds(x, y, z)) return false;
        int rx = x >> REGION_SHIFT, ry = y >> REGION_SHIFT, rz = z >> REGION_SHIFT;
        long key = regionKey(rx, ry, rz);
        BlockOctree<T> tree = regions.get(key);
        if (tree == null) {
            int ox = rx << REGION_SHIFT, oy = ry << REGION_SHIFT, oz = rz << REGION_SHIFT;
            tree = new BlockOctree<>(ox, oy, oz, ox + REGION_SIZE - 1, oy + REGION_SIZE - 1, oz + REGION_SIZE - 1);
            regions.put(key, tree);
        }
        T old = tree.get(x, y, z);
        if (old == value) return true;
        if (old != null) tree.remove(x, y, z);
        else size++;
        tree.insert(x, y, z, value);
        version++;
        return true;
    }

    public boolean put(BlockPos pos, T value) {
        return put(pos.getX(), pos.getY(), pos.getZ(), value);
    }

    public T remove(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        long key = regionKey(x >> REGION_SHIFT, y >> REGION_SHIFT, z >> REGION_SHIFT);
        BlockOctree<T> tree = regions.get(key);
        if (tree == null) return null;
        T removed = tree.remove(x, y, z);
        if (removed == null) return null;
        size--;
        version++;
        if (tree.isEmpty()) regions.remove(key);
        return removed;
    }

    public T remove(BlockPos pos) {
        return remove(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean remove(int x, int y, int z, T expected) {
        if (expected == null || get(x, y, z) != expected) return false;
        remove(x, y, z);
        return true;
    }

    public boolean remove(BlockPos pos, T expected) {
        return remove(pos.getX(), pos.getY(), pos.getZ(), expected);
    }

    public T get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        BlockOctree<T> tree = region(x, y, z);
        return tree == null ? null : tree.get(x, y, z);
    }

    public T get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    private List<BlockOctree<T>> regionsIn(int x, int y, int z, BlockRange range) {
        List<BlockOctree<T>> out = depth == 0 ? scratch : new ArrayList<>();
        out.clear();
        if (range.isEmpty() || size == 0) return out;
        long minX = Math.max((long) x - range.radiusX(), MIN_XZ), maxX = Math.min((long) x + range.radiusX(), MAX_XZ);
        long minY = Math.max((long) y - range.radiusY(), MIN_Y), maxY = Math.min((long) y + range.radiusY(), MAX_Y);
        long minZ = Math.max((long) z - range.radiusZ(), MIN_XZ), maxZ = Math.min((long) z + range.radiusZ(), MAX_XZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) return out;
        int rx0 = (int) minX >> REGION_SHIFT, rx1 = (int) maxX >> REGION_SHIFT;
        int ry0 = (int) minY >> REGION_SHIFT, ry1 = (int) maxY >> REGION_SHIFT;
        int rz0 = (int) minZ >> REGION_SHIFT, rz1 = (int) maxZ >> REGION_SHIFT;
        long cells = (long) (rx1 - rx0 + 1) * (ry1 - ry0 + 1) * (rz1 - rz0 + 1);
        if (cells > regions.size()) {
            for (Long2ObjectMap.Entry<BlockOctree<T>> e : regions.long2ObjectEntrySet()) {
                long k = e.getLongKey();
                int ry = (int) (k & 0xFF) - 128;
                int rz = (int) ((k >>> 8) & 0x3FFFF) - (1 << 17);
                int rx = (int) (k >>> 26) - (1 << 17);
                if (rx >= rx0 && rx <= rx1 && ry >= ry0 && ry <= ry1 && rz >= rz0 && rz <= rz1) out.add(e.getValue());
            }
            return out;
        }
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                for (int ry = ry0; ry <= ry1; ry++) {
                    BlockOctree<T> tree = regions.get(regionKey(rx, ry, rz));
                    if (tree != null) out.add(tree);
                }
            }
        }
        return out;
    }

    public void query(int x, int y, int z, BlockRange range, Predicate<? super T> filter, Consumer<? super T> sink) {
        List<BlockOctree<T>> trees = regionsIn(x, y, z, range);
        depth++;
        try {
            for (int i = 0, n = trees.size(); i < n; i++) trees.get(i).query(x, y, z, range, filter, sink);
        } finally {
            depth--;
        }
    }

    public int query(int x, int y, int z, BlockRange range, Predicate<? super T> filter, Collection<? super T> out) {
        int before = out.size();
        List<BlockOctree<T>> trees = regionsIn(x, y, z, range);
        depth++;
        try {
            for (int i = 0, n = trees.size(); i < n; i++) trees.get(i).query(x, y, z, range, filter, out);
        } finally {
            depth--;
        }
        return out.size() - before;
    }

    public int query(BlockPos center, BlockRange range, Predicate<? super T> filter, Collection<? super T> out) {
        return query(center.getX(), center.getY(), center.getZ(), range, filter, out);
    }

    public T findAny(int x, int y, int z, BlockRange range, Predicate<? super T> filter) {
        List<BlockOctree<T>> trees = regionsIn(x, y, z, range);
        depth++;
        try {
            for (int i = 0, n = trees.size(); i < n; i++) {
                T v = trees.get(i).findAny(x, y, z, range, filter);
                if (v != null) return v;
            }
            return null;
        } finally {
            depth--;
        }
    }

    public T findAny(BlockPos center, BlockRange range, Predicate<? super T> filter) {
        return findAny(center.getX(), center.getY(), center.getZ(), range, filter);
    }

    public T findNearest(int x, int y, int z, BlockRange range, Predicate<? super T> filter) {
        List<BlockOctree<T>> trees = regionsIn(x, y, z, range);
        depth++;
        try {
            T best = null;
            long bd2 = 0;
            int bx = 0, by = 0, bz = 0;
            for (int i = 0, n = trees.size(); i < n; i++) {
                BlockOctree<T> tree = trees.get(i);
                T v = tree.findNearest(x, y, z, range, filter);
                if (v == null) continue;
                long d2 = tree.lastNearestD2();
                int vx = tree.lastNearestX(), vy = tree.lastNearestY(), vz = tree.lastNearestZ();
                if (best == null || d2 < bd2 || (d2 == bd2 && (vx != bx ? vx < bx : vy != by ? vy < by : vz < bz))) {
                    best = v;
                    bd2 = d2;
                    bx = vx;
                    by = vy;
                    bz = vz;
                }
            }
            return best;
        } finally {
            depth--;
        }
    }

    public T findNearest(BlockPos center, BlockRange range, Predicate<? super T> filter) {
        return findNearest(center.getX(), center.getY(), center.getZ(), range, filter);
    }

    public void forEach(Consumer<? super T> action) {
        for (BlockOctree<T> tree : regions.values()) tree.forEach(action);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int version() {
        return version;
    }

    public void clear() {
        if (size == 0) return;
        regions.clear();
        size = 0;
        version++;
    }

    int regionCount() {
        return regions.size();
    }
}
