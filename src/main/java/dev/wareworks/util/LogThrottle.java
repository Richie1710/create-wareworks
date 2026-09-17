package dev.wareworks.util;

/**
 * Rate limit for a diagnostic log line, so a repeating fault reports once in a while instead of once per occurrence
 * (which would spam the log) or exactly once ever (which would hide every later fault).
 * <p>
 * Used for the storage-interop diagnostics of controller, interface and crane: a foreign inventory that throws, a
 * planner that fails, a handling head that contradicts its job. A plain {@code boolean logged} latch was used before; it
 * silenced <b>every other</b> failing inventory of the same block entity for the rest of its life, so a second, unrelated
 * fault could never be diagnosed ({@code docs/warehouse-system.md} §5, {@code docs/stacker-crane.md} §4.2).
 * <p>
 * The clock is the level's game time, which stands still while the level does; the first call always logs. Server thread
 * only.
 */
public final class LogThrottle {
    /**
     * Default interval: the same 1200 ticks (one minute of running game) after which a remembered live refusal expires,
     * so a fault that outlives the memory of its cause is reported again.
     */
    public static final long DEFAULT_MIN_INTERVAL_TICKS = 1200;

    private static final long NEVER = Long.MIN_VALUE;

    private final long minIntervalTicks;
    private long lastLogTick = NEVER;

    /** @param minIntervalTicks minimum game ticks between two log lines (at least 0) */
    public LogThrottle(long minIntervalTicks) {
        if (minIntervalTicks < 0)
            throw new IllegalArgumentException("minIntervalTicks must not be negative: " + minIntervalTicks);
        this.minIntervalTicks = minIntervalTicks;
    }

    /** A throttle with {@link #DEFAULT_MIN_INTERVAL_TICKS}. */
    public LogThrottle() {
        this(DEFAULT_MIN_INTERVAL_TICKS);
    }

    /**
     * Whether the line may be logged at game time {@code now}. If so, it counts as logged: the time is recorded, so the
     * caller must write the line.
     * <p>
     * A {@code now} that moved backwards (a level whose time was set back) counts as due, so the caller never goes
     * silent for a long time.
     */
    public boolean tryLog(long now) {
        if (lastLogTick != NEVER && now >= lastLogTick && now - lastLogTick < minIntervalTicks)
            return false;
        lastLogTick = now;
        return true;
    }
}
