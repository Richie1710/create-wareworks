package dev.wareworks.core.warehouse;

/**
 * How many storage locations the round robin re-reads per interval, so that the <b>cycle time</b> of a warehouse is
 * bounded rather than its per-tick cost ({@code docs/warehouse-system.md} §5, M22, issue #2).
 * <p>
 * <b>Why this exists.</b> Until M22 the controller re-read exactly one storage location every
 * {@code controller.snapshotIntervalTicks}, so coming round to all of them took
 * {@code locations · snapshotIntervalTicks} ticks — about nine minutes for a full 32-rail aisle and getting on for
 * half an hour for a warehouse of several of them. That is how long a chest a player emptied by hand could stay wrong
 * in the stock index, and it grew with the warehouse instead of staying put. A network-sized warehouse is what makes
 * it due.
 * <p>
 * <b>The rule.</b> Read as many locations per interval as it takes to come round within
 * {@code controller.snapshotCycleTicks}, never fewer than one and never more than the change queue's own per-tick
 * budget {@code controller.maxSnapshotsPerTick}. At the shipped defaults (interval 10, cycle 12000, budget 4) that is
 * <b>one</b> location per interval up to 1200 locations, which is literally what every version before M22 did.
 * <p>
 * <b>How far that promise reaches.</b> Every warehouse at the <b>default aisle limits</b> is inside those 1200
 * locations (32 × 16 → 1056), so it reads exactly as many inventories as it always did. A server that raised
 * {@code aisle.maxAisleLength} or {@code aisle.maxMastHeight} could already build a bigger single aisle before M22 —
 * at the config ceilings 128 × 64 → 16 512 locations — and such a warehouse now reads up to
 * {@code maxSnapshotsPerTick} locations per interval instead of one: four at the defaults, and already four at about
 * 4100 locations, which one raised limit alone reaches. That is the point of the change, but it is a real increase, so
 * it is stated rather than promised away (M22 review fix), and {@code controller.snapshotCycleTicks = 0} keeps the old
 * cadence exactly.
 * <p>
 * Pure integer maths, so the arithmetic that decides a server's per-tick cost is JUnit-tested rather than inspected.
 */
public final class SnapshotCadence {
    private SnapshotCadence() {
    }

    /**
     * Storage locations to re-read in one round-robin interval.
     *
     * @param locations     storage locations the warehouse has; 0 or fewer answers 1, because the caller then finds
     *                      nothing to read anyway and must not be handed a 0 that hides a counting mistake
     * @param intervalTicks {@code controller.snapshotIntervalTicks}, at least 1
     * @param cycleTicks    {@code controller.snapshotCycleTicks}; 0 or less switches the scaling off (one per interval)
     * @param maxPerRead    {@code controller.maxSnapshotsPerTick}, the hard ceiling, at least 1
     */
    public static int locationsPerInterval(int locations, int intervalTicks, int cycleTicks, int maxPerRead) {
        int ceiling = Math.max(1, maxPerRead);
        if (cycleTicks <= 0 || locations <= 1)
            return 1;
        long interval = Math.max(1, intervalTicks);
        // ceil(locations * interval / cycle): the smallest count whose whole cycle fits in cycleTicks.
        long perInterval = Math.ceilDiv((long) locations * interval, cycleTicks);
        return (int) Math.clamp(perInterval, 1L, ceiling);
    }

    /**
     * Ticks one full cycle really takes at that cadence — what the config comment promises, so a test can check the
     * promise rather than the formula.
     */
    public static long cycleTicks(int locations, int intervalTicks, int perInterval) {
        if (locations <= 0)
            return 0L;
        long interval = Math.max(1, intervalTicks);
        return Math.ceilDiv(locations, Math.max(1, perInterval)) * interval;
    }
}
