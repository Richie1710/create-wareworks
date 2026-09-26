package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * The collection of production orders a controller owns ({@code docs/warehouse-system.md} §3.5): what it promises,
 * which line ids a crane job may still be working for, and how finished orders are kept and then forgotten.
 */
class ProductionOrdersTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String STATION_A = "a";
    private static final String STATION_B = "b";
    private static final long TIMEOUT = 100L;
    private static final long START = 1000L;
    private static final long RETENTION = 50L;
    private static final int MAX_ORDERS = 2;

    private final Supplier<UUID> lineIds = sequentialIds();
    private final ProductionOrders<String, String> orders = new ProductionOrders<>(MAX_ORDERS);

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1};
        return () -> new UUID(0L, next[0]++);
    }

    private ProductionOrder<String, String> newOrder(String station, int runs) {
        return ProductionOrder.start(UUID.randomUUID(), station, ProductionPattern.of(LOG, 1, PLANK, 4), runs, lineIds,
                START, TIMEOUT, 0L, null);
    }

    /** An automatic order the warehouse started for itself (M15 part 2). */
    private ProductionOrder<String, String> restocking(String station, int runs) {
        return ProductionOrder.restock(UUID.randomUUID(), station, ProductionPattern.of(LOG, 1, PLANK, 4), runs,
                lineIds, START, TIMEOUT);
    }

    @Test
    void openOrdersPromiseTheirIngredients() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 3);
        assertTrue(orders.add(order));
        assertEquals(3L, orders.outstandingIngredient(LOG));
        assertEquals(12L, orders.outstandingResult(PLANK));
        assertEquals(1, orders.openCount());

        orders.deliver(order.lines().getFirst().id(), 2, START, TIMEOUT);
        assertEquals(1L, orders.outstandingIngredient(LOG), "delivered ingredients are no longer owed");
    }

    @Test
    void aFinishedOrderPromisesNothing() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 3);
        orders.add(order);
        orders.cancel(order.id(), START);
        assertEquals(0L, orders.outstandingIngredient(LOG));
        assertEquals(0L, orders.outstandingResult(PLANK));
        assertEquals(0, orders.openCount());
        assertEquals(1, orders.size(), "but it is still there to be read");
    }

    @Test
    void theCapCountsOpenOrdersOnly() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> second = newOrder(STATION_A, 1);
        assertTrue(orders.add(first));
        assertTrue(orders.add(second));
        assertTrue(orders.isFull());
        assertFalse(orders.add(newOrder(STATION_A, 1)), "the third open order is refused");
        orders.cancel(first.id(), START);
        assertFalse(orders.isFull());
        assertTrue(orders.add(newOrder(STATION_A, 1)), "a finished one frees its slot");
    }

    @Test
    void aRepeatedIdIsRefused() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        assertTrue(orders.add(order));
        assertFalse(orders.add(order));
    }

    /**
     * The id a {@code SUPPLY} job carries: true while that ingredient still needs items, false once it is served, so
     * the crane detaches the id exactly as it does for a finished request.
     */
    @Test
    void anOpenLineIsOneThatStillNeedsItems() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 2);
        orders.add(order);
        UUID line = order.lines().getFirst().id();
        assertTrue(orders.hasOpenLine(line));
        assertTrue(orders.byLine(line).isPresent());

        orders.deliver(line, 1, START, TIMEOUT);
        assertTrue(orders.hasOpenLine(line), "one of two logs arrived");
        orders.deliver(line, 1, START, TIMEOUT);
        assertFalse(orders.hasOpenLine(line), "the line is served");
        assertFalse(orders.hasOpenLine(UUID.randomUUID()));
    }

    @Test
    void deliveringEverythingMovesTheOrderToDelivered() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.add(order);
        orders.deliver(order.lines().getFirst().id(), 1, START, TIMEOUT);
        assertEquals(ProductionOrderState.DELIVERED, orders.get(order.id()).orElseThrow().state());

        assertTrue(orders.ingredientsTaken(order.id(), START, TIMEOUT).isPresent());
        assertEquals(ProductionOrderState.WAITING_FOR_RESULT, orders.get(order.id()).orElseThrow().state());
        assertTrue(orders.ingredientsTaken(order.id(), START, TIMEOUT).isEmpty(), "nothing moves a second time");
        assertTrue(orders.ingredientsTaken(UUID.randomUUID(), START, TIMEOUT).isEmpty(), "and an unknown order never");
    }

    /**
     * Two orders at <b>one</b> station: only the order that was named moves on. They share the station's buffer, but
     * the observation behind this call ("the buffer no longer holds <i>these</i> ingredients") answers for one order
     * only — advancing both would report an order as "at the machine" while a player can see its own ingredients
     * still lying in the buffer, and would push its timeout out by a step that never happened to it.
     */
    @Test
    void onlyTheNamedOrderIsTouched() {
        ProductionOrder<String, String> here = newOrder(STATION_A, 1);
        ProductionOrder<String, String> alsoHere = newOrder(STATION_A, 1);
        orders.add(here);
        orders.add(alsoHere);
        orders.deliver(here.lines().getFirst().id(), 1, START, TIMEOUT);
        orders.deliver(alsoHere.lines().getFirst().id(), 1, START, TIMEOUT);
        long deadlineBefore = orders.get(alsoHere.id()).orElseThrow().deadlineTick();

        assertTrue(orders.ingredientsTaken(here.id(), START + 5, TIMEOUT).isPresent());
        assertEquals(ProductionOrderState.WAITING_FOR_RESULT, orders.get(here.id()).orElseThrow().state());
        assertEquals(ProductionOrderState.DELIVERED, orders.get(alsoHere.id()).orElseThrow().state(),
                "the other order's ingredients are still in the shared buffer");
        assertEquals(deadlineBefore, orders.get(alsoHere.id()).orElseThrow().deadlineTick(),
                "and its timeout was not extended by an event that did not happen to it");
    }

    /**
     * Two open orders for the same result see the same stock level, so one arrival is credited <b>once</b>, in
     * creation order. Crediting both in full would complete an order nothing was made for — and such a
     * phantom-complete order never gives its backing request the amount back and can never time out either, so that
     * request would wait for ever.
     */
    @Test
    void twoOrdersForOneResultShareOneArrival() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> second = newOrder(STATION_B, 1);
        orders.add(first);
        orders.add(second);

        List<ProductionOrder<String, String>> changed = orders.observeResult(PLANK, 4L, START, TIMEOUT);
        assertEquals(ProductionOrderState.COMPLETE, orders.get(first.id()).orElseThrow().state(),
                "the oldest order takes the batch that arrived");
        assertEquals(ProductionOrderState.WAITING_FOR_INGREDIENTS, orders.get(second.id()).orElseThrow().state(),
                "nothing was made for the second one");
        assertEquals(0L, orders.get(second.id()).orElseThrow().produced());
        assertEquals(2, changed.size(), "both were rewritten: the second one's baseline moved on too");

        orders.observeResult(PLANK, 8L, START + 10, TIMEOUT);
        assertEquals(ProductionOrderState.COMPLETE, orders.get(second.id()).orElseThrow().state(),
                "the next batch is the second order's");
        assertEquals(4L, orders.get(second.id()).orElseThrow().produced());
    }

    /** Ingredients the open orders still owe, per key, in one pass (the producible computation asks for many keys). */
    @Test
    void outstandingIngredientsAreAlsoAnsweredPerKeyInOnePass() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 3);
        ProductionOrder<String, String> second = newOrder(STATION_B, 2);
        orders.add(first);
        orders.add(second);
        assertEquals(5L, orders.outstandingIngredientsByKey().get(LOG), "three logs plus two");
        assertEquals(orders.outstandingIngredient(LOG), orders.outstandingIngredientsByKey().get(LOG));

        orders.deliver(first.lines().getFirst().id(), 3, START, TIMEOUT);
        orders.cancel(second.id(), START);
        assertTrue(orders.outstandingIngredientsByKey().isEmpty(),
                "a served line and a finished order owe nothing, and an absent key is never mapped to 0");
    }

    /**
     * Observing the result reports what it <b>changed</b>, not only what it completed: the controller uses that to
     * decide whether anything has to be saved, and an unchanged stock level must change nothing at all.
     */
    @Test
    void observingTheResultReportsWhatItChangedAndCompletes() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.add(order);

        List<ProductionOrder<String, String>> partial = orders.observeResult(PLANK, 2L, START, TIMEOUT);
        assertEquals(1, partial.size(), "two of four planks arrived: the order moved");
        assertEquals(ProductionOrderState.WAITING_FOR_INGREDIENTS, partial.getFirst().state(), "but is not done");
        assertTrue(orders.observeResult(PLANK, 2L, START, TIMEOUT).isEmpty(),
                "observing the same level again changes nothing");

        List<ProductionOrder<String, String>> completed = orders.observeResult(PLANK, 4L, START, TIMEOUT);
        assertEquals(1, completed.size());
        assertEquals(ProductionOrderState.COMPLETE, completed.getFirst().state());
        assertTrue(orders.observeResult(PLANK, 8L, START, TIMEOUT).isEmpty(), "a finished order is not observed again");
        assertTrue(orders.observeResult(LOG, 100L, START, TIMEOUT).isEmpty(), "another item never completes it");
    }

    /**
     * One arrival is credited <b>once</b>, and an automatic order is credited by nothing else (M15 part 2): the level
     * channel skips it, so the physical batch that raised the level and the arrival that reported it cannot both count.
     */
    @Test
    void anArrivalIsCreditedOnceAndAnAutomaticOrderOnlyByIt() {
        ProductionOrder<String, String> automatic = restocking(STATION_A, 1);
        orders.add(automatic);
        orders.deliver(automatic.lines().getFirst().id(), 1, START, TIMEOUT);
        assertTrue(orders.observeResult(PLANK, 100L, START, TIMEOUT).isEmpty(),
                "a level rise never completes an automatic order, whatever it is");

        ProductionOrders.Observation<String, String> none = orders.observeStored(PLANK, 0L, START, TIMEOUT);
        assertTrue(none.isEmpty());
        assertEquals(0L, none.credited());

        ProductionOrders.Observation<String, String> observed = orders.observeStored(PLANK, 6L, START + 1, TIMEOUT);
        assertEquals(1, observed.changed().size());
        assertEquals(4L, observed.credited(), "only what the order still waited for is credited");
        assertEquals(ProductionOrderState.COMPLETE, observed.changed().getFirst().state());
        assertTrue(orders.observeStored(PLANK, 4L, START + 2, TIMEOUT).isEmpty(),
                "a finished order counts no further arrival");
        assertTrue(orders.observeStored(LOG, 100L, START + 2, TIMEOUT).isEmpty(), "and another item never does");
    }

    /** Two open orders for the same result split one arrival in creation order, exactly as they split a level rise. */
    @Test
    void twoOrdersSplitOneArrivalInsteadOfBothTakingIt() {
        ProductionOrder<String, String> first = restocking(STATION_A, 1);
        ProductionOrder<String, String> second = restocking(STATION_B, 1);
        orders.add(first);
        orders.add(second);
        orders.deliver(first.lines().getFirst().id(), 1, START, TIMEOUT);
        orders.deliver(second.lines().getFirst().id(), 1, START, TIMEOUT);

        ProductionOrders.Observation<String, String> observed = orders.observeStored(PLANK, 5L, START + 1, TIMEOUT);
        assertEquals(5L, observed.credited());
        assertEquals(ProductionOrderState.COMPLETE, orders.get(first.id()).orElseThrow().state());
        assertEquals(1L, orders.get(second.id()).orElseThrow().produced(), "and the second one only takes the rest");
    }

    /** A cancelled order is forgotten a retention after the cancellation, not after its original timeout. */
    @Test
    void aCancelledOrderIsPrunedFromTheMomentItWasCancelled() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.add(order);
        orders.cancel(order.id(), START);
        assertEquals(0, orders.prune(START + RETENTION - 1, RETENTION), "still readable");
        assertEquals(1, orders.prune(START + RETENTION, RETENTION), "and forgotten well before its old deadline");
    }

    /** A reloaded world restarts the timeout of open orders only; a finished one keeps its retention honest. */
    @Test
    void reloadingDoesNotRevivetheRetentionOfFinishedOrders() {
        ProductionOrder<String, String> open = newOrder(STATION_A, 1);
        ProductionOrder<String, String> done = newOrder(STATION_B, 1);
        orders.restore(List.of(open.withDeadline(0L), done.cancelled(0L)));
        orders.restartDeadlines(START, TIMEOUT);
        assertEquals(START + TIMEOUT, orders.get(open.id()).orElseThrow().deadlineTick(), "the open order waits again");
        assertEquals(START, orders.get(done.id()).orElseThrow().deadlineTick(), "the finished one ages from now");
        assertEquals(1, orders.prune(START + RETENTION, RETENTION));
    }

    @Test
    void cancellingAStationEndsEveryOpenOrderThere() {
        orders.add(newOrder(STATION_A, 1));
        ProductionOrder<String, String> elsewhere = newOrder(STATION_B, 1);
        orders.add(elsewhere);
        List<ProductionOrder<String, String>> cancelled = orders.cancelFor(STATION_A, START);
        assertEquals(1, cancelled.size());
        assertEquals(ProductionOrderState.CANCELLED, cancelled.getFirst().state());
        assertTrue(orders.get(elsewhere.id()).orElseThrow().isOpen());
    }

    @Test
    void timeOutsEndEveryOverdueOrder() {
        orders.add(newOrder(STATION_A, 1));
        orders.add(newOrder(STATION_B, 1));
        assertTrue(orders.timeOut(START + TIMEOUT - 1).isEmpty());
        assertEquals(2, orders.timeOut(START + TIMEOUT).size());
        assertEquals(0, orders.openCount());
    }

    @Test
    void finishedOrdersAreKeptForAWhileAndThenForgotten() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.add(order);
        orders.timeOut(START + TIMEOUT);
        long ended = orders.get(order.id()).orElseThrow().deadlineTick();
        assertEquals(0, orders.prune(ended + RETENTION - 1, RETENTION), "still readable");
        assertEquals(1, orders.prune(ended + RETENTION, RETENTION));
        assertTrue(orders.isEmpty());
    }

    @Test
    void anOpenOrderIsNeverPruned() {
        orders.add(newOrder(STATION_A, 1));
        assertEquals(0, orders.prune(START + 1_000_000L, RETENTION));
        assertEquals(1, orders.openCount());
    }

    @Test
    void detachingARequestLeavesTheOrderRunning() {
        UUID request = UUID.randomUUID();
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), STATION_A,
                ProductionPattern.of(LOG, 1, PLANK, 4), 1, lineIds, START, TIMEOUT, 0L, request);
        orders.add(order);
        orders.detachRequest(request);
        assertTrue(orders.get(order.id()).orElseThrow().backingRequest().isEmpty());
        assertTrue(orders.get(order.id()).orElseThrow().isOpen());
        assertEquals(Optional.empty(), orders.get(order.id()).orElseThrow().backingRequest());
        assertEquals(0L, orders.get(order.id()).orElseThrow().promisedToRequest(),
                "the promise goes with the request: the result simply lands in stock now");
    }

    @Test
    void restoreSkipsRepeatedIdsAndIgnoresTheCap() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> second = newOrder(STATION_A, 1);
        ProductionOrder<String, String> third = newOrder(STATION_A, 1);
        assertEquals(3, orders.restore(List.of(first, second, third)), "a lowered cap never deletes a saved order");
        assertEquals(2, orders.restore(List.of(first, second, first)));
    }

    @Test
    void reloadingGivesEveryOrderItsFullTimeoutAgain() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.restore(List.of(order.withDeadline(0L)));
        orders.restartDeadlines(START, TIMEOUT);
        assertEquals(START + TIMEOUT, orders.get(order.id()).orElseThrow().deadlineTick());
        assertTrue(orders.timeOut(START + TIMEOUT - 1).isEmpty(), "a reloaded order does not time out at once");
    }
}
