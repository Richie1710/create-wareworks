package dev.wareworks.core.production;

/**
 * The hard bounds a production plan obeys ({@code docs/warehouse-system.md} §3.5.6, ADR-032), as the configuration sets
 * them.
 * <p>
 * <b>There is deliberately no depth limit.</b> A chain may be as deep as the player's machines make it — planks from
 * logs, a chest from planks, a barrel from the chest. What has to be bounded is not how <i>long</i> a chain is but how
 * much one click can cost, and these two numbers bound exactly that. Termination is not their job either: the
 * planner's cycle check refuses a chain that comes back to an item it has already made
 * ({@link PlanRefusal#LOOP}), so a plan cannot grow for ever whatever these numbers say.
 * <ul>
 *   <li>{@link #maxSteps()} — production orders one plan may create, the ordered item's own order included. It bounds
 *       how many of the player's machines one click may set to work, and with it the depth of the chain: every level
 *       costs at least one step. 1 is the off switch — a plan is then a single order and the warehouse answers exactly
 *       as it did before M20.</li>
 *   <li>{@link #maxIngredientItems()} — <b>ingredient</b> items the whole plan may hand to machines, summed over every
 *       step. This is the number that really bounds what one click can lose ({@code §3.5.4}): the step count bounds
 *       how many machines, not how much. It is the plan-wide form of {@code maxRestockIngredientItems} (M15 review
 *       fix), which exists because a pattern of nine ingots to one block turns "at most 512 blocks" into 4608
 *       ingots.</li>
 * </ul>
 * <b>A bound is a clamp before it is a refusal.</b> A plan that does not fit is made <b>smaller</b> — the planner
 * reduces the runs of the ordered item until it fits, exactly as a single-level order has always been clamped to the
 * ingredients that were there ({@code WarehouseControllerBlockEntity#startProductionOrder}). Only when not even
 * <b>one</b> run of the ordered item fits is the click refused, and then the refusal names the bound and the item
 * ({@link PlanRefusal#TOO_MANY_STEPS}, {@link PlanRefusal#TOO_MANY_INGREDIENT_ITEMS}).
 * <p>
 * Every value is clamped into range on construction and nothing here ever throws: these numbers come from a config
 * file a player edits. {@link #MAX_STEPS} is the absolute ceiling of the step count, so the planner's recursion is
 * bounded whatever is written into the configuration.
 *
 * @param maxSteps           production orders one plan may create, {@value #MIN_STEPS}..{@value #MAX_STEPS}
 * @param maxIngredientItems ingredient items one plan may hand to machines over all its steps,
 *                           {@value #MIN_INGREDIENT_ITEMS}..{@value #MAX_INGREDIENT_ITEMS}
 */
public record PlanLimits(int maxSteps, long maxIngredientItems) {
    /** Smallest step count: one order, i.e. no chain at all. */
    public static final int MIN_STEPS = 1;
    /**
     * Largest step count any configuration may ask for. It is also what bounds the planner's recursion, because every
     * level of a chain costs at least one step.
     */
    public static final int MAX_STEPS = 1024;
    /** Smallest ingredient-item budget one plan may have. */
    public static final long MIN_INGREDIENT_ITEMS = 1L;
    /** Largest ingredient-item budget any configuration may ask for. */
    public static final long MAX_INGREDIENT_ITEMS = 65536L;

    /** The shipped defaults, for tests and for a caller without a configuration. */
    public static final PlanLimits DEFAULT = new PlanLimits(32, 256L);
    /**
     * Recursion off: one step, so a plan is a single production order and the answer is the pre-M20 one. The item
     * budget is the largest one, because at a single step the only thing that should be able to bite is the step
     * count.
     */
    public static final PlanLimits SINGLE_LEVEL = new PlanLimits(MIN_STEPS, MAX_INGREDIENT_ITEMS);
    /** Both bounds at their largest, for tests that want to see what a chain does without a bound in the way. */
    public static final PlanLimits UNBOUNDED = new PlanLimits(MAX_STEPS, MAX_INGREDIENT_ITEMS);

    public PlanLimits {
        maxSteps = Math.max(MIN_STEPS, Math.min(maxSteps, MAX_STEPS));
        maxIngredientItems = Math.max(MIN_INGREDIENT_ITEMS, Math.min(maxIngredientItems, MAX_INGREDIENT_ITEMS));
    }

    /** Whether a chain may be planned at all, i.e. whether a plan may hold more than the ordered item's own order. */
    public boolean allowsChains() {
        return maxSteps > 1;
    }
}
