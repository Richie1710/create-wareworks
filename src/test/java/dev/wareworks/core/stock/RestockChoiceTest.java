package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.production.IngredientChoices;
import dev.wareworks.core.production.PlanBudget;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.ProductionPlanInput;
import dev.wareworks.core.production.ProductionPlanResult;
import dev.wareworks.core.production.SequentialPlans;
import dev.wareworks.core.production.StationPattern;
import dev.wareworks.core.terminal.RequestConfirmation;

/**
 * Filter cells (ADR-082) outside the planner itself: automatic restocking, the request confirmation's guard, two
 * chains sharing the candidates of one filter, and the stock index's key generation the filter cache is keyed on.
 */
class RestockChoiceTest {
    private static final String ANY_PLANK = "filter:#planks";
    private static final String OAK = "oak";
    private static final String BIRCH = "birch";
    private static final String TABLE = "table";

    private static final ProductionPattern<String> TABLES = ProductionPattern.fromGrid(
            List.of(new ProductionEntry<>(ANY_PLANK, 4)), new ProductionEntry<>(TABLE, 1), Set.of(ANY_PLANK));
    private static final IngredientChoices<String> CHOICES = IngredientChoices.of(Map.of(ANY_PLANK,
            List.of(BIRCH, OAK)));

    private static RestockInput<String> keepsTables(long minimum) {
        return RestockInput.of(new StockRule<>(TABLE, minimum, StockRule.UNSET, StockRule.UNSET), StockLevels.NONE,
                List.of(TABLES)).withChoices(CHOICES);
    }

    @Test
    void aRuleRefillsThroughAFilterPattern() {
        Map<String, Long> stock = Map.of(OAK, 6L, BIRCH, 9L);
        RestockDecision<String> decision = RestockPlanner.decide(keepsTables(2), RestockLimits.DEFAULT, 0, false,
                key -> stock.getOrDefault(key, 0L));
        assertEquals(RestockOutcome.ORDERED, decision.outcome());
        assertEquals(2, decision.runs());
    }

    @Test
    void withoutChoicesOrStockTheFilterIsNamed() {
        RestockDecision<String> waiting = RestockPlanner.decide(keepsTables(2), RestockLimits.DEFAULT, 0, false,
                key -> 3L);
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, waiting.outcome());
        assertEquals(Optional.of(ANY_PLANK), waiting.missingIngredient(), "3 + 3 planks are no run of 4");
        RestockInput<String> none = RestockInput.of(new StockRule<>(TABLE, 2L, StockRule.UNSET, StockRule.UNSET),
                StockLevels.NONE, List.of(TABLES));
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, RestockPlanner.decide(none, RestockLimits.DEFAULT, 0,
                false, key -> 64L).outcome(), "without choices a filter pattern makes nothing");
    }

    /**
     * A filter cell leaves out the kind another rule is short of and pays with the rest: an oak rule below its minimum
     * does not stop the tables while birch is there, and the order is paid without oak.
     */
    @Test
    void aFilterLeavesOutAnotherRulesItemAndPaysWithTheRest() {
        RestockInput<String> oakRule = RestockInput.of(new StockRule<>(OAK, 64L, StockRule.UNSET, StockRule.UNSET),
                StockLevels.NONE, List.of());
        List<RestockInput<String>> inputs = List.of(oakRule, keepsTables(2));
        RestockPlan<String> plan = RestockPlanner.plan(inputs, RestockLimits.DEFAULT, 0, false, key -> 64L);
        RestockDecision<String> tables = plan.decisions().get(1);
        assertEquals(RestockOutcome.ORDERED, tables.outcome(), "birch is free for the tables");
        assertEquals(2, tables.runs());
        assertEquals(Set.of(OAK, TABLE), RestockPlanner.calledFor(inputs), "both rules are short");
        assertEquals(List.of(BIRCH), CHOICES.excluding(RestockPlanner.calledFor(inputs)).candidates(TABLES,
                ANY_PLANK), "and the order is paid without oak");
    }

    /** Only when nothing but the other rule's kind could pay one run does the filter wait, naming that kind. */
    @Test
    void aFilterOnlyTheOtherRulesItemCouldPayWaitsForIt() {
        RestockInput<String> oakRule = RestockInput.of(new StockRule<>(OAK, 64L, StockRule.UNSET, StockRule.UNSET),
                StockLevels.NONE, List.of());
        Map<String, Long> stock = Map.of(OAK, 32L, BIRCH, 3L);
        RestockPlan<String> plan = RestockPlanner.plan(List.of(oakRule, keepsTables(2)), RestockLimits.DEFAULT, 0,
                false, key -> stock.getOrDefault(key, 0L));
        RestockDecision<String> tables = plan.decisions().get(1);
        assertEquals(RestockOutcome.WAITING_FOR_INGREDIENTS, tables.outcome(), "three birch are no run of four");
        assertEquals(Optional.of(OAK), tables.missingIngredient(), "the concrete key another rule calls for");
    }

    @Test
    void aConfirmationOfAFilterPatternNeedsAPlan() {
        assertThrows(IllegalArgumentException.class, () -> RequestConfirmation.of(StockRules.empty(), TABLE, 1L,
                StockLevels.NONE, Optional.of(TABLES), 1L, key -> 0L));
    }

    @Test
    void twoChainsSharingAFiltersCandidatesNeverSpendTwice() {
        Map<String, Long> stock = new LinkedHashMap<>();
        stock.put(OAK, 4L);
        stock.put(BIRCH, 4L);
        ProductionPlanInput<String, String> base = ProductionPlanInput.of(List.of(StationPattern.of("crafter",
                TABLES)), PlanBudget.ofMap(stock), PlanLimits.UNBOUNDED, 16).withChoices(CHOICES);
        SequentialPlans<String, String> plans = SequentialPlans.over(base);
        ProductionPlanResult<String, String> first = plans.plan(TABLE, 1);
        ProductionPlanResult<String, String> second = plans.plan(TABLE, 1);
        ProductionPlanResult<String, String> third = plans.plan(TABLE, 1);
        assertTrue(first.accepted() && second.accepted());
        assertEquals(Map.of(BIRCH, 4L), first.orElseThrow().leafDemand(), "a tie goes by candidate order");
        assertEquals(Map.of(OAK, 4L), second.orElseThrow().leafDemand(), "what the first chain took is gone");
        assertTrue(!third.accepted(), "eight planks are two tables, not three");
    }

    @Test
    void theKeyGenerationMovesOnlyWhenAKeyComesOrGoes() {
        StockIndex<String, String> index = new StockIndex<>();
        long start = index.keyGeneration();
        index.restore("a", Map.of(OAK, 4L));
        long added = index.keyGeneration();
        assertTrue(added > start, "a new key");
        index.restore("a", Map.of(OAK, 9L));
        assertEquals(added, index.keyGeneration(), "a count change is no key change");
        index.restore("b", Map.of(OAK, 1L));
        assertEquals(added, index.keyGeneration(), "a second location of a known key");
        index.remove("a");
        assertEquals(added, index.keyGeneration(), "oak is still at b");
        index.remove("b");
        assertTrue(index.keyGeneration() > added, "oak is gone");
        assertEquals(index.keyGeneration(), index.readOnlyView().keyGeneration());
        long before = index.keyGeneration();
        index.restore("c", Map.of(BIRCH, 1L));
        index.clear();
        assertEquals(before + 2, index.keyGeneration(), "birch came and the clear took it");
    }
}
