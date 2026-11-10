package com.suried.gtoutils.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

/**
 * 只读已加载完成的区块，绝不触发加载，也不阻塞主线程。功能：
 * <ul>
 * <li>{@link #chunk}：取已加载的区块，没有就返回 null；服务端在主线程上同原版 getChunkNow，在其它线程上读区块表的可见副本，并用最近 4 个区块的缓存加速</li>
 * <li>{@link #isLoaded}：该格所在的区块是否已加载</li>
 * <li>{@link #blockState}：未加载或在高度范围外时返回虚空空气</li>
 * <li>{@link #blockEntity}：未加载、没有或已移除时返回 null，不创建待加载的方块实体</li>
 * <li>任何线程都能调用；在非主线程上读到的是某一时刻的值，可能和主线程的写入交错，需要强一致时应在主线程上取</li>
 * </ul>
 */
public final class LoadedChunks {

    public static final BlockState UNLOADED = Blocks.VOID_AIR.defaultBlockState();

    private LoadedChunks() {}

    public static @Nullable LevelChunk chunk(Level level, int chunkX, int chunkZ) {
        ChunkSource source = level.getChunkSource();
        if (source instanceof LoadedChunkSource loaded) return loaded.gtoutils$loadedChunk(chunkX, chunkZ);
        return source.getChunk(chunkX, chunkZ, false);
    }

    public static @Nullable LevelChunk chunk(Level level, BlockPos pos) {
        return chunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isLoaded(Level level, BlockPos pos) {
        return chunk(level, pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    public static BlockState blockState(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos.getY())) return UNLOADED;
        LevelChunk chunk = chunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? UNLOADED : chunk.getBlockState(pos);
    }

    public static @Nullable BlockEntity blockEntity(Level level, BlockPos pos) {
        LevelChunk chunk = chunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        BlockEntity be = chunk.getBlockEntities().get(pos);
        return be == null || be.isRemoved() ? null : be;
    }
}
