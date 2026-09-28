package dev.wareworks.core.terminal;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What giving up on one line of a screen's production section would really cost (M20, issue #4, ADR-032): how many
 * production orders the click <b>ends</b>, how many ingredient items it abandons at a machine, and therefore whether it
 * arms the safety stop.
 * <p>
 * <b>Why this is not a sum over the open orders.</b> A cancellation does not end every open order of a plan.
 * {@code ProductionOrders#failPlan} cancels the order that was named and every open order of the plan that has been
 * handed <b>nothing</b> yet, and it <b>detaches</b> the others — an order whose ingredients are already in a machine is
 * left running on purpose, because those items are not coming back out and letting the order finish turns them into a
 * product the player keeps instead of a loss ({@code docs/warehouse-system.md} §3.5.6). Counting such a step as ending,
 * and its items as lost, is what the panel did before this class existed: in the ordinary mid-chain state — the root
 * blocked with nothing at a machine, one step working with a batch in front of it — it reported one order too many and a
 * loss that does not happen. That is the state a player is most likely to open the panel in, and the panel's one job is
 * to say what the click costs before it is irreversible.
 * <p>
 * <b>Why the client can work it out at all.</b> The exact set {@code failPlan} ends is the named order, its open
 * <b>ancestors</b>, and every other open order with nothing delivered. An open ancestor is an order with an open child,
 * and such an order fetches nothing at all ({@code ProductionOrders#hasOpenChildren}, and blockedness only ever ends —
 * a finished step never reopens), so it has always been handed nothing and is already in the third set. Ancestry
 * therefore drops out of the arithmetic, and what is left needs only each member's own {@code open} flag and delivered
 * count. For the same reason the whole loss is the named order's own delivered count.
 *
 * @param target    the order the click names: the line's head while it is open, otherwise the frontier — exactly what
 *                  {@code WarehouseTerminalScreen#cancelLine} sends, so the numbers belong to the click that is offered
 * @param endedOrders how many production orders the click would end, {@code target} included and therefore at least 1
 * @param lostIngredients ingredient items the click would abandon at a machine: {@code target}'s own delivered count
 */
public record PlanCancelCost(UUID target, int endedOrders, long lostIngredients) {
    public PlanCancelCost {
        Objects.requireNonNull(target, "target");
        endedOrders = Math.max(1, endedOrders);
        lostIngredients = Math.max(0L, lostIngredients);
    }

    /**
     * Whether this click would arm the <b>safety stop</b> for the item the named order makes, so the warehouse stops
     * making it until a player resumes it at the machine ({@code docs/warehouse-system.md} §3.5.4, ADR-027 widened by
     * ADR-032).
     * <p>
     * It is the same condition the server uses: an order that ends as anything but complete with ingredients already
     * handed over arms the stop ({@code ProductionOrder#endedWithLostIngredients}). Since M20 that is true of a player's
     * own click as well, which is why the panel has to say it — the cost of this click is not only the items, it is that
     * the next click for the same item is refused until somebody has looked at the machine.
     */
    public boolean armsSafetyStop() {
        return lostIngredients > 0L;
    }

    /**
     * The cost of giving up on {@code line}, or empty when the screen holds no open order the click could name — which
     * is exactly when the cancel affordance does nothing and the panel therefore says nothing about a cost.
     *
     * @param line    the line a click would give up on
     * @param members its members as the screen has them; ones the payload no longer carries are simply absent, so a
     *                payload that was cut short understates the count rather than inventing a member
     */
    public static Optional<PlanCancelCost> of(PlanLine line, Collection<PlanMember> members) {
        if (line == null || members == null)
            return Optional.empty();
        PlanMember target = openMember(line.head(), members)
                .or(() -> line.frontier().flatMap(frontier -> openMember(frontier, members)))
                .orElse(null);
        if (target == null)
            return Optional.empty();
        int ended = 1;
        for (PlanMember member : members) {
            if (member == null || member.id().equals(target.id()) || !line.members().contains(member.id()))
                continue;
            // Exactly failPlan's rule: an open order with nothing at a machine is cancelled, one with a batch in front
            // of it is detached and runs on. An open ancestor is always the first kind (see the class comment).
            if (member.open() && member.delivered() == 0L)
                ended++;
        }
        return Optional.of(new PlanCancelCost(target.id(), ended, target.delivered()));
    }

    /** The member {@code id} of {@code members}, but only while it is still open. */
    private static Optional<PlanMember> openMember(UUID id, Collection<PlanMember> members) {
        if (id == null)
            return Optional.empty();
        for (PlanMember member : members) {
            if (member != null && member.id().equals(id))
                return member.open() ? Optional.of(member) : Optional.empty();
        }
        return Optional.empty();
    }
}
