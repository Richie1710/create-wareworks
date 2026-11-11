package dev.wareworks.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Review fix (tests 7): two queued orders at one Recipe Selector station must not read the same in the terminal. The
 * widths are the terminal's own: a 202 px row, a 9 px cancel column, a 4 px gap and the 54 px item floor; "Deepslate
 * Tile Slab x16" is about 118 px wide and a wait about 230 px.
 */
class OrderRowWidthsTest {
    private static final int ROW = 202;
    private static final int CANCEL = 9;
    private static final int GAP = 4;
    private static final int FLOOR = 54;
    private static final int ITEM = 118;

    @Test
    void aSelectorWaitLeavesTheItemsWholeName() {
        int state = OrderRowWidths.stateRoom(ROW, CANCEL, 0, ITEM, FLOOR, GAP, true);
        assertTrue(ROW - CANCEL - GAP - state >= ITEM, "the item's whole name fits beside the wait: state " + state);
        assertTrue(state >= OrderRowWidths.MIN_STATE_WIDTH, "and the wait keeps its own minimum");
    }

    @Test
    void anOrdinaryStateStillWinsTheRowDownToTheItemsFloor() {
        assertEquals(ROW - CANCEL - GAP - FLOOR, OrderRowWidths.stateRoom(ROW, CANCEL, 0, ITEM, FLOOR, GAP, false));
        assertEquals(ROW - CANCEL - GAP - 20, OrderRowWidths.stateRoom(ROW, CANCEL, 0, 20, FLOOR, GAP, false),
                "a short item keeps only its own width");
    }

    @Test
    void aVeryLongNameStillLeavesTheWaitItsMinimum() {
        assertEquals(OrderRowWidths.MIN_STATE_WIDTH, OrderRowWidths.stateRoom(ROW, CANCEL, 0, 400, FLOOR, GAP, true));
        assertEquals(20 - CANCEL - GAP, OrderRowWidths.stateRoom(20, CANCEL, 0, 400, FLOOR, GAP, true),
                "a row too narrow for both gives the wait what there is");
        assertEquals(0, OrderRowWidths.stateRoom(5, CANCEL, 0, 400, FLOOR, GAP, true), "never negative");
    }
}
