package dev.wareworks.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The policy of one warehouse port (M17, issue #12): the signed rank a board row and column compose, and the redstone
 * gate.
 * <p>
 * The <b>default</b> is the load-bearing case: a warehouse output saved before M17 carries neither a rank nor a mode, so
 * both read back as zero and {@code PULSE}, and that pair must be exactly "requests, on a rising edge".
 */
class PortSettingsTest {
    @Test
    void theDefaultIsAPlainRequestingOutput() {
        assertEquals(PortSettings.REQUEST_RANK, PortSettings.DEFAULT.rank());
        assertSame(PortRedstone.PULSE, PortSettings.DEFAULT.redstone());
        assertSame(PortDirection.REQUEST, PortSettings.DEFAULT.direction());
        assertTrue(PortSettings.DEFAULT.isRequesting());
        assertFalse(PortSettings.DEFAULT.isOverflow());
        assertFalse(PortSettings.DEFAULT.isDiversion());
        assertEquals(0, PortSettings.DEFAULT.strength());
        assertEquals(PortSettings.REQUEST_ROW, PortSettings.DEFAULT.row());
        // A missing NBT key reads as int 0 and a missing mode as the default, so this pair is what an old save produces.
        assertEquals(PortSettings.DEFAULT, new PortSettings(0, null));
    }

    /** The row carries the sign, the column the magnitude: every rank has to survive that round trip both ways. */
    @Test
    void signAndMagnitudeRoundTripOverTheWholeRange() {
        for (int rank = PortSettings.MIN_RANK; rank <= PortSettings.MAX_RANK; rank++) {
            PortSettings port = new PortSettings(rank, PortRedstone.PULSE);
            assertEquals(rank, port.rank(), "clamping must not touch a rank inside the range");
            assertEquals(rank, PortSettings.rankOf(port.row(), port.strength()), "rank " + rank);
            assertEquals(PortSettings.strengthOf(rank), port.strength(), "rank " + rank);
        }
    }

    @Test
    void aRowAndAColumnCompose() {
        assertEquals(0, PortSettings.rankOf(PortSettings.REQUEST_ROW, 7), "the request row ignores the column");
        // v + 1: the weakest overflow is already a usable one rather than a neutral that would tie with storage.
        assertEquals(-1, PortSettings.rankOf(PortSettings.OVERFLOW_ROW, 0));
        assertEquals(1, PortSettings.rankOf(PortSettings.DIVERSION_ROW, 0));
        assertEquals(-10, PortSettings.rankOf(PortSettings.OVERFLOW_ROW, PortSettings.MAX_STRENGTH));
        assertEquals(10, PortSettings.rankOf(PortSettings.DIVERSION_ROW, PortSettings.MAX_STRENGTH));
        // A board can never name a negative column or one past its maximum, but a tampered packet can.
        assertEquals(-1, PortSettings.rankOf(PortSettings.OVERFLOW_ROW, -99));
        assertEquals(10, PortSettings.rankOf(PortSettings.DIVERSION_ROW, 99));
        assertEquals(0, PortSettings.rankOf(99, 3), "an unknown row is the request row");
    }

    @Test
    void theDirectionIsTheSignOfTheRank() {
        assertSame(PortDirection.ACCEPT, new PortSettings(-1, PortRedstone.PULSE).direction());
        assertSame(PortDirection.ACCEPT, new PortSettings(4, PortRedstone.PULSE).direction());
        assertTrue(new PortSettings(-3, PortRedstone.PULSE).isOverflow());
        assertTrue(new PortSettings(3, PortRedstone.PULSE).isDiversion());
        assertFalse(new PortSettings(-3, PortRedstone.PULSE).isRequesting());
    }

    /** Clamped on read, so a number saved under a wider range is never silently rewritten into something else. */
    @Test
    void rankIsClampedOnRead() {
        assertEquals(PortSettings.MAX_RANK, new PortSettings(4711, PortRedstone.PULSE).rank());
        assertEquals(PortSettings.MIN_RANK, new PortSettings(-4711, PortRedstone.PULSE).rank());
        assertEquals(PortSettings.MAX_STRENGTH, PortSettings.strengthOf(4711));
        assertEquals(PortSettings.MAX_STRENGTH, PortSettings.strengthOf(-4711));
        assertEquals(0, PortSettings.strengthOf(0));
    }

    @Test
    void theRedstoneGateIsTheModeAndNothingElse() {
        for (boolean powered : new boolean[] {false, true}) {
            for (boolean armed : new boolean[] {false, true}) {
                assertEquals(armed, gate(PortRedstone.PULSE, powered, armed),
                        "a pulse port acts on its token: powered=" + powered + " armed=" + armed);
                assertEquals(powered, gate(PortRedstone.WHILE_POWERED, powered, armed),
                        "while powered: powered=" + powered + " armed=" + armed);
                assertEquals(!powered, gate(PortRedstone.UNLESS_POWERED, powered, armed),
                        "unless powered: powered=" + powered + " armed=" + armed);
            }
        }
    }

    /** {@code ordinal()} is the board row, so the declaration order is part of the UI contract. */
    @Test
    void theModeOrdinalIsTheBoardRow() {
        for (PortRedstone mode : PortRedstone.values())
            assertSame(mode, PortRedstone.byRow(mode.ordinal()));
        assertSame(PortRedstone.DEFAULT, PortRedstone.byRow(-1));
        assertSame(PortRedstone.DEFAULT, PortRedstone.byRow(PortRedstone.values().length));
        assertFalse(PortRedstone.PULSE.isContinuous());
        assertTrue(PortRedstone.WHILE_POWERED.isContinuous());
        assertTrue(PortRedstone.UNLESS_POWERED.isContinuous());
    }

    /** Save names, the way a block entity tag and a clipboard carry the mode. */
    @Test
    void modesAreFoundByTheirSaveName() {
        for (PortRedstone mode : PortRedstone.values())
            assertEquals(mode, PortRedstone.byName(mode.name()).orElseThrow());
        assertTrue(PortRedstone.byName(null).isEmpty());
        assertTrue(PortRedstone.byName("SOMETHING_ELSE").isEmpty());
        assertTrue(PortRedstone.byName("pulse").isEmpty(), "save names are compared exactly");
    }

    @Test
    void rowLabelsAndSignsAreWhatAPlayerReads() {
        assertEquals("output.port.request", PortSettings.rowLangKey(PortSettings.REQUEST_ROW));
        assertEquals("output.port.overflow", PortSettings.rowLangKey(PortSettings.OVERFLOW_ROW));
        assertEquals("output.port.diversion", PortSettings.rowLangKey(PortSettings.DIVERSION_ROW));
        assertEquals("output.port.request", PortSettings.rowLangKey(-1), "never out of bounds");
        assertEquals("output.port.diversion", PortSettings.rowLangKey(99));
        assertEquals("output.redstone.while_powered", PortRedstone.WHILE_POWERED.langKey());
        assertEquals("0", PortSettings.formatRank(0));
        assertEquals("+3", PortSettings.formatRank(3));
        assertEquals("-1", PortSettings.formatRank(-1));
    }

    private static boolean gate(PortRedstone mode, boolean powered, boolean armed) {
        return new PortSettings(PortSettings.REQUEST_RANK, mode).gateOpen(powered, armed);
    }
}
