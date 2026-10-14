package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The warehouse terminal's window with its crafting panel folded out (issue #24, M32) has to fit vanilla's smallest
 * scaled screen at every buffer size the server config allows (1–27 slots), keep the panel's own pieces apart and
 * inside its frame, put the fold toggle where it covers nothing, and stay on the screen when it grows to the right.
 * <p>
 * The screen widths are the setups the design was measured against: the floor of 320 (1280 x 960 at scale 4), 427
 * (2560 x 1440 at 6), 480 (1920 x 1080 at 4), 534 (the visual harness, 1600 x 900 at 3), 640 (2560 x 1440 at 4) and
 * 854 (5120 x 1440 at 6).
 */
class TerminalMenuLayoutTest {
    /** The largest buffer {@code terminalBufferSlots} allows. */
    private static final int MAX_BUFFER = 27;
    private static final int[] SCREEN_WIDTHS = {320, 427, 480, 534, 640, 854};
    /** Where the window sits on those screens folded in; folded out it sits there too from 374 pixels on. */
    private static final int[] LEFT_POS = {45, 98, 125, 152, 205, 312};
    /** The narrowest screen on which the panel folds out without moving the window: 298 + 2 x (4 + 34). */
    private static final int NO_JUMP_WIDTH = 374;
    /** Width of the brass frame's sides ({@code WarehouseTerminalScreen#renderBrassFrame}). */
    private static final int FRAME = 3;
    /** Height of a line of the normal font ({@code Font#lineHeight}). */
    private static final int FONT_HEIGHT = 9;

    /** A rectangle in window coordinates, right and bottom exclusive. */
    private record Box(String name, int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean overlaps(Box other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }

        boolean inside(Box outer) {
            return x >= outer.x && y >= outer.y && right() <= outer.right() && bottom() <= outer.bottom();
        }
    }

    /** Everything the panel draws, each piece in the box it may use. */
    private static List<Box> panelElements(TerminalMenuLayout layout) {
        List<Box> elements = new ArrayList<>();
        elements.add(new Box("title", TerminalMenuLayout.PANEL_CONTENT_X, TerminalMenuLayout.PANEL_TITLE_Y,
                TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH, FONT_HEIGHT));
        for (int cell = 0; cell < TerminalMenuLayout.CRAFT_CELLS; cell++)
            elements.add(new Box("cell " + cell, layout.craftCellX(cell), layout.craftCellY(cell),
                    TerminalMenuLayout.SLOT, TerminalMenuLayout.SLOT));
        elements.add(new Box("arrow", layout.craftArrowX(), layout.craftArrowY(), TerminalMenuLayout.SLOT,
                TerminalMenuLayout.ARROW_HEIGHT));
        elements.add(new Box("result", layout.craftResultX(), layout.craftResultY(), TerminalMenuLayout.SLOT,
                TerminalMenuLayout.SLOT));
        // The ready count is centred under the result and may use the whole row for a long number.
        elements.add(new Box("ready", TerminalMenuLayout.PANEL_CONTENT_X, layout.craftReadyY(),
                TerminalMenuLayout.PANEL_CONTENT_WIDTH, TerminalMenuLayout.LABEL_HEIGHT));
        for (int button = 0; button < TerminalMenuLayout.CRAFT_BUTTONS; button++)
            elements.add(new Box("button " + button, layout.craftButtonX(button), layout.craftButtonY(),
                    TerminalMenuLayout.SLOT, TerminalMenuLayout.SLOT));
        elements.add(new Box("progress bar", TerminalMenuLayout.PANEL_CONTENT_X, layout.craftProgressY(),
                TerminalMenuLayout.PANEL_CONTENT_WIDTH, TerminalMenuLayout.PROGRESS_HEIGHT));
        elements.add(new Box("progress numbers", TerminalMenuLayout.PANEL_CONTENT_X, layout.craftProgressTextY(),
                TerminalMenuLayout.PANEL_CONTENT_WIDTH, TerminalMenuLayout.LABEL_HEIGHT));
        return elements;
    }

    /**
     * Every slot the menu has, as the screen draws it: one pixel above and left of {@code Slot#x}/{@code Slot#y}, the
     * player inventory laid out the way {@code MenuBase#addPlayerSlots} does.
     */
    private static List<Box> slots(TerminalMenuLayout layout) {
        List<Box> slots = new ArrayList<>();
        for (int slot = 0; slot < layout.bufferSlots(); slot++)
            slots.add(cellAt("buffer slot " + slot, layout.bufferSlotX(slot), layout.bufferSlotY(slot)));
        slots.add(cellAt("list slot", layout.listSlotX(), layout.listSlotY()));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < TerminalMenuLayout.PLAYER_COLUMNS; column++)
                slots.add(cellAt("inventory slot " + row + "/" + column,
                        layout.playerSlotsX() + column * TerminalMenuLayout.SLOT,
                        layout.playerSlotsY() + row * TerminalMenuLayout.SLOT));
        }
        for (int column = 0; column < TerminalMenuLayout.PLAYER_COLUMNS; column++)
            slots.add(cellAt("hotbar slot " + column, layout.playerSlotsX() + column * TerminalMenuLayout.SLOT,
                    layout.playerSlotsY() + TerminalMenuLayout.HOTBAR_OFFSET));
        return slots;
    }

    private static Box cellAt(String name, int slotX, int slotY) {
        return new Box(name, slotX - 1, slotY - 1, TerminalMenuLayout.SLOT, TerminalMenuLayout.SLOT);
    }

    /** The window's rows that are not slots: each spans the whole window, so nothing may sit beside it either. */
    private static List<Box> rows(TerminalMenuLayout layout) {
        List<Box> rows = new ArrayList<>();
        rows.add(row("title", 0, TerminalMenuLayout.TITLE_HEIGHT));
        rows.add(row("search", layout.searchY(), TerminalMenuLayout.SEARCH_HEIGHT));
        rows.add(row("stock grid and its scrollbar", layout.gridY(), layout.gridHeight()));
        rows.add(row("buffer label", layout.bufferLabelY(), TerminalMenuLayout.LABEL_HEIGHT));
        rows.add(row("status line", layout.statusY(), TerminalMenuLayout.LABEL_HEIGHT));
        if (layout.orderLines() > 0) {
            rows.add(row("production label", layout.productionLabelY(), TerminalMenuLayout.LABEL_HEIGHT));
            for (int line = 0; line < layout.orderLines(); line++)
                rows.add(row("order line " + line, layout.orderLineY(line), TerminalMenuLayout.LABEL_HEIGHT));
        }
        rows.add(row("player inventory label", layout.playerLabelY(), TerminalMenuLayout.LABEL_HEIGHT));
        return rows;
    }

    private static Box row(String name, int y, int height) {
        return new Box(name, 0, y, TerminalMenuLayout.WIDTH, height);
    }

    private static Box toggle(TerminalMenuLayout layout) {
        return new Box("toggle", layout.craftToggleX(), layout.craftToggleY(), TerminalMenuLayout.SLOT,
                TerminalMenuLayout.SLOT);
    }

    // --- size --------------------------------------------------------------------------------------------------------

    /** Folded out, the window is still no wider than the floor and exactly as tall as folded in. */
    @Test
    void theFoldedWindowFitsTheSmallestScreen() {
        assertEquals(298, TerminalMenuLayout.FOLDED_WIDTH);
        assertEquals(68, TerminalMenuLayout.PANEL_WIDTH);
        assertTrue(TerminalMenuLayout.FOLDED_WIDTH <= TerminalMenuLayout.MIN_SCREEN_WIDTH);
        for (int buffer = 1; buffer <= MAX_BUFFER; buffer++) {
            TerminalMenuLayout layout = new TerminalMenuLayout(buffer);
            assertTrue(layout.height() <= TerminalMenuLayout.MAX_HEIGHT, "buffer " + buffer + ": " + layout.height());
            assertEquals(layout.height(), layout.panelHeight(), "buffer " + buffer + ": the frames line up");
            assertEquals(buffer <= 22 ? 234 : 238, layout.height(), "buffer " + buffer);
        }
    }

    // --- the panel ---------------------------------------------------------------------------------------------------

    /**
     * Every piece of the panel lies right of the window, inside the panel's own frame (the title shares the frame's
     * top edge exactly like the window's title does) and clear of every other piece.
     */
    @Test
    void everyPanelElementIsInsideThePanelAndOverlapsNoOther() {
        for (int buffer = 1; buffer <= MAX_BUFFER; buffer++) {
            TerminalMenuLayout layout = new TerminalMenuLayout(buffer);
            Box panel = new Box("panel", TerminalMenuLayout.PANEL_X, 0, TerminalMenuLayout.PANEL_WIDTH,
                    layout.panelHeight());
            Box inner = new Box("inside the frame", panel.x() + FRAME, FRAME, panel.width() - 2 * FRAME,
                    panel.height() - 2 * FRAME);
            Box content = new Box("between the margins", TerminalMenuLayout.PANEL_CONTENT_X, 0,
                    TerminalMenuLayout.PANEL_CONTENT_WIDTH, panel.height() - TerminalMenuLayout.BOTTOM_MARGIN);
            List<Box> elements = panelElements(layout);
            for (Box element : elements) {
                String where = "buffer " + buffer + ", " + element;
                assertTrue(element.inside(panel), where + " is inside the panel");
                assertTrue(element.inside(content), where + " keeps the panel's margins");
                assertTrue(element.name().equals("title") || element.inside(inner), where + " is inside the frame");
                assertTrue(element.x() >= TerminalMenuLayout.WIDTH, where + " is right of the window");
            }
            for (int i = 0; i < elements.size(); i++) {
                for (int j = i + 1; j < elements.size(); j++)
                    assertFalse(elements.get(i).overlaps(elements.get(j)),
                            "buffer " + buffer + ": " + elements.get(i) + " overlaps " + elements.get(j));
            }
        }
    }

    /** The numbers the screen, the harness and the design document share. */
    @Test
    void thePanelSitsWhereTheDesignPutsIt() {
        TerminalMenuLayout layout = new TerminalMenuLayout(9);
        assertEquals(230, TerminalMenuLayout.PANEL_X);
        assertEquals(237, layout.craftCellX(0));
        assertEquals(30, layout.craftCellY(0));
        assertEquals(273, layout.craftCellX(8));
        assertEquals(66, layout.craftCellY(8));
        assertEquals(255, layout.craftArrowX());
        assertEquals(86, layout.craftArrowY());
        assertEquals(255, layout.craftResultX());
        assertEquals(102, layout.craftResultY());
        assertEquals(264, layout.craftReadyCenterX());
        assertEquals(122, layout.craftReadyY());
        assertEquals(132, layout.craftButtonY());
        assertEquals(237, layout.craftButtonX(TerminalMenuLayout.FETCH_BUTTON));
        assertEquals(255, layout.craftButtonX(TerminalMenuLayout.FREE_BUTTON));
        assertEquals(273, layout.craftButtonX(TerminalMenuLayout.CLEAR_BUTTON));
        assertEquals(154, layout.craftProgressY());
        assertEquals(158, layout.craftProgressTextY());
        assertEquals(54, TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH);
        assertEquals(TerminalMenuLayout.PANEL_X + TerminalMenuLayout.PANEL_WIDTH - TerminalMenuLayout.MARGIN,
                layout.craftCellX(8) + TerminalMenuLayout.SLOT, "the panel's margins are the window's");
    }

    /** The panel's grid rows are the stock grid's rows, at every buffer size, so the two read as one line. */
    @Test
    void theGridIsLevelWithTheStockGrid() {
        for (int buffer = 1; buffer <= MAX_BUFFER; buffer++) {
            TerminalMenuLayout layout = new TerminalMenuLayout(buffer);
            for (int row = 0; row < TerminalMenuLayout.CRAFT_GRID_WIDTH; row++)
                assertEquals(layout.gridY() + row * TerminalMenuLayout.SLOT,
                        layout.craftCellY(row * TerminalMenuLayout.CRAFT_GRID_WIDTH), "buffer " + buffer);
        }
    }

    // --- the toggle --------------------------------------------------------------------------------------------------

    /**
     * The toggle is inside the window and its margins, in the free column right of the player inventory, level with
     * the inventory's first row of cells, and over no slot and no row of text.
     */
    @Test
    void theToggleSitsInTheWindowOverNoSlotOrLabelRow() {
        for (int buffer = 1; buffer <= MAX_BUFFER; buffer++) {
            TerminalMenuLayout layout = new TerminalMenuLayout(buffer);
            Box toggle = toggle(layout);
            String where = "buffer " + buffer + ": ";
            Box margins = new Box("window margins", TerminalMenuLayout.MARGIN, FRAME,
                    TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN, layout.height() - 2 * FRAME);
            assertTrue(toggle.inside(margins), where + toggle + " is inside the window");
            for (Box slot : slots(layout))
                assertFalse(toggle.overlaps(slot), where + "the toggle covers " + slot);
            for (Box row : rows(layout))
                assertFalse(toggle.overlaps(row), where + "the toggle covers " + row);
            assertEquals(201, toggle.x(), where + "the free column");
            int inventoryRight = layout.playerSlotsX() + TerminalMenuLayout.PLAYER_COLUMNS * TerminalMenuLayout.SLOT;
            assertTrue(toggle.x() >= inventoryRight, where + "right of the player inventory");
            assertEquals(layout.playerSlotsY() - 1, toggle.y(), where + "level with the first inventory row's cells");
        }
        assertEquals(152, new TerminalMenuLayout(9).craftToggleY());
        assertEquals(156, new TerminalMenuLayout(MAX_BUFFER).craftToggleY());
    }

    // --- the fold offset ---------------------------------------------------------------------------------------------

    /**
     * On every screen the window fits, the folded-out window keeps {@value TerminalMenuLayout#FOLD_MARGIN} pixels to
     * both edges, and its own part never moves right.
     */
    @Test
    void theFoldedWindowStaysOnTheScreen() {
        for (int screen = TerminalMenuLayout.FOLDED_WIDTH + 2 * TerminalMenuLayout.FOLD_MARGIN; screen <= 4000;
                screen++) {
            int offset = TerminalMenuLayout.foldOffset(screen);
            int left = folded(screen);
            assertTrue(offset >= 0 && offset <= TerminalMenuLayout.MAX_FOLD_OFFSET, screen + ": offset " + offset);
            assertTrue(left >= TerminalMenuLayout.FOLD_MARGIN, screen + ": left edge at " + left);
            assertTrue(left + TerminalMenuLayout.FOLDED_WIDTH <= screen - TerminalMenuLayout.FOLD_MARGIN,
                    screen + ": right edge at " + (left + TerminalMenuLayout.FOLDED_WIDTH));
            assertTrue(left <= unfolded(screen), screen + ": the window's own part never moves right");
        }
    }

    /** From 374 pixels on the panel appears and nothing jumps; 373 is the widest screen where the window moves. */
    @Test
    void nothingJumpsFrom374PixelsOn() {
        assertEquals(NO_JUMP_WIDTH, TerminalMenuLayout.FOLDED_WIDTH
                + 2 * (TerminalMenuLayout.FOLD_MARGIN + TerminalMenuLayout.MAX_FOLD_OFFSET));
        for (int screen = NO_JUMP_WIDTH; screen <= 4000; screen++)
            assertEquals(unfolded(screen), folded(screen), screen + " pixels");
        assertNotEquals(unfolded(NO_JUMP_WIDTH - 1), folded(NO_JUMP_WIDTH - 1));
    }

    /** The setups of the design's table, folded in and out. */
    @Test
    void theMeasuredScreens() {
        for (int i = 0; i < SCREEN_WIDTHS.length; i++) {
            int screen = SCREEN_WIDTHS[i];
            assertEquals(LEFT_POS[i], unfolded(screen), screen + " folded in");
            int expected = screen < NO_JUMP_WIDTH ? 18 : LEFT_POS[i];
            assertEquals(expected, folded(screen), screen + " folded out");
            assertTrue(folded(screen) + TerminalMenuLayout.FOLDED_WIDTH <= screen - TerminalMenuLayout.FOLD_MARGIN);
        }
        assertEquals(316, folded(320) + TerminalMenuLayout.FOLDED_WIDTH, "the floor keeps exactly the margin");
        assertEquals(0, TerminalMenuLayout.foldOffset(300), "too narrow to fit: simply centred");
    }

    /** Vanilla's {@code leftPos} for the folded-in window. */
    private static int unfolded(int screen) {
        return (screen - TerminalMenuLayout.WIDTH) / 2;
    }

    /** Vanilla's {@code leftPos} for the folded-out window plus the offset Create's screen adds. */
    private static int folded(int screen) {
        return (screen - TerminalMenuLayout.FOLDED_WIDTH) / 2 + TerminalMenuLayout.foldOffset(screen);
    }
}
