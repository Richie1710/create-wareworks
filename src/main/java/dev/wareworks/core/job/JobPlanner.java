package dev.wareworks.core.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.CapacityMath;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.LocationCount;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.warehouse.LocationKind;

/**
 * Plans transport jobs and reroutes ({@code docs/warehouse-system.md} §7.1, §8). Pure: it reads a
 * {@link PlannerInput}, calls its live simulation callbacks and returns a result; it reserves nothing and moves nothing.
 * <p>
 * <b>{@link #plan}</b>:
 * <ol>
 *   <li><b>RETRIEVE first</b>, for the open requests oldest first. A request's need is its remaining amount minus what
 *       its jobs already cover ({@link ReservationView#committedToRequest}); a covered request is skipped. The output
 *       must be available and accept at least 1 (live). Candidates are the locations holding the key per index,
 *       available and not fully reserved, ranked by travel time crane → source → output. Amount =
 *       {@code min(need, carryLimit, liveExtractable − reservedStock)}. <b>Store filters never restrict retrieval</b>
 *       (§3.1): items already inside a location can always be fetched, even when its filter no longer accepts them.</li>
 *   <li><b>SUPPLY</b> next, for the ingredients open production orders still owe their production stations
 *       ({@code docs/warehouse-system.md} §3.5, ADR-024). Planned exactly like a retrieve — same candidates, same
 *       ranking, same live validation — with the production station as the target and
 *       {@link NoJobReason#PRODUCTION_FULL} when it accepts nothing. It comes after the requests (a player waiting at
 *       a terminal is served first) and before storing, so ingredients move while new items are still arriving.</li>
 *   <li><b>STORE</b> otherwise, round robin over the input stations from the cursor, skipping empty buffers. Item = the
 *       first non-empty slot. A stock rule's maximum is consulted first ({@link PlannerInput#storeHeadroom()}, M15):
 *       an item with no headroom left is skipped before any candidate work and reported as
 *       {@link NoJobReason#AT_MAXIMUM}, and a headroom smaller than the buffered amount bounds the planned amount, so
 *       the rest stays in the input on purpose. Candidates are the available storage locations whose store filter
 *       accepts the item and whose estimate exceeds their reserved capacity, ranked by (a0) a filter that
 *       <b>selects</b> the item ({@link FilterMatch#DEDICATED}; a deny list that merely does not exclude it is
 *       {@link FilterMatch#ALLOWED} and ranks like an unfiltered location), then (a) already holding the item, then
 *       (a2) holding nothing or only items of the same type ({@link PlannerInput#itemType()}), then (b) travel time
 *       crane → input → location. Amount =
 *       {@code min(buffered, storeHeadroom, carryLimit, liveInsertable − reservedCapacity)}. When an input's items fit
 *       nowhere, the reason distinguishes "no room" ({@link NoJobReason#WAREHOUSE_FULL}) from "no filter accepts them"
 *       ({@link NoJobReason#NO_MATCHING_FILTER}) and from "a rule holds enough already"
 *       ({@link NoJobReason#AT_MAXIMUM}).</li>
 * </ol>
 * Candidates are tried in rank order and fall through when the live result leaves nothing (§5).
 * <p>
 * <b>Refinements</b> (recorded in {@code docs/warehouse-system.md} §7.4): a request that cannot be served now (output
 * full or unavailable, not in stock) does not block younger requests; an input whose first item fits nowhere tries its
 * other item types in slot order and then the next input, so one unstorable item never blocks every input; each run
 * makes at most {@link PlannerInput#liveSimulationBudget()} live calls; a new key prefers a storage location that is
 * empty or holds only its item type over a nearer one that holds other item types (rank (a2)), so item types are not
 * mixed while such locations are free; locations known to refuse the key right now
 * ({@link PlannerInput#insertRefused()}, {@link PlannerInput#extractRefused()}) are skipped without a live call, so a run
 * that exhausted the budget on refusals continues past them next time; a location whose store filter rejects the item
 * ({@link PlannerInput#storeFilter()}) is skipped even earlier, before the capacity estimate, so a filtered warehouse
 * spends neither live simulations nor remembered refusals on locations that could never take the item (ADR-021).
 * <p>
 * <b>{@link #planReroute}</b> follows the §8 table and never consults {@link PlannerInput#storeHeadroom()} (M15): the
 * items are already in the handling head, so they must always find a target or the crane holds for ever. Store
 * leftovers go to another storage location (same ranking as STORE, from the crane's position), else to an input
 * buffer; retrieve leftovers go back to a storage location, else to another output station (refinement: an output
 * that did not request the items only as the last resort); otherwise the result is empty (the crane holds). The station fallback has a live simulation budget of its own, so storage
 * candidates that used up the budget never hide a station that accepts the items.
 * <p>
 * <b>Store filters and the retrieve reroute</b> (M8 review fix, §8): a {@code RETRIEVE} reroute puts items back that
 * already came <i>out</i> of the warehouse, so a rejecting filter is advisory there — such locations are ranked
 * <b>last</b> instead of dropped, and the location the items were just picked from can therefore always take its own
 * stock back. Every other storing path (the store plan and the store reroute) drops them, so a dedicated location never
 * receives new items its filter rejects.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class JobPlanner<K, L> {
    /** Default for {@link PlannerInput#liveSimulationBudget()}. */
    public static final int DEFAULT_LIVE_SIMULATION_BUDGET = 64;
    /** Capacity estimate meaning "unknown, ask the live inventory". */
    public static final long UNKNOWN_CAPACITY = Long.MAX_VALUE;
    /** Filter rank of a candidate no store filter applies to (stations, retrieve sources): neither better nor worse. */
    private static final int NEUTRAL_FILTER_RANK = FilterMatch.UNFILTERED.storeRank();

    /** Simulated extraction from a live inventory: how many of {@code key} could be taken now, up to the maximum. */
    @FunctionalInterface
    public interface LiveExtract<K, L> {
        int simulateExtract(L location, K key, int maxAmount);
    }

    /** Simulated insertion into a live inventory: how many of {@code amount} items of {@code key} it accepts now. */
    @FunctionalInterface
    public interface LiveInsert<K, L> {
        int simulateInsert(L location, K key, int amount);
    }

    /** Upper-bound estimate of how many items of a key a location accepts, or {@link #UNKNOWN_CAPACITY}. */
    @FunctionalInterface
    public interface InsertEstimate<K, L> {
        long estimateInsertable(L location, K key);

        /** Every location may accept anything; only the live simulation decides. */
        static <K, L> InsertEstimate<K, L> unknown() {
            return (location, key) -> UNKNOWN_CAPACITY;
        }

        /**
         * Estimates from the index snapshots ({@link InventorySnapshot#insertable}); unknown for locations without a
         * snapshot (e.g. counts restored from a save).
         */
        static <K, L> InsertEstimate<K, L> fromSnapshots(StockView<K, L> stock, ToIntFunction<? super K> maxStackSize) {
            Objects.requireNonNull(stock, "stock");
            Objects.requireNonNull(maxStackSize, "maxStackSize");
            return (location, key) -> stock.snapshotOf(location)
                    .map(snapshot -> snapshot.insertable(key, maxStackSize.applyAsInt(key)))
                    .orElse(UNKNOWN_CAPACITY);
        }
    }

    private static final Comparator<Candidate<?>> RANKING = Comparator
            .comparingInt((Candidate<?> candidate) -> candidate.filterRank())
            .thenComparing((Candidate<?> candidate) -> !candidate.consolidates())
            .thenComparing((Candidate<?> candidate) -> !candidate.compatible())
            .thenComparingLong(Candidate::travelTicks)
            .thenComparingInt(Candidate::order);

    private final Function<? super L, RackPosition> positions;
    private final Supplier<UUID> idFactory;

    /** A planner with random job ids. */
    public JobPlanner(Function<? super L, RackPosition> positions) {
        this(positions, UUID::randomUUID);
    }

    /**
     * @param positions maps a location to its aisle-local rack position (identity for {@code RackPosition} locations)
     * @param idFactory creates job ids (deterministic in tests)
     */
    public JobPlanner(Function<? super L, RackPosition> positions, Supplier<UUID> idFactory) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
    }

    /** Plans at most one job (§7.1). */
    public PlanResult<K, L> plan(PlannerInput<K, L> input) {
        Objects.requireNonNull(input, "input");
        Budget budget = new Budget(input.liveSimulationBudget());
        Set<NoJobReason> reasons = EnumSet.noneOf(NoJobReason.class);
        List<L> inputs = input.inputs();
        int start = inputs.isEmpty() ? 0 : Math.floorMod(input.inputCursor(), inputs.size());
        boolean work = false;

        for (PlannerInput.OpenRequest<K, L> request : input.requests()) {
            long need = request.remaining() - Math.max(0L, input.reservations().committedToRequest(request.id()));
            if (need <= 0)
                continue;
            work = true;
            Optional<PlannedJob<K, L>> job = planRetrieve(input, request, (int) need, budget, reasons);
            if (job.isPresent())
                return new PlanResult<>(job, reasons, start);
            if (budget.exhausted) {
                reasons.add(NoJobReason.BUDGET_EXHAUSTED);
                return new PlanResult<>(Optional.empty(), reasons, start);
            }
        }

        for (PlannerInput.SupplyNeed<K, L> supply : input.supplies()) {
            long need = supply.remaining() - Math.max(0L, input.reservations().committedToRequest(supply.id()));
            if (need <= 0)
                continue;
            work = true;
            Optional<PlannedJob<K, L>> job = planSupply(input, supply, (int) need, budget, reasons);
            if (job.isPresent())
                return new PlanResult<>(job, reasons, start);
            if (budget.exhausted) {
                reasons.add(NoJobReason.BUDGET_EXHAUSTED);
                return new PlanResult<>(Optional.empty(), reasons, start);
            }
        }

        for (int i = 0; i < inputs.size(); i++) {
            int index = (start + i) % inputs.size();
            L station = inputs.get(index);
            if (!input.available().test(station))
                continue;
            InventorySnapshot<K> buffer = input.inputBuffers().apply(station);
            if (buffer == null || buffer.isEmpty())
                continue;
            work = true;
            int next = (index + 1) % inputs.size();
            RackPosition stationPos = position(station);
            long toStation = TravelTimeModel.travelTicks(input.speeds(), input.craneX(), input.craneY(), stationPos.x(),
                    stationPos.y());
            // Why this input got no job: "no room anywhere" and "no filter accepts these items" are different problems
            // for the player, and only the first is relieved by a retrieval (ADR-021, §7.4).
            StoreSurvey survey = new StoreSurvey();
            for (K key : buffer.keys()) {
                // A stock rule's maximum decides before anything else costs something: one lookup, no candidate walk,
                // no estimate, no live call and no remembered refusal (M15, issue #3). Partial storing is normal and
                // exact — stock 1990, maximum 2048 and a buffer of 64 store 58 and leave 6 in the input on purpose.
                long headroom = input.storeHeadroom().applyAsLong(key);
                if (headroom <= 0) {
                    // Skips this KEY, not the input: the buffer's other item types and then the next input are still
                    // tried, so one capped item never blocks a station.
                    survey.atMaximum = true;
                    continue;
                }
                int limit = limit(Math.min(buffer.count(key), headroom), input.carryLimit().applyAsInt(key));
                if (limit < 1)
                    continue;
                Optional<Selection<L>> selection = selectStorage(input, key, limit, null, stationPos.x(),
                        stationPos.y(), toStation, budget, false, survey);
                if (selection.isPresent()) {
                    L storage = selection.get().location();
                    TransportJob<K, L> job = TransportJob.store(newId(), station, storage, key,
                            selection.get().amount());
                    return new PlanResult<>(Optional.of(new PlannedJob<>(job, tripTicks(input, station, storage))),
                            reasons, next);
                }
                if (budget.exhausted) {
                    reasons.add(NoJobReason.BUDGET_EXHAUSTED);
                    return new PlanResult<>(Optional.empty(), reasons, next);
                }
            }
            reasons.add(survey.reason());
        }
        if (!work)
            reasons.add(NoJobReason.NO_WORK);
        return new PlanResult<>(Optional.empty(), reasons, start);
    }

    /**
     * Finds a new target for {@code amount} items of {@code key} left in the handling head of a job of {@code type}
     * whose target {@code failedTarget} failed (§8). The crane position of {@code input} is the travel origin.
     *
     * @param failedTarget the target to exclude, or {@code null} (a hold retry: every location may be chosen)
     * @return the best target that accepts at least one item in the live simulation, or empty (hold the items); at
     * most twice {@link PlannerInput#liveSimulationBudget()} live calls (storage, then stations)
     * @throws IllegalArgumentException if {@code amount < 1}
     */
    public Optional<RerouteTarget<L>> planReroute(PlannerInput<K, L> input, K key, int amount, JobType type,
            @Nullable L failedTarget) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        Budget budget = new Budget(input.liveSimulationBudget());
        double x = input.craneX();
        double y = input.craneY();
        return switch (type) {
            // Storing leftovers is still storing, so a rejecting filter drops the location here as in the store plan.
            case STORE -> selectStorage(input, key, amount, failedTarget, x, y, 0L, budget, false, null)
                    .map(selection -> selection.target(LocationKind.STORAGE))
                    .or(() -> selectStation(input, input.inputs(), key, amount, failedTarget,
                            new Budget(input.liveSimulationBudget()))
                            .map(selection -> selection.target(LocationKind.INPUT)));
            // Back into storage first: another output never asked for these items (its own requests are served by
            // their own jobs), so delivering there would over-deliver. Only when no storage location accepts them.
            // Store filters are advisory here (M8 review fix): these items already left the warehouse, so a location
            // whose filter rejects them is ranked last rather than dropped — otherwise a location that was
            // re-dedicated while its stock was inside could not take that stock back, and a fully partitioned aisle
            // could park the crane in HOLDING for ever (§8).
            case RETRIEVE -> selectStorage(input, key, amount, failedTarget, x, y, 0L, budget, true, null)
                    .map(selection -> selection.target(LocationKind.STORAGE))
                    .or(() -> selectStation(input, input.outputs(), key, amount, failedTarget,
                            new Budget(input.liveSimulationBudget()))
                            .map(selection -> selection.target(LocationKind.OUTPUT)));
            // Supply leftovers go back into storage and nowhere else: nobody requested them at a station, and putting
            // them into an output would hand a player ingredients they never asked for (ADR-024). Rejecting filters
            // are advisory here for the same reason as on a retrieve reroute — these items already left the warehouse.
            case SUPPLY -> selectStorage(input, key, amount, failedTarget, x, y, 0L, budget, true, null)
                    .map(selection -> selection.target(LocationKind.STORAGE));
        };
    }

    // --- retrieve ------------------------------------------------------------------------------------------------

    private Optional<PlannedJob<K, L>> planRetrieve(PlannerInput<K, L> input, PlannerInput.OpenRequest<K, L> request,
            int need, Budget budget, Set<NoJobReason> reasons) {
        return planOutOfStorage(input, request.key(), request.output(), request.id(), JobType.RETRIEVE, need,
                NoJobReason.OUTPUT_FULL, budget, reasons);
    }

    /** One ingredient of a production order, planned exactly like a retrieve but towards its production station. */
    private Optional<PlannedJob<K, L>> planSupply(PlannerInput<K, L> input, PlannerInput.SupplyNeed<K, L> supply,
            int need, Budget budget, Set<NoJobReason> reasons) {
        return planOutOfStorage(input, supply.key(), supply.station(), supply.id(), JobType.SUPPLY, need,
                NoJobReason.PRODUCTION_FULL, budget, reasons);
    }

    /**
     * The shared body of {@link #planRetrieve} and {@link #planSupply}: take {@code need} items of {@code key} out of
     * storage and bring them to {@code target}, which must accept at least one item right now.
     *
     * @param ownerId    the retrieval request or production ingredient line waiting for the items
     * @param fullReason the reason to report when {@code target} accepts nothing
     */
    private Optional<PlannedJob<K, L>> planOutOfStorage(PlannerInput<K, L> input, K key, L target, UUID ownerId,
            JobType type, int need, NoJobReason fullReason, Budget budget, Set<NoJobReason> reasons) {
        if (!input.available().test(target)) {
            reasons.add(NoJobReason.LOCATION_UNAVAILABLE);
            return Optional.empty();
        }
        int limit = limit(need, input.carryLimit().applyAsInt(key));
        if (limit < 1)
            return Optional.empty();
        if (!budget.tryUse())
            return Optional.empty();
        if (input.liveInsert().simulateInsert(target, key, 1) < 1) {
            reasons.add(fullReason);
            return Optional.empty();
        }
        RackPosition outputPos = position(target);
        List<Candidate<L>> candidates = new ArrayList<>();
        int order = 0;
        for (LocationCount<L> entry : input.stock().locationsOf(key)) {
            int rank = order++;
            L location = entry.location();
            if (!input.available().test(location) || input.extractRefused().test(location, key))
                continue;
            if (entry.count() - input.reservations().reservedStock(location, key) <= 0)
                continue;
            RackPosition pos = position(location);
            long travel = TravelTimeModel.add(
                    TravelTimeModel.travelTicks(input.speeds(), input.craneX(), input.craneY(), pos.x(), pos.y()),
                    TravelTimeModel.travelTicks(input.speeds(), pos.x(), pos.y(), outputPos.x(), outputPos.y()));
            candidates.add(new Candidate<>(location, NEUTRAL_FILTER_RANK, false, false, travel, rank));
        }
        candidates.sort(RANKING);
        for (Candidate<L> candidate : candidates) {
            if (!budget.tryUse())
                return Optional.empty();
            long reserved = input.reservations().reservedStock(candidate.location(), key);
            int ask = CapacityMath.toIntClamped(limit + reserved);
            long live = input.liveExtract().simulateExtract(candidate.location(), key, ask);
            long amount = Math.min(limit, live - reserved);
            if (amount > 0) {
                TransportJob<K, L> job = type == JobType.SUPPLY
                        ? TransportJob.supply(newId(), candidate.location(), target, key, (int) amount, ownerId)
                        : TransportJob.retrieve(newId(), candidate.location(), target, key, (int) amount, ownerId);
                return Optional.of(new PlannedJob<>(job, tripTicks(input, candidate.location(), target)));
            }
        }
        reasons.add(NoJobReason.NOT_IN_STOCK);
        return Optional.empty();
    }

    // --- target selection ----------------------------------------------------------------------------------------

    /**
     * Storage locations that accept {@code key}, ranked by their store filter ({@link FilterMatch#storeRank()}), then
     * consolidation, then {@code baseTravel} + travel from {@code (fromX, fromY)}. Every path that <b>stores</b> items
     * goes through here (the store plan and both reroutes into storage), so the store filter is honoured exactly once,
     * in one place.
     *
     * @param allowRejected rank locations whose filter rejects {@code key} last instead of dropping them. Only the
     *                      {@code RETRIEVE} reroute passes {@code true}: it puts items back that already left the
     *                      warehouse, so it must be able to return them to the location they came from even after that
     *                      location was re-dedicated (§8)
     * @param survey        collects why locations were skipped, for {@link NoJobReason#NO_MATCHING_FILTER}; may be null
     */
    private Optional<Selection<L>> selectStorage(PlannerInput<K, L> input, K key, int limit, @Nullable L excluded,
            double fromX, double fromY, long baseTravel, Budget budget, boolean allowRejected,
            @Nullable StoreSurvey survey) {
        List<Candidate<L>> candidates = new ArrayList<>();
        int order = 0;
        for (L location : input.storageLocations()) {
            int rank = order++;
            if (location.equals(excluded) || !input.available().test(location)) {
                note(survey, false);
                continue;
            }
            // Cheapest test first: a remembered refusal is two hash lookups, a filter evaluation walks the filter's
            // rules. Both decide before the capacity estimate and before any live call, so a location that could never
            // take this item costs neither a live simulation from the budget nor a remembered refusal (ADR-021).
            if (input.insertRefused().test(location, key)) {
                note(survey, false);
                continue;
            }
            FilterMatch filter = Objects.requireNonNull(input.storeFilter().apply(location, key), "storeFilter result");
            if (!filter.allowsStoring() && !allowRejected) {
                note(survey, true);
                continue;
            }
            long estimate = input.insertEstimate().estimateInsertable(location, key);
            if (estimate - input.reservations().reservedCapacity(location) <= 0) {
                note(survey, false);
                continue;
            }
            boolean consolidates = input.stock().countAt(key, location) > 0;
            // Nothing of another item type (per index): item types are not mixed while such locations are free.
            boolean compatible = consolidates || holdsOnlyTypeOf(input, location, key);
            RackPosition pos = position(location);
            long travel = TravelTimeModel.add(baseTravel,
                    TravelTimeModel.travelTicks(input.speeds(), fromX, fromY, pos.x(), pos.y()));
            candidates.add(new Candidate<>(location, filter.storeRank(), consolidates, compatible, travel, rank));
            if (survey != null)
                survey.ranked = true;
        }
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, limit, budget);
    }

    private static void note(@Nullable StoreSurvey survey, boolean filterRejected) {
        if (survey == null)
            return;
        if (filterRejected)
            survey.filterRejected = true;
        else
            survey.otherSkip = true;
    }

    /** Stations ranked by travel time from the crane. */
    private Optional<Selection<L>> selectStation(PlannerInput<K, L> input, List<L> stations, K key, int limit,
            @Nullable L excluded, Budget budget) {
        List<Candidate<L>> candidates = new ArrayList<>();
        int order = 0;
        for (L location : stations) {
            int rank = order++;
            if (location.equals(excluded) || !input.available().test(location))
                continue;
            RackPosition pos = position(location);
            long travel = TravelTimeModel.travelTicks(input.speeds(), input.craneX(), input.craneY(), pos.x(), pos.y());
            candidates.add(new Candidate<>(location, NEUTRAL_FILTER_RANK, false, false, travel, rank));
        }
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, limit, budget);
    }

    private Optional<Selection<L>> tryInsert(PlannerInput<K, L> input, List<Candidate<L>> candidates, K key, int limit,
            Budget budget) {
        for (Candidate<L> candidate : candidates) {
            if (!budget.tryUse())
                return Optional.empty();
            long reserved = input.reservations().reservedCapacity(candidate.location());
            int ask = CapacityMath.toIntClamped(limit + reserved);
            long live = input.liveInsert().simulateInsert(candidate.location(), key, ask);
            long amount = Math.min(limit, live - reserved);
            if (amount > 0)
                return Optional.of(new Selection<>(candidate.location(), (int) amount, candidate.travelTicks()));
        }
        return Optional.empty();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Whether {@code location} holds no item of another type than {@code key} (true for an empty location). */
    private static <K, L> boolean holdsOnlyTypeOf(PlannerInput<K, L> input, L location, K key) {
        Object type = input.itemType().apply(key);
        for (K stored : input.stock().countsAt(location).keySet()) {
            if (!Objects.equals(type, input.itemType().apply(stored)))
                return false;
        }
        return true;
    }

    private long tripTicks(PlannerInput<K, L> input, L source, L target) {
        RackPosition from = position(source);
        RackPosition to = position(target);
        return TravelTimeModel.tripTicks(input.speeds(), input.transferTicks(), input.craneX(), input.craneY(), from.x(),
                from.y(), to.x(), to.y());
    }

    private RackPosition position(L location) {
        return Objects.requireNonNull(positions.apply(location), "no rack position for " + location);
    }

    private UUID newId() {
        return Objects.requireNonNull(idFactory.get(), "id factory returned null");
    }

    private static int limit(long available, int carryLimit) {
        return (int) Math.max(0L, Math.min(available, carryLimit));
    }

    /**
     * A ranked candidate location.
     *
     * @param filterRank   {@link FilterMatch#storeRank()} of its store filter, the first sort key (storage only);
     *                     {@link #NEUTRAL_FILTER_RANK} where no filter applies (stations, retrieve sources)
     * @param consolidates already holds the item key (storage only)
     * @param compatible   holds nothing or only items of the key's type per index (storage only)
     */
    private record Candidate<L>(L location, int filterRank, boolean consolidates, boolean compatible,
            long travelTicks, int order) {
    }

    /**
     * Why one input station's items fit nowhere, so {@link #plan} can tell {@link NoJobReason#WAREHOUSE_FULL} ("no
     * room", relieved by a retrieval) from {@link NoJobReason#NO_MATCHING_FILTER} ("no filter accepts them", which
     * needs an unfiltered location instead) and from {@link NoJobReason#AT_MAXIMUM} ("a stock rule says the warehouse
     * holds enough of this", which is not a fault at all). Collected over all item types of that input.
     */
    private static final class StoreSurvey {
        /** At least one location entered the ranking: its filter allowed the key and its estimate had room. */
        private boolean ranked;
        /** At least one location was skipped because its store filter rejects the key. */
        private boolean filterRejected;
        /**
         * At least one item type was skipped because a stock rule left no headroom for it (M15). Set at the skip site
         * in {@link #plan}, not through {@link #note}: such a key never reaches {@link #selectStorage}, so an input
         * holding only a capped item would otherwise report {@link NoJobReason#WAREHOUSE_FULL} while eleven empty
         * chests stand behind it.
         */
        private boolean atMaximum;
        /** At least one location was skipped for another reason (unavailable, known refusal, no estimated room). */
        private boolean otherSkip;

        /**
         * Only a run in which <b>every</b> skip was a maximum or a filter mismatch reports one of those, and a
         * maximum wins over a filter mismatch: it is the more specific answer, and the one the player can act on.
         */
        NoJobReason reason() {
            if (ranked || otherSkip)
                return NoJobReason.WAREHOUSE_FULL;
            if (atMaximum)
                return NoJobReason.AT_MAXIMUM;
            return filterRejected ? NoJobReason.NO_MATCHING_FILTER : NoJobReason.WAREHOUSE_FULL;
        }
    }

    private record Selection<L>(L location, int amount, long travelTicks) {
        RerouteTarget<L> target(LocationKind kind) {
            return new RerouteTarget<>(location, kind, amount, travelTicks);
        }
    }

    private static final class Budget {
        private int left;
        private boolean exhausted;

        Budget(int size) {
            this.left = size;
        }

        boolean tryUse() {
            if (left <= 0) {
                exhausted = true;
                return false;
            }
            left--;
            return true;
        }
    }
}
