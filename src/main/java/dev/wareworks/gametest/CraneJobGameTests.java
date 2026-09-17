package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.crane.CraneTimings;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * End-to-end GameTests of M3: the crane executes store and retrieve jobs that the controller plans, with real item
 * transfers, reservations, reroutes, waiting, pauses, persistence and removal ({@code docs/warehouse-system.md} §7-8,
 * {@code docs/stacker-crane.md} §4-6).
 * <p>
 * Layout on the {@code aisle_16x10x7} floor: controller at x = 0, dock at x = 1 (z = 3, aisle along +X) with a creative
 * motor below it, rails at x = 2..7; right rack plane z = 4 (input at position 0, output at position 1), left rack plane
 * z = 2 with chests behind the interfaces at z = 1 (near location at position 2, far location at position 5). Motors run
 * at {@value #TEST_RPM} RPM, so a trip takes well under a hundred ticks; timeouts are generous.
 * <p>
 * Most tests check item conservation every tick: storage chests, station buffers, the handling head and dropped item
 * entities always add up to the expected total. Assertions in sequences only use {@code helper.fail/assert*}.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CraneJobGameTests {
    private static final Direction AISLE = Direction.EAST;
    private static final int AISLE_Z = 3;
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final int RAILS = 6;
    private static final AisleLayout RELATIVE = AisleLayout.of(DOCK, AISLE, AisleGeometry.of(RAILS, 1));
    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition NEAR = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition FAR = new RackPosition(5, 0, Side.LEFT);
    private static final BlockPos OUTPUT_TRIGGER = RELATIVE.rackPos(OUTPUT).relative(Direction.SOUTH);

    private static final int TEST_RPM = 128;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int NO_ROTATION_IDLE_TICKS = 40;
    private static final int PAUSE_IDLE_TICKS = 40;
    private static final int WAITING_IDLE_TICKS = 50;
    /** Ticks the census keeps running after the dock was broken, so the ticks after the drop are covered too. */
    private static final int POST_BREAK_IDLE_TICKS = 10;
    private static final int KINETIC_START_TICKS = 2;
    /**
     * Bound of the dock's update tag in NBT size accounting ({@code Tag#sizeInBytes}): Create's kinetic data, two poses,
     * the phase and the goggle data with one job summary and at most four item ids are a few kilobytes, independent of
     * item components (the chunk packet quota is 2 MB).
     */
    private static final int MAX_UPDATE_TAG_BYTES = 8192;
    private static final int STACK = 64;

    private static final int STORED_IRON = 32;
    private static final int STOCKED_DIAMONDS = 20;
    private static final int REQUESTED_DIAMONDS = 10;
    /** Iron requested back from the stored iron in the reservation goggles test. */
    private static final int REQUESTED_IRON = 10;
    private static final int CONSOLIDATION_STOCK = 5;
    private static final int CONSOLIDATION_STORED = 16;
    private static final int SPILL_START_IRON = 1;
    /** Iron a "player" adds to the target during the spill test: slot 0 then holds 54, room for 10. */
    private static final int SPILL_PLAYER_IRON = 53;
    private static final int SPILL_ROOM = STACK - SPILL_START_IRON - SPILL_PLAYER_IRON;
    private static final int CHEST_SLOTS = 27;
    /** Diamonds a "player" puts into the output's last free slot: room for 6 of the 10 carried. */
    private static final int OUTPUT_PLAYER_DIAMONDS = 58;
    private static final int OUTPUT_ROOM = STACK - OUTPUT_PLAYER_DIAMONDS;
    /** Crafted head data: distinct item keys offered, and the iron a crafted head is cut down to. */
    private static final int CRAFTED_HEAD_KEYS = 100;
    private static final int CRAFTED_HELD_IRON = 12;
    /** Throwing inventory test: slot count, the slot whose calls throw, iron per source slot, room per target slot. */
    private static final int THROWING_SLOTS = 9;
    private static final int FAILING_SLOT = 2;
    private static final int PICK_STACK = 16;
    private static final int DROP_ROOM_PER_SLOT = 10;
    /** Test-relative x and z of the (unused) spill position of the throwing inventory test. */
    private static final int THROWING_TEST_SPOT = 3;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private CraneJobGameTests() {
    }

    // --- (a) store -----------------------------------------------------------------------------------------------

    /**
     * STORE end to end: 32 iron ingots in the input are planned, reserved, picked, carried and dropped into the chest
     * behind the interface; afterwards the input is empty, the crane idle with an empty head and no reservation is left.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneStoreEndToEnd(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR);
        placeInput(helper);
        AtomicLong expected = new AtomicLong();
        helper.onEachTick(() -> assertConserved(helper, IRON, expected.get(), NEAR));

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1, 1, 0))
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                    expected.set(STORED_IRON);
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "store job assigned"))
                .thenExecute(() -> {
                    TransportJob<ItemKey, RackPosition> job = dockAt(helper).currentJob().orElseThrow();
                    helper.assertValueEqual(job.type(), JobType.STORE, "job type");
                    helper.assertValueEqual(job.source(), INPUT, "picks at the input");
                    helper.assertValueEqual(job.target(), NEAR, "drops at the storage location");
                    helper.assertValueEqual(job.plannedAmount(), STORED_IRON, "planned amount");
                    helper.assertValueEqual(controllerAt(helper).reservations().reservedCapacity(NEAR),
                            (long) STORED_IRON, "capacity reserved at the target");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, NEAR, IRON), (long) STORED_IRON, "iron stored");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(stationCount(helper, INPUT, IRON), 0L, "input emptied");
                    helper.assertValueEqual(controllerAt(helper).countOf(IRON), (long) STORED_IRON,
                            "stock index updated right after the drop");
                    helper.assertValueEqual(dockAt(helper).goggleInfo().phase(), CranePhase.IDLE, "goggles show idle");
                })
                .thenSucceed();
    }

    // --- (b) retrieve --------------------------------------------------------------------------------------------

    /**
     * RETRIEVE end to end: a filter of diamond x10 and a redstone pulse at the output request 10 of 20 stocked diamonds;
     * the crane delivers them, the request is removed. Availability never subtracts a request twice (M2 note), neither
     * before nor after the pick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneRetrieveEndToEnd(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        AtomicReference<UUID> requestId = new AtomicReference<>();
        helper.onEachTick(() -> {
            assertConserved(helper, DIAMOND, STOCKED_DIAMONDS, FAR);
            if (requestId.get() != null)
                helper.assertValueEqual(controllerAt(helper).availableStock(DIAMOND),
                        (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS), "available diamonds while the request is served");
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    FilteringBehaviour filter = filterOf(helper, outputAt(helper));
                    filter.setFilter(DIAMOND.toStack());
                    filter.count = REQUESTED_DIAMONDS; // after setFilter, which may clamp the count
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controllerAt(helper)
                            .requestsFor(absRack(helper, OUTPUT));
                    helper.assertValueEqual(requests.size(), 1, "one request after the rising edge");
                    helper.assertValueEqual(requests.getFirst().remaining(), REQUESTED_DIAMONDS, "requested amount");
                    requestId.set(requests.getFirst().id());
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "retrieve job assigned"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    TransportJob<ItemKey, RackPosition> job = dockAt(helper).currentJob().orElseThrow();
                    helper.assertValueEqual(job.type(), JobType.RETRIEVE, "job type");
                    helper.assertValueEqual(job.source(), FAR, "picks at the storage location");
                    helper.assertValueEqual(job.target(), OUTPUT, "drops at the output");
                    helper.assertValueEqual(job.requestId(), Optional.of(requestId.get()), "serves the request");
                    helper.assertValueEqual(controller.reservedStock(DIAMOND), 0L, "reservation backs the request");
                    helper.assertValueEqual(controller.reservations().committedToRequest(requestId.get()),
                            (long) REQUESTED_DIAMONDS, "request covered by the job");
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, DIAMOND, REQUESTED_DIAMONDS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS),
                            "source re-read right after the pick");
                    helper.assertValueEqual(controller.reservations().inTransit(DIAMOND), (long) REQUESTED_DIAMONDS,
                            "held items in transit");
                    WarehouseOutputBlockEntity output = outputAt(helper);
                    output.onGoggleObserved();
                    helper.assertValueEqual(output.summary().requestedItems(), (long) REQUESTED_DIAMONDS,
                            "output goggles: remaining");
                    helper.assertValueEqual(output.summary().deliveredItems(), 0L, "output goggles: delivered");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "diamonds delivered to the output");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS),
                            "diamonds left in storage");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "request removed");
                    requestId.set(null);
                })
                .thenSucceed();
    }

    // --- (b2) goggles: reserved amounts -----------------------------------------------------------------------------

    /**
     * Interface goggles show the reservations of their location ({@code docs/warehouse-system.md} §3.1.1, M4): the target
     * of a store job shows the incoming items until the drop, the source of a retrieve job shows the items reserved for
     * the pick until the pick. The summary is part of the bounded update tag and reaches a client copy.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void interfaceGogglesShowReservations(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR);
        placeInput(helper);
        placeOutput(helper);
        LocationReservationSummary incomingIron = new LocationReservationSummary(
                List.of(new KeyCount<>(IRON.getItem(), (long) STORED_IRON)), List.of());
        LocationReservationSummary reservedIron = new LocationReservationSummary(List.of(),
                List.of(new KeyCount<>(IRON.getItem(), (long) REQUESTED_IRON)));

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1, 1, 1))
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity target = interfaceAt(helper, NEAR);
                    target.onGoggleObserved();
                    helper.assertValueEqual(target.reservationSummary(), LocationReservationSummary.NONE,
                            "nothing reserved before a job");
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "store job assigned"))
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity target = interfaceAt(helper, NEAR);
                    target.onGoggleObserved();
                    helper.assertValueEqual(target.reservationSummary(), incomingIron, "store target: incoming iron");
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag updateTag = target.getUpdateTag(registries);
                    helper.assertTrue(updateTag.sizeInBytes() < WareworksGameTests.MAX_RESERVED_INTERFACE_SYNC_BYTES,
                            "interface update tag must stay small, but has " + updateTag.sizeInBytes() + " bytes");
                    WarehouseInterfaceBlockEntity client = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                            .create(target.getBlockPos(), target.getBlockState());
                    client.handleUpdateTag(updateTag, registries);
                    helper.assertValueEqual(client.reservationSummary(), incomingIron, "reservations after client sync");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, NEAR, IRON), (long) STORED_IRON, "iron stored");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity target = interfaceAt(helper, NEAR);
                    target.onGoggleObserved();
                    helper.assertValueEqual(target.reservationSummary(), LocationReservationSummary.NONE,
                            "nothing reserved after the drop");
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), IRON, REQUESTED_IRON)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> {
                    TransportJob<ItemKey, RackPosition> job = dockAt(helper).currentJob().orElse(null);
                    helper.assertTrue(job != null && job.type() == JobType.RETRIEVE && !job.picked(),
                            "retrieve job before its pick");
                    WarehouseInterfaceBlockEntity source = interfaceAt(helper, NEAR);
                    source.onGoggleObserved();
                    helper.assertValueEqual(source.reservationSummary(), reservedIron, "retrieve source: reserved iron");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) REQUESTED_IRON, "iron delivered");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity source = interfaceAt(helper, NEAR);
                    source.onGoggleObserved();
                    helper.assertValueEqual(source.reservationSummary(), LocationReservationSummary.NONE,
                            "nothing reserved after the retrieve");
                })
                .thenSucceed();
    }

    // --- (c) consolidation ---------------------------------------------------------------------------------------

    /** Of two storage locations, the one that already holds the item wins over the nearer empty one. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneStoreConsolidates(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR);
        storage(helper, FAR, IRON.toStack(CONSOLIDATION_STOCK));
        placeInput(helper);
        AtomicLong expected = new AtomicLong(CONSOLIDATION_STOCK);
        helper.onEachTick(() -> assertConserved(helper, IRON, expected.get(), NEAR, FAR));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 2, 1, 0);
                    helper.assertValueEqual(controllerAt(helper).countOf(IRON), (long) CONSOLIDATION_STOCK, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(CONSOLIDATION_STORED));
                    expected.set(CONSOLIDATION_STOCK + CONSOLIDATION_STORED);
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "store job assigned"))
                .thenExecute(() -> helper.assertValueEqual(dockAt(helper).currentJob().map(TransportJob::target),
                        Optional.of(FAR), "consolidation before travel time"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), (long) (CONSOLIDATION_STOCK + CONSOLIDATION_STORED),
                            "stored with the existing iron");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> helper.assertValueEqual(chestCount(helper, NEAR, IRON), 0L, "near location unused"))
                .thenSucceed();
    }

    // --- (d) spill and reroute -----------------------------------------------------------------------------------

    /**
     * The target fills up while the crane travels: the drop inserts what fits, the rest stays in the head and is rerouted
     * to another storage location. Totals are conserved (including the iron the "player" added).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneSpillReroutes(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR);
        storage(helper, FAR, IRON.toStack(SPILL_START_IRON));
        placeInput(helper);
        AtomicLong expected = new AtomicLong(SPILL_START_IRON);
        helper.onEachTick(() -> assertConserved(helper, IRON, expected.get(), NEAR, FAR));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 2, 1, 0);
                    helper.assertValueEqual(controllerAt(helper).countOf(IRON), (long) SPILL_START_IRON, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                    expected.addAndGet(STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON);
                    helper.assertValueEqual(dockAt(helper).currentJob().map(TransportJob::target), Optional.of(FAR),
                            "heading for the consolidation target");
                })
                .thenExecute(() -> {
                    IItemHandler far = handlerAt(helper, chestOf(FAR));
                    insertAll(helper, far, IRON.toStack(SPILL_PLAYER_IRON));
                    expected.addAndGet(SPILL_PLAYER_IRON);
                    for (int slot = 1; slot < CHEST_SLOTS; slot++)
                        insertAll(helper, far, COBBLE.toStack(STACK));
                    ItemStack rest = ItemHandlerHelper.insertItem(far, IRON.toStack(STORED_IRON), true);
                    helper.assertValueEqual(STORED_IRON - rest.getCount(), SPILL_ROOM, "room left at the target");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), (long) STACK, "target filled up");
                    helper.assertValueEqual(chestCount(helper, NEAR, IRON), (long) (STORED_IRON - SPILL_ROOM),
                            "rest rerouted to the other location");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> helper.assertValueEqual(controllerAt(helper).countOf(IRON), expected.get(),
                        "stock index follows both drops"))
                .thenSucceed();
    }

    // --- (e) no rotation -----------------------------------------------------------------------------------------

    /**
     * Without rotation no job starts and nothing moves; with rotation the job starts; losing rotation mid-job freezes the
     * crane (pose, phase, timers, head) and moves no items; restoring it completes the job.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneNoRotationPauses(GameTestHelper helper) {
        buildAisle(helper, false);
        storage(helper, FAR);
        placeInput(helper);
        AtomicLong expected = new AtomicLong();
        AtomicReference<CraneState<ItemKey, RackPosition>> frozen = new AtomicReference<>();
        long[] frozenCounts = new long[2];
        helper.onEachTick(() -> assertConserved(helper, IRON, expected.get(), FAR));

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1, 1, 0))
                .thenExecute(() -> {
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                    expected.set(STORED_IRON);
                })
                .thenIdle(NO_ROTATION_IDLE_TICKS)
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    helper.assertValueEqual(dock.pauseReason(), CranePauseReason.NO_ROTATION, "pause reason");
                    helper.assertFalse(dock.canAcceptJob(), "no job without rotation");
                    helper.assertTrue(dock.currentJob().isEmpty(), "no job assigned");
                    helper.assertValueEqual(dock.craneState().pose(), StackerCraneBlockEntity.HOME_POSE, "crane parked");
                    helper.assertValueEqual(stationCount(helper, INPUT, IRON), (long) STORED_IRON, "input untouched");
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), 0L, "nothing stored");
                    helper.assertTrue(controllerAt(helper).reservations().isEmpty(), "nothing reserved");
                    aisle(helper).placeMotor();
                })
                .thenIdle(KINETIC_START_TICKS)
                .thenExecute(() -> motorAt(helper).generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> motorAt(helper).generatedSpeed.setValue(0))
                .thenWaitUntil(() -> helper.assertValueEqual(dockAt(helper).pauseReason(), CranePauseReason.NO_ROTATION,
                        "paused after losing rotation"))
                .thenExecute(() -> {
                    frozen.set(dockAt(helper).craneState());
                    frozenCounts[0] = chestCount(helper, FAR, IRON);
                    frozenCounts[1] = stationCount(helper, INPUT, IRON);
                })
                .thenIdle(PAUSE_IDLE_TICKS)
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    CraneState<ItemKey, RackPosition> before = frozen.get();
                    CraneState<ItemKey, RackPosition> now = dock.craneState();
                    helper.assertTrue(now.paused(), "state is paused");
                    helper.assertValueEqual(now.pose(), before.pose(), "no motion while paused");
                    helper.assertValueEqual(now.phase(), before.phase(), "no phase change while paused");
                    helper.assertValueEqual(now.phaseTicks(), before.phaseTicks(), "transfer timer frozen");
                    helper.assertValueEqual(now.job(), before.job(), "job unchanged");
                    helper.assertValueEqual(dock.heldItems().count(IRON), before.heldAmount(), "head unchanged");
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), frozenCounts[0], "no items stored while paused");
                    helper.assertValueEqual(stationCount(helper, INPUT, IRON), frozenCounts[1], "no items taken while paused");
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), (long) STORED_IRON, "job completed");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenSucceed();
    }

    // --- (f) persistence -----------------------------------------------------------------------------------------

    /**
     * Saves dock and controller while the crane carries items to the target: detached copies restore job, head, phase
     * and pose, and a copy of the controller rebuilds the reservations from the crane's job. Then both block entities are
     * replaced in the world by copies loaded from the saves (a reload): the new controller adopts the crane's job and the
     * job completes with every item conserved. The client packet stays small and carries the goggle data.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void cranePersistenceMidJob(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR);
        placeInput(helper);
        AtomicLong expected = new AtomicLong();
        helper.onEachTick(() -> assertConserved(helper, IRON, expected.get(), FAR));

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1, 1, 0))
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                    expected.set(STORED_IRON);
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = dockAt(helper);
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);

                    StackerCraneBlockEntity dockCopy = loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class);
                    helper.assertValueEqual(dockCopy.currentJob(), dock.currentJob(), "job restored");
                    helper.assertValueEqual(dockCopy.heldItems(), dock.heldItems(), "head restored");
                    helper.assertValueEqual(dockCopy.craneState().phase(), CranePhase.TRAVEL_TO_TARGET, "phase restored");
                    helper.assertValueEqual(dockCopy.craneState().pose(), dock.craneState().pose(), "pose restored");
                    helper.assertValueEqual(dockCopy.craneState().target(), dock.craneState().target(), "target restored");
                    helper.assertTrue(dockCopy.craneState().isConsistent(), "restored state is consistent");

                    WarehouseControllerBlockEntity controllerCopy = loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class);
                    helper.assertTrue(controllerCopy.reservations().isEmpty(), "reservations are derived, not saved");
                    controllerCopy.adoptCraneJob(dockCopy.currentJob());
                    helper.assertValueEqual(controllerCopy.reservations().reservations(),
                            controller.reservations().reservations(), "reservations rebuilt from the restored job");

                    CompoundTag update = dock.getUpdateTag(registries);
                    helper.assertTrue(update.sizeInBytes() < MAX_UPDATE_TAG_BYTES,
                            "crane update tag must stay small, but has " + update.sizeInBytes() + " bytes");
                    helper.assertFalse(update.contains("Head"), "the head is not synced as items");
                    StackerCraneBlockEntity client = WareworksBlockEntityTypes.STACKER_CRANE.create(dock.getBlockPos(),
                            dock.getBlockState());
                    if (client == null) {
                        helper.fail("could not create a detached stacker crane");
                        return;
                    }
                    client.handleUpdateTag(update, registries);
                    helper.assertValueEqual(client.craneState().phase(), dock.craneState().phase(), "synced phase");
                    helper.assertValueEqual(client.craneState().pose(), dock.craneState().pose(), "synced pose");
                    helper.assertValueEqual(client.craneState().target(), dock.craneState().target(), "synced target");
                    helper.assertValueEqual(client.goggleInfo(), dock.goggleInfo(), "synced goggle data");
                    helper.assertValueEqual(client.goggleInfo().heldCount(), (long) STORED_IRON, "synced held items");

                    // Reload: fresh block entities from the saves replace the live ones.
                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(dock.isRemoved() && dockAt(helper) != dock, "dock block entity replaced");
                    helper.assertTrue(controller.isRemoved() && controllerAt(helper) != controller,
                            "controller block entity replaced");
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.isLinkedTo(helper.absolutePos(DOCK)), "reloaded controller linked");
                    Optional<TransportJob<ItemKey, RackPosition>> job = dock.currentJob();
                    helper.assertTrue(job.isPresent(), "reloaded crane still executes its job");
                    helper.assertValueEqual(controller.reservations().reservations(),
                            ReservationLedger.reservationsFor(job.get()), "reservations adopted from the crane's job");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), (long) STORED_IRON, "job completed after reload");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenSucceed();
    }

    // --- (g) dock broken -----------------------------------------------------------------------------------------

    /**
     * Breaking the dock while it carries requested diamonds drops exactly the head contents at the dock; the controller
     * releases the reservations and the request keeps its remaining amount.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneBrokenMidJobDropsHead(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        // The diamonds never leave the world: they move storage -> head -> dropped, and the census must hold in the
        // ticks after the break as well, not only in the one the block was destroyed in.
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, STOCKED_DIAMONDS, FAR));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    RequestResult result = controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND,
                            REQUESTED_DIAMONDS);
                    helper.assertTrue(result.isAccepted(), "request accepted: " + result);
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, DIAMOND, REQUESTED_DIAMONDS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    int held = dockAt(helper).heldItems().count(DIAMOND);
                    helper.assertFalse(controller.reservations().isEmpty(), "job reserved before the break");
                    helper.getLevel().destroyBlock(helper.absolutePos(DOCK), false);

                    helper.assertValueEqual(droppedCount(helper, DIAMOND), (long) held,
                            "the head contents dropped at the dock");
                    helper.assertTrue(controller.reservations().isEmpty(), "reservations released");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(absRack(helper, OUTPUT));
                    helper.assertValueEqual(requests.size(), 1, "request still open");
                    helper.assertValueEqual(requests.getFirst().remaining(), REQUESTED_DIAMONDS, "request keeps remaining");
                    long total = chestCount(helper, FAR, DIAMOND) + stationCount(helper, OUTPUT, DIAMOND)
                            + droppedCount(helper, DIAMOND);
                    helper.assertValueEqual(total, (long) STOCKED_DIAMONDS, "diamonds conserved");
                })
                .thenExecuteAfter(POST_BREAK_IDLE_TICKS, () -> {
                    helper.assertValueEqual(droppedCount(helper, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "the dropped items stay dropped and reappear in no inventory");
                    helper.assertTrue(WareworksBlockEntityTypes.STACKER_CRANE.getNullable(helper.getLevel(),
                            helper.absolutePos(DOCK)) == null, "the dock block entity is gone");
                })
                .thenSucceed();
    }

    // --- (h) output full -----------------------------------------------------------------------------------------

    /**
     * The output fills up while the crane travels: it drops what fits (the request counts only that), retracts and waits,
     * retries without moving anything, and completes once automation empties the buffer.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneWaitsForFullOutput(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        AtomicLong expected = new AtomicLong(STOCKED_DIAMONDS);
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, expected.get(), FAR));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity output = outputAt(helper);
                    for (int slot = 0; slot < output.bufferSlots() - 1; slot++)
                        helper.assertTrue(output.insert(COBBLE.toStack(STACK), false).isEmpty(), "buffer pre-filled");
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted with one free slot");
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, DIAMOND, REQUESTED_DIAMONDS))
                .thenExecute(() -> {
                    helper.assertTrue(outputAt(helper).insert(DIAMOND.toStack(OUTPUT_PLAYER_DIAMONDS), false).isEmpty(),
                            "last slot nearly filled");
                    expected.addAndGet(OUTPUT_PLAYER_DIAMONDS);
                })
                .thenWaitUntil(() -> {
                    assertCarrying(helper, CranePhase.WAITING_FOR_TARGET, DIAMOND, REQUESTED_DIAMONDS - OUTPUT_ROOM);
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) STACK, "what fits was dropped");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    BlockPos outputPos = absRack(helper, OUTPUT);
                    helper.assertValueEqual(controller.requestedFor(outputPos), (long) (REQUESTED_DIAMONDS - OUTPUT_ROOM),
                            "only the real drop counts for the request");
                    helper.assertValueEqual(controller.deliveredFor(outputPos), (long) OUTPUT_ROOM, "delivered so far");
                    helper.assertValueEqual(controller.reservations().inTransit(DIAMOND),
                            (long) (REQUESTED_DIAMONDS - OUTPUT_ROOM), "leftovers in transit");
                    WarehouseOutputBlockEntity output = outputAt(helper);
                    output.onGoggleObserved();
                    helper.assertValueEqual(output.summary().requestedItems(), (long) (REQUESTED_DIAMONDS - OUTPUT_ROOM),
                            "output goggles: remaining");
                    helper.assertValueEqual(output.summary().deliveredItems(), (long) OUTPUT_ROOM,
                            "output goggles: delivered so far");
                })
                .thenIdle(WAITING_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(dockAt(helper).heldItems().count(DIAMOND), REQUESTED_DIAMONDS - OUTPUT_ROOM,
                            "retries move nothing into a full output");
                    helper.assertValueEqual(controllerAt(helper).requestedFor(absRack(helper, OUTPUT)),
                            (long) (REQUESTED_DIAMONDS - OUTPUT_ROOM), "request unchanged while waiting");
                    // Automation empties the buffer.
                    IItemHandler pull = handlerAt(helper, relPos(OUTPUT));
                    long removedDiamonds = 0;
                    for (int slot = 0; slot < pull.getSlots(); slot++) {
                        ItemStack taken = pull.extractItem(slot, STACK, false);
                        if (DIAMOND.matches(taken))
                            removedDiamonds += taken.getCount();
                    }
                    expected.addAndGet(-removedDiamonds);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) (REQUESTED_DIAMONDS - OUTPUT_ROOM),
                            "leftovers delivered after emptying");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0, "request completed");
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS),
                            "diamonds left in storage");
                })
                .thenSucceed();
    }

    // --- (i) review fixes and gaps -------------------------------------------------------------------------------

    /**
     * A reload while the crane drops requested diamonds into the output. The dock ticks before the controller (its
     * ticker was added first, and replacing a block entity keeps the ticker's place), so the drop happens in the
     * reloaded dock's first tick, before the reloaded controller re-linked on its own. The dock links it at once, so the
     * delivery counts for the request and nothing is retrieved twice.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneReloadDuringDropCountsDelivery(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, STOCKED_DIAMONDS, FAR));
        int lastTransferTick = Math.max(CraneTimings.MIN_TICKS, WareworksConfig.transferTicks()) - 1;

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> {
                    CraneState<ItemKey, RackPosition> state = dockAt(helper).craneState();
                    helper.assertValueEqual(state.phase(), CranePhase.DROP, "dropping");
                    helper.assertValueEqual(state.phaseTicks(), lastTransferTick, "the drop happens in the next tick");
                })
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = dockAt(helper);
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(dock.isRemoved() && controller.isRemoved(), "both block entities replaced");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) REQUESTED_DIAMONDS, "delivered");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0,
                        "the delivery right after the reload counted for the request"))
                .thenIdle(WAITING_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "nothing delivered twice");
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS),
                            "nothing retrieved twice");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenSucceed();
    }

    /**
     * A foreign inventory that throws part way through a real transfer (here: every call for one slot). A pick keeps
     * exactly the items the earlier slots handed out, so none is lost; a drop counts exactly what went in before the
     * failure, so none is duplicated.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneHeadSurvivesThrowingInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spillPos = helper.absolutePos(new BlockPos(THROWING_TEST_SPOT, BASE_Y, THROWING_TEST_SPOT));

        InventoryGrabber picking = new InventoryGrabber(() -> {
        });
        ThrowingInventory source = new ThrowingInventory(THROWING_SLOTS, FAILING_SLOT);
        int sourceStacks = FAILING_SLOT + 2;
        for (int slot = 0; slot < sourceStacks; slot++)
            source.setStackInSlot(slot, IRON.toStack(PICK_STACK));
        int picked = picking.pick(TransferContexts.ofHandler(level, spillPos, source), IRON, STACK);
        helper.assertValueEqual(picked, FAILING_SLOT * PICK_STACK, "picked what the slots before the failing one gave");
        helper.assertValueEqual(picking.count(IRON), picked, "the head holds the picked items");
        helper.assertValueEqual(countIn(source, IRON) + picking.count(IRON), (long) sourceStacks * PICK_STACK,
                "no item lost on the pick");

        InventoryGrabber dropping = new InventoryGrabber(() -> {
        });
        ItemStackHandler plain = new ItemStackHandler(1);
        plain.setStackInSlot(0, IRON.toStack(STACK));
        helper.assertValueEqual(dropping.pick(TransferContexts.ofHandler(level, spillPos, plain), IRON, STACK), STACK,
                "head filled");
        ThrowingInventory target = new ThrowingInventory(THROWING_SLOTS, FAILING_SLOT);
        for (int slot = 0; slot < FAILING_SLOT; slot++)
            target.setStackInSlot(slot, IRON.toStack(STACK - DROP_ROOM_PER_SLOT));
        long before = countIn(target, IRON);
        int delivered = dropping.drop(TransferContexts.ofHandler(level, spillPos, target), IRON, STACK);
        helper.assertValueEqual(delivered, FAILING_SLOT * DROP_ROOM_PER_SLOT, "delivered what went in before the failure");
        helper.assertValueEqual(countIn(target, IRON) - before, (long) delivered, "the target holds the delivered items");
        helper.assertValueEqual(dropping.count(IRON), STACK - delivered, "the rest stays held, nothing duplicated");
        helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing spilled");
        helper.succeed();
    }

    /**
     * {@code clearContent} (commands that replace or move the dock, ADR-013) on a carrying dock: the head is emptied
     * without dropping anything, the job ends, its reservations are released and the request keeps its amount.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneClearContentReleasesJob(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, DIAMOND, REQUESTED_DIAMONDS))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertFalse(controller.reservations().isEmpty(), "job reserved before");
                    dock.clearContent();
                    helper.assertTrue(dock.heldItems().isEmpty(), "head emptied");
                    helper.assertTrue(dock.currentJob().isEmpty(), "job ended");
                    helper.assertValueEqual(dock.craneState().phase(), CranePhase.IDLE, "crane idle");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(),
                            "nothing dropped: the command copies or deletes the data");
                    helper.assertTrue(controller.reservations().isEmpty(), "reservations released");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(absRack(helper, OUTPUT));
                    helper.assertValueEqual(requests.size(), 1, "request still open");
                    helper.assertValueEqual(requests.getFirst().remaining(), REQUESTED_DIAMONDS, "request keeps remaining");
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND),
                            (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS), "storage untouched");
                })
                .thenSucceed();
    }

    /**
     * The source interface is broken while the crane travels to it: the job aborts before the pick with nothing held,
     * its reservations are released, the request keeps its amount, and a new job serves it from the other location.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneSourceRemovedBeforePickAborts(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR, DIAMOND.toStack(REQUESTED_DIAMONDS));
        storage(helper, FAR, DIAMOND.toStack(REQUESTED_DIAMONDS));
        placeOutput(helper);
        long stocked = 2L * REQUESTED_DIAMONDS;
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, stocked, NEAR, FAR));
        AtomicReference<UUID> firstJob = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 2, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), stocked, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "retrieve job assigned"))
                .thenExecute(() -> {
                    TransportJob<ItemKey, RackPosition> job = dockAt(helper).currentJob().orElseThrow();
                    helper.assertValueEqual(job.source(), NEAR, "the nearer source");
                    helper.assertFalse(job.picked(), "not picked yet");
                    firstJob.set(job.id());
                    aisle(helper).breakBlock(relPos(NEAR));
                })
                .thenWaitUntil(() -> helper.assertFalse(dockAt(helper).currentJob().map(TransportJob::id)
                        .equals(Optional.of(firstJob.get())), "the first job ended"))
                .thenExecute(() -> {
                    Optional<TransportJob<ItemKey, RackPosition>> next = dockAt(helper).currentJob();
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.reservations().reservations(),
                            next.map(ReservationLedger::reservationsFor).orElse(List.of()),
                            "the aborted job's reservations are released");
                    helper.assertValueEqual(chestCount(helper, NEAR, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "nothing picked at the removed source");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(absRack(helper, OUTPUT));
                    helper.assertValueEqual(requests.size(), 1, "request still open");
                    helper.assertValueEqual(requests.getFirst().remaining(), REQUESTED_DIAMONDS, "request keeps its amount");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "served from the other location");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), 0L, "taken from the other location");
                    helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0, "request completed");
                })
                .thenSucceed();
    }

    /**
     * A player empties the source while the crane travels to it: the real pick gives nothing, the job aborts after
     * retracting with nothing held, its reservations are released and the request keeps its amount. No job starts while
     * nothing is in stock; once the diamonds are back, the request is served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneZeroPickAborts(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        AtomicLong expected = new AtomicLong(STOCKED_DIAMONDS);
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, expected.get(), FAR));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 1, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "retrieve job assigned"))
                .thenExecute(() -> {
                    helper.assertFalse(dockAt(helper).currentJob().orElseThrow().picked(), "not picked yet");
                    IItemHandler chest = handlerAt(helper, chestOf(FAR));
                    long taken = 0;
                    for (int slot = 0; slot < chest.getSlots(); slot++)
                        taken += chest.extractItem(slot, STACK, false).getCount();
                    helper.assertValueEqual(taken, (long) STOCKED_DIAMONDS, "a player took every diamond");
                    expected.addAndGet(-taken);
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isEmpty(), "the job ended"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(dockAt(helper).heldItems().isEmpty(), "nothing held");
                    helper.assertTrue(controller.reservations().isEmpty(), "reservations released");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(absRack(helper, OUTPUT));
                    helper.assertValueEqual(requests.size(), 1, "request still open");
                    helper.assertValueEqual(requests.getFirst().remaining(), REQUESTED_DIAMONDS, "request keeps its amount");
                })
                .thenIdle(NO_ROTATION_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(dockAt(helper).currentJob().isEmpty(), "no job while nothing is in stock");
                    helper.assertValueEqual(controllerAt(helper).lastPlanReason(), Optional.of(NoJobReason.NOT_IN_STOCK),
                            "last planning result");
                    insertAll(helper, handlerAt(helper, chestOf(FAR)), DIAMOND.toStack(STOCKED_DIAMONDS));
                    expected.addAndGet(STOCKED_DIAMONDS);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) REQUESTED_DIAMONDS,
                            "served once the diamonds are back");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), (long) (STOCKED_DIAMONDS - REQUESTED_DIAMONDS),
                            "diamonds left in storage");
                    helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0, "request completed");
                })
                .thenSucceed();
    }

    /**
     * A request cancelled before the pick aborts its job (nothing moves, nothing stays reserved); a request cancelled
     * while the crane carries its items sends them back into storage, not into the output.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneRequestCancelledBeforeAndAfterPick(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, NEAR);
        storage(helper, FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        placeOutput(helper);
        helper.onEachTick(() -> assertConserved(helper, DIAMOND, STOCKED_DIAMONDS, NEAR, FAR));
        AtomicReference<UUID> requestId = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 2, 0, 1);
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    requestId.set(requestDiamonds(helper));
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isPresent(), "retrieve job assigned"))
                .thenExecute(() -> {
                    helper.assertFalse(dockAt(helper).currentJob().orElseThrow().picked(), "not picked yet");
                    helper.assertTrue(controllerAt(helper).cancelRequest(requestId.get()).isPresent(), "request cancelled");
                })
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper).currentJob().isEmpty(), "the job was aborted"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.reservations().isEmpty(), "reservations released");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "no request left");
                    helper.assertTrue(dockAt(helper).heldItems().isEmpty(), "nothing held");
                    helper.assertValueEqual(chestCount(helper, FAR, DIAMOND), (long) STOCKED_DIAMONDS, "nothing moved");
                    requestId.set(requestDiamonds(helper));
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, DIAMOND, REQUESTED_DIAMONDS))
                .thenExecute(() -> helper.assertTrue(controllerAt(helper).cancelRequest(requestId.get()).isPresent(),
                        "request cancelled while carrying"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, NEAR, DIAMOND) + chestCount(helper, FAR, DIAMOND),
                            (long) STOCKED_DIAMONDS, "the diamonds went back into storage");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), 0L, "nothing reached the output");
                    helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0, "no request left");
                })
                .thenSucceed();
    }

    // --- (j) crafted block entity data (M5 release audit) --------------------------------------------------------

    /**
     * A crafted handling head is untrusted data ({@code BlockItem.updateCustomBlockEntityTag}, which any creative
     * player can set, {@code /data merge}, structures, schematics; {@code docs/stacker-crane.md} §6.1): loading it
     * allocates at most {@link InventoryGrabber#MAX_LOADED_ENTRIES} keys and {@link InventoryGrabber#MAX_LOADED_ITEMS}
     * items, and the items then belong to no job, so the crane drops them at the dock instead of keeping or deleting
     * them ({@code CraneExecution#reconcileHeadWithJob}, §4.2).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneCraftedHeadIsBoundedAndSpilled(GameTestHelper helper) {
        buildAisle(helper, false);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 0, 0, 0))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();

                    // Far more item types than a head can ever hold: only MAX_LOADED_ENTRIES of them are decoded.
                    ListTag manyKeys = new ListTag();
                    for (int damage = 1; damage <= CRAFTED_HEAD_KEYS; damage++)
                        manyKeys.add(headEntry(registries, damagedSword(damage), 1));
                    loadHead(helper, manyKeys, registries);
                    StackerCraneBlockEntity withManyKeys = dockAt(helper);
                    helper.assertValueEqual(withManyKeys.heldItems().entries().size(),
                            InventoryGrabber.MAX_LOADED_ENTRIES, "distinct held keys are bounded");
                    helper.assertValueEqual(withManyKeys.heldItems().totalCount(),
                            (long) InventoryGrabber.MAX_LOADED_ENTRIES, "one item per decoded key");

                    // Crafted counts: the item budget is spent on the first entry and nothing beyond it is decoded.
                    ListTag hugeCounts = new ListTag();
                    hugeCounts.add(headEntry(registries, IRON, Integer.MAX_VALUE));
                    hugeCounts.add(headEntry(registries, DIAMOND, Integer.MAX_VALUE));
                    loadHead(helper, hugeCounts, registries);
                    StackerCraneBlockEntity bounded = dockAt(helper);
                    helper.assertValueEqual(bounded.heldItems().count(IRON), InventoryGrabber.MAX_LOADED_ITEMS,
                            "the crafted count is cut at the item budget");
                    helper.assertValueEqual(bounded.heldItems().count(DIAMOND), 0,
                            "nothing beyond the budget is decoded");
                    helper.assertValueEqual(bounded.heldItems().totalCount(),
                            (long) InventoryGrabber.MAX_LOADED_ITEMS, "held items are bounded");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(dockAt(helper).heldItems().isEmpty(), "the stray items left the head");
                    helper.assertValueEqual(droppedCount(helper, IRON), (long) InventoryGrabber.MAX_LOADED_ITEMS,
                            "they were dropped at the dock, not deleted");
                })
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    helper.assertTrue(dock.currentJob().isEmpty(), "no job was invented for crafted items");
                    helper.assertTrue(dock.craneState().isConsistent(), "the resumed state is consistent");
                    helper.assertValueEqual(droppedCount(helper, DIAMOND), 0L, "the skipped entry produced no items");
                })
                .thenSucceed();
    }

    /**
     * A crafted or damaged {@code Head} that disagrees with the saved job: the job is rewritten to follow the
     * <b>real</b> head, so the crane never promises, delivers or reserves items that are not in its grabber
     * ({@code CraneExecution#reconcileHeadWithJob}). The items the crafted tag removed are gone, which is the point:
     * the state follows the world, not the save.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneJobFollowsACraftedHead(GameTestHelper helper) {
        buildAisle(helper, true);
        storage(helper, FAR);
        placeInput(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1, 1, 0))
                .thenExecute(() -> {
                    motorAt(helper).generatedSpeed.setValue(TEST_RPM);
                    insertAll(helper, handlerAt(helper, relPos(INPUT)), IRON.toStack(STORED_IRON));
                })
                .thenWaitUntil(() -> assertCarrying(helper, CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    ListTag fewer = new ListTag();
                    fewer.add(headEntry(registries, IRON, CRAFTED_HELD_IRON));
                    loadHead(helper, fewer, registries);
                    StackerCraneBlockEntity reloaded = dockAt(helper);
                    helper.assertValueEqual(reloaded.heldItems().count(IRON), CRAFTED_HELD_IRON,
                            "the head was rewritten");
                    helper.assertValueEqual(reloaded.currentJob().map(TransportJob::heldAmount),
                            Optional.of(STORED_IRON), "the loaded job still promises the amount it was saved with");
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper);
                    TransportJob<ItemKey, RackPosition> job = dock.currentJob().orElse(null);
                    if (job == null) {
                        helper.fail("the job must survive a crafted head, not be dropped");
                        return;
                    }
                    helper.assertValueEqual(job.heldAmount(), CRAFTED_HELD_IRON, "the job follows the real head");
                    helper.assertTrue(job.picked(), "and stays a picked job, so nothing is picked twice");
                    helper.assertTrue(dock.craneState().isConsistent(), "the resumed state is consistent");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(chestCount(helper, FAR, IRON), (long) CRAFTED_HELD_IRON,
                            "exactly what the head really held is stored");
                    assertCraneIdleAndEmpty(helper);
                })
                .thenExecute(() -> helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(),
                        "a shrunken head drops nothing: the items were removed by the crafted tag, not spilled"))
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** One crafted {@code Head} entry in the documented save format ({@code {Item: <ItemKey>, Count: int}}). */
    private static CompoundTag headEntry(HolderLookup.Provider registries, ItemKey key, int count) {
        CompoundTag entry = new CompoundTag();
        entry.put("Item", key.save(registries));
        entry.putInt("Count", count);
        return entry;
    }

    /**
     * Puts crafted {@code entries} into the dock's saved handling head and brings that save back into the world the way
     * a reload does: as a <b>fresh</b> block entity ({@code Level#setBlockEntity}, as in
     * {@link #cranePersistenceMidJob}), so the crane rejoins its kinetic network and can still move afterwards. Reading
     * the tag into the live block entity instead re-reads its kinetic fields without re-attaching it, and the crane
     * never turns again — which is a property of Create's kinetics, not of the head, and would hide what this test is
     * about. The caller must re-read the dock afterwards.
     */
    private static void loadHead(GameTestHelper helper, ListTag entries, HolderLookup.Provider registries) {
        StackerCraneBlockEntity dock = dockAt(helper);
        CompoundTag saved = dock.saveWithFullMetadata(registries);
        CompoundTag head = new CompoundTag();
        head.put("Items", entries);
        saved.put("Head", head);
        helper.getLevel().setBlockEntity(loadCopy(helper, dock, saved, StackerCraneBlockEntity.class));
    }

    /** An item key that differs only in its damage component, so crafted data can carry many distinct keys. */
    private static ItemKey damagedSword(int damage) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, damage);
        return ItemKey.of(sword);
    }

    /** Requests {@value #REQUESTED_DIAMONDS} diamonds at the output (must be accepted) and returns the request id. */
    private static UUID requestDiamonds(GameTestHelper helper) {
        WarehouseControllerBlockEntity controller = controllerAt(helper);
        helper.assertTrue(controller.request(absRack(helper, OUTPUT), DIAMOND, REQUESTED_DIAMONDS).isAccepted(),
                "request accepted");
        return controller.requestsFor(absRack(helper, OUTPUT)).getLast().id();
    }

    /** A test inventory whose calls for one slot throw, like a broken modded storage. */
    private static final class ThrowingInventory extends ItemStackHandler {
        private final int failingSlot;

        ThrowingInventory(int size, int failingSlot) {
            super(size);
            this.failingSlot = failingSlot;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == failingSlot)
                throw new IllegalStateException("test inventory fails at slot " + slot);
            return super.extractItem(slot, amount, simulate);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot == failingSlot)
                throw new IllegalStateException("test inventory fails at slot " + slot);
            return super.insertItem(slot, stack, simulate);
        }
    }

    private static long countIn(IItemHandler handler, ItemKey key) {
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    /** The aisle of these tests as an {@link AisleFixture}: controller x = 0, dock x = 1 at z = 3, {@value #RAILS} rails. */
    private static AisleFixture aisle(GameTestHelper helper) {
        return new AisleFixture(helper, AISLE_Z, RAILS);
    }

    /** Dock (with a creative motor below if {@code withMotor}), rails and controller. */
    private static void buildAisle(GameTestHelper helper, boolean withMotor) {
        aisle(helper).build(withMotor);
    }

    /** A chest with the given contents behind an aligned interface at {@code rack}. */
    private static void storage(GameTestHelper helper, RackPosition rack, ItemStack... contents) {
        aisle(helper).storage(rack, contents);
    }

    private static void placeInput(GameTestHelper helper) {
        aisle(helper).input(INPUT);
    }

    private static void placeOutput(GameTestHelper helper) {
        aisle(helper).output(OUTPUT);
    }

    private static void assertAisleReady(GameTestHelper helper, int storage, int inputs, int outputs) {
        aisle(helper).assertReady(storage, inputs, outputs);
    }

    /** The crane is in {@code phase} with exactly {@code amount} items of {@code key} in its head. */
    private static void assertCarrying(GameTestHelper helper, CranePhase phase, ItemKey key, int amount) {
        aisle(helper).assertCarrying(phase, key, amount);
    }

    private static void assertCraneIdleAndEmpty(GameTestHelper helper) {
        aisle(helper).assertIdleAndEmpty();
    }

    /** Storage chests + station buffers + handling head + dropped item entities hold {@code expected} of {@code key}. */
    private static void assertConserved(GameTestHelper helper, ItemKey key, long expected, RackPosition... storage) {
        long total = stationCount(helper, INPUT, key) + stationCount(helper, OUTPUT, key)
                + droppedCount(helper, key);
        for (RackPosition rack : storage)
            total += chestCount(helper, rack, key);
        StackerCraneBlockEntity dock = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(helper.getLevel(),
                helper.absolutePos(DOCK));
        if (dock != null)
            total += dock.heldItems().count(key);
        helper.assertValueEqual(total, expected, "conserved " + key + " (storage, stations, head, dropped)");
    }

    private static long chestCount(GameTestHelper helper, RackPosition rack, ItemKey key) {
        return aisle(helper).storedAt(rack, key);
    }

    /** Items of {@code key} in the buffer of the station at {@code rack}; 0 if there is none. */
    private static long stationCount(GameTestHelper helper, RackPosition rack, ItemKey key) {
        return aisle(helper).stationCount(rack, key);
    }

    /**
     * Items of exactly {@code key} lying anywhere in the test area. Both halves of that matter for the conservation
     * checks: items spilled away from the dock (a failed give-back drops at the location that refused them) must be
     * counted too, and items are told apart by their components, not only by their item type.
     */
    private static long droppedCount(GameTestHelper helper, ItemKey key) {
        long total = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM)) {
            if (key.matches(entity.getItem()))
                total += entity.getItem().getCount();
        }
        return total;
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(GameTestHelper helper, RackPosition rack) {
        WarehouseInterfaceBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(helper.getLevel(),
                absRack(helper, rack));
        if (be == null)
            helper.fail("warehouse interface missing", relPos(rack));
        return be;
    }

    private static BlockPos relPos(RackPosition rack) {
        return RELATIVE.rackPos(rack);
    }

    private static BlockPos absRack(GameTestHelper helper, RackPosition rack) {
        return helper.absolutePos(relPos(rack));
    }

    private static BlockPos chestOf(RackPosition rack) {
        return relPos(rack).relative(RELATIVE.sideDirection(rack.side()));
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        return aisle(helper).handlerAt(pos);
    }

    private static void insertAll(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        aisle(helper).insertAll(handler, stack);
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper) {
        return aisle(helper).dock();
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper) {
        return aisle(helper).controller();
    }

    private static WarehouseOutputBlockEntity outputAt(GameTestHelper helper) {
        return aisle(helper).outputAt(OUTPUT);
    }

    private static CreativeMotorBlockEntity motorAt(GameTestHelper helper) {
        return aisle(helper).motor();
    }

    private static FilteringBehaviour filterOf(GameTestHelper helper, WarehouseOutputBlockEntity output) {
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            helper.fail("the output has no request filter");
        return filter;
    }

    /** A fresh block entity of the same type loaded from {@code tag} (as after a restart). */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
