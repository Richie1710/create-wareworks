package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.chute.ChuteBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
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
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.RecipeSelectorBlockEntity;
import dev.wareworks.content.selector.RecipeSlip;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.station.ProductionMenuLayout;
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
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "selector-remote" (ADR-085, design §15.3): the <b>Ender Recipe Selector</b> and recipe slips, with Create
 * Mobile Packages' Robo Bees to a factory {@value #FACTORY_DISTANCE} blocks east that has <b>one</b> Mechanical Saw.
 * Runs only with {@code -Pwareworks.cmp}; without CMP it fails at once ({@link RoboBeeLedger#verify}).
 * <p>
 * <b>The warehouse</b>: a short aisle with a terminal and one Production Station "Saw" with three patterns (cobbled
 * deepslate to deepslate tile stairs, two slabs, wall), a steadily powered Packager on its back signed "Saw" and the
 * warehouse's send port beside it; Warehouse Input R with an unpacking Packager on its back and the powered receive
 * port "Warehouse". <b>The factory</b> (force-loaded with the warehouse, released in {@code finish}): the powered
 * receive port "Saw" on an unpacking Packager that carries the Ender selector, a chest, a chute, a Mechanical Saw facing
 * up, a belt into a funnel and a chest, a Packager signed "Warehouse" on a redstone block and the factory's send port.
 * <p>
 * <b>Real clicks</b>: the four ports from one tuned stack, addresses, bees, return mode, signs, levers (the
 * {@code robobee} helpers {@link BeePorts}, {@link RealClicks}); sneak-clicks with the Ender item on the saw, the
 * station (slips on) and Input R (bound); a plain click on the Packager (nothing placed, "sneak to place") and the
 * sneak-placement; the three patterns; the orders for stairs, slabs and walls with three stock clicks back to back.
 * <p>
 * <b>Checked</b>: the probe sends one run first after slips are switched on and after the rejoin; every box the station's
 * Packager holds carries the slip of the station's selection; the factory saw's filter changes only in the tick a box
 * with a slip is accepted (and once by the declared hand change) and only while the saw is empty; no wall ingredient is
 * asked for before the slab order is complete; an order that leaves a queue or a hold starts over with a whole deadline
 * (at least two after a long queue); a save, quit and rejoin while the slab box flies keeps its slip; a hand
 * change at the factory is set right by the next box before it opens; exactly 64 stairs, 64 slabs and 64 walls come back,
 * none of the other stonecutting results, all bees home; the census of both sites, the player and every box in the air
 * exact. Grep {@code selector-remote: ALL CHECKS PASSED in English and German}.
 */
public final class SelectorRemoteVisualScenario implements VisualScenario {
    public static final String NAME = "selector-remote";

    private static final String WORLD_FOLDER = "wareworks_visual_selector_remote";
    private static final long RUN_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final String SAW_ADDRESS = "Saw";
    private static final String WAREHOUSE_ADDRESS = "Warehouse";
    /** Bee speed of this world (design §15.3: the subject is the switching; {@code robobee} proves the default). */
    private static final int BEE_SPEED = 18;

    // --- the warehouse (offsets from the dock; aisle east, right rack side +z) ---------------------------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 6;
    private static final int CRANE_RPM = 256;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition STATION_RACK = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition RETURN_RACK = RackPosition.of(5, 0, Side.RIGHT);
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(1, 0, Side.LEFT),
            RackPosition.of(2, 0, Side.LEFT), RackPosition.of(3, 0, Side.LEFT), RackPosition.of(4, 0, Side.LEFT));
    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);
    private static final BlockPos STATION = new BlockPos(3, 0, 1);
    private static final BlockPos STATION_PACKAGER = STATION.south();
    private static final BlockPos STATION_SIGN = STATION_PACKAGER.south();
    private static final BlockPos SEND_PORT = STATION_PACKAGER.west();
    private static final BlockPos RETURN_INPUT = new BlockPos(5, 0, 1);
    private static final BlockPos RETURN_PACKAGER = RETURN_INPUT.south();
    private static final BlockPos RECEIVE_PORT = RETURN_PACKAGER.south();
    private static final BlockPos RECEIVE_LEVER = RECEIVE_PORT.east();
    private static final BlockPos COBBLED_BARREL = new BlockPos(1, 0, -2);
    private static final BlockPos LIFT = new BlockPos(3, 6, -4);
    private static final BlockPos WAREHOUSE_MIN = new BlockPos(-3, -1, -4);
    private static final BlockPos WAREHOUSE_MAX = new BlockPos(RAILS + 2, 6, 5);

    // --- the factory (offsets from its origin, FACTORY_DISTANCE east of the dock) ------------------------------------
    private static final int FACTORY_DISTANCE = 96;
    private static final BlockPos FACTORY = new BlockPos(FACTORY_DISTANCE, 0, 0);
    private static final BlockPos F_SAW = BlockPos.ZERO;
    private static final BlockPos F_SAW_MOTOR = new BlockPos(0, 0, -1);
    private static final BlockPos F_CHUTE = new BlockPos(0, 1, 0);
    private static final BlockPos F_IN = new BlockPos(0, 2, 0);
    /** North of the chest it unpacks into, facing the receive port; the Ender selector sits on its east side. */
    private static final BlockPos F_UNPACKER = new BlockPos(0, 2, -1);
    private static final BlockPos F_SELECTOR = F_UNPACKER.east();
    private static final BlockPos F_RECEIVE = new BlockPos(0, 2, -2);
    private static final BlockPos F_PILLAR = new BlockPos(0, 1, -2);
    private static final BlockPos F_LEVER = new BlockPos(-1, 1, -2);
    private static final BlockPos F_BELT_START = new BlockPos(1, 0, 0);
    private static final BlockPos F_BELT_END = new BlockPos(3, 0, 0);
    private static final BlockPos F_BELT_MOTOR = new BlockPos(1, 0, 1);
    private static final BlockPos F_FUNNEL = F_BELT_END.above();
    private static final BlockPos F_OUT = F_FUNNEL.east();
    private static final BlockPos F_PACKER = F_OUT.east();
    private static final BlockPos F_SIGN = F_PACKER.south();
    private static final BlockPos F_SEND = F_PACKER.east();
    private static final BlockPos FACTORY_MIN = new BlockPos(-3, -1, -3);
    private static final BlockPos FACTORY_MAX = new BlockPos(8, 5, 4);
    private static final int SAW_RPM = 128;
    private static final int BELT_RPM = 64;

    // --- items -------------------------------------------------------------------------------------------------------
    private static final ItemKey COBBLED = ItemKey.of(Items.COBBLED_DEEPSLATE);
    private static final ItemKey STAIRS = ItemKey.of(Items.DEEPSLATE_TILE_STAIRS);
    private static final ItemKey SLAB = ItemKey.of(Items.DEEPSLATE_TILE_SLAB);
    private static final ItemKey WALL = ItemKey.of(Items.DEEPSLATE_TILE_WALL);
    private static final int COBBLED_STOCK = 256;
    private static final int ORDER = 64;
    private static final int DEFAULT_TIMEOUT_TICKS = 6000;
    /**
     * The watch (e) looks at an order's deadline this many ticks after the order left the selector's pause: the
     * controller gives it a fresh deadline in its next timeout pass, which may come a tick after the watch saw it go.
     */
    private static final int DEADLINE_CHECK_DELAY_TICKS = 5;
    /** How much of a fresh deadline may already have run when the watch (e) looks at it. */
    private static final int DEADLINE_SLACK_TICKS = 20;
    /** A pause this long counts as a queue whose deadline was really held back (re-review fix, design §15.3). */
    private static final int LONG_PAUSE_TICKS = 200;
    /** Slabs and walls each wait behind the stairs (walls behind the slabs too) and must leave their queue. */
    private static final int LONG_PAUSES_REQUIRED = 2;

    // --- bees and hotbar -----------------------------------------------------------------------------------------------
    private static final int PORTS = 4;
    private static final int WAREHOUSE_BEES = 8;
    private static final int FACTORY_BEES = 8;
    private static final int SLOT_PORTS = 0;
    private static final int SLOT_WAREHOUSE_BEES = 1;
    private static final int SLOT_FACTORY_BEES = 2;
    private static final int SLOT_SIGNS = 3;
    private static final int SLOT_ENDER = 4;
    private static final int SLOT_EMPTY = 8;

    // --- timing (client ticks) ---------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int STEP_TIMEOUT_TICKS = 6000;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- clicks ------------------------------------------------------------------------------------------------------
    private static final double RAIL_TOP = WarehouseRailBlock.HEIGHT_PIXELS / 16.0;
    private static final Vec3 FROM_SOUTH = new Vec3(0.0, 2.2, 1.2);
    private static final Vec3 FROM_EAST = new Vec3(1.2, 2.0, 0.0);
    private static final Vec3 ABOVE_STATION = new Vec3(0.0, 2.0, 0.6);

    // --- camera views ------------------------------------------------------------------------------------------------
    private static final CameraView HALL_VIEW = CameraView.of("hall", 3.5, 5.5, 8.5, 3.0, 0.0, 1.5);
    private static final CameraView FACTORY_VIEW = CameraView.of("factory", FACTORY_DISTANCE + 3.0, 4.5, 6.5,
            FACTORY_DISTANCE + 3.0, 1.0, 0.0);
    private static final CameraView SAW_VIEW = CameraView.of("factory-saw", FACTORY_DISTANCE + 0.5, 2.4, 2.6,
            FACTORY_DISTANCE + 0.5, 0.4, 0.3);
    private static final CameraView STATION_GOGGLES = CameraView.of("station", 3.5, 1.9, 0.4, 3.5, 0.5, 1.0);
    private static final CameraView ENDER_GOGGLES = CameraView.of("ender-selector", FACTORY_DISTANCE + 3.2, 2.6, -0.5,
            FACTORY_DISTANCE + 1.2, 2.5, -0.5);

    // --- state -------------------------------------------------------------------------------------------------------
    @Nullable
    private volatile RoboBeeLedger ledger;
    private final ForcedChunks forced = new ForcedChunks(NAME);
    private final BeePorts beePorts = new BeePorts(NAME, this::ledger);
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);
    private final Map<String, String> englishLines = new HashMap<>();
    private final Set<String> deviations = new LinkedHashSet<>();
    private volatile BlockPos origin = BlockPos.ZERO;
    private volatile AABB warehouseBox = new AABB(BlockPos.ZERO);
    private volatile AABB factoryBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> expected = Map.of();
    private volatile Set<ItemKey> otherResults = Set.of();
    @Nullable
    private volatile UUID network;
    private volatile boolean sawFlipped;
    private volatile boolean beltFlipped;
    /** Server thread: the orders of the run by result, and what the watch saw. */
    private final Map<ItemKey, UUID> orders = new LinkedHashMap<>();
    /** What each order had produced when it was seen COMPLETE (a finished order is not kept across a rejoin). */
    private final Map<ItemKey, Long> finished = new HashMap<>();
    @Nullable
    private Item lastFactoryFilter;
    /**
     * Whether the factory's chest and chute were empty at the last watched tick. A box is unpacked into the chest in the
     * tick its slip sets the filter, so the chest is judged as it was before that tick.
     */
    private boolean factoryFeedWasEmpty;
    private int factoryFilterChanges;
    private volatile boolean handChangeDeclared;
    private final Map<ItemKey, Integer> slipsSeen = new LinkedHashMap<>();
    private volatile int probeTrips = -1;
    /**
     * Server thread, watch (e): since when each order has been in the selector's pause, the orders that left it and
     * when, and how many left a long pause with a deadline that started over. Suspended across the rejoin, whose load
     * gives every order a fresh deadline anyway.
     */
    private final Map<UUID, Long> pausedSince = new HashMap<>();
    private final Map<UUID, long[]> leftPause = new HashMap<>();
    private int longPausesLeft;
    private long longestPause;
    private volatile boolean pauseWatchSuspended;
    private volatile int rejoinTrips;
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
        script.client("selector-remote: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "selector-remote run"))
                .server("selector-remote: CHECK Create Mobile Packages is loaded", (server, context) -> {
                    String cmp = RoboBeeLedger.verify(FACTORY_DISTANCE + RAILS + 8);
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS {} is loaded", cmp);
                })
                .server("selector-remote: build the warehouse", this::buildWarehouse)
                .server("selector-remote: build the factory", this::buildFactory)
                .server("selector-remote: force-load the warehouse and the factory", (server, context) -> forced.force(
                        server.overworld(), List.of(warehouseBox, factoryBox)))
                .server("selector-remote: the player flies, wears goggles and carries ports, bees, signs and the Ender "
                        + "Recipe Selector", this::equip)
                .serverUntil("selector-remote: wait until the warehouse is indexed and the saw line runs the right way",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("selector-remote: the config of this world", this::configure);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            worldShot(script, HALL_VIEW, "remote-hall");
            worldShot(script, FACTORY_VIEW, "remote-factory");
            ports(script);
            enderSelector(script);
            patterns(script);
            orders(script);
            end(script);
            goggles(script);
            return;
        }
        worldShot(script, FACTORY_VIEW, "remote-end");
        script.server("selector-remote: every check passed", (server, context) -> {
            ledger().check();
            // Re-review fix: the default timeout never runs out during these queues, so the guard against a queued
            // order's deadline running is that every order leaving a pause got a fresh deadline (watch e), and it must
            // really have seen the long queues.
            if (longPausesLeft < LONG_PAUSES_REQUIRED)
                throw new VisualTestException("only " + longPausesLeft + " order(s) left a selector pause of more than "
                        + LONG_PAUSE_TICKS + " ticks; expected at least " + LONG_PAUSES_REQUIRED + " (slabs, walls)");
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS {} orders left a selector pause of more than {} ticks "
                    + "(longest {}), each with a fresh deadline", longPausesLeft, LONG_PAUSE_TICKS, longestPause);
            LOGGER.info(PREFIX + "selector-remote: deviations from the design: {}",
                    deviations.isEmpty() ? "none" : deviations);
            LOGGER.info(PREFIX + "selector-remote: ALL CHECKS PASSED in English and German (4 ports from one tuned "
                    + "stack, the Ender Recipe Selector linked to the saw, slips switched on at the station, Input R "
                    + "bound, a plain click refused and the sneak-placement on the Packager, stairs, slabs and walls "
                    + "ordered back to back, the probe one run first, every box stamped with the selection, the filter "
                    + "set only when a box was accepted on an empty line, a rejoin while the slab box flew, a hand change "
                    + "set right by the next box, 64 + 64 + 64 back, nothing else made, all bees home, every census "
                    + "exact)");
        });
    }

    @Override
    public void finish(VisualScript script) {
        script.server("selector-remote: put the bee speed back, release the forced chunks and stop the ledger",
                (server, context) -> {
                    RoboBeeLedger.restoreBeeSpeed();
                    forced.release(server.overworld());
                    RoboBeeLedger running = ledger;
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
        if (WareworksConfig().productionOrderTimeoutTicks.get() != DEFAULT_TIMEOUT_TICKS)
            throw new VisualTestException("productionOrderTimeoutTicks is not the default " + DEFAULT_TIMEOUT_TICKS);
        int before = RoboBeeLedger.overrideBeeSpeed(BEE_SPEED);
        deviations.add("bee speed " + before + " -> " + BEE_SPEED + " for this world only (design §15.3)");
        Set<ItemKey> others = new LinkedHashSet<>(SelectorVisualScenario.OTHER_CUTS);
        others.removeAll(List.of(STAIRS, SLAB, WALL));
        otherResults = Set.copyOf(others);
        LOGGER.info(PREFIX + "selector-remote: CHECK PASS config: beeSpeed {} -> {}, productionOrderTimeoutTicks {} "
                + "(default); {} other results the saw could cut", before, BEE_SPEED, DEFAULT_TIMEOUT_TICKS,
                others.size());
    }

    private static dev.wareworks.config.WareworksConfig.Server WareworksConfig() {
        return dev.wareworks.config.WareworksConfig.SERVER;
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void buildWarehouse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        origin = dock;
        warehouseBox = AABB.encapsulatingFullBlocks(dock.offset(WAREHOUSE_MIN), dock.offset(WAREHOUSE_MAX));
        factoryBox = AABB.encapsulatingFullBlocks(dock.offset(FACTORY).offset(FACTORY_MIN),
                dock.offset(FACTORY).offset(FACTORY_MAX));
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
        Map<RackPosition, BlockPos> wanted = Map.of(TERMINAL_RACK, TERMINAL, STATION_RACK, STATION, RETURN_RACK,
                RETURN_INPUT);
        for (Map.Entry<RackPosition, BlockPos> entry : wanted.entrySet())
            if (!layout.rackPos(entry.getKey()).equals(dock.offset(entry.getValue())))
                throw new VisualTestException("rack position " + entry.getKey() + " stands at "
                        + layout.rackPos(entry.getKey()) + ", not at " + dock.offset(entry.getValue()));
        level.setBlockAndUpdate(dock.offset(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL_RACK)));
        level.setBlockAndUpdate(dock.offset(STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward(layout, STATION_RACK)));
        level.setBlockAndUpdate(dock.offset(RETURN_INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, RETURN_RACK)));
        for (RackPosition rack : STORAGE) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(layout.rackPos(rack).relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        for (int left = COBBLED_STOCK; left > 0; left -= 64)
            insert(level, dock.offset(COBBLED_BARREL), COBBLED.toStack(Math.min(64, left)));
        // The out door: a Packager whose back touches the station, on a redstone block; the in door: an unpacking
        // Packager on Input R's back, the receive port behind it and its lever.
        level.setBlockAndUpdate(dock.offset(STATION_PACKAGER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(STATION_PACKAGER).below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(dock.offset(RETURN_PACKAGER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(RECEIVE_LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false));
    }

    private void buildFactory(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos factory = context.origin().offset(FACTORY);
        level.getChunk(factory);
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, factory.getX(), factory.getZ());
        if (surface != factory.getY())
            throw new VisualTestException("the ground at the factory is at y " + surface + ", not " + factory.getY());
        level.setBlockAndUpdate(factory.offset(F_SAW), AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
        level.setBlockAndUpdate(factory.offset(F_SAW_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        motor(level, factory.offset(F_SAW_MOTOR)).generatedSpeed.setValue(SAW_RPM);
        level.setBlockAndUpdate(factory.offset(F_CHUTE), AllBlocks.CHUTE.getDefaultState());
        level.setBlockAndUpdate(factory.offset(F_IN), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(factory.offset(F_UNPACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(factory.offset(F_PILLAR), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(factory.offset(F_LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST)
                .setValue(LeverBlock.POWERED, false));
        BeltConnectorItem.createBelts(level, factory.offset(F_BELT_START), factory.offset(F_BELT_END));
        if (!AllBlocks.BELT.has(level.getBlockState(factory.offset(F_BELT_START))))
            throw new VisualTestException("the factory belt was not created");
        level.setBlockAndUpdate(factory.offset(F_BELT_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.NORTH));
        motor(level, factory.offset(F_BELT_MOTOR)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(factory.offset(F_OUT), Blocks.CHEST.defaultBlockState());
        RequesterVisualScenario.beltFunnel(level, factory.offset(F_FUNNEL), Direction.WEST, false);
        level.setBlockAndUpdate(factory.offset(F_PACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(factory.offset(F_PACKER).below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(factory.offset(F_SEND).below(), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(factory.offset(F_SIGN).below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
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
        ItemStack ports = new ItemStack(RoboBeeLedger.beePortItem(), PORTS);
        if (LogisticallyLinkedBlockItem.isTuned(ports))
            throw new VisualTestException("the fresh port stack is already tuned");
        player.getInventory().setItem(SLOT_PORTS, ports);
        player.getInventory().setItem(SLOT_WAREHOUSE_BEES, new ItemStack(RoboBeeLedger.roboBeeItem(), WAREHOUSE_BEES));
        player.getInventory().setItem(SLOT_FACTORY_BEES, new ItemStack(RoboBeeLedger.roboBeeItem(), FACTORY_BEES));
        player.getInventory().setItem(SLOT_SIGNS, new ItemStack(Items.OAK_SIGN, 2));
        player.getInventory().setItem(SLOT_ENDER, WareworksBlocks.ENDER_RECIPE_SELECTOR.asStack());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (AABB box : List.of(warehouseBox, factoryBox))
            if (!SceneItemCensus.isFullyLoaded(level, box))
                return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null || !(controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == STORAGE.size() && controller.inputStations().size() == 1
                && controller.productionStations().size() == 1 && crane.isControllerLinked()
                && crane.aisleLength() == RAILS && controller.countOf(COBBLED) == COBBLED_STOCK))
            return false;
        SawBlockEntity saw = factorySaw(level);
        if (saw.getSpeed() == 0)
            return false;
        if (Math.round(saw.getItemMovementVec().x) != 1) {
            if (sawFlipped)
                throw new VisualTestException("the factory saw pushes along " + saw.getItemMovementVec() + " either way");
            sawFlipped = true;
            CreativeMotorBlockEntity motor = motor(level, at(FACTORY.offset(F_SAW_MOTOR)));
            motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
            return false;
        }
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, at(FACTORY.offset(F_BELT_START)));
        if (belt == null || belt.getSpeed() == 0)
            return false;
        if (belt.getMovementFacing() != Direction.EAST) {
            if (beltFlipped)
                throw new VisualTestException("the factory belt moves " + belt.getMovementFacing() + " either way");
            beltFlipped = true;
            CreativeMotorBlockEntity motor = motor(level, at(FACTORY.offset(F_BELT_MOTOR)));
            motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
            return false;
        }
        LOGGER.info(PREFIX + "selector-remote: scene ready: the factory saw pushes EAST onto the belt (flipped saw {} "
                + "belt {})", sawFlipped, beltFlipped);
        return true;
    }

    // =================================================================================================================
    // act 1: ports, addresses, bees, signs, levers
    // =================================================================================================================

    private void ports(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_PORTS, "the bee ports",
                stack -> stack.is(RoboBeeLedger.beePortItem()));
        beePorts.placePort(script, "warehouse send port", SEND_PORT, FROM_SOUTH);
        beePorts.tuneOn(script, "warehouse send port", SEND_PORT, FROM_SOUTH, placed -> network = placed);
        beePorts.placePort(script, "warehouse receive port", RECEIVE_PORT, FROM_SOUTH);
        beePorts.placePort(script, "factory receive port", FACTORY.offset(F_RECEIVE), FROM_EAST);
        beePorts.placePort(script, "factory send port", FACTORY.offset(F_SEND), FROM_EAST);
        script.server("selector-remote: CHECK the four ports share one network and start the bee ledger",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    RoboBeeLedger started = RoboBeeLedger.start(level, NAME);
                    started.addPort(new RoboBeeLedger.Port("warehouse send", at(SEND_PORT), RoboBeeLedger.Role.SEND,
                            Set.of(SAW_ADDRESS)));
                    started.addPort(new RoboBeeLedger.Port("warehouse receive", at(RECEIVE_PORT),
                            RoboBeeLedger.Role.RECEIVE, Set.of()));
                    started.addPort(new RoboBeeLedger.Port("factory receive", at(FACTORY.offset(F_RECEIVE)),
                            RoboBeeLedger.Role.RECEIVE, Set.of()));
                    started.addPort(new RoboBeeLedger.Port("factory send", at(FACTORY.offset(F_SEND)),
                            RoboBeeLedger.Role.SEND, Set.of(WAREHOUSE_ADDRESS)));
                    ledger = started;
                    beePorts.checkNetwork(level, Objects.requireNonNull(network, "network"));
                });
        RequesterVisualScenario.takeAnEmptyHand(script);
        beePorts.sendPort(script, "warehouse send port", SEND_PORT, FROM_SOUTH, SLOT_WAREHOUSE_BEES, WAREHOUSE_BEES,
                null);
        beePorts.receivePort(script, "warehouse receive port", RECEIVE_PORT, FROM_SOUTH, WAREHOUSE_ADDRESS, null);
        beePorts.receivePort(script, "factory receive port", FACTORY.offset(F_RECEIVE), FROM_EAST, SAW_ADDRESS,
                "remote-port-address");
        beePorts.sendPort(script, "factory send port", FACTORY.offset(F_SEND), FROM_EAST, SLOT_FACTORY_BEES,
                FACTORY_BEES, null);
        script.server("selector-remote: CHECK every port", (server, context) -> beePorts.checkPorts(server,
                WAREHOUSE_BEES + FACTORY_BEES));
        RequesterVisualScenario.selectHotbar(script, SLOT_SIGNS, "the oak signs", stack -> stack.is(Items.OAK_SIGN));
        RealClicks.writeSign(script, NAME, "the station Packager's sign", STATION_SIGN, SAW_ADDRESS, FROM_SOUTH);
        RealClicks.writeSign(script, NAME, "the factory Packager's sign", FACTORY.offset(F_SIGN), WAREHOUSE_ADDRESS,
                FROM_SOUTH);
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click("the warehouse receive port's lever", RECEIVE_LEVER,
                Direction.UP, new Vec3(0.0, 2.0, 1.0)));
        RealClicks.rightClick(script, NAME, RealClicks.click("the factory receive port's lever",
                FACTORY.offset(F_LEVER), Direction.WEST, new Vec3(-1.8, 0.4, 0.0)));
        script.serverUntil("selector-remote: both levers are on", (server, context) -> server.overworld()
                .getBlockState(at(RECEIVE_LEVER)).getValue(LeverBlock.POWERED) && server.overworld()
                .getBlockState(at(FACTORY.offset(F_LEVER))).getValue(LeverBlock.POWERED), SYNC_TIMEOUT_TICKS);
        script.server("selector-remote: CHECK the signs and the wiring", (server, context) -> {
            ServerLevel level = server.overworld();
            for (Map.Entry<BlockPos, String> entry : Map.of(at(STATION_PACKAGER), SAW_ADDRESS,
                    at(FACTORY.offset(F_PACKER)), WAREHOUSE_ADDRESS).entrySet()) {
                PackagerBlockEntity packager = packager(level, entry.getKey());
                packager.updateSignAddress();
                if (!entry.getValue().equals(packager.signBasedAddress))
                    throw new VisualTestException("the Packager at " + entry.getKey() + " reads '"
                            + packager.signBasedAddress + "'");
            }
            beePorts.checkWiring(level, Map.of(at(SEND_PORT), Set.of(at(STATION_PACKAGER)), at(RECEIVE_PORT),
                    Set.of(at(RETURN_PACKAGER)), at(FACTORY.offset(F_RECEIVE)), Set.of(at(FACTORY.offset(F_UNPACKER))),
                    at(FACTORY.offset(F_SEND)), Set.of(at(FACTORY.offset(F_PACKER)))),
                    List.of(at(STATION_PACKAGER), at(FACTORY.offset(F_PACKER))),
                    List.of(at(RETURN_PACKAGER), at(FACTORY.offset(F_UNPACKER))));
        });
    }

    // =================================================================================================================
    // act 2: the Ender Recipe Selector
    // =================================================================================================================

    private void enderSelector(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_ENDER, "the Ender Recipe Selector",
                stack -> stack.is(WareworksBlocks.ENDER_RECIPE_SELECTOR.asItem()));
        RealClicks.sneakClick(script, NAME, "the factory saw", FACTORY.offset(F_SAW), Direction.SOUTH,
                new Vec3(0.5, 0.6, 1.0), new Vec3(FACTORY_DISTANCE + 0.5, 0.0, 3.0));
        actionBar(script, "the link to the saw", context -> WareworksLang.translateDirect(
                WareworksLang.SELECTOR_LINKED_MACHINE, AllBlocks.MECHANICAL_SAW.get().getName(),
                label(FACTORY.offset(F_SAW))));
        sneakOnAisle(script, "the station", STATION);
        actionBar(script, "slips switched on", context -> WareworksLang.translateDirect(WareworksLang.SELECTOR_SLIPS_ON,
                label(STATION)));
        script.server("selector-remote: CHECK the station puts recipe slips into its packages", (server, context) -> {
            WarehouseProductionBlockEntity station = station(server.overworld());
            if (station.selectorMode() != SelectorMode.SLIPS
                    || !station.selectorMachineKind().equals(Optional.of(SelectorMachine.Kind.SAW)))
                throw new VisualTestException("the station is " + station.selectorMode() + " for "
                        + station.selectorMachineKind());
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS a sneak-click with the saw-linked Ender item switched "
                    + "the station to recipe slips for a SAW");
        });
        worldShot(script, HALL_VIEW, "remote-slips-on");
        sneakOnAisle(script, "Input R", RETURN_INPUT);
        actionBar(script, "Input R bound", context -> WareworksLang.translateDirect(
                WareworksLang.SELECTOR_BOUND_RETURN_INPUT, label(STATION)));
        // At the factory: a plain click on the Packager is Create's and places nothing; a sneak-click places it.
        RealClicks.rightClick(script, NAME, RealClicks.click("the unpacking Packager (plain click)",
                FACTORY.offset(F_UNPACKER), Direction.EAST, new Vec3(1.6, 0.3, 0.0)));
        actionBar(script, "the plain click", context -> WareworksLang.translateDirect(
                WareworksLang.SELECTOR_SNEAK_TO_PLACE));
        script.server("selector-remote: CHECK the plain click placed nothing", (server, context) -> {
            if (!server.overworld().getBlockState(at(FACTORY.offset(F_SELECTOR))).isAir())
                throw new VisualTestException("a plain click placed " + server.overworld()
                        .getBlockState(at(FACTORY.offset(F_SELECTOR))));
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS a plain click on the Packager placed nothing and said "
                    + "to sneak");
        });
        RealClicks.sneakClick(script, NAME, "the unpacking Packager", FACTORY.offset(F_UNPACKER), Direction.EAST,
                new Vec3(1.0, 0.8, 0.5), new Vec3(FACTORY_DISTANCE + 3.0, 0.0, -0.5));
        script.serverUntil("selector-remote: the Ender selector stands on the Packager", (server, context) ->
                server.overworld().getBlockEntity(at(FACTORY.offset(F_SELECTOR))) instanceof RecipeSelectorBlockEntity,
                SYNC_TIMEOUT_TICKS).server("selector-remote: CHECK the Ender selector", (server, context) -> {
                    RecipeSelectorBlockEntity selector = enderSelector(server.overworld());
                    if (!selector.isEnder() || !selector.machineLink().equals(Optional.of(at(FACTORY.offset(F_SAW)))))
                        throw new VisualTestException("the selector is ender=" + selector.isEnder() + " linked to "
                                + selector.machineLink());
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS a sneak-click placed the Ender Recipe Selector "
                            + "on the unpacking Packager, linked to the saw at {}", selector.machineLink());
                });
        worldShot(script, SAW_VIEW, "remote-placed");
    }

    private void sneakOnAisle(VisualScript script, String what, BlockPos block) {
        RealClicks.sneakClick(script, NAME, what, block, Direction.NORTH, new Vec3(0.5, 0.8, 0.0),
                new Vec3(block.getX() + 0.5, RAIL_TOP, 0.3));
    }

    private Component label(BlockPos relative) {
        BlockPos pos = origin.offset(relative);
        return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void actionBar(VisualScript script, String what, Function<VisualContext, Component> text) {
        RequesterVisualScenario.actionBar(script, what, text);
    }

    // =================================================================================================================
    // act 3: patterns
    // =================================================================================================================

    private void patterns(VisualScript script) {
        hotbar(script, "the pattern items", () -> Map.of(0, COBBLED.toStack(1), 1, STAIRS.toStack(1), 2,
                SLAB.toStack(1), 3, WALL.toStack(1)));
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click("the station", STATION, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "selector-remote: the station's screen is open",
                context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                        && screen.getMenu().getStateId() > 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
        FeederVisualScenario.patternEntry(script, 0, 0, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 0, 1, Items.DEEPSLATE_TILE_STAIRS, ProductionMenuLayout.RESULT_CELL);
        tab(script, 1);
        FeederVisualScenario.patternEntry(script, 1, 0, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 1, 2, Items.DEEPSLATE_TILE_SLAB, ProductionMenuLayout.RESULT_CELL);
        FeederVisualScenario.scrollResult(script, 1, 2);
        tab(script, 2);
        FeederVisualScenario.patternEntry(script, 2, 0, Items.COBBLED_DEEPSLATE, 0);
        FeederVisualScenario.patternEntry(script, 2, 3, Items.DEEPSLATE_TILE_WALL, ProductionMenuLayout.RESULT_CELL);
        ArmVisualScenario.untilOrFail(script, "selector-remote: all three patterns pass the selector check",
                context -> List.of(0, 1, 2).stream().allMatch(slot -> {
                    List<Optional<SelectorRecipeCheck.Verdict>> checks = screen(context).state().selectorChecks();
                    return slot < checks.size() && checks.get(slot).equals(Optional.of(SelectorRecipeCheck.Verdict.VALID))
                            && !screen(context).isRed(slot);
                }), SCREEN_TIMEOUT_TICKS, context -> "checks " + screen(context).state().selectorChecks());
        RequesterVisualScenario.closeScreen(script, "the station's screen");
        hotbar(script, "an empty hotbar", Map::of);
        script.server("selector-remote: take the baseline census and start watching every tick", (server, context) -> {
            expected = census(server);
            if (expected.getOrDefault(COBBLED, 0L) != COBBLED_STOCK)
                throw new VisualTestException("the baseline holds " + SceneItemCensus.describe(expected));
            RoboBeeLedger running = ledger();
            running.record();
            running.watchEveryTick(this::watchTick);
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS three patterns written with clicks, every one VALID for "
                    + "the saw; baseline {}", SceneItemCensus.describe(expected));
        });
    }

    private static void tab(VisualScript script, int slot) {
        script.client("selector-remote: select pattern " + slot, context -> ScreenInput.click(context.minecraft(),
                ScreenInput.productionTab(screen(context), slot))).waitTicks(SETTLE_TICKS);
    }

    private static WarehouseProductionScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open: " + context.minecraft().screen);
    }

    private static void hotbar(VisualScript script, String what, Supplier<Map<Integer, ItemStack>> lazyItems) {
        script.server("selector-remote: the hotbar holds " + what, (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            for (int slot = 0; slot < player.getInventory().items.size(); slot++)
                player.getInventory().items.set(slot, ItemStack.EMPTY);
            lazyItems.get().forEach((slot, stack) -> player.getInventory().setItem(slot, stack.copy()));
            player.getInventory().selected = SLOT_EMPTY;
            player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
            player.inventoryMenu.broadcastChanges();
        }).waitTicks(SETTLE_TICKS * 2);
    }

    // =================================================================================================================
    // act 4: stairs, slabs and walls back to back; the probe; a rejoin while the slab box flies; a hand change
    // =================================================================================================================

    private void orders(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        RealClicks.rightClick(script, NAME, RealClicks.click("the terminal", TERMINAL, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "selector-remote: the terminal screen is open with its stock",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                        && terminal.entry(STAIRS).isPresent(), SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS).client("selector-remote: ask for " + ORDER + " per click",
                context -> TerminalOrder.scrollAmount(context, NAME, ORDER)).waitTicks(SETTLE_TICKS);
        clickOrder(script, STAIRS);
        clickOrder(script, SLAB);
        clickOrder(script, WALL);
        script.serverUntil("selector-remote: the stairs order is the probe, slabs and walls wait their turn",
                (server, context) -> {
                    ledger().check();
                    WarehouseControllerBlockEntity controller = controller(server.overworld());
                    return station(server.overworld()).selectorProbeOrder().equals(Optional.of(orders.get(STAIRS)))
                            && controller.waitsForSelector(order(server, SLAB)).queues()
                            && controller.waitsForSelector(order(server, WALL)).queues();
                }, SYNC_TIMEOUT_TICKS);
        script.waitTicks(SETTLE_TICKS * 3).client("selector-remote: rest the cursor on the title", context -> {
            WarehouseTerminalScreen terminal = TerminalOrder.terminalScreen(context);
            ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + 30.0,
                    terminal.getGuiTop() + 5.0));
        }).waitTicks(SETTLE_TICKS).shotWithGui("remote-probe");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.server("selector-remote: CHECK the probe and the queue", (server, context) -> {
            WarehouseControllerBlockEntity controller = controller(server.overworld());
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS the stairs order is the probe ({}), slabs {} and walls {} "
                    + "wait", controller.waitsForSelector(order(server, STAIRS)),
                    controller.waitsForSelector(order(server, SLAB)), controller.waitsForSelector(order(server, WALL)));
        });
        // The probe: one run goes out first, its stair comes back, then the rest.
        script.serverUntil("selector-remote: the probe's box left the warehouse", (server, context) -> {
            ledger().check();
            return ledger().sentTo(SAW_ADDRESS).getOrDefault(COBBLED, 0L) > 0;
        }, STEP_TIMEOUT_TICKS).server("selector-remote: CHECK the probe sent one run", (server, context) -> {
            Map<ItemKey, Long> out = ledger().sentTo(SAW_ADDRESS);
            if (!out.equals(Map.of(COBBLED, 1L)))
                throw new VisualTestException("the probe sent " + out + ", expected one run of 1 cobbled deepslate");
            if (slipsSeen.getOrDefault(STAIRS, 0) == 0)
                throw new VisualTestException("the watch never saw a stairs slip on the station's box");
            probeTrips = ledger().markTrips();
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS the probe's box carries one run (1 cobbled deepslate) "
                    + "and the slip for stairs");
        });
        script.serverUntil("selector-remote: the probe's stair is back and the station trusts its slips",
                (server, context) -> {
                    ledger().check();
                    return order(server, STAIRS).produced() > 0 && station(server.overworld()).selectorSlipsTrusted();
                }, STEP_TIMEOUT_TICKS);
        sawShot(script, STAIRS, "remote-saw-stairs");
        script.serverUntil("selector-remote: the stairs order is complete", (server, context) -> complete(server, STAIRS),
                STEP_TIMEOUT_TICKS).server("selector-remote: CHECK the stairs", (server, context) -> {
                    Map<ItemKey, Long> out = ledger().sentTo(SAW_ADDRESS);
                    if (!out.equals(Map.of(COBBLED, (long) ORDER)))
                        throw new VisualTestException("for the stairs " + out + " went out");
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS {} stairs back in {} boxes; slabs now {}", ORDER,
                            ledger().tripsSince(0).size(), controller(server.overworld())
                                    .waitsForSelector(order(server, SLAB)));
                });
        // The slab box flies: a real save, quit and rejoin with it in the air.
        script.serverUntil("selector-remote: the slab box is in a bee", (server, context) -> {
            ledger().check();
            return slabBoxInFlight();
        }, STEP_TIMEOUT_TICKS);
        script.server("selector-remote: CHECK the flying box's slip before the rejoin", (server, context) -> {
            if (!slabBoxInFlight())
                throw new VisualTestException("the slab box landed before the rejoin");
            rejoinTrips = ledger().markTrips();
            pauseWatchSuspended = true; // the load gives every order a fresh deadline (watch e)
            ledger().followNextWorld();
            ledger().allowReturnsHome();
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS a bee carries the slab box with its slip for slabs; "
                    + "save, quit and rejoin now");
        });
        VisualWorld.reload(script, worldProfile());
        script.server("selector-remote: follow the ledger into the loaded world", (server, context) -> {
            if (ledger().portEntity(at(SEND_PORT)) == null)
                throw new VisualTestException("the bee ledger did not follow the world loaded again");
            RoboBeeLedger.overrideBeeSpeed(BEE_SPEED);
            lastFactoryFilter = null;
            pausedSince.clear();
            leftPause.clear();
            pauseWatchSuspended = false;
        }).waitTicks(SETTLE_TICKS).server("selector-remote: CHECK the slab box's slip after the rejoin",
                (server, context) -> {
                    ledger().check();
                    WarehouseProductionBlockEntity station = station(server.overworld());
                    if (station.selectorMode() != SelectorMode.SLIPS || station.selectorSlipsTrusted())
                        throw new VisualTestException("after the rejoin the station is " + station.selectorMode()
                                + " trusted=" + station.selectorSlipsTrusted());
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS after the rejoin the station is in slip mode and "
                            + "no longer trusts its slips; slab box in the air: {}", slabBoxInFlight());
                });
        worldShot(script, HALL_VIEW, "remote-rejoined");
        sawShot(script, SLAB, "remote-saw-slabs");
        script.serverUntil("selector-remote: the slab order is complete", (server, context) -> complete(server, SLAB),
                STEP_TIMEOUT_TICKS);
        // Walls after the rejoin: the probe again, one run, then the hand change before the second box opens.
        script.serverUntil("selector-remote: the walls probe's wall is back", (server, context) -> {
            ledger().check();
            return order(server, WALL).produced() > 0;
        }, STEP_TIMEOUT_TICKS).server("selector-remote: CHECK the walls probe and change the factory saw's filter by "
                + "hand (declared)", (server, context) -> {
                    Map<ItemKey, Long> out = ledger().sentTo(SAW_ADDRESS);
                    long walls = out.getOrDefault(COBBLED, 0L) - ORDER - ORDER / 2;
                    if (walls != 1)
                        throw new VisualTestException("before the walls probe's wall came back " + walls
                                + " cobbled deepslate went out for walls");
                    SawBlockEntity saw = factorySaw(server.overworld());
                    if (!saw.inventory.isEmpty() || !isEmpty(handler(server.overworld(), at(FACTORY.offset(F_IN)))))
                        throw new VisualTestException("the factory line is not empty between the wall boxes");
                    handChangeDeclared = true;
                    FilteringBehaviour filter = BlockEntityBehaviour.get(saw, FilteringBehaviour.TYPE);
                    if (!Objects.requireNonNull(filter, "saw filter").setFilter(new ItemStack(Items.POLISHED_DEEPSLATE)))
                        throw new VisualTestException("the saw refused the hand change");
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS the walls probe sent one run after the rejoin; "
                            + "the factory saw's filter changed to polished deepslate between the two wall boxes "
                            + "(server-side, the player is at the warehouse)");
                });
        script.serverUntil("selector-remote: the next wall box set the saw back to walls before it opened",
                (server, context) -> {
                    ledger().check();
                    return sawFilter(factorySaw(server.overworld())) == Items.DEEPSLATE_TILE_WALL;
                }, STEP_TIMEOUT_TICKS).server("selector-remote: CHECK the set right", (server, context) -> {
                    handChangeDeclared = false;
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS the next box's slip set the factory saw to walls "
                            + "again before Create unpacked it");
                });
        sawShot(script, WALL, "remote-saw-walls");
        script.serverUntil("selector-remote: the walls order is complete", (server, context) -> complete(server, WALL),
                STEP_TIMEOUT_TICKS);
    }

    /** One stock click on {@code key} with the terminal screen open; the new order is remembered. */
    private void clickOrder(VisualScript script, ItemKey key) {
        script.client("selector-remote: click " + key, context -> TerminalOrder.clickCell(context, NAME, key));
        script.serverUntil("selector-remote: the " + key + " order exists", (server, context) -> {
            for (ProductionOrder<ItemKey, RackPosition> order : controller(server.overworld()).productionOrders())
                if (order.result().equals(key) && order.isOpen() && !orders.containsValue(order.id())) {
                    orders.put(key, order.id());
                    return true;
                }
            return false;
        }, SYNC_TIMEOUT_TICKS).waitTicks(SETTLE_TICKS);
    }

    private void sawShot(VisualScript script, ItemKey result, String label) {
        script.serverUntil("selector-remote: the factory saw cuts for " + result, (server, context) -> {
            ledger().check();
            SawBlockEntity saw = factorySaw(server.overworld());
            return !saw.inventory.isEmpty() && sawFilter(saw) == result.getItem();
        }, STEP_TIMEOUT_TICKS);
        worldShot(script, SAW_VIEW, label);
    }

    private boolean slabBoxInFlight() {
        for (ItemStack box : ledger().boxesInFlight())
            if (SAW_ADDRESS.equals(PackageItem.getAddress(box))
                    && RecipeSlip.read(box).flatMap(RecipeSlip::item).filter(Items.DEEPSLATE_TILE_SLAB::equals).isPresent())
                return true;
        return false;
    }

    // =================================================================================================================
    // act 5: the end
    // =================================================================================================================

    private void end(VisualScript script) {
        script.serverUntil("selector-remote: every order complete, the line empty and every bee home",
                (server, context) -> complete(server, STAIRS, SLAB, WALL) && quiet(server) && ledger().inFlight() == 0,
                STEP_TIMEOUT_TICKS).server("selector-remote: CHECK the end", this::checkEnd);
        script.client("selector-remote: CHECK the slip tooltip of a stamped box", context -> {
            ItemStack box = PackageItem.containing(List.of(COBBLED.toStack(1)));
            RecipeSlip.stamp(box, RecipeSlip.of(Items.DEEPSLATE_TILE_WALL, SAW_ADDRESS,
                    net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, origin.offset(STATION)), 1L));
            List<String> lines = box.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(
                    context.minecraft().level), context.minecraft().player, TooltipFlag.NORMAL).stream()
                    .map(Component::getString).toList();
            String wall = Items.DEEPSLATE_TILE_WALL.getDescription().getString();
            if (lines.stream().noneMatch(line -> line.contains(wall)))
                throw new VisualTestException("the box's tooltip does not name its slip's result: " + lines);
            LOGGER.info(PREFIX + "selector-remote: CHECK PASS a stamped box's tooltip shows its slip: {}", lines);
        });
    }

    private void checkEnd(MinecraftServer server, VisualContext context) {
        RoboBeeLedger running = ledger();
        running.check();
        ServerLevel level = server.overworld();
        WarehouseControllerBlockEntity controller = controller(level);
        Map<ItemKey, Long> back = running.arrivedFor(WAREHOUSE_ADDRESS);
        if (!back.equals(Map.of(STAIRS, (long) ORDER, SLAB, (long) ORDER, WALL, (long) ORDER)))
            throw new VisualTestException("back at the warehouse arrived " + back);
        if (!running.sentTo(SAW_ADDRESS).equals(Map.of(COBBLED, (long) (ORDER + ORDER / 2 + ORDER))))
            throw new VisualTestException("to the factory went " + running.sentTo(SAW_ADDRESS));
        for (String address : List.of(SAW_ADDRESS, WAREHOUSE_ADDRESS))
            if (!running.sentTo(address).equals(running.arrivedFor(address)))
                throw new VisualTestException("for '" + address + "' " + running.sentTo(address) + " left, "
                        + running.arrivedFor(address) + " arrived");
        if (running.openTrips() != 0)
            throw new VisualTestException(running.openTrips() + " bee trips never arrived");
        for (ItemKey key : List.of(STAIRS, SLAB, WALL))
            if (finished.getOrDefault(key, -1L) != ORDER)
                throw new VisualTestException("the " + key + " order produced " + finished.get(key));
        for (ItemKey other : otherResults)
            if (controller.countOf(other) != 0)
                throw new VisualTestException("the warehouse stores " + controller.countOf(other) + " " + other);
        Map<String, Integer> home = new LinkedHashMap<>();
        for (RoboBeeLedger.Port port : running.ports())
            home.put(port.name(), running.beesIn(port.pos()));
        if (!home.equals(Map.of("warehouse send", WAREHOUSE_BEES, "warehouse receive", 0, "factory receive", 0,
                "factory send", FACTORY_BEES)))
            throw new VisualTestException("the bees are " + home);
        if (!forced.allStillForced(level))
            throw new VisualTestException("a site lost its forced ticket: " + forced.chunks());
        expected = SceneItemCensus.plus(SceneItemCensus.plus(SceneItemCensus.plus(SceneItemCensus.plus(expected,
                COBBLED, -(ORDER + ORDER / 2 + ORDER)), STAIRS, ORDER), SLAB, ORDER), WALL, ORDER);
        assertCensus(server, "the end");
        for (RoboBeeLedger.Trip trip : running.returnedHome())
            if (trip.departed > 0 && !SAW_ADDRESS.equals(trip.address))
                throw new VisualTestException("a box for '" + trip.address + "' came back home");
        if (!running.returnedHome().isEmpty())
            deviations.add(running.returnedHome().size() + " box(es) for '" + SAW_ADDRESS + "' came back to the "
                    + "warehouse send port after the rejoin (CMP sends a bee home it cannot route at load) and were sent "
                    + "again; their slips were intact and the factory accepted them");
        LOGGER.info(PREFIX + "selector-remote: CHECK PASS {} trips ({} after the probe, {} after the rejoin); back {}; "
                + "the factory filter changed {} times, each when a box with a slip was accepted on an empty line, "
                + "plus the declared hand change; slips seen on the station's boxes {}; bees home {}; none of the {} "
                + "other results", running.trips().size(), running.tripsSince(Math.max(0, probeTrips)).size(),
                running.tripsSince(rejoinTrips).size(), SceneItemCensus.describe(back), factoryFilterChanges,
                slipsSeen, home, otherResults.size());
    }

    // =================================================================================================================
    // goggles
    // =================================================================================================================

    private void goggles(VisualScript script) {
        script.client("selector-remote: clear the toasts", context -> context.minecraft().getToasts().clear());
        gogglePair(script, "");
        language.switchTo(script, "selector-remote: ", GERMAN);
        gogglePair(script, "-de");
        language.switchTo(script, "selector-remote: ", ENGLISH);
    }

    private void gogglePair(VisualScript script, String suffix) {
        GoggleShots.shot(script, NAME, STATION_GOGGLES, "goggles-station" + suffix, dock -> dock.offset(STATION),
                context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(STATION));
                    String header = WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_SELECTOR_SLIPS)
                            .getString();
                    GoggleShots.requireLine(lines, header);
                    requireTranslated(context, "station", header);
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS the station's goggles: {}", lines);
                });
        GoggleShots.shot(script, NAME, ENDER_GOGGLES, "goggles-ender" + suffix,
                dock -> dock.offset(FACTORY).offset(F_SELECTOR), context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(FACTORY).offset(F_SELECTOR));
                    String header = WareworksLang.translateDirect(WareworksLang.GOGGLES_ENDER_RECIPE_SELECTOR)
                            .getString();
                    GoggleShots.requireLine(lines, header);
                    requireTranslated(context, "ender", header);
                    LOGGER.info(PREFIX + "selector-remote: CHECK PASS the Ender selector's goggles: {}", lines);
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
            throw new VisualTestException("'" + line + "' is no " + selected + " translation of '" + english + "'");
        LOGGER.info(PREFIX + "selector-remote: CHECK PASS '{}' is the {} line of '{}'", line, selected, english);
    }

    // =================================================================================================================
    // the watch (server thread, every tick from the baseline, through the ledger)
    // =================================================================================================================

    private void watchTick(ServerLevel level) {
        BlockPos dock = origin;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        if (controller == null || !(level.getBlockEntity(dock.offset(STATION)) instanceof WarehouseProductionBlockEntity
                station) || !(level.getBlockEntity(dock.offset(STATION_PACKAGER)) instanceof PackagerBlockEntity packager)
                || !(level.getBlockEntity(dock.offset(FACTORY).offset(F_SAW)) instanceof SawBlockEntity saw))
            return;
        long now = level.getGameTime();
        // (a) every box the station's Packager holds carries the slip of the selection.
        if (!packager.heldBox.isEmpty()) {
            Optional<Item> slip = RecipeSlip.read(packager.heldBox).flatMap(RecipeSlip::item);
            Optional<Item> selection = station.selection().map(ItemKey::getItem);
            if (slip.isPresent() && !slip.equals(selection))
                throw new VisualTestException("the station's box carries a slip for " + slip + " while the station is "
                        + "set to " + selection);
            // A station in slip mode with a selection stamps every box it makes, in the tick it is made.
            if (slip.isEmpty() && station.selectorMode() == SelectorMode.SLIPS && selection.isPresent())
                throw new VisualTestException("the station's box carries no slip while the station sends slips for "
                        + selection);
            if (packager.animationTicks == PackagerBlockEntity.CYCLE - 1 || slip.isPresent())
                slip.ifPresent(item -> slipsSeen.merge(ItemKey.of(item), 1, Integer::sum));
        }
        // (b) the factory filter changes only when a box with a slip was accepted this tick, on an empty saw, chute and
        // chest (the chest as it was before the box was unpacked into it).
        Item filter = sawFilter(saw);
        boolean feedEmpty = isEmpty(handler(level, dock.offset(FACTORY).offset(F_IN)))
                && level.getBlockEntity(dock.offset(FACTORY).offset(F_CHUTE)) instanceof ChuteBlockEntity chute
                && chute.getItem().isEmpty();
        if (lastFactoryFilter != null && filter != lastFactoryFilter) {
            RecipeSelectorBlockEntity selector = level.getBlockEntity(dock.offset(FACTORY).offset(F_SELECTOR))
                    instanceof RecipeSelectorBlockEntity found ? found : null;
            boolean declared = handChangeDeclared && filter == Items.POLISHED_DEEPSLATE;
            boolean bySlip = selector != null && selector.lastSlipTick() == now;
            if (!declared && !bySlip)
                throw new VisualTestException("the factory saw's filter changed " + lastFactoryFilter + " -> " + filter
                        + " outside a box's acceptance (last slip at " + (selector == null ? "?" : selector.lastSlipTick())
                        + ", now " + now + ")");
            if (!saw.inventory.isEmpty())
                throw new VisualTestException("the factory saw's filter changed while it held "
                        + saw.inventory.getStackInSlot(0));
            if (!factoryFeedWasEmpty)
                throw new VisualTestException("the factory saw's filter changed " + lastFactoryFilter + " -> " + filter
                        + " while the chest or the chute above it still held items");
            factoryFilterChanges++;
            LOGGER.info(PREFIX + "selector-remote: the factory saw's filter changed {} -> {} at tick {} ({})",
                    lastFactoryFilter, filter, now, declared ? "declared hand change" : "box accepted");
        }
        lastFactoryFilter = filter;
        factoryFeedWasEmpty = feedEmpty;
        // (e) a queued order's deadline does not run (design §15.3, re-review fix): an order that leaves the selector's
        // pause starts over with a whole deadline, however long it waited.
        if (!pauseWatchSuspended)
            watchPausedDeadlines(controller, now);
        // (c) no wall ingredient asked for before the slab order is complete; (d) no order ends badly.
        UUID slabs = orders.get(SLAB);
        UUID walls = orders.get(WALL);
        boolean slabsComplete = finished.containsKey(SLAB) || slabs != null && controller.productionOrder(slabs)
                .filter(order -> order.state() == ProductionOrderState.COMPLETE).isPresent();
        Map<UUID, ProductionOrder<ItemKey, RackPosition>> byLine = new HashMap<>();
        for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders())
            for (SupplyLine<ItemKey> line : order.lines())
                byLine.put(line.id(), order);
        for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : controller.supplyNeeds()) {
            ProductionOrder<ItemKey, RackPosition> order = byLine.get(need.id());
            if (order != null && order.id().equals(walls) && !slabsComplete)
                throw new VisualTestException("the crane is asked for wall ingredients before the slab order is "
                        + "complete");
        }
        StringBuilder status = new StringBuilder("orders=");
        for (ProductionOrder<ItemKey, RackPosition> order : controller.productionOrders()) {
            if (!orders.containsValue(order.id()) && orders.size() == 3)
                throw new VisualTestException("an order the clicks did not make appeared: " + order);
            if (order.state() == ProductionOrderState.TIMED_OUT || order.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the " + order.result() + " order ended " + order.state());
            if (order.state() == ProductionOrderState.COMPLETE && orders.get(order.result()) != null
                    && orders.get(order.result()).equals(order.id()))
                finished.putIfAbsent(order.result(), order.produced());
            SelectorWait wait = order.isOpen() ? controller.waitsForSelector(order) : SelectorWait.NONE;
            status.append(order.result().getItem().toString().replace("minecraft:", "")).append(':')
                    .append(order.state()).append('/').append(order.produced()).append('/').append(wait).append(' ');
        }
        for (ItemKey other : otherResults)
            if (controller.countOf(other) > 0)
                throw new VisualTestException("the warehouse stores " + other);
        RoboBeeLedger running = ledger;
        if (running != null)
            status.append("bees=").append(running.inFlight()).append(" trips=").append(running.trips().size());
        serverStatus = status.toString().trim() + " factory-filter=" + filter;
    }

    /**
     * Watch (e): every order the Recipe Selector holds back is followed; once it has left the pause for
     * {@value #DEADLINE_CHECK_DELAY_TICKS} ticks and is still open, its deadline must lie a whole timeout (less
     * {@value #DEADLINE_SLACK_TICKS} ticks) after the tick it left. A deadline that ran during the pause would lie that
     * much earlier.
     */
    private void watchPausedDeadlines(WarehouseControllerBlockEntity controller, long now) {
        Set<UUID> paused = controller.selectorPausedOrders();
        for (UUID id : paused) {
            long[] left = leftPause.remove(id);
            if (left != null)
                pausedSince.put(id, left[0]); // back in the pause before it was looked at: still the same wait
            else
                pausedSince.putIfAbsent(id, now);
        }
        for (UUID id : List.copyOf(pausedSince.keySet())) {
            if (!paused.contains(id))
                leftPause.put(id, new long[] {pausedSince.remove(id), now});
        }
        for (UUID id : List.copyOf(leftPause.keySet())) {
            long[] left = leftPause.get(id);
            if (now < left[1] + DEADLINE_CHECK_DELAY_TICKS)
                continue;
            leftPause.remove(id);
            Optional<ProductionOrder<ItemKey, RackPosition>> order = controller.productionOrder(id);
            if (order.isEmpty() || !order.get().isOpen())
                continue;
            long pausedFor = left[1] - left[0];
            long timeout = WareworksConfig().productionOrderTimeoutTicks.get();
            if (order.get().deadlineTick() < left[1] + timeout - DEADLINE_SLACK_TICKS)
                throw new VisualTestException("the " + order.get().result() + " order left the selector's pause after "
                        + pausedFor + " ticks with its deadline at " + order.get().deadlineTick() + ", not a whole "
                        + "timeout after tick " + left[1] + ": its deadline ran while it waited");
            if (pausedFor >= LONG_PAUSE_TICKS) {
                longPausesLeft++;
                longestPause = Math.max(longestPause, pausedFor);
                LOGGER.info(PREFIX + "selector-remote: CHECK PASS the {} order left a selector pause of {} ticks with a "
                        + "fresh deadline ({} ticks left)", order.get().result(), pausedFor,
                        order.get().deadlineTick() - now);
            }
        }
    }

    private boolean complete(MinecraftServer server, ItemKey... results) {
        ledger().check();
        for (ItemKey result : results) {
            if (finished.containsKey(result))
                continue;
            ProductionOrder<ItemKey, RackPosition> order = order(server, result);
            if (order.state() == ProductionOrderState.TIMED_OUT || order.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the " + result + " order ended " + order.state());
            if (order.state() != ProductionOrderState.COMPLETE)
                return false;
            finished.put(result, order.produced());
        }
        return true;
    }

    private boolean quiet(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, at(FACTORY.offset(F_BELT_START)));
        return factorySaw(level).inventory.isEmpty() && (belt == null || belt.getInventory().getTransportedItems()
                .isEmpty()) && isEmpty(handler(level, at(FACTORY.offset(F_IN))))
                && isEmpty(handler(level, at(FACTORY.offset(F_OUT))))
                && packager(level, at(STATION_PACKAGER)).heldBox.isEmpty()
                && packager(level, at(FACTORY.offset(F_PACKER))).heldBox.isEmpty()
                && packager(level, at(RETURN_PACKAGER)).heldBox.isEmpty()
                && packager(level, at(FACTORY.offset(F_UNPACKER))).heldBox.isEmpty()
                && !station(level).hasBufferedItems() && controller(level).requestsFor(at(TERMINAL)).isEmpty()
                && level.getBlockEntity(at(RETURN_INPUT))
                instanceof WarehouseInputBlockEntity input && !input.hasBufferedItems();
    }

    // =================================================================================================================
    // census, shots, lookups
    // =================================================================================================================

    private Map<ItemKey, Long> census(MinecraftServer server) {
        return MultiSiteCensus.take(server.overworld(), server.getPlayerList().getPlayers().getFirst(), ledger,
                List.of(warehouseBox, factoryBox));
    }

    private void assertCensus(MinecraftServer server, String moment) {
        Map<ItemKey, Long> actual = census(server);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated at " + moment + ": "
                    + SceneItemCensus.describe(actual) + ", expected " + SceneItemCensus.describe(expected));
        LOGGER.info(PREFIX + "selector-remote: census PASS: {} ({})", moment, SceneItemCensus.describe(actual));
    }

    private void worldShot(VisualScript script, CameraView view, String label) {
        GoggleShots.reach(script, NAME, 0.0);
        RealClicks.fly(script, NAME);
        script.camera(view).shot(label);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    private RoboBeeLedger ledger() {
        RoboBeeLedger running = ledger;
        if (running == null)
            throw new VisualTestException("the bee ledger has not started");
        return running;
    }

    private ProductionOrder<ItemKey, RackPosition> order(MinecraftServer server, ItemKey result) {
        UUID id = Objects.requireNonNull(orders.get(result), "no order for " + result);
        return controller(server.overworld()).productionOrder(id)
                .orElseThrow(() -> new VisualTestException("the controller forgot the " + result + " order"));
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

    private WarehouseProductionBlockEntity station(ServerLevel level) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                at(STATION));
        if (station == null)
            throw new VisualTestException("the production station is missing");
        return station;
    }

    private RecipeSelectorBlockEntity enderSelector(ServerLevel level) {
        if (level.getBlockEntity(at(FACTORY.offset(F_SELECTOR))) instanceof RecipeSelectorBlockEntity selector)
            return selector;
        throw new VisualTestException("no Ender Recipe Selector on the factory Packager");
    }

    private SawBlockEntity factorySaw(ServerLevel level) {
        if (level.getBlockEntity(at(FACTORY.offset(F_SAW))) instanceof SawBlockEntity saw)
            return saw;
        throw new VisualTestException("the factory saw is missing");
    }

    private static PackagerBlockEntity packager(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PackagerBlockEntity packager)
            return packager;
        throw new VisualTestException("the Packager at " + pos + " is missing");
    }

    private static Item sawFilter(SawBlockEntity saw) {
        FilteringBehaviour filter = BlockEntityBehaviour.get(saw, FilteringBehaviour.TYPE);
        return filter == null ? Items.AIR : filter.getFilter().getItem();
    }

    private static boolean isEmpty(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (!handler.getStackInSlot(slot).isEmpty())
                return false;
        return true;
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
    private static ClientLevel clientLevel(VisualContext context) {
        return Objects.requireNonNull(context.minecraft().level, "level");
    }
}
