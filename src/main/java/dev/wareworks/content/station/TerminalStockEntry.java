package dev.wareworks.content.station;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;

/**
 * One line of a warehouse terminal's stock snapshot ({@code docs/warehouse-system.md} §3.4): an item type of the aisle
 * with the amount that is stored and the amount a new request may still claim.
 * <p>
 * The snapshot is built on demand on the server ({@link WarehouseTerminalBlockEntity#stockSnapshot()}) and is the only
 * list of items a terminal screen may offer, so a client can never request something the server does not hold.
 *
 * @param key       the exact item identity (item and data components)
 * @param total     what the controller's stock index counts over all storage locations of the aisle
 * @param available what a new request may still claim: {@code total} minus reservations and the remaining amounts of the
 *                  open requests for this item ({@code WarehouseControllerBlockEntity#availableStock})
 * @param producible whether a production station of this aisle has a pattern that makes the item (M11, ADR-024). Such
 *                   an item appears in the snapshot <b>even at zero stock</b>, which is how a terminal offers
 *                   something the warehouse could make but does not have
 * @param producibleAmount how many of it the aisle could make <b>right now</b>, from ingredients that are in stock and
 *                   not promised to anything else ({@code WarehouseControllerBlockEntity#producibleAmounts}). This is
 *                   what a "request everything possible" click may ask for on top of {@code available}, and it is
 *                   computed here rather than on the screen: a client knows neither the patterns nor the promises
 * @param rule       what the stock rule governing this item is doing (M15, issue #3), empty when no rule governs it.
 *                   Only a <b>governing</b> rule is reported: a shadowed or inert one applies nothing, and a row that
 *                   claimed otherwise would tell a player their stock is capped when it is not
 * @param ruleReserved how many of the {@code available} items the rule's reserve holds back from the warehouse's own
 *                   automation. It is a <b>part of</b> {@code available}, not a deduction from it: a player at this
 *                   terminal may still take them and the row tells them that they are going below the reserve
 *                   ({@code core.stock.StockAccess}, the user's decision for M15)
 * @param ruleMaximum the most of this item the rule lets the warehouse store, or {@code StockRule.UNSET} for no cap
 *                   (M15 part 2). The screen needs it to say, before a click, that an order would bring in more than
 *                   the warehouse wants to hold
 */
public record TerminalStockEntry(ItemKey key, long total, long available, boolean producible, long producibleAmount,
                                 Optional<StockRuleStatus> rule, long ruleReserved, long ruleMaximum) {
    /**
     * Stable order of a snapshot, independent of the client's language: the largest stock first, then the most
     * available, then {@link ItemKey#ORDER} (item id, then the key's text). The same warehouse always produces the same
     * list, and two items that differ only in their components keep a fixed order.
     * <p>
     * <b>Why the stored amount comes first.</b> A snapshot is cut off at {@code maxTerminalStockEntries}, so the
     * leading key decides which item types a screen gets to see at all. {@code available} moves as soon as anything is
     * promised to a request, which would push stocked items in and out of that window while the crane works; the stored
     * amount only moves when items really arrive or leave. The screen orders what it shows by its own
     * {@code TerminalSort} anyway, so this order is about the <b>cut</b>, not about the display.
     * <p>
     * The last tie-break is deliberately <b>not</b> {@link ItemKey#hashCode()}: that hash mixes in {@code Item}'s
     * identity hash, which differs after every restart, so it would swap two equally stocked keys of the same item
     * between launches (M14 review fix). {@link ItemKey#ORDER} compares values instead, and renders the component
     * patch only for two entries that already agree on amounts and item id.
     */
    public static final Comparator<TerminalStockEntry> ORDER = Comparator
            .comparingLong((TerminalStockEntry entry) -> -entry.total())
            .thenComparingLong(entry -> -entry.available())
            .thenComparing(TerminalStockEntry::key, ItemKey.ORDER);

    public TerminalStockEntry {
        Objects.requireNonNull(key, "key");
        total = Math.max(0L, total);
        available = Math.max(0L, Math.min(available, total));
        producibleAmount = producible ? Math.max(0L, producibleAmount) : 0L;
        if (rule == null)
            rule = Optional.empty();
        ruleReserved = rule.isPresent() ? Math.max(0L, Math.min(ruleReserved, available)) : 0L;
        ruleMaximum = rule.isPresent() && ruleMaximum >= 0L ? Math.min(ruleMaximum, StockRule.MAX_AMOUNT)
                : StockRule.UNSET;
    }

    /** An entry of an item that is simply in stock. */
    public TerminalStockEntry(ItemKey key, long total, long available) {
        this(key, total, available, false, 0L, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** An entry of a producible item without the amount that could be made right now. */
    public TerminalStockEntry(ItemKey key, long total, long available, boolean producible) {
        this(key, total, available, producible, 0L, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** An entry no stock rule governs, which is every entry of an aisle without stock keepers. */
    public TerminalStockEntry(ItemKey key, long total, long available, boolean producible, long producibleAmount) {
        this(key, total, available, producible, producibleAmount, Optional.empty(), 0L, StockRule.UNSET);
    }

    /** Whether a stock rule governs this item, which is what keeps its row alive at zero stock. */
    public boolean ruled() {
        return rule.isPresent();
    }

    /** What the warehouse's own automation could still be promised: {@code available − ruleReserved}. */
    public long availableToAutomation() {
        return Math.max(0L, available - ruleReserved);
    }

    /** What is stored but already promised to a running job or an open request: {@code total - available}. */
    public long reserved() {
        return total - available;
    }

    /** The largest amount one request may ask for right now: what is available plus what could be made. */
    public long orderable() {
        return available + producibleAmount;
    }
}
