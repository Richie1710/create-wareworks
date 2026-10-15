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
 * configure. A bigger buffer grows by one slot row per {@value #GRID_COLUMNS} − {@value #LIST_COLUMNS} slots (the list
 * slot takes the last column, {@link #bufferColumns()}), and the <b>stock grid gives those rows back</b>
 * ({@link #gridRows()}, at most {@value #MAX_GRID_ROWS}, at least {@value #MIN_GRID_ROWS}) — the grid scrolls anyway,
 * while a buffer slot that is not drawn could not be reached by hand. Every buffer size the config
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
 * <p>
 * <b>The crafting panel folds out to the right, never down</b> (issue #24, M32). There is no vertical room left — the
 * window is 234 pixels tall, 238 with 23–27 buffer slots — and a crafting table's grid, arrow and result side by side
 * would make the window wider than 320. The panel is therefore a strip of {@value #PANEL_WIDTH} pixels beside the
 * window, from {@link #PANEL_X} on: the grid in the stock grid's rows, the arrow and the result under its middle
 * column, then the ready count, one row of buttons and the progress row. Folded out the window is
 * {@value #FOLDED_WIDTH} pixels wide, which still fits the floor, and <b>no slot moves</b>: the panel holds no slot at
 * all, because its cells are ghosts ({@code core.crafting.CraftGrid}), so neither the slot order nor a single
 * {@code Slot#x} depends on whether it is folded out. The toggle sits inside the window, in the free column right of
 * the player inventory, and {@link #foldOffset} keeps the wider window on the screen.
 */
public final class TerminalMenuLayout {
    /** Edge length of an item cell and of a slot. */
    public static final int SLOT = 18;
    /** Item cells per row of the stock grid. */
    public static final int GRID_COLUMNS = 12;
    /**
     * Columns of the buffer row the <b>list slot</b> takes for itself (M23, issue #19): the clipboard a list order is
     * read from and ticked off on sits at the right-hand end of the first buffer row
     * ({@link #listSlotX()}/{@link #listSlotY()}).
     * <p>
     * It is a reserved column rather than a widget of its own row, because the window must not get taller: the buffer
     * gives up one column instead ({@link #bufferColumns()}), which changes nothing at all for every buffer up to 11
     * slots — the shipped default is 9 — and costs a buffer of exactly 12 one extra row, which the layout search
     * already knows how to pay for.
     */
    public static final int LIST_COLUMNS = 1;
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
    /** Narrowest scaled screen vanilla's scale clamp guarantees ({@code Window#calculateScale}). */
    public static final int MIN_SCREEN_WIDTH = 320;

    // --- the crafting panel (issue #24, M32) -------------------------------------------------------------------------
    /** Cells per row, and rows, of the crafting panel's grid: a crafting table's 3 x 3. */
    public static final int CRAFT_GRID_WIDTH = 3;
    /** Cells of the crafting panel's grid. */
    public static final int CRAFT_CELLS = CRAFT_GRID_WIDTH * CRAFT_GRID_WIDTH;
    /** Width of the panel's content: the grid, and everything under it, which keeps to the grid's three columns. */
    public static final int PANEL_CONTENT_WIDTH = CRAFT_GRID_WIDTH * SLOT;
    /** Width of the crafting panel: its content plus the margins the window has. */
    public static final int PANEL_WIDTH = PANEL_CONTENT_WIDTH + 2 * MARGIN;
    /** Width of the window while the crafting panel is folded out; it fits {@value #MIN_SCREEN_WIDTH}. */
    public static final int FOLDED_WIDTH = WIDTH + PANEL_WIDTH;
    /** Left edge of the crafting panel: where the window's own frame ends. */
    public static final int PANEL_X = WIDTH;
    /** Left edge of the panel's content. */
    public static final int PANEL_CONTENT_X = PANEL_X + MARGIN;
    /** Top of the panel's title text, level with the window's own title. */
    public static final int PANEL_TITLE_Y = 2;
    /**
     * Widest the panel's title may be, in every language: one word in the content width. Everything longer than a
     * number goes to the window's status row, a tooltip or the modal panel instead.
     */
    public static final int MAX_PANEL_TITLE_WIDTH = PANEL_CONTENT_WIDTH;
    /** Height of the arrow between the grid and the result ({@code AllGuiTextures.JEI_DOWN_ARROW}, 18 x 14). */
    public static final int ARROW_HEIGHT = 14;
    /** Height of the progress bar. */
    public static final int PROGRESS_HEIGHT = 2;
    /** Height of a number drawn at three quarters of the normal size, as the panel draws them: 9 x 0.75, rounded up. */
    public static final int SMALL_TEXT_HEIGHT = 7;
    /** Button places in the panel's button row, one under each grid column. */
    public static final int CRAFT_BUTTONS = CRAFT_GRID_WIDTH;
    /** The button place of Fetch, and of what it turns into while an order runs (answer, resume, cancel). */
    public static final int FETCH_BUTTON = 0;
    /** The button place kept free for a later button, so that adding one moves nothing. */
    public static final int FREE_BUTTON = 1;
    /** The button place of Clear. */
    public static final int CLEAR_BUTTON = 2;
    /** Least space {@link #foldOffset} keeps between the folded-out window and either edge of the screen. */
    public static final int FOLD_MARGIN = 4;
    /**
     * The most {@link #foldOffset} moves the window to the right: half the panel, which is exactly what keeps the
     * window's own part where it is when folded in, so on a wide enough screen the panel appears and nothing jumps.
     */
    public static final int MAX_FOLD_OFFSET = PANEL_WIDTH / 2;

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
        this.bufferColumns = Math.min(this.bufferSlots, GRID_COLUMNS - LIST_COLUMNS);
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

    /**
     * X of the <b>list slot</b>: the right-hand end of the buffer row, in the column the buffer gave up for it
     * ({@link #LIST_COLUMNS}). It is aligned with the grid's last cell, so the window's right edge reads as one line.
     */
    public int listSlotX() {
        return MARGIN + (GRID_COLUMNS - LIST_COLUMNS) * SLOT;
    }

    /** Y of the list slot: the first buffer row, beside the slots the crane delivers into. */
    public int listSlotY() {
        return bufferY();
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

    // --- the crafting panel (issue #24, M32) -------------------------------------------------------------------------

    /** Height of the crafting panel: the window's own, so the two frames line up. */
    public int panelHeight() {
        return height();
    }

    /** X of the panel's grid cell {@code cell}, {@code 0..8} in row-major order. */
    public int craftCellX(int cell) {
        return PANEL_CONTENT_X + clampCell(cell) % CRAFT_GRID_WIDTH * SLOT;
    }

    /** Y of the panel's grid cell {@code cell}: the grid starts level with the stock grid. */
    public int craftCellY(int cell) {
        return gridY() + clampCell(cell) / CRAFT_GRID_WIDTH * SLOT;
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(cell, CRAFT_CELLS - 1));
    }

    /** X of the arrow under the grid's middle column. */
    public int craftArrowX() {
        return PANEL_CONTENT_X + CRAFT_GRID_WIDTH / 2 * SLOT;
    }

    /** Top of the arrow, just under the grid. */
    public int craftArrowY() {
        return gridY() + CRAFT_GRID_WIDTH * SLOT + GAP;
    }

    /** X of the result cell, under the arrow. */
    public int craftResultX() {
        return craftArrowX();
    }

    /** Y of the result cell. */
    public int craftResultY() {
        return craftArrowY() + ARROW_HEIGHT + GAP;
    }

    /** Centre of the ready count ("x3": the crafts the buffer holds now), under the result cell. */
    public int craftReadyCenterX() {
        return craftResultX() + SLOT / 2;
    }

    /** Top of the ready count's row, a {@value #LABEL_HEIGHT} pixel row for a smaller number. */
    public int craftReadyY() {
        return craftResultY() + SLOT + GAP;
    }

    /**
     * X of the button place {@code button} in the panel's button row: {@value #FETCH_BUTTON} is Fetch,
     * {@value #FREE_BUTTON} is kept free, {@value #CLEAR_BUTTON} is Clear.
     */
    public int craftButtonX(int button) {
        return PANEL_CONTENT_X + Math.max(0, Math.min(button, CRAFT_BUTTONS - 1)) * SLOT;
    }

    /** Top of the panel's button row, under the ready count. */
    public int craftButtonY() {
        return craftReadyY() + LABEL_HEIGHT;
    }

    /** Top of the progress bar, which spans the content width. */
    public int craftProgressY() {
        return craftButtonY() + SLOT + 2 * GAP;
    }

    /** Top of the progress numbers' row ("6/16", and the cancel mark at its right end while an order is open). */
    public int craftProgressTextY() {
        return craftProgressY() + PROGRESS_HEIGHT + GAP;
    }

    /**
     * X of the toggle that folds the panel out. It sits inside the window, in the free column between the player
     * inventory (which ends at x 196) and the right margin (x 223), centred and rounded to the right: x 201.
     */
    public int craftToggleX() {
        int freeLeft = playerSlotsX() + PLAYER_COLUMNS * SLOT;
        int freeRight = WIDTH - MARGIN;
        return freeLeft + (freeRight - freeLeft - SLOT + 1) / 2;
    }

    /**
     * Top of the fold toggle: level with the inventory's first row of cells. The screen draws a slot's cell one pixel
     * above and left of {@code Slot#y} ({@code renderSlotBackgrounds}), so that row's cells start at
     * {@code playerSlotsY() - 1}, right under the player inventory's label row.
     */
    public int craftToggleY() {
        return playerSlotsY() - 1;
    }

    /**
     * How far the screen moves the window to the right while the panel is folded out
     * ({@code AbstractSimiContainerScreen#setWindowOffset}, which is added to vanilla's centred {@code leftPos}):
     * {@code clamp((screenWidth - 298) / 2 - 4, 0, 34)}.
     * <p>
     * Centring the {@value #FOLDED_WIDTH} pixel window would move its own part {@value #MAX_FOLD_OFFSET} pixels to the
     * left the moment the panel folds out. The offset takes that jump back as far as the screen allows: all of it from
     * a screen 298 + 2 x (4 + 34) = 374 pixels wide on, and on the narrowest screen (320) as much as still leaves
     * {@value #FOLD_MARGIN} pixels to the right edge. The window never comes closer than that to either edge of a
     * screen it fits at all; below 298 + 2 x 4 = 306 pixels, which vanilla's scale clamp does not produce, the offset
     * is 0 and the window is simply centred.
     *
     * @param screenWidth the scaled screen width in GUI pixels
     */
    public static int foldOffset(int screenWidth) {
        int centredMargin = (screenWidth - FOLDED_WIDTH) / 2;
        return Math.max(0, Math.min(MAX_FOLD_OFFSET, centredMargin - FOLD_MARGIN));
    }
}
