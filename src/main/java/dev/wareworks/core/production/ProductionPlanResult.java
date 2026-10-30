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
 * <p>
 * An accepted plan may be <b>smaller than the click</b>: the planner clamps before it refuses. Then {@link #limit()}
 * is the refusal the whole order ran into and {@link #limitAbout()} its item, so the terminal can say why it stopped
 * at 40 rather than only that it did (issue #42).
 *
 * @param plan       the plan, present exactly when nothing refused it
 * @param refusal    why there is no plan, present exactly when {@link #plan()} is not
 * @param about      the item the refusal is about; empty only for a refusal that could not name one
 * @param limit      why an accepted plan makes fewer runs of the ordered item than the click asked for; empty for a
 *                   plan that makes all of them and for a refusal
 * @param limitAbout the item {@link #limit()} is about
 * @param <K>        item key type
 * @param <L>        location type
 */
public record ProductionPlanResult<K, L>(Optional<ProductionPlan<K, L>> plan, Optional<PlanRefusal> refusal,
                                         Optional<K> about, Optional<PlanRefusal> limit, Optional<K> limitAbout) {
    public ProductionPlanResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(limitAbout, "limitAbout");
        if (plan.isPresent() == refusal.isPresent())
            throw new IllegalArgumentException("a result is either a plan or a refusal");
        if (plan.isPresent() && about.isPresent())
            throw new IllegalArgumentException("an accepted plan is not about a refused item");
        if (plan.isEmpty() && (limit.isPresent() || limitAbout.isPresent()))
            throw new IllegalArgumentException("only an accepted plan can have been made smaller");
        if (limit.isEmpty())
            limitAbout = Optional.empty();
    }

    /** A plan or a refusal, with no limit. */
    public ProductionPlanResult(Optional<ProductionPlan<K, L>> plan, Optional<PlanRefusal> refusal, Optional<K> about) {
        this(plan, refusal, about, Optional.empty(), Optional.empty());
    }

    /** The plan, accepted. */
    public static <K, L> ProductionPlanResult<K, L> accepted(ProductionPlan<K, L> plan) {
        return new ProductionPlanResult<>(Optional.of(Objects.requireNonNull(plan, "plan")), Optional.empty(),
                Optional.empty());
    }

    /**
     * The plan, accepted for fewer runs of the ordered item than were asked for, because the whole order ran into
     * {@code limit} about {@code about}.
     */
    public static <K, L> ProductionPlanResult<K, L> shortened(ProductionPlan<K, L> plan, PlanRefusal limit, K about) {
        return new ProductionPlanResult<>(Optional.of(Objects.requireNonNull(plan, "plan")), Optional.empty(),
                Optional.empty(), Optional.of(Objects.requireNonNull(limit, "limit")), Optional.ofNullable(about));
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
