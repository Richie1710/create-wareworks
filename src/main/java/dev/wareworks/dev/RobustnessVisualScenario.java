package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "robustness": the cases of {@code docs/warehouse-system.md} §8 that GameTests cannot reach, because GameTest
 * areas are force-loaded and a test server never quits to a title screen.
 * <p>
 * It builds one aisle far away from the world spawn (so its chunks really can unload), starts store jobs and then:
 * <ol>
 *   <li><b>chunk round trip</b> — the camera flies far away until the aisle chunks unload while a job is running, waits,
 *       and comes back;</li>
 *   <li><b>save, quit and rejoin</b> — the world is saved in the middle of a job, the game returns to the title screen
 *       (which stops the integrated server) and opens the same world again;</li>
 *   <li><b>blocks broken at defined moments</b> — the controller is broken while the crane carries items, and later the
 *       dock itself, which drops the handling head at the dock.</li>
 * </ol>
 * After every step it counts <b>every item of the scene</b> ({@link SceneItemCensus}: inventories, station buffers,
 * handling head, dropped item entities) and logs one {@code robustness PASS} or {@code robustness FAIL} line. A FAIL
 * throws, so the harness writes a crash report and the Gradle task {@code runRobustnessTest} exits non-zero. Screenshots
 * are incidental here; the logs are the evidence.
 */
public final class RobustnessVisualScenario implements VisualScenario {
    public static final String NAME = "robustness";

    private static final Direction AISLE = Direction.EAST;
    /**
     * Build site, far outside the spawn chunks: the world spawn keeps a radius of about 11 chunks permanently loaded
     * ({@code TicketType.START}), so an aisle at spawn could never unload.
     */
    private static final int BUILD_X = 512;
    private static final int BUILD_Z = 512;
    /**
     * Where the camera goes to drop every chunk ticket of the aisle: far beyond the client's view distance (8 chunks
     * here), but close enough that the trip does not generate and then save a huge amount of new terrain.
     */
    private static final int AWAY_X = 1500;
    private static final int AWAY_Z = 1500;
    /** Spectator flight height for the teleports (the scenario's own camera views are set by the pass). */
    private static final double CAMERA_Y = 90.0;
    /** Any y inside the build height, for the "is the build site loaded" probe. */
    private static final int LOADED_PROBE_Y = 0;

    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 128;
    private static final int STORAGE_FIRST_POSITION = 2;
    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 6;
    /** Blocks added around the rack bounds for the census box, so dropped items are inside it. */
    private static final double CENSUS_MARGIN = 3.0;

    /**
     * The robustness run waits for chunk unloads and a world reload, so it needs more than the default run budget. It
     * stays well below ten minutes, so the watchdog always fires before any CI or tool timeout around the Gradle task.
     */
    private static final long RUN_TIMEOUT_MILLIS = 7L * 60L * 1000L;
    private static final int CHUNK_TIMEOUT_TICKS = 1200;
    private static final int UNLOAD_TIMEOUT_TICKS = 2400;
    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int JOB_TIMEOUT_TICKS = 2400;
    /** Ticks the scene stays unloaded before the camera returns. */
    private static final int UNLOADED_TICKS = 100;
    /** Ticks observed after a block was broken mid job. */
    private static final int BROKEN_OBSERVE_TICKS = 60;
    /** Ticks for dropped item entities to exist and settle before they are counted. */
    private static final int DROP_SETTLE_TICKS = 20;

    private static final int STACK = 64;

    /** Items fed into the input before each phase; fresh stacks, so the expectation and the world cannot share one. */
    private static List<ItemStack> firstFill() {
        return List.of(new ItemStack(Items.IRON_INGOT, STACK), new ItemStack(Items.COPPER_INGOT, STACK));
    }

    private static List<ItemStack> secondFill() {
        return List.of(new ItemStack(Items.GOLD_INGOT, STACK), new ItemStack(Items.REDSTONE, STACK));
    }

    private static List<ItemStack> thirdFill() {
        return List.of(new ItemStack(Items.LAPIS_LAZULI, STACK));
    }

    private static List<ItemStack> fourthFill() {
        return List.of(new ItemStack(Items.COAL, STACK));
    }

    private static final CameraView AFTERMATH = CameraView.of("aftermath", 9.5, 6.0, 6.5, 3.0, 1.0, 0.5);

    /** Set by a server step, read by later server steps (and the shot status on the client). */
    private volatile Map<ItemKey, Long> expected = Map.of();
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile String lastStep = "not started";
    /**
     * The dock's block entity before the camera flies away. A chunk that really unloads marks it removed and builds a
     * new instance from the save when it loads again, which is what this phase has to prove: {@code isLoaded} alone can
     * flip while the block entity is still the same object.
     */
    private volatile StackerCraneBlockEntity dockBeforeUnload;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.client("robustness: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "robustness run"))
                .server("robustness: move the camera to the build site", RobustnessVisualScenario::moveToBuildSite)
                .serverUntil("robustness: wait until the build site is loaded",
                        (server, context) -> server.overworld().isLoaded(new BlockPos(BUILD_X, LOADED_PROBE_Y, BUILD_Z)),
                        CHUNK_TIMEOUT_TICKS)
                .server("robustness: clear the area and place the creative motor", this::placeMotor)
                .server("robustness: build dock, rails, controller, input and storage", this::buildAisle)
                .serverUntil("robustness: wait until the controller is ready with every member", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("robustness: fill the input and take the baseline census", this::fillAndBaseline)
                .server("robustness: power the crane", this::powerOn)
                .serverUntil("robustness: wait until the crane carries items", this::craneCarries, JOB_TIMEOUT_TICKS);

        chunkRoundTrip(script);
        saveAndReload(script);
        brokenBlocks(script);

        script.client("robustness: every step passed",
                context -> LOGGER.info(PREFIX + "robustness: ALL STEPS PASSED (last step: {})", lastStep));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // The evidence of this scenario is the census in the log; one shot per pass documents the end state.
        script.shotFrom(AFTERMATH, "scene");
    }

    @Override
    public String status(VisualContext context) {
        String crane = clientCrane(context).map(be -> {
            CraneState<?, ?> state = be.craneState();
            CranePose pose = state.pose();
            return String.format(Locale.ROOT, "posX=%.2f posY=%.2f phase=%s held=%d", pose.x(), pose.y(), state.phase(),
                    be.goggleInfo().heldCount());
        }).orElse("crane=gone");
        return crane + " step=" + lastStep;
    }

    // --- phases ------------------------------------------------------------------------------------------------------

    /** A running job survives the chunks of its aisle unloading and loading again. */
    private void chunkRoundTrip(VisualScript script) {
        script.server("robustness: remember the dock block entity and move the camera far away", (server, context) -> {
                    dockBeforeUnload = crane(server, context);
                    moveAway(server, context);
                })
                .serverUntil("robustness: wait until the aisle chunks really unload", this::dockUnloaded,
                        UNLOAD_TIMEOUT_TICKS)
                .waitTicks(UNLOADED_TICKS)
                .server("robustness: move the camera back to the aisle", RobustnessVisualScenario::moveToBuildSite)
                .serverUntil("robustness: wait until the aisle is loaded again", this::sceneLoaded, CHUNK_TIMEOUT_TICKS)
                .server("robustness: check that the dock was read from the save again", this::assertDockWasReloaded)
                .server("robustness: census after the chunk round trip",
                        (server, context) -> census(server, "chunk unload and reload while a job ran"))
                .serverUntil("robustness: wait until the interrupted job finished", this::craneIdleAndInputEmpty,
                        JOB_TIMEOUT_TICKS)
                .server("robustness: census after the interrupted job finished",
                        (server, context) -> census(server, "the job finished after the chunk round trip"));
    }

    /** A running job survives a real save, quit to title and rejoin. */
    private void saveAndReload(VisualScript script) {
        script.server("robustness: refill the input", (server, context) -> refill(server, context, secondFill()))
                .serverUntil("robustness: wait until the crane carries items again", this::craneCarries,
                        JOB_TIMEOUT_TICKS)
                .server("robustness: save the world in the middle of the job",
                        (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile());
        script.server("robustness: move the camera back to the aisle", RobustnessVisualScenario::moveToBuildSite)
                .serverUntil("robustness: wait until the aisle is loaded after the reload", this::sceneLoaded,
                        CHUNK_TIMEOUT_TICKS)
                .server("robustness: census after save, quit and rejoin",
                        (server, context) -> census(server, "save, quit and rejoin during a job"))
                .serverUntil("robustness: wait until the resumed job finished", this::craneIdleAndInputEmpty,
                        JOB_TIMEOUT_TICKS)
                .server("robustness: census after the resumed job finished",
                        (server, context) -> census(server, "the job resumed after the reload"));
    }

    /** The controller and then the dock are broken while the crane carries items. */
    private void brokenBlocks(VisualScript script) {
        script.server("robustness: refill the input", (server, context) -> refill(server, context, thirdFill()))
                .serverUntil("robustness: wait until the crane carries items", this::craneCarries, JOB_TIMEOUT_TICKS)
                .server("robustness: break the controller while the crane carries items", this::breakController)
                .waitTicks(BROKEN_OBSERVE_TICKS)
                .server("robustness: census after the controller was broken mid job",
                        (server, context) -> census(server, "the controller broken while the crane carried items"))
                .serverUntil("robustness: wait until the crane finished its job without a controller",
                        this::craneIdleAndEmpty, JOB_TIMEOUT_TICKS)
                .server("robustness: place a controller again", this::placeController)
                .serverUntil("robustness: wait until the new controller adopted the aisle", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("robustness: refill the input once more",
                        (server, context) -> refill(server, context, fourthFill()))
                .serverUntil("robustness: wait until the crane carries items", this::craneCarries, JOB_TIMEOUT_TICKS)
                .server("robustness: break the dock while the crane carries items", this::breakDock)
                .waitTicks(DROP_SETTLE_TICKS)
                .server("robustness: census after the dock was broken mid job",
                        (server, context) -> census(server, "the dock broken while the crane carried items"));
    }

    // --- building (server thread) --------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = new BlockPos(BUILD_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, BUILD_X, BUILD_Z), BUILD_Z);
        context.setOrigin(dock);
        censusBox = layout(dock).bounds().inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        placeController(server, context);

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                BlockPos rack = layout.rackPos(RackPosition.of(x, 0, side));
                level.setBlockAndUpdate(rack.relative(outward),
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
            }
        }
    }

    private void placeController(MinecraftServer server, VisualContext context) {
        server.overworld().setBlockAndUpdate(context.origin().relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        int storage = (RAILS - STORAGE_FIRST_POSITION + 1) * Side.values().length;
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == storage && controller.inputStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    private void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(MOTOR_RPM);
    }

    // --- items and census ------------------------------------------------------------------------------------------

    private void fillAndBaseline(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        fillInput(level, context, firstFill());
        expected = SceneItemCensus.take(level, censusBox);
        lastStep = "baseline";
        LOGGER.info(PREFIX + "robustness: baseline census {} in {}", SceneItemCensus.describe(expected), censusBox);
    }

    private void refill(MinecraftServer server, VisualContext context, List<ItemStack> stacks) {
        expected = SceneItemCensus.plusAll(expected, stacks);
        fillInput(server.overworld(), context, stacks.stream().map(ItemStack::copy).toList());
    }

    /** Inserts through the input's item capability, like a hopper or funnel would. */
    private void fillInput(ServerLevel level, VisualContext context, List<ItemStack> stacks) {
        BlockPos input = layout(context.origin()).rackPos(INPUT);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, input, null);
        if (handler == null)
            throw new VisualTestException("the warehouse input at " + input + " has no item handler");
        for (ItemStack stack : stacks) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
            if (!rest.isEmpty())
                throw new VisualTestException("the warehouse input refused " + rest);
        }
    }

    private void census(MinecraftServer server, String step) {
        lastStep = step;
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, step);
    }

    // --- observations and breaking ---------------------------------------------------------------------------------

    /**
     * The scene is back: the dock position is loaded, <b>every chunk the census box touches</b> is loaded (the box is
     * inflated around the aisle and straddles chunk borders, so waiting for the origin's chunk alone would let a
     * census miss a rack wall), and the controller has all its members again.
     */
    private boolean sceneLoaded(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        return level.isLoaded(context.origin()) && SceneItemCensus.isFullyLoaded(level, censusBox)
                && sceneReady(server, context);
    }

    /**
     * The aisle chunk really unloaded: vanilla's unload callback marked the dock's block entity removed and the position
     * no longer counts as loaded. Checking {@code isLoaded} alone would pass while the block entity is still the same
     * live object waiting in the unload queue, and the phase would prove nothing.
     */
    private boolean dockUnloaded(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity before = dockBeforeUnload;
        return before != null && before.isRemoved() && !server.overworld().isLoaded(context.origin());
    }

    /** After the round trip the dock must be a new block entity, read from the save rather than kept in memory. */
    private void assertDockWasReloaded(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity now = crane(server, context);
        if (now == dockBeforeUnload)
            throw new VisualTestException("the dock block entity was never unloaded and read again");
        LOGGER.info(PREFIX + "robustness: the dock was unloaded and read from the save again (phase {}, holding {})",
                now.craneState().phase(), now.heldItems().totalCount());
    }

    private boolean craneCarries(MinecraftServer server, VisualContext context) {
        return crane(server, context).heldItems().totalCount() > 0;
    }

    private boolean craneIdleAndEmpty(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = crane(server, context);
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private boolean craneIdleAndInputEmpty(MinecraftServer server, VisualContext context) {
        if (!craneIdleAndEmpty(server, context))
            return false;
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(server.overworld(),
                layout(context.origin()).rackPos(INPUT));
        return input != null && input.bufferedItems().isEmpty();
    }

    private void breakController(MinecraftServer server, VisualContext context) {
        requireCarrying(server, context, "the controller");
        // dropBlock = false: the controller block itself must not become an item entity in the census.
        server.overworld().destroyBlock(context.origin().relative(AISLE.getOpposite()), false);
    }

    private void breakDock(MinecraftServer server, VisualContext context) {
        requireCarrying(server, context, "the dock");
        server.overworld().destroyBlock(context.origin(), false);
    }

    private void requireCarrying(MinecraftServer server, VisualContext context, String what) {
        StackerCraneBlockEntity crane = crane(server, context);
        if (crane.heldItems().isEmpty())
            throw new VisualTestException("the crane must carry items when " + what + " is broken, but its head is empty");
        LOGGER.info(PREFIX + "robustness: breaking {} while the crane holds {}", what,
                crane.heldItems().totalCount());
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static StackerCraneBlockEntity crane(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        if (crane == null)
            throw new VisualTestException("the stacker crane dock at " + context.origin() + " is gone");
        return crane;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static void moveToBuildSite(MinecraftServer server, VisualContext context) {
        teleport(server, context, BUILD_X + 0.5, CAMERA_Y, BUILD_Z + 0.5);
    }

    private static void moveAway(MinecraftServer server, VisualContext context) {
        teleport(server, context, AWAY_X + 0.5, CAMERA_Y, AWAY_Z + 0.5);
    }

    private static void teleport(MinecraftServer server, VisualContext context, double x, double y, double z) {
        ServerPlayer player = context.serverPlayer(server);
        player.teleportTo(server.overworld(), x, y, z, player.getYRot(), player.getXRot());
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }
}
