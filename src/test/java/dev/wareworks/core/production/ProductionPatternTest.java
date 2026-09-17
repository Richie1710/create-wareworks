package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rules a production pattern enforces at construction ({@code docs/warehouse-system.md} §3.5, ADR-024), the
 * <b>grid-to-multiset merge</b> that is the whole point of authoring a pattern in a 3 x 3 grid, and the arithmetic the
 * planner does with it.
 */
class ProductionPatternTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String NAIL = "nail";
    private static final String GLUE = "glue";
    private static final String TABLE = "table";

    /** One grid cell. */
    private static ProductionEntry<String> cell(String key, int count) {
        return new ProductionEntry<>(key, count);
    }

    @Test
    void oneLogMakesFourPlanks() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        assertTrue(pattern.produces(PLANK));
        assertFalse(pattern.produces(LOG));
        assertTrue(pattern.consumes(LOG));
        assertFalse(pattern.consumes(PLANK));
        assertEquals(1, pattern.ingredients().size());
        assertEquals(4, pattern.resultFor(1));
        assertEquals(1, pattern.ingredientItems());
    }

    // --- the grid ------------------------------------------------------------------------------------------------

    /**
     * The reason the grid exists at all: a recipe reads as a layout, but the planner needs one number per item,
     * because the crane carries one item key per trip. Three cells of a plank are "3 planks" and therefore <b>one</b>
     * crane trip, not three.
     */
    @Test
    void cellsNamingTheSameItemMergeIntoOneIngredient() {
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(
                List.of(cell(PLANK, 1), cell(PLANK, 1), cell(PLANK, 1)), cell(TABLE, 1));
        assertEquals(1, pattern.ingredients().size(), "one ingredient, not three");
        assertEquals(cell(PLANK, 3), pattern.ingredients().getFirst());
        assertEquals(3, pattern.ingredientItems());
    }

    /** Cells with counts of their own add up, and the ingredients keep the order the grid introduces them in. */
    @Test
    void theMergeSumsCountsAndKeepsGridOrder() {
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(
                List.of(cell(PLANK, 2), cell(NAIL, 1), cell(PLANK, 4), cell(GLUE, 1), cell(NAIL, 2)), cell(TABLE, 1));
        assertEquals(List.of(cell(PLANK, 6), cell(NAIL, 3), cell(GLUE, 1)), pattern.ingredients());
        assertEquals(10, pattern.ingredientItems());
    }

    /** A full grid of nine distinct items is the largest pattern there is. */
    @Test
    void nineDistinctCellsAreAllowed() {
        List<ProductionEntry<String>> cells = new ArrayList<>();
        for (int i = 0; i < ProductionPattern.GRID_SIZE; i++)
            cells.add(cell("item" + i, 1));
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(cells, cell(TABLE, 1));
        assertEquals(ProductionPattern.GRID_SIZE, pattern.ingredients().size());
        assertEquals(ProductionPattern.MAX_INGREDIENTS, ProductionPattern.GRID_SIZE,
                "a grid cannot hold more distinct items than it has cells");
    }

    @Test
    void aGridNeedsAtLeastOneCellAndAtMostNine() {
        assertThrows(IllegalArgumentException.class, () -> ProductionPattern.fromGrid(List.of(), cell(PLANK, 1)));
        List<ProductionEntry<String>> tooMany = new ArrayList<>();
        for (int i = 0; i <= ProductionPattern.GRID_SIZE; i++)
            tooMany.add(cell("item" + i, 1));
        assertThrows(IllegalArgumentException.class, () -> ProductionPattern.fromGrid(tooMany, cell(TABLE, 1)));
    }

    /** A whole grid of full stacks is the largest an ingredient can become, and it saturates rather than overflowing. */
    @Test
    void aMergedIngredientSaturatesAtAFullGridOfStacks() {
        List<ProductionEntry<String>> cells = new ArrayList<>();
        for (int i = 0; i < ProductionPattern.GRID_SIZE; i++)
            cells.add(cell(PLANK, ProductionEntry.MAX_PER_CELL));
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(cells, cell(TABLE, 1));
        assertEquals(ProductionEntry.MAX_COUNT, pattern.ingredients().getFirst().count());
        assertEquals(ProductionEntry.MAX_PER_CELL * ProductionPattern.GRID_SIZE, ProductionEntry.MAX_COUNT);
    }

    /** One cell holds at most a stack; the merged total of a whole pattern may be larger than that. */
    @Test
    void cellCountsAreClampedToOneStackButTotalsAreNot() {
        assertThrows(IllegalArgumentException.class, () -> cell(LOG, 0));
        assertThrows(IllegalArgumentException.class, () -> cell(LOG, ProductionEntry.MAX_COUNT + 1));
        assertEquals(ProductionEntry.MIN_COUNT, ProductionEntry.clampCellCount(-5));
        assertEquals(ProductionEntry.MAX_PER_CELL, ProductionEntry.clampCellCount(1000),
                "a grid cell holds at most one stack");
        assertEquals(7, ProductionEntry.sanitized(LOG, 7).count());
        assertEquals(cell(PLANK, 70), cell(PLANK, 64).plus(6), "but a merged total may exceed a stack");
        assertThrows(IllegalArgumentException.class, () -> cell(PLANK, 1).plus(0));
    }

    // --- the two construction rules ------------------------------------------------------------------------------

    @Test
    void aPatternNeedsAtLeastOneIngredient() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPattern<>(List.of(), cell(PLANK, 1)));
    }

    /** The merged list is a multiset: a caller that builds one by hand may not repeat a key. */
    @Test
    void aMergedIngredientListMayNotRepeatAKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPattern<>(List.of(cell(LOG, 1), cell(LOG, 2)), cell(PLANK, 4)));
    }

    /**
     * The rule that keeps stage 1 finite: a pattern that produced one of its own ingredients would let the producible
     * computation promise an item out of itself, and nothing would ever resolve it.
     */
    @Test
    void aPatternMayNotProduceItsOwnIngredient() {
        assertThrows(IllegalArgumentException.class, () -> ProductionPattern.of(PLANK, 1, PLANK, 2));
        assertThrows(IllegalArgumentException.class, () -> new ProductionPattern<>(
                List.of(cell(LOG, 1), cell(NAIL, 1)), cell(NAIL, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> ProductionPattern.fromGrid(List.of(cell(LOG, 1), cell(NAIL, 1)), cell(LOG, 4)));
    }

    // --- the batching maths --------------------------------------------------------------------------------------

    @Test
    void runsForRoundsUpAndIsAtLeastOne() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        assertEquals(1, pattern.runsFor(0), "no demand still needs a run to make anything");
        assertEquals(1, pattern.runsFor(1));
        assertEquals(1, pattern.runsFor(4));
        assertEquals(2, pattern.runsFor(5));
        assertEquals(16, pattern.runsFor(64));
    }

    /**
     * Several crafts of one pattern are one number per ingredient, not one order per craft: 10 planks need 3 runs, and
     * those 3 runs are 3 logs — a single crane trip, because the crane carries one item key at a time
     * ({@code ProductionOrder#start} turns this into one {@code SupplyLine}).
     */
    @Test
    void severalRunsMultiplyEachIngredientOnce() {
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(
                List.of(cell(PLANK, 2), cell(NAIL, 1), cell(PLANK, 2)), cell(TABLE, 1));
        int runs = pattern.runsFor(3);
        assertEquals(3, runs);
        assertEquals(12, pattern.ingredients().getFirst().totalFor(runs), "4 planks per run, three runs");
        assertEquals(3, pattern.ingredients().get(1).totalFor(runs));
        assertEquals(3, pattern.resultFor(runs));
    }

    @Test
    void totalsSaturateInsteadOfOverflowing() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 64, PLANK, 64);
        assertEquals(Integer.MAX_VALUE, pattern.resultFor(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, pattern.ingredients().getFirst().totalFor(Integer.MAX_VALUE));
        assertTrue(pattern.runsFor(Long.MAX_VALUE) > 0, "an absurd demand must not wrap around");
    }
}
