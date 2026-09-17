package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.TransportJob;
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
 * Robustness GameTests of M5 for the cases of {@code docs/warehouse-system.md} §8 that a world test can reach: an aisle
 * that shrinks under a loaded crane, a dock turned away while the crane carries items, two aisles beside each other, two
 * aisles sharing one rack plane, and the extremes of the server config (§9).
 * <p>
 * Every test checks <b>item conservation on every tick</b> with an {@link ItemCensus} of the whole test area
 * (inventories, station buffers, handling heads, dropped items, by exact item identity). The expectation changes only in
 * the step where the test plays the player.
 * <p>
 * <b>Config tests get one batch each.</b> The tests of one batch run at the same time, so a test that changes a server
 * config value would change it for its neighbours; batches run one after another ({@code GameTestRunner#runBatch}). Each
 * config batch has an {@code @AfterBatch} method that restores every override, also after a failure
 * ({@link ConfigOverrides}).
 * <p>
 * What GameTests cannot cover is in the {@code robustness} scenario of the dev harness
 * ({@code dev.wareworks.dev.RobustnessVisualScenario}, {@code ./gradlew runRobustnessTest}): test areas are
 * force-loaded, so chunk unloads and a real save, quit and rejoin need a running game.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RobustnessGameTests {
    /** One batch per config test: config values are global, and the tests of one batch run at the same time. */
    static final String CONFIG_SPEED_BATCH = "wareworksconfigspeed";
    static final String CONFIG_BUFFER_BATCH = "wareworksconfigbuffers";
    static final String CONFIG_DISPATCH_BATCH = "wareworksconfigdispatch";
    static final String CONFIG_LIMITS_BATCH = "wareworksconfiglimits";

    private static final int AISLE_Z = 3;
    private static final int SECOND_AISLE_Z = 9;
    private static final int RAILS = 6;
    /** Rails left after the aisle is shortened under the travelling crane. */
    private static final int SHRUNK_RAILS = 2;
    private static final int TEST_RPM = 128;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** At least two hold retries ({@code holdRetryTicks} = 40). */
    private static final int HOLD_OBSERVE_TICKS = 100;
    /** Long enough for several dispatch intervals to pass without anything happening. */
    private static final int QUIET_OBSERVE_TICKS = 60;
    /** Ticks a dispatch may take beyond its configured interval (planning plus the assignment tick). */
    private static final int DISPATCH_SLACK_TICKS = 20;
    private static final int FAST_DISPATCH_TICKS = 10;
    private static final int SLOW_DISPATCH_INTERVAL = 200;
    /** Upper end of the {@code aisle.maxMastHeight} config range ({@code docs/warehouse-system.md} §9). */
    private static final int MAX_CONFIGURED_MAST_HEIGHT = 64;

    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition CLOSE = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition NEAR = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition FAR = new RackPosition(5, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private static final int STACK = 64;
    private static final int STORED_IRON = 32;
    /** One item in the far location, so consolidation makes it the store target. */
    private static final int SEED_IRON = 1;
    private static final int STOCKED_DIAMONDS = 24;
    private static final int STOCKED_COBBLE = 24;
    private static final int REQUESTED = 10;

    private RobustnessGameTests() {
    }

    // --- aisle shrinks under a loaded crane -------------------------------------------------------------------------

    /**
     * The aisle is shortened while the crane is beyond the new length carrying items ({@code docs/stacker-crane.md} §3):
     * the target outside the new geometry counts as missing, the held items are rerouted into the aisle, the crane comes
     * back inside it, and the inventory left outside keeps its contents. Nothing is lost or duplicated.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void craneAisleShrinksWithHeldItems(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(FAR, IRON.toStack(SEED_IRON));
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, SEED_IRON);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "aisle shrink"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertValueEqual(dock.currentJob().map(TransportJob::target), Optional.of(FAR),
                            "the job targets the location that already holds iron");
                    aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON);
                    helper.assertTrue(dock.craneState().pose().x() > SHRUNK_RAILS,
                            "the crane must be beyond the shortened aisle, but is at " + dock.craneState().pose().x());
                })
                .thenExecute(() -> {
                    for (int position = SHRUNK_RAILS + 1; position <= RAILS; position++)
                        helper.setBlock(aisle.dockPos().relative(AisleFixture.AISLE, position), Blocks.AIR);
                    aisle.dock().requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.dock().geometry(),
                            AisleGeometry.of(SHRUNK_RAILS, aisle.dock().mastHeight()), "geometry after the shrink");
                    helper.assertValueEqual(aisle.controller().storageLocations().size(), 1,
                            "only the location inside the shortened aisle is still a member");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON,
                            "the held iron was rerouted into the shortened aisle");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) SEED_IRON,
                            "the inventory outside the aisle keeps its items");
                    helper.assertTrue(aisle.dock().craneState().pose().x() <= SHRUNK_RAILS,
                            "the crane must be back inside the aisle, but is at " + aisle.dock().craneState().pose().x());
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STORED_IRON,
                            "the stock index only counts locations inside the aisle");
                })
                .thenSucceed();
    }

    // --- the controller loses its aisle while the crane carries items -----------------------------------------------

    /**
     * The dock is turned away (a command or a structure; a survival wrench refuses this, see
     * {@code scenariodockrotationblocked}) while the crane carries items: the controller loses its aisle and releases
     * everything, the crane keeps its job and its items and holds them because no controller answers a reroute, and
     * turning the dock back lets the controller adopt the job and finish it. Nothing is lost or duplicated.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void craneDockTurnedAwayWhileHolding(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(FAR, IRON.toStack(SEED_IRON));
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, SEED_IRON);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "dock turned away"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                // Turn the dock while the crane is still near the dock end, so every aisle position it checks afterwards
                // stays inside the test structure whatever direction the aisle points in.
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertValueEqual(dock.heldItems().count(IRON), STORED_IRON, "the crane picked the iron");
                    helper.assertTrue(dock.craneState().pose().x() <= SHRUNK_RAILS,
                            "waiting until the crane is still near the dock, but it is at "
                                    + dock.craneState().pose().x());
                })
                .thenExecute(() -> {
                    StackerCraneBlockEntity before = aisle.dock();
                    turnDock(helper, aisle, Direction.SOUTH);
                    helper.assertTrue(aisle.dock() == before, "turning keeps the block entity and its items");
                    helper.assertValueEqual(aisle.dock().heldItems().count(IRON), STORED_IRON,
                            "the crane keeps the items it carries");
                    helper.assertTrue(aisle.dock().currentJob().isPresent(), "the crane keeps its job");
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.status(), ControllerStatus.DOCK_MISALIGNED,
                            "the controller lost its aisle to the turned dock");
                    helper.assertValueEqual(controller.storageLocations().size(), 0, "its records are cleared");
                    helper.assertTrue(controller.reservations().isEmpty(), "its reservations are released");
                    helper.assertFalse(aisle.dock().isControllerLinked(), "the crane has no controller any more");
                })
                .thenExecuteAfter(HOLD_OBSERVE_TICKS, () -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertValueEqual(dock.craneState().phase(), CranePhase.HOLDING,
                            "without a controller the crane holds what it carries");
                    helper.assertValueEqual(dock.heldItems().count(IRON), STORED_IRON, "still carrying every item");
                    helper.assertTrue(dock.currentJob().isPresent(), "and still owns the job");
                })
                .thenExecute(() -> turnDock(helper, aisle, AisleFixture.AISLE))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().status(), ControllerStatus.READY, "the aisle is back");
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) (SEED_IRON + STORED_IRON),
                            "the carried iron is stored once the aisle exists again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- two aisles beside each other --------------------------------------------------------------------------------

    /**
     * Two aisles next to each other keep their own members, stock, requests and crane: neither controller records the
     * other's storage location, a request for an item only the neighbour stocks is refused, and two requests submitted at
     * the same time are each served from their own aisle.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void twoAislesStayIndependent(GameTestHelper helper) {
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS);
        AisleFixture second = new AisleFixture(helper, SECOND_AISLE_Z, RAILS);
        first.build(true);
        first.storage(NEAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        first.output(OUTPUT);
        second.build(true);
        second.storage(NEAR, COBBLE.toStack(STOCKED_COBBLE));
        second.output(OUTPUT);
        BlockPos firstTrigger = first.rackPos(OUTPUT).relative(Direction.SOUTH);
        BlockPos secondTrigger = second.rackPos(OUTPUT).relative(Direction.SOUTH);
        Map<ItemKey, Long> expected = ItemCensus.of(DIAMOND, STOCKED_DIAMONDS, COBBLE, STOCKED_COBBLE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "two aisles"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    first.assertReady(1, 0, 1);
                    second.assertReady(1, 0, 1);
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity a = first.controller();
                    WarehouseControllerBlockEntity b = second.controller();
                    helper.assertTrue(a.locationAt(second.absoluteRackPos(NEAR)).isEmpty(),
                            "the first controller must not record the second aisle's storage location");
                    helper.assertTrue(b.locationAt(first.absoluteRackPos(NEAR)).isEmpty(),
                            "the second controller must not record the first aisle's storage location");
                    helper.assertValueEqual(a.countOf(COBBLE), 0L, "the first aisle stocks no cobblestone");
                    helper.assertValueEqual(b.countOf(DIAMOND), 0L, "the second aisle stocks no diamonds");
                    helper.assertValueEqual(a.countOf(DIAMOND), (long) STOCKED_DIAMONDS, "its own diamonds");
                    helper.assertValueEqual(b.countOf(COBBLE), (long) STOCKED_COBBLE, "its own cobblestone");
                    assertControllerOf(helper, first, first.absoluteRackPos(NEAR), "the first aisle's interface");
                    assertControllerOf(helper, second, second.absoluteRackPos(NEAR), "the second aisle's interface");

                    RequestResult refused = a.request(first.absoluteRackPos(OUTPUT), COBBLE, REQUESTED);
                    helper.assertValueEqual(refused.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "an item only the neighbour stocks is not available here");
                })
                .thenExecute(() -> {
                    first.motor().generatedSpeed.setValue(TEST_RPM);
                    second.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(first.dock().canAcceptJob(), "the first crane is ready");
                    helper.assertTrue(second.dock().canAcceptJob(), "the second crane is ready");
                })
                .thenExecute(() -> {
                    first.requestAt(OUTPUT, DIAMOND.toStack(), REQUESTED, firstTrigger);
                    second.requestAt(OUTPUT, COBBLE.toStack(), REQUESTED, secondTrigger);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(first.stationCount(OUTPUT, DIAMOND), (long) REQUESTED,
                            "the first output received its diamonds");
                    helper.assertValueEqual(second.stationCount(OUTPUT, COBBLE), (long) REQUESTED,
                            "the second output received its cobblestone");
                    first.assertIdleAndEmpty();
                    second.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(first.stationCount(OUTPUT, COBBLE), 0L,
                            "no cobblestone ended up in the first aisle");
                    helper.assertValueEqual(second.stationCount(OUTPUT, DIAMOND), 0L,
                            "no diamonds ended up in the second aisle");
                    helper.assertValueEqual(first.storedAt(NEAR, DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED),
                            "diamonds left in the first aisle");
                    helper.assertValueEqual(second.storedAt(NEAR, COBBLE), (long) (STOCKED_COBBLE - REQUESTED),
                            "cobblestone left in the second aisle");
                    helper.assertValueEqual(first.controller().openRequestCount(), 0, "first request served");
                    helper.assertValueEqual(second.controller().openRequestCount(), 0, "second request served");
                })
                .thenSucceed();
    }

    // --- two aisles sharing one rack plane ---------------------------------------------------------------------------

    /** World x of the storage location both opposing aisles reach. */
    private static final int SHARED_STORAGE_X = 5;
    private static final BlockPos SHARED_DOCK_A = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos SHARED_DOCK_B = new BlockPos(10, BASE_Y, AISLE_Z);
    private static final int SHARED_IRON = 40;
    private static final int SHARED_REQUEST = 32;

    /**
     * Two cranes facing each other on one rail line reach the same storage location ({@code docs/warehouse-system.md} §4:
     * aisles may share a rack plane). Both controllers record it and both index its contents, so together they can
     * promise more than exists. This test asserts the part that must never break: the real transfers are authoritative,
     * so <b>no item is duplicated or lost</b> and both outputs together never receive more than the chest held.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void twoAislesShareARackPlane(GameTestHelper helper) {
        BlockPos sharedInterface = new BlockPos(SHARED_STORAGE_X, BASE_Y, AISLE_Z - 1);
        BlockPos sharedChest = sharedInterface.north();
        BlockPos outputA = new BlockPos(2, BASE_Y, AISLE_Z + 1);
        BlockPos outputB = new BlockPos(9, BASE_Y, AISLE_Z + 1);
        buildOpposingAisles(helper, sharedInterface, sharedChest, outputA, outputB);
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, SHARED_IRON);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "shared rack plane"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (BlockPos controllerPos : List.of(SHARED_DOCK_A.west(), SHARED_DOCK_B.east())) {
                        WarehouseControllerBlockEntity controller = controllerAt(helper, controllerPos);
                        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller ready");
                        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "locations read");
                        helper.assertValueEqual(controller.locationAt(helper.absolutePos(sharedInterface))
                                .map(LocationRecord::kind), Optional.of(LocationKind.STORAGE),
                                "both aisles reach the shared storage location");
                        helper.assertValueEqual(controller.countOf(IRON), (long) SHARED_IRON,
                                "both aisles index the shared inventory");
                    }
                })
                .thenExecute(() -> {
                    motorAt(helper, SHARED_DOCK_A.below()).generatedSpeed.setValue(TEST_RPM);
                    motorAt(helper, SHARED_DOCK_B.below()).generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(dockAt(helper, SHARED_DOCK_A).canAcceptJob(), "the first crane is ready");
                    helper.assertTrue(dockAt(helper, SHARED_DOCK_B).canAcceptJob(), "the second crane is ready");
                })
                .thenExecute(() -> {
                    // Both outputs are aligned for both aisles, so the registry has to break the tie. It does so by
                    // dock distance, not by map iteration order: each output resolves to the controller of the dock it
                    // stands closer to, which is what a redstone request at that output would use (§4).
                    assertControllerAt(helper, outputA, SHARED_DOCK_A.west(), "the output beside the first dock");
                    assertControllerAt(helper, outputB, SHARED_DOCK_B.east(), "the output beside the second dock");
                    // The requests below name their controller, so the test states which aisle serves which output.
                    assertAccepted(helper, controllerAt(helper, SHARED_DOCK_A.west())
                            .request(helper.absolutePos(outputA), IRON, SHARED_REQUEST), "the first request");
                    assertAccepted(helper, controllerAt(helper, SHARED_DOCK_B.east())
                            .request(helper.absolutePos(outputB), IRON, SHARED_REQUEST), "the second request");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(countAt(helper, sharedChest, IRON), 0L, "the shared chest is emptied");
                    assertIdleAndEmpty(helper, SHARED_DOCK_A);
                    assertIdleAndEmpty(helper, SHARED_DOCK_B);
                })
                .thenExecuteAfter(QUIET_OBSERVE_TICKS, () -> {
                    long first = outputCount(helper, outputA, IRON);
                    long second = outputCount(helper, outputB, IRON);
                    helper.assertValueEqual(first + second, (long) SHARED_IRON,
                            "both outputs together hold exactly what the chest held");
                    helper.assertTrue(first <= SHARED_REQUEST && second <= SHARED_REQUEST,
                            "no output received more than it requested: " + first + " and " + second);
                    assertIdleAndEmpty(helper, SHARED_DOCK_A);
                    assertIdleAndEmpty(helper, SHARED_DOCK_B);
                })
                .thenSucceed();
    }

    // --- the inventory behind a target interface is broken mid job ---------------------------------------------------

    /**
     * The <b>inventory</b> behind the target interface is broken while the crane carries items. There are three
     * subjects for this case (crane, interface or inventory); this is the third one, and it
     * takes a different code path than a removed interface: the interface is still there and aligned, but without an
     * attached handler the location resolves as missing. The crane reroutes, finds nothing (the input is gone too) and
     * <b>holds</b> its items instead of dropping or deleting them; an inventory placed behind the same interface is
     * used by the next hold retry.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void craneTargetInventoryBrokenMidJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "target inventory broken"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    // Only the inventory, never the interface; the input goes too, so a reroute has no fallback left
                    // and the crane must hold what it carries.
                    aisle.breakBlock(aisle.inventoryPos(NEAR));
                    aisle.breakBlock(aisle.rackPos(INPUT));
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON))
                .thenIdle(QUIET_OBSERVE_TICKS)
                .thenExecute(() -> {
                    aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON);
                    helper.assertValueEqual(aisle.controller().storageLocations().size(), 1,
                            "an interface without an inventory stays a storage location");
                    helper.setBlock(aisle.inventoryPos(NEAR), Blocks.CHEST);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON,
                            "the held items go into the inventory placed behind the interface again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- config extremes --------------------------------------------------------------------------------------------

    /**
     * A crane speed factor of 0 (the config range allows it, §9) holds every crane still instead of stalling one axis
     * mid-trip: no job is accepted, nothing moves, the items stay where they are, and the goggles name the configuration
     * ({@link CranePauseReason#SPEED_FACTOR_ZERO}) instead of a missing shaft. A corrected config resumes the work.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_SPEED_BATCH, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void configZeroSpeedFactorPausesCranes(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.travelBlocksPerTickPerRpm, 0.0);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "zero speed factor"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenExecuteAfter(QUIET_OBSERVE_TICKS, () -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertTrue(Math.abs(dock.getSpeed()) > 0, "the dock does have rotation");
                    helper.assertTrue(dock.currentSpeeds().isStopped(), "a factor of 0 stops every axis");
                    helper.assertValueEqual(dock.pauseReason(), CranePauseReason.SPEED_FACTOR_ZERO,
                            "the pause reason names the configuration");
                    helper.assertFalse(dock.canAcceptJob(), "a crane that cannot move accepts no job");
                    helper.assertTrue(dock.currentJob().isEmpty(), "no job was planned");
                    helper.assertValueEqual(dock.craneState().pose(), StackerCraneBlockEntity.HOME_POSE, "nothing moved");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON,
                            "the items stay in the input");
                })
                .thenExecute(ConfigOverrides::restoreAll)
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON,
                            "the job runs once the configuration allows motion");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #configZeroSpeedFactorPausesCranes} fails before its own restore. */
    @AfterBatch(batch = CONFIG_SPEED_BATCH)
    public static void restoreSpeedConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Station buffers at their smallest configured size (one slot each, §9): the input takes one stack and refuses
     * anything else until the crane has emptied it, and a retrieval is delivered into the single output slot.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BUFFER_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void configTinyStationBuffers(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.inputBufferSlots, 1);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.outputBufferSlots, 1);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.storage(FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).relative(Direction.SOUTH);
        Map<ItemKey, Long> expected = ItemCensus.of(DIAMOND, STOCKED_DIAMONDS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "tiny station buffers"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.inputAt(INPUT).bufferedItems().totalSlots(), 1, "input buffer slots");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT).bufferedItems().totalSlots(), 1, "output buffer slots");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT));
                    aisle.insertAll(input, IRON.toStack(STACK));
                    ItemCensus.change(expected, IRON, STACK);
                    helper.assertFalse(ItemHandlerHelper.insertItem(input, COBBLE.toStack(1), true).isEmpty(),
                            "a full one-slot input refuses another item type");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STACK, "the stack was stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertTrue(ItemHandlerHelper
                            .insertItem(aisle.handlerAt(aisle.rackPos(INPUT)), COBBLE.toStack(1), true).isEmpty(),
                            "the emptied one-slot input accepts items again");
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, DIAMOND), (long) REQUESTED,
                            "the retrieval fits into the single output slot");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "request served"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #configTinyStationBuffers} fails. */
    @AfterBatch(batch = CONFIG_BUFFER_BATCH)
    public static void restoreBufferConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Both ends of {@code dispatchIntervalTicks} (§9) work: at the minimum of 1 a job starts within a few ticks, and at
     * the maximum of {@value #SLOW_DISPATCH_INTERVAL} a job still starts and completes, only later. Neither extreme
     * leaves work lying around.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_DISPATCH_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void configDispatchIntervalExtremes(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.dispatchIntervalTicks, 1);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        long[] insertedAt = new long[1];
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "dispatch interval"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().canAcceptJob(), "the crane is ready"))
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                    insertedAt[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isPresent(), "a job was planned"))
                .thenExecute(() -> {
                    long elapsed = helper.getTick() - insertedAt[0];
                    helper.assertTrue(elapsed <= FAST_DISPATCH_TICKS,
                            "at interval 1 a job must start within " + FAST_DISPATCH_TICKS + " ticks, took " + elapsed);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON, "stored at interval 1");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.dispatchIntervalTicks, SLOW_DISPATCH_INTERVAL);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                    insertedAt[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isPresent(),
                        "a job is planned at the largest dispatch interval too"))
                .thenExecute(() -> {
                    long elapsed = helper.getTick() - insertedAt[0];
                    helper.assertTrue(elapsed <= SLOW_DISPATCH_INTERVAL + DISPATCH_SLACK_TICKS,
                            "at interval " + SLOW_DISPATCH_INTERVAL + " a job must start within one interval, took "
                                    + elapsed);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) (2 * STORED_IRON),
                            "stored at the largest interval");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #configDispatchIntervalExtremes} fails. */
    @AfterBatch(batch = CONFIG_DISPATCH_BATCH)
    public static void restoreDispatchConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * {@code maxAisleLength} and {@code maxMastHeight} at their smallest and largest values (§9). A lowered maximum
     * shrinks a built aisle to it and drops the members outside; raising it again restores the length. <b>Surprising but
     * intended:</b> a lowered {@code maxMastHeight} clamps the stored mast height of every crane, and raising the limit
     * does not bring the old value back, because only the clamped number was kept.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_LIMITS_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void configAisleAndMastLimits(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(CLOSE);
        aisle.storage(NEAR);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "aisle and mast limits"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxAisleLength, 1);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxMastHeight, 1);
                    aisle.dock().requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.dock().geometry(), AisleGeometry.of(1, 1),
                            "geometry at the smallest configured limits");
                    helper.assertValueEqual(aisle.controller().status(), ControllerStatus.READY, "the aisle still works");
                    helper.assertValueEqual(aisle.controller().storageLocations().size(), 1,
                            "only the location inside the smallest aisle is a member");
                    helper.assertValueEqual(aisle.controller().storageLocations().getFirst().position(), CLOSE,
                            "and that is the one at position 1");
                })
                .thenExecute(() -> {
                    ConfigOverrides.restoreAll();
                    aisle.dock().requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.dock().aisleLength(), RAILS, "the length follows the raised limit");
                    helper.assertValueEqual(aisle.dock().mastHeight(), StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT,
                            "the mast height the player set comes back when the limit is raised again");
                    helper.assertValueEqual(aisle.controller().storageLocations().size(), 2,
                            "both locations are members again");
                })
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxMastHeight, MAX_CONFIGURED_MAST_HEIGHT);
                    // The value box takes its range from the config at every geometry refresh, so a raised maximum
                    // becomes settable only after the next refresh (at the latest after geometryRefreshTicks in game).
                    aisle.dock().refreshGeometry();
                    ScrollValueBehaviour mast = BlockEntityBehaviour.get(aisle.dock(), ScrollValueBehaviour.TYPE);
                    if (mast == null) {
                        helper.fail("the dock has no mast height value box", aisle.dockPos());
                        return;
                    }
                    mast.setValue(MAX_CONFIGURED_MAST_HEIGHT);
                    helper.assertValueEqual(aisle.dock().mastHeight(), MAX_CONFIGURED_MAST_HEIGHT,
                            "the mast height follows the raised limit after a refresh");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.dock().geometry(),
                            AisleGeometry.of(RAILS, MAX_CONFIGURED_MAST_HEIGHT), "geometry at the largest mast height");
                    helper.assertValueEqual(aisle.controller().status(), ControllerStatus.READY,
                            "the aisle still works at the largest mast height");
                    helper.assertFalse(aisle.controller().isMembershipDirty(), "the full scan of every level finished");
                    helper.assertValueEqual(aisle.controller().storageLocations().size(), 2, "both locations kept");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #configAisleAndMastLimits} fails. */
    @AfterBatch(batch = CONFIG_LIMITS_BATCH)
    public static void restoreLimitsConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Replaces the dock's block state with another aisle direction, as a command or a structure would. */
    private static void turnDock(GameTestHelper helper, AisleFixture aisle, Direction facing) {
        helper.setBlock(aisle.dockPos(), WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing));
    }

    private static void assertControllerOf(GameTestHelper helper, AisleFixture aisle, BlockPos member, String what) {
        Optional<BlockPos> found = WarehouseRegistry.findController(helper.getLevel(), member)
                .map(WarehouseControllerBlockEntity::getBlockPos);
        helper.assertValueEqual(found, Optional.of(helper.absolutePos(aisle.controllerPos())),
                "the registry resolves " + what + " to its own controller");
    }

    /** The registry resolves a member that lies in two aisles to the controller of the dock it is closest to. */
    private static void assertControllerAt(GameTestHelper helper, BlockPos member, BlockPos controllerPos, String what) {
        Optional<BlockPos> found = WarehouseRegistry.findController(helper.getLevel(), helper.absolutePos(member))
                .map(WarehouseControllerBlockEntity::getBlockPos);
        helper.assertValueEqual(found, Optional.of(helper.absolutePos(controllerPos)),
                "the registry resolves " + what + " to the controller of the nearer dock");
    }

    private static void assertAccepted(GameTestHelper helper, RequestResult result, String what) {
        helper.assertTrue(result.isAccepted(), what + " must be accepted, but was refused with " + result.rejection());
        helper.assertValueEqual(result.request().orElseThrow().remaining(), SHARED_REQUEST, what + " amount");
    }

    /**
     * Two aisles facing each other on one rail line: dock A at the west end facing east, dock B at the east end facing
     * west, their controllers behind them, one storage location in the shared rack plane and one output per aisle.
     */
    private static void buildOpposingAisles(GameTestHelper helper, BlockPos sharedInterface, BlockPos sharedChest,
                                            BlockPos outputA, BlockPos outputB) {
        placeMotor(helper, SHARED_DOCK_A.below());
        placeMotor(helper, SHARED_DOCK_B.below());
        helper.setBlock(SHARED_DOCK_A, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(SHARED_DOCK_B, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.WEST));
        for (int x = SHARED_DOCK_A.getX() + 1; x < SHARED_DOCK_B.getX(); x++)
            helper.setBlock(new BlockPos(x, BASE_Y, AISLE_Z), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, Direction.Axis.X));
        helper.setBlock(SHARED_DOCK_A.west(), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        helper.setBlock(SHARED_DOCK_B.east(), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.WEST));

        helper.setBlock(sharedChest, Blocks.CHEST);
        IItemHandler chest = handlerAt(helper, sharedChest);
        if (chest == null) {
            helper.fail("the shared chest exposes no item handler", sharedChest);
            return;
        }
        ItemStack rest = ItemHandlerHelper.insertItem(chest, IRON.toStack(SHARED_IRON), false);
        helper.assertTrue(rest.isEmpty(), "the shared chest rejected " + rest);
        // Facing north: away from the aisle for the east-facing aisle (side LEFT) and for the west-facing one (RIGHT).
        helper.setBlock(sharedInterface, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        for (BlockPos output : List.of(outputA, outputB))
            helper.setBlock(output, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                    .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
    }

    private static void placeMotor(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static CreativeMotorBlockEntity motorAt(GameTestHelper helper, BlockPos pos) {
        CreativeMotorBlockEntity be = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing creative motor block entity", pos);
        return be;
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper, BlockPos pos) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing stacker crane block entity", pos);
        return be;
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse controller block entity", pos);
        return be;
    }

    private static void assertIdleAndEmpty(GameTestHelper helper, BlockPos dockPos) {
        StackerCraneBlockEntity dock = dockAt(helper, dockPos);
        helper.assertValueEqual(dock.craneState().phase(), CranePhase.IDLE, "crane idle at " + dockPos);
        helper.assertTrue(dock.currentJob().isEmpty(), "no job left at " + dockPos);
        helper.assertTrue(dock.heldItems().isEmpty(), "head empty at " + dockPos);
    }

    private static long outputCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (output == null) {
            helper.fail("missing warehouse output block entity", pos);
            return 0L;
        }
        return output.bufferedItems().count(key);
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
    }

    private static long countAt(GameTestHelper helper, BlockPos pos, ItemKey key) {
        IItemHandler handler = handlerAt(helper, pos);
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
}
