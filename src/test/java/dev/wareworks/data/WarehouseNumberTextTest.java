package dev.wareworks.data;

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

import dev.wareworks.core.warehouse.WarehouseNumber;

/**
 * The texts of the "Warehouse Number" display source (issue #32), in English and in German, measured with the real
 * advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * <b>The selector.</b> The names of the numbers are drawn in the Display Link screen, inside the selector the source
 * adds there ({@code WarehouseNumberDisplaySource#initConfigurationWidgets}): a box {@value #SELECTOR_WIDTH} px wide
 * whose label starts {@value #LABEL_INSET} px in. Create's {@code Label} never cuts its text, so a name wider than the
 * box runs out of it and over the screen's frame. The source's own name stands in the screen's source selector, which
 * is {@value #SOURCE_SELECTOR_WIDTH} px wide.
 * <p>
 * <b>The display.</b> What the source writes onto a target is a number, a share, an amount of fluid or the line for a
 * block that belongs to no warehouse, which a controller without a dock writes too. The share and that line are lang
 * text, and each has to fit a display board three blocks wide, {@value #SMALL_BOARD_CHARS} characters
 * ({@code FlapDisplayBlockEntity#getMaxCharCount()} is {@code (xSize * 16 - 2) / 3.5}), which is the smallest board a
 * single number is worth hanging up. A board row is monospaced, so there characters are the unit.
 */
class WarehouseNumberTextTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.";
    /**
     * {@code WarehouseNumberDisplaySource.SELECTOR_WIDTH}, spelled out like every other number of these suites: they
     * run without Minecraft, and that class extends one of Create's.
     */
    private static final int SELECTOR_WIDTH = 137;
    /** Create's {@code ModularGuiLineBuilder} draws a selector's label this far into its box; as much is kept free. */
    private static final int LABEL_INSET = 5;
    /** Width of the Display Link screen's own source selector ({@code DisplayLinkScreen#initGathererOptions}). */
    private static final int SOURCE_SELECTOR_WIDTH = 135;
    private static final int SMALL_BOARD_BLOCKS = 3;
    private static final int SMALL_BOARD_CHARS = (int) ((SMALL_BOARD_BLOCKS * 16f - 2f) / 3.5f);
    /** The widest share, which is the one with three digits. */
    private static final String FULL_SHARE = "100";

    private static final String SOURCE_NAME = "display_source.warehouse_number";
    private static final String NUMBER_PREFIX = "display_source.number.";
    private static final String SELECTOR_TITLE = NUMBER_PREFIX + "shown";
    /** The two lang texts the source can put on a display ({@code WareworksLang}). */
    private static final String NO_WAREHOUSE = "display_source.aisle.no_aisle";
    private static final String PERCENT = "gui.goggles.percent";

    @Test
    void everyNumberHasANameThatFitsTheSelectorInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        int room = SELECTOR_WIDTH - 2 * LABEL_INSET;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> language : languages().entrySet()) {
            for (WarehouseNumber number : WarehouseNumber.values()) {
                String key = NUMBER_PREFIX + number.langKey();
                String text = shown(language.getValue(), key, language.getKey());
                int width = ItemTooltipLengthTest.width(text, advances);
                if (width > room)
                    problems.add(String.format("%s %s: '%s' is %d px, the selector has room for %d px",
                            language.getKey(), key, text, width, room));
            }
            String name = shown(language.getValue(), SOURCE_NAME, language.getKey());
            int nameWidth = ItemTooltipLengthTest.width(name, advances);
            if (nameWidth > SOURCE_SELECTOR_WIDTH - 2 * LABEL_INSET)
                problems.add(String.format("%s %s: '%s' is %d px, the source selector is %d px wide",
                        language.getKey(), SOURCE_NAME, name, nameWidth, SOURCE_SELECTOR_WIDTH));
            shown(language.getValue(), SELECTOR_TITLE, language.getKey());
        }
        if (!problems.isEmpty())
            fail("These names do not fit the Display Link screen; shorten them:\n  " + String.join("\n  ", problems));
    }

    /** Every key below the prefix is a number or the selector's title, so a name cannot outlive its number. */
    @Test
    void noNameIsLeftWithoutItsNumber() throws IOException {
        Set<String> expected = new TreeSet<>();
        for (WarehouseNumber number : WarehouseNumber.values())
            expected.add(PREFIX + NUMBER_PREFIX + number.langKey());
        expected.add(PREFIX + SELECTOR_TITLE);
        for (Map.Entry<String, Map<String, String>> language : languages().entrySet()) {
            Set<String> found = new TreeSet<>();
            for (String key : language.getValue().keySet())
                if (key.startsWith(PREFIX + NUMBER_PREFIX))
                    found.add(key);
            assertTrue(found.equals(expected), language.getKey() + " has " + found + " instead of " + expected);
        }
    }

    @Test
    void whatTheSourceWritesFitsASmallBoardInEnglishAndGerman() throws IOException {
        for (Map.Entry<String, Map<String, String>> language : languages().entrySet()) {
            Map<String, String> lang = language.getValue();
            String noWarehouse = shown(lang, NO_WAREHOUSE, language.getKey());
            String share = shown(lang, PERCENT, language.getKey()).replace("%1$s", FULL_SHARE).replace("%%", "%");
            for (String text : List.of(noWarehouse, share))
                assertTrue(text.length() <= SMALL_BOARD_CHARS, language.getKey() + ": '" + text + "' needs "
                        + text.length() + " of the " + SMALL_BOARD_CHARS + " characters of a " + SMALL_BOARD_BLOCKS
                        + "-block board");
        }
    }

    /** The text of {@code key} as the screen shows it, with the one escape a value without arguments can carry. */
    private static String shown(Map<String, String> lang, String key, String language) {
        String text = lang.get(PREFIX + key);
        if (text == null)
            fail("no " + language + " text for " + key + "; run ./gradlew runData");
        return text.replace("%%", "%");
    }

    private static Map<String, Map<String, String>> languages() throws IOException {
        Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        languages.put("en_us", ItemTooltipLengthTest.strings(GENERATED_EN_US));
        languages.put("de_de", ItemTooltipLengthTest.strings(HAND_WRITTEN_DE_DE));
        return languages;
    }
}
