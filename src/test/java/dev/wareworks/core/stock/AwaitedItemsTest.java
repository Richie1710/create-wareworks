package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link AwaitedItems#owed}: when a filled container is still waited for as an item (ADR-089 amendment). */
class AwaitedItemsTest {
    /** Arguments: production results, promised, free, minimum, held, arriving, returning. */
    private static long owed(long production, long promised, long free, long minimum, long held, long arriving,
            long returning) {
        return AwaitedItems.owed(production, promised, free, minimum, held, arriving, returning);
    }

    @Test
    void nothingWaitsWithoutAWaiter() {
        assertEquals(0L, owed(0, 0, 0, 0, 0, 0, 0));
        assertEquals(0L, owed(0, 0, 12, 0, 12, 0, 0), "stock alone waits for nothing");
    }

    @Test
    void anOrderWaitsForNewItemsWhateverIsInStock() {
        assertEquals(2L, owed(2, 0, 10, 0, 10, 0, 0));
        assertEquals(1L, owed(2, 0, 0, 0, 0, 1, 0), "one is on its way");
        assertEquals(0L, owed(2, 0, 0, 0, 0, 2, 0));
        assertEquals(2L, owed(2, 0, 0, 0, 0, 0, 2), "a leftover being put back is no product");
    }

    @Test
    void aRequestTheShelvesCoverWaitsForNothing() {
        assertEquals(0L, owed(0, 3, 3, 0, 3, 0, 0));
        assertEquals(2L, owed(0, 3, 1, 0, 1, 0, 0));
        assertEquals(1L, owed(0, 3, 1, 0, 1, 1, 0));
        assertEquals(0L, owed(0, 3, 1, 0, 1, 0, 2));
        assertEquals(3L, owed(0, 1, -2, 0, 0, 0, 0), "more reserved than held");
    }

    @Test
    void aMinimumWaitsUntilItIsMet() {
        assertEquals(4L, owed(0, 0, 0, 4, 0, 0, 0));
        assertEquals(1L, owed(0, 0, 2, 4, 2, 1, 0));
        assertEquals(0L, owed(0, 0, 4, 4, 4, 0, 0));
        assertEquals(0L, owed(0, 0, 9, 4, 9, 0, 0));
    }

    @Test
    void theWaitersAreNotAddedUp() {
        // One request, backed by an order, under a minimum of one: one bucket serves them.
        assertEquals(1L, owed(1, 1, 0, 1, 0, 0, 0));
        assertEquals(0L, owed(1, 1, 0, 1, 0, 1, 0));
        assertEquals(5L, owed(2, 3, 0, 5, 0, 0, 0), "the largest of them");
    }
}
