package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * How a choice ingredient is paid (ADR-082, owner decision 2 of 2026-11-09): <b>the largest available stock first</b>,
 * and several kinds only when one alone is not enough — the one implementation of that rule, shared by
 * {@link ProduciblePlanner}, {@link ProductionPlanner} and automatic restocking.
 * <p>
 * <b>Whole runs only.</b> A key pays a whole number of runs of the choice ({@code count} items each), never a part of
 * one. Every order is then homogeneous per run, which is what lets a Crafter Feeder lay one kind per slot and batch
 * ({@code CrafterFeedPlan#segment}), and the arithmetic stays monotone in the runs. The price is that an amount below one
 * run of a key (three oak planks for a run of four) is not used.
 * <p>
 * <b>The pick order</b> (D4), measured at the moment of paying, so a second choice of the same plan sees what the first
 * one left:
 * <ol>
 * <li>plain keys before keys with component changes ({@link IngredientChoices#plain}), so enchanted, damaged or named
 * items are kept longest;</li>
 * <li>more <b>free</b> stock first — above every reserve ({@link IngredientChoices#free}), less what the plan already
 * took. For the warehouse's own automation that is its availability, which is decision 2 exactly; for a player a reserve
 * is still usable with the reserve question, but last;</li>
 * <li>more <b>available</b> stock for the access in use ({@link PlanBudget#available});</li>
 * <li>the candidate order, which the content layer makes {@code ItemKey.ORDER}.</li>
 * </ol>
 * Every take comes from the budget, so nothing is ever promised twice and nothing beyond what the access may spend.
 * <p>
 * Pure Java with no Minecraft types. Cost per payment: one sort of at most the candidate bound (80) plus one budget
 * operation per paying key.
 */
public final class ChoiceAllocator {
    /** Kinds one choice may be paid with at most; also what bounds the lines of an order ({@link ProductionOrder#MAX_LINES}). */
    public static final int MAX_KEYS_PER_CHOICE = 8;

    private ChoiceAllocator() {
    }

    /**
     * One concrete part of a payment.
     *
     * @param key  the key that pays
     * @param runs whole runs of the choice it pays, at least 1
     */
    public record Part<K>(K key, int runs) {
        public Part {
            Objects.requireNonNull(key, "key");
            if (runs < 1)
                throw new IllegalArgumentException("runs must be at least 1: " + runs);
        }
    }

    /**
     * What a payment came to.
     *
     * @param parts         the paying keys in allocation order, at most {@value #MAX_KEYS_PER_CHOICE}
     * @param remainderRuns runs no candidate could pay, which a planner may make or report
     */
    public record Payment<K>(List<Part<K>> parts, int remainderRuns) {
        public Payment {
            parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
            if (remainderRuns < 0)
                throw new IllegalArgumentException("remainderRuns must not be negative: " + remainderRuns);
        }

        /** Runs the parts pay together. */
        public int paidRuns() {
            int total = 0;
            for (Part<K> part : parts)
                total += part.runs();
            return total;
        }
    }

    /**
     * The free amount of every key, asked of {@link IngredientChoices#free} at most once per key, so one plan sees one
     * snapshot of the reserves just as {@link PlanBudget} is one snapshot of the availability.
     */
    public static final class FreeMemo<K> {
        private final IngredientChoices<K> choices;
        private final Map<K, Long> free = new HashMap<>();

        public FreeMemo(IngredientChoices<K> choices) {
            this.choices = Objects.requireNonNull(choices, "choices");
        }

        /** The free stock of {@code key} above every reserve, never negative. */
        public long free(K key) {
            return free.computeIfAbsent(key, k -> Math.max(0L, choices.free(k)));
        }
    }

    /**
     * Pays {@code runs} runs of one choice ingredient of {@code count} items per run out of {@code budget}, taking from
     * it: the largest stock first, a second key only when the first is short, at most
     * {@value #MAX_KEYS_PER_CHOICE} keys.
     *
     * @param count      items per run of the choice, at least 1
     * @param runs       runs to pay, 0 or more
     * @param candidates the keys that may pay, in candidate order (duplicates are paid once)
     * @param excluded   keys that must not pay: the pattern's own result and everything a plan is making further up
     * @param free       the free stock memo of this plan
     */
    public static <K> Payment<K> pay(int count, int runs, List<K> candidates, PlanBudget<K> budget,
            IngredientChoices<K> choices, Predicate<? super K> excluded, FreeMemo<K> free) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(excluded, "excluded");
        Objects.requireNonNull(free, "free");
        if (count < 1)
            throw new IllegalArgumentException("count must be at least 1: " + count);
        if (runs <= 0)
            return new Payment<>(List.of(), 0);
        List<K> ranked = ranked(candidates, budget, choices, excluded, free);
        List<Part<K>> parts = new ArrayList<>(2);
        int left = runs;
        for (K key : ranked) {
            if (left == 0 || parts.size() >= MAX_KEYS_PER_CHOICE)
                break;
            int paid = (int) Math.min(left, budget.available(key) / count);
            if (paid <= 0)
                continue;
            budget.take(key, (long) paid * count);
            parts.add(new Part<>(key, paid));
            left -= paid;
        }
        return new Payment<>(parts, left);
    }

    /**
     * The candidates without excluded keys and duplicates, in pick order (D4) as the budget stands now. Exposed for the
     * planners' upper bound ({@link ProduciblePlanner}) and for the screen's "in stock, in the order used" list.
     */
    public static <K> List<K> ranked(List<K> candidates, PlanBudget<K> budget, IngredientChoices<K> choices,
            Predicate<? super K> excluded, FreeMemo<K> free) {
        List<Measured<K>> kept = new ArrayList<>(candidates.size());
        Set<K> seen = new HashSet<>();
        for (K key : candidates) {
            if (key == null || excluded.test(key) || !seen.add(key))
                continue;
            long freeNow = free.free(key);
            if (freeNow != Long.MAX_VALUE)
                freeNow = Math.max(0L, freeNow - budget.spent(key));
            kept.add(new Measured<>(key, !choices.plain(key), freeNow, budget.available(key)));
        }
        // A stable sort, so equal keys keep candidate order (D4 rule 4).
        if (kept.size() > 1)
            kept.sort(ChoiceAllocator::compare);
        List<K> ranked = new ArrayList<>(kept.size());
        for (Measured<K> measured : kept)
            ranked.add(measured.key());
        return ranked;
    }

    /** One candidate with the numbers it is ranked by, measured once per ranking. */
    private record Measured<K>(K key, boolean variant, long free, long available) {
    }

    /** D4: plain first, then more free stock, then more available stock. */
    private static <K> int compare(Measured<K> a, Measured<K> b) {
        if (a.variant() != b.variant())
            return a.variant() ? 1 : -1;
        int byFree = Long.compare(b.free(), a.free());
        return byFree != 0 ? byFree : Long.compare(b.available(), a.available());
    }

    /**
     * The order a pattern's ingredients are paid in (D5): the exact ingredients first in pattern order, then the choices
     * <b>most constrained first</b> — fewest candidates that could pay at least one run right now, ties in pattern
     * order. A broad filter must not eat the only birch a narrow one needs.
     *
     * @param candidates the candidates of a choice key (already without excluded keys)
     * @return ingredient indices into {@link ProductionPattern#ingredients()}
     */
    public static <K> int[] payOrder(ProductionPattern<K> pattern, Function<K, List<K>> candidates,
            PlanBudget<K> budget) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(budget, "budget");
        List<ProductionEntry<K>> ingredients = pattern.ingredients();
        int[] order = new int[ingredients.size()];
        int next = 0;
        List<int[]> choiceRanks = new ArrayList<>(2);
        for (int i = 0; i < ingredients.size(); i++) {
            ProductionEntry<K> ingredient = ingredients.get(i);
            if (!pattern.isChoice(ingredient.key())) {
                order[next++] = i;
                continue;
            }
            int able = 0;
            for (K key : candidates.apply(ingredient.key())) {
                if (budget.available(key) >= ingredient.count())
                    able++;
            }
            choiceRanks.add(new int[] {i, able});
        }
        choiceRanks.sort(Comparator.comparingInt((int[] rank) -> rank[1]));
        for (int[] rank : choiceRanks)
            order[next++] = rank[0];
        return order;
    }
}
