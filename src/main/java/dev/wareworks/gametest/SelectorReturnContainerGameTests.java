package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the <b>empty containers</b> of a Recipe Selector station's own fluid patterns (ADR-085 D9, amended;
 * ADR-089): the bucket of a fluid cell comes back through the station's
 * bound return input and must be stored like any stored product, never hold the station, never credit an order. An
 * item cell with a crafting remainder is covered too: Create's Basin returns none.
 * <p>
 * The selector is the real block, placed on a real Basin and linked and bound with real sneak-clicks. The tests play the
 * player's machine where the fluid is concerned (they take the filled buckets out of the station and put the empty ones
 * into the return input); the honey test lets the real Press make the block in the real Basin.
 * <p>
 * Layout ({@code aisle_16x10x7}): right plane a production station at 0, the return input at 1, an output at 2; left
 * plane a dirt (or honey bottle) chest at 1, a chest of empty buckets at 2, a copper fluid bay of water at 3 and an empty
 * chest at 4. The Basin stands behind the right plane with its operator two above it and the selector on its west side.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class SelectorReturnContainerGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 3600;
    private static final int SETTLE_TICKS = 100;
    /** Longer than the 600 ticks a finished order stays listed ({@code FINISHED_ORDER_RETENTION_TICKS}). */
    private static final int FORGOTTEN_TICKS = 700;

    private static final RackPosition STATION_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition RETURN_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    /** Only {@link #selectorReturnedBucketCreditsNoBucketOrder}: a station without a selector, and an ordinary input. */
    private static final RackPosition PLAIN_STATION_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition PLAIN_INPUT_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition FEED_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition BUCKET_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition EMPTY_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey MUD = ItemKey.of(Items.MUD);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey HONEY_BOTTLE = ItemKey.of(Items.HONEY_BOTTLE);
    private static final ItemKey HONEY_BLOCK = ItemKey.of(Items.HONEY_BLOCK);
    private static final ItemKey GLASS_BOTTLE = ItemKey.of(Items.GLASS_BOTTLE);
    private static final String WATER_ID = "minecraft:water";
    private static final int BUCKETS_IN_STOCK = 8;
    private static final int DIRT_IN_STOCK = 32;

    private SelectorReturnContainerGameTests() {
    }

    // --- the bucket of a fluid cell --------------------------------------------------------------------------------

    /**
     * The player's report: the empty water bucket goes back through the bound return input while the order is open. It
     * must not hold the station, is stored back on its shelf, credits the order with nothing, and the mud that follows
     * completes the order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketWhileOrderOpen(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.giveBack(BUCKET, 1);
                })
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK))
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.onlyOrder().produced(), 0L, "a bucket credits nothing");
                    helper.assertTrue(rig.onlyOrder().isOpen(), "the order still waits for its mud");
                    rig.giveBack(MUD, 4);
                })
                .thenWaitUntil(() -> rig.assertCompletedAtOutput(MUD, 4))
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /** A late bucket: the mud came back first and the order is complete. The bucket must not hold either. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketAfterCompletion(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.giveBack(MUD, 4);
                })
                .thenWaitUntil(() -> rig.assertCompletedAtOutput(MUD, 4))
                // Long after the finished order has left the list: the station's pattern alone names the bucket.
                .thenIdle(FORGOTTEN_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(rig.controller().productionOrders().isEmpty(), "the order is forgotten");
                    rig.giveBack(BUCKET, 1);
                })
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /**
     * A cancelled order: the station holds for the lost ingredients, as before. A bucket already in the return input does
     * not stand in the way of the release, and a second one that arrives after the release does not hold again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketAfterCancel(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.start()
                .thenExecute(() -> rig.order(MUD, 8))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L, "both buckets");
                    helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, DIRT), 0L, "before the dirt");
                })
                .thenExecute(() -> {
                    helper.assertTrue(rig.controller().cancelProductionOrder(rig.onlyOrder().id()).isPresent(),
                            "cancelled");
                    helper.assertValueEqual(rig.station().selectorHold().map(SelectorHold::cause),
                            Optional.of(SelectorHold.Cause.LOST_INGREDIENTS), "the lost buckets hold the station");
                })
                .thenWaitUntil(rig.aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.giveBack(BUCKET, 1);
                    helper.assertValueEqual(rig.controller().releaseSelectorHold(rig.station()).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED,
                            "the bucket in the return input is in nobody's way");
                    rig.neverHolds();
                })
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK - 1))
                .thenExecute(() -> rig.giveBack(BUCKET, 1))
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /** Two containers: both buckets go back as soon as they are emptied, before the dirt has been supplied. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketsBeforeTheDirt(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(MUD, 8))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L, "both buckets");
                    helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, DIRT), 0L, "no dirt yet");
                })
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.giveBack(BUCKET, 2);
                })
                .thenWaitUntil(() -> {
                    rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK);
                    helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, DIRT), 8L, "then the dirt");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.onlyOrder().produced(), 0L, "buckets credit nothing");
                    rig.takeIngredients();
                    rig.giveBack(MUD, 8);
                })
                .thenWaitUntil(() -> rig.assertCompletedAtOutput(MUD, 8))
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /** The station's pattern is removed while the bucket is out: the order that delivered it still names it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketAfterPatternRemoved(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    helper.assertTrue(rig.station().clearPattern(0), "the pattern is removed");
                    rig.giveBack(BUCKET, 1);
                })
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /**
     * An unloaded station: with an open order its bucket is still stored (the order names it); without any order and any
     * listed one the bucket waits in the return input, no hold is armed, and the station stores it once it is back.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketUnloadedStation(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.controller().hideSelectorStationForTest(rig.stationAbsolute(), true);
                    rig.giveBack(BUCKET, 1);
                })
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK))
                .thenExecute(() -> rig.giveBack(MUD, 4))
                .thenWaitUntil(() -> rig.assertCompletedAtOutput(MUD, 4))
                .thenIdle(FORGOTTEN_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(rig.controller().productionOrders().isEmpty(), "the order is forgotten");
                    rig.giveBack(BUCKET, 1);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.returnCount(BUCKET), 1,
                            "nothing is known of an unloaded station's patterns: the bucket waits");
                    rig.assertNotHeld();
                    rig.controller().hideSelectorStationForTest(rig.stationAbsolute(), false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.returnCount(BUCKET), 0, "stored once it is back"))
                .thenWaitUntil(() -> rig.assertStored(BUCKET_RACK, BUCKET, BUCKETS_IN_STOCK + 1))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    /**
     * A returned container is no product. An ordinary order for <b>empty buckets</b> is open at another station while
     * the mud station's own bucket comes back through the selector's return input: neither the arrival nor the rise of
     * the stock level may credit it. A bucket that comes through an ordinary input completes it as ever.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketCreditsNoBucketOrder(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.aisle.input(PLAIN_INPUT_RACK);
        rig.inputs = 2;
        rig.aisle.production(PLAIN_STATION_RACK);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity plain = rig.aisle.productionAt(PLAIN_STATION_RACK);
                    helper.assertTrue(plain.setPatternEntry(0, 0, IRON, 3), "iron");
                    helper.assertTrue(plain.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BUCKET, 1),
                            "makes a bucket");
                    rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.inventoryPos(EMPTY_RACK)), IRON.toStack(3));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.controller().countOf(IRON), 3L, "the iron is indexed"))
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    // All the buckets left on the shelf and one more, which the plain station has to make.
                    RequestResult result = rig.controller().request(rig.aisle.absoluteRackPos(OUTPUT_RACK), BUCKET,
                            BUCKETS_IN_STOCK);
                    helper.assertTrue(result.isAccepted(), "the bucket order is accepted: " + result);
                    helper.assertValueEqual(result.producing(), 1, "one bucket is made");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(rig.aisle.stationCount(PLAIN_STATION_RACK, IRON), 3L, "its iron arrived");
                    helper.assertValueEqual(rig.aisle.stationCount(OUTPUT_RACK, BUCKET), BUCKETS_IN_STOCK - 1L,
                            "the stocked buckets are delivered");
                    rig.aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.orderFor(BUCKET).produced(), 0L, "nothing is made yet");
                    rig.giveBack(BUCKET, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.returnCount(BUCKET), 0, "the crane takes the bucket"))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order = rig.orderFor(BUCKET);
                    helper.assertValueEqual(order.produced(), 0L, "a returned bucket is not the bucket order's product");
                    helper.assertTrue(order.isOpen(), "so that order still waits for its machine");
                    rig.aisle.insertAll(rig.aisle.handlerAt(rig.aisle.rackPos(PLAIN_INPUT_RACK)), BUCKET.toStack(1));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.orderFor(BUCKET).state(),
                        ProductionOrderState.COMPLETE, "a bucket from an ordinary input completes it"))
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    // --- what still holds ------------------------------------------------------------------------------------------

    /** The quarantine is as it was for a foreign item: a stick in the middle of the fluid order holds the station. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedStickStillHolds(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> rig.giveBack(STICK, 1))
                .thenWaitUntil(() -> rig.assertHeldFor(STICK))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> helper.assertValueEqual(rig.returnCount(STICK), 1, "the stick stays quarantined"))
                .thenSucceed();
    }

    /** Only the station's own containers pass: a glass bottle at the mud station still holds it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedForeignContainerStillHolds(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.start()
                .thenExecute(() -> rig.giveBack(GLASS_BOTTLE, 1))
                .thenWaitUntil(() -> rig.assertHeldFor(GLASS_BOTTLE))
                .thenSucceed();
    }

    /** Only the <b>empty</b> container passes: one of the station's own filled water buckets, sent back, holds it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedFilledBucketStillHolds(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.start()
                .thenExecute(() -> rig.order(MUD, 4))
                .thenWaitUntil(() -> rig.assertDelivered(1, 4))
                .thenExecute(() -> {
                    rig.takeIngredients();
                    rig.giveBack(WATER_BUCKET, 1);
                })
                .thenWaitUntil(() -> rig.assertHeldFor(WATER_BUCKET))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> helper.assertValueEqual(rig.returnCount(WATER_BUCKET), 1,
                        "the water bucket stays quarantined"))
                .thenSucceed();
    }

    /**
     * A foreign water bucket something waits for, on its way from an ordinary input to the last free shelf place: the
     * place goes away and that input is full again. The crane pours it into the water bay; it does not put it into the
     * station's return input, where the quarantine would hold the station.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnInputIsNoRerouteTargetBeforeABay(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.aisle.input(PLAIN_INPUT_RACK);
        rig.aisle.stockKeeper(PLAIN_STATION_RACK);
        rig.inputs = 2;
        rig.start()
                .thenExecute(() -> {
                    // Every shelf place taken but one in the empty chest.
                    for (RackPosition rack : List.of(FEED_RACK, BUCKET_RACK, EMPTY_RACK))
                        fillSlots(rig.aisle.handlerAt(rig.aisle.inventoryPos(rack)));
                    rig.aisle.handlerAt(rig.aisle.inventoryPos(EMPTY_RACK)).extractItem(0, 64, false);
                    WarehouseStockKeeperBlockEntity keeper = rig.aisle.stockKeeperAt(PLAIN_STATION_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, WATER_BUCKET, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_UNIT, null, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 1L);
                })
                .thenIdle(40)
                .thenExecute(() -> rig.aisle.insertAll(
                        rig.aisle.handlerAt(rig.aisle.rackPos(PLAIN_INPUT_RACK)), WATER_BUCKET.toStack(1)))
                .thenWaitUntil(() -> helper.assertTrue(rig.aisle.dock().currentJob()
                        .filter(job -> job.picked() && job.key().equals(WATER_BUCKET)
                                && job.target().equals(EMPTY_RACK))
                        .isPresent(), "on its way to the free shelf place"))
                .thenExecute(() -> {
                    helper.assertTrue(rig.aisle.handlerAt(rig.aisle.inventoryPos(EMPTY_RACK))
                            .insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false).isEmpty(), "shelf refilled");
                    fillSlots(rig.aisle.handlerAt(rig.aisle.rackPos(PLAIN_INPUT_RACK)));
                    helper.onEachTick(() -> {
                        if (rig.returnCount(WATER_BUCKET) > 0)
                            helper.fail("the bucket went into the return input");
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.aisle.dock().currentJob()
                        .map(job -> !job.key().equals(WATER_BUCKET)).orElse(true), "the crane let go of it"))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    rig.assertNotHeld();
                    FluidBayBlockEntity bay = WareworksBlockEntityTypes.FLUID_BAY.getNullable(
                            helper.getLevel(), helper.absolutePos(rig.aisle.rackPos(BAY_RACK)));
                    helper.assertValueEqual(bay.millibuckets(), 6000, "the water is in the bay");
                })
                .thenSucceed();
    }

    /** A bucket filled with a fluid the station does not use holds it too. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedLavaBucketStillHolds(GameTestHelper helper) {
        Rig rig = Rig.mud(helper);
        rig.start()
                .thenExecute(() -> rig.giveBack(LAVA_BUCKET, 1))
                .thenWaitUntil(() -> rig.assertHeldFor(LAVA_BUCKET))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> helper.assertValueEqual(rig.returnCount(LAVA_BUCKET), 1,
                        "the lava bucket stays quarantined"))
                .thenSucceed();
    }

    /** A station whose patterns use neither a fluid nor a container: an empty bucket is foreign there and holds it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedBucketWithoutFluidStillHolds(GameTestHelper helper) {
        Rig rig = Rig.honey(helper);
        rig.start()
                .thenExecute(() -> rig.giveBack(BUCKET, 1))
                .thenWaitUntil(() -> rig.assertHeldFor(BUCKET))
                .thenSucceed();
    }

    // --- item ingredients with a crafting remainder ----------------------------------------------------------------

    /**
     * The same question for an item cell whose item leaves a crafting remainder: four honey bottles pressed into a honey
     * block. <b>Create 6.0.10's Basin hands back no remainder at all</b> ({@code BasinRecipe#apply} asks for it with the
     * crafting input of its {@code DummyCraftingContainer}, which is empty), so a local selector's machine sends nothing
     * but the product through the return input, and the quarantine expects no bottle. This pins that fact with the real
     * Press: if a Create update starts to return remainders, this test fails and the return input has to expect them.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnedNoRemainderFromABasin(GameTestHelper helper) {
        Rig rig = Rig.honey(helper);
        rig.neverHolds();
        rig.start()
                .thenExecute(() -> rig.order(HONEY_BLOCK, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(rig.aisle.stationCount(STATION_RACK, HONEY_BOTTLE), 4L,
                        "the honey is in the station"))
                .thenExecute(() -> {
                    int taken = extractAll(rig.aisle.handlerAt(rig.aisle.rackPos(STATION_RACK)), HONEY_BOTTLE);
                    ItemStack rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(
                            rig.basinEntity().getInputInventory(), HONEY_BOTTLE.toStack(taken), false);
                    helper.assertTrue(rest.isEmpty(), "the basin takes the honey");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.basinCount(HONEY_BLOCK), 1, "the press made the block"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(rig.basinCount(HONEY_BOTTLE), 0, "all the honey is used");
                    helper.assertValueEqual(rig.basinCount(GLASS_BOTTLE), 0, "and the basin hands back no bottle");
                    helper.assertValueEqual(ItemCensus.take(helper).getOrDefault(GLASS_BOTTLE, 0L), 0L,
                            "nor is one anywhere else");
                    rig.takeFromBasin(HONEY_BLOCK);
                    rig.giveBack(HONEY_BLOCK, 1);
                })
                .thenWaitUntil(() -> rig.assertCompletedAtOutput(HONEY_BLOCK, 1))
                .thenExecute(rig::assertNotHeld)
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Every empty slot taken by something that does not stack. */
    private static void fillSlots(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++)
            handler.insertItem(slot, new ItemStack(Items.IRON_SWORD), false);
    }

    private static int extractAll(IItemHandler handler, ItemKey key) {
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (key.matches(handler.getStackInSlot(slot)))
                taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        return taken;
    }

    /** The aisle, its Basin with its operator, and the real Recipe Selector placed and bound by real sneak-clicks. */
    private static final class Rig {
        final GameTestHelper helper;
        final AisleFixture aisle;
        final BlockPos basin;
        final boolean press;
        final int storage;
        /** How many warehouse inputs the aisle has, the return input included. */
        int inputs = 1;

        private Rig(GameTestHelper helper, boolean press) {
            this.helper = helper;
            this.press = press;
            aisle = new AisleFixture(helper, AISLE_Z, RAILS);
            if (!press) {
                helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                        .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
            }
            aisle.build(true);
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
            if (press) {
                aisle.storage(FEED_RACK, new ItemStack(Items.HONEY_BOTTLE, 8));
                aisle.storage(BUCKET_RACK);
            } else {
                aisle.storage(FEED_RACK, DIRT.toStack(DIRT_IN_STOCK));
                aisle.storage(BUCKET_RACK, new ItemStack(Items.BUCKET, BUCKETS_IN_STOCK));
            }
            aisle.storage(EMPTY_RACK);
            storage = press ? 3 : 4;
            aisle.input(RETURN_RACK);
            aisle.output(OUTPUT_RACK);
            aisle.production(STATION_RACK);
            basin = new BlockPos(aisle.inventoryPos(OUTPUT_RACK).getX() + 3, BASE_Y,
                    aisle.inventoryPos(OUTPUT_RACK).getZ() + 1);
            helper.setBlock(basin, AllBlocks.BASIN.getDefaultState());
            if (press) {
                BlockPos operator = basin.above(2);
                helper.setBlock(operator, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
                motor(operator.north(), Direction.SOUTH);
            } else {
                BlockPos operator = basin.above(2);
                helper.setBlock(operator, AllBlocks.MECHANICAL_MIXER.getDefaultState());
                BlockPos cog = operator.east();
                helper.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState());
                motor(cog.above(), Direction.DOWN);
            }
        }

        /** One dirt and 250 mB of water make one mud, at a Basin under a Mixer. */
        static Rig mud(GameTestHelper helper) {
            Rig rig = new Rig(helper, false);
            WarehouseProductionBlockEntity station = rig.station();
            helper.assertTrue(station.setPatternEntry(0, 0, DIRT, 1), "dirt");
            helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "makes mud");
            helper.assertValueEqual(station.editFluidCell(0, 0, WATER_ID, 250),
                    WarehouseProductionBlockEntity.EditOutcome.CHANGED, "from 250 mB of water");
            return rig;
        }

        /** Four honey bottles make one honey block, at a Basin under a Press. */
        static Rig honey(GameTestHelper helper) {
            Rig rig = new Rig(helper, true);
            WarehouseProductionBlockEntity station = rig.station();
            helper.assertTrue(station.setPatternEntry(0, 0, HONEY_BOTTLE, 4), "honey");
            helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, HONEY_BLOCK, 1),
                    "makes a honey block");
            return rig;
        }

        private void motor(BlockPos pos, Direction facing) {
            helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, facing));
            if (helper.getBlockEntity(pos) instanceof CreativeMotorBlockEntity be)
                be.generatedSpeed.setValue(TEST_RPM);
        }

        /** The warehouse is ready, then the selector is placed, the input bound and the water filled in. */
        GameTestSequence start() {
            return helper.startSequence()
                    .thenWaitUntil(() -> aisle.assertReady(storage, inputs, 1))
                    .thenExecute(this::select)
                    .thenExecute(() -> {
                        if (!press) {
                            FluidBayBlockEntity bay = WareworksBlockEntityTypes.FLUID_BAY.getNullable(
                                    helper.getLevel(), helper.absolutePos(aisle.rackPos(BAY_RACK)));
                            helper.assertTrue(bay != null, "the fluid bay");
                            helper.assertValueEqual(bay.fill(new FluidStack(Fluids.WATER, 5000), false), 5000,
                                    "the bay took the water");
                        }
                    })
                    .thenWaitUntil(() -> {
                        if (!press)
                            helper.assertValueEqual(controller().availableFluid(FluidKey.of(Fluids.WATER)), 5000L,
                                    "the water is indexed");
                        helper.assertTrue(basinEntity() != null && helper.getBlockEntity(basin.above(2)) != null,
                                "the machine stands");
                        if (press) {
                            helper.assertTrue(helper.getBlockEntity(basin.above(2)) instanceof
                                    MechanicalPressBlockEntity operator && operator.getSpeed() != 0, "the press turns");
                            helper.assertValueEqual(controller().countOf(HONEY_BOTTLE), 8L, "the honey is indexed");
                        } else {
                            helper.assertValueEqual(controller().countOf(DIRT), (long) DIRT_IN_STOCK, "dirt indexed");
                        }
                    });
        }

        /** Sneak-clicks with the real item: link the station, place against the Basin, bind the return input. */
        private void select() {
            ClickingPlayer player = ClickingPlayer.at(helper, new BlockPos(8, BASE_Y, 1));
            player.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(WareworksBlocks.RECIPE_SELECTOR.asItem(), 1));
            sneakClick(player, aisle.rackPos(STATION_RACK), Direction.UP);
            ItemStack linked = player.getMainHandItem().copy();
            // The return input first: the placement uses up the item.
            sneakClick(player, aisle.rackPos(RETURN_RACK), Direction.UP);
            assertTold(player, WareworksLang.SELECTOR_BOUND_RETURN_INPUT);
            player.setItemInHand(InteractionHand.MAIN_HAND, linked);
            sneakClick(player, basin, Direction.WEST);
            assertTold(player, WareworksLang.SELECTOR_PLACED);
            helper.assertValueEqual(station().selectorMode(), SelectorMode.LOCAL, "a real local selector");
            helper.assertTrue(station().linkedSelector().isPresent(), "the station names its selector");
            helper.assertValueEqual(station().selectorReturnInput(),
                    Optional.of(aisle.absoluteRackPos(RETURN_RACK)), "the return input is bound");
            helper.assertValueEqual(station().selectorCheck(0).map(Enum::name), Optional.of("VALID"),
                    "the machine makes exactly this pattern");
        }

        private void sneakClick(ClickingPlayer player, BlockPos pos, Direction face) {
            BlockPos absolute = helper.absolutePos(pos);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).relative(face, 0.5), face, absolute,
                    false);
            player.setShiftKeyDown(true);
            try {
                player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(),
                        InteractionHand.MAIN_HAND, hit);
            } finally {
                player.setShiftKeyDown(false);
            }
        }

        private void assertTold(ClickingPlayer player, String key) {
            List<String> told = player.toldKeys();
            helper.assertTrue(!told.isEmpty() && told.get(told.size() - 1).equals(WareworksLang.key(key)),
                    "the player was told " + key + ", got " + told);
        }

        WarehouseControllerBlockEntity controller() {
            return aisle.controller();
        }

        WarehouseProductionBlockEntity station() {
            return aisle.productionAt(STATION_RACK);
        }

        BlockPos stationAbsolute() {
            return aisle.absoluteRackPos(STATION_RACK);
        }

        BasinBlockEntity basinEntity() {
            return helper.getBlockEntity(basin) instanceof BasinBlockEntity be ? be : null;
        }

        int basinCount(ItemKey key) {
            int total = 0;
            for (IItemHandler handler : List.of(basinEntity().getInputInventory(), basinEntity().getOutputInventory())) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    if (key.matches(handler.getStackInSlot(slot)))
                        total += handler.getStackInSlot(slot).getCount();
                }
            }
            return total;
        }

        void takeFromBasin(ItemKey key) {
            extractAll(basinEntity().getOutputInventory(), key);
            basinEntity().notifyChangeOfContents();
        }

        void order(ItemKey key, int amount) {
            RequestResult result = controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
            helper.assertTrue(result.isAccepted(), "the order is accepted: " + result);
            helper.assertValueEqual(result.producing(), amount, "all of it is made");
        }

        ProductionOrder<ItemKey, RackPosition> onlyOrder() {
            List<ProductionOrder<ItemKey, RackPosition>> orders = controller().productionOrders();
            helper.assertValueEqual(orders.size(), 1, "exactly one order");
            return orders.getFirst();
        }

        ProductionOrder<ItemKey, RackPosition> orderFor(ItemKey result) {
            List<ProductionOrder<ItemKey, RackPosition>> found = controller().productionOrders().stream()
                    .filter(order -> order.result().equals(result)).toList();
            helper.assertValueEqual(found.size(), 1, "exactly one order for " + result);
            return found.getFirst();
        }

        void assertDelivered(int buckets, int dirt) {
            helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), (long) buckets, "the buckets");
            helper.assertValueEqual(aisle.stationCount(STATION_RACK, DIRT), (long) dirt, "the dirt");
        }

        /** The player's machine takes whatever stands in the station. */
        void takeIngredients() {
            IItemHandler handler = aisle.handlerAt(aisle.rackPos(STATION_RACK));
            for (int slot = 0; slot < handler.getSlots(); slot++)
                handler.extractItem(slot, Integer.MAX_VALUE, false);
        }

        void giveBack(ItemKey key, int count) {
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(RETURN_RACK)), key.toStack(count));
        }

        int returnCount(ItemKey key) {
            IItemHandler handler = aisle.handlerAt(aisle.rackPos(RETURN_RACK));
            int total = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (key.matches(handler.getStackInSlot(slot)))
                    total += handler.getStackInSlot(slot).getCount();
            }
            return total;
        }

        /** {@code expected} of {@code key} are on the shelf at {@code rack} and the return input holds none. */
        void assertStored(RackPosition rack, ItemKey key, int expected) {
            helper.assertValueEqual(returnCount(key), 0, key + " left the return input");
            helper.assertValueEqual(aisle.storedAt(rack, key), (long) expected, key + " is back on its shelf");
        }

        void assertCompletedAtOutput(ItemKey key, int amount) {
            helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, key), (long) amount, "delivered to the output");
            helper.assertTrue(controller().productionOrders().stream()
                    .allMatch(order -> order.state() == ProductionOrderState.COMPLETE), "the order completed");
        }

        /** From this call on, a hold on any tick fails the test and names it. */
        void neverHolds() {
            helper.onEachTick(() -> {
                if (helper.getBlockEntity(aisle.rackPos(STATION_RACK)) instanceof WarehouseProductionBlockEntity found
                        && found.selectorHold().isPresent())
                    helper.fail("the station holds: " + found.selectorHold().get());
            });
        }

        void assertNotHeld() {
            helper.assertValueEqual(station().selectorHold(), Optional.<SelectorHold>empty(), "no hold");
        }

        void assertHeldFor(ItemKey unexpected) {
            helper.assertValueEqual(station().selectorHold().flatMap(SelectorHold::unexpected),
                    Optional.of(unexpected), "the station holds for it");
        }
    }
}
