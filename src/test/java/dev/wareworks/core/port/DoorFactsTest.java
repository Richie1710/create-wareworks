package dev.wareworks.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The rule that turns what was read about a position into a package door's state (M27, issue #18, ADR-065 D1), over
 * every one of its 2^7 inputs, and the two questions the rest of the mod asks of a state.
 */
class DoorFactsTest {
    private static final int FACTS = 7;

    /**
     * The whole truth table against the rule written out once more, in words: no door without all three of member,
     * requesting port and Packager; then a Stock Link, a waiting box, a full port and a missing sign, in that order.
     */
    @Test
    void everyCombinationOfFactsHasExactlyOneAnswer() {
        Set<DoorState> seen = EnumSet.noneOf(DoorState.class);
        for (int bits = 0; bits < 1 << FACTS; bits++) {
            boolean member = bit(bits, 0);
            boolean requestingPort = bit(bits, 1);
            boolean packager = bit(bits, 2);
            boolean linked = bit(bits, 3);
            boolean addressed = bit(bits, 4);
            boolean bufferFull = bit(bits, 5);
            boolean boxWaiting = bit(bits, 6);
            DoorFacts facts = new DoorFacts(member, requestingPort, packager, linked, addressed, bufferFull,
                    boxWaiting);

            Optional<DoorState> expected;
            if (!member || !requestingPort || !packager)
                expected = Optional.empty();
            else if (linked)
                expected = Optional.of(DoorState.IGNORES_REDSTONE);
            else if (boxWaiting)
                expected = Optional.of(DoorState.BOX_WAITING);
            else if (bufferFull)
                expected = Optional.of(DoorState.PORT_FULL);
            else if (!addressed)
                expected = Optional.of(DoorState.NO_ADDRESS);
            else
                expected = Optional.of(DoorState.READY);

            assertEquals(expected, facts.state(), facts.toString());
            assertEquals(expected.isPresent(), facts.isDoor(), "a door exactly when there is a state: " + facts);
            expected.ifPresent(seen::add);
        }
        // Every state but the one only memory gives is reachable from the facts.
        assertEquals(EnumSet.complementOf(EnumSet.of(DoorState.NOT_LOADED)), seen);
    }

    /** Each of the three door facts alone is enough to make a position no door, whatever else holds. */
    @Test
    void aMissingDoorFactIsNoDoorWhateverElseHolds() {
        assertTrue(new DoorFacts(false, true, true, true, true, true, true).state().isEmpty(), "not a member");
        assertTrue(new DoorFacts(true, false, true, true, true, true, true).state().isEmpty(), "not requesting");
        assertTrue(new DoorFacts(true, true, false, true, true, true, true).state().isEmpty(), "no Packager");
        assertTrue(DoorFacts.NONE.state().isEmpty(), "the facts of nothing");
        assertFalse(DoorFacts.NONE.isDoor(), "and they are no door");
    }

    /** The precedence, one step at a time: each cause hides the ones after it. */
    @Test
    void theMostActionableCauseWins() {
        assertEquals(Optional.of(DoorState.IGNORES_REDSTONE),
                new DoorFacts(true, true, true, true, false, true, true).state(), "a Stock Link hides everything");
        assertEquals(Optional.of(DoorState.BOX_WAITING),
                new DoorFacts(true, true, true, false, false, true, true).state(), "a waiting box hides a full port");
        assertEquals(Optional.of(DoorState.PORT_FULL),
                new DoorFacts(true, true, true, false, false, true, false).state(), "a full port hides a blank sign");
        assertEquals(Optional.of(DoorState.NO_ADDRESS),
                new DoorFacts(true, true, true, false, false, false, false).state(), "a blank sign");
        assertEquals(Optional.of(DoorState.READY),
                new DoorFacts(true, true, true, false, true, false, false).state(), "and nothing in the way");
    }

    /** Only a door that never sends and a door nobody can check are out of reach of a click. */
    @Test
    void onlyAStockLinkAndAnUnloadedDoorAreUnselectable() {
        for (DoorState state : DoorState.values())
            assertEquals(state != DoorState.IGNORES_REDSTONE && state != DoorState.NOT_LOADED, state.selectable(),
                    state.name());
    }

    /** A state travels by name, and a name this build does not know is the safe answer: a row nobody can choose. */
    @Test
    void anUnknownStateNameReadsAsNotLoaded() {
        for (DoorState state : DoorState.values())
            assertEquals(state, DoorState.byName(state.name()), state.name());
        assertEquals(DoorState.NOT_LOADED, DoorState.byName("SOMETHING_NEWER"));
        assertEquals(DoorState.NOT_LOADED, DoorState.byName(null));
        assertEquals(DoorState.NOT_LOADED, DoorState.byName("ready"), "names are matched exactly");
        assertFalse(DoorState.byName("SOMETHING_NEWER").selectable(), "and the fallback cannot be chosen");
    }

    /** Every state but ready has its own word, under one prefix; ready names its rack address instead. */
    @Test
    void everyStateButReadyHasAWord() {
        assertTrue(DoorState.READY.langKey().isEmpty(), "ready says no state word");
        for (DoorState state : DoorState.values()) {
            if (state == DoorState.READY)
                continue;
            assertEquals(Optional.of("gui.terminal.door.state." + state.name().toLowerCase(Locale.ROOT)),
                    state.langKey(), state.name());
        }
    }

    /** Every state but ready has one sentence of advice for the chosen door's tooltip, under a prefix of its own. */
    @Test
    void everyStateButReadyHasItsAdvice() {
        assertTrue(DoorState.READY.detailKey().isEmpty(), "a ready door needs no advice");
        for (DoorState state : DoorState.values()) {
            if (state == DoorState.READY)
                continue;
            assertEquals(Optional.of("gui.terminal.door.detail." + state.name().toLowerCase(Locale.ROOT)),
                    state.detailKey(), state.name());
        }
    }

    private static boolean bit(int bits, int index) {
        return (bits >> index & 1) == 1;
    }
}
