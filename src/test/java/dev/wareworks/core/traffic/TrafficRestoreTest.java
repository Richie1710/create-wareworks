package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * I1 across a load and across a crane that turns up where the ledger did not expect it (issue #14, ADR-075 §13): a
 * restored route is checked against the blocks of every other crane, later ones included, and the blocks a loaded crane
 * really stands on always win over another crane's route.
 */
class TrafficRestoreTest {
    private static final TestNetworks.Shape LINE = TestNetworks.line(8);

    private static TrafficCrane crane(int number, double x, int dock) {
        return new TrafficCrane(number, 0, x, true, TrafficHealth.HEALTHY, Optional.empty(), dock, TrackGraph.NONE);
    }

    private static BitSet cells(int... xs) {
        BitSet set = new BitSet();
        for (int x : xs)
            set.set(LINE.graph().cellAt(0, x));
        return set;
    }

    /**
     * Three cranes: crane 2 was saved with a route through the block crane 3 stands on (a route a rebuild had dropped
     * while crane 2's chunk was away). Restored one crane at a time, crane 2's route would be taken before crane 3's
     * blocks and both would claim block 6.
     */
    @Test
    void aSavedRouteThroughALaterCranesBlocksIsDropped() {
        TrafficLedger ledger = new TrafficLedger(LINE.graph(), LINE.routes(), 0.0);
        TrafficCrane one = crane(1, 0, LINE.dock(1));
        TrafficCrane two = crane(2, 8, LINE.dock(2));
        TrafficCrane three = crane(3, 6, TrackGraph.NONE);
        List<Integer> restored = ledger.restoreAll(List.of(
                new TrafficLedger.Saved(one, Optional.empty(), 0, 0, 0, OptionalLong.empty()),
                new TrafficLedger.Saved(two, Optional.of(CraneRoute.straight(0, 8, 3, Heading.EAST)), 0, 3, 0,
                        OptionalLong.empty()),
                new TrafficLedger.Saved(three, Optional.empty(), 0, 0, 0, OptionalLong.empty())));
        assertEquals(List.of(), restored, "the route crosses crane 3's block");
        assertEquals(cells(8), ledger.claim(2));
        assertEquals(cells(6), ledger.claim(3));
        assertTrue(ledger.commitment(2).isEmpty());
    }

    @Test
    void aSavedRouteClearOfEveryCraneIsKept() {
        TrafficLedger ledger = new TrafficLedger(LINE.graph(), LINE.routes(), 0.0);
        TrafficCrane one = crane(1, 0, LINE.dock(1));
        TrafficCrane two = crane(2, 8, LINE.dock(2));
        TrafficCrane three = crane(3, 3, TrackGraph.NONE);
        List<Integer> restored = ledger.restoreAll(List.of(
                new TrafficLedger.Saved(one, Optional.empty(), 0, 0, 0, OptionalLong.empty()),
                new TrafficLedger.Saved(two, Optional.of(CraneRoute.straight(0, 8, 5, Heading.EAST)), 0, 5, 0,
                        OptionalLong.empty()),
                new TrafficLedger.Saved(three, Optional.empty(), 0, 0, 0, OptionalLong.empty())));
        assertEquals(List.of(2), restored);
        assertEquals(cells(5, 6, 7, 8), ledger.claim(2));
    }

    /**
     * Crane 1 holds a route to block 5. Crane 2 turns up on block 4 — its last known pose was one motion step stale when
     * a rebuild claimed its blocks. The machine is where it is: crane 1 loses the route and holds, so nothing drives into
     * crane 2, and asks again.
     */
    @Test
    void theBlocksALoadedCraneStandsOnWinOverAnotherCranesRoute() {
        TrafficLedger ledger = new TrafficLedger(LINE.graph(), LINE.routes(), 0.0);
        TrafficCrane one = crane(1, 1, LINE.dock(1)).wanting(Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 5)));
        TrafficCrane two = crane(2, 9, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1));
        assertEquals(cells(1, 2, 3, 4, 5), ledger.claim(1));

        ledger.pass(2, List.of(one, two.at(0, 4)));
        assertTrue(ledger.commitment(1).isEmpty(), "crane 1's route is dropped");
        assertEquals(cells(1), ledger.claim(1), "it keeps the block it stands on");
        assertTrue(ledger.claim(2).get(LINE.graph().cellAt(0, 4)), "crane 2 holds where it really stands");
        assertEquals(1, ledger.repairs());
        TrafficVerdict.Hold hold = assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1));
        assertEquals(2, hold.waiting().otherCrane(), "and it waits for crane 2");
        assertEquals(TrafficLedger.Gate.HOLD, ledger.gate(1, 0, 1, true, 0, 5, null));
    }

    @Test
    void aHealthyPassNeverRepairs() {
        TrafficLedger ledger = new TrafficLedger(LINE.graph(), LINE.routes(), 0.0);
        TrafficCrane one = crane(1, 1, LINE.dock(1)).wanting(Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 5)));
        TrafficCrane two = crane(2, 9, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        for (int tick = 2; tick < 10; tick++)
            ledger.pass(tick, List.of(one.at(0, Math.min(5, 1 + (tick - 1) * 0.5)), two));
        assertEquals(0, ledger.repairs());
    }
}
