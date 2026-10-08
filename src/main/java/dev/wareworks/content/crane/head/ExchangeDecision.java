package dev.wareworks.content.crane.head;

import java.util.Objects;

/**
 * What a handling head does with the two answers a {@link TransferContext#exchange container exchange} gives it, as a
 * pure function of numbers ({@code docs/stacker-crane.md} §6, M30, issue #21). {@link InventoryGrabber} does the
 * asking and the moving; this decides.
 * <p>
 * It is its own class for one reason: <b>this table is the conservation argument of the exchange</b>, and the
 * exchange is the only operation in this mod under which an item legitimately changes identity. Everything around it
 * needs a world — an item capability, a fluid capability, a block entity — so none of it can be unit tested; the
 * decision itself needs none and therefore is ({@code CranePauseDecision} is the same split, for the same reason).
 *
 * <h2>The three gates, in the order the head passes them</h2>
 * <ol>
 * <li>{@link #mayAsk} — before anything is even asked, because a simulated exchange still costs a capability lookup
 * and a probe stack.</li>
 * <li>{@link #planAgrees} — on the simulated answer, while nothing anywhere has moved yet. <b>This is where the
 * all-or-nothing rule is enforced</b>, and it is the only place it can be enforced for free.</li>
 * <li>{@link #judge} — on the real answer, when the fluid has already left the containers. From here on nothing may
 * be "refused": the only choices are to complete the swap or to salvage it, and both of them conserve.</li>
 * </ol>
 */
final class ExchangeDecision {
    private ExchangeDecision() {
    }

    /** What the head does once the exchange has really happened at the location. */
    enum Action {
        /**
         * Nothing moved anywhere: the head stays as it was and the caller is told 0. Only reachable while the
         * location still reports that it did nothing.
         */
        REFUSE,
        /**
         * The swap the head asked for: remove the given-up containers, hold that many of the received item, report
         * them. The only outcome a correct location ever produces.
         */
        EXCHANGE,
        /**
         * The location did something other than what it had just said it would. The given-up containers are gone, so
         * they are removed from the head — but the received ones are <b>spilled at the location</b> rather than held,
         * because holding them would leave the head with two keys and the crane would throw one of them on the ground
         * at the dock instead. Items are conserved, the head keeps exactly one key, and the caller is told 0 so that
         * the job follows the head rather than the other way round.
         */
        SALVAGE
    }

    /**
     * What to do, and with how many containers.
     *
     * @param action what the head does
     * @param containers how many containers it moves; 0 exactly for {@link Action#REFUSE}
     */
    record Outcome(Action action, int containers) {
        /** The one outcome under which the head is untouched. */
        static final Outcome REFUSED = new Outcome(Action.REFUSE, 0);

        Outcome {
            Objects.requireNonNull(action, "action");
            if (containers < 0)
                throw new IllegalArgumentException("containers must not be negative: " + containers);
            if ((action == Action.REFUSE) != (containers == 0))
                throw new IllegalArgumentException("exactly a refusal moves no container: " + action + " " + containers);
        }
    }

    /**
     * Whether the head may ask a location to simulate this exchange at all.
     * <p>
     * It must really hold the containers it offers ({@code heldOfFrom >= requested}): offering containers it does not
     * have is how a location would be made to take fluid out of nothing. And the two keys must differ — an "exchange"
     * of a key for itself would have the head remove and re-add the same items while the location kept the fluid,
     * i.e. fluid from nothing, which is the one way this primitive could create rather than destroy.
     *
     * @param requested    how many containers the caller wants exchanged
     * @param heldOfFrom   how many of the given-up key the head really holds
     * @param distinctKeys whether the item received differs from the item given up
     */
    static boolean mayAsk(int requested, int heldOfFrom, boolean distinctKeys) {
        return requested > 0 && heldOfFrom >= requested && distinctKeys;
    }

    /**
     * Whether a simulated answer is the exchange the head asked for, which is the <b>all-or-nothing</b> rule: a plan
     * for fewer containers than asked, or for a different resulting item than the caller named, is refused here —
     * while nothing anywhere has moved — rather than applied as a partial swap that would leave two keys in the head.
     *
     * @param requested               how many containers the caller wants exchanged
     * @param plannedContainers       how many the location says it would exchange
     * @param plannedResultIsExpected whether the resulting item it names is the one the caller named
     */
    static boolean planAgrees(int requested, int plannedContainers, boolean plannedResultIsExpected) {
        return requested > 0 && plannedContainers == requested && plannedResultIsExpected;
    }

    /**
     * What to do with what a location <b>really</b> answered, after the fluid has already moved.
     * <p>
     * {@code reportedContainers} is clamped to what the head actually holds, because the head can only give up
     * containers it has: a location claiming more than that has taken fluid for containers which never existed, and
     * no bookkeeping here can repair it — the joint census is what reports it ({@code FluidCensus.assertConserved}).
     * Clamping at least keeps the head's own numbers honest instead of letting them go negative.
     *
     * @param requested          how many containers the caller asked for and the plan had agreed to
     * @param heldOfFrom         how many of the given-up key the head really holds right now
     * @param reportedContainers how many containers the real call says it exchanged
     * @param resultIsExpected   whether the item it really hands back is the one the caller named
     */
    static Outcome judge(int requested, int heldOfFrom, int reportedContainers, boolean resultIsExpected) {
        int moved = Math.min(Math.max(0, reportedContainers), Math.max(0, heldOfFrom));
        if (moved < 1)
            return Outcome.REFUSED;
        if (moved == requested && reportedContainers == requested && resultIsExpected)
            return new Outcome(Action.EXCHANGE, moved);
        return new Outcome(Action.SALVAGE, moved);
    }
}
