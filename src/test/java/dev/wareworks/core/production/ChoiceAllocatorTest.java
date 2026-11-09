package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * How a choice ingredient is paid (ADR-082, owner decision 2): whole runs, the largest stock first, several kinds only
 * when one is short, plain before variants, free stock before reserved stock, at most
 * {@value ChoiceAllocator#MAX_KEYS_PER_CHOICE} kinds.
 */
class ChoiceAllocatorTest {
    private static final String OAK = "oak";
    private static final String BIRCH = "birch";
    private static final String SPRUCE = "spruce";
    private static final String CHERRY = "cherry";

    private static ChoiceAllocator.Payment<String> pay(int count, int runs, List<String> candidates,
            Map<String, Long> stock) {
        return pay(count, runs, candidates, PlanBudget.ofMap(stock), IngredientChoices.none());
    }

    private static ChoiceAllocator.Payment<String> pay(int count, int runs, List<String> candidates,
            PlanBudget<String> budget, IngredientChoices<String> choices) {
        return ChoiceAllocator.pay(count, runs, candidates, budget, choices, key -> false,
                new ChoiceAllocator.FreeMemo<>(choices));
    }

    private static List<ChoiceAllocator.Part<String>> parts(Object... keysAndRuns) {
        List<ChoiceAllocator.Part<String>> parts = new ArrayList<>();
        for (int i = 0; i < keysAndRuns.length; i += 2)
            parts.add(new ChoiceAllocator.Part<>((String) keysAndRuns[i], (Integer) keysAndRuns[i + 1]));
        return parts;
    }

    @Test
    void theLargestStockWins() {
        ChoiceAllocator.Payment<String> payment = pay(4, 1, List.of(OAK, SPRUCE), Map.of(OAK, 3L, SPRUCE, 20L));
        assertEquals(parts(SPRUCE, 1), payment.parts());
        assertEquals(0, payment.remainderRuns());
    }

    @Test
    void onlyWholeRunsArePaid() {
        ChoiceAllocator.Payment<String> payment = pay(4, 1, List.of(OAK, BIRCH), Map.of(OAK, 3L, BIRCH, 3L));
        assertEquals(List.of(), payment.parts(), "3 + 3 planks are no run of 4");
        assertEquals(1, payment.remainderRuns());
    }

    @Test
    void kindsAreMixedOnlyWhenOneIsShort() {
        ChoiceAllocator.Payment<String> payment = pay(4, 3, List.of(BIRCH, OAK), Map.of(OAK, 9L, BIRCH, 6L));
        assertEquals(parts(OAK, 2, BIRCH, 1), payment.parts(), "oak 8 + birch 4");
        assertEquals(parts(OAK, 2), pay(4, 2, List.of(BIRCH, OAK), Map.of(OAK, 9L, BIRCH, 6L)).parts(),
                "one kind alone pays when it can");
    }

    @Test
    void tiesGoByCandidateOrder() {
        assertEquals(parts(BIRCH, 1), pay(4, 1, List.of(BIRCH, OAK), Map.of(OAK, 8L, BIRCH, 8L)).parts());
        assertEquals(parts(OAK, 1), pay(4, 1, List.of(OAK, BIRCH), Map.of(OAK, 8L, BIRCH, 8L)).parts());
    }

    @Test
    void plainKeysComeBeforeVariants() {
        String enchanted = "enchanted_oak";
        IngredientChoices<String> choices = new IngredientChoices<>() {
            @Override
            public List<String> candidates(ProductionPattern<String> pattern, String choice) {
                return List.of();
            }

            @Override
            public boolean plain(String key) {
                return !key.equals(enchanted);
            }
        };
        ChoiceAllocator.Payment<String> payment = pay(4, 1, List.of(enchanted, OAK),
                PlanBudget.ofMap(Map.of(enchanted, 64L, OAK, 8L)), choices);
        assertEquals(parts(OAK, 1), payment.parts());
    }

    @Test
    void freeStockComesBeforeAReserve() {
        IngredientChoices<String> choices = new IngredientChoices<>() {
            @Override
            public List<String> candidates(ProductionPattern<String> pattern, String choice) {
                return List.of();
            }

            @Override
            public long free(String key) {
                return key.equals(SPRUCE) ? 0L : Long.MAX_VALUE;
            }
        };
        Map<String, Long> stock = Map.of(SPRUCE, 20L, OAK, 8L);
        assertEquals(parts(OAK, 1), pay(4, 1, List.of(SPRUCE, OAK), PlanBudget.ofMap(stock), choices).parts(),
                "the reserved spruce is the last resort");
        assertEquals(parts(OAK, 2, SPRUCE, 1),
                pay(4, 3, List.of(SPRUCE, OAK), PlanBudget.ofMap(stock), choices).parts(),
                "and is used once the free oak runs out");
    }

    @Test
    void excludedKeysNeverPay() {
        ChoiceAllocator.Payment<String> payment = ChoiceAllocator.pay(4, 1, List.of(OAK, BIRCH),
                PlanBudget.ofMap(Map.of(OAK, 64L, BIRCH, 4L)), IngredientChoices.none(), OAK::equals,
                new ChoiceAllocator.FreeMemo<>(IngredientChoices.none()));
        assertEquals(parts(BIRCH, 1), payment.parts());
    }

    @Test
    void atMostEightKindsPayOneChoice() {
        Map<String, Long> stock = new LinkedHashMap<>();
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add("k" + i);
            stock.put("k" + i, 4L);
        }
        ChoiceAllocator.Payment<String> payment = pay(4, 10, candidates, stock);
        assertEquals(ChoiceAllocator.MAX_KEYS_PER_CHOICE, payment.parts().size());
        assertEquals(2, payment.remainderRuns());
    }

    @Test
    void theMostConstrainedChoiceIsPaidFirst() {
        String anyPlank = "#planks";
        String list = "list";
        // The broad filter comes first in the pattern, but the list can only use oak.
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(
                List.of(new ProductionEntry<>(anyPlank, 2), new ProductionEntry<>(list, 2)),
                new ProductionEntry<>("table", 1), Set.of(anyPlank, list));
        Map<String, List<String>> byChoice = Map.of(anyPlank, List.of(OAK, SPRUCE), list, List.of(OAK, BIRCH));
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(OAK, 2L, SPRUCE, 2L));
        int[] order = ChoiceAllocator.payOrder(pattern, byChoice::get, budget);
        assertArrayEquals(new int[] {1, 0}, order);
        IngredientChoices<String> choices = IngredientChoices.of(byChoice);
        ChoiceAllocator.FreeMemo<String> free = new ChoiceAllocator.FreeMemo<>(choices);
        for (int index : order) {
            ProductionEntry<String> ingredient = pattern.ingredients().get(index);
            assertEquals(0, ChoiceAllocator.pay(2, 1, byChoice.get(ingredient.key()), budget, choices, k -> false, free)
                    .remainderRuns(), "both choices are paid");
        }
        assertEquals(2L, budget.spent(OAK));
        assertEquals(2L, budget.spent(SPRUCE));
    }

    @Test
    void exactIngredientsArePaidBeforeChoices() {
        String anyPlank = "#planks";
        ProductionPattern<String> pattern = ProductionPattern.fromGrid(List.of(new ProductionEntry<>(anyPlank, 4),
                new ProductionEntry<>("stick", 2)), new ProductionEntry<>("sign", 3), Set.of(anyPlank));
        assertArrayEquals(new int[] {1, 0}, ChoiceAllocator.payOrder(pattern, choice -> List.of(OAK),
                PlanBudget.ofMap(Map.of())));
    }

    /** Never over the budget, deterministic, and more runs never take fewer items of any key. */
    @Test
    void randomPaymentsKeepTheirProperties() {
        Random random = new Random(20261109L);
        List<String> keys = List.of(OAK, BIRCH, SPRUCE, CHERRY, "acacia", "jungle", "mangrove", "bamboo", "crimson",
                "warped");
        for (int round = 0; round < 500; round++) {
            Map<String, Long> stock = new HashMap<>();
            for (String key : keys) {
                if (random.nextInt(3) > 0)
                    stock.put(key, (long) random.nextInt(40));
            }
            List<String> candidates = new ArrayList<>(keys.subList(0, 1 + random.nextInt(keys.size())));
            int count = 1 + random.nextInt(6);
            int runs = random.nextInt(12);
            ChoiceAllocator.Payment<String> first = pay(count, runs, candidates, stock);
            assertEquals(first, pay(count, runs, candidates, stock), "deterministic");
            Map<String, Long> taken = takenOf(first, count);
            for (Map.Entry<String, Long> entry : taken.entrySet())
                assertTrue(entry.getValue() <= stock.getOrDefault(entry.getKey(), 0L), "never over the budget");
            assertEquals(runs, first.paidRuns() + first.remainderRuns());
            Map<String, Long> more = takenOf(pay(count, runs + 1, candidates, stock), count);
            for (Map.Entry<String, Long> entry : taken.entrySet())
                assertTrue(more.getOrDefault(entry.getKey(), 0L) >= entry.getValue(), "monotone in runs");
        }
    }

    private static Map<String, Long> takenOf(ChoiceAllocator.Payment<String> payment, int count) {
        Map<String, Long> taken = new HashMap<>();
        for (ChoiceAllocator.Part<String> part : payment.parts())
            taken.merge(part.key(), (long) part.runs() * count, Long::sum);
        return taken;
    }
}
