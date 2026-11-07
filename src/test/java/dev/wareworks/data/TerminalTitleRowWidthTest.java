package dev.wareworks.data;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.core.address.StorageAddress;

/**
 * Keeps the terminal screen's title row free of collisions in English and German, with the real advance of every
 * glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}). The row is the window without its margins:
 * the screen's title is drawn from the left, the warehouse ({@code "Warehouse A"}) right-aligned at its end, and both
 * have to fit with the gap between them — for the plain title and for the one a screen opened through a Warehouse
 * Remote carries (issue #44, ADR-077), at the widest aisle letter.
 * <p>
 * The other labels of that row give way instead: a chosen door's name is cut to the room the title leaves and keeps a
 * floor of its own ({@link TerminalDoorTextWidthTest}), and the line of a terminal that is not part of a warehouse is
 * cut the same way ({@code WarehouseTerminalScreen#titleRowLabel}). The {@code remote} visual scenario measures the row
 * again with the client's font ({@code RemoteVisualScenario#measureTitleRow}).
 */
class TerminalTitleRowWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    /** The title row: the window without its two margins. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /** The gap the title row keeps between the screen's title and the label at its right end. */
    private static final int TITLE_GAP = 4;
    private static final String TERMINAL_TITLE = "block.wareworks.warehouse_terminal";
    private static final String REMOTE_TITLE = "wareworks.gui.terminal.remote_title";
    private static final String WAREHOUSE_LETTER = "wareworks.gui.goggles.warehouse_letter";

    @Test
    void bothTitlesLeaveTheWarehouseItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            String warehouse = widestWarehouse(text(lang, WAREHOUSE_LETTER), advances);
            for (String key : List.of(TERMINAL_TITLE, REMOTE_TITLE)) {
                String title = text(lang, key);
                int used = ItemTooltipLengthTest.width(title, advances) + TITLE_GAP
                        + ItemTooltipLengthTest.width(warehouse, advances);
                if (used > ROW_WIDTH)
                    tooWide.add(String.format("%s %s: '%s' and '%s' take %d of %d px", language.getKey(), key, title,
                            warehouse, used, ROW_WIDTH));
            }
        }
        if (!tooWide.isEmpty())
            fail("These titles run into the warehouse in the title row; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** {@code "Warehouse %1$s"} with the aisle letter whose glyph is widest. */
    private static String widestWarehouse(String template, Map<Character, Integer> advances) {
        String widest = null;
        for (int aisle = 0; aisle < StorageAddress.AISLE_COUNT; aisle++) {
            String text = template.replace("%1$s", String.valueOf(StorageAddress.aisleLetter(aisle)));
            if (widest == null
                    || ItemTooltipLengthTest.width(text, advances) > ItemTooltipLengthTest.width(widest, advances))
                widest = text;
        }
        return widest;
    }

    /** The text of a full key; a missing one fails, since LangConsistencyTest checks the two files agree. */
    private static String text(Map<String, String> lang, String key) {
        String text = lang.get(key);
        if (text == null)
            fail("no text for " + key + "; run ./gradlew runData");
        return text;
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }
}
