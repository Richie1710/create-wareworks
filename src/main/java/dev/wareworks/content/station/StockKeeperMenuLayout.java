package dev.wareworks.content.station;

/**
 * Where everything sits in a warehouse stock keeper's window ({@code docs/warehouse-system.md} §3.6, M15).
 * <p>
 * Like the terminal's and the production station's layouts this is shared by both sides, because {@code Slot#x} and
 * {@code Slot#y} are final: a slot's position is decided when the menu is built and can never be moved by the screen.
 * It is derived from the one number both sides know — the keeper's number of rule rows — which the server writes into
 * the menu's extra data.
 * <p>
 * <b>One line per rule.</b> A row is an item cell, the item's name, the three numbers and a status pip, all on
 * {@value #ROW_HEIGHT} pixels, so a player reads the whole policy of a keeper top to bottom without opening anything.
 * <p>
 * <b>Size budget.</b> Vanilla clamps the GUI scale so that the scaled screen is at least 320 × {@value #MAX_HEIGHT}
 * pixels, so the window must fit that at every configuration. The player inventory, the title, the column header and
 * the status line are fixed, which leaves room for {@link #visibleRows()} rows — {@value #MAX_VISIBLE_ROWS} at the
 * most. The default {@code stockKeeperRows} is exactly that many, so the common case has nothing to scroll; a keeper
 * configured with more rows scrolls, and an aisle may hold several keepers instead.
 */
public final class StockKeeperMenuLayout {
    /** Edge length of a rule row's item cell. */
    public static final int SLOT = 18;
    /** Height of one rule row. */
    public static final int ROW_HEIGHT = SLOT;
    /** Free space left and right. */
    public static final int MARGIN = 7;
    /** Window width: the same as the terminal and the production station, so all three frames match. */
    public static final int WIDTH = 12 * SLOT + 2 * MARGIN;
    /** Height of the title row. */
    public static final int TITLE_HEIGHT = 10;
    /** Height of a single line of text (the column header, the status line, the inventory label). */
    public static final int LABEL_HEIGHT = 10;
    /** Width of one of the three number fields. */
    public static final int NUMBER_WIDTH = 36;
    /** Width of the status pip at the right edge of a row. */
    public static final int STATUS_WIDTH = 6;
    /** Space between the item cell and the item name. */
    public static final int NAME_GAP = 4;
    /** Slots per row of the player inventory. */
    public static final int PLAYER_COLUMNS = 9;
    /** {@code MenuBase#addPlayerSlots} puts the hotbar this far below the first inventory row. */
    public static final int HOTBAR_OFFSET = 58;
    public static final int PLAYER_SLOTS_HEIGHT = HOTBAR_OFFSET + SLOT;
    /** Free space below the last slot row. */
    public static final int BOTTOM_MARGIN = 5;
    /** Tallest window that fits at every GUI scale. */
    public static final int MAX_HEIGHT = 240;

    /** The three number fields of a row, left to right: minimum, maximum, reserve. */
    public static final int NUMBER_FIELDS = 3;
    /** Field index of the minimum column. */
    public static final int FIELD_MINIMUM = 0;
    /** Field index of the maximum column. */
    public static final int FIELD_MAXIMUM = 1;
    /** Field index of the reserve column. */
    public static final int FIELD_RESERVE = 2;

    /** Everything above and below the rule rows: title, column header, status line, inventory label and slots. */
    private static final int CHROME_HEIGHT =
            TITLE_HEIGHT + LABEL_HEIGHT + LABEL_HEIGHT + LABEL_HEIGHT + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    /** Rule rows the window can ever show; a keeper with more of them scrolls. */
    public static final int MAX_VISIBLE_ROWS = (MAX_HEIGHT - CHROME_HEIGHT) / ROW_HEIGHT;

    private final int rows;
    private final int visibleRows;

    /** @param rows rule rows of the keeper (at least 1, at most {@link StockKeeperRules#MAX_ROWS}) */
    public StockKeeperMenuLayout(int rows) {
        this.rows = Math.max(1, Math.min(rows, StockKeeperRules.MAX_ROWS));
        this.visibleRows = Math.max(1, Math.min(this.rows, MAX_VISIBLE_ROWS));
    }

    /** Rule rows the keeper offers. */
    public int rows() {
        return rows;
    }

    /** Rule rows the window shows at once; the rest is reached by scrolling. */
    public int visibleRows() {
        return visibleRows;
    }

    /** Whether the window has to scroll to reach every row. */
    public boolean scrolls() {
        return rows > visibleRows;
    }

    /** The largest scroll offset, i.e. the first row index the last page starts at. */
    public int maxScroll() {
        return Math.max(0, rows - visibleRows);
    }

    /** Total window height. */
    public int height() {
        return playerSlotsY() + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    }

    /** Top of the column header above the rule rows. */
    public int headerY() {
        return TITLE_HEIGHT;
    }

    /** Top of the first visible rule row. */
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
        return slotX() + SLOT + NAME_GAP;
    }

    /** How wide the item name may be drawn before it is cut. */
    public int nameWidth() {
        return numbersX() - nameX() - NAME_GAP;
    }

    /** Left edge of the first of the three number fields. */
    public int numbersX() {
        return WIDTH - MARGIN - STATUS_WIDTH - NUMBER_FIELDS * NUMBER_WIDTH;
    }

    /** Left edge of number field {@code field} ({@link #FIELD_MINIMUM}, {@link #FIELD_MAXIMUM}, {@link #FIELD_RESERVE}). */
    public int numberX(int field) {
        return numbersX() + Math.max(0, Math.min(field, NUMBER_FIELDS - 1)) * NUMBER_WIDTH;
    }

    /** Left edge of the status pip of a row. */
    public int statusX() {
        return WIDTH - MARGIN - STATUS_WIDTH;
    }

    /** Top of the line that reports what the last edit had to correct. */
    public int statusLineY() {
        return rowsY() + visibleRows * ROW_HEIGHT;
    }

    /** Top of the player inventory's label. */
    public int playerLabelY() {
        return statusLineY() + LABEL_HEIGHT;
    }

    public int playerSlotsX() {
        return (WIDTH - PLAYER_COLUMNS * SLOT) / 2;
    }

    public int playerSlotsY() {
        return playerLabelY() + LABEL_HEIGHT;
    }
}
