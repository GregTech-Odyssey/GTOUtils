package com.suried.gtoutils.mixin;

import com.suried.gtoutils.spatial.BlockEntityTracking;
import com.suried.gtoutils.spatial.TrackedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements TrackedChunk {

    @Unique
    private boolean gtoutils$hiddenFromIndex;

    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    @Override
    public boolean gtoutils$hidden() {
        return gtoutils$hiddenFromIndex;
    }

    @Override
    public void gtoutils$setHidden(boolean hidden) {
        gtoutils$hiddenFromIndex = hidden;
    }

    @Inject(method = "setBlockEntity",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private void gtoutils$beforeSet(BlockEntity be, CallbackInfo ci) {
        if (gtoutils$hiddenFromIndex) return;
        BlockEntity old = getBlockEntities().get(be.getBlockPos());
        if (old == be) return;
        if (old != null) BlockEntityTracking.removed(old);
        BlockEntityTracking.added(be);
    }

    @Inject(method = "removeBlockEntity",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void gtoutils$beforeRemove(BlockPos pos, CallbackInfo ci) {
        if (gtoutils$hiddenFromIndex) return;
        BlockEntity old = getBlockEntities().get(pos);
        if (old != null) BlockEntityTracking.removed(old);
    }

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;isRemoved()Z")),
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void gtoutils$beforeDropRemoved(BlockPos pos, LevelChunk.EntityCreationType type, CallbackInfoReturnable<BlockEntity> cir) {
        if (gtoutils$hiddenFromIndex) return;
        BlockEntity old = getBlockEntities().get(pos);
        if (old != null) BlockEntityTracking.removed(old);
    }

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void gtoutils$beforeClear(CallbackInfo ci) {
        if (gtoutils$hiddenFromIndex) {
            gtoutils$hiddenFromIndex = false;
            return;
        }
        Map<BlockPos, BlockEntity> map = getBlockEntities();
        if (map.isEmpty()) return;
        for (BlockEntity be : map.values()) BlockEntityTracking.removed(be);
    }
}
