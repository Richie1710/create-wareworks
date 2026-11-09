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
 * Keeps every text of the filter cells of production patterns (ADR-082) inside the room it is drawn in, in English and
 * in German, with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * The texts are lines of the station screen's tooltips (a filter cell's, an empty cell's, an order line's, also at the
 * terminal) and the hotbar line of a refused edit. Tooltip lines are never wrapped, so each has to fit
 * {@value #TOOLTIP_ROOM} px, what a tooltip has at the 320 x 240 floor of the GUI (a 1280 x 960 window at GUI scale 4)
 * beside the cursor; the hotbar line has the whole {@value #FLOOR_WIDTH} px. A line that names an item is measured with
 * a long plank name and the widest amount.
 * <p>
 * Every key under {@code gui.production.filter.} and {@code gui.production.order.} has to be measured here
 * ({@link #everyFilterKeyIsMeasured}), so a new one cannot slip past.
 */
class ProductionFilterTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.";
    /** The scaled width of the GUI at its floor: 320 x 240. */
    private static final int FLOOR_WIDTH = 320;
    /** What a tooltip line may take at that floor. */
    private static final int TOOLTIP_ROOM = 300;
    /** Long plank names as an item a line names: the German one is the longest vanilla plank. */
    private static final Map<String, String> LONG_NAME = Map.of("en_us", "Dark Oak Planks", "de_de",
            "Schwarzeichenholzbretter");
    /** The widest amount a match or a use names. */
    private static final String WIDEST_AMOUNT = "99,999";

    /** Tooltip lines without arguments. */
    private static final List<String> PLAIN_LINES = List.of("gui.production.filter.cell",
            "gui.production.filter.matches", "gui.production.filter.none", "gui.production.filter.short",
            "gui.production.filter.deny", "gui.production.filter.crafter", "gui.production.filter.hint");
    /** Tooltip lines whose one argument is an amount. */
    private static final List<String> AMOUNT_LINES = List.of("gui.production.filter.more",
            "gui.production.order.uses_more");
    /** {@code "- %1$s x%2$s"}: a match, the name and the amount. */
    private static final String MATCH = "gui.production.filter.match";
    /** {@code "Uses %1$s"}: the argument is "name xN" ({@code gui.goggles.item_count}). */
    private static final String USES = "gui.production.order.uses";
    private static final String ITEM_COUNT = "gui.goggles.item_count";
    /** The hotbar lines of a refused edit. */
    private static final List<String> HOTBAR_LINES = List.of("gui.production.filter.too_large",
            "gui.production.filter.makes_result");

    @Test
    void everyFilterTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : PLAIN_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), TOOLTIP_ROOM);
            for (String key : AMOUNT_LINES) {
                String line = text(lang, name, key).replace("%1$s", WIDEST_AMOUNT);
                check(tooWide, name, key, ItemTooltipLengthTest.width(line, advances), TOOLTIP_ROOM);
            }
            String match = text(lang, name, MATCH).replace("%1$s", LONG_NAME.get(name))
                    .replace("%2$s", WIDEST_AMOUNT);
            check(tooWide, name, MATCH, ItemTooltipLengthTest.width(match, advances), TOOLTIP_ROOM);
            String item = text(lang, name, ITEM_COUNT).replace("%1$s", LONG_NAME.get(name))
                    .replace("%2$s", WIDEST_AMOUNT);
            String uses = text(lang, name, USES).replace("%1$s", item);
            check(tooWide, name, USES, ItemTooltipLengthTest.width(uses, advances), TOOLTIP_ROOM);
            for (String key : HOTBAR_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), FLOOR_WIDTH);
        }
        if (!tooWide.isEmpty())
            fail("These filter cell texts do not fit where they are drawn; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every filter and order-use key of the generated lang is measured above: a new one has to be added here first. */
    @Test
    void everyFilterKeyIsMeasured() throws IOException {
        Set<String> own = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (!key.startsWith(PREFIX))
                continue;
            String relative = key.substring(PREFIX.length());
            if (relative.startsWith("gui.production.filter.") || relative.startsWith("gui.production.order."))
                own.add(relative);
        }
        assertTrue(!own.isEmpty(), "no filter keys found; run ./gradlew runData");
        Set<String> measured = new TreeSet<>(PLAIN_LINES);
        measured.addAll(AMOUNT_LINES);
        measured.add(MATCH);
        measured.add(USES);
        measured.addAll(HOTBAR_LINES);
        assertEquals(own, measured, "every filter cell text must be measured by the test above");
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
