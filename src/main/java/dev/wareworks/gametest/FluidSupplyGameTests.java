package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.production.FluidSupply;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the <b>fluid supply</b> of a production order (issue #55, ADR-089, slice 2): a pattern that names a fluid is
 * planned as item lines plus a fluid line of whole filled containers, the crane serves the fluid line with one fill trip
 * per container that ends at the production station, and the containers count towards the line like any ingredient.
 * <p>
 * <b>Conservation.</b> A fluid is always in exactly one place: a fluid bay, a bucket in a rack, in the crane's head or in
 * the station's buffer, or — once the test plays the player's machine — gone into that machine, which the test declares
 * in the same step. {@link FluidCensus} is asserted on every tick, {@link ItemCensus} at rest, exactly as
 * {@code FluidRequestGameTests} does, and the declared moves are checked against the game's own container routines.
 * <p>
 * The test is the player's machine here, as in {@code ProductionGameTests}: it takes the delivered buckets and dirt out of
 * the station and hands the empty buckets and the mud back through the warehouse input. Wareworks crafts nothing.
 * <p>
 * Layout ({@code aisle_16x10x7}): right plane a production station at 0 (one dirt and 250 mB of water make one mud), an
 * input at 1, an output at 2; left plane a dirt chest at 1, a chest of empty buckets at 2 and a copper fluid bay of water
 * at 3.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidSupplyGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 3600;

    private static final RackPosition STATION_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_STATION_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition DIRT_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition BUCKET_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);

    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey MUD = ItemKey.of(Items.MUD);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final String WATER_ID = "minecraft:water";

    private FluidSupplyGameTests() {
    }

    // --- the full loop -----------------------------------------------------------------------------------------------

    /**
     * Eight mud from eight dirt and 250 mB of water a run: the order has an item line of 8 and a fluid line of
     * <b>2 containers</b> (8 x 250 = 2000 mB), the crane brings two filled buckets and the dirt to the station, the
     * lines are credited by container, and the order is delivered. The test then plays the player's Item Drain and Basin:
     * the water goes into its machine, the empty buckets and the mud come back through the input, the empties are stored,
     * and the order completes. 3 000 mB stay in the bay, 14 empty buckets on the shelf after the two came back.
     * <p>
     * <b>Teeth:</b> drop the fluid line from {@code ProductionOrder#build} and the station gets dirt and no water; make
     * {@code JobType.FILL} refuse {@code PRODUCTION} and the crane never delivers the bucket.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplydeliversbucketstostation(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(DIRT, 64, BUCKET, 16);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "water is in the bay, a bucket or the machine"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 5000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().availableFluid(WATER), 5000L,
                        "the controller has indexed the water and the bays offer it"))
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 8);
                    helper.assertTrue(result.isAccepted(), "the order is accepted: " + result);
                    helper.assertValueEqual(result.producing(), 8, "all of it is made");
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.lines().size(), 2, "an item line and a fluid line");
                    SupplyLine<ItemKey> water = order.lines().stream().filter(SupplyLine::isFluid).findFirst()
                            .orElseThrow();
                    helper.assertValueEqual(water.key(), WATER_BUCKET, "the fluid line is filled buckets");
                    helper.assertValueEqual(water.required(), 2, "counted in containers: 2000 mB is two buckets");
                    helper.assertValueEqual(water.fluid(), Optional.of(WATER_ID), "and it knows its fluid");
                    // The fluid is spoken for the moment the order exists, so nobody else can be promised it.
                    helper.assertValueEqual(aisle.controller().availableFluid(WATER), 3000L,
                            "2000 mB are owed to the order's fluid line");
                    helper.assertValueEqual(aisle.controller().supplyNeeds().getFirst().fluid(), true,
                            "and the crane is asked for the fluid first");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L, "both buckets arrived");
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, DIRT), 8L, "and the dirt");
                    helper.assertValueEqual(onlyOrder(helper, aisle).state(), ProductionOrderState.DELIVERED,
                            "every line is credited");
                    helper.assertTrue(aisle.controller().reservations().isEmpty(), "nothing stays reserved");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    ItemCensus.fill(helper, items, BUCKET, WATER_BUCKET, 2);
                    ItemCensus.assertEquals(helper, items, "two empties became two filled buckets");
                    helper.assertValueEqual(bay.millibuckets(), 3000, "3000 mB are left in the bay");
                    // The player's Item Drain, pump and Basin: the water leaves the warehouse, the empties return.
                    IItemHandler station = aisle.handlerAt(aisle.rackPos(STATION_RACK));
                    helper.assertValueEqual(extractAll(station, WATER_BUCKET), 2, "the machine took the buckets");
                    helper.assertValueEqual(extractAll(station, DIRT), 8, "and the dirt");
                    FluidCensus.change(fluid, WATER, -2000);
                    ItemCensus.change(items, WATER_BUCKET, -2);
                    ItemCensus.change(items, DIRT, -8);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), BUCKET.toStack(2));
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), MUD.toStack(8));
                    ItemCensus.change(items, BUCKET, 2);
                    ItemCensus.change(items, MUD, 8);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, MUD), 8L, "the mud reached the output");
                    helper.assertValueEqual(onlyOrder(helper, aisle).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                    helper.assertValueEqual(aisle.storedAt(BUCKET_RACK, BUCKET), 16L, "every empty bucket is back on its shelf");
                    ItemCensus.assertEquals(helper, items, "nothing was lost or made up");
                    helper.assertTrue(aisle.controller().reservations().isEmpty(), "nothing stays reserved");
                    helper.assertValueEqual(aisle.controller().availableFluid(WATER), 3000L,
                            "and the finished order owes nothing any more");
                })
                .thenSucceed();
    }

    // --- two in flight -----------------------------------------------------------------------------------------------

    /**
     * Six buckets of water for 24 mud (24 x 250 mB): the crane brings <b>two</b> and then waits, whatever the bay holds,
     * until the player's machine has taken them — a third bucket would stall an Item Drain (1 500 mB) behind a full Basin.
     * The most buckets ever standing in the station or carried by the crane is two; the test then drains them two at a
     * time and the line is served to its six.
     * <p>
     * <b>Teeth:</b> drop the {@code MAX_AT_STATION} room check from {@code JobPlanner#planFluidSupply} and a third bucket
     * is delivered while the first two still stand there.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplyholdstwocontainersinflight(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(DIRT, 64, BUCKET, 16);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicInteger mostInFlight = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        helper.onEachTick(() -> {
            FluidCensus.assertEquals(helper, fluid, "water is in the bay, a bucket or the machine");
            int standing = (int) aisle.stationCount(STATION_RACK, WATER_BUCKET);
            int carried = aisle.dock().heldItems().count(WATER_BUCKET);
            mostInFlight.accumulateAndGet(standing + carried, Math::max);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 10_000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER),
                        10_000L, "the controller has indexed the water"))
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 24);
                    helper.assertTrue(result.isAccepted(), "the order is accepted: " + result);
                    SupplyLine<ItemKey> water = onlyOrder(helper, aisle).lines().stream().filter(SupplyLine::isFluid)
                            .findFirst().orElseThrow();
                    helper.assertValueEqual(water.required(), 6, "six buckets");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L, "two buckets arrived");
                    aisle.assertIdleAndEmpty();
                })
                // Nothing more comes while they stand there, however long the crane is idle.
                .thenIdle(200)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L,
                            "a third bucket waits for the machine");
                    helper.assertValueEqual(onlyOrder(helper, aisle).lines().stream().filter(SupplyLine::isFluid)
                            .findFirst().orElseThrow().delivered(), 2, "two are credited");
                    helper.assertTrue(aisle.controller().supplyNeeds().stream().anyMatch(
                            PlannerInput.SupplyNeed::fluid), "the line still asks");
                    drain(helper, aisle, items, fluid, delivered);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L,
                        "the next two arrive after the machine took the first two"))
                .thenExecute(() -> drain(helper, aisle, items, fluid, delivered))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 2L,
                        "and the last two"))
                .thenExecute(() -> {
                    helper.assertValueEqual(onlyOrder(helper, aisle).lines().stream().filter(SupplyLine::isFluid)
                            .findFirst().orElseThrow().delivered(), 6, "all six are credited");
                    helper.assertTrue(mostInFlight.get() <= FluidSupply.MAX_IN_FLIGHT,
                            "never more than two buckets stood or travelled at once: " + mostInFlight.get());
                    helper.assertValueEqual(mostInFlight.get(), 2, "and two really did");
                    drain(helper, aisle, items, fluid, delivered);
                    helper.assertValueEqual(delivered.get(), 6, "six buckets in all");
                })
                .thenSucceed();
    }

    /** The player's machine empties the buckets standing in the station: the water is gone, empties come back. */
    private static void drain(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> items,
            Map<FluidKey, Long> fluid, AtomicInteger delivered) {
        IItemHandler station = aisle.handlerAt(aisle.rackPos(STATION_RACK));
        int taken = extractAll(station, WATER_BUCKET);
        helper.assertValueEqual(taken, 2, "two buckets stood in the station");
        delivered.addAndGet(taken);
        FluidCensus.change(fluid, WATER, -1000L * taken);
        ItemCensus.change(items, WATER_BUCKET, -taken);
        // The fills happened in the crane's head: two empties became two filled ones, which the machine now holds.
        ItemCensus.change(items, BUCKET, -taken);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), BUCKET.toStack(taken));
        ItemCensus.change(items, BUCKET, taken);
    }

    // --- refusals ----------------------------------------------------------------------------------------------------

    /** Water in the bays and no empty bucket anywhere: the click is refused naming the missing container. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplyneedsemptybucketsrefuses(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 0);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 5000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER), 5000L,
                        "indexed"))
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 4);
                    helper.assertFalse(result.isAccepted(), "no empty bucket, no order");
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK), "so");
                    helper.assertValueEqual(result.refusal(), Optional.of(PlanRefusal.NO_EMPTY_CONTAINER),
                            "and the reason is named");
                    helper.assertValueEqual(result.about(), Optional.of(WATER_BUCKET), "about the container");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0, "no order was made");
                    helper.assertValueEqual(aisle.controller().producibleAmount(MUD), 0L,
                            "and the terminal does not offer what cannot be made");
                })
                .thenSucceed();
    }

    /** A bay with less water than the order needs: the order is made smaller, and the click is told what stopped it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplyclampsToTheWaterInTheBays(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 1000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER), 1000L,
                        "indexed"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().producibleAmount(MUD), 4L,
                            "1000 mB of water make four runs of 250 mB");
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 10);
                    helper.assertTrue(result.isAccepted(), "a smaller order is accepted: " + result);
                    helper.assertValueEqual(result.producing(), 4, "four mud");
                    helper.assertValueEqual(onlyOrder(helper, aisle).lines().stream().filter(SupplyLine::isFluid)
                            .findFirst().orElseThrow().required(), 1, "from one bucket");
                    helper.assertValueEqual(result.refusal(), Optional.of(PlanRefusal.FLUID_MISSING),
                            "and the player is told what stopped the rest");
                })
                .thenSucceed();
    }

    /** The fluid an order owes is not offered to the next click: a second order cannot have the same water. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplytwoordersreservethesamebay(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        aisle.production(SECOND_STATION_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 1000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER), 1000L,
                        "indexed"))
                .thenExecute(() -> {
                    setMudPattern(helper, aisle.productionAt(SECOND_STATION_RACK));
                    RequestResult first = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 4);
                    helper.assertTrue(first.isAccepted(), "the first order takes the bay's one bucket: " + first);
                    helper.assertValueEqual(aisle.controller().availableFluid(WATER), 0L, "nothing is left to promise");
                    RequestResult second = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 4);
                    helper.assertFalse(second.isAccepted() && second.producing() > 0,
                            "a second order is not promised the same water: " + second);
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 1, "one order exists");
                })
                .thenSucceed();
    }

    // --- the order that ends -----------------------------------------------------------------------------------------

    /**
     * Cancelling the order while the crane carries a <b>filled</b> bucket to the station: the bucket is not delivered and
     * not dropped; it is put into a bay of its fluid, drained, and the empty bucket goes back to a shelf. The water is
     * all in the bay again, no filled bucket is anywhere, and nothing stays reserved or owed.
     * <p>
     * <b>Teeth:</b> let {@code fillDestination} answer for a cancelled line and the bucket is delivered to nobody.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplycancelreroutestobay(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(DIRT, 64, BUCKET, 16);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "water is in the bay or in a bucket"));
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 5000))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER), 5000L,
                        "indexed"))
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), MUD, 4);
                    helper.assertTrue(result.isAccepted(), "accepted: " + result);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(WATER_BUCKET) > 0,
                        "the crane carries the filled bucket"))
                .thenExecute(() -> {
                    UUID order = onlyOrder(helper, aisle).id();
                    helper.assertTrue(aisle.controller().cancelProductionOrder(order).isPresent(), "cancelled");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, WATER_BUCKET), 0L,
                            "no bucket was delivered to a cancelled order");
                    helper.assertValueEqual(bay.millibuckets(), 5000, "the water is in the bay again");
                    helper.assertValueEqual(aisle.storedAt(BUCKET_RACK, BUCKET), 16L, "and every empty is on its shelf");
                    helper.assertTrue(aisle.controller().reservations().isEmpty(), "nothing stays reserved");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, items, "every item is where it started");
                    helper.assertValueEqual(aisle.controller().availableFluid(WATER), 5000L, "and nothing is owed");
                })
                .thenSucceed();
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /**
     * An order with a fluid line is saved and loaded again: the line keeps its container, its count, its credit and its
     * fluid, and a save of an order without one has no {@code Fluid} tag at all. A line whose container no longer exists
     * (its mod was removed) takes the whole order with it instead of handing the machine its items without its fluid, and
     * a line that names an unknown fluid with a known container is kept, never a crash.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplyordersurvivesasave(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 0);
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    UUID line = UUID.randomUUID();
                    ProductionOrder<ItemKey, RackPosition> order = fluidOrder(helper, aisle, line, WATER_BUCKET, 3, 1,
                            WATER_ID);
                    helper.assertTrue(controller.addProductionOrderForTest(order), "added");
                    CompoundTag tag = controller.saveWithFullMetadata(registries);
                    ListTag orders = tag.getList("ProductionOrders", Tag.TAG_COMPOUND);
                    helper.assertValueEqual(orders.size(), 1, "one saved order");
                    ListTag lines = orders.getCompound(0).getList("Lines", Tag.TAG_COMPOUND);
                    helper.assertValueEqual(lines.size(), 2, "with its two lines");
                    helper.assertValueEqual(lines.getCompound(0).contains("Fluid"), false, "an item line has no fluid tag");
                    helper.assertValueEqual(lines.getCompound(1).getString("Fluid"), WATER_ID, "the fluid line has it");

                    WarehouseControllerBlockEntity loaded = loadCopy(helper, controller, tag);
                    ProductionOrder<ItemKey, RackPosition> back = loaded.productionOrders().getFirst();
                    SupplyLine<ItemKey> water = back.lines().get(1);
                    helper.assertTrue(water.isFluid(), "the loaded line is a fluid line");
                    helper.assertValueEqual(water.fluid(), Optional.of(WATER_ID), "of the same fluid");
                    helper.assertValueEqual(water.required(), 3, "of three containers");
                    helper.assertValueEqual(water.delivered(), 1, "one of them credited");
                    helper.assertValueEqual(water.key(), WATER_BUCKET, "of the same container");
                    helper.assertValueEqual(back.lines().get(0).fluid(), Optional.empty(), "the item line stays an item line");

                    // An unknown fluid id with a known container loads (and is never a crash).
                    CompoundTag goo = controller.saveWithFullMetadata(registries);
                    goo.getList("ProductionOrders", Tag.TAG_COMPOUND).getCompound(0).getList("Lines", Tag.TAG_COMPOUND)
                            .getCompound(1).putString("Fluid", "removedmod:goo");
                    helper.assertValueEqual(loadCopy(helper, controller, goo).productionOrders().getFirst().lines()
                            .get(1).fluid(), Optional.of("removedmod:goo"), "the unknown fluid id is kept as it is");

                    // A fluid line whose container cannot be read drops the whole order.
                    CompoundTag broken = controller.saveWithFullMetadata(registries);
                    ListTag brokenLines = broken.getList("ProductionOrders", Tag.TAG_COMPOUND).getCompound(0)
                            .getList("Lines", Tag.TAG_COMPOUND);
                    brokenLines.getCompound(1).put("Item", new CompoundTag());
                    helper.assertValueEqual(loadCopy(helper, controller, broken).productionOrders().size(), 0,
                            "an order whose fluid line has lost its container is not kept");
                })
                .thenSucceed();
    }

    /**
     * A fluid line whose container has no recipe (no empty container fills to it) is asked for and never served: no job is
     * ever planned, the order stays open and ends by its timeout instead of crashing the planner.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplyunknowncontainerneverplans(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order = fluidOrder(helper, aisle, UUID.randomUUID(),
                            ItemKey.of(Items.STICK), 1, 0, "removedmod:goo");
                    helper.assertTrue(aisle.controller().addProductionOrderForTest(order), "added");
                    helper.assertTrue(aisle.controller().supplyNeeds().stream().anyMatch(PlannerInput.SupplyNeed::fluid),
                            "the line is asked for");
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    helper.assertTrue(aisle.dock().currentJob().filter(job -> job.type() == JobType.FILL).isEmpty(),
                            "but never a fill trip");
                    helper.assertValueEqual(aisle.stationCount(STATION_RACK, ItemKey.of(Items.STICK)), 0L,
                            "and nothing was delivered");
                    helper.assertTrue(aisle.controller().productionOrders().getFirst().isOpen(), "it waits for its timeout");
                })
                .thenSucceed();
    }

    // --- the plan's own fluid view -------------------------------------------------------------------------------------

    /** The controller's supply names the bucket of a fluid and its real size, and refuses an unknown fluid. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidsupplydescribesthebucket(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 3);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2500))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER), 2500L,
                        "indexed"))
                .thenExecute(() -> {
                    FluidSupply<ItemKey> supply = aisle.controller().fluidSupplyForTest();
                    FluidSupply.Delivery<ItemKey> delivery = supply.delivery(WATER_ID).orElseThrow();
                    helper.assertValueEqual(delivery.container(), WATER_BUCKET, "a water bucket");
                    helper.assertValueEqual(delivery.empty(), BUCKET, "filled from an empty bucket");
                    helper.assertValueEqual(delivery.containerMb(), 1000, "that holds 1000 mB, measured");
                    helper.assertValueEqual(delivery.fluidMb(), 2500L, "of the 2500 mB in the bays");
                    helper.assertValueEqual(supply.delivery("removedmod:goo"), Optional.empty(), "an unknown fluid");
                    helper.assertValueEqual(supply.delivery("minecraft:empty"), Optional.empty(), "and the empty one");
                    helper.assertValueEqual(supply.bufferSlots(), dev.wareworks.config.WareworksConfig
                            .productionBufferSlots(), "the buffer is the configured one");
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static AisleFixture warehouse(GameTestHelper helper, int emptyBuckets) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.storage(DIRT_RACK, DIRT.toStack(64));
        if (emptyBuckets > 0)
            aisle.storage(BUCKET_RACK, new ItemStack(Items.BUCKET, 16));
        else
            aisle.storage(BUCKET_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(STATION_RACK);
        setMudPattern(helper, aisle.productionAt(STATION_RACK));
        return aisle;
    }

    /** One dirt and 250 mB of water make one mud. */
    private static void setMudPattern(GameTestHelper helper, WarehouseProductionBlockEntity station) {
        helper.assertTrue(station.setPatternEntry(0, 0, DIRT, 1), "dirt");
        helper.assertTrue(station.setPatternEntry(0, dev.wareworks.content.station.ProductionPatterns.RESULT_ENTRY,
                MUD, 1), "makes mud");
        helper.assertValueEqual(station.editFluidCell(0, 0, WATER_ID, 250),
                WarehouseProductionBlockEntity.EditOutcome.CHANGED, "from 250 mB of water");
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, AisleFixture aisle) {
        FluidBayBlockEntity be = WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(BAY_RACK)));
        if (be == null) {
            helper.fail("missing fluid bay block entity", aisle.rackPos(BAY_RACK));
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    private static void fill(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int amount) {
        helper.assertValueEqual(bay.fill(new FluidStack(Fluids.WATER, amount), false), amount, "the bay took the water");
        FluidCensus.change(fluid, WATER, amount);
    }

    private static ProductionOrder<ItemKey, RackPosition> onlyOrder(GameTestHelper helper, AisleFixture aisle) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
        helper.assertValueEqual(orders.size(), 1, "exactly one order");
        return orders.getFirst();
    }

    private static int extractAll(IItemHandler handler, ItemKey key) {
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack in = handler.getStackInSlot(slot);
            if (!key.matches(in))
                continue;
            ItemStack out = handler.extractItem(slot, in.getCount(), false);
            taken += out.getCount();
        }
        return taken;
    }

    /** An order of the mud pattern made by hand: an item line of dirt and a fluid line of {@code containers}. */
    private static ProductionOrder<ItemKey, RackPosition> fluidOrder(GameTestHelper helper, AisleFixture aisle,
            UUID line, ItemKey container, int containers, int delivered, String fluid) {
        List<SupplyLine<ItemKey>> lines = List.of(
                new SupplyLine<>(UUID.randomUUID(), DIRT, 4, 0),
                new SupplyLine<>(line, container, containers, delivered, Optional.empty(), Optional.of(fluid)));
        return new ProductionOrder<>(UUID.randomUUID(), STATION_RACK, MUD, 4, lines,
                ProductionOrderState.WAITING_FOR_INGREDIENTS, Long.MAX_VALUE / 2, 0L, 0L, Optional.empty(), 0L, false,
                Optional.empty(), Optional.empty(), 0);
    }

    private static WarehouseControllerBlockEntity loadCopy(GameTestHelper helper, WarehouseControllerBlockEntity live,
            CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof WarehouseControllerBlockEntity controller)) {
            helper.fail("a saved controller must load again as one");
            return live;
        }
        return controller;
    }

}
