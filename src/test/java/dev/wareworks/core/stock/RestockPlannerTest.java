package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.production.ProductionPattern;

/**
 * Automatic restocking (M15 part 2, issue #3): every branch of the decision, without a game.
 * <p>
 * The decision is the whole feature — whether the warehouse orders, how much, and what it says when it cannot — so it
 * is tested here rather than in a world, and the GameTests then prove that the order the planner asks for really is
 * started, really is served by a crane and really comes back.
 */
class RestockPlannerTest {
    private static final String PLANK = "plank";
    private static final String LOG = "log";
    private static final String NAIL = "nail";
    private static final String IRON = "iron";

    /** One log makes four planks, which is the whole-runs case the feature is written around. */
    private static final ProductionPattern<String> PLANKS = ProductionPattern.of(LOG, 1, PLANK, 4);
    /** A second pattern for the same item, out of something else, to test that the better one wins. */
    private static final ProductionPattern<String> PLANKS_FROM_IRON = ProductionPattern.of(IRON, 1, PLANK, 8);

    private static final RestockLimits LIMITS = RestockLimits.DEFAULT;

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static StockRule<String> keeps(String key, long minimum) {
        return new StockRule<>(key, minimum, StockRule.UNSET, StockRule.UNSET);
    }

    private static ToLongFunction<String> stocked(Object... pairs) {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        return key -> map.getOrDefault(key, 0L);
    }

    private static RestockDecision<String> decide(RestockInput<String> input, ToLongFunction<String> available) {
        return RestockPlanner.decide(input, LIMITS, 0, false, available);
    }

    /** A governing, unpaused rule that keeps {@code minimum} planks, with {@code stockedNow} of them in the racks. */
    private static RestockInput<String> shortOfPlanks(long minimum, long stockedNow,
            List<ProductionPattern<String>> patterns) {
        return RestockInput.of(keeps(PLANK, minimum), StockLevels.stored(stockedNow), patterns);
    }

    // --- the structural answers ------------------------------------------------------------------------------------

    @Test
    void aRuleThatGovernsNothingOrdersNothing() {
        RestockInput<String> shadowed = new RestockInput<>(keeps(PLANK, 256), StockLevels.NONE, false, false, 0,
                List.of(PLANKS));
        RestockDecision<String> decision = decide(shadowed, stocked(LOG, 64));
        assertEquals(RestockOutcome.NOT_GOVERNING, decision.outcome());
        assertEquals(0L, decision.amount());
        assertTrue(decision.pattern().isEmpty());
    }

    @Test
    void aPausedRuleOrdersNothingHoweverShortItIs() {
        RestockInput<String> paused = new RestockInput<>(keeps(PLANK, 256), StockLevels.stored(0), true, true, 0,
                List.of(PLANKS));
        assertEquals(RestockOutcome.PAUSED, decide(paused, stocked(LOG, 4096)).outcome());
    }

    @Test
    void aRuleWithoutAMinimumIsSatisfied() {
        StockRule<String> capOnly = new StockRule<>(PLANK, StockRule.UNSET, 64L, StockRule.UNSET);
        RestockInput<String> input = RestockInput.of(capOnly, StockLevels.stored(0), List.of(PLANKS));
        assertEquals(RestockOutcome.SATISFIED, decide(input, stocked(LOG, 64)).outcome());
    }

    @Test
    void aMetMinimumIsSatisfied() {
        assertEquals(RestockOutcome.SATISFIED,
                decide(shortOfPlanks(256, 256, List.of(PLANKS)), stocked(LOG, 64)).outcome());
    }

    /**
     * The whole hysteresis: an order that is already running counts towards the minimum, so the same rule cannot order
     * the same thing again a second later. Here it is counted through the levels ({@code expected}), which is what the
     * controller fills from the open orders.
     */
    @Test
    void whatIsAlreadyOnItsWayCountsTowardsTheMinimum() {
        StockLevels ordered = new StockLevels(200L, 0L, 56L, 200L);
        RestockInput<String> input = RestockInput.of(keeps(PLANK, 256), ordered, List.of(PLANKS));
        assertEquals(RestockOutcome.SATISFIED, decide(input, stocked(LOG, 64)).outcome());
    }

    // --- the bounds --------------------------------------------------------------------------------------------------

    @Test
    void restockingCanBeSwitchedOffCompletely() {
        RestockInput<String> input = shortOfPlanks(256, 0, List.of(PLANKS));
        assertEquals(RestockOutcome.DISABLED,
                RestockPlanner.decide(input, RestockLimits.OFF, 0, false, stocked(LOG, 64)).outcome());
        assertEquals(RestockOutcome.DISABLED, RestockPlanner
                .decide(input, new RestockLimits(0, 4, 512L, 64L), 0, false, stocked(LOG, 64)).outcome());
        assertEquals(RestockOutcome.DISABLED, RestockPlanner
                .decide(input, new RestockLimits(1, 0, 512L, 64L), 0, false, stocked(LOG, 64)).outcome());
    }

    @Test
    void aRuleNeverOrdersWhileOneOfItsOwnOrdersIsOpen() {
        RestockInput<String> running = new RestockInput<>(keeps(PLANK, 256), StockLevels.stored(0), true, false, 1,
                List.of(PLANKS));
        assertEquals(RestockOutcome.ORDER_OPEN, decide(running, stocked(LOG, 4096)).outcome());
        // The same answer once that order has closed the shortfall on paper: what the warehouse is doing about an
        // empty rack is more use to a player than "within its limits".
        RestockInput<String> covered = new RestockInput<>(keeps(PLANK, 256),
                new StockLevels(0L, 0L, 256L, 0L), true, false, 1, List.of(PLANKS));
        assertEquals(RestockOutcome.ORDER_OPEN, decide(covered, stocked(LOG, 4096)).outcome());
        assertEquals(StockRuleStatus.ORDERING,
                RestockOutcome.ORDER_OPEN.refine(StockRuleStatus.SATISFIED));
        // With a raised per-rule cap the very same rule may order again.
        assertEquals(RestockOutcome.ORDERED,
                RestockPlanner.decide(running, new RestockLimits(2, 4, 512L, 64L), 1, false, stocked(LOG, 4096))
                        .outcome());
    }

    @Test
    void theAisleCapAndTheProductionQueueBothStopAnOrder() {
        RestockInput<String> input = shortOfPlanks(256, 0, List.of(PLANKS));
        assertEquals(RestockOutcome.ORDERS_BUSY,
                RestockPlanner.decide(input, LIMITS, LIMITS.ordersPerAisle(), false, stocked(LOG, 4096)).outcome());
        assertEquals(RestockOutcome.ORDERS_BUSY,
                RestockPlanner.decide(input, LIMITS, 0, true, stocked(LOG, 4096)).outcome());
    }

    @Test
    void oneOrderNeverAsksForMoreThanTheConfiguredAmount() {
        RestockInput<String> input = shortOfPlanks(100_000, 0, List.of(PLANKS));
        RestockDecision<String> decision = RestockPlanner.decide(input, new RestockLimits(1, 4, 40L, 64L), 0, false,
                stocked(LOG, 4096));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(40L, decision.amount(), "bounded by maxRestockOrderAmount, not by the shortfall");
        assertEquals(10, decision.runs(), "and the run count the order is started with says the same thing");
    }

    /**
     * The product cap alone does not bound what an order can <b>cost</b>: a pattern of nine ingots to one block turns
     * "at most 512 blocks" into 4608 ingots, which is the whole ingredient stock of most warehouses and exactly the loss
     * the safety stop is supposed to allow only once (M15 review fix). The second bound counts ingredient items.
     */
    @Test
    void oneOrderNeverSpendsMoreIngredientItemsThanTheConfiguredNumber() {
        ProductionPattern<String> blocks = ProductionPattern.of(IRON, 9, PLANK, 1);
        RestockInput<String> input = shortOfPlanks(512, 0, List.of(blocks));
        RestockDecision<String> unbounded = RestockPlanner.decide(input, new RestockLimits(1, 4, 512L, 1_000_000L), 0,
                false, stocked(IRON, 100_000));
        assertEquals(512, unbounded.runs(), "without the bound the product cap allows 512 runs of nine ingots each");

        RestockDecision<String> decision = RestockPlanner.decide(input, new RestockLimits(1, 4, 512L, 64L), 0, false,
                stocked(IRON, 100_000));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(7, decision.runs(), "seven runs of nine ingots is what fits into 64 ingredient items");
        assertEquals(7L, decision.amount());
    }

    /**
     * A run cannot be cut in half, so one run is always allowed however expensive it is: the bound is about repeats, and
     * {@code maxRestockOrdersPerRule} sequences the rest of a large shortfall with the safety stop in between.
     */
    @Test
    void oneRunIsAlwaysAllowedEvenWhenItCostsMoreThanTheBound() {
        ProductionPattern<String> expensive = ProductionPattern.of(IRON, 64, PLANK, 1);
        RestockDecision<String> decision = RestockPlanner.decide(shortOfPlanks(512, 0, List.of(expensive)),
                new RestockLimits(1, 4, 512L, 8L), 0, false, stocked(IRON, 100_000));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(1, decision.runs());
    }

    // --- the rule's own maximum ------------------------------------------------------------------------------------

    /**
     * "Keep exactly 64" — minimum and maximum on the same number — must not settle above the cap for ever. A whole-run
     * surplus stored above a maximum never leaves the warehouse again: the rule would read {@code AT_MAXIMUM} for ever,
     * its lamp would stay lit, and a warehouse input holding the item would back up, which §3.6 teaches a player to read
     * as a jam (M15 review fix). The runs are rounded <b>down</b> to what fits.
     */
    @Test
    void anOrderNeverOvershootsTheRulesOwnMaximum() {
        StockRule<String> exactly64 = new StockRule<>(PLANK, 64L, 64L, StockRule.UNSET);
        RestockInput<String> input = RestockInput.of(exactly64, StockLevels.stored(54), List.of(PLANKS));
        RestockDecision<String> decision = decide(input, stocked(LOG, 4096));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(8L, decision.amount(), "two runs of four fit into the ten items of headroom, three do not");
        assertEquals(62L, 54L + decision.amount(), "and the aisle stays under its cap");
    }

    /**
     * The last few items of such a rule cannot be made at all, and the warehouse says so instead of overshooting: the
     * pair of numbers is a configuration only a player can resolve.
     */
    @Test
    void aShortfallSmallerThanOneRunIsReportedInsteadOfOrdered() {
        StockRule<String> exactly64 = new StockRule<>(PLANK, 64L, 64L, StockRule.UNSET);
        RestockInput<String> input = RestockInput.of(exactly64, StockLevels.stored(62), List.of(PLANKS));
        RestockDecision<String> decision = decide(input, stocked(LOG, 4096));
        assertEquals(RestockOutcome.NO_ROOM, decision.outcome());
        assertEquals(0L, decision.amount());
        assertTrue(decision.pattern().isEmpty());
        assertEquals(StockRuleStatus.BELOW_MINIMUM, decision.outcome().refine(StockRuleStatus.BELOW_MINIMUM),
                "the lamp still says what it always said; the outcome says why nothing is ordered");
        assertTrue(decision.outcome().isBlocked(), "and it is something a player can act on");
    }

    /** A rule with no maximum is bounded by nothing but its shortfall, i.e. exactly as before. */
    @Test
    void anUncappedRuleIsUnaffectedByTheHeadroomBound() {
        RestockDecision<String> decision = decide(shortOfPlanks(256, 100, List.of(PLANKS)), stocked(LOG, 4096));
        assertEquals(156L, decision.amount());
    }

    /**
     * The design's own sentence stays true: a pattern makes whole runs, so a warehouse settles a <b>little above</b> its
     * minimum. Rounding the runs down to the shortfall instead would mean a rule that keeps one plank never ordered at
     * all — the cap only ever takes runs away, it never turns the rounding around.
     */
    @Test
    void aShortfallSmallerThanOneRunStillOrdersAWholeRunWhenTheCapAllowsIt() {
        StockRule<String> keepOne = new StockRule<>(PLANK, 1L, 4L, StockRule.UNSET);
        RestockDecision<String> decision = decide(RestockInput.of(keepOne, StockLevels.stored(0), List.of(PLANKS)),
                stocked(LOG, 4096));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(1, decision.runs());
        assertEquals(4L, decision.amount(), "one run of four for a minimum of one, which the cap of four allows");
        // And with no cap at all, exactly the same.
        assertEquals(4L, decide(shortOfPlanks(1, 0, List.of(PLANKS)), stocked(LOG, 4096)).amount());
    }

    // --- what cannot be made -----------------------------------------------------------------------------------------

    @Test
    void withoutAPatternTheWarehouseSaysSoAndOrdersNothing() {
        RestockDecision<String> decision = decide(shortOfPlanks(256, 0, List.of()), stocked(LOG, 4096));
        assertEquals(RestockOutcome.NO_PATTERN, decision.outcome());
        assertTrue(decision.missingIngredient().isEmpty());
    }

    @Test
    void withoutIngredientsTheWarehouseNamesTheItemItNeeds() {
        RestockDecision<String> decision = decide(shortOfPlanks(256, 0, List.of(PLANKS)), stocked(LOG, 0));
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, decision.outcome());
        assertEquals(Optional.of(LOG), decision.missingIngredient());
        assertEquals(0L, decision.amount(), "and nothing is ordered, so nothing is spent");
    }

    /**
     * The reserve binds automatic restocking: the ingredients are measured with the availability of
     * {@link StockAccess#AUTOMATION}, so logs a rule protects are simply not there as far as an automatic order is
     * concerned — and the rule says "waiting for ingredients" rather than emptying the reserve through a pattern.
     */
    @Test
    void aReserveOnAnIngredientStopsTheOrderAndIsReportedAsMissing() {
        StockRules<String> rules = StockRules.of(List.of(
                keeps(PLANK, 256),
                new StockRule<>(LOG, StockRule.UNSET, StockRule.UNSET, 64L)));
        ToLongFunction<String> raw = stocked(LOG, 64);
        ToLongFunction<String> toAutomation = StockAvailability.of(rules, StockAccess.AUTOMATION, raw);
        assertEquals(64L, raw.applyAsLong(LOG), "the logs really are in the racks");
        assertEquals(0L, toAutomation.applyAsLong(LOG), "but none of them is for the warehouse's own automation");

        RestockDecision<String> decision = decide(shortOfPlanks(256, 0, List.of(PLANKS)), toAutomation);
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, decision.outcome());
        assertEquals(Optional.of(LOG), decision.missingIngredient());

        // One log above the reserve is enough for exactly one run.
        ToLongFunction<String> spare = StockAvailability.of(rules, StockAccess.AUTOMATION, stocked(LOG, 65));
        RestockDecision<String> allowed = decide(shortOfPlanks(256, 0, List.of(PLANKS)), spare);
        assertEquals(RestockOutcome.ORDERED, allowed.outcome());
    }

    @Test
    void aPartlyPayableOrderIsStartedForWhatTheIngredientsAllow() {
        // Two logs make eight planks, and the rule is short by 256: the order is started anyway, for what can be paid.
        RestockDecision<String> decision = decide(shortOfPlanks(256, 0, List.of(PLANKS)), stocked(LOG, 2));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(8L, decision.amount(), "two runs of four planks");
    }

    // --- ordering ------------------------------------------------------------------------------------------------------

    @Test
    void aShortRuleWithAPatternAndIngredientsOrders() {
        RestockDecision<String> decision = decide(shortOfPlanks(256, 100, List.of(PLANKS)), stocked(LOG, 4096));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(156L, decision.amount(), "exactly the shortfall; the pattern rounds it up to whole runs");
        assertEquals(Optional.of(PLANKS), decision.pattern());
        assertTrue(decision.ordered());
    }

    @Test
    void thePatternThatCanMakeTheMostRightNowIsChosen() {
        List<ProductionPattern<String>> both = List.of(PLANKS, PLANKS_FROM_IRON);
        assertEquals(Optional.of(PLANKS_FROM_IRON),
                decide(shortOfPlanks(256, 0, both), stocked(LOG, 4, IRON, 64)).pattern());
        assertEquals(Optional.of(PLANKS),
                decide(shortOfPlanks(256, 0, both), stocked(LOG, 64, IRON, 1)).pattern());
    }

    @Test
    void aPatternWithSeveralIngredientsNeedsAllOfThem() {
        ProductionPattern<String> nailed = new ProductionPattern<>(
                List.of(new dev.wareworks.core.production.ProductionEntry<>(LOG, 1),
                        new dev.wareworks.core.production.ProductionEntry<>(NAIL, 2)),
                new dev.wareworks.core.production.ProductionEntry<>(PLANK, 4));
        RestockDecision<String> missing = decide(shortOfPlanks(256, 0, List.of(nailed)), stocked(LOG, 64));
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, missing.outcome());
        assertEquals(Optional.of(NAIL), missing.missingIngredient());
        assertEquals(RestockOutcome.ORDERED,
                decide(shortOfPlanks(256, 0, List.of(nailed)), stocked(LOG, 64, NAIL, 128)).outcome());
    }

    // --- a whole aisle ------------------------------------------------------------------------------------------------

    @Test
    void anEmptyPlanOrdersNothingAndReportsNothing() {
        RestockPlan<String> plan = RestockPlanner.plan(List.of(), LIMITS, 0, false, stocked());
        assertTrue(plan.isEmpty());
        assertEquals(Optional.empty(), plan.order());
        assertEquals(RestockOutcome.NOT_GOVERNING, plan.outcomeFor(PLANK));
        assertSame(RestockPlan.<String>empty().decisions(), plan.decisions());
    }

    /**
     * One order per pass. Two rules that could both order are measured against the <b>same</b> availability snapshot,
     * so starting both would promise the same logs twice; the second reports {@code DEFERRED} and is reconsidered on
     * the next evaluation.
     */
    @Test
    void onlyOneOrderIsStartedPerPass() {
        RestockInput<String> planks = shortOfPlanks(256, 0, List.of(PLANKS));
        RestockInput<String> nails = RestockInput.of(keeps(NAIL, 256), StockLevels.stored(0),
                List.of(ProductionPattern.of(IRON, 1, NAIL, 8)));
        RestockPlan<String> plan = RestockPlanner.plan(List.of(planks, nails), LIMITS, 0, false,
                stocked(LOG, 4096, IRON, 4096));
        assertEquals(2, plan.decisions().size());
        assertEquals(RestockOutcome.ORDERED, plan.outcomeFor(PLANK));
        assertEquals(RestockOutcome.DEFERRED, plan.outcomeFor(NAIL));
        assertEquals(Optional.of(PLANK), plan.order().map(RestockDecision::key));
        assertEquals(0L, plan.decisionFor(NAIL).orElseThrow().amount(), "a deferred decision orders nothing");
    }

    /**
     * <b>A minimum holds items back from other rules too.</b> Two rules whose patterns are inverses of each other —
     * ingot to block and block to ingot, both plain vanilla recipes a Mechanical Crafter performs — and a stock that
     * cannot satisfy both: without this, the aisle converts the same items back and forth for as long as the world runs,
     * the crane never idles and neither rule is ever met (M15 review fix). Neither order is started, and both rules name
     * the ingredient the player has to supply.
     */
    @Test
    void aRuleNeverSpendsWhatAnotherRuleIsItselfShortOf() {
        ProductionPattern<String> toBlocks = ProductionPattern.of(IRON, 9, PLANK, 1);
        ProductionPattern<String> toIngots = ProductionPattern.of(PLANK, 1, IRON, 9);
        RestockInput<String> blocks = RestockInput.of(keeps(PLANK, 64), StockLevels.stored(22), List.of(toBlocks));
        RestockInput<String> ingots = RestockInput.of(keeps(IRON, 64), StockLevels.stored(2), List.of(toIngots));
        RestockPlan<String> plan = RestockPlanner.plan(List.of(blocks, ingots), LIMITS, 0, false,
                stocked(IRON, 2, PLANK, 22));
        assertEquals(Optional.empty(), plan.order(), "neither rule converts what the other one is asking for");
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, plan.outcomeFor(PLANK));
        assertEquals(Optional.of(IRON), plan.decisionFor(PLANK).orElseThrow().missingIngredient());
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, plan.outcomeFor(IRON));
        assertEquals(Optional.of(PLANK), plan.decisionFor(IRON).orElseThrow().missingIngredient());
    }

    /**
     * The same rule pair once one of them is satisfied: the other may convert again, because nobody is asking for its
     * ingredient any more. The check is about a rule that is <b>short</b>, not about every rule that exists.
     */
    @Test
    void aSatisfiedRuleHoldsNothingBack() {
        ProductionPattern<String> toBlocks = ProductionPattern.of(IRON, 9, PLANK, 1);
        RestockInput<String> blocks = RestockInput.of(keeps(PLANK, 64), StockLevels.stored(0), List.of(toBlocks));
        RestockInput<String> ingots = RestockInput.of(keeps(IRON, 64), StockLevels.stored(640), List.of());
        RestockPlan<String> plan = RestockPlanner.plan(List.of(blocks, ingots), LIMITS, 0, false,
                stocked(IRON, 640));
        assertEquals(RestockOutcome.ORDERED, plan.outcomeFor(PLANK));
        assertEquals(RestockOutcome.SATISFIED, plan.outcomeFor(IRON));
    }

    @Test
    void aPlanReportsEveryRuleEvenTheOnesThatOrderNothing() {
        RestockInput<String> satisfied = shortOfPlanks(64, 64, List.of(PLANKS));
        RestockInput<String> waiting = RestockInput.of(keeps(NAIL, 256), StockLevels.stored(0),
                List.of(ProductionPattern.of(IRON, 1, NAIL, 8)));
        RestockPlan<String> plan = RestockPlanner.plan(List.of(satisfied, waiting), LIMITS, 0, false, stocked());
        assertEquals(RestockOutcome.SATISFIED, plan.outcomeFor(PLANK));
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, plan.outcomeFor(NAIL));
        assertEquals(1, plan.blockedCount(), "only the waiting one is something a player can do anything about");
        assertEquals(Optional.empty(), plan.order());
    }

    // --- how an outcome is shown ----------------------------------------------------------------------------------------

    @Test
    void pausedOutranksEveryOtherStatus() {
        for (StockRuleStatus base : StockRuleStatus.values())
            assertEquals(StockRuleStatus.PAUSED, RestockOutcome.PAUSED.refine(base), "paused wins over " + base);
    }

    @Test
    void theTwoShortfallRefinementsOnlyEverReplaceBelowMinimum() {
        assertEquals(StockRuleStatus.ORDERING, RestockOutcome.ORDERED.refine(StockRuleStatus.BELOW_MINIMUM));
        assertEquals(StockRuleStatus.ORDERING, RestockOutcome.ORDER_OPEN.refine(StockRuleStatus.BELOW_MINIMUM));
        assertEquals(StockRuleStatus.WAITING_FOR_INGREDIENTS,
                RestockOutcome.WAITING_FOR_INGREDIENTS.refine(StockRuleStatus.BELOW_MINIMUM));
        // A rule whose minimum is met only because its own order is still running is not "within its limits".
        assertEquals(StockRuleStatus.ORDERING, RestockOutcome.ORDERED.refine(StockRuleStatus.SATISFIED));
        // A maximum or a reserve that bites at the same moment is the more actionable message and keeps its place.
        assertEquals(StockRuleStatus.AT_MAXIMUM, RestockOutcome.ORDERED.refine(StockRuleStatus.AT_MAXIMUM));
        assertEquals(StockRuleStatus.AT_RESERVE, RestockOutcome.ORDER_OPEN.refine(StockRuleStatus.AT_RESERVE));
        assertEquals(StockRuleStatus.SATISFIED,
                RestockOutcome.WAITING_FOR_INGREDIENTS.refine(StockRuleStatus.SATISFIED));
        assertEquals(StockRuleStatus.SATISFIED, RestockOutcome.SATISFIED.refine(StockRuleStatus.SATISFIED));
        assertEquals(StockRuleStatus.BELOW_MINIMUM, RestockOutcome.NO_PATTERN.refine(StockRuleStatus.BELOW_MINIMUM),
                "an item nothing here can make is simply below its minimum");
        assertEquals(StockRuleStatus.BELOW_MINIMUM, RestockOutcome.DEFERRED.refine(StockRuleStatus.BELOW_MINIMUM),
                "nothing was ordered yet, so nothing is claimed");
    }

    @Test
    void anEvaluationWithoutRestockingReadsExactlyAsItDidBefore() {
        StockRuleEvaluation<String> plain = new StockRuleEvaluation<>(0, keeps(PLANK, 256),
                StockRuleStatus.BELOW_MINIMUM, StockLevels.stored(0));
        assertEquals(RestockOutcome.NOT_GOVERNING, plain.restock());
        assertEquals(StockRuleStatus.BELOW_MINIMUM, plain.displayStatus());
        assertEquals(StockRuleStatus.ORDERING, plain.withRestock(RestockOutcome.ORDERED).displayStatus());
        assertEquals(StockRuleStatus.BELOW_MINIMUM, plain.withRestock(RestockOutcome.ORDERED).status(),
                "what the rule counts as never changes");
        assertSame(plain, plain.withRestock(RestockOutcome.NOT_GOVERNING));
    }

    @Test
    void everyOutcomeSaysWhetherItOrdersAndWhetherAPlayerCanDoSomething() {
        assertTrue(RestockOutcome.ORDERED.ordered());
        assertTrue(RestockOutcome.ORDERED.isOrdering());
        assertTrue(RestockOutcome.ORDER_OPEN.isOrdering());
        assertFalse(RestockOutcome.ORDER_OPEN.ordered());
        assertTrue(RestockOutcome.PAUSED.isBlocked());
        assertTrue(RestockOutcome.WAITING_FOR_INGREDIENTS.isBlocked());
        assertTrue(RestockOutcome.NO_PATTERN.isBlocked());
        assertFalse(RestockOutcome.SATISFIED.isBlocked());
        assertFalse(RestockOutcome.DEFERRED.isBlocked());
        assertTrue(RestockOutcome.NO_ROOM.isBlocked());
        // Every outcome has a text of its own, because every one of them is shown to a player: the status folds several
        // of them back into a bare "below the minimum", and this line is what tells them apart (M15 review fix).
        for (RestockOutcome outcome : RestockOutcome.values())
            assertEquals("gui.keeper.restock." + outcome.name().toLowerCase(java.util.Locale.ROOT), outcome.langKey());
    }

    // --- the bounds are never trusted ------------------------------------------------------------------------------------

    @Test
    void limitsClampWhateverAConfigFileSays() {
        RestockLimits absurd = new RestockLimits(-5, 100_000, -1L, -1L);
        assertEquals(0, absurd.ordersPerRule());
        assertEquals(RestockLimits.MAX_ORDERS, absurd.ordersPerAisle());
        assertEquals(1L, absurd.amountPerOrder());
        assertEquals(1L, absurd.ingredientItemsPerOrder());
        assertEquals(1, absurd.boundRuns(9), "one run is always allowed");
        assertEquals(64, RestockLimits.DEFAULT.boundRuns(1));
        assertEquals(7, RestockLimits.DEFAULT.boundRuns(9));
        assertEquals(64, RestockLimits.DEFAULT.boundRuns(0), "a run is read as costing at least one item");
        assertFalse(absurd.enabled());
        assertTrue(RestockLimits.DEFAULT.enabled());
        assertEquals(0L, RestockLimits.DEFAULT.boundAmount(-3L));
        assertEquals(512L, RestockLimits.DEFAULT.boundAmount(10_000L));
        assertEquals(7L, RestockLimits.DEFAULT.boundAmount(7L));
    }

    @Test
    void aPauseNeverThrowsAndNeverGoesNegative() {
        assertEquals(0L, StockRulePause.timedOut(-4L).unrecovered());
        assertEquals(StockRulePause.Cause.TIMED_OUT, StockRulePause.timedOut(3L).cause());
        assertEquals(StockRulePause.Cause.CANCELLED, StockRulePause.cancelled(3L).cause());
        for (StockRulePause.Cause cause : StockRulePause.Cause.values())
            assertEquals(Optional.of(cause), StockRulePause.Cause.byName(cause.name()));
        assertEquals(Optional.empty(), StockRulePause.Cause.byName(null));
    }
}
