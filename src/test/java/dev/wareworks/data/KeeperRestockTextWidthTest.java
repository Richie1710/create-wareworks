package dev.wareworks.data;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Keeps the Stock Keeper's restocking line about a pattern with a fluid cell ({@code RestockOutcome.FLUID_PATTERN}) on
 * one tooltip line at the 320 x 240 floor of the GUI, in English, German and Spanish, with the real advance of every
 * glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 */
class KeeperRestockTextWidthTest {
    private static final List<Path> LANGUAGES = List.of(
            Path.of("src/generated/resources/assets/wareworks/lang/en_us.json"),
            Path.of("src/main/resources/assets/wareworks/lang/de_de.json"),
            Path.of("src/main/resources/assets/wareworks/lang/es_es.json"));
    private static final String KEY = "wareworks.gui.keeper.restock.fluid_pattern";
    /** What a tooltip line may take at the floor. */
    private static final int TOOLTIP_ROOM = 300;

    @Test
    void theFluidPatternLineFitsATooltipLine() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> wrong = new ArrayList<>();
        for (Path language : LANGUAGES) {
            String text = ItemTooltipLengthTest.strings(language).get(KEY);
            if (text == null) {
                wrong.add(language.getFileName() + ": no text for " + KEY);
                continue;
            }
            int width = ItemTooltipLengthTest.width(text, advances);
            if (width > TOOLTIP_ROOM)
                wrong.add(String.format("%s: '%s' is %d px, the room is %d px", language.getFileName(), text, width,
                        TOOLTIP_ROOM));
        }
        if (!wrong.isEmpty())
            fail(String.join("\n", wrong));
    }
}
