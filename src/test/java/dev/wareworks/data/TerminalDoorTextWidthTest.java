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

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.production.PlanRefusal;

/**
 * Keeps every text of the terminal's door choice (M27, issue #18, ADR-065) inside the room it is drawn in, in English
 * and in German, with the real advance of every glyph of Minecraft 1.21.1's default font ({@link ItemTooltipLengthTest}).
 * <p>
 * The door texts live in four places with four budgets, and every key under {@code gui.terminal.door} has to be in one
 * of them ({@link #everyDoorKeyIsMeasured}), so a new one cannot slip past unmeasured:
 * <ul>
 * <li>the window's <b>status row</b> ({@value #ROW_WIDTH} px): the answers to a door click, the chain's refusal of an
 * accepted one and the standing line. A door's name and an item's name are arguments, and they are what gives way in
 * that row ({@code WarehouseTerminalScreen#fitDoorLine}), so each sentence is measured with the names empty, the
 * numbers at their widest, and has to leave the names a floor of their own;</li>
 * <li>the <b>title row</b>: the gold {@code "→ <door>"} right of the screen's title, which has to leave the door's name
 * room after the title and the gap in each language;</li>
 * <li>the <b>question</b>: the confirmation panel draws its lines {@value #DIALOG_WIDTH} px wide;</li>
 * <li>the door button's <b>tooltip</b>, whose lines the screen keeps inside the row width: its entries carry a mark,
 * the door's name and a state word, and the word has to leave the name a floor too.</li>
 * </ul>
 * The two door rejections are shown bare in the status row ({@code WarehouseTerminalScreen#refusedLine}), so they are
 * measured bare against it; the visual run measures them again through the screen's own decision
 * ({@code TerminalVisualScenario#statusRow}).
 */
class TerminalDoorTextWidthTest {
    private static final Path GENERATED_EN_US = Path.of("src/generated/resources/assets/wareworks/lang/en_us.json");
    private static final Path HAND_WRITTEN_DE_DE = Path.of("src/main/resources/assets/wareworks/lang/de_de.json");
    private static final String RELATIVE_PREFIX = "gui.terminal.door.";
    private static final String PREFIX = "wareworks." + RELATIVE_PREFIX;
    /** The status row and every tooltip line: the window without its two margins. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /** The confirmation panel's text width ({@code WarehouseTerminalScreen#confirmTextWidth}). */
    private static final int DIALOG_WIDTH = ROW_WIDTH - 2 * 6;
    /** The gap the title row keeps between the screen's title and the label at its right end. */
    private static final int TITLE_GAP = 4;
    /** The widest number any of these texts shows: the ceiling of {@code maxTerminalRequestAmount}. */
    private static final Map<String, String> WIDEST_NUMBER = Map.of("en_us", "65,536", "de_de", "65.536");
    /**
     * The widest rack address a door without a sign is named by, {@code StorageAddress#format}: a far aisle letter and
     * three-digit level and position, wider than any warehouse a server builds.
     */
    private static final String WIDEST_RACK = "W-128-128R";
    /** An argument that is a name: it gives way first wherever the text stands, so it is measured empty. */
    private static final char NAME = 'n';
    /** An argument that is a number, measured at its widest. */
    private static final char NUMBER = '#';
    /** An argument that is a rack address, measured at its widest. */
    private static final char RACK = 'r';

    /**
     * A text measured in one room: its key relative to {@link #PREFIX}, its arguments' kinds in order, and the room
     * it has to leave free for the names it gives way at.
     */
    private record Line(String key, String arguments, int nameFloor) {
    }

    /**
     * The status row's door texts. The answers name an item and a door, so their frames have to leave both names room:
     * the plain answer enough for an item's name and a typical sign side by side (120 px at the widest numbers), the
     * answer with a production count as much as two numbers allow. The short standing line is
     * the fallback that cuts the door's name, so it has to leave the name most of the row. The full standing line is
     * only used when it fits with the whole name, so it has no floor.
     */
    private static final List<Line> STATUS_ROW = List.of(new Line("requested", "n#n", 120),
            new Line("producing", "n#n#", 70), new Line("standing", "n##", 0), new Line("standing_short", "n#", 120),
            new Line("unavailable", "", 0), new Line("refused_full", "", 0));
    /**
     * An accepted door click whose chain was refused: the door, then the chain's own sentence about an item. Measured
     * with every chain sentence, both names empty, and it has to leave them a floor together.
     */
    private static final Line SHORTFALL = new Line("shortfall", "nn", 50);
    /** The title row's label: the door's name keeps at least this much beside the screen's title. */
    private static final Line INDICATOR = new Line("indicator", "n", 60);
    /** The lines of the question. */
    private static final List<Line> DIALOG = List.of(new Line("confirm", "n", 60));
    /** The tooltip lines that are not a door's entry. */
    private static final List<Line> TOOLTIP = List.of(new Line("title", "n", 90), new Line("here", "", 0),
            new Line("unnamed", "r", 0), new Line("more", "#", 0), new Line("unchecked", "", 0),
            new Line("hint", "", 0), new Line("cell", "n", 90), new Line("list_here", "", 0),
            new Line("fetch_here", "", 0));
    /** The mark of the current entry, the wider of the two ({@code SelectionScrollInput}), and the gap after a name. */
    private static final String ENTRY_MARK = "-> ";
    private static final String ENTRY_GAP = "  ";
    /** Room a door's name keeps in its tooltip entry beside the widest state word. */
    private static final int ENTRY_NAME_FLOOR = 90;
    private static final String TERMINAL_TITLE = "block.wareworks.warehouse_terminal";
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$s");

    @Test
    void everyDoorTextFitsItsRoomInEnglishAndGerman() throws IOException {
        Map<Character, Integer> advances = ItemTooltipLengthTest.advances();
        List<String> tooWide = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Path> language : languages().entrySet()) {
            String code = language.getKey();
            Map<String, String> lang = ItemTooltipLengthTest.strings(language.getValue());
            String number = WIDEST_NUMBER.get(code);
            for (Line line : STATUS_ROW)
                measured += check(tooWide, code, "status row", line, filled(lang, line, number), ROW_WIDTH, advances);
            for (PlanRefusal refusal : PlanRefusal.values()) {
                String sentence = fill(text(lang, refusal.langKey()), "");
                measured += check(tooWide, code, "status row", new Line(SHORTFALL.key() + " " + refusal,
                        SHORTFALL.arguments(), SHORTFALL.nameFloor()),
                        fill(text(lang, RELATIVE_PREFIX + SHORTFALL.key()), "", sentence), ROW_WIDTH, advances);
            }
            // Both rejections stand bare in the row: the "Request refused: " frame makes them too wide in both
            // languages, and the screen drops it then.
            for (RequestRejection rejection : List.of(RequestRejection.NOT_A_DOOR,
                    RequestRejection.DOOR_IGNORES_REDSTONE))
                measured += check(tooWide, code, "status row", new Line(rejection.langKey(), "", 0),
                        text(lang, rejection.langKey()), ROW_WIDTH, advances);
            int titleRoom = ROW_WIDTH - ItemTooltipLengthTest.width(lang.get(TERMINAL_TITLE), advances) - TITLE_GAP;
            measured += check(tooWide, code, "title row", INDICATOR, filled(lang, INDICATOR, number), titleRoom,
                    advances);
            for (Line line : DIALOG)
                measured += check(tooWide, code, "question", line, filled(lang, line, number), DIALOG_WIDTH, advances);
            for (Line line : TOOLTIP)
                measured += check(tooWide, code, "tooltip", line, filled(lang, line, number), ROW_WIDTH, advances);
            for (DoorState state : DoorState.values()) {
                if (state.langKey().isPresent()) {
                    String word = text(lang, state.langKey().get());
                    measured += check(tooWide, code, "tooltip entry", new Line(state.langKey().get(), "",
                            ENTRY_NAME_FLOOR), ENTRY_MARK + ENTRY_GAP + word, ROW_WIDTH, advances);
                }
                if (state.detailKey().isPresent())
                    measured += check(tooWide, code, "tooltip", new Line(state.detailKey().get(), "", 0),
                            text(lang, state.detailKey().get()), ROW_WIDTH, advances);
            }
            // A ready door's entry carries its rack address instead of a word.
            measured += check(tooWide, code, "tooltip entry", new Line("ready entry", "", ENTRY_NAME_FLOOR),
                    ENTRY_MARK + ENTRY_GAP + WIDEST_RACK, ROW_WIDTH, advances);
        }
        assertTrue(measured > 2 * (STATUS_ROW.size() + TOOLTIP.size()),
                "too few texts were measured; did the lang keys move?");
        if (!tooWide.isEmpty())
            fail("These door texts do not fit where the screen draws them; shorten them:\n  "
                    + String.join("\n  ", tooWide));
    }

    /** Every key of the door choice is measured above: a new one has to be given its room here first. */
    @Test
    void everyDoorKeyIsMeasured() throws IOException {
        Set<String> measured = new TreeSet<>();
        for (Line line : STATUS_ROW)
            measured.add(line.key());
        measured.add(SHORTFALL.key());
        measured.add(INDICATOR.key());
        for (Line line : DIALOG)
            measured.add(line.key());
        for (Line line : TOOLTIP)
            measured.add(line.key());
        for (DoorState state : DoorState.values()) {
            state.langKey().ifPresent(key -> measured.add(key.substring(RELATIVE_PREFIX.length())));
            state.detailKey().ifPresent(key -> measured.add(key.substring(RELATIVE_PREFIX.length())));
        }
        Set<String> present = new TreeSet<>();
        for (String key : ItemTooltipLengthTest.strings(GENERATED_EN_US).keySet()) {
            if (key.startsWith(PREFIX))
                present.add(key.substring(PREFIX.length()));
        }
        assertTrue(present.containsAll(measured), "measured keys that the generated lang does not have: "
                + difference(measured, present) + "; run ./gradlew runData");
        assertTrue(measured.containsAll(present), "door keys that no width check covers: "
                + difference(present, measured));
    }

    /** Records {@code text} as too wide when it is wider than {@code budget} less the floor its names keep. */
    private static int check(List<String> tooWide, String language, String room, Line line, String text, int budget,
            Map<Character, Integer> advances) {
        int width = ItemTooltipLengthTest.width(text, advances);
        int allowed = budget - line.nameFloor();
        if (width > allowed)
            tooWide.add(String.format("%s %s %s: '%s' is %d px, the room is %d px%s", language, room, line.key(),
                    text, width, allowed, line.nameFloor() > 0 ? " (" + budget + " less " + line.nameFloor()
                            + " for the names)" : ""));
        return 1;
    }

    /** The text of {@code line} with its arguments filled as their kinds say. */
    private static String filled(Map<String, String> lang, Line line, String number) {
        String[] arguments = new String[line.arguments().length()];
        for (int i = 0; i < arguments.length; i++) {
            char kind = line.arguments().charAt(i);
            arguments[i] = kind == NAME ? "" : kind == RACK ? WIDEST_RACK : number;
        }
        return fill(text(lang, RELATIVE_PREFIX + line.key()), arguments);
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
