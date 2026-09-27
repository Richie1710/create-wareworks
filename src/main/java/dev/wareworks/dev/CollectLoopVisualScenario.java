package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import org.jetbrains.annotations.Nullable;

import net.createmod.catnip.math.Pointing;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "collect-loop": <b>collecting in a real factory</b> ({@code docs/warehouse-system.md} §3.2.4, M18, issue
 * #13) — the production loop closed by the crane instead of by a return belt, and the three awkward cases the feature
 * was designed against.
 * <p>
 * One aisle, four storage locations, a stock keeper, a warehouse input, and on the machine side a <b>real Create
 * factory</b> that the mod knows nothing about: a Mechanical Arm takes the ingredient out of the warehouse production
 * station, a Mechanical Crafter turns one oak log into four oak planks and pushes them into its own output chest, and a
 * <b>collecting port</b> fetches them out of that chest into the racks. Nothing carries items back into the warehouse:
 * there is no belt, no funnel and no second arm on the return path, only the port and the crane — which is the whole
 * point of the third direction.
 * <p>
 * <b>Every claim a screenshot makes is asserted on the server before the shot that makes it</b>, and all four acts run
 * in the Flywheel pass, so the second pass photographs exactly the same finished scene:
 * <ol>
 *   <li><b>the loop closes by itself</b> — a stock rule's minimum orders {@value #PLANK_MINIMUM} planks, the crane
 *       supplies {@value #PLANK_RUNS} logs to the station, the arm feeds the crafter, the crafter's chest fills, the
 *       collecting port fetches the planks, the racks hold them and the order reads {@code COMPLETE} instead of timing
 *       out: the one line of {@code onCraneDelivered} that makes collecting close a production loop, in the real
 *       game;</li>
 *   <li><b>two collecting ports take turns</b> — both machine chests hold {@value #FAIRNESS_ITEMS} items, i.e. more
 *       than one crane trip each, and an input is kept busy at the same time: every port is served, the input is
 *       served, and no port is served twice in a row while the other one still has something (risk 4);</li>
 *   <li><b>the loop case cannot run away</b> — a maximum for copper plus an <b>overflow</b> port whose vanilla hopper
 *       pipes the surplus straight into the very chest the collecting port reads. Both preconditions are mutually
 *       exclusive, so the three numbers (collected, handed over, and what is in the chest) go constant and stay
 *       constant while the run watches on (risk 1);</li>
 *   <li><b>a full warehouse leaves the items where they are</b> — every rack chest full, diamonds in the machine: the
 *       crane picks nothing, the aisle reports {@code WAREHOUSE_FULL}, the port's goggles say how much is waiting, and
 *       the first room made afterwards is used at once (risk 3).</li>
 * </ol>
 * A {@link SceneItemCensus} at every quiet moment proves that nothing was created or lost. The census expectation
 * changes in exactly one place — the crafter, the one block of the scene that may turn one item into another — and that
 * change is the recipe, written down.
 * <p>
 * <b>Why this scenario needs a real player.</b> Two of its shots are goggle tooltips ("Ready" and "Collected" are the
 * lines that answer "does the port say so?"), and Create only draws a tooltip for a non-spectator who looks at a block
 * within reach, so it runs with {@link VisualWorldProfile#playable} and sets that reach back to 0 for the world shots —
 * a value box under the crosshair would otherwise cover the very block a shot is about.
 */
public final class CollectLoopVisualScenario implements VisualScenario {
    public static final String NAME = "collect-loop";

    /** Its own throw-away world: a scenario with a player must not reuse the camera world of another one. */
    private static final String WORLD_FOLDER = "wareworks_visual_collect_loop";
    /** Four acts with a real machine loop and two settle phases; the default 4 minutes are for a single-act scenario. */
    private static final long RUN_TIMEOUT_MILLIS = 25L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 10;
    private static final int CRANE_RPM = 128;
    /** The fastest a creative motor turns: one arm movement then takes four ticks. */
    private static final int ARM_RPM = 256;
    private static final int CRAFTER_RPM = 128;

    // --- the aisle's members -----------------------------------------------------------------------------------------

    /**
     * Storage locations, on the plane opposite the machines and near the dock, so a collect trip really travels: three
     * vanilla chests are 81 slots, which is room enough for everything the four acts store and few enough to fill to the
     * brim for the full-warehouse act.
     */
    private static final int STORAGE_FIRST = 1;
    private static final int STORAGE_LAST = 3;
    private static final RackPosition KEEPER_RACK = RackPosition.of(6, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = RackPosition.of(8, 0, Side.LEFT);
    /** The machine's ingredient buffer: what the crane supplies and the arm empties. */
    private static final RackPosition PRODUCTION_RACK = RackPosition.of(2, 0, Side.RIGHT);
    /** The collecting port of the production loop; the crafter's output chest stands behind it. */
    private static final RackPosition COLLECT_A_RACK = RackPosition.of(5, 0, Side.RIGHT);
    /** The second collecting port, for the fairness act and the loop act. */
    private static final RackPosition COLLECT_B_RACK = RackPosition.of(8, 0, Side.RIGHT);
    /**
     * The hopper that drains the overflow port above it into the <b>east half</b> of the double chest the second
     * collecting port reads — the real pipe that closes the churn loop of act 3 without a line of test code.
     */
    private static final RackPosition DRAIN_RACK = RackPosition.of(9, 0, Side.RIGHT);
    /** The overflow port, one level up, so a vanilla hopper can pull from underneath it as in the "ports" scenario. */
    private static final RackPosition OVERFLOW_RACK = RackPosition.of(9, 1, Side.RIGHT);

    // --- the player's machinery (offsets from the dock, outward of the machine plane) --------------------------------

    private static final BlockPos ARM = new BlockPos(3, 0, 3);
    private static final BlockPos ARM_COG = new BlockPos(3, 0, 4);
    private static final BlockPos ARM_MOTOR = new BlockPos(3, -1, 4);
    /** Faces east (rotation axis x) and points north, into the chest behind the first collecting port. */
    private static final BlockPos CRAFTER = new BlockPos(5, 0, 3);
    private static final BlockPos CRAFTER_COG = new BlockPos(5, 1, 3);
    private static final BlockPos CRAFTER_MOTOR = new BlockPos(6, 1, 3);

    private static final int CLEAR_MIN_X = -4;
    private static final int CLEAR_MAX_X = RAILS + 4;
    private static final int CLEAR_MIN_Z = -5;
    private static final int CLEAR_MAX_Z = 6;
    private static final int CLEAR_HEIGHT = 8;
    /**
     * The census box around the aisle, as offsets from the dock: wide enough for the storage chests on one side and the
     * whole machine cluster on the other, and <b>two</b> blocks down rather than five, because
     * {@code Level#isLoaded} is false for a position below the world and a box that reaches under the superflat bedrock
     * could never be "fully loaded".
     */
    private static final int CENSUS_MIN_X = -5;
    private static final int CENSUS_MAX_X = RAILS + 5;
    private static final int CENSUS_MIN_Y = -2;
    private static final int CENSUS_MAX_Y = CLEAR_HEIGHT;
    private static final int CENSUS_MIN_Z = -6;
    private static final int CENSUS_MAX_Z = 6;

    // --- items and amounts -------------------------------------------------------------------------------------------

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey FILLER = ItemKey.of(Items.COBBLESTONE);

    /** Slots and stack size of a vanilla chest: what it takes to fill one completely. */
    private static final int CHEST_SLOTS = 27;
    private static final int STACK = 64;

    /**
     * Act 1: what the crafter makes out of one log, and how many runs the minimum is worth. Eight runs rather than two,
     * so the machine really runs for a while: every run is one arm movement, one craft and one insertion into the
     * crafter's chest, which is what gives the crane several collect trips to photograph.
     */
    private static final int PLANKS_PER_LOG = 4;
    private static final int PLANK_MINIMUM = 32;
    private static final int PLANK_RUNS = PLANK_MINIMUM / PLANKS_PER_LOG;
    /** More logs than the order spends, so the rest stays in the racks and the census has something to account for. */
    private static final int LOGS_IN_STOCK = 12;

    /**
     * Act 2: items per machine chest. More than one crane trip ({@code grabberStacks} × one stack, the config default),
     * because "the ports take turns" can only be seen at all when each of them needs several trips.
     */
    private static final int FAIRNESS_ITEMS = 96;
    /** Act 2: how much is pushed into the input each time it runs low, and how much in total. */
    private static final int INPUT_BATCH = 16;
    private static final int INPUT_TOTAL = 96;
    /** Act 2: the input is topped up again below this, so it is never empty for long — what a belt would do. */
    private static final int INPUT_LOW_WATER = 8;

    /** Act 3: the maximum that makes an overflow necessary, and the copper fed against it. */
    private static final int COPPER_MAXIMUM = 16;
    private static final int COPPER_FED = 24;
    private static final int COPPER_SURPLUS = COPPER_FED - COPPER_MAXIMUM;

    /** Act 4: diamonds waiting in the machine while every rack chest is full. */
    private static final int DIAMONDS_IN_MACHINE = 24;

    private static final int RULE_PLANKS = 0;
    private static final int RULE_COPPER = 1;

    // --- timeouts ----------------------------------------------------------------------------------------------------

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int PORT_READY_TIMEOUT_TICKS = 400;
    private static final int POWER_TIMEOUT_TICKS = 200;
    private static final int MOMENT_TIMEOUT_TICKS = 2400;
    private static final int LOOP_TIMEOUT_TICKS = 6000;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 10;
    /** Long enough for several dispatch intervals, a collect poll and a back-off: proof that nothing happens any more. */
    private static final int QUIET_TICKS = 80;
    /** Polls between two log lines of the machine cluster's state while a moment of the machine loop is waited for. */
    private static final int MACHINE_LOG_INTERVAL = 20;
    /** How far the crane's arm has to be out for a shot to show it reaching <b>into</b> a port. */
    private static final double ARM_EXTENDED_MIN = 0.9;
    /** Tolerance when a synced crane target is matched against a rack position. */
    private static final double TARGET_TOLERANCE = 0.05;
    /**
     * A travel moment needs this much left on one axis, so the crane is still travelling when the freeze arrives two
     * ticks later. A collect trip of this scene is short on purpose — the machines stand beside the racks, which is what
     * a player builds — so the threshold is a fraction of a block rather than a whole one.
     */
    private static final double MIN_REMAINING_TRAVEL = 0.3;

    // --- cameras -----------------------------------------------------------------------------------------------------

    /** The whole factory from above the machine side: aisle, rack walls, arm, crafter and the two port columns. */
    private static final CameraView FACTORY = CameraView.of("factory", 9.0, 4.6, 6.6, 4.0, 1.0, 0.6);
    /** The machine cluster: production station, arm, crafter and the crafter's output chest behind the port. */
    private static final CameraView MACHINE = CameraView.of("machine", 7.5, 4.2, 8.5, 4.5, 0.9, 1.6);
    /** The arm between the station it empties and the crafter it feeds. */
    private static final CameraView ARM_VIEW = CameraView.of("arm", 0.8, 3.2, 7.0, 4.0, 1.0, 2.2);
    /** Inside the aisle, along the rails back to the dock. */
    private static final CameraView ALONG = CameraView.of("aisle", 12.5, 3.0, 0.5, 1.0, 1.5, 0.5);
    /**
     * The first collecting port from its machine's side, from above and east of the crafter so that the machine does
     * not stand in front of its own chest: the copper spout, the chest the crafter fills and the crafter itself, in one
     * frame.
     * <p>
     * Not from inside the aisle, however much the copper ring wants to be photographed there: the crane parks wherever
     * its last job ended, and a camera in the aisle is regularly inside the mast — which is a black screen, not a shot.
     */
    private static final CameraView PORT_A_BACK = CameraView.of("port-a", 6.8, 3.4, 4.6, 5.2, 1.0, 1.7);
    /** Inside the aisle in front of the second port column: copper below, andesite above. */
    private static final CameraView PORT_B_FRONT = CameraView.of("port-b", 5.2, 1.7, 0.4, 8.9, 1.3, 1.0);
    /** Behind the second port column: the overflow port, its hopper and the double chest both ports meet in. */
    private static final CameraView PORTS_BACK = CameraView.of("ports-back", 8.8, 3.4, 6.0, 8.8, 1.0, 1.5);
    /** The storage wall from outside the aisle, where everything the crane fetched ends up. */
    private static final CameraView RACKS = CameraView.of("racks", 2.5, 3.2, -6.0, 2.5, 1.0, -1.7);

    /**
     * Goggle shot of the first collecting port: from above its machine, looking down at the port's <b>top</b> face.
     * <p>
     * Two reasons for the angle. A camera in the aisle is at the mercy of where the crane parked, and the mast then
     * fills the frame. And Create's goggle overlay bails out before it draws a line when the crosshair really hits a
     * value box: the port's filter slot sits in the <b>middle</b> of its top face, so the crosshair is aimed at the
     * strip along the aisle edge of that face, which is the only part of it no box covers — the same trick the aisle
     * cameras of the "collect" scenario use from the other side.
     */
    private static final CameraView AT_PORT_A = CameraView.of("at-port-a", 5.5, 3.2, 2.7, 5.5, 1.0, 1.09);
    /** Goggle shot of the second collecting port, the same way. */
    private static final CameraView AT_PORT_B = CameraView.of("at-port-b", 8.5, 3.2, 2.7, 8.5, 1.0, 1.09);
    /**
     * Goggle shot of the controller, west of it and aimed at the <b>lower left corner</b> of its back face rather than
     * at its middle: the controller's aisle letter is a scroll value box on every face but the dock's, and a value box
     * the crosshair really hits makes Create's goggle overlay bail out before it draws a line ({@link GoggleShots}).
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("at-controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);

    /**
     * Following camera for the frozen moments: in the aisle <b>behind</b> the crane (on the dock side, where the aisle
     * is empty), at arm height, looking past the carriage at the arm crossing the rack plane into the port.
     * <p>
     * Behind rather than ahead, because ahead of a collecting port stands the next port's column with its hopper and
     * its double chest, and a camera among those photographs chests. Beside the crane is no option at all: the aisle is
     * one block wide between two rack walls.
     */
    private static final String CLOSE = "crane";
    private static final double CLOSE_BEHIND = 3.2;
    private static final double CLOSE_ABOVE = 1.8;
    /**
     * How far the camera leans away from the port's side of the aisle. The aisle is one block wide and the camera is a
     * real player, so its centre has to stay within its own half-width of the middle: at 0.3 the player is pushed back
     * out of the rack wall and never arrives at the view.
     */
    private static final double CLOSE_OFFSIDE = 0.15;
    private static final double CLOSE_LOOK_ASIDE = 0.9;
    private static final double ARM_LOOK_HEIGHT = 0.5;
    /** Following camera behind the travelling crane, looking along the aisle at what it carries. */
    private static final String CARRY = "carry";
    private static final double CARRY_BEHIND = 3.4;
    private static final double CARRY_ABOVE = 2.0;
    private static final double CARRY_LOOK_HEIGHT = 0.8;
    private static final double BLOCK_CENTER = 0.5;

    // --- run state ---------------------------------------------------------------------------------------------------

    /** The census box of the scene, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene holds; changed only where items are added, removed or turned into other items on purpose. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Act 2: one entry per collect job the run saw, in order. */
    private final List<CollectStep> collectSteps = new ArrayList<>();
    private UUID lastCollectJob;
    /** Act 2: how much was pushed into the input so far. */
    private int inputFed;
    /** Act 3: what the port collected, what the overflow handed over, and what is in the chest they meet in. */
    private volatile long[] churnNumbers = new long[3];
    /** How often the machine cluster was polled, so its state is logged now and then rather than every tick. */
    private int machinePolls;

    /**
     * One collect job of the fairness act.
     *
     * @param source        the port the crane picked at
     * @param otherWasReady whether the <b>other</b> collecting port's chest still held items at that moment, i.e.
     *                      whether serving this one twice in a row would have been unfair
     */
    private record CollectStep(RackPosition source, boolean otherWasReady) {
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
        script.client("collect-loop: arm the run watchdog",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "collect-loop run"))
                .server("collect-loop: clear the area and place the four creative motors", this::placeMotors)
                .server("collect-loop: build the aisle, its members, the machines and the two port columns",
                        CollectLoopVisualScenario::buildScene)
                .serverUntil("collect-loop: wait until the controller is ready with every member",
                        CollectLoopVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so it
                // is lifted into the air first and only then made to fly.
                .server("collect-loop: lift the player into the air", CollectLoopVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("collect-loop: the player flies and wears Engineer's Goggles",
                        CollectLoopVisualScenario::equipPlayer)
                .server("collect-loop: turn the two ports around and filter the overflow",
                        CollectLoopVisualScenario::configurePorts)
                .serverUntil("collect-loop: wait until the controller has read both machines",
                        CollectLoopVisualScenario::portsReady, PORT_READY_TIMEOUT_TICKS)
                .server("collect-loop: write the production pattern and the two stock rules",
                        CollectLoopVisualScenario::writePatternAndRules)
                .server("collect-loop: place the mechanical arm between the station and the crafter",
                        CollectLoopVisualScenario::placeArm)
                .server("collect-loop: put " + LOGS_IN_STOCK + " logs into the racks", this::stockLogs)
                .serverUntil("collect-loop: wait until every chunk of the census box is loaded",
                        (server, context) -> SceneItemCensus.isFullyLoaded(server.overworld(), censusBox),
                        SCENE_READY_TIMEOUT_TICKS)
                .server("collect-loop: take the baseline census", (server, context) -> census(server, "before the loop"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and a port has two of
        // them, so the world shots are taken by a player who reaches nothing.
        reach(script, 0.0);
        fly(script);
        for (CameraView view : List.of(FACTORY, MACHINE, PORTS_BACK))
            script.shotFrom(view, "scene");

        if (pass == VisualPass.FLYWHEEL) {
            script.server("collect-loop: power the crane, the arm and the crafter",
                            CollectLoopVisualScenario::powerOn)
                    .serverUntil("collect-loop: the crane, the arm and the crafter turn",
                            CollectLoopVisualScenario::machinesTurn, POWER_TIMEOUT_TICKS);
            productionLoop(script);
            twoPortsTakeTurns(script);
            theLoopCase(script);
            theFullWarehouse(script);
            script.client("collect-loop: every check passed",
                    context -> LOGGER.info(PREFIX + "collect-loop: ALL CHECKS PASSED (a real machine loop closed by a "
                            + "collecting port, two ports taking turns with an input, a maximum plus an overflow that "
                            + "cannot run away, and a full warehouse that leaves the items in the machine)"));
        }

        fly(script);
        for (CameraView view : List.of(PORT_A_BACK, PORT_B_FRONT, RACKS))
            script.shotFrom(view, "final");
    }

    @Override
    public String status(VisualContext context) {
        return clientCrane(context).map(crane -> {
            CranePose pose = crane.craneState().pose();
            String job = crane.goggleInfo().job().map(summary -> summary.type().name()).orElse("none");
            return String.format(Locale.ROOT, "posX=%.2f posY=%.2f arm=%.2f side=%s phase=%s job=%s held=%d", pose.x(),
                    pose.y(), pose.arm(), pose.side(), crane.craneState().phase(), job,
                    crane.goggleInfo().heldCount());
        }).orElse("crane=missing") + clientPorts(context) + GoggleShots.describeHover(context);
    }

    // --- act 1: the production loop ----------------------------------------------------------------------------------

    /**
     * The picture the milestone is about: the warehouse feeds a machine, the machine makes something, and the
     * <b>crane</b> brings the product back. Nothing else does.
     */
    private void productionLoop(VisualScript script) {
        script.serverUntil("collect-loop: the crane supplied the logs to the production station",
                        CollectLoopVisualScenario::ingredientDelivered, LOOP_TIMEOUT_TICKS)
                .server("collect-loop: check the automatic order that did it",
                        CollectLoopVisualScenario::assertRestockOrderRunning);
        fly(script);
        script.shotFrom(MACHINE, "supply");
        // The arm's claw is server-side knowledge: an arm has no item capability, its held stack only reaches a client
        // as save data, and the ingredient sits in the crafter's own slot for a handful of ticks before the craft
        // swallows it. So the moment is waited for, frozen and asserted on the server, all three.
        script.serverUntil("collect-loop: wait until the arm carries a log towards the crafter",
                this::armIsDelivering, MOMENT_TIMEOUT_TICKS);
        frozenShot(script, "arm-feeds", ARM_VIEW, CollectLoopVisualScenario::assertArmFeedsTheMachine);
        script.serverUntil("collect-loop: the crafter's output chest holds planks",
                        CollectLoopVisualScenario::productInTheChest, LOOP_TIMEOUT_TICKS);
        // Frozen, because a chest is not synced to a client and the port would otherwise empty it while the camera
        // travels: the shot's claim is asserted on the server in the same frozen moment it is taken in.
        frozenShot(script, "product", MACHINE, (server, context) -> {
            long planks = countIn(server.overworld(), machinePos(context.origin(), COLLECT_A_RACK), PLANKS);
            if (planks <= 0)
                throw new VisualTestException("the crafter's chest is empty in the shot that shows its product");
            LOGGER.info(PREFIX + "collect-loop: the crafter's chest holds {} planks in the 'product' shot", planks);
        });
        moment(script, "collect-arm", context -> collectingAt(context, COLLECT_A_RACK),
                CollectLoopVisualScenario::craneView);
        moment(script, "collect-carry", CollectLoopVisualScenario::carryingCollectedItems,
                CollectLoopVisualScenario::carryView);
        script.serverUntil("collect-loop: the loop finished: the planks are in the racks and the order is complete",
                        CollectLoopVisualScenario::loopFinished, LOOP_TIMEOUT_TICKS)
                .serverUntil("collect-loop: wait until every machine is quiet again",
                        CollectLoopVisualScenario::quiet, LOOP_TIMEOUT_TICKS)
                .server("collect-loop: CHECK 1 the production loop closed through the collecting port",
                        this::assertLoop);
        fly(script);
        script.shotFrom(RACKS, "loop");
        // What the racks hold is the one claim of this act a world shot cannot show, so the controller says it: the
        // aisle counts the planks the machine made, and it counts both collecting ports.
        controllerGoggles(script, LOGS_IN_STOCK - PLANK_RUNS + PLANK_MINIMUM);
    }

    // --- act 2: two collecting ports take turns ----------------------------------------------------------------------

    /**
     * Risk 4: two machines with more than one crane trip each, and an input that is never empty for long. Nothing may
     * starve — neither the second port behind the first one, nor storing behind collecting.
     */
    private void twoPortsTakeTurns(VisualScript script) {
        script.server("collect-loop: fill both machine chests and start feeding the input", this::fillBothMachines);
        frozenShot(script, "turns-full", PORTS_BACK, (server, context) -> {
            ServerLevel level = server.overworld();
            BlockPos dock = context.origin();
            long first = countIn(level, machinePos(dock, COLLECT_A_RACK), IRON);
            long second = countIn(level, machinePos(dock, COLLECT_B_RACK), GOLD);
            if (first != FAIRNESS_ITEMS || second != FAIRNESS_ITEMS)
                throw new VisualTestException("the machines hold " + first + " and " + second + " items in the shot "
                        + "that shows them full, expected " + FAIRNESS_ITEMS + " each");
        });
        // The picture of "taking turns": the same crane reaching into the one port and then into the other, each time
        // while the other machine still has items — which is the claim, and it is asserted in the frozen moment.
        turnMoment(script, "turns-port-a", COLLECT_A_RACK, COLLECT_B_RACK, GOLD);
        turnMoment(script, "turns-port-b", COLLECT_B_RACK, COLLECT_A_RACK, IRON);

        script.serverUntil("collect-loop: both machines are emptied and everything fed is stored",
                        this::fairnessDone, LOOP_TIMEOUT_TICKS)
                .serverUntil("collect-loop: wait until every machine is quiet again",
                        CollectLoopVisualScenario::quiet, LOOP_TIMEOUT_TICKS)
                .server("collect-loop: CHECK 2 both ports were served, and so was the input", this::assertFairness);
        fly(script);
        script.shotFrom(PORTS_BACK, "turns-empty");
    }

    // --- act 3: the loop case ----------------------------------------------------------------------------------------

    /**
     * Risk 1: the loop a player builds by accident. A maximum caps copper, so the surplus of what arrives at the input
     * leaves through the overflow port, whose hopper drops it into the very chest the second collecting port reads. The
     * two preconditions are mutually exclusive, so the warehouse has to settle instead of shuffling for ever.
     */
    private void theLoopCase(VisualScript script) {
        script.server("collect-loop: feed " + COPPER_FED + " copper into the input against a maximum of "
                        + COPPER_MAXIMUM, this::feedCopper)
                .serverUntil("collect-loop: the maximum is reached and the surplus went through the overflow",
                        CollectLoopVisualScenario::surplusArrived, LOOP_TIMEOUT_TICKS);
        fly(script);
        script.shotFrom(PORTS_BACK, "overflow");
        script.server("collect-loop: remember the three numbers of the loop", this::rememberChurnNumbers)
                .waitTicks(QUIET_TICKS)
                .waitTicks(QUIET_TICKS)
                .waitTicks(QUIET_TICKS)
                .server("collect-loop: CHECK 3 the loop settled: nothing moved while the run watched",
                        this::assertNoChurn);
        fly(script);
        script.shotFrom(PORT_B_FRONT, "no-churn");
        script.shotFrom(ALONG, "no-churn");
        goggles(script, AT_PORT_B, "goggles-port-b", COLLECT_B_RACK, COPPER_SURPLUS, NoJobReason.AT_MAXIMUM);
    }

    // --- act 4: the full warehouse -----------------------------------------------------------------------------------

    /**
     * Risk 3: what cannot be stored is left where it is. Every rack chest is filled to the brim, diamonds appear in the
     * machine, and the crane does not touch them — until one chest is emptied again.
     */
    private void theFullWarehouse(VisualScript script) {
        script.server("collect-loop: fill every rack chest and put " + DIAMONDS_IN_MACHINE
                        + " diamonds into the machine", this::fillWarehouse)
                .waitTicks(QUIET_TICKS)
                .waitTicks(QUIET_TICKS)
                .server("collect-loop: CHECK 4 a full warehouse leaves the diamonds in the machine",
                        this::assertFullWarehouse);
        fly(script);
        script.shotFrom(MACHINE, "full");
        goggles(script, AT_PORT_A, "goggles-port-a", COLLECT_A_RACK, DIAMONDS_IN_MACHINE,
                NoJobReason.WAREHOUSE_FULL);
        script.server("collect-loop: empty one rack chest again", this::makeRoom)
                .serverUntil("collect-loop: the diamonds are fetched the moment there is room",
                        CollectLoopVisualScenario::diamondsStored, LOOP_TIMEOUT_TICKS)
                .serverUntil("collect-loop: wait until every machine is quiet again",
                        CollectLoopVisualScenario::quiet, LOOP_TIMEOUT_TICKS)
                .server("collect-loop: CHECK 5 room made is used at once", this::assertRoomUsed);
        fly(script);
        script.shotFrom(RACKS, "room-again");
        // The same port as two shots ago, with the machine emptied: "Ready" has fallen back to nothing and "Collected"
        // has risen by exactly the diamonds — the visible half of check 5.
        goggles(script, AT_PORT_A, "goggles-port-a-after", COLLECT_A_RACK, 0L, null);
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = censusBoxOf(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(CLEAR_MIN_X, 0, CLEAR_MIN_Z),
                dock.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        BlockState motorUp = AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP);
        level.setBlockAndUpdate(dock.below(), motorUp);
        level.setBlockAndUpdate(dock.offset(ARM_MOTOR), motorUp);
        level.setBlockAndUpdate(dock.offset(CRAFTER_MOTOR), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.WEST));
    }

    private static void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (BlockPos motor : List.of(dock.below(), dock.offset(ARM_MOTOR), dock.offset(CRAFTER_MOTOR)))
            motor(level, motor).generatedSpeed.setValue(0);

        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        // The rack wall the crane stores into, on the plane opposite the machines.
        Direction storageOutward = layout.sideDirection(Side.LEFT);
        for (int x = STORAGE_FIRST; x <= STORAGE_LAST; x++) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.LEFT));
            level.setBlockAndUpdate(rack.relative(storageOutward), Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, storageOutward.getOpposite()));
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, storageOutward));
        }
        level.setBlockAndUpdate(layout.rackPos(KEEPER_RACK), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, towardsAisle(layout, KEEPER_RACK)));
        level.setBlockAndUpdate(layout.rackPos(INPUT_RACK), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, towardsAisle(layout, INPUT_RACK)));
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION_RACK), WareworksBlocks.WAREHOUSE_PRODUCTION
                .getDefaultState().setValue(WarehouseProductionBlock.FACING, towardsAisle(layout, PRODUCTION_RACK)));

        // The three ports. Each faces the aisle like every station; what a port reaches into stands behind it.
        for (RackPosition port : List.of(COLLECT_A_RACK, COLLECT_B_RACK, OVERFLOW_RACK))
            level.setBlockAndUpdate(layout.rackPos(port), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                    .setValue(WarehouseOutputBlock.FACING, towardsAisle(layout, port)));
        Direction machineOutward = layout.sideDirection(Side.RIGHT);
        // The crafter's own output chest: a single chest, the machine's result and nothing else.
        level.setBlockAndUpdate(machinePos(dock, COLLECT_A_RACK), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, machineOutward));
        // The second machine's chest is a double chest, so the overflow port's hopper and the collecting port really
        // meet in ONE inventory: the east half takes what the hopper drops, the port reads the west half.
        level.setBlockAndUpdate(machinePos(dock, COLLECT_B_RACK), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        level.setBlockAndUpdate(machinePos(dock, DRAIN_RACK), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        level.setBlockAndUpdate(layout.rackPos(DRAIN_RACK), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, machineOutward));

        // The player's factory: an arm between the station and the crafter, and the crafter pointing into its chest.
        level.setBlockAndUpdate(dock.offset(ARM_COG), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        level.setBlockAndUpdate(dock.offset(CRAFTER), crafterState(Direction.EAST, machineOutward.getOpposite()));
        level.setBlockAndUpdate(dock.offset(CRAFTER_COG), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        if (!dock.offset(CRAFTER).relative(machineOutward.getOpposite()).equals(machinePos(dock, COLLECT_A_RACK)))
            throw new VisualTestException("the crafter does not point at the chest behind the first collecting port");
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        int storage = STORAGE_LAST - STORAGE_FIRST + 1;
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == storage && controller.inputStations().size() == 1
                && controller.outputStations().size() == 3 && controller.productionStations().size() == 1
                && crane.isControllerLinked();
    }

    /** The wrench board's rows, as a player sets them: the direction is the rank, and nothing else. */
    private static void configurePorts(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (RackPosition rack : List.of(COLLECT_A_RACK, COLLECT_B_RACK)) {
            WarehouseOutputBlockEntity port = port(level, dock, rack);
            if (!port.setPortRank(PortSettings.COLLECT_RANK))
                throw new VisualTestException("the port at " + rack + " did not take the collect direction");
            port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
            if (port.portDirection() != PortDirection.COLLECT)
                throw new VisualTestException("the port at " + rack + " does not collect: " + port.portSettings());
            BlockState state = level.getBlockState(layout(dock).rackPos(rack));
            if (!state.getValue(WarehouseOutputBlock.COLLECTING) || state.getValue(WarehouseOutputBlock.ACCEPTING))
                throw new VisualTestException("the block at " + rack + " does not show the collect direction: " + state);
        }
        // The overflow: the weakest accepting rank, so a storage location always wins, and filtered to copper, so
        // nothing else can ever leave the warehouse through it (act 4 fills the racks while an input still holds items,
        // and an unfiltered overflow would then pipe those into the machine chest for ever).
        WarehouseOutputBlockEntity overflow = port(level, dock, OVERFLOW_RACK);
        if (!overflow.setPortRank(-1))
            throw new VisualTestException("the overflow port did not take rank -1");
        overflow.setRedstoneMode(PortRedstone.UNLESS_POWERED);
        if (overflow.portDirection() != PortDirection.ACCEPT || !overflow.portSettings().isOverflow())
            throw new VisualTestException("the overflow port is " + overflow.portSettings());
        if (!filterOf(overflow).setFilter(COPPER.toStack(1)))
            throw new VisualTestException("the overflow port did not take the copper filter");
        LOGGER.info(PREFIX + "collect-loop: ports are {} / {} / {} {}", port(level, dock, COLLECT_A_RACK)
                        .portDirection(), port(level, dock, COLLECT_B_RACK).portDirection(), overflow.portDirection(),
                overflow.portRank());
    }

    /**
     * Whether the controller has really <b>read</b> both machines. Waiting for this rather than for a tick count is what
     * keeps the run honest: an unread port collects nothing on purpose, and a scenario that shot the scene before the
     * first read would photograph that instead of collecting.
     */
    private static boolean portsReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (controller.collectingPortCount() != 2 || controller.pendingSnapshotCount() != 0)
            return false;
        for (RackPosition rack : List.of(COLLECT_A_RACK, COLLECT_B_RACK)) {
            WarehouseOutputBlockEntity port = port(level, dock, rack);
            // The inventory behind the port has to be found, and it must not be one this aisle already counts: a port
            // pointed at its own storage collects nothing at all, on purpose (guard 3).
            if (!port.hasAttachedInventory() || controller.collectsFromOwnStorage(port.getBlockPos()))
                return false;
        }
        return true;
    }

    private static void writePatternAndRules(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout(dock).rackPos(PRODUCTION_RACK));
        if (station == null || !station.setPatternEntry(0, 0, LOG, 1)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANKS, PLANKS_PER_LOG))
            throw new VisualTestException("the production pattern 1 oak log -> " + PLANKS_PER_LOG
                    + " oak planks could not be written");
        WarehouseStockKeeperBlockEntity keeper = keeper(level, dock);
        // Rule 0 drives the loop: a minimum the warehouse refills by itself, through the player's machine.
        rule(keeper, RULE_PLANKS, PLANKS, StockKeeperRules.FIELD_MINIMUM, PLANK_MINIMUM);
        // Rule 1 is act 3's guard: the maximum that makes an overflow necessary and a collect impossible.
        rule(keeper, RULE_COPPER, COPPER, StockKeeperRules.FIELD_MAXIMUM, COPPER_MAXIMUM);
    }

    private static void rule(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, int field, long value) {
        if (!keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L).changed()
                || !keeper.editRule(row, field, null, value).changed())
            throw new VisualTestException("the rule in row " + row + " could not be written");
    }

    /**
     * The Mechanical Arm of the loop: takes out of the production station, deposits into the crafter. Its targets are
     * handed to the arm through its saved data, the way a schematicannon places a configured arm — the same path
     * {@code gametest.MechanicalArmFixture} uses, because the placement packet's server half needs a connected player.
     */
    private static void placeArm(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BlockPos armPos = dock.offset(ARM);
        List<ArmInteractionPoint> selection = List.of(
                point(level, layout(dock).rackPos(PRODUCTION_RACK), Mode.TAKE),
                point(level, dock.offset(CRAFTER), Mode.DEPOSIT));
        level.setBlockAndUpdate(armPos, AllBlocks.MECHANICAL_ARM.getDefaultState());
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos);
        if (arm == null)
            throw new VisualTestException("the mechanical arm at " + armPos + " has no block entity");
        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag saved = arm.saveWithoutMetadata(registries);
        saved.put(INTERACTION_POINTS_TAG, new ArmPlacementPacket(selection, armPos).tag());
        arm.loadWithComponents(saved, registries);
        LOGGER.info(PREFIX + "collect-loop: the arm at {} takes from the station and deposits into the crafter", armPos);
    }

    /** NBT key of the arm's interaction point list ({@code ArmBlockEntity#write}). */
    private static final String INTERACTION_POINTS_TAG = "InteractionPoints";

    /** The point {@code wanted} clicks with the arm item would leave on the block at {@code pos}. */
    private static ArmInteractionPoint point(ServerLevel level, BlockPos pos, Mode wanted) {
        BlockState state = level.getBlockState(pos);
        ArmInteractionPoint point = ArmInteractionPoint.create(level, pos, state);
        if (point == null)
            throw new VisualTestException("a mechanical arm cannot target " + state + " at " + pos);
        for (int click = 0; click < Mode.values().length && point.getMode() != wanted; click++)
            point.cycleMode();
        if (point.getMode() != wanted)
            throw new VisualTestException("the arm point on " + state + " at " + pos + " cannot be " + wanted
                    + "; it is " + point.getMode());
        return point;
    }

    private void stockLogs(MinecraftServer server, VisualContext context) {
        insert(server.overworld(), storagePos(context.origin(), STORAGE_FIRST), LOG.toStack(LOGS_IN_STOCK));
        expected = SceneItemCensus.plus(expected, LOG, LOGS_IN_STOCK);
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        motor(level, dock.offset(ARM_MOTOR)).generatedSpeed.setValue(ARM_RPM);
        motor(level, dock.offset(CRAFTER_MOTOR)).generatedSpeed.setValue(CRAFTER_RPM);
    }

    private static boolean machinesTurn(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, dock.offset(ARM));
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                dock.offset(CRAFTER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        return arm != null && crafter != null && crane != null && arm.getSpeed() != 0f && crafter.getSpeed() != 0f
                && crane.getSpeed() != 0f;
    }

    // --- act 1 (server thread) ---------------------------------------------------------------------------------------

    private static boolean ingredientDelivered(MinecraftServer server, VisualContext context) {
        return productionCount(server.overworld(), context.origin(), LOG) >= PLANK_RUNS;
    }

    /**
     * Whether the arm's claw holds a log right now, i.e. it is on its way from the station to the crafter. Logs what the
     * machine cluster is doing every {@value #MACHINE_LOG_INTERVAL} polls, because "the player's machinery does not
     * start" is the one failure of this act that a screenshot cannot explain.
     */
    private boolean armIsDelivering(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ItemStack claw = armClaw(level, dock);
        if (machinePolls++ % MACHINE_LOG_INTERVAL == 0) {
            ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, dock.offset(ARM));
            MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                    dock.offset(CRAFTER));
            LOGGER.info(PREFIX + "collect-loop: machines: station={} logs, claw={}, armSpeed={}, crafterLoaded={}, "
                            + "crafterSpeed={}, chest={} planks", productionCount(level, dock, LOG), claw,
                    arm == null ? "none" : arm.getSpeed(), crafter != null && crafter.craftingItemPresent(),
                    crafter == null ? "none" : crafter.getSpeed(),
                    countIn(level, machinePos(dock, COLLECT_A_RACK), PLANKS));
        }
        return claw.getCount() > 0;
    }

    /**
     * The claim of the {@code arm-feeds} shot: the <b>player's</b> machinery is what moves the ingredient. The station
     * has been emptied partly although the warehouse never takes anything back out of a production station, and the log
     * is either in the arm's claw, in the crafter, or already a plank — the three places it can be in the tick the
     * picture was taken.
     */
    private static void assertArmFeedsTheMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long left = productionCount(level, dock, LOG);
        if (left >= PLANK_RUNS)
            throw new VisualTestException("the station still holds all " + left + " logs, so the arm took nothing");
        long inClaw = armClaw(level, dock).getCount();
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                dock.offset(CRAFTER));
        boolean inCrafter = crafter != null && crafter.craftingItemPresent();
        long made = countIn(level, machinePos(dock, COLLECT_A_RACK), PLANKS);
        if (inClaw == 0 && !inCrafter && made == 0)
            throw new VisualTestException("the arm delivered nothing: its claw is empty, the crafter is empty and no "
                    + "plank was made");
        LOGGER.info(PREFIX + "collect-loop: the arm has taken {} of {} logs out of the station; {} in its claw, crafter "
                + "loaded={}, {} planks made so far", PLANK_RUNS - left, PLANK_RUNS, inClaw, inCrafter, made);
    }

    /** The stack in the arm's claw: an arm has no item capability and no accessor for it, but it saves the stack. */
    private static ItemStack armClaw(ServerLevel level, BlockPos dock) {
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, dock.offset(ARM));
        if (arm == null)
            throw new VisualTestException("the mechanical arm of the loop is missing");
        HolderLookup.Provider registries = level.registryAccess();
        return ItemStack.parseOptional(registries,
                arm.saveWithoutMetadata(registries).getCompound(ARM_HELD_ITEM_TAG));
    }

    /** NBT key of the stack in a Create mechanical arm's claw ({@code ArmBlockEntity#write}). */
    private static final String ARM_HELD_ITEM_TAG = "HeldItem";

    private static void assertRestockOrderRunning(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.openProductionOrders();
        if (orders.size() != 1)
            throw new VisualTestException("expected exactly one open production order, found " + orders.size());
        ProductionOrder<ItemKey, RackPosition> order = orders.getFirst();
        if (!order.isRestock() || !order.result().equals(PLANKS))
            throw new VisualTestException("the open order is not the automatic plank order: restock=" + order.isRestock()
                    + " result=" + order.result());
        LOGGER.info(PREFIX + "collect-loop: the minimum of {} planks ordered {} runs; the station holds {} logs",
                PLANK_MINIMUM, PLANK_RUNS, productionCount(server.overworld(), context.origin(), LOG));
    }

    private static boolean productInTheChest(MinecraftServer server, VisualContext context) {
        return countIn(server.overworld(), machinePos(context.origin(), COLLECT_A_RACK), PLANKS) > 0;
    }

    private static boolean loopFinished(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        return controller.countOf(PLANKS) >= PLANK_MINIMUM && controller.openProductionOrders().isEmpty()
                && countIn(level, machinePos(dock, COLLECT_A_RACK), PLANKS) == 0;
    }

    /**
     * The whole loop in one assertion: the machine spent {@value #PLANK_RUNS} logs, the racks hold
     * {@value #PLANK_MINIMUM} planks, every one of them came in through the collecting port, the order reads
     * {@code COMPLETE} rather than timing out, no rule was paused — and the census accounts for every item, with the
     * crafter's recipe as the one change.
     */
    private void assertLoop(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long planks = controller.countOf(PLANKS);
        long logs = controller.countOf(LOG);
        long collected = port(level, dock, COLLECT_A_RACK).collectedItems();
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        if (planks != PLANK_MINIMUM)
            throw new VisualTestException("the racks hold " + planks + " planks, expected " + PLANK_MINIMUM);
        if (logs != LOGS_IN_STOCK - PLANK_RUNS)
            throw new VisualTestException("the loop spent " + (LOGS_IN_STOCK - logs) + " logs, expected " + PLANK_RUNS);
        if (collected != PLANK_MINIMUM)
            throw new VisualTestException("the collecting port fetched " + collected + " items, expected every one of "
                    + PLANK_MINIMUM + " planks");
        if (orders.size() != 1 || orders.getFirst().state() != ProductionOrderState.COMPLETE)
            throw new VisualTestException("the loop ran " + orders.size() + " order(s), the first one ended "
                    + (orders.isEmpty() ? "nowhere" : orders.getFirst().state()) + "; expected one "
                    + ProductionOrderState.COMPLETE);
        if (controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("a stock rule was paused although the loop completed");
        if (countIn(level, machinePos(dock, COLLECT_A_RACK), PLANKS) != 0)
            throw new VisualTestException("the crafter's chest still holds planks");
        // The one place a machine really changed items, so the one place the census expectation changes.
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -PLANK_RUNS), PLANKS, PLANK_MINIMUM);
        census(server, "after the production loop");
        LOGGER.info(PREFIX + "collect-loop: CHECK 1 PASS: {} logs became {} planks in the player's machine, and the "
                + "collecting port fetched all {} of them back into the racks with no return belt; the order is {}",
                PLANK_RUNS, planks, collected, ProductionOrderState.COMPLETE);
    }

    // --- act 2 (server thread) ---------------------------------------------------------------------------------------

    private void fillBothMachines(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        // What two machines would leave in their output chests. The first chest is the crafter's own; act 2 is about
        // fairness, not about the recipe, so what is in them only has to be told apart.
        insert(level, machinePos(dock, COLLECT_A_RACK), IRON.toStack(FAIRNESS_ITEMS));
        insert(level, machinePos(dock, COLLECT_B_RACK), GOLD.toStack(FAIRNESS_ITEMS));
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, IRON, FAIRNESS_ITEMS), GOLD, FAIRNESS_ITEMS);
        collectSteps.clear();
        lastCollectJob = null;
        inputFed = 0;
        LOGGER.info(PREFIX + "collect-loop: both machine chests hold {} items", FAIRNESS_ITEMS);
    }

    /**
     * Polled every tick: tops the input up like a belt would, records every collect job the crane starts, and is done
     * once both machines are empty and everything fed has been stored.
     */
    private boolean fairnessDone(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        recordCollectJob(level, dock);
        if (inputFed < INPUT_TOTAL && inputCount(level, dock, FILLER) <= INPUT_LOW_WATER) {
            int batch = Math.min(INPUT_BATCH, INPUT_TOTAL - inputFed);
            IItemHandler input = handler(level, layout(dock).rackPos(INPUT_RACK));
            ItemStack rest = ItemHandlerHelper.insertItem(input, FILLER.toStack(batch), false);
            int fed = batch - rest.getCount();
            inputFed += fed;
            expected = SceneItemCensus.plus(expected, FILLER, fed);
        }
        WarehouseControllerBlockEntity controller = controller(level, dock);
        return countIn(level, machinePos(dock, COLLECT_A_RACK), IRON) == 0
                && countIn(level, machinePos(dock, COLLECT_B_RACK), GOLD) == 0 && inputFed >= INPUT_TOTAL
                && inputCount(level, dock, FILLER) == 0 && controller.countOf(FILLER) == INPUT_TOTAL;
    }

    /** Records one entry per collect job, with what the <b>other</b> port had to offer at that moment. */
    private void recordCollectJob(ServerLevel level, BlockPos dock) {
        Optional<TransportJob<ItemKey, RackPosition>> current = crane(level, dock).currentJob();
        if (current.isEmpty())
            return;
        TransportJob<ItemKey, RackPosition> job = current.get();
        if (job.type() != JobType.COLLECT || job.id().equals(lastCollectJob))
            return;
        lastCollectJob = job.id();
        RackPosition other = job.source().equals(COLLECT_A_RACK) ? COLLECT_B_RACK : COLLECT_A_RACK;
        ItemKey otherKey = other.equals(COLLECT_A_RACK) ? IRON : GOLD;
        boolean otherWasReady = countIn(level, machinePos(dock, other), otherKey) > 0;
        collectSteps.add(new CollectStep(job.source(), otherWasReady));
    }

    /**
     * Risk 4 in three claims: both machines were emptied, the input was served while they were, and the round robin
     * never served one port twice in a row while the other one still had something.
     */
    private void assertFairness(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long iron = controller.countOf(IRON);
        long gold = controller.countOf(GOLD);
        long filler = controller.countOf(FILLER);
        long collectedA = port(level, dock, COLLECT_A_RACK).collectedItems();
        long collectedB = port(level, dock, COLLECT_B_RACK).collectedItems();
        LOGGER.info(PREFIX + "collect-loop: the collect jobs went {}", describeSteps());
        if (iron != FAIRNESS_ITEMS || gold != FAIRNESS_ITEMS)
            throw new VisualTestException("the racks hold " + iron + " iron and " + gold + " gold, expected "
                    + FAIRNESS_ITEMS + " of each");
        if (filler != INPUT_TOTAL)
            throw new VisualTestException("the racks hold " + filler + " of the input's items, expected "
                    + INPUT_TOTAL);
        if (collectedA != PLANK_MINIMUM + FAIRNESS_ITEMS || collectedB != FAIRNESS_ITEMS)
            throw new VisualTestException("the ports counted " + collectedA + " and " + collectedB
                    + " collected items, expected " + (PLANK_MINIMUM + FAIRNESS_ITEMS) + " and " + FAIRNESS_ITEMS);
        long fromA = collectSteps.stream().filter(step -> step.source().equals(COLLECT_A_RACK)).count();
        long fromB = collectSteps.size() - fromA;
        if (fromA < 1 || fromB < 1)
            throw new VisualTestException("the record holds " + fromA + " job(s) from the first port and " + fromB
                    + " from the second one; both had to be served");
        // That each port needed several trips is carried by its counter, not by the record: the crane carries at most
        // one stack per trip ({@code grabberStacks}), so {@value #FAIRNESS_ITEMS} items cannot have crossed a port in
        // one. The record can start after the first job, because its moments are photographed before the act's own
        // polling step begins.
        if (FAIRNESS_ITEMS <= STACK)
            throw new VisualTestException("a machine holds " + FAIRNESS_ITEMS + " items, which one trip could carry; "
                    + "the fairness act needs more than one trip per port");
        for (int step = 1; step < collectSteps.size(); step++) {
            CollectStep previous = collectSteps.get(step - 1);
            CollectStep now = collectSteps.get(step);
            if (now.source().equals(previous.source()) && now.otherWasReady())
                throw new VisualTestException("the port at " + now.source() + " was served twice in a row while the "
                        + "other one still had items: " + describeSteps());
        }
        census(server, "after two ports took turns");
        LOGGER.info(PREFIX + "collect-loop: CHECK 2 PASS: both machines were emptied of {} items each ({} and {} of "
                + "the trips recorded), the input's {} items were stored while they were, and no port was served twice "
                + "in a row while the other one had something", FAIRNESS_ITEMS, fromA, fromB, INPUT_TOTAL);
    }

    private String describeSteps() {
        StringBuilder text = new StringBuilder();
        for (CollectStep step : collectSteps)
            text.append(step.source().equals(COLLECT_A_RACK) ? "A" : "B").append(step.otherWasReady() ? "* " : " ");
        return "[" + text.toString().trim() + "] (* = the other port still had items)";
    }

    // --- act 3 (server thread) ---------------------------------------------------------------------------------------

    private void feedCopper(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        insert(level, layout(context.origin()).rackPos(INPUT_RACK), COPPER.toStack(COPPER_FED));
        expected = SceneItemCensus.plus(expected, COPPER, COPPER_FED);
    }

    private static boolean surplusArrived(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return controller(level, dock).countOf(COPPER) == COPPER_MAXIMUM
                && countIn(level, machinePos(dock, COLLECT_B_RACK), COPPER) == COPPER_SURPLUS
                && inputCount(level, dock, COPPER) == 0;
    }

    private void rememberChurnNumbers(MinecraftServer server, VisualContext context) {
        churnNumbers = churnNumbersNow(server.overworld(), context.origin());
        LOGGER.info(PREFIX + "collect-loop: the loop's three numbers are collected={} handedOver={} inTheChest={}",
                churnNumbers[0], churnNumbers[1], churnNumbers[2]);
    }

    private long[] churnNumbersNow(ServerLevel level, BlockPos dock) {
        return new long[] {port(level, dock, COLLECT_B_RACK).collectedItems(),
                port(level, dock, OVERFLOW_RACK).exportedItems(),
                countIn(level, machinePos(dock, COLLECT_B_RACK), COPPER)};
    }

    /**
     * Risk 1: the same three numbers, {@value #QUIET_TICKS}×3 ticks later. A churn would move at least one of them, and
     * the copper would be in the racks above its maximum or on a crane.
     */
    private void assertNoChurn(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long[] now = churnNumbersNow(level, dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        if (now[0] != churnNumbers[0] || now[1] != churnNumbers[1] || now[2] != churnNumbers[2])
            throw new VisualTestException("the loop is churning: collected " + churnNumbers[0] + " -> " + now[0]
                    + ", handed over " + churnNumbers[1] + " -> " + now[1] + ", in the chest " + churnNumbers[2]
                    + " -> " + now[2]);
        if (now[2] != COPPER_SURPLUS)
            throw new VisualTestException("the chest holds " + now[2] + " copper, expected the surplus of "
                    + COPPER_SURPLUS);
        if (controller.countOf(COPPER) != COPPER_MAXIMUM)
            throw new VisualTestException("the racks hold " + controller.countOf(COPPER) + " copper, expected exactly "
                    + "the maximum of " + COPPER_MAXIMUM);
        Optional<NoJobReason> reason = controller.lastPlanReason();
        if (reason.isEmpty() || reason.get() != NoJobReason.AT_MAXIMUM)
            throw new VisualTestException("the aisle reports " + reason + ", expected " + NoJobReason.AT_MAXIMUM);
        assertCraneIdle(level, dock);
        census(server, "after the loop case settled");
        LOGGER.info(PREFIX + "collect-loop: CHECK 3 PASS: a maximum of {} plus an overflow whose hopper fills the very "
                + "chest the port reads settled at collected={} handedOver={} inTheChest={}, and stayed there for {} "
                + "ticks; the aisle says {}", COPPER_MAXIMUM, now[0], now[1], now[2], 3 * QUIET_TICKS,
                NoJobReason.AT_MAXIMUM);
    }

    // --- act 4 (server thread) ---------------------------------------------------------------------------------------

    private void fillWarehouse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (inputCount(level, dock, FILLER) != 0 || inputCount(level, dock, COPPER) != 0)
            throw new VisualTestException("the input still holds items, which act 4 must not start with");
        long added = 0;
        for (int x = STORAGE_FIRST; x <= STORAGE_LAST; x++)
            added += fill(level, storagePos(dock, x));
        insert(level, machinePos(dock, COLLECT_A_RACK), DIAMOND.toStack(DIAMONDS_IN_MACHINE));
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, FILLER, added), DIAMOND, DIAMONDS_IN_MACHINE);
        LOGGER.info(PREFIX + "collect-loop: the rack chests took {} more items and are full; {} diamonds wait in the "
                + "machine", added, DIAMONDS_IN_MACHINE);
    }

    /** Risk 3: nothing was picked at all, the aisle says why, and the port's own numbers say what is waiting. */
    private void assertFullWarehouse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long inMachine = countIn(level, machinePos(dock, COLLECT_A_RACK), DIAMOND);
        if (inMachine != DIAMONDS_IN_MACHINE)
            throw new VisualTestException("the machine holds " + inMachine + " diamonds, expected every one of "
                    + DIAMONDS_IN_MACHINE);
        if (controller.countOf(DIAMOND) != 0)
            throw new VisualTestException("the racks hold " + controller.countOf(DIAMOND)
                    + " diamonds although the warehouse is full");
        // Really full, not merely capped by a rule: no storage location of the aisle accepts a single diamond.
        for (int x = STORAGE_FIRST; x <= STORAGE_LAST; x++) {
            BlockPos chest = storagePos(dock, x);
            if (ItemHandlerHelper.insertItem(handler(level, chest), DIAMOND.toStack(1), true).isEmpty())
                throw new VisualTestException("the rack chest at " + chest + " still accepts a diamond, so the "
                        + "warehouse is not full");
        }
        Optional<NoJobReason> reason = controller.lastPlanReason();
        if (reason.isEmpty() || reason.get() != NoJobReason.WAREHOUSE_FULL)
            throw new VisualTestException("the aisle reports " + reason + ", expected " + NoJobReason.WAREHOUSE_FULL);
        // The live number behind the port's "Ready" line; the synced summary is only refreshed for an observer, which is
        // what the goggle shot after this check waits for.
        long ready = controller.collectableAt(layout(dock).rackPos(COLLECT_A_RACK));
        if (ready != DIAMONDS_IN_MACHINE)
            throw new VisualTestException("the aisle knows " + ready + " items are ready behind the port, expected "
                    + DIAMONDS_IN_MACHINE);
        if (!level.getEntitiesOfClass(ItemEntity.class, censusBox).isEmpty())
            throw new VisualTestException("something was dropped into the world");
        assertCraneIdle(level, dock);
        census(server, "while the warehouse is full");
        LOGGER.info(PREFIX + "collect-loop: CHECK 4 PASS: a full warehouse fetched nothing, the aisle says {}, and the "
                + "port says {} items are ready", NoJobReason.WAREHOUSE_FULL, ready);
    }

    /**
     * Makes room the way a player would: the filler is carried out of one rack chest, and <b>only</b> the filler — every
     * other item of the scene stays where it is, so nothing else changes and no stock rule is lifted by accident.
     */
    private void makeRoom(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos chest = storagePos(context.origin(), STORAGE_LAST);
        IItemHandler handler = handler(level, chest);
        long removed = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (FILLER.matches(handler.getStackInSlot(slot)))
                removed += handler.extractItem(slot, STACK, false).getCount();
        if (removed == 0)
            throw new VisualTestException("the rack chest at " + chest + " held no filler to carry out");
        expected = SceneItemCensus.plus(expected, FILLER, -removed);
        LOGGER.info(PREFIX + "collect-loop: {} filler items were carried out of the rack chest at {}", removed, chest);
    }

    private static boolean diamondsStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return controller(level, dock).countOf(DIAMOND) == DIAMONDS_IN_MACHINE
                && countIn(level, machinePos(dock, COLLECT_A_RACK), DIAMOND) == 0;
    }

    private void assertRoomUsed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long collected = port(level, dock, COLLECT_A_RACK).collectedItems();
        long expectedCollected = PLANK_MINIMUM + FAIRNESS_ITEMS + DIAMONDS_IN_MACHINE;
        if (collected != expectedCollected)
            throw new VisualTestException("the port counted " + collected + " collected items, expected "
                    + expectedCollected);
        assertCraneIdle(level, dock);
        census(server, "after the room was used");
        LOGGER.info(PREFIX + "collect-loop: CHECK 5 PASS: the first room made was used at once; the port has fetched "
                + "{} items in all", collected);
    }

    // --- shared server-side helpers ----------------------------------------------------------------------------------

    /** Whether every machine of the scene holds nothing, so a census counts every item of it. */
    private static boolean quiet(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane.craneState().phase() != CranePhase.IDLE || !crane.heldItems().isEmpty()
                || crane.currentJob().isPresent())
            return false;
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                dock.offset(CRAFTER));
        if (crafter == null || crafter.craftingItemPresent())
            return false;
        IItemHandler crafterInventory = level.getCapability(Capabilities.ItemHandler.BLOCK, dock.offset(CRAFTER), null);
        if (crafterInventory != null)
            for (int slot = 0; slot < crafterInventory.getSlots(); slot++)
                if (!crafterInventory.getStackInSlot(slot).isEmpty())
                    return false;
        return level.getEntitiesOfClass(ItemEntity.class, censusBoxOf(dock)).isEmpty();
    }

    private static void assertCraneIdle(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = crane(level, dock);
        if (crane.craneState().phase() != CranePhase.IDLE || !crane.heldItems().isEmpty()
                || crane.currentJob().isPresent())
            throw new VisualTestException("the crane is not idle and empty: " + crane.craneState().phase() + " job="
                    + crane.currentJob() + " held=" + crane.heldItems());
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEquals(level, censusBox, expected, "collect-loop: " + step);
    }

    /** The census box: the aisle, both rack walls with their inventories, and the whole machine cluster behind one. */
    private static AABB censusBoxOf(BlockPos dock) {
        return AABB.encapsulatingFullBlocks(dock.offset(CENSUS_MIN_X, CENSUS_MIN_Y, CENSUS_MIN_Z),
                dock.offset(CENSUS_MAX_X, CENSUS_MAX_Y, CENSUS_MAX_Z));
    }

    // --- the player --------------------------------------------------------------------------------------------------

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(5);
        player.teleportTo(server.overworld(), above.getX() + BLOCK_CENTER, above.getY(), above.getZ() + BLOCK_CENTER,
                player.getYRot(), player.getXRot());
    }

    /**
     * Lifts the player back into the air and switches creative flight on again before every group of camera views: a
     * creative player that touches the ground switches flying off by itself, and the next camera in mid-air would then
     * never arrive.
     */
    private static void fly(VisualScript script) {
        script.server("collect-loop: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("collect-loop: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    /** Flying, wearing Engineer's Goggles and holding nothing, so no item covers a corner of a GUI shot. */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, NAME, range);
    }

    // --- the goggle tooltips -----------------------------------------------------------------------------------------

    /**
     * A goggle shot of a collecting port: the lines that answer "does the port say so?" — what is waiting in the machine
     * right now, how much the port has fetched in all, and, when the warehouse refused the items, why (M18 review).
     *
     * @param refusal the reason the port's own gold line must name, or {@code null} when it must show none
     */
    private void goggles(VisualScript script, CameraView view, String label, RackPosition rack, long ready,
            @Nullable NoJobReason refusal) {
        reach(script, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, view, label, dock -> layout(dock).rackPos(rack),
                context -> collectSynced(context, rack, ready, refusal),
                context -> checkPortGoggles(context, rack, ready, refusal));
        reach(script, 0.0);
    }

    /** What the client's own copy of the port says it has fetched, i.e. the number the tooltip is about to draw. */
    private static long collectedOnClient(VisualContext context, RackPosition rack) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(layout(context.origin()).rackPos(rack))
                instanceof WarehouseOutputBlockEntity port))
            throw new VisualTestException("the client has no warehouse port at " + rack);
        return port.summary().collect().collected();
    }

    /**
     * A goggle shot of the <b>controller</b>: how many items the aisle holds and how many of its ports collect — the two
     * numbers that say what a machine loop achieved, on the block that knows them.
     */
    private void controllerGoggles(VisualScript script, long stored) {
        reach(script, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, AT_CONTROLLER, "goggles-controller",
                CollectLoopVisualScenario::controllerPos, context -> controllerSynced(context, stored),
                context -> checkControllerGoggles(context, stored));
        reach(script, 0.0);
    }

    private static boolean controllerSynced(VisualContext context, long stored) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        return level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller && controller.summary().totalItems() == stored
                && controller.summary().collectingPorts() == 2;
    }

    private static void checkControllerGoggles(VisualContext context, long stored) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        LOGGER.info(PREFIX + "collect-loop: controller goggles {}", lines);
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_COLLECTING_PORTS, 2));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_ITEMS_STORED, stored));
    }

    private static boolean collectSynced(VisualContext context, RackPosition rack, long ready,
            @Nullable NoJobReason refusal) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        return level.getBlockEntity(layout(context.origin()).rackPos(rack)) instanceof WarehouseOutputBlockEntity port
                && port.summary().collect().ready() == ready
                && port.summary().collect().refusalReason().orElse(null) == refusal;
    }

    private static void checkPortGoggles(VisualContext context, RackPosition rack, long ready,
            @Nullable NoJobReason refusal) {
        List<String> lines = GoggleShots.lines(context, layout(context.origin()).rackPos(rack));
        LOGGER.info(PREFIX + "collect-loop: goggles of the port at {}: {}", rack, lines);
        long collected = collectedOnClient(context, rack);
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_COLLECTING).getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PORT_COLLECT_READY, ready));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PORT_COLLECTED, collected));
        GoggleShots.requireLine(lines, Blocks.CHEST.getName().getString());
        // A collecting port has no rank magnitude at all, so neither accept line may ever appear on it.
        GoggleShots.requireNoLine(lines,
                WareworksLang.translate(WareworksLang.GOGGLES_PORT_OVERFLOW, "").component().getString().trim());
        // Why the warehouse did not take what is waiting — the line a player at their machine needs, and which must not
        // appear on a port that is being served (M18 review).
        String refusalPrefix = WareworksLang.translate(WareworksLang.GOGGLES_PORT_COLLECT_REFUSED, "").component()
                .getString().trim();
        if (refusal == null)
            GoggleShots.requireNoLine(lines, refusalPrefix);
        else
            GoggleShots.requireLine(lines, WareworksLang
                    .translate(WareworksLang.GOGGLES_PORT_COLLECT_REFUSED,
                            WareworksLang.translateDirect(WareworksLang.noJobReasonKey(refusal)))
                    .component().getString());
    }

    // --- moments (client thread, synced state) -----------------------------------------------------------------------

    /**
     * A shot of a state only the server can see: the ticks are frozen first, the claim is then asserted on the server in
     * that same moment, and the camera travels afterwards — so the picture and the assertion are about one tick, not
     * about the twenty a camera move takes.
     */
    private static void frozenShot(VisualScript script, String label, CameraView view,
            VisualScript.ServerAction check) {
        script.freeze(true)
                .server("collect-loop: check what the shot '" + label + "' claims", check)
                .camera(view)
                .shot(label)
                .freeze(false);
    }

    private static void moment(VisualScript script, String moment, Predicate<VisualContext> condition,
            Function<VisualContext, CameraView> view) {
        script.until("collect-loop: wait for the moment '" + moment + "'", condition, MOMENT_TIMEOUT_TICKS)
                .freeze(true)
                .camera(moment, view)
                .shot(moment)
                .freeze(false);
    }

    /**
     * One half of "the two ports take turns": the crane's arm inside the port at {@code served} while the machine behind
     * the {@code other} port still holds {@code otherKey} — the fairness claim of this act, frozen and asserted in the
     * tick the picture is taken in.
     */
    private void turnMoment(VisualScript script, String label, RackPosition served, RackPosition other,
            ItemKey otherKey) {
        script.until("collect-loop: wait until the crane serves the port at " + served,
                        context -> collectingAt(context, served), MOMENT_TIMEOUT_TICKS)
                .freeze(true)
                .server("collect-loop: check what the shot '" + label + "' claims", (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos dock = context.origin();
                    // The moments run before the act's own polling step, so this is also where their jobs join the
                    // record the fairness check reads.
                    recordCollectJob(level, dock);
                    long waiting = countIn(level, machinePos(dock, other), otherKey);
                    if (waiting <= 0)
                        throw new VisualTestException("the other machine at " + other + " is already empty, so this "
                                + "shot does not show one port being served while the other one waits");
                    LOGGER.info(PREFIX + "collect-loop: the crane serves the port at {} while {} holds {} items",
                            served, other, waiting);
                })
                .camera(label, CollectLoopVisualScenario::craneView)
                .shot(label)
                .freeze(false);
    }

    /** The crane's arm inside the port at {@code rack}, on a collect job: the one moment that is not storing. */
    private static boolean collectingAt(VisualContext context, RackPosition rack) {
        return clientCrane(context).filter(crane -> {
            CranePhase phase = crane.craneState().phase();
            return isCollectJob(crane) && (phase == CranePhase.EXTEND_SOURCE || phase == CranePhase.PICK)
                    && crane.craneState().pose().arm() >= ARM_EXTENDED_MIN
                    && Math.abs(crane.craneState().target().x() - rack.x()) <= TARGET_TOLERANCE;
        }).isPresent();
    }

    private static boolean carryingCollectedItems(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && isCollectJob(crane) && crane.goggleInfo().heldCount() > 0
                && remainingTravel(crane) > MIN_REMAINING_TRAVEL).isPresent();
    }

    private static boolean isCollectJob(StackerCraneBlockEntity crane) {
        return crane.goggleInfo().job().filter(job -> job.type() == JobType.COLLECT).isPresent();
    }

    private static double remainingTravel(StackerCraneBlockEntity crane) {
        return Math.max(Math.abs(crane.craneState().target().x() - crane.craneState().pose().x()),
                Math.abs(crane.craneState().target().y() - crane.craneState().pose().y()));
    }

    /** Behind the carriage in the empty half of the aisle, looking at the arm reaching through the port's face. */
    private static CameraView craneView(VisualContext context) {
        CranePose pose = clientPose(context);
        Direction armSide = pose.side() == Side.LEFT ? AISLE.getCounterClockWise() : AISLE.getClockWise();
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(CLOSE,
                craneX - AISLE.getStepX() * CLOSE_BEHIND - armSide.getStepX() * CLOSE_OFFSIDE,
                pose.y() + CLOSE_ABOVE,
                craneZ - AISLE.getStepZ() * CLOSE_BEHIND - armSide.getStepZ() * CLOSE_OFFSIDE,
                craneX + armSide.getStepX() * CLOSE_LOOK_ASIDE, pose.y() + ARM_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * CLOSE_LOOK_ASIDE);
    }

    /** Behind the travelling crane, looking along the aisle at the carriage and what it carries. */
    private static CameraView carryView(VisualContext context) {
        CranePose pose = clientPose(context);
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(CARRY, craneX + AISLE.getStepX() * CARRY_BEHIND, pose.y() + CARRY_ABOVE,
                craneZ + AISLE.getStepZ() * CARRY_BEHIND, craneX, pose.y() + CARRY_LOOK_HEIGHT, craneZ);
    }

    private static CranePose clientPose(VisualContext context) {
        return clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    /** The two collecting ports' synced numbers, for the shot log. */
    private static String clientPorts(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return "";
        StringBuilder text = new StringBuilder();
        for (RackPosition rack : List.of(COLLECT_A_RACK, COLLECT_B_RACK)) {
            String name = rack.equals(COLLECT_A_RACK) ? " portA=" : " portB=";
            if (level.getBlockEntity(layout(context.origin()).rackPos(rack)) instanceof WarehouseOutputBlockEntity port)
                text.append(name).append(port.summary().collect().collected()).append('/')
                        .append(port.summary().collect().ready());
            else
                text.append(name).append("missing");
        }
        return text.toString();
    }

    // --- world helpers -----------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    private static Direction towardsAisle(AisleLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    /** The inventory a port reaches into: the block behind it, i.e. away from the aisle. */
    private static BlockPos machinePos(BlockPos dock, RackPosition rack) {
        AisleLayout layout = layout(dock);
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    /** The chest of the storage location at aisle position {@code x}. */
    private static BlockPos storagePos(BlockPos dock, int x) {
        return machinePos(dock, RackPosition.of(x, 0, Side.LEFT));
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the warehouse controller of the aisle is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return crane;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock, RackPosition rack) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(dock).rackPos(rack));
        if (port == null)
            throw new VisualTestException("the warehouse port at " + rack + " is missing");
        return port;
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                layout(dock).rackPos(KEEPER_RACK));
        if (keeper == null)
            throw new VisualTestException("the stock keeper of the aisle is missing");
        return keeper;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static RequestFilterBehaviour filterOf(WarehouseOutputBlockEntity port) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
        if (!(behaviour instanceof RequestFilterBehaviour filter))
            throw new VisualTestException("the port at " + port.getBlockPos() + " has no request filter behaviour");
        return filter;
    }

    /** The crafter block state whose arrow points at {@code target} (looked up through Create, as the showcase does). */
    private static BlockState crafterState(Direction facing, Direction target) {
        for (Pointing pointing : Pointing.values()) {
            BlockState state = AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing)
                    .setValue(MechanicalCrafterBlock.POINTING, pointing);
            if (MechanicalCrafterBlock.getTargetDirection(state) == target)
                return state;
        }
        throw new VisualTestException("no crafter pointing makes a crafter facing " + facing + " target " + target);
    }

    private static IItemHandler handler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("the block at " + pos + " has no item handler");
        return handler;
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler(level, pos), stack, false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    /** Fills every slot of the inventory at {@code pos} with the filler item; answers how many items it took. */
    private static long fill(ServerLevel level, BlockPos pos) {
        IItemHandler handler = handler(level, pos);
        if (handler.getSlots() != CHEST_SLOTS)
            throw new VisualTestException("the inventory at " + pos + " has " + handler.getSlots() + " slots, expected "
                    + CHEST_SLOTS);
        long added = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            int accepted;
            do {
                ItemStack rest = handler.insertItem(slot, FILLER.toStack(STACK), false);
                accepted = STACK - rest.getCount();
                added += accepted;
            } while (accepted > 0);
        }
        return added;
    }

    private static long countIn(ServerLevel level, BlockPos pos, ItemKey key) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    /** What the input station of the aisle holds (its own buffer, not the racks). */
    private static long inputCount(ServerLevel level, BlockPos dock, ItemKey key) {
        return countIn(level, layout(dock).rackPos(INPUT_RACK), key);
    }

    /** What the production station holds, i.e. what the crane has supplied and the arm has not fetched yet. */
    private static long productionCount(ServerLevel level, BlockPos dock, ItemKey key) {
        return countIn(level, layout(dock).rackPos(PRODUCTION_RACK), key);
    }
}
