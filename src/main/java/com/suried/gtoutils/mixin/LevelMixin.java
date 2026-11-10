package com.suried.gtoutils.mixin;

import com.suried.gtoutils.level.LevelSlotHolder;

import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelSlotHolder {

    @Unique
    private volatile @Nullable Object[] gtoutils$levelSlots;

    @Unique
    private volatile boolean gtoutils$levelReleased;

    @Override
    public @Nullable Object[] gtoutils$slots() {
        return gtoutils$levelSlots;
    }

    @Override
    public void gtoutils$setSlots(@Nullable Object[] slots) {
        gtoutils$levelSlots = slots;
    }

    @Override
    public boolean gtoutils$released() {
        return gtoutils$levelReleased;
    }

    @Override
    public void gtoutils$setReleased(boolean released) {
        gtoutils$levelReleased = released;
    }
}
