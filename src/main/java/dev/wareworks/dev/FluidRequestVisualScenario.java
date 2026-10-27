package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalFluids;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestKind;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.terminal.ContainerPlan;
import dev.wareworks.core.terminal.FluidQuestion;
import dev.wareworks.core.terminal.FluidRowState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
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
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "fluidrequest": asking the warehouse for a fluid at its terminal (M31, issue #21, ADR-073,
 * {@code docs/warehouse-system.md} §3.9.6), on the real terminal screen, in English and in German.
 * <p>
 * <b>The scene.</b> An aisle with a copper fluid bay of lava, a chest of empty buckets, a chest of iron, a warehouse
 * input, a production station whose pattern makes a bucket of three iron ingots, and the terminal. Every round starts
 * from the same stock: {@value #LAVA_BUCKETS} buckets of lava, {@value #EMPTIES} empty buckets and {@value #IRON} iron.
 * <p>
 * <b>A round</b>: the fluid cell above the items and its tooltip; ten buckets asked with the amount turned to 10 by the
 * real wheel and a real click on the cell, the question (ten empty buckets, ten trips, the terminal holding fewer at a
 * time) shot at the normal size and at the 320 × 240 floor (as are the partial question, the red cell's tooltip and the
 * make offer later in the round); Fetch; the crane frozen with an empty bucket at the bay and
 * right after the fill; the terminal full with the crane waiting; one lava bucket taken out through the menu with real
 * slot clicks, after which the tenth arrives; five buckets asked with two empties left (the partial question); and with
 * no empty bucket left the red cell with its pipe hint and the offer to make two buckets, Make and fetch, the made
 * buckets filled and delivered. The machine behind the production station is played by the server step that takes the
 * iron and puts the buckets into the input, as the GameTests do; the crane's every move is real.
 * <p>
 * <b>Conservation.</b> The joint item-and-fluid census ({@link SceneItemCensus}, {@link SceneFluidCensus}, the player
 * included) is asserted after every crane episode: the fluid never changes at all — it moves from the bay into
 * buckets — and the items change by exactly the fills ({@link SceneItemCensus#filled}) and the machine's declared
 * iron-for-buckets.
 * <p>
 * The first render pass runs the English round and the German one; the second pass (Flywheel off) reopens the screen.
 */
public final class FluidRequestVisualScenario implements VisualScenario {
    public static final String NAME = "fluidrequest";

    private static final String WORLD_FOLDER = "wareworks_visual_fluidrequest";
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 256;
    /** The motor while the first fill trip is photographed. */
    private static final int WATCH_RPM = 32;
    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition BAY = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition EMPTIES_CHEST = RackPosition.of(4, 0, Side.LEFT);
    private static final RackPosition IRON_CHEST = RackPosition.of(5, 0, Side.LEFT);
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;
    private static final long RUN_TIMEOUT_MILLIS = 14L * 60L * 1000L;

    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey IRON_INGOT = ItemKey.of(Items.IRON_INGOT);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final int BUCKET_MB = 1000;
    private static final int LAVA_BUCKETS = 32;
    private static final int EMPTIES = 12;
    private static final int IRON_PER_BUCKET = 3;
    private static final int MADE = 2;
    private static final int IRON = MADE * IRON_PER_BUCKET;
    /** The first request: more than the terminal's nine slots hold, so the crane has to wait. */
    private static final int FIRST = 10;
    /** The partial request, with {@code EMPTIES - FIRST} empties left. */
    private static final int PARTIAL = 5;
    private static final int LEFT_AFTER_FIRST = EMPTIES - FIRST;

    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;
    /** A vanilla tooltip's frame on each side, its line height and the gap under its first line, in GUI pixels. */
    private static final int TOOLTIP_FRAME = 4;
    private static final int TOOLTIP_LINE = 10;
    private static final int TOOLTIP_TITLE_GAP = 2;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int TRIP_TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 4;
    /** How often a long wait for the crane logs what the crane and the controller are doing. */
    private static final int DIAGNOSE_EVERY_TICKS = 100;
    /** Server thread: polls of the current crane wait, for {@link #DIAGNOSE_EVERY_TICKS}. */
    private static int polls;
    private static final String GERMAN = "de_de";

    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 1.5, 1.62, 2.7, 1.5, 0.6, 1.0);
    private static final CameraView OVER_AISLE = CameraView.of("aisle", 3.5, 1.62, 3.6, 3.2, 0.8, -1.0);

    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: the joint census expected at the next check of the running round. */
    private volatile Map<ItemKey, Long> expectedItems = Map.of();
    private volatile Map<FluidKey, Long> expectedFluid = Map.of();
    /** The English question title, so the German round can prove it reads another one. */
    private volatile String englishTitle = "";
    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: a spectator cannot move a stack between slots. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("fluidrequest: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "fluidrequest run"))
                .server("fluidrequest: clear the area and place the creative motor", this::placeMotor)
                .server("fluidrequest: build the aisle, the bay, the stations and the terminal",
                        FluidRequestVisualScenario::buildAisle)
                .server("fluidrequest: the player flies, so the camera can hold its views", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.server("fluidrequest: the player flies, so the camera can hold its views", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).waitTicks(SETTLE_TICKS);
        script.camera(AT_TERMINAL);
        if (pass == VisualPass.FLYWHEEL) {
            round(script, "");
            language.switchTo(script, "fluidrequest: ", GERMAN);
            round(script, "-de");
            return;
        }
        openScreen(script);
        script.client("fluidrequest: the reopened screen shows the lava above the items", context -> {
            WarehouseTerminalScreen screen = screen(context);
            List<TerminalFluids.Row> fluids = screen.visibleFluids();
            if (fluids.size() != 1 || !fluids.getFirst().fluid().equals(LAVA)
                    || screen.itemCellOffset() != TerminalMenuLayout.GRID_COLUMNS)
                throw new VisualTestException("the reopened screen shows the fluid cells " + fluids + " with the items"
                        + " from cell " + screen.itemCellOffset());
        });
        parkAndShoot(script, "fluid-reopened");
        closeScreen(script);
        script.client("fluidrequest: every check passed",
                context -> LOGGER.info(PREFIX + "fluidrequest: ALL CHECKS PASSED in English and German"));
    }

    @Override
    public String status(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseTerminalScreen terminal))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        FluidQuestion<ItemKey, FluidKey> question = terminal.fluidQuestion();
        return String.format(Locale.ROOT, "screen=terminal fluids=%d question=%s feedback=%s",
                terminal.visibleFluids().size(), question == null ? "none"
                        : question.servedMb() + "/" + question.askedMb() + " " + question.shortfall(),
                terminal.feedbackLine().map(Component::getString).orElse(""));
    }

    // --- one language round -----------------------------------------------------------------------------------------

    private void round(VisualScript script, String suffix) {
        boolean german = !suffix.isEmpty();
        String tag = "fluidrequest" + suffix + ": ";
        script.server(tag + "stock the scene for the round", FluidRequestVisualScenario::restock)
                .serverUntil(tag + "wait until the controller has indexed the round's stock",
                        FluidRequestVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server(tag + "baseline census", (server, context) -> {
                    Map<ItemKey, Long> items = SceneItemCensus.take(server.overworld(), censusBox,
                            context.serverPlayer(server));
                    expectedItems = items;
                    expectedFluid = SceneFluidCensus.take(server.overworld(), censusBox, items);
                    LOGGER.info(PREFIX + "fluidrequest: baseline items {} fluid {}", SceneItemCensus.describe(items),
                            SceneFluidCensus.describe(expectedFluid));
                });
        script.camera(AT_TERMINAL);
        openScreen(script);

        // 1. The fluid cell above the items, and its tooltip.
        script.client(tag + "CHECK the lava cell stands above the items", context -> {
            WarehouseTerminalScreen screen = screen(context);
            TerminalFluids.Row row = onlyFluid(screen);
            if (row.state() != FluidRowState.OK || row.storedMb() != (long) LAVA_BUCKETS * BUCKET_MB
                    || row.servableMb() != (long) EMPTIES * BUCKET_MB)
                throw new VisualTestException("the lava cell is " + row);
            if (screen.itemCellOffset() != TerminalMenuLayout.GRID_COLUMNS)
                throw new VisualTestException("the first item is drawn in cell " + screen.itemCellOffset()
                        + ", not on the row under the fluid");
            LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the lava cell {} above the items", row);
        });
        parkAndShoot(script, "fluid-rows" + suffix);
        script.client(tag + "rest the mouse on the lava cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalFluidCell(screen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .client(tag + "CHECK the cell's tooltip", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    List<String> tooltip = strings(tooltipAtMouse(context, screen));
                    String stored = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_STORED,
                            WarehouseTerminalScreen.fluidAmountText((long) LAVA_BUCKETS * BUCKET_MB)).getString();
                    String fills = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_FILLS,
                            BUCKET.toStack().getHoverName(), LangNumberFormat.format(BUCKET_MB)).getString();
                    if (!tooltip.contains(stored) || !tooltip.contains(fills))
                        throw new VisualTestException("the lava cell's tooltip is " + tooltip + ", expected '" + stored
                                + "' and '" + fills + "'");
                    LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the lava tooltip {}", tooltip);
                })
                .shot("fluid-tooltip" + suffix);

        // 2. Ten buckets: the question, at the normal size and at the floor.
        amount(script, tag, FIRST);
        clickLava(script, tag);
        script.client(tag + "CHECK the question names ten buckets, ten trips and the terminal's room", context -> {
            WarehouseTerminalScreen screen = screen(context);
            FluidQuestion<ItemKey, FluidKey> question = question(screen);
            if (question.servedMb() != (long) FIRST * BUCKET_MB || question.trips() != FIRST
                    || !question.portions().equals(List.of(new ContainerPlan.Portion<>(BUCKET, LAVA_BUCKET, BUCKET_MB,
                            FIRST))) || !question.overflowsStation())
                throw new VisualTestException("the question is " + question);
            List<String> lines = strings(screen.fluidQuestionLines(question));
            String trips = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_QUESTION_TRIPS,
                    String.valueOf(FIRST), "1").getString();
            String room = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_QUESTION_STATION_ROOM,
                    String.valueOf(question.stationSlots())).getString();
            if (!lines.contains(trips) || !lines.contains(room))
                throw new VisualTestException("the question reads " + lines + ", expected '" + trips + "' and '" + room
                        + "'");
            if (!german)
                englishTitle = lines.getFirst();
            else if (lines.getFirst().equals(englishTitle))
                throw new VisualTestException("the German question has the English title " + englishTitle);
            LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the question reads {}", lines);
        }).server(tag + "asking requested nothing", FluidRequestVisualScenario::checkNothingRequested);
        parkAndShoot(script, "fluid-question" + suffix);
        floor(script, tag, "fluid-floor" + suffix);
        script.client(tag + "press Fetch", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalConfirmButton(screen(context), true)))
                .serverUntil(tag + "one fill request for ten buckets is open", (server, context) ->
                        fillRequestFor(server, context, FIRST), SCREEN_TIMEOUT_TICKS)
                .until(tag + "the screen shows the fill line", context -> !screen(context).visibleFillLines().isEmpty()
                        && !screen(context).isAskingSomething(), SCREEN_TIMEOUT_TICKS)
                .client(tag + "CHECK the fill line", context -> {
                    TerminalFluids.FillLine line = screen(context).visibleFillLines().getFirst();
                    if (!line.fluid().equals(LAVA) || !line.filled().equals(LAVA_BUCKET))
                        throw new VisualTestException("the fill line is " + line);
                    LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the order line {}", line);
                });
        closeScreen(script);

        // 3. The trip, frozen at the bay and right after the fill, then the full terminal.
        // Slowed down while the first trip is watched: the harness polls the server every few client ticks, and at full
        // speed the crane spends only a tick or two at the bay.
        script.server(tag + "slow the crane down for the shots", (server, context) -> speed(server, context,
                        WATCH_RPM))
                .camera(OVER_AISLE)
                .serverUntil(tag + "the crane holds an empty bucket at the bay",
                        (server, context) -> craneAtBay(server, context, BUCKET, CranePhase.EXTEND_TARGET),
                        TRIP_TIMEOUT_TICKS)
                .freeze(true)
                .shot("fluid-filling" + suffix)
                .freeze(false)
                .serverUntil(tag + "the bay has filled the bucket",
                        (server, context) -> craneAtBay(server, context, LAVA_BUCKET, CranePhase.RETRACT_TARGET),
                        TRIP_TIMEOUT_TICKS)
                .freeze(true)
                .shot("fluid-filled" + suffix)
                .freeze(false)
                .server(tag + "the crane back at full speed", (server, context) -> speed(server, context, MOTOR_RPM))
                .serverUntil(tag + "the terminal is full and the tenth waits",
                        FluidRequestVisualScenario::waitingAtFullTerminal, TRIP_TIMEOUT_TICKS)
                .shot("fluid-station-full" + suffix)
                .server(tag + "census with the terminal full", (server, context) -> {
                    expectedItems = SceneItemCensus.filled(expectedItems, BUCKET, LAVA_BUCKET, FIRST - 1);
                    assertConserved(server, context, "nine buckets filled and delivered, the tenth not yet filled");
                });
        script.camera(AT_TERMINAL);
        openScreen(script);
        script.client(tag + "pick a lava bucket up from the terminal's buffer", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.menuSlot(screen(context), 0)))
                .until(tag + "the bucket is on the cursor",
                        context -> LAVA_BUCKET.matches(screen(context).getMenu().getCarried()), SCREEN_TIMEOUT_TICKS)
                .client(tag + "put it into the player's inventory", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.menuSlot(screen(context), screen(context).getMenu().firstPlayerSlot())))
                .until(tag + "the cursor is empty", context -> screen(context).getMenu().getCarried().isEmpty(),
                        SCREEN_TIMEOUT_TICKS)
                .serverUntil(tag + "the tenth lava bucket arrived and the request is served",
                        FluidRequestVisualScenario::servedAndIdle, TRIP_TIMEOUT_TICKS)
                .server(tag + "census after the tenth", (server, context) -> {
                    expectedItems = SceneItemCensus.filled(expectedItems, BUCKET, LAVA_BUCKET, 1);
                    assertConserved(server, context, "ten lava buckets delivered, one of them in the player's hands");
                })
                // The player puts the nine away: the buffer is emptied into the player's inventory, inside the census.
                .server(tag + "the player takes the other lava buckets", FluidRequestVisualScenario::emptyBuffer)
                .server(tag + "census after emptying the buffer", (server, context) -> assertConserved(server, context,
                        "the buffer emptied into the player's inventory"));

        // 4. Five asked with two empties left: the partial question.
        amount(script, tag, PARTIAL);
        clickLava(script, tag);
        script.client(tag + "CHECK the partial question", context -> {
            FluidQuestion<ItemKey, FluidKey> question = question(screen(context));
            if (question.servedMb() != (long) LEFT_AFTER_FIRST * BUCKET_MB
                    || question.shortfall() != ContainerPlan.Shortfall.NOT_ENOUGH_CONTAINERS)
                throw new VisualTestException("the partial question is " + question);
            List<String> lines = strings(screen(context).fluidQuestionLines(question));
            String only = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_QUESTION_ONLY,
                    WarehouseTerminalScreen.bucketsText((long) LEFT_AFTER_FIRST * BUCKET_MB)).getString();
            if (!lines.contains(only))
                throw new VisualTestException("the partial question reads " + lines + ", expected '" + only + "'");
            LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the partial question reads {}", lines);
        });
        parkAndShoot(script, "fluid-partial" + suffix);
        floor(script, tag, "fluid-partial-floor" + suffix);
        script.client(tag + "press Fetch for the two", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalConfirmButton(screen(context), true)))
                .serverUntil(tag + "the two arrived", FluidRequestVisualScenario::servedAndIdle, TRIP_TIMEOUT_TICKS)
                .server(tag + "census after the two", (server, context) -> {
                    expectedItems = SceneItemCensus.filled(expectedItems, BUCKET, LAVA_BUCKET, LEFT_AFTER_FIRST);
                    assertConserved(server, context, "two more lava buckets");
                });

        // 5. No empty bucket left: the red cell, the offer, Make and fetch.
        script.server(tag + "the player brings iron", this::bringIron)
                .serverUntil(tag + "the iron is indexed", (server, context) -> controller(server.overworld(),
                        context.origin()).countOf(IRON_INGOT) == IRON, SCREEN_TIMEOUT_TICKS)
                .server(tag + "census with the iron", (server, context) -> assertConserved(server, context,
                        "the iron brought in"))
                .until(tag + "the lava cell turns red with an offer", context -> onlyFluid(screen(context)).state()
                        == FluidRowState.NO_EMPTY_CONTAINERS_MAKEABLE, SCREEN_TIMEOUT_TICKS)
                .client(tag + "rest the mouse on the red cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalFluidCell(screen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .client(tag + "CHECK the red cell's tooltip", context -> {
                    List<String> tooltip = strings(tooltipAtMouse(context, screen(context)));
                    String none = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_NO_EMPTIES).getString();
                    String make = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_MAKE_HINT,
                            BUCKET.toStack().getHoverName()).getString();
                    String pipe = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_PIPE_HINT).getString();
                    String joined = String.join(" ", tooltip);
                    if (!tooltip.contains(none) || !tooltip.contains(make) || !joined.contains(pipe))
                        throw new VisualTestException("the red cell's tooltip is " + tooltip);
                    LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the red cell's tooltip {}", tooltip);
                })
                .shot("fluid-red" + suffix);
        redTooltipFloor(script, tag, "fluid-red-floor" + suffix);
        amount(script, tag, MADE);
        clickLava(script, tag);
        script.client(tag + "CHECK the offer to make two buckets", context -> {
            FluidQuestion<ItemKey, FluidKey> question = question(screen(context));
            if (!question.portions().isEmpty() || question.make().isEmpty() || question.make().get().count() != MADE
                    || !question.make().get().container().equals(BUCKET))
                throw new VisualTestException("the offer is " + question);
            LOGGER.info(PREFIX + "fluidrequest: CHECK PASS the offer {}", strings(screen(context)
                    .fluidQuestionLines(question)));
        }).server(tag + "the offer started nothing", FluidRequestVisualScenario::checkNothingRequested);
        parkAndShoot(script, "fluid-make-offer" + suffix);
        floor(script, tag, "fluid-make-offer-floor" + suffix);
        script.client(tag + "press Make and fetch", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalConfirmButton(screen(context), true)))
                .serverUntil(tag + "the iron reached the production station", FluidRequestVisualScenario::ironAtStation,
                        TRIP_TIMEOUT_TICKS)
                .server(tag + "the machine makes two buckets of the iron", this::playMachine)
                .serverUntil(tag + "the made buckets arrived filled", FluidRequestVisualScenario::servedAndIdle,
                        TRIP_TIMEOUT_TICKS)
                .server(tag + "census after the made buckets", (server, context) -> {
                    expectedItems = SceneItemCensus.filled(expectedItems, BUCKET, LAVA_BUCKET, MADE);
                    assertConserved(server, context, "two buckets made, filled and delivered");
                });
        parkAndShoot(script, "fluid-made" + suffix);
        closeScreen(script);
    }

    // --- steps --------------------------------------------------------------------------------------------------------

    private static void openScreen(VisualScript script) {
        script.server("fluidrequest: open the terminal screen", (server, context) -> {
                    if (!(context.minecraft().screen instanceof WarehouseTerminalScreen)
                            && !terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the terminal screen could not be opened");
                })
                .until("fluidrequest: the screen shows its stock and the lava",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && !terminal.visibleFluids().isEmpty(),
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    private static void closeScreen(VisualScript script) {
        script.client("fluidrequest: close the screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("fluidrequest: the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /** Turns the amount field to {@code buckets} with the real wheel. */
    private static void amount(VisualScript script, String tag, int buckets) {
        ArmVisualScenario.untilOrFail(script, tag + "turn the amount field to " + buckets, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == buckets)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < buckets ? 1 : -1);
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
    }

    /** A real click on the lava cell, which a fluid request always answers with its question. */
    private static void clickLava(VisualScript script, String tag) {
        script.client(tag + "click the lava cell", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalFluidCell(screen(context), 0)))
                .until(tag + "the fluid question is on screen", context -> screen(context).fluidQuestion() != null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /** The floor every layout is measured against: 1280 x 960 at GUI scale 4, i.e. 320 x 240, and back again. */
    /**
     * The question that is up, at the 320 × 240 floor (1280 × 960 at GUI scale 4): the whole panel and both buttons
     * inside the floor, shot as {@code label}, and the window and scale put back with the question still up.
     */
    private void floor(VisualScript script, String tag, String label) {
        enterFloor(script, tag);
        script.client(tag + "CHECK the question fits the floor (" + label + ")", context -> {
            WarehouseTerminalScreen screen = screen(context);
            if (screen.fluidQuestion() == null)
                throw new VisualTestException("the resize dropped the question");
            ScreenRectangle panel = screen.confirmPanelBounds();
            ScreenInput.Point fetch = ScreenInput.terminalConfirmButton(screen, true);
            ScreenInput.Point cancel = ScreenInput.terminalConfirmButton(screen, false);
            if (panel == null || panel.left() < 0 || panel.top() < 0 || panel.right() > FLOOR_GUI_WIDTH
                    || panel.bottom() > FLOOR_GUI_HEIGHT)
                throw new VisualTestException("the question panel " + describe(panel) + " is off the "
                        + FLOOR_GUI_WIDTH + "x" + FLOOR_GUI_HEIGHT + " floor");
            if (screen.confirmButtonBottom() > FLOOR_GUI_HEIGHT || screen.confirmButtonBottom() > panel.bottom()
                    || fetch.x() < panel.left() || cancel.x() > panel.right() || cancel.x() > FLOOR_GUI_WIDTH)
                throw new VisualTestException("the buttons are off the floor: " + fetch + " " + cancel
                        + ", bottom " + screen.confirmButtonBottom() + ", panel " + describe(panel));
            LOGGER.info(PREFIX + "fluidrequest: CHECK PASS at the floor ({}) the panel is {} and the buttons are at {}"
                    + " and {}", label, describe(panel), fetch, cancel);
        });
        script.client(tag + "park the mouse", context -> ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(2, 2))).waitTicks(SETTLE_TICKS).shot(label);
        leaveFloor(script, tag, true);
    }

    /**
     * The red cell's tooltip at the floor: "no empty containers", the wrapped pipe hint and the make hint, every line
     * inside the floor's width and all of them inside its height, shot as {@code label}.
     */
    private void redTooltipFloor(VisualScript script, String tag, String label) {
        enterFloor(script, tag);
        script.client(tag + "rest the mouse on the red cell at the floor", context -> ScreenInput.hover(
                        context.minecraft(), ScreenInput.terminalFluidCell(screen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .client(tag + "CHECK the red cell's tooltip fits the floor", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    List<Component> tooltip = tooltipAtMouse(context, screen);
                    String none = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_NO_EMPTIES).getString();
                    String make = WareworksLang.translateDirect(WareworksLang.TERMINAL_FLUID_MAKE_HINT,
                            BUCKET.toStack().getHoverName()).getString();
                    List<String> lines = strings(tooltip);
                    if (!lines.contains(none) || !lines.contains(make))
                        throw new VisualTestException("the red cell's tooltip at the floor is " + lines);
                    int widest = 0;
                    for (Component line : tooltip)
                        widest = Math.max(widest, context.minecraft().font.width(line));
                    // A vanilla tooltip adds a frame on each side, 10 px per line and a gap under its first line.
                    int width = widest + 2 * TOOLTIP_FRAME;
                    int height = tooltip.size() * TOOLTIP_LINE + TOOLTIP_TITLE_GAP + 2 * TOOLTIP_FRAME;
                    if (width > FLOOR_GUI_WIDTH || height > FLOOR_GUI_HEIGHT)
                        throw new VisualTestException("the red cell's tooltip is " + width + "x" + height
                                + " at the " + FLOOR_GUI_WIDTH + "x" + FLOOR_GUI_HEIGHT + " floor: " + lines);
                    LOGGER.info(PREFIX + "fluidrequest: CHECK PASS at the floor the red tooltip is {}x{}: {}", width,
                            height, lines);
                })
                .shot(label);
        leaveFloor(script, tag, false);
    }

    /** Remembers the window and the GUI scale, then makes the GUI 320 × 240. */
    private void enterFloor(VisualScript script, String tag) {
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
        }).waitTicks(SETTLE_TICKS).client(tag + "CHECK the GUI is " + FLOOR_GUI_WIDTH + "x" + FLOOR_GUI_HEIGHT,
                context -> {
                    Window window = context.minecraft().getWindow();
                    if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH
                            || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT)
                        throw new VisualTestException("the floor is " + describeWindow(window));
                });
    }

    /** Puts the window and the GUI scale back; {@code asking}: the question must still be up afterwards. */
    private void leaveFloor(VisualScript script, String tag, boolean asking) {
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
                        && screen(context).width == guiWidth && (!asking || screen(context).fluidQuestion() != null),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
    }

    private static String describe(ScreenRectangle rectangle) {
        return rectangle == null ? "none" : String.format(Locale.ROOT, "%d,%d %dx%d", rectangle.left(),
                rectangle.top(), rectangle.width(), rectangle.height());
    }

    /** Puts the cursor in a corner of the window, where nothing has a tooltip, then shoots. */
    private static void parkAndShoot(VisualScript script, String label) {
        script.client("fluidrequest: park the mouse", context -> ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(2, 2))).waitTicks(SETTLE_TICKS).shot(label);
    }

    // --- checks (client thread) -------------------------------------------------------------------------------------

    private static TerminalFluids.Row onlyFluid(WarehouseTerminalScreen screen) {
        List<TerminalFluids.Row> fluids = screen.visibleFluids();
        if (fluids.size() != 1)
            throw new VisualTestException("the screen shows the fluid cells " + fluids);
        return fluids.getFirst();
    }

    private static FluidQuestion<ItemKey, FluidKey> question(WarehouseTerminalScreen screen) {
        FluidQuestion<ItemKey, FluidKey> question = screen.fluidQuestion();
        if (question == null)
            throw new VisualTestException("the terminal is not asking about a fluid");
        return question;
    }

    private static List<Component> tooltipAtMouse(VisualContext context, WarehouseTerminalScreen screen) {
        Minecraft minecraft = context.minecraft();
        Window window = minecraft.getWindow();
        int x = (int) (minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
        int y = (int) (minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
        return screen.tooltipAt(x, y);
    }

    private static List<String> strings(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    // --- checks (server thread) -------------------------------------------------------------------------------------

    private void assertConserved(MinecraftServer server, VisualContext context, String step) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        Map<ItemKey, Long> items = SceneItemCensus.take(level, censusBox, player);
        Map<FluidKey, Long> fluid = SceneFluidCensus.take(level, censusBox, items);
        if (!items.equals(expectedItems) || !fluid.equals(expectedFluid))
            throw new VisualTestException("conservation violated after " + step + ": items expected "
                    + SceneItemCensus.describe(expectedItems) + " but found " + SceneItemCensus.describe(items)
                    + "; fluid expected " + SceneFluidCensus.describe(expectedFluid) + " but found "
                    + SceneFluidCensus.describe(fluid));
        LOGGER.info(PREFIX + "fluidrequest: census PASS after {} (items {}, fluid {} mB)", step,
                SceneItemCensus.describe(items), SceneFluidCensus.describe(fluid));
    }

    private static void checkNothingRequested(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        if (!controller.openRequests().isEmpty() || !controller.openProductionOrders().isEmpty())
            throw new VisualTestException("asking made " + controller.openRequests().size() + " requests and "
                    + controller.openProductionOrders().size() + " production orders");
    }

    private static boolean fillRequestFor(MinecraftServer server, VisualContext context, int containers) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = controller(server.overworld(), context.origin())
                .openRequests();
        return open.size() == 1 && open.getFirst().kind() == RequestKind.FILL
                && open.getFirst().requested() == containers;
    }

    private static boolean craneAtBay(MinecraftServer server, VisualContext context, ItemKey held, CranePhase phase) {
        StackerCraneBlockEntity crane = crane(server.overworld(), context.origin());
        Optional<TransportJob<ItemKey, RackPosition>> job = crane.currentJob();
        if (++polls % DIAGNOSE_EVERY_TICKS == 0) {
            WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
            LOGGER.info(PREFIX + "fluidrequest: waiting for {} at the bay: phase {} job {} held {} reason {} requests {}"
                    + " bay {} mB", held, crane.craneState().phase(), job.orElse(null), crane.heldItems().totalCount(),
                    controller.lastPlanReason().orElse(null), controller.openRequests(),
                    controller.fluidStockIndex().count(LAVA));
        }
        return job.filter(found -> found.type() == JobType.FILL).isPresent()
                && (crane.craneState().phase() == phase || (phase == CranePhase.EXTEND_TARGET
                        && crane.craneState().phase() == CranePhase.DROP))
                && crane.heldItems().count(held) == 1
                && (phase != CranePhase.EXTEND_TARGET || job.get().target().equals(BAY));
    }

    /**
     * Nine lava buckets fill the terminal's nine slots and the tenth is not planned: the planner sends no fill trip to a
     * station that takes nothing ({@code OUTPUT_FULL}), so the crane waits at rest with the request still open.
     */
    private static boolean waitingAtFullTerminal(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        StackerCraneBlockEntity crane = crane(level, context.origin());
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        return crane.craneState().phase() == CranePhase.IDLE && crane.heldItems().isEmpty()
                && terminal(level, context.origin()).bufferedItems().count(LAVA_BUCKET) == 9L
                && controller.openRequests().size() == 1 && controller.openRequests().getFirst().remaining() == 1
                && controller.lastPlanReason().filter(reason -> reason == NoJobReason.OUTPUT_FULL)
                        .isPresent();
    }

    private static boolean servedAndIdle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        StackerCraneBlockEntity crane = crane(level, context.origin());
        return controller.openRequests().isEmpty() && controller.openProductionOrders().isEmpty()
                && crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static boolean ironAtStation(MinecraftServer server, VisualContext context) {
        return production(server.overworld(), context.origin()).bufferedItems().count(IRON_INGOT) >= IRON;
    }

    // --- scene changes (server thread) --------------------------------------------------------------------------------

    /** The player's machine: the iron out of the station, the made buckets into the warehouse input. Declared. */
    private void playMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseProductionBlockEntity station = production(level, context.origin());
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, station.getBlockPos(), null);
        if (handler == null)
            throw new VisualTestException("the production station has no item handler");
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (IRON_INGOT.matches(handler.getStackInSlot(slot)))
                taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        if (taken != IRON)
            throw new VisualTestException("the machine found " + taken + " iron, expected " + IRON);
        BlockPos input = layout(context.origin()).rackPos(INPUT);
        IItemHandler intake = level.getCapability(Capabilities.ItemHandler.BLOCK, input, null);
        if (intake == null || !ItemHandlerHelper.insertItem(intake, BUCKET.toStack(MADE), false).isEmpty())
            throw new VisualTestException("the warehouse input did not take the made buckets");
        expectedItems = SceneItemCensus.plus(SceneItemCensus.plus(expectedItems, IRON_INGOT, -IRON), BUCKET, MADE);
        LOGGER.info(PREFIX + "fluidrequest: the machine turned {} iron into {} buckets", IRON, MADE);
    }

    /** The player puts the lava buckets of the buffer into their inventory, which the census counts. */
    private static void emptyBuffer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context.origin());
        IItemHandler buffer = terminal.externalHandler();
        ServerPlayer player = context.serverPlayer(server);
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack taken = buffer.extractItem(slot, Integer.MAX_VALUE, false);
            if (!taken.isEmpty() && !player.getInventory().add(taken))
                throw new VisualTestException("the player's inventory is full");
        }
    }

    /**
     * The round's stock: the bay at {@value #LAVA_BUCKETS} buckets of lava, {@value #EMPTIES} empty buckets and
     * {@value #IRON} iron, an empty terminal buffer and an empty player inventory. Not a crane move: the baseline is
     * taken after it.
     */
    private static void restock(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        context.serverPlayer(server).getInventory().clearContent();
        IItemHandler buffer = terminal(level, dock).externalHandler();
        for (int slot = 0; slot < buffer.getSlots(); slot++)
            buffer.extractItem(slot, Integer.MAX_VALUE, false);
        FluidBayBlockEntity bay = WareworksBlockEntityTypes.FLUID_BAY.getNullable(level, layout.rackPos(BAY));
        if (bay == null)
            throw new VisualTestException("the fluid bay is missing");
        bay.drain(Integer.MAX_VALUE, false);
        int filled = bay.fill(new FluidStack(Fluids.LAVA, LAVA_BUCKETS * BUCKET_MB), false);
        if (filled != LAVA_BUCKETS * BUCKET_MB)
            throw new VisualTestException("the bay took " + filled + " mB of lava");
        refill(level, chestOf(layout, EMPTIES_CHEST), new ItemStack(Items.BUCKET, EMPTIES));
        refill(level, chestOf(layout, IRON_CHEST), ItemStack.EMPTY);
    }

    /**
     * The player brings the iron for the offer only once the partial request is served: with iron in stock the partial
     * question would already offer to make the missing buckets. Declared in the census.
     */
    private void bringIron(MinecraftServer server, VisualContext context) {
        refill(server.overworld(), chestOf(layout(context.origin()), IRON_CHEST), new ItemStack(Items.IRON_INGOT, IRON));
        expectedItems = SceneItemCensus.plus(expectedItems, IRON_INGOT, IRON);
    }

    private static void refill(ServerLevel level, BlockPos chest, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        for (int slot = 0; slot < handler.getSlots(); slot++)
            handler.extractItem(slot, Integer.MAX_VALUE, false);
        int left = stack.isEmpty() ? 0 : stack.getCount();
        while (left > 0) {
            int portion = Math.min(left, stack.getMaxStackSize());
            if (!ItemHandlerHelper.insertItem(handler, stack.copyWithCount(portion), false).isEmpty())
                throw new VisualTestException("the chest at " + chest + " is full");
            left -= portion;
        }
    }

    private static void speed(MinecraftServer server, VisualContext context, int rpm) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(server.overworld(),
                context.origin().below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        motor.generatedSpeed.setValue(rpm);
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN));
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
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward(layout, PRODUCTION)));
        WarehouseProductionBlockEntity station = production(level, dock);
        if (!station.setPatternEntry(0, 0, IRON_INGOT, IRON_PER_BUCKET)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BUCKET, 1))
            throw new VisualTestException("the bucket pattern was not written");
        level.setBlockAndUpdate(layout.rackPos(BAY), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, layout.sideDirection(BAY.side())));
        for (RackPosition rack : List.of(EMPTIES_CHEST, IRON_CHEST)) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(chestOf(layout, rack),
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && crane.isControllerLinked()
                && controller.fluidStockIndex().count(LAVA) == (long) LAVA_BUCKETS * BUCKET_MB
                && controller.countOf(BUCKET) == EMPTIES && controller.countOf(IRON_INGOT) == 0
                && controller.countOf(LAVA_BUCKET) == 0 && controller.producibleKeys().contains(BUCKET)
                && controller.openRequests().isEmpty() && controller.openProductionOrders().isEmpty()
                && crane.currentJob().isEmpty() && crane.heldItems().isEmpty();
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos chestOf(BranchLayout layout, RackPosition rack) {
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseProductionBlockEntity production(ServerLevel level, BlockPos dock) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout(dock).rackPos(PRODUCTION));
        if (station == null)
            throw new VisualTestException("the production station is missing");
        return station;
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
            throw new VisualTestException("the crane is missing");
        return crane;
    }
}
