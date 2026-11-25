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
 * <b>One pattern at a time.</b> The window shows the <i>selected</i> pattern, read from left to right: the 3 x 3 grid,
 * the column of the two fluid cells, the arrow and the result alone (issue #62: what goes in stands left of the arrow,
 * what comes out right of it), with a strip of pattern tabs beside it; showing every pattern's grid at once would need
 * {@code maxProductionPatterns × 54} pixels and could not fit. The tabs always stay inside the grid's own height of
 * {@value #VISIBLE_TAB_ROWS} rows, so they cost no extra row at any configuration (issue #35, ADR-068):
 * <ul>
 *   <li>up to 9 patterns: {@value #MIN_TAB_COLUMNS} columns, exactly the look the station had when it held at most 8;</li>
 *   <li>10 to 18 patterns: {@code ceil(n / 3)} columns, up to {@value #MAX_TAB_COLUMNS}, so every tab is still
 *       visible at once;</li>
 *   <li>above 18: {@value #MAX_TAB_COLUMNS} × {@value #VISIBLE_TAB_ROWS} visible tabs that <b>scroll by rows</b> with
 *       a slim scroll bar beside them. The scroll position is client-only view state the screen owns; this class only
 *       says where a tab sits for a given scroll value ({@link #tabY(int, int)}).</li>
 * </ul>
 * <p>
 * <b>Size budget.</b> Vanilla clamps the GUI scale so that the scaled screen is at least 320 × {@value #MAX_HEIGHT}
 * pixels, so the window must fit that at every configuration. The grid and the buffer rows are what they are; the
 * <b>order lines</b> are therefore the flexible part ({@link #orderLines()}, {@value #MAX_ORDER_LINES} down to 0), the
 * same trade the terminal makes with its stock grid. Every combination the config allows (buffer 1–27, patterns 1–{@value ProductionPatterns#MAX_SLOTS})
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
    /** Space between the grid and the column of the fluid cells (issue #62). */
    public static final int FLUID_GAP = 2;
    /** Space between the two fluid cells. */
    public static final int FLUID_SPACING = 2;
    /** Space between the fluid column and the result cell, where the arrow is drawn. */
    public static final int ARROW_WIDTH = 12;
    /** Space between the result cell and the pattern tabs, while the window has room for it. */
    public static final int TAB_GAP = 12;
    /** Smallest that space becomes, with the widest tab strips. */
    public static final int MIN_TAB_GAP = 5;
    /** Columns of the pattern tab strip up to 9 patterns (the look of every station before issue #35). */
    public static final int MIN_TAB_COLUMNS = 3;
    /** Most columns the pattern tab strip widens to; above 3 rows of these the strip scrolls. */
    public static final int MAX_TAB_COLUMNS = 6;
    /** Tab rows visible at once: the grid's own height. */
    public static final int VISIBLE_TAB_ROWS = ProductionPattern.GRID_WIDTH;
    /** Space between the last tab column and the scroll bar. */
    public static final int SCROLLBAR_GAP = 2;
    /** Width of the tab strip's scroll bar. */
    public static final int SCROLLBAR_WIDTH = 3;
    /** Shortest the scroll bar's thumb becomes. */
    public static final int MIN_THUMB_HEIGHT = 6;
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

    /** The pattern block left of the tab gap: grid, fluid column, arrow and result. */
    private static final int PATTERN_WIDTH = GRID_WIDTH * SLOT + FLUID_GAP + SLOT + ARROW_WIDTH + SLOT;
    /** Height of the two fluid cells with the space between them, centred in the pattern block. */
    private static final int FLUID_COLUMN_HEIGHT =
            ProductionPatterns.FLUID_CELLS * SLOT + (ProductionPatterns.FLUID_CELLS - 1) * FLUID_SPACING;
    /** Height of the pattern block: three grid rows, which is also the tallest the tab strip can become. */
    private static final int PATTERN_BLOCK_HEIGHT = GRID_WIDTH * SLOT;

    private final int bufferSlots;
    private final int bufferColumns;
    private final int bufferRows;
    private final int patternSlots;
    private final int tabColumns;
    private final int tabRows;
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
        this.tabColumns = tabColumnsFor(this.patternSlots);
        this.tabRows = (this.patternSlots + tabColumns - 1) / tabColumns;
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

    /**
     * Columns of the tab strip for {@code patterns} pattern slots: {@value #MIN_TAB_COLUMNS} up to 9, then
     * {@code ceil(n / 3)} up to {@value #MAX_TAB_COLUMNS} (see the class comment).
     */
    public static int tabColumnsFor(int patterns) {
        int n = Math.max(1, patterns);
        if (n <= MIN_TAB_COLUMNS * VISIBLE_TAB_ROWS)
            return MIN_TAB_COLUMNS;
        return Math.min(MAX_TAB_COLUMNS, (n + VISIBLE_TAB_ROWS - 1) / VISIBLE_TAB_ROWS);
    }

    /** Columns of the pattern tab strip. */
    public int tabColumns() {
        return tabColumns;
    }

    /** Rows of the pattern tab strip, visible or not. */
    public int tabRows() {
        return tabRows;
    }

    /** Tab rows visible at once: at most {@value #VISIBLE_TAB_ROWS}. */
    public int visibleTabRows() {
        return Math.min(VISIBLE_TAB_ROWS, tabRows);
    }

    /** Largest scroll value of the tab strip, in rows: 0 unless the strip {@link #scrolls()}. */
    public int maxTabScroll() {
        return tabRows - visibleTabRows();
    }

    /** Whether the tab strip has more rows than it shows, i.e. above 18 patterns. */
    public boolean scrolls() {
        return maxTabScroll() > 0;
    }

    /** {@code scroll} clamped into {@code 0..}{@link #maxTabScroll()}. */
    public int clampTabScroll(int scroll) {
        return Math.max(0, Math.min(scroll, maxTabScroll()));
    }

    /**
     * The scroll value closest to {@code scroll} at which the tab of {@code pattern} is visible: unchanged when it
     * already is, otherwise just far enough that its row becomes the first or the last visible one.
     */
    public int scrollToShow(int pattern, int scroll) {
        int row = tabRow(pattern);
        int clamped = clampTabScroll(scroll);
        if (row < clamped)
            return clampTabScroll(row);
        if (row >= clamped + visibleTabRows())
            return clampTabScroll(row - visibleTabRows() + 1);
        return clamped;
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

    /**
     * Space between the result cell and the tab strip: {@value #TAB_GAP} while the block then keeps the window's margins,
     * narrowed down to {@value #MIN_TAB_GAP} for the wide strips.
     */
    public int tabGap() {
        int room = WIDTH - 2 * MARGIN - PATTERN_WIDTH - tabStripWidth();
        return Math.max(MIN_TAB_GAP, Math.min(TAB_GAP, room));
    }

    /**
     * Width of the pattern block: grid, fluid column, arrow, result, gap, tab strip and, when it scrolls, the scroll bar.
     * With six tab columns it is wider than the window less its two margins and stays 4 px inside the window's edges.
     */
    public int patternBlockWidth() {
        return PATTERN_WIDTH + tabGap() + tabStripWidth();
    }

    /** Left edge of the pattern block, centred in the window. */
    public int patternsX() {
        return (WIDTH - patternBlockWidth()) / 2;
    }

    /** X of a pattern cell: {@code 0..8} are the grid, {@value #RESULT_CELL} is the result. */
    public int patternCellX(int cell) {
        if (cell == RESULT_CELL)
            return arrowX() + ARROW_WIDTH;
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

    /**
     * X of fluid cell {@code cell} (0 or 1, issue #55, ADR-089): a column of its own between the grid and the arrow, so a
     * fluid reads as an ingredient (issue #62). The column is inside the grid's height, so a fluid cell costs the window
     * no height and every combination the configuration allows still fits the 320 x {@value #MAX_HEIGHT} floor.
     */
    public int fluidCellX(int cell) {
        return patternsX() + GRID_WIDTH * SLOT + FLUID_GAP;
    }

    /** Y of fluid cell {@code cell}: 0 above 1, the two centred beside the grid, off its rows. */
    public int fluidCellY(int cell) {
        int top = patternsY() + (PATTERN_BLOCK_HEIGHT - FLUID_COLUMN_HEIGHT) / 2;
        return cell <= 0 ? top : top + SLOT + FLUID_SPACING;
    }

    /** X of the arrow between the ingredients and the result cell. */
    public int arrowX() {
        return patternsX() + GRID_WIDTH * SLOT + FLUID_GAP + SLOT;
    }

    /** Left edge of the pattern tab strip. */
    public int tabsX() {
        return patternsX() + PATTERN_WIDTH + tabGap();
    }

    /** Row of the pattern tab of slot {@code pattern}; tabs run row by row. */
    public int tabRow(int pattern) {
        return Math.max(0, Math.min(pattern, patternSlots - 1)) / tabColumns;
    }

    /** X of the pattern tab of slot {@code pattern}. */
    public int tabX(int pattern) {
        return tabsX() + Math.max(0, Math.min(pattern, patternSlots - 1)) % tabColumns * SLOT;
    }

    /**
     * Y of the pattern tab of slot {@code pattern} with the strip scrolled down by {@code scroll} rows. Only meaningful
     * while the tab is {@linkplain #tabVisible visible}.
     */
    public int tabY(int pattern, int scroll) {
        return patternsY() + (tabRow(pattern) - clampTabScroll(scroll)) * SLOT;
    }

    /** Whether the tab of slot {@code pattern} is shown with the strip scrolled down by {@code scroll} rows. */
    public boolean tabVisible(int pattern, int scroll) {
        if (pattern < 0 || pattern >= patternSlots)
            return false;
        int first = clampTabScroll(scroll);
        int row = tabRow(pattern);
        return row >= first && row < first + visibleTabRows();
    }

    /** Left edge of the tab strip's rectangle (the mouse wheel's target). */
    public int tabStripX() {
        return tabsX();
    }

    /** Top of the tab strip's rectangle. */
    public int tabStripY() {
        return patternsY();
    }

    /** Width of the tab strip including its scroll bar when it has one. */
    public int tabStripWidth() {
        return tabColumns * SLOT + (scrolls() ? SCROLLBAR_GAP + SCROLLBAR_WIDTH : 0);
    }

    /** Height of the tab strip: its visible rows. */
    public int tabStripHeight() {
        return visibleTabRows() * SLOT;
    }

    /** Left edge of the scroll bar (only drawn when the strip {@link #scrolls()}). */
    public int scrollbarX() {
        return tabsX() + tabColumns * SLOT + SCROLLBAR_GAP;
    }

    /** Top of the scroll bar's track, which runs the pattern block's full height. */
    public int scrollbarY() {
        return patternsY();
    }

    /** Height of the scroll bar's track. */
    public int scrollbarHeight() {
        return PATTERN_BLOCK_HEIGHT;
    }

    /** Height of the scroll bar's thumb: the visible share of the rows, at least {@value #MIN_THUMB_HEIGHT}. */
    public int thumbHeight() {
        return Math.max(MIN_THUMB_HEIGHT, scrollbarHeight() * visibleTabRows() / tabRows);
    }

    /** Top of the scroll bar's thumb at scroll value {@code scroll}. */
    public int thumbY(int scroll) {
        int max = maxTabScroll();
        if (max <= 0)
            return scrollbarY();
        return scrollbarY() + clampTabScroll(scroll) * (scrollbarHeight() - thumbHeight()) / max;
    }

    /** Y on the scroll bar's track that stands for tab row {@code row} (where a hidden red row is marked). */
    public int scrollbarRowY(int row) {
        return scrollbarY() + Math.max(0, Math.min(row, tabRows - 1)) * scrollbarHeight() / tabRows;
    }

    /** Height of the part of the track that stands for one tab row. */
    public int scrollbarRowHeight() {
        return Math.max(1, scrollbarHeight() / tabRows);
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

    /**
     * Top of the <b>stopped row</b>, the screen's own way back from the safety stop (M20): the first order line, or the
     * <b>buffer's label row</b> when the window has no order line at all.
     * <p>
     * A station always has a row for it. With a buffer of 25 slots or more {@link #orderLines()} is 0 — the height budget
     * spent every line on buffer rows — and dropping the row there left the screen naming a problem, offering no way out of
     * it and contradicting the block's own description, which teaches the red row (review fix). Taking the buffer's label
     * is the cheaper trade by far: it is a word for slots that are self-evident, and only while something is really
     * stopped.
     */
    public int stoppedRowY() {
        return orderLines > 0 ? orderLineY(0) : bufferLabelY();
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
