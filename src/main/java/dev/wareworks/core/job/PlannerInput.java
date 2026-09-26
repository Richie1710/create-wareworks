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
 * @param craneX               crane position along the aisle
 * @param craneY               crane level
 * @param speeds               crane speeds for travel time ranking ({@link CraneSpeeds#STOPPED} ranks by list order)
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
 * @param inputBuffers         the current buffer of an input station; only called for inputs that are examined
 * @param inputCursor          index into {@code inputs} where the store round robin starts (taken modulo the size)
 * @param available            whether a location can be used now (loaded, present); unavailable ones are skipped
 * @param insertEstimate       upper-bound estimate of what a storage location accepts, for pre-filtering
 * @param liveExtract          simulated extraction from a storage location
 * @param liveInsert           simulated insertion into any location
 * @param insertRefused        whether a storage location is known to refuse a key right now (it refused a live
 *                             insertion recently, {@link RefusalMemory}); such locations are skipped without a live call
 * @param extractRefused       whether a storage location is known to give none of a key right now; skipped likewise
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
public record PlannerInput<K, L>(double craneX, double craneY, CraneSpeeds speeds, int transferTicks,
        ToIntFunction<? super K> carryLimit, Function<? super K, ?> itemType, StockView<K, L> stock,
        ReservationView<K, L> reservations, List<OpenRequest<K, L>> requests, List<SupplyNeed<K, L>> supplies,
        List<L> storageLocations, List<L> inputs, List<L> outputs,
        Function<? super L, InventorySnapshot<K>> inputBuffers, int inputCursor, Predicate<? super L> available,
        JobPlanner.InsertEstimate<K, L> insertEstimate, JobPlanner.LiveExtract<K, L> liveExtract,
        JobPlanner.LiveInsert<K, L> liveInsert, BiPredicate<? super L, ? super K> insertRefused,
        BiPredicate<? super L, ? super K> extractRefused,
        BiFunction<? super L, ? super K, FilterMatch> storeFilter, ToIntFunction<? super L> storePriority,
        ToLongFunction<? super K> storeHeadroom, int liveSimulationBudget) {
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

    public PlannerInput {
        if (!Double.isFinite(craneX) || !Double.isFinite(craneY))
            throw new IllegalArgumentException("crane position must be finite: " + craneX + ", " + craneY);
        Objects.requireNonNull(speeds, "speeds");
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
        Objects.requireNonNull(inputBuffers, "inputBuffers");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(insertEstimate, "insertEstimate");
        Objects.requireNonNull(liveExtract, "liveExtract");
        Objects.requireNonNull(liveInsert, "liveInsert");
        Objects.requireNonNull(insertRefused, "insertRefused");
        Objects.requireNonNull(extractRefused, "extractRefused");
        Objects.requireNonNull(storeFilter, "storeFilter");
        Objects.requireNonNull(storePriority, "storePriority");
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
     * ({@link #NO_PRIORITY}, i.e. 0 everywhere), unlimited store headroom ({@link #UNLIMITED_HEADROOM}, i.e. no stock
     * rule) and {@link JobPlanner#DEFAULT_LIVE_SIMULATION_BUDGET}. The carry limit has no default.
     */
    public static <K, L> Builder<K, L> builder(StockView<K, L> stock, ReservationView<K, L> reservations) {
        return new Builder<>(stock, reservations);
    }

    /** Builder for {@link PlannerInput}. */
    public static final class Builder<K, L> {
        private final StockView<K, L> stock;
        private final ReservationView<K, L> reservations;
        private double craneX;
        private double craneY;
        private CraneSpeeds speeds = CraneSpeeds.STOPPED;
        private int transferTicks;
        private ToIntFunction<? super K> carryLimit;
        private Function<? super K, ?> itemType = key -> key;
        private List<OpenRequest<K, L>> requests = List.of();
        private List<SupplyNeed<K, L>> supplies = List.of();
        private List<L> storageLocations = List.of();
        private List<L> inputs = List.of();
        private List<L> outputs = List.of();
        private Function<? super L, InventorySnapshot<K>> inputBuffers = location -> InventorySnapshot.empty();
        private int inputCursor;
        private Predicate<? super L> available = location -> true;
        private JobPlanner.InsertEstimate<K, L> insertEstimate = JobPlanner.InsertEstimate.unknown();
        private JobPlanner.LiveExtract<K, L> liveExtract = (location, key, maxAmount) -> 0;
        private JobPlanner.LiveInsert<K, L> liveInsert = (location, key, amount) -> 0;
        private BiPredicate<? super L, ? super K> insertRefused = (location, key) -> false;
        private BiPredicate<? super L, ? super K> extractRefused = (location, key) -> false;
        private BiFunction<? super L, ? super K, FilterMatch> storeFilter = (location, key) -> FilterMatch.UNFILTERED;
        private ToIntFunction<? super L> storePriority = NO_PRIORITY;
        private ToLongFunction<? super K> storeHeadroom = UNLIMITED_HEADROOM;
        private int liveSimulationBudget = JobPlanner.DEFAULT_LIVE_SIMULATION_BUDGET;

        private Builder(StockView<K, L> stock, ReservationView<K, L> reservations) {
            this.stock = Objects.requireNonNull(stock, "stock");
            this.reservations = Objects.requireNonNull(reservations, "reservations");
        }

        public Builder<K, L> crane(double x, double y) {
            this.craneX = x;
            this.craneY = y;
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
            return new PlannerInput<>(craneX, craneY, speeds, transferTicks, carryLimit, itemType, stock, reservations,
                    requests, supplies, storageLocations, inputs, outputs, inputBuffers, inputCursor, available,
                    insertEstimate, liveExtract, liveInsert, insertRefused, extractRefused, storeFilter, storePriority,
                    storeHeadroom, liveSimulationBudget);
        }
    }
}
