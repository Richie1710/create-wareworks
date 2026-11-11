package dev.wareworks.client.gui;

/**
 * How an order row of the terminal splits its width between the item ("Deepslate Tile Slab x16") and the state
 * ("Waits for its turn: Deepslate Tile Stairs first"). Pure arithmetic in GUI pixels, so a JUnit test can pin it.
 * <p>
 * Normally the state wins the row down to the item's floor, because how far an order has got is what changes. A Recipe
 * Selector wait is the exception (ADR-085, review fix tests 7): every queued order at one station says the same
 * thing, so with the state first two different orders read the same ("Deepslat... Waits for its turn: Deepslate...").
 * Then the item's whole name comes first and the wait gives way down to {@link #MIN_STATE_WIDTH}; the row's tooltip
 * holds the full wait and the item it waits for.
 */
public final class OrderRowWidths {
    /** Smallest width a Recipe Selector wait keeps beside a long item name: room for "Waits..." at least. */
    public static final int MIN_STATE_WIDTH = 30;

    private OrderRowWidths() {
    }

    /**
     * The width the state may take.
     *
     * @param row          the row's whole width
     * @param reserved     what the cancel mark takes on the right (0 for a finished order)
     * @param badge        what a chain's step badge takes, gap included (0 without one)
     * @param item         the item text's full width
     * @param minItem      the item's floor while the state wins the row
     * @param gap          the gap between item and state
     * @param itemFirst    whether the item's name comes first (a Recipe Selector wait)
     */
    public static int stateRoom(int row, int reserved, int badge, int item, int minItem, int gap, boolean itemFirst) {
        int free = Math.max(0, row - reserved - badge - gap);
        int floor = itemFirst ? Math.min(item, Math.max(0, free - MIN_STATE_WIDTH)) : Math.min(item, minItem);
        return Math.max(0, free - floor);
    }
}
