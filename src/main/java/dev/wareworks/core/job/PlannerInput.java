package dev.wareworks.core.job;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockView;

/**
 * Everything {@link JobPlanner#plan} and {@link JobPlanner#planReroute} read: the crane, the aisle's locations, the
 * stock index, the reservations and callbacks into the live inventories. Build it with {@link #builder}.
 * <p>
 * The planner only ranks with index data and estimates; every candidate it wants to use is validated with the
 * {@link JobPlanner.LiveExtract} / {@link JobPlanner.LiveInsert} callbacks (simulated item handler calls in the content
 * layer), falling through to the next candidate when the live result is too low ({@code docs/warehouse-system.md} §5).
 *
 * @param craneBranch          branch (aisle) of the warehouse the crane stands on; {@value
 *                             dev.wareworks.core.address.RackPosition#FIRST_BRANCH} on a warehouse that does not bend
 * @param craneX               crane position along the aisle
 * @param craneY               crane level
 * @param speeds               crane speeds for travel time ranking ({@link CraneSpeeds#STOPPED} ranks by list order)
 * @param travel               what it costs the crane to drive from one rack position to another; the default
 *                             {@link TravelCost#straight} is the formula a single straight aisle always used
 *                             ({@link TravelTimeModel#travelTicks}), so an input built without it is <b>literally</b>
 *                             the input the planner received before M21
 * @param transferTicks        duration of one pick or drop, for {@link PlannedJob#estimatedTicks()}
 * @param carryLimit           items of a key one trip carries ({@code CapacityMath.carryLimit})
 * @param itemType             the item type of a key (for Minecraft items: the item without its components); for a new
 *                             key, a storage location holding only keys of the same type ranks like an empty one
 * @param stock                stock index; locations holding a key and consolidation ranking
 * @param reservations         the controller's reservations
 * @param requests             open retrieval requests, oldest first
 * @param supplies             ingredients open production orders still owe their production stations, oldest first
 *                             ({@code docs/warehouse-system.md} §3.5, ADR-024). Each is planned as a {@code SUPPLY}
 *                             job exactly like a request is planned as a {@code RETRIEVE}, after the requests and
 *                             before the store round robin
 * @param storageLocations     candidate storage locations in index order (one per inventory: shared-inventory aliases
 *                             excluded)
 * @param inputs               input stations in round-robin order
 * @param outputs              output stations (reroute candidates)
 * @param ports                the aisle's <b>accepting</b> warehouse ports that will take items <b>now</b> (M17, issue
 *                             #12), in a stable index order. The content layer applies the whole policy — the direction,
 *                             the redstone gate, the pulse token and availability — and this list is its answer; the
 *                             planner only ranks what it is given, which is what keeps redstone out of
 *                             {@link JobPlanner}. Every entry is an output station of the aisle, but <b>not</b>
 *                             necessarily an {@code outputs} entry: {@code outputs} holds the output stations a reroute
 *                             may deliver to, and the content layer already keeps accepting ports out of it
 *                             ({@code CraneDispatch#rerouteOutputs}), so in the game the two lists are disjoint. The
 *                             planner does not rely on either shape — {@link JobPlanner#withoutPorts} removes a port
 *                             from {@code outputs} wherever one is passed in both. The default is the empty list: the
 *                             answer of an aisle whose ports all request, which makes the new ranking key answer 0 for
 *                             every pair of candidates and the planner the function it was before M17
 * @param collectSources       the aisle's <b>collecting</b> warehouse ports that will hand items out <b>now</b> (M18,
 *                             issue #13), in a stable index order. As with {@code ports}, the content layer applies the
 *                             whole policy — the direction, the redstone gate, the pulse token, whether the rack and the
 *                             attached inventory are loaded, whether an inventory is there at all, whether it is one this
 *                             aisle already indexes, and whether the cached snapshot holds anything — so this list is its
 *                             answer and {@link JobPlanner} stays free of redstone. Every entry is an output station of
 *                             the aisle and is never an entry of {@code ports}: a port has one direction. The default is
 *                             the empty list, which together with {@link #collectBuffers()} makes an input built without
 *                             them <b>literally</b> the input the planner received before M18
 * @param collectBuffers       the last read snapshot of the inventory behind a collecting port; only called for sources
 *                             the arrival walk really examines. It is the controller's cached snapshot, never a live
 *                             read: the live {@link JobPlanner.LiveExtract} decides the amount and the real pick stays
 *                             authoritative (§3.2.4)
 * @param inputBuffers         the current buffer of an input station; only called for inputs that are examined
 * @param inputCursor          index into the <b>arrival</b> round robin — {@code inputs} followed by
 *                             {@code collectSources} as one virtual list — where the walk starts (taken modulo the
 *                             combined size)
 * @param available            whether a location can be used now (loaded, present, and the crane can drive to it);
 *                             unavailable ones are skipped. The builder wraps what it is given in a
 *                             {@link LocationAvailability}, so the world is asked <b>once per location per pass</b>
 *                             however many item types the pass ranks it for (M22)
 * @param insertEstimate       upper-bound estimate of what a storage location accepts, for pre-filtering
 * @param liveExtract          simulated extraction from a storage location
 * @param liveInsert           simulated insertion into any location
 * @param insertRefused        whether a storage location is known to refuse a key right now (it refused a live
 *                             insertion recently, {@link RefusalMemory}); such locations are skipped without a live call
 * @param extractRefused       whether a storage location is known to give none of a key right now; skipped likewise
 * @param allOrNothing         whether a location takes a carry <b>whole or not at all</b> (M30, issue #21): a fluid
 *                             bay, whose containers are exchanged rather than inserted, cannot keep part of a carry
 *                             because a handling head holds one item key. Consulted only by
 *                             {@link JobPlanner#planReroute}, which has a <b>fixed</b> amount it must place: such a
 *                             location is dropped there unless it takes all of it, and the next candidate is tried.
 *                             The store plan is untouched — it sizes a job by what the location answers, so a bay with
 *                             room for one bucket legitimately gets a job of one bucket. The default is {@code false}
 *                             for every location, which makes the planner the function it was before M30
 * @param storeFilter          what a storage location's store filter says about a key ({@link FilterMatch}, §3.1):
 *                             {@link FilterMatch#REJECTED} skips the location before the estimate and before any live
 *                             call, {@link FilterMatch#DEDICATED} ranks it above every unfiltered location, and
 *                             {@link FilterMatch#ALLOWED} (a deny list that does not exclude the key) ranks like an
 *                             unfiltered one. Consulted only where items are <b>stored</b> (the store plan and both
 *                             reroutes into storage), never for retrieval; on a {@code RETRIEVE} reroute a rejected
 *                             location is ranked last rather than dropped, so leftovers can go back where they came
 *                             from
 * @param storePriority        the storage priority a player gave a location (M16, issue #11, ADR-028): higher fills
 *                             first. It is a property of the <b>location</b> and of nothing else — the type
 *                             {@code ToIntFunction<? super L>} is that guarantee, and it is why a priority can never
 *                             depend on the item and never reach the retrieval path. Consulted only in
 *                             {@link JobPlanner#selectStorage}, as the sort key <b>below</b> the store filter,
 *                             consolidation and item-type grouping and <b>above</b> travel time, so a preference never
 *                             overrules a filter and never mixes item types; the default is {@link #NO_PRIORITY},
 *                             i.e. 0 for every location, which makes the ranking the function it was before M16
 * @param portRank             the signed rank of an accepting warehouse port (M17, issue #12): {@code < 0} an
 *                             <b>overflow</b>, which every storage location outranks, {@code > 0} a <b>diversion</b>,
 *                             which outranks every storage location, and the magnitude minus one is the strength a
 *                             player set, compared like the storage priority. Called only for entries of {@code ports()};
 *                             a rank of 0 means "not an accepting port" and drops the candidate, so a list and a rank
 *                             function that disagree can never export anything. The default is {@link #NO_PORT_RANK}
 * @param storeHeadroom        how many more items of a key the warehouse may still <b>store</b> (M15, issue #3): a
 *                             stock rule's maximum, minus what is stored and on its way in, plus what an open
 *                             production order is still expected to bring back. Consulted once per key in the store
 *                             plan, before the candidate ranking, the estimate and any live call, so a capped item is
 *                             strictly cheaper to refuse than to accept; the default is {@link Long#MAX_VALUE} for
 *                             every key, which is what an aisle without rules answers, so the planner behaves exactly
 *                             as it did before M15. Never consulted by {@link JobPlanner#planReroute}: items already
 *                             in the handling head must always find a target, or the crane holds for ever
 * @param liveSimulationBudget maximum number of live callback calls per planner run (bounds the cost of a full
 *                             warehouse or a long fall-through)
 * @param <K>                  item key type
 * @param <L>                  location type
 */
public record PlannerInput<K, L>(int craneBranch, double craneX, double craneY, CraneSpeeds speeds, TravelCost travel,
        int transferTicks,
        ToIntFunction<? super K> carryLimit, Function<? super K, ?> itemType, StockView<K, L> stock,
        ReservationView<K, L> reservations, List<OpenRequest<K, L>> requests, List<SupplyNeed<K, L>> supplies,
        List<L> storageLocations, List<L> inputs, List<L> outputs, List<L> ports, List<L> collectSources,
        Function<? super L, InventorySnapshot<K>> collectBuffers,
        Function<? super L, InventorySnapshot<K>> inputBuffers, int inputCursor, LocationAvailability<L> available,
        JobPlanner.InsertEstimate<K, L> insertEstimate, JobPlanner.LiveExtract<K, L> liveExtract,
        JobPlanner.LiveInsert<K, L> liveInsert, BiPredicate<? super L, ? super K> insertRefused,
        BiPredicate<? super L, ? super K> extractRefused, Predicate<? super L> allOrNothing,
        BiFunction<? super L, ? super K, FilterMatch> storeFilter, ToIntFunction<? super L> storePriority,
        ToIntFunction<? super L> portRank, ToLongFunction<? super K> storeHeadroom, int liveSimulationBudget) {
    /**
     * The store headroom of a warehouse no stock rule governs: every key may always be stored (M15, issue #3). It is
     * the builder's default, so an aisle without rules plans exactly as it did before M15.
     */
    public static final ToLongFunction<Object> UNLIMITED_HEADROOM = key -> Long.MAX_VALUE;
    /**
     * The storage priority of a warehouse in which no location was prioritised: 0 everywhere (M16, issue #11). It is
     * the builder's default, and because the ranking key compares {@code 0} with {@code 0} for every pair of
     * candidates, an input built without {@link Builder#storePriority} is <b>literally</b> the input the planner
     * received before M16 — which is what makes the existing test suite the regression proof of "no priority set
     * behaves exactly as before".
     */
    public static final ToIntFunction<Object> NO_PRIORITY = location -> 0;
    /**
     * The port rank of a warehouse whose ports all request: 0 everywhere (M17, issue #12). It is the builder's default
     * and is only ever called for entries of {@link #ports()}, which is empty by default, so an input built without
     * {@link Builder#ports} is <b>literally</b> the input the planner received before M17.
     */
    public static final ToIntFunction<Object> NO_PORT_RANK = location -> 0;

    /**
     * The collect snapshot of an aisle that has no collecting port: nothing, for every location (M18, issue #13). It is
     * the builder's default and is only ever called for entries of {@link #collectSources()}, which is empty by default,
     * so an input built without either is <b>literally</b> the input the planner received before M18.
     */
    public static <K> Function<Object, InventorySnapshot<K>> nothingCollected() {
        return location -> InventorySnapshot.empty();
    }

    /**
     * What driving from one rack position to another costs the crane, in ticks (M21, ADR-033).
     * <p>
     * The planner learns nothing about rail networks: it asks this and ranks by the answer. A warehouse of one aisle
     * hands it {@link #straight}, which ignores the branches and is literally
     * {@link TravelTimeModel#travelTicks} — so "a warehouse that does not bend plans exactly as it did before M21" is
     * a property of the type, not a claim about the code. A warehouse with corners hands it a cost that counts the
     * route's blocks <b>and its quarter turns</b> ({@code crane.turnPenaltyBlocks}).
     * <p>
     * Unreachable is not this function's business: a rack the crane cannot drive to is dropped by
     * {@link PlannerInput#available()}, exactly like one in an unloaded chunk. An implementation that has no route
     * anyway answers {@link TravelTimeModel#UNAVAILABLE}, which ranks last and never becomes a job.
     */
    @FunctionalInterface
    public interface TravelCost {
        /** Ticks the crane needs to travel between two rack positions with the arm retracted. */
        long travelTicks(int fromBranch, double fromX, double fromY, int toBranch, double toX, double toY);

        /** The cost of a warehouse of one straight aisle: the branch is ignored, as it was before M21. */
        static TravelCost straight(CraneSpeeds speeds) {
            Objects.requireNonNull(speeds, "speeds");
            return (fromBranch, fromX, fromY, toBranch, toX, toY) ->
                    TravelTimeModel.travelTicks(speeds, fromX, fromY, toX, toY);
        }
    }

    public PlannerInput {
        if (craneBranch < 0)
            throw new IllegalArgumentException("craneBranch must not be negative: " + craneBranch);
        if (!Double.isFinite(craneX) || !Double.isFinite(craneY))
            throw new IllegalArgumentException("crane position must be finite: " + craneX + ", " + craneY);
        Objects.requireNonNull(speeds, "speeds");
        Objects.requireNonNull(travel, "travel");
        if (transferTicks < 0)
            throw new IllegalArgumentException("transferTicks must not be negative: " + transferTicks);
        Objects.requireNonNull(carryLimit, "carryLimit");
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(reservations, "reservations");
        requests = List.copyOf(requests);
        supplies = List.copyOf(supplies);
        storageLocations = List.copyOf(storageLocations);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        ports = List.copyOf(ports);
        collectSources = List.copyOf(collectSources);
        Objects.requireNonNull(collectBuffers, "collectBuffers");
        Objects.requireNonNull(inputBuffers, "inputBuffers");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(insertEstimate, "insertEstimate");
        Objects.requireNonNull(liveExtract, "liveExtract");
        Objects.requireNonNull(liveInsert, "liveInsert");
        Objects.requireNonNull(insertRefused, "insertRefused");
        Objects.requireNonNull(extractRefused, "extractRefused");
        Objects.requireNonNull(allOrNothing, "allOrNothing");
        Objects.requireNonNull(storeFilter, "storeFilter");
        Objects.requireNonNull(storePriority, "storePriority");
        Objects.requireNonNull(portRank, "portRank");
        Objects.requireNonNull(storeHeadroom, "storeHeadroom");
        if (liveSimulationBudget < 0)
            throw new IllegalArgumentException("liveSimulationBudget must not be negative: " + liveSimulationBudget);
    }

    /**
     * An open retrieval request as the planner sees it: the output station is a location of the aisle.
     *
     * @param id        request id (reservations of its jobs are counted through
     *                  {@link ReservationView#committedToRequest})
     * @param key       requested item
     * @param remaining amount not delivered yet, at least 1
     * @param output    the output station
     * @param <K>       item key type
     * @param <L>       location type
     */
    public record OpenRequest<K, L>(UUID id, K key, int remaining, L output) {
        public OpenRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(output, "output");
            if (remaining < 1)
                throw new IllegalArgumentException("remaining must be at least 1: " + remaining);
        }
    }

    /**
     * One ingredient an open production order still owes its production station, as the planner sees it
     * ({@code docs/warehouse-system.md} §3.5, ADR-024).
     * <p>
     * It is the production side's {@link OpenRequest}: the {@code id} is the order's <b>ingredient line</b>, not the
     * order, so the reservation ledger tracks each ingredient on its own and
     * {@link ReservationView#committedToRequest} answers "how much of this ingredient is already on its way" — the
     * same question the planner asks about a request before it plans a second trip for it.
     *
     * @param id        the ingredient line's id (the {@code SUPPLY} job carries it)
     * @param key       the ingredient
     * @param remaining items not delivered to the station yet, at least 1
     * @param station   the production station they go to
     * @param <K>       item key type
     * @param <L>       location type
     */
    public record SupplyNeed<K, L>(UUID id, K key, int remaining, L station) {
        public SupplyNeed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(station, "station");
            if (remaining < 1)
                throw new IllegalArgumentException("remaining must be at least 1: " + remaining);
        }
    }

    /**
     * A builder with safe defaults: crane at (0, 0), stopped, no transfer time, no requests or locations, everything
     * available, every key its own item type, unknown capacity estimates, live callbacks that accept and give nothing, no
     * known refusals, no store filters ({@link FilterMatch#UNFILTERED} everywhere), no storage priorities
     * ({@link #NO_PRIORITY}, i.e. 0 everywhere), no accepting ports (an empty {@link #ports()} and
     * {@link #NO_PORT_RANK}), unlimited store headroom ({@link #UNLIMITED_HEADROOM}, i.e. no stock rule) and
     * {@link JobPlanner#DEFAULT_LIVE_SIMULATION_BUDGET}, no collecting ports (an empty {@link #collectSources()} and
     * empty collect snapshots). The carry limit has no default.
     */
    public static <K, L> Builder<K, L> builder(StockView<K, L> stock, ReservationView<K, L> reservations) {
        return new Builder<>(stock, reservations);
    }

    /** Builder for {@link PlannerInput}. */
    public static final class Builder<K, L> {
        private final StockView<K, L> stock;
        private final ReservationView<K, L> reservations;
        private int craneBranch;
        private double craneX;
        private double craneY;
        private CraneSpeeds speeds = CraneSpeeds.STOPPED;
        private @Nullable TravelCost travel;
        private int transferTicks;
        private ToIntFunction<? super K> carryLimit;
        private Function<? super K, ?> itemType = key -> key;
        private List<OpenRequest<K, L>> requests = List.of();
        private List<SupplyNeed<K, L>> supplies = List.of();
        private List<L> storageLocations = List.of();
        private List<L> inputs = List.of();
        private List<L> outputs = List.of();
        private List<L> ports = List.of();
        private List<L> collectSources = List.of();
        private Function<? super L, InventorySnapshot<K>> collectBuffers = location -> InventorySnapshot.empty();
        private Function<? super L, InventorySnapshot<K>> inputBuffers = location -> InventorySnapshot.empty();
        private int inputCursor;
        private Predicate<? super L> available = location -> true;
        private JobPlanner.InsertEstimate<K, L> insertEstimate = JobPlanner.InsertEstimate.unknown();
        private JobPlanner.LiveExtract<K, L> liveExtract = (location, key, maxAmount) -> 0;
        private JobPlanner.LiveInsert<K, L> liveInsert = (location, key, amount) -> 0;
        private BiPredicate<? super L, ? super K> insertRefused = (location, key) -> false;
        private BiPredicate<? super L, ? super K> extractRefused = (location, key) -> false;
        private Predicate<? super L> allOrNothing = location -> false;
        private BiFunction<? super L, ? super K, FilterMatch> storeFilter = (location, key) -> FilterMatch.UNFILTERED;
        private ToIntFunction<? super L> storePriority = NO_PRIORITY;
        private ToIntFunction<? super L> portRank = NO_PORT_RANK;
        private ToLongFunction<? super K> storeHeadroom = UNLIMITED_HEADROOM;
        private int liveSimulationBudget = JobPlanner.DEFAULT_LIVE_SIMULATION_BUDGET;

        private Builder(StockView<K, L> stock, ReservationView<K, L> reservations) {
            this.stock = Objects.requireNonNull(stock, "stock");
            this.reservations = Objects.requireNonNull(reservations, "reservations");
        }

        /** The crane on the only branch a warehouse that does not bend has. */
        public Builder<K, L> crane(double x, double y) {
            return crane(RackPosition.FIRST_BRANCH, x, y);
        }

        public Builder<K, L> crane(int branch, double x, double y) {
            this.craneBranch = branch;
            this.craneX = x;
            this.craneY = y;
            return this;
        }

        /**
         * What driving costs. Left unset, it is {@link TravelCost#straight} over {@link #speeds}, which is the formula
         * a single straight aisle always used.
         */
        public Builder<K, L> travel(TravelCost travel) {
            this.travel = travel;
            return this;
        }

        public Builder<K, L> speeds(CraneSpeeds speeds) {
            this.speeds = speeds;
            return this;
        }

        public Builder<K, L> transferTicks(int transferTicks) {
            this.transferTicks = transferTicks;
            return this;
        }

        public Builder<K, L> carryLimit(ToIntFunction<? super K> carryLimit) {
            this.carryLimit = carryLimit;
            return this;
        }

        public Builder<K, L> itemType(Function<? super K, ?> itemType) {
            this.itemType = itemType;
            return this;
        }

        public Builder<K, L> requests(List<OpenRequest<K, L>> requests) {
            this.requests = requests;
            return this;
        }

        public Builder<K, L> supplies(List<SupplyNeed<K, L>> supplies) {
            this.supplies = supplies;
            return this;
        }

        public Builder<K, L> storageLocations(List<L> storageLocations) {
            this.storageLocations = storageLocations;
            return this;
        }

        public Builder<K, L> inputs(List<L> inputs) {
            this.inputs = inputs;
            return this;
        }

        public Builder<K, L> outputs(List<L> outputs) {
            this.outputs = outputs;
            return this;
        }

        /**
         * The aisle's accepting warehouse ports that will take items now ({@link PlannerInput#ports()}). Left out, it is
         * the empty list — the answer of an aisle whose ports all request, and the input the planner received before
         * M17.
         */
        public Builder<K, L> ports(List<L> ports) {
            this.ports = ports;
            return this;
        }

        /**
         * The aisle's collecting warehouse ports that will hand items out now ({@link PlannerInput#collectSources()}).
         * Left out, it is the empty list — the answer of an aisle without a collecting port, and the input the planner
         * received before M18.
         */
        public Builder<K, L> collectSources(List<L> collectSources) {
            this.collectSources = collectSources;
            return this;
        }

        /**
         * The cached snapshot of the inventory behind a collecting port ({@link PlannerInput#collectBuffers()}). Left
         * out, every source answers an empty snapshot, which is what an aisle without collecting ports means.
         */
        public Builder<K, L> collectBuffers(Function<? super L, InventorySnapshot<K>> collectBuffers) {
            this.collectBuffers = collectBuffers;
            return this;
        }

        public Builder<K, L> inputBuffers(Function<? super L, InventorySnapshot<K>> inputBuffers) {
            this.inputBuffers = inputBuffers;
            return this;
        }

        public Builder<K, L> inputCursor(int inputCursor) {
            this.inputCursor = inputCursor;
            return this;
        }

        public Builder<K, L> available(Predicate<? super L> available) {
            this.available = available;
            return this;
        }

        public Builder<K, L> insertEstimate(JobPlanner.InsertEstimate<K, L> insertEstimate) {
            this.insertEstimate = insertEstimate;
            return this;
        }

        public Builder<K, L> liveExtract(JobPlanner.LiveExtract<K, L> liveExtract) {
            this.liveExtract = liveExtract;
            return this;
        }

        public Builder<K, L> liveInsert(JobPlanner.LiveInsert<K, L> liveInsert) {
            this.liveInsert = liveInsert;
            return this;
        }

        public Builder<K, L> insertRefused(BiPredicate<? super L, ? super K> insertRefused) {
            this.insertRefused = insertRefused;
            return this;
        }

        public Builder<K, L> extractRefused(BiPredicate<? super L, ? super K> extractRefused) {
            this.extractRefused = extractRefused;
            return this;
        }

        /**
         * Which locations take a carry whole or not at all ({@link PlannerInput#allOrNothing()}). Left out, no location
         * does, which is the input the planner received before M30.
         */
        public Builder<K, L> allOrNothing(Predicate<? super L> allOrNothing) {
            this.allOrNothing = allOrNothing;
            return this;
        }

        public Builder<K, L> storeFilter(BiFunction<? super L, ? super K, FilterMatch> storeFilter) {
            this.storeFilter = storeFilter;
            return this;
        }

        /**
         * The storage priority of a location ({@link PlannerInput#storePriority()}). Left out, it is
         * {@link PlannerInput#NO_PRIORITY} — the answer of a warehouse in which nothing was prioritised, and the input
         * the planner received before M16.
         */
        public Builder<K, L> storePriority(ToIntFunction<? super L> storePriority) {
            this.storePriority = storePriority;
            return this;
        }

        /**
         * The signed rank of an accepting warehouse port ({@link PlannerInput#portRank()}). Left out, it is
         * {@link PlannerInput#NO_PORT_RANK}, which together with an empty {@link #ports} makes this the input the
         * planner received before M17.
         */
        public Builder<K, L> portRank(ToIntFunction<? super L> portRank) {
            this.portRank = portRank;
            return this;
        }

        /**
         * How many more items of a key the warehouse may still store ({@link PlannerInput#storeHeadroom()}). Left
         * out, it is {@link PlannerInput#UNLIMITED_HEADROOM} — the answer of an aisle that has no stock rules.
         */
        public Builder<K, L> storeHeadroom(ToLongFunction<? super K> storeHeadroom) {
            this.storeHeadroom = storeHeadroom;
            return this;
        }

        public Builder<K, L> liveSimulationBudget(int liveSimulationBudget) {
            this.liveSimulationBudget = liveSimulationBudget;
            return this;
        }

        public PlannerInput<K, L> build() {
            return new PlannerInput<>(craneBranch, craneX, craneY, speeds,
                    travel != null ? travel : TravelCost.straight(speeds), transferTicks, carryLimit, itemType, stock,
                    reservations,
                    requests, supplies, storageLocations, inputs, outputs, ports, collectSources, collectBuffers,
                    inputBuffers, inputCursor, LocationAvailability.of(available),
                    insertEstimate, liveExtract, liveInsert, insertRefused, extractRefused, allOrNothing, storeFilter,
                    storePriority,
                    portRank, storeHeadroom, liveSimulationBudget);
        }
    }
}
