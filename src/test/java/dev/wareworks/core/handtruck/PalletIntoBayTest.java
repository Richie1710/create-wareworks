package dev.wareworks.core.handtruck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.handtruck.PalletIntoBay.Verdict;
import dev.wareworks.core.storage.BayTier;

/** A pallet goes into a rack bay whole or not at all (ADR-084): every verdict and the order of the refusals. */
class PalletIntoBayTest {
    /** A wooden bay of cobblestone at the shipped capacity. */
    private static final long WOOD_COBBLESTONE = BayTier.WOOD.capacity(BayTier.WOOD.defaultStacks(), 64);

    @Test
    void anEmptyBayThatHoldsItAllFits() {
        assertEquals(4_096L, WOOD_COBBLESTONE, "precondition: a wooden bay holds 64 stacks of cobblestone");
        assertEquals(Verdict.FITS, PalletIntoBay.verdict(true, false, true, WOOD_COBBLESTONE, 4_096));
        assertEquals(Verdict.FITS, PalletIntoBay.verdict(true, false, true, WOOD_COBBLESTONE, 1));
    }

    @Test
    void oneItemMoreThanTheCapacityIsTooSmall() {
        assertEquals(Verdict.TOO_SMALL, PalletIntoBay.verdict(true, false, true, WOOD_COBBLESTONE, 4_097));
        assertEquals(Verdict.TOO_SMALL, PalletIntoBay.verdict(true, false, true, 0, 1));
    }

    @Test
    void eachRefusalOnItsOwn() {
        assertEquals(Verdict.NOT_EMPTY, PalletIntoBay.verdict(false, false, true, WOOD_COBBLESTONE, 1));
        assertEquals(Verdict.CRANE_BRINGING, PalletIntoBay.verdict(true, true, true, WOOD_COBBLESTONE, 1));
        assertEquals(Verdict.FILTERED, PalletIntoBay.verdict(true, false, false, WOOD_COBBLESTONE, 1));
    }

    @Test
    void theFirstRefusalWins() {
        assertEquals(Verdict.NOT_EMPTY, PalletIntoBay.verdict(false, true, false, 0, 4_097),
                "a bay that is not empty says so before anything else");
        assertEquals(Verdict.CRANE_BRINGING, PalletIntoBay.verdict(true, true, false, 0, 4_097),
                "a crane on its way comes before the filter");
        assertEquals(Verdict.FILTERED, PalletIntoBay.verdict(true, false, false, 0, 4_097),
                "the filter comes before the size: a bay that refuses the item is not too small for it");
    }
}
