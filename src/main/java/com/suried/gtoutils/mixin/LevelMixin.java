package com.suried.gtoutils.mixin;

import com.suried.gtoutils.spatial.LevelIndexHolder;

import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelIndexHolder {

    @Unique
    private @Nullable Object[] gtoutils$indexSlots;

    @Override
    public @Nullable Object[] gtoutils$slots() {
        return gtoutils$indexSlots;
    }

    @Override
    public void gtoutils$setSlots(@Nullable Object[] slots) {
        gtoutils$indexSlots = slots;
    }
}
