package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * The route table: the shape's corner blocks derived once, the cost of a route without building it, and — the reason
 * it exists — the one definition of "the machine can get there" (M21 review fix, ADR-033).
 */
class RouteTableTest {
    private static final double PENALTY = 1.0;
    private static final double EPSILON = 1.0E-9;

    /** An L: 8 east from the dock, then 5 south from the corner at {@code (8, 0)}. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5)), 6);

    /** The same L after a player broke a rail out of the middle of the second aisle: it ends after two blocks. */
    private static final NetworkGeometry SHORTENED = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 2)), 6);

    /** A U: the L, and 8 west from {@code (8, 5)} back alongside the first aisle. */
    private static final NetworkGeometry U_SHAPE = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5),
            new BranchGeometry(2, 8, 5, Heading.WEST, 8)), 6);

    // --- the links are the shape's, derived once ------------------------------------------------------------------

    @Test
    void aTableCarriesExactlyTheLinksOfItsShape() {
        RouteTable table = RouteTable.of(U_SHAPE);
        assertEquals(U_SHAPE.links(), table.links());
        assertEquals(U_SHAPE.branchCount(), table.branchCount());
        assertSameAnswers(table, U_SHAPE);
    }

    // --- the cheap cost may never drift from the real route -------------------------------------------------------

    @Test
    void theCostOfARouteIsTheRoutesOwnCostForEveryPairOfPoints() {
        RouteTable table = RouteTable.of(U_SHAPE);
        for (int from = 0; from < U_SHAPE.branchCount(); from++) {
            for (int to = 0; to < U_SHAPE.branchCount(); to++) {
                for (double fromX = 0.0; fromX <= U_SHAPE.branch(from).length(); fromX += 0.5) {
                    for (double toX = 0.0; toX <= U_SHAPE.branch(to).length(); toX += 0.5) {
                        double expected = table.route(from, fromX, to, toX).orElseThrow().costBlocks(PENALTY);
                        assertEquals(expected, table.routeBlocks(from, fromX, to, toX, PENALTY).orElseThrow(),
                                EPSILON, from + "@" + fromX + " → " + to + "@" + toX);
                    }
                }
            }
        }
    }

    @Test
    void aCostWithNoRouteIsEmpty() {
        RouteTable table = RouteTable.of(CORNER);
        assertEquals(OptionalDouble.empty(), table.routeBlocks(0, 0.0, 2, 0.0, PENALTY));
        assertEquals(OptionalDouble.empty(), table.routeBlocks(0, 99.0, 1, 0.0, PENALTY),
                "a point outside its own aisle is on no route at all");
    }

    // --- what "it can get there" means ----------------------------------------------------------------------------

    @Test
    void aCraneCanAlwaysDriveAlongTheAisleItStandsOn() {
        RouteTable table = RouteTable.of(CORNER);
        assertTrue(table.canDrive(0, 7.0, 0, 1.0), "back down its own aisle");
        assertTrue(table.canDrive(1, 0.0, 1, 5.0), "and out along it");
    }

    /**
     * The blocker this whole class was written for. A rail out of the middle of aisle B leaves the crane standing
     * beyond the end of its own branch. The branch still exists and still touches the corner, so the branch-only
     * question says "reachable" — while the motion finds no route and simply stands still. Asked from the crane's own
     * point, the two agree.
     */
    @Test
    void aCraneBeyondTheEndOfItsShortenedAisleCannotDriveToAnother() {
        RouteTable table = RouteTable.of(SHORTENED);
        assertTrue(table.reachable(1, 0), "the aisle still exists and still meets the corner");
        assertFalse(table.canDrive(1, 4.0, 0, 3.0), "but no route starts where the machine really is");
        assertTrue(table.canDrive(1, 4.0, 1, 1.0), "it can only come back down the rails it is on");
        assertTrue(table.canDrive(1, 2.0, 0, 3.0), "and once it is back on them, the corner is open again");
    }

    @Test
    void aCraneOnAnAisleTheWarehouseDoesNotHaveCanDriveNowhere() {
        RouteTable table = RouteTable.of(CORNER);
        assertFalse(table.canDrive(2, 0.0, 0, 0.0));
        assertFalse(table.canDrive(2, 0.0, 2, 3.0), "not even along the aisle it thinks it is on");
        assertFalse(table.canDrive(-1, 0.0, 0, 0.0));
    }

    @Test
    void everyPairOfAislesOfAConnectedWarehouseCanBeDrivenBothWays() {
        RouteTable table = RouteTable.of(U_SHAPE);
        for (int from = 0; from < U_SHAPE.branchCount(); from++) {
            for (int to = 0; to < U_SHAPE.branchCount(); to++)
                assertTrue(table.canDrive(from, 1.0, to, 1.0), from + " → " + to);
        }
    }

    private static void assertSameAnswers(RouteTable table, NetworkGeometry network) {
        for (int from = 0; from < network.branchCount(); from++) {
            for (int to = 0; to < network.branchCount(); to++) {
                assertEquals(RouteModel.reachable(network, from, to), table.reachable(from, to));
                assertEquals(RouteModel.route(network, from, 1.0, to, 2.0), table.route(from, 1.0, to, 2.0));
            }
        }
    }
}
