package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The cadence of the round-robin reconciliation ({@link SnapshotCadence}, M22, issue #2): the <b>cycle time</b> of a
 * warehouse is bounded, not its per-tick cost.
 * <p>
 * The numbers here are the shipped defaults, because what this class decides is a server's per-tick cost and the
 * promise in the config comment is what has to hold — above all the first one: an existing warehouse reads exactly as
 * many inventories as it always did.
 */
class SnapshotCadenceTest {
    private static final int INTERVAL = 10;
    private static final int CYCLE = 12000;
    private static final int MAX_PER_READ = 4;

    @Test
    void everyWarehouseThatCouldExistBeforeReadsOneLocationPerIntervalAsItAlwaysDid() {
        // A full 32-rail aisle with 10 levels on both sides: 2 * 33 * 10 = 660 locations, the biggest single aisle the
        // default caps allowed before M22 - and the number that must not change by a single read.
        assertEquals(1, SnapshotCadence.locationsPerInterval(660, INTERVAL, CYCLE, MAX_PER_READ));
        assertEquals(1, SnapshotCadence.locationsPerInterval(1, INTERVAL, CYCLE, MAX_PER_READ));
        assertEquals(1, SnapshotCadence.locationsPerInterval(1200, INTERVAL, CYCLE, MAX_PER_READ),
                "and one location per interval holds up to the whole cycle budget");
        assertEquals(1, SnapshotCadence.locationsPerInterval(0, INTERVAL, CYCLE, MAX_PER_READ),
                "a warehouse with no storage finds nothing to read anyway");
    }

    @Test
    void aWarehouseOfManyAislesComesRoundInTheConfiguredTime() {
        // Up to the point where the ceiling bites, which is maxPerRead * cycle / interval = 4800 locations; past it
        // the per-tick cost stops growing instead of the cycle staying put (itNeverReadsMoreThanTheChangeQueueMay).
        for (int locations : new int[] { 1200, 1201, 2400, 3000, 4800 }) {
            int perInterval = SnapshotCadence.locationsPerInterval(locations, INTERVAL, CYCLE, MAX_PER_READ);
            long cycle = SnapshotCadence.cycleTicks(locations, INTERVAL, perInterval);
            assertTrue(cycle <= CYCLE, locations + " locations came round in " + cycle + " ticks");
        }
    }

    @Test
    void itNeverReadsMoreThanTheChangeQueueMay() {
        // Past the point where even the ceiling cannot meet the cycle, the cost stops growing and the cycle does not:
        // that is the honest trade, and it is why the ceiling is the change queue's own per-tick budget.
        assertEquals(MAX_PER_READ, SnapshotCadence.locationsPerInterval(100_000, INTERVAL, CYCLE, MAX_PER_READ));
        assertEquals(1, SnapshotCadence.locationsPerInterval(100_000, INTERVAL, CYCLE, 1));
        assertEquals(1, SnapshotCadence.locationsPerInterval(100_000, INTERVAL, CYCLE, 0),
                "a ceiling below one is still one, so a counting mistake cannot switch the round robin off");
    }

    @Test
    void theCycleKeySwitchesTheScalingOff() {
        assertEquals(1, SnapshotCadence.locationsPerInterval(6000, INTERVAL, 0, MAX_PER_READ));
        assertEquals(1, SnapshotCadence.locationsPerInterval(6000, INTERVAL, -5, MAX_PER_READ));
    }

    @Test
    void aSingleTickIntervalIsScaledJustTheSame() {
        assertEquals(1, SnapshotCadence.locationsPerInterval(6000, 1, CYCLE, MAX_PER_READ),
                "6000 locations one tick apart already come round in 6000 ticks");
        assertEquals(2, SnapshotCadence.locationsPerInterval(24_000, 1, CYCLE, MAX_PER_READ));
        assertEquals(1, SnapshotCadence.locationsPerInterval(6000, 0, CYCLE, MAX_PER_READ),
                "an interval below one tick is read as one tick, never as none");
    }

    /**
     * <b>The measurement</b>, as a table rather than a promise: what one full reconciliation of a warehouse of that
     * many storage locations costs in ticks, before M22 (always one location per interval) and after it. The numbers
     * are asserted, so the table in {@code docs/warehouse-system.md} §5 and in the changelog is executed rather than
     * claimed, and a change to the formula has to come here and say what it changed.
     * <p>
     * Read in minutes at 20 ticks per second: 660 locations 5.5 min → 5.5 min (a full single aisle, untouched), 1056
     * 8.8 → 8.8 (the full default geometry, untouched), 1200 10 → 10 (the last size that reads one per interval),
     * 2400 20 → 10, 3000 25 → 8.3, 4800 40 → 10, and 16 512 — the largest geometry the config allows — <b>2.3 hours
     * → 34 minutes</b>, where the {@code maxSnapshotsPerTick} ceiling bites and the per-tick cost stops growing
     * instead of the cycle staying put.
     */
    @Test
    void whatOneFullReconciliationCostsAtEverySizeThatMatters() {
        int[][] table = {
                // locations, locations per interval, ticks for one full cycle
                {660, 1, 6600},
                {1056, 1, 10_560},
                {1200, 1, 12_000},
                {1201, 2, 6010},
                {2400, 2, 12_000},
                {3000, 3, 10_000},
                {4800, 4, 12_000},
                {16_512, 4, 41_280},
        };
        for (int[] row : table) {
            int locations = row[0];
            int perInterval = SnapshotCadence.locationsPerInterval(locations, INTERVAL, CYCLE, MAX_PER_READ);
            assertEquals(row[1], perInterval, locations + " locations per interval");
            assertEquals(row[2], SnapshotCadence.cycleTicks(locations, INTERVAL, perInterval),
                    "ticks for one cycle over " + locations + " locations");
            // What the same warehouse took before M22, and still takes wherever the answer above is 1.
            assertEquals((long) locations * INTERVAL, SnapshotCadence.cycleTicks(locations, INTERVAL, 1),
                    "ticks before M22 over " + locations + " locations");
        }
    }

    @Test
    void theCycleTimeOfANeverEmptyWarehouseIsNeverZero() {
        assertEquals(0L, SnapshotCadence.cycleTicks(0, INTERVAL, 1));
        assertEquals(10L, SnapshotCadence.cycleTicks(1, INTERVAL, 1));
        assertEquals(6600L, SnapshotCadence.cycleTicks(660, INTERVAL, 1));
        assertEquals(10L, SnapshotCadence.cycleTicks(3, INTERVAL, 4), "a cadence past the size is one interval");
        assertEquals(30L, SnapshotCadence.cycleTicks(3, INTERVAL, 0), "and a cadence of none is read as one");
    }
}
