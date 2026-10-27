package dev.wareworks.core.handtruck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The hand truck's size budget (issue #37): exactly the budget fits, one byte more does not. */
class HandTruckLimitsTest {
    @Test
    void exactlyTheBudgetFits() {
        assertTrue(HandTruckLimits.fits(512L * 1024L, 512));
        assertFalse(HandTruckLimits.fits(512L * 1024L + 1L, 512));
        assertTrue(HandTruckLimits.fits(0, 16));
        assertFalse(HandTruckLimits.fits(-1, 16), "a negative size is nonsense, not small");
    }

    @Test
    void kibRoundsUp() {
        assertEquals(0, HandTruckLimits.kib(0));
        assertEquals(1, HandTruckLimits.kib(1));
        assertEquals(1, HandTruckLimits.kib(1024));
        assertEquals(2, HandTruckLimits.kib(1025));
        assertEquals(513, HandTruckLimits.kib(512L * 1024L + 1L), "a refusal never reads as within the budget");
    }

    @Test
    void theConfiguredBudgetIsClamped() {
        assertEquals(HandTruckLimits.MIN_KIB, HandTruckLimits.clampKiB(0));
        assertEquals(HandTruckLimits.MAX_KIB, HandTruckLimits.clampKiB(1 << 20));
        assertEquals(HandTruckLimits.DEFAULT_KIB, HandTruckLimits.clampKiB(HandTruckLimits.DEFAULT_KIB));
        assertTrue(HandTruckLimits.fits(16L * 1024L, 1), "a budget under the minimum counts as the minimum");
        assertFalse(HandTruckLimits.fits(1024L * 1024L + 1L, Integer.MAX_VALUE),
                "a budget over the maximum counts as the maximum");
    }
}
