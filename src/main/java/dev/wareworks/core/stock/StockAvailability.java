package dev.wareworks.core.stock;

import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * How much of an item a taker may still be promised, once stock rules exist (M15, issue #3): the warehouse's own
 * availability with the reserve of a governing rule taken off for {@link StockAccess#AUTOMATION} and left alone for a
 * {@link StockAccess#PLAYER}.
 * <p>
 * This is the one place a reserve enters the request path. It is the function the controller hands to
 * {@code RequestQueue#add}, which keeps three existing guarantees intact:
 * <ul>
 *   <li><b>The merged total is what is clamped</b> (ADR-020). The availability it wraps already subtracts what the
 *       open requests promise, so growing an open request is bounded by the same number as a new one: ten clicks can
 *       never promise away a reserve that one click could not.</li>
 *   <li><b>Every key goes through it</b>, not only the requested one, so a merge cannot slip past the reserve on a
 *       key the caller did not think about.</li>
 *   <li><b>Nothing is reserved by reserving</b>: this is a constant subtracted at the entry point, not an entry in
 *       the reservation ledger, so it cannot interact with the double-subtraction rule of {@code §7.4} and it never
 *       claws back a request that was already accepted.</li>
 * </ul>
 * <b>Who may take the reserve</b> is the user's decision, and it is the inverse of the first design study: automation
 * stops at it, a player does not ({@link StockAccess}). A player's request is served down to the last item and the
 * terminal row says that it goes below the reserve ({@link StockRules#fromReserve}); a redstone-triggered request at a
 * warehouse output is refused there and told why, rather than silently emptying a buffer overnight.
 * <p>
 * Pure Java with no Minecraft types. Without a governing rule every answer is the unchanged input, which is what keeps
 * a warehouse from before M15 behaving exactly as it did.
 */
public final class StockAvailability {
    private StockAvailability() {
    }

    /**
     * What {@code access} may still claim of every key: {@code availableStock}, minus the reserve of a governing rule
     * for {@link StockAccess#AUTOMATION}. Never negative.
     *
     * @param rules          the aisle's rules (the controller's copy, never a keeper's block entity)
     * @param access         who is asking
     * @param availableStock what a new request may claim before any reserve, per key (the controller's
     *                       {@code availableStock}); negative answers count as 0
     */
    public static <K> ToLongFunction<K> of(StockRules<K> rules, StockAccess access,
            ToLongFunction<? super K> availableStock) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(availableStock, "availableStock");
        return key -> rules.availableTo(access, key, availableStock.applyAsLong(key));
    }

    /**
     * {@link #of} with what the aisle could still <b>make</b> of the requested item added on top of it, which is what
     * one retrieval request is clamped with: a request may ask for both the stock it can have now and the items a
     * production pattern would make for it.
     * <p>
     * The producible amount is added for {@code requested} alone, and the reserve is <b>not</b> taken off it: a
     * reserve holds back items that are lying in the racks, and items a pattern has yet to make are not there at all.
     * What that production would <b>spend</b> is held back by the very same reserve, one step earlier: the caller
     * measures the producible amount against {@link #of} for the same {@link StockAccess}, so the ingredients of an
     * order are only ever counted above their own reserve and {@code producible} can never promise an automation
     * request items that a rule holds back — reached through a pattern instead of directly.
     *
     * @param requested   the requested key; every other key is answered by {@link #of}
     * @param producible  how many items of {@code requested} the aisle could produce right now (negative counts as 0)
     */
    public static <K> ToLongFunction<K> forRequest(StockRules<K> rules, StockAccess access,
            ToLongFunction<? super K> availableStock, K requested, long producible) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(availableStock, "availableStock");
        Objects.requireNonNull(requested, "requested");
        long extra = Math.max(0L, producible);
        return key -> {
            long claimable = rules.availableTo(access, key, availableStock.applyAsLong(key));
            return requested.equals(key) ? StockLevels.sum(claimable, extra) : claimable;
        };
    }
}
