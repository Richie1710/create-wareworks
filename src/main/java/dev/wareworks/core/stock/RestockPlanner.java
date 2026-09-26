package dev.wareworks.core.stock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

import dev.wareworks.core.production.ProduciblePlanner;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;

/**
 * The decision behind automatic restocking (M15 part 2, issue #3): <i>should the warehouse make this item by itself,
 * and how much of it?</i>
 * <p>
 * <b>One number drives it.</b> A rule's minimum is a standing order: "keep 256 planks". When the warehouse has less
 * than that — counting what is already on its way and what an open order will bring back ({@link StockLevels#pipeline()})
 * — and a production station of the same aisle holds a pattern for the item, the warehouse orders the product by
 * itself. It is the very same production order a terminal request starts (M11, ADR-024), with <b>no request behind
 * it</b>: the crane delivers the ingredients, the player's machine works, and the result comes back through an ordinary
 * warehouse input. A pattern makes whole runs, so a warehouse usually settles a little above its minimum, which is the
 * intended reading of one number.
 * <p>
 * <b>The reserve binds it.</b> The ingredients are measured with the availability of
 * {@link StockAccess#AUTOMATION} — the caller passes {@link StockAvailability#of} — so an automatic restock can never
 * spend items a reserve protects, whether it asks for them directly or reaches them through a pattern. This is the
 * same path a redstone request already takes; there is deliberately no second one.
 * <p>
 * <b>Stage 1 stays stage 1.</b> {@link ProduciblePlanner} is used unchanged, so an ingredient that is itself only
 * producible counts for nothing and an order that waits for another order stays unrepresentable. A rule never triggers
 * production of an ingredient: a player expresses that with a second rule, visibly.
 * <p>
 * <b>A minimum holds items back from other rules too.</b> An order never spends an ingredient that a governing rule of
 * the same aisle is itself below its minimum on: it reports {@link RestockOutcome#WAITING_FOR_INGREDIENTS} and names
 * that item instead. Without this, two rules whose patterns are inverses of each other — iron ingot to iron block and
 * back, both plain vanilla recipes — convert the same items back and forth for as long as the world runs, and a lossy
 * pair drains to zero (M15 review fix). It is the sentence a reserve already says, one level up: what a rule is asking
 * for is not free for another rule's automation.
 * <p>
 * <b>An order never overshoots its own maximum.</b> The shortfall is still rounded <b>up</b> to whole runs — a pattern
 * cannot be cut, which is why "keep 256" settles a little above 256 — but never past the whole runs the rule's own
 * {@code headroom} leaves room for, because a surplus stored above the cap never leaves the warehouse again: the rule
 * would read {@code AT_MAXIMUM} for ever and a warehouse input holding the item would back up. A shortfall no whole run
 * fits into is reported as {@link RestockOutcome#NO_ROOM} rather than ordered.
 * <p>
 * <b>Nothing here spends anything.</b> The planner reads numbers and returns a decision; the content layer starts the
 * order. It is pure Java with no Minecraft types, and every branch is a unit test.
 */
public final class RestockPlanner {
    private RestockPlanner() {
    }

    /**
     * What to do about one rule, with no other rule's shortfall to respect; see
     * {@link #decide(RestockInput, RestockLimits, int, boolean, ToLongFunction, Set)}.
     */
    public static <K> RestockDecision<K> decide(RestockInput<K> input, RestockLimits limits, int openRestockOrders,
            boolean productionQueueFull, ToLongFunction<? super K> availableToAutomation) {
        return decide(input, limits, openRestockOrders, productionQueueFull, availableToAutomation, Set.of());
    }

    /**
     * What to do about one rule.
     *
     * @param input                the rule and what the warehouse knows about its item
     * @param limits               the configured bounds
     * @param openRestockOrders    automatic orders already open in the whole aisle
     * @param productionQueueFull  whether the aisle's production order queue ({@code maxProductionOrders}) is full, so
     *                             no order of any kind could be started
     * @param availableToAutomation how many items of a key the warehouse's own automation may still be promised,
     *                             i.e. {@code availableStock} with a governing reserve already taken off
     *                             ({@link StockAvailability#of}). Negative answers count as 0
     * @param calledFor            items another governing rule of the same aisle is itself below its minimum on. A
     *                             pattern that consumes one of them is not run: the rule waits and names that item
     *                             instead of taking what another rule is asking for
     */
    public static <K> RestockDecision<K> decide(RestockInput<K> input, RestockLimits limits, int openRestockOrders,
            boolean productionQueueFull, ToLongFunction<? super K> availableToAutomation, Set<K> calledFor) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(availableToAutomation, "availableToAutomation");
        Objects.requireNonNull(calledFor, "calledFor");
        K key = input.key();
        // Structural answers first: they make every later question meaningless, and each of them is cheaper than the
        // one after it.
        if (!input.governs())
            return RestockDecision.of(key, RestockOutcome.NOT_GOVERNING);
        // The safety stop outranks the shortfall: a paused rule is short of its minimum by definition, and saying
        // "below minimum" for it would hide the one state a player has to act on (StockRulePause).
        if (input.paused())
            return RestockDecision.of(key, RestockOutcome.PAUSED);
        long shortfall = input.rule().shortfall(input.levels());
        if (shortfall <= 0L)
            // An open order of its own is why the pipeline is met, and saying so is more use to a player than
            // "within its limits" over an empty rack: the items are being made right now (M15 part 2).
            return RestockDecision.of(key,
                    input.openOrders() > 0 ? RestockOutcome.ORDER_OPEN : RestockOutcome.SATISFIED);
        if (!limits.enabled())
            return RestockDecision.of(key, RestockOutcome.DISABLED);
        // "Never order while one is open for the same rule" is this line at the default of one order per rule.
        if (input.openOrders() >= limits.ordersPerRule())
            return RestockDecision.of(key, RestockOutcome.ORDER_OPEN);
        if (productionQueueFull || Math.max(0, openRestockOrders) >= limits.ordersPerAisle())
            return RestockDecision.of(key, RestockOutcome.ORDERS_BUSY);
        List<ProductionPattern<K>> patterns = input.patterns();
        if (patterns.isEmpty())
            return RestockDecision.of(key, RestockOutcome.NO_PATTERN);
        long amount = limits.boundAmount(shortfall);
        if (amount <= 0L)
            return RestockDecision.of(key, RestockOutcome.SATISFIED);
        // Patterns whose ingredients another rule is itself short of are not run at all: whichever of them could make
        // the most, spending those items is taking what that other rule is asking for.
        List<ProductionPattern<K>> usable = withoutCalledFor(patterns, calledFor);
        if (usable.isEmpty())
            return waitingForCalledFor(key, patterns, calledFor);
        // The best pattern is the one that could make the most right now, exactly as a request picks it, so a rule and
        // a player ordering the same item behave the same way.
        ProductionPattern<K> chosen = bestPattern(usable, availableToAutomation);
        if (chosen == null)
            return waitingFor(key, usable, availableToAutomation);
        // The shortfall rounded UP to whole runs: a pattern cannot be cut, which is why "keep 256" settles a little
        // above 256 and why one number is enough for a player.
        int runs = chosen.runsFor(amount);
        // But never more whole runs than the rule's own maximum leaves room for (M15 review fix). A surplus stored above
        // a cap never leaves the warehouse again: the rule would report AT_MAXIMUM for ever, its lamp would stay lit and
        // a warehouse input holding the item would back up on purpose — the state §3.6 teaches a player to read as a
        // jam. An uncapped rule has room for Long.MAX_VALUE and is unaffected.
        runs = Math.min(runs, wholeRunsWithin(input.rule().headroom(input.levels()), chosen.result().count()));
        if (runs < 1)
            // "Keep exactly 64, made four at a time, and 62 in the racks": a pair of numbers only a player can resolve.
            return RestockDecision.of(key, RestockOutcome.NO_ROOM);
        // What one order may really spend, measured in ingredient items rather than in product: this is the size the
        // first loss the safety stop allows is bounded by, and maxRestockOrdersPerRule sequences the rest.
        runs = Math.min(runs, limits.boundRuns(chosen.ingredientItems()));
        runs = Math.min(runs, ProduciblePlanner.runsPossible(chosen, availableToAutomation));
        if (runs < 1)
            return waitingFor(key, usable, availableToAutomation);
        return RestockDecision.order(key, runs, chosen);
    }

    /** How many whole runs of {@code resultCount} items fit into {@code room}; bounded to an {@code int}. */
    private static int wholeRunsWithin(long room, int resultCount) {
        if (room <= 0L || resultCount < 1)
            return 0;
        return (int) Math.min(Integer.MAX_VALUE, room / resultCount);
    }

    /**
     * What to do about a whole aisle: one decision per rule, of which <b>at most one</b> orders. See
     * {@link RestockPlan} for why.
     *
     * @param inputs the aisle's rules in rule order; a rule that governs nothing may be included and is reported as
     *               {@link RestockOutcome#NOT_GOVERNING}
     */
    public static <K> RestockPlan<K> plan(List<RestockInput<K>> inputs, RestockLimits limits, int openRestockOrders,
            boolean productionQueueFull, ToLongFunction<? super K> availableToAutomation) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty())
            return RestockPlan.empty();
        Set<K> calledFor = calledFor(inputs);
        List<RestockDecision<K>> decisions = new ArrayList<>(inputs.size());
        boolean ordered = false;
        for (RestockInput<K> input : inputs) {
            RestockDecision<K> decision = decide(input, limits, openRestockOrders, productionQueueFull,
                    availableToAutomation, calledFor);
            if (decision.ordered()) {
                if (ordered)
                    decision = decision.deferred();
                else
                    ordered = true;
            }
            decisions.add(decision);
        }
        return new RestockPlan<>(decisions);
    }

    /**
     * The items a governing rule of this pass is itself below its minimum on — what no other rule's order may spend.
     * <p>
     * A paused rule counts too: it still calls for its item, and the safety stop only stops it from ordering. A rule's
     * own item can never be in its own way, because a pattern may not produce one of its own ingredients.
     */
    private static <K> Set<K> calledFor(List<RestockInput<K>> inputs) {
        Set<K> keys = new LinkedHashSet<>();
        for (RestockInput<K> input : inputs) {
            if (input.governs() && input.rule().isBelowMinimum(input.levels()))
                keys.add(input.key());
        }
        return keys;
    }

    /** The patterns that consume none of {@code calledFor}; the list itself when that set is empty. */
    private static <K> List<ProductionPattern<K>> withoutCalledFor(List<ProductionPattern<K>> patterns,
            Set<K> calledFor) {
        if (calledFor.isEmpty())
            return patterns;
        List<ProductionPattern<K>> usable = new ArrayList<>(patterns.size());
        for (ProductionPattern<K> pattern : patterns) {
            if (!consumesAnyOf(pattern, calledFor))
                usable.add(pattern);
        }
        return usable;
    }

    private static <K> boolean consumesAnyOf(ProductionPattern<K> pattern, Set<K> keys) {
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            if (keys.contains(ingredient.key()))
                return true;
        }
        return false;
    }

    /**
     * "Another rule is asking for this ingredient", naming the first such ingredient of the first pattern in aisle
     * order — the item a player has to supply to get both rules moving.
     */
    private static <K> RestockDecision<K> waitingForCalledFor(K key, List<ProductionPattern<K>> patterns,
            Set<K> calledFor) {
        for (ProductionPattern<K> pattern : patterns) {
            for (ProductionEntry<K> ingredient : pattern.ingredients()) {
                if (calledFor.contains(ingredient.key()))
                    return RestockDecision.waitingFor(key, ingredient.key());
            }
        }
        return RestockDecision.of(key, RestockOutcome.WAITING_FOR_INGREDIENTS);
    }

    /** The pattern that could make the most of the item right now, or {@code null} when none can make any. */
    private static <K> ProductionPattern<K> bestPattern(List<ProductionPattern<K>> patterns,
            ToLongFunction<? super K> available) {
        ProductionPattern<K> best = null;
        long bestAmount = 0L;
        for (ProductionPattern<K> pattern : patterns) {
            long amount = ProduciblePlanner.producibleAmount(pattern, available);
            if (amount > bestAmount) {
                best = pattern;
                bestAmount = amount;
            }
        }
        return best;
    }

    /**
     * "The ingredients are not there", naming the first ingredient of the <b>first</b> pattern that one run cannot be
     * paid for. The first pattern in aisle order is used so that the same warehouse always names the same item; a
     * player who supplies it makes that pattern runnable, and the next evaluation orders.
     */
    private static <K> RestockDecision<K> waitingFor(K key, List<ProductionPattern<K>> patterns,
            ToLongFunction<? super K> available) {
        for (ProductionPattern<K> pattern : patterns) {
            ProductionEntry<K> missing = ProduciblePlanner.firstMissingIngredient(pattern, 1, available).orElse(null);
            if (missing != null)
                return RestockDecision.waitingFor(key, missing.key());
        }
        // Every pattern could pay for one run, yet none could make anything: only reachable for a result count of 0,
        // which a pattern cannot have. Reported without an item rather than guessed at.
        return RestockDecision.of(key, RestockOutcome.WAITING_FOR_INGREDIENTS);
    }
}
