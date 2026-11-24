package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.RecipeSelectorBlockEntity;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.station.ProductionGoggleSummary;
import dev.wareworks.content.station.ProductionMenuLayout;
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
import dev.wareworks.core.production.FluidCell;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "fluidpattern" (issue #55, ADR-089): a production pattern with a <b>fluid cell</b>, run through the real
 * player layout of {@code docs/warehouse-system.md} §12 with no Mechanical Arm anywhere: the crane carries a filled
 * bucket to a production station; an extracting brass funnel with the bucket as its filter hands it to an Item Drain,
 * a pump and pipes carry the water into a Basin under a Mechanical Mixer, a second filtered funnel hands the dirt to the
 * Basin, a third takes the mud to a Warehouse Input, and the empty bucket leaves through a funnel into a chest. The
 * recipe is Create's own {@code mud_by_mixing}: one dirt and 250 mB of water make one mud.
 * <p>
 * Every step is a real one: the pattern is written with real clicks through {@link ScreenInput} (dirt and mud as items,
 * a held water bucket as the fluid cell, the wheel from 1000 down to 250 mB), the order is a click on the terminal's
 * stock cell, the funnels' filters are set on their behaviours, and the second round places a Recipe Selector on the
 * Basin with sneak-clicks. The census is <b>exact on every server tick</b> ({@link ServerTickWatch}): the item census
 * (water buckets in flight, empty buckets short by the same number, dirt turned into mud) and the fluid census (water
 * leaves only by the mixer, 250 mB per mud) are matched against one ledger, and at most two filled buckets are ever on
 * their way. Mid order the world is saved, quit and joined again.
 * <p>
 * Grep {@code fluidpattern: ALL CHECKS PASSED in English and German}.
 */
public final class FluidPatternVisualScenario implements VisualScenario {
    public static final String NAME = "fluidpattern";

    private static final String WORLD_FOLDER = "wareworks_visual_fluidpattern";

    private static final Direction AISLE = Direction.NORTH;
    private static final int RAILS = 6;
    private static final int CRANE_RPM = 128;
    private static final int PUMP_RPM = 128;
    private static final int MIXER_RPM = 256;

    private static final RackPosition BAY = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition STATION = RackPosition.of(5, 2, Side.RIGHT);
    private static final RackPosition INPUT = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition EMPTIES = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition DIRT_STORE = RackPosition.of(2, 0, Side.LEFT);
    private static final RackPosition TERMINAL = RackPosition.of(3, 0, Side.LEFT);

    // The machine, relative to the dock block. The wall of the right racks is x = 1, rack position n at z = -n, level
    // l at y = l. The station stands at level 2 over a pillar so that everything under it fits: the bucket funnel and
    // the dirt funnel drop what they take as item entities, which fall onto the drain and into the Basin below them.
    private static final BlockPos STATION_REL = new BlockPos(1, 2, -5);
    private static final BlockPos PILLAR_LOW = new BlockPos(1, 0, -5);
    private static final BlockPos DIRT_FUNNEL = new BlockPos(1, 2, -4);
    private static final BlockPos BASIN = new BlockPos(1, 1, -4);
    private static final BlockPos INPUT_REL = new BlockPos(1, 0, -3);
    private static final BlockPos BUCKET_FUNNEL = new BlockPos(2, 2, -5);
    private static final BlockPos DRAIN = new BlockPos(2, 1, -5);
    /**
     * The Item Drain pushes the empty bucket out of the side its item last moved towards, which a bucket dropped by a
     * funnel does not decide (it came out east in this scenario and north in the spike): a funnel over a chest stands on
     * both free sides, and the pump on the third.
     */
    private static final BlockPos CATCH_NORTH = new BlockPos(2, 1, -6);
    private static final BlockPos CHEST_NORTH = new BlockPos(2, 0, -6);
    private static final BlockPos CATCH_EAST = new BlockPos(3, 1, -5);
    private static final BlockPos CHEST_EAST = new BlockPos(3, 0, -5);
    private static final BlockPos PUMP = new BlockPos(2, 1, -4);
    private static final BlockPos PUMP_COG = new BlockPos(3, 1, -4);
    private static final BlockPos PUMP_MOTOR = new BlockPos(3, 1, -3);
    /**
     * Out of the pump, down and round under the Basin and up into it: every pipe with exactly the two connections of this
     * path (see {@link #placePipes}), so no pipe has an open end that pours the water away.
     */
    private static final List<BlockPos> PIPES = List.of(new BlockPos(2, 1, -3), new BlockPos(2, 0, -3),
            new BlockPos(2, 0, -4), new BlockPos(1, 0, -4));
    private static final BlockPos MIXER = new BlockPos(1, 3, -4);
    private static final BlockPos MIXER_COG = new BlockPos(2, 3, -4);
    private static final BlockPos MIXER_MOTOR = new BlockPos(2, 2, -4);
    /**
     * Beside the Basin, but not on its spout side: Create turns the spout away from a side whose block has a collision
     * shape ({@code BasinBlock#canOutputTo}), and the mud would stay in the Basin. The gap under the station is free.
     */
    private static final BlockPos SELECTOR_REL = new BlockPos(1, 1, -5);

    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey MUD = ItemKey.of(Items.MUD);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final String WATER_ID = "minecraft:water";
    private static final int RUN_MB = 250;
    private static final int BUCKET_MB = 1000;
    private static final int EMPTY_BUCKETS = 16;
    private static final int DIRT_STOCK = 64;
    private static final int BAY_BUCKETS = 8;
    private static final int FIRST_ORDER = 8;
    private static final int SECOND_ORDER = 4;
    private static final int MAX_IN_FLIGHT = 2;

    private static final int HOTBAR_DIRT = 0;
    private static final int HOTBAR_MUD = 1;
    private static final int HOTBAR_WATER = 2;
    private static final int SLOT_SELECTOR = 3;
    private static final int PATTERN_SLOT = 0;
    private static final int MAX_SCROLL_NOTCHES = 40;
    private static final double RAIL_TOP = WarehouseRailBlock.HEIGHT_PIXELS / 16.0;

    private static final int CLEAR_MARGIN = 6;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 1200;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int ORDER_TIMEOUT_TICKS = 9000;
    private static final int MACHINE_TIMEOUT_TICKS = 6000;
    private static final int LANGUAGE_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 6;
    private static final int CLICK_GAP_TICKS = 3;
    private static final long RUN_TIMEOUT_MILLIS = 16L * 60L * 1000L;

    private static final CameraView OVERVIEW = CameraView.of("overview", 9.5, 5.5, 0.5, 2.0, 1.5, -4.0);
    private static final CameraView MACHINE_VIEW = CameraView.of("machine", 7.0, 4.0, -1.5, 2.2, 1.5, -4.5);
    private static final CameraView BASIN_VIEW = CameraView.of("basin", 5.5, 3.6, -7.8, 1.6, 1.6, -4.5);
    private static final CameraView DRAIN_VIEW = CameraView.of("drain", 5.5, 2.8, -2.2, 2.5, 1.0, -5.0);
    private static final CameraView AT_STATION = CameraView.of("station", -0.8, 3.6, -4.5, 1.2, 2.5, -4.5);
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 0.5, 2.3, -1.6, -0.5, 0.6, -2.5);

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> baseline = Map.of();
    private volatile Map<FluidKey, Long> fluidBaseline = Map.of();
    @Nullable
    private volatile ServerTickWatch watch;
    private volatile boolean ledgerOn;
    private volatile int maxCrafts = FIRST_ORDER;
    /** The ledger as the last tick matched it: muds crafted and filled buckets on their way. */
    private volatile int crafted;
    private volatile int inFlight;
    private volatile int mostInFlight;
    private volatile boolean sawBucketInStation;
    private volatile boolean sawBucketInDrain;
    private volatile boolean sawWaterInBasin;
    private volatile String englishFluidAmount = "";
    private volatile String englishGoggleLine = "";

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
        script.client("fluidpattern: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "fluidpattern run"))
                .server("fluidpattern: clear the area and place the motors", this::placeMotors)
                .server("fluidpattern: build the aisle, the station and the machine", this::buildScene)
                .serverUntil("fluidpattern: wait until the controller has every member and the stock",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("fluidpattern: the player flies, wears goggles and holds the pattern items",
                        FluidPatternVisualScenario::equipPlayer);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        GoggleShots.reach(script, NAME, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.shotFrom(MACHINE_VIEW, "scene");
        if (pass != VisualPass.FLYWHEEL)
            return;
        patternChapter(script);
        firstOrderChapter(script);
        selectorChapter(script);
        germanChapter(script);
        script.server("fluidpattern: stop the per-tick ledger", (server, context) -> {
            ServerTickWatch running = watch;
            if (running != null) {
                running.check();
                running.stop();
            }
            watch = null;
        }).client("fluidpattern: every check passed",
                context -> LOGGER.info(PREFIX + "fluidpattern: ALL CHECKS PASSED in English and German (pattern with a "
                        + "fluid cell written with real clicks, order from the terminal, the crane's bucket through "
                        + "funnel, drain, pump and pipes into the Basin, mud through the input, exact item and fluid "
                        + "ledger on every tick, a save and rejoin mid order, the Recipe Selector on the Basin)"));
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder("crafted=" + crafted + " inFlight=" + inFlight);
        status.append(GoggleShots.describeHover(context));
        if (context.minecraft().screen instanceof WarehouseProductionScreen station)
            status.append(" screen=station pattern=").append(station.selectedPattern());
        else if (context.minecraft().screen instanceof WarehouseTerminalScreen)
            status.append(" screen=terminal");
        return status.toString();
    }

    // --- chapter 1: the pattern, written in the station's screen ----------------------------------------------------

    private void patternChapter(VisualScript script) {
        script.camera(AT_STATION)
                .server("fluidpattern: open the station's screen", FluidPatternVisualScenario::openStationScreen)
                .until("fluidpattern: wait for the station screen",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
        FeederVisualScenario.patternEntry(script, PATTERN_SLOT, HOTBAR_DIRT, Items.DIRT, 0);
        FeederVisualScenario.patternEntry(script, PATTERN_SLOT, HOTBAR_MUD, Items.MUD, ProductionMenuLayout.RESULT_CELL);
        fluidEntry(script);
        scrollFluid(script, RUN_MB);
        script.client("fluidpattern: rest the cursor on the fluid cell",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionFluidCell(productionScreen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: check the fluid cell and its tooltip", context -> {
                    WarehouseProductionScreen screen = productionScreen(context);
                    FluidCell cell = screen.state().fluid(PATTERN_SLOT, 0).orElseThrow(
                            () -> new VisualTestException("the pattern has no fluid cell"));
                    if (!cell.fluid().equals(WATER_ID) || cell.millibuckets() != RUN_MB)
                        throw new VisualTestException("the fluid cell reads " + cell);
                    List<String> tooltip = ScreenInput.productionTooltip(screen,
                            ScreenInput.productionFluidCell(screen, 0)).stream().map(Component::getString).toList();
                    englishFluidAmount = WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_AMOUNT,
                            "250").getString();
                    if (!tooltip.contains(englishFluidAmount))
                        throw new VisualTestException("the fluid cell's tooltip " + tooltip + " lacks '"
                                + englishFluidAmount + "'");
                    LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the fluid cell reads {} and its tooltip {}", cell,
                            tooltip);
                })
                .shot("fluidpattern-pattern")
                .client("fluidpattern: close the station screen", FluidPatternVisualScenario::closeScreen)
                .until("fluidpattern: wait until the station screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .server("fluidpattern: check what the warehouse makes of the pattern", this::assertPattern)
                .server("fluidpattern: put the pattern items away, take the baseline and start the per-tick ledger",
                        this::startLedger);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.client("fluidpattern: clear the toasts", context -> context.minecraft().getToasts().clear());
        GoggleShots.shot(script, NAME, AT_STATION, "station-goggles", pos -> stationPos(pos),
                FluidPatternVisualScenario::stationSummarySynced, context -> {
                    List<String> lines = GoggleShots.lines(context, stationPos(context.origin()));
                    englishGoggleLine = GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_FLUID_PATTERNS, 1);
                    GoggleShots.requireLine(lines, englishGoggleLine);
                    LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the station's goggles count the fluid pattern: {}",
                            lines);
                });
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** Picks the water bucket up, clicks it into fluid cell 0 and puts it back: the cell is a ghost, nothing is taken. */
    private static void fluidEntry(VisualScript script) {
        script.client("fluidpattern: pick up the water bucket",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.hotbarSlot(productionScreen(context), HOTBAR_WATER)))
                .until("fluidpattern: the water bucket hangs on the mouse",
                        context -> productionScreen(context).getMenu().getCarried().is(Items.WATER_BUCKET),
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("fluidpattern: click fluid cell 0 with the bucket",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.productionFluidCell(productionScreen(context), 0)))
                .until("fluidpattern: the server wrote water into fluid cell 0",
                        context -> productionScreen(context).state().fluid(PATTERN_SLOT, 0)
                                .filter(cell -> cell.fluid().equals(WATER_ID)).isPresent(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("fluidpattern: put the bucket back",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.hotbarSlot(productionScreen(context), HOTBAR_WATER)))
                .until("fluidpattern: the bucket is back in its slot",
                        context -> productionScreen(context).getMenu().getCarried().isEmpty(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS);
    }

    /** Mouse-wheel notches over fluid cell 0 until it reads {@code target} mB, one notch per answer of the server. */
    private static void scrollFluid(VisualScript script, int target) {
        int[] scrolledAt = {-1};
        script.until("fluidpattern: scroll the fluid cell to " + target + " mB", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            int shown = screen.state().fluid(PATTERN_SLOT, 0).map(FluidCell::millibuckets).orElse(0);
            if (shown == target)
                return true;
            if (shown != scrolledAt[0]) {
                scrolledAt[0] = shown;
                ScreenInput.scroll(context.minecraft(), ScreenInput.productionFluidCell(screen, 0),
                        shown < target ? 1.0 : -1.0);
            }
            return false;
        }, MAX_SCROLL_NOTCHES * 20);
    }

    private void assertPattern(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseProductionBlockEntity station = station(level, context.origin());
        if (station.activePatterns().size() != 1)
            throw new VisualTestException("the warehouse plans with " + station.activePatterns().size() + " patterns");
        if (!station.activePatterns().getFirst().fluids().equals(List.of(new FluidCell(WATER_ID, RUN_MB))))
            throw new VisualTestException("the station's pattern holds " + station.activePatterns().getFirst());
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        if (!controller.producibleKeys().contains(MUD))
            throw new VisualTestException("the aisle offers " + controller.producibleKeys() + ", not the mud");
        LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the station's pattern is dirt + {} mB water -> mud and the "
                + "terminal offers the mud", RUN_MB);
    }

    private void startLedger(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        for (int slot : new int[] {HOTBAR_DIRT, HOTBAR_MUD, HOTBAR_WATER})
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        resetBaseline(server.overworld(), FIRST_ORDER);
        watch = ServerTickWatch.start(NAME, this::ledgerTick);
        ledgerOn = true;
    }

    // --- the ledger -------------------------------------------------------------------------------------------------

    private void resetBaseline(ServerLevel level, int crafts) {
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        baseline = SceneItemCensus.take(level, censusBox);
        fluidBaseline = SceneFluidCensus.take(level, censusBox);
        maxCrafts = crafts;
        crafted = 0;
        inFlight = 0;
        mostInFlight = 0;
        sawBucketInStation = false;
        sawBucketInDrain = false;
        sawWaterInBasin = false;
        LOGGER.info(PREFIX + "fluidpattern: baseline census {} and {} mB", SceneItemCensus.describe(baseline),
                SceneFluidCensus.describe(fluidBaseline));
    }

    /**
     * Every server tick: the census is the baseline plus a whole number of crafts (a dirt becomes a mud, 250 mB of water
     * are gone) plus a number of filled buckets on their way (a filled bucket in place of an empty one), never anything
     * else, and at most {@value #MAX_IN_FLIGHT} filled buckets are away from the warehouse's own store of empties.
     */
    private void ledgerTick(MinecraftServer server) {
        if (!ledgerOn)
            return;
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            return;
        Map<ItemKey, Long> items = SceneItemCensus.plusAll(SceneItemCensus.take(level, censusBox),
                basinOverflow(level));
        Map<FluidKey, Long> fluid = SceneFluidCensus.take(level, censusBox, items);
        for (int made = 0; made <= maxCrafts; made++) {
            for (int away = 0; away <= MAX_IN_FLIGHT + 1; away++) {
                Map<ItemKey, Long> expected = SceneItemCensus.plus(baseline, DIRT, -made);
                expected = SceneItemCensus.plus(expected, MUD, made);
                expected = SceneItemCensus.plus(expected, WATER_BUCKET, away);
                expected = SceneItemCensus.plus(expected, BUCKET, -away);
                if (expected.equals(items) && SceneFluidCensus.plus(fluidBaseline, WATER, -(long) RUN_MB * made)
                        .equals(fluid)) {
                    if (made != crafted || away != inFlight)
                        LOGGER.info(PREFIX + "fluidpattern: ledger {} mud made, {} filled bucket(s) away (drain {} mB, "
                                + "Basin {} mB, bay {} mB)", made, away, fluidOf(level, origin.offset(DRAIN), null),
                                water(level, origin.offset(BASIN)), bay(level, origin).millibuckets());
                    crafted = made;
                    inFlight = away;
                    mostInFlight = Math.max(mostInFlight, away);
                    if (away > MAX_IN_FLIGHT)
                        throw new VisualTestException(away + " filled buckets are away from the store, the cap is "
                                + MAX_IN_FLIGHT);
                    observe(level);
                    return;
                }
            }
        }
        StringBuilder loose = new StringBuilder();
        for (net.minecraft.world.entity.item.ItemEntity entity : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, censusBox))
            loose.append(' ').append(entity.getItem()).append('@').append(entity.blockPosition().subtract(origin));
        loose.append(" | water: drain ").append(fluidOf(level, origin.offset(DRAIN), null)).append(", basin ")
                .append(water(level, origin.offset(BASIN))).append(", bay ")
                .append(bay(level, origin).millibuckets());
        throw new VisualTestException("conservation violated (item entities:" + loose + "): items " + SceneItemCensus.describe(items) + " and fluid "
                + SceneFluidCensus.describe(fluid) + " are no whole number of crafts away from "
                + SceneItemCensus.describe(baseline) + " / " + SceneFluidCensus.describe(fluidBaseline));
    }

    /**
     * What the Basin has made and not yet poured through its spout into the Input: Create keeps it in a buffer that no
     * item capability shows ({@code BasinBlockEntity#spoutputBuffer}, saved as {@code Overflow}). The {@code selector}
     * scenario adds the same buffer to its census by hand, so the shared census stays as it is.
     */
    private List<ItemStack> basinOverflow(ServerLevel level) {
        BlockPos dock = origin;
        if (dock == null || !(level.getBlockEntity(dock.offset(BASIN)) instanceof BasinBlockEntity basin))
            return List.of();
        List<ItemStack> stacks = new java.util.ArrayList<>();
        for (net.minecraft.nbt.Tag tag : basin.saveWithoutMetadata(level.registryAccess()).getList("Overflow",
                net.minecraft.nbt.Tag.TAG_COMPOUND))
            stacks.add(ItemStack.parseOptional(level.registryAccess(), (net.minecraft.nbt.CompoundTag) tag));
        return stacks;
    }

    /** What the run has to have seen on the way: a bucket in the station, one held by the drain, water in the Basin. */
    private void observe(ServerLevel level) {
        BlockPos dock = originOf(level);
        if (dock == null)
            return;
        if (level.getBlockEntity(dock.offset(STATION_REL)) instanceof WarehouseProductionBlockEntity station
                && station.bufferedItems().count(WATER_BUCKET) > 0)
            sawBucketInStation = true;
        if (level.getBlockEntity(dock.offset(DRAIN)) instanceof ItemDrainBlockEntity drain
                && drain.getHeldItemStack().is(Items.WATER_BUCKET))
            sawBucketInDrain = true;
        if (water(level, dock.offset(BASIN)) > 0)
            sawWaterInBasin = true;
    }

    @Nullable
    private BlockPos origin;

    @Nullable
    private BlockPos originOf(ServerLevel level) {
        return origin;
    }

    // --- chapter 2: the first order, a save and rejoin in the middle ------------------------------------------------

    private void firstOrderChapter(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("fluidpattern: open the terminal screen", FluidPatternVisualScenario::openTerminalScreen)
                .until("fluidpattern: wait until the terminal offers the mud",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && terminal.entry(MUD).isPresent(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: scroll the requested amount up to " + FIRST_ORDER,
                        context -> TerminalOrder.scrollAmount(context, NAME, FIRST_ORDER))
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: click the mud cell", context -> TerminalOrder.clickCell(context, NAME, MUD))
                .serverUntil("fluidpattern: wait until the order is in", (server, context) -> orderIn(server, context,
                        FIRST_ORDER), SCREEN_TIMEOUT_TICKS)
                .client("fluidpattern: close the terminal screen", FluidPatternVisualScenario::closeScreen)
                .until("fluidpattern: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
        // The crane's bucket is in the station: funnel -> drain -> basin. Freeze the ticks the moment the basin has water.
        script.serverUntil("fluidpattern: wait for water in the Basin and freeze the ticks",
                        this::freezeOnWater, MACHINE_TIMEOUT_TICKS)
                .until("fluidpattern: the client sees the frozen machine",
                        context -> context.minecraft().level != null
                                && context.minecraft().level.tickRateManager().isFrozen(), SCREEN_TIMEOUT_TICKS)
                .camera(MACHINE_VIEW)
                .shot("fluidpattern-machine")
                .camera(DRAIN_VIEW)
                .shot("fluidpattern-drain")
                .camera(BASIN_VIEW)
                .shot("fluidpattern-basin")
                .freeze(false)
                .serverUntil("fluidpattern: wait until some mud is made and the machine still holds water",
                        (server, context) -> {
                            check();
                            return crafted >= 2 && crafted < FIRST_ORDER - 2
                                    && water(server.overworld(), context.origin().offset(BASIN))
                                    + fluidOf(server.overworld(), context.origin().offset(DRAIN), null) > 0;
                        }, ORDER_TIMEOUT_TICKS)
                .server("fluidpattern: save the world with the order half made", (server, context) -> {
                    check();
                    LOGGER.info(PREFIX + "fluidpattern: saving with {} of {} mud made and water in the drain and the "
                            + "Basin", crafted, FIRST_ORDER);
                    watch.pause(true);
                    server.saveEverything(true, true, true);
                });
        VisualWorld.reload(script, worldProfile());
        RealClicks.fly(script, NAME);
        script.serverUntil("fluidpattern: the scene is loaded and the warehouse ready after the rejoin",
                        (server, context) -> SceneItemCensus.isFullyLoaded(server.overworld(), censusBox)
                                && controller(server.overworld(), context.origin()).status() == ControllerStatus.READY,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("fluidpattern: CHECK the order is still open and the census holds after the rejoin",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            if (controller(level, context.origin()).productionOrders().stream()
                                    .noneMatch(order -> order.result().equals(MUD)
                                            && order.state() != ProductionOrderState.COMPLETE))
                                throw new VisualTestException("the mud order is gone after the rejoin: "
                                        + controller(level, context.origin()).productionOrders());
                            ledgerOn = true;
                            watch.pause(false);
                            LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the mud order came back and the ledger holds "
                                    + "after the rejoin (crafted {}, {} bucket(s) away)", crafted, inFlight);
                        })
                .serverUntil("fluidpattern: wait until the first order is made and in the terminal",
                        (server, context) -> orderDone(server, context, FIRST_ORDER), ORDER_TIMEOUT_TICKS)
                .server("fluidpattern: check the end of the first order", this::assertDone);
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(MACHINE_VIEW).shot("fluidpattern-done");
    }

    private boolean orderIn(MinecraftServer server, VisualContext context, int amount) {
        check();
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller(server.overworld(), context.origin())
                .productionOrders();
        if (orders.isEmpty())
            return false;
        ProductionOrder<ItemKey, RackPosition> order = orders.getLast();
        if (!order.result().equals(MUD) || order.resultAmount() != amount)
            throw new VisualTestException("the click created " + orders);
        LOGGER.info(PREFIX + "fluidpattern: CHECK PASS one production order of {} mud from the terminal click: {}",
                amount, order);
        return true;
    }

    private boolean freezeOnWater(MinecraftServer server, VisualContext context) {
        check();
        ServerLevel level = server.overworld();
        if (mostInFlight < 1 || water(level, context.origin().offset(BASIN)) <= 0)
            return false;
        server.tickRateManager().setFrozen(true);
        LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the bucket went station -> funnel -> drain -> pump -> pipes and "
                + "the Basin holds {} mB; ticks frozen", water(level, context.origin().offset(BASIN)));
        return true;
    }

    private boolean orderDone(MinecraftServer server, VisualContext context, int expectedMud) {
        check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller(level, dock).productionOrders();
        return !orders.isEmpty() && orders.stream().allMatch(order -> order.state() == ProductionOrderState.COMPLETE)
                && terminal(level, dock).bufferedItems().count(MUD) == baseline.getOrDefault(MUD, 0L) + expectedMud
                && crafted == expectedMud && inFlight == 0;
    }

    private void assertDone(MinecraftServer server, VisualContext context) {
        check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (mostInFlight < 1 || !sawWaterInBasin)
            throw new VisualTestException("the run did not see the whole route: filled buckets away at most "
                    + mostInFlight + ", water in the Basin " + sawWaterInBasin);
        if (mostInFlight > MAX_IN_FLIGHT)
            throw new VisualTestException(mostInFlight + " filled buckets were away at once");
        long sink = count(level, dock.offset(CHEST_NORTH), Items.BUCKET) + count(level, dock.offset(CHEST_EAST), Items.BUCKET);
        long bayLeft = SceneFluidCensus.take(level, censusBox).getOrDefault(WATER, 0L);
        if (water(level, dock.offset(BASIN)) != 0 || fluidOf(level, dock.offset(DRAIN), Direction.EAST) != 0)
            throw new VisualTestException("water is left in the Basin or the drain");
        if (level.getBlockEntity(dock.offset(DRAIN)) instanceof ItemDrainBlockEntity drain
                && !drain.getHeldItemStack().isEmpty())
            throw new VisualTestException("the Item Drain still holds " + drain.getHeldItemStack());
        LOGGER.info(PREFIX + "fluidpattern: CHECK PASS {} mud made, {} empty bucket(s) in the sink chest, at most {} "
                + "filled buckets away at once, {} mB water left in the scene, drain and Basin empty", maxCrafts,
                sink, mostInFlight, bayLeft);
        if (sink < 1)
            throw new VisualTestException("no empty bucket reached the sink chest");
    }

    private void check() {
        ServerTickWatch running = watch;
        if (running != null)
            running.check();
    }

    // --- chapter 3: the Recipe Selector on the Basin ----------------------------------------------------------------

    private void selectorChapter(VisualScript script) {
        script.server("fluidpattern: a new baseline and one Recipe Selector in the hotbar", (server, context) -> {
            ledgerOn = false;
            ServerPlayer player = context.serverPlayer(server);
            player.getInventory().setItem(SLOT_SELECTOR, WareworksBlocks.RECIPE_SELECTOR.asStack());
            player.inventoryMenu.broadcastChanges();
            resetBaseline(server.overworld(), SECOND_ORDER);
            ledgerOn = true;
        });
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        RequesterVisualScenario.selectHotbar(script, SLOT_SELECTOR, "the Recipe Selector",
                stack -> stack.is(WareworksBlocks.RECIPE_SELECTOR.asItem()));
        RealClicks.sneakClick(script, NAME, "the station", STATION_REL, Direction.WEST, new Vec3(0.0, 0.8, 0.5),
                new Vec3(0.5, RAIL_TOP, STATION_REL.getZ() + 0.5));
        RealClicks.sneakClick(script, NAME, "the Basin's north face", BASIN, Direction.NORTH, new Vec3(0.5, 0.5, 0.0),
                new Vec3(1.5, 0.0, -5.5));
        RealClicks.sneakClick(script, NAME, "the Input", INPUT_REL, Direction.WEST, new Vec3(0.0, 0.8, 0.5),
                new Vec3(0.5, RAIL_TOP, INPUT_REL.getZ() + 0.5));
        script.server("fluidpattern: CHECK the selector stands on the Basin, the Input is bound and the fluid recipe is "
                + "accepted", (server, context) -> {
            check();
            ServerLevel level = server.overworld();
            WarehouseProductionBlockEntity station = station(level, context.origin());
            RecipeSelectorBlockEntity selector = WareworksBlockEntityTypes.RECIPE_SELECTOR.getNullable(level,
                    context.origin().offset(SELECTOR_REL));
            if (selector == null || !selector.machinePos().equals(context.origin().offset(BASIN))
                    || station.selectorMode() != SelectorMode.LOCAL
                    || !station.selectorReturnInput().equals(Optional.of(context.origin().offset(INPUT_REL))))
                throw new VisualTestException("the selector is " + selector + ", the station "
                        + station.selectorMode() + " returning through " + station.selectorReturnInput());
            if (station.selectorCheck(PATTERN_SLOT).orElse(null) != SelectorRecipeCheck.Verdict.VALID)
                throw new VisualTestException("the Basin's fluid recipe is checked as " + station.selectorCheck(
                        PATTERN_SLOT) + ", not VALID");
            LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the Recipe Selector on the Basin (kind {}) accepts the "
                    + "dirt + water -> mud pattern: {}", station.selectorMachineKind(), station.selectorCheck(PATTERN_SLOT));
        });
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(BASIN_VIEW).shot("fluidpattern-selector")
                .camera(AT_TERMINAL)
                .server("fluidpattern: open the terminal screen", FluidPatternVisualScenario::openTerminalScreen)
                .until("fluidpattern: wait until the terminal offers the mud",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && terminal.entry(MUD).isPresent(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: scroll the requested amount to " + SECOND_ORDER,
                        context -> TerminalOrder.scrollAmount(context, NAME, SECOND_ORDER))
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: click the mud cell", context -> TerminalOrder.clickCell(context, NAME, MUD))
                .serverUntil("fluidpattern: wait until the second order is in",
                        (server, context) -> orderIn(server, context, SECOND_ORDER), SCREEN_TIMEOUT_TICKS)
                .client("fluidpattern: close the terminal screen", FluidPatternVisualScenario::closeScreen)
                .until("fluidpattern: wait until the terminal screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                .serverUntil("fluidpattern: wait until the second order is made and in the terminal",
                        (server, context) -> orderDone(server, context, SECOND_ORDER), ORDER_TIMEOUT_TICKS)
                .server("fluidpattern: check the end of the second order", (server, context) -> {
                    check();
                    if (!sawWaterInBasin || mostInFlight < 1)
                        throw new VisualTestException("the selector round did not use the bucket route");
                    LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the second order of {} mud ran with the Recipe "
                            + "Selector on the Basin, census exact on every tick", SECOND_ORDER);
                });
        script.camera(BASIN_VIEW).shot("fluidpattern-selector-done");
    }

    // --- chapter 4: German ------------------------------------------------------------------------------------------

    private void germanChapter(VisualScript script) {
        language.switchTo(script, "fluidpattern: ", "de_de");
        script.camera(AT_STATION)
                .server("fluidpattern: open the station's screen in German",
                        FluidPatternVisualScenario::openStationScreen)
                .until("fluidpattern: wait for the station screen with the fluid cell",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && screen.state().fluid(PATTERN_SLOT, 0).isPresent(), SCREEN_TIMEOUT_TICKS)
                .client("fluidpattern: rest the cursor on the fluid cell",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionFluidCell(productionScreen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .client("fluidpattern: check the German tooltip", context -> {
                    WarehouseProductionScreen screen = productionScreen(context);
                    List<String> tooltip = ScreenInput.productionTooltip(screen,
                            ScreenInput.productionFluidCell(screen, 0)).stream().map(Component::getString).toList();
                    String amount = WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_AMOUNT, "250")
                            .getString();
                    requireTranslated(amount, englishFluidAmount);
                    if (!tooltip.contains(amount))
                        throw new VisualTestException("the German tooltip " + tooltip + " lacks '" + amount + "'");
                    LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the fluid cell's tooltip in German: {}", tooltip);
                })
                .shot("fluidpattern-pattern-de")
                .client("fluidpattern: close the station screen", FluidPatternVisualScenario::closeScreen)
                .until("fluidpattern: wait until the station screen is closed",
                        context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.client("fluidpattern: clear the toasts", context -> context.minecraft().getToasts().clear());
        GoggleShots.shot(script, NAME, AT_STATION, "station-goggles-de", pos -> stationPos(pos),
                FluidPatternVisualScenario::stationSummarySynced, context -> {
                    List<String> lines = GoggleShots.lines(context, stationPos(context.origin()));
                    String line = GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_FLUID_PATTERNS, 1);
                    requireTranslated(line, englishGoggleLine);
                    GoggleShots.requireLine(lines, line);
                    LOGGER.info(PREFIX + "fluidpattern: CHECK PASS the station's goggles in German: {}", lines);
                });
        GoggleShots.reach(script, NAME, 0.0);
        language.switchTo(script, "fluidpattern: ", "en_us");
    }

    private static void requireTranslated(String german, String english) {
        if (german.isBlank() || german.equals(english))
            throw new VisualTestException("'" + german + "' is no German translation of '" + english + "'");
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        origin = dock;
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -3, CLEAR_MARGIN),
                dock.offset(CLEAR_MARGIN, CLEAR_HEIGHT, -RAILS - CLEAR_MARGIN));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, CLEAR_MARGIN),
                dock.offset(CLEAR_MARGIN, CLEAR_HEIGHT, -RAILS - CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        motorAt(level, dock.offset(PUMP_MOTOR), Direction.NORTH);
        motorAt(level, dock.offset(MIXER_MOTOR), Direction.UP);
    }

    private static void motorAt(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlockAndUpdate(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING,
                facing));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        motor(level, dock.offset(PUMP_MOTOR)).generatedSpeed.setValue(PUMP_RPM);
        motor(level, dock.offset(MIXER_MOTOR)).generatedSpeed.setValue(MIXER_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int n = 1; n <= RAILS; n++)
            level.setBlockAndUpdate(dock.relative(AISLE, n),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        requireAt(layout.rackPos(STATION), dock.offset(STATION_REL), "the station");
        requireAt(layout.rackPos(INPUT), dock.offset(INPUT_REL), "the input");
        level.setBlockAndUpdate(layout.rackPos(STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward(layout, STATION)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        level.setBlockAndUpdate(layout.rackPos(BAY), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, layout.sideDirection(BAY.side())));
        for (RackPosition rack : List.of(EMPTIES, DIRT_STORE)) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(chestOf(layout, rack),
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        insertInto(level, chestOf(layout, EMPTIES), new ItemStack(Items.BUCKET, EMPTY_BUCKETS));
        insertInto(level, chestOf(layout, DIRT_STORE), new ItemStack(Items.DIRT, DIRT_STOCK));
        FluidBayBlockEntity bay = bay(level, dock);
        if (bay.fill(new FluidStack(Fluids.WATER, BAY_BUCKETS * BUCKET_MB), false) != BAY_BUCKETS * BUCKET_MB)
            throw new VisualTestException("the bay refused the water");

        // The player's machine: no arm anywhere. Dirt goes straight into the Basin, the bucket through the drain.
        level.setBlockAndUpdate(dock.offset(PILLAR_LOW), AllBlocks.ANDESITE_CASING.getDefaultState());
        level.setBlockAndUpdate(dock.offset(DIRT_FUNNEL), extractingFunnel(Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(BUCKET_FUNNEL), extractingFunnel(Direction.EAST));
        // The Basin's spout points at the Input one level below its south side: the mud pours into it.
        level.setBlockAndUpdate(dock.offset(BASIN), AllBlocks.BASIN.getDefaultState().setValue(BasinBlock.FACING,
                Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(DRAIN), AllBlocks.ITEM_DRAIN.getDefaultState());
        for (BlockPos[] sink : new BlockPos[][] {{CATCH_NORTH, CHEST_NORTH}, {CATCH_EAST, CHEST_EAST}}) {
            level.setBlockAndUpdate(dock.offset(sink[1]), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(dock.offset(sink[0]), AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                    .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP));
        }
        level.setBlockAndUpdate(dock.offset(PUMP), AllBlocks.MECHANICAL_PUMP.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(PUMP_COG), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(CogWheelBlock.AXIS, Direction.Axis.Z));
        placePipes(level, dock);
        level.setBlockAndUpdate(dock.offset(MIXER), AllBlocks.MECHANICAL_MIXER.getDefaultState());
        level.setBlockAndUpdate(dock.offset(MIXER_COG), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(CogWheelBlock.AXIS, Direction.Axis.Y));
    }

    /**
     * The pipes with exactly the two connections of their path. A pipe placed beside a single neighbour opens the side
     * opposite to it ({@code FluidPipeBlock#updateBlockState}) and keeps it when the next pipe arrives, which leaves an
     * open end that pours into the world: Create's pump would divide every transfer between the Basin and that end.
     */
    private static void placePipes(ServerLevel level, BlockPos dock) {
        BlockPos previous = PUMP;
        for (int i = 0; i < PIPES.size(); i++) {
            BlockPos pipe = PIPES.get(i);
            BlockPos next = i + 1 < PIPES.size() ? PIPES.get(i + 1) : BASIN;
            net.minecraft.world.level.block.state.BlockState state = AllBlocks.FLUID_PIPE.getDefaultState();
            for (Direction side : Direction.values())
                state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(side), false);
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(towards(pipe, previous)), true)
                    .setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(towards(pipe, next)), true);
            level.setBlock(dock.offset(pipe), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            previous = pipe;
        }
    }

    private static Direction towards(BlockPos from, BlockPos to) {
        Direction direction = Direction.fromDelta(to.getX() - from.getX(), to.getY() - from.getY(),
                to.getZ() - from.getZ());
        if (direction == null)
            throw new VisualTestException(to + " is not next to " + from);
        return direction;
    }

    /** The funnels' filters are set one tick after they stand: a funnel's inventory cache needs a few ticks. */
    private static void filterFunnels(ServerLevel level, BlockPos dock) {
        setFilter(level, dock.offset(DIRT_FUNNEL), new ItemStack(Items.DIRT));
        setFilter(level, dock.offset(BUCKET_FUNNEL), new ItemStack(Items.WATER_BUCKET));
    }

    private static void setFilter(ServerLevel level, BlockPos pos, ItemStack filter) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE);
        if (behaviour == null)
            throw new VisualTestException("the funnel at " + pos + " has no filter slot");
        behaviour.setFilter(filter);
    }

    private static net.minecraft.world.level.block.state.BlockState extractingFunnel(Direction facing) {
        return AllBlocks.BRASS_FUNNEL.getDefaultState().setValue(AbstractDirectionalFunnelBlock.FACING, facing)
                .setValue(FunnelBlock.EXTRACTING, true);
    }

    private static void requireAt(BlockPos actual, BlockPos expected, String what) {
        if (!actual.equals(expected))
            throw new VisualTestException(what + " would stand at " + actual + ", the machine expects " + expected);
    }

    private boolean filtersSet;

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!filtersSet) {
            filterFunnels(level, dock);
            filtersSet = true;
        }
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && crane.isControllerLinked()
                && controller.inputStations().size() == 1 && controller.productionStations().size() == 1
                && controller.countOf(BUCKET) == EMPTY_BUCKETS && controller.countOf(DIRT) == DIRT_STOCK
                && controller.fluidStockIndex().count(WATER) == (long) BAY_BUCKETS * BUCKET_MB;
    }

    /** Flying, goggles on, and the three items the pattern is written with in the hotbar (they are ghosts there). */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(HOTBAR_DIRT, new ItemStack(Items.DIRT));
        player.getInventory().setItem(HOTBAR_MUD, new ItemStack(Items.MUD));
        player.getInventory().setItem(HOTBAR_WATER, new ItemStack(Items.WATER_BUCKET));
        player.getInventory().selected = 8;
        player.inventoryMenu.broadcastChanges();
    }

    // --- screens and client state -----------------------------------------------------------------------------------

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

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    private static boolean stationSummarySynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(stationPos(context.origin()))
                instanceof WarehouseProductionBlockEntity station))
            return false;
        ProductionGoggleSummary summary = station.productionSummary();
        return summary.fluidPatterns() == 1;
    }

    // --- positions and block entities -------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos chestOf(BranchLayout layout, RackPosition rack) {
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    private static BlockPos stationPos(BlockPos dock) {
        return dock.offset(STATION_REL);
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
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

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the terminal is missing");
        return terminal;
    }

    private static FluidBayBlockEntity bay(ServerLevel level, BlockPos dock) {
        FluidBayBlockEntity bay = WareworksBlockEntityTypes.FLUID_BAY.getNullable(level, layout(dock).rackPos(BAY));
        if (bay == null)
            throw new VisualTestException("the fluid bay is missing");
        return bay;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static long count(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        long total = 0;
        if (handler != null)
            for (int slot = 0; slot < handler.getSlots(); slot++)
                if (handler.getStackInSlot(slot).is(item))
                    total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    /** Millibuckets of water in the tanks of the block at {@code pos}. */
    private static long water(ServerLevel level, BlockPos pos) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        long total = 0;
        if (handler != null)
            for (int tank = 0; tank < handler.getTanks(); tank++)
                if (handler.getFluidInTank(tank).getFluid() == Fluids.WATER)
                    total += handler.getFluidInTank(tank).getAmount();
        return total;
    }

    private static long fluidOf(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        return handler == null ? 0 : handler.getFluidInTank(0).getAmount();
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
