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

import dev.wareworks.content.station.TerminalMenuLayout;

/**
 * Keeps the texts of asking the warehouse for a fluid (M31, issue #21, ADR-073) inside the room the terminal draws them
 * in, in English and German, with the real advance of every glyph of Minecraft 1.21.1's default font
 * ({@link ItemTooltipLengthTest}):
 * <ul>
 * <li>the fluid cell's <b>tooltip</b> lines, each within the window's row, every amount at its widest — except the
 * pipe hint, which the screen wraps to the row and is measured word by word;</li>
 * <li>the fluid <b>question</b>: every line that carries only numbers within the confirmation panel's text width
 * without wrapping, and the <b>whole</b> question at its worst — two kinds of container, a shortfall, the station's
 * room, a reserve and the offer to make containers, the names at a realistic length — wrapped as the panel wraps it and
 * still inside the window at the 320 × 240 floor;</li>
 * <li>the fill request's <b>order line</b> and the bucket amount within the row.</li>
 * </ul>
 * Every key under {@code gui.terminal.fluid} is in one of the groups, so a new one cannot slip past unmeasured.
 */
class FluidQuestionTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String PREFIX = "wareworks.gui.terminal.fluid.";
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /** The confirmation panel's text width ({@code WarehouseTerminalScreen#confirmTextWidth}). */
    private static final int DIALOG_WIDTH = ROW_WIDTH - 2 * 6;
    /** The panel's padding, button height and row pitch ({@code WarehouseTerminalScreen}'s constants). */
    private static final int PADDING = 6;
    private static final int BUTTON_HEIGHT = 14;
    private static final int LINE_PITCH = 9 + 2;
    /** The tallest the panel may be: the default terminal window without a margin above and below. */
    private static final int DIALOG_HEIGHT = new TerminalMenuLayout(9).height() - 2 * TerminalMenuLayout.MARGIN;
    private static final Map<String, String> WIDEST_NUMBER = Map.of("en_us", "65,536", "de_de", "65.536");
    private static final Map<String, String> WIDEST_MB = Map.of("en_us", "65,536,000", "de_de", "65.536.000");
    /** A realistic container or fluid name in the dialog: a name never wraps by itself, but it takes room. */
    private static final Map<String, String> NAME = Map.of("en_us", "Lava Bucket", "de_de", "Lavaeimer");
    private static final char NUMBER = '#';
    private static final char AMOUNT = 'a';
    private static final char BUCKETS = 'b';
    private static final char EMPTY_NAME = 'n';
    /** Tooltip lines and their argument kinds. */
    private static final Map<String, String> TOOLTIP_LINES = Map.of("stored", "a", "available", "a", "fills", "n#",
            "all_promised", "", "no_empties", "", "make_hint", "n", "amount", "#b", "buckets", "#", "amount_hint", "");
    /** Tooltip lines the screen wraps to the row: each word has to fit. */
    private static final Set<String> WRAPPED_TOOLTIP_LINES = Set.of("pipe_hint");
    /** Question lines that carry no name: each has to fit the panel without wrapping. */
    private static final Map<String, String> DIALOG_LINES = new LinkedHashMap<>();
    /** Every question line, in the order of a worst-case question, with its argument kinds. */
    private static final Map<String, String> WORST_QUESTION = new LinkedHashMap<>();

    static {
        DIALOG_LINES.put("question.uses", "");
        DIALOG_LINES.put("question.trips", "##");
        DIALOG_LINES.put("question.only", "b");
        DIALOG_LINES.put("question.not_enough_fluid", "b");
        DIALOG_LINES.put("question.not_enough_containers", "#");
        DIALOG_LINES.put("question.fetch", "");
        DIALOG_LINES.put("question.make_and_fetch", "");
        DIALOG_LINES.put("question.cancel", "");
        WORST_QUESTION.put("question.title", "bN");
        WORST_QUESTION.put("question.uses", "");
        WORST_QUESTION.put("question.portion", "#N#");
        WORST_QUESTION.put("question.portion ", "#N#");
        WORST_QUESTION.put("question.trips", "##");
        WORST_QUESTION.put("question.only", "b");
        WORST_QUESTION.put("question.no_container_fits", "a#");
        WORST_QUESTION.put("question.station_room", "#");
        WORST_QUESTION.put("question.fluid_reserve", "bbN");
        WORST_QUESTION.put("question.container_reserve", "##N");
        WORST_QUESTION.put("question.make_more", "#N");
    }

    /** Question keys measured only inside the worst case: they carry a name or a sentence the panel wraps. */
    private static final Set<String> WRAPPED_DIALOG_LINES = Set.of("question.title", "question.portion",
            "question.no_container_fits", "question.station_room", "question.fluid_reserve",
            "question.container_reserve", "question.make", "question.make_more");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$s");

    @Test
    void everyFluidTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String code = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            for (Map.Entry<String, String> line : TOOLTIP_LINES.entrySet())
                measured += check(tooWide, code, "tooltip", line.getKey(),
                        fill(text(lang, PREFIX + line.getKey()), arguments(lang, code, line.getValue(), true)),
                        ROW_WIDTH, advances);
            for (String key : WRAPPED_TOOLTIP_LINES) {
                for (String word : text(lang, PREFIX + key).split(" "))
                    measured += check(tooWide, code, "tooltip word", key, word, ROW_WIDTH, advances);
            }
            for (Map.Entry<String, String> line : DIALOG_LINES.entrySet())
                measured += check(tooWide, code, "question", line.getKey(),
                        fill(text(lang, PREFIX + line.getKey()), arguments(lang, code, line.getValue(), true)),
                        DIALOG_WIDTH, advances);
            measured += check(tooWide, code, "order line", "order.filling",
                    fill(text(lang, "wareworks.gui.terminal.order.filling"), ""), ROW_WIDTH, advances);
            // The whole question at its worst, wrapped as the panel wraps it: it has to stay inside the window.
            int rows = 0;
            for (Map.Entry<String, String> line : WORST_QUESTION.entrySet())
                rows += wrap(fill(text(lang, PREFIX + line.getKey().trim()), arguments(lang, code, line.getValue(),
                        false)), advances);
            int height = 2 * PADDING + rows * LINE_PITCH + BUTTON_HEIGHT + PADDING;
            measured++;
            if (height > DIALOG_HEIGHT)
                tooWide.add(String.format("%s the worst fluid question is %d rows, %d px high; the window has %d px",
                        code, rows, height, DIALOG_HEIGHT));
        }
        assertTrue(measured > 2 * TOOLTIP_LINES.size(), "too few texts were measured; did the lang keys move?");
        if (!tooWide.isEmpty())
            fail("These fluid request texts do not fit where the screen draws them; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every fluid key is measured above: a new one has to be given its room here first. */
    @Test
    void everyFluidKeyIsMeasured() throws IOException {
        Set<String> measured = new TreeSet<>(TOOLTIP_LINES.keySet());
        measured.addAll(WRAPPED_TOOLTIP_LINES);
        measured.addAll(DIALOG_LINES.keySet());
        measured.addAll(WRAPPED_DIALOG_LINES);
        for (String key : WORST_QUESTION.keySet())
            measured.add(key.trim());
        measured.add("question.make");
        Set<String> present = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(PREFIX))
                present.add(key.substring(PREFIX.length()));
        }
        assertTrue(present.containsAll(measured), "measured keys the generated lang does not have: "
                + difference(measured, present) + "; run ./gradlew runData");
        assertTrue(measured.containsAll(present), "fluid keys no width check covers: " + difference(present, measured));
    }

    /** The arguments of a line: numbers and amounts at their widest, names empty or realistic. */
    private static String[] arguments(Map<String, String> lang, String code, String kinds, boolean emptyNames) {
        String[] arguments = new String[kinds.length()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = switch (kinds.charAt(i)) {
                case NUMBER -> WIDEST_NUMBER.get(code);
                case AMOUNT -> fill(text(lang, PREFIX + "amount"), WIDEST_MB.get(code),
                        fill(text(lang, PREFIX + "buckets"), WIDEST_NUMBER.get(code)));
                case BUCKETS -> fill(text(lang, PREFIX + "buckets"), WIDEST_NUMBER.get(code));
                case EMPTY_NAME -> "";
                default -> emptyNames ? "" : NAME.get(code);
            };
        }
        return arguments;
    }

    /** Rows {@code text} takes in the panel, wrapped at spaces as {@code Font#split} wraps it. */
    private static int wrap(String text, Map<Character, Integer> advances) {
        int rows = 1;
        int width = 0;
        int space = ItemTooltipLengthTest.width(" ", advances);
        for (String word : text.split(" ")) {
            int wordWidth = ItemTooltipLengthTest.width(word, advances);
            if (width > 0 && width + space + wordWidth > DIALOG_WIDTH) {
                rows++;
                width = wordWidth;
            } else {
                width += (width > 0 ? space : 0) + wordWidth;
            }
        }
        return rows;
    }

    private static int check(List<String> tooWide, String language, String room, String key, String text, int budget,
            Map<Character, Integer> advances) {
        int width = ItemTooltipLengthTest.width(text, advances);
        if (width > budget)
            tooWide.add(String.format("%s %s %s: '%s' is %d px, the room is %d px", language, room, key, text, width,
                    budget));
        return 1;
    }

    private static String text(Map<String, String> lang, String key) {
        String text = lang.get(key);
        if (text == null)
            fail("no text for " + key + "; run ./gradlew runData");
        return text;
    }

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
