package dev.wareworks.content.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.head.HandlingHead;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.item.ItemKey;
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
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailGraph;
import dev.wareworks.core.warehouse.RailNetwork;
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

    // --- both sides ---
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
                new MastHeightValueBox());
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
        WarehouseLayout cached = cachedWarehouse;
        if (cached != null && cached.dock().equals(worldPosition) && cached.facing() == facing()
                && cached.network().equals(network))
            return cached;
        cached = WarehouseLayout.of(worldPosition, facing(), network, Optional.empty());
        cachedWarehouse = cached;
        return cached;
    }

    /**
     * The network the crane plans its motion in: the warehouse's aisles at the configured mast height, or the single
     * straight aisle of {@link #geometry()} while no controller has given this dock one.
     */
    public NetworkGeometry networkGeometry() {
        NetworkGeometry network = warehouseNetwork;
        int height = mastHeight();
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
        NetworkGeometry next = network == null || network.branchCount() < 2 ? null : network;
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
        return HomeReturn.of(WareworksConfig.returnHomeIdleTicks(), network.branchCount(), home);
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

        int maxLength = Math.min(WareworksConfig.maxAisleLength(), AisleGeometry.MAX_LENGTH);
        RailNetwork network = RailNetworkScan.scan(level, worldPosition, facing(), new RailGraph.Limits(
                WareworksConfig.maxNetworkRails(), WareworksConfig.maxBranches(), WareworksConfig.maxJunctions(),
                maxLength, mastHeight()));
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
        execution.onGeometryChanged();
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
        if (throughputSync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
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
        return warehouse().railOffset(state.previousPose(), state.pose(), partialTicks);
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
        warehouseNetwork = network == null || network.branchCount() < 2 ? null : network;
        cachedWarehouse = null;
        craneState = CraneState.displayed(pose, target, phase);
        // No throughput: a shown pose is a picture, and a Ponder scene or a visual scenario measures nothing.
        goggleInfo = new CraneGoggleInfo(phase, CranePauseReason.NONE, Optional.empty(), held,
                goggleInfo.aisleLetters(), Optional.empty());
        clientSynced = true;
        return true;
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
        goggleInfo = new CraneGoggleInfo(craneState.phase(), execution.pauseReason(),
                craneState.job().map(CraneJobSummary::of), CraneGoggleInfo.heldByType(head.held()),
                linkedControllerEntity().map(StackerCraneBlockEntity::aisleLettersOf).orElse(""), Optional.empty());
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
        if (!isVirtual() && !isRemoved())
            execution.tick(level);
    }

    /**
     * Client: moves the synced pose towards the synced target with the same deterministic motion as the server, so the
     * crane animates smoothly between packets. Phase transitions and item transfers only happen on the server.
     */
    private void tickClientMotion() {
        CraneState<ItemKey, RackPosition> state = craneState;
        CraneSpeeds speeds = currentSpeeds();
        if (state.paused() || speeds.isStopped()) {
            craneState = state.withPreviousPose(state.pose());
            return;
        }
        CraneNetwork network = craneNetwork();
        CranePose pose = state.pose();
        CranePose target = state.target();
        CraneRoute route = pose.branch() == target.branch() ? null
                : network.route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
        craneState = CraneMotion.step(state, speeds, route, network.turnPenaltyBlocks());
        wheelOdometer += CraneMotion.blocksDriven(pose, craneState.pose(), route);
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

    /** Real removal: the controller behind the dock re-links and finds no dock. */
    @Override
    public void remove() {
        super.remove();
        notifyControllerBehind(facing());
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
        updateLinkFlag();
    }

    /** Server: the controller at {@code controller} unlinks this dock; ignored unless it is the current owner. */
    public void unlinkController(BlockPos controller) {
        if (!controller.equals(linkedController))
            return;
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
        if (linkedController == null || level == null || level.isClientSide)
            return;
        boolean valid = level.isLoaded(linkedController)
                && level.getBlockEntity(linkedController) instanceof WarehouseControllerBlockEntity controller
                && !controller.isRemoved() && controller.isLinkedTo(worldPosition);
        if (valid)
            return;
        linkedController = null;
        updateLinkFlag();
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
        // before M21 (ADR-033).
        if (warehouseNetwork != null)
            tag.putIntArray(NETWORK_TAG, warehouseNetwork.pack());
        if (clientPacket) {
            tag.putBoolean(CONTROLLER_LINKED_TAG, controllerLinked);
            tag.put(CranePersistence.SYNC_TAG, CranePersistence.writeSync(craneState, restingYaw()));
            CompoundTag goggles = new CompoundTag();
            // The throughput is merged in here and nowhere else, so every client packet carries the same numbers
            // whether it was sent because the crane moved or because somebody is watching it. Without an observer
            // observedThroughput is null and withThroughput returns this very record, so nothing is allocated and
            // nothing is written (M25, issue #16).
            goggleInfo.withThroughput(observedThroughput).write(goggles);
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
        super.read(tag, registries, clientPacket);
        aisleLength = Mth.clamp(tag.getInt(AISLE_LENGTH_TAG), 0, AisleGeometry.MAX_LENGTH);
        // Never throws: an unreadable network is simply none, and the warehouse is the straight aisle it was.
        warehouseNetwork = tag.contains(NETWORK_TAG, Tag.TAG_INT_ARRAY)
                ? NetworkGeometry.unpack(tag.getIntArray(NETWORK_TAG)).filter(read -> read.branchCount() > 1)
                        .orElse(null)
                : null;
        if (!Objects.equals(networkBefore, warehouseNetwork))
            cachedWarehouse = null;
        // Keep the value box range in step with the (possibly reloaded or synced) config on both sides.
        mastHeight.between(MIN_MAST_HEIGHT, maxMastHeight());
        // A missing or broken "ScrollValue" reads as 0, which is no valid height.
        if (mastHeight.getValue() < MIN_MAST_HEIGHT)
            mastHeight.value = Mth.clamp(DEFAULT_MAST_HEIGHT, MIN_MAST_HEIGHT, maxMastHeight());
        else if (mastHeight.getValue() > AisleGeometry.MAX_HEIGHT)
            mastHeight.value = AisleGeometry.MAX_HEIGHT;
        if (!clientPacket) {
            craneState = CranePersistence.readState(tag, registries, restingYaw());
            head.load(tag.getCompound(CranePersistence.HEAD_TAG), registries);
            // Made consistent with the head and resumed on the first server tick.
            execution.onLoaded();
            return;
        }
        controllerLinked = tag.getBoolean(CONTROLLER_LINKED_TAG);
        readClientCrane(tag.getCompound(CranePersistence.SYNC_TAG));
        goggleInfo = CraneGoggleInfo.read(tag.getCompound(GOGGLE_TAG));
        if (!geometry().equals(before) || !Objects.equals(networkBefore, warehouseNetwork))
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
        // The dock is the surface that shows the whole throughput block: it is the machine itself, its tooltip is the
        // shorter of the two, and a number a player has to crouch for is a number nobody finds (M25, issue #16).
        goggleInfo.addGoggleLines(tooltip, 1, true, true, network.branchCount() > 1);

        List<Component> kineticStats = new ArrayList<>();
        if (super.addToGoggleTooltip(kineticStats, isPlayerSneaking)) {
            tooltip.add(CommonComponents.EMPTY);
            tooltip.addAll(kineticStats);
        }
        return true;
    }
}
