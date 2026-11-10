package com.suried.gtoutils.level;

public final class FakeLevel implements LevelSlotHolder {

    private Object[] slots;
    private boolean released;

    @Override
    public Object[] gtoutils$slots() {
        return slots;
    }

    @Override
    public void gtoutils$setSlots(Object[] slots) {
        this.slots = slots;
    }

    @Override
    public boolean gtoutils$released() {
        return released;
    }

    @Override
    public void gtoutils$setReleased(boolean released) {
        this.released = released;
    }
}
