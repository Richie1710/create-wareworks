package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleChunkTickets;
import dev.wareworks.content.controller.ChunkKeepReason;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.HomePointStatus;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the <b>warehouse home point</b> and of a crane <b>returning home</b> (M21, issue #1, ADR-034,
 * {@code docs/stacker-crane.md} §4.7).
 * <p>
 * Most tests build the same L on the {@code aisle_pair_16x10x13} floor as the other M21 holders: the controller at
 * {@code x = 0}, the dock at {@code x = 1} facing east along {@code z = 3}, four rails to the corner at {@code (5, 3)}
 * and three more south of it. Aisle A runs east over positions 0..4, aisle B south over positions 0..3, and the corner
 * block is position 4 of A and position 0 of B at the same time.
 * <p>
 * What is pinned here and nowhere else:
 * <ul>
 * <li><b>A home point is served round a corner</b>: the crane drives from its dock, turns, and parks in front of a
 * block on another aisle — with the arm in and facing the way that aisle runs.</li>
 * <li><b>Breaking it falls back to the dock</b>, with no further rule and nothing left over.</li>
 * <li><b>A second home point is visibly refused</b>: a warehouse has one crane, so it has one home. The block says so
 * on its own lamp and in its own tooltip data rather than being quietly ignored.</li>
 * <li><b>A home point the crane cannot drive to is reported, not obeyed</b>: it is not handed to the dock, its red
 * lamp burns, and the crane keeps waiting where it stands.</li>
 * <li><b>The return is interrupted by a real job at any moment, mid-turn included</b>, and never delays the work.</li>
 * <li><b>A return keeps no chunk loaded</b>, even with chunk loading switched on: it is not a job, so a warehouse
 * whose only activity is a crane rolling home is still an idle warehouse.</li>
 * <li><b>A warehouse of one straight aisle behaves exactly as it did before M21</b>: the crane does not move a single
 * tick's worth, whatever a home point says, and the block reports that instead of pretending to work.</li>
 * </ul>
 * Every test overrides {@code crane.returnHomeIdleTicks} ({@link ConfigOverrides}), because the shipped default of ten
 * seconds is a player-facing comfort value and not something a test should wait out. Each override group therefore has
 * its own batch and an {@code @AfterBatch} restore.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class HomePointGameTests {
    static final String HOME_BATCH = "wareworkshomepoint";
    static final String UNREACHABLE_BATCH = "wareworkshomepointunreachable";
    static final String INTERRUPT_BATCH = "wareworkshomepointinterrupt";
    static final String CHUNK_BATCH = "wareworkshomepointchunks";

    private static final int TIMEOUT_TICKS = 900;
    private static final int LONG_TIMEOUT_TICKS = 1400;
    private static final int SETTLE_TICKS = 12;
    private static final int TEST_RPM = 128;
    /** Short enough for a test, long enough that the crane never leaves between two jobs of one queue. */
    private static final int SHORT_DELAY = 20;
    /** For the tests that must keep a resting crane exactly where it is while they rebuild the world around it. */
    private static final int NEVER_DELAY = 4000;
    /** A quarter turn worth four blocks of travel: twelve ticks at {@link #TEST_RPM}, long enough to interrupt. */
    private static final double SLOW_TURN_BLOCKS = 4.0;
    /** More aisles than any test needs, so the level cap never refuses a hold. */
    private static final int MANY_AISLES = 8;
    /** How long a single-aisle warehouse is watched for a movement that must never come. */
    private static final int WATCH_TICKS = 3 * SHORT_DELAY + 60;

    private static final int AISLE_Z = 3;
    /** Rails of aisle A: x = 2..5 at z = 3. Position 4 is the corner block. */
    private static final int FIRST_RAILS = 4;
    /** Rails of aisle B, south of the corner. */
    private static final int SECOND_RAILS = 3;

    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);
    private static final BlockPos CORNER = new BlockPos(1 + FIRST_RAILS, BASE_Y, AISLE_Z);

    /** Home point at position 2 on the <b>left</b> of aisle B: east of its rails, facing them. */
    private static final BlockPos HOME_LEFT = new BlockPos(6, BASE_Y, 5);
    /** Home point at position 2 on the <b>right</b> of aisle B: west of its rails, facing them. */
    private static final BlockPos HOME_RIGHT = new BlockPos(4, BASE_Y, 5);
    /** Home point at position 3 on the left of aisle B, the far end: the longest trip this L has. */
    private static final BlockPos HOME_FAR = new BlockPos(6, BASE_Y, 6);
    /** Home point at position 2 on the left of aisle A, for the unreachable case. */
    private static final BlockPos HOME_ON_A = new BlockPos(3, BASE_Y, 2);

    /** Input station at position 1 on the right of aisle A. */
    private static final BlockPos INPUT = new BlockPos(2, BASE_Y, 4);
    /** Storage location at position 1 on the left of aisle A, with its chest behind it. */
    private static final BlockPos NEAR_RACK = new BlockPos(2, BASE_Y, 2);
    private static final BlockPos NEAR_CHEST = new BlockPos(2, BASE_Y, 1);
    /** Storage location at position 3 on the right of aisle B, with its chest behind it. */
    private static final BlockPos FAR_RACK = new BlockPos(4, BASE_Y, 6);
    private static final BlockPos FAR_CHEST = new BlockPos(3, BASE_Y, 6);

    /** The rails of aisle B beyond position 1, broken to make the aisle shorter than the crane standing on it. */
    private static final List<BlockPos> AISLE_B_TAIL = List.of(new BlockPos(5, BASE_Y, 5), new BlockPos(5, BASE_Y, 6));

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final int STACK = 16;

    /** Single-aisle test: eight rails along +X, a home point at position 3 and storage at position 5. */
    private static final int SINGLE_RAILS = 8;
    private static final RackPosition SINGLE_HOME = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition SINGLE_STORAGE = new RackPosition(5, 0, Side.RIGHT);
    private static final RackPosition SINGLE_INPUT = new RackPosition(1, 0, Side.LEFT);

    private HomePointGameTests() {
    }

    // --- served round a corner ------------------------------------------------------------------------------------

    /**
     * The headline: a home point on the <b>second</b> aisle of an L is served. The crane leaves its dock by itself
     * after the idle delay, drives down aisle A, turns the corner and parks in front of the block — on a branch no
     * straight warehouse has, with the arm in and facing the way aisle B runs, which is what makes the next job start
     * without an extra turn.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = HOME_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void homepointservedonanl(GameTestHelper helper) {
        buildCorner(helper);
        placeHomePoint(helper, HOME_LEFT, Direction.WEST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_LEFT);
            return;
        }
        Map<ItemKey, Long> conserved = new HashMap<>();

        helper.startSequence()
                .thenExecute(() -> shortDelay(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the warehouse bends"))
                .thenExecute(() -> conserved.putAll(ItemCensus.take(helper)))
                .thenWaitUntil(() -> {
                    RackPosition home = rackOf(helper, HOME_LEFT);
                    helper.assertValueEqual(controller(helper).homePoint(), Optional.of(home),
                            "the controller uses the home point");
                    helper.assertValueEqual(dock(helper).homePoint(), Optional.of(home),
                            "and hands it to the dock");
                    helper.assertValueEqual(homePoint(helper, HOME_LEFT).status(), HomePointStatus.SERVING,
                            "the block says the crane waits here");
                    helper.assertBlockProperty(HOME_LEFT, WarehouseHomePointBlock.LIT, true);
                    helper.assertBlockProperty(HOME_LEFT, WarehouseHomePointBlock.REFUSED, false);
                })
                .thenWaitUntil(() -> assertParkedAt(helper, rackOf(helper, HOME_LEFT), Direction.SOUTH))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    assertParkedAt(helper, rackOf(helper, HOME_LEFT), Direction.SOUTH);
                    helper.assertTrue(dock(helper).currentJob().isEmpty(), "it parked without a job");
                    ItemCensus.assertEquals(helper, conserved, "driving home moves no item");
                })
                .thenSucceed();
    }

    /**
     * Breaking the home point falls back to the dock — the home a crane has always had — and the crane really drives
     * back there. Nothing else has to happen: "no home point" and "the dock is home" are the same statement.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = HOME_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void homepointbrokenfallsbacktothedock(GameTestHelper helper) {
        buildCorner(helper);
        placeHomePoint(helper, HOME_LEFT, Direction.WEST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_LEFT);
            return;
        }

        helper.startSequence()
                .thenExecute(() -> shortDelay(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the warehouse bends"))
                .thenWaitUntil(() -> assertParkedAt(helper, rackOf(helper, HOME_LEFT), Direction.SOUTH))
                .thenExecute(() -> helper.getLevel().destroyBlock(helper.absolutePos(HOME_LEFT), false))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper).homePoint(), Optional.empty(),
                            "the warehouse has no home point any more");
                    helper.assertValueEqual(dock(helper).homePoint(), Optional.empty(), "and the dock knows");
                })
                .thenWaitUntil(() -> assertParkedAtTheDock(helper))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    assertParkedAtTheDock(helper);
                    helper.assertTrue(dock(helper).currentJob().isEmpty(), "and it is still idle");
                })
                .thenSucceed();
    }

    /**
     * A warehouse has one crane, so it has one home. The <b>first</b> home point in {@code RackPosition.ORDER} is used
     * and every further one is <b>visibly refused</b>: its red lamp burns and its own goggle data says why, which is
     * the whole difference between a rule and a block that does nothing for no reason.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = HOME_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void homepointsecondisrefused(GameTestHelper helper) {
        buildCorner(helper);
        placeHomePoint(helper, HOME_LEFT, Direction.WEST);
        placeHomePoint(helper, HOME_RIGHT, Direction.EAST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_LEFT);
            return;
        }
        AtomicReference<BlockPos> used = new AtomicReference<>();
        AtomicReference<BlockPos> refused = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> shortDelay(helper))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    List<LocationRecord> homes = controller(helper).locations().stream()
                            .filter(record -> record.kind() == LocationKind.HOME).toList();
                    helper.assertValueEqual(homes.size(), 2, "both home points joined the warehouse");
                })
                .thenExecute(() -> {
                    // Which of the two comes first is decided by RackPosition.ORDER, so the test derives it rather
                    // than assuming it: the assertion is the rule, not one particular pair of blocks.
                    RackPosition left = rackOf(helper, HOME_LEFT);
                    RackPosition right = rackOf(helper, HOME_RIGHT);
                    boolean leftFirst = RackPosition.ORDER.compare(left, right) < 0;
                    used.set(leftFirst ? HOME_LEFT : HOME_RIGHT);
                    refused.set(leftFirst ? HOME_RIGHT : HOME_LEFT);
                    helper.assertValueEqual(controller(helper).homePoint(),
                            Optional.of(leftFirst ? left : right), "the first home point is the one in use");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(homePoint(helper, used.get()).status(), HomePointStatus.SERVING,
                            "the first one serves");
                    helper.assertValueEqual(homePoint(helper, refused.get()).status(), HomePointStatus.SECOND,
                            "and the second one says it is a second one");
                    helper.assertBlockProperty(used.get(), WarehouseHomePointBlock.LIT, true);
                    helper.assertBlockProperty(used.get(), WarehouseHomePointBlock.REFUSED, false);
                    // Visibly refused, which is the requirement: the red lamp of everything a player has to act on.
                    helper.assertBlockProperty(refused.get(), WarehouseHomePointBlock.REFUSED, true);
                    helper.assertBlockProperty(refused.get(), WarehouseHomePointBlock.LIT, false);
                })
                .thenWaitUntil(() -> assertParkedAt(helper, rackOf(helper, used.get()), Direction.SOUTH))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    // And the refused one really is not a second home: the crane stays at the first.
                    assertParkedAt(helper, rackOf(helper, used.get()), Direction.SOUTH);
                    helper.assertValueEqual(homePoint(helper, refused.get()).status(), HomePointStatus.SECOND,
                            "the second one is still refused");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = HOME_BATCH)
    public static void restoreHomeConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- unreachable ----------------------------------------------------------------------------------------------

    /**
     * A home point the crane cannot drive to is <b>reported, not obeyed</b>.
     * <p>
     * The situation is the one the M21 review named: the crane stands on aisle B at position 3, a player breaks the
     * rails behind it, and aisle B is now shorter than the machine's own position — so there is no route from where it
     * stands to anywhere else, which is exactly the question {@code RouteTable#canDrive} answers. The motor is taken
     * away first, so the machine really stays where it is instead of rolling back onto the rails while the test looks.
     * The home point on aisle A then burns red and is not handed to the dock, and the crane waits where it is rather
     * than pushing against rails that do not join.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = UNREACHABLE_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void homepointonanunreachableaislereported(GameTestHelper helper) {
        buildCorner(helper);
        placeInput(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_ON_A);
            return;
        }
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "an unreachable home point"));

        helper.startSequence()
                // Long enough that the crane never leaves the far end of aisle B while this test rebuilds around it.
                .thenExecute(() -> ConfigOverrides.set(helper, WareworksConfig.SERVER.returnHomeIdleTicks,
                        NEVER_DELAY))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).storageLocations().size(), 1, "the far rack joined");
                })
                .thenExecute(() -> {
                    insertAll(helper, INPUT, IRON.toStack(STACK));
                    ItemCensus.change(expected, IRON, STACK);
                })
                // The store job takes the crane round the corner to position 3 of aisle B, where it then rests.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(storedAt(helper, FAR_CHEST, IRON), (long) STACK, "the stack was stored");
                    CranePose pose = dock(helper).craneState().pose();
                    helper.assertValueEqual(pose.branch(), 1, "the crane rests on aisle B");
                    helper.assertValueEqual(pose.x(), (double) SECOND_RAILS, "at its far end");
                    helper.assertValueEqual(dock(helper).craneState().phase(), CranePhase.IDLE, "and is idle");
                })
                .thenExecute(() -> {
                    // No rotation: the machine cannot move at all from here on, so what the test asserts is stable.
                    motor(helper).generatedSpeed.setValue(0);
                    placeHomePoint(helper, HOME_ON_A, Direction.SOUTH);
                    for (BlockPos rail : AISLE_B_TAIL)
                        helper.getLevel().destroyBlock(helper.absolutePos(rail), false);
                })
                .thenWaitUntil(() -> {
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 2, "both aisles are still there");
                    helper.assertValueEqual(warehouse.branch(1).geometry().length(), 1,
                            "but aisle B is shorter than the crane standing on it");
                    helper.assertValueEqual(dock(helper).craneState().pose().x(), (double) SECOND_RAILS,
                            "and the machine has not moved");
                })
                .thenWaitUntil(() -> {
                    BlockPos home = helper.absolutePos(HOME_ON_A);
                    helper.assertValueEqual(controller(helper).homePointStatusAt(home),
                            HomePointStatus.UNREACHABLE, "the home point cannot be reached");
                    helper.assertValueEqual(controller(helper).homePoint(), Optional.empty(),
                            "so it is not obeyed");
                    helper.assertValueEqual(dock(helper).homePoint(), Optional.empty(),
                            "and the dock is not told to go there");
                    helper.assertValueEqual(homePoint(helper, HOME_ON_A).status(), HomePointStatus.UNREACHABLE,
                            "the block itself says so");
                    helper.assertBlockProperty(HOME_ON_A, WarehouseHomePointBlock.REFUSED, true);
                    helper.assertBlockProperty(HOME_ON_A, WarehouseHomePointBlock.LIT, false);
                })
                .thenSucceed();
    }

    @AfterBatch(batch = UNREACHABLE_BATCH)
    public static void restoreUnreachableConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- interrupted by work --------------------------------------------------------------------------------------

    /**
     * A return home is interrupted by a real job <b>at any moment, mid-turn included</b>, and never delays the work.
     * <p>
     * The turn is slowed down to four blocks' worth of travel so that the interruption really lands inside it: the test
     * waits for a yaw that is strictly between the two aisle headings, puts items into the input in that very tick,
     * and then asserts that the crane took the job, that it never arrived at its home point, and that the items were
     * stored all the same.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = INTERRUPT_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void homepointreturninterruptedbyarealjob(GameTestHelper helper) {
        buildCorner(helper);
        placeInput(helper);
        placeStorage(helper, NEAR_RACK, NEAR_CHEST, Direction.NORTH);
        placeHomePoint(helper, HOME_FAR, Direction.WEST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_FAR);
            return;
        }
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "an interrupted return"));
        AtomicBoolean watching = new AtomicBoolean();
        AtomicBoolean arrivedHome = new AtomicBoolean();
        AtomicReference<CranePose> atInterruption = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> {
                    shortDelay(helper);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.turnPenaltyBlocks, SLOW_TURN_BLOCKS);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).storageLocations().size(), 1, "the near rack joined");
                })
                // The crane is at its dock and drives off towards the home point once the delay is up. From this tick
                // on, every single tick is watched for an arrival that must not happen.
                .thenWaitUntil(() -> helper.assertTrue(
                        dock(helper).craneState().target().branch() == 1
                                || dock(helper).craneState().pose().x() > 0.0,
                        "the crane set off towards its home point"))
                .thenExecute(() -> {
                    RackPosition home = rackOf(helper, HOME_FAR);
                    watching.set(true);
                    helper.onEachTick(() -> {
                        if (!watching.get())
                            return;
                        CranePose pose = dock(helper).craneState().pose();
                        if (pose.branch() == home.branch() && pose.x() == home.x())
                            arrivedHome.set(true);
                    });
                })
                // Mid-turn: the yaw is strictly between east (1) and south (2) while the machine swings at the corner.
                .thenWaitUntil(() -> {
                    double yaw = dock(helper).craneState().pose().yaw();
                    helper.assertTrue(yaw > CranePose.yawOf(dev.wareworks.core.address.Heading.EAST)
                            && yaw < CranePose.yawOf(dev.wareworks.core.address.Heading.SOUTH),
                            "the machine is in the middle of its quarter turn (yaw " + yaw + ")");
                })
                .thenExecute(() -> {
                    atInterruption.set(dock(helper).craneState().pose());
                    insertAll(helper, INPUT, IRON.toStack(STACK));
                    ItemCensus.change(expected, IRON, STACK);
                })
                // The job is planned on the controller's next dispatch tick and accepted at once: an idle crane on its
                // way home is an idle crane.
                .thenWaitUntil(() -> helper.assertTrue(dock(helper).currentJob().isPresent(),
                        "the crane took the job while it was driving home"))
                .thenExecute(() -> {
                    helper.assertFalse(arrivedHome.get(), "and it never got to its home point first");
                    CranePose pose = atInterruption.get();
                    helper.assertTrue(pose.yaw() % 1.0 != 0.0,
                            "the interruption really landed inside the turn (yaw " + pose.yaw() + ")");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(storedAt(helper, NEAR_CHEST, IRON), (long) STACK,
                            "the work was done, and done first");
                    helper.assertTrue(dock(helper).currentJob().isEmpty(), "the job is finished");
                })
                // Only then does it go home, which is the other half of "the return never delays work".
                .thenExecute(() -> watching.set(false))
                .thenWaitUntil(() -> assertParkedAt(helper, rackOf(helper, HOME_FAR), Direction.SOUTH))
                .thenSucceed();
    }

    @AfterBatch(batch = INTERRUPT_BATCH)
    public static void restoreInterruptConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- chunks ---------------------------------------------------------------------------------------------------

    /**
     * A crane driving home holds <b>no</b> chunk, with chunk loading switched on and the level cap wide open: the
     * return is not a {@code TransportJob}, so a warehouse whose only activity is a machine rolling back to its home
     * point is still an idle warehouse and still lets its chunks go (ADR-031, ADR-034).
     * <p>
     * Asserted on <b>every tick</b> of the whole trip, so a hold that appeared for a single tick would fail it. The
     * trip really happens: the test ends by asserting that the crane arrived.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = CHUNK_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void homepointreturnkeepsnochunksloaded(GameTestHelper helper) {
        buildCorner(helper);
        placeHomePoint(helper, HOME_FAR, Direction.WEST);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper, HOME_FAR);
            return;
        }

        helper.startSequence()
                .thenExecute(() -> {
                    shortDelay(helper);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the warehouse bends"))
                .thenExecute(() -> helper.onEachTick(() -> {
                    helper.assertValueEqual(heldChunks(helper), 0, "a crane driving home holds no chunk");
                    helper.assertValueEqual(controller(helper).chunkKeepReason(), ChunkKeepReason.NONE,
                            "and the warehouse reports nothing to hold them for");
                    helper.assertTrue(dock(helper).currentJob().isEmpty(), "because a return is not a job");
                }))
                .thenWaitUntil(() -> assertParkedAt(helper, rackOf(helper, HOME_FAR), Direction.SOUTH))
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(heldChunks(helper), 0,
                        "and a crane that arrived holds none either"))
                .thenSucceed();
    }

    @AfterBatch(batch = CHUNK_BATCH)
    public static void restoreChunkConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- one straight aisle ---------------------------------------------------------------------------------------

    /**
     * The regression oracle of the whole feature: on a warehouse of <b>one straight aisle</b> nothing returns, whatever
     * a home point says. The crane is sent to a rack far from its dock by a real job and then watched for three times
     * the idle delay — its pose must be the same on every one of those ticks, down to the last bit. The home point
     * reports that it has no effect here instead of pretending to work, and neither of its lamps burns.
     */
    @GameTest(template = AISLE_16X10X7, batch = HOME_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void homepointonasinglestraightaisleneverreturns(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, SINGLE_RAILS);
        aisle.build(true);
        aisle.storage(SINGLE_STORAGE);
        aisle.input(SINGLE_INPUT);
        aisle.homePoint(SINGLE_HOME);
        Map<ItemKey, Long> expected = ItemCensus.of();
        AtomicReference<CranePose> resting = new AtomicReference<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "one straight aisle");
            CranePose parked = resting.get();
            if (parked != null)
                helper.assertValueEqual(aisle.dock().craneState().pose(), parked,
                        "a crane on one straight aisle never moves once it is idle");
        });

        helper.startSequence()
                .thenExecute(() -> shortDelay(helper))
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 1, 0);
                    helper.assertValueEqual(aisle.controller().warehouse()
                            .map(WarehouseLayout::branchCount).orElse(0), 1, "one aisle, exactly as before M21");
                    helper.assertValueEqual(aisle.homePointAt(SINGLE_HOME).status(), HomePointStatus.SINGLE_AISLE,
                            "and the home point says it has no effect here");
                    helper.assertValueEqual(aisle.controller().homePoint(), Optional.empty(),
                            "so the warehouse uses none");
                    helper.assertBlockProperty(aisle.rackPos(SINGLE_HOME), WarehouseHomePointBlock.LIT, false);
                    helper.assertBlockProperty(aisle.rackPos(SINGLE_HOME), WarehouseHomePointBlock.REFUSED, false);
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(SINGLE_INPUT)), IRON.toStack(STACK));
                    ItemCensus.change(expected, IRON, STACK);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(SINGLE_STORAGE, IRON), (long) STACK, "the stack is stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    CranePose pose = aisle.dock().craneState().pose();
                    helper.assertValueEqual(pose.x(), (double) SINGLE_STORAGE.x(),
                            "the crane rests where its last job ended, away from the dock");
                    // From here on every tick compares against exactly this pose.
                    resting.set(pose);
                })
                .thenExecuteAfter(WATCH_TICKS, () -> {
                    helper.assertValueEqual(aisle.dock().craneState().pose(), resting.get(),
                            "and it is still there after three idle delays");
                    helper.assertValueEqual(aisle.homePointAt(SINGLE_HOME).status(), HomePointStatus.SINGLE_AISLE,
                            "with the home point still saying why");
                })
                .thenSucceed();
    }

    // --- the off switch -------------------------------------------------------------------------------------------

    /**
     * Whether a warehouse may bend at all: {@code aisle.maxBranches = 1} keeps every warehouse the single straight
     * aisle it was before 0.6, and this suite has to pass with it unedited.
     */
    private static boolean cornersEnabled() {
        return WareworksConfig.maxBranches() > 1;
    }

    /**
     * What every bent test asserts instead while corners are switched off: the warehouse is one straight aisle, the
     * home point beyond the corner belongs to nothing at all, and the crane never leaves the aisle at the dock.
     */
    private static void assertCornersAreOff(GameTestHelper helper, BlockPos home) {
        helper.startSequence()
                .thenExecute(() -> shortDelay(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                        "one aisle, exactly as before 0.6"))
                .thenExecute(() -> helper.onEachTick(() -> helper.assertValueEqual(
                        dock(helper).craneState().pose().branch(), 0,
                        "the crane never leaves the aisle at the dock")))
                .thenExecuteAfter(WATCH_TICKS, () -> {
                    if (helper.getLevel().getBlockEntity(helper.absolutePos(home))
                            instanceof WarehouseHomePointBlockEntity block)
                        helper.assertValueEqual(block.status(), HomePointStatus.NO_WAREHOUSE,
                                "a home point beyond the corner is part of nothing");
                })
                .thenSucceed();
    }

    // --- building -------------------------------------------------------------------------------------------------

    /** Motor, dock, controller, aisle A's rails and aisle B running south out of the corner block. */
    private static void buildCorner(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        for (int x = 1; x <= FIRST_RAILS; x++)
            helper.setBlock(DOCK.east(x), WarehouseRailBlock.along(Direction.Axis.X));
        for (int z = 1; z <= SECOND_RAILS; z++)
            helper.setBlock(CORNER.south(z), WarehouseRailBlock.along(Direction.Axis.Z));
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        motor(helper).generatedSpeed.setValue(TEST_RPM);
    }

    private static void shortDelay(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.returnHomeIdleTicks, SHORT_DELAY);
    }

    /** A home point at {@code pos} whose plate faces {@code towardsTheAisle}. */
    private static void placeHomePoint(GameTestHelper helper, BlockPos pos, Direction towardsTheAisle) {
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                .setValue(WarehouseHomePointBlock.FACING, towardsTheAisle));
    }

    private static void placeInput(GameTestHelper helper) {
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.NORTH));
    }

    /** A chest with {@code contents} behind an interface facing {@code away} from the rails. */
    private static void placeStorage(GameTestHelper helper, BlockPos rack, BlockPos chest, Direction away,
                                     ItemStack... contents) {
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = handlerAt(helper, chest);
        for (ItemStack stack : contents)
            ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, away));
    }

    // --- reading --------------------------------------------------------------------------------------------------

    /**
     * The crane stands exactly in front of {@code home}: that aisle, that position, that level, the arm in, and facing
     * the way the aisle runs. Everything a parked crane is.
     */
    private static void assertParkedAt(GameTestHelper helper, RackPosition home, Direction heading) {
        CranePose pose = dock(helper).craneState().pose();
        helper.assertValueEqual(pose.branch(), home.branch(), "the aisle the crane waits on");
        helper.assertValueEqual(pose.x(), (double) home.x(), "the position it waits at");
        helper.assertValueEqual(pose.y(), (double) home.y(), "the level it waits at");
        helper.assertValueEqual(pose.arm(), CranePose.RETRACTED, "the arm is in");
        helper.assertValueEqual(pose.yaw(), CranePose.yawOf(dev.wareworks.util.Headings.of(heading)),
                "and it faces the way its aisle runs");
        helper.assertValueEqual(dock(helper).craneState().phase(), CranePhase.IDLE, "and it is idle");
    }

    /** The crane stands at position 0 of the aisle at the dock, which is the home of a warehouse without a home point. */
    private static void assertParkedAtTheDock(GameTestHelper helper) {
        CranePose pose = dock(helper).craneState().pose();
        helper.assertValueEqual(pose.branch(), 0, "the crane is back on the aisle at its dock");
        helper.assertValueEqual(pose.x(), 0.0, "at position 0");
        helper.assertValueEqual(pose.y(), 0.0, "at dock level");
        helper.assertValueEqual(pose.arm(), CranePose.RETRACTED, "with the arm in");
        helper.assertValueEqual(pose.yaw(), CranePose.yawOf(dev.wareworks.util.Headings.of(Direction.EAST)),
                "facing the way that aisle runs");
    }

    private static int heldChunks(GameTestHelper helper) {
        return AisleChunkTickets.heldChunkCount(helper.getLevel(), helper.absolutePos(CONTROLLER));
    }

    private static WarehouseControllerBlockEntity controller(GameTestHelper helper) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(CONTROLLER));
        if (be == null) {
            helper.fail("missing warehouse controller", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static StackerCraneBlockEntity dock(GameTestHelper helper) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(DOCK));
        if (be == null) {
            helper.fail("missing stacker crane dock", DOCK);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static CreativeMotorBlockEntity motor(GameTestHelper helper) {
        CreativeMotorBlockEntity be = com.simibubi.create.AllBlockEntityTypes.MOTOR
                .getNullable(helper.getLevel(), helper.absolutePos(MOTOR));
        if (be == null) {
            helper.fail("missing creative motor", MOTOR);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static WarehouseHomePointBlockEntity homePoint(GameTestHelper helper, BlockPos pos) {
        WarehouseHomePointBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_HOME_POINT
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing warehouse home point", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static WarehouseLayout warehouse(GameTestHelper helper) {
        WarehouseLayout layout = controller(helper).warehouse().orElse(null);
        if (layout == null) {
            helper.fail("the controller has no warehouse", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return layout;
    }

    /** The rack position of a test-relative world block, as the warehouse names it. */
    private static RackPosition rackOf(GameTestHelper helper, BlockPos pos) {
        LocationRecord record = controller(helper).locationAt(helper.absolutePos(pos)).orElse(null);
        if (record == null) {
            helper.fail("no warehouse member", pos);
            throw new AssertionError("unreachable");
        }
        return record.position();
    }

    private static void insertAll(GameTestHelper helper, BlockPos pos, ItemStack stack) {
        ItemStack left = ItemHandlerHelper.insertItem(handlerAt(helper, pos), stack.copy(), false);
        helper.assertTrue(left.isEmpty(), "everything fits into " + pos + ", " + left.getCount() + " left over");
    }

    private static long storedAt(GameTestHelper helper, BlockPos chest, ItemKey key) {
        IItemHandler handler = handlerAt(helper, chest);
        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && ItemKey.of(stack).equals(key))
                count += stack.getCount();
        }
        return count;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            throw new AssertionError("unreachable");
        }
        return handler;
    }

}
