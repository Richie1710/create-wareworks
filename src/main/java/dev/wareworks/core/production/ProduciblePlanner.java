package dev.wareworks.core.production;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(available, "available");
        long runs = Long.MAX_VALUE;
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            long stock = Math.max(0L, available.applyAsLong(ingredient.key()));
            runs = Math.min(runs, stock / ingredient.count());
            if (runs == 0L)
                return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, runs);
    }

    /** How many items of its result {@code pattern} could yield right now, 0 when an ingredient is missing. */
    public static <K> long producibleAmount(ProductionPattern<K> pattern, ToLongFunction<? super K> available) {
        int runs = runsPossible(pattern, available);
        return runs == 0 ? 0L : (long) runs * pattern.result().count();
    }

    /**
     * How many items of {@code key} the aisle could produce right now: the best single pattern, never the sum of
     * several. Two patterns for one item usually compete for the same ingredients, so adding them up would promise
     * items twice; taking the best one is the amount that is certainly reachable.
     */
    public static <K> long producibleAmount(Collection<? extends ProductionPattern<K>> patterns, K key,
            ToLongFunction<? super K> available) {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(key, "key");
        long best = 0L;
        for (ProductionPattern<K> pattern : patterns) {
            if (pattern.produces(key))
                best = Math.max(best, producibleAmount(pattern, available));
        }
        return best;
    }

    /**
     * The pattern that can make the most of {@code key} right now, or empty when none of them can make any. Ties keep
     * the first pattern in iteration order, so the same warehouse always picks the same pattern.
     */
    public static <K> Optional<ProductionPattern<K>> bestPatternFor(Collection<? extends ProductionPattern<K>> patterns,
            K key, ToLongFunction<? super K> available) {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(key, "key");
        ProductionPattern<K> best = null;
        long bestAmount = 0L;
        for (ProductionPattern<K> pattern : patterns) {
            if (!pattern.produces(key))
                continue;
            long amount = producibleAmount(pattern, available);
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
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(available, "available");
        if (runs < 1)
            return Optional.empty();
        for (ProductionEntry<K> ingredient : pattern.ingredients()) {
            if (Math.max(0L, available.applyAsLong(ingredient.key())) < (long) ingredient.count() * runs)
                return Optional.of(ingredient);
        }
        return Optional.empty();
    }
}
