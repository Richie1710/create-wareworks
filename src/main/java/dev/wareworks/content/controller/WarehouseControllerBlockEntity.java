package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
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
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.HomePointStatus;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.AbortReason;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.HomeReturn;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.SharedInventories;
import dev.wareworks.core.inventory.SnapshotQueue;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.FilterMatch;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RerouteTarget;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.production.PlanBudget;
import dev.wareworks.core.production.PlanNode;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProduciblePlanner;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionOrders;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.ProductionPlan;
import dev.wareworks.core.production.ProductionPlanInput;
import dev.wareworks.core.production.ProductionPlanResult;
import dev.wareworks.core.production.ProductionPlanner;
import dev.wareworks.core.production.StationPattern;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.RestockDecision;
import dev.wareworks.core.stock.RestockInput;
import dev.wareworks.core.stock.RestockLimits;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.RestockPlan;
import dev.wareworks.core.stock.RestockPlanner;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockAvailability;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleEvaluation;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.stock.StockRules;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.core.warehouse.AisleMembership;
import dev.wareworks.core.warehouse.AisleName;
import dev.wareworks.core.warehouse.AisleNames;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.MembershipChanges;
import dev.wareworks.core.warehouse.RackProbe;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.core.warehouse.SnapshotCadence;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.Headings;
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
 * Block entity of the warehouse controller: the logical state of one warehouse ({@code docs/warehouse-system.md}
 * §3.3-§8). It plans and counts, but never moves items.
 * <p>
 * <b>Layout.</b> The controller links to the stacker crane dock directly in front of it ({@code pos + FACING}) if that
 * dock faces the same direction, and builds a {@link WarehouseLayout} from the rail network that dock discovered: one
 * {@link BranchLayout} per straight aisle, the first of them at the dock under the controller's own aisle letter
 * ("Aisle" value box, A-Z) and every further one under the letter its line of rails has pinned ({@link BranchTable}).
 * A warehouse that never bends is a network of exactly one aisle, which is what every warehouse up to 0.5.0 is. The
 * layout is registered in the {@link WarehouseRegistry}. Re-linking runs on the next tick
 * after a hint (block update in front, dock geometry change, dock load or removal, own rotation) and at the latest every
 * {@code geometryRefreshTicks}; it costs one block entity lookup. While the dock position is not loaded, the known
 * layout is kept, and so are its aisles whenever a scan ran into a chunk that is not loaded. A changed dock position
 * or aisle direction rebuilds records, counts and requests from scratch; an aisle that moved its origin or turned
 * round has everything remapped through the world positions its labels stand for ({@code remapState}, ADR-033).
 * <p>
 * <b>Membership.</b> An {@link AisleMembership} of rack positions: members notify through the registry, which marks
 * only their position dirty; geometry changes mark all positions dirty. Dirty positions are probed on the next tick,
 * only in loaded chunks; records in unloaded chunks are kept.
 * <p>
 * <b>Stock.</b> A {@link StockIndex} over the storage locations, updated incrementally. Locations wait in a
 * {@link SnapshotQueue} and at most {@code maxSnapshotsPerTick} of them are read per tick: urgently when they join or
 * when their interface reports a content change ({@link WarehouseRegistry#contentChanged}), in the background when
 * their counts were restored from a save. On top, every {@code snapshotIntervalTicks} as many storage locations are
 * re-read round robin as {@link SnapshotCadence} asks for (inventories that change silently) — one up to about 1200
 * locations, which is every warehouse at the default aisle limits, and at most {@code maxSnapshotsPerTick} above that,
 * read directly rather than through the queue — and {@link #refreshLocation} re-reads one on demand (after every crane
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
 * <b>Per tick (server)</b>: at most a re-link check, the dirty probes, the bounded snapshot queue, the round-robin
 * snapshots {@link SnapshotCadence} asks for and a dispatch attempt; nothing else. The two snapshot budgets are
 * separate, so in the one tick per interval the round robin falls on, a warehouse of more than about 1200 locations may
 * read up to <b>twice</b> {@code maxSnapshotsPerTick} inventories — which is why that ceiling is the change queue's own
 * budget rather than a number of its own (M22).
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
     * How often a <b>holding</b> aisle re-decides its hold without an event (M19, issue #10). The hooks below drive every
     * real transition; this is the bounded safety net that makes the release independent of every one of them being
     * perfect, and it is what makes a changed server config, a freed level slot or a work change nobody hinted at take
     * effect. An aisle that holds nothing schedules no re-check at all.
     */
    private static final int CHUNK_KEEP_RECHECK_TICKS = 20;
    /** The footprint of an aisle that has none (no dock). */
    private static final int[] EMPTY_FOOTPRINT = new int[0];
    /**
     * Terminals of one warehouse that may work a clipboard order off at the same time (M23, issue #19). It bounds what
     * one controller's list walk can ever cost; a terminal beyond it simply waits for one of the others to finish, and
     * its order is untouched.
     */
    private static final int MAX_LIST_TERMINALS = 64;
    /**
     * How often the controller looks in on its list orders. Each order then decides for itself whether a top-up pass
     * is due ({@code ListOrder#due}, on {@code terminalListIntervalTicks}), so this is only the resolution at which a
     * freed buffer slot restarts a waiting list — not how often anything is measured.
     */
    private static final int LIST_WALK_INTERVAL_TICKS = 5;

    /**
     * "Aisle" value box. Assigned in {@link #addBehaviours}, which {@code SmartBlockEntity} calls from its constructor, so
     * this field must not have an initializer (it would reset the behaviour to {@code null}).
     */
    protected AisleLetterBehaviour aisleLetter;

    private final AisleMembership membership = new AisleMembership();
    private final StockIndex<ItemKey, RackPosition> stock = new StockIndex<>(RackPosition.ORDER);
    /**
     * The <b>parallel</b> fluid stock index: fluid key → storage location → millibuckets (M30, issue #21, D10).
     * <p>
     * A second index and never a union key with {@link #stock}, for three reasons that each stand alone.
     * {@code StockView#totalItems()} and {@code distinctKeys()} feed the {@code ITEM_TYPES} and {@code TOTAL_ITEMS}
     * goggle lines and the display board's own rows, and summing millibuckets into an item count corrupts every one of
     * them. {@code countsAt(location)} is part of the controller's <b>save</b> format, so a union key would be a
     * migration for every existing world. And {@code locationsOf(key)} is {@link ItemKey}-typed end to end through
     * {@code PlannerInput}.
     * <p>
     * <b>Not saved</b>, unlike the item index, and that is a property of where fluid lives rather than an omission: a
     * fluid bay <b>is</b> its own tank ({@code StorageMember#attachedPos()} is the bay's own position), so its contents
     * are in its own block entity and cannot be read without it being loaded. There is nothing a save could add that
     * the first {@link #refreshLocation} of a loaded bay does not bring back, and every bay is read within one
     * snapshot cycle after a load. The item index is saved for the opposite reason: a chest can stay unloaded while the
     * interface in front of it is not.
     * <p>
     * It is written on exactly one path, {@link #refreshLocation}, and dropped with the location in
     * {@link #forgetStorageLocation} and {@link #clearAisleState}. A bay reports every change of its contents through
     * {@code WarehouseRegistry.contentChanged}, so the entry is normally one tick old.
     * <p>
     * It holds the warehouse's <b>fluid bays and nothing else</b>: a storage location that holds no fluid is removed
     * rather than kept with no counts, which is the other way round from {@link #stock}, where every storage location
     * stays indexed so that {@code occupiedLocations()} can compare the ones in use against the ones counted.
     */
    private final StockIndex<FluidKey, RackPosition> fluidStock = new StockIndex<>(RackPosition.ORDER);
    /** Storage locations waiting for a snapshot; not saved (rebuilt from the records on load). */
    private final SnapshotQueue<RackPosition> pendingSnapshots = new SnapshotQueue<>();
    /** Storage locations reading the same inventory; derived from snapshots, not saved. */
    private final SharedInventories<RackPosition, Object> sharedInventories = new SharedInventories<>();
    /** Open retrieval requests; destinations are world positions of output stations. Saved. */
    private final RequestQueue<ItemKey, BlockPos> requests = new RequestQueue<>(configuredMaxOpenRequests());
    /** Store filters of the storage locations, cached for planning; read from the interfaces, not saved. */
    private final AisleFilters filters = new AisleFilters();
    /**
     * The one-shot resolve {@link #storeFilterMatch} and {@link #storePriorityAt} share, held once instead of captured
     * per call: filter and priority of a never-read location come from the same lookup ({@link #readStoreSettingsAt}),
     * and {@link AisleFilters} never calls it once the cache is warm.
     */
    private final Function<RackPosition, Optional<AisleFilters.StoreSettings>> storeSettingsResolver =
            this::readStoreSettingsAt;
    /**
     * The commitment test {@link AisleFilters#match} applies to a location that holds one item type only (M28), held
     * once beside the resolver for the same reason: {@link #storeFilterMatch} runs per storage candidate per planning
     * run, and a warehouse without such a location never calls it at all.
     */
    private final BiPredicate<RackPosition, ItemKey> storeCommitment = this::committedTo;
    /** Port policies of the aisle's warehouse ports, cached for the continuous pass and for planning (M17); not saved. */
    private final AislePorts ports = new AislePorts();
    /**
     * What the inventories behind the aisle's <b>collecting</b> ports held at their last read (M18, issue #13); not saved.
     * Deliberately not part of the stock index: collected items are not stock until they are stored.
     */
    private final AisleCollections collections = new AisleCollections();
    /**
     * Collecting ports waiting for a read of their attached inventory; not saved. A second queue rather than entries in
     * {@link #pendingSnapshots}, because the two read different blocks and answer different questions — but both are
     * drained in one loop under the one shared {@code maxSnapshotsPerTick} budget, so the cost bound of §5 is unchanged.
     */
    private final SnapshotQueue<RackPosition> pendingCollections = new SnapshotQueue<>();
    /** The aisle's stock rules, copied from its warehouse stock keepers (M15, issue #3). <b>Saved</b>, see below. */
    private final AisleStockRules stockRules = new AisleStockRules();
    /**
     * The rules the <b>safety stop</b> is holding, by item (M15 part 2, issue #3). At most one rule governs an item,
     * so the item is the rule's identity here, and a pause survives a rule being edited into another keeper row.
     * <p>
     * <b>Saved</b>, for the same reason the rule copy is: it has to be known before the first evaluation after a world
     * load, or a restart would quietly resume ordering into a machine that already swallowed a batch
     * ({@link StockRulePause}).
     */
    private final Map<ItemKey, StockRulePause> stockPauses = new LinkedHashMap<>();
    /**
     * The production stations of this aisle whose own block is currently showing the safety stop
     * ({@code WarehouseProductionBlock#STOPPED}, M20), by world position. Derived and never saved.
     * <p>
     * It is a <b>set</b> rather than a flag for one reason: a lamp lives in a block state, which survives every save,
     * so a station that leaves this warehouse — broken, turned away from the aisle, or its pattern deleted — would
     * otherwise keep burning for a warehouse that is not watching it any more. The next pass walks what it lit last
     * time and puts out whatever it did not light again, which is the counterpart M15 had to add for the stock
     * keeper's comparator ({@link #clearKeeperRuleState}). It also keeps a warehouse without a pause from resolving a
     * single station.
     */
    private final Set<BlockPos> stoppedStations = new LinkedHashSet<>();
    /**
     * Whether one pass has looked at <b>every</b> production station of this aisle since this controller was loaded, so
     * {@link #stoppedStations} may be trusted to know about every lit lamp (M20 review fix).
     * <p>
     * A lamp lives in a block state and therefore survives every save, while the set above does not: after a load the set
     * is empty and a station's lamp may well be burning. Without this flag the cheap guard of
     * {@link #refreshProductionStops} ("no pause and nothing lit, so nothing to do") would read that empty set as proof
     * and return for ever, leaving a red light nothing can put out — and the same would happen to a station that was
     * unloaded during a pass while the last pause was lifted. One sweep per load costs one block entity lookup per
     * production station, once; afterwards a warehouse that has never lost a batch pays nothing again. A pass that could
     * not reach every station does not set it, so the next one tries again.
     */
    private boolean stopsSwept;
    /** Production orders of this aisle ({@code docs/warehouse-system.md} §3.5, ADR-024). Saved. */
    private final ProductionOrders<ItemKey, RackPosition> productionOrders =
            new ProductionOrders<>(configuredMaxProductionOrders());
    /** Job planning and reservations; derived from the crane's job, not saved. */
    private final CraneDispatch dispatch = new CraneDispatch(this);
    /**
     * The whole warehouse — every straight aisle of the connected rail network, with their letters; {@code null}
     * without dock. Saved (ADR-033).
     * <p>
     * A warehouse that never bends is a network of exactly one branch, so this is literally the single aisle every
     * version up to 0.5.0 held: the same rack positions, the same addresses, the same containment. The branches are
     * rediscovered from the rails on every re-link; what is saved is what lets a reload answer before the first scan
     * and what the remap compares a new shape against.
     */
    @Nullable
    private WarehouseLayout layout;
    /** The aisle letters and origin ends pinned to the lines of rails ({@link BranchTable}). Saved with the network. */
    private final BranchTable branchTable = new BranchTable();
    /**
     * The names a player has given this warehouse's aisles (M25, issue #15, ADR-038). Saved in its own top-level tag
     * ({@link ControllerPersistence#NAMES_TAG}).
     * <p>
     * <b>Written on a player's click and never otherwise.</b> There is no tick path, no derivation and no scan: a
     * naming click calls {@link #setAisleName} or {@link #clearAisleName}, which saves and syncs once, and a changed
     * aisle letter carries the name with it ({@link #onAisleLetterChanged}). That is the whole write surface, which is
     * also why a label does not flicker when a chunk unloads or a dock is rebuilt — the names outlive the warehouse
     * they decorate and are lost only with the controller itself.
     */
    private final AisleNames names = new AisleNames();
    /**
     * The aisle letter the value box carried before the change being handled, so {@link #onAisleLetterChanged} can
     * move a name off it <b>whether or not this controller has a warehouse right now</b> (M25 review fix).
     * <p>
     * Not saved, and it does not have to be: the value box's own value is, and this is read back from it in
     * {@link #read}. {@code ScrollValueBehaviour#read} assigns its field directly without the callback, so a load can
     * never be mistaken for a scroll.
     */
    private char carriedAisleLetter = StorageAddress.FIRST_AISLE;
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
    private long nextStockRuleTick;
    private long nextPortTick;
    private long nextListTick;
    /**
     * The terminals of this warehouse that are working a clipboard order off (M23, issue #19, ADR-036).
     * <p>
     * <b>Nothing of a list order is saved here.</b> The order itself lives in its terminal's own block entity, where
     * the clipboard it belongs to lies in a slot; this set is only the controller's note of <i>which</i> terminals are
     * worth a call, so that a warehouse without a list order costs nothing per tick and one with a list order does not
     * resolve every output station's block entity to find it. It is rebuilt from the world whenever
     * {@link #listTerminalsDirty} says so — on every membership change, which is also what a terminal's chunk load
     * fires ({@link #onMemberChanged}) — and an entry whose terminal is gone, unloaded or finished drops out of it on
     * the next walk. A set that is wrong is therefore self-healing and can lose no order.
     */
    private final Set<BlockPos> listTerminals = new LinkedHashSet<>();
    private boolean listTerminalsDirty = true;
    /**
     * Every keeper of the aisle is re-read on the next tick. Set on load, after a layout change and on every re-link
     * check, so a keeper edited while this controller was unloaded is picked up at the latest one
     * {@code geometryRefreshTicks} later. Single edits do not go through it: they are applied at once
     * ({@link #onStockRulesChanged}).
     */
    private boolean stockRulesRefreshPending = true;
    /**
     * The home points of this warehouse are judged again on the next tick (M21, ADR-034). Set on load, after a layout
     * change, on every re-link check and whenever a home point joins or leaves, so a lamp is never more than one
     * {@code geometryRefreshTicks} behind the truth.
     */
    private boolean homePointsRefreshPending = true;
    /**
     * The rack position of the home point this warehouse's crane really waits at, or empty while the dock is home.
     * Derived from the membership records and never saved: the records are, and the first tick after a load decides it
     * again.
     */
    private Optional<RackPosition> servingHomePoint = Optional.empty();
    /**
     * The <b>world positions</b> of the home points this controller last wrote a lamp to, so it can switch them off
     * again — including the ones whose aisle a player has just broken away.
     * <p>
     * World positions on purpose, not rack positions: a label means a block only through the layout it belongs to, and
     * the moment that aisle is gone the label names nothing, which is exactly how a stock keeper's lamp was left
     * burning for ever (M21 review). A block position never stops meaning the block it meant.
     */
    private final Set<BlockPos> writtenHomePoints = new HashSet<>();
    /**
     * A restore is waiting to be finished on the first tick that knows the game time: restored orders have no deadline
     * yet, and a saved production plan has not been checked yet ({@code ProductionOrders#validatePlans}).
     */
    private boolean productionRestorePending;
    /** Rate limit for the "could not read storage location" line: a second, different inventory stays reportable. */
    private final LogThrottle snapshotFailures = new LogThrottle();
    /**
     * Result items credited to production orders through the <b>arrival</b> channel since the last observation of the
     * stock level, by item ({@link #onResultStored}, M15 part 2).
     * <p>
     * The same items are in the stock index too, so the level-based channel has to leave them out or an order would be
     * credited twice for one physical batch ({@link ProductionOrders#observeResult(Object, long, long, long, long)}).
     * Derived, cleared by every observation pass and never saved: it only ever holds one tick's worth of arrivals.
     */
    private final Map<ItemKey, Long> storedCredits = new HashMap<>();

    // --- goggles ---
    private ControllerGoggleSummary summary = ControllerGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);
    /**
     * What the aisle's stock rules are doing, for the controller's goggle lines (M15, issue #3): how many rules govern
     * an item, how many of them call for it and how many of them stop it from being stored.
     * <p>
     * Derived and <b>not</b> saved, refreshed by the rule tick ({@link #tickStockKeepers}) rather than while a summary
     * is built: a goggle summary is rebuilt on every observation, and reading three counters per rule per tick for as
     * long as a player looks at the block is work nobody asked for. Enforcement never reads these numbers.
     */
    private int governingRules;
    private int rulesBelowMinimum;
    private int rulesAtMaximum;
    /**
     * The aisle's rules with their status and levels, computed at most once per tick ({@link #stockRuleEvaluations()}).
     * Display state for the lamps, the comparators, both goggle surfaces and an open keeper screen; never read by
     * anything that moves an item.
     */
    private List<StockRuleEvaluation<ItemKey>> ruleEvaluations = List.of();
    private long ruleEvaluationTick = Long.MIN_VALUE;
    /** The rule set {@link #ruleEvaluations} was computed from, compared by identity so an edit invalidates it. */
    @Nullable
    private StockRules<ItemKey> ruleEvaluationOf;
    /**
     * What automatic restocking last decided about each item, by item (M15 part 2, issue #3) — the overlay every
     * surface reads on top of the three numbers ({@link RestockOutcome#refine}).
     * <p>
     * Derived and <b>not</b> saved: it is recomputed by the restock pass every {@code stockRuleIntervalTicks}, and an
     * outcome that is out of date for one second says nothing a player acts on. Only outcomes that really say
     * something are kept, so a warehouse whose rules are all satisfied holds an empty map and refines nothing. The
     * map instance is replaced only when the outcomes really changed, which is what {@link #ruleEvaluations} compares
     * against.
     */
    private Map<ItemKey, RestockOutcome> restockOutcomes = Map.of();
    /**
     * The ingredient a waiting rule needs a player to supply, by item (M15 part 2, issue #3): the other half of
     * {@code RestockDecision}, kept because it is the one thing a player can act on and the planner is the only place
     * that knows it ({@link RestockOutcome#WAITING_FOR_INGREDIENTS}).
     * <p>
     * Derived and not saved, like {@link #restockOutcomes}, and only filled for the rules that really wait — which is
     * why it is a second map rather than a field on every outcome.
     */
    private Map<ItemKey, ItemKey> restockMissing = Map.of();
    /** The outcomes {@link #ruleEvaluations} was computed with, by identity (see {@link #restockOutcomes}). */
    private Map<ItemKey, RestockOutcome> ruleEvaluationRestock = Map.of();

    // --- chunk loading (M19, issue #10, ADR-031); server only, and only the give-up bound is saved ------------------
    /**
     * Re-decide the chunk hold on the next tick. Set from every place that changes what the aisle has to do (a job, a
     * request, an order, a collecting port), from every place that changes the aisle itself, and from {@link #onLoad()}.
     * <p>
     * <b>Flag and defer</b> is the whole point ({@code AisleChunkTickets}): taking a ticket loads a chunk synchronously,
     * which loads block entities, which would take tickets — re-entrantly, and from inside
     * {@code Level#tickBlockEntities}' fresh-block-entity pass in the case of {@code onLoad()}. Only {@link #tick()} ever
     * calls into NeoForge.
     */
    private boolean chunkKeepDirty = true;
    /**
     * Game tick at which the hold must be re-decided without any event ({@link ChunkKeepDecision#NO_RECHECK} while
     * nothing is pending): the linger and the give-up deadline, and while holding a bounded safety re-check. An aisle that
     * holds nothing schedules nothing at all, so a server with the feature off pays one boolean and one long compare per
     * controller tick. A <b>refused</b> aisle therefore waits for an event too, and is woken by
     * {@code AisleChunkTickets#wakeRefused} when a slot of its dimension frees up.
     */
    private long chunkKeepRecheckTick = ChunkKeepDecision.NO_RECHECK;
    /** When the last work disappeared, for the release linger; {@link ChunkKeepDecision#NOT_SET} while there is work. */
    private long chunkKeepIdleSince = ChunkKeepDecision.NOT_SET;
    /** What the goggles say about the hold. Derived, synced inside {@link ControllerGoggleSummary}. */
    private ChunkKeepReason chunkKeepReason = ChunkKeepReason.NONE;
    /** Chunks held right now, or (while refused) how many the footprint would need. */
    private int chunkKeepChunks;
    /**
     * The hold gave up on this work and must not be taken again until the work really changed — or disappeared, which is
     * the escape hatch. <b>Saved</b> together with {@link #chunkKeepFingerprint} ({@code ControllerPersistence}): the work
     * it refuses is saved, so a flag that lived only as long as this instance would let every reload and every restart
     * take the whole footprint again for work that had already proved unservable (M19 review).
     */
    private boolean chunkKeepGaveUp;
    /** Which work {@link #chunkKeepGaveUp} was decided on: the job, request and order ids ({@link #chunkWorkFingerprint}). */
    private long chunkKeepFingerprint;
    /** The config generation this controller last decided with, so a config reload takes effect within one tick. */
    private int chunkKeepConfigGeneration = Integer.MIN_VALUE;
    /** The aisle's chunk footprint, cached per layout instance ({@link AisleChunkSpan}); recomputed nowhere else. */
    private int[] chunkFootprintCache = EMPTY_FOOTPRINT;
    @Nullable
    private WarehouseLayout chunkFootprintOf;
    /** Rate limit for the "cannot hold chunks" line, so a permanently capped aisle does not fill the log. */
    private final LogThrottle chunkKeepRefusals = new LogThrottle();

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

    /** The name a player gave the aisle with this letter (M25, issue #15); empty when it has none. Server. */
    public Optional<String> aisleName(char aisle) {
        return names.nameOf(aisle);
    }

    /** Every named aisle of this warehouse, in letter order; an unmodifiable snapshot. Server. */
    public SortedMap<Character, String> aisleNames() {
        return names.entries();
    }

    /** Whether no aisle of this warehouse has a name — the case every surface has to cost nothing for. Server. */
    public boolean hasNoAisleNames() {
        return names.isEmpty();
    }

    /**
     * The names of the aisles this warehouse <b>really has</b>, in letter order; what the display board shows
     * (M25, issue #15, ADR-038). Empty without a warehouse. Server.
     * <p>
     * Filtered by the warehouse's own letters, unlike {@link #aisleNames()}, which is the whole saved table. A name
     * can outlive the aisle it was given to — an aisle torn down leaves its label behind on purpose, so that rebuilding
     * it brings the label back — and a board is a surface that names rows of <i>this</i> warehouse, so a label with no
     * aisle behind it must not stand on one. The controller's goggles filter it the same way for free, because they
     * list the aisles themselves and read the name per aisle.
     */
    public SortedMap<Character, String> namedAisles() {
        if (names.isEmpty())
            return Collections.emptySortedMap();
        WarehouseLayout shown = layout;
        if (shown == null)
            return Collections.emptySortedMap();
        SortedMap<Character, String> named = new TreeMap<>();
        String letters = shown.branchLetters();
        for (int aisle = 0; aisle < letters.length(); aisle++) {
            char letter = letters.charAt(aisle);
            names.nameOf(letter).ifPresent(name -> named.put(letter, name));
        }
        return Collections.unmodifiableSortedMap(named);
    }

    /**
     * Names the aisle with this letter, as a player's click does ({@link AisleNaming}): {@code raw} goes through
     * {@link AisleName#sanitize}, and a text that sanitises to blank clears the name rather than storing an invisible
     * one. Saves and syncs once, and only when something really changed.
     *
     * @return the name as it is now stored, or {@link AisleName#NONE} when the aisle ends up unnamed
     */
    public String setAisleName(char aisle, @Nullable String raw) {
        String name = AisleName.sanitize(raw);
        if (names.nameOf(aisle).orElse(AisleName.NONE).equals(name))
            return name;
        if (!names.set(aisle, name))
            return AisleName.NONE; // not an aisle letter: nothing to name
        nameChanged();
        return name;
    }

    /**
     * Takes the name off the aisle with this letter.
     *
     * @return whether there was a name to take off, so a caller can leave an already-unnamed aisle unmentioned
     */
    public boolean clearAisleName(char aisle) {
        if (!names.clear(aisle))
            return false;
        nameChanged();
        return true;
    }

    /**
     * The one write path of the name table: save it and let the clients that draw it know.
     * <p>
     * The goggle summary is <b>rebuilt here</b>, not only in {@link #onGoggleObserved()}. That method is the only
     * other place that calls {@link #createSummary()}, so without this the packet a naming click sends would carry
     * the summary from the last time somebody looked at the block — and the new line would wait for the next look
     * instead of appearing on the click that caused it. It costs a handful of field reads, once per player click
     * (ADR-026), and it is what makes a name visible at the moment a player gives it, including on a controller a
     * player is looking at while naming an aisle through one of its interfaces.
     */
    private void nameChanged() {
        setChanged();
        if (level != null && !level.isClientSide && !isVirtual() && !isRemoved())
            summary = createSummary();
        sendData();
    }

    public ControllerStatus status() {
        return status;
    }

    /**
     * The aisle at the dock with its letter; empty without dock (and on clients, which receive only the goggle
     * summary). This is the whole warehouse of every build that never bends; {@link #warehouse()} answers for all of
     * its aisles.
     */
    public Optional<BranchLayout> layout() {
        return layout == null ? Optional.empty() : Optional.of(layout.firstBranch());
    }

    /** The whole warehouse — every aisle of the rail network; empty without dock and on clients. */
    public Optional<WarehouseLayout> warehouse() {
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
     * queued for its snapshot) is resolved once through {@link #readStoreSettingsAt} instead of being treated as
     * unfiltered — see {@link AisleFilters} for why that matters after every world load.
     * <p>
     * <b>One function for storage locations and accepting warehouse ports alike</b> (M17, issue #12): the planner asks
     * the same {@code storeFilter} about both, and the cache that knows the answer is asked. A port's filter is one
     * concrete item, so {@link AislePorts#filterMatch} is an equality test and answers {@code null} for everything that is
     * no cached port — one lookup in a map that is empty for a warehouse without them.
     * <p>
     * <b>It is also the only gate for the two store rules a location reports itself</b> (M28): a location that accepts
     * no storing, and a location that holds one item type only and is not committed to this one, answer
     * {@link FilterMatch#REJECTED} here. That puts them exactly where a store filter already decides — before the
     * capacity estimate, before any live simulation and before a refusal could be remembered — so the planner needs no
     * change and a {@code RETRIEVE} reroute still ranks such a location last instead of dropping it (§8).
     */
    FilterMatch storeFilterMatch(RackPosition rack, ItemKey key) {
        if (level == null)
            return FilterMatch.UNFILTERED;
        FilterMatch port = ports.filterMatch(rack, key);
        return port != null ? port : filters.match(level, rack, key, storeSettingsResolver, storeCommitment);
    }

    /**
     * Whether the storage location at {@code rack} holds, and awaits, nothing but {@code key} — the test behind
     * {@link StorageMember#holdsOneTypeOnly()}, asked by {@link AisleFilters#match} for such a location only.
     * <p>
     * <b>The reservations are half of the answer, and the half that is easy to forget.</b> The stock index alone says
     * what is <i>inside</i> the location; it says nothing about the items a crane is already carrying towards it. An
     * empty one-type location with a cobblestone job in flight would otherwise accept a dirt job planned in the same
     * window, and two item types would be on their way into a location whose whole premise is one. Nothing is lost when
     * that happens — the second delivery is rerouted (§8) — but it is a wasted trip and an inexplicable one, so the
     * ledger is consulted beside the index: the location may reserve capacity for {@code key} and for nothing else.
     * <p>
     * Both reads are the ones the planner already does per candidate ({@code ReservationView#reservedCapacity}), and a
     * one-type location holds at most one item type by construction, so this is O(1) for the only locations that ask it.
     */
    private boolean committedTo(RackPosition rack, ItemKey key) {
        for (ItemKey stored : stock.countsAt(rack).keySet()) {
            if (!stored.equals(key))
                return false;
        }
        ReservationView<ItemKey, RackPosition> reserved = dispatch.reservations();
        return reserved.reservedCapacity(rack) == reserved.reservedCapacity(rack, key);
    }

    /**
     * The storage priority of the storage location at {@code rack} ({@code docs/warehouse-system.md} §3.1, ADR-028,
     * M16): the planner's {@code storePriority}, higher fills first. One map lookup.
     * <p>
     * Like {@link #storeFilterMatch} it resolves a location this controller has not read yet through
     * {@link #readStoreSettingsAt} — the <b>same</b> one-shot resolve, down to the same {@link #storeSettingsResolver}
     * instance, which reads filter and priority together, so the first plan after a world load already stores into the
     * preferred rack instead of the nearest one.
     */
    int storePriorityAt(RackPosition rack) {
        return level == null ? AisleFilters.NO_PRIORITY : filters.priorityOf(rack, storeSettingsResolver);
    }

    /**
     * Whether the storage location at {@code rack} takes <b>filled containers</b> rather than items — a fluid bay
     * (M30 step 9, issue #21, D6). From the same cache and the same one-shot resolve as the store filter and the
     * priority, so it is one hash map read per candidate ({@link AisleFilters#takesFluidContainers}).
     * <p>
     * It is read by {@code CraneDispatch} twice and nowhere else. Once as the <b>insert estimate</b>: a fluid bay's
     * snapshot has zero slots, so the snapshot estimate answers 0 and the planner's capacity gate would drop the bay
     * before any live call. That gate must keep dropping a warehouse interface whose chest was taken away, which
     * reports the <i>same</i> empty snapshot for the opposite reason, so the estimate asks this positive question
     * instead of reading anything into an empty snapshot. And once as {@code PlannerInput#allOrNothing}, the rule that
     * keeps a <b>reroute</b> from offering a bay part of a carry it would refuse whole (M30 review fix).
     */
    boolean takesFluidContainers(RackPosition rack) {
        return level != null && filters.takesFluidContainers(rack, storeSettingsResolver);
    }

    /**
     * Reads the store settings of {@code rack} straight from its member — filter, priority, the two store rules of M28
     * and the fluid dedication of M30 ({@link AisleFilters#read}) — for the one lookup {@link AisleFilters} does per
     * location that was never read into the cache. All of them in one lookup on purpose: the planner may ask for any of
     * them first, and a setting the controller has not read must never be guessed. Empty while the position is not
     * loaded or holds no storage member.
     */
    private Optional<AisleFilters.StoreSettings> readStoreSettingsAt(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return Optional.empty();
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageMember member) || blockEntity.isRemoved())
            return Optional.empty();
        return Optional.of(AisleFilters.read(member));
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

    /**
     * Storage locations of this aisle that carry a storage <b>priority</b> that applies (goggle summary, M16, ADR-028).
     * Shared-inventory aliases are excluded for the same reason as for the filters: a priority on the other half of a
     * double chest can do nothing.
     */
    public int prioritisedLocationCount() {
        return filters.prioritisedCount(rack -> !sharedInventories.isAlias(rack));
    }

    /** Whether the storage location at {@code rack} carries a store filter the planner actually consults. */
    public boolean isStorageFiltered(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return !sharedInventories.isAlias(rack) && filters.isFiltered(rack);
    }

    /** Whether the storage location at {@code rack} carries a storage priority the planner actually consults (M16). */
    public boolean isStoragePrioritised(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return !sharedInventories.isAlias(rack) && filters.isPrioritised(rack);
    }

    /**
     * Whether the storage location at {@code rack} carries a store filter or a storage priority that has <b>no
     * effect</b>, because another location counts the inventory it reads (double chest, item vault) and the planner only
     * asks that one ({@code docs/warehouse-system.md} §3.1.1). Its interface shows this as a goggle hint, so a player can
     * see which of two interfaces on one inventory is the effective one.
     * <p>
     * A <b>priority</b> on an alias is included (M16): it is as dead as a filter there, and which of two interfaces on
     * one inventory is canonical depends on the order {@code SharedInventories.assign} saw them, so the hint is the only
     * way to tell.
     */
    public boolean isStorageFilterShadowed(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        return sharedInventories.isAlias(rack) && (filters.isFiltered(rack) || filters.isPrioritised(rack));
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

    /**
     * Read-only view of the <b>fluid</b> stock index (fluid key → fluid bay → millibuckets, M30, issue #21, D10):
     * what the controller's goggles, the "Fluid Stock" display source and the warehouse summary's fluid line read.
     * <p>
     * Counted in millibuckets and shown in <b>buckets</b> (D9): a bottle is 250 mB, which buckets cannot express,
     * and a brass bay holds 256 000 mB, which nobody reads. Every surface converts at the edge, with Create's own
     * unit keys, so no reader of this view has to know which way round it is.
     * <p>
     * Empty for every warehouse without a fluid bay, which is every warehouse built before M30 — and that is what
     * keeps an item-only warehouse's readouts byte-identical to what they were.
     */
    public StockView<FluidKey, RackPosition> fluidStockIndex() {
        return fluidStock.readOnlyView();
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
        markChunkKeepDirty(); // M19: the job this controller knows about changed
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
        return rackAt(pos).flatMap(rack -> membership.kindAt(rack).map(kind -> new LocationRecord(rack, kind)));
    }

    /**
     * The rack position a world block holds in this warehouse, decided by the member standing there where a corner
     * leaves a choice ({@link #resolveRack}); empty if the block is no rack position at all.
     */
    Optional<RackPosition> rackOf(BlockPos pos) {
        return rackAt(pos);
    }

    /** The world position of a rack position of this warehouse; empty without a warehouse or without that aisle. */
    public Optional<BlockPos> worldPosOf(RackPosition rack) {
        return layout == null ? Optional.empty() : layout.worldPosOf(rack);
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
        // A terminal that was placed, broken, turned or whose chunk has just come back may hold a clipboard order the
        // warehouse does not know about yet; one boolean is the whole cost of never missing one (M23, issue #19).
        listTerminalsDirty = true;
    }

    /**
     * From the registry: the inventory a member at {@code rack} reads changed; read it again soon.
     * <p>
     * A storage location joins the stock-index queue, a <b>collecting</b> warehouse port the collect queue (M18, issue
     * #13) — the same hint channel, the same throttle, the same per-tick budget. A port that does not collect drops the
     * hint here rather than at the block, because a neighbour update need not find the port's settings in step.
     */
    void onContentChanged(RackPosition rack) {
        LocationKind kind = membership.kindAt(rack).orElse(null);
        if (kind == LocationKind.STORAGE)
            pendingSnapshots.addUrgent(rack);
        else if (kind == LocationKind.OUTPUT && ports.at(rack).isCollecting())
            pendingCollections.addUrgent(rack);
    }

    /**
     * From the registry: a player changed the store filter or the storage priority of the storage location at
     * {@code rack}. Re-reads <b>both</b> at once (one block entity lookup), so the next planning run already honours the
     * change; the inventory itself is not re-read, because its contents did not change
     * ({@code docs/warehouse-system.md} §3.1, ADR-021, ADR-028).
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
            filters.set(rack, AisleFilters.read(member));
    }

    /**
     * From the registry: a player changed a setting of the warehouse port at {@code rack} — its direction, its rank or
     * its redstone behaviour ({@code docs/warehouse-system.md} §3.2, M17, issue #12). Re-reads its policy at once (one
     * block entity lookup), so the next continuous pass and the next planning run already obey it.
     * <p>
     * A port that <b>stopped requesting</b> also loses what it still waits for: nothing may be delivered to a port that
     * no longer asks for anything, and the existing cancellation path aborts a crane job before its pick and reroutes
     * its items back into storage after it, so nothing is over-delivered either.
     */
    void onPortChanged(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return;
        if (membership.kindAt(rack).orElse(null) != LocationKind.OUTPUT) {
            forgetPort(rack);
            return;
        }
        if (!readPortAt(rack))
            return;
        if (!ports.at(rack).isRequesting() && cancelRequestsFor(layout.rackPos(rack)))
            setChanged();
    }

    /** The port at {@code rack} left the aisle, or is no longer a port: its policy and its collect state go with it. */
    private void forgetPort(RackPosition rack) {
        ports.remove(rack);
        collections.remove(rack);
        pendingCollections.remove(rack);
    }

    /**
     * Reads the policy of the warehouse port at {@code rack} straight from its block entity: the one lookup
     * {@link AislePorts} does per port that was never read into the cache. A warehouse <b>terminal</b> is an
     * {@link LocationKind#OUTPUT} member too (ADR-018) and carries no port settings, so it is recorded as the default
     * policy rather than left unread for ever.
     *
     * @return whether it could be read; while it cannot, the port keeps its unread mark and the default policy
     */
    private boolean readPortAt(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return false;
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved())
            return false;
        if (blockEntity instanceof WarehouseOutputBlockEntity port)
            ports.set(rack, port.portSettings(), port.filterKey());
        else
            ports.set(rack, PortSettings.DEFAULT, Optional.empty());
        // A port that stopped collecting keeps no collect state: an entry left behind would offer a snapshot of an
        // inventory nobody collects from any more (M18, issue #13). A port that started collecting is read at once, so
        // the very next planning run can use it instead of waiting a poll interval.
        if (!ports.at(rack).isCollecting()) {
            collections.remove(rack);
            pendingCollections.remove(rack);
        } else if (!collections.isRead(rack)) {
            pendingCollections.addUrgent(rack);
        }
        return true;
    }

    /**
     * Server: reads the inventory behind the collecting warehouse port at {@code rack} into {@link AisleCollections}
     * (M18, issue #13). The collect side of {@link #refreshLocation}.
     * <p>
     * It also resolves, in the same read and from the same {@code InventoryIdentifier} the stock index uses, whether that
     * inventory is one this aisle already counts as a storage location — the mistake a player makes by pointing a port at
     * the far side of a double chest that an interface already reads. Collecting from it is refused (§5, guard 3), and
     * because it is resolved <b>here</b> rather than per plan, it costs nothing in the planner and the port's goggles can
     * name it.
     *
     * @return whether a snapshot was taken; false if {@code rack} is no collecting port, or it or its inventory is not
     * loaded, or the inventory failed to read (the last read is kept in these cases)
     */
    private boolean refreshCollection(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return false;
        // M19: what a collecting port has pending is work only with the separate opt-in, so only then can a port poll
        // change the hold at all. Below the guards above and behind the opt-in on purpose: hinting unconditionally made
        // every port poll of every server pay an evaluation for a decision that was already NONE (M19 review).
        if (WareworksConfig.chunkLoadingEnabled() && WareworksConfig.maxCollectHoldAislesPerLevel() > 0)
            markChunkKeepDirty();
        if (membership.kindAt(rack).orElse(null) != LocationKind.OUTPUT || !ports.at(rack).isCollecting())
            return false;
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port) || port.isRemoved()) {
            membership.markDirty(rack); // gone without a notification: probe it again
            return false;
        }
        BlockPos attached = port.attachedPos();
        if (!level.isLoaded(attached))
            return false;
        InventorySnapshot<ItemKey> snapshot;
        boolean ownStorage;
        Object identity;
        try {
            identity = inventoryIdentity(attached, port.facing());
            ownStorage = sharedInventories.holds(identity);
            snapshot = ownStorage ? InventorySnapshot.empty() : port.collectSnapshot();
        } catch (RuntimeException e) {
            if (snapshotFailures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Warehouse controller at {} could not read the inventory behind the port {}",
                        worldPosition, rack, e);
            return false;
        }
        pendingCollections.remove(rack);
        collections.set(rack, snapshot, level.getGameTime(), ownStorage, identity);
        return true;
    }

    /**
     * The aisle's accepting warehouse ports that will take items <b>right now</b> ({@code PlannerInput#ports}, M17,
     * issue #12): the cached accepting ports whose redstone gate is open, in index order.
     * <p>
     * This is where the whole policy is applied, so the planner only ever ranks what it is handed: the direction and the
     * rank come from the cache, the <b>signal</b> from the port's block state (one {@code getBlockState}, never cached, so
     * it cannot be stale) and the pulse token from the port's block entity — the only lookup here, and only for a port
     * that is in pulse mode at all. An aisle whose ports all request answers the empty list without touching the world,
     * which is what makes a warehouse without accepting ports plan exactly as it did before M17.
     */
    List<RackPosition> acceptingPorts() {
        if (level == null || level.isClientSide || layout == null)
            return List.of();
        List<RackPosition> candidates = ports.acceptingPorts();
        if (candidates.isEmpty())
            return List.of();
        List<RackPosition> open = new ArrayList<>(candidates.size());
        for (RackPosition rack : candidates) {
            BlockPos pos = layout.rackPos(rack);
            if (!level.isLoaded(pos))
                continue;
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(WarehouseOutputBlock.POWERED))
                continue;
            PortSettings policy = ports.at(rack);
            boolean armed = policy.redstone() == PortRedstone.PULSE && isArmed(pos);
            if (policy.gateOpen(state.getValue(WarehouseOutputBlock.POWERED), armed))
                open.add(rack);
        }
        return open;
    }

    /**
     * The aisle's <b>collecting</b> warehouse ports that will hand items out <b>right now</b>
     * ({@code PlannerInput#collectSources}, M18, issue #13), in index order.
     * <p>
     * The whole policy is applied here, so the planner only ranks what it is handed. A port is a source only if all of
     * this holds:
     * <ul>
     * <li>its direction is {@code COLLECT} (from the cache, so a port this controller never read is <b>not</b> one —
     * "not read" never means "collect", the same safe asymmetry as M17);</li>
     * <li>its redstone gate is open right now: the signal from the block state (never cached, so it cannot be stale) and
     * the pulse token from the block entity — the only world lookup here, and only for a port in pulse mode;</li>
     * <li>its rack position and the inventory behind it are loaded;</li>
     * <li>the last read of that inventory found something <b>the port's own filter names</b>, and it is not an inventory
     * this aisle already indexes ({@link AisleCollections#hasItems}, which is one map read because a port's filter is one
     * item). A port whose machine holds only items it does not name is therefore no source at all, rather than a candidate
     * that spends a live extract on a certain refusal on every run (M18 review).</li>
     * </ul>
     * An aisle without a collecting port answers the empty list without touching the world at all: the cache is empty, so
     * the candidate list is, which is what makes a warehouse without them plan exactly as it did before M18.
     */
    List<RackPosition> collectSources() {
        if (level == null || level.isClientSide || layout == null || collections.isEmpty())
            return List.of();
        List<RackPosition> candidates = ports.collectingPorts();
        if (candidates.isEmpty())
            return List.of();
        List<RackPosition> open = new ArrayList<>(candidates.size());
        for (RackPosition rack : candidates) {
            if (!collections.hasItems(rack, ports.filterKeyAt(rack)))
                continue;
            BlockPos pos = layout.rackPos(rack);
            if (!level.isLoaded(pos))
                continue;
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(WarehouseOutputBlock.POWERED))
                continue;
            PortSettings policy = ports.at(rack);
            boolean armed = policy.redstone() == PortRedstone.PULSE && isArmed(pos);
            if (!policy.gateOpen(state.getValue(WarehouseOutputBlock.POWERED), armed))
                continue;
            // The inventory has to be loaded too, or the crane would travel there and wait; the port's own block entity
            // knows where it is, and it is already resolved because the snapshot was read through it.
            if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port) || port.isRemoved()
                    || !level.isLoaded(port.attachedPos()))
                continue;
            open.add(rack);
        }
        return open;
    }

    /** What the collecting port at {@code rack} may hand out, as of its last read ({@code PlannerInput#collectBuffers}). */
    InventorySnapshot<ItemKey> collectBuffer(RackPosition rack) {
        return collections.snapshotOf(rack);
    }

    /** How many warehouse ports of this aisle collect items out of a machine (M18), for the goggles and the displays. */
    public int collectingPortCount() {
        return ports.collectingCount();
    }

    /**
     * Whether the port at {@code rack} collects (M18, issue #13). Asked by {@code CraneDispatch#rerouteOutputs}: a
     * collecting port is <b>no delivery target at all</b>, so it must not even be offered to a retrieve reroute — its
     * context refuses every insertion anyway, but offering it would spend a live simulation on a certain refusal.
     */
    boolean isCollectingPort(RackPosition rack) {
        return ports.at(rack).isCollecting();
    }

    /**
     * Items the last read of the inventory behind the collecting port at the world position {@code portPos} found
     * <b>that the port may fetch</b>, for that port's own goggle line; 0 for anything that is no collecting port of this
     * aisle (M18, issue #13).
     * <p>
     * Filtered by the port's own filter, so the "Ready: N" line counts what the next collect job may work with and never
     * contradicts the "Collects: X" line above it (M18 review).
     */
    public long collectableAt(BlockPos portPos) {
        Objects.requireNonNull(portPos, "portPos");
        if (layout == null)
            return 0L;
        return rackAt(portPos).map(rack -> collections.totalAt(rack, ports.filterKeyAt(rack))).orElse(0L);
    }

    /**
     * Why the warehouse did not take what waits behind the collecting port at the world position {@code portPos}, for that
     * port's own goggle line; empty while it has no such answer (M18 review).
     * <p>
     * It is the aisle's last planning reason, narrowed to the answers a collect plan produces about the <b>warehouse</b>
     * ({@link NoJobReason#refusesCollecting()}) and only while this port really has something ready. A planning run that
     * produced a job clears the reason, so a port that is being served never shows one. Two map reads, no world access:
     * this is asked while a player looks at the port through goggles.
     */
    public Optional<NoJobReason> collectRefusalAt(BlockPos portPos) {
        Objects.requireNonNull(portPos, "portPos");
        if (layout == null)
            return Optional.empty();
        Optional<NoJobReason> reason = lastPlanReason().filter(NoJobReason::refusesCollecting);
        if (reason.isEmpty())
            return Optional.empty();
        return rackAt(portPos)
                .filter(rack -> collections.hasItems(rack, ports.filterKeyAt(rack))).isPresent()
                ? reason : Optional.empty();
    }

    /**
     * Whether the port at the world position {@code portPos} is pointed at an inventory this aisle already counts as a
     * storage location, so collecting from it is refused (§5, guard 3) — the one line a player needs to see to understand
     * why their port does nothing (M18, issue #13).
     */
    public boolean collectsFromOwnStorage(BlockPos portPos) {
        Objects.requireNonNull(portPos, "portPos");
        return layout != null && rackAt(portPos).map(collections::isOwnStorage).orElse(false);
    }

    private boolean isArmed(BlockPos pos) {
        return level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port && !port.isRemoved()
                && port.isArmed();
    }

    /**
     * The signed rank of the warehouse port at {@code rack} ({@code PlannerInput#portRank}, M17): negative an overflow,
     * positive a diversion, 0 a requesting port — and 0 is also the answer for a port this controller has not read, which
     * is why an unresolved port can never receive anything.
     */
    int portRankAt(RackPosition rack) {
        return ports.rankAt(rack);
    }

    /** How many warehouse ports of this aisle accept items instead of requesting them (M17), for the goggles. */
    public int acceptingPortCount() {
        return ports.acceptingCount();
    }

    /**
     * A store job was planned into the accepting port at {@code rack}: an unused rising edge of a port in pulse mode is
     * spent now, so "one action per edge" means one trip (M17). Continuous ports hold no token, so this is a no-op for
     * them.
     */
    void onPortSelected(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return;
        BlockPos pos = layout.rackPos(rack);
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port
                && !port.isRemoved())
            port.consumeArmed();
    }

    /**
     * Tops up the aisle's <b>continuous requesting ports</b> ({@code docs/warehouse-system.md} §3.2, M17): a port whose
     * redstone behaviour is not a single pulse submits again as soon as it waits for nothing at all, so a machine stays
     * supplied without a clock and never more than one trip is promised at a time. A request can only be re-submitted
     * once the previous one closed, which nothing but a pass like this can notice.
     * <p>
     * Cost: nothing at all for an aisle whose ports are plain outputs, because the cache is then empty. Otherwise one
     * {@code isLoaded} and one {@code getBlockState} per continuous port — the redstone signal is read from the block
     * state and never cached, so it cannot be stale — and one block entity lookup only for a port that really submits. A
     * refused port is backed off for {@code retryTicks}, so an item that is out of stock cannot make it spin.
     */
    private void tickPorts(long now) {
        for (RackPosition rack : ports.unreadPorts())
            readPortAt(rack);
        pollCollectingPorts(now);
        for (RackPosition rack : ports.continuousRequests()) {
            if (!ports.isDue(rack, now))
                continue;
            BlockPos pos = layout.rackPos(rack);
            if (!level.isLoaded(pos))
                continue;
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(WarehouseOutputBlock.POWERED)
                    || !ports.at(rack).gateOpen(state.getValue(WarehouseOutputBlock.POWERED), false))
                continue;
            if (!requests.requestsFor(pos).isEmpty())
                continue;
            if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port) || port.isRemoved())
                continue;
            Optional<RequestResult> result = port.submitIfIdle();
            if (result.isPresent() && !result.get().isAccepted())
                ports.backOffUntil(rack, now + Math.max(1, WareworksConfig.retryTicks()));
            else
                ports.clearBackOff(rack);
        }
    }

    /**
     * Queues a read of the inventory behind every <b>gated-open collecting</b> port whose last read is older than
     * {@code collectPollIntervalTicks} ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13).
     * <p>
     * This is what covers the machines that change their inventory <b>without</b> firing a neighbour-change hint — a
     * furnace's result slot, several Create blocks — which is the same problem the warehouse interface answers with its
     * clean-summary refresh. A collect can therefore lag by up to {@code collectPollIntervalTicks + dispatchIntervalTicks};
     * the alternative is a per-tick scan, which the hard rules forbid.
     * <p>
     * Cost: nothing at all for an aisle without a collecting port (the cache is empty, so the list is), and otherwise one
     * {@code isLoaded} plus one {@code getBlockState} per collecting port per pass — the cost the continuous-request pass
     * already pays — plus one block entity lookup only for a port in pulse mode. The reads themselves are
     * <b>background</b>, so a hint always overtakes a poll, and they share the one {@code maxSnapshotsPerTick} budget with
     * the stock index.
     */
    private void pollCollectingPorts(long now) {
        List<RackPosition> collecting = ports.collectingPorts();
        if (collecting.isEmpty())
            return;
        long interval = Math.max(1, WareworksConfig.collectPollIntervalTicks());
        for (RackPosition rack : collecting) {
            if (!collections.isStale(rack, now, interval) || pendingCollections.contains(rack))
                continue;
            BlockPos pos = layout.rackPos(rack);
            if (!level.isLoaded(pos))
                continue;
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(WarehouseOutputBlock.POWERED))
                continue;
            PortSettings policy = ports.at(rack);
            boolean armed = policy.redstone() == PortRedstone.PULSE && isArmed(pos);
            // A port whose gate is shut costs nothing at all: "off means off" holds for the reading, not only for the
            // planning, so a switched-off port never touches its machine's inventory.
            if (policy.gateOpen(state.getValue(WarehouseOutputBlock.POWERED), armed))
                pendingCollections.addBackground(rack);
        }
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
        // The store settings are refreshed here as well: this is every path on which the controller already resolves the
        // member (join, content hint, round robin, after a transfer, load verification), and they have to be known even
        // when the attached inventory is not loaded (ADR-021, ADR-028, M28).
        filters.set(rack, AisleFilters.read(member));
        // And the fluid, on the same path and for the same reason (M30 step 10, D10): a fluid bay IS its own tank, so
        // its contents are readable the moment the member resolves and need none of the checks below, which are about
        // an inventory that stands somewhere else. Every other storage member answers the empty map, so this line
        // costs a warehouse without a fluid bay one virtual call per read and changes nothing about it.
        //
        // Keyed by the location itself and never by the shared-inventory canonical: a bay answers no item capability
        // at all (D3), so no two locations can ever count one tank.
        //
        // No setChanged() for a change here, deliberately, which is one of the two places this index differs from the
        // item one: it is not saved (see the field), so there is nothing to write — and a Mechanical Pump filling a
        // bay reports a change several times a second, so marking the chunk dirty for it would be pure churn.
        //
        // The other difference: a location holding no fluid is REMOVED rather than restored with no counts, so the
        // fluid index holds the warehouse's fluid bays and nothing else. The item index keeps every storage location
        // on purpose, because occupiedLocations() compares the ones in use against the ones counted; nothing asks the
        // fluid index how many locations a warehouse has, and an entry per chest would be a map entry per location for
        // a number nobody reads.
        Map<FluidKey, Long> carried = member.fluidStock();
        if (carried.isEmpty())
            fluidStock.remove(rack);
        else
            fluidStock.restore(rack, carried);
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
        return inventoryIdentity(attached, member.facing().getOpposite());
    }

    /**
     * The same identity for a position and the face of that block which touches the member reading it, so a storage
     * location and a <b>collecting</b> warehouse port resolve one double chest to the <b>same</b> identity however they
     * face (M18, issue #13) — which is what makes "a port never collects from an inventory this aisle counts" an identity
     * comparison rather than a position comparison.
     */
    private Object inventoryIdentity(BlockPos attached, Direction attachedFace) {
        InventoryIdentifier identifier = InventoryIdentifier.get(level, new BlockFace(attached, attachedFace));
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
    private void queueOtherReaders(WarehouseLayout current, RackPosition rack, Object identity) {
        if (!(identity instanceof InventoryIdentifier identifier))
            return; // a bare position identifies exactly one block: no other location can read it as something else
        for (LocationRecord record : membership.records(LocationKind.STORAGE)) {
            RackPosition other = record.position();
            if (other.equals(rack) || identity.equals(sharedInventories.identityOf(other).orElse(null)))
                continue;
            Direction side = current.sideDirection(other);
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
        return rackAt(station).map(productionOrders::ordersFor).orElse(List.of());
    }

    /**
     * The ingredients the open production orders still owe their stations, for the planner
     * ({@code PlannerInput#supplies}). Only orders that are still collecting ingredients contribute, and only at
     * stations this aisle really records.
     * <p>
     * <b>An order that is waiting for a step of its own plan contributes nothing at all</b> (M20, ADR-032): not one of
     * its lines, not even the ones the racks could pay for right now. This is the safety property of a chain. A machine
     * cannot run on a partial set, but a funnel or a Mechanical Arm will happily push half a run into it, and a chain
     * that then fails has left half-sets of ingredients in several machines with nothing to show for them — which is
     * exactly what turns a deep chain into a deep loss ({@code docs/warehouse-system.md} §3.5.4). Waiting also makes the
     * blocked state truthful, and it makes two steps of one plan at the same station strictly sequential.
     * <p>
     * It costs one {@link ProductionOrders#hasOpenChildren} per open order — a lookup per ingredient line, bounded by
     * {@code maxProductionOrders} — and nothing at all in an aisle whose orders are all single level.
     */
    public List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> supplyNeeds() {
        if (level == null || level.isClientSide || layout == null || productionOrders.isEmpty())
            return List.of();
        List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> needs = new ArrayList<>();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open()) {
            if (order.state() != ProductionOrderState.WAITING_FOR_INGREDIENTS
                    || membership.kindAt(order.station()).orElse(null) != LocationKind.PRODUCTION)
                continue;
            if (productionOrders.hasOpenChildren(order.id()))
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
     * <p>
     * <b>Cancelling one order of a production plan ends the whole plan</b> (M20, ADR-032): an order above it is waiting
     * for something that will never be made, and an unfinished order below it is making something nobody will use. What
     * that costs is bounded by the same rule everywhere — what a machine already swallowed is gone and is reported, what
     * is still in the racks stays there, and an order whose ingredients are already at a machine is left running so its
     * product still comes back ({@link #failProductionPlan}).
     */
    public Optional<ProductionOrder<ItemKey, RackPosition>> cancelProductionOrder(UUID id) {
        if (level == null || level.isClientSide)
            return Optional.empty();
        Optional<ProductionOrder<ItemKey, RackPosition>> cancelled = productionOrders.cancel(id, level.getGameTime());
        cancelled.ifPresent(order -> {
            onProductionOrderEnded(order);
            setChanged();
        });
        return cancelled;
    }

    /** A production station was broken or replaced: its orders can never finish, so they are cancelled. */
    public void onProductionStationRemoved(BlockPos station) {
        Objects.requireNonNull(station, "station");
        if (layout == null)
            return;
        rackAt(station).ifPresent(this::cancelProductionOrdersAt);
    }

    private void cancelProductionOrdersAt(RackPosition rack) {
        long now = level == null ? 0L : level.getGameTime();
        for (ProductionOrder<ItemKey, RackPosition> cancelled : productionOrders.cancelFor(rack, now))
            onProductionOrderEnded(cancelled);
    }

    /**
     * Everything the planner needs to work out a chain, from <b>one</b> snapshot of this aisle (M20, issue #4,
     * ADR-032): the patterns of its production stations, what an ingredient is worth to this caller, the configured
     * bounds, the free order slots, the items the safety stop has stopped, and the room every item's own maximum leaves.
     * <p>
     * Built once per click and never per tick. Nothing in it is read again while the plan is worked out — the budget
     * asks the availability at most once per item, however many times the planner has to try a smaller plan
     * ({@link PlanBudget#fresh()}) — which is what makes a refusal and the plan that follows it talk about the same
     * warehouse.
     *
     * @param ingredients what an ingredient is worth to <b>this</b> caller: the plain {@link #availabilityLookup()} for
     *                    the warehouse's own accounting, and the same lookup behind a rule's reserve for a taker that
     *                    may not spend it ({@link StockAvailability#of}, M15). It bounds both the pattern choice and the
     *                    number of runs at every level, so no step of a chain can be planned against ingredients its
     *                    starter is not allowed to have
     */
    private ProductionPlanInput<ItemKey, RackPosition> productionPlanInput(List<AislePattern> patterns,
            ToLongFunction<ItemKey> ingredients) {
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        List<StationPattern<ItemKey, RackPosition>> stationPatterns = new ArrayList<>(patterns.size());
        for (AislePattern candidate : patterns)
            stationPatterns.add(StationPattern.of(candidate.station(), candidate.pattern()));
        int slots = configuredMaxProductionOrders();
        int freeSlots = slots - productionOrders.openCount();
        // productionRoomFor answers Long.MAX_VALUE for an item no rule caps, which is exactly ProductionPlanInput's
        // "as much room as anyone could ask for", so an aisle without stock keepers pays nothing for the question.
        return new ProductionPlanInput<>(stationPatterns, PlanBudget.of(ingredients), WareworksConfig.planLimits(),
                freeSlots, slots, stockPauses::containsKey, this::productionRoomFor);
    }

    /**
     * The chain that would make {@code amount} items of {@code key} for one click, or the refusal that <b>names the
     * item</b> in the way; empty when nothing is to be produced at all.
     */
    private Optional<ProductionPlanResult<ItemKey, RackPosition>> planProduction(
            ProductionPlanInput<ItemKey, RackPosition> input, ItemKey key, long amount) {
        if (level == null || layout == null || amount < 1L || input.patterns().isEmpty())
            return Optional.empty();
        return Optional.of(ProductionPlanner.plan(input, key, amount));
    }

    /**
     * Creates <b>every</b> order of {@code plan} in this tick, children first (M20, issue #4, ADR-032): a chain is
     * accepted all at once or not at all.
     * <p>
     * That atomicity is the whole reservation. The moment the orders exist, every ingredient <i>and</i> every
     * intermediate of the chain is promised by an ordinary supply line — {@link #availableStock} already subtracts what
     * the open orders owe — so the plan itself never has to stay valid, and there is no window in which it is a hope
     * rather than a promise. A half-created chain, on the other hand, would be a parent fetching ingredients for a run
     * nothing is going to complete, which is why {@link ProductionOrders#addAll} is all-or-nothing.
     * <p>
     * Each step names the <b>ingredient line of its parent</b> that its product is for, and the orders are therefore
     * built root first (a child needs its parent's line id) and added children first.
     *
     * @param backingRequest the retrieval request waiting for the ordered item, or {@code null}
     * @return result items the plan's root <b>promises that request</b>, i.e. at most what was asked for (0 when nothing
     * was created). A pattern makes whole runs, so the orders may yield more; that surplus simply lands in stock and was
     * promised to nobody ({@code ProductionOrder#promisedToRequest})
     */
    private int startProductionPlan(ProductionPlan<ItemKey, RackPosition> plan, @Nullable UUID backingRequest) {
        if (level == null || layout == null)
            return 0;
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        long now = level.getGameTime();
        long timeout = productionTimeoutTicks();
        int promised = (int) Math.min(Integer.MAX_VALUE, plan.rootPromise());
        Map<Integer, ProductionOrder<ItemKey, RackPosition>> byNode = new HashMap<>();
        // Root first, because a step can only name a line of an order that already exists.
        for (int index = plan.steps() - 1; index >= 0; index--) {
            PlanNode<ItemKey, RackPosition> node = plan.node(index);
            ProductionOrder<ItemKey, RackPosition> order;
            if (node.isRoot()) {
                order = ProductionOrder.start(UUID.randomUUID(), node.station(), node.pattern(), node.runs(),
                        UUID::randomUUID, now, timeout, stock.count(plan.result()), backingRequest, promised);
            } else {
                ProductionOrder<ItemKey, RackPosition> parent = byNode.get(node.parent());
                Optional<UUID> line = parent == null ? Optional.empty() : lineFor(parent, node.result());
                if (line.isEmpty()) {
                    // Unreachable: a node's result is an ingredient of its parent's pattern by construction. Creating
                    // nothing is the only safe answer, because a step without a parent line is not a step at all.
                    Wareworks.LOGGER.warn("Production plan for {} at {} dropped: step {} has no line to feed",
                            plan.result(), worldPosition, index);
                    return 0;
                }
                order = ProductionOrder.step(UUID.randomUUID(), node.station(), node.pattern(), node.runs(),
                        UUID::randomUUID, now, timeout, line.get());
            }
            byNode.put(index, order);
        }
        List<ProductionOrder<ItemKey, RackPosition>> batch = new ArrayList<>(plan.steps());
        for (int index = 0; index < plan.steps(); index++)
            batch.add(byNode.get(index));
        if (!productionOrders.addAll(batch))
            return 0;
        setChanged();
        markChunkKeepDirty(); // M19: an open order is work, and every step of a plan is one of these
        return promised;
    }

    /** The ingredient line of {@code parent} that asks for {@code key}; a pattern merges duplicates, so at most one. */
    private static Optional<UUID> lineFor(ProductionOrder<ItemKey, RackPosition> parent, ItemKey key) {
        for (SupplyLine<ItemKey> line : parent.lines()) {
            if (line.key().equals(key))
                return Optional.of(line.id());
        }
        return Optional.empty();
    }

    /**
     * Whether a <b>chain</b> started for the output station at {@code destination} is still running (M20, decision 3):
     * the "at most one open request at a time" rule of a continuous requesting port (M17), extended to plans.
     * <p>
     * A redstone port may start a whole chain, and that is unattended: a clock pulsing a port that can spend ingredients
     * into a tree of machines is exactly the overnight drain ADR-027 forbids. A plan is attributed to the port through
     * its root's backing request, which is open for as long as the chain is: the request only closes once the produced
     * items have been delivered there.
     * <p>
     * <b>A plan is open while any of its orders is</b>, and that is the whole point of asking it this way round (M20
     * review fix). The steps of a chain are the <b>first</b> orders to finish; the root then still has to fetch the
     * intermediate, wait for its own machine and have the product delivered, which is usually the longest part. Asking
     * "is an open order a step" therefore answered "no plan here" for most of a chain's life, and a clock could stack a
     * second, third and fourth chain into the same machines. The finished steps are still in the collection while their
     * plan runs ({@link ProductionOrders#prune} ages a plan out as a whole), so counting the plan's members is exact.
     * <p>
     * <b>A chain nobody is waiting for holds every port back.</b> A plan whose root has lost its backing request — the
     * request was cancelled, pruned with its output or reduced to nothing ({@link ProductionOrders#detachRequest}) —
     * cannot be attributed to a destination any more, and answering "not this port's" for it would let the very pulse
     * that this guard exists for start a second chain. It keeps running, so it is treated as one open plan for every
     * port; it is bounded in time, and the answer a port is given is "the warehouse is busy", which is true.
     * <p>
     * Costs one walk of the open orders, each with a bounded walk of its own plan, and only for a
     * {@link StockAccess#AUTOMATION} request that really would create a chain.
     */
    public boolean hasOpenProductionPlan(BlockPos destination) {
        Objects.requireNonNull(destination, "destination");
        Set<UUID> seen = new HashSet<>();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open()) {
            ProductionOrder<ItemKey, RackPosition> root = productionOrders.rootOf(order.id()).orElse(order);
            if (!seen.add(root.id()))
                continue; // one answer per plan, however many of its orders are open
            if (productionOrders.planOf(root.id()).size() <= 1)
                continue; // a single order is nobody's plan
            Optional<UUID> request = root.backingRequest();
            if (request.isEmpty())
                return true;
            if (requests.get(request.get()).filter(waiting -> waiting.destination().equals(destination)).isPresent())
                return true;
        }
        return false;
    }

    /** The whole plan production order {@code id} belongs to, in dependency order; an order in no plan is one of one. */
    public List<ProductionOrder<ItemKey, RackPosition>> productionPlanOf(UUID id) {
        return id == null ? List.of() : productionOrders.planOf(id);
    }

    /** Open production orders of this aisle that are a <b>step</b> of a chain, i.e. that another order waits for. */
    public int openProductionStepCount() {
        return productionOrders.openStepCount();
    }

    /**
     * The screen rows for {@code orders} (M20, issue #4, ADR-032): every order with the plan it belongs to, how deep in
     * that plan it sits, the <b>address</b> of the station it runs at and whether it is waiting for an earlier step.
     * <p>
     * It lives here, in the one place that owns the orders, because both screens show these rows — the terminal's
     * production section and a production station's own list — and a plan that looked different in the two would be
     * worse than no plan display at all. The terminal in particular has no aisle layout and no aisle letter, so only the
     * server can name an address ({@code docs/warehouse-system.md} §3.4.2).
     * <p>
     * <b>A plan of one order is no plan.</b> An ordinary single-level order gets an empty plan id, depth 0 and
     * {@code waitingForStep = false}, which is exactly the row it had before M20: a warehouse that runs no chains looks
     * unchanged, on the wire and on the screen.
     * <p>
     * Cost: one {@link ProductionOrders#planOf} walk per plan, memoised over the rows of one push, plus one
     * {@code hasOpenChildren} lookup per open order. Nothing here reads an inventory or the world.
     */
    public List<ProductionScreenState.OrderView> productionOrderViews(
            List<ProductionOrder<ItemKey, RackPosition>> orders) {
        Objects.requireNonNull(orders, "orders");
        if (orders.isEmpty())
            return List.of();
        Map<UUID, Optional<UUID>> planOfOrder = new HashMap<>();
        Map<UUID, Integer> planSizes = new HashMap<>();
        List<ProductionScreenState.OrderView> views = new ArrayList<>(orders.size());
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            Optional<UUID> plan = planOfOrder.computeIfAbsent(order.id(), id -> {
                UUID root = productionOrders.rootOf(id).map(ProductionOrder::id).orElse(id);
                int size = planSizes.computeIfAbsent(root, member -> productionOrders.planOf(member).size());
                return size > 1 ? Optional.of(root) : Optional.empty();
            });
            int depth = plan.isPresent() ? productionOrders.depthOf(order.id()) : 0;
            boolean waiting = waitsForStep(order);
            views.add(new ProductionScreenState.OrderView(order.id(), order.state(), order.result(),
                    order.resultAmount(), order.produced(), order.outstandingIngredients(),
                    order.deliveredIngredients(), plan, depth, stationAddress(order.station()), waiting));
        }
        return List.copyOf(views);
    }

    /**
     * Whether {@code order} is an open order of a plan that is <b>fetching nothing</b> because an earlier step of its
     * own chain is still running (M20, issue #4, ADR-032).
     * <p>
     * The one definition of that state: {@link #productionOrderViews} sends it to both screens, a production station's
     * goggle line asks it about its oldest order, and {@link #supplyNeeds} acts on the same question by handing such an
     * order nothing. A surface that worked it out for itself could contradict what the aisle really does.
     */
    public boolean waitsForStep(ProductionOrder<ItemKey, RackPosition> order) {
        return order != null && order.isOpen() && productionOrders.hasOpenChildren(order.id());
    }

    /** The canonical address of a rack position of this aisle, e.g. {@code "A-05-01R"}; empty without a layout. */
    public Optional<String> stationAddress(RackPosition rack) {
        if (rack == null || layout == null)
            return Optional.empty();
        return layout.address(rack).map(StorageAddress::format);
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
        // A step that times out ends its whole plan (M20): the order above it is waiting for something no machine is
        // going to make now. An order that is itself waiting for a step is not timed out at all — its deadline starts
        // over when that step ends (ProductionOrders#timeOut).
        for (ProductionOrder<ItemKey, RackPosition> timedOut : productionOrders.timeOut(now, timeout)) {
            onProductionOrderEnded(timedOut);
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
        boolean changed = false;
        if (productionRestorePending) {
            productionRestorePending = false;
            productionOrders.restartDeadlines(now, timeout);
            // M20: a truncated or hand-edited save must not leave a step waiting for a parent that does not exist.
            // validatePlans answers for the whole broken chain itself (a step under a step whose own parent is gone is
            // broken too), so this is deliberately the per-order cleanup and not onProductionOrderEnded: a plan that the
            // save data does not describe any more must not be "failed" as though a machine had let it down.
            // It can arm no safety stop either — a step is never an automatic order, it has delivered nothing when it is
            // cancelled here, and a save-integrity failure is no evidence about anybody's machine (ADR-027, ADR-032).
            for (ProductionOrder<ItemKey, RackPosition> broken : productionOrders.validatePlans(now)) {
                changed = true;
                if (!broken.isOpen())
                    onProductionOrderFinished(broken);
            }
        }
        Set<ItemKey> results = new HashSet<>();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open())
            results.add(order.result());
        for (ItemKey result : results) {
            // Items the warehouse really stored out of one of its inputs since the last observation are already
            // counted (onCraneDelivered) and are in this level too: crediting them a second time would complete an
            // order nothing was made for (M15 part 2).
            long arrived = storedCredits.getOrDefault(result, 0L);
            // Only an order that really changed marks the controller dirty: observing an unchanged stock level must
            // not save the chunk on every tick for as long as an order is open.
            for (ProductionOrder<ItemKey, RackPosition> observed
                    : productionOrders.observeResult(result, stock.count(result), now, timeout, arrived)) {
                changed = true;
                if (observed.state() == ProductionOrderState.COMPLETE)
                    onProductionOrderFinished(observed);
            }
        }
        // The credits are per observation window: whatever was not consumed here belonged to a level rise that has
        // already been seen, or to an order that has since closed.
        storedCredits.clear();
        return changed;
    }

    /**
     * Server: result items of a production order really arrived in the warehouse — the crane stored them out of one of
     * this aisle's warehouse inputs, which is the route the product of a pattern takes back into the racks
     * ({@code docs/warehouse-system.md} §3.5).
     * <p>
     * This is the <b>only</b> channel an automatic restock order is completed by, and that is the whole of the safety
     * stop (M15 part 2, ADR-026): a rise of the stock index says nothing about where the items came from, so a barrel
     * tipped into a rack, an unrelated farm or a player taking the product out and putting it back would otherwise
     * complete an order whose ingredients a machine had swallowed — and the rule would go on feeding that machine.
     */
    private void onResultStored(ItemKey key, int delivered) {
        long timeout = productionTimeoutTicks();
        ProductionOrders.Observation<ItemKey, RackPosition> observed =
                productionOrders.observeStored(key, delivered, level.getGameTime(), timeout);
        if (observed.isEmpty())
            return;
        if (observed.credited() > 0L)
            storedCredits.merge(key, observed.credited(), Long::sum);
        for (ProductionOrder<ItemKey, RackPosition> order : observed.changed()) {
            if (order.state() == ProductionOrderState.COMPLETE)
                onProductionOrderFinished(order);
        }
        setChanged();
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
     * A production order <b>ended</b>, in the one place that answers for the whole of it: its own cleanup
     * ({@link #onProductionOrderFinished}) and, when it did not complete, the rest of the production plan it belonged
     * to ({@link #failProductionPlan}).
     * <p>
     * A <b>completed</b> order never touches its plan: completing is precisely what unblocks the order above it.
     */
    private void onProductionOrderEnded(ProductionOrder<ItemKey, RackPosition> order) {
        onProductionOrderFinished(order);
        if (order.state() != ProductionOrderState.COMPLETE)
            failProductionPlan(order);
    }

    /**
     * An order of a production plan ended badly, so the rest of that plan ends with it (M20, issue #4, ADR-032):
     * <b>every open order above it is cancelled</b> — it is waiting for something no machine is going to make now — and
     * every other open order of the plan is cancelled if it has handed nothing over, or detached and left running if its
     * ingredients are already at a machine ({@link ProductionOrders#failPlan}).
     * <p>
     * Every order this ends goes through the ordinary {@link #onProductionOrderFinished}, so a crane that is fetching
     * for it aborts before the pick or reroutes what it already holds back into storage, a request waiting for it is
     * given its promise back, and an order that really did lose a batch arms the safety stop for its own item. The
     * item-conservation invariant is untouched throughout: nothing is invented and nothing is taken back out of a
     * machine.
     * <p>
     * The plan's whole unrecovered total is logged as one number, because that is what a player has to be told
     * ({@code docs/warehouse-system.md} §3.5.4); the same number is on {@link ProductionOrders#unrecoveredOf} for the
     * screens.
     */
    private void failProductionPlan(ProductionOrder<ItemKey, RackPosition> node) {
        if (level == null)
            return;
        ProductionOrders.PlanFailure<ItemKey, RackPosition> failure =
                productionOrders.failPlan(node.id(), level.getGameTime());
        if (failure.isEmpty())
            return;
        // Not onProductionOrderEnded: failPlan has already answered for the whole plan in one pass, so every order it
        // ended needs its own cleanup and nothing more.
        for (ProductionOrder<ItemKey, RackPosition> cancelled : failure.cancelled())
            onProductionOrderFinished(cancelled);
        setChanged();
        markChunkKeepDirty(); // M19: the aisle may have nothing left to do
        Wareworks.LOGGER.info("Production plan at {} ended: the order for {} {}, so {} more were cancelled, {} were "
                + "left running and {} ingredient items were not recovered", worldPosition, node.result(),
                node.state().name().toLowerCase(java.util.Locale.ROOT), failure.cancelled().size(),
                failure.detached().size(), failure.unrecovered());
    }

    /**
     * A production order ended. The crane stops fetching for it, and the request that waited for its result gets back
     * what this order <b>promised</b> it and will never deliver, so that request does not wait for items nobody will
     * ever make. <b>Items are never invented and never taken back</b>: ingredients already handed to a machine stay
     * where they are ({@code docs/warehouse-system.md} §3.5.4).
     * <p>
     * A completed order needs no special case: it produced its whole promise, so
     * {@code ProductionOrder#unfulfilledPromise} is 0 and nothing is given back.
     * <p>
     * This is the per-order half; {@link #onProductionOrderEnded} is the one that also ends the order's plan.
     */
    private void onProductionOrderFinished(ProductionOrder<ItemKey, RackPosition> order) {
        cancelSupplyJobsOf(order);
        markChunkKeepDirty(); // M19: one fewer order, so the aisle may be idle now
        // The safety stop (M15 part 2, widened by M20): an order ended with ingredients already in a machine and
        // nothing coming back. Those items are unrecoverable, so the warehouse stops making that item and waits for the
        // player rather than feeding the same machine again. An order that gave up while the crane was still fetching
        // cost nothing and never pauses anything (ProductionOrder#endedWithLostIngredients).
        //
        // M15 armed this only for an order the warehouse had started by itself, because that was the only order it
        // repeated. A plan repeats just as well — a click, a redstone pulse or a rule can all send the same chain into
        // the same machine again — so the first loss of ANY order now stops that item for everyone, and the pause's
        // cause is what says which kind of order it was (StockRulePause.Cause, ADR-032).
        if (order.endedWithLostIngredients())
            pauseProduction(order.result(),
                    StockRulePause.Cause.of(order.isRestock(), order.state() == ProductionOrderState.CANCELLED),
                    order.deliveredIngredients());
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
        markChunkKeepDirty(); // M19: what the aisle has to do changed
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
        markChunkKeepDirty(); // M19: what the aisle has to do changed
    }

    private static int configuredMaxProductionOrders() {
        return Math.max(ProductionOrders.MIN_OPEN_ORDERS, WareworksConfig.maxProductionOrders());
    }

    private static long productionTimeoutTicks() {
        return Math.max(1, WareworksConfig.productionOrderTimeoutTicks());
    }

    // --- retrieval requests --------------------------------------------------------------------------------------

    /**
     * Server: what a player's request for {@code amount} items of {@code key} would cost across the boundaries a stock
     * keeper set — the question a warehouse terminal asks before it makes the request ({@code docs/warehouse-system.md}
     * §3.6.6, M15 part 2).
     * <p>
     * It measures; {@link RequestConfirmation#of} decides. Everything it measures is measured the way
     * {@link #request(BlockPos, ItemKey, int, int, StockAccess)} measures it — the same availability, the same pattern
     * choice, the same run count — so the question names exactly what the request would then do. A player is never
     * <b>refused</b> any of it ({@link StockAccess#PLAYER}); being told the number is the whole point.
     * <p>
     * <b>Cost.</b> An aisle without a governing rule answers after one integer read: no rule can be crossed, so
     * nothing is measured at all. A ruled item that is simply in stock costs one {@link #stockLevelsOf} — whose
     * {@link StockLevels#available()} is the very number the request would be clamped with — and nothing else: neither
     * the aisle's patterns nor the ingredients' availability are resolved, because a request that stays inside the
     * racks can spend no ingredient (M15 review fix). Only a request that would have to <b>produce</b> something walks
     * the patterns, and then once per click — never per tick.
     * <p>
     * A terminal does not call this and then make the request: it makes the request with what the player accepted, and
     * the request measures the question from its own snapshot
     * ({@link #request(BlockPos, ItemKey, int, int, StockAccess, RequestAcknowledgement)}), so the two can never
     * disagree and the snapshot is built once. This entry point is for asking without requesting.
     */
    public RequestConfirmation<ItemKey> confirmationFor(ItemKey key, int amount) {
        Objects.requireNonNull(key, "key");
        long wantedByClick = Math.max(0, amount);
        StockRules<ItemKey> rules = stockRules();
        if (level == null || level.isClientSide || isRemoved() || layout == null || wantedByClick < 1
                || rules.governingCount() == 0)
            return RequestConfirmation.none(key, wantedByClick);
        StockLevels levels = stockLevelsOf(key);
        if (wantedByClick <= levels.available())
            return confirmationFrom(key, wantedByClick, levels, null, NOTHING_AVAILABLE);
        // What the ingredients of a production order are worth to this request: a player's availability, i.e. with no
        // reserve taken off — which is precisely why the reserved part of it has to be named rather than subtracted.
        List<AislePattern> patterns = aislePatterns();
        ToLongFunction<ItemKey> ingredients = StockAvailability.of(rules, StockAccess.PLAYER, availabilityLookup());
        ProductionPlan<ItemKey, RackPosition> plan = planProduction(productionPlanInput(patterns, ingredients), key,
                wantedByClick - levels.available()).flatMap(ProductionPlanResult::plan).orElse(null);
        return confirmationFrom(key, wantedByClick, levels, plan, ingredients);
    }

    /**
     * {@link #confirmationFor} measured against a snapshot the caller has already taken: the <b>plan</b> the request
     * would create and what an ingredient is worth to this request.
     * <p>
     * Measuring it over the plan is what keeps the question honest once a chain can be ordered (M20, ADR-032): the
     * reserve warning names every item the whole chain takes <b>out of the racks</b>
     * ({@link ProductionPlan#leafDemand()}), which may be an item two steps away from the one that was clicked, and the
     * maximum is judged against what the plan's root really makes. It is the very plan the click then creates, so the
     * question and what happens cannot disagree.
     * <p>
     * {@code plan} and {@code ingredientAvailability} are only consulted when the request reaches past the racks, so an
     * in-stock request may be given {@code null} and {@link #NOTHING_AVAILABLE}.
     */
    private RequestConfirmation<ItemKey> confirmationFrom(ItemKey key, long wantedByClick, StockLevels levels,
            @Nullable ProductionPlan<ItemKey, RackPosition> plan, ToLongFunction<ItemKey> ingredientAvailability) {
        StockRules<ItemKey> rules = stockRules();
        // An aisle with no governing rule can cross no boundary a keeper set — but it can still have items *made*,
        // which is the one thing a list order's portion has to be agreed to first (M23, issue #19,
        // RequestConfirmation#required(RequestScope)). So the cheap answer is only given when nothing would be
        // produced either; a plain click is unaffected, because its question never looks at that number.
        if (wantedByClick < 1 || (rules.governingCount() == 0 && (plan == null || wantedByClick <= levels.available())))
            return RequestConfirmation.none(key, Math.max(0L, wantedByClick));
        // Only a request that reaches past the racks can start a plan at all, and nothing is produced without one.
        if (plan == null || wantedByClick <= levels.available())
            return RequestConfirmation.ofPlan(rules, key, Math.min(wantedByClick, levels.available()), levels, 0L,
                    Map.of(), NOTHING_AVAILABLE);
        return RequestConfirmation.ofPlan(rules, key, wantedByClick, levels, plan.root().output(), plan.leafDemand(),
                ingredientAvailability);
    }

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
     * Server: a retrieval request made on a player's behalf, i.e. one a stock rule's reserve does not hold back; see
     * {@link #request(BlockPos, ItemKey, int, int, StockAccess)}.
     * <p>
     * The warehouse's own automation passes {@link StockAccess#AUTOMATION} instead. This overload keeps the meaning
     * every caller had before M15 — nothing was ever held back from anyone — so no existing behaviour changes.
     */
    public RequestResult request(BlockPos outputPos, ItemKey key, int amount, int maxRemainingPerRequest) {
        return request(outputPos, key, amount, maxRemainingPerRequest, StockAccess.PLAYER);
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
     * <p>
     * <b>Stock rules</b> (M15, issue #3) enter here and nowhere else on the request side: the availability the queue
     * clamps with is {@link StockAvailability}, so a rule's reserve holds items back from
     * {@link StockAccess#AUTOMATION} — a redstone-triggered output request — while a {@link StockAccess#PLAYER} at a
     * terminal is still served down to the last item and told in the row that it goes below the reserve. Because the
     * wrapped availability already subtracts what the open requests promise, the reserve bounds a <b>merged</b>
     * request exactly as it bounds a new one (ADR-020). The <b>ingredients</b> of a production order this request
     * starts are measured against the same reserve, so automation cannot reach reserved items through a pattern.
     *
     * @param maxRemainingPerRequest largest amount one request of this station may wait for, at least 1
     *                               ({@link RequestQueue#NO_AMOUNT_LIMIT} for no cap of its own)
     * @param access                 who is asking: the warehouse's own automation stops at a rule's reserve, a player
     *                               may take it
     * @throws IllegalArgumentException if {@code amount < 1} or {@code maxRemainingPerRequest < 1}
     */
    public RequestResult request(BlockPos outputPos, ItemKey key, int amount, int maxRemainingPerRequest,
            StockAccess access) {
        // ANY never raises a question, so there is always a result to unwrap: an output's redstone request and a
        // GameTest ask nobody, and a rule's reserve refuses them at the queue rather than in a dialog.
        return request(outputPos, key, amount, maxRemainingPerRequest, access, RequestAcknowledgement.ANY).result()
                .orElseThrow();
    }

    /**
     * Server: {@link #request(BlockPos, ItemKey, int, int, StockAccess)} that may <b>ask first</b> (M15 part 2,
     * issue #3): when the click crosses a boundary a stock keeper set and {@code acknowledged} does not already cover
     * it, nothing at all is requested and the question is returned instead ({@link TerminalRequestOutcome}).
     * <p>
     * The question is measured from the <b>same snapshot</b> the order would be started against — one walk of the
     * aisle's patterns, one availability lookup, one set of levels — so what the player is told and what then happens
     * cannot differ, and a click costs that snapshot once rather than twice ({@code docs/warehouse-system.md} §3.6.6).
     * <p>
     * <b>The part that has to be made is a whole chain</b> (M20, issue #4, ADR-032). It is worked out here, from that
     * same snapshot: either every step of it becomes an ordinary production order <b>in this tick</b>, children first,
     * each naming the parent supply line it feeds ({@link #startProductionPlan}), or nothing is created at all and the
     * click is refused with a reason that <b>names the item</b> in the way ({@link RequestResult#refusal()},
     * {@link RequestResult#about()}). There is no window in between, which is why a chain that cannot be finished never
     * gets as far as a crane trip. A chain of one step is an ordinary single-level order and behaves exactly as it did
     * before M20.
     *
     * @param acknowledged what the player has already accepted; {@link RequestAcknowledgement#ANY} asks nothing and
     *                     {@link RequestAcknowledgement#NONE} is a plain click
     */
    public TerminalRequestOutcome request(BlockPos outputPos, ItemKey key, int amount, int maxRemainingPerRequest,
            StockAccess access, RequestAcknowledgement acknowledged) {
        return request(outputPos, key, amount, maxRemainingPerRequest, access, acknowledged, RequestScope.CLICK);
    }

    /**
     * Server: {@link #request(BlockPos, ItemKey, int, int, StockAccess, RequestAcknowledgement)} made for a
     * {@link RequestScope} (M23, issue #19, ADR-036).
     * <p>
     * <b>A portion of a list order is not a new kind of request.</b> It takes this very method, with the same
     * clamping, the same merging (ADR-020), the same reserves, maxima, filters, priorities, chains, safety stop and
     * full-destination back-off; nothing downstream can tell it from a click. The scope changes exactly two things,
     * both in {@code core.terminal}: whether a request that would have items <b>made</b> has to be agreed to first
     * ({@link RequestConfirmation#required(RequestScope)} — a list order starts production while nobody is at the
     * terminal), and whether an answer has to name that number
     * ({@link RequestAcknowledgement#covers(RequestConfirmation, RequestScope)}).
     * <p>
     * It also hands the measured cost back on success ({@link TerminalRequestOutcome#cost()}), because a list order
     * has to spend its consent budget down by what the portion really cost. That measurement is the question this
     * method takes anyway whenever {@code acknowledged} is not {@link RequestAcknowledgement#ANY}, so it is free.
     */
    public TerminalRequestOutcome request(BlockPos outputPos, ItemKey key, int amount, int maxRemainingPerRequest,
            StockAccess access, RequestAcknowledgement acknowledged, RequestScope scope) {
        Objects.requireNonNull(outputPos, "outputPos");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(acknowledged, "acknowledged");
        Objects.requireNonNull(scope, "scope");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (level == null || level.isClientSide || isRemoved() || layout == null || !isOutputStation(layout, outputPos))
            return TerminalRequestOutcome.of(RequestResult.rejected(RequestRejection.NO_CONTROLLER));
        requests.setMaxOpenRequests(configuredMaxOpenRequests());
        requests.setMaxOpenRequestsPerDestination(Math.max(RequestQueue.MIN_OPEN_REQUESTS,
                WareworksConfig.maxOpenRequestsPerOutput()));
        // What the aisle really holds, and what its production patterns could still make of what it holds. A request
        // may ask for both: the stock part is served at once, the produced part as it arrives through a warehouse
        // input, by ordinary RETRIEVE jobs serving this very request (§3.5, ADR-024).
        StockLevels levels = stockLevelsOf(key);
        long inStock = levels.available();
        // What this taker may have of that stock: everything for a player, everything above a rule's reserve for the
        // warehouse's own automation (M15). Without a rule for the key the two are the same number.
        long claimable = stockRules().availableTo(access, key, inStock);
        // One pass over the aisle's patterns for every production question this request asks — what could be made, at
        // which station, and what its ingredients are still worth. Asking each of them separately resolved every
        // production station's block entity again per click (§3.5.2).
        List<AislePattern> patterns = aislePatterns();
        // The ingredients are governed by the same reserve as the stock (M15 review fix): a rule's reserve holds items
        // back from the warehouse's own automation, and spending them as the ingredients of an order this very request
        // starts would be exactly that — automation taking the reserved items, one step removed. Measured once and
        // used for both questions, so what is promised and what is then ordered agree.
        ToLongFunction<ItemKey> ingredients = StockAvailability.of(stockRules(), access, availabilityLookup());
        // Everything beyond what this taker may claim out of the racks has to be made, and the WHOLE chain that would
        // make it is worked out here, once, from this very snapshot (M20, issue #4, ADR-032). Either every step of it
        // can be created or the click is refused with a reason that names the item in the way — nothing moves in
        // between, so the crane never starts carrying logs for a chest the aisle could not have finished.
        ProductionPlanInput<ItemKey, RackPosition> planInput = productionPlanInput(patterns, ingredients);
        Optional<ProductionPlanResult<ItemKey, RackPosition>> planned = planProduction(planInput, key,
                amount - claimable);
        ProductionPlan<ItemKey, RackPosition> plan = planned.flatMap(ProductionPlanResult::plan).orElse(null);
        // Decision 3: a redstone port may start a chain, but only one at a time. A lever is an unattended, repeating
        // trigger, so the M17 "one open request at a time" rule is extended to plans — the stock part of the request is
        // served as usual, the second chain is not started.
        boolean planBusy = plan != null && !plan.isSingleLevel() && access == StockAccess.AUTOMATION
                && hasOpenProductionPlan(outputPos);
        if (planBusy)
            plan = null;
        // What this click would cross, measured over that very plan. Nothing is promised until it is covered.
        // The measurement is kept: a list order spends its consent budget down by what the portion really cost (M23).
        RequestConfirmation<ItemKey> measured = null;
        if (!acknowledged.any()) {
            RequestConfirmation<ItemKey> question = confirmationFrom(key, amount, levels, plan, ingredients);
            if (!acknowledged.covers(question, scope))
                return TerminalRequestOutcome.asking(question);
            measured = question;
        }
        // What the queue may promise beyond the racks is what the plan really offers, not what a pattern could make in
        // the abstract: the plan is the only thing that knows whether the chain behind it holds.
        long producible = plan == null ? 0L : plan.rootPromise();
        RequestQueue.AddResult<ItemKey, BlockPos> added = requests.add(key, amount, outputPos.immutable(),
                StockAvailability.forRequest(stockRules(), access,
                        candidate -> candidate.equals(key) ? inStock : availableStock(candidate), key, producible),
                maxRemainingPerRequest);
        if (added.request().isEmpty()) {
            return TerminalRequestOutcome.of(switch (added.rejection().orElseThrow()) {
                case QUEUE_FULL -> RequestResult.rejected(RequestRejection.QUEUE_FULL);
                case DESTINATION_FULL -> RequestResult.rejected(RequestRejection.OUTPUT_FULL);
                case NOTHING_AVAILABLE -> nothingAvailable(key, patterns, access, planned.orElse(null), planBusy);
                case REQUEST_FULL -> RequestResult.rejected(RequestRejection.REQUEST_FULL);
            });
        }
        RetrievalRequest<ItemKey, BlockPos> accepted = added.request().get();
        int granted = added.accepted();
        // Everything beyond what this taker may claim from the racks has to be made, not fetched. Measured against
        // the claimable amount rather than the whole stock, so items a reserve holds back are not silently counted as
        // served (M15).
        int fromProduction = (int) Math.max(0L, granted - claimable);
        // A pattern makes whole runs, so an order may yield more than was asked for; that surplus simply lands in
        // stock. What this request waits for — and what it gets back if the order fails — is never more than what it
        // asked for, which the order records as its promise (§3.5.3).
        ProductionPlan<ItemKey, RackPosition> creating = fromProduction > 0 && plan != null
                ? planFor(planInput, key, fromProduction, plan) : null;
        int producing = creating == null ? 0 : startProductionPlan(creating, accepted.id());
        if (producing < fromProduction) {
            // No order could be started after all, or a smaller one: give the request back what will never be made,
            // instead of leaving it waiting for items nobody produces.
            reduceRequest(accepted.id(), fromProduction - producing);
            granted -= fromProduction - producing;
        }
        if (granted < 1)
            return TerminalRequestOutcome.of(nothingAvailable(key, patterns, access, planned.orElse(null), planBusy));
        setChanged();
        markChunkKeepDirty(); // M19: an open request is work
        // A click the racks served only part of keeps the plan refusal that says why the rest could not be made (M20
        // review fix): "Oak Log is missing" takes the status row from "Requested Chest x2" then, because that is the half
        // a player can act on, and it would otherwise be computed and thrown away in the same tick.
        ProductionPlanResult<ItemKey, RackPosition> shortfall = granted < amount ? planned.orElse(null) : null;
        return TerminalRequestOutcome.of(RequestResult.accepted(requests.get(accepted.id()).orElse(accepted), granted,
                added.merged(), producing, shortfall == null ? Optional.empty() : shortfall.refusal(),
                shortfall == null ? Optional.empty() : shortfall.about()), measured);
    }

    /**
     * The plan for exactly {@code needed} result items: {@code offered} itself when that is what it promises, and
     * otherwise the same chain worked out again for the smaller amount.
     * <p>
     * The queue may grant less than the plan offered — a merge cap, or another station's share of the item — and a chain
     * for more than anybody is waiting for would hand a machine ingredients for items nobody asked for. Re-walking is
     * free of new observations: it uses the same input, whose budget answers out of the snapshot the click was decided
     * from, and fewer runs of the ordered item can never need more ingredients or more steps than the plan that already
     * fitted. A smaller plan that unexpectedly does not hold leaves the whole production part unstarted and refunded,
     * never half of it.
     */
    private @Nullable ProductionPlan<ItemKey, RackPosition> planFor(ProductionPlanInput<ItemKey, RackPosition> input,
            ItemKey key, long needed, ProductionPlan<ItemKey, RackPosition> offered) {
        if (needed >= offered.rootPromise())
            return offered;
        return planProduction(input, key, needed).flatMap(ProductionPlanResult::plan).orElse(null);
    }

    /** An availability that answers 0 for every key: for a request that cannot spend an ingredient at all. */
    private static final ToLongFunction<ItemKey> NOTHING_AVAILABLE = key -> 0L;

    /**
     * The refusal for a click nothing of {@code key} could be promised for: the coarse reason a station's goggles
     * remember, plus the <b>plan refusal and the item it is about</b> when it was a chain that could not be planned
     * (M20, ADR-032).
     * <p>
     * That precise part is the point of the feature: before M20 ordering a chest with no planks answered "not in stock"
     * about the chest, and now the same click can answer {@code MISSING_INGREDIENT} about three oak logs.
     *
     * @param planned  what the planner answered, or {@code null} when nothing was to be produced at all
     * @param planBusy whether a chain was possible but this port already has one open (decision 3)
     */
    private RequestResult nothingAvailable(ItemKey key, List<AislePattern> patterns, StockAccess access,
            @Nullable ProductionPlanResult<ItemKey, RackPosition> planned, boolean planBusy) {
        PlanRefusal refusal = planned == null ? null : planned.refusal().orElse(null);
        RequestRejection reason = nothingAvailableReason(key, patterns, access, refusal, planBusy);
        if (refusal == null)
            return RequestResult.rejected(reason);
        return RequestResult.rejected(reason, refusal, planned.about().orElse(key));
    }

    /**
     * Why nothing of {@code key} could be promised. A stock rule's reserve ({@link RequestRejection#RESERVED}), a
     * stopped item ({@link RequestRejection#PRODUCTION_PAUSED}) and a full production order queue
     * ({@link RequestRejection#PRODUCTION_BUSY}) are told apart from a plain "not in stock", because the cure is a
     * different one in each case: the items may all be there, and what the player has to do is lower a reserve, look at
     * a machine or wait for an order rather than go looking for an item the warehouse is not missing.
     * <p>
     * The reserve answers for both ways it can refuse automation: the requested item is in the racks and held back, or
     * the item could be <b>made</b> and it is the ingredients that are held back.
     */
    private RequestRejection nothingAvailableReason(ItemKey key, List<AislePattern> patterns, StockAccess access,
            @Nullable PlanRefusal refusal, boolean planBusy) {
        // The safety stop comes first and for everyone: waiting does not help and no reserve is in the way — somebody
        // has to look at a machine (ADR-027, extended by ADR-032).
        if (refusal == PlanRefusal.PAUSED)
            return RequestRejection.PRODUCTION_PAUSED;
        // A reserve is checked next and only for the warehouse's own automation: the items are there, they are just
        // not for it (M15). A player is never held back, so they can never see this reason.
        if (access == StockAccess.AUTOMATION) {
            if (availableStock(key) > 0 && availableTo(access, key) == 0)
                return RequestRejection.RESERVED;
            // The same answer when it is the ingredients that are reserved: without a reserve this aisle could make
            // the item, so "not in stock" would send a player looking for something the warehouse is not missing.
            if (producibleAmount(key, patterns, availabilityLookup()) > 0)
                return RequestRejection.RESERVED;
        }
        // A chain that could not be created because the orders it needs do not exist yet — no free slots, or a port
        // that already has one plan open — is the same answer as a full order queue: wait, or give one up. A chain that
        // is longer or costlier than the configuration allows is <b>not</b> this: waiting would not help, so it keeps
        // the plain "not in stock" until the precise reason reaches the player's own screen.
        if (planBusy || refusal == PlanRefusal.ORDERS_BUSY)
            return RequestRejection.PRODUCTION_BUSY;
        if (!productionOrders.isFull())
            return RequestRejection.NOT_IN_STOCK;
        for (AislePattern candidate : patterns) {
            if (candidate.pattern().produces(key))
                return RequestRejection.PRODUCTION_BUSY;
        }
        return RequestRejection.NOT_IN_STOCK;
    }

    private boolean isOutputStation(WarehouseLayout current, BlockPos pos) {
        Optional<RackPosition> rack = resolveRack(current, pos);
        if (rack.isEmpty() || !level.isLoaded(pos))
            return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof WarehouseMember member && !blockEntity.isRemoved()
                && member.locationKind() == LocationKind.OUTPUT
                && member.isAlignedWith(current.branch(rack.get().branch()), rack.get().side());
    }

    /**
     * Cancels the requests whose destination is no aligned output of this aisle any more although it is loaded (e.g. an
     * output removed before the controller ticked, so it never became a record), or lies outside the aisle. Requests for
     * unloaded destinations are kept. One block entity lookup per distinct destination.
     *
     * @return whether a request was cancelled
     */
    private boolean pruneRequests(WarehouseLayout current) {
        if (requests.isEmpty())
            return false;
        boolean cancelled = false;
        Set<BlockPos> checked = new HashSet<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : requests.requests()) {
            BlockPos destination = request.destination();
            if (!checked.add(destination))
                continue;
            boolean outside = !current.isRackPosition(destination);
            if (outside || (level.isLoaded(destination) && !isOutputStation(current, destination)))
                cancelled |= cancelRequestsFor(destination);
        }
        if (cancelled)
            markChunkKeepDirty(); // M19: what the aisle has to do changed
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
     * The stock rules that govern this aisle (M15, issue #3): the controller's own copy of what the warehouse stock
     * keepers of the aisle hold, in a stable order. Everything that gates item movement asks this copy and never a
     * keeper's block entity, so nothing can be stored past a maximum, and nothing reserved handed out, while a
     * keeper's chunk happens to be unloaded (the M8 cold-cache lesson).
     * <p>
     * The copy is saved with this controller and refreshed whenever a keeper joins, changes or leaves
     * ({@link AisleStockRules}), so an aisle without keepers answers neutrally ({@code Long.MAX_VALUE} of headroom, no
     * reserve) and behaves exactly as it did before M15.
     */
    public StockRules<ItemKey> stockRules() {
        return stockRules.rules();
    }

    /**
     * What this warehouse knows about one item right now, as a stock rule is judged against it (M15): what the racks
     * hold, what a transport job is carrying in, what an open production order is still expected to bring back, and
     * what a new request may still claim.
     * <p>
     * Four counter reads, one of them a pass over the request queue ({@link #availableStock}). Asked per rule of a
     * keeper that is being looked at or whose screen is open, never per candidate of a planning run.
     */
    public StockLevels stockLevelsOf(ItemKey key) {
        Objects.requireNonNull(key, "key");
        return new StockLevels(stock.count(key), dispatch.reservations().reservedCapacityFor(key),
                productionOrders.outstandingResult(key), availableStock(key));
    }

    /**
     * {@link #stockLevelsOf(ItemKey)} for a caller that has already collected what the open requests owe
     * ({@link #remainingRequestedByKey()}), i.e. one that asks about many keys in the same pass — a terminal's stock
     * snapshot, or this controller's own rule tick.
     * <p>
     * That turns the one expensive term into a map lookup: every other counter is O(1) or bounded by the open
     * production orders, so asking for a whole aisle's rules costs one pass over the queue instead of one per key.
     *
     * @param openRequestRemaining what the open requests for {@code key} still wait for, 0 when none do
     */
    public StockLevels stockLevelsOf(ItemKey key, long openRequestRemaining) {
        Objects.requireNonNull(key, "key");
        long promised = Math.max(0L, openRequestRemaining) + productionOrders.outstandingIngredient(key);
        return new StockLevels(stock.count(key), dispatch.reservations().reservedCapacityFor(key),
                productionOrders.outstandingResult(key),
                dispatch.reservations().availableStock(key, stock.count(key), promised));
    }

    /**
     * What the aisle's rule set says about the rule the warehouse stock keeper at {@code keeperPos} holds at
     * {@code ruleIndex} of its own configured rules (empty rows skipped) — the one answer a keeper's screen, its lamp
     * and its goggles all read.
     * <p>
     * It is answered <b>here</b> and not in the keeper, because only this copy knows the whole aisle: whether an
     * earlier rule already governs the same item ({@link StockRuleStatus#SHADOWED}) and whether the rule is beyond
     * {@code maxStockRules} ({@link StockRuleStatus#INERT}). A keeper this controller does not hold rules for answers
     * {@link StockRuleStatus#NO_WAREHOUSE}.
     */
    public StockRuleStatus stockRuleStatus(BlockPos keeperPos, int ruleIndex) {
        Objects.requireNonNull(keeperPos, "keeperPos");
        if (ruleIndex < 0)
            return StockRuleStatus.NO_WAREHOUSE;
        List<StockRuleStatus> statuses = stockRuleStatuses(keeperPos);
        return ruleIndex < statuses.size() ? statuses.get(ruleIndex) : StockRuleStatus.NO_WAREHOUSE;
    }

    /**
     * What the aisle's rule set says about <b>all</b> the rules of the keeper at {@code keeperPos}, in that keeper's own
     * row order (empty rows skipped) — one answer for its lamp, its comparator, its goggles and its screen.
     * <p>
     * This is the batched form, and the one every caller should use: it resolves the keeper's rack and its offset once
     * instead of per rule, and it reads the aisle's evaluation, which is computed <b>once per tick</b> and costs one
     * pass over the request queue for the whole aisle ({@link #stockRuleEvaluations()}). Asking per rule walked that
     * queue again for every single rule (M15 review fix).
     * <p>
     * A keeper this controller holds no rules for answers an empty list, which its caller reports as
     * {@link StockRuleStatus#NO_WAREHOUSE}: only this copy knows the whole aisle, i.e. whether an earlier rule already
     * governs the same item ({@link StockRuleStatus#SHADOWED}) and whether a rule is beyond {@code maxStockRules}
     * ({@link StockRuleStatus#INERT}).
     */
    public List<StockRuleStatus> stockRuleStatuses(BlockPos keeperPos) {
        Objects.requireNonNull(keeperPos, "keeperPos");
        if (level == null || level.isClientSide || layout == null)
            return List.of();
        Optional<RackPosition> rack = rackAt(keeperPos);
        if (rack.isEmpty())
            return List.of();
        OptionalInt offset = stockRules.offsetOf(rack.get());
        if (offset.isEmpty())
            return List.of();
        int start = offset.getAsInt();
        int count = stockRules.ruleCountAt(rack.get());
        List<StockRuleEvaluation<ItemKey>> evaluations = stockRuleEvaluations();
        List<StockRuleStatus> statuses = new ArrayList<>(count);
        for (int index = start; index < start + count; index++)
            // Beyond the flattened set only when StockRules truncated it at its hard bound, which is the same "applies
            // nothing until the cap is raised" the rule cap produces. The displayed status, not the counted one: this
            // is what a player is shown, so a paused rule says "paused" here (M15 part 2).
            statuses.add(index < evaluations.size() ? evaluations.get(index).displayStatus() : StockRuleStatus.INERT);
        return List.copyOf(statuses);
    }

    /**
     * Server: whether the rule the warehouse stock keeper at {@code keeperPos} holds at {@code ruleIndex} of its own
     * configured rules is the one that <b>governs</b> {@code key} in this aisle (M15 part 2, issue #3).
     * <p>
     * This is the question a keeper asks before it lets an edit lift a rule's safety stop: a pause is held per item, and
     * only the row that really applies the numbers for that item may re-arm an automatic order into the machine that
     * swallowed a batch (M15 review fix).
     * <p>
     * It is answered from the rule set alone — no levels, no evaluation, nothing cached — because it is asked in the
     * middle of an edit, and building the per-tick evaluation there would freeze levels that the same tick still
     * changes.
     */
    public boolean stockRuleGoverns(BlockPos keeperPos, int ruleIndex, ItemKey key) {
        Objects.requireNonNull(keeperPos, "keeperPos");
        Objects.requireNonNull(key, "key");
        if (level == null || level.isClientSide || layout == null || ruleIndex < 0)
            return false;
        Optional<RackPosition> rack = rackAt(keeperPos);
        if (rack.isEmpty())
            return false;
        OptionalInt offset = stockRules.offsetOf(rack.get());
        if (offset.isEmpty() || ruleIndex >= stockRules.ruleCountAt(rack.get()))
            return false;
        OptionalInt governing = stockRules.rules().governingIndexOf(key);
        return governing.isPresent() && governing.getAsInt() == offset.getAsInt() + ruleIndex;
    }

    /**
     * Every rule of this aisle with its status and the levels it was judged against — <b>one</b> evaluation per tick,
     * reused by the rule tick, every keeper's lamp and comparator, its goggles and its open screen.
     * <p>
     * The one expensive term is what the open requests owe, which {@link #remainingRequestedByKey()} collects in a
     * single pass over the queue; everything else is O(1) per governing rule, and a shadowed, inert or unfinished rule
     * is never measured at all ({@code StockRules#evaluate}). Judging the same rule set several times in one tick — once
     * for the counts and once per keeper — walked that queue once per rule instead (M15 review fix).
     * <p>
     * The cache is scoped to the current game tick and is display state only: what gates item movement asks
     * {@link #storeHeadroom} and {@link #availableTo} directly, which always read the live counters.
     */
    private List<StockRuleEvaluation<ItemKey>> stockRuleEvaluations() {
        if (level == null || level.isClientSide)
            return List.of();
        long now = level.getGameTime();
        StockRules<ItemKey> rules = stockRules.rules();
        // The rule set and the restock outcomes are compared by identity, not by value: every refresh that really
        // changed something builds a new one, so an edit — or an order started in this very tick — is never answered
        // out of the cache.
        if (ruleEvaluationTick == now && ruleEvaluationOf == rules && ruleEvaluationRestock == restockOutcomes)
            return ruleEvaluations;
        Map<ItemKey, Long> promised = requests.remainingByKey();
        List<StockRuleEvaluation<ItemKey>> evaluated =
                rules.evaluate(key -> stockLevelsOf(key, promised.getOrDefault(key, 0L)));
        if (!restockOutcomes.isEmpty()) {
            List<StockRuleEvaluation<ItemKey>> overlaid = new ArrayList<>(evaluated.size());
            // Only onto a rule that really governs its item: the outcomes are keyed by item, and PAUSED outranks every
            // other status, so a shadowed or inert duplicate row for a paused item would hide its own "an earlier rule
            // already governs this" warning and offer a resume click for the other row's pause (M15 review fix).
            for (StockRuleEvaluation<ItemKey> evaluation : evaluated)
                overlaid.add(evaluation.withRestock(evaluation.governs()
                        ? restockOutcomes.getOrDefault(evaluation.key(), RestockOutcome.NOT_GOVERNING)
                        : RestockOutcome.NOT_GOVERNING));
            evaluated = List.copyOf(overlaid);
        }
        ruleEvaluations = evaluated;
        ruleEvaluationTick = now;
        ruleEvaluationOf = rules;
        ruleEvaluationRestock = restockOutcomes;
        return ruleEvaluations;
    }

    /**
     * The evaluations of the rules the keeper at {@code keeperPos} holds, in that keeper's own row order (empty rows
     * skipped) — one answer for its lamp, its comparator, its goggles and its screen.
     * <p>
     * This is the batched form and the one every caller should use: it resolves the keeper's rack and its offset once
     * instead of per rule, and it reads the aisle's evaluation, which is computed <b>once per tick</b> for the whole
     * aisle ({@link #stockRuleEvaluations()}). An evaluation carries the rule, its unrefined status (what the rule
     * <i>counts as</i>), the levels it was judged against and what restocking is doing about it, so a caller needs no
     * second lookup for any of them.
     * <p>
     * A keeper this controller holds no rules for answers an empty list, which its caller reports as
     * {@link StockRuleStatus#NO_WAREHOUSE}: only this copy knows the whole aisle.
     */
    public List<StockRuleEvaluation<ItemKey>> stockRuleViewsAt(BlockPos keeperPos) {
        Objects.requireNonNull(keeperPos, "keeperPos");
        if (level == null || level.isClientSide || layout == null)
            return List.of();
        Optional<RackPosition> rack = rackAt(keeperPos);
        if (rack.isEmpty())
            return List.of();
        OptionalInt offset = stockRules.offsetOf(rack.get());
        if (offset.isEmpty())
            return List.of();
        int start = offset.getAsInt();
        int count = stockRules.ruleCountAt(rack.get());
        List<StockRuleEvaluation<ItemKey>> evaluations = stockRuleEvaluations();
        List<StockRuleEvaluation<ItemKey>> views = new ArrayList<>(count);
        for (int index = start; index < start + count && index < evaluations.size(); index++)
            views.add(evaluations.get(index));
        return List.copyOf(views);
    }

    /**
     * Server: the warehouse stock keeper at {@code rack} was loaded, edited or removed
     * ({@link WarehouseRegistry#stockRulesChanged}). Its rules are re-read <b>at once</b> rather than on the next
     * tick, so the plan and the request that follow in the same tick already obey the new rule.
     */
    void onStockRulesChanged(RackPosition rack) {
        boolean changed = readStockRulesAt(rack);
        // A rule a player deleted takes its pause with it, which is one of the two ways to resume (M15 part 2).
        boolean pruned = pruneStockPauses();
        // ... and a pause that is gone must take its lamp with it in the same tick (M20 review fix). Waiting for the next
        // rule pass left a station burning for a stop nothing backed up, and a save inside that window made it permanent.
        if (pruned)
            refreshProductionStops();
        if (changed || pruned)
            setChanged();
    }

    /**
     * Re-reads every keeper of the aisle and drops the copies of racks that no longer hold an aligned keeper. Runs on
     * the first tick after a load and on every re-link check ({@code geometryRefreshTicks}), always as a backstop for a
     * keeper that was edited while this controller was unloaded.
     * <p>
     * The racks it visits are the ones this copy already holds rules for <b>plus</b> the aisle's keeper records, and
     * every one of them is judged by {@link #readStockRulesAt}, i.e. by what really stands there. A rack is therefore
     * only ever dropped on the evidence of a loaded block, never because a membership record happens to be missing:
     * after an aisle was replaced the records are gone for a moment, and reading that as "no rule" would store past a
     * maximum and hand out a reserve, which nothing can undo (M15 review fix).
     */
    private void refreshAllStockRules() {
        if (layout == null)
            return;
        boolean changed = stockRules.setCap(WareworksConfig.maxStockRules());
        Set<RackPosition> racks = new TreeSet<>(RackPosition.ORDER);
        racks.addAll(stockRules.keepers());
        // Only when the aisle really has keepers: records(KEEPER) walks the whole member map, and a warehouse without
        // keepers must pay nothing for the question.
        if (membership.keeperCount() > 0) {
            for (LocationRecord record : membership.records(LocationKind.KEEPER))
                racks.add(record.position());
        }
        for (RackPosition rack : racks)
            changed |= readStockRulesAt(rack);
        changed |= pruneStockPauses();
        if (changed)
            setChanged();
    }

    /**
     * Reads the rules of one keeper into the copy.
     * <p>
     * A position whose chunk is <b>not loaded</b> keeps the rules it was last read with — that is the whole point of
     * saving the copy with the controller: a maximum must keep capping and a reserve must keep holding back while the
     * keeper sleeps. A position that <b>is</b> loaded and holds no keeper facing this aisle any more loses its rules,
     * because then the keeper really is gone as far as this warehouse is concerned. The alignment test is the same one
     * {@link #isOutputStation} uses, so a keeper a player turned away from the aisle stops governing at once instead of
     * being put back by the next edit or chunk load (M15 review fix).
     *
     * @return whether the aisle's rule set changed
     */
    private boolean readStockRulesAt(RackPosition rack) {
        if (level == null || level.isClientSide || layout == null)
            return false;
        Optional<BranchLayout> branch = layout.branchOf(rack);
        if (branch.isEmpty())
            // The aisle this rule was read on is gone, so the label names no block. Judging it by what a shorter
            // warehouse would point at would clear an unrelated keeper's lamp - or, since branch(i) bounds-checks
            // where rackPos did not, throw straight out of this block entity's tick (M21 review fix). The keeper
            // itself, if it is still standing, was quietened when its aisle went away, by the very pass that took it
            // (quietenKeepersOutside) - the last moment a layout that still named its block was in hand.
            return stockRules.remove(rack);
        BlockPos pos = branch.get().rackPos(rack);
        if (!level.isLoaded(pos))
            return false;
        if (level.getBlockEntity(pos) instanceof WarehouseStockKeeperBlockEntity keeper && !keeper.isRemoved()
                && keeper.isAlignedWith(branch.get(), rack.side()))
            return stockRules.set(rack, keeper.rules().rules());
        return dropStockRulesAt(rack);
    }

    /**
     * Forgets the rules of the keeper at {@code rack} and, while that block is loaded, lets it stop calling for items:
     * a keeper this warehouse no longer reads must not keep a comparator running for a rule nothing enforces
     * ({@link WarehouseStockKeeperBlockEntity#clearRuleState}, M15 review fix).
     *
     * @return whether the aisle's rule set changed
     */
    private boolean dropStockRulesAt(RackPosition rack) {
        boolean changed = stockRules.remove(rack);
        clearKeeperRuleState(layout, rack);
        return changed;
    }

    /**
     * Lets the keeper at {@code rack} of {@code current} drop its lamp and its comparator value, if that block is loaded
     * and really is a keeper. The layout is passed in because the caller may be the very code that is <b>taking it
     * away</b>: a rack position means a world position only through the layout it belongs to.
     * <p>
     * Never called from {@code invalidate()} or from a load: a chunk unload leaves a keeper exactly as it was
     * (ADR-013), and only a real loss of the warehouse quietens it.
     */
    private void clearKeeperRuleState(@Nullable WarehouseLayout current, RackPosition rack) {
        if (level == null || level.isClientSide || current == null)
            return;
        // A label whose aisle this warehouse does not have names no block, and the keeper it meant is not the one a
        // shorter warehouse would point at (M21 review fix).
        Optional<BlockPos> pos = current.worldPosOf(rack);
        if (pos.isPresent() && level.isLoaded(pos.get())
                && level.getBlockEntity(pos.get()) instanceof WarehouseStockKeeperBlockEntity keeper
                && !keeper.isRemoved())
            keeper.clearRuleState();
    }

    /**
     * Every keeper this controller holds rules for in {@code current} stops signalling, because that aisle is gone or
     * because this controller is — the counterpart of the rule tick, which is what switched those states on.
     */
    private void clearAllKeeperRuleStates(@Nullable WarehouseLayout current) {
        for (RackPosition rack : List.copyOf(stockRules.keepers()))
            clearKeeperRuleState(current, rack);
    }

    /**
     * Server: judges the rules of every loaded keeper of this aisle and lets it take its lamp state and its
     * comparator value from the result ({@code WarehouseStockKeeperBlockEntity#refreshRuleState}).
     * <p>
     * Runs every {@code stockRuleIntervalTicks} and costs <b>one</b> evaluation of the aisle's rule set
     * ({@link #stockRuleEvaluations()}) plus one block entity lookup per keeper — bounded by the number of rules and
     * keepers, never by the size of the aisle: the keepers are taken from the rule copy's own index, so the aisle's
     * member map is not walked at all. It writes a block state only when a lamp really changed and pushes a neighbour
     * update only when the comparator value really changed, so a crane delivering a stack of 64 produces one update
     * rather than 64. Enforcement does not depend on it: the maximum and the reserve are applied where a job is planned
     * and where a request is made.
     */
    private void tickStockKeepers(long now) {
        if (now < nextStockRuleTick)
            return;
        nextStockRuleTick = now + Math.max(1, WareworksConfig.stockRuleIntervalTicks());
        if (layout == null)
            return;
        refreshStockRuleCounts();
        tickRestocking(now);
        for (RackPosition rack : List.copyOf(stockRules.keepers())) {
            BlockPos pos = layout.rackPos(rack);
            if (!level.isLoaded(pos))
                continue;
            if (level.getBlockEntity(pos) instanceof WarehouseStockKeeperBlockEntity keeper && !keeper.isRemoved())
                keeper.refreshRuleState(this);
        }
        refreshProductionStops();
    }

    // --- home point ----------------------------------------------------------------------------------------------

    /**
     * The rack position of the home point this warehouse's crane really waits at, or empty while the <b>dock</b> is
     * home (M21, issue #1, ADR-034, {@code docs/stacker-crane.md} §4.7).
     * <p>
     * A warehouse has one crane, so it has one home: the <b>first</b> of its home points in
     * {@link RackPosition#ORDER}, which is the same one on every tick and after every restart. Everything else is
     * empty here and reported on the block instead — a second home point ({@link HomePointStatus#SECOND}), one the
     * crane cannot drive to ({@link HomePointStatus#UNREACHABLE}), a warehouse of one aisle
     * ({@link HomePointStatus#SINGLE_AISLE}) and a server that switched returning home off
     * ({@link HomePointStatus#SWITCHED_OFF}). In every one of those cases the dock is home, which is where a crane has
     * always started.
     */
    public Optional<RackPosition> homePoint() {
        return servingHomePoint;
    }

    /**
     * What this warehouse does with the home point at {@code pos} — the sentence its goggles show
     * ({@link HomePointStatus}). {@link HomePointStatus#NO_WAREHOUSE} when this controller has no aligned home point
     * there at all, which is also what a controller without a warehouse answers.
     */
    public HomePointStatus homePointStatusAt(BlockPos pos) {
        WarehouseLayout current = layout;
        if (current == null || level == null || level.isClientSide)
            return HomePointStatus.NO_WAREHOUSE;
        Optional<RackPosition> rack = rackAt(pos);
        if (rack.isEmpty() || membership.kindAt(rack.get()).orElse(null) != LocationKind.HOME)
            return HomePointStatus.NO_WAREHOUSE;
        List<LocationRecord> homes = membership.records(LocationKind.HOME);
        RackPosition first = homes.isEmpty() ? null : homes.getFirst().position();
        return statusOfHomePoint(current, rack.get(), first, first != null && craneCanDriveTo(current, first));
    }

    /**
     * Decides which home point this warehouse's crane uses, hands it to the dock and lets every home point show what
     * it is doing.
     * <p>
     * Runs on the first tick after a load, on every re-link check ({@code geometryRefreshTicks}) and whenever a home
     * point joins or leaves — never per tick. It costs one route question plus one block entity lookup per home point,
     * and a warehouse without any home point pays a single counter read ({@link AisleMembership#homePointCount}).
     * <p>
     * <b>Switching a lamp off is tracked by world position</b> ({@link #writtenHomePoints}), because that is the one
     * thing that still means the right block after a player has broken the aisle a rack position was named on.
     */
    private void refreshHomePoints() {
        if (level == null || level.isClientSide)
            return;
        WarehouseLayout current = layout;
        List<LocationRecord> homes = current == null || membership.homePointCount() == 0 ? List.<LocationRecord>of()
                : membership.records(LocationKind.HOME);
        RackPosition first = homes.isEmpty() ? null : homes.getFirst().position();
        boolean reachable = current != null && first != null && craneCanDriveTo(current, first);
        // Only a home point that is really used is handed over, and that is exactly the one whose own status says so:
        // an unreachable one is reported rather than obeyed, a warehouse of one aisle waits where it is, and a server
        // may have switched the whole thing off. Falling through to the SECOND home point in any of those cases would
        // make "at most one per crane" depend on the rails.
        HomePointStatus firstStatus = current == null || first == null ? null
                : statusOfHomePoint(current, first, first, reachable);
        servingHomePoint = firstStatus == HomePointStatus.SERVING ? Optional.of(first) : Optional.empty();
        linkedDockEntity().ifPresent(dock -> dock.setHomePoint(servingHomePoint.orElse(null)));
        Set<BlockPos> written = new HashSet<>();
        if (current != null) {
            for (LocationRecord record : homes) {
                Optional<BlockPos> pos = current.worldPosOf(record.position());
                if (pos.isEmpty())
                    continue;
                // Tracked even while its chunk sleeps, so a home point that leaves the warehouse in the meantime is
                // still switched off when it comes back into a controller that never wrote to it.
                written.add(pos.get().immutable());
                homePointAt(pos.get()).ifPresent(home -> home.applyStatus(
                        statusOfHomePoint(current, record.position(), first, reachable)));
            }
        }
        for (BlockPos pos : writtenHomePoints) {
            if (!written.contains(pos))
                homePointAt(pos).ifPresent(WarehouseHomePointBlockEntity::clearStatus);
        }
        writtenHomePoints.clear();
        writtenHomePoints.addAll(written);
    }

    /**
     * What one home point is doing, in the order the rules apply: "only one per crane" always holds, then the rails,
     * then the two reasons a warehouse does not send its crane anywhere at all ({@link HomeReturn}).
     */
    private HomePointStatus statusOfHomePoint(WarehouseLayout current, RackPosition rack, @Nullable RackPosition first,
                                              boolean reachable) {
        if (!rack.equals(first))
            return HomePointStatus.SECOND;
        if (!reachable)
            return HomePointStatus.UNREACHABLE;
        if (current.branchCount() <= 1)
            return HomePointStatus.SINGLE_AISLE;
        if (WareworksConfig.returnHomeIdleTicks() <= HomeReturn.OFF)
            return HomePointStatus.SWITCHED_OFF;
        return HomePointStatus.SERVING;
    }

    /**
     * Whether this warehouse's crane can really drive to {@code rack} — the question a member's goggles ask about the
     * aisle it stands on (M22, issue #2, {@link AisleAssignment.State#UNREACHABLE}).
     * <p>
     * A warehouse with no layout at all answers yes: it has nothing to say about reachability, and answering no would
     * paint every member of an unloaded warehouse gold. Asked from the crane's own point through the same
     * {@link dev.wareworks.core.warehouse.RouteTable#canDrive} the planner and the machine use, so a block, a plan and
     * a crane can never mean three different things by "it can get there".
     */
    public boolean craneCanReach(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        WarehouseLayout current = layout;
        return current == null || craneCanDriveTo(current, rack);
    }

    /**
     * Whether the crane could drive from where it stands to {@code rack} — the very question {@code CraneMotion}
     * answers, asked through {@link dev.wareworks.core.warehouse.RouteTable#canDrive} so that the lamp on the block and
     * the machine can never mean two different things by "it can get there".
     * <p>
     * A dock whose chunk is away is asked from position 0 of the aisle at the dock instead, which is where its crane
     * parks: an unload must not turn a working home point red. The same default catches a crane named on an aisle this
     * warehouse no longer has, which is the {@code pose.branch() < current.branchCount()} guard below.
     */
    private boolean craneCanDriveTo(WarehouseLayout current, RackPosition rack) {
        int from = RackPosition.FIRST_BRANCH;
        double fromX = 0.0;
        Optional<StackerCraneBlockEntity> dock = linkedDockEntity();
        if (dock.isPresent()) {
            CranePose pose = dock.get().craneState().pose();
            if (pose.branch() < current.branchCount()) {
                from = pose.branch();
                fromX = pose.x();
            }
        }
        return current.routes().canDrive(from, fromX, rack.branch(), rack.x());
    }

    private Optional<WarehouseHomePointBlockEntity> homePointAt(BlockPos pos) {
        if (level == null || level.isClientSide || !level.isLoaded(pos))
            return Optional.empty();
        return level.getBlockEntity(pos) instanceof WarehouseHomePointBlockEntity home && !home.isRemoved()
                ? Optional.of(home) : Optional.empty();
    }

    /**
     * Every home point this controller lit goes dark, because this warehouse is gone or this controller is — the
     * counterpart of {@link #refreshHomePoints}, which is what switched those lamps on. Needs no layout: the positions
     * are world positions.
     */
    private void clearAllHomePointStates() {
        for (BlockPos pos : List.copyOf(writtenHomePoints))
            homePointAt(pos).ifPresent(WarehouseHomePointBlockEntity::clearStatus);
        writtenHomePoints.clear();
        servingHomePoint = Optional.empty();
    }

    /**
     * Re-counts what the aisle's rules are doing for the goggle lines (M15, issue #3).
     * <p>
     * It judges the controller's <b>own copy</b>, not the keepers, so the numbers describe what the warehouse is
     * really enforcing — including the rules of a keeper whose chunk is not loaded, which still cap and still reserve.
     * An aisle without rules leaves the loop immediately and reads no counter at all; a rule that governs nothing
     * (shadowed, inert or without a number) never reaches {@code stockLevelsOf} either, which is
     * {@code StockRules#evaluate}'s own guarantee.
     */
    private void refreshStockRuleCounts() {
        if (stockRules.isEmpty()) {
            governingRules = 0;
            rulesBelowMinimum = 0;
            rulesAtMaximum = 0;
            return;
        }
        int governing = 0;
        int below = 0;
        int atMaximum = 0;
        for (StockRuleEvaluation<ItemKey> evaluation : stockRuleEvaluations()) {
            if (!evaluation.status().governs())
                continue;
            governing++;
            if (evaluation.status() == StockRuleStatus.BELOW_MINIMUM)
                below++;
            else if (evaluation.status() == StockRuleStatus.AT_MAXIMUM)
                atMaximum++;
        }
        governingRules = governing;
        rulesBelowMinimum = below;
        rulesAtMaximum = atMaximum;
    }

    /**
     * How many stock rules of this aisle really govern an item — the number the controller's goggles and the aisle
     * summary display show (M15, issue #3).
     * <p>
     * Derived state, refreshed by the rule tick every {@code stockRuleIntervalTicks} and never saved, so it can be a
     * tick or two behind what a keeper's screen shows and reads 0 for the first rule tick after a load. Nothing that
     * moves an item ever reads it: enforcement asks {@link #stockRules()} itself.
     */
    public int governingStockRuleCount() {
        return governingRules;
    }

    /** Governing rules of this aisle whose item the warehouse is short of ({@link #governingStockRuleCount()}). */
    public int stockRulesBelowMinimum() {
        return rulesBelowMinimum;
    }

    /** Governing rules of this aisle that stop their item from being stored ({@link #governingStockRuleCount()}). */
    public int stockRulesAtMaximum() {
        return rulesAtMaximum;
    }

    // --- automatic restocking (M15 part 2, issue #3) ---------------------------------------------------------------

    /**
     * Server: the warehouse's own restocking pass. Runs inside the rule tick, i.e. every
     * {@code stockRuleIntervalTicks} and never per tick, and starts <b>at most one</b> production order
     * ({@link RestockPlan}).
     * <p>
     * <b>A satisfied warehouse pays almost nothing for it.</b> The evaluation of the rules has already been made for
     * the counts and the lamps; if no governing rule is short of its minimum and no rule is paused, this method
     * returns before it resolves a single production station or builds an availability snapshot. Only a warehouse that
     * really is missing something walks its patterns — one block entity lookup per production station, the same method
     * a terminal request uses.
     * <p>
     * <b>The ingredients are measured against the reserve.</b> The availability handed to the planner is
     * {@link StockAvailability#of} for {@link StockAccess#AUTOMATION}, which is the same path a redstone request
     * already takes: an automatic order can never spend items a rule protects, whether it asks for them directly or
     * reaches them through a pattern.
     */
    private void tickRestocking(long now) {
        if (stockRules.isEmpty()) {
            clearRestockOutcomes();
            return;
        }
        List<StockRuleEvaluation<ItemKey>> evaluations = stockRuleEvaluations();
        boolean anyShort = false;
        for (StockRuleEvaluation<ItemKey> evaluation : evaluations) {
            if (evaluation.status() == StockRuleStatus.BELOW_MINIMUM && !stockPauses.containsKey(evaluation.key())) {
                anyShort = true;
                break;
            }
        }
        // Nothing to order, nothing already being made and nothing paused: every outcome would refine to the plain
        // status anyway, so the whole pass — patterns, availability snapshot, planner — is skipped and the overlay is
        // dropped. An order that is already running keeps the pass alive although no rule reads as short: its own
        // result counts towards the minimum ({@code StockLevels#pipeline()}), and "being made now" is exactly what
        // that rule has to say while it does.
        if (!anyShort && stockPauses.isEmpty() && productionOrders.openRestockCount() == 0) {
            clearRestockOutcomes();
            return;
        }
        RestockLimits limits = WareworksConfig.restockLimits();
        // Resolved once for the whole pass, and only when an order could actually come of it.
        List<AislePattern> patterns = anyShort && limits.enabled() ? aislePatterns() : List.of();
        Map<ItemKey, List<ProductionPattern<ItemKey>>> byResult = patternsByResult(patterns);
        ToLongFunction<ItemKey> available = patterns.isEmpty() ? key -> 0L
                : StockAvailability.of(stockRules(), StockAccess.AUTOMATION, availabilityLookup());
        List<RestockInput<ItemKey>> inputs = new ArrayList<>(evaluations.size());
        for (StockRuleEvaluation<ItemKey> evaluation : evaluations) {
            ItemKey key = evaluation.key();
            inputs.add(new RestockInput<>(evaluation.rule(), evaluation.levels(), evaluation.governs(),
                    stockPauses.containsKey(key), productionOrders.openRestockCountFor(key),
                    byResult.getOrDefault(key, List.of())));
        }
        productionOrders.setMaxOpenOrders(configuredMaxProductionOrders());
        RestockPlan<ItemKey> plan = RestockPlanner.plan(inputs, limits, productionOrders.openRestockCount(),
                productionOrders.isFull(), available);
        plan.order().ifPresent(decision -> startRestockOrder(decision, patterns, available));
        applyRestockOutcomes(plan);
    }

    /** The patterns of {@code patterns} grouped by the item they make, in aisle order. */
    private Map<ItemKey, List<ProductionPattern<ItemKey>>> patternsByResult(List<AislePattern> patterns) {
        if (patterns.isEmpty())
            return Map.of();
        Map<ItemKey, List<ProductionPattern<ItemKey>>> byResult = new LinkedHashMap<>();
        for (AislePattern candidate : patterns)
            byResult.computeIfAbsent(candidate.pattern().result().key(), key -> new ArrayList<>())
                    .add(candidate.pattern());
        return byResult;
    }

    /**
     * Starts the order a decision asks for: an ordinary production order with <b>no request behind it</b>
     * ({@code ProductionOrder#restock}). Nothing new is invented — the crane fetches the ingredients with the same
     * {@code SUPPLY} jobs, the machine works, and the result comes back through a warehouse input and is stored by an
     * ordinary {@code STORE} job.
     *
     * @return whether an order was really started
     */
    private boolean startRestockOrder(RestockDecision<ItemKey> decision, List<AislePattern> patterns,
            ToLongFunction<ItemKey> available) {
        if (level == null || layout == null || decision.pattern().isEmpty())
            return false;
        ProductionPattern<ItemKey> chosen = decision.pattern().get();
        RackPosition station = null;
        for (AislePattern candidate : patterns) {
            // Identity: the decision was made from exactly these pattern objects, in this pass.
            if (candidate.pattern() == chosen) {
                station = candidate.station();
                break;
            }
        }
        if (station == null)
            return false;
        // The planner's own run count, not a second derivation of it: it already bounded the runs by the rule's
        // headroom, by what one order may spend in ingredient items and by what the ingredients allow, and rounding the
        // amount up again here would order more than the rule may have (M15 review fix).
        int runs = Math.min(decision.runs(), ProduciblePlanner.runsPossible(chosen, available));
        if (runs < 1)
            return false;
        ProductionOrder<ItemKey, RackPosition> order = ProductionOrder.restock(UUID.randomUUID(), station, chosen,
                runs, UUID::randomUUID, level.getGameTime(), productionTimeoutTicks());
        if (!productionOrders.add(order))
            return false;
        setChanged();
        markChunkKeepDirty(); // M19: an automatic restock order is work like any other order
        return true;
    }

    /**
     * Keeps what the pass decided, for every surface that reports it. Outcomes that say nothing
     * ({@link RestockOutcome#NOT_GOVERNING}, {@link RestockOutcome#SATISFIED}) are left out, so a warehouse whose
     * rules are all met holds an empty map, and the map instance is only replaced when something really changed —
     * which is what keeps the per-tick evaluation cache valid.
     */
    private void applyRestockOutcomes(RestockPlan<ItemKey> plan) {
        Map<ItemKey, RestockOutcome> next = new LinkedHashMap<>();
        Map<ItemKey, ItemKey> missing = new LinkedHashMap<>();
        for (RestockDecision<ItemKey> decision : plan.decisions()) {
            if (decision.outcome() != RestockOutcome.NOT_GOVERNING && decision.outcome() != RestockOutcome.SATISFIED)
                next.put(decision.key(), decision.outcome());
            decision.missingIngredient().ifPresent(item -> missing.put(decision.key(), item));
        }
        if (!next.equals(restockOutcomes))
            restockOutcomes = Map.copyOf(next);
        if (!missing.equals(restockMissing))
            restockMissing = Map.copyOf(missing);
    }

    private void clearRestockOutcomes() {
        if (!restockOutcomes.isEmpty())
            restockOutcomes = Map.of();
        if (!restockMissing.isEmpty())
            restockMissing = Map.of();
    }

    /**
     * The ingredient the rule for {@code key} is waiting for a player to supply, or empty when it is not waiting for
     * one ({@link RestockOutcome#WAITING_FOR_INGREDIENTS}, M15 part 2). This is what the keeper's screen names.
     */
    public Optional<ItemKey> restockMissingIngredient(ItemKey key) {
        return Optional.ofNullable(restockMissing.get(Objects.requireNonNull(key, "key")));
    }

    /** What automatic restocking last decided about {@code key}; {@link RestockOutcome#NOT_GOVERNING} when nothing. */
    public RestockOutcome restockOutcomeOf(ItemKey key) {
        Objects.requireNonNull(key, "key");
        return restockOutcomes.getOrDefault(key, RestockOutcome.NOT_GOVERNING);
    }

    /**
     * {@code status} refined by what automatic restocking is doing about {@code key} — the one value every surface
     * shows ({@link RestockOutcome#refine}).
     * <p>
     * It takes the status rather than computing it, because the caller has just measured the levels itself and a
     * second, cached answer could be a tick out of date: a terminal row has to turn in the same tick the request that
     * emptied the reserve was accepted, not on the next one.
     */
    public StockRuleStatus refineStockRuleStatus(ItemKey key, StockRuleStatus status) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        return restockOutcomeOf(key).refine(status);
    }

    /**
     * Server: the <b>safety stop</b>. An order ended with ingredients already handed to a machine and no result, so the
     * warehouse stops making that item and waits for the player ({@link StockRulePause}).
     * <p>
     * <b>It covers every kind of order</b> (M20, ADR-032): a player's click, a redstone request, a step of a chain and a
     * rule's own refill alike. M15 armed it only for an automatic order because that was the only order the warehouse
     * repeated by itself; a chain repeats just as well, and rebuilding the same chain into the same broken machine is the
     * drain ADR-027 exists to stop. The one way back is a player's own action ({@link #resumeStockRule}) — never a timer.
     * <p>
     * Only the making stops: a rule keeps its maximum, keeps its reserve, and the keeper's comparator still calls for the
     * item, so a player's own farm goes on running. A second loss for the same item adds its unrecovered items to the
     * first pause rather than replacing it, because what a player has to be told is the whole cost.
     */
    private void pauseProduction(ItemKey key, StockRulePause.Cause cause, long unrecovered) {
        StockRulePause before = stockPauses.get(key);
        StockRulePause next = before == null ? new StockRulePause(cause, unrecovered)
                : new StockRulePause(before.cause(), before.unrecovered() + Math.max(0L, unrecovered));
        if (next.equals(before))
            return;
        stockPauses.put(key, next);
        // "Stop making it" has to mean the orders that are already out, too: a second batch may be on its way to the
        // very machine that swallowed the first — one more automatic order (M15 review fix) or, since M20, another
        // chain's step for the same item. Cancelling reroutes what the crane is still carrying back into storage
        // (cancelSupplyJobsOf), so only what a machine already took is lost, and a cancelled step takes the rest of its
        // own plan with it (onProductionOrderEnded).
        cancelOpenOrdersFor(key);
        // The overlay is rebuilt by the next pass; invalidating it here makes the paused state visible in this tick,
        // which is the tick a player watching the keeper sees the order fail in.
        restockOutcomes = withOutcome(key, RestockOutcome.PAUSED);
        refreshProductionStops();
        setChanged();
        Wareworks.LOGGER.info("The warehouse at {} stopped making {} ({}): {} ingredient items were not recovered",
                worldPosition, key, cause.name().toLowerCase(java.util.Locale.ROOT), next.unrecovered());
    }

    /**
     * Server: lets the warehouse make {@code key} again after a player has looked at their machine. This is the one way
     * back from the safety stop, whatever armed it — a rule's own order, a player's click, a redstone request or a step
     * of a chain (M20) — and it is deliberately a <b>player's</b> action: the warehouse cannot tell a fixed machine from
     * a broken one, and retrying by itself would feed the same machine a second batch. Never a timer.
     *
     * @return whether a pause was really lifted
     */
    public boolean resumeStockRule(ItemKey key) {
        Objects.requireNonNull(key, "key");
        if (level == null || level.isClientSide || stockPauses.remove(key) == null)
            return false;
        restockOutcomes = withoutOutcome(key);
        refreshProductionStops();
        setChanged();
        return true;
    }

    /**
     * Server: lets every loaded production station of this aisle show on its own block whether the safety stop is holding
     * one of the things it makes ({@code WarehouseProductionBlock#STOPPED}, M20, ADR-032).
     * <p>
     * <b>A warehouse that has never lost a batch pays almost nothing for it</b>: after one sweep per load
     * ({@link #stopsSwept}) it returns before resolving a single block entity while there is no pause and nothing lit.
     * The sweep itself, and every pass with something to say, costs one block entity lookup per production station of the
     * aisle — a handful, taken from the membership index rather than from any search — and each station writes a block
     * state only when its own lamp really changed, exactly as a stock keeper's does.
     * <p>
     * It runs where the stop changes ({@link #pauseProduction}, {@link #resumeStockRule},
     * {@link #onStockRulesChanged}) so a player sees the lamp in the tick they caused it, and once per rule pass, which is
     * what picks up a pattern that was edited afterwards and a controller that has just come back from a save.
     * <p>
     * <b>A station whose chunk is not loaded is remembered, not forgotten.</b> Its lamp cannot be read or written, so
     * dropping it from {@link #stoppedStations} would lose the only record that it may be burning; it stays in the set and
     * the next pass that can reach it decides.
     */
    private void refreshProductionStops() {
        if (level == null || level.isClientSide)
            return;
        if (stopsSwept && stockPauses.isEmpty() && stoppedStations.isEmpty())
            return;
        Set<BlockPos> shown = new LinkedHashSet<>();
        boolean complete = layout != null;
        if (layout != null) {
            for (LocationRecord record : productionStations()) {
                BlockPos pos = layout.rackPos(record.position());
                if (!level.isLoaded(pos)) {
                    // Nothing can be read or written here: keep a lamp this controller lit, and try again next pass.
                    complete = false;
                    if (stoppedStations.contains(pos))
                        shown.add(pos);
                    continue;
                }
                if (level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station && !station.isRemoved()
                        && station.refreshStoppedState(this))
                    shown.add(pos);
            }
        }
        for (BlockPos lit : stoppedStations) {
            if (!shown.contains(lit))
                clearProductionStop(lit);
        }
        stoppedStations.clear();
        stoppedStations.addAll(shown);
        stopsSwept |= complete;
    }

    /**
     * Every station this controller has lit stops showing the safety stop, because the aisle is gone or because this
     * controller is — the counterpart of {@link #refreshProductionStops}, exactly as
     * {@link #clearAllKeeperRuleStates} is the counterpart of the rule tick. Never called from a chunk unload, which
     * leaves a member exactly as it was (ADR-013).
     */
    private void clearAllProductionStops() {
        for (BlockPos lit : List.copyOf(stoppedStations))
            clearProductionStop(lit);
        stoppedStations.clear();
        // Whatever aisle comes next is a different set of stations, so it earns its own sweep (M20 review fix).
        stopsSwept = false;
    }

    /**
     * Puts out the stopped lamp of the block at {@code pos}, if a loaded production station is still standing there.
     * The position is a world position rather than a rack position on purpose: the caller may be the very code that is
     * taking the layout away, and a station that is no longer a member has no rack position at all any more.
     */
    private void clearProductionStop(BlockPos pos) {
        if (level == null || level.isClientSide || !level.isLoaded(pos))
            return;
        if (level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station && !station.isRemoved())
            station.clearStoppedState();
    }

    /**
     * Decides the lamp of the one production station at {@code pos} from this controller's pauses, and remembers the
     * answer in {@link #stoppedStations} — the single-station form of {@link #refreshProductionStops}, for a station that
     * has just joined the aisle. An unloaded or replaced block is left alone, exactly as a pass leaves it.
     */
    private void lightOrClearProductionStop(BlockPos pos) {
        if (level == null || level.isClientSide || !level.isLoaded(pos))
            return;
        if (!(level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station) || station.isRemoved())
            return;
        if (station.refreshStoppedState(this))
            stoppedStations.add(pos);
        else
            stoppedStations.remove(pos);
    }

    /**
     * Cancels <b>every</b> open order that is making {@code key}, whoever asked for it (M20, ADR-032, widening the M15
     * review fix that cancelled the automatic ones).
     * <p>
     * "The warehouse has stopped making this" cannot mean "except for the batches already on their way": the machine
     * that swallowed the last batch is the very machine those orders are feeding, and one of them may be a step of
     * another chain that would go on committing ingredients level by level. A request that was waiting for such an order
     * is given its promise back, so nobody waits for items nobody will make.
     * <p>
     * Each cancellation goes through {@link #onProductionOrderEnded}, so a sibling that had already delivered
     * ingredients adds its own loss to the pause — what a player has to be told is the whole cost — and a cancelled step
     * takes the rest of its plan with it. The recursion that follows from it ends after the last open order, because an
     * order is only ever cancelled once.
     */
    private void cancelOpenOrdersFor(ItemKey key) {
        if (level == null)
            return;
        long now = level.getGameTime();
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.open()) {
            if (order.result().equals(key))
                productionOrders.cancel(order.id(), now).ifPresent(this::onProductionOrderEnded);
        }
    }

    /** The pause holding the rule for {@code key}, or empty when it is not paused. */
    public Optional<StockRulePause> stockRulePause(ItemKey key) {
        return Optional.ofNullable(stockPauses.get(Objects.requireNonNull(key, "key")));
    }

    /** How many rules of this aisle the safety stop is holding — the count both goggle surfaces show. */
    public int pausedStockRuleCount() {
        return stockPauses.size();
    }

    /**
     * Forgets the pauses of items no rule governs any more — but only the ones a <b>rule</b> armed. A rule a player
     * deleted takes its pause with it, which is also one of the two ways to resume: writing the rule again starts from a
     * clean state.
     * <p>
     * <b>A pause armed by any other order is never forgotten by itself</b> (M20, ADR-032,
     * {@link StockRulePause.Cause#isRuleBorn()}). It has no rule it could belong to — the item may well be governed by
     * none at all, which is the normal case for an intermediate of a chain — so forgetting it here would let the very
     * next click rebuild the same chain into the same broken machine, which is the one thing the safety stop exists to
     * prevent. Such a pause is lifted only by a player ({@link #resumeStockRule}).
     *
     * @return whether anything was forgotten
     */
    private boolean pruneStockPauses() {
        if (stockPauses.isEmpty())
            return false;
        StockRules<ItemKey> rules = stockRules.rules();
        List<ItemKey> gone = new ArrayList<>();
        for (Map.Entry<ItemKey, StockRulePause> paused : stockPauses.entrySet()) {
            if (paused.getValue().isRuleBorn() && !rules.governsKey(paused.getKey()))
                gone.add(paused.getKey());
        }
        for (ItemKey key : gone) {
            stockPauses.remove(key);
            restockOutcomes = withoutOutcome(key);
        }
        return !gone.isEmpty();
    }

    /** {@link #restockOutcomes} with one more entry, as a new map (the cache compares by identity). */
    private Map<ItemKey, RestockOutcome> withOutcome(ItemKey key, RestockOutcome outcome) {
        if (restockOutcomes.get(key) == outcome)
            return restockOutcomes;
        Map<ItemKey, RestockOutcome> next = new LinkedHashMap<>(restockOutcomes);
        next.put(key, outcome);
        return Map.copyOf(next);
    }

    /** {@link #restockOutcomes} without one entry, as a new map. */
    private Map<ItemKey, RestockOutcome> withoutOutcome(ItemKey key) {
        if (!restockOutcomes.containsKey(key))
            return restockOutcomes;
        Map<ItemKey, RestockOutcome> next = new LinkedHashMap<>(restockOutcomes);
        next.remove(key);
        return Map.copyOf(next);
    }

    /**
     * How many items of {@code key} the given taker may still be promised: {@link #availableStock} for a
     * {@link StockAccess#PLAYER}, and what is left above a governing rule's reserve for
     * {@link StockAccess#AUTOMATION} (M15, issue #3). Without a rule for the key both are {@link #availableStock}.
     * <p>
     * {@link #availableStock} itself stays the warehouse's internal number — what the aisle really has to give. The
     * reserve is subtracted here, at the entry point of a request, and at the one place that entry point spends stock
     * on the taker's behalf: the ingredients of a production order the request starts ({@link StockAvailability#of} in
     * {@link #request}), so automation cannot reach a reserve through a pattern either.
     */
    public long availableTo(StockAccess access, ItemKey key) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(key, "key");
        return stockRules().availableTo(access, key, availableStock(key));
    }

    /**
     * How many more items of {@code key} this warehouse may still <b>store</b> ({@code PlannerInput#storeHeadroom},
     * M15, issue #3): the maximum of a governing rule, minus what is in the racks and what a transport job is
     * carrying in, plus what an open production order is still expected to bring back.
     * <p>
     * {@link Long#MAX_VALUE} when no rule governs the key, and then it costs a single map lookup: the three counters
     * are only read for an item a rule really caps, so a warehouse without rules pays nothing for the question.
     * <p>
     * The {@code + outstandingResult} allowance is the rule "the warehouse always takes back what it sent out for": a
     * pattern makes whole runs, so an order for 32 regularly comes back as 36, and without it the surplus would be
     * refused at the input and strand the order.
     */
    public long storeHeadroom(ItemKey key) {
        Objects.requireNonNull(key, "key");
        Optional<StockRule<ItemKey>> rule = stockRules().ruleFor(key);
        if (rule.isEmpty())
            return Long.MAX_VALUE;
        return rule.get().headroom(stock.count(key), dispatch.reservations().reservedCapacityFor(key),
                productionOrders.outstandingResult(key));
    }

    /**
     * Room an <b>intermediate</b> of a production plan has under its own maximum ({@link ProductionPlanInput#headroom()},
     * M20): the same numbers as {@link #storeHeadroom} <b>without</b> the "the warehouse always takes back what it sent
     * out for" allowance.
     * <p>
     * The allowance is right where it is used — a store must never strand the surplus of a run — but it is wrong as the
     * answer to "may this chain make four planks at a time", because it counts what <i>other</i> open orders are expected
     * to bring back as room. A click would then be refused with an idle aisle and accepted a minute later while an
     * unrelated order for the same intermediate happened to be in flight, which is not an answer a player can act on
     * (M20 review fix). Measured against the click alone, the question is the player's own cap and nothing else.
     * <p>
     * {@link Long#MAX_VALUE} when no rule governs the key, at the cost of a single map lookup.
     */
    public long productionRoomFor(ItemKey key) {
        Objects.requireNonNull(key, "key");
        Optional<StockRule<ItemKey>> rule = stockRules().ruleFor(key);
        if (rule.isEmpty())
            return Long.MAX_VALUE;
        return rule.get().headroom(stock.count(key), dispatch.reservations().reservedCapacityFor(key), 0L);
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
     * What the open <b>production orders</b> still have to take out of the racks, per item key, in one pass over the
     * orders ({@link ProductionOrders#outstandingIngredientsByKey()}).
     * <p>
     * The companion of {@link #remainingRequestedByKey()}, and needed by the same callers for the same reason: both
     * numbers are what {@link #availableStock} subtracts, so a surface that computes an available amount for many keys at
     * once has to subtract both or it advertises items that are already promised. A plan holds that promise open for the
     * whole chain rather than for one crane trip, which is what made the difference visible (M20 review fix).
     */
    public Map<ItemKey, Long> outstandingIngredientsByKey() {
        return productionOrders.outstandingIngredientsByKey();
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
        if (counted > 0) {
            setChanged();
            markChunkKeepDirty(); // M19: a delivered request may have been the last thing to do
        }
        return counted;
    }

    /** Server: removes request {@code id}; a crane job serving it is cancelled. */
    public Optional<RetrievalRequest<ItemKey, BlockPos>> cancelRequest(UUID id) {
        Optional<RetrievalRequest<ItemKey, BlockPos>> cancelled = requests.cancel(id);
        if (cancelled.isPresent()) {
            setChanged();
            onRequestsGone(List.of(cancelled.get())); // marks the chunk hold dirty (M19)
        }
        return cancelled;
    }

    private static int configuredMaxOpenRequests() {
        return Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequests());
    }

    // --- clipboard orders (M23, issue #19, ADR-036) ----------------------------------------------------------------

    /**
     * Server: from a terminal — "I have a clipboard order now, look in on me".
     * <p>
     * It is a hint and not a registration: the set is rebuilt from the world
     * ({@link #refreshListTerminals(WarehouseLayout)}), so a terminal that never calls this is found by the next
     * membership change anyway, and one that calls it without an order drops straight out again. That is what makes
     * the feature survive every order of block entity loading without a second saved list.
     */
    public void onListOrderStarted(BlockPos terminalPos) {
        if (terminalPos == null || level == null || level.isClientSide)
            return;
        listTerminalsDirty = true;
        // Due at once: the first pass of a fresh order belongs to the tick the player pressed Fetch in, not to the
        // next walk, so the crane is on its way while they are still looking at the screen.
        nextListTick = Long.MIN_VALUE;
    }

    /**
     * Looks in on the terminals that are working a clipboard order off ({@code docs/warehouse-system.md} §3.4.4).
     * <p>
     * Each terminal decides for itself whether a top-up pass is due and what it costs; this walk is one block entity
     * lookup per <b>known</b> list terminal, at most {@value #MAX_LIST_TERMINALS} of them and only every
     * {@value #LIST_WALK_INTERVAL_TICKS} ticks. A warehouse with no clipboard order in it does nothing at all here,
     * which is the whole reason the set exists.
     */
    private void tickListOrders(long now) {
        if (listTerminalsDirty) {
            listTerminalsDirty = false;
            refreshListTerminals(layout);
        }
        if (listTerminals.isEmpty())
            return;
        // A copy, because a pass may finish an order and take its terminal out of the set.
        for (BlockPos pos : List.copyOf(listTerminals)) {
            // An unloaded position is dropped without being looked at: resolving a block entity there would load the
            // chunk synchronously, which is exactly what this walk must never do (M23 review fix). The entry comes
            // back on the membership change a chunk load fires, so nothing is lost.
            if (!level.isLoaded(pos)) {
                listTerminals.remove(pos);
                continue;
            }
            WarehouseTerminalBlockEntity terminal = level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity be
                    ? be : null;
            if (terminal == null || terminal.isRemoved() || !terminal.tickListOrder(now))
                listTerminals.remove(pos);
        }
    }

    /**
     * Rebuilds {@link #listTerminals} from the warehouse's own output-style members: every loaded terminal of this
     * warehouse that really has an open clipboard order, in aisle order and bounded by
     * {@value #MAX_LIST_TERMINALS}.
     * <p>
     * It resolves one block entity per <b>loaded</b> output station, which is why it runs on a dirty flag rather than on
     * a cadence. An unloaded position is skipped rather than resolved: a member record survives a chunk unload on
     * purpose ({@code WarehouseStationBlockEntity#remove}), so without that check one block placed in the aisle would
     * force-load every unloaded output-station chunk of the warehouse (M23 review fix). A chunk load fires a membership
     * change of its own, which is what brings the terminal back into the set.
     */
    private void refreshListTerminals(@Nullable WarehouseLayout current) {
        listTerminals.clear();
        if (current == null || level == null || level.isClientSide)
            return;
        for (LocationRecord record : membership.records(LocationKind.OUTPUT)) {
            if (listTerminals.size() >= MAX_LIST_TERMINALS)
                return;
            BlockPos pos = current.rackPos(record.position());
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal
                    && terminal.hasOpenListOrder())
                listTerminals.add(pos.immutable());
        }
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
        markChunkKeepDirty(); // M19: the crane is carrying items, so the aisle has work whatever else changed
        dispatch.track(job);
        if (job.sourceKind() == LocationKind.STORAGE)
            refreshLocation(job.source());
        // A collect job's pick is the moment the items crossed the port's threshold, so that is where the port's counter
        // grows — and the inventory it came out of just changed, so its snapshot is read again at once rather than at the
        // next poll (M18, issue #13).
        if (job.type() == JobType.COLLECT) {
            if (job.pickedAmount() > 0
                    && level.getBlockEntity(layout.rackPos(job.source())) instanceof WarehouseOutputBlockEntity port)
                port.recordCollected(job.pickedAmount());
            // Whatever was picked, including nothing at all: a pick of 0 is the one outcome that *proves* the cached
            // snapshot wrong (the machine was emptied between the plan and the pick), and extracting through an item
            // handler fires no neighbour update, so without this the stale entry would survive until the next poll. Every
            // port that reads the same inventory is invalidated with it, or a second port would keep offering the items
            // this one just took (M18 review).
            for (RackPosition source : collections.sharing(job.source()))
                pendingCollections.addUrgent(source);
        }
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
            BlockPos targetPos = layout.rackPos(target);
            if (request.isPresent() && request.get().destination().equals(targetPos)) {
                int counted = deliverRequest(requestId.get(), delivered);
                // The one moment a clipboard order learns that items really arrived, which is also what ticks the
                // entry off and makes the next portion due (M23, issue #19): the physical drop into this terminal's
                // buffer. A delivery no list order was waiting for is claimed as nothing (ListOrder#credit).
                if (counted > 0 && level.getBlockEntity(targetPos) instanceof WarehouseTerminalBlockEntity terminal
                        && terminal.creditListDelivery(requestId.get(), counted, level.getGameTime()))
                    listTerminalsDirty = true;
            }
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
        // Items that came into the warehouse and were really stored: the one arrival a production order may count as its
        // machine's product (M15 part 2). Reported after the snapshot, so the stock index and the order agree within the
        // same tick.
        // Both arrivals count (JobType#bringsItemsIn, M18, issue #13): a restock order that ordered 32 planks and now
        // collects them out of the crafter's output chest has to see its product arrive, or it times out and the safety
        // stop fires for no reason. This single line is what makes "collecting closes the production loop" true rather
        // than decorative.
        if (job.type().bringsItemsIn() && job.targetKind() == LocationKind.STORAGE && delivered > 0)
            onResultStored(job.key(), delivered);
        // The mirror image: items the warehouse handed over through an accepting port instead of storing them (M17).
        // onResultStored above is deliberately not reached by them — a product diverted out was never stored, so no
        // restock order may count it, and ADR-027's safety stop is what catches the rule that keeps ordering.
        if (job.type() == JobType.STORE && job.targetKind() == LocationKind.OUTPUT && delivered > 0
                && level.getBlockEntity(layout.rackPos(target)) instanceof WarehouseOutputBlockEntity port)
            port.recordExport(delivered);
        return requestId.map(this::hasOpenJobOwner).orElse(true);
    }

    /**
     * The crane exchanged {@code amount} containers of {@code formerKey} at {@code target} for the item {@code job} now
     * carries ({@code onCraneExchanged}, M30, issue #21, D14): a filled container went into a fluid bay's tank and the
     * crane is carrying the empty container away.
     * <p>
     * <b>Two things, and deliberately nothing else.</b> The reservations follow the job, which now holds another key and
     * no request at all, and the bay is re-read at once — not for its items, of which it has none, but because this is
     * the path on which a location's store settings are refreshed, and an unfiltered bay that was empty before this
     * exchange has just learned which fluid it holds.
     * <p>
     * What must <b>not</b> happen here is the reason this is a report of its own rather than an
     * {@link #onCraneDelivered} with a delivered amount: that method credits {@code onResultStored} for every job that
     * brings items in and delivered into a {@link LocationKind#STORAGE} location, so an exchange routed through it would
     * credit a restock order with a filled container that was never stored, while the stock index showed none of it and
     * ADR-027's safety stop fired for no reason. {@code formerKey} and {@code amount} are therefore the report of what
     * happened and not an instruction to count anything — the same shape {@code onCraneJobAborted}'s reason has.
     */
    public void onCraneExchanged(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job,
            RackPosition target, ItemKey formerKey, int amount) {
        if (!acceptsReportsFrom(crane))
            return;
        dispatch.track(job);
        refreshLocation(target);
    }

    /** The crane's leftovers have a new target: the reservations move with them. */
    public void onCraneRerouted(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane))
            dispatch.track(job);
    }

    /** Everything picked was delivered: release the job's reservations. */
    public void onCraneJobFinished(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane)) {
            dispatch.release(job.id());
            markChunkKeepDirty(); // M19: the job is done, so the aisle may be idle now
        }
    }

    /** The crane gave up the job with nothing held ({@code onJobAborted}): release it; requests keep their amount. */
    public void onCraneJobAborted(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job,
            AbortReason reason) {
        if (acceptsReportsFrom(crane)) {
            dispatch.release(job.id());
            markChunkKeepDirty(); // M19
        }
    }

    /**
     * The crane lost the job with its block (broken: the head dropped its items; cleared by a command): release its
     * reservations; a request keeps its remaining amount ({@code docs/warehouse-system.md} §8).
     */
    public void onCraneJobLost(StackerCraneBlockEntity crane, TransportJob<ItemKey, RackPosition> job) {
        if (acceptsReportsFrom(crane)) {
            dispatch.release(job.id());
            markChunkKeepDirty(); // M19
        }
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
        // Before the layout guard on purpose: an aisle that just lost its dock (or came back from a save without one)
        // still has to let its chunks go.
        if (chunkKeepDirty || now >= chunkKeepRecheckTick
                || chunkKeepConfigGeneration != AisleChunkTickets.configGeneration())
            evaluateChunkKeep(now);
        if (layout == null)
            return;
        if (membership.isDirty())
            processMembership(layout);
        if (stockRulesRefreshPending) {
            stockRulesRefreshPending = false;
            refreshAllStockRules();
        }
        // After the membership pass, so a home point placed in this very tick is already a member when it is judged.
        if (homePointsRefreshPending) {
            homePointsRefreshPending = false;
            refreshHomePoints();
        }
        drainPendingSnapshots();
        if (!productionOrders.isEmpty()) {
            if (observeProductionResults(now))
                setChanged();
            if (now >= nextProductionTick) {
                nextProductionTick = now + Math.max(1, WareworksConfig.dispatchIntervalTicks());
                tickProductionOrders(now);
            }
        } else if (!storedCredits.isEmpty()) {
            // Nothing is waiting for a result any more, so an arrival credit that was never consumed is stale: keeping
            // it would suppress the first level gain of the next order for that item (M15 part 2).
            storedCredits.clear();
        }
        if (now >= nextSnapshotTick) {
            nextSnapshotTick = now + WareworksConfig.snapshotIntervalTicks();
            // As many locations as it takes to come round within controller.snapshotCycleTicks, so the cycle time of a
            // warehouse of many aisles is bounded instead of growing with it (M22). One location up to about 1200 of
            // them at the shipped defaults, which is literally what every version before M22 read.
            for (int budget = SnapshotCadence.locationsPerInterval(membership.storageCount(),
                    WareworksConfig.snapshotIntervalTicks(), WareworksConfig.snapshotCycleTicks(),
                    WareworksConfig.maxSnapshotsPerTick()); budget > 0; budget--) {
                Optional<RackPosition> next = nextRoundRobinLocation();
                if (next.isEmpty())
                    break;
                refreshLocation(next.get());
            }
        }
        tickStockKeepers(now);
        if (layout != null && now >= nextPortTick) {
            nextPortTick = now + Math.max(1, WareworksConfig.dispatchIntervalTicks());
            tickPorts(now);
        }
        // Before the dispatch on purpose: a portion a list order asks for in this tick is an ordinary request, so the
        // planner may serve it in this very tick instead of on the next one (M23, issue #19).
        if (layout != null && now >= nextListTick) {
            nextListTick = now + LIST_WALK_INTERVAL_TICKS;
            tickListOrders(now);
        }
        if (layout != null)
            dispatch.tick(level, layout, now);
    }

    /**
     * Reads at most {@code maxSnapshotsPerTick} queued inventories, urgent ones first.
     * <p>
     * A location whose rack position is not loaded is put back (M8 review fix): {@link SnapshotQueue#poll} has already
     * removed it, so dropping it here left its restored counts to the round robin, which needs up to
     * 2 · (L+1) · H · {@code snapshotIntervalTicks} to come round — minutes at the default caps (§5).
     *
     * <h2>The collect queue has the last unit of the budget to itself</h2>
     * The two queues share one budget, so an aisle with collecting ports never reads more inventories per tick than
     * one without (M18, issue #13), and storage comes first: a stale stock index is what a player's request and every
     * plan depend on. But "storage first" alone was <b>starvation</b>, not an order, from M28 on. A rack bay tells its
     * controller the moment its contents change, so every accepted transfer through a bay's handler is one
     * {@code addUrgent} — one item from a hopper is one hint. As soon as {@code maxSnapshotsPerTick} <i>distinct</i>
     * bays of an aisle change per tick (several hints for one bay already collapse, because {@link SnapshotQueue} is a
     * set), the urgent set never empties, and {@link #refreshCollection} is reached from nowhere else: a collecting
     * port would then never be read again for as long as the feeding lasts, which {@code AisleCollections} says out
     * loud is silent — an unread port collects nothing, and a port read once goes stale and makes the planner plan
     * collects whose pick returns 0. The same saturation also makes the stock index itself fall behind.
     * <p>
     * So the <b>last</b> unit of the budget belongs to {@link #pendingCollections}, and neither queue can waste it:
     * whichever queue is reserved is asked first and the other one gets the unit when it is empty. At
     * {@code maxSnapshotsPerTick = 1} there is nothing to reserve and the stock index keeps the whole budget, which is
     * the honest reading of a budget of one.
     */
    private void drainPendingSnapshots() {
        int budget = Math.max(1, WareworksConfig.maxSnapshotsPerTick());
        int reservedForCollections = budget > 1 ? 1 : 0;
        for (; budget > 0; budget--) {
            boolean collectFirst = budget <= reservedForCollections;
            boolean read = collectFirst ? drainOneCollection() || drainOneSnapshot()
                    : drainOneSnapshot() || drainOneCollection();
            if (!read)
                return; // both queues are empty
        }
    }

    /** Reads one queued storage location; {@code false} when the queue held nothing. */
    private boolean drainOneSnapshot() {
        Optional<RackPosition> next = pendingSnapshots.poll();
        if (next.isEmpty())
            return false;
        RackPosition rack = next.get();
        if (!refreshLocation(rack) && isRackUnloaded(rack))
            pendingSnapshots.addBackground(rack); // keep it queued until its chunk is loaded again
        return true;
    }

    /** Reads one queued collecting port; {@code false} when the queue held nothing. */
    private boolean drainOneCollection() {
        Optional<RackPosition> collect = pendingCollections.poll();
        if (collect.isEmpty())
            return false;
        RackPosition rack = collect.get();
        if (!refreshCollection(rack) && isCollectUnloaded(rack))
            pendingCollections.addBackground(rack); // keep it queued until its chunks are loaded again
        return true;
    }

    /**
     * Whether a collecting port's read failed only because a chunk is not loaded — the one failure that is transient, so
     * the entry is kept queued instead of waiting for the poll (the M8 review fix, applied to the collect queue).
     */
    private boolean isCollectUnloaded(RackPosition rack) {
        if (level == null || layout == null)
            return false;
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return true;
        return level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port && !port.isRemoved()
                && !level.isLoaded(port.attachedPos());
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


    // --- chunk loading (M19, issue #10, ADR-031) ------------------------------------------------------------------

    /**
     * The aisle re-decides its chunk hold on its next tick (M19, issue #10). Called from every place that changes what the
     * aisle has to do, from every place that changes the aisle itself, and from the crane when its state machine falls
     * back to idle — which is one tick after it reports a finished job and therefore the moment the job is really gone.
     * <p>
     * A hint, never an action: only {@link #tick()} ever calls into NeoForge's chunk API ({@link AisleChunkTickets}).
     */
    public void markChunkKeepDirty() {
        chunkKeepDirty = true;
    }

    /**
     * Gives up the hold until the work really changes (an operator's {@code /wareworks chunks release}). The chunks go in
     * the same tick and nothing is taken again for the same job, request or order — across a reload and a restart too,
     * because the flag and its fingerprint are saved (M19 review).
     */
    void giveUpChunkKeep() {
        setChunkKeepGaveUp(true, chunkWorkFingerprint(linkedDockEntity()));
        chunkKeepDirty = true;
    }

    /**
     * Sets or clears the give-up flag and saves the change. Saved on purpose: the work it refuses is saved too, so a
     * flag that lived only as long as one block entity instance would let every reload and every restart take the whole
     * footprint again for work that had already proved unservable (M19 review).
     */
    private void setChunkKeepGaveUp(boolean gaveUp, long fingerprint) {
        if (chunkKeepGaveUp == gaveUp && chunkKeepFingerprint == fingerprint)
            return;
        chunkKeepGaveUp = gaveUp;
        chunkKeepFingerprint = fingerprint;
        setChanged();
    }

    /** What the goggles and the display source say about this aisle's chunk hold. */
    public ChunkKeepReason chunkKeepReason() {
        return chunkKeepReason;
    }

    /** Chunks held right now, or how many the footprint would need while the hold is refused. */
    public int chunkKeepChunks() {
        return chunkKeepChunks;
    }

    /** How many chunks this aisle's footprint covers ({@link AisleChunkSpan}); 0 without a dock. */
    public int chunkFootprintSize() {
        return chunkFootprint().length / 2;
    }

    /**
     * The chunk columns of this aisle, cached per layout instance ({@link AisleChunkSpan}). {@link #applyLayout} is the
     * only place the layout changes, and a layout is a value object that is replaced only when it really differs, so
     * identity is the right comparison and this is never recomputed per tick.
     */
    private int[] chunkFootprint() {
        if (layout == null) {
            chunkFootprintOf = null;
            chunkFootprintCache = EMPTY_FOOTPRINT;
            return chunkFootprintCache;
        }
        if (chunkFootprintOf != layout) {
            chunkFootprintOf = layout;
            chunkFootprintCache = AisleChunkSpan.networkChunks(branchSpans(layout));
        }
        return chunkFootprintCache;
    }

    /** One {@code {originX, originZ, stepX, stepZ, length}} group per aisle, for {@link AisleChunkSpan#networkChunks}. */
    private static int[] branchSpans(WarehouseLayout warehouse) {
        int[] spans = new int[warehouse.branchCount() * AisleChunkSpan.INTS_PER_BRANCH];
        for (int i = 0; i < warehouse.branchCount(); i++) {
            BranchLayout branch = warehouse.branch(i);
            int at = i * AisleChunkSpan.INTS_PER_BRANCH;
            spans[at] = branch.origin().getX();
            spans[at + 1] = branch.origin().getZ();
            spans[at + 2] = branch.heading().getStepX();
            spans[at + 3] = branch.heading().getStepZ();
            spans[at + 4] = branch.geometry().length();
        }
        return spans;
    }

    /**
     * Decides whether this aisle holds its chunks, and carries the decision out ({@link ChunkKeepDecision},
     * {@link AisleChunkTickets}). The only place that ever calls into NeoForge's chunk API.
     * <p>
     * Cost: a handful of field reads plus the one block entity lookup for the linked dock. It runs when a hook marked the
     * aisle dirty, on the deadlines the decision itself asked for, and at most every {@value #CHUNK_KEEP_RECHECK_TICKS}
     * ticks while the aisle really holds something.
     */
    private void evaluateChunkKeep(long now) {
        chunkKeepDirty = false;
        chunkKeepRecheckTick = ChunkKeepDecision.NO_RECHECK;
        chunkKeepConfigGeneration = AisleChunkTickets.configGeneration();
        if (!(level instanceof ServerLevel serverLevel) || isVirtual())
            return;
        // The default server's path, and the reason "a server that does not want this pays nothing" is literally true:
        // two map lookups instead of the ledger reads, five config reads, the dock lookup and a possible collect count.
        // A holder still gets the whole body, which is what makes switching the setting off release at once (M19 review).
        if (!WareworksConfig.chunkLoadingEnabled()) {
            AisleChunkTickets.forget(serverLevel, worldPosition);
            if (AisleChunkTickets.heldChunkCount(serverLevel, worldPosition) == 0) {
                chunkKeepReason = ChunkKeepReason.NONE;
                chunkKeepChunks = 0;
                return;
            }
        }
        boolean unclaimed = AisleChunkTickets.isUnclaimed(serverLevel, worldPosition);
        AisleChunkTickets.claim(serverLevel, worldPosition);
        int[] footprint = chunkFootprint();
        // The opt-in alone can hold nothing, so without the master switch it must not make chunkKeepWork read the world
        // for a collect count either.
        boolean collectHoldEnabled = WareworksConfig.chunkLoadingEnabled()
                && WareworksConfig.maxCollectHoldAislesPerLevel() > 0;
        Optional<StackerCraneBlockEntity> dock = linkedDockEntity();
        ChunkKeepDecision.Work work = chunkKeepWork(dock, collectHoldEnabled);
        // A hold reinstated from the save cannot be judged while the dock's chunk is still away: a crane job is the one
        // kind of work only the dock knows about. Extending the hold loads it, and the next evaluation decides for real -
        // at worst one extra round for an aisle whose work is gone.
        if (unclaimed && layout != null && !level.isLoaded(dockPos()))
            work = new ChunkKeepDecision.Work(true, work.openRequests(), work.openOrders(), work.collectPending());
        boolean collectCapAllows = AisleChunkTickets.collectCapAllows(serverLevel, worldPosition);
        boolean collectCounts = collectHoldEnabled && collectCapAllows;
        if (work.any(collectCounts))
            chunkKeepIdleSince = ChunkKeepDecision.NOT_SET;
        else {
            if (chunkKeepIdleSince == ChunkKeepDecision.NOT_SET && !unclaimed)
                // A reinstated hold does not linger: the linger smooths out bursts of work, and a world that has just
                // been loaded had none.
                chunkKeepIdleSince = now;
            // Nothing to be refused for any more, so the flag goes: an aisle that gave up and then became idle would
            // otherwise keep reporting GAVE_UP for ever, because the fingerprint of an empty aisle never changes again
            // (M19 review). This is also the escape hatch for the saved flag - finished work always re-arms an aisle.
            setChunkKeepGaveUp(false, 0L);
        }
        // The re-arm needs the whole work to be visible. While the dock's chunk is away the crane job is not, and the
        // fingerprint would differ for that reason alone - which would re-arm a hold that gave up on that very job.
        if (chunkKeepGaveUp && (layout == null || dock.isPresent())
                && chunkWorkFingerprint(dock) != chunkKeepFingerprint)
            setChunkKeepGaveUp(false, 0L); // real progress, so it may hold again
        ChunkKeepDecision.Limits limits = new ChunkKeepDecision.Limits(WareworksConfig.chunkLoadingEnabled(),
                collectHoldEnabled, AisleChunkTickets.levelCapAllows(serverLevel, worldPosition), collectCapAllows,
                footprint.length / 2, WareworksConfig.maxChunksPerAisle(), WareworksConfig.chunkReleaseDelayTicks(),
                WareworksConfig.maxChunkHoldTicks());
        ChunkKeepDecision.State state = new ChunkKeepDecision.State(
                AisleChunkTickets.heldChunkCount(serverLevel, worldPosition) > 0,
                AisleChunkTickets.heldSince(serverLevel, worldPosition), chunkKeepIdleSince, chunkKeepGaveUp);
        ChunkKeepDecision.Decision decision = ChunkKeepDecision.decide(work, limits, state, now);
        applyChunkKeep(serverLevel, decision, work, footprint, dock, now);
    }

    /**
     * What this aisle has to do, for {@link ChunkKeepDecision}. Buffered input items are deliberately <b>not</b> work: a
     * buffer cannot change while its own chunk does not tick, so it can never appear while the aisle is unloaded, and the
     * moment it is planned it becomes a crane job. As a hold reason it would let one forgotten item hold chunks forever.
     * <p>
     * The collect count is read only with the opt-in, so the default path never touches the world for it.
     */
    private ChunkKeepDecision.Work chunkKeepWork(Optional<StackerCraneBlockEntity> dock, boolean collectHoldEnabled) {
        boolean craneJob = dock.map(crane -> crane.currentJob().isPresent()).orElse(false);
        int collectPending = collectHoldEnabled && layout != null ? collectSources().size() : 0;
        return new ChunkKeepDecision.Work(craneJob, requests.openCount(), productionOrders.openCount(), collectPending);
    }

    /**
     * The identity of the work the aisle is doing: the crane job, the open requests and the open production orders. A
     * changed fingerprint is what re-arms a hold that gave up, so "gave up" ends on real progress and not on a timer.
     * Saved together with the flag, so the bound survives a reload and a restart.
     */
    private long chunkWorkFingerprint(Optional<StackerCraneBlockEntity> dock) {
        // Order-independent inside each collection (a sum plus its count, not a positional hash), because this value is
        // saved and compared after a reload, where the queues are rebuilt from NBT: an equal set of work has to give an
        // equal fingerprint (M19 review). The ids are UUIDs, whose hashCode is stable across restarts by contract.
        long requestIds = 0;
        int requestCount = 0;
        for (RetrievalRequest<ItemKey, BlockPos> request : requests.requests()) {
            requestIds += request.id().hashCode() & 0xFFFF_FFFFL;
            requestCount++;
        }
        long orderIds = 0;
        int orderCount = 0;
        for (ProductionOrder<ItemKey, RackPosition> order : productionOrders.all()) {
            orderIds += order.id().hashCode() & 0xFFFF_FFFFL;
            orderCount++;
        }
        long hash = 1125899906842597L;
        hash = 31 * hash + dock.flatMap(StackerCraneBlockEntity::currentJob).map(job -> job.id().hashCode()).orElse(0);
        hash = 31 * hash + requestIds;
        hash = 31 * hash + requestCount;
        hash = 31 * hash + orderIds;
        hash = 31 * hash + orderCount;
        return hash;
    }

    private void applyChunkKeep(ServerLevel serverLevel, ChunkKeepDecision.Decision decision,
            ChunkKeepDecision.Work work, int[] footprint, Optional<StackerCraneBlockEntity> dock, long now) {
        switch (decision.action()) {
            case TAKE, KEEP -> {
                // collectOnly: this hold exists only because a collecting port has something pending, which is what the
                // separate cap counts.
                if (!AisleChunkTickets.hold(serverLevel, worldPosition, footprint, decision.reason(), !work.hard(), now))
                    chunkKeepDirty = true; // the rest of the footprint on the next tick (bounded chunks per tick)
            }
            case RELEASE -> {
                if (decision.reason() == ChunkKeepReason.GAVE_UP) {
                    // Only the maxHoldTicks deadline reaches this with the flag still clear; an operator's release sets
                    // it and lets the chunks go itself, so the WARN below cannot claim a timeout that did not happen.
                    boolean timedOut = !chunkKeepGaveUp;
                    setChunkKeepGaveUp(true, chunkWorkFingerprint(dock));
                    if (timedOut)
                        Wareworks.LOGGER.warn("Aisle at {} held its chunks for the configured maximum without finishing "
                                + "its work and gave up; it holds again once its work really changes", worldPosition);
                }
                AisleChunkTickets.release(serverLevel, worldPosition,
                        decision.reason() == ChunkKeepReason.GAVE_UP ? AisleChunkTickets.Cause.GAVE_UP
                                : AisleChunkTickets.Cause.DECIDED);
                // A cap that no longer allows the hold is a refusal that happens to release first: it is remembered and
                // logged like any other refusal, so a lowered cap is visible rather than silent (M19 review).
                if (decision.reason() == ChunkKeepReason.GAVE_UP)
                    AisleChunkTickets.forget(serverLevel, worldPosition); // its own WARN above says all there is to say
                else
                    rememberRefusal(serverLevel, decision.reason(), footprint, now);
            }
            case REFUSE -> rememberRefusal(serverLevel, decision.reason(), footprint, now);
            case NONE -> AisleChunkTickets.forget(serverLevel, worldPosition);
        }
        int held = AisleChunkTickets.heldChunkCount(serverLevel, worldPosition);
        chunkKeepReason = decision.reason();
        // The operator's row in /wareworks chunks, kept in step with the decision itself rather than only with a refusal
        // (M21, ADR-033): a cap raised under a warehouse that then holds its chunks has to drop the row in the same
        // breath, or the listing would go on claiming it holds nothing.
        if (decision.reason() == ChunkKeepReason.TOO_MANY_CHUNKS)
            AisleChunkTickets.overCap(serverLevel, worldPosition, footprint.length / 2);
        else
            AisleChunkTickets.notOverCap(serverLevel, worldPosition);
        // The one number the goggle line is about: what is held while holding, what would be needed while refused, and
        // nothing at all when there is nothing to report (which keeps the synced summary empty on a default server).
        chunkKeepChunks = switch (decision.reason()) {
            case NONE -> 0;
            case CRANE_JOB, OPEN_REQUESTS, PRODUCTION_ORDERS, COLLECTING, RELEASING -> held;
            case AT_LEVEL_LIMIT, AT_COLLECT_LIMIT, TOO_MANY_CHUNKS, GAVE_UP -> footprint.length / 2;
        };
        // Only a real hold schedules a re-check; a refused or idle aisle waits for an event, so it costs nothing at all.
        if (held > 0)
            chunkKeepRecheckTick = Math.min(decision.recheckAtTick(), now + CHUNK_KEEP_RECHECK_TICKS);
    }

    /**
     * An aisle that wants to hold and may not: remembered for the cap wake-up while the cap is the kind that frees up
     * again, and reported once per throttle window. The two permanent refusals ({@code TOO_MANY_CHUNKS}, {@code GAVE_UP})
     * are deliberately not woken — nothing another aisle does can change them.
     * <p>
     * {@code TOO_MANY_CHUNKS} is listed for the operator all the same, with the number this warehouse would need; that
     * record is written by {@link #applyChunkKeep} on every decision, because it has to disappear again the moment the
     * warehouse is no longer over the cap (M21, ADR-033).
     */
    private void rememberRefusal(ServerLevel serverLevel, ChunkKeepReason reason, int[] footprint, long now) {
        int needed = footprint.length / 2;
        if (reason == ChunkKeepReason.AT_LEVEL_LIMIT || reason == ChunkKeepReason.AT_COLLECT_LIMIT)
            AisleChunkTickets.refuse(serverLevel, worldPosition);
        else
            AisleChunkTickets.forget(serverLevel, worldPosition);
        if (reason != ChunkKeepReason.NONE && chunkKeepRefusals.tryLog(now))
            Wareworks.LOGGER.warn("Warehouse at {} may not hold its chunks: {} (its footprint needs {} chunks, the "
                    + "limit is {})", worldPosition, reason.name(), needed, WareworksConfig.maxChunksPerAisle());
    }

    private void relink(long now) {
        relinkRequested = false;
        nextRelinkTick = now + Math.max(MIN_RELINK_INTERVAL_TICKS, WareworksConfig.geometryRefreshTicks());
        // The stock rules ride the geometry cadence: a keeper edited while this controller was unloaded is picked up
        // here at the latest, and a changed maxStockRules takes effect (AisleStockRules#setCap).
        stockRulesRefreshPending = true;
        // ... and so do the home points: a changed returnHomeIdleTicks, a rail somebody broke between the crane and
        // its home point, and a home point placed while this controller slept all reach the block within one cadence.
        homePointsRefreshPending = true;
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
        // The pinned lines are offsets from the dock, so a dock that moved or turned describes other rails entirely.
        // Cleared before the aisles are assigned, not after, or the fresh assignment would go with them.
        if (layout != null && (!layout.dock().equals(dockPos) || layout.facing() != facing))
            branchTable.clear();
        applyLayout(warehouseOf(dock, dockPos, facing));
        // The crane drives in this controller's numbering, not in the raw scan's: the branch table pins aisle letters
        // and origin ends, so only the network the controller resolved agrees with the rack positions of a job
        // (M21, ADR-033). Handed over on every relink, which is also what a geometry change triggers.
        dock.setWarehouseNetwork(layout == null ? null : layout.network());
        // Idempotent, and it keeps a dock that was just linked (or relinked after a save) in step at once rather than
        // one tick later: without a home point this hands over nothing, which means "the dock is home" (M21, ADR-034).
        dock.setHomePoint(layout == null ? null : servingHomePoint.orElse(null));
        if (layout == null)
            return;
        if (pruneRequests(layout))
            setChanged();
        // A controller placed or loaded behind a busy crane adopts its job and rebuilds the reservations.
        dispatch.adopt(dock.currentJob());
    }

    /**
     * The warehouse this controller serves right now: the branches the dock discovered, with their pinned letters and
     * origin ends ({@link BranchTable}).
     * <p>
     * <b>An incomplete scan never reshapes a warehouse.</b> A discovery that ran into a chunk that is not loaded may
     * have found less than there is, and a shorter network would renumber positions — so the branches the controller
     * already knows are kept, exactly as the dock keeps its own aisle length in that case
     * ({@code AisleGeometry#scannedLength}). Renumbering must never be caused by a chunk boundary.
     */
    private WarehouseLayout warehouseOf(StackerCraneBlockEntity dock, BlockPos dockPos, Direction facing) {
        AisleGeometry own = dock.geometry();
        Heading heading = Headings.of(facing);
        NetworkGeometry discovered = dock.discoveredNetwork()
                .filter(scan -> !scan.reachedUnloadedChunk())
                // A scan taken while the dock faced another way describes another warehouse: every branch of it is
                // measured from that heading, so adopting it would name other blocks under the same addresses. The
                // dock re-scans on the tick after a facing change; until then the branches this controller knows are
                // the ones that match the world.
                .filter(scan -> scan.geometry().firstBranch().heading() == heading)
                .map(RailNetwork::geometry)
                .orElseGet(() -> keptNetwork(own, facing));
        BranchTable.Assignment assignment = branchTable.assign(discovered.withHeight(own.height()), aisleLetter());
        return WarehouseLayout.of(dockPos, facing, assignment.network(), Optional.of(aisleLetter()))
                .withBranchLetters(assignment.letters());
    }

    /**
     * What a partial scan falls back to: the branches this controller already knows, or the dock's own aisle.
     * <p>
     * The kept branches are <b>cut back to the aisle length the dock itself confirmed</b>
     * ({@link RailNetwork#resolveFirstBranchLength}, the narrow flag). Keeping them verbatim froze the first aisle's
     * old length for as long as anything the walk looked at was unloaded — a rack column beside the surviving part of
     * the aisle is enough — and the crane then planned routes, answered {@code canDrive} and parked against rails that
     * a player had broken. The broad flag is the right reason not to <b>reshape</b> a warehouse, because a shorter scan
     * renumbers; it is not a reason to keep a length that a loaded block has already disproved
     * ({@link NetworkGeometry#withFirstBranchLength} renumbers nothing, M21 review fix).
     * <p>
     * Only that one aisle is shortened. A loaded block at the far end of the aisle at the dock says nothing about the
     * other aisles, so the side aisles of a comb keep their addresses and their stock even when the main run under them
     * got shorter; one the shorter run really disconnects says on its own blocks that the crane cannot reach it
     * ({@link AisleAssignment.State#UNREACHABLE}), which is what M22 made the answer for a loose aisle everywhere.
     */
    private NetworkGeometry keptNetwork(AisleGeometry own, Direction facing) {
        WarehouseLayout known = layout;
        if (known != null && known.branchCount() > 1 && known.facing() == facing)
            return known.network().withFirstBranchLength(own.length());
        return NetworkGeometry.of(own, Headings.of(facing));
    }

    private void applyLayout(@Nullable WarehouseLayout next) {
        WarehouseLayout previous = layout;
        status = statusOf(next);
        if (Objects.equals(previous, next)) {
            if (next != null)
                WarehouseRegistry.register(level, worldPosition, next); // idempotent; repairs a lost entry
            return;
        }
        layout = next;
        // M19: a changed (or lost) aisle is a changed (or empty) chunk footprint. Deliberately below the early return
        // above: an unchanged layout - which is what every periodic re-link check finds - changes nothing to decide.
        markChunkKeepDirty();
        if (next == null) {
            WarehouseRegistry.unregister(level, worldPosition);
            // The keepers lose their warehouse, so they stop calling for items. Their world positions come from the
            // layout that is being taken away. A real loss of the aisle on a loaded controller, never an unload.
            clearAllKeeperRuleStates(previous);
            // The home points lose their warehouse, so their lamps go out and nothing claims to be a crane's home.
            clearAllHomePointStates();
            clearAllProductionStops();
            clearAisleState(); // no aisle, no locations, no output stations to deliver to
            branchTable.clear(); // the lines this table pinned belong to a warehouse that is gone
        } else {
            WarehouseRegistry.register(level, worldPosition, next);
            if (previous == null || !previous.dock().equals(next.dock()) || previous.facing() != next.facing()) {
                // Records, counts and request destinations refer to the rack positions of another layout. Kept records
                // of the same kind at the same aisle-local position would keep another inventory's counts, so every
                // member joins again and is read.
                clearAisleState();
                membership.invalidateAll();
            } else if (!previous.namesTheSameBlocks(next)) {
                // A branch moved its origin or turned round, so its positions name other blocks than they did. Nothing
                // may be carried over under its old label (ADR-033).
                remapState(previous, next);
            } else if (!previous.network().equals(next.network())) {
                // A branch that disappeared (a rail closed with a wrench or broken before the corner) keeps its origin
                // and its heading, so this is the arm a lost aisle lands in - no remap runs, and the removal pass that
                // follows is handed the NEW layout, whose labels for that branch name no block at all. Its keepers
                // must therefore be quietened here, while the layout that still names their blocks is in hand, or a
                // keeper left the warehouse with its lamp burning and its comparator running for ever (M21 review fix).
                quietenKeepersOutside(previous, next);
                membership.markAllDirty();
            }
        }
        setChanged();
    }

    /**
     * Every keeper this controller holds rules for whose rack position {@code next} no longer has stops signalling —
     * reached through {@code previous}, the layout that still names its block.
     * <p>
     * It deliberately does not remove the rules: {@link #refreshAllStockRules} and the membership pass drop them on the
     * evidence of a loaded block, which is the rule a maximum and a reserve depend on. This only takes the lamp and the
     * comparator value off a block nothing writes any more.
     */
    private void quietenKeepersOutside(WarehouseLayout previous, WarehouseLayout next) {
        for (RackPosition rack : List.copyOf(stockRules.keepers())) {
            if (!next.contains(rack))
                clearKeeperRuleState(previous, rack);
        }
    }

    /**
     * Moves everything this controller knows from the labels of {@code previous} to those of {@code next}, through the
     * <b>world positions</b> the labels stand for: {@code world = previous.rackPos(record)}, then the position
     * {@code next} gives that block, decided by the member standing there when a corner leaves a choice
     * ({@link #resolveRack}). A record whose block is no longer a rack position of this warehouse is dropped, exactly
     * as a member that left the aisle is dropped — and its items stay where they are, in the chest a player can still
     * open (ADR-033, {@code docs/warehouse-system.md} §4).
     * <p>
     * <b>No path here can lose an item.</b> Nothing is moved, taken or dropped: a remap only renames. The crane's
     * head is not touched at all, and its pose, its motion target and its job's locations are renamed through the same
     * world blocks as everything else ({@link #remapCrane}); a position that stopped existing reaches the crane as the
     * {@code MISSING} it already handles by aborting before the pick and rerouting after it. Filters and priorities live on the
     * blocks, stock rules are re-read from their keepers, request destinations are saved as world offsets and every
     * remapped storage location is snapshotted again, so every number is re-derived from the world within a few ticks.
     * <p>
     * What is deliberately <b>cancelled</b> rather than carried over is an open production order whose station no
     * longer exists: an order that kept its label would send a player's ingredients to a different machine, which is
     * the one failure this remap exists to prevent ({@code processMembership} only ever cancelled orders of a
     * <i>removed</i> record, and a renumber removes none).
     */
    private void remapState(WarehouseLayout previous, WarehouseLayout next) {
        List<LocationRecord> records = membership.records();
        List<RackPosition> misaligned = List.copyOf(membership.misalignedPositions());
        Map<RackPosition, Map<ItemKey, Long>> counts = new HashMap<>();
        for (LocationRecord record : records) {
            if (record.kind() == LocationKind.STORAGE)
                counts.put(record.position(), stock.countsAt(record.position()));
        }
        Map<RackPosition, List<StockRule<ItemKey>>> rules = stockRules.saved();
        List<ProductionOrder<ItemKey, RackPosition>> orders = productionOrders.all();
        List<RetrievalRequest<ItemKey, BlockPos>> openRequests = requests.requests();
        Map<RackPosition, RackPosition> moved = new HashMap<>();
        Set<RackPosition> taken = new HashSet<>();
        for (RackPosition position : knownPositions(records, misaligned, rules, orders)) {
            Optional<RackPosition> to = resolveRack(next, previous.rackPos(position));
            // Two labels of the old warehouse can name one block (a rack beside a corner has a position on both of its
            // aisles), and only one of them may survive. The lowest in RackPosition.ORDER wins, and the loser is
            // dropped like any position that stopped existing; the next probe puts the block back where it belongs.
            if (to.isPresent() && next.contains(to.get()) && taken.add(to.get()))
                moved.put(position, to.get());
        }

        clearAisleState();
        List<LocationRecord> remappedRecords = new ArrayList<>(records.size());
        for (LocationRecord record : records) {
            RackPosition to = moved.get(record.position());
            if (to != null)
                remappedRecords.add(new LocationRecord(to, record.kind()));
        }
        List<RackPosition> remappedMisaligned = new ArrayList<>(misaligned.size());
        for (RackPosition position : misaligned) {
            RackPosition to = moved.get(position);
            if (to != null)
                remappedMisaligned.add(to);
        }
        membership.restore(remappedRecords, remappedMisaligned);
        for (LocationRecord record : membership.records(LocationKind.STORAGE)) {
            stock.restore(record.position(), Map.of());
            pendingSnapshots.addUrgent(record.position());
            // Until that snapshot runs, the planner must not read the missing filter entry as "accepts everything".
            filters.markUnread(record.position());
        }
        for (Map.Entry<RackPosition, Map<ItemKey, Long>> entry : counts.entrySet()) {
            RackPosition to = moved.get(entry.getKey());
            if (to != null && stock.contains(to))
                stock.restore(to, entry.getValue());
        }
        for (LocationRecord record : membership.records(LocationKind.OUTPUT))
            ports.markUnread(record.position());
        Map<RackPosition, List<StockRule<ItemKey>>> remappedRules = new LinkedHashMap<>();
        List<RackPosition> droppedKeepers = new ArrayList<>();
        for (Map.Entry<RackPosition, List<StockRule<ItemKey>>> entry : rules.entrySet()) {
            RackPosition to = moved.get(entry.getKey());
            if (to != null)
                remappedRules.put(to, entry.getValue());
            else
                droppedKeepers.add(entry.getKey());
        }
        stockRules.restore(remappedRules);
        // A keeper this warehouse no longer reads must stop signalling, or it leaves a lit lamp and a comparator value
        // for a rule nothing enforces any more (M15 review). Its world position comes from the layout that is being
        // taken away, which is why the old one is passed in.
        for (RackPosition dropped : droppedKeepers)
            clearKeeperRuleState(previous, dropped);
        stockRules.setCap(WareworksConfig.maxStockRules());
        requests.restore(openRequests); // destinations are world positions, so they never needed a label
        // Restored before they are cancelled, so an order whose machine is gone goes through the very path a removed
        // production station already uses: its plan ends with it, a crane fetching for it aborts or reroutes, and the
        // request waiting for it is given its promise back. It is then an ended order a player can see the reason of,
        // rather than one that silently vanished.
        List<ProductionOrder<ItemKey, RackPosition>> remappedOrders = new ArrayList<>(orders.size());
        Set<RackPosition> orphaned = new LinkedHashSet<>();
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            RackPosition to = moved.get(order.station());
            if (to == null) {
                remappedOrders.add(order);
                if (order.isOpen())
                    orphaned.add(order.station());
                continue;
            }
            remappedOrders.add(order.atStation(to));
        }
        productionOrders.restore(remappedOrders);
        int cancelled = 0;
        for (RackPosition station : orphaned) {
            cancelled += productionOrders.ordersFor(station).size();
            cancelProductionOrdersAt(station);
        }
        productionRestorePending = true; // a truncated plan is checked on the first tick that knows the game time
        remapCrane(previous, next, moved);
        membership.markAllDirty();
        stockRulesRefreshPending = true;
        homePointsRefreshPending = true;
        Wareworks.LOGGER.info("The warehouse at {} was rebuilt: {} of {} locations kept their place, {} aisles"
                + (cancelled > 0 ? ", {} production orders cancelled with their station" : " ({} orders kept)"),
                worldPosition, remappedRecords.size(), records.size(), next.branchCount(),
                cancelled > 0 ? cancelled : remappedOrders.size());
    }

    /**
     * The crane goes through the same remap as everything else (M21 review fix, ADR-033): its pose, its motion target
     * and its job's two locations are moved from the old labels to the new ones through the world blocks they stood
     * for.
     * <p>
     * It used to be left out on the argument that "the branch at the dock is pinned to the dock, so a remap can never
     * move a position the crane is working on" — which holds for branch 0 and for nothing else. Branch indices are the
     * discovery order, and a rebuilt aisle can keep its index while it runs the other way or names another line of
     * blocks entirely; a crane that kept its number would be drawn on the wrong aisle from one tick to the next, drive
     * its route from a point it is not at, and deliver its items into whatever chest inherited its target's number.
     * <p>
     * A pose whose block is no longer on any aisle goes back onto the aisle at the dock
     * ({@link WarehouseLayout#parkedAtDock}), and a job location that was dropped keeps its old label and reaches the
     * crane as the missing location it already handles. <b>No item moves here.</b>
     */
    private void remapCrane(WarehouseLayout previous, WarehouseLayout next, Map<RackPosition, RackPosition> moved) {
        linkedDockEntity().ifPresent(dock -> dock.remapOnto(
                pose -> next.renamedFrom(previous, pose).orElseGet(() -> next.parkedAtDock(pose)),
                rack -> moved.getOrDefault(rack, rack)));
    }

    /** Every rack position this controller holds anything for, in {@link RackPosition#ORDER} and without duplicates. */
    private static List<RackPosition> knownPositions(List<LocationRecord> records, List<RackPosition> misaligned,
                                                     Map<RackPosition, List<StockRule<ItemKey>>> rules,
                                                     List<ProductionOrder<ItemKey, RackPosition>> orders) {
        Set<RackPosition> positions = new TreeSet<>(RackPosition.ORDER);
        for (LocationRecord record : records)
            positions.add(record.position());
        positions.addAll(misaligned);
        positions.addAll(rules.keySet());
        for (ProductionOrder<ItemKey, RackPosition> order : orders)
            positions.add(order.station());
        return List.copyOf(positions);
    }

    /**
     * Forgets everything that belongs to the aisle. The reservations go with it: a crane that is no longer this
     * controller's keeps its items and job and continues without controller (it holds items it cannot deliver).
     * <p>
     * <b>The stock rules are deliberately kept</b> (M15 review fix). Everything else here is derived from inventories
     * that are read again within a few ticks, but a rule gates item movement in both irreversible directions: a
     * forgotten maximum stores items that are never moved back out, and a forgotten reserve is handed to automation and
     * cannot be recalled. Both ways back into the copy need the keeper's chunk to be loaded, so throwing it away here
     * would read "a keeper I cannot see" as "no rule" — exactly what saving the copy with the controller exists to
     * prevent. Instead {@link #refreshAllStockRules} re-probes every rack the copy holds on the next tick and drops the
     * ones that really are no keeper any more, on the evidence of a loaded block.
     */
    private void clearAisleState() {
        markChunkKeepDirty(); // M19: no members, so no requests and no orders either
        membership.clear();
        stock.clear();
        fluidStock.clear();
        pendingSnapshots.clear();
        filters.clear();
        ports.clear();
        collections.clear();
        pendingCollections.clear();
        stockRulesRefreshPending = true;
        homePointsRefreshPending = true;
        sharedInventories.clear();
        requests.clear();
        productionOrders.clear();
        dispatch.reset();
    }

    private ControllerStatus statusOf(@Nullable WarehouseLayout layout) {
        if (layout == null)
            return dockMisaligned ? ControllerStatus.DOCK_MISALIGNED : ControllerStatus.NO_DOCK;
        return layout.geometry().length() == 0 ? ControllerStatus.NO_RAILS : ControllerStatus.READY;
    }

    /** The linked dock (if loaded) loses this controller as its owner; another controller's link is left alone. */
    private void unlinkDock() {
        markChunkKeepDirty(); // M19: without a dock there is no crane job to hold chunks for
        BlockPos dockPos = linkedDock;
        linkedDock = null;
        if (dockPos != null && level != null && level.isLoaded(dockPos)
                && level.getBlockEntity(dockPos) instanceof StackerCraneBlockEntity dock)
            // Which also puts its warehouse back to the one straight aisle its own rails describe: nobody names its
            // further aisles any more (ADR-033).
            dock.unlinkController(worldPosition);
    }

    private void processMembership(WarehouseLayout current) {
        MembershipChanges changes = membership.reconcile(current.network(), rack -> probe(current, rack));
        boolean changed = pruneRequests(current);
        for (LocationRecord removed : changes.removed()) {
            if (removed.kind() == LocationKind.STORAGE)
                forgetStorageLocation(removed.position());
            else if (removed.kind() == LocationKind.OUTPUT) {
                // Through worldPosOf, not rackPos: a record whose aisle the warehouse no longer has stands for no
                // block at all, and reading it as one cancelled the open requests of whatever really stands at that
                // (x, y, side) on the first aisle - a working output station, losing a player's requests and the job
                // serving them (M21 review fix). The requests of the station that really left were cancelled two
                // lines above by pruneRequests, which asks the world rather than a label.
                current.worldPosOf(removed.position()).ifPresent(this::cancelRequestsFor);
                forgetPort(removed.position());
            }
            else if (removed.kind() == LocationKind.PRODUCTION)
                cancelProductionOrdersAt(removed.position()); // its orders can never finish
            else if (removed.kind() == LocationKind.KEEPER)
                // The keeper left the aisle (broken, replaced or turned away from it): its rules go with it, and if the
                // block is still there it stops calling for items — nothing enforces them any more.
                dropStockRulesAt(removed.position());
            else if (removed.kind() == LocationKind.HOME)
                // The home point left the warehouse (broken, replaced or turned away): the crane falls back to its
                // dock, and a second home point may become the first. Both are decided by the refresh, which also
                // switches the lamps of the blocks that are still standing (M21, ADR-034).
                homePointsRefreshPending = true;
            changed = true;
        }
        for (LocationRecord added : changes.added()) {
            changed = true;
            if (added.kind() == LocationKind.KEEPER) {
                // Read at once, not on a later tick: a keeper a player just placed governs from now on, and a plan in
                // this very tick must not store past the maximum it carries.
                readStockRulesAt(added.position());
                continue;
            }
            if (added.kind() == LocationKind.HOME) {
                // Judged on this same tick (the refresh runs right after this pass), so a home point a player just
                // placed either lights up or says why it does not, instead of looking dead until the next cadence.
                homePointsRefreshPending = true;
                continue;
            }
            if (added.kind() == LocationKind.OUTPUT) {
                // Resolved by the next port pass (the port's own onLoad usually beats it to it): until then it keeps the
                // default policy, which is the harmless answer in both directions (AislePorts).
                ports.markUnread(added.position());
                continue;
            }
            if (added.kind() == LocationKind.PRODUCTION) {
                // A station that joins gets its lamp decided at once (M20 review fix): a block state survives every save
                // and a /setblock, so a station arriving with a lit lamp and nothing behind it would keep it until the
                // next stop of one of its own products, and the sweep flag may already be set for the stations before it.
                lightOrClearProductionStop(current.rackPos(added.position()));
                continue;
            }
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
        // A fluid bay's contents never move to another location: there is no shared-tank case to hand over, because a
        // bay is its own tank (D3). A broken bay loses its fluid and says so; the index simply forgets it.
        fluidStock.remove(rack);
    }

    /**
     * Classifies one rack position, in three ways rather than two (ADR-033): the member there is this position's if it
     * is aligned with it; it is <b>another position's</b> — {@link RackProbe#EMPTY} here — if it is aligned with one of
     * the other branches of this same warehouse that also reach the block, which is what happens at a corner; and only
     * a member aligned with none of them is {@link RackProbe#MISALIGNED}. Without the middle case a perfectly built
     * corner rack would be reported misaligned at its non-owning position and could register twice. A warehouse that
     * never bends has exactly one candidate per block, so the middle case never fires there.
     * <p>
     * <b>The order of the three questions is the rule, not a detail.</b> Nothing is written before ownership is
     * decided, and only the lowest candidate of a block may write at all. Aligning first and asking afterwards made
     * the probe destroy the very premise the ownership rule rests on — that each candidate of a block requires its own
     * facing — because {@code alignToAisle} is exactly what changes that facing (M21 review fix).
     */
    private RackProbe probe(WarehouseLayout current, RackPosition rack) {
        BlockPos pos = current.rackPos(rack);
        if (!current.isRackPosition(pos))
            // The column this label names is an aisle block of another branch of this same warehouse - the rail just
            // before a corner, and everything above it, which is where the crane's mast travels. A full scan walks
            // every rack position of the network, so it reaches those few labels even though no player can ever be
            // offered them any more; and the label of a branch that is gone lands on the dock block, whose whole
            // sentinel argument is that every question about it gets the harmless answer (M21 review fix).
            return RackProbe.EMPTY;
        if (!level.isLoaded(pos))
            return RackProbe.UNLOADED;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof WarehouseMember member) || blockEntity.isRemoved())
            return RackProbe.EMPTY;
        if (member.locationKind() == LocationKind.STORAGE && !(member instanceof StorageMember))
            return RackProbe.EMPTY; // a storage kind the controller cannot read
        BranchLayout branch = current.branch(rack.branch());
        if (member.isAlignedWith(branch, rack.side()))
            return RackProbe.member(member.locationKind()); // already this position's: nothing is written
        // Ownership is decided before anything is written. A block beside a corner is a rack position of two aisles
        // at once and each of them wants its own facing there, so aligning first and asking afterwards let both of
        // them write: the same terminal registered as an output on both aisles and its block state flipped twice
        // every tick for ever - the M10 failure mode, reproduced inside a single warehouse (M21 review fix).
        if (belongsToAnotherBranch(current, member, pos, rack))
            return RackProbe.EMPTY;
        if (!adaptsMembersAt(current, pos, rack))
            // Another candidate of this same block is the one that may turn it - and the one that reports it. A
            // block beside a corner that fits neither aisle is ONE badly turned block, so only its lowest candidate
            // may call it misaligned: reporting it here as well stored two rack positions for it and made the
            // goggles read "Misaligned blocks: 2" for a single interface (M21 review fix).
            return RackProbe.EMPTY;
        // A member whose block state depends on where the aisle is (the terminal's intake port) adapts it here, so
        // this probe already classifies the corrected state. It writes only on a real change, and only when this
        // controller owns the member's block state, so two aisles sharing a rack plane cannot fight over it
        // (WarehouseMember §doc, WarehouseRegistry#ownsMemberState, M10 review fix).
        member.alignToAisle(worldPosition, branch, rack.side());
        return member.isAlignedWith(branch, rack.side()) ? RackProbe.member(member.locationKind())
                : RackProbe.MISALIGNED;
    }

    /**
     * Whether {@code rack} is the one candidate of the block at {@code pos} that may turn a member towards its aisle:
     * the <b>lowest</b> of them, which is the first this warehouse names for that block and therefore the same one on
     * every pass. A member that fits no aisle of a corner is then adapted once by one aisle, instead of being pulled
     * back and forth between two of them, and is <b>counted once</b>: the other candidates answer
     * {@link RackProbe#EMPTY}, so one badly turned block is one entry in the misaligned set. A warehouse that never
     * bends has exactly one candidate per block, so this is always true there and the probe does literally what it did
     * before M21.
     */
    private static boolean adaptsMembersAt(WarehouseLayout current, BlockPos pos, RackPosition rack) {
        List<RackPosition> candidates = current.candidates(pos);
        return candidates.isEmpty() || candidates.getFirst().equals(rack);
    }

    /** Whether {@code member} is aligned with another branch of this warehouse that also reaches {@code pos}. */
    private boolean belongsToAnotherBranch(WarehouseLayout warehouse, WarehouseMember member, BlockPos pos,
                                           RackPosition rack) {
        if (warehouse.branchCount() <= 1)
            return false;
        for (RackPosition candidate : warehouse.candidates(pos)) {
            if (!candidate.equals(rack)
                    && member.isAlignedWith(warehouse.branch(candidate.branch()), candidate.side()))
                return true;
        }
        return false;
    }

    /**
     * The rack position the block at {@code pos} occupies in {@code warehouse}, decided the way the ownership rule
     * decides it (ADR-033): a block beside a corner is laterally beside a straight rail of two aisles at once, and the
     * member standing there picks which one it belongs to by the way it faces.
     * <p>
     * A warehouse that never bends offers at most one candidate per block, so this is literally what
     * {@code BranchLayout#worldToLocal} answered before M21. Where there is a choice and nothing decides it — the
     * block is empty and this controller knows nothing about either position — the lowest branch wins, which keeps the
     * answer deterministic; the next probe of a member that really stands there corrects it.
     */
    private Optional<RackPosition> resolveRack(WarehouseLayout warehouse, BlockPos pos) {
        List<RackPosition> candidates = warehouse.candidates(pos);
        if (candidates.size() <= 1)
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
        if (level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseMember member
                && !((BlockEntity) member).isRemoved()) {
            for (RackPosition candidate : candidates) {
                if (member.isAlignedWith(warehouse.branch(candidate.branch()), candidate.side()))
                    return Optional.of(candidate);
            }
        }
        for (RackPosition candidate : candidates) {
            if (membership.kindAt(candidate).isPresent() || membership.isMisaligned(candidate))
                return Optional.of(candidate);
        }
        return Optional.of(candidates.getFirst());
    }

    /** {@link #resolveRack} against the warehouse as it is now; empty without a dock. */
    private Optional<RackPosition> rackAt(BlockPos pos) {
        return layout == null ? Optional.empty() : resolveRack(layout, pos);
    }

    private void onAisleLetterChanged(int index) {
        if (level == null || level.isClientSide)
            return;
        char letter = AisleLetterBehaviour.letterOf(index);
        char before = carriedAisleLetter;
        carriedAisleLetter = letter;
        // The name follows the letter it was given to (M25, issue #15), and it does so BEFORE the layout is looked at
        // (M25 review fix). The names deliberately outlive a dock that was lost, moved or turned, and the value box
        // deliberately still scrolls then - so "no layout" is a state in which a letter really does move and a name
        // really has to move with it. Returning early left the name on the old letter, and because the rename is a
        // swap, scrolling back then moved it onto the letter the warehouse had just taken: a state no further scroll
        // could repair.
        //
        // The old letter comes from this controller's own field rather than from the layout, for the same reason:
        // without a layout there is no letter to read there. ScrollValueBehaviour#setValue has already written its
        // own field by the time it calls this back, which is why the previous value cannot be read off the box.
        //
        // It is a SWAP, not an overwrite, because scrolling the box is reversible and the table's answer to it has to
        // be too: scrolling back restores exactly what was there. It also mirrors what the next re-link does to the
        // letters themselves - BranchTable#assign gives branch 0 this box's letter and moves the branch that held it
        // to the lowest free one - so each name stays with the aisle the player gave it to.
        if (before != letter && !names.isEmpty()) {
            names.rename(before, letter);
            // setChanged() and sendData() once, so the moved name is saved and drawn. applyLayout does both for a
            // warehouse that has one; without a layout nothing else would.
            nameChanged();
        }
        if (layout != null)
            applyLayout(layout.withLetter(letter));
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
        // Flag only, never a ticket: this runs inside Level#tickBlockEntities' fresh-block-entity pass, and forcing a
        // chunk loads block entities, which would re-enter this method (M19, ADR-031).
        markChunkKeepDirty();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() != before)
            relinkRequested = true; // rotated: another dock position
    }

    /**
     * Real removal: the dock loses its controller link, the keepers stop signalling for a warehouse that no longer
     * exists, and the aisle leaves the registry. A chunk unload does none of this ({@link #invalidate()}, ADR-013).
     */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel) {
            unlinkDock();
            clearAllKeeperRuleStates(layout);
            clearAllHomePointStates();
            clearAllProductionStops();
            WarehouseRegistry.unregister(level, worldPosition);
            // The owner of the tickets is gone, so the tickets go with it, in this tick. A ticket that outlives its
            // owner is the one defect this whole feature must not have (M19, ADR-031).
            AisleChunkTickets.releaseOnRemove(level, worldPosition);
        }
    }

    /**
     * Removal or chunk unload. The level is not touched here (it may be unloading); a dock that stays loaded notices a
     * lost controller in its own periodic check ({@code StackerCraneBlockEntity#validateControllerLink}).
     */
    @Override
    public void invalidate() {
        super.invalidate();
        if (level instanceof ServerLevel) {
            WarehouseRegistry.unregister(level, worldPosition);
            // A safety net: our own ticket keeps this chunk loaded, so a runtime unload while holding is pathological.
            // During a shutdown this must NOT release, or the save would lose the hold and with it an in-progress job.
            AisleChunkTickets.releaseOnInvalidate(level, worldPosition);
        }
    }

    // --- goggles -------------------------------------------------------------------------------------------------

    /**
     * How many aisles the goggle line names before it falls back to "and N more" (M21, ADR-033). A goggle tooltip is
     * read at a glance and the address format allows 26 aisles; six letters and six numbers is about as much as one
     * line carries, and {@code /wareworks chunks} and the controller's display board are where the rest belongs.
     */
    private static final int GOGGLE_AISLES_LISTED = 6;

    /**
     * One {@code "letter length"} entry per aisle, in aisle order, for {@link WareworksLang#networkAisles} — and
     * {@code "letter name"} for an aisle a player has named (M25, issue #15, ADR-038).
     * <p>
     * The name <b>replaces</b> the length rather than being added to it. The warehouse's total rails are already on
     * the line above ({@link WareworksLang#networkSize}), the line's own budget is six entries ("six letters and six
     * numbers is about as much as one line carries"), and a length is what a player stops caring about once the aisle
     * has a name: "Aisles: A Ores, B Metals, C 14" is one line a player reads at a glance, while
     * "A Ores 16, B Metals 14, C 14" is not.
     * <p>
     * Public so that the tests which assert this line build it from the one implementation instead of a copy of it:
     * a second copy of the rule is a second rule ({@code CombVisualScenario}).
     */
    public static List<String> aisleEntries(NetworkGoggleInfo network) {
        List<String> entries = new ArrayList<>(network.aisleCount());
        for (int aisle = 0; aisle < network.aisleCount(); aisle++) {
            String letter = network.letterOf(aisle).map(String::valueOf).orElse("?");
            String length = String.valueOf(network.aisleLengths().get(aisle));
            entries.add(letter + " " + network.nameOf(aisle).orElse(length));
        }
        return entries;
    }

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
                prioritisedLocationCount(), membership.inputCount(), membership.outputCount(), acceptingPortCount(),
                collectingPortCount(), membership.productionCount(),
                membership.misalignedCount(), stock.distinctKeys(), stock.totalItems(),
                // The fluid numbers come from the parallel index and are 0 for every warehouse without a fluid bay,
                // which keeps that warehouse's tooltip and its chunk packet exactly what they were (M30, D10).
                fluidStock.distinctKeys(), fluidStock.totalItems(), requests.openCount(),
                productionOrders.openCount(), governingRules, rulesBelowMinimum, rulesAtMaximum, stockPauses.size(),
                chunkKeepReason, chunkKeepChunks, aisleName(aisleLetter()), networkInfo(),
                // A fresh throughput snapshot on top of the dock's cached record (M25, issue #16, ADR-039). The dock
                // keeps its published goggle data free of the measurement on purpose — it publishes several times a
                // second while the crane works, and a line that came and went that often would flicker for ever — so
                // the number has to be merged in where it is really wanted. Here that is safe and exact, because this
                // method runs in exactly two places, neither of them a tick: an observation (throttled to one packet a
                // second) and a naming click.
                linkedDockEntity().map(dock -> dock.goggleInfo().withThroughput(dock.throughput())),
                dispatch.lastReason());
    }

    /**
     * Server: what the goggles and the aisle display board should say about the shape of the rails (M21, issue #1,
     * ADR-033), or nothing at all for a warehouse of one aisle whose rails simply end — which is every warehouse built
     * before M21.
     * <p>
     * The shape is the <b>dock's</b> discovery, not the controller's own layout: the dock is what walks the rails, and
     * it is the only place that knows where the walk stopped and why. Without a loaded dock there is nothing to say,
     * and the status line already says that much.
     * <p>
     * Computed on demand from state both blocks already hold, so it is a handful of field reads and never a scan
     * (ADR-026). The goggle tooltip reads the synced copy inside {@link ControllerGoggleSummary} instead, because it
     * runs on the client.
     */
    public Optional<NetworkGoggleInfo> networkInfo() {
        WarehouseLayout shown = layout;
        if (shown == null)
            return Optional.empty();
        return linkedDockEntity().flatMap(StackerCraneBlockEntity::discoveredNetwork)
                .flatMap(network -> NetworkGoggleInfo.of(network, shown.branchLetters(), names));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        ControllerGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_CONTROLLER).forGoggles(tooltip);
        // "Warehouse A", and "Warehouse A — Ores" once the aisle at the dock has a name (M25, issue #15, ADR-038).
        // The name travels in the synced summary, never read off the server's table, because this runs on the client.
        WareworksLang.warehouseLetter(aisleLetter(), shown.aisleName()).forGoggles(tooltip, 1);
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
        // The size of the warehouse. One aisle reads exactly as it always did; a warehouse that bends says how many
        // rails and aisles it is made of instead, and lists them underneath (M21, issue #1, ADR-033).
        Optional<NetworkGoggleInfo> network = shown.network();
        if (network.isPresent() && network.get().aisleCount() > 1) {
            NetworkGoggleInfo info = network.get();
            WareworksLang.networkSize(info.rails(), info.aisleCount(), shown.mastHeight()).forGoggles(tooltip, 1);
            WareworksLang.networkAisles(aisleEntries(info), GOGGLE_AISLES_LISTED).forGoggles(tooltip, 2);
        } else {
            WareworksLang.aisleSize(shown.aisleLength(), shown.mastHeight()).forGoggles(tooltip, 1);
        }
        // Where the rails were cut off short of what a player laid, and by what. Never merged into one message with
        // the status above: a warehouse can be perfectly ready and still stop at a rail that branches.
        network.filter(NetworkGoggleInfo::stopsShort)
                .ifPresent(info -> WareworksLang.networkStop(dockPos().offset(info.stopDx(), 0, info.stopDz()),
                        info.stop()).style(ChatFormatting.GOLD).forGoggles(tooltip, 1));
        WareworksLang.countLine(WareworksLang.GOGGLES_STORAGE_LOCATIONS, shown.storageLocations())
                .forGoggles(tooltip, 1);
        if (shown.filteredLocations() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_FILTERED_LOCATIONS, shown.filteredLocations())
                    .forGoggles(tooltip, 2);
        // A count, so the summary stays bounded whatever the warehouse holds, and only while something is prioritised.
        if (shown.prioritisedLocations() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRIORITISED_LOCATIONS, shown.prioritisedLocations())
                    .forGoggles(tooltip, 2);
        WareworksLang.stationCounts(shown.inputs(), shown.outputs()).forGoggles(tooltip, 1);
        // Indented under the station counts, and only while a port really accepts: an aisle of plain outputs shows the
        // line it always showed (M17, issue #12).
        if (shown.acceptingPorts() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_ACCEPTING_PORTS, shown.acceptingPorts())
                    .forGoggles(tooltip, 2);
        // And the collecting ones, by the same rule (M18, issue #13): the third direction is the other answer to "why is
        // nothing arriving at my input" — because the warehouse fetches instead.
        if (shown.collectingPorts() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_COLLECTING_PORTS, shown.collectingPorts())
                    .forGoggles(tooltip, 2);
        if (shown.productionStations() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_STATIONS, shown.productionStations())
                    .forGoggles(tooltip, 1);
        if (shown.misaligned() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_MISALIGNED_COUNT, shown.misaligned())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_ITEM_TYPES, shown.itemTypes()).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_ITEMS_STORED, shown.totalItems()).forGoggles(tooltip, 1);
        // What the warehouse holds as FLUID, directly under the two item numbers and never merged into them (M30,
        // issue #21, D10): the item lines come from the item index, and a warehouse holding "lava: 64 buckets" and
        // "bucket: 17" has to say both without either pretending to be the other.
        //
        // Behind one guard, so a warehouse without a fluid bay — which is every warehouse built before M30 — shows the
        // tooltip it always showed, down to the byte: the two synced fields are left out of the tag under the same
        // condition. This tooltip is already 14 lines for an ordinary working warehouse, so two more lines are paid for
        // by the one build that wanted them.
        //
        // Shown in BUCKETS although everything inside is counted in millibuckets (D9), with the bay's own two rules:
        // two fraction digits, and millibuckets instead for a total a bucket figure would round to 0 — a warehouse
        // holding 7 mB must not say it holds nothing.
        if (shown.fluidTypes() > 0) {
            WareworksLang.countLine(WareworksLang.GOGGLES_FLUID_TYPES, shown.fluidTypes()).forGoggles(tooltip, 1);
            WareworksLang.fluidStored(shown.fluidMillibuckets()).forGoggles(tooltip, 1);
        }
        WareworksLang.countLine(WareworksLang.GOGGLES_OPEN_REQUESTS, shown.openRequests()).forGoggles(tooltip, 1);
        if (shown.productionOrders() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_ORDERS, shown.productionOrders())
                    .forGoggles(tooltip, 1);
        // What the aisle's stock keepers are doing to it (M15, issue #3). The two warnings are indented under the
        // count and are left out while they are 0, so an aisle whose rules are all satisfied shows one quiet line.
        if (shown.stockRules() > 0) {
            WareworksLang.countLine(WareworksLang.GOGGLES_STOCK_RULES, shown.stockRules()).forGoggles(tooltip, 1);
            if (shown.rulesBelowMinimum() > 0)
                WareworksLang.countLine(WareworksLang.GOGGLES_RULES_BELOW_MINIMUM, shown.rulesBelowMinimum())
                        .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
            if (shown.rulesAtMaximum() > 0)
                WareworksLang.countLine(WareworksLang.GOGGLES_RULES_AT_MAXIMUM, shown.rulesAtMaximum())
                        .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
        }
        // The safety stop is red, not gold: it is the one line that means a machine ate a batch and the warehouse
        // stopped making that item until a player looks at it (M15 part 2, issue #3).
        //
        // It is deliberately outside the stock-rule block (M20 review fix). Since M20 any order's lost batch arms the
        // stop, and the item it holds is normally an intermediate of a chain that no rule governs at all - an aisle
        // without a single stock keeper can be holding one. Nested under the rules while there are any, on its own line
        // when there are none.
        //
        // It says "Stopped products", the very line the production station and the aisle's display board show for the
        // same number (M20 part 2): a player meets this count on three surfaces and has to recognize it as one state.
        if (shown.rulesPaused() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_STOPPED, shown.rulesPaused())
                    .style(ChatFormatting.RED).forGoggles(tooltip, shown.stockRules() > 0 ? 2 : 1);
        // What this aisle is doing with chunks (M19, issue #10). Left out entirely while there is nothing to report,
        // which is every aisle on a server that has chunk loading switched off - the default.
        if (shown.chunkKeepReason() != ChunkKeepReason.NONE)
            chunkKeepLine(shown).forGoggles(tooltip, 1);
        shown.crane().ifPresent(crane -> {
            WareworksLang.translate(WareworksLang.GOGGLES_STACKER_CRANE).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
            // The short form, which is what the throughput adds here: the busy headline and, above zero, the blocked
            // share — the two numbers a player came to this block for ("is the crane my bottleneck, and is anything
            // holding it"). The breakdown and the trip counts stay on the dock (M25, issue #16, ADR-039). This
            // tooltip is already 14 lines for an ordinary working warehouse and about 28 for a built-out one, so two
            // is what it can afford, and not showing the held items keeps it to two.
            crane.addGoggleLines(tooltip, 2, false);
        });
        shown.lastPlanReason().ifPresent(reason -> WareworksLang.lastPlan(reason).forGoggles(tooltip, 1));
        // What a player has to do to see a name here at all (M25, issue #15, ADR-038). It is the last line of the
        // tooltip and dark grey, so it never pushes a number anybody asked for out of the way.
        if (showsNamingHint(shown))
            WareworksLang.translate(WareworksLang.GOGGLES_AISLE_NAME_HINT).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        return true;
    }

    /**
     * Whether the controller's goggles teach the naming gesture (M25, issue #15, ADR-038): only while <b>no name is
     * visible on them at all</b>, so it teaches until this tooltip can show a name of its own.
     * <p>
     * The gate is deliberately "nothing on this tooltip carries a name" and not "the warehouse has no name anywhere".
     * The two synced carriers are the whole truth a client has; a stale name left on a letter this warehouse has no
     * aisle for any more would be invisible either way, so hiding the hint for it would hide it for nothing; and the
     * alternative is a third synced value that exists only to suppress a hint.
     * <p>
     * <b>Where the two readings really differ, said plainly</b> (M25 review fix), because the words elsewhere used to
     * promise more than this: {@link ControllerGoggleSummary#aisleName()} is the <b>dock</b> aisle's name only, and
     * {@link NetworkGoggleInfo#names} lists {@value NetworkGoggleInfo#NAMES_LISTED} aisles. So on a warehouse of more
     * than six aisles whose only named aisle is the seventh or later, this keeps returning {@code true} while the
     * name is saved, drawn on that aisle's members' address lines and drawn on the display board's names row. A hint
     * that outstays its welcome, not a wrong number — and the price of exactness is a 26th component on a record
     * whose own javadoc warns that an added argument silently shifts every count after it.
     * <p>
     * Its own method so that the rule has exactly one implementation: the tooltip draws it and
     * {@code gametest.WarehouseControllerGameTests#aisleNameSurfaces} asserts it, on a dedicated server where a goggle
     * line cannot be built at all ({@code LangBuilder#forGoggles} measures the client font).
     */
    public static boolean showsNamingHint(ControllerGoggleSummary shown) {
        return shown.aisleName().isEmpty() && shown.network().filter(NetworkGoggleInfo::hasNames).isEmpty();
    }

    /**
     * The goggle line for the chunk hold: what is held and why, or what stops the aisle from holding. The two refusals
     * that are about a number name both numbers, because "needs 12 of 8 chunks" is the only form a player can act on.
     */
    private static net.createmod.catnip.lang.LangBuilder chunkKeepLine(ControllerGoggleSummary shown) {
        ChunkKeepReason reason = shown.chunkKeepReason();
        if (reason.isHolding())
            return WareworksLang.chunkLoading(shown.chunkKeepChunks(), reason);
        return switch (reason) {
            case AT_LEVEL_LIMIT -> WareworksLang.chunkLoadingAtLimit(WareworksConfig.maxTicketedAislesPerLevel());
            case AT_COLLECT_LIMIT ->
                WareworksLang.chunkLoadingAtCollectLimit(WareworksConfig.maxCollectHoldAislesPerLevel());
            case TOO_MANY_CHUNKS -> WareworksLang.chunkLoadingTooMany(shown.chunkKeepChunks(),
                    WareworksConfig.maxChunksPerAisle());
            default -> WareworksLang.chunkLoadingNone(reason);
        };
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
        // Only a warehouse that really bends writes anything here, so a straight aisle saves the bytes it always did.
        ControllerPersistence.writeNetwork(tag, layout, branchTable);
        // And only a warehouse somebody has named writes anything here, so a world from 0.7.0 saves byte for byte what
        // it did before the names existed (M25, issue #15).
        ControllerPersistence.writeNames(tag, names);
        ControllerPersistence.writeLocations(tag, membership, stock.readOnlyView(), registries);
        ControllerPersistence.writeRequests(tag, requests.requests(), worldPosition, registries);
        ControllerPersistence.writeProductionOrders(tag, productionOrders.all(), registries);
        // The aisle's stock rules are saved with the controller on purpose: a keeper's chunk can be unloaded while
        // this controller plans, and a rule that is read as "no rule" would store past a maximum or hand out a
        // reserve, which nothing ever undoes (AisleStockRules).
        ControllerPersistence.writeStockRules(tag, stockRules.saved(), registries);
        // The safety stop is saved with the rules and for the same reason: a restart must not quietly resume ordering
        // into a machine that already swallowed a batch (M15 part 2, issue #3).
        ControllerPersistence.writeStockPauses(tag, stockPauses, registries);
        // The one thing M19 saves, and only while it is set: the bound "this aisle may not hold its chunks again until
        // its work really changes" has to outlive this block entity instance, because the work it refuses does (§11.4).
        ControllerPersistence.writeChunkKeep(tag, chunkKeepGaveUp, chunkKeepFingerprint);
    }

    /**
     * The warehouse a save describes: the aisle at the dock from {@code Layout} — the only thing a world built before
     * M21 saved, and still the whole warehouse of every build that never bends — plus the further aisles and the
     * pinned lines from {@code Network}, if there are any (ADR-033).
     * <p>
     * A {@code Network} that does not describe a valid warehouse is dropped rather than trusted: the rails are
     * rediscovered on the first re-link anyway, so the worst a broken tag can cost is one straight aisle for a few
     * ticks, and the records that are read next refer to positions that really exist.
     */
    private WarehouseLayout savedWarehouse(CompoundTag tag, Direction facing,
                                           ControllerPersistence.SavedLayout saved) {
        BlockPos dockPos = worldPosition.relative(facing);
        NetworkGeometry single = NetworkGeometry.of(saved.geometry(), Headings.of(facing));
        ControllerPersistence.SavedNetwork network = ControllerPersistence.readNetwork(tag);
        branchTable.restore(network.lines());
        if (network.branches().isEmpty())
            return oneAisle(dockPos, facing, saved);
        try {
            List<BranchGeometry> branches = new ArrayList<>(network.branches().size() + 1);
            branches.add(single.firstBranch());
            branches.addAll(network.branches());
            NetworkGeometry geometry = new NetworkGeometry(branches, saved.geometry().height());
            return WarehouseLayout.of(dockPos, facing, geometry, Optional.of(aisleLetter()))
                    .withBranchLetters(network.letters());
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Could not read the saved warehouse of the controller at {}; keeping its first aisle",
                    worldPosition, e);
            branchTable.clear();
            return oneAisle(dockPos, facing, saved);
        }
    }

    /** The warehouse a save that describes one straight aisle means: the warehouse of every build up to 0.5.0. */
    private WarehouseLayout oneAisle(BlockPos dockPos, Direction facing, ControllerPersistence.SavedLayout saved) {
        return WarehouseLayout.single(BranchLayout.of(dockPos, facing, saved.geometry()).withLetter(aisleLetter()));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        aisleLetter.value = Mth.clamp(aisleLetter.value, AisleLetterBehaviour.FIRST_INDEX, AisleLetterBehaviour.LAST_INDEX);
        // The letter a name has to be carried off when the box is next scrolled (M25). Read back from the box, which
        // is saved, so no second value goes into the save file.
        carriedAisleLetter = aisleLetter();
        if (clientPacket) {
            summary = ControllerGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        // Read unconditionally, and deliberately NOT gated on the layout the way the records, requests and orders
        // below are: names are labels a player wrote, not state derived from the rails, so they survive a dock that
        // was lost, moved or turned exactly as they survive a chunk unload (M25, issue #15). Rebuild the dock and the
        // labels are back. Only breaking the controller takes them away, with everything else it knows.
        names.copyFrom(ControllerPersistence.readNames(tag));
        Direction facing = facing();
        // Records are warehouse-local: a layout saved for another direction (e.g. a rotated structure) is dropped.
        layout = ControllerPersistence.readLayout(tag)
                .filter(saved -> saved.facing() == facing)
                .map(saved -> savedWarehouse(tag, facing, saved))
                .orElse(null);
        if (layout == null)
            branchTable.clear();
        status = statusOf(layout);
        clearAisleState();
        // M19: the give-up bound belongs to the work it refuses, so it is restored with that work and dropped whenever
        // the work is (a layout that was not saved, or was saved for another facing, drops the requests and orders too).
        ControllerPersistence.SavedChunkKeep savedChunkKeep = layout == null
                ? ControllerPersistence.SavedChunkKeep.NONE : ControllerPersistence.readChunkKeep(tag);
        chunkKeepGaveUp = savedChunkKeep.gaveUp();
        chunkKeepFingerprint = savedChunkKeep.workFingerprint();
        chunkKeepDirty = true;
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
            // Port policies are not saved either, and a restored port is no "added" member, so nothing else would ever
            // ask it: a continuous port has to be found again after a load (M17, the M8 cold-cache lesson).
            for (LocationRecord record : membership.records(LocationKind.OUTPUT))
                ports.markUnread(record.position());
            // Requests belong to the saved layout; without it (or for another facing) they are dropped like the records.
            requests.restore(ControllerPersistence.readRequests(tag, worldPosition, registries));
            // Production orders belong to the saved layout like the requests do. Their deadlines are not saved, so
            // the first tick gives every restored order its full timeout again (§3.5) — and the same tick checks the
            // saved production plans, because a truncated save must not leave a step waiting for a parent that is not
            // there any more (M20, ProductionOrders#validatePlans).
            productionOrders.restore(ControllerPersistence.readProductionOrders(tag, registries));
            productionRestorePending = true;
            // Restored before the first tick, so the very first plan after a load already knows every maximum and
            // every reserve, even while the keepers themselves are still in unloaded chunks.
            stockRules.restore(ControllerPersistence.readStockRules(tag, registries));
            stockRules.setCap(WareworksConfig.maxStockRules());
            stockPauses.clear();
            stockPauses.putAll(ControllerPersistence.readStockPauses(tag, registries));
            restockOutcomes = Map.of();
        }
        relinkRequested = true;
        stockRulesRefreshPending = true;
        homePointsRefreshPending = true;
        if (level instanceof ServerLevel && !isRemoved()) {
            // Data changed on a live block entity (e.g. /data merge): keep the registry in step.
            if (layout != null)
                WarehouseRegistry.register(level, worldPosition, layout);
            else
                WarehouseRegistry.unregister(level, worldPosition);
        }
    }
}
