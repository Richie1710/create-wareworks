package dev.wareworks.content.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.AisleGeometry;

/**
 * {@link CraneTrafficInfo}: what the goggles of one crane of a warehouse with several say about the others (issue #14,
 * ADR-075 §15.1), and the packed form it travels in. The NBT key around it is checked in the world
 * ({@code gametest.MultiCraneGameTests#goggleslistseverycrane}); this source set has no Minecraft on its classpath.
 */
class CraneTrafficInfoTest {
    private static final int NO = CraneTrafficInfo.NO_POSITION;

    @Test
    void everyLineSurvivesThePackedRoundTrip() {
        for (CraneTrafficInfo.Line line : CraneTrafficInfo.Line.values()) {
            CraneTrafficInfo info = new CraneTrafficInfo(2, 3, line, 1, 0, 7, 0, 3, 1, 2, 5);
            assertEquals(Optional.of(info), CraneTrafficInfo.unpack(info.pack()), line.name());
        }
    }

    @Test
    void everyFieldHasItsOwnPlace() {
        // Ten different numbers, so two swapped fields cannot pass.
        CraneTrafficInfo info = new CraneTrafficInfo(3, 4, CraneTrafficInfo.Line.PATH_HELD, 2, 1, 7, 0, 3, 1, 9, 5);
        int[] packed = info.pack();
        assertEquals(CraneTrafficInfo.PACKED_LENGTH, packed.length);
        CraneTrafficInfo read = CraneTrafficInfo.unpack(packed).orElseThrow();
        assertEquals(3, read.number());
        assertEquals(4, read.craneCount());
        assertEquals(CraneTrafficInfo.Line.PATH_HELD, read.line());
        assertEquals(2, read.otherCrane());
        assertEquals(1, read.heldBranch());
        assertEquals(7, read.heldX());
        assertEquals(0, read.fromBranch());
        assertEquals(3, read.fromX());
        assertEquals(1, read.toBranch());
        assertEquals(9, read.toX());
        assertEquals(5, read.servedLevels());
    }

    @Test
    void thereIsNoTrafficInfoForOneCrane() {
        CraneTrafficInfo quiet = CraneTrafficInfo.quiet(1, 1, 0);
        assertEquals(2, quiet.craneCount(), "a record that exists speaks for a warehouse of at least two cranes");
        int[] forOne = quiet.pack();
        forOne[1] = 1;
        assertEquals(Optional.empty(), CraneTrafficInfo.unpack(forOne), "a packet naming one crane is no traffic info");
    }

    @Test
    void untrustedDataNeverThrows() {
        assertEquals(Optional.empty(), CraneTrafficInfo.unpack(null));
        assertEquals(Optional.empty(), CraneTrafficInfo.unpack(new int[3]), "wrong length");
        int[] unknown = CraneTrafficInfo.quiet(2, 2, 0).pack();
        unknown[2] = 99;
        assertEquals(CraneTrafficInfo.Line.NONE, CraneTrafficInfo.unpack(unknown).orElseThrow().line(),
                "an unknown line reads as none");
        int[] wild = {2, 2, CraneTrafficInfo.Line.PATH_HELD.ordinal(), -5, 999, -3, -1, 4, 0, 4,
                AisleGeometry.MAX_HEIGHT + 50};
        CraneTrafficInfo read = CraneTrafficInfo.unpack(wild).orElseThrow();
        assertEquals(0, read.otherCrane(), "no negative crane");
        assertFalse(read.hasHeldRail(), "a rail outside every warehouse is none");
        assertFalse(read.hasReservation(), "half a reserved stretch is none");
        assertEquals(AisleGeometry.MAX_HEIGHT, read.servedLevels());
    }

    @Test
    void onlyLinesThatNameACraneKeepOne() {
        CraneTrafficInfo noPath = new CraneTrafficInfo(2, 2, CraneTrafficInfo.Line.NO_PATH, 1, NO, NO, NO, NO, NO, NO,
                0);
        assertEquals(0, noPath.otherCrane(), "\"no rails lead there\" is about nobody");
        CraneTrafficInfo aside = CraneTrafficInfo.quiet(2, 3, 0).withLine(CraneTrafficInfo.Line.MOVING_ASIDE, 3, NO,
                NO);
        assertEquals(3, aside.otherCrane());
        assertTrue(CraneTrafficInfo.Line.PATH_HELD.isWaiting());
        assertFalse(CraneTrafficInfo.Line.MOVING_ASIDE.isWaiting(), "a crane moving aside drives");
        assertFalse(CraneTrafficInfo.Line.STRANDED.isWaiting());
        assertEquals("gui.goggles.crane_traffic.would_cut_off", CraneTrafficInfo.Line.WOULD_CUT_OFF.langKey());
    }

    /** The crane list says "waiting for crane N" only for a wait that names a crane, never "waiting for crane 0". */
    @Test
    void onlyAWaitThatNamesACraneWaitsForOne() {
        CraneTrafficInfo noPath = CraneTrafficInfo.quiet(2, 2, 0).withLine(CraneTrafficInfo.Line.NO_PATH, 1, NO, NO);
        assertFalse(noPath.waitsForACrane(), "no rails lead there: it waits for no crane");
        CraneTrafficInfo noController = CraneTrafficInfo.quiet(2, 2, 0)
                .withLine(CraneTrafficInfo.Line.NO_CONTROLLER, 1, NO, NO);
        assertFalse(noController.waitsForACrane());
        CraneTrafficInfo held = CraneTrafficInfo.quiet(2, 2, 0).withLine(CraneTrafficInfo.Line.PATH_HELD, 1, 0, 4);
        assertTrue(held.waitsForACrane());
        assertFalse(CraneTrafficInfo.quiet(2, 2, 0).withLine(CraneTrafficInfo.Line.MOVING_ASIDE, 1, NO, NO)
                .waitsForACrane(), "a crane moving aside drives");
    }

    @Test
    void theReservationIsBothEndsOrNone() {
        CraneTrafficInfo reserved = CraneTrafficInfo.quiet(1, 2, 0).withReservation(0, 2, 0, 9);
        assertTrue(reserved.hasReservation());
        assertFalse(reserved.withReservation(NO, NO, NO, NO).hasReservation());
    }
}
