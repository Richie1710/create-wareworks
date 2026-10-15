package dev.wareworks.core.production;

import java.util.Objects;
import java.util.Optional;

/**
 * What a production chain would do for one ingredient a craft fetch is short of, measured before anything is ordered
 * ({@code docs/warehouse-system.md} §3.4.5, issue #24, slice 3): either the items it would make available, or the
 * refusal that <b>names the item</b> in the way.
 * <p>
 * It is the answer of one {@link SequentialPlans} step and nothing else. A preview creates no order, promises nothing
 * and is thrown away after the Fetch it was measured for: the craft order's portions later go through the ordinary
 * request path, which plans the same chain again from the warehouse as it is then (ADR-032) — a measurement, and the
 * portions are the truth.
 *
 * @param promised items of the ingredient the chain would make available to the fetch, on top of what it takes from
 *                 the racks: the plan's {@link ProductionPlan#rootPromise()}. Less than asked for when the plan had to
 *                 be made smaller to fit the ingredients or a bound; 0 for a refusal
 * @param made     items the chain's root step makes in whole runs ({@link PlanNode#output()} of the root), at least
 *                 {@link #promised()}: what a portion's question compares against, and so what a Yes agrees to having
 *                 made; 0 for a refusal
 * @param refusal  why no chain makes the ingredient, present exactly when nothing is promised
 * @param about    the item the refusal names — the ingredient itself ({@link PlanRefusal#NO_PATTERN},
 *                 {@link PlanRefusal#PAUSED}) or one further down the chain ("Oak Log is missing"); empty for an
 *                 accepted chain and for a refusal that could not name one
 * @param <K>      item key type
 */
public record ChainPreview<K>(long promised, long made, Optional<PlanRefusal> refusal, Optional<K> about) {
    public ChainPreview {
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(about, "about");
        if (refusal.isPresent()) {
            if (promised != 0L || made != 0L)
                throw new IllegalArgumentException("a refused chain makes nothing: " + promised + "/" + made);
        } else {
            if (about.isPresent())
                throw new IllegalArgumentException("an accepted chain is not about a refused item");
            if (promised < 1L)
                throw new IllegalArgumentException("an accepted chain promises at least 1 item: " + promised);
            if (made < promised)
                throw new IllegalArgumentException("a chain makes at least what it promises: " + made + " < "
                        + promised);
        }
    }

    /** A chain that makes {@code made} items in whole runs, {@code promised} of them for the fetch. */
    public static <K> ChainPreview<K> accepted(long promised, long made) {
        return new ChainPreview<>(promised, made, Optional.empty(), Optional.empty());
    }

    /** The chain the plan describes: its root's promise and its root's whole-run output. */
    public static <K> ChainPreview<K> of(ProductionPlan<K, ?> plan) {
        Objects.requireNonNull(plan, "plan");
        return accepted(plan.rootPromise(), plan.root().output());
    }

    /** No chain, for {@code reason}, about {@code about}. */
    public static <K> ChainPreview<K> refused(PlanRefusal reason, K about) {
        return new ChainPreview<>(0L, 0L, Optional.of(Objects.requireNonNull(reason, "reason")),
                Optional.ofNullable(about));
    }

    /** Whether a chain would be made. */
    public boolean accepted() {
        return refusal.isEmpty();
    }

    /**
     * Whether a chain was really <b>tried</b> and did not hold: refused for any reason but
     * {@link PlanRefusal#NO_PATTERN}. Nothing in the aisle makes an ingredient refused with that one, so there is no
     * chain to talk about and the ingredient is simply missing; every other refusal says why the aisle's own machines
     * cannot make up for it — a stopped item, a missing raw material, busy order slots.
     */
    public boolean chainRefused() {
        return refusal.filter(reason -> reason != PlanRefusal.NO_PATTERN).isPresent();
    }
}
