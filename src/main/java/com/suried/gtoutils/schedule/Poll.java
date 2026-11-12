package com.suried.gtoutils.schedule;

/**
 * 没有事件来源的读取用的周期信号。功能：
 * <ul>
 * <li>{@link #every}：在派生求值期间调用，让该派生每 period 轮重算一次；不在求值期间调用时什么也不做</li>
 * <li>同一车道上的派生按建立顺序分到周期内的各个相位，重查时刻互相错开，不会挤在同一轮；同一个派生每次重算落在同一个相位</li>
 * <li>同一车道同一周期同一相位共用一个信号，信号挂在车道上、随车道一起回收；有订阅时才挂周期任务，最后一个订阅消失即取消</li>
 * </ul>
 */
public final class Poll {

    private Poll() {}

    public static void every(int period) {
        Signal.Frame frame = Signal.currentFrame();
        if (frame == null) return;
        int p = Math.max(1, period);
        frame.add(frame.lane.poll(p, Math.floorMod(frame.salt, p)));
    }

    static PollSignal signal(TickScheduler lane, int period, int phase) {
        return lane.poll(period, phase);
    }

    static final class PollSignal extends Signal {

        private final TickScheduler lane;
        private final int period;
        private final int phase;
        private Task task;

        PollSignal(TickScheduler lane, int period, int phase) {
            this.lane = lane;
            this.period = period;
            this.phase = phase;
        }

        @Override
        void firstSubscribed() {
            task = lane.repeat(Cadence.every(period).phase(phase), this::emit);
        }

        @Override
        void lastUnsubscribed() {
            task = Task.cancel(task);
        }
    }
}
