package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.RecipeSelectorBlockEntity;
import dev.wareworks.content.selector.RecipeSelectorStatus;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.terminal.PlanLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "selector" (ADR-085, design §15.3): the local <b>Recipe Selector</b>, end to end and the way a player
 * builds and uses it, in its own world {@value #WORLD_FOLDER}, no extra mods.
 * <p>
 * <b>Station A</b> (a saw line) makes Deepslate Tile Stairs, Slabs and Walls from Cobbled Deepslate: a hopper under
 * the station drops what the crane delivers onto a belt that runs into a Mechanical Saw facing up, which pushes its
 * products straight into Warehouse Input A (the {@code showcase}'s proven sawmill geometry). The selector sits on the
 * saw's outer side. <b>Station B</b> makes gray and light gray dye from black and white dye: two hoppers carry the
 * ingredients into a Basin under a Mechanical Mixer, whose spout drops the dye onto a belt that a funnel empties into
 * Warehouse Input B; its selector sits on the Basin (a sneak-click on the Mixer first is refused).
 * <p>
 * <b>Real clicks</b>: the selectors linked with sneak-clicks on the stations, placed with sneak-clicks on the saw and
 * the Basin, the return inputs bound with sneak-clicks; the five patterns written through {@link ScreenInput} (first a
 * wrong slab pattern, which the selector check draws red); every order a stock click at the terminal (stairs, slabs and
 * walls back to back; gray and light gray back to back); a hand change on the saw's value box and a List Filter put in
 * and taken out with clicks; a stairs order cancelled on its terminal line; a slab thrown into the hopper over Input A,
 * taken out of the input with an empty-hand click and the hold released with a sneak-click on the station.
 * <p>
 * <b>Checked on every server tick</b> ({@link ServerTickWatch}): the saw's filter never changes while it holds an item
 * (except a set-back to the selection), no selector reports FILTER_REFUSED, the crane is asked only for the selection's
 * ingredients and nothing while a station holds, none of the other cobbled deepslate stonecutting results is ever in
 * stock, no light gray while the Basin makes gray, no order ever times out (the deadline of this world is shortened, so
 * a queued order whose deadline ran would). {@link SceneItemCensus} is exact after every act. Grep
 * {@code selector: ALL CHECKS PASSED in English and German}.
 */
public final class SelectorVisualScenario implements VisualScenario {
    public static final String NAME = "selector";

    private static final String WORLD_FOLDER = "wareworks_visual_selector";
    private static final long RUN_TIMEOUT_MILLIS = 25L * 60L * 1000L;

    // --- the warehouse (offsets from the dock; the aisle runs east, the right rack side is +z) ----------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 10;
    private static final int CRANE_RPM = 256;
    private static final int SAW_RPM = 128;
    private static final int BELT_RPM = 64;
    private static final int MIXER_RPM = 128;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT_A_RACK = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition STATION_A_RACK = RackPosition.of(5, 1, Side.RIGHT);
    private static final RackPosition STATION_B_RACK = RackPosition.of(7, 2, Side.RIGHT);
    private static final RackPosition INPUT_B_RACK = RackPosition.of(8, 1, Side.RIGHT);
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(1, 0, Side.LEFT),
            RackPosition.of(2, 0, Side.LEFT), RackPosition.of(3, 0, Side.LEFT), RackPosition.of(4, 0, Side.LEFT));

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);
    private static final BlockPos INPUT_A = new BlockPos(3, 0, 1);
    private static final BlockPos QUARANTINE_HOPPER = INPUT_A.above();
    private static final BlockPos STATION_A = new BlockPos(5, 1, 1);
    private static final BlockPos HOPPER_A = STATION_A.below();
    private static final BlockPos BELT_A_START = new BlockPos(6, 0, 2);
    private static final BlockPos BELT_A_END = new BlockPos(4, 0, 2);
    private static final BlockPos BELT_A_MOTOR = BELT_A_START.south();
    private static final BlockPos SAW = new BlockPos(3, 0, 2);
    private static final BlockPos SAW_MOTOR = SAW.west();
    private static final BlockPos SELECTOR_A = SAW.south();
    private static final BlockPos STATION_B = new BlockPos(7, 2, 1);
    private static final BlockPos HOPPER_B1 = STATION_B.below();
    private static final BlockPos HOPPER_B2 = HOPPER_B1.south();
    private static final BlockPos BASIN = HOPPER_B2.south();
    private static final BlockPos MIXER = BASIN.above(2);
    private static final BlockPos COG = MIXER.east();
    private static final BlockPos MIXER_MOTOR = COG.above();
    private static final BlockPos SELECTOR_B = BASIN.south();
    private static final BlockPos BELT_B_START = new BlockPos(8, 0, 3);
    private static final BlockPos BELT_B_END = new BlockPos(8, 0, 2);
    private static final BlockPos BELT_B_MOTOR = BELT_B_START.east();
    private static final BlockPos FUNNEL_B = BELT_B_END.above();
    private static final BlockPos INPUT_B = new BlockPos(8, 1, 1);
    private static final BlockPos COBBLED_BARREL = new BlockPos(1, 0, -2);
    private static final BlockPos DYE_BARREL = new BlockPos(2, 0, -2);
    private static final BlockPos LIFT = new BlockPos(4, 6, -4);
    private static final BlockPos SCENE_MIN = new BlockPos(-3, -1, -4);
    private static final BlockPos SCENE_MAX = new BlockPos(RAILS + 2, 6, 8);

    // --- items -------------------------------------------------------------------------------------------------------
    private static final ItemKey COBBLED = ItemKey.of(Items.COBBLED_DEEPSLATE);
    private static final ItemKey STAIRS = ItemKey.of(Items.DEEPSLATE_TILE_STAIRS);
    private static final ItemKey SLAB = ItemKey.of(Items.DEEPSLATE_TILE_SLAB);
    private static final ItemKey WALL = ItemKey.of(Items.DEEPSLATE_TILE_WALL);
    private static final ItemKey BLACK = ItemKey.of(Items.BLACK_DYE);
    private static final ItemKey WHITE = ItemKey.of(Items.WHITE_DYE);
    private static final ItemKey GRAY = ItemKey.of(Items.GRAY_DYE);
    private static final ItemKey LIGHT_GRAY = ItemKey.of(Items.LIGHT_GRAY_DYE);
    /**
     * Every other result a saw cuts from cobbled deepslate (vanilla 1.21.1 stonecutting, checked against the recipes
     * of the game once; the scenarios must not read recipes, {@code RecipeExecutionBoundaryTest}). Shared with
     * {@code selector-remote}.
     */
    static final List<ItemKey> OTHER_CUTS = List.of(ItemKey.of(Items.COBBLED_DEEPSLATE_STAIRS),
            ItemKey.of(Items.COBBLED_DEEPSLATE_SLAB), ItemKey.of(Items.COBBLED_DEEPSLATE_WALL),
            ItemKey.of(Items.CHISELED_DEEPSLATE), ItemKey.of(Items.POLISHED_DEEPSLATE),
            ItemKey.of(Items.POLISHED_DEEPSLATE_STAIRS), ItemKey.of(Items.POLISHED_DEEPSLATE_SLAB),
            ItemKey.of(Items.POLISHED_DEEPSLATE_WALL), ItemKey.of(Items.DEEPSLATE_BRICKS),
            ItemKey.of(Items.DEEPSLATE_BRICK_STAIRS), ItemKey.of(Items.DEEPSLATE_BRICK_SLAB),
            ItemKey.of(Items.DEEPSLATE_BRICK_WALL), ItemKey.of(Items.DEEPSLATE_TILES));
    private static final int COBBLED_STOCK = 192;
    private static final int BLACK_STOCK = 16;
    private static final int WHITE_STOCK = 32;
    private static final int ORDER = 16;
    /** More than one crane trip (a stack), so a cancel after the first delivery leaves the rest in stock. */
    private static final int CANCELLED_ORDER = 96;
    private static final int GRAY_ORDER = 8;
    private static final int LIGHT_GRAY_ORDER = 9;
    /** Shorter than the default, so an order queued behind another result for longer would time out if its ran. */
    private static final int DEADLINE_TICKS = 900;
    /** How far past its deadline the queued slab order is kept waiting under the hold before the player acts. */
    private static final int PAST_DEADLINE_TICKS = 100;

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_SELECTORS = 0;
    private static final int SLOT_A = 0;
    private static final int SLOT_B = 1;
    private static final int SLOT_C = 2;
    private static final int SLOT_D = 3;
    private static final int SLOT_EMPTY = 8;
    private static final int SLOT_HAND_ITEM = 0;
    private static final int SLOT_LIST_FILTER = 1;
    private static final int SLOT_THROWN_SLAB = 2;

    // --- timing (client ticks) ---------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int STEP_TIMEOUT_TICKS = 4800;
    private static final int QUIET_TIMEOUT_TICKS = 1200;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- where the player stands for a sneak-click (feet, relative to the dock) --------------------------------------
    /** On the rails: a rail is 3 px high. */
    private static final double RAIL_TOP = WarehouseRailBlock.HEIGHT_PIXELS / 16.0;
    private static final double AISLE_STAND_Z = 0.3;
    private static final Vec3 ABOVE_STATION = new Vec3(0.0, 2.0, 0.6);
    private static final Vec3 FACING_INPUT_A = new Vec3(0.0, 1.4, -0.6);

    // --- camera views ------------------------------------------------------------------------------------------------
    private static final CameraView HALL_VIEW = CameraView.of("hall", 5.5, 6.5, 10.5, 5.0, 0.5, 2.0);
    private static final CameraView SAW_VIEW = CameraView.of("saw", 3.5, 2.6, 5.4, 3.5, 0.5, 2.3);
    private static final CameraView BASIN_VIEW = CameraView.of("basin", 10.5, 3.2, 6.5, 7.5, 1.5, 3.0);
    private static final CameraView STATION_A_GOGGLES = CameraView.of("station-a", 5.5, 1.9, 0.2, 5.5, 1.5, 1.0);
    private static final CameraView SELECTOR_A_GOGGLES = CameraView.of("selector-a", 3.5, 1.3, 5.0, 3.5, 0.5, 3.2);

    private static final Field BASIN_SPOUT = RealClicks.field(BasinBlockEntity.class, "spoutputBuffer");

    // --- state -------------------------------------------------------------------------------------------------------
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);
    private final Map<String, String> englishLines = new HashMap<>();
    private final Set<String> deviations = new LinkedHashSet<>();
    private volatile BlockPos origin = BlockPos.ZERO;
    private volatile AABB sceneBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> expected = Map.of();
    private volatile Set<ItemKey> otherResults = Set.of();
    private volatile int timeoutBefore = -1;
    private volatile boolean sawFlipped;
    private volatile boolean beltAFlipped;
    private volatile boolean beltBFlipped;
    @Nullable
    private volatile ServerTickWatch watch;
    /** Server thread: the ids of the orders of the acts, by name. */
    private final Map<String, UUID> orders = new LinkedHashMap<>();
    @Nullable
    private Item lastFilter;
    /** Station A's selection at the end of the last watched tick: a set-back is a change back to an unchanged one. */
    @Nullable
    private Optional<ItemKey> lastSelection;
    private int filterChanges;
    private int setBacks;
    private int setBacksAtHandChange;
    private volatile int trimmedTo = -1;
    private final Map<UUID, Long> queuedSince = new HashMap<>();
    private final Map<UUID, Long> longestQueue = new HashMap<>();
    /**
     * Server thread: every order as the watch last saw it. The controller prunes a finished order after a while, and the
     * held stairs order may finish under the hold long before the act looks at it again.
     */
    private final Map<UUID, ProductionOrder<ItemKey, RackPosition>> seenOrders = new HashMap<>();
    private volatile String serverStatus = "";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    // =================================================================================================================
    // setup
    // =================================================================================================================

    @Override
    public void setup(VisualScript script) {
        script.client("selector: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "selector run"))
                .server("selector: build the warehouse, the saw line and the basin line", this::build)
                .server("selector: the player flies, wears goggles and carries two Recipe Selectors", this::equip)
                .serverUntil("selector: wait until the warehouse is indexed and every machine runs the right way",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("selector: the config of this world", this::configure);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            worldShot(script, HALL_VIEW, "selector-hall");
            linkAndPlace(script);
            patterns(script);
            sawOrders(script);
            handChange(script);
            basinOrders(script);
            cancelAct(script);
            quarantineAct(script);
            goggles(script);
            floor(script);
            return;
        }
        worldShot(script, SAW_VIEW, "selector-saw-end");
        script.server("selector: every check passed", (server, context) -> {
            watch().check();
            long longest = longestQueue.values().stream().mapToLong(Long::longValue).max().orElse(0L);
            if (longest <= DEADLINE_TICKS)
                throw new VisualTestException("no order was queued longer than the deadline of " + DEADLINE_TICKS
                        + " ticks (longest " + longest + "), so a running deadline was never shown to be paused");
            LOGGER.info(PREFIX + "selector: CHECK PASS the longest queue lasted {} ticks, past the deadline of {}, and "
                    + "no order timed out; set-backs under an unchanged selection {}", longest, DEADLINE_TICKS, setBacks);
            LOGGER.info(PREFIX + "selector: deviations from the design: {}", deviations.isEmpty() ? "none" : deviations);
            LOGGER.info(PREFIX + "selector: ALL CHECKS PASSED in English and German (two selectors linked, placed and "
                    + "bound with sneak-clicks, the Mixer refused, five patterns written and the wrong slab pattern "
                    + "drawn red, stairs, slabs and walls back to back at one saw, a save and rejoin mid order, a hand "
                    + "change set back, a List Filter left alone, gray then light gray at one Basin, a cancelled order "
                    + "trimmed, a quarantined slab taken out and the hold released, a queued order held past its "
                    + "deadline, the filter never changed under an item, no other stonecutting result, no order timed "
                    + "out, every census exact)");
        });
    }

    @Override
    public void finish(VisualScript script) {
        script.server("selector: put the config back and stop the watch", (server, context) -> {
            if (timeoutBefore >= 0)
                VisualConfig.setConfig(WareworksConfig.SERVER.productionOrderTimeoutTicks, timeoutBefore);
            ServerTickWatch running = watch;
            if (running != null)
                running.stop();
        });
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder(serverStatus);
        Screen open = context.minecraft().screen;
        status.append(" screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected());
        return status + GoggleShots.describeHover(context);
    }

    private void configure(MinecraftServer server, VisualContext context) {
        timeoutBefore = WareworksConfig.SERVER.productionOrderTimeoutTicks.get();
        VisualConfig.setConfig(WareworksConfig.SERVER.productionOrderTimeoutTicks, DEADLINE_TICKS);
        // Every other result the saw could cut from cobbled deepslate: the fixed list OTHER_CUTS (vanilla 1.21.1).
        Set<ItemKey> others = new LinkedHashSet<>(OTHER_CUTS);
        others.removeAll(List.of(STAIRS, SLAB, WALL));
        otherResults = Set.copyOf(others);
        LOGGER.info(PREFIX + "selector: CHECK PASS config: productionOrderTimeoutTicks {} -> {} for this world; the "
                + "fixed list OTHER_CUTS names {} other results a saw cuts from cobbled deepslate: {}", timeoutBefore,
                DEADLINE_TICKS,
                others.size(), others);
        deviations.add("the saw line is the showcase's proven sawmill (a hopper under the station onto a belt, the saw "
                + "pushing straight into Input A) instead of funnel, belt, funnel");
        deviations.add("a hopper over Input A takes the slab the player throws (a Warehouse Input has no screen)");
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        origin = dock;
        sceneBox = AABB.encapsulatingFullBlocks(dock.offset(SCENE_MIN), dock.offset(SCENE_MAX));

        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.offset(CONTROLLER),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        Map<RackPosition, BlockPos> wanted = Map.of(TERMINAL_RACK, TERMINAL, INPUT_A_RACK, INPUT_A, STATION_A_RACK,
                STATION_A, STATION_B_RACK, STATION_B, INPUT_B_RACK, INPUT_B);
        for (Map.Entry<RackPosition, BlockPos> entry : wanted.entrySet())
            if (!layout.rackPos(entry.getKey()).equals(dock.offset(entry.getValue())))
                throw new VisualTestException("rack position " + entry.getKey() + " stands at "
                        + layout.rackPos(entry.getKey()) + ", not at " + dock.offset(entry.getValue()));
        level.setBlockAndUpdate(dock.offset(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL_RACK)));
        for (RackPosition station : List.of(STATION_A_RACK, STATION_B_RACK))
            level.setBlockAndUpdate(layout.rackPos(station), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                    .setValue(WarehouseProductionBlock.FACING, inward(layout, station)));
        for (RackPosition input : List.of(INPUT_A_RACK, INPUT_B_RACK))
            level.setBlockAndUpdate(layout.rackPos(input), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                    .setValue(WarehouseInputBlock.FACING, inward(layout, input)));
        for (RackPosition rack : STORAGE) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(layout.rackPos(rack).relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        for (int left = COBBLED_STOCK; left > 0; left -= 64)
            insert(level, dock.offset(COBBLED_BARREL), COBBLED.toStack(Math.min(64, left)));
        insert(level, dock.offset(DYE_BARREL), BLACK.toStack(BLACK_STOCK));
        insert(level, dock.offset(DYE_BARREL), WHITE.toStack(WHITE_STOCK));

        // Station A's saw line: the showcase's sawmill.
        level.setBlockAndUpdate(dock.offset(HOPPER_A),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.SOUTH));
        belt(level, dock.offset(BELT_A_START), dock.offset(BELT_A_END), "the saw's feed belt");
        level.setBlockAndUpdate(dock.offset(BELT_A_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.NORTH));
        motor(level, dock.offset(BELT_A_MOTOR)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(dock.offset(SAW), AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        level.setBlockAndUpdate(dock.offset(SAW_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.EAST));
        motor(level, dock.offset(SAW_MOTOR)).generatedSpeed.setValue(SAW_RPM);
        level.setBlockAndUpdate(dock.offset(QUARANTINE_HOPPER),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));

        // Station B's basin line.
        level.setBlockAndUpdate(dock.offset(HOPPER_B1),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(HOPPER_B2),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.SOUTH));
        belt(level, dock.offset(BELT_B_START), dock.offset(BELT_B_END), "the basin's belt");
        level.setBlockAndUpdate(dock.offset(BELT_B_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.WEST));
        motor(level, dock.offset(BELT_B_MOTOR)).generatedSpeed.setValue(BELT_RPM);
        RequesterVisualScenario.beltFunnel(level, dock.offset(FUNNEL_B), Direction.SOUTH, false);
        level.setBlockAndUpdate(dock.offset(BASIN), AllBlocks.BASIN.getDefaultState()
                .setValue(BasinBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(dock.offset(MIXER), AllBlocks.MECHANICAL_MIXER.getDefaultState());
        level.setBlockAndUpdate(dock.offset(COG), AllBlocks.COGWHEEL.getDefaultState());
        level.setBlockAndUpdate(dock.offset(MIXER_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.DOWN));
        motor(level, dock.offset(MIXER_MOTOR)).generatedSpeed.setValue(MIXER_RPM);
    }

    private static void belt(ServerLevel level, BlockPos start, BlockPos end, String what) {
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException(what + " was not created between " + start + " and " + end);
    }

    private void equip(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos lift = context.origin().offset(LIFT);
        player.teleportTo(server.overworld(), lift.getX() + 0.5, lift.getY(), lift.getZ() + 0.5, player.getYRot(),
                player.getXRot());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(SLOT_SELECTORS, WareworksBlocks.RECIPE_SELECTOR.asStack(2));
        selectEmpty(player);
    }

    private static void selectEmpty(ServerPlayer player) {
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    /** Replaces the hotbar (the selected slot stays the empty one) with {@code items}, by slot. */
    private static void hotbar(VisualScript script, String what,
            java.util.function.Supplier<Map<Integer, ItemStack>> lazyItems) {
        Map<Integer, ItemStack> items = new HashMap<>();
        script.server("selector: the hotbar holds " + what, (server, context) -> {
            items.clear();
            items.putAll(lazyItems.get());
            ServerPlayer player = context.serverPlayer(server);
            for (int slot = 0; slot < player.getInventory().items.size(); slot++)
                player.getInventory().items.set(slot, ItemStack.EMPTY);
            items.forEach((slot, stack) -> player.getInventory().setItem(slot, stack.copy()));
            selectEmpty(player);
        }).until("selector: the client has the hotbar of " + what, context -> {
            var inventory = context.minecraft().player.getInventory();
            return items.entrySet().stream().allMatch(entry -> ItemStack.isSameItemSameComponents(
                    inventory.getItem(entry.getKey()), entry.getValue()));
        }, SYNC_TIMEOUT_TICKS);
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!SceneItemCensus.isFullyLoaded(level, sceneBox))
            return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        if (!(controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && controller.inputStations().size() == 2 && controller.productionStations().size() == 2
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.countOf(COBBLED) == COBBLED_STOCK && controller.countOf(WHITE) == WHITE_STOCK))
            return false;
        SawBlockEntity saw = saw(level);
        if (saw.getSpeed() == 0)
            return false;
        Vec3 movement = saw.getItemMovementVec();
        if (Math.round(movement.z) != -1) {
            if (sawFlipped)
                throw new VisualTestException("the saw pushes along " + movement + " with either direction");
            sawFlipped = true;
            CreativeMotorBlockEntity motor = motor(level, dock.offset(SAW_MOTOR));
            motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
            return false;
        }
        if (!beltMoves(level, dock.offset(BELT_A_START), Direction.WEST, dock.offset(BELT_A_MOTOR), true)
                || !beltMoves(level, dock.offset(BELT_B_START), Direction.NORTH, dock.offset(BELT_B_MOTOR), false))
            return false;
        if (!(level.getBlockEntity(dock.offset(MIXER)) instanceof KineticBlockEntity mixer) || mixer.getSpeed() == 0)
            return false;
        if (level.getBlockState(dock.offset(BASIN)).getValue(BasinBlock.FACING) != Direction.EAST)
            throw new VisualTestException("the basin's spout faces "
                    + level.getBlockState(dock.offset(BASIN)).getValue(BasinBlock.FACING) + ", not onto its belt");
        LOGGER.info(PREFIX + "selector: scene ready: saw pushes towards Input A (motor flipped {}), belts move WEST and "
                + "NORTH (flipped {} {}), the mixer turns, the basin's spout faces its belt", sawFlipped, beltAFlipped,
                beltBFlipped);
        return true;
    }

    private boolean beltMoves(ServerLevel level, BlockPos start, Direction wanted, BlockPos motorPos, boolean first) {
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, start);
        if (belt == null || belt.getSpeed() == 0)
            return false;
        if (belt.getMovementFacing() == wanted)
            return true;
        if (first ? beltAFlipped : beltBFlipped)
            throw new VisualTestException("the belt at " + start + " moves " + belt.getMovementFacing() + " either way");
        if (first)
            beltAFlipped = true;
        else
            beltBFlipped = true;
        CreativeMotorBlockEntity motor = motor(level, motorPos);
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
        return false;
    }

    // =================================================================================================================
    // act 1: link, place, bind
    // =================================================================================================================

    private void linkAndPlace(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_SELECTORS, "the Recipe Selectors",
                stack -> stack.is(WareworksBlocks.RECIPE_SELECTOR.asItem()));
        // Station A: link, place on the saw's outer side, bind Input A.
        sneakOnAisle(script, "station A", STATION_A);
        actionBar(script, "the link to station A", context -> message(WareworksLang.SELECTOR_LINKED_STATION, STATION_A));
        RealClicks.sneakClick(script, NAME, "the saw's south face", SAW, Direction.SOUTH, new Vec3(0.5, 0.6, 1.0),
                new Vec3(SAW.getX() + 0.5, 0.0, SAW.getZ() + 3.0));
        actionBar(script, "selector A placed", context -> message(WareworksLang.SELECTOR_PLACED, STATION_A));
        script.server("selector: CHECK selector A stands on the saw and station A makes one recipe at a time",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    RecipeSelectorBlockEntity selector = selector(level, SELECTOR_A);
                    WarehouseProductionBlockEntity station = station(level, STATION_A);
                    if (!selector.linkedStation().equals(Optional.of(at(STATION_A)))
                            || !selector.machinePos().equals(at(SAW)) || station.selectorMode() != SelectorMode.LOCAL
                            || !station.linkedSelector().equals(Optional.of(at(SELECTOR_A))))
                        throw new VisualTestException("selector A is linked to " + selector.linkedStation()
                                + " facing " + selector.machinePos() + ", station A is " + station.selectorMode()
                                + " with " + station.linkedSelector());
                    LOGGER.info(PREFIX + "selector: CHECK PASS a sneak-click linked the selector to station A and a "
                            + "sneak-click on the saw placed it: station A is LOCAL, kind {}", station.selectorMachineKind());
                });
        worldShot(script, SAW_VIEW, "selector-link");
        sneakOnAisle(script, "Input A", INPUT_A);
        actionBar(script, "Input A bound", context -> message(WareworksLang.SELECTOR_BOUND_RETURN_INPUT, STATION_A));
        script.server("selector: CHECK Input A is station A's return input", (server, context) -> {
            if (!station(server.overworld(), STATION_A).selectorReturnInput().equals(Optional.of(at(INPUT_A))))
                throw new VisualTestException("station A returns through " + station(server.overworld(), STATION_A)
                        .selectorReturnInput());
            LOGGER.info(PREFIX + "selector: CHECK PASS a sneak-click bound Input A as station A's return input");
        });
        worldShot(script, HALL_VIEW, "selector-bind");

        // Station B: link, refused on the Mixer, placed on the Basin, bind Input B.
        sneakOnAisle(script, "station B", STATION_B);
        actionBar(script, "the link to station B", context -> message(WareworksLang.SELECTOR_LINKED_STATION, STATION_B));
        Vec3 basinStand = new Vec3(BASIN.getX() + 0.5, 0.0, BASIN.getZ() + 3.0);
        RealClicks.sneakClick(script, NAME, "the Mixer's south face", MIXER, Direction.SOUTH, new Vec3(0.5, 0.8, 1.0),
                basinStand);
        actionBar(script, "the refusal on the Mixer", context -> WareworksLang.translateDirect(
                WareworksLang.SELECTOR_PLACE_ON_BASIN, AllBlocks.MECHANICAL_MIXER.get().getName()));
        script.server("selector: CHECK nothing was placed on the Mixer", (server, context) -> {
            if (!server.overworld().getBlockState(at(MIXER.south())).isAir())
                throw new VisualTestException("a block stands on the Mixer: " + server.overworld()
                        .getBlockState(at(MIXER.south())));
            LOGGER.info(PREFIX + "selector: CHECK PASS a sneak-click on the Mixer placed nothing and said to place it on "
                    + "the Basin");
        });
        RealClicks.sneakClick(script, NAME, "the Basin's south face", BASIN, Direction.SOUTH, new Vec3(0.5, 0.8, 1.0),
                basinStand);
        actionBar(script, "selector B placed", context -> message(WareworksLang.SELECTOR_PLACED, STATION_B));
        sneakOnAisle(script, "Input B", INPUT_B);
        actionBar(script, "Input B bound", context -> message(WareworksLang.SELECTOR_BOUND_RETURN_INPUT, STATION_B));
        script.server("selector: CHECK selector B and Input B", (server, context) -> {
            ServerLevel level = server.overworld();
            WarehouseProductionBlockEntity station = station(level, STATION_B);
            RecipeSelectorBlockEntity selector = selector(level, SELECTOR_B);
            if (station.selectorMode() != SelectorMode.LOCAL || !selector.machinePos().equals(at(BASIN))
                    || !station.selectorReturnInput().equals(Optional.of(at(INPUT_B))))
                throw new VisualTestException("station B is " + station.selectorMode() + " returning through "
                        + station.selectorReturnInput() + ", selector B faces " + selector.machinePos());
            LOGGER.info(PREFIX + "selector: CHECK PASS selector B placed on the Basin (kind {}), Input B bound",
                    station.selectorMachineKind());
        });
    }

    private void sneakOnAisle(VisualScript script, String what, BlockPos block) {
        RealClicks.sneakClick(script, NAME, what, block, Direction.NORTH, new Vec3(0.5, 0.8, 0.0),
                new Vec3(block.getX() + 0.5, RAIL_TOP, AISLE_STAND_Z));
    }

    /** A selector message naming the block at {@code relative} the way the selector's messages do: "x, y, z". */
    private Component message(String key, BlockPos relative) {
        BlockPos pos = origin.offset(relative);
        return WareworksLang.translateDirect(key, Component.literal(pos.getX() + ", " + pos.getY() + ", "
                + pos.getZ()));
    }

    private void actionBar(VisualScript script, String what, java.util.function.Function<VisualContext, Component> text) {
        RequesterVisualScenario.actionBar(script, what, text);
    }

    // =================================================================================================================
    // act 2: patterns
    // =================================================================================================================

    private void patterns(VisualScript script) {
        hotbar(script, "the saw pattern items", () -> Map.of(SLOT_A, COBBLED.toStack(1), SLOT_B, STAIRS.toStack(1),
                SLOT_C, SLAB.toStack(1), SLOT_D, WALL.toStack(1)));
        openStation(script, "station A", STATION_A);
        FeederVisualScenario.patternEntry(script, 0, SLOT_A, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 0, SLOT_C, Items.DEEPSLATE_TILE_SLAB, ProductionMenuLayout.RESULT_CELL);
        ArmVisualScenario.untilOrFail(script, "selector: the selector check refuses 1 cobbled deepslate -> 1 slab",
                context -> productionScreen(context).isRed(0) && check(context, 0)
                        .filter(verdict -> verdict != SelectorRecipeCheck.Verdict.VALID).isPresent(),
                SCREEN_TIMEOUT_TICKS, context -> "pattern 0 is red=" + productionScreen(context).isRed(0)
                        + " check " + check(context, 0));
        GoggleShots.reach(script, NAME, 0.0);
        script.client("selector: rest the cursor on the result cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionCell(productionScreen(context), ProductionMenuLayout.RESULT_CELL)))
                .waitTicks(SETTLE_TICKS).shotWithGui("selector-pattern-refused");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.client("selector: CHECK the wrong slab pattern", context -> LOGGER.info(PREFIX
                + "selector: CHECK PASS the pattern 1 cobbled deepslate -> 1 slab is drawn red, check {}",
                check(context, 0)));
        FeederVisualScenario.scrollResult(script, 0, 2);
        ArmVisualScenario.untilOrFail(script, "selector: 1 cobbled deepslate -> 2 slabs passes the check",
                context -> !productionScreen(context).isRed(0)
                        && check(context, 0).equals(Optional.of(SelectorRecipeCheck.Verdict.VALID)),
                SCREEN_TIMEOUT_TICKS, context -> "check " + check(context, 0));
        tab(script, 1);
        FeederVisualScenario.patternEntry(script, 1, SLOT_A, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 1, SLOT_B, Items.DEEPSLATE_TILE_STAIRS, ProductionMenuLayout.RESULT_CELL);
        tab(script, 2);
        FeederVisualScenario.patternEntry(script, 2, SLOT_A, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 2, SLOT_D, Items.DEEPSLATE_TILE_WALL, ProductionMenuLayout.RESULT_CELL);
        ArmVisualScenario.untilOrFail(script, "selector: all three saw patterns pass the check",
                context -> List.of(0, 1, 2).stream().allMatch(slot -> !productionScreen(context).isRed(slot)
                        && check(context, slot).equals(Optional.of(SelectorRecipeCheck.Verdict.VALID))),
                SCREEN_TIMEOUT_TICKS, context -> "checks " + productionScreen(context).state().selectorChecks());
        GoggleShots.reach(script, NAME, 0.0);
        script.client("selector: rest the cursor beside the window", context -> ScreenInput.hover(context.minecraft(),
                new ScreenInput.Point(4.0, 4.0))).waitTicks(SETTLE_TICKS).shotWithGui("selector-patterns");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        RequesterVisualScenario.closeScreen(script, "station A's screen");

        hotbar(script, "the dye pattern items", () -> Map.of(SLOT_A, BLACK.toStack(1), SLOT_B, WHITE.toStack(1),
                SLOT_C, GRAY.toStack(1), SLOT_D, LIGHT_GRAY.toStack(1)));
        openStation(script, "station B", STATION_B);
        FeederVisualScenario.patternEntry(script, 0, SLOT_A, Items.BLACK_DYE, 0);
        FeederVisualScenario.patternEntry(script, 0, SLOT_B, Items.WHITE_DYE, 1);
        FeederVisualScenario.patternEntry(script, 0, SLOT_C, Items.GRAY_DYE, ProductionMenuLayout.RESULT_CELL);
        FeederVisualScenario.scrollResult(script, 0, 2);
        tab(script, 1);
        FeederVisualScenario.patternEntry(script, 1, SLOT_A, Items.BLACK_DYE, 0);
        FeederVisualScenario.patternEntry(script, 1, SLOT_B, Items.WHITE_DYE, 1);
        FeederVisualScenario.patternEntry(script, 1, SLOT_B, Items.WHITE_DYE, 2);
        FeederVisualScenario.patternEntry(script, 1, SLOT_D, Items.LIGHT_GRAY_DYE, ProductionMenuLayout.RESULT_CELL);
        FeederVisualScenario.scrollResult(script, 1, 3);
        ArmVisualScenario.untilOrFail(script, "selector: both dye patterns pass the check",
                context -> List.of(0, 1).stream().allMatch(slot -> !productionScreen(context).isRed(slot)
                        && check(context, slot).equals(Optional.of(SelectorRecipeCheck.Verdict.VALID))),
                SCREEN_TIMEOUT_TICKS, context -> "checks " + productionScreen(context).state().selectorChecks());
        RequesterVisualScenario.closeScreen(script, "station B's screen");
        hotbar(script, "the hand-change items and a slab", () -> Map.of(SLOT_HAND_ITEM, new ItemStack(Items.POLISHED_DEEPSLATE),
                SLOT_LIST_FILTER, AllItems.FILTER.asStack(), SLOT_THROWN_SLAB, SLAB.toStack(1)));
        script.server("selector: CHECK the patterns and take the baseline census", (server, context) -> {
            ServerLevel level = server.overworld();
            WarehouseControllerBlockEntity controller = controller(level);
            if (!controller.producibleKeys().containsAll(List.of(STAIRS, SLAB, WALL, GRAY, LIGHT_GRAY)))
                throw new VisualTestException("the aisle makes " + controller.producibleKeys());
            for (int slot = 0; slot < 3; slot++)
                if (!station(level, STATION_A).selectorCheck(slot).equals(Optional.of(SelectorRecipeCheck.Verdict.VALID)))
                    throw new VisualTestException("station A's pattern " + slot + " checks "
                            + station(level, STATION_A).selectorCheck(slot));
            expected = census(server);
            if (expected.getOrDefault(COBBLED, 0L) != COBBLED_STOCK)
                throw new VisualTestException("the baseline holds " + SceneItemCensus.describe(expected));
            LOGGER.info(PREFIX + "selector: CHECK PASS five patterns written with clicks, every one VALID; baseline {}",
                    SceneItemCensus.describe(expected));
            watch = ServerTickWatch.start(NAME, this::watchTick);
        });
    }

    private static Optional<SelectorRecipeCheck.Verdict> check(VisualContext context, int slot) {
        List<Optional<SelectorRecipeCheck.Verdict>> checks = productionScreen(context).state().selectorChecks();
        return slot < checks.size() ? checks.get(slot) : Optional.empty();
    }

    private static void tab(VisualScript script, int slot) {
        script.client("selector: select pattern " + slot, context -> ScreenInput.click(context.minecraft(),
                ScreenInput.productionTab(productionScreen(context), slot))).waitTicks(SETTLE_TICKS);
    }

    private void openStation(VisualScript script, String what, BlockPos station) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click(what, station, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "selector: " + what + "'s screen is open",
                context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                        && screen.getMenu().getStateId() > 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    private static WarehouseProductionScreen productionScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open: " + context.minecraft().screen);
    }

    // =================================================================================================================
    // act 3: stairs, slabs, walls back to back at one saw; a save and rejoin mid order
    // =================================================================================================================

    private void sawOrders(VisualScript script) {
        openTerminal(script);
        script.client("selector: ask for " + ORDER + " per click", context -> TerminalOrder.scrollAmount(context, NAME,
                ORDER)).waitTicks(SETTLE_TICKS);
        clickOrder(script, "stairs", STAIRS);
        clickOrder(script, "slabs", SLAB);
        clickOrder(script, "walls", WALL);
        script.serverUntil("selector: the slab and wall orders wait for their turn", (server, context) -> {
            watch().check();
            WarehouseControllerBlockEntity controller = controller(server.overworld());
            return controller.waitsForSelector(order(server, "slabs")) == SelectorWait.BEHIND
                    && controller.waitsForSelector(order(server, "walls")) == SelectorWait.BEHIND;
        }, SYNC_TIMEOUT_TICKS);
        script.waitTicks(SETTLE_TICKS * 3).client("selector: rest the cursor on the title", context -> {
            WarehouseTerminalScreen terminal = TerminalOrder.terminalScreen(context);
            ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + 30.0,
                    terminal.getGuiTop() + 5.0));
        }).waitTicks(SETTLE_TICKS).shotWithGui("selector-waiting");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.server("selector: CHECK the three orders queue at station A", (server, context) -> {
            WarehouseControllerBlockEntity controller = controller(server.overworld());
            SelectorWait slabs = controller.waitsForSelector(order(server, "slabs"));
            SelectorWait walls = controller.waitsForSelector(order(server, "walls"));
            if (slabs != SelectorWait.BEHIND || walls != SelectorWait.BEHIND)
                throw new VisualTestException("after the shot the slabs wait " + slabs + ", the walls " + walls);
            LOGGER.info(PREFIX + "selector: CHECK PASS three clicks made three orders at station A; slabs and walls "
                    + "wait BEHIND the stairs ({}, {})", slabs, walls);
        });
        frozenSaw(script, "stairs", STAIRS, "selector-saw-stairs");
        frozenSaw(script, "slabs", SLAB, "selector-switch-slabs");
        frozenSaw(script, "walls", WALL, "selector-switch-walls");
        // A real save, quit and rejoin while the walls are made.
        script.server("selector: pause the per-tick watch for the rejoin", (server, context) -> {
            watch().check();
            watch().pause(true);
        });
        VisualWorld.reload(script, worldProfile());
        script.server("selector: CHECK the station, the selector and the walls order came back", (server, context) -> {
            ServerLevel level = server.overworld();
            WarehouseProductionBlockEntity station = station(level, STATION_A);
            ProductionOrder<ItemKey, RackPosition> walls = order(server, "walls");
            if (station.selectorMode() != SelectorMode.LOCAL || !station.linkedSelector().equals(Optional.of(at(SELECTOR_A)))
                    || !station.selectorReturnInput().equals(Optional.of(at(INPUT_A)))
                    || !station.selection().equals(Optional.of(WALL)) || !walls.selectorBound())
                throw new VisualTestException("after the rejoin station A is " + station.selectorMode() + " linked to "
                        + station.linkedSelector() + " returning through " + station.selectorReturnInput()
                        + " set to " + station.selection() + "; the walls order is " + walls);
            lastFilter = null;
            lastSelection = null;
            watch().pause(false);
            LOGGER.info(PREFIX + "selector: CHECK PASS after a save, quit and rejoin station A is LOCAL, linked, set to "
                    + "walls, the walls order {} open and bound ({} of {})", walls.state(), walls.produced(), ORDER);
        });
        script.serverUntil("selector: the three orders are complete and the line is empty",
                (server, context) -> complete(server, "stairs", "slabs", "walls") && quiet(server), STEP_TIMEOUT_TICKS);
        script.server("selector: CHECK the saw made exactly the three orders", (server, context) -> {
            WarehouseControllerBlockEntity controller = controller(server.overworld());
            for (String name : List.of("stairs", "slabs", "walls"))
                if (order(server, name).produced() != ORDER)
                    throw new VisualTestException("the " + name + " order produced " + order(server, name).produced());
            change(Map.of(COBBLED, -(long) (ORDER + ORDER / 2 + ORDER), STAIRS, (long) ORDER, SLAB, (long) ORDER, WALL,
                    (long) ORDER));
            assertCensus(server, "stairs, slabs and walls");
            var delivered = terminal(server.overworld()).bufferedItems();
            if (delivered.count(STAIRS) != ORDER || delivered.count(SLAB) != ORDER || delivered.count(WALL) != ORDER)
                throw new VisualTestException("the terminal holds " + delivered);
            LOGGER.info(PREFIX + "selector: CHECK PASS {} stairs, {} slabs, {} walls delivered into the terminal; the "
                    + "filter changed {} times, never under an item; none of the other results in stock {}",
                    delivered.count(STAIRS), delivered.count(SLAB), delivered.count(WALL), filterChanges,
                    otherResultsInStock(controller));
        });
    }

    private void openTerminal(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click("the terminal", TERMINAL, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "selector: the terminal screen is open with its stock",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                        && terminal.entry(STAIRS).isPresent() && terminal.entry(GRAY).isPresent(),
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    /** One stock click on {@code key} with the screen open; the new order is remembered under {@code name}. */
    private void clickOrder(VisualScript script, String name, ItemKey key) {
        int[] before = new int[1];
        script.server("selector: count the orders before the " + name + " click",
                (server, context) -> before[0] = controller(server.overworld()).productionOrders().size());
        script.client("selector: click " + name, context -> TerminalOrder.clickCell(context, NAME, key));
        script.serverUntil("selector: the " + name + " order exists", (server, context) -> {
            for (ProductionOrder<ItemKey, RackPosition> order : controller(server.overworld()).productionOrders())
                if (order.result().equals(key) && order.isOpen() && !orders.containsValue(order.id())) {
                    orders.put(name, order.id());
                    return true;
                }
            return false;
        }, SYNC_TIMEOUT_TICKS);
        script.server("selector: CHECK the " + name + " order", (server, context) -> {
            ProductionOrder<ItemKey, RackPosition> order = order(server, name);
            if (controller(server.overworld()).productionOrders().size() != before[0] + 1 || !order.selectorBound())
                throw new VisualTestException("the " + name + " click made " + order + " (orders before: " + before[0]
                        + ")");
            LOGGER.info(PREFIX + "selector: CHECK PASS one click made the {} order {} x{} at {}, bound to its line",
                    name, order.id(), order.resultAmount(), order.station());
        });
        script.waitTicks(SETTLE_TICKS);
    }

    /** Freezes the moment the saw cuts for {@code name} and shoots its value box, which shows the selection. */
    private void frozenSaw(VisualScript script, String name, ItemKey result, String label) {
        script.serverUntil("selector: the saw cuts for the " + name + " order", (server, context) -> {
            watch().check();
            SawBlockEntity saw = saw(server.overworld());
            return !saw.inventory.isEmpty() && sawFilter(saw) == result.getItem();
        }, STEP_TIMEOUT_TICKS).freeze(true);
        worldShot(script, SAW_VIEW, label);
        script.freeze(false).server("selector: CHECK the " + name + " switch", (server, context) -> {
            WarehouseProductionBlockEntity station = station(server.overworld(), STATION_A);
            LOGGER.info(PREFIX + "selector: CHECK PASS the saw cuts for the {} order with its filter on {} (epoch {}, "
                    + "switches so far {})", name, result, station.selectorEpoch(), filterChanges);
        });
    }

    // =================================================================================================================
    // act 4: a hand change set back; a List Filter left alone
    // =================================================================================================================

    private void handChange(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_HAND_ITEM, "polished deepslate",
                stack -> stack.is(Items.POLISHED_DEEPSLATE));
        script.server("selector: remember the set-backs before the hand change",
                (server, context) -> setBacksAtHandChange = selector(server.overworld(), SELECTOR_A).setBackCount());
        clickSawFilter(script, "with polished deepslate");
        script.serverUntil("selector: the selector set the saw back to walls", (server, context) -> {
            watch().check();
            RecipeSelectorBlockEntity selector = selector(server.overworld(), SELECTOR_A);
            return selector.setBackCount() > setBacksAtHandChange && sawFilter(saw(server.overworld())) == Items.DEEPSLATE_TILE_WALL;
        }, SYNC_TIMEOUT_TICKS).server("selector: CHECK the set-back", (server, context) -> LOGGER.info(PREFIX
                + "selector: CHECK PASS a click with polished deepslate on the saw's value box was set back to walls "
                + "(set-backs {} -> {})", setBacksAtHandChange, selector(server.overworld(), SELECTOR_A).setBackCount()));
        worldShot(script, SAW_VIEW, "selector-set-back");

        RequesterVisualScenario.selectHotbar(script, SLOT_LIST_FILTER, "the List Filter", AllItems.FILTER::isIn);
        clickSawFilter(script, "with a List Filter");
        script.serverUntil("selector: the List Filter sits in the saw and the selector leaves it", (server, context) -> {
            watch().check();
            return AllItems.FILTER.isIn(sawFilterStack(saw(server.overworld())))
                    && selector(server.overworld(), SELECTOR_A).status() == RecipeSelectorStatus.FILTER_ITEM;
        }, SYNC_TIMEOUT_TICKS);
        script.waitTicks(20).server("selector: CHECK the List Filter is never overwritten", (server, context) -> {
            if (!AllItems.FILTER.isIn(sawFilterStack(saw(server.overworld()))))
                throw new VisualTestException("the List Filter was replaced by " + sawFilterStack(saw(server.overworld())));
            LOGGER.info(PREFIX + "selector: CHECK PASS a List Filter clicked into the saw stays there; the selector shows "
                    + "{}", selector(server.overworld(), SELECTOR_A).status());
        });
        worldShot(script, SAW_VIEW, "selector-filter-item");
        RequesterVisualScenario.takeAnEmptyHand(script);
        clickSawFilter(script, "with an empty hand");
        script.serverUntil("selector: the List Filter is out and the saw is set to walls again", (server, context) -> {
            watch().check();
            return sawFilter(saw(server.overworld())) == Items.DEEPSLATE_TILE_WALL;
        }, SYNC_TIMEOUT_TICKS).server("selector: CHECK the List Filter came out", (server, context) -> {
            assertCensus(server, "the hand change and the List Filter");
            LOGGER.info(PREFIX + "selector: CHECK PASS an empty-hand click took the List Filter out and the saw is set "
                    + "to walls again; status {}", selector(server.overworld(), SELECTOR_A).status());
        });
    }

    /** A right-click on the saw's filter slot, checked with Create's own hit test of its value box. */
    private void clickSawFilter(VisualScript script, String what) {
        String label = "the saw's value box " + what;
        RealClicks.fly(script, NAME);
        script.camera(label, context -> {
            ClientLevel level = Objects.requireNonNull(context.minecraft().level, "level");
            BlockPos pos = context.origin().offset(SAW);
            FilteringBehaviour filter = BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE);
            Vec3 slot = Objects.requireNonNull(filter, "saw filter").getSlotPositioning()
                    .getLocalOffset(level, pos, level.getBlockState(pos));
            Vec3 aim = Vec3.atLowerCornerOf(SAW).add(slot.x, 0.75, slot.z);
            return new CameraView(label, aim.add(0.0, 2.0, 2.2), aim);
        });
        ArmVisualScenario.untilOrFail(script, "selector: the crosshair is in the saw's filter slot",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(context.origin().offset(SAW))
                        && inSawSlot(context, hit), RealClicks.AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context));
        script.serverUntil("selector: the server has the player's answer to every teleport",
                (server, context) -> RealClicks.teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        RequesterVisualScenario.useKey(script, label);
        script.waitTicks(SETTLE_TICKS);
    }

    private static boolean inSawSlot(VisualContext context, BlockHitResult hit) {
        ClientLevel level = Objects.requireNonNull(context.minecraft().level, "level");
        FilteringBehaviour filter = BlockEntityBehaviour.get(level, hit.getBlockPos(), FilteringBehaviour.TYPE);
        return filter != null && filter.getSlotPositioning().testHit(level, hit.getBlockPos(),
                level.getBlockState(hit.getBlockPos()), hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos())));
    }

    // =================================================================================================================
    // act 5: gray then light gray at one Basin
    // =================================================================================================================

    private void basinOrders(VisualScript script) {
        openTerminal(script);
        script.client("selector: ask for " + GRAY_ORDER, context -> TerminalOrder.scrollAmount(context, NAME,
                GRAY_ORDER)).waitTicks(SETTLE_TICKS);
        clickOrder(script, "gray", GRAY);
        script.client("selector: ask for " + LIGHT_GRAY_ORDER, context -> TerminalOrder.scrollAmount(context, NAME,
                LIGHT_GRAY_ORDER)).waitTicks(SETTLE_TICKS);
        clickOrder(script, "light gray", LIGHT_GRAY);
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        frozenBasin(script, "gray", GRAY, "selector-basin-gray");
        frozenBasin(script, "light gray", LIGHT_GRAY, "selector-basin-lightgray");
        script.serverUntil("selector: both dye orders are complete and the line is empty",
                (server, context) -> complete(server, "gray", "light gray") && quiet(server), STEP_TIMEOUT_TICKS);
        script.server("selector: CHECK the Basin made exactly the two orders", (server, context) -> {
            long gray = order(server, "gray").produced();
            long lightGray = order(server, "light gray").produced();
            if (gray != GRAY_ORDER || lightGray != LIGHT_GRAY_ORDER)
                throw new VisualTestException("the dye orders produced gray " + gray + ", light gray " + lightGray);
            change(Map.of(BLACK, -(long) (GRAY_ORDER / 2 + LIGHT_GRAY_ORDER / 3), WHITE,
                    -(long) (GRAY_ORDER / 2 + 2 * LIGHT_GRAY_ORDER / 3), GRAY, (long) GRAY_ORDER, LIGHT_GRAY,
                    (long) LIGHT_GRAY_ORDER));
            assertCensus(server, "gray and light gray");
            LOGGER.info(PREFIX + "selector: CHECK PASS one Basin made {} gray, then {} light gray, never light gray "
                    + "while set to gray", gray, lightGray);
        });
    }

    private void frozenBasin(VisualScript script, String name, ItemKey result, String label) {
        script.serverUntil("selector: the Basin mixes for the " + name + " order", (server, context) -> {
            watch().check();
            BasinBlockEntity basin = basin(server.overworld());
            return basin.getFilter().getFilter().is(result.getItem()) && !isEmpty(basin.getInputInventory());
        }, STEP_TIMEOUT_TICKS).freeze(true);
        worldShot(script, BASIN_VIEW, label);
        script.freeze(false);
    }

    // =================================================================================================================
    // act 6: a stairs order cancelled mid-run is trimmed; the slabs follow cleanly
    // =================================================================================================================

    private void cancelAct(VisualScript script) {
        openTerminal(script);
        script.client("selector: ask for " + CANCELLED_ORDER, context -> TerminalOrder.scrollAmount(context, NAME,
                CANCELLED_ORDER)).waitTicks(SETTLE_TICKS);
        clickOrder(script, "cancelled stairs", STAIRS);
        script.client("selector: ask for " + ORDER, context -> TerminalOrder.scrollAmount(context, NAME, ORDER))
                .waitTicks(SETTLE_TICKS);
        clickOrder(script, "slabs after the cancel", SLAB);
        script.serverUntil("selector: the stairs order is in the machine", (server, context) -> {
            watch().check();
            ProductionOrder<ItemKey, RackPosition> stairs = order(server, "cancelled stairs");
            return stairs.engaged();
        }, STEP_TIMEOUT_TICKS);
        script.client("selector: click the stairs order's line, which cancels it", context -> {
            WarehouseTerminalScreen terminal = TerminalOrder.terminalScreen(context);
            UUID id = Objects.requireNonNull(orders.get("cancelled stairs"), "cancelled stairs");
            List<PlanLine> lines = terminal.visibleOrderLines();
            int index = -1;
            for (int line = 0; line < lines.size(); line++)
                if (lines.get(line).head().equals(id))
                    index = line;
            if (index < 0 || lines.get(index).isChain())
                throw new VisualTestException("the terminal shows no single line of the stairs order: " + lines);
            ScreenInput.click(context.minecraft(), ScreenInput.terminalOrderLine(terminal,
                    index + terminal.visibleFillLines().size()));
        });
        script.serverUntil("selector: the cancelled stairs order is trimmed, still open", (server, context) -> {
            watch().check();
            ProductionOrder<ItemKey, RackPosition> stairs = order(server, "cancelled stairs");
            return stairs.isOpen() && (stairs.resultAmount() < CANCELLED_ORDER
                    || stairs.deliveredIngredients() >= CANCELLED_ORDER);
        }, SYNC_TIMEOUT_TICKS).server("selector: CHECK the trim", (server, context) -> {
            ProductionOrder<ItemKey, RackPosition> stairs = order(server, "cancelled stairs");
            trimmedTo = stairs.resultAmount();
            if (stairs.resultAmount() >= CANCELLED_ORDER)
                deviations.add("the whole stairs order was delivered before the cancel arrived, so the trim kept all "
                        + CANCELLED_ORDER);
            LOGGER.info(PREFIX + "selector: CHECK PASS cancelling the engaged stairs order trimmed it to {} (of {}), "
                    + "{} made so far; it stays open until the line is back", stairs.resultAmount(), CANCELLED_ORDER,
                    stairs.produced());
        });
        script.waitTicks(SETTLE_TICKS).client("selector: rest the cursor on the title", context -> {
            WarehouseTerminalScreen terminal = TerminalOrder.terminalScreen(context);
            ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + 30.0,
                    terminal.getGuiTop() + 5.0));
        }).waitTicks(SETTLE_TICKS).shotWithGui("selector-trimmed");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.serverUntil("selector: the trimmed stairs and the slabs are complete and the line is empty",
                (server, context) -> complete(server, "cancelled stairs", "slabs after the cancel") && quiet(server),
                STEP_TIMEOUT_TICKS);
        script.server("selector: CHECK the trimmed order and the slabs", (server, context) -> {
            ProductionOrder<ItemKey, RackPosition> stairs = order(server, "cancelled stairs");
            long made = stairs.produced();
            if (made != stairs.resultAmount() || made != trimmedTo || order(server, "slabs after the cancel")
                    .produced() != ORDER)
                throw new VisualTestException("the trimmed order is " + stairs + ", the slabs "
                        + order(server, "slabs after the cancel"));
            change(Map.of(COBBLED, -(made + ORDER / 2), STAIRS, made, SLAB, (long) ORDER));
            assertCensus(server, "the trimmed order");
            LOGGER.info(PREFIX + "selector: CHECK PASS the trimmed stairs order completed with {} stairs (what was in "
                    + "the line), the slabs followed cleanly", made);
        });
    }

    // =================================================================================================================
    // act 7: a slab thrown into Input A is quarantined, taken out by hand, the hold released
    // =================================================================================================================

    private void quarantineAct(VisualScript script) {
        openTerminal(script);
        script.client("selector: ask for " + ORDER, context -> TerminalOrder.scrollAmount(context, NAME, ORDER))
                .waitTicks(SETTLE_TICKS);
        clickOrder(script, "held stairs", STAIRS);
        // A slab order queued behind the stairs: it waits BEHIND, then HOLD while the station is held, so it stays
        // queued past its deadline below (the stairs themselves may drain and complete under the hold).
        clickOrder(script, "queued slabs", SLAB);
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.serverUntil("selector: the held stairs order is in the machine", (server, context) -> {
            watch().check();
            return order(server, "held stairs").engaged();
        }, STEP_TIMEOUT_TICKS);
        RequesterVisualScenario.selectHotbar(script, SLOT_THROWN_SLAB, "the slab to throw", stack -> stack.is(SLAB.getItem()));
        RealClicks.fly(script, NAME);
        script.camera(CameraView.of("over the hopper", QUARANTINE_HOPPER.getX() + 0.5, QUARANTINE_HOPPER.getY() + 3.0,
                QUARANTINE_HOPPER.getZ() + 0.5, QUARANTINE_HOPPER.getX() + 0.5, QUARANTINE_HOPPER.getY() + 1.0,
                QUARANTINE_HOPPER.getZ() + 0.501));
        script.serverUntil("selector: the server has the player's answer to every teleport",
                (server, context) -> RealClicks.teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        script.client("selector: throw the slab with the drop key",
                context -> KeyMapping.click(context.minecraft().options.keyDrop.getKey()));
        ArmVisualScenario.untilOrFail(script, "selector: the client threw the slab",
                context -> context.minecraft().player.getInventory().getItem(SLOT_THROWN_SLAB).isEmpty(),
                SYNC_TIMEOUT_TICKS, context -> "slot " + SLOT_THROWN_SLAB + " holds "
                        + context.minecraft().player.getInventory().getItem(SLOT_THROWN_SLAB));
        script.serverUntil("selector: the slab came back through Input A and holds station A", (server, context) -> {
            watch().check();
            Optional<SelectorHold> hold = station(server.overworld(), STATION_A).selectorHold();
            return hold.isPresent() && hold.get().cause() == SelectorHold.Cause.UNEXPECTED_RETURN;
        }, QUIET_TIMEOUT_TICKS).server("selector: CHECK the quarantine", (server, context) -> {
            ServerLevel level = server.overworld();
            SelectorHold hold = station(level, STATION_A).selectorHold().orElseThrow();
            if (!hold.unexpected().equals(Optional.of(SLAB)) || input(level, INPUT_A).countOf(SLAB) != 1
                    || controller(level).countOf(SLAB) != 0)
                throw new VisualTestException("the hold is " + hold + ", Input A holds " + input(level, INPUT_A)
                        .countOf(SLAB) + " slabs, the stock " + controller(level).countOf(SLAB));
            LOGGER.info(PREFIX + "selector: CHECK PASS the thrown slab stays in Input A, is never stored, and holds "
                    + "station A ({})", hold.cause());
        });
        openStation(script, "station A", STATION_A);
        ArmVisualScenario.untilOrFail(script, "selector: station A's screen shows the hold",
                context -> productionScreen(context).state().selector().hold().isPresent(), SCREEN_TIMEOUT_TICKS,
                context -> "the selector row is " + productionScreen(context).state().selector());
        GoggleShots.reach(script, NAME, 0.0);
        script.client("selector: rest the cursor on the selector row", context -> {
            WarehouseProductionScreen screen = productionScreen(context);
            ScreenInput.Point point = selectorRowPoint(screen);
            if (!screen.isOverSelectorRow(point.x(), point.y()))
                throw new VisualTestException("the screen's hit test does not put " + point + " on the selector row");
            ScreenInput.hover(context.minecraft(), point);
        }).waitTicks(SETTLE_TICKS).shotWithGui("selector-quarantine");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        RequesterVisualScenario.closeScreen(script, "station A's screen");
        // The hold stops the station, so the queued slab order waits (HOLD) and its deadline must not run: wait until
        // it has been queued for longer than the deadline. The watch fails the run on a timed-out order and on a crane
        // asked for anything while the station holds.
        script.serverUntil("selector: the queued slab order stays queued past its deadline", (server, context) -> {
            watch().check();
            ProductionOrder<ItemKey, RackPosition> slabs = order(server, "queued slabs");
            if (!slabs.isOpen())
                throw new VisualTestException("the queued slab order ended under the hold: " + slabs);
            return longestQueue.getOrDefault(slabs.id(), 0L) > DEADLINE_TICKS + PAST_DEADLINE_TICKS;
        }, DEADLINE_TICKS + PAST_DEADLINE_TICKS + QUIET_TIMEOUT_TICKS).server("selector: CHECK the deadline is paused "
                + "under the hold", (server, context) -> {
            ProductionOrder<ItemKey, RackPosition> slabs = order(server, "queued slabs");
            SelectorWait wait = controller(server.overworld()).waitsForSelector(slabs);
            if (wait != SelectorWait.HOLD || !slabs.isOpen() || slabs.produced() != 0)
                throw new VisualTestException("the queued slab order waits " + wait + " and is " + slabs);
            LOGGER.info(PREFIX + "selector: CHECK PASS the queued slab order has waited ({}) for {} ticks, past its "
                    + "deadline of {}, and is still {}; the held stairs order is {}", wait,
                    longestQueue.get(slabs.id()), DEADLINE_TICKS, slabs.state(), order(server, "held stairs").state());
        });
        // Take it out: an empty-hand click on the input.
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click("Input A", INPUT_A, Direction.NORTH, FACING_INPUT_A));
        actionBar(script, "the slab taken out", context -> WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOK_OUT,
                SLAB.toStack().getHoverName()));
        script.server("selector: CHECK the slab is back in the player's hand", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            if (player.getInventory().countItem(Items.DEEPSLATE_TILE_SLAB) != 1
                    || input(server.overworld(), INPUT_A).countOf(SLAB) != 0)
                throw new VisualTestException("the player has " + player.getInventory().countItem(
                        Items.DEEPSLATE_TILE_SLAB) + " slabs, Input A " + input(server.overworld(), INPUT_A)
                        .countOf(SLAB));
            LOGGER.info(PREFIX + "selector: CHECK PASS an empty-hand click on Input A took the quarantined slab out");
        });
        // Release: a sneak-click on the station with an empty hand.
        sneakOnAisle(script, "station A (release)", STATION_A);
        script.serverUntil("selector: the hold is released", (server, context) -> {
            watch().check();
            return station(server.overworld(), STATION_A).selectorHold().isEmpty();
        }, SYNC_TIMEOUT_TICKS).server("selector: CHECK the release", (server, context) -> LOGGER.info(PREFIX
                + "selector: CHECK PASS a sneak-click on station A released the hold; it goes on with {}",
                station(server.overworld(), STATION_A).selection()));
        worldShot(script, SAW_VIEW, "selector-released");
        script.serverUntil("selector: the held stairs and the queued slabs complete and the line is empty",
                (server, context) -> complete(server, "held stairs", "queued slabs") && quiet(server),
                STEP_TIMEOUT_TICKS);
        script.server("selector: CHECK the end of the quarantine", (server, context) -> {
            if (order(server, "held stairs").produced() != ORDER || order(server, "queued slabs").produced() != ORDER)
                throw new VisualTestException("the held stairs order is " + order(server, "held stairs")
                        + ", the queued slabs " + order(server, "queued slabs"));
            change(Map.of(COBBLED, -(long) (ORDER + ORDER / 2), STAIRS, (long) ORDER, SLAB, (long) ORDER));
            assertCensus(server, "the quarantine");
            LOGGER.info(PREFIX + "selector: CHECK PASS the stairs order completed, the slab order followed after the "
                    + "release; the thrown slab is the player's again");
        });
    }

    // =================================================================================================================
    // goggles and the floor
    // =================================================================================================================

    private void goggles(VisualScript script) {
        script.client("selector: clear the toasts", context -> context.minecraft().getToasts().clear());
        gogglePair(script, "");
        language.switchTo(script, "selector: ", GERMAN);
        gogglePair(script, "-de");
        language.switchTo(script, "selector: ", ENGLISH);
    }

    private void gogglePair(VisualScript script, String suffix) {
        GoggleShots.shot(script, NAME, STATION_A_GOGGLES, "goggles-station" + suffix, dock -> dock.offset(STATION_A),
                context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(STATION_A));
                    String header = WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_SELECTOR_LOCAL)
                            .getString();
                    GoggleShots.requireLine(lines, header);
                    requireTranslated(context, "station header", header);
                    LOGGER.info(PREFIX + "selector: CHECK PASS station A's goggles: {}", lines);
                });
        GoggleShots.shot(script, NAME, SELECTOR_A_GOGGLES, "goggles-selector" + suffix, dock -> dock.offset(SELECTOR_A),
                context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(SELECTOR_A));
                    String header = WareworksLang.translateDirect(WareworksLang.GOGGLES_RECIPE_SELECTOR).getString();
                    GoggleShots.requireLine(lines, header);
                    requireTranslated(context, "selector header", header);
                    LOGGER.info(PREFIX + "selector: CHECK PASS selector A's goggles: {}", lines);
                });
    }

    private void requireTranslated(VisualContext context, String moment, String line) {
        String selected = context.minecraft().getLanguageManager().getSelected();
        if (ENGLISH.equals(selected)) {
            englishLines.put(moment, line);
            return;
        }
        String english = englishLines.get(moment);
        if (english == null || line.isBlank() || line.equals(english))
            throw new VisualTestException("'" + line + "' at '" + moment + "' is no " + selected + " translation of '"
                    + english + "'");
        LOGGER.info(PREFIX + "selector: CHECK PASS '{}' is the {} line of '{}'", line, selected, english);
    }

    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;

    /** Station A's screen at the floor every layout is measured against: 1280 x 960 at GUI scale 4, 320 x 240. */
    private void floor(VisualScript script) {
        openStation(script, "station A", STATION_A);
        script.client("selector: remember the window and the GUI scale", context -> {
            Window window = context.minecraft().getWindow();
            windowWidth = window.getScreenWidth();
            windowHeight = window.getScreenHeight();
            guiWidth = window.getGuiScaledWidth();
            guiHeight = window.getGuiScaledHeight();
            guiScaleOption = context.minecraft().options.guiScale().get();
        }).client("selector: make the window " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> context.minecraft().getWindow().setWindowed(FLOOR_WIDTH, FLOOR_HEIGHT));
        ArmVisualScenario.untilOrFail(script, "selector: the framebuffer is " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> TooltipsVisualScenario.synced(context) && TooltipsVisualScenario.framebufferIs(
                        context.minecraft().getWindow(), FLOOR_WIDTH, FLOOR_HEIGHT),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("selector: GUI scale " + FLOOR_GUI_SCALE, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(FLOOR_GUI_SCALE);
            minecraft.resizeDisplay();
        }).waitTicks(SETTLE_TICKS).client("selector: the selector row fits the floor", context -> {
            Window window = context.minecraft().getWindow();
            if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT)
                throw new VisualTestException("the floor is " + describeWindow(window));
            WarehouseProductionScreen screen = productionScreen(context);
            int y = screen.getGuiTop() + screen.selectorRowY();
            String text = screen.selectorRowText().getString();
            int width = context.minecraft().font.width(screen.selectorRowText());
            if (screen.selectorRowY() < 0 || y + 9 > FLOOR_GUI_HEIGHT || screen.getGuiLeft() < 0 || text.isBlank()
                    || screen.getGuiLeft() + screen.selectorOffMarkX() + 6 > FLOOR_GUI_WIDTH)
                throw new VisualTestException("at the floor the selector row '" + text + "' is at y " + y + ", "
                        + width + " px wide, the window starts at " + screen.getGuiLeft());
            ScreenInput.Point point = selectorRowPoint(screen);
            if (!screen.isOverSelectorRow(point.x(), point.y()))
                throw new VisualTestException("at the floor the hit test misses the selector row at " + point);
            ScreenInput.hover(context.minecraft(), point);
            LOGGER.info(PREFIX + "selector: CHECK PASS at the floor the selector row '{}' is at y {} and {} px wide",
                    text, y, width);
        });
        GoggleShots.reach(script, NAME, 0.0);
        script.waitTicks(SETTLE_TICKS).shotWithGui("selector-floor");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.client("selector: put the window back",
                context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, "selector: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("selector: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, "selector: the GUI is as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        RequesterVisualScenario.closeScreen(script, "station A's screen");
    }

    /** A point on the selector row's text, just left of its red mark, checked by the caller with the screen's own test. */
    private static ScreenInput.Point selectorRowPoint(WarehouseProductionScreen screen) {
        return new ScreenInput.Point(screen.getGuiLeft() + screen.selectorOffMarkX() - 12.0,
                screen.getGuiTop() + screen.selectorRowY() + 4.0);
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    // =================================================================================================================
    // the watch (server thread, every tick from the baseline)
    // =================================================================================================================

    private void watchTick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos dock = origin;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        if (controller == null || !(level.getBlockEntity(dock.offset(SAW)) instanceof SawBlockEntity saw)
                || !(level.getBlockEntity(dock.offset(STATION_A)) instanceof WarehouseProductionBlockEntity stationA)
                || !(level.getBlockEntity(dock.offset(STATION_B)) instanceof WarehouseProductionBlockEntity stationB)
                || !(level.getBlockEntity(dock.offset(SELECTOR_A)) instanceof RecipeSelectorBlockEntity selectorA)
                || !(level.getBlockEntity(dock.offset(SELECTOR_B)) instanceof RecipeSelectorBlockEntity selectorB)
                || !(level.getBlockEntity(dock.offset(BASIN)) instanceof BasinBlockEntity basin))
            return;
        long now = level.getGameTime();
        // (a) the filter never changes under an item, except a set-back to the selection. The switch writes the new
        // selection in the same tick as the filter (SelectorStations: armLocalSelector, then selectorSwitched), so a
        // change only counts as a set-back while the selection is the one of the tick before: an early switch, whose
        // selection is new, must find the saw empty.
        Item filter = sawFilter(saw);
        Optional<ItemKey> selection = stationA.selection();
        if (lastFilter != null && filter != lastFilter) {
            boolean setBack = lastSelection != null && selection.equals(lastSelection)
                    && selection.map(key -> key.getItem() == filter).orElse(false);
            if (!saw.inventory.isEmpty() && !setBack)
                throw new VisualTestException("the saw's filter changed from " + lastFilter + " to " + filter
                        + " while it held " + saw.inventory.getStackInSlot(0) + " (selection " + lastSelection
                        + " -> " + selection + ")");
            filterChanges++;
            if (setBack)
                setBacks++;
            LOGGER.info(PREFIX + "selector: the saw's filter changed {} -> {} at tick {} (saw empty: {}, selection {} "
                    + "-> {}, {})", lastFilter, filter, now, saw.inventory.isEmpty(), lastSelection, selection,
                    setBack ? "a set-back" : selection.equals(lastSelection) ? "not the selection's" : "a switch");
        }
        lastFilter = filter;
        lastSelection = selection;
        // (b) never a refused filter.
        for (RecipeSelectorBlockEntity selector : List.of(selectorA, selectorB))
            if (selector.status() == RecipeSelectorStatus.FILTER_REFUSED)
                throw new VisualTestException("the selector at " + selector.getBlockPos() + " reports FILTER_REFUSED");
        for (WarehouseProductionBlockEntity station : List.of(stationA, stationB))
            if (station.selectorArmFault().filter(SelectorWait.FILTER_REFUSED::equals).isPresent())
                throw new VisualTestException("the station at " + station.getBlockPos() + " sees a refused filter");
        // (c) the crane is asked only for the selection's ingredients, and for nothing while a station holds.
        Map<UUID, ProductionOrder<ItemKey, RackPosition>> byLine = new HashMap<>();
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders())
            for (SupplyLine<ItemKey> line : order.lines())
                byLine.put(line.id(), order);
        for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : controller.supplyNeeds()) {
            ProductionOrder<ItemKey, RackPosition> order = byLine.get(need.id());
            if (order == null)
                continue;
            WarehouseProductionBlockEntity station = order.station().equals(STATION_A_RACK) ? stationA
                    : order.station().equals(STATION_B_RACK) ? stationB : null;
            if (station == null)
                continue;
            if (station.selectorHold().isPresent())
                throw new VisualTestException("the crane is asked for " + need.key() + " while the station holds");
            if (!station.selection().equals(Optional.of(order.result())))
                throw new VisualTestException("the crane is asked for " + need.key() + " of the " + order.result()
                        + " order while the station is set to " + station.selection());
        }
        // (d) none of the other stonecutting results.
        for (ItemKey other : otherResults)
            if (controller.countOf(other) > 0)
                throw new VisualTestException("the warehouse stores " + controller.countOf(other) + " " + other);
        // (e) no light gray while the Basin is set to gray.
        if (stationB.selection().equals(Optional.of(GRAY)) && (count(basin.getInputInventory(), Items.LIGHT_GRAY_DYE)
                + count(basin.getOutputInventory(), Items.LIGHT_GRAY_DYE) > 0))
            throw new VisualTestException("light gray dye in the Basin while it is set to gray");
        // (f) no order ever times out; how long each waited in the queue.
        StringBuilder status = new StringBuilder("orders=");
        for (ProductionOrder<ItemKey, RackPosition> order : controller.productionOrders()) {
            seenOrders.put(order.id(), order);
            if (order.state() == ProductionOrderState.TIMED_OUT)
                throw new VisualTestException("the " + order.result() + " order timed out: " + order);
            if (!order.isOpen()) {
                queuedSince.remove(order.id());
                continue;
            }
            SelectorWait wait = controller.waitsForSelector(order);
            if (wait.queues())
                queuedSince.putIfAbsent(order.id(), now);
            Long since = queuedSince.get(order.id());
            if (since != null)
                longestQueue.merge(order.id(), now - since, Math::max);
            if (!wait.queues())
                queuedSince.remove(order.id());
            status.append(order.result().getItem().toString().replace("minecraft:", "")).append(':')
                    .append(order.state()).append('/').append(order.produced()).append('/').append(wait).append(' ');
        }
        serverStatus = status.toString().trim() + " filter=" + filter + " selection=" + stationA.selection();
    }

    private ServerTickWatch watch() {
        ServerTickWatch running = watch;
        if (running == null)
            throw new VisualTestException("the per-tick watch has not started");
        return running;
    }

    // =================================================================================================================
    // the census (server thread)
    // =================================================================================================================

    /** The scene and the player, plus the Basin's spout buffer and what the belts took in this tick. */
    private Map<ItemKey, Long> census(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Map<ItemKey, Long> counts = SceneItemCensus.take(level, sceneBox, server.getPlayerList().getPlayers().getFirst());
        for (ItemStack stack : spout(basin(level)))
            counts = RequesterVisualScenario.plus(counts, stack);
        for (BlockPos start : List.of(BELT_A_START, BELT_B_START)) {
            BeltBlockEntity belt = BeltHelper.getControllerBE(level, at(start));
            if (belt != null && belt.getInventory() != null)
                for (TransportedItemStack arriving : arrivals(belt))
                    counts = RequesterVisualScenario.plus(counts, arriving.stack);
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> spout(BasinBlockEntity basin) {
        return List.copyOf((List<ItemStack>) RealClicks.get(BASIN_SPOUT, basin));
    }

    @SuppressWarnings("unchecked")
    private static List<TransportedItemStack> arrivals(BeltBlockEntity belt) {
        return List.copyOf((List<TransportedItemStack>) RealClicks.get(RequesterVisualScenario.beltArrivalsField(),
                belt.getInventory()));
    }

    private void change(Map<ItemKey, Long> delta) {
        Map<ItemKey, Long> next = expected;
        for (Map.Entry<ItemKey, Long> entry : delta.entrySet())
            next = SceneItemCensus.plus(next, entry.getKey(), entry.getValue());
        expected = next;
    }

    private void assertCensus(MinecraftServer server, String moment) {
        watch().check();
        Map<ItemKey, Long> actual = census(server);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated at " + moment + ": "
                    + SceneItemCensus.describe(actual) + ", expected " + SceneItemCensus.describe(expected));
        LOGGER.info(PREFIX + "selector: census PASS: {} ({})", moment, SceneItemCensus.describe(actual));
    }

    // =================================================================================================================
    // shots and lookups
    // =================================================================================================================

    private void worldShot(VisualScript script, CameraView view, String label) {
        GoggleShots.reach(script, NAME, 0.0);
        RealClicks.fly(script, NAME);
        script.camera(view).shot(label);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    private boolean complete(MinecraftServer server, String... names) {
        watch().check();
        for (String name : names) {
            ProductionOrder<ItemKey, RackPosition> order = order(server, name);
            if (order.state() == ProductionOrderState.TIMED_OUT || order.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the " + name + " order ended " + order.state() + ": " + order);
            if (order.state() != ProductionOrderState.COMPLETE)
                return false;
        }
        return true;
    }

    /** Nothing in the lines, the inputs or the crane's head: every product stored. */
    private boolean quiet(MinecraftServer server) {
        ServerLevel level = server.overworld();
        SawBlockEntity saw = saw(level);
        BasinBlockEntity basin = basin(level);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, origin);
        for (BlockPos start : List.of(BELT_A_START, BELT_B_START)) {
            BeltBlockEntity belt = BeltHelper.getControllerBE(level, at(start));
            if (belt != null && (!belt.getInventory().getTransportedItems().isEmpty() || !arrivals(belt).isEmpty()))
                return false;
        }
        for (BlockPos hopper : List.of(HOPPER_A, HOPPER_B1, HOPPER_B2, QUARANTINE_HOPPER))
            if (!isEmpty(handler(level, at(hopper))))
                return false;
        return saw.inventory.isEmpty() && isEmpty(basin.getInputInventory()) && isEmpty(basin.getOutputInventory())
                && spout(basin).isEmpty() && !input(level, INPUT_A).hasBufferedItems()
                && !input(level, INPUT_B).hasBufferedItems() && !station(level, STATION_A).hasBufferedItems()
                && !station(level, STATION_B).hasBufferedItems() && crane != null && crane.goggleInfo().heldCount() == 0
                && controller(level).requestsFor(at(TERMINAL)).isEmpty();
    }

    private Map<ItemKey, Long> otherResultsInStock(WarehouseControllerBlockEntity controller) {
        Map<ItemKey, Long> stock = new LinkedHashMap<>();
        for (ItemKey other : otherResults)
            stock.put(other, controller.countOf(other));
        return stock;
    }

    private ProductionOrder<ItemKey, RackPosition> order(MinecraftServer server, String name) {
        UUID id = Objects.requireNonNull(orders.get(name), "no order named " + name);
        // A finished order the controller has pruned is taken as the watch last saw it; an open one must be there.
        return controller(server.overworld()).productionOrder(id)
                .or(() -> Optional.ofNullable(seenOrders.get(id)).filter(order -> !order.isOpen()))
                .orElseThrow(() -> new VisualTestException("the controller forgot the " + name + " order"));
    }

    private BlockPos at(BlockPos relative) {
        return origin.offset(relative);
    }

    private WarehouseControllerBlockEntity controller(ServerLevel level) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                at(CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private dev.wareworks.content.station.WarehouseTerminalBlockEntity terminal(ServerLevel level) {
        var terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level, at(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the terminal is missing");
        return terminal;
    }

    private WarehouseProductionBlockEntity station(ServerLevel level, BlockPos relative) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                at(relative));
        if (station == null)
            throw new VisualTestException("the production station at " + relative + " is missing");
        return station;
    }

    private WarehouseInputBlockEntity input(ServerLevel level, BlockPos relative) {
        if (level.getBlockEntity(at(relative)) instanceof WarehouseInputBlockEntity input)
            return input;
        throw new VisualTestException("the warehouse input at " + relative + " is missing");
    }

    private RecipeSelectorBlockEntity selector(ServerLevel level, BlockPos relative) {
        if (level.getBlockEntity(at(relative)) instanceof RecipeSelectorBlockEntity selector)
            return selector;
        throw new VisualTestException("no Recipe Selector at " + relative + ": " + level.getBlockState(at(relative)));
    }

    private SawBlockEntity saw(ServerLevel level) {
        if (level.getBlockEntity(at(SAW)) instanceof SawBlockEntity saw)
            return saw;
        throw new VisualTestException("the saw is missing");
    }

    private BasinBlockEntity basin(ServerLevel level) {
        if (level.getBlockEntity(at(BASIN)) instanceof BasinBlockEntity basin)
            return basin;
        throw new VisualTestException("the basin is missing");
    }

    private static ItemStack sawFilterStack(SawBlockEntity saw) {
        FilteringBehaviour filter = BlockEntityBehaviour.get(saw, FilteringBehaviour.TYPE);
        return filter == null ? ItemStack.EMPTY : filter.getFilter();
    }

    private static Item sawFilter(SawBlockEntity saw) {
        return sawFilterStack(saw).getItem();
    }

    private static boolean isEmpty(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (!handler.getStackInSlot(slot).isEmpty())
                return false;
        return true;
    }

    private static int count(IItemHandler handler, Item item) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (handler.getStackInSlot(slot).is(item))
                total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    private static IItemHandler handler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("there is no item handler at " + pos);
        return handler;
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler(level, pos), stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return FeederVisualScenario.inward(layout, rack);
    }

    @SuppressWarnings("unused")
    private static List<String> describe(List<ItemStack> stacks) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : stacks)
            out.add(stack.toString());
        return out;
    }
}
