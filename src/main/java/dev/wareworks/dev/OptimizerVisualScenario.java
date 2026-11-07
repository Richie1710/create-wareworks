package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.client.gui.WarehouseOptimizerScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerMenuLayout;
import dev.wareworks.content.station.OptimizerScreenState;
import dev.wareworks.content.station.WarehouseOptimizerBlock;
import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "optimizer": the Warehouse Optimizer (issue #46, ADR-079, {@code docs/warehouse-system.md}) on its real
 * screen, with the real crane carrying the overflow up, in English and in German.
 * <p>
 * <b>The scene.</b> One powered aisle in a creative world with the vanilla reach ({@link VisualWorldProfile#playable}:
 * a spectator cannot pick a stack up onto the cursor). At the far end of the left rack row stands the chest that took
 * the overflow while the racks were full: priority 0, {@value #IRON_LOW} iron ingots and {@value #COPPER_LOW} copper
 * ingots. Near the dock stand two chests at priority {@value #HIGH_PRIORITY}: the one the iron belongs in, holding
 * {@value #IRON_HIGH} iron and with plenty of room, and one holding {@value #GOLD_HIGH} gold, which therefore has nowhere
 * better to go. The optimizer sits opposite, beside the dock. The player carries one iron ingot and a filled-in
 * clipboard listing iron, gold and copper.
 * <p>
 * <b>A round</b> ({@link #round}), once per language, every step a real click through {@link ScreenInput} at a point the
 * screen's own hit tests confirm:
 * <ol>
 * <li>the empty list ({@code optimizer-empty}); in the German round its three rows are first cleared with an empty
 * hand, one click each;</li>
 * <li>the iron ingot picked up onto the cursor and clicked onto the second row: a ghost — the ingot goes back into its
 * slot — and the row reads Best {@value #IRON_HIGH}, Below {@value #IRON_LOW}, Move {@value #IRON_LOW}
 * ({@code optimizer-ghost}), with every priority in the cell's tooltip ({@code optimizer-tooltip});</li>
 * <li>the clipboard picked up and clicked onto the list cell: gold and copper fill the free rows above and below it,
 * the iron already listed is left out ({@code optimizer-imported}). Gold cannot move, copper can;</li>
 * <li>Optimise: the world is frozen in the very server tick the crane carries the first {@value #FIRST_TRIP} iron to
 * the better chest. The screen shows the running order, its aqua and grey pips and the red cancel mark
 * ({@code optimizer-running}); then the screen is closed and the carry is shot across and along the aisle
 * ({@code carry-across}, {@code carry-along});</li>
 * <li>frozen again while the second trip carries the remaining {@value #SECOND_TRIP}: the cancel mark's tooltip
 * ({@code optimizer-cancel-tooltip}) and a real click on it. The order stops at once, the trip under way is finished and
 * counted, and the copper stays where it lies ({@code optimizer-cancelled});</li>
 * <li>the same screen at the floor every layout is measured against, 1280 x 960 at GUI scale 4, i.e. 320 x 240
 * ({@code optimizer-floor}).</li>
 * </ol>
 * Every round starts from the same stock ({@link #restock}). The second render pass (Flywheel off, still German) clicks
 * the copper row's pip, which optimises that item alone, shoots its carry and the finished order with the green pip.
 * <p>
 * <b>Every number is asserted before it is photographed</b>, on the server (the order, its trips and what it moved, the
 * crane's job and what it holds, the chests) and on the client (the rows the screen was pushed, the status line, the
 * tooltips, that the status line and the column headers fit their room). {@link SceneItemCensus} of the aisle and the
 * player brackets every act and every frozen moment: a ghost, an import and a relocation never create, destroy or
 * change a single item.
 * <p>
 * <b>A frozen world pushes no screen updates.</b> The optimizer's menu pushes at most once per game tick, and a frozen
 * server does not advance the game time, so a screen opened while frozen shows one push and a click made while frozen
 * is checked on the server and shows on screen only once the world runs again.
 */
public final class OptimizerVisualScenario implements VisualScenario {
    public static final String NAME = "optimizer";

    private static final String WORLD_FOLDER = "wareworks_visual_optimizer";
    /** Two rounds of four crane trips, two language switches and two window sizes: far above the default. */
    private static final long RUN_TIMEOUT_MILLIS = 12L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 7;
    private static final int MOTOR_RPM = 128;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    /** The optimizer beside the dock, on the right rack side, its panel towards the aisle. */
    private static final RackPosition OPTIMIZER = RackPosition.of(1, 0, Side.RIGHT);
    /** The gold's chest at the high priority: the gold has nowhere better to go. */
    private static final RackPosition GOLD_RACK = RackPosition.of(2, 0, Side.LEFT);
    /** The chest the iron belongs in: the high priority, some iron already, plenty of room. */
    private static final RackPosition HIGH = RackPosition.of(3, 0, Side.LEFT);
    /** The chest that took the overflow while the racks were full: priority 0, at the far end of the row. */
    private static final RackPosition LOW = RackPosition.of(RAILS, 0, Side.LEFT);
    private static final int HIGH_PRIORITY = 5;
    private static final int LOW_PRIORITY = 0;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);
    private static final int IRON_HIGH = 20;
    private static final int IRON_LOW = 100;
    private static final int GOLD_HIGH = 32;
    private static final int COPPER_LOW = 16;
    /** The first relocation: one full stack. */
    private static final int FIRST_TRIP = 64;
    /** The second relocation: the rest of the overflow. */
    private static final int SECOND_TRIP = IRON_LOW - FIRST_TRIP;
    private static final int IRON_TOTAL = IRON_HIGH + IRON_LOW;
    /** Where a carry is frozen: halfway between the overflow chest and the chest the iron belongs in. */
    private static final double CARRY_SHOT_X = (LOW.x() + HIGH.x()) / 2.0;

    /**
     * The rows the round fills: the ghost goes into the second row, and the clipboard's two new items fill the free rows
     * in its order — the first, then the third. An order searches its items in row order, so the gold, which has
     * nowhere better to go, is searched and ended before the iron's first trip is planned.
     */
    private static final int GOLD_ROW = 0;
    private static final int IRON_ROW = 1;
    private static final int COPPER_ROW = 2;
    private static final int USED_ROWS = 3;
    /** The listed items in row order, as the optimizer keeps them and an order searches them. */
    private static final List<ItemKey> ROW_ORDER = List.of(GOLD, IRON, COPPER);
    /** The clipboard: iron (listed already when it is imported), gold and copper. */
    private static final List<ItemKey> CLIPBOARD = List.of(IRON, GOLD, COPPER);
    private static final int CLIPBOARD_ADDS = 2;
    /** The player's hotbar: the ingot that becomes the ghost, and the clipboard. */
    private static final int IRON_SLOT = 0;
    private static final int CLIPBOARD_SLOT = 1;

    // --- windows ------------------------------------------------------------------------------------------------------
    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;

    // --- timing -------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int CLICK_TIMEOUT_TICKS = 100;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int FREEZE_TIMEOUT_TICKS = 100;
    private static final int TRIP_TIMEOUT_TICKS = 1600;
    private static final int SETTLE_TICKS = 4;
    /**
     * Client ticks between two clicks on the same slot, kept above vanilla's 250 ms in which a container screen turns
     * the second click into a double click ("pick up all"). A click on a row or the list cell in between does not
     * count: the optimizer's screen answers it before the container screen sees it.
     */
    private static final int SLOT_CLICK_GAP_TICKS = 10;
    /** Ticks after the cancelled order's last trip landed in which no other trip may set off: many dispatches. */
    private static final int NO_MORE_TRIPS_TICKS = 80;
    /** How often a long wait for the crane logs what it is doing. */
    private static final int DIAGNOSE_EVERY_POLLS = 100;
    /** Blocks above the dock the player is lifted to before it flies to a view in the air. */
    private static final int LIFT = 5;
    private static final String GERMAN = "de_de";

    /**
     * Standing behind the right rack row within the optimizer's reach, looking away from the racks at the open field, so
     * nothing busy shows through the window's translucent background. The feet are on the ground, so the view holds
     * without flying.
     */
    private static final CameraView AT_OPTIMIZER = CameraView.of("optimizer", 2.5, 1.62, 3.2, 6.0, 0.8, 7.5);
    /** Across the aisle at the left rack row, where the crane carries the iron from the far end to the dock end. */
    private static final CameraView ACROSS = CameraView.of("across", 5.0, 3.2, 4.6, 4.8, 0.6, -1.2);
    /** Along the aisle from beyond its far end, the crane coming towards the camera's left. */
    private static final CameraView ALONG = CameraView.of("along", RAILS + 3.5, 2.6, 1.6, 2.0, 0.8, -0.4);

    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);
    /** Server thread: the aisle the census counts, set by the build, and what it and the player must hold. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Server thread: the trips an order may cost on this server ({@code optimizer.tripsPerRequest}). */
    private volatile int tripsPerOrder;
    /** Server thread: polls of the current wait for the crane. */
    private int polls;
    /** Client thread: the English column header, so the German round can prove it reads another one. */
    private String englishHeader = "";
    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: a spectator cannot pick a stack up onto the cursor. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("optimizer: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "optimizer run"))
                .server("optimizer: clear the area and place the creative motor", this::placeMotor)
                .server("optimizer: build the aisle, the optimizer and the three chests",
                        OptimizerVisualScenario::buildAisle)
                .server("optimizer: set the priorities of the two chests near the dock",
                        OptimizerVisualScenario::setPriorities)
                .server("optimizer: read the server's trips per order",
                        (server, context) -> tripsPerOrder = WareworksConfig.optimizerTripsPerRequest());
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_OPTIMIZER);
        if (pass == VisualPass.FLYWHEEL) {
            round(script, "");
            language.switchTo(script, "optimizer: ", GERMAN);
            round(script, "-de");
            return;
        }
        // Flywheel off, still in German: the copper row's own pip optimises copper alone, and the order finishes.
        String tag = "optimizer-nofw: ";
        openScreen(script, tag);
        script.client(tag + "click the copper row's pip", context -> click(context,
                        ScreenInput.optimizerPip(screen(context), COPPER_ROW)))
                .until(tag + "the screen shows the running order", context -> screen(context).state().order().running(),
                        CLICK_TIMEOUT_TICKS)
                .server(tag + "CHECK the pip started an order for copper alone", (server, context) -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    List<ItemKey> keys = order.entries().stream().map(OptimizerOrder.Entry::key).toList();
                    if (!order.isRunning() || !keys.equals(List.of(COPPER)))
                        throw new VisualTestException("the pip started " + order);
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS the copper pip started an order for {} alone", keys);
                });
        closeScreen(script, tag);
        freezeAtCarry(script, tag, COPPER, COPPER_LOW, 0L);
        fly(script);
        script.shotFrom(ACROSS, "carry")
                .freeze(false)
                .serverUntil(tag + "the copper is up and the order finished", (server, context) -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    return craneIdle(crane(server.overworld(), context.origin()))
                            && order.state() == OptimizerOrder.State.FINISHED;
                }, TRIP_TIMEOUT_TICKS)
                .server(tag + "CHECK the finished copper order", (server, context) -> {
                    ServerLevel level = server.overworld();
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    OptimizerOrder.Entry<ItemKey> copper = order.entryFor(COPPER).orElseThrow();
                    if (order.moved() != COPPER_LOW || copper.state() != OptimizerOrder.EntryState.DONE
                            || !order.inFlight().isEmpty() || order.tripsLeft() != tripsPerOrder - 1)
                        throw new VisualTestException("the copper order ended as " + order);
                    if (stored(level, context.origin(), LOW, COPPER) != 0)
                        throw new VisualTestException("copper is left in the overflow chest");
                    PriorityBreakdown view = controller(level, context.origin()).optimizerView(List.of(COPPER))
                            .getFirst();
                    if (view.countAt(HIGH_PRIORITY) != COPPER_LOW || view.movable() != 0)
                        throw new VisualTestException("the controller sees the copper as " + view);
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS the pip's order moved {} copper in one trip and "
                            + "finished", order.moved());
                    census(server, context, "optimizer: the copper's trip moved no item out of the aisle");
                })
                .camera(AT_OPTIMIZER);
        openScreen(script, tag);
        script.until(tag + "the screen shows the finished order with the green pip", context -> {
                    OptimizerScreenState state = screen(context).state();
                    return state.order().state() == OptimizerScreenState.OrderState.FINISHED
                            && state.order().moved() == COPPER_LOW
                            && pipOf(state, COPPER_ROW) == OptimizerScreenState.Pip.DONE;
                }, SCREEN_TIMEOUT_TICKS)
                .client(tag + "CHECK the finished screen", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    requireStatusLine(screen, WareworksLang.OPTIMIZER_FINISHED, number(COPPER_LOW));
                    requireRow(screen, COPPER_ROW, COPPER, COPPER_LOW, 0, 0);
                    requirePip(screen, IRON_ROW, OptimizerScreenState.Pip.IDLE);
                    requirePip(screen, GOLD_ROW, OptimizerScreenState.Pip.IDLE);
                });
        parkAndShoot(script, "optimizer-finished");
        closeScreen(script, tag);
        script.client("optimizer: every check passed",
                context -> LOGGER.info(PREFIX + "optimizer: ALL CHECKS PASSED in English and German"));
    }

    @Override
    public String status(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        StringBuilder status = new StringBuilder();
        ClientLevel level = minecraft.level;
        if (level != null && level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane)
            status.append(String.format(Locale.ROOT, "crane=x%.2f phase=%s held=%d frozen=%s",
                    crane.craneState().pose().x(), crane.craneState().phase(), crane.goggleInfo().heldCount(),
                    level.tickRateManager().isFrozen()));
        Screen open = minecraft.screen;
        if (!(open instanceof WarehouseOptimizerScreen optimizer))
            return status + " screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        OptimizerScreenState state = optimizer.state();
        StringBuilder rows = new StringBuilder();
        for (OptimizerScreenState.RowView row : state.rows()) {
            if (row.key().isEmpty())
                continue;
            PriorityBreakdown breakdown = row.breakdown();
            rows.append(rows.isEmpty() ? "" : ",").append(row.row()).append(':')
                    .append(row.key().get().toStack().getItem()).append('/').append(breakdown.best()).append('/')
                    .append(breakdown.below()).append('/').append(breakdown.movable())
                    .append(breakdown.movableUnknown() ? "?" : "").append('/').append(row.pip());
        }
        return status + String.format(Locale.ROOT, " screen=optimizer order=%s moved=%d trips=%d message=%s rows=[%s]",
                state.order().state(), state.order().moved(), state.order().tripsLeft(), state.message(), rows);
    }

    // --- one language round -------------------------------------------------------------------------------------------

    /** Ghost row, clipboard, Optimise, two frozen trips, cancel and the floor, from the same stock every time. */
    private void round(VisualScript script, String suffix) {
        boolean german = !suffix.isEmpty();
        String tag = "optimizer" + suffix + ": ";
        script.server(tag + "stock the chests and the player for the round", OptimizerVisualScenario::restock)
                .serverUntil(tag + "wait until the controller has indexed the round's stock",
                        OptimizerVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server(tag + "baseline census of the aisle and the player", (server, context) -> {
                    expected = SceneItemCensus.take(server.overworld(), censusBox, context.serverPlayer(server));
                    LOGGER.info(PREFIX + "optimizer: baseline census {}", SceneItemCensus.describe(expected));
                });
        script.camera(AT_OPTIMIZER);
        openScreen(script, tag);

        // 1. The empty list. The German round clears the English round's rows first, each with an empty hand.
        for (int row = 0; row < USED_ROWS; row++) {
            int cleared = row;
            script.client(tag + "click row " + cleared + " with an empty hand if it lists an item", context -> {
                        WarehouseOptimizerScreen screen = screen(context);
                        if (keyOf(screen.state(), cleared).isPresent()) {
                            requireEmptyCursor(screen);
                            click(context, ScreenInput.optimizerCell(screen, cleared));
                        }
                    })
                    .until(tag + "row " + cleared + " is empty", context -> keyOf(screen(context).state(), cleared)
                            .isEmpty(), CLICK_TIMEOUT_TICKS);
        }
        script.client(tag + "CHECK the empty list and the column headers", context -> {
            WarehouseOptimizerScreen screen = screen(context);
            for (OptimizerScreenState.RowView row : screen.state().rows())
                if (row.key().isPresent())
                    throw new VisualTestException("row " + row.row() + " still lists " + row.key().get());
            if (!german)
                requireStatusLine(screen, WareworksLang.OPTIMIZER_HINT);
            requireHeadersFit(screen);
            String header = WareworksLang.translateDirect(WareworksLang.OPTIMIZER_BEST_SHORT).getString();
            if (!german)
                englishHeader = header;
            else if (header.equals(englishHeader))
                throw new VisualTestException("the German screen shows the English header " + header);
        }).server(tag + "census after clearing the list", (server, context) -> census(server, context,
                "optimizer: clearing rows moved no item"));
        parkAndShoot(script, "optimizer-empty" + suffix);

        // 2. A ghost: the ingot on the cursor writes the second row and goes back into its slot.
        pickUp(script, tag, IRON_SLOT, IRON, "the iron ingot");
        script.client(tag + "click the iron onto the second row", context -> click(context,
                        ScreenInput.optimizerCell(screen(context), IRON_ROW)))
                .until(tag + "the second row lists iron", context -> keyOf(screen(context).state(), IRON_ROW)
                        .filter(IRON::equals).isPresent(), CLICK_TIMEOUT_TICKS)
                .client(tag + "CHECK the ingot is still on the cursor", context -> {
                    if (!ItemKey.of(screen(context).getMenu().getCarried()).equals(IRON))
                        throw new VisualTestException("the ghost took the ingot off the cursor");
                });
        putBack(script, tag, IRON_SLOT, "the iron ingot");
        script.client(tag + "CHECK the iron row's numbers", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    requireRow(screen, IRON_ROW, IRON, IRON_HIGH, IRON_LOW, IRON_LOW);
                    requirePip(screen, IRON_ROW, OptimizerScreenState.Pip.IDLE);
                    logNames(screen);
                })
                .server(tag + "CHECK the ghost on the server and the census", (server, context) -> {
                    WarehouseOptimizerBlockEntity optimizer = optimizer(server.overworld(), context.origin());
                    if (!optimizer.rows().keyAt(IRON_ROW).filter(IRON::equals).isPresent()
                            || optimizer.rows().count() != 1)
                        throw new VisualTestException("the optimizer lists " + optimizer.rows().keys());
                    census(server, context, "optimizer: the ghost row moved no item");
                });
        parkAndShoot(script, "optimizer-ghost" + suffix);
        script.client(tag + "rest the mouse on the iron row's cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.optimizerCell(screen(context), IRON_ROW)))
                .waitTicks(SETTLE_TICKS)
                .client(tag + "CHECK the cell's tooltip names every priority", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    List<String> tooltip = strings(ScreenInput.optimizerTooltip(screen,
                            ScreenInput.optimizerCell(screen, IRON_ROW)));
                    List<String> wanted = List.of(
                            WareworksLang.translateDirect(WareworksLang.OPTIMIZER_PRIORITY, HIGH_PRIORITY,
                                    number(IRON_HIGH)).getString(),
                            WareworksLang.translateDirect(WareworksLang.OPTIMIZER_PRIORITY, LOW_PRIORITY,
                                    number(IRON_LOW)).getString(),
                            WareworksLang.translateDirect(WareworksLang.OPTIMIZER_MOVABLE_NOW, number(IRON_LOW))
                                    .getString());
                    int at = -1;
                    for (String line : wanted) {
                        int next = tooltip.indexOf(line);
                        if (next <= at)
                            throw new VisualTestException("the iron cell's tooltip is " + tooltip + ", expected "
                                    + wanted + " in that order");
                        at = next;
                    }
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS the iron cell's tooltip {}", tooltip);
                })
                .shot("optimizer-tooltip" + suffix);

        // 3. The clipboard onto the list cell: gold and copper are added, the iron is listed already.
        pickUp(script, tag, CLIPBOARD_SLOT, null, "the clipboard");
        script.client(tag + "click the clipboard onto the list cell", context -> click(context,
                        ScreenInput.optimizerListCell(screen(context))))
                .until(tag + "the screen reports the added items", context -> {
                    OptimizerScreenState state = screen(context).state();
                    return state.message() == OptimizerScreenState.Message.ADDED && state.first() == CLIPBOARD_ADDS
                            && keyOf(state, COPPER_ROW).isPresent();
                }, CLICK_TIMEOUT_TICKS);
        putBack(script, tag, CLIPBOARD_SLOT, "the clipboard");
        script.client(tag + "CHECK the imported rows", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    requireRow(screen, IRON_ROW, IRON, IRON_HIGH, IRON_LOW, IRON_LOW);
                    requireRow(screen, GOLD_ROW, GOLD, GOLD_HIGH, 0, 0);
                    requireRow(screen, COPPER_ROW, COPPER, COPPER_LOW, 0, COPPER_LOW);
                    if (keyOf(screen.state(), USED_ROWS).isPresent())
                        throw new VisualTestException("the import listed the iron a second time");
                    requireStatusLine(screen, OptimizerScreenState.Message.ADDED.langKey(), number(CLIPBOARD_ADDS),
                            number(0));
                    logNames(screen);
                })
                .server(tag + "CHECK the import on the server and the census", (server, context) -> {
                    List<ItemKey> listed = optimizer(server.overworld(), context.origin()).rows().keys();
                    if (!listed.equals(ROW_ORDER))
                        throw new VisualTestException("the optimizer lists " + listed + ", expected " + ROW_ORDER);
                    census(server, context, "optimizer: the clipboard import moved no item");
                });
        parkAndShoot(script, "optimizer-imported" + suffix);

        // 4. Optimise, and the first trip frozen in the air.
        script.client(tag + "press Optimise", context -> click(context,
                        ScreenInput.optimizerOptimiseButton(screen(context))))
                .until(tag + "the screen shows the running order", context -> screen(context).state().order()
                        .running(), CLICK_TIMEOUT_TICKS)
                .server(tag + "CHECK the order lists the three items", (server, context) -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    List<ItemKey> keys = order.entries().stream().map(OptimizerOrder.Entry::key).toList();
                    if (!order.isRunning() || !keys.equals(ROW_ORDER))
                        throw new VisualTestException("Optimise started " + order);
                });
        freezeAtCarry(script, tag, IRON, FIRST_TRIP, 0L);
        script.server(tag + "CHECK the order while the first trip is carried", (server, context) -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    if (!order.isRunning() || order.moved() != 0 || order.tripsLeft() != tripsPerOrder - 1
                            || order.inFlight().size() != 1
                            || order.entryFor(GOLD).orElseThrow().state() != OptimizerOrder.EntryState.NOTHING_TO_MOVE
                            || !order.entryFor(COPPER).orElseThrow().isOpen())
                        throw new VisualTestException("during the first trip the order is " + order);
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS the first trip is under way: {}", order);
                })
                .client(tag + "CHECK the running screen", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    OptimizerScreenState.OrderLine line = screen.state().order();
                    if (!line.running() || line.moved() != 0 || line.tripsLeft() != tripsPerOrder - 1)
                        throw new VisualTestException("the screen shows the order as " + line);
                    requireStatusLine(screen, WareworksLang.OPTIMIZER_RUNNING, number(0), number(tripsPerOrder - 1));
                    requirePip(screen, IRON_ROW, OptimizerScreenState.Pip.RUNNING);
                    requirePip(screen, GOLD_ROW, OptimizerScreenState.Pip.NOTHING_TO_MOVE);
                    requirePip(screen, COPPER_ROW, OptimizerScreenState.Pip.RUNNING);
                    ScreenInput.optimizerCancelMark(screen);
                });
        parkAndShoot(script, "optimizer-running" + suffix);
        closeScreen(script, tag);
        fly(script);
        script.shotFrom(ACROSS, "carry" + suffix)
                .shotFrom(ALONG, "carry" + suffix)
                .camera(AT_OPTIMIZER);
        openScreen(script, tag);
        script.freeze(false);

        // 5. Frozen again during the second trip: the cancel mark, clicked for real.
        freezeAtCarry(script, tag, IRON, SECOND_TRIP, FIRST_TRIP);
        script.client(tag + "rest the mouse on the cancel mark", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.optimizerCancelMark(screen(context))))
                .waitTicks(SETTLE_TICKS)
                .client(tag + "CHECK the cancel mark's tooltip", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    List<String> tooltip = strings(ScreenInput.optimizerTooltip(screen,
                            ScreenInput.optimizerCancelMark(screen)));
                    String cancel = WareworksLang.translateDirect(WareworksLang.OPTIMIZER_CANCEL).getString();
                    String hint = WareworksLang.translateDirect(WareworksLang.OPTIMIZER_CANCEL_HINT).getString();
                    if (!tooltip.equals(List.of(cancel, hint)))
                        throw new VisualTestException("the cancel mark's tooltip is " + tooltip);
                    if (!screen.state().order().running())
                        throw new VisualTestException("the screen does not show the running order");
                })
                .shot("optimizer-cancel-tooltip" + suffix)
                .client(tag + "click the cancel mark", context -> click(context,
                        ScreenInput.optimizerCancelMark(screen(context))))
                .serverUntil(tag + "the server cancelled the order", (server, context) ->
                        order(server, context).state() == OptimizerOrder.State.CANCELLED, CLICK_TIMEOUT_TICKS)
                .server(tag + "CHECK the trip under way is kept", (server, context) -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    StackerCraneBlockEntity crane = crane(server.overworld(), context.origin());
                    if (order.inFlight().size() != 1 || order.moved() != FIRST_TRIP
                            || order.entryFor(IRON).orElseThrow().state() != OptimizerOrder.EntryState.STOPPED
                            || crane.currentJob().isEmpty() || crane.heldItems().count(IRON) != SECOND_TRIP)
                        throw new VisualTestException("after the cancel the order is " + order + " and the crane "
                                + describe(crane));
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS cancelled with the second trip still under way");
                    census(server, context, "optimizer: frozen at the cancel");
                })
                .freeze(false)
                .serverUntil(tag + "the second trip lands", (server, context) -> craneIdle(crane(server.overworld(),
                        context.origin())) && order(server, context).inFlight().isEmpty(), TRIP_TIMEOUT_TICKS)
                .waitTicks(NO_MORE_TRIPS_TICKS)
                .server(tag + "CHECK the cancelled order: the trip was counted, nothing more moved", (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos dock = context.origin();
                    OptimizerOrder<ItemKey, RackPosition> order = order(server, context);
                    if (order.state() != OptimizerOrder.State.CANCELLED || order.moved() != IRON_LOW
                            || order.tripsLeft() != tripsPerOrder - 2)
                        throw new VisualTestException("the cancelled order ended as " + order);
                    if (!craneIdle(crane(level, dock)))
                        throw new VisualTestException("a trip set off after the cancel: " + describe(crane(level, dock)));
                    if (stored(level, dock, HIGH, IRON) != IRON_TOTAL || stored(level, dock, LOW, IRON) != 0
                            || stored(level, dock, LOW, COPPER) != COPPER_LOW
                            || stored(level, dock, GOLD_RACK, GOLD) != GOLD_HIGH)
                        throw new VisualTestException("after the cancel the chests hold iron " + stored(level, dock,
                                HIGH, IRON) + "/" + stored(level, dock, LOW, IRON) + ", copper " + stored(level, dock,
                                LOW, COPPER) + ", gold " + stored(level, dock, GOLD_RACK, GOLD));
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS both trips moved {} iron up, the copper stayed", order
                            .moved());
                    census(server, context, "optimizer: two relocations and a cancel moved no item out of the aisle");
                })
                .until(tag + "the screen shows the cancelled order and the new numbers", context -> {
                    OptimizerScreenState state = screen(context).state();
                    return state.order().state() == OptimizerScreenState.OrderState.CANCELLED
                            && state.order().moved() == IRON_LOW && state.row(IRON_ROW)
                                    .map(row -> row.breakdown().best() == IRON_TOTAL).orElse(false);
                }, SCREEN_TIMEOUT_TICKS)
                .client(tag + "CHECK the cancelled screen", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    requireStatusLine(screen, WareworksLang.OPTIMIZER_CANCELLED, number(IRON_LOW));
                    requireRow(screen, IRON_ROW, IRON, IRON_TOTAL, 0, 0);
                    requireRow(screen, GOLD_ROW, GOLD, GOLD_HIGH, 0, 0);
                    requireRow(screen, COPPER_ROW, COPPER, COPPER_LOW, 0, COPPER_LOW);
                    requirePip(screen, IRON_ROW, OptimizerScreenState.Pip.IDLE);
                    requirePip(screen, GOLD_ROW, OptimizerScreenState.Pip.NOTHING_TO_MOVE);
                    requirePip(screen, COPPER_ROW, OptimizerScreenState.Pip.IDLE);
                });
        parkAndShoot(script, "optimizer-cancelled" + suffix);

        // 6. The floor every layout is measured against.
        floor(script, tag, "optimizer-floor" + suffix);
        closeScreen(script, tag);
    }

    /**
     * Waits on the server until the crane carries {@code amount} of {@code key} on a relocation from the overflow chest
     * up to a chest at the high priority, halfway there, with {@code moved} counted already, and freezes the world in that
     * very tick (a submitted task runs between two ticks); then waits until the client sees it and counts the items.
     */
    private void freezeAtCarry(VisualScript script, String tag, ItemKey key, int amount, long moved) {
        String what = amount + " " + key.toStack().getHoverName().getString();
        script.server(tag + "look for the trip with " + what, (server, context) -> polls = 0)
                .serverUntil(tag + "freeze the world while the crane carries " + what, (server, context) -> {
                    ServerLevel level = server.overworld();
                    StackerCraneBlockEntity crane = crane(level, context.origin());
                    boolean carrying = crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                            && crane.craneState().pose().x() <= CARRY_SHOT_X && crane.heldItems().count(key) == amount
                            && order(server, context).moved() == moved && crane.currentJob().filter(job -> isRelocationUp(job, key)).isPresent();
                    if (!carrying) {
                        if (++polls % DIAGNOSE_EVERY_POLLS == 0)
                            LOGGER.info(PREFIX + "optimizer: still waiting for the trip with {}: crane {}, order {}",
                                    what, describe(crane), order(server, context));
                        return false;
                    }
                    server.tickRateManager().setFrozen(true);
                    return true;
                }, TRIP_TIMEOUT_TICKS)
                .until(tag + "the client sees the frozen world", context -> context.minecraft().level != null
                        && context.minecraft().level.tickRateManager().isFrozen(), FREEZE_TIMEOUT_TICKS)
                .server(tag + "CHECK the frozen carry and the census", (server, context) -> {
                    StackerCraneBlockEntity crane = crane(server.overworld(), context.origin());
                    if (crane.heldItems().count(key) != amount)
                        throw new VisualTestException("the crane left the moment before the world froze: "
                                + describe(crane));
                    LOGGER.info(PREFIX + "optimizer: CHECK PASS frozen while the crane carries {}: {}", what,
                            describe(crane));
                    census(server, context, "optimizer: frozen while the crane carries " + what);
                });
    }

    /** A relocation of {@code key} out of the overflow chest into one of the two chests at the high priority. */
    private static boolean isRelocationUp(TransportJob<ItemKey, RackPosition> job, ItemKey key) {
        return job.type() == JobType.RELOCATE && job.key().equals(key) && job.source().equals(LOW)
                && (key.equals(IRON) ? job.target().equals(HIGH)
                        : job.target().equals(HIGH) || job.target().equals(GOLD_RACK));
    }

    /** The floor: 1280 x 960 at GUI scale 4, i.e. 320 x 240, and back again. */
    private void floor(VisualScript script, String tag, String label) {
        script.client(tag + "remember the window and the GUI scale", context -> {
            Window window = context.minecraft().getWindow();
            windowWidth = window.getScreenWidth();
            windowHeight = window.getScreenHeight();
            guiWidth = window.getGuiScaledWidth();
            guiHeight = window.getGuiScaledHeight();
            guiScaleOption = context.minecraft().options.guiScale().get();
        }).client(tag + "make the window " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> context.minecraft().getWindow().setWindowed(FLOOR_WIDTH, FLOOR_HEIGHT));
        ArmVisualScenario.untilOrFail(script, tag + "the framebuffer is " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> TooltipsVisualScenario.synced(context) && TooltipsVisualScenario.framebufferIs(
                        context.minecraft().getWindow(), FLOOR_WIDTH, FLOOR_HEIGHT),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client(tag + "GUI scale " + FLOOR_GUI_SCALE, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(FLOOR_GUI_SCALE);
            minecraft.resizeDisplay();
        }).waitTicks(SETTLE_TICKS).client(tag + "CHECK the window fits the floor", context -> {
            Window window = context.minecraft().getWindow();
            if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT)
                throw new VisualTestException("the floor is " + describeWindow(window));
            WarehouseOptimizerScreen screen = screen(context);
            int left = screen.getGuiLeft();
            int top = screen.getGuiTop();
            int height = screen.getMenu().layout().height();
            if (left < 0 || top < 0 || left + OptimizerMenuLayout.WIDTH > FLOOR_GUI_WIDTH
                    || top + height > FLOOR_GUI_HEIGHT)
                throw new VisualTestException("at the floor the window is " + OptimizerMenuLayout.WIDTH + "x" + height
                        + " at (" + left + ", " + top + ")");
            // Every target still answers where it is drawn.
            ScreenInput.optimizerCell(screen, IRON_ROW);
            ScreenInput.optimizerPip(screen, COPPER_ROW);
            ScreenInput.optimizerListCell(screen);
            ScreenInput.optimizerOptimiseButton(screen);
            ScreenInput.hotbarSlot(screen, IRON_SLOT);
            requireStatusLine(screen, WareworksLang.OPTIMIZER_CANCELLED, number(IRON_LOW));
            requireHeadersFit(screen);
            LOGGER.info(PREFIX + "optimizer: CHECK PASS at the floor the window is {}x{} at ({}, {})",
                    OptimizerMenuLayout.WIDTH, height, left, top);
        });
        parkAndShoot(script, label);
        script.client(tag + "put the window back",
                context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, tag + "the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client(tag + "put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, tag + "the GUI is as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight
                        && screen(context).width == guiWidth,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
    }

    // --- steps shared by the rounds -------------------------------------------------------------------------------------

    /**
     * Lifts the player into the air and lets it fly, and waits until the client has it, so the next camera view in
     * mid-air holds. Needed before every group of views in the air: a creative player standing on the ground switches
     * flying off by itself ({@code LocalPlayer#aiStep}), as it does at {@link #AT_OPTIMIZER}.
     */
    private static void fly(VisualScript script) {
        script.server("optimizer: lift the player into the air and let it fly", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            BlockPos above = context.origin().above(LIFT);
            player.teleportTo(server.overworld(), player.getX(), above.getY(), player.getZ(), player.getYRot(),
                    player.getXRot());
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("optimizer: wait until the client flies too", context -> context.minecraft().player != null
                && context.minecraft().player.getAbilities().flying, FREEZE_TIMEOUT_TICKS);
    }

    private void openScreen(VisualScript script, String tag) {
        script.server(tag + "open the optimizer's screen", (server, context) -> {
                    if (!optimizer(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the optimizer screen could not be opened");
                })
                .until(tag + "the screen shows its rows", context -> context.minecraft()
                        .screen instanceof WarehouseOptimizerScreen screen && screen.state().linked()
                        && screen.state().rows().size() == screen.getMenu().rowCount(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    private static void closeScreen(VisualScript script, String tag) {
        script.client(tag + "close the screen with Escape", context -> {
                    requireEmptyCursor(screen(context));
                    ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
                })
                .until(tag + "the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /** A real click on hotbar slot {@code slot}, which puts its stack onto the cursor. */
    private static void pickUp(VisualScript script, String tag, int slot, ItemKey key, String what) {
        script.client(tag + "pick " + what + " up onto the cursor", context -> {
                    WarehouseOptimizerScreen screen = screen(context);
                    requireEmptyCursor(screen);
                    click(context, ScreenInput.hotbarSlot(screen, slot));
                })
                .until(tag + what + " is on the cursor", context -> {
                    ItemStack carried = screen(context).getMenu().getCarried();
                    return !carried.isEmpty() && (key == null || ItemKey.of(carried).equals(key));
                }, CLICK_TIMEOUT_TICKS);
    }

    /** A real click on the now empty hotbar slot {@code slot}, which puts the cursor's stack back. */
    private static void putBack(VisualScript script, String tag, int slot, String what) {
        script.waitTicks(SLOT_CLICK_GAP_TICKS)
                .client(tag + "put " + what + " back into its slot", context -> click(context,
                        ScreenInput.hotbarSlot(screen(context), slot)))
                .until(tag + "the cursor is empty again", context -> screen(context).getMenu().getCarried().isEmpty(),
                        CLICK_TIMEOUT_TICKS);
    }

    /** Puts the cursor on the window's title, where nothing draws a tooltip or a hover, then shoots. */
    private static void parkAndShoot(VisualScript script, String label) {
        script.client("optimizer: park the mouse on the title", context -> {
            WarehouseOptimizerScreen screen = screen(context);
            ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + 12,
                    screen.getGuiTop() + 4));
        }).waitTicks(SETTLE_TICKS).shot(label);
    }

    private static void click(VisualContext context, ScreenInput.Point point) {
        ScreenInput.click(context.minecraft(), point);
    }

    // --- checks (client thread) ---------------------------------------------------------------------------------------

    /** Row {@code row} lists {@code key} with exactly these three numbers, as the server pushed them. */
    private static void requireRow(WarehouseOptimizerScreen screen, int row, ItemKey key, long best, long below,
            long movable) {
        OptimizerScreenState.RowView view = screen.state().row(row)
                .orElseThrow(() -> new VisualTestException("the screen has no row " + row));
        PriorityBreakdown breakdown = view.breakdown();
        if (!view.key().filter(key::equals).isPresent() || breakdown.best() != best || breakdown.below() != below
                || breakdown.movable() != movable || breakdown.movableUnknown())
            throw new VisualTestException(String.format(Locale.ROOT,
                    "row %d shows %s with best %d, below %d, move %d%s; expected %s %d/%d/%d", row,
                    view.key().map(ItemKey::toString).orElse("nothing"), breakdown.best(), breakdown.below(),
                    breakdown.movable(), breakdown.movableUnknown() ? "?" : "", key, best, below, movable));
        LOGGER.info(PREFIX + "optimizer: CHECK PASS row {} {}: best {}, below {}, move {}", row,
                key.toStack().getHoverName().getString(), best, below, movable);
    }

    private static void requirePip(WarehouseOptimizerScreen screen, int row, OptimizerScreenState.Pip pip) {
        OptimizerScreenState.Pip shown = pipOf(screen.state(), row);
        if (shown != pip)
            throw new VisualTestException("row " + row + " shows the pip " + shown + ", expected " + pip);
    }

    private static OptimizerScreenState.Pip pipOf(OptimizerScreenState state, int row) {
        return state.row(row).map(OptimizerScreenState.RowView::pip).orElse(OptimizerScreenState.Pip.IDLE);
    }

    /**
     * The status row says exactly {@code key} with {@code args}, and it is drawn whole: as wide as the room it has, with
     * the cancel mark beside it while an order runs.
     */
    private static void requireStatusLine(WarehouseOptimizerScreen screen, String key, Object... args) {
        String wanted = WareworksLang.translateDirect(key, args).getString();
        Component line = ScreenInput.optimizerStatusLine(screen);
        if (!line.getString().equals(wanted))
            throw new VisualTestException("the status row reads '" + line.getString() + "', expected '" + wanted + "'");
        OptimizerMenuLayout layout = screen.getMenu().layout();
        int room = screen.state().order().running() ? layout.statusTextWidthWithCancel() : layout.statusTextWidth();
        int width = font().width(line);
        if (width > room)
            throw new VisualTestException("the status row '" + wanted + "' is " + width + " px wide, its room " + room);
        LOGGER.info(PREFIX + "optimizer: CHECK PASS the status row reads '{}' ({} of {} px)", wanted, width, room);
    }

    /** No column header is wider than its field, and the first one starts right of the "Items" label. */
    private static void requireHeadersFit(WarehouseOptimizerScreen screen) {
        OptimizerMenuLayout layout = screen.getMenu().layout();
        Font font = font();
        int itemsEnd = OptimizerMenuLayout.MARGIN
                + font.width(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_ITEMS));
        List<String> widths = new ArrayList<>();
        String[] keys = {WareworksLang.OPTIMIZER_BEST_SHORT, WareworksLang.OPTIMIZER_BELOW_SHORT,
                WareworksLang.OPTIMIZER_MOVABLE_SHORT};
        for (int field = 0; field < keys.length; field++) {
            Component label = WareworksLang.translateDirect(keys[field]);
            int width = font.width(label);
            int left = layout.numberX(field) + OptimizerMenuLayout.NUMBER_WIDTH - OptimizerMenuLayout.NUMBER_PADDING
                    - width;
            if (width > OptimizerMenuLayout.NUMBER_WIDTH - OptimizerMenuLayout.NUMBER_PADDING
                    || (field == 0 && left <= itemsEnd))
                throw new VisualTestException("the header '" + label.getString() + "' is " + width + " px wide and "
                        + "starts at " + left + " (the items label ends at " + itemsEnd + ")");
            widths.add(label.getString() + "=" + width);
        }
        LOGGER.info(PREFIX + "optimizer: CHECK PASS the column headers fit: {}", widths);
    }

    /** Logs whether the three item names fit their column whole; a longer one is shortened with an ellipsis. */
    private static void logNames(WarehouseOptimizerScreen screen) {
        int room = screen.getMenu().layout().nameWidth();
        List<String> names = new ArrayList<>();
        for (ItemKey key : CLIPBOARD) {
            Component name = key.toStack().getHoverName();
            names.add(name.getString() + "=" + font().width(name) + (font().width(name) > room ? " (shortened)" : ""));
        }
        LOGGER.info(PREFIX + "optimizer: the item names in a column of {} px: {}", room, names);
    }

    private static void requireEmptyCursor(WarehouseOptimizerScreen screen) {
        if (!screen.getMenu().getCarried().isEmpty())
            throw new VisualTestException("the cursor carries " + screen.getMenu().getCarried());
    }

    private static Optional<ItemKey> keyOf(OptimizerScreenState state, int row) {
        return state.row(row).flatMap(OptimizerScreenState.RowView::key);
    }

    private static String number(long value) {
        return LangNumberFormat.format(value);
    }

    private static List<String> strings(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    private static WarehouseOptimizerScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseOptimizerScreen optimizer)
            return optimizer;
        throw new VisualTestException("the optimizer screen is not open (screen: " + open + ")");
    }

    // --- checks (server thread) ---------------------------------------------------------------------------------------

    private void census(MinecraftServer server, VisualContext context, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, step, context.serverPlayer(server));
    }

    private static OptimizerOrder<ItemKey, RackPosition> order(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        ServerLevel level = server.overworld();
        return controller(level, dock).optimizerOrder(layout(dock).rackPos(OPTIMIZER))
                .orElseThrow(() -> new VisualTestException("the optimizer has no order"));
    }

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static String describe(StackerCraneBlockEntity crane) {
        return String.format(Locale.ROOT, "phase %s at x%.2f, job %s, holding %s", crane.craneState().phase(),
                crane.craneState().pose().x(), crane.currentJob().map(job -> job.type() + " " + job.key() + " "
                        + job.source() + "->" + job.target()).orElse("none"), crane.heldItems().entries());
    }

    /** The round's stock is indexed, the priorities are read, and the view offers what the round expects. */
    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        if (controller.status() != ControllerStatus.READY || controller.isMembershipDirty()
                || controller.pendingSnapshotCount() != 0 || !crane.isControllerLinked() || !craneIdle(crane)
                || controller.storageLocations().size() != 3 || !controller.isStoragePrioritised(HIGH)
                || !controller.isStoragePrioritised(GOLD_RACK) || controller.countOf(IRON) != IRON_TOTAL
                || controller.countOf(GOLD) != GOLD_HIGH || controller.countOf(COPPER) != COPPER_LOW)
            return false;
        List<PriorityBreakdown> views = controller.optimizerView(CLIPBOARD);
        PriorityBreakdown iron = views.get(0);
        PriorityBreakdown gold = views.get(1);
        PriorityBreakdown copper = views.get(2);
        return iron.countAt(HIGH_PRIORITY) == IRON_HIGH && iron.countAt(LOW_PRIORITY) == IRON_LOW
                && iron.movable() == IRON_LOW && gold.countAt(HIGH_PRIORITY) == GOLD_HIGH && gold.movable() == 0
                && copper.countAt(LOW_PRIORITY) == COPPER_LOW && copper.movable() == COPPER_LOW;
    }

    // --- build (server thread) ----------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-2, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + 1, CLEAR_HEIGHT - 2, CLEAR_MARGIN));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        motor.generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(OPTIMIZER), WareworksBlocks.WAREHOUSE_OPTIMIZER.getDefaultState()
                .setValue(WarehouseOptimizerBlock.FACING, layout.sideDirection(OPTIMIZER.side()).getOpposite()));
        for (RackPosition rack : List.of(GOLD_RACK, HIGH, LOW)) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(layout.rackPos(rack).relative(outward),
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
    }

    /** The priorities, as holding the click on an interface's filter slot would set them; the overflow keeps 0. */
    private static void setPriorities(MinecraftServer server, VisualContext context) {
        for (RackPosition rack : List.of(GOLD_RACK, HIGH)) {
            WarehouseInterfaceBlockEntity storage = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(
                    server.overworld(), layout(context.origin()).rackPos(rack));
            if (storage == null)
                throw new VisualTestException("the interface at " + rack + " is missing");
            storage.setStorePriority(HIGH_PRIORITY);
            if (storage.storePriority() != HIGH_PRIORITY)
                throw new VisualTestException("the interface at " + rack + " refused the priority " + HIGH_PRIORITY);
        }
    }

    /**
     * The round's stock: the overflow chest with the iron and the copper, the chest the iron belongs in with its first
     * {@value #IRON_HIGH}, the gold's chest; the player with one iron ingot and the clipboard. Every round starts from
     * here, so its census is taken afresh after this step.
     */
    private static void restock(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        refill(level, chestOf(dock, LOW), IRON.toStack(IRON_LOW), COPPER.toStack(COPPER_LOW));
        refill(level, chestOf(dock, HIGH), IRON.toStack(IRON_HIGH));
        refill(level, chestOf(dock, GOLD_RACK), GOLD.toStack(GOLD_HIGH));
        ServerPlayer player = context.serverPlayer(server);
        player.getInventory().clearContent();
        player.getInventory().setItem(IRON_SLOT, IRON.toStack(1));
        player.getInventory().setItem(CLIPBOARD_SLOT, clipboard());
        player.inventoryMenu.broadcastChanges();
    }

    /** A filled-in clipboard listing the three items, one entry each, as a player writes it. */
    private static ItemStack clipboard() {
        List<ClipboardEntry> page = new ArrayList<>();
        for (ItemKey key : CLIPBOARD)
            page.add(new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                    .displayItem(key.toStack(), 1));
        ItemStack clipboard = AllBlocks.CLIPBOARD.asStack();
        clipboard.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.copyOf(page)), true));
        return clipboard;
    }

    /** Empties the chest, then puts exactly the given stacks in. */
    private static void refill(ServerLevel level, BlockPos chest, ItemStack... stacks) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        for (int slot = 0; slot < handler.getSlots(); slot++)
            handler.extractItem(slot, Integer.MAX_VALUE, false);
        for (ItemStack stack : stacks) {
            int left = stack.getCount();
            while (left > 0) {
                int portion = Math.min(left, stack.getMaxStackSize());
                if (!ItemHandlerHelper.insertItem(handler, stack.copyWithCount(portion), false).isEmpty())
                    throw new VisualTestException("the chest at " + chest + " is full");
                left -= portion;
            }
        }
    }

    /** How many of {@code key} the chest behind {@code rack} holds. */
    private static long stored(ServerLevel level, BlockPos dock, RackPosition rack, ItemKey key) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chestOf(dock, rack), null);
        if (handler == null)
            throw new VisualTestException("the chest behind " + rack + " has no item handler");
        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && ItemKey.of(stack).equals(key))
                count += stack.getCount();
        }
        return count;
    }

    private static BlockPos chestOf(BlockPos dock, RackPosition rack) {
        BranchLayout layout = layout(dock);
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseOptimizerBlockEntity optimizer(ServerLevel level, BlockPos dock) {
        WarehouseOptimizerBlockEntity optimizer = WareworksBlockEntityTypes.WAREHOUSE_OPTIMIZER.getNullable(level,
                layout(dock).rackPos(OPTIMIZER));
        if (optimizer == null)
            throw new VisualTestException("the warehouse optimizer of the aisle is missing");
        return optimizer;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the stacker crane is missing");
        return crane;
    }
}
