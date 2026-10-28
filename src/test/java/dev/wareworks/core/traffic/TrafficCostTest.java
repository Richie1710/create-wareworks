package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;

/**
 * What one traffic pass costs at the worst case the config allows (ADR-075 §4.4): {@code aisle.maxCranesPerNetwork =
 * 16} cranes on a warehouse of about {@code aisle.maxNetworkRails = 1024} blocks, every one of them asking for a route
 * in the same tick. Measured in blocks expanded by the searches ({@link TrackGraph#visits()}), which is what a pass
 * spends its time on, and pinned so a change that makes it grow faster than the cranes times the rails fails here
 * rather than on a server.
 */
class TrafficCostTest {
    private static final int CRANES = 16;
    /**
     * Blocks expanded by one pass, at most: a request that is checked in full runs four escape checks (I3 before and
     * after, the top request's blockers before and after), each one search per crane, so the pass is bounded by
     * {@code 4 · cranes² · blocks}. The full-check pass below measures about 0.66 million of the 1.05 million.
     */
    private static final long BOUND = 4L * CRANES * CRANES * 1025;
    private static final int SPACING = 4;
    private static final int TOOTH = 62;

    /** A comb: a main run of 63 rails with fifteen teeth of 62, a member dock at the end of every tooth. */
    private static TestNetworks.Shape comb() {
        TestNetworks.Builder builder = TestNetworks.builder().railsX(0, 1, (CRANES - 1) * SPACING + 3);
        for (int tooth = 1; tooth < CRANES; tooth++) {
            int dx = tooth * SPACING;
            builder.railsZ(dx, 1, TOOTH).dock(dx, TOOTH + 1, Heading.NORTH);
        }
        return builder.build("worst-case comb");
    }

    /**
     * Crane 1 at the west end of the main run, every other crane halfway up its own tooth, each wanting the cell
     * {@code wanted} gives for its number.
     */
    private static List<TrafficCrane> cranes(TestNetworks.Shape comb, IntUnaryOperator wanted) {
        TrackGraph graph = comb.graph();
        List<TrafficCrane> cranes = new ArrayList<>();
        for (int n = 1; n <= CRANES; n++) {
            int dock = graph.docks()[n - 1];
            int at = n == 1 ? comb.cell(1, 0) : comb.cell(toothX(graph, dock), TOOTH / 2);
            int stop = wanted.applyAsInt(n);
            cranes.add(new TrafficCrane(n, graph.branchOf(at, 0), graph.xOf(at, 0), true, TrafficHealth.HEALTHY,
                    Optional.of(new TrafficWant(TrafficWant.Kind.JOB, graph.branchOf(stop, 0), graph.xOf(stop, 0))),
                    dock, TrackGraph.NONE));
        }
        return cranes;
    }

    private static long passCost(TestNetworks.Shape comb, List<TrafficCrane> cranes) {
        TrackGraph graph = comb.graph();
        TrafficLedger ledger = new TrafficLedger(graph, comb.routes(), 0.0);
        long before = graph.visits();
        ledger.pass(1, cranes);
        long spent = graph.visits() - before;
        assertTrue(spent > 0);
        assertTrue(spent <= BOUND, "the pass expanded " + spent + " blocks, more than " + BOUND);

        long tick = 2;
        while (ledger.pass(tick, cranes))
            tick++;
        long again = graph.visits();
        ledger.pass(tick + 1, cranes);
        assertEquals(again, graph.visits(), "and a pass after which nothing changed is not repeated");
        return spent;
    }

    @Test
    void aPassOfCrossingRequestsStaysUnderTheBound() {
        TestNetworks.Shape comb = comb();
        TrackGraph graph = comb.graph();
        assertEquals(CRANES, graph.docks().length);
        assertTrue(graph.cellCount() > 1000 && graph.cellCount() <= 1025, "cells: " + graph.cellCount());
        // Every crane wants the far end of the next tooth: most requests meet another crane's claim early.
        passCost(comb, cranes(comb, n -> comb.cell(((n % (CRANES - 1)) + 1) * SPACING, TOOTH - 1)));
    }

    @Test
    void aPassInWhichEveryRequestIsCheckedInFullStaysUnderTheBound() {
        TestNetworks.Shape comb = comb();
        // Crane 1 waits for the rails crane 2 stands on, so it is the top request and every other request is checked
        // against it in full: its own rails are free, so each one runs every escape check there is.
        passCost(comb, cranes(comb, n -> n == 1 ? comb.cell(SPACING, TOOTH - 10)
                : comb.cell(n * SPACING - SPACING, TOOTH - 10)));
    }

    /** The X of a tooth's dock: the tooth's own X. */
    private static int toothX(TrackGraph graph, int dock) {
        return graph.network().branch(graph.branchOf(dock, 0)).originDx();
    }
}
