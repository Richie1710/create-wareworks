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

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalMenuLayout;

/**
 * Keeps package mode's terminal lines (issue #40, owner decision E6) inside the one status row the terminal draws them
 * in, in English and German, with the real advance of every glyph of Minecraft 1.21.1's default font
 * ({@link ItemTooltipLengthTest}): the reason the crafting panel, the Fetch, the recipe viewer's "+" and the clipboard
 * list are off, the reason a fluid cell is read-only, the clipboard's own answer, and the request refusals package mode
 * adds, which stand bare in the row (the terminal scenario's vocabulary check found the address order's one too wide). Every key under {@code gui.terminal.package_mode.} is measured, so a new one cannot slip past.
 */
class PackageModeTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String MOD = "wareworks.";
    private static final String TERMINAL_PREFIX = "gui.terminal.package_mode.";
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;

    /** Every relative key that has to fit the status row. */
    private static List<String> rowKeys() {
        return List.of(TERMINAL_PREFIX + "refused", TERMINAL_PREFIX + "fluid",
                TerminalListResult.PACKAGE_MODE.langKey(), RequestRejection.PACKAGE_MODE.langKey(),
                RequestRejection.DOOR_STRIPS_ADDRESS.langKey(), RequestRejection.PACKAGE_ORDER_ELSEWHERE.langKey());
    }

    @Test
    void everyPackageModeLineFitsTheStatusRowInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (String key : rowKeys()) {
                String text = lang.get(MOD + key);
                if (text == null) {
                    tooWide.add(language.getKey() + " " + key + ": missing; run ./gradlew runData");
                    continue;
                }
                int width = ItemTooltipLengthTest.width(text, advances);
                measured++;
                if (width > ROW_WIDTH)
                    tooWide.add(String.format("%s %s: '%s' is %d px, the row is %d px", language.getKey(), key, text,
                            width, ROW_WIDTH));
            }
        }
        assertTrue(measured >= 2 * rowKeys().size() || !tooWide.isEmpty(), "too few texts were measured");
        if (!tooWide.isEmpty())
            fail("These package mode texts do not fit the terminal's status row; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    @Test
    void everyPackageModeTerminalKeyIsMeasured() throws IOException {
        Set<String> present = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(MOD + TERMINAL_PREFIX))
                present.add(key.substring(MOD.length()));
        }
        Set<String> measured = new TreeSet<>(rowKeys());
        assertTrue(!present.isEmpty(), "the generated lang has no package mode terminal key; run ./gradlew runData");
        assertTrue(measured.containsAll(present), "package mode terminal keys no width check covers: " + present);
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }
}
