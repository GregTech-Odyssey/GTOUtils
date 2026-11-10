package com.suried.gtoutils.schedule;

public final class Cadence {

    private static final int AUTO = -1;
    private static final Cadence[] CACHED = new Cadence[129];

    static {
        for (int p = 1; p < CACHED.length; p++) CACHED[p] = new Cadence(p, AUTO);
    }

    public static final Cadence EVERY_TICK = CACHED[1];

    private final int period;
    private final int phase;

    private Cadence(int period, int phase) {
        this.period = period;
        this.phase = phase;
    }

    public static Cadence every(int period) {
        int p = Math.max(1, period);
        return p < CACHED.length ? CACHED[p] : new Cadence(p, AUTO);
    }

    public Cadence phase(int phase) {
        return new Cadence(period, Math.floorMod(phase, period));
    }

    public int period() {
        return period;
    }

    public boolean isAutoPhase() {
        return phase == AUTO;
    }

    public int phase() {
        return phase;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cadence c && c.period == period && c.phase == phase;
    }

    @Override
    public int hashCode() {
        return period * 31 + phase;
    }

    @Override
    public String toString() {
        return phase == AUTO ? "every " + period : "every " + period + " at " + phase;
    }
}
