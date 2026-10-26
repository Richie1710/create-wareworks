package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * The layout a Crafter Feeder lays (issue #34, ADR-067): cell i is Crafter slot i, and the grid read that decides
 * whether the feeder may pulse.
 */
class CrafterGridTest {
    private static final String COAL = "coal";
    private static final String STICK = "stick";
    private static final String PLANK = "plank";

    /** Coal over a stick in the left column, the torch recipe. */
    private static CrafterGrid<String> torch() {
        return CrafterGrid.ofNullable(Arrays.asList(COAL, null, null, STICK, null, null, null, null, null));
    }

    private static List<Optional<String>> slots(String... keys) {
        List<Optional<String>> slots = new ArrayList<>(CrafterGrid.SLOTS);
        for (String key : keys)
            slots.add(Optional.ofNullable(key));
        return slots;
    }

    @Test
    void cellIsCrafterSlotForAllNine() {
        // The vanilla Crafter's container is row-major with a width of three, like a pattern grid.
        assertEquals(3, CrafterGrid.WIDTH);
        assertEquals(9, CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            assertEquals(cell, CrafterGrid.slotOf(cell));
        assertThrows(IllegalArgumentException.class, () -> CrafterGrid.slotOf(-1));
        assertThrows(IllegalArgumentException.class, () -> CrafterGrid.slotOf(9));
    }

    @Test
    void aGridHasNineCells() {
        assertThrows(IllegalArgumentException.class, () -> CrafterGrid.ofNullable(Arrays.asList(COAL, STICK)));
        assertTrue(CrafterGrid.<String>empty().isEmpty());
        assertEquals(0, CrafterGrid.<String>empty().usedMask());
    }

    @Test
    void theUsedMaskHasOneBitPerFilledCell() {
        CrafterGrid<String> grid = torch();
        assertEquals(0b1001, grid.usedMask());
        assertEquals(2, grid.usedCells());
        assertEquals(Optional.of(COAL), grid.at(0));
        assertEquals(Optional.empty(), grid.at(1));
        assertEquals(Optional.empty(), grid.at(42), "an index out of range is an empty cell");
    }

    @Test
    void cellsPerKeyCountsRepeatedItems() {
        CrafterGrid<String> sticks = CrafterGrid.ofNullable(Arrays.asList(PLANK, null, null, PLANK, null, null, null,
                null, null));
        assertEquals(Map.of(PLANK, 2), sticks.cellsPerKey());
        assertEquals(Map.of(COAL, 1, STICK, 1), torch().cellsPerKey());
    }

    @Test
    void allCellsSingleLooksAtFilledCellsOnly() {
        int[] counts = {1, 7, 7, 1, 7, 7, 7, 7, 7};
        assertTrue(torch().allCellsSingle(counts), "the counts of empty cells say nothing");
        counts[3] = 2;
        assertFalse(torch().allCellsSingle(counts));
    }

    @Test
    void anEmptyGridHoldsNoRuns() {
        assertEquals(OptionalInt.of(0), torch().runsIn(slots(null, null, null, null, null, null, null, null, null),
                new int[9]));
    }

    @Test
    void aLaidGridHoldsAsManyRunsAsEachUsedSlot() {
        int[] counts = {5, 0, 0, 5, 0, 0, 0, 0, 0};
        assertEquals(OptionalInt.of(5), torch().runsIn(slots(COAL, null, null, STICK, null, null, null, null, null),
                counts));
    }

    @Test
    void aDisturbedGridHoldsNoRunsAtAll() {
        // unequal counts
        assertTrue(torch().runsIn(slots(COAL, null, null, STICK, null, null, null, null, null),
                new int[] {5, 0, 0, 4, 0, 0, 0, 0, 0}).isEmpty());
        // an unused slot holds something
        assertTrue(torch().runsIn(slots(COAL, PLANK, null, STICK, null, null, null, null, null),
                new int[] {5, 1, 0, 5, 0, 0, 0, 0, 0}).isEmpty());
        // a used slot holds another item
        assertTrue(torch().runsIn(slots(STICK, null, null, COAL, null, null, null, null, null),
                new int[] {5, 0, 0, 5, 0, 0, 0, 0, 0}).isEmpty());
        // a used slot is empty while another is not
        assertTrue(torch().runsIn(slots(COAL, null, null, null, null, null, null, null, null),
                new int[] {5, 0, 0, 0, 0, 0, 0, 0, 0}).isEmpty());
        // a read of the wrong size
        assertTrue(torch().runsIn(slots(COAL), new int[] {1}).isEmpty());
    }
}
