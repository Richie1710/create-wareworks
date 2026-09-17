package dev.wareworks.content.station;

import java.util.Optional;

import dev.wareworks.core.production.ProductionOrderState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What the goggle tooltip of a warehouse production station shows beyond the shared station lines
 * ({@code docs/warehouse-system.md} §3.5): how many patterns it holds, how many production orders run here and where
 * the oldest of them stands.
 * <p>
 * Numbers and one enum name only, so the synced tag has a fixed size — the pattern <b>items</b> deliberately never
 * reach a client packet, because a block entity's update tag is part of every chunk packet and item components there
 * are the bug class {@code docs/architecture.md} (goggle data notes) forbids. The screen gets the patterns through its
 * own menu payload instead, for one player at a time.
 * <p>
 * Records compare by value, so the server syncs only visible changes. Negative values are clamped and reading never
 * throws.
 *
 * @param patterns          complete patterns this station holds
 * @param openOrders        production orders running at this station
 * @param oldestState       where the oldest order stands, empty without orders
 * @param missingIngredients items the open orders still have to fetch
 * @param awaitedResult     result items the open orders still wait for
 */
public record ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
                                      long missingIngredients, long awaitedResult) {
    public static final ProductionGoggleSummary NONE =
            new ProductionGoggleSummary(0, 0, Optional.empty(), 0L, 0L);

    private static final String PATTERNS = "Patterns";
    private static final String OPEN_ORDERS = "OpenOrders";
    private static final String STATE = "State";
    private static final String MISSING = "Missing";
    private static final String AWAITED = "Awaited";

    public ProductionGoggleSummary {
        patterns = Math.max(0, patterns);
        openOrders = Math.max(0, openOrders);
        if (oldestState == null)
            oldestState = Optional.empty();
        missingIngredients = Math.max(0L, missingIngredients);
        awaitedResult = Math.max(0L, awaitedResult);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putInt(PATTERNS, patterns);
        tag.putInt(OPEN_ORDERS, openOrders);
        tag.putLong(MISSING, missingIngredients);
        tag.putLong(AWAITED, awaitedResult);
        oldestState.ifPresent(state -> tag.putString(STATE, state.name()));
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static ProductionGoggleSummary read(CompoundTag tag) {
        Optional<ProductionOrderState> state = tag.contains(STATE, Tag.TAG_STRING)
                ? ProductionOrderState.byName(tag.getString(STATE)) : Optional.empty();
        return new ProductionGoggleSummary(tag.getInt(PATTERNS), tag.getInt(OPEN_ORDERS), state, tag.getLong(MISSING),
                tag.getLong(AWAITED));
    }
}
