package dev.wareworks.core.crane;

/**
 * Durations the crane state machine uses, copied from the server config by the content layer
 * ({@code docs/warehouse-system.md} §9).
 *
 * @param transferTicks  duration of a pick or drop
 * @param retryTicks     wait before retrying a full output station
 * @param holdRetryTicks wait before retrying a reroute while holding items
 */
public record CraneTimings(int transferTicks, int retryTicks, int holdRetryTicks) {
    /** Smallest allowed value of every duration. */
    public static final int MIN_TICKS = 1;

    public CraneTimings {
        requireTicks("transferTicks", transferTicks);
        requireTicks("retryTicks", retryTicks);
        requireTicks("holdRetryTicks", holdRetryTicks);
    }

    private static void requireTicks(String name, int value) {
        if (value < MIN_TICKS)
            throw new IllegalArgumentException(name + " must be at least " + MIN_TICKS + ": " + value);
    }
}
