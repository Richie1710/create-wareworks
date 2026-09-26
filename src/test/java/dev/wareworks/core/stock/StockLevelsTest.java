package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The four numbers a stock rule is judged against (M15, issue #3): they are read from four different counters of the
 * warehouse, so the record has to survive whatever a raw subtraction hands it.
 */
class StockLevelsTest {
    @Test
    void negativeInputsReadAsNothing() {
        StockLevels levels = new StockLevels(-5L, -1L, -100L, -7L);
        assertEquals(0L, levels.stocked());
        assertEquals(0L, levels.inbound());
        assertEquals(0L, levels.expected());
        assertEquals(0L, levels.available());
        assertEquals(StockLevels.NONE, levels, "an item the warehouse has never seen");
    }

    @Test
    void thePipelineIsWhatTheWarehouseHasOrHasSentFor() {
        StockLevels levels = new StockLevels(100L, 30L, 12L, 80L);
        assertEquals(142L, levels.pipeline());
        assertEquals(0L, StockLevels.NONE.pipeline());
    }

    @Test
    void storedItemsAreAvailableAndNothingIsOnItsWay() {
        StockLevels levels = StockLevels.stored(64L);
        assertEquals(64L, levels.stocked());
        assertEquals(64L, levels.available());
        assertEquals(0L, levels.inbound());
        assertEquals(0L, levels.expected());
        assertEquals(64L, levels.pipeline());
    }

    /** Counters are longs read from the world; a sum must saturate rather than wrap into a negative pipeline. */
    @Test
    void thePipelineSaturatesInsteadOfOverflowing() {
        StockLevels levels = new StockLevels(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0L);
        assertEquals(Long.MAX_VALUE, levels.pipeline());
    }

    @Test
    void saturatingArithmeticIsExactInTheNormalRange() {
        assertEquals(7L, StockLevels.sum(3L, 4L));
        assertEquals(Long.MAX_VALUE, StockLevels.sum(Long.MAX_VALUE, 1L));
        assertEquals(-3L, StockLevels.difference(4L, 7L));
        assertEquals(Long.MAX_VALUE, StockLevels.difference(Long.MAX_VALUE, -1L));
        assertEquals(Long.MIN_VALUE, StockLevels.difference(Long.MIN_VALUE, 1L));
    }
}
