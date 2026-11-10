package com.suried.gtoutils.mixin;

import com.suried.gtoutils.level.LoadedChunkSource;
import com.suried.gtoutils.level.RecentChunks;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import com.mojang.datafixers.util.Either;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements LoadedChunkSource {

    @Shadow
    @Final
    Thread mainThread;

    @Unique
    private final RecentChunks<LevelChunk> gtoutils$recent = new RecentChunks<>();

    @Shadow
    public abstract @Nullable LevelChunk getChunkNow(int chunkX, int chunkZ);

    @Shadow
    protected abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    @Override
    public @Nullable LevelChunk gtoutils$loadedChunk(int chunkX, int chunkZ) {
        if (Thread.currentThread() == mainThread) return getChunkNow(chunkX, chunkZ);
        long key = ChunkPos.asLong(chunkX, chunkZ);
        LevelChunk cached = gtoutils$recent.get(key);
        if (cached != null) return cached;
        ChunkHolder holder = getVisibleChunkIfPresent(key);
        if (holder == null) return null;
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = holder.getFutureIfPresent(ChunkStatus.FULL).getNow(null);
        if (either == null) return null;
        if (!(either.left().orElse(null) instanceof LevelChunk chunk)) return null;
        gtoutils$recent.put(key, chunk);
        return chunk;
    }

    @Inject(method = "clearCache", at = @At("TAIL"))
    private void gtoutils$clearRecent(CallbackInfo ci) {
        gtoutils$recent.clear();
    }
}
