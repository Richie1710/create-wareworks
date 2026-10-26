package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The Crafter Feeder's pulse (issue #34, ADR-067): exactly one rising edge per run, high for two ticks, never two edges
 * closer than the minimum run, and the grid read after the craft.
 */
class FeederCycleTest {
    /**
     * Drives a cycle for {@code ticks} ticks, raising whenever it is ready and {@code allowed} says so, and records the
     * signal level after each tick.
     */
    private static List<Boolean> drive(FeederCycle cycle, int ticks, int ticksPerRun, boolean allowed,
            List<Integer> edges, List<Integer> verifies) {
        List<Boolean> signal = new ArrayList<>(ticks);
        boolean high = false;
        for (int tick = 0; tick < ticks; tick++) {
            FeederCycle.Step step = cycle.advance();
            if (step == FeederCycle.Step.LOWER)
                high = false;
            if (step == FeederCycle.Step.VERIFY)
                verifies.add(tick);
            if (allowed && cycle.ready()) {
                cycle.raise(ticksPerRun);
                high = true;
                edges.add(tick);
            }
            signal.add(high);
        }
        return signal;
    }

    @Test
    void oneEdgePerRunHighForTwoTicks() {
        FeederCycle cycle = new FeederCycle();
        List<Integer> edges = new ArrayList<>();
        List<Integer> verifies = new ArrayList<>();
        List<Boolean> signal = drive(cycle, 40, FeederCycle.MIN_RUN_TICKS, true, edges, verifies);
        assertEquals(List.of(0, 8, 16, 24, 32), edges);
        assertEquals(List.of(6, 14, 22, 30, 38), verifies, "the grid is read after the craft");
        for (int edge : edges) {
            assertTrue(signal.get(edge));
            assertTrue(signal.get(edge + 1));
            assertFalse(signal.get(edge + 2), "low again after " + FeederCycle.PULSE_HIGH_TICKS + " ticks");
        }
    }

    @Test
    void neverTwoEdgesWithinTheMinimumRun() {
        FeederCycle cycle = new FeederCycle();
        List<Integer> edges = new ArrayList<>();
        drive(cycle, 200, 1, true, edges, new ArrayList<>());
        for (int i = 1; i < edges.size(); i++)
            assertTrue(edges.get(i) - edges.get(i - 1) >= FeederCycle.MIN_RUN_TICKS, "edges " + edges);
        assertTrue(FeederCycle.MIN_RUN_TICKS > FeederCycle.CRAFT_DELAY_TICKS + 1,
                "every edge finds the previous craft consumed");
        assertTrue(FeederCycle.VERIFY_AFTER_TICKS > FeederCycle.CRAFT_DELAY_TICKS);
    }

    @Test
    void noEdgeWhileBlocked() {
        FeederCycle cycle = new FeederCycle();
        List<Integer> edges = new ArrayList<>();
        drive(cycle, 50, FeederCycle.MIN_RUN_TICKS, false, edges, new ArrayList<>());
        assertTrue(edges.isEmpty());
        assertTrue(cycle.ready());
        assertThrows(IllegalStateException.class, () -> {
            cycle.raise(8);
            cycle.raise(8);
        }, "a second raise during a run is a bug");
    }

    @Test
    void loweringEarlyKeepsTheReadOfTheCraft() {
        FeederCycle cycle = new FeederCycle();
        cycle.raise(8);
        assertTrue(cycle.high());
        assertTrue(cycle.lowerEarly(), "the caller has to lower the signal");
        assertFalse(cycle.high());
        assertFalse(cycle.lowerEarly(), "only once");
        FeederCycle.Step step = FeederCycle.Step.NONE;
        for (int tick = 0; tick < FeederCycle.VERIFY_AFTER_TICKS; tick++)
            step = cycle.advance();
        assertEquals(FeederCycle.Step.VERIFY, step, "the craft its edge scheduled is still read");
    }

    @Test
    void aLoadStartsInTheCooldown() {
        FeederCycle cycle = new FeederCycle();
        cycle.cooldown(8);
        assertFalse(cycle.ready());
        assertFalse(cycle.inPulse());
        for (int tick = 0; tick < 8; tick++)
            cycle.advance();
        assertTrue(cycle.ready());
    }

    @Test
    void aFailedCraftBacksOff() {
        FeederCycle cycle = new FeederCycle();
        cycle.raise(8);
        for (int tick = 0; tick < FeederCycle.VERIFY_AFTER_TICKS; tick++)
            cycle.advance();
        cycle.backOff();
        int waited = FeederCycle.VERIFY_AFTER_TICKS;
        while (!cycle.ready()) {
            cycle.advance();
            waited++;
        }
        assertEquals(FeederCycle.FAILED_BACKOFF_TICKS, waited);
    }

    @Test
    void ticksPerRunFollowsTheSpeed() {
        assertEquals(256, FeederCycle.ticksPerRun(0f, true), "no rotation: the slowest pace (and no run at all)");
        assertEquals(256, FeederCycle.ticksPerRun(1f, true));
        assertEquals(16, FeederCycle.ticksPerRun(16f, true));
        assertEquals(8, FeederCycle.ticksPerRun(32f, true));
        assertEquals(8, FeederCycle.ticksPerRun(-256f, true), "the direction does not matter");
        assertEquals(26, FeederCycle.ticksPerRun(10f, true), "rounded up");
        assertEquals(8, FeederCycle.ticksPerRun(0f, false), "rotation optional: the fastest pace");
        assertEquals(8, FeederCycle.ticksPerRun(1f, false));
    }
}
