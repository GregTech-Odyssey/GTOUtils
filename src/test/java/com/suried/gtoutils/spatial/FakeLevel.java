package com.suried.gtoutils.spatial;

final class FakeLevel implements LevelIndexHolder {

    private Object[] slots;

    @Override
    public Object[] gtoutils$slots() {
        return slots;
    }

    @Override
    public void gtoutils$setSlots(Object[] slots) {
        this.slots = slots;
    }
}
