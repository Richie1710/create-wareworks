package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlock;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionGoggleSummary;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.StoppedProduct;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.terminal.PlanCancelCost;
import dev.wareworks.core.terminal.PlanLine;
import dev.wareworks.core.terminal.PlanLines;
import dev.wareworks.core.terminal.PlanMember;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDisplaySources;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.createmod.catnip.math.Pointing;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "chain": a <b>recursive order</b> running in a real factory ({@code docs/warehouse-system.md} §3.5.6,
 * ADR-032, M20, issue #4) — order an item whose ingredient is itself only producible, and watch the whole chain work.
 * <p>
 * One aisle holds a powered stacker crane, a warehouse terminal and <b>two</b> production stations, each with a machine
 * of the player's own behind it: a Create Mechanical Arm that carries whatever the station holds into a Create
 * Mechanical Crafter whose arrow points straight into a warehouse input. The first machine turns one oak log into
 * {@value #PLANKS_PER_LOG} oak planks, the second turns each of those planks into an oak button. Nothing in the
 * warehouse crafts anything: the crane fetches, the player's machine works, and the result comes back through an input
 * like any other delivery (ADR-024).
 * <p>
 * The run tells one story in four chapters, and every claim a screenshot makes is asserted on the server (or on the
 * client block entity that draws it) <b>before</b> the shot that shows it:
 * <ol>
 *   <li><b>the chain</b> — {@value #ORDER_AMOUNT} oak buttons are ordered with a real click in the terminal's grid at a
 *       moment when the aisle holds <b>no</b> planks at all. The click creates the whole plan in one tick: the root
 *       order at the button machine and its step at the plank machine, which names the very ingredient line it feeds.
 *       The crane serves only the step — the station that is waiting for it is offered nothing and asks for nothing —
 *       the planks come back through an input and <b>are stored in a rack</b>, the crane fetches those very planks to
 *       the second machine, and the buttons are delivered to the terminal the click came from. An item census accounts
 *       for every log, plank and button;</li>
 *   <li><b>the failure</b> — the player's second machine loses its drive, and the same order is placed again. The saw
 *       still works, so the step completes and the crane hands the planks to a machine that cannot move: the root order
 *       times out with its ingredients gone, the plan ends, and the warehouse <b>stops making that item</b> until a
 *       player says otherwise (decision 2 of M20: the safety stop covers a player's own order too). The next click on
 *       the same item is refused with a sentence that says so, and the items are all still there — "unrecoverable"
 *       means nobody can get them out, not that they vanished;</li>
 *   <li><b>the way back</b> (M20 part 2) — a player walks to the machine that swallowed the batch. Its block burns the
 *       stopped lamp, its goggles name the item and what the loss cost, and its own screen carries the <b>stopped
 *       row</b>. A real click on that row is the way back: the warehouse makes the item again, the chat line says what
 *       the stop cost, the lamp goes out and the aisle's display board drops its paused line. Nothing else moves — a
 *       resume lifts a stop, it does not re-order anything;</li>
 *   <li><b>the chain again</b> — the player's machine gets its drive back, and the batch it had swallowed comes back
 *       out by itself: the crafter finishes the planks it was holding and the buttons arrive through the input like any
 *       other delivery. The next click is served straight out of that windfall stock, and the click after it — with the
 *       racks empty again — plans and runs the <b>whole chain</b> a second time, from the log to the buttons at the
 *       terminal.</li>
 * </ol>
 * <b>Why this scenario needs a real player.</b> Like {@link RestockVisualScenario} it runs with
 * {@link VisualWorldProfile#playable}, because Create only draws a goggle tooltip for a non-spectator who looks at a
 * block within reach, and because every click of the run goes through the real input path into the open screen
 * ({@link ScreenInput}: the order in the grid, the chain's line, the panel's buttons, the station's stopped row).
 * <p>
 * <b>The aisle's fourth surface</b> is here as well: a five-by-three Create display board west of the controller, fed by
 * a display link on the controller itself. That link never pulls on its own ({@link #pull}): the scenario pulls it in
 * the very server tick a moment is observed and waits until the client board carries exactly those lines, so a board
 * shot can never race the crane that moves the warehouse on a second later.
 * <p>
 * <b>What part 1 of M20 did not have yet</b> is still visible in two places on purpose, because the wording belongs to
 * the surface task that owns those blocks: the goggle tooltip of the station that waits for a step says "Ingredients
 * still to fetch: 4" without a word about the step that is making them, and the controller and the display board both
 * call a plan-born pause a paused <b>rule</b> although this aisle has no rule at all.
 * <p>
 * <b>Four things this run found</b>, recorded here rather than changed: a terminal row still <b>offers an
 * intermediate</b> the order above it has promised (a row's {@code available} subtracts the crane's reservations and the
 * open requests, while a request is granted out of {@code availableStock}, which also subtracts what open production
 * orders owe their stations); the controller's goggles count a plan-born pause as a paused rule; the refusal sentence is
 * <b>wider than the terminal's status row</b>, because it was written for a goggle tooltip on a warehouse port and only
 * an accepted line is ever shortened to fit; and <b>"unrecovered" is about the warehouse, not about the items</b> — a
 * machine that is given its drive back finishes the batch it swallowed and hands the result to the warehouse as stock
 * nobody ordered, which is exactly what chapter 4 photographs.
 * <p>
 * <b>How a chain is photographed at all</b> — two lessons that cost a run each: a chain <b>moves on by itself</b>, so
 * the claims that are about a <i>moment</i> (the waiting station, the intermediate in its rack) are asserted inside the
 * very server-thread call that first sees that moment, never in a step of their own that would race the crane; and what
 * the handling head holds is <b>server</b> state, so a client-side condition reads the crane's synced goggle info
 * instead. The crane and the machines run slowly, and every machine moment is shot with the ticks frozen.
 * <p>
 * The second render pass repeats the world shots only (a chain takes minutes and a GUI does not depend on Flywheel), so
 * its {@code nofw-} twins show the <b>end</b> state of the run rather than its beginning.
 */
public final class ChainVisualScenario implements VisualScenario {
    public static final String NAME = "chain";

    /** Its own throw-away world: a creative player with the vanilla reach, deleted and rebuilt on every run. */
    private static final String WORLD_FOLDER = "wareworks_visual_chain";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    /**
     * The crane and the machines run <b>slowly</b> on purpose. A chain is a sequence of moments — the log at the first
     * machine, the planks in a rack, the crane carrying them out of it — and every one of them is over in a second at
     * the speeds the other scenarios use, long before a camera has moved and a screen has refreshed. The shots that
     * must be exact are taken with the ticks frozen; the rest simply have room.
     */
    private static final int CRANE_RPM = 64;
    private static final int MACHINE_RPM = 32;

    /** The aisle letter a fresh controller starts with, which is what the rows of the step panel are addressed in. */
    private static final char AISLE_LETTER = StorageAddress.FIRST_AISLE;

    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    /** The first machine's crafter points into this input, so its planks come back like any other delivery. */
    private static final RackPosition SAW_INPUT = RackPosition.of(3, 0, Side.RIGHT);
    /** Where the crane drops the logs and the first arm picks them up. */
    private static final RackPosition SAW_STATION = RackPosition.of(5, 0, Side.RIGHT);
    /** The second machine's own input. */
    private static final RackPosition JOINER_INPUT = RackPosition.of(6, 0, Side.RIGHT);
    /** Where the crane drops the planks the first machine made. */
    private static final RackPosition JOINER_STATION = RackPosition.of(8, 0, Side.RIGHT);
    /** Aisle positions whose rack holds storage; all on the left, because the machines need the right side. */
    private static final List<Integer> STORAGE_LEFT = List.of(1, 2, 3, 4, 5, 6, 7, 8);
    private static final int STORAGE_LOCATIONS = 8;

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey BUTTON = ItemKey.of(Items.OAK_BUTTON);

    private static final int LOGS_IN_STOCK = 8;
    /** What the first machine really does (vanilla {@code minecraft:oak_planks}: one log makes four planks). */
    private static final int PLANKS_PER_LOG = 4;
    /** The second pattern's ingredients: one whole run of the first, so the chain has no surplus to hide in. */
    private static final int PLANKS_PER_RUN = 4;
    /** What the second machine really does, four times over (vanilla {@code minecraft:oak_button}). */
    private static final int BUTTONS_PER_RUN = 4;
    /** What one click asks for: exactly one run of the second pattern. */
    private static final int ORDER_AMOUNT = 4;
    /** Logs one order costs: one run of the first pattern per run of the second. */
    private static final int LOGS_PER_CHAIN = PLANKS_PER_RUN / PLANKS_PER_LOG;

    /**
     * The production order timeout of the failure half. Set once the step has completed, so the step itself always ran
     * on the generous default: from then on every event of the root order pushes its deadline by this much, and with a
     * machine that cannot move no event ever comes again.
     */
    private static final int FAILURE_ORDER_TIMEOUT = 1200;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int READY_TIMEOUT_TICKS = 400;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int DELIVERY_TIMEOUT_TICKS = 4800;
    /** One level of the chain: a crane trip, a machine cycle, a return through an input and a store trip. */
    private static final int LEVEL_TIMEOUT_TICKS = 7200;
    private static final int PAUSE_TIMEOUT_TICKS = 3600;
    private static final int SETTLE_TICKS = 6;
    /** Three chains through two real machines, several screens and a timeout: far above the harness's default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 60L * 60L * 1000L;
    /**
     * Polls between two state lines in the log while the scenario waits for a machine. A chain is minutes of real
     * machines, so a step that does not come is diagnosed from the log rather than from a screenshot.
     */
    private static final int STATE_LOG_INTERVAL = 100;
    /** Notches of the mouse wheel a scenario may spend on the amount input before it gives up. */
    private static final int MAX_SCROLL_NOTCHES = 32;

    // --- the aisle's display board (M14, §10; here it is the fourth surface of the safety stop) -----------------------

    /**
     * West of the controller, across the aisle line, which is the only part of this scene nothing else stands in: the
     * machines take the whole right-hand side and the storage the whole left-hand rack wall.
     * <p>
     * It faces <b>east</b>, down the aisle, so the camera that reads it stands west of the controller and looks away from
     * the warehouse: the display link and the redstone block that silences it are then behind the camera instead of
     * filling a third of every board shot.
     */
    private static final int BOARD_X = -6;
    /** North end of the board; its <b>controller</b> block is the southern one, which is where its cogwheel sits. */
    private static final int BOARD_Z = -2;
    /** Top row of the board, above the rack wall, with its cogwheel and motor one block higher still. */
    private static final int BOARD_TOP_Y = 3;
    /** Five blocks wide, i.e. 22 characters: room for the longest aisle summary line in both languages. */
    private static final int BOARD_WIDTH = 5;
    /**
     * Three blocks high, i.e. <b>six</b> lines. The aisle summary is four lines for a working warehouse and grows by one
     * while the safety stop holds something, and a target with too few rows would simply drop that line
     * ({@code WarehouseDisplays#limit}) — the one line this chapter is about.
     */
    private static final int BOARD_HEIGHT = 3;
    /**
     * Above 128 RPM Create flips a whole board in one tick instead of one flap at a time
     * ({@code FlapDisplayBlockEntity#tick}), so a shot taken right after a pull can never catch half-turned flaps.
     */
    private static final int BOARD_RPM = 192;
    /** Down the aisle, so the camera that reads the board has the controller and its link behind it. */
    private static final Direction BOARD_FACING = Direction.EAST;
    /** How far in front of the board's face the camera that reads it stands. */
    private static final double BOARD_CAMERA_DISTANCE = 3.0;

    // Cameras, relative to the lower corner of the dock block. The player is a real creature here, so every eye sits
    // at least 1.62 + 0.2 blocks above the floor: its feet must clear the floor and the 3-pixel rails.
    /** The whole factory from the machine side: the rack wall behind, both machines in front of it. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 10.6, 5.0, 8.6, 4.0, 1.0, 0.8);
    /** Both of the player's machines in one frame, the plank machine on the left and the button machine right. */
    private static final CameraView AT_MACHINES = CameraView.of("machines", 6.0, 3.0, 7.0, 5.6, 1.2, 2.2);
    /**
     * Close on the first machine, from the <b>west</b>: its crafter faces that way, and Create draws what a crafter
     * holds on its own face, so this is the only side from which a machine can be seen working.
     */
    private static final CameraView AT_SAW = CameraView.of("saw", 1.6, 2.5, 5.5, 3.6, 1.2, 2.2);
    /** Close on the second machine, from the <b>east</b>, where its crafter faces; the one that will break. */
    private static final CameraView AT_JOINER = CameraView.of("joiner", 10.0, 2.5, 4.6, 6.9, 1.2, 2.3);
    /** How far ahead of the carriage the frozen carry shot stands, how high above it, and where it looks. */
    private static final double CARRY_AHEAD = 1.9;
    private static final double CARRY_ABOVE = 2.4;
    private static final double CARRY_LOOK_HEIGHT = 0.55;
    /** In the aisle, two blocks from the terminal: inside the vanilla container range, so the screen stays open. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.6, 1.9, 0.45, 1.5, 0.8, 1.05);
    /** In the aisle in front of the station that waits for the step, close enough for its goggle tooltip. */
    private static final CameraView AT_JOINER_STATION = CameraView.of("joiner-station", 6.7, 1.9, 0.4, 8.2, 0.55, 1.02);
    /**
     * West of the controller, aimed at the <b>lower left corner</b> of its back face rather than at its middle: the
     * controller's aisle letter is a scroll value box on every face but the dock's, and a value box the crosshair
     * really hits makes Create's goggle overlay bail out before it draws a single line.
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);
    /**
     * Straight in front of the display board, far enough back that all five blocks fit into the frame and close enough
     * that the lines can be read. It stands west of the dock, so the crane's mast can never be between the two.
     */
    private static final CameraView AT_DISPLAY = CameraView.of("display",
            BOARD_X + 1.0 + BOARD_CAMERA_DISTANCE, BOARD_TOP_Y + 1.0 - BOARD_HEIGHT / 2.0, BOARD_Z + BOARD_WIDTH / 2.0,
            BOARD_X + 1.0, BOARD_TOP_Y + 1.0 - BOARD_HEIGHT / 2.0, BOARD_Z + BOARD_WIDTH / 2.0);

    /** The box every item census of this run counts, set once the scene origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene must hold, item for item; changed only where a machine really transformed something. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** The plan's root order (the buttons) and its step (the planks), remembered when the click created them. */
    @Nullable
    private volatile UUID rootId;
    @Nullable
    private volatile UUID stepId;
    /** The largest number of open production orders any poll ever saw: a chain of two is all this scene may have. */
    private volatile int maxOpenOrders;
    /** Planks handed to the machine that cannot move, kept for the assertions of the second half. */
    private volatile long handedOver;
    /**
     * Whether the two assertions that are made <b>inside</b> the poll that observes them have run.
     * <p>
     * A chain moves on by itself: the crane fetches the intermediate out of its rack a second after it was stored, and
     * the station that was empty is full a second later. So the two claims that are about a <b>moment</b> are asserted
     * in the same server-thread call that first sees that moment, where nothing can have moved in between, instead of
     * in a step of their own that would race the crane.
     */
    private volatile boolean waitingStationChecked;
    private volatile boolean throughRackChecked;
    /** Polls of {@link #pollChain} so far; every {@value #STATE_LOG_INTERVAL}th one writes the scene's whole state. */
    private volatile int polls;
    /**
     * The lines the display source wrote onto the board at the last pull, read back from the <b>server</b> board.
     * <p>
     * The link never pulls by itself ({@link #pull}), so this is the whole state of the board until the scenario pulls
     * it again: a client step can wait for exactly these lines, and a board shot taken minutes later still shows the
     * moment it was pulled in.
     */
    private volatile List<String> boardLines = List.of();
    /** What the machine that swallowed the batch had in it when the stop was lifted, for the third chain's census. */
    private volatile long rescued;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: goggles need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("chain: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "chain run"))
                .server("chain: clear the area and place the motors", this::placeMotors)
                .server("chain: build the aisle, its stations, the storage and the player's two machines",
                        this::buildScene)
                .serverUntil("chain: wait until the controller has every member and the logs", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("chain: build the aisle's display board and its link", ChainVisualScenario::buildDisplayBoard)
                .serverUntil("chain: wait until the display board is one board and turns fast enough",
                        ChainVisualScenario::boardReady, READY_TIMEOUT_TICKS)
                .server("chain: read the aisle onto the board", this::pullDisplay)
                .until("chain: wait until the client board carries what the source wrote", this::boardShowsPulled,
                        READY_TIMEOUT_TICKS)
                .server("chain: write the two production patterns that form the chain",
                        ChainVisualScenario::writePatterns)
                .serverUntil("chain: wait until the aisle knows what its machines make",
                        ChainVisualScenario::patternsLive, READY_TIMEOUT_TICKS)
                .server("chain: give both Mechanical Arms their interaction points",
                        ChainVisualScenario::teachTheArms)
                .serverUntil("chain: wait until both arms have resolved their station and their crafter",
                        ChainVisualScenario::armsReady, READY_TIMEOUT_TICKS)
                .server("chain: check that nothing can be made from thin air",
                        ChainVisualScenario::assertNoIntermediateInStock)
                .server("chain: count every item of the scene once", this::baselineCensus)

                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // it is lifted into the air first and only then made to fly.
                .server("chain: lift the player into the air", ChainVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("chain: the player flies and wears Engineer's Goggles", ChainVisualScenario::equipPlayer);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so the world shots
        // are taken by a player who reaches nothing at all.
        reach(script, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.camera(AT_MACHINES).shot("machines");
        if (pass != VisualPass.FLYWHEEL)
            return;

        orderChapter(script);
        chainChapter(script);
        failureChapter(script);
        resumeChapter(script);
        againChapter(script);
        script.client("chain: every check passed",
                context -> LOGGER.info(PREFIX + "chain: ALL CHECKS PASSED (the plan created by one click, the waiting "
                        + "station fetching nothing, the intermediate through a rack, the chain's own line and its step "
                        + "panel, the buttons delivered, the broken machine, the safety stop, the refusal that names it, "
                        + "the board that carries it, the way back at the machine itself and the whole chain run again "
                        + "afterwards)"));
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringBuilder status = new StringBuilder();
        if (level != null) {
            BlockPos dock = context.origin();
            if (level.getBlockEntity(controllerPos(dock)) instanceof WarehouseControllerBlockEntity controller) {
                ControllerGoggleSummary summary = controller.summary();
                status.append(String.format(Locale.ROOT, "orders=%d paused=%d items=%d", summary.productionOrders(),
                        summary.rulesPaused(), summary.itemTypes()));
            }
            if (level.getBlockEntity(stationPos(dock, JOINER_STATION))
                    instanceof WarehouseProductionBlockEntity joiner) {
                ProductionGoggleSummary summary = joiner.productionSummary();
                status.append(String.format(Locale.ROOT, " joiner=%dord/%s/%dmissing/%dawaited/%dstopped",
                        summary.openOrders(), summary.oldestState().map(Enum::name).orElse("-"),
                        summary.missingIngredients(), summary.awaitedResult(), summary.stoppedProducts()));
            }
            BlockState station = level.getBlockState(stationPos(dock, JOINER_STATION));
            if (station.hasProperty(WarehouseProductionBlock.STOPPED))
                status.append(" joinerLamp=").append(station.getValue(WarehouseProductionBlock.STOPPED));
            clientBoard(context).ifPresent(board -> status.append(" board='")
                    .append(String.join(" | ", boardLines(board)).trim()).append('\''));
            status.append(" sawCrafter=").append(describe(crafterItemOn(level, dock, SAW_INPUT)))
                    .append(" joinerCrafter=").append(describe(crafterItemOn(level, dock, JOINER_INPUT)));
        }
        status.append(GoggleShots.describeHover(context));
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            status.append(String.format(Locale.ROOT, " screen=terminal shown=%d requestsHere=%d orders=%d "
                            + "feedback='%s'", terminal.visibleEntries().size(), terminal.status().requestsHere(),
                    terminal.productionOrders().size(),
                    terminal.feedbackLine().map(Component::getString).orElse("")));
        else if (context.minecraft().screen != null)
            status.append(" screen=").append(context.minecraft().screen.getClass().getSimpleName());
        return status.toString();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "-" : stack.getCount() + "x" + ItemKey.of(stack);
    }

    // --- chapter 1: one click, one plan --------------------------------------------------------------------------------

    /**
     * The click that starts everything: an item the aisle holds none of, whose ingredient the aisle holds none of
     * either, ordered with a real left click on the terminal's grid cell. The plan is created in that one tick, and the
     * assertion that follows the shot is what the shot claims.
     */
    private void orderChapter(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen with its stock", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the button is offered although nothing can make one right now",
                        ChainVisualScenario::checkChainOffered)
                .client("chain: scroll the requested amount up to " + ORDER_AMOUNT,
                        ChainVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("chain: rest the cursor on the item that needs a whole chain",
                        context -> hoverCell(context, BUTTON))
                .waitTicks(SETTLE_TICKS)
                .shot("terminal-offer")
                .client("chain: click the item that needs a whole chain", context -> clickCell(context, BUTTON))
                .until("chain: wait until the terminal says the order was accepted",
                        ChainVisualScenario::producingShown, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check what the terminal answered", ChainVisualScenario::checkProducingLine)
                .shot("terminal-ordered")
                .server("chain: check that one click created the whole plan", this::assertPlanCreatedAtomically)
                .client("chain: click the chain's own line to open its step panel",
                        ChainVisualScenario::clickChainLine)
                .client("chain: move the cursor off the panel's text", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the panel names every step and the machine it runs at",
                        ChainVisualScenario::checkChainStepPanel)
                .shot("terminal-chain-steps")
                .client("chain: close the step panel with its own button",
                        ChainVisualScenario::clickStepPanelClose)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that only the panel was closed", ChainVisualScenario::checkStepPanelClosed)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    // --- chapter 2: the chain runs -------------------------------------------------------------------------------------

    /** The chain itself: the step first, through a rack, and only then the order that was waiting for it. */
    private void chainChapter(VisualScript script) {
        script.serverUntil("chain: wait until the crane delivered the log to the first machine (and check, in that "
                        + "very call, that the station waiting for the step is offered nothing)", this::logAtSaw,
                        DELIVERY_TIMEOUT_TICKS)
                .freeze(true)
                .camera(AT_SAW)
                .shot("saw-fed")
                .freeze(false);
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "chain", AT_JOINER_STATION, "goggles-waiting-station",
                dock -> stationPos(dock, JOINER_STATION), ChainVisualScenario::joinerWaitingSynced,
                ChainVisualScenario::checkWaitingStationGoggles);
        GoggleShots.shot(script, "chain", AT_CONTROLLER, "goggles-controller-chain",
                ChainVisualScenario::controllerPos, ChainVisualScenario::controllerChainSynced,
                ChainVisualScenario::checkChainControllerGoggles);
        reach(script, 0.0);
        script.serverUntil("chain: wait until the first machine has the log in it", this::sawWorking,
                        LEVEL_TIMEOUT_TICKS)
                .freeze(true)
                .camera(AT_SAW)
                .shot("saw-working")
                .freeze(false)
                // The screen is opened *before* the planks come back, so the shot of them lying in the warehouse
                // cannot race the crane that fetches them again a second later.
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .serverUntil("chain: wait until the planks came back and were stored in a rack (and check, in that "
                        + "very call, that they really are in one)", this::planksStored, LEVEL_TIMEOUT_TICKS)
                .until("chain: wait until the screen shows the planks in stock",
                        ChainVisualScenario::planksShownInStock, SCREEN_TIMEOUT_TICKS)
                .client("chain: check what the screen says about the planks",
                        ChainVisualScenario::checkPlanksInStockLine)
                .shot("terminal-intermediate")

                // The same screen, one line lower: the chain's own line has moved on with the chain — its step is done
                // and the work is at the machine that makes the ordered item now. The line is shot with the cursor off
                // it and then again with the cursor on it, because the tooltip a hover draws covers the very row it is
                // about.
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check the chain's line while it is working",
                        ChainVisualScenario::checkWorkingChainLine)
                .shot("terminal-chain-working")
                .client("chain: rest the cursor on the chain's line", ChainVisualScenario::hoverChainLine)
                .waitTicks(SETTLE_TICKS)
                .shot("terminal-chain-working-tooltip")
                .client("chain: open the step panel of the running chain", ChainVisualScenario::clickChainLine)
                .client("chain: move the cursor off the panel's text", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the panel moved on with the chain",
                        ChainVisualScenario::checkWorkingStepPanel)
                .shot("terminal-chain-steps-working")
                .client("chain: close the step panel with its own button",
                        ChainVisualScenario::clickStepPanelClose)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that only the panel was closed", ChainVisualScenario::checkStepPanelClosed)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)

                // Out of that rack and on to the second machine, with the ticks frozen so the trip can be photographed.
                .until("chain: wait until the crane carries the planks out of the rack",
                        ChainVisualScenario::craneCarriesPlanks, DELIVERY_TIMEOUT_TICKS)
                .freeze(true)
                .camera("carry", ChainVisualScenario::carryView)
                .shot("crane-carries-planks")
                .server("chain: check that the crane took the very planks that were in the rack",
                        this::assertCraneTookThePlanks)
                .freeze(false)
                .serverUntil("chain: wait until the crane fetched those very planks to the second machine",
                        this::planksAtJoiner, DELIVERY_TIMEOUT_TICKS)
                .freeze(true)
                .camera(AT_JOINER)
                .shot("joiner-fed")
                .freeze(false)
                .serverUntil("chain: wait until the second machine is working on them", this::joinerWorking,
                        LEVEL_TIMEOUT_TICKS)
                .freeze(true)
                .camera(AT_JOINER)
                .shot("joiner-working")
                .freeze(false)
                .serverUntil("chain: wait until the buttons were delivered to the terminal", this::buttonsDelivered,
                        LEVEL_TIMEOUT_TICKS)
                .serverUntil("chain: wait until the crane, the arms and the machines are empty again",
                        this::sceneSettled, LEVEL_TIMEOUT_TICKS)
                .server("chain: check the whole chain and count every item", this::assertChainRan)
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait until the screen shows the delivered buttons",
                        ChainVisualScenario::buttonsShownDelivered, SCREEN_TIMEOUT_TICKS)
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check what the screen shows after the chain",
                        ChainVisualScenario::checkDeliveredScreen)
                .shot("terminal-delivered")
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .shotFrom(OVERVIEW, "chain-done")

                // The board was pulled in the very server tick the planks were seen lying in a rack, and its link is
                // never pulls again by itself: this shot shows that moment however long the camera takes to get here,
                // while the world in the same frame has moved on to the finished chain.
                .until("chain: wait until the board carries the intermediate moment", this::boardShowsPulled,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: check what the board says about the aisle with the intermediate in it",
                        this::checkIntermediateDisplay)
                .camera(AT_DISPLAY)
                .shot("display-intermediate");
    }

    // --- chapter 3: the machine breaks --------------------------------------------------------------------------------

    /**
     * The same order again, with the second machine dead. The step still works, so the chain gets as far as handing the
     * planks over — and then the root order times out with them gone, the plan ends, and the warehouse stops making
     * that item for <b>everyone</b> until a player looks at the machine (decision 2 of M20).
     */
    private void failureChapter(VisualScript script) {
        script.server("chain: the player's second machine loses its drive",
                        ChainVisualScenario::breakTheJoinerMachine)
                .serverUntil("chain: wait until the second machine really stands still",
                        ChainVisualScenario::joinerMachineStopped, READY_TIMEOUT_TICKS)
                .camera(AT_JOINER)
                .shot("machine-dead")
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: scroll the requested amount up to " + ORDER_AMOUNT,
                        ChainVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("chain: order the same thing again", context -> clickCell(context, BUTTON))
                .until("chain: wait until the terminal says the order was accepted",
                        ChainVisualScenario::producingShown, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("terminal-ordered-again")
                .server("chain: check that a second chain was created", this::assertSecondChainCreated)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("chain: wait until the step completed again", this::secondStepComplete,
                        LEVEL_TIMEOUT_TICKS)
                .server("chain: shorten the production order timeout to " + FAILURE_ORDER_TIMEOUT + " ticks",
                        ChainVisualScenario::shortenProductionTimeout)
                .serverUntil("chain: wait until the dead machine has the planks", this::planksInDeadMachine,
                        DELIVERY_TIMEOUT_TICKS)
                .camera(AT_JOINER)
                .shot("planks-in-dead-machine")

                // The state a player really gives up in: a machine is holding the batch, the order above it is running
                // its deadline down, and the panel has to say what the click would cost *before* it is clicked. The
                // timeout is shortened only after this, so the screen cannot race it.
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: open the step panel while the dead machine holds the batch",
                        ChainVisualScenario::clickChainLine)
                .client("chain: move the cursor off the panel's text", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check what giving up costs while a machine holds the batch",
                        ChainVisualScenario::checkCostlyStepPanel)
                .shot("terminal-chain-steps-costly")
                .client("chain: press Escape, which drops the panel and not the screen",
                        ChainVisualScenario::pressEscape)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that only the panel was closed", ChainVisualScenario::checkStepPanelClosed)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)

                .serverUntil("chain: wait until the order gave up and the warehouse stopped making the item",
                        this::itemPaused, PAUSE_TIMEOUT_TICKS)
                .server("chain: check the safety stop", this::assertSafetyStop)
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: scroll the requested amount up to " + ORDER_AMOUNT,
                        ChainVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("chain: try to order it once more", context -> clickCell(context, BUTTON))
                .until("chain: wait until the terminal refuses it", ChainVisualScenario::refusalShown,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check the sentence the refusal shows", ChainVisualScenario::checkRefusalLine)
                .shot("terminal-refused")
                .server("chain: check that the refused click moved nothing at all", this::assertRefusedClickIsFree)

                // The chain that failed is still one line, and it is the line that says what it cost.
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check the line of the failed chain", ChainVisualScenario::checkFailedChainLine)
                .shot("terminal-chain-failed")
                .client("chain: rest the cursor on the line of the chain that failed",
                        ChainVisualScenario::hoverChainLine)
                .waitTicks(SETTLE_TICKS)
                .shot("terminal-chain-failed-tooltip")
                .client("chain: open the step panel of the chain that failed", ChainVisualScenario::clickChainLine)
                .client("chain: move the cursor off the panel's text", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the panel names the machine that swallowed the batch",
                        ChainVisualScenario::checkFailedStepPanel)
                .shot("terminal-chain-failed-steps")
                .client("chain: press Escape, which drops the panel and not the screen",
                        ChainVisualScenario::pressEscape)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that only the panel was closed", ChainVisualScenario::checkStepPanelClosed)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "chain", AT_CONTROLLER, "goggles-controller-paused",
                ChainVisualScenario::controllerPos, ChainVisualScenario::controllerPausedSynced,
                ChainVisualScenario::checkPausedControllerGoggles);
        reach(script, 0.0);
        script.server("chain: read the stopped aisle onto the board", this::pullDisplay)
                .until("chain: wait until the board carries the stop", this::boardShowsPulled, SCREEN_TIMEOUT_TICKS)
                .client("chain: check that the board says the warehouse stopped making something",
                        this::checkPausedDisplay)
                .camera(AT_DISPLAY)
                .shot("display-paused")
                .shotFrom(OVERVIEW, "after-failure");
    }

    // --- chapter 4: the way back, where the machine is ------------------------------------------------------------------

    /**
     * A player walks to the machine that swallowed the batch. Everything they need is on that one block: its lamp burns,
     * its goggles name the item and what the loss cost, and its screen carries the stopped row a click on makes the
     * warehouse try again (M20 part 2, {@code docs/warehouse-system.md} §3.5.4).
     * <p>
     * This is the way back that exists <b>whether or not</b> a stock keeper's rule governs the item — here no rule does,
     * so before M20 part 2 this stop had no control anywhere.
     */
    private void resumeChapter(VisualScript script) {
        script.server("chain: check that the station in front of the dead machine reports the stop",
                        this::assertStationStopped)
                .camera(AT_JOINER_STATION)
                .shot("station-stopped-block");
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "chain", AT_JOINER_STATION, "goggles-station-stopped",
                dock -> stationPos(dock, JOINER_STATION), ChainVisualScenario::stationStoppedSynced,
                ChainVisualScenario::checkStoppedStationGoggles);
        reach(script, 0.0);
        script.camera(AT_JOINER_STATION)
                .server("chain: open the screen of the station that lost the batch",
                        ChainVisualScenario::openStationScreen)
                .until("chain: wait for the station screen", ChainVisualScenario::stationScreenReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: move the cursor off the station's rows", ChainVisualScenario::hoverAsideStation)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check the stopped row and what its tooltip says",
                        ChainVisualScenario::checkStoppedRow)
                .shot("station-stopped-screen")
                .client("chain: rest the cursor on the stopped row", ChainVisualScenario::hoverStoppedRow)
                .waitTicks(SETTLE_TICKS)
                .shot("station-stopped-tooltip")

                // The click that lifts it, and the sentence a player is answered with.
                .client("chain: clear the chat before the resume", ChainVisualScenario::clearChat)
                .client("chain: click the stopped row", ChainVisualScenario::clickStoppedRow)
                .serverUntil("chain: wait until the warehouse makes the item again", ChainVisualScenario::itemResumed,
                        SCREEN_TIMEOUT_TICKS)
                .until("chain: wait until the station's screen has dropped its stopped row",
                        ChainVisualScenario::stationScreenClear, SCREEN_TIMEOUT_TICKS)
                .until("chain: wait until the chat carries what the resume cost",
                        ChainVisualScenario::resumedMessageShown, SCREEN_TIMEOUT_TICKS)
                .client("chain: check the sentence the resume answered with",
                        ChainVisualScenario::checkResumedMessage)
                .server("chain: check that the stop is gone and that nothing else moved", this::assertResumed)
                .client("chain: move the cursor off the station's rows", ChainVisualScenario::hoverAsideStation)
                .waitTicks(SETTLE_TICKS)
                .shot("station-resumed-screen")
                .client("chain: close the station screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the station screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .until("chain: wait until the client sees the lamp go out", ChainVisualScenario::lampOutOnClient,
                        SCREEN_TIMEOUT_TICKS)
                .camera(AT_JOINER_STATION)
                .shot("station-lamp-out");
        // The fourth surface of the machine, after the click: its goggles have to have dropped the stop as well, and
        // saying so needs the tooltip drawn again rather than the block state alone.
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "chain", AT_JOINER_STATION, "goggles-station-resumed",
                dock -> stationPos(dock, JOINER_STATION), ChainVisualScenario::stationResumedSynced,
                ChainVisualScenario::checkResumedStationGoggles);
        reach(script, 0.0);
        script.server("chain: read the aisle that makes the item again onto the board", this::pullDisplay)
                .until("chain: wait until the board dropped the stop", this::boardShowsPulled, SCREEN_TIMEOUT_TICKS)
                .client("chain: check that the board says nothing about a stop any more", this::checkResumedDisplay)
                .camera(AT_DISPLAY)
                .shot("display-resumed");
    }

    // --- chapter 5: the chain runs again --------------------------------------------------------------------------------

    /**
     * The player's machine gets its drive back, and the chain runs a second time.
     * <p>
     * Two things happen in this order, and both are photographed. First the machine <b>finishes the batch it swallowed</b>
     * — the crafter turns the planks it was still holding into buttons and the input hands them to the warehouse like any
     * other delivery. That is what "the warehouse cannot recover them" really means: the items were never destroyed, they
     * were only out of the warehouse's reach, and they come back as stock nobody ordered. Then the next click is served
     * straight out of that windfall, and the click after it — with the racks empty again — plans and runs the whole chain
     * from the log to the buttons.
     */
    private void againChapter(VisualScript script) {
        script.server("chain: the player's second machine gets its drive back",
                        ChainVisualScenario::repairTheJoinerMachine)
                .serverUntil("chain: wait until the second machine turns again",
                        ChainVisualScenario::joinerMachineRunning, READY_TIMEOUT_TICKS)
                .camera(AT_JOINER)
                .shot("machine-repaired")
                .serverUntil("chain: wait until the swallowed batch has come back as buttons", this::batchCameBack,
                        LEVEL_TIMEOUT_TICKS)
                .server("chain: check what the repaired machine gave back", this::assertBatchCameBack)
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait until the screen shows the buttons the machine gave back",
                        ChainVisualScenario::windfallShownInStock, SCREEN_TIMEOUT_TICKS)
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check what the screen says about them", ChainVisualScenario::checkWindfallLine)
                .shot("terminal-windfall")

                // The item is orderable again, and this one is served out of stock: the refusal is really gone.
                .client("chain: scroll the requested amount up to " + ORDER_AMOUNT,
                        ChainVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("chain: order it again now that the warehouse has some",
                        context -> clickCell(context, BUTTON))
                .until("chain: wait until the terminal accepts it", ChainVisualScenario::requestedShown,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the terminal answered with a plain request",
                        ChainVisualScenario::checkRequestedLine)
                .shot("terminal-from-stock")
                .server("chain: check that the click was served out of stock and made nothing",
                        this::assertServedFromStock)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("chain: wait until those buttons were delivered and the racks are empty again",
                        this::windfallDelivered, DELIVERY_TIMEOUT_TICKS)

                // And now the whole chain again, from a warehouse that holds nothing but logs.
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: check that neither the item nor its ingredient is in stock again",
                        ChainVisualScenario::checkChainOfferedAgain)
                .client("chain: scroll the requested amount up to " + ORDER_AMOUNT,
                        ChainVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("chain: order the chain a third time", context -> clickCell(context, BUTTON))
                .until("chain: wait until the terminal says the order was accepted",
                        ChainVisualScenario::producingShown, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("terminal-ordered-third")
                .server("chain: check that a whole chain was planned again", this::assertThirdChainCreated)
                .client("chain: open the step panel of the new chain", ChainVisualScenario::clickChainLine)
                .client("chain: move the cursor off the panel's text", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that the panel names every step again",
                        ChainVisualScenario::checkChainStepPanel)
                .shot("terminal-chain-again-steps")
                .client("chain: close the step panel with its own button",
                        ChainVisualScenario::clickStepPanelClose)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check that only the panel was closed", ChainVisualScenario::checkStepPanelClosed)
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("chain: wait until the repaired machine is working on the new planks",
                        this::joinerWorking, LEVEL_TIMEOUT_TICKS)
                .freeze(true)
                .camera(AT_JOINER)
                .shot("joiner-working-again")
                .freeze(false)
                .serverUntil("chain: wait until the third chain finished and the scene is at rest",
                        this::thirdChainDone, LEVEL_TIMEOUT_TICKS)
                .server("chain: check the whole chain again and count every item", this::assertChainRanAgain)
                .shotFrom(OVERVIEW, "chain-again-done")
                .camera(AT_TERMINAL)
                .server("chain: open the terminal screen", ChainVisualScenario::openTerminalScreen)
                .until("chain: wait for the terminal screen", ChainVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .client("chain: move the cursor off the grid", ChainVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .client("chain: check what the screen shows after the second chain",
                        ChainVisualScenario::checkDeliveredScreen)
                .shot("terminal-delivered-again")
                .client("chain: close the terminal screen", ChainVisualScenario::closeScreen)
                .until("chain: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    // --- build (server thread) ----------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = sceneBounds(dock);
        Direction right = AISLE.getClockWise();
        for (BlockPos pos : BlockPos.betweenClosed(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN + 4).above(CLEAR_HEIGHT)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        BlockState motorUp = AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP);
        level.setBlockAndUpdate(dock.below(), motorUp);
        level.setBlockAndUpdate(armMotorPos(dock, SAW_STATION), motorUp);
        level.setBlockAndUpdate(armMotorPos(dock, JOINER_STATION), motorUp);
        // A Mechanical Crafter turns on the axis of its facing, so its cogwheel sits on top of it and the motor beside
        // that cogwheel, along the same axis.
        BlockState motorWest = AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE.getOpposite());
        level.setBlockAndUpdate(crafterMotorPos(dock, SAW_INPUT), motorWest);
        level.setBlockAndUpdate(crafterMotorPos(dock, JOINER_INPUT), motorWest);
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION))
            motor(level, armMotorPos(dock, station)).generatedSpeed.setValue(MACHINE_RPM);
        for (RackPosition input : List.of(SAW_INPUT, JOINER_INPUT))
            motor(level, crafterMotorPos(dock, input)).generatedSpeed.setValue(MACHINE_RPM);

        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        for (RackPosition input : List.of(SAW_INPUT, JOINER_INPUT))
            level.setBlockAndUpdate(layout.rackPos(input), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                    .setValue(WarehouseInputBlock.FACING, inward(layout, input)));
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION))
            level.setBlockAndUpdate(layout.rackPos(station), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                    .setValue(WarehouseProductionBlock.FACING, inward(layout, station)));

        buildStorage(level, layout);
        // Each crafter faces away from the other machine: a Mechanical Crafter draws what it holds on its own face,
        // and a face that looks at the next machine along the aisle can never be photographed.
        buildMachine(level, dock, SAW_INPUT, SAW_STATION, AISLE.getOpposite());
        buildMachine(level, dock, JOINER_INPUT, JOINER_STATION, AISLE);
    }

    /** Storage behind the left rack plane: barrels rather than chests, so nothing ever merges into a double chest. */
    private static void buildStorage(ServerLevel level, BranchLayout layout) {
        Direction outward = layout.sideDirection(Side.LEFT);
        for (int x : STORAGE_LEFT) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.LEFT));
            level.setBlockAndUpdate(rack.relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        // The only thing the warehouse starts with: logs. Neither the intermediate nor the ordered item exists.
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.getFirst(), 0, Side.LEFT)).relative(outward),
                LOG.toStack(LOGS_IN_STOCK));
    }

    /**
     * One of the player's machines: a Mechanical Arm that takes whatever its production station holds and puts it into a
     * Mechanical Crafter whose arrow points straight into a warehouse input. No warehouse block changes an item
     * anywhere in this chain — the arm and the crafter are Create's, and the input takes the result like any other
     * delivery (ADR-024).
     */
    private static void buildMachine(ServerLevel level, BlockPos dock, RackPosition input, RackPosition station,
            Direction crafterFacing) {
        level.setBlockAndUpdate(crafterPos(dock, input), crafterState(crafterFacing, towardsInput()));
        level.setBlockAndUpdate(crafterCogPos(dock, input), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(armPos(dock, station), AllBlocks.MECHANICAL_ARM.getDefaultState());
        level.setBlockAndUpdate(armCogPos(dock, station), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
    }

    /** The crafter block state whose arrow points at {@code target} (looked up through Create, as the showcase does). */
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

    /** From a crafter back into its warehouse input, i.e. inwards across the rack plane. */
    private static Direction towardsInput() {
        return AISLE.getClockWise().getOpposite();
    }

    // --- the display board (server thread) ----------------------------------------------------------------------------

    /**
     * The aisle's display board, its cogwheel and motor, and the display link that feeds it from the controller itself
     * ({@code docs/warehouse-system.md} §10, M14). Positions are written as plain offsets from the dock, as in
     * {@link DisplayVisualScenario}, because {@link #AISLE} is east here: {@code +x} runs along the aisle and {@code -z}
     * is the left rack side.
     */
    private static void buildDisplayBoard(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            BlockState state = AllBlocks.DISPLAY_BOARD.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, BOARD_FACING)
                    .setValue(FlapDisplayBlock.UP, row > 0).setValue(FlapDisplayBlock.DOWN, row < BOARD_HEIGHT - 1);
            for (int i = 0; i < BOARD_WIDTH; i++)
                level.setBlockAndUpdate(dock.offset(BOARD_X, BOARD_TOP_Y - row, BOARD_Z + i), state);
        }
        // A display board turns on the axis of its own facing, so its cogwheel sits on top of the controller block and
        // the motor beside that cogwheel, along the same axis.
        BlockPos cog = boardPos(dock).above();
        level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, BOARD_FACING.getAxis()));
        BlockPos motor = cog.relative(BOARD_FACING.getOpposite());
        level.setBlockAndUpdate(motor,
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, BOARD_FACING));
        motor(level, motor).generatedSpeed.setValue(BOARD_RPM);
        board(level, dock).updateControllerStatus();
        attachDisplayLink(level, dock);
    }

    /** The display link on top of the controller, pointed at the board and pulled once so it finds its target. */
    private static void attachDisplayLink(ServerLevel level, BlockPos dock) {
        BlockPos controller = controllerPos(dock);
        BlockPos pos = displayLinkPos(dock);
        level.setBlockAndUpdate(pos,
                AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, Direction.UP));
        DisplayLinkBlockEntity link = link(level, dock);
        if (!link.getSourcePosition().equals(controller))
            throw new VisualTestException("the display link at " + pos + " reads " + link.getSourcePosition()
                    + " instead of the controller at " + controller);
        if (!DisplaySource.getAll(level, controller).contains(aisleSummary()))
            throw new VisualTestException("the controller does not offer the display source "
                    + WareworksDisplaySources.AISLE_SUMMARY.getId());
        link.target(boardPos(dock));
        link.targetLine = 0;
        pull(level, dock);
        if (link.activeTarget == null)
            throw new VisualTestException("the display board at " + boardPos(dock) + " does not accept display text");
        LOGGER.info(PREFIX + "chain: the display link at {} reads the controller at {} onto the board at {}", pos,
                controller, boardPos(dock));
    }

    /** One board of the right size that really turns, so a pull is drawn in the tick it is made. */
    private static boolean boardReady(MinecraftServer server, VisualContext context) {
        FlapDisplayBlockEntity board = board(server.overworld(), context.origin());
        return board.isController && board.xSize == BOARD_WIDTH && board.ySize == BOARD_HEIGHT
                && board.isSpeedRequirementFulfilled();
    }

    /**
     * Reads the aisle onto the board <b>now</b> and remembers the lines the source wrote, so a client step can wait for
     * exactly those and a shot can never race the warehouse.
     */
    private void pullDisplay(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        pull(level, dock);
        boardLines = boardLines(board(level, dock));
        LOGGER.info(PREFIX + "chain: the board now reads {}", boardLines);
    }

    /**
     * One pull, and only when this scenario says so: the link is given its source for that one call and has it taken
     * away again, because a link without a source does nothing at all in its own tick
     * ({@code DisplayLinkBlockEntity#tick}).
     * <p>
     * A chain <b>moves on by itself</b>, and the moment this board has to carry — the intermediate lying in a rack — is
     * over in a few seconds, long before a camera has moved. A passive pull landing in between would photograph the
     * wrong warehouse. Create's own way of saying "only when I say so" is a <b>powered</b> link, but that needs a
     * redstone block within two blocks of the controller, and every such place is in the frame of one of this
     * scenario's cameras: a bright red block in the corner of half the shots costs more than it explains.
     */
    private static void pull(ServerLevel level, BlockPos dock) {
        DisplayLinkBlockEntity link = link(level, dock);
        link.activeSource = aisleSummary();
        link.updateGatheredData();
        link.activeSource = null;
        link.notifyUpdate();
    }

    private static DisplaySource aisleSummary() {
        return WareworksDisplaySources.AISLE_SUMMARY.get();
    }

    /** Client: the board really carries the lines the last pull wrote. */
    private boolean boardShowsPulled(VisualContext context) {
        List<String> wanted = boardLines;
        return !wanted.isEmpty() && clientBoard(context).filter(board -> boardLines(board).equals(wanted)).isPresent();
    }

    /** The text of every line of a board, in order, as the display source wrote it (before the flaps uppercase it). */
    private static List<String> boardLines(FlapDisplayBlockEntity board) {
        List<String> lines = new ArrayList<>();
        for (FlapDisplayLayout line : board.getLines()) {
            StringBuilder text = new StringBuilder();
            for (FlapDisplaySection section : line.getSections())
                text.append(section.getText() == null ? "" : section.getText().getString());
            lines.add(text.toString());
        }
        return lines;
    }

    private static Optional<FlapDisplayBlockEntity> clientBoard(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(boardPos(context.origin())) instanceof FlapDisplayBlockEntity board
                ? Optional.of(board) : Optional.empty();
    }

    /**
     * Fails unless the client board carries a line containing {@code text}, and unless every line of it fits its flaps —
     * a board that cuts a line shows what no display source ever produced.
     */
    private static List<String> requireBoardLine(VisualContext context, String text) {
        FlapDisplayBlockEntity board = clientBoard(context)
                .orElseThrow(() -> new VisualTestException("no display board on the client"));
        List<String> lines = boardLines(board);
        if (lines.stream().noneMatch(line -> line.contains(text)))
            throw new VisualTestException("no line of the display board says '" + text + "': " + lines);
        for (FlapDisplayLayout line : board.getLines()) {
            for (FlapDisplaySection section : line.getSections()) {
                Component shown = section.getText();
                int flaps = (int) (section.getSize() / FlapDisplaySection.MONOSPACE);
                if (shown != null && shown.getString().trim().length() > flaps)
                    throw new VisualTestException("the board cuts '" + shown.getString().trim() + "' to " + flaps
                            + " characters (it is " + BOARD_WIDTH + " blocks wide)");
            }
        }
        return lines;
    }

    private static void requireNoBoardLine(VisualContext context, String text) {
        List<String> lines = clientBoard(context).map(ChainVisualScenario::boardLines)
                .orElseThrow(() -> new VisualTestException("no display board on the client"));
        if (lines.stream().anyMatch(line -> line.contains(text)))
            throw new VisualTestException("the display board still says '" + text + "': " + lines);
    }

    /**
     * The aisle as the board carried it in the tick the intermediate lay in a rack: two item types and every item of the
     * scene's warehouse, the planks among them. This is the whole warehouse-level evidence that an intermediate of a
     * chain really is ordinary stock (ADR-032) — a board reads nothing but the stock index.
     */
    private void checkIntermediateDisplay(VisualContext context) {
        long items = LOGS_IN_STOCK - LOGS_PER_CHAIN + PLANKS_PER_RUN;
        requireBoardLine(context, displayLine(WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES, 2));
        requireBoardLine(context, displayLine(WareworksLang.DISPLAY_AISLE_LINE_ITEMS, items));
        requireNoBoardLine(context, pausedDisplayLine(1));
        LOGGER.info(PREFIX + "chain: CHECK the board carried the moment the intermediate was in a rack: {} items of 2 "
                + "types, and not a word about a stop: {}", items, boardLines);
    }

    /**
     * The safety stop on the aisle's board — the one surface of this warehouse that names it without a player clicking
     * anything, because the aisle has no stock keeper at all (M15 review fix, still the count of a paused <i>rule</i>).
     */
    private void checkPausedDisplay(VisualContext context) {
        requireBoardLine(context, pausedDisplayLine(1));
        LOGGER.info(PREFIX + "chain: CHECK the board says the warehouse stopped making something although this aisle "
                + "has no stock rule at all: {}", boardLines);
    }

    /** After the click at the machine: the board has dropped the line entirely, because nothing is stopped any more. */
    private void checkResumedDisplay(VisualContext context) {
        requireNoBoardLine(context, pausedDisplayLine(1));
        requireBoardLine(context, displayLine(WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES, 1));
        LOGGER.info(PREFIX + "chain: CHECK the board dropped the stop line: {}", boardLines);
    }

    private static String displayLine(String key, long value) {
        return WareworksLang.translateDirect(key, WareworksLang.number(value)).getString();
    }

    private static String pausedDisplayLine(long paused) {
        return displayLine(WareworksLang.DISPLAY_AISLE_LINE_STOPPED, paused);
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        for (RackPosition input : List.of(SAW_INPUT, JOINER_INPUT)) {
            MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                    crafterPos(dock, input));
            if (crafter == null || crafter.getSpeed() == 0.0F)
                return false;
        }
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION)) {
            ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos(dock, station));
            if (arm == null || arm.getSpeed() == 0.0F)
                return false;
        }
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == STORAGE_LOCATIONS
                && controller.inputStations().size() == 2
                // The terminal counts as an output as well (ADR-018), and it is the only one here.
                && controller.outputStations().size() == 1 && controller.productionStations().size() == 2
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.countOf(LOG) == LOGS_IN_STOCK;
    }

    /**
     * The two patterns that form the chain: what each of the player's machines really does. A pattern is the player's
     * own declaration — the warehouse never verifies it, which is precisely why the safety stop exists.
     */
    private static void writePatterns(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseProductionBlockEntity saw = station(level, dock, SAW_STATION);
        if (!saw.setPatternEntry(0, 0, LOG, 1)
                || !saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG))
            throw new VisualTestException("the pattern 1 oak log -> " + PLANKS_PER_LOG + " oak planks was refused");
        WarehouseProductionBlockEntity joiner = station(level, dock, JOINER_STATION);
        if (!joiner.setPatternEntry(0, 0, PLANK, PLANKS_PER_RUN)
                || !joiner.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BUTTON, BUTTONS_PER_RUN))
            throw new VisualTestException("the pattern " + PLANKS_PER_RUN + " oak planks -> " + BUTTONS_PER_RUN
                    + " oak buttons was refused");
    }

    private static boolean patternsLive(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.aislePatterns().size() == 2 && controller.producibleKeys().contains(PLANK)
                && controller.producibleKeys().contains(BUTTON);
    }

    /**
     * Gives both arms their interaction points through Create's own placement packet — the very message a player's click
     * sends after selecting blocks with a Mechanical Arm in hand ({@code ArmPlacementPacket#handle}). Selecting them
     * with real clicks is what {@link ArmVisualScenario} is for (M12); here the arms are the player's machines and not
     * the subject.
     */
    private static void teachTheArms(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ServerPlayer player = context.serverPlayer(server);
        teachArm(level, dock, player, SAW_STATION, SAW_INPUT);
        teachArm(level, dock, player, JOINER_STATION, JOINER_INPUT);
    }

    private static void teachArm(ServerLevel level, BlockPos dock, ServerPlayer player, RackPosition station,
            RackPosition input) {
        ArmInteractionPoint take = point(level, stationPos(dock, station), Mode.TAKE);
        ArmInteractionPoint deposit = point(level, crafterPos(dock, input), Mode.DEPOSIT);
        new ArmPlacementPacket(List.of(take, deposit), armPos(dock, station)).handle(player);
        LOGGER.info(PREFIX + "chain: the arm at {} takes from {} ({}) and deposits into {} ({})",
                armPos(dock, station), take.getPos(), take.getType(), deposit.getPos(), deposit.getType());
    }

    /** One interaction point of the primary type of the block at {@code pos}, in the wanted mode. */
    private static ArmInteractionPoint point(ServerLevel level, BlockPos pos, Mode mode) {
        ArmInteractionPoint point = ArmInteractionPoint.create(level, pos, level.getBlockState(pos));
        if (point == null)
            throw new VisualTestException("no Mechanical Arm interaction point for the block at " + pos + ": "
                    + level.getBlockState(pos));
        if (point.getMode() != mode)
            point.cycleMode();
        if (point.getMode() != mode)
            throw new VisualTestException("the interaction point at " + pos + " cannot be put into mode " + mode);
        return point;
    }

    /**
     * Both arms have resolved their points into their own lists and really turn. Reading the lists is the only proof the
     * packet arrived, and the speed is what tells a dead kinetic connection apart from a slow machine — without it a
     * standing arm would only show up as an order that times out two minutes later.
     */
    private static boolean armsReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION)) {
            ArmBlockEntity arm = arm(level, dock, station);
            if ((Boolean) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_UPDATE, arm)
                    || armPoints(arm, ArmVisualScenario.Reflect.ARM_INPUTS).size() != 1
                    || armPoints(arm, ArmVisualScenario.Reflect.ARM_OUTPUTS).size() != 1 || arm.getSpeed() == 0.0F)
                return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<ArmInteractionPoint> armPoints(ArmBlockEntity arm, Field field) {
        return (List<ArmInteractionPoint>) ArmVisualScenario.Reflect.get(field, arm);
    }

    private static ItemStack armHeld(ArmBlockEntity arm) {
        return (ItemStack) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_HELD, arm);
    }

    /**
     * The starting point the whole feature is about: the aisle can <b>make</b> both items and holds <b>neither</b>, and
     * the single-level answer for the ordered item is therefore 0 — which is exactly the click that used to be refused
     * with "not in stock" (M11) and now plans a chain.
     */
    private static void assertNoIntermediateInStock(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        if (controller.countOf(PLANK) != 0L || controller.countOf(BUTTON) != 0L)
            throw new VisualTestException("the aisle already holds " + controller.countOf(PLANK) + " planks and "
                    + controller.countOf(BUTTON) + " buttons; the chain would have nothing to do");
        if (controller.producibleAmount(BUTTON) != 0L)
            throw new VisualTestException("the ordered item reports " + controller.producibleAmount(BUTTON)
                    + " producible at a single level, so this scene would not need a chain at all");
        if (controller.producibleAmount(PLANK) < PLANKS_PER_RUN)
            throw new VisualTestException("the intermediate reports only " + controller.producibleAmount(PLANK)
                    + " producible, expected at least " + PLANKS_PER_RUN);
        LOGGER.info(PREFIX + "chain: CHECK the start: {} logs, no planks, no buttons, and the ordered item is "
                        + "producible in {} step(s) only", LOGS_IN_STOCK, 2);
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * The player of a goggle shot: flying (so a camera view in mid-air holds), wearing Engineer's Goggles (Create asks
     * {@code GogglesItem.isWearingGoggles}, which reads the head slot) and holding nothing, so no item covers a corner
     * of a shot with the GUI shown.
     */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- the click ----------------------------------------------------------------------------------------------------

    /**
     * The item that needs a whole chain is offered, and offered honestly: the grid says the aisle can make it and that
     * it can make <b>none</b> of it right now, because the number in the grid is still the single-level one (part 2's
     * wording). A player clicks it anyway, and the server answers with the real answer.
     */
    private static void checkChainOffered(VisualContext context) {
        StockLine<ItemKey> button = line(context, BUTTON);
        if (!button.producible() || button.total() != 0L)
            throw new VisualTestException("the ordered item shows " + button.total() + " in stock, producible="
                    + button.producible() + "; expected an offered row with nothing in stock");
        if (button.producibleAmount() != 0L)
            throw new VisualTestException("the ordered item claims " + button.producibleAmount()
                    + " producible right now, but its ingredient does not exist yet");
        StockLine<ItemKey> plank = line(context, PLANK);
        if (plank.total() != 0L || plank.producibleAmount() < PLANKS_PER_RUN)
            throw new VisualTestException("the intermediate shows " + plank.total() + " in stock and "
                    + plank.producibleAmount() + " producible, expected none in stock and a run's worth producible");
        if (line(context, LOG).total() != LOGS_IN_STOCK)
            throw new VisualTestException("the logs show " + line(context, LOG).total() + " in stock, expected "
                    + LOGS_IN_STOCK);
        LOGGER.info(PREFIX + "chain: CHECK the offer: {} logs, no planks ({} producible), no buttons (0 producible "
                        + "at a single level) — and the button row is offered all the same", LOGS_IN_STOCK,
                plank.producibleAmount());
    }

    /**
     * Scrolls the request amount up to {@value #ORDER_AMOUNT} with real notches of the mouse wheel over Create's own
     * scroll input, which is the only way a player sets it.
     */
    private static void scrollAmount(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScrollInput amount = ScreenInput.terminalAmount(terminal);
        ScreenInput.Point point = ScreenInput.centre(amount);
        for (int notch = 0; notch < MAX_SCROLL_NOTCHES && amount.getState() != ORDER_AMOUNT; notch++)
            ScreenInput.scroll(context.minecraft(), point, amount.getState() < ORDER_AMOUNT ? 1.0 : -1.0);
        if (amount.getState() != ORDER_AMOUNT)
            throw new VisualTestException("the requested amount stayed at " + amount.getState() + " after "
                    + MAX_SCROLL_NOTCHES + " notches, expected " + ORDER_AMOUNT);
        LOGGER.info(PREFIX + "chain: the terminal asks for {} per click", amount.getState());
    }

    /** A plain left click on the grid cell of {@code key}, i.e. a request for the selected amount. */
    private static void clickCell(VisualContext context, ItemKey key) {
        WarehouseTerminalScreen terminal = screen(context);
        int cell = cellOf(context, key);
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
        LOGGER.info(PREFIX + "chain: clicked cell {} ({})", cell, key);
    }

    private static void hoverCell(VisualContext context, ItemKey key) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalCell(screen(context), cellOf(context, key)));
    }

    /** Moves the cursor onto the window's title, where the screen draws no tooltip at all. */
    private static void hoverAside(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(terminal.getGuiLeft() + 30.0, terminal.getGuiTop() + 5.0));
    }

    private static boolean producingShown(VisualContext context) {
        return screen(context).feedbackLine()
                .filter(line -> line.getString().contains(producingSentence())).isPresent();
    }

    /** "Requested Oak Button x4, producing 4" — what an accepted order that has to be made says (M11). */
    private static String producingSentence() {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCING, BUTTON.toStack().getHoverName(),
                LangNumberFormat.format(ORDER_AMOUNT), LangNumberFormat.format(ORDER_AMOUNT)).getString();
    }

    /**
     * Opens the step panel of the chain's line with a <b>real click</b> on the line itself (M20, issue #4), left of its
     * cancel mark — because that column gives up on the whole plan instead ({@link ScreenInput#terminalOrderLine}).
     * <p>
     * The line must be a chain: an ordinary order has no panel at all, which is the guarantee that a warehouse without
     * chains is the warehouse it was before M20.
     */
    private static void clickChainLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.click(context.minecraft(), ScreenInput.terminalOrderLine(terminal, chainLineIndex(terminal)));
        if (terminal.openStepPanelPlan().isEmpty())
            throw new VisualTestException("the click on the chain's line opened no step panel: "
                    + terminal.orderLines());
    }

    /** Rests the cursor on the chain's line, where the screen draws the line's own tooltip. */
    private static void hoverChainLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalOrderLine(terminal, chainLineIndex(terminal)));
    }

    /** The visible production line of the chain, newest first; a section without one is a failure, never a wait. */
    private static int chainLineIndex(WarehouseTerminalScreen terminal) {
        List<PlanLine> shown = terminal.visibleOrderLines();
        for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).isChain())
                return index;
        }
        throw new VisualTestException("no visible line of the production section is a chain: " + terminal.orderLines());
    }

    /** Closes the panel with its own "Close" button, which is one of the two things a player can do with it. */
    private static void clickStepPanelClose(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.click(context.minecraft(), ScreenInput.terminalStepButton(terminal, false));
    }

    /** Escape, which the step panel takes for itself: it drops the panel and leaves the screen open (M20). */
    private static void pressEscape(VisualContext context) {
        ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
    }

    /**
     * Only the panel is gone: {@link #screen} throws when the whole screen went with it, which is exactly what a Close
     * button and an Escape must not do here.
     */
    private static void checkStepPanelClosed(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.openStepPanelPlan().isPresent())
            throw new VisualTestException("the step panel is still up after it was closed");
        LOGGER.info(PREFIX + "chain: CHECK the step panel closed and the terminal screen stayed open");
    }

    /**
     * The chain's line while it is <b>working</b>: the step is done, so the line has moved on to the machine that makes
     * the ordered item, and the order that was blocked is no longer waiting for an earlier step.
     */
    private static void checkWorkingChainLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        PlanLine line = terminal.visibleOrderLines().get(chainLineIndex(terminal));
        if (line.members().size() != 2)
            throw new VisualTestException("the chain's line folds " + line.members().size()
                    + " orders, expected the two of this plan");
        if (!line.frontierIsHead())
            throw new VisualTestException("the line's frontier is still a step although the step is done: " + line);
        ProductionScreenState.OrderView head = terminal.productionOrders().stream()
                .filter(order -> order.id().equals(line.head())).findFirst()
                .orElseThrow(() -> new VisualTestException("the line names an order the screen does not have"));
        if (head.waitingForStep())
            throw new VisualTestException("the ordered item's own order still says it waits for a step: " + head);
        LOGGER.info(PREFIX + "chain: CHECK the chain's line moved on with the chain: one line of {} orders, the work at "
                + "the machine that makes {} ({}), nothing waiting for an earlier step", line.members().size(),
                head.result(), WareworksLang.translateDirect(head.state().langKey()).getString());
    }

    /**
     * The panel while the chain is working: both machines are still named, the step that is done says so, no row claims
     * to be waiting for an earlier step any more — and <b>what giving up would cost is what giving up would really
     * do</b>.
     * <p>
     * That last one is the defect this run found twice. The cost line first summed the ingredients of <i>every</i> member,
     * so a chain whose first step had completed reported the log that step had used as an item a cancellation would lose,
     * although it had come back out as the intermediate the warehouse now holds. The fix for that still counted every
     * <i>open</i> member, which is wrong in the other ordinary state — a step at its machine is detached and left
     * running, so it neither ends nor loses anything ({@link #checkCancelCost}). A chain is in one of those two states for
     * most of its life, so both were the usual reading rather than an edge case.
     */
    private static void checkWorkingStepPanel(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<String> rows = panelRows(terminal);
        requireAddresses(rows);
        requireRow(rows, WareworksLang.translateDirect(ProductionOrderState.COMPLETE.langKey()).getString());
        String waiting = WareworksLang.translateDirect(WareworksLang.PRODUCTION_WAITING_FOR_STEP).getString();
        requireNoRow(rows, waiting);
        PlanLine line = terminal.visibleOrderLines().get(chainLineIndex(terminal));
        if (line.openMembers() < 1)
            throw new VisualTestException("the chain has already finished, so this check is not about the moment it is "
                    + "working: " + line);
        checkCancelCost(terminal, line, rows);
        LOGGER.info(PREFIX + "chain: CHECK the panel moved on with the chain: {}", rows);
    }

    /**
     * The three cost lines say what {@code ProductionOrders#failPlan} would really do, and this works that out for itself
     * rather than asking {@code PlanCancelCost}: the orders that <b>end</b> are the one the click names (the line's head
     * while it is open, otherwise its frontier) plus every other open order with <b>nothing</b> at a machine, and the loss
     * is the named order's own batch — an order that already has ingredients in a machine is detached and left running,
     * so it neither ends nor loses anything. The safety-stop line appears exactly when that loss is not zero, because that
     * is the condition the server arms the stop under.
     */
    private static void checkCancelCost(WarehouseTerminalScreen terminal, PlanLine line, List<String> rows) {
        ProductionScreenState.OrderView named = namedByCancel(terminal, line);
        long open = 0L;
        long all = 0L;
        int ends = named == null ? 0 : 1;
        for (UUID member : line.members()) {
            ProductionScreenState.OrderView view = shownOrder(terminal, member);
            all += view.delivered();
            if (view.state().isFinished())
                continue;
            open += view.delivered();
            if (named != null && !view.id().equals(named.id()) && view.delivered() == 0L)
                ends++;
        }
        long lost = named == null ? 0L : named.delivered();
        requireRow(rows, WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_COST,
                LangNumberFormat.format(ends)).getString());
        if (ends != line.openMembers())
            requireNoRow(rows, WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_COST,
                    LangNumberFormat.format(line.openMembers())).getString());
        if (lost > 0L) {
            requireRow(rows, cancelLostRow(lost));
            requireRow(rows, stopWarningRow(named));
        } else if (named != null) {
            requireNoRow(rows, stopWarningRow(named));
        }
        if (all != lost)
            requireNoRow(rows, cancelLostRow(all));
        if (open != lost)
            requireNoRow(rows, cancelLostRow(open));
        LOGGER.info(PREFIX + "chain: CHECK giving up would end {} order(s) and lose {} ingredient item(s) — not the "
                + "{} open order(s), and not the {} items of every open order or the {} of every order", ends, lost,
                line.openMembers(), open, all);
    }

    /** The order a click on the cancel affordance really names: the head while it is open, otherwise the frontier. */
    @Nullable
    private static ProductionScreenState.OrderView namedByCancel(WarehouseTerminalScreen terminal, PlanLine line) {
        ProductionScreenState.OrderView head = shownOrder(terminal, line.head());
        if (!head.state().isFinished())
            return head;
        return line.frontier().map(id -> shownOrder(terminal, id))
                .filter(view -> !view.state().isFinished()).orElse(null);
    }

    /** The line that warns the safety stop will hold {@code named}'s product until a player resumes it. */
    private static String stopWarningRow(ProductionScreenState.OrderView named) {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_STOP,
                named.result().toStack().getHoverName()).getString();
    }

    private static String cancelLostRow(long items) {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_LOST,
                Component.literal(LangNumberFormat.format(items))).getString();
    }

    private static ProductionScreenState.OrderView shownOrder(WarehouseTerminalScreen terminal, UUID id) {
        return terminal.productionOrders().stream().filter(order -> order.id().equals(id)).findFirst()
                .orElseThrow(() -> new VisualTestException("the screen does not show the order " + id));
    }

    /**
     * <b>The panel in the state a player gives up in.</b> A machine is holding the batch of the order a click would end,
     * so all three cost lines are on the panel: the orders that would end, the ingredient items that were delivered and
     * never come back, and that the warehouse would <b>stop making the item</b> until somebody resumes it at the machine.
     * <p>
     * This is the one moment of the run in which the last two lines can be read at all, and it is the moment the review
     * found the panel lying about: it counted the open members, so a step at its machine was reported as ending and its
     * batch as lost, although the server detaches it and lets it finish ({@link #checkCancelCost}).
     */
    private static void checkCostlyStepPanel(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<String> rows = panelRows(terminal);
        requireAddresses(rows);
        PlanLine line = terminal.visibleOrderLines().get(chainLineIndex(terminal));
        ProductionScreenState.OrderView named = namedByCancel(terminal, line);
        if (named == null || named.delivered() <= 0L)
            throw new VisualTestException("this check is about the moment the order a click would end has its batch at "
                    + "a machine, but it is " + (named == null ? "finished" : named.delivered() + " item(s) in")
                    + ": " + line);
        checkCancelCost(terminal, line, rows);
        LOGGER.info(PREFIX + "chain: CHECK the panel says what giving up costs while the machine holds the batch: {}",
                rows);
    }

    /**
     * The panel of the chain that failed: it is the one surface that names <b>which machine</b> swallowed the batch and
     * how much it cost, and it still shows the step that did its job.
     */
    private static void checkFailedChainLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        PlanLine line = terminal.visibleOrderLines().get(chainLineIndex(terminal));
        if (line.openMembers() != 0 || line.frontier().isPresent())
            throw new VisualTestException("the failed chain still has " + line.openMembers()
                    + " open order(s): " + line);
        ProductionScreenState.OrderView head = shownOrder(terminal, line.head());
        if (head.state() != ProductionOrderState.TIMED_OUT || !head.lostIngredients())
            throw new VisualTestException("the line's own order is " + head.state() + " with " + head.delivered()
                    + " ingredient(s) handed over, expected a timed-out order that lost its batch");
        LOGGER.info(PREFIX + "chain: CHECK the failed chain is still one line, naming {} x{} as {} with {} ingredient "
                + "item(s) gone", head.result(), head.amount(), head.state(), head.delivered());
    }

    /**
     * The panel of the chain that failed: the machine that swallowed the batch is named by its address, its row says the
     * batch is not coming back, the step that did its job says it is complete — and giving up now would end nothing and
     * cost nothing, because there is nothing left to end.
     */
    private static void checkFailedStepPanel(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<String> rows = panelRows(terminal);
        requireAddresses(rows);
        requireRow(rows, WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_LOST,
                BUTTON.toStack().getHoverName(), LangNumberFormat.format(ORDER_AMOUNT),
                WareworksLang.translateDirect(ProductionOrderState.TIMED_OUT.langKey())).getString());
        requireRow(rows, WareworksLang.translateDirect(ProductionOrderState.COMPLETE.langKey()).getString());
        checkCancelCost(terminal, terminal.visibleOrderLines().get(chainLineIndex(terminal)), rows);
        LOGGER.info(PREFIX + "chain: CHECK the panel of the failed chain names the machine that swallowed the batch: {}",
                rows);
    }

    /** Every line of the open step panel as a player reads it. */
    private static List<String> panelRows(WarehouseTerminalScreen terminal) {
        List<String> rows = new ArrayList<>();
        for (Component line : terminal.stepPanelLines())
            rows.add(line.getString());
        if (rows.isEmpty())
            throw new VisualTestException("the step panel shows nothing at all");
        return rows;
    }

    /** Both machines of the chain are named by their rack address, which is what the panel exists for. */
    private static void requireAddresses(List<String> rows) {
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION))
            requireRow(rows, StorageAddress.of(AISLE_LETTER, station).format());
    }

    private static void requireRow(List<String> rows, String text) {
        requireText(rows, text, "row of the step panel");
    }

    private static void requireNoRow(List<String> rows, String text) {
        if (rows.stream().anyMatch(row -> row.contains(text)))
            throw new VisualTestException("a row of the step panel still says '" + text + "': " + rows);
    }

    /** Fails unless one of {@code lines} contains {@code text}; {@code what} names what those lines are. */
    private static void requireText(List<String> lines, String text, String what) {
        if (lines.stream().noneMatch(line -> line.contains(text)))
            throw new VisualTestException("no " + what + " says '" + text + "': " + lines);
    }

    /**
     * <b>The panel says which machine to walk to.</b> Every step of the chain is a row with the address of the station
     * it runs at, the order that is waiting says that it is waiting for an earlier <b>step</b> rather than looking like
     * a stuck crane, and the last line says what giving the chain up would cost.
     */
    private static void checkChainStepPanel(VisualContext context) {
        List<String> rows = panelRows(screen(context));
        if (rows.size() < 4)
            throw new VisualTestException("the step panel shows " + rows
                    + ", expected a title, both steps and what giving up would cost");
        requireAddresses(rows);
        requireRow(rows, WareworksLang.translateDirect(WareworksLang.PRODUCTION_WAITING_FOR_STEP).getString());
        LOGGER.info(PREFIX + "chain: CHECK the step panel names every machine of the chain: {}", rows);
    }

    private static void checkProducingLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        String line = terminal.feedbackLine().map(Component::getString).orElse("");
        if (!line.contains(producingSentence()))
            throw new VisualTestException("the terminal says '" + line + "', expected '" + producingSentence() + "'");
        if (!terminal.statusTextsFit())
            throw new VisualTestException("the status texts do not fit their rows: " + line);
        if (terminal.productionOrders().size() != 2)
            throw new VisualTestException("the screen shows " + terminal.productionOrders().size()
                    + " production order(s), expected the two of the chain");
        LOGGER.info(PREFIX + "chain: CHECK the terminal answered \"{}\" and lists {} orders", line,
                terminal.productionOrders().size());
    }

    /**
     * <b>One click, one plan, one tick.</b> The order at the machine that makes the ordered item and the step at the
     * machine that makes its ingredient both exist; the step names the very ingredient line of its parent that it
     * feeds; the step is nobody's errand (no backing request, nothing promised to anybody); and the planks are already
     * promised to that parent although not one plank exists — which is what makes acceptance the reservation.
     */
    private void assertPlanCreatedAtomically(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
        if (open.size() != 2)
            throw new VisualTestException("the click created " + open.size() + " order(s), expected the two of a chain");
        ProductionOrder<ItemKey, RackPosition> root = openRoot(controller);
        ProductionOrder<ItemKey, RackPosition> step = openStep(controller);
        rootId = root.id();
        stepId = step.id();
        if (!root.result().equals(BUTTON) || root.resultAmount() != ORDER_AMOUNT)
            throw new VisualTestException("the order is for " + root.resultAmount() + " " + root.result()
                    + ", expected " + ORDER_AMOUNT + " " + BUTTON);
        if (!step.result().equals(PLANK) || step.resultAmount() != PLANKS_PER_RUN)
            throw new VisualTestException("the step is for " + step.resultAmount() + " " + step.result()
                    + ", expected " + PLANKS_PER_RUN + " " + PLANK);
        if (!root.station().equals(JOINER_STATION) || !step.station().equals(SAW_STATION))
            throw new VisualTestException("the orders sit at " + root.station() + " and " + step.station()
                    + ", expected " + JOINER_STATION + " and " + SAW_STATION);
        UUID parentLine = step.parentLine()
                .orElseThrow(() -> new VisualTestException("the step names no parent line: " + step));
        SupplyLine<ItemKey> line = root.line(parentLine)
                .orElseThrow(() -> new VisualTestException("the step's parent line " + parentLine
                        + " is not a line of the order above it"));
        if (!line.key().equals(PLANK) || line.required() != PLANKS_PER_RUN)
            throw new VisualTestException("the step feeds the line " + line + ", expected " + PLANKS_PER_RUN + " "
                    + PLANK);
        if (step.backingRequest().isPresent() || step.promisedToRequest() != 0L)
            throw new VisualTestException("the step promises somebody something: " + step);
        if (root.backingRequest().isEmpty())
            throw new VisualTestException("the order nobody waits for: " + root);
        if (controller.openProductionStepCount() != 1)
            throw new VisualTestException(controller.openProductionStepCount() + " open steps, expected one");
        List<ProductionOrder<ItemKey, RackPosition>> fromRoot = controller.productionPlanOf(root.id());
        List<ProductionOrder<ItemKey, RackPosition>> fromStep = controller.productionPlanOf(step.id());
        if (fromRoot.size() != 2 || !ids(fromRoot).equals(ids(fromStep)))
            throw new VisualTestException("the plan is " + ids(fromRoot) + " from one end and " + ids(fromStep)
                    + " from the other");
        if (controller.availableStock(PLANK) != 0L)
            throw new VisualTestException("the planks the order will need are still offered to everybody: "
                    + controller.availableStock(PLANK));
        if (controller.availableStock(LOG) != LOGS_IN_STOCK - LOGS_PER_CHAIN)
            throw new VisualTestException("the step promised " + (LOGS_IN_STOCK - controller.availableStock(LOG))
                    + " log(s), expected " + LOGS_PER_CHAIN);
        if (controller.openRequestCount() != 1)
            throw new VisualTestException(controller.openRequestCount() + " open requests, expected the one the click "
                    + "made");
        // Nothing has moved yet: a plan is a set of promises, and the crane has not been anywhere.
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: the tick the plan was created");
        LOGGER.info(PREFIX + "chain: CHECK one click created the whole plan: {} {} at {} and its step of {} {} at {} "
                        + "naming the parent's own line, {} planks available to anybody else, {} log(s) promised",
                root.resultAmount(), root.result(), root.station(), step.resultAmount(), step.result(),
                step.station(), controller.availableStock(PLANK), LOGS_PER_CHAIN);
    }

    // --- the chain ----------------------------------------------------------------------------------------------------

    /**
     * The safety property of a chain, enforced on <b>every</b> poll while a step is open: the order that is waiting for
     * it is offered nothing at all, so the machine that cannot run yet is never handed a thing
     * ({@code ProductionOrders#hasOpenChildren}, ADR-032).
     */
    private void pollChain(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        maxOpenOrders = Math.max(maxOpenOrders, controller.openProductionOrders().size());
        if (polls++ % STATE_LOG_INTERVAL == 0)
            logChainState(level, dock, controller);
        boolean stepOpen = controller.openProductionOrders().stream().anyMatch(ProductionOrder::isStep);
        if (!stepOpen)
            return;
        long planks = station(level, dock, JOINER_STATION).bufferedItems().count(PLANK);
        if (planks != 0L)
            throw new VisualTestException("the station waiting for a step was handed " + planks
                    + " plank(s) while the step was still open");
        for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : controller.supplyNeeds()) {
            if (need.station().equals(JOINER_STATION))
                throw new VisualTestException("the order waiting for a step still asks for " + need.remaining() + " "
                        + need.key() + " at " + need.station());
        }
    }

    /**
     * One line with everything a stalled chain could be stalled on: where every item of the scene is, what the crane is
     * doing, what both machines hold and what every order thinks. This is the log a step that times out is read from.
     */
    private void logChainState(ServerLevel level, BlockPos dock, WarehouseControllerBlockEntity controller) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        StringBuilder orders = new StringBuilder();
        for (ProductionOrder<ItemKey, RackPosition> order : controller.productionOrders())
            orders.append(orders.isEmpty() ? "" : ", ").append(order.result()).append("x").append(order.resultAmount())
                    .append("@").append(order.station()).append(" ").append(order.state())
                    .append(order.isStep() ? " step" : " root").append(" delivered=")
                    .append(order.deliveredIngredients()).append(" produced=").append(order.produced());
        LOGGER.info(PREFIX + "chain: state: census={} storedPlanks={} sawStation={} sawInput={} sawCrafter={} "
                        + "sawArm={} joinerStation={} joinerInput={} joinerCrafter={} joinerArm={} crane={} held={} "
                        + "job={} stock(plank)={} available(plank)={} requests={} orders=[{}]",
                SceneItemCensus.isFullyLoaded(level, censusBox)
                        ? SceneItemCensus.describe(SceneItemCensus.take(level, censusBox)) : "not loaded",
                storedPlanks(level, dock), station(level, dock, SAW_STATION).bufferedItems(),
                input(level, dock, SAW_INPUT).bufferedItems(), describe(crafterItemOn(level, dock, SAW_INPUT)),
                describe(armHeld(arm(level, dock, SAW_STATION))),
                station(level, dock, JOINER_STATION).bufferedItems(),
                input(level, dock, JOINER_INPUT).bufferedItems(), describe(crafterItemOn(level, dock, JOINER_INPUT)),
                describe(armHeld(arm(level, dock, JOINER_STATION))),
                crane == null ? "none" : crane.craneState().phase(),
                crane == null ? "-" : crane.heldItems().entries().size(),
                crane == null ? "-" : crane.currentJob().map(Object::toString).orElse("none"),
                controller.countOf(PLANK), controller.availableStock(PLANK), controller.openRequestCount(), orders);
    }

    private boolean logAtSaw(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (station(level, dock, SAW_STATION).bufferedItems().count(LOG) < LOGS_PER_CHAIN
                && crafterItemOn(level, dock, SAW_INPUT).isEmpty())
            return false;
        if (!waitingStationChecked) {
            waitingStationChecked = true;
            assertWaitingStationFetchesNothing(server, context);
        }
        return true;
    }

    /**
     * What the shot shows, in numbers: the logs are at the first machine, the second machine's station holds nothing,
     * and the only thing the warehouse is fetching for is the step.
     */
    private void assertWaitingStationFetchesNothing(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        ProductionOrder<ItemKey, RackPosition> root = orderOf(controller, rootId);
        ProductionOrder<ItemKey, RackPosition> step = orderOf(controller, stepId);
        if (!step.isOpen())
            throw new VisualTestException("the step is already " + step.state() + "; this check is about the moment "
                    + "it is still working");
        if (!root.isOpen() || root.deliveredIngredients() != 0)
            throw new VisualTestException("the order above the step is " + root.state() + " with "
                    + root.deliveredIngredients() + " ingredient(s) delivered, expected an open order with none");
        long waiting = station(level, dock, JOINER_STATION).bufferedItems().totalItems();
        if (waiting != 0L)
            throw new VisualTestException("the station waiting for the step holds " + waiting + " item(s)");
        List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> needs = controller.supplyNeeds();
        for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : needs) {
            if (!need.station().equals(SAW_STATION))
                throw new VisualTestException("the warehouse is fetching for " + need.station() + " as well: " + needs);
        }
        if (controller.availableStock(PLANK) != 0L)
            throw new VisualTestException("the planks nobody has made yet are available again: "
                    + controller.availableStock(PLANK));
        LOGGER.info(PREFIX + "chain: CHECK the step runs alone: {} log(s) at {}, nothing at all at {}, and every "
                        + "supply need there is belongs to the step: {}",
                station(level, dock, SAW_STATION).bufferedItems().count(LOG), SAW_STATION, JOINER_STATION,
                needs.isEmpty() ? "none is left, its line is served" : needs.toString());
        assertCostOfGivingUpNow(controller, step, root);
    }

    /**
     * <b>The state a player really gives up in, priced.</b> This is the moment the step has its batch at the machine and
     * the blocked order above it has nothing: exactly the state a player opens the step panel in while a machine runs down
     * its timeout, and the one the review found the panel lying about.
     * <p>
     * Giving up now cancels the blocked root, <b>detaches</b> the step and leaves it running, so its planks still come
     * back: one order ends and nothing is lost. The panel summed the open members instead and said two orders and the
     * step's whole batch. It is checked here, on the live aisle, because this run never photographed the panel in this
     * state — every shot of it was taken with a frontier that had been handed nothing.
     */
    private void assertCostOfGivingUpNow(WarehouseControllerBlockEntity controller,
            ProductionOrder<ItemKey, RackPosition> step, ProductionOrder<ItemKey, RackPosition> root) {
        if (step.deliveredIngredients() <= 0)
            throw new VisualTestException("the step has nothing at its machine yet, so this is not the state this "
                    + "check is about: " + step);
        List<ProductionScreenState.OrderView> rows = controller.productionOrderViews(controller.openProductionOrders());
        List<PlanMember> members = ProductionScreenState.members(rows);
        List<PlanLine> lines = PlanLines.of(members);
        if (lines.size() != 1)
            throw new VisualTestException("the aisle's orders are " + lines.size() + " lines, expected the one chain");
        PlanCancelCost cost = PlanCancelCost.of(lines.getFirst(), members)
                .orElseThrow(() -> new VisualTestException("the running chain cannot be given up on at all"));
        if (!cost.target().equals(root.id()))
            throw new VisualTestException("a click would name " + cost.target() + ", expected the open root "
                    + root.id());
        if (cost.endedOrders() != 1 || cost.lostIngredients() != 0L || cost.armsSafetyStop())
            throw new VisualTestException("giving up now is priced at " + cost.endedOrders() + " order(s) and "
                    + cost.lostIngredients() + " lost item(s), expected exactly the blocked root and no loss: " + cost);
        LOGGER.info(PREFIX + "chain: CHECK giving up while the step has its {} item(s) at the machine would end 1 "
                + "order and lose nothing — the step is detached and still makes its planks",
                step.deliveredIngredients());
    }

    /**
     * The machine is really working: the log is in the crafter or on its way there in the arm's claw. Deliberately not
     * "planks exist" — by then the machine is done and the shot would show an empty crafter.
     */
    private boolean sawWorking(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return !crafterItemOn(level, dock, SAW_INPUT).isEmpty()
                || !armHeld(arm(level, dock, SAW_STATION)).isEmpty()
                || input(level, dock, SAW_INPUT).bufferedItems().count(PLANK) > 0L;
    }

    private boolean planksStored(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (storedPlanks(level, dock) < PLANKS_PER_RUN)
            return false;
        if (!throughRackChecked) {
            throughRackChecked = true;
            assertIntermediateThroughRack(server, context);
        }
        return true;
    }

    /**
     * <b>The beat that matters.</b> The planks the step made are lying in a real storage inventory before the second
     * machine has ever seen one: there is no hand-over between machines and no shortcut through the controller, and the
     * step is complete because those planks <b>arrived</b> (ADR-024, ADR-032).
     */
    private void assertIntermediateThroughRack(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = storedPlanks(level, dock);
        if (stored != PLANKS_PER_RUN)
            throw new VisualTestException("the racks hold " + stored + " planks, expected the " + PLANKS_PER_RUN
                    + " the step made");
        if (station(level, dock, JOINER_STATION).bufferedItems().count(PLANK) != 0L)
            throw new VisualTestException("the second machine already has planks, so they did not come out of a rack");
        ProductionOrder<ItemKey, RackPosition> step = orderOf(controller, stepId);
        if (step.state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the step is " + step.state() + ", expected "
                    + ProductionOrderState.COMPLETE);
        if (!step.isStep())
            throw new VisualTestException("the completed step lost its place in the plan: " + step);
        if (controller.countOf(PLANK) != PLANKS_PER_RUN)
            throw new VisualTestException("the index says " + controller.countOf(PLANK) + " planks, the racks hold "
                    + stored);
        if (controller.availableStock(PLANK) != 0L)
            throw new VisualTestException("the planks in the rack are offered to somebody else as well: "
                    + controller.availableStock(PLANK));
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -LOGS_PER_CHAIN), PLANK, PLANKS_PER_RUN);
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the first machine");
        // The board is read in this very tick, because this is the moment it has to carry: the aisle with the
        // intermediate in it. Its link never pulls again on its own, so the shot can be taken minutes later, after the
        // crane has fetched those planks out again.
        pullDisplay(server, context);
        LOGGER.info(PREFIX + "chain: CHECK the intermediate went through a rack: {} planks in storage, the step is {}, "
                + "and they are still promised to the order above it", stored, step.state());
    }

    /** Client: the crane really has the whole run of planks on its arm, on its way out of the rack. */
    private static boolean craneCarriesPlanks(VisualContext context) {
        // What the handling head really holds is server state; what a client has is the crane's synced goggle info,
        // which is also what the renderer draws the items on the arm from.
        return clientCrane(context).filter(crane -> crane.goggleInfo().held().stream()
                .anyMatch(entry -> entry.key() == Items.OAK_PLANKS && entry.count() >= PLANKS_PER_RUN)).isPresent();
    }

    /** In the aisle ahead of the crane, looking back at the carriage and the planks it carries. */
    private static CameraView carryView(VisualContext context) {
        CranePose pose = clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
        double craneX = 0.5 + AISLE.getStepX() * pose.x();
        double craneZ = 0.5 + AISLE.getStepZ() * pose.x();
        return CameraView.of("carry", craneX + AISLE.getStepX() * CARRY_AHEAD, pose.y() + CARRY_ABOVE,
                craneZ + AISLE.getStepZ() * CARRY_AHEAD, craneX, pose.y() + CARRY_LOOK_HEIGHT, craneZ);
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static long heldCount(StackerCraneBlockEntity crane, ItemKey key) {
        long total = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries()) {
            if (entry.key().equals(key))
                total += entry.count();
        }
        return total;
    }

    /**
     * The trip the shot shows, in numbers: the crane holds the whole run of planks and the racks hold none of them any
     * more. This is the one place in the run where the intermediate is in the open, between two machines, in the only
     * thing that ever carries it (ADR-024). Asserted while the ticks are frozen, so the picture and the numbers are
     * the same moment.
     */
    private void assertCraneTookThePlanks(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the stacker crane is missing");
        long held = heldCount(crane, PLANK);
        if (held != PLANKS_PER_RUN)
            throw new VisualTestException("the crane carries " + held + " planks, expected " + PLANKS_PER_RUN);
        if (storedPlanks(level, dock) != 0L)
            throw new VisualTestException("the racks still hold " + storedPlanks(level, dock)
                    + " planks, so these are not the ones that were stored");
        if (station(level, dock, JOINER_STATION).bufferedItems().count(PLANK) != 0L)
            throw new VisualTestException("the second machine's station already holds planks");
        LOGGER.info(PREFIX + "chain: CHECK the crane carries the intermediate out of the rack it was stored in: {} "
                + "planks on the arm, none left in the racks", held);
    }

    private boolean planksAtJoiner(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long atStation = station(level, dock, JOINER_STATION).bufferedItems().count(PLANK);
        if (atStation > 0L && storedPlanks(level, dock) + atStation > PLANKS_PER_RUN)
            throw new VisualTestException("more planks are in play than the step made: " + atStation + " at the "
                    + "station and " + storedPlanks(level, dock) + " in the racks");
        return atStation >= PLANKS_PER_RUN;
    }

    private boolean joinerWorking(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return !crafterItemOn(level, dock, JOINER_INPUT).isEmpty()
                || !armHeld(arm(level, dock, JOINER_STATION)).isEmpty()
                || input(level, dock, JOINER_INPUT).bufferedItems().count(BUTTON) > 0L;
    }

    private boolean buttonsDelivered(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        return terminal(server.overworld(), context.origin()).bufferedItems().count(BUTTON) >= ORDER_AMOUNT;
    }

    /** Nothing is in flight any more: no crane job, nothing in a claw and nothing in either machine. */
    private boolean sceneSettled(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null || !craneIdle(crane))
            return false;
        for (RackPosition station : List.of(SAW_STATION, JOINER_STATION)) {
            if (!armHeld(arm(level, dock, station)).isEmpty()
                    || station(level, dock, station).bufferedItems().totalItems() != 0L)
                return false;
        }
        for (RackPosition input : List.of(SAW_INPUT, JOINER_INPUT)) {
            if (!crafterItemOn(level, dock, input).isEmpty()
                    || input(level, dock, input).bufferedItems().totalItems() != 0L)
                return false;
        }
        return controller(level, dock).openProductionOrders().isEmpty()
                && SceneItemCensus.isFullyLoaded(level, censusBox);
    }

    /**
     * The whole chain in one assertion: both orders completed, the ordered items are at the terminal the click came
     * from, the intermediate is gone again because the second machine used all of it, it took exactly
     * {@value #LOGS_PER_CHAIN} log(s) and never more than two orders at once — and every item of the scene is
     * accounted for.
     */
    private void assertChainRan(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long delivered = terminal(level, dock).bufferedItems().count(BUTTON);
        if (delivered != ORDER_AMOUNT)
            throw new VisualTestException("the terminal holds " + delivered + " buttons, expected " + ORDER_AMOUNT);
        ProductionOrder<ItemKey, RackPosition> root = orderOf(controller, rootId);
        ProductionOrder<ItemKey, RackPosition> step = orderOf(controller, stepId);
        if (root.state() != ProductionOrderState.COMPLETE || step.state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the chain ended " + root.state() + " / " + step.state() + ", expected both "
                    + ProductionOrderState.COMPLETE);
        if (maxOpenOrders != 2)
            throw new VisualTestException("the chain ran with up to " + maxOpenOrders
                    + " open orders, expected exactly the two of the plan");
        if (controller.countOf(LOG) != LOGS_IN_STOCK - LOGS_PER_CHAIN)
            throw new VisualTestException("the chain spent " + (LOGS_IN_STOCK - controller.countOf(LOG))
                    + " logs, expected " + LOGS_PER_CHAIN);
        if (controller.countOf(PLANK) != 0L)
            throw new VisualTestException("the racks still hold " + controller.countOf(PLANK)
                    + " planks; the second machine was supposed to use the whole run");
        if (controller.openRequestCount() != 0)
            throw new VisualTestException(controller.openRequestCount() + " requests are still open");
        if (!controller.reservations().isEmpty())
            throw new VisualTestException("something stays reserved after the chain: " + controller.reservations());
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("the warehouse stopped making something although the chain completed");
        // The second machine turned the whole run of planks into buttons, so this is where the census changes again.
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, PLANK, -PLANKS_PER_RUN), BUTTON,
                BUTTONS_PER_RUN);
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the whole chain");
        LOGGER.info(PREFIX + "chain: CHECK the chain: {} log(s) became {} planks in a rack and then {} buttons at the "
                        + "terminal, in two orders and nothing more, and the census holds: {}", LOGS_PER_CHAIN,
                PLANKS_PER_RUN, delivered, SceneItemCensus.describe(expected));
    }

    // --- the failure --------------------------------------------------------------------------------------------------

    /**
     * The player's second machine breaks: its creative motor is gone, so the crafter no longer turns. The arm still
     * does, so the planks the crane brings really are handed over and really do disappear into a machine that will
     * never give anything back — which is the case {@code docs/warehouse-system.md} §3.5.4 is about.
     */
    private static void breakTheJoinerMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BlockPos motor = crafterMotorPos(dock, JOINER_INPUT);
        level.setBlockAndUpdate(motor, Blocks.AIR.defaultBlockState());
        LOGGER.info(PREFIX + "chain: the drive of the second machine at {} is gone", motor);
    }

    private static boolean joinerMachineStopped(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        MechanicalCrafterBlockEntity crafter = crafter(level, dock, JOINER_INPUT);
        return crafter.getSpeed() == 0.0F && arm(level, dock, JOINER_STATION).getSpeed() != 0.0F;
    }

    /** The second chain is a chain again, and it is new: two open orders, one of them a step, nothing reused. */
    private void assertSecondChainCreated(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
        if (open.size() != 2)
            throw new VisualTestException("the second click created " + open.size() + " open order(s), expected two");
        ProductionOrder<ItemKey, RackPosition> root = openRoot(controller);
        ProductionOrder<ItemKey, RackPosition> step = openStep(controller);
        if (root.id().equals(rootId) || step.id().equals(stepId))
            throw new VisualTestException("the second chain reused an order of the first one");
        if (step.parentLine().flatMap(root::line).isEmpty())
            throw new VisualTestException("the second chain's step does not name a line of its parent");
        rootId = root.id();
        stepId = step.id();
        LOGGER.info(PREFIX + "chain: CHECK the second chain was planned as well: {} {} with a step of {} {}",
                root.resultAmount(), root.result(), step.resultAmount(), step.result());
    }

    private boolean secondStepComplete(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return orderOf(controller, stepId).state() == ProductionOrderState.COMPLETE;
    }

    /**
     * Shortens {@code productionOrderTimeoutTicks} for the rest of the run, so a lost batch gives up inside a
     * screenshot run instead of after five minutes. It is set <b>after</b> the step completed, so the step itself always
     * had the generous default: from here on every event of the order above it pushes its deadline by this much, and a
     * machine that cannot move produces no more events.
     * <p>
     * {@code ConfigValue#set} writes into the loaded config in memory only (no file, no config event), exactly as the
     * GameTests' {@code ConfigOverrides} does.
     */
    private static void shortenProductionTimeout(MinecraftServer server, VisualContext context) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so the order timeout cannot be shortened");
        WareworksConfig.SERVER.productionOrderTimeoutTicks.set(FAILURE_ORDER_TIMEOUT);
        WareworksConfig.SERVER.productionOrderTimeoutTicks.clearCache();
        if (WareworksConfig.productionOrderTimeoutTicks() != FAILURE_ORDER_TIMEOUT)
            throw new VisualTestException("the order timeout stayed at "
                    + WareworksConfig.productionOrderTimeoutTicks() + " ticks");
    }

    /** The planks really are in the machine that cannot move: handed over by the crane and taken by the arm. */
    private boolean planksInDeadMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        ProductionOrder<ItemKey, RackPosition> root = orderOf(controller, rootId);
        handedOver = Math.max(handedOver, root.deliveredIngredients());
        if (root.deliveredIngredients() < PLANKS_PER_RUN)
            return false;
        // The arm has moved at least one plank on, so the machine really swallowed something rather than the station
        // holding it all.
        return !crafterItemOn(level, dock, JOINER_INPUT).isEmpty()
                || !armHeld(arm(level, dock, JOINER_STATION)).isEmpty();
    }

    private boolean itemPaused(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.stockRulePause(BUTTON).isPresent() && controller.openProductionOrders().isEmpty();
    }

    /**
     * The safety stop, whole, for a <b>player's own</b> order (decision 2 of M20): the item is paused for the right
     * reason, the pause names what the machine kept, it is not a pause any stock rule could ever prune, the order ended
     * as a timeout with its ingredients gone, the request got its promise back, the intermediate is <b>not</b> paused —
     * the stop is per item, not per warehouse — and every item is still in the scene.
     */
    private void assertSafetyStop(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        StockRulePause pause = controller.stockRulePause(BUTTON)
                .orElseThrow(() -> new VisualTestException("the warehouse still makes " + BUTTON));
        if (pause.cause() != StockRulePause.Cause.ORDER_TIMED_OUT)
            throw new VisualTestException("the item was paused by " + pause.cause() + ", expected "
                    + StockRulePause.Cause.ORDER_TIMED_OUT);
        if (pause.unrecovered() != PLANKS_PER_RUN || handedOver != PLANKS_PER_RUN)
            throw new VisualTestException("the pause names " + pause.unrecovered() + " unrecovered items and the order "
                    + "had handed over " + handedOver + ", expected " + PLANKS_PER_RUN);
        if (pause.isRuleBorn())
            throw new VisualTestException("the pause claims a stock rule armed it, but no rule governs " + BUTTON);
        if (controller.stockRules().governingCount() != 0)
            throw new VisualTestException("this aisle has stock rules after all, so the pause could belong to one");
        if (controller.stockRulePause(PLANK).isPresent())
            throw new VisualTestException("the intermediate was paused as well; the stop is per item");
        ProductionOrder<ItemKey, RackPosition> root = orderOf(controller, rootId);
        if (root.state() != ProductionOrderState.TIMED_OUT || root.deliveredIngredients() != PLANKS_PER_RUN)
            throw new VisualTestException("the order ended " + root.state() + " with " + root.deliveredIngredients()
                    + " ingredient(s) handed over, expected " + ProductionOrderState.TIMED_OUT + " with "
                    + PLANKS_PER_RUN);
        ProductionOrder<ItemKey, RackPosition> step = orderOf(controller, stepId);
        if (step.state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the step that did its job ended " + step.state());
        if (controller.openRequestCount() != 0)
            throw new VisualTestException(controller.openRequestCount() + " request(s) are still waiting for items "
                    + "nobody is going to make");
        if (!controller.reservations().isEmpty())
            throw new VisualTestException("the failed chain left reservations behind: " + controller.reservations());
        long inMachine = countIn(level, crafterPos(dock, JOINER_INPUT), PLANK)
                + armHeld(arm(level, dock, JOINER_STATION)).getCount()
                + station(level, dock, JOINER_STATION).bufferedItems().count(PLANK);
        if (inMachine != PLANKS_PER_RUN)
            throw new VisualTestException("the machine and its station hold " + inMachine + " planks, expected the "
                    + PLANKS_PER_RUN + " that were handed over");
        // Nothing is added or taken: the planks are still items, in the machine the player has to go and look at.
        // "Unrecoverable" means nobody can get them out, not that they are gone.
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -LOGS_PER_CHAIN), PLANK, PLANKS_PER_RUN);
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the machine swallowed the batch");
        LOGGER.info(PREFIX + "chain: CHECK the safety stop: the warehouse stopped making {} ({}, {} items not "
                        + "recovered, rule-born={}), the order timed out, the request got its promise back, {} is "
                        + "still made, and the census holds", BUTTON, pause.cause(), pause.unrecovered(),
                pause.isRuleBorn(), PLANK);
    }

    private static boolean refusalShown(VisualContext context) {
        return screen(context).feedbackLine().filter(line -> line.getString().contains(refusalSentence())).isPresent();
    }

    /**
     * "Making Oak Button is stopped" — the sentence of the {@link PlanRefusal} the click really ran into, which names
     * the item and therefore replaces the generic {@code "Request refused: …"} frame (M20 part 2,
     * {@code WarehouseTerminalScreen#refusedLine}). The click is still refused with
     * {@link RequestRejection#PRODUCTION_PAUSED}, which is what a warehouse port's goggles show.
     */
    private static String refusalSentence() {
        return WareworksLang.translateDirect(PlanRefusal.PAUSED.langKey(),
                BUTTON.toStack().getHoverName()).getString();
    }

    /** The one sentence this whole half exists for, drawn where the player clicked. */
    private static void checkRefusalLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        String line = terminal.feedbackLine().map(Component::getString).orElse("");
        if (!line.contains(refusalSentence()))
            throw new VisualTestException("the terminal says '" + line + "', expected '" + refusalSentence() + "'");
        if (terminal.status().requestsHere() != 0)
            throw new VisualTestException("the refused click left " + terminal.status().requestsHere()
                    + " request(s) at this terminal");
        LOGGER.info(PREFIX + "chain: CHECK the refusal a player reads: \"{}\"", line);
        // Asserted since part 2: the refusal names the item, so it is the one line that must fit the status row whole
        // (WarehouseTerminalScreen#refusedLine, docs/warehouse-system.md §3.4.2). Before part 2 the terminal borrowed a
        // sentence written for a goggle tooltip on a warehouse port and the row cut it off exactly before what it was
        // about.
        if (!terminal.statusTextsFit())
            throw new VisualTestException("the refusal \"" + line + "\" is wider than the terminal's status row");
    }

    /** A refused click is free: no order, no request, no item moved, and the stop still in force. */
    private void assertRefusedClickIsFree(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (!controller.openProductionOrders().isEmpty())
            throw new VisualTestException("the refused click started " + controller.openProductionOrders().size()
                    + " order(s)");
        if (controller.openRequestCount() != 0)
            throw new VisualTestException("the refused click queued " + controller.openRequestCount() + " request(s)");
        if (controller.stockRulePause(BUTTON).isEmpty())
            throw new VisualTestException("the stop was lifted by a click that was refused because of it");
        if (controller.countOf(LOG) != LOGS_IN_STOCK - 2L * LOGS_PER_CHAIN)
            throw new VisualTestException("the racks hold " + controller.countOf(LOG) + " logs, expected "
                    + (LOGS_IN_STOCK - 2L * LOGS_PER_CHAIN) + ": a refused click spends nothing");
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the refused click");
        LOGGER.info(PREFIX + "chain: CHECK the refused click cost nothing: no order, no request, {} logs untouched "
                + "and the stop still in force", controller.countOf(LOG));
    }

    // --- the way back, at the machine (chapter 4) ----------------------------------------------------------------------

    /**
     * What the station in front of the dead machine reports, which is the whole of chapter 4 in numbers: the item this
     * machine makes is the one the warehouse stopped, the cause is the order that timed out, the cost is the batch the
     * machine swallowed — and its own block is burning the stopped lamp while the station at the other machine is not.
     */
    private void assertStationStopped(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        List<StoppedProduct> stopped = station(level, dock, JOINER_STATION).stoppedProducts();
        if (stopped.size() != 1)
            throw new VisualTestException("the station of the dead machine reports " + stopped
                    + ", expected the one item it makes");
        StoppedProduct only = stopped.getFirst();
        if (!only.key().equals(BUTTON) || only.cause() != StockRulePause.Cause.ORDER_TIMED_OUT
                || only.unrecovered() != PLANKS_PER_RUN)
            throw new VisualTestException("the station reports " + only + ", expected " + BUTTON + " stopped by "
                    + StockRulePause.Cause.ORDER_TIMED_OUT + " with " + PLANKS_PER_RUN + " item(s) lost");
        if (!station(level, dock, SAW_STATION).stoppedProducts().isEmpty())
            throw new VisualTestException("the machine that did its job reports a stop as well: "
                    + station(level, dock, SAW_STATION).stoppedProducts());
        if (!level.getBlockState(stationPos(dock, JOINER_STATION)).getValue(WarehouseProductionBlock.STOPPED))
            throw new VisualTestException("the stopped station's own lamp is not burning");
        if (level.getBlockState(stationPos(dock, SAW_STATION)).getValue(WarehouseProductionBlock.STOPPED))
            throw new VisualTestException("the station of the machine that works is burning the stopped lamp");
        rescued = only.unrecovered();
        LOGGER.info(PREFIX + "chain: CHECK the station at {} names the stop a player has to look at: {} ({}), {} "
                        + "ingredient item(s) not recovered, and its own lamp is the only one burning", JOINER_STATION,
                only.key(), only.cause(), only.unrecovered());
    }

    private static boolean stationStoppedSynced(VisualContext context) {
        ProductionGoggleSummary summary = clientJoinerSummary(context);
        return summary != null && summary.anyStopped() && summary.stoppedProducts() == 1
                && summary.unrecovered() == PLANKS_PER_RUN;
    }

    /**
     * What a player standing in front of the machine reads through goggles: one stopped product, what the loss cost, and
     * the gesture that lifts it. These three lines outrank everything else the station says, because this is the only
     * state of a station that asks a player to do something.
     */
    private static void checkStoppedStationGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, stationPos(context.origin(), JOINER_STATION));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_STOPPED, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.KEEPER_PAUSED_LOST, PLANKS_PER_RUN));
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUME_HINT).getString());
        LOGGER.info(PREFIX + "chain: CHECK the stopped machine's goggles say {}", lines);
    }

    private static void openStationScreen(MinecraftServer server, VisualContext context) {
        if (!station(server.overworld(), context.origin(), JOINER_STATION).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the production station screen could not be opened for the camera player");
    }

    private static boolean stationScreenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseProductionScreen station && station.state().anyStopped();
    }

    private static boolean stationScreenClear(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseProductionScreen station && !station.state().anyStopped();
    }

    private static WarehouseProductionScreen stationScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen station)
            return station;
        throw new VisualTestException("the production station screen is not open (screen: "
                + context.minecraft().screen + ")");
    }

    private static void hoverStoppedRow(VisualContext context) {
        WarehouseProductionScreen station = stationScreen(context);
        ScreenInput.hover(context.minecraft(), ScreenInput.productionStoppedRow(station));
    }

    /** Moves the cursor onto the station window's title, where it draws no tooltip over the rows it is about. */
    private static void hoverAsideStation(VisualContext context) {
        WarehouseProductionScreen station = stationScreen(context);
        ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(station.getGuiLeft() + 30.0, station.getGuiTop() + 3.0));
    }

    /**
     * The row a player finds at the machine, and its tooltip: the item, why the warehouse stopped making it (in the stock
     * keeper's own sentence, because it is the same safety stop) and what the loss cost.
     */
    private static void checkStoppedRow(VisualContext context) {
        WarehouseProductionScreen station = stationScreen(context);
        List<StoppedProduct> stopped = station.state().stopped();
        if (stopped.size() != 1 || !stopped.getFirst().key().equals(BUTTON))
            throw new VisualTestException("the station's screen shows " + stopped + ", expected one row for " + BUTTON);
        StoppedProduct only = stopped.getFirst();
        if (only.cause() != StockRulePause.Cause.ORDER_TIMED_OUT
                || station.state().unrecoveredTotal() != PLANKS_PER_RUN)
            throw new VisualTestException("the row says " + only + " and names "
                    + station.state().unrecoveredTotal() + " lost item(s), expected "
                    + StockRulePause.Cause.ORDER_TIMED_OUT + " and " + PLANKS_PER_RUN);
        Component name = BUTTON.toStack().getHoverName();
        List<String> tooltip = new ArrayList<>();
        for (Component line : ScreenInput.productionTooltip(station, ScreenInput.productionStoppedRow(station)))
            tooltip.add(line.getString());
        requireText(tooltip, WareworksLang.translateDirect(WareworksLang.PRODUCTION_STOPPED_LINE, name).getString(),
                "line of the stopped row's tooltip");
        requireText(tooltip, WareworksLang.translateDirect(WareworksLang.PRODUCTION_STOPPED_ITEM, name,
                WareworksLang.translateDirect(only.causeKey())).getString(), "line of the stopped row's tooltip");
        requireText(tooltip, WareworksLang.translateDirect(WareworksLang.KEEPER_PAUSED_LOST,
                        Component.literal(LangNumberFormat.format(PLANKS_PER_RUN))).getString(),
                "line of the stopped row's tooltip");
        LOGGER.info(PREFIX + "chain: CHECK the stopped row of the machine's own screen reads {}", tooltip);
    }

    /** The click that says the machine is worth another batch — a real left click on the row (M20). */
    private static void clickStoppedRow(VisualContext context) {
        WarehouseProductionScreen station = stationScreen(context);
        ScreenInput.click(context.minecraft(), ScreenInput.productionStoppedRow(station));
    }

    private static boolean itemResumed(MinecraftServer server, VisualContext context) {
        return controller(server.overworld(), context.origin()).stockRulePause(BUTTON).isEmpty();
    }

    private static boolean resumedMessageShown(VisualContext context) {
        return chatLines(context).stream().anyMatch(line -> line.contains(resumedSentence()));
    }

    /** "The warehouse makes Oak Button again; 4 ingredient items stayed in the machine". */
    private static String resumedSentence() {
        return WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUMED_LOST, BUTTON.toStack().getHoverName(),
                Component.literal(Long.toString(PLANKS_PER_RUN))).getString();
    }

    /**
     * The sentence the resume answered with, read out of the chat the player really has. It names the cost, which is the
     * one thing a player has to be told: the pause is gone, but the items the machine took are not coming back through
     * the warehouse.
     */
    private static void checkResumedMessage(VisualContext context) {
        List<String> chat = chatLines(context);
        if (chat.stream().noneMatch(line -> line.contains(resumedSentence())))
            throw new VisualTestException("the chat says " + chat + ", expected '" + resumedSentence() + "'");
        LOGGER.info(PREFIX + "chain: CHECK the click at the machine answered \"{}\"", resumedSentence());
    }

    /**
     * The stop is gone, and <b>nothing else happened</b>: a resume lifts a stop, it does not re-order anything. The lamp
     * is out, the station reports nothing stopped, the controller counts no pause, and every item of the scene is still
     * exactly where the failed chain left it.
     */
    private void assertResumed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (controller.stockRulePause(BUTTON).isPresent())
            throw new VisualTestException("the warehouse still refuses to make " + BUTTON);
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException(controller.pausedStockRuleCount() + " stop(s) are left after the resume");
        if (!station(level, dock, JOINER_STATION).stoppedProducts().isEmpty())
            throw new VisualTestException("the station still reports "
                    + station(level, dock, JOINER_STATION).stoppedProducts());
        if (level.getBlockState(stationPos(dock, JOINER_STATION)).getValue(WarehouseProductionBlock.STOPPED))
            throw new VisualTestException("the station's stopped lamp is still burning");
        if (!controller.openProductionOrders().isEmpty() || controller.openRequestCount() != 0)
            throw new VisualTestException("the resume started something: " + controller.openProductionOrders().size()
                    + " order(s), " + controller.openRequestCount() + " request(s)");
        long inMachine = countIn(level, crafterPos(dock, JOINER_INPUT), PLANK)
                + armHeld(arm(level, dock, JOINER_STATION)).getCount()
                + station(level, dock, JOINER_STATION).bufferedItems().count(PLANK);
        if (inMachine != rescued)
            throw new VisualTestException("the machine holds " + inMachine + " plank(s), but the stop had said "
                    + rescued + " were lost in it");
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the stop was lifted at the machine");
        LOGGER.info(PREFIX + "chain: CHECK the stop is gone, the lamp is out, nothing was ordered, and the {} plank(s) "
                + "the machine swallowed are still in it", inMachine);
    }

    private static boolean stationResumedSynced(VisualContext context) {
        ProductionGoggleSummary summary = clientJoinerSummary(context);
        return summary != null && !summary.anyStopped() && summary.unrecovered() == 0L;
    }

    /**
     * The machine's goggles after the click: not a word about a stop any more, and the ordinary lines back — the same
     * tooltip a player would have read before the batch was ever lost.
     */
    private static void checkResumedStationGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, stationPos(context.origin(), JOINER_STATION));
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_STOPPED, 1));
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.KEEPER_PAUSED_LOST, PLANKS_PER_RUN));
        GoggleShots.requireNoLine(lines,
                WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUME_HINT).getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_PATTERNS, 1));
        LOGGER.info(PREFIX + "chain: CHECK the machine's goggles after the click say {}", lines);
    }

    /** Client: the block state really arrived, so the lamp in the shot is out for the right reason. */
    private static boolean lampOutOnClient(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockState state = level.getBlockState(stationPos(context.origin(), JOINER_STATION));
        return state.hasProperty(WarehouseProductionBlock.STOPPED)
                && !state.getValue(WarehouseProductionBlock.STOPPED);
    }

    // --- the chat -----------------------------------------------------------------------------------------------------

    /**
     * The player's own chat, where the sentence a resume answers with lands ({@code Player#displayClientMessage} with
     * {@code actionBar = false}). Minecraft keeps it in a private list and offers no reader, so a scenario that checks
     * what a player is <b>told</b> reads that list; dev tooling only (ADR-014).
     */
    private static final Field CHAT_MESSAGES = chatMessagesField();

    private static Field chatMessagesField() {
        try {
            Field field = ChatComponent.class.getDeclaredField("allMessages");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach the chat's messages", error);
        }
    }

    /** What the chat holds, newest first. */
    private static List<String> chatLines(VisualContext context) {
        try {
            @SuppressWarnings("unchecked")
            List<GuiMessage> messages = (List<GuiMessage>) CHAT_MESSAGES.get(context.minecraft().gui.getChat());
            List<String> lines = new ArrayList<>(messages.size());
            for (GuiMessage message : messages)
                lines.add(message.content().getString());
            return lines;
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read the chat: " + error);
        }
    }

    private static void clearChat(VisualContext context) {
        context.minecraft().gui.getChat().clearMessages(true);
    }

    // --- the chain again (chapter 5) ----------------------------------------------------------------------------------

    /** The player puts the drive of the second machine back, exactly where {@link #breakTheJoinerMachine} took it. */
    private static void repairTheJoinerMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos pos = crafterMotorPos(context.origin(), JOINER_INPUT);
        level.setBlockAndUpdate(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE.getOpposite()));
        motor(level, pos).generatedSpeed.setValue(MACHINE_RPM);
        LOGGER.info(PREFIX + "chain: the second machine has its drive back at {}", pos);
    }

    private static boolean joinerMachineRunning(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return crafter(level, dock, JOINER_INPUT).getSpeed() != 0.0F
                && arm(level, dock, JOINER_STATION).getSpeed() != 0.0F;
    }

    /**
     * The batch the machine had swallowed, back in the warehouse: the crafter finished the planks it was still holding,
     * the buttons came through the input like any other delivery and the crane stored them.
     */
    private boolean batchCameBack(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return controller(level, dock).countOf(BUTTON) >= BUTTONS_PER_RUN && sceneSettled(server, context);
    }

    /**
     * <b>"Unrecovered" is about the warehouse, not about the items.</b> The planks were never destroyed: they were out of
     * the warehouse's reach, and a machine that runs again finishes the batch and hands the result over like any other
     * delivery — as stock nobody ordered. The census proves it item for item.
     */
    private void assertBatchCameBack(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (controller.countOf(PLANK) != 0L)
            throw new VisualTestException("the racks hold " + controller.countOf(PLANK)
                    + " plank(s); the repaired machine was supposed to use the whole swallowed batch");
        if (controller.countOf(BUTTON) != BUTTONS_PER_RUN)
            throw new VisualTestException("the repaired machine gave back " + controller.countOf(BUTTON)
                    + " button(s), expected the " + BUTTONS_PER_RUN + " of the batch it had swallowed");
        if (!controller.openProductionOrders().isEmpty())
            throw new VisualTestException("the windfall started a production order: "
                    + controller.openProductionOrders());
        if (controller.stockRulePause(BUTTON).isPresent())
            throw new VisualTestException("the warehouse stopped making " + BUTTON + " again");
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, PLANK, -rescued), BUTTON, BUTTONS_PER_RUN);
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the repaired machine finished the batch");
        // No shot of its own: the board simply follows the warehouse again, so the one standing behind every terminal
        // shot of this chapter says what the warehouse really holds.
        pullDisplay(server, context);
        LOGGER.info(PREFIX + "chain: CHECK the {} plank(s) the dead machine had swallowed came back as {} button(s) in "
                        + "the racks, and the census holds: {}", rescued, controller.countOf(BUTTON),
                SceneItemCensus.describe(expected));
    }

    private static boolean windfallShownInStock(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                && terminal.entry(BUTTON).filter(line -> line.total() == BUTTONS_PER_RUN).isPresent();
    }

    /** The windfall as a player sees it: buttons in the racks, offered to anybody, promised to nobody. */
    private static void checkWindfallLine(VisualContext context) {
        StockLine<ItemKey> button = line(context, BUTTON);
        if (button.total() != BUTTONS_PER_RUN || button.available() != BUTTONS_PER_RUN)
            throw new VisualTestException("the row shows " + button.total() + " button(s) in stock and offers "
                    + button.available() + ", expected " + BUTTONS_PER_RUN + " of both");
        if (line(context, PLANK).total() != 0L)
            throw new VisualTestException("the screen still shows planks in stock: " + line(context, PLANK));
        LOGGER.info(PREFIX + "chain: CHECK the terminal offers the {} button(s) the repaired machine gave back",
                button.total());
    }

    /**
     * The starting point of the first click, reached a second time: the racks hold neither the ordered item nor its
     * ingredient, the ordered item is producible but none of it right now, and the logs are what is left after two
     * chains. This is what makes the third click a chain rather than a delivery.
     */
    private static void checkChainOfferedAgain(VisualContext context) {
        StockLine<ItemKey> button = line(context, BUTTON);
        if (!button.producible() || button.total() != 0L || button.producibleAmount() != 0L)
            throw new VisualTestException("the ordered item shows " + button.total() + " in stock and "
                    + button.producibleAmount() + " producible at a single level, expected none of either");
        StockLine<ItemKey> plank = line(context, PLANK);
        if (plank.total() != 0L || plank.producibleAmount() < PLANKS_PER_RUN)
            throw new VisualTestException("the intermediate shows " + plank.total() + " in stock and "
                    + plank.producibleAmount() + " producible, expected none in stock and a run's worth producible");
        long logs = line(context, LOG).total();
        if (logs != LOGS_IN_STOCK - 2L * LOGS_PER_CHAIN)
            throw new VisualTestException("the logs show " + logs + " in stock, expected "
                    + (LOGS_IN_STOCK - 2L * LOGS_PER_CHAIN) + " after two chains");
        LOGGER.info(PREFIX + "chain: CHECK the warehouse is back where it started: {} log(s), no planks, no buttons, "
                + "and the ordered item offered with 0 producible at a single level", logs);
    }

    private static boolean requestedShown(VisualContext context) {
        return screen(context).feedbackLine().filter(line -> line.getString().equals(requestedSentence())).isPresent();
    }

    /** "Requested Oak Button x4" — an accepted request nothing has to be made for (M11). */
    private static String requestedSentence() {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_REQUESTED, BUTTON.toStack().getHoverName(),
                LangNumberFormat.format(ORDER_AMOUNT)).getString();
    }

    /**
     * The click after the resume is an ordinary request again: the item is orderable, and because the warehouse now holds
     * it, nothing is produced at all — which is the proof that the refusal really is gone and not merely quieter.
     */
    private static void checkRequestedLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        String line = terminal.feedbackLine().map(Component::getString).orElse("");
        if (!line.equals(requestedSentence()))
            throw new VisualTestException("the terminal says '" + line + "', expected '" + requestedSentence() + "'");
        if (!terminal.statusTextsFit())
            throw new VisualTestException("the status texts do not fit their rows: " + line);
        LOGGER.info(PREFIX + "chain: CHECK the terminal answered \"{}\" — no chain, no production", line);
    }

    private void assertServedFromStock(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        if (!controller.openProductionOrders().isEmpty())
            throw new VisualTestException("the click started " + controller.openProductionOrders().size()
                    + " production order(s) although the warehouse had the item in stock");
        if (controller.openRequestCount() != 1)
            throw new VisualTestException(controller.openRequestCount() + " open request(s), expected the one the click "
                    + "made");
        LOGGER.info(PREFIX + "chain: CHECK the click was served out of stock: one request, no production order");
    }

    /** Those buttons are at the terminal and the racks hold none of them again, so the next click needs a chain. */
    private boolean windfallDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return terminal(level, dock).bufferedItems().count(BUTTON) >= ORDER_AMOUNT + BUTTONS_PER_RUN
                && controller(level, dock).countOf(BUTTON) == 0L && sceneSettled(server, context);
    }

    /** The third chain: a chain again, new orders again, and nothing of the first two reused. */
    private void assertThirdChainCreated(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
        if (open.size() != 2)
            throw new VisualTestException("the third click created " + open.size() + " open order(s), expected two");
        ProductionOrder<ItemKey, RackPosition> root = openRoot(controller);
        ProductionOrder<ItemKey, RackPosition> step = openStep(controller);
        if (root.id().equals(rootId) || step.id().equals(stepId))
            throw new VisualTestException("the third chain reused an order of an earlier one");
        if (step.parentLine().flatMap(root::line).isEmpty())
            throw new VisualTestException("the third chain's step does not name a line of its parent");
        if (!root.station().equals(JOINER_STATION) || !step.station().equals(SAW_STATION))
            throw new VisualTestException("the third chain sits at " + root.station() + " and " + step.station());
        rootId = root.id();
        stepId = step.id();
        LOGGER.info(PREFIX + "chain: CHECK the warehouse plans the whole chain again after the stop was lifted: {} {} "
                + "at {} with a step of {} {} at {}", root.resultAmount(), root.result(), root.station(),
                step.resultAmount(), step.result(), step.station());
    }

    private boolean thirdChainDone(MinecraftServer server, VisualContext context) {
        pollChain(server, context);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return terminal(level, dock).bufferedItems().count(BUTTON) >= ORDER_AMOUNT + BUTTONS_PER_RUN + ORDER_AMOUNT
                && sceneSettled(server, context);
    }

    /**
     * The whole chain a second time, in the warehouse that had stopped making the item an hour ago: both orders complete,
     * the buttons at the terminal, one more log spent, nothing paused, nothing reserved — and the census still accounts
     * for every item the run ever touched.
     */
    private void assertChainRanAgain(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        ProductionOrder<ItemKey, RackPosition> root = orderOf(controller, rootId);
        ProductionOrder<ItemKey, RackPosition> step = orderOf(controller, stepId);
        if (root.state() != ProductionOrderState.COMPLETE || step.state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the third chain ended " + root.state() + " / " + step.state()
                    + ", expected both " + ProductionOrderState.COMPLETE);
        long delivered = terminal(level, dock).bufferedItems().count(BUTTON);
        if (delivered != 2L * ORDER_AMOUNT + BUTTONS_PER_RUN)
            throw new VisualTestException("the terminal holds " + delivered + " button(s), expected the "
                    + (2 * ORDER_AMOUNT + BUTTONS_PER_RUN) + " of both chains and the windfall");
        if (controller.countOf(LOG) != LOGS_IN_STOCK - 3L * LOGS_PER_CHAIN)
            throw new VisualTestException("the racks hold " + controller.countOf(LOG) + " log(s), expected "
                    + (LOGS_IN_STOCK - 3L * LOGS_PER_CHAIN) + " after three chains");
        if (controller.countOf(PLANK) != 0L || controller.countOf(BUTTON) != 0L)
            throw new VisualTestException("the racks still hold " + controller.countOf(PLANK) + " plank(s) and "
                    + controller.countOf(BUTTON) + " button(s)");
        if (controller.pausedStockRuleCount() != 0 || !controller.reservations().isEmpty()
                || controller.openRequestCount() != 0)
            throw new VisualTestException("the third chain left something behind: "
                    + controller.pausedStockRuleCount() + " stop(s), " + controller.reservations() + ", "
                    + controller.openRequestCount() + " request(s)");
        if (maxOpenOrders != 2)
            throw new VisualTestException("the run saw up to " + maxOpenOrders
                    + " open orders, expected never more than the two of one plan");
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -LOGS_PER_CHAIN), BUTTON, BUTTONS_PER_RUN);
        SceneItemCensus.assertEquals(level, censusBox, expected, "chain: after the whole chain ran again");
        pullDisplay(server, context);
        LOGGER.info(PREFIX + "chain: CHECK the chain ran again after the stop was lifted: {} log(s) became {} plank(s) "
                        + "and then {} button(s), {} at the terminal in all, and the census holds: {}", LOGS_PER_CHAIN,
                PLANKS_PER_RUN, BUTTONS_PER_RUN, delivered, SceneItemCensus.describe(expected));
    }

    // --- what the player reads ----------------------------------------------------------------------------------------

    private static boolean joinerWaitingSynced(VisualContext context) {
        ProductionGoggleSummary summary = clientJoinerSummary(context);
        return summary != null && summary.openOrders() == 1 && summary.missingIngredients() == PLANKS_PER_RUN
                && summary.oldestState().filter(ProductionOrderState.WAITING_FOR_INGREDIENTS::equals).isPresent()
                // Part 2: the flag that turns that state into the sentence a player reads, so the shot cannot be taken
                // one sync before the line it is about.
                && summary.oldestWaitingForStep();
    }

    @Nullable
    private static ProductionGoggleSummary clientJoinerSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(stationPos(context.origin(), JOINER_STATION))
                instanceof WarehouseProductionBlockEntity station))
            return null;
        return station.productionSummary();
    }

    /**
     * What a player standing in front of the machine that is doing nothing reads today: it has an order, it is waiting
     * for ingredients, and {@value #PLANKS_PER_RUN} of them are missing. That the missing planks are being made by
     * another machine of the same plan is exactly the sentence part 2 adds here.
     */
    private static void checkWaitingStationGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, stationPos(context.origin(), JOINER_STATION));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_ORDERS, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_MISSING, PLANKS_PER_RUN));
        // Since part 2 the station says what it is really doing rather than its bare state: the order is nominally
        // WAITING_FOR_INGREDIENTS, but the aisle hands it nothing at all while the step below it runs, and "waiting for
        // ingredients" on a machine nothing is being carried to reads as a stuck crane. It is the very sentence the
        // terminal's step panel and this station's own screen show for that order.
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.PRODUCTION_WAITING_FOR_STEP).getString());
        GoggleShots.requireNoLine(lines, WareworksLang.translateDirect(
                ProductionOrderState.WAITING_FOR_INGREDIENTS.langKey()).getString());
        LOGGER.info(PREFIX + "chain: CHECK the waiting machine's goggles say {}", lines);
    }

    private static boolean controllerChainSynced(VisualContext context) {
        ControllerGoggleSummary summary = clientControllerSummary(context);
        return summary != null && summary.productionOrders() == 2 && summary.rulesPaused() == 0;
    }

    private static boolean controllerPausedSynced(VisualContext context) {
        ControllerGoggleSummary summary = clientControllerSummary(context);
        return summary != null && summary.rulesPaused() == 1 && summary.productionOrders() == 0;
    }

    @Nullable
    private static ControllerGoggleSummary clientControllerSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller))
            return null;
        return controller.summary();
    }

    /** The controller counts a plan as what it is made of: two ordinary production orders (part 1 of M20). */
    private static void checkChainControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_ORDERS, 2));
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_STOPPED, 1));
        LOGGER.info(PREFIX + "chain: CHECK the controller's goggles say {}", lines);
    }

    /**
     * The stop on the controller, which this aisle can show <b>although it has no stock rule at all</b>.
     * <p>
     * That is the M20 review fix this check pins: the paused count used to be drawn only inside the stock-rule block of
     * the tooltip ({@code if (stockRules() > 0)}), and an item a chain lost a batch of is normally governed by no rule —
     * here there is not a single rule and not a single keeper, so a warehouse whose production had stopped said nothing
     * anywhere but in the refusal of the next click. The line is now on its own level when no rule is shown above it.
     * <p>
     * The count is still called a paused <i>rule</i>; the wording is part 2's.
     */
    private static void checkPausedControllerGoggles(VisualContext context) {
        ControllerGoggleSummary summary = clientControllerSummary(context);
        if (summary == null || summary.rulesPaused() != 1)
            throw new VisualTestException("the controller does not carry the stop at all: " + summary);
        if (summary.stockRules() != 0)
            throw new VisualTestException("this aisle is meant to have no stock rule at all: " + summary);
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_STOPPED, 1));
        LOGGER.info(PREFIX + "chain: CHECK the paused controller's goggles name the stop although no stock rule "
                + "governs the item: {}", lines);
    }

    private static boolean planksShownInStock(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                && terminal.entry(PLANK).filter(line -> line.total() == PLANKS_PER_RUN).isPresent();
    }

    /**
     * The intermediate as a player sees it: {@value #PLANKS_PER_RUN} planks really in the warehouse's stock, between
     * two machines, in a rack like any other item.
     * <p>
     * <b>The row offers none of them</b>, and that is the M20 review fix it pins: every one of those planks is promised to
     * the order above, so a click could not be granted a single one. A row's {@code available} used to be the stock minus
     * the crane's <i>reservations</i> and the open <i>requests</i> only, while a request is granted out of
     * {@code availableStock}, which also subtracts what the open production orders owe their stations — so the row
     * advertised items no click could get. Before M20 that gap was a second wide (an order fetches its ingredients at
     * once); a parent waiting for a step holds its promise for as long as the step takes, which is what made it visible.
     * Saying <i>why</i> on the row is part 2's; the number is right here.
     */
    private static void checkPlanksInStockLine(VisualContext context) {
        StockLine<ItemKey> plank = line(context, PLANK);
        if (plank.total() != PLANKS_PER_RUN)
            throw new VisualTestException("the screen shows " + plank.total() + " planks, expected "
                    + PLANKS_PER_RUN);
        if (plank.available() != 0L)
            throw new VisualTestException("the row offers " + plank.available() + " planks although the order above "
                    + "has promised every one of them");
        LOGGER.info(PREFIX + "chain: CHECK the terminal shows the intermediate in stock: {} planks in the racks "
                        + "between the two machines, and the row offers {} of them because the order above has promised "
                        + "every one", plank.total(), plank.available());
    }

    private static boolean buttonsShownDelivered(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                && terminal.status().requestsHere() == 0 && terminal.entry(PLANK).isPresent()
                && terminal.entry(PLANK).get().total() == 0L;
    }

    /** After the chain: no planks left, no buttons in the racks, and nothing still being made. */
    private static void checkDeliveredScreen(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (line(context, PLANK).total() != 0L)
            throw new VisualTestException("the screen still shows planks in stock: " + line(context, PLANK));
        if (line(context, BUTTON).total() != 0L)
            throw new VisualTestException("the ordered buttons are in the racks instead of at the terminal: "
                    + line(context, BUTTON));
        if (terminal.status().requestsHere() != 0)
            throw new VisualTestException(terminal.status().requestsHere() + " request(s) are still open");
        LOGGER.info(PREFIX + "chain: CHECK the screen after the chain: {} orders listed, no request left, no planks "
                + "and no buttons in the racks", terminal.productionOrders().size());
    }

    // --- screens ------------------------------------------------------------------------------------------------------

    private static void openTerminalScreen(MinecraftServer server, VisualContext context) {
        if (!terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    private static boolean terminalReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && terminal.entry(LOG).isPresent() && terminal.entry(PLANK).isPresent()
                && terminal.entry(BUTTON).isPresent();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + context.minecraft().screen + ")");
    }

    private static StockLine<ItemKey> line(VisualContext context, ItemKey key) {
        return screen(context).entry(key)
                .orElseThrow(() -> new VisualTestException("the terminal does not show " + key));
    }

    /** The visible grid cell showing {@code key}; a missing one is a failure, never a wait. */
    private static int cellOf(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (visible.get(cell).key().equals(key))
                return cell;
        }
        throw new VisualTestException(key + " is not in the terminal's visible grid: " + visible.stream()
                .map(stock -> String.valueOf(stock.key())).toList());
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "chain", range);
    }

    // --- positions ----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    /** The direction a member at {@code rack} faces: towards the aisle, like every other station. */
    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    /**
     * The board's <b>controller</b> block: the one a display link targets and the only one that knows the whole board.
     * Create looks for the block that has no board of the same state to its {@code FACING.getClockWise()} side
     * ({@code FlapDisplayBlockEntity#updateControllerStatus}), which for an east-facing board is its <b>southern</b> end.
     */
    private static BlockPos boardPos(BlockPos dock) {
        return dock.offset(BOARD_X, BOARD_TOP_Y, BOARD_Z + BOARD_WIDTH - 1);
    }

    /** On top of the controller: a display link reads the block it is attached to ({@code #getSourcePosition}). */
    private static BlockPos displayLinkPos(BlockPos dock) {
        return controllerPos(dock).above();
    }

    private static BlockPos terminalPos(BlockPos dock) {
        return layout(dock).rackPos(TERMINAL);
    }

    private static BlockPos inputPos(BlockPos dock, RackPosition input) {
        return layout(dock).rackPos(input);
    }

    private static BlockPos stationPos(BlockPos dock, RackPosition station) {
        return layout(dock).rackPos(station);
    }

    /** Behind an input, one step outside the rack wall: the crafter's arrow points back into that input. */
    private static BlockPos crafterPos(BlockPos dock, RackPosition input) {
        return inputPos(dock, input).relative(layout(dock).sideDirection(input.side()));
    }

    private static BlockPos crafterCogPos(BlockPos dock, RackPosition input) {
        return crafterPos(dock, input).above();
    }

    private static BlockPos crafterMotorPos(BlockPos dock, RackPosition input) {
        return crafterCogPos(dock, input).relative(AISLE);
    }

    /** Behind a station, within reach of both it and its crafter. */
    private static BlockPos armPos(BlockPos dock, RackPosition station) {
        return stationPos(dock, station).relative(layout(dock).sideDirection(station.side()), 2);
    }

    /** The arm's cogwheel, on the side away from the next machine along the aisle. */
    private static BlockPos armCogPos(BlockPos dock, RackPosition station) {
        return armPos(dock, station).relative(AISLE.getOpposite());
    }

    private static BlockPos armMotorPos(BlockPos dock, RackPosition station) {
        return armCogPos(dock, station).below();
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(2),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN + 4).above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories -------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                terminalPos(dock));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BlockPos dock, RackPosition input) {
        WarehouseInputBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                inputPos(dock, input));
        if (station == null)
            throw new VisualTestException("the warehouse input at " + input + " is missing");
        return station;
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos dock, RackPosition station) {
        WarehouseProductionBlockEntity production = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                stationPos(dock, station));
        if (production == null)
            throw new VisualTestException("the production station at " + station + " is missing");
        return production;
    }

    private static ArmBlockEntity arm(ServerLevel level, BlockPos dock, RackPosition station) {
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos(dock, station));
        if (arm == null)
            throw new VisualTestException("the Mechanical Arm of the machine at " + station + " is missing");
        return arm;
    }

    private static MechanicalCrafterBlockEntity crafter(ServerLevel level, BlockPos dock, RackPosition input) {
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                crafterPos(dock, input));
        if (crafter == null)
            throw new VisualTestException("the Mechanical Crafter of the machine at " + input + " is missing");
        return crafter;
    }

    /** What a Mechanical Crafter has in it; its single slot is the machine's whole memory. */
    private static ItemStack crafterItemOn(Level level, BlockPos dock, RackPosition input) {
        if (level.getBlockEntity(crafterPos(dock, input)) instanceof MechanicalCrafterBlockEntity crafter)
            return crafter.getInventory().getItem(0);
        return ItemStack.EMPTY;
    }

    private static FlapDisplayBlockEntity board(ServerLevel level, BlockPos dock) {
        FlapDisplayBlockEntity board = AllBlockEntityTypes.FLAP_DISPLAY.getNullable(level, boardPos(dock));
        if (board == null)
            throw new VisualTestException("the aisle's display board is missing");
        return board;
    }

    private static DisplayLinkBlockEntity link(ServerLevel level, BlockPos dock) {
        DisplayLinkBlockEntity link = AllBlockEntityTypes.DISPLAY_LINK.getNullable(level, displayLinkPos(dock));
        if (link == null)
            throw new VisualTestException("the display link on the controller is missing");
        return link;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    /** Planks lying in the aisle's storage inventories, i.e. in a rack rather than in a machine or a station. */
    private static long storedPlanks(ServerLevel level, BlockPos dock) {
        BranchLayout layout = layout(dock);
        Direction outward = layout.sideDirection(Side.LEFT);
        long total = 0;
        for (int x : STORAGE_LEFT)
            total += countIn(level, layout.rackPos(RackPosition.of(x, 0, Side.LEFT)).relative(outward), PLANK);
        return total;
    }

    /** How many items of {@code key} an inventory holds, through the capability every machine exposes. */
    private static long countIn(Level level, BlockPos pos, ItemKey key) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    private static ProductionOrder<ItemKey, RackPosition> openRoot(WarehouseControllerBlockEntity controller) {
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders()) {
            if (!order.isStep())
                return order;
        }
        throw new VisualTestException("no open order of the plan is its root: " + controller.openProductionOrders());
    }

    private static ProductionOrder<ItemKey, RackPosition> openStep(WarehouseControllerBlockEntity controller) {
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders()) {
            if (order.isStep())
                return order;
        }
        throw new VisualTestException("no open order of the plan is a step: " + controller.openProductionOrders());
    }

    private static ProductionOrder<ItemKey, RackPosition> orderOf(WarehouseControllerBlockEntity controller,
            @Nullable UUID id) {
        if (id == null)
            throw new VisualTestException("the scenario has not remembered that order yet");
        Optional<ProductionOrder<ItemKey, RackPosition>> order = controller.productionOrder(id);
        return order.orElseThrow(() -> new VisualTestException("the production order " + id + " is gone"));
    }

    private static List<UUID> ids(List<ProductionOrder<ItemKey, RackPosition>> orders) {
        List<UUID> ids = new ArrayList<>(orders.size());
        for (ProductionOrder<ItemKey, RackPosition> order : orders)
            ids.add(order.id());
        return ids;
    }

    /** The first census of the run: what the scene holds before anything has moved. */
    private void baselineCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        expected = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "chain: baseline census {}", SceneItemCensus.describe(expected));
    }

    private static void insertInto(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }
}
