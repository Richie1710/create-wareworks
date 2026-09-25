package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.AbortReason;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.SharedInventories;
import dev.wareworks.core.inventory.SnapshotQueue;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.FilterMatch;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RerouteTarget;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProduciblePlanner;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionOrders;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.warehouse.AisleMembership;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.MembershipChanges;
import dev.wareworks.core.warehouse.RackProbe;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.LogThrottle;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the warehouse controller: the logical warehouse state of one aisle ({@code docs/warehouse-system.md}
 * §3.3-§8). It plans and counts, but never moves items.
 * <p>
 * <b>Layout.</b> The controller links to the stacker crane dock directly in front of it ({@code pos + FACING}) if that
 * dock faces the same direction, and builds an {@link AisleLayout} from the dock's geometry and its own aisle letter
 * ("Aisle" value box, A-Z). The layout is registered in the {@link WarehouseRegistry}. Re-linking runs on the next tick
 * after a hint (block update in front, dock geometry change, dock load or removal, own rotation) and at the latest every
 * {@code geometryRefreshTicks}; it costs one block entity lookup. While the dock position is not loaded, the known
 * layout is kept. A changed dock position or aisle direction rebuilds records, counts and requests from scratch.
 * <p>
 * <b>Membership.</b> An {@link AisleMembership} of rack positions: members notify through the registry, which marks
 * only their position dirty; geometry changes mark all positions dirty. Dirty positions are probed on the next tick,
 * only in loaded chunks; records in unloaded chunks are kept.
 * <p>
 * <b>Stock.</b> A {@link StockIndex} over the storage locations, updated incrementally. Locations wait in a
 * {@link SnapshotQueue} and at most {@code maxSnapshotsPerTick} of them are read per tick: urgently when they join or
 * when their interface reports a content change ({@link WarehouseRegistry#contentChanged}), in the background when
 * their counts were restored from a save. On top, one storage location per {@code snapshotIntervalTicks} is re-read
 * round robin (inventories that change silently), and {@link #refreshLocation} re-reads one on demand (after every crane
 * transfer). Locations that read the same inventory (a double chest or a vault behind several interfaces, identified with
 * Create's {@link InventoryIdentifier}) are counted once, by one canonical location ({@link SharedInventories}).
 * <p>
 * <b>Requests.</b> A {@link RequestQueue} of retrieval requests (item key, amount, output station position), filled by
 * output stations through {@link #request}: the amount is clamped to the stock that is not promised yet
 * ({@link #availableStock}), the queue is capped at {@code maxOpenRequests} and at {@code maxOpenRequestsPerOutput} per
 * output. Requests whose output is gone are cancelled after every membership reconcile and every periodic re-link check
 * (a crane job serving one is cancelled too); losing the dock (or moving it) clears them.
 * <p>
 * <b>Dispatch (M3).</b> {@link CraneDispatch} plans a job for the linked crane every {@code dispatchIntervalTicks} while
 * the crane is idle, empty and powered, reserves it and assigns it; the crane reports picks, deliveries, reroutes,
 * completion and aborts back ({@code onCrane*}), which update the reservations, the request queue (only deliveries into
 * the request's output count) and the stock index of touched storage locations. Reservations are derived from the
 * crane's job, so a new or reloaded controller adopts the job of the crane in front of it.
 * <p>
 * <b>Per tick (server)</b>: at most a re-link check, the dirty probes, the bounded snapshot queue, one round-robin
 * snapshot and a dispatch attempt; nothing else.
 * <p>
 * <b>Persistence.</b> Layout, records, per-location stock counts and requests are saved ({@link ControllerPersistence});
 * the index is rebuilt from the counts on load, the membership is verified by a full scan on the first tick, the
 * restored counts are verified by background snapshots, and reservations are rebuilt from the crane's job. Goggle data is
 * a bounded {@link ControllerGoggleSummary}, refreshed and synced only while a player observes the controller
 * ({@link GoggleObservers}).
 */
public class WarehouseControllerBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation, GoggleObservers.Observable {
    private static final String SUMMARY_TAG = "GoggleSummary";
    /** Minimum interval between two re-link checks, whatever the configured geometry refresh cadence. */
    private static final int MIN_RELINK_INTERVAL_TICKS = 1;
    /**
     * How long a finished production order stays visible before it is forgotten. A player has to be able to come back
     * and read that their order timed out; one that vanished the moment it failed would look like one that was never
     * placed ({@code docs/warehouse-system.md} §3.5).
     */
    private static final long FINISHED_ORDER_RETENTION_TICKS = 600;

    /**
     * "Aisle" value box. Assigned in {@link #addBehaviours}, which {@code SmartBlockEntity} calls from its constructor, so
     * this field must not have an initializer (it would reset the behaviour to {@code null}).
     */
    protected AisleLetterBehaviour aisleLetter;

    private final AisleMembership membership = new AisleMembership();
    private final StockIndex<ItemKey, RackPosition> stock = new StockIndex<>(RackPosition.ORDER);
    /** Storage locations waiting for a snapshot; not saved (rebuilt from the records on load). */
    private final SnapshotQueue<RackPosition> pendingSnapshots = new SnapshotQueue<>();
    /** Storage locations reading the same inventory; derived from snapshots, not saved. */
    private final SharedInventories<RackPosition, Object> sharedInventories = new SharedInventories<>();
    /** Open retrieval requests; destinations are world positions of output stations. Saved. */
    private final RequestQueue<ItemKey, BlockPos> requests = new RequestQueue<>(configuredMaxOpenRequests());
    /** Store filters of the storage locations, cached for planning; read from the interfaces, not saved. */
    private final AisleFilters filters = new AisleFilters();
    /** Production orders of this aisle ({@code docs/warehouse-system.md} §3.5, ADR-024). Saved. */
    private final ProductionOrders<ItemKey, RackPosition> productionOrders =
            new ProductionOrders<>(configuredMaxProductionOrders());
    /** Job planning and reservations; derived from the crane's job, not saved. */
    private final CraneDispatch dispatch = new CraneDispatch(this);
    /** The linked aisle including the letter; {@code null} without dock. Saved. */
    @Nullable
    private AisleLayout layout;
    private ControllerStatus status = ControllerStatus.NO_DOCK;

    // --- server only, not saved ---
    @Nullable
    private BlockPos linkedDock;
    private boolean relinkRequested = true;
    /** A dock stands in front but faces another direction ({@link ControllerStatus#DOCK_MISALIGNED}). */
    private boolean dockMisaligned;
    private long nextRelinkTick;
    private long nextSnapshotTick;
    private long nextProductionTick;
    /** Restored orders have no deadline yet: the first tick that knows the game time gives them a fresh one. */
    private boolean productionDeadlinesPending;
    /** Rate limit for the "could not read storage location" line: a second, different inventory stays reportable. */
    private final LogThrottle snapshotFailures = new LogThrottle();

    // --- goggles ---
    private ControllerGoggleSummary summary = ControllerGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public WarehouseControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Every face except the one touching the dock and the bottom shows the value box.
        aisleLetter = new AisleLetterBehaviour(WareworksLang.translateDirect(WareworksLang.CONTROLLER_AISLE_LETTER), this,
                new CenteredSideValueBoxTransform((state, side) -> side != Direction.DOWN
                        && side != state.getOptionalValue(WarehouseControllerBlock.FACING).orElse(null)));
        // Default by direct field write: setValue would run the callback while level == null.
        aisleLetter.value = AisleLetterBehaviour.FIRST_INDEX;
        aisleLetter.withCallback(this::onAisleLetterChanged);
        behaviours.add(aisleLetter);
    }

    // --- API -----------------------------------------------------------------------------------------------------

    /** Direction from the controller to its dock. */
    public Direction facing() {
        return getBlockState().getOptionalValue(WarehouseControllerBlock.FACING).orElse(Direction.NORTH);
    }

    /** Where the dock of this controller must stand. */
    public BlockPos dockPos() {
        return worldPosition.relative(facing());
    }

    /** The aisle letter of the value box. */
    public char aisleLetter() {
        return aisleLetter == null ? StorageAddress.FIRST_AISLE : aisleLetter.letter();
    }

    public ControllerStatus status() {
        return status;
    }

    /** The linked aisle with its letter; empty without dock (and on clients, which receive only the goggle summary). */
    public Optional<AisleLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /** Server: whether this controller has an aisle and is linked to the dock at {@code dock}. */
    public boolean isLinkedTo(BlockPos dock) {
        return layout != null && dock.equals(linkedDock);
    }

    /** Server: the linked dock, if its chunk is loaded and it still stands there with the controller's facing. */
    public Optional<StackerCraneBlockEntity> linkedDockEntity() {
        BlockPos pos = linkedDock;
        if (pos == null || layout == null || level == null || level.isClientSide || !level.isLoaded(pos))
            return Optional.empty();
        return level.getBlockEntity(pos) instanceof StackerCraneBlockEntity dock && !dock.isRemoved()
                && dock.facing() == facing() ? Optional.of(dock) : Optional.empty();
    }

    /** All members (storage locations and stations) in {@link RackPosition#ORDER}. */
    public List<LocationRecord> locations() {
        return membership.records();
    }

    public List<LocationRecord> storageLocations() {
        return membership.records(LocationKind.STORAGE);
    }

    /** How many storage locations this aisle has, without building the record list ({@link #storageLocations()}). */
    public int storageLocationCount() {
        return membership.storageCount();
    }

    /**
     * How many storage locations of this aisle count an inventory of their own: {@link #storageLocationCount()} minus
     * the shared-inventory aliases ({@code docs/warehouse-system.md} §3.1.1).
     * <p>
     * This is the number to put opposite {@link StockView#occupiedLocations()}, which can never count an alias: an
     * alias is indexed with empty counts on purpose, so a ratio against {@link #storageLocationCount()} could never
     * reach full on an aisle with a double chest or an item vault behind several interfaces (M14 review fix). Two
     * field reads, like the plain count.
     */
    public int countedStorageLocationCount() {
        return membership.storageCount() - sharedInventories.aliasCount();
    }

    public List<LocationRecord> inputStations() {
        return membership.records(LocationKind.INPUT);
    }

    public List<LocationRecord> outputStations() {
        return membership.records(LocationKind.OUTPUT);
    }

    /** The kind of the member recorded at {@code rack}. */
    Optional<LocationKind> kindAt(RackPosition rack) {
        return membership.kindAt(rack);
    }

    /** Whether {@code rack} reads an inventory another storage location counts (planning skips it). */
    boolean isStorageAlias(RackPosition rack) {
        return sharedInventories.isAlias(rack);
    }

    /**
     * What the store filter of the storage location at {@code rack} says about {@code key}
     * ({@code docs/warehouse-system.md} §3.1, ADR-021): the planner's {@code storeFilter}. One map lookup, plus the
     * filter's own test for a filtered location.
     * <p>
     * A location whose filter this controller has not read yet (restored from a save, or freshly joined and still
     * queued for its snapshot) is resolved once through {@link #readStoreFilterAt} instead of being treated as
     * unfiltered — see {@link AisleFilters} for why that matters after every world load.
     */
    FilterMatch storeFilterMatch(RackPosition rack, ItemKey key) {
        return level == null ? FilterMatch.UNFILTERED : filters.match(level, rack, key, this::readStoreFilterAt);
    }

    /**
     * Reads the store filter of {@code rack} straight from its interface, for the one lookup {@link AisleFilters} does
     * per location that was never read into the cache. Empty while the position is not loaded or holds no storage
     * member.
     */
    private Optional<ItemStack> readStoreFilterAt(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return Optional.empty();
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageMember member) || blockEntity.isRemoved())
            return Optional.empty();
        return Optional.of(member.storeFilter());
    }

    /**
     * Storage locations of this aisle that carry a store filter <b>that applies</b> (goggle summary).
     * <p>
     * Shared-inventory aliases are excluded (M8 review fix): the planner is only ever given the location that counts an
     * inventory ({@link #sharedInventoryOf}), so a filter on the other half of a double chest can do nothing and must
     * not be reported as an active partition.
     */
    public int filteredLocationCount() {
        return filters.filteredCount(rack -> !sharedInventories.isAlias(rack));
    }

    /** Whether the storage location at {@code rack} carries a store filter the planner actually consults. */
    public boolean isStorageFiltered(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return !sharedInventories.isAlias(rack) && filters.isFiltered(rack);
    }

    /**
     * Whether the storage location at {@code rack} carries a store filter that has <b>no effect</b>, because another
     * location counts the inventory it reads (double chest, item vault) and the planner only asks that one
     * ({@code docs/warehouse-system.md} §3.1.1). Its interface shows this as a goggle hint, so a player can see which
     * of two interfaces on one inventory is the effective one.
     */
    public boolean isStorageFilterShadowed(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return sharedInventories.isAlias(rack) && filters.isFiltered(rack);
    }

    /** Members at rack positions with the wrong facing. */
    public int misalignedCount() {
        return membership.misalignedCount();
    }

    /** Whether member changes wait for the next tick. */
    public boolean isMembershipDirty() {
        return membership.isDirty();
    }

    /**
     * Read-only view of the stock index (item key → storage location → count). A location that reads the same inventory
     * as another one has no counts of its own; see {@link #sharedInventoryOf}.
     */
    public StockView<ItemKey, RackPosition> stockIndex() {
        return stock.readOnlyView();
    }

    /** Total stored amount of {@code key} over all storage locations. */
    public long countOf(ItemKey key) {
        return stock.count(key);
    }

    /** Read-only view of the reservations of the crane's job (derived from the job, not saved). */
    public ReservationView<ItemKey, RackPosition> reservations() {
        return dispatch.reservations();
    }

    /** Why the last planning run created no job; empty after a planned job or before the first run. */
    public Optional<NoJobReason> lastPlanReason() {
        return dispatch.lastReason();
    }

    /** Whether the controller backs off planning after "warehouse full" at game time {@code now}. */
    public boolean isBackingOff(long now) {
        return dispatch.isBackingOff(now);
    }

    /**
     * Rebuilds the reservations from a crane job, as the controller does every dispatch interval with the linked crane's
     * job (adoption after load or when placed behind a busy crane). Public for tests of detached copies.
     */
    public void adoptCraneJob(Optional<TransportJob<ItemKey, RackPosition>> craneJob) {
        dispatch.adopt(Objects.requireNonNull(craneJob, "craneJob"));
    }

    /** Whether the storage location at {@code rack} waits for a snapshot (content hint, joined, load verification). */
    public boolean isSnapshotPending(RackPosition rack) {
        return pendingSnapshots.contains(Objects.requireNonNull(rack, "rack"));
    }

    /** Number of storage locations waiting for a snapshot. */
    public int pendingSnapshotCount() {
        return pendingSnapshots.size();
    }

    /**
     * The storage location whose stock index entry counts the inventory of {@code rack}: {@code rack} itself, or the one
     * location that counts an inventory several locations read (double chest, vault). Empty until {@code rack} was read.
     */
    public Optional<RackPosition> sharedInventoryOf(RackPosition rack) {
        return sharedInventories.canonicalOf(Objects.requireNonNull(rack, "rack"));
    }

    /**
     * The reservations at the storage location {@code rack} by item type, for the goggles of its interface
     * ({@code docs/warehouse-system.md} §3.1.1): what a crane job brings and what it will take out. A location that reads
     * the same inventory as another one shows the reservations of the location that counts it ({@link #sharedInventoryOf}),
     * where the planner reserves. O(number of reservations).
     */
    public LocationReservationSummary reservationSummaryAt(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return LocationReservationSummary.of(reservations().reservationsAt(sharedInventoryOf(rack).orElse(rack)));
    }

    /** The storage locations reading the same inventory as {@code rack}, the counting one first. */
    public List<RackPosition> locationsSharingInventory(RackPosition rack) {
        return sharedInventories.sharing(Objects.requireNonNull(rack, "rack"));
    }

    /** The address of a rack position of this aisle, whatever stands there; empty outside the aisle or without dock. */
    public Optional<StorageAddress> addressOf(BlockPos pos) {
        return layout == null ? Optional.empty() : layout.addressOf(pos);
    }

    /** The member record at a world position. */
    public Optional<LocationRecord> locationAt(BlockPos pos) {
        if (layout == null)
            return Optional.empty();
        return layout.worldToLocal(pos).flatMap(rack -> membership.kindAt(rack).map(kind -> new LocationRecord(rack, kind)));
    }

    /** The world position of a rack position of this aisle. */
    public Optional<BlockPos> worldPosOf(RackPosition rack) {
        return layout == null ? Optional.empty() : Optional.of(layout.rackPos(rack));
    }

    /** The goggle summary as of the last observation (server) or sync (client). */
    public ControllerGoggleSummary summary() {
        return summary;
    }

    /** Server: re-link to the dock on the next tick. */
    public void requestRelink() {
        relinkRequested = true;
    }

    /**
     * Server: re-links to the dock now instead of on the next tick. Called by a dock whose crane has a job but no linked
     * controller (it ticked first after loading, or this controller's chunk does not tick), so the crane's reports reach
     * this controller. One block entity lookup; see {@link #requestRelink} for the regular path.
     */
    public void relinkNow() {
        if (level == null || level.isClientSide || isRemoved() || isVirtual())
            return;
        relink(level.getGameTime());
    }

    /** From the registry: a member at {@code rack} changed. */
    void onMemberChanged(RackPosition rack) {
        membership.markDirty(rack);
    }

    /** From the registry: the inventory of the storage location at {@code rack} changed; read it again soon. */
    void onContentChanged(RackPosition rack) {
        if (membership.kindAt(rack).orElse(null) == LocationKind.STORAGE)
            pendingSnapshots.addUrgent(rack);
    }

    /**
     * From the registry: a player changed the store filter of the storage location at {@code rack}. Re-reads that one
     * filter at once (one block entity lookup), so the next planning run already honours it; the inventory itself is not
     * re-read, because its contents did not change ({@code docs/warehouse-system.md} §3.1, ADR-021).
     */
    void onStorageFilterChanged(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return;
        if (membership.kindAt(rack).orElse(null) != LocationKind.STORAGE) {
            filters.remove(rack);
            return;
        }
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof StorageMember member && !blockEntity.isRemoved())
            filters.set(rack, member.storeFilter());
    }

    /**
     * Server: re-reads one storage location into the stock index now (after a transfer, or on demand). If another
     * location reads the same inventory, the counts are stored for the location that counts it
     * ({@link #sharedInventoryOf}).
     *
     * @return whether a snapshot was taken; false if {@code rack} is no storage location, it or its inventory is not
     * loaded, or the inventory failed to read (the last counts are kept in these cases)
     */
    public boolean refreshLocation(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (level == null || level.isClientSide || layout == null)
            return false;
        if (membership.kindAt(rack).orElse(null) != LocationKind.STORAGE)
            return false;
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageMember member) || blockEntity.isRemoved()) {
            membership.markDirty(rack); // gone without a notification: probe it again
            return false;
        }
        // The store filter is refreshed here as well: this is every path on which the controller already resolves the
        // member (join, content hint, round robin, after a transfer, load verification), and it has to be known even
        // when the attached inventory is not loaded (ADR-021).
        filters.set(rack, member.storeFilter());
        BlockPos attached = member.attachedPos();
        if (!level.isLoaded(attached))
            return false;
        InventorySnapshot<ItemKey> snapshot;
        Object identity;
        try {
            identity = inventoryIdentity(member, attached);
            snapshot = member.snapshot();
        } catch (RuntimeException e) {
            if (snapshotFailures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Warehouse controller at {} could not read storage location {}", worldPosition,
                        rack, e);
            return false;
        }
        pendingSnapshots.remove(rack);
        boolean newLocation = !stock.contains(rack);
        SharedInventories.Assignment<RackPosition> assignment = sharedInventories.assign(rack, identity);
        // Fresh contents: a remembered live refusal of this inventory may no longer hold.
        dispatch.forgetRefusals(rack);
        dispatch.forgetRefusals(assignment.canonical());
        assignment.promoted().ifPresent(promoted -> handOver(rack, promoted));
        boolean changed;
        if (assignment.canonical().equals(rack)) {
            changed = stock.update(rack, snapshot);
        } else {
            // Another location counts this inventory: this one keeps no counts of its own.
            changed = stock.restore(rack, Map.of());
            changed |= stock.update(assignment.canonical(), snapshot);
        }
        if (assignment.identityChanged())
            queueOtherReaders(layout, rack, identity);
        if (changed || newLocation)
            setChanged();
        return true;
    }

    /**
     * Create's identifier of the inventory at {@code attached} as seen from the member (a double chest and every block
     * of an item vault share one), or the position for inventories Create does not identify.
     */
    private Object inventoryIdentity(StorageMember member, BlockPos attached) {
        InventoryIdentifier identifier = InventoryIdentifier.get(level,
                new BlockFace(attached, member.facing().getOpposite()));
        return identifier != null ? identifier : attached.immutable();
    }

    /**
     * A location read a multi-block inventory for the first time or after a change: other storage locations of this
     * aisle that are attached to a block of that inventory, but still count it as something else, are read again soon,
     * so the inventory is not counted twice until the round robin reaches them. Position arithmetic only, no block
     * reads.
     * <p>
     * It walks this aisle's own storage records (at most 2 · (L+1) · H) and asks the identifier whether it contains
     * their inventory face, rather than enumerating the inventory's blocks: a Create item vault of 16 × 16 × 16 blocks
     * cost 8192 containment tests with two fresh block positions each, in a single tick, for every location that
     * reported a new identity (M5 release review). Because the aisle is the bound now, the old 4096-block cap is gone,
     * so very large vaults are handled as well.
     */
    private void queueOtherReaders(AisleLayout current, RackPosition rack, Object identity) {
        if (!(identity instanceof InventoryIdentifier identifier))
            return; // a bare position identifies exactly one block: no other location can read it as something else
        for (LocationRecord record : membership.records(LocationKind.STORAGE)) {
            RackPosition other = record.position();
            if (other.equals(rack) || identity.equals(sharedInventories.identityOf(other).orElse(null)))
                continue;
            Direction side = current.sideDirection(other.side());
            if (identifier.contains(new BlockFace(current.rackPos(other).relative(side), side.getOpposite())))
                pendingSnapshots.addUrgent(other);
        }
    }

    /** {@code promoted} now counts the inventory {@code from} counted: it takes over the counts until it is read. */
    private void handOver(RackPosition from, RackPosition promoted) {
        stock.restore(promoted, stock.countsAt(from));
        pendingSnapshots.addUrgent(promoted);
    }

    // --- production (M11, ADR-024) --------------------------------------------------------------------------------

    /** One pattern of one production station of this aisle. */
    public record AislePattern(RackPosition station, ProductionPattern<ItemKey> pattern) {
    }

    /** The production station records of this aisle. */
    public List<LocationRecord> productionStations() {
        return membership.records(LocationKind.PRODUCTION);
    }

    /**
     * Every complete pattern of every loaded production station of this aisle, with the station it belongs to.
     * <p>
     * Read live (one block entity lookup per production station), not cached: an aisle has a handful of them, the
     * patterns live in the stations themselves, and this is only asked when a request arrives or a terminal screen
     * refreshes — never per tick and never per planner candidate.
     */
    public List<AislePattern> aislePatterns() {
        if (level == null || level.isClientSide || layout == null)
            return List.of();
        List<AislePattern> found = new ArrayList<>();
        for (LocationRecord record : membership.records(LocationKind.PRODUCTION)) {
            BlockPos pos = layout.rackPos(record.position());
            if (!level.isLoaded(pos))
                continue;
            if (level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station && !station.isRemoved()) {
                for (ProductionPattern<ItemKey> pattern : station.activePatterns())
                    found.add(new AislePattern(record.position(), pattern));
            }
        }
        return List.copyOf(found);
    }

    /**
     * The item keys this aisle can produce, whether or not the ingredients are in stock. A terminal marks them as
     * producible even at zero stock, so a player sees what this warehouse could make for them.
     */
    public Set<ItemKey> producibleKeys() {
        Set<ItemKey> keys = new LinkedHashSet<>();
        for (AislePattern candidate : aislePatterns())
            keys.add(candidate.pattern().result().key());
        return keys;
    }

    /**
     * How many items of {@code key} this aisle could produce <b>right now</b>: the best single pattern, paid for with
     * the ingredients that are available (stage 1, {@link ProduciblePlanner} — an ingredient that is itself only
     * producible counts for nothing). 0 when no pattern makes it, nothing is left to pay with, or the order cap is
     * reached, because no further order could be started for it either.
     */
    public long producibleAmount(ItemKey key) {
        Objects.requireNonNull(key, "key");
        if (level == null || level.isClientSide || layout == null)
            return 0L;
        return producibleAmount(key, aislePatterns(), availabilityLookup());
    }

    /**
     * {@link #producibleAmount(ItemKey)} against patterns and an availability function the caller has already built,
     * so one request does not resolve every production station's block entity again for each question it asks.
     */
    private long producibleAmount(ItemKey key, List<AislePattern> patterns, ToLongFunction<ItemKey> available) {
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        if (productionOrders.isFull())
            return 0L;
        long best = 0L;
        for (AislePattern candidate : patterns) {
            if (candidate.pattern().produces(key))
                best = Math.max(best, ProduciblePlanner.producibleAmount(candidate.pattern(), available));
        }
        return best;
    }

    /**
     * {@link #availableStock} for <b>many</b> keys at once: the two scans it does per key — the open requests and the
     * ingredients the open orders promised — are each collected into one map here, so a caller that asks for every
     * ingredient of every pattern costs two map lookups per key instead of two passes over the queue and the orders.
     * This is the same reason {@link #remainingRequestedByKey} exists, applied to the production side as well.
     * <p>
     * The snapshot is taken once and stays fixed for the caller's pass, which is also what makes it consistent: a
     * producible amount computed half against one state of the queue and half against another is nobody's answer.
     */
    private ToLongFunction<ItemKey> availabilityLookup() {
        Map<ItemKey, Long> requested = requests.remainingByKey();
        Map<ItemKey, Long> ingredients = productionOrders.outstandingIngredientsByKey();
        ReservationView<ItemKey, RackPosition> reservations = dispatch.reservations();
        return key -> reservations.availableStock(key, stock.count(key),
                requested.getOrDefault(key, 0L) + ingredients.getOrDefault(key, 0L));
    }

    /**
     * How many items of <b>every</b> producible key this aisle could make right now, in one pass over its patterns.
     * <p>
     * It answers exactly what {@link #producibleAmount} answers per key, and exists because the callers that need it
     * need it for many keys at once: a terminal's stock snapshot marks every producible key and reports how much of it
     * could be ordered ({@code docs/warehouse-system.md} §3.4.2). Asking per key would resolve the production stations'
     * block entities again for every key. A key with no ingredients left is present with <b>0</b>, because the terminal
     * still offers it — "can be made here" and "can be made now" are different statements.
     */
    public Map<ItemKey, Long> producibleAmounts() {
        List<AislePattern> patterns = aislePatterns();
        if (patterns.isEmpty())
            return Map.of();
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        // With the order cap reached nothing can be started, so nothing is producible right now, whatever is in stock.
        boolean full = productionOrders.isFull();
        Map<ItemKey, Long> amounts = new LinkedHashMap<>();
        ToLongFunction<ItemKey> available = availabilityLookup();
        for (AislePattern candidate : patterns) {
            long amount = full ? 0L
                    : ProduciblePlanner.producibleAmount(candidate.pattern(), available);
            // Two patterns for one item do not add up: the best single one is what is certainly reachable (§3.5.2).
            amounts.merge(candidate.pattern().result().key(), amount, Math::max);
        }
        return amounts;
    }

    /** The production orders of this aisle, open and recently finished, in creation order. */
    public List<ProductionOrder<ItemKey, RackPosition>> productionOrders() {
        return productionOrders.all();
    }

    /**
     * The production order {@code id} of <b>this</b> aisle, if it has one. A cancellation from a screen is checked
     * against this before anything happens, so a crafted payload cannot reach into another aisle's orders
     * ({@code docs/warehouse-system.md} §3.4.2, §3.5).
     */
    public Optional<ProductionOrder<ItemKey, RackPosition>> productionOrder(UUID id) {
        return id == null ? Optional.empty() : productionOrders.get(id);
    }

    /** The open production orders of this aisle. */
    public List<ProductionOrder<ItemKey, RackPosition>> openProductionOrders() {
        return productionOrders.open();
    }

    /** The production orders of the station at the world position {@code station}. */
    public List<ProductionOrder<ItemKey, RackPosition>> productionOrdersAt(BlockPos station) {
        Objects.requireNonNull(station, "station");
        if (layout == null)
            return List.of();
        return layout.worldToLocal(station).map(productionOrders::ordersFor).orElse(List.of());
    }

    /**
     * The ingredients the open production orders still owe their stations, for the planner
     * ({@code PlannerInput#supplies}). Only orders that are still collecting ingredients contribute, and only at
     * stations this aisle really records.
     */
    public List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> supplyNeeds() {
        if (level == null || level.isClientSide || layout == null || productionOrders.isEmpty())
            return List.of();
        List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> needs = new ArrayList<>();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open()) {
            if (order.state() != ProductionOrderState.WAITING_FOR_INGREDIENTS
                    || membership.kindAt(order.station()).orElse(null) != LocationKind.PRODUCTION)
                continue;
            for (SupplyLine<ItemKey> line : order.lines()) {
                if (line.remaining() > 0)
                    needs.add(new PlannerInput.SupplyNeed<>(line.id(), line.key(), line.remaining(), order.station()));
            }
        }
        return List.copyOf(needs);
    }

    /**
     * Whether {@code id} names something a crane job can still be working for: an open retrieval request, or an
     * ingredient line of an open production order that still needs items. The dispatch detaches a job's reservations
     * from an owner this answers false for.
     */
    public boolean hasOpenJobOwner(UUID id) {
        Objects.requireNonNull(id, "id");
        return requests.get(id).isPresent() || productionOrders.hasOpenLine(id);
    }

    /**
     * Server: cancels production order {@code id}. Its ingredients stop being promised; ingredients a machine already
     * took are <b>not</b> recovered ({@code docs/warehouse-system.md} §3.5), and the request that waited for the
     * result gets the unproduced amount back instead of waiting for ever.
     */
    public Optional<ProductionOrder<ItemKey, RackPosition>> cancelProductionOrder(UUID id) {
        if (level == null || level.isClientSide)
            return Optional.empty();
        Optional<ProductionOrder<ItemKey, RackPosition>> cancelled = productionOrders.cancel(id, level.getGameTime());
        cancelled.ifPresent(order -> {
            onProductionOrderFinished(order);
            setChanged();
        });
        return cancelled;
    }

    /** A production station was broken or replaced: its orders can never finish, so they are cancelled. */
    public void onProductionStationRemoved(BlockPos station) {
        Objects.requireNonNull(station, "station");
        if (layout == null)
            return;
        layout.worldToLocal(station).ifPresent(this::cancelProductionOrdersAt);
    }

    private void cancelProductionOrdersAt(RackPosition rack) {
        long now = level == null ? 0L : level.getGameTime();
        for (ProductionOrder<ItemKey, RackPosition> cancelled : productionOrders.cancelFor(rack, now))
            onProductionOrderFinished(cancelled);
    }

    /**
     * Starts a production order for {@code amount} items of {@code key}, using the pattern of {@code patterns} that
     * can make the most of it right now.
     *
     * @return how many result items the order <b>promises the backing request</b>, i.e. at most {@code amount} (0 when
     * no order could be started). The order itself may yield more, because a pattern makes whole runs; that surplus
     * simply lands in stock and was promised to nobody ({@code ProductionOrder#promisedToRequest}).
     */
    private int startProductionOrder(ItemKey key, int amount, UUID backingRequest, List<AislePattern> patterns) {
        if (level == null || layout == null || amount < 1)
            return 0;
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        ToLongFunction<ItemKey> available = availabilityLookup();
        Optional<AislePattern> found = bestProductionPattern(key, patterns, available);
        if (found.isEmpty())
            return 0;
        AislePattern chosen = found.get();
        int runs = Math.min(chosen.pattern().runsFor(amount),
                ProduciblePlanner.runsPossible(chosen.pattern(), available));
        if (runs < 1)
            return 0;
        int promised = Math.min(chosen.pattern().resultFor(runs), amount);
        ProductionOrder<ItemKey, RackPosition> order = ProductionOrder.start(UUID.randomUUID(), chosen.station(),
                chosen.pattern(), runs, UUID::randomUUID, level.getGameTime(), productionTimeoutTicks(),
                stock.count(key), backingRequest, promised);
        if (!productionOrders.add(order))
            return 0;
        setChanged();
        return promised;
    }

    /** The pattern of {@code patterns} that can make the most of {@code key} right now. */
    private Optional<AislePattern> bestProductionPattern(ItemKey key, List<AislePattern> patterns,
            ToLongFunction<ItemKey> available) {
        AislePattern best = null;
        long bestAmount = 0L;
        for (AislePattern candidate : patterns) {
            if (!candidate.pattern().produces(key))
                continue;
            long amount = ProduciblePlanner.producibleAmount(candidate.pattern(), available);
            if (amount > bestAmount) {
                best = candidate;
                bestAmount = amount;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Server: the production orders' own tick — the machine taking ingredients out of a station, result items
     * arriving, timeouts and forgetting finished orders. Runs only while orders exist, at the dispatch cadence.
     */
    private void tickProductionOrders(long now) {
        if (productionOrders.isEmpty())
            return;
        long timeout = productionTimeoutTicks();
        boolean changed = false;
        // The player's machine took the ingredients out of the station: that order now waits for its result. The
        // question is asked and answered <b>per order</b> — a station runs as many orders as it has patterns, and
        // only the order whose own ingredients have left the buffer may move on (§3.5.3).
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open()) {
            if (order.state() == ProductionOrderState.DELIVERED && !stationStillHolds(order))
                changed |= productionOrders.ingredientsTaken(order.id(), now, timeout).isPresent();
        }
        for (ProductionOrder<ItemKey, RackPosition> timedOut : productionOrders.timeOut(now)) {
            onProductionOrderFinished(timedOut);
            changed = true;
        }
        changed |= productionOrders.prune(now, FINISHED_ORDER_RETENTION_TICKS) > 0;
        if (changed)
            setChanged();
    }

    /**
     * Server: result items that arrived in the warehouse, per distinct result key of the open orders (one stock index
     * lookup each).
     * <p>
     * This runs on <b>every</b> tick rather than at the dispatch cadence, because it reads a level instead of counting
     * an event: anything that returns the level to the previous sample between two observations — the crane storing a
     * batch and a {@code RETRIEVE} taking exactly it back out again, a funnel emptying a chest — would be counted as
     * no progress at all, and the order would eventually time out although the machine worked perfectly (§3.5.3).
     * Observing every tick narrows that window to a single tick, at a cost of one map lookup per distinct result key,
     * bounded by {@code maxProductionOrders}; the expensive part of the order tick (block entity lookups, timeouts,
     * pruning) stays on the dispatch cadence in {@link #tickProductionOrders}.
     *
     * @return whether anything changed, i.e. whether the controller has to be saved
     */
    private boolean observeProductionResults(long now) {
        long timeout = productionTimeoutTicks();
        if (productionDeadlinesPending) {
            productionDeadlinesPending = false;
            productionOrders.restartDeadlines(now, timeout);
        }
        Set<ItemKey> results = new HashSet<>();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open())
            results.add(order.result());
        boolean changed = false;
        for (ItemKey result : results) {
            // Only an order that really changed marks the controller dirty: observing an unchanged stock level must
            // not save the chunk on every tick for as long as an order is open.
            for (ProductionOrder<ItemKey, RackPosition> observed
                    : productionOrders.observeResult(result, stock.count(result), now, timeout)) {
                changed = true;
                if (observed.state() == ProductionOrderState.COMPLETE)
                    onProductionOrderFinished(observed);
            }
        }
        return changed;
    }

    /**
     * Whether the production station of {@code order} still holds any of its ingredients. A station that is not loaded
     * answers true, so an order never advances past "delivered" on a guess.
     */
    private boolean stationStillHolds(ProductionOrder<ItemKey, RackPosition> order) {
        if (level == null || layout == null)
            return true;
        BlockPos pos = layout.rackPos(order.station());
        if (!level.isLoaded(pos))
            return true;
        if (!(level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station) || station.isRemoved())
            return false;
        Set<ItemKey> keys = new HashSet<>();
        for (SupplyLine<ItemKey> line : order.lines())
            keys.add(line.key());
        return station.holdsAnyOf(keys);
    }

    /**
     * A production order ended. The crane stops fetching for it, and the request that waited for its result gets back
     * what this order <b>promised</b> it and will never deliver, so that request does not wait for items nobody will
     * ever make. <b>Items are never invented and never taken back</b>: ingredients already handed to a machine stay
     * where they are ({@code docs/warehouse-system.md} §3.5.4).
     * <p>
     * A completed order needs no special case: it produced its whole promise, so
     * {@code ProductionOrder#unfulfilledPromise} is 0 and nothing is given back.
     */
    private void onProductionOrderFinished(ProductionOrder<ItemKey, RackPosition> order) {
        cancelSupplyJobsOf(order);
        // Only what production promised this request, never the whole run: a pattern makes whole runs, so the surplus
        // of an order was promised to nobody, and taking it off the request would strip that request of items the
        // aisle really holds — or delete it outright when the shortfall reaches its remaining amount (§3.5.3).
        long owed = order.unfulfilledPromise();
        if (owed > 0)
            order.backingRequest().ifPresent(id -> reduceRequest(id, (int) Math.min(Integer.MAX_VALUE, owed)));
    }

    /**
     * Stops a crane job that is fetching an ingredient for {@code order}: before the pick it aborts, after it the held
     * items are rerouted back into storage ({@link CraneDispatch#onOwnersCancelled}). Without this the crane finished
     * the trip of an order that had already ended and dropped its ingredients into the machine's buffer
     * ({@code docs/warehouse-system.md} §3.5.4).
     */
    private void cancelSupplyJobsOf(ProductionOrder<ItemKey, RackPosition> order) {
        Set<UUID> lines = new HashSet<>();
        for (SupplyLine<ItemKey> line : order.lines())
            lines.add(line.id());
        dispatch.onOwnersCancelled(lines);
    }

    /** Takes {@code amount} items off a request without counting them as delivered; releases it if nothing remains. */
    private void reduceRequest(UUID id, int amount) {
        if (amount <= 0)
            return;
        Optional<RetrievalRequest<ItemKey, BlockPos>> before = requests.get(id);
        if (before.isEmpty())
            return;
        if (requests.reduce(id, amount).isEmpty())
            onRequestsGone(List.of(before.get()));
        setChanged();
    }

    /**
     * Requests are gone — cancelled, pruned with their output, or reduced to nothing. No production order may go on
     * naming one of them ({@code ProductionOrders#detachRequest}: it keeps running, and its result lands in stock),
     * their reservations stop backing a request and a crane job serving one is cancelled.
     */
    private void onRequestsGone(List<RetrievalRequest<ItemKey, BlockPos>> gone) {
        for (RetrievalRequest<ItemKey, BlockPos> request : gone)
            productionOrders.detachRequest(request.id());
        dispatch.onRequestsCancelled(gone);
    }

    private static int configuredMaxProductionOrders() {
        return Math.max(ProductionOrders.MIN_OPEN_ORDERS, WareworksConfig.maxProductionOrders());
    }

    private static long productionTimeoutTicks() {
        return Math.max(1, WareworksConfig.productionOrderTimeoutTicks());
    }

    // --- retrieval requests --------------------------------------------------------------------------------------

    /**
     * Server: a retrieval request with no amount cap of its own, i.e. bounded only by {@link #availableStock}; see
     * {@link #request(BlockPos, ItemKey, int, int)}.
     * <p>
     * Both station entry points pass a cap of their own (a terminal {@code maxTerminalRequestAmount}, an output
     * {@code WarehouseOutputBlockEntity#maxRequestAmount()}), because a repeated request merges into the open one and
     * an unbounded merge would let one station promise a whole item type (§7.2). This overload is for callers that
     * bound the amount themselves, such as GameTests.
     */
    public RequestResult request(BlockPos outputPos, ItemKey key, int amount) {
        return request(outputPos, key, amount, RequestQueue.NO_AMOUNT_LIMIT);
    }

    /**
     * Server: a retrieval request for up to {@code amount} items of {@code key} (exact item identity), to be delivered
     * to the output station at {@code outputPos} ({@code docs/warehouse-system.md} §7.2).
     * <p>
     * Rejected with {@link RequestRejection#NO_CONTROLLER} unless the controller has an aisle and {@code outputPos} holds
     * an aligned warehouse output at one of its rack positions (checked live with one block entity lookup, so a station
     * placed this tick already counts). The amount is clamped to {@link #availableStock}; nothing available gives
     * {@link RequestRejection#NOT_IN_STOCK}, {@code maxOpenRequestsPerOutput} open requests for this output give
     * {@link RequestRejection#OUTPUT_FULL}, {@code maxOpenRequests} open requests give {@link RequestRejection#QUEUE_FULL}.
     * <p>
     * <b>Repeated requests merge</b> (ADR-020): when this station already has an open request for {@code key}, the
     * accepted amount grows that request instead of queueing a second one. Ten clicks for one item are therefore one
     * request the crane serves in one trip. A merge takes no queue slot, so the two queue caps cannot refuse it;
     * {@code maxRemainingPerRequest} bounds the <b>merged</b> remaining amount and gives
     * {@link RequestRejection#REQUEST_FULL} when nothing fits any more. Because {@link #availableStock} already subtracts
     * what the open requests promise, the stock clamp applies to the merged total as well. A grown request keeps its
     * queue position until it has been served once and then moves behind the waiting requests, so repeatedly topping one
     * up cannot starve the other stations (§7.2 "fairness").
     *
     * @param maxRemainingPerRequest largest amount one request of this station may wait for, at least 1
     *                               ({@link RequestQueue#NO_AMOUNT_LIMIT} for no cap of its own)
     * @throws IllegalArgumentException if {@code amount < 1} or {@code maxRemainingPerRequest < 1}
     */
    public RequestResult request(BlockPos outputPos, ItemKey key, int amount, int maxRemainingPerRequest) {
        Objects.requireNonNull(outputPos, "outputPos");
        Objects.requireNonNull(key, "key");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (level == null || level.isClientSide || isRemoved() || layout == null || !isOutputStation(layout, outputPos))
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        requests.setMaxOpenRequests(configuredMaxOpenRequests());
        requests.setMaxOpenRequestsPerDestination(Math.max(RequestQueue.MIN_OPEN_REQUESTS,
                WareworksConfig.maxOpenRequestsPerOutput()));
        // What the aisle really holds, and what its production patterns could still make of what it holds. A request
        // may ask for both: the stock part is served at once, the produced part as it arrives through a warehouse
        // input, by ordinary RETRIEVE jobs serving this very request (§3.5, ADR-024).
        long inStock = availableStock(key);
        // One pass over the aisle's patterns for every production question this request asks — how much could be
        // made, which pattern would make it, and what its ingredients are still worth. Asking each of them separately
        // resolved every production station's block entity again per click (§3.5.2).
        List<AislePattern> patterns = aislePatterns();
        long producible = producibleAmount(key, patterns, availabilityLookup());
        RequestQueue.AddResult<ItemKey, BlockPos> added = requests.add(key, amount, outputPos.immutable(),
                candidate -> candidate.equals(key) ? inStock + producible : availableStock(candidate),
                maxRemainingPerRequest);
        if (added.request().isEmpty()) {
            return RequestResult.rejected(switch (added.rejection().orElseThrow()) {
                case QUEUE_FULL -> RequestRejection.QUEUE_FULL;
                case DESTINATION_FULL -> RequestRejection.OUTPUT_FULL;
                case NOTHING_AVAILABLE -> nothingAvailableReason(key, patterns);
                case REQUEST_FULL -> RequestRejection.REQUEST_FULL;
            });
        }
        RetrievalRequest<ItemKey, BlockPos> accepted = added.request().get();
        int granted = added.accepted();
        int fromProduction = (int) Math.max(0L, granted - inStock);
        // A pattern makes whole runs, so an order may yield more than was asked for; that surplus simply lands in
        // stock. What this request waits for — and what it gets back if the order fails — is never more than what it
        // asked for, which the order records as its promise (§3.5.3).
        int producing = fromProduction > 0 ? startProductionOrder(key, fromProduction, accepted.id(), patterns) : 0;
        if (producing < fromProduction) {
            // No order could be started after all, or a smaller one: give the request back what will never be made,
            // instead of leaving it waiting for items nobody produces.
            reduceRequest(accepted.id(), fromProduction - producing);
            granted -= fromProduction - producing;
        }
        if (granted < 1)
            return RequestResult.rejected(nothingAvailableReason(key, patterns));
        setChanged();
        return RequestResult.accepted(requests.get(accepted.id()).orElse(accepted), granted, added.merged(), producing);
    }

    /**
     * Why nothing of {@code key} could be promised. A full production order queue is told apart from a plain "not in
     * stock" ({@link RequestRejection#PRODUCTION_BUSY}), because the cure is a different one: the ingredients may all
     * be there, and what the player has to do is wait for an order to finish or give one up rather than go looking
     * for an item the warehouse is not missing.
     */
    private RequestRejection nothingAvailableReason(ItemKey key, List<AislePattern> patterns) {
        if (!productionOrders.isFull())
            return RequestRejection.NOT_IN_STOCK;
        for (AislePattern candidate : patterns) {
            if (candidate.pattern().produces(key))
                return RequestRejection.PRODUCTION_BUSY;
        }
        return RequestRejection.NOT_IN_STOCK;
    }

    private boolean isOutputStation(AisleLayout current, BlockPos pos) {
        Optional<RackPosition> rack = current.worldToLocal(pos);
        if (rack.isEmpty() || !level.isLoaded(pos))
            return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof WarehouseMember member && !blockEntity.isRemoved()
                && member.locationKind() == LocationKind.OUTPUT && member.isAlignedWith(current, rack.get().side());
    }

    /**
     * Cancels the requests whose destination is no aligned output of this aisle any more although it is loaded (e.g. an
     * output removed before the controller ticked, so it never became a record), or lies outside the aisle. Requests for
     * unloaded destinations are kept. One block entity lookup per distinct destination.
     *
     * @return whether a request was cancelled
     */
    private boolean pruneRequests(AisleLayout current) {
        if (requests.isEmpty())
            return false;
        boolean cancelled = false;
        Set<BlockPos> checked = new HashSet<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : requests.requests()) {
            BlockPos destination = request.destination();
            if (!checked.add(destination))
                continue;
            boolean outside = current.worldToLocal(destination).isEmpty();
            if (outside || (level.isLoaded(destination) && !isOutputStation(current, destination)))
                cancelled |= cancelRequestsFor(destination);
        }
        return cancelled;
    }

    /** Cancels every request for {@code destination} and a crane job serving one of them. */
    private boolean cancelRequestsFor(BlockPos destination) {
        List<RetrievalRequest<ItemKey, BlockPos>> cancelled = requests.cancelFor(destination);
        onRequestsGone(cancelled);
        return !cancelled.isEmpty();
    }

    /**
     * Stock of {@code key} that a new request may still claim ({@code ReservationView#availableStock}): the indexed count
     * minus stock reserved for jobs that serve no request, minus what the open requests for {@code key} still need from
     * storage (their remaining amounts without the items already in the crane's head for them). Reservations backing a
     * request are inside its remaining amount and are not subtracted twice. Never negative.
     */
    public long availableStock(ItemKey key) {
        Objects.requireNonNull(key, "key");
        // Open production orders promise their ingredients exactly as open requests promise their items: both are
        // already spoken for, so neither may be handed out twice (§3.5, ADR-024).
        return dispatch.reservations().availableStock(key, stock.count(key),
                requests.remainingOf(key) + productionOrders.outstandingIngredient(key));
    }

    /**
     * What the open requests still owe, per item key, in <b>one</b> pass over the queue.
     * <p>
     * For callers that need {@link #availableStock} for many keys at once (a warehouse terminal's stock snapshot,
     * {@code warehouse-system.md} §3.4.1): pass an entry of this map as the {@code openRequestRemaining} of
     * {@code ReservationView#availableStock} instead of asking {@link #availableStock} per key, which scans the whole
     * queue every time. A key without an open request is absent (i.e. owes 0).
     */
    public Map<ItemKey, Long> remainingRequestedByKey() {
        return requests.remainingByKey();
    }

    /**
     * Stock of {@code key} reserved for jobs that do not serve an open request (reservations for requests are part of
     * the requests' remaining amounts, which {@link #availableStock} subtracts on its own).
     */
    public long reservedStock(ItemKey key) {
        return dispatch.reservations().reservedStockNotBackingRequests(Objects.requireNonNull(key, "key"));
    }

    /** The open retrieval requests in queue order (oldest first). */
    public List<RetrievalRequest<ItemKey, BlockPos>> openRequests() {
        return requests.requests();
    }

    public int openRequestCount() {
        return requests.openCount();
    }

    /** Whether request {@code id} is open. */
    public boolean hasOpenRequest(UUID id) {
        return requests.get(Objects.requireNonNull(id, "id")).isPresent();
    }

    /** The oldest open request, which the crane serves first. */
    public Optional<RetrievalRequest<ItemKey, BlockPos>> oldestOpenRequest() {
        return requests.oldestOpen();
    }

    /** The open requests for the output station at {@code outputPos}, oldest first. */
    public List<RetrievalRequest<ItemKey, BlockPos>> requestsFor(BlockPos outputPos) {
        return requests.requestsFor(outputPos);
    }

    /** Items the open requests for the output station at {@code outputPos} still wait for. */
    public long requestedFor(BlockPos outputPos) {
        return requests.remainingFor(outputPos);
    }

    /** Items of the open requests for the output station at {@code outputPos} that were already delivered. */
    public long deliveredFor(BlockPos outputPos) {
        long delivered = 0;
        for (RetrievalRequest<ItemKey, BlockPos> request : requests.requestsFor(outputPos))
            delivered += request.delivered();
        return delivered;
    }

    /**
     * Server: {@code amount} items of request {@code id} were dropped into its output buffer. A request is removed
     * once nothing remains.
     *
     * @return the amount counted towards the request (0 for an unknown id)
     */
    public int deliverRequest(UUID id, int amount) {
        int counted = requests.deliver(id, amount);
        if (counted > 0)
            setChanged();
        return counted;
    }

    /** Server: removes request {@code id}; a crane job serving it is cancelled. */
    public Optional<RetrievalRequest<ItemKey, BlockPos>> cancelRequest(UUID id) {
        Optional<RetrievalRequest<ItemKey, BlockPos>> cancelled = requests.cancel(id);
        if (cancelled.isPresent()) {
            setChanged();
            onRequestsGone(List.of(cancelled.get()));
        }
        return cancelled;
    }

    private static int configuredMaxOpenRequests() {
        return Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequests());
    }

    // --- crane reports (M3) --------------------------------------------------------------------------------------

    /** Reports are accepted from the linked dock only. */
    private boolean acceptsReportsFrom(StackerCraneBlockEntity crane) {
        return level != null && !level.isClientSide && layout != null && crane.getBlockPos().equals(linkedDock);
    }

    /**
     * The crane picked {@code job} ({@code docs/warehouse-system.md} §7.3 {@code onPicked}): its stock reservation ends
     * (the held amount continues as transit or capacity), and a storage source is re-read at once.
     */
    public void onCranePicked(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (!acceptsReportsFrom(crane))
            return;
        dispatch.track(job);
        if (job.sourceKind() == LocationKind.STORAGE)
            refreshLocation(job.source());
    }

    /**
     * The crane dropped {@code delivered} items of {@code job} at {@code target} ({@code onDelivered}). A delivery into
     * the output station of the job's request counts towards the request (only real drops count); the reservations
     * follow the job; a storage target is re-read at once.
     *
     * @return whether the job's request (if any) is still open; false tells the crane to detach it
     */
    public boolean onCraneDelivered(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job,
            RackPosition target, int delivered) {
        if (!acceptsReportsFrom(crane))
            return true;
        Optional<UUID> requestId = job.requestId();
        if (requestId.isPresent() && job.targetKind() == LocationKind.OUTPUT) {
            Optional<RetrievalRequest<ItemKey, BlockPos>> request = requests.get(requestId.get());
            if (request.isPresent() && request.get().destination().equals(layout.rackPos(target)))
                deliverRequest(requestId.get(), delivered);
        }
        // A supply job carries the ingredient line of a production order instead of a request (§3.5, ADR-024): this is
        // the moment an ingredient really arrived at the machine, so only a real drop counts here too.
        if (requestId.isPresent() && job.targetKind() == LocationKind.PRODUCTION && delivered > 0
                && productionOrders.deliver(requestId.get(), delivered, level.getGameTime(), productionTimeoutTicks())
                        .isPresent())
            setChanged();
        dispatch.track(job);
        if (job.targetKind() == LocationKind.STORAGE)
            refreshLocation(target);
        return requestId.map(this::hasOpenJobOwner).orElse(true);
    }

    /** The crane's leftovers have a new target: the reservations move with them. */
    public void onCraneRerouted(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane))
            dispatch.track(job);
    }

    /** Everything picked was delivered: release the job's reservations. */
    public void onCraneJobFinished(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane))
            dispatch.release(job.id());
    }

    /** The crane gave up the job with nothing held ({@code onJobAborted}): release it; requests keep their amount. */
    public void onCraneJobAborted(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job,
            AbortReason reason) {
        if (acceptsReportsFrom(crane))
            dispatch.release(job.id());
    }

    /**
     * The crane lost the job with its block (broken: the head dropped its items; cleared by a command): release its
     * reservations; a request keeps its remaining amount ({@code docs/warehouse-system.md} §8).
     */
    public void onCraneJobLost(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane))
            dispatch.release(job.id());
    }

    /**
     * A new target for {@code amount} items left in the crane's head ({@code planReroute}, §8); empty to hold them.
     * {@code failedTarget} (the target that just failed) is excluded; {@code null} for a hold retry.
     */
    public Optional<RerouteTarget<RackPosition>> planReroute(StackerCraneBlockEntity crane,
            TransportJob<ItemKey, RackPosition> job, @Nullable RackPosition failedTarget, int amount) {
        if (!acceptsReportsFrom(crane))
            return Optional.empty();
        return dispatch.planReroute(level, layout, crane, job, failedTarget, amount);
    }

    // --- ticking -------------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || isVirtual())
            return;
        long now = level.getGameTime();
        if (relinkRequested || now >= nextRelinkTick)
            relink(now);
        if (layout == null)
            return;
        if (membership.isDirty())
            processMembership(layout);
        drainPendingSnapshots();
        if (!productionOrders.isEmpty()) {
            if (observeProductionResults(now))
                setChanged();
            if (now >= nextProductionTick) {
                nextProductionTick = now + Math.max(1, WareworksConfig.dispatchIntervalTicks());
                tickProductionOrders(now);
            }
        }
        if (now >= nextSnapshotTick) {
            nextSnapshotTick = now + WareworksConfig.snapshotIntervalTicks();
            nextRoundRobinLocation().ifPresent(this::refreshLocation);
        }
        if (layout != null)
            dispatch.tick(level, layout, now);
    }

    /**
     * Reads at most {@code maxSnapshotsPerTick} queued storage locations, urgent ones first.
     * <p>
     * A location whose rack position is not loaded is put back (M8 review fix): {@link SnapshotQueue#poll} has already
     * removed it, so dropping it here left its restored counts to the round robin, which needs up to
     * 2 · (L+1) · H · {@code snapshotIntervalTicks} to come round — minutes at the default caps (§5).
     */
    private void drainPendingSnapshots() {
        for (int budget = Math.max(1, WareworksConfig.maxSnapshotsPerTick()); budget > 0; budget--) {
            Optional<RackPosition> next = pendingSnapshots.poll();
            if (next.isEmpty())
                return;
            RackPosition rack = next.get();
            if (!refreshLocation(rack) && isRackUnloaded(rack))
                pendingSnapshots.addBackground(rack); // keep it queued until its chunk is loaded again
        }
    }

    /** Whether the rack position of {@code rack} lies in an unloaded chunk (the one refresh failure that is transient). */
    private boolean isRackUnloaded(RackPosition rack) {
        return level != null && layout != null && !level.isLoaded(layout.rackPos(rack));
    }

    /** The next storage location of the round robin that counts its inventory itself (aliases are skipped). */
    private Optional<RackPosition> nextRoundRobinLocation() {
        for (int tries = membership.storageCount(); tries > 0; tries--) {
            Optional<RackPosition> next = membership.nextStorageLocation();
            if (next.isEmpty() || !sharedInventories.isAlias(next.get()))
                return next;
        }
        return Optional.empty();
    }

    private void relink(long now) {
        relinkRequested = false;
        nextRelinkTick = now + Math.max(MIN_RELINK_INTERVAL_TICKS, WareworksConfig.geometryRefreshTicks());
        Direction facing = facing();
        BlockPos dockPos = worldPosition.relative(facing);
        if (!level.isLoaded(dockPos))
            return; // keep the known layout until the dock is loaded again
        StackerCraneBlockEntity inFront = level.getBlockEntity(dockPos) instanceof StackerCraneBlockEntity crane
                && !crane.isRemoved() ? crane : null;
        StackerCraneBlockEntity dock = inFront != null && inFront.facing() == facing ? inFront : null;
        // A dock facing another way belongs to no controller on this side. It is told apart from "no dock at all",
        // because otherwise the goggles say "no stacker crane in front" while the player looks straight at one.
        dockMisaligned = inFront != null && dock == null;
        if (dock == null) {
            unlinkDock();
            applyLayout(null);
            return;
        }
        if (linkedDock != null && !linkedDock.equals(dockPos))
            unlinkDock();
        linkedDock = dockPos.immutable();
        dock.linkController(worldPosition);
        applyLayout(AisleLayout.of(dockPos, facing, dock.geometry()).withLetter(aisleLetter()));
        if (layout == null)
            return;
        if (pruneRequests(layout))
            setChanged();
        // A controller placed or loaded behind a busy crane adopts its job and rebuilds the reservations.
        dispatch.adopt(dock.currentJob());
    }

    private void applyLayout(@Nullable AisleLayout next) {
        AisleLayout previous = layout;
        status = statusOf(next);
        if (Objects.equals(previous, next)) {
            if (next != null)
                WarehouseRegistry.register(level, worldPosition, next); // idempotent; repairs a lost entry
            return;
        }
        layout = next;
        if (next == null) {
            WarehouseRegistry.unregister(level, worldPosition);
            clearAisleState(); // no aisle, no locations, no output stations to deliver to
        } else {
            WarehouseRegistry.register(level, worldPosition, next);
            if (previous == null || !previous.dock().equals(next.dock()) || previous.facing() != next.facing()) {
                // Records, counts and request destinations refer to the rack positions of another layout. Kept records
                // of the same kind at the same aisle-local position would keep another inventory's counts, so every
                // member joins again and is read.
                clearAisleState();
                membership.invalidateAll();
            } else if (!previous.geometry().equals(next.geometry()))
                membership.markAllDirty();
        }
        setChanged();
    }

    /**
     * Forgets everything that belongs to the aisle. The reservations go with it: a crane that is no longer this
     * controller's keeps its items and job and continues without controller (it holds items it cannot deliver).
     */
    private void clearAisleState() {
        membership.clear();
        stock.clear();
        pendingSnapshots.clear();
        filters.clear();
        sharedInventories.clear();
        requests.clear();
        productionOrders.clear();
        dispatch.reset();
    }

    private ControllerStatus statusOf(@Nullable AisleLayout layout) {
        if (layout == null)
            return dockMisaligned ? ControllerStatus.DOCK_MISALIGNED : ControllerStatus.NO_DOCK;
        return layout.geometry().length() == 0 ? ControllerStatus.NO_RAILS : ControllerStatus.READY;
    }

    /** The linked dock (if loaded) loses this controller as its owner; another controller's link is left alone. */
    private void unlinkDock() {
        BlockPos dockPos = linkedDock;
        linkedDock = null;
        if (dockPos != null && level != null && level.isLoaded(dockPos)
                && level.getBlockEntity(dockPos) instanceof StackerCraneBlockEntity dock)
            dock.unlinkController(worldPosition);
    }

    private void processMembership(AisleLayout current) {
        MembershipChanges changes = membership.reconcile(current.geometry(), rack -> probe(current, rack));
        boolean changed = pruneRequests(current);
        for (LocationRecord removed : changes.removed()) {
            if (removed.kind() == LocationKind.STORAGE)
                forgetStorageLocation(removed.position());
            else if (removed.kind() == LocationKind.OUTPUT)
                cancelRequestsFor(current.rackPos(removed.position())); // nothing can be delivered there any more
            else if (removed.kind() == LocationKind.PRODUCTION)
                cancelProductionOrdersAt(removed.position()); // its orders can never finish
            changed = true;
        }
        for (LocationRecord added : changes.added()) {
            changed = true;
            if (added.kind() != LocationKind.STORAGE)
                continue;
            // A new storage location is indexed empty at once and read within the next ticks (this tick first, at most
            // maxSnapshotsPerTick per tick); if its inventory is not loaded, the round robin reads it later.
            if (!stock.contains(added.position()))
                stock.restore(added.position(), Map.of());
            pendingSnapshots.addUrgent(added.position());
            // Until that snapshot runs, the planner must not read the missing filter entry as "accepts everything".
            filters.markUnread(added.position());
        }
        if (changed)
            setChanged();
    }

    private void forgetStorageLocation(RackPosition rack) {
        pendingSnapshots.remove(rack);
        filters.remove(rack);
        dispatch.forgetRefusals(rack);
        sharedInventories.remove(rack).ifPresent(promoted -> handOver(rack, promoted));
        stock.remove(rack);
    }

    private RackProbe probe(AisleLayout current, RackPosition rack) {
        BlockPos pos = current.rackPos(rack);
        if (!level.isLoaded(pos))
            return RackProbe.UNLOADED;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof WarehouseMember member) || blockEntity.isRemoved())
            return RackProbe.EMPTY;
        if (member.locationKind() == LocationKind.STORAGE && !(member instanceof StorageMember))
            return RackProbe.EMPTY; // a storage kind the controller cannot read
        // A member whose block state depends on where the aisle is (the terminal's intake port) adapts it first, so
        // this probe already classifies the corrected state. It writes only on a real change, and only when this
        // controller owns the member's block state, so two aisles sharing a rack plane cannot fight over it
        // (WarehouseMember §doc, WarehouseRegistry#ownsMemberState, M10 review fix).
        member.alignToAisle(worldPosition, current, rack.side());
        return member.isAlignedWith(current, rack.side()) ? RackProbe.member(member.locationKind())
                : RackProbe.MISALIGNED;
    }

    private void onAisleLetterChanged(int index) {
        if (level == null || level.isClientSide || layout == null)
            return;
        applyLayout(layout.withLetter(AisleLetterBehaviour.letterOf(index)));
    }

    // --- lifecycle -----------------------------------------------------------------------------------------------

    /** Registers the saved layout at once (also in chunks that do not tick) and re-links on the first tick. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel))
            return;
        if (layout != null)
            WarehouseRegistry.register(level, worldPosition, layout);
        relinkRequested = true;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() != before)
            relinkRequested = true; // rotated: another dock position
    }

    /** Real removal: the dock loses its controller link and the aisle leaves the registry. */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel) {
            unlinkDock();
            WarehouseRegistry.unregister(level, worldPosition);
        }
    }

    /**
     * Removal or chunk unload. The level is not touched here (it may be unloading); a dock that stays loaded notices a
     * lost controller in its own periodic check ({@code StackerCraneBlockEntity#validateControllerLink}).
     */
    @Override
    public void invalidate() {
        super.invalidate();
        if (level instanceof ServerLevel)
            WarehouseRegistry.unregister(level, worldPosition);
    }

    // --- goggles -------------------------------------------------------------------------------------------------

    /** A player looks at the controller through goggles (server): refresh the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        ControllerGoggleSummary next = createSummary();
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    private ControllerGoggleSummary createSummary() {
        int length = layout == null ? 0 : layout.geometry().length();
        int height = layout == null ? 0 : layout.geometry().height();
        return new ControllerGoggleSummary(status, length, height, membership.storageCount(), filteredLocationCount(),
                membership.inputCount(), membership.outputCount(), membership.productionCount(),
                membership.misalignedCount(), stock.distinctKeys(), stock.totalItems(), requests.openCount(),
                productionOrders.openCount(), linkedDockEntity().map(StackerCraneBlockEntity::goggleInfo),
                dispatch.lastReason());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        ControllerGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_CONTROLLER).forGoggles(tooltip);
        WareworksLang.aisleLetter(aisleLetter()).forGoggles(tooltip, 1);
        switch (shown.status()) {
            case READY -> WareworksLang.translate(WareworksLang.GOGGLES_STATUS_READY).style(ChatFormatting.GREEN)
                    .forGoggles(tooltip, 1);
            case NO_RAILS -> WareworksLang.translate(WareworksLang.GOGGLES_STATUS_NO_RAILS).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            case NO_DOCK -> WareworksLang.translate(WareworksLang.GOGGLES_STATUS_NO_DOCK).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            case DOCK_MISALIGNED -> WareworksLang.translate(WareworksLang.GOGGLES_STATUS_DOCK_MISALIGNED)
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        }
        // Without an aisle there is nothing to count: every line below would read 0.
        if (shown.status() == ControllerStatus.NO_DOCK || shown.status() == ControllerStatus.DOCK_MISALIGNED)
            return true;
        WareworksLang.aisleSize(shown.aisleLength(), shown.mastHeight()).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_STORAGE_LOCATIONS, shown.storageLocations())
                .forGoggles(tooltip, 1);
        if (shown.filteredLocations() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_FILTERED_LOCATIONS, shown.filteredLocations())
                    .forGoggles(tooltip, 2);
        WareworksLang.stationCounts(shown.inputs(), shown.outputs()).forGoggles(tooltip, 1);
        if (shown.productionStations() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_STATIONS, shown.productionStations())
                    .forGoggles(tooltip, 1);
        if (shown.misaligned() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_MISALIGNED_COUNT, shown.misaligned())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_ITEM_TYPES, shown.itemTypes()).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_ITEMS_STORED, shown.totalItems()).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_OPEN_REQUESTS, shown.openRequests()).forGoggles(tooltip, 1);
        if (shown.productionOrders() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_ORDERS, shown.productionOrders())
                    .forGoggles(tooltip, 1);
        shown.crane().ifPresent(crane -> {
            WareworksLang.translate(WareworksLang.GOGGLES_STACKER_CRANE).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
            crane.addGoggleLines(tooltip, 2, false);
        });
        shown.lastPlanReason().ifPresent(reason -> WareworksLang.lastPlan(reason).forGoggles(tooltip, 1));
        return true;
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            // Clients only need the bounded goggle summary; records and stock stay on the server.
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            return;
        }
        ControllerPersistence.writeLayout(tag, layout);
        ControllerPersistence.writeLocations(tag, membership, stock.readOnlyView(), registries);
        ControllerPersistence.writeRequests(tag, requests.requests(), worldPosition, registries);
        ControllerPersistence.writeProductionOrders(tag, productionOrders.all(), registries);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        aisleLetter.value = Mth.clamp(aisleLetter.value, AisleLetterBehaviour.FIRST_INDEX, AisleLetterBehaviour.LAST_INDEX);
        if (clientPacket) {
            summary = ControllerGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        Direction facing = facing();
        // Records are aisle-local: a layout saved for another direction (e.g. a rotated structure) is dropped.
        layout = ControllerPersistence.readLayout(tag)
                .filter(saved -> saved.facing() == facing)
                .map(saved -> AisleLayout.of(worldPosition.relative(facing), facing, saved.geometry())
                        .withLetter(aisleLetter()))
                .orElse(null);
        status = statusOf(layout);
        clearAisleState();
        if (layout != null) {
            ControllerPersistence.SavedLocations saved = ControllerPersistence.readLocations(tag, registries);
            membership.restore(saved.records(), saved.misaligned());
            for (LocationRecord record : membership.records(LocationKind.STORAGE)) {
                stock.restore(record.position(), saved.stock().getOrDefault(record.position(), Map.of()));
                // Restored counts are unverified: inventories may have changed while unloaded, and a mirrored structure
                // keeps the facing but swaps the rack sides. Background snapshots verify them.
                pendingSnapshots.addBackground(record.position());
                // Filters are not saved either, and planning starts before the queue above is drained. Marking them
                // unread keeps the planner from reading "no entry" as "accepts everything" (AisleFilters, ADR-021).
                filters.markUnread(record.position());
            }
            // Requests belong to the saved layout; without it (or for another facing) they are dropped like the records.
            requests.restore(ControllerPersistence.readRequests(tag, worldPosition, registries));
            // Production orders belong to the saved layout like the requests do. Their deadlines are not saved, so
            // the first tick gives every restored order its full timeout again (§3.5).
            productionOrders.restore(ControllerPersistence.readProductionOrders(tag, registries));
            productionDeadlinesPending = true;
        }
        relinkRequested = true;
        if (level instanceof ServerLevel && !isRemoved()) {
            // Data changed on a live block entity (e.g. /data merge): keep the registry in step.
            if (layout != null)
                WarehouseRegistry.register(level, worldPosition, layout);
            else
                WarehouseRegistry.unregister(level, worldPosition);
        }
    }
}
