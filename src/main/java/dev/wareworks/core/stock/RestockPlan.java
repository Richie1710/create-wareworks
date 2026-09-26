package dev.wareworks.core.stock;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What automatic restocking decided about a whole aisle in one evaluation (M15 part 2, issue #3): one
 * {@link RestockDecision} per rule, in rule order, of which <b>at most one</b> orders anything.
 * <p>
 * <b>Why at most one.</b> Every order promises its ingredients, so it changes what the ingredients of the next order
 * are worth. The planner measures every rule against <i>one</i> availability snapshot, which is what makes the pass
 * consistent and cheap; a second order planned against those same, now stale, numbers would promise the same items
 * twice. Rules that would have ordered therefore report {@link RestockOutcome#DEFERRED} and are reconsidered on the
 * next evaluation ({@code stockRuleIntervalTicks} later, one second by default) against the new state.
 * <p>
 * The decisions that order nothing are kept, because they are the report: they are what a keeper's screen, the
 * controller's goggles and a terminal row show for the rule.
 *
 * @param decisions one decision per rule the planner was given, in that order
 * @param <K>       item key type
 */
public record RestockPlan<K>(List<RestockDecision<K>> decisions) {
    private static final RestockPlan<?> EMPTY = new RestockPlan<>(List.of());

    public RestockPlan {
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }

    /** A plan over no rules at all: nothing to order and nothing to report. */
    @SuppressWarnings("unchecked")
    public static <K> RestockPlan<K> empty() {
        return (RestockPlan<K>) EMPTY;
    }

    /** The one decision that starts a production order, or empty when this pass orders nothing. */
    public Optional<RestockDecision<K>> order() {
        for (RestockDecision<K> decision : decisions) {
            if (decision.ordered())
                return Optional.of(decision);
        }
        return Optional.empty();
    }

    /** The decision made for {@code key}, or empty when no rule of this pass governs it. */
    public Optional<RestockDecision<K>> decisionFor(K key) {
        Objects.requireNonNull(key, "key");
        for (RestockDecision<K> decision : decisions) {
            if (decision.key().equals(key))
                return Optional.of(decision);
        }
        return Optional.empty();
    }

    /** What happened for {@code key}, {@link RestockOutcome#NOT_GOVERNING} when no rule of this pass governs it. */
    public RestockOutcome outcomeFor(K key) {
        return decisionFor(key).map(RestockDecision::outcome).orElse(RestockOutcome.NOT_GOVERNING);
    }

    /** How many rules of this pass are blocked by something a player could change ({@link RestockOutcome#isBlocked}). */
    public int blockedCount() {
        int count = 0;
        for (RestockDecision<K> decision : decisions) {
            if (decision.outcome().isBlocked())
                count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return decisions.isEmpty();
    }
}
