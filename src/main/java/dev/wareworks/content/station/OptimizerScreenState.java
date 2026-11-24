package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.job.RelocationReason;
import dev.wareworks.core.optimizer.OptimizerLimits;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * What a Warehouse Optimizer's screen is shown (issue #46, ADR-079): one line per item row with its breakdown by
 * priority, where the optimizer's order is, and what the last action had to say.
 * <p>
 * This travels as a <b>menu payload</b> to the one player who has the screen open, never in the block entity's update
 * tag: a row's item is an {@link ItemKey} with data components, and an update tag is part of every chunk packet
 * ({@code docs/architecture.md}). The goggles get numbers only ({@link OptimizerGoggleSummary}).
 * <p>
 * The rows carry the <b>server's</b> numbers, so the screen never computes a breakdown itself. Both directions are
 * bounded: at most {@value OptimizerRows#MAX_ROWS} rows are ever written or read, whatever a length prefix claims, and
 * an unknown ordinal falls back to the first value instead of throwing.
 *
 * @param rows    one entry per item row of the optimizer, in row order
 * @param linked  whether a loaded controller serves this optimizer at all ("Not part of a warehouse" otherwise): it
 *                is a member of that controller's aisle
 * @param misaligned whether an optimizer that is not {@code linked} stands at a rack position facing away from the
 *                aisle ("Not in a warehouse: face the aisle", issue #52); always false while linked
 * @param order   the optimizer's order, running or finished, or {@link OrderLine#NONE}
 * @param message what the last action of this screen had to say, or {@link Message#NONE}
 * @param first   the first number of {@link #message()}, 0 when it has none
 * @param second  the second number of {@link #message()}, 0 when it has none
 */
public record OptimizerScreenState(List<RowView> rows, boolean linked, boolean misaligned, OrderLine order,
                                   Message message, int first, int second) {
    /** No optimizer known yet: what a screen renders until the first payload arrives. */
    public static final OptimizerScreenState NONE =
            new OptimizerScreenState(List.of(), false, false, OrderLine.NONE, Message.NONE, 0, 0);

    public OptimizerScreenState {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        misaligned = misaligned && !linked;
        if (order == null)
            order = OrderLine.NONE;
        if (message == null)
            message = Message.NONE;
        first = Math.max(0, first);
        second = Math.max(0, second);
    }

    /** The status mark of one row: what the optimizer's order is doing with its item. */
    public enum Pip {
        /** No order lists the item, or its order ended before it was reached. Drawn as nothing. */
        IDLE,
        /** The running order still searches trips for it (aqua). */
        RUNNING,
        /** Its order moved some of it and nothing more fits anywhere better now (green). */
        DONE,
        /** Its order found nothing to move from the start (grey). */
        NOTHING_TO_MOVE;

        /** Relative lang key of this mark's sentence, e.g. {@code gui.optimizer.pip.done}. */
        public String langKey() {
            return WareworksLang.OPTIMIZER_PIP_PREFIX + name().toLowerCase(Locale.ROOT);
        }

        /**
         * Why {@code key} has nothing to move in {@code order} (issue #53): the reason the warehouse gave when its entry
         * ended with {@link #NOTHING_TO_MOVE}, empty for every other mark and for an entry saved without one.
         */
        public static Optional<RelocationReason> reasonOf(Optional<? extends OptimizerOrder<ItemKey, ?>> order,
                ItemKey key) {
            if (order.isEmpty() || key == null || of(order, key) != NOTHING_TO_MOVE)
                return Optional.empty();
            return order.get().entryFor(key).flatMap(OptimizerOrder.Entry::endReason);
        }

        /** The mark of {@code key} in {@code order}, {@link #IDLE} when the order does not list it. */
        public static Pip of(Optional<? extends OptimizerOrder<ItemKey, ?>> order, ItemKey key) {
            if (order.isEmpty() || key == null)
                return IDLE;
            return order.get().entryFor(key).map(entry -> switch (entry.state()) {
                case OPEN -> order.get().isRunning() ? RUNNING : IDLE;
                case DONE -> DONE;
                case NOTHING_TO_MOVE -> NOTHING_TO_MOVE;
                case STOPPED -> IDLE;
            }).orElse(IDLE);
        }
    }

    /**
     * Relative lang key of the sentence a row shows for {@code reason} instead of "Nothing to move" (issue #53), e.g.
     * {@code gui.optimizer.reason.same_priority}; {@link RelocationReason#NONE_IN_STOCK} reads as the pip's own
     * "Nothing to move" and so has the pip's key.
     */
    public static String reasonKey(RelocationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return reason == RelocationReason.NONE_IN_STOCK ? Pip.NOTHING_TO_MOVE.langKey()
                : WareworksLang.OPTIMIZER_REASON_PREFIX + reason.name().toLowerCase(Locale.ROOT);
    }

    /** Relative lang key of what a player can do about {@code reason}, or empty when there is nothing to add. */
    public static Optional<String> reasonHintKey(RelocationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return Optional.ofNullable(switch (reason) {
            case NONE_IN_STOCK -> null;
            case SPOKEN_FOR -> WareworksLang.OPTIMIZER_MOVABLE_RESERVED;
            case NOT_REACHABLE -> WareworksLang.OPTIMIZER_REASON_HINT_REACH;
            case SAME_PRIORITY, NO_HIGHER_PRIORITY -> WareworksLang.OPTIMIZER_REASON_HINT_PRIORITY;
            case FILTER_DOES_NOT_FIT -> WareworksLang.OPTIMIZER_REASON_HINT_FILTER;
            case NO_ROOM_HIGHER -> WareworksLang.OPTIMIZER_REASON_HINT_ROOM;
            case ALREADY_ONE_PLACE -> null;
            case HOME_FULL -> WareworksLang.OPTIMIZER_REASON_HINT_HOME;
            case HOME_WORSE -> WareworksLang.OPTIMIZER_REASON_HINT_PRIORITY;
        });
    }

    /** Where the optimizer's order is, as the status line says it. */
    public enum OrderState {
        /** It has no order (none was started, or it was forgotten). */
        NONE,
        /** It is running: "Moved n, k trips left". */
        RUNNING,
        /** Every item ended: nothing more fits anywhere better. */
        FINISHED,
        /** It used up its trips. */
        OUT_OF_TRIPS,
        /** A player cancelled it. */
        CANCELLED;

        /** The state of {@code order}. */
        public static OrderState of(OptimizerOrder.State state) {
            return switch (state) {
                case RUNNING -> RUNNING;
                case FINISHED -> FINISHED;
                case OUT_OF_TRIPS -> OUT_OF_TRIPS;
                case CANCELLED -> CANCELLED;
            };
        }
    }

    /**
     * The optimizer's order as the status line shows it.
     *
     * @param state     where it is
     * @param moved     how many items its trips have put down
     * @param tripsLeft how many trips it may still cost
     * @param merge     whether it is a merge order (issue #57, ADR-088) rather than an optimise order
     */
    public record OrderLine(OrderState state, long moved, int tripsLeft, boolean merge) {
        public static final OrderLine NONE = new OrderLine(OrderState.NONE, 0L, 0, false);

        /** The line of an optimise order. */
        public OrderLine(OrderState state, long moved, int tripsLeft) {
            this(state, moved, tripsLeft, false);
        }

        public OrderLine {
            if (state == null)
                state = OrderState.NONE;
            moved = Math.max(0L, moved);
            tripsLeft = Math.max(0, Math.min(OptimizerLimits.MAX_TRIPS, tripsLeft));
        }

        /** The line of {@code order}, or {@link #NONE}. */
        public static OrderLine of(Optional<? extends OptimizerOrder<?, ?>> order) {
            return order.map(o -> new OrderLine(OrderState.of(o.state()), o.moved(), o.tripsLeft(),
                    o.mode() == OptimizerOrder.Mode.MERGE)).orElse(NONE);
        }

        public boolean running() {
            return state == OrderState.RUNNING;
        }
    }

    /** What the last action of the screen had to say; each value is one sentence with up to two numbers. */
    public enum Message {
        /** Nothing: the status line shows the order or the hint. */
        NONE,
        /** {@code first} items were added from a clipboard. */
        ADDED,
        /** {@code first} items were added from a clipboard, and {@code second} more found no free row. */
        ADDED_NO_ROOM,
        /** Every item of the clipboard was listed already. */
        ALL_LISTED,
        /** The clipboard carries no item to add. */
        CLIPBOARD_EMPTY,
        /** The click on the list cell was not made with a clipboard. */
        NOT_A_CLIPBOARD,
        /** The item a player set is listed in another row already. */
        ALREADY_LISTED,
        /** Optimise was pressed with no item listed (or on an empty row). */
        NO_ITEMS,
        /** Every order this warehouse keeps is running ({@code OptimizerOrders.Start#TOO_MANY_ORDERS}). */
        TOO_MANY_ORDERS,
        /** A press found no warehouse this optimizer is a member of (issue #52). */
        NO_WAREHOUSE,
        /** A press found the optimizer facing away from the aisle it stands in, so it is no member (issue #52). */
        MISALIGNED;

        /**
         * Relative lang key of this message, e.g. {@code gui.optimizer.message.added}; {@link #NONE} has none. The two
         * "not part of a warehouse" answers say exactly what the status line says without a press, so they share its
         * keys ({@link #ownSentence()} is false for them).
         */
        public String langKey() {
            return switch (this) {
                case NO_WAREHOUSE -> WareworksLang.OPTIMIZER_NO_WAREHOUSE;
                case MISALIGNED -> WareworksLang.OPTIMIZER_MISALIGNED;
                default -> WareworksLang.OPTIMIZER_MESSAGE_PREFIX + name().toLowerCase(Locale.ROOT);
            };
        }

        /** Whether this message has a sentence of its own under {@code gui.optimizer.message.}. */
        public boolean ownSentence() {
            return this != NONE && this != NO_WAREHOUSE && this != MISALIGNED;
        }
    }

    /**
     * One item row as the screen shows it.
     *
     * @param row       the row index, so a click can name exactly this row to the server
     * @param key       the listed item, empty for a row a player has not filled
     * @param breakdown what the warehouse holds of it by priority and what could move, {@link PriorityBreakdown#NONE}
     *                  for an empty row
     * @param pip       what the optimizer's order is doing with it
     * @param reason    why it has nothing to move (issue #53), decided on the server ({@link Pip#reasonOf}); present only
     *                  with {@link Pip#NOTHING_TO_MOVE}
     * @param places    in how many places the warehouse holds the item (issue #57), 0 for an empty row; a place in an
     *                  unloaded chunk counts, a shared inventory counts once
     */
    public record RowView(int row, Optional<ItemKey> key, PriorityBreakdown breakdown, Pip pip,
                          Optional<RelocationReason> reason, int places) {
        public RowView {
            row = Math.max(0, row);
            if (key == null)
                key = Optional.empty();
            if (breakdown == null)
                breakdown = PriorityBreakdown.NONE;
            if (pip == null)
                pip = Pip.IDLE;
            if (reason == null || pip != Pip.NOTHING_TO_MOVE)
                reason = Optional.empty();
            places = Math.max(0, places);
        }

        /** A row with a reason and no place count. */
        public RowView(int row, Optional<ItemKey> key, PriorityBreakdown breakdown, Pip pip,
                       Optional<RelocationReason> reason) {
            this(row, key, breakdown, pip, reason, 0);
        }

        /** A row without a reason. */
        public RowView(int row, Optional<ItemKey> key, PriorityBreakdown breakdown, Pip pip) {
            this(row, key, breakdown, pip, Optional.empty());
        }

        /** An empty row. */
        public static RowView empty(int row) {
            return new RowView(row, Optional.empty(), PriorityBreakdown.NONE, Pip.IDLE);
        }

        /**
         * The lang key of what the row's mark says: the reason's sentence for an item with nothing to move (issue #53),
         * the mark's own sentence otherwise.
         */
        public String statusKey() {
            return reason.map(OptimizerScreenState::reasonKey).orElse(pip.langKey());
        }

        /**
         * {@link #statusKey()} for a row of a merge order (issue #57): running and done say merging and merged, every
         * other mark and every reason read as they do for optimising.
         */
        public String statusKey(boolean merge) {
            if (merge && reason.isEmpty() && pip == Pip.RUNNING)
                return WareworksLang.OPTIMIZER_PIP_MERGING;
            if (merge && reason.isEmpty() && pip == Pip.DONE)
                return WareworksLang.OPTIMIZER_PIP_MERGED;
            return statusKey();
        }

        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(row);
            buffer.writeBoolean(key.isPresent());
            key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
            writeBreakdown(buffer, breakdown);
            buffer.writeVarInt(pip.ordinal());
            // 0 for none, the reason's ordinal + 1 otherwise (issue #53).
            buffer.writeVarInt(reason.map(value -> value.ordinal() + 1).orElse(0));
            buffer.writeVarInt(places);
        }

        /**
         * Reads a row written by {@link #write}; an unknown pip ordinal falls back to {@link Pip#IDLE}, an unknown reason
         * to none.
         */
        public static RowView read(RegistryFriendlyByteBuf buffer) {
            int row = buffer.readVarInt();
            Optional<ItemKey> key = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                    : Optional.empty();
            PriorityBreakdown breakdown = readBreakdown(buffer);
            Pip pip = byOrdinal(Pip.values(), buffer.readVarInt());
            int reason = buffer.readVarInt() - 1;
            RelocationReason[] reasons = RelocationReason.values();
            Optional<RelocationReason> why = reason < 0 || reason >= reasons.length ? Optional.empty()
                    : Optional.of(reasons[reason]);
            return new RowView(row, key, breakdown, pip, why, buffer.readVarInt());
        }
    }

    /** The row with index {@code row}, if the state has one. */
    public Optional<RowView> row(int row) {
        for (RowView view : rows) {
            if (view.row() == row)
                return Optional.of(view);
        }
        return Optional.empty();
    }

    /** Writes this state; never more than {@value OptimizerRows#MAX_ROWS} rows. */
    public void write(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(rows.size(), OptimizerRows.MAX_ROWS);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            rows.get(i).write(buffer);
        buffer.writeBoolean(linked);
        buffer.writeBoolean(misaligned);
        buffer.writeVarInt(order.state().ordinal());
        buffer.writeVarLong(order.moved());
        buffer.writeVarInt(order.tripsLeft());
        buffer.writeBoolean(order.merge());
        buffer.writeVarInt(message.ordinal());
        buffer.writeVarInt(first);
        buffer.writeVarInt(second);
    }

    /** Reads a state written by {@link #write}; bounded, and unknown ordinals fall back to the first value. */
    public static OptimizerScreenState read(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), OptimizerRows.MAX_ROWS);
        List<RowView> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            rows.add(RowView.read(buffer));
        boolean linked = buffer.readBoolean();
        boolean misaligned = buffer.readBoolean();
        OrderState state = byOrdinal(OrderState.values(), buffer.readVarInt());
        long moved = buffer.readVarLong();
        int tripsLeft = buffer.readVarInt();
        boolean merge = buffer.readBoolean();
        Message message = byOrdinal(Message.values(), buffer.readVarInt());
        return new OptimizerScreenState(rows, linked, misaligned, new OrderLine(state, moved, tripsLeft, merge), message,
                buffer.readVarInt(), buffer.readVarInt());
    }

    /**
     * A breakdown on the wire: a mask of the priorities that hold anything, their counts, then the unloaded, free and
     * movable figures and the unknown flag. An empty row costs five bytes.
     */
    private static void writeBreakdown(RegistryFriendlyByteBuf buffer, PriorityBreakdown breakdown) {
        int mask = 0;
        for (int priority = PriorityBreakdown.MIN_PRIORITY; priority <= PriorityBreakdown.MAX_PRIORITY; priority++) {
            if (breakdown.countAt(priority) > 0)
                mask |= 1 << (priority - PriorityBreakdown.MIN_PRIORITY);
        }
        buffer.writeVarInt(mask);
        for (int priority = PriorityBreakdown.MIN_PRIORITY; priority <= PriorityBreakdown.MAX_PRIORITY; priority++) {
            if (breakdown.countAt(priority) > 0)
                buffer.writeVarLong(breakdown.countAt(priority));
        }
        buffer.writeVarLong(breakdown.unloaded());
        buffer.writeVarLong(breakdown.free());
        buffer.writeVarLong(breakdown.movable());
        buffer.writeBoolean(breakdown.movableUnknown());
    }

    private static PriorityBreakdown readBreakdown(RegistryFriendlyByteBuf buffer) {
        int mask = buffer.readVarInt();
        List<Long> counts = new ArrayList<>(PriorityBreakdown.PRIORITIES);
        for (int index = 0; index < PriorityBreakdown.PRIORITIES; index++)
            counts.add((mask & (1 << index)) != 0 ? buffer.readVarLong() : 0L);
        return new PriorityBreakdown(counts, buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(),
                buffer.readBoolean());
    }

    private static <E extends Enum<E>> E byOrdinal(E[] values, int ordinal) {
        return ordinal < 0 || ordinal >= values.length ? values[0] : values[ordinal];
    }
}
