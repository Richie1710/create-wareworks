package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The chain planner (M20, issue #4, {@code docs/warehouse-system.md} §3.5.6, ADR-032): every branch of the decision a
 * click takes, without a game.
 * <p>
 * The decision is the whole feature — whether a chain can be made, how large it may be, and what a refusal tells the
 * player — so it is settled here, and the GameTests then prove that the orders the planner asks for really are started,
 * really are served by a crane and that an intermediate really passes through a rack.
 */
class ProductionPlannerTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String STICK = "stick";
    private static final String CHEST = "chest";
    private static final String TABLE = "table";
    private static final String NAIL = "nail";
    private static final String INGOT = "ingot";
    private static final String BLOCK = "block";

    private static final String SAW = "saw";
    private static final String CRAFTER = "crafter";

    /** One log makes four planks — the whole-runs case the feature is written around. */
    private static final ProductionPattern<String> PLANKS = ProductionPattern.of(LOG, 1, PLANK, 4);
    /** Eight planks make a chest: the second level of the chain the feature exists for. */
    private static final ProductionPattern<String> CHESTS = ProductionPattern.of(PLANK, 8, CHEST, 1);
    /** Two planks make four sticks, so a plan can need planks in two branches at once. */
    private static final ProductionPattern<String> STICKS = ProductionPattern.of(PLANK, 2, STICK, 4);

    private final Map<String, Long> stock = new LinkedHashMap<>();

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static ProductionPattern<String> makes(String result, int count, Object... ingredients) {
        List<ProductionEntry<String>> entries = new ArrayList<>(ingredients.length / 2);
        for (int i = 0; i < ingredients.length; i += 2)
            entries.add(new ProductionEntry<>((String) ingredients[i], ((Number) ingredients[i + 1]).intValue()));
        return new ProductionPattern<>(entries, new ProductionEntry<>(result, count));
    }

    private static List<StationPattern<String, String>> aisle(Object... stationsAndPatterns) {
        List<StationPattern<String, String>> patterns = new ArrayList<>(stationsAndPatterns.length / 2);
        for (int i = 0; i < stationsAndPatterns.length; i += 2) {
            @SuppressWarnings("unchecked")
            ProductionPattern<String> pattern = (ProductionPattern<String>) stationsAndPatterns[i + 1];
            patterns.add(StationPattern.of((String) stationsAndPatterns[i], pattern));
        }
        return List.copyOf(patterns);
    }

    private void stocked(String key, long amount) {
        stock.put(key, amount);
    }

    private ProductionPlanInput<String, String> input(List<StationPattern<String, String>> patterns) {
        return input(patterns, PlanLimits.UNBOUNDED, 16);
    }

    private ProductionPlanInput<String, String> input(List<StationPattern<String, String>> patterns,
            PlanLimits limits, int freeOrderSlots) {
        return ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock), limits, freeOrderSlots);
    }

    /** An aisle whose order cap is {@code orderSlots} and that already has all but {@code freeOrderSlots} of it open. */
    private ProductionPlanInput<String, String> busy(List<StationPattern<String, String>> patterns,
            PlanLimits limits, int freeOrderSlots, int orderSlots) {
        return ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock), limits, freeOrderSlots, orderSlots);
    }

    private static ProductionPlan<String, String> planned(ProductionPlanResult<String, String> result) {
        assertTrue(result.accepted(), () -> "refused with " + result.refusal().orElse(null) + " about "
                + result.about().orElse(null));
        return result.orElseThrow();
    }

    /** Asserts the refusal and, which is the point of a refusal, the item it names. */
    private static void refused(ProductionPlanResult<String, String> result, PlanRefusal reason, String about) {
        assertFalse(result.accepted(), "expected a refusal");
        assertEquals(Optional.of(reason), result.refusal());
        assertEquals(Optional.of(about), result.about(), "a refusal has to name the item it is about");
        assertTrue(result.refusedWith(reason));
    }

    /** The items a plan's steps make, children before parents. */
    private static List<String> results(ProductionPlan<String, String> plan) {
        List<String> results = new ArrayList<>(plan.steps());
        for (PlanNode<String, String> node : plan.nodes())
            results.add(node.result());
        return results;
    }

    // --- a plain single-level order is exactly what M11 answers -----------------------------------------------------

    /**
     * Ordering an item whose ingredients are in the racks is one order, with the runs and the promise
     * {@code startProductionOrder} has always computed.
     */
    @Test
    void aSingleLevelOrderIsOneStep() {
        stocked(LOG, 10);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(SAW, PLANKS)), PLANK, 8));
        assertTrue(plan.isSingleLevel());
        assertEquals(1, plan.steps());
        assertEquals(0, plan.depth());
        PlanNode<String, String> root = plan.root();
        assertTrue(root.isRoot());
        assertEquals(PlanNode.NO_PARENT, root.parent());
        assertEquals(SAW, root.station());
        assertEquals(PLANK, root.result());
        assertEquals(2, root.runs(), "two runs of four planks");
        assertEquals(8L, root.needed());
        assertEquals(8, root.output());
        assertEquals(8L, plan.rootPromise());
        assertFalse(plan.isClamped());
        assertEquals(Map.of(LOG, 2L), plan.leafDemand());
        assertEquals(2L, plan.ingredientItems());
    }

    /**
     * A pattern makes whole runs, so an order regularly yields more than was asked for; the promise is what the request
     * wanted and the surplus is promised to nobody ({@code ProductionOrder#promisedToRequest}).
     */
    @Test
    void wholeRunsOvershootAndThePromiseDoesNot() {
        stocked(LOG, 10);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(SAW, PLANKS)), PLANK, 5));
        assertEquals(2, plan.root().runs());
        assertEquals(8, plan.root().output());
        assertEquals(5L, plan.rootPromise());
        assertEquals(5L, plan.root().needed(), "the root is created for what the click gets, not for the whole run");
        assertFalse(plan.isClamped(), "the click got everything it asked for");
    }

    /** A clamped plan's root is created for what it promises, which is also the amount a step panel reads. */
    @Test
    void aClampedRootIsCreatedForWhatItPromises() {
        stocked(LOG, 3);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(SAW, PLANKS)), PLANK, 40));
        assertEquals(12L, plan.rootPromise());
        assertEquals(12L, plan.root().needed());
        assertEquals(12, plan.root().output(), "three whole runs, and nothing was cut");
    }

    /** The pre-M20 clamp: the runs are bounded by what the racks can pay for, and the request is told less. */
    @Test
    void theRunsAreClampedByWhatTheRacksCanPayFor() {
        stocked(LOG, 3);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(SAW, PLANKS)), PLANK, 40));
        assertEquals(3, plan.root().runs(), "three logs, three runs");
        assertEquals(12L, plan.rootPromise());
        assertTrue(plan.isClamped());
        assertEquals(Map.of(LOG, 3L), plan.leafDemand());
    }

    /** The pre-M20 refusal, and the sentence the whole feature is judged by: name the item that is missing. */
    @Test
    void anIngredientNothingMakesIsRefusedByName() {
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(SAW, PLANKS)), PLANK, 4);
        refused(result, PlanRefusal.MISSING_INGREDIENT, LOG);
    }

    /** The first ingredient one run cannot be paid for is the one named, as {@code firstMissingIngredient} does. */
    @Test
    void theFirstUnpayableIngredientIsTheOneNamed() {
        stocked(PLANK, 100);
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(
                input(aisle(CRAFTER, makes(TABLE, 1, PLANK, 4, NAIL, 2))), TABLE, 1);
        refused(result, PlanRefusal.MISSING_INGREDIENT, NAIL);
    }

    @Test
    void nothingMakesTheOrderedItemAtAll() {
        stocked(LOG, 10);
        refused(ProductionPlanner.plan(input(aisle(SAW, PLANKS)), TABLE, 1), PlanRefusal.NO_PATTERN, TABLE);
    }

    @Test
    void anAmountBelowOneIsNotAnOrder() {
        stocked(LOG, 10);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS));
        assertThrows(IllegalArgumentException.class, () -> ProductionPlanner.plan(input, PLANK, 0L));
    }

    // --- the chain --------------------------------------------------------------------------------------------------

    /** The feature: a chest with logs in the racks and no planks anywhere. */
    @Test
    void aTwoStepChainIsPlannedChildrenFirst() {
        stocked(LOG, 4);
        ProductionPlan<String, String> plan = planned(
                ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, CHESTS)), CHEST, 1));
        assertEquals(2, plan.steps());
        assertEquals(1, plan.depth());
        assertEquals(List.of(PLANK, CHEST), results(plan), "children before parents, the root last");
        PlanNode<String, String> planks = plan.node(0);
        PlanNode<String, String> chest = plan.root();
        assertEquals(1, chest.index());
        assertEquals(0, chest.depth());
        assertEquals(1, planks.parent(), "the step feeds the root");
        assertEquals(1, planks.depth());
        assertEquals(SAW, planks.station());
        assertEquals(8L, planks.needed(), "the chest's whole plank line");
        assertEquals(2, planks.runs());
        assertEquals(8, planks.output());
        assertEquals(List.of(planks), plan.childrenOf(1));
        assertTrue(plan.childrenOf(0).isEmpty());
        // Out of the racks come only the logs: the planks are made, so they are not part of what the plan spends.
        assertEquals(Map.of(LOG, 2L), plan.leafDemand());
        assertEquals(10L, plan.ingredientItems(), "8 planks into the crafter and 2 logs into the saw");
        assertEquals(1L, plan.rootPromise());
    }

    /** No depth limit (the project owner's decision): a chain may be as deep as the player's machines make it. */
    @Test
    void aChainMayBeAsDeepAsItLikes() {
        stocked("a", 64);
        List<StationPattern<String, String>> patterns = aisle("s", ProductionPattern.of("a", 1, "b", 2),
                "s", ProductionPattern.of("b", 1, "c", 2), "s", ProductionPattern.of("c", 1, "d", 2),
                "s", ProductionPattern.of("d", 1, "e", 2), "s", ProductionPattern.of("e", 1, "f", 2));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), "f", 2));
        assertEquals(5, plan.steps());
        assertEquals(4, plan.depth());
        assertEquals(List.of("b", "c", "d", "e", "f"), results(plan));
        assertEquals(Map.of("a", 1L), plan.leafDemand());
    }

    /** What is in the racks is used; only the rest becomes a step of its own. */
    @Test
    void whatIsInStockIsPaidForAndOnlyTheRestIsMade() {
        stocked(PLANK, 3);
        stocked(LOG, 4);
        ProductionPlan<String, String> plan = planned(
                ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, CHESTS)), CHEST, 1));
        assertEquals(2, plan.steps());
        assertEquals(5L, plan.node(0).needed(), "three planks are there, five are made");
        assertEquals(2, plan.node(0).runs(), "whole runs: two runs make eight");
        assertEquals(Map.of(PLANK, 3L, LOG, 2L), plan.leafDemand());
    }

    @Test
    void anIngredientTheRacksPayForIsNotExpanded() {
        stocked(PLANK, 64);
        stocked(LOG, 64);
        ProductionPlan<String, String> plan = planned(
                ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, CHESTS)), CHEST, 2));
        assertTrue(plan.isSingleLevel(), "a pattern for planks exists, but the planks are there");
        assertEquals(Map.of(PLANK, 16L), plan.leafDemand());
    }

    /**
     * One budget, two branches: the planks the first branch takes out of the racks are gone for the second one, which
     * gets a step of its own instead of the same items a second time.
     */
    @Test
    void oneBudgetPaysForTwoBranchesOnlyOnce() {
        stocked(PLANK, 4);
        stocked(LOG, 8);
        // A table needs four planks and two sticks, and the sticks are made of planks as well.
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, STICKS,
                CRAFTER, makes(TABLE, 1, PLANK, 4, STICK, 2));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), TABLE, 1));
        assertEquals(List.of(PLANK, STICK, TABLE), results(plan),
                "the four planks in stock go to the table, and the sticks get planks of their own");
        assertEquals(Map.of(PLANK, 4L, LOG, 1L), plan.leafDemand());
        assertEquals(2L, plan.node(0).needed(), "the sticks' own plank line");
        assertEquals(1, plan.node(0).parent(), "those planks are for the sticks");
        assertEquals(2, plan.node(1).parent(), "and the sticks are for the table");
        assertEquals(2, plan.root().index());
        assertEquals(2, plan.node(0).depth());
    }

    /** The second branch is refused by name when the budget really has run out. */
    @Test
    void theSecondBranchIsRefusedByNameWhenTheBudgetRunsOut() {
        stocked(PLANK, 4);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, STICKS,
                CRAFTER, makes(TABLE, 1, PLANK, 4, STICK, 2));
        refused(ProductionPlanner.plan(input(patterns), TABLE, 1), PlanRefusal.MISSING_INGREDIENT, LOG);
    }

    // --- cycles -----------------------------------------------------------------------------------------------------

    /** Two patterns that are inverses of each other: refused before anything is converted. */
    @Test
    void aChainThatComesBackToAnItemIsRefused() {
        List<StationPattern<String, String>> patterns = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9));
        refused(ProductionPlanner.plan(input(patterns), BLOCK, 1), PlanRefusal.LOOP, BLOCK);
    }

    /** Even with the ancestor's own product in the racks: converting an item into itself is never what a click meant. */
    @Test
    void aLoopIsRefusedEvenWhenTheItemIsInStock() {
        stocked(BLOCK, 64);
        List<StationPattern<String, String>> patterns = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9));
        refused(ProductionPlanner.plan(input(patterns), BLOCK, 1), PlanRefusal.LOOP, BLOCK);
    }

    /** A longer way round is the same answer, and it names the item the chain comes back to. */
    @Test
    void aLongerCycleIsRefusedToo() {
        List<StationPattern<String, String>> patterns = aisle("s", ProductionPattern.of("b", 1, "a", 1),
                "s", ProductionPattern.of("c", 1, "b", 1), "s", ProductionPattern.of("a", 1, "c", 1));
        refused(ProductionPlanner.plan(input(patterns), "a", 1), PlanRefusal.LOOP, "a");
    }

    /** A cycle costs nothing: it is refused, so no step of the chain exists at all. */
    @Test
    void aLoopedPatternPairIsStillUsableOnItsOwn() {
        stocked(INGOT, 64);
        List<StationPattern<String, String>> patterns = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9));
        // With the ingots in the racks there is nothing to expand, so the perfectly legal pair is ordered as one step.
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), BLOCK, 1));
        assertTrue(plan.isSingleLevel());
        assertEquals(Map.of(INGOT, 9L), plan.leafDemand());
    }

    // --- the safety stop ------------------------------------------------------------------------------------------

    /** A pause blocks planning, not only ordering (ADR-027 extended): the ordered item itself. */
    @Test
    void aPausedOrderedItemIsRefused() {
        stocked(LOG, 64);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS)).withPaused(Set.of(PLANK));
        refused(ProductionPlanner.plan(input, PLANK, 4), PlanRefusal.PAUSED, PLANK);
    }

    /** And a step of the chain: the next click must not rebuild the same chain into the same broken machine. */
    @Test
    void aPausedStepIsNotExpanded() {
        stocked(LOG, 64);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS, CRAFTER, CHESTS))
                .withPaused(Set.of(PLANK));
        refused(ProductionPlanner.plan(input, CHEST, 1), PlanRefusal.PAUSED, PLANK);
    }

    /** A pause on an item the chain does not need changes nothing. */
    @Test
    void aPauseOnAnotherItemDoesNotStopThePlan() {
        stocked(LOG, 64);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS, CRAFTER, CHESTS))
                .withPaused(Set.of(TABLE));
        assertEquals(2, planned(ProductionPlanner.plan(input, CHEST, 1)).steps());
    }

    // --- the bounds ------------------------------------------------------------------------------------------------

    /** One step is the off switch: the answer is the pre-M20 one for everything. */
    @Test
    void oneStepIsRecursionOff() {
        stocked(LOG, 64);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS, CRAFTER, CHESTS),
                PlanLimits.SINGLE_LEVEL, 16);
        refused(ProductionPlanner.plan(input, CHEST, 1), PlanRefusal.TOO_MANY_STEPS, PLANK);
        // …while what the racks can pay for is planned exactly as before.
        stocked(PLANK, 8);
        ProductionPlanInput<String, String> filled = input(aisle(SAW, PLANKS, CRAFTER, CHESTS),
                PlanLimits.SINGLE_LEVEL, 16);
        assertTrue(planned(ProductionPlanner.plan(filled, CHEST, 1)).isSingleLevel());
    }

    @Test
    void theStepCountBoundsTheChainAndNamesWhereItStopped() {
        stocked("a", 64);
        List<StationPattern<String, String>> patterns = aisle("s", ProductionPattern.of("a", 1, "b", 1),
                "s", ProductionPattern.of("b", 1, "c", 1), "s", ProductionPattern.of("c", 1, "d", 1));
        // Three steps are needed for one d; two are allowed, and the walk stops at the deepest item it wanted.
        refused(ProductionPlanner.plan(input(patterns, new PlanLimits(2, PlanLimits.MAX_INGREDIENT_ITEMS), 16), "d", 1),
                PlanRefusal.TOO_MANY_STEPS, "b");
        assertEquals(3, planned(ProductionPlanner.plan(
                input(patterns, new PlanLimits(3, PlanLimits.MAX_INGREDIENT_ITEMS), 16), "d", 1)).steps());
    }

    /** The free order slots bound the same thing and say so with the reason whose cure is "wait". */
    @Test
    void theFreeOrderSlotsAreTheOtherStepBound() {
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, CHESTS);
        refused(ProductionPlanner.plan(busy(patterns, PlanLimits.UNBOUNDED, 1, 16), CHEST, 1),
                PlanRefusal.ORDERS_BUSY, PLANK);
        refused(ProductionPlanner.plan(busy(patterns, PlanLimits.UNBOUNDED, 0, 16), CHEST, 1),
                PlanRefusal.ORDERS_BUSY, CHEST);
        assertEquals(2, planned(ProductionPlanner.plan(input(patterns, PlanLimits.UNBOUNDED, 2), CHEST, 1)).steps());
    }

    /**
     * "Wait for an order" is only said while an order is <b>running</b>. An idle aisle whose own order cap is too small
     * for the chain is told that the configuration is what has to change, because waiting for nothing does not help
     * (M20 review fix: with the shipped defaults the free slots are always fewer than {@code maxProductionPlanSteps}, so
     * the older test could never be false and an idle aisle was told to wait).
     */
    @Test
    void anIdleAisleIsNeverToldToWait() {
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, CHESTS);
        // Every slot of a cap of one is free: nothing is running, and a chain of two still does not fit.
        refused(ProductionPlanner.plan(busy(patterns, PlanLimits.UNBOUNDED, 1, 1), CHEST, 1),
                PlanRefusal.TOO_MANY_STEPS, PLANK);
        // The very same shortage with one of two slots taken is the reason whose cure is to wait.
        refused(ProductionPlanner.plan(busy(patterns, PlanLimits.UNBOUNDED, 1, 2), CHEST, 1),
                PlanRefusal.ORDERS_BUSY, PLANK);
    }

    /** A plan over the item budget is made smaller rather than refused — one run is the smallest thing there is. */
    @Test
    void aPlanOverTheItemBudgetIsMadeSmaller() {
        stocked(LOG, 64);
        // One chest costs 8 planks into the crafter plus 2 logs into the saw: 10 ingredient items per chest.
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS, CRAFTER, CHESTS),
                new PlanLimits(PlanLimits.MAX_STEPS, 20L), 16);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input, CHEST, 4));
        assertEquals(2L, plan.rootPromise(), "two chests fit into twenty ingredient items, four do not");
        assertTrue(plan.isClamped());
        assertEquals(20L, plan.ingredientItems());
        assertEquals(2, plan.root().runs());
        assertEquals(Map.of(LOG, 4L), plan.leafDemand());
    }

    /** …and refused, naming the step that went over, when not even one run of the ordered item fits. */
    @Test
    void notEvenOneRunFitsTheItemBudget() {
        stocked(LOG, 64);
        // The root's own 8 planks are already over a budget of 5.
        refused(ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, CHESTS), new PlanLimits(8, 5L), 16), CHEST, 1),
                PlanRefusal.TOO_MANY_INGREDIENT_ITEMS, CHEST);
        // With room for the root but not for its step, the step is what is named.
        refused(ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, CHESTS), new PlanLimits(8, 9L), 16), CHEST, 1),
                PlanRefusal.TOO_MANY_INGREDIENT_ITEMS, PLANK);
    }

    /** An intermediate that would not fit under its own maximum is refused at the click, naming it. */
    @Test
    void anIntermediateMustFitUnderItsMaximum() {
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, CHESTS);
        ProductionPlanInput<String, String> tight = input(patterns).withHeadroom(key -> PLANK.equals(key) ? 4L
                : ProductionPlanInput.UNLIMITED_ROOM);
        refused(ProductionPlanner.plan(tight, CHEST, 1), PlanRefusal.NO_ROOM, PLANK);
        ProductionPlanInput<String, String> enough = input(patterns).withHeadroom(key -> PLANK.equals(key) ? 8L
                : ProductionPlanInput.UNLIMITED_ROOM);
        assertEquals(2, planned(ProductionPlanner.plan(enough, CHEST, 1)).steps());
    }

    /** The ordered item's own maximum is not the planner's business: a player may order past it. */
    @Test
    void theOrderedItemsOwnMaximumIsNotChecked() {
        stocked(PLANK, 64);
        ProductionPlanInput<String, String> input = input(aisle(CRAFTER, CHESTS)).withHeadroom(key -> 0L);
        assertTrue(planned(ProductionPlanner.plan(input, CHEST, 1)).isSingleLevel());
    }

    /** Two steps for the same intermediate share one maximum, so the second one does not fill the rack again. */
    @Test
    void twoStepsForOneIntermediateShareItsMaximum() {
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, STICKS,
                CRAFTER, makes(TABLE, 1, PLANK, 4, STICK, 2));
        ProductionPlanInput<String, String> input = input(patterns).withHeadroom(key -> PLANK.equals(key) ? 4L
                : ProductionPlanInput.UNLIMITED_ROOM);
        // The first branch makes one run of four planks and fills the room; the second one has nowhere to put its own.
        refused(ProductionPlanner.plan(input, TABLE, 1), PlanRefusal.NO_ROOM, PLANK);
    }

    /**
     * <b>A plan is a tree, not a graph</b>, and that is a decision rather than an accident: two branches that need the
     * same intermediate get a step each, even when the first one's whole run already makes enough for both. The plan
     * therefore commits two logs where one would have done, and the surplus planks land in a rack as items nobody
     * promised ({@link ProductionPlan}).
     */
    @Test
    void twoBranchesForOneIntermediateGetAStepEach() {
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(SAW, PLANKS, CRAFTER, STICKS,
                CRAFTER, makes(TABLE, 1, PLANK, 4, STICK, 2));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), TABLE, 1));
        assertEquals(4, plan.steps(), "planks for the table, planks for the sticks, the sticks, the table");
        assertEquals(List.of(PLANK, PLANK, STICK, TABLE), results(plan));
        assertEquals(Map.of(LOG, 2L), plan.leafDemand(),
                "one log per plank step, although one step's four planks would cover both branches");
    }

    // --- how a pattern is chosen -----------------------------------------------------------------------------------

    /** The pattern that could make the most right now wins, exactly as a request and a rule pick it. */
    @Test
    void theBestPatternWinsAndTiesGoToAisleOrder() {
        stocked(LOG, 1);
        stocked(NAIL, 64);
        ProductionPattern<String> fromNails = ProductionPattern.of(NAIL, 1, PLANK, 1);
        ProductionPlan<String, String> plan = planned(
                ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, fromNails)), PLANK, 4));
        assertEquals(CRAFTER, plan.root().station(), "64 nails make more planks than one log does");
        assertSame(fromNails, plan.root().pattern());
        // With nothing to measure, the first pattern in aisle order is used — which is what makes a chain plannable at
        // all: an item the chain has yet to make can make nothing right now, so the pre-M20 choice answered "none".
        // The item the refusal names is what says which of the two was taken.
        stock.clear();
        refused(ProductionPlanner.plan(input(aisle(CRAFTER, fromNails, SAW, PLANKS, CRAFTER, CHESTS)), CHEST, 1),
                PlanRefusal.MISSING_INGREDIENT, NAIL);
        refused(ProductionPlanner.plan(input(aisle(SAW, PLANKS, CRAFTER, fromNails, CRAFTER, CHESTS)), CHEST, 1),
                PlanRefusal.MISSING_INGREDIENT, LOG);
    }

    /**
     * <b>A dead-end pattern is not the answer</b> (M20 review fix). Two patterns make planks: from nails, which nothing
     * supplies, and from boards, which the aisle can make out of logs. Nothing is producible yet, so the nail pattern
     * wins on aisle order — and the chain behind it is a dead end. The planner has to try the other one rather than
     * refuse a chain the aisle really can make, and rather than send the player looking for a nail.
     */
    @Test
    void aDeadEndPatternFallsBackToTheNextOne() {
        String board = "board";
        stocked(LOG, 64);
        List<StationPattern<String, String>> patterns = aisle(
                CRAFTER, ProductionPattern.of(NAIL, 1, PLANK, 1),
                SAW, ProductionPattern.of(board, 1, PLANK, 4),
                SAW, ProductionPattern.of(LOG, 1, board, 1),
                CRAFTER, makes(CHEST, 1, PLANK, 8));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), CHEST, 1));
        assertEquals(List.of(board, PLANK, CHEST), results(plan), "log to board to plank to chest");
        assertEquals(Map.of(LOG, 2L), plan.leafDemand());
        // …and when neither route can be made, the refusal is the preferred pattern's, so it stays stable.
        stock.clear();
        refused(ProductionPlanner.plan(input(patterns), CHEST, 1), PlanRefusal.MISSING_INGREDIENT, NAIL);
    }

    /**
     * A pattern that would convert an item into itself is refused, and that refusal is about the <b>pattern</b> and not
     * about the item: the other route to the same item is tried, and a chain the aisle can make goes through. Iron
     * blocks out of ore while a block press and an ingot press sit side by side.
     */
    @Test
    void anInversePatternDoesNotBlockTheOtherRouteToAnItem() {
        String ore = "ore";
        stocked(BLOCK, 3);
        stocked(ore, 20);
        List<StationPattern<String, String>> patterns = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9), "furnace", ProductionPattern.of(ore, 1, INGOT, 1));
        // The inverse pattern could make 27 ingots out of the three blocks in stock, so it is the preferred one for
        // ingots - and it is a loop under a block order. The furnace is what the plan has to fall back on.
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(patterns), BLOCK, 2));
        assertEquals(List.of(INGOT, BLOCK), results(plan));
        assertEquals(Map.of(ore, 18L), plan.leafDemand(), "the ore route, not the three blocks in the racks");
        assertEquals(2, plan.root().runs());
    }

    /**
     * When every pattern for an item fails, a refusal that is <b>not</b> a loop is the one reported: a loop says that
     * one pattern cannot be used here, while "an ingredient is missing" is something a player can act on.
     */
    @Test
    void aRefusalThatIsNotALoopIsPreferredOverOne() {
        String ore = "ore";
        List<StationPattern<String, String>> patterns = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9), "furnace", ProductionPattern.of(ore, 1, INGOT, 1));
        // Nothing at all in the racks: the inverse pattern is a loop, the furnace has no ore, and the ore is what the
        // player has to supply.
        refused(ProductionPlanner.plan(input(patterns), BLOCK, 1), PlanRefusal.MISSING_INGREDIENT, ore);
    }

    /** The same aisle always answers the same plan, and planning never spends the input's own budget. */
    @Test
    void planningIsReproducibleAndSpendsNothing() {
        stocked(LOG, 9);
        stocked(PLANK, 3);
        ProductionPlanInput<String, String> input = input(aisle(SAW, PLANKS, CRAFTER, CHESTS));
        ProductionPlan<String, String> first = planned(ProductionPlanner.plan(input, CHEST, 2));
        ProductionPlan<String, String> second = planned(ProductionPlanner.plan(input, CHEST, 2));
        assertEquals(first, second);
        assertTrue(input.budget().nothingSpent(), "the caller's budget is untouched, so a plan can be taken twice");
        assertEquals(9L, input.budget().available(LOG));
    }

    // --- completeness ----------------------------------------------------------------------------------------------

    /** Every refusal a player can be shown is reachable, and each one names the item it is about. */
    @Test
    void everyRefusalIsReachableAndNamesItsItem() {
        Set<PlanRefusal> seen = EnumSet.noneOf(PlanRefusal.class);
        List<StationPattern<String, String>> chain = aisle(SAW, PLANKS, CRAFTER, CHESTS);
        List<StationPattern<String, String>> loop = aisle("press", ProductionPattern.of(INGOT, 9, BLOCK, 1),
                "press", ProductionPattern.of(BLOCK, 1, INGOT, 9));
        record Case(PlanRefusal reason, String about, ProductionPlanResult<String, String> result) {
        }
        stocked(LOG, 64);
        List<Case> cases = List.of(
                new Case(PlanRefusal.NO_PATTERN, TABLE, ProductionPlanner.plan(input(chain), TABLE, 1)),
                new Case(PlanRefusal.PAUSED, PLANK,
                        ProductionPlanner.plan(input(chain).withPaused(Set.of(PLANK)), CHEST, 1)),
                new Case(PlanRefusal.LOOP, BLOCK, ProductionPlanner.plan(input(loop), BLOCK, 1)),
                new Case(PlanRefusal.NO_ROOM, PLANK, ProductionPlanner.plan(
                        input(chain).withHeadroom(key -> PLANK.equals(key) ? 1L : ProductionPlanInput.UNLIMITED_ROOM),
                        CHEST, 1)),
                new Case(PlanRefusal.TOO_MANY_STEPS, PLANK,
                        ProductionPlanner.plan(input(chain, PlanLimits.SINGLE_LEVEL, 16), CHEST, 1)),
                new Case(PlanRefusal.ORDERS_BUSY, PLANK,
                        ProductionPlanner.plan(busy(chain, PlanLimits.UNBOUNDED, 1, 16), CHEST, 1)),
                new Case(PlanRefusal.TOO_MANY_INGREDIENT_ITEMS, CHEST,
                        ProductionPlanner.plan(input(chain, new PlanLimits(8, 5L), 16), CHEST, 1)));
        for (Case one : cases) {
            refused(one.result(), one.reason(), one.about());
            seen.add(one.reason());
        }
        stock.clear();
        ProductionPlanResult<String, String> missing = ProductionPlanner.plan(input(chain), CHEST, 1);
        refused(missing, PlanRefusal.MISSING_INGREDIENT, LOG);
        seen.add(PlanRefusal.MISSING_INGREDIENT);
        assertEquals(EnumSet.allOf(PlanRefusal.class), seen, "every refusal has a case here");
        for (PlanRefusal refusal : PlanRefusal.values())
            assertTrue(refusal.langKey().startsWith("gui.production.plan.refusal."), refusal::langKey);
    }

    // --- a randomised comparison against a straightforward recomputation -------------------------------------------

    /**
     * The planner against a naive recomputation: for every generated aisle, the plan it returns must be the largest one
     * a plain run-by-run search can find, and its cost and step count must be that search's numbers.
     * <p>
     * The naive search is deliberately written the slow way — it tries every number of runs from one upwards, walking
     * the chain with a plain map of what it has spent — so the two implementations share nothing but the rules.
     */
    @Test
    void randomAislesMatchAStraightforwardRecomputation() {
        Random random = new Random(20260927L);
        int chains = 0;
        int clamped = 0;
        int refused = 0;
        for (int round = 0; round < 400; round++) {
            List<StationPattern<String, String>> patterns = randomAisle(random);
            Map<String, Long> available = randomStock(random);
            PlanLimits limits = new PlanLimits(1 + random.nextInt(8), 8L + random.nextInt(400));
            int slots = 1 + random.nextInt(8);
            // The ordered item is one the aisle has a pattern for: that nothing makes an item is a case of its own.
            String key = "i" + (1 + random.nextInt(RANDOM_ITEMS - 1));
            long amount = 1L + random.nextInt(16);
            ProductionPlanInput<String, String> input = ProductionPlanInput.of(patterns, PlanBudget.ofMap(available),
                    limits, slots);
            ProductionPlanResult<String, String> result = ProductionPlanner.plan(input, key, amount);
            Naive naive = new Naive(patterns, available, limits, Math.min(limits.maxSteps(), slots));
            Naive.Outcome best = naive.largestThatFits(key, amount);
            String where = "round " + round + ", " + key + " x" + amount + ", " + limits + ", slots " + slots
                    + ", stock " + available + ", patterns " + patterns;
            if (best == null) {
                assertFalse(result.accepted(), () -> "the planner made a plan the naive search could not: " + where);
                refused++;
                continue;
            }
            ProductionPlan<String, String> plan = planned(result);
            if (!plan.isSingleLevel())
                chains++;
            if (plan.isClamped())
                clamped++;
            assertEquals(best.runs(), plan.root().runs(), () -> "not the largest plan that fits: " + where);
            assertEquals(best.steps(), plan.steps(), () -> "step count: " + where);
            assertEquals(best.cost(), plan.ingredientItems(), () -> "ingredient items: " + where);
            assertEquals(best.spent(), plan.leafDemand(), () -> "what comes out of the racks: " + where);
            // What a plan takes out of the racks was really there, whatever the shape of the chain.
            for (Map.Entry<String, Long> leaf : plan.leafDemand().entrySet())
                assertTrue(leaf.getValue() <= available.getOrDefault(leaf.getKey(), 0L), where);
        }
        // The generated aisles have to be worth comparing: real chains, real clamps and real refusals, not 400 rounds
        // of one-step plans.
        String seen = chains + " chains, " + clamped + " clamped, " + refused + " refused";
        assertTrue(chains > 100, seen);
        assertTrue(clamped > 30, seen);
        assertTrue(refused > 20, seen);
    }

    private static final int RANDOM_ITEMS = 6;

    /** An acyclic aisle: a pattern for item {@code i} only ever consumes items before it. */
    private static List<StationPattern<String, String>> randomAisle(Random random) {
        List<StationPattern<String, String>> patterns = new ArrayList<>();
        for (int item = 1; item < RANDOM_ITEMS; item++) {
            int count = 1 + random.nextInt(2); // one or two patterns per item, sometimes competing for the same items
            for (int n = 0; n < count; n++) {
                List<ProductionEntry<String>> ingredients = new ArrayList<>(2);
                int first = random.nextInt(item);
                ingredients.add(new ProductionEntry<>("i" + first, 1 + random.nextInt(3)));
                if (item > 1 && random.nextBoolean()) {
                    int second = random.nextInt(item);
                    if (second != first)
                        ingredients.add(new ProductionEntry<>("i" + second, 1 + random.nextInt(3)));
                }
                patterns.add(StationPattern.of("s" + item + "_" + n,
                        new ProductionPattern<>(ingredients, new ProductionEntry<>("i" + item,
                                1 + random.nextInt(4)))));
            }
        }
        return List.copyOf(patterns);
    }

    /** The raw material is usually there, the items further up sometimes — which is the world a chain is made for. */
    private static Map<String, Long> randomStock(Random random) {
        Map<String, Long> stock = new LinkedHashMap<>();
        stock.put("i0", 4L + random.nextInt(48));
        for (int item = 1; item < RANDOM_ITEMS; item++) {
            if (random.nextBoolean())
                stock.put("i" + item, (long) random.nextInt(9));
        }
        return stock;
    }

    /**
     * A straightforward recomputation of the same rules, written for clarity rather than for speed: walk the chain for a
     * fixed number of runs of the ordered item, counting the steps and the ingredient items, and try every number of
     * runs from one upwards.
     */
    private static final class Naive {
        private final List<StationPattern<String, String>> patterns;
        private final Map<String, Long> stock;
        private final PlanLimits limits;
        private final int stepLimit;

        record Outcome(int runs, int steps, long cost, Map<String, Long> spent) {
        }

        private Naive(List<StationPattern<String, String>> patterns, Map<String, Long> stock, PlanLimits limits,
                int stepLimit) {
            this.patterns = patterns;
            this.stock = stock;
            this.limits = limits;
            this.stepLimit = stepLimit;
        }

        /**
         * The largest number of runs of {@code key} that can be made, or {@code null} when not even one can — found by
         * trying <b>every</b> number of runs rather than by a search, which also checks the monotonicity the planner's
         * binary search relies on: once a number of runs does not fit, no larger one may.
         */
        Outcome largestThatFits(String key, long amount) {
            // The ordered item's own patterns are tried in the same ranking order, and the first one that can make at
            // least one run is the one the plan is built from.
            for (ProductionPattern<String> root : ranked(key)) {
                int wanted = root.runsFor(amount);
                Outcome best = null;
                boolean failed = false;
                for (int runs = 1; runs <= wanted; runs++) {
                    Outcome tried = attempt(key, root, Math.min(amount, (long) root.result().count() * runs), runs);
                    if (tried == null) {
                        failed = true;
                        continue;
                    }
                    if (failed)
                        throw new AssertionError(runs + " runs fit where fewer did not, so the cost is not monotone");
                    best = tried;
                }
                if (best != null)
                    return best;
            }
            return null;
        }

        private Outcome attempt(String key, ProductionPattern<String> root, long needed, int runs) {
            Map<String, Long> spent = new LinkedHashMap<>();
            int[] steps = {0};
            long[] cost = {0L};
            return tryPattern(key, needed, root, spent, steps, cost, new ArrayList<>())
                    ? new Outcome(runs, steps[0], cost[0], spent) : null;
        }

        /**
         * Tries every pattern for {@code key} in ranking order until one of them yields a subtree, putting everything a
         * failed one spent back first — the fallback the planner has to make a chain plannable at all.
         */
        private boolean walk(String key, long needed, Map<String, Long> spent, int[] steps, long[] cost,
                List<String> path) {
            if (path.contains(key))
                return false;
            for (ProductionPattern<String> pattern : ranked(key)) {
                Map<String, Long> spentBefore = new LinkedHashMap<>(spent);
                int stepsBefore = steps[0];
                long costBefore = cost[0];
                if (tryPattern(key, needed, pattern, spent, steps, cost, path))
                    return true;
                spent.clear();
                spent.putAll(spentBefore);
                steps[0] = stepsBefore;
                cost[0] = costBefore;
            }
            return false;
        }

        private boolean tryPattern(String key, long needed, ProductionPattern<String> pattern, Map<String, Long> spent,
                int[] steps, long[] cost, List<String> path) {
            for (ProductionEntry<String> ingredient : pattern.ingredients()) {
                if (path.contains(ingredient.key()))
                    return false;
            }
            int runs = pattern.runsFor(needed);
            if (steps[0] + 1 > stepLimit)
                return false;
            long items = (long) pattern.ingredientItems() * runs;
            if (cost[0] + items > limits.maxIngredientItems())
                return false;
            steps[0]++;
            cost[0] += items;
            path.add(key);
            try {
                for (ProductionEntry<String> ingredient : pattern.ingredients()) {
                    long need = (long) ingredient.count() * runs;
                    long free = Math.max(0L, stock.getOrDefault(ingredient.key(), 0L)
                            - spent.getOrDefault(ingredient.key(), 0L));
                    long paid = Math.min(free, need);
                    if (paid > 0L)
                        spent.merge(ingredient.key(), paid, Long::sum);
                    if (paid < need && !walk(ingredient.key(), need - paid, spent, steps, cost, path))
                        return false;
                }
            } finally {
                path.remove(path.size() - 1);
            }
            return true;
        }

        /** The patterns making {@code key}, largest producible amount against the plain stock first, ties by aisle order. */
        private List<ProductionPattern<String>> ranked(String key) {
            List<ProductionPattern<String>> found = new ArrayList<>(2);
            for (StationPattern<String, String> candidate : patterns) {
                if (candidate.produces(key))
                    found.add(candidate.pattern());
            }
            found.sort(java.util.Comparator.comparingLong(this::producible).reversed());
            return found;
        }


        private long producible(ProductionPattern<String> pattern) {
            long runs = Long.MAX_VALUE;
            for (ProductionEntry<String> ingredient : pattern.ingredients())
                runs = Math.min(runs, stock.getOrDefault(ingredient.key(), 0L) / ingredient.count());
            return runs * pattern.result().count();
        }
    }
}
