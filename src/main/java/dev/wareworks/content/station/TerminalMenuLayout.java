package dev.wareworks.content.station;

/**
 * Where everything sits in a warehouse terminal's window ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * The menu needs these numbers on <b>both</b> sides, because {@code Slot#x} and {@code Slot#y} are final: a slot's
 * position is decided when the menu is built and can never be moved by the screen. The layout is therefore fixed and
 * derived only from the terminal's buffer size, which both sides know (the server reads it from the block entity, the
 * client gets it in the menu's extra data).
 * <p>
 * The window is one panel: stock grid, the terminal's buffer and the player inventory share the brass frame, instead of
 * putting Create's separate player inventory texture underneath it.
 * <p>
 * <b>Size budget.</b> Vanilla clamps the GUI scale so that the scaled screen is at least 320 × {@value #MAX_HEIGHT}
 * pixels ({@code Window#calculateScale}), so the window must not be taller than that at any buffer size a server can
 * configure. A bigger buffer grows by one slot row per {@value #GRID_COLUMNS} slots, and the <b>stock grid gives those
 * rows back</b> ({@link #gridRows()}, at most {@value #MAX_GRID_ROWS}, at least {@value #MIN_GRID_ROWS}) — the grid
 * scrolls anyway, while a buffer slot that is not drawn could not be reached by hand. Every buffer size the config
 * allows (1–27) therefore fits; only a buffer grown far past it by save data ({@link StationBuffer}) can still make the
 * window taller than the floor, and it then needs a smaller GUI scale.
 * <p>
 * <b>The production section is fixed, not conditional</b> (M11, ADR-024). The window carries a label plus
 * {@link #orderLines()} order lines whether or not the aisle has an order right now, because slot positions are decided
 * when the menu is built and a section that appears later could not move them. Its cost is paid by the stock grid for
 * the same reason the buffer's is — the grid scrolls, an order line does not. Two things follow, both deliberate: with
 * the default buffer the grid shows {@value #GRID_COLUMNS} × 2 cells instead of × {@value #MAX_GRID_ROWS}, and for a
 * buffer so large that the section would push the window past the floor the <b>lines are dropped first</b>
 * ({@value #MAX_ORDER_LINES} down to 0), which reproduces exactly the pre-M11 layout. The search for the tallest
 * section that fits happens once, in the constructor.
 */
public final class TerminalMenuLayout {
    /** Edge length of an item cell and of a slot. */
    public static final int SLOT = 18;
    /** Item cells per row of the stock grid, and slots per row of the terminal's buffer. */
    public static final int GRID_COLUMNS = 12;
    /** Rows of the stock grid with the default buffer; more entries are reached by scrolling. */
    public static final int MAX_GRID_ROWS = 3;
    /** Rows the stock grid keeps even for a very large buffer. */
    public static final int MIN_GRID_ROWS = 1;
    /** Production order lines the window shows at most, under the status line (M11, ADR-024). */
    public static final int MAX_ORDER_LINES = 2;
    /** Free space left and right of the grid. */
    public static final int MARGIN = 7;
    /** Window width: the grid plus its margins. */
    public static final int WIDTH = GRID_COLUMNS * SLOT + 2 * MARGIN;
    /** Height of the title row. */
    public static final int TITLE_HEIGHT = 10;
    /** Height of the row holding the search box, the amount input and the two option buttons. */
    public static final int SEARCH_HEIGHT = 18;
    /** Height of a single line of text (a section label or the status line). */
    public static final int LABEL_HEIGHT = 10;
    /** Vertical space between two sections. */
    public static final int GAP = 2;
    /** Slots per row of the player inventory. */
    public static final int PLAYER_COLUMNS = 9;
    /** {@code MenuBase#addPlayerSlots} puts the hotbar this far below the first inventory row. */
    public static final int HOTBAR_OFFSET = 58;
    /** Height of the player inventory's four slot rows. */
    public static final int PLAYER_SLOTS_HEIGHT = HOTBAR_OFFSET + SLOT;
    /** Free space below the last slot row. */
    public static final int BOTTOM_MARGIN = 5;
    /** Tallest window that fits at every GUI scale (the height vanilla's scale clamp guarantees). */
    public static final int MAX_HEIGHT = 240;

    /** Everything of {@link #height()} that is not a grid or buffer slot row. */
    private static final int HEIGHT_WITHOUT_SLOT_ROWS =
            TITLE_HEIGHT + SEARCH_HEIGHT + 3 * LABEL_HEIGHT + 4 * GAP + 1 + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;

    private final int bufferSlots;
    private final int bufferColumns;
    private final int bufferRows;
    private final int gridRows;
    private final int orderLines;

    /**
     * @param bufferSlots number of slots of the terminal's buffer (at least 1)
     */
    public TerminalMenuLayout(int bufferSlots) {
        this.bufferSlots = Math.max(1, bufferSlots);
        this.bufferColumns = Math.min(this.bufferSlots, GRID_COLUMNS);
        this.bufferRows = (this.bufferSlots + bufferColumns - 1) / bufferColumns;
        // The tallest production section that still fits, giving up its lines before the grid's last row: a buffer so
        // large that both cannot be had is the one case where the section disappears (see the class comment).
        int lines = MAX_ORDER_LINES;
        int rows = gridRowsFor(lines, bufferRows);
        while (lines > 0 && heightFor(lines, bufferRows, rows) > MAX_HEIGHT) {
            lines--;
            rows = gridRowsFor(lines, bufferRows);
        }
        this.orderLines = lines;
        this.gridRows = rows;
    }

    /** Height of the production section with {@code orderLines} lines; 0 when it is left out entirely. */
    private static int productionHeight(int orderLines) {
        return orderLines <= 0 ? 0 : GAP + LABEL_HEIGHT + orderLines * LABEL_HEIGHT;
    }

    private static int gridRowsFor(int orderLines, int bufferRows) {
        int fitting = (MAX_HEIGHT - HEIGHT_WITHOUT_SLOT_ROWS - productionHeight(orderLines)) / SLOT - bufferRows;
        return Math.max(MIN_GRID_ROWS, Math.min(MAX_GRID_ROWS, fitting));
    }

    private static int heightFor(int orderLines, int bufferRows, int gridRows) {
        return HEIGHT_WITHOUT_SLOT_ROWS + productionHeight(orderLines) + (bufferRows + gridRows) * SLOT;
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

    /** Rows of the stock grid: {@value #MAX_GRID_ROWS}, less when the buffer needs the space (see the class comment). */
    public int gridRows() {
        return gridRows;
    }

    /** Item cells the grid shows at once. */
    public int gridCells() {
        return GRID_COLUMNS * gridRows;
    }

    /** Total window height. */
    public int height() {
        return playerSlotsY() + PLAYER_SLOTS_HEIGHT + BOTTOM_MARGIN;
    }

    /** Top of the search row. */
    public int searchY() {
        return TITLE_HEIGHT;
    }

    /** Left edge of the stock grid. */
    public int gridX() {
        return MARGIN;
    }

    /** Top of the stock grid. */
    public int gridY() {
        return TITLE_HEIGHT + SEARCH_HEIGHT + GAP;
    }

    public int gridWidth() {
        return GRID_COLUMNS * SLOT;
    }

    public int gridHeight() {
        return gridRows * SLOT;
    }

    /** Top of the label above the terminal's buffer slots. */
    public int bufferLabelY() {
        return gridY() + gridHeight() + GAP;
    }

    /** Left edge of the buffer slots, aligned with the label above them and with the stock grid. */
    public int bufferX() {
        return MARGIN;
    }

    /** Top of the buffer slots. */
    public int bufferY() {
        return bufferLabelY() + LABEL_HEIGHT;
    }

    /** X of the buffer slot with index {@code slot}. */
    public int bufferSlotX(int slot) {
        return bufferX() + slot % bufferColumns * SLOT;
    }

    /** Y of the buffer slot with index {@code slot}. */
    public int bufferSlotY(int slot) {
        return bufferY() + slot / bufferColumns * SLOT;
    }

    /** Top of the status line. */
    public int statusY() {
        return bufferY() + bufferRows * SLOT + GAP;
    }

    /** Production order lines the window has room for; 0 when the buffer left none (see the class comment). */
    public int orderLines() {
        return orderLines;
    }

    /** Top of the "Production" label; only meaningful while {@link #orderLines()} is above 0. */
    public int productionLabelY() {
        return statusY() + LABEL_HEIGHT + GAP;
    }

    /** Top of the production order line with index {@code line}. */
    public int orderLineY(int line) {
        return productionLabelY() + LABEL_HEIGHT + Math.max(0, line) * LABEL_HEIGHT;
    }

    /** Top of the player inventory's label. */
    public int playerLabelY() {
        if (orderLines <= 0)
            return statusY() + LABEL_HEIGHT + GAP;
        return orderLineY(orderLines - 1) + LABEL_HEIGHT + GAP;
    }

    /** X of the first player inventory slot. */
    public int playerSlotsX() {
        return (WIDTH - PLAYER_COLUMNS * SLOT) / 2;
    }

    /** Y of the first player inventory slot row. */
    public int playerSlotsY() {
        return playerLabelY() + LABEL_HEIGHT + 1;
    }
}
