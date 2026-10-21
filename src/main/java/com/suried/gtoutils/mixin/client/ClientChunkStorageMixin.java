package com.suried.gtoutils.mixin.client;

import com.suried.gtoutils.spatial.ClientChunkVisibility;

import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class ClientChunkStorageMixin implements ClientChunkVisibility.View {

    @Shadow
    @Final
    AtomicReferenceArray<LevelChunk> chunks;

    @Shadow
    abstract boolean inRange(int x, int z);

    @Override
    public AtomicReferenceArray<LevelChunk> gtoutils$chunks() {
        return chunks;
    }

    @Override
    public boolean gtoutils$inRange(int x, int z) {
        return inRange(x, z);
    }
}
