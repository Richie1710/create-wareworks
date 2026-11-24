package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The fluid cell of a production pattern (issue #55, ADR-089): its bounds, the wheel's steps, the whole-container
 * arithmetic with the real size of the container, and how a pattern holds fluid cells.
 */
class FluidCellTest {
    private static final String WATER = "minecraft:water";
    private static final String LAVA = "minecraft:lava";

    @Test
    void anAmountIsBoundedByOneBasinSegment() {
        assertEquals(1, new FluidCell(WATER, 1).millibuckets());
        assertEquals(1000, new FluidCell(WATER, 1000).millibuckets());
        assertThrows(IllegalArgumentException.class, () -> new FluidCell(WATER, 0));
        assertThrows(IllegalArgumentException.class, () -> new FluidCell(WATER, 1001));
        assertThrows(IllegalArgumentException.class, () -> new FluidCell(" ", 250));
        assertThrows(NullPointerException.class, () -> new FluidCell(null, 250));
    }

    @Test
    void untrustedAmountsAreClampedNotRefused() {
        assertEquals(1, FluidCell.sanitized(WATER, -5).millibuckets());
        assertEquals(1000, FluidCell.sanitized(WATER, Integer.MAX_VALUE).millibuckets());
        assertEquals(250, FluidCell.sanitized(WATER, 250).millibuckets());
    }

    @Test
    void theWheelStepsAndClamps() {
        FluidCell cell = new FluidCell(WATER, 250);
        assertEquals(300, cell.stepped(1, FluidCell.STEP_SMALL));
        assertEquals(200, cell.stepped(-1, FluidCell.STEP_SMALL));
        assertEquals(500, cell.stepped(1, FluidCell.STEP_MEDIUM));
        assertEquals(1000, cell.stepped(1, FluidCell.STEP_LARGE), "1250 clamps to the Basin segment of 1000");
        assertEquals(1, cell.stepped(-1, FluidCell.STEP_LARGE), "-750 clamps to 1");
        assertEquals(1, new FluidCell(WATER, 1).stepped(-1, FluidCell.STEP_SMALL));
        assertEquals(1000, new FluidCell(WATER, 1000).stepped(Integer.MAX_VALUE, FluidCell.STEP_LARGE),
                "no overflow");
    }

    @Test
    void anAmountThatDividesABucketIsMarked() {
        for (int mb : new int[] {1, 2, 4, 5, 8, 10, 20, 25, 40, 50, 100, 125, 200, 250, 500, 1000})
            assertTrue(new FluidCell(WATER, mb).dividesBucket(), mb + " divides 1000");
        for (int mb : new int[] {3, 300, 333, 600, 750, 999})
            assertFalse(new FluidCell(WATER, mb).dividesBucket(), mb + " does not");
    }

    /** The number the planner needs: whole containers, rounded up, with the size of the container it planned. */
    @Test
    void containersAreWholeAndRoundedUpWithTheRealSize() {
        FluidCell quarter = new FluidCell(WATER, 250);
        assertEquals(1, quarter.containersFor(1, 1000), "one run still needs a whole bucket");
        assertEquals(1, quarter.containersFor(4, 1000), "four quarters are one bucket");
        assertEquals(2, quarter.containersFor(5, 1000), "a fifth run needs the second bucket");
        assertEquals(13, quarter.containersFor(50, 1000));
        FluidCell lot = new FluidCell(LAVA, 750);
        assertEquals(1, lot.containersFor(1, 1000));
        assertEquals(2, lot.containersFor(2, 1000), "1500 mB are two buckets, 500 mB stay in the machine");
        assertEquals(3, lot.containersFor(3, 1000));
        // The container's real content, not a constant: a tank item of 4000 mB serves sixteen quarter runs.
        assertEquals(1, quarter.containersFor(16, 4000));
        assertEquals(2, quarter.containersFor(17, 4000));
        assertEquals(1, new FluidCell(WATER, 1000).containersFor(1, 4000));
        // A container smaller than a run: three of 250 mB for one 750 run.
        assertEquals(3, lot.containersFor(1, 250));
    }

    @Test
    void containersNeverOverflow() {
        assertEquals(Integer.MAX_VALUE, new FluidCell(WATER, 1000).containersFor(Integer.MAX_VALUE, 1));
        assertEquals(1000L * 7, new FluidCell(WATER, 1000).totalFor(7));
        assertThrows(IllegalArgumentException.class, () -> new FluidCell(WATER, 1).containersFor(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new FluidCell(WATER, 1).containersFor(1, 0));
    }

    // --- in a pattern --------------------------------------------------------------------------------------------

    private static ProductionEntry<String> entry(String key, int count) {
        return new ProductionEntry<>(key, count);
    }

    @Test
    void aFluidCellAloneIsAnIngredient() {
        ProductionPattern<String> pattern = new ProductionPattern<>(List.of(), entry("mud", 1), Set.of(),
                List.of(new FluidCell(WATER, 250)));
        assertTrue(pattern.hasFluids());
        assertEquals(0, pattern.ingredientItems(), "a fluid is no item");
        assertEquals(List.of(new FluidCell(WATER, 250)), pattern.fluids());
        assertFalse(pattern.consumes("mud"));
    }

    @Test
    void aPatternNeedsAnItemOrAFluid() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPattern<>(List.of(), entry("mud", 1), Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ProductionPattern.fromGrid(List.<ProductionEntry<String>>of(), entry("mud", 1)));
        assertEquals(List.of(), ProductionPattern.fromGrid(List.of(entry("dirt", 1)), entry("mud", 1)).fluids());
    }

    @Test
    void atMostTwoDistinctFluids() {
        FluidCell water = new FluidCell(WATER, 250);
        FluidCell lava = new FluidCell(LAVA, 500);
        ProductionPattern<String> two = ProductionPattern.fromGrid(List.of(entry("dirt", 1)), entry("mud", 1),
                Set.of(), List.of(water, lava));
        assertEquals(List.of(water, lava), two.fluids());
        assertThrows(IllegalArgumentException.class, () -> ProductionPattern.fromGrid(List.of(entry("dirt", 1)),
                entry("mud", 1), Set.of(), List.of(water, lava, new FluidCell("minecraft:milk", 100))));
        assertThrows(IllegalArgumentException.class, () -> ProductionPattern.fromGrid(List.of(entry("dirt", 1)),
                entry("mud", 1), Set.of(), List.of(water, new FluidCell(WATER, 500))),
                "the same fluid twice is one ingredient, not two");
    }

    /** A pattern without a fluid cell is the very same value it was before fluid cells existed. */
    @Test
    void aPatternWithoutFluidsIsTheOldValue() {
        ProductionPattern<String> old = new ProductionPattern<>(List.of(entry("log", 1)), entry("plank", 4));
        ProductionPattern<String> viaGrid = ProductionPattern.fromGrid(List.of(entry("log", 1)), entry("plank", 4));
        ProductionPattern<String> explicit = new ProductionPattern<>(List.of(entry("log", 1)), entry("plank", 4),
                Set.of(), List.of());
        assertEquals(old, viaGrid);
        assertEquals(old, explicit);
        assertEquals(old.hashCode(), explicit.hashCode());
        assertFalse(old.hasFluids());
        assertEquals(List.of(), old.fluids());
        assertFalse(old.equals(new ProductionPattern<>(List.of(entry("log", 1)), entry("plank", 4), Set.of(),
                List.of(new FluidCell(WATER, 1)))), "a fluid makes it another pattern");
    }
}
