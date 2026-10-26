package dev.wareworks.core.production;

/**
 * The redstone pulse of a Warehouse Crafter Feeder, one per run (issue #34, ADR-067): when to raise the signal, when to
 * lower it, when to look whether the Crafter crafted, and when the next run may start.
 * <p>
 * <b>Why it is shaped like this.</b> A vanilla Crafter crafts on a <b>rising edge</b> only: it schedules its craft
 * {@value #CRAFT_DELAY_TICKS} ticks after the edge and marks itself triggered, and only a falling signal clears that
 * mark ({@code CrafterBlock#neighborChanged}). A signal that stays high crafts once; a second edge while the first craft
 * is still pending adds nothing, because one position holds at most one scheduled tick of a block. So the feeder keeps
 * the signal high for {@value #PULSE_HIGH_TICKS} ticks, looks at the grid {@value #VERIFY_AFTER_TICKS} ticks after the
 * edge — the craft has run by then — and raises it again no earlier than {@value #MIN_RUN_TICKS} ticks after the last
 * edge, so every edge finds the previous craft consumed.
 * <p>
 * One run takes {@link #ticksPerRun} ticks, which follows the rotation like every other machine of Create.
 * <p>
 * Not saved: a loaded feeder starts in the cooldown ({@link #cooldown}), lowers any signal it finds and reads the grid,
 * which is where the truth about the runs lives. Pure and mutable; server thread only.
 */
public final class FeederCycle {
    /** Ticks the signal stays high. */
    public static final int PULSE_HIGH_TICKS = 2;
    /**
     * Ticks between the rising edge and the craft: the vanilla Crafter's own private {@code CRAFTING_TICK_DELAY}
     * ({@code CrafterBlock#neighborChanged} schedules its tick that far ahead). Mirrored here for the arithmetic below.
     */
    public static final int CRAFT_DELAY_TICKS = 4;
    /** Ticks after the edge at which the grid is read to see whether the run was crafted; after the craft. */
    public static final int VERIFY_AFTER_TICKS = CRAFT_DELAY_TICKS + 2;
    /** The shortest run, whatever the speed: more than the craft delay, so no edge ever meets a pending craft. */
    public static final int MIN_RUN_TICKS = 8;
    /** The pause after a pulse that crafted nothing, so a broken setup does not click eight times a second. */
    public static final int FAILED_BACKOFF_TICKS = 100;
    /** Ticks per run at 1 RPM; 32 RPM and above reach {@value #MIN_RUN_TICKS}. */
    public static final int TICKS_AT_ONE_RPM = 256;

    /** Where the cycle stands. */
    public enum Phase {
        /** The next run may start. */
        READY,
        /** The signal is high. */
        HIGH,
        /** The signal is low again; the craft is pending or done, and the grid has not been read yet. */
        CRAFTING,
        /** The grid was read; waiting until the run's time is over. */
        COOLDOWN
    }

    /** What the caller has to do in this tick. */
    public enum Step {
        NONE,
        /** Lower the signal. */
        LOWER,
        /** Read the grid: one run fewer than at the edge means the Crafter crafted. */
        VERIFY
    }

    private Phase phase = Phase.READY;
    private int sinceEdge;
    private int runTicks = MIN_RUN_TICKS;

    /**
     * Ticks one run takes: {@code max(}{@value #MIN_RUN_TICKS}{@code , ceil(}{@value #TICKS_AT_ONE_RPM}{@code  / |rpm|))},
     * so 32 RPM and above run two and a half crafts a second. A feeder that needs no rotation (server config) runs at
     * the fastest pace; one that needs rotation and has none does not run at all, and this answers the slowest pace for
     * it.
     */
    public static int ticksPerRun(float rpm, boolean requiresRotation) {
        if (!requiresRotation)
            return MIN_RUN_TICKS;
        float speed = Math.abs(rpm);
        if (speed < 1.0e-4f || Float.isNaN(speed))
            return TICKS_AT_ONE_RPM;
        return Math.max(MIN_RUN_TICKS, (int) Math.ceil(TICKS_AT_ONE_RPM / speed));
    }

    public Phase phase() {
        return phase;
    }

    /** Whether a new run may start now. */
    public boolean ready() {
        return phase == Phase.READY;
    }

    /** Whether a pulse is under way and its grid has not been read yet. */
    public boolean inPulse() {
        return phase == Phase.HIGH || phase == Phase.CRAFTING;
    }

    /** Whether the signal is meant to be high right now. */
    public boolean high() {
        return phase == Phase.HIGH;
    }

    /**
     * Starts a run: the caller raises the signal in this same tick.
     *
     * @param ticksPerRun this run's length ({@link #ticksPerRun}), at least {@value #MIN_RUN_TICKS}
     * @throws IllegalStateException when the cycle is not {@link #ready()}
     */
    public void raise(int ticksPerRun) {
        if (phase != Phase.READY)
            throw new IllegalStateException("a run is still under way: " + phase);
        phase = Phase.HIGH;
        sinceEdge = 0;
        runTicks = Math.max(MIN_RUN_TICKS, ticksPerRun);
    }

    /**
     * Advances one tick. Call it once per tick while the feeder ticks; it tells the caller when to lower the signal and
     * when to read the grid, and moves on to {@link Phase#READY} once the run's time is over.
     */
    public Step advance() {
        if (phase == Phase.READY)
            return Step.NONE;
        sinceEdge++;
        switch (phase) {
            case HIGH:
                if (sinceEdge >= PULSE_HIGH_TICKS) {
                    phase = Phase.CRAFTING;
                    return Step.LOWER;
                }
                return Step.NONE;
            case CRAFTING:
                if (sinceEdge >= VERIFY_AFTER_TICKS) {
                    phase = Phase.COOLDOWN;
                    return Step.VERIFY;
                }
                return Step.NONE;
            case COOLDOWN:
                if (sinceEdge >= runTicks)
                    phase = Phase.READY;
                return Step.NONE;
            default:
                return Step.NONE;
        }
    }

    /**
     * The signal had to come down early (the feeder lost its rotation, its Crafter or its station while it was high): the
     * edge has already been given, so the craft it scheduled still happens and is read as usual.
     *
     * @return whether the signal was meant to be high, i.e. whether the caller has to lower it
     */
    public boolean lowerEarly() {
        if (phase != Phase.HIGH)
            return false;
        phase = Phase.CRAFTING;
        return true;
    }

    /** The pulse crafted nothing: wait {@value #FAILED_BACKOFF_TICKS} ticks after its edge before trying again. */
    public void backOff() {
        if (phase == Phase.COOLDOWN || phase == Phase.READY) {
            phase = Phase.COOLDOWN;
            runTicks = Math.max(runTicks, FAILED_BACKOFF_TICKS);
        }
    }

    /** Waits {@code ticks} before the next run, with no pulse under way (after a load or a rotation). */
    public void cooldown(int ticks) {
        phase = Phase.COOLDOWN;
        sinceEdge = 0;
        runTicks = Math.max(1, ticks);
    }

    @Override
    public String toString() {
        return "FeederCycle[" + phase + ", " + sinceEdge + "/" + runTicks + "]";
    }
}
