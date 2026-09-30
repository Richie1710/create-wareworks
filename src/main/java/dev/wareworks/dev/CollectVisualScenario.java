package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "collect": the third direction of the warehouse port ({@code docs/warehouse-system.md} §3.2.4, ADR-030, M18,
 * issue #13) — the warehouse <b>fetches</b> instead of waiting.
 * <p>
 * One aisle with a rack wall of storage locations, and on the other plane a <b>collecting port</b> whose machine is the
 * barrel right behind it. Nothing pushes anything anywhere: the barrel is filled the way a machine fills its own output
 * chest, and the crane reaches through the port, takes the items out and stores them — which is the whole feature in one
 * picture, and the reason a machine needs no belt back to a warehouse input.
 * <p>
 * <b>Every claim a screenshot makes is asserted on the server before the shot that makes it</b>, and the moving part runs
 * in the Flywheel pass, so the second pass photographs exactly the same finished scene:
 * <ol>
 *   <li><b>the port is copper and collects</b> — its direction is the collect sentinel, its block state says so, and the
 *       {@code port-*} shots show the copper ring from inside the aisle and the copper spout from behind, where the other
 *       two directions show brass and andesite;</li>
 *   <li><b>the crane fetches through the port</b> — the frozen {@code collect-arm} shot is the arm extended <b>into the
 *       port</b> on a {@code COLLECT} job, which is the one moment that tells this feature from storing;</li>
 *   <li><b>and stores what it fetched</b> — the barrel ends up empty, the racks hold the items, and the port's own
 *       counter says how many it fetched;</li>
 *   <li><b>the goggles say all of it</b> — the port's tooltip names what it collects, the inventory behind it, what is
 *       ready there and what it has collected; the controller's tooltip counts the collecting ports of the aisle.</li>
 * </ol>
 * An {@link SceneItemCensus} around every item move proves that nothing was created or lost while all of that happened.
 * <p>
 * <b>Why this scenario needs a real player.</b> Two of its shots are goggle tooltips, and Create only draws one for a
 * non-spectator who looks at a block within reach, so it runs with {@link VisualWorldProfile#playable} and sets that
 * reach back to 0 for the world shots — a value box under the crosshair would otherwise cover the very block the shot is
 * about (the port has two of them).
 */
public final class CollectVisualScenario implements VisualScenario {
    public static final String NAME = "collect";

    /** Its own throw-away world: a scenario with a player must not reuse the camera world of another one. */
    private static final String WORLD_FOLDER = "wareworks_visual_collect";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    /** Blocks cleared to air around the aisle and above the floor. */
    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 9;
    /** Blocks added around the rack bounds for the census box, so a dropped item would be inside it. */
    private static final int CENSUS_MARGIN = 2;

    /** The collecting port, far enough from the dock that the crane really travels to it. */
    private static final RackPosition PORT = RackPosition.of(6, 0, Side.RIGHT);
    /** Storage locations on the other plane, near the dock, so the collect trip crosses the whole aisle. */
    private static final int STORAGE_FIRST_POSITION = 1;
    private static final int STORAGE_LAST_POSITION = 3;

    /** What the machine leaves in its output barrel: one trip, so one arm movement tells the whole story. */
    private static final ItemKey COLLECTED = ItemKey.of(Items.OAK_PLANKS);
    private static final int IN_MACHINE = 32;

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int PORT_READY_TIMEOUT_TICKS = 400;
    private static final int MOMENT_TIMEOUT_TICKS = 1200;
    private static final int COLLECTED_TIMEOUT_TICKS = 2400;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 10;
    private static final double ARM_EXTENDED = 0.9;
    /** A travel moment needs this much left on one axis, so the crane is still travelling when the freeze arrives. */
    private static final double MIN_REMAINING_TRAVEL = 1.0;

    /** From the right (south) side, over the port's rack row, into the aisle. */
    private static final CameraView SIDE = CameraView.of("side", 5.5, 10.0, 8.0, 5.5, 1.0, 0.5);
    /** From beyond the aisle end, along the rails back to the dock. */
    private static final CameraView ALONG = CameraView.of("aisle", 12.5, 3.0, 0.5, 1.0, 1.5, 0.5);
    /** Inside the aisle in front of the port: the face the crane reaches through, where the copper ring is. */
    private static final CameraView PORT_FRONT = CameraView.of("port-front", 3.4, 1.4, 0.5, 6.0, 1.0, 1.0);
    /** Behind the port, where its machine stands and where the copper spout is. */
    private static final CameraView PORT_BACK = CameraView.of("port-back", 6.0, 2.3, 5.4, 6.0, 1.1, 1.6);
    /** The rack row the items end up in, from inside the aisle. */
    private static final CameraView STORED = CameraView.of("stored", 6.0, 2.4, 0.5, 1.0, 1.0, -1.0);
    /** Goggle shot of the port, from inside the aisle but off its value boxes (which sit on the other faces). */
    private static final CameraView AT_PORT = CameraView.of("at-port", 4.2, 1.3, 0.5, 6.0, 1.05, 1.0);
    /**
     * Goggle shot of the controller, west of it and aimed at the <b>lower left corner</b> of its back face rather than at
     * its middle: the controller's aisle letter is a scroll value box on every face but the dock's, and a value box the
     * crosshair really hits makes Create's goggle overlay bail out before it draws a line ({@link GoggleShots}).
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("at-controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);

    /** Following camera ahead of the crane, for the moments the game is frozen for. */
    private static final String APPROACH = "approach";
    private static final double AHEAD = 2.4;
    private static final double ABOVE = 1.7;
    private static final String CLOSE = "arm";
    private static final double CLOSE_AHEAD = 1.7;
    private static final double CLOSE_ABOVE = 1.3;
    private static final double CLOSE_OFFSIDE = 0.35;
    private static final double CLOSE_LOOK_ASIDE = 0.8;
    private static final double CARRIAGE_LOOK_HEIGHT = 0.7;
    private static final double ARM_LOOK_HEIGHT = 0.55;
    private static final double BLOCK_CENTER = 0.5;

    /** The census box of this aisle, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene holds, for the census; the items never leave the box, they only move inside it. */
    private volatile Map<ItemKey, Long> expected = Map.of();

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: the two goggle shots need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.server("collect: clear the area and place the creative motor", this::placeMotor)
                .server("collect: build dock, rails, controller, the rack wall and the port with its machine",
                        CollectVisualScenario::buildAisle)
                .serverUntil("collect: wait until the controller is ready with every member",
                        CollectVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so it
                // is lifted into the air first and only then made to fly.
                .server("collect: lift the player into the air", CollectVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("collect: the player flies and wears Engineer's Goggles", CollectVisualScenario::equipPlayer)
                .server("collect: turn the port around with the collect row of its board",
                        CollectVisualScenario::makeItCollect)
                .serverUntil("collect: wait until the controller has read the port and its machine",
                        CollectVisualScenario::portReady, PORT_READY_TIMEOUT_TICKS)
                .server("collect: put " + IN_MACHINE + " " + COLLECTED + " into the machine's output barrel",
                        this::fillMachine)
                .server("collect: take the baseline census", (server, context) -> census(server, "before collecting"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and the port has two of
        // them, so the world shots are taken by a player who reaches nothing.
        reach(script, 0.0);
        fly(script);
        for (CameraView view : List.of(SIDE, ALONG))
            script.shotFrom(view, "idle");

        if (pass == VisualPass.FLYWHEEL) {
            script.server("collect: creative motor to " + MOTOR_RPM + " RPM", CollectVisualScenario::powerOn);
            // The two moments that tell collecting from storing: the crane on its way to the port, and its arm inside
            // the port, on a COLLECT job.
            moment(script, "collect-" + APPROACH, CollectVisualScenario::approachingThePort,
                    CollectVisualScenario::frontView);
            moment(script, "collect-" + CLOSE, CollectVisualScenario::armInThePort, CollectVisualScenario::armView);
            script.until("collect: wait until the crane carries what it fetched",
                            CollectVisualScenario::carryingCollectedItems, MOMENT_TIMEOUT_TICKS)
                    .freeze(true)
                    .camera(ALONG)
                    .shot("carry-aisle")
                    .freeze(false)
                    .serverUntil("collect: wait until everything is out of the machine and in the racks",
                            CollectVisualScenario::allCollected, COLLECTED_TIMEOUT_TICKS)
                    .server("collect: census after the whole machine was collected",
                            (server, context) -> census(server, "after collecting"))
                    .server("collect: check what the port fetched and where it went", this::assertCollected);
        }

        fly(script);
        for (CameraView view : List.of(STORED, PORT_FRONT, PORT_BACK))
            script.shotFrom(view, "collect");

        if (pass == VisualPass.FLYWHEEL) {
            // The goggles: the only shots with the GUI shown, and the only ones that need the real reach.
            reach(script, vanillaReach());
            fly(script);
            GoggleShots.shot(script, NAME, AT_PORT, "goggles-port",
                    dock -> layout(dock).rackPos(PORT), CollectVisualScenario::portNumbersSynced,
                    CollectVisualScenario::checkPortGoggles);
            fly(script);
            GoggleShots.shot(script, NAME, AT_CONTROLLER, "goggles-controller",
                    CollectVisualScenario::controllerPos, CollectVisualScenario::controllerNumbersSynced,
                    CollectVisualScenario::checkControllerGoggles);
            reach(script, 0.0);
            script.client("collect: every check passed",
                    context -> LOGGER.info(PREFIX + "collect: ALL CHECKS PASSED (the port collects, the crane reaches "
                            + "through it, the machine is emptied into the racks, the counter and both goggle "
                            + "tooltips agree, and the census is unchanged)"));
        }
    }

    @Override
    public String status(VisualContext context) {
        return clientCrane(context).map(crane -> {
            CranePose pose = crane.craneState().pose();
            String job = crane.goggleInfo().job().map(summary -> summary.type().name()).orElse("none");
            return String.format(Locale.ROOT, "posX=%.2f posY=%.2f arm=%.2f side=%s phase=%s job=%s held=%d",
                    pose.x(), pose.y(), pose.arm(), pose.side(), crane.craneState().phase(), job,
                    crane.goggleInfo().heldCount());
        }).orElse("crane=missing") + GoggleShots.describeHover(context);
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = layout(dock).bounds().inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        // The rack wall: the items the crane fetches end up here, in plain sight of the "stored" camera.
        Direction storageOutward = layout.sideDirection(Side.LEFT);
        for (int x = STORAGE_FIRST_POSITION; x <= STORAGE_LAST_POSITION; x++) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.LEFT));
            level.setBlockAndUpdate(rack.relative(storageOutward), Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, storageOutward.getOpposite()));
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, storageOutward));
        }
        // The port faces the aisle like every station, and its machine's output barrel stands right behind it — the same
        // world position a storage location's inventory would occupy, which is the geometry of §3.2.4.
        Direction portOutward = layout.sideDirection(PORT.side());
        BlockPos portPos = layout.rackPos(PORT);
        level.setBlockAndUpdate(portPos, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, portOutward.getOpposite()));
        level.setBlockAndUpdate(portPos.relative(portOutward), Blocks.BARREL.defaultBlockState());
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        int expectedStorage = STORAGE_LAST_POSITION - STORAGE_FIRST_POSITION + 1;
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked();
    }

    /** The wrench board's fourth row, as a player sets it: the direction is the rank, and nothing else. */
    private static void makeItCollect(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        if (!port.setPortRank(PortSettings.COLLECT_RANK))
            throw new VisualTestException("the port did not take the collect direction");
        port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
        if (port.portDirection() != PortDirection.COLLECT)
            throw new VisualTestException("the port does not collect: " + port.portSettings());
        BlockState state = server.overworld().getBlockState(layout(context.origin()).rackPos(PORT));
        if (!state.getValue(WarehouseOutputBlock.COLLECTING) || state.getValue(WarehouseOutputBlock.ACCEPTING))
            throw new VisualTestException("the block does not show the collect direction: " + state);
        LOGGER.info(PREFIX + "collect: the port is {} on {}", port.portDirection(), port.redstoneMode());
    }

    /**
     * Whether the controller has really <b>read</b> the port and the inventory behind it. Waiting for this rather than
     * for a tick count is what keeps the run honest: an unread port collects nothing on purpose, and a scenario that
     * shot the scene before the first read would photograph that instead of collecting.
     */
    private static boolean portReady(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.collectingPortCount() == 1
                && !controller.collectsFromOwnStorage(layout(context.origin()).rackPos(PORT));
    }

    /** Fills the machine's output barrel through its item capability, exactly as a machine's own output would. */
    private void fillMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos barrel = machinePos(context.origin());
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, barrel, null);
        if (handler == null)
            throw new VisualTestException("the machine's output barrel at " + barrel + " has no item handler");
        ItemStack rest = ItemHandlerHelper.insertItem(handler, COLLECTED.toStack(IN_MACHINE), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the machine's output barrel refused " + rest);
        expected = SceneItemCensus.plus(Map.of(), COLLECTED, IN_MACHINE);
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(MOTOR_RPM);
    }

    private static boolean allCollected(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty() && countIn(level, machinePos(context.origin())) == 0;
    }

    /** The claim every later shot rests on: the machine is empty, the racks hold its items, and the port counted them. */
    private void assertCollected(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = controller.countOf(COLLECTED);
        long collected = port(level, dock).collectedItems();
        long inMachine = countIn(level, machinePos(dock));
        LOGGER.info(PREFIX + "collect: stored={} collected={} left in the machine={}", stored, collected, inMachine);
        if (inMachine != 0)
            throw new VisualTestException("the machine still holds " + inMachine + " " + COLLECTED);
        if (stored != IN_MACHINE)
            throw new VisualTestException("the racks hold " + stored + " " + COLLECTED + ", not " + IN_MACHINE);
        if (collected != IN_MACHINE)
            throw new VisualTestException("the port counted " + collected + " collected items, not " + IN_MACHINE);
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEquals(level, censusBox, expected, step);
        LOGGER.info(PREFIX + "collect: census {} -> {}", step, SceneItemCensus.describe(expected));
    }

    // --- the player --------------------------------------------------------------------------------------------------

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * Lifts the player back into the air and switches creative flight on again before every group of camera views: a
     * creative player that touches the ground switches flying off by itself, and the next camera in mid-air would then
     * never arrive ({@link PrioritiesVisualScenario}).
     */
    private static void fly(VisualScript script) {
        script.server("collect: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("collect: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    /** Flying, wearing Engineer's Goggles and holding nothing, so no item covers a corner of a GUI shot. */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, NAME, range);
    }

    private static double vanillaReach() {
        return GoggleShots.vanillaReach();
    }

    // --- the goggle tooltips -----------------------------------------------------------------------------------------

    private static boolean portNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos pos = layout(context.origin()).rackPos(PORT);
        return level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port
                && port.summary().collect().collected() == IN_MACHINE;
    }

    /** What the port's tooltip has to say: the direction, what it collects, what is behind it, and its counter. */
    private static void checkPortGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, layout(context.origin()).rackPos(PORT));
        LOGGER.info(PREFIX + "collect: port goggles {}", lines);
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_COLLECTING).getString());
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY).getString());
        GoggleShots.requireLine(lines, Blocks.BARREL.getName().getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PORT_COLLECTED, IN_MACHINE));
        // A collecting port has no rank magnitude at all, so neither accept line may ever appear on it.
        GoggleShots.requireNoLine(lines,
                WareworksLang.translate(WareworksLang.GOGGLES_PORT_OVERFLOW, "").component().getString().trim());
    }

    private static boolean controllerNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        return level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller && controller.summary().collectingPorts() == 1;
    }

    private static void checkControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        LOGGER.info(PREFIX + "collect: controller goggles {}", lines);
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_COLLECTING_PORTS, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_ITEMS_STORED, IN_MACHINE));
    }

    // --- moments (client thread, synced crane state) -------------------------------------------------------------

    private static void moment(VisualScript script, String moment,
            java.util.function.Predicate<VisualContext> condition,
            java.util.function.Function<VisualContext, CameraView> view) {
        script.until("collect: wait for the crane moment '" + moment + "'", condition, MOMENT_TIMEOUT_TICKS)
                .freeze(true)
                .camera(moment, view)
                .shot(moment)
                .freeze(false);
    }

    private static boolean approachingThePort(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_SOURCE
                && isCollectJob(crane) && remainingTravel(crane) > MIN_REMAINING_TRAVEL).isPresent();
    }

    /** The one moment that tells collecting from storing: the arm inside the <b>port</b>, on a collect job. */
    private static boolean armInThePort(VisualContext context) {
        return clientCrane(context).filter(crane -> {
            CranePhase phase = crane.craneState().phase();
            return isCollectJob(crane) && (phase == CranePhase.EXTEND_SOURCE || phase == CranePhase.PICK)
                    && crane.craneState().pose().arm() >= ARM_EXTENDED;
        }).isPresent();
    }

    private static boolean carryingCollectedItems(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && isCollectJob(crane) && crane.goggleInfo().heldCount() > 0
                && remainingTravel(crane) > MIN_REMAINING_TRAVEL).isPresent();
    }

    private static boolean isCollectJob(StackerCraneBlockEntity crane) {
        return crane.goggleInfo().job().filter(job -> job.type() == JobType.COLLECT).isPresent();
    }

    private static double remainingTravel(StackerCraneBlockEntity crane) {
        return Math.max(Math.abs(crane.craneState().target().x() - crane.craneState().pose().x()),
                Math.abs(crane.craneState().target().y() - crane.craneState().pose().y()));
    }

    /** In the aisle ahead of the crane, looking back at the carriage. */
    private static CameraView frontView(VisualContext context) {
        CranePose pose = frozenPose(context);
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(APPROACH, craneX + AISLE.getStepX() * AHEAD, pose.y() + ABOVE,
                craneZ + AISLE.getStepZ() * AHEAD, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    /** Close in front of the carriage, off to the other side, looking at the arm reaching into the port. */
    private static CameraView armView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction armSide = pose.side() == Side.LEFT ? AISLE.getCounterClockWise() : AISLE.getClockWise();
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(CLOSE,
                craneX + AISLE.getStepX() * CLOSE_AHEAD - armSide.getStepX() * CLOSE_OFFSIDE, pose.y() + CLOSE_ABOVE,
                craneZ + AISLE.getStepZ() * CLOSE_AHEAD - armSide.getStepZ() * CLOSE_OFFSIDE,
                craneX + armSide.getStepX() * CLOSE_LOOK_ASIDE, pose.y() + ARM_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * CLOSE_LOOK_ASIDE);
    }

    private static CranePose frozenPose(VisualContext context) {
        return clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    /** The inventory a collecting port reaches into: the block behind it, i.e. away from the aisle. */
    private static BlockPos machinePos(BlockPos dock) {
        BranchLayout layout = layout(dock);
        return layout.rackPos(PORT).relative(layout.sideDirection(PORT.side()));
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the warehouse controller of the aisle is missing");
        return controller;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(dock).rackPos(PORT));
        if (port == null)
            throw new VisualTestException("the warehouse port of the aisle is missing");
        return port;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static long countIn(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (COLLECTED.matches(stack))
                total += stack.getCount();
        }
        return total;
    }
}
