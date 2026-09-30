package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;

/** Routes through a rail network, and what they cost in blocks (M21, ADR-033). */
class RouteModelTest {
    private static final double PENALTY = 1.0;
    private static final double EPSILON = 1.0E-9;

    /** One straight aisle of 12 rails running east from the dock: every warehouse before M21. */
    private static final NetworkGeometry STRAIGHT = NetworkGeometry.single(Heading.EAST, 12, 6);

    /** An L: 8 east from the dock, then 5 south from the corner at {@code (8, 0)}. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5)), 6);

    /** A U: the L, and 8 west from {@code (8, 5)} back alongside the first aisle. */
    private static final NetworkGeometry U_SHAPE = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5),
            new BranchGeometry(2, 8, 5, Heading.WEST, 8)), 6);

    /** Two aisles that never touch — the warehouse a player broke a rail out of. */
    private static final NetworkGeometry SPLIT = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 20, 20, Heading.SOUTH, 5)), 6);

    // --- one branch: exactly what it was before M21 --------------------------------------------------------------

    @Test
    void aRouteOnOneAisleIsOneLegAndCostsItsDistance() {
        CraneRoute route = RouteModel.route(STRAIGHT, 0, 2.0, 0, 9.0).orElseThrow();
        assertEquals(1, route.legCount());
        assertTrue(route.isStraight());
        assertEquals(0, route.turns());
        assertEquals(new CraneRoute.Leg(0, 2.0, 9.0, Heading.EAST), route.firstLeg());
        assertEquals(7.0, route.blocks(), EPSILON);
        assertEquals(7.0, route.costBlocks(PENALTY), EPSILON, "no turn, so the penalty cannot change anything");
        assertEquals(7.0, route.costBlocks(0.0), EPSILON);
    }

    @Test
    void aRouteBackwardsAlongItsAisleIsStillOneLeg() {
        CraneRoute route = RouteModel.route(STRAIGHT, 0, 9.0, 0, 2.0).orElseThrow();
        assertEquals(1, route.legCount(), "driving an aisle backwards is not a turn");
        assertEquals(Heading.EAST, route.firstLeg().heading(), "the machine still faces the way the aisle runs");
        assertEquals(7.0, route.blocks(), EPSILON);
    }

    @Test
    void aRouteToWhereTheCraneStandsCostsNothing() {
        CraneRoute route = RouteModel.route(STRAIGHT, 0, 4.0, 0, 4.0).orElseThrow();
        assertEquals(0.0, route.costBlocks(PENALTY), EPSILON);
    }

    // --- one corner ----------------------------------------------------------------------------------------------

    @Test
    void aRouteAroundOneCornerHandsOverOnTheSharedBlock() {
        CraneRoute route = RouteModel.route(CORNER, 0, 2.0, 1, 4.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(0, 2.0, 8.0, Heading.EAST),
                new CraneRoute.Leg(1, 0.0, 4.0, Heading.SOUTH)), route.legs());
        assertEquals(1, route.turns());
        assertEquals(10.0, route.blocks(), EPSILON, "6 along the first aisle, 4 along the second");
        assertEquals(0, route.fromBranch());
        assertEquals(1, route.toBranch());
    }

    @Test
    void aQuarterTurnCostsTheConfiguredBlocks() {
        CraneRoute route = RouteModel.route(CORNER, 0, 2.0, 1, 4.0).orElseThrow();
        assertEquals(10.0, route.costBlocks(0.0), EPSILON, "turnPenaltyBlocks = 0 is a free, instant turn");
        assertEquals(11.0, route.costBlocks(1.0), EPSILON);
        assertEquals(14.0, route.costBlocks(4.0), EPSILON);
        assertEquals(OptionalDouble.of(11.0), RouteModel.routeBlocks(CORNER, 0, 2.0, 1, 4.0, 1.0));
    }

    @Test
    void theCornerBlockItselfIsNamedOnBothAisles() {
        CraneRoute fromFirst = RouteModel.route(CORNER, 0, 0.0, 1, 0.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(0, 0.0, 8.0, Heading.EAST),
                new CraneRoute.Leg(1, 0.0, 0.0, Heading.SOUTH)), fromFirst.legs());
        assertEquals(8.0, fromFirst.blocks(), EPSILON, "the hand-over moves the machine by nothing at all");
        assertEquals(9.0, fromFirst.costBlocks(PENALTY), EPSILON, "but it still has to turn to face the new aisle");
    }

    @Test
    void aCraneStandingOnTheCornerDrivesOffWithoutRetracingAnything() {
        CraneRoute route = RouteModel.route(CORNER, 1, 0.0, 0, 3.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(1, 0.0, 0.0, Heading.SOUTH),
                new CraneRoute.Leg(0, 8.0, 3.0, Heading.EAST)), route.legs());
        assertEquals(5.0, route.blocks(), EPSILON);
        assertEquals(6.0, route.costBlocks(PENALTY), EPSILON);
    }

    // --- several corners -----------------------------------------------------------------------------------------

    @Test
    void aUShapedWarehouseCostsBothItsTurns() {
        CraneRoute route = RouteModel.route(U_SHAPE, 0, 0.0, 2, 8.0).orElseThrow();
        assertEquals(3, route.legCount());
        assertEquals(2, route.turns());
        assertEquals(21.0, route.blocks(), EPSILON, "8 east, 5 south, 8 west");
        assertEquals(23.0, route.costBlocks(PENALTY), EPSILON);
        assertEquals(Heading.WEST, route.lastLeg().heading());
    }

    @Test
    void everyLegOfARouteIsPerpendicularToTheOneBefore() {
        CraneRoute route = RouteModel.route(U_SHAPE, 0, 1.0, 2, 7.0).orElseThrow();
        for (int i = 1; i < route.legCount(); i++) {
            assertTrue(route.leg(i - 1).heading().isPerpendicularTo(route.leg(i).heading()),
                    "leg " + i + " of " + route);
            assertTrue(route.leg(i - 1).branch() != route.leg(i).branch());
        }
    }

    // --- no route ------------------------------------------------------------------------------------------------

    @Test
    void thereIsNoRouteBetweenTwoAislesThatDoNotTouch() {
        assertEquals(Optional.empty(), RouteModel.route(SPLIT, 0, 0.0, 1, 2.0));
        assertEquals(OptionalDouble.empty(), RouteModel.routeBlocks(SPLIT, 0, 0.0, 1, 2.0, PENALTY));
        assertFalse(RouteModel.reachable(SPLIT, 0, 1));
        assertTrue(RouteModel.reachable(SPLIT, 1, 1), "an aisle always reaches itself");
    }

    @Test
    void thereIsNoRouteToAnAisleTheWarehouseDoesNotHave() {
        assertEquals(Optional.empty(), RouteModel.route(CORNER, 0, 0.0, 5, 0.0));
        assertEquals(Optional.empty(), RouteModel.route(CORNER, -1, 0.0, 1, 0.0));
        assertFalse(RouteModel.reachable(CORNER, 0, 7));
    }

    @Test
    void thereIsNoRouteFromOrToAPositionOutsideItsAisle() {
        assertEquals(Optional.empty(), RouteModel.route(CORNER, 1, 0.0, 1, 5.5),
                "the second aisle is 5 long, so 5.5 is not a position of it");
        assertEquals(Optional.empty(), RouteModel.route(CORNER, 0, -0.5, 0, 2.0));
        assertEquals(Optional.empty(), RouteModel.route(CORNER, 0, Double.NaN, 0, 2.0));
    }

    @Test
    void everyPairOfAislesOfAConnectedWarehouseHasARouteBothWays() {
        for (int from = 0; from < U_SHAPE.branchCount(); from++) {
            for (int to = 0; to < U_SHAPE.branchCount(); to++) {
                assertTrue(RouteModel.reachable(U_SHAPE, from, to), from + " → " + to);
                assertTrue(RouteModel.route(U_SHAPE, from, 0.0, to, 0.0).isPresent(), from + " → " + to);
            }
        }
    }

    // --- the cost function's own guards --------------------------------------------------------------------------

    @Test
    void aNegativeTurnPenaltyIsRefused() {
        CraneRoute route = RouteModel.route(CORNER, 0, 2.0, 1, 4.0).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> route.costBlocks(-1.0));
        assertThrows(IllegalArgumentException.class, () -> route.costBlocks(Double.NaN));
    }

    @Test
    void aRouteNeverDoublesBackOntoOneAisle() {
        assertThrows(IllegalArgumentException.class, () -> CraneRoute.of(
                new CraneRoute.Leg(0, 0.0, 2.0, Heading.EAST),
                new CraneRoute.Leg(0, 2.0, 4.0, Heading.EAST)));
        assertThrows(IllegalArgumentException.class, () -> CraneRoute.of(
                new CraneRoute.Leg(0, 0.0, 2.0, Heading.EAST),
                new CraneRoute.Leg(1, 2.0, 4.0, Heading.WEST)));
        assertThrows(IllegalArgumentException.class, () -> new CraneRoute(List.of()));
    }
}
