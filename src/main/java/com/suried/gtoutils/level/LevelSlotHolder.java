package com.suried.gtoutils.level;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public interface LevelSlotHolder {

    @Nullable
    Object[] gtoutils$slots();

    void gtoutils$setSlots(@Nullable Object[] slots);

    boolean gtoutils$released();

    void gtoutils$setReleased(boolean released);
}
