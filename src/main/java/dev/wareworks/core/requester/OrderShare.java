package dev.wareworks.core.requester;

/**
 * How much of a merged request belongs to one Schematicannon Requester order (issue #45, ADR-080): <b>first in, first
 * served</b>. Pure arithmetic.
 * <p>
 * Repeated requests for one item and destination merge into one request that keeps its id (ADR-020), so a requester's
 * order can share an id with a player's earlier click at the same terminal, with a list order, or with another
 * requester's order. The crane serves the merged request in the order the amounts were added, so the requester's share
 * is credited only once everything that was in the request before it — {@code ahead}, recorded when the order was
 * granted as {@code requested - granted} — has been delivered:
 * <pre>
 * ownDelivered = clamp(delivered - ahead, 0, share)
 * </pre>
 * Crediting the requester first would call its share done when a player's earlier click arrived, and after the grace
 * the requester would order again while its own share still waits in the queue: exactly the duplicate it must not make.
 * <p>
 * Somebody ahead may give their part back ({@code reduceRequestShare}: a list order given up, another requester's
 * dead-man switch). When the request then asks for less than {@code ahead + share}, the reduction came from ahead of
 * this share, and {@code ahead} shrinks to {@code max(0, requested - share)} first ({@link #ahead}). A reduction from
 * behind it changes nothing. A reduction ahead that is no larger than what was added behind cannot be told apart from
 * one behind (a player's click merged in behind, then a production shortfall on a click ahead): for the credit that
 * only delays it until the request is gone, so {@code ahead} stays an upper bound and the share is never called done
 * early.
 * <p>
 * <b>Giving back is the other way round.</b> The dead-man switch and the give-back take an amount off the merged
 * request ({@code reduceRequestShare}), and whatever is taken off beyond the share's own undelivered part comes off
 * somebody else's — a player's click merged in behind would get fewer items. So what is given back is only what is
 * provably the share's own ({@link #returnable}): the outstanding part counted as if everything ahead had been given
 * back, except {@code aheadDelivered}, what of the request had already arrived when the share was granted, which no
 * reduction can ever take off. Where the two differ the error is towards delivering too much into the buffer, never
 * towards taking anything off a player.
 * <p>
 * <b>A request that is gone counts as done</b>: delivered and cancelled cannot be told apart from outside, and the
 * buffer and grace checks that follow cover both.
 */
public final class OrderShare {
    private OrderShare() {
    }

    /**
     * What is ahead of a share of {@code share} items in a request that asks for {@code requested} items in all now,
     * when {@code ahead} items were ahead of it when it was granted.
     */
    public static int ahead(int requested, int ahead, int share) {
        int before = Math.max(0, ahead);
        int own = Math.max(0, share);
        if ((long) requested < (long) before + own)
            return Math.max(0, requested - own);
        return before;
    }

    /**
     * How many of a share's {@code share} items were delivered when the request asks for {@code requested} and has
     * delivered {@code delivered} in all, with {@code ahead} items ahead of the share ({@link #ahead} applied).
     */
    public static int ownDelivered(int requested, int delivered, int ahead, int share) {
        int before = ahead(requested, ahead, share);
        long own = (long) Math.max(0, delivered) - before;
        return (int) Math.max(0, Math.min(Math.max(0, share), own));
    }

    /** How many of the share are still to come: {@code share - ownDelivered}. */
    public static int outstanding(int requested, int delivered, int ahead, int share) {
        return Math.max(0, share) - ownDelivered(requested, delivered, ahead, share);
    }

    /**
     * How much of the share may be given back without taking anything off anybody else in the request: the share
     * minus what of it may have arrived if everything ahead of it but {@code aheadDelivered} had been given back in the
     * meantime, and never more than {@link #outstanding}. {@code aheadDelivered} is what of the request had been
     * delivered when the share was granted; delivered items are never taken off a request, so they stay ahead of it.
     */
    public static int returnable(int requested, int delivered, int ahead, int aheadDelivered, int share) {
        int own = Math.max(0, share);
        int floor = Math.max(0, Math.min(aheadDelivered, ahead(requested, ahead, share)));
        long mayHaveArrived = Math.max(0L, Math.min(own, (long) Math.max(0, delivered) - floor));
        return (int) Math.min(outstanding(requested, delivered, ahead, share), own - mayHaveArrived);
    }

    /** Whether the share is complete: every item of it was delivered. */
    public static boolean done(int requested, int delivered, int ahead, int share) {
        return outstanding(requested, delivered, ahead, share) == 0;
    }
}
