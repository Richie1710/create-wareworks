package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Fluid ingredients in the chain planner (issue #55, ADR-089, slice 2): a pattern with fluid cells is planned as item
 * lines plus <b>fluid lines of whole filled containers</b>, and every way it can fail names its reason.
 * <p>
 * The world's half (the fill trip, the ledger, the credit) is proven by {@code FluidSupplyGameTests}; here is the
 * arithmetic, without a game.
 */
class ProductionPlannerFluidTest {
    private static final String DIRT = "dirt";
    private static final String MUD = "mud";
    private static final String IRON = "iron";
    private static final String PLATE = "plate";
    private static final String BUCKET = "bucket";
    private static final String WATER_BUCKET = "water_bucket";
    private static final String LAVA_BUCKET = "lava_bucket";
    private static final String WATER = "minecraft:water";
    private static final String LAVA = "minecraft:lava";
    private static final String MIXER = "mixer";

    private final Map<String, Long> stock = new LinkedHashMap<>();
    private final Map<String, Long> fluidMb = new LinkedHashMap<>();
    private int slots = Integer.MAX_VALUE;
    private int calls;

    /** 1 dirt and 250 mB water make 1 mud: the Basin recipe the feature is built around. */
    private static ProductionPattern<String> mud(int dirt, int waterMb) {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(DIRT, dirt)), new ProductionEntry<>(MUD, 1),
                Set.of(), List.of(new FluidCell(WATER, waterMb)));
    }

    private FluidSupply<String> supply() {
        return new FluidSupply<>() {
            @Override
            public Optional<Delivery<String>> delivery(String fluid) {
                calls++;
                if (WATER.equals(fluid))
                    return Optional.of(new Delivery<>(WATER_BUCKET, BUCKET, 1000, fluidMb.getOrDefault(WATER, 0L)));
                if (LAVA.equals(fluid))
                    return Optional.of(new Delivery<>(LAVA_BUCKET, BUCKET, 1000, fluidMb.getOrDefault(LAVA, 0L)));
                return Optional.empty();
            }

            @Override
            public int bufferSlots() {
                return slots;
            }

            @Override
            public int stackSize(String key) {
                return DIRT.equals(key) || IRON.equals(key) ? 64 : 1;
            }
        };
    }

    private ProductionPlanInput<String, String> input(List<StationPattern<String, String>> patterns) {
        return ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock), PlanLimits.UNBOUNDED, 16)
                .withFluids(supply());
    }

    private static List<StationPattern<String, String>> aisle(ProductionPattern<String> pattern) {
        return List.of(StationPattern.of(MIXER, pattern));
    }

    private static ProductionPlan<String, String> planned(ProductionPlanResult<String, String> result) {
        assertTrue(result.accepted(), () -> "refused with " + result.refusal().orElse(null) + " about "
                + result.about().orElse(null));
        return result.orElseThrow();
    }

    private static NodeLine<String> fluidLine(PlanNode<String, String> node) {
        return node.lines().stream().filter(NodeLine::isFluid).findFirst().orElseThrow();
    }

    // --- the arithmetic ----------------------------------------------------------------------------------------------

    /** The whole point: dirt and a fluid line of one bucket, the empty bucket paid from the item budget. */
    @Test
    void aFluidCellBecomesALineOfFilledContainers() {
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 5L);
        fluidMb.put(WATER, 8000L);
        ProductionPlan<String, String> plan = planned(
                ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 4));
        PlanNode<String, String> root = plan.root();
        assertEquals(4, root.runs());
        assertEquals(2, root.lines().size());
        assertFalse(root.lines().get(0).isFluid(), "the item lines come first");
        NodeLine<String> water = fluidLine(root);
        assertEquals(WATER_BUCKET, water.key());
        assertEquals(1L, water.amount(), "250 mB x 4 runs is exactly one bucket");
        assertEquals(Optional.of(WATER), water.fluid());
        assertEquals(1L, plan.leafDemand().get(BUCKET), "the crane takes one empty bucket out of a rack");
        assertEquals(4L, plan.leafDemand().get(DIRT));
    }

    /** One run still needs a whole container, and the fifth run of a 250 mB cell needs the second one. */
    @Test
    void containersAreRoundedUpWithTheRealContainerSize() {
        stock.put(DIRT, 100L);
        stock.put(BUCKET, 10L);
        fluidMb.put(WATER, 100_000L);
        assertEquals(1L, fluidLine(planned(ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 1)).root()).amount());
        assertEquals(2L, fluidLine(planned(ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 5)).root()).amount());
        assertEquals(3L, fluidLine(planned(ProductionPlanner.plan(input(aisle(mud(1, 1000))), MUD, 3)).root())
                .amount());
    }

    /** More runs than the bays can fill are clamped, like items, and the click is told why it was shortened. */
    @Test
    void theRunsAreClampedByTheFluidInTheBays() {
        stock.put(DIRT, 100L);
        stock.put(BUCKET, 10L);
        fluidMb.put(WATER, 2000L);
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(mud(1, 1000))), MUD, 5);
        ProductionPlan<String, String> plan = planned(result);
        assertEquals(2, plan.root().runs(), "2000 mB is two runs of 1000 mB");
        assertTrue(plan.isClamped());
        assertEquals(Optional.of(PlanRefusal.FLUID_MISSING), result.limit(),
                "the player is told what stopped the rest");
    }

    @Test
    void emptyBaysRefuseWithTheFilledContainerNamed() {
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 5L);
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 1);
        assertFalse(result.accepted());
        assertEquals(Optional.of(PlanRefusal.FLUID_MISSING), result.refusal());
        assertEquals(Optional.of(WATER_BUCKET), result.about());
    }

    @Test
    void noEmptyContainerIsItsOwnReason() {
        stock.put(DIRT, 10L);
        fluidMb.put(WATER, 5000L);
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 1);
        assertFalse(result.accepted());
        assertEquals(Optional.of(PlanRefusal.NO_EMPTY_CONTAINER), result.refusal());
        assertEquals(Optional.of(WATER_BUCKET), result.about());
    }

    /** An unknown fluid (a removed mod) and a fluid nothing is filled with are refused, never a crash. */
    @Test
    void anUnknownFluidIsRefusedNamingWhatIsMade() {
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 5L);
        ProductionPattern<String> goo = new ProductionPattern<>(List.of(new ProductionEntry<>(DIRT, 1)),
                new ProductionEntry<>(MUD, 1), Set.of(), List.of(new FluidCell("removedmod:goo", 250)));
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(goo)), MUD, 1);
        assertFalse(result.accepted());
        assertEquals(Optional.of(PlanRefusal.FLUID_MISSING), result.refusal());
        assertEquals(Optional.of(MUD), result.about());
    }

    /** The empties are the item budget's: a pattern that also needs buckets as an item cannot spend them twice. */
    @Test
    void emptyContainersAreSharedWithTheItemCells() {
        stock.put(BUCKET, 1L);
        fluidMb.put(WATER, 5000L);
        ProductionPattern<String> both = new ProductionPattern<>(List.of(new ProductionEntry<>(BUCKET, 1)),
                new ProductionEntry<>(MUD, 1), Set.of(), List.of(new FluidCell(WATER, 250)));
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(both)), MUD, 1);
        assertFalse(result.accepted(), "one bucket cannot be the ingredient and the container");
    }

    /** A pattern with a water and a lava cell shares one pool of empties, and each fluid its own bays. */
    @Test
    void twoFluidsSharePoolOfEmptiesButNotFluid() {
        stock.put(IRON, 100L);
        stock.put(BUCKET, 2L);
        fluidMb.put(WATER, 5000L);
        fluidMb.put(LAVA, 5000L);
        ProductionPattern<String> two = new ProductionPattern<>(List.of(new ProductionEntry<>(IRON, 1)),
                new ProductionEntry<>(PLATE, 1), Set.of(), List.of(new FluidCell(WATER, 500), new FluidCell(LAVA, 500)));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(two)), PLATE, 2));
        long fluidLines = plan.root().lines().stream().filter(NodeLine::isFluid).count();
        assertEquals(2L, fluidLines);
        assertEquals(2L, plan.leafDemand().get(BUCKET), "one empty bucket per fluid");
        stock.put(BUCKET, 1L);
        ProductionPlanResult<String, String> short1 = ProductionPlanner.plan(input(aisle(two)), PLATE, 2);
        assertFalse(short1.accepted());
        assertEquals(Optional.of(PlanRefusal.NO_EMPTY_CONTAINER), short1.refusal());
    }

    /** A fluid-only pattern (no item ingredient) is planned and bounded by the fluid, never by an empty grid. */
    @Test
    void aFluidOnlyPatternIsPlanned() {
        stock.put(BUCKET, 3L);
        fluidMb.put(WATER, 2000L);
        ProductionPattern<String> only = new ProductionPattern<>(List.of(), new ProductionEntry<>(MUD, 1), Set.of(),
                List.of(new FluidCell(WATER, 1000)));
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(only)), MUD, 5));
        assertEquals(2, plan.root().runs(), "2000 mB is two buckets");
        assertEquals(1, plan.root().lines().size());
        assertTrue(plan.root().lines().get(0).isFluid());
    }

    // --- the buffer --------------------------------------------------------------------------------------------------

    /** The stacks of the runs plus at most two containers in flight must fit the slots; more runs need more stacks. */
    @Test
    void theBufferSlotsClampTheRuns() {
        stock.put(DIRT, 1000L);
        stock.put(BUCKET, 100L);
        fluidMb.put(WATER, 100_000L);
        slots = 4; // 2 containers + 2 stacks of dirt (128 dirt)
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 500);
        ProductionPlan<String, String> plan = planned(result);
        assertEquals(128, plan.root().runs(), "two stacks of 64 dirt beside two buckets fill a buffer of four");
        assertEquals(Optional.of(PlanRefusal.BUFFER_TOO_SMALL), result.limit());
    }

    @Test
    void aBufferThatCannotHoldOneRunRefuses() {
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 10L);
        fluidMb.put(WATER, 100_000L);
        slots = 1; // the container alone takes the slot, the dirt has none
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 1);
        assertFalse(result.accepted());
        assertEquals(Optional.of(PlanRefusal.BUFFER_TOO_SMALL), result.refusal());
        assertEquals(Optional.of(MUD), result.about());
    }

    /** More than two containers are never counted: they are fetched two at a time, so they never stand there together. */
    @Test
    void onlyTwoContainersCountAgainstTheSlots() {
        stock.put(DIRT, 100L);
        stock.put(BUCKET, 50L);
        fluidMb.put(WATER, 100_000L);
        slots = 3; // 2 containers in flight + 1 stack of dirt
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(mud(1, 1000))), MUD, 40));
        assertEquals(40, plan.root().runs(), "forty buckets in all, never more than two at once");
        assertEquals(40L, fluidLine(plan.root()).amount());
    }

    // --- chains and bounds -------------------------------------------------------------------------------------------

    /** Two steps of one plan never promise the same water twice. */
    @Test
    void twoStepsOfOnePlanDoNotSpendTheSameWaterTwice() {
        // The root plate needs 2 mud; a mud step needs 1000 mB water per run; the bays hold 1500.
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 10L);
        fluidMb.put(WATER, 1500L);
        ProductionPattern<String> plate = new ProductionPattern<>(List.of(new ProductionEntry<>(MUD, 2)),
                new ProductionEntry<>(PLATE, 1), Set.of(), List.of(new FluidCell(WATER, 1000)));
        List<StationPattern<String, String>> aisle = List.of(StationPattern.of(MIXER, mud(1, 1000)),
                StationPattern.of("press", plate));
        ProductionPlanResult<String, String> result = ProductionPlanner.plan(input(aisle), PLATE, 1);
        assertFalse(result.accepted(), "3 buckets are needed in all (2 for the mud, 1 for the plate), 1.5 exist");
        assertEquals(Optional.of(PlanRefusal.FLUID_MISSING), result.refusal());
        fluidMb.put(WATER, 3000L);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle), PLATE, 1));
        assertEquals(2, plan.steps());
        long containers = 0;
        for (PlanNode<String, String> node : plan.nodes())
            containers += fluidLine(node).amount();
        assertEquals(3L, containers);
    }

    /** The containers count against one click's ingredient items, which is what bounds what a click can lose. */
    @Test
    void theContainersCountAsIngredientItems() {
        stock.put(DIRT, 10L);
        stock.put(BUCKET, 10L);
        fluidMb.put(WATER, 100_000L);
        ProductionPlan<String, String> plan = planned(ProductionPlanner.plan(input(aisle(mud(1, 1000))), MUD, 3));
        assertEquals(3L + 3L, plan.ingredientItems(), "3 dirt and 3 buckets");
    }

    /** The availability of a fluid is asked of the supply once per plan walk, not per item: bounded work. */
    @Test
    void thePlannerAsksTheSupplyBoundedTimes() {
        stock.put(DIRT, 1000L);
        stock.put(BUCKET, 100L);
        fluidMb.put(WATER, 100_000L);
        slots = 5;
        calls = 0;
        planned(ProductionPlanner.plan(input(aisle(mud(1, 250))), MUD, 1000));
        assertTrue(calls < 200, "the clamp's binary search walks at most 31 times, asked twice per walk: " + calls);
    }

    // --- the plan's shape --------------------------------------------------------------------------------------------

    @Test
    void aPlanNodeWithoutItsFluidLineIsRejected() {
        ProductionPattern<String> pattern = mud(1, 250);
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlan<>(List.of(new PlanNode<>(0,
                PlanNode.NO_PARENT, 0, MIXER, pattern, 1, 1L, List.of(NodeLine.exact(DIRT, 1L)), PlanNode.NO_PARENT)),
                0, 1L, Map.of(), 1L, 1L), "a node of a fluid pattern needs a line for its fluid cell");
        assertThrows(IllegalArgumentException.class, () -> NodeLine.exactLinesOf(pattern, 1),
                "a pattern with a fluid cell has no lines of its own");
    }

    @Test
    void aFluidLineMustNameThePatternsFluid() {
        ProductionPattern<String> pattern = mud(1, 250);
        List<NodeLine<String>> wrong = List.of(NodeLine.exact(DIRT, 1L), NodeLine.fluid(LAVA_BUCKET, 1L, LAVA));
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlan<>(List.of(new PlanNode<>(0,
                PlanNode.NO_PARENT, 0, MIXER, pattern, 1, 1L, wrong, PlanNode.NO_PARENT)), 0, 1L, Map.of(), 1L, 1L));
        List<NodeLine<String>> right = List.of(NodeLine.exact(DIRT, 1L), NodeLine.fluid(WATER_BUCKET, 1L, WATER));
        ProductionPlan<String, String> plan = new ProductionPlan<>(List.of(new PlanNode<>(0, PlanNode.NO_PARENT, 0,
                MIXER, pattern, 1, 1L, right, PlanNode.NO_PARENT)), 0, 1L, Map.of(), 1L, 1L);
        assertEquals(1, plan.steps());
    }

    /** An order is built from the plan's lines; a pattern with fluid cells cannot be built without them. */
    @Test
    void anOrderKeepsTheFluidMarkOfItsLinesAndCreditsContainers() {
        ProductionPattern<String> pattern = mud(1, 250);
        List<NodeLine<String>> lines = List.of(NodeLine.exact(DIRT, 4L), NodeLine.fluid(WATER_BUCKET, 1L, WATER));
        ProductionOrder<String, String> order = ProductionOrder.start(UUID.randomUUID(), MIXER, pattern, 4, lines,
                UUID::randomUUID, 0L, 1000L, 0L, null, 0L);
        assertEquals(2, order.lines().size());
        SupplyLine<String> water = order.lines().get(1);
        assertTrue(water.isFluid());
        assertEquals(Optional.of(WATER), water.fluid());
        assertEquals(1, water.required(), "counted in containers");
        assertFalse(order.lines().get(0).isFluid());
        assertTrue(water.withDelivered(1).isComplete(), "one container completes the line");
        assertEquals(Optional.of(WATER), water.withDelivered(1).fluid(), "and credit keeps the mark");
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.start(UUID.randomUUID(), MIXER, pattern, 4,
                UUID::randomUUID, 0L, 1000L, 0L, null, 0L), "an order from the pattern alone would forget its fluid");
    }

    /** The planner's cap of two and the job planner's are one number. */
    @Test
    void theInFlightCapOfThePlannerAndTheJobPlannerAgree() {
        assertEquals(FluidSupply.MAX_IN_FLIGHT, dev.wareworks.core.job.PlannerInput.FillSupply.MAX_AT_STATION);
    }

    // --- makeable ----------------------------------------------------------------------------------------------------

    /** A fluid-only pattern without a supply makes nothing, instead of an unbounded number of runs. */
    @Test
    void aFluidPatternWithoutASupplyMakesNothing() {
        ProductionPattern<String> only = new ProductionPattern<>(List.of(), new ProductionEntry<>(MUD, 1), Set.of(),
                List.of(new FluidCell(WATER, 1000)));
        assertEquals(0, ProduciblePlanner.runsPossible(only, key -> 100L));
        assertEquals(0L, ProduciblePlanner.producibleAmount(only, key -> 100L));
        ProductionPattern<String> withDirt = mud(1, 250);
        assertEquals(0, ProduciblePlanner.runsPossible(withDirt, key -> 100L), "dirt alone does not make mud");
    }

    @Test
    void producibleIsBoundedByFluidAndEmpties() {
        stock.put(DIRT, 100L);
        stock.put(BUCKET, 2L);
        fluidMb.put(WATER, 10_000L);
        FluidSupply<String> supply = supply();
        ProductionPattern<String> pattern = mud(1, 250);
        // 2 empty buckets fill 2000 mB = 8 runs of 250 mB; the dirt allows 100, the bays 40.
        assertEquals(8, ProduciblePlanner.runsPossible(pattern, key -> stock.getOrDefault(key, 0L),
                IngredientChoices.none(), supply));
        stock.put(BUCKET, 100L);
        assertEquals(40, ProduciblePlanner.runsPossible(pattern, key -> stock.getOrDefault(key, 0L),
                IngredientChoices.none(), supply));
        stock.put(DIRT, 3L);
        assertEquals(3, ProduciblePlanner.runsPossible(pattern, key -> stock.getOrDefault(key, 0L),
                IngredientChoices.none(), supply));
    }

    /** A pattern without a fluid cell is answered exactly as before, whatever the supply says. */
    @Test
    void aPatternWithoutFluidIgnoresTheSupply() {
        ProductionPattern<String> plain = ProductionPattern.of(IRON, 2, PLATE, 1);
        assertEquals(5, ProduciblePlanner.runsPossible(plain, key -> 10L, IngredientChoices.none(), supply()));
        assertEquals(5, ProduciblePlanner.runsPossible(plain, key -> 10L));
    }
}
