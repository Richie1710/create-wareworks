package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * Filter cells in production patterns (ADR-082) through the whole core: the pattern, the producible amounts, the
 * planner, the plan's concrete lines, the order and the Crafter Feeder's segments.
 */
class ChoiceIngredientTest {
    private static final String ANY_PLANK = "filter:#planks";
    private static final String LIST = "filter:list";
    private static final String OAK = "oak";
    private static final String BIRCH = "birch";
    private static final String SPRUCE = "spruce";
    private static final String CHERRY = "cherry";
    private static final String DARK_OAK = "dark_oak";
    private static final String OAK_LOG = "oak_log";
    private static final String BIRCH_LOG = "birch_log";
    private static final String TABLE = "table";
    private static final String STICK = "stick";
    private static final String CRAFTER = "crafter";
    private static final String SAW = "saw";

    /** Four cells of "any plank" make a crafting table. */
    private static final ProductionPattern<String> TABLES = ProductionPattern.fromGrid(
            List.of(cell(ANY_PLANK), cell(ANY_PLANK), cell(ANY_PLANK), cell(ANY_PLANK)), new ProductionEntry<>(TABLE, 1),
            Set.of(ANY_PLANK));

    private final Map<String, Long> stock = new LinkedHashMap<>();
    private final Map<String, List<String>> candidates = new HashMap<>();

    private static ProductionEntry<String> cell(String key) {
        return new ProductionEntry<>(key, 1);
    }

    private IngredientChoices<String> choices() {
        return IngredientChoices.of(candidates);
    }

    private ProductionPlanInput<String, String> input(List<StationPattern<String, String>> patterns) {
        return ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock), PlanLimits.UNBOUNDED, 16)
                .withChoices(choices());
    }

    private static ProductionPlan<String, String> planned(ProductionPlanResult<String, String> result) {
        assertTrue(result.accepted(), () -> "refused with " + result.refusal().orElse(null) + " about "
                + result.about().orElse(null));
        return result.orElseThrow();
    }

    // --- the pattern -----------------------------------------------------------------------------------------------

    @Test
    void equalFiltersMergeAndDifferentOnesStayApart() {
        assertEquals(List.of(new ProductionEntry<>(ANY_PLANK, 4)), TABLES.ingredients());
        assertEquals(Set.of(ANY_PLANK), TABLES.choices());
        ProductionPattern<String> two = ProductionPattern.fromGrid(List.of(cell(ANY_PLANK), cell(LIST), cell(STICK)),
                new ProductionEntry<>("sign", 3), Set.of(ANY_PLANK, LIST));
        assertEquals(3, two.ingredients().size());
        assertEquals(List.of(cell(STICK)), two.exactIngredients(), "an exact key beside two filters");
        assertEquals(List.of(cell(ANY_PLANK), cell(LIST)), two.choiceIngredients());
        assertTrue(two.isChoice(LIST));
        assertFalse(two.consumes(LIST), "a choice is never consumed as itself");
        assertTrue(two.consumes(STICK));
    }

    @Test
    void choicesMustBeIngredients() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionPattern<>(List.of(cell(OAK)),
                new ProductionEntry<>(TABLE, 1), Set.of(ANY_PLANK)));
        ProductionPattern<String> old = new ProductionPattern<>(List.of(cell(OAK)), new ProductionEntry<>(TABLE, 1));
        assertEquals(Set.of(), old.choices(), "the compatibility constructor has no choices");
        assertFalse(old.hasChoices());
        assertEquals(old.ingredients(), old.exactIngredients());
    }

    // --- producible amounts ----------------------------------------------------------------------------------------

    @Test
    void withoutChoicesAChoicePatternMakesNothing() {
        stock.put(OAK, 64L);
        assertEquals(0, ProduciblePlanner.runsPossible(TABLES, stock::get));
        candidates.put(ANY_PLANK, List.of(OAK));
        assertEquals(16, ProduciblePlanner.runsPossible(TABLES, key -> stock.getOrDefault(key, 0L), choices()));
    }

    @Test
    void theMissingChoiceIsNamedByItsFilter() {
        stock.put(OAK, 3L);
        stock.put(BIRCH, 3L);
        candidates.put(ANY_PLANK, List.of(BIRCH, OAK));
        assertEquals(Optional.of(new ProductionEntry<>(ANY_PLANK, 4)), ProduciblePlanner.firstMissingIngredient(TABLES,
                1, key -> stock.getOrDefault(key, 0L), choices()), "3 + 3 planks are no whole run");
    }

    /** The closed form and the binary search over overlapping choices agree, and neither over-promises. */
    @Test
    void randomOverlappingChoicesNeverOverPromise() {
        Random random = new Random(82L);
        List<String> woods = List.of(OAK, BIRCH, SPRUCE, CHERRY, DARK_OAK);
        for (int round = 0; round < 300; round++) {
            Map<String, Long> available = new HashMap<>();
            for (String wood : woods)
                available.put(wood, (long) random.nextInt(20));
            Map<String, List<String>> byChoice = new HashMap<>();
            byChoice.put(ANY_PLANK, woods.subList(random.nextInt(3), woods.size()));
            byChoice.put(LIST, woods.subList(0, 1 + random.nextInt(woods.size())));
            int a = 1 + random.nextInt(3);
            int b = 1 + random.nextInt(3);
            ProductionPattern<String> pattern = ProductionPattern.fromGrid(List.of(new ProductionEntry<>(ANY_PLANK, a),
                    new ProductionEntry<>(LIST, b)), new ProductionEntry<>(TABLE, 1), Set.of(ANY_PLANK, LIST));
            IngredientChoices<String> choices = IngredientChoices.of(byChoice);
            int runs = ProduciblePlanner.runsPossible(pattern, key -> available.getOrDefault(key, 0L), choices);
            assertTrue(paysInFull(pattern, runs, available, choices), "the answer is one a payment really reaches");
            // A brute-force search over the same payments never finds less than the answer... and the answer is its max
            // where the payment is monotone, which a smaller probe reaching it shows.
            int brute = 0;
            for (int r = 1; r <= 100; r++) {
                if (paysInFull(pattern, r, available, choices))
                    brute = r;
            }
            assertTrue(runs <= brute, "never more than any payment reaches");
        }
    }

    private static boolean paysInFull(ProductionPattern<String> pattern, int runs, Map<String, Long> available,
            IngredientChoices<String> choices) {
        if (runs == 0)
            return true;
        PlanBudget<String> budget = PlanBudget.ofMap(available);
        ChoiceAllocator.FreeMemo<String> free = new ChoiceAllocator.FreeMemo<>(choices);
        for (int index : ChoiceAllocator.payOrder(pattern, c -> choices.candidates(pattern, c), budget)) {
            ProductionEntry<String> ingredient = pattern.ingredients().get(index);
            if (ChoiceAllocator.pay(ingredient.count(), runs, choices.candidates(pattern, ingredient.key()), budget,
                    choices, TABLE::equals, free).remainderRuns() > 0)
                return false;
        }
        return true;
    }

    // --- the planner -----------------------------------------------------------------------------------------------

    @Test
    void theLargestStockPaysAndTheLineNamesItsFilter() {
        stock.put(OAK, 10L);
        stock.put(BIRCH, 30L);
        stock.put(CHERRY, 5L);
        candidates.put(ANY_PLANK, List.of(BIRCH, CHERRY, OAK));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(
                input(List.of(StationPattern.of(CRAFTER, TABLES))), TABLE, 2));
        assertEquals(List.of(NodeLine.chosen(BIRCH, 8L, ANY_PLANK)), plan.root().lines());
        assertEquals(Map.of(BIRCH, 8L), plan.leafDemand());
    }

    @Test
    void kindsAreMixedInWholeRuns() {
        stock.put(OAK, 9L);
        stock.put(BIRCH, 6L);
        stock.put(CHERRY, 4L);
        candidates.put(ANY_PLANK, List.of(BIRCH, CHERRY, OAK));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(
                input(List.of(StationPattern.of(CRAFTER, TABLES))), TABLE, 3));
        assertEquals(List.of(NodeLine.chosen(OAK, 8L, ANY_PLANK), NodeLine.chosen(BIRCH, 4L, ANY_PLANK)),
                plan.root().lines(), "oak 8 + birch 4; the cherry is untouched");
    }

    @Test
    void aShortChoiceIsMadeAndTheChildFeedsItsLine() {
        stock.put(OAK, 4L);
        stock.put(BIRCH_LOG, 1L);
        stock.put(STICK, 4L);
        ProductionPattern<String> signs = ProductionPattern.fromGrid(List.of(cell(STICK), new ProductionEntry<>(
                ANY_PLANK, 4)), new ProductionEntry<>("sign", 3), Set.of(ANY_PLANK));
        ProductionPattern<String> birchPlanks = ProductionPattern.of(BIRCH_LOG, 1, BIRCH, 4);
        candidates.put(ANY_PLANK, List.of(BIRCH, OAK));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(List.of(
                StationPattern.of(SAW, birchPlanks), StationPattern.of(CRAFTER, signs))), "sign", 6));
        PlanNode<String, String> root = plan.root();
        assertEquals(List.of(NodeLine.exact(STICK, 2L), NodeLine.chosen(OAK, 4L, ANY_PLANK),
                NodeLine.chosen(BIRCH, 4L, ANY_PLANK)), root.lines());
        PlanNode<String, String> child = plan.node(0);
        assertEquals(BIRCH, child.result());
        assertEquals(2, child.parentLine(), "the child feeds the birch line, not the stick or the oak line");
    }

    @Test
    void stockAndMadePartsOfOneKeyMerge() {
        stock.put(OAK, 4L);
        stock.put(OAK_LOG, 1L);
        candidates.put(ANY_PLANK, List.of(OAK));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(List.of(
                StationPattern.of(SAW, ProductionPattern.of(OAK_LOG, 1, OAK, 4)),
                StationPattern.of(CRAFTER, TABLES))), TABLE, 2));
        assertEquals(List.of(NodeLine.chosen(OAK, 8L, ANY_PLANK)), plan.root().lines());
        assertEquals(0, plan.node(0).parentLine());
        assertEquals(Map.of(OAK, 4L, OAK_LOG, 1L), plan.leafDemand());
    }

    @Test
    void theSecondMakeCandidateIsTried() {
        stock.put(OAK_LOG, 1L);
        stock.put(BIRCH_LOG, 1L);
        candidates.put(ANY_PLANK, List.of(OAK, BIRCH));
        // Oak planks come first (a tie, candidate order), but a maximum leaves them no room; birch is the fallback.
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(List.of(
                StationPattern.of(SAW, ProductionPattern.of(OAK_LOG, 1, OAK, 4)),
                StationPattern.of(SAW, ProductionPattern.of(BIRCH_LOG, 1, BIRCH, 4)),
                StationPattern.of(CRAFTER, TABLES))).withHeadroom(
                        key -> OAK.equals(key) ? 0L : ProductionPlanInput.UNLIMITED_ROOM), TABLE, 1));
        assertEquals(List.of(BIRCH, TABLE), List.of(plan.node(0).result(), plan.root().result()));
        stock.clear();
        ProductionPlanResult<String, String> refused = ProductionPlanner.plan(input(List.of(
                StationPattern.of(SAW, ProductionPattern.of(OAK_LOG, 1, OAK, 4)),
                StationPattern.of(CRAFTER, TABLES))), TABLE, 1);
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), refused.refusal(), "the deepest refusal");
        assertEquals(Optional.of(OAK_LOG), refused.about());
    }

    @Test
    void itemsOnThePathAreLeftOutNotALoop() {
        // "Any plank" allows the table itself (a broken filter) and an oak plank; the table is never used for itself.
        stock.put(OAK, 4L);
        stock.put(TABLE, 64L);
        candidates.put(ANY_PLANK, List.of(OAK, TABLE));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(
                input(List.of(StationPattern.of(CRAFTER, TABLES))), TABLE, 1));
        assertEquals(List.of(NodeLine.chosen(OAK, 4L, ANY_PLANK)), plan.root().lines());
    }

    @Test
    void plansAreReproducibleAndMonotoneInRuns() {
        Random random = new Random(2026L);
        List<String> woods = List.of(OAK, BIRCH, SPRUCE, CHERRY);
        for (int round = 0; round < 200; round++) {
            stock.clear();
            for (String wood : woods)
                stock.put(wood, (long) random.nextInt(24));
            stock.put(OAK_LOG, (long) random.nextInt(4));
            candidates.put(ANY_PLANK, woods);
            List<StationPattern<String, String>> aisle = List.of(
                    StationPattern.of(SAW, ProductionPattern.of(OAK_LOG, 1, OAK, 4)),
                    StationPattern.of(CRAFTER, TABLES));
            boolean failed = false;
            for (int runs = 1; runs <= 12; runs++) {
                ProductionPlanInput<String, String> input = input(aisle);
                ProductionPlanResult<String, String> result = ProductionPlanner.plan(input, TABLE, runs);
                assertEquals(result, ProductionPlanner.plan(input, TABLE, runs), "reproducible");
                boolean whole = result.accepted() && result.limit().isEmpty();
                if (failed)
                    assertFalse(whole, "plan(r) fails => plan(r + 1) fails");
                failed |= !whole;
            }
        }
    }

    // --- the plan's lines and the order ----------------------------------------------------------------------------

    @Test
    void aPlanChecksItsLines() {
        PlanNode<String, String> wrong = new PlanNode<>(0, PlanNode.NO_PARENT, 0, CRAFTER, TABLES, 1, 1L,
                List.of(NodeLine.chosen(OAK, 3L, ANY_PLANK)), PlanNode.NO_PARENT);
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlan<>(List.of(wrong), 0, 4L, Map.of(),
                1L, 1L), "3 of 4 is not a payment");
        PlanNode<String, String> partRun = new PlanNode<>(0, PlanNode.NO_PARENT, 0, CRAFTER, TABLES, 2, 1L,
                List.of(NodeLine.chosen(OAK, 6L, ANY_PLANK), NodeLine.chosen(BIRCH, 2L, ANY_PLANK)),
                PlanNode.NO_PARENT);
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlan<>(List.of(partRun), 0, 8L, Map.of(),
                1L, 1L), "a choice is paid in whole runs");
        PlanNode<String, String> right = new PlanNode<>(0, PlanNode.NO_PARENT, 0, CRAFTER, TABLES, 2, 1L,
                List.of(NodeLine.chosen(OAK, 4L, ANY_PLANK), NodeLine.chosen(BIRCH, 4L, ANY_PLANK)),
                PlanNode.NO_PARENT);
        assertEquals(1, new ProductionPlan<>(List.of(right), 0, 8L, Map.of(), 1L, 1L).steps());
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, PlanNode.NO_PARENT, 0, CRAFTER, TABLES, 1,
                1L), "a choice pattern has no lines of its own");
    }

    @Test
    void anOrderIsBuiltFromLinesAndNeverFromAChoicePattern() {
        Supplier<UUID> ids = UUID::randomUUID;
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.start(UUID.randomUUID(), CRAFTER, TABLES, 1,
                ids, 0L, 100L, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.restock(UUID.randomUUID(), CRAFTER, TABLES,
                1, ids, 0L, 100L));
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), CRAFTER, TABLES, 2,
                List.of(NodeLine.chosen(OAK, 4L, ANY_PLANK), NodeLine.chosen(BIRCH, 4L, ANY_PLANK)), ids, 0L, 100L, 0L,
                null, 0L);
        assertEquals(2, order.lines().size());
        assertEquals(Optional.of(ANY_PLANK), order.lines().get(0).choice());
        SupplyLine<String> delivered = order.lines().get(0).withDelivered(2);
        assertEquals(Optional.of(ANY_PLANK), delivered.choice(), "progress keeps the choice");
        assertEquals(Optional.empty(), SupplyLine.of(UUID.randomUUID(), OAK, 4).choice());
        assertEquals(72, ProductionOrder.MAX_LINES);
        // An exact cell and a filter may both name oak: two lines of one key, summed per key.
        ProductionPattern<String> both = ProductionPattern.fromGrid(List.of(cell(OAK), cell(ANY_PLANK)),
                new ProductionEntry<>(STICK, 4), Set.of(ANY_PLANK));
        ProductionOrder<String, String> twice = ProductionOrder.start(UUID.randomUUID(), CRAFTER, both, 1,
                List.of(NodeLine.exact(OAK, 1L), NodeLine.chosen(OAK, 1L, ANY_PLANK)), ids, 0L, 100L, 0L, null, 0L);
        assertEquals(2, twice.outstanding(OAK));
        List<SupplyLine<String>> many = new ArrayList<>();
        for (int i = 0; i <= ProductionOrder.MAX_LINES; i++)
            many.add(SupplyLine.of(UUID.randomUUID(), OAK + i, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProductionOrder<>(UUID.randomUUID(), CRAFTER, TABLE, 1,
                many, ProductionOrderState.WAITING_FOR_INGREDIENTS, 100L, 0L, 0L, Optional.empty(), 0L, false,
                Optional.empty()));
    }

    // --- the Crafter Feeder ----------------------------------------------------------------------------------------

    @Test
    void theFeederMatchesChoiceGroupsAndLegacyLines() {
        Supplier<UUID> ids = UUID::randomUUID;
        ProductionOrder<String, String> mixed = ProductionOrder.start(UUID.randomUUID(), CRAFTER, TABLES, 2,
                List.of(NodeLine.chosen(OAK, 4L, ANY_PLANK), NodeLine.chosen(BIRCH, 4L, ANY_PLANK)), ids, 0L, 100L, 0L,
                null, 0L);
        assertEquals(OptionalInt.of(2), CrafterFeedPlan.runsFor(mixed, TABLES));
        ProductionOrder<String, String> part = ProductionOrder.start(UUID.randomUUID(), CRAFTER, TABLES, 2,
                List.of(NodeLine.chosen(OAK, 6L, ANY_PLANK), NodeLine.chosen(BIRCH, 2L, ANY_PLANK)), ids, 0L, 100L, 0L,
                null, 0L);
        assertEquals(OptionalInt.empty(), CrafterFeedPlan.runsFor(part, TABLES), "6 is not a whole number of runs");
        ProductionPattern<String> exact = ProductionPattern.of(OAK, 4, TABLE, 1);
        ProductionOrder<String, String> legacy = ProductionOrder.start(UUID.randomUUID(), CRAFTER, exact, 2, ids, 0L,
                100L, 0L, null);
        assertEquals(OptionalInt.of(2), CrafterFeedPlan.runsFor(legacy, exact));
        assertEquals(OptionalInt.empty(), CrafterFeedPlan.runsFor(legacy, TABLES), "an exact line pays no choice");
        assertEquals(OptionalInt.empty(), CrafterFeedPlan.runsFor(mixed, exact), "a choice line pays no exact cell");
    }

    @Test
    void theFeederLaysOneKindPerSegment() {
        CrafterGrid<String> grid = CrafterGrid.ofNullable(java.util.Arrays.asList(ANY_PLANK, ANY_PLANK, null,
                ANY_PLANK, ANY_PLANK, null, null, null, null));
        List<SupplyLine<String>> lines = List.of(new SupplyLine<>(UUID.randomUUID(), OAK, 4, 0, Optional.of(ANY_PLANK)),
                new SupplyLine<>(UUID.randomUUID(), BIRCH, 4, 0, Optional.of(ANY_PLANK)));
        CrafterFeedPlan.Segment<String> first = CrafterFeedPlan.segment(grid, Set.of(ANY_PLANK), lines, 0);
        assertEquals(1, first.runs());
        assertEquals(Optional.of(OAK), first.layout().at(0));
        assertEquals(Optional.of(OAK), first.layout().at(4));
        assertEquals(Optional.empty(), first.layout().at(2));
        CrafterFeedPlan.Segment<String> second = CrafterFeedPlan.segment(grid, Set.of(ANY_PLANK), lines, 1);
        assertEquals(1, second.runs());
        assertEquals(Map.of(BIRCH, 4), second.layout().cellsPerKey());
        assertEquals(0, CrafterFeedPlan.segment(grid, Set.of(ANY_PLANK), lines, 2).runs(), "no further run");
        CrafterGrid<String> plain = CrafterGrid.ofNullable(java.util.Arrays.asList(OAK, null, null, null, null, null,
                null, null, null));
        CrafterFeedPlan.Segment<String> whole = CrafterFeedPlan.segment(plain, Set.of(),
                List.of(SupplyLine.of(UUID.randomUUID(), OAK, 3)), 0);
        assertEquals(plain, whole.layout());
        assertEquals(Integer.MAX_VALUE, whole.runs(), "no filter cell: one segment");
    }

    @Test
    void threeChoicesSegmentAtEveryBoundary() {
        CrafterGrid<String> grid = CrafterGrid.ofNullable(java.util.Arrays.asList(ANY_PLANK, LIST, STICK, null, null,
                null, null, null, null));
        List<SupplyLine<String>> lines = List.of(
                new SupplyLine<>(UUID.randomUUID(), OAK, 2, 0, Optional.of(ANY_PLANK)),
                new SupplyLine<>(UUID.randomUUID(), BIRCH, 3, 0, Optional.of(ANY_PLANK)),
                new SupplyLine<>(UUID.randomUUID(), SPRUCE, 3, 0, Optional.of(LIST)),
                new SupplyLine<>(UUID.randomUUID(), CHERRY, 2, 0, Optional.of(LIST)),
                SupplyLine.of(UUID.randomUUID(), STICK, 5));
        List<Integer> runs = new ArrayList<>();
        int done = 0;
        while (done < 5) {
            CrafterFeedPlan.Segment<String> segment = CrafterFeedPlan.segment(grid, Set.of(ANY_PLANK, LIST), lines,
                    done);
            runs.add(segment.runs());
            assertEquals(Optional.of(STICK), segment.layout().at(2));
            done += segment.runs();
        }
        assertEquals(List.of(2, 1, 2), runs, "boundaries at 2 (oak to birch) and 3 (spruce to cherry)");
    }
}
