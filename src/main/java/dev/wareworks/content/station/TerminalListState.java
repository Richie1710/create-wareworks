package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.ListLine;
import dev.wareworks.core.terminal.ListOrder;
import dev.wareworks.core.terminal.ListOrderState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * How far a warehouse terminal's clipboard order has got, in the bounded form the screen and the goggles are given
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19).
 * <p>
 * Everything in it is a number or an enum name, plus at most <b>one</b> item: the entry the order is working on, which
 * is what makes "Fetching Oak Log, 3 of 12 entries" possible. That one item travels only in the terminal's
 * <b>payload</b> ({@code TerminalListPayload}) and never in a chunk packet: {@link #write(CompoundTag)} leaves it out,
 * so a goggle tooltip costs the same bytes whatever the clipboard holds (the rule every station's summary follows,
 * {@link StationGoggleSummary}).
 * <p>
 * It is derived state, rebuilt from the order whenever it is asked for, and compares by value — so a terminal whose
 * order did not move costs no packet.
 *
 * @param active          whether a clipboard order exists at all. Everything else is 0 or empty when it does not
 * @param state           where the order stands ({@link ListOrderState})
 * @param entries         clipboard entries the order took
 * @param entriesComplete of those, the ones that are delivered in full, i.e. the tick marks the clipboard carries
 * @param wanted          items the whole list asks for
 * @param delivered       items the crane has really brought here for it
 * @param inFlight        items open requests of this order still owe
 * @param dropped         orderable entries the entry cap left on the clipboard, untouched and unticked
 * @param asking          whether a question a single portion raised is waiting for an answer. The question itself goes
 *                        to the screen through the terminal's ordinary confirmation payload, because it is the very
 *                        question a click raises ({@code §3.6.6})
 * @param current         the entry the order is working on, for the status line; empty for a finished order
 */
public record TerminalListState(boolean active, ListOrderState state, int entries, int entriesComplete, long wanted,
                                long delivered, long inFlight, int dropped, boolean asking,
                                Optional<ItemKey> current) {
    /** No clipboard order on this terminal. */
    public static final TerminalListState NONE = new TerminalListState(false, ListOrderState.DONE, 0, 0, 0L, 0L, 0L, 0,
            false, Optional.empty());

    private static final String ACTIVE = "Active";
    private static final String STATE = "State";
    private static final String ENTRIES = "Entries";
    private static final String COMPLETE = "Complete";
    private static final String WANTED = "Wanted";
    private static final String DELIVERED = "Delivered";
    private static final String IN_FLIGHT = "InFlight";
    private static final String DROPPED = "Dropped";
    private static final String ASKING = "Asking";

    public TerminalListState {
        Objects.requireNonNull(current, "current");
        if (state == null)
            state = ListOrderState.DONE;
        entries = Math.max(0, entries);
        entriesComplete = Math.max(0, Math.min(entriesComplete, entries));
        wanted = Math.max(0L, wanted);
        delivered = Math.max(0L, Math.min(delivered, wanted));
        inFlight = Math.max(0L, Math.min(inFlight, wanted - delivered));
        dropped = Math.max(0, dropped);
        if (!active) {
            state = ListOrderState.DONE;
            entries = 0;
            entriesComplete = 0;
            wanted = 0L;
            delivered = 0L;
            inFlight = 0L;
            dropped = 0;
            asking = false;
            current = Optional.empty();
        }
    }

    /** The state of {@code order}; {@link #NONE} for a terminal without one. */
    public static TerminalListState of(ListOrder<ItemKey> order) {
        if (order == null)
            return NONE;
        return new TerminalListState(true, order.state(), order.entries(), order.entriesComplete(), order.wanted(),
                order.delivered(), order.inFlight(), order.dropped(),
                order.state() == ListOrderState.ASKING, order.current().map(ListLine::key));
    }

    /** Items the list is still missing. */
    public long outstanding() {
        return wanted - delivered;
    }

    /** Entries that are not delivered in full yet. */
    public int entriesLeft() {
        return entries - entriesComplete;
    }

    /** Whether the order still has work to do. */
    public boolean isOpen() {
        return active && state.isOpen();
    }

    /** Whether the order is waiting for a player: a question to answer, or a park to resume. */
    public boolean waitsForPlayer() {
        return active && state.waitsForPlayer();
    }

    /** Whether the clipboard held more entries than this order took. */
    public boolean truncated() {
        return dropped > 0;
    }

    /**
     * Writes the numbers into {@code tag}, and <b>nothing at all</b> for a terminal without an order, so no station's
     * client packet grows by a byte unless a list is really being worked off. The current item is deliberately left
     * out (see the class comment). Never throws.
     */
    public void write(CompoundTag tag) {
        if (!active)
            return;
        tag.putBoolean(ACTIVE, true);
        tag.putString(STATE, state.name());
        tag.putInt(ENTRIES, entries);
        tag.putInt(COMPLETE, entriesComplete);
        tag.putLong(WANTED, wanted);
        tag.putLong(DELIVERED, delivered);
        tag.putLong(IN_FLIGHT, inFlight);
        if (dropped > 0)
            tag.putInt(DROPPED, dropped);
        if (asking)
            tag.putBoolean(ASKING, true);
    }

    /** Reads what {@link #write(CompoundTag)} wrote; never throws, and anything unreadable reads as {@link #NONE}. */
    public static TerminalListState read(CompoundTag tag) {
        if (tag == null || !tag.getBoolean(ACTIVE))
            return NONE;
        return new TerminalListState(true,
                ListOrderState.byName(tag.getString(STATE)).orElse(ListOrderState.RUNNING), tag.getInt(ENTRIES),
                tag.getInt(COMPLETE), tag.getLong(WANTED), tag.getLong(DELIVERED), tag.getLong(IN_FLIGHT),
                tag.contains(DROPPED, Tag.TAG_INT) ? tag.getInt(DROPPED) : 0, tag.getBoolean(ASKING),
                Optional.empty());
    }

    /** Writes this state into a payload, including the one item it carries. */
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        if (!active)
            return;
        buffer.writeVarInt(state.ordinal());
        buffer.writeVarInt(entries);
        buffer.writeVarInt(entriesComplete);
        buffer.writeVarLong(wanted);
        buffer.writeVarLong(delivered);
        buffer.writeVarLong(inFlight);
        buffer.writeVarInt(dropped);
        buffer.writeBoolean(asking);
        buffer.writeBoolean(current.isPresent());
        current.ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
    }

    /** Reads a state written by {@link #write(RegistryFriendlyByteBuf)}; an unknown state ordinal reads as RUNNING. */
    public static TerminalListState read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return NONE;
        int ordinal = buffer.readVarInt();
        ListOrderState[] states = ListOrderState.values();
        ListOrderState state = ordinal < 0 || ordinal >= states.length ? ListOrderState.RUNNING : states[ordinal];
        int entries = buffer.readVarInt();
        int complete = buffer.readVarInt();
        long wanted = buffer.readVarLong();
        long delivered = buffer.readVarLong();
        long inFlight = buffer.readVarLong();
        int dropped = buffer.readVarInt();
        boolean asking = buffer.readBoolean();
        Optional<ItemKey> current = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                : Optional.empty();
        return new TerminalListState(true, state, entries, complete, wanted, delivered, inFlight, dropped, asking,
                current);
    }
}
