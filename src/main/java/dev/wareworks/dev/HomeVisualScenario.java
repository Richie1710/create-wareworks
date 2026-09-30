package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.HomePointStatus;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.warehouse.LocationKind;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "home": the <b>warehouse home point</b> and a crane <b>returning home</b> (M21, issue #1, ADR-034,
 * {@code docs/stacker-crane.md} §4.7). The companion of {@link CornerVisualScenario}, which shows the machine working
 * across a bend; this one shows where it goes when there is nothing left to do.
 * <p>
 * <b>The build</b>, relative to the dock (the scene origin): the same L as the corner scenario — a creative motor under
 * the dock, the dock facing east, the controller behind it, {@value #FIRST_RAILS} rails east to the corner block and
 * {@value #SECOND_RAILS} more south of it — with an input station and an iron rack on aisle <b>A</b> and, on aisle
 * <b>B</b> beyond the bend, the three blocks this scenario is about: the <b>home point</b> at position
 * {@value #HOME_X}, a <b>warehouse terminal</b> beside it (the reason a player puts a home point exactly there), and,
 * later in the run, a <b>second</b> home point which the warehouse has to refuse.
 * <p>
 * <b>The story of the run</b>, in the order a player would see it:
 * <ol>
 * <li>the L with the home point in it, the lamp already burning, the machine still parked at its dock;</li>
 * <li>a real store job on aisle A, which leaves the machine standing where that job ended — not at its home point;</li>
 * <li>the machine setting off by itself after the idle delay, rolling towards the bend <b>with an empty grabber and
 * no job</b>, and parking in front of the home point beyond it, with the arm in and facing the way aisle B runs;</li>
 * <li>the goggle tooltip that says <b>where the crane parks</b>: the home point's own address and the sentence that the
 * machine really waits here;</li>
 * <li>a <b>second</b> home point, visibly refused — red lamp and crossed brass stop — while the first one keeps
 * serving;</li>
 * <li>a trip home <b>interrupted</b> by a real job, which is served first and finished before the machine goes
 * home again;</li>
 * <li>the home point <b>broken</b>, and the machine driving back to its dock, which is the home of every warehouse
 * without one.</li>
 * </ol>
 * The Flywheel pass tells that whole story; the second pass re-places the home point, lets the machine come back and
 * re-shoots the parked moment with Flywheel off, so the twin comparison of the parked machine still exists.
 * <p>
 * <b>Nothing here is only a picture.</b> Every frozen moment is reached by a <i>server</i> condition that freezes the
 * ticks in the same server tick, and before each group of shots the server asserts exactly what those shots claim: the
 * pose the machine really has, what the controller hands to the dock, what each home point's status and lamps say, and
 * the item census of the whole scene. The claim that no item moves while a machine drives home is checked at every one
 * of those moments.
 */
public final class HomeVisualScenario implements VisualScenario {
    public static final String NAME = "home";
    /** Its own throw-away world: the goggle shots need a non-spectator with the vanilla reach. */
    private static final String WORLD_FOLDER = "wareworks_visual_home";
    /** Two passes, four trips home and a dozen camera moves; the default four minutes are not enough. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    private static final Direction FIRST_AISLE = Direction.EAST;
    private static final Direction SECOND_AISLE = Direction.SOUTH;
    /** Rails east of the dock; the last one is the corner block, which is position 0 of aisle B as well. */
    private static final int FIRST_RAILS = 6;
    /** Rails south of the corner block. */
    private static final int SECOND_RAILS = 5;
    /**
     * Speed of the crane's creative motor, as in {@link CornerVisualScenario}: at {@value} RPM the machine travels
     * {@code 48/384} blocks per tick, which is slow enough to point a camera at and fast enough for a run.
     */
    private static final int MOTOR_RPM = 48;
    /**
     * Ticks the machine waits for work before it drives home ({@code crane.returnHomeIdleTicks}, default 200).
     * <p>
     * Shorter than the shipped default so a run is not spent waiting, but <b>long enough to photograph inside</b>: the
     * "the job is done and the machine has not set off yet" moment lives in this window, and the window has to survive
     * the client tick the harness needs to poll for it.
     */
    private static final int IDLE_DELAY_TICKS = 120;
    /**
     * What a quarter turn costs, in blocks of travel ({@code crane.turnPenaltyBlocks}, default 1).
     * <p>
     * {@value} blocks at {@value #MOTOR_RPM} RPM is a turn of 128 ticks — the slowest this mod allows, and the price
     * of two honest shots: a mid-turn picture of a machine on its way home, and an interruption that provably lands
     * <b>inside</b> the swing and stays there ({@link #assertInterruption}). A turn a machine hurries through is a
     * turn nothing can be proved about.
     */
    private static final double SLOW_TURN_BLOCKS = 16.0;

    /** What the run feeds into the warehouse; every batch goes to the iron rack on aisle A. */
    private static final Item AISLE_A_ITEM = Items.IRON_INGOT;
    private static final int BATCH = 16;
    /** What every rack that is not the run's target holds from the start (part of the baseline census). */
    private static final int RACK_STOCK = 32;

    /** The input station on aisle A. */
    private static final RackPosition INPUT = RackPosition.of(2, 0, Side.LEFT);
    /** The iron rack on aisle A: where every job of this run ends, and the pose the machine rests in afterwards. */
    private static final RackPosition RACK_A = RackPosition.of(4, 0, Side.RIGHT);
    /**
     * Position of the home point on aisle B.
     * <p>
     * Three blocks past the corner on purpose: a round trip from the corner to the home point and back costs
     * {@code 2 * 3 / 0.125 = 48} ticks, which is what makes the interruption window of
     * {@value #INTERRUPT_WINDOW_TICKS} ticks a <b>proof</b> that the machine never reached its home point first.
     */
    private static final int HOME_X = 3;
    private static final RackPosition HOME = new RackPosition(1, HOME_X, 0, Side.RIGHT);
    /**
     * The warehouse terminal beside the home point: the reason a player marks this spot rather than another. It
     * stands on the corner side of the home point, so that the camera looking up the aisle has the home point in
     * front of it and the terminal behind — the other way round, the terminal photographs itself.
     */
    private static final RackPosition TERMINAL = new RackPosition(1, HOME_X - 1, 0, Side.RIGHT);
    /**
     * The <b>second</b> home point, placed in the middle of the run. Behind {@link #HOME} in
     * {@link RackPosition#ORDER} (same branch, larger x), so the warehouse keeps serving the first one and this one is
     * the one that has to be refused — which the run asserts rather than assumes.
     */
    private static final RackPosition SECOND_HOME = new RackPosition(1, SECOND_RAILS, 0, Side.RIGHT);

    /**
     * The racks of the warehouse and the item each one is dedicated to. Only {@link #RACK_A} ever receives anything:
     * every other rack is stocked while the scene is built and filtered to an item the run never feeds, so a warehouse
     * that looks like a warehouse in the shots cannot change where the run's own batches end up.
     * <p>
     * <b>They all stand on aisle A, and aisle B carries the terminal, the home point and nothing else.</b> Two
     * reasons, and both are the scenario's subject: a warehouse whose far aisle is the one a player walks to is
     * exactly why a "wait here" block exists, and every camera of the home point stands <b>in</b> aisle B's rack
     * plane — a rack wall there would photograph itself instead of the block the shot is about. The chests are also
     * spaced so that no two of them ever touch: two chests side by side are one double chest, which would make two
     * storage locations share one inventory.
     */
    private static final List<Rack> RACKS = List.of(
            new Rack(RackPosition.of(1, 0, Side.LEFT), Items.EMERALD),
            new Rack(RackPosition.of(2, 0, Side.RIGHT), Items.REDSTONE),
            new Rack(RackPosition.of(3, 0, Side.LEFT), Items.GOLD_INGOT),
            new Rack(RACK_A, AISLE_A_ITEM),
            new Rack(RackPosition.of(5, 0, Side.LEFT), Items.LAPIS_LAZULI));

    /** A storage location of the warehouse and the item its store filter dedicates it to. */
    private record Rack(RackPosition position, Item item) {
    }

    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 8;
    private static final int CENSUS_MARGIN = 3;
    private static final int SETTLE_TICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int MOMENT_TIMEOUT_TICKS = 3600;
    private static final int MEMBER_TIMEOUT_TICKS = 600;
    /** Player height above the scene between camera moves, so a creative player never lands and stops flying. */
    private static final int FLY_HEIGHT = 12;

    /**
     * How far the machine must still be from the corner when the interrupting work arrives, in blocks.
     * <p>
     * The work goes in while the machine is rolling home along the aisle at its dock, <b>before</b> it reaches the
     * bend — the one part of a trip home this harness can be sure to see (see {@link #moment}). That distance is also
     * what makes the shot's claim provable: the machine's home point lies beyond the corner, so every tick of this
     * distance, of the quarter turn behind it and of the run down the far aisle would have to be spent twice before it
     * could be standing here again ({@link #assertInterruption}).
     */
    private static final double INTERRUPT_BEFORE_THE_CORNER_BLOCKS = 0.5;
    /** How far the client's yaw may sit ahead of the server's at a frozen moment (a tick of turning is ~0.02 here). */
    private static final double CLIENT_YAW_TOLERANCE = 0.4;
    private static final double BLOCK_CENTER = 0.5;
    private static final double POSITION_EPSILON = 1.0e-6;
    /** Polls between two diagnostic lines while a moment has not come yet (one poll per client tick). */
    private static final int WAITING_LOG_INTERVAL_POLLS = 100;

    private static final String INTERRUPT_MOMENT = "interrupted";

    /**
     * The voices of a trip home: the machine driving, and the quarter turn it makes on the way. A return nobody can
     * hear would be a return a player never notices ({@code docs/stacker-crane.md} §8.1).
     */
    private static final List<String> RETURN_SOUNDS =
            List.of("minecraft:block.metal.step", "minecraft:block.iron_trapdoor.open");

    // --- cameras ----------------------------------------------------------------------------------------------------

    /** Straight down over the whole L; the small northward target offset puts north at the top. */
    private static final CameraView TOP = CameraView.of("top", 3.6, 9.5, 2.0, 3.6, 0.0, 1.85);
    /** From outside the bend (north-east), high enough to see both aisles and everything on them. */
    private static final CameraView BEND = CameraView.of("bend", 9.0, 4.6, -2.6, 4.3, 1.2, 2.4);
    /** Down aisle B from beyond its far end, back towards the corner and the dock. */
    private static final CameraView ALONG_B = CameraView.of("aisle-b", 7.6, 2.6, 8.4, 5.9, 1.3, 3.4);
    /** The corner block from outside the bend, above the rack wall: the part that turns is the chassis. */
    private static final CameraView CORNER = CameraView.of("corner", 9.8, 5.0, -2.9, 6.6, 1.9, 0.6);
    /** Straight down on the corner block itself, where a turn is unmistakable. */
    private static final CameraView PIVOT = CameraView.of("pivot", 6.5, 7.0, 0.5, 6.5, 0.0, 0.35);
    /**
     * The home point from straight across aisle B, at the height of a standing player.
     * <p>
     * Its plate — with the lamp on it — faces the rails, so this is the one direction from which a player reads the
     * block, and the same camera answers the whole question twice: before the machine comes, the lamp burns over an
     * empty rail; afterwards the machine stands in front of it, which is exactly what a home point is for and exactly
     * what a player sees.
     */
    private static final CameraView HOME_SIDE = CameraView.of("home", 10.6, 4.2, 4.0, 6.2, 1.6, 3.8);
    /** Straight down over the home point and the rail beside it: where the machine stands, past all occlusion. */
    private static final CameraView HOME_OVER = CameraView.of("home-over", 6.0, 6.5, 3.5, 6.0, 0.0, 3.35);
    /** The second home point from across the aisle, close enough to read its red lamp and its crossed stop. */
    private static final CameraView REFUSED_CLOSE = CameraView.of("refused", 9.8, 2.4, 5.5, 5.9, 1.2, 5.5);
    /** The same block from the far side of the warehouse: does a refusal still read across a room? */
    private static final CameraView REFUSED_FAR = CameraView.of("refused-far", 15.0, 3.6, 5.5, 5.9, 1.3, 5.4);
    /** The dock end: controller, dock and the machine parked at position 0, which is the home of a warehouse
     * without a home point. */
    private static final CameraView DOCK_VIEW = CameraView.of("dock", -3.4, 3.8, -3.4, 0.9, 1.8, 0.4);

    /** Following camera ahead of the machine on the aisle it is named on (as in the corner scenario). */
    private static final String FRONT = "front";
    private static final double FRONT_AHEAD = 5.5;
    private static final double FRONT_ABOVE = 3.2;
    private static final double CARRIAGE_LOOK_HEIGHT = 1.6;

    /** Goggle camera: above the block, looking down on its top face from the side the machine is not on. */
    private static final double GOGGLE_EYE_HEIGHT = 4.0;
    private static final double GOGGLE_EYE_ASIDE = 0.9;
    private static final double GOGGLE_TOP_FACE = 1.0;

    // --- run state (server steps write, the client status line reads) -------------------------------------------------

    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Every item of the scene, kept in step with what the run feeds into the input. */
    private volatile Map<ItemKey, Long> conserved = Map.of();
    /** What was fed into the warehouse over the whole run; all of it belongs in the iron rack on aisle A. */
    private long fedToAisleA;
    /** The moment the run is photographing, for the shot log. */
    private volatile String moment = "none";
    /** Polls spent waiting for the current moment (server thread), so a long wait says what it is waiting for. */
    private int waitingPolls;
    /**
     * What the polls of the current wait have seen the machine doing, so that a moment which never comes says which
     * half of its condition never held instead of timing out with nothing but its own name.
     */
    private int midTurnPolls;
    private int returningPolls;
    private String lastMidTurn = "none";
    /** The machine's own pose at the frozen moment, read on the server and checked against what the client draws. */
    private volatile CranePose poseAtMoment = StackerCraneBlockEntity.HOME_POSE;
    /** The pose the machine had when the interrupting work was put into the input, or {@code null} before that. */
    @Nullable
    private CranePose poseAtInterruption;
    /** The server's own game time in the tick the interrupting work was put in. */
    private long gameTimeAtInterruption;
    /** Server game time the machine last had something to do, or {@code -1} before the first poll that looked. */
    private long lastBusyGameTime = -1L;
    /** The pose the previous poll saw, so that any movement between two polls counts as "it had something to do". */
    @Nullable
    private CranePose poseAtLastPoll;

    /** A state the run waits for: tested on the server, with the whole scene in reach. */
    @FunctionalInterface
    private interface Moment {
        boolean holds(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane);
    }

    /** A camera of a moment: a fixed view, or one computed from the frozen machine when the step starts. */
    private record MomentView(String label, Function<VisualContext, CameraView> view) {
        static MomentView fixed(CameraView view) {
            return new MomentView(view.label(), context -> view);
        }

        static MomentView following(String label, Function<VisualContext, CameraView> view) {
            return new MomentView(label, view);
        }
    }

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
        script.client("home: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "home run"))
                .server("home: set the idle delay and the turn penalty of this run", this::applyConfig)
                .server("home: clear the area and place the creative motor", this::placeMotor)
                .server("home: build the L, the stations, the racks, the terminal and the home point", this::buildScene)
                .serverUntil("home: wait until the warehouse bends and every member joined", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("home: dedicate every rack to one item type", this::setStoreFilters)
                .server("home: check the home point against the warehouse itself", this::assertHomePointJoined)
                .server("home: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("home: the player flies and wears Engineer's Goggles", this::equipPlayer)
                .server("home: take the item census of the whole scene", this::takeCensus);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so every world shot is
        // taken by a player who reaches nothing; the goggle shots switch the real reach on for themselves.
        GoggleShots.reach(script, NAME, 0.0);
        if (pass == VisualPass.FLYWHEEL)
            theWholeStory(script);
        else
            theParkedMachineAgain(script);
        if (pass == VisualPass.values()[VisualPass.values().length - 1])
            script.client("home: check that the trip home was heard", HomeVisualScenario::assertReturnSounds);
    }

    /** The Flywheel pass: the whole feature, from the block being placed to the dock taking over again. */
    private void theWholeStory(VisualScript script) {
        fly(script);
        for (CameraView view : List.of(TOP, BEND, ALONG_B, HOME_SIDE))
            script.shotFrom(view, "placed");

        script.server("home: run the creative motor at " + MOTOR_RPM + " RPM and feed one batch", this::powerOnAndFeed);
        moment(script, "after-job", this::jobDoneAwayFromHome,
                MomentView.following(FRONT, HomeVisualScenario::frontView), MomentView.fixed(BEND));
        moment(script, "going-home", this::drivingHome, true,
                MomentView.following(FRONT, HomeVisualScenario::frontView), MomentView.fixed(CORNER),
                MomentView.fixed(BEND));
        moment(script, "parked", this::parkedAtTheHomePoint, MomentView.fixed(HOME_SIDE), MomentView.fixed(ALONG_B),
                MomentView.fixed(HOME_OVER), MomentView.fixed(TOP));
        homePointGoggleShot(script);
        theSecondOneIsRefused(script);
        theWorkComesFirst(script);
        theDockTakesOver(script);
        script.client("home: every check passed",
                context -> LOGGER.info(PREFIX + "home: ALL CHECKS PASSED (the machine drives home round the corner by "
                        + "itself, parks in front of the home point with the arm in, says so in its goggles, refuses a "
                        + "second home point visibly, serves a real job first even mid-turn, and falls back to its dock "
                        + "when the block is broken — with the item census exact at every moment)"));
    }

    /**
     * The second pass: the home point is put back, the machine comes home again, and the parked moment is re-shot with
     * Flywheel off — the twin every crane shot of this project has.
     */
    private void theParkedMachineAgain(VisualScript script) {
        script.server("home: place the home point again", (server, context) -> placeHomePoint(server, context, HOME))
                .serverUntil("home: wait until the warehouse serves it again",
                        (server, context) -> statusAt(server, context, HOME) == HomePointStatus.SERVING,
                        MEMBER_TIMEOUT_TICKS);
        moment(script, "parked", this::parkedAtTheHomePoint, MomentView.fixed(HOME_SIDE), MomentView.fixed(ALONG_B),
                MomentView.fixed(HOME_OVER), MomentView.fixed(BEND));
    }

    @Override
    public String status(VisualContext context) {
        String state = clientCrane(context).map(crane -> {
            CraneState<ItemKey, RackPosition> craneState = crane.craneState();
            CranePose pose = craneState.pose();
            return String.format(Locale.ROOT,
                    "branch=%d posX=%.2f posY=%.2f yaw=%.2f turn=%.2f arm=%.2f side=%s phase=%s job=%s held=%d "
                            + "target=%d/%.2f",
                    pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                    craneState.phase(), craneState.job().isPresent(), crane.goggleInfo().heldCount(),
                    craneState.target().branch(), craneState.target().x());
        }).orElse("crane=missing");
        return "moment=" + moment + " " + state + " home=" + clientHomeStatus(context, HOME)
                + " second=" + clientHomeStatus(context, SECOND_HOME) + GoggleShots.describeHover(context);
    }

    // --- building (server thread) ---------------------------------------------------------------------------------

    /**
     * The two settings this run needs, written into the loaded config in memory only ({@code ConfigValue#set} plus
     * {@code clearCache}): no file is written and no config event fires, so {@code wareworks-server.toml} is left
     * exactly as the player has it. They stay set for the whole run, which then exits.
     */
    private void applyConfig(MinecraftServer server, VisualContext context) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so overriding it would be a silent no-op");
        WareworksConfig.SERVER.returnHomeIdleTicks.set(IDLE_DELAY_TICKS);
        WareworksConfig.SERVER.returnHomeIdleTicks.clearCache();
        WareworksConfig.SERVER.turnPenaltyBlocks.set(SLOW_TURN_BLOCKS);
        WareworksConfig.SERVER.turnPenaltyBlocks.clearCache();
        if (WareworksConfig.returnHomeIdleTicks() != IDLE_DELAY_TICKS
                || WareworksConfig.turnPenaltyBlocks() != SLOW_TURN_BLOCKS)
            throw new VisualTestException("the run's settings did not take: idle delay "
                    + WareworksConfig.returnHomeIdleTicks() + ", turn penalty "
                    + WareworksConfig.turnPenaltyBlocks());
        LOGGER.info(PREFIX + "home: idle delay {} ticks, one quarter turn {} blocks of travel", IDLE_DELAY_TICKS,
                SLOW_TURN_BLOCKS);
    }

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(BlockPos.ZERO))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-3, -1, -3),
                dock.offset(FIRST_RAILS + 3, CLEAR_HEIGHT, SECOND_RAILS + 3)).inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
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
        for (Rack rack : RACKS) {
            placeStorage(level, dock, rack.position());
            // Every rack but the run's own target starts stocked, so the warehouse in the shots holds something.
            if (rack.item() != AISLE_A_ITEM)
                insertAll(level, chestPos(dock, rack.position()), new ItemStack(rack.item(), RACK_STOCK));
        }
        // The terminal is a station like any other: it faces the aisle it stands beside.
        level.setBlockAndUpdate(rackPos(dock, TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, outward(TERMINAL).getOpposite()));
        placeHomePoint(level, dock, HOME);
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

    /** A home point whose plate faces the rails, i.e. placed exactly as a player standing in the aisle places it. */
    private static void placeHomePoint(ServerLevel level, BlockPos dock, RackPosition rack) {
        level.setBlockAndUpdate(rackPos(dock, rack), WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                .setValue(WarehouseHomePointBlock.FACING, outward(rack).getOpposite()));
    }

    private void placeHomePoint(MinecraftServer server, VisualContext context, RackPosition rack) {
        placeHomePoint(server.overworld(), context.origin(), rack);
        LOGGER.info(PREFIX + "home: a home point was placed at {} ({})", rack, rackPos(context.origin(), rack));
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
                && memberAt(controller, dock, TERMINAL).isPresent()
                && memberAt(controller, dock, HOME).map(LocationRecord::kind).orElse(null) == LocationKind.HOME
                // The controller decides which home point it serves on its own re-link cadence
                // (geometryRefreshTicks), so the scene is only ready once that answer exists: everything the first
                // shots claim about the home point is read from it.
                && controller.homePoint().isPresent()
                && crane.isControllerLinked() && crane.networkGeometry().branchCount() == 2;
    }

    /**
     * Sets a plain store filter on every rack, exactly as a right-click with that item would. Only one of them takes
     * the item this run feeds, so nothing the shots claim about where a batch went depends on the planner's ranking.
     */
    private void setStoreFilters(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (Rack rack : RACKS) {
            WarehouseInterfaceBlockEntity storage = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                    .getNullable(level, rackPos(dock, rack.position()));
            if (storage == null)
                throw new VisualTestException("no warehouse interface at " + rack.position());
            if (!storage.setStoreFilter(new ItemStack(rack.item())))
                throw new VisualTestException("the interface at " + rack.position() + " refused the filter "
                        + rack.item());
        }
    }

    /**
     * What the first shots claim about the home point, checked against the warehouse itself: it is a member of the
     * aisle beyond the bend, the warehouse really uses it, the dock was told about it, and its lamp burns.
     */
    private void assertHomePointJoined(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        RackPosition rack = memberPosition(controller, dock, HOME);
        if (rack.branch() == RackPosition.FIRST_BRANCH)
            throw new VisualTestException("this scenario is about a home point beyond the bend, but the warehouse "
                    + "names it " + rack);
        assertServingHomePoint(level, dock, rack);
        LOGGER.info(PREFIX + "home: CHECK the home point at {} joined the warehouse as {} and the crane's home is now "
                + "{} instead of its dock", rackPos(dock, HOME), address(controller, rack), rack);
    }

    /** The whole chain behind one lit lamp: controller, dock, block entity and both block state lamps. */
    private void assertServingHomePoint(ServerLevel level, BlockPos dock, RackPosition rack) {
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (!controller.homePoint().equals(Optional.of(rack)))
            throw new VisualTestException("the warehouse serves the home point " + controller.homePoint()
                    + " instead of " + rack);
        if (!dock(level, dock).homePoint().equals(Optional.of(rack)))
            throw new VisualTestException("the dock was not told about the home point: "
                    + dock(level, dock).homePoint());
        assertStatus(level, dock, HOME, HomePointStatus.SERVING);
    }

    /** The status of one home point, on the block itself and on both of its lamps. */
    private void assertStatus(ServerLevel level, BlockPos dock, RackPosition rack, HomePointStatus expected) {
        BlockPos pos = rackPos(dock, rack);
        WarehouseHomePointBlockEntity block = WareworksBlockEntityTypes.WAREHOUSE_HOME_POINT.getNullable(level, pos);
        if (block == null)
            throw new VisualTestException("no home point at " + pos);
        if (block.status() != expected)
            throw new VisualTestException("the home point at " + pos + " says " + block.status() + " instead of "
                    + expected);
        boolean lit = level.getBlockState(pos).getValue(WarehouseHomePointBlock.LIT);
        boolean refused = level.getBlockState(pos).getValue(WarehouseHomePointBlock.REFUSED);
        if (lit != expected.isLit() || refused != expected.isRefused())
            throw new VisualTestException("the lamps of the home point at " + pos + " say lit=" + lit + " refused="
                    + refused + ", but " + expected + " means lit=" + expected.isLit() + " refused="
                    + expected.isRefused());
    }

    private void takeCensus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box " + censusBox + " is not fully loaded");
        conserved = SceneItemCensus.take(level, censusBox);
        LOGGER.info(PREFIX + "home: baseline census {} in {}", SceneItemCensus.describe(conserved), censusBox);
    }

    /**
     * Rotation, and one batch of iron in the same step. The order matters: an idle machine with rotation and nothing to
     * do would drive home after the idle delay, and the first thing this run shows is a machine doing <b>work</b>.
     */
    private void powerOnAndFeed(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin().below()).generatedSpeed.setValue(MOTOR_RPM);
        feedAisleA(server.overworld(), context.origin());
    }

    /** Puts one batch into the input station, exactly as a belt or a funnel would hand it over. */
    private void feedAisleA(ServerLevel level, BlockPos dock) {
        ItemStack batch = new ItemStack(AISLE_A_ITEM, BATCH);
        insertAll(level, rackPos(dock, INPUT), batch);
        fedToAisleA += BATCH;
        conserved = SceneItemCensus.plusAll(conserved, List.of(batch));
        LOGGER.info(PREFIX + "home: {} {} went into the input on aisle A ({} in total)", BATCH, AISLE_A_ITEM,
                fedToAisleA);
    }

    // --- the moments -------------------------------------------------------------------------------------------------

    /**
     * One frozen moment: the <b>server</b> waits until the machine really is in this state and freezes the ticks in the
     * same server tick, then the client is given time to see the freeze, both poses are checked against what the shots
     * will claim, the census is taken again, and every view is photographed.
     * <p>
     * A moment that only exists <b>while the machine drives home</b> is asked for with {@code keepBusy}: see
     * {@link #keepTheWarehouseBusy}.
     * <p>
     * <b>What this harness can and cannot catch, measured.</b> These conditions are polled a few times a second from
     * the client thread, not once per server tick, so only a state that lasts is reliably seen: the machine rolling
     * along an aisle, or standing still. A single quarter turn is not — run after run, not one poll landed inside the
     * turn of a trip <b>home</b>, while the turns the same machine makes on its way to work were sampled freely.
     * Freezing the world and stepping it forward tick by tick ({@code ServerTickRateManager#stepGameIfPaused}) does
     * not help either, and that is worth writing down: a frozen world has <b>no rotation</b>, so the machine pauses
     * with 0 RPM and 400 stepped ticks move it nothing. Every moment here is therefore a state the run can wait for
     * while the world runs, and the claims that need a particular tick are made in numbers on the server rather than
     * in a pose in a picture.
     */
    private void moment(VisualScript script, String label, Moment test, MomentView... views) {
        moment(script, label, test, false, views);
    }

    private void moment(VisualScript script, String label, Moment test, boolean keepBusy, MomentView... views) {
        script.server("home: the run is looking for the moment '" + label + "'",
                        (server, context) -> startWaitingFor(label))
                .serverUntil("home: wait for the moment '" + label + "'",
                        (server, context) -> freezeAtMoment(server, context, test, keepBusy), MOMENT_TIMEOUT_TICKS)
                .until("home: wait until the client sees the frozen moment '" + label + "'",
                        HomeVisualScenario::clientFrozen, SYNC_TIMEOUT_TICKS)
                .server("home: check the machine's own state at '" + label + "'",
                        (server, context) -> assertServerMoment(server, context, label, test))
                .client("home: check the pose the client draws at '" + label + "'",
                        context -> assertClientMoment(context, label));
        fly(script);
        for (MomentView view : views)
            script.camera(view.label(), view.view()).shot(label + "-" + view.label());
        script.freeze(false);
    }

    /**
     * One poll of a moment: when the machine is in the state the shot is about, stop the world right there. Freezing
     * from the server condition itself is what makes the shots honest — a client that asked for a freeze would be two
     * round trips late, and a mid-turn moment would be over.
     */
    /** A new moment: its name for the shot log, and a fresh tally of what its polls see. */
    private void startWaitingFor(String label) {
        moment = label;
        waitingPolls = 0;
        midTurnPolls = 0;
        returningPolls = 0;
        lastMidTurn = "none";
    }

    private boolean freezeAtMoment(MinecraftServer server, VisualContext context, Moment test, boolean keepBusy) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = dock(level, dock);
        if (!test.holds(level, dock, crane)) {
            if (keepBusy)
                keepTheWarehouseBusy(level, dock, crane);
            logWhileWaiting(level, dock, crane);
            return false;
        }
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /**
     * A trip home happens once and then the machine stands still, so a moment that only exists <b>during</b> such a
     * trip has exactly one chance — and one chance is not enough here.
     * <p>
     * <b>Why.</b> While the ticks are frozen for a group of shots, the integrated server builds up the tick debt of
     * however long that group took, and it pays that debt off in one burst the moment the world is unfrozen: dozens of
     * server ticks between two polls of this condition, measured. A whole quarter turn can go by inside such a burst,
     * so no window is narrow enough to be missed and none is wide enough to be safe.
     * <p>
     * <b>What this does about it.</b> When the machine has been standing still with nothing to do for longer than it
     * would have taken to set off ({@code 2 ×} the idle delay), the warehouse gets <b>another batch</b>: the machine
     * drives out to the rack on aisle A, stores it, waits, and comes home again — this time with no freeze before it,
     * so the trip is sampled in real time. A missed window costs a work cycle instead of the run.
     * <p>
     * <b>Two traps, both paid for in failed runs.</b> Feeding the moment the machine goes idle takes the idle delay
     * away from it, and it then never drives home at all. And asking {@code CraneState#isMoving()} — whether the pose
     * changed in the <i>last</i> tick — is no use here either: these polls are sampled, not per-tick, so a whole trip
     * home can happen between two of them, the counter never notices, and the batch lands in the middle of the very
     * turn the run is waiting for, which cuts it short. What counts as "it had something to do" is therefore two
     * <b>stable</b> facts: the machine has not reached its resting target, or its pose is not the one the previous
     * poll saw.
     */
    private void keepTheWarehouseBusy(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                rackPos(dock, INPUT));
        long now = level.getGameTime();
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        boolean movedSinceLastPoll = poseAtLastPoll != null && !poseAtLastPoll.equals(state.pose());
        poseAtLastPoll = state.pose();
        boolean hasSomethingToDo = input == null || !input.bufferedItems().isEmpty()
                || crane.currentJob().isPresent() || !crane.heldItems().isEmpty() || movedSinceLastPoll
                || !CraneMotion.isAt(state.pose(), state.target())
                || countAt(level, chestPos(dock, RACK_A), AISLE_A_ITEM) < fedToAisleA;
        if (hasSomethingToDo || lastBusyGameTime < 0) {
            lastBusyGameTime = now;
            return;
        }
        long standingStill = now - lastBusyGameTime;
        if (standingStill < 2L * IDLE_DELAY_TICKS)
            return; // it may still be about to set off, or be on its way
        lastBusyGameTime = now;
        LOGGER.info(PREFIX + "home: the machine has been standing still for {} ticks while the run waited for '{}', "
                + "so the warehouse gets another batch and the trip home comes round again", standingStill, moment);
        feedAisleA(level, dock);
    }

    /**
     * One line every {@value #WAITING_LOG_INTERVAL_POLLS} polls while a moment has not come yet, and a tally of what
     * every poll in between saw: how many of them found the machine <b>in a turn</b> and how many found it on its way
     * <b>home</b>, plus the last mid-turn pose in full. A moment that never comes then says which half of its
     * condition never held.
     */
    private void logWhileWaiting(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        boolean returning = isReturning(crane);
        if (returning)
            returningPolls++;
        if (!state.pose().isAligned()) {
            midTurnPolls++;
            lastMidTurn = describe(state, crane.goggleInfo().heldCount()) + " returning=" + returning;
        }
        if (++waitingPolls % WAITING_LOG_INTERVAL_POLLS != 0)
            return;
        LOGGER.info(PREFIX + "home: still waiting for '{}' after {} polls ({} of them with the machine in a turn, {} "
                + "of them with it on its way home): {} | home={} stored={} reason={} | last mid-turn poll: {}",
                moment, waitingPolls, midTurnPolls, returningPolls,
                describe(state, crane.goggleInfo().heldCount()), crane.homePoint(),
                countAt(level, chestPos(dock, RACK_A), AISLE_A_ITEM), controller(level, dock).lastPlanReason(),
                lastMidTurn);
    }

    private void assertServerMoment(MinecraftServer server, VisualContext context, String label, Moment test) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = dock(level, dock);
        if (!test.holds(level, dock, crane))
            throw new VisualTestException("the machine left the moment '" + label + "' before the world was frozen: "
                    + describe(crane.craneState(), crane.goggleInfo().heldCount()));
        poseAtMoment = crane.craneState().pose();
        SceneItemCensus.assertEquals(level, censusBox, conserved, "at the moment '" + label + "'");
        LOGGER.info(PREFIX + "home: CHECK moment '{}' on the server: {}", label,
                describe(crane.craneState(), crane.goggleInfo().heldCount()));
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
        if (pose.branch() != onTheServer.branch())
            throw new VisualTestException("the client draws the machine on aisle " + pose.branch()
                    + " while it stands on aisle " + onTheServer.branch() + " at the moment '" + label + "'");
        double yawDrift = Math.abs(CranePose.yawDelta(onTheServer.yaw(), pose.yaw()));
        if (yawDrift > CLIENT_YAW_TOLERANCE)
            throw new VisualTestException("the client draws the machine " + yawDrift + " quarter turns away from the "
                    + "yaw it really has at the moment '" + label + "'");
        LOGGER.info(PREFIX + "home: CHECK moment '{}' on the client: {} (yaw drift {})", label,
                describe(state, crane.goggleInfo().heldCount()), String.format(Locale.ROOT, "%.3f", yawDrift));
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    // --- what each moment means ---------------------------------------------------------------------------------------

    /**
     * The job is done, the batch really lies in the rack, and the machine <b>has not set off yet</b>: it stands where
     * that job left it, on the aisle at the dock, with its resting target still on that aisle. This is the "before"
     * of the whole feature.
     */
    private boolean jobDoneAwayFromHome(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        return countAt(level, chestPos(dock, RACK_A), AISLE_A_ITEM) >= fedToAisleA
                && state.phase() == CranePhase.IDLE && state.job().isEmpty() && crane.heldItems().isEmpty()
                && !state.isMoving() && state.pose().branch() == RackPosition.FIRST_BRANCH
                && state.pose().x() > 0.0 && state.target().branch() == RackPosition.FIRST_BRANCH;
    }

    /**
     * Rolling home: square with the aisle it is on, moving, with nothing in the grabber, no job and the home point as
     * its target. Either aisle counts — the machine crosses both on the way — because the shot is about a machine that
     * sets off by itself, not about where on the L it is when it does.
     */
    private boolean drivingHome(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        return isReturning(crane) && state.pose().isAligned() && state.isMoving();
    }

    /**
     * Parked: the aisle beyond the bend, the home point's own position and level, the arm in, facing the way that aisle
     * runs, idle and with nothing held — everything a crane that has arrived is, and the pose the next job starts from
     * without an extra turn.
     */
    private boolean parkedAtTheHomePoint(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        RackPosition home = controller(level, dock).homePoint().orElse(null);
        if (home == null)
            return false;
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        return state.phase() == CranePhase.IDLE && state.job().isEmpty() && crane.heldItems().isEmpty()
                && !state.isMoving() && pose.branch() == home.branch()
                && Math.abs(pose.x() - home.x()) < POSITION_EPSILON
                && Math.abs(pose.y() - home.y()) < POSITION_EPSILON && pose.arm() == CranePose.RETRACTED
                && pose.yaw() == CranePose.yawOf(Headings.of(headingOf(home.branch())));
    }

    /** At position 0 of the aisle at the dock: the home of every warehouse without a home point. */
    private boolean parkedAtTheDock(ServerLevel level, BlockPos dock, StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        return controller(level, dock).homePoint().isEmpty() && state.phase() == CranePhase.IDLE
                && state.job().isEmpty() && !state.isMoving() && pose.branch() == RackPosition.FIRST_BRANCH
                && Math.abs(pose.x()) < POSITION_EPSILON && Math.abs(pose.y()) < POSITION_EPSILON
                && pose.arm() == CranePose.RETRACTED
                && pose.yaw() == CranePose.yawOf(Headings.of(FIRST_AISLE));
    }

    /**
     * Whether the machine is on its way home rather than working: idle, no job, nothing in the grabber, and a resting
     * target on the aisle its home point stands on. A return is nothing but the resting target of
     * {@link CranePhase#IDLE} (ADR-034), so this is exactly what "driving home" is.
     */
    private boolean isReturning(StackerCraneBlockEntity crane) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        return state.phase() == CranePhase.IDLE && state.job().isEmpty() && crane.heldItems().isEmpty()
                && crane.homePoint().isPresent()
                && state.target().branch() == crane.homePoint().orElseThrow().branch();
    }

    // --- the goggle line that says where the crane parks -----------------------------------------------------------

    /**
     * The home point's own tooltip, shot while the machine really stands in front of it: its <b>address</b> — which
     * names the aisle and the position beyond the bend — and the sentence that the crane waits here. This is the line a
     * player reads to find out where their machine parks, so the shot asserts both, plus the fact that the machine is
     * parked there at the moment the frame is taken.
     */
    private void homePointGoggleShot(VisualScript script) {
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, goggleView("goggles-home-point", HOME), "goggles-home-point",
                dock -> rackPos(dock, HOME), context -> homeSummarySynced(context, HOME, HomePointStatus.SERVING),
                context -> checkHomePointGoggles(context, HomePointStatus.SERVING));
        script.server("home: the machine is parked at the home point while its tooltip is shown",
                (server, context) -> assertParked(server, context, "while its tooltip is shown"));
        GoggleShots.reach(script, NAME, 0.0);
    }

    /** The goggle summary of a home point has reached the client with the status this shot is about. */
    private static boolean homeSummarySynced(VisualContext context, RackPosition rack, HomePointStatus status) {
        return clientHomePoint(context, rack)
                .filter(block -> block.summary().status() == status
                        && block.summary().assignment().address().isPresent())
                .isPresent();
    }

    private static void checkHomePointGoggles(VisualContext context, HomePointStatus status) {
        BlockPos pos = rackPos(context.origin(), status == HomePointStatus.SERVING ? HOME : SECOND_HOME);
        WarehouseHomePointBlockEntity block = clientHomePoint(context,
                status == HomePointStatus.SERVING ? HOME : SECOND_HOME)
                .orElseThrow(() -> new VisualTestException("the client has no home point at " + pos));
        StorageAddress address = block.summary().assignment().address()
                .orElseThrow(() -> new VisualTestException("the home point at " + pos + " shows no address"));
        List<String> lines = GoggleShots.lines(context, pos);
        GoggleShots.requireLine(lines,
                WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_HOME_POINT).component().getString());
        GoggleShots.requireLine(lines, address.format());
        GoggleShots.requireLine(lines, WareworksLang.translate(status.langKey()).component().getString());
        LOGGER.info(PREFIX + "home: CHECK the goggles at {} say '{}' for {} and name the address {}", pos,
                WareworksLang.translate(status.langKey()).component().getString(), status, address.format());
    }

    // --- the second home point -------------------------------------------------------------------------------------

    /**
     * A warehouse has one crane, so it has one home. A second home point is placed while the first one is being used,
     * and the run proves that it is <b>visibly</b> refused rather than quietly ignored: the first one keeps serving and
     * keeps the machine, the second one burns its red lamp, wears its crossed brass stop and says which of the two it
     * is in its own tooltip. The far shot exists for the question a screenshot can answer and a unit test cannot:
     * whether a refusal reads across a room.
     */
    private void theSecondOneIsRefused(VisualScript script) {
        script.server("home: a player places a second home point on the same aisle",
                        (server, context) -> placeHomePoint(server, context, SECOND_HOME))
                .serverUntil("home: wait until the warehouse has judged it",
                        (server, context) -> statusAt(server, context, SECOND_HOME) != HomePointStatus.NO_WAREHOUSE,
                        MEMBER_TIMEOUT_TICKS)
                .server("home: check that the first one is kept and the second one refused", this::assertSecondRefused);
        fly(script);
        script.shotFrom(REFUSED_CLOSE, "second").shotFrom(REFUSED_FAR, "second");

        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, goggleView("goggles-second", SECOND_HOME), "goggles-second",
                dock -> rackPos(dock, SECOND_HOME),
                context -> homeSummarySynced(context, SECOND_HOME, HomePointStatus.SECOND),
                context -> checkHomePointGoggles(context, HomePointStatus.SECOND));
        GoggleShots.reach(script, NAME, 0.0);

        script.server("home: take the second home point away again",
                        (server, context) -> breakBlock(server, context, SECOND_HOME))
                .serverUntil("home: wait until only one home point is left",
                        (server, context) -> memberAt(controller(server.overworld(), context.origin()),
                                context.origin(), SECOND_HOME).isEmpty(), MEMBER_TIMEOUT_TICKS)
                .server("home: the first home point is untouched by all of it",
                        (server, context) -> assertParked(server, context, "after the second one was removed"));
    }

    private void assertSecondRefused(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        RackPosition first = memberPosition(controller, dock, HOME);
        RackPosition second = memberPosition(controller, dock, SECOND_HOME);
        // Which of two home points comes first is RackPosition.ORDER's answer, so the run checks that the block it
        // photographs as "the refused one" really is the second one, instead of assuming it.
        if (RackPosition.ORDER.compare(first, second) >= 0)
            throw new VisualTestException("this scenario photographs " + second + " as the refused home point, but "
                    + "RackPosition.ORDER puts it before " + first);
        assertServingHomePoint(level, dock, first);
        assertStatus(level, dock, SECOND_HOME, HomePointStatus.SECOND);
        if (!parkedAtTheHomePoint(level, dock, dock(level, dock)))
            throw new VisualTestException("the machine left the first home point when the second one appeared: "
                    + describe(dock(level, dock).craneState(), dock(level, dock).goggleInfo().heldCount()));
        LOGGER.info(PREFIX + "home: CHECK the warehouse keeps the first home point {} and refuses the second one {} "
                + "visibly (red lamp, no green lamp), and the machine stays where it was", first, second);
    }

    // --- the work comes first --------------------------------------------------------------------------------------

    /**
     * A return home is <b>interrupted by a real job</b>, and the work is served first.
     * <p>
     * The run gives the machine a job that takes it back to the aisle at the dock, lets it finish, and waits until it
     * sets off home again. While it is rolling along that aisle, still short of the bend, the next batch goes into the
     * input <b>in that very server tick</b> - and the moment is frozen as soon as the machine has taken that job.
     * Afterwards the batch really is in the rack, and only then does the machine go home.
     * <p>
     * Why here and not in the quarter turn itself: this harness samples the world a few times a second rather than
     * every tick, and the stretch before the bend is the part of a trip home it can be relied on to see (see
     * {@link #moment}). What the shot claims is not weakened by it — the machine is caught on the near side of a
     * corner its home point lies beyond, which is a stronger statement about where it did <b>not</b> get to than any
     * pose inside the turn would be.
     */
    private void theWorkComesFirst(VisualScript script) {
        script.server("home: work arrives for the aisle at the dock", (server, context) -> {
            poseAtInterruption = null;
            feedAisleA(server.overworld(), context.origin());
        });
        script.serverUntil("home: wait until that batch is stored", this::batchStored, MOMENT_TIMEOUT_TICKS);

        script.server("home: the run is looking for the moment '" + INTERRUPT_MOMENT + "'",
                        (server, context) -> startWaitingFor(INTERRUPT_MOMENT))
                .serverUntil("home: put work in while the machine rolls home and wait until it takes it",
                        this::freezeAtInterruption, MOMENT_TIMEOUT_TICKS)
                .until("home: wait until the client sees the frozen moment", HomeVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("home: check the interruption on the server", this::assertInterruption)
                .client("home: check the pose the client draws at the interruption",
                        context -> assertClientMoment(context, INTERRUPT_MOMENT));
        fly(script);
        script.camera(FRONT, HomeVisualScenario::frontView).shot(INTERRUPT_MOMENT + "-" + FRONT)
                .camera(BEND).shot(INTERRUPT_MOMENT + "-" + BEND.label())
                .freeze(false);

        script.serverUntil("home: wait until the interrupting batch is stored too", this::batchStored,
                        MOMENT_TIMEOUT_TICKS)
                .server("home: the work was done first", this::assertWorkCameFirst);
        moment(script, "then-home", this::parkedAtTheHomePoint, MomentView.fixed(HOME_SIDE));
    }

    private boolean batchStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return countAt(level, chestPos(dock, RACK_A), AISLE_A_ITEM) >= fedToAisleA
                && dock(level, dock).currentJob().isEmpty() && dock(level, dock).heldItems().isEmpty();
    }

    /**
     * Two stages in one condition. First: wait until the machine is rolling home along the aisle at its dock and is
     * still at least {@value #INTERRUPT_BEFORE_THE_CORNER_BLOCKS} blocks short of the bend, and put the work in
     * <b>in that very server tick</b>, remembering the pose it had and the server's own game time. Then: freeze as
     * soon as it has taken that job, as long as it is still on this side of the corner.
     */
    private boolean freezeAtInterruption(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = dock(level, dock);
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        if (poseAtInterruption == null) {
            if (!drivingHome(level, dock, crane) || state.pose().branch() != RackPosition.FIRST_BRANCH
                    || state.pose().x() > FIRST_RAILS - INTERRUPT_BEFORE_THE_CORNER_BLOCKS) {
                keepTheWarehouseBusy(level, dock, crane);
                logWhileWaiting(level, dock, crane);
                return false;
            }
            poseAtInterruption = state.pose();
            gameTimeAtInterruption = level.getGameTime();
            feedAisleA(level, dock);
            LOGGER.info(PREFIX + "home: the work arrives while the machine is rolling home, {} blocks short of the "
                    + "bend: {}", String.format(Locale.ROOT, "%.2f", FIRST_RAILS - state.pose().x()),
                    describe(state, 0));
            return false;
        }
        boolean shortOfTheCorner = state.pose().branch() == RackPosition.FIRST_BRANCH
                && state.pose().x() <= FIRST_RAILS;
        boolean stillProvable = level.getGameTime() - gameTimeAtInterruption
                < ticksToHaveBeenAnywhere(poseAtInterruption);
        if (state.job().isPresent() && shortOfTheCorner && stillProvable) {
            server.tickRateManager().setFrozen(true);
            return true;
        }
        if (!shortOfTheCorner || !stillProvable) {
            LOGGER.info(PREFIX + "home: the machine was past the bend, or took too long to take the work, {} ticks "
                    + "after the batch went in ({}); that interruption does not count and the run waits for the next "
                    + "trip home", level.getGameTime() - gameTimeAtInterruption, describe(state, 0));
            poseAtInterruption = null;
        }
        return false;
    }

    /**
     * What the interruption shot claims, and why it is a <b>proof</b> rather than a likely story.
     * <p>
     * The work went in while the machine was rolling home along the aisle at its dock, a measured distance short of
     * the bend, and it is still on that side of the bend now, with the job in hand. Its home point lies beyond the
     * corner: to have been there it would have had to cover that distance, turn the quarter turn
     * ({@value #SLOW_TURN_BLOCKS} blocks of travel), run down the far aisle and come all the way back — a number of
     * ticks this run computes from the speeds it configured and compares against the ticks that really passed on the
     * server.
     */
    private void assertInterruption(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = dock(level, dock);
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose started = poseAtInterruption;
        if (started == null)
            throw new VisualTestException("the interruption was never put in");
        if (state.job().isEmpty())
            throw new VisualTestException("the machine has no job at the interruption: "
                    + describe(state, crane.goggleInfo().heldCount()));
        if (state.pose().branch() != RackPosition.FIRST_BRANCH)
            throw new VisualTestException("the machine crossed the bend before the world was frozen: "
                    + describe(state, crane.goggleInfo().heldCount()));
        if (started.branch() != RackPosition.FIRST_BRANCH
                || started.x() > FIRST_RAILS - INTERRUPT_BEFORE_THE_CORNER_BLOCKS)
            throw new VisualTestException("the work did not go in on the near side of the bend: " + started);
        long elapsed = level.getGameTime() - gameTimeAtInterruption;
        double ticksToHaveBeenAnywhere = ticksToHaveBeenAnywhere(started);
        if (elapsed >= ticksToHaveBeenAnywhere)
            throw new VisualTestException("the machine took " + elapsed + " ticks to take the work, which is longer "
                    + "than the " + Math.round(ticksToHaveBeenAnywhere) + " ticks it would have needed to reach its "
                    + "home point and come back, so this shot could no longer claim it never got there");
        poseAtMoment = state.pose();
        SceneItemCensus.assertEquals(level, censusBox, conserved, "at the interruption");
        LOGGER.info(PREFIX + "home: CHECK the machine took the job {} server tick(s) after the work arrived and is "
                + "still on the near side of the bend (x {} then, {} now). Reaching its home point {} blocks beyond "
                + "the corner and coming back would have cost at least {} ticks, so the trip home really was cut "
                + "short by the work", elapsed, String.format(Locale.ROOT, "%.3f", started.x()),
                String.format(Locale.ROOT, "%.3f", state.pose().x()), HOME_X,
                Math.round(ticksToHaveBeenAnywhere));
    }

    /**
     * Ticks the machine would have needed, from the pose it had when the work arrived, to reach its home point and be
     * standing here again: the run to the bend and back, two quarter turns, and the far aisle twice. It is the number
     * that turns "it is still on this side of the bend" into "it cannot have been at its home point".
     */
    private static double ticksToHaveBeenAnywhere(CranePose started) {
        double blocksPerTick = travelBlocksPerTick();
        double toTheCornerAndBack = 2.0 * (FIRST_RAILS - started.x());
        double alongTheFarAisleAndBack = 2.0 * HOME_X;
        double twoQuarterTurns = 2.0 * SLOW_TURN_BLOCKS;
        return (toTheCornerAndBack + alongTheFarAisleAndBack + twoQuarterTurns) / blocksPerTick;
    }

    /** How far the machine travels in one tick at this run's motor speed ({@code crane.*} travel settings). */
    private static double travelBlocksPerTick() {
        double speed = Math.min(WareworksConfig.maxBlocksPerTick(),
                WareworksConfig.travelBlocksPerTickPerRpm() * MOTOR_RPM);
        if (speed <= 0.0)
            throw new VisualTestException("the machine cannot travel at all: " + speed + " blocks per tick");
        return speed;
    }

    private void assertWorkCameFirst(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long stored = countAt(level, chestPos(dock, RACK_A), AISLE_A_ITEM);
        if (stored != fedToAisleA)
            throw new VisualTestException("the rack on aisle A holds " + stored + " " + AISLE_A_ITEM + ", expected "
                    + fedToAisleA);
        SceneItemCensus.assertEquals(level, censusBox, conserved, "after the interrupting job was done");
        LOGGER.info(PREFIX + "home: CHECK the interrupted trip cost the warehouse nothing: all {} {} are in the rack "
                + "the job named", stored, AISLE_A_ITEM);
    }

    // --- the dock takes over again ---------------------------------------------------------------------------------

    /**
     * Breaking the home point falls back to the dock, which is the home a crane has always had — and the machine
     * really drives back there, round the bend, all by itself. Nothing else has to happen: "no home point" and "the
     * dock is home" are the same statement.
     */
    private void theDockTakesOver(VisualScript script) {
        script.server("home: a player breaks the home point", (server, context) -> breakBlock(server, context, HOME))
                .serverUntil("home: wait until the warehouse and the dock forgot it", (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos dock = context.origin();
                    return controller(level, dock).homePoint().isEmpty() && dock(level, dock).homePoint().isEmpty();
                }, MEMBER_TIMEOUT_TICKS)
                .server("home: the home point is really gone", (server, context) -> {
                    BlockPos pos = rackPos(context.origin(), HOME);
                    if (WareworksBlockEntityTypes.WAREHOUSE_HOME_POINT.getNullable(server.overworld(), pos) != null)
                        throw new VisualTestException("the home point at " + pos + " is still there");
                    LOGGER.info(PREFIX + "home: CHECK the warehouse fell back to its dock the moment the home point "
                            + "was broken, with no further rule");
                });
        moment(script, "dock", this::parkedAtTheDock, MomentView.fixed(DOCK_VIEW), MomentView.fixed(BEND));
    }

    // --- assertions shared by several steps -----------------------------------------------------------------------

    private void assertParked(MinecraftServer server, VisualContext context, String when) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = dock(level, dock);
        if (!parkedAtTheHomePoint(level, dock, crane))
            throw new VisualTestException("the machine is not parked at its home point " + when + ": "
                    + describe(crane.craneState(), crane.goggleInfo().heldCount()));
        assertServingHomePoint(level, dock, memberPosition(controller(level, dock), dock, HOME));
        LOGGER.info(PREFIX + "home: CHECK the machine waits at its home point {}: {}", when,
                describe(crane.craneState(), crane.goggleInfo().heldCount()));
    }

    /** Fails the run unless the client heard the machine drive and turn on its way home. */
    private static void assertReturnSounds(VisualContext context) {
        List<String> missing = RETURN_SOUNDS.stream().filter(sound -> context.soundCount(sound) == 0).toList();
        LOGGER.info(PREFIX + "home: return sounds {}", RETURN_SOUNDS.stream()
                .map(sound -> sound + "=" + context.soundCount(sound)).toList());
        if (!missing.isEmpty())
            throw new VisualTestException("the trip home was not heard: " + missing + "; heard "
                    + context.soundCounts());
    }

    private static String describe(CraneState<ItemKey, RackPosition> state, long held) {
        CranePose pose = state.pose();
        return String.format(Locale.ROOT,
                "branch=%d x=%.3f y=%.2f yaw=%.3f turn=%.2f arm=%.2f side=%s phase=%s job=%s held=%d target=%d/%.2f "
                        + "paused=%b",
                pose.branch(), pose.x(), pose.y(), pose.yaw(), turnProgress(pose), pose.arm(), pose.side(),
                state.phase(), state.job().isPresent(), held, state.target().branch(), state.target().x(),
                state.paused());
    }

    // --- the player --------------------------------------------------------------------------------------------------

    /**
     * Lifts the player back into the air and switches creative flight on again, and waits until the client has it. A
     * creative player that touches the ground switches flying off by itself, and the next camera view in mid-air would
     * then never be reached.
     */
    private void fly(VisualScript script) {
        script.server("home: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("home: wait until the client is flying too", context -> {
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

    // --- cameras -----------------------------------------------------------------------------------------------------

    /** In the aisle ahead of the machine, looking back at the carriage — on whichever aisle it is named on. */
    private static CameraView frontView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction heading = headingOf(pose.branch());
        double craneX = BLOCK_CENTER + offset(pose, Direction.Axis.X);
        double craneZ = BLOCK_CENTER + offset(pose, Direction.Axis.Z);
        return CameraView.of(FRONT, craneX + heading.getStepX() * FRONT_AHEAD, pose.y() + FRONT_ABOVE,
                craneZ + heading.getStepZ() * FRONT_AHEAD, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    /**
     * Above a home point, looking down on its top face from the side <b>away</b> from the rails: the machine parks on
     * the rails, and a camera that looked over them would photograph the machine's mast instead of the block whose
     * tooltip the shot is about.
     */
    private static CameraView goggleView(String label, RackPosition rack) {
        Direction outward = outward(rack);
        double x = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.X) + outward.getStepX();
        double z = BLOCK_CENTER + offsetOf(rack.branch(), rack.x(), Direction.Axis.Z) + outward.getStepZ();
        return CameraView.of(label, x + outward.getStepX() * GOGGLE_EYE_ASIDE, GOGGLE_EYE_HEIGHT,
                z + outward.getStepZ() * GOGGLE_EYE_ASIDE, x, GOGGLE_TOP_FACE, z);
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

    /** How far through its quarter turn the machine is: 0 as it starts, 1 when it is square with its aisle again. */
    private static double turnProgress(CranePose pose) {
        double aligned = CranePose.yawOf(Headings.of(headingOf(pose.branch())));
        return 1.0 - Math.abs(CranePose.yawDelta(pose.yaw(), aligned));
    }

    // --- the world, read and written --------------------------------------------------------------------------------

    private void breakBlock(MinecraftServer server, VisualContext context, RackPosition rack) {
        BlockPos pos = rackPos(context.origin(), rack);
        // No drop: a dropped item would be one more item in the scene, and the census counts every one of them.
        if (!server.overworld().destroyBlock(pos, false))
            throw new VisualTestException("the block at " + pos + " could not be broken");
        LOGGER.info(PREFIX + "home: the block at {} was broken", pos);
    }

    private HomePointStatus statusAt(MinecraftServer server, VisualContext context, RackPosition rack) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        return controller == null ? HomePointStatus.NO_WAREHOUSE
                : controller.homePointStatusAt(rackPos(dock, rack));
    }

    /** The warehouse's own record of the member at a rack position, read by its world position. */
    private static Optional<LocationRecord> memberAt(WarehouseControllerBlockEntity controller, BlockPos dock,
            RackPosition rack) {
        return controller.locationAt(rackPos(dock, rack));
    }

    private static RackPosition memberPosition(WarehouseControllerBlockEntity controller, BlockPos dock,
            RackPosition rack) {
        return memberAt(controller, dock, rack)
                .orElseThrow(() -> new VisualTestException("no warehouse member at " + rackPos(dock, rack)))
                .position();
    }

    private static String address(WarehouseControllerBlockEntity controller, RackPosition rack) {
        return controller.warehouse().flatMap(warehouse -> warehouse.address(rack)).map(StorageAddress::format)
                .orElse("without an address");
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

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static Optional<WarehouseHomePointBlockEntity> clientHomePoint(VisualContext context, RackPosition rack) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(rackPos(context.origin(), rack)) instanceof WarehouseHomePointBlockEntity block
                ? Optional.of(block) : Optional.empty();
    }

    /** What a home point says on the client, for the status line of a shot. */
    private static String clientHomeStatus(VisualContext context, RackPosition rack) {
        return clientHomePoint(context, rack).map(block -> block.status().name()).orElse("none");
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
