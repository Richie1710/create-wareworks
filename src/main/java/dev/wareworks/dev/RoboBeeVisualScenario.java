package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

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

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlock;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

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
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseCrafterFeederBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "robobee": production in two <b>remote, chunk-loaded factories</b> reached by Create Mobile Packages' Robo
 * Bees, end to end and the way a player builds and orders it (owner request 2026-11-09). Runs only with
 * {@code -Pwareworks.cmp} (ADR-074); without CMP it fails at once.
 * <p>
 * <b>The warehouse.</b> A powered aisle running east. On its left wall the storage barrels and, on the top level, an
 * input fed by a chute from a chest full of cobblestone (27 x 64). On its right wall the terminal; a production station
 * <b>Crushing</b> (cobblestone makes gravel, gravel makes sand) and a station <b>Melting</b> (sand makes glass), each
 * with a steadily powered Create Packager on its back and a sign naming its factory, and between the two Packagers the
 * warehouse's <b>one send port</b>; the glass pane station with a Warehouse Crafter Feeder and a vanilla Crafter whose
 * output faces a warehouse input; and the <b>return</b> input with an unpacking Packager on its back and the warehouse's
 * <b>one receive port</b> (powered by a lever) behind that.
 * <p>
 * <b>The factories</b> ({@value #CRUSHING_DISTANCE} blocks north and {@value #MELTING_DISTANCE} blocks east, force-loaded
 * for the whole run like the warehouse), each with one powered receive port feeding an unpacking Packager and a chest,
 * one line (crushing wheels behind chutes; a fan blowing through a lit Blaze Burner over a slow belt), an output chest
 * with a steadily powered Packager signed "{@value #WAREHOUSE_ADDRESS}" and one send port.
 * <p>
 * <b>Real clicks</b> where a player clicks: the six ports placed from one stack and tuned on the first; the receive
 * ports' addresses typed into their screens; the bees put into the send ports' bee slots and their return mode
 * switched on; the four signs written; the three levers flipped; the burner lit with a Creative Blaze Cake; the four
 * patterns written; and the order of {@value #ORDER} glass panes with one click at the terminal. Everything else is
 * server-built scaffolding, as in every scenario.
 * <p>
 * <b>Checked</b>: one click plans the four-step chain; every bee trip out and in with its address (a misdelivery fails
 * at once); the sums per address; the bees conserved and all home at the end; no order duplicated, timed out or
 * cancelled and no safety stop; the largest progress gap of each order against the default 6000-tick deadline; every
 * order complete with its amounts; {@value #ORDER} panes delivered, the by-products stored; the item census of both
 * sites, the player and every box in flight ({@link RoboBeeLedger}) at every moment. Grep
 * {@code robobee: ALL CHECKS PASSED in English and German}.
 */
public final class RoboBeeVisualScenario implements VisualScenario {
    public static final String NAME = "robobee";

    private static final String WORLD_FOLDER = "wareworks_visual_robobee";
    /** Setup with 40-odd real clicks, a 27-trip intake, a four-step chain with six bee legs, two language switches. */
    private static final long RUN_TIMEOUT_MILLIS = 40L * 60L * 1000L;

    // --- addresses ---------------------------------------------------------------------------------------------------
    private static final String CRUSHING_ADDRESS = "Crushing";
    private static final String MELTING_ADDRESS = "Melting";
    private static final String WAREHOUSE_ADDRESS = "Warehouse";

    // --- the warehouse (offsets from the dock; the aisle runs east, so the right rack side is +z) --------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 8;
    private static final int CRANE_RPM = 256;
    /** The feeder lays a run every {@code max(8, 256 / 32)} ticks. */
    private static final int FEEDER_RPM = 32;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition CRUSHING_RACK = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition MELTING_RACK = RackPosition.of(4, 0, Side.RIGHT);
    private static final RackPosition PANES_RACK = RackPosition.of(5, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_INPUT_RACK = RackPosition.of(6, 0, Side.RIGHT);
    private static final RackPosition RETURN_RACK = RackPosition.of(7, 0, Side.RIGHT);
    private static final RackPosition INTAKE_RACK = RackPosition.of(8, 3, Side.LEFT);
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(1, 0, Side.LEFT),
            RackPosition.of(2, 0, Side.LEFT), RackPosition.of(3, 0, Side.LEFT), RackPosition.of(4, 0, Side.LEFT));

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);
    private static final BlockPos CRUSHING_STATION = new BlockPos(2, 0, 1);
    private static final BlockPos MELTING_STATION = new BlockPos(4, 0, 1);
    private static final BlockPos PANES_STATION = new BlockPos(5, 0, 1);
    private static final BlockPos CRAFTER_INPUT = new BlockPos(6, 0, 1);
    private static final BlockPos RETURN_INPUT = new BlockPos(7, 0, 1);
    private static final BlockPos INTAKE_INPUT = new BlockPos(8, 3, -1);
    private static final BlockPos INTAKE_CHUTE = INTAKE_INPUT.above();
    private static final BlockPos INTAKE_CHEST = INTAKE_CHUTE.above();
    /** The packing Packagers on the two stations' backs, each on a redstone block (steady power). */
    private static final BlockPos CRUSHING_PACKAGER = CRUSHING_STATION.south();
    private static final BlockPos MELTING_PACKAGER = MELTING_STATION.south();
    private static final BlockPos CRUSHING_SIGN = CRUSHING_PACKAGER.south();
    private static final BlockPos MELTING_SIGN = MELTING_PACKAGER.south();
    /** The warehouse's one send port, between the two packing Packagers. */
    private static final BlockPos SEND_PORT = new BlockPos(3, 0, 2);
    private static final BlockPos FEEDER = PANES_STATION.south();
    private static final BlockPos FEEDER_MOTOR = FEEDER.above();
    private static final BlockPos CRAFTER = FEEDER.relative(AISLE);
    /** The unpacking Packager on the return input's back, and the warehouse's one receive port behind it. */
    private static final BlockPos RETURN_PACKAGER = RETURN_INPUT.south();
    private static final BlockPos RECEIVE_PORT = RETURN_PACKAGER.south();
    private static final BlockPos RECEIVE_LEVER = RECEIVE_PORT.east();
    private static final BlockPos LIFT = new BlockPos(4, 6, -4);

    // --- the crushing factory (offsets from its origin) -------------------------------------------------------------
    private static final int CRUSHING_DISTANCE = 72;
    private static final int MELTING_DISTANCE = 96;
    private static final BlockPos CRUSHING_FACTORY = new BlockPos(0, 0, -CRUSHING_DISTANCE);
    private static final BlockPos C_OUT = BlockPos.ZERO;
    private static final BlockPos C_LOW_CHUTE = new BlockPos(0, 1, 0);
    private static final BlockPos C_CONTROLLER = new BlockPos(0, 2, 0);
    private static final BlockPos C_WHEEL_NORTH = new BlockPos(0, 2, -1);
    private static final BlockPos C_WHEEL_SOUTH = new BlockPos(0, 2, 1);
    private static final BlockPos C_MOTOR_NORTH = new BlockPos(-1, 2, -1);
    private static final BlockPos C_MOTOR_SOUTH = new BlockPos(-1, 2, 1);
    private static final BlockPos C_HIGH_CHUTE = new BlockPos(0, 3, 0);
    private static final BlockPos C_IN = new BlockPos(0, 4, 0);
    private static final BlockPos C_UNPACKER = new BlockPos(1, 4, 0);
    private static final BlockPos C_RECEIVE = new BlockPos(2, 4, 0);
    private static final BlockPos C_PILLAR = new BlockPos(2, 3, 0);
    private static final BlockPos C_LEVER = new BlockPos(3, 3, 0);
    private static final BlockPos C_PACKER = new BlockPos(1, 0, 0);
    private static final BlockPos C_SEND = new BlockPos(2, 0, 0);
    private static final BlockPos C_SIGN = new BlockPos(1, 0, 1);
    private static final int WHEEL_RPM = 256;

    // --- the melting factory (offsets from its origin) --------------------------------------------------------------
    private static final BlockPos MELTING_FACTORY = new BlockPos(MELTING_DISTANCE, 0, 0);
    private static final BlockPos M_IN = new BlockPos(0, 2, 0);
    private static final BlockPos M_CHUTE = new BlockPos(0, 1, 0);
    private static final BlockPos M_BELT_START = BlockPos.ZERO;
    private static final BlockPos M_BELT_END = new BlockPos(9, 0, 0);
    private static final BlockPos M_BELT_MOTOR = new BlockPos(0, 0, -1);
    private static final BlockPos M_FAN_MOTOR = new BlockPos(1, 1, 0);
    private static final BlockPos M_FAN = new BlockPos(2, 1, 0);
    private static final BlockPos M_BURNER = new BlockPos(3, 1, 0);
    private static final BlockPos M_FUNNEL = M_BELT_END.above();
    private static final BlockPos M_OUT = M_FUNNEL.east();
    private static final BlockPos M_PACKER = M_OUT.east();
    private static final BlockPos M_SEND = M_PACKER.east();
    private static final BlockPos M_SEND_BASE = M_SEND.below();
    private static final BlockPos M_SIGN = M_PACKER.south();
    private static final BlockPos M_UNPACKER = new BlockPos(-1, 2, 0);
    private static final BlockPos M_RECEIVE = new BlockPos(-2, 2, 0);
    private static final BlockPos M_PILLAR = new BlockPos(-2, 1, 0);
    private static final BlockPos M_LEVER = new BlockPos(-3, 1, 0);
    /** At 8 RPM a belt item takes 60 ticks per block: six blocks in the current, 360 ticks, a 16-stack needs 151. */
    private static final int BELT_RPM = 8;
    private static final int FAN_RPM = 64;

    // --- items and amounts ---------------------------------------------------------------------------------------------
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey GRAVEL = ItemKey.of(Items.GRAVEL);
    private static final ItemKey SAND = ItemKey.of(Items.SAND);
    private static final ItemKey GLASS = ItemKey.of(Items.GLASS);
    private static final ItemKey PANE = ItemKey.of(Items.GLASS_PANE);
    private static final ItemKey FLINT = ItemKey.of(Items.FLINT);
    private static final ItemKey CLAY = ItemKey.of(Items.CLAY_BALL);
    private static final List<ItemKey> CHAIN = List.of(COBBLE, GRAVEL, SAND, GLASS, PANE);
    private static final List<ItemKey> BY_PRODUCTS = List.of(FLINT, CLAY);
    private static final int INTAKE = 27 * 64;
    private static final int ORDER = 512;
    private static final int PANES_PER_RUN = 16;
    private static final int GLASS_PER_RUN = 6;
    private static final int ROOT_RUNS = ORDER / PANES_PER_RUN;
    private static final int STEP_AMOUNT = ROOT_RUNS * GLASS_PER_RUN;
    /** The chain needs 4 x 192 ingredient items; the default cap of 256 would grant only 160 panes. */
    private static final int PLAN_INGREDIENT_CAP = 1024;
    private static final int DEFAULT_TIMEOUT_TICKS = 6000;

    // --- bees ----------------------------------------------------------------------------------------------------------
    private static final int PORTS = 6;
    private static final int WAREHOUSE_BEES = 16;
    private static final int FACTORY_BEES = 32;
    private static final int BEES = WAREHOUSE_BEES + 2 * FACTORY_BEES;

    // --- hotbar --------------------------------------------------------------------------------------------------------
    private static final int SLOT_PORTS = 0;
    private static final int SLOT_WAREHOUSE_BEES = 1;
    private static final int SLOT_CRUSHING_BEES = 2;
    private static final int SLOT_MELTING_BEES = 3;
    private static final int SLOT_SIGNS = 4;
    private static final int SLOT_CAKE = 5;
    private static final int SLOT_EMPTY = 8;
    private static final int SLOT_COBBLE = 0;
    private static final int SLOT_GRAVEL = 1;
    private static final int SLOT_SAND = 2;
    private static final int SLOT_GLASS = 3;
    private static final int SLOT_PANE = 4;

    // --- patterns -------------------------------------------------------------------------------------------------------
    private static final int TOP_LEFT = 0;
    private static final int FIRST_PATTERN = 0;
    private static final int SECOND_PATTERN = 1;

    // --- areas -----------------------------------------------------------------------------------------------------------
    private static final BlockPos WAREHOUSE_MIN = new BlockPos(-3, -1, -4);
    private static final BlockPos WAREHOUSE_MAX = new BlockPos(RAILS + 2, 6, 5);
    private static final BlockPos CRUSHING_MIN = new BlockPos(-2, -1, -2);
    private static final BlockPos CRUSHING_MAX = new BlockPos(4, 5, 2);
    private static final BlockPos MELTING_MIN = new BlockPos(-4, -1, -2);
    private static final BlockPos MELTING_MAX = new BlockPos(14, 3, 2);
    private static final int CLEAR_HEIGHT = 8;

    // --- timing (client ticks) --------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int INTAKE_TIMEOUT_TICKS = 4800;
    private static final int STEP_TIMEOUT_TICKS = 10_000;
    private static final int ROOT_TIMEOUT_TICKS = 6000;
    private static final int QUIET_TIMEOUT_TICKS = 600;
    private static final int MOMENT_TIMEOUT_TICKS = 2400;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    private static final double BEE_SHOT_RANGE = 6.0;
    private static final double BEE_VIEW_DEGREES = 20.0;
    /** A bee this far above the port's centre is out of the port and against the sky in the picture. */
    private static final double BEE_CLEAR_OF_PORT = 1.5;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- clicks (relative to the dock) ---------------------------------------------------------------------------------
    private static final Vec3 FROM_SOUTH = new Vec3(0.0, 2.2, 1.2);
    private static final Vec3 FROM_EAST = new Vec3(1.2, 2.0, 0.0);
    private static final Vec3 FROM_WEST = new Vec3(-1.2, 2.0, 0.0);
    private static final Vec3 ABOVE_STATION = new Vec3(0.0, 2.0, 0.6);

    // --- camera views ----------------------------------------------------------------------------------------------
    private static final CameraView WAREHOUSE_VIEW = CameraView.of("warehouse", 4.5, 6.5, 9.5, 4.0, 0.0, 1.0);
    private static final CameraView CRUSHING_VIEW = CameraView.of("crushing-factory", 6.5, 5.5,
            -CRUSHING_DISTANCE + 6.5, 0.5, 2.0, -CRUSHING_DISTANCE + 0.5);
    private static final CameraView MELTING_VIEW = CameraView.of("melting-factory", MELTING_DISTANCE + 5.0, 6.0,
            7.5, MELTING_DISTANCE + 5.0, 0.5, 0.5);
    /** From south-east of the warehouse, up past the send port against the sky, where a bee rises over it. */
    private static final CameraView SEND_PORT_VIEW = CameraView.of("send-port", 6.5, 2.5, 8.5, 3.5, 3.0, 2.5);
    /** From east of the crushing factory, across its receive port: a bee comes in from the south and lands. */
    private static final CameraView CRUSHING_PORT_VIEW = CameraView.of("crushing-port", 9.5, 6.5,
            -CRUSHING_DISTANCE + 2.5, 2.5, 5.0, -CRUSHING_DISTANCE + 0.5);
    private static final CameraView CRUSHING_STATION_GOGGLES = CameraView.of("crushing-station", 2.5, 3.1, 2.1, 2.5,
            1.0, 1.5);
    private static final CameraView CONTROLLER_GOGGLES = CameraView.of("controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);
    private static final CameraView FEEDER_VIEW = CameraView.of("feeder", 7.5, 3.0, 5.5, 5.5, 0.5, 2.0);


    // --- state -------------------------------------------------------------------------------------------------------
    @Nullable
    private volatile RoboBeeLedger ledger;
    private volatile AABB warehouseBox = new AABB(BlockPos.ZERO);
    private volatile AABB crushingBox = new AABB(BlockPos.ZERO);
    private volatile AABB meltingBox = new AABB(BlockPos.ZERO);
    private final ForcedChunks forced = new ForcedChunks(NAME);
    private final BeePorts beePorts = new BeePorts(NAME, this::ledger);
    private volatile Map<ItemKey, Long> baseline = Map.of();
    private final Map<ItemKey, Long> byProductsSeen = new HashMap<>();
    private volatile int ingredientCapBefore = -1;
    private volatile boolean motorsFlipped;
    private volatile boolean fanFlipped;
    private volatile boolean beltFlipped;
    @Nullable
    private volatile UUID network;
    /** Server thread: the plan of the one click, by result, and the request it backs. */
    private final Map<ItemKey, UUID> plan = new LinkedHashMap<>();
    @Nullable
    private volatile UUID requestId;
    private final Map<UUID, String> fingerprints = new HashMap<>();
    private final Map<UUID, Long> lastProgress = new HashMap<>();
    private final Map<UUID, Long> largestGap = new HashMap<>();
    /** Server thread, per order of the plan: ticks it was blocked by an open child, and the ticks of its milestones. */
    private final Map<UUID, Long> blockedTicks = new HashMap<>();
    private final Map<UUID, Long> firstFed = new HashMap<>();
    private final Map<UUID, Long> completedAt = new HashMap<>();
    private final Set<String> deviations = new LinkedHashSet<>();
    private volatile String serverStatus = "";
    /** Client thread: the English goggle lines, so the German ones can prove they are a translation. */
    private final Map<String, String> englishLines = new HashMap<>();
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** Creative with the vanilla reach: every port, sign, lever and screen of the story is clicked by the player. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    // =================================================================================================================
    // setup
    // =================================================================================================================

    @Override
    public void setup(VisualScript script) {
        script.client("robobee: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "robobee run"))
                .server("robobee: CHECK Create Mobile Packages is loaded and is the reviewed version", (server, context) -> {
                    String cmp = RoboBeeLedger.verify(MELTING_DISTANCE + RAILS + 8);
                    if (!RoboBeeLedger.nestedLibraryLoaded())
                        throw new VisualTestException("CMP's jar-in-jar library create_factory_abstractions is not in "
                                + "the mod list");
                    LOGGER.info(PREFIX + "robobee: CHECK PASS {} is loaded with its nested create_factory_abstractions",
                            cmp);
                })
                .server("robobee: clear the three sites and build the warehouse", this::buildWarehouse)
                .server("robobee: build the crushing factory", this::buildCrushingFactory)
                .server("robobee: build the melting factory", this::buildMeltingFactory)
                .server("robobee: force-load the warehouse and both factories", (server, context) -> forced.force(
                        server.overworld(), List.of(warehouseBox, crushingBox, meltingBox)))
                .server("robobee: the player flies, wears goggles and carries ports, bees, signs and a blaze cake",
                        this::equipPlayer)
                .serverUntil("robobee: wait until the warehouse is indexed and every machine runs", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("robobee: CHECK the config of this world", this::configure);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            worldShots(script, "robobee-scene", WAREHOUSE_VIEW, CRUSHING_VIEW, MELTING_VIEW);
            placePorts(script);
            configurePorts(script);
            writeSigns(script);
            flipLevers(script);
            lightBurner(script);
            writePatterns(script);
            intake(script);
            order(script);
            chain(script);
            end(script);
            return;
        }
        worldShots(script, "robobee-finished", WAREHOUSE_VIEW, CRUSHING_VIEW, MELTING_VIEW);
        script.server("robobee: every check passed", (server, context) -> {
            ledger().check();
            LOGGER.info(PREFIX + "robobee: deviations from the design: {}", deviations.isEmpty() ? "none" : deviations);
            LOGGER.info(PREFIX + "robobee: ALL CHECKS PASSED in English and German (6 ports from one tuned stack, "
                    + "addresses, bees and return mode set in the ports' screens, signs written, levers flipped, the "
                    + "burner lit, 4 patterns written, a chest of 1728 cobblestone stored, one click planned the "
                    + "4-step chain, every bee trip out and in at the right port, every box sent once, all bees home, "
                    + "no order duplicated, timed out or cancelled, every parent fed only after its child step, every "
                    + "progress gap under the 6000-tick deadline, 512 glass panes delivered, flint and clay stored, "
                    + "every census exact)");
        });
    }

    @Override
    public void finish(VisualScript script) {
        script.server("robobee: put the config back, release the forced chunks and stop the bee ledger",
                (server, context) -> {
                    if (ingredientCapBefore >= 0)
                        VisualConfig.setConfig(WareworksConfig.SERVER.maxPlanIngredientItems, ingredientCapBefore);
                    forced.release(server.overworld());
                    RoboBeeLedger running = ledger;
                    if (running != null)
                        running.stop();
                });
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder(serverStatus);
        ClientLevel level = context.minecraft().level;
        if (level != null && level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane) {
            CranePose pose = crane.craneState().pose();
            status.append(String.format(Locale.ROOT, " crane=%s posX=%.2f posY=%.2f held=%d",
                    crane.craneState().phase(), pose.x(), pose.y(), crane.goggleInfo().heldCount()));
        }
        Screen open = context.minecraft().screen;
        status.append(" screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected());
        return status + GoggleShots.describeHover(context);
    }

    private void configure(MinecraftServer server, VisualContext context) {
        if (WareworksConfig.SERVER.productionOrderTimeoutTicks.get() != DEFAULT_TIMEOUT_TICKS)
            throw new VisualTestException("productionOrderTimeoutTicks is "
                    + WareworksConfig.SERVER.productionOrderTimeoutTicks.get() + ", the run proves the default "
                    + DEFAULT_TIMEOUT_TICKS);
        if (WareworksConfig.SERVER.maxProductionOrders.get() < 4 || WareworksConfig.SERVER.maxProductionPlanSteps.get() < 4
                || WareworksConfig.SERVER.maxTerminalRequestAmount.get() < ORDER)
            throw new VisualTestException("the production bounds of this world are too small for the chain");
        deviations.add("owner override: two factories " + CRUSHING_DISTANCE + " and " + MELTING_DISTANCE
                + " blocks away instead of one per step, one crushing line makes both crushing steps, six ports "
                + "(warehouse one send and one receive, each factory one receive and one send)");
        ingredientCapBefore = WareworksConfig.SERVER.maxPlanIngredientItems.get();
        VisualConfig.setConfig(WareworksConfig.SERVER.maxPlanIngredientItems, PLAN_INGREDIENT_CAP);
        LOGGER.info(PREFIX + "robobee: CHECK PASS config: maxPlanIngredientItems {} -> {} for this world (the chain "
                + "needs {}), productionOrderTimeoutTicks {} (default, unchanged)", ingredientCapBefore,
                PLAN_INGREDIENT_CAP, 4 * STEP_AMOUNT, WareworksConfig.SERVER.productionOrderTimeoutTicks.get());
    }

    // --- build (server thread) -----------------------------------------------------------------------------------------

    private void buildWarehouse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        warehouseBox = AABB.encapsulatingFullBlocks(dock.offset(WAREHOUSE_MIN), dock.offset(WAREHOUSE_MAX));
        crushingBox = AABB.encapsulatingFullBlocks(dock.offset(CRUSHING_FACTORY).offset(CRUSHING_MIN),
                dock.offset(CRUSHING_FACTORY).offset(CRUSHING_MAX));
        meltingBox = AABB.encapsulatingFullBlocks(dock.offset(MELTING_FACTORY).offset(MELTING_MIN),
                dock.offset(MELTING_FACTORY).offset(MELTING_MAX));
        clear(level, dock.offset(WAREHOUSE_MIN).above(), dock.offset(WAREHOUSE_MAX).above(CLEAR_HEIGHT));

        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(dock.offset(FEEDER_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.DOWN));
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        motor(level, dock.offset(FEEDER_MOTOR)).generatedSpeed.setValue(FEEDER_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.offset(CONTROLLER),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        Map<RackPosition, BlockPos> expected = Map.of(TERMINAL_RACK, TERMINAL, CRUSHING_RACK, CRUSHING_STATION,
                MELTING_RACK, MELTING_STATION, PANES_RACK, PANES_STATION, CRAFTER_INPUT_RACK, CRAFTER_INPUT,
                RETURN_RACK, RETURN_INPUT, INTAKE_RACK, INTAKE_INPUT);
        for (Map.Entry<RackPosition, BlockPos> entry : expected.entrySet())
            if (!layout.rackPos(entry.getKey()).equals(dock.offset(entry.getValue())))
                throw new VisualTestException("rack position " + entry.getKey() + " stands at "
                        + layout.rackPos(entry.getKey()) + ", not at " + dock.offset(entry.getValue()));
        level.setBlockAndUpdate(dock.offset(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL_RACK)));
        for (RackPosition station : List.of(CRUSHING_RACK, MELTING_RACK, PANES_RACK))
            level.setBlockAndUpdate(layout.rackPos(station), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                    .setValue(WarehouseProductionBlock.FACING, inward(layout, station)));
        for (RackPosition input : List.of(CRAFTER_INPUT_RACK, RETURN_RACK, INTAKE_RACK))
            level.setBlockAndUpdate(layout.rackPos(input), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                    .setValue(WarehouseInputBlock.FACING, inward(layout, input)));
        for (RackPosition rack : STORAGE) {
            Direction outward = layout.sideDirection(rack.side());
            level.setBlockAndUpdate(layout.rackPos(rack).relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        // The player's chest full of cobblestone, emptied by a chute into the input on the top level.
        level.setBlockAndUpdate(dock.offset(INTAKE_CHEST), Blocks.CHEST.defaultBlockState());
        for (int stack = 0; stack < INTAKE / 64; stack++)
            insert(level, dock.offset(INTAKE_CHEST), COBBLE.toStack(64));
        level.setBlockAndUpdate(dock.offset(INTAKE_CHUTE), AllBlocks.CHUTE.getDefaultState());

        // The two out doors: a Packager whose back touches the station, steadily powered by a redstone block under it
        // (which touches neither the station nor the port), and the sign spot in front of it.
        Direction right = layout.sideDirection(Side.RIGHT);
        for (BlockPos packager : List.of(CRUSHING_PACKAGER, MELTING_PACKAGER)) {
            level.setBlockAndUpdate(dock.offset(packager),
                    AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, right));
            level.setBlockAndUpdate(dock.offset(packager).below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        // The glass pane machine: the feeder on the station's back facing along the wall, the Crafter in front of it,
        // its output facing the input (FeederVisualScenario's geometry).
        level.setBlockAndUpdate(dock.offset(FEEDER), WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, AISLE)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        level.setBlockAndUpdate(dock.offset(CRAFTER), Blocks.CRAFTER.defaultBlockState().setValue(
                BlockStateProperties.ORIENTATION,
                FrontAndTop.fromFrontAndTop(inward(layout, CRAFTER_INPUT_RACK), Direction.UP)));
        // The in door: an unpacking Packager (no power) whose back touches the return input; the receive port goes
        // behind it, and its lever beside the port, away from the Packager.
        level.setBlockAndUpdate(dock.offset(RETURN_PACKAGER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, right));
        level.setBlockAndUpdate(dock.offset(RECEIVE_LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false));
    }

    private void buildCrushingFactory(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = factoryOrigin(level, context, CRUSHING_FACTORY);
        clear(level, origin.offset(CRUSHING_MIN).above(), origin.offset(CRUSHING_MAX).above(CLEAR_HEIGHT));
        level.setBlockAndUpdate(origin.offset(C_OUT), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(C_LOW_CHUTE), AllBlocks.CHUTE.getDefaultState());
        level.setBlockAndUpdate(origin.offset(C_HIGH_CHUTE), AllBlocks.CHUTE.getDefaultState());
        level.setBlockAndUpdate(origin.offset(C_IN), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(C_MOTOR_NORTH),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.offset(C_MOTOR_SOUTH),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.EAST));
        motor(level, origin.offset(C_MOTOR_NORTH)).generatedSpeed.setValue(WHEEL_RPM);
        motor(level, origin.offset(C_MOTOR_SOUTH)).generatedSpeed.setValue(-WHEEL_RPM);
        for (BlockPos wheel : List.of(C_WHEEL_NORTH, C_WHEEL_SOUTH))
            level.setBlockAndUpdate(origin.offset(wheel), AllBlocks.CRUSHING_WHEEL.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        // In: an unpacking Packager (no power) on the input chest, the receive port's pillar and its lever.
        level.setBlockAndUpdate(origin.offset(C_UNPACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.offset(C_PILLAR), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(C_LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.POWERED, false));
        // Out: the return Packager on the output chest, on a redstone block.
        level.setBlockAndUpdate(origin.offset(C_PACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.offset(C_PACKER).below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
    }

    private void buildMeltingFactory(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = factoryOrigin(level, context, MELTING_FACTORY);
        clear(level, origin.offset(MELTING_MIN).above(), origin.offset(MELTING_MAX).above(CLEAR_HEIGHT));
        BlockPos start = origin.offset(M_BELT_START);
        BlockPos end = origin.offset(M_BELT_END);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the kiln belt was not created between " + start + " and " + end);
        level.setBlockAndUpdate(origin.offset(M_BELT_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        motor(level, origin.offset(M_BELT_MOTOR)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(origin.offset(M_CHUTE), AllBlocks.CHUTE.getDefaultState());
        level.setBlockAndUpdate(origin.offset(M_IN), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(M_FAN_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.offset(M_FAN),
                AllBlocks.ENCASED_FAN.getDefaultState().setValue(EncasedFanBlock.FACING, Direction.EAST));
        motor(level, origin.offset(M_FAN_MOTOR)).generatedSpeed.setValue(FAN_RPM);
        level.setBlockAndUpdate(origin.offset(M_BURNER), AllBlocks.BLAZE_BURNER.getDefaultState()
                .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        level.setBlockAndUpdate(origin.offset(M_OUT), Blocks.CHEST.defaultBlockState());
        RequesterVisualScenario.beltFunnel(level, origin.offset(M_FUNNEL), Direction.WEST, false);
        // In: the unpacking Packager (no power) on the input chest, the receive port's pillar and its lever.
        level.setBlockAndUpdate(origin.offset(M_UNPACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(origin.offset(M_PILLAR), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(M_LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST)
                .setValue(LeverBlock.POWERED, false));
        // Out: the return Packager on the output chest, on a redstone block; the send port's base and the sign's.
        level.setBlockAndUpdate(origin.offset(M_PACKER),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.offset(M_PACKER).below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(M_SEND_BASE), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(M_SIGN).below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
    }

    /** The ground surface at a factory, which must be the warehouse's own (the world is superflat). */
    private static BlockPos factoryOrigin(ServerLevel level, VisualContext context, BlockPos offset) {
        BlockPos origin = context.origin().offset(offset);
        level.getChunk(origin);
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, origin.getX(), origin.getZ());
        if (surface != origin.getY())
            throw new VisualTestException("the ground at the factory " + origin + " is at y " + surface
                    + ", not at the warehouse's " + origin.getY());
        return origin;
    }

    private static void clear(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
            if (!level.getBlockState(pos).isAir())
                level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    /** Flying, wearing Engineer's Goggles, and the hotbar of the set-up; the selected slot is the empty one. */
    private void equipPlayer(MinecraftServer server, VisualContext context) {
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
            throw new VisualTestException("the fresh port stack is already tuned: " + ports);
        player.getInventory().setItem(SLOT_PORTS, ports);
        player.getInventory().setItem(SLOT_WAREHOUSE_BEES, new ItemStack(RoboBeeLedger.roboBeeItem(), WAREHOUSE_BEES));
        player.getInventory().setItem(SLOT_CRUSHING_BEES, new ItemStack(RoboBeeLedger.roboBeeItem(), FACTORY_BEES));
        player.getInventory().setItem(SLOT_MELTING_BEES, new ItemStack(RoboBeeLedger.roboBeeItem(), FACTORY_BEES));
        player.getInventory().setItem(SLOT_SIGNS, new ItemStack(Items.OAK_SIGN, 4));
        player.getInventory().setItem(SLOT_CAKE, AllItems.CREATIVE_BLAZE_CAKE.asStack());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (AABB box : List.of(warehouseBox, crushingBox, meltingBox))
            if (!SceneItemCensus.isFullyLoaded(level, box))
                return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseCrafterFeederBlockEntity feeder = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                .getNullable(level, dock.offset(FEEDER));
        WarehouseProductionBlockEntity panes = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                dock.offset(PANES_STATION));
        if (controller == null || crane == null || feeder == null || panes == null)
            return false;
        if (!(controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && controller.inputStations().size() == 3 && controller.outputStations().size() == 1
                && controller.productionStations().size() == 3 && crane.isControllerLinked()
                && crane.aisleLength() == RAILS && feeder.running() && panes.attachedFeeder().isPresent()))
            return false;
        // The crushing wheels: a valid controller that drops its output downwards.
        BlockPos crushing = dock.offset(CRUSHING_FACTORY);
        BlockState controllerState = level.getBlockState(crushing.offset(C_CONTROLLER));
        if (!AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(controllerState)
                || !controllerState.getValue(CrushingWheelControllerBlock.VALID))
            return false;
        if (controllerState.getValue(CrushingWheelControllerBlock.FACING) != Direction.DOWN) {
            if (motorsFlipped)
                throw new VisualTestException("the crushing wheels throw " + controllerState
                        .getValue(CrushingWheelControllerBlock.FACING) + " with either direction of the motors");
            motorsFlipped = true;
            for (BlockPos motor : List.of(C_MOTOR_NORTH, C_MOTOR_SOUTH)) {
                CreativeMotorBlockEntity be = motor(level, crushing.offset(motor));
                be.generatedSpeed.setValue(-be.generatedSpeed.getValue());
            }
            return false;
        }
        // The kiln: the belt carries east, the fan blows east.
        BlockPos melting = dock.offset(MELTING_FACTORY);
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, melting.offset(M_BELT_START));
        if (belt == null || belt.getSpeed() == 0)
            return false;
        if (belt.getMovementFacing() != Direction.EAST) {
            if (beltFlipped)
                throw new VisualTestException("the kiln belt moves " + belt.getMovementFacing() + " either way");
            beltFlipped = true;
            CreativeMotorBlockEntity be = motor(level, melting.offset(M_BELT_MOTOR));
            be.generatedSpeed.setValue(-be.generatedSpeed.getValue());
            return false;
        }
        EncasedFanBlockEntity fan = fan(level, melting.offset(M_FAN));
        if (fan.getSpeed() == 0 || fan.airCurrent.direction == null)
            return false;
        if (fan.getAirFlowDirection() != Direction.EAST) {
            if (fanFlipped)
                throw new VisualTestException("the kiln fan blows " + fan.getAirFlowDirection() + " either way");
            fanFlipped = true;
            CreativeMotorBlockEntity be = motor(level, melting.offset(M_FAN_MOTOR));
            be.generatedSpeed.setValue(-be.generatedSpeed.getValue());
            return false;
        }
        for (RackPosition station : List.of(CRUSHING_RACK, MELTING_RACK, PANES_RACK))
            if (station(level, layout(dock).rackPos(station)).patterns().patternCount() != 0)
                throw new VisualTestException("a station starts with a pattern");
        LOGGER.info(PREFIX + "robobee: scene ready: crushing wheels throw DOWN, kiln belt moves EAST at {} RPM, fan "
                + "blows EAST at {} RPM (motors flipped: wheels={} belt={} fan={})", belt.getSpeed(), fan.getSpeed(),
                motorsFlipped, beltFlipped, fanFlipped);
        return true;
    }

    // =================================================================================================================
    // act 1: six ports from one stack, tuned on the first
    // =================================================================================================================

    private void placePorts(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_PORTS, "the bee ports",
                stack -> stack.is(RoboBeeLedger.beePortItem()));
        script.client("robobee: CHECK the port stack starts untuned", context -> {
            if (LogisticallyLinkedBlockItem.isTuned(context.minecraft().player.getMainHandItem()))
                throw new VisualTestException("the port stack is tuned before the first port is placed");
        });
        placePort(script, "warehouse send port", SEND_PORT, FROM_SOUTH);
        // Tune: a right-click on the placed port with the rest of the stack, not sneaking.
        beePorts.tuneOn(script, "warehouse send port", SEND_PORT, FROM_SOUTH, placed -> network = placed);
        placePort(script, "warehouse receive port", RECEIVE_PORT, FROM_SOUTH);
        script.camera(CRUSHING_VIEW);
        placePort(script, "crushing receive port", CRUSHING_FACTORY.offset(C_RECEIVE), FROM_EAST);
        placePort(script, "crushing send port", CRUSHING_FACTORY.offset(C_SEND), FROM_EAST);
        script.camera(MELTING_VIEW);
        placePort(script, "melting receive port", MELTING_FACTORY.offset(M_RECEIVE), FROM_WEST);
        placePort(script, "melting send port", MELTING_FACTORY.offset(M_SEND), FROM_EAST);
        script.server("robobee: CHECK all six bee ports share one network and start the bee ledger",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos dock = context.origin();
                    UUID expected = Objects.requireNonNull(network, "network");
                    RoboBeeLedger started = RoboBeeLedger.start(level, NAME);
                    started.addPort(new RoboBeeLedger.Port("warehouse send", dock.offset(SEND_PORT),
                            RoboBeeLedger.Role.SEND, Set.of(CRUSHING_ADDRESS, MELTING_ADDRESS)));
                    started.addPort(new RoboBeeLedger.Port("warehouse receive", dock.offset(RECEIVE_PORT),
                            RoboBeeLedger.Role.RECEIVE, Set.of()));
                    started.addPort(new RoboBeeLedger.Port("crushing receive",
                            dock.offset(CRUSHING_FACTORY).offset(C_RECEIVE), RoboBeeLedger.Role.RECEIVE, Set.of()));
                    started.addPort(new RoboBeeLedger.Port("crushing send", dock.offset(CRUSHING_FACTORY).offset(C_SEND),
                            RoboBeeLedger.Role.SEND, Set.of(WAREHOUSE_ADDRESS)));
                    started.addPort(new RoboBeeLedger.Port("melting receive",
                            dock.offset(MELTING_FACTORY).offset(M_RECEIVE), RoboBeeLedger.Role.RECEIVE, Set.of()));
                    started.addPort(new RoboBeeLedger.Port("melting send", dock.offset(MELTING_FACTORY).offset(M_SEND),
                            RoboBeeLedger.Role.SEND, Set.of(WAREHOUSE_ADDRESS)));
                    ledger = started;
                    beePorts.checkNetwork(level, expected);
                });
    }

    private void placePort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset) {
        beePorts.placePort(script, what, port, eyeOffset);
    }

    // =================================================================================================================
    // act 2: the ports' screens
    // =================================================================================================================

    private void configurePorts(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        sendPort(script, "warehouse send port", SEND_PORT, FROM_SOUTH, SLOT_WAREHOUSE_BEES, WAREHOUSE_BEES, true);
        receivePort(script, "warehouse receive port", RECEIVE_PORT, FROM_SOUTH, WAREHOUSE_ADDRESS, true);
        script.camera(CRUSHING_VIEW);
        receivePort(script, "crushing receive port", CRUSHING_FACTORY.offset(C_RECEIVE), FROM_EAST, CRUSHING_ADDRESS,
                false);
        sendPort(script, "crushing send port", CRUSHING_FACTORY.offset(C_SEND), FROM_EAST, SLOT_CRUSHING_BEES,
                FACTORY_BEES, false);
        script.camera(MELTING_VIEW);
        receivePort(script, "melting receive port", MELTING_FACTORY.offset(M_RECEIVE), FROM_WEST, MELTING_ADDRESS,
                false);
        sendPort(script, "melting send port", MELTING_FACTORY.offset(M_SEND), FROM_EAST, SLOT_MELTING_BEES,
                FACTORY_BEES, false);
        script.server("robobee: CHECK the addresses, the bees and return mode of every port",
                (server, context) -> beePorts.checkPorts(server, BEES));
    }

    /** A send port: its screen, the bees picked up from the hotbar and put into the bee slot, return mode on. */
    private void sendPort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset, int beeSlot, int bees,
            boolean shoot) {
        beePorts.sendPort(script, what, port, eyeOffset, beeSlot, bees, shoot ? "robobee-bee-slot" : null);
    }

    /** A receive port: its screen, the address typed into the address field, confirmed with the tick button. */
    private void receivePort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset, String address,
            boolean shoot) {
        beePorts.receivePort(script, what, port, eyeOffset, address, shoot ? "robobee-port-address" : null);
    }

    // =================================================================================================================
    // act 3: signs, levers, burner
    // =================================================================================================================

    private void writeSigns(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_SIGNS, "the oak signs", stack -> stack.is(Items.OAK_SIGN));
        script.camera(WAREHOUSE_VIEW);
        writeSign(script, "the crushing station's sign", CRUSHING_SIGN, CRUSHING_ADDRESS);
        writeSign(script, "the melting station's sign", MELTING_SIGN, MELTING_ADDRESS);
        script.camera(CRUSHING_VIEW);
        writeSign(script, "the crushing factory's sign", CRUSHING_FACTORY.offset(C_SIGN), WAREHOUSE_ADDRESS);
        script.camera(MELTING_VIEW);
        writeSign(script, "the melting factory's sign", MELTING_FACTORY.offset(M_SIGN), WAREHOUSE_ADDRESS);
        script.server("robobee: CHECK every packing Packager reads its sign", (server, context) -> {
            ServerLevel level = server.overworld();
            BlockPos dock = context.origin();
            Map<BlockPos, String> wanted = Map.of(dock.offset(CRUSHING_PACKAGER), CRUSHING_ADDRESS,
                    dock.offset(MELTING_PACKAGER), MELTING_ADDRESS,
                    dock.offset(CRUSHING_FACTORY).offset(C_PACKER), WAREHOUSE_ADDRESS,
                    dock.offset(MELTING_FACTORY).offset(M_PACKER), WAREHOUSE_ADDRESS);
            for (Map.Entry<BlockPos, String> entry : wanted.entrySet()) {
                PackagerBlockEntity packager = packager(level, entry.getKey());
                packager.updateSignAddress();
                if (!entry.getValue().equals(packager.signBasedAddress))
                    throw new VisualTestException("the Packager at " + entry.getKey() + " reads '"
                            + packager.signBasedAddress + "', not '" + entry.getValue() + "'");
            }
            LOGGER.info(PREFIX + "robobee: CHECK PASS the four signs were written with clicks and every packing "
                    + "Packager reads its address");
        });
    }

    private void writeSign(VisualScript script, String what, BlockPos sign, String text) {
        RealClicks.writeSign(script, NAME, what, sign, text, FROM_SOUTH);
    }

    private void flipLevers(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        script.camera(WAREHOUSE_VIEW);
        rightClick(script, click("the warehouse receive port's lever", RECEIVE_LEVER, Direction.UP,
                new Vec3(0.0, 2.0, 1.0)));
        script.camera(CRUSHING_VIEW);
        rightClick(script, click("the crushing receive port's lever", CRUSHING_FACTORY.offset(C_LEVER), Direction.EAST,
                new Vec3(1.8, 0.4, 0.0)));
        script.camera(MELTING_VIEW);
        rightClick(script, click("the melting receive port's lever", MELTING_FACTORY.offset(M_LEVER), Direction.WEST,
                new Vec3(-1.8, 0.4, 0.0)));
        script.serverUntil("robobee: the three levers are on",
                (server, context) -> levers(context).stream().allMatch(lever -> server.overworld()
                        .getBlockState(context.origin().offset(lever)).getValue(LeverBlock.POWERED)),
                SYNC_TIMEOUT_TICKS);
        script.server("robobee: CHECK the wiring of every port and Packager", this::checkWiring);
    }

    private static List<BlockPos> levers(VisualContext context) {
        return List.of(RECEIVE_LEVER, CRUSHING_FACTORY.offset(C_LEVER), MELTING_FACTORY.offset(M_LEVER));
    }

    /**
     * The rules a working bee network needs: send ports unpowered, receive ports powered, every port touching nothing
     * with an item capability but its own Packager(s) and no other port, unpacking Packagers unpowered, packing ones
     * powered, no Packager with a Stock Link.
     */
    private void checkWiring(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Map<BlockPos, Set<BlockPos>> ownPackagers = Map.of(
                dock.offset(SEND_PORT), Set.of(dock.offset(CRUSHING_PACKAGER), dock.offset(MELTING_PACKAGER)),
                dock.offset(RECEIVE_PORT), Set.of(dock.offset(RETURN_PACKAGER)),
                dock.offset(CRUSHING_FACTORY).offset(C_RECEIVE), Set.of(dock.offset(CRUSHING_FACTORY).offset(C_UNPACKER)),
                dock.offset(CRUSHING_FACTORY).offset(C_SEND), Set.of(dock.offset(CRUSHING_FACTORY).offset(C_PACKER)),
                dock.offset(MELTING_FACTORY).offset(M_RECEIVE), Set.of(dock.offset(MELTING_FACTORY).offset(M_UNPACKER)),
                dock.offset(MELTING_FACTORY).offset(M_SEND), Set.of(dock.offset(MELTING_FACTORY).offset(M_PACKER)));
        List<BlockPos> packing = List.of(dock.offset(CRUSHING_PACKAGER), dock.offset(MELTING_PACKAGER),
                dock.offset(CRUSHING_FACTORY).offset(C_PACKER), dock.offset(MELTING_FACTORY).offset(M_PACKER));
        List<BlockPos> unpacking = List.of(dock.offset(RETURN_PACKAGER),
                dock.offset(CRUSHING_FACTORY).offset(C_UNPACKER), dock.offset(MELTING_FACTORY).offset(M_UNPACKER));
        beePorts.checkWiring(level, ownPackagers, packing, unpacking);
    }

    private void lightBurner(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_CAKE, "the Creative Blaze Cake",
                AllItems.CREATIVE_BLAZE_CAKE::isIn);
        script.camera(MELTING_VIEW);
        rightClick(script, click("the kiln's Blaze Burner", MELTING_FACTORY.offset(M_BURNER), Direction.UP,
                new Vec3(0.0, 1.8, 1.2)));
        script.serverUntil("robobee: the burner is lit and the fan's current blasts", (server, context) -> {
            ServerLevel level = server.overworld();
            BlockPos melting = context.origin().offset(MELTING_FACTORY);
            BlockState burner = level.getBlockState(melting.offset(M_BURNER));
            EncasedFanBlockEntity fan = fan(level, melting.offset(M_FAN));
            return burner.getValue(BlazeBurnerBlock.HEAT_LEVEL).isAtLeast(BlazeBurnerBlock.HeatLevel.KINDLED)
                    && fan.airCurrent.getTypeAt(3.0f) == AllFanProcessingTypes.BLASTING;
        }, MOMENT_TIMEOUT_TICKS).server("robobee: CHECK the kiln", (server, context) -> {
            BlockPos melting = context.origin().offset(MELTING_FACTORY);
            LOGGER.info(PREFIX + "robobee: CHECK PASS a click with a Creative Blaze Cake lit the burner ({}) and the "
                    + "fan's current over the belt blasts", server.overworld().getBlockState(melting.offset(M_BURNER))
                    .getValue(BlazeBurnerBlock.HEAT_LEVEL));
        });
        RequesterVisualScenario.takeAnEmptyHand(script);
    }

    // =================================================================================================================
    // act 4: patterns
    // =================================================================================================================

    private void writePatterns(VisualScript script) {
        script.server("robobee: put ports, signs and cake away and take the pattern items into the hotbar",
                (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    for (int slot = 0; slot < player.getInventory().items.size(); slot++)
                        player.getInventory().items.set(slot, ItemStack.EMPTY);
                    player.getInventory().setItem(SLOT_COBBLE, COBBLE.toStack(1));
                    player.getInventory().setItem(SLOT_GRAVEL, GRAVEL.toStack(1));
                    player.getInventory().setItem(SLOT_SAND, SAND.toStack(1));
                    player.getInventory().setItem(SLOT_GLASS, GLASS.toStack(1));
                    player.getInventory().setItem(SLOT_PANE, PANE.toStack(1));
                    player.inventoryMenu.broadcastChanges();
                });
        script.camera(WAREHOUSE_VIEW);
        openStation(script, "the crushing station", CRUSHING_STATION, false);
        FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_COBBLE, Items.COBBLESTONE, TOP_LEFT);
        FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_GRAVEL, Items.GRAVEL,
                ProductionMenuLayout.RESULT_CELL);
        script.client("robobee: select the crushing station's second pattern", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.productionTab(productionScreen(context), SECOND_PATTERN)))
                .waitTicks(SETTLE_TICKS);
        FeederVisualScenario.patternEntry(script, SECOND_PATTERN, SLOT_GRAVEL, Items.GRAVEL, TOP_LEFT);
        FeederVisualScenario.patternEntry(script, SECOND_PATTERN, SLOT_SAND, Items.SAND,
                ProductionMenuLayout.RESULT_CELL);
        GoggleShots.reach(script, NAME, 0.0);
        script.client("robobee: rest the cursor on the result cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionCell(productionScreen(context), ProductionMenuLayout.RESULT_CELL)))
                .waitTicks(SETTLE_TICKS).shotWithGui("robobee-pattern-crushing");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        RequesterVisualScenario.closeScreen(script, "the crushing station's screen");

        openStation(script, "the melting station", MELTING_STATION, false);
        FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_SAND, Items.SAND, TOP_LEFT);
        FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_GLASS, Items.GLASS,
                ProductionMenuLayout.RESULT_CELL);
        RequesterVisualScenario.closeScreen(script, "the melting station's screen");

        openStation(script, "the glass pane station", PANES_STATION, true);
        for (int cell = 0; cell < GLASS_PER_RUN; cell++)
            FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_GLASS, Items.GLASS, cell);
        FeederVisualScenario.patternEntry(script, FIRST_PATTERN, SLOT_PANE, Items.GLASS_PANE,
                ProductionMenuLayout.RESULT_CELL);
        FeederVisualScenario.scrollResult(script, FIRST_PATTERN, PANES_PER_RUN);
        script.until("robobee: the pane pattern passed the Crafter's recipe check",
                context -> productionScreen(context).state().check(FIRST_PATTERN).check() == CrafterPatternCheck.VALID,
                SCREEN_TIMEOUT_TICKS);
        GoggleShots.reach(script, NAME, 0.0);
        script.client("robobee: rest the cursor on the result cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionCell(productionScreen(context), ProductionMenuLayout.RESULT_CELL)))
                .waitTicks(SETTLE_TICKS).shotWithGui("robobee-pattern-panes");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        RequesterVisualScenario.closeScreen(script, "the glass pane station's screen");
        script.server("robobee: CHECK the four patterns, put the pattern items away and take the baseline census",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos dock = context.origin();
                    ProductionPatterns crushing = station(level, dock.offset(CRUSHING_STATION)).patterns();
                    ProductionPatterns melting = station(level, dock.offset(MELTING_STATION)).patterns();
                    WarehouseProductionBlockEntity panesStation = station(level, dock.offset(PANES_STATION));
                    ProductionPatterns panes = panesStation.patterns();
                    requirePattern(crushing, FIRST_PATTERN, COBBLE, GRAVEL, 1);
                    requirePattern(crushing, SECOND_PATTERN, GRAVEL, SAND, 1);
                    requirePattern(melting, FIRST_PATTERN, SAND, GLASS, 1);
                    if (panes.filledCells(FIRST_PATTERN) != GLASS_PER_RUN
                            || !panes.keyAt(FIRST_PATTERN, ProductionPatterns.RESULT_ENTRY).equals(Optional.of(PANE))
                            || panes.countAt(FIRST_PATTERN, ProductionPatterns.RESULT_ENTRY) != PANES_PER_RUN)
                        throw new VisualTestException("the pane station's pattern is " + panes);
                    if (panesStation.crafterCheck(FIRST_PATTERN).check() != CrafterPatternCheck.VALID)
                        throw new VisualTestException("the Crafter check says " + panesStation.crafterCheck(FIRST_PATTERN));
                    if (!controller(level, context).producibleKeys().containsAll(List.of(GRAVEL, SAND, GLASS, PANE)))
                        throw new VisualTestException("the aisle makes " + controller(level, context).producibleKeys());
                    ServerPlayer player = context.serverPlayer(server);
                    for (int slot = 0; slot < player.getInventory().items.size(); slot++)
                        player.getInventory().items.set(slot, ItemStack.EMPTY);
                    player.inventoryMenu.broadcastChanges();
                    baseline = census(server, context);
                    if (chainUnits(baseline) != INTAKE)
                        throw new VisualTestException("the baseline holds " + chainUnits(baseline)
                                + " chain units, expected the chest's " + INTAKE + ": " + SceneItemCensus.describe(baseline));
                    LOGGER.info(PREFIX + "robobee: CHECK PASS the four patterns were written with clicks (crushing: "
                            + "cobblestone -> gravel, gravel -> sand; melting: sand -> glass; panes: 6 glass -> 16, "
                            + "Crafter check VALID); baseline census {}", SceneItemCensus.describe(baseline));
                });
    }

    private static void requirePattern(ProductionPatterns patterns, int slot, ItemKey ingredient, ItemKey result,
            int count) {
        if (!patterns.keyAt(slot, TOP_LEFT).equals(Optional.of(ingredient)) || patterns.countAt(slot, TOP_LEFT) != 1
                || patterns.filledCells(slot) != 1
                || !patterns.keyAt(slot, ProductionPatterns.RESULT_ENTRY).equals(Optional.of(result))
                || patterns.countAt(slot, ProductionPatterns.RESULT_ENTRY) != count)
            throw new VisualTestException("pattern " + slot + " is not 1 " + ingredient + " -> " + count + " " + result
                    + ": " + patterns);
    }

    private void openStation(VisualScript script, String what, BlockPos station, boolean feeder) {
        rightClick(script, click(what, station, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "robobee: " + what + "'s screen is open",
                context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                        && screen.state().feeder() == feeder, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    private static WarehouseProductionScreen productionScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open: " + context.minecraft().screen);
    }

    // =================================================================================================================
    // act 5: the chest of cobblestone
    // =================================================================================================================

    private void intake(VisualScript script) {
        script.serverUntil("robobee: the chest full of cobblestone is stored", (server, context) -> {
            ServerLevel level = server.overworld();
            BlockPos dock = context.origin();
            IItemHandler chest = handler(level, dock.offset(INTAKE_CHEST));
            for (int slot = 0; slot < chest.getSlots(); slot++)
                if (!chest.getStackInSlot(slot).isEmpty())
                    return false;
            if (inputStation(level, dock.offset(INTAKE_INPUT)).hasBufferedItems())
                return false;
            return controller(level, context).countOf(COBBLE) == INTAKE;
        }, INTAKE_TIMEOUT_TICKS).server("robobee: CHECK the intake", (server, context) -> {
            assertCensus(server, context, "the intake", Map.of(COBBLE, (long) INTAKE));
            LOGGER.info(PREFIX + "robobee: CHECK PASS intake {} cobblestone stored through the chute and the top-level "
                    + "input", INTAKE);
        });
    }

    // =================================================================================================================
    // act 6: the order
    // =================================================================================================================

    private void order(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        script.camera(WAREHOUSE_VIEW);
        rightClick(script, click("the terminal", TERMINAL, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "robobee: the terminal screen is open with its stock",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                        && terminal.entry(PANE).isPresent() && terminal.entry(COBBLE).isPresent(),
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS)
                .client("robobee: scroll the requested amount up to " + ORDER,
                        context -> TerminalOrder.scrollAmount(context, NAME, ORDER))
                .waitTicks(SETTLE_TICKS)
                .server("robobee: start recording every bee trip and watching every tick",
                        (server, context) -> {
                            RoboBeeLedger running = ledger();
                            running.record();
                            running.watchEveryTick(level -> watchTick(level, context));
                        })
                .client("robobee: click the glass panes, which a whole chain has to make", context -> {
                    WarehouseTerminalScreen terminal = terminalScreen(context);
                    int cell = cellOf(terminal, PANE);
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
                    LOGGER.info(PREFIX + "robobee: clicked cell {} (glass panes) asking for {}", cell,
                            ScreenInput.terminalAmount(terminal).getState());
                })
                .serverUntil("robobee: the click planned the chain", (server, context) ->
                        controller(server.overworld(), context).openProductionOrders().size() >= 4, SYNC_TIMEOUT_TICKS)
                .server("robobee: CHECK one click planned the 4-step chain", this::checkPlan);
        String[] seen = {"nothing"};
        ArmVisualScenario.untilOrFail(script, "robobee: the terminal says the order is being produced",
                context -> {
                    Optional<Component> line = terminalScreen(context).feedbackLine();
                    line.ifPresent(shown -> seen[0] = shown.getString());
                    return line.filter(shown -> isProducingSentence(shown.getString())).isPresent();
                }, SCREEN_TIMEOUT_TICKS, context -> "the terminal said '" + seen[0] + "', not the producing sentence "
                        + "for " + ORDER + " glass panes");
        script.client("robobee: CHECK the terminal's answer", context -> LOGGER.info(PREFIX
                + "robobee: CHECK PASS the terminal answered '{}' (no gold 'served only in part')", seen[0]));
        script.waitTicks(SETTLE_TICKS).shotWithGui("robobee-order");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.server("robobee: census after the click",
                (server, context) -> assertCensus(server, context, "the click", Map.of(COBBLE, (long) INTAKE)));
    }

    private static boolean isProducingSentence(String shown) {
        return TerminalOrder.isProducingSentence(shown, ORDER);
    }

    private static int cellOf(WarehouseTerminalScreen terminal, ItemKey key) {
        return TerminalOrder.cellOf(terminal, key);
    }

    private static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        return TerminalOrder.terminalScreen(context);
    }

    /** One request for 512 panes and exactly the four orders of the chain, each step feeding its parent's line. */
    private void checkPlan(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, context);
        List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(dock.offset(TERMINAL));
        if (requests.size() != 1 || requests.getFirst().requested() != ORDER || !requests.getFirst().key().equals(PANE))
            throw new VisualTestException("the terminal's requests are " + requests + ", expected one for " + ORDER
                    + " glass panes");
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        if (orders.size() != 4)
            throw new VisualTestException("the click made " + orders.size() + " production orders: " + orders);
        Map<ItemKey, ProductionOrder<ItemKey, RackPosition>> byResult = new HashMap<>();
        for (ProductionOrder<ItemKey, RackPosition> order : orders)
            if (byResult.put(order.result(), order) != null)
                throw new VisualTestException("two orders make " + order.result());
        ProductionOrder<ItemKey, RackPosition> root = require(byResult, PANE);
        ProductionOrder<ItemKey, RackPosition> glass = require(byResult, GLASS);
        ProductionOrder<ItemKey, RackPosition> sand = require(byResult, SAND);
        ProductionOrder<ItemKey, RackPosition> gravel = require(byResult, GRAVEL);
        if (root.isStep() || root.resultAmount() != ORDER || !root.station().equals(PANES_RACK)
                || !root.backingRequest().equals(Optional.of(requests.getFirst().id())))
            throw new VisualTestException("the root is " + root);
        requireStep(glass, MELTING_RACK, root, GLASS, SAND);
        requireStep(sand, CRUSHING_RACK, glass, SAND, GRAVEL);
        requireStep(gravel, CRUSHING_RACK, sand, GRAVEL, COBBLE);
        requireLine(root, GLASS);
        plan.put(PANE, root.id());
        plan.put(GLASS, glass.id());
        plan.put(SAND, sand.id());
        plan.put(GRAVEL, gravel.id());
        requestId = requests.getFirst().id();
        LOGGER.info(PREFIX + "robobee: CHECK PASS one click planned the 4-step chain: request {} for {} panes; root "
                + "{} x{} at the pane station; steps glass {} at melting, sand {} and gravel {} at crushing, each "
                + "feeding its parent's line of {}", requestId, ORDER, root.id(), root.resultAmount(), glass.id(),
                sand.id(), gravel.id(), STEP_AMOUNT);
    }

    private static ProductionOrder<ItemKey, RackPosition> require(
            Map<ItemKey, ProductionOrder<ItemKey, RackPosition>> byResult, ItemKey result) {
        ProductionOrder<ItemKey, RackPosition> order = byResult.get(result);
        if (order == null)
            throw new VisualTestException("no production order makes " + result + ": " + byResult.keySet());
        return order;
    }

    private static void requireStep(ProductionOrder<ItemKey, RackPosition> step, RackPosition station,
            ProductionOrder<ItemKey, RackPosition> parent, ItemKey result, ItemKey ingredient) {
        if (!step.isStep() || step.resultAmount() != STEP_AMOUNT || !step.station().equals(station)
                || step.backingRequest().isPresent())
            throw new VisualTestException("the " + result + " step is " + step);
        UUID line = step.parentLine().orElseThrow(() -> new VisualTestException("the " + result
                + " step names no parent line"));
        SupplyLine<ItemKey> parentLine = parent.line(line).orElseThrow(() -> new VisualTestException("the " + result
                + " step's parent line is no line of " + parent.result()));
        if (!parentLine.key().equals(result) || parentLine.required() != STEP_AMOUNT)
            throw new VisualTestException("the " + result + " step feeds " + parentLine);
        requireLine(step, ingredient);
    }

    private static void requireLine(ProductionOrder<ItemKey, RackPosition> order, ItemKey ingredient) {
        if (order.lines().size() != 1 || !order.lines().getFirst().key().equals(ingredient)
                || order.lines().getFirst().required() != STEP_AMOUNT)
            throw new VisualTestException("the order for " + order.result() + " has the lines " + order.lines()
                    + ", expected one of " + STEP_AMOUNT + " " + ingredient);
    }

    // =================================================================================================================
    // act 7: the chain runs
    // =================================================================================================================

    private void chain(VisualScript script) {
        // A bee leaving the warehouse with cobblestone, seen from beside the send port.
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(SEND_PORT_VIEW);
        ArmVisualScenario.untilOrFail(script, "robobee: a bee rises over the warehouse's send port",
                context -> beeNear(context, SEND_PORT), MOMENT_TIMEOUT_TICKS,
                context -> "no bee came within " + BEE_SHOT_RANGE + " blocks of the send port");
        script.shot("robobee-bee-warehouse");
        script.camera(CRUSHING_PORT_VIEW);
        ArmVisualScenario.untilOrFail(script, "robobee: a bee lands on the crushing factory's receive port",
                context -> beeNear(context, CRUSHING_FACTORY.offset(C_RECEIVE)), MOMENT_TIMEOUT_TICKS,
                context -> "no bee came within " + BEE_SHOT_RANGE + " blocks of the crushing receive port");
        script.shot("robobee-bee-factory");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        // The goggles while the gravel step waits for its result (its longest stable window).
        script.serverUntil("robobee: the gravel step waits for its result",
                (server, context) -> stateOf(server, context, GRAVEL) == ProductionOrderState.WAITING_FOR_RESULT,
                STEP_TIMEOUT_TICKS);
        goggles(script);
        stepDone(script, GRAVEL, "gravel", Map.of(COBBLE, (long) INTAKE - STEP_AMOUNT, GRAVEL, (long) STEP_AMOUNT));
        crushingShot(script);
        stepDone(script, SAND, "sand", Map.of(COBBLE, (long) INTAKE - STEP_AMOUNT, SAND, (long) STEP_AMOUNT));
        kilnShot(script);
        stepDone(script, GLASS, "glass", Map.of(COBBLE, (long) INTAKE - STEP_AMOUNT, GLASS, (long) STEP_AMOUNT));
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(FEEDER_VIEW);
        script.serverUntil("robobee: the feeder has laid glass into the Crafter",
                (server, context) -> {
                    check(server, context);
                    IItemHandler crafter = handler(server.overworld(), context.origin().offset(CRAFTER));
                    for (int slot = 0; slot < crafter.getSlots(); slot++)
                        if (crafter.getStackInSlot(slot).is(Items.GLASS))
                            return true;
                    return false;
                }, ROOT_TIMEOUT_TICKS);
        script.shot("robobee-feeder");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    /** Waits until the step making {@code result} is COMPLETE, then checks it and takes the census of that moment. */
    private void stepDone(VisualScript script, ItemKey result, String what, Map<ItemKey, Long> expected) {
        script.serverUntil("robobee: the " + what + " step is complete and the kiln belt is empty",
                (server, context) -> {
                    check(server, context);
                    ProductionOrderState state = stateOf(server, context, result);
                    if (state == ProductionOrderState.TIMED_OUT || state == ProductionOrderState.CANCELLED)
                        throw new VisualTestException("the " + what + " step ended " + state);
                    return state == ProductionOrderState.COMPLETE && beltEmpty(server.overworld(), context);
                }, STEP_TIMEOUT_TICKS)
                .server("robobee: CHECK the " + what + " step", (server, context) -> {
                    ProductionOrder<ItemKey, RackPosition> step = order(server, context, result);
                    if (step.produced() < STEP_AMOUNT)
                        throw new VisualTestException("the " + what + " step completed with " + step.produced()
                                + " of " + STEP_AMOUNT);
                    LOGGER.info(PREFIX + "robobee: CHECK PASS step {} COMPLETE: {} of {} arrived through the return "
                            + "input, largest progress gap so far {} ticks", what, step.produced(), STEP_AMOUNT,
                            largestGap.getOrDefault(step.id(), 0L));
                    assertCensus(server, context, "the " + what + " step is complete", expected);
                    checkBottomUp(server, context, result);
                });
    }

    /**
     * The parent of the step just completed was blocked by it, was fed nothing while it was blocked (both checked on
     * every tick by {@link #watchTick}) and was first fed only once that step had completed.
     */
    private void checkBottomUp(MinecraftServer server, VisualContext context, ItemKey done) {
        ledger().check();
        ItemKey parent = done.equals(GRAVEL) ? SAND : done.equals(SAND) ? GLASS : PANE;
        UUID childId = Objects.requireNonNull(plan.get(done), "the plan has no " + done + " order");
        UUID parentId = Objects.requireNonNull(plan.get(parent), "the plan has no " + parent + " order");
        long blocked = blockedTicks.getOrDefault(parentId, 0L);
        if (blocked == 0)
            throw new VisualTestException("the " + parent + " order was never seen blocked by its " + done
                    + " step, so the bottom-up watch observed nothing");
        Long completed = completedAt.get(childId);
        if (completed == null)
            throw new VisualTestException("the watch never saw the " + done + " step COMPLETE");
        Long fed = firstFed.get(parentId);
        if (fed != null && fed < completed)
            throw new VisualTestException("the " + parent + " order was first fed at tick " + fed + ", before its "
                    + done + " step completed at tick " + completed);
        LOGGER.info(PREFIX + "robobee: CHECK PASS bottom-up after {}: the {} order was blocked for {} ticks with "
                + "nothing fed and its station empty unless shared with the open step, the step completed at tick {}, "
                + "the parent was first fed at tick {}", done, parent, blocked, completed,
                fed == null ? "(not yet)" : fed);
    }

    private static BlockPos stationOf(ItemKey result) {
        if (result.equals(GRAVEL) || result.equals(SAND))
            return CRUSHING_STATION;
        if (result.equals(GLASS))
            return MELTING_STATION;
        if (result.equals(PANE))
            return PANES_STATION;
        throw new VisualTestException("no station makes " + result);
    }

    private void crushingShot(VisualScript script) {
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(CRUSHING_VIEW);
        script.serverUntil("robobee: gravel lies in the crushing line", (server, context) -> {
            check(server, context);
            BlockPos crushing = context.origin().offset(CRUSHING_FACTORY);
            return holds(server.overworld(), crushing.offset(C_IN), Items.GRAVEL)
                    || holds(server.overworld(), crushing.offset(C_CONTROLLER), Items.GRAVEL)
                    || holds(server.overworld(), crushing.offset(C_HIGH_CHUTE), Items.GRAVEL);
        }, STEP_TIMEOUT_TICKS);
        script.shot("robobee-crushing");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    private void kilnShot(VisualScript script) {
        GoggleShots.reach(script, NAME, 0.0);
        script.camera(MELTING_VIEW);
        script.serverUntil("robobee: sand rides the kiln belt", (server, context) -> {
            check(server, context);
            return !beltEmpty(server.overworld(), context);
        }, STEP_TIMEOUT_TICKS);
        script.shot("robobee-kiln");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    private void goggles(VisualScript script) {
        script.client("robobee: clear the toasts before the goggles", context -> context.minecraft().getToasts().clear());
        gogglePair(script, "");
        language.switchTo(script, "robobee: ", GERMAN);
        gogglePair(script, "-de");
        language.switchTo(script, "robobee: ", ENGLISH);
    }

    private void gogglePair(VisualScript script, String suffix) {
        GoggleShots.shot(script, NAME, CRUSHING_STATION_GOGGLES, "robobee-goggles-crushing" + suffix,
                dock -> dock.offset(CRUSHING_STATION), context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(CRUSHING_STATION));
                    String patterns = GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_PATTERNS, 2);
                    String orders = GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_ORDERS, 2);
                    GoggleShots.requireLine(lines, patterns);
                    GoggleShots.requireLine(lines, orders);
                    requireTranslated(context, "crushing patterns", patterns);
                    requireTranslated(context, "crushing orders", orders);
                    LOGGER.info(PREFIX + "robobee: CHECK PASS the crushing station's goggles: {}", lines);
                });
        GoggleShots.shot(script, NAME, CONTROLLER_GOGGLES, "robobee-goggles-controller" + suffix,
                dock -> dock.offset(CONTROLLER), context -> true, context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(CONTROLLER));
                    String orders = GoggleShots.count(WareworksLang.GOGGLES_PRODUCTION_ORDERS, 4);
                    GoggleShots.requireLine(lines, orders);
                    requireTranslated(context, "controller orders", orders);
                    LOGGER.info(PREFIX + "robobee: CHECK PASS the controller's goggles: {}", lines);
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
            throw new VisualTestException("'" + line + "' at '" + moment + "' is no " + selected
                    + " translation of '" + english + "'");
        LOGGER.info(PREFIX + "robobee: CHECK PASS '{}' is the {} line of '{}'", line, selected, english);
    }

    // =================================================================================================================
    // act 8: the end
    // =================================================================================================================

    private void end(VisualScript script) {
        script.serverUntil("robobee: the panes reached the terminal and every bee is home", (server, context) -> {
                    check(server, context);
                    ServerLevel level = server.overworld();
                    ProductionOrderState root = stateOf(server, context, PANE);
                    if (root == ProductionOrderState.TIMED_OUT || root == ProductionOrderState.CANCELLED)
                        throw new VisualTestException("the pane order ended " + root);
                    return root == ProductionOrderState.COMPLETE
                            && controller(level, context).requestsFor(context.origin().offset(TERMINAL)).isEmpty()
                            && ledger().inFlight() == 0;
                }, ROOT_TIMEOUT_TICKS)
                .server("robobee: CHECK the end state", this::checkEnd);
        RequesterVisualScenario.takeAnEmptyHand(script);
        script.camera(WAREHOUSE_VIEW);
        rightClick(script, click("the terminal", TERMINAL, Direction.UP, ABOVE_STATION));
        ArmVisualScenario.untilOrFail(script, "robobee: the terminal screen is open",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                        && terminal.getMenu().getStateId() > 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS * 2)
                .client("robobee: rest the cursor on the window's title, where no tooltip is drawn", context -> {
                    WarehouseTerminalScreen terminal = terminalScreen(context);
                    ScreenInput.hover(context.minecraft(),
                            new ScreenInput.Point(terminal.getGuiLeft() + 30.0, terminal.getGuiTop() + 5.0));
                })
                .waitTicks(SETTLE_TICKS).shotWithGui("robobee-finished-terminal");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
    }

    private void checkEnd(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        RoboBeeLedger running = ledger();
        // The per-tick verdict up to this tick first: a watcher violation since the last poll fails here, before any
        // CHECK PASS line of the end is written.
        running.check();
        WarehouseControllerBlockEntity controller = controller(level, context);
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        long panesInBuffer = 0;
        for (ItemKey key : terminal.bufferedItems().keys())
            if (!key.equals(PANE))
                throw new VisualTestException("the terminal's buffer holds " + key);
        panesInBuffer = terminal.bufferedItems().count(PANE);
        if (panesInBuffer != ORDER)
            throw new VisualTestException("the terminal's buffer holds " + panesInBuffer + " glass panes");
        LOGGER.info(PREFIX + "robobee: CHECK PASS {} glass panes delivered into the terminal's buffer", panesInBuffer);
        Map<ItemKey, Long> stock = Map.of(COBBLE, controller.countOf(COBBLE), GRAVEL, controller.countOf(GRAVEL),
                SAND, controller.countOf(SAND), GLASS, controller.countOf(GLASS), PANE, controller.countOf(PANE));
        if (!stock.equals(Map.of(COBBLE, (long) INTAKE - STEP_AMOUNT, GRAVEL, 0L, SAND, 0L, GLASS, 0L, PANE, 0L)))
            throw new VisualTestException("the stock is " + stock);
        long flint = controller.countOf(FLINT);
        long clay = controller.countOf(CLAY);
        if (flint < 1)
            throw new VisualTestException("no flint came back from crushing 192 gravel");
        for (BlockPos input : List.of(RETURN_INPUT, CRAFTER_INPUT, INTAKE_INPUT))
            if (inputStation(level, dock.offset(input)).hasBufferedItems())
                throw new VisualTestException("the input at " + input + " still holds "
                        + inputStation(level, dock.offset(input)).bufferedItems());
        LOGGER.info(PREFIX + "robobee: CHECK PASS flint and clay by-products stored: flint={} clay={}", flint, clay);
        // Every order complete with its amount; no other order ever.
        for (Map.Entry<ItemKey, UUID> entry : plan.entrySet()) {
            ProductionOrder<ItemKey, RackPosition> order = controller.productionOrder(entry.getValue())
                    .orElseThrow(() -> new VisualTestException("the controller forgot the order " + entry));
            if (order.state() != ProductionOrderState.COMPLETE)
                throw new VisualTestException("the " + entry.getKey() + " order is " + order.state());
            long gap = largestGap.getOrDefault(order.id(), 0L);
            LOGGER.info(PREFIX + "robobee: CHECK PASS order {} largest progress gap {} ticks (deadline {})",
                    entry.getKey(), gap, DEFAULT_TIMEOUT_TICKS);
        }
        LOGGER.info(PREFIX + "robobee: CHECK PASS every production order COMPLETE: gravel {}, sand {}, glass {}, "
                + "panes {}", STEP_AMOUNT, STEP_AMOUNT, STEP_AMOUNT, ORDER);
        // Bottom-up over the whole chain: every parent was first fed only after its child step had completed.
        for (ItemKey child : List.of(GRAVEL, SAND, GLASS)) {
            ItemKey parent = child.equals(GRAVEL) ? SAND : child.equals(SAND) ? GLASS : PANE;
            Long completed = completedAt.get(plan.get(child));
            Long fed = firstFed.get(plan.get(parent));
            if (completed == null || fed == null || fed < completed)
                throw new VisualTestException("the " + parent + " order was first fed at tick " + fed + ", its "
                        + child + " step completed at tick " + completed);
            LOGGER.info(PREFIX + "robobee: CHECK PASS bottom-up: the {} step completed at tick {}, the {} order was "
                    + "first fed at tick {}", child, completed, parent, fed);
        }
        LOGGER.info(PREFIX + "robobee: CHECK PASS no duplicate order: only the four of the click, the request {} "
                + "unchanged, nothing timed out or cancelled, no safety stop", requestId);
        // Sums per address and every trip landed.
        if (running.openTrips() != 0)
            throw new VisualTestException(running.openTrips() + " bee trips never arrived");
        Map<ItemKey, Long> crushingOut = running.sentTo(CRUSHING_ADDRESS);
        Map<ItemKey, Long> meltingOut = running.sentTo(MELTING_ADDRESS);
        Map<ItemKey, Long> back = running.sentTo(WAREHOUSE_ADDRESS);
        Map<ItemKey, Long> backExpected = new HashMap<>(Map.of(GRAVEL, (long) STEP_AMOUNT, SAND, (long) STEP_AMOUNT,
                GLASS, (long) STEP_AMOUNT, FLINT, flint));
        if (clay > 0)
            backExpected.put(CLAY, clay);
        if (!crushingOut.equals(Map.of(COBBLE, (long) STEP_AMOUNT, GRAVEL, (long) STEP_AMOUNT))
                || !meltingOut.equals(Map.of(SAND, (long) STEP_AMOUNT)) || !back.equals(backExpected))
            throw new VisualTestException("the sums per address are Crushing " + crushingOut + ", Melting " + meltingOut
                    + ", Warehouse " + back + " (expected back " + backExpected + ")");
        for (String address : List.of(CRUSHING_ADDRESS, MELTING_ADDRESS, WAREHOUSE_ADDRESS))
            if (!running.sentTo(address).equals(running.arrivedFor(address)))
                throw new VisualTestException("for '" + address + "' " + running.sentTo(address) + " left but "
                        + running.arrivedFor(address) + " arrived");
        LOGGER.info(PREFIX + "robobee: CHECK PASS every box sent once: {} trips; '{}' out {}, '{}' out {}, '{}' back {}",
                running.trips().size(), CRUSHING_ADDRESS, SceneItemCensus.describe(crushingOut), MELTING_ADDRESS,
                SceneItemCensus.describe(meltingOut), WAREHOUSE_ADDRESS, SceneItemCensus.describe(back));
        // Bees home.
        Map<String, Integer> home = new LinkedHashMap<>();
        for (RoboBeeLedger.Port port : running.ports())
            home.put(port.name(), running.beesIn(port.pos()));
        Map<String, Integer> wanted = new LinkedHashMap<>();
        wanted.put("warehouse send", WAREHOUSE_BEES);
        wanted.put("warehouse receive", 0);
        wanted.put("crushing receive", 0);
        wanted.put("crushing send", FACTORY_BEES);
        wanted.put("melting receive", 0);
        wanted.put("melting send", FACTORY_BEES);
        if (!home.equals(wanted) || running.inFlight() != 0)
            throw new VisualTestException("the bees are " + home + " with " + running.inFlight() + " in the air");
        LOGGER.info(PREFIX + "robobee: CHECK PASS all {} bees home: {} (most in the air at once: {}, empty flights: "
                + "{})", BEES, home, running.maxInFlight(), running.emptyFlights());
        if (!forced.allStillForced(level))
            throw new VisualTestException("a chunk of the warehouse or a factory lost its forced ticket: "
                    + forced.chunks());
        deviations.add("the design's check that the client lacks the factory chunks is replaced by the forced-ticket "
                + "check: at " + CRUSHING_DISTANCE + " and " + MELTING_DISTANCE + " blocks the client has them");
        LOGGER.info(PREFIX + "robobee: CHECK PASS the warehouse and both factories stayed force-loaded for the whole "
                + "run: {} (at {} and {} blocks the player's own simulation distance reaches them too, so this run "
                + "does not prove that forced chunks alone keep a factory working)", forced.chunks(), CRUSHING_DISTANCE,
                MELTING_DISTANCE);
        assertCensus(server, context, "the end", Map.of(COBBLE, (long) INTAKE - STEP_AMOUNT, PANE, (long) ORDER));
        LOGGER.info(PREFIX + "robobee: CHECK PASS {} glass panes delivered", ORDER);
    }

    // =================================================================================================================
    // the watch (server thread, every tick from the click)
    // =================================================================================================================

    /** The checks of every server tick (through the ledger's tick listener): orders, request, safety stop, gaps. */
    private void watchTick(ServerLevel level, VisualContext context) {
        if (plan.isEmpty())
            return;
        WarehouseControllerBlockEntity controller = controller(level, context);
        long now = level.getGameTime();
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        Set<UUID> openLines = new LinkedHashSet<>();
        for (ProductionOrder<ItemKey, RackPosition> order : orders)
            if (order.isOpen())
                order.parentLine().ifPresent(openLines::add);
        StringBuilder status = new StringBuilder("orders=");
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            if (!plan.containsValue(order.id()))
                throw new VisualTestException("a production order the click did not plan appeared: " + order);
            if (order.state() == ProductionOrderState.TIMED_OUT || order.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the " + order.result() + " order ended " + order.state() + ": " + order);
            status.append(order.result().getItem().toString().replace("minecraft:", "")).append(':')
                    .append(order.state()).append('/').append(order.produced()).append(' ');
            if (order.deliveredIngredients() > 0)
                firstFed.putIfAbsent(order.id(), now);
            if (!order.isOpen()) {
                if (order.state() == ProductionOrderState.COMPLETE)
                    completedAt.putIfAbsent(order.id(), now);
                continue;
            }
            boolean blocked = order.lines().stream().anyMatch(line -> openLines.contains(line.id()));
            // Bottom-up: an order whose child step is still open is fed nothing, and its station holds nothing
            // unless an order that is not blocked works at the same station (the crushing station makes both steps).
            if (blocked) {
                blockedTicks.merge(order.id(), 1L, Long::sum);
                if (order.deliveredIngredients() != 0)
                    throw new VisualTestException("the " + order.result() + " order was fed "
                            + order.deliveredIngredients() + " ingredient(s) while its child step was open: " + order);
                boolean stationShared = orders.stream().anyMatch(other -> other.isOpen()
                        && other.station().equals(order.station())
                        && other.lines().stream().noneMatch(line -> openLines.contains(line.id())));
                if (!stationShared && station(level, context.origin().offset(stationOf(order.result())))
                        .hasBufferedItems())
                    throw new VisualTestException("the station of the " + order.result() + " order holds items while "
                            + "its child step is open");
            }
            String fingerprint = order.state() + "/" + order.deliveredIngredients() + "/" + order.produced();
            if (blocked || !fingerprint.equals(fingerprints.put(order.id(), fingerprint))) {
                lastProgress.put(order.id(), now);
                continue;
            }
            long gap = now - lastProgress.getOrDefault(order.id(), now);
            largestGap.merge(order.id(), gap, Math::max);
            if (gap >= DEFAULT_TIMEOUT_TICKS)
                throw new VisualTestException("the " + order.result() + " order made no progress for " + gap + " ticks");
        }
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("the safety stop holds " + controller.pausedStockRuleCount() + " item(s)");
        List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(context.origin().offset(TERMINAL));
        if (requests.size() > 1 || requests.stream().anyMatch(request -> !request.id().equals(requestId)
                || request.requested() != ORDER))
            throw new VisualTestException("the terminal's requests changed: " + requests);
        RoboBeeLedger running = ledger;
        if (running != null)
            status.append("bees=").append(running.inFlight()).append(" carrying=").append(running.carrying())
                    .append(" trips=").append(running.trips().size());
        serverStatus = status.toString().trim();
    }

    /** Every server poll of the chain: the ledger's verdict of the ticks since. */
    private void check(MinecraftServer server, VisualContext context) {
        ledger().check();
    }

    // =================================================================================================================
    // the census (server thread)
    // =================================================================================================================

    /** Both sites and the player, plus every box in flight and every bee in the air. */
    private Map<ItemKey, Long> census(MinecraftServer server, VisualContext context) {
        return MultiSiteCensus.take(server.overworld(), context.serverPlayer(server), ledger,
                List.of(warehouseBox, crushingBox, meltingBox));
    }

    /** Cobblestone, gravel, sand and glass one unit each; a glass pane 3/8 of one (6 glass make 16). */
    private static long chainUnits(Map<ItemKey, Long> counts) {
        long panes = counts.getOrDefault(PANE, 0L);
        if (panes * 3 % 8 != 0)
            throw new VisualTestException(panes + " glass panes are no whole number of crafts");
        return counts.getOrDefault(COBBLE, 0L) + counts.getOrDefault(GRAVEL, 0L) + counts.getOrDefault(SAND, 0L)
                + counts.getOrDefault(GLASS, 0L) + panes * 3 / 8;
    }

    /**
     * Every key but the chain's and the by-products' exactly as in the baseline (bees, goggles, everything); the chain
     * units add up to the chest of cobblestone; the by-products never shrink and stay within one roll per gravel;
     * {@code expected} chain items (where given) exactly.
     */
    private void assertCensus(MinecraftServer server, VisualContext context, String moment,
            Map<ItemKey, Long> expected) {
        Map<ItemKey, Long> actual = census(server, context);
        Map<ItemKey, Long> rest = new HashMap<>(actual);
        Map<ItemKey, Long> baseRest = new HashMap<>(baseline);
        for (ItemKey key : CHAIN) {
            rest.remove(key);
            baseRest.remove(key);
        }
        for (ItemKey key : BY_PRODUCTS) {
            rest.remove(key);
            baseRest.remove(key);
        }
        if (!rest.equals(baseRest))
            throw new VisualTestException("item conservation violated at " + moment + ": besides the chain "
                    + SceneItemCensus.describe(rest) + ", expected " + SceneItemCensus.describe(baseRest));
        long units = chainUnits(actual);
        if (units != INTAKE)
            throw new VisualTestException("item conservation violated at " + moment + ": " + units + " chain units, "
                    + "expected " + INTAKE + ": " + SceneItemCensus.describe(actual));
        for (ItemKey key : BY_PRODUCTS) {
            long now = actual.getOrDefault(key, 0L);
            long before = byProductsSeen.getOrDefault(key, 0L);
            if (now < before || now > STEP_AMOUNT)
                throw new VisualTestException(key + " went from " + before + " to " + now + " at " + moment);
            byProductsSeen.put(key, now);
        }
        for (ItemKey key : CHAIN) {
            long want = expected.getOrDefault(key, 0L);
            if (actual.getOrDefault(key, 0L) != want)
                throw new VisualTestException("at " + moment + " the census holds " + actual.getOrDefault(key, 0L)
                        + " " + key + ", expected " + want + ": " + SceneItemCensus.describe(actual));
        }
        LOGGER.info(PREFIX + "robobee: census PASS: {} (bees in the air {}; items {})", moment,
                ledger == null ? 0 : ledger().inFlight(), SceneItemCensus.describe(actual));
    }

    // =================================================================================================================
    // input (client thread)
    // =================================================================================================================

    private static ArmVisualScenario.Click click(String label, BlockPos block, Direction face, Vec3 eyeOffset) {
        return RealClicks.click(label, block, face, eyeOffset);
    }

    /** A right-click on {@code click}: camera, crosshair checked on the intended face, one press of the use key. */
    private void rightClick(VisualScript script, ArmVisualScenario.Click click) {
        RealClicks.rightClick(script, NAME, click);
    }

    private void fly(VisualScript script) {
        RealClicks.fly(script, NAME);
    }

    private void worldShots(VisualScript script, String moment, CameraView... views) {
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        for (CameraView view : views)
            script.shotFrom(view, moment);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    /**
     * Whether the client has one of CMP's bee entities within a few blocks of the port at {@code port}, well inside the
     * camera's picture (within {@value #BEE_VIEW_DEGREES} degrees of where it looks), so the shot really shows it.
     */
    private static boolean beeNear(VisualContext context, BlockPos port) {
        ClientLevel level = context.minecraft().level;
        if (level == null || context.minecraft().player == null)
            return false;
        Vec3 centre = Vec3.atCenterOf(context.origin().offset(port));
        AABB around = new AABB(centre, centre).inflate(BEE_SHOT_RANGE);
        Vec3 eye = context.minecraft().player.getEyePosition();
        Vec3 look = context.minecraft().player.getLookAngle();
        double cos = Math.cos(Math.toRadians(BEE_VIEW_DEGREES));
        for (Entity entity : level.getEntities((Entity) null, around, entity -> true)) {
            if (!RoboBeeLedger.ROBO_BEE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))
                continue;
            Vec3 toBee = entity.position().subtract(eye);
            boolean clear = entity.getY() >= centre.y + BEE_CLEAR_OF_PORT;
            if (clear && toBee.length() > 2.0 && toBee.normalize().dot(look) >= cos)
                return true;
        }
        return false;
    }

    // =================================================================================================================
    // lookups (server thread)
    // =================================================================================================================

    private RoboBeeLedger ledger() {
        RoboBeeLedger running = ledger;
        if (running == null)
            throw new VisualTestException("the bee ledger has not started");
        return running;
    }

    private ProductionOrder<ItemKey, RackPosition> order(MinecraftServer server, VisualContext context, ItemKey result) {
        UUID id = Objects.requireNonNull(plan.get(result), "no planned order for " + result);
        return controller(server.overworld(), context).productionOrder(id)
                .orElseThrow(() -> new VisualTestException("the controller forgot the " + result + " order"));
    }

    private ProductionOrderState stateOf(MinecraftServer server, VisualContext context, ItemKey result) {
        check(server, context);
        return order(server, context, result).state();
    }

    private static boolean beltEmpty(ServerLevel level, VisualContext context) {
        BeltBlockEntity belt = BeltHelper.getControllerBE(level,
                context.origin().offset(MELTING_FACTORY).offset(M_BELT_START));
        return belt == null || belt.getInventory().getTransportedItems().isEmpty();
    }

    private static boolean holds(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return false;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (handler.getStackInSlot(slot).is(item))
                return true;
        return false;
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return FeederVisualScenario.inward(layout, rack);
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                context.origin().offset(CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                context.origin().offset(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal is missing");
        return terminal;
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos pos) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level, pos);
        if (station == null)
            throw new VisualTestException("the production station at " + pos + " is missing");
        return station;
    }

    private static WarehouseStationBlockEntity inputStation(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof WarehouseStationBlockEntity station)
            return station;
        throw new VisualTestException("the warehouse input at " + pos + " is missing");
    }

    private static PackagerBlockEntity packager(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PackagerBlockEntity packager)
            return packager;
        throw new VisualTestException("the Packager at " + pos + " is missing");
    }

    private static EncasedFanBlockEntity fan(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof EncasedFanBlockEntity fan)
            return fan;
        throw new VisualTestException("the encased fan at " + pos + " is missing");
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
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
}
