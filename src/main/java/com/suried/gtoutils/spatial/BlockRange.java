package com.suried.gtoutils.spatial;

/**
 * 以某个方块为中心的查询范围，供 {@link BlockOctree} 和 {@link BlockIndex} 使用。功能：
 * <ul>
 * <li>{@link #sphere(int)}：球形，方块坐标差的平方和 ≤ r²；{@link #sphereSq(long)} 直接给平方半径，
 * 例如原版 {@code closerThan} 的"严格小于 r"写成 {@code sphereSq(r * r - 1)}</li>
 * <li>{@link #box(int)} / {@link #box(int, int, int)}：立方体或长方体，各轴坐标差的绝对值 ≤ 对应半径</li>
 * <li>{@link #contains(int, int, int)}：判断坐标差是否落在范围内</li>
 * <li>{@link #reaches(long, long, long)}：给定三轴的最小距离，判断一个区域是否可能与范围相交，也用于判断区域是否整体落在范围内</li>
 * <li>半径为负的范围是空范围，什么都不包含</li>
 * <li>0~64 的球形与立方体半径返回预建的共享实例，不分配内存；实例不可变，可放心复用</li>
 * </ul>
 */
public final class BlockRange {

    public enum Shape {
        SPHERE,
        BOX
    }

    private static final int CACHED = 64;
    private static final BlockRange EMPTY_SPHERE = new BlockRange(Shape.SPHERE, -1, -1, -1, -1);
    private static final BlockRange EMPTY_BOX = new BlockRange(Shape.BOX, -1, -1, -1, -1);
    private static final BlockRange[] SPHERES = new BlockRange[CACHED + 1];
    private static final BlockRange[] BOXES = new BlockRange[CACHED + 1];

    static {
        for (int r = 0; r <= CACHED; r++) {
            SPHERES[r] = new BlockRange(Shape.SPHERE, r, r, r, (long) r * r);
            BOXES[r] = new BlockRange(Shape.BOX, r, r, r, -1);
        }
    }

    private final Shape shape;
    private final int rx, ry, rz;
    private final long radiusSq;

    private BlockRange(Shape shape, int rx, int ry, int rz, long radiusSq) {
        this.shape = shape;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.radiusSq = radiusSq;
    }

    public static BlockRange sphere(int radius) {
        if (radius < 0) return EMPTY_SPHERE;
        if (radius <= CACHED) return SPHERES[radius];
        return sphereSq((long) radius * radius);
    }

    public static BlockRange sphereSq(long radiusSq) {
        if (radiusSq < 0) return EMPTY_SPHERE;
        int r = (int) Math.sqrt((double) radiusSq);
        while ((long) r * r > radiusSq) r--;
        while ((long) (r + 1) * (r + 1) <= radiusSq) r++;
        return new BlockRange(Shape.SPHERE, r, r, r, radiusSq);
    }

    public static BlockRange box(int radius) {
        return box(radius, radius, radius);
    }

    public static BlockRange box(int rx, int ry, int rz) {
        if (rx < 0 || ry < 0 || rz < 0) return EMPTY_BOX;
        if (rx == ry && ry == rz && rx <= CACHED) return BOXES[rx];
        return new BlockRange(Shape.BOX, rx, ry, rz, -1);
    }

    public Shape shape() {
        return shape;
    }

    public int radiusX() {
        return rx;
    }

    public int radiusY() {
        return ry;
    }

    public int radiusZ() {
        return rz;
    }

    public long radiusSq() {
        return radiusSq;
    }

    public boolean isEmpty() {
        return rx < 0;
    }

    public boolean contains(int dx, int dy, int dz) {
        return reaches(Math.abs((long) dx), Math.abs((long) dy), Math.abs((long) dz));
    }

    public boolean reaches(long ax, long ay, long az) {
        if (rx < 0) return false;
        if (shape == Shape.BOX) return ax <= rx && ay <= ry && az <= rz;
        if (ax > rx || ay > ry || az > rz) return false;
        return ax * ax + ay * ay + az * az <= radiusSq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockRange other)) return false;
        return shape == other.shape && rx == other.rx && ry == other.ry && rz == other.rz && radiusSq == other.radiusSq;
    }

    @Override
    public int hashCode() {
        int h = shape.hashCode();
        h = 31 * h + rx;
        h = 31 * h + ry;
        h = 31 * h + rz;
        return 31 * h + Long.hashCode(radiusSq);
    }

    @Override
    public String toString() {
        return shape == Shape.SPHERE ? "BlockRange.sphereSq(" + radiusSq + ")" : "BlockRange.box(" + rx + ", " + ry + ", " + rz + ")";
    }
}
