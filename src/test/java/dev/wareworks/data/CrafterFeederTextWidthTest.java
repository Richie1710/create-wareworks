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

import dev.wareworks.content.station.ProductionMenuLayout;

/**
 * Keeps every text of the Warehouse Crafter Feeder (issue #34, ADR-067) inside the room it is drawn in, in English and
 * in German, with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * The texts live in three places:
 * <ul>
 * <li>the <b>"Patterns" row</b> of the production station's screen, which carries the dim "Crafter Feeder" label at its
 * right end: label, gap and the row's own word have to fit the {@value #ROW_WIDTH} px row;</li>
 * <li>the <b>tooltips</b> of a refused pattern's tab and arrow: the reason and the consequence, held to the same row
 * width so a tooltip over the window stays inside it. The reason that names the recipe's real result is measured with
 * the name empty and the widest amount, and has to leave the name a floor of {@value #NAME_FLOOR} px;</li>
 * <li>the <b>goggle rows</b> of the feeder and of the station, which are never wrapped: the room a goggle row really has
 * in the 1600x900 window of the visual runs ({@code LangConsistencyTest}), measured with the longest vanilla item name
 * where a row names an item.</li>
 * </ul>
 * Every key under {@code gui.crafter_feeder.} and {@code gui.production.check.} has to be measured here
 * ({@link #everyFeederKeyIsMeasured}), so a new one cannot slip past.
 */
class CrafterFeederTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.";
    /** The screen's text row: the window without its two margins ({@code WarehouseProductionScreen#ROW_WIDTH}). */
    private static final int ROW_WIDTH = ProductionMenuLayout.WIDTH - 2 * ProductionMenuLayout.MARGIN;
    /** The gap the screen keeps between two texts that share a row ({@code WarehouseProductionScreen#TEXT_GAP}). */
    private static final int TEXT_GAP = 4;
    /** Room the "The recipe makes %1$s x%2$s" reason has to leave for the item's name. */
    private static final int NAME_FLOOR = 90;
    /** The widest amount a recipe makes: a stack. */
    private static final String WIDEST_AMOUNT = "64";
    /**
     * A goggle row's room: the scaled width of the 1600x900 window at GUI scale 3 less 24 px, less the 24 px of indent
     * a detail row spends ({@code LangConsistencyTest#GOGGLE_ROW_PIXELS}, {@code #GOGGLE_INDENT_PIXELS}).
     */
    private static final int GOGGLE_ROW = 510 - 24;
    /** The longest vanilla item name, as a stand-in for the item an order makes. */
    private static final String LONG_ITEM = "Waxed Weathered Cut Copper Stairs";
    private static final String WIDEST_COUNT = "999,999";

    /** The screen's row word and the label at its right end. */
    private static final String PATTERNS = "gui.production.patterns";
    private static final String FEEDER_LABEL = "gui.production.feeder";
    /** The tooltip lines of a refused pattern, without arguments. */
    private static final List<String> TOOLTIP_LINES = List.of("gui.production.check.cell_not_one",
            "gui.production.check.not_a_recipe", "gui.production.check.skipped");
    private static final String RESULT_DIFFERS = "gui.production.check.result_differs";
    /** Goggle rows without an item in them; their only argument, if any, is a count. */
    private static final List<String> GOGGLE_ROWS = List.of("gui.goggles.crafter_feeder",
            "gui.goggles.production_feeder", "gui.goggles.production_refused",
            "gui.crafter_feeder.status.idle", "gui.crafter_feeder.status.feeding",
            "gui.crafter_feeder.status.no_station", "gui.crafter_feeder.status.two_stations",
            "gui.crafter_feeder.status.no_warehouse", "gui.crafter_feeder.status.no_crafter",
            "gui.crafter_feeder.status.no_output", "gui.crafter_feeder.status.output_full",
            "gui.crafter_feeder.status.no_rotation", "gui.crafter_feeder.status.grid_not_empty",
            "gui.crafter_feeder.status.grid_disturbed", "gui.crafter_feeder.status.external_signal",
            "gui.crafter_feeder.status.missing_ingredients", "gui.crafter_feeder.status.craft_failed",
            "gui.crafter_feeder.status.recipe_changed");
    /** The goggle row that names the order's item and its runs. */
    private static final String FEEDING = "gui.crafter_feeder.feeding";

    @Test
    void theFeederLabelSharesThePatternsRow() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            int width = ItemTooltipLengthTest.width(text(lang, language.getKey(), PATTERNS), advances) + TEXT_GAP
                    + ItemTooltipLengthTest.width(text(lang, language.getKey(), FEEDER_LABEL), advances);
            assertTrue(width <= ROW_WIDTH, language.getKey() + ": the Patterns row with the feeder label is " + width
                    + " px, the row is " + ROW_WIDTH);
        }
    }

    @Test
    void everyFeederTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String name = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : TOOLTIP_LINES)
                check(tooWide, name, key, ItemTooltipLengthTest.width(text(lang, name, key), advances), ROW_WIDTH);
            String differs = text(lang, name, RESULT_DIFFERS).replace("%1$s", "").replace("%2$s", WIDEST_AMOUNT);
            check(tooWide, name, RESULT_DIFFERS, ItemTooltipLengthTest.width(differs, advances),
                    ROW_WIDTH - NAME_FLOOR);
            for (String key : GOGGLE_ROWS) {
                String row = text(lang, name, key).replace("%1$s", WIDEST_COUNT);
                check(tooWide, name, key, ItemTooltipLengthTest.width(row, advances), GOGGLE_ROW);
            }
            String feeding = text(lang, name, FEEDING).replace("%1$s", LONG_ITEM).replace("%2$s", WIDEST_COUNT);
            check(tooWide, name, FEEDING, ItemTooltipLengthTest.width(feeding, advances), GOGGLE_ROW);
        }
        if (!tooWide.isEmpty())
            fail("These Crafter Feeder texts do not fit where they are drawn; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every feeder key of the generated lang is measured above: a new one has to be added here first. */
    @Test
    void everyFeederKeyIsMeasured() throws IOException {
        Set<String> own = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (!key.startsWith(PREFIX))
                continue;
            String relative = key.substring(PREFIX.length());
            if (relative.startsWith("gui.crafter_feeder.") || relative.startsWith("gui.production.check."))
                own.add(relative);
        }
        assertTrue(!own.isEmpty(), "no feeder keys found; run ./gradlew runData");
        Set<String> measured = new TreeSet<>(TOOLTIP_LINES);
        measured.add(RESULT_DIFFERS);
        measured.add(FEEDING);
        for (String key : GOGGLE_ROWS)
            if (key.startsWith("gui.crafter_feeder."))
                measured.add(key);
        assertEquals(own, measured, "every Crafter Feeder text must be measured by the test above");
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
