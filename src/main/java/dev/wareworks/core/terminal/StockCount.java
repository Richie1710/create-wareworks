package dev.wareworks.core.terminal;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;

/**
 * The amounts a warehouse terminal reports for one item type: what the aisle stores, what a new request may still claim
 * and what a stock rule says about it ({@code docs/warehouse-system.md} §3.4.2, §3.6).
 * <p>
 * This is the pure form of the server's {@code content.station.TerminalStockEntry}, used by the screen's list model and
 * by the delta the server sends ({@link StockDiff}). An entry is <b>gone</b> — the client removes its row — only when
 * nothing is stored, nothing can be produced and no rule governs the item: all three are reasons to keep offering a row
 * at zero stock.
 * <p>
 * <b>What "available" means here is a player's number.</b> A rule's reserve holds items back from the warehouse's own
 * automation, never from the player standing at the terminal ({@code core.stock.StockAccess}), so
 * {@link #ruleReserved()} is a <b>part of</b> {@link #available()} and not a deduction from it. The parts a row shows
 * therefore still add up: {@code total = available + promised}, with {@code ruleReserved} naming how much of the
 * available amount a redstone request would already be refused.
 *
 * @param key       the item identity (the content layer uses {@code ItemKey})
 * @param total     what is stored over all storage locations of the aisle
 * @param available what a new request <b>of this player</b> may still claim (never more than {@code total})
 * @param producible whether a production station of the aisle has a pattern that makes this item (M11, ADR-024). Such
 *                   an item is offered by the terminal even at zero stock, and ordering it starts a production order
 * @param producibleAmount how many of it the aisle could make <b>right now</b>, i.e. how many runs the ingredients
 *                   that are in stock and unpromised allow ({@code ProduciblePlanner}). It is computed on the
 *                   <b>server</b> and only reported here, because a screen knows neither the patterns nor what the
 *                   ingredients are promised to; it is what a "request everything possible" click may ask for on top
 *                   of {@code available}. 0 for an item nothing can make at the moment
 * @param rule      what the stock rule that governs this item is doing (M15, issue #3), empty when none governs it.
 *                  A shadowed or inert rule is never reported: it applies nothing, and a row saying otherwise would
 *                  claim a cap that does not exist
 * @param ruleReserved how many of the {@code available} items the rule's reserve holds back from automation. A player
 *                  may still take them and the row says so; 0 without a rule or without a reserve
 * @param ruleMaximum the most of this item the rule lets the warehouse store, or {@link StockRule#UNSET} for no cap
 *                  (M15 part 2). It travels because it is the one number a screen needs to say, <b>before</b> a click,
 *                  that an order would bring in more than the warehouse wants to hold
 * @param <K>       item key type
 */
public record StockCount<K>(K key, long total, long available, boolean producible, long producibleAmount,
                            Optional<StockRuleStatus> rule, long ruleReserved, long ruleMaximum) {
    public StockCount {
        Objects.requireNonNull(key, "key");
        total = Math.max(0L, total);
        available = Math.max(0L, Math.min(available, total));
        producibleAmount = producible ? Math.max(0L, producibleAmount) : 0L;
        if (rule == null)
            rule = Optional.empty();
        // Never more than what is there: what is not available at all is not "held back", it is simply missing.
        ruleReserved = rule.isPresent() ? Math.max(0L, Math.min(ruleReserved, available)) : 0L;
        ruleMaximum = rule.isPresent() && ruleMaximum >= 0L ? Math.min(ruleMaximum, StockRule.MAX_AMOUNT)
                : StockRule.UNSET;
    }

    /** An entry of an item that is simply in stock. */
    public StockCount(K key, long total, long available) {
        this(key, total, available, false, 0L, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** An entry of an item the aisle can make, without saying how many of it it could make right now. */
    public StockCount(K key, long total, long available, boolean producible) {
        this(key, total, available, producible, 0L, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** An entry no stock rule governs, which is every entry of a warehouse without stock keepers. */
    public StockCount(K key, long total, long available, boolean producible, long producibleAmount) {
        this(key, total, available, producible, producibleAmount, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** An entry of a ruled item without a storage cap. */
    public StockCount(K key, long total, long available, boolean producible, long producibleAmount,
            Optional<StockRuleStatus> rule, long ruleReserved) {
        this(key, total, available, producible, producibleAmount, rule, ruleReserved, StockRule.UNSET);
    }

    /** The marker for an item type that left the index: the client drops it from its list. */
    public static <K> StockCount<K> gone(K key) {
        return new StockCount<>(key, 0L, 0L, false, 0L, Optional.empty(), 0L, StockRule.UNSET);
    }

    /**
     * Whether this entry means "no longer worth a row", i.e. the client drops it.
     * <p>
     * Three things keep a row alive at zero stock, and each of them would otherwise make an item unreachable: the
     * aisle can <b>produce</b> it (M11, ADR-024), or a stock <b>rule</b> governs it (M15) — a player has to be able to
     * see that the warehouse is calling for an item it has none of, and an item whose row vanished can never be
     * requested again.
     */
    public boolean isGone() {
        return total <= 0L && !producible && rule.isEmpty();
    }

    /** Whether a stock rule governs this item, so its row is kept and its badge is drawn. */
    public boolean ruled() {
        return rule.isPresent();
    }

    /** What is stored but already promised to a running job or an open request. */
    public long reserved() {
        return total - available;
    }

    /**
     * What the warehouse's own automation — a redstone request at an output — could still be promised:
     * {@code available − ruleReserved}. A player is not bounded by it, which is exactly what the row has to make
     * visible.
     */
    public long availableToAutomation() {
        return Math.max(0L, available - ruleReserved);
    }

    /**
     * How many items of a request for {@code amount} would come out of the reserve, i.e. how far below the reserve a
     * click goes. 0 while the request fits above it, and never more than {@link #ruleReserved()}.
     */
    public long fromReserve(long amount) {
        return Math.min(Math.max(0L, Math.max(0L, amount) - availableToAutomation()), ruleReserved);
    }

    // What a request would leave above the maximum is deliberately NOT computed here (M15 review fix). It is the
    // whole-run surplus a pattern makes and nobody asked for, and the size of a run is something only the server knows
    // — the same reason producibleAmount() is a number the server sends rather than one a screen derives. A client
    // estimate of it named a level the racks only pass through, which the same request undoes; the server's question
    // names what stays (core.terminal.RequestConfirmation#pastMaximum). The cap itself is reported, so a row can still
    // show it.

    /**
     * The largest amount one request may ask for right now: what is available plus what the aisle could still make of
     * it ({@link #producibleAmount()}). Both halves are the server's own numbers, so a screen never invents a bound.
     */
    public long orderable() {
        return available + producibleAmount;
    }
}
