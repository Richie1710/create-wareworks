package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThroughputWindowTest {
    /** A game time that is not a multiple of a second, so nothing may assume the ring is aligned to it. */
    private static final long START = 1_234_567L;
    private static final int MINUTE = ThroughputWindow.WINDOW_TICKS;
    private static final int SECOND = ThroughputWindow.TICKS_PER_BUCKET;

    private final ThroughputWindow window = new ThroughputWindow();

    /** Records {@code ticks} consecutive ticks of one activity and returns the game time of the last one. */
    private long run(long firstTick, int ticks, CraneActivity activity) {
        for (int tick = 0; tick < ticks; tick++)
            window.record(firstTick + tick, activity);
        return firstTick + ticks - 1;
    }

    @Test
    void aWindowThatHasSeenNothingIsEmptyAndDividesByNothing() {
        CraneThroughput nothing = window.snapshot();
        assertEquals(CraneThroughput.EMPTY, nothing);
        assertEquals(0, nothing.observedTicks());
        assertEquals(0, nothing.observedSeconds());
        assertFalse(nothing.isFullMinute());
        assertTrue(nothing.isEmpty());
        // The arithmetic must survive an empty window on every surface: no division by zero, no 100 % of nothing.
        assertEquals(0, nothing.busyShare());
        assertEquals(0, nothing.blockedShare());
        assertEquals(0, nothing.travelShare());
        assertEquals(0, nothing.turnShare());
        assertEquals(0, nothing.stopShare());
        assertEquals(0, nothing.idleShare());
        assertEquals(0, nothing.pausedShare());
        assertEquals(0, nothing.share(7));
    }

    @Test
    void theFirstTickAfterAReloadCountsExactlyOneTick() {
        // A fresh window is what every chunk load hands out: it must claim one tick, not a minute, and not zero.
        window.record(START, CraneActivity.TRAVELLING);
        CraneThroughput first = window.snapshot();
        assertEquals(1, first.observedTicks());
        assertEquals(1, first.travelTicks());
        assertEquals(1, first.observedSeconds());
        assertFalse(first.isFullMinute());
        assertEquals(100, first.busyShare());
        assertEquals(0, first.blockedShare());
    }

    @Test
    void theSixBucketsSumToTheObservedTicks() {
        CraneActivity[] all = CraneActivity.values();
        int ticks = 900;
        for (int tick = 0; tick < ticks; tick++)
            window.record(START + tick, all[tick % all.length]);
        CraneThroughput throughput = window.snapshot();
        assertEquals(ticks, throughput.observedTicks());
        int sum = 0;
        for (CraneActivity activity : all) {
            assertEquals(ticks / all.length, throughput.ticks(activity), activity.name());
            sum += throughput.ticks(activity);
        }
        assertEquals(throughput.observedTicks(), sum);
        assertEquals(throughput.travelTicks() + throughput.turnTicks() + throughput.stopTicks(),
                throughput.workingTicks());
    }

    @Test
    void aWindowOfPureWorkReadsAFullShareLongBeforeAMinuteIsUp() {
        // The warm-up guard: the shares divide by the observed ticks, never by a fixed minute. A surface that divided
        // by 1200 would show every crane as idle for its first minute after every chunk load.
        run(START, 100, CraneActivity.TRAVELLING);
        CraneThroughput throughput = window.snapshot();
        assertEquals(100, throughput.observedTicks());
        assertEquals(100, throughput.busyShare());
        assertEquals(100, throughput.travelShare());
        assertEquals(0, throughput.blockedShare());
        assertFalse(throughput.isEmpty());
    }

    @Test
    void aWindowThatHasSeenLessThanAMinuteNamesItsOwnLength() {
        run(0, 23 * SECOND, CraneActivity.TRAVELLING);
        CraneThroughput throughput = window.snapshot();
        assertEquals(23 * SECOND, throughput.observedTicks());
        assertEquals(23, throughput.observedSeconds());
        assertFalse(throughput.isFullMinute());
    }

    @Test
    void aFullMinuteReadsAsAFullMinuteThroughoutTheSecondInProgress() {
        // The youngest bucket is always still filling, so the observed ticks oscillate just under a minute. The label
        // must not flicker between "the last minute" and "the last 59 s" once the ring has wrapped.
        long last = run(0, MINUTE, CraneActivity.TRAVELLING);
        assertTrue(window.snapshot().isFullMinute());
        for (int tick = 1; tick <= SECOND; tick++) {
            window.record(last + tick, CraneActivity.TRAVELLING);
            assertTrue(window.snapshot().isFullMinute(), "not full after " + tick + " more ticks");
        }
    }

    @Test
    void theWindowNeverHoldsMoreThanAMinute() {
        run(START, 5000, CraneActivity.AT_A_STOP);
        int observed = window.snapshot().observedTicks();
        assertTrue(observed <= MINUTE, "observed " + observed);
        assertTrue(observed > MINUTE - SECOND, "observed " + observed);
        assertEquals(observed, window.observedTicks());
    }

    @Test
    void theWindowForgetsWhatHasRolledOutOfIt() {
        run(0, MINUTE, CraneActivity.TRAVELLING);
        assertEquals(MINUTE, window.snapshot().travelTicks());
        run(MINUTE, MINUTE, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(MINUTE, throughput.observedTicks());
        assertEquals(0, throughput.travelTicks());
        assertEquals(MINUTE, throughput.idleTicks());
        assertTrue(throughput.isEmpty());
    }

    @Test
    void aGapZeroesTheSkippedSecondsAndAddsNothingOfItsOwn() {
        long last = run(0, MINUTE, CraneActivity.TRAVELLING);
        int gapSeconds = 10;
        window.record(last + gapSeconds * SECOND, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(MINUTE - gapSeconds * SECOND, throughput.travelTicks());
        assertEquals(1, throughput.idleTicks());
        assertEquals(0, throughput.pausedTicks());
        assertEquals(0, throughput.blockedTicks());
        assertEquals(MINUTE - gapSeconds * SECOND + 1, throughput.observedTicks());
    }

    @Test
    void aGapOfAMinuteOrMoreClearsTheWindow() {
        long last = run(0, MINUTE, CraneActivity.TRAVELLING);
        window.record(last + MINUTE, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(1, throughput.observedTicks());
        assertEquals(1, throughput.idleTicks());
        assertEquals(0, throughput.travelTicks());
    }

    @Test
    void aGapOfNearlyAMinuteKeepsTheOldestSecondThatIsStillInside() {
        long last = run(0, MINUTE, CraneActivity.TRAVELLING);
        window.record(last + (ThroughputWindow.BUCKETS - 1) * SECOND, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(SECOND, throughput.travelTicks());
        assertEquals(SECOND + 1, throughput.observedTicks());
    }

    @Test
    void aHugeGapIsBoundedAndClears() {
        run(START, 300, CraneActivity.TRAVELLING);
        window.record(START + 1_000_000_000L, CraneActivity.IDLE);
        assertEquals(1, window.snapshot().observedTicks());
    }

    @Test
    void aClockThatMovesBackwardsClearsTheWindow() {
        run(START, 300, CraneActivity.TRAVELLING);
        window.record(START - 5000, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(1, throughput.observedTicks());
        assertEquals(0, throughput.travelTicks());
    }

    @Test
    void aNegativeClockDoesNotBreakTheRing() {
        window.record(-30, CraneActivity.TRAVELLING);
        window.record(-10, CraneActivity.TRAVELLING);
        assertEquals(2, window.snapshot().observedTicks());
    }

    @Test
    void ticksOfTheSameSecondShareABucket() {
        window.record(0, CraneActivity.TRAVELLING);
        window.record(SECOND - 1, CraneActivity.TRAVELLING);
        assertEquals(2, window.snapshot().observedTicks());
        window.record(SECOND, CraneActivity.TRAVELLING);
        assertEquals(3, window.snapshot().observedTicks());
    }

    @Test
    void tripsAndItemsAreCounted() {
        window.record(START, CraneActivity.AT_A_STOP);
        window.addTrip();
        window.addTrip();
        window.addItems(64);
        window.addItems(12);
        window.addItems(0);
        CraneThroughput throughput = window.snapshot();
        assertEquals(2, throughput.trips());
        assertEquals(76, throughput.items());
        assertFalse(throughput.isEmpty());
    }

    @Test
    void tripsAndItemsAreForgottenWithTheirSecond() {
        window.record(0, CraneActivity.AT_A_STOP);
        window.addTrip();
        window.addItems(64);
        assertFalse(window.snapshot().isEmpty());
        // Two steps, neither of them a whole minute, so the ring really rolls onto the second that held them instead
        // of taking the clear-everything shortcut.
        window.record((ThroughputWindow.BUCKETS - 1) * SECOND, CraneActivity.IDLE);
        assertEquals(1, window.snapshot().trips());
        window.record(ThroughputWindow.BUCKETS * SECOND, CraneActivity.IDLE);
        CraneThroughput throughput = window.snapshot();
        assertEquals(0, throughput.trips());
        assertEquals(0, throughput.items());
        assertEquals(0, throughput.stopTicks());
        assertEquals(2, throughput.observedTicks());
        assertTrue(throughput.isEmpty());
    }

    @Test
    void cornersComeFromTheQuarterTurnsThatWereSwung() {
        int corners = 9;
        int ticksPerCorner = 3;
        long tick = START;
        for (int corner = 0; corner < corners; corner++) {
            for (int step = 0; step < ticksPerCorner; step++)
                window.record(tick++, CraneActivity.TURNING, 1.0 / ticksPerCorner);
        }
        CraneThroughput throughput = window.snapshot();
        assertEquals(corners, throughput.corners());
        assertEquals(corners * ticksPerCorner, throughput.turnTicks());
        assertEquals(corners * ticksPerCorner, throughput.observedTicks());
        assertEquals(100, throughput.turnShare());
    }

    @Test
    void aCornerTakenAnticlockwiseCountsToo() {
        for (int step = 0; step < 3; step++)
            window.record(START + step, CraneActivity.TURNING, -1.0 / 3.0);
        assertEquals(1, window.snapshot().corners());
    }

    /**
     * The corner count is measured over the same ticks as the turning share it is printed beside, so a swing on a tick
     * the window books as anything else adds no corner (M25 review fix).
     * <p>
     * The real case is the drive home: it has no job, so every tick of it is {@link CraneActivity#IDLE} by the
     * window's own precedence, and it really does swing on a warehouse that bends — which is the only kind that prints
     * a turning term at all. Counting those corners produced "turning 0 % (11 corners)" on a line that reads as one
     * phrase.
     */
    @Test
    void aSwingOnATickThatIsNotTurningAddsNoCorner() {
        for (int step = 0; step < 3; step++)
            window.record(START + step, CraneActivity.IDLE, 1.0 / 3.0);
        window.record(START + 3, CraneActivity.PAUSED, 1.0);
        window.record(START + 4, CraneActivity.TRAVELLING, 1.0);
        CraneThroughput idle = window.snapshot();
        assertEquals(0, idle.corners());
        assertEquals(0, idle.turnTicks());
        assertEquals(0, idle.turnShare());
        assertEquals(5, idle.observedTicks());

        // And the same window keeps counting the corners of the ticks that really are turning.
        for (int step = 0; step < 3; step++)
            window.record(START + 5 + step, CraneActivity.TURNING, 1.0 / 3.0);
        assertEquals(1, window.snapshot().corners());
    }

    @Test
    void cornersAreForgottenWithTheirSecond() {
        for (int step = 0; step < 3; step++)
            window.record(step, CraneActivity.TURNING, 1.0 / 3.0);
        assertEquals(1, window.snapshot().corners());
        window.record((ThroughputWindow.BUCKETS - 1) * SECOND, CraneActivity.IDLE);
        assertEquals(1, window.snapshot().corners());
        window.record(ThroughputWindow.BUCKETS * SECOND, CraneActivity.IDLE);
        assertEquals(0, window.snapshot().corners());
        assertEquals(0, window.snapshot().turnTicks());
    }

    @Test
    void aWindowOfOnlyIdleOrPausedTicksIsEmpty() {
        run(START, 100, CraneActivity.IDLE);
        run(START + 100, 100, CraneActivity.PAUSED);
        CraneThroughput throughput = window.snapshot();
        assertEquals(200, throughput.observedTicks());
        assertEquals(100, throughput.idleTicks());
        assertEquals(100, throughput.pausedTicks());
        assertTrue(throughput.isEmpty());
        assertEquals(50, throughput.idleShare());
        window.record(START + 200, CraneActivity.TRAVELLING);
        assertFalse(window.snapshot().isEmpty());
    }

    @Test
    void sharesAreFlooredAndTheBreakdownAddsUpToTheHeadline() {
        // 7 travel, 3 blocked out of 10: 70 % and 30 %.
        run(START, 7, CraneActivity.TRAVELLING);
        run(START + 7, 3, CraneActivity.BLOCKED);
        CraneThroughput throughput = window.snapshot();
        assertEquals(70, throughput.travelShare());
        assertEquals(70, throughput.busyShare());
        assertEquals(30, throughput.blockedShare());

        // The case the busy headline is summed for: one tick of each of the three working buckets out of three floors
        // to 33 % three times, so the headline must read 99 and not 100. Flooring the whole independently would print
        // "Busy: 100 %" over three lines adding up to 99, which is the contradiction a reader sees without being told
        // to look for it (M25 review fix). Nothing claims a total of exactly 100 either way.
        CraneThroughput thirds = new CraneThroughput(3, 0, 0, 1, 1, 1, 0, 0, 0, 0);
        assertEquals(33, thirds.travelShare());
        assertEquals(33, thirds.turnShare());
        assertEquals(33, thirds.stopShare());
        assertEquals(99, thirds.busyShare());
        assertEquals(thirds.travelShare() + thirds.turnShare() + thirds.stopShare(), thirds.busyShare());

        // And the same on a window that really ran: whatever the ticks, the four printed numbers agree.
        CraneThroughput mixed = new CraneThroughput(1200, 180, 0, 577, 83, 349, 11, 9, 412, 11);
        assertEquals(mixed.travelShare() + mixed.turnShare() + mixed.stopShare(), mixed.busyShare());
        assertTrue(mixed.busyShare() <= mixed.share(mixed.workingTicks()));
    }

    @Test
    void clearForgetsEverything() {
        run(START, 300, CraneActivity.TRAVELLING);
        window.addTrip();
        window.addItems(5);
        window.clear();
        assertEquals(CraneThroughput.EMPTY, window.snapshot());
        // And the window keeps working afterwards, with no second left over from before.
        window.record(START + 300, CraneActivity.TRAVELLING);
        assertEquals(1, window.snapshot().observedTicks());
    }

    @Test
    void snapshotDoesNotChangeTheWindow() {
        run(START, 42, CraneActivity.AT_A_STOP);
        CraneThroughput first = window.snapshot();
        assertEquals(first, window.snapshot());
        assertEquals(first, window.snapshot());
    }

    @Test
    void badInputIsRefused() {
        assertThrows(NullPointerException.class, () -> window.record(START, null));
        assertThrows(IllegalArgumentException.class,
                () -> window.record(START, CraneActivity.TURNING, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> window.addItems(-1));
        assertThrows(IllegalArgumentException.class, () -> new CraneThroughput(0, 0, 0, 0, 0, 0, 0, 0, -1, 0));
    }
}
