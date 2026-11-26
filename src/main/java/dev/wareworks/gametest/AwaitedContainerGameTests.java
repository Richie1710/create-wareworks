package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of a <b>filled container something waits for as an item</b> (ADR-089, amended): a water bucket that a
 * production order, a request or a stock keeper minimum waits for is stored on a shelf instead of being poured into a
 * fluid bay, which would drain it and leave the waiter with nothing. Every other filled container is poured as before.
 * <p>
 * Layout ({@code aisle_16x10x7}): right plane a production station at 0, the input at 1, an output at 2, a second
 * production station at 3 and a stock keeper at 4; left plane a dirt chest at 1, a chest of empty buckets at 2, a free
 * chest at 3 and an unfiltered copper fluid bay holding water at 4. The test plays the player's machines.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class AwaitedContainerGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 120;
    private static final int BAY_START = 5000;

    private static final RackPosition STATION = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition SECOND_STATION = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition OVERFLOW = new RackPosition(5, 0, Side.RIGHT);
    private static final RackPosition DIRT_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition BUCKET_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition FREE_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey MUD = ItemKey.of(Items.MUD);
    private static final ItemKey CLAY = ItemKey.of(Items.CLAY_BALL);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);

    private AwaitedContainerGameTests() {
    }

    /** The reported stall: the ordered water bucket comes back and must complete the order, not vanish into the bay. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketResultCompletesTheOrder(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> {
                    rig.take(STATION);
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> {
                    rig.assertBay(BAY_START);
                    rig.assertAt(OUTPUT, WATER_BUCKET, 1);
                    rig.assertNoOpenOrder();
                })
                // Nothing waits any more: the next water bucket is poured into the bay, as before (issue #60).
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenWaitUntil(() -> rig.assertBay(BAY_START + 1000))
                .thenExecute(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 0L,
                        "no shelf holds one"))
                .thenSucceed();
    }

    /** Only what is awaited stays an item: one of three buckets, the other two are poured. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketTakesOnlyWhatIsAwaited(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> {
                    rig.take(STATION);
                    rig.arrive(WATER_BUCKET);
                    rig.arrive(WATER_BUCKET);
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(OUTPUT, WATER_BUCKET, 1);
                    rig.assertBay(BAY_START + 2000);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    rig.assertBay(BAY_START + 2000);
                    helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 0L, "none left on a shelf");
                })
                .thenSucceed();
    }

    /** A stock keeper minimum of the bucket ITEM: restocked once, satisfied, and what is beyond it is poured. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedKeeperMinimumRestocksOnce(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> rig.keepItems(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> {
                    helper.assertTrue(rig.onlyOrder().isRestock(), "an automatic order");
                    rig.take(STATION);
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> {
                    rig.assertNoOpenOrder();
                    helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "kept on a shelf");
                    rig.assertBay(BAY_START);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(rig.controller().openProductionOrders().size(), 0, "no second order");
                    helper.assertValueEqual(rig.controller().pausedStockRuleCount(), 0, "nothing paused");
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> rig.assertBay(BAY_START + 1000))
                .thenExecute(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "still one"))
                .thenSucceed();
    }

    /** A minimum without any pattern: the player's buckets fill the minimum, the rest are poured. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedKeeperMinimumWithoutPattern(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> {
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.keepItems(WATER_BUCKET, 2);
                    for (int i = 0; i < 4; i++)
                        rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(INPUT, WATER_BUCKET, 0);
                    helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 2L, "two on shelves");
                    rig.assertBay(BAY_START + 2000);
                })
                .thenSucceed();
    }

    /** A chain: the filled bucket is the intermediate another order pays as an item ingredient. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketAsChainIntermediate(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        rig.start()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity second = rig.aisle.productionAt(SECOND_STATION);
                    helper.assertTrue(second.setPatternEntry(0, 0, WATER_BUCKET, 1), "a water bucket");
                    helper.assertTrue(second.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, CLAY, 4), "clay");
                })
                .thenIdle(40)
                .thenExecute(() -> rig.order(CLAY, 4))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> {
                    rig.take(STATION);
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> {
                    rig.assertBay(BAY_START);
                    rig.assertAt(SECOND_STATION, WATER_BUCKET, 1);
                })
                .thenExecute(() -> {
                    rig.take(SECOND_STATION);
                    rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.rackPos(INPUT)), CLAY.toStack(4));
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(OUTPUT, CLAY, 4);
                    rig.assertNoOpenOrder();
                    rig.assertBay(BAY_START);
                })
                .thenSucceed();
    }

    /** The crane is already carrying a bucket to the bay when an order for it appears: it is not poured. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketAlreadyOnItsWayToTheBay(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenWaitUntil(() -> {
                    Optional<TransportJob<ItemKey, RackPosition>> job = rig.aisle.dock().currentJob();
                    helper.assertTrue(job.isPresent() && job.get().picked() && job.get().target().equals(BAY_RACK),
                            "on its way to the bay");
                })
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(() -> {
                    rig.assertAt(OUTPUT, WATER_BUCKET, 1);
                    rig.assertBay(BAY_START);
                })
                .thenSucceed();
    }

    /**
     * No shelf has room: the awaited bucket waits in the input with the reason, the bay is left alone, and a freed slot
     * takes it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketWaitsWithoutShelfRoom(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.startFull()
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> {
                    rig.take(STATION);
                    rig.arrive(WATER_BUCKET);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    rig.assertBay(BAY_START);
                    rig.assertAt(INPUT, WATER_BUCKET, 1);
                    helper.assertValueEqual(rig.refusal(), "AWAITED_AS_ITEM", "the input says why it waits");
                    rig.aisle.handlerAt(rig.aisle.inventoryPos(FREE_RACK)).extractItem(0, 64, false);
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(OUTPUT, WATER_BUCKET, 1);
                    rig.assertNoOpenOrder();
                    rig.assertBay(BAY_START);
                })
                .thenSucceed();
    }

    /**
     * Open requests: one the shelf covers waits for nothing, so an arriving bucket is poured; once the shelf's bucket
     * is gone the request still waits, and the next one that arrives is shelved and serves it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedByARequestTheShelvesCannotCover(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> {
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.inventoryPos(FREE_RACK)),
                            WATER_BUCKET.toStack(1));
                    // The crane stands, so the request stays open while the shelf is changed by hand.
                    rig.aisle.motor().generatedSpeed.setValue(0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "in stock"))
                .thenExecute(() -> {
                    rig.order(WATER_BUCKET, 1);
                    helper.assertFalse(rig.controller().awaitsAsItem(WATER_BUCKET), "the shelf covers the request");
                    rig.aisle.handlerAt(rig.aisle.inventoryPos(FREE_RACK)).extractItem(0, 1, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.controller().awaitsAsItem(WATER_BUCKET),
                        "now it waits for one to come in"))
                .thenExecute(() -> {
                    rig.arrive(WATER_BUCKET);
                    rig.aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(OUTPUT, WATER_BUCKET, 1);
                    rig.assertBay(BAY_START);
                })
                .thenExecute(() -> {
                    helper.assertFalse(rig.controller().awaitsAsItem(WATER_BUCKET), "served");
                    rig.arrive(WATER_BUCKET);
                })
                .thenWaitUntil(() -> rig.assertBay(BAY_START + 1000))
                .thenSucceed();
    }

    /** The wait ends with the order: a bucket waiting for shelf room is poured once its order is cancelled. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketPouredWhenTheOrderIsCancelled(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.startFull()
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    rig.assertBay(BAY_START);
                    rig.assertAt(INPUT, WATER_BUCKET, 1);
                    helper.assertTrue(rig.controller().cancelProductionOrder(rig.onlyOrder().id()).isPresent(),
                            "cancelled");
                })
                .thenWaitUntil(() -> rig.assertBay(BAY_START + 1000))
                .thenSucceed();
    }

    /**
     * A cancelled fluid line while a water bucket is awaited: the bucket the warehouse had just filled from its own bay
     * is shelved for the waiter, and is not counted as the other order's product.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketFromACancelledFillCreditsNothing(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        rig.start()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity second = rig.aisle.productionAt(SECOND_STATION);
                    helper.assertTrue(second.setPatternEntry(0, 0, DIRT, 1), "dirt");
                    helper.assertTrue(second.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "mud");
                    second.editFluidCell(0, 0, "minecraft:water", 1000);
                })
                .thenIdle(40)
                .thenExecute(() -> rig.order(MUD, 1))
                .thenWaitUntil(() -> {
                    Optional<TransportJob<ItemKey, RackPosition>> job = rig.aisle.dock().currentJob();
                    helper.assertTrue(job.isPresent() && job.get().type() == JobType.FILL
                            && job.get().key().equals(WATER_BUCKET), "carrying the filled bucket");
                })
                .thenExecute(() -> {
                    rig.order(WATER_BUCKET, 1);
                    ProductionOrder<ItemKey, RackPosition> mud = rig.controller().productionOrders().stream()
                            .filter(order -> order.result().equals(MUD)).findFirst().orElseThrow();
                    helper.assertTrue(rig.controller().cancelProductionOrder(mud.id()).isPresent(), "mud cancelled");
                })
                .thenWaitUntil(() -> rig.assertAt(OUTPUT, WATER_BUCKET, 1))
                .thenExecuteAfter(40, () -> {
                    ProductionOrder<ItemKey, RackPosition> water = rig.controller().productionOrders().stream()
                            .filter(order -> order.result().equals(WATER_BUCKET)).findFirst().orElseThrow();
                    helper.assertValueEqual(water.produced(), 0L, "the warehouse's own bucket is no product");
                    helper.assertTrue(water.isOpen(), "its machine still owes it one");
                })
                .thenSucceed();
    }

    /**
     * No shelf has room and an overflow port is open: the awaited bucket does not leave through the port, which it
     * could only reach because the fluid bay is passed over. It waits in the input, and goes into the bay once the order
     * is gone.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketNeverLeavesThroughAnOverflow(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.startFull()
                .thenExecute(() -> rig.aisle.output(OVERFLOW))
                .thenWaitUntil(() -> rig.aisle.assertReady(4, 1, 2))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = rig.aisle.outputAt(OVERFLOW);
                    helper.assertTrue(port.setPortRank(-1), "an overflow port");
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                })
                .thenIdle(40)
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(rig::assertSupplied)
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    rig.assertAt(OVERFLOW, WATER_BUCKET, 0);
                    rig.assertAt(INPUT, WATER_BUCKET, 1);
                    rig.assertBay(BAY_START);
                    helper.assertValueEqual(rig.refusal(), "AWAITED_AS_ITEM", "the input says why it waits");
                    helper.assertTrue(rig.controller().cancelProductionOrder(rig.onlyOrder().id()).isPresent(),
                            "cancelled");
                })
                .thenWaitUntil(() -> rig.assertBay(BAY_START + 1000))
                .thenExecute(() -> rig.assertAt(OVERFLOW, WATER_BUCKET, 0))
                .thenSucceed();
    }

    /**
     * A crane that already holds an awaited bucket which no shelf and no station can take pours it back into a bay
     * instead of holding it and stopping the warehouse.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketTheCraneCannotPutDownGoesIntoABay(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        rig.startFull()
                .thenExecute(() -> {
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.waterMudPattern();
                })
                .thenIdle(40)
                .thenExecute(() -> rig.order(MUD, 1))
                .thenWaitUntil(() -> rig.assertCarryingJob(JobType.FILL, WATER_BUCKET))
                .thenExecute(() -> {
                    // A minimum nothing restocks (no pattern makes the bucket): the wait does not end by itself.
                    rig.keepItems(WATER_BUCKET, 1);
                    rig.fillOutput();
                    rig.cancelOrderFor(MUD);
                })
                .thenWaitUntil(() -> {
                    rig.aisle.assertIdleAndEmpty();
                    rig.assertBay(BAY_START);
                })
                .thenExecute(() -> {
                    rig.assertAt(OUTPUT, WATER_BUCKET, 0);
                    helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 0L, "poured, not shelved");
                })
                .thenSucceed();
    }

    /**
     * The shelf an awaited bucket is on its way to fills up, the input is full again and the water bay is full: nothing
     * can keep the bucket and no bay takes it, so it leaves through the overflow port instead of staying in the crane.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketOfALostShelfTripUsesTheOverflowPastAFullBay(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        int[] full = new int[1];
        rig.startFull()
                .thenExecute(() -> rig.aisle.output(OVERFLOW))
                .thenWaitUntil(() -> rig.aisle.assertReady(4, 1, 2))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = rig.aisle.outputAt(OVERFLOW);
                    helper.assertTrue(port.setPortRank(-1), "an overflow port");
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.keepItems(WATER_BUCKET, 1);
                    rig.bay().fill(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), false);
                    full[0] = rig.bay().millibuckets();
                    rig.shelf(FREE_RACK).extractItem(0, 64, false);
                })
                .thenIdle(40)
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenWaitUntil(() -> {
                    rig.assertCarryingJob(JobType.STORE, WATER_BUCKET);
                    helper.assertTrue(rig.aisle.dock().currentJob().orElseThrow().target().equals(FREE_RACK),
                            "on its way to the free shelf place");
                })
                .thenExecute(() -> {
                    helper.assertTrue(rig.shelf(FREE_RACK)
                            .insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false).isEmpty(), "shelf refilled");
                    rig.fillInput();
                })
                .thenWaitUntil(() -> {
                    rig.assertAt(OVERFLOW, WATER_BUCKET, 1);
                    helper.assertTrue(rig.aisle.dock().currentJob()
                            .map(job -> !job.key().equals(WATER_BUCKET)).orElse(true), "the crane let go of it");
                })
                .thenExecute(() -> rig.assertBay(full[0]))
                .thenSucceed();
    }

    /**
     * The bay refuses the drop because a shelf would keep the bucket, and while the arm retracts the wait ends and the
     * shelf and the input fill up: the bay that was refused still comes before the overflow port.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketRefusedAtTheBayStillBeatsAnOverflow(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        boolean[] changed = new boolean[1];
        rig.startFull()
                .thenExecute(() -> rig.aisle.output(OVERFLOW))
                .thenWaitUntil(() -> rig.aisle.assertReady(4, 1, 2))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = rig.aisle.outputAt(OVERFLOW);
                    helper.assertTrue(port.setPortRank(-1), "an overflow port");
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.shelf(FREE_RACK).extractItem(0, 64, false);
                })
                .thenIdle(40)
                .thenExecute(() -> rig.arrive(WATER_BUCKET))
                .thenWaitUntil(() -> {
                    rig.assertCarryingJob(JobType.STORE, WATER_BUCKET);
                    helper.assertTrue(rig.aisle.dock().currentJob().orElseThrow().target().equals(BAY_RACK),
                            "on its way to the bay");
                })
                .thenExecute(() -> {
                    rig.keepItems(WATER_BUCKET, 1);
                    helper.onEachTick(() -> {
                        if (changed[0] || rig.aisle.dock().craneState().phase() != CranePhase.RETRACT_TARGET
                                || rig.aisle.dock().currentJob()
                                        .filter(job -> job.key().equals(WATER_BUCKET)).isEmpty())
                            return;
                        changed[0] = true;
                        rig.keepItems(WATER_BUCKET, 0);
                        helper.assertTrue(rig.shelf(FREE_RACK)
                                .insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false).isEmpty(),
                                "shelf refilled");
                        rig.fillInput();
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(changed[0], "the bay refused the drop"))
                .thenWaitUntil(() -> helper.assertTrue(rig.aisle.dock().currentJob()
                        .map(job -> !job.key().equals(WATER_BUCKET)).orElse(true), "the crane let go of it"))
                .thenExecute(() -> {
                    rig.assertAt(OVERFLOW, WATER_BUCKET, 0);
                    rig.assertBay(BAY_START + 1000);
                })
                .thenSucceed();
    }

    /**
     * A cancelled retrieval of an awaited bucket with no shelf to take it goes back into the water bay, not into an
     * output that asked for nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketOfACancelledRetrieveGoesIntoABayNotAnOutput(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.startFull()
                .thenExecute(() -> {
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.shelf(FREE_RACK).extractItem(0, 64, false);
                    helper.assertTrue(rig.shelf(FREE_RACK).insertItem(0, WATER_BUCKET.toStack(1), false).isEmpty(),
                            "one bucket on the shelf");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "in stock"))
                .thenIdle(40)
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(() -> rig.assertCarryingJob(JobType.RETRIEVE, WATER_BUCKET))
                .thenExecute(() -> {
                    helper.assertTrue(rig.shelf(FREE_RACK)
                            .insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false).isEmpty(), "shelf refilled");
                    // A minimum nothing restocks: the bucket stays awaited.
                    rig.keepItems(WATER_BUCKET, 1);
                    helper.assertTrue(rig.controller()
                            .cancelRequest(rig.controller().openRequests().getFirst().id()).isPresent(), "cancelled");
                })
                .thenWaitUntil(() -> {
                    rig.aisle.assertIdleAndEmpty();
                    rig.assertAt(OUTPUT, WATER_BUCKET, 0);
                    rig.assertBay(BAY_START + 1000);
                })
                .thenSucceed();
    }

    /** The same for a cancelled fluid line: the filled bucket goes back where its water came from. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketOfACancelledFillGoesIntoABayNotAnOutput(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        rig.startFull()
                .thenExecute(() -> {
                    rig.station().setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, null, 1);
                    rig.waterMudPattern();
                })
                .thenIdle(40)
                .thenExecute(() -> rig.order(MUD, 1))
                .thenWaitUntil(() -> rig.assertCarryingJob(JobType.FILL, WATER_BUCKET))
                .thenExecute(() -> {
                    rig.keepItems(WATER_BUCKET, 1);
                    rig.cancelOrderFor(MUD);
                })
                .thenWaitUntil(() -> {
                    rig.aisle.assertIdleAndEmpty();
                    rig.assertAt(OUTPUT, WATER_BUCKET, 0);
                    rig.assertBay(BAY_START);
                })
                .thenSucceed();
    }

    /**
     * A stock bucket on its way to a station as an ingredient goes back onto a shelf, because a bucket order appeared
     * and its own order was cancelled: that rise of the stock level is nobody's product.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketFromACancelledSupplyCreditsNothing(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        rig.start()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity second = rig.aisle.productionAt(SECOND_STATION);
                    helper.assertTrue(second.setPatternEntry(0, 0, WATER_BUCKET, 1), "a water bucket");
                    helper.assertTrue(second.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, CLAY, 4), "clay");
                    rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.inventoryPos(FREE_RACK)),
                            WATER_BUCKET.toStack(1));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "in stock"))
                .thenIdle(40)
                .thenExecute(() -> rig.order(CLAY, 4))
                .thenWaitUntil(() -> rig.assertCarryingJob(JobType.SUPPLY, WATER_BUCKET))
                .thenExecute(() -> {
                    rig.order(WATER_BUCKET, 1);
                    rig.cancelOrderFor(CLAY);
                })
                .thenWaitUntil(() -> rig.assertAt(OUTPUT, WATER_BUCKET, 1))
                .thenExecuteAfter(40, rig::assertWaterOrderUncredited)
                .thenSucceed();
    }

    /**
     * The same for a retrieval: a port that filled up under the carrying crane sends its bucket back onto a shelf,
     * which must not complete the bucket order that appeared meanwhile.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awaitedBucketBackFromARetrieveCreditsNothing(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.start()
                .thenExecute(() -> rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.inventoryPos(FREE_RACK)),
                        WATER_BUCKET.toStack(1)))
                .thenWaitUntil(() -> helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "in stock"))
                .thenIdle(40)
                .thenExecute(() -> rig.order(WATER_BUCKET, 1))
                .thenWaitUntil(() -> rig.assertCarryingJob(JobType.RETRIEVE, WATER_BUCKET))
                .thenExecute(() -> {
                    rig.order(WATER_BUCKET, 1);
                    rig.fillOutput();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(rig.controller().countOf(WATER_BUCKET), 1L, "back on a shelf");
                    rig.aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(40, () -> {
                    rig.assertWaterOrderUncredited();
                    rig.assertBay(BAY_START);
                })
                .thenSucceed();
    }

    // --- fixture ----------------------------------------------------------------------------------------------------

    private static final class Rig {
        final GameTestHelper helper;
        final AisleFixture aisle;
        final boolean second;

        Rig(GameTestHelper helper, boolean second) {
            this.helper = helper;
            this.second = second;
            aisle = new AisleFixture(helper, AISLE_Z, RAILS);
            helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                    .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
            aisle.build(true);
        }

        GameTestSequence start() {
            aisle.storage(DIRT_RACK, DIRT.toStack(32));
            aisle.storage(BUCKET_RACK, new ItemStack(Items.BUCKET, 8));
            aisle.storage(FREE_RACK);
            return finish();
        }

        /** Every shelf slot taken, by stacks that stay in their slot when one dirt or one bucket is fetched. */
        GameTestSequence startFull() {
            aisle.storage(DIRT_RACK, full(Items.DIRT, 64));
            aisle.storage(BUCKET_RACK, full(Items.BUCKET, 16));
            aisle.storage(FREE_RACK, full(Items.COBBLESTONE, 64));
            return finish();
        }

        private static ItemStack[] full(Item item, int each) {
            ItemStack[] stacks = new ItemStack[27];
            for (int slot = 0; slot < stacks.length; slot++)
                stacks[slot] = new ItemStack(item, each);
            return stacks;
        }

        private GameTestSequence finish() {
            aisle.input(INPUT);
            aisle.output(OUTPUT);
            aisle.production(STATION);
            if (second)
                aisle.production(SECOND_STATION);
            aisle.stockKeeper(KEEPER);
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
            return helper.startSequence()
                    .thenWaitUntil(() -> aisle.assertReady(4, 1, 1))
                    .thenExecute(() -> {
                        WarehouseProductionBlockEntity station = station();
                        helper.assertTrue(station.setPatternEntry(0, 0, DIRT, 1), "dirt");
                        helper.assertTrue(station.setPatternEntry(0, 1, BUCKET, 1), "an empty bucket");
                        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY,
                                WATER_BUCKET, 1), "makes a water bucket");
                        helper.assertValueEqual(bay().fill(new FluidStack(Fluids.WATER, BAY_START), false),
                                BAY_START, "water in the bay");
                    })
                    .thenWaitUntil(() -> {
                        helper.assertValueEqual(controller().availableFluid(FluidKey.of(Fluids.WATER)),
                                (long) BAY_START, "indexed water");
                        helper.assertTrue(controller().countOf(DIRT) > 0 && controller().countOf(BUCKET) > 0,
                                "indexed stock");
                    })
                    .thenIdle(40);
        }

        /** The second station makes mud from dirt and 1000 mB of water. */
        void waterMudPattern() {
            WarehouseProductionBlockEntity station = aisle.productionAt(SECOND_STATION);
            helper.assertTrue(station.setPatternEntry(0, 0, DIRT, 1), "dirt");
            helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "mud");
            station.editFluidCell(0, 0, "minecraft:water", 1000);
        }

        void assertCarryingJob(JobType type, ItemKey key) {
            Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
            helper.assertTrue(job.isPresent() && job.get().type() == type && job.get().picked()
                    && job.get().key().equals(key), "carrying " + key + " on a " + type + " trip");
        }

        void cancelOrderFor(ItemKey result) {
            ProductionOrder<ItemKey, RackPosition> order = orderFor(result);
            helper.assertTrue(controller().cancelProductionOrder(order.id()).isPresent(), result + " cancelled");
        }

        ProductionOrder<ItemKey, RackPosition> orderFor(ItemKey result) {
            return controller().productionOrders().stream().filter(order -> order.result().equals(result))
                    .findFirst().orElseThrow();
        }

        void assertWaterOrderUncredited() {
            ProductionOrder<ItemKey, RackPosition> water = orderFor(WATER_BUCKET);
            helper.assertValueEqual(water.produced(), 0L, "the warehouse's own bucket is no product");
            helper.assertTrue(water.isOpen(), "its machine still owes it one");
        }

        /** Every slot of the output taken by something that does not stack, so it accepts nothing. */
        void fillOutput() {
            WarehouseOutputBlockEntity output = aisle.outputAt(OUTPUT);
            for (int slot = 0; slot < 64; slot++) {
                if (!output.insert(new ItemStack(Items.IRON_SWORD), false).isEmpty())
                    break;
            }
            helper.assertValueEqual(output.insert(WATER_BUCKET.toStack(1), true).getCount(), 1, "the output is full");
        }

        IItemHandler shelf(RackPosition rack) {
            return aisle.handlerAt(aisle.inventoryPos(rack));
        }

        /** Every slot of the input taken, as a belt that refills it would. */
        void fillInput() {
            IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT));
            for (int slot = 0; slot < input.getSlots(); slot++)
                input.insertItem(slot, new ItemStack(Items.IRON_SWORD), false);
            boolean room = false;
            for (int slot = 0; slot < input.getSlots(); slot++)
                room |= input.insertItem(slot, WATER_BUCKET.toStack(1), true).isEmpty();
            helper.assertFalse(room, "the input is full");
        }

        WarehouseControllerBlockEntity controller() {
            return aisle.controller();
        }

        WarehouseProductionBlockEntity station() {
            return aisle.productionAt(STATION);
        }

        FluidBayBlockEntity bay() {
            return WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                    helper.absolutePos(aisle.rackPos(BAY_RACK)));
        }

        void assertBay(int millibuckets) {
            helper.assertValueEqual(bay().millibuckets(), millibuckets, "water in the bay");
        }

        void assertAt(RackPosition station, ItemKey key, int count) {
            helper.assertValueEqual(aisle.stationCount(station, key), (long) count, key + " at " + station);
        }

        void assertSupplied() {
            assertAt(STATION, DIRT, 1);
            assertAt(STATION, BUCKET, 1);
        }

        void take(RackPosition station) {
            IItemHandler handler = aisle.handlerAt(aisle.rackPos(station));
            for (int slot = 0; slot < handler.getSlots(); slot++)
                handler.extractItem(slot, Integer.MAX_VALUE, false);
        }

        void arrive(ItemKey key) {
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), key.toStack(1));
        }

        void order(ItemKey key, int amount) {
            RequestResult result = controller().request(aisle.absoluteRackPos(OUTPUT), key, amount);
            helper.assertTrue(result.isAccepted(), "the order is accepted: " + result);
        }

        void keepItems(ItemKey key, int minimum) {
            WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER);
            keeper.editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
            keeper.editRule(0, StockKeeperRules.FIELD_UNIT, null, 0L);
            keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) minimum);
        }

        ProductionOrder<ItemKey, RackPosition> onlyOrder() {
            List<ProductionOrder<ItemKey, RackPosition>> orders = controller().openProductionOrders();
            helper.assertValueEqual(orders.size(), 1, "exactly one open order");
            return orders.getFirst();
        }

        void assertNoOpenOrder() {
            helper.assertValueEqual(controller().openProductionOrders().size(), 0, "no open order");
            helper.assertTrue(controller().productionOrders().stream()
                    .allMatch(order -> order.state() == ProductionOrderState.COMPLETE), "every order completed");
        }

        String refusal() {
            return controller().storeRefusalAt(aisle.absoluteRackPos(INPUT)).map(Enum::name).orElse("none");
        }
    }
}
