package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.JobPlanner;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.PlanResult;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.RefusalMemory;
import dev.wareworks.core.job.RerouteTarget;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.RouteTable;
import dev.wareworks.util.LogThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Job dispatch of a warehouse controller ({@code docs/warehouse-system.md} §7.1, §7.3, §8): plans jobs for the linked
 * crane, keeps the {@link ReservationLedger} and answers reroute requests. It never moves items. Owned by one
 * {@link WarehouseControllerBlockEntity}; server thread only.
 * <p>
 * <b>Production</b> ({@code docs/warehouse-system.md} §3.5, ADR-024): the ingredients open production orders still owe
 * their stations are handed to the planner as {@code supplies}, which plans them as {@code SUPPLY} jobs after the
 * retrieval requests and before storing. The controller owns the orders themselves; dispatch only moves their items.
 * <p>
 * <b>Dispatch</b> runs every {@code dispatchIntervalTicks}, only while the dock is loaded and
 * {@link StackerCraneBlockEntity#canAcceptJob()} (idle, empty head, powered, not paused, its chunk ticking). During a
 * {@code fullBackoffTicks} back-off after {@link NoJobReason#WAREHOUSE_FULL} only retrieves are planned (a player waits
 * for them, and they free space). Without open requests and buffered input items nothing is planned (one block entity
 * lookup per input station). Otherwise it builds a {@link PlannerInput} from the membership records (shared inventory
 * aliases excluded), the stock index, the request queue, the input buffers and live simulations through
 * {@link TransferContexts} (interface capability, input extract, output insert), plans, reserves the job and assigns it.
 * <p>
 * <b>Refusals.</b> A storage location whose live simulation gives nothing is remembered for that key
 * ({@link RefusalMemory}) until it is read again ({@link #forgetRefusals}) or {@value #REFUSAL_MEMORY_TICKS} ticks pass;
 * the planner skips it without spending its live simulation budget, so more restricted locations than the budget never
 * stall planning.
 * <p>
 * <b>Store filters</b> ({@code docs/warehouse-system.md} §3.1, ADR-021) are answered from the controller's
 * {@link AisleFilters} cache, so a planning run costs one map lookup per storage candidate instead of one block entity
 * lookup per candidate. A location whose filter rejects the item is skipped before the capacity estimate, so it never
 * reaches a live simulation and never enters the refusal memory. {@link NoJobReason#NO_MATCHING_FILTER} arms the same
 * {@code fullBackoffTicks} back-off as {@link NoJobReason#WAREHOUSE_FULL}: it is at least as persistent (no retrieval
 * ever makes a filter match), and the candidate scan it would repeat is longest in a heavily partitioned warehouse.
 * <p>
 * <b>Accepting warehouse ports</b> ({@code docs/warehouse-system.md} §3.2, M17, issue #12, ADR-029) reach the planner as
 * {@code ports} plus {@code portRank}, answered from the controller's {@link AislePorts} cache. The <b>whole policy</b> is
 * applied here rather than in the planner: the list contains only the ports whose direction accepts and whose redstone
 * gate is open right now ({@code WarehouseControllerBlockEntity#acceptingPorts}), so "off means off" is a property of the
 * list and the planner stays free of redstone. A port's filter is answered by the same {@code storeFilter} as a storage
 * location's, so a rejecting one is dropped before any live simulation, and a port is never entered into the refusal
 * memory: it has no snapshot round robin that would forget the entry.
 * <p>
 * <b>Collecting warehouse ports</b> ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13) reach the planner as
 * {@code collectSources} plus {@code collectBuffers}, answered from the controller's {@link AisleCollections} cache. The
 * whole policy is applied there, exactly as it is for the accepting ports, and the <b>arrival</b> round robin the planner
 * then walks covers the input stations and the collecting ports as one list from one cursor
 * ({@link PlanResult#nextArrivalCursor()}), so neither starves the other while a player's request and a production order's
 * ingredients are still planned first.
 * <p>
 * <b>Reservations are derived from the crane's job</b> ({@code docs/warehouse-system.md} §7.4): every report re-tracks
 * the reported job, and every dispatch interval the ledger is rebuilt from the dock's current job
 * ({@link #adopt}), which also adopts the job of a crane that a new or reloaded controller finds. Reservations of
 * requests this controller does not know (any more) are detached, so they never count as backing a request.
 */
final class CraneDispatch {
    /** How long a storage location that gave nothing in a live simulation is skipped for that key, at most. */
    static final long REFUSAL_MEMORY_TICKS = 1200;
    /** Bound of the remembered refusals per direction (insert, extract). */
    static final int MAX_REMEMBERED_REFUSALS = 4096;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long NO_BACKOFF = Long.MIN_VALUE;

    private final WarehouseControllerBlockEntity controller;
    private final ReservationLedger<ItemKey, RackPosition> ledger = new ReservationLedger<>();
    private final ReservationView<ItemKey, RackPosition> ledgerView = ledger.readOnlyView();
    private final JobPlanner<ItemKey, RackPosition> planner = new JobPlanner<>(Function.identity());
    private final RefusalMemory<ItemKey, RackPosition> insertRefusals = new RefusalMemory<>(REFUSAL_MEMORY_TICKS,
            MAX_REMEMBERED_REFUSALS);
    private final RefusalMemory<ItemKey, RackPosition> extractRefusals = new RefusalMemory<>(REFUSAL_MEMORY_TICKS,
            MAX_REMEMBERED_REFUSALS);
    private int arrivalCursor;
    private long nextDispatchTick = NO_BACKOFF;
    private long backoffUntilTick = NO_BACKOFF;
    @Nullable
    private NoJobReason lastReason;
    /**
     * Whether the planning run being built skipped at least one rack because the crane cannot drive to it. Set while
     * the input is built and read when the planner came back empty, so the goggles name the rails rather than the
     * stock (M21, ADR-033).
     */
    private boolean unreachableSkipped;
    /**
     * Rate limits for the storage-interop diagnostics. A one-shot latch was used before, which silenced every
     * <b>other</b> failing inventory of this aisle for the rest of the controller's life.
     */
    private final LogThrottle planningFailures = new LogThrottle();
    private final LogThrottle simulationFailures = new LogThrottle();

    CraneDispatch(WarehouseControllerBlockEntity controller) {
        this.controller = controller;
    }

    ReservationView<ItemKey, RackPosition> reservations() {
        return ledgerView;
    }

    Optional<NoJobReason> lastReason() {
        return Optional.ofNullable(lastReason);
    }

    boolean isBackingOff(long now) {
        return now < backoffUntilTick;
    }

    /** The aisle is gone or replaced: no reservations, no planning state. */
    void reset() {
        unreachableSkipped = false;
        ledger.clear();
        insertRefusals.clear();
        extractRefusals.clear();
        lastReason = null;
        backoffUntilTick = NO_BACKOFF;
        arrivalCursor = 0;
    }

    /** The storage location at {@code rack} was read again or left the aisle: its remembered refusals are void. */
    void forgetRefusals(RackPosition rack) {
        insertRefusals.forget(rack);
        extractRefusals.forget(rack);
    }

    // --- dispatch ------------------------------------------------------------------------------------------------

    void tick(Level level, WarehouseLayout layout, long now) {
        if (now < nextDispatchTick)
            return;
        nextDispatchTick = now + Math.max(1, WareworksConfig.dispatchIntervalTicks());
        Optional<StackerCraneBlockEntity> dock = controller.linkedDockEntity();
        if (dock.isEmpty())
            return;
        adopt(dock.get().currentJob());
        if (!dock.get().canAcceptJob())
            return;
        // The machine stands on rails this warehouse does not contain, so there is no candidate it could drive to and
        // no plan worth building. Reported rather than papered over with the aisle at the dock: pretending it stood
        // there made the planner rank a whole warehouse of candidates for a crane that is somewhere else, hand it a
        // job, and have the crane's own location check abort that job on its very next tick — every dispatch interval,
        // for as long as the rail stayed broken (M21 review fix, ADR-033). The crane puts itself back on the rails
        // within a tick or two ({@code CraneExecution#recoverLostAisle}); until it has, this is the honest answer.
        if (!isOnTheWarehouse(layout, dock.get().craneState().pose())) {
            lastReason = NoJobReason.UNREACHABLE;
            return;
        }
        // The "warehouse full" back-off holds back storing only. Retrieves serve a waiting player, and supplies take
        // items *out* of storage for a production order, so both free space rather than needing it (ADR-024).
        boolean retrieveOnly = isBackingOff(now);
        // Gated once per dispatch, then used by both the pre-check and the planner input: the list costs a block state and
        // up to two block entity lookups per collecting port, and building it twice threw one of them away and let the two
        // answers disagree within one tick (M18 review). During a back-off nothing collects, so it is not built at all.
        List<RackPosition> collectSources = retrieveOnly ? List.of() : controller.collectSources();
        if (retrieveOnly ? controller.openRequestCount() == 0 && controller.supplyNeeds().isEmpty()
                : !hasWork(level, layout, collectSources)) {
            if (!retrieveOnly)
                lastReason = NoJobReason.NO_WORK;
            return;
        }
        PlannerInput.Builder<ItemKey, RackPosition> builder = input(level, layout, dock.get(), collectSources)
                .inputCursor(arrivalCursor);
        // The back-off suppresses the whole arrival stage, collecting included (M18, issue #13): the collect branch walks
        // the same storage candidates the back-off exists to protect, so leaving it in would repeat exactly the candidate
        // scan that armed it.
        if (retrieveOnly)
            builder.inputs(List.of()).collectSources(List.of());
        PlanResult<ItemKey, RackPosition> result;
        try {
            result = planner.plan(builder.build());
        } catch (RuntimeException e) {
            if (planningFailures.tryLog(now))
                LOGGER.error("Warehouse controller at {} could not plan a job", controller.getBlockPos(), e);
            return;
        }
        if (!retrieveOnly)
            arrivalCursor = result.nextArrivalCursor();
        if (result.job().isEmpty()) {
            if (retrieveOnly)
                return; // the back-off's planning result stays
            // A rack the crane cannot drive to was skipped exactly like one in an unloaded chunk, so the planner's own
            // answer would be about stock or filters — true of what was left, and useless to a player whose rails are
            // broken. UNREACHABLE arms no back-off: it is a map lookup, not a candidate scan (M21, ADR-033).
            lastReason = unreachableSkipped ? NoJobReason.UNREACHABLE
                    : result.primaryReason().orElse(NoJobReason.NO_WORK);
            // Both mean "this input's items fit nowhere right now", and re-running the full candidate scan every
            // dispatch interval would cost the most in exactly the warehouse that produces them (ADR-021, §7.4).
            // NoJobReason.AT_MAXIMUM deliberately does not belong here (M15): a stock rule's maximum is answered by
            // one lookup before any candidate work, so there is no expensive scan to protect, and backing off would
            // stop storing for every other input of the aisle because a single item type is capped on purpose.
            // NoJobReason.PORT_FULL does belong here (M17): unlike a maximum it is reached only after a full candidate
            // walk with an estimate and a live simulation per candidate, which is exactly the work this back-off exists
            // to protect, and an overflow port that is full stays full until a funnel drains it.
            // NoJobReason.COLLECT_SOURCE_EMPTY does not belong here either (M18 review), with M15's argument again: a
            // machine that hands nothing out is answered by a map lookup and at most one live extract per item type,
            // before any candidate is ranked — and it is the resting state of every production loop, so backing off would
            // suspend storing from every input station of the aisle for as long as a machine is empty.
            if (result.reasons().contains(NoJobReason.WAREHOUSE_FULL)
                    || result.reasons().contains(NoJobReason.NO_MATCHING_FILTER)
                    || result.reasons().contains(NoJobReason.PORT_FULL))
                backoffUntilTick = now + Math.max(1, WareworksConfig.fullBackoffTicks());
            return;
        }
        lastReason = null;
        TransportJob<ItemKey, RackPosition> job = result.job().get().job();
        track(job);
        if (!dock.get().assignJob(job)) {
            ledger.releaseJob(job.id());
            return;
        }
        // M19 (issue #10): the aisle now has a crane job, which is the most common reason to hold its chunks. Event
        // driven on purpose — the controller never polls for this.
        controller.markChunkKeepDirty();
        spendPortToken(job, job.target(), job.targetKind());
        spendCollectToken(job);
    }

    /**
     * A collect job spends the pulse token of the port it fetches <b>from</b> (M18, issue #13): a rising edge on a
     * collecting port in pulse mode is one trip, the mirror of {@link #spendPortToken} keyed on the source instead of the
     * target. Nothing happens for any other job type, for a continuous port (which holds no token) or for a job the crane
     * refused.
     * <p>
     * It is <b>not</b> spent on a reroute: a collect reroute moves items the crane already holds towards storage or an
     * input buffer, so no second trip is promised and no further edge is consumed.
     */
    private void spendCollectToken(TransportJob<ItemKey, RackPosition> job) {
        if (job.type() == JobType.COLLECT)
            controller.onPortSelected(job.source());
    }

    /**
     * A store job that really goes to an accepting warehouse port spends that port's pulse token (M17): a rising edge on
     * a port in pulse mode is one trip, and the token is spent when the job is handed out, so an edge is never turned
     * into two exports. Nothing happens for a store into storage, for a continuous port (which holds no token) or for a
     * job the crane refused.
     * <p>
     * {@code targetKind} is the kind of the target that was <b>chosen</b> and is passed in rather than re-derived from
     * {@code job}: on the reroute path the job still carries the kind of the target that <b>failed</b> (the crane's state
     * machine applies {@code TransportJob#withTarget} only when it receives the new target), so asking the job there
     * would answer {@code STORAGE} for the ordinary case — a store whose storage location filled up — and leave the port
     * armed although a whole trip was exported into it.
     */
    private void spendPortToken(TransportJob<ItemKey, RackPosition> job, RackPosition target, LocationKind targetKind) {
        if (job.type() == JobType.STORE && targetKind == LocationKind.OUTPUT)
            controller.onPortSelected(target);
    }

    /**
     * Open requests, production ingredients, buffered input items or a collecting port with something in its machine
     * exist (cheap pre-check before a planner input).
     * <p>
     * The collect half (M18, issue #13) is the <b>same list</b> the planner input is built from — gated once in
     * {@link #tick} and passed in, so the world lookups it costs are paid once per dispatch — and it is asked last, after
     * the three cheaper questions, so a warehouse with work to do never looks at it at all.
     *
     * @param collectSources the aisle's gated collecting ports, empty during a back-off
     */
    private boolean hasWork(Level level, WarehouseLayout layout, List<RackPosition> collectSources) {
        if (controller.openRequestCount() > 0 || !controller.supplyNeeds().isEmpty())
            return true;
        for (LocationRecord input : controller.inputStations()) {
            BlockPos pos = layout.rackPos(input.position());
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity station
                    && station.hasBufferedItems())
                return true;
        }
        return !collectSources.isEmpty();
    }

    private PlannerInput.Builder<ItemKey, RackPosition> input(Level level, WarehouseLayout layout,
            StackerCraneBlockEntity dock, List<RackPosition> collectSources) {
        CranePose pose = dock.craneState().pose();
        long now = level.getGameTime();
        int craneBranch = pose.branch();
        double craneX = pose.x();
        // The corners are derived once for the whole pass: every candidate asks two route questions, and a route
        // question starts by walking the branch pairs for the blocks they share (M21 review fix).
        RouteTable routes = layout.routes();
        unreachableSkipped = false;
        // How much of an item a storage location is estimated to take, from the index snapshots. Built once per run,
        // because the planner asks it per storage candidate per key.
        JobPlanner.InsertEstimate<ItemKey, RackPosition> snapshots =
                JobPlanner.InsertEstimate.fromSnapshots(controller.stockIndex(), ItemKey::getMaxStackSize);
        return PlannerInput.builder(controller.stockIndex(), ledgerView)
                .crane(craneBranch, craneX, pose.y())
                .speeds(dock.currentSpeeds())
                // What driving really costs on these rails: the blocks of the route plus one turn penalty per corner
                // (M21, ADR-033). On a warehouse of one aisle every route is one leg with no turn, so this is literally
                // the formula the planner always used.
                .travel(travelCost(routes, dock.currentSpeeds()))
                .transferTicks(Math.max(0, WareworksConfig.transferTicks()))
                .carryLimit(InventoryGrabber::carryLimitFor)
                .itemType(ItemKey::getItem)
                .requests(openRequests(layout))
                .supplies(controller.supplyNeeds())
                .storageLocations(storageLocations())
                .inputs(positions(controller.inputStations()))
                .outputs(rerouteOutputs())
                .inputBuffers(rack -> inputBuffer(level, layout, rack))
                // A rack on an aisle the crane cannot drive to is skipped exactly like one in an unloaded chunk, so no
                // job is ever planned towards a place the machine cannot physically reach (ADR-033).
                .available(rack -> canDriveTo(routes, craneBranch, craneX, rack) && isLoaded(level, layout, rack))
                // A fluid bay is the one storage location whose room is not an item question at all: it holds no items,
                // so its snapshot has zero slots and the snapshot estimate answers 0 — which would drop it before any
                // live call, exactly as it rightly drops a warehouse interface whose chest was taken away. The two
                // report the same empty snapshot for opposite reasons, so the bay is told apart by what it IS (M30
                // step 9, D6) and answers "unknown, ask the live inventory"; the live call then measures the container
                // and the room together, all or nothing (TransferContexts' FluidBayContext#simulateInsert).
                .insertEstimate((rack, key) -> controller.takesFluidContainers(rack)
                        ? JobPlanner.UNKNOWN_CAPACITY : snapshots.estimateInsertable(rack, key))
                // And the other half of what a fluid bay is: it takes a carry WHOLE or not at all, because the
                // containers are exchanged and a handling head holds one item key (TransferContext#exchange). The
                // store plan sizes a job by what the bay answers and is unaffected; a REROUTE has a fixed amount in
                // the head already, so the planner must not offer a bay part of it — the crane would arrive, be
                // refused and be sent to the next bay, and with two of them that never ends (M30 review fix).
                .allOrNothing(controller::takesFluidContainers)
                // Store filters decide before the estimate and before any live call, so a location that may not take the
                // item costs neither a live simulation nor a remembered refusal (ADR-021).
                .storeFilter(controller::storeFilterMatch)
                // The player's storage priority, from the same cache: it orders only the locations the filter,
                // consolidation and item-type grouping left equal, and it is read nowhere but when storing (ADR-028).
                .storePriority(controller::storePriorityAt)
                // The aisle's accepting ports, already gated: direction, redstone and the pulse token are applied here,
                // so the planner only ranks them (M17, ADR-029). An aisle of plain outputs answers an empty list without
                // touching the world, which is what makes it the pre-M17 planner input literally.
                .ports(controller.acceptingPorts())
                .portRank(controller::portRankAt)
                // The aisle's collecting ports, gated the same way (M18, issue #13): direction, redstone, the pulse
                // token, both chunks, the port's own filter and "never an inventory this aisle already indexes" are applied
                // by the controller, and the snapshots come from its throttled collect cache — so the planner never reads
                // the world and a warehouse without a collecting port hands it an empty list without touching one.
                .collectSources(collectSources)
                .collectBuffers(controller::collectBuffer)
                // A stock rule's maximum decides even earlier, per item type instead of per location: one lookup that
                // costs nothing while no rule governs the key (M15, issue #3).
                .storeHeadroom(controller::storeHeadroom)
                .insertRefused((rack, key) -> insertRefusals.isRefused(rack, key, now))
                .extractRefused((rack, key) -> extractRefusals.isRefused(rack, key, now))
                .liveExtract((rack, key, max) -> simulate(level, layout, rack, true, key, max, now))
                .liveInsert((rack, key, amount) -> simulate(level, layout, rack, false, key, amount, now));
    }

    /**
     * Whether the machine really stands on an aisle of this warehouse. A pose naming an aisle the warehouse no longer
     * has is not planned for at all ({@link NoJobReason#UNREACHABLE}); the crane puts itself back onto the aisle at
     * the dock on one of its next ticks (M21 review fix, ADR-033).
     */
    private static boolean isOnTheWarehouse(WarehouseLayout layout, CranePose pose) {
        return pose.branch() >= 0 && pose.branch() < layout.branchCount();
    }

    /**
     * What the planner is told a trip costs: the route's blocks plus {@code crane.turnPenaltyBlocks} per quarter turn,
     * in the same tick formula a straight aisle always used ({@link TravelTimeModel#travelAlongTicks}).
     * <p>
     * Two racks equally far away by number are therefore <b>not</b> equally far away when one of them is round a
     * corner, which is physically honest and is what keeps the crane from criss-crossing a bent warehouse. A pair with
     * no route at all answers {@link TravelTimeModel#UNAVAILABLE}; such a rack is already dropped by
     * {@link PlannerInput#available()}, so that answer is the second of two independent refusals rather than the only
     * one.
     * <p>
     * A pair on <b>one</b> aisle takes the old formula directly, without building a route at all: that is the whole of
     * a warehouse that does not bend, and the planner asks this ten times per candidate.
     */
    private static PlannerInput.TravelCost travelCost(RouteTable routes, CraneSpeeds speeds) {
        double penalty = WareworksConfig.turnPenaltyBlocks();
        return (fromBranch, fromX, fromY, toBranch, toX, toY) -> {
            if (fromBranch == toBranch)
                return TravelTimeModel.travelTicks(speeds, fromX, fromY, toX, toY);
            OptionalDouble blocks = routes.routeBlocks(fromBranch, fromX, toBranch, toX, penalty);
            if (blocks.isEmpty())
                return TravelTimeModel.UNAVAILABLE;
            return TravelTimeModel.travelAlongTicks(speeds, blocks.getAsDouble(), fromY, toY);
        };
    }

    /**
     * Whether the crane standing at {@code (craneBranch, craneX)} can drive to a rack at all, remembering when it
     * could not: a warehouse whose rails were cut has racks it still knows, addresses and lists, and a player who
     * looks at the controller has to be told <b>that</b> rather than "not in stock"
     * ({@link NoJobReason#UNREACHABLE}).
     * <p>
     * Asked from the crane's own <b>point</b> and not from its branch index, so that the controller and the machine
     * mean the same thing by "it can get there" ({@link RouteTable#canDrive}, M21 review fix). A branch that a broken
     * rail made shorter than the crane's position still exists and is still joined to its neighbours, so the
     * branch-only question would plan a trip the machine then stands still on.
     */
    private boolean canDriveTo(RouteTable routes, int craneBranch, double craneX, RackPosition rack) {
        if (routes.canDrive(craneBranch, craneX, rack.branch(), rack.x()))
            return true;
        unreachableSkipped = true;
        return false;
    }

    /** Open requests whose destination is an output station of this aisle, oldest first. */
    private List<PlannerInput.OpenRequest<ItemKey, RackPosition>> openRequests(WarehouseLayout layout) {
        List<PlannerInput.OpenRequest<ItemKey, RackPosition>> result = new ArrayList<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.openRequests()) {
            Optional<RackPosition> output = controller.rackOf(request.destination());
            if (output.isEmpty() || controller.kindAt(output.get()).orElse(null) != LocationKind.OUTPUT
                    || request.remaining() < 1)
                continue;
            result.add(new PlannerInput.OpenRequest<>(request.id(), request.key(), request.remaining(), output.get()));
        }
        return result;
    }

    /**
     * Output stations the planner may use as the last resort for retrieve leftovers ({@code PlannerInput#outputs}), i.e.
     * every output that is <b>not</b> an accepting warehouse port (M17, issue #12). An accepting port must never receive
     * items somebody requested: what leaves through a port has to be surplus the warehouse chose not to keep. A port that
     * requests, and a warehouse terminal (ADR-018), are ranked as before.
     */
    private List<RackPosition> rerouteOutputs() {
        List<RackPosition> result = new ArrayList<>();
        for (LocationRecord record : controller.outputStations()) {
            // A collecting port answers rank 0 like a requesting one (so that nothing can ever export through it), so it
            // is excluded by direction here: it takes no deliveries at all (M18, issue #13).
            if (controller.portRankAt(record.position()) == PortSettings.REQUEST_RANK
                    && !controller.isCollectingPort(record.position()))
                result.add(record.position());
        }
        return result;
    }

    /** Storage locations in index order, one per inventory (aliases of a shared inventory are skipped). */
    private List<RackPosition> storageLocations() {
        List<RackPosition> result = new ArrayList<>();
        for (LocationRecord record : controller.storageLocations()) {
            if (!controller.isStorageAlias(record.position()))
                result.add(record.position());
        }
        return result;
    }

    private static List<RackPosition> positions(List<LocationRecord> records) {
        List<RackPosition> result = new ArrayList<>(records.size());
        for (LocationRecord record : records)
            result.add(record.position());
        return result;
    }

    private static InventorySnapshot<ItemKey> inputBuffer(Level level, WarehouseLayout layout, RackPosition rack) {
        BlockPos pos = layout.rackPos(rack);
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity station
                && !station.isRemoved())
            return station.bufferedItems();
        return InventorySnapshot.empty();
    }

    /** Whether a location (and for storage its inventory) is in a loaded chunk; the live calls check the rest. */
    private boolean isLoaded(Level level, WarehouseLayout layout, RackPosition rack) {
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        return controller.kindAt(rack).orElse(null) != LocationKind.STORAGE
                || level.isLoaded(pos.relative(layout.sideDirection(rack)));
    }

    /**
     * One live simulation. A storage location that gives nothing (gone, no inventory, full, restricted, or failing) is
     * remembered as refusing {@code key} in that direction; an unloaded one is not.
     */
    private int simulate(Level level, WarehouseLayout layout, RackPosition rack, boolean extract, ItemKey key, int amount,
            long now) {
        Optional<LocationKind> kind = controller.kindAt(rack);
        if (kind.isEmpty() || amount < 1)
            return 0;
        TransferContexts.Resolution resolution = TransferContexts.resolve(level, layout.branch(rack.branch()), rack,
                kind.get());
        int result = 0;
        if (resolution.isAvailable()) {
            try {
                result = extract ? resolution.context().orElseThrow().simulateExtract(key, amount)
                        : resolution.context().orElseThrow().simulateInsert(key, amount);
            } catch (RuntimeException e) {
                if (simulationFailures.tryLog(now))
                    LOGGER.warn("Warehouse controller at {} could not simulate a transfer at {}",
                            controller.getBlockPos(), rack, e);
            }
        }
        if (result <= 0 && kind.get() == LocationKind.STORAGE
                && resolution.status() != TransferContexts.Status.UNLOADED)
            (extract ? extractRefusals : insertRefusals).record(rack, key, now);
        return result;
    }

    // --- reservations and reports --------------------------------------------------------------------------------

    /** Rebuilds the reservations from the crane's current job (adoption and drift correction). */
    void adopt(Optional<TransportJob<ItemKey, RackPosition>> craneJob) {
        if (craneJob.isEmpty()) {
            if (!ledger.isEmpty())
                ledger.clear();
            return;
        }
        ledger.restoreFrom(List.of(craneJob.get()));
        detachUnknownRequest(craneJob.get());
    }

    /** Re-tracks a reported job (its reservations follow its stage). */
    void track(TransportJob<ItemKey, RackPosition> job) {
        ledger.track(job);
        detachUnknownRequest(job);
    }

    void release(UUID jobId) {
        ledger.releaseJob(jobId);
    }

    /**
     * A job whose owner is gone no longer backs anything: its reservations stop counting towards that owner. The owner
     * is a retrieval request for a {@code RETRIEVE} job and a production order's ingredient line for a {@code SUPPLY}
     * job, so both are asked for here ({@code WarehouseControllerBlockEntity#hasOpenJobOwner}).
     */
    private void detachUnknownRequest(TransportJob<ItemKey, RackPosition> job) {
        job.requestId().filter(id -> !controller.hasOpenJobOwner(id)).ifPresent(ledger::detachRequest);
    }

    /**
     * Requests were cancelled: their reservations no longer back a request, and a crane job serving one of them is
     * cancelled (aborted before the pick, rerouted after it; its reports release the reservations).
     */
    void onRequestsCancelled(Collection<RetrievalRequest<ItemKey, BlockPos>> cancelled) {
        if (cancelled.isEmpty())
            return;
        Set<UUID> ids = new HashSet<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : cancelled)
            ids.add(request.id());
        onOwnersCancelled(ids);
    }

    /**
     * The owners named by {@code ids} are gone: cancelled retrieval requests, or the ingredient lines of a production
     * order that was cancelled, timed out or lost its station. Their reservations stop backing anything, and a crane
     * job working for one of them is cancelled — aborted before the pick, its held items rerouted back into storage
     * after it.
     * <p>
     * The production path needs this exactly as much as the request path does: without it the crane finished the trip
     * of an order that had already ended and dropped the ingredients into the machine's buffer, where the player's
     * funnel fed them to a machine for an order nobody was waiting on any more
     * ({@code docs/warehouse-system.md} §3.5.4).
     */
    void onOwnersCancelled(Set<UUID> ids) {
        if (ids.isEmpty())
            return;
        for (UUID id : ids)
            ledger.detachRequest(id);
        controller.linkedDockEntity().ifPresent(dock -> dock.currentJob()
                .filter(job -> job.requestId().filter(ids::contains).isPresent())
                .ifPresent(job -> dock.cancelJob(job.id())));
    }

    /**
     * A new target for the {@code amount} items {@code job} holds. The job's own reservation (capacity or transit at
     * its current target) is left out while planning: it stands for these very items, and would otherwise keep a former
     * target that has room for exactly them from ever qualifying again. It is tracked again afterwards (a found target
     * is tracked by the reroute report).
     *
     * @param failedTarget the target to exclude, or {@code null} for a hold retry
     */
    Optional<RerouteTarget<RackPosition>> planReroute(Level level, WarehouseLayout layout, StackerCraneBlockEntity dock,
            TransportJob<ItemKey, RackPosition> job, @Nullable RackPosition failedTarget, int amount) {
        if (amount < 1)
            return Optional.empty();
        // A machine on rails this warehouse does not contain can drive to nothing, so a reroute would only name a
        // target it cannot reach and the whole cycle would start again on its next location check. Answering "none"
        // puts it in HOLDING with its items, where the crane's own recovery picks it up (M21 review fix, ADR-033).
        if (!isOnTheWarehouse(layout, dock.craneState().pose()))
            return Optional.empty();
        ledger.releaseJob(job.id());
        try {
            // No collect sources: a reroute looks for a new home for items the crane already holds and never plans a
            // collect job, so gating the ports here would spend the world lookups of that pass on nothing (M18 review).
            Optional<RerouteTarget<RackPosition>> target = planner.planReroute(
                    input(level, layout, dock, List.of()).build(), job.key(), amount, job.type(), failedTarget);
            // The last resort of a store reroute may be an accepting port, and that spends its pulse token too, so a
            // single edge can never export one trip's leftovers and then a whole trip of its own (M17). The kind comes
            // from the target that was found, never from the job, which still names the target that failed.
            target.ifPresent(found -> spendPortToken(job, found.location(), found.kind()));
            return target;
        } catch (RuntimeException e) {
            if (planningFailures.tryLog(level.getGameTime()))
                LOGGER.error("Warehouse controller at {} could not plan a reroute", controller.getBlockPos(), e);
            return Optional.empty();
        } finally {
            track(job);
        }
    }
}
