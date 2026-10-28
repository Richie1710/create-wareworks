package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * The track graph of ADR-075 §3: one cell per aisle block, the two names of a link merged, every dock a dead end, and
 * deterministic searches that become routes a crane can drive.
 */
class TrackGraphTest {
    /** An L: east four, then south three; the corner is position 4 of aisle A and position 0 of aisle B. */
    private static final TestNetworks.Shape L = TestNetworks.builder().railsX(0, 1, 4).railsZ(4, 1, 3).build("L");

    @Test
    void mergesTheTwoNamesOfALinkIntoOneCell() {
        TrackGraph graph = L.graph();
        assertEquals(8, graph.cellCount(), "the dock, seven rails, and the corner counted once");
        int corner = graph.cellAt(0, 4);
        assertEquals(corner, graph.cellAt(1, 0));
        assertEquals(2, graph.nameCount(corner));
        assertEquals(4, graph.xOn(corner, 0));
        assertEquals(0, graph.xOn(corner, 1));
        assertEquals(TrackGraph.NONE, graph.xOn(graph.cellAt(0, 1), 1));
        assertEquals(TrackGraph.NONE, graph.cellAt(0, 5));
        assertEquals(TrackGraph.NONE, graph.cellAt(2, 0));
    }

    @Test
    void listsTheCellsOfARouteInDrivingOrderWithTheLinkOnce() {
        CraneRoute route = L.routes().route(1, 2, 0, 1).orElseThrow();
        TrackGraph graph = L.graph();
        assertArrayEquals(new int[] {graph.cellAt(1, 2), graph.cellAt(1, 1), graph.cellAt(1, 0), graph.cellAt(0, 3),
                graph.cellAt(0, 2), graph.cellAt(0, 1)}, graph.cellsOf(route));
    }

    @Test
    void aRouteThatStartsBetweenTwoBlocksListsBoth() {
        TrackGraph graph = L.graph();
        CraneRoute backwards = CraneRoute.straight(0, 2.5, 1, Heading.EAST);
        assertArrayEquals(new int[] {graph.cellAt(0, 3), graph.cellAt(0, 2), graph.cellAt(0, 1)},
                graph.cellsOf(backwards));
    }

    @Test
    void aChassisBetweenTwoBlocksStandsOnBoth() {
        TrackGraph graph = L.graph();
        assertArrayEquals(new int[] {graph.cellAt(0, 3), graph.cellAt(0, 4)}, graph.standingCells(0, 3.4));
        assertArrayEquals(new int[] {graph.cellAt(0, 3)}, graph.standingCells(0, 3.0));
        assertArrayEquals(new int[] {graph.cellAt(0, 4)}, graph.standingCells(0, 4.6),
                "a position beyond the rails covers only the block that exists");
        assertArrayEquals(new int[0], graph.standingCells(5, 1.0));
    }

    @Test
    void everyDockIsADeadEnd() {
        for (TestNetworks.Shape shape : TestNetworks.all()) {
            TrackGraph graph = shape.graph();
            int[] docks = graph.docks();
            assertEquals(shape.network().craneCount(), docks.length, shape + ": one dock per crane");
            for (int dock : docks) {
                assertTrue(dock >= 0, shape + ": every dock is part of the network");
                assertTrue(graph.isDock(dock));
                assertEquals(1, graph.degree(dock), shape + ": a dock connects only towards its facing");
                assertTrue(graph.isDeadEnd(dock));
            }
            for (int cell = 0; cell < graph.cellCount(); cell++)
                assertTrue(graph.connected(graph.primaryDock(), cell), shape + ": a discovered network is connected");
        }
    }

    @Test
    void escapePathsAreSimpleAvoidBlockedCellsAndBecomeRoutes() {
        for (TestNetworks.Shape shape : TestNetworks.all()) {
            TrackGraph graph = shape.graph();
            int from = shape.dock(2);
            int to = graph.primaryDock();
            int[] path = graph.path(new int[] {from}, to, null, null).orElseThrow();
            assertEquals(from, path[0]);
            assertEquals(to, path[path.length - 1]);
            BitSet seen = new BitSet();
            for (int i = 0; i < path.length; i++) {
                assertFalse(seen.get(path[i]), shape + ": a path never visits a block twice");
                seen.set(path[i]);
                if (i > 0)
                    assertNotEquals(TrackGraph.NONE, graph.edgeBranch(path[i - 1], path[i]));
            }
            int startBranch = graph.branchOf(from, 0);
            CraneRoute route = graph.routeAlong(path, startBranch, graph.xOf(from, 0), graph.branchOf(to, 0))
                    .orElseThrow();
            assertArrayEquals(path, graph.cellsOf(route), shape + ": the route drives exactly the path");
        }
    }

    @Test
    void aBlockedCellIsNeverEnteredAndADiscouragedOneAvoidedWhereItCanBe() {
        TestNetworks.Shape ring = TestNetworks.ring();
        TrackGraph graph = ring.graph();
        int start = ring.cell(1, 0);
        int goal = ring.cell(4, 3);
        BitSet top = new BitSet();
        top.set(ring.cell(3, 0));
        int[] around = graph.path(new int[] {start}, goal, top, null).orElseThrow();
        for (int cell : around)
            assertNotEquals(ring.cell(3, 0), cell, "the blocked block is never entered");
        assertEquals(ring.cell(1, 1), around[1], "so the path goes round the other way");

        BitSet discouraged = new BitSet();
        discouraged.set(ring.cell(1, 1));
        discouraged.set(ring.cell(1, 2));
        int[] preferred = graph.path(new int[] {start}, goal, null, discouraged).orElseThrow();
        assertEquals(ring.cell(2, 0), preferred[1], "the way without discouraged blocks is taken");

        BitSet walled = new BitSet();
        walled.set(ring.cell(2, 0));
        walled.set(ring.cell(1, 1));
        assertTrue(graph.path(new int[] {start}, goal, walled, null).isEmpty(), "no way at all is empty");
    }

    @Test
    void tiesBreakTheSameWayEveryTime() {
        TestNetworks.Shape ring = TestNetworks.tinyRing();
        TrackGraph graph = ring.graph();
        int start = ring.cell(1, 0);
        int goal = ring.cell(3, 2);
        int[] first = graph.path(new int[] {start}, goal, null, null).orElseThrow();
        for (int i = 0; i < 5; i++)
            assertArrayEquals(first, graph.path(new int[] {start}, goal, null, null).orElseThrow());
        assertEquals(5, first.length, "one of the two equally short ways round");
        TrackGraph again = TestNetworks.tinyRing().graph();
        assertArrayEquals(first, again.path(new int[] {start}, goal, null, null).orElseThrow(),
                "and the same one on a graph derived again from the same shape");
    }

    @Test
    void aRouteFromAPoseNamedOnTheOtherBranchOfALinkHandsOverFirst() {
        TrackGraph graph = L.graph();
        int corner = graph.cellAt(0, 4);
        int[] path = {corner, graph.cellAt(1, 1), graph.cellAt(1, 2)};
        CraneRoute route = graph.routeAlong(path, 0, 4.0, 1).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(0, 4, 4, Heading.EAST), new CraneRoute.Leg(1, 0, 2, Heading.SOUTH)),
                route.legs());
        assertTrue(graph.routeAlong(new int[] {graph.cellAt(0, 1), graph.cellAt(0, 3)}, 0, 1.0, 0).isEmpty(),
                "two blocks that are no neighbours are no path");
    }

    @Test
    void aWarehouseOfOneCraneMarksOnlyItsOwnDock() {
        TrackGraph graph = TrackGraph.of(NetworkGeometry.single(Heading.NORTH, 5, 3));
        assertArrayEquals(new int[] {graph.cellAt(0, 0)}, graph.docks());
        assertTrue(graph.isDock(graph.primaryDock()));
        assertFalse(graph.isDock(graph.cellAt(0, 5)), "the far end of a one-crane aisle is no dock");
    }
}
