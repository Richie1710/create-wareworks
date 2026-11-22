package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.terminal.ListLine;
import dev.wareworks.core.terminal.ListOrder;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * A package-mode terminal's <b>address order</b> (issue #40, owner decision E11, design §6.3 as corrected by the
 * critic's M2): the boxes a click on an address row picked, fetched sealed into this terminal's buffer or to one
 * package door, a few at a time.
 * <p>
 * <b>A third list order, not a new mechanism.</b> {@link #order()} is a {@link ListOrder} like the clipboard's and the
 * craft order's: one line per picked box key, worked off in portions that are each an ordinary request to the order's
 * destination, so the per-destination cap ({@code maxOpenRequestsPerOutput}), the queue cap and the buffer's room
 * bound it exactly as they bound a click, and a twentieth box simply waits for the first ones to be taken out. Its
 * consent is {@link RequestAcknowledgement#ANY}: the player clicked the boxes, and package mode makes nothing, so there
 * is nothing to ask about. It never parks: it waits until its boxes can come, or until a player cancels it.
 * <p>
 * Beside the clipboard order and the craft order it has what the other two cannot: a <b>destination</b> (a package
 * door, or this terminal) and an id the screen's cancel mark names. A box another request took out before the crane
 * came for it is taken off its line ({@link #dropGone}) and counted in {@link #gone()}, so the order ends instead of
 * waiting for a box that no longer exists.
 * <p>
 * Server thread only. Persistence ({@link #write}, {@link #read}), written only while an order exists, so a terminal
 * without one saves exactly what it saved before package mode:
 * <pre>
 * PackageOrder: { Id: UUID, Order: &lt;TerminalListPersistence&gt;, Door?: long, Gone?: int, Addresses: [ string ] }
 * </pre>
 * Reading never throws: an order without a readable list is no order, an unreadable door is "here".
 */
final class TerminalPackageOrder {
    /** NBT key of the address order inside the terminal's station data. */
    static final String PACKAGE_ORDER_TAG = "PackageOrder";
    /** Most addresses one order remembers by name. */
    private static final int MAX_ADDRESSES = 64;

    private static final String ID = "Id";
    private static final String ORDER = "Order";
    private static final String DOOR = "Door";
    private static final String GONE = "Gone";
    private static final String ADDRESSES = "Addresses";

    private final UUID id;
    private ListOrder<ItemKey> order;
    private final Optional<BlockPos> door;
    private int gone;
    private final Set<String> addresses = new LinkedHashSet<>();

    TerminalPackageOrder(UUID id, ListOrder<ItemKey> order, Optional<BlockPos> door, int gone,
            List<String> addresses) {
        this.id = Objects.requireNonNull(id, "id");
        this.order = Objects.requireNonNull(order, "order");
        this.door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
        this.gone = Math.max(0, gone);
        for (String address : addresses) {
            if (this.addresses.size() >= MAX_ADDRESSES)
                break;
            this.addresses.add(address == null ? "" : address);
        }
    }

    /** A new order for the picked boxes of {@code address}, running from {@code now}. */
    static TerminalPackageOrder start(String address, List<KeyCount<ItemKey>> picked, Optional<BlockPos> door,
            long now) {
        List<ListLine<ItemKey>> lines = lines(picked, 0);
        return new TerminalPackageOrder(UUID.randomUUID(), ListOrder.restore(lines, 0, ListOrderState.RUNNING,
                RequestAcknowledgement.ANY, now, now), door, 0, List.of(address));
    }

    private static List<ListLine<ItemKey>> lines(List<KeyCount<ItemKey>> picked, int firstIndex) {
        List<ListLine<ItemKey>> lines = new ArrayList<>(picked.size());
        int index = firstIndex;
        for (KeyCount<ItemKey> box : picked)
            lines.add(ListLine.of(0, index++, box.key(), (int) Math.min(Integer.MAX_VALUE, box.count())));
        return lines;
    }

    /**
     * Adds a further click's boxes to this open order (same destination): its lines are kept as they are, with their
     * requests and what arrived, and the new ones are appended. The order runs again from {@code now}.
     *
     * @return how many boxes were added; fewer than picked when the order reached {@link ListOrder#MAX_ENTRIES} lines
     */
    int add(String address, List<KeyCount<ItemKey>> picked, long now) {
        List<ListLine<ItemKey>> lines = new ArrayList<>(order.lines());
        int room = ListOrder.MAX_ENTRIES - lines.size();
        List<KeyCount<ItemKey>> taken = picked.subList(0, Math.max(0, Math.min(room, picked.size())));
        if (taken.isEmpty())
            return 0;
        lines.addAll(lines(taken, lines.size()));
        order = ListOrder.restore(lines, order.dropped(), ListOrderState.RUNNING, RequestAcknowledgement.ANY, now,
                now);
        if (addresses.size() < MAX_ADDRESSES)
            addresses.add(address == null ? "" : address);
        long added = 0L;
        for (KeyCount<ItemKey> box : taken)
            added += box.count();
        return (int) Math.min(Integer.MAX_VALUE, added);
    }

    UUID id() {
        return id;
    }

    ListOrder<ItemKey> order() {
        return order;
    }

    /** The package door the boxes go to; empty for the terminal's own buffer. */
    Optional<BlockPos> door() {
        return door;
    }

    /** Boxes that were taken by somebody else before the crane came for them. */
    int gone() {
        return gone;
    }

    boolean isOpen() {
        return order.state().isOpen();
    }

    /** How many boxes of {@code key} the open lines still wait for and have not asked a request for yet. */
    long unrequested(ItemKey key) {
        long waiting = 0L;
        for (ListLine<ItemKey> line : order.lines()) {
            if (!line.isComplete() && line.key().equals(key))
                waiting += line.outstanding() - line.inFlight();
        }
        return waiting;
    }

    /**
     * Takes the boxes off their lines that nobody may fetch any more: a line still waiting, with no request open, whose
     * box the warehouse no longer holds ({@code held} answers 0) — another request or a player took it out between
     * the click and the crane. The line keeps what arrived for it; a line that got nothing is dropped. What was taken
     * off is counted in {@link #gone()}. An order left with nothing to wait for is done.
     *
     * @return whether anything changed
     */
    boolean dropGone(ToLongFunction<ItemKey> held) {
        List<ListLine<ItemKey>> kept = new ArrayList<>(order.lines().size());
        int taken = 0;
        for (ListLine<ItemKey> line : order.lines()) {
            if (line.isComplete() || line.isInFlight() || held.applyAsLong(line.key()) > 0L) {
                kept.add(line);
                continue;
            }
            taken += line.outstanding();
            if (line.delivered() > 0)
                kept.add(new ListLine<>(line.page(), line.index(), line.key(), line.delivered(), line.delivered(), 0,
                        Optional.empty()));
        }
        if (taken == 0)
            return false;
        gone += taken;
        boolean complete = kept.stream().allMatch(ListLine::isComplete);
        order = ListOrder.restore(kept, order.dropped(), complete ? ListOrderState.DONE : order.state(),
                RequestAcknowledgement.ANY, order.nextPassTick(), order.lastProgressTick());
        return true;
    }

    /** The bounded form the screens are given. */
    TerminalPackageOrderState state() {
        return new TerminalPackageOrderState(true, id, order.state(), order.wanted(), order.delivered(),
                order.inFlight(), gone, door, List.copyOf(addresses));
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /** Writes {@code packageOrder} into {@code tag}, and nothing at all for a terminal without one. Never throws. */
    static void write(CompoundTag tag, TerminalPackageOrder packageOrder, HolderLookup.Provider registries) {
        if (packageOrder == null)
            return;
        CompoundTag orderTag = new CompoundTag();
        TerminalListPersistence.write(orderTag, packageOrder.order, registries, ORDER);
        if (!orderTag.contains(ORDER, Tag.TAG_COMPOUND))
            return;
        orderTag.putUUID(ID, packageOrder.id);
        packageOrder.door.ifPresent(pos -> orderTag.putLong(DOOR, pos.asLong()));
        if (packageOrder.gone > 0)
            orderTag.putInt(GONE, packageOrder.gone);
        ListTag names = new ListTag();
        for (String address : packageOrder.addresses)
            names.add(StringTag.valueOf(address));
        orderTag.put(ADDRESSES, names);
        tag.put(PACKAGE_ORDER_TAG, orderTag);
    }

    /**
     * Reads the order {@link #write} wrote, or empty when the tag holds none or none that can be read. {@code now}
     * becomes the restored order's interval origin ({@link TerminalListPersistence}). Never throws.
     */
    static Optional<TerminalPackageOrder> read(CompoundTag tag, HolderLookup.Provider registries, long now) {
        if (tag == null || !tag.contains(PACKAGE_ORDER_TAG, Tag.TAG_COMPOUND))
            return Optional.empty();
        try {
            CompoundTag orderTag = tag.getCompound(PACKAGE_ORDER_TAG);
            Optional<ListOrder<ItemKey>> order = TerminalListPersistence.read(orderTag, registries, now, ORDER);
            if (order.isEmpty())
                return Optional.empty();
            UUID id = orderTag.hasUUID(ID) ? orderTag.getUUID(ID) : UUID.randomUUID();
            Optional<BlockPos> door = orderTag.contains(DOOR, Tag.TAG_LONG)
                    ? Optional.of(BlockPos.of(orderTag.getLong(DOOR))) : Optional.empty();
            ListTag names = orderTag.getList(ADDRESSES, Tag.TAG_STRING);
            List<String> addresses = new ArrayList<>(Math.min(names.size(), MAX_ADDRESSES));
            for (int i = 0; i < names.size() && addresses.size() < MAX_ADDRESSES; i++)
                addresses.add(names.getString(i));
            return Optional.of(new TerminalPackageOrder(id, order.get(), door, orderTag.getInt(GONE), addresses));
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Skipping an unreadable package order", e);
            return Optional.empty();
        }
    }
}
