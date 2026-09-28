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
 * @param stoppedProducts   products of this station the <b>safety stop</b> is holding (M20, issue #4, ADR-032). The
 *                          items themselves stay out of this tag on purpose — it is a number, like everything else
 *                          here — and the screen names them for the one player who opens it
 * @param unrecovered       ingredient items those stops cost together: what the machines behind this station were given
 *                          and never gave back ({@code docs/warehouse-system.md} §3.5.4)
 * @param oldestWaitingForStep whether {@link #oldestState()} belongs to an order of a chain that is fetching nothing
 *                          because an earlier step of its own plan is still running (M20, issue #4, ADR-032). Such an
 *                          order is nominally {@code WAITING_FOR_INGREDIENTS}, so without this flag the goggle line
 *                          would read as a crane that has forgotten it — and it would contradict the very same order's
 *                          row in the terminal's step panel and in this station's own screen
 */
public record ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
                                      long missingIngredients, long awaitedResult, int stoppedProducts,
                                      long unrecovered, boolean oldestWaitingForStep) {
    public static final ProductionGoggleSummary NONE =
            new ProductionGoggleSummary(0, 0, Optional.empty(), 0L, 0L, 0, 0L, false);

    private static final String PATTERNS = "Patterns";
    private static final String OPEN_ORDERS = "OpenOrders";
    private static final String STATE = "State";
    private static final String MISSING = "Missing";
    private static final String AWAITED = "Awaited";
    private static final String STOPPED = "Stopped";
    private static final String UNRECOVERED = "Unrecovered";
    private static final String WAITING_FOR_STEP = "WaitingForStep";

    public ProductionGoggleSummary {
        patterns = Math.max(0, patterns);
        openOrders = Math.max(0, openOrders);
        if (oldestState == null)
            oldestState = Optional.empty();
        missingIngredients = Math.max(0L, missingIngredients);
        awaitedResult = Math.max(0L, awaitedResult);
        stoppedProducts = Math.max(0, stoppedProducts);
        unrecovered = Math.max(0L, unrecovered);
        // A flag about an order nobody reported says nothing, and clearing it here keeps two summaries that show the
        // same tooltip equal to each other (records compare by value, and equality is what gates the sync).
        oldestWaitingForStep = oldestWaitingForStep && oldestState.isPresent();
    }

    /**
     * A station nothing of whose products is stopped, i.e. the shape every summary had before M20: what a warehouse that
     * has never lost a batch reports, and the form the pre-M20 call sites keep using.
     */
    public ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
            long missingIngredients, long awaitedResult) {
        this(patterns, openOrders, oldestState, missingIngredients, awaitedResult, 0, 0L, false);
    }

    /** A station with stopped products whose oldest order waits for nothing but its own ingredients. */
    public ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
            long missingIngredients, long awaitedResult, int stoppedProducts, long unrecovered) {
        this(patterns, openOrders, oldestState, missingIngredients, awaitedResult, stoppedProducts, unrecovered,
                false);
    }

    /** Whether the safety stop is holding anything this station makes. */
    public boolean anyStopped() {
        return stoppedProducts > 0;
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putInt(PATTERNS, patterns);
        tag.putInt(OPEN_ORDERS, openOrders);
        tag.putLong(MISSING, missingIngredients);
        tag.putLong(AWAITED, awaitedResult);
        oldestState.ifPresent(state -> tag.putString(STATE, state.name()));
        // Only written while something really is stopped, so the tag of a working station keeps the size it had (M20).
        if (stoppedProducts > 0) {
            tag.putInt(STOPPED, stoppedProducts);
            tag.putLong(UNRECOVERED, unrecovered);
        }
        // By the same rule: an aisle that runs no chains pays nothing for this flag (M20).
        if (oldestWaitingForStep)
            tag.putBoolean(WAITING_FOR_STEP, true);
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static ProductionGoggleSummary read(CompoundTag tag) {
        Optional<ProductionOrderState> state = tag.contains(STATE, Tag.TAG_STRING)
                ? ProductionOrderState.byName(tag.getString(STATE)) : Optional.empty();
        return new ProductionGoggleSummary(tag.getInt(PATTERNS), tag.getInt(OPEN_ORDERS), state, tag.getLong(MISSING),
                tag.getLong(AWAITED), tag.getInt(STOPPED), tag.getLong(UNRECOVERED),
                tag.getBoolean(WAITING_FOR_STEP));
    }
}
