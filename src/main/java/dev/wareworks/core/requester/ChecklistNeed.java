package dev.wareworks.core.requester;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the schematic still needs of an item, as far as one Schematicannon Requester can tell (issue #45, ADR-080): the
 * cannon's material list minus what this requester already ordered since the list was made. One per requester; pure
 * Java, not thread-safe (server thread only).
 * <p>
 * <b>The list.</b> Create's {@code MaterialChecklist} holds what the schematic {@code required} of each item and what
 * the cannon's inventories held ({@code gathered}) when it was last made. Create remakes it when the printer starts
 * (also after every reload) and when it prints a book or clipboard mid-run; there is no count of what was placed since.
 * So the requester counts what it ordered itself ({@link #add}, {@link #subtract}), per item and for at most
 * {@value RequesterLimits#MAX_COUNTED_ITEMS} items; an item past that cap counts as unknown to the list, which means one
 * stack.
 * <p>
 * <b>Rebase.</b> Every decision passes the list's {@link Fingerprint} (sum of required, sum of gathered, blocks to
 * place). When it differs from the one the counter was made against, the list is fresh: what reached the chest is in
 * {@code gathered} now, and the counter restarts from the shares of this requester's own live orders, which are not in
 * it yet (in the buffer, in the crane, on the belt). The error is at most one share, towards ordering too little, never
 * overflowing the chest — and the fallback repairs that.
 * <p>
 * <b>Fallback.</b> When the counter says nothing is left while the cannon still misses the item (the player took items
 * out of the chest, the warehouse shortened a request), the counter is stale. At most once per
 * {@code requester.checklistRefreshTicks} the requester then asks the cannon for a fresh list (the same call Create makes
 * for a book print mid-run), rebases and computes again; otherwise, or when that still says nothing, it orders what the
 * cannon reports missing. So the requester never stalls, and the extra schematic pass is bounded.
 *
 * @param <I> item type (the counter is per item, not per variant)
 */
public final class ChecklistNeed<I> {
    /** {@link #lastRefresh()} before the first refresh. */
    public static final long NEVER = Long.MIN_VALUE;

    /**
     * What identifies one state of the cannon's material list, O(distinct items) to compute.
     *
     * @param required      the sum of every item's required count
     * @param gathered      the sum of every item's gathered count
     * @param blocksToPlace the cannon's blocks to place, which the list's refresh also recomputes
     */
    public record Fingerprint(long required, long gathered, long blocksToPlace) {
    }

    /**
     * What to order of a missing item at most, and how much of that production may make (ADR-081, rule R2: never more
     * is ordered made than the schematic still needs; whole runs may leave a surplus in stock).
     *
     * @param amount  what to order at most, at least 1 ({@link #need})
     * @param makeCap what of it may be made, {@code 1..amount}: all of it when the amount is the material list's
     *                remainder, otherwise (one stack for an item the list or the counter does not know, the one of a
     *                requirement that only damages its item, the stale-counter fallback) at most what the cannon
     *                reports missing
     */
    public record Need(int amount, int makeCap) {
        public Need {
            if (amount < 1)
                throw new IllegalArgumentException("amount must be at least 1: " + amount);
            if (makeCap < 1 || makeCap > amount)
                throw new IllegalArgumentException("makeCap must be within 1.." + amount + ": " + makeCap);
        }

        /** The list's remainder: all of it may be made. */
        static Need remainder(int amount) {
            return new Need(amount, amount);
        }

        /** An amount that is not the list's remainder: at most what the cannon reports missing may be made. */
        static Need guessed(int amount, int missing) {
            return new Need(amount, Math.min(amount, Math.max(1, missing)));
        }
    }

    /**
     * A read of the cannon's material list.
     *
     * @param <I> item type
     */
    public interface Checklist<I> {
        /** What the schematic requires of {@code item} in all; {@code 0} when it is not on the list. */
        long required(I item);

        /** What the list found of {@code item} in the cannon's inventories. */
        long gathered(I item);

        /** This state of the list. */
        Fingerprint fingerprint();
    }

    private final Map<I, Integer> ordered = new LinkedHashMap<>();
    private Fingerprint fingerprint;
    private long lastRefresh = NEVER;

    /** What this requester ordered of {@code item} since the list was made. */
    public int ordered(I item) {
        Integer count = ordered.get(item);
        return count == null ? 0 : count;
    }

    /** Whether {@code item} is counted: it already is, or the counter still has room for it. */
    public boolean counts(I item) {
        return ordered.containsKey(item) || ordered.size() < RequesterLimits.MAX_COUNTED_ITEMS;
    }

    /** Counts {@code amount} more items of {@code item} as ordered (a granted share); ignored past the cap. */
    public void add(I item, int amount) {
        Objects.requireNonNull(item, "item");
        if (amount < 1 || !counts(item))
            return;
        ordered.merge(item, amount, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    /** Counts {@code amount} items of {@code item} as no longer ordered (given up or given back), floored at 0. */
    public void subtract(I item, int amount) {
        Objects.requireNonNull(item, "item");
        Integer count = ordered.get(item);
        if (amount < 1 || count == null)
            return;
        int left = count - amount;
        if (left > 0)
            ordered.put(item, left);
        else
            ordered.remove(item);
    }

    /**
     * Rebases the counter onto {@code liveShares} when {@code current} differs from the fingerprint the counter was made
     * against (see the class comment).
     *
     * @param liveShares the sum of the shares of this requester's live orders, per item
     * @return whether the counter was rebased
     */
    public boolean observe(Fingerprint current, Map<I, Integer> liveShares) {
        Objects.requireNonNull(current, "current");
        if (current.equals(fingerprint))
            return false;
        rebase(current, liveShares);
        return true;
    }

    /**
     * What to order of the missing {@code item} at most (before the stack, room and stock caps of {@link OrderAmount}),
     * always at least 1: the list's remainder ({@link OrderAmount#need}); one stack for an item the list or the counter
     * does not know; one for a requirement that only damages its item; and for a stale counter the fallback of the class
     * comment, which calls {@code refresh} at most once per {@code refreshTicks}.
     *
     * @param item         the missing item
     * @param stack        its stack size
     * @param missing      how many the cannon reports missing
     * @param damage       whether the requirement only damages the item
     * @param list         the cannon's material list as it is
     * @param liveShares   the shares of this requester's live orders per item, for a rebase
     * @param now          the game time
     * @param refreshTicks the shortest time between two refreshes (clamped, {@link RequesterLimits#clampRefresh})
     * @param refresh      asks the cannon to remake its list and returns it; called at most once
     */
    public int need(I item, int stack, int missing, boolean damage, Checklist<I> list, Map<I, Integer> liveShares,
            long now, int refreshTicks, Supplier<? extends Checklist<I>> refresh) {
        return needOf(item, stack, missing, damage, list, liveShares, now, refreshTicks, refresh).amount();
    }

    /**
     * {@link #need}, with how much of it production may make (ADR-081, {@link Need#makeCap()}): all of it when the
     * amount is the material list's remainder, on the current list or a refreshed one, and otherwise at most what the
     * cannon reports missing. Stock keeps the old rule (one stack for an item the list does not show): it costs no
     * ingredients.
     */
    public Need needOf(I item, int stack, int missing, boolean damage, Checklist<I> list, Map<I, Integer> liveShares,
            long now, int refreshTicks, Supplier<? extends Checklist<I>> refresh) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(liveShares, "liveShares");
        Objects.requireNonNull(refresh, "refresh");
        int floor = Math.max(1, missing);
        observe(list.fingerprint(), liveShares);
        if (damage)
            return Need.guessed(1, floor);
        Need need = needFrom(item, stack, floor, list);
        if (need != null)
            return need;
        if (mayRefresh(now, refreshTicks)) {
            lastRefresh = now;
            Checklist<I> fresh = Objects.requireNonNull(refresh.get(), "refresh returned null");
            rebase(fresh.fingerprint(), liveShares);
            need = needFrom(item, stack, floor, fresh);
            if (need != null)
                return need;
        }
        return Need.guessed(floor, floor);
    }

    /** Whether a refresh at {@code now} is allowed: never refreshed, the pause is over, or the clock went backwards. */
    public boolean mayRefresh(long now, int refreshTicks) {
        if (lastRefresh == NEVER || now < lastRefresh)
            return true;
        return now - lastRefresh >= RequesterLimits.clampRefresh(refreshTicks);
    }

    /** The game time of the last refresh, or {@link #NEVER}. */
    public long lastRefresh() {
        return lastRefresh;
    }

    /** The fingerprint the counter was made against, empty before the first decision. */
    public Optional<Fingerprint> fingerprint() {
        return Optional.ofNullable(fingerprint);
    }

    /** The counter, per item (unmodifiable copy), for the save. */
    public Map<I, Integer> counts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /**
     * Restores a saved state; invalid counts are skipped and at most {@value RequesterLimits#MAX_COUNTED_ITEMS} items are
     * kept.
     */
    public void restore(Map<I, Integer> counts, Optional<Fingerprint> savedFingerprint, long savedLastRefresh) {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(savedFingerprint, "savedFingerprint");
        ordered.clear();
        putAll(counts);
        fingerprint = savedFingerprint.orElse(null);
        lastRefresh = savedLastRefresh;
    }

    /** Forgets everything: the remote was taken out, or the requester starts afresh. */
    public void clear() {
        ordered.clear();
        fingerprint = null;
        lastRefresh = NEVER;
    }

    /** The need by {@code list}, or {@code null} when the counter is stale (the list says nothing is left). */
    private Need needFrom(I item, int stack, int floor, Checklist<I> list) {
        if (!counts(item))
            return Need.guessed(Math.max(1, stack), floor);
        long required = list.required(item);
        int need = OrderAmount.need(stack, floor, required, list.gathered(item), ordered(item), false);
        if (need <= 0)
            return null;
        // Not on the list: one stack from stock, but production makes no more than the cannon reports missing.
        return required <= 0 ? Need.guessed(need, floor) : Need.remainder(need);
    }

    private void rebase(Fingerprint current, Map<I, Integer> liveShares) {
        fingerprint = Objects.requireNonNull(current, "current");
        ordered.clear();
        putAll(liveShares);
    }

    private void putAll(Map<I, Integer> counts) {
        for (Map.Entry<I, Integer> entry : counts.entrySet()) {
            if (ordered.size() >= RequesterLimits.MAX_COUNTED_ITEMS)
                break;
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                ordered.put(entry.getKey(), entry.getValue());
        }
    }
}
