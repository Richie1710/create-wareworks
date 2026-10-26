package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.CrafterFeederGoggleSummary;
import dev.wareworks.content.station.ProductionGoggleSummary;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseCrafterFeederBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FeederStatus;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "feeder" (issue #34, ADR-067): a <b>vanilla Crafter</b> behind a production station crafts what the
 * warehouse orders, fed by a Warehouse Crafter Feeder — and Wareworks itself still crafts nothing.
 * <p>
 * One aisle holds a powered stacker crane, a production station with a feeder on its back, the Crafter behind the next
 * rack position with its output facing a warehouse input there, and a warehouse terminal. The run, every claim asserted
 * on the server or on the client block entity that draws it before the shot that shows it:
 * <ol>
 *   <li>the torch pattern is written into the station's screen with real clicks ({@link ScreenInput}): coal into cell
 *       0, a stick into cell 3, torches as the result and the mouse wheel up to four; a second slot gets "dirt makes a
 *       diamond", which the screen draws red — the tab's tooltip and the arrow name the reason ({@code feeder-invalid})
 *       — and which the warehouse never offers;</li>
 *   <li>sixteen torches are ordered with a click on the terminal's stock cell; the crane delivers four coal and four
 *       sticks, the feeder lays all four runs as one batch and locks the seven slots the torch leaves empty
 *       ({@code feeder-laid}: its goggles), pulses once per run, and the Crafter's crafting face is caught lit
 *       ({@code feeder-pulse}, the ticks frozen on the server in the very tick the Crafter crafts);</li>
 *   <li>the torches come back through the input, the order completes, the terminal holds them, the Crafter has its
 *       slots back and the feeder is idle again ({@code feeder-done});</li>
 *   <li>the same screen and goggles in German, compared against the English texts so a key missing from
 *       {@code de_de.json} cannot pass as a translation.</li>
 * </ol>
 * The item census of the scene ({@link SceneItemCensus}) is on the <b>craft ledger</b> at every poll: the baseline plus
 * a whole number of torch crafts (one coal and one stick in, four torches out), never anything else. The second pass
 * (Flywheel off) only repeats the world shots. Grep {@code feeder: ALL CHECKS PASSED in English and German}.
 */
public final class FeederVisualScenario implements VisualScenario {
    public static final String NAME = "feeder";

    private static final String WORLD_FOLDER = "wareworks_visual_feeder";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 5;
    private static final int CRANE_RPM = 128;
    /**
     * Slow on purpose: at 2 RPM a run takes {@code 256 / 2 = 128} ticks ({@code FeederCycle#ticksPerRun}), so the laid
     * grid holds still long enough for a goggle shot that has to wait for the overlay to fade in.
     */
    private static final int FEEDER_RPM = 2;

    private static final RackPosition STATION = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = RackPosition.of(3, 0, Side.LEFT);
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(1, 0, Side.LEFT),
            RackPosition.of(2, 0, Side.LEFT), RackPosition.of(4, 0, Side.LEFT), RackPosition.of(4, 0, Side.RIGHT));

    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final int IN_STOCK = 16;

    private static final int TORCH_SLOT = 0;
    private static final int INVALID_SLOT = 1;
    private static final int TOP_LEFT = 0;
    private static final int MIDDLE_LEFT = 3;
    private static final int TORCHES_PER_RUN = 4;
    private static final int ORDERED = 16;
    private static final int RUNS = ORDERED / TORCHES_PER_RUN;
    /** One torch craft as a census change. */
    private static final Map<ItemKey, Long> TORCH_RUN = Map.of(COAL, -1L, STICK, -1L, TORCH, (long) TORCHES_PER_RUN);

    /** The hotbar slots the pattern items are picked up from. */
    private static final int HOTBAR_COAL = 0;
    private static final int HOTBAR_STICK = 1;
    private static final int HOTBAR_TORCH = 2;
    private static final int HOTBAR_DIRT = 3;
    private static final int HOTBAR_DIAMOND = 4;
    private static final int MAX_SCROLL_NOTCHES = 32;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int LAID_TIMEOUT_TICKS = 1800;
    private static final int PULSE_TIMEOUT_TICKS = 600;
    private static final int DONE_TIMEOUT_TICKS = 3600;
    private static final int LANGUAGE_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 6;
    private static final int CLICK_GAP_TICKS = 3;
    private static final long RUN_TIMEOUT_MILLIS = 12L * 60L * 1000L;

    // Cameras, relative to the lower corner of the dock block; the aisle runs along +x (rack position n at x = n), the
    // right rack wall is z = 1: station x = 1, input x = 2, feeder and Crafter behind them at z = 2.
    /** The machine from outside the right rack wall: station, feeder with its motor, Crafter and input. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 0.0, 3.4, 5.6, 1.8, 0.4, 1.0);
    /** Close on the feeder and the Crafter in front of it. */
    private static final CameraView MACHINE = CameraView.of("machine", 1.2, 2.0, 4.9, 2.0, 0.5, 2.4);
    /** Within reach of the feeder's south face, for its goggles; low enough to pass under its motor. */
    private static final CameraView AT_FEEDER = CameraView.of("feeder", 1.5, 1.9, 4.6, 1.5, 0.6, 2.6);
    /** The Crafter's face from outside, for the moment it crafts. */
    private static final CameraView AT_CRAFTER = CameraView.of("crafter", 2.6, 2.2, 4.4, 2.5, 0.5, 2.4);
    /** In the aisle in front of the station, for its screen and its goggles. */
    private static final CameraView AT_STATION = CameraView.of("station", 1.3, 2.0, 0.35, 1.5, 0.5, 1.05);
    /** In the aisle in front of the terminal on the left rack wall. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.3, 2.0, 0.65, 3.5, 0.5, -0.05);

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> baseline = Map.of();
    /** The English texts, so the German round can prove it is not reading them again. */
    private volatile String englishRefusal = "";
    private volatile String englishIdle = "";
    /** Whether a {@link #laid} poll has seen the feeder's signal high, i.e. whether a run may already be crafted. */
    private volatile boolean edgeSeen;
    private volatile String englishFeederLine = "";

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
        script.client("feeder: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "feeder run"))
                .server("feeder: clear the area and place the motors", this::placeMotors)
                .server("feeder: build the aisle, the station, the feeder and the Crafter", this::buildScene)
                .serverUntil("feeder: wait until the controller has every member and the stock", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("feeder: lift the player into the air", FeederVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("feeder: the player flies, wears goggles and holds the pattern items",
                        FeederVisualScenario::equipPlayer);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        reach(script, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.shotFrom(MACHINE, "scene");
        if (pass != VisualPass.FLYWHEEL)
            return;

        patternChapter(script);
        orderChapter(script);
        germanChapter(script);
        script.client("feeder: every check passed",
                context -> LOGGER.info(PREFIX + "feeder: ALL CHECKS PASSED in English and German (pattern written "
                        + "in the screen, invalid pattern red and never offered, terminal order, the whole order laid "
                        + "as one batch with the unused slots locked, result through the input, slots restored, census "
                        + "on the craft ledger)"));
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringBuilder status = new StringBuilder();
        if (level != null) {
            BlockPos dock = context.origin();
            if (level.getBlockEntity(feederPos(dock)) instanceof WarehouseCrafterFeederBlockEntity feeder) {
                CrafterFeederGoggleSummary summary = feeder.summary();
                status.append("feeder=").append(summary.status()).append('/').append(summary.runsLeft());
                BlockState state = level.getBlockState(feederPos(dock));
                if (state.hasProperty(WarehouseCrafterFeederBlock.POWERING))
                    status.append(" powering=").append(state.getValue(WarehouseCrafterFeederBlock.POWERING));
            }
            BlockState crafter = level.getBlockState(crafterPos(dock));
            if (crafter.hasProperty(CrafterBlock.CRAFTING))
                status.append(" crafting=").append(crafter.getValue(CrafterBlock.CRAFTING));
        }
        status.append(GoggleShots.describeHover(context));
        if (context.minecraft().screen instanceof WarehouseProductionScreen station)
            status.append(" screen=station pattern=").append(station.selectedPattern());
        else if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            status.append(" screen=terminal shown=").append(terminal.visibleEntries().size());
        else if (context.minecraft().screen != null)
            status.append(" screen=").append(context.minecraft().screen.getClass().getSimpleName());
        return status.toString();
    }

    // --- chapter 1: the patterns, written in the station's screen ----------------------------------------------------

    /**
     * The torch into slot 1 and "dirt makes a diamond" into slot 2, both with real clicks, then the red slot: its tab
     * tooltip and its arrow say why the Crafter Feeder skips it, and the warehouse offers torches but no diamond.
     */
    private void patternChapter(VisualScript script) {
        script.camera(AT_STATION)
                .server("feeder: open the station's screen", FeederVisualScenario::openStationScreen)
                .until("feeder: wait for the station screen with the feeder flag",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && screen.state().feeder(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
        patternEntry(script, TORCH_SLOT, HOTBAR_COAL, Items.COAL, TOP_LEFT);
        patternEntry(script, TORCH_SLOT, HOTBAR_STICK, Items.STICK, MIDDLE_LEFT);
        patternEntry(script, TORCH_SLOT, HOTBAR_TORCH, Items.TORCH, ProductionMenuLayout.RESULT_CELL);
        scrollResult(script, TORCH_SLOT, TORCHES_PER_RUN);
        script.until("feeder: the torch pattern passed the recipe check",
                        context -> productionScreen(context).state().check(TORCH_SLOT).check()
                                == CrafterPatternCheck.VALID, SCREEN_TIMEOUT_TICKS)
                .client("feeder: select the second pattern slot with a click on its tab",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.productionTab(productionScreen(context), INVALID_SLOT)))
                .until("feeder: the second slot is selected",
                        context -> productionScreen(context).selectedPattern() == INVALID_SLOT, SCREEN_TIMEOUT_TICKS);
        patternEntry(script, INVALID_SLOT, HOTBAR_DIRT, Items.DIRT, TOP_LEFT);
        patternEntry(script, INVALID_SLOT, HOTBAR_DIAMOND, Items.DIAMOND, ProductionMenuLayout.RESULT_CELL);
        script.until("feeder: the screen draws the second slot red",
                        context -> productionScreen(context).isRefused(INVALID_SLOT), SCREEN_TIMEOUT_TICKS)
                .client("feeder: rest the cursor on the red slot's tab",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionTab(productionScreen(context), INVALID_SLOT)))
                .waitTicks(SETTLE_TICKS)
                .client("feeder: check the tab's tooltip", context -> {
                    englishRefusal = checkRefusal(context, ScreenInput.productionTab(productionScreen(context),
                            INVALID_SLOT));
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the red tab says '{}'", englishRefusal);
                })
                .client("feeder: rest the cursor on the red slot's arrow",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionArrow(productionScreen(context))))
                .waitTicks(SETTLE_TICKS)
                .client("feeder: check the arrow's tooltip",
                        context -> checkRefusal(context, ScreenInput.productionArrow(productionScreen(context))))
                .shot("feeder-invalid")
                .client("feeder: close the station screen", FeederVisualScenario::closeScreen)
                .until("feeder: wait until the station screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .server("feeder: check what the warehouse makes of the two patterns", FeederVisualScenario::assertChecks)
                .server("feeder: put the pattern items away and count every item of the scene", this::baselineCensus);
        reach(script, GoggleShots.vanillaReach());
        clearToasts(script);
        GoggleShots.shot(script, "feeder", AT_STATION, "station-goggles", FeederVisualScenario::stationPos,
                FeederVisualScenario::stationSummarySynced, context -> {
                    List<String> lines = GoggleShots.lines(context, stationPos(context.origin()));
                    englishFeederLine = WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_FEEDER)
                            .getString();
                    GoggleShots.requireLine(lines, englishFeederLine);
                    GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_REFUSED, 1));
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the station's goggles name the feeder and the skipped "
                            + "pattern: {}", lines);
                });
        reach(script, 0.0);
    }

    /** Picks the item of hotbar slot {@code hotbar} up, clicks it into {@code cell} of {@code slot}, and puts it back. */
    private static void patternEntry(VisualScript script, int slot, int hotbar, Item item, int cell) {
        String what = BuiltInRegistries.ITEM.getKey(item).getPath();
        script.client("feeder: pick up the " + what,
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.hotbarSlot(productionScreen(context), hotbar)))
                .until("feeder: the " + what + " hangs on the mouse",
                        context -> productionScreen(context).getMenu().getCarried().is(item), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("feeder: click pattern cell " + cell + " with the " + what,
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.productionCell(productionScreen(context), cell)))
                .until("feeder: the server wrote the " + what + " into cell " + cell,
                        context -> productionScreen(context).state().entry(slot, cell)
                                .filter(entry -> entry.key().equals(ItemKey.of(item))).isPresent(),
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("feeder: put the " + what + " back",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.hotbarSlot(productionScreen(context), hotbar)))
                .until("feeder: the " + what + " is back in its slot",
                        context -> productionScreen(context).getMenu().getCarried().isEmpty(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS);
    }

    /** Mouse-wheel notches over the result cell until it reads {@code count}, one notch per answer of the server. */
    private static void scrollResult(VisualScript script, int slot, int count) {
        int[] scrolledAt = {-1};
        script.until("feeder: scroll the result up to " + count, context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            int shown = screen.state().entry(slot, ProductionMenuLayout.RESULT_CELL)
                    .map(ProductionScreenState.EntryView::count).orElse(0);
            if (shown == count)
                return true;
            if (shown != scrolledAt[0]) {
                scrolledAt[0] = shown;
                ScreenInput.scroll(context.minecraft(),
                        ScreenInput.productionCell(screen, ProductionMenuLayout.RESULT_CELL), shown < count ? 1.0 : -1.0);
            }
            return false;
        }, SCREEN_TIMEOUT_TICKS);
    }

    /**
     * The tooltip at {@code point} names why the slot is red ("Not a crafting recipe") and that the feeder skips it;
     * returns the reason as the screen says it.
     */
    private static String checkRefusal(VisualContext context, ScreenInput.Point point) {
        WarehouseProductionScreen screen = productionScreen(context);
        ProductionScreenState.PatternCheckView check = screen.state().check(INVALID_SLOT);
        if (check.check() != CrafterPatternCheck.NOT_A_RECIPE)
            throw new VisualTestException("the dirt-to-diamond slot was checked as " + check.check());
        String reason = WarehouseProductionScreen.refusalText(check).getString();
        String skipped = WareworksLang.translateDirect(WareworksLang.PRODUCTION_CHECK_SKIPPED).getString();
        List<String> tooltip = ScreenInput.productionTooltip(screen, point).stream().map(Component::getString)
                .toList();
        if (!tooltip.contains(reason) || !tooltip.contains(skipped))
            throw new VisualTestException("the tooltip at " + point + " does not say '" + reason + "' and '"
                    + skipped + "': " + tooltip);
        return reason;
    }

    private static void assertChecks(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        WarehouseProductionBlockEntity station = station(server.overworld(), dock);
        if (station.crafterCheck(TORCH_SLOT).check() != CrafterPatternCheck.VALID
                || station.crafterCheck(INVALID_SLOT).check() != CrafterPatternCheck.NOT_A_RECIPE)
            throw new VisualTestException("the checks are " + station.crafterChecks());
        if (station.activePatterns().size() != 1)
            throw new VisualTestException("the warehouse plans with " + station.activePatterns().size()
                    + " patterns, expected only the torch");
        WarehouseControllerBlockEntity controller = controller(server.overworld(), dock);
        if (!controller.producibleKeys().contains(TORCH) || controller.producibleKeys().contains(DIAMOND))
            throw new VisualTestException("the aisle offers " + controller.producibleKeys());
        LOGGER.info(PREFIX + "feeder: CHECK PASS the torch is planned, the diamond pattern is refused and never "
                + "offered");
    }

    // --- chapter 2: the order, the batch, the pulse, the result -----------------------------------------------------

    private void orderChapter(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("feeder: open the terminal screen", FeederVisualScenario::openTerminalScreen)
                .until("feeder: wait until the terminal offers torches",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && terminal.entry(TORCH).isPresent(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("feeder: scroll the requested amount up to " + ORDERED, FeederVisualScenario::scrollAmount)
                .waitTicks(SETTLE_TICKS)
                .client("feeder: click the torch cell", FeederVisualScenario::clickTorch)
                .serverUntil("feeder: wait until the order is in", FeederVisualScenario::ordered, SCREEN_TIMEOUT_TICKS)
                .client("feeder: close the terminal screen", FeederVisualScenario::closeScreen)
                .until("feeder: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("feeder: wait until the feeder laid the batch", this::laid, LAID_TIMEOUT_TICKS);
        reach(script, GoggleShots.vanillaReach());
        clearToasts(script);
        GoggleShots.shot(script, "feeder", AT_FEEDER, "feeder-laid", FeederVisualScenario::feederPos,
                context -> clientFeeder(context).status() == FeederStatus.FEEDING
                        && clientFeeder(context).runsLeft() > 0,
                FeederVisualScenario::checkFeedingGoggles);
        reach(script, 0.0);
        // The Crafter is lit for a few ticks per craft: the ticks are frozen on the server in the tick it crafts, so the
        // shot cannot miss it.
        script.serverUntil("feeder: freeze the ticks the moment the Crafter crafts", this::freezeOnCraft,
                        PULSE_TIMEOUT_TICKS)
                .until("feeder: the client sees the frozen craft", context -> {
                    ClientLevel level = context.minecraft().level;
                    return level != null && level.tickRateManager().isFrozen()
                            && level.getBlockState(crafterPos(context.origin())).getValue(CrafterBlock.CRAFTING);
                }, SCREEN_TIMEOUT_TICKS)
                .camera(AT_CRAFTER)
                .shot("feeder-pulse")
                .freeze(false)
                .serverUntil("feeder: wait until the order is complete and the torches are in the terminal",
                        this::done, DONE_TIMEOUT_TICKS)
                .server("feeder: check the end of the run", this::assertDone);
        reach(script, GoggleShots.vanillaReach());
        clearToasts(script);
        GoggleShots.shot(script, "feeder", AT_FEEDER, "feeder-done", FeederVisualScenario::feederPos,
                context -> clientFeeder(context).status() == FeederStatus.IDLE, context -> {
                    List<String> lines = GoggleShots.lines(context, feederPos(context.origin()));
                    englishIdle = WareworksLang.translateDirect(FeederStatus.IDLE.langKey()).getString();
                    GoggleShots.requireLine(lines, englishIdle);
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the idle feeder's goggles: {}", lines);
                });
        reach(script, 0.0);
    }

    private static void scrollAmount(VisualContext context) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        ScrollInput amount = ScreenInput.terminalAmount(terminal);
        ScreenInput.Point point = ScreenInput.centre(amount);
        for (int notch = 0; notch < MAX_SCROLL_NOTCHES && amount.getState() != ORDERED; notch++)
            ScreenInput.scroll(context.minecraft(), point, amount.getState() < ORDERED ? 1.0 : -1.0);
        if (amount.getState() != ORDERED)
            throw new VisualTestException("the requested amount stayed at " + amount.getState());
    }

    private static void clickTorch(VisualContext context) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (visible.get(cell).key().equals(TORCH)) {
                ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
                return;
            }
        }
        throw new VisualTestException("the terminal shows no torch cell");
    }

    private static boolean ordered(MinecraftServer server, VisualContext context) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller(server.overworld(), context.origin())
                .productionOrders();
        if (orders.isEmpty())
            return false;
        ProductionOrder<ItemKey, RackPosition> order = orders.getFirst();
        if (orders.size() != 1 || !order.result().equals(TORCH) || order.resultAmount() != ORDERED)
            throw new VisualTestException("the click created " + orders);
        LOGGER.info(PREFIX + "feeder: CHECK PASS one production order of {} torches from the terminal click", ORDERED);
        return true;
    }

    /**
     * The grid holds the whole order as one batch: both slots hold {@value #RUNS} items (one fewer only once a pulse was
     * seen, since the poll runs every tick and the signal is high for two of them), nothing is left to lay, and only
     * slots 0 and 3 are open.
     */
    private boolean laid(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ledger(level);
        BlockPos dock = context.origin();
        if (level.getBlockState(feederPos(dock)).getValue(WarehouseCrafterFeederBlock.POWERING))
            edgeSeen = true;
        CrafterBlockEntity crafter = crafter(level, dock);
        int coal = crafter.getItem(TOP_LEFT).getCount();
        int sticks = crafter.getItem(MIDDLE_LEFT).getCount();
        if (!crafter.getItem(TOP_LEFT).is(Items.COAL) || !crafter.getItem(MIDDLE_LEFT).is(Items.STICK))
            return false;
        if (coal != sticks || !(coal == RUNS || (coal == RUNS - 1 && edgeSeen)))
            return false;
        if (feeder(level, dock).runsToLay() != 0)
            throw new VisualTestException("the feeder still has " + feeder(level, dock).runsToLay()
                    + " runs to lay after laying " + coal);
        for (int slot = 0; slot < 9; slot++) {
            boolean used = slot == TOP_LEFT || slot == MIDDLE_LEFT;
            if (crafter.isSlotDisabled(slot) == used)
                throw new VisualTestException("Crafter slot " + slot + " is " + (used ? "locked" : "open")
                        + " while the torch is laid");
        }
        LOGGER.info(PREFIX + "feeder: CHECK PASS the batch lies in slots 0 and 3 ({} coal, {} sticks), the other "
                + "seven slots are locked", crafter.getItem(TOP_LEFT).getCount(), crafter.getItem(MIDDLE_LEFT).getCount());
        return true;
    }

    private static void checkFeedingGoggles(VisualContext context) {
        CrafterFeederGoggleSummary summary = clientFeeder(context);
        List<String> lines = GoggleShots.lines(context, feederPos(context.origin()));
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_CRAFTER_FEEDER).getString());
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.CRAFTER_FEEDER_FEEDING,
                new ItemStack(Items.TORCH).getHoverName(), LangNumberFormat.format(summary.runsLeft())).getString());
        LOGGER.info(PREFIX + "feeder: CHECK PASS the feeding goggles: {}", lines);
    }

    /** Server condition: freezes the ticks in the tick the Crafter is crafting. */
    private boolean freezeOnCraft(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ledger(level);
        if (!level.getBlockState(crafterPos(context.origin())).getValue(CrafterBlock.CRAFTING))
            return false;
        server.tickRateManager().setFrozen(true);
        LOGGER.info(PREFIX + "feeder: CHECK PASS caught the Crafter crafting; ticks frozen");
        return true;
    }

    private boolean done(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        int crafted = ledger(level);
        BlockPos dock = context.origin();
        Optional<ProductionOrder<ItemKey, RackPosition>> order = controller(level, dock).productionOrders().stream()
                .findFirst();
        return crafted == RUNS && order.isPresent() && order.get().state() == ProductionOrderState.COMPLETE
                && terminal(level, dock).bufferedItems().count(TORCH) == ORDERED
                && feeder(level, dock).status() == FeederStatus.IDLE;
    }

    private void assertDone(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CrafterBlockEntity crafter = crafter(level, dock);
        for (int slot = 0; slot < 9; slot++) {
            if (crafter.isSlotDisabled(slot) || !crafter.getItem(slot).isEmpty())
                throw new VisualTestException("the Crafter's slot " + slot + " was not given back empty");
        }
        if (ledger(level) != RUNS)
            throw new VisualTestException("the census is not " + RUNS + " crafts away from the baseline");
        LOGGER.info(PREFIX + "feeder: CHECK PASS {} torches in the terminal, {} crafts on the ledger, the Crafter has "
                + "all nine slots back", ORDERED, RUNS);
    }

    /**
     * The census is the baseline plus a whole number of torch crafts; returns that number. Anything else — an item
     * lost or copied between the buffer, the grid and the input, a result dropped — fails the run.
     */
    private int ledger(ServerLevel level) {
        Map<ItemKey, Long> actual = SceneItemCensus.take(level, censusBox);
        for (int crafted = 0; crafted <= RUNS; crafted++) {
            Map<ItemKey, Long> expected = baseline;
            for (Map.Entry<ItemKey, Long> change : TORCH_RUN.entrySet())
                expected = SceneItemCensus.plus(expected, change.getKey(), change.getValue() * crafted);
            if (expected.equals(actual))
                return crafted;
        }
        throw new VisualTestException("item conservation violated: " + SceneItemCensus.describe(actual)
                + " is no whole number of torch crafts away from " + SceneItemCensus.describe(baseline));
    }

    // --- chapter 3: German -------------------------------------------------------------------------------------------

    private void germanChapter(VisualScript script) {
        language.switchTo(script, "feeder: ", "de_de");
        script.camera(AT_STATION)
                .server("feeder: open the station's screen in German", FeederVisualScenario::openStationScreen)
                .until("feeder: wait for the station screen with the red slot",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && screen.isRefused(INVALID_SLOT), SCREEN_TIMEOUT_TICKS)
                .client("feeder: select the red slot",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.productionTab(productionScreen(context), INVALID_SLOT)))
                .until("feeder: the red slot is selected",
                        context -> productionScreen(context).selectedPattern() == INVALID_SLOT, SCREEN_TIMEOUT_TICKS)
                .client("feeder: rest the cursor on the red slot's tab",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionTab(productionScreen(context), INVALID_SLOT)))
                .waitTicks(SETTLE_TICKS)
                .client("feeder: check the German tooltip", context -> {
                    String reason = checkRefusal(context, ScreenInput.productionTab(productionScreen(context),
                            INVALID_SLOT));
                    requireTranslated(reason, englishRefusal);
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the red tab says '{}' in German", reason);
                })
                .shot("feeder-invalid-de")
                .client("feeder: close the station screen", FeederVisualScenario::closeScreen)
                .until("feeder: wait until the station screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
        reach(script, GoggleShots.vanillaReach());
        clearToasts(script);
        GoggleShots.shot(script, "feeder", AT_STATION, "station-goggles-de", FeederVisualScenario::stationPos,
                FeederVisualScenario::stationSummarySynced, context -> {
                    List<String> lines = GoggleShots.lines(context, stationPos(context.origin()));
                    String line = WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_FEEDER).getString();
                    requireTranslated(line, englishFeederLine);
                    GoggleShots.requireLine(lines, line);
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the station's goggles in German: {}", lines);
                });
        clearToasts(script);
        GoggleShots.shot(script, "feeder", AT_FEEDER, "feeder-done-de", FeederVisualScenario::feederPos,
                context -> clientFeeder(context).status() == FeederStatus.IDLE, context -> {
                    List<String> lines = GoggleShots.lines(context, feederPos(context.origin()));
                    String idle = WareworksLang.translateDirect(FeederStatus.IDLE.langKey()).getString();
                    requireTranslated(idle, englishIdle);
                    GoggleShots.requireLine(lines, idle);
                    LOGGER.info(PREFIX + "feeder: CHECK PASS the idle feeder's goggles in German: {}", lines);
                });
        reach(script, 0.0);
        language.switchTo(script, "feeder: ", "en_us");
    }

    /** A German text must exist and differ from the English one: a missing key would fall back to English. */
    private static void requireTranslated(String german, String english) {
        if (german.isBlank() || german.equals(english))
            throw new VisualTestException("'" + german + "' is no German translation of '" + english + "'");
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

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
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN).above(CLEAR_HEIGHT)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        // The feeder's shaft runs vertically (axis_along_first with a horizontal facing along x), so its motor sits on
        // top of it and turns downwards.
        level.setBlockAndUpdate(feederPos(dock).above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.DOWN));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        motor(level, feederPos(dock).above()).generatedSpeed.setValue(FEEDER_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward(layout, STATION)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        for (RackPosition rack : STORAGE) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(layout.rackPos(rack).relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        Direction left = layout.sideDirection(Side.LEFT);
        insertInto(level, layout.rackPos(STORAGE.get(0)).relative(left), COAL.toStack(IN_STOCK));
        insertInto(level, layout.rackPos(STORAGE.get(1)).relative(left), STICK.toStack(IN_STOCK));

        // The feeder on the station's back, facing along the rack wall; the Crafter in front of it, behind the input,
        // its output facing the input (ADR-067 D2).
        level.setBlockAndUpdate(feederPos(dock), WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, AISLE)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        level.setBlockAndUpdate(crafterPos(dock), Blocks.CRAFTER.defaultBlockState().setValue(
                BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(inward(layout, INPUT), Direction.UP)));
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseCrafterFeederBlockEntity feeder = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                .getNullable(level, feederPos(dock));
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                stationPos(dock));
        if (controller == null || crane == null || feeder == null || station == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && controller.inputStations().size() == 1 && controller.outputStations().size() == 1
                && controller.productionStations().size() == 1 && crane.isControllerLinked()
                && controller.countOf(COAL) == IN_STOCK && controller.countOf(STICK) == IN_STOCK
                && feeder.running() && station.attachedFeeder().isPresent();
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /** Flying, goggles on, and the five items the patterns are written with in the hotbar (they are ghosts there). */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(HOTBAR_COAL, new ItemStack(Items.COAL));
        player.getInventory().setItem(HOTBAR_STICK, new ItemStack(Items.STICK));
        player.getInventory().setItem(HOTBAR_TORCH, new ItemStack(Items.TORCH));
        player.getInventory().setItem(HOTBAR_DIRT, new ItemStack(Items.DIRT));
        player.getInventory().setItem(HOTBAR_DIAMOND, new ItemStack(Items.DIAMOND));
        player.getInventory().selected = 8; // an empty hand: nothing covers a corner of a shot with the GUI shown
        player.inventoryMenu.broadcastChanges();
    }

    /** The pattern items go, the goggles stay; then the first census of the run. */
    private void baselineCensus(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        for (int slot : new int[] {HOTBAR_COAL, HOTBAR_STICK, HOTBAR_TORCH, HOTBAR_DIRT, HOTBAR_DIAMOND})
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        baseline = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "feeder: baseline census {}", SceneItemCensus.describe(baseline));
    }

    // --- screens ---------------------------------------------------------------------------------------------------

    private static void openStationScreen(MinecraftServer server, VisualContext context) {
        if (!station(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the production station screen could not be opened");
    }

    private static void openTerminalScreen(MinecraftServer server, VisualContext context) {
        if (!terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened");
    }

    private static WarehouseProductionScreen productionScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open (screen: " + context.minecraft().screen + ")");
    }

    private static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen screen)
            return screen;
        throw new VisualTestException("the terminal screen is not open (screen: " + context.minecraft().screen + ")");
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    /**
     * The recipe and advancement toasts the pattern items set off sit in the corner of a shot with the GUI shown, so a
     * goggle shot clears them first.
     */
    private static void clearToasts(VisualScript script) {
        script.client("feeder: clear the toasts", context -> context.minecraft().getToasts().clear());
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "feeder", range);
    }

    // --- client state ------------------------------------------------------------------------------------------------

    private static CrafterFeederGoggleSummary clientFeeder(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level != null && level.getBlockEntity(feederPos(context.origin()))
                instanceof WarehouseCrafterFeederBlockEntity feeder)
            return feeder.summary();
        return CrafterFeederGoggleSummary.NONE;
    }

    private static boolean stationSummarySynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(stationPos(context.origin()))
                instanceof WarehouseProductionBlockEntity station))
            return false;
        ProductionGoggleSummary summary = station.productionSummary();
        return summary.feeder() && summary.refusedPatterns() == 1;
    }

    // --- positions ---------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    private static BlockPos stationPos(BlockPos dock) {
        return layout(dock).rackPos(STATION);
    }

    private static BlockPos terminalPos(BlockPos dock) {
        return layout(dock).rackPos(TERMINAL);
    }

    /** On the station's back, one step outside the rack wall. */
    private static BlockPos feederPos(BlockPos dock) {
        return stationPos(dock).relative(layout(dock).sideDirection(STATION.side()));
    }

    /** In front of the feeder, behind the input. */
    private static BlockPos crafterPos(BlockPos dock) {
        return feederPos(dock).relative(AISLE);
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(2),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN).above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories ------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos dock) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                stationPos(dock));
        if (station == null)
            throw new VisualTestException("the production station is missing");
        return station;
    }

    private static WarehouseCrafterFeederBlockEntity feeder(ServerLevel level, BlockPos dock) {
        WarehouseCrafterFeederBlockEntity feeder = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                .getNullable(level, feederPos(dock));
        if (feeder == null)
            throw new VisualTestException("the crafter feeder is missing");
        return feeder;
    }

    private static CrafterBlockEntity crafter(ServerLevel level, BlockPos dock) {
        if (level.getBlockEntity(crafterPos(dock)) instanceof CrafterBlockEntity crafter)
            return crafter;
        throw new VisualTestException("the Crafter is missing");
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                terminalPos(dock));
        if (terminal == null)
            throw new VisualTestException("the terminal is missing");
        return terminal;
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
