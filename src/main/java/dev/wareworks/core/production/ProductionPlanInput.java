package dev.wareworks.core.production;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Everything {@link ProductionPlanner} needs to know about an aisle to work out a chain
 * ({@code docs/warehouse-system.md} §3.5.6, ADR-032) — the snapshot a click is decided from.
 * <p>
 * <b>One snapshot per click.</b> The controller already builds all of this once per request: the patterns of its
 * production stations, the availability of every key behind the taker's own reserve, the paused items and the free order
 * slots. Bundling it keeps the planner's signature honest about the fact that a plan is computed from <b>one</b>
 * consistent view of the aisle, and it is what lets the planner try a smaller plan without asking the world anything
 * again ({@link PlanBudget#fresh()}).
 * <p>
 * <b>Nothing in here is mutated.</b> The planner spends forks of {@link #budget()}, never the budget itself, so a caller
 * may plan twice with the same input and get the same answer.
 *
 * @param patterns      every pattern of every production station of the aisle, in aisle order
 *                      ({@link StationPattern}); ties between two patterns for one item are resolved by this order
 * @param budget        what the plan may pay with, and the ledger of what it has spent
 * @param limits        the configured bounds
 * @param freeOrderSlots production orders the aisle could still open ({@code maxProductionOrders} minus the open ones).
 *                      A plan that does not fit into them is made smaller, and refused with
 *                      {@link PlanRefusal#ORDERS_BUSY} when not even one run of the ordered item fits
 * @param orderSlots    production orders the aisle may run at all ({@code maxProductionOrders}), never less than
 *                      {@link #freeOrderSlots()}. It is here for one reason: to tell "the orders are busy right now"
 *                      apart from "the configuration is too small for this chain" ({@link #slotsBind()}). Without it a
 *                      chain that is too long for the configured caps told a player with an <b>idle</b> aisle to wait
 *                      for an order to finish (M20 review fix)
 * @param pausedResults whether the warehouse has <b>stopped making</b> an item (ADR-027, extended by ADR-032). A paused
 *                      item is never planned — neither as the ordered item nor as a step — so the next click cannot
 *                      rebuild the same chain into the same broken machine
 * @param headroom      room left under an item's own maximum: its {@code maximum} minus what the racks hold and what a
 *                      transport job is carrying in, and {@link Long#MAX_VALUE} for an item no rule caps. Checked for
 *                      every <b>intermediate</b>, so that a chain does not quietly make four times what a player capped.
 *                      It is deliberately <b>not</b> {@code StockRule#headroom}'s own answer, which adds the expected
 *                      result of every open order ("the warehouse always takes back what it sent out for") and would
 *                      make the question depend on unrelated orders that are in flight rather than on this click
 *                      (M20 review fix)
 * @param <K>           item key type
 * @param <L>           location type
 */
public record ProductionPlanInput<K, L>(List<StationPattern<K, L>> patterns, PlanBudget<K> budget, PlanLimits limits,
                                        int freeOrderSlots, int orderSlots, Predicate<? super K> pausedResults,
                                        ToLongFunction<? super K> headroom) {
    /** What {@link #headroom()} answers for an item no rule caps: as much room as anyone could ask for. */
    public static final long UNLIMITED_ROOM = Long.MAX_VALUE;

    public ProductionPlanInput {
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(pausedResults, "pausedResults");
        Objects.requireNonNull(headroom, "headroom");
        freeOrderSlots = Math.max(0, freeOrderSlots);
        orderSlots = Math.max(freeOrderSlots, orderSlots);
    }

    /**
     * An input with nothing paused, no maximum in the way and an <b>idle</b> aisle — the shape a warehouse without stock
     * rules and without a safety stop has, and the one most tests want.
     */
    public static <K, L> ProductionPlanInput<K, L> of(List<StationPattern<K, L>> patterns, PlanBudget<K> budget,
            PlanLimits limits, int freeOrderSlots) {
        return of(patterns, budget, limits, freeOrderSlots, freeOrderSlots);
    }

    /**
     * {@link #of(List, PlanBudget, PlanLimits, int)} for an aisle that already has orders open: {@code orderSlots} is
     * the configured cap and {@code freeOrderSlots} what is left of it.
     */
    public static <K, L> ProductionPlanInput<K, L> of(List<StationPattern<K, L>> patterns, PlanBudget<K> budget,
            PlanLimits limits, int freeOrderSlots, int orderSlots) {
        return new ProductionPlanInput<>(patterns, budget, limits, freeOrderSlots, orderSlots, key -> false,
                key -> UNLIMITED_ROOM);
    }

    /** This input with the items the warehouse has stopped making. */
    public ProductionPlanInput<K, L> withPaused(Predicate<? super K> paused) {
        return new ProductionPlanInput<>(patterns, budget, limits, freeOrderSlots, orderSlots, paused, headroom);
    }

    /** This input with a fixed set of paused items. */
    public ProductionPlanInput<K, L> withPaused(Set<K> paused) {
        Objects.requireNonNull(paused, "paused");
        return withPaused(paused::contains);
    }

    /** This input with the room every item's own maximum leaves. */
    public ProductionPlanInput<K, L> withHeadroom(ToLongFunction<? super K> room) {
        return new ProductionPlanInput<>(patterns, budget, limits, freeOrderSlots, orderSlots, pausedResults, room);
    }

    /** Whether the warehouse has stopped making {@code key}. */
    public boolean isPaused(K key) {
        return pausedResults.test(key);
    }

    /** Room left under {@code key}'s own maximum, never negative. */
    public long roomFor(K key) {
        return Math.max(0L, headroom.applyAsLong(key));
    }

    /**
     * How many steps a plan may hold at all: the smaller of the configured step count and the free order slots. Both
     * bound the same thing — orders that have to exist at the same time — and taking the smaller one is what lets the
     * planner say <i>which</i> of them bit ({@link #slotsBind()}).
     */
    public int stepLimit() {
        return Math.min(limits.maxSteps(), freeOrderSlots);
    }

    /**
     * Whether {@link #stepLimit()} is short because <b>orders are open right now</b> rather than because the
     * configuration is what it is — i.e. whether waiting for an order to finish would let a longer chain through.
     * <p>
     * It is the free slots against the configured ceiling, not against {@link PlanLimits#maxSteps()} alone. With the
     * shipped defaults ({@code maxProductionOrders} 12, {@code maxProductionPlanSteps} 32) the free slots are always
     * fewer than the step count, so the older test could never be false: a chain of 13 steps in a completely idle aisle
     * was refused with "too many production orders are running; wait for one or give one up" while nothing at all was
     * running (M20 review fix).
     */
    public boolean slotsBind() {
        return freeOrderSlots < Math.min(limits.maxSteps(), orderSlots);
    }
}
