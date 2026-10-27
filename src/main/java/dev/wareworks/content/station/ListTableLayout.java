package dev.wareworks.content.station;

/**
 * The geometry of the clipboard list question's table in the warehouse terminal (issue #36, ADR-069,
 * {@code docs/warehouse-system.md} §3.4.4): its columns, how many rows fit, where the panel sits and how it scrolls.
 * <p>
 * Pure arithmetic beside {@link TerminalMenuLayout}, with no client import, so the JUnit test can hold it to the
 * smallest window every buffer size allows and to the English and German column widths. The client measures the texts
 * ({@code client.gui.TerminalListTable}) and hands the widths in; nothing here knows a font.
 * <p>
 * <b>Columns</b>, in the panel's inner coordinates: the icon at 0 ({@value #ICON} + {@value #ICON_GAP}), the name from
 * {@link #nameX()}, the wanted amount right-aligned at {@link #wantedRight()}, the status word from {@link #statusX()},
 * and the scroll bar to the right of {@value #CONTENT_WIDTH}. A row is two text lines: the name, the amount and the
 * coloured status word, then a grey detail line from the name's column to the end of the status column.
 * <p>
 * <b>Rows scroll whole</b>: the table shows {@link #visibleRows()} complete rows from a scroll row on, so nothing is
 * ever cut and no scissor is needed. One wheel notch is one row.
 */
public final class ListTableLayout {
    /** Height of one row: two lines of the font (9 px) with 2 px between and below them. */
    public static final int ROW_HEIGHT = 20;
    /** Height of the header row above the entries. */
    public static final int HEADER_HEIGHT = 11;
    /** Height of one line of text above the table: the question, the summary and the notices. */
    public static final int TEXT_ROW = 11;
    public static final int ICON = 16;
    public static final int ICON_GAP = 2;
    public static final int COLUMN_GAP = 4;
    public static final int SCROLLBAR_WIDTH = 4;
    public static final int SCROLLBAR_GAP = 2;
    /** The narrowest the name column may become, in either language. */
    public static final int MIN_NAME_WIDTH = 40;
    /** The widest the amount column becomes; a wider amount is shortened and named in full in the tooltip. */
    public static final int MAX_WANTED_WIDTH = 40;
    /** The fewest rows the table may show at the smallest window any buffer size allows. */
    public static final int MIN_VISIBLE_ROWS = 3;
    public static final int GAP_ABOVE_TABLE = 2;
    public static final int GAP_BELOW_TABLE = 4;
    /** The panel's padding, the same as the other questions' panel ({@code WarehouseTerminalScreen}). */
    public static final int PADDING = 6;
    /** Height of the Confirm and Cancel buttons at the bottom. */
    public static final int BUTTON_HEIGHT = 14;
    /** The shortest a scroll bar's thumb is drawn. */
    public static final int MIN_THUMB = 8;

    /** Outer width of the panel: the terminal window without its margins. */
    public static final int PANEL_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /** Width inside the panel's padding: what the text above the table may use. */
    public static final int INNER_WIDTH = PANEL_WIDTH - 2 * PADDING;
    /** Width of the columns: the inner width without the scroll bar. */
    public static final int CONTENT_WIDTH = INNER_WIDTH - SCROLLBAR_WIDTH - SCROLLBAR_GAP;

    private final int nameWidth;
    private final int wantedWidth;
    private final int statusWidth;
    private final int textRowsAbove;
    private final int rowCount;
    private final int visibleRows;
    private final int height;
    private final int offsetY;

    private ListTableLayout(int nameWidth, int wantedWidth, int statusWidth, int textRowsAbove, int rowCount,
            int visibleRows, int height, int offsetY) {
        this.nameWidth = nameWidth;
        this.wantedWidth = wantedWidth;
        this.statusWidth = statusWidth;
        this.textRowsAbove = textRowsAbove;
        this.rowCount = rowCount;
        this.visibleRows = visibleRows;
        this.height = height;
        this.offsetY = offsetY;
    }

    /**
     * The table for the measured widths, in a window {@code layoutHeight} tall.
     *
     * @param wantedHeaderWidth width of the amount column's header
     * @param widestWanted      widest amount of any row
     * @param widestStatus      widest status word
     * @param statusHeaderWidth width of the status column's header
     * @param layoutHeight      height of the terminal window ({@link TerminalMenuLayout#height()})
     * @param textRowsAbove     lines of text above the table: the question (it may wrap), the summary, the notices
     * @param rowCount          rows of the table's content: the entries, and the Missing section with its rows
     */
    public static ListTableLayout of(int wantedHeaderWidth, int widestWanted, int widestStatus, int statusHeaderWidth,
            int layoutHeight, int textRowsAbove, int rowCount) {
        int wanted = Math.min(MAX_WANTED_WIDTH, Math.max(0, Math.max(wantedHeaderWidth, widestWanted)));
        int status = Math.max(0, Math.max(widestStatus, statusHeaderWidth));
        int name = nameRoom(wanted, status);
        if (name < MIN_NAME_WIDTH) {
            // The status words give way, never the name: they are shortened and stay readable by their colour.
            status = Math.max(0, status - (MIN_NAME_WIDTH - name));
            name = nameRoom(wanted, status);
        }
        int above = Math.max(0, textRowsAbove);
        int rows = Math.max(0, rowCount);
        int fixed = fixedHeight(above);
        int room = (layoutHeight - 2 * TerminalMenuLayout.MARGIN - fixed) / ROW_HEIGHT;
        int visible = Math.min(rows, Math.max(1, room));
        int height = fixed + visible * ROW_HEIGHT;
        int offsetY = Math.max(TerminalMenuLayout.MARGIN, (layoutHeight - height) / 2);
        return new ListTableLayout(name, wanted, status, above, rows, visible, height, offsetY);
    }

    private static int nameRoom(int wanted, int status) {
        return CONTENT_WIDTH - nameXOf() - COLUMN_GAP - wanted - COLUMN_GAP - status;
    }

    private static int nameXOf() {
        return ICON + ICON_GAP;
    }

    /** Everything of the panel's height but the rows: padding, text, header, the gaps and the buttons. */
    public static int fixedHeight(int textRowsAbove) {
        return 2 * PADDING + textRowsAbove * TEXT_ROW + GAP_ABOVE_TABLE + HEADER_HEIGHT + GAP_BELOW_TABLE
                + BUTTON_HEIGHT;
    }

    // --- columns (inner x, i.e. from the panel's left edge plus its padding) --------------------------------------

    public int iconX() {
        return 0;
    }

    public int nameX() {
        return nameXOf();
    }

    public int nameWidth() {
        return nameWidth;
    }

    /** Right edge of the amount column: amounts are drawn right-aligned to it. */
    public int wantedRight() {
        return nameX() + nameWidth + COLUMN_GAP + wantedWidth;
    }

    public int wantedWidth() {
        return wantedWidth;
    }

    public int statusX() {
        return wantedRight() + COLUMN_GAP;
    }

    public int statusWidth() {
        return statusWidth;
    }

    /** Where the grey detail line starts: under the name. */
    public int detailX() {
        return nameX();
    }

    /** How wide the detail line may be: from the name's column to the end of the status column. */
    public int detailWidth() {
        return statusX() + statusWidth - detailX();
    }

    public int scrollbarX() {
        return CONTENT_WIDTH + SCROLLBAR_GAP;
    }

    // --- rows -----------------------------------------------------------------------------------------------------

    public int rowCount() {
        return rowCount;
    }

    public int visibleRows() {
        return visibleRows;
    }

    public int textRowsAbove() {
        return textRowsAbove;
    }

    /** Outer height of the whole panel. */
    public int height() {
        return height;
    }

    /** The panel's top edge below the window's top edge. */
    public int offsetY() {
        return offsetY;
    }

    /** Top of the header row, below the panel's top edge. */
    public int headerY() {
        return PADDING + textRowsAbove * TEXT_ROW + GAP_ABOVE_TABLE;
    }

    /** Top of the first visible row, below the panel's top edge. */
    public int rowsY() {
        return headerY() + HEADER_HEIGHT;
    }

    /** Height of the rows' area, which is also the scroll bar's track. */
    public int rowsHeight() {
        return visibleRows * ROW_HEIGHT;
    }

    /** Top of the buttons, below the panel's top edge. */
    public int buttonY() {
        return height - PADDING - BUTTON_HEIGHT;
    }

    // --- scrolling ------------------------------------------------------------------------------------------------

    /** The last row the table can be scrolled to; 0 when every row fits. */
    public int maxScroll() {
        return Math.max(0, rowCount - visibleRows);
    }

    public int clampScroll(int scroll) {
        return Math.max(0, Math.min(maxScroll(), scroll));
    }

    /** The scroll row after one wheel event: one row per notch, down ({@code wheel < 0}) to later rows. */
    public int scrollBy(int scroll, double wheel) {
        return clampScroll(scroll - (int) Math.signum(wheel));
    }

    /**
     * The content row under {@code y} (below the panel's top edge) at scroll row {@code scroll}, or -1 when {@code y}
     * is above or below the rows.
     */
    public int rowAt(int scroll, int y) {
        int inside = y - rowsY();
        if (inside < 0 || inside >= rowsHeight())
            return -1;
        int row = clampScroll(scroll) + inside / ROW_HEIGHT;
        return row < rowCount ? row : -1;
    }

    /** Whether the table has more rows than it shows, i.e. whether the scroll bar is drawn. */
    public boolean scrolls() {
        return rowCount > visibleRows;
    }

    public int thumbHeight() {
        if (!scrolls())
            return rowsHeight();
        return Math.min(rowsHeight(), Math.max(MIN_THUMB, rowsHeight() * visibleRows / rowCount));
    }

    /** Top of the thumb, below the top of the track. */
    public int thumbY(int scroll) {
        int max = maxScroll();
        return max == 0 ? 0 : (rowsHeight() - thumbHeight()) * clampScroll(scroll) / max;
    }
}
