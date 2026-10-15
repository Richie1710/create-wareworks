package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.inventory.SlotView;

/**
 * The recipe a player lays in a terminal's crafting panel (issue #24, M32): the merge into one multiset that every
 * later step works with, how many crafts a buffer holds, and what Fetch has to bring for a number of them.
 */
class CraftGridTest {
    private static final String PLANK = "oak_planks";
    private static final String STICK = "stick";
    private static final String COBBLE = "cobblestone";
    private static final String REDSTONE = "redstone";
    private static final String IRON = "iron_ingot";
    private static final String FILLED_MAP = "filled_map";
    private static final String MAP = "map";

    /** A chest: eight planks around an empty centre. */
    private static CraftGrid<String> chest() {
        return CraftGrid.of(PLANK, PLANK, PLANK, PLANK, null, PLANK, PLANK, PLANK, PLANK);
    }

    /** A piston: planks on top, cobblestone around an iron ingot, redstone below it. */
    private static CraftGrid<String> piston() {
        return CraftGrid.of(PLANK, PLANK, PLANK, COBBLE, IRON, COBBLE, COBBLE, REDSTONE, COBBLE);
    }

    // --- the grid itself ---------------------------------------------------------------------------------------------

    @Test
    void anEmptyGridHasNineEmptyCells() {
        CraftGrid<String> grid = CraftGrid.empty();
        assertEquals(CraftGrid.SIZE, grid.cells().size());
        assertTrue(grid.isEmpty());
        assertEquals(0, grid.occupiedCells());
        assertEquals(Map.of(), grid.perCraft());
        assertEquals(OptionalInt.of(0), grid.nextFreeCell());
        assertEquals(CraftGrid.<String>empty(), CraftGrid.of(), "a grid written with no keys is the empty grid");
    }

    @Test
    void aGridHasExactlyNineCells() {
        assertThrows(IllegalArgumentException.class, () -> new CraftGrid<>(List.of(Optional.of(PLANK))));
        List<Optional<String>> ten = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE + 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CraftGrid<>(ten));
        assertThrows(IllegalArgumentException.class,
                () -> CraftGrid.of(PLANK, PLANK, PLANK, PLANK, PLANK, PLANK, PLANK, PLANK, PLANK, PLANK));
        List<Optional<String>> withNull = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, Optional.empty()));
        withNull.set(4, null);
        assertThrows(NullPointerException.class, () -> new CraftGrid<>(withNull));
    }

    /** A grid is a value: editing a cell gives a new grid and leaves the old one, and equal cells are equal grids. */
    @Test
    void editingACellGivesANewGrid() {
        CraftGrid<String> before = CraftGrid.empty();
        CraftGrid<String> after = before.with(4, PLANK);
        assertTrue(before.isEmpty(), "the old grid is unchanged");
        assertEquals(Optional.of(PLANK), after.cell(4));
        assertEquals(1, after.occupiedCells());
        assertEquals(CraftGrid.of(null, null, null, null, PLANK), after);
        assertEquals(after.hashCode(), CraftGrid.of(null, null, null, null, PLANK).hashCode());
        assertEquals(before, after.without(4));
        assertEquals(before, after.withCell(4, Optional.empty()), "an empty hand clears the cell");
        assertNotEquals(after, after.with(4, STICK));
        assertThrows(IndexOutOfBoundsException.class, () -> before.with(CraftGrid.SIZE, PLANK));
        assertThrows(IndexOutOfBoundsException.class, () -> before.cell(-1));
    }

    /** The cell list cannot be edited behind the grid's back. */
    @Test
    void theCellsAreACopy() {
        List<Optional<String>> cells = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, Optional.empty()));
        CraftGrid<String> grid = new CraftGrid<>(cells);
        cells.set(0, Optional.of(PLANK));
        assertTrue(grid.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> grid.cells().set(0, Optional.of(PLANK)));
    }

    /** Shift + right-click on a stock cell writes into the next free cell, row by row. */
    @Test
    void theNextFreeCellIsRowMajor() {
        assertEquals(OptionalInt.of(4), chest().nextFreeCell(), "the chest's empty centre");
        assertEquals(OptionalInt.of(1), CraftGrid.of(PLANK, null, PLANK).nextFreeCell());
        assertEquals(OptionalInt.empty(), piston().nextFreeCell(), "a full grid has no free cell");
    }

    @Test
    void theFirstCellOfAKey() {
        assertEquals(OptionalInt.of(3), piston().firstCell(COBBLE));
        assertEquals(OptionalInt.of(7), piston().firstCell(REDSTONE));
        assertEquals(OptionalInt.of(0), chest().firstCell(PLANK));
        assertEquals(OptionalInt.empty(), chest().firstCell(STICK));
    }

    // --- the multiset ------------------------------------------------------------------------------------------------

    /** Cells naming the same key are one ingredient, and the keys keep the order the grid introduces them in. */
    @Test
    void cellsMergeInFirstCellOrder() {
        Map<String, Integer> perCraft = piston().perCraft();
        assertEquals(List.of(PLANK, COBBLE, IRON, REDSTONE), List.copyOf(perCraft.keySet()));
        assertEquals(List.of(3, 4, 1, 1), List.copyOf(perCraft.values()));
        assertEquals(Map.of(PLANK, 8), chest().perCraft());
        assertEquals(9, piston().occupiedCells());
        assertThrows(UnsupportedOperationException.class, () -> piston().perCraft().put(STICK, 1));
    }

    /** The order is the first appearance, not the most frequent key and not the alphabet. */
    @Test
    void aLaterRepeatDoesNotMoveAKeyForward() {
        CraftGrid<String> grid = CraftGrid.of(STICK, PLANK, PLANK, null, PLANK, null, COBBLE, STICK);
        assertEquals(List.of(STICK, PLANK, COBBLE), List.copyOf(grid.perCraft().keySet()));
        assertEquals(List.of(2, 3, 1), List.copyOf(grid.perCraft().values()));
    }

    /**
     * Cloning a map takes a filled map and gives two back. A production pattern refuses a result among its ingredients;
     * a crafting grid knows no result and has no such rule, so the grid works like any other.
     */
    @Test
    void aKeyTheRecipeGivesBackIsAnOrdinaryIngredient() {
        CraftGrid<String> cloning = CraftGrid.of(FILLED_MAP, MAP);
        assertEquals(Map.of(FILLED_MAP, 1, MAP, 1), cloning.perCraft());
        assertEquals(3, cloning.craftsFrom(Map.of(FILLED_MAP, 3L, MAP, 5L)));
        assertEquals(Map.of(MAP, 1), cloning.toFetch(2, Map.of(FILLED_MAP, 2L, MAP, 1L)));
    }

    // --- crafts from counts ------------------------------------------------------------------------------------------

    @Test
    void craftsFromIsTheScarcestKeyRoundedDown() {
        assertEquals(2, chest().craftsFrom(Map.of(PLANK, 23L)), "23 planks are two chests, not 2.875");
        assertEquals(3, chest().craftsFrom(Map.of(PLANK, 24L)));
        assertEquals(1, piston().craftsFrom(Map.of(PLANK, 30L, COBBLE, 7L, IRON, 9L, REDSTONE, 9L)),
                "seven cobblestone are one piston, however many planks there are");
        assertEquals(0, piston().craftsFrom(Map.of(PLANK, 30L, COBBLE, 40L, IRON, 9L)), "no redstone, no piston");
    }

    @Test
    void craftsFromIgnoresOtherKeysAndNegativeCounts() {
        assertEquals(1, chest().craftsFrom(Map.of(PLANK, 8L, STICK, 640L)));
        assertEquals(0, chest().craftsFrom(Map.of(PLANK, -16L)));
        assertEquals(0, chest().craftsFrom(Map.of()));
        assertEquals(0, CraftGrid.<String>empty().craftsFrom(Map.of(PLANK, 64L)), "an empty grid crafts nothing");
    }

    @Test
    void craftsFromSaturates() {
        assertEquals(Integer.MAX_VALUE, CraftGrid.of(STICK).craftsFrom(Map.of(STICK, Long.MAX_VALUE)));
    }

    @Test
    void theBufferCountsAreReadFromItsSlotsForTheGridsKeysOnly() {
        List<SlotView<String>> slots = List.of(SlotView.of(PLANK, 10, 99, 64), SlotView.empty(99),
                SlotView.of(STICK, 64, 99, 64), SlotView.of(PLANK, 64, 99, 64), SlotView.of(IRON, 3, 99, 64));
        Map<String, Long> counts = piston().countsIn(slots);
        assertEquals(List.of(PLANK, COBBLE, IRON, REDSTONE), List.copyOf(counts.keySet()), "in first-cell order");
        assertEquals(74L, counts.get(PLANK), "every slot holding the key counts");
        assertEquals(0L, counts.get(COBBLE), "a key no slot holds counts 0");
        assertEquals(3L, counts.get(IRON));
        assertFalse(counts.containsKey(STICK), "a key the grid does not name is not counted");
        assertEquals(0, piston().craftsFrom(counts), "no cobblestone, no piston");
        assertEquals(Map.of(), CraftGrid.<String>empty().countsIn(slots));
        assertThrows(UnsupportedOperationException.class, () -> counts.put(STICK, 1L));
    }

    // --- what to fetch -----------------------------------------------------------------------------------------------

    /** Three chests are 24 planks; ten already delivered leave 14 to fetch. */
    @Test
    void toFetchTopsUpTheBuffer() {
        assertEquals(Map.of(PLANK, 24), chest().toFetch(3, Map.of()));
        assertEquals(Map.of(PLANK, 14), chest().toFetch(3, Map.of(PLANK, 10L)));
    }

    /** A key the buffer already covers is not listed, and a buffer with more than enough never asks for less than 0. */
    @Test
    void toFetchNeverGoesBelowZero() {
        assertEquals(Map.of(), chest().toFetch(3, Map.of(PLANK, 24L)), "exactly enough: nothing to fetch");
        assertEquals(Map.of(), chest().toFetch(3, Map.of(PLANK, 500L)));
        Map<String, Integer> fetch = piston().toFetch(2, Map.of(COBBLE, 10L, IRON, 1L));
        assertEquals(List.of(PLANK, IRON, REDSTONE), List.copyOf(fetch.keySet()), "first-cell order, cobble covered");
        assertEquals(List.of(6, 1, 2), List.copyOf(fetch.values()));
    }

    @Test
    void toFetchForNoCraftsIsNothing() {
        assertEquals(Map.of(), chest().toFetch(0, Map.of()));
        assertEquals(Map.of(), chest().toFetch(-4, Map.of()));
        assertEquals(Map.of(), CraftGrid.<String>empty().toFetch(5, Map.of()));
        assertEquals(Map.of(PLANK, 16), chest().toFetch(2, Map.of(PLANK, -7L)), "a negative count counts as 0");
    }

    /**
     * The terminal's amount field goes up to 65 536 crafts and a key can fill all nine cells, so the largest real
     * demand is 9 × 65 536 = 589 824 items: well inside an {@code int}. Beyond that the arithmetic saturates instead of
     * wrapping around into a negative amount.
     */
    @Test
    void toFetchStaysInsideIntBounds() {
        CraftGrid<String> nine = CraftGrid.of(STICK, STICK, STICK, STICK, STICK, STICK, STICK, STICK, STICK);
        assertEquals(Map.of(STICK, 9 * 65_536), nine.toFetch(65_536, Map.of()));
        assertEquals(Map.of(STICK, 9 * 65_536 - 1), nine.toFetch(65_536, Map.of(STICK, 1L)));
        assertEquals(Map.of(STICK, Integer.MAX_VALUE), nine.toFetch(Integer.MAX_VALUE, Map.of()));
        assertEquals(Map.of(STICK, Integer.MAX_VALUE), nine.toFetch(Integer.MAX_VALUE, Map.of(STICK, 9L)),
                "still more than an int can hold after the top-up");
        assertFalse(nine.toFetch(Integer.MAX_VALUE, Map.of(STICK, Long.MAX_VALUE)).containsKey(STICK));
    }

    /** Fetching what {@code toFetch} names makes exactly {@code n} crafts possible: the two methods agree. */
    @Test
    void fetchingTheTopUpMakesTheCraftsPossible() {
        CraftGrid<String> grid = piston();
        Map<String, Long> buffer = new HashMap<>(Map.of(PLANK, 4L, COBBLE, 1L, REDSTONE, 7L));
        for (int crafts = 1; crafts <= 12; crafts++) {
            Map<String, Long> after = new HashMap<>(buffer);
            grid.toFetch(crafts, buffer).forEach((key, amount) -> after.merge(key, (long) amount, Long::sum));
            assertTrue(grid.craftsFrom(after) >= crafts, crafts + " crafts after the top-up");
        }
    }
}
