package com.suried.gtoutils.schedule;

import org.jetbrains.annotations.Nullable;

public interface Task {

    boolean isActive();

    void cancel();

    int period();

    void setPeriod(int period);

    static @Nullable Task cancel(@Nullable Task task) {
        if (task != null) task.cancel();
        return null;
    }

    static boolean isActive(@Nullable Task task) {
        return task != null && task.isActive();
    }
}
