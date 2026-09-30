package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.warehouse.RouteModel;

/**
 * What driving costs the planner ({@link PlannerInput.TravelCost}, M21, ADR-033): blocks plus quarter turns, and
 * literally the old formula on a warehouse that does not bend.
 */
class TravelCostTest {
    private static final String IRON = "iron";
    private static final int STACK = 64;
    private static final int TRANSFER_TICKS = 10;
    private static final double PENALTY = TravelTimeModel.DEFAULT_TURN_PENALTY_BLOCKS;
    private static final double EPSILON = 1.0E-9;
    /** Four ticks per block on both axes. */
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    /** An L: 8 rails east from the dock, then 5 south from the corner block. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5)), 6);

    private static final RackPosition INPUT = new RackPosition(0, 0, 0, Side.RIGHT);
    /** Six blocks straight down the first aisle. */
    private static final RackPosition NEAR_BY_ROUTE = new RackPosition(0, 6, 0, Side.LEFT);
    /** Position 1 of the second aisle: nearer in raw numbers, ten blocks away round the corner. */
    private static final RackPosition NEAR_BY_NUMBER = new RackPosition(1, 1, 0, Side.LEFT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Map<RackPosition, Integer> insertable = new HashMap<>();

    // --- the cost function ---------------------------------------------------------------------------------------

    @Test
    void theDefaultCostIsTheFormulaASingleAisleAlwaysUsed() {
        PlannerInput<String, RackPosition> input = PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(3, 2).speeds(SPEEDS).carryLimit(key -> STACK).build();
        assertEquals(RackPosition.FIRST_BRANCH, input.craneBranch());
        for (double[] trip : new double[][] { { 0, 0, 7, 3 }, { 7, 3, 0, 0 }, { 2, 1, 2, 1 }, { 4, 0, 4, 9 } }) {
            assertEquals(TravelTimeModel.travelTicks(SPEEDS, trip[0], trip[1], trip[2], trip[3]),
                    input.travel().travelTicks(0, trip[0], trip[1], 0, trip[2], trip[3]));
            assertEquals(TravelTimeModel.travelTicks(SPEEDS, trip[0], trip[1], trip[2], trip[3]),
                    input.travel().travelTicks(3, trip[0], trip[1], 7, trip[2], trip[3]),
                    "the aisle is ignored, exactly as it was before M21");
        }
    }

    @Test
    void aRouteCostsItsBlocksPlusItsTurns() {
        assertEquals(6.0, TravelTimeModel.routeBlocks(6.0, 0, PENALTY), EPSILON);
        assertEquals(7.0, TravelTimeModel.routeBlocks(6.0, 1, PENALTY), EPSILON);
        assertEquals(8.0, TravelTimeModel.routeBlocks(6.0, 2, PENALTY), EPSILON);
        assertEquals(6.0, TravelTimeModel.routeBlocks(6.0, 2, 0.0), EPSILON, "a free turn costs nothing");
        assertEquals(14.0, TravelTimeModel.routeBlocks(6.0, 2, 4.0), EPSILON);
        assertEquals(6.0, TravelTimeModel.routeBlocks(-6.0, 0, PENALTY), EPSILON, "a route has no direction");
    }

    @Test
    void aRouteWithoutTurnsTakesTheTicksItAlwaysTook() {
        assertEquals(TravelTimeModel.travelTicks(SPEEDS, 2.0, 1.0, 9.0, 4.0),
                TravelTimeModel.travelAlongTicks(SPEEDS, TravelTimeModel.routeBlocks(7.0, 0, PENALTY), 1.0, 4.0));
    }

    @Test
    void aTurnAddsItsBlocksToTheTrip() {
        long straight = TravelTimeModel.travelAlongTicks(SPEEDS, TravelTimeModel.routeBlocks(7.0, 0, PENALTY),
                0.0, 0.0);
        long cornered = TravelTimeModel.travelAlongTicks(SPEEDS, TravelTimeModel.routeBlocks(7.0, 1, PENALTY),
                0.0, 0.0);
        assertEquals(TravelTimeModel.ticksToCover(1.0, SPEEDS.vx()), cornered - straight);
    }

    @Test
    void anImpossibleRouteIsRefusedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.routeBlocks(1.0, -1, PENALTY));
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.routeBlocks(1.0, 1, -1.0));
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.routeBlocks(Double.NaN, 0, PENALTY));
    }

    // --- what it changes for the planner -------------------------------------------------------------------------

    @Test
    void theCraneIsSentToTheRackItCanReachSoonestAndNotToTheNearerNumber() {
        insertable.put(NEAR_BY_ROUTE, STACK);
        insertable.put(NEAR_BY_NUMBER, STACK);
        // Listed so that the location round the corner would win on index order alone.
        List<RackPosition> locations = List.of(NEAR_BY_NUMBER, NEAR_BY_ROUTE);

        PlanResult<String, RackPosition> byNumber = planner.plan(input(locations).build());
        assertEquals(NEAR_BY_NUMBER, target(byNumber),
                "without a network the planner only sees the position along an aisle, as it did before M21");

        PlanResult<String, RackPosition> byRoute = planner.plan(input(locations).travel(routeCost()).build());
        assertEquals(NEAR_BY_ROUTE, target(byRoute),
                "with the real route, six blocks straight ahead beat eight blocks, a turn and one more");
    }

    @Test
    void theEstimatedTicksOfAJobCountTheTurnsItHasToMake() {
        insertable.put(NEAR_BY_NUMBER, STACK);
        PlanResult<String, RackPosition> result = planner.plan(
                input(List.of(NEAR_BY_NUMBER)).travel(routeCost()).build());
        long toInput = TravelTimeModel.travelAlongTicks(SPEEDS, 0.0, 0.0, 0.0);
        long toTarget = TravelTimeModel.travelAlongTicks(SPEEDS,
                RouteModel.route(CORNER, 0, 0.0, 1, 1.0).orElseThrow().costBlocks(PENALTY), 0.0, 0.0);
        long stop = TravelTimeModel.stopTicks(SPEEDS, TRANSFER_TICKS);
        assertEquals(toInput + stop + toTarget + stop, result.job().orElseThrow().estimatedTicks());
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** The travel cost a warehouse with corners hands the planner: the route's blocks and its quarter turns. */
    private static PlannerInput.TravelCost routeCost() {
        return (fromBranch, fromX, fromY, toBranch, toX, toY) -> RouteModel
                .routeBlocks(CORNER, fromBranch, fromX, toBranch, toX, PENALTY)
                .stream()
                .mapToLong(blocks -> TravelTimeModel.travelAlongTicks(SPEEDS, blocks, fromY, toY))
                .findFirst()
                .orElse(TravelTimeModel.UNAVAILABLE);
    }

    private PlannerInput.Builder<String, RackPosition> input(List<RackPosition> storage) {
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(RackPosition.FIRST_BRANCH, 0, 0)
                .speeds(SPEEDS)
                .transferTicks(TRANSFER_TICKS)
                .carryLimit(key -> STACK)
                .storageLocations(storage)
                .inputs(List.of(INPUT))
                .inputBuffers(rack -> rack.equals(INPUT) ? buffer() : InventorySnapshot.empty())
                .liveExtract((rack, key, max) -> rack.equals(INPUT) ? Math.min(max, STACK) : 0)
                .liveInsert((rack, key, amount) -> Math.min(amount, insertable.getOrDefault(rack, 0)));
    }

    private static InventorySnapshot<String> buffer() {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(1);
        return builder.add(IRON, STACK, STACK, STACK).build();
    }

    private static RackPosition target(PlanResult<String, RackPosition> result) {
        return result.job().map(job -> job.job().target()).orElseThrow(
                () -> new AssertionError("no job planned: " + result.reasons()));
    }

    private static Supplier<UUID> sequentialIds() {
        long[] next = { 1000 };
        return () -> new UUID(0L, next[0]++);
    }
}
