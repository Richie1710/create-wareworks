package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.Headings;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "comb": a warehouse whose rails <b>split</b> — one main run down a hall with three side aisles hanging off
 * it, worked by one crane (M22, issue #2, ADR-033, ADR-035). This is the shot the milestone is judged by, the way
 * {@link CornerVisualScenario} is the shot M21 is judged by.
 * <p>
 * <b>The build</b>, relative to the dock (the scene origin): the dock faces east, the controller sits behind it,
 * {@value #MAIN_RAILS} rails run east down the hall, and three side aisles of {@value #TOOTH_RAILS} rails each run
 * south out of it at offsets {@value #TEETH_TEXT}. The main run is aisle <b>A</b> — <i>one</i> letter, straight
 * through all three junctions — and the teeth are <b>B</b>, <b>C</b> and <b>D</b>, each numbered from its junction
 * outwards. Racks stand on every aisle, <b>at every junction</b>, and on the two blocks beside the middle junction
 * where the ownership rule has to decide:
 * <ul>
 * <li>iron on the main run, gold on the three blocks <b>north of the junctions</b> — the one rack a machine serves
 * while standing on the block two aisles share;</li>
 * <li>copper on aisle B, lapis on aisle C, diamond on aisle D, twelve rails and three more down the hall;</li>
 * <li><b>the mirror pair at the middle junction</b>: the block west of aisle C's first rail and the block east of it
 * are each laterally beside a straight rail of aisle C <i>and</i> of the main run, so the warehouse offers two
 * candidates for both and only the way the interface faces decides. The west one faces west, away from aisle C, so it
 * is C's (redstone); the east one faces south, away from the main run, so it is the run's (emerald). Two blocks of
 * identical geometry, two different aisles, and no new rule — this is the proof that the facing rule M21 built already
 * decides a tee.</li>
 * </ul>
 * A belt fed from a chest carries items into the warehouse input on <b>aisle B</b>; a warehouse terminal stands on
 * <b>aisle C</b>; a warehouse output with a hopper, a pull chest and a lever sits on <b>aisle D</b>, so goods from
 * every aisle really do reach one block. Every rack carries a store filter, so where each item type ends up is decided
 * before the run and asserted afterwards, and nothing but diamond can be stored past the second junction.
 * <p>
 * <b>The tour</b> of each pass: the comb from above, down the hall and across the teeth; then, with the game ticks
 * frozen, the six moments this scenario exists for — the machine <b>turning off</b> at a junction and the machine
 * <b>driving straight through</b> one, which are the two ways it can go and together are "the crane choosing a way";
 * its arm in the rack <b>at</b> a junction, its arm in a rack of a side aisle, the machine <b>carrying</b> down the
 * farthest aisle, and its arm in the <b>shared</b> block, reached from aisle C rather than across from the run it is
 * also beside. The Flywheel pass adds the goggles that name the aisles and their letters (the controller's
 * "Warehouse: N rails, 4 aisles" over "Aisles: A 12 · B 3 · C 3 · D 3", a rack of the main run, a rack of the far
 * aisle, and both halves of the mirror pair with the address each really has), the terminal with one stock list for
 * the whole comb, a retrieval out of aisle C into the output on aisle D — two turns in one trip, the only shape where
 * that happens — and the <b>failure chapter</b>.
 * <p>
 * <b>The failure chapter</b> is two different things a player can do, and they are told different things:
 * <ol>
 * <li>A <b>rail closed with a wrench</b> on the main run before the last junction. Discovery floods from the dock, so
 * the rails beyond a closed one are not reached at all and aisle D is simply <b>not part of the warehouse</b> any
 * more: the controller's goggles drop to three aisles and a shorter A, and the racks on D say "Not part of an aisle".
 * That is the whole of what a closed rail does — it cannot leave an aisle behind, which is why the state below needs a
 * configured maximum to exist at all.</li>
 * <li>{@code aisle.maxAisleLength} lowered under the running warehouse, which truncates the main run and takes the
 * last junction with it. Aisle D is then <b>kept</b> — it keeps its address and its stock — and its racks say on their
 * own goggles that the crane cannot reach them, while the controller names the maximum that stopped it. This is the
 * one genuinely unreachable state M22 introduced, and the shot is its evidence.</li>
 * </ol>
 * Both are undone again and the comb is shown whole afterwards, so the chapter ends where it started.
 * <p>
 * <b>Nothing here is only a picture.</b> Every frozen moment is reached by a <i>server</i> condition that freezes the
 * ticks in the same server tick, so what the shot shows is the machine's own state, and both the server pose and the
 * pose the client draws are checked against what the shot claims. The routes the shots are about are asserted against
 * the warehouse's own {@code RouteTable} before the first item moves, the ownership of both shared blocks is checked
 * against the warehouse rather than against the picture, the item census of the whole scene is taken before the first
 * item moves and checked at every moment and after every act, and which rack received what is read from the chests.
 */
public final class CombVisualScenario implements VisualScenario {
    public static final String NAME = "comb";
    /** Its own throw-away world: goggle tooltips need a non-spectator with the vanilla reach. */
    private static final String WORLD_FOLDER = "wareworks_visual_comb";
    /** The run builds, stores, retrieves, breaks and photographs twice; the default four minutes are not enough. */
    private static final long RUN_TIMEOUT_MILLIS = 40L * 60L * 1000L;

    private static final Direction MAIN_AISLE = Direction.EAST;
    private static final Direction TOOTH_AISLE = Direction.SOUTH;
    /** Rails east of the dock: one aisle, straight through every junction. */
    private static final int MAIN_RAILS = 12;
    /** Rails of each side aisle beyond its own junction, which is that aisle's position 0. */
    private static final int TOOTH_RAILS = 3;
    /** Where the side aisles branch off, as positions along the main run. Aisle {@code i + 1} hangs off {@code TEETH[i]}. */
    private static final int[] TEETH = { 4, 8, 12 };
    /** The same offsets for the class javadoc, which cannot read an array. */
    private static final String TEETH_TEXT = "4, 8 and 12";
    /** The junction the fixed junction cameras are aimed at, and the one the mirror pair stands beside. */
    private static final int MIDDLE_JUNCTION = TEETH[1];

    /**
     * Speed of the crane's creative motor. Deliberately the {@value} RPM of {@link CornerVisualScenario} and not the
     * 128 of the other crane scenarios: a quarter turn costs one block of travel
     * ({@code crane.turnPenaltyBlocks}), which at this speed takes eight ticks — a turn a camera can be pointed at.
     */
    private static final int MOTOR_RPM = 48;
    private static final int BELT_RPM = 32;
    /** Belt blocks between the feed chest and the warehouse input on aisle B. */
    private static final int FEED_BELT_LENGTH = 3;

    private static final Item MAIN_ITEM = Items.IRON_INGOT;
    private static final Item JUNCTION_ITEM = Items.GOLD_INGOT;
    private static final Item TOOTH_B_ITEM = Items.COPPER_INGOT;
    private static final Item TOOTH_C_ITEM = Items.LAPIS_LAZULI;
    private static final Item TOOTH_D_ITEM = Items.DIAMOND;
    /** The shared block that belongs to aisle C, because its interface faces away from C. */
    private static final Item SHARED_TOOTH_ITEM = Items.REDSTONE;
    /** The shared block that belongs to the main run, because its interface faces away from the run. */
    private static final Item SHARED_RUN_ITEM = Items.EMERALD;

    /** Branch indices of the three side aisles, in the order the discovery numbers them. */
    private static final int TOOTH_B = 1;
    private static final int TOOTH_C = 2;
    private static final int TOOTH_D = 3;

    /** The input station on aisle B, which the belt feeds: position 2 on its west side. */
    private static final RackPosition INPUT = new RackPosition(TOOTH_B, 2, 0, Side.RIGHT);
    /** The terminal on aisle C: position 2 on its west side, with one stock list for the whole comb. */
    private static final RackPosition TERMINAL = new RackPosition(TOOTH_C, 2, 0, Side.RIGHT);
    /**
     * The output station on aisle D — the <b>one block</b> every aisle delivers to — one level up, with the hopper and
     * the pull chest below it.
     */
    private static final RackPosition OUTPUT = new RackPosition(TOOTH_D, 2, 1, Side.RIGHT);

    /** The two blocks beside the middle junction that the warehouse offers to two aisles at once. */
    private static final RackPosition SHARED_ON_TOOTH = new RackPosition(TOOTH_C, 1, 0, Side.RIGHT);
    private static final RackPosition SHARED_ON_RUN = RackPosition.of(MIDDLE_JUNCTION + 1, 0, Side.RIGHT);

    /** The rack the goggle shot of the main run looks at. */
    private static final RackPosition GOGGLE_RACK_MAIN = RackPosition.of(6, 0, Side.LEFT);
    /**
     * The rack the goggle shots of the far aisle look at — the same block the failure chapter is about. It is the
     * <b>nearest</b> rack of that aisle on purpose: the planner always fills the nearest suitable one, so a shot of
     * any other would be a shot of an empty chest, and the chapter's claim is that a cut-off aisle keeps its address
     * <i>and</i> its stock.
     */
    private static final RackPosition GOGGLE_RACK_FAR = new RackPosition(TOOTH_D, 2, 0, Side.LEFT);

    /** Every storage location of the warehouse, with the item its store filter dedicates it to. */
    private static final List<Rack> RACKS = List.of(
            new Rack(RackPosition.of(2, 0, Side.LEFT), MAIN_ITEM),
            new Rack(GOGGLE_RACK_MAIN, MAIN_ITEM),
            new Rack(RackPosition.of(10, 0, Side.LEFT), MAIN_ITEM),
            new Rack(RackPosition.of(TEETH[0], 0, Side.LEFT), JUNCTION_ITEM),
            new Rack(RackPosition.of(TEETH[1], 0, Side.LEFT), JUNCTION_ITEM),
            new Rack(RackPosition.of(TEETH[2], 0, Side.LEFT), JUNCTION_ITEM),
            new Rack(SHARED_ON_RUN, SHARED_RUN_ITEM),
            new Rack(new RackPosition(TOOTH_B, 3, 0, Side.RIGHT), TOOTH_B_ITEM),
            new Rack(new RackPosition(TOOTH_B, 2, 0, Side.LEFT), TOOTH_B_ITEM),
            new Rack(SHARED_ON_TOOTH, SHARED_TOOTH_ITEM),
            new Rack(new RackPosition(TOOTH_C, 3, 0, Side.RIGHT), TOOTH_C_ITEM),
            new Rack(new RackPosition(TOOTH_C, 3, 0, Side.LEFT), TOOTH_C_ITEM),
            new Rack(GOGGLE_RACK_FAR, TOOTH_D_ITEM),
            new Rack(new RackPosition(TOOTH_D, 3, 0, Side.LEFT), TOOTH_D_ITEM));

    /**
     * Output-style members of the comb: the warehouse output on aisle D and the <b>terminal</b> on aisle C, which is a
     * {@code LocationKind.OUTPUT} member of its own because it is a delivery target too.
     */
    private static final int OUTPUT_STYLE_MEMBERS = 2;

    /** Every item type of the warehouse, for the terminal's stock list. */
    private static final List<Item> ITEM_TYPES = List.of(MAIN_ITEM, JUNCTION_ITEM, TOOTH_B_ITEM, TOOTH_C_ITEM,
            TOOTH_D_ITEM, SHARED_TOOTH_ITEM, SHARED_RUN_ITEM);

    /** One batch, and the amount of one push from the feed chest onto the belt. */
    private static final int BATCH = 16;
    /**
     * What one pass puts into the feed chest. Diamond comes in three batches because it is the only item type that can
     * only be stored past the second junction, and the moments of this scenario that are about driving the whole comb
     * are the diamond trips.
     */
    private static final List<ItemStack> STREAM = List.of(new ItemStack(MAIN_ITEM, BATCH),
            new ItemStack(JUNCTION_ITEM, BATCH), new ItemStack(SHARED_RUN_ITEM, BATCH / 2),
            new ItemStack(TOOTH_B_ITEM, BATCH), new ItemStack(SHARED_TOOTH_ITEM, BATCH / 2),
            new ItemStack(TOOTH_C_ITEM, BATCH), new ItemStack(TOOTH_D_ITEM, 3 * BATCH));
    /** What the output on aisle D hands out on a rising edge: lapis, which lies on aisle C — two turns away. */
    private static final int OUTPUT_REQUEST_AMOUNT = BATCH;

    /** The rail of the main run a wrench closes in the failure chapter: the one before the last junction. */
    private static final int CLOSED_RAIL = TEETH[2] - 1;
    /** The aisle length the failure chapter configures: short enough to take the last junction with it. */
    private static final int CUT_LENGTH = TEETH[2] - 1;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int CENSUS_MARGIN = 3;
    private static final int SETTLE_TICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int BELT_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int MOMENT_TIMEOUT_TICKS = 4800;
    private static final int ALL_STORED_TIMEOUT_TICKS = 9000;
    private static final int DELIVERY_TIMEOUT_TICKS = 3600;
    private static final int IDLE_TIMEOUT_TICKS = 4800;
    private static final int RESCAN_TIMEOUT_TICKS = 600;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    /** How long a message written into the action bar stays on screen ({@code Gui}: 60 ticks). */
    private static final int ACTION_BAR_TICKS = 60;
    /** Player height above the scene between camera moves, so a creative player never lands and stops flying. */
    private static final int FLY_HEIGHT = 14;

    /** Arm extension from which a shot shows the grabber inside a rack. */
    private static final double ARM_EXTENDED = 0.85;
    /**
     * How near a junction the machine has to be for the "driving straight through" shot, and how far it may be: close
     * enough that the junction is the subject of the frame, far enough that it has not arrived at it. The window is
     * {@value #APPROACH_FAR}{@code  - }{@value #APPROACH_NEAR} blocks wide, which at {@value #MOTOR_RPM} RPM is about
     * nine ticks on each approach.
     */
    private static final double APPROACH_NEAR = 0.5;
    private static final double APPROACH_FAR = 1.6;
    /** How far past a junction the machine's work has to lie for it to be driving <b>through</b> rather than to it. */
    private static final double BEYOND_THE_JUNCTION = 1.0;
    /** How far down a side aisle the machine has to be to count as working on it rather than turning onto it. */
    private static final double PAST_THE_JUNCTION = 1.0;
    /** Quarter-turn progress the mid-turn shot is taken at (see {@link CornerVisualScenario}). */
    private static final double TURN_PROGRESS_MIN = 0.3;
    private static final double TURN_PROGRESS_MAX = 0.5;
    /** How far the client's yaw may sit ahead of the server's at a frozen moment (a tick of turning is 0.125). */
    private static final double CLIENT_YAW_TOLERANCE = 0.4;
    private static final double BLOCK_CENTER = 0.5;
    private static final double POSITION_EPSILON = 1.0e-6;
    /** Polls between two diagnostic lines while a moment has not come yet (one poll per client tick). */
    private static final int WAITING_LOG_INTERVAL_POLLS = 100;

    /** The voices of a quarter turn: the deep iron groan of the swing and the metal clack it settles with. */
    private static final List<String> TURN_SOUNDS = List.of(SoundEvents.IRON_TRAPDOOR_OPEN.getLocation().toString(),
            SoundEvents.METAL_STEP.getLocation().toString());
    /** The one moment whose client check is about more than the aisle: a mid-turn shot must still show a turn. */
    private static final String TURN_MOMENT = "turn";
    /** The one moment during which no new items may be fed: what comes back is counted against what went in. */
    private static final String RETRIEVE_MOMENT = "retrieve";

    // --- cameras ----------------------------------------------------------------------------------------------------

    /** Straight down over the whole comb; the small northward target offset puts north at the top. */
    private static final CameraView TOP = CameraView.of("top", 5.5, 11.0, 0.5, 5.5, 0.0, 0.45);
    /** Down the hall from behind the dock: the main run going away and the three teeth hanging off it. */
    private static final CameraView HALL = CameraView.of("hall", -3.5, 7.5, -5.8, 7.0, 0.8, 1.2);
    /** From the south, where the three side aisles run towards the camera and read as three. */
    private static final CameraView TEETH_VIEW = CameraView.of("teeth", 10.5, 6.0, 10.0, 6.0, 0.7, 0.8);
    /**
     * Straight down aisle C from beyond its far end: the corridor of its rails leads the eye to the middle junction,
     * with one half of the mirror pair on either side of it and the machine, when there is one, in the middle.
     */
    private static final CameraView SHARED = CameraView.of("shared", 8.5, 4.4, 7.4, 8.0, 1.0, 1.4);
    /** The output cluster on aisle D: output, lever, hopper and the pull chest. */
    private static final CameraView OUTPUT_VIEW = CameraView.of("output", 8.6, 3.2, 5.0, 11.0, 0.9, 2.8);
    /** The closed rail on the main run, straight down the gap between the two racks beside it. */
    private static final CameraView CLOSED_RAIL_VIEW =
            CameraView.of("closed-rail", CLOSED_RAIL + BLOCK_CENTER, 2.6, -2.8, CLOSED_RAIL + BLOCK_CENTER, 0.25, 0.35);
    /** In aisle C, south of the terminal, looking at it: well inside the range a container screen stays open at. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 5.8, 2.6, 4.9, 7.4, 1.0, 2.6);

    /** Following camera ahead of the machine on the aisle it is named on (see {@link CornerVisualScenario}). */
    private static final String FRONT = "front";
    private static final double FRONT_AHEAD = 4.6;
    private static final double FRONT_ABOVE = 3.0;
    private static final double CARRIAGE_LOOK_HEIGHT = 1.3;
    /**
     * Straight down over the machine, high enough to clear its own mast: the whole junction and both aisles leaving
     * it, with the chassis's angle to the rails unmistakable. {@value #OVER_ABOVE} blocks is not a round number for
     * nothing - a mast is four blocks tall, and from much lower its perspective smear covers the very block the shot
     * is about.
     */
    private static final String OVER = "over";
    private static final double OVER_ABOVE = 8.0;
    private static final double OVER_BEHIND = 1.2;
    private static final double OVER_LOOK_ASIDE = 0.8;
    private static final double OVER_LOOK_HEIGHT = 0.4;
    /**
     * The close camera of every arm moment: low, steeply down, and <b>offset to the arm's own side</b>.
     * <p>
     * A rack wall hides an arm from every angle at its own height, and the mast hides it from the other side of the
     * aisle - the first version of this scenario tried that and photographed the mast. From here the mast is above the
     * camera and what is left of it leans away across the far half of the frame, so the arm bridging the gap between
     * the rails and the rack it reaches into is the only thing in the middle.
     */
    private static final String ARM_OVER = "arm";
    private static final double ARM_OVER_ABOVE = 3.6;
    private static final double ARM_OVER_ASIDE = 1.7;
    private static final double ARM_OVER_BEHIND = 0.6;
    /**
     * How far towards the rack the arm camera aims. It is deliberately short of the rack itself: at full extension the
     * grabber is <b>inside</b> the rack block and cannot be photographed from anywhere, so what the shot is really
     * about is the arm bridging the gap, and aiming at the machine keeps the warehouse in the frame around it.
     */
    private static final double ARM_LOOK_ASIDE = 0.3;
    private static final double ARM_LOOK_HEIGHT = 0.6;
    /**
     * Following three-quarter camera: behind the machine along its own aisle, off to the side its arm is <b>not</b> on,
     * and high over the rack wall.
     * <p>
     * The side is not a taste decision. A rack at a junction stands on the arm's side, so a camera there has the very
     * block the shot is about between itself and the arm — which is what the first framing of this scenario did. From
     * behind and across, the junction, the aisle leaving it and the arm are all in view at once.
     */
    private static final String SWING = "swing";
    private static final double SWING_ASIDE = 1.9;
    private static final double SWING_BACK = 4.6;
    private static final double SWING_ABOVE = 5.2;
    private static final double SWING_LOOK_HEIGHT = 1.6;
    private static final double SWING_LOOK_ASIDE = 0.5;

    /** Goggle camera: above the block, looking down on its top face, away from the value box on its aisle face. */
    private static final double GOGGLE_EYE_HEIGHT = 3.4;
    private static final double GOGGLE_EYE_BACK = 0.9;
    private static final double GOGGLE_TOP_FACE = 1.0;

    // --- run state (server steps write, the client status line reads) -------------------------------------------------

    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Every item of the scene, kept in step with what the run puts into the feed chest. */
    private volatile Map<ItemKey, Long> conserved = Map.of();
    /** What was fed per item type over the whole run, and what left the warehouse through the output. */
    private final Map<Item, Long> fed = new HashMap<>();
    private final Map<Item, Long> handedOut = new HashMap<>();
    /** The moment the run is photographing, for the shot log. */
    private volatile String moment = "none";
    /** Polls spent waiting for the current moment (server thread), so a long wait says what it is waiting for. */
    private int waitingPolls;
    /** The machine's own pose at the frozen moment, read on the server and checked against what the client draws. */
    private volatile CranePose poseAtMoment = StackerCraneBlockEntity.HOME_POSE;
    /** Every aisle the machine really stood on, so a shot cannot claim a trip the crane did not make. */
    private final Set<Integer> visitedAisles = new HashSet<>();
    /** What {@code aisle.maxAisleLength} was before the failure chapter lowered it. */
    private volatile int aisleLengthBefore = -1;

    /** A storage location of the warehouse and the item its store filter dedicates it to. */
    private record Rack(RackPosition position, Item item) {
    }

    /** A state the run waits for, tested on the server and again on the client that draws it. */
    @FunctionalInterface
    private interface CraneMoment {
        boolean holds(CraneState<ItemKey, RackPosition> state, long held);
    }

    /** A camera of a moment: a fixed view, or one computed from the frozen crane when the step starts. */
    private record MomentView(String label, Function<VisualContext, CameraView> view) {
        static MomentView fixed(CameraView view) {
            return new MomentView(view.label(), context -> view);
        }

        static MomentView following(String label, Function<VisualContext, CameraView> view) {
            return new MomentView(label, view);
        }
    }

    // --- the moments -------------------------------------------------------------------------------------------------

    /**
     * On a junction block, between two headings: the machine <b>turning off</b>. Every job of this scenario starts with
     * one, because the input stands on aisle B and everything has to come out of it onto the run.
     */
    private static final CraneMoment TURNING = (state, held) -> {
        CranePose pose = state.pose();
        double progress = turnProgress(pose);
        return !pose.isAligned() && onAJunction(pose) && progress >= TURN_PROGRESS_MIN
                && progress <= TURN_PROGRESS_MAX;
    };

    /**
     * Square with the main run, carrying, a block short of a junction, and with its work <b>at least a block beyond</b>
     * that junction: the machine about to drive <b>straight over</b> a block two aisles share. This is the other way a
     * machine can go at a junction, and together with {@link #TURNING} it is the whole of "the crane choosing a way" on
     * a comb.
     * <p>
     * All three conditions are needed, and the first version of this scenario had only the last: a machine that has
     * just <i>turned onto</i> the run at a junction also stands on that junction, aligned and carrying, so the shot
     * came out as the machine <b>leaving</b> a junction it had turned at — a true picture of the wrong thing. Requiring
     * the junction to lie ahead of the machine on the way to its work, with a block of clearance on either side, is
     * what makes the shot the claim. Guaranteed by the diamond trips, which drive the hall from the first junction to
     * the last and pass the middle one on the way.
     */
    private static final CraneMoment DRIVING_THROUGH = (state, held) -> {
        CranePose pose = state.pose();
        if (state.phase() != CranePhase.TRAVEL_TO_TARGET || held <= 0
                || pose.branch() != RackPosition.FIRST_BRANCH || !pose.isAligned())
            return false;
        double destination = destinationOnTheRun(state.target());
        if (Double.isNaN(destination))
            return false;
        for (int junction : TEETH) {
            double toJunction = junction - pose.x();
            if (Math.abs(toJunction) < APPROACH_NEAR || Math.abs(toJunction) > APPROACH_FAR)
                continue;
            // The junction has to be on the way there, and the work has to be past it rather than at it.
            if (Math.signum(toJunction) != Math.signum(destination - pose.x()))
                continue;
            if (Math.abs(destination - junction) >= BEYOND_THE_JUNCTION)
                return true;
        }
        return false;
    };

    /** The arm inside the rack <b>at</b> a junction, with the machine standing on the block two aisles share. */
    private static final CraneMoment SERVING_A_JUNCTION = (state, held) -> {
        CranePose pose = state.pose();
        return pose.branch() == RackPosition.FIRST_BRANCH && onAJunction(pose) && pose.isAligned()
                && pose.arm() >= ARM_EXTENDED;
    };

    /**
     * The arm inside a rack of aisle C or D, clear of the junction. Aisle B is left out on purpose: the input station
     * stands on it, and a shot of the arm in that would be a picture of a pick-up rather than of a rack.
     */
    private static final CraneMoment ARM_ON_A_TOOTH = (state, held) -> {
        CranePose pose = state.pose();
        return pose.branch() >= TOOTH_C && pose.x() > 1.5 && pose.isAligned() && pose.arm() >= ARM_EXTENDED;
    };

    /**
     * Carrying down the farthest aisle, at least a block past its junction: twelve rails along the hall and more off
     * it, which is as deep into a comb as this build goes.
     * <p>
     * {@value #PAST_THE_JUNCTION} block is deliberately modest, and the farthest aisle deliberately has <b>no rack at
     * position 1</b>. The planner serves the <i>nearest</i> suitable rack, and a rack wall of plain chests never fills,
     * so whichever position is nearest is the only one the machine ever drives to — with a rack at position 1 this
     * moment could not happen at all, which is how the first run of this scenario timed out.
     */
    private static final CraneMoment ON_THE_FAR_AISLE = (state, held) -> {
        CranePose pose = state.pose();
        return state.phase() == CranePhase.TRAVEL_TO_TARGET && held > 0 && pose.branch() == TOOTH_D
                && pose.isAligned() && pose.x() >= PAST_THE_JUNCTION;
    };

    /**
     * The arm inside the <b>shared</b> block, with the machine standing on aisle C at position 1 — not on the main run
     * it is also beside. This is the ownership rule being obeyed by the machine and not only by the address.
     */
    private static final CraneMoment SERVING_THE_SHARED_BLOCK = (state, held) -> {
        CranePose pose = state.pose();
        return pose.branch() == TOOTH_C && Math.abs(pose.x() - SHARED_ON_TOOTH.x()) < 0.2 && pose.isAligned()
                && pose.side() == SHARED_ON_TOOTH.side() && pose.arm() >= ARM_EXTENDED;
    };

    /**
     * The way back out of aisle C into the output on aisle D: a job whose two ends lie on two different side aisles, so
     * the machine turns <b>twice</b> in one trip. On the main run between those turns is the only place a crane is ever
     * driving a leg that is neither its source nor its target.
     */
    private static final CraneMoment RETRIEVING_ACROSS = (state, held) -> state.phase() == CranePhase.TRAVEL_TO_TARGET
            && held > 0 && state.pose().branch() == RackPosition.FIRST_BRANCH
            && state.target().branch() == TOOTH_D
            // The job's own source, not only its target: a diamond put away on aisle D also drives the hall with its
            // work on aisle D, and this shot is about a retrieval that started on another side aisle.
            && state.job().filter(job -> job.source().branch() == TOOTH_C).isPresent();

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: the goggle shots need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("comb: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "comb run"))
                .server("comb: clear the hall and place the creative motor", this::placeMotor)
                .server("comb: build the comb, the stations, the racks and the feed belt", this::buildScene)
                .serverUntil("comb: wait until the warehouse splits and every member joined", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("comb: aim the feed belt at the warehouse input on aisle B", this::aimBelt)
                .serverUntil("comb: wait until the belt really carries towards the input", this::beltAimed,
                        BELT_TIMEOUT_TICKS)
                .server("comb: dedicate every rack to one item type", this::setStoreFilters)
                .server("comb: check the comb against the warehouse itself", this::assertCombShape)
                .server("comb: check who owns the two blocks beside the middle junction", this::assertSharedOwnership)
                .server("comb: check the routes the shots are about", this::assertRoutes)
                .server("comb: preset the request of the output on aisle D", this::presetOutputFilter)
                .server("comb: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("comb: the player flies and wears Engineer's Goggles", this::equipPlayer)
                .server("comb: take the item census of the whole scene", this::takeCensus);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so every world shot
        // is taken by a player who reaches nothing; the goggle shots switch the real reach on for themselves.
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        for (CameraView view : List.of(TOP, HALL, TEETH_VIEW, SHARED))
            script.shotFrom(view, "idle");

        if (pass == VisualPass.FLYWHEEL)
            script.server("comb: run the creative motor at " + MOTOR_RPM + " RPM", this::powerOn);
        script.server("comb: put the stream into the feed chest", this::fillFeedChest);

        moment(script, TURN_MOMENT, TURNING, MomentView.following(SWING, CombVisualScenario::swingView),
                MomentView.following(OVER, CombVisualScenario::overView));
        moment(script, "through", DRIVING_THROUGH, MomentView.following(SWING, CombVisualScenario::swingView),
                MomentView.following(FRONT, CombVisualScenario::frontView));
        moment(script, "junction-rack", SERVING_A_JUNCTION,
                MomentView.following(ARM_OVER, CombVisualScenario::armOverView),
                MomentView.following(SWING, CombVisualScenario::swingView));
        moment(script, "tooth-arm", ARM_ON_A_TOOTH,
                MomentView.following(ARM_OVER, CombVisualScenario::armOverView),
                MomentView.following(FRONT, CombVisualScenario::frontView));
        moment(script, "far-aisle", ON_THE_FAR_AISLE, MomentView.following(FRONT, CombVisualScenario::frontView),
                MomentView.fixed(TEETH_VIEW));
        moment(script, "shared-rack", SERVING_THE_SHARED_BLOCK,
                MomentView.following(ARM_OVER, CombVisualScenario::armOverView), MomentView.fixed(SHARED));

        if (pass == VisualPass.FLYWHEEL) {
            script.server("comb: put another stream into the feed chest", this::fillFeedChest)
                    .serverUntil("comb: wait until a job sets off across a junction",
                            this::crossingJobSetOff, MOMENT_TIMEOUT_TICKS);
            controllerGoggleShot(script);
        }
        script.serverUntil("comb: wait until every item is stored and the crane is idle", this::allStored,
                        ALL_STORED_TIMEOUT_TICKS)
                .server("comb: check which rack received what", this::assertStored);

        if (pass == VisualPass.FLYWHEEL) {
            rackGoggleShots(script);
            terminalShot(script);
            retrieveThroughTheOutput(script);
            failureChapter(script);
            script.client("comb: every check passed",
                    context -> LOGGER.info(PREFIX + "comb: ALL CHECKS PASSED (the comb is one warehouse of four "
                            + "lettered aisles, the machine turns off at a junction and drives straight through one, "
                            + "serves the rack at a junction and both shared blocks from the aisle each faces, every "
                            + "item reached the rack it was addressed to, goods from aisle C reach the one block on "
                            + "aisle D, the census is exact, a closed rail takes an aisle out of the warehouse and an "
                            + "aisle the crane cannot reach says so)"));
        }
        if (pass == VisualPass.values()[VisualPass.values().length - 1])
            script.client("comb: check that the turns were heard", CombVisualScenario::assertTurnSounds);
    }

    @Override
    public String status(VisualContext context) {
        String state = clientCrane(context).map(crane -> {
            CraneState<ItemKey, RackPosition> craneState = crane.craneState();
            CranePose pose = craneState.pose();
            return String.format(Locale.ROOT,
                    "aisle=%d posX=%.2f posY=%.2f yaw=%.2f turn=%.2f arm=%.2f side=%s phase=%s held=%d target=%d/%.2f",
                    pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                    craneState.phase(), crane.goggleInfo().heldCount(), craneState.target().branch(),
                    craneState.target().x());
        }).orElse("crane=missing");
        return "moment=" + moment + " aisles=" + syncedAisleCount(context) + " " + state
                + GoggleShots.describeHover(context);
    }

    // --- building (server thread) ---------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(BlockPos.ZERO))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-2, -1, -FEED_BELT_LENGTH - 2),
                dock.offset(MAIN_RAILS + 3, CLEAR_HEIGHT, TOOTH_RAILS + 2)).inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN - 2),
                dock.offset(MAIN_RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, TOOTH_RAILS + CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motorAt(level, dock.below(), Direction.UP, 0);

        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, MAIN_AISLE));
        for (int x = 1; x <= MAIN_RAILS; x++)
            level.setBlockAndUpdate(aislePos(dock, RackPosition.FIRST_BRANCH, x),
                    WarehouseRailBlock.along(MAIN_AISLE.getAxis()));
        for (int tooth = 0; tooth < TEETH.length; tooth++) {
            for (int x = 1; x <= TOOTH_RAILS; x++)
                level.setBlockAndUpdate(aislePos(dock, tooth + 1, x),
                        WarehouseRailBlock.along(TOOTH_AISLE.getAxis()));
        }
        level.setBlockAndUpdate(controllerPos(dock), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, MAIN_AISLE));

        level.setBlockAndUpdate(rackPos(dock, INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, outward(INPUT).getOpposite()));
        level.setBlockAndUpdate(rackPos(dock, TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, outward(TERMINAL).getOpposite()));
        for (Rack rack : RACKS)
            placeStorage(level, dock, rack.position());
        buildOutputCluster(level, dock);
        buildFeedBelt(level, dock);
    }

    /** A storage location: a chest outside the rack wall and a warehouse interface facing away from its own aisle. */
    private static void placeStorage(ServerLevel level, BlockPos dock, RackPosition rack) {
        BlockPos pos = rackPos(dock, rack);
        Direction outward = outward(rack);
        level.setBlockAndUpdate(pos.relative(outward),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward));
    }

    /**
     * The output on aisle D, one level up, with a hopper below it that pulls the delivery out and pushes it into the
     * pull chest, and a lever on a stone block beside it for the redstone request.
     */
    private static void buildOutputCluster(ServerLevel level, BlockPos dock) {
        Direction outward = outward(OUTPUT);
        BlockPos output = rackPos(dock, OUTPUT);
        BlockPos hopper = output.below();
        level.setBlockAndUpdate(output, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, TOOTH_AISLE));
        level.setBlockAndUpdate(pullChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward));
        // A lever reports signal 15 to every side, so the output beside it sees a neighbour signal; the stone carries it.
        level.setBlockAndUpdate(hopper.relative(outward), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, TOOTH_AISLE)
                .setValue(LeverBlock.POWERED, false));
    }

    /**
     * The feed: a chest over a belt that carries into the warehouse input on aisle B, whose
     * {@link DirectBeltInputBehaviour} is the path Create designs for a belt-fed machine.
     */
    private static void buildFeedBelt(ServerLevel level, BlockPos dock) {
        BlockPos start = feedBeltStart(dock);
        BlockPos end = feedBeltEnd(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the feed belt was not created between " + start + " and " + end);
        // The belt runs along the main run's axis, so its pulley takes a shaft on the other horizontal axis.
        motorAt(level, start.relative(TOOTH_AISLE), TOOTH_AISLE.getOpposite(), BELT_RPM);
        level.setBlockAndUpdate(feedChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, MAIN_AISLE));
    }

    /**
     * One poll of the setup: every part of "the warehouse splits and every member joined", and a diagnostic line every
     * {@value #WAITING_LOG_INTERVAL_POLLS} polls naming the parts that are still false.
     * <p>
     * The line is not a nicety. A scene condition is a conjunction of a dozen facts about a build of a hundred blocks,
     * and without it a single mistyped offset is a bare "step timed out after 900 ticks" with nothing to go on — which
     * is exactly how this scenario's own first run failed.
     */
    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null) {
            logWhileBuilding("controller=" + controller + " crane=" + crane);
            return false;
        }
        List<String> missing = new ArrayList<>();
        if (controller.status() != ControllerStatus.READY)
            missing.add("status=" + controller.status());
        if (controller.isMembershipDirty())
            missing.add("membership still dirty");
        if (controller.pendingSnapshotCount() != 0)
            missing.add("pendingSnapshots=" + controller.pendingSnapshotCount());
        int aisles = controller.warehouse().map(WarehouseLayout::branchCount).orElse(0);
        if (aisles != aisleCount())
            missing.add("aisles=" + aisles + "/" + aisleCount() + " stop="
                    + dock(level, dock).discoveredNetwork().map(network -> network.stop() + "@" + network.stopDx()
                            + "/" + network.stopDz()).orElse("none"));
        if (controller.storageLocations().size() != RACKS.size())
            missing.add("racks=" + controller.storageLocations().size() + "/" + RACKS.size()
                    + " missing " + racksNotJoined(level, dock, controller));
        if (controller.inputStations().size() != 1)
            missing.add("inputs=" + controller.inputStations().size());
        // The terminal is an output-style member of its own ({@code LocationKind.OUTPUT}), so a comb with one output
        // and one terminal has two of them; the kinds are checked by position below rather than by counting.
        if (controller.outputStations().size() != OUTPUT_STYLE_MEMBERS)
            missing.add("output-style members=" + controller.outputStations().size() + "/" + OUTPUT_STYLE_MEMBERS);
        if (controller.locationAt(rackPos(dock, OUTPUT)).isEmpty())
            missing.add("the output on aisle D has not joined");
        if (controller.locationAt(rackPos(dock, TERMINAL)).isEmpty())
            missing.add("the terminal on aisle C has not joined");
        if (!crane.isControllerLinked())
            missing.add("the crane is not linked to the controller");
        if (crane.networkGeometry().branchCount() != aisleCount())
            missing.add("the crane sees " + crane.networkGeometry().branchCount() + " aisles");
        if (missing.isEmpty())
            return true;
        logWhileBuilding(String.join(", ", missing));
        return false;
    }

    /** Which racks of {@link #RACKS} the controller has not taken, and what the block there says about itself. */
    private static List<String> racksNotJoined(ServerLevel level, BlockPos dock,
            WarehouseControllerBlockEntity controller) {
        Set<RackPosition> joined = new HashSet<>();
        for (LocationRecord record : controller.storageLocations())
            joined.add(record.position());
        List<String> absent = new ArrayList<>();
        for (Rack rack : RACKS) {
            if (joined.contains(rack.position()))
                continue;
            BlockPos pos = rackPos(dock, rack.position());
            String assignment;
            try {
                assignment = assignmentOf(level, dock, rack.position()).toString();
            } catch (RuntimeException error) {
                assignment = "unreadable: " + error;
            }
            absent.add(rack.position() + "@" + pos + " " + assignment);
        }
        return absent;
    }

    private void logWhileBuilding(String what) {
        if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS != 0)
            return;
        LOGGER.info(PREFIX + "comb: the scene is not ready yet: {}", what);
    }

    /** Which way a belt carries follows the sign of its rotation, so it is read back and the motor reversed if wrong. */
    private void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Direction wanted = feedDirection();
        Direction actual = beltMovement(level, feedBeltStart(dock));
        LOGGER.info(PREFIX + "comb: the feed belt carries {} and must carry {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, feedBeltStart(dock).relative(TOOTH_AISLE));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private boolean beltAimed(MinecraftServer server, VisualContext context) {
        return beltMovement(server.overworld(), feedBeltStart(context.origin())) == feedDirection();
    }

    /** Sets a plain store filter on every rack, exactly as a right-click with that item would. */
    private void setStoreFilters(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (Rack rack : RACKS) {
            WarehouseInterfaceBlockEntity storage = interfaceAt(level, dock, rack.position());
            if (!storage.setStoreFilter(new ItemStack(rack.item())))
                throw new VisualTestException(
                        "the interface at " + rack.position() + " refused the filter " + rack.item());
        }
        int filtered = controller(level, dock).filteredLocationCount();
        if (filtered != RACKS.size())
            throw new VisualTestException("the controller counts " + filtered + " filtered locations, expected "
                    + RACKS.size());
        LOGGER.info(PREFIX + "comb: {} racks dedicated, {} of them on a side aisle", RACKS.size(),
                RACKS.stream().filter(rack -> rack.position().branch() != RackPosition.FIRST_BRANCH).count());
    }

    /**
     * The claim every overview shot makes, checked against the warehouse itself: the comb is <b>one</b> warehouse, the
     * main run is <b>one</b> aisle straight through all three junctions, every aisle has its own letter, every rail
     * belongs to exactly one aisle (a junction counted once), and every aisle is joined to the one at the dock.
     */
    private void assertCombShape(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        RailNetwork network = network(level, dock);
        if (network.stop() != NetworkStop.END)
            throw new VisualTestException("the comb should simply end, but the discovery stopped with "
                    + network.stop() + " at " + network.stopDx() + "/" + network.stopDz());
        if (network.branchCount() != aisleCount())
            throw new VisualTestException("the comb is " + network.branchCount() + " aisles, expected " + aisleCount());
        if (network.firstBranchLength() != MAIN_RAILS)
            throw new VisualTestException("the main run is " + network.firstBranchLength()
                    + " rails long, so it was not taken as one aisle through its junctions; expected " + MAIN_RAILS);
        if (network.geometry().links().size() != TEETH.length)
            throw new VisualTestException("one junction per side aisle, but the network has "
                    + network.geometry().links().size());
        int expectedRails = MAIN_RAILS + TEETH.length * TOOTH_RAILS;
        if (network.rails() != expectedRails)
            throw new VisualTestException("the comb holds " + network.rails() + " rails, expected " + expectedRails
                    + " (every rail on one aisle, a junction counted once)");

        WarehouseLayout warehouse = warehouse(level, dock);
        Set<Character> letters = new TreeSet<>();
        for (int aisle = 0; aisle < warehouse.branchCount(); aisle++)
            warehouse.branch(aisle).letter().ifPresent(letters::add);
        if (letters.size() != aisleCount())
            throw new VisualTestException("one letter per aisle, but the comb shows " + letters);
        for (int aisle = 1; aisle < warehouse.branchCount(); aisle++) {
            if (!warehouse.routes().reachable(RackPosition.FIRST_BRANCH, aisle))
                throw new VisualTestException("aisle " + aisle + " is not joined to the aisle at the dock");
        }
        LOGGER.info(PREFIX + "comb: CHECK the comb is one warehouse of {} rails on {} aisles {}, {} junctions, stop {}",
                network.rails(), network.branchCount(), letters, network.geometry().links().size(), network.stop());
    }

    /**
     * The claim the {@code shared} shots make: both blocks beside the middle junction really are rack positions of
     * <b>two</b> aisles, the warehouse refuses to decide between them by itself, and each of them belongs to the aisle
     * its interface faces away from — the one facing west to aisle C, the one facing south to the main run.
     */
    private void assertSharedOwnership(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        WarehouseLayout warehouse = warehouse(level, dock);
        for (RackPosition shared : List.of(SHARED_ON_TOOTH, SHARED_ON_RUN)) {
            BlockPos pos = rackPos(dock, shared);
            List<RackPosition> candidates = warehouse.candidates(pos);
            if (candidates.size() != 2)
                throw new VisualTestException("the block at " + pos + " should be a rack position of two aisles, but "
                        + "the warehouse offers " + candidates);
            if (warehouse.worldToLocal(pos).isPresent())
                throw new VisualTestException("the warehouse decided the ambiguous block " + pos + " by itself "
                        + "instead of leaving it to the member standing there");
            assertMemberAt(controller, shared, pos);
        }
        char onTooth = addressOf(warehouse, SHARED_ON_TOOTH).aisle();
        char onRun = addressOf(warehouse, SHARED_ON_RUN).aisle();
        char runLetter = warehouse.letter().orElseThrow(
                () -> new VisualTestException("the aisle at the dock has no letter"));
        if (onTooth == runLetter)
            throw new VisualTestException("the block facing away from aisle C should belong to aisle C, but its "
                    + "address names aisle " + onTooth);
        if (onRun != runLetter)
            throw new VisualTestException("the block facing away from the main run should belong to the main run ("
                    + runLetter + "), but its address names aisle " + onRun);
        LOGGER.info(PREFIX + "comb: CHECK the mirror pair beside the middle junction: {} belongs to aisle {} and {} "
                        + "to aisle {}, each decided by the way its interface faces",
                addressOf(warehouse, SHARED_ON_TOOTH).format(), onTooth,
                addressOf(warehouse, SHARED_ON_RUN).format(), onRun);
    }

    /**
     * The routes the moments are about, asked of the warehouse's own route table before a single item moves: a trip to
     * the farthest aisle drives the hall and turns <b>once</b> at the last junction, the shared block on aisle C is
     * reached by turning onto that aisle, and the way out of aisle C into the output on aisle D turns <b>twice</b> and
     * uses the main run as a middle leg. Where the rails split there is more than one way to ask, so a shot of a
     * machine driving is only honest if the plan behind it is the one being photographed.
     */
    private void assertRoutes(MinecraftServer server, VisualContext context) {
        WarehouseLayout warehouse = warehouse(server.overworld(), context.origin());
        assertRoute(warehouse, RackPosition.FIRST_BRANCH, 0.0, GOGGLE_RACK_FAR,
                List.of(RackPosition.FIRST_BRANCH, TOOTH_D), "the dock to the farthest aisle");
        assertRoute(warehouse, RackPosition.FIRST_BRANCH, 0.0, SHARED_ON_TOOTH,
                List.of(RackPosition.FIRST_BRANCH, TOOTH_C), "the dock to the shared block of aisle C");
        assertRoute(warehouse, RackPosition.FIRST_BRANCH, 0.0, SHARED_ON_RUN,
                List.of(RackPosition.FIRST_BRANCH), "the dock to the shared block of the main run");
        assertRoute(warehouse, TOOTH_C, TOOTH_RAILS, OUTPUT,
                List.of(TOOTH_C, RackPosition.FIRST_BRANCH, TOOTH_D), "aisle C to the output on aisle D");
        for (Rack rack : RACKS) {
            if (!warehouse.canDriveTo(RackPosition.FIRST_BRANCH, 0.0, rack.position()))
                throw new VisualTestException("the machine cannot drive from the dock to " + rack.position());
        }
    }

    private static void assertRoute(WarehouseLayout warehouse, int fromBranch, double fromX, RackPosition to,
            List<Integer> legs, String what) {
        CraneRoute route = warehouse.route(fromBranch, fromX, to.branch(), to.x())
                .orElseThrow(() -> new VisualTestException("no route for " + what));
        List<Integer> driven = new ArrayList<>(route.legCount());
        route.legs().forEach(leg -> driven.add(leg.branch()));
        if (!driven.equals(legs))
            throw new VisualTestException(what + " should be driven over the aisles " + legs + ", but the warehouse "
                    + "plans " + driven);
        LOGGER.info(PREFIX + "comb: CHECK the route for {}: aisles {}, {} turns, {} blocks", what, driven,
                route.turns(), String.format(Locale.ROOT, "%.1f", route.blocks()));
    }

    private static void assertMemberAt(WarehouseControllerBlockEntity controller, RackPosition expected, BlockPos pos) {
        List<RackPosition> members = controller.storageLocations().stream().map(LocationRecord::position)
                .filter(expected::equals).toList();
        if (members.size() != 1)
            throw new VisualTestException("the block at " + pos + " is not the single storage location " + expected
                    + ": " + members);
    }

    private static StorageAddress addressOf(WarehouseLayout warehouse, RackPosition rack) {
        return warehouse.address(rack)
                .orElseThrow(() -> new VisualTestException(rack + " has no address"));
    }

    /** Presets the output's request, so flipping the lever fetches lapis from aisle C without any setup by the player. */
    private void presetOutputFilter(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(server.overworld(), rackPos(context.origin(), OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output on aisle D is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(TOOTH_C_ITEM)))
            throw new VisualTestException("the warehouse output refused the preset filter");
        filter.count = OUTPUT_REQUEST_AMOUNT; // after setFilter, which may clamp the count
    }

    private void takeCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box " + censusBox + " is not fully loaded");
        conserved = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "comb: baseline census {} in {}", SceneItemCensus.describe(conserved), censusBox);
    }

    private void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin().below()).generatedSpeed.setValue(MOTOR_RPM);
    }

    /** Puts one full stream into the feed chest; the belt carries it into the warehouse input batch by batch. */
    private void fillFeedChest(MinecraftServer server, VisualContext context) {
        fillFeedChest(server.overworld(), context.origin());
    }

    private void fillFeedChest(ServerLevel level, BlockPos dock) {
        BlockPos chest = feedChestPos(dock);
        for (ItemStack stack : STREAM) {
            insertAll(level, chest, stack.copy());
            fed.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
        }
        conserved = SceneItemCensus.plusAll(conserved, STREAM);
        LOGGER.info(PREFIX + "comb: the feed chest holds another stream ({} items in {} batches)",
                STREAM.stream().mapToInt(ItemStack::getCount).sum(), STREAM.size());
    }

    /**
     * Nothing is moving and a moment has still to come: put another stream in. A warehouse that has run out of work
     * cannot drive its comb, and which item types are still in flight when a moment is reached depends on how long the
     * shots before it took.
     */
    private void keepTheWarehouseBusy(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        // Never while the retrieval is being watched: what comes back through the output is counted against what was
        // fed, and a stream that arrives in the middle of it would be counted before it is stored.
        if (RETRIEVE_MOMENT.equals(moment))
            return;
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                rackPos(dock, INPUT));
        if (input == null || !craneIdle(crane) || !input.bufferedItems().isEmpty()
                || !isChestEmpty(level, feedChestPos(dock)) || beltItems(level, feedBeltStart(dock)) > 0)
            return;
        LOGGER.info(PREFIX + "comb: the warehouse ran out of work while waiting for '{}'", moment);
        fillFeedChest(level, dock);
    }

    // --- the moments -------------------------------------------------------------------------------------------------

    /**
     * One frozen moment: the <b>server</b> waits until the machine really is in this state and freezes the ticks in
     * the same server tick (submitted tasks run between two ticks, so nothing moves in between), then the client is
     * given time to see the freeze, both poses are checked against what the shots will claim, the census is taken
     * again, and every view is photographed.
     */
    private void moment(VisualScript script, String label, CraneMoment test, MomentView... views) {
        script.server("comb: the run is looking for the moment '" + label + "'", (server, context) -> moment = label)
                .serverUntil("comb: run the belt and wait for the moment '" + label + "'",
                        (server, context) -> freezeAtMoment(server, context, test), MOMENT_TIMEOUT_TICKS)
                .until("comb: wait until the client sees the frozen moment '" + label + "'",
                        CombVisualScenario::clientFrozen, SYNC_TIMEOUT_TICKS)
                .server("comb: check the machine's own state at '" + label + "'",
                        (server, context) -> assertServerMoment(server, context, label, test))
                .client("comb: check the pose the client draws at '" + label + "'",
                        context -> assertClientMoment(context, label));
        fly(script);
        for (MomentView view : views)
            script.camera(view.label(), view.view()).shot(label + "-" + view.label());
        script.freeze(false);
    }

    /**
     * One poll of the wait before the controller's goggle shot: keep the belt fed, as a moment's own poll does, and
     * hold until a job has just <b>set off</b> across a junction — items in the head, still on the main run, target on
     * another aisle. Create's tooltip needs about two seconds to fade in before the shot may assert it, and the start
     * of such a job leaves the whole drive onto the tooth ahead of it.
     * <p>
     * Without this the shot took whatever job happened to be in flight when the last moment ended, and nothing fed
     * the belt in between: {@link #feedBelt} and {@link #keepTheWarehouseBusy} run only inside a moment's poll. The
     * same race made the sibling {@code corner} scenario fail once in the M22 Definition-of-Done run.
     */
    private boolean crossingJobSetOff(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        feedBelt(level, dock);
        StackerCraneBlockEntity crane = dock(level, dock);
        keepTheWarehouseBusy(level, dock, crane);
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        return state.phase() == CranePhase.TRAVEL_TO_TARGET && crane.goggleInfo().heldCount() > 0
                && state.pose().branch() == RackPosition.FIRST_BRANCH
                && state.target().branch() != RackPosition.FIRST_BRANCH;
    }

    /**
     * One poll of a moment: keep the belt fed, record every aisle the machine stands on, and when it is in the state
     * the shot is about, stop the world right there. Freezing from the server condition itself is what makes the shots
     * honest — a client that asked for a freeze would be two round trips late, and at eight ticks a quarter turn would
     * be over.
     */
    private boolean freezeAtMoment(MinecraftServer server, VisualContext context, CraneMoment test) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        feedBelt(level, dock);
        StackerCraneBlockEntity crane = dock(level, dock);
        visitedAisles.add(crane.craneState().pose().branch());
        if (!test.holds(crane.craneState(), crane.goggleInfo().heldCount())) {
            keepTheWarehouseBusy(level, dock, crane);
            logWhileWaiting(level, dock, crane);
            return false;
        }
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /** One line every {@value #WAITING_LOG_INTERVAL_POLLS} polls while a moment has not come yet. */
    private void logWhileWaiting(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS != 0)
            return;
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                rackPos(dock, INPUT));
        LOGGER.info(PREFIX + "comb: still waiting for '{}': {} | chest={} belt={} input={} reason={}", moment,
                describe(crane.craneState(), crane.goggleInfo().heldCount()), itemsIn(level, feedChestPos(dock)),
                beltItems(level, feedBeltStart(dock)),
                input == null ? "gone" : input.bufferedItems().totalItems(),
                controller(level, dock).lastPlanReason());
    }

    /** Everything in an inventory, by item type, for a diagnostic line. */
    private static String itemsIn(ServerLevel level, BlockPos pos) {
        IItemHandler handler = handlerAt(level, pos);
        Map<String, Long> items = new TreeMap<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty())
                items.merge(stack.getItem().toString(), (long) stack.getCount(), Long::sum);
        }
        return items.toString();
    }

    private void assertServerMoment(MinecraftServer server, VisualContext context, String label, CraneMoment test) {
        ServerLevel level = server.overworld();
        StackerCraneBlockEntity crane = dock(level, context.origin());
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        long held = crane.goggleInfo().heldCount();
        if (!test.holds(state, held))
            throw new VisualTestException("the machine left the moment '" + label + "' before the world was frozen: "
                    + describe(state, held));
        poseAtMoment = state.pose();
        SceneItemCensus.assertEquals(level, censusBox, conserved, "at the moment '" + label + "'");
        LOGGER.info(PREFIX + "comb: CHECK moment '{}' on the server: {}", label, describe(state, held));
    }

    /**
     * The pose the shot really draws. The client simulates the machine itself and learns of the freeze about a tick
     * later, so it does not stop on the same yaw as the server — but it must stop on the same <b>aisle</b>, within a
     * tick's worth of yaw of it, and, while the machine turns, still between the two headings.
     */
    private void assertClientMoment(VisualContext context, String label) {
        StackerCraneBlockEntity crane = clientCrane(context)
                .orElseThrow(() -> new VisualTestException("the client has no crane to photograph"));
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        CranePose onTheServer = poseAtMoment;
        long held = crane.goggleInfo().heldCount();
        if (pose.branch() != onTheServer.branch())
            throw new VisualTestException("the client draws the machine on aisle " + pose.branch()
                    + " while it stands on aisle " + onTheServer.branch() + " at the moment '" + label + "'");
        double yawDrift = Math.abs(CranePose.yawDelta(onTheServer.yaw(), pose.yaw()));
        if (yawDrift > CLIENT_YAW_TOLERANCE)
            throw new VisualTestException("the client draws the machine " + yawDrift + " quarter turns away from the "
                    + "yaw it really has at the moment '" + label + "' (" + pose.yaw() + " against "
                    + onTheServer.yaw() + ")");
        if (TURN_MOMENT.equals(label) && pose.isAligned())
            throw new VisualTestException("the client draws a machine that has finished its turn at the moment '"
                    + label + "', so the mid-turn shot would show nothing: " + describe(state, held));
        LOGGER.info(PREFIX + "comb: CHECK moment '{}' on the client: {} (yaw drift {})", label,
                describe(state, held), String.format(Locale.ROOT, "%.3f", yawDrift));
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    private static String describe(CraneState<ItemKey, RackPosition> state, long held) {
        CranePose pose = state.pose();
        return String.format(Locale.ROOT,
                "aisle=%d x=%.3f y=%.2f yaw=%.3f turn=%.2f arm=%.2f side=%s phase=%s held=%d target=%d/%.2f/%.0f",
                pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                state.phase(), held, state.target().branch(), state.target().x(), state.target().y());
    }

    // --- the goggle shots --------------------------------------------------------------------------------------------

    /**
     * The controller's tooltip: the <b>size</b> line of a warehouse that splits ("Warehouse: 24 rails, 4 aisles, mast
     * 4 high"), the <b>aisle list</b> under it that names every letter with its length, and a job whose two ends lie
     * on two different aisles. A warehouse that bends must not show the single-aisle line any more, so that is checked
     * too and not only shot.
     */
    private void controllerGoggleShot(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, controllerView(), "goggles-controller", CombVisualScenario::controllerPos,
                CombVisualScenario::crossingJobSynced, CombVisualScenario::checkControllerGoggles);
        // Bracketed: the same check runs again after the frame was grabbed, so a line that only arrived afterwards
        // could not be the one that was photographed. A job that simply finished in those few ticks is work, not a
        // failure, so that case is logged instead.
        script.client("comb: the controller still shows the job across the junctions",
                CombVisualScenario::checkControllerGogglesAfterTheShot);
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** A rack of the main run, a rack of the farthest aisle, and both halves of the mirror pair. */
    private void rackGoggleShots(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        for (RackGoggle rack : List.of(new RackGoggle(GOGGLE_RACK_MAIN, "goggles-rack-main"),
                new RackGoggle(GOGGLE_RACK_FAR, "goggles-rack-far"),
                new RackGoggle(SHARED_ON_TOOTH, "goggles-shared-tooth"),
                new RackGoggle(SHARED_ON_RUN, "goggles-shared-run"))) {
            fly(script);
            GoggleShots.shot(script, NAME, overheadView(rack.label(), rack.position()), rack.label(),
                    dock -> rackPos(dock, rack.position()),
                    context -> rackStateSynced(context, rack.position(), AisleAssignment.State.ASSIGNED),
                    context -> checkRackGoggles(context, rack.position()));
        }
        GoggleShots.reach(script, NAME, 0.0);
    }

    private record RackGoggle(RackPosition position, String label) {
    }

    /**
     * The controller's synced summary carries a crane job whose two ends lie on different aisles, and the dock's own
     * synced data says the same — so the line this shot is about is not one throttled packet behind the machine.
     */
    private static boolean crossingJobSynced(VisualContext context) {
        if (crossingCrane(context).isEmpty())
            return false;
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && crane.goggleInfo().job().filter(job -> job.source().branch() != job.target().branch()).isPresent())
                .isPresent();
    }

    /** The crane data behind the controller's tooltip, while its job crosses a junction. */
    private static Optional<CraneGoggleInfo> crossingCrane(VisualContext context) {
        return controllerSummary(context).flatMap(ControllerGoggleSummary::crane)
                .filter(crane -> crane.job().filter(job -> job.source().branch() != job.target().branch()).isPresent());
    }

    /** The controller's own synced summary, i.e. the data the lines this shot is about are drawn from. */
    private static Optional<ControllerGoggleSummary> controllerSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(controllerPos(context.origin())) instanceof WarehouseControllerBlockEntity controller
                ? Optional.of(controller.summary()) : Optional.empty();
    }

    private static void checkControllerGoggles(VisualContext context) {
        CraneGoggleInfo crane = crossingCrane(context)
                .orElseThrow(() -> new VisualTestException("the controller shows no job across a junction any more"));
        CraneJobSummary job = crane.job().orElseThrow();
        String source = crane.address(job.source());
        String target = crane.address(job.target());
        if (source.charAt(0) == target.charAt(0))
            throw new VisualTestException("the goggle line names both ends of a job across a junction on the same "
                    + "aisle: from " + source + " to " + target);
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, source);
        GoggleShots.requireLine(lines, target);
        ControllerGoggleSummary summary = controllerSummary(context)
                .orElseThrow(() -> new VisualTestException("the controller has no synced summary at all"));
        NetworkGoggleInfo network = summary.network()
                .orElseThrow(() -> new VisualTestException("the controller's summary carries no network"));
        if (network.aisleCount() != aisleCount())
            throw new VisualTestException("this shot is about a comb of " + aisleCount() + " aisles, and the synced "
                    + "network has " + network.aisleCount());
        GoggleShots.requireLine(lines, WareworksLang
                .networkSize(network.rails(), network.aisleCount(), summary.mastHeight()).component().getString());
        GoggleShots.requireLine(lines, aisleListLine(network));
        GoggleShots.requireNoLine(lines, WareworksLang
                .aisleSize(summary.aisleLength(), summary.mastHeight()).component().getString());
        LOGGER.info(PREFIX + "comb: CHECK the controller's goggles name the job from {} to {}, under '{}' and '{}'",
                source, target, WareworksLang.networkSize(network.rails(), network.aisleCount(),
                        summary.mastHeight()).component().getString(), aisleListLine(network));
    }

    /** The same check after the frame was grabbed; a job that finished in the meantime is work, not a failure. */
    private static void checkControllerGogglesAfterTheShot(VisualContext context) {
        if (crossingCrane(context).isEmpty()) {
            LOGGER.info(PREFIX + "comb: the job across the junction finished right after the shot; the tooltip was "
                    + "checked before the frame was taken");
            return;
        }
        checkControllerGoggles(context);
    }

    /** The member's own assignment has reached the client in the state the shot is about. */
    private static boolean rackStateSynced(VisualContext context, RackPosition rack, AisleAssignment.State state) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        return level.getBlockEntity(rackPos(context.origin(), rack)) instanceof WarehouseInterfaceBlockEntity storage
                && storage.aisleAssignment().state() == state;
    }

    /** The address the goggles of a rack show is the one the warehouse really gave it, read from the client. */
    private static void checkRackGoggles(VisualContext context, RackPosition rack) {
        BlockPos pos = rackPos(context.origin(), rack);
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity storage))
            throw new VisualTestException("no warehouse interface on the client at " + pos);
        StorageAddress address = storage.aisleAssignment().address()
                .orElseThrow(() -> new VisualTestException("the rack at " + pos + " shows no address"));
        if (!address.rackPosition().equals(new RackPosition(rack.x(), rack.y(), rack.side())))
            throw new VisualTestException("the rack at " + pos + " calls itself " + address.format()
                    + ", which is position " + address.position() + " level " + address.level() + " side "
                    + address.side() + " and not " + rack);
        GoggleShots.requireLine(GoggleShots.lines(context, pos), address.format());
        LOGGER.info(PREFIX + "comb: CHECK the goggles at {} show the address {}", pos, address.format());
    }

    /** The aisle list line the controller draws for {@code network}, built exactly as the controller builds it. */
    private static String aisleListLine(NetworkGoggleInfo network) {
        List<String> entries = new ArrayList<>(network.aisleCount());
        for (int aisle = 0; aisle < network.aisleCount(); aisle++)
            entries.add(network.letterOf(aisle).map(String::valueOf).orElse("?") + " "
                    + network.aisleLengths().get(aisle));
        return WareworksLang.networkAisles(entries, entries.size()).component().getString();
    }

    // --- storing, the terminal and the retrieval across the comb -------------------------------------------------------

    /** One poll of the storing phase: keep the belt fed and report when nothing is left in flight. */
    private boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        feedBelt(level, dock);
        StackerCraneBlockEntity crane = dock(level, dock);
        visitedAisles.add(crane.craneState().pose().branch());
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                rackPos(dock, INPUT));
        if (input == null)
            throw new VisualTestException("the warehouse input is missing");
        return isChestEmpty(level, feedChestPos(dock)) && beltItems(level, feedBeltStart(dock)) == 0
                && input.bufferedItems().isEmpty() && craneIdle(crane);
    }

    /**
     * Which rack received what, read from the chests themselves: every item type lies in the racks its filter
     * dedicated to it, in the amount that was fed minus what left through the output, and in no other rack. Then the
     * census of the whole scene, and the aisles the machine really stood on.
     */
    private void assertStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        List<String> report = new ArrayList<>();
        for (Item item : ITEM_TYPES) {
            long expected = fed.getOrDefault(item, 0L) - handedOut.getOrDefault(item, 0L);
            long inItsOwnRacks = 0;
            for (Rack rack : RACKS) {
                long found = countAt(level, chestPos(dock, rack.position()), item);
                if (rack.item() == item)
                    inItsOwnRacks += found;
                else if (found != 0)
                    throw new VisualTestException(found + " " + item + " ended up at " + rack.position()
                            + ", which is dedicated to " + rack.item());
            }
            if (inItsOwnRacks != expected)
                throw new VisualTestException("the racks dedicated to " + item + " hold " + inItsOwnRacks
                        + " of them, expected " + expected);
            report.add(item + "=" + inItsOwnRacks);
        }
        for (int aisle = 0; aisle < aisleCount(); aisle++) {
            if (!visitedAisles.contains(aisle))
                throw new VisualTestException("the machine never stood on aisle " + aisle + ", so the comb was not "
                        + "really driven: it stood on " + new TreeSet<>(visitedAisles));
        }
        SceneItemCensus.assertEquals(level, censusBox, conserved, "after everything was stored");
        LOGGER.info(PREFIX + "comb: CHECK every item reached the rack it was addressed to ({}), and the machine stood "
                + "on every aisle {}", report, new TreeSet<>(visitedAisles));
    }

    /**
     * The terminal on aisle C: <b>one</b> stock list for the whole comb. The item types are counted on the server
     * first and then on the screen, because a screenshot cannot tell a complete list from a short one.
     */
    private void terminalShot(VisualScript script) {
        fly(script);
        script.camera(AT_TERMINAL)
                .server("comb: check the stock index holds every item type of the comb", this::assertStockIndex)
                .server("comb: open the terminal screen on aisle C", CombVisualScenario::openTerminal)
                .until("comb: wait for the screen with the stock of the whole comb",
                        CombVisualScenario::terminalReady, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("comb: check that every aisle's item type is on the screen",
                        CombVisualScenario::checkTerminalStock)
                .shot("terminal")
                .client("comb: close the terminal screen", CombVisualScenario::closeTerminal)
                .until("comb: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    private void assertStockIndex(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        Set<Integer> aisles = new TreeSet<>();
        for (LocationRecord record : controller.storageLocations())
            aisles.add(record.position().branch());
        for (Item item : ITEM_TYPES) {
            long counted = controller.countOf(ItemKey.of(item));
            if (counted <= 0)
                throw new VisualTestException("the stock index counts no " + item + ", so the terminal would not show "
                        + "it");
        }
        if (aisles.size() != aisleCount())
            throw new VisualTestException("the one stock index should cover every aisle of the comb, but its "
                    + "locations lie on " + aisles);
        LOGGER.info(PREFIX + "comb: CHECK one stock index over {} aisles holds all {} item types of the comb", aisles,
                ITEM_TYPES.size());
    }

    private static void openTerminal(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                .getNullable(server.overworld(), rackPos(context.origin(), TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal on aisle C is missing");
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    private static void closeTerminal(VisualContext context) {
        var player = context.minecraft().player;
        if (player == null)
            throw new VisualTestException("there is no local player to close the terminal screen");
        player.closeContainer();
    }

    private static boolean terminalReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && !terminal.matchingEntries().isEmpty();
    }

    private static void checkTerminalStock(VisualContext context) {
        if (!(context.minecraft().screen instanceof WarehouseTerminalScreen terminal))
            throw new VisualTestException("the terminal screen closed before the shot");
        Set<ItemKey> shown = new HashSet<>();
        for (StockLine<ItemKey> line : terminal.matchingEntries())
            shown.add(line.key());
        for (Item item : ITEM_TYPES) {
            if (!shown.contains(ItemKey.of(item)))
                throw new VisualTestException("the terminal's list does not offer " + item + ", which lies in the "
                        + "comb: it shows " + shown);
        }
        LOGGER.info(PREFIX + "comb: CHECK the one terminal lists all {} item types of the comb ({} rows)",
                ITEM_TYPES.size(), terminal.matchingEntries().size());
    }

    /** The sentence of issue #2: lapis out of aisle C into the one block on aisle D — two turns in one trip. */
    private void retrieveThroughTheOutput(VisualScript script) {
        script.server("comb: a player flips the lever beside the output on aisle D",
                (server, context) -> setLever(server, context, true));
        moment(script, RETRIEVE_MOMENT, RETRIEVING_ACROSS,
                MomentView.following(FRONT, CombVisualScenario::frontView), MomentView.fixed(HALL));
        script.serverUntil("comb: wait until the lapis arrived in the pull chest on aisle D", this::outputDelivered,
                        DELIVERY_TIMEOUT_TICKS)
                .server("comb: flip the lever back", (server, context) -> setLever(server, context, false))
                .server("comb: check the delivery that crossed the comb", this::assertDelivered);
        fly(script);
        script.shotFrom(OUTPUT_VIEW, "delivered").shotFrom(TOP, "delivered");
    }

    /** {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the output see the rising edge. */
    private void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(context.origin());
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    private boolean outputDelivered(MinecraftServer server, VisualContext context) {
        return countAt(server.overworld(), pullChestPos(context.origin()), TOOTH_C_ITEM) >= OUTPUT_REQUEST_AMOUNT;
    }

    private void assertDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long delivered = countAt(level, pullChestPos(dock), TOOTH_C_ITEM);
        if (delivered != OUTPUT_REQUEST_AMOUNT)
            throw new VisualTestException("the pull chest holds " + delivered + " " + TOOTH_C_ITEM + ", expected "
                    + OUTPUT_REQUEST_AMOUNT);
        handedOut.merge(TOOTH_C_ITEM, (long) OUTPUT_REQUEST_AMOUNT, Long::sum);
        long left = 0;
        for (Rack rack : RACKS) {
            if (rack.item() == TOOTH_C_ITEM)
                left += countAt(level, chestPos(dock, rack.position()), TOOTH_C_ITEM);
        }
        long expected = fed.getOrDefault(TOOTH_C_ITEM, 0L) - handedOut.getOrDefault(TOOTH_C_ITEM, 0L);
        if (left != expected)
            throw new VisualTestException("the racks on aisle C hold " + left + " " + TOOTH_C_ITEM + " after the "
                    + "retrieval, expected " + expected);
        SceneItemCensus.assertEquals(level, censusBox, conserved, "after the retrieval across the comb");
        LOGGER.info(PREFIX + "comb: CHECK {} {} came out of aisle C into the one block on aisle D", delivered,
                TOOTH_C_ITEM);
    }

    // --- the failure chapter ------------------------------------------------------------------------------------------

    /**
     * What a player is told when an aisle stops working, in the two shapes that can happen — and they are not the same
     * thing (see the class javadoc). Both are undone again, and the comb is shown whole at the end.
     */
    private void failureChapter(VisualScript script) {
        script.serverUntil("comb: wait until the machine is idle before anything is broken", this::craneParked,
                IDLE_TIMEOUT_TICKS);

        // 1. A rail closed with a wrench: the aisles beyond it leave the warehouse altogether.
        fly(script);
        script.camera(CLOSED_RAIL_VIEW)
                .server("comb: a player closes the rail before the last junction with a wrench",
                        (server, context) -> wrenchRail(server, context, true))
                // With the GUI: the action bar still carries the line the wrench itself writes.
                .shotWithGui("closed-rail")
                // The wrench writes into the action bar, which Create's goggle overlay does not clear and which would
                // otherwise sit across the tooltip of the very next shot.
                .waitTicks(ACTION_BAR_TICKS)
                .serverUntil("comb: wait until the warehouse is the three aisles the closed rail leaves",
                        (server, context) -> aislesAre(server, context, TEETH.length), RESCAN_TIMEOUT_TICKS)
                .server("comb: check what the closed rail did to the warehouse", this::assertRailClosed);
        goggleShotOfTheClosedRail(script);

        // 2. The same aisle, cut loose by a configured maximum instead: it is kept, and it says so.
        script.server("comb: open the rail again", (server, context) -> wrenchRail(server, context, false))
                .serverUntil("comb: wait until the comb is whole again",
                        (server, context) -> aislesAre(server, context, aisleCount()), RESCAN_TIMEOUT_TICKS)
                .server("comb: lower aisle.maxAisleLength under the running warehouse", this::cutAisleLength)
                .serverUntil("comb: wait until the last aisle is no longer joined to the crane", this::aisleCutLoose,
                        RESCAN_TIMEOUT_TICKS)
                .server("comb: check that the aisle was kept rather than deleted", this::assertCutLoose);
        goggleShotsOfTheUnreachableAisle(script);

        script.server("comb: put aisle.maxAisleLength back", this::restoreAisleLength)
                .serverUntil("comb: wait until the last aisle is joined again", this::aisleJoinedAgain,
                        RESCAN_TIMEOUT_TICKS)
                .server("comb: check the comb is the one it started as", this::assertCombShape);
        fly(script);
        script.shotFrom(TOP, "restored").shotFrom(HALL, "restored");
    }

    /** The controller and a rack of the lost aisle, while a closed rail keeps that aisle out of the warehouse. */
    private void goggleShotOfTheClosedRail(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, controllerView(), "goggles-closed-controller",
                CombVisualScenario::controllerPos,
                context -> syncedAisleCount(context) == TEETH.length,
                context -> checkShortenedControllerGoggles(context, TEETH.length, CLOSED_RAIL - 1));
        fly(script);
        GoggleShots.shot(script, NAME, overheadView("goggles-closed-rack", GOGGLE_RACK_FAR), "goggles-closed-rack",
                dock -> rackPos(dock, GOGGLE_RACK_FAR),
                context -> rackStateSynced(context, GOGGLE_RACK_FAR, AisleAssignment.State.NONE),
                CombVisualScenario::checkLostRackGoggles);
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** The controller naming the maximum, and the rack of the kept aisle saying the crane cannot reach it. */
    private void goggleShotsOfTheUnreachableAisle(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, controllerView(), "goggles-unreachable-controller",
                CombVisualScenario::controllerPos,
                context -> syncedStop(context) == NetworkStop.MAX_LENGTH,
                context -> checkMaximumNamed(context, NetworkStop.MAX_LENGTH));
        fly(script);
        GoggleShots.shot(script, NAME, overheadView("goggles-unreachable-rack", GOGGLE_RACK_FAR),
                "goggles-unreachable-rack", dock -> rackPos(dock, GOGGLE_RACK_FAR),
                context -> rackStateSynced(context, GOGGLE_RACK_FAR, AisleAssignment.State.UNREACHABLE),
                CombVisualScenario::checkUnreachableRackGoggles);
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** Idle, empty-handed and back on the aisle at the dock: nothing in flight when the rails change. */
    private boolean craneParked(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = dock(server.overworld(), context.origin());
        return craneIdle(crane) && crane.craneState().pose().branch() == RackPosition.FIRST_BRANCH
                && crane.craneState().pose().x() <= CUT_LENGTH;
    }

    /** The wrench itself, through the block's own {@code onWrenched}: the one way a player closes a rail. */
    private void wrenchRail(MinecraftServer server, VisualContext context, boolean expectClosed) {
        ServerLevel level = server.overworld();
        BlockPos pos = aislePos(context.origin(), RackPosition.FIRST_BRANCH, CLOSED_RAIL);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WarehouseRailBlock rail))
            throw new VisualTestException("no warehouse rail at " + pos + ", found " + state);
        if (state.getValue(WarehouseRailBlock.CLOSED) == expectClosed)
            throw new VisualTestException("the rail at " + pos + " is already "
                    + (expectClosed ? "closed" : "open"));
        ServerPlayer player = context.serverPlayer(server);
        ItemStack wrench = AllItems.WRENCH.asStack();
        rail.onWrenched(state, new UseOnContext(level, player,
                InteractionHand.MAIN_HAND, wrench,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        if (level.getBlockState(pos).getValue(WarehouseRailBlock.CLOSED) != expectClosed)
            throw new VisualTestException("the wrench did not " + (expectClosed ? "close" : "open") + " the rail at "
                    + pos);
        LOGGER.info(PREFIX + "comb: the rail at {} is now {}", pos, expectClosed ? "closed" : "open");
    }

    private boolean aislesAre(MinecraftServer server, VisualContext context, int aisles) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.warehouse().map(WarehouseLayout::branchCount).orElse(0) == aisles
                && !controller.isMembershipDirty();
    }

    /**
     * What the closed rail really did: the rails beyond it were never reached, so the main run is shorter, the last
     * aisle is <b>gone from the warehouse</b> rather than left in it, and its racks are not storage locations any more.
     */
    private void assertRailClosed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        RailNetwork network = network(level, dock);
        if (network.stop() != NetworkStop.CLOSED)
            throw new VisualTestException("the discovery should stop at the closed rail, but it says " + network.stop());
        if (network.stopDx() != CLOSED_RAIL || network.stopDz() != 0)
            throw new VisualTestException("the warehouse names the block it stopped at as " + network.stopDx() + "/"
                    + network.stopDz() + ", expected " + CLOSED_RAIL + "/0");
        if (network.firstBranchLength() != CLOSED_RAIL - 1)
            throw new VisualTestException("the main run should end before the closed rail at " + (CLOSED_RAIL - 1)
                    + " rails, but it is " + network.firstBranchLength());
        WarehouseLayout warehouse = warehouse(level, dock);
        if (warehouse.branchCount() != TEETH.length)
            throw new VisualTestException("the closed rail should leave " + TEETH.length + " aisles, but the "
                    + "warehouse has " + warehouse.branchCount());
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long onTheLostAisle = controller.storageLocations().stream()
                .filter(record -> record.position().branch() == TOOTH_D).count();
        if (onTheLostAisle != 0)
            throw new VisualTestException(onTheLostAisle + " racks of the lost aisle are still storage locations");
        AisleAssignment assignment = assignmentOf(level, dock, GOGGLE_RACK_FAR);
        if (assignment.state() != AisleAssignment.State.NONE)
            throw new VisualTestException("a rack behind a closed rail is not part of an aisle any more, but it says "
                    + assignment);
        LOGGER.info(PREFIX + "comb: CHECK the closed rail at {} leaves a warehouse of {} rails on {} aisles, and the "
                        + "racks behind it are not part of an aisle", network.stopDx(), network.rails(),
                warehouse.branchCount());
    }

    /** Lowers {@code aisle.maxAisleLength} in the loaded server config, exactly as the GameTests' overrides do. */
    private void cutAisleLength(MinecraftServer server, VisualContext context) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so it cannot be lowered");
        ModConfigSpec.IntValue value = WareworksConfig.SERVER.maxAisleLength;
        aisleLengthBefore = value.get();
        setConfig(value, CUT_LENGTH);
        dock(server.overworld(), context.origin()).refreshGeometry();
        LOGGER.info(PREFIX + "comb: aisle.maxAisleLength lowered from {} to {}", aisleLengthBefore, CUT_LENGTH);
    }

    private void restoreAisleLength(MinecraftServer server, VisualContext context) {
        if (aisleLengthBefore < 0)
            throw new VisualTestException("aisle.maxAisleLength was never lowered, so it cannot be put back");
        setConfig(WareworksConfig.SERVER.maxAisleLength, aisleLengthBefore);
        aisleLengthBefore = -1;
        dock(server.overworld(), context.origin()).refreshGeometry();
    }

    /**
     * {@code set} writes into the loaded config in memory only; {@code clearCache} makes the next {@code get} re-read
     * it, which the cached values need.
     */
    private static void setConfig(ModConfigSpec.IntValue value, int newValue) {
        value.set(newValue);
        value.clearCache();
    }

    private boolean aisleCutLoose(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.warehouse()
                .filter(warehouse -> warehouse.branchCount() > TOOTH_D
                        && !warehouse.routes().reachable(RackPosition.FIRST_BRANCH, TOOTH_D))
                .isPresent() && !controller.isMembershipDirty();
    }

    private boolean aisleJoinedAgain(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.warehouse()
                .filter(warehouse -> warehouse.branchCount() == aisleCount()
                        && warehouse.routes().reachable(RackPosition.FIRST_BRANCH, TOOTH_D))
                .isPresent() && !controller.isMembershipDirty()
                && controller.storageLocations().size() == RACKS.size();
    }

    /**
     * The state the {@code unreachable} shots are about: the aisle is still in the warehouse, its rack still has its
     * address and its stock, and the rails no longer join it to the crane. An aisle deleted instead would take a
     * player's chests out of the address space because a number in a config file is too small.
     */
    private void assertCutLoose(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        RailNetwork network = network(level, dock);
        if (network.stop() != NetworkStop.MAX_LENGTH)
            throw new VisualTestException("the warehouse should name the maximum that stopped it, but it says "
                    + network.stop());
        WarehouseLayout warehouse = warehouse(level, dock);
        if (warehouse.branchCount() != aisleCount())
            throw new VisualTestException("the cut aisle must be kept, but the warehouse has only "
                    + warehouse.branchCount() + " aisles");
        if (warehouse.routes().reachable(RackPosition.FIRST_BRANCH, TOOTH_D))
            throw new VisualTestException("the rails still join the last aisle to the crane");
        AisleAssignment assignment = assignmentOf(level, dock, GOGGLE_RACK_FAR);
        if (assignment.state() != AisleAssignment.State.UNREACHABLE)
            throw new VisualTestException("the rack of the cut aisle should say the crane cannot reach it, but it "
                    + "says " + assignment);
        StorageAddress address = assignment.address()
                .orElseThrow(() -> new VisualTestException("the unreachable rack lost its address"));
        // The stock of the whole cut aisle, not of this one rack: the planner always fills the nearest suitable rack,
        // so which of the aisle's chests holds the diamonds is its business and not this check's.
        long stock = 0;
        for (Rack rack : RACKS) {
            if (rack.position().branch() == TOOTH_D)
                stock += countAt(level, chestPos(dock, rack.position()), TOOTH_D_ITEM);
        }
        if (stock <= 0)
            throw new VisualTestException("the cut aisle should keep its stock, but its chests are empty");
        LOGGER.info(PREFIX + "comb: CHECK the cut aisle is kept: {} keeps its address, the aisle still holds {} {}, "
                + "and the crane cannot reach it; the warehouse reports {}", address.format(), stock, TOOTH_D_ITEM,
                network.stop());
    }

    /** The controller's size and aisle lines while the warehouse is shorter than the rails a player laid. */
    private static void checkShortenedControllerGoggles(VisualContext context, int aisles, int mainRails) {
        NetworkGoggleInfo network = syncedNetwork(context);
        if (network.aisleCount() != aisles)
            throw new VisualTestException("this shot is about a warehouse of " + aisles + " aisles, and the synced "
                    + "network has " + network.aisleCount());
        if (network.aisleLengths().getFirst() != mainRails)
            throw new VisualTestException("the main run should be " + mainRails + " rails here, but the goggles say "
                    + network.aisleLengths().getFirst());
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, aisleListLine(network));
        LOGGER.info(PREFIX + "comb: CHECK the controller's goggles say '{}' while the rail is closed",
                aisleListLine(network));
    }

    /** The controller naming the maximum that stopped it, with the block a player has to go and look at. */
    private static void checkMaximumNamed(VisualContext context, NetworkStop stop) {
        NetworkGoggleInfo network = syncedNetwork(context);
        if (network.stop() != stop)
            throw new VisualTestException("the controller should report " + stop + ", but it reports "
                    + network.stop());
        BlockPos stopped = controllerStopPos(context, network);
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, WareworksLang.networkStop(stopped, stop).component().getString());
        LOGGER.info(PREFIX + "comb: CHECK the controller's goggles say '{}'",
                WareworksLang.networkStop(stopped, stop).component().getString());
    }

    /** A rack whose aisle left the warehouse: no address, and the plainest line there is. */
    private static void checkLostRackGoggles(VisualContext context) {
        BlockPos pos = rackPos(context.origin(), GOGGLE_RACK_FAR);
        List<String> lines = GoggleShots.lines(context, pos);
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_NO_AISLE).getString());
        LOGGER.info(PREFIX + "comb: CHECK the rack behind the closed rail says '{}'",
                WareworksLang.translateDirect(WareworksLang.GOGGLES_NO_AISLE).getString());
    }

    /** A rack of a kept but unreachable aisle: its own address <b>and</b> why nothing happens at it. */
    private static void checkUnreachableRackGoggles(VisualContext context) {
        BlockPos pos = rackPos(context.origin(), GOGGLE_RACK_FAR);
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity storage))
            throw new VisualTestException("no warehouse interface on the client at " + pos);
        StorageAddress address = storage.aisleAssignment().address()
                .orElseThrow(() -> new VisualTestException("the unreachable rack shows no address on the client"));
        List<String> lines = GoggleShots.lines(context, pos);
        GoggleShots.requireLine(lines, address.format());
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_UNREACHABLE_AISLE).getString());
        GoggleShots.requireNoLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_NO_AISLE).getString());
        LOGGER.info(PREFIX + "comb: CHECK the rack of the cut aisle says '{}' under its own address {}",
                WareworksLang.translateDirect(WareworksLang.GOGGLES_UNREACHABLE_AISLE).getString(), address.format());
    }

    private static NetworkGoggleInfo syncedNetwork(VisualContext context) {
        return controllerSummary(context).flatMap(ControllerGoggleSummary::network)
                .orElseThrow(() -> new VisualTestException("the controller's summary carries no network"));
    }

    private static int syncedAisleCount(VisualContext context) {
        return controllerSummary(context).flatMap(ControllerGoggleSummary::network)
                .map(NetworkGoggleInfo::aisleCount).orElse(0);
    }

    private static NetworkStop syncedStop(VisualContext context) {
        return controllerSummary(context).flatMap(ControllerGoggleSummary::network)
                .map(NetworkGoggleInfo::stop).orElse(NetworkStop.END);
    }

    /** The block the controller's stop line names: the dock plus the offsets the network reported. */
    private static BlockPos controllerStopPos(VisualContext context, NetworkGoggleInfo network) {
        return context.origin().offset(network.stopDx(), 0, network.stopDz());
    }

    /** Fails the run unless the client heard the machine turn. */
    private static void assertTurnSounds(VisualContext context) {
        List<String> missing = TURN_SOUNDS.stream().filter(sound -> context.soundCount(sound) == 0).toList();
        LOGGER.info(PREFIX + "comb: turn sounds {}", TURN_SOUNDS.stream()
                .map(sound -> sound + "=" + context.soundCount(sound)).toList());
        if (!missing.isEmpty())
            throw new VisualTestException("the turns were not heard: " + missing + "; heard " + context.soundCounts());
    }

    // --- the player --------------------------------------------------------------------------------------------------

    /** Lifts the player back into the air and switches creative flight on again, and waits until the client has it. */
    private void fly(VisualScript script) {
        script.server("comb: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("comb: wait until the client is flying too", context -> {
            var player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    private void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(FLY_HEIGHT);
        player.teleportTo(server.overworld(), above.getX() + BLOCK_CENTER, above.getY(), above.getZ() + BLOCK_CENTER,
                player.getYRot(), player.getXRot());
    }

    /** Flying, wearing Engineer's Goggles and holding nothing, so no item covers a corner of a shot with the GUI. */
    private void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- cameras that follow the machine -------------------------------------------------------------------------------

    /** In the aisle ahead of the machine, looking back at the carriage — on whichever aisle it is named on. */
    private static CameraView frontView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(FRONT, craneX + heading.getStepX() * FRONT_AHEAD, pose.y() + FRONT_ABOVE,
                craneZ + heading.getStepZ() * FRONT_AHEAD, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    /** Low over the arm's own side of the machine, looking steeply down at the arm inside the rack. */
    private static CameraView armOverView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        Direction armSide = armSideOf(pose);
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(ARM_OVER,
                craneX + armSide.getStepX() * ARM_OVER_ASIDE - heading.getStepX() * ARM_OVER_BEHIND,
                pose.y() + ARM_OVER_ABOVE,
                craneZ + armSide.getStepZ() * ARM_OVER_ASIDE - heading.getStepZ() * ARM_OVER_BEHIND,
                craneX + armSide.getStepX() * ARM_LOOK_ASIDE, pose.y() + ARM_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * ARM_LOOK_ASIDE);
    }

    /** Straight down over the machine, looking at the side its arm reaches into. */
    private static CameraView overView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        Direction armSide = armSideOf(pose);
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(OVER, craneX - heading.getStepX() * OVER_BEHIND, pose.y() + OVER_ABOVE,
                craneZ - heading.getStepZ() * OVER_BEHIND, craneX + armSide.getStepX() * OVER_LOOK_ASIDE,
                pose.y() + OVER_LOOK_HEIGHT, craneZ + armSide.getStepZ() * OVER_LOOK_ASIDE);
    }

    /** Behind the machine along its aisle and across from its arm, high over the rack wall. */
    private static CameraView swingView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        Direction armSide = armSideOf(pose);
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(SWING,
                craneX - armSide.getStepX() * SWING_ASIDE - heading.getStepX() * SWING_BACK, pose.y() + SWING_ABOVE,
                craneZ - armSide.getStepZ() * SWING_ASIDE - heading.getStepZ() * SWING_BACK,
                craneX + armSide.getStepX() * SWING_LOOK_ASIDE, pose.y() + SWING_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * SWING_LOOK_ASIDE);
    }

    /** Above a block, looking down on its top face — never at the aisle face, which carries the value box. */
    private static CameraView overheadView(String label, RackPosition rack) {
        double x = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.X) + outward(rack).getStepX();
        double z = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.Z) + outward(rack).getStepZ();
        return CameraView.of(label, x, GOGGLE_EYE_HEIGHT + rack.y(), z - GOGGLE_EYE_BACK, x,
                GOGGLE_TOP_FACE + rack.y(), z);
    }

    /**
     * The controller's tooltip is read from its <b>north</b> face, off centre: every other face of a controller
     * carries the centred aisle-letter value box, and Create's goggle overlay bails out before its first line while
     * one is under the crosshair ({@code GoggleOverlayRenderer}).
     */
    private static CameraView controllerView() {
        return CameraView.of("goggles-controller", -1.5, 1.8, -2.6, -0.78, 0.21, -0.02);
    }

    private static CranePose frozenPose(VisualContext context) {
        return clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
    }

    private static Direction armSideOf(CranePose pose) {
        Direction heading = headingOf(pose.branch());
        return pose.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
    }

    // --- geometry ----------------------------------------------------------------------------------------------------

    /** Aisles of the whole comb: the main run plus one per side aisle. */
    private static int aisleCount() {
        return TEETH.length + 1;
    }

    private static Direction headingOf(int branch) {
        return branch == RackPosition.FIRST_BRANCH ? MAIN_AISLE : TOOTH_AISLE;
    }

    /** The direction a member at this position faces away from its own rails: outwards, across the aisle. */
    private static Direction outward(RackPosition rack) {
        Direction heading = headingOf(rack.branch());
        return rack.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
    }

    /** Where the machine stands, as an offset from the dock along one world axis. */
    private static double offset(CranePose pose, Direction.Axis axis) {
        return offsetOf(pose.branch(), pose.x(), axis);
    }

    private static double offsetOf(int branch, double x, Direction.Axis axis) {
        int main = axis == Direction.Axis.X ? MAIN_AISLE.getStepX() : MAIN_AISLE.getStepZ();
        int tooth = axis == Direction.Axis.X ? TOOTH_AISLE.getStepX() : TOOTH_AISLE.getStepZ();
        return branch == RackPosition.FIRST_BRANCH ? main * x : main * TEETH[branch - 1] + tooth * x;
    }

    private static BlockPos aislePos(BlockPos dock, int branch, int x) {
        return branch == RackPosition.FIRST_BRANCH ? dock.relative(MAIN_AISLE, x)
                : dock.relative(MAIN_AISLE, TEETH[branch - 1]).relative(TOOTH_AISLE, x);
    }

    private static BlockPos rackPos(BlockPos dock, RackPosition rack) {
        return aislePos(dock, rack.branch(), rack.x()).relative(outward(rack)).above(rack.y());
    }

    private static BlockPos chestPos(BlockPos dock, RackPosition rack) {
        return rackPos(dock, rack).relative(outward(rack));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(MAIN_AISLE.getOpposite());
    }

    /** The pull chest the hopper below the output fills, one block further along aisle D. */
    private static BlockPos pullChestPos(BlockPos dock) {
        return rackPos(dock, OUTPUT).below().relative(TOOTH_AISLE);
    }

    private static BlockPos leverPos(BlockPos dock) {
        return rackPos(dock, OUTPUT).relative(outward(OUTPUT));
    }

    /** The belt's last block, right in front of the warehouse input. */
    private static BlockPos feedBeltEnd(BlockPos dock) {
        return rackPos(dock, INPUT).relative(outward(INPUT));
    }

    private static BlockPos feedBeltStart(BlockPos dock) {
        return rackPos(dock, INPUT).relative(outward(INPUT), FEED_BELT_LENGTH);
    }

    private static BlockPos feedChestPos(BlockPos dock) {
        return feedBeltStart(dock).above();
    }

    /** Which way the feed belt must carry: from its tail towards the warehouse input. */
    private static Direction feedDirection() {
        return outward(INPUT).getOpposite();
    }

    /** Whether the machine stands exactly on a block two aisles share, under either of its two names. */
    private static boolean onAJunction(CranePose pose) {
        if (pose.branch() != RackPosition.FIRST_BRANCH)
            return Math.abs(pose.x()) < POSITION_EPSILON;
        for (int junction : TEETH) {
            if (Math.abs(pose.x() - junction) < POSITION_EPSILON)
                return true;
        }
        return false;
    }

    /**
     * How far along the <b>main run</b> a machine driving towards {@code target} is going: the target's own position
     * when the work is on the run, and otherwise the junction it has to turn at. {@code NaN} when the target names an
     * aisle this comb does not have.
     */
    private static double destinationOnTheRun(CranePose target) {
        int branch = target.branch();
        if (branch == RackPosition.FIRST_BRANCH)
            return target.x();
        return branch >= 1 && branch <= TEETH.length ? TEETH[branch - 1] : Double.NaN;
    }

    /** How far through its quarter turn the machine is: 0 as it starts, 1 when it is square with its aisle again. */
    private static double turnProgress(CranePose pose) {
        double aligned = CranePose.yawOf(Headings.of(headingOf(pose.branch())));
        return 1.0 - Math.abs(CranePose.yawDelta(pose.yaw(), aligned));
    }

    // --- the world, read and written --------------------------------------------------------------------------------

    /** Moves one batch from the feed chest onto the belt's tail, from above, whenever that segment is free. */
    private static void feedBelt(ServerLevel level, BlockPos dock) {
        BlockPos start = feedBeltStart(dock);
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(level, start, DirectBeltInputBehaviour.TYPE);
        if (belt == null)
            throw new VisualTestException("no belt with a direct belt input at " + start);
        if (!belt.canInsertFromSide(Direction.UP) || belt.isOccupied(Direction.UP))
            return;
        IItemHandler chest = handlerAt(level, feedChestPos(dock));
        for (int slot = 0; slot < chest.getSlots(); slot++) {
            ItemStack available = chest.extractItem(slot, BATCH, true);
            if (available.isEmpty())
                continue;
            ItemStack rest = belt.handleInsertion(available.copy(), Direction.UP, false);
            int moved = available.getCount() - rest.getCount();
            if (moved > 0)
                chest.extractItem(slot, moved, false);
            return;
        }
    }

    private static boolean isChestEmpty(ServerLevel level, BlockPos pos) {
        IItemHandler handler = handlerAt(level, pos);
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty())
                return false;
        }
        return true;
    }

    private static long beltItems(ServerLevel level, BlockPos segment) {
        BeltBlockEntity first = AllBlockEntityTypes.BELT.getNullable(level, segment);
        BeltBlockEntity belt = first == null ? null : first.getControllerBE();
        if (belt == null || belt.getInventory() == null)
            return 0L;
        long items = 0;
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems())
            items += transported.stack.getCount();
        return items;
    }

    private static Direction beltMovement(ServerLevel level, BlockPos pos) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, pos);
        if (belt == null)
            throw new VisualTestException("no belt at " + pos);
        return belt.getMovementFacing();
    }

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static StackerCraneBlockEntity dock(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the stacker crane dock at " + dock + " is missing");
        return crane;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static WarehouseLayout warehouse(ServerLevel level, BlockPos dock) {
        return controller(level, dock).warehouse()
                .orElseThrow(() -> new VisualTestException("the controller has no warehouse"));
    }

    private static RailNetwork network(ServerLevel level, BlockPos dock) {
        return dock(level, dock).discoveredNetwork()
                .orElseThrow(() -> new VisualTestException("the dock recorded no network"));
    }

    /** What the goggles of the interface at {@code rack} say about its aisle, resolved exactly as they are in game. */
    private static AisleAssignment assignmentOf(ServerLevel level, BlockPos dock, RackPosition rack) {
        BlockPos pos = rackPos(dock, rack);
        if (!(level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity member))
            throw new VisualTestException("no warehouse interface at " + pos);
        return WarehouseRegistry.assignmentOf(level, pos, member);
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(ServerLevel level, BlockPos dock, RackPosition rack) {
        WarehouseInterfaceBlockEntity storage = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(level,
                rackPos(dock, rack));
        if (storage == null)
            throw new VisualTestException("no warehouse interface at " + rack);
        return storage;
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static void motorAt(ServerLevel level, BlockPos pos, Direction facing, int rpm) {
        level.setBlockAndUpdate(pos,
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, facing));
        motor(level, pos).generatedSpeed.setValue(rpm);
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static void insertAll(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handlerAt(level, pos), stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    private static long countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }
}
