package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.production.ProductionPattern;

/**
 * The crafting panel's amount field counts result items and a fetch counts crafts (issue #33): the rounding between
 * them, that a number the panel shows types back into the same crafts, and that it is the rule production orders use.
 */
class CraftAmountsTest {
    /** The field's default maximum ({@code maxTerminalRequestAmount}). */
    private static final int FIELD_MAX = 1024;
    /** The largest result count a crafting recipe can have: one full stack. */
    private static final int MAX_PER_CRAFT = 64;

    /** The issue's own numbers: four Stone make four Stone Bricks. */
    @Test
    void itemsRoundUpToWholeCrafts() {
        assertEquals(1, CraftAmounts.craftsFor(4, 4), "4 bricks are one craft, not four");
        assertEquals(2, CraftAmounts.craftsFor(5, 4), "5 bricks round up to two crafts");
        assertEquals(2, CraftAmounts.craftsFor(8, 4));
        assertEquals(3, CraftAmounts.craftsFor(9, 4));
        assertEquals(1, CraftAmounts.craftsFor(1, 4), "a single brick still needs one craft");
    }

    /** A recipe that makes one item counts the same in items and in crafts: nothing changes for it. */
    @Test
    void oneItemRecipesCountCrafts() {
        for (int items = 1; items <= FIELD_MAX; items++) {
            assertEquals(items, CraftAmounts.craftsFor(items, 1));
            assertEquals(items, CraftAmounts.itemsFor(items, 1));
        }
    }

    @Test
    void edgesAreClampedNotThrown() {
        assertEquals(1, CraftAmounts.craftsFor(0, 4), "nothing asked is still one craft");
        assertEquals(1, CraftAmounts.craftsFor(-3, 4));
        assertEquals(5, CraftAmounts.craftsFor(5, 0), "no known result counts one per craft");
        assertEquals(5, CraftAmounts.craftsFor(5, -2));
        assertEquals(Integer.MAX_VALUE, CraftAmounts.craftsFor(Long.MAX_VALUE, 1), "saturates");
        assertEquals(Integer.MAX_VALUE, CraftAmounts.craftsFor(Long.MAX_VALUE, 7), "no overflow on the way");
        assertEquals(0L, CraftAmounts.itemsFor(-1, 4));
        assertEquals(3L, CraftAmounts.itemsFor(3, 0));
    }

    @Test
    void itemsMultiplyWithoutOverflow() {
        assertEquals(8L, CraftAmounts.itemsFor(2, 4));
        assertEquals(1L, CraftAmounts.itemsFor(1, 1));
        assertEquals(65_536L * 99, CraftAmounts.itemsFor(65_536, 99));
        assertEquals((long) Integer.MAX_VALUE * MAX_PER_CRAFT, CraftAmounts.itemsFor(Integer.MAX_VALUE, MAX_PER_CRAFT));
    }

    /** A number of items the panel shows for some crafts, typed into the field, asks for exactly those crafts. */
    @Test
    void shownItemsTypeBackIntoTheSameCrafts() {
        for (int perCraft = 1; perCraft <= MAX_PER_CRAFT; perCraft++) {
            for (int crafts = 1; crafts <= 200; crafts++)
                assertEquals(crafts, CraftAmounts.craftsFor(CraftAmounts.itemsFor(crafts, perCraft), perCraft),
                        crafts + " crafts of " + perCraft);
        }
    }

    /** What the Fetch announces is never less than the field and at most one craft's worth more. */
    @Test
    void announcedItemsCoverTheFieldByLessThanOneCraft() {
        for (int perCraft = 1; perCraft <= MAX_PER_CRAFT; perCraft++) {
            for (int field = 1; field <= FIELD_MAX; field++) {
                long announced = CraftAmounts.itemsFor(CraftAmounts.craftsFor(field, perCraft), perCraft);
                assertTrue(announced >= field, field + " of " + perCraft + " announced " + announced);
                assertTrue(announced < field + perCraft, field + " of " + perCraft + " announced " + announced);
            }
        }
    }

    /**
     * The question's "Set x… to fetch only that" names fewer crafts than the Fetch asked for, so the items it shows are
     * below the field the player typed, and therefore always under the field's maximum.
     */
    @Test
    void aSmallerCraftCountAlwaysFitsUnderTheField() {
        for (int perCraft = 1; perCraft <= MAX_PER_CRAFT; perCraft++) {
            for (int field = 1; field <= FIELD_MAX; field++) {
                int asked = CraftAmounts.craftsFor(field, perCraft);
                for (int fewer = 0; fewer < asked; fewer++)
                    assertTrue(CraftAmounts.itemsFor(fewer, perCraft) < field,
                            fewer + " crafts of " + perCraft + " under field " + field);
            }
        }
    }

    /** The same rounding as a production order's: two implementations of one rule must not drift apart. */
    @Test
    void agreesWithProductionRuns() {
        for (int perCraft = 1; perCraft <= MAX_PER_CRAFT; perCraft++) {
            ProductionPattern<String> pattern = ProductionPattern.of("in", 1, "out", perCraft);
            for (long items = 0; items <= 300; items++)
                assertEquals(pattern.runsFor(items), CraftAmounts.craftsFor(items, perCraft),
                        items + " items of " + perCraft);
        }
    }
}
