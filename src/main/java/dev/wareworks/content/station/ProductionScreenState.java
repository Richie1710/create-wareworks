package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.terminal.PlanMember;
import net.minecraft.core.BlockPos;
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
 * {@value ProductionPatterns#ENTRIES_PER_PATTERN} entries each, {@value #MAX_ORDERS} orders and
 * {@value #MAX_CHOICE_VIEWS} filter views of {@value #MAX_MATCHES_SHOWN} matches each are ever written or read, whatever
 * a length prefix claims.
 *
 * @param patterns one entry list per pattern slot, in slot order
 * @param orders   the production orders at this station, newest last
 * @param stopped  the products of this station the <b>safety stop</b> is holding (M20, issue #4, ADR-032), in pattern
 *                 order. A station whose machine works carries an empty list and its screen reads exactly as it did
 *                 before; a station that has lost a batch is the one place a player can see it and lift it while
 *                 standing in front of the machine that swallowed it
 * @param feeder   whether a Warehouse Crafter Feeder is attached to this station's back (issue #34, ADR-067)
 * @param checks   the crafting recipe check of each pattern slot, in slot order; empty or
 *                 {@link CrafterPatternCheck#NOT_CHECKED} entries without a feeder. A slot past the end of the list
 *                 reads as not checked ({@link #check})
 * @param choices  what the filter cells of the patterns would take (ADR-082): one view per filter of a slot, in slot
 *                 order, at most {@value #MAX_CHOICE_VIEWS}. Empty for a station without a filter cell, and a filter
 *                 past the bound has no view: the screen then draws the filter itself ({@link #choice})
 * @param selector the station's Recipe Selector (ADR-085): mode, selection, hold and fault; {@link SelectorView#NONE}
 *                 for a station without one, which then reads exactly as before
 * @param selectorChecks the Recipe Selector pattern check of each slot, in slot order (ADR-085, design §10); empty for
 *                 a station without a selector or whose machine is not known yet, and an empty entry for a slot that
 *                 was not checked. A slot past the end of the list reads as not checked ({@link #selectorCheck})
 */
public record ProductionScreenState(List<PatternView> patterns, List<OrderView> orders,
                                    List<StoppedProduct> stopped, boolean feeder, List<PatternCheckView> checks,
                                    List<ChoiceView> choices, SelectorView selector,
                                    List<Optional<SelectorRecipeCheck.Verdict>> selectorChecks) {
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

    /**
     * Filter views one payload carries (ADR-082), in slot order. A station whose filters go past it would be very
     * unusual; a filter without a view shows only itself and its amount.
     */
    public static final int MAX_CHOICE_VIEWS = 64;

    /** Stock matches a filter view names; the rest is a number ({@link ChoiceView#more()}). */
    public static final int MAX_MATCHES_SHOWN = 4;

    /** Items an order line names that a filter chose ({@link OrderView#uses()}); the rest is a number. */
    public static final int MAX_USES_SHOWN = 4;

    /** Longest enum name a payload may claim (ADR-085: selector waits, modes, causes and kinds travel by name). */
    public static final int MAX_ENUM_NAME_LENGTH = 32;

    public static final ProductionScreenState NONE =
            new ProductionScreenState(List.of(), List.of(), List.of());

    public ProductionScreenState {
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        stopped = List.copyOf(Objects.requireNonNull(stopped, "stopped"));
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        Objects.requireNonNull(selector, "selector");
        selectorChecks = List.copyOf(Objects.requireNonNull(selectorChecks, "selectorChecks"));
        if (selectorChecks.size() > ProductionPatterns.MAX_SLOTS)
            selectorChecks = selectorChecks.subList(0, ProductionPatterns.MAX_SLOTS);
    }

    /**
     * A station without a Recipe Selector: the shape every call site used before ADR-085, so a caller that says nothing
     * about a selector keeps describing a station that makes every order as it comes.
     */
    public ProductionScreenState(List<PatternView> patterns, List<OrderView> orders, List<StoppedProduct> stopped,
            boolean feeder, List<PatternCheckView> checks, List<ChoiceView> choices) {
        this(patterns, orders, stopped, feeder, checks, choices, SelectorView.NONE, List.of());
    }

    /**
     * A station without a filter cell: the shape every call site used before ADR-082, so a caller that says nothing about
     * filters keeps describing patterns of exact items.
     */
    public ProductionScreenState(List<PatternView> patterns, List<OrderView> orders, List<StoppedProduct> stopped,
            boolean feeder, List<PatternCheckView> checks) {
        this(patterns, orders, stopped, feeder, checks, List.of());
    }

    /**
     * A station without a Crafter Feeder: the shape every call site used before issue #34, so a caller that says nothing
     * about the feeder keeps describing a station whose patterns nobody checks.
     */
    public ProductionScreenState(List<PatternView> patterns, List<OrderView> orders, List<StoppedProduct> stopped) {
        this(patterns, orders, stopped, false, List.of());
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
     * @param entry  the entry index: a grid cell, or {@value ProductionPatterns#RESULT_ENTRY} for the result
     * @param key    the item; for a filter cell the filter
     * @param count  items per run
     * @param filter whether this is a <b>filter cell</b> (ADR-082): any item the filter {@code key} allows pays for it
     */
    public record EntryView(int entry, ItemKey key, int count, boolean filter) {
        public EntryView {
            Objects.requireNonNull(key, "key");
        }

        /** An exact entry: every entry was one before ADR-082. */
        public EntryView(int entry, ItemKey key, int count) {
            this(entry, key, count, false);
        }
    }

    /**
     * One item in stock a filter cell allows, as its tooltip lists it (ADR-082).
     *
     * @param key       the item
     * @param available how many of it a player's click may have, the reserve included as at the terminal
     */
    public record MatchView(ItemKey key, long available) {
        public MatchView {
            Objects.requireNonNull(key, "key");
            available = Math.max(0L, available);
        }
    }

    /**
     * What one filter of one pattern slot would take right now (ADR-082). The server builds it from the aisle's stock in
     * the planner's own pick order ({@code ChoiceAllocator#ranked}: plain items first, then the most free stock, then the
     * most a player may have), so the cell says what an order placed now would pick.
     *
     * @param slot           the pattern slot
     * @param filter         the filter, as the slot's cells hold it
     * @param representative what the cell is drawn as: the first match, else the first item the filter names; empty when
     *                       there is neither, and the screen draws the filter itself
     * @param matches        the first {@value #MAX_MATCHES_SHOWN} matches in pick order
     * @param more           how many more matches there are
     * @param shortOfRun     whether there are matches but none of them alone pays one run: kinds are mixed only in whole
     *                       runs
     * @param deny           whether the filter is a deny filter, which allows anything in stock it does not name
     * @param crafterLimited whether a Crafter Feeder narrows the matches to what the Crafter takes in this grid
     * @param perRun         items of this filter one run takes: every cell of it in the slot together
     */
    public record ChoiceView(int slot, ItemKey filter, Optional<ItemKey> representative, List<MatchView> matches,
                             int more, boolean shortOfRun, boolean deny, boolean crafterLimited, int perRun) {
        public ChoiceView {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(representative, "representative");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            if (matches.size() > MAX_MATCHES_SHOWN)
                matches = matches.subList(0, MAX_MATCHES_SHOWN);
            more = Math.max(0, more);
            perRun = Math.max(0, perRun);
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(slot);
            ItemKey.STREAM_CODEC.encode(buffer, filter);
            buffer.writeBoolean(representative.isPresent());
            representative.ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
            buffer.writeVarInt(matches.size());
            for (MatchView match : matches) {
                ItemKey.STREAM_CODEC.encode(buffer, match.key());
                buffer.writeVarLong(match.available());
            }
            buffer.writeVarInt(more);
            buffer.writeBoolean(shortOfRun);
            buffer.writeBoolean(deny);
            buffer.writeBoolean(crafterLimited);
            buffer.writeVarInt(perRun);
        }

        static ChoiceView read(RegistryFriendlyByteBuf buffer) {
            int slot = buffer.readVarInt();
            ItemKey filter = ItemKey.STREAM_CODEC.decode(buffer);
            Optional<ItemKey> representative = buffer.readBoolean()
                    ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty();
            int matchCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_MATCHES_SHOWN);
            List<MatchView> matches = new ArrayList<>(matchCount);
            for (int i = 0; i < matchCount; i++) {
                ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
                matches.add(new MatchView(key, buffer.readVarLong()));
            }
            int more = buffer.readVarInt();
            boolean shortOfRun = buffer.readBoolean();
            boolean deny = buffer.readBoolean();
            boolean crafterLimited = buffer.readBoolean();
            return new ChoiceView(slot, filter, representative, matches, more, shortOfRun, deny, crafterLimited,
                    buffer.readVarInt());
        }
    }

    /**
     * The crafting recipe check of one pattern slot while a Crafter Feeder is attached (issue #34, ADR-067).
     *
     * @param check        the verdict; it travels by ordinal ({@link CrafterPatternCheck#byOrdinal})
     * @param recipeResult what the recipe really makes, for {@link CrafterPatternCheck#RESULT_DIFFERS} only, as the
     *                     result entry of the slot
     */
    public record PatternCheckView(CrafterPatternCheck check, Optional<EntryView> recipeResult) {
        public static final PatternCheckView NOT_CHECKED =
                new PatternCheckView(CrafterPatternCheck.NOT_CHECKED, Optional.empty());

        public PatternCheckView {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(recipeResult, "recipeResult");
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(check.ordinal());
            buffer.writeBoolean(recipeResult.isPresent());
            if (recipeResult.isPresent()) {
                ItemKey.STREAM_CODEC.encode(buffer, recipeResult.get().key());
                buffer.writeVarInt(recipeResult.get().count());
            }
        }

        static PatternCheckView read(RegistryFriendlyByteBuf buffer) {
            CrafterPatternCheck check = CrafterPatternCheck.byOrdinal(buffer.readVarInt());
            if (!buffer.readBoolean())
                return new PatternCheckView(check, Optional.empty());
            ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
            return new PatternCheckView(check, Optional.of(new EntryView(ProductionPatterns.RESULT_ENTRY, key,
                    buffer.readVarInt())));
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
     * @param uses      the items a filter cell chose for this order (ADR-082): one per supply line with a filter, in
     *                  line order, with the line's whole amount, at most {@value #MAX_USES_SHOWN}. Empty for an order of
     *                  exact ingredients, which shows nothing new
     * @param usesMore  how many more such lines the order has
     * @param selectorWait why the order fetches nothing (or only one run) at its Recipe Selector station (ADR-085,
     *                  design §5.1): {@link SelectorWait#NONE} for an order at an ordinary station, which reads as before
     * @param selectorItem the item the wait text names: the result that goes first ({@code BEHIND}, {@code LINE_BUSY},
     *                  {@code PROBE_BEHIND}) or, for a {@code HOLD}, what came back through the return input that the
     *                  station does not make; empty otherwise (a hold without an item lost ingredients in the line)
     */
    public record OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced,
                            long missing, long delivered, Optional<UUID> plan, int depth, Optional<String> address,
                            boolean waitingForStep, List<EntryView> uses, int usesMore, SelectorWait selectorWait,
                            Optional<ItemKey> selectorItem) {
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
            uses = List.copyOf(Objects.requireNonNull(uses, "uses"));
            if (uses.size() > MAX_USES_SHOWN)
                uses = uses.subList(0, MAX_USES_SHOWN);
            usesMore = Math.max(0, usesMore);
            Objects.requireNonNull(selectorWait, "selectorWait");
            Objects.requireNonNull(selectorItem, "selectorItem");
            if (selectorWait == SelectorWait.NONE)
                selectorItem = Optional.empty();
        }

        /** An order at a station without a Recipe Selector: every order was one before ADR-085. */
        public OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced, long missing,
                long delivered, Optional<UUID> plan, int depth, Optional<String> address, boolean waitingForStep,
                List<EntryView> uses, int usesMore) {
            this(id, state, result, amount, produced, missing, delivered, plan, depth, address, waitingForStep, uses,
                    usesMore, SelectorWait.NONE, Optional.empty());
        }

        /** An order no filter chose an item for: every order was one before ADR-082. */
        public OrderView(UUID id, ProductionOrderState state, ItemKey result, int amount, long produced, long missing,
                long delivered, Optional<UUID> plan, int depth, Optional<String> address, boolean waitingForStep) {
            this(id, state, result, amount, produced, missing, delivered, plan, depth, address, waitingForStep,
                    List.of(), 0);
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
            // Appended (ADR-082): an order of exact ingredients pays two zero bytes.
            buffer.writeVarInt(uses.size());
            for (EntryView use : uses) {
                ItemKey.STREAM_CODEC.encode(buffer, use.key());
                buffer.writeVarInt(use.count());
            }
            buffer.writeVarInt(usesMore);
            // Appended (ADR-085): an order at a station without a Recipe Selector pays one zero byte.
            buffer.writeBoolean(selectorWait != SelectorWait.NONE);
            if (selectorWait != SelectorWait.NONE) {
                buffer.writeUtf(selectorWait.name(), MAX_ENUM_NAME_LENGTH);
                writeKey(buffer, selectorItem);
            }
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
            boolean waitingForStep = buffer.readBoolean();
            int useCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_USES_SHOWN);
            List<EntryView> uses = new ArrayList<>(useCount);
            for (int i = 0; i < useCount; i++) {
                ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
                uses.add(new EntryView(i, key, buffer.readVarInt()));
            }
            int usesMore = buffer.readVarInt();
            SelectorWait selectorWait = SelectorWait.NONE;
            Optional<ItemKey> selectorItem = Optional.empty();
            if (buffer.readBoolean()) {
                selectorWait = waitByName(buffer.readUtf(MAX_ENUM_NAME_LENGTH));
                selectorItem = readKey(buffer);
            }
            return new OrderView(id, state, result, amount, produced, missing, delivered, plan, depth, address,
                    waitingForStep, uses, usesMore, selectorWait, selectorItem);
        }

        /**
         * Whether the order waits at its Recipe Selector station for something a player has to fix (ADR-085): drawn in
         * the error colour. A queue (grey) and a wait that runs out by itself are not faults.
         */
        public boolean selectorFault() {
            return isSelectorFault(selectorWait);
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
                buffer.writeBoolean(entry.filter());
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
        // Appended after the stopped products (issue #34): a station without a Crafter Feeder pays two zero bytes.
        buffer.writeBoolean(feeder);
        int checkCount = Math.min(checks.size(), ProductionPatterns.MAX_SLOTS);
        buffer.writeVarInt(checkCount);
        for (int i = 0; i < checkCount; i++)
            checks.get(i).write(buffer);
        // Appended after the checks (ADR-082): a station without a filter cell pays one zero byte.
        int choiceCount = Math.min(choices.size(), MAX_CHOICE_VIEWS);
        buffer.writeVarInt(choiceCount);
        for (int i = 0; i < choiceCount; i++)
            choices.get(i).write(buffer);
        // Appended after the filter views (ADR-085): a station without a Recipe Selector pays two zero bytes.
        selector.write(buffer);
        int selectorCheckCount = Math.min(selectorChecks.size(), ProductionPatterns.MAX_SLOTS);
        buffer.writeVarInt(selectorCheckCount);
        for (int i = 0; i < selectorCheckCount; i++)
            buffer.writeVarInt(selectorChecks.get(i).map(verdict -> verdict.ordinal() + 1).orElse(0));
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
                int count = buffer.readVarInt();
                entries.add(new EntryView(index, key, count, buffer.readBoolean()));
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
        boolean feeder = buffer.readBoolean();
        int checkCount = Math.min(Math.max(0, buffer.readVarInt()), ProductionPatterns.MAX_SLOTS);
        List<PatternCheckView> checks = new ArrayList<>(checkCount);
        for (int i = 0; i < checkCount; i++)
            checks.add(PatternCheckView.read(buffer));
        int choiceCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_CHOICE_VIEWS);
        List<ChoiceView> choices = new ArrayList<>(choiceCount);
        for (int i = 0; i < choiceCount; i++)
            choices.add(ChoiceView.read(buffer));
        SelectorView selector = SelectorView.read(buffer);
        int selectorCheckCount = Math.min(Math.max(0, buffer.readVarInt()), ProductionPatterns.MAX_SLOTS);
        List<Optional<SelectorRecipeCheck.Verdict>> selectorChecks = new ArrayList<>(selectorCheckCount);
        SelectorRecipeCheck.Verdict[] verdicts = SelectorRecipeCheck.Verdict.values();
        for (int i = 0; i < selectorCheckCount; i++) {
            int value = buffer.readVarInt();
            selectorChecks.add(value <= 0 || value > verdicts.length ? Optional.empty()
                    : Optional.of(verdicts[value - 1]));
        }
        return new ProductionScreenState(patterns, orders, stopped, feeder, checks, choices, selector,
                selectorChecks);
    }

    /**
     * The Recipe Selector check of pattern slot {@code pattern} (ADR-085) if the machine would not make it exactly, i.e.
     * the reason the warehouse never orders it at this station; empty for a slot that passed or was not checked.
     */
    public Optional<SelectorRecipeCheck.Verdict> selectorRefusal(int pattern) {
        return selectorCheck(pattern).filter(verdict -> verdict != SelectorRecipeCheck.Verdict.VALID);
    }

    /** The Recipe Selector check of pattern slot {@code pattern}; empty when it was not checked. */
    public Optional<SelectorRecipeCheck.Verdict> selectorCheck(int pattern) {
        return pattern < 0 || pattern >= selectorChecks.size() ? Optional.empty() : selectorChecks.get(pattern);
    }

    /** Writes an optional item: a flag, then the key. */
    static void writeKey(RegistryFriendlyByteBuf buffer, Optional<ItemKey> key) {
        buffer.writeBoolean(key.isPresent());
        key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
    }

    /** Reads an optional item written by {@link #writeKey}. */
    static Optional<ItemKey> readKey(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty();
    }

    /** The selector wait a payload names; {@link SelectorWait#NONE} for a name this build does not know. */
    static SelectorWait waitByName(String name) {
        for (SelectorWait wait : SelectorWait.values()) {
            if (wait.name().equals(name))
                return wait;
        }
        return SelectorWait.NONE;
    }

    /**
     * Whether {@code wait} is something a player has to act on (ADR-085, design §12), drawn in the error colour: a hold
     * and every fault. Not a queue behind another result, and not a wait that runs out by itself (the buffer, a crane
     * trip, a Packager, a busy machine, a switch being made).
     */
    public static boolean isSelectorFault(SelectorWait wait) {
        return switch (wait) {
            case NONE, PROBE_ONE_RUN, BEHIND, LINE_BUSY, PROBE_BEHIND, BUFFER_NOT_EMPTY, CRANE_INBOUND, PACKAGER_BUSY,
                    SWITCHING, ARMING, MACHINE_BUSY -> false;
            default -> true;
        };
    }

    /**
     * What a production station's screen shows of its Recipe Selector (ADR-085, design §13). Built by the server from
     * the station's own state, so the screen never guesses.
     *
     * @param mode         how the station selects; {@link SelectorMode#NONE} for a station without a selector
     * @param selection    the result the line is set to, empty before its first switch
     * @param epoch        how often the line switched
     * @param hold         why the station holds, if it does
     * @param holdItem     the item a hold names: what came back unexpectedly, else the result the line was making
     * @param fault        the station's fault that stops its orders (no selector, no return input, ...), if any
     * @param linked       local mode: where the registered selector stands
     * @param kind         the machine the selector serves, as far as the station knows it
     * @param returnInput  the station's return input
     * @param slipsTrusted slip mode: whether a probe has shown the far factory to work since the last switch
     * @param engaged      whether an order of the station has handed ingredients over: switching off is refused then
     */
    public record SelectorView(SelectorMode mode, Optional<ItemKey> selection, int epoch,
                               Optional<SelectorHold.Cause> hold, Optional<ItemKey> holdItem,
                               Optional<SelectorWait> fault, Optional<BlockPos> linked,
                               Optional<SelectorMachine.Kind> kind, Optional<BlockPos> returnInput,
                               boolean slipsTrusted, boolean engaged) {
        public static final SelectorView NONE = new SelectorView(SelectorMode.NONE, Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, false);

        public SelectorView {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(selection, "selection");
            epoch = Math.max(0, epoch);
            Objects.requireNonNull(hold, "hold");
            Objects.requireNonNull(holdItem, "holdItem");
            Objects.requireNonNull(fault, "fault");
            fault = fault.filter(wait -> wait != SelectorWait.NONE);
            Objects.requireNonNull(linked, "linked");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(returnInput, "returnInput");
            if (hold.isEmpty())
                holdItem = Optional.empty();
        }

        /** Whether the station selects at all; the view of a station that does not is {@link #NONE}. */
        public boolean on() {
            return mode.selects();
        }

        /** Whether the station is held until a player confirms (design §8). */
        public boolean held() {
            return hold.isPresent();
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(on());
            if (!on())
                return;
            buffer.writeUtf(mode.name(), MAX_ENUM_NAME_LENGTH);
            writeKey(buffer, selection);
            buffer.writeVarInt(epoch);
            buffer.writeOptional(hold, (out, cause) -> out.writeUtf(cause.name(), MAX_ENUM_NAME_LENGTH));
            writeKey(buffer, holdItem);
            buffer.writeOptional(fault, (out, wait) -> out.writeUtf(wait.name(), MAX_ENUM_NAME_LENGTH));
            buffer.writeOptional(linked, (out, pos) -> out.writeLong(pos.asLong()));
            buffer.writeOptional(kind, (out, value) -> out.writeUtf(value.name(), MAX_ENUM_NAME_LENGTH));
            buffer.writeOptional(returnInput, (out, pos) -> out.writeLong(pos.asLong()));
            buffer.writeBoolean(slipsTrusted);
            buffer.writeBoolean(engaged);
        }

        static SelectorView read(RegistryFriendlyByteBuf buffer) {
            if (!buffer.readBoolean())
                return NONE;
            String modeName = buffer.readUtf(MAX_ENUM_NAME_LENGTH);
            SelectorMode mode = SelectorMode.NONE;
            for (SelectorMode candidate : SelectorMode.values()) {
                if (candidate.name().equals(modeName))
                    mode = candidate;
            }
            Optional<ItemKey> selection = readKey(buffer);
            int epoch = buffer.readVarInt();
            Optional<String> holdName = buffer.readOptional(in -> in.readUtf(MAX_ENUM_NAME_LENGTH));
            Optional<ItemKey> holdItem = readKey(buffer);
            Optional<SelectorWait> fault = buffer.readOptional(in -> in.readUtf(MAX_ENUM_NAME_LENGTH))
                    .map(ProductionScreenState::waitByName);
            Optional<BlockPos> linked = buffer.readOptional(in -> BlockPos.of(in.readLong()));
            Optional<SelectorMachine.Kind> kind = buffer.readOptional(in -> in.readUtf(MAX_ENUM_NAME_LENGTH))
                    .flatMap(SelectorMachine.Kind::byName);
            Optional<BlockPos> returnInput = buffer.readOptional(in -> BlockPos.of(in.readLong()));
            boolean slipsTrusted = buffer.readBoolean();
            boolean engaged = buffer.readBoolean();
            Optional<SelectorHold.Cause> hold = Optional.empty();
            for (SelectorHold.Cause cause : SelectorHold.Cause.values()) {
                if (holdName.filter(cause.name()::equals).isPresent())
                    hold = Optional.of(cause);
            }
            return new SelectorView(mode, selection, epoch, hold, holdItem, fault, linked, kind, returnInput,
                    slipsTrusted, engaged);
        }
    }

    /**
     * The view of filter {@code filter} in pattern slot {@code pattern} (ADR-082), if the server sent one: past
     * {@value #MAX_CHOICE_VIEWS} views it sends none for the rest.
     */
    public Optional<ChoiceView> choice(int pattern, ItemKey filter) {
        if (filter == null)
            return Optional.empty();
        for (ChoiceView view : choices) {
            if (view.slot() == pattern && view.filter().equals(filter))
                return Optional.of(view);
        }
        return Optional.empty();
    }

    /** The crafting recipe check of pattern slot {@code pattern}; not checked without a feeder or past the list. */
    public PatternCheckView check(int pattern) {
        return pattern < 0 || pattern >= checks.size() ? PatternCheckView.NOT_CHECKED : checks.get(pattern);
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
