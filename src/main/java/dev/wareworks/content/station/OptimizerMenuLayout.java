package dev.wareworks.content.station;

import dev.wareworks.core.optimizer.OptimizerLimits;

/**
 * Where everything sits in a Warehouse Optimizer's window (issue #46, ADR-079): the stock keeper's window
 * ({@link StockKeeperMenuLayout}) with three read-only numbers in place of the keeper's three settings and a taller
 * status row.
 * <p>
 * Like the keeper's layout this is shared by both sides, because {@code Slot#x} and {@code Slot#y} are final. Unlike it
 * nothing has to travel to build it: an optimizer always has {@value OptimizerRows#MAX_ROWS} rows.
 * <p>
 * <b>One line per item.</b> A row is an item cell, the item's name, the three numbers — Best, Below and Movable — and a
 * status pip, all on {@value #ROW_HEIGHT} pixels.
 * <p>
 * <b>The status row</b> is a whole slot tall, because it carries two things a player clicks: the <b>list cell</b> at
 * its left end, which takes a clipboard's items when a filled-in clipboard is clicked onto it, and the <b>Optimise</b>
 * button at its right end. The status text stands between them, and while an order runs its cancel mark takes the
 * text's right end.
 * <p>
 * <b>Size budget.</b> Vanilla clamps the GUI scale so that the scaled screen is at least 320 × {@value #MAX_HEIGHT}
 * pixels, so the window must fit that at every configuration. With the title, the column header, the status row and the
 * player inventory fixed, {@link #VISIBLE_ROWS} rows fit; the rest is reached by scrolling.
 */
public final class OptimizerMenuLayout {
    /** Edge length of an item cell, the list cell and the Optimise button. */
    public static final int SLOT = 18;
    /** Height of one item row. */
    public static final int ROW_HEIGHT = SLOT;
    /** Free space left and right. */
    public static final int MARGIN = 7;
    /** Window width: the same as the terminal, the production station and the keeper, so all frames match. */
    public static final int WIDTH = 12 * SLOT + 2 * MARGIN;
    /** Height of the title row. */
    public static final int TITLE_HEIGHT = 10;
    /** Height of a single line of text (the column header, the inventory label). */
    public static final int LABEL_HEIGHT = 10;
    /** Height of the status row: a slot, for the list cell and the button. */
    public static final int STATUS_HEIGHT = SLOT;
    /** Width of one of the three number fields. */
    public static final int NUMBER_WIDTH = 36;
    /** Space a number keeps from the right edge of its field. */
    public static final int NUMBER_PADDING = 3;
    /** Width of the clickable status pip at the right edge of a row. */
    public static final int STATUS_WIDTH = 10;
    /** Space between the item cell and the item name, and between the texts of the status row. */
    public static final int GAP = 4;
    /** Width of the cancel mark at the right end of the status text while an order runs. */
    public static final int CANCEL_WIDTH = 8;
    /** Slots per row of the player inventory. */
    public static final int PLAYER_COLUMNS = 9;
    /** {@code MenuBase#addPlayerSlots} puts the hotbar this far below the first inventory row. */
    public static final int HOTBAR_OFFSET = 58;
    public static final int PLAYER_SLOTS_HEIGHT = HOTBAR_OFFSET + SLOT;
    /** Free space below the last slot row. */
    public static final int BOTTOM_MARGIN = 5;
    /** Tallest window that fits at every GUI scale. */
    public static final int MAX_HEIGHT = 240;

    /** The three number fields of a row, left to right: Best, Below, Movable. */
    public static final int NUMBER_FIELDS = 3;
    /** Field index of the count at the best priority that holds the item. */
    public static final int FIELD_BEST = 0;
    /** Field index of the count at the priorities below it. */
    public static final int FIELD_BELOW = 1;
    /** Field index of what could move to a better place now. */
    public static final int FIELD_MOVABLE = 2;

    /** Everything above and below the item rows: title, column header, status row, inventory label and slots. */
    private static final int CHROME_HEIGHT =
            TITLE_HEIGHT + LABEL_HEIGHT + STATUS_HEIGHT + LABEL_HEIGHT + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    /** Item rows the window shows at once. */
    public static final int VISIBLE_ROWS = Math.min(OptimizerLimits.MAX_ROWS, (MAX_HEIGHT - CHROME_HEIGHT) / ROW_HEIGHT);

    /** Item rows the optimizer offers. */
    public int rows() {
        return OptimizerLimits.MAX_ROWS;
    }

    /** Item rows the window shows at once; the rest is reached by scrolling. */
    public int visibleRows() {
        return VISIBLE_ROWS;
    }

    /** Whether the window has to scroll to reach every row. */
    public boolean scrolls() {
        return rows() > visibleRows();
    }

    /** The largest scroll offset, i.e. the first row index the last page starts at. */
    public int maxScroll() {
        return Math.max(0, rows() - visibleRows());
    }

    /** Total window height. */
    public int height() {
        return playerSlotsY() + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    }

    /** Top of the column header above the item rows. */
    public int headerY() {
        return TITLE_HEIGHT;
    }

    /** Top of the first visible item row. */
    public int rowsY() {
        return headerY() + LABEL_HEIGHT;
    }

    /** Top of the visible row with index {@code line} ({@code 0 .. visibleRows - 1}). */
    public int rowY(int line) {
        return rowsY() + Math.max(0, line) * ROW_HEIGHT;
    }

    /** Left edge of a row's item cell. */
    public int slotX() {
        return MARGIN;
    }

    /** Left edge of the item name of a row. */
    public int nameX() {
        return slotX() + SLOT + GAP;
    }

    /** How wide the item name may be drawn before it is cut. */
    public int nameWidth() {
        return numbersX() - nameX() - GAP;
    }

    /** Left edge of the first of the three number fields. */
    public int numbersX() {
        return WIDTH - MARGIN - STATUS_WIDTH - NUMBER_FIELDS * NUMBER_WIDTH;
    }

    /** Left edge of number field {@code field} ({@link #FIELD_BEST}, {@link #FIELD_BELOW}, {@link #FIELD_MOVABLE}). */
    public int numberX(int field) {
        return numbersX() + Math.max(0, Math.min(field, NUMBER_FIELDS - 1)) * NUMBER_WIDTH;
    }

    /** Left edge of the status pip of a row. */
    public int statusX() {
        return WIDTH - MARGIN - STATUS_WIDTH;
    }

    /** Top of the status row with the list cell, the status text and the Optimise button. */
    public int statusRowY() {
        return rowsY() + visibleRows() * ROW_HEIGHT;
    }

    /** Left edge of the list cell, which takes a clipboard's items. */
    public int listCellX() {
        return MARGIN;
    }

    /** Left edge of the Optimise button. */
    public int optimiseX() {
        return WIDTH - MARGIN - SLOT;
    }

    /** Left edge of the status text. */
    public int statusTextX() {
        return listCellX() + SLOT + GAP;
    }

    /** How wide the status text may be while no cancel mark is shown. */
    public int statusTextWidth() {
        return optimiseX() - GAP - statusTextX();
    }

    /** Left edge of the cancel mark, at the right end of the status text. */
    public int cancelX() {
        return optimiseX() - GAP - CANCEL_WIDTH;
    }

    /** How wide the status text may be while the cancel mark is shown beside it. */
    public int statusTextWidthWithCancel() {
        return cancelX() - 1 - statusTextX();
    }

    /** Top of the player inventory's label. */
    public int playerLabelY() {
        return statusRowY() + STATUS_HEIGHT;
    }

    public int playerSlotsX() {
        return (WIDTH - PLAYER_COLUMNS * SLOT) / 2;
    }

    public int playerSlotsY() {
        return playerLabelY() + LABEL_HEIGHT;
    }
}
