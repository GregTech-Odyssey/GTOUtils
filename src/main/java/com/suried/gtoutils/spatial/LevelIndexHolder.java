package com.suried.gtoutils.spatial;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public interface LevelIndexHolder {

    @Nullable
    Object[] gtoutils$slots();

    void gtoutils$setSlots(@Nullable Object[] slots);
}
