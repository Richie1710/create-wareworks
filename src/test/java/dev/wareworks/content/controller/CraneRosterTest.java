package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

/**
 * {@link CraneRoster} and {@link CraneCounts}: the controller's list of the cranes of a warehouse with several, the
 * terminal's counts, and the packed forms they travel in (issue #14, ADR-075 §15). The NBT key and the terminal's byte
 * stream around them are checked in the world ({@code gametest.MultiCraneGameTests},
 * {@code gametest.WarehouseTerminalMenuGameTests#terminalpayloadroundtrip}); this source set has no Minecraft on its
 * classpath.
 */
class CraneRosterTest {
    private static final RackPosition TARGET = new RackPosition(1, 7, 2, Side.RIGHT);

    private static CraneRoster.Entry entry(int number, CraneRoster.Activity activity) {
        return new CraneRoster.Entry(number, activity, 0, Optional.empty(), -1);
    }

    @Test
    void theCountsCoverEveryCrane() {
        List<CraneRoster.Entry> cranes = List.of(
                new CraneRoster.Entry(1, CraneRoster.Activity.STORING, 0, Optional.of(TARGET), 12),
                new CraneRoster.Entry(2, CraneRoster.Activity.WAITING, 1, Optional.empty(), -1),
                entry(3, CraneRoster.Activity.MOVING_ASIDE),
                entry(4, CraneRoster.Activity.PAUSED),
                entry(5, CraneRoster.Activity.IDLE),
                entry(6, CraneRoster.Activity.UNLOADED),
                entry(7, CraneRoster.Activity.RETRIEVING));
        CraneRoster roster = CraneRoster.of(cranes, 2, 7);
        assertEquals(new CraneCounts(7, 3, 1, 1), roster.counts(),
                "working: the two jobs and the crane moving aside; waiting and paused one each");
        assertEquals(2, roster.counts().idle(), "the idle crane and the one nobody can see");
        assertEquals(CraneRoster.MAX_LISTED, roster.entries().size(), "six listed by name");
        assertEquals(1, roster.unlisted(), "and the seventh counted");
    }

    @Test
    void thePackedFormKeepsEveryLine() {
        List<CraneRoster.Entry> cranes = new ArrayList<>();
        cranes.add(new CraneRoster.Entry(1, CraneRoster.Activity.HANDING_OVER, 0, Optional.of(TARGET), 9));
        cranes.add(new CraneRoster.Entry(2, CraneRoster.Activity.WAITING, 1, Optional.empty(), -1));
        cranes.add(new CraneRoster.Entry(3, CraneRoster.Activity.COLLECTING, 0, Optional.of(TARGET), -1));
        for (CraneRoster.Activity activity : CraneRoster.Activity.values())
            if (cranes.size() < 9)
                cranes.add(entry(cranes.size() + 1, activity));
        CraneRoster roster = CraneRoster.of(cranes, 1, 4);
        assertEquals(Optional.of(roster), CraneRoster.unpack(roster.pack()));
        CraneRoster read = CraneRoster.unpack(roster.pack()).orElseThrow();
        assertEquals(Optional.of(TARGET), read.entries().get(0).target());
        assertEquals(9, read.entries().get(0).seconds());
        assertEquals(1, read.entries().get(1).otherCrane());
        assertFalse(read.entries().get(2).hasSeconds(), "a job whose time is unknown says so");
        assertEquals(1, read.refusedDocks());
        assertEquals(4, read.maxCranes());
    }

    @Test
    void anEntryKeepsOnlyWhatItsActivityShows() {
        CraneRoster.Entry idle = new CraneRoster.Entry(2, CraneRoster.Activity.IDLE, 3, Optional.of(TARGET), 40);
        assertEquals(0, idle.otherCrane(), "an idle crane waits for nobody");
        assertEquals(Optional.empty(), idle.target(), "and goes nowhere");
        assertFalse(idle.hasSeconds());
    }

    @Test
    void untrustedDataNeverThrows() {
        assertEquals(Optional.empty(), CraneRoster.unpack(null));
        assertEquals(Optional.empty(), CraneRoster.unpack(new int[2]), "shorter than its head");
        int[] packed = CraneRoster.of(List.of(entry(1, CraneRoster.Activity.IDLE), entry(2, CraneRoster.Activity.IDLE)),
                0, 4).pack();
        int[] lying = packed.clone();
        lying[CraneCounts.PACKED_LENGTH + 2] = 5;
        assertEquals(Optional.empty(), CraneRoster.unpack(lying), "a line count that does not match its length");
        int[] tooMany = packed.clone();
        tooMany[CraneCounts.PACKED_LENGTH + 2] = CraneRoster.MAX_LISTED + 1;
        assertEquals(Optional.empty(), CraneRoster.unpack(tooMany), "more lines than are ever synced");
        int[] wild = packed.clone();
        wild[CraneCounts.PACKED_LENGTH + 3 + 1] = 77;
        assertEquals(CraneRoster.Activity.IDLE, CraneRoster.unpack(wild).orElseThrow().entries().get(0).activity(),
                "an unknown activity reads as idle");
    }

    @Test
    void theCountsNeverAddUpToMoreThanTheCranes() {
        CraneCounts counts = new CraneCounts(3, 2, 2, 2);
        assertEquals(3, counts.working() + counts.waiting() + counts.paused());
        assertEquals(0, counts.idle());
        assertEquals(new CraneCounts(0, 0, 0, 0), new CraneCounts(-1, 5, 5, 5));
        assertFalse(CraneCounts.NONE.several());
        assertFalse(new CraneCounts(1, 1, 0, 0).several(), "one crane is never counted on a surface");
        assertTrue(new CraneCounts(2, 0, 0, 0).several());
        CraneCounts four = new CraneCounts(9, 4, 3, 1);
        assertEquals(four, CraneCounts.unpack(four.pack()));
        assertEquals(CraneCounts.NONE, CraneCounts.unpack(new int[] {1, 2}), "the wrong length reads as none");
    }
}
