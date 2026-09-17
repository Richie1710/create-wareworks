package dev.wareworks.content.station;

import dev.wareworks.core.production.ProductionPattern;

/**
 * Where everything sits in a warehouse production station's window ({@code docs/warehouse-system.md} §3.5).
 * <p>
 * Like the terminal's layout this is shared by both sides, because {@code Slot#x} and {@code Slot#y} are final: a
 * slot's position is decided when the menu is built and can never be moved by the screen. It is derived only from the
 * two numbers both sides know — the station's buffer size and its number of pattern slots — which the server writes
 * into the menu's extra data.
 * <p>
 * <b>One pattern at a time.</b> The window shows the 3 x 3 grid and the result of the <i>selected</i> pattern, with a
 * strip of pattern tabs beside it; showing every pattern's grid at once would need
 * {@code maxProductionPatterns × 54} pixels and could not fit. The tabs are laid out in {@value #TAB_COLUMNS} columns,
 * so all {@value ProductionPatterns#MAX_SLOTS} of them fit inside the grid's own height and cost no extra row.
 * <p>
 * <b>Size budget.</b> Vanilla clamps the GUI scale so that the scaled screen is at least 320 × {@value #MAX_HEIGHT}
 * pixels, so the window must fit that at every configuration. The grid and the buffer rows are what they are; the
 * <b>order lines</b> are therefore the flexible part ({@link #orderLines()}, {@value #MAX_ORDER_LINES} down to 0), the
 * same trade the terminal makes with its stock grid. Every combination the config allows (buffer 1–27, patterns 1–8)
 * stays at or below {@value #MAX_HEIGHT}.
 */
public final class ProductionMenuLayout {
    /** Edge length of a slot, a pattern cell and a pattern tab. */
    public static final int SLOT = 18;
    /** Slots per row of the station's buffer. */
    public static final int BUFFER_COLUMNS = 12;
    /** Free space left and right of the buffer row. */
    public static final int MARGIN = 7;
    /** Window width: the buffer row plus its margins (the same width as the terminal, so both frames match). */
    public static final int WIDTH = BUFFER_COLUMNS * SLOT + 2 * MARGIN;
    /** Height of the title row. */
    public static final int TITLE_HEIGHT = 10;
    /** Height of a single line of text (a section label, an order line or the status line). */
    public static final int LABEL_HEIGHT = 10;
    /** Vertical space between two sections. */
    public static final int GAP = 2;
    /** Cells per row of the authoring grid. */
    public static final int GRID_WIDTH = ProductionPattern.GRID_WIDTH;
    /** Space between the grid and the result cell, where the arrow is drawn. */
    public static final int ARROW_WIDTH = 14;
    /** Space between the result cell and the pattern tabs. */
    public static final int TAB_GAP = 12;
    /** Columns of the pattern tab strip; {@value ProductionPatterns#MAX_SLOTS} tabs then fit the grid's height. */
    public static final int TAB_COLUMNS = 3;
    /** Order lines the window shows at most, below the buffer. */
    public static final int MAX_ORDER_LINES = 2;
    /** Slots per row of the player inventory. */
    public static final int PLAYER_COLUMNS = 9;
    /** {@code MenuBase#addPlayerSlots} puts the hotbar this far below the first inventory row. */
    public static final int HOTBAR_OFFSET = 58;
    public static final int PLAYER_SLOTS_HEIGHT = HOTBAR_OFFSET + SLOT;
    /** Free space below the last slot row. */
    public static final int BOTTOM_MARGIN = 5;
    /** Tallest window that fits at every GUI scale. */
    public static final int MAX_HEIGHT = 240;

    /** Cells of one pattern: its grid plus the result. */
    public static final int PATTERN_CELLS = ProductionPatterns.ENTRIES_PER_PATTERN;
    /** The cell index of the result cell. */
    public static final int RESULT_CELL = ProductionPatterns.RESULT_ENTRY;

    /** Height of the pattern block: three grid rows, which is also the tallest the tab strip can become. */
    private static final int PATTERN_BLOCK_HEIGHT = GRID_WIDTH * SLOT;
    /** Width of the pattern block: grid, arrow, result, gap, tab strip. */
    private static final int PATTERN_BLOCK_WIDTH =
            GRID_WIDTH * SLOT + ARROW_WIDTH + SLOT + TAB_GAP + TAB_COLUMNS * SLOT;

    private final int bufferSlots;
    private final int bufferColumns;
    private final int bufferRows;
    private final int patternSlots;
    private final int orderLines;

    /**
     * @param bufferSlots  slots of the station's buffer (at least 1)
     * @param patternSlots pattern slots of the station (at least 1)
     */
    public ProductionMenuLayout(int bufferSlots, int patternSlots) {
        this.bufferSlots = Math.max(1, bufferSlots);
        this.bufferColumns = Math.min(this.bufferSlots, BUFFER_COLUMNS);
        this.bufferRows = (this.bufferSlots + bufferColumns - 1) / bufferColumns;
        this.patternSlots = Math.max(1, patternSlots);
        int withoutOrders = ordersY() + LABEL_HEIGHT + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
        int fitting = (MAX_HEIGHT - withoutOrders) / LABEL_HEIGHT;
        this.orderLines = Math.max(0, Math.min(MAX_ORDER_LINES, fitting));
    }

    public int bufferSlots() {
        return bufferSlots;
    }

    public int bufferColumns() {
        return bufferColumns;
    }

    public int bufferRows() {
        return bufferRows;
    }

    public int patternSlots() {
        return patternSlots;
    }

    /** Rows of the pattern tab strip. */
    public int tabRows() {
        return (patternSlots + TAB_COLUMNS - 1) / TAB_COLUMNS;
    }

    /** Order lines the window has room for (see the class comment). */
    public int orderLines() {
        return orderLines;
    }

    /** Total window height. */
    public int height() {
        return playerSlotsY() + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    }

    /** Top of the label above the pattern block. */
    public int patternLabelY() {
        return TITLE_HEIGHT;
    }

    /** Top of the pattern block (grid, result and tabs). */
    public int patternsY() {
        return patternLabelY() + LABEL_HEIGHT;
    }

    /** Left edge of the pattern block, centred in the window. */
    public int patternsX() {
        return (WIDTH - PATTERN_BLOCK_WIDTH) / 2;
    }

    /** X of a pattern cell: {@code 0..8} are the grid, {@value #RESULT_CELL} is the result. */
    public int patternCellX(int cell) {
        if (cell == RESULT_CELL)
            return patternsX() + GRID_WIDTH * SLOT + ARROW_WIDTH;
        int index = Math.max(0, Math.min(cell, RESULT_CELL - 1));
        return patternsX() + index % GRID_WIDTH * SLOT;
    }

    /** Y of a pattern cell; the result cell is centred beside the grid. */
    public int patternCellY(int cell) {
        if (cell == RESULT_CELL)
            return patternsY() + (PATTERN_BLOCK_HEIGHT - SLOT) / 2;
        int index = Math.max(0, Math.min(cell, RESULT_CELL - 1));
        return patternsY() + index / GRID_WIDTH * SLOT;
    }

    /** X of the arrow between the grid and the result cell. */
    public int arrowX() {
        return patternsX() + GRID_WIDTH * SLOT;
    }

    /** Left edge of the pattern tab strip. */
    public int tabsX() {
        return patternsX() + GRID_WIDTH * SLOT + ARROW_WIDTH + SLOT + TAB_GAP;
    }

    /** X of the pattern tab of slot {@code pattern}. */
    public int tabX(int pattern) {
        return tabsX() + Math.max(0, pattern) % TAB_COLUMNS * SLOT;
    }

    /** Y of the pattern tab of slot {@code pattern}. */
    public int tabY(int pattern) {
        return patternsY() + Math.max(0, pattern) / TAB_COLUMNS * SLOT;
    }

    /** Top of the label above the buffer slots. */
    public int bufferLabelY() {
        return patternsY() + PATTERN_BLOCK_HEIGHT + GAP;
    }

    public int bufferX() {
        return MARGIN;
    }

    public int bufferY() {
        return bufferLabelY() + LABEL_HEIGHT;
    }

    public int bufferSlotX(int slot) {
        return bufferX() + slot % bufferColumns * SLOT;
    }

    public int bufferSlotY(int slot) {
        return bufferY() + slot / bufferColumns * SLOT;
    }

    /** Top of the first order line. */
    public int ordersY() {
        return bufferY() + bufferRows * SLOT + GAP;
    }

    /** Top of the order line with index {@code line}. */
    public int orderLineY(int line) {
        return ordersY() + Math.max(0, line) * LABEL_HEIGHT;
    }

    /** Top of the player inventory's label. */
    public int playerLabelY() {
        return ordersY() + Math.max(orderLines, 0) * LABEL_HEIGHT;
    }

    public int playerSlotsX() {
        return (WIDTH - PLAYER_COLUMNS * SLOT) / 2;
    }

    public int playerSlotsY() {
        return playerLabelY() + LABEL_HEIGHT;
    }
}
