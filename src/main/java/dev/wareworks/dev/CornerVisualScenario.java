package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.warehouse.LocationRecord;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "corner": an <b>L-shaped</b> warehouse worked across the bend, and the machine turning in it (M21, issue
 * #1, ADR-033). This is the shot the feature is judged by.
 * <p>
 * <b>The build</b>, relative to the dock (the scene origin): a creative motor below the dock, the dock facing east,
 * the controller behind it, {@value #FIRST_RAILS} rails east to the corner block and {@value #SECOND_RAILS} more south
 * of it. Aisle <b>A</b> runs east over positions 0..{@value #FIRST_RAILS}, aisle <b>B</b> south over positions
 * 0..{@value #SECOND_RAILS}, and the corner block is the last position of A and position 0 of B at the same time.
 * A belt fed from a chest carries items into the warehouse input on aisle A; a warehouse output with a hopper, a pull
 * chest and a lever sits at the far end of aisle B. Racks stand on <b>both</b> aisles and <b>in the corner</b>:
 * <ul>
 * <li>four on aisle A, dedicated to iron;</li>
 * <li>four on aisle B, dedicated to copper;</li>
 * <li>the corner block's two free faces — its north face, which belongs to aisle A (gold), and its east face, which
 * belongs to aisle B (lapis);</li>
 * <li>the block <b>inside the bend</b> (redstone), which lies laterally beside a straight rail of each aisle and is
 * therefore a rack position of both: the one block in a warehouse where the ownership rule decides, and it decides by
 * the way the interface faces. It faces west, away from aisle B's rails, so it is B's.</li>
 * </ul>
 * Every rack carries a store filter, so where each item type ends up is decided before the run and can be asserted
 * afterwards, and three of the five types can only be reached round the bend.
 * <p>
 * <b>The tour</b> of each pass: the L from above, from outside the bend and down aisle B; then, with the game ticks
 * frozen, the six moments this scenario exists for — the machine <b>approaching</b> the corner with items and a target
 * on the other aisle, <b>mid-turn</b> on the corner block (from outside the bend, from directly above and from far
 * enough away to see the whole machine), its arm in a <b>rack of the corner</b>, the machine <b>leaving</b> down aisle
 * B, <b>carrying</b> along it and reaching into a rack of it. The Flywheel pass adds the goggle tooltips — the
 * controller while a job crosses the corner ("From A-01-02L to B-01-03R", one line naming both aisles), and a rack of
 * each aisle plus the corner rack with the address it really has — and a retrieval the other way round the bend,
 * through the output on aisle B into its pull chest.
 * <p>
 * <b>Nothing here is only a picture.</b> Every frozen moment is reached by a <i>server</i> condition that freezes the
 * ticks in the same server tick, so what the shot shows is the machine's own state, and both the server pose and the
 * pose the client draws are checked against what the shot claims. The item census of the whole scene is taken before
 * the first item moves and checked at every moment and after every act, and which rack received what is checked
 * against the chests themselves.
 */
public final class CornerVisualScenario implements VisualScenario {
    public static final String NAME = "corner";
    /** Its own throw-away world: goggle tooltips need a non-spectator with the vanilla reach. */
    private static final String WORLD_FOLDER = "wareworks_visual_corner";
    /** The run builds, stores, turns, retrieves and photographs twice; the default four minutes are not enough. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    private static final Direction FIRST_AISLE = Direction.EAST;
    private static final Direction SECOND_AISLE = Direction.SOUTH;
    /** Rails east of the dock; the last one is the corner block, which is position 0 of aisle B as well. */
    private static final int FIRST_RAILS = 6;
    /** Rails south of the corner block. */
    private static final int SECOND_RAILS = 5;
    /**
     * Speed of the crane's creative motor. Deliberately not the 128 RPM of the other crane scenarios: a quarter turn
     * costs one block of travel ({@code crane.turnPenaltyBlocks}), which at 128 RPM is three ticks — too fast to catch
     * reliably and too fast to watch. At {@value} RPM the machine travels {@code 48/384} blocks per tick, so a quarter
     * turn takes eight ticks: still brisk, but a turn a camera can be pointed at.
     */
    private static final int MOTOR_RPM = 48;
    private static final int BELT_RPM = 32;
    /** Speed of the cogwheels and the gearbox under the floor, which are there to drone ({@link #buildCogDrive}). */
    private static final int COG_DRIVE_RPM = 32;
    /** Belt blocks between the feed chest and the warehouse input. */
    private static final int FEED_BELT_LENGTH = 4;

    private static final Item AISLE_A_ITEM = Items.IRON_INGOT;
    private static final Item AISLE_B_ITEM = Items.COPPER_INGOT;
    private static final Item CORNER_A_ITEM = Items.GOLD_INGOT;
    private static final Item CORNER_B_ITEM = Items.LAPIS_LAZULI;
    private static final Item INNER_CORNER_ITEM = Items.REDSTONE;

    /** The input station on aisle A, which the belt feeds. */
    private static final RackPosition INPUT = RackPosition.of(2, 0, Side.LEFT);
    /** The output station at the far end of aisle B, one level up, with the hopper and the pull chest below it. */
    private static final RackPosition OUTPUT = new RackPosition(1, SECOND_RAILS, 1, Side.RIGHT);
    /** The rack the goggle shot of aisle A looks at. */
    private static final RackPosition GOGGLE_RACK_A = RackPosition.of(4, 0, Side.LEFT);
    /** The rack the goggle shot of aisle B looks at. */
    private static final RackPosition GOGGLE_RACK_B = new RackPosition(1, 3, 0, Side.RIGHT);
    /**
     * The rack <b>inside the bend</b>: laterally beside the rail at position {@value #FIRST_RAILS}{@code - 1} of aisle
     * A and beside position 1 of aisle B at once, so the warehouse offers two candidates for it and only the way the
     * interface faces decides. Facing west — away from aisle B — makes it B's.
     */
    private static final RackPosition INNER_CORNER = new RackPosition(1, 1, 0, Side.RIGHT);

    /** Every storage location of the warehouse, with the item its store filter dedicates it to. */
    private static final List<Rack> RACKS = List.of(
            new Rack(RackPosition.of(2, 0, Side.RIGHT), AISLE_A_ITEM),
            new Rack(RackPosition.of(3, 0, Side.RIGHT), AISLE_A_ITEM),
            new Rack(GOGGLE_RACK_A, AISLE_A_ITEM),
            new Rack(RackPosition.of(5, 0, Side.LEFT), AISLE_A_ITEM),
            new Rack(RackPosition.of(FIRST_RAILS, 0, Side.LEFT), CORNER_A_ITEM),
            new Rack(new RackPosition(1, 0, 0, Side.LEFT), CORNER_B_ITEM),
            new Rack(INNER_CORNER, INNER_CORNER_ITEM),
            new Rack(new RackPosition(1, 3, 0, Side.LEFT), AISLE_B_ITEM),
            new Rack(new RackPosition(1, 4, 0, Side.LEFT), AISLE_B_ITEM),
            new Rack(GOGGLE_RACK_B, AISLE_B_ITEM),
            new Rack(new RackPosition(1, 4, 0, Side.RIGHT), AISLE_B_ITEM));

    /** One batch, and the amount of one push from the feed chest onto the belt. */
    private static final int BATCH = 16;
    /**
     * What one pass puts into the feed chest. The three item types that can only be stored round the bend come in
     * several batches, so there are always more crossings left than moments still to be photographed.
     */
    private static final List<ItemStack> STREAM = List.of(new ItemStack(AISLE_A_ITEM, BATCH),
            new ItemStack(CORNER_A_ITEM, BATCH), new ItemStack(CORNER_B_ITEM, 2 * BATCH),
            new ItemStack(INNER_CORNER_ITEM, 2 * BATCH), new ItemStack(AISLE_B_ITEM, 3 * BATCH));
    /** What the output hands out on a rising edge: iron, which lies on aisle A and has to come round the bend. */
    private static final int OUTPUT_REQUEST_AMOUNT = BATCH;

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int CENSUS_MARGIN = 3;
    private static final int SETTLE_TICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int BELT_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int MOMENT_TIMEOUT_TICKS = 3600;
    private static final int ALL_STORED_TIMEOUT_TICKS = 6000;
    private static final int DELIVERY_TIMEOUT_TICKS = 2400;
    /** Player height above the scene between camera moves, so a creative player never lands and stops flying. */
    private static final int FLY_HEIGHT = 12;

    /** Arm extension from which a shot shows the grabber inside a rack. */
    private static final double ARM_EXTENDED = 0.85;
    /** Distance from the corner, in blocks, within which the approach shot is taken. */
    private static final double APPROACH_NEAR = 0.6;
    private static final double APPROACH_FAR = 2.6;
    /**
     * Quarter-turn progress the mid-turn shot is taken at. The window is well inside the turn on purpose: the client
     * simulates the machine itself and learns of the freeze about a tick later, so it stops a little further along
     * than the server did, and both must still be visibly mid-turn.
     */
    private static final double TURN_PROGRESS_MIN = 0.3;
    private static final double TURN_PROGRESS_MAX = 0.5;
    /** How far the client's yaw may sit ahead of the server's at a frozen moment (a tick of turning is 0.125). */
    private static final double CLIENT_YAW_TOLERANCE = 0.4;
    private static final double BLOCK_CENTER = 0.5;
    private static final double POSITION_EPSILON = 1.0e-6;
    /** Polls between two diagnostic lines while a moment has not come yet (one poll per client tick). */
    private static final int WAITING_LOG_INTERVAL_POLLS = 100;

    /**
     * The deep iron groan of the swing and the metal clack it settles with: the two voices of a quarter turn.
     * <p>
     * Not {@code create:cogs} any more — Create drones that very sample continuously for every cogwheel and gearbox
     * within 16 blocks, and the scene now has both in its power train precisely so that a turn has to be audible
     * <b>over</b> a real drivetrain (M21 review fix).
     */
    private static final List<String> TURN_SOUNDS =
            List.of("minecraft:block.iron_trapdoor.open", "minecraft:block.metal.step");
    /** The one moment whose client check is about more than the branch: a mid-turn shot must still show a turn. */
    private static final String TURN_MOMENT = "turn";
    /** The one moment during which no new items may be fed: what comes back is counted against what went in. */
    private static final String RETRIEVE_MOMENT = "retrieve";

    // --- cameras ----------------------------------------------------------------------------------------------------

    /** Straight down over the whole L; the small northward target offset puts north at the top. */
    private static final CameraView TOP = CameraView.of("top", 3.0, 11.5, 0.5, 3.0, 0.0, 0.45);
    /** From outside the bend (north-east), high enough to see both aisles and everything on them. */
    private static final CameraView BEND = CameraView.of("bend", 9.5, 4.5, -3.5, 4.0, 1.0, 2.5);
    /** Down aisle B from beyond its far end, back towards the corner and the dock. */
    private static final CameraView ALONG_B = CameraView.of("aisle-b", 6.5, 2.8, 9.5, 6.5, 1.2, 2.5);
    /**
     * The corner block from outside the bend and well above the rack wall. The height is the point: at eye level the
     * chests around the corner hide the chassis, and the chassis is the part that turns.
     */
    private static final CameraView CORNER = CameraView.of("corner", 9.8, 5.0, -2.9, 6.6, 1.9, 0.6);
    /** Straight down on the corner block itself, where a machine sweeping into the racks would be unmistakable. */
    private static final CameraView PIVOT = CameraView.of("pivot", 6.5, 7.0, 0.5, 6.5, 0.0, 0.45);
    /** From inside the bend: the corner racks and the rack inside the bend all face this camera. */
    private static final CameraView INSIDE = CameraView.of("inside", 1.5, 3.8, 5.5, 6.2, 1.2, 1.2);
    /** The output cluster at the far end of aisle B: output, lever, hopper and the pull chest. */
    private static final CameraView OUTPUT_VIEW = CameraView.of("output", 2.2, 3.4, 8.6, 5.2, 1.0, 5.2);

    /**
     * Following camera ahead of the crane on the aisle it is named on.
     * <p>
     * It flies <b>three blocks</b> above the rails on purpose: ahead of a machine near the corner is a rack of the
     * other aisle, and the camera is a real player who cannot stand inside one. At this height its feet are clear of
     * every rack of the warehouse, whichever aisle it looks down.
     */
    private static final String FRONT = "front";
    private static final double FRONT_AHEAD = 5.5;
    private static final double FRONT_ABOVE = 3.2;
    private static final double CARRIAGE_LOOK_HEIGHT = 1.6;
    /**
     * Following close camera for the arm moment: over the rails, where only the 3 px rail bed stands, and high enough
     * to look <b>down into</b> the gap between the rails and the rack wall — from the side, a rack wall of chests
     * hides the very arm the shot is about.
     */
    private static final String CLOSE = "close";
    private static final double CLOSE_AHEAD = 1.2;
    /**
     * Over the rack wall on the <b>other</b> side of the aisle. From behind, the mast hides the arm; from the arm's
     * own side, the chest it reaches into does.
     */
    private static final double CLOSE_ASIDE = 1.8;
    private static final double CLOSE_ABOVE = 3.6;
    private static final double CLOSE_LOOK_ASIDE = 0.8;
    private static final double ARM_LOOK_HEIGHT = 0.7;
    /** Straight down over the machine: the one angle at which an arm inside a rack wall is never hidden. */
    private static final String OVER = "over";
    private static final double OVER_ABOVE = 8.0;
    private static final double OVER_BEHIND = 1.2;
    private static final double OVER_LOOK_HEIGHT = 0.4;

    /** Goggle camera: above the block, looking down on its top face, away from the value box on its aisle face. */
    private static final double GOGGLE_EYE_HEIGHT = 4.0;
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

    /** On aisle A, carrying, with a target on aisle B: the last blocks before the bend. */
    private static final CraneMoment APPROACHING = (state, held) -> {
        CranePose pose = state.pose();
        return state.phase() == CranePhase.TRAVEL_TO_TARGET && held > 0
                && pose.branch() == RackPosition.FIRST_BRANCH && pose.isAligned()
                && state.target().branch() != RackPosition.FIRST_BRANCH
                && pose.x() >= FIRST_RAILS - APPROACH_FAR && pose.x() <= FIRST_RAILS - APPROACH_NEAR;
    };

    /** On the corner block, between the two aisle headings: the moment this whole scenario is about. */
    private static final CraneMoment TURNING = (state, held) -> {
        CranePose pose = state.pose();
        double progress = turnProgress(pose);
        return !pose.isAligned() && onCornerBlock(pose) && progress >= TURN_PROGRESS_MIN
                && progress <= TURN_PROGRESS_MAX;
    };

    /** The arm inside one of the racks at the corner, with the machine standing on the corner block itself. */
    private static final CraneMoment SERVING_THE_CORNER = (state, held) -> {
        CranePose pose = state.pose();
        return onCornerBlock(pose) && pose.isAligned() && pose.arm() >= ARM_EXTENDED;
    };

    /** On aisle B, square with it, carrying away from the corner: the machine has left the bend behind. */
    private static final CraneMoment LEAVING = (state, held) -> {
        CranePose pose = state.pose();
        return state.phase() == CranePhase.TRAVEL_TO_TARGET && held > 0
                && pose.branch() != RackPosition.FIRST_BRANCH && pose.isAligned() && pose.x() >= APPROACH_NEAR
                && pose.x() <= APPROACH_FAR && state.target().x() > pose.x();
    };

    /** Well down aisle B with items: a machine at work on an aisle that does not touch the dock. */
    private static final CraneMoment CARRYING = (state, held) -> {
        CranePose pose = state.pose();
        return state.phase() == CranePhase.TRAVEL_TO_TARGET && held > 0
                && pose.branch() != RackPosition.FIRST_BRANCH && pose.isAligned() && pose.x() > APPROACH_FAR;
    };

    /** The arm inside a rack of aisle B, clear of the corner. */
    private static final CraneMoment ARM_IN_AISLE_B = (state, held) -> {
        CranePose pose = state.pose();
        return pose.branch() != RackPosition.FIRST_BRANCH && pose.x() > APPROACH_FAR && pose.isAligned()
                && pose.arm() >= ARM_EXTENDED;
    };

    /** The way back: on aisle B, carrying, on the way up to the output one level above the rails. */
    private static final CraneMoment RETRIEVING = (state, held) -> state.phase() == CranePhase.TRAVEL_TO_TARGET
            && held > 0 && state.pose().branch() != RackPosition.FIRST_BRANCH && state.target().y() > 0;

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
        script.client("corner: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "corner run"))
                .server("corner: clear the area and place the creative motor", this::placeMotor)
                .server("corner: build the L, the stations, the racks and the feed belt", this::buildScene)
                .serverUntil("corner: wait until the warehouse bends and every member joined", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("corner: aim the feed belt at the warehouse input", this::aimBelt)
                .serverUntil("corner: wait until the belt really carries towards the input", this::beltAimed,
                        BELT_TIMEOUT_TICKS)
                .server("corner: dedicate every rack to one item type", this::setStoreFilters)
                .server("corner: check the corner racks against the warehouse itself", this::assertCornerOwnership)
                .server("corner: preset the request of the output on aisle B", this::presetOutputFilter)
                .server("corner: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("corner: the player flies and wears Engineer's Goggles", this::equipPlayer)
                .server("corner: take the item census of the whole scene", this::takeCensus);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so every world shot
        // is taken by a player who reaches nothing; the goggle shots switch the real reach on for themselves.
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        for (CameraView view : List.of(TOP, BEND, ALONG_B))
            script.shotFrom(view, "idle");

        if (pass == VisualPass.FLYWHEEL)
            script.server("corner: run the creative motor at " + MOTOR_RPM + " RPM", this::powerOn);
        script.server("corner: put the stream into the feed chest", this::fillFeedChest);

        moment(script, "approach", APPROACHING, MomentView.fixed(CORNER),
                MomentView.following(FRONT, CornerVisualScenario::frontView));
        moment(script, TURN_MOMENT, TURNING, MomentView.fixed(CORNER), MomentView.fixed(PIVOT),
                MomentView.fixed(BEND));
        // Fixed views only: ahead of a machine on the corner block stands a rack of the other aisle, and a following
        // camera is a player who cannot stand inside one.
        moment(script, "corner-rack", SERVING_THE_CORNER, MomentView.fixed(CORNER), MomentView.fixed(PIVOT),
                MomentView.following(OVER, CornerVisualScenario::overView), MomentView.fixed(INSIDE));
        moment(script, "leave", LEAVING, MomentView.fixed(CORNER), MomentView.fixed(ALONG_B));
        moment(script, "carry", CARRYING, MomentView.following(FRONT, CornerVisualScenario::frontView),
                MomentView.fixed(ALONG_B));
        moment(script, "arm", ARM_IN_AISLE_B, MomentView.following(CLOSE, CornerVisualScenario::armView),
                MomentView.following(OVER, CornerVisualScenario::overView), MomentView.fixed(INSIDE));

        if (pass == VisualPass.FLYWHEEL) {
            script.server("corner: put another stream into the feed chest", this::fillFeedChest);
            controllerGoggleShot(script);
        }
        script.serverUntil("corner: wait until every item is stored and the crane is idle", this::allStored,
                        ALL_STORED_TIMEOUT_TICKS)
                .server("corner: check which rack received what", this::assertStored);

        if (pass == VisualPass.FLYWHEEL) {
            rackGoggleShots(script);
            retrieveThroughTheOutput(script);
            script.client("corner: every check passed",
                    context -> LOGGER.info(PREFIX + "corner: ALL CHECKS PASSED (the machine turns at the corner, the "
                            + "corner racks are served, the rack inside the bend belongs to the aisle it faces, every "
                            + "item reached the rack it was addressed to, the census is exact, and a retrieval comes "
                            + "back round the bend)"));
        }
        if (pass == VisualPass.values()[VisualPass.values().length - 1])
            script.client("corner: check that the turn was heard", CornerVisualScenario::assertTurnSounds);
    }

    @Override
    public String status(VisualContext context) {
        String state = clientCrane(context).map(crane -> {
            CraneState<ItemKey, RackPosition> craneState = crane.craneState();
            CranePose pose = craneState.pose();
            return String.format(Locale.ROOT,
                    "branch=%d posX=%.2f posY=%.2f yaw=%.2f turn=%.2f arm=%.2f side=%s phase=%s held=%d target=%d/%.2f",
                    pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                    craneState.phase(), crane.goggleInfo().heldCount(), craneState.target().branch(),
                    craneState.target().x());
        }).orElse("crane=missing");
        return "moment=" + moment + " " + state + GoggleShots.describeHover(context);
    }

    // --- building (server thread) ---------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = BlockPos.ZERO;
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-2, -1, -FEED_BELT_LENGTH - 2),
                dock.offset(FIRST_RAILS + 2, CLEAR_HEIGHT, SECOND_RAILS + 2)).inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -FEED_BELT_LENGTH - CLEAR_MARGIN),
                dock.offset(FIRST_RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, SECOND_RAILS + CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motorAt(level, dock.below(), Direction.UP, 0);

        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, FIRST_AISLE));
        for (int x = 1; x <= FIRST_RAILS; x++)
            level.setBlockAndUpdate(aislePos(dock, RackPosition.FIRST_BRANCH, x),
                    WarehouseRailBlock.along(FIRST_AISLE.getAxis()));
        for (int x = 1; x <= SECOND_RAILS; x++)
            level.setBlockAndUpdate(aislePos(dock, 1, x), WarehouseRailBlock.along(SECOND_AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, FIRST_AISLE));

        level.setBlockAndUpdate(rackPos(dock, INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, outward(INPUT).getOpposite()));
        for (Rack rack : RACKS)
            placeStorage(level, dock, rack.position());
        buildOutputCluster(level, dock);
        buildFeedBelt(level, dock);
        buildCogDrive(level, dock);
    }

    /**
     * A cogwheel drivetrain and a gearbox, spinning, within earshot of every camera of this scene (M21 review fix).
     * <p>
     * They are here for the <b>sound</b>, and they are load bearing: Create drones {@code create:cogs} continuously at
     * volume 1.5 for every cogwheel, large cogwheel and gearbox within 16 blocks of the player
     * ({@code SoundScapes#cogwheel}), which is the drivetrain a real warehouse is driven by — and which the old turn
     * cue, Create's own {@code COGS} sample at 0.35, would have been drowned in. A scene powered by nothing but a
     * creative motor never faced that, so the turn was never really heard over anything. With these in it, the
     * {@code sounds:} census of a run shows the drone and the turn side by side.
     * <p>
     * They sit <b>under the floor</b>, one layer below the ground the warehouse stands on: sound does not care, and
     * the forty-odd framed shots of this scenario are compared against each other from run to run, so nothing may
     * appear in them that is not part of the warehouse.
     */
    private static void buildCogDrive(ServerLevel level, BlockPos dock) {
        // The ground a player walks on is dock.below(); everything here is a layer under it, out of every camera.
        BlockPos cogs = dock.below(2);
        BlockPos motors = dock.below(3);
        motorAt(level, motors.relative(FIRST_AISLE, 1), Direction.UP, COG_DRIVE_RPM);
        level.setBlockAndUpdate(cogs.relative(FIRST_AISLE, 1), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        motorAt(level, motors.relative(FIRST_AISLE, 3), Direction.UP, COG_DRIVE_RPM);
        level.setBlockAndUpdate(cogs.relative(FIRST_AISLE, 3), AllBlocks.LARGE_COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        // A gearbox takes a shaft on every face whose axis is not its own, so a vertical one is driven from the side.
        motorAt(level, cogs.relative(FIRST_AISLE, 5), FIRST_AISLE, COG_DRIVE_RPM);
        level.setBlockAndUpdate(cogs.relative(FIRST_AISLE, 6), AllBlocks.GEARBOX.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
    }

    /** A storage location: a chest outside the rack wall and a warehouse interface facing it. */
    private static void placeStorage(ServerLevel level, BlockPos dock, RackPosition rack) {
        BlockPos pos = rackPos(dock, rack);
        Direction outward = outward(rack);
        level.setBlockAndUpdate(pos.relative(outward),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward));
    }

    /**
     * The output at the far end of aisle B, one level up, with a hopper below it that pulls the delivery out and
     * pushes it into the pull chest, and a lever on a stone block beside it for the redstone request.
     */
    private static void buildOutputCluster(ServerLevel level, BlockPos dock) {
        Direction outward = outward(OUTPUT);
        BlockPos output = rackPos(dock, OUTPUT);
        BlockPos hopper = output.below();
        level.setBlockAndUpdate(output, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(hopper,
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, SECOND_AISLE));
        level.setBlockAndUpdate(pullChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward));
        // A lever reports signal 15 to every side, so the output beside it sees a neighbour signal; the stone below
        // carries it.
        level.setBlockAndUpdate(hopper.relative(outward), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, SECOND_AISLE)
                .setValue(LeverBlock.POWERED, false));
    }

    /**
     * The feed: a chest over a belt that carries into the warehouse input, whose {@link DirectBeltInputBehaviour} is
     * the path Create designs for a belt-fed machine. {@link #feedBelt} moves one batch at a time from the chest onto
     * the belt's tail, from above, with exactly the call a chute or a brass funnel over the tail makes.
     */
    private static void buildFeedBelt(ServerLevel level, BlockPos dock) {
        BlockPos start = feedBeltStart(dock);
        BlockPos end = feedBeltEnd(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the feed belt was not created between " + start + " and " + end);
        // The belt runs across the aisle, so it turns on the aisle's own axis and its motor stands beside the first
        // pulley.
        motorAt(level, start.relative(FIRST_AISLE), FIRST_AISLE.getOpposite(), BELT_RPM);
        level.setBlockAndUpdate(feedChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, FIRST_AISLE));
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0
                && controller.warehouse().map(WarehouseLayout::branchCount).orElse(0) == 2
                && controller.storageLocations().size() == RACKS.size() && controller.inputStations().size() == 1
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && crane.networkGeometry().branchCount() == 2;
    }

    /** Which way a belt carries follows the sign of its rotation, so it is read back and the motor reversed if wrong. */
    private void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Direction wanted = feedDirection();
        Direction actual = beltMovement(level, feedBeltStart(dock));
        LOGGER.info(PREFIX + "corner: the feed belt carries {} and must carry {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, feedBeltStart(dock).relative(FIRST_AISLE));
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
        LOGGER.info(PREFIX + "corner: {} racks dedicated, {} of them round the bend", RACKS.size(),
                RACKS.stream().filter(rack -> rack.position().branch() != RackPosition.FIRST_BRANCH).count());
    }

    /**
     * The claim the corner shots make about the warehouse, checked against the warehouse itself: the rack inside the
     * bend really is a position of <b>both</b> aisles and really belongs to the one it faces away from, and the two
     * free faces of the corner block really are positions of the aisles they face.
     */
    private void assertCornerOwnership(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        WarehouseLayout warehouse = controller.warehouse()
                .orElseThrow(() -> new VisualTestException("the controller has no warehouse"));

        BlockPos inner = rackPos(dock, INNER_CORNER);
        List<RackPosition> candidates = warehouse.candidates(inner);
        if (candidates.size() != 2)
            throw new VisualTestException("the block inside the bend at " + inner + " should be a rack position of "
                    + "both aisles, but the warehouse offers " + candidates);
        if (warehouse.worldToLocal(inner).isPresent())
            throw new VisualTestException("the warehouse decided the ambiguous block " + inner + " by itself instead "
                    + "of leaving it to the member standing there");
        assertMemberAt(controller, INNER_CORNER, inner, "the rack inside the bend");
        assertAddress(warehouse, INNER_CORNER, 'B', "the rack inside the bend faces away from aisle B, so it is B's");

        RackPosition cornerOnA = RackPosition.of(FIRST_RAILS, 0, Side.LEFT);
        RackPosition cornerOnB = new RackPosition(1, 0, 0, Side.LEFT);
        assertMemberAt(controller, cornerOnA, rackPos(dock, cornerOnA), "the north face of the corner block");
        assertMemberAt(controller, cornerOnB, rackPos(dock, cornerOnB), "the east face of the corner block");
        assertAddress(warehouse, cornerOnA, 'A', "the corner block's north face is the last position of aisle A");
        assertAddress(warehouse, cornerOnB, 'B', "the corner block's east face is position 0 of aisle B");
        if (!warehouse.aislePos(RackPosition.FIRST_BRANCH, FIRST_RAILS).equals(warehouse.aislePos(1, 0)))
            throw new VisualTestException("the corner block is not the same block on both aisles");
        LOGGER.info(PREFIX + "corner: CHECK the corner racks belong to the aisles they face ({} candidates inside the "
                + "bend, resolved to {})", candidates.size(), warehouse.address(INNER_CORNER).orElseThrow().format());
    }

    private static void assertMemberAt(WarehouseControllerBlockEntity controller, RackPosition expected, BlockPos pos,
            String what) {
        List<RackPosition> members = controller.storageLocations().stream().map(LocationRecord::position)
                .filter(expected::equals).toList();
        if (members.size() != 1)
            throw new VisualTestException(what + " at " + pos + " is not a storage location of " + expected + ": "
                    + controller.storageLocations());
    }

    private static void assertAddress(WarehouseLayout warehouse, RackPosition rack, char letter, String what) {
        StorageAddress address = warehouse.address(rack)
                .orElseThrow(() -> new VisualTestException(what + ": " + rack + " has no address"));
        if (address.aisle() != letter)
            throw new VisualTestException(what + ", but its address is " + address.format());
    }

    /** Presets the output's request, so flipping the lever fetches iron from aisle A without any setup by the player. */
    private void presetOutputFilter(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(server.overworld(), rackPos(context.origin(), OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output on aisle B is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(AISLE_A_ITEM)))
            throw new VisualTestException("the warehouse output refused the preset filter");
        filter.count = OUTPUT_REQUEST_AMOUNT; // after setFilter, which may clamp the count
    }

    private void takeCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box " + censusBox + " is not fully loaded");
        conserved = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "corner: baseline census {} in {}", SceneItemCensus.describe(conserved), censusBox);
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
        LOGGER.info(PREFIX + "corner: the feed chest holds another stream ({} items in {} batches)",
                STREAM.stream().mapToInt(ItemStack::getCount).sum(), STREAM.size());
    }

    /**
     * Nothing is moving and a moment has still to come: put another stream in. A warehouse that has run out of work
     * cannot turn a corner, and which item types are still in flight when a moment is reached depends on how long the
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
        LOGGER.info(PREFIX + "corner: the warehouse ran out of work while waiting for '{}'", moment);
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
        script.server("corner: the run is looking for the moment '" + label + "'", (server, context) -> moment = label)
                .serverUntil("corner: run the belt and wait for the moment '" + label + "'",
                        (server, context) -> freezeAtMoment(server, context, test), MOMENT_TIMEOUT_TICKS)
                .until("corner: wait until the client sees the frozen moment '" + label + "'",
                        CornerVisualScenario::clientFrozen, SYNC_TIMEOUT_TICKS)
                .server("corner: check the machine's own state at '" + label + "'",
                        (server, context) -> assertServerMoment(server, context, label, test))
                .client("corner: check the pose the client draws at '" + label + "'",
                        context -> assertClientMoment(context, label));
        fly(script);
        for (MomentView view : views)
            script.camera(view.label(), view.view()).shot(label + "-" + view.label());
        script.freeze(false);
    }

    /**
     * One poll of a moment: keep the belt fed, and when the machine is in the state the shot is about, stop the world
     * right there. Freezing from the server condition itself is what makes the shots honest — a client that asked for
     * a freeze would be two round trips late, and at eight ticks a quarter turn would be over.
     */
    private boolean freezeAtMoment(MinecraftServer server, VisualContext context, CraneMoment test) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        feedBelt(level, dock);
        StackerCraneBlockEntity crane = dock(level, dock);
        if (!test.holds(crane.craneState(), crane.goggleInfo().heldCount())) {
            keepTheWarehouseBusy(level, dock, crane);
            logWhileWaiting(level, dock, crane);
            return false;
        }
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /**
     * One line every {@value #WAITING_LOG_INTERVAL_POLLS} polls while a moment has not come yet: what the machine is
     * doing and where the stream still is. A moment that never comes is otherwise a timeout with no explanation.
     */
    private void logWhileWaiting(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS != 0)
            return;
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                rackPos(dock, INPUT));
        LOGGER.info(PREFIX + "corner: still waiting for '{}': {} | chest={} belt={} input={} reason={}", moment,
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
        LOGGER.info(PREFIX + "corner: CHECK moment '{}' on the server: {}", label, describe(state, held));
    }

    /**
     * The pose the shot really draws. The client simulates the machine itself and learns of the freeze about a tick
     * later, so it does not stop on the same yaw as the server — but it must stop on the same <b>branch</b>, within a
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
        LOGGER.info(PREFIX + "corner: CHECK moment '{}' on the client: {} (yaw drift {})", label,
                describe(state, held), String.format(Locale.ROOT, "%.3f", yawDrift));
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    private static String describe(CraneState<ItemKey, RackPosition> state, long held) {
        CranePose pose = state.pose();
        return String.format(Locale.ROOT,
                "branch=%d x=%.3f y=%.2f yaw=%.3f turn=%.2f arm=%.2f side=%s phase=%s held=%d target=%d/%.2f/%.0f",
                pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                state.phase(), held, state.target().branch(), state.target().x(), state.target().y());
    }

    // --- the goggle shots --------------------------------------------------------------------------------------------

    /**
     * The controller's tooltip while a job crosses the corner: one line naming the rack the items come from and the
     * rack they are going to, each with the letter of <b>its own</b> aisle. Before M21 there was one letter for the
     * whole warehouse, so this line would have named a rack of aisle B with an A address.
     */
    private void controllerGoggleShot(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, controllerView(), "goggles-controller", CornerVisualScenario::controllerPos,
                CornerVisualScenario::crossingJobSynced, CornerVisualScenario::checkControllerGoggles);
        // Bracketed: the same check runs again after the frame was grabbed, so a line that only arrived afterwards
        // could not be the one that was photographed. A job that simply finished in those few ticks is not a failure
        // — it is the machine doing its work — so that case is logged instead.
        script.client("corner: the controller still shows the crossing job",
                CornerVisualScenario::checkControllerGogglesAfterTheShot);
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** A rack of each aisle and the rack inside the bend, each with the address it really has. */
    private void rackGoggleShots(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        for (RackGoggle rack : List.of(new RackGoggle(GOGGLE_RACK_A, 'A', "goggles-rack-a"),
                new RackGoggle(GOGGLE_RACK_B, 'B', "goggles-rack-b"),
                new RackGoggle(INNER_CORNER, 'B', "goggles-corner-rack"))) {
            fly(script);
            GoggleShots.shot(script, NAME, overheadView(rack.label(), rack.position()), rack.label(),
                    dock -> rackPos(dock, rack.position()), context -> rackAddressSynced(context, rack.position()),
                    context -> checkRackGoggles(context, rack));
        }
        GoggleShots.reach(script, NAME, 0.0);
    }

    private record RackGoggle(RackPosition position, char letter, String label) {
    }

    /**
     * The controller's synced summary carries a crane job whose two ends lie on different aisles, and the dock's own
     * synced data says the same — so the line this shot is about is not one throttled packet behind the machine. The
     * crane is also still <b>carrying</b> the items to the far end, which is the longest part of such a job.
     */
    private static boolean crossingJobSynced(VisualContext context) {
        if (crossingCrane(context).isEmpty())
            return false;
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && crane.goggleInfo().job().filter(job -> job.source().branch() != job.target().branch()).isPresent())
                .isPresent();
    }

    /**
     * The crane data behind the controller's tooltip, while its job crosses the corner. Read from the controller's own
     * summary rather than from the dock, because that summary is what draws the lines this shot is about.
     */
    private static Optional<CraneGoggleInfo> crossingCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        if (!(level.getBlockEntity(controllerPos(context.origin())) instanceof WarehouseControllerBlockEntity controller))
            return Optional.empty();
        ControllerGoggleSummary summary = controller.summary();
        return summary.crane()
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
                .orElseThrow(() -> new VisualTestException("the controller shows no job across the corner any more"));
        CraneJobSummary job = crane.job().orElseThrow();
        String source = crane.address(job.source());
        String target = crane.address(job.target());
        if (source.charAt(0) == target.charAt(0))
            throw new VisualTestException("the goggle line names both ends of a job across the corner on the same "
                    + "aisle: from " + source + " to " + target);
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, source);
        GoggleShots.requireLine(lines, target);
        // This tooltip is the milestone's main teaching surface, so the shot proves its size lines too rather than
        // only the job: a warehouse that bends says how many rails and aisles it is made of, lists them underneath,
        // and does NOT show the single-aisle line any more. The first corner evidence set was shot before those lines
        // existed and still read "Aisle: 6 long, mast 4 high" - a screenshot nobody could tell from a regression
        // (M21 review fix).
        ControllerGoggleSummary summary = controllerSummary(context)
                .orElseThrow(() -> new VisualTestException("the controller has no synced summary at all"));
        NetworkGoggleInfo network = summary.network()
                .orElseThrow(() -> new VisualTestException("the controller's summary carries no network"));
        if (network.aisleCount() < 2)
            throw new VisualTestException("this shot is about a warehouse that bends, and the synced network has "
                    + network.aisleCount() + " aisle(s)");
        GoggleShots.requireLine(lines, WareworksLang
                .networkSize(network.rails(), network.aisleCount(), summary.mastHeight()).component().getString());
        GoggleShots.requireNoLine(lines, WareworksLang
                .aisleSize(summary.aisleLength(), summary.mastHeight()).component().getString());
        LOGGER.info(PREFIX + "corner: CHECK the controller's goggles name the job from {} to {}, under a size line of "
                + "{} rails on {} aisles", source, target, network.rails(), network.aisleCount());
    }

    /** The same check after the frame was grabbed; a job that finished in the meantime is work, not a failure. */
    private static void checkControllerGogglesAfterTheShot(VisualContext context) {
        if (crossingCrane(context).isEmpty()) {
            LOGGER.info(PREFIX + "corner: the job across the corner finished right after the shot; the tooltip was "
                    + "checked before the frame was taken");
            return;
        }
        checkControllerGoggles(context);
    }

    /** The interface's own address has reached the client (the server resolves it when a player looks at it). */
    private static boolean rackAddressSynced(VisualContext context, RackPosition rack) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos pos = rackPos(context.origin(), rack);
        return level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity storage
                && storage.aisleAssignment().state() == AisleAssignment.State.ASSIGNED;
    }

    private static void checkRackGoggles(VisualContext context, RackGoggle rack) {
        BlockPos pos = rackPos(context.origin(), rack.position());
        StorageAddress address = StorageAddress.of(rack.letter(), rack.position());
        GoggleShots.requireLine(GoggleShots.lines(context, pos), address.format());
        LOGGER.info(PREFIX + "corner: CHECK the goggles at {} show the address {}", pos, address.format());
    }

    // --- storing and the retrieval round the bend ---------------------------------------------------------------------

    /**
     * One poll of the storing phase: keep the belt fed, and report when the feed chest and the belt are empty, the
     * input buffer is empty and the crane is idle.
     */
    private boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        feedBelt(level, dock);
        StackerCraneBlockEntity crane = dock(level, dock);
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
     * census of the whole scene again.
     */
    private void assertStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        List<String> report = new ArrayList<>();
        for (Item item : List.of(AISLE_A_ITEM, AISLE_B_ITEM, CORNER_A_ITEM, CORNER_B_ITEM, INNER_CORNER_ITEM)) {
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
        SceneItemCensus.assertEquals(level, censusBox, conserved, "after everything was stored");
        LOGGER.info(PREFIX + "corner: CHECK every item reached the rack it was addressed to ({})", report);
    }

    /** The other way round the bend: a redstone request at the output on aisle B for iron that lies on aisle A. */
    private void retrieveThroughTheOutput(VisualScript script) {
        script.server("corner: a player flips the lever beside the output on aisle B",
                (server, context) -> setLever(server, context, true));
        moment(script, RETRIEVE_MOMENT, RETRIEVING, MomentView.following(FRONT, CornerVisualScenario::frontView),
                MomentView.fixed(ALONG_B));
        script.serverUntil("corner: wait until the iron arrived in the pull chest", this::outputDelivered,
                        DELIVERY_TIMEOUT_TICKS)
                .server("corner: flip the lever back", (server, context) -> setLever(server, context, false))
                .server("corner: check the delivery that came round the bend", this::assertDelivered);
        fly(script);
        script.shotFrom(OUTPUT_VIEW, "delivered").shotFrom(BEND, "delivered");
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
        return countAt(server.overworld(), pullChestPos(context.origin()), AISLE_A_ITEM) >= OUTPUT_REQUEST_AMOUNT;
    }

    private void assertDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long delivered = countAt(level, pullChestPos(dock), AISLE_A_ITEM);
        if (delivered != OUTPUT_REQUEST_AMOUNT)
            throw new VisualTestException("the pull chest holds " + delivered + " " + AISLE_A_ITEM + ", expected "
                    + OUTPUT_REQUEST_AMOUNT);
        handedOut.merge(AISLE_A_ITEM, (long) OUTPUT_REQUEST_AMOUNT, Long::sum);
        long left = 0;
        for (Rack rack : RACKS) {
            if (rack.item() == AISLE_A_ITEM)
                left += countAt(level, chestPos(dock, rack.position()), AISLE_A_ITEM);
        }
        long expected = fed.getOrDefault(AISLE_A_ITEM, 0L) - handedOut.getOrDefault(AISLE_A_ITEM, 0L);
        if (left != expected)
            throw new VisualTestException("the racks on aisle A hold " + left + " " + AISLE_A_ITEM + " after the "
                    + "retrieval, expected " + expected);
        SceneItemCensus.assertEquals(level, censusBox, conserved, "after the retrieval round the bend");
        LOGGER.info(PREFIX + "corner: CHECK {} {} came from aisle A round the bend into the pull chest on aisle B",
                delivered, AISLE_A_ITEM);
    }

    /** Fails the run unless the client heard the machine turn. */
    private static void assertTurnSounds(VisualContext context) {
        List<String> missing = TURN_SOUNDS.stream().filter(sound -> context.soundCount(sound) == 0).toList();
        LOGGER.info(PREFIX + "corner: turn sounds {}", TURN_SOUNDS.stream()
                .map(sound -> sound + "=" + context.soundCount(sound)).toList());
        if (!missing.isEmpty())
            throw new VisualTestException("the turn was not heard: " + missing + "; heard " + context.soundCounts());
    }

    // --- the player --------------------------------------------------------------------------------------------------

    /**
     * Lifts the player back into the air and switches creative flight on again, and waits until the client has it.
     * A creative player that touches the ground switches flying off by itself ({@code LocalPlayer#aiStep}), and the
     * next camera view in mid-air would then never be reached.
     */
    private void fly(VisualScript script) {
        script.server("corner: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("corner: wait until the client is flying too", context -> {
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

    /** In the aisle ahead of the crane, looking back at the carriage — on whichever aisle it is named on. */
    private static CameraView frontView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(FRONT, craneX + heading.getStepX() * FRONT_AHEAD, pose.y() + FRONT_ABOVE,
                craneZ + heading.getStepZ() * FRONT_AHEAD, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    /** Close in front of the carriage, slightly off the arm side, looking at the arm inside the rack. */
    private static CameraView armView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        Direction armSide = pose.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(CLOSE,
                craneX + heading.getStepX() * CLOSE_AHEAD - armSide.getStepX() * CLOSE_ASIDE, pose.y() + CLOSE_ABOVE,
                craneZ + heading.getStepZ() * CLOSE_AHEAD - armSide.getStepZ() * CLOSE_ASIDE,
                craneX + armSide.getStepX() * CLOSE_LOOK_ASIDE, pose.y() + ARM_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * CLOSE_LOOK_ASIDE);
    }

    /**
     * Straight down over the machine, looking at the side its arm reaches into. A rack wall hides an arm from every
     * angle at its own height; from above, the arm bridging the gap between the rails and the rack is unmistakable.
     */
    private static CameraView overView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        Direction armSide = pose.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(OVER, craneX - heading.getStepX() * OVER_BEHIND, pose.y() + OVER_ABOVE,
                craneZ - heading.getStepZ() * OVER_BEHIND, craneX + armSide.getStepX() * CLOSE_LOOK_ASIDE,
                pose.y() + OVER_LOOK_HEIGHT, craneZ + armSide.getStepZ() * CLOSE_LOOK_ASIDE);
    }

    /** Above a block, looking down on its top face — never at the aisle face, which carries the value box. */
    private static CameraView overheadView(String label, RackPosition rack) {
        double x = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.X)
                + outward(rack).getStepX();
        double z = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.Z)
                + outward(rack).getStepZ();
        return CameraView.of(label, x, GOGGLE_EYE_HEIGHT, z - GOGGLE_EYE_BACK, x, GOGGLE_TOP_FACE, z);
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

    /** Where the machine stands, as an offset from the dock along one world axis: along aisle A, then along aisle B. */
    private static double offset(CranePose pose, Direction.Axis axis) {
        return offsetOf(pose.branch(), pose.x(), axis);
    }

    private static double offsetOf(int branch, double x, Direction.Axis axis) {
        int first = axis == Direction.Axis.X ? FIRST_AISLE.getStepX() : FIRST_AISLE.getStepZ();
        int second = axis == Direction.Axis.X ? SECOND_AISLE.getStepX() : SECOND_AISLE.getStepZ();
        return branch == RackPosition.FIRST_BRANCH ? first * x : first * FIRST_RAILS + second * x;
    }

    // --- geometry ----------------------------------------------------------------------------------------------------

    private static Direction headingOf(int branch) {
        return branch == RackPosition.FIRST_BRANCH ? FIRST_AISLE : SECOND_AISLE;
    }

    /** The direction a member at this position faces away from its own rails: outwards, across the aisle. */
    private static Direction outward(RackPosition rack) {
        Direction heading = headingOf(rack.branch());
        return rack.side() == Side.LEFT ? heading.getCounterClockWise() : heading.getClockWise();
    }

    private static BlockPos aislePos(BlockPos dock, int branch, int x) {
        return branch == RackPosition.FIRST_BRANCH ? dock.relative(FIRST_AISLE, x)
                : dock.relative(FIRST_AISLE, FIRST_RAILS).relative(SECOND_AISLE, x);
    }

    private static BlockPos rackPos(BlockPos dock, RackPosition rack) {
        return aislePos(dock, rack.branch(), rack.x()).relative(outward(rack)).above(rack.y());
    }

    private static BlockPos chestPos(BlockPos dock, RackPosition rack) {
        return rackPos(dock, rack).relative(outward(rack));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(FIRST_AISLE.getOpposite());
    }

    /** The pull chest the hopper below the output fills, one block further along aisle B. */
    private static BlockPos pullChestPos(BlockPos dock) {
        return rackPos(dock, OUTPUT).below().relative(SECOND_AISLE);
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

    /** Whether the machine stands on the block the two aisles share, under either of its two names. */
    private static boolean onCornerBlock(CranePose pose) {
        double x = pose.branch() == RackPosition.FIRST_BRANCH ? FIRST_RAILS : 0.0;
        return Math.abs(pose.x() - x) < POSITION_EPSILON;
    }

    /** How far through its quarter turn the machine is: 0 as it starts, 1 when it is square with its aisle again. */
    private static double turnProgress(CranePose pose) {
        double aligned = CranePose.yawOf(Headings.of(headingOf(pose.branch())));
        return 1.0 - Math.abs(CranePose.yawDelta(pose.yaw(), aligned));
    }

    // --- the world, read and written --------------------------------------------------------------------------------

    /**
     * Moves one batch from the feed chest onto the belt's tail, from above, whenever that segment is free — the call a
     * chute or a brass funnel over the tail makes. One batch at a time, so the warehouse gets a stream of jobs rather
     * than one big one.
     */
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
        IItemHandler handler = handlerAt(level, pos);
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
