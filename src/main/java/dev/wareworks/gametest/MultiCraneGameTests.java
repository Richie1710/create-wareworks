package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.CraneMember;
import dev.wareworks.content.controller.CraneRoster;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneMembership;
import dev.wareworks.content.crane.CraneTrafficClaim;
import dev.wareworks.content.crane.CraneTrafficInfo;
import dev.wareworks.content.crane.DockJoinStatus;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.HomePointGoggleSummary;
import dev.wareworks.content.station.HomePointStatus;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalScreenStatus;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.traffic.TrafficVerdict;
import dev.wareworks.core.traffic.TrafficWait;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.CraneTrafficLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of several stacker cranes on one warehouse (issue #14, ADR-075, {@code docs/stacker-crane.md} §4.8): a
 * dock without a controller of its own that faces into a warehouse's rails joins it as another crane, the warehouse
 * gives each job to the nearest free crane, and a crane reserves every rail up to its next stop before it moves.
 * <p>
 * Most tests build one straight line on {@code aisle_16x10x7}: the controller at x = 0, the warehouse's own dock at
 * x = 1, rails at x = 2..14 and a <b>second dock at x = 15 facing back</b>, so the line has positions 0..14 and crane 2
 * stands on position 14. The comb, the ring and the two copies of the determinism test use {@code aisle_pair_16x10x13}.
 * <p>
 * Every test that moves items takes an {@link ItemCensus} on every tick and asserts on every tick that no two cranes
 * stand on one block. A test that needs several cranes also runs with {@code aisle.maxCranesPerNetwork = 1} (the second
 * {@code runGameTestServer} pass of the milestone's gates): it then asserts that the second dock is the wall it was up
 * to 0.17.1 and stops there, which is what proves the off switch.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class MultiCraneGameTests {
    /** Config overrides need their own batch, because the tests of one batch run at the same time. */
    static final String CAP_BATCH = "wareworksmulticranecap";
    static final String LEGACY_BATCH = "wareworksmulticranelegacy";
    static final String PORT_BATCH = "wareworksmulticraneport";
    static final String LOWERED_CAP_BATCH = "wareworksmulticraneloweredcap";

    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int LONG_TIMEOUT_TICKS = 3200;
    private static final int SETTLE_TICKS = 10;
    private static final int RAILS = 13;
    /** The line of most tests: positions 0..14, crane 2's dock on position 14. */
    private static final Line LINE = new Line(3, RAILS);
    /** Where the member dock stands on {@link #LINE}. */
    private static final int MEMBER_X = RAILS + 1;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey NAIL = ItemKey.of(Items.IRON_NUGGET);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final int COUNT = 16;
    private static final int STACK = 64;
    private static final int HALF = 32;
    /** What {@link #reducedrequestontwocranesbringsonlywhatisleft} takes off its request of {@code 2 * HALF}. */
    private static final int REDUCED_BY = 24;

    private MultiCraneGameTests() {
    }

    // --- joining -----------------------------------------------------------------------------------------------------

    /** A second dock at the far end of the line, facing back, is crane 2 of the warehouse, named in its frame. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void seconddockjoinsasacrane(GameTestHelper helper) {
        build(helper, LINE, true);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertJoined(helper, LINE))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper, LINE);
                    List<CraneMember> members = controller.craneMembers();
                    helper.assertValueEqual(members.size(), 2, "two cranes listed");
                    helper.assertValueEqual(members.get(0), CraneMember.primary(helper.absolutePos(LINE.dock())),
                            "crane 1 is the one at the controller's own dock");
                    helper.assertValueEqual(members.get(1), new CraneMember(2, helper.absolutePos(LINE.memberDock()),
                            RackPosition.FIRST_BRANCH, MEMBER_X, false), "crane 2 is the far dock, at the line's end");
                    StackerCraneBlockEntity primary = crane(helper, LINE.dock());
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertValueEqual(primary.craneNumber(), 1, "the primary's number");
                    helper.assertFalse(primary.isMember(), "the primary is no member");
                    helper.assertValueEqual(member.craneNumber(), 2, "the member's number");
                    CraneMembership frame = member.membership().orElseThrow();
                    helper.assertValueEqual(frame.anchor(), helper.absolutePos(LINE.dock()),
                            "the member's frame is anchored at the warehouse's own dock");
                    helper.assertValueEqual(frame.anchorFacing(), Direction.EAST, "running the way that dock faces");
                    helper.assertValueEqual(frame.siteX(), MEMBER_X, "its own dock is the last position");
                    CranePose pose = member.craneState().pose();
                    helper.assertValueEqual(pose.branch(), RackPosition.FIRST_BRANCH, "its machine stands on the line");
                    helper.assertValueEqual(pose.x(), (double) MEMBER_X, "on its own dock block");
                    helper.assertTrue(member.isControllerLinked(), "linked to the warehouse's controller");
                    helper.assertTrue(controller.isLinkedTo(helper.absolutePos(LINE.memberDock())),
                            "and the controller lists it");
                    RailNetwork network = primary.discoveredNetwork().orElseThrow();
                    helper.assertValueEqual(network.memberDocks().size(), 1, "the scan took one member dock");
                    helper.assertValueEqual(network.refusedDocks(), 0, "and refused none");
                    helper.assertValueEqual(network.stop(), NetworkStop.END, "the rails simply end");
                    helper.assertValueEqual(network.firstBranchLength(), MEMBER_X,
                            "the line runs up to the member dock");
                })
                .thenSucceed();
    }

    /** With room for two cranes, a third dock is a wall that the discovery names with its key. */
    @GameTest(template = AISLE_16X10X7, batch = CAP_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void dockbeyondthemaximumisrefused(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 2);
        build(helper, LINE, true);
        // A third dock beside position 7, facing into the line: it is reached first, so it is crane 2.
        BlockPos side = LINE.rack(7, Side.LEFT);
        placeMotor(helper, side.below());
        helper.setBlock(side, dockState(Direction.SOUTH));
        motor(helper, side.below()).generatedSpeed.setValue(TEST_RPM);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper, LINE);
                    helper.assertValueEqual(controller.craneCount(), 2, "two cranes, the maximum");
                    helper.assertTrue(crane(helper, side).isMember(), "the dock beside the line joined");
                })
                .thenExecute(() -> {
                    RailNetwork network = crane(helper, LINE.dock()).discoveredNetwork().orElseThrow();
                    helper.assertValueEqual(network.stop(), NetworkStop.MAX_CRANES, "the cap is named");
                    helper.assertValueEqual(helper.absolutePos(LINE.dock()).offset(network.stopDx(), 0,
                            network.stopDz()), helper.absolutePos(LINE.memberDock()),
                            "at the dock it refused");
                    helper.assertValueEqual(network.refusedDocks(), 1, "one dock refused");
                    helper.assertTrue(crane(helper, LINE.memberDock()).membership().isEmpty(),
                            "the refused dock is no crane of the warehouse");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = CAP_BATCH)
    public static void restoreCapConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** A dock with its own controller is another warehouse and a wall for this one, exactly as before. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void dockwithitsowncontrollerstaysawall(GameTestHelper helper) {
        Line line = new Line(LINE.z(), RAILS - 1);
        build(helper, line, true);
        BlockPos otherController = line.memberDock().east();
        helper.setBlock(otherController, controllerState(Direction.WEST));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper, line).status(), ControllerStatus.READY, "ready");
                    helper.assertValueEqual(controllerAt(helper, otherController).status(), ControllerStatus.READY,
                            "and so is the other warehouse");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    RailNetwork network = crane(helper, line.dock()).discoveredNetwork().orElseThrow();
                    helper.assertValueEqual(network.stop(), NetworkStop.SECOND_DOCK, "the other dock is a wall");
                    helper.assertTrue(network.memberDocks().isEmpty(), "and no member");
                    helper.assertValueEqual(controller(helper, line).craneCount(), 1, "one crane here");
                    helper.assertValueEqual(controllerAt(helper, otherController).craneCount(), 1, "one crane there");
                    helper.assertTrue(crane(helper, line.memberDock()).membership().isEmpty(),
                            "the other dock is no member");
                    helper.assertTrue(crane(helper, line.dock()).membership().isEmpty(),
                            "and nothing of several cranes exists on either");
                })
                .thenSucceed();
    }

    // --- working together --------------------------------------------------------------------------------------------

    /** Requests at both ends: both cranes work at the same time and never share a block. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twocranesworkbothendsatonce(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 2, Side.LEFT, IRON.toStack(COUNT));
        output(helper, LINE.rack(1, Side.RIGHT), Side.RIGHT);
        storage(helper, LINE, 12, Side.LEFT, GOLD.toStack(COUNT));
        output(helper, LINE.rack(13, Side.RIGHT), Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicBoolean together = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (busy(helper, LINE.dock()) && busy(helper, LINE.memberDock()))
                together.set(true);
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    request(helper, LINE, LINE.rack(1, Side.RIGHT), IRON, COUNT);
                    request(helper, LINE, LINE.rack(13, Side.RIGHT), GOLD, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, LINE.rack(1, Side.RIGHT), IRON), (long) COUNT,
                            "the iron arrived in the west");
                    helper.assertValueEqual(stationCount(helper, LINE.rack(13, Side.RIGHT), GOLD), (long) COUNT,
                            "the gold arrived in the east");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> helper.assertTrue(!severalCranes() || together.get(),
                        "both cranes had a job in the same tick"))
                .thenSucceed();
    }

    /** A crane whose way another crane holds waits with that crane's name, and both jobs finish. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void cranewaitsforrailsanothercraneholds(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, GOLD.toStack(COUNT));
        BlockPos westOutput = LINE.rack(0, Side.RIGHT);
        output(helper, westOutput, Side.RIGHT);
        storage(helper, LINE, 10, Side.RIGHT, IRON.toStack(COUNT));
        BlockPos eastOutput = LINE.rack(MEMBER_X, Side.LEFT);
        output(helper, eastOutput, Side.LEFT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicBoolean held = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (verdict(helper, LINE, 2) instanceof TrafficVerdict.Hold hold
                    && hold.waiting().reason() == TrafficWait.Reason.PATH_HELD && hold.waiting().otherCrane() == 1)
                held.set(true);
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    // Gold to the block beside crane 1's dock: only crane 1 may serve it, and its way out holds the
                    // rails crane 2 needs for the iron.
                    request(helper, LINE, westOutput, GOLD, COUNT);
                    request(helper, LINE, eastOutput, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, westOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertValueEqual(stationCount(helper, eastOutput, IRON), (long) COUNT, "the iron arrived");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> helper.assertTrue(!severalCranes() || held.get(),
                        "crane 2 waited for the rails crane 1 held"))
                .thenSucceed();
    }

    /**
     * Crane 2 rests on its home point in crane 1's way. The home point is on crane 1's route, so moving aside there is
     * refused and crane 2 goes to its dock instead; crane 1's job completes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void idlecraneinthewaymovesaside(GameTestHelper helper) {
        build(helper, LINE, true);
        homePoint(helper, LINE.rack(9, Side.LEFT), Side.LEFT);
        storage(helper, LINE, 11, Side.RIGHT, GOLD.toStack(COUNT));
        BlockPos westOutput = LINE.rack(0, Side.RIGHT);
        output(helper, westOutput, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicBoolean yielded = new AtomicBoolean();
        AtomicBoolean atDock = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (verdict(helper, LINE, 2) instanceof TrafficVerdict.Yield)
                yielded.set(true);
            if (yielded.get() && crane(helper, LINE.memberDock()).craneState().pose().x() == MEMBER_X)
                atDock.set(true);
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                // Crane 2 has nothing to do and drives to the home point paired with it.
                .thenWaitUntil(() -> {
                    CranePose pose = crane(helper, LINE.memberDock()).craneState().pose();
                    helper.assertValueEqual(pose.x(), 9.0, "crane 2 rests on its home point");
                })
                .thenExecute(() -> request(helper, LINE, westOutput, GOLD, COUNT))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, westOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertTrue(crane(helper, LINE.dock()).currentJob().isEmpty(), "crane 1 is done");
                })
                .thenExecute(() -> {
                    helper.assertTrue(yielded.get(), "crane 2 was ordered aside");
                    helper.assertTrue(atDock.get(), "to its dock, because its home point was in the way");
                })
                .thenSucceed();
    }

    /** The nearest free crane takes a job; at equal distance the lower crane number does. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void nearestfreecranetakesthejob(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 7, Side.LEFT, DIAMOND.toStack(COUNT));
        BlockPos diamondOutput = LINE.rack(6, Side.RIGHT);
        output(helper, diamondOutput, Side.RIGHT);
        storage(helper, LINE, 11, Side.LEFT, IRON.toStack(COUNT));
        BlockPos ironOutput = LINE.rack(9, Side.RIGHT);
        output(helper, ironOutput, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicInteger diamondCrane = new AtomicInteger();
        AtomicInteger ironCrane = new AtomicInteger();
        helper.onEachTick(() -> {
            for (BlockPos dock : List.of(LINE.dock(), LINE.memberDock())) {
                StackerCraneBlockEntity crane = crane(helper, dock);
                crane.currentJob().ifPresent(job -> {
                    if (job.key().equals(DIAMOND))
                        diamondCrane.compareAndSet(0, crane.craneNumber());
                    else if (job.key().equals(IRON))
                        ironCrane.compareAndSet(0, crane.craneNumber());
                });
            }
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                // Position 7 is seven blocks from either dock: the tie goes to crane 1.
                .thenExecute(() -> request(helper, LINE, diamondOutput, DIAMOND, COUNT))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, diamondOutput, DIAMOND), (long) COUNT,
                            "the diamonds arrived");
                    assertIdle(helper, LINE);
                })
                // Crane 1 now stands at position 6, crane 2 still at 14: the iron at 11 is crane 2's.
                .thenExecute(() -> request(helper, LINE, ironOutput, IRON, COUNT))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, ironOutput, IRON), (long) COUNT, "the iron arrived");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(diamondCrane.get(), 1, "the tie went to crane 1");
                    helper.assertValueEqual(ironCrane.get(), 2, "the nearer crane took the iron");
                })
                .thenSucceed();
    }

    /**
     * A comb with a dock at each end of the main run: each crane works in a tooth on the other's side. One waits in its
     * tooth while the other passes along the main run, and both jobs complete.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void cranesonacombpassthroughatooth(GameTestHelper helper) {
        build(helper, LINE, true);
        for (int tooth : new int[] {5, 10}) {
            for (int z = 1; z <= 3; z++)
                helper.setBlock(new BlockPos(tooth, BASE_Y, LINE.z() + z), WarehouseRailBlock.along(Direction.Axis.Z));
        }
        // Iron in the west tooth for crane 2, gold in the east tooth for crane 1.
        placeStorageAt(helper, new BlockPos(4, BASE_Y, 6), Direction.WEST, IRON.toStack(COUNT));
        placeStorageAt(helper, new BlockPos(11, BASE_Y, 6), Direction.EAST, GOLD.toStack(COUNT));
        BlockPos westOutput = LINE.rack(0, Side.LEFT);
        BlockPos eastOutput = LINE.rack(MEMBER_X, Side.LEFT);
        output(helper, westOutput, Side.LEFT);
        output(helper, eastOutput, Side.LEFT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicBoolean passed = new AtomicBoolean();
        helper.onEachTick(() -> {
            Set<BlockPos> one = standingBlocks(crane(helper, LINE.dock()));
            Set<BlockPos> two = standingBlocks(crane(helper, LINE.memberDock()));
            int oneX = one.stream().mapToInt(BlockPos::getX).max().orElse(0);
            int twoX = two.stream().mapToInt(BlockPos::getX).max().orElse(0);
            if (twoX < oneX)
                passed.set(true);
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    request(helper, LINE, westOutput, GOLD, COUNT);
                    request(helper, LINE, eastOutput, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, westOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertValueEqual(stationCount(helper, eastOutput, IRON), (long) COUNT, "the iron arrived");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> helper.assertTrue(passed.get(), "the cranes passed each other through a tooth"))
                .thenSucceed();
    }

    /** A ring with a dock at each of two corners: jobs on the far sides complete going round. */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void cranesonaringgoround(GameTestHelper helper) {
        BlockPos controllerPos = new BlockPos(0, BASE_Y, 2);
        BlockPos dock = new BlockPos(1, BASE_Y, 2);
        BlockPos memberDock = new BlockPos(1, BASE_Y, 10);
        placeMotor(helper, dock.below());
        helper.setBlock(dock, dockState(Direction.EAST));
        for (int x = 2; x <= 13; x++) {
            helper.setBlock(new BlockPos(x, BASE_Y, 2), WarehouseRailBlock.along(Direction.Axis.X));
            helper.setBlock(new BlockPos(x, BASE_Y, 10), WarehouseRailBlock.along(Direction.Axis.X));
        }
        for (int z = 3; z <= 9; z++) {
            helper.setBlock(new BlockPos(2, BASE_Y, z), WarehouseRailBlock.along(Direction.Axis.Z));
            helper.setBlock(new BlockPos(13, BASE_Y, z), WarehouseRailBlock.along(Direction.Axis.Z));
        }
        helper.setBlock(controllerPos, controllerState(Direction.EAST));
        placeMotor(helper, memberDock.below());
        helper.setBlock(memberDock, dockState(Direction.EAST));
        motor(helper, dock.below()).generatedSpeed.setValue(TEST_RPM);
        motor(helper, memberDock.below()).generatedSpeed.setValue(TEST_RPM);
        placeStorageAt(helper, new BlockPos(12, BASE_Y, 11), Direction.SOUTH, GOLD.toStack(COUNT));
        placeStorageAt(helper, new BlockPos(12, BASE_Y, 1), Direction.NORTH, IRON.toStack(COUNT));
        BlockPos oneOutput = new BlockPos(1, BASE_Y, 1);
        BlockPos twoOutput = new BlockPos(1, BASE_Y, 11);
        helper.setBlock(oneOutput, outputState(Direction.SOUTH));
        helper.setBlock(twoOutput, outputState(Direction.NORTH));
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        List<BlockPos> docks = List.of(dock, memberDock);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while the ring works");
            assertApart(helper, docks);
        });
        if (!severalCranes()) {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper, controllerPos).status(),
                            ControllerStatus.READY, "ready"))
                    .thenExecuteAfter(SETTLE_TICKS, () -> {
                        helper.assertValueEqual(controllerAt(helper, controllerPos).craneCount(), 1, "one crane");
                        helper.assertTrue(crane(helper, memberDock).membership().isEmpty(), "the other dock a wall");
                    })
                    .thenSucceed();
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper, controllerPos);
                    helper.assertValueEqual(controller.craneCount(), 2, "two cranes on the ring");
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "snapshots read");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) COUNT, "the gold indexed");
                    helper.assertValueEqual(controller.countOf(IRON), (long) COUNT, "the iron indexed");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper, controllerPos);
                    accepted(helper, controller.request(helper.absolutePos(oneOutput), GOLD, COUNT), "gold");
                    accepted(helper, controller.request(helper.absolutePos(twoOutput), IRON, COUNT), "iron");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, oneOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertValueEqual(stationCount(helper, twoOutput, IRON), (long) COUNT, "the iron arrived");
                    for (BlockPos each : docks)
                        helper.assertTrue(crane(helper, each).currentJob().isEmpty(), "no job left");
                })
                .thenSucceed();
    }

    // --- the reservation ledger with several jobs ------------------------------------------------------------------

    /** Two requests from one chest: the two jobs never promise more than the chest holds, and each picks in full. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twojobsneverpromisethesamestock(GameTestHelper helper) {
        build(helper, LINE, true);
        BlockPos chest = storage(helper, LINE, 7, Side.LEFT, IRON.toStack(2 * COUNT));
        BlockPos west = LINE.rack(2, Side.RIGHT);
        BlockPos east = LINE.rack(12, Side.RIGHT);
        output(helper, west, Side.RIGHT);
        output(helper, east, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicReference<RackPosition> source = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (source.get() == null)
                return;
            long promised = 0;
            for (TransportJob<ItemKey, RackPosition> job : jobs(helper, LINE)) {
                if (job.source().equals(source.get()) && !job.picked())
                    promised += job.plannedAmount();
                if (job.picked() && job.type() == JobType.RETRIEVE)
                    helper.assertValueEqual(job.pickedAmount(), job.plannedAmount(), "every pick is in full");
            }
            helper.assertTrue(promised <= countIn(helper, chest, IRON),
                    "jobs promise no more than the chest holds (" + promised + ")");
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    source.set(rackOf(helper, LINE, LINE.rack(7, Side.LEFT)));
                    request(helper, LINE, west, IRON, COUNT);
                    request(helper, LINE, east, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, west, IRON), (long) COUNT, "west served");
                    helper.assertValueEqual(stationCount(helper, east, IRON), (long) COUNT, "east served");
                    assertIdle(helper, LINE);
                })
                .thenSucceed();
    }

    /** Two inputs and room for one carry: the second crane never sets off towards the room the first one has. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twojobsneverpromisethesameroom(GameTestHelper helper) {
        build(helper, LINE, true);
        ItemStack[] dirt = new ItemStack[26];
        for (int i = 0; i < dirt.length; i++)
            dirt[i] = DIRT.toStack(STACK);
        BlockPos chest = storage(helper, LINE, 7, Side.LEFT, dirt);
        BlockPos westInput = LINE.rack(3, Side.RIGHT);
        BlockPos eastInput = LINE.rack(11, Side.RIGHT);
        input(helper, westInput, Side.RIGHT);
        input(helper, eastInput, Side.RIGHT);
        AtomicReference<RackPosition> target = new AtomicReference<>();
        AtomicReference<Map<ItemKey, Long>> census = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (census.get() != null)
                ItemCensus.assertEquals(helper, census.get(), "while storing");
            assertApart(helper, LINE.docks());
            if (target.get() == null)
                return;
            long coming = 0;
            for (TransportJob<ItemKey, RackPosition> job : jobs(helper, LINE)) {
                if (job.target().equals(target.get()))
                    coming += job.plannedAmount() - job.deliveredAmount();
            }
            helper.assertTrue(coming + countIn(helper, chest, IRON) <= STACK,
                    "no more is on its way into the chest than it has room for (" + coming + ")");
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    target.set(rackOf(helper, LINE, LINE.rack(7, Side.LEFT)));
                    insert(helper, westInput, IRON.toStack(STACK));
                    insert(helper, eastInput, IRON.toStack(STACK));
                    census.set(ItemCensus.take(helper));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(countIn(helper, chest, IRON), (long) STACK, "the room was filled once");
                    helper.assertValueEqual(stationCount(helper, westInput, IRON)
                            + stationCount(helper, eastInput, IRON), (long) STACK, "the rest waits in its input");
                    assertIdle(helper, LINE);
                })
                .thenSucceed();
    }

    /** One input, two cranes: they share its buffered items and never plan the same ones twice. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twojobsneverpromisethesamebuffereditems(GameTestHelper helper) {
        build(helper, LINE, true);
        BlockPos westChest = storage(helper, LINE, 3, Side.LEFT);
        BlockPos eastChest = storage(helper, LINE, 11, Side.LEFT);
        BlockPos input = LINE.rack(7, Side.RIGHT);
        input(helper, input, Side.RIGHT);
        AtomicReference<RackPosition> source = new AtomicReference<>();
        AtomicReference<Map<ItemKey, Long>> census = new AtomicReference<>();
        Set<Integer> storing = new TreeSet<>();
        helper.onEachTick(() -> {
            if (census.get() != null)
                ItemCensus.assertEquals(helper, census.get(), "while storing");
            assertApart(helper, LINE.docks());
            if (source.get() == null)
                return;
            long planned = 0;
            for (BlockPos dock : LINE.docks()) {
                StackerCraneBlockEntity crane = crane(helper, dock);
                Optional<TransportJob<ItemKey, RackPosition>> job = crane.currentJob();
                if (job.isPresent() && job.get().source().equals(source.get()) && !job.get().picked()) {
                    planned += job.get().plannedAmount();
                    storing.add(crane.craneNumber());
                }
            }
            helper.assertTrue(planned <= stationCount(helper, input, IRON),
                    "no buffered item is planned twice (" + planned + ")");
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    source.set(rackOf(helper, LINE, input));
                    insert(helper, input, IRON.toStack(STACK));
                    insert(helper, input, IRON.toStack(STACK));
                    census.set(ItemCensus.take(helper));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(countIn(helper, westChest, IRON) + countIn(helper, eastChest, IRON),
                            (long) 2 * STACK, "everything was stored");
                    helper.assertValueEqual(stationCount(helper, input, IRON), 0L, "and the input is empty");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> helper.assertValueEqual(storing.size(), severalCranes() ? 2 : 1,
                        "every crane stored from the one input"))
                .thenSucceed();
    }

    /**
     * One terminal request served by both cranes at once, 32 iron each, is reduced from 64 to 40 before either picks:
     * neither trip alone is planned for more than is left, but together they are, so trips are cancelled until no more
     * is coming than is still wanted, and exactly 40 arrive (issue #24, ADR-062, with several cranes).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void reducedrequestontwocranesbringsonlywhatisleft(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 5, Side.LEFT, IRON.toStack(HALF));
        storage(helper, LINE, 9, Side.LEFT, IRON.toStack(HALF));
        // A terminal, not a warehouse port: a port takes one crane's retrieve at a time (ADR-075 §9), a terminal both.
        BlockPos output = LINE.rack(7, Side.RIGHT);
        helper.setBlock(output, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, Line.away(Side.RIGHT).getOpposite()));
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        AtomicReference<UUID> request = new AtomicReference<>();
        int wanted = 2 * HALF - REDUCED_BY;
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    RequestResult result = controller(helper, LINE).request(helper.absolutePos(output), IRON, 2 * HALF);
                    accepted(helper, result, "iron");
                    request.set(result.request().orElseThrow().id());
                })
                .thenWaitUntil(() -> {
                    for (BlockPos dock : LINE.docks()) {
                        Optional<TransportJob<ItemKey, RackPosition>> job = crane(helper, dock).currentJob();
                        helper.assertTrue(job.isPresent() && !job.get().picked()
                                && job.get().requestId().filter(request.get()::equals).isPresent(),
                                "both cranes are on their way to pick for the one request");
                        helper.assertTrue(job.get().plannedAmount() <= wanted, "neither trip alone is too much");
                    }
                })
                .thenExecute(() -> controller(helper, LINE).reduceRequestShare(request.get(), REDUCED_BY))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) wanted,
                            "exactly what is still wanted arrived");
                    helper.assertTrue(controller(helper, LINE).openRequests().isEmpty(), "and the request is done");
                    assertIdle(helper, LINE);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(stationCount(helper, output, IRON),
                        (long) wanted, "and nothing more comes"))
                .thenSucceed();
    }

    /**
     * A port with room for one carry and two stocked racks: one crane brings a retrieve to it at a time, so no two
     * trips are ever sized by the same room.
     */
    @GameTest(template = AISLE_16X10X7, batch = PORT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void twojobsneverpromisethesameportroom(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.outputBufferSlots, 1);
        build(helper, LINE, true);
        storage(helper, LINE, 3, Side.LEFT, IRON.toStack(STACK));
        storage(helper, LINE, 11, Side.LEFT, IRON.toStack(STACK));
        BlockPos port = LINE.rack(7, Side.RIGHT);
        output(helper, port, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicReference<RackPosition> portRack = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (portRack.get() == null)
                return;
            long coming = 0;
            for (TransportJob<ItemKey, RackPosition> job : jobs(helper, LINE)) {
                if (job.type() == JobType.RETRIEVE && job.target().equals(portRack.get()))
                    coming += job.plannedAmount() - job.deliveredAmount();
            }
            helper.assertTrue(coming + stationCount(helper, port, IRON) <= STACK,
                    "no more is on its way to the port than it has room for (" + coming + ")");
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    portRack.set(rackOf(helper, LINE, port));
                    request(helper, LINE, port, IRON, 2 * STACK);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, port, IRON), (long) STACK, "the port is full");
                    assertIdle(helper, LINE);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(stationCount(helper, port, IRON), (long) STACK, "and stays full");
                    helper.assertValueEqual(controller(helper, LINE).openRequests().getFirst().remaining(), STACK,
                            "the rest of the request waits for room");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = PORT_BATCH)
    public static void restorePortConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** One production order with two ingredient lines: each crane brings one of them. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionsuppliesrunonbothcranes(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 2, Side.LEFT, LOG.toStack(COUNT));
        storage(helper, LINE, 12, Side.LEFT, NAIL.toStack(COUNT));
        BlockPos station = LINE.rack(7, Side.RIGHT);
        helper.setBlock(station, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.NORTH));
        BlockPos planks = LINE.rack(4, Side.RIGHT);
        output(helper, planks, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        Set<Integer> supplying = new TreeSet<>();
        helper.onEachTick(() -> {
            for (BlockPos dock : LINE.docks()) {
                StackerCraneBlockEntity crane = crane(helper, dock);
                crane.currentJob().filter(job -> job.type() == JobType.SUPPLY)
                        .ifPresent(job -> supplying.add(crane.craneNumber()));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity production = productionAt(helper, station);
                    helper.assertTrue(production.setPatternEntry(0, 0, LOG, 1), "the log");
                    helper.assertTrue(production.setPatternEntry(0, 1, NAIL, 1), "a nail");
                    helper.assertTrue(production.setPatternEntry(0, 2, NAIL, 1), "and another");
                    helper.assertTrue(production.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4),
                            "four planks");
                })
                .thenExecute(() -> request(helper, LINE, planks, PLANK, 4))
                .thenWaitUntil(() -> {
                    List<ProductionOrder<ItemKey, RackPosition>> orders = controller(helper, LINE)
                            .productionOrders();
                    helper.assertValueEqual(orders.size(), 1, "one order");
                    helper.assertValueEqual(orders.getFirst().state(), ProductionOrderState.DELIVERED,
                            "both lines delivered");
                    helper.assertValueEqual(stationCount(helper, station, LOG), 1L, "the log is at the machine");
                    helper.assertValueEqual(stationCount(helper, station, NAIL), 2L, "and the nails");
                })
                .thenExecute(() -> helper.assertValueEqual(supplying.size(), severalCranes() ? 2 : 1,
                        "every crane brought an ingredient"))
                .thenSucceed();
    }

    // --- failures ----------------------------------------------------------------------------------------------------

    /**
     * Crane 2 loses its rotation on its home point in the middle of the line. Crane 1 finishes what does not need its
     * blocks, waits with "crane stopped" for what does, and finishes that too once crane 2 turns again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void unpoweredcranewhiletheotherworks(GameTestHelper helper) {
        build(helper, LINE, true);
        homePoint(helper, LINE.rack(9, Side.LEFT), Side.LEFT);
        storage(helper, LINE, 3, Side.LEFT, IRON.toStack(COUNT));
        BlockPos ironOutput = LINE.rack(1, Side.RIGHT);
        output(helper, ironOutput, Side.RIGHT);
        storage(helper, LINE, 12, Side.RIGHT, GOLD.toStack(COUNT));
        BlockPos goldOutput = LINE.rack(0, Side.RIGHT);
        output(helper, goldOutput, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        AtomicBoolean waitedForStopped = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (verdict(helper, LINE, 1) instanceof TrafficVerdict.Hold hold
                    && hold.waiting().reason() == TrafficWait.Reason.CRANE_STOPPED
                    && hold.waiting().otherCrane() == 2)
                waitedForStopped.set(true);
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneState().pose().x(),
                        9.0, "crane 2 rests on its home point"))
                .thenExecute(() -> motor(helper, LINE.memberDock().below()).generatedSpeed.setValue(0))
                .thenWaitUntil(() -> helper.assertTrue(crane(helper, LINE.memberDock()).currentSpeeds().isStopped(),
                        "crane 2 has no rotation"))
                .thenExecute(() -> request(helper, LINE, ironOutput, IRON, COUNT))
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, ironOutput, IRON), (long) COUNT,
                        "crane 1 finished what did not need crane 2's blocks"))
                .thenExecute(() -> request(helper, LINE, goldOutput, GOLD, COUNT))
                .thenWaitUntil(() -> helper.assertTrue(waitedForStopped.get(),
                        "crane 1 waits for the crane that cannot move"))
                .thenExecute(() -> motor(helper, LINE.memberDock().below()).generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, goldOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertTrue(crane(helper, LINE.dock()).currentJob().isEmpty(), "crane 1 is done");
                })
                .thenSucceed();
    }

    /**
     * The member dock is broken while its crane carries: the items drop at the dock and are counted, the warehouse is
     * one crane again, and crane 1 serves the request.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void brokenmemberdockmidjob(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, IRON.toStack(2 * COUNT));
        BlockPos output = LINE.rack(8, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the dock breaks"));
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> request(helper, LINE, output, IRON, COUNT))
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertValueEqual(member.craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                            "crane 2 carries");
                    helper.assertValueEqual(member.heldItems().count(IRON), COUNT, "the iron");
                })
                .thenExecute(() -> helper.getLevel().destroyBlock(helper.absolutePos(LINE.memberDock()), false))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper, LINE);
                    helper.assertValueEqual(controller.craneCount(), 1, "the warehouse has one crane again");
                    helper.assertTrue(crane(helper, LINE.dock()).membership().isEmpty(),
                            "and its crane has no role of several any more");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) COUNT,
                            "crane 1 served the request");
                    helper.assertTrue(crane(helper, LINE.dock()).currentJob().isEmpty(), "and is done");
                })
                .thenExecute(() -> {
                    CompoundTag saved = crane(helper, LINE.dock()).saveWithoutMetadata(
                            helper.getLevel().registryAccess());
                    helper.assertFalse(saved.contains(CraneMembership.TAG), "its save is the one-crane save again");
                })
                .thenSucceed();
    }

    /**
     * A rail is closed between crane 2's machine and its dock while it carries: it is stranded, finishes its job on the
     * warehouse's side, and joins again normally once the rail is open.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void railclosedbetweenthecranes(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, IRON.toStack(2 * COUNT));
        BlockPos output = LINE.rack(4, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        BlockPos cut = LINE.cell(RAILS);
        AtomicBoolean stranded = new AtomicBoolean();
        helper.onEachTick(() -> {
            for (CraneMember member : controller(helper, LINE).craneMembers()) {
                if (member.stranded())
                    stranded.set(true);
            }
        });
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> request(helper, LINE, output, IRON, COUNT))
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertValueEqual(member.craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                            "crane 2 carries");
                    helper.assertTrue(member.craneState().pose().x() < RAILS - 1.5, "west of the rail to close");
                })
                .thenExecute(() -> wrench(helper, cut))
                .thenWaitUntil(() -> helper.assertTrue(stranded.get(), "crane 2 is stranded"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) COUNT,
                            "the stranded crane finished its job on the warehouse's side");
                    helper.assertTrue(crane(helper, LINE.memberDock()).currentJob().isEmpty(), "and holds nothing");
                })
                .thenExecute(() -> wrench(helper, cut))
                .thenWaitUntil(() -> {
                    List<CraneMember> members = controller(helper, LINE).craneMembers();
                    helper.assertValueEqual(members.size(), 2, "two cranes again");
                    helper.assertFalse(members.get(1).stranded(), "crane 2 reaches its dock again");
                })
                .thenSucceed();
    }

    /**
     * Room for two cranes (ADR-075 §2.5). A new dock beside the line, nearer the warehouse's own dock than crane 2, is
     * the dock left out, not crane 2. A rail closed behind crane 2 strands it: it keeps its place, so the new dock is
     * still left out and the warehouse never has three cranes; once the rail is open, crane 2 is crane 2 again.
     */
    @GameTest(template = AISLE_16X10X7, batch = CAP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void listedcraneskeeptheirplacesunderthecap(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 2);
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, IRON.toStack(2 * COUNT));
        BlockPos output = LINE.rack(4, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        BlockPos side = LINE.rack(7, Side.LEFT);
        List<BlockPos> docks = List.of(LINE.dock(), LINE.memberDock(), side);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        AtomicBoolean placed = new AtomicBoolean();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while the cranes work");
            assertApart(helper, placed.get() ? docks : LINE.docks());
            helper.assertTrue(controller(helper, LINE).craneCount() <= 2, "never more cranes than the cap");
        });
        BlockPos cut = LINE.cell(RAILS);
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    placeMotor(helper, side.below());
                    helper.setBlock(side, dockState(Direction.SOUTH));
                    motor(helper, side.below()).generatedSpeed.setValue(TEST_RPM);
                    placed.set(true);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper, LINE).craneRoster()
                        .map(CraneRoster::refusedDocks).orElse(0), 1, "the new dock is the one left out"))
                .thenExecute(() -> {
                    helper.assertTrue(crane(helper, LINE.memberDock()).isMember(), "crane 2 kept its place");
                    helper.assertTrue(crane(helper, side).membership().isEmpty(), "the new dock is no crane");
                    request(helper, LINE, output, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertValueEqual(member.craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                            "crane 2 carries");
                    helper.assertTrue(member.craneState().pose().x() < RAILS - 1.5, "west of the rail to close");
                })
                .thenExecute(() -> wrench(helper, cut))
                .thenWaitUntil(() -> {
                    List<CraneMember> members = controller(helper, LINE).craneMembers();
                    helper.assertValueEqual(members.size(), 2, "two cranes, the cap");
                    helper.assertTrue(members.get(1).stranded(), "crane 2 is stranded and keeps its place");
                    helper.assertTrue(crane(helper, side).membership().isEmpty(),
                            "the dock the rails reach now is still left out");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) COUNT, "the iron arrived");
                    helper.assertTrue(crane(helper, LINE.memberDock()).currentJob().isEmpty(), "crane 2 is done");
                })
                .thenExecute(() -> wrench(helper, cut))
                .thenWaitUntil(() -> {
                    List<CraneMember> members = controller(helper, LINE).craneMembers();
                    helper.assertValueEqual(members.size(), 2, "two cranes again");
                    helper.assertValueEqual(members.get(1).dock(), helper.absolutePos(LINE.memberDock()),
                            "crane 2 is the dock at the end of the line");
                    helper.assertFalse(members.get(1).stranded(), "and reaches its dock again");
                    helper.assertTrue(crane(helper, side).membership().isEmpty(), "the new dock is still no crane");
                })
                .thenSucceed();
    }

    /**
     * Crane 2's saved number no longer matches the controller's list (a crane before it left while its chunk was away).
     * Until the next relink names it again, it is gated as a crane without a controller: it holds, rather than reading
     * another crane's slot of the ledger.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void renumberedcraneholdsuntilrelinked(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, IRON.toStack(COUNT));
        BlockPos output = LINE.rack(4, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        AtomicBoolean heldUnderTheWrongNumber = new AtomicBoolean();
        AtomicReference<CraneTrafficClaim> ownClaim = new AtomicReference<>();
        helper.onEachTick(() -> {
            StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
            if (member.craneNumber() != 3)
                return;
            if (member.isTrafficHeld())
                heldUnderTheWrongNumber.set(true);
            helper.assertValueEqual(member.trafficClaim(), ownClaim.get(),
                    "under the wrong number it keeps its own claim and never takes another slot's");
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> request(helper, LINE, output, IRON, COUNT))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneState().phase(),
                        CranePhase.TRAVEL_TO_TARGET, "crane 2 carries"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertTrue(member.trafficClaim().committed(), "it drives a granted route");
                    ownClaim.set(member.trafficClaim());
                    CraneMembership frame = member.membership().orElseThrow();
                    member.joinWarehouse(new CraneMembership(frame.controller(), frame.anchor(), frame.anchorFacing(),
                            frame.siteBranch(), frame.siteX(), 3, 3), member.warehouse().network());
                })
                .thenWaitUntil(() -> helper.assertTrue(heldUnderTheWrongNumber.get(),
                        "under a number the controller does not list it holds"))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneNumber(), 2,
                        "the relink names it crane 2 again"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) COUNT, "the iron arrived");
                    assertIdle(helper, LINE);
                })
                .thenSucceed();
    }

    /**
     * The controller is broken while crane 2 carries. Crane 1, parked on its dock, becomes a crane by itself; crane 2
     * keeps its role and holds where it stands with its items instead of driving on ungated. A controller placed again
     * takes both in, and crane 2 drives on.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void brokencontrollerholdsthecranesontherails(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, IRON.toStack(COUNT));
        BlockPos output = LINE.rack(4, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        AtomicReference<CranePose> heldAt = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> request(helper, LINE, output, IRON, COUNT))
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertValueEqual(member.craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                            "crane 2 carries");
                    helper.assertTrue(crane(helper, LINE.dock()).parkedOnOwnDock(), "crane 1 is parked");
                })
                .thenExecute(() -> helper.getLevel().destroyBlock(helper.absolutePos(LINE.controller()), false))
                .thenExecuteAfter(2, () -> heldAt.set(crane(helper, LINE.memberDock()).craneState().pose()))
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    StackerCraneBlockEntity member = crane(helper, LINE.memberDock());
                    helper.assertTrue(member.isMember(), "crane 2 keeps its role");
                    helper.assertTrue(member.isTrafficHeld(), "and holds");
                    helper.assertValueEqual(member.craneState().pose().x(), heldAt.get().x(), "where it stood");
                    helper.assertValueEqual(member.heldItems().count(IRON), COUNT, "with its items");
                })
                .thenWaitUntil(() -> helper.assertTrue(crane(helper, LINE.dock()).membership().isEmpty(),
                        "crane 1, parked on its dock, is a crane by itself"))
                .thenExecute(() -> helper.setBlock(LINE.controller(), controllerState(Direction.EAST)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper, LINE).craneCount(), 2, "a new controller takes both in");
                    helper.assertFalse(crane(helper, LINE.memberDock()).isTrafficHeld(), "and crane 2 drives on");
                })
                .thenWaitUntil(() -> helper.assertTrue(crane(helper, LINE.memberDock()).heldItems().isEmpty(),
                        "crane 2 puts its items somewhere, counted by the census"))
                .thenSucceed();
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /**
     * Both cranes carry: the docks save their frame and their claim, the controller saves both cranes, and all of it
     * reads back into detached block entities unchanged. The live warehouse then finishes both jobs.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void savedandreloadedwithtwocranesinflight(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 2, Side.LEFT, IRON.toStack(COUNT));
        BlockPos west = LINE.rack(1, Side.RIGHT);
        output(helper, west, Side.RIGHT);
        storage(helper, LINE, 12, Side.LEFT, GOLD.toStack(COUNT));
        BlockPos east = LINE.rack(13, Side.RIGHT);
        output(helper, east, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    request(helper, LINE, west, IRON, COUNT);
                    request(helper, LINE, east, GOLD, COUNT);
                })
                .thenWaitUntil(() -> {
                    for (BlockPos dock : LINE.docks()) {
                        CranePhase phase = crane(helper, dock).craneState().phase();
                        helper.assertTrue(phase == CranePhase.TRAVEL_TO_SOURCE || phase == CranePhase.TRAVEL_TO_TARGET,
                                "both cranes drive (" + phase + ")");
                    }
                })
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    for (BlockPos dock : LINE.docks()) {
                        StackerCraneBlockEntity live = crane(helper, dock);
                        CompoundTag tag = live.saveWithoutMetadata(registries);
                        helper.assertTrue(tag.contains(CraneMembership.TAG, Tag.TAG_COMPOUND),
                                "crane " + live.craneNumber() + " saves its frame and claim");
                        StackerCraneBlockEntity loaded = WareworksBlockEntityTypes.STACKER_CRANE
                                .create(live.getBlockPos(), live.getBlockState());
                        if (loaded == null) {
                            helper.fail("could not create a detached dock", dock);
                            return;
                        }
                        loaded.loadWithComponents(tag, registries);
                        helper.assertValueEqual(loaded.membership(), live.membership(), "the same frame");
                        helper.assertValueEqual(loaded.trafficClaim(), live.trafficClaim(), "the same claim");
                        helper.assertValueEqual(loaded.craneState().pose(), live.craneState().pose(), "the same pose");
                        helper.assertValueEqual(loaded.currentJob(), live.currentJob(), "the same job");
                    }
                    WarehouseControllerBlockEntity live = controller(helper, LINE);
                    CompoundTag tag = live.saveWithoutMetadata(registries);
                    helper.assertValueEqual(tag.getList("Cranes", Tag.TAG_COMPOUND).size(), 2,
                            "the controller saves both cranes");
                    for (int i = 0; i < 2; i++) {
                        CompoundTag entry = tag.getList("Cranes", Tag.TAG_COMPOUND).getCompound(i);
                        Optional<TransportJob<ItemKey, RackPosition>> remembered = StackerCraneBlockEntity
                                .loadJob(entry.getCompound("Job"), registries);
                        helper.assertValueEqual(remembered.map(TransportJob::id),
                                crane(helper, LINE.docks().get(i)).currentJob().map(TransportJob::id),
                                "and the job of crane " + (i + 1) + ", for a load while its chunk is away");
                    }
                    WarehouseControllerBlockEntity loaded = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(live.getBlockPos(), live.getBlockState());
                    if (loaded == null) {
                        helper.fail("could not create a detached controller", LINE.controller());
                        return;
                    }
                    loaded.loadWithComponents(tag, registries);
                    helper.assertValueEqual(loaded.craneMembers(), live.craneMembers(), "and reads them back");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, west, IRON), (long) COUNT, "the iron arrived");
                    helper.assertValueEqual(stationCount(helper, east, GOLD), (long) COUNT, "the gold arrived");
                    assertIdle(helper, LINE);
                })
                .thenSucceed();
    }

    /**
     * The same warehouse built twice, its cranes placed in opposite order: the per-tick traces of the two copies are
     * identical, so what the traffic decides does not depend on the order the cranes tick in.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void twocranetraceisdeterministic(GameTestHelper helper) {
        Line first = new Line(3, RAILS);
        Line second = new Line(9, RAILS);
        buildTraced(helper, first, false);
        buildTraced(helper, second, true);
        List<String> firstTrace = new ArrayList<>();
        List<String> secondTrace = new ArrayList<>();
        AtomicBoolean recording = new AtomicBoolean();
        if (!severalCranes()) {
            assertLegacy(helper, first);
            return;
        }
        helper.onEachTick(() -> {
            assertApart(helper, first.docks());
            assertApart(helper, second.docks());
            if (recording.get()) {
                firstTrace.add(trace(helper, first));
                secondTrace.add(trace(helper, second));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertReady(helper, first);
                    assertReady(helper, second);
                })
                .thenExecute(() -> {
                    recording.set(true);
                    for (Line line : List.of(first, second)) {
                        request(helper, line, line.rack(0, Side.RIGHT), GOLD, COUNT);
                        request(helper, line, line.rack(MEMBER_X, Side.LEFT), IRON, COUNT);
                        request(helper, line, line.rack(6, Side.RIGHT), DIAMOND, COUNT);
                    }
                })
                .thenWaitUntil(() -> {
                    for (Line line : List.of(first, second)) {
                        helper.assertValueEqual(stationCount(helper, line.rack(0, Side.RIGHT), GOLD), (long) COUNT,
                                "gold");
                        helper.assertValueEqual(stationCount(helper, line.rack(MEMBER_X, Side.LEFT), IRON),
                                (long) COUNT, "iron");
                        helper.assertValueEqual(stationCount(helper, line.rack(6, Side.RIGHT), DIAMOND),
                                (long) COUNT, "diamonds");
                        assertIdle(helper, line);
                    }
                })
                .thenExecute(() -> {
                    recording.set(false);
                    helper.assertValueEqual(firstTrace.size(), secondTrace.size(), "both traces as long");
                    for (int tick = 0; tick < firstTrace.size(); tick++) {
                        if (!firstTrace.get(tick).equals(secondTrace.get(tick))) {
                            helper.fail("the copies part at tick " + tick + ": " + firstTrace.get(tick) + " vs "
                                    + secondTrace.get(tick));
                            return;
                        }
                    }
                })
                .thenSucceed();
    }

    /** One warehouse of {@link #twocranetraceisdeterministic}: the cranes placed in one order or the other. */
    private static void buildTraced(GameTestHelper helper, Line line, boolean memberFirst) {
        helper.setBlock(line.controller(), controllerState(Direction.EAST));
        placeMotor(helper, line.motor(line.dock()));
        placeMotor(helper, line.motor(line.memberDock()));
        for (int x = 1; x <= line.rails(); x++)
            helper.setBlock(line.cell(x), WarehouseRailBlock.along(Direction.Axis.X));
        if (memberFirst) {
            helper.setBlock(line.memberDock(), dockState(Direction.WEST));
            helper.setBlock(line.dock(), dockState(Direction.EAST));
        } else {
            helper.setBlock(line.dock(), dockState(Direction.EAST));
            helper.setBlock(line.memberDock(), dockState(Direction.WEST));
        }
        motor(helper, line.motor(line.dock())).generatedSpeed.setValue(TEST_RPM);
        motor(helper, line.motor(line.memberDock())).generatedSpeed.setValue(TEST_RPM);
        storage(helper, line, 12, Side.LEFT, GOLD.toStack(COUNT));
        storage(helper, line, 10, Side.RIGHT, IRON.toStack(COUNT));
        storage(helper, line, 7, Side.LEFT, DIAMOND.toStack(COUNT));
        output(helper, line.rack(0, Side.RIGHT), Side.RIGHT);
        output(helper, line.rack(MEMBER_X, Side.LEFT), Side.LEFT);
        output(helper, line.rack(6, Side.RIGHT), Side.RIGHT);
    }

    private static String trace(GameTestHelper helper, Line line) {
        StringBuilder state = new StringBuilder();
        for (BlockPos dock : line.docks()) {
            StackerCraneBlockEntity crane = crane(helper, dock);
            CranePose pose = crane.craneState().pose();
            state.append(crane.craneState().phase()).append(' ').append(pose.branch()).append('/')
                    .append(String.format(java.util.Locale.ROOT, "%.4f", pose.x())).append(';');
        }
        return state.toString();
    }

    /** With {@code aisle.maxCranesPerNetwork = 1} the second dock is the wall it was up to 0.17.1, and nothing more. */
    @GameTest(template = AISLE_16X10X7, batch = LEGACY_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void maxcranesoneisthelegacywarehouse(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 1);
        build(helper, LINE, true);
        assertLegacy(helper, LINE);
    }

    @AfterBatch(batch = LEGACY_BATCH)
    public static void restoreLegacyConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Three cranes, and the cap is lowered to two while crane 3 carries (ADR-075 §2.5). Crane 3 is not dropped mid trip:
     * it stays listed and gated, takes no new job, delivers, drives home to its own dock, and only then leaves. The
     * warehouse then has two cranes and the dock is a wall.
     */
    @GameTest(template = AISLE_16X10X7, batch = LOWERED_CAP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void loweredcapletsacranegoonlyonceparked(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 4);
        build(helper, LINE, true);
        BlockPos side = LINE.rack(7, Side.LEFT);
        placeMotor(helper, side.below());
        helper.setBlock(side, dockState(Direction.SOUTH));
        motor(helper, side.below()).generatedSpeed.setValue(TEST_RPM);
        storage(helper, LINE, 12, Side.RIGHT, IRON.toStack(2 * COUNT));
        BlockPos output = LINE.rack(4, Side.RIGHT);
        output(helper, output, Side.RIGHT);
        List<BlockPos> docks = List.of(LINE.dock(), side, LINE.memberDock());
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        AtomicReference<UUID> lastJob = new AtomicReference<>();
        AtomicBoolean leaving = new AtomicBoolean();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while the cranes work");
            assertApart(helper, docks);
            if (!leaving.get())
                return;
            Optional<TransportJob<ItemKey, RackPosition>> job = crane(helper, LINE.memberDock()).currentJob();
            job.ifPresent(found -> helper.assertValueEqual(found.id(), lastJob.get(),
                    "a crane the warehouse has no room for takes no new job"));
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper, LINE).craneCount(), 3, "three cranes");
                    helper.assertValueEqual(crane(helper, LINE.memberDock()).craneNumber(), 3,
                            "the dock at the end of the line is crane 3");
                    helper.assertValueEqual(controller(helper, LINE).pendingSnapshotCount(), 0, "storage read");
                })
                .thenExecute(() -> request(helper, LINE, output, IRON, COUNT))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneState().phase(),
                        CranePhase.TRAVEL_TO_TARGET, "crane 3 carries"))
                .thenExecute(() -> {
                    lastJob.set(crane(helper, LINE.memberDock()).currentJob().orElseThrow().id());
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 2);
                })
                .thenWaitUntil(() -> helper.assertTrue(controller(helper, LINE)
                        .isLeaving(helper.absolutePos(LINE.memberDock())), "crane 3 has no room any more"))
                .thenExecute(() -> {
                    leaving.set(true);
                    helper.assertValueEqual(controller(helper, LINE).craneCount(), 3,
                            "but it stays listed while it works");
                    helper.assertTrue(crane(helper, LINE.memberDock()).isMember(), "and keeps its role");
                    request(helper, LINE, output, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, IRON), (long) 2 * COUNT,
                            "both requests arrived");
                    helper.assertValueEqual(controller(helper, LINE).craneCount(), 2, "crane 3 left");
                    StackerCraneBlockEntity left = crane(helper, LINE.memberDock());
                    helper.assertTrue(left.membership().isEmpty(), "and is a crane by itself");
                    helper.assertTrue(left.parkedOnOwnDock(), "parked on its own dock");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    RailNetwork network = crane(helper, LINE.dock()).discoveredNetwork().orElseThrow();
                    helper.assertValueEqual(network.refusedDocks(), 1, "its dock is the one left out now");
                    helper.assertTrue(crane(helper, side).isMember(), "and the dock beside the line is crane 2");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = LOWERED_CAP_BATCH)
    public static void restoreLoweredCapConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Crane 2 drives from its dock to its home point, which no report tells the controller. The controller saves where
     * crane 2 really stands, so a ledger built after a restart while crane 2's chunk is still away keeps the right blocks
     * as an obstacle, and its chunk is to be saved (ADR-075 §13). Other block entities of that chunk may mark it as well,
     * so the second half does not tell who did.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllersavesthecranesitdoesnotsee(GameTestHelper helper) {
        build(helper, LINE, true);
        homePoint(helper, LINE.rack(9, Side.LEFT), Side.LEFT);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneState().pose().x(),
                        (double) MEMBER_X, "crane 2 stands on its dock"))
                .thenExecute(() -> controllerChunk(helper).setUnsaved(false))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, LINE.memberDock()).craneState().pose().x(),
                        9.0, "crane 2 rests on its home point"))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(controllerChunk(helper).isUnsaved(), "the controller's chunk is to be saved");
                    CompoundTag saved = controller(helper, LINE).saveWithoutMetadata(helper.getLevel()
                            .registryAccess());
                    CompoundTag entry = saved.getList("Cranes", Tag.TAG_COMPOUND).getCompound(1);
                    helper.assertValueEqual(entry.getDouble("PX"), 9.0, "the save knows where crane 2 stands");
                })
                .thenSucceed();
    }

    private static LevelChunk controllerChunk(GameTestHelper helper) {
        return helper.getLevel().getChunkAt(helper.absolutePos(LINE.controller()));
    }

    // --- what a player sees ------------------------------------------------------------------------------------------

    /**
     * Every surface knows the warehouse has two cranes (ADR-075 §15): each dock's goggle data names its crane and the
     * count and survives its tag, the controller's goggle summary lists both cranes and survives its tag, and the
     * terminal counts them. With {@code aisle.maxCranesPerNetwork = 1} none of it exists.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void goggleslistseverycrane(GameTestHelper helper) {
        build(helper, LINE, true);
        BlockPos terminal = LINE.rack(5, Side.RIGHT);
        helper.setBlock(terminal, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, Line.away(Side.RIGHT).getOpposite()));
        boolean several = severalCranes();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertReady(helper, LINE);
                    if (several) {
                        for (BlockPos dock : LINE.docks())
                            helper.assertTrue(crane(helper, dock).goggleInfo().traffic().isPresent(),
                                    "crane " + crane(helper, dock).craneNumber() + " published its traffic info");
                    }
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper, LINE);
                    controller.onGoggleObserved();
                    ControllerGoggleSummary summary = controller.summary();
                    CompoundTag summaryTag = new CompoundTag();
                    summary.write(summaryTag);
                    helper.assertValueEqual(ControllerGoggleSummary.read(summaryTag), summary,
                            "the controller's summary survives its tag");
                    TerminalScreenStatus status = TerminalScreenStatus.of(terminalAt(helper, terminal).status());
                    if (!several) {
                        helper.assertTrue(summary.cranes().isEmpty(), "one crane lists no cranes");
                        helper.assertFalse(summaryTag.contains("Cranes"), "and writes nothing of them");
                        helper.assertTrue(crane(helper, LINE.dock()).goggleInfo().traffic().isEmpty(),
                                "its dock says nothing about other cranes");
                        helper.assertValueEqual(status.cranes().total(), 1, "the terminal counts one crane");
                        helper.assertFalse(status.severalCranes(), "and keeps its one-crane line");
                        return;
                    }
                    for (BlockPos dock : LINE.docks()) {
                        StackerCraneBlockEntity crane = crane(helper, dock);
                        CraneTrafficInfo info = crane.goggleInfo().traffic().orElseThrow();
                        helper.assertValueEqual(info.number(), crane.craneNumber(), "the goggles name the crane");
                        helper.assertValueEqual(info.craneCount(), 2, "and how many there are");
                        helper.assertValueEqual(info.servedLevels(), 0, "both masts serve every level");
                        CompoundTag goggles = new CompoundTag();
                        crane.goggleInfo().write(goggles);
                        helper.assertValueEqual(CraneGoggleInfo.read(goggles), crane.goggleInfo(),
                                "crane " + info.number() + "'s goggle data survives its tag");
                    }
                    CraneRoster roster = summary.cranes().orElseThrow();
                    helper.assertValueEqual(roster.counts().total(), 2, "the summary lists two cranes");
                    helper.assertValueEqual(roster.entries().size(), 2, "both by name");
                    helper.assertValueEqual(roster.entries().get(0).number(), 1, "crane 1 first");
                    helper.assertValueEqual(roster.entries().get(1).number(), 2, "then crane 2");
                    helper.assertValueEqual(roster.refusedDocks(), 0, "no dock left out");
                    helper.assertValueEqual(controller.craneCounts(), roster.counts(),
                            "the terminal's counts are the summary's");
                    helper.assertValueEqual(status.cranes(), roster.counts(), "and reach the terminal's screen");
                    helper.assertTrue(status.severalCranes(), "which counts the cranes");
                })
                .thenSucceed();
    }

    /**
     * A warehouse of one crane shows exactly what it showed up to 0.17.1: no traffic info on the dock, no crane list on
     * the controller and neither key in their tags; the terminal counts one crane and keeps its line.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void onecranegogglesstayasbefore(GameTestHelper helper) {
        build(helper, LINE, false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper, LINE).status(), ControllerStatus.READY,
                        "the warehouse is ready"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper, LINE);
                    controller.onGoggleObserved();
                    CompoundTag summaryTag = new CompoundTag();
                    controller.summary().write(summaryTag);
                    helper.assertFalse(summaryTag.contains("Cranes"), "the controller lists no cranes");
                    helper.assertTrue(controller.craneRoster().isEmpty(), "and has no list to give");
                    StackerCraneBlockEntity dock = crane(helper, LINE.dock());
                    dock.onGoggleObserved();
                    CompoundTag goggles = new CompoundTag();
                    dock.goggleInfo().write(goggles);
                    helper.assertFalse(goggles.contains("Traffic"), "the dock says nothing about other cranes");
                    helper.assertTrue(dock.joinStatus().isEmpty(), "nor about joining anything");
                    helper.assertValueEqual(controller.craneCounts().total(), 1, "one crane counted");
                    helper.assertFalse(controller.craneCounts().several(), "which no surface names as a count");
                })
                .thenSucceed();
    }

    /**
     * The crane that waits says for whom and where (ADR-075 §15.1): crane 2's traffic info names crane 1 and the rail it
     * holds, the controller's list shows crane 2 waiting for crane 1, the counts have one crane waiting, and the crane
     * that drives has reserved its stretch and knows how long its trip still takes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void waitingcranesayswhy(GameTestHelper helper) {
        build(helper, LINE, true);
        storage(helper, LINE, 12, Side.LEFT, GOLD.toStack(COUNT));
        BlockPos westOutput = LINE.rack(0, Side.RIGHT);
        output(helper, westOutput, Side.RIGHT);
        storage(helper, LINE, 10, Side.RIGHT, IRON.toStack(COUNT));
        BlockPos eastOutput = LINE.rack(MEMBER_X, Side.LEFT);
        output(helper, eastOutput, Side.LEFT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        watch(helper, census, LINE);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        AtomicBoolean named = new AtomicBoolean();
        AtomicBoolean listed = new AtomicBoolean();
        AtomicBoolean reserved = new AtomicBoolean();
        AtomicBoolean timed = new AtomicBoolean();
        helper.onEachTick(() -> {
            StackerCraneBlockEntity waiting = crane(helper, LINE.memberDock());
            Optional<CraneTrafficInfo> info = waiting.goggleInfo().traffic();
            if (waiting.isTrafficHeld() && info.isPresent() && info.get().line() == CraneTrafficInfo.Line.PATH_HELD
                    && info.get().otherCrane() == 1 && info.get().hasHeldRail())
                named.set(true);
            WarehouseControllerBlockEntity controller = controller(helper, LINE);
            Optional<CraneRoster> roster = controller.craneRoster();
            if (roster.isPresent() && roster.get().entries().get(1).activity() == CraneRoster.Activity.WAITING
                    && roster.get().entries().get(1).otherCrane() == 1 && controller.craneCounts().waiting() == 1)
                listed.set(true);
            StackerCraneBlockEntity driving = crane(helper, LINE.dock());
            if (driving.trafficInfo().filter(CraneTrafficInfo::hasReservation).isPresent())
                reserved.set(true);
            if (driving.currentJob().isPresent() && driving.remainingTripTicks().isPresent())
                timed.set(true);
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, LINE))
                .thenExecute(() -> {
                    request(helper, LINE, westOutput, GOLD, COUNT);
                    request(helper, LINE, eastOutput, IRON, COUNT);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, westOutput, GOLD), (long) COUNT, "the gold arrived");
                    helper.assertValueEqual(stationCount(helper, eastOutput, IRON), (long) COUNT, "the iron arrived");
                    assertIdle(helper, LINE);
                })
                .thenExecute(() -> {
                    helper.assertTrue(named.get(), "crane 2's goggles named crane 1 and the rail it holds");
                    helper.assertTrue(listed.get(), "the controller listed crane 2 as waiting for crane 1");
                    helper.assertTrue(reserved.get(), "crane 1 showed the stretch it reserved");
                    helper.assertTrue(timed.get(), "and how long its trip still takes");
                })
                .thenSucceed();
    }

    /**
     * Home points name the crane that waits at them (ADR-075 §8.4): with three home points on a line of two cranes,
     * the one nearest each dock is that crane's — "Crane 1 waits here", "Crane 2 waits here" — and the third says the
     * warehouse has more home points than cranes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void homepointsnametheircrane(GameTestHelper helper) {
        build(helper, LINE, true);
        BlockPos west = LINE.rack(2, Side.LEFT);
        BlockPos middle = LINE.rack(9, Side.LEFT);
        BlockPos east = LINE.rack(11, Side.LEFT);
        for (BlockPos home : List.of(west, middle, east))
            homePoint(helper, home, Side.LEFT);
        if (!severalCranes()) {
            assertLegacy(helper, LINE);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertReady(helper, LINE);
                    helper.assertValueEqual(homePointAt(helper, west).summary().crane(), 1, "crane 1 waits west");
                    helper.assertValueEqual(homePointAt(helper, east).summary().crane(), 2, "crane 2 waits east");
                })
                .thenExecute(() -> {
                    for (BlockPos home : List.of(west, middle, east))
                        homePointAt(helper, home).onGoggleObserved();
                    HomePointGoggleSummary first = homePointAt(helper, west).summary();
                    helper.assertValueEqual(first.status(), HomePointStatus.SERVING, "the west home point serves");
                    helper.assertValueEqual(first.statusKey(), CraneTrafficLang.GOGGLES_HOME_POINT_SERVING_CRANE,
                            "and names its crane");
                    helper.assertValueEqual(homePointAt(helper, east).summary().crane(), 2,
                            "a look at it names crane 2 too");
                    HomePointGoggleSummary left = homePointAt(helper, middle).summary();
                    helper.assertValueEqual(left.status(), HomePointStatus.SECOND, "the middle one is left over");
                    helper.assertValueEqual(left.statusKey(), CraneTrafficLang.GOGGLES_HOME_POINT_SECOND_CRANES,
                            "because there are more home points than cranes");
                    helper.assertValueEqual(left.crane(), 0, "and no crane waits there");
                    CompoundTag tag = new CompoundTag();
                    first.write(tag);
                    helper.assertValueEqual(HomePointGoggleSummary.read(tag), first, "the summary survives its tag");
                })
                .thenSucceed();
    }

    /** A dock beyond the maximum says why it is no crane, and the controller counts it as left out. */
    @GameTest(template = AISLE_16X10X7, batch = CAP_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void dockbeyondthemaximumsayswhy(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCranesPerNetwork, 2);
        build(helper, LINE, true);
        BlockPos side = LINE.rack(7, Side.LEFT);
        placeMotor(helper, side.below());
        helper.setBlock(side, dockState(Direction.SOUTH));
        motor(helper, side.below()).generatedSpeed.setValue(TEST_RPM);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper, LINE).craneCount(), 2, "two cranes, the maximum");
                    helper.assertTrue(crane(helper, side).isMember(), "the dock beside the line joined");
                })
                .thenExecute(() -> {
                    StackerCraneBlockEntity refused = crane(helper, LINE.memberDock());
                    refused.onGoggleObserved();
                    DockJoinStatus join = refused.joinStatus().orElseThrow();
                    helper.assertValueEqual(join.reason(), DockJoinStatus.Reason.MAX_CRANES, "the maximum is named");
                    helper.assertValueEqual(join.maxCranes(), 2, "with its value");
                    helper.assertValueEqual(join.letter(), controller(helper, LINE).aisleLetter(),
                            "and the warehouse it faces");
                    CompoundTag tag = new CompoundTag();
                    join.write(tag);
                    helper.assertValueEqual(DockJoinStatus.read(tag), Optional.of(join), "it survives its tag");
                    crane(helper, side).onGoggleObserved();
                    helper.assertTrue(crane(helper, side).joinStatus().isEmpty(), "a crane of it has nothing to say");
                    CraneRoster roster = controller(helper, LINE).craneRoster().orElseThrow();
                    helper.assertValueEqual(roster.refusedDocks(), 1, "the controller counts the dock left out");
                    helper.assertValueEqual(roster.maxCranes(), 2, "and the maximum");
                })
                .thenSucceed();
    }

    /**
     * The foreign-dock hint: a dock with a controller of its own on another warehouse's rails says it is a warehouse by
     * itself, and names the warehouse it faces. Two warehouses on one line each say it about the other, with any
     * maximum.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void dockwithitsowncontrollersayswhy(GameTestHelper helper) {
        Line line = new Line(LINE.z(), RAILS - 1);
        build(helper, line, true);
        BlockPos otherController = line.memberDock().east();
        helper.setBlock(otherController, controllerState(Direction.WEST));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper, line).status(), ControllerStatus.READY, "ready");
                    helper.assertValueEqual(controllerAt(helper, otherController).status(), ControllerStatus.READY,
                            "and so is the other warehouse");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    StackerCraneBlockEntity foreign = crane(helper, line.memberDock());
                    foreign.onGoggleObserved();
                    DockJoinStatus there = foreign.joinStatus().orElseThrow();
                    helper.assertValueEqual(there.reason(), DockJoinStatus.Reason.OWN_CONTROLLER,
                            "a dock with a controller of its own is a warehouse by itself");
                    helper.assertValueEqual(there.letter(), controller(helper, line).aisleLetter(),
                            "naming the warehouse it faces");
                    StackerCraneBlockEntity own = crane(helper, line.dock());
                    own.onGoggleObserved();
                    helper.assertValueEqual(own.joinStatus().map(DockJoinStatus::reason),
                            Optional.of(DockJoinStatus.Reason.OWN_CONTROLLER), "and so is this one, to the other");
                    helper.assertValueEqual(own.joinStatus().map(DockJoinStatus::letter),
                            Optional.of(controllerAt(helper, otherController).aisleLetter()), "which it names");
                })
                .thenSucceed();
    }

    // --- building ----------------------------------------------------------------------------------------------------

    /**
     * One straight warehouse along +X at {@code z}: controller at x = 0, its dock at x = 1, rails at x = 2..(1 +
     * rails), and a member dock at x = rails + 2 facing back.
     */
    private record Line(int z, int rails) {
        BlockPos controller() {
            return new BlockPos(0, BASE_Y, z);
        }

        BlockPos dock() {
            return new BlockPos(1, BASE_Y, z);
        }

        BlockPos memberDock() {
            return new BlockPos(rails + 2, BASE_Y, z);
        }

        List<BlockPos> docks() {
            return List.of(dock(), memberDock());
        }

        BlockPos motor(BlockPos dock) {
            return new BlockPos(dock.getX(), FLOOR_Y, dock.getZ());
        }

        /** The aisle block at position {@code x}: the warehouse's own dock is 0. */
        BlockPos cell(int x) {
            return new BlockPos(1 + x, BASE_Y, z);
        }

        /** The rack position beside position {@code x}: north on the left, south on the right. */
        BlockPos rack(int x, Side side) {
            return cell(x).relative(away(side));
        }

        /** The inventory behind the storage location at {@code (x, side)}. */
        BlockPos behind(int x, Side side) {
            return rack(x, side).relative(away(side));
        }

        static Direction away(Side side) {
            return side == Side.LEFT ? Direction.NORTH : Direction.SOUTH;
        }
    }

    private static void build(GameTestHelper helper, Line line, boolean member) {
        placeMotor(helper, line.motor(line.dock()));
        helper.setBlock(line.dock(), dockState(Direction.EAST));
        for (int x = 1; x <= line.rails(); x++)
            helper.setBlock(line.cell(x), WarehouseRailBlock.along(Direction.Axis.X));
        helper.setBlock(line.controller(), controllerState(Direction.EAST));
        motor(helper, line.motor(line.dock())).generatedSpeed.setValue(TEST_RPM);
        if (member) {
            placeMotor(helper, line.motor(line.memberDock()));
            helper.setBlock(line.memberDock(), dockState(Direction.WEST));
            motor(helper, line.motor(line.memberDock())).generatedSpeed.setValue(TEST_RPM);
        }
    }

    private static void placeMotor(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static net.minecraft.world.level.block.state.BlockState dockState(Direction facing) {
        return WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING,
                facing);
    }

    private static net.minecraft.world.level.block.state.BlockState controllerState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, facing);
    }

    private static net.minecraft.world.level.block.state.BlockState outputState(Direction towardsAisle) {
        return WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState().setValue(WarehouseOutputBlock.FACING, towardsAisle);
    }

    /** A chest with {@code contents} behind an interface at {@code (x, side)} of {@code line}; the chest position. */
    private static BlockPos storage(GameTestHelper helper, Line line, int x, Side side, ItemStack... contents) {
        BlockPos chest = line.behind(x, side);
        placeStorageAt(helper, line.rack(x, side), Line.away(side), contents);
        return chest;
    }

    /** A chest with {@code contents} one block beyond an interface at {@code rack}, which faces {@code away}. */
    private static void placeStorageAt(GameTestHelper helper, BlockPos rack, Direction away, ItemStack... contents) {
        BlockPos chest = rack.relative(away);
        helper.setBlock(chest, Blocks.CHEST);
        for (ItemStack stack : contents)
            insert(helper, chest, stack);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, away));
    }

    private static void output(GameTestHelper helper, BlockPos rack, Side side) {
        helper.setBlock(rack, outputState(Line.away(side).getOpposite()));
    }

    private static void input(GameTestHelper helper, BlockPos rack, Side side) {
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Line.away(side).getOpposite()));
    }

    private static void homePoint(GameTestHelper helper, BlockPos rack, Side side) {
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                .setValue(WarehouseHomePointBlock.FACING, Line.away(side).getOpposite()));
    }

    private static void insert(GameTestHelper helper, BlockPos pos, ItemStack stack) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            return;
        }
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        helper.assertTrue(rest.isEmpty(), "the inventory at " + pos + " rejected " + rest);
    }

    private static void request(GameTestHelper helper, Line line, BlockPos output, ItemKey key, int amount) {
        accepted(helper, controller(helper, line).request(helper.absolutePos(output), key, amount), key.toString());
    }

    private static void accepted(GameTestHelper helper, RequestResult result, String what) {
        helper.assertTrue(result.isAccepted(), "the request for " + what + " is accepted");
    }

    /** Uses a wrench on the top face of a test-relative position, like a player who is not sneaking. */
    private static void wrench(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 hit = Vec3.atCenterOf(absolute).add(0.0, 0.5, 0.0);
        helper.useBlock(pos, player, new BlockHitResult(hit, Direction.UP, absolute, false));
    }

    // --- assertions --------------------------------------------------------------------------------------------------

    /** Whether the server lets a warehouse have several cranes at all; {@code 1} is the off switch. */
    private static boolean severalCranes() {
        return WareworksConfig.maxCranesPerNetwork() > 1;
    }

    /**
     * The off switch: the second dock is no crane of the warehouse, the discovery stops at it as at any other dock, and
     * neither the dock nor the controller writes anything of several cranes.
     */
    private static void assertLegacy(GameTestHelper helper, Line line) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper, line).status(), ControllerStatus.READY,
                        "the warehouse is ready"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper, line);
                    helper.assertValueEqual(controller.craneCount(), 1, "one crane");
                    helper.assertTrue(crane(helper, line.memberDock()).membership().isEmpty(),
                            "the second dock is no crane of it");
                    RailNetwork network = crane(helper, line.dock()).discoveredNetwork().orElseThrow();
                    helper.assertValueEqual(network.stop(), NetworkStop.SECOND_DOCK, "it is a wall, as in 0.17.1");
                    helper.assertValueEqual(helper.absolutePos(line.dock()).offset(network.stopDx(), 0,
                            network.stopDz()), helper.absolutePos(line.memberDock()), "named where it stands");
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    helper.assertFalse(crane(helper, line.dock()).saveWithoutMetadata(registries)
                            .contains(CraneMembership.TAG), "the dock saves what it always saved");
                    helper.assertFalse(controller.saveWithoutMetadata(registries).contains("Cranes"),
                            "and so does the controller");
                })
                .thenSucceed();
    }

    /** Both cranes listed and linked. */
    private static void assertJoined(GameTestHelper helper, Line line) {
        WarehouseControllerBlockEntity controller = controller(helper, line);
        helper.assertValueEqual(controller.craneCount(), 2, "two cranes");
        StackerCraneBlockEntity member = crane(helper, line.memberDock());
        helper.assertTrue(member.isMember(), "the second dock joined");
        helper.assertTrue(member.isControllerLinked(), "and is linked");
    }

    /** The warehouse is ready to plan: members recorded and read, and — with several cranes allowed — both joined. */
    private static void assertReady(GameTestHelper helper, Line line) {
        WarehouseControllerBlockEntity controller = controller(helper, line);
        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller ready");
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "storage read");
        if (severalCranes())
            assertJoined(helper, line);
    }

    private static void assertIdle(GameTestHelper helper, Line line) {
        for (BlockPos dock : line.docks()) {
            StackerCraneBlockEntity crane = crane(helper, dock);
            helper.assertTrue(crane.currentJob().isEmpty(), "crane " + crane.craneNumber() + " has no job");
            helper.assertTrue(crane.heldItems().isEmpty(), "and holds nothing");
        }
    }

    /** The census and "no two cranes on one block", on every tick. */
    private static void watch(GameTestHelper helper, Map<ItemKey, Long> census, Line line) {
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while the cranes work");
            assertApart(helper, line.docks());
        });
    }

    /** No two cranes cover one block (ADR-075 I1/I2, as the world sees it). */
    private static void assertApart(GameTestHelper helper, List<BlockPos> docks) {
        List<Set<BlockPos>> blocks = new ArrayList<>();
        for (BlockPos dock : docks) {
            BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(dock));
            if (be instanceof StackerCraneBlockEntity crane)
                blocks.add(standingBlocks(crane));
        }
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                if (!Collections.disjoint(blocks.get(i), blocks.get(j)))
                    helper.fail("two cranes share a block: " + blocks.get(i) + " and " + blocks.get(j));
            }
        }
    }

    /** The world blocks the crane's chassis covers: {@code floor(x)} and {@code ceil(x)} of its pose. */
    private static Set<BlockPos> standingBlocks(StackerCraneBlockEntity crane) {
        WarehouseLayout frame = crane.warehouse();
        CranePose pose = crane.craneState().pose();
        Set<BlockPos> blocks = new HashSet<>();
        for (double x : new double[] {Math.floor(pose.x()), Math.ceil(pose.x())}) {
            Vec3 offset = frame.railOffset(pose.branch(), x);
            blocks.add(frame.dock().offset((int) Math.round(offset.x), 0, (int) Math.round(offset.z)));
        }
        return blocks;
    }

    private static boolean busy(GameTestHelper helper, BlockPos dock) {
        return crane(helper, dock).currentJob().isPresent();
    }

    private static List<TransportJob<ItemKey, RackPosition>> jobs(GameTestHelper helper, Line line) {
        List<TransportJob<ItemKey, RackPosition>> jobs = new ArrayList<>();
        for (BlockPos dock : line.docks())
            crane(helper, dock).currentJob().ifPresent(jobs::add);
        return jobs;
    }

    private static TrafficVerdict verdict(GameTestHelper helper, Line line, int number) {
        return controller(helper, line).traffic().map(traffic -> traffic.verdict(number))
                .orElse(TrafficVerdict.CLEAR);
    }

    private static RackPosition rackOf(GameTestHelper helper, Line line, BlockPos pos) {
        return controller(helper, line).locationAt(helper.absolutePos(pos)).map(record -> record.position())
                .orElseGet(() -> {
                    helper.fail("no warehouse member", pos);
                    throw new AssertionError("unreachable");
                });
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(GameTestHelper helper, Line line) {
        return controllerAt(helper, line.controller());
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing warehouse controller", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static StackerCraneBlockEntity crane(GameTestHelper helper, BlockPos pos) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing stacker crane dock", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static CreativeMotorBlockEntity motor(GameTestHelper helper, BlockPos pos) {
        CreativeMotorBlockEntity be = com.simibubi.create.AllBlockEntityTypes.MOTOR
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing creative motor", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static WarehouseHomePointBlockEntity homePointAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof WarehouseHomePointBlockEntity home)
            return home;
        helper.fail("missing home point", pos);
        throw new AssertionError("unreachable");
    }

    private static WarehouseTerminalBlockEntity terminalAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof WarehouseTerminalBlockEntity terminal)
            return terminal;
        helper.fail("missing terminal", pos);
        throw new AssertionError("unreachable");
    }

    private static WarehouseProductionBlockEntity productionAt(GameTestHelper helper, BlockPos pos) {
        WarehouseProductionBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing production station", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static long countIn(GameTestHelper helper, BlockPos pos, ItemKey key) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
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

    /** Items of {@code key} in the buffer of the station at a test-relative position; 0 if there is none. */
    private static long stationCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        return be instanceof WarehouseStationBlockEntity station ? station.bufferedItems().count(key) : 0L;
    }
}
