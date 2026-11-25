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

/**
 * Keeps every text of the fluid cells of production patterns (issue #55, ADR-089) inside the room it is drawn in, in
 * English and in German, with the real advance of every glyph of Minecraft 1.21.1's default font
 * ({@link ItemTooltipLengthTest}).
 * <p>
 * The texts are lines of the station screen's tooltips (a fluid cell's and an empty one's) and the hotbar line of a
 * refused edit. Tooltip lines are never wrapped, so each has to fit {@value #TOOLTIP_ROOM} px, what a tooltip has at the
 * 320 x 240 floor of the GUI beside the cursor; the hotbar line has the whole {@value #FLOOR_WIDTH} px. Every key under
 * {@code gui.production.fluid.} has to be measured here ({@link #everyFluidKeyIsMeasured}), so a new one cannot slip
 * past.
 */
class ProductionFluidTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.";
    /** The scaled width of the GUI at its floor: 320 x 240. */
    private static final int FLOOR_WIDTH = 320;
    /** What a tooltip line may take at that floor. */
    private static final int TOOLTIP_ROOM = 300;
    /** The widest amount a line names: 1,000 mB. */
    private static final String WIDEST_AMOUNT = "1,000";

    /** Tooltip lines without arguments. */
    private static final List<String> PLAIN_LINES = List.of("gui.production.fluid.cell",
            "gui.production.fluid.whole", "gui.production.fluid.rest", "gui.production.fluid.set_hint",
            "gui.production.fluid.wheel_hint", "gui.production.fluid.clear_hint", "gui.production.fluid.unknown",
            "gui.production.fluid.paid_twice", "gui.production.fluid.also_in_grid",
            "gui.production.fluid.twice_hint");
    /** The tooltip lines of a pattern without a result (issue #62): the result cell, the arrow and the tab. */
    private static final List<String> NO_RESULT_LINES = List.of("gui.production.no_result.reason",
            "gui.production.no_result.hint");
    /** Tooltip lines whose one argument is an amount. */
    private static final List<String> AMOUNT_LINES = List.of("gui.production.fluid.amount",
            "gui.production.fluid.buckets");
    /** The hotbar line of a refused edit. */
    private static final List<String> HOTBAR_LINES = List.of("gui.production.fluid.same");

    @Test
    void everyFluidTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : NO_RESULT_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), TOOLTIP_ROOM);
            for (String key : PLAIN_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), TOOLTIP_ROOM);
            for (String key : AMOUNT_LINES) {
                String line = text(lang, name, key).replace("%1$s", WIDEST_AMOUNT);
                check(tooWide, name, key, ItemTooltipLengthTest.width(line, advances), TOOLTIP_ROOM);
            }
            for (String key : HOTBAR_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), FLOOR_WIDTH);
        }
        if (!tooWide.isEmpty())
            fail("These fluid cell texts do not fit where they are drawn; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every fluid key of the generated lang is measured above: a new one has to be added here first. */
    @Test
    void everyFluidKeyIsMeasured() throws IOException {
        Set<String> own = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (!key.startsWith(PREFIX))
                continue;
            String relative = key.substring(PREFIX.length());
            if (relative.startsWith("gui.production.fluid."))
                own.add(relative);
        }
        assertTrue(!own.isEmpty(), "no fluid keys found; run ./gradlew runData");
        Set<String> measured = new TreeSet<>(PLAIN_LINES);
        measured.addAll(AMOUNT_LINES);
        measured.addAll(HOTBAR_LINES);
        assertEquals(own, measured, "every fluid cell text must be measured by the test above");
        Set<String> noResult = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(PREFIX + "gui.production.no_result."))
                noResult.add(key.substring(PREFIX.length()));
        }
        assertEquals(noResult, new TreeSet<>(NO_RESULT_LINES), "and every line about a missing result");
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
