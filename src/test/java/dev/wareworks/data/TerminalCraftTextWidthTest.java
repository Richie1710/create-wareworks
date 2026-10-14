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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;

/**
 * Keeps every text of the terminal's crafting panel (M32, issue #24) inside the room it is drawn in, in English and in
 * German, with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * The panel is {@value TerminalMenuLayout#PANEL_CONTENT_WIDTH} px wide and holds numbers and one title word, so its
 * texts live in three places with three budgets:
 * <ul>
 * <li>the <b>title</b>, at most {@value TerminalMenuLayout#MAX_PANEL_TITLE_WIDTH} px;</li>
 * <li>the window's <b>status row</b>, which shows the answer to a craft click: every result's sentence with the widest
 * count and without the item's name, because the names are what gives way in that row
 * ({@code WarehouseTerminalScreen#craftAnswerLine}), and the partial form of a Shift-click that stopped after some
 * crafts, for every refusal that can arise inside a run;</li>
 * <li>the <b>tooltips</b>, whose lines the screen keeps inside the same row width, every number at its widest.</li>
 * </ul>
 * The visual run measures the status row again on the real client ({@code TerminalVisualScenario#statusRow}); this
 * suite needs no game, so a translation that does not fit fails the build. Every key under {@code gui.terminal.craft}
 * has to be in one of the three groups, so a new one cannot slip past unmeasured.
 */
class TerminalCraftTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.gui.terminal.craft.";
    /** The status row and every tooltip line: the window without its two margins. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /**
     * The widest number any of these texts is asked to show, as the screen formats it in each language: the ceiling of
     * {@code maxTerminalRequestAmount}, which the visual run's rows are measured with too. A craft count stays far
     * below it (the buffer's slots times a stack of results).
     */
    private static final Map<String, String> WIDEST_NUMBER = Map.of("en_us", "65,536", "de_de", "65.536");
    private static final String TITLE = "title";
    /** The tooltip lines, relative to {@link #PREFIX}; every placeholder in them is a number. */
    private static final Set<String> TOOLTIP_LINES = Set.of("toggle", "toggle_hint", "clear", "per_craft",
            "available", "short", "missing", "cell_empty", "cell_empty_shift", "cell_clear", "stock_hint", "ready",
            "once", "all");
    /** The form a Shift-click that crafted some and then stopped takes ({@code %3$s} is the refusal's sentence). */
    private static final String PARTIAL = "partial";
    /**
     * The refusals a running Shift-click can stop at after it crafted: the ones decided inside a single craft. Locked,
     * out of reach and an empty grid are all decided before the first craft, and the first craft unlocks the recipe.
     */
    private static final Set<TerminalCraftResult> STOPS_A_RUN = Set.of(TerminalCraftResult.NO_ROOM,
            TerminalCraftResult.NOT_IN_BUFFER, TerminalCraftResult.NO_RECIPE);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$s");

    @Test
    void everyCraftTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            String number = WIDEST_NUMBER.get(language.getKey());
            Map<String, String> statusRow = new LinkedHashMap<>();
            for (TerminalCraftResult result : TerminalCraftResult.values()) {
                // The name gives way first in the row, so what has to fit is the sentence around it.
                String sentence = fill(text(lang, result.langKey()), "", number);
                statusRow.put(result.langKey(), sentence);
                if (STOPS_A_RUN.contains(result))
                    statusRow.put(PARTIAL + " + " + result.langKey(),
                            fill(text(lang, "gui.terminal.craft." + PARTIAL), "", number, sentence));
            }
            for (Map.Entry<String, String> line : statusRow.entrySet())
                measured += check(tooWide, language.getKey(), "status row", line.getKey(), line.getValue(), ROW_WIDTH,
                        advances);
            for (String key : TOOLTIP_LINES)
                measured += check(tooWide, language.getKey(), "tooltip", key,
                        fill(text(lang, "gui.terminal.craft." + key), number, number, number), ROW_WIDTH, advances);
            measured += check(tooWide, language.getKey(), "panel title", TITLE,
                    text(lang, "gui.terminal.craft." + TITLE), TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH, advances);
        }
        assertTrue(measured > 2 * TOOLTIP_LINES.size(), "too few texts were measured; did the lang keys move?");
        if (!tooWide.isEmpty())
            fail("These crafting panel texts do not fit where the screen draws them; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every key of the panel is measured above: a new one has to be given its room here first. */
    @Test
    void everyCraftKeyIsMeasured() throws IOException {
        Set<String> measured = new TreeSet<>(TOOLTIP_LINES);
        measured.add(TITLE);
        measured.add(PARTIAL);
        for (TerminalCraftResult result : TerminalCraftResult.values())
            measured.add(result.langKey().substring("gui.terminal.craft.".length()));
        Set<String> present = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(PREFIX))
                present.add(key.substring(PREFIX.length()));
        }
        assertTrue(present.containsAll(measured), "measured keys that the generated lang does not have: "
                + difference(measured, present) + "; run ./gradlew runData");
        assertTrue(measured.containsAll(present), "crafting panel keys that no width check covers: "
                + difference(present, measured));
    }

    private static int check(List<String> tooWide, String language, String room, String key, String text, int budget,
            Map<Character, Integer> advances) {
        int width = ItemTooltipLengthTest.width(text, advances);
        if (width > budget)
            tooWide.add(String.format("%s %s %s: '%s' is %d px, the room is %d px", language, room, key, text, width,
                    budget));
        return 1;
    }

    /** The text of a relative key; a missing one fails, since LangConsistencyTest checks the two files agree. */
    private static String text(Map<String, String> lang, String relativeKey) {
        String text = lang.get("wareworks." + relativeKey);
        if (text == null)
            fail("no text for " + relativeKey + "; run ./gradlew runData");
        return text;
    }

    /** {@code template} with {@code %n$s} replaced by the n-th argument, as {@code TranslatableContents} does it. */
    private static String fill(String template, String... arguments) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            matcher.appendReplacement(out, Matcher.quoteReplacement(index < arguments.length ? arguments[index] : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> left = new TreeSet<>(from);
        left.removeAll(without);
        return left;
    }
}
