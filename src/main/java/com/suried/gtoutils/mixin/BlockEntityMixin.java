package com.suried.gtoutils.mixin;

import com.suried.gtoutils.spatial.TrackedBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements TrackedBlockEntity {

    @Unique
    private int gtoutils$probed;

    @Override
    public int gtoutils$probedMask() {
        return gtoutils$probed;
    }

    @Override
    public void gtoutils$setProbedMask(int mask) {
        gtoutils$probed = mask;
    }
}
