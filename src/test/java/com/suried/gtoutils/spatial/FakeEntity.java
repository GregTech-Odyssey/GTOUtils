package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;

class FakeEntity {

    static final Tracker.Access<FakeEntity> ACCESS = new Tracker.Access<>() {

        @Override
        public LevelIndexHolder holder(FakeEntity e) {
            return e.level;
        }

        @Override
        public BlockPos pos(FakeEntity e) {
            return e.pos;
        }

        @Override
        public boolean live(FakeEntity e) {
            return !e.removed;
        }

        @Override
        public int probedMask(FakeEntity e) {
            return e.mask;
        }

        @Override
        public void setProbedMask(FakeEntity e, int mask) {
            e.mask = mask;
        }
    };

    interface Marker {}

    static class Jar extends FakeEntity {

        Jar(FakeLevel level, int x, int y, int z) {
            super(level, x, y, z);
        }
    }

    static class BigJar extends Jar implements Marker {

        BigJar(FakeLevel level, int x, int y, int z) {
            super(level, x, y, z);
        }
    }

    static class Pipe extends FakeEntity implements Marker {

        Pipe(FakeLevel level, int x, int y, int z) {
            super(level, x, y, z);
        }
    }

    final FakeLevel level;
    final BlockPos pos;
    boolean removed;
    boolean capable;
    int probes;
    int mask;

    FakeEntity(FakeLevel level, int x, int y, int z) {
        this.level = level;
        this.pos = new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + pos.toShortString();
    }
}
