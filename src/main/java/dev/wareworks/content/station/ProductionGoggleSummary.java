package dev.wareworks.content.station;

import java.util.Optional;

import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

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
 * @param feeder            whether a Warehouse Crafter Feeder is attached to this station's back (issue #34, ADR-067)
 * @param refusedPatterns   complete patterns the feeder refuses because they are no crafting recipe it can lay
 *                          ({@code CrafterPatternCheck#refused}); always 0 without a feeder
 * @param selector          the station's Recipe Selector (ADR-085), {@link SelectorSummary#NONE} without one
 */
public record ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
                                      long missingIngredients, long awaitedResult, int stoppedProducts,
                                      long unrecovered, boolean oldestWaitingForStep, boolean feeder,
                                      int refusedPatterns, SelectorSummary selector) {
    public static final ProductionGoggleSummary NONE =
            new ProductionGoggleSummary(0, 0, Optional.empty(), 0L, 0L, 0, 0L, false, false, 0);

    private static final String PATTERNS = "Patterns";
    private static final String OPEN_ORDERS = "OpenOrders";
    private static final String STATE = "State";
    private static final String MISSING = "Missing";
    private static final String AWAITED = "Awaited";
    private static final String STOPPED = "Stopped";
    private static final String UNRECOVERED = "Unrecovered";
    private static final String WAITING_FOR_STEP = "WaitingForStep";
    private static final String FEEDER = "Feeder";
    private static final String REFUSED = "Refused";
    private static final String SELECTOR = "Selector";

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
        // Refused patterns exist only for a feeder, by the same reasoning as the flag above.
        refusedPatterns = feeder ? Math.max(0, refusedPatterns) : 0;
        if (selector == null)
            selector = SelectorSummary.NONE;
    }

    /** A station without a Recipe Selector: the shape every summary had before ADR-085. */
    public ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
            long missingIngredients, long awaitedResult, int stoppedProducts, long unrecovered,
            boolean oldestWaitingForStep, boolean feeder, int refusedPatterns) {
        this(patterns, openOrders, oldestState, missingIngredients, awaitedResult, stoppedProducts, unrecovered,
                oldestWaitingForStep, feeder, refusedPatterns, SelectorSummary.NONE);
    }

    /** A station without a Crafter Feeder: the shape every summary had before issue #34. */
    public ProductionGoggleSummary(int patterns, int openOrders, Optional<ProductionOrderState> oldestState,
            long missingIngredients, long awaitedResult, int stoppedProducts, long unrecovered,
            boolean oldestWaitingForStep) {
        this(patterns, openOrders, oldestState, missingIngredients, awaitedResult, stoppedProducts, unrecovered,
                oldestWaitingForStep, false, 0);
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
        // And a station without a Crafter Feeder pays nothing for it either (issue #34).
        if (feeder) {
            tag.putBoolean(FEEDER, true);
            if (refusedPatterns > 0)
                tag.putInt(REFUSED, refusedPatterns);
        }
        // And a station without a Recipe Selector pays nothing for it (ADR-085).
        if (selector.on()) {
            CompoundTag selectorTag = new CompoundTag();
            selector.write(selectorTag);
            tag.put(SELECTOR, selectorTag);
        }
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static ProductionGoggleSummary read(CompoundTag tag) {
        Optional<ProductionOrderState> state = tag.contains(STATE, Tag.TAG_STRING)
                ? ProductionOrderState.byName(tag.getString(STATE)) : Optional.empty();
        return new ProductionGoggleSummary(tag.getInt(PATTERNS), tag.getInt(OPEN_ORDERS), state, tag.getLong(MISSING),
                tag.getLong(AWAITED), tag.getInt(STOPPED), tag.getLong(UNRECOVERED),
                tag.getBoolean(WAITING_FOR_STEP), tag.getBoolean(FEEDER), tag.getInt(REFUSED),
                tag.contains(SELECTOR, Tag.TAG_COMPOUND) ? SelectorSummary.read(tag.getCompound(SELECTOR))
                        : SelectorSummary.NONE);
    }

    /**
     * What the goggles of a Recipe Selector station add (ADR-085, design §12): numbers, item ids and enum names only,
     * like the rest of this tag, so it stays small and carries no item components.
     *
     * @param mode      how the station selects; {@link SelectorMode#NONE} for a station without a selector
     * @param selection the item id of the result the line is set to
     * @param epoch     how often the line switched
     * @param hold      whether the station holds until a player confirms
     * @param fault     the station's fault, if any
     * @param headWait  why the oldest waiting order waits ({@link SelectorWait#NONE} when nothing waits)
     * @param headItem  the item id that wait names
     * @param waiting   orders queued behind another result ({@link SelectorWait#queues()}, a hold excluded)
     */
    public record SelectorSummary(SelectorMode mode, Optional<ResourceLocation> selection, int epoch, boolean hold,
                                  Optional<SelectorWait> fault, SelectorWait headWait,
                                  Optional<ResourceLocation> headItem, int waiting) {
        public static final SelectorSummary NONE = new SelectorSummary(SelectorMode.NONE, Optional.empty(), 0, false,
                Optional.empty(), SelectorWait.NONE, Optional.empty(), 0);

        private static final String MODE = "Mode";
        private static final String SELECTION = "Selection";
        private static final String EPOCH = "Epoch";
        private static final String HOLD = "Hold";
        private static final String FAULT = "Fault";
        private static final String HEAD_WAIT = "HeadWait";
        private static final String HEAD_ITEM = "HeadItem";
        private static final String WAITING = "Waiting";

        public SelectorSummary {
            if (mode == null)
                mode = SelectorMode.NONE;
            if (selection == null)
                selection = Optional.empty();
            epoch = Math.max(0, epoch);
            if (fault == null)
                fault = Optional.empty();
            fault = fault.filter(wait -> wait != SelectorWait.NONE);
            if (headWait == null)
                headWait = SelectorWait.NONE;
            if (headItem == null || headWait == SelectorWait.NONE)
                headItem = Optional.empty();
            waiting = Math.max(0, waiting);
        }

        /** Whether the station selects at all. */
        public boolean on() {
            return mode.selects();
        }

        void write(CompoundTag tag) {
            tag.putString(MODE, mode.name());
            selection.ifPresent(id -> tag.putString(SELECTION, id.toString()));
            tag.putInt(EPOCH, epoch);
            if (hold)
                tag.putBoolean(HOLD, true);
            fault.ifPresent(wait -> tag.putString(FAULT, wait.name()));
            if (headWait != SelectorWait.NONE)
                tag.putString(HEAD_WAIT, headWait.name());
            headItem.ifPresent(id -> tag.putString(HEAD_ITEM, id.toString()));
            if (waiting > 0)
                tag.putInt(WAITING, waiting);
        }

        static SelectorSummary read(CompoundTag tag) {
            SelectorMode mode = SelectorMode.NONE;
            for (SelectorMode candidate : SelectorMode.values()) {
                if (candidate.name().equals(tag.getString(MODE)))
                    mode = candidate;
            }
            return new SelectorSummary(mode, id(tag, SELECTION), tag.getInt(EPOCH), tag.getBoolean(HOLD),
                    wait(tag.getString(FAULT)), wait(tag.getString(HEAD_WAIT)).orElse(SelectorWait.NONE),
                    id(tag, HEAD_ITEM), tag.getInt(WAITING));
        }

        private static Optional<ResourceLocation> id(CompoundTag tag, String key) {
            return tag.contains(key, Tag.TAG_STRING) ? Optional.ofNullable(ResourceLocation.tryParse(
                    tag.getString(key))) : Optional.empty();
        }

        private static Optional<SelectorWait> wait(String name) {
            for (SelectorWait wait : SelectorWait.values()) {
                if (wait != SelectorWait.NONE && wait.name().equals(name))
                    return Optional.of(wait);
            }
            return Optional.empty();
        }
    }
}
