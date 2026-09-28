package dev.wareworks.core.terminal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One production order as the production section of a screen sees it (M20, issue #4, ADR-032): only the fields that
 * decide <b>which line it belongs to, where in that line it sits and what giving up on the line would cost</b>
 * ({@link PlanLines}, {@link PlanCancelCost}).
 * <p>
 * It carries no item, no amount and no state, because none of those change either answer. A screen keeps its own list of
 * full order rows and looks them up again by {@link #id()} afterwards, which is why this model is pure and can be
 * tested without a game ({@code PlanLinesTest}, {@code PlanCancelCostTest}).
 * <p>
 * Every field is <b>server-authoritative</b>: the plan an order belongs to, how deep it is in it, whether it is open and
 * how many ingredient items it has already been handed are all answered by the controller and sent in the payload
 * ({@code content.station.ProductionScreenState.OrderView}). A client never walks the chain itself, so a payload that was
 * cut short shows fewer steps instead of a wrong tree.
 *
 * @param id        the production order
 * @param plan      the <b>root order</b> of the production plan this one belongs to, empty for an ordinary single-level
 *                  order — which is what makes a warehouse without chains look exactly as it did before M20
 * @param depth     how many steps this order is below its plan's root: 0 for the root and for an order in no plan
 * @param open      whether the order is still running
 * @param delivered ingredient items the crane has already dropped into this order's station. What a cancellation of this
 *                  order would abandon, and therefore the one number {@link PlanCancelCost} needs besides the shape
 */
public record PlanMember(UUID id, Optional<UUID> plan, int depth, boolean open, long delivered) {
    public PlanMember {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plan, "plan");
        depth = Math.max(0, depth);
        delivered = Math.max(0L, delivered);
    }

    /** An ordinary order that is no part of a chain and has nothing at a machine yet. */
    public static PlanMember single(UUID id, boolean open) {
        return single(id, open, 0L);
    }

    /** An ordinary order that is no part of a chain. */
    public static PlanMember single(UUID id, boolean open, long delivered) {
        return new PlanMember(id, Optional.empty(), 0, open, delivered);
    }

    /** A member of the plan rooted at {@code plan} that has nothing at a machine yet. */
    public static PlanMember of(UUID id, UUID plan, int depth, boolean open) {
        return of(id, plan, depth, open, 0L);
    }

    /** A member of the plan rooted at {@code plan}. */
    public static PlanMember of(UUID id, UUID plan, int depth, boolean open, long delivered) {
        return new PlanMember(id, Optional.of(Objects.requireNonNull(plan, "plan")), depth, open, delivered);
    }

    /** Whether this order is the root of its own plan, i.e. the order a player actually asked for. */
    public boolean isRoot() {
        return plan.filter(id::equals).isPresent();
    }
}
