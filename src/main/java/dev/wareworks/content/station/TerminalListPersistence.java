package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.ListLine;
import dev.wareworks.core.terminal.ListOrder;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * NBT form of a warehouse terminal's clipboard order ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19):
 * <pre>
 * ListOrder: { State: "RUNNING", Dropped: int,
 *              Budget: { Any?: true, Reserve: long, Maximum: long, Ingredient: long, Produced: long },
 *              Lines: [ { P: int, I: int, Item: &lt;ItemKey&gt;, Want: int, Got: int, Flight: int, Req?: UUID } ] }
 * </pre>
 * <b>The two tick stamps are deliberately not saved.</b> A world that was closed for an hour would otherwise come back
 * with every order already past its stall timeout and immediately parked; a restored order starts its interval and its
 * stall timer over instead, exactly as a production order restarts its deadline
 * ({@code ProductionOrders#restartDeadlines}).
 * <p>
 * {@code ASKING} is not saved either — {@link ListOrder#restore} maps it to {@code RUNNING}, because the question it
 * was waiting for is measured against the warehouse as it is now and was therefore never written down. The order
 * offers that portion again and asks again if it still costs more than the budget covers.
 * <p>
 * A line's {@code Req} is the retrieval request that still owes it items. Nothing has to be loaded in any order for
 * that to be right: a request id the controller no longer has is forgotten by the order's next pass and gives nothing
 * back, because what a line is still missing is {@link ListLine#outstanding()} either way.
 * <p>
 * Writing never throws (a line that fails is skipped and logged) and reading never throws: an unreadable line is
 * skipped, every number is clamped by {@link ListLine} itself, and the list is bounded by
 * {@link ListOrder#MAX_ENTRIES}, so crafted save data can neither make one order walk an unbounded list nor describe a
 * line that loses items.
 */
final class TerminalListPersistence {
    static final String LIST_ORDER_TAG = "ListOrder";

    private static final String STATE = "State";
    private static final String DROPPED = "Dropped";
    private static final String BUDGET = "Budget";
    private static final String LINES = "Lines";
    private static final String ANY = "Any";
    private static final String RESERVE = "Reserve";
    private static final String MAXIMUM = "Maximum";
    private static final String INGREDIENT = "Ingredient";
    private static final String PRODUCED = "Produced";
    private static final String PAGE = "P";
    private static final String INDEX = "I";
    private static final String ITEM = "Item";
    private static final String WANTED = "Want";
    private static final String DELIVERED = "Got";
    private static final String IN_FLIGHT = "Flight";
    private static final String REQUEST = "Req";

    private TerminalListPersistence() {
    }

    /** Writes {@code order} into {@code tag}, and nothing at all for a terminal without one. Never throws. */
    static void write(CompoundTag tag, @Nullable ListOrder<ItemKey> order, HolderLookup.Provider registries) {
        if (order == null)
            return;
        ListTag lines = new ListTag();
        for (ListLine<ItemKey> line : order.lines()) {
            try {
                Tag keyTag = line.key().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key (already logged by ItemKey); the line is lost, no items are
                CompoundTag entry = new CompoundTag();
                entry.putInt(PAGE, line.page());
                entry.putInt(INDEX, line.index());
                entry.put(ITEM, keyTag);
                entry.putInt(WANTED, line.wanted());
                entry.putInt(DELIVERED, line.delivered());
                if (line.inFlight() > 0)
                    entry.putInt(IN_FLIGHT, line.inFlight());
                line.request().ifPresent(id -> entry.putUUID(REQUEST, id));
                lines.add(entry);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save a clipboard order line {}", line, e);
            }
        }
        CompoundTag orderTag = new CompoundTag();
        orderTag.putString(STATE, order.state().name());
        if (order.dropped() > 0)
            orderTag.putInt(DROPPED, order.dropped());
        orderTag.put(BUDGET, writeBudget(order.budget()));
        orderTag.put(LINES, lines);
        tag.put(LIST_ORDER_TAG, orderTag);
    }

    /**
     * Reads the order {@link #write} wrote, or empty when the tag holds none. {@code now} becomes the restored order's
     * interval and stall origin (see the class comment).
     */
    static Optional<ListOrder<ItemKey>> read(CompoundTag tag, HolderLookup.Provider registries, long now) {
        if (!tag.contains(LIST_ORDER_TAG, Tag.TAG_COMPOUND))
            return Optional.empty();
        CompoundTag orderTag = tag.getCompound(LIST_ORDER_TAG);
        ListTag lineTags = orderTag.getList(LINES, Tag.TAG_COMPOUND);
        List<ListLine<ItemKey>> lines = new ArrayList<>(Math.min(lineTags.size(), ListOrder.MAX_ENTRIES));
        int skipped = 0;
        for (int i = 0; i < lineTags.size() && lines.size() < ListOrder.MAX_ENTRIES; i++) {
            CompoundTag entry = lineTags.getCompound(i);
            try {
                Optional<ListLine<ItemKey>> line = readLine(entry, registries);
                if (line.isPresent())
                    lines.add(line.get());
                else
                    skipped++;
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping an unreadable clipboard order line {}", entry, e);
                skipped++;
            }
        }
        if (lines.isEmpty())
            return Optional.empty(); // an order without lines has nothing to fetch and nothing to tick off
        ListOrderState state = ListOrderState.byName(orderTag.getString(STATE)).orElse(ListOrderState.RUNNING);
        // A line that could not be read is an entry this order will never fetch, which is exactly what "dropped" says:
        // it stays unticked on the clipboard and the terminal reports it.
        int dropped = Math.max(0, orderTag.getInt(DROPPED)) + skipped;
        return Optional.of(ListOrder.restore(lines, dropped, state, readBudget(orderTag.getCompound(BUDGET)), now,
                now));
    }

    private static Optional<ListLine<ItemKey>> readLine(CompoundTag entry, HolderLookup.Provider registries) {
        int wanted = entry.getInt(WANTED);
        Optional<ItemKey> key = ItemKey.load(registries, entry.get(ITEM));
        if (key.isEmpty() || wanted < 1)
            return Optional.empty();
        Optional<UUID> request = entry.hasUUID(REQUEST) ? Optional.of(entry.getUUID(REQUEST)) : Optional.empty();
        return Optional.of(new ListLine<>(entry.getInt(PAGE), entry.getInt(INDEX), key.get(), wanted,
                entry.getInt(DELIVERED), entry.getInt(IN_FLIGHT), request));
    }

    /** The consent budget: a flag for "whatever it costs" and otherwise the four numbers the player accepted. */
    private static CompoundTag writeBudget(RequestAcknowledgement budget) {
        CompoundTag tag = new CompoundTag();
        if (budget.any()) {
            tag.putBoolean(ANY, true);
            return tag;
        }
        if (budget.fromReserve() > 0L)
            tag.putLong(RESERVE, budget.fromReserve());
        if (budget.pastMaximum() > 0L)
            tag.putLong(MAXIMUM, budget.pastMaximum());
        if (budget.ingredientReserve() > 0L)
            tag.putLong(INGREDIENT, budget.ingredientReserve());
        if (budget.produced() > 0L)
            tag.putLong(PRODUCED, budget.produced());
        return tag;
    }

    private static RequestAcknowledgement readBudget(CompoundTag tag) {
        if (tag.getBoolean(ANY))
            return RequestAcknowledgement.ANY;
        return new RequestAcknowledgement(false, tag.getLong(RESERVE), tag.getLong(MAXIMUM), tag.getLong(INGREDIENT),
                tag.getLong(PRODUCED));
    }
}
