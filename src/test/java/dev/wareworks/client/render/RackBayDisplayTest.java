package dev.wareworks.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Issue #49: what a rack bay shows — the stock first, else its filter, else nothing. */
class RackBayDisplayTest {
    @Test
    void aBayWithStockShowsTheStockWhateverItsFilter() {
        assertEquals(RackBayDisplay.STORED, RackBayDisplay.choose(true, true));
        assertEquals(RackBayDisplay.STORED, RackBayDisplay.choose(true, false));
    }

    @Test
    void anEmptyBayWithAFilterShowsTheFilter() {
        assertEquals(RackBayDisplay.FILTER, RackBayDisplay.choose(false, true));
    }

    @Test
    void anEmptyBayWithoutAFilterShowsNothing() {
        assertEquals(RackBayDisplay.NOTHING, RackBayDisplay.choose(false, false));
    }
}
