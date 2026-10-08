package dev.wareworks.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The two tier ladders are <b>separate and not comparable</b> (M30 step 3, issue #21): the pure half of the rule that
 * lets a rack bay and a fluid bay share one column walk without either of them having to answer "is a copper tank
 * stronger than an andesite rack".
 * <p>
 * The rest of that rule is structural and lives in {@code content.storage.BayColumn}, which reaches a neighbouring bay
 * only through {@code sameFamilyBay} and therefore never compares strengths across ladders at all. What can be stated
 * here is the thing that would break it: a ladder whose constants disagreed about which family they are on, or a
 * family both ladders claimed.
 */
class BayFamilyTest {
    /** Every item tier is on the item ladder, and every fluid tier on the fluid one. One constant, no exceptions. */
    @Test
    void eachLadderNamesExactlyOneFamily() {
        for (BayTier tier : BayTier.values())
            assertEquals(BayFamily.ITEM, tier.family(), tier + " must be on the item ladder");
        for (FluidBayTier tier : FluidBayTier.values())
            assertEquals(BayFamily.FLUID, tier.family(), tier + " must be on the fluid ladder");
    }

    /**
     * The two ladders never meet, which is the whole point: if they shared a family, the column rule would compare a
     * {@link BayTier#strength()} with a {@link FluidBayTier#strength()} — two ordinals of unrelated enums, where
     * {@code BRASS} is 2 on one ladder and 1 on the other.
     */
    @Test
    void theTwoLaddersNeverShareAFamily() {
        Set<BayFamily> items = Arrays.stream(BayTier.values()).map(BayTier::family)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BayFamily.class)));
        Set<BayFamily> fluids = Arrays.stream(FluidBayTier.values()).map(FluidBayTier::family)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BayFamily.class)));
        assertEquals(EnumSet.of(BayFamily.ITEM), items);
        assertEquals(EnumSet.of(BayFamily.FLUID), fluids);
        assertTrue(EnumSet.copyOf(items).stream().noneMatch(fluids::contains),
                "a family both ladders claimed would make their strengths comparable");
    }

    /**
     * Every family has a ladder, so a block can never answer a family nothing is measured in. The assertion is the
     * other direction of the one above, and it is what fails the day a third family is declared without its tiers.
     */
    @Test
    void everyFamilyHasALadder() {
        Set<BayFamily> covered = EnumSet.noneOf(BayFamily.class);
        Arrays.stream(BayTier.values()).map(BayTier::family).forEach(covered::add);
        Arrays.stream(FluidBayTier.values()).map(FluidBayTier::family).forEach(covered::add);
        assertEquals(EnumSet.allOf(BayFamily.class), covered, "a family with no tier ladder behind it");
    }

    /**
     * The one number that would be read across ladders if the families were ever merged, stated so that the reason the
     * enums are separate is not only in a comment: {@code BRASS} is the strongest item tier and the strongest fluid
     * tier, and those two strengths are different integers.
     */
    @Test
    void brassIsADifferentStrengthOnEachLadder() {
        assertEquals(2, BayTier.BRASS.strength());
        assertEquals(1, FluidBayTier.BRASS.strength());
        assertNotEquals(BayTier.BRASS.strength(), FluidBayTier.BRASS.strength(),
                "one name, two ladders, two strengths — which is why they are never compared");
    }
}
