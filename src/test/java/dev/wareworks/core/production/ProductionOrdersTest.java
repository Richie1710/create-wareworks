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
    /** What a step of a plan makes logs out of, so a two-level chain has three distinct items. */
    private static final String TREE = "tree";
    /** A second ingredient, so a parent can have two lines and therefore two steps of its own. */
    private static final String NAIL = "nail";
    private static final String STATION_A = "a";
    private static final String STATION_B = "b";
    private static final long TIMEOUT = 100L;
    private static final long START = 1000L;
    private static final long RETENTION = 50L;
    private static final int MAX_ORDERS = 2;

    private final Supplier<UUID> lineIds = sequentialIds();
    private final ProductionOrders<String, String> orders = new ProductionOrders<>(MAX_ORDERS);
    /** Room for a whole chain, for the plan tests (M20); the cap itself is tested on {@link #orders}. */
    private final ProductionOrders<String, String> plans = new ProductionOrders<>(8);

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

    /**
     * <b>An arrival goes to the order that has no other way to be completed</b> (M20 review fix). A step of a plan and an
     * automatic order can only ever be completed by an arrival, while an ordinary order is completed by a rising stock
     * level just as well — and an ordinary order counts arrivals from the moment it is created, i.e. while its own crane
     * is still fetching.
     * <p>
     * In plain creation order the older ordinary order therefore took the batch the step's machine had just made and gave
     * nothing back: the step counted nothing, its deadline was not pushed out, and it timed out minutes later — arming a
     * safety stop for an item whose machines were all working and taking the rest of its plan with it.
     */
    @Test
    void anArrivalGoesToTheOrderWithNoOtherChannelFirst() {
        // An older ordinary order for logs, still fetching its own ingredients, and a step of a plan making logs.
        ProductionOrder<String, String> older = ProductionOrder.start(UUID.randomUUID(), STATION_A,
                ProductionPattern.of(TREE, 1, LOG, 2), 1, lineIds, START, TIMEOUT, 0L, null);
        ProductionOrder<String, String> parent = newOrder(STATION_A, 2);
        ProductionOrder<String, String> step = stepOf(parent, 0, STATION_B, 1);
        assertTrue(plans.add(older));
        assertTrue(plans.addAll(List.of(step, parent)));
        // The step's machine has really taken its ingredients, so it is the order the batch can have come from.
        plans.deliver(step.lines().getFirst().id(), 1, START, TIMEOUT);
        plans.ingredientsTaken(step.id(), START, TIMEOUT);

        ProductionOrders.Observation<String, String> observed = plans.observeStored(LOG, 2L, START + 1, TIMEOUT);
        assertEquals(2L, observed.credited());
        assertEquals(ProductionOrderState.COMPLETE, plans.get(step.id()).orElseThrow().state(),
                "the step is completed, so the order above it may start fetching");
        assertEquals(0L, plans.get(older.id()).orElseThrow().produced(),
                "and the older order, which a rising stock level completes just as well, took nothing");
        // What is left over does reach it, so nothing is lost by the order the batch is offered in.
        assertEquals(2L, plans.observeStored(LOG, 2L, START + 2, TIMEOUT).credited());
        assertEquals(ProductionOrderState.COMPLETE, plans.get(older.id()).orElseThrow().state());
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
        assertTrue(orders.timeOut(START + TIMEOUT - 1, TIMEOUT).isEmpty());
        assertEquals(2, orders.timeOut(START + TIMEOUT, TIMEOUT).size());
        assertEquals(0, orders.openCount());
    }

    @Test
    void finishedOrdersAreKeptForAWhileAndThenForgotten() {
        ProductionOrder<String, String> order = newOrder(STATION_A, 1);
        orders.add(order);
        orders.timeOut(START + TIMEOUT, TIMEOUT);
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
        assertTrue(orders.timeOut(START + TIMEOUT - 1, TIMEOUT).isEmpty(), "a reloaded order does not time out at once");
    }

    // --- plans (M20) ---------------------------------------------------------------------------------------------

    /** A whole plan is created in one go or not at all, children before parents. */
    @Test
    void aPlanIsAddedAllOrNothing() {
        ProductionOrder<String, String> parent = newOrder(STATION_A, 2);
        ProductionOrder<String, String> child = stepOf(parent, 0, STATION_B, 2);
        assertTrue(plans.addAll(List.of(child, parent)), "the plan fits");
        assertEquals(2, plans.size());
        assertEquals(2L, plans.outstandingIngredient(LOG), "the parent's logs are promised");
        assertEquals(2L, plans.outstandingIngredient(TREE), "and so are the step's own ingredients");
    }

    @Test
    void aPlanThatDoesNotFitChangesNothing() {
        ProductionOrders<String, String> tight = new ProductionOrders<>(2);
        ProductionOrder<String, String> parent = newOrder(STATION_A, 1);
        ProductionOrder<String, String> child = stepOf(parent, 0, STATION_B, 1);
        ProductionOrder<String, String> grandchild = stepOf(child, 0, STATION_B, 1);
        assertFalse(tight.addAll(List.of(grandchild, child, parent)), "three open orders do not fit two slots");
        assertTrue(tight.isEmpty(), "and not one of them was created");

        assertFalse(plans.addAll(List.of(child, parent, parent)), "a repeated id is refused");
        assertTrue(plans.isEmpty());
        assertFalse(plans.addAll(List.of(child)), "a step whose parent is nowhere is refused");
        assertTrue(plans.isEmpty(), "so no step can ever wait for a parent that does not exist");
        assertTrue(plans.addAll(List.of()), "an empty plan is nothing to refuse");
    }

    /** A step that names its own line, and a pair of steps naming each other, are refused at the door. */
    @Test
    void addAllRefusesALoopOfParentLinks() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> itself = ProductionOrder.step(UUID.randomUUID(), STATION_A,
                ProductionPattern.of(TREE, 1, LOG, 2), 1, lineIds, START, TIMEOUT,
                first.lines().getFirst().id());
        // A step pointing at a line of its own: build it, then make that line its parent link by hand.
        ProductionOrder<String, String> selfFed = ProductionOrder.step(itself.id(), STATION_A,
                ProductionPattern.of(TREE, 1, LOG, 2), 1, () -> itself.lines().getFirst().id(), START, TIMEOUT,
                itself.lines().getFirst().id());
        assertFalse(plans.addAll(List.of(selfFed)), "an order waiting for itself is refused");
        assertTrue(plans.isEmpty());
    }

    /** Everything about the tree is derived from the one parent link, and nothing is stored twice. */
    @Test
    void theTreeIsDerivedFromTheParentLinks() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 2);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 2);
        ProductionOrder<String, String> deeper = stepOf(step, 0, STATION_B, 1);
        assertTrue(plans.addAll(List.of(deeper, step, root)));

        assertEquals(Optional.of(root.id()), plans.parentOf(step).map(ProductionOrder::id));
        assertTrue(plans.parentOf(root).isEmpty(), "a root waits for nobody");
        assertEquals(List.of(step.id()), ids(plans.childrenOf(root.id())));
        assertEquals(List.of(deeper.id()), ids(plans.childrenOf(step.id())));
        assertTrue(plans.childrenOf(deeper.id()).isEmpty());
        assertTrue(plans.hasOpenChildren(root.id()), "so the root fetches nothing yet");
        assertFalse(plans.hasOpenChildren(deeper.id()), "the deepest step fetches");
        assertEquals(0, plans.depthOf(root.id()));
        assertEquals(1, plans.depthOf(step.id()));
        assertEquals(2, plans.depthOf(deeper.id()));
        assertEquals(Optional.of(root.id()), plans.rootOf(deeper.id()).map(ProductionOrder::id));
        assertEquals(List.of(deeper.id(), step.id(), root.id()), ids(plans.planOf(step.id())),
                "the plan reads in dependency order, children before parents, the root last");
        assertEquals(List.of(step.id(), deeper.id()), ids(plans.descendantsOf(root.id())));
        assertEquals(Optional.of(deeper.id()), plans.frontierOf(root.id()).map(ProductionOrder::id),
                "the deepest open step is the one that is really working");
        assertEquals(2, plans.openStepCount());

        // Once the deepest step is done, the step above it is the one working.
        plans.cancel(deeper.id(), START + 1);
        assertFalse(plans.hasOpenChildren(step.id()));
        assertEquals(Optional.of(step.id()), plans.frontierOf(root.id()).map(ProductionOrder::id));
        assertEquals(1, plans.openStepCount());
    }

    /** An order in no plan is a plan of one, which is what keeps every answer here the same as it always was. */
    @Test
    void anOrderInNoPlanIsAPlanOfOne() {
        ProductionOrder<String, String> alone = newOrder(STATION_A, 1);
        plans.add(alone);
        assertEquals(List.of(alone.id()), ids(plans.planOf(alone.id())));
        assertEquals(Optional.of(alone.id()), plans.rootOf(alone.id()).map(ProductionOrder::id));
        assertEquals(0, plans.depthOf(alone.id()));
        assertFalse(plans.hasOpenChildren(alone.id()));
        assertTrue(plans.planOf(UUID.randomUUID()).isEmpty(), "and an unknown order is no plan at all");
        assertEquals(0, plans.depthOf(UUID.randomUUID()));
        assertTrue(plans.frontierOf(UUID.randomUUID()).isEmpty());
    }

    /** A finished step is kept while its plan runs, and the whole plan is then forgotten from the last ending. */
    @Test
    void aFinishedStepIsKeptWhileItsPlanIsStillOpen() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(step, root));
        plans.cancel(step.id(), START);
        assertEquals(0, plans.prune(START + 1_000_000L, RETENTION),
                "the step stays readable for as long as anything of its plan runs");
        assertEquals(2, plans.size());

        plans.cancel(root.id(), START + 100L);
        assertEquals(0, plans.prune(START + 100L + RETENTION - 1, RETENTION), "the plan ages from the last ending");
        assertEquals(2, plans.prune(START + 100L + RETENTION, RETENTION), "and then goes together");
        assertTrue(plans.isEmpty());
    }

    /** A step whose parent was truncated out of the save and that cost nothing is cancelled. */
    @Test
    void validatePlansCancelsAnOrphanThatCostNothing() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 2);
        plans.restore(List.of(step)); // the parent did not make it into the save
        assertEquals(2L, plans.outstandingIngredient(TREE), "before the check it still promises its ingredients");

        List<ProductionOrder<String, String>> changed = plans.validatePlans(START);
        assertEquals(List.of(step.id()), ids(changed));
        assertEquals(ProductionOrderState.CANCELLED, plans.get(step.id()).orElseThrow().state());
        assertEquals(0L, plans.outstandingIngredient(TREE), "and afterwards the crane fetches nothing for it");
        assertTrue(plans.validatePlans(START + 1).isEmpty(), "a second pass finds nothing");
    }

    /** One whose ingredients are already in a machine is detached instead: those items are gone either way. */
    @Test
    void validatePlansDetachesAnOrphanThatAlreadyCostSomething() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 2);
        plans.restore(List.of(step));
        plans.deliver(step.lines().getFirst().id(), 1, START, TIMEOUT);

        List<ProductionOrder<String, String>> changed = plans.validatePlans(START);
        assertEquals(List.of(step.id()), ids(changed));
        ProductionOrder<String, String> loose = plans.get(step.id()).orElseThrow();
        assertTrue(loose.isOpen(), "it runs on, so the product of those items still comes back");
        assertFalse(loose.isStep());
        assertEquals(ProductionOrder.BASELINE_PENDING, loose.resultStockSeen(),
                "and it does not count the stock that is already there as its own");
    }

    /** The whole chain is checked, not just one hop: a step under a broken step is broken too. */
    @Test
    void validatePlansEndsAStepWhoseChainIsBroken() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        ProductionOrder<String, String> deeper = stepOf(step, 0, STATION_B, 1);
        // The root was truncated away; the two steps below it are restored in creation order, deepest first.
        plans.restore(List.of(deeper, step));
        assertEquals(2, plans.validatePlans(START).size(), "both steps end, whichever order they are checked in");
        assertEquals(0, plans.openCount());

        // The same for a chain whose parent is finished: it can never use what the step is making.
        ProductionOrders<String, String> another = new ProductionOrders<>(8);
        another.restore(List.of(stepOf(root, 0, STATION_B, 1), root.cancelled(START)));
        assertEquals(1, another.validatePlans(START + 1).size());
        assertEquals(0, another.openCount());
    }

    /** Save data that names an order as its own ancestor terminates instead of spinning. */
    @Test
    void validatePlansBreaksACycleInSaveData() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> second = newOrder(STATION_B, 1);
        ProductionOrder<String, String> firstStep = asStepOf(first, second.lines().getFirst().id());
        ProductionOrder<String, String> secondStep = asStepOf(second, first.lines().getFirst().id());
        plans.restore(List.of(firstStep, secondStep));
        assertEquals(2, plans.planOf(firstStep.id()).size(), "the derived queries answer without spinning");
        assertEquals(2, plans.validatePlans(START).size(), "and the cycle is broken");
        assertEquals(0, plans.openCount(), "neither of them promises anything any more");
    }

    /** An intact plan is left exactly as it was saved. */
    @Test
    void validatePlansLeavesAnIntactPlanAlone() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.restore(List.of(step, root));
        assertTrue(plans.validatePlans(START).isEmpty());
        assertEquals(2, plans.openCount());
        assertEquals(Optional.of(root.id()), plans.parentOf(step).map(ProductionOrder::id),
                "the link survived the save unchanged");
    }

    /** Detaching is the one way a parent link is cleared, and the indices follow it. */
    @Test
    void detachingAStepTakesItOutOfItsPlan() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(step, root));
        assertTrue(plans.detach(step.id()).isPresent());
        assertFalse(plans.hasOpenChildren(root.id()), "the root is free to fetch its own ingredients now");
        assertTrue(plans.childrenOf(root.id()).isEmpty());
        assertEquals(0, plans.depthOf(step.id()));
        assertEquals(0, plans.openStepCount());
        assertTrue(plans.detach(step.id()).isEmpty(), "detaching an order that is no step changes nothing");
        assertTrue(plans.detach(UUID.randomUUID()).isEmpty());
    }

    /** The line index survives every change: the crane resolves a line id after deliveries, prunes and a restore. */
    @Test
    void lineIdsResolveToTheirOrderThroughEveryChange() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(step, root));
        UUID rootLine = root.lines().getFirst().id();
        UUID stepLine = step.lines().getFirst().id();
        assertEquals(Optional.of(root.id()), plans.byLine(rootLine).map(ProductionOrder::id));
        assertEquals(Optional.of(step.id()), plans.byLine(stepLine).map(ProductionOrder::id));

        plans.deliver(stepLine, 1, START, TIMEOUT);
        assertEquals(Optional.of(step.id()), plans.byLine(stepLine).map(ProductionOrder::id), "after a delivery");
        plans.cancel(step.id(), START);
        plans.cancel(root.id(), START);
        plans.prune(START + RETENTION, RETENTION);
        assertTrue(plans.byLine(stepLine).isEmpty(), "a forgotten order takes its lines with it");
        assertTrue(plans.byLine(rootLine).isEmpty());

        plans.restore(List.of(step, root));
        assertEquals(Optional.of(step.id()), plans.byLine(stepLine).map(ProductionOrder::id), "and after a restore");
        plans.clear();
        assertTrue(plans.byLine(stepLine).isEmpty());
    }

    // --- a blocked order's deadline does not run (M20) ------------------------------------------------------------

    /**
     * An order that is waiting for a step of its own plan is not timed out at all: there is nothing it could be making
     * progress on, and the step below it has a running deadline of its own. Without this the root of a chain would die
     * while the machines under it were working perfectly.
     */
    @Test
    void anOrderWaitingForAStepIsNotTimedOut() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(step, root));

        assertEquals(List.of(step.id()), ids(plans.timeOut(START + TIMEOUT, TIMEOUT)),
                "the step is the only order whose deadline runs");
        assertTrue(plans.get(root.id()).orElseThrow().isOpen(), "the order above it is untouched");
        assertEquals(START + TIMEOUT, plans.get(root.id()).orElseThrow().deadlineTick(),
                "and it still carries the deadline it was created with");
    }

    /**
     * The other half of the same rule: an order that stops being blocked starts its timeout over. Its own deadline was
     * set when the plan was created and is long past by the time its last step ends, so without the restart the chain
     * would die in the very tick it became ready to run.
     */
    @Test
    void anOrderThatStopsWaitingStartsItsTimeoutOver() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(step, root));
        long blockedPass = START + TIMEOUT - 1;
        assertTrue(plans.timeOut(blockedPass, TIMEOUT).isEmpty(), "nothing is overdue yet, and the root is waiting");

        // The step's own product arrives: it completes, and the root may fetch its ingredients from now on.
        plans.deliver(step.lines().getFirst().id(), 1, blockedPass, TIMEOUT);
        plans.observeStored(LOG, 2, blockedPass, TIMEOUT);
        assertEquals(ProductionOrderState.COMPLETE, plans.get(step.id()).orElseThrow().state());
        assertFalse(plans.hasOpenChildren(root.id()));

        long afterwards = START + TIMEOUT + 5;
        assertTrue(plans.timeOut(afterwards, TIMEOUT).isEmpty(),
                "the order that was waiting is not killed by the deadline it could never have met");
        assertEquals(afterwards + TIMEOUT, plans.get(root.id()).orElseThrow().deadlineTick(),
                "it has its whole timeout to fetch and run now");
        assertEquals(List.of(root.id()), ids(plans.timeOut(afterwards + TIMEOUT, TIMEOUT)),
                "and a genuinely stuck order still ends");
    }

    /** A parent is free only when <b>every</b> step it waits for has ended, not when the first one has. */
    @Test
    void aParentWaitsForEveryStepOfItsOwn() {
        ProductionOrder<String, String> root = ProductionOrder.start(UUID.randomUUID(), STATION_A, twoIngredients(), 1,
                lineIds, START, TIMEOUT, 0L, null);
        ProductionOrder<String, String> logs = stepOf(root, 0, STATION_B, 1);
        ProductionOrder<String, String> nails = ProductionOrder.step(UUID.randomUUID(), STATION_B,
                ProductionPattern.of(TREE, 1, NAIL, 2), 1, lineIds, START, TIMEOUT, root.lines().get(1).id());
        plans.addAll(List.of(logs, nails, root));

        assertTrue(plans.hasOpenChildren(root.id()));
        plans.cancel(logs.id(), START + 1);
        assertTrue(plans.hasOpenChildren(root.id()), "one step is still making an ingredient");
        plans.cancel(nails.id(), START + 2);
        assertFalse(plans.hasOpenChildren(root.id()), "now nothing is");
    }

    // --- failing a plan (M20) ------------------------------------------------------------------------------------

    /**
     * <b>Fail upward.</b> An order above the one that failed is waiting for something no machine is going to make now,
     * so it is cancelled in the same call — and because a blocked order fetches nothing, cancelling it costs nothing at
     * all.
     */
    @Test
    void failingAStepCancelsEveryOrderAboveIt() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> step = stepOf(root, 0, STATION_B, 1);
        ProductionOrder<String, String> deeper = ProductionOrder.step(UUID.randomUUID(), STATION_B,
                ProductionPattern.of(PLANK, 1, TREE, 2), 1, lineIds, START, TIMEOUT, step.lines().getFirst().id());
        plans.addAll(List.of(deeper, step, root));
        plans.deliver(deeper.lines().getFirst().id(), 1, START, TIMEOUT);
        plans.timeOut(START + TIMEOUT, TIMEOUT);
        assertEquals(ProductionOrderState.TIMED_OUT, plans.get(deeper.id()).orElseThrow().state());

        ProductionOrders.PlanFailure<String, String> failure = plans.failPlan(deeper.id(), START + TIMEOUT);
        assertEquals(List.of(step.id(), root.id()), ids(failure.cancelled()),
                "both orders above the failing one end with it");
        assertTrue(failure.detached().isEmpty());
        assertEquals(0, plans.openCount(), "nothing of the plan is left open");
        assertEquals(1L, failure.unrecovered(), "and the one item a machine really swallowed is reported");
        assertEquals(1L, plans.unrecoveredOf(root.id()), "the same number, read off the plan afterwards");
        assertEquals(0L, plans.outstandingIngredient(LOG), "an ended plan promises nothing any more");
        assertTrue(plans.failPlan(deeper.id(), START + TIMEOUT).isEmpty(), "and running it again finds nothing");
    }

    /**
     * <b>Cancel downward only what has cost nothing.</b> An order whose ingredients are already at a machine is left
     * running instead: those items are in the machine and nothing takes them back out, so its product still comes back
     * and lands in stock as items nobody promised.
     */
    @Test
    void failingARootKeepsAStepWhoseIngredientsAreAlreadyAtAMachine() {
        ProductionOrder<String, String> root = newOrder(STATION_A, 1);
        ProductionOrder<String, String> paid = stepOf(root, 0, STATION_B, 1);
        plans.addAll(List.of(paid, root));
        plans.deliver(paid.lines().getFirst().id(), 1, START, TIMEOUT);
        plans.cancel(root.id(), START + 1);

        ProductionOrders.PlanFailure<String, String> failure = plans.failPlan(root.id(), START + 1);
        assertTrue(failure.cancelled().isEmpty());
        assertEquals(List.of(paid.id()), ids(failure.detached()));
        ProductionOrder<String, String> left = plans.get(paid.id()).orElseThrow();
        assertTrue(left.isOpen(), "it runs on, so the items in the machine still become a product");
        assertFalse(left.isStep(), "as an ordinary order that promises nobody anything");
        assertEquals(0L, failure.unrecovered(), "nothing is lost while it can still finish");
    }

    /** The branch the failure was not on ends too: nobody is going to use what it is making. */
    @Test
    void failingOneBranchEndsTheOtherOne() {
        ProductionOrder<String, String> root = ProductionOrder.start(UUID.randomUUID(), STATION_A, twoIngredients(), 1,
                lineIds, START, TIMEOUT, 0L, null);
        ProductionOrder<String, String> logs = stepOf(root, 0, STATION_B, 1);
        ProductionOrder<String, String> nails = ProductionOrder.step(UUID.randomUUID(), STATION_B,
                ProductionPattern.of(TREE, 1, NAIL, 2), 1, lineIds, START, TIMEOUT, root.lines().get(1).id());
        plans.addAll(List.of(logs, nails, root));
        plans.cancel(logs.id(), START + 1);

        ProductionOrders.PlanFailure<String, String> failure = plans.failPlan(logs.id(), START + 1);
        assertEquals(List.of(nails.id(), root.id()), ids(failure.cancelled()),
                "the sibling branch and the order both branches were for");
        assertEquals(0, plans.openCount());
        assertEquals(0L, plans.outstandingIngredient(TREE), "and no branch promises an ingredient any more");
    }

    /** An order in no plan has nothing to fail, and an unknown one nothing at all. */
    @Test
    void failingAnOrderThatIsInNoPlanChangesNothing() {
        ProductionOrder<String, String> alone = newOrder(STATION_A, 1);
        plans.add(alone);
        plans.cancel(alone.id(), START + 1);
        assertTrue(plans.failPlan(alone.id(), START + 1).isEmpty());
        assertTrue(plans.failPlan(UUID.randomUUID(), START + 1).isEmpty());
        assertEquals(1, plans.size(), "and the order itself is still there to be read");
    }

    /** Hand-edited save data that names an order as its own ancestor cannot make the walk spin. */
    @Test
    void failingAPlanTerminatesOnACycleInSaveData() {
        ProductionOrder<String, String> first = newOrder(STATION_A, 1);
        ProductionOrder<String, String> second = newOrder(STATION_B, 1);
        plans.restore(List.of(asStepOf(first, second.lines().getFirst().id()),
                asStepOf(second, first.lines().getFirst().id())));
        plans.cancel(first.id(), START + 1);
        assertFalse(plans.failPlan(first.id(), START + 1).isEmpty(), "the walk ends and the other order is ended");
        assertEquals(0, plans.openCount());
    }

    private static List<UUID> ids(List<ProductionOrder<String, String>> orders) {
        return orders.stream().map(ProductionOrder::id).toList();
    }

    /** A pattern with two ingredients, for a parent whose lines two steps feed. */
    private static ProductionPattern<String> twoIngredients() {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(LOG, 1), new ProductionEntry<>(NAIL, 2)),
                new ProductionEntry<>(PLANK, 4));
    }

    /** A step making the first ingredient of {@code parent} out of trees. */
    private ProductionOrder<String, String> stepOf(ProductionOrder<String, String> parent, int lineIndex,
            String station, int runs) {
        return ProductionOrder.step(UUID.randomUUID(), station, ProductionPattern.of(TREE, 1, LOG, 2), runs, lineIds,
                START, TIMEOUT, parent.lines().get(lineIndex).id());
    }

    /** {@code order} as a step for {@code parentLine}, as a hand-edited save could describe it. */
    private static ProductionOrder<String, String> asStepOf(ProductionOrder<String, String> order, UUID parentLine) {
        return new ProductionOrder<>(order.id(), order.station(), order.result(), order.resultAmount(), order.lines(),
                order.state(), order.deadlineTick(), order.produced(), order.resultStockSeen(), order.backingRequest(),
                order.promisedToRequest(), order.isRestock(), Optional.of(parentLine));
    }
}
