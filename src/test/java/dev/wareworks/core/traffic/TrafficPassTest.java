package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * One behaviour of the traffic pass per test (ADR-075 §4, §5): atomic grants, the reasons a request waits, commitment
 * and the claim swap, settling, moving aside, giving way, unhealthy cranes, priorities and the skipped pass.
 */
class TrafficPassTest {
    private static final TestNetworks.Shape LINE = TestNetworks.line(8);

    private static TrafficLedger ledger(TestNetworks.Shape shape) {
        return new TrafficLedger(shape.graph(), shape.routes(), 0.0);
    }

    /** A healthy crane with its arm in, standing at {@code x} on the first aisle of {@code shape}. */
    private static TrafficCrane crane(TestNetworks.Shape shape, int number, double x, int dock) {
        return new TrafficCrane(number, 0, x, true, TrafficHealth.HEALTHY, Optional.empty(), dock, TrackGraph.NONE);
    }

    /** A healthy crane with its arm in, standing on the block at {@code (dx, dz)}. */
    private static TrafficCrane craneAt(TestNetworks.Shape shape, int number, int dx, int dz, int dock) {
        int cell = shape.cell(dx, dz);
        return new TrafficCrane(number, shape.graph().branchOf(cell, 0), shape.graph().xOf(cell, 0), true,
                TrafficHealth.HEALTHY, Optional.empty(), dock, TrackGraph.NONE);
    }

    private static Optional<TrafficWant> job(int x) {
        return Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, x));
    }

    private static Optional<TrafficWant> jobAt(TestNetworks.Shape shape, int dx, int dz) {
        int cell = shape.cell(dx, dz);
        return Optional.of(new TrafficWant(TrafficWant.Kind.JOB, shape.graph().branchOf(cell, 0),
                shape.graph().xOf(cell, 0)));
    }

    private static BitSet cells(int... xs) {
        BitSet set = new BitSet();
        for (int x : xs)
            set.set(LINE.cell(x, 0));
        return set;
    }

    @Test
    void grantsTheWholeRouteOrNothing() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(4));
        TrafficCrane two = crane(LINE, 2, 9, LINE.dock(2));
        assertTrue(ledger.pass(1, List.of(one, two)));
        TrafficVerdict.Granted granted = assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1));
        assertEquals(LINE.cell(4, 0), granted.stopCell());
        assertEquals(cells(0, 1, 2, 3, 4), ledger.claim(1), "every rail up to the stop, reserved at once");

        ledger.pass(2, List.of(one, two.wanting(job(3))));
        TrafficVerdict.Hold hold = assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(2));
        assertEquals(new TrafficWait(TrafficWait.Reason.PATH_HELD, 1, LINE.cell(4, 0)), hold.waiting(),
                "the first rail along the way that another crane holds, and whose it is");
        assertEquals(cells(9), ledger.claim(2), "a refused request reserves nothing");
    }

    @Test
    void namesTheCraneAStopWouldCutOff() {
        TestNetworks.Shape tee = TestNetworks.tee();
        TrafficLedger ledger = ledger(tee);
        TrafficCrane one = crane(tee, 1, 0, tee.cell(0, 0)).wanting(jobAt(tee, 5, 0));
        // Crane 2 waits in the stem; its dock is at the east end of the main run.
        TrafficCrane two = craneAt(tee, 2, 3, 2, tee.cell(7, 0));
        ledger.pass(1, List.of(one, two));
        TrafficVerdict.Hold hold = assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1));
        assertEquals(TrafficWait.of(TrafficWait.Reason.WOULD_CUT_OFF, 2), hold.waiting());
        TrafficVerdict.Yield aside = assertInstanceOf(TrafficVerdict.Yield.class, ledger.verdict(2));
        assertEquals(1, aside.forCrane(), "so crane 2 is sent home, out of the way");
        assertEquals(tee.cell(7, 0), aside.stopCell());
    }

    @Test
    void theNextStopIsGrantedWhileTheArmIsStillOut() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane picking = crane(LINE, 1, 3, LINE.dock(1)).withArmRetracted(false).wanting(job(6));
        ledger.pass(1, List.of(picking, crane(LINE, 2, 9, LINE.dock(2))));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1), "posted ahead during the pick");
        assertEquals(TrafficLedger.Gate.NORMAL, ledger.gate(1, 0, 3, false, 0, 3, null), "the pick goes on");
        assertEquals(TrafficLedger.Gate.NORMAL, ledger.gate(1, 0, 3, true, 0, 6, null),
                "and with the arm in it drives off in the same tick");
    }

    @Test
    void aNewWantSwapsTheClaimInOneStep() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane two = crane(LINE, 2, 9, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        ledger.pass(2, List.of(one.at(0, 3).wanting(job(1)), two));
        TrafficVerdict.Granted granted = assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1));
        assertEquals(LINE.cell(1, 0), granted.stopCell());
        assertEquals(cells(1, 2, 3), ledger.claim(1), "the rest of the old route is released in the same step");
    }

    @Test
    void aRefusedWantKeepsTheCommittedRouteDriven() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane two = crane(LINE, 2, 8, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        ledger.pass(2, List.of(one.at(0, 3).wanting(job(8)), two));
        TrafficVerdict.Detour detour = assertInstanceOf(TrafficVerdict.Detour.class, ledger.verdict(1));
        assertEquals(LINE.cell(6, 0), detour.stopCell(), "a committed route is driven to its end");
        assertEquals(TrafficWait.Reason.PATH_HELD, detour.waiting().reason());
        assertEquals(TrafficLedger.Gate.DETOUR, ledger.gate(1, 0, 3, true, 0, 8, null));
        assertEquals(CraneRoute.straight(0, 3, 6, Heading.EAST), ledger.detourRoute(1, 0, 3).orElseThrow());
    }

    @Test
    void aCraneOnItsStopGivesUpTheRestOfItsRoute() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(4));
        TrafficCrane two = crane(LINE, 2, 9, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        ledger.pass(2, List.of(one.at(0, 4).wanting(Optional.empty()), two));
        assertEquals(cells(4), ledger.claim(1));
        assertTrue(ledger.commitment(1).isEmpty());
        assertEquals(TrafficVerdict.CLEAR, ledger.verdict(1));
    }

    @Test
    void aCraneInTheWayMovesToItsHomePoint() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane two = new TrafficCrane(2, 0, 5, true, TrafficHealth.HEALTHY, Optional.empty(), LINE.dock(2),
                LINE.cell(8, 0));
        ledger.pass(1, List.of(one, two));
        assertEquals(new TrafficWait(TrafficWait.Reason.PATH_HELD, 2, LINE.cell(5, 0)),
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1)).waiting());
        TrafficVerdict.Yield aside = assertInstanceOf(TrafficVerdict.Yield.class, ledger.verdict(2));
        assertEquals(LINE.cell(8, 0), aside.stopCell(), "its home point is out of the way");
        assertEquals(1, ledger.topCrane());

        ledger.pass(2, List.of(one, two.at(0, 8)));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1), "once it stands there, crane 1 goes");
        assertEquals(TrafficWait.of(TrafficWait.Reason.GIVING_WAY, 1),
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(2)).waiting());
    }

    @Test
    void aHomePointInTheWayIsPassedOverForTheDock() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane two = new TrafficCrane(2, 0, 5, true, TrafficHealth.HEALTHY, Optional.empty(), LINE.dock(2),
                LINE.cell(2, 0));
        ledger.pass(1, List.of(one, two));
        TrafficVerdict.Yield aside = assertInstanceOf(TrafficVerdict.Yield.class, ledger.verdict(2));
        assertEquals(LINE.dock(2), aside.stopCell(), "a home point on crane 1's way would be no way out");
    }

    @Test
    void aYoungerRequestGivesWayToTheTopOne() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane two = crane(LINE, 2, 5, LINE.dock(2)).withArmRetracted(false);
        ledger.pass(1, List.of(one, two));
        assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1));
        assertEquals(TrafficVerdict.CLEAR, ledger.verdict(2), "a crane with its arm out is not ordered aside");

        ledger.pass(2, List.of(one, two.wanting(job(3))));
        assertEquals(TrafficWait.of(TrafficWait.Reason.GIVING_WAY, 1),
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(2)).waiting(),
                "the rails crane 1 waits for are not given to a younger request");
        assertEquals(cells(5), ledger.claim(2));
    }

    @Test
    void aCraneThatCannotMoveIsAnObstacleAndSaysWhy() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane stopped = crane(LINE, 2, 5, LINE.dock(2)).withHealth(TrafficHealth.STOPPED);
        ledger.pass(1, List.of(one, stopped));
        assertEquals(new TrafficWait(TrafficWait.Reason.CRANE_STOPPED, 2, LINE.cell(5, 0)),
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1)).waiting());
        assertEquals(TrafficVerdict.CLEAR, ledger.verdict(2), "nobody orders a stopped crane aside");
        assertEquals(cells(5), ledger.claim(2));

        ledger.pass(2, List.of(one, stopped.withHealth(TrafficHealth.UNLOADED)));
        assertEquals(TrafficWait.Reason.CRANE_UNLOADED,
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1)).waiting().reason());

        ledger.pass(3, List.of(one.wanting(job(3)), stopped.withHealth(TrafficHealth.UNLOADED)));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1), "what does not need it goes on");
    }

    @Test
    void aJobGoesBeforeAnIdleCraneGoingHome() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane resting = crane(LINE, 1, 0, LINE.dock(1))
                .wanting(Optional.of(new TrafficWant(TrafficWant.Kind.REST, 0, 4)));
        TrafficCrane working = crane(LINE, 2, 9, LINE.dock(2)).wanting(job(4));
        ledger.pass(1, List.of(resting, working));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(2), "the job, although crane 2 is younger");
        assertEquals(TrafficWait.Reason.PATH_HELD,
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(1)).waiting().reason());
    }

    @Test
    void theOlderJobGoesFirst() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 3, LINE.dock(1)).withArmRetracted(false);
        TrafficCrane two = crane(LINE, 2, 6, LINE.dock(2)).withArmRetracted(false).wanting(job(2));
        ledger.pass(1, List.of(one, two));
        assertEquals(OptionalLong.of(1), ledger.since(2));
        ledger.pass(2, List.of(one.wanting(job(7)), two));
        assertEquals(OptionalLong.of(2), ledger.since(1), "a want is stamped with the tick it was first seen");
        assertEquals(OptionalLong.of(1), ledger.since(2), "and keeps its stamp while it waits");
        assertEquals(2, ledger.topCrane(), "the older one is the top request, whatever its number");
    }

    @Test
    void aPassWithNothingNewChangesNothing() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 0, LINE.dock(1)).wanting(job(6));
        TrafficCrane stopped = crane(LINE, 2, 5, LINE.dock(2)).withHealth(TrafficHealth.STOPPED);
        List<TrafficCrane> cranes = List.of(one, stopped);
        ledger.pass(1, cranes);
        TrafficVerdict before = ledger.verdict(1);
        assertFalse(ledger.pass(2, cranes));
        assertFalse(ledger.pass(3, List.of(stopped, one)), "in any order");
        assertEquals(before, ledger.verdict(1));
        assertFalse(ledger.ensurePass(3, () -> {
            throw new AssertionError("a second access in the same tick reads nothing");
        }));
    }

    @Test
    void theGateLetsACraneLeaveItsBlocksOnlyInsideItsClaim() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 2, LINE.dock(1));
        TrafficCrane two = crane(LINE, 2, 6, LINE.dock(2));
        ledger.pass(1, List.of(one, two));
        assertEquals(TrafficLedger.Gate.HOLD, ledger.gate(1, 0, 2, true, 0, 4, null), "no grant, no motion");
        assertEquals(TrafficLedger.Gate.NORMAL, ledger.gate(1, 0, 2, true, 0, 2, null), "lifting in place is free");
        assertEquals(TrafficLedger.Gate.NORMAL, ledger.gate(1, 0, 2, false, 0, 4, null),
                "a crane with its arm out may still retract");
        assertEquals(TrafficLedger.Gate.HOLD, ledger.gate(3, 0, 2, true, 0, 2, null), "an unknown crane holds");
    }

    @Test
    void aRestoredClaimNeverOverlapsAnother() {
        TrafficLedger ledger = ledger(LINE);
        TrafficCrane one = crane(LINE, 1, 1, LINE.dock(1));
        TrafficCrane two = crane(LINE, 2, 7, LINE.dock(2));
        assertTrue(ledger.restore(one, Optional.of(CraneRoute.straight(0, 1, 5, Heading.EAST)), 0, 5, 0,
                OptionalLong.of(7)));
        assertFalse(ledger.restore(two, Optional.of(CraneRoute.straight(0, 7, 4, Heading.EAST)), 0, 4, 0,
                OptionalLong.empty()), "a route into a restored claim is dropped");
        assertEquals(cells(1, 2, 3, 4, 5), ledger.claim(1));
        assertEquals(cells(7), ledger.claim(2));
        ledger.pass(10, List.of(one.wanting(job(5)), two));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1), "the restored crane drives on");
        ledger.forget(2);
        assertEquals(List.of(1), ledger.cranes());
    }
}
