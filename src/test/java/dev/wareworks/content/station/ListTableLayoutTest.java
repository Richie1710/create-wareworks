package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The clipboard list question's table (issue #36, ADR-069) has to show at least {@value ListTableLayout#MIN_VISIBLE_ROWS}
 * whole rows in the smallest terminal window every buffer size the server config allows (1–27 slots), keep its panel
 * inside that window with the buttons at the bottom, give the name column what the other columns leave and scroll by
 * whole rows, one per wheel notch. The English and German column widths are measured with the real font in
 * {@code data.TerminalListTableTextWidthTest}; this test holds the arithmetic to representative numbers.
 */
class ListTableLayoutTest {
    /** The largest buffer {@code terminalBufferSlots} allows. */
    private static final int MAX_BUFFER = 27;
    /** "Wanted", "65,536", "Not possible", "Status" in the default font. */
    private static final int EN_WANTED_HEADER = 34;
    private static final int EN_WIDEST_WANTED = 34;
    private static final int EN_WIDEST_STATUS = 61;
    private static final int EN_STATUS_HEADER = 34;
    /** "Menge", "65.536", "Nicht möglich", "Status". */
    private static final int DE_WANTED_HEADER = 29;
    private static final int DE_WIDEST_WANTED = 34;
    private static final int DE_WIDEST_STATUS = 64;
    private static final int DE_STATUS_HEADER = 34;
    private static final int MANY_ROWS = 1024 + 1 + 3 * 1024;

    private static ListTableLayout english(int layoutHeight, int textRows, int rows) {
        return ListTableLayout.of(EN_WANTED_HEADER, EN_WIDEST_WANTED, EN_WIDEST_STATUS, EN_STATUS_HEADER, layoutHeight,
                textRows, rows);
    }

    @Test
    void thePanelIsTheWindowsContentWidth() {
        assertEquals(216, ListTableLayout.PANEL_WIDTH);
        assertEquals(204, ListTableLayout.INNER_WIDTH);
        assertEquals(198, ListTableLayout.CONTENT_WIDTH);
    }

    @Test
    void theColumnsFillTheContentWidthExactly() {
        for (ListTableLayout table : new ListTableLayout[] { english(234, 2, 10),
                ListTableLayout.of(DE_WANTED_HEADER, DE_WIDEST_WANTED, DE_WIDEST_STATUS, DE_STATUS_HEADER, 234, 2,
                        10) }) {
            assertEquals(ListTableLayout.ICON + ListTableLayout.ICON_GAP, table.nameX());
            assertEquals(ListTableLayout.CONTENT_WIDTH, table.nameX() + table.nameWidth() + ListTableLayout.COLUMN_GAP
                    + table.wantedWidth() + ListTableLayout.COLUMN_GAP + table.statusWidth());
            assertEquals(ListTableLayout.CONTENT_WIDTH, table.statusX() + table.statusWidth());
            assertEquals(table.statusX() + table.statusWidth() - table.nameX(), table.detailWidth());
            assertTrue(table.nameWidth() >= ListTableLayout.MIN_NAME_WIDTH, "name " + table.nameWidth());
            assertEquals(ListTableLayout.CONTENT_WIDTH + ListTableLayout.SCROLLBAR_GAP, table.scrollbarX());
            assertTrue(table.scrollbarX() + ListTableLayout.SCROLLBAR_WIDTH <= ListTableLayout.INNER_WIDTH);
        }
        assertEquals(EN_WIDEST_STATUS, english(234, 2, 10).statusWidth(), "the status words are not shortened");
    }

    @Test
    void theStatusWordsGiveWayToTheNameNeverTheOtherWayRound() {
        ListTableLayout table = ListTableLayout.of(40, 40, 120, 30, 234, 2, 10);
        assertEquals(ListTableLayout.MIN_NAME_WIDTH, table.nameWidth());
        assertTrue(table.statusWidth() < 120, "the status column was shortened to " + table.statusWidth());
    }

    @Test
    void aVeryWideAmountIsCappedAndShortened() {
        ListTableLayout table = ListTableLayout.of(34, 120, 61, 34, 234, 2, 10);
        assertEquals(ListTableLayout.MAX_WANTED_WIDTH, table.wantedWidth());
    }

    /** The smallest window every buffer size allows still shows three whole rows, with up to four lines above them. */
    @Test
    void theSmallestWindowShowsAtLeastThreeRowsForEveryBuffer() {
        for (int buffer = 1; buffer <= MAX_BUFFER; buffer++) {
            TerminalMenuLayout window = new TerminalMenuLayout(buffer);
            for (int textRows = 1; textRows <= 4; textRows++) {
                ListTableLayout table = english(window.height(), textRows, MANY_ROWS);
                String where = "buffer " + buffer + ", " + textRows + " text rows, window " + window.height();
                assertTrue(table.visibleRows() >= ListTableLayout.MIN_VISIBLE_ROWS,
                        where + ": only " + table.visibleRows() + " rows");
                assertEquals(ListTableLayout.fixedHeight(textRows) + table.visibleRows() * ListTableLayout.ROW_HEIGHT,
                        table.height(), where);
                assertTrue(table.height() <= window.height() - 2 * TerminalMenuLayout.MARGIN, where + ": too tall");
                assertTrue(table.offsetY() >= TerminalMenuLayout.MARGIN, where);
                assertTrue(table.offsetY() + table.height() <= window.height() - TerminalMenuLayout.MARGIN, where);
                // The buttons sit at the bottom, under the rows and the gap below them.
                assertEquals(table.rowsY() + table.rowsHeight() + ListTableLayout.GAP_BELOW_TABLE, table.buttonY(),
                        where);
                assertEquals(table.height() - ListTableLayout.PADDING - ListTableLayout.BUTTON_HEIGHT,
                        table.buttonY(), where);
            }
        }
    }

    @Test
    void theRowsSitUnderTheTextAndTheHeader() {
        ListTableLayout table = english(234, 3, 20);
        assertEquals(ListTableLayout.PADDING + 3 * ListTableLayout.TEXT_ROW + ListTableLayout.GAP_ABOVE_TABLE,
                table.headerY());
        assertEquals(table.headerY() + ListTableLayout.HEADER_HEIGHT, table.rowsY());
        assertEquals(table.visibleRows() * ListTableLayout.ROW_HEIGHT, table.rowsHeight());
    }

    @Test
    void aShortListNeitherScrollsNorGrowsThePanel() {
        ListTableLayout table = english(234, 2, 2);
        assertEquals(2, table.visibleRows());
        assertEquals(0, table.maxScroll());
        assertFalse(table.scrolls());
        assertEquals(0, table.scrollBy(0, -1.0));
        assertEquals(ListTableLayout.fixedHeight(2) + 2 * ListTableLayout.ROW_HEIGHT, table.height());
    }

    @Test
    void theWheelMovesOneRowPerNotchAndStopsAtBothEnds() {
        ListTableLayout table = english(234, 2, 30);
        int max = table.maxScroll();
        assertEquals(30 - table.visibleRows(), max);
        assertEquals(1, table.scrollBy(0, -1.0), "the wheel down shows later rows");
        assertEquals(0, table.scrollBy(1, 1.0), "the wheel up earlier ones");
        assertEquals(0, table.scrollBy(0, 1.0), "not above the first row");
        assertEquals(max, table.scrollBy(max, -1.0), "not past the last");
        assertEquals(1, table.scrollBy(0, -3.0), "one row per event, whatever the delta");
        assertEquals(0, table.clampScroll(-5));
        assertEquals(max, table.clampScroll(max + 5));
    }

    @Test
    void aPointIsMappedToTheRowUnderIt() {
        ListTableLayout table = english(234, 2, 30);
        int top = table.rowsY();
        assertEquals(-1, table.rowAt(0, top - 1), "the header is no row");
        assertEquals(0, table.rowAt(0, top));
        assertEquals(1, table.rowAt(0, top + ListTableLayout.ROW_HEIGHT));
        assertEquals(5, table.rowAt(4, top + ListTableLayout.ROW_HEIGHT + 3), "scrolled by four");
        assertEquals(-1, table.rowAt(0, top + table.rowsHeight()), "below the rows");
        ListTableLayout shortList = english(234, 2, 1);
        assertEquals(0, shortList.rowAt(0, shortList.rowsY() + 1));
        assertEquals(-1, shortList.rowAt(0, shortList.rowsY() + ListTableLayout.ROW_HEIGHT));
    }

    @Test
    void theThumbRunsTheWholeTrack() {
        ListTableLayout table = english(234, 2, 200);
        assertTrue(table.scrolls());
        assertTrue(table.thumbHeight() >= ListTableLayout.MIN_THUMB);
        assertEquals(0, table.thumbY(0));
        assertEquals(table.rowsHeight() - table.thumbHeight(), table.thumbY(table.maxScroll()));
        int middle = table.thumbY(table.maxScroll() / 2);
        assertTrue(middle > 0 && middle < table.rowsHeight() - table.thumbHeight(), "middle " + middle);
        ListTableLayout fewRows = english(234, 2, table.visibleRows() + 1);
        assertTrue(fewRows.thumbHeight() > table.thumbHeight(), "fewer rows, longer thumb");
    }
}
