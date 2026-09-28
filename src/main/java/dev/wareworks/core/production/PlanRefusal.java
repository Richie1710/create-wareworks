package dev.wareworks.core.production;

import java.util.Locale;

/**
 * Why a production plan could not be made ({@code docs/warehouse-system.md} §3.5.6, ADR-032) — the whole point of
 * deciding a chain at the click instead of accepting an order that stalls four minutes later.
 * <p>
 * <b>Every refusal names an item</b> ({@link ProductionPlanResult#about()}), because that is what makes it something a
 * player can act on: "Oak Log is missing" sends them to a farm, "Making Oak Planks is stopped" sends them to a machine,
 * and "The chain loops at Iron Ingot" tells them their two patterns are inverses of each other. A reason without an item
 * would be the status line M11 already had. The sentences are the shipped ones: each key spends one placeholder on the
 * item and nothing on an amount, because the status row has to hold the name as well.
 * <p>
 * <b>Derived, never saved, never an ordinal on the wire.</b> A refusal is computed by one planning pass and shown as its
 * {@link #langKey()} beside the item it is about; nothing stores it, exactly as nothing stores {@code RestockOutcome}. It
 * does cross the wire once — {@code TerminalResultPayload} carries it to the screen — and it travels there by
 * {@link #name()}, like a {@code StoppedProduct}'s cause, so <b>the order of the values here is free</b> and says what the
 * planner tests: reordering them, or putting one in the middle, can never shift a sentence on a client of another build.
 * <p>
 * <b>Precedence</b> at one node, in the order {@link ProductionPlanner} tests them: {@link #PAUSED}, {@link #LOOP},
 * {@link #NO_PATTERN} / {@link #MISSING_INGREDIENT}, {@link #NO_ROOM}, {@link #TOO_MANY_STEPS} /
 * {@link #ORDERS_BUSY}, {@link #TOO_MANY_INGREDIENT_ITEMS}. The structural answers come first because they make every
 * later question meaningless, and because a smaller order cannot cure them.
 */
public enum PlanRefusal {
    /**
     * Nothing in the aisle makes the <b>ordered</b> item at all: no production station holds a pattern for it. Names
     * the ordered item. An ingredient nothing makes is {@link #MISSING_INGREDIENT} instead — the difference is what a
     * player has to do about it.
     */
    NO_PATTERN,
    /**
     * An ingredient is not in stock and nothing in the aisle makes it either, so the chain ends at an item the player
     * has to supply. Names that ingredient, and this is the sentence the feature is judged by: "Oak Log is missing".
     * <p>
     * It is also the pre-M20 answer, one level down: stage 1 refused every order whose ingredient was not real,
     * unpromised stock ({@code ProduciblePlanner#firstMissingIngredient}).
     */
    MISSING_INGREDIENT,
    /**
     * The warehouse has <b>stopped making</b> that item: an order for it ended with ingredients already handed to a
     * machine and nothing coming back, and no player has resumed it yet (ADR-027, extended by ADR-032). Names the
     * paused item.
     * <p>
     * A pause blocks <b>planning</b> and not only ordering, which is the whole of its safety value: otherwise the next
     * click rebuilds the same chain into the same broken machine.
     */
    PAUSED,
    /**
     * A step's own product would not fit under its maximum: a stock rule caps the intermediate, and the whole runs the
     * chain needs of it do not fit in the room that is left. Names the intermediate.
     * <p>
     * It is the player's own <b>maximum</b> that is respected here, and nothing about physical rack space — a rack that
     * is physically full is a separate term of the store job itself ({@code JobPlanner}). A chain has to be able to make
     * whole runs of an intermediate, and "keep exactly 64 planks, made four at a time" is a pair of numbers only a
     * player can resolve; refusing at the click says so before a machine is given anything.
     * <p>
     * The room is measured against the click alone ({@link ProductionPlanInput#headroom()}): what other orders are
     * expected to bring back is deliberately not counted as room, or the same click would be refused with an idle aisle
     * and accepted while an unrelated order happened to be in flight.
     */
    NO_ROOM,
    /**
     * The chain comes back to an item it has already made: a pattern on the path makes it, or one of its ingredients is
     * something a step further up produces — two patterns that are inverses of each other, iron ingot to iron block and
     * back. Names the item the chain returns to.
     * <p>
     * Refused <b>before anything is converted</b>, and it is what makes a plan terminate by construction rather than by
     * a depth limit. A loop rules out the <b>pattern</b> that closes it, not the item: the planner tries the item's other
     * patterns first and only reports this when every one of them is a dead end too — and then a non-loop answer from one
     * of the others is preferred, because it is the more actionable one ({@code ProductionPlanner}).
     */
    LOOP,
    /**
     * The chain needs more production orders than the configuration allows one plan to create: its own step count
     * ({@link PlanLimits#maxSteps()}) or the aisle's whole order cap ({@code maxProductionOrders}), whichever is smaller.
     * Names the item the walk wanted a further step for. The cure is a larger number in the configuration, which is why
     * it is told apart from {@link #ORDERS_BUSY} — there, waiting really does help.
     * <p>
     * Only reported when not even one run of the ordered item fits into the step count; a plan that is merely large is
     * made smaller instead.
     */
    TOO_MANY_STEPS,
    /**
     * The chain would hand more ingredient items to machines than one plan may spend
     * ({@link PlanLimits#maxIngredientItems()}). Names the step whose own runs went past the budget.
     * <p>
     * Only reported when not even one run of the ordered item fits; a larger order is made smaller instead. This is the
     * bound that decides what a single click can lose, so it is the one a player raises deliberately.
     */
    TOO_MANY_INGREDIENT_ITEMS,
    /**
     * The aisle has <b>orders open right now</b> and what is left of {@code maxProductionOrders} is not enough for the
     * chain, so its steps cannot all be created. Names the item a further order was needed for. The cure is to wait, or
     * to allow the aisle more orders — the same cure {@code RequestRejection.PRODUCTION_BUSY} already has. A chain that
     * would not fit into an <b>empty</b> aisle either is {@link #TOO_MANY_STEPS} instead
     * ({@link ProductionPlanInput#slotsBind()}).
     */
    ORDERS_BUSY;

    private static final String LANG_PREFIX = "gui.production.plan.refusal.";

    /**
     * Whether this refusal is about the <b>shape</b> of the chain rather than its size: the aisle makes the item with
     * the wrong patterns, not at all, or has stopped making it. A smaller order cannot cure it <i>at the step where it
     * was raised</i> — though a smaller order may well not need that step at all, which is why the planner still tries
     * a smaller one and only reports what the smallest one says.
     */
    public boolean isStructural() {
        return this == NO_PATTERN || this == PAUSED || this == LOOP;
    }

    /**
     * Relative lang key of this refusal's text, e.g. {@code gui.production.plan.refusal.missing_ingredient} — the only
     * externally visible form of a refusal, and the only reason {@link #name()} matters.
     */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
