package dev.wareworks.core.terminal;

import java.util.Objects;

/**
 * The amounts a warehouse terminal reports for one item type: what the aisle stores and what a new request may still
 * claim ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * This is the pure form of the server's {@code content.station.TerminalStockEntry}, used by the screen's list model and
 * by the delta the server sends ({@link StockDiff}). {@code total <= 0} marks an entry that is <b>gone</b>: the client
 * removes it from its list.
 *
 * @param key       the item identity (the content layer uses {@code ItemKey})
 * @param total     what is stored over all storage locations of the aisle
 * @param available what a new request may still claim (never more than {@code total})
 * @param producible whether a production station of the aisle has a pattern that makes this item (M11, ADR-024). Such
 *                   an item is offered by the terminal even at zero stock, and ordering it starts a production order
 * @param producibleAmount how many of it the aisle could make <b>right now</b>, i.e. how many runs the ingredients
 *                   that are in stock and unpromised allow ({@code ProduciblePlanner}). It is computed on the
 *                   <b>server</b> and only reported here, because a screen knows neither the patterns nor what the
 *                   ingredients are promised to; it is what a "request everything possible" click may ask for on top
 *                   of {@code available}. 0 for an item nothing can make at the moment
 * @param <K>       item key type
 */
public record StockCount<K>(K key, long total, long available, boolean producible, long producibleAmount) {
    public StockCount {
        Objects.requireNonNull(key, "key");
        total = Math.max(0L, total);
        available = Math.max(0L, Math.min(available, total));
        producibleAmount = producible ? Math.max(0L, producibleAmount) : 0L;
    }

    /** An entry of an item that is simply in stock. */
    public StockCount(K key, long total, long available) {
        this(key, total, available, false, 0L);
    }

    /** An entry of an item the aisle can make, without saying how many of it it could make right now. */
    public StockCount(K key, long total, long available, boolean producible) {
        this(key, total, available, producible, 0L);
    }

    /** The marker for an item type that left the index: the client drops it from its list. */
    public static <K> StockCount<K> gone(K key) {
        return new StockCount<>(key, 0L, 0L, false, 0L);
    }

    /**
     * Whether this entry means "no longer in stock", i.e. the client drops its row.
     * <p>
     * A <b>producible</b> item at zero stock is deliberately not gone (M11, ADR-024): the aisle can make it, so the
     * terminal keeps offering it. That is what separates "there is none and never will be" from "there is none yet".
     */
    public boolean isGone() {
        return total <= 0L && !producible;
    }

    /** What is stored but already promised to a running job or an open request. */
    public long reserved() {
        return total - available;
    }

    /**
     * The largest amount one request may ask for right now: what is available plus what the aisle could still make of
     * it ({@link #producibleAmount()}). Both halves are the server's own numbers, so a screen never invents a bound.
     */
    public long orderable() {
        return available + producibleAmount;
    }
}
