package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class BlockEntityTracking {

    static final Tracker<BlockEntity> TRACKER = new Tracker<>(new Tracker.Access<>() {

        @Override
        public @Nullable LevelIndexHolder holder(BlockEntity be) {
            return be.getLevel() instanceof LevelIndexHolder holder ? holder : null;
        }

        @Override
        public BlockPos pos(BlockEntity be) {
            return be.getBlockPos();
        }

        @Override
        public boolean live(BlockEntity be) {
            return !be.isRemoved();
        }

        @Override
        public int probedMask(BlockEntity be) {
            return ((TrackedBlockEntity) be).gtoutils$probedMask();
        }

        @Override
        public void setProbedMask(BlockEntity be, int mask) {
            ((TrackedBlockEntity) be).gtoutils$setProbedMask(mask);
        }
    });

    private BlockEntityTracking() {}

    public static void added(BlockEntity be) {
        TRACKER.added(be);
    }

    public static void removed(BlockEntity be) {
        TRACKER.removed(be);
    }

    public static void setVisible(LevelChunk chunk, boolean visible) {
        TrackedChunk tracked = (TrackedChunk) chunk;
        if (tracked.gtoutils$hidden() != visible) return;
        tracked.gtoutils$setHidden(!visible);
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (visible) TRACKER.added(be);
            else TRACKER.removed(be);
        }
    }
}
