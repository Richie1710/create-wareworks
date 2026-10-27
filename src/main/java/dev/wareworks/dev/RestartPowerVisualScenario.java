package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlock;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenarios "restart", "restart-stale" and "restart-late" (issue #38, ADR-071): a warehouse driven like the owner's
 * server, three Large Water Wheels into a Rotation Speed Controller at {@value #TARGET_RPM} RPM, through a real save,
 * quit and rejoin ({@link VisualWorld#reload}: the integrated server stops, the world is written to disk and loaded
 * from it again).
 * <p>
 * Layout, relative to the dock (raised {@value #DOCK_RAISE} blocks above the superflat surface, built at
 * ({@value #FAR}, {@value #FAR}) so nothing of it lies in the spawn chunks): the aisle runs east with {@value #RAILS}
 * rails, the controller behind the dock, output {@code 1/0 L}, input {@code 1/0 R}, interface + chest storage from
 * position {@value #STORAGE_FIRST_POSITION}. Below the dock a vertical shaft comes up from a gearbox; a line of shafts
 * runs south from the gearbox to the large cogwheel on top of the speed controller (axis X), whose input shaft comes from
 * the west, from the three wheels (axis X) over a water channel dug into the surface, flowing south.
 * <ul>
 * <li>{@code restart}: the power train {@value #NEAR_TRAIN_Z} blocks south of the dock. Store a load, save, rejoin, and
 * the crane must store and deliver again: a plain restart of a healthy network.</li>
 * <li>{@code restart-stale} and {@code restart-late}: the power train {@value #FAR_TRAIN_Z} blocks south of the dock,
 * sixteen chunk columns away, and the dock saved overstressed. The warehouse's chunks and the power train's chunks are
 * force-loaded (what {@code /forceload}, the warehouse's own chunk loading or a chunk loader do on a server). Then the
 * power train's chunks are released and the player walks away: the chunk of the wheels really unloads (its block
 * entities are removed), the warehouse chunks keep ticking. A chunk closer than {@value #UNLOAD_DISTANCE_CHUNKS} chunk
 * columns to a forced one would only drop to an inaccessible level and keep its block entities in memory, which
 * Create's network treats as still present. One more shaft is then placed on the warehouse side of the network (any
 * change that makes Create recompute the network does); Create's {@code KineticNetwork#calculateCapacity} drops the
 * unloaded wheels without counting them as unloaded capacity and syncs capacity 0 into every loaded member, the dock
 * included. That overstress is real (the network has no generator left) and must survive the recheck of ADR-071 for
 * {@value #REAL_OVERSTRESS_TICKS} ticks. The wheels come back through {@code KineticNetwork#addSilently}, which never
 * recomputes; a steady network (wheels with an unchanged flow score, a fixed speed controller) never recomputes on its
 * own either, so without the recheck the dock stays overstressed with a network that has the capacity.
 * <ul>
 * <li>{@code restart-stale}: the game ticks are frozen, the power train chunks are forced again and load (their block
 * entities do not tick, so the wheels do not even join), and the world is saved with the dock overstressed: after the
 * rejoin every forced chunk loads at once, the dock reads {@code Capacity < Stress} from its own save, and the network
 * has the capacity.</li>
 * <li>{@code restart-late}: saved with the power train unloaded. After the rejoin only the warehouse loads; the power
 * train chunks are forced {@value #OBSERVE_TICKS} ticks later, so the wheels join a network that is already running
 * with the dock overstressed (what a player arriving later, or a generator chunk loading later, does). This is the
 * same silent join as in a session without a restart.</li>
 * </ul>
 * </li>
 * </ul>
 * Both fail without the recheck ({@code CHECK FAIL: ... still overstressed ...}). After the check the scenario refills
 * the input and requests items to the output and fails unless the crane carries both out within
 * {@value #AFTER_RELOAD_TIMEOUT_TICKS} ticks.
 */
public final class RestartPowerVisualScenario implements VisualScenario {
    public static final String NAME = "restart";
    public static final String NAME_STALE = "restart-stale";
    public static final String NAME_LATE = "restart-late";

    /** How the dock comes into the save. */
    private enum Variant {
        /** Healthy: a plain restart. */
        PLAIN,
        /** Saved overstressed, the wheels saved loaded beside it: everything loads at once. */
        STALE,
        /** Saved overstressed, the wheels saved unloaded and loaded after the rejoin. */
        LATE
    }

    private static final Direction AISLE = Direction.EAST;
    private static final int FAR = 512;
    private static final int DOCK_RAISE = 6;
    private static final int RAILS = 6;
    private static final int STORAGE_FIRST_POSITION = 3;
    private static final int STORAGE_LEVELS = 2;
    private static final RackPosition OUTPUT = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    private static final int TARGET_RPM = 128;
    private static final int WHEELS = 3;
    /** Blocks between the gearbox and the speed controller's cogwheel ({@code restart}). */
    private static final int NEAR_TRAIN_Z = 3;
    /**
     * Chunk columns a chunk must be away from a force-loaded one to unload for real: a forced ticket has level 31 and
     * every column adds one; a chunk stays in memory up to {@code ChunkLevel.MAX_LEVEL} (44 in 1.21.1).
     */
    private static final int UNLOAD_DISTANCE_CHUNKS = 14;
    /**
     * Blocks between the gearbox and the speed controller's cogwheel ({@code restart-stale}): the wheels then lie
     * sixteen chunk columns from the force-loaded warehouse chunks, so they unload while the warehouse keeps ticking.
     */
    private static final int FAR_TRAIN_Z = 256;
    /** The water channel runs from this many blocks north of the cogwheel ... */
    private static final int CHANNEL_BEFORE = 4;
    /** ... to this many blocks south of it. */
    private static final int CHANNEL_AFTER = 3;

    private static final long RUN_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final int SCENE_READY_TIMEOUT_TICKS = 1200;
    private static final int STORED_TIMEOUT_TICKS = 2400;
    private static final int CHUNK_TIMEOUT_TICKS = 600;
    private static final int UNLOAD_TIMEOUT_TICKS = 1200;
    private static final int AFTER_RELOAD_TIMEOUT_TICKS = 1800;
    private static final int OBSERVE_TICKS = 100;
    /**
     * Ticks the dock must stay overstressed while the wheels are unloaded: more than the first recheck and two more
     * ({@code OverstressRecheck}: 40 + 2 x 100), so the recheck cannot clear a real overstress.
     */
    private static final int REAL_OVERSTRESS_TICKS = 250;
    private static final int LOG_EVERY_TICKS = 100;
    private static final int REQUESTED = 8;
    private static final double CAMERA_Y_ABOVE = 4.0;

    private static final CameraView POWER = CameraView.of("power", -9.5, 1.0, 11.5, -2.0, -5.0, 2.5);
    private static final CameraView OVERVIEW = CameraView.of("overview", 4.5, 6.0, 11.5, 2.0, -2.0, 1.5);

    private final String name;
    private final Variant variant;
    private final int trainZ;
    private final List<KineticBlockEntity> wheelsBeforeLeaving = new ArrayList<>();
    private long checkStart = -1;
    private long lastLog = -1;
    private int requestedBefore;

    private RestartPowerVisualScenario(String name, Variant variant) {
        this.name = name;
        this.variant = variant;
        this.trainZ = variant == Variant.PLAIN ? NEAR_TRAIN_Z : FAR_TRAIN_Z;
    }

    static RestartPowerVisualScenario plain() {
        return new RestartPowerVisualScenario(NAME, Variant.PLAIN);
    }

    static RestartPowerVisualScenario stale() {
        return new RestartPowerVisualScenario(NAME_STALE, Variant.STALE);
    }

    static RestartPowerVisualScenario late() {
        return new RestartPowerVisualScenario(NAME_LATE, Variant.LATE);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setup(VisualScript script) {
        script.client(name + ": give the run its own time budget",
                context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, name + " run"));
        if (variant != Variant.PLAIN)
            script.server(name + ": force-load the warehouse and the power train chunks", (server, context) -> {
                force(server, "warehouse", warehouseChunks(), true);
                force(server, "power train", powerChunks(), true);
            });
        script.server(name + ": move the camera to the build site", this::moveToBuildSite)
                .serverUntil(name + ": wait until the build site and the power train are loaded",
                        (server, context) -> server.overworld().shouldTickBlocksAt(new BlockPos(FAR, 0, FAR))
                                && server.overworld().shouldTickBlocksAt(new BlockPos(FAR - 2, 0, FAR + trainZ)),
                        CHUNK_TIMEOUT_TICKS)
                .server(name + ": build the aisle and the power train", this::build)
                .serverUntil(name + ": wait until the controller is ready and the crane turns", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server(name + ": state before the first load", (server, context) -> logState(server, context,
                        "before-first-load"))
                .server(name + ": fill the input", (server, context) -> fillInput(server, context, firstLoad()))
                .serverUntil(name + ": wait until the crane stored the first load", this::allStored,
                        STORED_TIMEOUT_TICKS)
                .server(name + ": state before the save", (server, context) -> logState(server, context, "before-save"));
        if (variant != Variant.PLAIN)
            overstressSteps(script);
        if (variant == Variant.STALE)
            wheelsBackFrozenSteps(script);
        script.server(name + ": save the world", (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile());
        if (variant == Variant.LATE)
            lateRejoinSteps(script);
        else
            rejoinSteps(script);
        script.server(name + ": the dock is not overstressed by a network that has the capacity",
                this::checkNoStaleOverstress);
    }

    /**
     * Overstresses the dock for real: the warehouse chunks stay loaded, the wheels' chunk unloads and the network is
     * recomputed while the wheels are away. Then the dock must stay overstressed under the recheck.
     */
    private void overstressSteps(VisualScript script) {
        script.server(name + ": remember the water wheels' block entities", this::rememberWheels)
                .server(name + ": release the power train chunks and leave for the world spawn", (server, context) -> {
                    force(server, "power train", powerChunks(), false);
                    leaveBuildSite(server, context);
                })
                .serverUntil(name + ": wait until the chunk of the water wheels has unloaded", this::wheelsUnloaded,
                        UNLOAD_TIMEOUT_TICKS)
                .server(name + ": state while the wheels are unloaded", (server, context) -> logQuiet(server, context,
                        "wheels-unloaded"))
                .server(name + ": place one more shaft on the warehouse side of the network", this::extendNetwork)
                .waitTicks(REAL_OVERSTRESS_TICKS)
                .server(name + ": state after the network changed while the wheels were unloaded",
                        (server, context) -> logQuiet(server, context, "changed-while-unloaded"))
                .server(name + ": the dock stays overstressed while the network has no generator",
                        this::requireRealOverstress);
    }

    /**
     * {@code restart-stale}: brings the wheels' chunk back with the game ticks frozen, so the save holds the dock
     * overstressed beside the wheels. While frozen, chunks still load but no block entity ticks: the dock cannot
     * recheck and the wheels do not even initialise. The freeze is not saved.
     */
    private void wheelsBackFrozenSteps(VisualScript script) {
        script.freeze(true)
                .server(name + ": load the power train chunks again and return", (server, context) -> {
                    force(server, "power train", powerChunks(), true);
                    moveToBuildSite(server, context);
                })
                .serverUntil(name + ": wait until the water wheels are loaded again", this::wheelsBack,
                        CHUNK_TIMEOUT_TICKS)
                .server(name + ": the dock is saved overstressed", this::requireOverstressedBeforeSave);
    }

    /** {@code restart} and {@code restart-stale}: everything loads with the world. */
    private void rejoinSteps(VisualScript script) {
        if (variant == Variant.STALE)
            script.freeze(false);
        script.server(name + ": move the camera back to the build site", this::moveToBuildSite)
                .serverUntil(name + ": wait until the aisle and the power train are loaded after the rejoin",
                        this::sceneLoaded, CHUNK_TIMEOUT_TICKS);
        observe(script, "after-rejoin");
    }

    /**
     * {@code restart-late}: only the warehouse loads with the world; the power train is forced {@value #OBSERVE_TICKS}
     * ticks later, and the wheels join the running network silently.
     */
    private void lateRejoinSteps(VisualScript script) {
        script.server(name + ": move the camera back to the build site", this::moveToBuildSite)
                .serverUntil(name + ": wait until the aisle is loaded after the rejoin", this::warehouseLoaded,
                        CHUNK_TIMEOUT_TICKS)
                .server(name + ": state right after the rejoin, the wheels unloaded", (server, context) ->
                        logQuiet(server, context, "after-rejoin-0"))
                .waitTicks(OBSERVE_TICKS)
                .server(name + ": state after the rejoin, the wheels still unloaded", (server, context) ->
                        logQuiet(server, context, "after-rejoin-" + OBSERVE_TICKS))
                .server(name + ": load the power train chunks", (server, context) ->
                        force(server, "power train", powerChunks(), true))
                .serverUntil(name + ": wait until the water wheels are loaded again", this::wheelsBack,
                        CHUNK_TIMEOUT_TICKS);
        observe(script, "wheels-back");
    }

    /** Logs the full state now and twice {@value #OBSERVE_TICKS} ticks later, touching nothing. */
    private void observe(VisualScript script, String moment) {
        script.server(name + ": state at " + moment, (server, context) -> logState(server, context, moment + "-0"))
                .waitTicks(OBSERVE_TICKS)
                .server(name + ": state at " + moment + ", untouched", (server, context) -> logState(server, context,
                        moment + "-" + OBSERVE_TICKS))
                .waitTicks(OBSERVE_TICKS)
                .server(name + ": state at " + moment + ", untouched", (server, context) -> logState(server, context,
                        moment + "-" + 2 * OBSERVE_TICKS));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.shotFrom(POWER, "rejoined").shotFrom(OVERVIEW, "rejoined");
        if (pass != VisualPass.FLYWHEEL)
            return;
        script.server(name + ": refill the input and request items after the rejoin", this::refillAndRequest)
                .serverUntil(name + ": wait until the crane stored the refill and delivered the request",
                        this::workedAfterRejoin, AFTER_RELOAD_TIMEOUT_TICKS + 200)
                .server(name + ": state after the work", (server, context) -> logState(server, context, "after-work"))
                .server(name + ": result", (server, context) -> LOGGER.info(PREFIX + "{}: CHECK PASS: the warehouse "
                        + "stored and delivered after a real save, quit and rejoin", name));
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane))
            return "crane=missing";
        return String.format(Locale.ROOT, "speed=%.1f overStressed=%s phase=%s", crane.getSpeed(),
                crane.isOverStressed(), crane.craneState().phase());
    }

    // --- build -------------------------------------------------------------------------------------------------------

    private void moveToBuildSite(MinecraftServer server, VisualContext context) {
        teleport(server, context, FAR + 0.5, FAR + 0.5);
    }

    private void leaveBuildSite(MinecraftServer server, VisualContext context) {
        teleport(server, context, 0.5, 0.5);
    }

    private static void teleport(MinecraftServer server, VisualContext context, double x, double z) {
        ServerPlayer player = context.serverPlayer(server);
        ServerLevel level = server.overworld();
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) x, (int) z);
        player.teleportTo(level, x, surface + DOCK_RAISE + CAMERA_Y_ABOVE, z, player.getYRot(), player.getXRot());
    }

    private void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, FAR, FAR);
        BlockPos ground = new BlockPos(FAR, surface, FAR);
        BlockPos dock = ground.above(DOCK_RAISE);
        context.setOrigin(dock);
        LOGGER.info(PREFIX + "{}: building at dock {} (surface y {}), power train {} blocks south", name, dock, surface,
                trainZ);
        clear(level, ground.offset(-8, 0, -4), ground.offset(RAILS + 3, 12, NEAR_TRAIN_Z + CHANNEL_AFTER + 3));
        clear(level, ground.offset(-8, 0, trainZ - CHANNEL_BEFORE - 3),
                ground.offset(2, 6, trainZ + CHANNEL_AFTER + 3));

        // Power train, bottom up; the water goes in last.
        for (int y = 3; y < DOCK_RAISE; y++)
            level.setBlockAndUpdate(ground.above(y), shaft(Direction.Axis.Y));
        BlockPos gearbox = gearbox(dock);
        level.setBlockAndUpdate(gearbox,
                AllBlocks.GEARBOX.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        for (int z = 1; z < trainZ; z++)
            level.setBlockAndUpdate(gearbox.south(z), shaft(Direction.Axis.Z));
        BlockPos cog = gearbox.south(trainZ);
        level.setBlockAndUpdate(cog,
                AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, Direction.Axis.Z));
        BlockPos controller = cog.below();
        level.setBlockAndUpdate(controller, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState()
                .setValue(SpeedControllerBlock.HORIZONTAL_AXIS, Direction.Axis.X));
        level.setBlockAndUpdate(controller.west(), shaft(Direction.Axis.X));
        for (int i = 0; i < WHEELS; i++)
            level.setBlockAndUpdate(wheel(dock, i), AllBlocks.LARGE_WATER_WHEEL.getDefaultState()
                    .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));

        // The aisle.
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, layout.sideDirection(OUTPUT.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    level.setBlockAndUpdate(rack.relative(outward),
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, outward));
                }
            }
        }

        // Power on: a channel one block deep in the surface under the wheels, flowing south from a row of sources.
        for (int i = 0; i < WHEELS; i++) {
            BlockPos wheel = wheel(dock, i);
            for (int z = -CHANNEL_BEFORE; z <= CHANNEL_AFTER; z++) {
                BlockPos cell = new BlockPos(wheel.getX(), surface - 1, cog.getZ() + z);
                level.setBlockAndUpdate(cell, z == -CHANNEL_BEFORE ? Blocks.WATER.defaultBlockState()
                        : Blocks.AIR.defaultBlockState());
            }
        }
        speedController(level, dock).targetSpeed.setValue(TARGET_RPM);
    }

    private static void clear(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    private static BlockState shaft(Direction.Axis axis) {
        return AllBlocks.SHAFT.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, axis);
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        StackerCraneBlockEntity crane = crane(level, context.origin());
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        boolean ready = controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == expectedStorage && crane.isControllerLinked()
                && Math.abs(crane.getSpeed()) == TARGET_RPM && !crane.isOverStressed();
        long now = level.getGameTime();
        if (!ready && now - lastLog >= LOG_EVERY_TICKS) {
            lastLog = now;
            logState(server, context, "waiting-for-ready");
        }
        return ready;
    }

    private boolean sceneLoaded(MinecraftServer server, VisualContext context) {
        return warehouseLoaded(server, context) && server.overworld().isLoaded(wheel(context.origin(), 0));
    }

    /** The dock and the controller are loaded; never looks at the power train. */
    private boolean warehouseLoaded(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return level.isLoaded(dock) && crane(level, dock) != null && controller(level, dock) != null;
    }

    // --- the stale state ---------------------------------------------------------------------------------------------

    /** The chunk columns of the warehouse footprint (controller, dock, rails, racks and storage chests). */
    private static List<ChunkPos> warehouseChunks() {
        return chunks(FAR - 2, FAR + RAILS + 1, FAR - 3, FAR + 3);
    }

    /**
     * The chunk columns of the shaft line south of the warehouse, the cogwheel, the speed controller, the wheels and
     * their water channel, without the warehouse's own. Create propagates rotation only through loaded chunks, so the
     * whole line must be loaded while the network forms.
     */
    private List<ChunkPos> powerChunks() {
        List<ChunkPos> chunks = chunks(FAR - 2 - WHEELS - 2, FAR + 1, FAR, FAR + trainZ + CHANNEL_AFTER + 2);
        chunks.removeAll(warehouseChunks());
        return chunks;
    }

    private static List<ChunkPos> chunks(int minX, int maxX, int minZ, int maxZ) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(minX); x <= SectionPos.blockToSectionCoord(maxX); x++)
            for (int z = SectionPos.blockToSectionCoord(minZ); z <= SectionPos.blockToSectionCoord(maxZ); z++)
                chunks.add(new ChunkPos(x, z));
        return chunks;
    }

    private void force(MinecraftServer server, String what, List<ChunkPos> chunks, boolean force) {
        ServerLevel level = server.overworld();
        for (ChunkPos chunk : chunks)
            level.setChunkForced(chunk.x, chunk.z, force);
        LOGGER.info(PREFIX + "{}: {} chunks {} forced={}", name, what, chunks, force);
    }

    private void rememberWheels(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        wheelsBeforeLeaving.clear();
        for (int i = 0; i < WHEELS; i++) {
            if (!(level.getBlockEntity(wheel(context.origin(), i)) instanceof WaterWheelBlockEntity wheel))
                throw new VisualTestException("water wheel " + i + " is missing before leaving");
            wheelsBeforeLeaving.add(wheel);
        }
    }

    /**
     * Whether the wheels' chunk has really unloaded: not loaded any more ({@code hasChunk} never loads one) and the block
     * entities the network knows removed with it. Never looks up a block entity there, which would load the chunk.
     */
    private boolean wheelsUnloaded(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        boolean unloaded = !level.isLoaded(wheel(dock, 0)) && wheelsBeforeLeaving.stream()
                .allMatch(BlockEntity::isRemoved);
        long now = level.getGameTime();
        if (unloaded || now - lastLog >= LOG_EVERY_TICKS) {
            lastLog = now;
            LOGGER.info(PREFIX + "{}: wheels' chunk loaded={} removed={} ({}); warehouse loaded={}; player at {}", name,
                    level.isLoaded(wheel(dock, 0)), wheelsBeforeLeaving.stream().map(BlockEntity::isRemoved).toList(),
                    level.getChunkSource().getChunkDebugData(new ChunkPos(wheel(dock, 0))).replace('\n', ' '),
                    level.isLoaded(dock), context.serverPlayer(server).blockPosition());
        }
        return unloaded;
    }

    /** One more shaft north of the gearbox: Create propagates rotation into it and recomputes the network. */
    private void extendNetwork(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos pos = gearbox(context.origin()).north();
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir())
            throw new VisualTestException("the place for the extra shaft at " + pos + " is not free and loaded");
        level.setBlockAndUpdate(pos, shaft(Direction.Axis.Z));
    }

    private boolean wheelsBack(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!level.isLoaded(wheel(dock, 0)))
            return false;
        for (int i = 0; i < WHEELS; i++) {
            BlockEntity be = level.getBlockEntity(wheel(dock, i));
            if (!(be instanceof WaterWheelBlockEntity wheel) || wheelsBeforeLeaving.contains(wheel)
                    || !level.shouldTickBlocksAt(wheel.getBlockPos()))
                return false;
        }
        return true;
    }

    /**
     * Precondition and check of the stale variants, {@value #REAL_OVERSTRESS_TICKS} ticks after the network changed
     * without the wheels: the dock is overstressed (otherwise this run could not produce the state it is about), and it
     * still is, although the recheck ran three times: the network really has no capacity.
     */
    private void requireRealOverstress(MinecraftServer server, VisualContext context) {
        Verdict verdict = verdict(server.overworld(), context.origin());
        LOGGER.info(PREFIX + "{}: with the wheels unloaded: {}", name, verdict);
        if (!verdict.overStressed())
            throw new VisualTestException("PRECONDITION FAIL: the dock is not overstressed while the wheels are "
                    + "unloaded, so this run did not produce the state it is about: " + verdict);
        if (verdict.capacity() >= verdict.stress())
            throw new VisualTestException("PRECONDITION FAIL: the network still counts capacity for the unloaded "
                    + "wheels: " + verdict);
        LOGGER.info(PREFIX + "{}: PRECONDITION PASS: the dock is overstressed with the wheels unloaded", name);
        LOGGER.info(PREFIX + "{}: CHECK PASS: a really overstressed dock stays overstressed for {} ticks", name,
                REAL_OVERSTRESS_TICKS);
    }

    /** {@code restart-stale}: the dock goes into the save overstressed, the wheels loaded beside it. */
    private void requireOverstressedBeforeSave(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = crane(server.overworld(), context.origin());
        if (crane == null || !crane.isOverStressed())
            throw new VisualTestException("PRECONDITION FAIL: the dock is not overstressed before the save: "
                    + craneLine(server.overworld(), context.origin()));
        LOGGER.info(PREFIX + "{}: PRECONDITION PASS: the dock is saved overstressed beside the loaded wheels: {}",
                name, craneLine(server.overworld(), context.origin()));
    }

    private void checkNoStaleOverstress(MinecraftServer server, VisualContext context) {
        Verdict verdict = verdict(server.overworld(), context.origin());
        LOGGER.info(PREFIX + "{}: after the rejoin: {}", name, verdict);
        if (verdict.overStressed() && !verdict.stale())
            throw new VisualTestException("CHECK FAIL: the network is really overstressed after the rejoin: " + verdict);
        if (verdict.stale())
            throw new VisualTestException("CHECK FAIL: " + 2 * OBSERVE_TICKS + " ticks after everything was loaded "
                    + "the dock is still overstressed although the network has the capacity: " + verdict);
        LOGGER.info(PREFIX + "{}: CHECK PASS: no stale overstress after the rejoin", name);
    }

    /**
     * What the dock believes (its synced capacity and stress) against what its network computes now from the members
     * that are loaded. {@code calculateCapacity}/{@code calculateStress} do not sync the totals, but like any Create
     * recompute they drop members whose block entity was unloaded, and they load that chunk synchronously.
     */
    private record Verdict(boolean overStressed, float speed, float dockCapacity, float dockStress, float capacity,
                           float stress, int sources, int members) {
        boolean stale() {
            return overStressed && capacity >= stress;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "dock overStressed=%s speed=%.1f capacity=%.1f stress=%.1f; network "
                            + "recomputed capacity=%.1f stress=%.1f sources=%d members=%d", overStressed, speed,
                    dockCapacity, dockStress, capacity, stress, sources, members);
        }
    }

    private static Verdict verdict(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane == null || !crane.hasNetwork())
            throw new VisualTestException("the dock is missing or has no kinetic network");
        KineticNetwork network = crane.getOrCreateNetwork();
        float capacity = network.calculateCapacity();
        float stress = network.calculateStress();
        return new Verdict(crane.isOverStressed(), crane.getSpeed(), (float) field(crane, "capacity"),
                (float) field(crane, "stress"), capacity, stress, network.sources.size(), network.members.size());
    }

    // --- work after the rejoin ---------------------------------------------------------------------------------------

    private static List<ItemStack> firstLoad() {
        return List.of(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.COPPER_INGOT, 32));
    }

    private void fillInput(MinecraftServer server, VisualContext context, List<ItemStack> stacks) {
        ServerLevel level = server.overworld();
        BlockPos input = layout(context.origin()).rackPos(INPUT);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, input, null);
        if (handler == null)
            throw new VisualTestException("the warehouse input at " + input + " has no item handler");
        for (ItemStack stack : stacks) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the warehouse input refused " + rest);
        }
    }

    private boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (now - lastLog >= LOG_EVERY_TICKS) {
            lastLog = now;
            logState(server, context, "storing");
        }
        return allStoredQuiet(level, context.origin());
    }

    private void refillAndRequest(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        fillInput(server, context, List.of(new ItemStack(Items.GOLD_INGOT, 16)));
        requestedBefore = outputCount(level, context.origin());
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing after the rejoin");
        RequestResult result = controller.request(layout(context.origin()).rackPos(OUTPUT), ItemKey.of(Items.IRON_INGOT),
                REQUESTED);
        LOGGER.info(PREFIX + "{}: request {} iron to the output after the rejoin: {}", name, REQUESTED, result);
        if (result.request().isEmpty())
            throw new VisualTestException("the controller refused the request after the rejoin: " + result);
        checkStart = level.getGameTime();
        lastLog = checkStart;
    }

    private boolean workedAfterRejoin(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        boolean stored = allStoredQuiet(level, context.origin());
        int delivered = outputCount(level, context.origin()) - requestedBefore;
        if (stored && delivered >= REQUESTED) {
            LOGGER.info(PREFIX + "{}: the crane stored the refill and delivered {} iron {} ticks after the request",
                    name, delivered, now - checkStart);
            return true;
        }
        if (now - lastLog >= LOG_EVERY_TICKS) {
            lastLog = now;
            logState(server, context, "after-rejoin-working-" + (now - checkStart));
        }
        if (now - checkStart > AFTER_RELOAD_TIMEOUT_TICKS) {
            logState(server, context, "after-rejoin-timeout");
            throw new VisualTestException("CHECK FAIL: " + (now - checkStart) + " ticks after the rejoin the crane "
                    + "has not worked: stored=" + stored + " delivered=" + delivered + "; " + craneLine(level,
                    context.origin()));
        }
        return false;
    }

    private static boolean allStoredQuiet(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = crane(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input is missing");
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty() && input.bufferedItems().isEmpty();
    }

    private static int outputCount(ServerLevel level, BlockPos dock) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, layout(dock).rackPos(OUTPUT), null);
        if (handler == null)
            return 0;
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            count += handler.getStackInSlot(slot).getCount();
        return count;
    }

    // --- state log ---------------------------------------------------------------------------------------------------

    /**
     * The full state: the dock, the warehouse controller, every block of the power train and the network's recomputed
     * totals. Looks up block entities in the power train, so it loads their chunk: never while the wheels are meant to
     * be unloaded ({@link #logQuiet}).
     */
    private void logState(MinecraftServer server, VisualContext context, String moment) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        LOGGER.info(PREFIX + "{}: STATE {} t={} {}", name, moment, level.getGameTime(), craneLine(level, dock));
        WarehouseControllerBlockEntity controller = controller(level, dock);
        LOGGER.info(PREFIX + "{}: STATE {} controller {}", name, moment, controller == null ? "missing"
                : "status=" + controller.status() + " membershipDirty=" + controller.isMembershipDirty()
                        + " storage=" + controller.storageLocations().size() + " openRequests="
                        + controller.openRequestCount());
        for (BlockPos pos : trainPositions(dock)) {
            BlockEntity be = level.getBlockEntity(pos);
            String what = level.getBlockState(pos).getBlock().getDescriptionId().replace("block.", "");
            LOGGER.info(PREFIX + "{}: STATE {} {} {} {}", name, moment, what, rel(pos, dock),
                    be instanceof KineticBlockEntity kinetic ? kinetic(kinetic) : "no kinetic block entity");
        }
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane != null && crane.hasNetwork())
            LOGGER.info(PREFIX + "{}: STATE {} network {}", name, moment, network(crane.getOrCreateNetwork()));
    }

    /** The dock and its network's stored totals only: no block entity lookup outside the warehouse, no recomputation. */
    private void logQuiet(MinecraftServer server, VisualContext context, String moment) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        LOGGER.info(PREFIX + "{}: STATE {} t={} wheels loaded={} {}", name, moment, level.getGameTime(),
                level.isLoaded(wheel(dock, 0)), craneLine(level, dock));
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane != null && crane.hasNetwork()) {
            KineticNetwork network = crane.getOrCreateNetwork();
            LOGGER.info(PREFIX + "{}: STATE {} network id={} members={} sources={} currentStress={} "
                            + "currentCapacity={} unloadedStress={} unloadedCapacity={}", name, moment, network.id,
                    network.members.size(), network.sources.size(), field(network, "currentStress"),
                    field(network, "currentCapacity"), field(network, "unloadedStress"),
                    field(network, "unloadedCapacity"));
        }
    }

    private static String craneLine(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane == null)
            return "crane=missing";
        return String.format(Locale.ROOT, "crane pause=%s phase=%s job=%s held=%s linked=%s %s", crane.pauseReason(),
                crane.craneState().phase(), crane.currentJob().isPresent(), !crane.heldItems().isEmpty(),
                crane.isControllerLinked(), kinetic(crane));
    }

    private List<BlockPos> trainPositions(BlockPos dock) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos gearbox = gearbox(dock);
        for (int y = DOCK_RAISE - 1; y > 2; y--)
            positions.add(gearbox.above(y - 2));
        positions.add(gearbox);
        positions.add(gearbox.south());
        positions.add(gearbox.south(trainZ));
        BlockPos controller = gearbox.south(trainZ).below();
        positions.add(controller);
        positions.add(controller.west());
        for (int i = 0; i < WHEELS; i++)
            positions.add(wheel(dock, i));
        return positions;
    }

    private static String rel(BlockPos pos, BlockPos dock) {
        BlockPos d = pos.subtract(dock);
        return "(" + d.getX() + "," + d.getY() + "," + d.getZ() + ")";
    }

    private static String kinetic(KineticBlockEntity be) {
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "speed=%.2f theoretical=%.2f generated=%.2f overStressed=%s source=%s network=%s capacity=%.1f "
                        + "stress=%.1f size=%s lastImpact=%.2f lastCapacity=%.2f",
                be.getSpeed(), be.getTheoreticalSpeed(), be.getGeneratedSpeed(), be.isOverStressed(),
                be.source == null ? "none" : be.source.subtract(be.getBlockPos()).toShortString(), be.network,
                (float) field(be, "capacity"), (float) field(be, "stress"), field(be, "networkSize"),
                (float) field(be, "lastStressApplied"), (float) field(be, "lastCapacityProvided")));
        if (be instanceof WaterWheelBlockEntity wheel)
            line.append(" flowScore=").append(wheel.flowScore);
        if (be instanceof SpeedControllerBlockEntity controller)
            line.append(" target=").append(controller.targetSpeed.getValue());
        return line.toString();
    }

    private static String network(KineticNetwork network) {
        return String.format(Locale.ROOT, "id=%s members=%d sources=%d calculatedStress=%.1f calculatedCapacity=%.1f "
                        + "currentStress=%.1f currentCapacity=%.1f unloadedStress=%.1f unloadedCapacity=%.1f "
                        + "unloadedMembers=%s", network.id, network.members.size(), network.sources.size(),
                network.calculateStress(), network.calculateCapacity(), (float) field(network, "currentStress"),
                (float) field(network, "currentCapacity"), (float) field(network, "unloadedStress"),
                (float) field(network, "unloadedCapacity"), field(network, "unloadedMembers"));
    }

    /** Reads a private field of Create's kinetic classes for the log (dev only). */
    private static Object field(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // look further up
            } catch (IllegalAccessException e) {
                throw new VisualTestException("cannot read " + fieldName + ": " + e);
            }
        }
        throw new VisualTestException("no field " + fieldName + " on " + target.getClass());
    }

    // --- lookups -----------------------------------------------------------------------------------------------------

    private static BlockPos gearbox(BlockPos dock) {
        return dock.below(DOCK_RAISE - 2);
    }

    private BlockPos wheel(BlockPos dock, int index) {
        return gearbox(dock).south(trainZ).below().west(2 + index);
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        return WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        return WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level, dock.relative(AISLE.getOpposite()));
    }

    private SpeedControllerBlockEntity speedController(ServerLevel level, BlockPos dock) {
        BlockPos pos = gearbox(dock).south(trainZ).below();
        if (!(level.getBlockEntity(pos) instanceof SpeedControllerBlockEntity controller))
            throw new VisualTestException("the rotation speed controller at " + pos + " is missing");
        return controller;
    }
}
