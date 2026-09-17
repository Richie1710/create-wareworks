package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.DeliveryStationArmPoint;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseInputArmPoint;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.dev.ArmVisualScenario.Click;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.math.Pointing;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Scenario "arm-dedicated": Create mechanical arms at all four warehouse stations on a <b>dedicated server</b> (M12, the
 * dedicated-server half of manual check 92 in {@code docs/manual-test-checklist.md} section P). Where the "arm" scenario
 * runs on the integrated server, whose local connection skips packet encoding, this one joins a real server over TCP,
 * so the synced arm interaction point type registry, the {@code ArmPlacementPacket}, the value settings packets and the
 * Wareworks screen payloads are encoded, sent and decoded, and the arms have to survive a real server restart.
 * <p>
 * <b>Setup outside the client.</b> A dedicated server ({@code runServer}) with a fresh flat throw-away world must be
 * running, and the dev player {@code Dev} must be an operator there (the scene is built with commands, and the scenario
 * stops the server with {@code /stop}). The address comes from the system property {@value #SERVER_PROPERTY}
 * (default {@value #DEFAULT_SERVER}). After the first {@code /stop} the client probes the server's port until whoever
 * runs the test has started the same server again, and only then joins; after the second session it stops the server
 * once more, so both server runs end cleanly.
 * <p>
 * <b>Everything the client does is what a player can do.</b> Blocks are placed with {@code /setblock} and {@code /fill},
 * items handed out and removed with {@code /item} and {@code /clear}. Hotbar slots are chosen with the hotbar keys,
 * targets are selected and arms placed with real right-clicks ({@link KeyMapping#click}, as in the "arm" scenario), items
 * are put on a depot and taken off it by right-clicking it, the output's request filter is set by clicking its slot with
 * an item (Create's value settings packet), and a request is triggered by placing a redstone block next to the output.
 * Screens are opened by right-clicking the station with an empty hand and used with mouse and key input through
 * {@link ScreenInput}: the production pattern "1 oak log → 4 oak planks" is written by picking the items up from the
 * inventory and clicking the pattern cells ({@code ProductionPatternPayload}), and planks are ordered by scrolling the
 * terminal's amount field and clicking the planks in its stock grid ({@code TerminalRequestPayload}). The camera moves
 * with {@code /tp}.
 * <p>
 * <b>What is read back.</b> On the client: the arm item's selection, the outline colours and the action bar (as in the
 * "arm" scenario), the interaction points each client arm resolved from its synced block entity data, and what the
 * screens show (the pattern the server echoed, the production orders the server pushes to the terminal). On the server:
 * the block entity data answered by {@code /data get block} in chat, parsed with the vanilla SNBT parser. An arm's saved
 * {@code InteractionPoints} list names the type id and mode of every point; before the server arm has resolved the
 * points of its placement packet it echoes the packet's list in selection order, afterwards it writes its inputs
 * ("take") before its outputs ("deposit"). Arm A selects its deposit target first, so its list only turns into
 * "depot, then input" once the server has resolved both points through the registry.
 * <p>
 * Items are counted in the inventories the chain runs through (depots, arm claws, station buffers, crafter, chests)
 * whenever the machines are done, so nothing may be lost or duplicated between two checks.
 */
public final class ArmDedicatedServerScenario implements VisualScenario {
    public static final String NAME = "arm-dedicated";
    /** System property with the {@code host:port} of the dedicated server to join. */
    public static final String SERVER_PROPERTY = "wareworks.visualTest.server";
    private static final String DEFAULT_SERVER = "localhost:25565";

    /** Two sessions, a server restart that someone else performs, and two passes. */
    private static final long RUN_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    // --- scene: world position of the dock, offsets as in the "arm" scenario (the aisle runs east, right side is +z) --
    private static final BlockPos ORIGIN = new BlockPos(8, -60, 8);
    private static final int RAILS = 8;
    private static final int STORAGE_FIRST = 6;
    private static final int CRANE_RPM = 128;
    private static final int ARM_RPM = 32;
    private static final int CRAFTER_RPM = 128;

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos INPUT = new BlockPos(1, 0, 1);
    private static final BlockPos OUTPUT = new BlockPos(3, 0, 1);
    private static final BlockPos TERMINAL = new BlockPos(5, 0, 1);
    private static final BlockPos PRODUCTION = new BlockPos(1, 0, -1);
    /** Where the crafter puts the planks. */
    private static final BlockPos INPUT_B = new BlockPos(3, 0, -1);
    /** Next to the output: placing a redstone block here is the rising edge that submits the output's request. */
    private static final BlockPos REDSTONE = new BlockPos(3, 0, 2);
    private static final BlockPos ARM_A = new BlockPos(2, 0, 3);
    private static final BlockPos ARM_B = new BlockPos(4, 0, 3);
    private static final BlockPos ARM_C = new BlockPos(3, 0, 4);
    private static final BlockPos ARM_COG = new BlockPos(3, 0, 3);
    private static final BlockPos DEPOT_A = new BlockPos(1, 0, 4);
    private static final BlockPos DEPOT_B = new BlockPos(5, 0, 4);
    /**
     * Arm C's destination is a basin, not a depot: a depot holds a single stack and an arm puts nothing onto it while
     * it holds one, but the planks can reach the terminal in more than one delivery.
     */
    private static final BlockPos BASIN = new BlockPos(3, 0, 5);
    private static final BlockPos ARM_D = new BlockPos(1, 0, -3);
    private static final BlockPos ARM_D_COG = new BlockPos(0, 0, -3);
    /** Faces east and points south, into the second warehouse input. */
    private static final BlockPos CRAFTER = new BlockPos(3, 0, -2);
    private static final BlockPos CRAFTER_COG = CRAFTER.above();
    private static final BlockPos CRAFTER_MOTOR = CRAFTER_COG.east();
    /** Where the player waits in the air before it starts flying. */
    private static final BlockPos LIFT = new BlockPos(-3, 4, -4);

    private static final BlockPos CLEAR_MIN = new BlockPos(-5, 0, -7);
    private static final BlockPos CLEAR_MAX = new BlockPos(12, 7, 8);

    // --- items -------------------------------------------------------------------------------------------------------
    private static final String IRON = "minecraft:iron_ingot";
    private static final String GOLD = "minecraft:gold_ingot";
    private static final String LOG = "minecraft:oak_log";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final int FED_IRON = 32;
    private static final int FED_GOLD = 16;
    private static final int FED_LOGS = 12;
    private static final int PLANKS_PER_LOG = 4;
    private static final int ORDERED_PLANKS = 8;
    private static final int ORDERED_PLANKS_AFTER_RESTART = 4;

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_ARM = 0;
    private static final int SLOT_FEED = 1;
    private static final int SLOT_FILTER = 2;
    private static final int SLOT_PATTERN_LOG = 3;
    private static final int SLOT_PATTERN_PLANKS = 4;
    /** Never filled for long: selecting it gives the player an empty hand. */
    private static final int SLOT_EMPTY = 8;

    // --- timing ------------------------------------------------------------------------------------------------------
    private static final int FIRST_JOIN_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int REJOIN_TIMEOUT_TICKS = 20 * 60 * 10;
    private static final int DISCONNECT_TIMEOUT_TICKS = 20 * 60;
    private static final int SCENE_TIMEOUT_TICKS = 20 * 60;
    private static final int STORE_TIMEOUT_TICKS = 20 * 60 * 3;
    private static final int PRODUCTION_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int QUERY_TIMEOUT_TICKS = 20 * 30;
    private static final int SCREEN_TIMEOUT_TICKS = 20 * 10;
    private static final int AIM_TIMEOUT_TICKS = 40;
    private static final int CLICK_TIMEOUT_TICKS = 20;
    private static final int OUTLINE_TIMEOUT_TICKS = 10;
    private static final int HELD_ITEM_TIMEOUT_TICKS = 100;
    private static final int CAMERA_MIN_TICKS = 10;
    private static final int CAMERA_SETTLED_TICKS = 5;
    private static final int CAMERA_MAX_TICKS = 300;
    private static final double CAMERA_ARRIVAL_DISTANCE_SQR = 0.01;
    /** Ticks between two rounds of {@code /data get block} while waiting for a state. */
    private static final int POLL_INTERVAL_TICKS = 20;
    /** A round whose answers are not all back after this many ticks is sent again. */
    private static final int ANSWER_TIMEOUT_TICKS = 100;
    private static final int SETTLE_TICKS = 4;
    /**
     * Between two clicks on the same inventory slot: more than the 250 ms in which a container screen turns the second
     * click into a double click ("pick up all").
     */
    private static final int SLOT_CLICK_GAP_TICKS = 10;

    // --- what a player reads -----------------------------------------------------------------------------------------
    private static final String DEPOSIT_TO_INPUT = "Deposit items to Warehouse Input";
    private static final String TAKE_FROM_OUTPUT = "Take items from Warehouse Output";
    private static final String TAKE_FROM_TERMINAL = "Take items from Warehouse Terminal";
    private static final String TAKE_FROM_PRODUCTION = "Take items from Warehouse Production";
    private static final String TAKE_FROM_DEPOT = "Take items from Depot";
    private static final String DEPOSIT_TO_DEPOT = "Deposit items to Depot";
    private static final String DEPOSIT_TO_CRAFTER = "Deposit items to Mechanical Crafter";
    private static final String DEPOSIT_TO_BASIN = "Deposit items to Basin";
    private static final String DATA_ANSWER = "%d, %d, %d has the following block data: ";

    // --- clicks (offsets from the dock) ------------------------------------------------------------------------------
    private static final Vec3 SOUTH_EYE = new Vec3(0.0, 2.0, 1.6);
    private static final Vec3 NORTH_EYE = new Vec3(0.0, 2.0, -1.6);
    private static final Vec3 CENTRE = new Vec3(0.5, 0.5, 0.5);
    private static final Click CLICK_INPUT = new Click("input", INPUT, Direction.UP, new Vec3(0.25, 0.5, 0.75),
            SOUTH_EYE);
    /** Off the centre of the top face, where the request filter slot is. */
    private static final Click CLICK_OUTPUT = new Click("output", OUTPUT, Direction.UP, new Vec3(0.2, 0.5, 0.8),
            SOUTH_EYE);
    private static final Click CLICK_OUTPUT_FILTER_SLOT = new Click("output-filter-slot", OUTPUT, Direction.UP, CENTRE,
            SOUTH_EYE);
    private static final Click CLICK_TERMINAL = new Click("terminal", TERMINAL, Direction.UP, new Vec3(0.25, 0.5, 0.75),
            SOUTH_EYE);
    private static final Click CLICK_PRODUCTION = new Click("production", PRODUCTION, Direction.UP,
            new Vec3(0.25, 0.5, 0.25), NORTH_EYE);
    /** The crafter's top is covered by its cogwheel, so it is clicked on its west face. */
    private static final Click CLICK_CRAFTER = new Click("crafter", CRAFTER, Direction.WEST, new Vec3(0.5, 0.45, 0.5),
            new Vec3(-2.2, 1.3, 0.0));
    private static final Click CLICK_DEPOT_A = new Click("depot-a", DEPOT_A, Direction.UP, CENTRE,
            new Vec3(-1.8, 2.2, 0.0));
    private static final Click CLICK_DEPOT_B = new Click("depot-b", DEPOT_B, Direction.UP, CENTRE,
            new Vec3(1.8, 2.2, 0.0));
    /** Aimed steeply from above: at a flatter angle the crosshair meets the basin's inner wall before its floor. */
    private static final Click CLICK_BASIN = new Click("basin", BASIN, Direction.UP, CENTRE, new Vec3(0.0, 2.4, 0.3));
    private static final Click GROUND_ARM_A = new Click("ground-arm-a", ARM_A.below(), Direction.UP, CENTRE,
            new Vec3(0.0, 2.4, 1.8));
    private static final Click GROUND_ARM_B = new Click("ground-arm-b", ARM_B.below(), Direction.UP, CENTRE,
            new Vec3(0.0, 2.4, 1.8));
    private static final Click GROUND_ARM_C = new Click("ground-arm-c", ARM_C.below(), Direction.UP, CENTRE,
            new Vec3(-1.8, 2.6, 0.0));
    private static final Click GROUND_ARM_D = new Click("ground-arm-d", ARM_D.below(), Direction.UP, CENTRE,
            new Vec3(0.0, 2.4, -1.8));
    private static final CameraView OVERVIEW = CameraView.of("overview", 3.5, 5.5, 7.5, 2.5, 0.0, -0.5);

    /** The points each arm must have, in the order a server arm saves resolved points: inputs, then outputs. */
    private static final List<SavedPoint> ARM_A_POINTS = List.of(
            new SavedPoint("create:depot", Mode.TAKE, DEPOT_A.subtract(ARM_A)),
            new SavedPoint("wareworks:warehouse_input", Mode.DEPOSIT, INPUT.subtract(ARM_A)));
    private static final List<SavedPoint> ARM_B_POINTS = List.of(
            new SavedPoint("wareworks:warehouse_output", Mode.TAKE, OUTPUT.subtract(ARM_B)),
            new SavedPoint("create:depot", Mode.DEPOSIT, DEPOT_B.subtract(ARM_B)));
    private static final List<SavedPoint> ARM_C_POINTS = List.of(
            new SavedPoint("wareworks:warehouse_terminal", Mode.TAKE, TERMINAL.subtract(ARM_C)),
            new SavedPoint("create:basin", Mode.DEPOSIT, BASIN.subtract(ARM_C)));
    private static final List<SavedPoint> ARM_D_POINTS = List.of(
            new SavedPoint("wareworks:warehouse_production", Mode.TAKE, PRODUCTION.subtract(ARM_D)),
            new SavedPoint("create:crafter", Mode.DEPOSIT, CRAFTER.subtract(ARM_D)));

    // --- run state (client thread, except the chat list, which the client thread also fills) ------------------------
    private final List<String> systemMessages = new CopyOnWriteArrayList<>();
    private boolean listening;
    @Nullable
    private Component overlayBeforeClick;
    /** The production orders the terminal screen listed before the planks were clicked. */
    private List<UUID> ordersBeforeClick = List.of();
    @Nullable
    private UUID orderedPlanks;
    private final List<ProductionOrderState> seenStates = new ArrayList<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void prepare(VisualScript script) {
        VisualWorld.prepareRemote(script, serverAddress(), RUN_TIMEOUT_MILLIS, FIRST_JOIN_TIMEOUT_TICKS);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("arm-dedicated: read the server's chat answers; scene origin " + ORIGIN.toShortString(), context -> {
            installChatListener();
            context.setOrigin(ORIGIN);
        });
        playerAndWorld(script);
        buildScene(script);
        selectAndPlaceArms(script);
        writePattern(script);
        feedAndEmpty(script, IRON, FED_IRON, "before the restart");
        feed(script, LOG, FED_LOGS, "before the restart");
        orderPlanks(script, ORDERED_PLANKS, FED_LOGS - ORDERED_PLANKS / PLANKS_PER_LOG, ORDERED_PLANKS,
                "before the restart");
        camera(script, OVERVIEW.label(), context -> OVERVIEW);
        playerShot(script, "first-session-done");
        queryPoints(script, "before the restart");

        script.client("arm-dedicated: stop the dedicated server with /stop (the test runner starts it again)",
                context -> send(context, "stop"));
        VisualWorld.waitUntilDisconnected(script, "arm-dedicated: the server stopped and closed the connection",
                DISCONNECT_TIMEOUT_TICKS);
        script.client("arm-dedicated: waiting for the dedicated server to come back",
                context -> LOGGER.info(PREFIX + "arm-dedicated: the server is down; probing its port until it is up again"));
        VisualWorld.joinServer(script, serverAddress(), REJOIN_TIMEOUT_TICKS);

        afterRestart(script);
        script.client("arm-dedicated: every check passed", context -> LOGGER.info(PREFIX
                + "arm-dedicated: ALL CHECKS PASSED (92 on a dedicated server: selection at all four stations, placement "
                + "packets, saved points, pattern and order screens, items moved through all four arms before and after "
                + "a server restart)"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        camera(script, OVERVIEW.label(), context -> OVERVIEW);
        script.shot("scene-" + OVERVIEW.label());
    }

    /** Stops the server again, so its second run ends as cleanly as the first. */
    @Override
    public void finish(VisualScript script) {
        script.client("arm-dedicated: stop the dedicated server again with /stop", context -> send(context, "stop"));
        VisualWorld.waitUntilDisconnected(script, "arm-dedicated: the server stopped again and closed the connection",
                DISCONNECT_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder("selection=").append(ArmVisualScenario.selectionSummary())
                .append(" actionBar='").append(ArmVisualScenario.overlayText(context)).append('\'');
        ClientLevel level = context.minecraft().level;
        if (level != null) {
            for (Map.Entry<String, BlockPos> arm : Map.of("A", ARM_A, "B", ARM_B, "C", ARM_C, "D", ARM_D).entrySet()) {
                if (level.getBlockEntity(ORIGIN.offset(arm.getValue())) instanceof ArmBlockEntity be)
                    status.append(" arm").append(arm.getKey()).append('=')
                            .append(ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_PHASE, be));
            }
        }
        return status.toString();
    }

    private static String serverAddress() {
        return System.getProperty(SERVER_PROPERTY, DEFAULT_SERVER);
    }

    // --- player and world ----------------------------------------------------------------------------------------

    private void playerAndWorld(VisualScript script) {
        for (String command : List.of("gamemode creative", "difficulty peaceful", "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false", "gamerule doMobSpawning false", "time set noon", "weather clear"))
            command(script, command);
        script.until("arm-dedicated: the client player is in creative mode", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.isCreative();
        }, HELD_ITEM_TIMEOUT_TICKS);
        liftAndFly(script);
    }

    /**
     * A player standing on the ground stops flying at once ({@code LocalPlayer#aiStep}), so the player is teleported into
     * the air first and switches flying on there, like a double jump does.
     */
    private void liftAndFly(VisualScript script) {
        Vec3 lift = Vec3.atBottomCenterOf(ORIGIN.offset(LIFT));
        command(script, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", lift.x, lift.y, lift.z));
        script.until("arm-dedicated: the player is in the air above the scene", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.position().distanceToSqr(lift) < 4.0;
        }, HELD_ITEM_TIMEOUT_TICKS).client("arm-dedicated: the player starts flying", context -> {
            LocalPlayer player = requirePlayer(context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        camera(script, "lift", context -> CameraView.of("lift", LIFT.getX() + 0.5, LIFT.getY() + 1.6, LIFT.getZ() + 0.5,
                2.5, 0.0, 1.5));
        script.until("arm-dedicated: the player flies", context -> requirePlayer(context).getAbilities().flying,
                HELD_ITEM_TIMEOUT_TICKS);
    }

    // --- scene -------------------------------------------------------------------------------------------------------

    /**
     * An aisle of {@value #RAILS} rails with six storage positions (interfaces in front of chests on both sides), the
     * first warehouse input, the output and the terminal on its right side, the production station and a second input on
     * its left side, a mechanical crafter that puts its result into the second input, three depots, cogwheels on
     * creative motors for the arms and the crafter, and a creative motor under the dock.
     */
    private void buildScene(VisualScript script) {
        command(script, fill(CLEAR_MIN.below(), CLEAR_MAX.atY(-1), "minecraft:smooth_stone"));
        command(script, fill(CLEAR_MIN, CLEAR_MAX, "minecraft:air"));
        command(script, set(DOCK.below(), motor(Direction.UP, CRANE_RPM)));
        command(script, set(ARM_COG.below(), motor(Direction.UP, ARM_RPM)));
        command(script, set(ARM_D_COG.below(), motor(Direction.UP, ARM_RPM)));
        command(script, set(DOCK, "wareworks:stacker_crane[facing=east]"));
        command(script, fill(DOCK.east(), DOCK.east(RAILS), "wareworks:warehouse_rail[axis=x]"));
        command(script, set(CONTROLLER, "wareworks:warehouse_controller[facing=east]"));
        command(script, set(INPUT, "wareworks:warehouse_input[facing=north]"));
        command(script, set(OUTPUT, "wareworks:warehouse_output[facing=north,powered=false]"));
        command(script, set(TERMINAL, "wareworks:warehouse_terminal[facing=north]"));
        command(script, set(PRODUCTION, "wareworks:warehouse_production[facing=south]"));
        command(script, set(INPUT_B, "wareworks:warehouse_input[facing=south]"));
        command(script, fill(new BlockPos(STORAGE_FIRST, 0, 1), new BlockPos(RAILS, 0, 1),
                "wareworks:warehouse_interface[facing=south]"));
        command(script, fill(new BlockPos(STORAGE_FIRST, 0, 2), new BlockPos(RAILS, 0, 2), "minecraft:chest[facing=north]"));
        command(script, fill(new BlockPos(STORAGE_FIRST, 0, -1), new BlockPos(RAILS, 0, -1),
                "wareworks:warehouse_interface[facing=north]"));
        command(script, fill(new BlockPos(STORAGE_FIRST, 0, -2), new BlockPos(RAILS, 0, -2),
                "minecraft:chest[facing=south]"));
        command(script, set(ARM_COG, "create:cogwheel[axis=y]"));
        command(script, set(ARM_D_COG, "create:cogwheel[axis=y]"));
        command(script, set(DEPOT_A, "create:depot"));
        command(script, set(DEPOT_B, "create:depot"));
        command(script, set(BASIN, "create:basin"));
        script.client("arm-dedicated: /setblock the crafter facing east and pointing into the second input",
                context -> send(context, set(CRAFTER, BlockStateParser.serialize(crafterState(Direction.EAST,
                        Direction.SOUTH)))));
        command(script, set(CRAFTER_COG, "create:cogwheel[axis=x]"));
        command(script, set(CRAFTER_MOTOR, motor(Direction.WEST, CRAFTER_RPM)));

        script.until("arm-dedicated: the client sees the scene", context -> {
            ClientLevel level = context.minecraft().level;
            return level != null && WareworksBlocks.STACKER_CRANE.has(level.getBlockState(ORIGIN.offset(DOCK)))
                    && WareworksBlocks.WAREHOUSE_PRODUCTION.has(level.getBlockState(ORIGIN.offset(PRODUCTION)))
                    && AllBlocks.MECHANICAL_CRAFTER.has(level.getBlockState(ORIGIN.offset(CRAFTER)))
                    && AllBlocks.CREATIVE_MOTOR.has(level.getBlockState(ORIGIN.offset(CRAFTER_MOTOR)));
        }, SCENE_TIMEOUT_TICKS);
        queryUntil(script, "the controller has linked the aisle with its storage and all its stations",
                List.of(CONTROLLER), data -> {
                    CompoundTag controller = data.get(CONTROLLER);
                    int length = controller.getCompound("Layout").getInt("Length");
                    Map<String, Integer> kinds = new LinkedHashMap<>();
                    for (Tag entry : controller.getList("Locations", Tag.TAG_COMPOUND))
                        kinds.merge(((CompoundTag) entry).getString("Kind"), 1, Integer::sum);
                    // The terminal is an output-style member (ADR-018).
                    boolean ready = length == RAILS && kinds.getOrDefault("STORAGE", 0) == 2 * (RAILS - STORAGE_FIRST + 1)
                            && kinds.getOrDefault("INPUT", 0) == 2 && kinds.getOrDefault("OUTPUT", 0) == 2
                            && kinds.getOrDefault("PRODUCTION", 0) == 1;
                    return new DataResult(ready, "layout length " + length + ", locations " + kinds);
                }, SCENE_TIMEOUT_TICKS);
        queryUntil(script, "the creative motors turn at their set speeds",
                List.of(DOCK.below(), ARM_COG.below(), ARM_D_COG.below(), CRAFTER_MOTOR), data -> {
                    float crane = data.get(DOCK.below()).getFloat("Speed");
                    float arms = data.get(ARM_COG.below()).getFloat("Speed");
                    float armD = data.get(ARM_D_COG.below()).getFloat("Speed");
                    float crafter = data.get(CRAFTER_MOTOR).getFloat("Speed");
                    return new DataResult(Math.abs(crane) == CRANE_RPM && Math.abs(arms) == ARM_RPM
                            && Math.abs(armD) == ARM_RPM && Math.abs(crafter) == CRAFTER_RPM,
                            "crane motor " + crane + " RPM, arm motors " + arms + " and " + armD + " RPM, crafter motor "
                                    + crafter + " RPM");
                }, SCENE_TIMEOUT_TICKS);
    }

    private static String motor(Direction facing, int rpm) {
        return "create:creative_motor[facing=" + facing.getName() + "]{ScrollValue:" + rpm + "}";
    }

    /** The crafter block state whose arrow points at {@code target} (looked up through Create, as the "arm" scenario). */
    private static BlockState crafterState(Direction facing, Direction target) {
        for (Pointing pointing : Pointing.values()) {
            BlockState state = AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing)
                    .setValue(MechanicalCrafterBlock.POINTING, pointing);
            if (MechanicalCrafterBlock.getTargetDirection(state) == target)
                return state;
        }
        throw new VisualTestException("no crafter pointing makes a crafter facing " + facing + " target " + target);
    }

    // --- selecting targets and placing the arms over the network -----------------------------------------------------

    private void selectAndPlaceArms(VisualScript script) {
        hold(script, SLOT_ARM, "create:mechanical_arm", stack -> AllBlocks.MECHANICAL_ARM.isIn(stack));

        // Arm A: depot A -> warehouse input. The input is selected first, so the packet lists it first.
        for (int click = 1; click <= 3; click++) {
            rightClick(script, CLICK_INPUT, click == 1);
            expectSelected(script, CLICK_INPUT, Mode.DEPOSIT, WarehouseInputArmPoint.class, DEPOSIT_TO_INPUT, 1);
        }
        playerShot(script, "select-input");
        rightClick(script, CLICK_DEPOT_A, true);
        expectSelected(script, CLICK_DEPOT_A, Mode.TAKE, AllArmInteractionPointTypes.DepotPoint.class, TAKE_FROM_DEPOT, 2);
        placeArm(script, "A", GROUND_ARM_A, ARM_A, ARM_A_POINTS);
        playerShot(script, "placed-arm-a");

        // Arm B: warehouse output -> depot B. The first click lands on the output's request filter slot, which must let
        // the arm item through (RequestFilterBehaviour#bypassesInput) and keep its filter empty on the server.
        rightClick(script, CLICK_OUTPUT_FILTER_SLOT, true);
        expectSelected(script, CLICK_OUTPUT_FILTER_SLOT, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_OUTPUT, 1);
        rightClick(script, CLICK_OUTPUT, true);
        expectSelected(script, CLICK_OUTPUT, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_OUTPUT, 1);
        queryUntil(script, "CHECK 92 the clicks with the arm item on the output left its request filter empty",
                List.of(OUTPUT), data -> {
                    CompoundTag filter = data.get(OUTPUT).getCompound("Filter");
                    return new DataResult(filter.isEmpty(), "request filter " + filter);
                }, QUERY_TIMEOUT_TICKS);
        playerShot(script, "select-output");
        rightClick(script, CLICK_DEPOT_B, true);
        expectSelected(script, CLICK_DEPOT_B, Mode.TAKE, AllArmInteractionPointTypes.DepotPoint.class, TAKE_FROM_DEPOT, 2);
        rightClick(script, CLICK_DEPOT_B, false);
        expectSelected(script, CLICK_DEPOT_B, Mode.DEPOSIT, AllArmInteractionPointTypes.DepotPoint.class,
                DEPOSIT_TO_DEPOT, 2);
        placeArm(script, "B", GROUND_ARM_B, ARM_B, ARM_B_POINTS);

        // Arm C: warehouse terminal -> basin.
        for (int click = 1; click <= 3; click++) {
            rightClick(script, CLICK_TERMINAL, click == 1);
            expectSelected(script, CLICK_TERMINAL, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_TERMINAL, 1);
        }
        playerShot(script, "select-terminal");
        rightClick(script, CLICK_BASIN, true);
        rightClick(script, CLICK_BASIN, false);
        expectSelected(script, CLICK_BASIN, Mode.DEPOSIT, ArmInteractionPoint.class, DEPOSIT_TO_BASIN, 2);
        placeArm(script, "C", GROUND_ARM_C, ARM_C, ARM_C_POINTS);

        // Arm D: production station -> mechanical crafter.
        for (int click = 1; click <= 3; click++) {
            rightClick(script, CLICK_PRODUCTION, click == 1);
            expectSelected(script, CLICK_PRODUCTION, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_PRODUCTION, 1);
        }
        playerShot(script, "select-production");
        rightClick(script, CLICK_CRAFTER, true);
        rightClick(script, CLICK_CRAFTER, false);
        expectSelected(script, CLICK_CRAFTER, Mode.DEPOSIT, AllArmInteractionPointTypes.CrafterPoint.class,
                DEPOSIT_TO_CRAFTER, 2);
        placeArm(script, "D", GROUND_ARM_D, ARM_D, ARM_D_POINTS);
    }

    /**
     * Places an arm with a real click on the ground: the server places the block and asks the client for the selection
     * ({@code ArmPlacementPacket.ClientBoundRequest}), the client answers with the {@code ArmPlacementPacket}. Checks the
     * client's summary message, the points the server arm saved, and the points the client arm resolved from its synced
     * data.
     */
    private void placeArm(VisualScript script, String name, Click ground, BlockPos arm, List<SavedPoint> points) {
        rightClick(script, ground, true);
        String summary = "Mechanical Arm has 1 input(s) and 1 output(s).";
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the action bar shows the placement summary of arm " + name,
                context -> summary.equals(ArmVisualScenario.overlayText(context))
                        && ArmVisualScenario.selection().isEmpty(),
                CLICK_TIMEOUT_TICKS * 5, context -> "the action bar reads '" + ArmVisualScenario.overlayText(context)
                        + "', selection " + ArmVisualScenario.selectionSummary());
        queryUntil(script, "CHECK 92 the server arm " + name + " resolved the points of its placement packet", List.of(arm),
                data -> pointsResult(data.get(arm), points), QUERY_TIMEOUT_TICKS);
        clientArmResolved(script, name, arm);
    }

    // --- the production pattern, written in the production station's screen ------------------------------------------

    /**
     * Writes "1 oak log → 4 oak planks" into the first pattern of the production station the way a player does: an
     * empty hand opens the station's screen, a click on the inventory slot picks the log up, a click on grid cell 0 writes
     * it, a click on the slot puts the log back; the same with the planks on the result cell, and three notches of the
     * mouse wheel over the result cell raise its amount to 4. Every click waits for the pattern the server sends back.
     */
    private void writePattern(VisualScript script) {
        command(script, "item replace entity @s hotbar." + SLOT_PATTERN_LOG + " with " + LOG);
        command(script, "item replace entity @s hotbar." + SLOT_PATTERN_PLANKS + " with " + PLANKS);
        hold(script, SLOT_EMPTY, null, ItemStack::isEmpty);
        script.until("arm-dedicated: the client has a log and planks in its hotbar", context -> {
            LocalPlayer player = requirePlayer(context);
            return player.getInventory().getItem(SLOT_PATTERN_LOG).is(Items.OAK_LOG)
                    && player.getInventory().getItem(SLOT_PATTERN_PLANKS).is(Items.OAK_PLANKS);
        }, HELD_ITEM_TIMEOUT_TICKS);
        rightClick(script, CLICK_PRODUCTION, true);
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the production station's screen is open",
                context -> context.minecraft().screen instanceof WarehouseProductionScreen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);

        patternEntry(script, SLOT_PATTERN_LOG, Items.OAK_LOG, 0, "oak log", 1);
        patternEntry(script, SLOT_PATTERN_PLANKS, Items.OAK_PLANKS, ProductionMenuLayout.RESULT_CELL, "oak planks", 1);
        int[] scrolledAt = { -1 };
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: scroll the result cell up to " + PLANKS_PER_LOG + " planks",
                context -> {
                    WarehouseProductionScreen screen = productionScreen(context);
                    int count = screen.state().entry(0, ProductionMenuLayout.RESULT_CELL)
                            .map(ProductionScreenState.EntryView::count).orElse(0);
                    if (count == PLANKS_PER_LOG)
                        return true;
                    // One notch, then wait for the server's answer before the next one.
                    if (count != scrolledAt[0]) {
                        scrolledAt[0] = count;
                        ScreenInput.scroll(context.minecraft(),
                                ScreenInput.productionCell(screen, ProductionMenuLayout.RESULT_CELL), 1.0);
                    }
                    return false;
                }, SCREEN_TIMEOUT_TICKS, context -> "the result cell shows " + productionScreen(context).state()
                        .entry(0, ProductionMenuLayout.RESULT_CELL));
        script.client("arm-dedicated: CHECK 92 the pattern the server sent back to the screen", context -> {
            ProductionScreenState state = productionScreen(context).state();
            LOGGER.info(PREFIX + "arm-dedicated: CHECK 92 PASS (dedicated): the production screen shows the pattern the "
                    + "server wrote from the clicks: cell 0 {}, result {}", state.entry(0, 0),
                    state.entry(0, ProductionMenuLayout.RESULT_CELL));
        });
        script.shot("production-pattern");
        closeScreen(script);
        queryUntil(script, "CHECK 92 the production station saved the pattern written in its screen", List.of(PRODUCTION),
                data -> {
                    String patterns = String.valueOf(data.get(PRODUCTION).get("Patterns"));
                    return new DataResult(patterns.contains(LOG) && patterns.contains(PLANKS), "Patterns " + patterns);
                }, QUERY_TIMEOUT_TICKS);
    }

    /** Picks the item of hotbar slot {@code slot} up, clicks it into pattern cell {@code cell}, and puts it back. */
    private void patternEntry(VisualScript script, int slot, net.minecraft.world.item.Item item, int cell, String what,
            int count) {
        script.client("arm-dedicated: click the " + what + " in the inventory to pick it up", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            ScreenInput.click(context.minecraft(), ScreenInput.hotbarSlot(screen, slot));
        });
        script.until("arm-dedicated: the " + what + " hangs on the mouse",
                context -> productionScreen(context).getMenu().getCarried().is(item), SCREEN_TIMEOUT_TICKS);
        script.waitTicks(SLOT_CLICK_GAP_TICKS);
        script.client("arm-dedicated: click pattern cell " + cell + " with the " + what, context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            ScreenInput.click(context.minecraft(), ScreenInput.productionCell(screen, cell));
        });
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the server wrote the " + what + " into pattern cell " + cell,
                context -> productionScreen(context).state().entry(0, cell)
                        .filter(entry -> entry.key().equals(ItemKey.of(new ItemStack(item))) && entry.count() == count)
                        .isPresent(),
                SCREEN_TIMEOUT_TICKS, context -> "cell " + cell + " shows " + productionScreen(context).state().entry(0, cell));
        script.waitTicks(SLOT_CLICK_GAP_TICKS);
        script.client("arm-dedicated: click the inventory slot to put the " + what + " back", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            ScreenInput.click(context.minecraft(), ScreenInput.hotbarSlot(screen, slot));
        });
        script.until("arm-dedicated: the " + what + " is back in its slot", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            return screen.getMenu().getCarried().isEmpty() && requirePlayer(context).getInventory().getItem(slot).is(item);
        }, SCREEN_TIMEOUT_TICKS);
        script.waitTicks(SLOT_CLICK_GAP_TICKS);
    }

    // --- items -------------------------------------------------------------------------------------------------------

    /** Puts {@code amount} of {@code item} on depot A by hand; arm A feeds the input until the crane has stored all of it. */
    private void feed(VisualScript script, String item, int amount, String when) {
        hold(script, SLOT_FEED, item + " " + amount, stack -> isItem(stack, item) && stack.getCount() == amount);
        rightClick(script, CLICK_DEPOT_A, true);
        script.until("arm-dedicated: depot A took the stack out of the player's hand",
                context -> requirePlayer(context).getMainHandItem().isEmpty(), HELD_ITEM_TIMEOUT_TICKS);
        queryUntil(script, "CHECK 92 arm A moved " + amount + " " + item + " from depot A into the warehouse input and the "
                + "crane stored all of it (" + when + ")", withChests(DEPOT_A, ARM_A, INPUT), data -> {
                    int depot = itemCount(data.get(DEPOT_A), item);
                    int claw = itemCount(data.get(ARM_A), item);
                    int buffered = bufferEntries(data.get(INPUT));
                    int stored = chestCount(data, item);
                    return new DataResult(depot == 0 && claw == 0 && buffered == 0 && stored == amount,
                            "depot A " + depot + ", arm A claw " + claw + ", input buffer entries " + buffered
                                    + ", chests " + stored + " " + item);
                }, STORE_TIMEOUT_TICKS);
    }

    /**
     * Arm A feeds the input from depot A until the crane has stored everything; then the output requests the item and arm
     * B takes all of it out of the output onto depot B.
     */
    private void feedAndEmpty(VisualScript script, String item, int amount, String when) {
        feed(script, item, amount, when);
        hold(script, SLOT_FILTER, item + " 1", stack -> isItem(stack, item));
        rightClick(script, CLICK_OUTPUT_FILTER_SLOT, true);
        queryUntil(script, "the click on the output's filter slot set its request filter to " + item, List.of(OUTPUT),
                data -> {
                    String filter = data.get(OUTPUT).getCompound("Filter").getString("id");
                    return new DataResult(item.equals(filter), "request filter '" + filter + "'");
                }, QUERY_TIMEOUT_TICKS);
        command(script, set(REDSTONE, "minecraft:air"));
        script.until("arm-dedicated: the client sees the output unpowered", context -> outputPowered(context) == Boolean.FALSE,
                HELD_ITEM_TIMEOUT_TICKS);
        command(script, set(REDSTONE, "minecraft:redstone_block"));
        queryUntil(script, "CHECK 92 the output requested " + item + ", the crane delivered it and arm B moved all "
                + amount + " onto depot B (" + when + ")", withChests(OUTPUT, ARM_B, DEPOT_B), data -> {
                    int buffered = bufferEntries(data.get(OUTPUT));
                    int claw = itemCount(data.get(ARM_B), item);
                    int depot = itemCount(data.get(DEPOT_B), item);
                    int stored = chestCount(data, item);
                    return new DataResult(buffered == 0 && claw == 0 && depot == amount && stored == 0,
                            "output buffer entries " + buffered + ", arm B claw " + claw + ", depot B " + depot
                                    + ", chests " + stored + " " + item);
                }, STORE_TIMEOUT_TICKS);
    }

    /**
     * Orders {@code amount} oak planks in the terminal screen: an empty hand opens it, the mouse wheel over the amount
     * field sets the amount, a left click on the planks in the stock grid sends the request. The screen stays open and
     * records every state the server reports for the new production order until it is complete; arm D carries the logs
     * from the production station into the crafter, the crane stores the planks and brings them to the terminal, and arm
     * C takes them out into the basin. {@code logsLeft} is what the chests and {@code planksInBasin} what the basin must
     * hold afterwards.
     */
    private void orderPlanks(VisualScript script, int amount, int logsLeft, int planksInBasin, String when) {
        hold(script, SLOT_EMPTY, null, ItemStack::isEmpty);
        rightClick(script, CLICK_TERMINAL, true);
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the terminal screen is open and lists oak planks",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen screen && screen.hasStock()
                        && stockCell(screen, PLANKS) >= 0,
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: scroll the terminal's amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(terminalScreen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(terminalScreen(context)).getState());
        script.client("arm-dedicated: left-click the oak planks in the terminal's stock grid", context -> {
            WarehouseTerminalScreen screen = terminalScreen(context);
            ordersBeforeClick = screen.productionOrders().stream().map(ProductionScreenState.OrderView::id).toList();
            orderedPlanks = null;
            seenStates.clear();
            StockLine<ItemKey> line = screen.visibleEntries().get(stockCell(screen, PLANKS));
            int cell = stockCell(screen, PLANKS);
            ScreenInput.Point point = ScreenInput.terminalCell(screen, cell);
            ScreenInput.click(context.minecraft(), point);
            LOGGER.info(PREFIX + "arm-dedicated: clicked the oak planks (grid cell {} at {}, available {}, producible {}) "
                    + "in the terminal screen with the amount field at x{} ({})", cell, point, line.available(),
                    line.producibleAmount(), ScreenInput.terminalAmount(screen).getState(), when);
        });
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: CHECK 92 the server reports a production order for "
                + amount + " planks", context -> {
                    Optional<ProductionScreenState.OrderView> order = terminalScreen(context).productionOrders().stream()
                            .filter(view -> !ordersBeforeClick.contains(view.id())).findFirst();
                    if (order.isEmpty())
                        return false;
                    ProductionScreenState.OrderView view = order.get();
                    if (!view.result().equals(ItemKey.of(new ItemStack(Items.OAK_PLANKS))) || view.amount() != amount)
                        throw new VisualTestException("the click started an order for " + view.amount() + " "
                                + view.result() + ", expected " + amount + " oak planks");
                    orderedPlanks = view.id();
                    LOGGER.info(PREFIX + "arm-dedicated: CHECK 92 PASS (dedicated): the click in the terminal screen "
                            + "started production order {} for {} oak planks, as the server's order list shows ({})",
                            view.id(), amount, when);
                    return true;
                }, SCREEN_TIMEOUT_TICKS, context -> "the terminal lists the orders " + terminalScreen(context)
                        .productionOrders());
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the terminal screen shows the order complete", context -> {
            ProductionScreenState.OrderView view = terminalScreen(context).productionOrders().stream()
                    .filter(candidate -> candidate.id().equals(orderedPlanks)).findFirst()
                    .orElseThrow(() -> new VisualTestException("the order " + orderedPlanks + " left the terminal's list"));
            if (seenStates.isEmpty() || seenStates.getLast() != view.state()) {
                seenStates.add(view.state());
                LOGGER.info(PREFIX + "arm-dedicated: the terminal screen shows order {} as {} (produced {}, missing {}, "
                        + "delivered {})", view.id(), view.state(), view.produced(), view.missing(), view.delivered());
            }
            if (view.state() == ProductionOrderState.TIMED_OUT || view.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the production order ended " + view.state());
            return view.state() == ProductionOrderState.COMPLETE;
        }, PRODUCTION_TIMEOUT_TICKS, context -> "the states seen so far are " + seenStates);
        script.client("arm-dedicated: CHECK 92 the order went through waiting for the result to complete", context -> {
            int waiting = seenStates.indexOf(ProductionOrderState.WAITING_FOR_RESULT);
            if (waiting < 0 || seenStates.getLast() != ProductionOrderState.COMPLETE)
                throw new VisualTestException("the terminal showed the states " + seenStates
                        + ", expected WAITING_FOR_RESULT before a final COMPLETE");
            LOGGER.info(PREFIX + "arm-dedicated: CHECK 92 PASS (dedicated): the terminal screen showed the order as {} "
                    + "({})", seenStates, when);
        });
        script.shot("terminal-order-complete-" + when.replace(' ', '-'));
        closeScreen(script);
        List<BlockPos> chain = withChests(PRODUCTION, ARM_D, CRAFTER, INPUT_B, TERMINAL, ARM_C, BASIN);
        queryUntil(script, "CHECK 92 arm D fed the crafter from the production station and arm C moved all " + amount
                + " planks out of the terminal into the basin (" + when + ")", chain, data -> {
                    int stationBuffer = bufferEntries(data.get(PRODUCTION));
                    int clawD = itemCount(data.get(ARM_D), LOG) + itemCount(data.get(ARM_D), PLANKS);
                    int crafter = itemCount(data.get(CRAFTER), LOG) + itemCount(data.get(CRAFTER), PLANKS);
                    int inputB = bufferEntries(data.get(INPUT_B));
                    int terminal = bufferEntries(data.get(TERMINAL));
                    int clawC = itemCount(data.get(ARM_C), PLANKS);
                    int basin = itemCount(data.get(BASIN), PLANKS);
                    int logs = chestCount(data, LOG);
                    int storedPlanks = chestCount(data, PLANKS);
                    return new DataResult(stationBuffer == 0 && clawD == 0 && crafter == 0 && inputB == 0 && terminal == 0
                            && clawC == 0 && basin == planksInBasin && logs == logsLeft && storedPlanks == 0,
                            "production buffer entries " + stationBuffer + ", arm D claw " + clawD + ", crafter "
                                    + crafter + ", second input buffer entries " + inputB + ", terminal buffer entries "
                                    + terminal + ", arm C claw " + clawC + ", basin " + basin + " planks, chests "
                                    + logs + " logs and " + storedPlanks + " planks");
                }, PRODUCTION_TIMEOUT_TICKS);
    }

    // --- after the restart -------------------------------------------------------------------------------------------

    private void afterRestart(VisualScript script) {
        script.until("arm-dedicated: the client has the scene and all four arms again", context -> {
            ClientLevel level = context.minecraft().level;
            if (level == null)
                return false;
            for (BlockPos arm : List.of(ARM_A, ARM_B, ARM_C, ARM_D))
                if (!(level.getBlockEntity(ORIGIN.offset(arm)) instanceof ArmBlockEntity))
                    return false;
            return true;
        }, SCENE_TIMEOUT_TICKS);
        liftAndFly(script);
        queryUntil(script, "CHECK 92 after the server restart all four arms kept their saved points, depot B and the basin their "
                + "items", withChests(ARM_A, ARM_B, ARM_C, ARM_D, DEPOT_B, BASIN), data -> {
                    DataResult points = allPoints(data);
                    int iron = itemCount(data.get(DEPOT_B), IRON);
                    int planks = itemCount(data.get(BASIN), PLANKS);
                    int storedIron = chestCount(data, IRON);
                    int logs = chestCount(data, LOG);
                    int expectedLogs = FED_LOGS - ORDERED_PLANKS / PLANKS_PER_LOG;
                    return new DataResult(points.passed() && iron == FED_IRON && planks == ORDERED_PLANKS
                            && storedIron == 0 && logs == expectedLogs, points.detail() + "; depot B " + iron
                            + " iron, basin " + planks + " planks, chests " + storedIron + " iron and " + logs + " logs");
                }, QUERY_TIMEOUT_TICKS);
        for (Map.Entry<String, BlockPos> arm : List.of(Map.entry("A", ARM_A), Map.entry("B", ARM_B),
                Map.entry("C", ARM_C), Map.entry("D", ARM_D)))
            clientArmResolved(script, arm.getKey(), arm.getValue());

        // Depot B still holds the iron from before the restart: take it off by hand, so arm B has room again. The basin
        // has room for the next planks.
        takeOffDepot(script, CLICK_DEPOT_B, DEPOT_B, IRON, Items.IRON_INGOT, FED_IRON);

        feedAndEmpty(script, GOLD, FED_GOLD, "after the restart");
        orderPlanks(script, ORDERED_PLANKS_AFTER_RESTART,
                FED_LOGS - (ORDERED_PLANKS + ORDERED_PLANKS_AFTER_RESTART) / PLANKS_PER_LOG,
                ORDERED_PLANKS + ORDERED_PLANKS_AFTER_RESTART, "after the restart");
        queryPoints(script, "at the end");
        camera(script, OVERVIEW.label(), context -> OVERVIEW);
        playerShot(script, "second-session-done");
    }

    /**
     * A right-click with an empty hand takes the depot's stack into the player's inventory (Create puts it back like a
     * picked-up item, so it may join a stack of the same item in another slot); {@code /clear} then removes that item
     * from the inventory again.
     */
    private void takeOffDepot(VisualScript script, Click click, BlockPos depot, String itemId,
            net.minecraft.world.item.Item item, int amount) {
        int[] before = { 0 };
        hold(script, SLOT_EMPTY, null, ItemStack::isEmpty);
        script.client("arm-dedicated: count the " + itemId + " in the inventory before taking it off " + click.label(),
                context -> before[0] = requirePlayer(context).getInventory().countItem(item));
        rightClick(script, click, true);
        queryUntil(script, "a right-click with an empty hand took the " + amount + " " + itemId + " off " + click.label(),
                List.of(depot), data -> new DataResult(itemCount(data.get(depot), itemId) == 0,
                        click.label() + " holds " + itemCount(data.get(depot), itemId) + " " + itemId), QUERY_TIMEOUT_TICKS);
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the player's inventory gained the " + amount + " " + itemId
                        + " from " + click.label(),
                context -> requirePlayer(context).getInventory().countItem(item) == before[0] + amount,
                HELD_ITEM_TIMEOUT_TICKS, context -> "the inventory holds " + requirePlayer(context).getInventory()
                        .countItem(item) + " " + itemId + ", " + before[0] + " before");
        command(script, "clear @s " + itemId);
        script.until("arm-dedicated: /clear removed the " + itemId + " from the inventory",
                context -> requirePlayer(context).getInventory().countItem(item) == 0, HELD_ITEM_TIMEOUT_TICKS);
    }

    private void queryPoints(VisualScript script, String when) {
        queryUntil(script, "CHECK 92 saved points of all four arms " + when, List.of(ARM_A, ARM_B, ARM_C, ARM_D),
                ArmDedicatedServerScenario::allPoints, QUERY_TIMEOUT_TICKS);
    }

    private static DataResult allPoints(Map<BlockPos, CompoundTag> data) {
        DataResult a = pointsResult(data.get(ARM_A), ARM_A_POINTS);
        DataResult b = pointsResult(data.get(ARM_B), ARM_B_POINTS);
        DataResult c = pointsResult(data.get(ARM_C), ARM_C_POINTS);
        DataResult d = pointsResult(data.get(ARM_D), ARM_D_POINTS);
        return new DataResult(a.passed() && b.passed() && c.passed() && d.passed(), "arm A " + a.detail() + "; arm B "
                + b.detail() + "; arm C " + c.detail() + "; arm D " + d.detail());
    }

    /** The client arm resolved its synced points into the Wareworks point classes, through the synced type registry. */
    private static void clientArmResolved(VisualScript script, String name, BlockPos arm) {
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: CHECK 92 the client arm " + name + " resolved its points",
                context -> clientPoints(context, arm).size() == 2, QUERY_TIMEOUT_TICKS,
                context -> "the client arm " + name + " has " + describe(clientPoints(context, arm)));
        script.client("arm-dedicated: CHECK 92 the classes of the client arm " + name + "'s points", context -> {
            List<ArmInteractionPoint> points = clientPoints(context, arm);
            for (ArmInteractionPoint point : points) {
                String type = String.valueOf(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType()));
                Class<?> expected = switch (type) {
                    case "wareworks:warehouse_input" -> WarehouseInputArmPoint.class;
                    case "wareworks:warehouse_output", "wareworks:warehouse_terminal", "wareworks:warehouse_production" ->
                            DeliveryStationArmPoint.class;
                    case "create:depot" -> AllArmInteractionPointTypes.DepotPoint.class;
                    case "create:crafter" -> AllArmInteractionPointTypes.CrafterPoint.class;
                    case "create:basin" -> ArmInteractionPoint.class;
                    default -> throw new VisualTestException("the client arm " + name + " has an unexpected point " + type);
                };
                if (point.getClass() != expected)
                    throw new VisualTestException("the client arm " + name + " made a " + point.getClass().getName()
                            + " of " + type + ", expected " + expected.getName());
            }
            LOGGER.info(PREFIX + "arm-dedicated: CHECK 92 PASS (dedicated): the client arm {} at {} resolved its synced "
                    + "points through the synced registry: {}", name, ORIGIN.offset(arm).toShortString(), describe(points));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<ArmInteractionPoint> clientPoints(VisualContext context, BlockPos arm) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(ORIGIN.offset(arm)) instanceof ArmBlockEntity be))
            return List.of();
        List<ArmInteractionPoint> points = new ArrayList<>(
                (List<ArmInteractionPoint>) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_INPUTS, be));
        points.addAll((List<ArmInteractionPoint>) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_OUTPUTS, be));
        return points;
    }

    private static String describe(List<ArmInteractionPoint> points) {
        List<String> parts = new ArrayList<>();
        for (ArmInteractionPoint point : points)
            parts.add(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType()) + "@"
                    + point.getPos().toShortString() + ":" + point.getMode() + ":" + point.getClass().getSimpleName());
        return parts.toString();
    }

    @Nullable
    private static Boolean outputPowered(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return null;
        BlockState state = level.getBlockState(ORIGIN.offset(OUTPUT));
        return WareworksBlocks.WAREHOUSE_OUTPUT.has(state) ? state.getValue(WarehouseOutputBlock.POWERED) : null;
    }

    // --- screens -----------------------------------------------------------------------------------------------------

    private static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen screen)
            return screen;
        throw new VisualTestException("the terminal screen is not open: " + context.minecraft().screen);
    }

    private static WarehouseProductionScreen productionScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production station's screen is not open: " + context.minecraft().screen);
    }

    /** The visible grid cell of the terminal showing {@code itemId}, or -1. */
    private static int stockCell(WarehouseTerminalScreen screen, String itemId) {
        List<StockLine<ItemKey>> visible = screen.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (isItem(visible.get(cell).key().toStack(), itemId))
                return cell;
        return -1;
    }

    /** Closes the open screen with the Escape key. */
    private static void closeScreen(VisualScript script) {
        script.client("arm-dedicated: press Escape to close the screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("arm-dedicated: the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    // --- client input ------------------------------------------------------------------------------------------------

    /**
     * A shot with the GUI as the player sees it. The chat lines of the {@code /data} answers and the join toasts would
     * cover the action bar and the scene, so they are cleared from the screen first (the client log keeps every chat
     * line).
     */
    private static void playerShot(VisualScript script, String label) {
        script.client("arm-dedicated: clear the chat lines and toasts from the screen for the shot " + label, context -> {
            context.minecraft().gui.getChat().clearMessages(false);
            context.minecraft().getToasts().clear();
        });
        script.shotWithGui(label);
    }

    /**
     * Puts {@code item} (id and count, or {@code null} to leave the slot as it is) into hotbar slot {@code slot} with
     * {@code /item}, selects the slot with its hotbar key and waits until the client holds what {@code expected} accepts.
     */
    private void hold(VisualScript script, int slot, @Nullable String item, Predicate<ItemStack> expected) {
        if (item != null)
            command(script, "item replace entity @s hotbar." + slot + " with " + item);
        script.client("arm-dedicated: press hotbar key " + (slot + 1),
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[slot].getKey()));
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the client holds hotbar slot " + slot + " ("
                + (item == null ? "as it is" : item) + ")", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && player.getInventory().selected == slot && expected.test(player.getMainHandItem());
                }, HELD_ITEM_TIMEOUT_TICKS, context -> "the client holds " + requirePlayer(context).getMainHandItem()
                        + " in slot " + requirePlayer(context).getInventory().selected);
        script.waitTicks(SETTLE_TICKS);
    }

    private void rightClick(VisualScript script, Click click, boolean moveCamera) {
        if (moveCamera)
            camera(script, click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the crosshair is on the " + click.face() + " face of the "
                        + click.label(), context -> ArmVisualScenario.aimedAt(context, click), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context) + " instead of the "
                        + click.face() + " face of " + ORIGIN.offset(click.block()));
        script.client("arm-dedicated: right-click " + click.label(), context -> {
            overlayBeforeClick = ArmVisualScenario.overlay(context);
            KeyMapping.click(context.minecraft().options.keyUse.getKey());
        });
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the client handled the right-click on " + click.label(),
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
        script.waitTicks(SETTLE_TICKS);
    }

    /** The selection after a click: point, mode, class, a new action bar message and the outline colour. */
    private void expectSelected(VisualScript script, Click click, Mode mode, Class<?> pointClass, String message,
            int selectionSize) {
        BlockPos pos = ORIGIN.offset(click.block());
        script.client("arm-dedicated: CHECK 92 " + click.label() + " is selected as " + mode, context -> {
            ArmInteractionPoint point = ArmVisualScenario.selected(pos).orElseThrow(() -> new VisualTestException(
                    "the " + click.label() + " at " + pos + " is not selected: " + ArmVisualScenario.selectionSummary()));
            if (point.getMode() != mode || point.getClass() != pointClass)
                throw new VisualTestException("the " + click.label() + " is selected as " + point.getMode() + " "
                        + point.getClass().getName() + ", expected " + mode + " " + pointClass.getName());
            Component shown = ArmVisualScenario.overlay(context);
            if (shown == null || shown == overlayBeforeClick)
                throw new VisualTestException("the click on the " + click.label() + " showed no new action bar message");
            if (!message.equals(shown.getString()))
                throw new VisualTestException("the action bar reads '" + shown.getString() + "', expected '" + message
                        + "'");
            if (ArmVisualScenario.selection().size() != selectionSize)
                throw new VisualTestException("the selection is " + ArmVisualScenario.selectionSummary() + ", expected "
                        + selectionSize + " points");
        });
        ArmVisualScenario.untilOrFail(script, "arm-dedicated: the outline of the " + click.label() + " is drawn",
                context -> ArmVisualScenario.outlineColour(pos).isPresent(), OUTLINE_TIMEOUT_TICKS,
                context -> "no outline in Create's outliner for " + pos);
        script.client("arm-dedicated: CHECK 92 outline colour of the " + click.label(), context -> {
            int colour = ArmVisualScenario.outlineColour(pos).orElseThrow();
            if (colour != mode.getColor())
                throw new VisualTestException(String.format(Locale.ROOT, "the outline of the %s is #%06X, expected #%06X",
                        click.label(), colour, mode.getColor()));
            LOGGER.info(PREFIX + "arm-dedicated: CHECK 92 PASS (dedicated): {} selected as {} ({}), outline #{} ({}), "
                            + "action bar '{}', selection {}", click.label(), mode, pointClass.getSimpleName(),
                    String.format(Locale.ROOT, "%06X", colour), mode == Mode.DEPOSIT ? "yellow" : "light blue",
                    ArmVisualScenario.overlayText(context), ArmVisualScenario.selectionSummary());
        });
    }

    /**
     * Moves the camera with {@code /tp} to the view computed when the step starts, and waits until the player arrived and
     * no chunk section is left to compile (like {@code VisualScript#camera}, which teleports on the integrated server).
     */
    private void camera(VisualScript script, String label, Function<VisualContext, CameraView> viewAtStart) {
        AtomicReference<CameraView.Placement> target = new AtomicReference<>();
        int[] ticks = { 0 };
        int[] settled = { 0 };
        script.client("arm-dedicated: /tp the camera to view " + label, context -> {
            LocalPlayer player = requirePlayer(context);
            CameraView.Placement placement = Objects.requireNonNull(viewAtStart.apply(context), label)
                    .placement(context.origin(), player.getEyeHeight());
            target.set(placement);
            ticks[0] = 0;
            settled[0] = 0;
            send(context, String.format(Locale.ROOT, "tp @s %.5f %.5f %.5f %.4f %.4f", placement.x(), placement.y(),
                    placement.z(), placement.yaw(), placement.pitch()));
        });
        script.until("arm-dedicated: the camera arrived at view " + label, context -> {
            CameraView.Placement placement = Objects.requireNonNull(target.get(), "placement");
            Minecraft minecraft = context.minecraft();
            LocalPlayer player = minecraft.player;
            ticks[0]++;
            boolean arrived = player != null
                    && player.position().distanceToSqr(placement.feet()) <= CAMERA_ARRIVAL_DISTANCE_SQR;
            boolean compiled = minecraft.levelRenderer.hasRenderedAllSections();
            settled[0] = arrived && compiled ? settled[0] + 1 : 0;
            if (ticks[0] >= CAMERA_MIN_TICKS && settled[0] >= CAMERA_SETTLED_TICKS)
                return true;
            if (ticks[0] < CAMERA_MAX_TICKS)
                return false;
            if (!arrived)
                throw new VisualTestException("the camera did not arrive at view " + label + " " + placement.feet()
                        + " (the player is at " + (player == null ? "nowhere" : player.position()) + ")");
            LOGGER.warn(PREFIX + "chunk sections still compiling at view {}; going on", label);
            return true;
        }, CAMERA_MAX_TICKS + 10);
    }

    // --- server data over chat ---------------------------------------------------------------------------------------

    private void installChatListener() {
        if (listening)
            return;
        listening = true;
        NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.System.class, event -> {
            if (!event.isOverlay())
                systemMessages.add(event.getMessage().getString());
        });
    }

    /** The outcome of one evaluation of server data: whether the expected state is reached, and what was seen. */
    private record DataResult(boolean passed, String detail) {
    }

    @FunctionalInterface
    private interface DataCheck {
        DataResult evaluate(Map<BlockPos, CompoundTag> data);
    }

    /**
     * Asks the server for the block entity data at {@code offsets} with {@code /data get block}, one round at a time, and
     * evaluates {@code check} on the parsed answers until it passes; fails with the last evaluation after
     * {@code timeoutTicks}. The passing answers' evaluation is logged as evidence.
     */
    private void queryUntil(VisualScript script, String description, List<BlockPos> offsets, DataCheck check,
            int timeoutTicks) {
        DataPoll poll = new DataPoll(description, offsets, check, timeoutTicks);
        script.until("arm-dedicated: " + description, poll, timeoutTicks + ANSWER_TIMEOUT_TICKS);
    }

    private final class DataPoll implements Predicate<VisualContext> {
        private final String description;
        private final List<BlockPos> offsets;
        private final DataCheck check;
        private final int timeoutTicks;
        private final Map<BlockPos, CompoundTag> answers = new LinkedHashMap<>();
        private int ticks;
        private int roundTicks;
        private int firstMessage;
        private boolean roundOpen;
        private boolean evaluated;
        private int rounds;
        private String lastDetail = "no complete answer yet";

        DataPoll(String description, List<BlockPos> offsets, DataCheck check, int timeoutTicks) {
            this.description = description;
            this.offsets = List.copyOf(offsets);
            this.check = check;
            this.timeoutTicks = timeoutTicks;
        }

        @Override
        public boolean test(VisualContext context) {
            ticks++;
            if (!roundOpen || (evaluated && roundTicks >= POLL_INTERVAL_TICKS) || roundTicks >= ANSWER_TIMEOUT_TICKS) {
                if (ticks > timeoutTicks)
                    throw new VisualTestException(description + ": not reached after " + rounds + " rounds; last seen: "
                            + lastDetail + "; answers " + answers);
                answers.clear();
                firstMessage = systemMessages.size();
                for (BlockPos offset : offsets) {
                    BlockPos pos = ORIGIN.offset(offset);
                    send(context, "data get block " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                }
                rounds++;
                roundOpen = true;
                evaluated = false;
                roundTicks = 0;
                return false;
            }
            roundTicks++;
            if (evaluated)
                return false;
            collectAnswers();
            if (answers.size() < offsets.size())
                return false;
            DataResult result = check.evaluate(answers);
            evaluated = true;
            lastDetail = result.detail();
            if (!result.passed())
                return false;
            LOGGER.info(PREFIX + "arm-dedicated: {}{}: {} (server data after {} round(s))",
                    description.startsWith("CHECK ") ? "" : "OK: ",
                    description.startsWith("CHECK ") ? description.replaceFirst("^CHECK 92 ", "CHECK 92 PASS (dedicated): ")
                            : description, result.detail(), rounds);
            return true;
        }

        private void collectAnswers() {
            List<String> messages = systemMessages;
            for (int index = firstMessage; index < messages.size(); index++) {
                String text = messages.get(index);
                for (BlockPos offset : offsets) {
                    BlockPos pos = ORIGIN.offset(offset);
                    String prefix = String.format(Locale.ROOT, DATA_ANSWER, pos.getX(), pos.getY(), pos.getZ());
                    if (answers.containsKey(offset) || !text.startsWith(prefix))
                        continue;
                    try {
                        answers.put(offset, TagParser.parseTag(text.substring(prefix.length())));
                    } catch (CommandSyntaxException error) {
                        throw new VisualTestException("the server's data answer for " + pos.toShortString()
                                + " cannot be parsed (" + error.getMessage() + "): " + text);
                    }
                }
            }
        }
    }

    // --- reading block entity data ------------------------------------------------------------------------------------

    /** A point in an arm's saved {@code InteractionPoints}: type id, mode and position relative to the arm. */
    private record SavedPoint(String type, Mode mode, BlockPos relative) {
        @Override
        public String toString() {
            return type + "@" + relative.toShortString() + ":" + mode;
        }
    }

    private static DataResult pointsResult(CompoundTag arm, List<SavedPoint> expected) {
        List<SavedPoint> saved = new ArrayList<>();
        for (Tag entry : arm.getList("InteractionPoints", Tag.TAG_COMPOUND)) {
            CompoundTag point = (CompoundTag) entry;
            int[] pos = point.getIntArray("Pos");
            Mode mode;
            try {
                mode = Mode.valueOf(point.getString("Mode"));
            } catch (IllegalArgumentException error) {
                return new DataResult(false, "a point with the mode '" + point.getString("Mode") + "': " + point);
            }
            saved.add(new SavedPoint(point.getString("Type"), mode,
                    pos.length == 3 ? new BlockPos(pos[0], pos[1], pos[2]) : BlockPos.ZERO));
        }
        return new DataResult(saved.equals(expected), "saved InteractionPoints " + saved + " (expected " + expected
                + "), claw " + arm.getCompound("HeldItem") + ", phase " + arm.getString("Phase"));
    }

    /** The storage chests behind the interfaces on both sides of the aisle. */
    private static List<BlockPos> chests() {
        List<BlockPos> chests = new ArrayList<>();
        for (int x = STORAGE_FIRST; x <= RAILS; x++) {
            chests.add(new BlockPos(x, 0, 2));
            chests.add(new BlockPos(x, 0, -2));
        }
        return chests;
    }

    private static List<BlockPos> withChests(BlockPos... offsets) {
        List<BlockPos> all = new ArrayList<>(List.of(offsets));
        all.addAll(chests());
        return all;
    }

    private static int chestCount(Map<BlockPos, CompoundTag> data, String itemId) {
        int total = 0;
        for (BlockPos chest : chests())
            total += itemCount(data.get(chest), itemId);
        return total;
    }

    /**
     * Items of {@code itemId} anywhere in the data: chest slots, a depot's held item and output buffer, an arm's claw, a
     * crafter's inventory.
     */
    private static int itemCount(Tag tag, String itemId) {
        if (tag instanceof CompoundTag compound) {
            if (itemId.equals(compound.getString("id")) && compound.contains("count", Tag.TAG_ANY_NUMERIC))
                return compound.getInt("count");
            int total = 0;
            for (String key : compound.getAllKeys())
                total += itemCount(Objects.requireNonNull(compound.get(key)), itemId);
            return total;
        }
        if (tag instanceof ListTag list) {
            int total = 0;
            for (Tag element : list)
                total += itemCount(element, itemId);
            return total;
        }
        return 0;
    }

    /** Occupied slots of a station buffer ({@code Buffer.Items}). */
    private static int bufferEntries(CompoundTag station) {
        if (!station.contains("Buffer", Tag.TAG_COMPOUND))
            throw new VisualTestException("the station data has no buffer: " + station);
        return station.getCompound("Buffer").getList("Items", Tag.TAG_COMPOUND).size();
    }

    private static boolean isItem(ItemStack stack, String itemId) {
        return !stack.isEmpty() && itemId.equals(String.valueOf(stack.getItemHolder().getRegisteredName()));
    }

    // --- commands ----------------------------------------------------------------------------------------------------

    private static void command(VisualScript script, String command) {
        script.client("arm-dedicated: /" + command, context -> send(context, command));
    }

    /** Sends a command to the server as the player types it (client commands such as {@code flywheel} run locally). */
    private static void send(VisualContext context, String command) {
        requirePlayer(context).connection.sendCommand(command);
    }

    private static String set(BlockPos offset, String block) {
        BlockPos pos = ORIGIN.offset(offset);
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private static String fill(BlockPos fromOffset, BlockPos toOffset, String block) {
        BlockPos from = ORIGIN.offset(fromOffset);
        BlockPos to = ORIGIN.offset(toOffset);
        return "fill " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + to.getX() + " " + to.getY() + " "
                + to.getZ() + " " + block;
    }

    private static LocalPlayer requirePlayer(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player == null)
            throw new VisualTestException("no local player");
        return player;
    }
}
