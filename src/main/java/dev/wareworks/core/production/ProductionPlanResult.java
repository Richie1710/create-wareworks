package dev.wareworks.core.production;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@link ProductionPlanner#plan} answered ({@code docs/warehouse-system.md} §3.5.6, ADR-032): either a complete
 * plan, or a refusal that <b>names the item it is about</b>.
 * <p>
 * Shaped like {@code core.job.PlanResult}, and for the same reason: one value carries the whole answer, so no caller has
 * to guess why nothing happened. The refusal and its item are what a terminal turns into a sentence a player can act on
 * — "Oak Log is missing" instead of "not in stock".
 *
 * @param plan    the plan, present exactly when nothing refused it
 * @param refusal why there is no plan, present exactly when {@link #plan()} is not
 * @param about   the item the refusal is about; empty only for a refusal that could not name one
 * @param <K>     item key type
 * @param <L>     location type
 */
public record ProductionPlanResult<K, L>(Optional<ProductionPlan<K, L>> plan, Optional<PlanRefusal> refusal,
                                         Optional<K> about) {
    public ProductionPlanResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(about, "about");
        if (plan.isPresent() == refusal.isPresent())
            throw new IllegalArgumentException("a result is either a plan or a refusal");
        if (plan.isPresent() && about.isPresent())
            throw new IllegalArgumentException("an accepted plan is not about a refused item");
    }

    /** The plan, accepted. */
    public static <K, L> ProductionPlanResult<K, L> accepted(ProductionPlan<K, L> plan) {
        return new ProductionPlanResult<>(Optional.of(Objects.requireNonNull(plan, "plan")), Optional.empty(),
                Optional.empty());
    }

    /** Refused for {@code reason}, about {@code about} — the item a player has to do something about. */
    public static <K, L> ProductionPlanResult<K, L> refused(PlanRefusal reason, K about) {
        return new ProductionPlanResult<>(Optional.empty(), Optional.of(Objects.requireNonNull(reason, "reason")),
                Optional.ofNullable(about));
    }

    /** Whether there is a plan. */
    public boolean accepted() {
        return plan.isPresent();
    }

    /** The plan. */
    public ProductionPlan<K, L> orElseThrow() {
        return plan.orElseThrow(() -> new IllegalStateException("refused: " + refusal.orElse(null)));
    }

    /** Whether this was refused for {@code reason}. */
    public boolean refusedWith(PlanRefusal reason) {
        return refusal.filter(found -> found == reason).isPresent();
    }
}
