package dev.wareworks.content.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.head.HandlingHead;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneResync;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Block entity of the stacker crane dock ({@code docs/stacker-crane.md} §2-6).
 * <p>
 * <b>Kinetics.</b> A {@link KineticBlockEntity} with a constant stress impact registered through
 * {@code WareworksStress} from the server config. {@link #getSpeed()} (0 without rotation and while overstressed) drives
 * the crane axes through {@link CraneKinematics} and the {@code crane.*} speed factors.
 * <p>
 * <b>Geometry.</b> Length = consecutive warehouse rails in front of the dock ({@link RailScan}), capped at
 * {@code maxAisleLength}; height = the "Mast Height" scroll value ({@value #MIN_MAST_HEIGHT}..{@code maxMastHeight},
 * default {@value #DEFAULT_MAST_HEIGHT}). The server re-counts the rails at most once per {@code geometryRefreshTicks},
 * and immediately on load, on a mast height change and after a facing change (next tick). A refresh costs at most
 * {@code maxAisleLength} block state reads and never loads chunks; rails behind an unloaded chunk keep the last known
 * length ({@link dev.wareworks.core.address.AisleGeometry#scannedLength}). The length is saved and synced.
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
 * {@link CraneExecution#MOVING_SYNC_INTERVAL_TICKS} while moving. The client runs the same {@link CraneMotion} towards the
 * synced target every tick (previous poses for partial-tick rendering, M4) and snaps only when its own pose has really
 * drifted ({@link CraneResync#diverges}).
 * <p>
 * <b>Item conservation.</b> {@link #destroy()} (real break) drops the head at the dock and tells the controller to release
 * the job; {@link #clearContent()} (commands, structures) empties the head without drops, so {@code /clone ... move}
 * cannot duplicate held items. Saving never throws, loading is bounded ({@link InventoryGrabber#load}).
 */
public class StackerCraneBlockEntity extends KineticBlockEntity implements Clearable {
    public static final int MIN_MAST_HEIGHT = AisleGeometry.MIN_HEIGHT;
    public static final int DEFAULT_MAST_HEIGHT = 4;
    /** Parking pose: aisle position 0 at dock level, arm retracted. */
    public static final CranePose HOME_POSE = CranePose.at(0.0, 0.0, CranePose.DEFAULT_SIDE);
    /** Margin in blocks added around the rack bounds for the render bounding box (mast top, arm reach). */
    private static final double RENDER_BOUNDS_MARGIN = 1.0;

    /** NBT key of the aisle length (saved and synced). */
    public static final String AISLE_LENGTH_TAG = "AisleLength";
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

    // --- both sides ---
    @Nullable
    private AisleGeometry cachedGeometry;
    /** Server: the authoritative crane state (saved). Client: the synced pose, target and phase (no job). */
    private CraneState<ItemKey, RackPosition> craneState = CraneState.idle(HOME_POSE);
    /** Server: goggle data as of the last publication. Client: as synced. */
    private CraneGoggleInfo goggleInfo = CraneGoggleInfo.NONE;
    /** Client: whether a crane packet arrived (the first one always snaps). */
    private boolean clientSynced;

    // --- server only (M3) ---
    private final InventoryGrabber head = new InventoryGrabber(this::onHeadChanged);
    private final CraneExecution execution = new CraneExecution(this);

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

    /** World mapping of the aisle: dock position, facing and geometry (no aisle letter; the controller adds it). */
    public AisleLayout layout() {
        return AisleLayout.of(worldPosition, facing(), geometry());
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
        AisleGeometry before = publishedGeometry != null ? publishedGeometry : geometry();

        // Only the range of the value box follows the config; the stored value stays as the player set it.
        mastHeight.between(MIN_MAST_HEIGHT, maxMastHeight());

        int maxLength = Math.min(WareworksConfig.maxAisleLength(), AisleGeometry.MAX_LENGTH);
        aisleLength = RailScan.scan(level, worldPosition, facing(), maxLength).resolveLength(aisleLength, maxLength);

        AisleGeometry after = geometry();
        publishedGeometry = after;
        if (after.equals(before))
            return false;
        onGeometryChanged(before, after);
        notifyUpdate();
        return true;
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

    /** The crane pose for rendering at {@code partialTicks} between the previous and the current tick (M4). */
    public CranePose renderPose(float partialTicks) {
        return craneState.interpolatedPose(partialTicks);
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
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(phase, "phase");
        if (level == null || !level.isClientSide)
            return false;
        craneState = CraneState.displayed(pose, target, phase);
        goggleInfo = new CraneGoggleInfo(phase, CranePauseReason.NONE, Optional.empty(), held, goggleInfo.aisleLetter());
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

    private void refreshGoggleInfo() {
        goggleInfo = new CraneGoggleInfo(craneState.phase(), execution.pauseReason(),
                craneState.job().map(CraneJobSummary::of), CraneGoggleInfo.heldByType(head.held()),
                linkedControllerEntity().map(WarehouseControllerBlockEntity::aisleLetter));
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
        craneState = state.paused() || speeds.isStopped() ? state.withPreviousPose(state.pose())
                : CraneMotion.step(state, speeds);
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
        if (clientPacket) {
            tag.putBoolean(CONTROLLER_LINKED_TAG, controllerLinked);
            tag.put(CranePersistence.SYNC_TAG, CranePersistence.writeSync(craneState));
            CompoundTag goggles = new CompoundTag();
            goggleInfo.write(goggles);
            tag.put(GOGGLE_TAG, goggles);
            return;
        }
        CranePersistence.writeState(tag, craneState, registries);
        tag.put(CranePersistence.HEAD_TAG, head.save(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        AisleGeometry before = geometry();
        super.read(tag, registries, clientPacket);
        aisleLength = Mth.clamp(tag.getInt(AISLE_LENGTH_TAG), 0, AisleGeometry.MAX_LENGTH);
        // Keep the value box range in step with the (possibly reloaded or synced) config on both sides.
        mastHeight.between(MIN_MAST_HEIGHT, maxMastHeight());
        // A missing or broken "ScrollValue" reads as 0, which is no valid height.
        if (mastHeight.getValue() < MIN_MAST_HEIGHT)
            mastHeight.value = Mth.clamp(DEFAULT_MAST_HEIGHT, MIN_MAST_HEIGHT, maxMastHeight());
        else if (mastHeight.getValue() > AisleGeometry.MAX_HEIGHT)
            mastHeight.value = AisleGeometry.MAX_HEIGHT;
        if (!clientPacket) {
            craneState = CranePersistence.readState(tag, registries);
            head.load(tag.getCompound(CranePersistence.HEAD_TAG), registries);
            // Made consistent with the head and resumed on the first server tick.
            execution.onLoaded();
            return;
        }
        controllerLinked = tag.getBoolean(CONTROLLER_LINKED_TAG);
        readClientCrane(tag.getCompound(CranePersistence.SYNC_TAG));
        goggleInfo = CraneGoggleInfo.read(tag.getCompound(GOGGLE_TAG));
        if (!geometry().equals(before))
            invalidateRenderBoundingBox();
    }

    /**
     * Client: adopts the synced phase, target and pause flag. The pose snaps to the synced one on the first packet and
     * when the client's own simulation differs by more than the snap distance of an axis
     * ({@link CraneResync#diverges}); otherwise the client keeps its smooth pose and continues towards the new target.
     */
    private void readClientCrane(CompoundTag syncTag) {
        CraneState<ItemKey, RackPosition> synced = CranePersistence.readSync(syncTag);
        CranePose own = craneState.pose();
        if (!clientSynced || CraneResync.diverges(own, synced.pose(), currentSpeeds())) {
            craneState = synced;
        } else {
            craneState = new CraneState<>(own, craneState.previousPose(), synced.target(), synced.phase(), 0, 0, false,
                    synced.paused(), Optional.empty(), Optional.empty());
        }
        clientSynced = true;
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return layout().bounds().inflate(RENDER_BOUNDS_MARGIN);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        WareworksLang.translate(WareworksLang.GOGGLES_STACKER_CRANE).forGoggles(tooltip);
        AisleGeometry shown = geometry();
        WareworksLang.aisleSize(shown.length(), shown.height()).forGoggles(tooltip, 1);
        if (controllerLinked)
            WareworksLang.translate(WareworksLang.GOGGLES_CONTROLLER_LINKED).style(ChatFormatting.GREEN)
                    .forGoggles(tooltip, 1);
        else
            WareworksLang.translate(WareworksLang.GOGGLES_NO_CONTROLLER).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        goggleInfo.addGoggleLines(tooltip, 1, true);

        List<Component> kineticStats = new ArrayList<>();
        if (super.addToGoggleTooltip(kineticStats, isPlayerSneaking)) {
            tooltip.add(CommonComponents.EMPTY);
            tooltip.addAll(kineticStats);
        }
        return true;
    }
}
