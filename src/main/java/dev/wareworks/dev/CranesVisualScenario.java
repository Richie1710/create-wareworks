package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.CraneMember;
import dev.wareworks.content.controller.CraneRoster;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneTrafficInfo;
import dev.wareworks.content.crane.StackerCraneBlock;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.traffic.TrafficVerdict;
import dev.wareworks.core.traffic.TrafficWait;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.CraneTrafficLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "cranes": <b>two stacker cranes on one warehouse</b> (issue #14, ADR-075), the shots M33 is judged by.
 * <p>
 * <b>The build</b>, relative to the warehouse's own dock (the scene origin), which faces east with the controller behind
 * it: {@value #MAIN_RAILS} rails east down a hall and a <b>second dock</b> at the far end facing back west, without a
 * controller of its own, so it is crane 2 of the same warehouse and the main run (aisle A) is
 * {@value #MEMBER_X} blocks long with crane 2's dock on its last position. Three side aisles of {@value #TOOTH_RAILS}
 * rails run south off positions {@value #TEETH_TEXT} (B, C and D). One <b>warehouse terminal</b> stands in the middle of
 * the run, a <b>home point</b> beside position {@value #HOME_X} is crane 2's (it is the nearer crane, and that is how
 * the warehouse pairs them), and a <b>warehouse output</b> with a lever stands in aisle D. Every rack is a chest behind a
 * storage interface, filled before the warehouse reads it: iron near crane 1, diamonds near crane 2, emeralds beside crane
 * 1's own dock (which only crane 1 serves: the blocks beside a dock are its own crane's), lapis deep in aisle C and some
 * scenery stock in aisles B and C. Each dock has a creative motor of its own.
 * <p>
 * <b>The acts</b>, each bracketed by an item census of the whole scene, each frozen moment reached by a <b>server</b>
 * condition that freezes the ticks in the same server tick, and every poll asserting that no two cranes cover one block
 * (ADR-075 I1/I2):
 * <ol>
 * <li>Both cranes parked — crane 1 on its dock, crane 2 on its home point — from above and down the hall, and the
 * controller's crane list in English and German.</li>
 * <li><b>Two orders at the terminal</b> through real clicks ({@link ScreenInput}): iron, which lies near crane 1, and
 * diamonds, which lie near crane 2. The nearest free crane takes each, and the shot {@code cranes-both-moving} is the
 * first tick in which both drive at once. Both deliver into the one terminal, so one of them has to wait until the other
 * has left the terminal's block: {@code cranes-waiting} is the first tick in which a crane with a job holds because the
 * other holds the rails it needs, with that crane's dock goggles ("Waiting for crane 1: it holds the rails at A-07") in
 * both languages.</li>
 * <li><b>A redstone retrieval and a terminal order at once</b>: the lever asks the output in aisle D for lapis from aisle
 * C (crane 2 is nearer), the terminal for iron (crane 1): {@code cranes-parallel} is the first tick in which both
 * carry. It stands in for the design's production supply, which {@code MultiCraneGameTests} covers.</li>
 * <li><b>Moving aside</b>: crane 2 rests on its home point when the output asks for emeralds, which only crane 1 can
 * fetch and whose way into aisle D runs over that home point. The home point is in the way itself, so crane 2 moves
 * aside to its <b>dock</b>: {@code cranes-moving-aside}, with crane 2's goggles ("Moving aside for crane 1").</li>
 * <li><b>A crane without rotation</b>: crane 2's motor is stopped on its home point and the output asks for emeralds
 * again. Crane 1 waits with "Waiting for crane 2: it is stopped" ({@code cranes-unpowered}); the motor turns again and
 * everything completes.</li>
 * </ol>
 * The second (no-Flywheel) pass parks both again, shoots the hall and orders iron and diamonds once more for its own
 * {@code cranes-both-moving}. The run ends with {@code cranes: ALL CHECKS PASSED in English and German}.
 * <p>
 * Dev tooling only (ADR-014). Written for the owner to run; the design it checks is {@code docs/stacker-crane.md} §4.8.
 */
public final class CranesVisualScenario implements VisualScenario {
    public static final String NAME = "cranes";
    /** Its own throw-away world: goggle tooltips need a non-spectator with the vanilla reach. */
    private static final String WORLD_FOLDER = "wareworks_visual_cranes";
    /** Two orders per act, five acts, eight language switches and the second pass: the default budget is too short. */
    private static final long RUN_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private static final Direction MAIN_AISLE = Direction.EAST;
    private static final Direction TOOTH_AISLE = Direction.SOUTH;
    /** Rails east of the warehouse's own dock, before the second dock. */
    private static final int MAIN_RAILS = 14;
    /** Where crane 2's dock stands on the main run: right after its last rail. */
    private static final int MEMBER_X = MAIN_RAILS + 1;
    /** Rails of each side aisle beyond its junction. */
    private static final int TOOTH_RAILS = 4;
    /** Where the side aisles leave the main run; aisle {@code i + 1} hangs off {@code TEETH[i]}. */
    private static final int[] TEETH = { 4, 8, 12 };
    private static final String TEETH_TEXT = "4, 8 and 12";
    private static final int TOOTH_B = 1;
    private static final int TOOTH_C = 2;
    private static final int TOOTH_D = 3;
    /** Crane 2's home point, on the main run between aisles C and D. */
    private static final int HOME_X = 9;

    /**
     * The speed both docks are turned at: slow enough that the moments last several ticks each, and the same for both,
     * because the nearest crane is measured at each crane's own speed and this scenario's assignments assume equal ones.
     */
    private static final int MOTOR_RPM = 32;

    private static final RackPosition TERMINAL = RackPosition.of(7, 0, Side.LEFT);
    private static final RackPosition HOME = RackPosition.of(HOME_X, 0, Side.LEFT);
    /** The output in aisle D the lever asks, on that aisle's west side. */
    private static final RackPosition OUTPUT = new RackPosition(TOOTH_D, 2, 0, Side.RIGHT);

    private static final Item EMERALD = Items.EMERALD;
    private static final Item IRON = Items.IRON_INGOT;
    private static final Item DIAMOND = Items.DIAMOND;
    private static final Item LAPIS = Items.LAPIS_LAZULI;

    /** Every storage location and what its chest holds before the warehouse reads it. */
    private static final List<Rack> RACKS = List.of(
            // Beside crane 1's own dock: only crane 1 serves it.
            new Rack(RackPosition.of(0, 0, Side.LEFT), EMERALD, 128),
            new Rack(RackPosition.of(3, 0, Side.LEFT), IRON, 320),
            new Rack(RackPosition.of(13, 0, Side.LEFT), DIAMOND, 256),
            // Deep in aisle C, so a trip from here to the output in aisle D carries along the run for a long time.
            new Rack(new RackPosition(TOOTH_C, TOOTH_RAILS, 0, Side.LEFT), LAPIS, 192),
            new Rack(new RackPosition(TOOTH_B, 2, 0, Side.LEFT), Items.COPPER_INGOT, 64),
            new Rack(new RackPosition(TOOTH_C, 3, 0, Side.LEFT), Items.GOLD_INGOT, 64));
    /** The terminal and the output are the warehouse's two output-style members. */
    private static final int OUTPUT_STYLE_MEMBERS = 2;

    /**
     * What every terminal order asks for: one crane trip, so each order is one job and the nearest free crane takes it
     * whole. With two trips of iron the second one would go to crane 2, the only crane still free, before the diamonds
     * near it are even ordered, and the moment could not show which crane is nearer.
     */
    private static final int ORDER = 64;
    /**
     * What the output asks for in act 3: one stack of lapis, because that is all one rising edge of the lever asks an
     * output for ({@code WarehouseOutputBlockEntity#requestAmount}).
     */
    private static final int LAPIS_REQUEST = 64;
    /** What the output asks for in each of acts 4 and 5. */
    private static final int EMERALD_REQUEST = 32;

    private static final String ENGLISH = "en_us";
    private static final String GERMAN = "de_de";
    private static final int RELOAD_TIMEOUT_TICKS = 600;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int CENSUS_MARGIN = 3;
    private static final int SETTLE_TICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    /** Long enough for the configured idle delay of a return home plus the drive (default 200 ticks). */
    private static final int PARKED_TIMEOUT_TICKS = 2400;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int MOMENT_TIMEOUT_TICKS = 2400;
    private static final int DELIVERY_TIMEOUT_TICKS = 4800;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int AMOUNT_TIMEOUT_TICKS = 400;
    private static final int SPEED_TIMEOUT_TICKS = 200;
    /** Player height above the scene between camera moves, so a creative player never lands and stops flying. */
    private static final int FLY_HEIGHT = 14;
    private static final double BLOCK_CENTER = 0.5;
    /** How far a machine has to have left its home point to count as on its way to its dock. */
    private static final double LEFT_HOME = 0.3;
    /** How far the client's pose may lag the server's at a frozen moment along an aisle. */
    private static final double CLIENT_POSE_TOLERANCE = 1.5;
    private static final int WAITING_LOG_INTERVAL_POLLS = 100;

    // --- cameras ------------------------------------------------------------------------------------------------------

    /** Straight down over the whole warehouse; the small northward target offset puts north at the top. */
    private static final CameraView TOP = CameraView.of("top", 8.0, 22.0, 1.6, 8.0, 0.0, 1.55);
    /** Down the hall from behind crane 1's dock: both docks, the run between them and the three side aisles. */
    private static final CameraView HALL = CameraView.of("hall", -4.5, 7.0, -5.5, 9.0, 0.5, 1.5);
    /** South of the terminal in the aisle, looking at it: well inside the range a container screen stays open at. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 7.5, 2.5, 2.5, 7.5, 0.6, -0.5);
    /** The controller's tooltip is read from its north face, off its value boxes ({@code CombVisualScenario}). */
    private static final CameraView CONTROLLER_VIEW = CameraView.of("goggles-controller", -1.5, 1.8, -2.6, -0.78,
            0.21, -0.02);
    /**
     * A dock's tooltip is read from the south of the dock and above, on the south strip of its rail bed's top face, clear
     * of the mast-height value box in the middle of that face ({@code CornerVisualScenario#dockView}, mirrored: the north
     * side of crane 1's dock carries the emerald rack).
     */
    private static final double DOCK_EYE_HEIGHT = 3.2;
    private static final double DOCK_EYE_SOUTH = 3.2;
    private static final double DOCK_TOP_FACE = StackerCraneBlock.BED_HEIGHT_PIXELS / 16.0;
    private static final double DOCK_TARGET_Z = 0.88;
    /** Over the two machines, north of them and high, so both and the rails between them are in the frame. */
    private static final double PAIR_ABOVE = 8.5;
    private static final double PAIR_NORTH = 6.5;
    private static final double PAIR_MIN_SPAN = 4.0;

    // --- run state ----------------------------------------------------------------------------------------------------

    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Every item of the scene; nothing enters or leaves it, so this never changes after the baseline. */
    private volatile Map<ItemKey, Long> conserved = Map.of();
    /** What the terminal's buffer must hold of each item once every order so far is delivered. */
    private final Map<Item, Long> atTerminal = new HashMap<>();
    /** What the output's buffer must hold of each item once every request so far is delivered. */
    private final Map<Item, Long> atOutput = new HashMap<>();
    private volatile String moment = "none";
    private int waitingPolls;
    /** Both machines' poses at the frozen moment, read on the server; the shot cameras and the client check use them. */
    private volatile CranePose oneAtMoment = StackerCraneBlockEntity.HOME_POSE;
    private volatile CranePose twoAtMoment = StackerCraneBlockEntity.HOME_POSE;
    /** The crane whose dock the goggle shot of the frozen moment looks at, and the line its goggles must show. */
    private volatile int goggleCrane = 1;
    private volatile CraneTrafficInfo.Line goggleLine = CraneTrafficInfo.Line.NONE;
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    /** A storage location and what its chest holds before the warehouse reads it. */
    private record Rack(RackPosition position, Item item, int amount) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("cranes: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "cranes run"))
                .server("cranes: clear the hall and place both creative motors", this::placeMotors)
                .server("cranes: build both docks, the comb, the stations and the racks", this::buildScene)
                .serverUntil("cranes: wait until the warehouse has both cranes and every member", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("cranes: check the warehouse against what the shots claim", this::assertWarehouse)
                .server("cranes: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("cranes: the player flies and wears Engineer's Goggles", this::equipPlayer)
                .server("cranes: run both creative motors at " + MOTOR_RPM + " RPM", this::powerOn)
                .serverUntil("cranes: wait until crane 1 is on its dock and crane 2 on its home point", this::parked,
                        PARKED_TIMEOUT_TICKS)
                .until("cranes: wait until the client knows crane 2 is a crane of this warehouse",
                        CranesVisualScenario::clientKnowsTheMember, SYNC_TIMEOUT_TICKS)
                .server("cranes: take the item census of the whole scene", this::takeCensus);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so every world shot is
        // taken by a player who reaches nothing; the goggle shots switch the real reach on for themselves.
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        if (pass == VisualPass.FLYWHEEL) {
            script.shotFrom(TOP, "cranes").shotFrom(HALL, "cranes");
            controllerGoggleShots(script);
            bothOrders(script, ORDER, true);
            parallel(script);
            movingAside(script);
            unpowered(script);
            return;
        }
        script.serverUntil("cranes: wait until both cranes are parked again", this::parked, PARKED_TIMEOUT_TICKS);
        script.shotFrom(HALL, "cranes");
        bothOrders(script, ORDER, false);
        script.client("cranes: every check passed",
                context -> LOGGER.info(PREFIX + "cranes: ALL CHECKS PASSED in English and German (two cranes on one "
                        + "warehouse: the nearest free crane took each order, both drove at once on disjoint rails, a "
                        + "crane waited for the rails the other held, a crane on its home point in the way moved aside "
                        + "to its dock, a crane without rotation held the other up and released it once it turned "
                        + "again, no two cranes ever covered one block, and every census was exact)"));
    }

    @Override
    public String status(VisualContext context) {
        return "moment=" + moment + " one=" + clientCrane(context, context.origin()).map(CranesVisualScenario::describe)
                .orElse("missing") + " two=" + clientCrane(context, memberDock(context.origin()))
                .map(CranesVisualScenario::describe).orElse("missing") + GoggleShots.describeHover(context);
    }

    // --- building (server thread) ---------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(BlockPos.ZERO))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-2, -1, -3),
                dock.offset(MEMBER_X + 1, CLEAR_HEIGHT, TOOTH_RAILS + 1)).inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(MEMBER_X + CLEAR_MARGIN, CLEAR_HEIGHT, TOOTH_RAILS + CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (BlockPos motor : List.of(dock.below(), memberDock(dock).below()))
            level.setBlockAndUpdate(motor,
                    AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (BlockPos motor : List.of(dock.below(), memberDock(dock).below()))
            motor(level, motor).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, MAIN_AISLE));
        for (int x = 1; x <= MAIN_RAILS; x++)
            level.setBlockAndUpdate(aislePos(dock, RackPosition.FIRST_BRANCH, x),
                    WarehouseRailBlock.along(MAIN_AISLE.getAxis()));
        for (int tooth = 0; tooth < TEETH.length; tooth++) {
            for (int x = 1; x <= TOOTH_RAILS; x++)
                level.setBlockAndUpdate(aislePos(dock, tooth + 1, x), WarehouseRailBlock.along(TOOTH_AISLE.getAxis()));
        }
        // The second dock: no controller behind it, facing back into the rails, so it is crane 2 of this warehouse.
        level.setBlockAndUpdate(memberDock(dock), WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, MAIN_AISLE.getOpposite()));
        level.setBlockAndUpdate(controllerPos(dock), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, MAIN_AISLE));

        level.setBlockAndUpdate(rackPos(dock, TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, outward(TERMINAL).getOpposite()));
        level.setBlockAndUpdate(rackPos(dock, HOME), WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                .setValue(WarehouseHomePointBlock.FACING, outward(HOME).getOpposite()));
        level.setBlockAndUpdate(rackPos(dock, OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward(OUTPUT).getOpposite()));
        // A lever reports its signal to every side, so the output beside it sees a neighbour signal.
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, TOOTH_AISLE)
                .setValue(LeverBlock.POWERED, false));
        for (Rack rack : RACKS)
            placeStorage(level, dock, rack);
    }

    /** A chest with the rack's stock outside the rack wall and a storage interface facing away from its own aisle. */
    private static void placeStorage(ServerLevel level, BlockPos dock, Rack rack) {
        BlockPos pos = rackPos(dock, rack.position());
        Direction outward = outward(rack.position());
        BlockPos chest = pos.relative(outward);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,
                outward.getOpposite()));
        IItemHandler handler = handlerAt(level, chest);
        int left = rack.amount();
        while (left > 0) {
            int stack = Math.min(left, rack.item().getDefaultMaxStackSize());
            ItemStack rest = ItemHandlerHelper.insertItem(handler, new ItemStack(rack.item(), stack), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the chest of " + rack.position() + " refused " + rest);
            left -= stack;
        }
        level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward));
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity one = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        StackerCraneBlockEntity two = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, memberDock(dock));
        if (controller == null || one == null || two == null) {
            logWhileWaiting("controller=" + controller + " one=" + one + " two=" + two);
            return false;
        }
        List<String> missing = new ArrayList<>();
        if (controller.status() != ControllerStatus.READY)
            missing.add("status=" + controller.status());
        if (controller.isMembershipDirty())
            missing.add("membership still dirty");
        if (controller.pendingSnapshotCount() != 0)
            missing.add("pendingSnapshots=" + controller.pendingSnapshotCount());
        if (controller.craneCount() != 2)
            missing.add("cranes=" + controller.craneCount() + " stop=" + one.discoveredNetwork()
                    .map(network -> network.stop() + "@" + network.stopDx() + "/" + network.stopDz()).orElse("none"));
        if (!two.isMember() || !two.isControllerLinked())
            missing.add("crane 2 member=" + two.isMember() + " linked=" + two.isControllerLinked());
        if (controller.storageLocations().size() != RACKS.size())
            missing.add("racks=" + controller.storageLocations().size() + "/" + RACKS.size());
        if (controller.outputStations().size() != OUTPUT_STYLE_MEMBERS)
            missing.add("output-style members=" + controller.outputStations().size() + "/" + OUTPUT_STYLE_MEMBERS);
        if (!two.homePoint().equals(Optional.of(HOME)))
            missing.add("crane 2's home point=" + two.homePoint() + " crane 1's=" + one.homePoint());
        if (missing.isEmpty())
            return true;
        logWhileWaiting(String.join(", ", missing));
        return false;
    }

    /**
     * The claims every shot makes about the build, checked against the warehouse itself: one warehouse of two cranes, the
     * second dock on the last position of aisle A, four lettered aisles, the home point paired with crane 2 and none
     * with crane 1, and every rack the shots name on the aisle this file says it is on.
     */
    private void assertWarehouse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        WarehouseLayout warehouse = controller.warehouse()
                .orElseThrow(() -> new VisualTestException("the controller has no warehouse"));
        if (warehouse.branchCount() != TEETH.length + 1)
            throw new VisualTestException("the warehouse is " + warehouse.branchCount() + " aisles, expected "
                    + (TEETH.length + 1));
        if (warehouse.network().branch(RackPosition.FIRST_BRANCH).length() != MEMBER_X)
            throw new VisualTestException("aisle A is " + warehouse.network().branch(RackPosition.FIRST_BRANCH)
                    .length() + " blocks long, so the second dock does not extend it; expected " + MEMBER_X);
        List<CraneMember> members = controller.craneMembers();
        if (members.size() != 2 || !members.get(1).dock().equals(memberDock(dock))
                || members.get(1).siteBranch() != RackPosition.FIRST_BRANCH || members.get(1).siteX() != MEMBER_X)
            throw new VisualTestException("crane 2 should be the far dock at the last position of aisle A: " + members);
        if (dock(level, dock).homePoint().isPresent())
            throw new VisualTestException("crane 1 should park on its dock, but has the home point "
                    + dock(level, dock).homePoint());
        for (Rack rack : RACKS) {
            if (controller.locationAt(rackPos(dock, rack.position())).filter(record -> record.position()
                    .equals(rack.position())).isEmpty())
                throw new VisualTestException("the rack at " + rackPos(dock, rack.position()) + " is not the storage "
                        + "location " + rack.position());
        }
        LOGGER.info(PREFIX + "cranes: CHECK one warehouse of {} aisles with cranes {}, aisle A {} blocks, crane 2 waits "
                + "on {}", warehouse.branchCount(), members, MEMBER_X, HOME);
    }

    private void takeCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box " + censusBox + " is not fully loaded");
        conserved = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "cranes: baseline census {} in {}", SceneItemCensus.describe(conserved), censusBox);
    }

    private void census(MinecraftServer server, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, conserved, "cranes: " + step);
    }

    private void powerOn(MinecraftServer server, VisualContext context) {
        setMotor(server, context.origin().below(), MOTOR_RPM);
        setMotor(server, memberDock(context.origin()).below(), MOTOR_RPM);
    }

    private static void setMotor(MinecraftServer server, BlockPos pos, int rpm) {
        motor(server.overworld(), pos).generatedSpeed.setValue(rpm);
    }

    // --- act 1: the controller's crane list ---------------------------------------------------------------------------

    /** "Cranes: 2 (2 idle)" over one line per crane, in English and in German, while both are parked. */
    private void controllerGoggleShots(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, CONTROLLER_VIEW, "goggles-controller", CranesVisualScenario::controllerPos,
                CranesVisualScenario::rosterSynced, CranesVisualScenario::checkControllerGoggles);
        language.switchTo(script, "cranes: ", GERMAN);
        fly(script);
        GoggleShots.shot(script, NAME, CONTROLLER_VIEW, "goggles-controller-de", CranesVisualScenario::controllerPos,
                CranesVisualScenario::rosterSynced, CranesVisualScenario::checkControllerGoggles);
        language.switchTo(script, "cranes: ", ENGLISH);
        GoggleShots.reach(script, NAME, 0.0);
    }

    private static boolean rosterSynced(VisualContext context) {
        return controllerSummary(context).flatMap(ControllerGoggleSummary::cranes)
                .filter(roster -> roster.counts().total() == 2).isPresent();
    }

    private static void checkControllerGoggles(VisualContext context) {
        CraneRoster roster = controllerSummary(context).flatMap(ControllerGoggleSummary::cranes)
                .orElseThrow(() -> new VisualTestException("the controller's summary carries no crane list"));
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        String head = CraneTrafficLang.cranesHead(roster.counts()).component().getString();
        GoggleShots.requireLine(lines, head);
        if (roster.entries().size() != 2)
            throw new VisualTestException("the crane list should name both cranes: " + roster);
        LOGGER.info(PREFIX + "cranes: CHECK the controller's goggles read '{}' in {} ({} lines)", head,
                context.minecraft().getLanguageManager().getSelected(), lines.size());
    }

    // --- act 2: two orders at once --------------------------------------------------------------------------------------

    /**
     * Iron and diamonds ordered at the one terminal with real clicks. The nearest free crane takes each: iron lies three
     * blocks from crane 1's dock and six from crane 2's home point, the diamonds four from that home point. The first
     * tick in which both drive is frozen and shot, and — on the Flywheel pass — the first tick in which one of them has to
     * wait for the rails the other holds, with that crane's goggles in both languages.
     */
    private void bothOrders(VisualScript script, int amount, boolean withWaiting) {
        script.server("cranes: census before the two orders", (server, context) -> census(server, "before the orders"));
        fly(script);
        openTerminal(script);
        amount(script, amount);
        order(script, IRON, amount);
        order(script, DIAMOND, amount);
        closeTerminal(script);
        moment(script, "both-moving", this::bothMoving);
        frozenShots(script, "cranes-both-moving", HALL);
        script.freeze(false);
        if (withWaiting) {
            moment(script, "waiting", this::waitingForRails);
            frozenShots(script, "cranes-waiting", null);
            dockGoggleShots(script, "goggles-waiting");
            script.freeze(false);
        }
        script.serverUntil("cranes: wait until both orders are in the terminal and both cranes idle",
                        this::everythingDelivered, DELIVERY_TIMEOUT_TICKS)
                .server("cranes: census after the two orders", (server, context) -> census(server, "after the orders"));
    }

    /** Both machines drive in this tick: a travel phase each, and neither standing at its target yet. */
    private boolean bothMoving(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity one = dock(level, dock);
        StackerCraneBlockEntity two = dock(level, memberDock(dock));
        return driving(one) && driving(two);
    }

    private static boolean driving(StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePhase phase = state.phase();
        return (phase == CranePhase.TRAVEL_TO_SOURCE || phase == CranePhase.TRAVEL_TO_TARGET) && !crane.isTrafficHeld()
                && !CraneMotion.isAt(state.pose(), state.target());
    }

    /** A crane with a job holds in this tick because the other crane holds a rail of its way. */
    private boolean waitingForRails(ServerLevel level, BlockPos dock) {
        for (int number = 1; number <= 2; number++) {
            StackerCraneBlockEntity crane = number == 1 ? dock(level, dock) : dock(level, memberDock(dock));
            // The ledger may answer a route asked for ahead during the pick with a hold while the crane still picks;
            // only a crane that really stands still for the rails says so on its goggles.
            if (crane.currentJob().isEmpty() || !crane.isTrafficHeld())
                continue;
            if (verdict(level, dock, number) instanceof TrafficVerdict.Hold hold
                    && hold.waiting().reason() == TrafficWait.Reason.PATH_HELD && hold.waiting().otherCrane() != number) {
                goggleCrane = number;
                goggleLine = CraneTrafficInfo.Line.PATH_HELD;
                return true;
            }
        }
        return false;
    }

    // --- act 3: a retrieval and an order at once ------------------------------------------------------------------------

    /**
     * The lever asks the output in aisle D for lapis, which lies deep in aisle C — crane 2 is the nearer crane, and its
     * trip carries over the run from one side aisle into the other, which is long — and the terminal is asked for iron,
     * which crane 1 fetches meanwhile on its own end of the run. The first tick in which both carry is frozen and shot.
     */
    private void parallel(VisualScript script) {
        script.serverUntil("cranes: wait until both cranes are parked", this::parked, PARKED_TIMEOUT_TICKS)
                .server("cranes: census before the retrieval and the order", (server, context) -> census(server,
                        "before the parallel act"))
                .server("cranes: the output asks for lapis", (server, context) -> {
                    presetOutput(server, context, LAPIS, LAPIS_REQUEST);
                    atOutput.merge(LAPIS, (long) LAPIS_REQUEST, Long::sum);
                    setLever(server, context, true);
                });
        fly(script);
        openTerminal(script);
        amount(script, ORDER);
        order(script, IRON, ORDER);
        closeTerminal(script);
        moment(script, "parallel", this::bothCarry);
        frozenShots(script, "cranes-parallel", TOP);
        script.freeze(false)
                .server("cranes: the lever goes back off", (server, context) -> setLever(server, context, false))
                .serverUntil("cranes: wait until the lapis and the iron are delivered", this::everythingDelivered,
                        DELIVERY_TIMEOUT_TICKS)
                .server("cranes: census after the parallel act", (server, context) -> census(server,
                        "after the parallel act"));
    }

    private boolean bothCarry(ServerLevel level, BlockPos dock) {
        return !dock(level, dock).heldItems().isEmpty() && !dock(level, memberDock(dock)).heldItems().isEmpty();
    }

    // --- act 4: moving aside ------------------------------------------------------------------------------------------

    /**
     * Crane 2 rests on its home point. The output asks for emeralds, which lie beside crane 1's own dock and which only
     * crane 1 therefore fetches; its way into aisle D runs over the home point, so crane 2 is ordered aside — and since
     * the home point is in the way itself, to its dock. Frozen while it drives there.
     */
    private void movingAside(VisualScript script) {
        script.serverUntil("cranes: wait until both cranes are parked", this::parked, PARKED_TIMEOUT_TICKS)
                .server("cranes: census before the move aside", (server, context) -> census(server,
                        "before the move aside"))
                .server("cranes: the output asks for emeralds", this::askForEmeralds);
        moment(script, "moving-aside", this::movingAsideToItsDock);
        frozenShots(script, "cranes-moving-aside", HALL);
        dockGoggleShots(script, "goggles-moving-aside");
        script.freeze(false)
                .server("cranes: the lever goes back off", (server, context) -> setLever(server, context, false))
                .serverUntil("cranes: wait until the emeralds are in the output", this::everythingDelivered,
                        DELIVERY_TIMEOUT_TICKS)
                .server("cranes: census after the move aside", (server, context) -> census(server,
                        "after the move aside"));
    }

    private void askForEmeralds(MinecraftServer server, VisualContext context) {
        presetOutput(server, context, EMERALD, EMERALD_REQUEST);
        atOutput.merge(EMERALD, (long) EMERALD_REQUEST, Long::sum);
        setLever(server, context, true);
    }

    /** Crane 2 has left its home point under a move-aside order for crane 1, on its way to its dock. */
    private boolean movingAsideToItsDock(ServerLevel level, BlockPos dock) {
        if (!(verdict(level, dock, 2) instanceof TrafficVerdict.Yield yield) || yield.forCrane() != 1)
            return false;
        CranePose pose = dock(level, memberDock(dock)).craneState().pose();
        if (pose.branch() != RackPosition.FIRST_BRANCH || pose.x() < HOME_X + LEFT_HOME || pose.x() >= MEMBER_X)
            return false;
        goggleCrane = 2;
        goggleLine = CraneTrafficInfo.Line.MOVING_ASIDE;
        return true;
    }

    // --- act 5: a crane without rotation ------------------------------------------------------------------------------

    /**
     * Crane 2's motor stops while it rests on its home point, and the output asks for emeralds again: crane 1 has to pass
     * it and waits with "Waiting for crane 2: it is stopped". Then the motor turns again and everything completes.
     */
    private void unpowered(VisualScript script) {
        script.serverUntil("cranes: wait until both cranes are parked", this::parked, PARKED_TIMEOUT_TICKS)
                .server("cranes: census before crane 2 loses its rotation", (server, context) -> census(server,
                        "before the stopped crane"))
                .server("cranes: stop crane 2's motor", (server, context) -> setMotor(server,
                        memberDock(context.origin()).below(), 0))
                .serverUntil("cranes: wait until crane 2 has no rotation", (server, context) -> dock(server.overworld(),
                        memberDock(context.origin())).currentSpeeds().isStopped(), SPEED_TIMEOUT_TICKS)
                .server("cranes: the output asks for emeralds again", this::askForEmeralds);
        moment(script, "unpowered", this::waitingForTheStoppedCrane);
        frozenShots(script, "cranes-unpowered", null);
        dockGoggleShots(script, "goggles-unpowered");
        script.freeze(false)
                .server("cranes: the lever goes back off", (server, context) -> setLever(server, context, false))
                .server("cranes: crane 2's motor turns again", (server, context) -> setMotor(server,
                        memberDock(context.origin()).below(), MOTOR_RPM))
                .serverUntil("cranes: wait until the emeralds are in the output", this::everythingDelivered,
                        DELIVERY_TIMEOUT_TICKS)
                .server("cranes: census after the stopped crane turned again", (server, context) -> census(server,
                        "after the stopped crane turned again"));
    }

    private boolean waitingForTheStoppedCrane(ServerLevel level, BlockPos dock) {
        // As at the 'waiting' moment: a hold while the crane still picks is not yet a crane standing still.
        if (dock(level, dock).currentJob().isEmpty() || !dock(level, dock).isTrafficHeld())
            return false;
        if (!(verdict(level, dock, 1) instanceof TrafficVerdict.Hold hold)
                || hold.waiting().reason() != TrafficWait.Reason.CRANE_STOPPED || hold.waiting().otherCrane() != 2)
            return false;
        goggleCrane = 1;
        goggleLine = CraneTrafficInfo.Line.CRANE_STOPPED;
        return true;
    }

    // --- frozen moments -----------------------------------------------------------------------------------------------

    /** A test of a frozen moment, on the server thread. */
    @FunctionalInterface
    private interface CraneMoment {
        boolean holds(ServerLevel level, BlockPos dock);
    }

    /**
     * One frozen moment: the server waits until both machines are in this state and freezes the ticks in the same server
     * tick (submitted tasks run between two ticks, so nothing moves in between), the client is given time to see the
     * freeze, and both poses are checked on both sides. The caller shoots and unfreezes.
     */
    private void moment(VisualScript script, String label, CraneMoment test) {
        script.server("cranes: the run is looking for the moment '" + label + "'", (server, context) -> {
                    moment = label;
                    waitingPolls = 0;
                })
                .serverUntil("cranes: wait for the moment '" + label + "'",
                        (server, context) -> freezeAtMoment(server, context, test), MOMENT_TIMEOUT_TICKS)
                .until("cranes: wait until the client sees the frozen moment '" + label + "'",
                        CranesVisualScenario::clientFrozen, SYNC_TIMEOUT_TICKS)
                .server("cranes: check both machines at '" + label + "'",
                        (server, context) -> assertServerMoment(server, context, label, test))
                .client("cranes: check the poses the client draws at '" + label + "'",
                        context -> assertClientMoment(context, label));
    }

    private boolean freezeAtMoment(MinecraftServer server, VisualContext context, CraneMoment test) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        assertApart(level, dock, moment);
        if (!test.holds(level, dock)) {
            if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS == 0)
                LOGGER.info(PREFIX + "cranes: still waiting for '{}': one {} {} | two {} {}", moment,
                        describe(dock(level, dock)), verdict(level, dock, 1), describe(dock(level, memberDock(dock))),
                        verdict(level, dock, 2));
            return false;
        }
        server.tickRateManager().setFrozen(true);
        return true;
    }

    private void assertServerMoment(MinecraftServer server, VisualContext context, String label, CraneMoment test) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!test.holds(level, dock))
            throw new VisualTestException("the machines left the moment '" + label + "' before the world was frozen: "
                    + describe(dock(level, dock)) + " / " + describe(dock(level, memberDock(dock))));
        assertApart(level, dock, label);
        assertAssignment(level, dock, label);
        oneAtMoment = dock(level, dock).craneState().pose();
        twoAtMoment = dock(level, memberDock(dock)).craneState().pose();
        census(server, "at the moment '" + label + "'");
        LOGGER.info(PREFIX + "cranes: CHECK moment '{}' on the server: one {} {} | two {} {}", label,
                describe(dock(level, dock)), verdict(level, dock, 1), describe(dock(level, memberDock(dock))),
                verdict(level, dock, 2));
    }

    /**
     * The nearest free crane took each order (the owner's rule, ADR-075 §8): while both drive, crane 1 fetches the iron
     * that lies near its dock and crane 2 the diamonds near its home point; while both carry, crane 2 has the lapis from
     * aisle C and crane 1 the iron.
     */
    private void assertAssignment(ServerLevel level, BlockPos dock, String label) {
        StackerCraneBlockEntity one = dock(level, dock);
        StackerCraneBlockEntity two = dock(level, memberDock(dock));
        Item oneWants;
        Item twoWants;
        boolean held;
        switch (label) {
            case "both-moving" -> {
                oneWants = IRON;
                twoWants = DIAMOND;
                held = false;
            }
            case "parallel" -> {
                oneWants = IRON;
                twoWants = LAPIS;
                held = true;
            }
            default -> {
                return;
            }
        }
        boolean oneRight = held ? one.heldItems().count(ItemKey.of(oneWants)) > 0
                : one.currentJob().filter(job -> job.key().equals(ItemKey.of(oneWants))).isPresent();
        boolean twoRight = held ? two.heldItems().count(ItemKey.of(twoWants)) > 0
                : two.currentJob().filter(job -> job.key().equals(ItemKey.of(twoWants))).isPresent();
        if (!oneRight || !twoRight)
            throw new VisualTestException("at '" + label + "' crane 1 should " + (held ? "carry " : "fetch ")
                    + oneWants + " and crane 2 " + twoWants + ", but crane 1 has " + jobOf(one) + " / "
                    + one.heldItems() + " and crane 2 " + jobOf(two) + " / " + two.heldItems());
        LOGGER.info(PREFIX + "cranes: CHECK nearest free crane at '{}': crane 1 {}, crane 2 {}", label, oneWants,
                twoWants);
    }

    private static String jobOf(StackerCraneBlockEntity crane) {
        return crane.currentJob().map(job -> job.type() + " " + job.key() + " x" + job.plannedAmount())
                .orElse("no job");
    }

    /** The client draws both machines on the aisles they stand on, within a tick or two of where they stand. */
    private void assertClientMoment(VisualContext context, String label) {
        checkClientPose(context, context.origin(), oneAtMoment, label, 1);
        checkClientPose(context, memberDock(context.origin()), twoAtMoment, label, 2);
    }

    private static void checkClientPose(VisualContext context, BlockPos dockPos, CranePose onTheServer, String label,
            int number) {
        StackerCraneBlockEntity crane = clientCrane(context, dockPos)
                .orElseThrow(() -> new VisualTestException("the client has no crane " + number + " to photograph"));
        CranePose pose = crane.craneState().pose();
        if (pose.branch() != onTheServer.branch() || Math.abs(pose.x() - onTheServer.x()) > CLIENT_POSE_TOLERANCE)
            throw new VisualTestException("the client draws crane " + number + " at " + pose.branch() + "/" + pose.x()
                    + " while it stands at " + onTheServer.branch() + "/" + onTheServer.x() + " at '" + label + "'");
    }

    /** The shots of a frozen moment: over both machines, then from {@code second} if one is given. */
    private void frozenShots(VisualScript script, String label, CameraView second) {
        fly(script);
        script.camera("pair", context -> pairView(oneAtMoment, twoAtMoment)).shot(label);
        if (second != null)
            script.camera(second).shot(label + "-" + second.label());
    }

    /**
     * The goggle tooltip of the dock whose crane the moment is about, in English and German: "Crane N of 2" and the
     * traffic line, built from the very record the client draws them from. Taken while the ticks are frozen; the language
     * switch rebuilds the client's resources and leaves the world as it is.
     */
    private void dockGoggleShots(VisualScript script, String label) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, label, context -> dockView(label, goggleDockX()), label, this::goggleDock,
                this::trafficSynced, this::checkDockGoggles);
        language.switchTo(script, "cranes: ", GERMAN);
        fly(script);
        GoggleShots.shot(script, NAME, label + "-de", context -> dockView(label + "-de", goggleDockX()),
                label + "-de", this::goggleDock, this::trafficSynced, this::checkDockGoggles);
        language.switchTo(script, "cranes: ", ENGLISH);
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** X of the dock the goggle shot looks at, relative to the origin, once the moment has said which crane. */
    private int goggleDockX() {
        return goggleCrane == 1 ? 0 : MEMBER_X;
    }

    private BlockPos goggleDock(BlockPos origin) {
        return goggleCrane == 1 ? origin : memberDock(origin);
    }

    private boolean trafficSynced(VisualContext context) {
        return clientCrane(context, goggleDock(context.origin())).flatMap(crane -> crane.goggleInfo().traffic())
                .filter(info -> info.line() == goggleLine).isPresent();
    }

    private void checkDockGoggles(VisualContext context) {
        BlockPos pos = goggleDock(context.origin());
        StackerCraneBlockEntity crane = clientCrane(context, pos)
                .orElseThrow(() -> new VisualTestException("no client crane at " + pos));
        CraneGoggleInfo info = crane.goggleInfo();
        CraneTrafficInfo traffic = info.traffic()
                .orElseThrow(() -> new VisualTestException("crane " + goggleCrane + " shows nothing about traffic"));
        if (traffic.number() != goggleCrane || traffic.craneCount() != 2)
            throw new VisualTestException("the dock of crane " + goggleCrane + " calls itself crane " + traffic.number()
                    + " of " + traffic.craneCount());
        if (traffic.line() != goggleLine || traffic.otherCrane() != 3 - goggleCrane)
            throw new VisualTestException("crane " + goggleCrane + " should say " + goggleLine + " naming crane "
                    + (3 - goggleCrane) + ", but says " + traffic.line() + " naming " + traffic.otherCrane());
        List<String> lines = GoggleShots.lines(context, pos);
        GoggleShots.requireLine(lines, CraneTrafficLang.craneNumber(traffic.number(), traffic.craneCount())
                .component().getString());
        String line = CraneTrafficLang.trafficLine(traffic, info.railAddress(traffic.heldBranch(), traffic.heldX()))
                .orElseThrow(() -> new VisualTestException("no traffic line for " + traffic)).component().getString();
        GoggleShots.requireLine(lines, line);
        LOGGER.info(PREFIX + "cranes: CHECK the goggles of crane {} read '{}' in {}", goggleCrane, line,
                context.minecraft().getLanguageManager().getSelected());
    }

    // --- terminal clicks (client thread) ------------------------------------------------------------------------------

    private void openTerminal(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("cranes: open the terminal screen", CranesVisualScenario::openTerminalScreen)
                .until("cranes: wait for the screen with the stock of the whole warehouse",
                        CranesVisualScenario::terminalReady, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    private static void openTerminalScreen(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                .getNullable(server.overworld(), rackPos(context.origin(), TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal is missing");
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    private static boolean terminalReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && !terminal.matchingEntries().isEmpty();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open: " + context.minecraft().screen);
    }

    /** The amount field to {@code amount}, one notch of the wheel per tick over the field, the way a player sets it. */
    private static void amount(VisualScript script, int amount) {
        ArmVisualScenario.untilOrFail(script, "cranes: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
    }

    /**
     * One click on the stock cell of {@code item}, checked with the screen's own hit test. Plain stock with no stock rule
     * behind it asks no question ({@code RequestConfirmation}), so the server is asked straight away whether the request
     * is really open, and the moments after it are about a real order.
     */
    private void order(VisualScript script, Item item, int amount) {
        ItemKey key = ItemKey.of(item);
        script.client("cranes: click " + item + " x" + amount + " at the terminal", context -> {
                    ScreenInput.Point cell = cellOf(context, key);
                    ScreenInput.click(context.minecraft(), cell);
                    LOGGER.info(PREFIX + "cranes: clicked {} at {} with the amount field at x{}", key, cell,
                            ScreenInput.terminalAmount(screen(context)).getState());
                })
                .serverUntil("cranes: wait until the controller holds the order for " + item,
                        (server, context) -> requested(server, context, key), SYNC_TIMEOUT_TICKS)
                .server("cranes: expect " + item + " x" + amount + " in the terminal",
                        (server, context) -> atTerminal.merge(item, (long) amount, Long::sum));
    }

    private static ScreenInput.Point cellOf(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).key().equals(key))
                return ScreenInput.terminalCell(screen(context), index);
        }
        throw new VisualTestException("the grid shows no " + key + ": " + visible.size() + " cells");
    }

    private static boolean requested(MinecraftServer server, VisualContext context, ItemKey key) {
        return controller(server.overworld(), context.origin())
                .requestsFor(rackPos(context.origin(), TERMINAL)).stream()
                .anyMatch(request -> request.key().equals(key));
    }

    private static void closeTerminal(VisualScript script) {
        script.client("cranes: close the terminal screen", context -> {
            LocalPlayer player = context.minecraft().player;
            if (player != null)
                player.closeContainer();
        }).until("cranes: wait until the screen is closed", context -> context.minecraft().screen == null,
                SCREEN_TIMEOUT_TICKS);
    }

    // --- waits (server thread) ----------------------------------------------------------------------------------------

    /** Crane 1 idle on its own dock and crane 2 idle on its home point, the start of every act. */
    private boolean parked(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        assertApart(level, dock, "parked");
        StackerCraneBlockEntity one = dock(level, dock);
        StackerCraneBlockEntity two = dock(level, memberDock(dock));
        boolean parked = idle(one) && idle(two) && at(one, RackPosition.FIRST_BRANCH, 0)
                && at(two, RackPosition.FIRST_BRANCH, HOME_X);
        if (!parked && ++waitingPolls % WAITING_LOG_INTERVAL_POLLS == 0)
            LOGGER.info(PREFIX + "cranes: not parked yet: one {} | two {}", describe(one), describe(two));
        return parked;
    }

    /** Every order and request so far is in its buffer, nothing is open and both cranes are idle and empty. */
    private boolean everythingDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        assertApart(level, dock, "delivering");
        WarehouseControllerBlockEntity controller = controller(level, dock);
        boolean done = controller.openRequestCount() == 0 && idle(dock(level, dock))
                && idle(dock(level, memberDock(dock))) && holds(level, rackPos(dock, TERMINAL), atTerminal)
                && holds(level, rackPos(dock, OUTPUT), atOutput);
        if (!done && ++waitingPolls % WAITING_LOG_INTERVAL_POLLS == 0)
            LOGGER.info(PREFIX + "cranes: not delivered yet: open={} one {} | two {}", controller.openRequestCount(),
                    describe(dock(level, dock)), describe(dock(level, memberDock(dock))));
        return done;
    }

    private static boolean holds(ServerLevel level, BlockPos station, Map<Item, Long> expected) {
        if (!(level.getBlockEntity(station) instanceof WarehouseStationBlockEntity buffer))
            throw new VisualTestException("no station at " + station);
        for (Map.Entry<Item, Long> entry : expected.entrySet()) {
            if (buffer.bufferedItems().count(ItemKey.of(entry.getKey())) != entry.getValue())
                return false;
        }
        return true;
    }

    private static boolean idle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static boolean at(StackerCraneBlockEntity crane, int branch, double x) {
        CranePose pose = crane.craneState().pose();
        return pose.branch() == branch && Math.abs(pose.x() - x) < 1.0e-6 && pose.arm() == CranePose.RETRACTED;
    }

    /**
     * No two cranes cover one block (ADR-075 I1/I2, as the world sees it): the blocks under {@code floor(x)} and
     * {@code ceil(x)} of each machine's pose, each in its own crane's warehouse frame. Checked on every server poll.
     */
    private static void assertApart(ServerLevel level, BlockPos dock, String when) {
        Set<BlockPos> one = standingBlocks(dock(level, dock));
        Set<BlockPos> two = standingBlocks(dock(level, memberDock(dock)));
        if (!Collections.disjoint(one, two))
            throw new VisualTestException("two cranes share a block " + when + ": " + one + " and " + two);
    }

    private static Set<BlockPos> standingBlocks(StackerCraneBlockEntity crane) {
        WarehouseLayout frame = crane.warehouse();
        CranePose pose = crane.craneState().pose();
        Set<BlockPos> blocks = new HashSet<>();
        for (double x : new double[] {Math.floor(pose.x()), Math.ceil(pose.x())}) {
            Vec3 offset = frame.railOffset(pose.branch(), x);
            blocks.add(frame.dock().offset((int) Math.round(offset.x), 0, (int) Math.round(offset.z)));
        }
        return blocks;
    }

    private static TrafficVerdict verdict(ServerLevel level, BlockPos dock, int number) {
        return controller(level, dock).traffic().map(traffic -> traffic.verdict(number))
                .orElse(TrafficVerdict.CLEAR);
    }

    // --- the output and its lever -------------------------------------------------------------------------------------

    /** The output's request: {@code amount} of {@code item}, fetched on the lever's next rising edge. */
    private static void presetOutput(MinecraftServer server, VisualContext context, Item item, int amount) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(server.overworld(), rackPos(context.origin(), OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output in aisle D is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(item)))
            throw new VisualTestException("the warehouse output refused the filter " + item);
        filter.count = amount; // after setFilter, which may clamp the count
    }

    /** {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the output see the rising edge. */
    private static void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(context.origin());
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    // --- the player ---------------------------------------------------------------------------------------------------

    private void fly(VisualScript script) {
        script.server("cranes: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("cranes: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    private void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(FLY_HEIGHT);
        player.teleportTo(server.overworld(), above.getX() + BLOCK_CENTER, above.getY(), above.getZ() + BLOCK_CENTER,
                player.getYRot(), player.getXRot());
    }

    /** Flying, wearing Engineer's Goggles and holding nothing, so no item covers a corner of a shot with the GUI. */
    private void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- cameras ------------------------------------------------------------------------------------------------------

    /** Over the two machines: north of the middle between them, high enough to see both and the rails between. */
    private static CameraView pairView(CranePose one, CranePose two) {
        double x1 = offsetOf(one.branch(), one.x(), Direction.Axis.X);
        double z1 = offsetOf(one.branch(), one.x(), Direction.Axis.Z);
        double x2 = offsetOf(two.branch(), two.x(), Direction.Axis.X);
        double z2 = offsetOf(two.branch(), two.x(), Direction.Axis.Z);
        double midX = (x1 + x2) / 2.0 + BLOCK_CENTER;
        double midZ = (z1 + z2) / 2.0 + BLOCK_CENTER;
        double span = Math.max(PAIR_MIN_SPAN, Math.abs(x1 - x2) + Math.abs(z1 - z2));
        double lift = Math.max(PAIR_ABOVE, span * 0.6);
        return CameraView.of("pair", midX, lift, midZ - Math.max(PAIR_NORTH, span * 0.5), midX, 0.5, midZ + 0.5);
    }

    /** South of the dock {@code dx} blocks east of the origin and above, looking down on the south strip of its bed. */
    private static CameraView dockView(String label, int dx) {
        return CameraView.of(label, dx + BLOCK_CENTER, DOCK_EYE_HEIGHT, DOCK_EYE_SOUTH, dx + BLOCK_CENTER,
                DOCK_TOP_FACE, DOCK_TARGET_Z);
    }

    // --- geometry -----------------------------------------------------------------------------------------------------

    private static Direction headingOf(int branch) {
        return branch == RackPosition.FIRST_BRANCH ? MAIN_AISLE : TOOTH_AISLE;
    }

    /** The direction a member at this position faces away from its own rails: outwards, across the aisle. */
    private static Direction outward(RackPosition rack) {
        Direction heading = headingOf(rack.branch());
        return rack.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
    }

    private static double offsetOf(int branch, double x, Direction.Axis axis) {
        int main = axis == Direction.Axis.X ? MAIN_AISLE.getStepX() : MAIN_AISLE.getStepZ();
        int tooth = axis == Direction.Axis.X ? TOOTH_AISLE.getStepX() : TOOTH_AISLE.getStepZ();
        return branch == RackPosition.FIRST_BRANCH ? main * x : main * TEETH[branch - 1] + tooth * x;
    }

    private static BlockPos aislePos(BlockPos dock, int branch, int x) {
        return branch == RackPosition.FIRST_BRANCH ? dock.relative(MAIN_AISLE, x)
                : dock.relative(MAIN_AISLE, TEETH[branch - 1]).relative(TOOTH_AISLE, x);
    }

    private static BlockPos rackPos(BlockPos dock, RackPosition rack) {
        return aislePos(dock, rack.branch(), rack.x()).relative(outward(rack)).above(rack.y());
    }

    private static BlockPos memberDock(BlockPos dock) {
        return dock.relative(MAIN_AISLE, MEMBER_X);
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(MAIN_AISLE.getOpposite());
    }

    /** The lever on the floor beside the output, on the side away from its aisle. */
    private static BlockPos leverPos(BlockPos dock) {
        return rackPos(dock, OUTPUT).relative(outward(OUTPUT));
    }

    // --- reading --------------------------------------------------------------------------------------------------------

    private static StackerCraneBlockEntity dock(ServerLevel level, BlockPos pos) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, pos);
        if (crane == null)
            throw new VisualTestException("the stacker crane dock at " + pos + " is missing");
        return crane;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static Optional<ControllerGoggleSummary> controllerSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(controllerPos(context.origin())) instanceof WarehouseControllerBlockEntity controller
                ? Optional.of(controller.summary()) : Optional.empty();
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context, BlockPos pos) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(pos) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    /** The client draws crane 2 in the warehouse's frame, which needs the frame its packets carry. */
    private static boolean clientKnowsTheMember(VisualContext context) {
        return clientCrane(context, memberDock(context.origin())).filter(StackerCraneBlockEntity::isMember)
                .isPresent();
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }

    private void logWhileWaiting(String what) {
        if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS != 0)
            return;
        LOGGER.info(PREFIX + "cranes: the scene is not ready yet: {}", what);
    }

    private static String describe(StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        return String.format(Locale.ROOT, "crane %d aisle=%d x=%.2f arm=%.2f phase=%s held=%d target=%d/%.2f%s",
                crane.craneNumber(), pose.branch(), pose.x(), pose.arm(), state.phase(),
                crane.heldItems().totalCount(), state.target().branch(), state.target().x(),
                crane.isTrafficHeld() ? " held" : "");
    }
}
