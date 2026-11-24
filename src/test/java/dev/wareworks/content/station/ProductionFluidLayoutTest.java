package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Where the two fluid cells of a pattern sit in the production station's window (issue #55, ADR-089): in the column of
 * the result cell, above and below it, so they cost no height at any configuration and overlap nothing.
 */
class ProductionFluidLayoutTest {
    private static final int SLOT = ProductionMenuLayout.SLOT;

    private static boolean overlap(int ax, int ay, int bx, int by) {
        return ax < bx + SLOT && bx < ax + SLOT && ay < by + SLOT && by < ay + SLOT;
    }

    /** Every configuration: the cells are inside the pattern block and touch no grid cell, result, tab or slot. */
    @Test
    void fluidCellsOverlapNothingAtAnyConfiguration() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                String where = "buffer " + buffer + ", patterns " + patterns;
                for (int cell = 0; cell < ProductionPatterns.FLUID_CELLS; cell++) {
                    int x = layout.fluidCellX(cell);
                    int y = layout.fluidCellY(cell);
                    assertTrue(y >= layout.patternsY() && y + SLOT <= layout.patternsY() + 3 * SLOT,
                            where + ": fluid cell " + cell + " inside the pattern block's height");
                    assertTrue(x >= layout.patternsX() && x + SLOT <= layout.patternsX() + layout.patternBlockWidth(),
                            where + ": and its width");
                    for (int other = 0; other < ProductionMenuLayout.PATTERN_CELLS; other++)
                        assertFalse(overlap(x, y, layout.patternCellX(other), layout.patternCellY(other)),
                                where + ": fluid cell " + cell + " overlaps pattern cell " + other);
                    for (int tab = 0; tab < patterns; tab++) {
                        if (layout.tabVisible(tab, 0))
                            assertFalse(overlap(x, y, layout.tabX(tab), layout.tabY(tab, 0)),
                                    where + ": fluid cell " + cell + " overlaps tab " + tab);
                    }
                    assertFalse(y + SLOT > layout.bufferLabelY(), where + ": clear of the buffer's label");
                }
                assertFalse(overlap(layout.fluidCellX(0), layout.fluidCellY(0), layout.fluidCellX(1),
                        layout.fluidCellY(1)), where + ": the two fluid cells overlap");
                assertTrue(layout.height() <= ProductionMenuLayout.MAX_HEIGHT, where + ": the window did not grow");
            }
        }
    }

    /** The cells sit above and below the result cell, so a fluid cell adds no row to the window. */
    @Test
    void theCellsFlankTheResultCell() {
        ProductionMenuLayout layout = new ProductionMenuLayout(9, 8);
        int resultX = layout.patternCellX(ProductionMenuLayout.RESULT_CELL);
        int resultY = layout.patternCellY(ProductionMenuLayout.RESULT_CELL);
        assertEquals(resultX, layout.fluidCellX(0));
        assertEquals(resultX, layout.fluidCellX(1));
        assertEquals(resultY - SLOT, layout.fluidCellY(0), "the first one directly above");
        assertEquals(resultY + SLOT, layout.fluidCellY(1), "the second one directly below");
        assertEquals(layout.patternsY(), layout.fluidCellY(0), "flush with the grid's first row");
        // The window is exactly as tall as it was without fluid cells: the height formula names no fluid row.
        assertEquals(layout.playerSlotsY() + ProductionMenuLayout.PLAYER_SLOTS_HEIGHT
                + ProductionMenuLayout.BOTTOM_MARGIN, layout.height());
    }
}
