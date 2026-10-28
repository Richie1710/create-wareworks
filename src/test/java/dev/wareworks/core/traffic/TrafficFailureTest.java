package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * What the traffic guarantees when a crane fails (ADR-075 §12): a crane that stops keeps the no-collision guarantee
 * — nothing ever drives through it — but not the progress guarantee, so the cranes that need its rails wait and say
 * why, everything else goes on, and everything finishes once it turns again. A crane whose dock was cut away may
 * finish the job it carries but is never sent beyond the cut.
 */
class TrafficFailureTest {
    @Test
    void aCraneThatStopsMidRouteIsAnObstacleUntilItTurnsAgain() {
        int runs = 0;
        int doneMeanwhile = 0;
        for (TestNetworks.Shape shape : TestNetworks.all()) {
            Random seeds = new Random(20261028L + shape.name().hashCode());
            for (int run = 0; run < 60; run++) {
                long seed = seeds.nextLong();
                int cranes = 2 + new Random(seed).nextInt(shape.cranes() - 1);
                TrafficSimulator simulator = new TrafficSimulator(shape, cranes, seed, false);
                long from = 20 + seeds.nextInt(60);
                simulator.outage(1 + seeds.nextInt(cranes), from, from + 50 + seeds.nextInt(150));
                simulator.run(12, 0.15, 1_500, 40, 30_000);
                doneMeanwhile += simulator.jobsDoneDuringOutage;
                runs++;
            }
        }
        assertTrue(doneMeanwhile > runs, "the other cranes go on working while one is stopped: " + doneMeanwhile
                + " jobs in " + runs + " outages");
    }

    @Test
    void aStoppedCraneKeepsItsWholeClaimAndNamesItself() {
        TestNetworks.Shape line = TestNetworks.line(8);
        TrafficLedger ledger = new TrafficLedger(line.graph(), line.routes(), 0.0);
        TrafficCrane one = new TrafficCrane(1, 0, 0, true, TrafficHealth.HEALTHY,
                Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 5)), line.dock(1), TrackGraph.NONE);
        TrafficCrane two = new TrafficCrane(2, 0, 9, true, TrafficHealth.HEALTHY, Optional.empty(), line.dock(2),
                TrackGraph.NONE);
        ledger.pass(1, List.of(one, two));
        BitSet granted = ledger.claim(1);

        // Crane 1 loses its rotation between blocks 2 and 3, on its way.
        TrafficCrane stopped = one.at(0, 2.5).withHealth(TrafficHealth.STOPPED);
        ledger.pass(2, List.of(stopped, two.wanting(Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 4)))));
        assertEquals(granted, ledger.claim(1), "its route stays reserved: it will drive it once it turns again");
        TrafficVerdict.Hold hold = assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(2));
        assertEquals(new TrafficWait(TrafficWait.Reason.CRANE_STOPPED, 1, line.cell(5, 0)), hold.waiting());

        ledger.pass(3, List.of(stopped, two.wanting(Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 6)))));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(2), "a stop beyond its claim is not held up");

        ledger.pass(4, List.of(one.at(0, 2.5), two.at(0, 6)));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(1), "turning again, it drives on");
        assertEquals(TrafficLedger.Gate.NORMAL, ledger.gate(1, 0, 2.5, true, 0, 5, null));
    }

    /**
     * Two aisles the rails do not join, the way a closed rail leaves a warehouse: crane 2's dock is at the end of the
     * second, its machine stands on the first.
     */
    @Test
    void aStrandedCraneFinishesInsideTheWarehouseButIsNeverSentBeyondTheCut() {
        NetworkGeometry split = new NetworkGeometry(List.of(BranchGeometry.first(Heading.EAST, 6),
                new BranchGeometry(1, 0, 2, Heading.EAST, 6)), TestNetworks.HEIGHT);
        TrackGraph graph = TrackGraph.of(split, List.of(new DockSite(6, 2, Heading.WEST, 1, 6)));
        TrafficLedger ledger = new TrafficLedger(graph, RouteTable.of(split), 0.0);
        int[] docks = graph.docks();
        TrafficCrane one = new TrafficCrane(1, 0, 0, true, TrafficHealth.HEALTHY, Optional.empty(), docks[0],
                TrackGraph.NONE);
        TrafficCrane stranded = new TrafficCrane(2, 0, 4, true, TrafficHealth.HEALTHY,
                Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 0, 6)), docks[1], TrackGraph.NONE);
        ledger.pass(1, List.of(one, stranded));
        assertInstanceOf(TrafficVerdict.Granted.class, ledger.verdict(2), "the job it carries ends on its side");

        ledger.pass(2, List.of(one, stranded.at(0, 6)
                .wanting(Optional.of(new TrafficWant(TrafficWant.Kind.JOB, 1, 3)))));
        assertEquals(TrafficWait.Reason.NO_PATH,
                assertInstanceOf(TrafficVerdict.Hold.class, ledger.verdict(2)).waiting().reason(),
                "nothing beyond the cut");
        assertEquals(0, ledger.topCrane(), "and a stranded crane's request never holds the others up");
    }
}
