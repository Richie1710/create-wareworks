package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The plan as a value (M20, {@code docs/warehouse-system.md} §3.5.6, ADR-032): the tree it describes is checked when it
 * is built, because everything downstream reads it as one — the order the production orders are created in, the level a
 * screen indents by, and which step a blocked step waits for.
 */
class ProductionPlanTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String CHEST = "chest";
    private static final ProductionPattern<String> PLANKS = ProductionPattern.of(LOG, 1, PLANK, 4);
    private static final ProductionPattern<String> CHESTS = ProductionPattern.of(PLANK, 8, CHEST, 1);

    private static PlanNode<String, String> planks(int index, int parent) {
        return new PlanNode<>(index, parent, 1, "saw", PLANKS, 2, 8L);
    }

    private static PlanNode<String, String> chest(int index) {
        return new PlanNode<>(index, PlanNode.NO_PARENT, 0, "crafter", CHESTS, 1, 1L);
    }

    private static ProductionPlan<String, String> plan(List<PlanNode<String, String>> nodes) {
        return new ProductionPlan<>(nodes, 1, 10L, Map.of(LOG, 2L), 1L, 1L);
    }

    @Test
    void aTwoStepPlanReadsChildrenFirst() {
        ProductionPlan<String, String> plan = plan(List.of(planks(0, 1), chest(1)));
        assertEquals(2, plan.steps());
        assertEquals(CHEST, plan.result());
        assertEquals(1, plan.root().index());
        assertFalse(plan.isSingleLevel());
        assertFalse(plan.isClamped());
        assertEquals(List.of(plan.node(0)), plan.childrenOf(1));
        assertTrue(plan.childrenOf(0).isEmpty());
        assertEquals(8, plan.node(0).output());
        assertEquals(2L, plan.node(0).ingredientItems());
        assertEquals(8L, plan.root().ingredientItems());
    }

    @Test
    void aSingleStepPlanIsSingleLevel() {
        ProductionPlan<String, String> plan = new ProductionPlan<>(List.of(chest(0)), 0, 8L, Map.of(PLANK, 8L), 4L,
                1L);
        assertTrue(plan.isSingleLevel());
        assertTrue(plan.isClamped(), "one chest of the four that were asked for");
        assertEquals(0, plan.depth());
    }

    @Test
    void aPlanNeedsExactlyOneRootAndItComesLast() {
        assertThrows(IllegalArgumentException.class, () -> plan(List.of()));
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(chest(0), planks(1, 0))),
                "the root must be last");
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(chest(0), chest(1))), "two roots");
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(planks(0, 1), planks(1, 0))), "no root");
    }

    @Test
    void everyStepComesBeforeItsParent() {
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(planks(0, 3), chest(1))),
                "a parent that does not exist");
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPlan<>(List.of(planks(0, 1), new PlanNode<>(1, 2, 1, "saw", PLANKS, 1, 1L),
                        chest(2)), 1, 10L, Map.of(), 1L, 1L),
                "a step one level below a step is not one level below the root");
    }

    @Test
    void theIndicesAreThePositions() {
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(planks(1, 1), chest(1))));
    }

    @Test
    void aNodeIsAStepOrTheRootAndNothingElse() {
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, 0, 1, "saw", PLANKS, 1, 1L),
                "its own parent");
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, PlanNode.NO_PARENT, 1, "saw", PLANKS, 1,
                1L), "no parent but not the root");
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, 1, 0, "saw", PLANKS, 1, 1L),
                "a parent but the root's depth");
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, 1, 1, "saw", PLANKS, 0, 1L), "no runs");
        assertThrows(IllegalArgumentException.class, () -> new PlanNode<>(0, 1, 1, "saw", PLANKS, 1, 0L),
                "nothing needed");
    }

    @Test
    void theGrantedAmountIsNeverMoreThanWasAsked() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPlan<>(List.of(chest(0)), 0, 8L, Map.of(), 1L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPlan<>(List.of(chest(0)), 0, 8L, Map.of(), 1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionPlan<>(List.of(chest(0)), 0, 8L, Map.of(), 0L, 0L));
    }

    @Test
    void aResultIsEitherAPlanOrARefusal() {
        ProductionPlan<String, String> plan = plan(List.of(planks(0, 1), chest(1)));
        ProductionPlanResult<String, String> accepted = ProductionPlanResult.accepted(plan);
        assertTrue(accepted.accepted());
        assertEquals(plan, accepted.orElseThrow());
        assertTrue(accepted.refusal().isEmpty());
        assertFalse(accepted.refusedWith(PlanRefusal.LOOP));
        ProductionPlanResult<String, String> refused = ProductionPlanResult.refused(PlanRefusal.MISSING_INGREDIENT,
                LOG);
        assertFalse(refused.accepted());
        assertEquals(Optional.of(LOG), refused.about());
        assertTrue(refused.refusedWith(PlanRefusal.MISSING_INGREDIENT));
        assertThrows(IllegalStateException.class, refused::orElseThrow);
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlanResult<>(Optional.of(plan),
                Optional.of(PlanRefusal.LOOP), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlanResult<String, String>(Optional.empty(),
                Optional.empty(), Optional.of(LOG)));
        assertThrows(IllegalArgumentException.class, () -> new ProductionPlanResult<>(Optional.of(plan),
                Optional.empty(), Optional.of(LOG)));
    }

    /** The bounds come from a config file a player edits, so they are clamped and never throw. */
    @Test
    void theLimitsAreClampedIntoRange() {
        assertEquals(PlanLimits.MIN_STEPS, new PlanLimits(0, 10L).maxSteps());
        assertEquals(PlanLimits.MAX_STEPS, new PlanLimits(Integer.MAX_VALUE, 10L).maxSteps());
        assertEquals(PlanLimits.MIN_INGREDIENT_ITEMS, new PlanLimits(4, -5L).maxIngredientItems());
        assertEquals(PlanLimits.MAX_INGREDIENT_ITEMS, new PlanLimits(4, Long.MAX_VALUE).maxIngredientItems());
        assertFalse(PlanLimits.SINGLE_LEVEL.allowsChains(), "one step is the off switch");
        assertTrue(PlanLimits.DEFAULT.allowsChains());
    }

    /** The step bound is the smaller of the configured one and the aisle's free order slots, and it says which. */
    @Test
    void theStepLimitIsWhicheverBoundIsSmaller() {
        // Three of nine slots free, so orders really are open: waiting would let a longer chain through.
        ProductionPlanInput<String, String> input = ProductionPlanInput.of(
                List.of(StationPattern.of("saw", PLANKS)), PlanBudget.ofMap(Map.of()), new PlanLimits(6, 100L), 3, 9);
        assertEquals(3, input.stepLimit());
        assertTrue(input.slotsBind());
        ProductionPlanInput<String, String> roomy = ProductionPlanInput.of(
                List.of(StationPattern.of("saw", PLANKS)), PlanBudget.ofMap(Map.of()), new PlanLimits(6, 100L), 9);
        assertEquals(6, roomy.stepLimit());
        assertFalse(roomy.slotsBind());
        // An idle aisle whose whole order cap is the bound: nothing is running, so waiting cannot help and the
        // configuration is what has to change (M20 review fix).
        ProductionPlanInput<String, String> idle = ProductionPlanInput.of(
                List.of(StationPattern.of("saw", PLANKS)), PlanBudget.ofMap(Map.of()), new PlanLimits(32, 100L), 4, 4);
        assertEquals(4, idle.stepLimit());
        assertFalse(idle.slotsBind(), "an idle aisle is never busy, whatever its cap");
        assertEquals(9, ProductionPlanInput.of(List.of(), PlanBudget.ofMap(Map.of()), PlanLimits.DEFAULT, 9, 2)
                .orderSlots(), "a cap below the free slots is no cap at all");
        assertEquals(0, ProductionPlanInput.of(List.of(), PlanBudget.ofMap(Map.of()), PlanLimits.DEFAULT, -4)
                .freeOrderSlots(), "a negative slot count is none");
        assertEquals(ProductionPlanInput.UNLIMITED_ROOM, roomy.roomFor(PLANK), "no rule caps anything by default");
        assertFalse(roomy.isPaused(PLANK));
        assertEquals(0L, roomy.withHeadroom(key -> -3L).roomFor(PLANK), "a negative headroom is no room");
        assertTrue(roomy.withPaused(java.util.Set.of(PLANK)).isPaused(PLANK));
    }
}
