package dev.wareworks.core.production;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What an aisle could produce right now, from the patterns of its production stations
 * ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * <b>Stage 1 is single level.</b> An ingredient counts only as what is <i>in stock and not promised</i>; a pattern
 * whose ingredient is itself only producible yields nothing here. That is the whole recursion rule: this class never
 * looks at a second pattern to satisfy the first, so an order can never be created that waits for another order
 * ({@link #firstMissingIngredient} names the ingredient a player has to supply instead).
 * <p>
 * Pure and stateless; every query takes the availability function the controller passes
 * ({@code WarehouseControllerBlockEntity#availableStock}, which already subtracts reservations, open requests and the
 * ingredients other production orders promised). Cost per query: one pass over the patterns times their ingredients,
 * i.e. at most {@code maxProductionPatterns} × {@value ProductionPattern#MAX_INGREDIENTS} per production station.
 * Measured at the cap (issue #35, ADR-068, {@code ProductionScaleTest}): the core of the terminal's producible pass
 * (this planner over a map of stock; the controller's reservation lookup and station resolution are not included) over
 * 8 stations × 36 nine-ingredient patterns takes about 0.01 ms; it grew × 4.7 for × 4.5 the patterns of the old cap.
 * <p>
 * <b>Choice ingredients (ADR-082).</b> A filter cell is paid by {@link ChoiceAllocator} out of the keys an
 * {@link IngredientChoices} names, in whole runs, the largest stock first. The overloads without an
 * {@code IngredientChoices} use {@link IngredientChoices#none()}, so a pattern with choices makes nothing there. With
 * choices, {@link #runsPossible(ProductionPattern, ToLongFunction, IngredientChoices)} has a closed form when no two
 * ingredients can draw on the same key, and otherwise a binary search of payment probes, each of which really paid its
 * runs — so the answer never over-promises; with pathological overlaps it may under-estimate. Measured at the cap
 * ({@code ProductionScaleTest}): 8 stations × 36 patterns, each with two choices of 64 candidates over 2 000 stock keys
 * (far past a real aisle), take about 4 ms for the whole producible pass with disjoint candidate lists and about 7 ms
 * with overlapping ones (one payment probe per pattern); a plan over that aisle about 0.05 ms. A pattern without choices
 * costs what it always did.
 */
public final class ProduciblePlanner {
    private ProduciblePlanner() {
    }

    /**
     * The item keys any of {@code patterns} yields, in pattern order. This is the set a terminal marks as producible,
     * <b>whether or not the ingredients are in stock</b>: a player wants to see that an item can be made here before
     * they gather what it needs.
     */
    public static <K> Set<K> producibleKeys(Collection<? extends ProductionPattern<K>> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        Set<K> keys = new LinkedHashSet<>();
        for (ProductionPattern<K> pattern : patterns)
            keys.add(pattern.result().key());
        return keys;
    }

    /**
     * How many runs of {@code pattern} the currently available ingredients support (0 when one of them is missing).
     *
     * @param available how many items of a key a new promise may still claim; negative values count as 0
     */
    public static <K> int runsPossible(ProductionPattern<K> pattern, ToLongFunction<? super K> available) {
        return runsPossible(pattern, available, IngredientChoices.none());
    }

    /**
     * {@link #runsPossible(ProductionPattern, ToLongFunction)} with the keys that may pay a choice ingredient
     * (ADR-082): each choice is paid in whole runs out of its candidates, never with the pattern's own result.
     */
    public static <K> int runsPossible(ProductionPattern<K> pattern, ToLongFunction<? super K> available,
            IngredientChoices<K> choices) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(choices, "choices");
        if (pattern.hasChoices())
            return runsWithChoices(pattern, available, choices);
        long runs = Long.MAX_VALUE;
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            long stock = Math.max(0L, available.applyAsLong(ingredient.key()));
            runs = Math.min(runs, stock / ingredient.count());
            if (runs == 0L)
                return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, runs);
    }

    /**
     * The runs of a pattern with choices: the closed-form upper bound when no two ingredients can draw on one key, and
     * otherwise the largest run count a real payment probe achieved below that bound.
     */
    private static <K> int runsWithChoices(ProductionPattern<K> pattern, ToLongFunction<? super K> available,
            IngredientChoices<K> choices) {
        K result = pattern.result().key();
        Predicate<K> excluded = result::equals;
        PlanBudget<K> budget = PlanBudget.of(available);
        ChoiceAllocator.FreeMemo<K> free = new ChoiceAllocator.FreeMemo<>(choices);
        Set<K> seen = new HashSet<>();
        boolean exact = true;
        long upper = Long.MAX_VALUE;
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            if (!pattern.isChoice(ingredient.key())) {
                exact &= seen.add(ingredient.key());
                upper = Math.min(upper, budget.available(ingredient.key()) / ingredient.count());
                continue;
            }
            // The sum of the eight largest whole-run counts bounds what any eight paying keys reach.
            long[] top = new long[ChoiceAllocator.MAX_KEYS_PER_CHOICE];
            int paying = 0;
            boolean byAvailability = true;
            boolean allFree = true;
            boolean freeIsAvailable = true;
            Set<K> own = new HashSet<>();
            for (K key : choices.candidates(pattern, ingredient.key())) {
                if (key == null || excluded.test(key) || !own.add(key))
                    continue;
                exact &= seen.add(key);
                long stock = budget.available(key);
                long freeStock = free.free(key);
                byAvailability &= choices.plain(key);
                allFree &= freeStock == Long.MAX_VALUE;
                freeIsAvailable &= freeStock == stock;
                long runs = stock / ingredient.count();
                if (runs <= 0L)
                    continue;
                paying++;
                insertTop(top, runs);
            }
            // With more paying keys than one choice may use, the pick order decides which of them pay. When that order
            // is plain availability (every key plain, and the free stock either unknown or the availability itself),
            // the keys that pay are exactly the eight largest.
            byAvailability &= allFree || freeIsAvailable;
            exact &= paying <= ChoiceAllocator.MAX_KEYS_PER_CHOICE || byAvailability;
            long sum = 0L;
            for (long runs : top)
                sum += runs;
            upper = Math.min(upper, sum);
        }
        int bound = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, upper));
        // No two ingredients draw on one key and every paying key is used: the bound is the answer.
        if (bound == 0 || exact)
            return bound;
        // Otherwise only a real payment answers: the bound itself usually pays, and a binary search below it is the
        // rare case. Either way the answer is one a probe achieved.
        if (pays(pattern, bound, budget, choices, excluded, free))
            return bound;
        int lo = 0;
        int hi = bound - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (pays(pattern, mid, budget, choices, excluded, free))
                lo = mid;
            else
                hi = mid - 1;
        }
        return lo;
    }

    /** Keeps the largest values in {@code top}, descending. */
    private static void insertTop(long[] top, long value) {
        if (value <= top[top.length - 1])
            return;
        int at = top.length - 1;
        while (at > 0 && top[at - 1] < value) {
            top[at] = top[at - 1];
            at--;
        }
        top[at] = value;
    }

    /**
     * Whether {@code runs} runs of {@code pattern} can be paid in full out of a fresh ledger over {@code snapshot}'s
     * remembered availability.
     */
    private static <K> boolean pays(ProductionPattern<K> pattern, int runs, PlanBudget<K> snapshot,
            IngredientChoices<K> choices, Predicate<K> excluded, ChoiceAllocator.FreeMemo<K> free) {
        PlanBudget<K> budget = snapshot.fresh();
        List<ProductionEntry<K>> ingredients = pattern.ingredients();
        for (int index : ChoiceAllocator.payOrder(pattern, choice -> choices.candidates(pattern, choice), budget)) {
            ProductionEntry<K> ingredient = ingredients.get(index);
            long need = (long) ingredient.count() * runs;
            if (!pattern.isChoice(ingredient.key())) {
                if (budget.take(ingredient.key(), need) < need)
                    return false;
                continue;
            }
            ChoiceAllocator.Payment<K> payment = ChoiceAllocator.pay(ingredient.count(), runs,
                    choices.candidates(pattern, ingredient.key()), budget, choices, excluded, free);
            if (payment.remainderRuns() > 0)
                return false;
        }
        return true;
    }

    /** How many items of its result {@code pattern} could yield right now, 0 when an ingredient is missing. */
    public static <K> long producibleAmount(ProductionPattern<K> pattern, ToLongFunction<? super K> available) {
        return producibleAmount(pattern, available, IngredientChoices.none());
    }

    /** {@link #producibleAmount(ProductionPattern, ToLongFunction)} with the keys that may pay a choice (ADR-082). */
    public static <K> long producibleAmount(ProductionPattern<K> pattern, ToLongFunction<? super K> available,
            IngredientChoices<K> choices) {
        int runs = runsPossible(pattern, available, choices);
        return runs == 0 ? 0L : (long) runs * pattern.result().count();
    }

    /**
     * How many items of {@code key} the aisle could produce right now: the best single pattern, never the sum of
     * several. Two patterns for one item usually compete for the same ingredients, so adding them up would promise
     * items twice; taking the best one is the amount that is certainly reachable.
     */
    public static <K> long producibleAmount(Collection<? extends ProductionPattern<K>> patterns, K key,
            ToLongFunction<? super K> available) {
        return producibleAmount(patterns, key, available, IngredientChoices.none());
    }

    /** {@link #producibleAmount(Collection, Object, ToLongFunction)} with the keys that may pay a choice (ADR-082). */
    public static <K> long producibleAmount(Collection<? extends ProductionPattern<K>> patterns, K key,
            ToLongFunction<? super K> available, IngredientChoices<K> choices) {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(key, "key");
        long best = 0L;
        for (ProductionPattern<K> pattern : patterns) {
            if (pattern.produces(key))
                best = Math.max(best, producibleAmount(pattern, available, choices));
        }
        return best;
    }

    /**
     * The pattern that can make the most of {@code key} right now, or empty when none of them can make any. Ties keep
     * the first pattern in iteration order, so the same warehouse always picks the same pattern.
     */
    public static <K> Optional<ProductionPattern<K>> bestPatternFor(Collection<? extends ProductionPattern<K>> patterns,
            K key, ToLongFunction<? super K> available) {
        return bestPatternFor(patterns, key, available, IngredientChoices.none());
    }

    /** {@link #bestPatternFor(Collection, Object, ToLongFunction)} with the keys that may pay a choice (ADR-082). */
    public static <K> Optional<ProductionPattern<K>> bestPatternFor(Collection<? extends ProductionPattern<K>> patterns,
            K key, ToLongFunction<? super K> available, IngredientChoices<K> choices) {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(key, "key");
        ProductionPattern<K> best = null;
        long bestAmount = 0L;
        for (ProductionPattern<K> pattern : patterns) {
            if (!pattern.produces(key))
                continue;
            long amount = producibleAmount(pattern, available, choices);
            if (amount > bestAmount) {
                best = pattern;
                bestAmount = amount;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The first ingredient of {@code pattern} that {@code runs} runs cannot be paid for, or empty when all of them
     * can. This is what a refusal tells the player in stage 1: the missing item is theirs to supply, because nothing
     * here will produce it ({@code docs/warehouse-system.md} §3.5).
     */
    public static <K> Optional<ProductionEntry<K>> firstMissingIngredient(ProductionPattern<K> pattern, int runs,
            ToLongFunction<? super K> available) {
        return firstMissingIngredient(pattern, runs, available, IngredientChoices.none());
    }

    /**
     * {@link #firstMissingIngredient(ProductionPattern, int, ToLongFunction)} with the keys that may pay a choice
     * (ADR-082): a choice is missing when the whole runs its candidates hold together fall short, and it is named by the
     * filter's own entry.
     */
    public static <K> Optional<ProductionEntry<K>> firstMissingIngredient(ProductionPattern<K> pattern, int runs,
            ToLongFunction<? super K> available, IngredientChoices<K> choices) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(choices, "choices");
        if (runs < 1)
            return Optional.empty();
        K result = pattern.result().key();
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            if (pattern.isChoice(ingredient.key())) {
                long whole = 0L;
                Set<K> seen = new HashSet<>();
                for (K key : choices.candidates(pattern, ingredient.key())) {
                    if (key.equals(result) || !seen.add(key))
                        continue;
                    whole += Math.max(0L, available.applyAsLong(key)) / ingredient.count();
                }
                if (whole < runs)
                    return Optional.of(ingredient);
                continue;
            }
            if (Math.max(0L, available.applyAsLong(ingredient.key())) < (long) ingredient.count() * runs)
                return Optional.of(ingredient);
        }
        return Optional.empty();
    }
}
