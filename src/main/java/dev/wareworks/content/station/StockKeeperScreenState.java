package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRuleStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * What a warehouse stock keeper's screen is shown ({@code docs/warehouse-system.md} §3.6, M15): one line per rule row
 * and the one correction the last edit needed.
 * <p>
 * This travels as a <b>menu payload</b> to the one player who has the screen open, never in the block entity's update
 * tag: a rule's item is an {@link ItemKey} with data components, and an update tag is part of every chunk packet
 * ({@code docs/architecture.md}). The goggles get numbers only ({@link StockKeeperGoggleSummary}).
 * <p>
 * The rows carry the <b>server's</b> numbers, both the stored ones and what the warehouse currently holds, so the
 * screen never computes a rule's state itself and can never disagree with the controller that enforces it. Both
 * directions are bounded: at most {@value StockKeeperRules#MAX_ROWS} rows are ever written or read, whatever a length
 * prefix claims, and an unknown status or adjustment ordinal falls back to the first value instead of throwing.
 *
 * @param rows       one entry per rule row of the keeper, in row order
 * @param linked     whether a loaded controller reads these rules at all ("Not part of a warehouse" otherwise)
 * @param adjustment the correction the last accepted edit needed, for the screen's status line
 * @param adjustedRow the row that correction happened in, or -1 when there was none
 * @param resumed    whether the last accepted edit lifted a rule's safety stop (M15 part 2), so the screen can say
 *                   that the warehouse will order that item again instead of silently re-arming
 */
public record StockKeeperScreenState(List<RowView> rows, boolean linked, StockRuleAdjustment adjustment,
                                     int adjustedRow, boolean resumed) {
    /** {@link #adjustedRow()} of a state whose last edit needed no correction. */
    public static final int NO_ROW = -1;

    /** No keeper known yet: what a screen renders until the first payload arrives. */
    public static final StockKeeperScreenState NONE =
            new StockKeeperScreenState(List.of(), false, StockRuleAdjustment.NONE, NO_ROW, false);

    public StockKeeperScreenState {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (adjustment == null)
            adjustment = StockRuleAdjustment.NONE;
        if (adjustment == StockRuleAdjustment.NONE || adjustedRow < 0)
            adjustedRow = NO_ROW;
    }

    /**
     * One rule row as the screen shows it.
     *
     * @param row      the row index, so a click can name exactly this row to the server
     * @param key      the item the rule governs, empty for a row a player has not filled yet
     * @param minimum  the stored minimum ({@code StockRule.UNSET} for "off")
     * @param maximum  the stored maximum ({@code StockRule.UNSET} for "no cap")
     * @param reserve  the stored reserve ({@code StockRule.UNSET} for "off")
     * @param status   what this rule is doing right now, as the controller judged it
     * @param stocked  how many of the item the aisle holds
     * @param available how many of them a player at a terminal could still ask for
     * @param heldBack how many of the available items the reserve keeps from the warehouse's own automation
     * @param shortfall how many items the warehouse is short of the minimum
     * @param unrecovered ingredient items an automatic order of this rule handed to a machine and never got back, for
     *                  a row the safety stop is holding ({@code StockRuleStatus.PAUSED}, M15 part 2); 0 otherwise.
     *                  It is what the screen names when it says why the rule stopped ordering
     * @param restock   what automatic restocking last decided about this rule (M15 part 2). The status folds several of
     *                  those answers back into a bare {@code BELOW_MINIMUM} — switched off, no pattern, the queue busy,
     *                  no room for a whole run — so the outcome is the only thing that says <i>which</i> one it is
     * @param missingIngredient the item the rule is waiting for a player to supply, for
     *                  {@code RestockOutcome.WAITING_FOR_INGREDIENTS}; empty otherwise
     */
    public record RowView(int row, Optional<ItemKey> key, long minimum, long maximum, long reserve,
                          StockRuleStatus status, long stocked, long available, long heldBack, long shortfall,
                          long unrecovered, RestockOutcome restock, Optional<ItemKey> missingIngredient) {
        public RowView {
            if (key == null)
                key = Optional.empty();
            if (status == null)
                status = StockRuleStatus.NO_ITEM;
            if (restock == null)
                restock = RestockOutcome.NOT_GOVERNING;
            if (missingIngredient == null)
                missingIngredient = Optional.empty();
            row = Math.max(0, row);
            stocked = Math.max(0L, stocked);
            available = Math.max(0L, available);
            heldBack = Math.max(0L, heldBack);
            shortfall = Math.max(0L, shortfall);
            unrecovered = Math.max(0L, unrecovered);
        }

        /** An empty row: no item, no numbers, nothing to report. */
        public static RowView empty(int row) {
            return new RowView(row, Optional.empty(), -1L, -1L, -1L, StockRuleStatus.NO_ITEM, 0L, 0L, 0L, 0L, 0L,
                    RestockOutcome.NOT_GOVERNING, Optional.empty());
        }

        /**
         * Whether this row has something to say about automatic restocking beyond what its status already says. A row
         * of a warehouse that never restocks has not, which is why such a screen reads exactly as it did before.
         */
        public boolean hasRestockLine() {
            return key.isPresent() && restock != RestockOutcome.NOT_GOVERNING
                    && restock != RestockOutcome.SATISFIED;
        }

        /** Whether the safety stop is holding this rule, so the screen offers the way back (M15 part 2). */
        public boolean paused() {
            return status.isPaused();
        }

        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(row);
            buffer.writeBoolean(key.isPresent());
            key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
            buffer.writeVarLong(minimum);
            buffer.writeVarLong(maximum);
            buffer.writeVarLong(reserve);
            buffer.writeVarInt(status.ordinal());
            buffer.writeVarLong(stocked);
            buffer.writeVarLong(available);
            buffer.writeVarLong(heldBack);
            buffer.writeVarLong(shortfall);
            buffer.writeVarLong(unrecovered);
            // Appended, and on the wire by ordinal like the status above it: a new value goes at the end of the enum
            // and the network version is bumped with it ({@code WareworksNetwork#VERSION}).
            buffer.writeVarInt(restock.ordinal());
            buffer.writeBoolean(missingIngredient.isPresent());
            missingIngredient.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
        }

        /** Reads a row written by {@link #write}; an unknown status ordinal falls back to the first status. */
        public static RowView read(RegistryFriendlyByteBuf buffer) {
            int row = buffer.readVarInt();
            Optional<ItemKey> key = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                    : Optional.empty();
            long minimum = buffer.readVarLong();
            long maximum = buffer.readVarLong();
            long reserve = buffer.readVarLong();
            StockRuleStatus[] statuses = StockRuleStatus.values();
            int ordinal = buffer.readVarInt();
            StockRuleStatus status = ordinal < 0 || ordinal >= statuses.length ? statuses[0] : statuses[ordinal];
            long stocked = buffer.readVarLong();
            long available = buffer.readVarLong();
            long heldBack = buffer.readVarLong();
            long shortfall = buffer.readVarLong();
            long unrecovered = buffer.readVarLong();
            RestockOutcome[] outcomes = RestockOutcome.values();
            int restockOrdinal = buffer.readVarInt();
            RestockOutcome restock = restockOrdinal < 0 || restockOrdinal >= outcomes.length ? outcomes[0]
                    : outcomes[restockOrdinal];
            Optional<ItemKey> missing = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                    : Optional.empty();
            return new RowView(row, key, minimum, maximum, reserve, status, stocked, available, heldBack, shortfall,
                    unrecovered, restock, missing);
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

    /** Writes this state; never more than {@value StockKeeperRules#MAX_ROWS} rows. */
    public void write(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(rows.size(), StockKeeperRules.MAX_ROWS);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            rows.get(i).write(buffer);
        buffer.writeBoolean(linked);
        buffer.writeVarInt(adjustment.ordinal());
        buffer.writeVarInt(adjustedRow);
        buffer.writeBoolean(resumed);
    }

    /** Reads a state written by {@link #write}; bounded, and unknown ordinals fall back to the first value. */
    public static StockKeeperScreenState read(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), StockKeeperRules.MAX_ROWS);
        List<RowView> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            rows.add(RowView.read(buffer));
        boolean linked = buffer.readBoolean();
        StockRuleAdjustment[] adjustments = StockRuleAdjustment.values();
        int ordinal = buffer.readVarInt();
        StockRuleAdjustment adjustment = ordinal < 0 || ordinal >= adjustments.length ? adjustments[0]
                : adjustments[ordinal];
        return new StockKeeperScreenState(rows, linked, adjustment, buffer.readVarInt(), buffer.readBoolean());
    }
}
