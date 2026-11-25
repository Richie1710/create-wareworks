package dev.wareworks.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Issues #49 and #61: what a rack bay and a fluid bay show — the stock first, else its filter, else nothing. */
class BayDisplayTest {
    @Test
    void aBayWithStockShowsTheStockWhateverItsFilter() {
        assertEquals(BayDisplay.STORED, BayDisplay.choose(true, true));
        assertEquals(BayDisplay.STORED, BayDisplay.choose(true, false));
    }

    @Test
    void anEmptyBayWithAFilterShowsTheFilter() {
        assertEquals(BayDisplay.FILTER, BayDisplay.choose(false, true));
    }

    @Test
    void anEmptyBayWithoutAFilterShowsNothing() {
        assertEquals(BayDisplay.NOTHING, BayDisplay.choose(false, false));
    }
}
