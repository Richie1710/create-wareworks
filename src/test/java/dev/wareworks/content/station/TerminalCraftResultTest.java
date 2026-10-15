package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The answers of the terminal's crafting panel (issue #24): which belong to a craft click and which to the Fetch
 * button, which count as success, and that every one of them travels by its own name and has its own sentence.
 */
class TerminalCraftResultTest {
    /** What a craft click can end in (slice 1); four of them answer a Fetch too, with the same sentence. */
    private static final Set<TerminalCraftResult> CRAFT = EnumSet.of(TerminalCraftResult.CRAFTED,
            TerminalCraftResult.NO_RECIPE, TerminalCraftResult.NOT_IN_BUFFER, TerminalCraftResult.NO_ROOM,
            TerminalCraftResult.LOCKED, TerminalCraftResult.OUT_OF_REACH, TerminalCraftResult.GRID_EMPTY);
    private static final Set<TerminalCraftResult> SUCCESS = EnumSet.of(TerminalCraftResult.CRAFTED,
            TerminalCraftResult.STARTED, TerminalCraftResult.NOTHING_TO_FETCH, TerminalCraftResult.ANSWERED,
            TerminalCraftResult.DECLINED, TerminalCraftResult.RESUMED, TerminalCraftResult.CANCELLED);

    @Test
    void fetchValuesAreExactlyTheOnesNoCraftEndsIn() {
        for (TerminalCraftResult result : TerminalCraftResult.values())
            assertEquals(!CRAFT.contains(result), result.isFetch(), result.name());
        assertEquals(13, EnumSet.complementOf(EnumSet.copyOf(CRAFT)).size(), "the thirteen values of the Fetch");
    }

    @Test
    void successIsWhatThePlayerAskedFor() {
        for (TerminalCraftResult result : TerminalCraftResult.values())
            assertEquals(SUCCESS.contains(result), result.isSuccess(), result.name());
    }

    @Test
    void onlyAQuestionAsksAndAQuestionIsNoSuccess() {
        for (TerminalCraftResult result : TerminalCraftResult.values()) {
            boolean asking = result == TerminalCraftResult.ASKING || result == TerminalCraftResult.CHANGED;
            assertEquals(asking, result.isAsking(), result.name());
            if (asking) {
                assertTrue(result.isFetch(), result.name() + " answers a Fetch");
                assertFalse(result.isSuccess(), result.name() + " started nothing");
            }
        }
    }

    @Test
    void everyResultTravelsByItsNameAndHasItsOwnSentence() {
        for (TerminalCraftResult result : TerminalCraftResult.values()) {
            assertEquals(Optional.of(result), TerminalCraftResult.byName(result.name()));
            assertEquals("gui.terminal.craft.result." + result.name().toLowerCase(Locale.ROOT), result.langKey());
        }
        assertEquals(Optional.empty(), TerminalCraftResult.byName("EXPLODED"));
        assertEquals(Optional.empty(), TerminalCraftResult.byName(null));
    }
}
