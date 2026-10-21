package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Predicate;

/**
 * 在长方体范围里找满足条件的格子：先用 16³ 区段的调色板排除不可能命中的区段，只逐格读剩下的，结果顺序与原版逐格遍历相同。功能：
 * <ul>
 * <li>{@link #betweenClosed}：按 {@code BlockPos.betweenClosed} 的顺序收集前 limit 个命中的格子，够数后不再读 z 更靠后的区段层</li>
 * <li>{@link #inRange}：同 betweenClosed，范围换成以某格为中心的 {@link BlockRange}（球形或长方体），只判断落在范围内的格子，整段在范围外的区段不读，
 * 顺序是范围外接长方体的 betweenClosed 顺序</li>
 * <li>{@link #withinManhattan}：按 {@code BlockPos.withinManhattan} 的顺序收集前 limit 个命中的格子，区段按到中心的最小曼哈顿距离由近到远处理，
 * 够数后更远的区段不再读；limit 有限时按距离分段推进（8、16、32……），每段只读落在该段距离内的格子，够数就停</li>
 * <li>{@link Sections#seek} 返回非 null 表示整个区段都是这个值（高度范围外、空区块、全空气区段），palette 与 test 都只对它判断，不逐格读</li>
 * <li>palette 必须对所有可能命中的值成立：区段不含满足 palette 的值就整段跳过；格子的值不满足 palette 时不调用 test</li>
 * <li>test 拿到的坐标是复用的可变坐标，只在调用期间有效；收集到的坐标是新建的不可变坐标，按顺序追加到 out，返回追加的个数</li>
 * <li>betweenClosed、inRange 在 limit 有限时先按原版顺序逐格看最前面的 1024 格，够数就停（目标常见时和原版一样早停），不够再按区段扫剩下的部分</li>
 * <li>limit 小于 1、任一半径为负或最小角大于最大角时什么都不收集；范围大到排序键放不下时退回逐格遍历，结果相同</li>
 * </ul>
 */
public final class SectionScan {

    public interface Sections<T> {

        @Nullable
        T seek(int sectionX, int sectionY, int sectionZ);

        boolean mayContain(Predicate<? super T> palette);

        T get(int localX, int localY, int localZ);
    }

    public interface CellTest<T> {

        boolean test(T value, BlockPos pos);
    }

    private static final int MAX_SECTIONS = 1 << 24;
    private static final int HEAD_CELLS = 1024;
    private static final int FIRST_BAND = 8;

    private SectionScan() {}

    public static <T> int betweenClosed(Sections<T> sections, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                        Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        return closed(sections, minX, minY, minZ, maxX, maxY, maxZ, null, 0, 0, 0, palette, test, limit, out);
    }

    public static <T> int inRange(Sections<T> sections, int cx, int cy, int cz, BlockRange range,
                                  Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (range.isEmpty()) return 0;
        int minX = clamp((long) cx - range.radiusX()), maxX = clamp((long) cx + range.radiusX());
        int minY = clamp((long) cy - range.radiusY()), maxY = clamp((long) cy + range.radiusY());
        int minZ = clamp((long) cz - range.radiusZ()), maxZ = clamp((long) cz + range.radiusZ());
        return closed(sections, minX, minY, minZ, maxX, maxY, maxZ, range, cx, cy, cz, palette, test, limit, out);
    }

    private static <T> int closed(Sections<T> sections, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  @Nullable BlockRange range, int cx, int cy, int cz,
                                  Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (limit < 1 || minX > maxX || minY > maxY || minZ > maxZ) return 0;
        int bx = bits((long) maxX - minX), by = bits((long) maxY - minY), bz = bits((long) maxZ - minZ);
        long count = (long) ((maxX >> 4) - (minX >> 4) + 1) * ((maxY >> 4) - (minY >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
        if (bx + by + bz > 63 || count > MAX_SECTIONS) {
            return closedBrute(sections, minX, minY, minZ, maxX, maxY, maxZ, range, cx, cy, cz, palette, test, limit, out);
        }
        Keys keys = new Keys(limit);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int shiftZ = bx + by;
        long head = -1;
        if (limit != Integer.MAX_VALUE) {
            int budget = HEAD_CELLS;
            boolean complete = true;
            scan:
            for (long z = minZ; z <= maxZ; z++) {
                for (long y = minY; y <= maxY; y++) {
                    long lo = minX, hi = maxX;
                    if (range != null) {
                        long half = rowHalfWidth(range, y - cy, z - cz);
                        if (half < 0) continue;
                        lo = Math.max(minX, cx - half);
                        hi = Math.min(maxX, cx + half);
                    }
                    for (long x = lo; x <= hi; x++) {
                        if (budget-- == 0) {
                            complete = false;
                            break scan;
                        }
                        long key = ((z - minZ) << shiftZ) | ((y - minY) << bx) | (x - minX);
                        T v = valueAt(sections, (int) x, (int) y, (int) z);
                        if (palette.test(v) && test.test(v, cursor.set((int) x, (int) y, (int) z))) {
                            keys.add(key);
                            if (keys.size >= limit) break scan;
                        }
                        head = key;
                    }
                }
            }
            if (complete || keys.size >= limit) return emitClosed(keys, minX, minY, minZ, bx, by, out);
        }
        for (int sz = minZ >> 4, sz1 = maxZ >> 4; sz <= sz1; sz++) {
            int z0 = Math.max(minZ, sz << 4), z1 = Math.min(maxZ, (sz << 4) + 15);
            if (keys.size >= limit && ((long) (z0 - minZ) << shiftZ) > keys.bound()) break;
            for (int sy = minY >> 4, sy1 = maxY >> 4; sy <= sy1; sy++) {
                int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                for (int sx = minX >> 4, sx1 = maxX >> 4; sx <= sx1; sx++) {
                    int x0 = Math.max(minX, sx << 4), x1 = Math.min(maxX, (sx << 4) + 15);
                    if (range != null && !range.reaches(axisDistance(cx, x0, x1), axisDistance(cy, y0, y1), axisDistance(cz, z0, z1))) continue;
                    if ((((long) (z1 - minZ) << shiftZ) | ((long) (y1 - minY) << bx) | (x1 - minX)) <= head) continue;
                    T uniform = sections.seek(sx, sy, sz);
                    boolean dense = uniform == null;
                    if (dense ? !sections.mayContain(palette) : !palette.test(uniform)) continue;
                    for (int z = z0; z <= z1; z++) {
                        for (int y = y0; y <= y1; y++) {
                            int lo = x0, hi = x1;
                            if (range != null) {
                                long half = rowHalfWidth(range, (long) y - cy, (long) z - cz);
                                if (half < 0) continue;
                                lo = (int) Math.max(x0, cx - half);
                                hi = (int) Math.min(x1, cx + half);
                            }
                            for (int x = lo; x <= hi; x++) {
                                long key = ((long) (z - minZ) << shiftZ) | ((long) (y - minY) << bx) | (x - minX);
                                if (key <= head) continue;
                                T v = uniform;
                                if (dense) {
                                    v = sections.get(x & 15, y & 15, z & 15);
                                    if (!palette.test(v)) continue;
                                }
                                if (test.test(v, cursor.set((int) x, (int) y, (int) z))) {
                                    keys.add(key);
                                }
                            }
                        }
                    }
                }
            }
        }
        return emitClosed(keys, minX, minY, minZ, bx, by, out);
    }

    private static int emitClosed(Keys keys, int minX, int minY, int minZ, int bx, int by, Collection<? super BlockPos> out) {
        keys.compact();
        long maskX = (1L << bx) - 1, maskY = (1L << by) - 1;
        for (int i = 0; i < keys.size; i++) {
            long k = keys.data[i];
            out.add(new BlockPos(minX + (int) (k & maskX), minY + (int) ((k >>> bx) & maskY), minZ + (int) (k >>> (bx + by))));
        }
        return keys.size;
    }

    public static <T> int withinManhattan(Sections<T> sections, int cx, int cy, int cz, int rx, int ry, int rz,
                                          Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (limit < 1 || rx < 0 || ry < 0 || rz < 0) return 0;
        int bx = bits(2L * rx), by = bits(2L * ry), bz = bits(2L * rz), bd = bits((long) rx + ry + rz);
        long minX = (long) cx - rx, maxX = (long) cx + rx, minY = (long) cy - ry, maxY = (long) cy + ry, minZ = (long) cz - rz, maxZ = (long) cz + rz;
        long nx = (maxX >> 4) - (minX >> 4) + 1, ny = (maxY >> 4) - (minY >> 4) + 1, nz = (maxZ >> 4) - (minZ >> 4) + 1;
        if (bd + bx + by + bz > 63 || nx * ny * nz > MAX_SECTIONS || minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE ||
                minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            return withinManhattanBrute(sections, cx, cy, cz, rx, ry, rz, palette, test, limit, out);
        }
        int sx0 = (int) (minX >> 4), sy0 = (int) (minY >> 4), sz0 = (int) (minZ >> 4);
        int cnx = (int) nx, cny = (int) ny, cnz = (int) nz;
        long[] order = new long[cnx * cny * cnz];
        int n = 0;
        for (int iz = 0; iz < cnz; iz++) {
            long dz = axisDistance(cz, Math.max(minZ, (long) (sz0 + iz) << 4), Math.min(maxZ, ((long) (sz0 + iz) << 4) + 15));
            for (int iy = 0; iy < cny; iy++) {
                long dy = axisDistance(cy, Math.max(minY, (long) (sy0 + iy) << 4), Math.min(maxY, ((long) (sy0 + iy) << 4) + 15));
                for (int ix = 0; ix < cnx; ix++) {
                    long dx = axisDistance(cx, Math.max(minX, (long) (sx0 + ix) << 4), Math.min(maxX, ((long) (sx0 + ix) << 4) + 15));
                    order[n] = (dx + dy + dz) << 24 | n;
                    n++;
                }
            }
        }
        Arrays.sort(order);
        Keys keys = new Keys(limit);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int shiftD = bx + by + bz, shiftX = by + bz;
        long reach = (long) rx + ry + rz;
        long lo = -1, hi = limit == Integer.MAX_VALUE ? reach : Math.min(reach, FIRST_BAND);
        while (true) {
            for (long o : order) {
                long nearest = o >>> 24;
                if (nearest > hi) break;
                if (keys.size >= limit && nearest > (keys.bound() >>> shiftD)) break;
                int idx = (int) (o & 0xFFFFFF);
                int sx = sx0 + idx % cnx, sy = sy0 + (idx / cnx) % cny, sz = sz0 + idx / (cnx * cny);
                int x0 = (int) Math.max(minX, sx << 4), x1 = (int) Math.min(maxX, (sx << 4) + 15);
                int y0 = (int) Math.max(minY, sy << 4), y1 = (int) Math.min(maxY, (sy << 4) + 15);
                int z0 = (int) Math.max(minZ, sz << 4), z1 = (int) Math.min(maxZ, (sz << 4) + 15);
                if (axisReach(cx, x0, x1) + axisReach(cy, y0, y1) + axisReach(cz, z0, z1) <= lo) continue;
                T uniform = sections.seek(sx, sy, sz);
                boolean dense = uniform == null;
                if (dense ? !sections.mayContain(palette) : !palette.test(uniform)) continue;
                for (int z = z0; z <= z1; z++) {
                    int dz = z - cz;
                    for (int y = y0; y <= y1; y++) {
                        int dy = y - cy;
                        long base = (long) Math.abs(dy) + Math.abs(dz);
                        long far = hi - base;
                        if (far < 0) continue;
                        long near = Math.max(0, lo + 1 - base);
                        if (near > far) continue;
                        if (near == 0) {
                            scanRow(sections, uniform, (int) Math.max(x0, cx - far), (int) Math.min(x1, cx + far), y, z, cx, dy, dz, rx, ry, rz, bz, shiftX, shiftD, palette, test, cursor, keys);
                        } else {
                            scanRow(sections, uniform, (int) Math.max(x0, cx - far), (int) Math.min(x1, cx - near), y, z, cx, dy, dz, rx, ry, rz, bz, shiftX, shiftD, palette, test, cursor, keys);
                            scanRow(sections, uniform, (int) Math.max(x0, cx + near), (int) Math.min(x1, cx + far), y, z, cx, dy, dz, rx, ry, rz, bz, shiftX, shiftD, palette, test, cursor, keys);
                        }
                    }
                }
            }
            if (keys.size >= limit || hi >= reach) break;
            lo = hi;
            hi = Math.min(reach, hi * 2);
        }
        return emitManhattan(keys, cx, cy, cz, rx, ry, rz, bx, by, bz, out);
    }

    private static <T> void scanRow(Sections<T> sections, @Nullable T uniform, int from, int to, int y, int z, int cx, int dy, int dz, int rx, int ry, int rz,
                                    int bz, int shiftX, int shiftD, Predicate<? super T> palette, CellTest<? super T> test, BlockPos.MutableBlockPos cursor, Keys keys) {
        int ly = y & 15, lz = z & 15;
        long rowKey = (long) (dy + ry) << bz | (rz - dz);
        long rowDistance = (long) Math.abs(dy) + Math.abs(dz);
        for (int x = from; x <= to; x++) {
            T v = uniform;
            if (v == null) {
                v = sections.get(x & 15, ly, lz);
                if (!palette.test(v)) continue;
            }
            if (test.test(v, cursor.set(x, y, z))) {
                int dx = x - cx;
                keys.add((rowDistance + Math.abs(dx)) << shiftD | (long) (dx + rx) << shiftX | rowKey);
            }
        }
    }

    private static int emitManhattan(Keys keys, int cx, int cy, int cz, int rx, int ry, int rz, int bx, int by, int bz, Collection<? super BlockPos> out) {
        int shiftX = by + bz;
        keys.compact();
        long maskX = (1L << bx) - 1, maskY = (1L << by) - 1, maskZ = (1L << bz) - 1;
        for (int i = 0; i < keys.size; i++) {
            long k = keys.data[i];
            int dx = (int) ((k >>> shiftX) & maskX) - rx, dy = (int) ((k >>> bz) & maskY) - ry, dz = rz - (int) (k & maskZ);
            out.add(new BlockPos(cx + dx, cy + dy, cz + dz));
        }
        return keys.size;
    }

    static <T> int betweenClosedBrute(Sections<T> sections, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                      Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        return closedBrute(sections, minX, minY, minZ, maxX, maxY, maxZ, null, 0, 0, 0, palette, test, limit, out);
    }

    static <T> int inRangeBrute(Sections<T> sections, int cx, int cy, int cz, BlockRange range,
                                Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (range.isEmpty()) return 0;
        return closedBrute(sections, clamp((long) cx - range.radiusX()), clamp((long) cy - range.radiusY()), clamp((long) cz - range.radiusZ()),
                clamp((long) cx + range.radiusX()), clamp((long) cy + range.radiusY()), clamp((long) cz + range.radiusZ()), range, cx, cy, cz,
                palette, test, limit, out);
    }

    private static <T> int closedBrute(Sections<T> sections, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                       @Nullable BlockRange range, int cx, int cy, int cz,
                                       Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (limit < 1 || minX > maxX || minY > maxY || minZ > maxZ) return 0;
        int found = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long z = minZ; z <= maxZ; z++) {
            for (long y = minY; y <= maxY; y++) {
                for (long x = minX; x <= maxX; x++) {
                    if (range != null && !range.reaches(Math.abs(x - cx), Math.abs(y - cy), Math.abs(z - cz))) continue;
                    T v = valueAt(sections, (int) x, (int) y, (int) z);
                    if (palette.test(v) && test.test(v, cursor.set((int) x, (int) y, (int) z))) {
                        out.add(cursor.immutable());
                        if (++found >= limit) return found;
                    }
                }
            }
        }
        return found;
    }

    static <T> int withinManhattanBrute(Sections<T> sections, int cx, int cy, int cz, int rx, int ry, int rz,
                                        Predicate<? super T> palette, CellTest<? super T> test, int limit, Collection<? super BlockPos> out) {
        if (limit < 1 || rx < 0 || ry < 0 || rz < 0) return 0;
        int found = 0;
        for (BlockPos p : BlockPos.withinManhattan(new BlockPos(cx, cy, cz), rx, ry, rz)) {
            T v = valueAt(sections, p.getX(), p.getY(), p.getZ());
            if (palette.test(v) && test.test(v, p)) {
                out.add(p.immutable());
                if (++found >= limit) break;
            }
        }
        return found;
    }

    private static <T> T valueAt(Sections<T> sections, int x, int y, int z) {
        T uniform = sections.seek(x >> 4, y >> 4, z >> 4);
        return uniform != null ? uniform : sections.get(x & 15, y & 15, z & 15);
    }

    private static long rowHalfWidth(BlockRange range, long dy, long dz) {
        long ay = Math.abs(dy), az = Math.abs(dz);
        if (ay > range.radiusY() || az > range.radiusZ()) return -1;
        if (range.shape() == BlockRange.Shape.BOX) return range.radiusX();
        long rest = range.radiusSq() - ay * ay - az * az;
        if (rest < 0) return -1;
        long h = (long) Math.sqrt((double) rest);
        while (h * h > rest) h--;
        while ((h + 1) * (h + 1) <= rest) h++;
        return Math.min(h, range.radiusX());
    }

    private static int clamp(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static long axisReach(long center, long lo, long hi) {
        return Math.max(Math.abs(lo - center), Math.abs(hi - center));
    }

    private static long axisDistance(long center, long lo, long hi) {
        return center < lo ? lo - center : center > hi ? center - hi : 0;
    }

    private static int bits(long span) {
        return 64 - Long.numberOfLeadingZeros(span);
    }

    private static final class Keys {

        final int limit;
        long[] data = new long[16];
        int size;
        boolean sorted = true;

        Keys(int limit) {
            this.limit = limit;
        }

        void add(long key) {
            if (sorted && size >= limit && key > data[limit - 1]) return;
            if (size == data.length) {
                if (size >= limit) compact();
                if (size == data.length) data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = key;
            sorted = false;
        }

        long bound() {
            compact();
            return data[limit - 1];
        }

        void compact() {
            if (!sorted) {
                Arrays.sort(data, 0, size);
                sorted = true;
            }
            if (size > limit) size = limit;
        }
    }
}
