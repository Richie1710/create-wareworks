package dev.wareworks.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.inventory.CapacityMath;

/**
 * The three rack bay tiers (M28, issue #20): the ladder the issue settled, the strength order the column rule is built
 * on, and the promise that no number a config file, a per-world override or a hand-edited TOML can carry ever turns a
 * bay into a hole or makes its content count leave the {@code int} range.
 */
class BayTierTest {
    /** How far cobblestone stacks, i.e. the stack size the issue's capacity table is written for. */
    private static final int COBBLESTONE = 64;
    /** How far an ender pearl stacks: the item that shows what "counted in stacks" costs. */
    private static final int ENDER_PEARL = 16;

    // --- the ladder ------------------------------------------------------------------------------------------------

    /** The capacity table of issue #20: 64 / 256 / 1024 stacks, fourfold between tiers. */
    @Test
    void theShippedLadderRisesFourfold() {
        assertEquals(64, BayTier.WOOD.defaultStacks());
        assertEquals(256, BayTier.ANDESITE.defaultStacks());
        assertEquals(1024, BayTier.BRASS.defaultStacks());
        assertEquals(4 * BayTier.WOOD.defaultStacks(), BayTier.ANDESITE.defaultStacks());
        assertEquals(4 * BayTier.ANDESITE.defaultStacks(), BayTier.BRASS.defaultStacks());
    }

    /** A shipped default the config's own range would reject would be a crash on first load, not a bad number. */
    @Test
    void everyShippedDefaultIsInsideTheConfiguredRange() {
        for (BayTier tier : BayTier.values()) {
            assertTrue(tier.defaultStacks() >= BayTier.MIN_STACKS, tier + " default below the minimum");
            assertTrue(tier.defaultStacks() <= BayTier.MAX_STACKS, tier + " default above the maximum");
            assertEquals(tier.defaultStacks(), BayTier.clampStacks(tier.defaultStacks()), tier + " default clamped");
        }
    }

    // --- the strength order the column rule uses -------------------------------------------------------------------

    /**
     * The declaration order <b>is</b> the strength order, and {@link BayTier#strength()} is the ordinal. A tier
     * inserted in the middle rather than appended would silently reorder the column rule, so the order is pinned here
     * rather than remembered.
     */
    @Test
    void theDeclarationOrderIsTheStrengthOrder() {
        assertEquals(List.of(BayTier.WOOD, BayTier.ANDESITE, BayTier.BRASS), List.of(BayTier.values()));
        assertEquals(0, BayTier.WOOD.strength());
        assertEquals(1, BayTier.ANDESITE.strength());
        assertEquals(2, BayTier.BRASS.strength());
        for (BayTier tier : BayTier.values())
            assertEquals(tier.ordinal(), tier.strength(), tier + " strength is its ordinal");
    }

    @Test
    void strongerIsStrictAndOneDirectionOnly() {
        assertTrue(BayTier.BRASS.isStrongerThan(BayTier.ANDESITE));
        assertTrue(BayTier.BRASS.isStrongerThan(BayTier.WOOD));
        assertTrue(BayTier.ANDESITE.isStrongerThan(BayTier.WOOD));
        assertFalse(BayTier.WOOD.isStrongerThan(BayTier.ANDESITE));
        assertFalse(BayTier.WOOD.isStrongerThan(BayTier.BRASS));
        assertFalse(BayTier.ANDESITE.isStrongerThan(BayTier.BRASS));
        for (BayTier tier : BayTier.values())
            assertFalse(tier.isStrongerThan(tier), tier + " is not stronger than itself");
    }

    /** "A bay may carry nothing stronger above it", tier by tier. */
    @Test
    void aBayCarriesNothingStrongerAboveIt() {
        assertTrue(BayTier.WOOD.mayCarry(BayTier.WOOD));
        assertFalse(BayTier.WOOD.mayCarry(BayTier.ANDESITE));
        assertFalse(BayTier.WOOD.mayCarry(BayTier.BRASS));
        assertTrue(BayTier.ANDESITE.mayCarry(BayTier.WOOD));
        assertTrue(BayTier.ANDESITE.mayCarry(BayTier.ANDESITE));
        assertFalse(BayTier.ANDESITE.mayCarry(BayTier.BRASS));
        assertTrue(BayTier.BRASS.mayCarry(BayTier.WOOD));
        assertTrue(BayTier.BRASS.mayCarry(BayTier.ANDESITE));
        assertTrue(BayTier.BRASS.mayCarry(BayTier.BRASS));
    }

    /**
     * The rule is the same question from either side, which is what lets the placement check refuse both directions
     * with one method: of two different tiers exactly one order stands, and a column of one material is any height.
     */
    @Test
    void theRuleAnswersTheSameFromAboveAndBelow() {
        for (BayTier below : BayTier.values())
            for (BayTier above : BayTier.values()) {
                assertEquals(!above.isStrongerThan(below), below.mayCarry(above), below + " carrying " + above);
                if (below == above)
                    assertTrue(below.mayCarry(above) && above.mayCarry(below), "equal tiers carry each other");
                else
                    assertTrue(below.mayCarry(above) ^ above.mayCarry(below),
                            "exactly one order of " + below + " and " + above + " stands");
            }
    }

    /**
     * What the column rule amounts to over a whole column: strength never rises going upwards. Every three-high column
     * of the three tiers is checked against that, which is the property the placement walk has to preserve.
     */
    @Test
    void aLegalColumnNeverGrowsStrongerUpwards() {
        for (BayTier bottom : BayTier.values())
            for (BayTier middle : BayTier.values())
                for (BayTier top : BayTier.values()) {
                    boolean legal = bottom.mayCarry(middle) && middle.mayCarry(top);
                    boolean monotone = bottom.strength() >= middle.strength() && middle.strength() >= top.strength();
                    assertEquals(monotone, legal, bottom + " / " + middle + " / " + top);
                }
    }

    // --- absurd configuration --------------------------------------------------------------------------------------

    @Test
    void aSensibleStackCountIsKeptAsItIs() {
        assertEquals(1, BayTier.clampStacks(1));
        assertEquals(64, BayTier.clampStacks(64));
        assertEquals(1024, BayTier.clampStacks(1024));
        assertEquals(BayTier.MAX_STACKS, BayTier.clampStacks(BayTier.MAX_STACKS));
    }

    /** A bay of zero or fewer stacks would be a storage location that is full the moment it is placed. */
    @Test
    void aBayIsNeverSmallerThanOneStack() {
        assertEquals(BayTier.MIN_STACKS, BayTier.clampStacks(0));
        assertEquals(BayTier.MIN_STACKS, BayTier.clampStacks(-1));
        assertEquals(BayTier.MIN_STACKS, BayTier.clampStacks(-65536));
        assertEquals(BayTier.MIN_STACKS, BayTier.clampStacks(Integer.MIN_VALUE));
    }

    @Test
    void aBayIsNeverLargerThanTheCeiling() {
        assertEquals(BayTier.MAX_STACKS, BayTier.clampStacks(BayTier.MAX_STACKS + 1));
        assertEquals(BayTier.MAX_STACKS, BayTier.clampStacks(100_000));
        assertEquals(BayTier.MAX_STACKS, BayTier.clampStacks(Integer.MAX_VALUE));
    }

    /**
     * The ceiling exists to keep a bay's content count inside an {@code int}, and this is that claim as an assertion:
     * the largest capacity any configuration can ask for, of the largest stack Minecraft allows, still fits.
     */
    @Test
    void theCeilingKeepsAContentCountInsideAnInt() {
        assertEquals(4096, BayTier.MAX_STACKS);
        assertEquals(405_504, BayTier.MAX_CAPACITY_ITEMS);
        assertEquals((long) BayTier.MAX_STACKS * CapacityMath.STACK_SIZE_CEILING, BayTier.MAX_CAPACITY_ITEMS);
        assertTrue(BayTier.MAX_CAPACITY_ITEMS > 0, "no overflow in the constant itself");
        for (BayTier tier : BayTier.values())
            assertEquals(BayTier.MAX_CAPACITY_ITEMS, tier.capacity(Integer.MAX_VALUE, CapacityMath.STACK_SIZE_CEILING),
                    tier + " at the ceiling");
    }

    // --- capacity in items -----------------------------------------------------------------------------------------

    /** The "Cobblestone" column of the issue's table, and what the same bay is worth for a 16-stacking item. */
    @Test
    void theItemSaysWhatAStackIs() {
        assertEquals(4096L, BayTier.WOOD.capacity(BayTier.WOOD.defaultStacks(), COBBLESTONE));
        assertEquals(16_384L, BayTier.ANDESITE.capacity(BayTier.ANDESITE.defaultStacks(), COBBLESTONE));
        assertEquals(65_536L, BayTier.BRASS.capacity(BayTier.BRASS.defaultStacks(), COBBLESTONE));
        assertEquals(1024L, BayTier.WOOD.capacity(BayTier.WOOD.defaultStacks(), ENDER_PEARL));
        assertEquals(16_384L, BayTier.BRASS.capacity(BayTier.BRASS.defaultStacks(), ENDER_PEARL));
    }

    /** The configured number is the only thing that differs between the tiers, so any tier answers for any number. */
    @Test
    void theCapacityFollowsTheConfiguredNumberAndNotTheTier() {
        for (BayTier tier : BayTier.values())
            assertEquals(128L * COBBLESTONE, tier.capacity(128, COBBLESTONE), tier + " at 128 stacks");
    }

    @Test
    void anAbsurdStackCountIsClampedBeforeItIsMultiplied() {
        assertEquals((long) BayTier.MAX_STACKS * COBBLESTONE, BayTier.BRASS.capacity(Integer.MAX_VALUE, COBBLESTONE));
        assertEquals((long) BayTier.MIN_STACKS * COBBLESTONE, BayTier.WOOD.capacity(0, COBBLESTONE));
        assertEquals((long) BayTier.MIN_STACKS * COBBLESTONE, BayTier.WOOD.capacity(Integer.MIN_VALUE, COBBLESTONE));
    }

    /**
     * An item reporting no sensible stack size counts as one item per stack rather than turning the bay into a hole
     * that swallows everything or one that holds nothing, and nothing here throws: this is asked on every insert.
     */
    @Test
    void anItemWithoutASensibleStackSizeCountsAsOne() {
        assertEquals(64L, BayTier.WOOD.capacity(64, 1));
        assertEquals(64L, BayTier.WOOD.capacity(64, 0));
        assertEquals(64L, BayTier.WOOD.capacity(64, -8));
        assertEquals((long) BayTier.MIN_STACKS, BayTier.WOOD.capacity(0, 0));
    }

    @Test
    void noConfigurationMakesACapacityNegativeOrEmpty() {
        int[] stacks = {Integer.MIN_VALUE, -1, 0, 1, 64, BayTier.MAX_STACKS, BayTier.MAX_STACKS + 1,
                Integer.MAX_VALUE};
        int[] stackSizes = {Integer.MIN_VALUE, -1, 0, 1, 16, 64, CapacityMath.STACK_SIZE_CEILING};
        for (BayTier tier : BayTier.values())
            for (int configured : stacks)
                for (int maxStackSize : stackSizes) {
                    long capacity = tier.capacity(configured, maxStackSize);
                    assertTrue(capacity >= 1L, tier + " with " + configured + " x " + maxStackSize);
                    assertTrue(capacity <= BayTier.MAX_CAPACITY_ITEMS,
                            tier + " with " + configured + " x " + maxStackSize + " left the int range");
                }
    }
}
