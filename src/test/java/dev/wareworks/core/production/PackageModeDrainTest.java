package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * The drain rule of package mode (issue #40, owner decision E2): which loose results a package-mode warehouse still lets
 * in for production orders that were open when the world loaded, and how many; and that the allowance shrinks with
 * every item let through, whether or not the order is still credited (review fix, 2026-11-22).
 */
class PackageModeDrainTest {
    private static final String PLANK = "plank";
    private static final String STICK = "stick";
    private static final String LOG = "log";
    private static final String WATER = "water_bucket";
    private static final String BUCKET = "bucket";
    private static final String CONCRETE = "concrete";
    private static final Function<String, Optional<String>> EMPTY_OF =
            key -> WATER.equals(key) ? Optional.of(BUCKET) : Optional.empty();

    private final PackageModeDrain<String> drain = new PackageModeDrain<>();

    /** An order for {@code amount} results out of 4 logs, {@code delivered} of them at the machine. */
    private static ProductionOrder<String, String> order(String result, int amount, ProductionOrderState state,
            int delivered, long produced) {
        return order(result, amount, state, delivered, produced, false);
    }

    private static ProductionOrder<String, String> order(String result, int amount, ProductionOrderState state,
            int delivered, long produced, boolean restock) {
        SupplyLine<String> line = new SupplyLine<>(UUID.randomUUID(), LOG, 4, delivered, Optional.empty());
        return new ProductionOrder<>(UUID.randomUUID(), "station", result, amount, List.of(line), state, 1000L,
                produced, 0L, Optional.empty(), 0L, restock, Optional.empty());
    }

    @Test
    void makeableFollowsWhatReachedTheMachine() {
        assertEquals(0L, PackageModeDrain.makeable(order(PLANK, 16, ProductionOrderState.WAITING_FOR_INGREDIENTS, 0,
                0)), "nothing delivered, nothing can come back");
        assertEquals(4L, PackageModeDrain.makeable(order(PLANK, 16, ProductionOrderState.WAITING_FOR_INGREDIENTS, 1,
                0)), "one log of four: one run of four planks");
        assertEquals(16L, PackageModeDrain.makeable(order(PLANK, 16, ProductionOrderState.DELIVERED, 4, 0)));
        // Two ingredients: the scarcer one decides, a partial run makes nothing.
        SupplyLine<String> sticks = new SupplyLine<>(UUID.randomUUID(), STICK, 4, 4, Optional.empty());
        SupplyLine<String> coal = new SupplyLine<>(UUID.randomUUID(), "coal", 4, 1, Optional.empty());
        var torches = new ProductionOrder<>(UUID.randomUUID(), "station", "torch", 16, List.of(sticks, coal),
                ProductionOrderState.WAITING_FOR_INGREDIENTS, 1000L, 0L, 0L, Optional.empty(), 0L, false,
                Optional.empty());
        assertEquals(4L, PackageModeDrain.makeable(torches), "one coal of four");
        // A choice paid with two kinds counts as one ingredient.
        SupplyLine<String> oak = new SupplyLine<>(UUID.randomUUID(), "oak", 4, 4, Optional.of("#planks"));
        SupplyLine<String> birch = new SupplyLine<>(UUID.randomUUID(), "birch", 4, 0, Optional.of("#planks"));
        var chest = new ProductionOrder<>(UUID.randomUUID(), "station", "chest", 1, List.of(oak, birch),
                ProductionOrderState.WAITING_FOR_INGREDIENTS, 1000L, 0L, 0L, Optional.empty(), 0L, false,
                Optional.empty());
        assertEquals(0L, PackageModeDrain.makeable(chest), "four of eight planks make no chest");
    }

    @Test
    void anOpenOrderLetsInWhatItsDeliveriesPayForAndHasNotCounted() {
        var open = order(PLANK, 16, ProductionOrderState.WAITING_FOR_RESULT, 4, 6);
        assertEquals(10L, drain.owedResult(open));
        assertEquals(Map.of(PLANK, 10L), drain.allowance(List.of(open), EMPTY_OF));
    }

    /** Review MAJOR 1: an order that waits for its ingredients lets nothing in, whatever kind of order it is. */
    @Test
    void anOrderWithNothingAtItsMachineLetsNothingIn() {
        var restock = order(PLANK, 16, ProductionOrderState.WAITING_FOR_INGREDIENTS, 0, 0, true);
        var player = order(PLANK, 16, ProductionOrderState.WAITING_FOR_INGREDIENTS, 0, 0, false);
        assertTrue(drain.allowance(List.of(restock, player), EMPTY_OF).isEmpty());
        assertEquals(0L, drain.remaining(List.of(restock, player), PLANK, EMPTY_OF));
    }

    @Test
    void ordersOfOneResultAreSummed() {
        var first = order(PLANK, 8, ProductionOrderState.DELIVERED, 4, 0);
        var second = order(PLANK, 4, ProductionOrderState.DELIVERED, 4, 1);
        var sticks = order(STICK, 4, ProductionOrderState.WAITING_FOR_RESULT, 4, 0);
        assertEquals(Map.of(PLANK, 11L, STICK, 4L), drain.allowance(List.of(first, second, sticks), EMPTY_OF));
        assertEquals(11L, drain.remaining(List.of(first, second, sticks), PLANK, EMPTY_OF));
    }

    @Test
    void aCompletedOrderAndOneThatGaveUpBeforeItsMachineGotAnythingAllowNothing() {
        var complete = order(PLANK, 8, ProductionOrderState.COMPLETE, 4, 8);
        var cancelledEarly = order(PLANK, 8, ProductionOrderState.CANCELLED, 0, 0);
        var timedOutEarly = order(PLANK, 8, ProductionOrderState.TIMED_OUT, 0, 0);
        assertTrue(drain.allowance(List.of(complete, cancelledEarly, timedOutEarly), EMPTY_OF).isEmpty());
    }

    /** Critique M4: a machine that was given ingredients may still finish them after its order was given up. */
    @Test
    void anOrderThatEndedWithIngredientsAtItsMachineStillLetsItsResultsBack() {
        var cancelled = order(PLANK, 8, ProductionOrderState.CANCELLED, 2, 3);
        var timedOut = order(STICK, 4, ProductionOrderState.TIMED_OUT, 4, 0);
        assertEquals(Map.of(PLANK, 1L, STICK, 4L), drain.allowance(List.of(cancelled, timedOut), EMPTY_OF),
                "two logs of four make four planks, three of them already counted");
    }

    /**
     * Review MAJOR 1 and inert-api 4: an order that no longer counts arrivals (it ended) is booked by the ledger, so its
     * allowance shrinks with every item let through and loose items beyond its shortfall are no longer let in.
     */
    @Test
    void whatWasLetThroughForAnEndedOrderIsBookedAgainstIt() {
        var timedOut = order(PLANK, 16, ProductionOrderState.TIMED_OUT, 4, 6);
        List<ProductionOrder<String, String>> orders = List.of(timedOut);
        assertEquals(10L, drain.remaining(orders, PLANK, EMPTY_OF));
        assertEquals(0L, drain.consume(orders, PLANK, 7, EMPTY_OF));
        assertEquals(3L, drain.remaining(orders, PLANK, EMPTY_OF), "the order never counts them, the ledger does");
        assertEquals(5L, drain.consume(orders, PLANK, 8, EMPTY_OF), "five more than it waited for");
        assertEquals(0L, drain.remaining(orders, PLANK, EMPTY_OF));
        assertTrue(drain.allowance(orders, EMPTY_OF).isEmpty());
    }

    /** An open order is booked as well as credited, and the two never count one arrival twice. */
    @Test
    void anOpenOrderBookedThenCreditedCountsTheArrivalOnce() {
        var open = order(PLANK, 16, ProductionOrderState.WAITING_FOR_RESULT, 4, 0);
        assertEquals(0L, drain.consume(List.of(open), PLANK, 4, EMPTY_OF));
        assertEquals(12L, drain.owedResult(open), "booked before it is credited");
        var credited = open.withStored(4, 2000L, 6000L);
        assertEquals(4L, credited.produced());
        assertEquals(12L, drain.owedResult(credited), "the credit of the same four changes nothing");
        var creditedByAnother = credited.withStored(6, 2001L, 6000L);
        assertEquals(6L, drain.owedResult(creditedByAnother), "a credit the ledger never saw still counts");
    }

    /** Review minor 4: a filled container's empty form comes back, one per delivered container. */
    @Test
    void theEmptyContainersOfDeliveredFilledContainersComeBack() {
        SupplyLine<String> water = new SupplyLine<>(UUID.randomUUID(), WATER, 3, 2, Optional.empty());
        SupplyLine<String> powder = new SupplyLine<>(UUID.randomUUID(), "powder", 3, 3, Optional.empty());
        var mixing = new ProductionOrder<>(UUID.randomUUID(), "station", CONCRETE, 3, List.of(water, powder),
                ProductionOrderState.COMPLETE, 1000L, 3L, 0L, Optional.empty(), 0L, false, Optional.empty());
        List<ProductionOrder<String, String>> orders = List.of(mixing);
        assertEquals(Map.of(BUCKET, 2L), drain.allowance(orders, EMPTY_OF), "even after the order completed");
        assertEquals(0L, drain.consume(orders, BUCKET, 2, EMPTY_OF));
        assertTrue(drain.allowance(orders, EMPTY_OF).isEmpty(), "and only as many as went in full");
    }

    /** Review minor 4: a forgotten order keeps its rest until it is used up. */
    @Test
    void aForgottenOrderKeepsItsRestUntilItIsUsedUp() {
        var cancelled = order(PLANK, 16, ProductionOrderState.CANCELLED, 4, 6);
        drain.consume(List.of(cancelled), PLANK, 2, EMPTY_OF);
        drain.forget(List.of(cancelled), Set.of(), EMPTY_OF);
        List<ProductionOrder<String, String>> none = List.of();
        assertEquals(Map.of(PLANK, 8L), drain.allowance(none, EMPTY_OF), "the rest outlives the order");
        assertEquals(0L, drain.consume(none, PLANK, 5, EMPTY_OF));
        assertEquals(3L, drain.remaining(none, PLANK, EMPTY_OF));
        assertEquals(1L, drain.consume(none, PLANK, 4, EMPTY_OF));
        assertTrue(drain.isEmpty(), "used up, nothing is kept");
    }

    @Test
    void anOrderThatOwedNothingLeavesNoRestAndItsBookingsGo() {
        var complete = order(PLANK, 4, ProductionOrderState.COMPLETE, 4, 4);
        var open = order(STICK, 4, ProductionOrderState.DELIVERED, 4, 0);
        drain.consume(List.of(open), STICK, 1, EMPTY_OF);
        drain.forget(List.of(complete), Set.of(), EMPTY_OF);
        assertTrue(drain.isEmpty(), "nothing left for the complete order, the open one's booking dropped");
    }

    @Test
    void roomSubtractsWhatTheInputAlreadyHoldsAndNeverGoesNegative() {
        Map<String, Long> allowance = Map.of(PLANK, 10L);
        assertEquals(10L, PackageModeDrain.room(allowance, PLANK, 0));
        assertEquals(3L, PackageModeDrain.room(allowance, PLANK, 7));
        assertEquals(0L, PackageModeDrain.room(allowance, PLANK, 12));
        assertEquals(0L, PackageModeDrain.room(allowance, STICK, 0), "nothing for a key no order makes");
        assertEquals(10L, PackageModeDrain.room(allowance, PLANK, -5), "a negative count reads as none");
    }

    @Test
    void noOrdersNoAllowance() {
        assertTrue(drain.allowance(List.<ProductionOrder<String, String>>of(), EMPTY_OF).isEmpty());
    }
}
