package com.suried.gtoutils.level;

import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public interface LoadedChunkSource {

    @Nullable
    LevelChunk gtoutils$loadedChunk(int chunkX, int chunkZ);
}
