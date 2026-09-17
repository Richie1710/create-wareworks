package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.ProductionOrderState;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * What a warehouse production station's screen is shown ({@code docs/warehouse-system.md} §3.5): its pattern entries
 * and the production orders running at it.
 * <p>
 * This travels as a <b>menu payload</b> to the one player who has the screen open, never in the block entity's update
 * tag: pattern entries are {@link ItemKey}s with data components, and a block entity's update tag is part of every
 * chunk packet ({@code docs/architecture.md}, goggle data notes). The goggles get numbers only
 * ({@link ProductionGoggleSummary}).
 * <p>
 * Both directions are bounded: at most {@value ProductionPatterns#MAX_SLOTS} patterns with
 * {@value ProductionPatterns#ENTRIES_PER_PATTERN} entries each and {@value #MAX_ORDERS} orders are ever written or
 * read, whatever a length prefix claims.
 *
 * @param patterns one entry list per pattern slot, in slot order
 * @param orders   the production orders at this station, newest last
 */
public record ProductionScreenState(List<PatternView> patterns, List<OrderView> orders) {
    /** Orders one payload carries; a station with more shows the first ones. */
    public static final int MAX_ORDERS = 8;

    public static final ProductionScreenState NONE = new ProductionScreenState(List.of(), List.of());

    public ProductionScreenState {
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
    }

    /**
     * One entry of a pattern slot.
     *
     * @param entry the entry index: a grid cell, or {@value ProductionPatterns#RESULT_ENTRY} for the result
     * @param key   the item
     * @param count items per run
     */
    public record EntryView(int entry, ItemKey key, int count) {
        public EntryView {
            Objects.requireNonNull(key, "key");
        }
    }

    /** The entries a pattern slot holds; an empty list is an empty slot. */
    public record PatternView(List<EntryView> entries) {
        public PatternView {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * One production order as the screen shows it.
     *
     * @param id        the order, so the screen can ask the server to cancel exactly this one
     * @param state     where it stands
     * @param result    what it is making
     * @param amount    how many of it the order waits for
     * @param produced  how many of them have arrived in the warehouse
     * @param missing   ingredient items the crane still has to bring
     * @param delivered ingredient items already dropped into this station; for a finished order these are the items
     *                  the machine may already have taken, which nothing recovers ({@code warehouse-system.md} §3.5)
     */
    public record OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced,
                            long missing, long delivered) {
        public OrderView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(result, "result");
            amount = Math.max(0, amount);
            produced = Math.max(0L, produced);
            missing = Math.max(0L, missing);
            delivered = Math.max(0L, delivered);
        }

        /**
         * Whether this order ended without producing everything although ingredients had already been handed over, so
         * the screen has to say plainly that those items are gone.
         */
        public boolean lostIngredients() {
            return state.isFinished() && state != ProductionOrderState.COMPLETE && delivered > 0L;
        }

        /**
         * Writes one order line. It lives here rather than inside {@link ProductionScreenState#write} because the
         * warehouse terminal shows the same lines and sends them in its own payload
         * ({@code network.TerminalOrdersPayload}, M11): one wire form, so the two screens cannot drift apart.
         */
        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeVarInt(state.ordinal());
            ItemKey.STREAM_CODEC.encode(buffer, result);
            buffer.writeVarInt(amount);
            buffer.writeVarLong(produced);
            buffer.writeVarLong(missing);
            buffer.writeVarLong(delivered);
        }

        /** Reads a line written by {@link #write}; an unknown state ordinal falls back to the first state. */
        public static OrderView read(RegistryFriendlyByteBuf buffer) {
            UUID id = buffer.readUUID();
            int ordinal = buffer.readVarInt();
            ProductionOrderState[] states = ProductionOrderState.values();
            ProductionOrderState state = ordinal < 0 || ordinal >= states.length ? states[0] : states[ordinal];
            ItemKey result = ItemKey.STREAM_CODEC.decode(buffer);
            return new OrderView(id, state, result, buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong());
        }
    }

    /** Writes this state; never more than the bounds above. */
    public void write(RegistryFriendlyByteBuf buffer) {
        int patternCount = Math.min(patterns.size(), ProductionPatterns.MAX_SLOTS);
        buffer.writeVarInt(patternCount);
        for (int i = 0; i < patternCount; i++) {
            List<EntryView> entries = patterns.get(i).entries();
            int entryCount = Math.min(entries.size(), ProductionPatterns.ENTRIES_PER_PATTERN);
            buffer.writeVarInt(entryCount);
            for (int e = 0; e < entryCount; e++) {
                EntryView entry = entries.get(e);
                buffer.writeVarInt(entry.entry());
                ItemKey.STREAM_CODEC.encode(buffer, entry.key());
                buffer.writeVarInt(entry.count());
            }
        }
        int orderCount = Math.min(orders.size(), MAX_ORDERS);
        buffer.writeVarInt(orderCount);
        for (int i = 0; i < orderCount; i++)
            orders.get(i).write(buffer);
    }

    /** Reads a state written by {@link #write}; bounded, and unknown ordinals fall back to the first state. */
    public static ProductionScreenState read(RegistryFriendlyByteBuf buffer) {
        int patternCount = Math.min(Math.max(0, buffer.readVarInt()), ProductionPatterns.MAX_SLOTS);
        List<PatternView> patterns = new ArrayList<>(patternCount);
        for (int i = 0; i < patternCount; i++) {
            int entryCount = Math.min(Math.max(0, buffer.readVarInt()), ProductionPatterns.ENTRIES_PER_PATTERN);
            List<EntryView> entries = new ArrayList<>(entryCount);
            for (int e = 0; e < entryCount; e++) {
                int index = buffer.readVarInt();
                ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
                entries.add(new EntryView(index, key, buffer.readVarInt()));
            }
            patterns.add(new PatternView(entries));
        }
        int orderCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_ORDERS);
        List<OrderView> orders = new ArrayList<>(orderCount);
        for (int i = 0; i < orderCount; i++)
            orders.add(OrderView.read(buffer));
        return new ProductionScreenState(patterns, orders);
    }

    /** The entry of a pattern slot, if it is set. */
    public Optional<EntryView> entry(int pattern, int entry) {
        if (pattern < 0 || pattern >= patterns.size())
            return Optional.empty();
        for (EntryView view : patterns.get(pattern).entries()) {
            if (view.entry() == entry)
                return Optional.of(view);
        }
        return Optional.empty();
    }

    /** The result entry of a pattern slot, i.e. what its tab shows. */
    public Optional<EntryView> result(int pattern) {
        return entry(pattern, ProductionPatterns.RESULT_ENTRY);
    }
}
