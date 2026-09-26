package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * The production order state machine ({@code docs/warehouse-system.md} §3.5, ADR-024): collecting ingredients,
 * handing them to the machine, counting what comes back, and the two ways an order can end without producing
 * anything.
 */
class ProductionOrderTest {
    private static final String LOG = "log";
    private static final String NAIL = "nail";
    private static final String PLANK = "plank";
    private static final String STATION = "station";
    private static final long TIMEOUT = 100L;
    private static final long START = 1000L;

    private final Supplier<UUID> lineIds = sequentialIds();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1};
        return () -> new UUID(0L, next[0]++);
    }

    private ProductionOrder<String, String> order(ProductionPattern<String> pattern, int runs, long stockNow) {
        return ProductionOrder.start(UUID.randomUUID(), STATION, pattern, runs, lineIds, START, TIMEOUT, stockNow,
                null);
    }

    private static ProductionPattern<String> planks() {
        return ProductionPattern.of(LOG, 1, PLANK, 4);
    }

    private static ProductionPattern<String> twoIngredients() {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(LOG, 2), new ProductionEntry<>(NAIL, 3)),
                new ProductionEntry<>(PLANK, 1));
    }

    @Test
    void aNewOrderOwesItsIngredientsAndWaitsForThem() {
        ProductionOrder<String, String> order = order(planks(), 3, 0L);
        assertEquals(ProductionOrderState.WAITING_FOR_INGREDIENTS, order.state());
        assertTrue(order.isOpen());
        assertEquals(12, order.resultAmount(), "3 runs of 4 planks");
        assertEquals(3, order.outstanding(LOG), "3 runs of one log");
        assertEquals(3, order.outstandingIngredients());
        assertEquals(12L, order.outstandingResult());
        assertEquals(12L, order.unproducedAmount());
        assertFalse(order.allIngredientsDelivered());
    }

    @Test
    void everyIngredientLineHasItsOwnId() {
        ProductionOrder<String, String> order = order(twoIngredients(), 2, 0L);
        assertEquals(2, order.lines().size());
        SupplyLine<String> logs = order.lines().getFirst();
        SupplyLine<String> nails = order.lines().get(1);
        assertFalse(logs.id().equals(nails.id()), "one id per ingredient, so each is tracked on its own");
        assertEquals(4, logs.required(), "2 runs of 2 logs");
        assertEquals(6, nails.required(), "2 runs of 3 nails");
        assertEquals(Optional.of(logs), order.line(logs.id()));
        assertTrue(order.line(UUID.randomUUID()).isEmpty());
    }

    @Test
    void deliveriesCountDownPerLineAndCompleteTheDelivery() {
        ProductionOrder<String, String> order = order(twoIngredients(), 1, 0L);
        UUID logs = order.lines().getFirst().id();
        UUID nails = order.lines().get(1).id();

        order = order.withDelivered(logs, 1, START, TIMEOUT);
        assertEquals(ProductionOrderState.WAITING_FOR_INGREDIENTS, order.state());
        assertEquals(1, order.outstanding(LOG));
        assertEquals(4, order.outstandingIngredients());

        order = order.withDelivered(logs, 1, START, TIMEOUT);
        order = order.withDelivered(nails, 3, START, TIMEOUT);
        assertEquals(ProductionOrderState.DELIVERED, order.state(), "every line is served");
        assertTrue(order.allIngredientsDelivered());
        assertEquals(0, order.outstandingIngredients());
    }

    @Test
    void aDeliveryNeverPushesALinePastItsTotal() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        UUID logs = order.lines().getFirst().id();
        order = order.withDelivered(logs, 99, START, TIMEOUT);
        assertEquals(1, order.lines().getFirst().delivered(), "only what the line asked for is counted");
        assertEquals(ProductionOrderState.DELIVERED, order.state());
    }

    @Test
    void anUnknownLineOrAZeroDeliveryChangesNothing() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        assertSame(order, order.withDelivered(UUID.randomUUID(), 1, START, TIMEOUT));
        assertSame(order, order.withDelivered(order.lines().getFirst().id(), 0, START, TIMEOUT));
        ProductionOrder<String, String> cancelled = order.cancelled(START);
        assertSame(cancelled, cancelled.withDelivered(UUID.randomUUID(), 1, START, TIMEOUT),
                "an unknown line changes nothing, finished or not");
    }

    /**
     * A drop that was already in the crane's grabber when the order ended still counts. Those items really are in the
     * station, and {@code deliveredIngredients} is the number the screen prints as "not recovered" — leaving them
     * uncounted would report that nothing was handed over while they lie in the buffer (§3.5.4).
     */
    @Test
    void aFinishedOrderStillCountsADeliveryThatReachesIt() {
        ProductionOrder<String, String> order = order(twoIngredients(), 1, 0L);
        UUID logs = order.lines().getFirst().id();
        ProductionOrder<String, String> cancelled = order.cancelled(START);
        assertEquals(0, cancelled.deliveredIngredients());

        ProductionOrder<String, String> landed = cancelled.withDelivered(logs, 2, START + 5, TIMEOUT);
        assertEquals(2, landed.deliveredIngredients(), "the items really are in the station");
        assertEquals(ProductionOrderState.CANCELLED, landed.state(), "but the order stays finished");
        assertEquals(START, landed.deadlineTick(), "and its retention still runs from the moment it ended");
        assertEquals(0, landed.outstandingIngredients(), "it promises nothing any more");
    }

    @Test
    void theMachineTakingTheIngredientsMovesTheOrderOn() {
        ProductionOrder<String, String> order = delivered(planks(), 1);
        assertEquals(ProductionOrderState.DELIVERED, order.state());
        order = order.withIngredientsTaken(START, TIMEOUT);
        assertEquals(ProductionOrderState.WAITING_FOR_RESULT, order.state());
        // Only a delivered order moves on: a still-collecting one is untouched.
        ProductionOrder<String, String> collecting = order(planks(), 1, 0L);
        assertSame(collecting, collecting.withIngredientsTaken(START, TIMEOUT));
    }

    @Test
    void onlyIncreasesOfTheResultStockCount() {
        ProductionOrder<String, String> order = delivered(planks(), 2).withIngredientsTaken(START, TIMEOUT);
        assertEquals(8, order.resultAmount());

        order = order.withResultStock(5L, START, TIMEOUT);
        assertEquals(5L, order.produced());
        assertEquals(ProductionOrderState.WAITING_FOR_RESULT, order.state());

        // The crane serves a request out of the same stock: the level falls, which says nothing about production.
        order = order.withResultStock(1L, START, TIMEOUT);
        assertEquals(5L, order.produced(), "a falling stock level never lowers what was produced");

        order = order.withResultStock(4L, START, TIMEOUT);
        assertEquals(8L, order.produced(), "three more arrived on top of the lowered baseline");
        assertEquals(ProductionOrderState.COMPLETE, order.state());
        assertEquals(0L, order.outstandingResult());
        assertEquals(0L, order.unproducedAmount());
        assertFalse(order.isOpen());
    }

    @Test
    void aBaselineAboveZeroIsNotCountedAsProduced() {
        ProductionOrder<String, String> order = order(planks(), 1, 64L);
        order = order.withResultStock(64L, START, TIMEOUT);
        assertEquals(0L, order.produced(), "what was already there is not this order's doing");
        order = order.withResultStock(68L, START, TIMEOUT);
        assertEquals(4L, order.produced());
        assertEquals(ProductionOrderState.COMPLETE, order.state());
    }

    /** A player who puts the product in by hand has satisfied the order just as well. */
    @Test
    void anOrderCanCompleteBeforeItsIngredientsMoved() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L).withResultStock(4L, START, TIMEOUT);
        assertEquals(ProductionOrderState.COMPLETE, order.state());
        assertEquals(0, order.outstanding(LOG), "a finished order promises nothing any more");
    }

    @Test
    void anOpenOrderTimesOutOnlyAtItsDeadline() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        assertSame(order, order.timedOutAt(START + TIMEOUT - 1), "before the deadline nothing happens");
        ProductionOrder<String, String> timedOut = order.timedOutAt(START + TIMEOUT);
        assertEquals(ProductionOrderState.TIMED_OUT, timedOut.state());
        assertFalse(timedOut.isOpen());
        assertEquals(4L, timedOut.unproducedAmount(), "what it never made is what its request gets back");
        assertEquals(0L, timedOut.outstandingResult(), "a finished order promises nothing");
        assertSame(timedOut, timedOut.timedOutAt(START + 10 * TIMEOUT));
    }

    @Test
    void progressPushesTheDeadlineOut() {
        ProductionOrder<String, String> order = order(twoIngredients(), 1, 0L);
        long later = START + TIMEOUT - 1;
        order = order.withDelivered(order.lines().getFirst().id(), 2, later, TIMEOUT);
        assertEquals(later + TIMEOUT, order.deadlineTick());
        assertSame(order, order.timedOutAt(START + TIMEOUT), "a slow machine that is making progress is not killed");
    }

    @Test
    void arrivingResultsPushTheDeadlineOutTooButNothingElseDoes() {
        ProductionOrder<String, String> order = delivered(planks(), 2).withIngredientsTaken(START, TIMEOUT);
        long deadline = order.deadlineTick();
        ProductionOrder<String, String> unchangedStock = order.withResultStock(0L, START + 5, TIMEOUT);
        assertEquals(deadline, unchangedStock.deadlineTick(), "observing the same stock is no progress");
        ProductionOrder<String, String> gained = order.withResultStock(1L, START + 5, TIMEOUT);
        assertEquals(START + 5 + TIMEOUT, gained.deadlineTick());
    }

    @Test
    void cancellingEndsAnOpenOrderAndLeavesAFinishedOneAlone() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        ProductionOrder<String, String> cancelled = order.cancelled(START);
        assertEquals(ProductionOrderState.CANCELLED, cancelled.state());
        assertEquals(0, cancelled.outstandingIngredients());
        assertSame(cancelled, cancelled.cancelled(START));
        assertEquals(START, cancelled.deadlineTick(), "a finished order's deadline is the moment it ended");
    }

    /**
     * The batching maths the planner relies on: a pattern's ingredients become <b>one line per item</b>, whatever the
     * number of runs. Ten planks are three runs of "one log to four planks", i.e. one line of three logs — one crane
     * trip, not three orders of one craft each.
     */
    @Test
    void severalRunsBecomeOneLinePerIngredient() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        ProductionOrder<String, String> order = order(pattern, pattern.runsFor(10), 0L);
        assertEquals(1, order.lines().size(), "one line, whatever the number of runs");
        assertEquals(3, order.lines().getFirst().required(), "three runs of one log");
        assertEquals(12, order.resultAmount(), "and three runs make twelve planks");
        assertEquals(3, order.outstanding(LOG));
    }

    /** A grid that names one item in several cells is one line as well, with the merged count times the runs. */
    @Test
    void mergedGridCellsBecomeOneLine() {
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(
                List.of(new ProductionEntry<>(PLANK, 1), new ProductionEntry<>(PLANK, 1),
                        new ProductionEntry<>(PLANK, 1), new ProductionEntry<>(NAIL, 2)),
                new ProductionEntry<>(LOG, 1));
        ProductionOrder<String, String> order = order(pattern, 5, 0L);
        assertEquals(2, order.lines().size(), "planks and nails, not four lines");
        assertEquals(15, order.lines().getFirst().required(), "three planks per run, five runs");
        assertEquals(10, order.lines().get(1).required());
        assertEquals(25, order.outstandingIngredients());
    }

    /**
     * What a cancelled or timed-out order leaves behind. Items the crane already dropped into the station are gone
     * from the warehouse's point of view — the machine may have swallowed them — and the order says so, which is what
     * the screen and the docs report ({@code warehouse-system.md} §3.5).
     */
    @Test
    void anEndedOrderSaysHowManyIngredientsItHandedOver() {
        ProductionOrder<String, String> order = order(twoIngredients(), 1, 0L);
        assertEquals(0, order.deliveredIngredients());
        order = order.withDelivered(order.lines().getFirst().id(), 2, START, TIMEOUT);
        assertEquals(2, order.deliveredIngredients());
        ProductionOrder<String, String> cancelled = order.cancelled(START + 1);
        assertEquals(2, cancelled.deliveredIngredients(), "a finished order still answers");
        assertEquals(0, cancelled.outstandingIngredients(), "but it promises nothing any more");
        assertEquals(1L, cancelled.unproducedAmount(), "and its request gets that amount back");
    }

    /**
     * The boundary the design is explicit about: once the machine has the ingredients, ending the order cannot get
     * them back. The state says so, so the goggles and the docs can say so too.
     */
    @Test
    void theStateSaysWhetherIngredientsWereHandedOver() {
        assertFalse(ProductionOrderState.WAITING_FOR_INGREDIENTS.handedIngredientsOver());
        assertTrue(ProductionOrderState.DELIVERED.handedIngredientsOver());
        assertTrue(ProductionOrderState.WAITING_FOR_RESULT.handedIngredientsOver());
        assertTrue(ProductionOrderState.COMPLETE.handedIngredientsOver());
        assertFalse(ProductionOrderState.TIMED_OUT.handedIngredientsOver());
        assertFalse(ProductionOrderState.CANCELLED.handedIngredientsOver());
        assertTrue(ProductionOrderState.WAITING_FOR_INGREDIENTS.isOpen());
        assertTrue(ProductionOrderState.TIMED_OUT.isFinished());
    }

    @Test
    void theBackingRequestCanBeDetached() {
        UUID request = UUID.randomUUID();
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), STATION, planks(), 1, lineIds,
                START, TIMEOUT, 0L, request);
        assertEquals(Optional.of(request), order.backingRequest());
        ProductionOrder<String, String> detached = order.withoutBackingRequest();
        assertTrue(detached.backingRequest().isEmpty());
        assertEquals(0L, detached.promisedToRequest(), "nobody left to give anything back to");
        assertEquals(0L, detached.timedOutAt(START + TIMEOUT).unfulfilledPromise());
        assertSame(detached, detached.withoutBackingRequest());
    }

    /**
     * A pattern makes whole runs, so an order regularly yields <b>more</b> than its request asked production for. What
     * a failed order gives back is that promise, never the whole shortfall: refunding the run would take items off a
     * request that the aisle can serve out of its own stock (§3.5.3).
     */
    @Test
    void aFailedOrderGivesBackOnlyWhatItPromised() {
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), STATION, planks(), 1, lineIds,
                START, TIMEOUT, 0L, UUID.randomUUID(), 1L);
        assertEquals(4, order.resultAmount(), "one run makes four planks");
        assertEquals(1L, order.promisedToRequest(), "but only one of them was asked for");
        assertEquals(4L, order.unproducedAmount(), "the run made nothing yet");
        assertEquals(1L, order.unfulfilledPromise(), "and only the promised one is given back");
        assertEquals(1L, order.cancelled(START).unfulfilledPromise());

        ProductionOrder<String, String> made = order.withResultStock(1L, START, TIMEOUT);
        assertEquals(0L, made.unfulfilledPromise(), "the promise is kept as soon as the asked amount arrived");
        assertTrue(made.isOpen(), "although the run itself is not finished");
    }

    @Test
    void anOrderNobodyWaitsForPromisesNothing() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        assertTrue(order.backingRequest().isEmpty());
        assertEquals(0L, order.promisedToRequest());
        assertEquals(0L, order.timedOutAt(START + TIMEOUT).unfulfilledPromise());
    }

    /** The promise can never exceed what the order could make, whatever a caller or a crafted save claims. */
    @Test
    void thePromiseIsBoundedByTheRun() {
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), STATION, planks(), 1, lineIds,
                START, TIMEOUT, 0L, UUID.randomUUID(), 999L);
        assertEquals(4L, order.promisedToRequest());
    }

    /**
     * One arrival may have to be shared with another order for the same result, so an order counts the part it was
     * <b>credited</b> — but remembers the level either way, or it would see the same items again on the next
     * observation ({@code ProductionOrders#observeResult}).
     */
    @Test
    void onlyTheCreditedPartOfAnArrivalIsCounted() {
        ProductionOrder<String, String> order = order(planks(), 2, 0L);
        assertEquals(8L, order.observableGain(8L), "the whole increase is visible to it");

        ProductionOrder<String, String> partial = order.withResultStock(8L, 3L, START, TIMEOUT);
        assertEquals(3L, partial.produced(), "but only what it was credited counts as produced");
        assertEquals(8L, partial.resultStockSeen(), "the level is remembered either way");
        assertEquals(0L, partial.observableGain(8L), "so the same arrival is not seen twice");
        assertTrue(partial.isOpen());

        ProductionOrder<String, String> nothing = order.withResultStock(8L, 0L, START + 5, TIMEOUT);
        assertEquals(0L, nothing.produced(), "an arrival that went to another order is no progress of this one");
        assertEquals(order.deadlineTick(), nothing.deadlineTick(), "and does not extend its timeout");
    }

    // --- automatic orders: how their result is counted (M15 part 2) -------------------------------------------------

    /**
     * <b>The safety stop's foundation.</b> A rise of the result's stock level says nothing about where the items came
     * from, so it may never complete an automatic order: an unrelated farm, a barrel tipped into a rack or a player
     * taking the product out and putting it back would otherwise complete an order whose ingredients a machine had
     * swallowed, and the rule would go on feeding that machine for ever (M15 review fix).
     */
    @Test
    void anAutomaticOrderIsNeverCompletedByARiseOfTheStockLevel() {
        ProductionOrder<String, String> order = restocking(planks(), 1);
        assertTrue(order.isRestock());
        assertFalse(order.countsStockLevels());
        UUID logs = order.lines().getFirst().id();
        order = order.withDelivered(logs, 1, START, TIMEOUT);
        assertEquals(0L, order.observableGain(1000L), "however much of the item turns up in the aisle");
        ProductionOrder<String, String> observed = order.withResultStock(1000L, START + 5, TIMEOUT);
        assertSame(order, observed, "nothing is counted and nothing is even written");
        assertEquals(0L, observed.produced());
        // The order therefore ends as what it is: a batch handed over with nothing coming back.
        ProductionOrder<String, String> ended = observed.timedOutAt(START + TIMEOUT + 1);
        assertEquals(ProductionOrderState.TIMED_OUT, ended.state());
        assertTrue(ended.endedWithLostIngredients(), "which is exactly when a rule stops ordering");
    }

    /** An ordinary order somebody is waiting for keeps counting the level, which is a documented feature (§3.5.3). */
    @Test
    void anOrdinaryOrderStillCountsTheStockLevel() {
        ProductionOrder<String, String> order = order(planks(), 1, 0L);
        assertTrue(order.countsStockLevels());
        assertEquals(ProductionOrderState.COMPLETE, order.withResultStock(4L, START + 5, TIMEOUT).state());
    }

    /** What really arrived: the crane stored items out of a warehouse input, which is the machine's own route back. */
    @Test
    void anArrivalCompletesAnAutomaticOrderOnceItsIngredientsWereDelivered() {
        ProductionOrder<String, String> order = restocking(planks(), 1);
        assertFalse(order.countsArrivals(), "nothing was given to a machine yet, so nothing can have come back");
        assertSame(order, order.withStored(4L, START + 1, TIMEOUT), "and an arrival before that counts for nothing");

        order = order.withDelivered(order.lines().getFirst().id(), 1, START + 2, TIMEOUT);
        assertTrue(order.countsArrivals());
        ProductionOrder<String, String> partial = order.withStored(3L, START + 3, TIMEOUT);
        assertEquals(3L, partial.produced());
        assertTrue(partial.isOpen());
        assertEquals(START + 3 + TIMEOUT, partial.deadlineTick(), "real progress pushes the deadline out");
        ProductionOrder<String, String> done = partial.withStored(5L, START + 4, TIMEOUT);
        assertEquals(ProductionOrderState.COMPLETE, done.state());
        assertEquals(4L, done.produced(), "never more than the order waits for");
        assertFalse(done.endedWithLostIngredients(), "a machine that gave its batch back pauses nothing");
    }

    @Test
    void anArrivalOfNothingAndAFinishedOrderChangeNothing() {
        ProductionOrder<String, String> order = restocking(planks(), 1);
        order = order.withDelivered(order.lines().getFirst().id(), 1, START, TIMEOUT);
        assertSame(order, order.withStored(0L, START, TIMEOUT));
        assertSame(order, order.withStored(-3L, START, TIMEOUT));
        ProductionOrder<String, String> cancelled = order.cancelled(START + 1);
        assertSame(cancelled, cancelled.withStored(4L, START + 2, TIMEOUT));
    }

    @Test
    void stateNamesRoundTripForSaves() {
        for (ProductionOrderState state : ProductionOrderState.values())
            assertEquals(Optional.of(state), ProductionOrderState.byName(state.name()));
        assertTrue(ProductionOrderState.byName("NOT_A_STATE").isEmpty());
        assertTrue(ProductionOrderState.byName(null).isEmpty());
    }

    @Test
    void anOrderNeedsIngredientsAndAPositiveResult() {
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.start(UUID.randomUUID(), STATION, planks(),
                0, lineIds, START, TIMEOUT, 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionOrder<>(UUID.randomUUID(), STATION, PLANK, 1, List.of(),
                        ProductionOrderState.WAITING_FOR_INGREDIENTS, START, 0L, 0L, Optional.empty(), 0L,
                        false));
    }

    /** An automatic order the warehouse started for itself (M15 part 2). */
    private ProductionOrder<String, String> restocking(ProductionPattern<String> pattern, int runs) {
        return ProductionOrder.restock(UUID.randomUUID(), STATION, pattern, runs, lineIds, START, TIMEOUT);
    }

    /** An order with every line served, for the tests that start after the delivery. */
    private ProductionOrder<String, String> delivered(ProductionPattern<String> pattern, int runs) {
        ProductionOrder<String, String> order = order(pattern, runs, 0L);
        for (SupplyLine<String> line : order.lines())
            order = order.withDelivered(line.id(), line.required(), START, TIMEOUT);
        return order;
    }
}
