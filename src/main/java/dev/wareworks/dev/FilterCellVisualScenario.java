package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.filter.FilterScreen;
import com.simibubi.create.content.logistics.filter.ListFilterItem;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PatternFilterCells;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
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
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FeederStatus;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.terminal.PlanLine;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "filtercells" (ADR-082, M34, {@code docs/warehouse-system.md} §3.5.8): a pattern cell holds a Create List
 * Filter or Attribute Filter instead of one exact item, and the warehouse pays it with the allowed item it has the most
 * of, mixing kinds only in whole runs.
 * <p>
 * The scene is the {@code feeder} machine (a production station with a Warehouse Crafter Feeder on its back, a vanilla
 * Crafter into a Warehouse Input) in a powered short aisle with a terminal, stocked with 40 dark oak, 24 birch, 14 oak
 * and 8 cherry planks and one oak log; pattern slot 2 makes 4 oak planks from the log. Every claim is a server-side
 * {@code CHECK} before the shot that shows it:
 * <ol>
 *   <li>an "is tagged #minecraft:planks" Attribute Filter is clicked into the four cells of a crafting table with real
 *       clicks ({@link ScreenInput}); the cells are filter cells, the result followed to the crafting table, the cell's
 *       tooltip lists the stock largest first ({@code filtercells-pattern});</li>
 *   <li>one table ordered at the terminal is made from <b>dark oak</b>, the largest stock: the crane carries it
 *       ({@code filtercells-carry}) and the feeder lays it ({@code filtercells-crafter}), both frozen;</li>
 *   <li>a blank List Filter is configured in Create's own screen with real clicks (oak, birch and cherry, not the dark
 *       oak; {@code filtercells-listfilter}) and clicked into the four cells; two tables take birch only, and the
 *       terminal's order tooltip says so ({@code filtercells-order});</li>
 *   <li>six tables mix whole runs: birch x16 and oak x8, laid by the feeder as two segments with one kind per grid
 *       ({@code filtercells-mixed}, frozen on the second segment);</li>
 *   <li>four tables need a chain: cherry x8 and oak x8, four of the oak made from the log at slot 2;</li>
 *   <li>one more table is refused, and the terminal names the oak log as missing ({@code filtercells-refused});</li>
 *   <li>the cell's tooltip fits the 320 x 240 floor ({@code filtercells-floor});</li>
 *   <li>the cell tooltip, the order's "Uses" line and the refusal in German ({@code -de}).</li>
 * </ol>
 * The dark oak is never touched after the List Filter replaced the Attribute Filter, and the census of the scene
 * ({@link SceneItemCensus}) is checked on every server poll: every key but the wood is unchanged and the wood is
 * conserved (a table counts four planks, a log four planks); after every act it is exact. The second pass (Flywheel off)
 * repeats the world shots. Grep {@code filtercells: ALL CHECKS PASSED in English and German}.
 */
public final class FilterCellVisualScenario implements VisualScenario {
    public static final String NAME = "filtercells";

    private static final String WORLD_FOLDER = "wareworks_visual_filtercells";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 5;
    private static final int CRANE_RPM = 128;
    /** At 8 RPM a run takes {@code 256 / 8 = 32} ticks ({@code FeederCycle#ticksPerRun}). */
    private static final int FEEDER_RPM = 8;

    private static final RackPosition STATION = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = RackPosition.of(3, 0, Side.LEFT);
    /** Cherry, oak with the log, birch, dark oak: the dark oak lies far from the station, so its carry is visible. */
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(1, 0, Side.LEFT),
            RackPosition.of(2, 0, Side.LEFT), RackPosition.of(4, 0, Side.LEFT), RackPosition.of(4, 0, Side.RIGHT));

    private static final ItemKey DARK_OAK = ItemKey.of(Items.DARK_OAK_PLANKS);
    private static final ItemKey BIRCH = ItemKey.of(Items.BIRCH_PLANKS);
    private static final ItemKey OAK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHERRY = ItemKey.of(Items.CHERRY_PLANKS);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey TABLE = ItemKey.of(Items.CRAFTING_TABLE);
    private static final Set<ItemKey> PLANKS = Set.of(DARK_OAK, BIRCH, OAK, CHERRY);
    private static final long DARK_OAK_STOCK = 40;
    private static final long BIRCH_STOCK = 24;
    private static final long OAK_STOCK = 14;
    private static final long CHERRY_STOCK = 8;
    private static final long LOG_STOCK = 1;
    /** Planks per table and per log: the unit the census conserves wood in. */
    private static final int PLANKS_PER_TABLE = 4;
    private static final int PLANKS_PER_LOG = 4;

    private static final int TABLE_SLOT = 0;
    private static final int LOG_SLOT = 1;
    private static final int[] TABLE_CELLS = {0, 1, 3, 4};

    /** The tables ordered per act, and how many lie in the terminal's buffer after it. */
    private static final int FIRST_ORDER = 1;
    private static final int SECOND_ORDER = 2;
    private static final int MIXED_ORDER = 6;
    private static final int CHAIN_ORDER = 4;
    private static final int REFUSED_ORDER = 1;

    private static final int HOTBAR_ATTRIBUTE = 0;
    private static final int HOTBAR_LIST = 1;
    private static final int HOTBAR_OAK = 2;
    private static final int HOTBAR_BIRCH = 3;
    private static final int HOTBAR_CHERRY = 4;
    private static final int HOTBAR_EMPTY = 8;
    /** The planks clicked into the List Filter's ghost slots 0, 1 and 2, as hotbar slots. */
    private static final int[] LIST_HOTBAR = {HOTBAR_OAK, HOTBAR_BIRCH, HOTBAR_CHERRY};
    private static final List<ItemKey> LIST_ITEMS = List.of(OAK, BIRCH, CHERRY);
    /** Create's {@code GhostItemMenu}: the 36 player slots come first, the ghost slots after them. */
    private static final int FIRST_GHOST_SLOT = 36;
    private static final int MAX_SCROLL_NOTCHES = 32;

    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int ORDER_TIMEOUT_TICKS = 4800;
    private static final int LANGUAGE_TIMEOUT_TICKS = 1200;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 6;
    private static final int CLICK_GAP_TICKS = 3;
    private static final double MIN_REMAINING_TRAVEL = 0.5;
    private static final long RUN_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    // Cameras, relative to the lower corner of the dock block; the aisle runs along +x (rack position n at x = n), the
    // right rack wall is z = 1: station x = 1, input x = 2, feeder and Crafter behind them at z = 2.
    private static final CameraView OVERVIEW = CameraView.of("overview", 0.0, 3.4, 5.6, 1.8, 0.4, 1.0);
    private static final CameraView MACHINE = CameraView.of("machine", 1.2, 2.0, 4.9, 2.0, 0.5, 2.4);
    private static final CameraView AT_CRAFTER = CameraView.of("crafter", 2.6, 2.2, 4.4, 2.5, 0.5, 2.4);
    private static final CameraView AT_STATION = CameraView.of("station", 1.3, 2.0, 0.35, 1.5, 0.5, 1.05);
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.3, 2.0, 0.65, 3.5, 0.5, -0.05);
    /** Looking up into the sky, so the use key reaches no block and opens the filter in hand. */
    private static final CameraView SKY = CameraView.of("sky", 2.5, 3.0, 0.5, 2.6, 9.0, 0.6);

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** The census before the first order, and what it must be after the acts so far. */
    private volatile Map<ItemKey, Long> baseline = Map.of();
    private volatile Map<ItemKey, Long> expected = Map.of();
    private volatile long woodMass;
    /** From the List Filter on, the dark oak may never move again: its count, or -1 while it still may. */
    private volatile long darkOakFrozenAt = -1;
    /** The filter clicked into the cells, as the server stores it (a ghost), set on the client when picked up. */
    @Nullable
    private volatile ItemKey cellFilter;
    /** The List Filter as the server stores it after Create's screen configured it. */
    @Nullable
    private volatile ItemKey listFilter;
    /** Orders seen before the current act, so the act's own orders are the new ones. */
    private final Set<UUID> seenOrders = new LinkedHashSet<>();
    /** The root order of the current act, whose line the terminal's tooltip is read from. */
    private volatile UUID actRoot = new UUID(0L, 0L);
    /** The plank kinds the Crafter's grid held in a row during the current act, one entry per feeder segment. */
    private final List<ItemKey> segments = new ArrayList<>();
    /** The order view of the second act, for the German "Uses" line. */
    private volatile Optional<ProductionScreenState.OrderView> birchOrderView = Optional.empty();
    private final Map<String, String> english = new HashMap<>();
    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: the use key must open a filter in hand. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("filtercells: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "filtercells run"))
                .server("filtercells: clear the area and place the motors", this::placeMotors)
                .server("filtercells: build the aisle, the station, the feeder and the Crafter", this::buildScene)
                .serverUntil("filtercells: wait until the controller has every member and the stock", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("filtercells: write 1 oak log -> 4 oak planks into pattern slot 2", this::writeLogPattern)
                .server("filtercells: lift the player into the air", FilterCellVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("filtercells: the player flies, wears goggles and holds the filters and planks",
                        FilterCellVisualScenario::equipPlayer);
        selectHotbar(script, HOTBAR_EMPTY, "an empty hand", ItemStack::isEmpty);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        GoggleShots.reach(script, NAME, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.shotFrom(MACHINE, "scene");
        if (pass != VisualPass.FLYWHEEL)
            return;

        patternAct(script);
        largestFirstAct(script);
        listFilterAct(script);
        mixedAct(script);
        chainAct(script);
        refusalAct(script);
        floorAct(script);
        germanAct(script);
        script.client("filtercells: every check passed",
                context -> LOGGER.info(PREFIX + "filtercells: ALL CHECKS PASSED in English and German (an Attribute "
                        + "Filter and a List Filter clicked into pattern cells, the largest stock first, the excluded "
                        + "dark oak never touched, whole runs mixed in feeder segments, a chain through a filter cell, "
                        + "the refusal names the oak log, the tooltip fits the floor, census conserved on every poll "
                        + "and exact after every act)"));
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringBuilder status = new StringBuilder();
        if (level != null && level.getBlockEntity(feederPos(context.origin()))
                instanceof WarehouseCrafterFeederBlockEntity feeder)
            status.append("feeder=").append(feeder.summary().status()).append('/').append(feeder.summary().runsLeft());
        if (context.minecraft().screen != null)
            status.append(" screen=").append(context.minecraft().screen.getClass().getSimpleName());
        return status.toString();
    }

    // --- act 1: the Attribute Filter into the crafting table's cells --------------------------------------------------

    private void patternAct(VisualScript script) {
        openStation(script);
        filterIntoCells(script, HOTBAR_ATTRIBUTE, "the Attribute Filter");
        script.until("filtercells: the result followed to one crafting table and the check is valid", context -> {
            ProductionScreenState state = productionScreen(context).state();
            return state.result(TABLE_SLOT).filter(view -> view.key().equals(TABLE) && view.count() == 1).isPresent()
                    && state.check(TABLE_SLOT).check() == CrafterPatternCheck.VALID
                    && state.choice(TABLE_SLOT, cellFilter).filter(view -> view.matches().size() == 4).isPresent();
        }, SCREEN_TIMEOUT_TICKS)
                .client("filtercells: CHECK the filter cells and their matches on the client", context -> {
                    ProductionScreenState state = productionScreen(context).state();
                    if (!cellFilter.equals(PatternFilterCells.inTag(ItemTags.PLANKS)))
                        throw new VisualTestException("the hotbar filter is " + cellFilter
                                + ", not the 'is tagged #minecraft:planks' filter");
                    requireFilterCells(state, cellFilter);
                    ProductionScreenState.ChoiceView choice = state.choice(TABLE_SLOT, cellFilter).orElseThrow();
                    requireMatches(choice, List.of(DARK_OAK, BIRCH, OAK, CHERRY),
                            List.of(DARK_OAK_STOCK, BIRCH_STOCK, OAK_STOCK, CHERRY_STOCK));
                    if (choice.representative().filter(DARK_OAK::equals).isEmpty() || choice.shortOfRun()
                            || choice.deny() || !choice.crafterLimited() || choice.perRun() != TABLE_CELLS.length)
                        throw new VisualTestException("the filter's view is " + choice);
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS the four cells are filter cells of {}, the result "
                            + "is one crafting table, the Crafter check is VALID, the matches are {}", cellFilter,
                            choice.matches());
                });
        hoverCell(script, TABLE_CELLS[0]);
        script.client("filtercells: CHECK the filter cell's tooltip", context -> {
            List<String> tooltip = cellTooltip(context);
            String name = cellFilter.toStack().getHoverName().getString();
            if (tooltip.isEmpty() || !tooltip.getFirst().equals(name))
                throw new VisualTestException("the tooltip does not start with the filter's name '" + name + "': "
                        + tooltip);
            if (tooltip.stream().noneMatch(line -> line.contains("minecraft:planks")))
                throw new VisualTestException("the tooltip does not show Create's summary of the tag: " + tooltip);
            requireInOrder(tooltip, List.of(
                    lang(WareworksLang.PRODUCTION_FILTER_CELL),
                    lang(WareworksLang.PRODUCTION_FILTER_MATCHES),
                    matchLine(DARK_OAK, DARK_OAK_STOCK), matchLine(BIRCH, BIRCH_STOCK), matchLine(OAK, OAK_STOCK),
                    matchLine(CHERRY, CHERRY_STOCK), lang(WareworksLang.PRODUCTION_FILTER_CRAFTER)));
            english.put("cell", lang(WareworksLang.PRODUCTION_FILTER_CELL));
            english.put("matches", lang(WareworksLang.PRODUCTION_FILTER_MATCHES));
            english.put("short", lang(WareworksLang.PRODUCTION_FILTER_SHORT));
            english.put("crafter", lang(WareworksLang.PRODUCTION_FILTER_CRAFTER));
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the cell's tooltip lists the stock largest first: {}",
                    tooltip);
        });
        clearToasts(script);
        script.shot("filtercells-pattern");
        closeScreen(script);
        script.server("filtercells: CHECK the pattern on the server", (server, context) -> {
            WarehouseProductionBlockEntity station = station(server.overworld(), context.origin());
            requireServerFilterCells(station, cellFilter);
            if (station.crafterCheck(TABLE_SLOT).check() != CrafterPatternCheck.VALID
                    || station.crafterCheck(LOG_SLOT).check() != CrafterPatternCheck.VALID)
                throw new VisualTestException("the Crafter checks are " + station.crafterChecks());
            if (!controller(server.overworld(), context.origin()).producibleKeys().contains(TABLE))
                throw new VisualTestException("the aisle does not offer crafting tables");
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the server holds four filter cells of the tag filter, "
                    + "both slots pass the Crafter check, the aisle offers crafting tables");
        }).server("filtercells: count every item of the scene", this::baselineCensus);
    }

    // --- act 2: the largest stock first -------------------------------------------------------------------------------

    private void largestFirstAct(VisualScript script) {
        orderTables(script, FIRST_ORDER);
        script.serverUntil("filtercells: wait for the order of one table", (server, context) -> {
            watch(server, context);
            Optional<ProductionOrder<ItemKey, RackPosition>> root = newRoot(server, context, FIRST_ORDER);
            if (root.isEmpty())
                return false;
            requireLines(root.get(), Map.of(DARK_OAK, 4), cellFilter);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS largest stock chosen: Dark Oak Planks x4 (stock: dark oak "
                    + "{}, birch {}, oak {}, cherry {})", DARK_OAK_STOCK, BIRCH_STOCK, OAK_STOCK, CHERRY_STOCK);
            return true;
        }, SCREEN_TIMEOUT_TICKS);
        closeScreen(script);
        script.serverUntil("filtercells: freeze while the crane carries the dark oak to the station",
                        this::freezeWhileCarrying, ORDER_TIMEOUT_TICKS)
                .until("filtercells: the client sees the frozen carry", FilterCellVisualScenario::clientFrozen,
                        SCREEN_TIMEOUT_TICKS)
                .camera("carry", FilterCellVisualScenario::carryView)
                .shot("filtercells-carry")
                .freeze(false)
                .serverUntil("filtercells: freeze when the feeder laid the dark oak", (server, context) -> {
                    watch(server, context);
                    if (!gridHolds(crafter(server.overworld(), context.origin()), DARK_OAK))
                        return false;
                    server.tickRateManager().setFrozen(true);
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS the feeder laid dark oak planks into the four "
                            + "table cells of the Crafter; ticks frozen");
                    return true;
                }, ORDER_TIMEOUT_TICKS)
                .until("filtercells: the client sees the frozen grid", FilterCellVisualScenario::clientFrozen,
                        SCREEN_TIMEOUT_TICKS)
                .camera(AT_CRAFTER)
                .shot("filtercells-crafter")
                .freeze(false);
        finishAct(script, "one table of dark oak", FIRST_ORDER, Map.of(DARK_OAK, -4L), List.of(DARK_OAK));
    }

    private boolean freezeWhileCarrying(MinecraftServer server, VisualContext context) {
        watch(server, context);
        StackerCraneBlockEntity crane = crane(server.overworld(), context.origin());
        CranePose target = crane.craneState().target();
        if (held(crane, DARK_OAK) <= 0 || crane.craneState().phase() != CranePhase.TRAVEL_TO_TARGET
                || Math.abs(target.x() - STATION.x()) > 1.0e-6 || target.side() != STATION.side()
                || Math.abs(target.x() - crane.craneState().pose().x()) <= MIN_REMAINING_TRAVEL)
            return false;
        server.tickRateManager().setFrozen(true);
        LOGGER.info(PREFIX + "filtercells: CHECK PASS the crane carries {} dark oak planks to the station; ticks frozen",
                held(crane, DARK_OAK));
        return true;
    }

    // --- act 3: a List Filter that keeps the dark oak ----------------------------------------------------------------

    private void listFilterAct(VisualScript script) {
        script.camera(SKY);
        selectHotbar(script, HOTBAR_LIST, "the blank List Filter",
                stack -> stack.getItem() instanceof ListFilterItem && !stack.has(AllDataComponents.FILTER_ITEMS));
        script.client("filtercells: press the use key with the List Filter in hand",
                        context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()))
                .until("filtercells: Create's filter screen is open",
                        context -> context.minecraft().screen instanceof FilterScreen, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
        for (int i = 0; i < LIST_ITEMS.size(); i++)
            ghostEntry(script, i, LIST_HOTBAR[i], LIST_ITEMS.get(i));
        script.client("filtercells: park the mouse", context -> ScreenInput.hover(context.minecraft(),
                        new ScreenInput.Point(1.0, 1.0)))
                .waitTicks(SETTLE_TICKS);
        clearToasts(script);
        script.shot("filtercells-listfilter")
                .client("filtercells: close the filter screen with Escape",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("filtercells: the filter screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS)
                .until("filtercells: the client's List Filter names three planks", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && filterItems(player.getInventory().getItem(HOTBAR_LIST)).size() == 3;
                }, SCREEN_TIMEOUT_TICKS)
                .server("filtercells: CHECK the configured List Filter and the census", (server, context) -> {
                    ItemStack filter = context.serverPlayer(server).getInventory().getItem(HOTBAR_LIST);
                    List<ItemKey> named = filterItems(filter);
                    if (!named.equals(LIST_ITEMS))
                        throw new VisualTestException("the List Filter names " + named + ", not " + LIST_ITEMS);
                    listFilter = PatternFilterCells.ghostOf(ItemKey.of(filter));
                    if (!PatternFilterCells.isFilter(listFilter) || PatternFilterCells.denies(listFilter))
                        throw new VisualTestException("the configured List Filter is no allow filter: " + listFilter);
                    assertCensus(server, context, "the List Filter was configured (nothing consumed)");
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS Create's filter screen wrote {} into the List "
                            + "Filter with real clicks; nothing was consumed", named);
                });
        selectHotbar(script, HOTBAR_EMPTY, "an empty hand", ItemStack::isEmpty);
        openStation(script);
        filterIntoCells(script, HOTBAR_LIST, "the List Filter");
        script.until("filtercells: the List Filter's matches arrive", context -> {
            ProductionScreenState state = productionScreen(context).state();
            return state.choice(TABLE_SLOT, cellFilter).filter(view -> view.matches().size() == 3).isPresent()
                    && state.result(TABLE_SLOT).filter(view -> view.key().equals(TABLE)).isPresent();
        }, SCREEN_TIMEOUT_TICKS)
                .client("filtercells: CHECK the List Filter's cells and matches", context -> {
                    ProductionScreenState state = productionScreen(context).state();
                    if (!cellFilter.equals(listFilter))
                        throw new VisualTestException("the cells were written with " + cellFilter + ", the server "
                                + "configured " + listFilter);
                    requireFilterCells(state, cellFilter);
                    ProductionScreenState.ChoiceView choice = state.choice(TABLE_SLOT, cellFilter).orElseThrow();
                    requireMatches(choice, List.of(BIRCH, OAK, CHERRY), List.of(BIRCH_STOCK, OAK_STOCK,
                            CHERRY_STOCK));
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS the List Filter replaced the tag filter in all four "
                            + "cells, the result stays a crafting table, the matches leave out the dark oak: {}",
                            choice.matches());
                });
        closeScreen(script);
        script.server("filtercells: from now on the dark oak may not move", (server, context) -> {
            WarehouseProductionBlockEntity station = station(server.overworld(), context.origin());
            requireServerFilterCells(station, listFilter);
            darkOakFrozenAt = controller(server.overworld(), context.origin()).countOf(DARK_OAK);
            LOGGER.info(PREFIX + "filtercells: the dark oak stands at {} and may not move again", darkOakFrozenAt);
        });
        orderTables(script, SECOND_ORDER);
        script.serverUntil("filtercells: wait for the order of two tables", (server, context) -> {
            watch(server, context);
            Optional<ProductionOrder<ItemKey, RackPosition>> root = newRoot(server, context, SECOND_ORDER);
            if (root.isEmpty())
                return false;
            requireLines(root.get(), Map.of(BIRCH, 8), listFilter);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the List Filter chose Birch Planks x8, its largest stock; "
                    + "the dark oak stays at {}", darkOakFrozenAt);
            return true;
        }, SCREEN_TIMEOUT_TICKS);
        script.until("filtercells: the terminal shows the order with what it uses",
                        context -> terminalOrder(context).filter(view -> !view.uses().isEmpty()).isPresent(),
                        SCREEN_TIMEOUT_TICKS)
                .client("filtercells: rest the cursor on the order's line", context -> ScreenInput.hover(
                        context.minecraft(), orderLinePoint(context)))
                .waitTicks(SETTLE_TICKS)
                .client("filtercells: CHECK the order's tooltip", context -> {
                    ProductionScreenState.OrderView view = terminalOrder(context).orElseThrow();
                    String uses = WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_USES,
                            WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT,
                                    BIRCH.toStack().getHoverName(), LangNumberFormat.format(8))).getString();
                    List<String> tooltip = terminalScreen(context).tooltipAt((int) orderLinePoint(context).x(),
                            (int) orderLinePoint(context).y()).stream().map(Component::getString).toList();
                    if (!tooltip.contains(uses) || view.uses().size() != 1 || view.usesMore() != 0)
                        throw new VisualTestException("the order's tooltip does not say '" + uses + "': " + tooltip
                                + " (uses " + view.uses() + ")");
                    birchOrderView = Optional.of(view);
                    english.put("uses", uses);
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS the terminal's order tooltip says '{}'", uses);
                });
        clearToasts(script);
        script.shot("filtercells-order");
        closeScreen(script);
        finishAct(script, "two tables of birch", SECOND_ORDER, Map.of(BIRCH, -8L), List.of(BIRCH));
    }

    /** Picks plank {@code key} up from hotbar slot {@code hotbar} and clicks it into ghost slot {@code ghost}. */
    private void ghostEntry(VisualScript script, int ghost, int hotbar, ItemKey key) {
        String what = key.toStack().getHoverName().getString();
        script.client("filtercells: pick up the " + what,
                        context -> ScreenInput.click(context.minecraft(), ScreenInput.hotbarSlot(filterScreen(context),
                                hotbar)))
                .until("filtercells: the " + what + " hangs on the mouse",
                        context -> key.matches(filterScreen(context).getMenu().getCarried()), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("filtercells: click ghost slot " + ghost + " with the " + what,
                        context -> ScreenInput.click(context.minecraft(), ScreenInput.menuSlot(filterScreen(context),
                                FIRST_GHOST_SLOT + ghost)))
                .until("filtercells: ghost slot " + ghost + " shows the " + what,
                        context -> key.matches(filterScreen(context).getMenu().ghostInventory.getStackInSlot(ghost)),
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("filtercells: put the " + what + " back",
                        context -> ScreenInput.click(context.minecraft(), ScreenInput.hotbarSlot(filterScreen(context),
                                hotbar)))
                .until("filtercells: the " + what + " is back in its slot",
                        context -> filterScreen(context).getMenu().getCarried().isEmpty(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS);
    }

    // --- act 4: whole runs mixed ----------------------------------------------------------------------------------------

    private void mixedAct(VisualScript script) {
        orderTables(script, MIXED_ORDER);
        script.serverUntil("filtercells: wait for the order of six tables", (server, context) -> {
            watch(server, context);
            Optional<ProductionOrder<ItemKey, RackPosition>> root = newRoot(server, context, MIXED_ORDER);
            if (root.isEmpty())
                return false;
            requireLines(root.get(), Map.of(BIRCH, 16, OAK, 8), listFilter);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS six tables mix whole runs: Birch Planks x16 (all the birch) "
                    + "and Oak Planks x8, lines {}", describeLines(root.get()));
            return true;
        }, SCREEN_TIMEOUT_TICKS);
        closeScreen(script);
        script.serverUntil("filtercells: freeze when the feeder laid the second segment", (server, context) -> {
            watch(server, context);
            if (!segments.equals(List.of(BIRCH, OAK)) || !gridHolds(crafter(server.overworld(), context.origin()), OAK))
                return false;
            server.tickRateManager().setFrozen(true);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the feeder laid birch first and then oak, one kind per "
                    + "grid; ticks frozen on the oak segment");
            return true;
        }, ORDER_TIMEOUT_TICKS)
                .until("filtercells: the client sees the frozen second segment", FilterCellVisualScenario::clientFrozen,
                        SCREEN_TIMEOUT_TICKS)
                .camera(MACHINE)
                .shot("filtercells-mixed")
                .freeze(false);
        finishAct(script, "six tables of birch and oak", MIXED_ORDER, Map.of(BIRCH, -16L, OAK, -8L),
                List.of(BIRCH, OAK));
    }

    // --- act 5: a chain through a filter cell ---------------------------------------------------------------------------

    private void chainAct(VisualScript script) {
        orderTables(script, CHAIN_ORDER);
        script.serverUntil("filtercells: wait for the chain of four tables", (server, context) -> {
            watch(server, context);
            Optional<ProductionOrder<ItemKey, RackPosition>> root = newRoot(server, context, CHAIN_ORDER);
            if (root.isEmpty())
                return false;
            requireLines(root.get(), Map.of(CHERRY, 8, OAK, 8), listFilter);
            List<ProductionOrder<ItemKey, RackPosition>> steps = newOrders(server, context).stream()
                    .filter(order -> !order.id().equals(root.get().id())).toList();
            if (steps.size() != 1)
                throw new VisualTestException("the chain has the steps " + steps);
            ProductionOrder<ItemKey, RackPosition> step = steps.getFirst();
            SupplyLine<ItemKey> oakLine = root.get().lines().stream().filter(line -> line.key().equals(OAK))
                    .findFirst().orElseThrow();
            if (!step.result().equals(OAK) || step.resultAmount() != PLANKS_PER_LOG || step.lines().size() != 1
                    || !step.lines().getFirst().key().equals(LOG) || step.lines().getFirst().required() != 1
                    || step.lines().getFirst().choice().isPresent()
                    || !step.parentLine().equals(Optional.of(oakLine.id())))
                throw new VisualTestException("the chain's step is " + step + "; the root's oak line is " + oakLine);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS a two-step plan: Cherry Planks x8 and Oak Planks x8, four "
                    + "of the oak made from the oak log at slot 2 for the oak line; lines {}", describeLines(root.get()));
            return true;
        }, SCREEN_TIMEOUT_TICKS);
        closeScreen(script);
        finishAct(script, "the chain of four tables", CHAIN_ORDER, Map.of(CHERRY, -8L, OAK, -4L, LOG, -1L),
                List.of(CHERRY, OAK));
    }

    // --- act 6: nothing left the filter allows --------------------------------------------------------------------------

    private void refusalAct(VisualScript script) {
        refusal(script, "");
    }

    /** One more table is refused and the terminal names the oak log, the deepest thing missing. */
    private void refusal(VisualScript script, String suffix) {
        orderTables(script, REFUSED_ORDER);
        String log = "filtercells: the terminal names the oak log as missing" + suffix;
        ArmVisualScenario.untilOrFail(script, log, context -> terminalScreen(context).shownStatusLine().getString()
                .contains(LOG.toStack().getHoverName().getString()), SCREEN_TIMEOUT_TICKS,
                context -> "the status line says '" + terminalScreen(context).shownStatusLine().getString() + "'");
        script.client("filtercells: CHECK the refusal" + suffix, context -> {
            WarehouseTerminalScreen terminal = terminalScreen(context);
            String shown = terminal.shownStatusLine().getString();
            if (!terminal.statusTextsFit())
                throw new VisualTestException("the status line '" + shown + "' does not fit");
            if (suffix.isEmpty())
                english.put("refused", shown);
            else
                requireTranslated(shown, english.get("refused"));
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the refusal says '{}'", shown);
        }).server("filtercells: CHECK the refusal started nothing" + suffix, (server, context) -> {
            if (!newOrders(server, context).isEmpty())
                throw new VisualTestException("the refused click started " + newOrders(server, context));
            assertCensus(server, context, "the refused click" + suffix);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the refused click started no order");
        });
        clearToasts(script);
        script.shot("filtercells-refused" + suffix);
        closeScreen(script);
    }

    // --- the 320 x 240 floor ------------------------------------------------------------------------------------------

    private void floorAct(VisualScript script) {
        openStation(script);
        script.until("filtercells: the cell is short of a run", context -> productionScreen(context).state()
                        .choice(TABLE_SLOT, cellFilter).filter(ProductionScreenState.ChoiceView::shortOfRun).isPresent(),
                        SCREEN_TIMEOUT_TICKS)
                .client("filtercells: remember the window and the GUI scale", this::rememberWindow)
                .client("filtercells: make the window " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                        context -> context.minecraft().getWindow().setWindowed(FLOOR_WIDTH, FLOOR_HEIGHT));
        ArmVisualScenario.untilOrFail(script, "filtercells: the framebuffer is " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> TooltipsVisualScenario.synced(context)
                        && TooltipsVisualScenario.framebufferIs(context.minecraft().getWindow(), FLOOR_WIDTH,
                        FLOOR_HEIGHT), RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("filtercells: GUI scale " + FLOOR_GUI_SCALE, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(FLOOR_GUI_SCALE);
            minecraft.resizeDisplay();
            Window window = minecraft.getWindow();
            if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT)
                throw new VisualTestException("the floor is " + describeWindow(window));
        }).waitTicks(SETTLE_TICKS);
        hoverCell(script, TABLE_CELLS[0]);
        script.client("filtercells: CHECK the tooltip fits the floor", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            List<Component> tooltip = ScreenInput.productionTooltip(screen,
                    ScreenInput.productionCell(screen, TABLE_CELLS[0]));
            List<String> lines = tooltip.stream().map(Component::getString).toList();
            List<String> filterLines = List.of(english.get("cell"), english.get("matches"), matchLine(OAK, 2),
                    english.get("short"), english.get("crafter"));
            requireInOrder(lines, filterLines);
            // The lines ADR-082 added; the station's older hint line is longer and NeoForge's tooltip wraps it
            // (ClientHooks#gatherTooltipComponents), which is not a layout this scenario has to prove.
            int widest = tooltip.stream().filter(line -> filterLines.contains(line.getString()))
                    .mapToInt(line -> context.minecraft().font.width(line)).max().orElse(0);
            if (widest > FLOOR_GUI_WIDTH - 2 * ProductionMenuLayout.MARGIN)
                throw new VisualTestException("a filter line of the cell's tooltip is " + widest + " px wide at the "
                        + FLOOR_GUI_WIDTH + " px floor: " + lines);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the filter lines of the short cell are at most {} px wide at the {}x{} floor: {}",
                    widest, FLOOR_GUI_WIDTH, FLOOR_GUI_HEIGHT, lines);
        });
        clearToasts(script);
        script.shot("filtercells-floor")
                .client("filtercells: put the window back",
                        context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, "filtercells: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("filtercells: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, "filtercells: the GUI is as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        closeScreen(script);
    }

    private void rememberWindow(VisualContext context) {
        Window window = context.minecraft().getWindow();
        windowWidth = window.getScreenWidth();
        windowHeight = window.getScreenHeight();
        guiWidth = window.getGuiScaledWidth();
        guiHeight = window.getGuiScaledHeight();
        guiScaleOption = context.minecraft().options.guiScale().get();
    }

    private static String describeWindow(Window window) {
        return "window " + window.getScreenWidth() + "x" + window.getScreenHeight() + ", framebuffer "
                + window.getWidth() + "x" + window.getHeight() + ", GUI " + window.getGuiScaledWidth() + "x"
                + window.getGuiScaledHeight() + " at scale " + window.getGuiScale();
    }

    // --- German ---------------------------------------------------------------------------------------------------------

    private void germanAct(VisualScript script) {
        language.switchTo(script, "filtercells: ", "de_de");
        openStation(script);
        hoverCell(script, TABLE_CELLS[0]);
        script.client("filtercells: CHECK the cell's tooltip in German", context -> {
            List<String> tooltip = cellTooltip(context);
            String cell = lang(WareworksLang.PRODUCTION_FILTER_CELL);
            String matches = lang(WareworksLang.PRODUCTION_FILTER_MATCHES);
            String shortOfRun = lang(WareworksLang.PRODUCTION_FILTER_SHORT);
            String crafter = lang(WareworksLang.PRODUCTION_FILTER_CRAFTER);
            requireTranslated(cell, english.get("cell"));
            requireTranslated(matches, english.get("matches"));
            requireTranslated(shortOfRun, english.get("short"));
            requireTranslated(crafter, english.get("crafter"));
            requireInOrder(tooltip, List.of(cell, matches, matchLine(OAK, 2), shortOfRun, crafter));
            LOGGER.info(PREFIX + "filtercells: CHECK PASS the cell's tooltip in German: {}", tooltip);
        });
        clearToasts(script);
        script.shot("filtercells-pattern-de")
                .client("filtercells: CHECK the order's 'Uses' line in German", context -> {
                    ProductionScreenState.OrderView view = birchOrderView.orElseThrow(
                            () -> new VisualTestException("no order view of the birch order was kept"));
                    String uses = WarehouseProductionScreen.usesLines(view).getFirst().getString();
                    requireTranslated(uses, english.get("uses"));
                    if (!uses.contains(BIRCH.toStack().getHoverName().getString()))
                        throw new VisualTestException("the German 'Uses' line '" + uses + "' does not name the birch");
                    LOGGER.info(PREFIX + "filtercells: CHECK PASS the order's line in German: '{}'", uses);
                });
        closeScreen(script);
        refusal(script, "-de");
        language.switchTo(script, "filtercells: ", "en_us");
    }

    private static void requireTranslated(String german, String english) {
        if (german == null || english == null || german.isBlank() || german.equals(english))
            throw new VisualTestException("'" + german + "' is no German translation of '" + english + "'");
    }

    // --- shared steps ---------------------------------------------------------------------------------------------------

    private void openStation(VisualScript script) {
        script.camera(AT_STATION)
                .server("filtercells: open the station's screen", (server, context) -> {
                    if (!station(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the production station screen could not be opened");
                })
                .until("filtercells: wait for the station screen with the feeder flag",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && screen.state().feeder() && screen.selectedPattern() == TABLE_SLOT,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /**
     * Picks the filter of hotbar slot {@code hotbar} up, clicks it into the four cells of the crafting table, waiting
     * for the server's answer after each, and puts it back. The cursor keeps the filter: a cell holds a ghost.
     */
    private void filterIntoCells(VisualScript script, int hotbar, String what) {
        script.client("filtercells: pick up " + what, context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.hotbarSlot(productionScreen(context), hotbar)))
                .until("filtercells: " + what + " hangs on the mouse", context -> {
                    ItemStack carried = productionScreen(context).getMenu().getCarried();
                    if (carried.isEmpty())
                        return false;
                    cellFilter = PatternFilterCells.ghostOf(ItemKey.of(carried));
                    return PatternFilterCells.isFilter(cellFilter);
                }, SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS);
        for (int cell : TABLE_CELLS) {
            script.client("filtercells: click pattern cell " + cell + " with " + what,
                            context -> ScreenInput.click(context.minecraft(),
                                    ScreenInput.productionCell(productionScreen(context), cell)))
                    .until("filtercells: the server wrote " + what + " into cell " + cell,
                            context -> productionScreen(context).state().entry(TABLE_SLOT, cell)
                                    .filter(entry -> entry.filter() && entry.key().equals(cellFilter)).isPresent(),
                            SCREEN_TIMEOUT_TICKS)
                    .client("filtercells: CHECK the cursor still holds " + what, context -> {
                        if (productionScreen(context).getMenu().getCarried().isEmpty())
                            throw new VisualTestException("the click into cell " + cell + " consumed " + what);
                    })
                    .waitTicks(CLICK_GAP_TICKS);
        }
        script.client("filtercells: put " + what + " back", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.hotbarSlot(productionScreen(context), hotbar)))
                .until("filtercells: " + what + " is back in its slot",
                        context -> productionScreen(context).getMenu().getCarried().isEmpty(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("filtercells: CHECK " + what + " went into the cells with real clicks",
                        context -> LOGGER.info(PREFIX + "filtercells: CHECK PASS {} went into cells {} with real "
                                + "clicks: {}", what, Arrays.toString(TABLE_CELLS), cellFilter));
    }

    private static void hoverCell(VisualScript script, int cell) {
        script.client("filtercells: rest the cursor on cell " + cell, context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionCell(productionScreen(context), cell)))
                .waitTicks(SETTLE_TICKS);
    }

    private static List<String> cellTooltip(VisualContext context) {
        WarehouseProductionScreen screen = productionScreen(context);
        return ScreenInput.productionTooltip(screen, ScreenInput.productionCell(screen, TABLE_CELLS[0])).stream()
                .map(Component::getString).toList();
    }

    /**
     * Opens the terminal, scrolls the amount to {@code tables} and clicks the crafting table's cell; the orders that
     * exist at the click are remembered, so the act's own are the new ones.
     */
    private void orderTables(VisualScript script, int tables) {
        script.server("filtercells: remember the orders before the click", (server, context) -> {
            seenOrders.clear();
            for (ProductionOrder<ItemKey, RackPosition> order : controller(server.overworld(), context.origin())
                    .productionOrders())
                seenOrders.add(order.id());
            segments.clear();
        });
        script.camera(AT_TERMINAL)
                .server("filtercells: open the terminal screen", (server, context) -> {
                    if (!terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the terminal screen could not be opened");
                })
                .until("filtercells: wait until the terminal offers crafting tables",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && terminal.entry(TABLE).isPresent(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("filtercells: scroll the requested amount to " + tables, context -> scrollAmount(context, tables))
                .waitTicks(SETTLE_TICKS)
                .client("filtercells: click the crafting table's cell", FilterCellVisualScenario::clickTable);
    }

    private static void scrollAmount(VisualContext context, int wanted) {
        ScrollInput amount = ScreenInput.terminalAmount(terminalScreen(context));
        ScreenInput.Point point = ScreenInput.centre(amount);
        for (int notch = 0; notch < MAX_SCROLL_NOTCHES && amount.getState() != wanted; notch++)
            ScreenInput.scroll(context.minecraft(), point, amount.getState() < wanted ? 1.0 : -1.0);
        if (amount.getState() != wanted)
            throw new VisualTestException("the requested amount stayed at " + amount.getState());
    }

    private static void clickTable(VisualContext context) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (visible.get(cell).key().equals(TABLE)) {
                ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
                return;
            }
        }
        throw new VisualTestException("the terminal shows no crafting table cell");
    }

    /** The act's own orders are complete, the tables are in the terminal, the feeder is idle; then the census. */
    private void finishAct(VisualScript script, String what, int tables, Map<ItemKey, Long> spent,
            List<ItemKey> expectedSegments) {
        script.serverUntil("filtercells: wait until " + what + " are in the terminal", (server, context) -> {
            watch(server, context);
            ServerLevel level = server.overworld();
            BlockPos dock = context.origin();
            List<ProductionOrder<ItemKey, RackPosition>> orders = newOrders(server, context);
            for (ProductionOrder<ItemKey, RackPosition> order : orders) {
                if (order.isOpen())
                    return false;
                if (order.state() != ProductionOrderState.COMPLETE)
                    throw new VisualTestException("an order of " + what + " ended " + order.state() + ": " + order);
            }
            return !orders.isEmpty() && terminal(level, dock).bufferedItems().count(TABLE)
                    == expected.getOrDefault(TABLE, 0L) + tables
                    && feeder(level, dock).status() == FeederStatus.IDLE && gridEmpty(crafter(level, dock));
        }, ORDER_TIMEOUT_TICKS).server("filtercells: CHECK the end of " + what, (server, context) -> {
            if (!segments.equals(expectedSegments))
                throw new VisualTestException("the feeder laid the segments " + segments + ", expected "
                        + expectedSegments);
            Map<ItemKey, Long> next = SceneItemCensus.plus(expected, TABLE, tables);
            for (Map.Entry<ItemKey, Long> change : spent.entrySet())
                next = SceneItemCensus.plus(next, change.getKey(), change.getValue());
            expected = next;
            assertCensus(server, context, what);
            LOGGER.info(PREFIX + "filtercells: CHECK PASS {}: {} tables in the terminal, feeder segments {}, census "
                    + "exact", what, expected.getOrDefault(TABLE, 0L), segments);
        });
    }

    private static void closeScreen(VisualScript script) {
        script.client("filtercells: close the screen", context -> {
            LocalPlayer player = context.minecraft().player;
            if (player != null)
                player.closeContainer();
        }).until("filtercells: no screen is open", context -> context.minecraft().screen == null,
                SCREEN_TIMEOUT_TICKS);
    }

    private static void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> expected) {
        script.client("filtercells: press hotbar key " + (slot + 1) + " for " + what,
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[slot].getKey()));
        ArmVisualScenario.untilOrFail(script, "filtercells: the player holds " + what, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot && expected.test(player.getMainHandItem());
        }, SCREEN_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem()
                + " in slot " + context.minecraft().player.getInventory().selected);
        script.waitTicks(SETTLE_TICKS);
    }

    /** The recipe toasts the planks in the hotbar set off sit in the corner of a shot with the GUI shown. */
    private static void clearToasts(VisualScript script) {
        script.client("filtercells: clear the toasts", context -> context.minecraft().getToasts().clear());
    }

    // --- checks ---------------------------------------------------------------------------------------------------------

    private static void requireFilterCells(ProductionScreenState state, ItemKey filter) {
        for (int cell : TABLE_CELLS) {
            Optional<ProductionScreenState.EntryView> entry = state.entry(TABLE_SLOT, cell);
            if (entry.isEmpty() || !entry.get().filter() || !entry.get().key().equals(filter) || entry.get().count() != 1)
                throw new VisualTestException("cell " + cell + " is " + entry + ", not a filter cell of " + filter);
        }
        for (int cell = 0; cell < ProductionPatterns.GRID_CELLS; cell++) {
            int other = cell;
            if (Arrays.stream(TABLE_CELLS).noneMatch(used -> used == other) && state.entry(TABLE_SLOT, cell).isPresent())
                throw new VisualTestException("cell " + cell + " is filled: " + state.entry(TABLE_SLOT, cell));
        }
    }

    private static void requireServerFilterCells(WarehouseProductionBlockEntity station, ItemKey filter) {
        ProductionPatterns patterns = station.patterns();
        for (int cell : TABLE_CELLS) {
            if (!patterns.isFilterAt(TABLE_SLOT, cell)
                    || !patterns.keyAt(TABLE_SLOT, cell).filter(filter::equals).isPresent())
                throw new VisualTestException("the server's cell " + cell + " holds " + patterns.keyAt(TABLE_SLOT, cell)
                        + " (filter " + patterns.isFilterAt(TABLE_SLOT, cell) + "), not " + filter);
        }
        if (!patterns.keyAt(TABLE_SLOT, ProductionPatterns.RESULT_ENTRY).filter(TABLE::equals).isPresent()
                || patterns.countAt(TABLE_SLOT, ProductionPatterns.RESULT_ENTRY) != 1)
            throw new VisualTestException("the server's result is " + patterns.keyAt(TABLE_SLOT,
                    ProductionPatterns.RESULT_ENTRY));
    }

    private static void requireMatches(ProductionScreenState.ChoiceView choice, List<ItemKey> keys, List<Long> counts) {
        List<ItemKey> shownKeys = choice.matches().stream().map(ProductionScreenState.MatchView::key).toList();
        List<Long> shownCounts = choice.matches().stream().map(ProductionScreenState.MatchView::available).toList();
        if (!shownKeys.equals(keys) || !shownCounts.equals(counts) || choice.more() != 0)
            throw new VisualTestException("the filter's matches are " + choice.matches() + " (+" + choice.more()
                    + "), expected " + keys + " " + counts);
    }

    /** Every line of {@code lines} appears in {@code tooltip}, in this order. */
    private static void requireInOrder(List<String> tooltip, List<String> lines) {
        int from = 0;
        for (String line : lines) {
            int at = tooltip.subList(from, tooltip.size()).indexOf(line);
            if (at < 0)
                throw new VisualTestException("the tooltip has no '" + line + "' after line " + from + ": " + tooltip);
            from += at + 1;
        }
    }

    private static String lang(String key) {
        return WareworksLang.translateDirect(key).getString();
    }

    private static String matchLine(ItemKey key, long count) {
        return WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_MATCH, key.toStack().getHoverName(),
                LangNumberFormat.format(count)).getString();
    }

    /** The root order of the act: the one new order of {@code tables} crafting tables that feeds no other. */
    private Optional<ProductionOrder<ItemKey, RackPosition>> newRoot(MinecraftServer server, VisualContext context,
            int tables) {
        List<ProductionOrder<ItemKey, RackPosition>> roots = newOrders(server, context).stream()
                .filter(order -> order.parentLine().isEmpty()).toList();
        if (roots.isEmpty())
            return Optional.empty();
        ProductionOrder<ItemKey, RackPosition> root = roots.getFirst();
        if (roots.size() != 1 || !root.result().equals(TABLE) || root.resultAmount() != tables)
            throw new VisualTestException("the click of " + tables + " tables started " + roots);
        actRoot = root.id();
        return Optional.of(root);
    }

    private List<ProductionOrder<ItemKey, RackPosition>> newOrders(MinecraftServer server, VisualContext context) {
        return controller(server.overworld(), context.origin()).productionOrders().stream()
                .filter(order -> !seenOrders.contains(order.id())).toList();
    }

    /** The order's lines are exactly {@code amounts}, every one of them chosen by {@code filter}. */
    private static void requireLines(ProductionOrder<ItemKey, RackPosition> order, Map<ItemKey, Integer> amounts,
            ItemKey filter) {
        Map<ItemKey, Integer> shown = new HashMap<>();
        for (SupplyLine<ItemKey> line : order.lines()) {
            if (!line.choice().equals(Optional.of(filter)))
                throw new VisualTestException("the line " + line + " was not chosen by " + filter);
            shown.merge(line.key(), line.required(), Integer::sum);
        }
        if (!shown.equals(amounts))
            throw new VisualTestException("the order's lines are " + describeLines(order) + ", expected " + amounts);
    }

    private static String describeLines(ProductionOrder<ItemKey, RackPosition> order) {
        List<String> lines = new ArrayList<>();
        for (SupplyLine<ItemKey> line : order.lines())
            lines.add(line.key() + " x" + line.required() + (line.choice().isPresent() ? " (filter)" : ""));
        return lines.toString();
    }

    /**
     * The check of every server poll: every key but the wood is where it was, the wood is conserved (a crafting table
     * and an oak log count four planks each), the dark oak has not moved since the List Filter, and the Crafter's grid
     * holds one plank kind at most, recorded as a feeder segment when it changes.
     */
    private void watch(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        Map<ItemKey, Long> actual = SceneItemCensus.take(level, censusBox);
        Set<ItemKey> keys = new HashSet<>(actual.keySet());
        keys.addAll(baseline.keySet());
        for (ItemKey key : keys) {
            if (PLANKS.contains(key) || key.equals(TABLE) || key.equals(LOG))
                continue;
            if (actual.getOrDefault(key, 0L).longValue() != baseline.getOrDefault(key, 0L).longValue())
                throw new VisualTestException("item conservation violated: " + key + " changed from "
                        + baseline.getOrDefault(key, 0L) + " to " + actual.getOrDefault(key, 0L));
        }
        long mass = woodMass(actual);
        if (mass != woodMass)
            throw new VisualTestException("item conservation violated: the wood is " + mass + " planks, not "
                    + woodMass + ": " + SceneItemCensus.describe(actual));
        if (darkOakFrozenAt >= 0 && actual.getOrDefault(DARK_OAK, 0L) != darkOakFrozenAt)
            throw new VisualTestException("the dark oak the List Filter excludes moved: " + actual.getOrDefault(
                    DARK_OAK, 0L) + " instead of " + darkOakFrozenAt);
        CrafterBlockEntity crafter = crafter(level, context.origin());
        Set<ItemKey> kinds = new HashSet<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = crafter.getItem(slot);
            if (!stack.isEmpty() && PLANKS.contains(ItemKey.of(stack)))
                kinds.add(ItemKey.of(stack));
        }
        if (kinds.size() > 1)
            throw new VisualTestException("the Crafter's grid holds two plank kinds at once: " + kinds);
        if (kinds.size() == 1) {
            ItemKey kind = kinds.iterator().next();
            if (segments.isEmpty() || !segments.getLast().equals(kind))
                segments.add(kind);
        }
    }

    private static long woodMass(Map<ItemKey, Long> counts) {
        long mass = 0;
        for (ItemKey plank : PLANKS)
            mass += counts.getOrDefault(plank, 0L);
        return mass + PLANKS_PER_TABLE * counts.getOrDefault(TABLE, 0L)
                + PLANKS_PER_LOG * counts.getOrDefault(LOG, 0L);
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEquals(level, censusBox, expected, "filtercells: " + step);
        LOGGER.info(PREFIX + "filtercells: CHECK PASS census exact after {}", step);
    }

    private void baselineCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        baseline = SceneItemCensus.take(level, censusBox);
        expected = baseline;
        woodMass = woodMass(baseline);
        if (baseline.getOrDefault(DARK_OAK, 0L) != DARK_OAK_STOCK || baseline.getOrDefault(BIRCH, 0L) != BIRCH_STOCK
                || baseline.getOrDefault(OAK, 0L) != OAK_STOCK || baseline.getOrDefault(CHERRY, 0L) != CHERRY_STOCK
                || baseline.getOrDefault(LOG, 0L) != LOG_STOCK)
            throw new VisualTestException("the baseline is " + SceneItemCensus.describe(baseline));
        LOGGER.info(PREFIX + "filtercells: baseline census {} ({} planks of wood)", SceneItemCensus.describe(baseline),
                woodMass);
    }

    private static boolean gridHolds(CrafterBlockEntity crafter, ItemKey key) {
        for (int cell : TABLE_CELLS)
            if (!key.matches(crafter.getItem(cell)))
                return false;
        return true;
    }

    private static boolean gridEmpty(CrafterBlockEntity crafter) {
        for (int slot = 0; slot < 9; slot++)
            if (!crafter.getItem(slot).isEmpty())
                return false;
        return true;
    }

    private static long held(StackerCraneBlockEntity crane, ItemKey key) {
        long held = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries())
            if (entry.key().equals(key))
                held += entry.count();
        return held;
    }

    private static List<ItemKey> filterItems(ItemStack filter) {
        ItemContainerContents contents = filter.get(AllDataComponents.FILTER_ITEMS);
        if (contents == null)
            return List.of();
        return contents.nonEmptyStream().map(ItemKey::of).toList();
    }

    // --- client state ---------------------------------------------------------------------------------------------------

    private Optional<ProductionScreenState.OrderView> terminalOrder(VisualContext context) {
        return terminalScreen(context).productionOrders().stream().filter(view -> view.id().equals(actRoot))
                .findFirst();
    }

    private ScreenInput.Point orderLinePoint(VisualContext context) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        List<PlanLine> lines = terminal.visibleOrderLines();
        for (int index = 0; index < lines.size(); index++)
            if (lines.get(index).head().equals(actRoot))
                return ScreenInput.terminalOrderLine(terminal, index + terminal.visibleFillLines().size());
        throw new VisualTestException("the terminal shows no line of order " + actRoot + ": " + lines);
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    /** In the aisle beside the frozen client crane, looking down at the planks on its handling head. */
    private static CameraView carryView(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane))
            throw new VisualTestException("the client has no crane to look at");
        double x = crane.craneState().pose().x() + 0.5;
        return CameraView.of("carry", x + 1.6, 2.3, 0.1, x, 0.6, 0.6);
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

    private static FilterScreen filterScreen(VisualContext context) {
        if (context.minecraft().screen instanceof FilterScreen screen)
            return screen;
        throw new VisualTestException("Create's filter screen is not open (screen: " + context.minecraft().screen + ")");
    }

    // --- build (server thread) ------------------------------------------------------------------------------------------

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
        insertInto(level, barrel(layout, 0), CHERRY.toStack((int) CHERRY_STOCK));
        insertInto(level, barrel(layout, 1), OAK.toStack((int) OAK_STOCK));
        insertInto(level, barrel(layout, 1), LOG.toStack((int) LOG_STOCK));
        insertInto(level, barrel(layout, 2), BIRCH.toStack((int) BIRCH_STOCK));
        insertInto(level, barrel(layout, 3), DARK_OAK.toStack((int) DARK_OAK_STOCK));

        // The feeder on the station's back, facing along the rack wall; the Crafter in front of it, behind the input,
        // its output facing the input (ADR-067 D2), as in the feeder scenario.
        level.setBlockAndUpdate(feederPos(dock), WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, AISLE)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        level.setBlockAndUpdate(crafterPos(dock), Blocks.CRAFTER.defaultBlockState().setValue(
                BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(inward(layout, INPUT), Direction.UP)));
    }

    private static BlockPos barrel(BranchLayout layout, int storage) {
        RackPosition rack = STORAGE.get(storage);
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
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
                && controller.countOf(DARK_OAK) == DARK_OAK_STOCK && controller.countOf(BIRCH) == BIRCH_STOCK
                && controller.countOf(OAK) == OAK_STOCK && controller.countOf(CHERRY) == CHERRY_STOCK
                && controller.countOf(LOG) == LOG_STOCK && feeder.running() && station.attachedFeeder().isPresent();
    }

    /** Slot 2 makes four oak planks from one oak log, so a short filter cell can have the rest made. */
    private void writeLogPattern(MinecraftServer server, VisualContext context) {
        WarehouseProductionBlockEntity station = station(server.overworld(), context.origin());
        if (!station.setPatternEntry(LOG_SLOT, 0, LOG, 1)
                || !station.setPatternEntry(LOG_SLOT, ProductionPatterns.RESULT_ENTRY, OAK, PLANKS_PER_LOG))
            throw new VisualTestException("the log pattern could not be written: " + station.patterns());
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * Flying, goggles on; in the hotbar the "is tagged #minecraft:planks" Attribute Filter (built by
     * {@link PatternFilterCells#inTag}: configuring it in Create's screen would test Create), a blank List Filter and
     * one oak, birch and cherry plank for the List Filter's ghost slots. The player is not part of the census.
     */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(HOTBAR_ATTRIBUTE, PatternFilterCells.inTag(ItemTags.PLANKS).toStack());
        player.getInventory().setItem(HOTBAR_LIST, AllItems.FILTER.asStack());
        player.getInventory().setItem(HOTBAR_OAK, OAK.toStack());
        player.getInventory().setItem(HOTBAR_BIRCH, BIRCH.toStack());
        player.getInventory().setItem(HOTBAR_CHERRY, CHERRY.toStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- positions ------------------------------------------------------------------------------------------------------

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

    private static BlockPos feederPos(BlockPos dock) {
        return stationPos(dock).relative(layout(dock).sideDirection(STATION.side()));
    }

    private static BlockPos crafterPos(BlockPos dock) {
        return feederPos(dock).relative(AISLE);
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(2),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN).above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories ---------------------------------------------------------------------------------

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

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the stacker crane is missing");
        return crane;
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
