package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
import dev.wareworks.core.job.JobPlanner;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.PlanResult;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.RefusalMemory;
import dev.wareworks.core.job.RerouteTarget;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
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
    private int inputCursor;
    private long nextDispatchTick = NO_BACKOFF;
    private long backoffUntilTick = NO_BACKOFF;
    @Nullable
    private NoJobReason lastReason;
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
        ledger.clear();
        insertRefusals.clear();
        extractRefusals.clear();
        lastReason = null;
        backoffUntilTick = NO_BACKOFF;
        inputCursor = 0;
    }

    /** The storage location at {@code rack} was read again or left the aisle: its remembered refusals are void. */
    void forgetRefusals(RackPosition rack) {
        insertRefusals.forget(rack);
        extractRefusals.forget(rack);
    }

    // --- dispatch ------------------------------------------------------------------------------------------------

    void tick(Level level, AisleLayout layout, long now) {
        if (now < nextDispatchTick)
            return;
        nextDispatchTick = now + Math.max(1, WareworksConfig.dispatchIntervalTicks());
        Optional<StackerCraneBlockEntity> dock = controller.linkedDockEntity();
        if (dock.isEmpty())
            return;
        adopt(dock.get().currentJob());
        if (!dock.get().canAcceptJob())
            return;
        // The "warehouse full" back-off only holds back storing: a retrieve serves a waiting player and frees space.
        // The "warehouse full" back-off holds back storing only. Retrieves serve a waiting player, and supplies take
        // items *out* of storage for a production order, so both free space rather than needing it (ADR-024).
        boolean retrieveOnly = isBackingOff(now);
        if (retrieveOnly ? controller.openRequestCount() == 0 && controller.supplyNeeds().isEmpty()
                : !hasWork(level, layout)) {
            if (!retrieveOnly)
                lastReason = NoJobReason.NO_WORK;
            return;
        }
        PlannerInput.Builder<ItemKey, RackPosition> builder = input(level, layout, dock.get()).inputCursor(inputCursor);
        if (retrieveOnly)
            builder.inputs(List.of());
        PlanResult<ItemKey, RackPosition> result;
        try {
            result = planner.plan(builder.build());
        } catch (RuntimeException e) {
            if (planningFailures.tryLog(now))
                LOGGER.error("Warehouse controller at {} could not plan a job", controller.getBlockPos(), e);
            return;
        }
        if (!retrieveOnly)
            inputCursor = result.nextInputCursor();
        if (result.job().isEmpty()) {
            if (retrieveOnly)
                return; // the back-off's planning result stays
            lastReason = result.primaryReason().orElse(NoJobReason.NO_WORK);
            // Both mean "this input's items fit nowhere right now", and re-running the full candidate scan every
            // dispatch interval would cost the most in exactly the warehouse that produces them (ADR-021, §7.4).
            if (result.reasons().contains(NoJobReason.WAREHOUSE_FULL)
                    || result.reasons().contains(NoJobReason.NO_MATCHING_FILTER))
                backoffUntilTick = now + Math.max(1, WareworksConfig.fullBackoffTicks());
            return;
        }
        lastReason = null;
        TransportJob<ItemKey, RackPosition> job = result.job().get().job();
        track(job);
        if (!dock.get().assignJob(job))
            ledger.releaseJob(job.id());
    }

    /** Open requests, production ingredients or buffered input items exist (cheap pre-check before a planner input). */
    private boolean hasWork(Level level, AisleLayout layout) {
        if (controller.openRequestCount() > 0 || !controller.supplyNeeds().isEmpty())
            return true;
        for (LocationRecord input : controller.inputStations()) {
            BlockPos pos = layout.rackPos(input.position());
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity station
                    && station.hasBufferedItems())
                return true;
        }
        return false;
    }

    private PlannerInput.Builder<ItemKey, RackPosition> input(Level level, AisleLayout layout,
            StackerCraneBlockEntity dock) {
        CranePose pose = dock.craneState().pose();
        long now = level.getGameTime();
        return PlannerInput.builder(controller.stockIndex(), ledgerView)
                .crane(pose.x(), pose.y())
                .speeds(dock.currentSpeeds())
                .transferTicks(Math.max(0, WareworksConfig.transferTicks()))
                .carryLimit(InventoryGrabber::carryLimitFor)
                .itemType(ItemKey::getItem)
                .requests(openRequests(layout))
                .supplies(controller.supplyNeeds())
                .storageLocations(storageLocations())
                .inputs(positions(controller.inputStations()))
                .outputs(positions(controller.outputStations()))
                .inputBuffers(rack -> inputBuffer(level, layout, rack))
                .available(rack -> isLoaded(level, layout, rack))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(controller.stockIndex(), ItemKey::getMaxStackSize))
                // Store filters decide before the estimate and before any live call, so a location that may not take the
                // item costs neither a live simulation nor a remembered refusal (ADR-021).
                .storeFilter(controller::storeFilterMatch)
                .insertRefused((rack, key) -> insertRefusals.isRefused(rack, key, now))
                .extractRefused((rack, key) -> extractRefusals.isRefused(rack, key, now))
                .liveExtract((rack, key, max) -> simulate(level, layout, rack, true, key, max, now))
                .liveInsert((rack, key, amount) -> simulate(level, layout, rack, false, key, amount, now));
    }

    /** Open requests whose destination is an output station of this aisle, oldest first. */
    private List<PlannerInput.OpenRequest<ItemKey, RackPosition>> openRequests(AisleLayout layout) {
        List<PlannerInput.OpenRequest<ItemKey, RackPosition>> result = new ArrayList<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.openRequests()) {
            Optional<RackPosition> output = layout.worldToLocal(request.destination());
            if (output.isEmpty() || controller.kindAt(output.get()).orElse(null) != LocationKind.OUTPUT
                    || request.remaining() < 1)
                continue;
            result.add(new PlannerInput.OpenRequest<>(request.id(), request.key(), request.remaining(), output.get()));
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

    private static InventorySnapshot<ItemKey> inputBuffer(Level level, AisleLayout layout, RackPosition rack) {
        BlockPos pos = layout.rackPos(rack);
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity station
                && !station.isRemoved())
            return station.bufferedItems();
        return InventorySnapshot.empty();
    }

    /** Whether a location (and for storage its inventory) is in a loaded chunk; the live calls check the rest. */
    private boolean isLoaded(Level level, AisleLayout layout, RackPosition rack) {
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        return controller.kindAt(rack).orElse(null) != LocationKind.STORAGE
                || level.isLoaded(pos.relative(layout.sideDirection(rack.side())));
    }

    /**
     * One live simulation. A storage location that gives nothing (gone, no inventory, full, restricted, or failing) is
     * remembered as refusing {@code key} in that direction; an unloaded one is not.
     */
    private int simulate(Level level, AisleLayout layout, RackPosition rack, boolean extract, ItemKey key, int amount,
            long now) {
        Optional<LocationKind> kind = controller.kindAt(rack);
        if (kind.isEmpty() || amount < 1)
            return 0;
        TransferContexts.Resolution resolution = TransferContexts.resolve(level, layout, rack, kind.get());
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
    Optional<RerouteTarget<RackPosition>> planReroute(Level level, AisleLayout layout, StackerCraneBlockEntity dock,
            TransportJob<ItemKey, RackPosition> job, @Nullable RackPosition failedTarget, int amount) {
        if (amount < 1)
            return Optional.empty();
        ledger.releaseJob(job.id());
        try {
            return planner.planReroute(input(level, layout, dock).build(), job.key(), amount, job.type(), failedTarget);
        } catch (RuntimeException e) {
            if (planningFailures.tryLog(level.getGameTime()))
                LOGGER.error("Warehouse controller at {} could not plan a reroute", controller.getBlockPos(), e);
            return Optional.empty();
        } finally {
            track(job);
        }
    }
}
