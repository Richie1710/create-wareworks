package dev.wareworks.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Keeps every item's Create-style description short enough to be read while holding Shift (issue #22).
 * <p>
 * Create's {@code ItemDescription} wraps the text at a fixed {@value #MAX_WIDTH_PER_LINE} px per line
 * ({@code TooltipHelper.MAX_WIDTH_PER_LINE}), so a long description never gets wider — it gets <b>taller</b>, and a
 * tooltip taller than the screen loses its top: vanilla's {@code DefaultTooltipPositioner} only pushes it up from the
 * bottom edge. What decides whether it fits is therefore the screen's height in GUI pixels, which a wide screen does
 * not add to: the automatic GUI scale is the largest that leaves at least 320 x 240, so 1440 px (2560x1440 as well as
 * 5120x1440) runs at scale 6 and has <b>240</b> px, less than 1080 px at scale 4 (270).
 * <p>
 * This suite reproduces Create's wrapping exactly — the same line break iterator, every word measured with the
 * underscores of Create's highlight markers removed, a hard wrap past {@value #MAX_WIDTH_PER_LINE} px — with the
 * real advance of every glyph in Minecraft 1.21.1's default font, read once from the client jar into
 * {@code src/test/resources/font/default_advances.json} by {@code scripts/gen_glyph_advances.py}. It runs without
 * Minecraft, like the rest of the JUnit suite.
 */
class ItemTooltipLengthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final Path ADVANCES = Path.of("src/test/resources/font/default_advances.json");

    /** {@code TooltipHelper.MAX_WIDTH_PER_LINE} in Create 6.0.10, spelled out because Create is not on this classpath. */
    private static final int MAX_WIDTH_PER_LINE = 200;

    /**
     * The most lines the description of one item may take in the Shift view.
     * <p>
     * A tooltip line is 10 GUI px tall, the positioner keeps 3 px below it and the border needs 4 above, so the 240 px
     * of a 1440 px screen hold 23 lines in all. Five of them are not the description: the item's name, Create's "Hold
     * [Shift]" line and the blank line under it, Ponder's "Hold [W] to Ponder" and the mod name JEI adds in every pack
     * that has it. The {@code tooltips} visual scenario measures the same on the real client at 5120x1440. Create's own
     * longest description, the Linked Controller, measures 19 lines in English and 21 in German the same way.
     */
    static final int MAX_SHIFT_LINES = 18;

    /**
     * Items whose tooltip also carries Create's kinetic stats — a blank line, "Kinetic Stress Impact:" and its bar —
     * which take those three lines from the description's budget.
     */
    private static final Set<String> KINETIC_ITEMS = Set.of("stacker_crane");
    private static final int KINETIC_STATS_LINES = 3;

    private static final Pattern ENTRY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+)");
    private static final Pattern SUMMARY_KEY = Pattern.compile("((?:block|item)\\.wareworks\\.([a-z0-9_]+)\\.tooltip)\\.summary");

    @Test
    void everyShiftTooltipFitsInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = advances();
        List<String> tooLong = new ArrayList<>();
        int measured = 0;
        for (Language language : Language.values()) {
            Map<String, String> lang = strings(language.file);
            for (Map.Entry<String, String> item : tooltipPrefixes(lang).entrySet()) {
                int lines = shiftLines(lang, item.getValue(), advances, language.locale);
                int budget = MAX_SHIFT_LINES - (KINETIC_ITEMS.contains(item.getKey()) ? KINETIC_STATS_LINES : 0);
                measured++;
                if (lines > budget)
                    tooLong.add(String.format("%s %s: %d lines, at most %d", language, item.getKey(), lines, budget));
            }
        }
        assertTrue(measured >= 2, "no item description was found to measure; did the lang key shape change?");
        if (!tooLong.isEmpty())
            fail("These item descriptions do not fit the Shift view (issue #22). Shorten them; the depth belongs in "
                    + "the Ponder scene and the goggles:\n  "
                    + String.join("\n  ", tooLong));
    }

    @Test
    void everyItemHasADescriptionInBothLanguages() throws IOException {
        Map<String, String> en = tooltipPrefixes(strings(GENERATED_EN_US));
        Map<String, String> de = tooltipPrefixes(strings(HAND_WRITTEN_DE_DE));
        assertEquals(en.keySet(), de.keySet(), "an item described in one language only would be measured in one only");
    }

    /**
     * A character the table does not know would be measured as nothing and let a description through that the game
     * draws wider. Unifont fallbacks are left out of the table on purpose, so one of those fails here, loudly.
     */
    @Test
    void everyCharacterInADescriptionHasAKnownWidth() throws IOException {
        Map<Character, Integer> advances = advances();
        for (Language language : Language.values()) {
            Map<String, String> lang = strings(language.file);
            for (String prefix : tooltipPrefixes(lang).values())
                for (Map.Entry<String, String> entry : lang.entrySet())
                    if (entry.getKey().startsWith(prefix + "."))
                        for (char c : entry.getValue().replace("_", "").toCharArray())
                            assertTrue(advances.containsKey(c),
                                    language + " " + entry.getKey() + " uses '" + c + "' (U+"
                                            + Integer.toHexString(c) + "), which the font table does not know");
        }
    }

    /**
     * Pins the table to the game rather than to the script that wrote it: three widths the client itself reported in
     * the visual runs ({@code WarehouseTerminalScreen} and {@code TerminalVisualScenario} record them).
     */
    @Test
    void theFontTableReproducesWidthsMeasuredInTheGame() throws IOException {
        Map<Character, Integer> advances = advances();
        assertEquals(93, width("Regalbediengerät: ", advances));
        assertEquals(219, width("Regalbediengerät: Wartet auf einen Auftrag", advances));
        assertEquals(36, width("Crane: ", advances));
    }

    @Test
    void wrappingFollowsCreate() throws IOException {
        Map<Character, Integer> advances = advances();
        assertEquals(1, wrap("Short.", advances, Locale.ENGLISH));
        assertEquals(0, wrap("", advances, Locale.ENGLISH), "an empty text draws no line");
        // Two words that just fit beside each other stay on one line; one character more pushes the second down.
        String word = "m".repeat(16) + " ";
        assertTrue(width(word + word, advances) <= MAX_WIDTH_PER_LINE, "precondition: two words fit one line");
        assertEquals(1, wrap(word + word, advances, Locale.ENGLISH));
        String longer = "m".repeat(20) + " ";
        assertTrue(width(longer + longer, advances) > MAX_WIDTH_PER_LINE, "precondition: these two do not");
        assertEquals(2, wrap(longer + longer, advances, Locale.ENGLISH));
        String highlighted = "Hold _Shift_ to read this.";
        assertEquals(wrap(highlighted.replace("_", ""), advances, Locale.ENGLISH),
                wrap(highlighted, advances, Locale.ENGLISH), "the highlight markers take no width");
        assertFalse(width("W".repeat(40), advances) <= MAX_WIDTH_PER_LINE, "precondition: a word wider than a line");
        assertEquals(1, wrap("W".repeat(40), advances, Locale.ENGLISH), "a word wider than a line stands alone");
    }

    // --- measuring ---------------------------------------------------------------------------------------------------

    /** Lines of the Shift view, as {@code ItemDescription.Builder#build} assembles them. */
    static int shiftLines(Map<String, String> lang, String prefix, Map<Character, Integer> advances, Locale locale) {
        int lines = wrap(lang.getOrDefault(prefix + ".summary", ""), advances, locale);
        int pairs = 0;
        for (int i = 1; lang.containsKey(prefix + ".condition" + i); i++) {
            pairs++;
            lines += 1; // the condition is one unwrapped line
            lines += wrap(lang.getOrDefault(prefix + ".behaviour" + i, ""), advances, locale);
        }
        return pairs > 0 ? lines + 1 : lines; // the blank line between the summary and the behaviours
    }

    /** {@code TooltipHelper.cutTextComponent}'s line count. */
    static int wrap(String text, Map<Character, Integer> advances, Locale locale) {
        BreakIterator iterator = BreakIterator.getLineInstance(locale);
        iterator.setText(text);
        int lines = 0;
        int width = 0;
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            int wordWidth = width(text.substring(start, end).replace("_", ""), advances);
            if (width + wordWidth > MAX_WIDTH_PER_LINE) {
                lines++;
                if (width == 0)
                    continue; // a word wider than a line is put on a line of its own
                width = 0;
            }
            width += wordWidth;
        }
        return width > 0 ? lines + 1 : lines;
    }

    static int width(String text, Map<Character, Integer> advances) {
        int width = 0;
        for (char c : text.toCharArray()) {
            Integer advance = advances.get(c);
            if (advance == null)
                throw new IllegalArgumentException("no advance for U+" + Integer.toHexString(c));
            width += advance;
        }
        return width;
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    /** Item id to the key prefix of its description, for every item that has one. */
    private static Map<String, String> tooltipPrefixes(Map<String, String> lang) {
        Map<String, String> prefixes = new TreeMap<>();
        for (String key : lang.keySet()) {
            Matcher matcher = SUMMARY_KEY.matcher(key);
            if (matcher.matches())
                prefixes.put(matcher.group(2), matcher.group(1));
        }
        return prefixes;
    }

    static Map<Character, Integer> advances() throws IOException {
        Map<Character, Integer> advances = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(Files.readString(ADVANCES, StandardCharsets.UTF_8));
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1));
            if (codePoint <= Character.MAX_VALUE)
                advances.put((char) codePoint, Integer.parseInt(matcher.group(2)));
        }
        return advances;
    }

    static Map<String, String> strings(Path file) throws IOException {
        Map<String, String> strings = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find())
            if (matcher.group(3) != null)
                strings.put(unescape(matcher.group(1)), unescape(matcher.group(3)));
        return strings;
    }

    private static String unescape(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\' || i + 1 >= json.length()) {
                out.append(c);
                continue;
            }
            char next = json.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private enum Language {
        ENGLISH(GENERATED_EN_US, Locale.ENGLISH),
        GERMAN(HAND_WRITTEN_DE_DE, Locale.GERMAN);

        final Path file;
        final Locale locale;

        Language(Path file, Locale locale) {
            this.file = file;
            this.locale = locale;
        }
    }
}
