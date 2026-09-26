package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
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
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperGoggleSummary;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.createmod.catnip.math.Pointing;
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
 * Scenario "restock": the second half of a stock rule's work ({@code docs/warehouse-system.md} §3.6.3 – §3.6.6, M15
 * part 2, issue #3) — a <b>minimum that orders by itself</b>, the reserve that binds it, the safety stop that ends it,
 * and the terminal's confirmation.
 * <p>
 * One aisle holds a powered stacker crane, a warehouse stock keeper with four rules, a warehouse terminal, a warehouse
 * production station, a warehouse input, and — as the <b>player's machine</b> — a Create Mechanical Arm that carries
 * whatever the station holds into a Create Mechanical Crafter whose arrow points into that input. Nothing in the
 * warehouse crafts anything: the crane fetches, the machine works, the result comes back through the input like any
 * other delivery.
 * <p>
 * The run tells one story in five chapters, and every claim is asserted on the server (or on the client block entity
 * that draws it) <b>before</b> the shot that shows it:
 * <ol>
 *   <li><b>a rule that cannot order</b> — the keeper keeps {@value #PLANK_MINIMUM} planks and the aisle has none, but a
 *       second rule reserves every oak log the pattern would need, so the warehouse orders <i>nothing</i> and reports
 *       {@link StockRuleStatus#WAITING_FOR_INGREDIENTS} instead of quietly spending the logs a player protected;</li>
 *   <li><b>the terminal's three questions</b> — a click that reaches into a reserve, one whose whole-run surplus could
 *       not be stored under a maximum, and the one a screen could never work out by itself: a click for an item
 *       <i>no</i> rule governs whose <i>ingredient</i> is reserved. Two are dropped with Escape and must leave the
 *       warehouse untouched, the third is confirmed with a real click on the panel's button, and a fourth click skips
 *       the question the way Alt does;</li>
 *   <li><b>the loop</b> — the reserve is lowered, the warehouse orders by itself ({@code ProductionOrder#restock}, no
 *       backing request), the crane delivers the logs, the arm feeds them to the crafter, the planks come back through
 *       the input and are stored, and the rule is satisfied. Exactly <b>one</b> order is ever started, and the item
 *       census accounts for every log and plank;</li>
 *   <li><b>the safety stop</b> — a second pattern promises diamonds from iron, and the player re-aims the arm at a
 *       <b>basin with nothing over it</b>: the crane delivers the iron, the arm drops it in, and a basin no mixer and
 *       no press ever works gives nothing back. The order times out, the rule is paused, and the keeper's second
 *       lamp, both goggle tooltips and the terminal row all say so;</li>
 *   <li><b>the way back</b> — a real click on the paused row's status mark in the keeper's screen, after which the
 *       warehouse orders again.</li>
 * </ol>
 * <b>Why this scenario needs a real player.</b> Like {@link StockRulesVisualScenario} it runs with
 * {@link VisualWorldProfile#playable}, because Create only draws a goggle tooltip for a non-spectator who looks at a
 * block within reach, and because the questions of chapter 2 are answered with real mouse and key input into the open
 * screen ({@link ScreenInput}).
 * <p>
 * <b>The one thing that is not real input:</b> the modifiers of the skip. {@code Screen#hasAltDown} and
 * {@code hasControlDown} poll GLFW's key state rather than reading the modifiers of the click event, so no harness can
 * hold a modifier — the same limitation {@link ArmVisualScenario} documents for Shift. The click therefore lands on the
 * cell through the screen's own hit test and takes the branch {@code mouseClicked} takes with Ctrl and Alt held
 * ({@link TerminalAmounts.Click#ALL} plus the skip); everything after it — the amount, the acknowledgement, the payload,
 * the server's decision — is the real path.
 * <p>
 * <b>Alt, not Ctrl, is the skip</b> (M15 review fix): Ctrl already means "everything available" on this screen, so a
 * panel that sent a player to Ctrl would have changed the amount as well as skipping the question.
 */
public final class RestockVisualScenario implements VisualScenario {
    public static final String NAME = "restock";

    /** Its own throw-away world: a creative player with the vanilla reach, deleted and rebuilt on every run. */
    private static final String WORLD_FOLDER = "wareworks_visual_restock";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int CRANE_RPM = 128;
    /** The arm and the crafter run fast: no claw pose is measured here, only the loop as a whole. */
    private static final int MACHINE_RPM = 128;

    private static final RackPosition KEEPER = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = RackPosition.of(2, 0, Side.RIGHT);
    /** The crafter's arrow points into this one, so the product of the machine comes back like any other delivery. */
    private static final RackPosition INPUT = RackPosition.of(4, 0, Side.RIGHT);
    /** Where the crane drops the ingredients and the arm picks them up. */
    private static final RackPosition PRODUCTION = RackPosition.of(6, 0, Side.RIGHT);
    /** Aisle positions whose rack holds storage, per side; the right side leaves room for the machine. */
    private static final List<Integer> STORAGE_LEFT = List.of(1, 2, 3, 4, 5, 6, 7, 8);
    private static final List<Integer> STORAGE_RIGHT = List.of(3, 5, 7, 8);
    private static final int STORAGE_LOCATIONS = 12;

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);

    private static final int LOGS_IN_STOCK = 8;
    private static final int IRON_IN_STOCK = 4;
    private static final int COPPER_IN_STOCK = 16;

    /** Pattern 0: what the machine really does. */
    private static final int PLANKS_PER_LOG = 4;
    /**
     * Pattern 1: what the machine is <b>told</b> to do and cannot — the swallowed batch of chapter 4.
     * <p>
     * Deliberately more than {@link #REQUEST_AMOUNT}, so that a click for four diamonds really does leave a surplus
     * nobody asked for: that surplus is the only thing the maximum question is about (M15 review fix).
     */
    private static final int DIAMONDS_PER_IRON = 8;

    /** Rule 0: keep this many planks. A multiple of a run, so the loop ends exactly on the number. */
    private static final int PLANK_MINIMUM = 12;
    private static final int PLANK_RUNS = PLANK_MINIMUM / PLANKS_PER_LOG;
    /** Rule 1: every log is protected from the warehouse's own automation, so nothing can be ordered. */
    private static final int LOG_RESERVE = LOGS_IN_STOCK;
    /** What the reserve is lowered to in chapter 3: one log more than the three runs need. */
    private static final int LOG_RESERVE_LOWERED = LOGS_IN_STOCK - PLANK_RUNS - 1;
    /** Rule 2: the item of the confirmation chapter — in stock, fully reserved, and nothing can make it. */
    private static final int COPPER_RESERVE = COPPER_IN_STOCK;
    /**
     * Rule 3: a cap of two, so the surplus of a run is asked about in chapter 2 — a click for
     * {@value #REQUEST_AMOUNT} diamonds has {@value #DIAMONDS_PER_IRON} made and leaves four behind, of which the cap
     * has room for two.
     */
    private static final int DIAMOND_MAXIMUM = 2;
    /**
     * What chapter 4 raises that cap to before it sets a minimum: room for one whole run. A rule whose cap cannot hold
     * a single run of its pattern is never ordered for at all ({@code RestockOutcome#NO_ROOM}, M15 review fix), which is
     * exactly the overshoot the warehouse must not commit — and the chapter needs an order to lose.
     */
    private static final int DIAMOND_MAXIMUM_RAISED = DIAMONDS_PER_IRON;
    private static final int DIAMOND_MINIMUM = 1;

    private static final int RULES = 4;
    private static final int RULE_PLANKS = 0;
    private static final int RULE_LOG = 1;
    private static final int RULE_COPPER = 2;
    private static final int RULE_DIAMOND = 3;

    /**
     * What every question of chapter 2 asks for. Less than a whole run of {@value #DIAMONDS_PER_IRON}, because what the
     * terminal asks about is the part of a run that <b>stays</b> in the racks, and a request that takes everything a run
     * makes leaves nothing behind (M15 review fix).
     */
    private static final int REQUEST_AMOUNT = 4;
    /** Notches of the mouse wheel a scenario may spend on the amount input before it gives up. */
    private static final int MAX_SCROLL_NOTCHES = 32;

    /** Ingredient items the machine of chapter 4 swallows: one run of pattern 1. */
    private static final int SWALLOWED_IRON = 1;
    /** The production order timeout of chapter 4 ({@code productionOrderTimeoutTicks}), i.e. its smallest legal value. */
    private static final int SHORT_ORDER_TIMEOUT = 200;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int RULE_TIMEOUT_TICKS = 400;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int DELIVERY_TIMEOUT_TICKS = 2400;
    private static final int PRODUCTION_TIMEOUT_TICKS = 4800;
    private static final int PAUSE_TIMEOUT_TICKS = 2400;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 6;
    /** Two production loops, four screens and a timeout: far above the harness's default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    // Cameras, relative to the lower corner of the dock block. The player is a real creature here, so every eye sits
    // at least 1.62 + 0.2 blocks above the floor: its feet must clear the floor and the 3-pixel rails.
    /** The whole warehouse from outside the rack wall, with the machine on the right. */
    private static final CameraView OVERVIEW = CameraView.of("overview", -3.0, 3.0, 5.6, 5.0, 0.9, 1.2);
    /** The player's machine: production station, Mechanical Arm and Mechanical Crafter in one frame. */
    private static final CameraView AT_MACHINE = CameraView.of("machine", 7.5, 2.6, 6.2, 5.4, 1.0, 2.0);
    /**
     * Down <b>into</b> the basin the arm was re-aimed at, i.e. onto the batch that will never come back. Steep on
     * purpose: Create draws a basin's contents near its floor, so a flat camera photographs the basin's own front
     * wall and the batch looks like an empty machine.
     */
    private static final CameraView AT_BASIN = CameraView.of("basin", 4.4, 3.5, 5.4, 5.5, 0.85, 3.5);
    /** Close on the production station and the arm beside it, for the delivered ingredients. */
    private static final CameraView AT_STATION = CameraView.of("station", 9.8, 3.0, 5.6, 6.2, 1.0, 1.8);
    /** In the aisle, two blocks from the keeper: close enough for its goggles and for its screen to stay open. */
    private static final CameraView AT_KEEPER = CameraView.of("keeper", 3.8, 2.0, 0.35, 1.5, 0.5, 1.05);
    /**
     * The keeper's own panel, head on. A station faces the aisle and an aisle is one block wide, so the only way to
     * look a keeper in the face is from <b>over</b> the opposite rack wall: the camera floats above it, high enough
     * that the player's feet clear the barrels and low enough that the lamp strip at the bottom of the panel (1.5 px
     * tall in the model) is still more than a line. Whether the paused colour is unmistakable <i>in the dark</i> stays
     * a question for a human eye (manual check 106).
     */
    private static final CameraView AT_KEEPER_FACE = CameraView.of("keeper-face", 1.5, 2.7, -2.2, 1.5, 0.35, 1.0);
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 4.8, 2.0, 0.35, 2.5, 0.5, 1.05);
    /**
     * West of the controller, aimed at the <b>lower left corner</b> of its back face rather than at its middle: the
     * controller's aisle letter is a scroll value box on every face but the dock's, and a value box the crosshair
     * really hits makes Create's goggle overlay bail out before it draws a single line.
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);

    /** The box every item census of this run counts, set once the scene origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene must hold, item for item; changed only where a machine really transformed something. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** The largest number of open production orders any poll of chapter 3 ever saw. */
    private volatile int maxOpenOrders;
    /** What the safety stop said it cost, kept for the assertions of chapter 5. */
    private volatile long unrecovered;

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
        script.client("restock: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "restock run"))
                .server("restock: clear the area and place the motors", this::placeMotors)
                .server("restock: build the aisle, its stations, the storage and the player's machine", this::buildScene)
                .serverUntil("restock: wait until the controller has every member and the stock", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("restock: write the two production patterns", RestockVisualScenario::writePatterns)
                .serverUntil("restock: wait until the aisle knows what it can make",
                        RestockVisualScenario::patternsLive, RULE_TIMEOUT_TICKS)
                .server("restock: give the Mechanical Arm its two interaction points",
                        RestockVisualScenario::teachTheArm)
                .serverUntil("restock: wait until the arm has resolved the station and the crafter",
                        RestockVisualScenario::armReady, RULE_TIMEOUT_TICKS)
                .server("restock: count every item of the scene once", this::baselineCensus)

                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // it is lifted into the air first and only then made to fly.
                .server("restock: lift the player into the air", RestockVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("restock: the player flies and wears Engineer's Goggles", RestockVisualScenario::equipPlayer)

                // Chapter 1: the rule that cannot order. Both rules are written in the same tick, so the warehouse
                // never sees a minimum without the reserve that binds it.
                .server("restock: write the four stock rules", RestockVisualScenario::writeRules)
                .serverUntil("restock: wait until the aisle governs all four", RestockVisualScenario::rulesLive,
                        RULE_TIMEOUT_TICKS)
                .serverUntil("restock: wait until the rule reports that it is waiting for its ingredients",
                        RestockVisualScenario::waitingForIngredients, RULE_TIMEOUT_TICKS)
                .server("restock: check that a reserve really stopped the order",
                        RestockVisualScenario::assertReserveStoppedTheOrder);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so the world shots
        // are taken by a player who reaches nothing at all.
        reach(script, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.camera(AT_MACHINE).shot("machine");
        script.camera(AT_KEEPER_FACE).shot("keeper-block");
        if (pass != VisualPass.FLYWHEEL)
            return;

        waitingChapter(script);
        confirmationChapter(script);
        loopChapter(script);
        safetyStopChapter(script);
        script.client("restock: every check passed",
                context -> LOGGER.info(PREFIX + "restock: ALL CHECKS PASSED (waiting for ingredients, the three "
                        + "terminal questions, the alt skip, the restock loop, the safety stop, the way back)"));
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringBuilder status = new StringBuilder();
        if (level != null) {
            BlockPos dock = context.origin();
            BlockState keeper = level.getBlockState(keeperPos(dock));
            if (keeper.hasProperty(WarehouseStockKeeperBlock.LIT))
                status.append("lit=").append(keeper.getValue(WarehouseStockKeeperBlock.LIT))
                        .append(" paused=").append(keeper.getValue(WarehouseStockKeeperBlock.PAUSED));
            if (level.getBlockEntity(keeperPos(dock)) instanceof WarehouseStockKeeperBlockEntity be) {
                StockKeeperGoggleSummary summary = be.summary();
                status.append(String.format(Locale.ROOT, " keeper=%dr/%dmin/%dmax/%dres/%dord/%dwait/%dpause",
                        summary.rules(), summary.belowMinimum(), summary.atMaximum(), summary.atReserve(),
                        summary.ordering(), summary.waiting(), summary.paused()));
            }
            if (level.getBlockEntity(crafterPos(dock)) instanceof MechanicalCrafterBlockEntity crafter)
                status.append(" crafter=").append(describe(crafter.getInventory().getItem(0)));
            status.append(" basin=").append(countIn(level, basinPos(dock), IRON));
            if (level.getBlockEntity(armPos(dock)) instanceof ArmBlockEntity arm)
                status.append(" arm=").append(describe(armHeld(arm)));
        }
        status.append(GoggleShots.describeHover(context));
        if (context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper)
            status.append(" screen=keeper rows=").append(describeRows(keeper.state()));
        else if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            status.append(String.format(Locale.ROOT, " screen=terminal shown=%d requestsHere=%d asking=%s",
                    terminal.visibleEntries().size(), terminal.status().requestsHere(),
                    terminal.confirmation() == null ? "no" : String.valueOf(terminal.confirmation().key())));
        else if (context.minecraft().screen != null)
            status.append(" screen=").append(context.minecraft().screen.getClass().getSimpleName());
        return status.toString();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "-" : stack.getCount() + "x" + ItemKey.of(stack);
    }

    private static String describeRows(StockKeeperScreenState state) {
        StringBuilder rows = new StringBuilder();
        for (StockKeeperScreenState.RowView row : state.rows()) {
            if (row.key().isEmpty())
                continue;
            rows.append(rows.isEmpty() ? "" : ",").append(row.row()).append(':').append(row.status()).append('/')
                    .append(row.minimum()).append('/').append(row.maximum()).append('/').append(row.reserve());
        }
        return "[" + rows + "]";
    }

    // --- chapter 1: a rule that cannot order --------------------------------------------------------------------------

    /**
     * What a player sees while the warehouse is short of something it may not make: the keeper's goggles name the
     * shortfall <b>and</b> what it is waiting for, the controller counts the rule, and the keeper's screen says it in
     * the row.
     */
    private void waitingChapter(VisualScript script) {
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "restock", AT_KEEPER, "goggles-waiting", RestockVisualScenario::keeperPos,
                RestockVisualScenario::keeperWaitingSynced, RestockVisualScenario::checkWaitingGoggles);
        GoggleShots.shot(script, "restock", AT_CONTROLLER, "goggles-controller",
                RestockVisualScenario::controllerPos, RestockVisualScenario::controllerWaitingSynced,
                RestockVisualScenario::checkControllerGoggles);
        reach(script, 0.0);
        script.camera(AT_KEEPER)
                .server("restock: open the stock keeper screen", RestockVisualScenario::openKeeperScreen)
                .until("restock: wait for the screen with its four rules", RestockVisualScenario::keeperScreenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                // The mark is where the row says in words what it is doing, and a tooltip is only drawn while a
                // cursor rests on it.
                .client("restock: rest the cursor on the waiting row's status mark",
                        context -> hoverStatusMark(context, RULE_PLANKS))
                .waitTicks(SETTLE_TICKS)
                .client("restock: check what the waiting row shows", RestockVisualScenario::checkWaitingRow)
                .shot("keeper-waiting")
                .client("restock: close the keeper screen", RestockVisualScenario::closeScreen)
                .until("restock: wait until the keeper screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    // --- chapter 2: the terminal's questions --------------------------------------------------------------------------

    /**
     * The confirmation, four clicks of it, all through the screen's own input path
     * ({@code MouseHandler#onPress}/{@code KeyboardHandler#keyPress}).
     * <p>
     * The two questions that are dropped with Escape are followed by a server check that the warehouse is
     * <b>untouched</b>: a question is neither a refusal nor an acceptance, and a player who says no must not have
     * spent anything. The one that is confirmed is confirmed by clicking the panel's own button, and the last click
     * skips the question the way Ctrl does.
     */
    private void confirmationChapter(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("restock: open the terminal screen", RestockVisualScenario::openTerminalScreen)
                .until("restock: wait for the terminal screen with its stock", RestockVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: scroll the requested amount up to " + REQUEST_AMOUNT,
                        RestockVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)

                // The question no screen could ever work out: nothing governs the planks, but the log a production
                // order would spend for them is reserved.
                .client("restock: click the planks nothing has in stock", context -> clickCell(context, PLANKS))
                .until("restock: wait until the terminal asks about the reserved ingredient",
                        context -> asking(context, PLANKS), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that the question names the reserved log",
                        RestockVisualScenario::checkIngredientQuestion)
                .shot("confirm-ingredient")
                .client("restock: drop the question with Escape", RestockVisualScenario::pressEscape)
                .until("restock: wait until the question is gone", context -> screen(context).confirmation() == null,
                        SCREEN_TIMEOUT_TICKS)
                .server("restock: check that no log was spent", RestockVisualScenario::assertNothingSpent)

                // The other direction: a cap of two and a run of four.
                .client("restock: click a diamond the machine would overshoot", context -> clickCell(context, DIAMOND))
                .until("restock: wait until the terminal asks about the maximum",
                        context -> asking(context, DIAMOND), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that the question names the cap", RestockVisualScenario::checkMaximumQuestion)
                .shot("confirm-maximum")
                .client("restock: drop this one with Escape too", RestockVisualScenario::pressEscape)
                .until("restock: wait until the question is gone", context -> screen(context).confirmation() == null,
                        SCREEN_TIMEOUT_TICKS)
                .server("restock: check that nothing was ordered", RestockVisualScenario::assertNothingSpent)

                // The item's own reserve, answered with the panel's button.
                .client("restock: click the fully reserved copper", context -> clickCell(context, COPPER))
                .until("restock: wait until the terminal asks about the reserve",
                        context -> asking(context, COPPER), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that the question names the reserve",
                        RestockVisualScenario::checkReserveQuestion)
                .client("restock: rest the cursor on the Confirm button", RestockVisualScenario::hoverConfirmButton)
                .waitTicks(SETTLE_TICKS)
                .shot("confirm-reserve")
                .client("restock: click Confirm", RestockVisualScenario::clickConfirmButton)
                .until("restock: wait until the terminal accepted the request",
                        context -> screen(context).status().requestsHere() > 0, SCREEN_TIMEOUT_TICKS)
                .serverUntil("restock: wait until the crane delivered the confirmed copper",
                        (server, context) -> copperInTerminal(server, context) == REQUEST_AMOUNT,
                        DELIVERY_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("confirm-delivered")

                // And the skip: Ctrl asks for everything, Alt says "do not ask me", and the two compose.
                .client("restock: ask for every copper left the way a ctrl-alt-click does",
                        RestockVisualScenario::skipClickCopper)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that nothing was asked this time",
                        RestockVisualScenario::assertNoQuestionWasAsked)
                .serverUntil("restock: wait until the rest of the reserve arrived",
                        (server, context) -> copperInTerminal(server, context) == COPPER_IN_STOCK,
                        DELIVERY_TIMEOUT_TICKS)
                .server("restock: check that the skip really emptied the reserve",
                        RestockVisualScenario::assertReserveTaken)
                // Off the grid: the delivery re-sorted it under the cursor, and a row's tooltip would cover the one
                // line this shot is about ("Requested Copper Ingot x12", with no question in between).
                .client("restock: move the cursor off the grid", RestockVisualScenario::hoverAside)
                .waitTicks(SETTLE_TICKS)
                .shot("confirm-skipped")
                .client("restock: close the terminal screen", RestockVisualScenario::closeScreen)
                .until("restock: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    // --- chapter 3: the loop -----------------------------------------------------------------------------------------

    /** The whole restocking loop, from the lowered reserve to a satisfied rule, with one order and nothing lost. */
    private void loopChapter(VisualScript script) {
        script.server("restock: lower the log reserve to " + LOG_RESERVE_LOWERED,
                        RestockVisualScenario::lowerLogReserve)
                .serverUntil("restock: wait until the warehouse orders the planks by itself", this::restockOrdered,
                        RULE_TIMEOUT_TICKS)
                .server("restock: check that the order is the warehouse's own",
                        RestockVisualScenario::assertAutomaticOrder)
                .serverUntil("restock: wait until the crane delivered the logs to the station", this::logsAtStation,
                        DELIVERY_TIMEOUT_TICKS)
                .camera(AT_STATION)
                .shot("ordering-station")
                .serverUntil("restock: wait until the machine has a log in it", this::crafterHoldsALog,
                        PRODUCTION_TIMEOUT_TICKS)
                .camera(AT_MACHINE)
                .shot("machine-working")
                .serverUntil("restock: wait until the planks came back and met the minimum", this::minimumMet,
                        PRODUCTION_TIMEOUT_TICKS)
                .serverUntil("restock: wait until the crane, the arm and the machine are empty again",
                        this::sceneSettled, PRODUCTION_TIMEOUT_TICKS)
                // The order is finished a moment before the rule says so: what a rule *reports* is rebuilt by the
                // restock pass every stockRuleIntervalTicks, so "ordering" can outlive the order it was about by up
                // to one pass. Waiting for the word rather than asserting it is the difference between a check and a
                // race.
                .serverUntil("restock: wait until the rule reports itself satisfied again",
                        RestockVisualScenario::ruleSatisfied, RULE_TIMEOUT_TICKS)
                .server("restock: check the whole loop and count every item", this::assertLoop)
                .shotFrom(OVERVIEW, "restocked")
                .camera(AT_KEEPER)
                .server("restock: open the stock keeper screen", RestockVisualScenario::openKeeperScreen)
                .until("restock: wait for the screen", RestockVisualScenario::keeperScreenReady, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: rest the cursor on the restocked row's status mark",
                        context -> hoverStatusMark(context, RULE_PLANKS))
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that the restocked row is satisfied",
                        RestockVisualScenario::checkSatisfiedRow)
                .shot("keeper-satisfied")
                .client("restock: close the keeper screen", RestockVisualScenario::closeScreen)
                .until("restock: wait until the keeper screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    // --- chapters 4 and 5: the safety stop and the way back ------------------------------------------------------------

    /**
     * The machine that eats a batch, and everything that has to say so. The player re-aims the arm at a <b>basin</b>
     * with nothing over it: it takes the iron the crane fetched and, with no mixer and no press to work it, gives
     * nothing back — which is exactly the case {@code docs/warehouse-system.md} §3.5.4 describes and the reason the
     * safety stop exists.
     * <p>
     * The obvious machine, a lone Mechanical Crafter fed something it cannot craft, would <b>not</b> do: Create moves
     * the item out of the crafter's slot the moment it arrives and drops it on the floor when the recipe fails, where
     * a creative player standing nearby picks it up and an item census turns red.
     */
    private void safetyStopChapter(VisualScript script) {
        script.server("restock: shorten the production order timeout to " + SHORT_ORDER_TIMEOUT + " ticks",
                        RestockVisualScenario::shortenProductionTimeout)
                .server("restock: the player re-aims the arm at a basin nothing works",
                        RestockVisualScenario::reaimTheArm)
                .serverUntil("restock: wait until the arm has resolved the station and the basin",
                        RestockVisualScenario::armReady, RULE_TIMEOUT_TICKS)
                .server("restock: ask the keeper to keep a diamond as well",
                        RestockVisualScenario::askForADiamondToo)
                .serverUntil("restock: wait until the warehouse orders the diamonds by itself", this::diamondOrdered,
                        RULE_TIMEOUT_TICKS)
                .serverUntil("restock: wait until the machine has swallowed the iron", this::ironSwallowed,
                        DELIVERY_TIMEOUT_TICKS)
                .camera(AT_BASIN)
                .shot("swallowed")
                .serverUntil("restock: wait until the lost order pauses the rule", this::rulePaused,
                        PAUSE_TIMEOUT_TICKS)
                .server("restock: check the safety stop", this::assertSafetyStop)
                .camera(AT_KEEPER_FACE)
                .shot("paused-keeper-block");
        reach(script, GoggleShots.vanillaReach());
        GoggleShots.shot(script, "restock", AT_KEEPER, "goggles-paused", RestockVisualScenario::keeperPos,
                RestockVisualScenario::keeperPausedSynced, RestockVisualScenario::checkPausedGoggles);
        GoggleShots.shot(script, "restock", AT_CONTROLLER, "goggles-controller-paused",
                RestockVisualScenario::controllerPos, RestockVisualScenario::controllerPausedSynced,
                RestockVisualScenario::checkPausedControllerGoggles);
        reach(script, 0.0);
        script.camera(AT_TERMINAL)
                .server("restock: open the terminal screen", RestockVisualScenario::openTerminalScreen)
                .until("restock: wait for the terminal screen", RestockVisualScenario::terminalReady,
                        SCREEN_TIMEOUT_TICKS)
                .until("restock: wait until the terminal row reports the paused rule",
                        RestockVisualScenario::terminalRowPaused, SCREEN_TIMEOUT_TICKS)
                .client("restock: rest the cursor on the paused item", RestockVisualScenario::hoverPausedCell)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check what the paused row says", RestockVisualScenario::checkPausedTerminalRow)
                .shot("paused-terminal")
                .client("restock: close the terminal screen", RestockVisualScenario::closeScreen)
                .until("restock: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .camera(AT_KEEPER)
                .server("restock: open the stock keeper screen", RestockVisualScenario::openKeeperScreen)
                .until("restock: wait for the screen", RestockVisualScenario::keeperScreenReady, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: rest the cursor on the paused row's status mark",
                        context -> hoverStatusMark(context, RULE_DIAMOND))
                .waitTicks(SETTLE_TICKS)
                .client("restock: check what the paused row shows and offers", this::checkPausedRow)
                .shot("paused-keeper-screen")

                // Chapter 5: the way back. A real click on the row's status mark, which is the affordance the goggle
                // hint and the row's own tooltip both name.
                .client("restock: click the paused row's status mark", RestockVisualScenario::clickStatusMark)
                .serverUntil("restock: wait until the rule may order again", RestockVisualScenario::ruleResumed,
                        SCREEN_TIMEOUT_TICKS)
                .until("restock: wait until the screen shows the resumed row",
                        RestockVisualScenario::keeperRowNotPaused, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("restock: check that the row is no longer paused",
                        RestockVisualScenario::checkResumedRow)
                .shot("resumed-keeper-screen")
                .client("restock: close the keeper screen", RestockVisualScenario::closeScreen)
                .until("restock: wait until the keeper screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("restock: wait until the warehouse orders again after the resume",
                        this::orderedAgain, RULE_TIMEOUT_TICKS)
                .server("restock: check that the keeper's pause lamp went out",
                        RestockVisualScenario::assertPauseLampOut)
                .camera(AT_KEEPER_FACE)
                .shot("resumed-keeper-block");
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
        level.setBlockAndUpdate(armMotorPos(dock), motorUp);
        // A Mechanical Crafter turns on the axis of its facing, so its cogwheel sits on top of it and the motor beside
        // that cogwheel, along the same axis.
        level.setBlockAndUpdate(crafterMotorPos(dock), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE.getOpposite()));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        motor(level, armMotorPos(dock)).generatedSpeed.setValue(MACHINE_RPM);
        motor(level, crafterMotorPos(dock)).generatedSpeed.setValue(MACHINE_RPM);

        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(KEEPER), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, inward(layout, KEEPER)));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward(layout, PRODUCTION)));

        buildStorage(level, layout);
        buildMachine(level, dock);
    }

    /** Storage behind both rack planes: barrels rather than chests, so nothing ever merges into a double chest. */
    private static void buildStorage(ServerLevel level, AisleLayout layout) {
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x : side == Side.LEFT ? STORAGE_LEFT : STORAGE_RIGHT) {
                BlockPos rack = layout.rackPos(RackPosition.of(x, 0, side));
                level.setBlockAndUpdate(rack.relative(outward), Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
            }
        }
        // The stock the warehouse starts with: the ingredients of both patterns, and the reserved item of chapter 2.
        Direction left = layout.sideDirection(Side.LEFT);
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.get(0), 0, Side.LEFT)).relative(left),
                LOG.toStack(LOGS_IN_STOCK));
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.get(1), 0, Side.LEFT)).relative(left),
                IRON.toStack(IRON_IN_STOCK));
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.get(2), 0, Side.LEFT)).relative(left),
                COPPER.toStack(COPPER_IN_STOCK));
    }

    /**
     * The player's machine: a Mechanical Arm that takes whatever the production station holds and puts it into a
     * Mechanical Crafter whose arrow points straight into the warehouse input. No warehouse block changes an item
     * anywhere in this chain — the arm and the crafter are Create's, and the input takes the result like any other
     * delivery.
     */
    private static void buildMachine(ServerLevel level, BlockPos dock) {
        level.setBlockAndUpdate(crafterPos(dock), crafterState(AISLE, towardsInput()));
        level.setBlockAndUpdate(crafterCogPos(dock), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(armPos(dock), AllBlocks.MECHANICAL_ARM.getDefaultState());
        level.setBlockAndUpdate(armCogPos(dock), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        // The half-built half of the machine (chapter 4): a Create Basin with nothing over it. It takes whatever an
        // arm drops into it and gives nothing back, because only a mixer or a press above it ever works a basin —
        // which is exactly the "a machine swallowed the batch" of docs/warehouse-system.md §3.5.4.
        level.setBlockAndUpdate(basinPos(dock), AllBlocks.BASIN.getDefaultState());
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

    /** From the crafter back into the warehouse input, i.e. inwards across the rack plane. */
    private static Direction towardsInput() {
        return AISLE.getClockWise().getOpposite();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                crafterPos(dock));
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos(dock));
        if (controller == null || crane == null || crafter == null || arm == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == STORAGE_LOCATIONS
                && controller.inputStations().size() == 1
                // The terminal counts as an output as well (ADR-018).
                && controller.outputStations().size() == 1 && controller.productionStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && crafter.getSpeed() != 0.0F && arm.getSpeed() != 0.0F
                && controller.countOf(LOG) == LOGS_IN_STOCK && controller.countOf(IRON) == IRON_IN_STOCK
                && controller.countOf(COPPER) == COPPER_IN_STOCK;
    }

    /**
     * The two patterns of the station: the one the machine really performs, and the one it cannot (chapter 4). A
     * pattern is the player's own declaration of what their machine does — the warehouse never verifies it, which is
     * precisely why the safety stop exists.
     */
    private static void writePatterns(MinecraftServer server, VisualContext context) {
        WarehouseProductionBlockEntity station = station(server.overworld(), context.origin());
        if (!station.setPatternEntry(0, 0, LOG, 1)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANKS, PLANKS_PER_LOG))
            throw new VisualTestException("the pattern 1 oak log -> " + PLANKS_PER_LOG + " oak planks was refused");
        if (!station.setPatternEntry(1, 0, IRON, 1)
                || !station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, DIAMOND, DIAMONDS_PER_IRON))
            throw new VisualTestException("the pattern 1 iron ingot -> " + DIAMONDS_PER_IRON + " diamonds was refused");
    }

    private static boolean patternsLive(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.producibleKeys().contains(PLANKS) && controller.producibleKeys().contains(DIAMOND)
                && controller.aislePatterns().size() == 2;
    }

    /**
     * Gives the arm its two interaction points through Create's own placement packet — the very message a player's
     * click sends after selecting blocks with a Mechanical Arm in hand ({@code ArmPlacementPacket#handle}). Selecting
     * them with real clicks is what {@link ArmVisualScenario} is for (M12, checks 87-92); here the arm is the player's
     * machine and not the subject.
     */
    private static void teachTheArm(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ArmInteractionPoint take = point(level, stationPos(dock), Mode.TAKE);
        ArmInteractionPoint deposit = point(level, crafterPos(dock), Mode.DEPOSIT);
        new ArmPlacementPacket(List.of(take, deposit), armPos(dock)).handle(context.serverPlayer(server));
        LOGGER.info(PREFIX + "restock: the arm at {} takes from {} ({}) and deposits into {} ({})", armPos(dock),
                take.getPos(), take.getType(), deposit.getPos(), deposit.getType());
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
     * The arm has resolved both points into its own lists and really turns. Reading the lists is the only proof the
     * packet arrived, and the speed is what tells a dead kinetic connection apart from a slow machine — without it a
     * standing arm would only show up as a step that times out two minutes later.
     */
    private static boolean armReady(MinecraftServer server, VisualContext context) {
        ArmBlockEntity arm = arm(server.overworld(), context.origin());
        return !(Boolean) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_UPDATE, arm)
                && armPoints(arm, ArmVisualScenario.Reflect.ARM_INPUTS).size() == 1
                && armPoints(arm, ArmVisualScenario.Reflect.ARM_OUTPUTS).size() == 1 && arm.getSpeed() != 0.0F;
    }

    @SuppressWarnings("unchecked")
    private static List<ArmInteractionPoint> armPoints(ArmBlockEntity arm, Field field) {
        return (List<ArmInteractionPoint>) ArmVisualScenario.Reflect.get(field, arm);
    }

    private static ItemStack armHeld(ArmBlockEntity arm) {
        return (ItemStack) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_HELD, arm);
    }

    /**
     * Chapter 4: the player takes the arm off and puts it back pointing at the <b>basin</b> instead of the crafter,
     * which is the whole cause of what follows — a pattern that promises diamonds and a machine that cannot make them.
     * <p>
     * The block really is replaced, because that is what a player does and because Create only re-reads an arm's
     * points on a <b>fresh</b> block entity ({@code ArmBlockEntity#initInteractionPoints} clears its own update flag,
     * and only a client packet ever sets it again). The arm must be empty-handed first, or the item it carries would
     * drop into the world.
     */
    private static void reaimTheArm(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!armHeld(arm(level, dock)).isEmpty())
            throw new VisualTestException("the arm still carries " + armHeld(arm(level, dock)));
        level.setBlockAndUpdate(armPos(dock), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(armPos(dock), AllBlocks.MECHANICAL_ARM.getDefaultState());
        ArmInteractionPoint take = point(level, stationPos(dock), Mode.TAKE);
        ArmInteractionPoint deposit = point(level, basinPos(dock), Mode.DEPOSIT);
        new ArmPlacementPacket(List.of(take, deposit), armPos(dock)).handle(context.serverPlayer(server));
        LOGGER.info(PREFIX + "restock: the arm at {} now takes from {} and deposits into the basin at {} ({})",
                armPos(dock), take.getPos(), deposit.getPos(), deposit.getType());
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

    // --- the rules ---------------------------------------------------------------------------------------------------

    /**
     * The four rules, all in one tick: the minimum that will order, the reserve that stops it from ordering now, the
     * reserved item of the confirmation chapter, and the cap that will be asked about.
     */
    private static void writeRules(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        rule(keeper, RULE_PLANKS, PLANKS, StockKeeperRules.FIELD_MINIMUM, PLANK_MINIMUM);
        rule(keeper, RULE_LOG, LOG, StockKeeperRules.FIELD_RESERVE, LOG_RESERVE);
        rule(keeper, RULE_COPPER, COPPER, StockKeeperRules.FIELD_RESERVE, COPPER_RESERVE);
        rule(keeper, RULE_DIAMOND, DIAMOND, StockKeeperRules.FIELD_MAXIMUM, DIAMOND_MAXIMUM);
        LOGGER.info(PREFIX + "restock: the keeper holds {} rules", keeper.rules().ruleCount());
    }

    private static void rule(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, int field, long value) {
        if (!keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L).changed()
                || !keeper.editRule(row, field, null, value).changed())
            throw new VisualTestException("the rule in row " + row + " could not be written");
    }

    private static boolean rulesLive(MinecraftServer server, VisualContext context) {
        return controller(server.overworld(), context.origin()).stockRules().governingCount() == RULES;
    }

    /** The planks rule is short and says why it is not closing the gap: the ingredients are not to be had. */
    private static boolean waitingForIngredients(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(server.overworld(), dock);
        return controller.restockOutcomeOf(PLANKS) == RestockOutcome.WAITING_FOR_INGREDIENTS
                && controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) == StockRuleStatus.WAITING_FOR_INGREDIENTS;
    }

    /**
     * The reserve on the ingredient really is what stopped the order, and it stopped it <b>without spending
     * anything</b>: no order, no request, and every log still where it was.
     */
    private static void assertReserveStoppedTheOrder(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (controller.availableTo(StockAccess.AUTOMATION, LOG) != 0L)
            throw new VisualTestException("automation can still reach "
                    + controller.availableTo(StockAccess.AUTOMATION, LOG) + " logs above the reserve");
        if (controller.availableTo(StockAccess.PLAYER, LOG) != LOGS_IN_STOCK)
            throw new VisualTestException("a player should still be offered every log");
        if (!controller.productionOrders().isEmpty())
            throw new VisualTestException("the warehouse started " + controller.productionOrders().size()
                    + " production order(s) although every ingredient is reserved");
        if (controller.countOf(LOG) != LOGS_IN_STOCK || controller.countOf(PLANKS) != 0L)
            throw new VisualTestException("the stock changed: " + controller.countOf(LOG) + " logs, "
                    + controller.countOf(PLANKS) + " planks");
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("a rule is paused although no order has ever run");
        LOGGER.info(PREFIX + "restock: CHECK the reserve stopped the order: {} logs are reserved from automation and "
                        + "untouched, the rule reports {}, and nothing was ordered", LOGS_IN_STOCK,
                RestockOutcome.WAITING_FOR_INGREDIENTS);
    }

    // --- chapter 1: what a player reads ------------------------------------------------------------------------------

    private static boolean keeperWaitingSynced(VisualContext context) {
        StockKeeperGoggleSummary summary = clientSummary(context);
        return summary != null && summary.linked() && summary.rules() == RULES && summary.belowMinimum() == 1
                && summary.waiting() == 1 && summary.paused() == 0;
    }

    private static boolean keeperPausedSynced(VisualContext context) {
        StockKeeperGoggleSummary summary = clientSummary(context);
        return summary != null && summary.linked() && summary.paused() == 1;
    }

    private static StockKeeperGoggleSummary clientSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(keeperPos(context.origin()))
                instanceof WarehouseStockKeeperBlockEntity keeper))
            return null;
        return keeper.summary();
    }

    private static boolean controllerWaitingSynced(VisualContext context) {
        ControllerGoggleSummary summary = clientControllerSummary(context);
        return summary != null && summary.stockRules() == RULES && summary.rulesBelowMinimum() == 1
                && summary.rulesPaused() == 0;
    }

    private static boolean controllerPausedSynced(VisualContext context) {
        ControllerGoggleSummary summary = clientControllerSummary(context);
        return summary != null && summary.rulesPaused() == 1;
    }

    private static ControllerGoggleSummary clientControllerSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller))
            return null;
        return controller.summary();
    }

    private static void checkWaitingGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, keeperPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_RULES, RULES));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_BELOW_MINIMUM, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_WAITING, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_AT_RESERVE, 2));
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_PAUSED, 1));
        LOGGER.info(PREFIX + "restock: CHECK the keeper's goggles say {}", lines);
    }

    private static void checkControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STOCK_RULES, RULES));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_RULES_BELOW_MINIMUM, 1));
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.GOGGLES_RULES_PAUSED, 1));
        LOGGER.info(PREFIX + "restock: CHECK the controller's goggles say {}", lines);
    }

    private static void checkPausedGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, keeperPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_PAUSED, 1));
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_KEEPER_PAUSED_HINT).getString());
        LOGGER.info(PREFIX + "restock: CHECK the paused keeper's goggles say {}", lines);
    }

    private static void checkPausedControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_RULES_PAUSED, 1));
        LOGGER.info(PREFIX + "restock: CHECK the paused controller's goggles say {}", lines);
    }

    /** The row of the rule that cannot order: short of its minimum, and saying so as "waiting for ingredients". */
    private static void checkWaitingRow(VisualContext context) {
        StockKeeperScreenState.RowView row = row(context, RULE_PLANKS);
        if (row.status() != StockRuleStatus.WAITING_FOR_INGREDIENTS)
            throw new VisualTestException("the planks row reports " + row.status() + ", expected "
                    + StockRuleStatus.WAITING_FOR_INGREDIENTS);
        if (row.minimum() != PLANK_MINIMUM || row.shortfall() != PLANK_MINIMUM || row.stocked() != 0L)
            throw new VisualTestException("the planks row shows " + row + ", expected a minimum of " + PLANK_MINIMUM
                    + " and nothing in stock");
        StockKeeperScreenState.RowView logs = row(context, RULE_LOG);
        if (logs.reserve() != LOG_RESERVE || logs.heldBack() != LOG_RESERVE)
            throw new VisualTestException("the log row holds back " + logs.heldBack() + " of " + logs.reserve());
        WarehouseStockKeeperScreen keeper = keeperScreen(context);
        List<String> tooltip = ScreenInput.keeperTooltip(keeper,
                ScreenInput.keeperStatusMark(keeper, RULE_PLANKS)).stream().map(Component::getString).toList();
        String waiting = WareworksLang.translateDirect(
                WareworksLang.keeperStatusKey(StockRuleStatus.WAITING_FOR_INGREDIENTS)).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(waiting)))
            throw new VisualTestException("the waiting row's mark does not say '" + waiting + "': " + tooltip);
        LOGGER.info(PREFIX + "restock: CHECK the keeper screen shows {} and the waiting row's mark says {}",
                describeRows(keeper.state()), tooltip);
    }

    // --- chapter 2: the terminal's questions -------------------------------------------------------------------------

    /**
     * Scrolls the request amount up to {@value #REQUEST_AMOUNT} with real notches of the mouse wheel over Create's own
     * scroll input, which is the only way a player sets it. One notch is one step ({@code ScrollInput#mouseScrolled}
     * uses {@code signum}), but the loop reads the state back rather than trusting that.
     */
    private static void scrollAmount(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScrollInput amount = ScreenInput.terminalAmount(terminal);
        ScreenInput.Point point = ScreenInput.centre(amount);
        for (int notch = 0; notch < MAX_SCROLL_NOTCHES && amount.getState() != REQUEST_AMOUNT; notch++)
            ScreenInput.scroll(context.minecraft(), point, amount.getState() < REQUEST_AMOUNT ? 1.0 : -1.0);
        if (amount.getState() != REQUEST_AMOUNT)
            throw new VisualTestException("the requested amount stayed at " + amount.getState() + " after "
                    + MAX_SCROLL_NOTCHES + " notches, expected " + REQUEST_AMOUNT);
        LOGGER.info(PREFIX + "restock: the terminal asks for {} per click", amount.getState());
    }

    /** A plain left click on the grid cell of {@code key}, i.e. a request for the selected amount. */
    private static void clickCell(VisualContext context, ItemKey key) {
        WarehouseTerminalScreen terminal = screen(context);
        int cell = cellOf(context, key);
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
        LOGGER.info(PREFIX + "restock: clicked cell {} ({})", cell, key);
    }

    /**
     * What a ctrl-alt-click does: ask for everything available (Ctrl) and accept every boundary in advance (Alt).
     * <p>
     * The modifiers themselves cannot be synthesised — {@code Screen#hasControlDown} and {@code hasAltDown} poll GLFW's
     * key state, so only a physical key can set them — so the cursor is put on the cell through the screen's own hit
     * test and the branch {@code mouseClicked} takes with both held is called directly. Everything after that point is
     * the real path: the amount, {@code RequestAcknowledgement.ANY}, the payload and the server's decision.
     */
    private static void skipClickCopper(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        int cell = cellOf(context, COPPER);
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
        if (terminal.confirmation() != null)
            throw new VisualTestException("a question is still up before the skipping click");
        if (!terminal.requestVisible(cell, TerminalAmounts.Click.ALL, true))
            throw new VisualTestException("the skipping click on cell " + cell + " requested nothing");
        LOGGER.info(PREFIX + "restock: ctrl-alt-clicked cell {} ({}): everything available, no question wanted", cell,
                COPPER);
    }

    /** Moves the cursor onto the window's title, where the screen draws no tooltip at all. */
    private static void hoverAside(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(terminal.getGuiLeft() + 30.0, terminal.getGuiTop() + 5.0));
    }

    private static void pressEscape(VisualContext context) {
        ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
    }

    private static void hoverConfirmButton(VisualContext context) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalConfirmButton(screen(context), true));
    }

    private static void clickConfirmButton(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalConfirmButton(screen(context), true));
    }

    private static boolean asking(VisualContext context, ItemKey key) {
        RequestConfirmation<ItemKey> question = screen(context).confirmation();
        return question != null && question.key().equals(key);
    }

    /**
     * The question the whole feature exists for: no rule governs the planks at all, but the log a production order
     * would spend for them is reserved, and only the server can know that.
     */
    private static void checkIngredientQuestion(VisualContext context) {
        RequestConfirmation<ItemKey> question = question(context, PLANKS);
        if (question.fromReserve() != 0L || question.pastMaximum() != 0L)
            throw new VisualTestException("the planks themselves are not governed, but the question claims "
                    + question.fromReserve() + " from a reserve and " + question.pastMaximum() + " past a maximum");
        if (question.ingredients().size() != 1)
            throw new VisualTestException("the question names " + question.ingredients().size()
                    + " reserved ingredients, expected exactly the log");
        RequestConfirmation.ReservedIngredient<ItemKey> ingredient = question.ingredients().getFirst();
        if (!ingredient.key().equals(LOG) || ingredient.fromReserve() != 1L || ingredient.reserved() != LOG_RESERVE)
            throw new VisualTestException("the question names " + ingredient + ", expected 1 of " + LOG_RESERVE + " "
                    + LOG);
        requireQuestionSays(context, WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_INGREDIENT,
                LangNumberFormat.format(1L), LangNumberFormat.format(LOG_RESERVE),
                LOG.toStack().getHoverName()).getString());
        if (screen(context).status().requestsHere() > 0)
            throw new VisualTestException("the request was made before the player answered");
    }

    /**
     * The other direction: the part of a whole run that would be <b>left</b> in the racks above a cap the player set
     * themselves. A run makes {@value #DIAMONDS_PER_IRON}, the click asks for {@value #REQUEST_AMOUNT} of them, and the
     * four that stay do not all fit under a cap of {@value #DIAMOND_MAXIMUM}.
     */
    private static void checkMaximumQuestion(VisualContext context) {
        RequestConfirmation<ItemKey> question = question(context, DIAMOND);
        long surplus = (long) DIAMONDS_PER_IRON - REQUEST_AMOUNT;
        long expectedPast = surplus - DIAMOND_MAXIMUM;
        if (question.pastMaximum() != expectedPast || question.maximum() != DIAMOND_MAXIMUM)
            throw new VisualTestException("the question says " + question.pastMaximum() + " past a maximum of "
                    + question.maximum() + ", expected " + expectedPast + " past " + DIAMOND_MAXIMUM);
        if (question.amount() != REQUEST_AMOUNT || question.made() != DIAMONDS_PER_IRON)
            throw new VisualTestException("the question is about " + question.amount() + " of " + question.made()
                    + " diamonds, expected " + REQUEST_AMOUNT + " of the " + DIAMONDS_PER_IRON + " a run makes");
        if (!question.ingredients().isEmpty())
            throw new VisualTestException("no ingredient is reserved, but the question names "
                    + question.ingredients());
        requireQuestionSays(context, WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_MAXIMUM,
                LangNumberFormat.format(expectedPast), LangNumberFormat.format(DIAMONDS_PER_IRON),
                LangNumberFormat.format(DIAMOND_MAXIMUM)).getString());
    }

    /** The plain case: the item a player clicked is itself reserved, and the panel names how much of it they take. */
    private static void checkReserveQuestion(VisualContext context) {
        RequestConfirmation<ItemKey> question = question(context, COPPER);
        if (question.fromReserve() != REQUEST_AMOUNT || question.reserved() != COPPER_RESERVE)
            throw new VisualTestException("the question says " + question.fromReserve() + " of "
                    + question.reserved() + " reserved, expected " + REQUEST_AMOUNT + " of " + COPPER_RESERVE);
        if (!question.ingredients().isEmpty() || question.pastMaximum() != 0L)
            throw new VisualTestException("the copper is only reserved, but the question also claims "
                    + question.pastMaximum() + " past a maximum and the ingredients " + question.ingredients());
        requireQuestionSays(context, WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_TITLE).getString());
        requireQuestionSays(context, WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_RESERVE,
                LangNumberFormat.format(REQUEST_AMOUNT), LangNumberFormat.format(COPPER_RESERVE)).getString());
        // The skip the design asks for has to be readable on the panel, or nobody will ever find it.
        requireQuestionSays(context, WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_SKIP).getString());
    }

    private static RequestConfirmation<ItemKey> question(VisualContext context, ItemKey key) {
        RequestConfirmation<ItemKey> question = screen(context).confirmation();
        if (question == null)
            throw new VisualTestException("the terminal asked nothing about " + key);
        if (!question.key().equals(key))
            throw new VisualTestException("the terminal asks about " + question.key() + ", not about " + key);
        if (!question.required())
            throw new VisualTestException("the terminal asks a question that crosses nothing: " + question);
        return question;
    }

    /** The panel really <b>draws</b> the sentence, not only the record behind it. */
    private static void requireQuestionSays(VisualContext context, String sentence) {
        String text = panelText(context);
        if (!text.contains(sentence))
            throw new VisualTestException("the confirmation panel does not say '" + sentence + "': " + text);
        LOGGER.info(PREFIX + "restock: CHECK the terminal asks: \"{}\"", text);
    }

    /** Every line the confirmation panel draws, in one string. */
    private static String panelText(VisualContext context) {
        return String.join(" | ", ScreenInput.terminalConfirmationLines(screen(context)).stream()
                .map(Component::getString).toList());
    }

    /**
     * The skip really skipped: no panel came up at all. That the request itself went through is proved by the delivery
     * the next step waits for, not by a status line that may still be a tick behind.
     */
    private static void assertNoQuestionWasAsked(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.confirmation() != null)
            throw new VisualTestException("the skipping click was asked about after all: " + terminal.confirmation());
        LOGGER.info(PREFIX + "restock: CHECK the skipped question: nothing was asked at all, and the request was "
                + "made ({} open at this terminal)", terminal.status().requestsHere());
    }

    /** A question a player dropped must leave the warehouse exactly as it was. */
    private static void assertNothingSpent(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        if (!controller.productionOrders().isEmpty())
            throw new VisualTestException("a dropped question started " + controller.productionOrders().size()
                    + " production order(s)");
        if (controller.openRequestCount() != 0)
            throw new VisualTestException("a dropped question left " + controller.openRequestCount()
                    + " open request(s)");
        if (controller.countOf(LOG) != LOGS_IN_STOCK || controller.countOf(IRON) != IRON_IN_STOCK
                || controller.countOf(COPPER) != COPPER_IN_STOCK)
            throw new VisualTestException("a dropped question changed the stock: " + controller.countOf(LOG)
                    + " logs, " + controller.countOf(IRON) + " iron, " + controller.countOf(COPPER) + " copper");
        LOGGER.info(PREFIX + "restock: CHECK a dropped question spent nothing: {} logs, {} iron, {} copper, no order, "
                + "no request", controller.countOf(LOG), controller.countOf(IRON), controller.countOf(COPPER));
    }

    private static long copperInTerminal(MinecraftServer server, VisualContext context) {
        return terminal(server.overworld(), context.origin()).bufferedItems().count(COPPER);
    }

    /** Both answered clicks together took the whole reserve — which a player may, and automation never could. */
    private static void assertReserveTaken(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long delivered = copperInTerminal(server, context);
        if (delivered != COPPER_IN_STOCK || controller.countOf(COPPER) != 0L)
            throw new VisualTestException("the terminal holds " + delivered + " copper and the racks "
                    + controller.countOf(COPPER) + ", expected " + COPPER_IN_STOCK + " and 0");
        LOGGER.info(PREFIX + "restock: CHECK the confirmed click and the skipped one took all {} reserved copper "
                + "({} by the confirmed one), while automation was never offered any", delivered, REQUEST_AMOUNT);
    }

    // --- chapter 3: the loop -----------------------------------------------------------------------------------------

    private static void lowerLogReserve(MinecraftServer server, VisualContext context) {
        keeper(server.overworld(), context.origin()).editRule(RULE_LOG, StockKeeperRules.FIELD_RESERVE, null,
                LOG_RESERVE_LOWERED);
    }

    /** An automatic order is open. Every poll also records how many were ever open at once. */
    private boolean restockOrdered(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        maxOpenOrders = Math.max(maxOpenOrders, controller.openProductionOrders().size());
        return controller.openProductionOrders().size() == 1
                && controller.restockOutcomeOf(PLANKS).isOrdering();
    }

    /**
     * The order is the warehouse's own: no request behind it, nobody waiting for the result, and the rule says it is
     * being made rather than that it is short.
     */
    private static void assertAutomaticOrder(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(server.overworld(), dock);
        ProductionOrder<ItemKey, RackPosition> order = controller.openProductionOrders().getFirst();
        if (!order.isRestock() || order.backingRequest().isPresent())
            throw new VisualTestException("the order is not an automatic one: restock=" + order.isRestock()
                    + " backingRequest=" + order.backingRequest());
        if (!order.result().equals(PLANKS) || order.resultAmount() != PLANK_MINIMUM)
            throw new VisualTestException("the order is for " + order.resultAmount() + " " + order.result()
                    + ", expected " + PLANK_MINIMUM + " " + PLANKS);
        if (controller.openRequestCount() != 0)
            throw new VisualTestException("the warehouse invented " + controller.openRequestCount()
                    + " retrieval request(s) for its own order");
        if (controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) != StockRuleStatus.ORDERING)
            throw new VisualTestException("the rule reports "
                    + controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) + ", expected "
                    + StockRuleStatus.ORDERING);
        LOGGER.info(PREFIX + "restock: CHECK the warehouse ordered {} {} by itself in {} run(s), with no request "
                        + "behind it and no retrieval request invented", order.resultAmount(), order.result(),
                PLANK_RUNS);
    }

    private boolean logsAtStation(MinecraftServer server, VisualContext context) {
        maxOpenOrders = Math.max(maxOpenOrders,
                controller(server.overworld(), context.origin()).openProductionOrders().size());
        return station(server.overworld(), context.origin()).bufferedItems().count(LOG) > 0
                || crafterItem(server.overworld(), context.origin()).is(Items.OAK_LOG);
    }

    private boolean crafterHoldsALog(MinecraftServer server, VisualContext context) {
        maxOpenOrders = Math.max(maxOpenOrders,
                controller(server.overworld(), context.origin()).openProductionOrders().size());
        return crafterItem(server.overworld(), context.origin()).is(Items.OAK_LOG)
                || controller(server.overworld(), context.origin()).countOf(PLANKS) > 0L;
    }

    private boolean minimumMet(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        maxOpenOrders = Math.max(maxOpenOrders, controller.openProductionOrders().size());
        return controller.countOf(PLANKS) >= PLANK_MINIMUM && controller.openProductionOrders().isEmpty();
    }

    /** Nothing is in flight any more: no crane job, no item in the arm's claw and none in the machine. */
    private boolean sceneSettled(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        return crane != null && craneIdle(crane) && armHeld(arm(level, dock)).isEmpty()
                && crafterItem(level, dock).isEmpty()
                && station(level, dock).bufferedItems().count(LOG) == 0
                && input(level, dock).bufferedItems().count(PLANKS) == 0
                && SceneItemCensus.isFullyLoaded(level, censusBox);
    }

    /** The restocked rule has said the last word about itself: nothing to report, and satisfied. */
    private static boolean ruleSatisfied(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(server.overworld(), dock);
        return controller.restockOutcomeOf(PLANKS) == RestockOutcome.NOT_GOVERNING
                && controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) == StockRuleStatus.SATISFIED;
    }

    /**
     * The whole loop in one assertion: the minimum is met, it took exactly <b>one</b> order from start to finish, the
     * rule is satisfied, nothing is paused, and every item of the scene is accounted for — {@value #PLANK_RUNS} logs
     * became {@value #PLANK_MINIMUM} planks and nothing else changed.
     */
    private void assertLoop(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long planks = controller.countOf(PLANKS);
        if (planks != PLANK_MINIMUM)
            throw new VisualTestException("the warehouse stored " + planks + " planks, expected exactly "
                    + PLANK_MINIMUM);
        if (controller.countOf(LOG) != LOGS_IN_STOCK - PLANK_RUNS)
            throw new VisualTestException("the loop spent " + (LOGS_IN_STOCK - controller.countOf(LOG))
                    + " logs, expected " + PLANK_RUNS);
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        if (orders.size() != 1 || maxOpenOrders != 1)
            throw new VisualTestException("the loop ran " + orders.size() + " order(s), up to " + maxOpenOrders
                    + " of them at once; expected exactly one from start to finish");
        if (orders.getFirst().state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the order ended " + orders.getFirst().state() + ", expected "
                    + ProductionOrderState.COMPLETE);
        if (controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) != StockRuleStatus.SATISFIED)
            throw new VisualTestException("the rule reports "
                    + controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) + ", expected "
                    + StockRuleStatus.SATISFIED);
        if (controller.restockOutcomeOf(PLANKS) != RestockOutcome.NOT_GOVERNING)
            throw new VisualTestException("a satisfied rule still reports " + controller.restockOutcomeOf(PLANKS));
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("a rule was paused although the loop completed");
        // The one place a machine really changed items, so the one place the census expectation changes.
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -PLANK_RUNS), PLANKS, PLANK_MINIMUM);
        SceneItemCensus.assertEquals(level, censusBox, expected, "restock: after the loop");
        LOGGER.info(PREFIX + "restock: CHECK the loop: one automatic order turned {} logs into {} planks through the "
                        + "player's machine, the rule is {}, and the census holds: {}", PLANK_RUNS, planks,
                StockRuleStatus.SATISFIED, SceneItemCensus.describe(expected));
    }

    private static void checkSatisfiedRow(VisualContext context) {
        StockKeeperScreenState.RowView row = row(context, RULE_PLANKS);
        if (row.status() != StockRuleStatus.SATISFIED || row.stocked() != PLANK_MINIMUM || row.shortfall() != 0L)
            throw new VisualTestException("the restocked row shows " + row + ", expected " + PLANK_MINIMUM
                    + " in stock and " + StockRuleStatus.SATISFIED);
        WarehouseStockKeeperScreen keeper = keeperScreen(context);
        List<String> tooltip = ScreenInput.keeperTooltip(keeper,
                ScreenInput.keeperStatusMark(keeper, RULE_PLANKS)).stream().map(Component::getString).toList();
        String satisfied = WareworksLang.translateDirect(
                WareworksLang.keeperStatusKey(StockRuleStatus.SATISFIED)).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(satisfied)))
            throw new VisualTestException("the restocked row's mark does not say '" + satisfied + "': " + tooltip);
        LOGGER.info(PREFIX + "restock: CHECK the keeper screen shows {} and the restocked row's mark says {}",
                describeRows(keeper.state()), tooltip);
    }

    // --- chapters 4 and 5: the safety stop ---------------------------------------------------------------------------

    /**
     * Shortens {@code productionOrderTimeoutTicks} to its smallest legal value for the rest of the run, so a lost batch
     * gives up inside a screenshot run instead of after five minutes. {@code ConfigValue#set} writes into the loaded
     * config in memory only (no file, no config event), exactly as the GameTests' {@code ConfigOverrides} does.
     */
    private static void shortenProductionTimeout(MinecraftServer server, VisualContext context) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so the order timeout cannot be shortened");
        WareworksConfig.SERVER.productionOrderTimeoutTicks.set(SHORT_ORDER_TIMEOUT);
        WareworksConfig.SERVER.productionOrderTimeoutTicks.clearCache();
        if (WareworksConfig.productionOrderTimeoutTicks() != SHORT_ORDER_TIMEOUT)
            throw new VisualTestException("the order timeout stayed at "
                    + WareworksConfig.productionOrderTimeoutTicks() + " ticks");
    }

    /**
     * The rule of the machine that cannot deliver: keep one diamond, made from iron by a pattern that is a lie.
     * <p>
     * The cap is raised to one whole run first. A rule whose maximum cannot hold a single run of its pattern is never
     * ordered for — the warehouse says {@code NO_ROOM} instead of storing a surplus above a cap it could never get rid
     * of again (M15 review fix) — and this chapter needs an order to lose.
     */
    private static void askForADiamondToo(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        keeper.editRule(RULE_DIAMOND, StockKeeperRules.FIELD_MAXIMUM, null, DIAMOND_MAXIMUM_RAISED);
        keeper.editRule(RULE_DIAMOND, StockKeeperRules.FIELD_MINIMUM, null, DIAMOND_MINIMUM);
    }

    private boolean diamondOrdered(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders()) {
            if (order.result().equals(DIAMOND) && order.isRestock())
                return true;
        }
        return false;
    }

    /** The machine really has the ingredients: the crane delivered them and the arm dropped them into the basin. */
    private boolean ironSwallowed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (countIn(level, basinPos(dock), IRON) < SWALLOWED_IRON)
            return false;
        unrecovered = Math.max(unrecovered, deliveredIron(level, dock));
        return true;
    }

    /** Ingredient items the crane has already handed over for the diamond order, i.e. what a loss would cost. */
    private static long deliveredIron(ServerLevel level, BlockPos dock) {
        for (ProductionOrder<ItemKey, RackPosition> order : controller(level, dock).productionOrders()) {
            if (order.result().equals(DIAMOND))
                return order.deliveredIngredients();
        }
        return 0L;
    }

    private boolean rulePaused(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        return controller.stockRulePause(DIAMOND).isPresent()
                && level.getBlockState(keeperPos(dock)).getValue(WarehouseStockKeeperBlock.PAUSED);
    }

    /**
     * The safety stop, whole: the rule is paused for the right reason, it names what the machine kept, the keeper's own
     * lamp says so, nothing is ordered any more — and everything <b>else</b> about the rules still works, because a
     * pause stops ordering and nothing more.
     */
    private void assertSafetyStop(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        StockRulePause pause = controller.stockRulePause(DIAMOND)
                .orElseThrow(() -> new VisualTestException("the rule for " + DIAMOND + " is not paused"));
        if (pause.cause() != StockRulePause.Cause.TIMED_OUT)
            throw new VisualTestException("the rule was paused by " + pause.cause() + ", expected "
                    + StockRulePause.Cause.TIMED_OUT);
        if (pause.unrecovered() != SWALLOWED_IRON || unrecovered != SWALLOWED_IRON)
            throw new VisualTestException("the pause names " + pause.unrecovered() + " unrecovered items and the "
                    + "order had delivered " + unrecovered + ", expected " + SWALLOWED_IRON);
        if (controller.pausedStockRuleCount() != 1)
            throw new VisualTestException(controller.pausedStockRuleCount() + " rules are paused, expected one");
        if (controller.restockOutcomeOf(DIAMOND) != RestockOutcome.PAUSED
                || controller.stockRuleStatus(keeperPos(dock), RULE_DIAMOND) != StockRuleStatus.PAUSED)
            throw new VisualTestException("the rule reports " + controller.restockOutcomeOf(DIAMOND) + " / "
                    + controller.stockRuleStatus(keeperPos(dock), RULE_DIAMOND) + ", expected PAUSED");
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders()) {
            if (order.result().equals(DIAMOND))
                throw new VisualTestException("a paused rule still has an open order: " + order);
        }
        if (countIn(level, basinPos(dock), IRON) < SWALLOWED_IRON)
            throw new VisualTestException("the machine does not hold the iron it swallowed: "
                    + countIn(level, basinPos(dock), IRON) + " in the basin");
        // The pause stops ordering and nothing else: what the rules do for the player goes on.
        if (controller.stockRuleStatus(keeperPos(dock), RULE_PLANKS) != StockRuleStatus.SATISFIED)
            throw new VisualTestException("the other rule stopped working while this one is paused");
        long ironLeft = controller.countOf(IRON);
        if (ironLeft != IRON_IN_STOCK - SWALLOWED_IRON)
            throw new VisualTestException("the racks hold " + ironLeft + " iron, expected "
                    + (IRON_IN_STOCK - SWALLOWED_IRON) + ": what the machine has is gone and nothing pretends "
                    + "otherwise");
        // Nothing is added or taken: the swallowed iron is still an item, in the crafter's own slot, and the census
        // reads that slot like any other inventory. "Unrecoverable" means nobody can get it out, not that it is gone.
        SceneItemCensus.assertEquals(level, censusBox, expected, "restock: after the lost batch");
        LOGGER.info(PREFIX + "restock: CHECK the safety stop: the rule for {} is paused ({}), it names the {} item(s) "
                        + "the machine kept, the keeper's lamp says so, nothing is ordered any more, and the census "
                        + "still holds", DIAMOND, pause.cause(), pause.unrecovered());
    }

    private static boolean terminalRowPaused(VisualContext context) {
        Optional<StockLine<ItemKey>> line = screen(context).entry(DIAMOND);
        return line.isPresent() && line.get().rule().filter(StockRuleStatus.PAUSED::equals).isPresent();
    }

    private static void hoverPausedCell(VisualContext context) {
        ScreenInput.hover(context.minecraft(),
                ScreenInput.terminalCell(screen(context), cellOf(context, DIAMOND)));
    }

    /** The terminal is a surface of the safety stop too: the row of a paused item says so where a player shops. */
    private static void checkPausedTerminalRow(VisualContext context) {
        StockLine<ItemKey> line = screen(context).entry(DIAMOND)
                .orElseThrow(() -> new VisualTestException("the terminal does not show the paused item"));
        if (line.rule().filter(StockRuleStatus.PAUSED::equals).isEmpty())
            throw new VisualTestException("the paused row reports " + line.rule() + ", expected PAUSED");
        List<String> tooltip = screen(context).itemTooltip(line).stream().map(Component::getString).toList();
        String paused = WareworksLang.translateDirect(WareworksLang.TERMINAL_RULE,
                WareworksLang.translateDirect(WareworksLang.keeperStatusKey(StockRuleStatus.PAUSED))).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(paused)))
            throw new VisualTestException("the paused row does not say '" + paused + "': " + tooltip);
        LOGGER.info(PREFIX + "restock: CHECK the terminal row of the paused item says: {}", tooltip);
    }

    /** The keeper's screen names the cost of the lost batch and offers the way back, in the row itself. */
    private void checkPausedRow(VisualContext context) {
        StockKeeperScreenState.RowView row = row(context, RULE_DIAMOND);
        if (!row.paused() || row.status() != StockRuleStatus.PAUSED)
            throw new VisualTestException("the diamond row reports " + row.status() + ", expected PAUSED");
        if (row.unrecovered() != SWALLOWED_IRON)
            throw new VisualTestException("the row names " + row.unrecovered() + " unrecovered items, expected "
                    + SWALLOWED_IRON);
        WarehouseStockKeeperScreen keeper = keeperScreen(context);
        List<String> tooltip = ScreenInput.keeperTooltip(keeper,
                ScreenInput.keeperStatusMark(keeper, RULE_DIAMOND)).stream().map(Component::getString).toList();
        String lost = WareworksLang.translateDirect(WareworksLang.KEEPER_PAUSED_LOST,
                Component.literal(LangNumberFormat.format(SWALLOWED_IRON))).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(lost)))
            throw new VisualTestException("the paused row's tooltip does not say '" + lost + "': " + tooltip);
        LOGGER.info(PREFIX + "restock: CHECK the paused keeper row: {} and its tooltip {}", describeRows(
                keeperScreen(context).state()), tooltip);
    }

    /** Puts the cursor on a row's status mark, so the shot carries the row's own words about what it is doing. */
    private static void hoverStatusMark(VisualContext context, int row) {
        WarehouseStockKeeperScreen keeper = keeperScreen(context);
        ScreenInput.hover(context.minecraft(), ScreenInput.keeperStatusMark(keeper, row));
    }

    /** The way back: a real left click on the status mark of the paused row. */
    private static void clickStatusMark(VisualContext context) {
        WarehouseStockKeeperScreen keeper = keeperScreen(context);
        ScreenInput.Point point = ScreenInput.keeperStatusMark(keeper, RULE_DIAMOND);
        ScreenInput.click(context.minecraft(), point);
        LOGGER.info(PREFIX + "restock: clicked the status mark of row {} at {}", RULE_DIAMOND, point);
    }

    private static boolean ruleResumed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        return controller.stockRulePause(DIAMOND).isEmpty() && controller.pausedStockRuleCount() == 0;
    }

    private static boolean keeperRowNotPaused(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper
                && keeper.state().row(RULE_DIAMOND).filter(row -> !row.paused()).isPresent();
    }

    private static void checkResumedRow(VisualContext context) {
        StockKeeperScreenState.RowView row = row(context, RULE_DIAMOND);
        if (row.paused() || row.unrecovered() != 0L)
            throw new VisualTestException("the resumed row still reports " + row.status() + " and "
                    + row.unrecovered() + " unrecovered items");
        LOGGER.info(PREFIX + "restock: CHECK the resumed row: {}", describeRows(keeperScreen(context).state()));
    }

    /**
     * After the resume the warehouse really tries again — which is the whole point of the click. A <b>new open</b> order
     * is the proof: the paused rule had none, and counting the finished ones would race the retention that drops them
     * half a minute after they end.
     */
    private boolean orderedAgain(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return diamondOrdered(server, context) && controller.restockOutcomeOf(DIAMOND).isOrdering();
    }

    private static void assertPauseLampOut(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (level.getBlockState(keeperPos(dock)).getValue(WarehouseStockKeeperBlock.PAUSED))
            throw new VisualTestException("the keeper's pause lamp still burns after the resume");
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException(controller.pausedStockRuleCount() + " rules are still paused");
        LOGGER.info(PREFIX + "restock: CHECK the way back: the click on the status mark lifted the pause, the keeper's "
                + "pause lamp is out and the warehouse ordered again");
    }

    // --- screens -----------------------------------------------------------------------------------------------------

    private static void openKeeperScreen(MinecraftServer server, VisualContext context) {
        if (!keeper(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the stock keeper screen could not be opened for the camera player");
    }

    private static boolean keeperScreenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper && keeper.state().linked()
                && keeper.state().rows().size() > RULE_DIAMOND;
    }

    private static WarehouseStockKeeperScreen keeperScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper)
            return keeper;
        throw new VisualTestException("the stock keeper screen is not open (screen: " + context.minecraft().screen
                + ")");
    }

    private static StockKeeperScreenState.RowView row(VisualContext context, int row) {
        return keeperScreen(context).state().row(row)
                .orElseThrow(() -> new VisualTestException("the keeper screen has no row " + row));
    }

    private static void openTerminalScreen(MinecraftServer server, VisualContext context) {
        if (!terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    private static boolean terminalReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && terminal.entry(COPPER).isPresent() && terminal.entry(PLANKS).isPresent()
                && terminal.entry(DIAMOND).isPresent();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + context.minecraft().screen + ")");
    }

    /** The visible grid cell showing {@code key}; a missing one is a failure, never a wait. */
    private static int cellOf(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (visible.get(cell).key().equals(key))
                return cell;
        }
        throw new VisualTestException(key + " is not in the terminal's visible grid: " + visible.stream()
                .map(line -> String.valueOf(line.key())).toList());
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "restock", range);
    }

    // --- positions ---------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    /** The direction a member at {@code rack} faces: towards the aisle, like every other station. */
    private static Direction inward(AisleLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    private static BlockPos keeperPos(BlockPos dock) {
        return layout(dock).rackPos(KEEPER);
    }

    private static BlockPos terminalPos(BlockPos dock) {
        return layout(dock).rackPos(TERMINAL);
    }

    private static BlockPos inputPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT);
    }

    private static BlockPos stationPos(BlockPos dock) {
        return layout(dock).rackPos(PRODUCTION);
    }

    /** Behind the input, one step outside the rack wall: the crafter's arrow points back into the input. */
    private static BlockPos crafterPos(BlockPos dock) {
        return inputPos(dock).relative(layout(dock).sideDirection(INPUT.side()));
    }

    private static BlockPos crafterCogPos(BlockPos dock) {
        return crafterPos(dock).above();
    }

    private static BlockPos crafterMotorPos(BlockPos dock) {
        return crafterCogPos(dock).relative(AISLE);
    }

    /** Behind the station, within reach of both it and the crafter. */
    private static BlockPos armPos(BlockPos dock) {
        return stationPos(dock).relative(layout(dock).sideDirection(PRODUCTION.side()), 2);
    }

    private static BlockPos armCogPos(BlockPos dock) {
        return armPos(dock).relative(AISLE);
    }

    /** Beside the arm, on the side away from its cogwheel: the machine of chapter 4. */
    private static BlockPos basinPos(BlockPos dock) {
        return armPos(dock).relative(AISLE.getOpposite());
    }

    private static BlockPos armMotorPos(BlockPos dock) {
        return armCogPos(dock).below();
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(2),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN + 4).above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories ------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                keeperPos(dock));
        if (keeper == null)
            throw new VisualTestException("the warehouse stock keeper of the aisle is missing");
        return keeper;
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BlockPos dock) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level, inputPos(dock));
        if (input == null)
            throw new VisualTestException("the warehouse input of the aisle is missing");
        return input;
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos dock) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                stationPos(dock));
        if (station == null)
            throw new VisualTestException("the production station of the aisle is missing");
        return station;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                terminalPos(dock));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static ArmBlockEntity arm(ServerLevel level, BlockPos dock) {
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos(dock));
        if (arm == null)
            throw new VisualTestException("the Mechanical Arm of the machine is missing");
        return arm;
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

    /** What the Mechanical Crafter has in it; its single slot is the machine's whole memory. */
    private static ItemStack crafterItem(ServerLevel level, BlockPos dock) {
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                crafterPos(dock));
        if (crafter == null)
            throw new VisualTestException("the Mechanical Crafter of the machine is missing");
        return crafter.getInventory().getItem(0);
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

    /** The first census of the run: what the scene holds before anything has moved. */
    private void baselineCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        expected = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "restock: baseline census {}", SceneItemCensus.describe(expected));
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
