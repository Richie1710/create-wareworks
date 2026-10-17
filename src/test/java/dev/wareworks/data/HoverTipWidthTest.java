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

/**
 * Keeps every line of the hover tips Create draws for this mod's value boxes on the screen, in English and in German,
 * with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * Create's {@code ValueSettingsClient#render} draws each line of a hover tip with one {@code drawString}, centred on
 * the GUI's middle, and never wraps or clamps it, so a line wider than the GUI runs off both edges. The narrowest GUI
 * is {@value #MIN_GUI_WIDTH} px wide: {@code Window#calculateScale} only picks a scale that leaves at least that much,
 * and the GUI scale option offers nothing larger. A 1280 x 960 window is exactly that wide, and the vanilla default
 * window (854 x 480) and 1280 x 720 are 427 and 426 px. The German storage priority tip was 446 px when it was first
 * written (issue #30) and was cut off at both ends in all of them.
 * <p>
 * The lines are a value box's label (its first line: {@code FilteringRenderer} and {@code ScrollValueRenderer} draw
 * it), Create's own second line, which is not this mod's, and the third line of a filter slot that has a board
 * ({@code getAmountTip}). Every key that ends in {@code _tip} has to be measured here ({@link #everyTipKeyIsMeasured}),
 * so a new one cannot slip past.
 */
class HoverTipWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    /** The narrowest GUI any automatic or chosen GUI scale gives, in GUI px. */
    private static final int MIN_GUI_WIDTH = 320;
    private static final String PREFIX = "wareworks.";
    private static final String TIP_SUFFIX = "_tip";

    /**
     * Every line of a hover tip that is this mod's text, as relative lang keys, spelled out rather than read from
     * {@code WareworksLang}: this test suite runs without Minecraft, and that class pulls in {@code Component}.
     */
    private static final List<String> LINES = List.of(
            // The store filter slot of an interface, a rack bay and a fluid bay (StorageFilterBehaviour).
            "interface.store_filter", "interface.store_priority_tip",
            // The filter slot of a warehouse port, named per direction (RequestFilterBehaviour).
            "output.request_filter", "output.accept_filter", "output.collect_filter", "output.request_amount_tip",
            "output.port_redstone_tip",
            // The scroll values: a controller's aisle letter (AisleLetterBehaviour) and a port's rank
            // (PortRankBehaviour).
            "controller.aisle_letter", "output.port_rank");

    @Test
    void everyHoverTipLineFitsTheNarrowestGuiInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : LINES) {
                String text = lang.get(PREFIX + key);
                if (text == null)
                    fail("no " + language.getKey() + " text for " + key + "; run ./gradlew runData");
                int width = ItemTooltipLengthTest.width(text, advances);
                if (width > MIN_GUI_WIDTH)
                    tooWide.add(String.format("%s %s: '%s' is %d px, the narrowest GUI is %d px", language.getKey(),
                            key, text, width, MIN_GUI_WIDTH));
                measured++;
            }
        }
        assertTrue(measured == 2 * LINES.size(), "not every line was measured in both languages");
        if (!tooWide.isEmpty())
            fail("These hover tip lines run off a narrow screen; shorten them:\n  " + String.join("\n  ", tooWide));
    }

    /** Every {@code _tip} key of the generated lang is measured above: a new one has to be added here first. */
    @Test
    void everyTipKeyIsMeasured() throws IOException {
        Set<String> tips = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet())
            if (key.startsWith(PREFIX) && key.endsWith(TIP_SUFFIX))
                tips.add(key.substring(PREFIX.length()));
        assertTrue(!tips.isEmpty(), "no tip keys found; did the lang file move?");
        Set<String> unmeasured = new TreeSet<>(tips);
        unmeasured.removeAll(LINES);
        assertTrue(unmeasured.isEmpty(), "hover tip keys that no width check covers: " + unmeasured);
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }
}
