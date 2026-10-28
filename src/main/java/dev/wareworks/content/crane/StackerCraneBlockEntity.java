package dev.wareworks.content.crane;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.CraneMember;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.head.HandlingHead;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.kinetics.StaleOverstressCheck;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CraneNetwork;
import dev.wareworks.core.crane.HomeReturn;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneResync;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.core.crane.RemainingTrip;
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailGraph;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.util.CraneTrafficLang;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.Headings;
import dev.wareworks.util.LogThrottle;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Block entity of the stacker crane dock ({@code docs/stacker-crane.md} §2-6).
 * <p>
 * <b>Kinetics.</b> A {@link KineticBlockEntity} with a constant stress impact registered through
 * {@code WareworksStress} from the server config. {@link #getSpeed()} (0 without rotation and while overstressed) drives
 * the crane axes through {@link CraneKinematics} and the {@code crane.*} speed factors.
 * <p>
 * <b>Geometry.</b> The dock discovers the connected set of warehouse rails in front of it
 * ({@link RailNetworkScan}, ADR-033) and its own aisle length is the first branch of that network, capped at
 * {@code maxAisleLength}; height = the "Mast Height" scroll value ({@value #MIN_MAST_HEIGHT}..{@code maxMastHeight},
 * default {@value #DEFAULT_MAST_HEIGHT}). The server re-scans at most once per {@code geometryRefreshTicks},
 * and immediately on load, on a mast height change and after a facing change (next tick). A refresh is bounded by
 * {@code maxNetworkRails} aisle blocks, reads every position at most once and never loads chunks; rails behind an
 * unloaded chunk keep the last known length ({@link dev.wareworks.core.address.AisleGeometry#scannedLength}). The
 * length is saved and synced; the rest of the discovered network is server-side only until the controller takes it
 * over.
 * <p>
 * <b>Controller.</b> The warehouse controller directly behind the dock links it ({@link #linkController}) and becomes its
 * owner; only the owner can unlink it ({@link #unlinkController}). The dock re-validates its owner at its geometry
 * refresh cadence ({@link #validateControllerLink}) and hints the controller behind it to re-link whenever something it
 * reads may have changed (geometry, load, rotation, removal).
 * <p>
 * <b>Crane (M3).</b> The moving crane is logical state of this block entity: a {@link CraneState} (pose, phase, current
 * {@link TransportJob}) and an {@link InventoryGrabber} holding the real items in transit. The server tick
 * ({@link CraneExecution}) feeds {@code core.crane.CraneStateMachine}, executes its effects on the world (real
 * extraction and insertion through the handling head, in the tick the phase ends) and reports to the controller. The
 * controller assigns jobs through {@link #assignJob} while the crane is idle, powered and empty, and cancels them through
 * {@link #cancelJob}. Without a controller the crane finishes a job whose target is valid and holds items it cannot
 * deliver.
 * <p>
 * <b>Sync.</b> Clients receive the pose, target, phase and pause flag ({@link CranePersistence#writeSync}) and a bounded
 * {@link CraneGoggleInfo} on every phase, job, target, head, pause or geometry change and every
 * {@link CraneExecution#MOVING_SYNC_INTERVAL_TICKS} while moving. The dock is additionally
 * {@link GoggleObservers.Observable}: while a player really looks at it through goggles it refreshes its throughput
 * and syncs a change at most once a second ({@link #onGoggleObserved}), which is the only way a <b>parked</b> crane's
 * numbers can stay honest. The client runs the same {@link CraneMotion} towards the
 * synced target every tick (previous poses for partial-tick rendering, M4) and snaps only when its own pose has really
 * drifted ({@link CraneResync#diverges}).
 * <p>
 * <b>Several cranes (issue #14, ADR-075).</b> A dock without a controller of its own that faces into a warehouse's
 * rails joins that warehouse as another crane ({@link #joinWarehouse}): its poses, routes and job are then named in the
 * warehouse's frame, measured from the warehouse's own dock (the <b>anchor</b>, {@link CraneMembership}), so it renders
 * and plays its sounds with the offset between the two blocks, skips its own rail scan and parks at its own dock. Every
 * crane of such a warehouse asks the controller's traffic ledger before it leaves the blocks it stands on
 * ({@link CraneExecution}), saves what it holds of the rails ({@link CraneTrafficClaim}) and tells clients when it is
 * held or drives a route other than the cheapest one. A crane of a warehouse with one crane has no membership and
 * writes exactly what it wrote up to 0.17.1.
 * <p>
 * <b>Item conservation.</b> {@link #destroy()} (real break) drops the head at the dock and tells the controller to release
 * the job; {@link #clearContent()} (commands, structures) empties the head without drops, so {@code /clone ... move}
 * cannot duplicate held items. Saving never throws, loading is bounded ({@link InventoryGrabber#load}).
 */
public class StackerCraneBlockEntity extends KineticBlockEntity implements Clearable, GoggleObservers.Observable {
    public static final int MIN_MAST_HEIGHT = AisleGeometry.MIN_HEIGHT;
    public static final int DEFAULT_MAST_HEIGHT = 4;
    /** Parking pose: aisle position 0 at dock level, arm retracted. */
    public static final CranePose HOME_POSE = CranePose.at(0.0, 0.0, CranePose.DEFAULT_SIDE);
    /** Margin in blocks added around the rack bounds for the render bounding box (mast top, arm reach). */
    private static final double RENDER_BOUNDS_MARGIN = 1.0;

    /** NBT key of the aisle length (saved and synced). */
    public static final String AISLE_LENGTH_TAG = "AisleLength";
    /**
     * NBT key of the warehouse's rail network, packed as an {@code int[]} ({@link NetworkGeometry#pack}) and saved and
     * synced <b>only while the warehouse really bends</b> (M21, ADR-033). A warehouse of one straight aisle is fully
     * described by {@link #AISLE_LENGTH_TAG} and the dock's facing, so it writes exactly the bytes it always wrote.
     */
    public static final String NETWORK_TAG = "Network";
    /** NBT key of the controller link flag (client packets only). */
    public static final String CONTROLLER_LINKED_TAG = "ControllerLinked";
    /** NBT key of the crane goggle data (client packets only). */
    public static final String GOGGLE_TAG = "CraneGoggles";
    /** Key inside the client packet's {@link CraneMembership#TAG}: the crane stands still for traffic (issue #14). */
    private static final String HOLD_TAG = "Hold";
    /**
     * Key inside the client packet's {@link CraneMembership#TAG}: the route a detour drives instead of the cheapest. A
     * client packet never carries the claim, whose committed route the save keeps under the same name.
     */
    private static final String DETOUR_TAG = "Route";
    /** Key inside the client packet's {@link #GOGGLE_TAG}: why the dock is no crane of the warehouse it faces. */
    private static final String JOIN_TAG = "Join";

    /**
     * "Mast Height" value box. Assigned in {@link #addBehaviours}, which {@code SmartBlockEntity} calls from its
     * constructor, so this field must not have an initializer (it would reset the behaviour to {@code null}).
     */
    protected ScrollValueBehaviour mastHeight;

    /** Number of rails of the aisle. Saved and synced. */
    private int aisleLength;
    /** Whether a warehouse controller serves this crane. Synced, not saved. */
    private boolean controllerLinked;

    // --- server only ---
    /** Position of the controller that linked this dock; {@code null} without. Not saved (it links again on load). */
    @Nullable
    private BlockPos linkedController;
    private boolean geometryRefreshRequested;
    private long nextGeometryRefreshTick;
    /** Geometry at the last refresh; {@code null} until the first refresh. */
    @Nullable
    private AisleGeometry publishedGeometry;
    /**
     * The whole rail network the last refresh found, not only this dock's own aisle; {@code null} until the first
     * refresh. Never saved and never synced — it is rediscovered from the world on every refresh, so it can never
     * contradict the blocks that are there ({@code docs/warehouse-system.md} §4).
     */
    @Nullable
    private RailNetwork discoveredNetwork;
    /** The last stop reason that was reported, so a standing fault is logged once and not on every refresh. */
    private NetworkStop reportedStop = NetworkStop.END;
    private final LogThrottle networkStops = new LogThrottle();
    /**
     * The rack position of the warehouse's home point, i.e. where a crane with nothing to do waits (M21, ADR-034,
     * {@code docs/stacker-crane.md} §4.7); {@code null} while the dock is home, which is every warehouse that has no
     * home point.
     * <p>
     * The <b>controller</b> owns it ({@code WarehouseControllerBlockEntity#refreshHomePoints} → {@link #setHomePoint}),
     * because only it knows the whole warehouse and therefore which of several home points is the one. Neither saved
     * nor synced: it says nothing about where the crane <i>is</i>, only where it would go next, and the controller
     * hands it over again on its first re-link after every load — until then the crane simply waits where it stands,
     * which is what it did before this version.
     */
    @Nullable
    private RackPosition homePoint;
    /**
     * Whether the last rail scan was taken as the dock of a warehouse, i.e. with a controller of its own behind it.
     * Only such a scan lets other docks join (issue #14): a dock nobody serves keeps discovering exactly what it
     * discovered up to 0.17.1, and a controller placed behind it rescans it before it links ({@link #linkController}).
     */
    private boolean scannedAsPrimary;
    /**
     * What this crane holds of the rails beyond the blocks it stands on, as the traffic ledger last told it (issue #14,
     * ADR-075 §13). Saved inside {@link CraneMembership#TAG}, only while there is a membership.
     */
    private CraneTrafficClaim trafficClaim = CraneTrafficClaim.NONE;
    /**
     * What this crane's goggles say about the other cranes of its warehouse, as its last traffic tick worked it out
     * (issue #14, ADR-075 §15.1); {@code null} for a crane of a warehouse with one crane, which never has any. Never
     * saved: the next tick works it out again.
     */
    @Nullable
    private CraneTrafficInfo trafficInfo;
    /**
     * Why this dock, facing into another warehouse's rails, is no crane of it — as of the last time a player looked at
     * it through goggles (issue #14, ADR-075 §15.1); {@code null} when there is nothing to say, which is every dock
     * nobody looks at. Synced inside the goggle data, never saved.
     */
    @Nullable
    private DockJoinStatus observedJoin;
    /**
     * Whether the controller has no room for this crane any more ({@code aisle.maxCranesPerNetwork} lowered, issue #14,
     * ADR-075 §2.5): it takes no new job and drives home to its own dock as soon as it is idle, and the controller lets
     * it go once it stands there. Told on every relink, never saved.
     */
    private boolean leavingWarehouse;
    /**
     * Whether the warehouse of several cranes this crane belonged to is gone (its controller broken or no longer listing
     * it, its own dock gone) while the machine was not parked on its own dock (issue #14, ADR-075 §2.5). The crane then
     * keeps its role and its claim, so its traffic tick finds no controller and holds it inside its own blocks, and it
     * only becomes a crane by itself once it stands on its own dock or a controller takes it in again. Never saved: a
     * crane that loads finds its controller gone again and comes back to the same state.
     */
    private boolean dissolvePending;

    // --- both sides ---
    /**
     * Which warehouse of several cranes this one belongs to, its frame and its number (issue #14, ADR-075);
     * {@code null} for a crane of a warehouse with one crane, which is every crane up to 0.17.1. Saved and synced
     * ({@link CraneMembership#TAG}).
     */
    @Nullable
    private CraneMembership membership;
    /** Whether the traffic ledger holds the crane where it stands (synced, so a client does not animate it on). */
    private boolean trafficHold;
    /** The route a traffic detour drives instead of the cheapest one (synced), or {@code null}. */
    @Nullable
    private CraneRoute trafficDetour;
    /**
     * The warehouse's rail network as the controller names it — the branch order, headings, origins and lengths every
     * rack position of a job is measured in. Saved and synced (packed, {@link #NETWORK_TAG}), {@code null} while the
     * warehouse is the one straight aisle it was before M21, which is then exactly what the dock's own facing and
     * {@link #aisleLength} describe.
     * <p>
     * The <b>controller</b> owns it ({@code WarehouseControllerBlockEntity#applyLayout} → {@link #setWarehouseNetwork}),
     * not the scan: the controller pins aisle letters and origin ends, so only its numbering agrees with the rack
     * positions in a crane job. A dock nobody serves keeps what it last saved, and a crane without a job never asks.
     */
    @Nullable
    private NetworkGeometry warehouseNetwork;
    @Nullable
    private AisleGeometry cachedGeometry;
    @Nullable
    private WarehouseLayout cachedWarehouse;
    @Nullable
    private CraneNetwork cachedCraneNetwork;
    /** Server: the authoritative crane state (saved). Client: the synced pose, target and phase (no job). */
    private CraneState<ItemKey, RackPosition> craneState = CraneState.idle(HOME_POSE);
    /**
     * Server: goggle data as of the last publication. Client: as synced.
     * <p>
     * The throughput is deliberately <b>not</b> in here on the server: {@link #refreshGoggleInfo} rebuilds this field
     * several times a second for a working crane, and a measurement rebuilt from scratch each time would either flicker
     * in and out of the client's copy or force the dock to snapshot its window on every publish. It is merged in at
     * {@linkplain #write write} time instead, from {@link #observedThroughput}.
     */
    private CraneGoggleInfo goggleInfo = CraneGoggleInfo.NONE;
    /** Client: whether a crane packet arrived (the first one always snaps). */
    private boolean clientSynced;
    /**
     * Client: how far the machine has really travelled through the world since it was loaded, in blocks. Purely
     * visual — never saved, never synced — and the only thing that can turn the wheels correctly now that
     * {@link CranePose#x()} restarts at 0 on every aisle the crane hands over to (ADR-033).
     */
    private double wheelOdometer;

    // --- server only (M3) ---
    private final InventoryGrabber head = new InventoryGrabber(this::onHeadChanged);
    private final CraneExecution execution = new CraneExecution(this);
    /** Server: clears an overstress Create keeps after the network has the capacity again (issue #38, ADR-071). */
    private final StaleOverstressCheck staleOverstress = new StaleOverstressCheck();
    /**
     * Server: the throughput snapshot that goes out with the next client packet, or {@code null} while <b>nobody has
     * looked at this dock through goggles</b> and the numbers therefore do not exist as far as any client is concerned
     * (M25, issue #16, ADR-039).
     * <p>
     * This is what keeps the feature free for everybody else: a dock <b>nobody has looked at</b> never snapshots its
     * window, never grows its update tag by a byte and never sends a packet it would not have sent anyway.
     * <p>
     * Once it has been looked at, the last snapshot keeps riding the packets the dock sends anyway until an
     * observation replaces it — about 44 bytes, and never a packet of its own. There is deliberately no "stopped
     * looking" hook to clear it: a player who is not looking sees nothing either way, one who looks again has it
     * refreshed within {@value GoggleObservers#SCAN_INTERVAL_TICKS} ticks, and a machine that goes idle nulls it by
     * itself as soon as its window decays to nothing.
     */
    @Nullable
    private CraneThroughput observedThroughput;
    /**
     * Server: at most one throughput sync per {@value GoggleObservers#SUMMARY_SYNC_MIN_INTERVAL_TICKS} ticks, shared
     * with every other observed goggle summary in the mod.
     */
    private final SyncThrottle throughputSync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public StackerCraneBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // The value box sits on the top face of the low rail bed, the only face of the block that is large enough.
        mastHeight = new ScrollValueBehaviour(WareworksLang.translateDirect(WareworksLang.CRANE_MAST_HEIGHT), this,
                new MastHeightValueBox()) {
            /**
             * A schematic carries the height only while it is not the default (issue #25, ADR-061): the dock's read
             * turns a missing {@code ScrollValue} into exactly that default, so a dock nobody adjusted adds nothing to
             * a schematic. Saves and client packets keep Create's unconditional key.
             */
            @Override
            public void writeSafe(CompoundTag nbt, HolderLookup.Provider registries) {
                if (value != Mth.clamp(DEFAULT_MAST_HEIGHT, MIN_MAST_HEIGHT, maxMastHeight()))
                    super.writeSafe(nbt, registries);
            }
        };
        int max = maxMastHeight();
        mastHeight.between(MIN_MAST_HEIGHT, max);
        // Default by direct field write: setValue would run the callback while level == null.
        mastHeight.value = Mth.clamp(DEFAULT_MAST_HEIGHT, MIN_MAST_HEIGHT, max);
        mastHeight.withCallback(this::onMastHeightChanged);
        behaviours.add(mastHeight);
    }

    // --- geometry -----------------------------------------------------------------------------------------------

    /** The aisle direction. */
    public Direction facing() {
        return getBlockState().getOptionalValue(HorizontalKineticBlock.HORIZONTAL_FACING).orElse(Direction.NORTH);
    }

    /**
     * The current mast height (number of reachable levels): the scroll value, clamped to the configured maximum
     * <b>on read</b>. The stored value is never rewritten, so lowering {@code aisle.maxMastHeight} only shortens the
     * mast while that configuration is in force; raising the limit again brings the player's own height back
     * ({@code docs/warehouse-system.md} §8.1).
     */
    public int mastHeight() {
        return mastHeight == null ? DEFAULT_MAST_HEIGHT
                : Mth.clamp(mastHeight.getValue(), MIN_MAST_HEIGHT, maxMastHeight());
    }

    /** The number of rails of the aisle as of the last refresh (server) or sync (client). */
    public int aisleLength() {
        return aisleLength;
    }

    /** The aisle size in aisle-local coordinates. Synced to clients. */
    public AisleGeometry geometry() {
        AisleGeometry cached = cachedGeometry;
        int height = mastHeight();
        if (cached == null || cached.length() != aisleLength || cached.height() != height) {
            cached = new AisleGeometry(aisleLength, height);
            cachedGeometry = cached;
        }
        return cached;
    }

    /**
     * Server: the whole rail network the last refresh found, with the branch at this dock first, and where and why the
     * discovery stopped. Empty before the first refresh and on the client.
     */
    public Optional<RailNetwork> discoveredNetwork() {
        return Optional.ofNullable(discoveredNetwork);
    }

    /** World mapping of the aisle: dock position, facing and geometry (no aisle letter; the controller adds it). */
    public BranchLayout layout() {
        return BranchLayout.of(worldPosition, facing(), geometry());
    }

    /**
     * World mapping of the <b>whole warehouse</b> the crane drives on: every aisle of the network, with the one at the
     * dock first ({@code docs/warehouse-system.md} §1, ADR-033). Without a network it is literally {@link #layout()}
     * as a one-aisle warehouse, which is what every build that does not bend is.
     * <p>
     * Aisle letters are not in it — the controller owns those — so this answers positions and blocks, never addresses.
     */
    public WarehouseLayout warehouse() {
        NetworkGeometry network = networkGeometry();
        // A member crane's warehouse is measured from the warehouse's own dock, not from this one (issue #14).
        boolean anchored = anchoredElsewhere();
        BlockPos anchor = anchored ? membership.anchor() : worldPosition;
        Direction anchorFacing = anchored ? membership.anchorFacing() : facing();
        WarehouseLayout cached = cachedWarehouse;
        if (cached != null && cached.dock().equals(anchor) && cached.facing() == anchorFacing
                && cached.network().equals(network))
            return cached;
        cached = WarehouseLayout.of(anchor, anchorFacing, network, Optional.empty());
        cachedWarehouse = cached;
        return cached;
    }

    /**
     * Whether this crane's poses are named in the frame of <b>another</b> dock: a member of a warehouse with several
     * cranes that knows that warehouse's rails (issue #14, ADR-075).
     */
    private boolean anchoredElsewhere() {
        return membership != null && membership.isMember() && warehouseNetwork != null;
    }

    /**
     * The network the crane plans its motion in: the warehouse's aisles at the configured mast height, or the single
     * straight aisle of {@link #geometry()} while no controller has given this dock one.
     */
    public NetworkGeometry networkGeometry() {
        NetworkGeometry network = warehouseNetwork;
        int height = mastHeight();
        // A member keeps the warehouse's network however few aisles it has: a straight line is still measured from the
        // other dock, which its own facing and aisle length cannot describe (issue #14).
        if (network != null && anchoredElsewhere())
            return network.height() == height ? network : network.withHeight(height);
        if (network == null || network.branchCount() < 2)
            return NetworkGeometry.of(geometry(), Headings.of(facing()));
        return network.height() == height ? network : network.withHeight(height);
    }

    /**
     * The rails the crane drives on and what a turn on them costs ({@code crane.turnPenaltyBlocks}). Recomputed from
     * the live geometry, never stored in a job — so a crane follows rails a player has just changed, and a crane whose
     * route disappeared simply has none (ADR-033).
     * <p>
     * Kept until the shape or the penalty really changes: the client asks for it on <b>every</b> tick of a moving
     * crane, and the corner blocks it derives depend on the shape alone (M21 review fix).
     * <p>
     * Built on the {@link WarehouseLayout#routes() route table of this dock's own warehouse}, which {@link #warehouse}
     * keeps for as long as the shape lasts, so the machine's route questions and the ones asked about the same rails
     * through the layout read one table and one set of derived single-source passes (M22 review fix).
     */
    public CraneNetwork craneNetwork() {
        WarehouseLayout warehouse = warehouse();
        double penalty = WareworksConfig.turnPenaltyBlocks();
        CraneNetwork cached = cachedCraneNetwork;
        if (cached instanceof CraneNetwork.Discovered discovered && discovered.turnPenaltyBlocks() == penalty
                && discovered.routes() == warehouse.routes())
            return cached;
        cached = CraneNetwork.of(warehouse.routes(), penalty);
        cachedCraneNetwork = cached;
        return cached;
    }

    /**
     * Server: the warehouse's aisles as its controller names them, which is the numbering every rack position of a
     * crane job is measured in. Saved and synced, because a dock that loads before its controller must already put the
     * crane back where it stood — on a further aisle, facing the way that aisle runs.
     *
     * @param network the controller's network, or {@code null} for a warehouse of one aisle
     * @return whether it changed
     */
    public boolean setWarehouseNetwork(@Nullable NetworkGeometry network) {
        boolean member = membership != null && membership.isMember();
        NetworkGeometry next = network == null || (network.branchCount() < 2 && !member) ? null : network;
        if (Objects.equals(warehouseNetwork, next))
            return false;
        warehouseNetwork = next;
        cachedWarehouse = null;
        execution.onGeometryChanged();
        // The client invalidates its own render bounds when the packet arrives (see read); here only the packet.
        if (level != null && !level.isClientSide)
            notifyUpdate();
        return true;
    }

    /**
     * Server: the warehouse's home point, as its controller decided it — the rack position a crane with nothing to do
     * waits at, or {@code null} for the dock (M21, ADR-034).
     *
     * @return whether it changed
     */
    public boolean setHomePoint(@Nullable RackPosition rack) {
        if (Objects.equals(homePoint, rack))
            return false;
        homePoint = rack;
        return true;
    }

    /** Server: the warehouse's home point, or empty while the dock is home. */
    public Optional<RackPosition> homePoint() {
        return Optional.ofNullable(homePoint);
    }

    /**
     * How many aisles the crane can drive on, without building anything: {@code 1} for every warehouse that does not
     * bend, which is what {@link #networkGeometry()} would have to allocate a geometry to say. Read on every tick of
     * every idle dock ({@code CraneExecution#returnHomeIfIdle}), which is why it exists.
     */
    public int aisleCount() {
        NetworkGeometry network = warehouseNetwork;
        return network == null ? 1 : Math.max(1, network.branchCount());
    }

    /**
     * The rule that decides where this crane waits ({@link HomeReturn}, M21, ADR-034): the configured idle delay, the
     * number of aisles the crane can drive on and the home point — but only while the network still has the aisle that
     * home point stands on, because a label whose aisle a player broke away names no block.
     */
    public HomeReturn homeReturn() {
        NetworkGeometry network = networkGeometry();
        Optional<RackPosition> home = Optional.ofNullable(homePoint).filter(network::contains);
        return HomeReturn.of(WareworksConfig.returnHomeIdleTicks(), network.branchCount(), craneCount(), home);
    }

    // --- several cranes (issue #14, ADR-075) ---------------------------------------------------------------------

    /** Which warehouse of several cranes this crane belongs to, or empty for a crane of a warehouse with one. */
    public Optional<CraneMembership> membership() {
        return Optional.ofNullable(membership);
    }

    /** Whether this crane is a member: crane 2 or later, its dock not the warehouse's own. */
    public boolean isMember() {
        return membership != null && membership.isMember();
    }

    /** This crane's number: 1 at the warehouse's own dock and for every crane of a warehouse with one. */
    public int craneNumber() {
        return membership == null ? 1 : membership.number();
    }

    /** How many cranes this crane's warehouse has: 1 without a membership. */
    public int craneCount() {
        return membership == null ? 1 : membership.craneCount();
    }

    /** Whether this crane asks the traffic ledger before it moves: a crane of a warehouse with several. */
    public boolean inTraffic() {
        return membership != null && membership.craneCount() > 1;
    }

    /**
     * Server: whether the machine stands parked on its own dock block: idle, without a job, its arm in and not on its
     * way anywhere. The one place a crane of a warehouse with several may leave that warehouse from, because a dock is a
     * dead end no other crane's route crosses (issue #14, ADR-075 §2.5).
     */
    public boolean parkedOnOwnDock() {
        CraneState<ItemKey, RackPosition> state = craneState;
        CranePose pose = state.pose();
        if (state.phase() != CranePhase.IDLE || state.job().isPresent() || pose.arm() > CranePose.RETRACTED
                || state.isMoving())
            return false;
        double x = pose.x();
        if (Math.abs(x - Math.rint(x)) > 1.0e-3 || pose.branch() >= networkGeometry().branchCount())
            return false;
        WarehouseLayout frame = warehouse();
        Vec3 offset = frame.railOffset(pose.branch(), (int) Math.rint(x));
        return frame.dock().offset((int) Math.round(offset.x), 0, (int) Math.round(offset.z)).equals(worldPosition);
    }

    /**
     * Server: the controller tells this crane on every relink whether it still has room for it (issue #14, ADR-075
     * §2.5). A crane it has no room for any more takes no new job and drives home to its own dock as soon as it is idle.
     */
    public void setLeavingWarehouse(boolean leaving) {
        leavingWarehouse = leaving;
    }

    /** Server: whether the controller has no room for this crane any more and lets it go once it is parked. */
    public boolean isLeavingWarehouse() {
        return leavingWarehouse && isMember();
    }

    /**
     * Server: the warehouse of several cranes this crane belongs to is gone: its controller was broken or no longer
     * lists it, or the warehouse's own dock is gone (issue #14, ADR-075 §2.5). A crane parked on its own dock becomes a
     * crane by itself at once. Any other keeps its role and its claim and holds inside its own blocks (no controller,
     * no grant), because the cranes it shared the rails with are still on them, until it stands on its own dock or a
     * controller takes it in again. Without this, several cranes on shared rails would drive on ungated and into each
     * other.
     */
    public void warehouseDissolved() {
        if (level == null || level.isClientSide || membership == null)
            return;
        if (membership.craneCount() > 1 && !parkedOnOwnDock()) {
            dissolvePending = true;
            if (linkedController != null && linkedController.equals(membership.controller())) {
                linkedController = null;
                updateLinkFlag();
            }
            return;
        }
        finishDissolve();
    }

    /** The crane by itself again: a member leaves, the crane at the warehouse's own dock drops its role and link. */
    private void finishDissolve() {
        dissolvePending = false;
        if (membership == null)
            return;
        if (membership.isMember()) {
            leaveWarehouse();
            return;
        }
        setPrimaryRole(null);
        if (linkedController == null) {
            // Nobody names its aisles any more, exactly as when its controller unlinks it (ADR-033, ADR-034).
            setWarehouseNetwork(null);
            setHomePoint(null);
        }
    }

    /** What this crane holds of the rails beyond its blocks, as the ledger last told it (server). */
    public CraneTrafficClaim trafficClaim() {
        return trafficClaim;
    }

    /**
     * Server: what the goggles of this crane say about the other cranes, as its traffic tick worked it out
     * ({@link CraneExecution}).
     *
     * @return whether it changed, so clients should be told
     */
    boolean setTrafficInfo(@Nullable CraneTrafficInfo info) {
        if (Objects.equals(info, trafficInfo))
            return false;
        trafficInfo = info;
        return true;
    }

    /**
     * Server: what the goggles of this crane say about the other cranes of its warehouse right now; empty for a crane of
     * a warehouse with one crane. The controller's crane list reads it (issue #14).
     */
    public Optional<CraneTrafficInfo> trafficInfo() {
        return inTraffic() ? Optional.ofNullable(trafficInfo) : Optional.empty();
    }

    /**
     * About how many ticks the job this crane carries out still takes, if nothing holds it up (issue #14, ADR-075
     * §8.3); empty without a job, in a phase that is no part of a trip and while it cannot move. Both sides: the client
     * works it out from the pose and the job it was sent, so it never costs a packet ({@link RemainingTrip}).
     */
    public OptionalLong remainingTripTicks() {
        Optional<CraneJobSummary> job = goggleInfo.job();
        if (job.isEmpty())
            return OptionalLong.empty();
        CraneSpeeds speeds = currentSpeeds();
        if (speeds.isStopped())
            return OptionalLong.empty();
        return RemainingTrip.ticks(craneState.phase(), craneState.pose(), job.get().source(), job.get().target(),
                speeds, Math.max(0, WareworksConfig.transferTicks()),
                RemainingTrip.over(warehouse().routes(), speeds, WareworksConfig.turnPenaltyBlocks()));
    }

    /**
     * Client levels only: what a shown crane's goggles say about the other cranes (issue #14), for a Ponder scene or a
     * visual scenario that pictures a warehouse with several; {@code null} for none. Pairs with
     * {@link #showClientPose(CraneMembership, NetworkGeometry, CranePose, CranePose, CranePhase, List)}.
     */
    public boolean showClientTraffic(@Nullable CraneTrafficInfo info) {
        if (level == null || !level.isClientSide)
            return false;
        goggleInfo = new CraneGoggleInfo(goggleInfo.phase(), goggleInfo.pauseReason(), goggleInfo.job(),
                goggleInfo.held(), goggleInfo.aisleLetters(), goggleInfo.throughput(), Optional.ofNullable(info));
        return true;
    }

    /** Whether the traffic ledger holds this crane where it stands (both sides). */
    public boolean isTrafficHeld() {
        return trafficHold;
    }

    /**
     * Server: what the ledger told the crane this tick ({@link CraneExecution}): the claim it saves, whether it is held
     * and the route a detour drives.
     *
     * @return whether a client should be told (held, released, or another detour)
     */
    boolean setTrafficState(CraneTrafficClaim claim, boolean hold, @Nullable CraneRoute detour) {
        Objects.requireNonNull(claim, "claim");
        if (!claim.equals(trafficClaim)) {
            trafficClaim = claim;
            setChanged();
        }
        if (hold == trafficHold && Objects.equals(detour, trafficDetour))
            return false;
        trafficHold = hold;
        trafficDetour = detour;
        return true;
    }

    /**
     * Server: the controller of a warehouse with several cranes names the crane at its own dock crane 1 of
     * {@code craneCount}, or — with {@code null} — makes it the one crane of its warehouse again, which writes nothing
     * of this feature any more (issue #14). Nothing moves: the primary's frame is its own dock.
     */
    public void setPrimaryRole(@Nullable CraneMembership primary) {
        if (level == null || level.isClientSide)
            return;
        if (primary != null && primary.isMember())
            throw new IllegalArgumentException("the crane at the warehouse's own dock is crane 1: " + primary);
        if (primary != null)
            dissolvePending = false; // a controller takes it in again
        if (Objects.equals(membership, primary))
            return;
        membership = primary;
        if (primary == null) {
            setTrafficState(CraneTrafficClaim.NONE, false, null);
            trafficInfo = null;
        }
        cachedWarehouse = null;
        refreshGoggleInfo();
        setChanged();
        notifyUpdate();
    }

    /**
     * Server: this dock joins the warehouse of {@code joined} as one of its cranes, or takes in what changed about it
     * (issue #14, ADR-075 §2.5). The controller calls it on every relink with the warehouse's own network.
     * <p>
     * A crane that comes from another frame — its own dock's, which every dock without a controller has — has its pose,
     * its motion target and its job's locations <b>relabelled through the world blocks they stand for</b>, the same
     * rename a rebuild does at one dock, generalised to two anchors. Nothing moves and no item is touched. A pose whose
     * block is no aisle block of the warehouse is put on this crane's own dock; a job location that is no rack position
     * of it keeps its label and reaches the existing source- or target-missing ladder.
     */
    public void joinWarehouse(CraneMembership joined, NetworkGeometry network) {
        Objects.requireNonNull(joined, "joined");
        Objects.requireNonNull(network, "network");
        if (level == null || level.isClientSide || isRemoved())
            return;
        if (!joined.isMember())
            throw new IllegalArgumentException("a member is crane 2 or later: " + joined);
        CraneMembership before = membership;
        boolean sameFrame = before != null && before.isMember() && before.anchor().equals(joined.anchor())
                && before.anchorFacing() == joined.anchorFacing() && warehouseNetwork != null;
        WarehouseLayout from = warehouse();
        membership = joined;
        if (!sameFrame) {
            warehouseNetwork = network;
            cachedWarehouse = null;
            WarehouseLayout to = warehouse();
            double dockYaw = joined.siteBranch() < network.branchCount()
                    ? CranePose.yawOf(network.branch(joined.siteBranch()).heading()) : restingYaw();
            relabel(from, to, pose -> joined.dockPose(dockYaw));
            setTrafficState(CraneTrafficClaim.NONE, false, null);
            // An idle crane faces the aisle it now stands on at once, not on its next refresh: its first trip in the
            // warehouse is then the trip every other crane of it would make from the same block.
            alignRestingCrane();
        } else {
            setWarehouseNetwork(network);
        }
        if (!Objects.equals(before, joined)) {
            cachedWarehouse = null;
            setChanged();
            notifyUpdate();
        }
        linkController(joined.controller());
    }

    /**
     * Server: this member leaves its warehouse for good (issue #14, ADR-075 §2.5): its dock is no longer reached by the
     * warehouse's rails, its controller is gone, or the warehouse has no room for it any more. Its pose, target and job
     * are relabelled back into its own dock's frame through the world blocks they stand for; a machine that does not
     * stand on its own rails is put back at its dock by the existing lost-aisle recovery
     * ({@link WarehouseLayout#parkedAtDock}). The crane at a warehouse's own dock only drops its role.
     */
    public void leaveWarehouse() {
        if (level == null || level.isClientSide || membership == null)
            return;
        CraneMembership left = membership;
        if (!left.isMember()) {
            setPrimaryRole(null);
            return;
        }
        WarehouseLayout from = warehouse();
        membership = null;
        warehouseNetwork = null;
        homePoint = null;
        cachedWarehouse = null;
        WarehouseLayout to = warehouse();
        relabel(from, to, to::parkedAtDock);
        setTrafficState(CraneTrafficClaim.NONE, false, null);
        trafficInfo = null;
        refreshGoggleInfo();
        if (left.controller().equals(linkedController)) {
            linkedController = null;
            updateLinkFlag();
        }
        leavingWarehouse = false;
        dissolvePending = false;
        geometryRefreshRequested = true;
        setChanged();
        notifyUpdate();
    }

    /**
     * Renames the crane's pose, motion target and job locations from {@code from} to {@code to} through the world
     * blocks they stand for. A rack side keeps its <b>world</b> direction, so an arm that is out still points at the
     * rack it points at.
     *
     * @param lost where a pose goes whose block is no aisle block of {@code to}
     */
    private void relabel(WarehouseLayout from, WarehouseLayout to, UnaryOperator<CranePose> lost) {
        CraneState<ItemKey, RackPosition> state = craneState;
        CranePose pose = relabelPose(from, to, state.pose()).orElseGet(() -> lost.apply(state.pose()));
        CranePose target = relabelPose(from, to, state.target()).orElse(pose.withArm(CranePose.RETRACTED));
        CraneState<ItemKey, RackPosition> next = state.withPoses(pose, pose).withTarget(target);
        Optional<TransportJob<ItemKey, RackPosition>> job = state.job();
        if (job.isPresent()) {
            RackPosition source = to.rackFrom(from, job.get().source()).orElse(job.get().source());
            RackPosition destination = to.rackFrom(from, job.get().target()).orElse(job.get().target());
            next = next.withJob(job.get().relabelled(source, destination));
        }
        craneState = next;
        execution.onGeometryChanged();
    }

    /** One pose renamed between two frames, its arm's side keeping its world direction. */
    private static Optional<CranePose> relabelPose(WarehouseLayout from, WarehouseLayout to, CranePose pose) {
        return to.relabelledFrom(from, pose).map(renamed -> {
            Direction away = from.sideDirection(pose.branch(), pose.side());
            return to.sideDirection(renamed.branch(), renamed.side()) == away ? renamed
                    : renamed.withSide(renamed.side().opposite());
        });
    }

    /**
     * Where a crane of this dock goes back to when its aisle left the warehouse: position 0 of the aisle at the dock,
     * or — for a member — its own dock block in the warehouse's frame (issue #14).
     */
    CranePose parkedAtOwnDock(CranePose pose) {
        if (anchoredElsewhere()) {
            NetworkGeometry network = networkGeometry();
            CraneMembership frame = membership;
            if (frame.siteBranch() < network.branchCount())
                return frame.dockPose(CranePose.yawOf(network.branch(frame.siteBranch()).heading()))
                        .withXY(frame.siteX(), Math.min(Math.max(pose.y(), 0.0), network.height() - 1.0))
                        .withSide(pose.side());
        }
        return warehouse().parkedAtDock(pose);
    }

    /**
     * Client: how far the machine has driven since it loaded, <b>signed</b> — forward down the aisle it faces counts
     * up, rolling back counts down — so the wheels turn the way the machine really moves. For the wheel animation
     * only.
     */
    public double wheelOdometer() {
        return wheelOdometer;
    }

    /**
     * Server: the warehouse was rebuilt under the working machine, and everything the controller knows has been moved
     * from the old labels to the new ones through the world blocks they stood for. The crane's own labels are moved
     * with them (M21 review fix, ADR-033).
     * <p>
     * Without this the machine keeps the branch index it had while that index came to mean another line of blocks:
     * it would be drawn on the wrong aisle from one tick to the next, plan its route from a point it is not at, and
     * deliver a job's items into whatever chest inherited its target's number. Nothing is moved here — a remap only
     * renames — and a label whose place really disappeared is deliberately left alone by the caller, so it reaches the
     * existing source- or target-missing ladder.
     *
     * @param poses the pose and the motion target under the new numbering
     * @param racks the new label of a rack position, or the same one when it did not move
     * @return whether anything changed
     */
    public boolean remapOnto(UnaryOperator<CranePose> poses, UnaryOperator<RackPosition> racks) {
        Objects.requireNonNull(poses, "poses");
        Objects.requireNonNull(racks, "racks");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        CraneState<ItemKey, RackPosition> state = craneState;
        CranePose pose = poses.apply(state.pose());
        CranePose target = poses.apply(state.target());
        // The previous pose goes with it: it names the tick before the rebuild on a numbering that no longer exists,
        // and interpolating the two would drag the machine across the warehouse for one frame.
        CraneState<ItemKey, RackPosition> next = state.withPoses(pose, pose).withTarget(target);
        Optional<TransportJob<ItemKey, RackPosition>> job = state.job();
        if (job.isPresent())
            next = next.withJob(job.get().relabelled(racks.apply(job.get().source()), racks.apply(job.get().target())));
        if (next.equals(state))
            return false;
        craneState = next;
        cachedWarehouse = null;
        execution.onGeometryChanged();
        setChanged();
        notifyUpdate();
        return true;
    }

    /** Server: re-count the rails on the next tick instead of waiting for the periodic refresh. */
    public void requestGeometryRefresh() {
        geometryRefreshRequested = true;
    }

    /**
     * Server: re-counts the rails now and takes the value box range from the current config. Syncs, saves and calls
     * {@link #onGeometryChanged} if the geometry changed. Does nothing on the client or while removed.
     * <p>
     * The stored mast height is deliberately <b>not</b> rewritten here: {@link #mastHeight()} clamps on read, so a
     * lowered {@code aisle.maxMastHeight} shortens every mast without destroying the number the player scrolled, and
     * raising the limit restores it ({@code docs/warehouse-system.md} §8.1).
     *
     * @return whether the geometry changed
     */
    public boolean refreshGeometry() {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        geometryRefreshRequested = false;
        nextGeometryRefreshTick = level.getGameTime() + WareworksConfig.geometryRefreshTicks();
        alignRestingCrane();
        AisleGeometry before = publishedGeometry != null ? publishedGeometry : geometry();

        // Only the range of the value box follows the config; the stored value stays as the player set it.
        mastHeight.between(MIN_MAST_HEIGHT, maxMastHeight());

        if (isMember()) {
            // A member's rails are the warehouse's, as its controller names them, so it walks none of its own; the
            // warehouse's own dock finds this one (issue #14, ADR-075). Only its mast is its own.
            AisleGeometry kept = geometry();
            publishedGeometry = kept;
            if (kept.equals(before))
                return false;
            onGeometryChanged(before, kept);
            notifyUpdate();
            return true;
        }
        int maxLength = Math.min(WareworksConfig.maxAisleLength(), AisleGeometry.MAX_LENGTH);
        // Only the dock of a warehouse lets other docks join, so a dock nobody serves discovers exactly what it did up
        // to 0.17.1 (issue #14). One block read.
        scannedAsPrimary = RailNetworkScan.hasOwnController(level, worldPosition, facing());
        RailNetwork network = RailNetworkScan.scan(level, worldPosition, facing(), new RailGraph.Limits(
                WareworksConfig.maxNetworkRails(), WareworksConfig.maxBranches(), WareworksConfig.maxJunctions(),
                maxLength, mastHeight(),
                scannedAsPrimary ? WareworksConfig.maxCranesPerNetwork() : RailGraph.Limits.MIN_CRANES),
                scannedAsPrimary ? listedMemberDocks() : Set.of(), scannedAsPrimary ? strandedMembers() : 0);
        discoveredNetwork = network;
        reportNetworkStop(network);
        aisleLength = network.resolveFirstBranchLength(aisleLength, maxLength);

        AisleGeometry after = geometry();
        publishedGeometry = after;
        if (after.equals(before))
            return false;
        onGeometryChanged(before, after);
        notifyUpdate();
        return true;
    }

    /**
     * A crane job in the form the dock saves it, for a controller that keeps the last job of a crane whose chunk is away
     * (issue #14, ADR-075 §13); empty for a job whose item cannot be written.
     */
    public static Optional<CompoundTag> saveJob(TransportJob<ItemKey, RackPosition> job,
            HolderLookup.Provider registries) {
        return CranePersistence.writeJob(job, registries);
    }

    /** Reads what {@link #saveJob} wrote; empty for anything that does not describe a job. Untrusted data. */
    public static Optional<TransportJob<ItemKey, RackPosition>> loadJob(CompoundTag tag,
            HolderLookup.Provider registries) {
        return CranePersistence.readJob(tag, registries);
    }

    /**
     * The member docks the controller behind this dock already lists as its cranes, stranded ones included, which keep
     * their places in the next scan whatever new dock it meets first (issue #14, ADR-075 §2.5). Empty without a linked
     * controller; one list read.
     */
    private Set<BlockPos> listedMemberDocks() {
        Optional<WarehouseControllerBlockEntity> controller = linkedControllerEntity();
        if (controller.isEmpty())
            return Set.of();
        Set<BlockPos> docks = new HashSet<>();
        for (CraneMember member : controller.get().craneMembers()) {
            if (member.number() > 1)
                docks.add(member.dock());
        }
        return docks;
    }

    /**
     * How many cranes the controller behind this dock lists as stranded: their places of the cap are taken although
     * the scan cannot reach their docks (issue #14, ADR-075 §2.5).
     */
    private int strandedMembers() {
        Optional<WarehouseControllerBlockEntity> controller = linkedControllerEntity();
        if (controller.isEmpty())
            return 0;
        int stranded = 0;
        for (CraneMember member : controller.get().craneMembers()) {
            if (member.stranded())
                stranded++;
        }
        return stranded;
    }

    /**
     * Server: a crane that is standing still and has nothing to do faces the aisle it stands on.
     * <p>
     * {@link #HOME_POSE} faces north, because north is what every pose of a warehouse meant before M21 — so without
     * this a freshly placed dock on an east-facing aisle would swing a quarter turn on its very first job, and every
     * trip of that warehouse would be measured against a travel time that does not include it (ADR-033). It is a snap
     * rather than a turn precisely because nothing is happening: the machine is idle, holds no job, and the aisle it
     * faces is the one it has always stood on.
     * <p>
     * It also puts a crane straight again after a wrench turned its dock, which is the same kind of change as the
     * whole aisle moving to another line of blocks.
     */
    private void alignRestingCrane() {
        CraneState<ItemKey, RackPosition> state = craneState;
        if (state.phase() != CranePhase.IDLE || state.job().isPresent())
            return;
        // The aisle the crane is parked on, not the dock's own: a machine idling round a corner faces the way that
        // aisle runs, and one on an aisle the warehouse no longer knows is left exactly as it stands.
        double yaw = craneNetwork().restingYaw(state.pose().branch(), state.pose().yaw());
        if (state.pose().yaw() == yaw && state.target().yaw() == yaw)
            return;
        CranePose straight = state.pose().withYaw(yaw);
        craneState = state.withPoses(straight, straight).withTarget(state.target().withYaw(yaw));
        execution.requestSync();
    }

    /**
     * Says in the log where the warehouse stops and why, whenever that changes to something a player did not ask for:
     * another dock on the same rails, a configured maximum, or a chunk that is not loaded. The rails a player closed on
     * purpose and a warehouse that simply ends say nothing.
     * <p>
     * Since M22 (issue #2) a rail that <b>splits</b> or closes into a ring is no longer one of those reasons: a T, a
     * cross and a ring are ordinary warehouses, so the two stops that only ever said "this version cannot follow that
     * shape" are gone and every reason that is left names something a player can walk to and change
     * ({@link NetworkStop}).
     * <p>
     * The log is the dock's own half of the report. The player-facing half belongs to the controller behind it, which
     * shows the same stop as a goggle line and marks it on the warehouse summary display
     * ({@code NetworkGoggleInfo}); {@code /wareworks network} is still not built. A fault is therefore never
     * <b>silent</b>, which is the one thing discovery must not be.
     */
    private void reportNetworkStop(RailNetwork network) {
        NetworkStop stop = network.stop();
        if (!stop.isFault()) {
            reportedStop = NetworkStop.END; // it healed, so the same fault is worth reporting if it comes back
            return;
        }
        if (stop == reportedStop)
            return; // already said, and a standing fault belongs in the goggles rather than in the log
        if (!networkStops.tryLog(level.getGameTime()))
            return; // a fault that keeps changing (a piston flipping a rail) costs one line per interval
        reportedStop = stop;
        BlockPos stopped = worldPosition.offset(network.stopDx(), 0, network.stopDz());
        Wareworks.LOGGER.info("The warehouse of the stacker crane dock at {} stops at {}: {} (rails: {}, "
                + "aisles: {})", worldPosition, stopped, stop.name(), network.rails(),
                network.branchCount());
    }

    /**
     * Server hook, called after the geometry changed (rails added or removed, mast height or facing changed): the
     * controller behind the dock re-links, which marks its membership dirty ({@code docs/warehouse-system.md} §4), and
     * the crane re-checks its job locations at once (a shrunken aisle may exclude them). Subclasses must call super.
     */
    protected void onGeometryChanged(AisleGeometry previous, AisleGeometry current) {
        notifyControllerBehind(facing());
        notifyFrameController();
        execution.onGeometryChanged();
    }

    /**
     * Server: asks the controller of this crane's warehouse to re-link on its next tick, when that is not the one
     * behind the dock: a member's (issue #14). Never loads a chunk.
     */
    private void notifyFrameController() {
        CraneMembership frame = membership;
        if (!(level instanceof ServerLevel) || frame == null || !frame.isMember())
            return;
        if (level.isLoaded(frame.controller())
                && level.getBlockEntity(frame.controller()) instanceof WarehouseControllerBlockEntity controller)
            controller.requestRelink();
    }

    /**
     * Server: asks a warehouse controller behind the dock for {@code aisleFacing} ({@code dock - facing}) to re-link on
     * its next tick. Never loads a chunk.
     */
    private void notifyControllerBehind(Direction aisleFacing) {
        if (!(level instanceof ServerLevel))
            return;
        BlockPos behind = worldPosition.relative(aisleFacing.getOpposite());
        if (level.isLoaded(behind) && level.getBlockEntity(behind) instanceof WarehouseControllerBlockEntity controller)
            controller.requestRelink();
    }

    /**
     * Server: lets a warehouse controller directly behind the dock that faces it link this dock now
     * ({@link WarehouseControllerBlockEntity#relinkNow}), instead of on that controller's next tick. One block entity
     * lookup; never loads a chunk.
     */
    void linkControllerBehind() {
        if (!(level instanceof ServerLevel) || isRemoved())
            return;
        CraneMembership frame = membership;
        if (frame != null && frame.isMember()) {
            // A member's controller does not stand behind it: it is the one its frame names (issue #14).
            if (level.isLoaded(frame.controller())
                    && level.getBlockEntity(frame.controller()) instanceof WarehouseControllerBlockEntity controller
                    && !controller.isRemoved())
                controller.relinkNow();
            return;
        }
        Direction aisleFacing = facing();
        BlockPos behind = worldPosition.relative(aisleFacing.getOpposite());
        if (level.isLoaded(behind) && level.getBlockEntity(behind) instanceof WarehouseControllerBlockEntity controller
                && !controller.isRemoved() && controller.facing() == aisleFacing)
            controller.relinkNow();
    }

    private void onMastHeightChanged(int newHeight) {
        if (level != null && !level.isClientSide)
            refreshGeometry();
    }

    private static int maxMastHeight() {
        return Mth.clamp(WareworksConfig.maxMastHeight(), MIN_MAST_HEIGHT, AisleGeometry.MAX_HEIGHT);
    }

    // --- crane (M3) ----------------------------------------------------------------------------------------------

    /** The crane state: authoritative on the server, the synced pose, target and phase (without job) on clients. */
    public CraneState<ItemKey, RackPosition> craneState() {
        return craneState;
    }

    void setCraneState(CraneState<ItemKey, RackPosition> state) {
        craneState = Objects.requireNonNull(state, "state");
    }

    /** Server: the job the crane is executing. */
    public Optional<TransportJob<ItemKey, RackPosition>> currentJob() {
        return craneState.job();
    }

    /** Server: the items in the handling head. */
    public HeldItems heldItems() {
        return head.held();
    }

    HandlingHead head() {
        return head;
    }

    /** Server: why the crane is paused, as of its last tick. */
    public CranePauseReason pauseReason() {
        return execution.pauseReason();
    }

    /** Goggle data as of the last publication (server) or sync (client). */
    public CraneGoggleInfo goggleInfo() {
        return goggleInfo;
    }

    /**
     * Server: a <b>fresh</b> snapshot of what the machine got done in the rolling minute behind it (M25, issue #16,
     * {@code docs/stacker-crane.md} §9). {@link CraneThroughput#EMPTY} on a client, in a Ponder level and for a
     * virtual block entity, none of which runs {@link CraneExecution}.
     * <p>
     * Fresh and not cached on purpose: a caller that wants these numbers wants them now, and the window decays even
     * while the machine stands still, so a cached copy would be as stale as the last phase change.
     */
    public CraneThroughput throughput() {
        if (level == null || level.isClientSide || isVirtual())
            return CraneThroughput.EMPTY;
        return execution.throughput();
    }

    /**
     * A player looks at the dock through goggles (server): take a fresh throughput snapshot and sync a change
     * (throttled to one packet a second).
     * <p>
     * <b>This is the one thing the existing publish path cannot do.</b> A working crane publishes several times a
     * second anyway — on every phase, job, target, head or pause change and every
     * {@value CraneExecution#MOVING_SYNC_INTERVAL_TICKS} ticks while moving — but a <b>parked</b> crane publishes
     * nothing at all, so without this a player would read "Busy: 85 %" off a machine that has stood still for five
     * minutes. The one genuinely new traffic case is therefore a just-parked, watched dock: it syncs about once a
     * second for the minute its window takes to decay to nothing, and then goes quiet of its own accord.
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        CraneThroughput measured = execution.throughput();
        CraneThroughput next = measured.isEmpty() ? null : measured;
        if (!Objects.equals(next, observedThroughput)) {
            observedThroughput = next;
            throughputSync.markPending();
        }
        // Why a dock facing into a warehouse's rails is no crane of it (issue #14): asked here, while somebody is
        // looking, and never on a tick. It rides the same throttled packet as the throughput.
        DockJoinStatus join = joinStatusNow();
        if (!Objects.equals(join, observedJoin)) {
            observedJoin = join;
            throughputSync.markPending();
        }
        if (throughputSync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    /**
     * Why this dock is no crane of the warehouse whose rails it faces, as of the last time a player looked at it through
     * goggles (server) or as synced (client); empty when there is nothing to say (issue #14).
     */
    public Optional<DockJoinStatus> joinStatus() {
        return Optional.ofNullable(observedJoin);
    }

    /**
     * Server: why this dock is no crane of the warehouse whose rails it faces, or {@code null} when it is one, faces no
     * other warehouse's rails, or there is nothing to say (issue #14, ADR-075 §15.1). A dock with a controller of its
     * own gets the foreign-dock hint: it is a warehouse by itself and a wall to the other one. One containment test per
     * registered controller ({@link WarehouseRegistry#findByAisleBlock}) and one block entity lookup, only while a player
     * looks at the dock.
     */
    @Nullable
    private DockJoinStatus joinStatusNow() {
        if (isMember())
            return null;
        Optional<WarehouseControllerBlockEntity> faced = WarehouseRegistry.findByAisleBlock(level,
                worldPosition.relative(facing()), linkedController);
        if (faced.isEmpty() || faced.get().listsCrane(worldPosition))
            return null;
        WarehouseControllerBlockEntity other = faced.get();
        int maxCranes = WareworksConfig.maxCranesPerNetwork();
        DockJoinStatus.Reason reason;
        if (linkedController != null || RailNetworkScan.hasOwnController(level, worldPosition, facing()))
            reason = DockJoinStatus.Reason.OWN_CONTROLLER;
        else if (maxCranes <= RailGraph.Limits.MIN_CRANES)
            reason = DockJoinStatus.Reason.ONE_CRANE;
        else if (other.craneCount() >= maxCranes)
            reason = DockJoinStatus.Reason.MAX_CRANES;
        else
            reason = DockJoinStatus.Reason.PENDING;
        return new DockJoinStatus(reason, other.aisleLetter(), maxCranes);
    }

    /** The crane pose for rendering at {@code partialTicks} between the previous and the current tick (M4). */
    public CranePose renderPose(float partialTicks) {
        return craneState.interpolatedPose(partialTicks);
    }

    /**
     * Where the machine stands at {@code partialTicks}, as an offset from this dock block, measured along the rails it
     * drives on rather than along {@link CranePose#x()} (M21, ADR-033).
     * <p>
     * The two differ for exactly one tick per corner: a hand-over renames the machine onto the next aisle without
     * moving it, so the {@code x} before and the {@code x} after belong to different lines of blocks and cannot be
     * interpolated against each other ({@code WarehouseLayout#railOffset}). Taken through the rails, the same tick is
     * the short drive up to the corner block it always was.
     */
    public Vec3 renderOffset(float partialTicks) {
        CraneState<ItemKey, RackPosition> state = craneState;
        WarehouseLayout warehouse = warehouse();
        Vec3 offset = warehouse.railOffset(state.previousPose(), state.pose(), partialTicks);
        // A member's warehouse is measured from the warehouse's own dock, and this block draws it (issue #14).
        BlockPos anchor = warehouse.dock();
        if (anchor.equals(worldPosition))
            return offset;
        return offset.add(anchor.getX() - worldPosition.getX(), anchor.getY() - worldPosition.getY(),
                anchor.getZ() - worldPosition.getZ());
    }

    /**
     * Client levels only: shows the crane standing still at {@code pose} in {@code phase}, holding {@code held}, for
     * deterministic render poses (the visual smoke test). The same as {@link #showClientPose(CranePose, CranePose,
     * CranePhase, List)} with the pose as its own target.
     *
     * @return whether the pose is shown (false on a server level or without level)
     */
    public boolean showClientPose(CranePose pose, CranePhase phase, List<KeyCount<Item>> held) {
        return showClientPose(pose, pose, phase, held);
    }

    /**
     * Client levels only (the game client and Ponder; the Ponder pose API of ADR-013): shows the crane at {@code pose} in
     * {@code phase}, holding {@code held}, and lets the client motion simulation move it towards {@code target} every
     * client tick with the same {@link CraneMotion} as a synced crane (the arm retracts before X/Y motion and extends only
     * at the target position), at the speeds of the dock's kinetic speed ({@link #currentSpeeds()}; without rotation it
     * stays at {@code pose}). Nothing else happens: no phase change, no job, no item transfer. A Ponder scene animates the
     * crane by calling this again with the next target. A later sync packet from the server takes over as usual; on a
     * server level this does nothing.
     *
     * @return whether the pose is shown (false on a server level or without level)
     */
    public boolean showClientPose(CranePose pose, CranePose target, CranePhase phase, List<KeyCount<Item>> held) {
        // Poses of this dock's own aisle: a caller that names no network means the one straight aisle the dock stands
        // on, so both poses face the way that aisle runs rather than north (M21, ADR-033). Every pose written before
        // M21 is such a pose, which is why Ponder scenes and visual scenarios need no edit.
        return showClientPose(null, pose.withYaw(restingYaw()), target.withYaw(restingYaw()), phase, held);
    }

    /**
     * The pose a crane of this dock parks in: position 0 of the aisle at the dock, at dock level, with the arm
     * retracted and the machine facing the way that aisle runs.
     */
    public CranePose homePose() {
        if (anchoredElsewhere()) {
            NetworkGeometry network = networkGeometry();
            if (membership.siteBranch() < network.branchCount())
                return membership.dockPose(CranePose.yawOf(network.branch(membership.siteBranch()).heading()));
        }
        return HOME_POSE.withYaw(restingYaw());
    }

    /**
     * Client levels only: the same as {@link #showClientPose(CranePose, CranePose, CranePhase, List)}, with the rail
     * network the shown crane drives on (M21, ADR-033).
     * <p>
     * A Ponder level never runs the server-only {@code refreshGeometry}, and a visual scenario freezes the server, so
     * neither would otherwise know that the warehouse bends — and a crane that does not know its own network cannot
     * turn a corner, however many rails are drawn under it. A {@code null} network means the one straight aisle every
     * build that does not bend is.
     */
    public boolean showClientPose(@Nullable NetworkGeometry network, CranePose pose, CranePose target, CranePhase phase,
            List<KeyCount<Item>> held) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(phase, "phase");
        if (level == null || !level.isClientSide)
            return false;
        warehouseNetwork = network == null || (network.branchCount() < 2 && !isMember()) ? null : network;
        cachedWarehouse = null;
        craneState = CraneState.displayed(pose, target, phase);
        // No throughput: a shown pose is a picture, and a Ponder scene or a visual scenario measures nothing.
        goggleInfo = new CraneGoggleInfo(phase, CranePauseReason.NONE, Optional.empty(), held,
                goggleInfo.aisleLetters(), Optional.empty());
        clientSynced = true;
        return true;
    }

    /**
     * Client levels only: the same as {@link #showClientPose(NetworkGeometry, CranePose, CranePose, CranePhase, List)}
     * for a crane of a warehouse with several (issue #14, ADR-075): {@code frame} says which dock the poses are
     * measured from, so a member dock draws its machine on the warehouse's rails. A Ponder scene and the visual
     * scenarios have no controller to hand a frame over, so they name it here. {@code null} is a crane of a warehouse
     * with one crane.
     */
    public boolean showClientPose(@Nullable CraneMembership frame, @Nullable NetworkGeometry network, CranePose pose,
            CranePose target, CranePhase phase, List<KeyCount<Item>> held) {
        if (level == null || !level.isClientSide)
            return false;
        if (!Objects.equals(membership, frame)) {
            membership = frame;
            cachedWarehouse = null;
            invalidateRenderBoundingBox();
        }
        trafficHold = false;
        trafficDetour = null;
        return showClientPose(network, pose, target, phase, held);
    }

    /**
     * The axis speeds at the current kinetic speed ({@code docs/stacker-crane.md} §5). {@link CraneSpeeds#STOPPED}
     * without rotation, while overstressed, and when a configured speed factor is 0: a factor of 0 would stall that
     * axis forever while the others move (the config range allows it), so the crane rather pauses. The pause reason
     * then names the configuration ({@link CranePauseReason#SPEED_FACTOR_ZERO}), not the machine.
     */
    public CraneSpeeds currentSpeeds() {
        return speedsFor(kinematicParams(), getSpeed());
    }

    /**
     * The axis speeds for {@code rpm} with already read {@code params}, so a caller that needs both the speeds and the
     * factors reads the config only once. A configured factor of 0 stops the whole crane, not just its own axis.
     */
    public static CraneSpeeds speedsFor(CraneKinematics.Params params, double rpm) {
        CraneSpeeds speeds = CraneKinematics.speeds(params, rpm);
        if (speeds.vx() == 0.0 || speeds.vy() == 0.0 || speeds.va() == 0.0)
            return CraneSpeeds.STOPPED;
        return speeds;
    }

    /** The speed factors from the server config (safe getters, so this also works on clients and in Ponder). */
    public static CraneKinematics.Params kinematicParams() {
        return new CraneKinematics.Params(Math.max(0.0, WareworksConfig.travelBlocksPerTickPerRpm()),
                Math.max(0.0, WareworksConfig.liftBlocksPerTickPerRpm()),
                Math.max(0.0, WareworksConfig.armExtendPerTickPerRpm()), Math.max(0.0, WareworksConfig.maxBlocksPerTick()));
    }

    /**
     * Server: whether {@link #assignJob} would accept a job now: the dock's chunk ticks, loaded state resumed, idle (or
     * just complete), nothing in the head, not paused and powered ({@code docs/warehouse-system.md} §7.1). A dock in a
     * chunk that is loaded but does not tick (border chunk) would never run the job, and its reservations would stay.
     */
    public boolean canAcceptJob() {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return level.shouldTickBlocksAt(worldPosition) && execution.canAcceptJob() && head.isEmpty()
                && !currentSpeeds().isStopped();
    }

    /**
     * Server: starts {@code job} (not picked yet) if {@link #canAcceptJob()}. The caller has reserved it already.
     *
     * @return whether the crane took the job
     */
    public boolean assignJob(TransportJob<ItemKey, RackPosition> job) {
        Objects.requireNonNull(job, "job");
        if (!canAcceptJob() || job.picked())
            return false;
        return execution.assign(level, job);
    }

    /**
     * Server: the controller cancels job {@code jobId} (its request or output is gone). Before the pick the job is
     * aborted after retracting; after the pick the held items are rerouted ({@code docs/stacker-crane.md} §4.1).
     *
     * @return whether the crane executes that job
     */
    public boolean cancelJob(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return execution.cancel(level, jobId);
    }

    /** Server: the loaded controller that linked this dock and is still linked to it. */
    public Optional<WarehouseControllerBlockEntity> linkedControllerEntity() {
        BlockPos pos = linkedController;
        if (pos == null || level == null || level.isClientSide || !level.isLoaded(pos))
            return Optional.empty();
        return level.getBlockEntity(pos) instanceof WarehouseControllerBlockEntity controller && !controller.isRemoved()
                && controller.isLinkedTo(worldPosition) ? Optional.of(controller) : Optional.empty();
    }

    /** Server: the crane state changed in a way clients or goggles should see; publish it now. */
    void publishState() {
        refreshGoggleInfo();
        setChanged();
        sendData();
    }

    /**
     * Rebuilds the published goggle data from the live state. The throughput is left out on purpose and merged in at
     * write time instead ({@link #goggleInfo}), so this stays what it always was: a cheap rebuild of what changed.
     */
    private void refreshGoggleInfo() {
        // The traffic info only while the warehouse has several cranes, so one crane publishes what it always did.
        goggleInfo = new CraneGoggleInfo(craneState.phase(), execution.pauseReason(),
                craneState.job().map(CraneJobSummary::of), CraneGoggleInfo.heldByType(head.held()),
                linkedControllerEntity().map(StackerCraneBlockEntity::aisleLettersOf).orElse(""), Optional.empty(),
                trafficInfo());
    }

    /**
     * The aisle letters of the warehouse a controller runs, one per branch (M21, ADR-033), so that a job round a corner
     * names the rack it is going to by the letter of <b>its own</b> aisle. A controller that has no warehouse yet
     * answers with its own letter alone, which is the aisle at the dock and the only one such a controller can mean.
     */
    private static String aisleLettersOf(WarehouseControllerBlockEntity controller) {
        return controller.warehouse().map(WarehouseLayout::branchLetters)
                .orElseGet(() -> String.valueOf(controller.aisleLetter()));
    }

    private void onHeadChanged() {
        execution.requestSync();
    }

    /**
     * Server hook: the crane phase changed from {@code from} to {@code to} (the crane state is already the new one). Plays
     * the arm sounds ({@link CraneSounds}); travel and transfer sounds come from {@link CraneExecution}. Called while
     * effects are executed; must not change the crane state. Subclasses must call super.
     */
    protected void onPhaseChanged(CranePhase from, CranePhase to) {
        if (level != null && !level.isClientSide)
            execution.sounds().onPhaseChanged(level, this, from, to);
    }

    // --- ticking and lifecycle -----------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;
        if (level.isClientSide) {
            tickClientMotion();
            return;
        }
        if (geometryRefreshRequested || level.getGameTime() >= nextGeometryRefreshTick) {
            refreshGeometry();
            validateControllerLink();
        }
        if (!isVirtual() && !isRemoved()) {
            // Before the execution, so a cleared stale overstress resumes the crane in the same tick.
            staleOverstress.serverTick(this);
            execution.tick(level);
        }
    }

    /**
     * Client: moves the synced pose towards the synced target with the same deterministic motion as the server, so the
     * crane animates smoothly between packets. Phase transitions and item transfers only happen on the server.
     */
    private void tickClientMotion() {
        CraneState<ItemKey, RackPosition> state = craneState;
        CraneSpeeds speeds = currentSpeeds();
        // A crane the traffic ledger holds stands still on the server, so the client must not animate it on and snap
        // it back with the next packet (issue #14).
        if (state.paused() || speeds.isStopped() || trafficHold) {
            craneState = state.withPreviousPose(state.pose());
            return;
        }
        CraneNetwork network = craneNetwork();
        CranePose pose = state.pose();
        CraneRoute detour = trafficDetour;
        if (detour != null && pose.arm() <= CranePose.RETRACTED && detour.legIndexAt(pose.branch(), pose.x()) >= 0) {
            // A detour drives a route the ledger granted rather than the cheapest one, exactly as the server does.
            CranePose moved = CraneMotion.step(pose, detourTarget(pose, detour), speeds, detour,
                    network.turnPenaltyBlocks());
            craneState = state.withPoses(pose, moved);
            wheelOdometer += CraneMotion.blocksDriven(pose, moved, detour);
            return;
        }
        CranePose target = state.target();
        CraneRoute route = pose.branch() == target.branch() ? null
                : network.route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
        craneState = CraneMotion.step(state, speeds, route, network.turnPenaltyBlocks());
        wheelOdometer += CraneMotion.blocksDriven(pose, craneState.pose(), route);
    }

    /**
     * Where a traffic detour along {@code route} ends for a crane at {@code pose}: the route's last block, arm in, at
     * the level the crane is at, facing the way that last leg runs (issue #14). The server's detour tick and a client's
     * animation of it use this one rule.
     */
    static CranePose detourTarget(CranePose pose, CraneRoute route) {
        CraneRoute.Leg last = route.lastLeg();
        return new CranePose(last.branch(), last.toX(), pose.y(), CranePose.RETRACTED, pose.side(),
                CranePose.yawOf(last.heading()));
    }

    /**
     * Counts the rails as soon as the dock is loaded or placed, also in chunks that do not tick, and lets a controller
     * that loaded earlier link to it.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            refreshGeometry();
            notifyControllerBehind(facing());
            notifyFrameController();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() == before)
            return;
        // Wrench rotation or structure placement: the aisle now runs in another direction.
        geometryRefreshRequested = true;
        if (level != null && level.isClientSide)
            invalidateRenderBoundingBox();
        // The old controller loses this dock, a controller behind the new direction may gain it.
        notifyControllerBehind(before);
        notifyControllerBehind(facing());
    }

    /** Real removal: the controller behind the dock re-links and finds no dock, and a member's controller loses it. */
    @Override
    public void remove() {
        super.remove();
        notifyControllerBehind(facing());
        notifyFrameController();
    }

    /**
     * Whether the aisle direction may change (wrench): only while the crane has no job and holds nothing, because the
     * aisle would otherwise turn under a running job. Clients decide from the synced phase and held items.
     */
    public boolean canChangeAisleDirection() {
        return craneState.phase() == CranePhase.IDLE && craneState.job().isEmpty() && head.isEmpty()
                && goggleInfo.held().isEmpty();
    }

    /**
     * Clears the handling head without dropping anything ({@code /clone ... move}, {@code /setblock}, structure placement):
     * the command copied or deletes the block entity data, so dropping
     * would duplicate. The job ends with it, and the controller releases its reservations.
     */
    @Override
    public void clearContent() {
        endJobWithoutItems();
        head.clear();
        setChanged();
    }

    /**
     * Real break or replacement (server, via {@code KineticBlock.onRemove} → {@code IBE.onRemove}): the handling head
     * contents drop at the dock ({@code Containers.dropItemStack}, which ignores {@code doTileDrops}), and the controller
     * releases the job's reservations; a request keeps its remaining amount ({@code docs/warehouse-system.md} §8).
     */
    @Override
    public void destroy() {
        super.destroy();
        if (level == null || level.isClientSide)
            return;
        head.spill(level, worldPosition, key -> true);
        endJobWithoutItems();
    }

    private void endJobWithoutItems() {
        Optional<TransportJob<ItemKey, RackPosition>> job = craneState.job();
        if (level != null && !level.isClientSide)
            job.ifPresent(lost -> linkedControllerEntity().ifPresent(controller -> controller.onCraneJobLost(this, lost)));
        craneState = CraneState.idle(craneState.pose().withArm(CranePose.RETRACTED));
    }

    // --- controller link ----------------------------------------------------------------------------------------

    /** Whether a warehouse controller serves this crane (goggles; synced to clients). */
    public boolean isControllerLinked() {
        return controllerLinked;
    }

    /** Server: the position of the controller that linked this dock. */
    public Optional<BlockPos> linkedController() {
        return Optional.ofNullable(linkedController);
    }

    /**
     * Server: the controller at {@code controller} links this dock and becomes its owner (replacing a previous one).
     * Syncs a change of the link flag. Not saved: the controller links again after loading.
     */
    public void linkController(BlockPos controller) {
        linkedController = controller.immutable();
        dissolvePending = false;
        // A dock that was scanned while nobody served it saw every other dock as a wall. The controller behind it is
        // about to read that scan, so it is taken again now, as the dock of a warehouse (issue #14).
        if (!scannedAsPrimary && !isMember() && level != null && !level.isClientSide
                && WareworksConfig.maxCranesPerNetwork() > RailGraph.Limits.MIN_CRANES
                && controller.equals(worldPosition.relative(facing().getOpposite())))
            refreshGeometry();
        updateLinkFlag();
    }

    /** Server: the controller at {@code controller} unlinks this dock; ignored unless it is the current owner. */
    public void unlinkController(BlockPos controller) {
        if (!controller.equals(linkedController))
            return;
        if (inTraffic() && !parkedOnOwnDock()) {
            // Its warehouse of several cranes is gone; it holds until it is parked on its own dock (issue #14).
            warehouseDissolved();
            return;
        }
        if (isMember()) {
            leaveWarehouse();
            return;
        }
        // The one crane of its warehouse again, which writes nothing of several cranes any more (issue #14).
        setPrimaryRole(null);
        linkedController = null;
        // Nobody names its aisles any more, so it is the one straight aisle its own rails and facing describe. A
        // kept network would otherwise outlive the warehouse that defined it and let the crane drive on rails the
        // world no longer has (ADR-033).
        setWarehouseNetwork(null);
        // ... and with it the home point that controller named: nothing knows any more whether that block is still
        // there or still the first one, and a crane with no warehouse waits where it stands (M21, ADR-034).
        setHomePoint(null);
        updateLinkFlag();
    }

    /**
     * Server: clears the link unless its owner is still a loaded controller that is linked to this dock (it may have
     * unloaded with its chunk, which does not notify). One block entity lookup; called at the geometry refresh cadence.
     */
    public void validateControllerLink() {
        if (level == null || level.isClientSide)
            return;
        if (dissolvePending) {
            // Its warehouse is gone: it becomes a crane by itself the moment it stands parked on its own dock.
            if (parkedOnOwnDock())
                finishDissolve();
            return;
        }
        if (isMember()) {
            validateMembership();
            return;
        }
        if (linkedController == null) {
            if (membership != null)
                dropRoleIfControllerLost(membership.controller());
            return;
        }
        boolean valid = level.isLoaded(linkedController)
                && level.getBlockEntity(linkedController) instanceof WarehouseControllerBlockEntity controller
                && !controller.isRemoved() && controller.isLinkedTo(worldPosition);
        if (valid)
            return;
        BlockPos lost = linkedController;
        linkedController = null;
        updateLinkFlag();
        if (membership != null)
            dropRoleIfControllerLost(lost);
    }

    /**
     * A member keeps its frame and its claim while its controller is merely not loaded — moving inside a claim is safe
     * without anybody watching, and it asks for nothing new until the controller is back — and leaves the warehouse for
     * good once a loaded block says the controller is gone or no longer lists it (issue #14, ADR-075 §2.5).
     */
    private void validateMembership() {
        BlockPos controllerPos = membership.controller();
        if (!level.isLoaded(controllerPos))
            return; // transient: its frame, its claim and its number stay
        if (level.getBlockEntity(controllerPos) instanceof WarehouseControllerBlockEntity controller
                && !controller.isRemoved() && controller.isLinkedTo(worldPosition)) {
            if (!controllerPos.equals(linkedController)) {
                linkedController = controllerPos;
                updateLinkFlag();
            }
            return;
        }
        warehouseDissolved();
    }

    /**
     * The crane at a warehouse's own dock drops its role once a loaded block says its controller is gone. A controller
     * that has just loaded and not relinked yet still lists it from its save, and that keeps the role and its claim.
     */
    private void dropRoleIfControllerLost(BlockPos controllerPos) {
        if (!level.isLoaded(controllerPos))
            return;
        if (level.getBlockEntity(controllerPos) instanceof WarehouseControllerBlockEntity controller
                && !controller.isRemoved()
                && (controller.isLinkedTo(worldPosition) || controller.listsCrane(worldPosition)))
            return;
        warehouseDissolved();
    }

    private void updateLinkFlag() {
        boolean linked = linkedController != null;
        if (controllerLinked == linked)
            return;
        controllerLinked = linked;
        refreshGoggleInfo();
        sendData();
    }

    // --- persistence, sync, rendering bounds, goggles -------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(AISLE_LENGTH_TAG, aisleLength);
        // Only a warehouse that really bends writes a network; a straight aisle saves and syncs exactly what it did
        // before M21 (ADR-033). A member writes its warehouse's network whatever its shape (issue #14).
        if (warehouseNetwork != null)
            tag.putIntArray(NETWORK_TAG, warehouseNetwork.pack());
        // Only a crane of a warehouse with several writes this, so one crane saves and syncs what it did up to 0.17.1.
        CraneMembership frame = membership;
        if (frame != null) {
            CompoundTag traffic = new CompoundTag();
            frame.write(traffic);
            if (clientPacket) {
                if (trafficHold)
                    traffic.putBoolean(HOLD_TAG, true);
                if (trafficDetour != null)
                    traffic.put(DETOUR_TAG, CraneTrafficClaim.writeRoute(trafficDetour));
            } else {
                trafficClaim.write(traffic);
            }
            tag.put(CraneMembership.TAG, traffic);
        }
        if (clientPacket) {
            tag.putBoolean(CONTROLLER_LINKED_TAG, controllerLinked);
            tag.put(CranePersistence.SYNC_TAG, CranePersistence.writeSync(craneState, restingYaw()));
            CompoundTag goggles = new CompoundTag();
            // The throughput is merged in here and nowhere else, so every client packet carries the same numbers
            // whether it was sent because the crane moved or because somebody is watching it. Without an observer
            // observedThroughput is null and withThroughput returns this very record, so nothing is allocated and
            // nothing is written (M25, issue #16).
            goggleInfo.withThroughput(observedThroughput).write(goggles);
            // Only while a player looks at a dock that has something to say about joining (issue #14).
            if (observedJoin != null) {
                CompoundTag join = new CompoundTag();
                observedJoin.write(join);
                goggles.put(JOIN_TAG, join);
            }
            tag.put(GOGGLE_TAG, goggles);
            return;
        }
        CranePersistence.writeState(tag, craneState, registries, restingYaw());
        tag.put(CranePersistence.HEAD_TAG, head.save(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        AisleGeometry before = geometry();
        NetworkGeometry networkBefore = warehouseNetwork;
        CraneMembership membershipBefore = membership;
        super.read(tag, registries, clientPacket);
        aisleLength = Mth.clamp(tag.getInt(AISLE_LENGTH_TAG), 0, AisleGeometry.MAX_LENGTH);
        // The frame first, because it decides what the network means. A schematic print carries neither the crane nor
        // its head and leaves the frame alone, like everything else it does not describe (issue #25, ADR-061).
        if (clientPacket || tag.contains(CranePersistence.STATE_TAG) || tag.contains(CranePersistence.HEAD_TAG)) {
            CompoundTag traffic = tag.getCompound(CraneMembership.TAG);
            membership = tag.contains(CraneMembership.TAG, Tag.TAG_COMPOUND)
                    ? CraneMembership.read(traffic, worldPosition, facing()).orElse(null) : null;
            if (clientPacket) {
                trafficHold = membership != null && traffic.getBoolean(HOLD_TAG);
                trafficDetour = membership != null && traffic.contains(DETOUR_TAG, Tag.TAG_LIST)
                        ? CraneTrafficClaim.readRoute(traffic.getList(DETOUR_TAG, Tag.TAG_COMPOUND)).orElse(null)
                        : null;
            } else {
                trafficClaim = membership == null ? CraneTrafficClaim.NONE : CraneTrafficClaim.read(traffic);
            }
        }
        boolean member = membership != null && membership.isMember();
        // Never throws: an unreadable network is simply none, and the warehouse is the straight aisle it was.
        warehouseNetwork = tag.contains(NETWORK_TAG, Tag.TAG_INT_ARRAY)
                ? NetworkGeometry.unpack(tag.getIntArray(NETWORK_TAG))
                        .filter(read -> read.branchCount() > 1 || member).orElse(null)
                : null;
        if (!Objects.equals(networkBefore, warehouseNetwork) || !Objects.equals(membershipBefore, membership))
            cachedWarehouse = null;
        // Keep the value box range in step with the (possibly reloaded or synced) config on both sides.
        mastHeight.between(MIN_MAST_HEIGHT, maxMastHeight());
        // A missing or broken "ScrollValue" reads as 0, which is no valid height.
        if (mastHeight.getValue() < MIN_MAST_HEIGHT)
            mastHeight.value = Mth.clamp(DEFAULT_MAST_HEIGHT, MIN_MAST_HEIGHT, maxMastHeight());
        else if (mastHeight.getValue() > AisleGeometry.MAX_HEIGHT)
            mastHeight.value = AisleGeometry.MAX_HEIGHT;
        if (!clientPacket) {
            // A tag carrying neither the crane nor its head is no save of this dock: it is a schematic print landing on
            // a dock that is already standing (BlockHelper.placeSchematicBlock loads the printer's settings-only tag
            // into the block entity that survived the new state, issue #25, ADR-061). It says nothing about the job or
            // the items in the head, so it must not reset the one or delete the other: a world load reads into a fresh
            // dock, where leaving them alone and resetting them are the same thing.
            if (!tag.contains(CranePersistence.STATE_TAG) && !tag.contains(CranePersistence.HEAD_TAG))
                return;
            craneState = CranePersistence.readState(tag, registries, restingYaw());
            head.load(tag.getCompound(CranePersistence.HEAD_TAG), registries);
            // Made consistent with the head and resumed on the first server tick.
            execution.onLoaded();
            return;
        }
        controllerLinked = tag.getBoolean(CONTROLLER_LINKED_TAG);
        readClientCrane(tag.getCompound(CranePersistence.SYNC_TAG));
        goggleInfo = CraneGoggleInfo.read(tag.getCompound(GOGGLE_TAG));
        observedJoin = DockJoinStatus.read(tag.getCompound(GOGGLE_TAG).getCompound(JOIN_TAG)).orElse(null);
        if (!geometry().equals(before) || !Objects.equals(networkBefore, warehouseNetwork)
                || !Objects.equals(membershipBefore, membership))
            invalidateRenderBoundingBox();
    }

    /**
     * Client: adopts the synced phase, target and pause flag. The pose snaps to the synced one on the first packet and
     * when the client's own simulation differs by more than the snap distance of an axis
     * ({@link CraneResync#diverges}); otherwise the client keeps its smooth pose and continues towards the new target.
     */
    private void readClientCrane(CompoundTag syncTag) {
        CraneState<ItemKey, RackPosition> synced = CranePersistence.readSync(syncTag, restingYaw());
        CranePose own = craneState.pose();
        if (!clientSynced || CraneResync.diverges(own, synced.pose(), currentSpeeds())) {
            craneState = synced;
        } else {
            craneState = new CraneState<>(own, craneState.previousPose(), synced.target(), synced.phase(), 0, 0, false,
                    synced.paused(), Optional.empty(), Optional.empty());
        }
        clientSynced = true;
    }

    /**
     * The yaw a crane of this dock faces at rest when nothing says otherwise: the way the aisle at the dock runs.
     * <p>
     * This is what an <b>absent</b> saved or synced yaw means (M21, ADR-033). North would be wrong: a crane saved on
     * an east-facing aisle before 0.6 would spin a quarter turn on its first job after the update, in a warehouse
     * whose rails never bent at all.
     */
    public double restingYaw() {
        return CranePose.yawOf(Headings.of(facing()));
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return warehouse().bounds().inflate(RENDER_BOUNDS_MARGIN);
    }

    /** Aisle blocks of {@code network} beyond the dock: the rails a player laid, counted the way the goggles say it. */
    private static int networkRails(NetworkGeometry network) {
        int rails = 0;
        for (BranchGeometry branch : network.branches())
            rails += branch.length();
        return rails;
    }

    /**
     * "On aisle B at position 7" — where the machine is standing, for a warehouse that bends. Empty while the aisle it
     * stands on has no letter, which is every dock without a linked controller: a position without the letter that
     * gives it meaning would be worse than no line at all ({@link CraneGoggleInfo#aisleLetters()}).
     */
    private Optional<LangBuilder> craneOnAisleLine() {
        CranePose pose = craneState.pose();
        String letters = goggleInfo.aisleLetters();
        if (pose.branch() < 0 || pose.branch() >= letters.length())
            return Optional.empty();
        char letter = letters.charAt(pose.branch());
        if (letter == CraneGoggleInfo.NO_LETTER)
            return Optional.empty();
        return Optional.of(WareworksLang.craneOnAisle(letter, (int) Math.round(pose.x())));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        WareworksLang.translate(WareworksLang.GOGGLES_STACKER_CRANE).forGoggles(tooltip);
        AisleGeometry shown = geometry();
        NetworkGeometry network = networkGeometry();
        // A warehouse of one aisle reads exactly as it always did. One that bends says how big the whole network is —
        // the dock is the only surface that knows that without a controller — and then where the machine is standing,
        // which on a network is a question its own line has to answer (M21, issue #1, ADR-033).
        if (network.branchCount() > 1) {
            WareworksLang.networkSize(networkRails(network), network.branchCount(), shown.height())
                    .forGoggles(tooltip, 1);
            craneOnAisleLine().ifPresent(line -> line.forGoggles(tooltip, 1));
        } else {
            WareworksLang.aisleSize(shown.length(), shown.height()).forGoggles(tooltip, 1);
        }
        if (controllerLinked)
            WareworksLang.translate(WareworksLang.GOGGLES_CONTROLLER_LINKED).style(ChatFormatting.GREEN)
                    .forGoggles(tooltip, 1);
        else
            WareworksLang.translate(WareworksLang.GOGGLES_NO_CONTROLLER).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        // Several cranes on one warehouse (issue #14): which one this is, and — for a dock that faces into a
        // warehouse's rails without being one of its cranes — why not. Neither line exists for one crane.
        goggleInfo.traffic().ifPresent(info -> CraneTrafficLang.craneNumber(info.number(), info.craneCount())
                .forGoggles(tooltip, 1));
        DockJoinStatus join = observedJoin;
        if (join != null)
            WareworksLang.translate(CraneTrafficLang.dockJoinKey(join.reason()),
                    WareworksLang.text(String.valueOf(join.letter())).style(ChatFormatting.WHITE),
                    WareworksLang.number(join.maxCranes()).style(ChatFormatting.WHITE))
                    .style(join.reason() == DockJoinStatus.Reason.PENDING
                            || join.reason() == DockJoinStatus.Reason.OWN_CONTROLLER ? ChatFormatting.GRAY
                                    : ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        // The dock is the surface that shows the whole throughput block: it is the machine itself, its tooltip is the
        // shorter of the two, and a number a player has to crouch for is a number nobody finds (M25, issue #16).
        goggleInfo.addGoggleLines(tooltip, 1, true, true, network.branchCount() > 1,
                goggleInfo.traffic().isPresent() ? remainingTripTicks() : OptionalLong.empty());

        List<Component> kineticStats = new ArrayList<>();
        if (super.addToGoggleTooltip(kineticStats, isPlayerSneaking)) {
            tooltip.add(CommonComponents.EMPTY);
            tooltip.addAll(kineticStats);
        }
        return true;
    }
}
