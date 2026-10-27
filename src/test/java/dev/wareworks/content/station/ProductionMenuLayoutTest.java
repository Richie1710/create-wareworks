package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The production station's window has to fit vanilla's smallest scaled screen at <b>every</b> configuration the server
 * config allows (buffer 1–27 slots, 1–36 pattern slots), and the safety stop's row has to have somewhere to go in every
 * one of them (M20 review fix).
 * <p>
 * The row is the screen's own way back from the stop, so a configuration without it would leave the screen naming a
 * problem and offering no way out of it — which is exactly what a buffer of 25 slots or more did, because the height
 * budget had spent every order line on buffer rows.
 */
class ProductionMenuLayoutTest {
    /** The whole range {@code productionBufferSlots} and {@code maxProductionPatterns} allow. */
    @Test
    void everyConfigurationFitsTheSmallestScreen() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                assertTrue(layout.height() <= ProductionMenuLayout.MAX_HEIGHT,
                        "buffer " + buffer + ", patterns " + patterns + " needs " + layout.height() + " pixels");
            }
        }
    }

    /** A large buffer really does leave no order line: the configuration the stopped row had to be given a home in. */
    @Test
    void theLargestBuffersLeaveNoOrderLine() {
        assertEquals(0, new ProductionMenuLayout(25, 1).orderLines(), "25 slots are three buffer rows");
        assertEquals(0, new ProductionMenuLayout(27, 8).orderLines());
        assertTrue(new ProductionMenuLayout(24, 8).orderLines() > 0, "two buffer rows still leave a line");
    }

    /** Wherever it goes, the stopped row is a real row of the window and never lands on a slot. */
    @Test
    void theStoppedRowAlwaysHasARow() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                int row = layout.stoppedRowY();
                String where = "buffer " + buffer + ", patterns " + patterns;
                assertEquals(layout.orderLines() > 0 ? layout.orderLineY(0) : layout.bufferLabelY(), row,
                        where + ": the row takes the buffer's label only when there is no order line");
                assertTrue(row >= layout.bufferLabelY(), where + ": never above the buffer's label");
                assertTrue(row + ProductionMenuLayout.LABEL_HEIGHT <= layout.playerLabelY()
                        || layout.orderLines() == 0, where + ": never into the player inventory's label");
                assertTrue(row + ProductionMenuLayout.LABEL_HEIGHT <= layout.bufferY() || layout.orderLines() > 0,
                        where + ": and it stays clear of the buffer slots when it takes their label");
            }
        }
    }

    // --- the pattern tab strip (issue #35, ADR-068) --------------------------------------------------------------

    private static final int SLOT = ProductionMenuLayout.SLOT;

    private static boolean overlaps(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    /** The column rule, the block width and where the block starts, literally as ADR-068 states them. */
    @Test
    void theTabStripWidensThenScrolls() {
        int[][] table = {
                // patterns, columns, rows, visible rows, max scroll, patternsX
                {1, 3, 1, 1, 0, 39}, {4, 3, 2, 2, 0, 39}, {8, 3, 3, 3, 0, 39}, {9, 3, 3, 3, 0, 39},
                {10, 4, 3, 3, 0, 30}, {12, 4, 3, 3, 0, 30}, {13, 5, 3, 3, 0, 21}, {15, 5, 3, 3, 0, 21},
                {16, 6, 3, 3, 0, 12}, {18, 6, 3, 3, 0, 12}, {19, 6, 4, 3, 1, 9}, {24, 6, 4, 3, 1, 9},
                {25, 6, 5, 3, 2, 9}, {36, 6, 6, 3, 3, 9}};
        for (int[] row : table) {
            ProductionMenuLayout layout = new ProductionMenuLayout(9, row[0]);
            String where = row[0] + " patterns";
            assertEquals(row[1], layout.tabColumns(), where + ": columns");
            assertEquals(row[2], layout.tabRows(), where + ": rows");
            assertEquals(row[3], layout.visibleTabRows(), where + ": visible rows");
            assertEquals(row[4], layout.maxTabScroll(), where + ": max scroll");
            assertEquals(row[5], layout.patternsX(), where + ": left edge of the pattern block");
            assertEquals(row[0] >= 19, layout.scrolls(), where + ": scrolls only above 18");
        }
    }

    /**
     * Up to 9 patterns the window is pixel for pixel the one every station had while the cap was 8: the feeder and arm
     * visual runs click these positions.
     */
    @Test
    void upToNinePatternsNothingMoves() {
        for (int patterns = 1; patterns <= 9; patterns++) {
            ProductionMenuLayout layout = new ProductionMenuLayout(9, patterns);
            assertEquals(39, layout.patternsX());
            assertEquals(39 + 54 + 14 + 18 + 12, layout.tabsX());
            for (int p = 0; p < patterns; p++) {
                assertEquals(layout.tabsX() + p % 3 * SLOT, layout.tabX(p));
                assertEquals(layout.patternsY() + p / 3 * SLOT, layout.tabY(p, 0));
                assertTrue(layout.tabVisible(p, 0));
            }
        }
    }

    /** Every count, every buffer and every scroll value: nothing leaves the window and no tab covers anything. */
    @Test
    void everyTabFitsAtEveryScroll() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                String where = "buffer " + buffer + ", patterns " + patterns;
                assertEquals(230, ProductionMenuLayout.WIDTH);
                assertTrue(layout.height() <= ProductionMenuLayout.MAX_HEIGHT, where);
                assertTrue(layout.patternsX() >= ProductionMenuLayout.MARGIN, where + ": left margin");
                int right = layout.patternsX() + layout.patternBlockWidth();
                assertTrue(right <= ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN, where + ": right margin");
                assertEquals(right, layout.tabStripX() + layout.tabStripWidth(), where + ": the strip ends the block");
                assertTrue(layout.tabStripY() + layout.tabStripHeight() <= layout.bufferLabelY(),
                        where + ": the strip stays above the buffer");
                if (layout.scrolls())
                    assertTrue(layout.scrollbarX() + ProductionMenuLayout.SCROLLBAR_WIDTH <= right, where);
                int arrowY = layout.patternCellY(ProductionMenuLayout.RESULT_CELL);
                for (int scroll = 0; scroll <= layout.maxTabScroll(); scroll++) {
                    int visible = 0;
                    for (int p = 0; p < patterns; p++) {
                        if (!layout.tabVisible(p, scroll))
                            continue;
                        visible++;
                        int x = layout.tabX(p);
                        int y = layout.tabY(p, scroll);
                        String tab = where + ", scroll " + scroll + ", tab " + p;
                        assertTrue(x >= layout.tabStripX() && x + SLOT <= layout.tabStripX() + layout.tabStripWidth(),
                                tab + ": inside the strip horizontally");
                        assertTrue(y >= layout.patternsY() && y + SLOT <= layout.patternsY() + 3 * SLOT,
                                tab + ": inside the pattern block's height");
                        for (int cell = 0; cell < ProductionMenuLayout.PATTERN_CELLS; cell++)
                            assertFalse(overlaps(x, y, SLOT, SLOT, layout.patternCellX(cell), layout.patternCellY(cell),
                                    SLOT, SLOT), tab + ": covers cell " + cell);
                        assertFalse(overlaps(x, y, SLOT, SLOT, layout.arrowX(), arrowY, ProductionMenuLayout.ARROW_WIDTH,
                                SLOT), tab + ": covers the arrow");
                        if (layout.scrolls())
                            assertTrue(x + SLOT <= layout.scrollbarX(), tab + ": clear of the scroll bar");
                        for (int q = p + 1; q < patterns; q++) {
                            if (layout.tabVisible(q, scroll))
                                assertFalse(overlaps(x, y, SLOT, SLOT, layout.tabX(q), layout.tabY(q, scroll), SLOT,
                                        SLOT), tab + ": overlaps tab " + q);
                        }
                    }
                    assertTrue(visible <= ProductionMenuLayout.MAX_TAB_COLUMNS * ProductionMenuLayout.VISIBLE_TAB_ROWS);
                    int thumb = layout.thumbY(scroll);
                    assertTrue(thumb >= layout.scrollbarY()
                            && thumb + layout.thumbHeight() <= layout.scrollbarY() + layout.scrollbarHeight(),
                            where + ", scroll " + scroll + ": thumb inside the track");
                }
                if (layout.scrolls()) {
                    assertEquals(layout.scrollbarY(), layout.thumbY(0), where + ": thumb at the top");
                    assertEquals(layout.scrollbarY() + layout.scrollbarHeight(),
                            layout.thumbY(layout.maxTabScroll()) + layout.thumbHeight(), where + ": thumb at the bottom");
                }
            }
        }
    }

    /**
     * The scroll bar's per-row ticks (a hidden red row, the hidden selected row) stay inside its track at every count
     * that scrolls, are at least a pixel high, and go down the track in row order.
     */
    @Test
    void everyRowTickStaysInsideTheTrack() {
        for (int patterns = 19; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
            ProductionMenuLayout layout = new ProductionMenuLayout(9, patterns);
            assertTrue(layout.scrolls(), patterns + " patterns scroll");
            assertTrue(layout.scrollbarRowHeight() >= 1, patterns + " patterns: a tick is at least a pixel high");
            int previous = Integer.MIN_VALUE;
            for (int row = 0; row < layout.tabRows(); row++) {
                String where = patterns + " patterns, row " + row;
                int y = layout.scrollbarRowY(row);
                assertTrue(y >= layout.scrollbarY(), where + ": below the track's top");
                assertTrue(y + layout.scrollbarRowHeight() <= layout.scrollbarY() + layout.scrollbarHeight(),
                        where + ": above the track's bottom");
                assertTrue(y > previous, where + ": below the row before it");
                previous = y;
            }
        }
    }

    /** Every tab can be reached, and scrolling a tab into view moves the strip as little as it can. */
    @Test
    void scrollingToATabShowsIt() {
        for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
            ProductionMenuLayout layout = new ProductionMenuLayout(9, patterns);
            for (int p = 0; p < patterns; p++) {
                for (int scroll = 0; scroll <= layout.maxTabScroll(); scroll++) {
                    int shown = layout.scrollToShow(p, scroll);
                    String where = patterns + " patterns, tab " + p + " from scroll " + scroll;
                    assertTrue(layout.tabVisible(p, shown), where);
                    if (layout.tabVisible(p, scroll))
                        assertEquals(scroll, shown, where + ": an already visible tab does not move the strip");
                    else if (shown < scroll)
                        assertEquals(layout.tabRow(p), shown, where + ": its row becomes the top row");
                    else
                        assertEquals(layout.tabRow(p) - layout.visibleTabRows() + 1, shown,
                                where + ": its row becomes the bottom row");
                }
            }
        }
        ProductionMenuLayout full = new ProductionMenuLayout(9, 36);
        assertEquals(3, full.scrollToShow(35, 0));
        assertEquals(1, full.scrollToShow(18, 0));
        assertEquals(0, full.scrollToShow(0, 3));
        assertEquals(2, full.scrollToShow(12, 3));
        assertEquals(0, full.clampTabScroll(-5));
        assertEquals(3, full.clampTabScroll(99));
        assertFalse(full.tabVisible(0, 3));
        assertFalse(full.tabVisible(36, 0), "a slot the station does not have is never a tab");
        assertEquals(6, ProductionMenuLayout.tabColumnsFor(99), "never wider than six columns");
    }
}
