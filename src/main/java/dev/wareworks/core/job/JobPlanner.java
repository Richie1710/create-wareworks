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
 *   <li><b>ARRIVALS</b> otherwise: one round robin from the cursor over the input stations <b>and</b> the aisle's
 *       collecting warehouse ports as a single virtual list ({@link PlannerInput#inputs()} ++
 *       {@link PlannerInput#collectSources()}, M18, issue #13), so a player's request and a production order's
 *       ingredients are always planned first and collecting can never make somebody wait, while within one full walk
 *       every input and every gated-open collecting port gets its turn. A collect source is planned by
 *       {@link #planCollect}: it stores what the machine behind the port hands out, honours a stock rule's maximum and is
 *       never offered a port as its target. At most {@value #MAX_COLLECT_CANDIDATES} collect sources are examined per
 *       run.</li>
 *   <li><b>STORE</b> for an input station of that walk, skipping empty buffers. Item = the
 *       first non-empty slot. A stock rule's maximum is consulted first ({@link PlannerInput#storeHeadroom()}, M15):
 *       an item with no headroom left is skipped before any candidate work and reported as
 *       {@link NoJobReason#AT_MAXIMUM}, and a headroom smaller than the buffered amount bounds the planned amount, so
 *       the rest stays in the input on purpose. Candidates are the available storage locations whose store filter
 *       accepts the item and whose estimate exceeds their reserved capacity, ranked by (a0) a filter that
 *       <b>selects</b> the item ({@link FilterMatch#DEDICATED}; a deny list that merely does not exclude it is
 *       {@link FilterMatch#ALLOWED} and ranks like an unfiltered location), then (a) already holding the item, then
 *       (a2) holding nothing or only items of the same type ({@link PlannerInput#itemType()}), then (a3) the storage
 *       priority a player gave the location ({@link PlannerInput#storePriority()}, higher first, M16), then (b) travel
 *       time crane → input → location. Amount =
 *       {@code min(buffered, storeHeadroom, carryLimit, liveInsertable − reservedCapacity)}. The aisle's <b>accepting
 *       warehouse ports</b> ({@link PlannerInput#ports()}, M17) are candidates of the same ranking, above (a0) by their
 *       target class: a <b>diversion</b> (positive {@link PlannerInput#portRank()}) outranks every location, an
 *       <b>overflow</b> (negative) loses to every location that may take the item, and their amount is bounded by the
 *       buffer and the carry limit but <b>not</b> by {@code storeHeadroom} — a stock rule's maximum is exactly what makes
 *       an overflow necessary. When an input's items fit nowhere, the reason distinguishes "no room"
 *       ({@link NoJobReason#WAREHOUSE_FULL}) from "no filter accepts them"
 *       ({@link NoJobReason#NO_MATCHING_FILTER}), from "an accepting port was the only place left and it is full"
 *       ({@link NoJobReason#PORT_FULL}) and from "a rule holds enough already"
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
 * buffer, else to an accepting warehouse port (M17: <b>last</b>, whatever its rank, because putting items back into an
 * input is reversible and exporting them is not); retrieve leftovers go back to a storage location, else to another
 * output station (refinement: an output that did not request the items only as the last resort) and <b>never</b> to an
 * accepting port; otherwise the result is empty (the crane holds). The station fallback and the port fallback have a
 * live simulation budget of their own, so storage candidates that used up the budget never hide a station or a port that
 * accepts the items.
 * <p>
 * <b>Store filters and the retrieve reroute</b> (M8 review fix, §8): a {@code RETRIEVE} reroute puts items back that
 * already came <i>out</i> of the warehouse, so a rejecting filter is advisory there — such locations are ranked
 * <b>last</b> instead of dropped, and the location the items were just picked from can therefore always take its own
 * stock back. Every other storing path (the store plan and the store reroute) drops them, so a dedicated location never
 * receives new items its filter rejects.
 * <p>
 * <b>Storage priorities and where they may not reach</b> (M16, issue #11, ADR-028): a player's priority is a property of
 * a storage <i>location</i> ({@link PlannerInput#storePriority()}), it orders only the locations the rules above it left
 * equal, and it is read in exactly one method, {@link #collectStorage}. Retrieval ({@link #planOutOfStorage}), the
 * station fallback ({@link #selectStation}) and the accepting ports ({@link #collectPorts}) pass
 * {@link #NEUTRAL_PRIORITY} or their own strength, so "a high priority must never send the crane past a nearer location
 * that holds the same item" holds structurally and cannot be broken by forgetting a check. On a reroute with
 * {@code allowRejected} the priority is still below the filter class, so a priority can never lift a rejecting location
 * above an accepting one.
 * <p>
 * <b>Accepting warehouse ports</b> (M17, issue #12, ADR-029): a port is a <b>target</b> and nothing else. It is never a
 * source, so retrieval cannot see it — {@link #planOutOfStorage} iterates {@code stock().locationsOf(key)} and a station
 * buffer is never in the stock index, which is what makes "items in a port are never fetched back, never counted as
 * stock and never re-stored" structural rather than a rule. Its rank is read in exactly two methods,
 * {@link #collectPorts} and nothing else, and the sign became the ranking's first key while the magnitude reuses M16's
 * priority key, so M17 added one comparator key in total.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class JobPlanner<K, L> {
    /** Default for {@link PlannerInput#liveSimulationBudget()}. */
    public static final int DEFAULT_LIVE_SIMULATION_BUDGET = 64;
    /**
     * How many accepting warehouse ports one run may offer per item key ({@link #collectPorts}, M17, issue #12).
     * <p>
     * A port consults neither the capacity estimate nor the refusal memory, so — unlike a storage location — it costs one
     * live simulation per key per run for as long as it is gated open. That cost was argued from "the handful of ports an
     * aisle has", which nothing enforced: an aisle has up to {@code maxAisleLength × maxMastHeight × 2} rack positions,
     * so a rack wall of accepting ports could spend the whole {@link PlannerInput#liveSimulationBudget()} on ports every
     * run and never reach a storage location at all — while a heavily restricted <i>warehouse</i> cannot, which is
     * exactly the asymmetry ADR-021 exists to remove. This enforces the claim instead: the port candidates are ranked
     * first and only the best ones are offered, so the cost of the port stage is bounded whatever a player builds.
     * <p>
     * Well above any real build and far below the budget, so an aisle with this many accepting ports or fewer — every
     * aisle anybody builds, and every test — ranks and offers all of them, literally unchanged. Beyond it the ranking
     * still decides <b>which</b> ports are dropped, so a dedicated or higher-ranked port is never cut in favour of a
     * weaker one, and dropping a port is the safe direction in any case: it sends items to storage or backs the input up
     * instead of exporting them, and exporting is the irreversible half.
     */
    public static final int MAX_PORT_CANDIDATES = 12;
    /**
     * How many <b>collecting</b> warehouse ports one run may really examine ({@link #planCollect}, M18, issue #13).
     * <p>
     * The same bound as {@link #MAX_PORT_CANDIDATES} and for the same enforced-not-assumed reason: a collect source costs
     * one live {@code simulateExtract} plus a storage candidate walk, and an aisle has up to
     * {@code maxAisleLength × maxMastHeight × 2} rack positions, so a rack wall of collecting ports could otherwise spend
     * the whole {@link PlannerInput#liveSimulationBudget()} before a storage location or an input station is reached.
     * <p>
     * Sources beyond it are simply not examined <b>this run</b>; the arrival cursor moves on regardless, so the next runs
     * reach them and nothing starves ({@link PlanResult#nextArrivalCursor()}). Dropping a collect source is the safe
     * direction in any case: it leaves the items in the player's machine, where they already are.
     */
    public static final int MAX_COLLECT_CANDIDATES = 12;
    /** Capacity estimate meaning "unknown, ask the live inventory". */
    public static final long UNKNOWN_CAPACITY = Long.MAX_VALUE;
    /** Filter rank of a candidate no store filter applies to (stations, retrieve sources): neither better nor worse. */
    private static final int NEUTRAL_FILTER_RANK = FilterMatch.UNFILTERED.storeRank();
    /**
     * Storage priority of a candidate no priority applies to (M16, issue #11): every path that does <b>not</b> store
     * passes this literally, so retrieval and station ranking are free of priorities <b>by construction</b> rather than
     * by a check. It is also the value of an unprioritised storage location, so a warehouse nobody prioritised ranks
     * exactly as it did before M16.
     */
    private static final int NEUTRAL_PRIORITY = 0;
    /**
     * Rank class of an accepting warehouse port that takes items <b>before</b> they are stored (M17, issue #12): a
     * positive port rank, the diversion.
     */
    private static final int CLASS_DIVERSION = 0;
    /**
     * Rank class of a storage location, and the class every path that does not store passes literally (retrieval
     * sources, station fallbacks). It sits between the two port classes, which is the whole of "storage always wins over
     * an overflow, a diversion always wins over storage": it is the only place those two sentences are written down.
     */
    private static final int CLASS_STORAGE = 1;
    /** Rank class of an accepting warehouse port that only receives what no storage location took: the overflow. */
    private static final int CLASS_OVERFLOW = 2;
    /** The rank of a port that is none: {@link PlannerInput#portRank()} answering this drops the candidate. */
    private static final int NOT_A_PORT = 0;

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

    /**
     * The candidate ranking, lower first: <b>where the items go at all</b> (the target class, M17) → <b>hard rules</b>
     * (the store filter) → <b>automatic tidiness</b> (consolidation, item-type grouping) → <b>explicit player
     * preference</b> (the storage priority, M16, and an accepting port's strength) → <b>cost</b> (travel time) →
     * <b>stability</b> (index order, which makes this a strict total order on distinct candidates).
     * <p>
     * The <b>target class</b> is the first key because "a diversion takes items before they are stored" and "storage
     * always wins over an overflow" are statements about <i>which kind of place</i> the items go to, and they must hold
     * against every other rule — a diversion beats even a {@link FilterMatch#DEDICATED} location, an overflow loses to
     * every location that may take the item. The sign of a port's rank became this class and its magnitude reuses the
     * M16 priority key, so M17 adds <b>one</b> key rather than one per property (ADR-028's promised shape).
     * <p>
     * The priority is compared with {@link Integer#compare} of the swapped operands rather than by negating a value, so
     * a range widened later cannot trip over {@code -Integer.MIN_VALUE}. Adding either key changed nothing for a
     * warehouse without priorities or accepting ports: the key answers 0 for every pair, and {@code thenComparing}
     * consults the next key exactly then, so the comparator is the same function it was before M16 and before M17 (see
     * {@link PlannerInput#NO_PRIORITY}, {@link PlannerInput#NO_PORT_RANK}).
     */
    private static final Comparator<Candidate<?>> RANKING = Comparator
            .comparingInt((Candidate<?> candidate) -> candidate.rankClass())
            .thenComparingInt(Candidate::filterRank)
            .thenComparing((Candidate<?> candidate) -> !candidate.consolidates())
            .thenComparing((Candidate<?> candidate) -> !candidate.compatible())
            .thenComparing((a, b) -> Integer.compare(b.priority(), a.priority()))
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
        List<L> collectSources = input.collectSources();
        // One round robin over both kinds of arrival (M18, issue #13): the input stations, then the collecting ports. A
        // separate stage after storing would starve collecting for as long as any input is permanently non-empty — which
        // is exactly the production loop collecting exists for (a belt keeps feeding the input while the machine's result
        // chest fills). The accepted consequence is that an input can be delayed by one trip behind a collecting port,
        // which is precisely how two input stations already treat each other.
        int arrivals = inputs.size() + collectSources.size();
        int start = arrivals == 0 ? 0 : Math.floorMod(input.inputCursor(), arrivals);
        boolean work = false;
        int collectsExamined = 0;
        /**
         * The first collect source this run did not examine because {@link #MAX_COLLECT_CANDIDATES} was reached, or -1.
         * Once it is set it becomes the cursor of <b>every</b> return of the arrival walk — the empty one and the ones
         * that found a job — because a run leaving its own cursor there would make the run after it examine the same
         * sources again and never reach the ones beyond the cap. This is what makes "the cap drops a source for one run,
         * not for ever" true, also next to an input station that plans a job on every single run.
         */
        int cappedCursor = -1;

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

        for (int i = 0; i < arrivals; i++) {
            int index = (start + i) % arrivals;
            // Where the next run starts, whatever this one returns: the first source the cap dropped if there was one,
            // otherwise the arrival after this one. Honoured on *every* return of this walk, not only on the empty one —
            // a run that hit the cap among the collect sources and then wrapped round to an input station that produced
            // the job is exactly the run in which the skipped sources need it, and leaving the input's own cursor there
            // would make the next run examine the same leading MAX_COLLECT_CANDIDATES sources again, for ever.
            int next = cappedCursor >= 0 ? cappedCursor : (index + 1) % arrivals;
            if (index >= inputs.size()) {
                L source = collectSources.get(index - inputs.size());
                // Beyond the cap the source is not examined this run; the cursor is left here instead, so the next run
                // starts at the first source this one skipped (MAX_COLLECT_CANDIDATES).
                if (collectsExamined >= MAX_COLLECT_CANDIDATES) {
                    if (cappedCursor < 0)
                        cappedCursor = index;
                    continue;
                }
                if (!input.available().test(source))
                    continue;
                InventorySnapshot<K> held = input.collectBuffers().apply(source);
                if (held == null || held.isEmpty())
                    continue;
                work = true;
                collectsExamined++;
                Optional<PlannedJob<K, L>> job = planCollect(input, source, held, budget, reasons);
                if (job.isPresent())
                    return new PlanResult<>(job, reasons, next);
                if (budget.exhausted) {
                    reasons.add(NoJobReason.BUDGET_EXHAUSTED);
                    return new PlanResult<>(Optional.empty(), reasons, next);
                }
                continue;
            }
            L station = inputs.get(index);
            if (!input.available().test(station))
                continue;
            InventorySnapshot<K> buffer = input.inputBuffers().apply(station);
            if (buffer == null || buffer.isEmpty())
                continue;
            work = true;
            RackPosition stationPos = position(station);
            long toStation = input.travel().travelTicks(input.craneBranch(), input.craneX(), input.craneY(),
                    stationPos.branch(), stationPos.x(), stationPos.y());
            // Why this input got no job: "no room anywhere" and "no filter accepts these items" are different problems
            // for the player, and only the first is relieved by a retrieval (ADR-021, §7.4).
            StoreSurvey survey = new StoreSurvey();
            for (K key : buffer.keys()) {
                // What one trip can carry of this key, whatever it ends up being carried to. This bounds an accepting
                // port's amount (M17): a port is not storage, so no stock rule's maximum applies to it.
                int portLimit = limit(buffer.count(key), input.carryLimit().applyAsInt(key));
                if (portLimit < 1)
                    continue;
                // A stock rule's maximum decides before anything else costs something: one lookup, no candidate walk,
                // no estimate, no live call and no remembered refusal (M15, issue #3). Partial storing is normal and
                // exact — stock 1990, maximum 2048 and a buffer of 64 store 58 and leave 6 in the input on purpose.
                // It gates the STORAGE candidates only: an item at its maximum contributes none at all and still
                // reaches the accepting ports, which is the whole reason an overflow exists (M17).
                long headroom = input.storeHeadroom().applyAsLong(key);
                if (headroom <= 0) {
                    // The key is not skipped any more, but it contributes no storage candidate: the buffer's other item
                    // types and then the next input are tried as before, so one capped item never blocks a station.
                    survey.atMaximum = true;
                }
                int storageLimit = headroom <= 0 ? 0
                        : limit(Math.min(buffer.count(key), headroom), input.carryLimit().applyAsInt(key));
                Optional<Selection<L>> selection = selectStoreTarget(input, key, portLimit, storageLimit, null,
                        stationPos.branch(), stationPos.x(), stationPos.y(), toStation, budget, survey);
                if (selection.isPresent()) {
                    L target = selection.get().location();
                    TransportJob<K, L> job = selection.get().kind() == LocationKind.STORAGE
                            ? TransportJob.store(newId(), station, target, key, selection.get().amount())
                            : TransportJob.storeToPort(newId(), station, target, key, selection.get().amount());
                    return new PlanResult<>(Optional.of(new PlannedJob<>(job, tripTicks(input, station, target))),
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
        return new PlanResult<>(Optional.empty(), reasons, cappedCursor < 0 ? start : cappedCursor);
    }

    /**
     * Finds a new target for {@code amount} items of {@code key} left in the handling head of a job of {@code type}
     * whose target {@code failedTarget} failed (§8). The crane position of {@code input} is the travel origin.
     *
     * @param failedTarget the target to exclude, or {@code null} (a hold retry: every location may be chosen)
     * @return the best target that accepts at least one item in the live simulation, or empty (hold the items); at most
     * three times {@link PlannerInput#liveSimulationBudget()} live calls for a {@code STORE} reroute (storage, then the
     * input stations, then the accepting ports), twice for a {@code RETRIEVE} one (storage, then the output stations) and
     * for a {@code COLLECT} one (storage, then the input stations), and once for a {@code SUPPLY} one (storage alone)
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
        int branch = input.craneBranch();
        double x = input.craneX();
        double y = input.craneY();
        return switch (type) {
            // Storing leftovers is still storing, so a rejecting filter drops the location here as in the store plan.
            // An accepting port is the LAST resort, after the input buffers and whatever its rank says (M17): putting
            // items back into an input is reversible and exporting them is not, and it converges to the same outcome
            // anyway, because the next store plan offers them to the port. The port list is the gated one, so "off means
            // off" holds on a reroute too, and a port's filter is hard here — which cannot park the crane, because
            // advisory-filter storage is still a target and HOLDING is still the floor.
            case STORE -> selectStorage(input, key, amount, failedTarget, branch, x, y, 0L, budget, false, null)
                    .map(Selection::target)
                    .or(() -> selectStation(input, input.inputs(), LocationKind.INPUT, key, amount, failedTarget,
                            new Budget(input.liveSimulationBudget())).map(Selection::target))
                    .or(() -> selectPorts(input, key, amount, failedTarget, branch, x, y,
                            new Budget(input.liveSimulationBudget())).map(Selection::target));
            // Back into storage first: another output never asked for these items (its own requests are served by
            // their own jobs), so delivering there would over-deliver. Only when no storage location accepts them.
            // Store filters are advisory here (M8 review fix): these items already left the warehouse, so a location
            // whose filter rejects them is ranked last rather than dropped — otherwise a location that was
            // re-dedicated while its stock was inside could not take that stock back, and a fully partitioned aisle
            // could park the crane in HOLDING for ever (§8).
            case RETRIEVE -> selectStorage(input, key, amount, failedTarget, branch, x, y, 0L, budget, true, null)
                    .map(Selection::target)
                    .or(() -> selectStation(input, withoutPorts(input, input.outputs()), LocationKind.OUTPUT, key,
                            amount, failedTarget, new Budget(input.liveSimulationBudget())).map(Selection::target));
            // Supply leftovers go back into storage and nowhere else: nobody requested them at a station, and putting
            // them into an output would hand a player ingredients they never asked for (ADR-024). Rejecting filters
            // are advisory here for the same reason as on a retrieve reroute — these items already left the warehouse.
            // Retrieve and supply leftovers are never offered to an accepting port (M17): only items the warehouse chose
            // not to store may leave through one, so a player can reason that what comes out of a port is surplus and
            // the mod never quietly feeds a shredder with items somebody requested.
            case SUPPLY -> selectStorage(input, key, amount, failedTarget, branch, x, y, 0L, budget, true, null)
                    .map(Selection::target);
            // Collected items are storing items (M18, issue #13), so a rejecting filter drops the location as in the
            // store plan, and the fallback is an input buffer — from where they are stored normally. A port is
            // deliberately <b>not</b> the last resort here, unlike a store reroute: JobType.COLLECT does not allow an
            // OUTPUT target at all, which is what keeps a diversion from defeating the headroom that stopped the
            // collecting in the first place (§5, guard 2). The station fallback gets its own budget, so storage
            // candidates that used it up never hide an input that accepts the items.
            case COLLECT -> selectStorage(input, key, amount, failedTarget, branch, x, y, 0L, budget, false, null)
                    .map(Selection::target)
                    .or(() -> selectStation(input, input.inputs(), LocationKind.INPUT, key, amount, failedTarget,
                            new Budget(input.liveSimulationBudget())).map(Selection::target));
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
                    input.travel().travelTicks(input.craneBranch(), input.craneX(), input.craneY(), pos.branch(),
                            pos.x(), pos.y()),
                    input.travel().travelTicks(pos.branch(), pos.x(), pos.y(), outputPos.branch(), outputPos.x(),
                            outputPos.y()));
            // Retrieval never reads a storage priority (M16) and never a port rank (M17): the neutral values are passed
            // literally, so the shortest path wins and neither a prioritised location nor a port can send the crane past
            // a nearer source of the same item.
            candidates.add(new Candidate<>(location, LocationKind.STORAGE, CLASS_STORAGE, NEUTRAL_FILTER_RANK, false,
                    false, NEUTRAL_PRIORITY, travel, rank, limit));
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

    // --- collect (M18, issue #13) --------------------------------------------------------------------------------

    /**
     * One collecting warehouse port ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13): fetch items out of the
     * inventory behind {@code source} and store them, the mirror image of the store branch with the port in the input
     * station's place.
     * <p>
     * What it does <b>not</b> do, and why each is the loop answer of §5 made structural:
     * <ul>
     * <li>it never offers a <b>port</b> as the target — only {@link #selectStorage} is called, so a diversion, which
     * ignores the headroom, can never receive a collected item (and {@link JobType#COLLECT} would refuse the job anyway);
     * </li>
     * <li>it <b>honours {@link PlannerInput#storeHeadroom()}</b>, and a key with no headroom left yields no job at all.
     * That is the exact opposite of an accepting port, which deliberately ignores it, and the asymmetry is the point: a
     * maximum is what makes an overflow necessary and what makes collecting stop, so the two preconditions of the churn
     * loop are mutually exclusive;</li>
     * <li>it reserves nothing at the source: a foreign inventory is not indexed stock, so two aisles sharing a rack plane
     * may both plan against the same chest and the real extract decides (§8).</li>
     * </ul>
     * Why it got no job is answered by {@link StoreSurvey#collectReason()} rather than {@link StoreSurvey#reason()}: a
     * machine that hands out nothing is not a full warehouse, and reporting one would both mislead and arm the aisle's
     * back-off against every input station (M18 review).
     * <p>
     * The port's own <b>filter is hard</b> and answered by the same {@code storeFilter} a storage location's is, so a
     * rejecting key is dropped before any live call. Keys are tried in the cached snapshot's order and fall through to
     * the next key and then to the next source, so one unstorable item never blocks a collecting port — the store
     * branch's refinement, extended.
     *
     * @param held the controller's <b>cached</b> snapshot of the attached inventory; the live extract bounds the amount
     *             and the real pick stays authoritative
     */
    private Optional<PlannedJob<K, L>> planCollect(PlannerInput<K, L> input, L source, InventorySnapshot<K> held,
            Budget budget, Set<NoJobReason> reasons) {
        RackPosition sourcePos = position(source);
        long toSource = input.travel().travelTicks(input.craneBranch(), input.craneX(), input.craneY(),
                sourcePos.branch(), sourcePos.x(), sourcePos.y());
        StoreSurvey survey = new StoreSurvey();
        for (K key : held.keys()) {
            // The port's filter first: cheapest test, and a rejected key must not cost a live call (ADR-021). It is
            // noted, because a survey with no flag at all answers "warehouse full" — which is neither true nor harmless
            // here: it is the one reason that arms the aisle's back-off (M18 review).
            FilterMatch filter = Objects.requireNonNull(input.storeFilter().apply(source, key), "storeFilter result");
            if (!filter.allowsStoring()) {
                survey.collectNothingToFetch = true;
                continue;
            }
            // Then a stock rule's maximum, per item type and before any candidate work (M15): a key at its maximum is
            // not collected at all, which is guard 1 of the churn loop.
            long headroom = input.storeHeadroom().applyAsLong(key);
            if (headroom <= 0) {
                survey.atMaximum = true;
                continue;
            }
            int limit = limit(Math.min(held.count(key), headroom), input.carryLimit().applyAsInt(key));
            if (limit < 1) {
                survey.collectNothingToFetch = true;
                continue;
            }
            if (!budget.tryUse())
                return Optional.empty();
            // What the machine really hands out right now. A stale snapshot therefore costs one simulation rather than a
            // wasted trip; the real pick still decides, and a zero pick aborts cleanly (§2). Nothing handed out is noted
            // as such and never as a full warehouse: no storage candidate was even looked at yet (M18 review).
            int extractable = input.liveExtract().simulateExtract(source, key, limit);
            if (extractable < 1) {
                survey.collectNothingToFetch = true;
                continue;
            }
            Optional<Selection<L>> selection = selectStorage(input, key, Math.min(limit, extractable), null,
                    sourcePos.branch(), sourcePos.x(), sourcePos.y(), toSource, budget, false, survey);
            if (selection.isPresent()) {
                L target = selection.get().location();
                TransportJob<K, L> job = TransportJob.collect(newId(), source, target, key,
                        selection.get().amount());
                return Optional.of(new PlannedJob<>(job, tripTicks(input, source, target)));
            }
            if (budget.exhausted)
                return Optional.empty();
        }
        reasons.add(survey.collectReason());
        return Optional.empty();
    }

    // --- target selection ----------------------------------------------------------------------------------------

    /**
     * The store plan's <b>one</b> ranking (M17, issue #12): the aisle's accepting warehouse ports and its storage
     * locations in a single candidate list, ranked by {@link #RANKING} and tried in that order with the existing live
     * fall-through. A diversion port therefore outranks every location, an overflow port loses to every location that may
     * take the item, and ports among themselves rank by filter, then strength, then travel time, then index order.
     * <p>
     * The two limits are separate on purpose, and this is the crux of the M15 interaction: {@code portLimit} is what one
     * trip can carry, {@code storageLimit} is that bounded by the stock rule's headroom. A key at its maximum passes
     * {@code storageLimit == 0}, which contributes <b>no storage candidate at all</b> — no estimate, no live call and no
     * remembered refusal, exactly as before M17 — and still reaches the ports, which is the whole reason an overflow
     * exists.
     *
     * @param portLimit    the amount a port candidate may take (0 for none)
     * @param storageLimit the amount a storage candidate may take (0 for none, e.g. a key at its maximum)
     */
    private Optional<Selection<L>> selectStoreTarget(PlannerInput<K, L> input, K key, int portLimit, int storageLimit,
            @Nullable L excluded, int fromBranch, double fromX, double fromY, long baseTravel, Budget budget,
            @Nullable StoreSurvey survey) {
        List<Candidate<L>> candidates = new ArrayList<>();
        collectPorts(input, candidates, key, portLimit, excluded, fromBranch, fromX, fromY, baseTravel, survey);
        collectStorage(input, candidates, key, storageLimit, excluded, fromBranch, fromX, fromY, baseTravel, false,
                survey);
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, budget);
    }

    /**
     * Storage locations that accept {@code key}, ranked by their store filter ({@link FilterMatch#storeRank()}), then
     * consolidation, then item-type compatibility, then the location's storage priority (M16), then {@code baseTravel} +
     * travel from {@code (fromX, fromY)}. Every path that <b>stores into a location</b> goes through here (the store plan
     * through {@link #selectStoreTarget} and both reroutes into storage), so the store filter and the priority are each
     * honoured exactly once, in one place — and nowhere else, which is what keeps priorities out of retrieval.
     *
     * @param allowRejected rank locations whose filter rejects {@code key} last instead of dropping them. Only the
     *                      {@code RETRIEVE} reroute passes {@code true}: it puts items back that already left the
     *                      warehouse, so it must be able to return them to the location they came from even after that
     *                      location was re-dedicated (§8)
     * @param survey        collects why locations were skipped, for {@link NoJobReason#NO_MATCHING_FILTER}; may be null
     */
    private Optional<Selection<L>> selectStorage(PlannerInput<K, L> input, K key, int limit, @Nullable L excluded,
            int fromBranch, double fromX, double fromY, long baseTravel, Budget budget, boolean allowRejected,
            @Nullable StoreSurvey survey) {
        List<Candidate<L>> candidates = new ArrayList<>();
        collectStorage(input, candidates, key, limit, excluded, fromBranch, fromX, fromY, baseTravel, allowRejected,
                survey);
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, budget);
    }

    /**
     * The output stations of {@code outputs} that are <b>not</b> accepting warehouse ports (M17, issue #12). The last
     * resort of a retrieve reroute is "another output station that did not ask for these items", and an accepting port
     * must never be that station: only items the warehouse chose not to store may leave through a port, so a player can
     * reason that what comes out of one is surplus. Returns {@code outputs} itself while there are no ports, so an aisle
     * without them reroutes exactly as it did before M17.
     * <p>
     * <b>Defensive</b> for the production content layer, which already keeps accepting ports out of
     * {@link PlannerInput#outputs()} ({@code CraneDispatch#rerouteOutputs}, so that a port which is currently gated shut
     * is not a retrieve-reroute target either — a rule this method cannot express, because it only sees the ports that
     * are gated open). It stays because it is the one place the rule is written inside the planner, and because an input
     * built with a port in both lists must not export a retrieve's leftovers.
     */
    private static <K, L> List<L> withoutPorts(PlannerInput<K, L> input, List<L> outputs) {
        if (input.ports().isEmpty())
            return outputs;
        List<L> result = new ArrayList<>(outputs.size());
        for (L output : outputs) {
            if (!input.ports().contains(output))
                result.add(output);
        }
        return result;
    }

    /** Accepting warehouse ports alone, for the last resort of a store reroute (§8, M17). */
    private Optional<Selection<L>> selectPorts(PlannerInput<K, L> input, K key, int limit, @Nullable L excluded,
            int fromBranch, double fromX, double fromY, Budget budget) {
        List<Candidate<L>> candidates = new ArrayList<>();
        collectPorts(input, candidates, key, limit, excluded, fromBranch, fromX, fromY, 0L, null);
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, budget);
    }

    /** Adds the storage candidates for {@code key} to {@code candidates}; see {@link #selectStorage}. */
    private void collectStorage(PlannerInput<K, L> input, List<Candidate<L>> candidates, K key, int limit,
            @Nullable L excluded, int fromBranch, double fromX, double fromY, long baseTravel, boolean allowRejected,
            @Nullable StoreSurvey survey) {
        if (limit < 1)
            return;
        // The available storage locations, derived once for the whole pass rather than per (station x item type)
        // (M22, issue #2): availability is the most expensive question about a location and nothing can change it
        // while the planner ranks. Each kept location keeps the index it has in the full list, which is the last
        // ranking key, so filtering cannot reorder two candidates that are otherwise equal.
        LocationAvailability.Available<L> reachable = input.available().available(input.storageLocations());
        if (reachable.anySkipped())
            note(survey, false); // one note is one flag: the survey counts kinds of skip, never skips
        List<L> locations = reachable.locations();
        for (int at = 0; at < locations.size(); at++) {
            L location = locations.get(at);
            int rank = reachable.rankOf(at);
            if (location.equals(excluded)) {
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
            // The one place a storage priority is read (M16), and only once the location may take the item at all: it
            // decides among the locations the rules above left equal, and never against them.
            int priority = input.storePriority().applyAsInt(location);
            RackPosition pos = position(location);
            long travel = TravelTimeModel.add(baseTravel,
                    input.travel().travelTicks(fromBranch, fromX, fromY, pos.branch(), pos.x(), pos.y()));
            candidates.add(new Candidate<>(location, LocationKind.STORAGE, CLASS_STORAGE, filter.storeRank(),
                    consolidates, compatible, priority, travel, rank, limit));
            if (survey != null)
                survey.storageRanked = true;
        }
    }

    /**
     * Adds the accepting warehouse ports for {@code key} to {@code candidates} (M17, issue #12). Their class comes from
     * the <b>sign</b> of {@link PlannerInput#portRank()} and their ranking strength from its magnitude, so one signed
     * number carries both.
     * <p>
     * What a port candidate deliberately does <b>not</b> consult:
     * <ul>
     * <li>the <b>capacity estimate</b> — a station buffer has no index snapshot, so the estimate could only answer
     * "unknown"; the reservation is subtracted by {@link #tryInsert} as for every candidate;</li>
     * <li>the <b>refusal memory</b>, which stays storage-only: a port has no snapshot round robin that would forget an
     * entry, so a remembered refusal would ignore it long after a funnel emptied it. The cost is therefore one live call
     * per offered port per key per run, and {@value #MAX_PORT_CANDIDATES} of them at most: with more gated-open ports
     * than that the candidates are ranked here and only the best are offered, so no rack wall of ports can spend the
     * budget a storage location needs ({@link #MAX_PORT_CANDIDATES});</li>
     * <li>{@link PlannerInput#storeHeadroom()} — a port is not storage, and a maximum is exactly what makes an overflow
     * necessary;</li>
     * <li>{@link PlannerInput#storePriority()} — that is a property of a storage <i>location</i> (ADR-028); a port's
     * strength travels in its own rank.</li>
     * </ul>
     * A port's <b>filter is hard</b> everywhere, including the reroute path: an unfiltered port takes anything, a
     * filtered one only its item, and a rejected one is dropped before any live call, so a rack wall of filtered ports
     * can no more eat the live-simulation budget than a partitioned warehouse can (ADR-021).
     */
    private void collectPorts(PlannerInput<K, L> input, List<Candidate<L>> candidates, K key, int limit,
            @Nullable L excluded, int fromBranch, double fromX, double fromY, long baseTravel,
            @Nullable StoreSurvey survey) {
        if (limit < 1 || input.ports().isEmpty())
            return;
        // Collected apart from the caller's list so the cap can be applied by rank; merged unsorted when it does not bite,
        // which makes an aisle with at most MAX_PORT_CANDIDATES accepting ports the same function it was.
        List<Candidate<L>> ports = new ArrayList<>();
        int order = 0;
        for (L port : input.ports()) {
            int rank = order++;
            int portRank = input.portRank().applyAsInt(port);
            // A list and a rank function that disagree must never export anything: rank 0 is "this is no accepting
            // port", which is also what a content layer that could not read a port's policy answers.
            if (portRank == NOT_A_PORT || port.equals(excluded) || !input.available().test(port))
                continue;
            FilterMatch filter = Objects.requireNonNull(input.storeFilter().apply(port, key), "storeFilter result");
            if (!filter.allowsStoring())
                continue;
            RackPosition pos = position(port);
            long travel = TravelTimeModel.add(baseTravel,
                    input.travel().travelTicks(fromBranch, fromX, fromY, pos.branch(), pos.x(), pos.y()));
            ports.add(new Candidate<>(port, LocationKind.OUTPUT,
                    portRank > NOT_A_PORT ? CLASS_DIVERSION : CLASS_OVERFLOW, filter.storeRank(), false, false,
                    portStrength(portRank), travel, rank, limit));
            if (survey != null)
                survey.portRanked = true;
        }
        if (ports.size() <= MAX_PORT_CANDIDATES) {
            candidates.addAll(ports);
            return;
        }
        // More gated-open ports than one run may live-test: the same comparator the caller uses decides which survive, so
        // the cap drops the weakest ports and never the port the ranking wanted.
        ports.sort(RANKING);
        candidates.addAll(ports.subList(0, MAX_PORT_CANDIDATES));
    }

    /**
     * The ranking strength of a port rank: its magnitude minus one, so the weakest overflow ({@code -1}) and the weakest
     * diversion ({@code +1}) both rank 0 among their own class. Computed in {@code long} so a rank of
     * {@link Integer#MIN_VALUE} from a broken caller cannot overflow.
     */
    private static int portStrength(int portRank) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, Math.abs((long) portRank) - 1L));
    }

    private static void note(@Nullable StoreSurvey survey, boolean filterRejected) {
        if (survey == null)
            return;
        if (filterRejected)
            survey.filterRejected = true;
        else
            survey.otherSkip = true;
    }

    /** Stations of one {@code kind} ranked by travel time from the crane. */
    private Optional<Selection<L>> selectStation(PlannerInput<K, L> input, List<L> stations, LocationKind kind, K key,
            int limit, @Nullable L excluded, Budget budget) {
        List<Candidate<L>> candidates = new ArrayList<>();
        int order = 0;
        for (L location : stations) {
            int rank = order++;
            if (location.equals(excluded) || !input.available().test(location))
                continue;
            RackPosition pos = position(location);
            long travel = input.travel().travelTicks(input.craneBranch(), input.craneX(), input.craneY(),
                    pos.branch(), pos.x(), pos.y());
            // A station is no storage location: it has no store filter, no storage priority (M16) and no class of its
            // own (M17) — a station fallback is reached only when nothing else took the items.
            candidates.add(new Candidate<>(location, kind, CLASS_STORAGE, NEUTRAL_FILTER_RANK, false, false,
                    NEUTRAL_PRIORITY, travel, rank, limit));
        }
        candidates.sort(RANKING);
        return tryInsert(input, candidates, key, budget);
    }

    private Optional<Selection<L>> tryInsert(PlannerInput<K, L> input, List<Candidate<L>> candidates, K key,
            Budget budget) {
        for (Candidate<L> candidate : candidates) {
            if (!budget.tryUse())
                return Optional.empty();
            int limit = candidate.limit();
            long reserved = input.reservations().reservedCapacity(candidate.location());
            int ask = CapacityMath.toIntClamped(limit + reserved);
            long live = input.liveInsert().simulateInsert(candidate.location(), key, ask);
            long amount = Math.min(limit, live - reserved);
            if (amount > 0)
                return Optional.of(new Selection<>(candidate.location(), candidate.kind(), (int) amount,
                        candidate.travelTicks()));
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
        long stop = TravelTimeModel.stopTicks(input.speeds(), input.transferTicks());
        long total = input.travel().travelTicks(input.craneBranch(), input.craneX(), input.craneY(), from.branch(),
                from.x(), from.y());
        total = TravelTimeModel.add(total, stop);
        total = TravelTimeModel.add(total,
                input.travel().travelTicks(from.branch(), from.x(), from.y(), to.branch(), to.x(), to.y()));
        return TravelTimeModel.add(total, stop);
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
     * @param kind         what it is, which the store plan needs to tell a storage location from an accepting warehouse
     *                     port and a reroute needs for its {@link RerouteTarget} (M17)
     * @param rankClass    where the items go at all, the first sort key (M17): {@link #CLASS_DIVERSION},
     *                     {@link #CLASS_STORAGE} or {@link #CLASS_OVERFLOW}. Every path that does not store passes
     *                     {@link #CLASS_STORAGE} literally, so the key answers 0 for every pair there
     * @param filterRank   {@link FilterMatch#storeRank()} of its store filter (storage locations and ports);
     *                     {@link #NEUTRAL_FILTER_RANK} where no filter applies (stations, retrieve sources)
     * @param consolidates already holds the item key (storage only)
     * @param compatible   holds nothing or only items of the key's type per index (storage only)
     * @param priority     the storage priority a player gave the location, higher first (M16), or an accepting port's
     *                     strength (M17); {@link #NEUTRAL_PRIORITY} where neither applies (stations, retrieve sources)
     * @param limit        the most items this candidate may take, which differs between a port and a storage location
     *                     whenever a stock rule's maximum bounds the latter (M15 × M17)
     */
    private record Candidate<L>(L location, LocationKind kind, int rankClass, int filterRank, boolean consolidates,
            boolean compatible, int priority, long travelTicks, int order, int limit) {
    }

    /**
     * Why one input station's items fit nowhere, so {@link #plan} can tell {@link NoJobReason#WAREHOUSE_FULL} ("no
     * room", relieved by a retrieval) from {@link NoJobReason#NO_MATCHING_FILTER} ("no filter accepts them", which
     * needs an unfiltered location instead), from {@link NoJobReason#AT_MAXIMUM} ("a stock rule says the warehouse
     * holds enough of this", which is not a fault at all) and from {@link NoJobReason#PORT_FULL} ("an accepting port was
     * the only place left for them and it is full", M17). Collected over all item types of that input.
     */
    private static final class StoreSurvey {
        /** At least one storage location entered the ranking: its filter allowed the key and its estimate had room. */
        private boolean storageRanked;
        /**
         * At least one accepting warehouse port entered the ranking (M17): its filter allowed the key. Told apart from
         * {@link #storageRanked} because a run in which only ports were ranked and nothing was planned means the port is
         * full, not that the warehouse is.
         */
        private boolean portRanked;
        /** At least one location was skipped because its store filter rejects the key. */
        private boolean filterRejected;
        /**
         * At least one item type was skipped because a stock rule left no headroom for it (M15). Set at the skip site
         * in {@link #plan}, not through {@link #note}: such a key contributes no storage candidate at all, so an input
         * holding only a capped item would otherwise report {@link NoJobReason#WAREHOUSE_FULL} while eleven empty
         * chests stand behind it.
         */
        private boolean atMaximum;
        /** At least one location was skipped for another reason (unavailable, known refusal, no estimated room). */
        private boolean otherSkip;
        /**
         * Collect only (M18, issue #13): at least one item type in the machine behind a collecting port was skipped
         * before any storage candidate was looked at — the port's own filter rejects it, or the live extract handed
         * nothing out. Set at the skip sites in {@link #planCollect}, like {@link #atMaximum} and for the same reason:
         * such a key ranks nothing at all, so a survey without it would answer "the warehouse is full" about a machine
         * that simply has nothing ready.
         */
        private boolean collectNothingToFetch;

        /**
         * Only a run in which <b>every</b> skip was a full port, a maximum or a filter mismatch reports one of those,
         * and the more specific answer wins: a storage location that was ranked or skipped for room is a genuinely full
         * warehouse, then a full accepting port (the thing to go and fix), then a maximum (not a fault at all), then a
         * filter mismatch.
         */
        NoJobReason reason() {
            if (storageRanked || otherSkip)
                return NoJobReason.WAREHOUSE_FULL;
            if (portRanked)
                return NoJobReason.PORT_FULL;
            if (atMaximum)
                return NoJobReason.AT_MAXIMUM;
            return filterRejected ? NoJobReason.NO_MATCHING_FILTER : NoJobReason.WAREHOUSE_FULL;
        }

        /**
         * The same ladder for a <b>collect</b> run (M18, issue #13), with the two ends different because a collecting
         * port is not an input station:
         * <ul>
         * <li>{@link NoJobReason#PORT_FULL} cannot happen — {@link #planCollect} only ever ranks storage locations, so
         * no port enters the ranking and a collected item can never be handed back out (the loop answer);</li>
         * <li>the <b>default</b> is {@link NoJobReason#COLLECT_SOURCE_EMPTY} instead of
         * {@link NoJobReason#WAREHOUSE_FULL}. A machine that hands out nothing is the resting state of every production
         * loop, and answering "the warehouse is full" about it would be wrong on the goggles <b>and</b> expensive: it is
         * the reason that arms the aisle's {@code fullBackoffTicks} back-off, which would then suspend storing from
         * every input station of the aisle for as long as the machine stays empty.</li>
         * </ul>
         * A genuinely full warehouse, a storage filter that accepts none of the collected items and a stock rule's
         * maximum are reported exactly as they are for an input station: those are answers about the warehouse, and the
         * items really did reach its candidates.
         */
        NoJobReason collectReason() {
            if (storageRanked || otherSkip)
                return NoJobReason.WAREHOUSE_FULL;
            if (atMaximum)
                return NoJobReason.AT_MAXIMUM;
            if (filterRejected)
                return NoJobReason.NO_MATCHING_FILTER;
            return NoJobReason.COLLECT_SOURCE_EMPTY;
        }
    }

    private record Selection<L>(L location, LocationKind kind, int amount, long travelTicks) {
        RerouteTarget<L> target() {
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
