package dev.wareworks.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Warehouse Remote's range (issue #44): exactly the range reaches, a little more does not, and it is clamped. */
class RemoteLimitsTest {
    @Test
    void exactlyTheRangeReaches() {
        assertTrue(RemoteLimits.withinRange(256.0, 256));
        assertFalse(RemoteLimits.withinRange(256.001, 256));
        assertTrue(RemoteLimits.withinRange(0.0, 16));
        assertFalse(RemoteLimits.withinRange(-1.0, 16), "a negative distance is nonsense, not near");
        assertFalse(RemoteLimits.withinRange(Double.NaN, RemoteLimits.MAX_RANGE), "no distance reaches nothing");
    }

    @Test
    void theConfiguredRangeIsClamped() {
        assertEquals(RemoteLimits.MIN_RANGE, RemoteLimits.clampRange(0));
        assertEquals(RemoteLimits.MIN_RANGE, RemoteLimits.clampRange(Integer.MIN_VALUE));
        assertEquals(RemoteLimits.MAX_RANGE, RemoteLimits.clampRange(Integer.MAX_VALUE));
        assertEquals(RemoteLimits.DEFAULT_RANGE, RemoteLimits.clampRange(RemoteLimits.DEFAULT_RANGE));
        assertTrue(RemoteLimits.withinRange(16.0, 1), "a range under the minimum counts as the minimum");
        assertFalse(RemoteLimits.withinRange(30_000_001.0, Integer.MAX_VALUE), "a range over the maximum counts as it");
    }

    @Test
    void aShownDistanceRoundsUp() {
        assertEquals(0, RemoteLimits.shownBlocks(0.0));
        assertEquals(0, RemoteLimits.shownBlocks(Double.NaN));
        assertEquals(1, RemoteLimits.shownBlocks(0.2));
        assertEquals(256, RemoteLimits.shownBlocks(256.0));
        assertEquals(257, RemoteLimits.shownBlocks(256.001), "a refusal never reads as within the range");
    }
}
