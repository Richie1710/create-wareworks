package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

/**
 * The producible-key computation ({@code docs/warehouse-system.md} §3.5, ADR-024): what an aisle could make from what
 * it has, and the stage-1 rule that an ingredient counts only as real stock.
 */
class ProduciblePlannerTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String NAIL = "nail";
    private static final String TABLE = "table";
    private static final String STICK = "stick";

    private final Map<String, Long> available = new HashMap<>();

    private ToLongFunction<String> stock() {
        return key -> available.getOrDefault(key, 0L);
    }

    @Test
    void producibleKeysAreTheResultsOfThePatterns() {
        List<ProductionPattern<String>> patterns = List.of(ProductionPattern.of(LOG, 1, PLANK, 4),
                ProductionPattern.of(PLANK, 2, STICK, 4));
        assertEquals(Set.of(PLANK, STICK), ProduciblePlanner.producibleKeys(patterns));
        assertTrue(ProduciblePlanner.producibleKeys(List.of()).isEmpty());
    }

    /** The result set does not depend on stock: a terminal marks an item producible before anyone gathers for it. */
    @Test
    void producibleKeysIgnoreWhatIsInStock() {
        List<ProductionPattern<String>> patterns = List.of(ProductionPattern.of(LOG, 1, PLANK, 4));
        assertEquals(Set.of(PLANK), ProduciblePlanner.producibleKeys(patterns));
        assertEquals(0L, ProduciblePlanner.producibleAmount(patterns, PLANK, stock()), "but nothing can be made yet");
    }

    @Test
    void runsAreBoundedByTheScarcestIngredient() {
        ProductionPattern<String> table = new ProductionPattern<>(
                List.of(new ProductionEntry<>(PLANK, 4), new ProductionEntry<>(NAIL, 2)),
                new ProductionEntry<>(TABLE, 1));
        available.put(PLANK, 40L);
        available.put(NAIL, 5L);
        assertEquals(2, ProduciblePlanner.runsPossible(table, stock()), "5 nails pay for two runs of two");
        assertEquals(2L, ProduciblePlanner.producibleAmount(table, stock()));
        available.put(NAIL, 0L);
        assertEquals(0, ProduciblePlanner.runsPossible(table, stock()));
        assertEquals(0L, ProduciblePlanner.producibleAmount(table, stock()));
    }

    @Test
    void negativeAvailabilityCountsAsNothing() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        available.put(LOG, -10L);
        assertEquals(0, ProduciblePlanner.runsPossible(pattern, stock()));
    }

    @Test
    void theBestPatternWinsAndAmountsAreNotAddedUp() {
        ProductionPattern<String> fromLogs = ProductionPattern.of(LOG, 1, PLANK, 4);
        ProductionPattern<String> fromSticks = ProductionPattern.of(STICK, 1, PLANK, 1);
        List<ProductionPattern<String>> patterns = List.of(fromLogs, fromSticks);
        available.put(LOG, 3L);
        available.put(STICK, 5L);
        // 3 logs make 12 planks, 5 sticks make 5. Two patterns usually compete for the same ingredients, so the
        // reachable amount is the best single one, never the sum.
        assertEquals(12L, ProduciblePlanner.producibleAmount(patterns, PLANK, stock()));
        assertEquals(Optional.of(fromLogs), ProduciblePlanner.bestPatternFor(patterns, PLANK, stock()));
        assertEquals(0L, ProduciblePlanner.producibleAmount(patterns, TABLE, stock()), "nothing makes a table");
        assertTrue(ProduciblePlanner.bestPatternFor(patterns, TABLE, stock()).isEmpty());
    }

    @Test
    void aPatternThatCanMakeNothingIsNotChosen() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        assertTrue(ProduciblePlanner.bestPatternFor(List.of(pattern), PLANK, stock()).isEmpty());
    }

    /**
     * Stage 1 is single level: an ingredient that is itself only producible is <b>missing</b>. Without this an order
     * could be created that waits for another order, which stage 1 has no machinery for.
     */
    @Test
    void anIngredientThatIsOnlyProducibleCountsAsMissing() {
        ProductionPattern<String> planks = ProductionPattern.of(LOG, 1, PLANK, 4);
        ProductionPattern<String> table = ProductionPattern.of(PLANK, 4, TABLE, 1);
        List<ProductionPattern<String>> patterns = List.of(planks, table);
        available.put(LOG, 10L);
        assertEquals(40L, ProduciblePlanner.producibleAmount(patterns, PLANK, stock()));
        assertEquals(0L, ProduciblePlanner.producibleAmount(patterns, TABLE, stock()),
                "planks could be made, but stage 1 does not chain patterns");
        assertEquals(Optional.of(new ProductionEntry<>(PLANK, 4)),
                ProduciblePlanner.firstMissingIngredient(table, 1, stock()));
    }

    @Test
    void theFirstMissingIngredientIsNamedInPatternOrder() {
        ProductionPattern<String> table = new ProductionPattern<>(
                List.of(new ProductionEntry<>(PLANK, 4), new ProductionEntry<>(NAIL, 2)),
                new ProductionEntry<>(TABLE, 1));
        available.put(PLANK, 1000L);
        available.put(NAIL, 100L);
        assertTrue(ProduciblePlanner.firstMissingIngredient(table, 1, stock()).isEmpty(), "one run is paid for");
        assertTrue(ProduciblePlanner.firstMissingIngredient(table, 50, stock()).isEmpty(),
                "50 runs need exactly the 100 nails there are");
        assertEquals(Optional.of(new ProductionEntry<>(NAIL, 2)),
                ProduciblePlanner.firstMissingIngredient(table, 51, stock()), "102 nails are more than 100");
        available.put(PLANK, 0L);
        assertEquals(Optional.of(new ProductionEntry<>(PLANK, 4)),
                ProduciblePlanner.firstMissingIngredient(table, 1, stock()), "the first entry is reported first");
    }

    @Test
    void noRunsNeedsNoIngredients() {
        ProductionPattern<String> pattern = ProductionPattern.of(LOG, 1, PLANK, 4);
        assertTrue(ProduciblePlanner.firstMissingIngredient(pattern, 0, stock()).isEmpty());
    }
}
