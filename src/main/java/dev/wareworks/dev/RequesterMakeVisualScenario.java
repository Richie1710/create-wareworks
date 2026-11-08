package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.EnumSet;
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
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonScreen;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.content.requester.CannonView;
import dev.wareworks.content.requester.RequesterSummary;
import dev.wareworks.content.requester.SchematicannonRequesterBlock;
import dev.wareworks.content.requester.SchematicannonRequesterBlockEntity;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
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
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.requester.RequesterLimits;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterStatus;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "requester-make" (issue #45, ADR-081): a Schematicannon Requester has what the warehouse lacks <b>made</b>
 * through a production pattern — stock first, the rest from the player's machine — set up and watched the way a player
 * does it, in English and German.
 * <p>
 * <b>The scene.</b> A powered aisle running east. On its right wall the warehouse terminal with the {@code requester}
 * scenario's route out of its buffer behind it (an andesite belt funnel, a belt on a creative motor, a funnel into a
 * chest) and a Schematicannon beside that chest, with a Schematicannon Requester facing it; on its left wall a
 * Warehouse Production station <b>without a pattern</b>, a Warehouse Crafter Feeder on its back and a vanilla Crafter in
 * front of the feeder whose output faces a Warehouse Input — the {@code feeder} scenario's machine, mirrored. The racks
 * hold {@value #PLANKS_IN_STOCK} oak planks and {@value #LOGS_IN_STOCK} oak logs. The schematic is a floor of
 * {@value #FLOOR_BLOCKS} oak planks, saved as a player's upload. The positions the shared helpers of
 * {@link RequesterVisualScenario} read ({@code checkGoggles}: terminal and requester; {@code beltArrivals}: the belt's
 * first segment) are the same as there.
 * <p>
 * <b>The story</b> (first pass), every step a player's:
 * <ol>
 * <li>The floor's schematic and one gunpowder are carried into the cannon with clicks in its own screen
 * ({@code make-scene}).</li>
 * <li>A sneaking right-click links the Warehouse Remote to the terminal, a right-click puts it into the requester, and
 * Play is pressed in the cannon's screen.</li>
 * <li>Stock first: the one order takes the {@value #PLANKS_IN_STOCK} planks in stock and has nothing made, because no
 * pattern makes oak planks; the belt brings them and the cannon prints them.</li>
 * <li>Then the warehouse has no planks and no pattern: the lamp is red and over three retry windows nothing is ordered
 * and no production order exists ({@code make-no-pattern}, goggles in English and German).</li>
 * <li>The player writes the pattern "one oak log makes four oak planks" into the station's screen with real clicks
 * ({@code make-pattern}).</li>
 * <li>Within {@value RequesterLimits#MAKE_RETRY_POLLS} polls and one the requester orders the
 * {@value #MADE_ORDER} the floor still needs, all of them made, and its own production root backs the request; the
 * lamp is lit and the goggles say how many are being made ({@code make-making-goggles}, English and German, the ticks
 * frozen). While they are being made the dead-man clock stands and nothing is ordered a second time.</li>
 * <li>The machine crafts (frozen in the tick the Crafter crafts: {@code make-crafting}), the crane carries the planks
 * to the terminal ({@code make-carrying}), the belt takes them to the cannon ({@code make-belt}) and the floor is
 * printed ({@code make-printed}). Nothing is ordered while they lie in the buffer or ride the belt.</li>
 * </ol>
 * The second pass (Flywheel off) re-shoots the scene and the printed floor.
 * <p>
 * <b>Items.</b> {@link #census} counts the scene and the player ({@link SceneItemCensus}) plus what no capability
 * reports: the cannon's slots and its blocks in flight, the remote in the cradle, an item a funnel handed the belt in
 * the tick that just ended, and every printed block as the item its requirement took. It is exact around every act on
 * the <b>craft ledger</b>: the expected counts plus a whole number of crafts (one oak log in, four oak planks out), never
 * fewer crafts than already seen. The declared changes are the gunpowder burnt as fuel, the remote's link (part of its
 * item key) and the remote copied into the cradle in creative mode; the empty schematic Create gives back once the print
 * is done is counted as the schematic it was.
 */
public final class RequesterMakeVisualScenario implements VisualScenario {
    public static final String NAME = "requester-make";

    private static final String WORLD_FOLDER = "wareworks_visual_requester_make";
    /** A print, a production run and four language switches. */
    private static final long RUN_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    // --- the warehouse (offsets from the dock; the aisle runs east, so the right rack side is +z) --------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 6;
    private static final int CRANE_RPM = 64;
    /** The feeder lays a run every {@code 256 / 32 = 8} ticks ({@code FeederCycle#ticksPerRun}). */
    private static final int FEEDER_RPM = 32;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition STATION_RACK = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = RackPosition.of(2, 0, Side.LEFT);
    private static final RackPosition LOG_RACK = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition PLANK_RACK = RackPosition.of(4, 0, Side.RIGHT);
    private static final List<RackPosition> STORAGE = List.of(LOG_RACK, RackPosition.of(4, 0, Side.LEFT),
            RackPosition.of(3, 0, Side.RIGHT), PLANK_RACK);

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    /** Where {@link #TERMINAL_RACK} stands, as in {@link RequesterVisualScenario}; {@link #buildAisle} checks it. */
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);
    /** Where {@link #STATION_RACK} stands; {@link #buildAisle} checks it. */
    private static final BlockPos STATION = new BlockPos(1, 0, -1);
    /** On the station's back; it faces along the rack wall towards the Crafter. */
    private static final BlockPos FEEDER = STATION.north();
    /** The feeder's shaft runs vertically, so its motor sits on top and turns downwards. */
    private static final BlockPos FEEDER_MOTOR = FEEDER.above();
    /** In front of the feeder and behind the input, its output facing the input. */
    private static final BlockPos CRAFTER = FEEDER.relative(AISLE);

    // --- the player's belt from the terminal's buffer to the cannon's chest (as in RequesterVisualScenario) ----------
    private static final BlockPos BELT_START = new BlockPos(1, -1, 2);
    private static final BlockPos BELT_END = new BlockPos(1, -1, 8);
    private static final Direction BELT_MOVEMENT = Direction.SOUTH;
    private static final BlockPos BELT_MOTOR = new BlockPos(2, -1, 2);
    private static final int BELT_RPM = -32;
    private static final BlockPos OUT_FUNNEL = BELT_START.above();
    private static final BlockPos IN_FUNNEL = BELT_END.above();
    private static final BlockPos BELT_STOP = BELT_END.relative(BELT_MOVEMENT);
    private static final BlockPos CHEST = IN_FUNNEL.relative(BELT_MOVEMENT);
    private static final BlockPos CANNON = CHEST.east();
    private static final BlockPos REQUESTER = CANNON.east();
    private static final BlockPos LIFT = new BlockPos(-3, 6, 4);
    private static final double LINK_DISTANCE = 2.0;

    // --- the schematic -------------------------------------------------------------------------------------------------
    /** A square floor of oak planks, well clear of the cannon. */
    private static final BlockPos SITE = new BlockPos(4, 0, 12);
    private static final int SITE_SIZE = 4;
    private static final int FLOOR_BLOCKS = SITE_SIZE * SITE_SIZE;
    private static final String FLOOR_FILE = "wareworks_requester_make_floor.nbt";

    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey GUNPOWDER = ItemKey.of(Items.GUNPOWDER);
    private static final int PLANKS_IN_STOCK = 5;
    private static final int LOGS_IN_STOCK = 16;
    /** The first order is the stock, the second the rest of the floor, all of it made. */
    private static final int STOCK_ORDER = PLANKS_IN_STOCK;
    private static final int MADE_ORDER = FLOOR_BLOCKS - STOCK_ORDER;
    private static final int PLANKS_PER_RUN = 4;
    private static final int RUNS = (MADE_ORDER + PLANKS_PER_RUN - 1) / PLANKS_PER_RUN;
    private static final int MADE_RESULT = RUNS * PLANKS_PER_RUN;

    // --- the pattern and the Schematicannon's slots ----------------------------------------------------------------
    private static final int PATTERN_SLOT = 0;
    private static final int LOG_CELL = 0;
    private static final int CANNON_SLOT_SCHEMATIC = 0;
    private static final int CANNON_SLOT_OUTPUT = 1;
    private static final int CANNON_SLOT_GUNPOWDER = 4;

    // --- the area ----------------------------------------------------------------------------------------------------
    private static final int CLEAR_MIN_X = -4;
    private static final int CLEAR_MAX_X = SITE.getX() + SITE_SIZE + 4;
    private static final int CLEAR_MIN_Z = -5;
    private static final int CLEAR_MAX_Z = SITE.getZ() + SITE_SIZE + 3;
    private static final int CLEAR_HEIGHT = 8;
    private static final int CENSUS_MIN_X = -3;
    private static final int CENSUS_MAX_X = SITE.getX() + SITE_SIZE + 1;
    private static final int CENSUS_MIN_Y = -1;
    private static final int CENSUS_MAX_Y = 5;
    private static final int CENSUS_MIN_Z = -4;
    private static final int CENSUS_MAX_Z = SITE.getZ() + SITE_SIZE + 1;

    // --- timing ------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int MESSAGE_TIMEOUT_TICKS = 60;
    private static final int SNEAK_TIMEOUT_TICKS = 40;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int CANNON_TIMEOUT_TICKS = 400;
    private static final int MOMENT_TIMEOUT_TICKS = 1200;
    private static final int ORDER_TIMEOUT_TICKS = 3600;
    private static final int PRINT_TIMEOUT_TICKS = 3600;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    /** Ticks Create's goggle overlay needs to fade out once nothing is targeted any more. */
    private static final int GOGGLE_FADE_TICKS = 20;
    /** Retry windows of the requester the run watches while no pattern makes oak planks. */
    private static final int WATCHED_RETRY_WINDOWS = 3;
    /** Polls the clock has to be seen standing for the check to mean something. */
    private static final int MIN_STANDING_POLLS = 2;
    /** A crane this far from the terminal is still well inside the aisle. */
    private static final double MIN_REMAINING_TRAVEL = 0.5;
    private static final float BELT_SHOT_FROM = 1.0f;
    private static final float BELT_SHOT_TO = 5.5f;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_REMOTE = 0;
    private static final int SLOT_SCHEMATIC = 1;
    private static final int SLOT_GUNPOWDER = 2;
    private static final int SLOT_LOG = 3;
    private static final int SLOT_PLANKS = 4;
    /** Never filled: the slot {@link RequesterVisualScenario#takeAnEmptyHand} selects. */
    private static final int SLOT_EMPTY = 8;

    // --- clicks ------------------------------------------------------------------------------------------------------
    private static final ArmVisualScenario.Click CLICK_CANNON = new ArmVisualScenario.Click("cannon", CANNON,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 1.9, -1.8));
    private static final ArmVisualScenario.Click CLICK_REQUESTER = new ArmVisualScenario.Click("requester", REQUESTER,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(1.8, 1.9, 0.0));
    /** The station's top face, seen from above the aisle. */
    private static final ArmVisualScenario.Click CLICK_STATION = new ArmVisualScenario.Click("production station",
            STATION, Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 1.9, 1.6));

    // --- camera views (relative to the dock's lower corner) -----------------------------------------------------------
    /** The requester's back, the cannon and its chest, the belt up to the terminal and the aisle behind it. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 8.5, 5.5, REQUESTER.getZ() + 2.5, 2.0, 0.0,
            4.5);
    /** The machine from outside the left rack wall: the station, the feeder with its motor, the Crafter, the input. */
    private static final CameraView MACHINE = CameraView.of("machine", 0.2, 3.2, -5.2, 1.8, 0.3, -1.2);
    /** Close on the Crafter's top and its north face, which light up while it crafts. */
    private static final CameraView AT_CRAFTER = CameraView.of("crafter", 2.6, 2.4, -3.6, 2.5, 0.5, -1.5);
    private static final CameraView REQUESTER_CLOSE = CameraView.of("close", REQUESTER.getX() + 3.2, 1.8,
            REQUESTER.getZ() + 2.4, REQUESTER.getX(), 0.5, REQUESTER.getZ());
    private static final CameraView ALONG_BELT = CameraView.of("belt", -2.8, 3.4, 5.0, 1.5, -0.4, 5.7);
    /** Down the aisle from its far end: the crane between the rack walls. */
    private static final CameraView ALONG_AISLE = CameraView.of("aisle", RAILS + 3.0, 3.6, 3.5, 2.0, 1.0, 0.0);
    /** The floor's site, seen over the cannon. */
    private static final CameraView AT_SITE = CameraView.of("site", SITE.getX() + 6.0, 5.0, SITE.getZ() - 4.0,
            SITE.getX() + 1.5, 0.0, SITE.getZ() + 1.5);
    /** The requester's top face from its back, within reach: what a player with goggles sees. */
    private static final CameraView GOGGLE_VIEW = CameraView.of("goggles", REQUESTER.getX() + 2.6, 2.3,
            REQUESTER.getZ() + 0.5, REQUESTER.getX() + 0.5, 1.0, REQUESTER.getZ() + 0.5);

    /** The position a server waits for the client to confirm after a teleport, {@code null} once it has. */
    private static final Field AWAITING_TELEPORT = awaitingTeleportField();

    /** Server thread: the census box of the scene, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the census must find right now, before any craft. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Server thread: the crafts the census has seen so far; it may only grow. */
    private volatile int crafted;
    @Nullable
    private volatile ItemKey remoteKey;
    private volatile ItemStack floorSchematic = ItemStack.EMPTY;
    /** Server thread: every order the requester placed, by request id, with its share when first seen. */
    private final Map<UUID, Integer> shares = new LinkedHashMap<>();
    /** Server thread: every production order the run has seen. */
    private final Set<UUID> productionOrders = new LinkedHashSet<>();
    /** Server thread: the phases the made order went through. */
    private final Set<RequesterOrder.Phase> madePhases = EnumSet.noneOf(RequesterOrder.Phase.class);
    @Nullable
    private volatile UUID madeRequest;
    @Nullable
    private volatile UUID madeRoot;
    /** Server thread: the live age the made order showed while being made, and the game times it was seen so. */
    private volatile int makingAge = -1;
    private volatile long makingFrom = -1L;
    private volatile long makingTo = -1L;
    private volatile long watchSince = Long.MIN_VALUE;
    /** Client thread: the English goggle lines, so the German ones can prove they are a translation. */
    private final Map<String, String> englishLines = new HashMap<>();
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** Creative with the vanilla reach: every block of the story is clicked and opened by a right-click. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("requester-make: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "requester-make run"))
                .server("requester-make: clear the area and place the creative motors", this::placeMotors)
                .server("requester-make: build the floor, save it as a schematic and clear the site",
                        this::saveFloor)
                .server("requester-make: build the aisle, the machine, the cannon and the requester", this::buildAisle)
                .server("requester-make: build the belt from the terminal's buffer to the chest", this::buildBelt)
                .server("requester-make: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("requester-make: the player flies, wears Engineer's Goggles and carries what the story needs",
                        this::equipPlayer)
                .serverUntil("requester-make: wait until the warehouse has indexed every rack and the belt runs",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("requester-make: CHECK the cannon waits for its blocks and the baseline census",
                        (server, context) -> {
                            SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
                            if (cannon.skipMissing)
                                throw new VisualTestException("the Schematicannon skips missing blocks, so it would "
                                        + "never ask for planks");
                            expected = census(server, context);
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS the cannon does not skip missing blocks; "
                                    + "baseline census {}", SceneItemCensus.describe(expected));
                        });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            loadTheCannon(script);
            linkTheRemote(script);
            insertTheRemote(script);
            startThePrint(script);
            stockFirst(script);
            noPattern(script);
            writeThePattern(script);
            theMadeOrder(script);
            theMachine(script);
            theFloorIsPrinted(script);
            return;
        }
        worldShots(script, "make-scene", OVERVIEW, MACHINE);
        worldShots(script, "make-printed", AT_SITE);
        script.client("requester-make: every check passed", context -> LOGGER.info(PREFIX + "requester-make: ALL "
                + "CHECKS PASSED in English and German (stock first with nothing made while no pattern made oak "
                + "planks, red and nothing ordered or made without the pattern, the pattern written with clicks, one "
                + "order of the rest all made and backed by its own production root, the clock standing and no "
                + "second order while it was made, crafted by the player's machine, carried, belted and printed, and "
                + "every census exact on the craft ledger)"));
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        Optional<SchematicannonRequesterBlockEntity> requester = clientRequester(context);
        if (requester.isPresent()) {
            RequesterSummary summary = requester.get().summary();
            BlockState state = requester.get().getBlockState();
            status.append(String.format(Locale.ROOT, "requester=%s cannon=%s rows=%s lit=%s refused=%s",
                    summary.status(), summary.cannon(), summary.rows(),
                    state.getOptionalValue(SchematicannonRequesterBlock.LIT).orElse(false),
                    state.getOptionalValue(SchematicannonRequesterBlock.REFUSED).orElse(false)));
        } else {
            status.append("requester=none");
        }
        ClientLevel level = context.minecraft().level;
        if (level != null) {
            if (level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane) {
                CranePose pose = crane.craneState().pose();
                status.append(String.format(Locale.ROOT, " crane=%s posX=%.2f posY=%.2f arm=%.2f held=%d",
                        crane.craneState().phase(), pose.x(), pose.y(), pose.arm(), crane.goggleInfo().heldCount()));
            }
            BlockState crafter = level.getBlockState(context.origin().offset(CRAFTER));
            if (crafter.hasProperty(CrafterBlock.CRAFTING))
                status.append(" crafting=").append(crafter.getValue(CrafterBlock.CRAFTING));
        }
        Screen open = context.minecraft().screen;
        status.append(" screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected());
        return status + GoggleShots.describeHover(context);
    }

    // --- 1 and 2: the cannon, the remote, Play -------------------------------------------------------------------------

    /** The floor's schematic and one gunpowder go into the cannon's screen with clicks; the gunpowder is burnt at once. */
    private void loadTheCannon(VisualScript script) {
        worldShots(script, "make-scene", OVERVIEW, MACHINE);
        openCannonScreen(script);
        RequesterVisualScenario.moveInMenu(script, "the floor's schematic", "the cannon's blueprint slot",
                RequesterVisualScenario.hotbar(SLOT_SCHEMATIC), RequesterVisualScenario.menuSlot(CANNON_SLOT_SCHEMATIC));
        RequesterVisualScenario.moveInMenu(script, "the gunpowder", "the cannon's gunpowder slot",
                RequesterVisualScenario.hotbar(SLOT_GUNPOWDER), RequesterVisualScenario.menuSlot(CANNON_SLOT_GUNPOWDER));
        script.serverUntil("requester-make: the cannon read the floor and burnt the gunpowder as fuel",
                this::floorLoaded, CANNON_TIMEOUT_TICKS);
        RequesterVisualScenario.closeScreen(script, "the cannon's screen");
    }

    private boolean floorLoaded(MinecraftServer server, VisualContext context) {
        SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
        if (cannon.printer.isErrored())
            throw new VisualTestException("the Schematicannon could not read the floor: " + cannon.statusMsg);
        if (!cannon.printer.isLoaded() || !cannon.inventory.getStackInSlot(CANNON_SLOT_GUNPOWDER).isEmpty()
                || cannon.remainingFuel != cannon.getShotsPerGunpowder())
            return false;
        int required = cannon.checklist.getRequiredAmount(Items.OAK_PLANKS);
        if (required != FLOOR_BLOCKS)
            throw new VisualTestException("the cannon's material list asks for " + required + " oak planks, expected "
                    + FLOOR_BLOCKS + ": " + cannon.checklist.required);
        expected = SceneItemCensus.plus(expected, GUNPOWDER, -1);
        assertCensus(server, context, "the cannon burnt one gunpowder as fuel");
        LOGGER.info(PREFIX + "requester-make: CHECK PASS the cannon read the floor ({} oak planks) and burnt one "
                + "gunpowder, state {} '{}'", required, cannon.state, cannon.statusMsg);
        return true;
    }

    /** Standing beside the terminal, sneaking, a real click on the use key links the remote to the terminal. */
    private void linkTheRemote(VisualScript script) {
        RequesterVisualScenario.selectHotbar(script, SLOT_REMOTE, "the Warehouse Remote",
                WareworksItems.WAREHOUSE_REMOTE::isIn);
        script.server("requester-make: the player stops flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }).camera("link", context -> linkView());
        ArmVisualScenario.untilOrFail(script, "requester-make: the player stands on the ground beside the terminal",
                context -> context.minecraft().player.onGround() && !context.minecraft().player.getAbilities().flying,
                SNEAK_TIMEOUT_TICKS, context -> "the player is at " + context.minecraft().player.position());
        RequesterVisualScenario.sneak(script, true);
        ArmVisualScenario.untilOrFail(script, "requester-make: the crosshair is on the terminal",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(context.origin().offset(TERMINAL)),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the terminal");
        RequesterVisualScenario.useKey(script, "the terminal while sneaking");
        ArmVisualScenario.untilOrFail(script, "requester-make: the client's remote is linked to the terminal",
                context -> {
                    RemoteLink link = WarehouseRemoteItem.link(context.minecraft().player.getMainHandItem());
                    return link != null && link.terminal().pos().equals(context.origin().offset(TERMINAL));
                }, MESSAGE_TIMEOUT_TICKS, context -> "the remote in hand is "
                        + context.minecraft().player.getMainHandItem());
        RequesterVisualScenario.actionBar(script, "the link", context -> {
            BlockPos pos = context.origin().offset(TERMINAL);
            return WareworksLang.translateDirect(WareworksLang.REMOTE_LINKED, pos.getX(), pos.getY(), pos.getZ());
        });
        script.server("requester-make: CHECK the server's link and the census", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context);
            RemoteLink wanted = new RemoteLink(GlobalPos.of(Level.OVERWORLD, terminal.getBlockPos()),
                    terminal.terminalId());
            RemoteLink link = WarehouseRemoteItem.link(player.getMainHandItem());
            if (!wanted.equals(link))
                throw new VisualTestException("the remote is linked to " + link + ", not to " + wanted);
            // The link is part of the remote's item key: the census swaps the old key for the new one, nothing else.
            Map<ItemKey, Long> next = SceneItemCensus.plus(expected, Objects.requireNonNull(remoteKey, "remoteKey"), -1);
            remoteKey = ItemKey.of(player.getMainHandItem());
            expected = SceneItemCensus.plus(next, remoteKey, 1);
            assertCensus(server, context, "the remote was linked");
            LOGGER.info(PREFIX + "requester-make: CHECK PASS a sneaking right-click linked the remote to the terminal");
        });
        RequesterVisualScenario.sneak(script, false);
        fly(script);
    }

    /** A right-click on the requester with the linked remote: it goes into the cradle and the player owns the orders. */
    private void insertTheRemote(VisualScript script) {
        rightClick(script, CLICK_REQUESTER);
        RequesterVisualScenario.actionBar(script, "the insert", context -> {
            BlockPos pos = context.origin().offset(TERMINAL);
            return WareworksLang.translateDirect(WareworksLang.REQUESTER_LINKED, pos.getX(), pos.getY(), pos.getZ());
        });
        script.server("requester-make: CHECK the remote is in the cradle and the player owns the orders",
                        (server, context) -> {
                            ServerPlayer player = context.serverPlayer(server);
                            SchematicannonRequesterBlockEntity requester = requester(server.overworld(), context);
                            ItemKey linked = Objects.requireNonNull(remoteKey, "remoteKey");
                            if (!requester.hasRemote() || !ItemKey.of(requester.remote()).equals(linked))
                                throw new VisualTestException("the cradle holds " + requester.remote());
                            if (!requester.owner().equals(Optional.of(player.getUUID())))
                                throw new VisualTestException("the orders belong to " + requester.owner());
                            // Creative mode puts a copy in and leaves the hand as it was.
                            expected = SceneItemCensus.plus(expected, linked, 1);
                            assertCensus(server, context, "the remote went into the cradle");
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS the remote is in the cradle, owned by {}",
                                    player.getGameProfile().getName());
                        })
                .serverUntil("requester-make: the requester sees a cannon that waits to be started",
                        (server, context) -> showing(server, context, RequesterStatus.NOT_PRINTING),
                        SYNC_TIMEOUT_TICKS);
    }

    /** Play pressed in the cannon's own screen. */
    private void startThePrint(VisualScript script) {
        openCannonScreen(script);
        script.client("requester-make: click Play", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.cannonPlayButton(RequesterVisualScenario.cannonScreen(context))))
                .serverUntil("requester-make: the cannon runs", (server, context) -> !"ready".equals(
                        cannon(server.overworld(), context).statusMsg), CLICK_TIMEOUT_TICKS);
        RequesterVisualScenario.closeScreen(script, "the cannon's screen");
    }

    // --- 3 and 4: stock first, then red without a pattern --------------------------------------------------------------

    /** The first order takes what is in stock and has nothing made: no pattern makes oak planks yet. */
    private void stockFirst(VisualScript script) {
        script.serverUntil("requester-make: the cannon misses planks and the requester orders the stock",
                        (server, context) -> {
                            watch(server, context);
                            return runningOrder(server, context).isPresent();
                        }, ORDER_TIMEOUT_TICKS)
                .server("requester-make: CHECK stock first", (server, context) -> {
                    RequesterOrder<ItemKey> order = runningOrder(server, context).orElseThrow();
                    RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                    if (order.share() != STOCK_ORDER || order.producing() != 0 || order.root().isPresent()
                            || order.ahead() != 0 || request.requested() != STOCK_ORDER
                            || !request.key().equals(PLANKS))
                        throw new VisualTestException("the first order is " + order + " for " + request
                                + ", expected " + STOCK_ORDER + " oak planks from stock with nothing made");
                    WarehouseControllerBlockEntity controller = controller(server.overworld(), context);
                    if (!controller.openProductionOrders().isEmpty())
                        throw new VisualTestException("production started without a pattern: "
                                + controller.openProductionOrders());
                    if (!showing(server, context, RequesterStatus.ORDERED))
                        throw new VisualTestException("the requester shows " + requester(server.overworld(), context)
                                .status() + " for a stock order");
                    assertCensus(server, context, "the stock order was placed");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS stock first: one order of {} oak planks granted "
                            + "{} with nothing made, no production order (no pattern makes oak planks yet)",
                            order.share(), request.requested());
                })
                .serverUntil("requester-make: the cannon printed the stock and misses planks again; the requester is "
                        + "out of stock", (server, context) -> {
                            watch(server, context);
                            return printedPlanks(server.overworld(), context) == STOCK_ORDER
                                    && requester(server.overworld(), context).orders().isEmpty()
                                    && showing(server, context, RequesterStatus.OUT_OF_STOCK);
                        }, PRINT_TIMEOUT_TICKS)
                .server("requester-make: CHECK the stock reached the floor", (server, context) -> {
                    assertCensus(server, context, "the stock was printed");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS the belt brought the {} oak planks and the cannon "
                            + "printed them", STOCK_ORDER);
                });
    }

    /**
     * Three retry windows without a pattern: the lamp is red, nothing is ordered and no production order exists — the
     * warehouse has no planks and no pattern makes them.
     */
    private void noPattern(VisualScript script) {
        int window = RequesterLimits.makeRetryTicks(WareworksConfig.requesterPollIntervalTicks());
        script.server("requester-make: start watching the requester without a pattern",
                        (server, context) -> watchSince = server.overworld().getGameTime())
                .serverUntil("requester-make: CHECK nothing is made while no pattern makes oak planks",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            watch(server, context);
                            assertNoRequests(server, context, "while no pattern makes oak planks");
                            if (!controller(level, context).productionOrders().isEmpty())
                                throw new VisualTestException("a production order exists without a pattern: "
                                        + controller(level, context).productionOrders());
                            if (!requester(level, context).orders().isEmpty())
                                throw new VisualTestException("an order runs without stock or pattern: "
                                        + requester(level, context).orders());
                            if (!showing(server, context, RequesterStatus.OUT_OF_STOCK))
                                throw new VisualTestException("the requester shows "
                                        + requester(level, context).status() + " without stock or pattern");
                            CannonView cannon = CannonView.read(level, context.origin().offset(CANNON));
                            if (cannon.phase() != CannonView.Phase.MISSING || !PLANKS.matches(cannon.missing()))
                                throw new VisualTestException("the cannon is " + cannon.phase() + ", missing "
                                        + cannon.missing());
                            long watched = level.getGameTime() - watchSince;
                            if (watched < (long) WATCHED_RETRY_WINDOWS * window)
                                return false;
                            assertCensus(server, context, "the requester waited without a pattern");
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS nothing is made while no pattern makes oak "
                                    + "planks: {} ticks ({} retry windows of {}), red lamp, no request, no production "
                                    + "order", watched, WATCHED_RETRY_WINDOWS, window);
                            return true;
                        }, WATCHED_RETRY_WINDOWS * window + MOMENT_TIMEOUT_TICKS);
        worldShots(script, "make-no-pattern", REQUESTER_CLOSE, OVERVIEW);
        goggles(script, "make-no-pattern-goggles", context -> synced(context, RequesterStatus.OUT_OF_STOCK, 0, 0),
                context -> {
                    RequesterSummary summary = clientSummary(context);
                    if (summary.missing() != Items.OAK_PLANKS)
                        throw new VisualTestException("the goggles say the cannon misses " + summary.missing());
                    String line = RequesterVisualScenario.text(
                            WareworksLang.requesterStatusKey(RequesterStatus.OUT_OF_STOCK),
                            Items.OAK_PLANKS.getDescription());
                    RequesterVisualScenario.checkGoggles(context, "no pattern", List.of(
                            RequesterVisualScenario.text(WareworksLang.GOGGLES_REQUESTER_CANNON_MISSING,
                                    Items.OAK_PLANKS.getDescription(), WareworksLang.number(summary.missingCount())),
                            line));
                    requireTranslated(context, "no pattern", line);
                });
    }

    // --- 5: the pattern, written with clicks -----------------------------------------------------------------------------

    /** "One oak log makes four oak planks", written into the station's screen with real clicks and the mouse wheel. */
    private void writeThePattern(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        rightClick(script, CLICK_STATION);
        ArmVisualScenario.untilOrFail(script, "requester-make: the station's screen is open with the feeder flag",
                context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                        && screen.state().feeder(), SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
        FeederVisualScenario.patternEntry(script, PATTERN_SLOT, SLOT_LOG, Items.OAK_LOG, LOG_CELL);
        FeederVisualScenario.patternEntry(script, PATTERN_SLOT, SLOT_PLANKS, Items.OAK_PLANKS,
                ProductionMenuLayout.RESULT_CELL);
        FeederVisualScenario.scrollResult(script, PATTERN_SLOT, PLANKS_PER_RUN);
        script.until("requester-make: the pattern passed the Crafter's recipe check",
                        context -> productionScreen(context).state().check(PATTERN_SLOT).check()
                                == CrafterPatternCheck.VALID, SCREEN_TIMEOUT_TICKS);
        // No reach: nothing is under the crosshair any more, so the station's goggle tooltip leaves the picture.
        GoggleShots.reach(script, NAME, 0.0);
        script.client("requester-make: rest the cursor on the result cell",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.productionCell(productionScreen(context), ProductionMenuLayout.RESULT_CELL)))
                .waitTicks(GOGGLE_FADE_TICKS)
                .shotWithGui("make-pattern");
        RequesterVisualScenario.closeScreen(script, "the station's screen");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.server("requester-make: CHECK the pattern was written with clicks", (server, context) -> {
            ServerLevel level = server.overworld();
            WarehouseProductionBlockEntity station = station(level, context);
            ProductionPatterns patterns = station.patterns();
            if (!patterns.keyAt(PATTERN_SLOT, LOG_CELL).equals(Optional.of(LOG))
                    || patterns.countAt(PATTERN_SLOT, LOG_CELL) != 1
                    || !patterns.keyAt(PATTERN_SLOT, ProductionPatterns.RESULT_ENTRY).equals(Optional.of(PLANKS))
                    || patterns.countAt(PATTERN_SLOT, ProductionPatterns.RESULT_ENTRY) != PLANKS_PER_RUN
                    || patterns.filledCells(PATTERN_SLOT) != 1)
                throw new VisualTestException("the station's pattern is " + patterns);
            if (station.crafterCheck(PATTERN_SLOT).check() != CrafterPatternCheck.VALID)
                throw new VisualTestException("the Crafter check says " + station.crafterCheck(PATTERN_SLOT));
            if (!controller(level, context).producibleKeys().contains(PLANKS))
                throw new VisualTestException("the aisle makes " + controller(level, context).producibleKeys());
            assertCensus(server, context, "the pattern was written");
            LOGGER.info(PREFIX + "requester-make: CHECK PASS the pattern was written with clicks: 1 oak log makes {} "
                    + "oak planks, the Crafter check passed, the census is unchanged", PLANKS_PER_RUN);
        });
    }

    // --- 6: the made order -----------------------------------------------------------------------------------------------

    /**
     * The order of the rest, all of it made, backed by the requester's own production root; the lamp lit and the
     * goggles, with the ticks frozen so the made part cannot run out under the camera.
     */
    private void theMadeOrder(VisualScript script) {
        int poll = WareworksConfig.requesterPollIntervalTicks();
        script.serverUntil("requester-make: the requester orders the rest within " + (RequesterLimits.MAKE_RETRY_POLLS
                        + 1) + " polls", (server, context) -> {
                            watch(server, context);
                            return runningOrder(server, context).filter(RequesterOrder::isMaking).isPresent();
                        }, (RequesterLimits.MAKE_RETRY_POLLS + 1) * poll + SETTLE_TICKS)
                .server("requester-make: CHECK one order of the rest, all being made, its own root backs it",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            RequesterOrder<ItemKey> order = runningOrder(server, context).orElseThrow();
                            RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                            if (order.share() != MADE_ORDER || order.producing() != MADE_ORDER || order.ahead() != 0
                                    || order.root().isEmpty() || request.requested() != MADE_ORDER)
                                throw new VisualTestException("the made order is " + order + " for " + request
                                        + ", expected " + MADE_ORDER + " oak planks, all of them made");
                            UUID rootId = order.root().get();
                            ProductionOrder<ItemKey, RackPosition> root = controller(level, context)
                                    .productionOrder(rootId).orElseThrow(() -> new VisualTestException(
                                            "the record's root " + rootId + " is no production order"));
                            if (!root.isOpen() || root.isStep() || !root.result().equals(PLANKS)
                                    || !root.backingRequest().equals(Optional.of(order.requestId()))
                                    || root.resultAmount() != MADE_RESULT)
                                throw new VisualTestException("the root is " + root + ", expected an open root of "
                                        + MADE_RESULT + " oak planks backing request " + order.requestId());
                            List<ProductionOrder<ItemKey, RackPosition>> open = controller(level, context)
                                    .openProductionOrders();
                            if (open.size() != 1)
                                throw new VisualTestException("the open production orders are " + open);
                            if (!showing(server, context, RequesterStatus.MAKING))
                                throw new VisualTestException("the requester shows "
                                        + requester(level, context).status() + " with its lamp "
                                        + requester(level, context).getBlockState());
                            madeRequest = order.requestId();
                            madeRoot = rootId;
                            assertCensus(server, context, "the made order was placed");
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS one order of {}, all being made, its own "
                                    + "root backs it: request {} asks for {}, root {} makes {} for it (promised {}), "
                                    + "lamp lit", order.share(), request.id(), request.requested(), rootId,
                                    root.resultAmount(), root.promisedToRequest());
                        });
        fly(script);
        script.camera(GOGGLE_VIEW);
        ArmVisualScenario.untilOrFail(script, "requester-make: the goggles show the order being made",
                context -> synced(context, RequesterStatus.MAKING, MADE_ORDER, MADE_ORDER), MOMENT_TIMEOUT_TICKS,
                context -> "the requester shows " + clientRequester(context).map(r -> r.summary().toString())
                        .orElse("nothing"));
        script.serverUntil("requester-make: freeze while the order is being made", (server, context) -> {
                    watch(server, context);
                    if (requester(server.overworld(), context).status() != RequesterStatus.MAKING
                            || runningOrder(server, context).filter(RequesterOrder::isMaking).isEmpty())
                        throw new VisualTestException("the order was no longer being made when the goggles were "
                                + "ready: " + requester(server.overworld(), context).orders());
                    server.tickRateManager().setFrozen(true);
                    return true;
                }, SYNC_TIMEOUT_TICKS)
                .until("requester-make: the client sees the frozen moment", RequesterMakeVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester-make: census while the order is being made",
                        (server, context) -> assertCensus(server, context, "the order is being made"));
        goggles(script, "make-making-goggles",
                context -> synced(context, RequesterStatus.MAKING, MADE_ORDER, MADE_ORDER), context -> {
                    RequesterSummary.Row row = clientRow(context);
                    String line = RequesterVisualScenario.text(WareworksLang.requesterStatusKey(RequesterStatus.MAKING),
                            row.item().getDescription(), WareworksLang.number(row.share()),
                            WareworksLang.number(row.made()), WareworksLang.number(row.delivered()));
                    RequesterVisualScenario.checkGoggles(context, "making", List.of(line));
                    requireTranslated(context, "making", line);
                });
        script.freeze(false);
    }

    // --- 7: the machine, the crane, the belt ------------------------------------------------------------------------------

    private void theMachine(VisualScript script) {
        script.serverUntil("requester-make: freeze the tick the Crafter crafts", (server, context) -> {
                    watch(server, context);
                    if (!server.overworld().getBlockState(context.origin().offset(CRAFTER))
                            .getValue(CrafterBlock.CRAFTING))
                        return false;
                    server.tickRateManager().setFrozen(true);
                    return true;
                }, ORDER_TIMEOUT_TICKS)
                .until("requester-make: the client sees the frozen craft", context -> clientFrozen(context)
                        && context.minecraft().level.getBlockState(context.origin().offset(CRAFTER))
                                .getValue(CrafterBlock.CRAFTING), SYNC_TIMEOUT_TICKS)
                .server("requester-make: CHECK the player's machine crafts the made order", (server, context) -> {
                    ProductionOrder<ItemKey, RackPosition> root = madeRootOrder(server, context);
                    assertCensus(server, context, "the Crafter crafts");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS the Crafter crafts for the requester's root ({} "
                            + "of {} stored, {}), {} crafts on the ledger", root.produced(), root.resultAmount(),
                            root.state(), crafted);
                });
        worldShots(script, "make-crafting", AT_CRAFTER, MACHINE);
        script.freeze(false)
                .serverUntil("requester-make: freeze the moment the crane carries planks to the terminal",
                        this::freezeWhileCarrying, ORDER_TIMEOUT_TICKS)
                .until("requester-make: the client sees the frozen carry", RequesterMakeVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester-make: CHECK the crane carries the made planks", (server, context) -> {
                    StackerCraneBlockEntity crane = crane(server.overworld(), context);
                    ProductionOrder<ItemKey, RackPosition> root = madeRootOrder(server, context);
                    assertCensus(server, context, "the crane carries the made planks");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS the crane carries {} oak planks to the terminal "
                            + "for request {}; the root has {} of {} stored ({})", planksHeld(crane), madeRequest,
                            root.produced(), root.resultAmount(), root.state());
                });
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        script.camera("carry", RequesterMakeVisualScenario::carryView)
                .shot("make-carrying-crane");
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        worldShots(script, "make-carrying", ALONG_AISLE);
        script.freeze(false)
                .serverUntil("requester-make: the root is complete and the record's made part ended",
                        (server, context) -> {
                            watch(server, context);
                            ProductionOrder<ItemKey, RackPosition> root = madeRootOrder(server, context);
                            if (root.isOpen())
                                return false;
                            if (root.state() != ProductionOrderState.COMPLETE)
                                throw new VisualTestException("the requester's root ended " + root.state() + ": "
                                        + root);
                            return requester(server.overworld(), context).orders().stream()
                                    .noneMatch(RequesterOrder::isMaking);
                        }, ORDER_TIMEOUT_TICKS)
                .server("requester-make: CHECK the made part ended and the clock stood while it was made",
                        (server, context) -> {
                            ProductionOrder<ItemKey, RackPosition> root = madeRootOrder(server, context);
                            long stood = makingTo - makingFrom;
                            int poll = WareworksConfig.requesterPollIntervalTicks();
                            if (makingAge < 0 || stood < (long) MIN_STANDING_POLLS * poll)
                                throw new VisualTestException("the order was seen being made for only " + stood
                                        + " ticks, too short to prove the clock stands");
                            assertCensus(server, context, "the made part ended");
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS the root is {} ({} of {} made); the "
                                    + "record's made part ended", root.state(), root.produced(), root.resultAmount());
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS the dead-man clock stands while being "
                                    + "made: live age {} for {} ticks of making", makingAge, stood);
                            LOGGER.info(PREFIX + "requester-make: CHECK PASS no second order while being made: orders "
                                    + "{}", shares);
                        })
                .serverUntil("requester-make: freeze the moment the made planks ride the belt", this::freezeOnTheBelt,
                        ORDER_TIMEOUT_TICKS)
                .until("requester-make: the client sees the frozen belt", RequesterMakeVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester-make: CHECK the made planks ride the belt", (server, context) -> {
                    assertCensus(server, context, "the made planks ride the belt");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS {} oak planks ride the belt, the order is {}",
                            beltPlanks(server.overworld(), context), requester(server.overworld(), context).orders());
                });
        worldShots(script, "make-belt", ALONG_BELT);
        script.freeze(false)
                .serverUntil("requester-make: CHECK no order during the buffer and the grace", (server, context) -> {
                    watch(server, context);
                    if (!requester(server.overworld(), context).orders().isEmpty())
                        return false;
                    if (!madePhases.containsAll(EnumSet.allOf(RequesterOrder.Phase.class)))
                        throw new VisualTestException("the made order went through " + madePhases + " only");
                    assertCensus(server, context, "the made order is done");
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS no order during the buffer and the grace: the "
                            + "made order went through {}, orders {}", madePhases, shares);
                    return true;
                }, ORDER_TIMEOUT_TICKS);
    }

    /**
     * Freezes the ticks while the crane carries oak planks towards the terminal, still well inside the aisle, with the
     * made order running.
     */
    private boolean freezeWhileCarrying(MinecraftServer server, VisualContext context) {
        watch(server, context);
        StackerCraneBlockEntity crane = crane(server.overworld(), context);
        CranePose target = crane.craneState().target();
        if (runningOrder(server, context).isEmpty() || planksHeld(crane) <= 0
                || crane.craneState().phase() != CranePhase.TRAVEL_TO_TARGET
                || Math.abs(target.x() - TERMINAL_RACK.x()) > 1.0e-6 || target.side() != TERMINAL_RACK.side()
                || remainingTravel(crane) <= MIN_REMAINING_TRAVEL)
            return false;
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /** Freezes the ticks while made planks ride the middle of the belt and the order is no longer running. */
    private boolean freezeOnTheBelt(MinecraftServer server, VisualContext context) {
        watch(server, context);
        ServerLevel level = server.overworld();
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt == null || runningOrder(server, context).isPresent())
            return false;
        boolean midBelt = belt.getInventory().getTransportedItems().stream().anyMatch(riding -> PLANKS.matches(
                riding.stack) && riding.beltPosition >= BELT_SHOT_FROM && riding.beltPosition <= BELT_SHOT_TO);
        if (!midBelt)
            return false;
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /** The floor is printed: the stock, the shares, the one production order, the crafts. */
    private void theFloorIsPrinted(VisualScript script) {
        script.serverUntil("requester-make: the floor is printed and nothing is left on the way", (server, context) -> {
                    watch(server, context);
                    ServerLevel level = server.overworld();
                    SchematicannonBlockEntity cannon = cannon(level, context);
                    return "finished".equals(cannon.statusMsg) && cannon.flyingBlocks.isEmpty()
                            && requester(level, context).orders().isEmpty();
                }, PRINT_TIMEOUT_TICKS)
                .server("requester-make: CHECK the floor, the stock and the orders", (server, context) -> {
                    ServerLevel level = server.overworld();
                    assertNothingOnTheWay(level, context);
                    int printed = printedPlanks(level, context);
                    WarehouseControllerBlockEntity controller = controller(level, context);
                    long planks = controller.countOf(PLANKS);
                    long logs = controller.countOf(LOG);
                    if (printed != FLOOR_BLOCKS || planks != MADE_RESULT - MADE_ORDER || logs != LOGS_IN_STOCK - RUNS)
                        throw new VisualTestException("the floor has " + printed + " oak planks, the warehouse "
                                + planks + " oak planks and " + logs + " oak logs");
                    if (!List.copyOf(shares.values()).equals(List.of(STOCK_ORDER, MADE_ORDER)))
                        throw new VisualTestException("the requester placed the orders " + shares + ", expected "
                                + List.of(STOCK_ORDER, MADE_ORDER));
                    if (productionOrders.size() != 1 || !productionOrders.contains(madeRoot))
                        throw new VisualTestException("the run saw the production orders " + productionOrders
                                + ", expected only the root " + madeRoot);
                    SchematicannonRequesterBlockEntity requester = requester(level, context);
                    if (requester.timeouts() != 0 || requester.orderedSinceList(Items.OAK_PLANKS) != FLOOR_BLOCKS)
                        throw new VisualTestException("the requester gave up " + requester.timeouts()
                                + " orders and counts " + requester.orderedSinceList(Items.OAK_PLANKS) + " ordered");
                    if (!AllItems.EMPTY_SCHEMATIC.isIn(cannon(level, context).inventory
                            .getStackInSlot(CANNON_SLOT_OUTPUT)))
                        throw new VisualTestException("the finished cannon gave no empty schematic back");
                    assertCensus(server, context, "the floor is printed");
                    if (crafted != RUNS)
                        throw new VisualTestException("the census saw " + crafted + " crafts, expected " + RUNS);
                    LOGGER.info(PREFIX + "requester-make: CHECK PASS the cannon printed {} oak planks, {} logs "
                            + "crafted, {} plank left in stock: {} logs left, orders {}, exactly one production order, "
                            + "no timeouts", printed, RUNS, planks, logs, shares.values());
                });
        worldShots(script, "make-printed", AT_SITE, OVERVIEW);
    }

    // --- the watch (server thread) -------------------------------------------------------------------------------------

    /**
     * Every poll from the first order on: never two orders and never a request the requester does not follow, a share
     * never changed after the order was placed, no production step and no timeout, and the clock of an order being made
     * standing still.
     */
    private void watch(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        SchematicannonRequesterBlockEntity requester = requester(level, context);
        WarehouseControllerBlockEntity controller = controller(level, context);
        List<RequesterOrder<ItemKey>> orders = requester.orders();
        if (orders.size() > 1)
            throw new VisualTestException("the requester ordered twice: " + orders);
        List<RetrievalRequest<ItemKey, BlockPos>> open = controller.requestsFor(context.origin().offset(TERMINAL));
        for (RetrievalRequest<ItemKey, BlockPos> request : open)
            if (orders.stream().noneMatch(order -> order.requestId().equals(request.id())))
                throw new VisualTestException("the terminal has a request the requester does not follow: " + request
                        + ", orders " + orders);
        for (RequesterOrder<ItemKey> order : orders) {
            if (!order.key().equals(PLANKS))
                throw new VisualTestException("the requester ordered " + order.key());
            Integer known = shares.putIfAbsent(order.requestId(), order.share());
            if (known != null && known != order.share())
                throw new VisualTestException("the share of " + order.requestId() + " changed from " + known + ": "
                        + order);
            if (order.requestId().equals(madeRequest))
                madePhases.add(order.phase());
            if (order.isMaking()) {
                long now = level.getGameTime();
                if (makingAge < 0) {
                    makingAge = order.liveAge();
                    makingFrom = now;
                } else if (order.liveAge() != makingAge) {
                    throw new VisualTestException("the dead-man clock ran while the order was being made: live age "
                            + makingAge + " became " + order.liveAge());
                }
                makingTo = now;
            }
        }
        for (ProductionOrder<ItemKey, RackPosition> order : controller.productionOrders()) {
            if (order.isStep())
                throw new VisualTestException("a production step was planned: " + order);
            productionOrders.add(order.id());
        }
        if (requester.timeouts() != 0)
            throw new VisualTestException("the dead-man switch gave up an order");
    }

    // --- the census (server thread) ------------------------------------------------------------------------------------

    /**
     * Everything in the scene and on the player, plus the cannon's slots and its blocks in flight, the remote in the
     * cradle, what a funnel just handed the belt, and the printed floor as the items it took.
     */
    private Map<ItemKey, Long> census(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = context.origin();
        Map<ItemKey, Long> counts = SceneItemCensus.take(level, censusBox, context.serverPlayer(server));
        if (level.getBlockEntity(origin.offset(CANNON)) instanceof SchematicannonBlockEntity cannon) {
            for (int slot = 0; slot < cannon.inventory.getSlots(); slot++)
                counts = RequesterVisualScenario.plus(counts, cannon.inventory.getStackInSlot(slot));
            for (LaunchedItem flying : cannon.flyingBlocks)
                counts = RequesterVisualScenario.plus(counts, flying.stack);
            // Create turns the printed schematic into an empty one in its output slot the moment the print is done
            // (SchematicannonBlockEntity#finishedPrinting), which can fall into any act after the last block; it is
            // counted as the schematic it was.
            ItemStack output = cannon.inventory.getStackInSlot(CANNON_SLOT_OUTPUT);
            if (cannon.inventory.getStackInSlot(CANNON_SLOT_SCHEMATIC).isEmpty() && AllItems.EMPTY_SCHEMATIC.isIn(output))
                counts = SceneItemCensus.plus(SceneItemCensus.plus(counts, ItemKey.of(output), -1),
                        ItemKey.of(floorSchematic), 1);
        }
        SchematicannonRequesterBlockEntity requester = requesterOrNull(level, context);
        if (requester != null)
            counts = RequesterVisualScenario.plus(counts, requester.remote());
        for (TransportedItemStack arriving : RequesterVisualScenario.beltArrivals(level, context))
            counts = RequesterVisualScenario.plus(counts, arriving.stack);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(SITE),
                origin.offset(SITE).offset(SITE_SIZE - 1, 0, SITE_SIZE - 1)))
            counts = RequesterVisualScenario.plusPrinted(level, counts, pos);
        return counts;
    }

    /** {@link #expected} after {@code runs} crafts: one oak log in and four oak planks out each. */
    private Map<ItemKey, Long> afterCrafts(int runs) {
        return SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -runs), PLANKS, (long) runs * PLANKS_PER_RUN);
    }

    /**
     * Fails the run unless the census is {@link #expected} plus a whole number of crafts, at least as many as already
     * seen and at most the made order's.
     */
    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        if (!SceneItemCensus.isFullyLoaded(server.overworld(), censusBox))
            throw new VisualTestException("the census box is not fully loaded at: " + step);
        Map<ItemKey, Long> actual = census(server, context);
        for (int runs = crafted; runs <= RUNS; runs++) {
            if (actual.equals(afterCrafts(runs))) {
                crafted = runs;
                LOGGER.info(PREFIX + "requester-make: census PASS: {} ({} crafts; items {})", step, runs,
                        SceneItemCensus.describe(actual));
                return;
            }
        }
        throw new VisualTestException("item conservation violated, " + step + ": expected "
                + SceneItemCensus.describe(afterCrafts(crafted)) + " plus up to " + (RUNS - crafted)
                + " crafts, but found " + SceneItemCensus.describe(actual));
    }

    // --- the goggles (client thread) -------------------------------------------------------------------------------

    /** Whether the client's summary shows {@code status} with one row of {@code share} and {@code made} (none for 0). */
    private static boolean synced(VisualContext context, RequesterStatus status, int share, int made) {
        return clientRequester(context).map(SchematicannonRequesterBlockEntity::summary)
                .filter(summary -> summary.status() == status && !summary.ownerName().isEmpty())
                .filter(summary -> share == 0 ? summary.rows().isEmpty()
                        : summary.rows().size() == 1 && summary.rows().getFirst().share() == share
                                && summary.rows().getFirst().made() == made)
                .isPresent();
    }

    /**
     * Remembers {@code line} in English; in German it must exist and differ from the English one, so a key missing from
     * {@code de_de.json} cannot pass as a translation.
     */
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
        LOGGER.info(PREFIX + "requester-make: CHECK PASS '{}' is the {} line of '{}'", line, selected, english);
    }

    /** The requester's goggle tooltip from {@link #GOGGLE_VIEW}, in English and German, back to English afterwards. */
    private void goggles(VisualScript script, String label, Predicate<VisualContext> synced,
            VisualScript.ClientAction check) {
        script.client("requester-make: clear the toasts before " + label,
                context -> context.minecraft().getToasts().clear());
        fly(script);
        GoggleShots.shot(script, NAME, GOGGLE_VIEW, label, origin -> origin.offset(REQUESTER), synced, check);
        language.switchTo(script, "requester-make: ", GERMAN);
        fly(script);
        GoggleShots.shot(script, NAME, GOGGLE_VIEW, label + "-de", origin -> origin.offset(REQUESTER), synced, check);
        language.switchTo(script, "requester-make: ", ENGLISH);
    }

    /** World shots by a player who reaches nothing, so no value box is drawn; the reach comes back afterwards. */
    private void worldShots(VisualScript script, String moment, CameraView... views) {
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        for (CameraView view : views)
            script.shotFrom(view, moment);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    /**
     * Above the right rack wall and looking down into the aisle at the frozen client crane, so the planks on its
     * handling head are in the middle of the picture.
     */
    private static CameraView carryView(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane))
            throw new VisualTestException("the client has no crane to look at");
        double x = crane.craneState().pose().x() + 0.5;
        return CameraView.of("carry", x + 0.6, 2.9, 2.9, x, 0.4, 0.5);
    }

    private static RequesterSummary clientSummary(VisualContext context) {
        return clientRequester(context).map(SchematicannonRequesterBlockEntity::summary)
                .orElseThrow(() -> new VisualTestException("the client has no requester"));
    }

    private static RequesterSummary.Row clientRow(VisualContext context) {
        List<RequesterSummary.Row> rows = clientSummary(context).rows();
        if (rows.size() != 1)
            throw new VisualTestException("the client's goggle summary has no single order row: " + rows);
        return rows.getFirst();
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    // --- input (client thread) -----------------------------------------------------------------------------------------

    /** A right-click on {@code click}: camera, crosshair checked on the intended face, one press of the use key. */
    private void rightClick(VisualScript script, ArmVisualScenario.Click click) {
        fly(script);
        script.camera(click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, "requester-make: the crosshair is on the " + click.face()
                        + " face of the " + click.label(), context -> ArmVisualScenario.aimedAt(context, click),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the " + click.face() + " face of " + click.block());
        script.serverUntil("requester-make: the server has the player's answer to every teleport",
                (server, context) -> teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        RequesterVisualScenario.useKey(script, "the " + click.label());
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * Whether the server has the client's confirmation of the last teleport: until then vanilla drops every click on a
     * block without a word ({@code ServerGamePacketListenerImpl#handleUseItemOn} checks
     * {@code awaitingPositionFromClient}), and every camera move of this run is a teleport.
     */
    private static boolean teleportConfirmed(ServerPlayer player) {
        try {
            return AWAITING_TELEPORT.get(player.connection) == null;
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read whether the server waits for a teleport: " + error);
        }
    }

    private static Field awaitingTeleportField() {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach the server's pending teleport", error);
        }
    }

    /** The cannon's own screen, opened with an empty hand, once the server's first contents of its menu arrived. */
    private void openCannonScreen(VisualScript script) {
        RequesterVisualScenario.takeAnEmptyHand(script);
        rightClick(script, CLICK_CANNON);
        ArmVisualScenario.untilOrFail(script, "requester-make: the Schematicannon's screen is open with its contents",
                context -> context.minecraft().screen instanceof SchematicannonScreen screen
                        && screen.getMenu().getStateId() > 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    /** The player in the air and flying, which every view above the ground needs. */
    private void fly(VisualScript script) {
        script.server("requester-make: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("requester-make: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    private static WarehouseProductionScreen productionScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open: " + context.minecraft().screen);
    }

    /** Standing on the ground west of the terminal, looking at its west face within reach. */
    private static CameraView linkView() {
        return new CameraView("link", new Vec3(TERMINAL.getX() - LINK_DISTANCE, Player.DEFAULT_EYE_HEIGHT,
                TERMINAL.getZ() + 0.5), new Vec3(TERMINAL.getX(), 0.5, TERMINAL.getZ() + 0.5));
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(CENSUS_MIN_X, CENSUS_MIN_Y, CENSUS_MIN_Z),
                dock.offset(CENSUS_MAX_X, CENSUS_MAX_Y, CENSUS_MAX_Z));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(CLEAR_MIN_X, 0, CLEAR_MIN_Z),
                dock.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(dock.offset(FEEDER_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.DOWN));
    }

    /** The floor, saved through Create's exporter as a player's upload, then cleared so all of it is missing. */
    private void saveFloor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos site = context.origin().offset(SITE);
        BlockPos far = site.offset(SITE_SIZE - 1, 0, SITE_SIZE - 1);
        for (BlockPos pos : BlockPos.betweenClosed(site, far))
            level.setBlockAndUpdate(pos.immutable(), Blocks.OAK_PLANKS.defaultBlockState());
        String owner = context.serverPlayer(server).getGameProfile().getName();
        floorSchematic = RequesterVisualScenario.saveSchematic(level, owner, FLOOR_FILE, site, far);
        for (BlockPos pos : BlockPos.betweenClosed(site, far))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    private void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
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
        if (!layout.rackPos(TERMINAL_RACK).equals(dock.offset(TERMINAL))
                || !layout.rackPos(STATION_RACK).equals(dock.offset(STATION))
                || !layout.rackPos(INPUT_RACK).equals(dock.offset(CRAFTER).relative(
                        FeederVisualScenario.inward(layout, INPUT_RACK))))
            throw new VisualTestException("the rack positions do not stand where the belt, the feeder and the "
                    + "Crafter expect them");
        level.setBlockAndUpdate(layout.rackPos(TERMINAL_RACK), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, FeederVisualScenario.inward(layout, TERMINAL_RACK)));
        level.setBlockAndUpdate(layout.rackPos(STATION_RACK), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, FeederVisualScenario.inward(layout, STATION_RACK)));
        level.setBlockAndUpdate(layout.rackPos(INPUT_RACK), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, FeederVisualScenario.inward(layout, INPUT_RACK)));
        for (RackPosition rack : STORAGE) {
            Direction outward = layout.sideDirection(rack.side());
            BlockPos barrel = layout.rackPos(rack).relative(outward);
            level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
            if (rack.equals(LOG_RACK))
                insert(level, barrel, LOG.toStack(LOGS_IN_STOCK));
            else if (rack.equals(PLANK_RACK))
                insert(level, barrel, PLANKS.toStack(PLANKS_IN_STOCK));
        }
        // The feeder on the station's back, facing along the rack wall; the Crafter in front of it, behind the input,
        // its output facing the input (ADR-067 D2), as in FeederVisualScenario on the other wall.
        level.setBlockAndUpdate(dock.offset(FEEDER), WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, AISLE)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        level.setBlockAndUpdate(dock.offset(CRAFTER), Blocks.CRAFTER.defaultBlockState().setValue(
                BlockStateProperties.ORIENTATION,
                FrontAndTop.fromFrontAndTop(FeederVisualScenario.inward(layout, INPUT_RACK), Direction.UP)));
    }

    /**
     * The {@code requester} scenario's route out of the terminal's buffer, running from the start: the funnel behind
     * the terminal, the belt on its motor, the funnel into the chest; then the cannon beside the chest and the requester
     * facing the cannon.
     */
    private void buildBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = context.origin();
        BlockPos start = origin.offset(BELT_START);
        BlockPos end = origin.offset(BELT_END);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the belt was not created between " + start + " and " + end);
        BlockPos motor = origin.offset(BELT_MOTOR);
        level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.WEST));
        motor(level, motor).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(origin.offset(BELT_STOP), Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(CHEST),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        RequesterVisualScenario.beltFunnel(level, origin.offset(OUT_FUNNEL), BELT_MOVEMENT, true);
        RequesterVisualScenario.beltFunnel(level, origin.offset(IN_FUNNEL), BELT_MOVEMENT.getOpposite(), false);
        level.setBlockAndUpdate(origin.offset(CANNON), AllBlocks.SCHEMATICANNON.getDefaultState());
        level.setBlockAndUpdate(origin.offset(REQUESTER), WareworksBlocks.SCHEMATICANNON_REQUESTER.getDefaultState()
                .setValue(SchematicannonRequesterBlock.FACING, Direction.WEST));
    }

    private void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().offset(LIFT);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /** Flying, wearing Engineer's Goggles, and the hotbar of the story; the selected slot is the empty one. */
    private void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
        remoteKey = ItemKey.of(remote);
        player.getInventory().setItem(SLOT_REMOTE, remote);
        player.getInventory().setItem(SLOT_SCHEMATIC, floorSchematic.copy());
        player.getInventory().setItem(SLOT_GUNPOWDER, GUNPOWDER.toStack(1));
        player.getInventory().setItem(SLOT_LOG, LOG.toStack(1));
        player.getInventory().setItem(SLOT_PLANKS, PLANKS.toStack(1));
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        BeltBlockEntity belt = BeltHelper.getSegmentBE(level, dock.offset(BELT_START));
        WarehouseCrafterFeederBlockEntity feeder = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                .getNullable(level, dock.offset(FEEDER));
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                dock.offset(STATION));
        if (controller == null || crane == null || belt == null || feeder == null || station == null
                || requesterOrNull(level, context) == null)
            return false;
        boolean ready = controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && controller.inputStations().size() == 1 && controller.outputStations().size() == 1
                && controller.productionStations().size() == 1 && crane.isControllerLinked()
                && crane.aisleLength() == RAILS && controller.countOf(PLANKS) == PLANKS_IN_STOCK
                && controller.countOf(LOG) == LOGS_IN_STOCK && belt.getSpeed() != 0 && feeder.running()
                && station.attachedFeeder().isPresent();
        if (!ready)
            return false;
        if (belt.getMovementFacing() != BELT_MOVEMENT)
            throw new VisualTestException("the belt moves " + belt.getMovementFacing() + ", not " + BELT_MOVEMENT);
        if (station.patterns().patternCount() != 0 || controller.producibleKeys().contains(PLANKS))
            throw new VisualTestException("the station starts with a pattern: " + station.patterns());
        if (!requester(level, context).cannonPos().equals(dock.offset(CANNON)))
            throw new VisualTestException("the requester does not face the cannon");
        return true;
    }

    // --- server checks ---------------------------------------------------------------------------------------------

    /** Whether the requester shows {@code status} with the lamp that status has. */
    private static boolean showing(MinecraftServer server, VisualContext context, RequesterStatus status) {
        SchematicannonRequesterBlockEntity requester = requester(server.overworld(), context);
        BlockState state = requester.getBlockState();
        return requester.status() == status
                && state.getValue(SchematicannonRequesterBlock.LIT) == status.isLit()
                && state.getValue(SchematicannonRequesterBlock.REFUSED) == status.isRefused();
    }

    private static Optional<RequesterOrder<ItemKey>> runningOrder(MinecraftServer server, VisualContext context) {
        return requester(server.overworld(), context).orders().stream()
                .filter(order -> order.isRunning() && order.key().equals(PLANKS)).findFirst();
    }

    private static RetrievalRequest<ItemKey, BlockPos> request(MinecraftServer server, VisualContext context, UUID id) {
        ServerLevel level = server.overworld();
        return controller(level, context).requestsFor(context.origin().offset(TERMINAL)).stream()
                .filter(request -> request.id().equals(id)).findFirst()
                .orElseThrow(() -> new VisualTestException("the terminal has no open request " + id));
    }

    private static void assertNoRequests(MinecraftServer server, VisualContext context, String when) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = controller(server.overworld(), context)
                .requestsFor(context.origin().offset(TERMINAL));
        if (!open.isEmpty())
            throw new VisualTestException("the terminal has open requests " + when + ": " + open);
    }

    /** The requester's own production root; fails the run once the controller forgot it. */
    private ProductionOrder<ItemKey, RackPosition> madeRootOrder(MinecraftServer server, VisualContext context) {
        UUID root = Objects.requireNonNull(madeRoot, "madeRoot");
        return controller(server.overworld(), context).productionOrder(root)
                .orElseThrow(() -> new VisualTestException("the controller no longer knows the root " + root));
    }

    private static int printedPlanks(ServerLevel level, VisualContext context) {
        int printed = 0;
        BlockPos site = context.origin().offset(SITE);
        for (BlockPos pos : BlockPos.betweenClosed(site, site.offset(SITE_SIZE - 1, 0, SITE_SIZE - 1)))
            if (level.getBlockState(pos).is(Blocks.OAK_PLANKS))
                printed++;
        return printed;
    }

    private static void assertNothingOnTheWay(ServerLevel level, VisualContext context) {
        if (terminal(level, context).hasBufferedItems())
            throw new VisualTestException("the buffer holds " + terminal(level, context).bufferedItems());
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt != null && !belt.getInventory().getTransportedItems().isEmpty())
            throw new VisualTestException("the belt still carries items");
        IItemHandler chest = handler(level, context.origin().offset(CHEST));
        for (int slot = 0; slot < chest.getSlots(); slot++)
            if (!chest.getStackInSlot(slot).isEmpty())
                throw new VisualTestException("the chest still holds " + chest.getStackInSlot(slot));
        if (!controller(level, context).requestsFor(context.origin().offset(TERMINAL)).isEmpty())
            throw new VisualTestException("requests are still open");
    }

    private static long beltPlanks(ServerLevel level, VisualContext context) {
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt == null)
            return 0;
        return belt.getInventory().getTransportedItems().stream().filter(riding -> PLANKS.matches(riding.stack))
                .mapToLong(riding -> riding.stack.getCount()).sum();
    }

    private static long planksHeld(StackerCraneBlockEntity crane) {
        long held = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries())
            if (entry.key().equals(PLANKS))
                held += entry.count();
        return held;
    }

    private static double remainingTravel(StackerCraneBlockEntity crane) {
        return Math.max(Math.abs(crane.craneState().target().x() - crane.craneState().pose().x()),
                Math.abs(crane.craneState().target().y() - crane.craneState().pose().y()));
    }

    // --- lookups -----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    @Nullable
    private static SchematicannonRequesterBlockEntity requesterOrNull(ServerLevel level, VisualContext context) {
        return WareworksBlockEntityTypes.SCHEMATICANNON_REQUESTER.getNullable(level,
                context.origin().offset(REQUESTER));
    }

    private static SchematicannonRequesterBlockEntity requester(ServerLevel level, VisualContext context) {
        SchematicannonRequesterBlockEntity requester = requesterOrNull(level, context);
        if (requester == null)
            throw new VisualTestException("the Schematicannon Requester is missing");
        return requester;
    }

    private static Optional<SchematicannonRequesterBlockEntity> clientRequester(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin().offset(REQUESTER)) instanceof SchematicannonRequesterBlockEntity
                requester ? Optional.of(requester) : Optional.empty();
    }

    private static SchematicannonBlockEntity cannon(ServerLevel level, VisualContext context) {
        SchematicannonBlockEntity cannon = AllBlockEntityTypes.SCHEMATICANNON.getNullable(level,
                context.origin().offset(CANNON));
        if (cannon == null)
            throw new VisualTestException("the Schematicannon is missing");
        return cannon;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return crane;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                context.origin().offset(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal is missing");
        return terminal;
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, VisualContext context) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                context.origin().offset(STATION));
        if (station == null)
            throw new VisualTestException("the production station is missing");
        return station;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                context.origin().offset(CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
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
