package dev.wareworks.core.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * The Warehouse Optimizer orders of one warehouse (issue #46, ADR-079): one order per optimizer, a trip cap, entries
 * that end when nothing more fits, and in-flight trips that are counted when they land, whatever became of the order.
 */
class OptimizerOrdersTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";
    private static final String COAL = "coal";
    private static final String NORTH = "north";
    private static final String SOUTH = "south";

    private final OptimizerOrders<String, String> orders = new OptimizerOrders<>(sequentialIds());

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private OptimizerOrder<String, String> order(String optimizer) {
        return orders.get(optimizer).orElseThrow();
    }

    @Test
    void aFreshOrderListsEachItemOnceInRowOrder() {
        assertFalse(orders.hasPending());
        assertEquals(OptimizerOrders.Start.STARTED, orders.start(NORTH, List.of(IRON, GOLD, IRON, COAL), 32));
        OptimizerOrder<String, String> order = order(NORTH);
        assertEquals(List.of(IRON, GOLD, COAL), order.entries().stream().map(OptimizerOrder.Entry::key).toList());
        assertEquals(OptimizerOrder.State.RUNNING, order.state());
        assertEquals(32, order.tripsLeft());
        assertEquals(3, order.openEntries());
        assertTrue(orders.hasPending());
        assertEquals(List.of(new OptimizerOrders.OpenEntry<>(id(1), IRON), new OptimizerOrders.OpenEntry<>(id(2), GOLD),
                new OptimizerOrders.OpenEntry<>(id(3), COAL)), orders.openEntries());
    }

    @Test
    void anEmptyListStartsNothing() {
        assertEquals(OptimizerOrders.Start.NO_ITEMS, orders.start(NORTH, List.of(), 32));
        assertTrue(orders.isEmpty());
    }

    @Test
    void theRowsAndTheTripsAreBounded() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < OptimizerLimits.MAX_ROWS + 5; i++)
            many.add("item" + i);
        orders.start(NORTH, many, 100_000);
        assertEquals(OptimizerLimits.MAX_ROWS, order(NORTH).entries().size());
        assertEquals(OptimizerLimits.MAX_TRIPS, order(NORTH).tripsLeft());
        orders.start(SOUTH, List.of(IRON), -3);
        assertEquals(OptimizerLimits.MIN_TRIPS, order(SOUTH).tripsLeft());
    }

    @Test
    void pressingAgainStartsAFreshOrder() {
        orders.start(NORTH, List.of(IRON), 32);
        assertTrue(orders.onAssigned(id(100), IRON));
        orders.start(NORTH, List.of(GOLD), 32);
        assertEquals(1, orders.all().size(), "one order per optimizer");
        assertEquals(List.of(GOLD), order(NORTH).entries().stream().map(OptimizerOrder.Entry::key).toList());
        assertEquals(Map.of(), order(NORTH).inFlight(), "the old trip finishes uncounted");
        assertFalse(orders.onDelivered(id(100), 10));
    }

    @Test
    void aWarehouseKeepsEightOrdersAndMakesRoomFromTheOldestFinished() {
        for (int i = 0; i < OptimizerLimits.MAX_ORDERS; i++)
            assertEquals(OptimizerOrders.Start.STARTED, orders.start("o" + i, List.of(IRON), 32));
        assertEquals(OptimizerOrders.Start.TOO_MANY_ORDERS, orders.start("late", List.of(IRON), 32));
        assertEquals(OptimizerOrders.Start.STARTED, orders.start("o3", List.of(GOLD), 32),
                "an optimizer that has an order may always replace it");

        orders.cancel("o1");
        orders.cancel("o5");
        assertEquals(OptimizerOrders.Start.STARTED, orders.start("late", List.of(IRON), 32));
        assertFalse(orders.get("o1").isPresent(), "the oldest finished order made room");
        assertTrue(orders.get("o5").isPresent());
        assertEquals(OptimizerLimits.MAX_ORDERS, orders.all().size());
    }

    @Test
    void anItemEndsInEveryOrderThatListsIt() {
        orders.start(NORTH, List.of(IRON, GOLD), 32);
        orders.start(SOUTH, List.of(IRON), 32);
        assertEquals(List.of(new OptimizerOrders.OpenEntry<>(id(1), IRON), new OptimizerOrders.OpenEntry<>(id(2), GOLD)),
                orders.openEntries(), "one need per item, the oldest order's entry");

        assertTrue(orders.endExhausted(Set.of(id(1))));
        assertEquals(OptimizerOrder.EntryState.NOTHING_TO_MOVE, order(NORTH).entryFor(IRON).orElseThrow().state());
        assertEquals(OptimizerOrder.State.RUNNING, order(NORTH).state(), "gold is still open");
        assertEquals(OptimizerOrder.State.FINISHED, order(SOUTH).state(), "nothing more fits for south's iron either");
        assertEquals(List.of(new OptimizerOrders.OpenEntry<>(id(2), GOLD)), orders.openEntries());

        assertTrue(orders.endKey(GOLD));
        assertFalse(orders.hasPending());
        assertFalse(orders.endKey(GOLD), "an ended item ends once");
    }

    @Test
    void anEntryWithATripIsDone() {
        orders.start(NORTH, List.of(IRON, GOLD), 32);
        orders.onAssigned(id(100), IRON);
        orders.endKey(IRON);
        assertEquals(OptimizerOrder.EntryState.DONE, order(NORTH).entryFor(IRON).orElseThrow().state(),
                "its trip is still on its way, so something moved");
    }

    @Test
    void tripsAreSpentAndCountedWhenTheyLand() {
        orders.start(NORTH, List.of(IRON), 2);
        assertTrue(orders.onAssigned(id(100), IRON));
        assertEquals(1, order(NORTH).tripsLeft());
        assertEquals(Map.of(id(100), id(1)), order(NORTH).inFlight());
        assertFalse(orders.onAssigned(id(101), GOLD), "nobody asked for gold");

        assertTrue(orders.onDelivered(id(100), 30));
        assertEquals(30, order(NORTH).moved());
        assertEquals(30, order(NORTH).entryFor(IRON).orElseThrow().moved());
        assertTrue(orders.onJobEnded(id(100)));
        assertEquals(Map.of(), order(NORTH).inFlight());
        assertFalse(orders.onJobEnded(id(100)));
    }

    @Test
    void theLastTripEndsTheOrderButStillCounts() {
        orders.start(NORTH, List.of(IRON, GOLD), 1);
        orders.onAssigned(id(100), IRON);
        OptimizerOrder<String, String> order = order(NORTH);
        assertEquals(OptimizerOrder.State.OUT_OF_TRIPS, order.state());
        assertEquals(0, order.tripsLeft());
        assertFalse(orders.hasPending());
        assertEquals(List.of(), orders.openEntries());
        assertTrue(order.entries().stream().allMatch(entry -> entry.state() == OptimizerOrder.EntryState.STOPPED));

        assertTrue(orders.onDelivered(id(100), 64), "the trip under way is finished and counted");
        assertEquals(64, order(NORTH).moved());
    }

    @Test
    void cancellingStopsPlanningAndKeepsCounting() {
        orders.start(NORTH, List.of(IRON), 32);
        orders.onAssigned(id(100), IRON);
        assertTrue(orders.cancel(NORTH));
        assertFalse(orders.cancel(NORTH), "only a running order is cancelled");
        assertEquals(OptimizerOrder.State.CANCELLED, order(NORTH).state());
        assertFalse(orders.hasPending());
        assertFalse(orders.onAssigned(id(101), IRON), "no further trip");
        assertTrue(orders.onDelivered(id(100), 12));
        assertEquals(12, order(NORTH).moved());
    }

    @Test
    void aRemovedOptimizerIsForgotten() {
        orders.start(NORTH, List.of(IRON), 32);
        assertTrue(orders.remove(NORTH));
        assertFalse(orders.remove(NORTH));
        assertTrue(orders.isEmpty());
    }

    @Test
    void tripsNoCraneHoldsAreForgotten() {
        orders.start(NORTH, List.of(IRON), 32);
        orders.onAssigned(id(100), IRON);
        orders.onAssigned(id(101), IRON);
        assertTrue(orders.hasTripsInFlight());
        assertTrue(orders.retainTrips(Set.of(id(101))));
        assertEquals(Map.of(id(101), id(1)), order(NORTH).inFlight());
        assertFalse(orders.retainTrips(Set.of(id(101))));
    }

    @Test
    void theInFlightSetIsBounded() {
        orders.start(NORTH, List.of(IRON), OptimizerLimits.MAX_TRIPS);
        for (int i = 0; i < OptimizerLimits.MAX_IN_FLIGHT + 3; i++)
            orders.onAssigned(id(1000 + i), IRON);
        assertEquals(OptimizerLimits.MAX_IN_FLIGHT, order(NORTH).inFlight().size());
        assertFalse(order(NORTH).inFlight().containsKey(id(1000)), "the oldest is forgotten first");
    }

    @Test
    void aRestoredOrderWithNothingLeftToRunHasEnded() {
        OptimizerOrder.Entry<String> open = new OptimizerOrder.Entry<>(id(1), IRON, OptimizerOrder.EntryState.OPEN, 0);
        OptimizerOrder.Entry<String> done = new OptimizerOrder.Entry<>(id(2), GOLD, OptimizerOrder.EntryState.DONE, 5);
        orders.restore(List.of(
                new OptimizerOrder<>(NORTH, OptimizerOrder.State.RUNNING, List.of(open), 0, 0, Map.of()),
                new OptimizerOrder<>(SOUTH, OptimizerOrder.State.RUNNING, List.of(done), 4, 5, Map.of()),
                new OptimizerOrder<>(SOUTH, OptimizerOrder.State.RUNNING, List.of(open), 4, 0, Map.of()),
                new OptimizerOrder<>("west", OptimizerOrder.State.RUNNING, List.of(open), 4, 0,
                        Map.of(id(100), id(1)))));
        assertEquals(OptimizerOrder.State.OUT_OF_TRIPS, order(NORTH).state());
        assertEquals(OptimizerOrder.State.FINISHED, order(SOUTH).state(), "the first of a repeated optimizer is kept");
        assertEquals(OptimizerOrder.State.RUNNING, order("west").state());
        assertEquals(List.of(new OptimizerOrders.OpenEntry<>(id(1), IRON)), orders.openEntries());
        assertTrue(orders.onDelivered(id(100), 3), "a trip saved on its way is counted after the load");
        orders.clear();
        assertTrue(orders.isEmpty());
    }

    @Test
    void theLimitsAreClamped() {
        assertEquals(OptimizerLimits.MIN_TRIPS, OptimizerLimits.clampTrips(0));
        assertEquals(OptimizerLimits.MIN_TRIPS, OptimizerLimits.clampTrips(Integer.MIN_VALUE));
        assertEquals(OptimizerLimits.MAX_TRIPS, OptimizerLimits.clampTrips(Integer.MAX_VALUE));
        assertEquals(OptimizerLimits.DEFAULT_TRIPS, OptimizerLimits.clampTrips(OptimizerLimits.DEFAULT_TRIPS));
    }
}
