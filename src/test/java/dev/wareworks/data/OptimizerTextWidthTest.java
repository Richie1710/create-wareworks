package dev.wareworks.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.station.OptimizerMenuLayout;
import dev.wareworks.core.optimizer.OptimizerLimits;

/**
 * Keeps every text of the Warehouse Optimizer (issue #46, ADR-079) inside the room it is drawn in, in English and in
 * German, with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * The texts live in four places:
 * <ul>
 * <li>the <b>column header</b>: "Items" at the left, and the three short headers right-aligned in their number fields,
 * each of which has to stay inside its own field;</li>
 * <li>the <b>status row</b> between the list cell and the Optimise button, which is cut with an ellipsis rather than
 * wrapped: every sentence of it has to fit whole, measured with the widest numbers it can carry, and the running
 * order's line beside the cancel mark;</li>
 * <li>the <b>tooltips</b> of the rows, the numbers, the pips, the list cell, the button and the cancel mark, held to the
 * window's inner width so a tooltip over the window stays inside it;</li>
 * <li>the <b>goggle rows</b>, which are never wrapped: the room a goggle row really has in the 1600x900 window of the
 * visual runs ({@code LangConsistencyTest}).</li>
 * </ul>
 * Every key under {@code gui.optimizer.} and every optimizer goggle key has to be measured here
 * ({@link #everyOptimizerKeyIsMeasured}), so a new one cannot slip past.
 */
class OptimizerTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.";

    /** The window without its two margins: where a tooltip over the window stays inside it. */
    private static final int TOOLTIP_WIDTH = OptimizerMenuLayout.WIDTH - 2 * OptimizerMenuLayout.MARGIN;
    /** A short column header may take its field up to the padding, and keeps one pixel from the field before it. */
    private static final int HEADER_WIDTH = OptimizerMenuLayout.NUMBER_WIDTH - OptimizerMenuLayout.NUMBER_PADDING - 1;
    /** A goggle row's room ({@code LangConsistencyTest#GOGGLE_ROW_PIXELS}, less one indent). */
    private static final int GOGGLE_ROW = 510 - 24;

    /**
     * The most a running order's line is shown with: well over what a crane moves in {@value OptimizerLimits#MAX_TRIPS}
     * trips of a stack each. A larger number is cut with an ellipsis rather than drawn over the cancel mark.
     */
    private static final String WIDEST_MOVED = "99,999";
    private static final String WIDEST_TRIPS = String.valueOf(OptimizerLimits.MAX_TRIPS);
    /** Rows added from one clipboard: at most every row. */
    private static final String WIDEST_ADDED = String.valueOf(OptimizerLimits.MAX_ROWS);
    /** Items of a clipboard that found no free row: a long material list. */
    private static final String WIDEST_LEFT_OUT = "999";
    private static final String WIDEST_COUNT = "999,999";
    private static final String WIDEST_PRIORITY = "9";

    private static final String ITEMS = "gui.optimizer.items";
    private static final List<String> HEADERS = List.of("gui.optimizer.best_short", "gui.optimizer.below_short",
            "gui.optimizer.movable_short");
    /** Status row sentences without a number. */
    private static final List<String> STATUS_PLAIN = List.of("gui.optimizer.hint", "gui.optimizer.no_warehouse",
            "gui.optimizer.message.all_listed", "gui.optimizer.message.clipboard_empty",
            "gui.optimizer.message.not_a_clipboard", "gui.optimizer.message.already_listed",
            "gui.optimizer.message.no_items", "gui.optimizer.message.too_many_orders");
    /** Status row sentences whose one number is what an order moved. */
    private static final List<String> STATUS_MOVED = List.of("gui.optimizer.finished", "gui.optimizer.out_of_trips",
            "gui.optimizer.cancelled");
    private static final String RUNNING = "gui.optimizer.running";
    private static final String ADDED = "gui.optimizer.message.added";
    private static final String ADDED_NO_ROOM = "gui.optimizer.message.added_no_room";
    /** Tooltip lines without a number. */
    private static final List<String> TOOLTIPS = List.of("gui.optimizer.empty_row", "gui.optimizer.best",
            "gui.optimizer.below", "gui.optimizer.movable", "gui.optimizer.best_hint", "gui.optimizer.below_hint",
            "gui.optimizer.movable_hint", "gui.optimizer.movable_reserved", "gui.optimizer.movable_unknown",
            "gui.optimizer.none_stored", "gui.optimizer.item_hint", "gui.optimizer.pip.idle",
            "gui.optimizer.pip.running", "gui.optimizer.pip.done", "gui.optimizer.pip.nothing_to_move",
            "gui.optimizer.pip_hint", "gui.optimizer.list", "gui.optimizer.list_hint", "gui.optimizer.optimise",
            "gui.optimizer.optimise_hint", "gui.optimizer.optimise_now", "gui.optimizer.cancel",
            "gui.optimizer.cancel_hint");
    private static final String PRIORITY = "gui.optimizer.priority";
    private static final String UNLOADED = "gui.optimizer.unloaded";
    private static final String MOVABLE_NOW = "gui.optimizer.movable_now";
    /** Goggle rows; their numbers are counts. */
    private static final List<String> GOGGLE_ROWS = List.of("gui.goggles.warehouse_optimizer",
            "gui.goggles.optimizer_items", "gui.goggles.optimizer_no_items", "gui.goggles.optimizer_no_warehouse",
            "gui.goggles.optimizer_running");

    @Test
    void theColumnHeaderFitsItsFields() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        OptimizerMenuLayout layout = new OptimizerMenuLayout();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            check(tooWide, name, ITEMS, ItemTooltipLengthTest.width(text(lang, name, ITEMS), advances),
                    layout.numbersX() - OptimizerMenuLayout.MARGIN - OptimizerMenuLayout.GAP);
            for (String key : HEADERS)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), HEADER_WIDTH);
        }
        if (!tooWide.isEmpty())
            fail("These optimizer headers do not fit their fields; shorten them:\n  " + String.join("\n  ", tooWide));
    }

    @Test
    void everyStatusLineFitsTheStatusRow() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        OptimizerMenuLayout layout = new OptimizerMenuLayout();
        int room = layout.statusTextWidth();
        assertTrue(room >= 150, "the status row lost its room: " + room);
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : STATUS_PLAIN)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), room);
            for (String key : STATUS_MOVED)
                check(tooWide, name, key, ItemTooltipLengthTest.width(
                        text(lang, name, key).replace("%1$s", WIDEST_MOVED), advances), room);
            check(tooWide, name, ADDED, ItemTooltipLengthTest.width(
                    text(lang, name, ADDED).replace("%1$s", WIDEST_ADDED), advances), room);
            check(tooWide, name, ADDED_NO_ROOM, ItemTooltipLengthTest.width(text(lang, name, ADDED_NO_ROOM)
                    .replace("%1$s", WIDEST_ADDED).replace("%2$s", WIDEST_LEFT_OUT), advances), room);
            // The running order's line shares the row with the cancel mark.
            check(tooWide, name, RUNNING, ItemTooltipLengthTest.width(text(lang, name, RUNNING)
                    .replace("%1$s", WIDEST_MOVED).replace("%2$s", WIDEST_TRIPS), advances),
                    layout.statusTextWidthWithCancel());
        }
        if (!tooWide.isEmpty())
            fail("These optimizer status lines do not fit the status row; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    @Test
    void everyTooltipLineStaysInsideTheWindow() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : TOOLTIPS)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances),
                        TOOLTIP_WIDTH);
            check(tooWide, name, PRIORITY, ItemTooltipLengthTest.width(text(lang, name, PRIORITY)
                    .replace("%1$s", WIDEST_PRIORITY).replace("%2$s", WIDEST_COUNT), advances), TOOLTIP_WIDTH);
            check(tooWide, name, UNLOADED, ItemTooltipLengthTest.width(
                    text(lang, name, UNLOADED).replace("%1$s", WIDEST_COUNT), advances), TOOLTIP_WIDTH);
            check(tooWide, name, MOVABLE_NOW, ItemTooltipLengthTest.width(
                    text(lang, name, MOVABLE_NOW).replace("%1$s", WIDEST_COUNT + "?"), advances), TOOLTIP_WIDTH);
        }
        if (!tooWide.isEmpty())
            fail("These optimizer tooltip lines are wider than the window; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    @Test
    void everyGoggleRowFits() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : GOGGLE_ROWS) {
                String row = text(lang, name, key).replace("%1$s", WIDEST_COUNT).replace("%2$s", WIDEST_TRIPS);
                check(tooWide, name, key, ItemTooltipLengthTest.width(row, advances), GOGGLE_ROW);
            }
        }
        if (!tooWide.isEmpty())
            fail("These optimizer goggle rows do not fit; shorten them:\n  " + String.join("\n  ", tooWide));
    }

    /** Every optimizer key of the generated lang is measured above: a new one has to be added here first. */
    @Test
    void everyOptimizerKeyIsMeasured() throws IOException {
        Set<String> own = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (!key.startsWith(PREFIX))
                continue;
            String relative = key.substring(PREFIX.length());
            if (relative.startsWith("gui.optimizer.") || relative.startsWith("gui.goggles.optimizer_")
                    || relative.equals("gui.goggles.warehouse_optimizer"))
                own.add(relative);
        }
        assertTrue(!own.isEmpty(), "no optimizer keys found; run ./gradlew runData");
        Set<String> measured = new TreeSet<>();
        measured.add(ITEMS);
        measured.addAll(HEADERS);
        measured.addAll(STATUS_PLAIN);
        measured.addAll(STATUS_MOVED);
        measured.add(RUNNING);
        measured.add(ADDED);
        measured.add(ADDED_NO_ROOM);
        measured.addAll(TOOLTIPS);
        measured.add(PRIORITY);
        measured.add(UNLOADED);
        measured.add(MOVABLE_NOW);
        measured.addAll(GOGGLE_ROWS);
        assertEquals(own, measured, "every Warehouse Optimizer text must be measured by the tests above");
    }

    /** The window keeps to the height every GUI scale guarantees, and shows six rows like the keeper's default. */
    @Test
    void theWindowFitsTheSmallestScreen() {
        OptimizerMenuLayout layout = new OptimizerMenuLayout();
        assertTrue(layout.height() <= OptimizerMenuLayout.MAX_HEIGHT, "window height " + layout.height());
        assertEquals(6, layout.visibleRows());
        assertTrue(layout.scrolls(), "16 rows do not all fit at once");
        assertEquals(OptimizerLimits.MAX_ROWS - layout.visibleRows(), layout.maxScroll());
        assertTrue(layout.cancelX() > layout.statusTextX(), "the cancel mark stands right of the status text");
        assertTrue(layout.statusTextX() + layout.statusTextWidth() < layout.optimiseX(), "the text ends before the button");
    }

    private static void check(List<String> tooWide, String language, String key, int width, int room) {
        if (width > room)
            tooWide.add(String.format("%s %s: %d px, the room is %d px", language, key, width, room));
    }

    private static String text(Map<String, String> lang, String language, String key) {
        String text = lang.get(PREFIX + key);
        if (text == null)
            fail("no " + language + " text for " + key + "; run ./gradlew runData");
        return text;
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }
}
