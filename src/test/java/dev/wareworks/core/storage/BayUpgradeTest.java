package dev.wareworks.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The upgrade kits' steps (ADR-083): one kit per step of each ladder and no other, a kit fits exactly the tier it
 * starts from, and the column rule can only ever refuse an upgrade because of a bay <b>below</b>.
 */
class BayUpgradeTest {
    /** Every step is exactly one tier up its own ladder: no kit skips a tier, and none goes down. */
    @Test
    void everyStepIsExactlyOneTierUp() {
        for (BayUpgrade step : BayUpgrade.values())
            assertEquals(step.fromStrength() + 1, step.toStrength(), step + " is one step");
    }

    /**
     * Every tier but the top one of each ladder starts exactly one kit, and the top one none — so a tier appended to
     * either ladder without a kit of its own fails here rather than in a player's hands.
     */
    @Test
    void everyNonTopTierHasExactlyOneKit() {
        for (BayTier tier : BayTier.values())
            assertEquals(tier.next() == null ? 0 : 1, kitsFrom(BayFamily.ITEM, tier.strength()).size(),
                    "kits for a " + tier + " rack bay");
        for (FluidBayTier tier : FluidBayTier.values())
            assertEquals(tier.next() == null ? 0 : 1, kitsFrom(BayFamily.FLUID, tier.strength()).size(),
                    "kits for a " + tier + " fluid bay");
    }

    /** The source and the target name real tiers of the step's own ladder. */
    @Test
    void eachStepNamesRealTiersOfItsLadder() {
        for (BayUpgrade step : BayUpgrade.values()) {
            if (step.family() == BayFamily.ITEM) {
                BayTier from = BayTier.ofStrength(step.fromStrength());
                assertNotNull(from, step + " source");
                assertEquals(from.next(), BayTier.ofStrength(step.toStrength()), step + " target");
            } else {
                FluidBayTier from = FluidBayTier.ofStrength(step.fromStrength());
                assertNotNull(from, step + " source");
                assertEquals(from.next(), FluidBayTier.ofStrength(step.toStrength()), step + " target");
            }
        }
        assertEquals(BayTier.WOOD.strength(), BayUpgrade.ANDESITE_RACK.fromStrength());
        assertEquals(BayTier.ANDESITE.strength(), BayUpgrade.BRASS_RACK.fromStrength());
        assertEquals(FluidBayTier.COPPER.strength(), BayUpgrade.BRASS_FLUID.fromStrength());
    }

    /** A kit fits its own family at exactly its source strength, and nothing else: no downgrade, no other ladder. */
    @Test
    void aKitFitsOnlyItsOwnSource() {
        for (BayUpgrade step : BayUpgrade.values())
            for (BayFamily family : BayFamily.values())
                for (int strength = -1; strength <= 3; strength++)
                    assertEquals(family == step.family() && strength == step.fromStrength(),
                            step.fits(family, strength), step + " on " + family + " at " + strength);
    }

    /**
     * Monotonicity, the proof that only "below" can refuse: whatever the source tier carried above it, the target
     * carries too, and whatever carried the target from below also carried the source.
     */
    @Test
    void anUpgradeNeverMakesABayAboveIllegal() {
        for (BayUpgrade step : BayUpgrade.values()) {
            if (step.family() == BayFamily.ITEM) {
                BayTier from = BayTier.ofStrength(step.fromStrength());
                BayTier to = BayTier.ofStrength(step.toStrength());
                for (BayTier x : BayTier.values()) {
                    if (from.mayCarry(x))
                        assertTrue(to.mayCarry(x), step + ": " + to + " carries " + x);
                    if (x.mayCarry(to))
                        assertTrue(x.mayCarry(from), step + ": " + x + " carried " + from);
                }
                assertFalse(from.mayCarry(to), step + ": the source does not carry the target, so bottom-up");
            } else {
                FluidBayTier from = FluidBayTier.ofStrength(step.fromStrength());
                FluidBayTier to = FluidBayTier.ofStrength(step.toStrength());
                for (FluidBayTier x : FluidBayTier.values()) {
                    if (from.mayCarry(x))
                        assertTrue(to.mayCarry(x), step + ": " + to + " carries " + x);
                    if (x.mayCarry(to))
                        assertTrue(x.mayCarry(from), step + ": " + x + " carried " + from);
                }
                assertFalse(from.mayCarry(to), step + ": the source does not carry the target, so bottom-up");
            }
        }
    }

    private static List<BayUpgrade> kitsFrom(BayFamily family, int strength) {
        List<BayUpgrade> kits = new ArrayList<>();
        for (BayUpgrade step : BayUpgrade.values())
            if (step.fits(family, strength))
                kits.add(step);
        return kits;
    }
}
