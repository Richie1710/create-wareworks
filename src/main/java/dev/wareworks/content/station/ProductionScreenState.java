package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.PlanMember;
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
 * @param stopped  the products of this station the <b>safety stop</b> is holding (M20, issue #4, ADR-032), in pattern
 *                 order. A station whose machine works carries an empty list and its screen reads exactly as it did
 *                 before; a station that has lost a batch is the one place a player can see it and lift it while
 *                 standing in front of the machine that swallowed it
 */
public record ProductionScreenState(List<PatternView> patterns, List<OrderView> orders,
                                    List<StoppedProduct> stopped) {
    /**
     * Orders one payload carries; a station with more shows the first ones.
     * <p>
     * It is <b>16</b> since M20 (issue #4, ADR-032), because a production plan is one order per step
     * ({@code maxProductionPlanSteps}) and a screen that showed only part of a chain would name a step count its own
     * panel could not list. A whole plan plus another plan's root therefore fits one payload at the default bounds.
     */
    public static final int MAX_ORDERS = 16;

    /**
     * Stopped products one payload carries. A station has at most {@value ProductionPatterns#MAX_SLOTS} patterns and so
     * at most that many different products, and the bound is what keeps a hostile payload from claiming a megabyte of
     * them (M20).
     */
    public static final int MAX_STOPPED = ProductionPatterns.MAX_SLOTS;

    /**
     * Longest station address a line may carry. A canonical {@code StorageAddress} is at most ten characters
     * ({@code Z-999-999R}), and the bound is what keeps a hostile payload from claiming a megabyte of text.
     */
    public static final int MAX_ADDRESS_LENGTH = 16;

    /**
     * Deepest step a line may claim to be. A legitimate depth can never reach it: the server derives it by walking parent
     * links, and a plan may have at most {@link PlanLimits#MAX_STEPS} orders whatever the configuration says, so every
     * level of a chain costs at least one of them. The bound exists for the same reason {@link #MAX_ADDRESS_LENGTH} does
     * — the terminal turns a depth into an indent string ({@code WarehouseTerminalScreen#stepRow}), so an unbounded
     * varint would let a hostile payload make a client build megabytes of spaces, or overflow the multiplication.
     */
    public static final int MAX_DEPTH = PlanLimits.MAX_STEPS;

    public static final ProductionScreenState NONE =
            new ProductionScreenState(List.of(), List.of(), List.of());

    public ProductionScreenState {
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        stopped = List.copyOf(Objects.requireNonNull(stopped, "stopped"));
    }

    /**
     * A station nothing of whose products is stopped: the shape every call site used before M20, so a test or a caller
     * that says nothing about the safety stop keeps describing a station whose machine works.
     */
    public ProductionScreenState(List<PatternView> patterns, List<OrderView> orders) {
        this(patterns, orders, List.of());
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
     * @param plan      the <b>root order</b> of the production plan this order belongs to (M20, issue #4, ADR-032),
     *                  empty for an ordinary single-level order. The server answers it, so a screen never walks a chain
     *                  itself; an empty value is what makes a warehouse without chains look exactly as it did before
     * @param depth     how many steps this order is below its plan's root: 0 for the root and for an order in no plan,
     *                  never more than {@link #MAX_DEPTH}, which no real plan can reach
     * @param address   the address of the rack position its station stands at, e.g. {@code "A-05-01R"}, formatted by
     *                  the <b>server</b> — a screen has no layout and no aisle letter. Naming it is what makes a stalled
     *                  chain actionable: it says which machine to walk to. Empty while the aisle cannot name one
     * @param waitingForStep whether another order of the same plan is still making one of this order's ingredients. Such
     *                  an order fetches <b>nothing</b> at all until that step is done ({@code ProductionOrders
     *                  #hasOpenChildren}), which is the safety property of a chain and therefore said out loud rather
     *                  than guessed from the state
     */
    public record OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced,
                            long missing, long delivered, Optional<UUID> plan, int depth, Optional<String> address,
                            boolean waitingForStep) {
        public OrderView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(result, "result");
            amount = Math.max(0, amount);
            produced = Math.max(0L, produced);
            missing = Math.max(0L, missing);
            delivered = Math.max(0L, delivered);
            Objects.requireNonNull(plan, "plan");
            // Clamped here rather than at the reader, so the bound holds for every caller and not only for the wire.
            depth = Math.min(Math.max(0, depth), MAX_DEPTH);
            Objects.requireNonNull(address, "address");
        }

        /**
         * An order that is no part of a production plan: every order was one of these before M20, and this is the form
         * the tests and the pre-M20 call sites keep using.
         */
        public OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced, long missing,
                long delivered) {
            this(id, state, result, amount, produced, missing, delivered, Optional.empty(), 0, Optional.empty(), false);
        }

        /**
         * The model of this order a screen's production section works from ({@code core.terminal.PlanLines},
         * {@code core.terminal.PlanCancelCost}): its place in the chain and what ending it would abandon.
         */
        public PlanMember member() {
            return new PlanMember(id, plan, depth, !state.isFinished(), delivered);
        }

        /** Whether this order is a step of a chain rather than the item a player actually ordered. */
        public boolean isStep() {
            return plan.isPresent() && !plan.get().equals(id);
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
            // The plan fields are appended, so an order in no plan costs four bytes: two empty optionals, a zero
            // depth and a flag (M20).
            buffer.writeOptional(plan, (out, value) -> out.writeUUID(value));
            buffer.writeVarInt(depth);
            buffer.writeOptional(address, (out, value) -> out.writeUtf(value, MAX_ADDRESS_LENGTH));
            buffer.writeBoolean(waitingForStep);
        }

        /** Reads a line written by {@link #write}; an unknown state ordinal falls back to the first state. */
        public static OrderView read(RegistryFriendlyByteBuf buffer) {
            UUID id = buffer.readUUID();
            int ordinal = buffer.readVarInt();
            ProductionOrderState[] states = ProductionOrderState.values();
            ProductionOrderState state = ordinal < 0 || ordinal >= states.length ? states[0] : states[ordinal];
            ItemKey result = ItemKey.STREAM_CODEC.decode(buffer);
            int amount = buffer.readVarInt();
            long produced = buffer.readVarLong();
            long missing = buffer.readVarLong();
            long delivered = buffer.readVarLong();
            Optional<UUID> plan = buffer.readOptional(in -> in.readUUID());
            int depth = buffer.readVarInt();
            Optional<String> address = buffer.readOptional(in -> in.readUtf(MAX_ADDRESS_LENGTH));
            return new OrderView(id, state, result, amount, produced, missing, delivered, plan, depth, address,
                    buffer.readBoolean());
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
        // Appended after the orders (M20), so a station whose machine works pays one zero byte for it.
        int stoppedCount = Math.min(stopped.size(), MAX_STOPPED);
        buffer.writeVarInt(stoppedCount);
        for (int i = 0; i < stoppedCount; i++)
            stopped.get(i).write(buffer);
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
        int stoppedCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_STOPPED);
        List<StoppedProduct> stopped = new ArrayList<>(stoppedCount);
        for (int i = 0; i < stoppedCount; i++)
            stopped.add(StoppedProduct.read(buffer));
        return new ProductionScreenState(patterns, orders, stopped);
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

    /** Whether the safety stop is holding anything this station makes (M20). */
    public boolean anyStopped() {
        return !stopped.isEmpty();
    }

    /**
     * The stopped entry for {@code key}, if the safety stop is holding it. The screen asks this per pattern tab, so a
     * tab says plainly whether <b>its</b> product is the one that stopped.
     */
    public Optional<StoppedProduct> stoppedOf(ItemKey key) {
        if (key == null)
            return Optional.empty();
        for (StoppedProduct entry : stopped) {
            if (entry.key().equals(key))
                return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** Ingredient items every stopped product of this station has lost together — what a player is really owed. */
    public long unrecoveredTotal() {
        long total = 0L;
        for (StoppedProduct entry : stopped)
            total += entry.unrecovered();
        return total;
    }

    /**
     * The members of {@code orders} as the production section's own models ({@link OrderView#member()}), which is what
     * {@code core.terminal.PlanLines} groups into lines and {@code core.terminal.PlanCancelCost} prices.
     * <p>
     * It lives here so that both screens and the GameTests build that list the same way: the terminal's step panel and a
     * station's own list must never disagree about which orders belong to which chain or about what giving up costs.
     */
    public static List<PlanMember> members(Collection<OrderView> orders) {
        if (orders == null || orders.isEmpty())
            return List.of();
        List<PlanMember> members = new ArrayList<>(orders.size());
        for (OrderView order : orders) {
            if (order != null)
                members.add(order.member());
        }
        return List.copyOf(members);
    }
}
