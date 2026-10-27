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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.station.ListTableLayout;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.core.terminal.ListEntryStatus;

/**
 * Keeps the clipboard list question's table (issue #36, ADR-069) readable in English and German, with the real advance
 * of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}): the measured headers, status
 * words and widest amount leave the name column at least {@value ListTableLayout#MIN_NAME_WIDTH} px without
 * shortening a status word, every fixed text fits the column it is drawn in, and the question and the summary above
 * the table take at most two lines each — the room {@link ListTableLayout} was measured against (four lines above the
 * table at the smallest window, {@code ListTableLayoutTest}).
 * <p>
 * A text that is too wide would still be drawn — the client shortens it with an ellipsis — but a status word or a
 * header cut short is a translation that has to be changed, so this fails the build instead. Every key under
 * {@code gui.terminal.list.table} has to be in one of the groups, so a new one cannot slip past unmeasured.
 */
class TerminalListTableTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.gui.terminal.list.table.";
    /**
     * The widest amount a row is asked to show, as the screen formats it: an entry's amount is a clipboard count, and a
     * Schematicannon writes far less than this per entry.
     */
    private static final Map<String, String> WIDEST_NUMBER = Map.of("en_us", "65,536", "de_de", "65.536");
    /**
     * The widest total the lines above the table show: a whole list's items, 1024 entries of up to a few stacks each.
     */
    private static final Map<String, String> WIDEST_TOTAL = Map.of("en_us", "1,048,576", "de_de", "1.048.576");
    /** Lines the question and the summary may take: the layout's floor was measured with four lines above the table. */
    private static final int MAX_LINES_ABOVE = 2;
    /** Keys drawn in the detail column, with their number of numeric arguments. */
    private static final Map<String, Integer> DETAIL = Map.of("detail.available", 1, "detail.crafted", 2,
            "missing.entries", 1, "reason.none", 0);
    /** Keys drawn above the table, every argument a total. */
    private static final Map<String, Integer> ABOVE = Map.of("question.partial", 2, "question.whole", 1, "summary", 3,
            "question.partial_made", 3, "question.whole_made", 2);
    /** Keys only shown in a tooltip, which wraps nothing and may be as wide as the screen; listed so all are seen. */
    private static final Set<String> TOOLTIP_ONLY = Set.of("tooltip.split", "tooltip.made", "missing.needed_by",
            "missing.entry", "missing.more", "detail.partly");
    private static final Set<String> HEADERS = Set.of("header.item", "header.wanted", "header.status");
    private static final String SECTION = "missing.title";
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$s");

    @Test
    void theTableFitsInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String code = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            String number = WIDEST_NUMBER.get(code);
            int widestStatus = 0;
            for (ListEntryStatus status : ListEntryStatus.values())
                widestStatus = Math.max(widestStatus, width(text(lang, status.langKey()), advances));
            int wantedHeader = width(table(lang, "header.wanted"), advances);
            int statusHeader = width(table(lang, "header.status"), advances);
            ListTableLayout layout = ListTableLayout.of(wantedHeader, width(number, advances), widestStatus,
                    statusHeader, new TerminalMenuLayout(1).height(), 2, 100);
            if (layout.nameWidth() < ListTableLayout.MIN_NAME_WIDTH)
                tooWide.add(code + ": the name column is only " + layout.nameWidth() + " px");
            if (layout.statusWidth() < widestStatus)
                tooWide.add(code + ": the status words are cut to " + layout.statusWidth() + " of " + widestStatus);
            if (layout.wantedWidth() < width(number, advances) || layout.wantedWidth() < wantedHeader)
                tooWide.add(code + ": the amount column is " + layout.wantedWidth() + " px");
            check(tooWide, code, "header.item", table(lang, "header.item"), layout.nameX() + layout.nameWidth(),
                    advances);
            check(tooWide, code, SECTION, fill(table(lang, SECTION), number), ListTableLayout.CONTENT_WIDTH, advances);
            for (Map.Entry<String, Integer> detail : DETAIL.entrySet())
                check(tooWide, code, detail.getKey(), fill(table(lang, detail.getKey()),
                        repeat(number, detail.getValue())), layout.detailWidth(), advances);
            String total = WIDEST_TOTAL.get(code);
            for (Map.Entry<String, Integer> above : ABOVE.entrySet())
                check(tooWide, code, above.getKey(), fill(table(lang, above.getKey()), repeat(total, above.getValue())),
                        MAX_LINES_ABOVE * ListTableLayout.INNER_WIDTH * 4 / 5, advances);
        }
        if (!tooWide.isEmpty())
            fail("These list table texts do not fit where the screen draws them; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every key of the table is measured above or named as a tooltip line: a new one has to be given its room. */
    @Test
    void everyTableKeyIsMeasured() throws IOException {
        Set<String> measured = new TreeSet<>(DETAIL.keySet());
        measured.addAll(ABOVE.keySet());
        measured.addAll(TOOLTIP_ONLY);
        measured.addAll(HEADERS);
        measured.add(SECTION);
        for (ListEntryStatus status : ListEntryStatus.values())
            measured.add(status.langKey().substring("gui.terminal.list.table.".length()));
        Set<String> present = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(PREFIX))
                present.add(key.substring(PREFIX.length()));
        }
        assertEquals(present, measured, "run ./gradlew runData, and give every new table key its room here");
        assertTrue(measured.size() >= 21, "too few keys; did they move?");
    }

    private static void check(List<String> tooWide, String language, String key, String text, int budget,
            Map<Character, Integer> advances) {
        int width = width(text, advances);
        if (width > budget)
            tooWide.add(String.format("%s %s: '%s' is %d px, the room is %d px", language, key, text, width, budget));
    }

    private static int width(String text, Map<Character, Integer> advances) {
        return ItemTooltipLengthTest.width(text, advances);
    }

    private static String[] repeat(String value, int times) {
        String[] values = new String[times];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static Map<String, Path> languages() {
        Map<String, Path> languages = new LinkedHashMap<>();
        languages.put("en_us", GENERATED_EN_US);
        languages.put("de_de", HAND_WRITTEN_DE_DE);
        return languages;
    }

    private static String table(Map<String, String> lang, String relativeKey) {
        return text(lang, "gui.terminal.list.table." + relativeKey);
    }

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
}
