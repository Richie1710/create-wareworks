package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What a warehouse terminal has to send to one open screen: the difference between the stock it last sent and the stock
 * it holds now ({@code docs/warehouse-system.md} §3.4.2, ADR-019).
 * <p>
 * A terminal sends the full list once when a screen opens and afterwards only entries whose amounts changed, plus the
 * ones that left the index as {@link StockCount#gone}. A warehouse in which nothing moves therefore costs no packet at
 * all, and a busy one costs a handful of entries per refresh instead of the whole list.
 * <p>
 * <b>A reported list may be shorter than the index</b> ({@code maxTerminalStockEntries}), and "not in this snapshot"
 * then means one of two very different things: the item type left the index, or it only fell outside the reported
 * window. Only the first may be sent as {@code gone}, because the client deletes such a row — a stocked item would
 * vanish from the screen and could no longer be requested. The caller therefore passes a
 * {@code stillStocked} predicate; a key it accepts keeps its last reported amounts and is not sent again.
 * <p>
 * Server thread only; one instance per open menu.
 *
 * @param <K> item key type
 */
public final class StockDiff<K> {
    /** Default for the callers that report a complete list: a key that vanished really left the index. */
    private static final Predicate<Object> NOTHING_OUTSIDE_THE_WINDOW = key -> false;

    private final Map<K, StockCount<K>> sent = new HashMap<>();

    /**
     * The entries that differ from what was last {@link #commit committed}, without changing the remembered state:
     * new and changed entries as they are, and every key that vanished as {@link StockCount#gone}.
     */
    public List<StockCount<K>> changes(Collection<StockCount<K>> current) {
        return changes(current, NOTHING_OUTSIDE_THE_WINDOW);
    }

    /**
     * {@link #changes(Collection)} for a {@code current} that may be a cut-off view of the index.
     *
     * @param stillStocked whether a key that is missing from {@code current} is nevertheless still in stock, i.e. was
     *                     only cut off; such a key is not reported as {@link StockCount#gone}
     */
    public List<StockCount<K>> changes(Collection<StockCount<K>> current, Predicate<? super K> stillStocked) {
        return diff(current, stillStocked, new HashMap<>());
    }

    /** {@link #changes} plus remembering {@code current} as the new state. */
    public List<StockCount<K>> commit(Collection<StockCount<K>> current) {
        return commit(current, NOTHING_OUTSIDE_THE_WINDOW);
    }

    /**
     * {@link #changes(Collection, Predicate)} plus remembering the new state. A key that was only cut off keeps the
     * amounts that were last sent for it, so the screen goes on showing the row it already has.
     */
    public List<StockCount<K>> commit(Collection<StockCount<K>> current, Predicate<? super K> stillStocked) {
        Map<K, StockCount<K>> next = new HashMap<>();
        List<StockCount<K>> changes = diff(current, stillStocked, next);
        sent.clear();
        sent.putAll(next);
        return changes;
    }

    /** Fills {@code next} with the state after this diff and returns what has to be sent for it. */
    private List<StockCount<K>> diff(Collection<StockCount<K>> current, Predicate<? super K> stillStocked,
            Map<K, StockCount<K>> next) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(stillStocked, "stillStocked");
        List<StockCount<K>> changes = new ArrayList<>();
        Map<K, StockCount<K>> missing = new HashMap<>(sent);
        for (StockCount<K> entry : current) {
            StockCount<K> before = missing.remove(entry.key());
            if (!entry.equals(before))
                changes.add(entry);
            if (!entry.isGone())
                next.put(entry.key(), entry);
        }
        for (Map.Entry<K, StockCount<K>> dropped : missing.entrySet()) {
            if (stillStocked.test(dropped.getKey()))
                next.put(dropped.getKey(), dropped.getValue()); // only outside the window: leave the screen's row alone
            else
                changes.add(StockCount.gone(dropped.getKey()));
        }
        return List.copyOf(changes);
    }

    /** Forgets everything, so the next {@link #commit} reports the whole list again (a screen opened or re-synced). */
    public void reset() {
        sent.clear();
    }

    /** Whether nothing has been sent yet. */
    public boolean isEmpty() {
        return sent.isEmpty();
    }

    /** Number of item types the screen currently knows about. */
    public int size() {
        return sent.size();
    }
}
