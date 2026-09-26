package dev.wareworks.core.stock;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.production.ProductionPattern;

/**
 * What automatic restocking decided about <b>one</b> stock rule (M15 part 2, issue #3): whether to order, how much,
 * and what to tell the player when it cannot.
 * <p>
 * The decision is pure and complete — it is the whole of {@code RestockPlanner}'s answer — which is why every branch of
 * it is unit tested without a game. The content layer only carries it out: on {@link RestockOutcome#ORDERED} it starts
 * the ordinary production order M11 already runs, with no request behind it, and on every other outcome it does
 * nothing at all but report.
 *
 * @param key               the item the rule governs
 * @param outcome           what happened, and the one value every surface shows
 * @param amount            result items to order, {@code > 0} only for {@link RestockOutcome#ORDERED}. It is what
 *                          {@link #runs()} runs of {@link #pattern()} really yield, so nothing downstream has to round
 *                          anything again
 * @param runs              runs of {@link #pattern()} the order makes, {@code > 0} only for
 *                          {@link RestockOutcome#ORDERED}. The planner chooses it and the content layer <b>uses</b> it
 *                          rather than deriving it a second time: two roundings of the same number are two different
 *                          orders, and the question of how much a rule may spend then has two answers (M15 review fix)
 * @param pattern           the pattern that would make it, present only for {@link RestockOutcome#ORDERED}
 * @param missingIngredient the first ingredient the order could not be paid for, for
 *                          {@link RestockOutcome#WAITING_FOR_INGREDIENTS}; empty otherwise. This is the item a player
 *                          has to supply, because stage 1 never produces an ingredient to produce something else
 * @param <K>               item key type
 */
public record RestockDecision<K>(K key, RestockOutcome outcome, long amount, int runs,
                                 Optional<ProductionPattern<K>> pattern, Optional<K> missingIngredient) {
    public RestockDecision {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        if (pattern == null)
            pattern = Optional.empty();
        if (missingIngredient == null)
            missingIngredient = Optional.empty();
        amount = outcome == RestockOutcome.ORDERED ? Math.max(1L, amount) : 0L;
        runs = outcome == RestockOutcome.ORDERED ? Math.max(1, runs) : 0;
        if (outcome != RestockOutcome.ORDERED)
            pattern = Optional.empty();
        if (outcome != RestockOutcome.WAITING_FOR_INGREDIENTS)
            missingIngredient = Optional.empty();
    }

    /** A decision that orders nothing, for the given reason. */
    public static <K> RestockDecision<K> of(K key, RestockOutcome outcome) {
        return new RestockDecision<>(key, outcome, 0L, 0, Optional.empty(), Optional.empty());
    }

    /** "The ingredients are not there", naming the first item the order could not be paid for. */
    public static <K> RestockDecision<K> waitingFor(K key, K missingIngredient) {
        return new RestockDecision<>(key, RestockOutcome.WAITING_FOR_INGREDIENTS, 0L, 0, Optional.empty(),
                Optional.ofNullable(missingIngredient));
    }

    /** "Make {@code runs} runs of this pattern for {@code key}", which yield {@link #amount()} items. */
    public static <K> RestockDecision<K> order(K key, int runs, ProductionPattern<K> pattern) {
        Objects.requireNonNull(pattern, "pattern");
        int made = Math.max(1, runs);
        return new RestockDecision<>(key, RestockOutcome.ORDERED, pattern.resultFor(made), made,
                Optional.of(pattern), Optional.empty());
    }

    /** Whether this decision starts a production order. */
    public boolean ordered() {
        return outcome.ordered();
    }

    /** This decision as one that was not carried out, because another rule's order went first in the same pass. */
    public RestockDecision<K> deferred() {
        return ordered() ? RestockDecision.of(key, RestockOutcome.DEFERRED) : this;
    }
}
