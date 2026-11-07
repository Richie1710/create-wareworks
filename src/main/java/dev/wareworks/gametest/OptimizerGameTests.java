package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerAction;
import dev.wareworks.content.station.OptimizerMenu;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseOptimizerBlock;
import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * What the crane does with a Warehouse Optimizer's order (issue #46, ADR-079, {@code docs/warehouse-system.md}): it
 * carries the listed items, with real trips through its handling head, from worse storage locations into strictly
 * better ones, only while it has nothing else to do, never more than the order's trips and never what is spoken for.
 * The block itself, its rows and its view are {@link OptimizerBlockGameTests}' subject.
 * <p>
 * Every test that moves items takes an {@link ItemCensus} on every tick.
 * <ul>
 * <li>{@code optimizermovestheoverflowup} — a chest at priority 0 that took the overflow and a new chest at priority 5:
 * Optimise in the screen moves every item up in two trips, both {@code RELOCATE}, and the order finishes with its
 * entry done and what it moved counted;</li>
 * <li>{@code optimizermovesnothingatequalpriority} — an equal priority is no better place, and neither is a higher one
 * with a worse filter match than a chest dedicated to the item: the order ends at once, no trip set off;</li>
 * <li>{@code optimizerleavesreservedstockalone} — an open request and a stock rule's reserve hold their items back
 * where they lie: only the free surplus goes up, and it stays at that after the order ended;</li>
 * <li>{@code optimizerservesarequestfirst} — a request made while a trip is under way sees the items in the crane's
 * head as stock, and the crane serves it before the next relocation;</li>
 * <li>{@code optimizerhidesnostock} — while the only stack of an item is on its way up, before the pick and after it,
 * the terminal row, a keeper's levels and a stock rule's maximum all see the whole stack, counted once;</li>
 * <li>{@code optimizerstopsatthetripcap} ({@link #CONFIG_BATCH}) — {@code optimizer.tripsPerRequest} bounds an order:
 * two trips, then it is out of trips and nothing more moves;</li>
 * <li>{@code optimizerrunsbesideafullinput} ({@link #CONFIG_BATCH}) — an input whose items fit nowhere: the relocation
 * planned beside it arms the "warehouse full" back-off itself, and the order goes on during that back-off;</li>
 * <li>{@code optimizernevermovesintoafluidbay} — lava buckets go up into a plain chest and never into a better fluid
 * bay dedicated to lava, which would drain them;</li>
 * <li>{@code optimizercancelfinishesthetrip} — cancelled while the crane carries: that trip is put down and counted,
 * and no other one sets off;</li>
 * <li>{@code optimizersavedmidtrip} — dock and controller saved and replaced by their saves while a relocation is under
 * way: the order comes back with its trip, the trip lands and is counted;</li>
 * <li>{@code optimizertwocranesmoveatonce} — two cranes on {@code aisle_pair_16x10x13}: one order keeps both busy at
 * once, each at its own end of the line;</li>
 * <li>{@code optimizerbusycraneendsnoitem} — while crane 2 carries a request, crane 1 alone finds no better place for
 * an item only crane 2 may reach, and that does not end the item: crane 2 moves it once it is free;</li>
 * <li>{@code optimizerendsanitemwhileacraneisbusy} — while crane 2 carries a request, an item that has nowhere better
 * to go anywhere in the warehouse ends all the same.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class OptimizerGameTests {
    /** Config overrides need their own batch, because the tests of one batch run at the same time. */
    static final String CONFIG_BATCH = "wareworksoptimizerconfig";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int LONG_TIMEOUT_TICKS = 3200;
    /** Ticks after an order ended in which nothing may move any more: many dispatch intervals. */
    private static final int SETTLE_TICKS = 80;

    private static final RackPosition LOW = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition HIGH = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition MIDDLE = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(5, 0, Side.LEFT);
    /** The far end of the aisle: a trip there is long enough to reload a warehouse while the crane carries. */
    private static final RackPosition FAR = new RackPosition(6, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OPTIMIZER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);

    private static final int STACK = 64;
    private static final int HIGH_PRIORITY = 5;
    private static final int MIDDLE_PRIORITY = 3;
    /** The overflow of {@link #optimizerMovesTheOverflowUp}: a full trip and a part of one. */
    private static final int OVERFLOW = 100;
    private static final int RESERVE = 16;
    private static final int REQUESTED = 16;
    private static final int MID_ORDER_REQUEST = 32;
    private static final int LAVA_BUCKETS = 3;
    private static final int CAPPED_TRIPS = 2;
    /** A stock rule's maximum above what the warehouse holds, so it has headroom to measure. */
    private static final long IRON_MAXIMUM = 100;
    /** Long enough that the whole order of {@link #optimizerRunsBesideAFullInput} fits into one back-off. */
    private static final int LONG_BACKOFF_TICKS = 1200;

    private OptimizerGameTests() {
    }

    // --- moving up ---------------------------------------------------------------------------------------------------

    /**
     * The situation of the issue: the racks were full, so the overflow went into a chest at priority 0, and a new
     * chest at priority 5 now has room. Optimise, clicked in the optimizer's screen, moves all of it up through the
     * crane's head: one full trip and one of the rest, both {@code RELOCATE} from the chest to the new one. The order
     * finishes with its one entry done, every moved item counted and two trips spent; the view then has nothing left
     * to move.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerMovesTheOverflowUp(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK), IRON.toStack(OVERFLOW - STACK));
        aisle.storage(HIGH);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the optimizer moves the iron"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        int trips = WareworksConfig.optimizerTripsPerRequest();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().optimizerView(List.of(IRON)).get(0).movable(),
                            (long) OVERFLOW, "the screen offers all of it");
                    // The player's way: the item set in a row, then Optimise, both through the menu's payload entry.
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    Player player = playerAt(helper, aisle.rackPos(OPTIMIZER_RACK));
                    OptimizerMenu menu = OptimizerMenu.create(player.containerMenu.containerId + 1,
                            player.getInventory(), optimizer);
                    player.containerMenu = menu;
                    helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.SET_ITEM, 0,
                            Optional.of(IRON)), Optional.of(true), "iron is listed");
                    helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.OPTIMISE, 0,
                            Optional.empty()), Optional.of(true), "Optimise starts an order");
                    player.containerMenu = player.inventoryMenu;
                    helper.assertTrue(order(helper, aisle).isRunning(), "the order runs");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.FINISHED, "the order finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), (long) OVERFLOW, "all of it went up");
                    helper.assertValueEqual(aisle.storedAt(LOW, IRON), 0L, "and the overflow chest is empty");
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.DONE,
                            "the iron is done");
                    helper.assertValueEqual(order.moved(), (long) OVERFLOW, "every item it moved is counted");
                    helper.assertValueEqual(order.entries().get(0).moved(), (long) OVERFLOW, "on its entry too");
                    helper.assertValueEqual(order.tripsLeft(), trips - 2, "two trips were spent");
                    helper.assertTrue(order.inFlight().isEmpty(), "and none is on its way");
                    helper.assertValueEqual(jobs.size(), 2, "two trips set off");
                    for (TransportJob<ItemKey, RackPosition> job : jobs.values()) {
                        helper.assertValueEqual(job.type(), JobType.RELOCATE, "a relocation");
                        helper.assertValueEqual(job.source(), LOW, "out of the overflow chest");
                        helper.assertValueEqual(job.target(), HIGH, "into the better one");
                    }
                    PriorityBreakdown iron = aisle.controller().optimizerView(List.of(IRON)).get(0);
                    helper.assertValueEqual(iron.countAt(HIGH_PRIORITY), (long) OVERFLOW, "the view sees it up");
                    helper.assertValueEqual(iron.movable(), 0L, "with nothing left to move");
                })
                .thenSucceed();
    }

    /**
     * Nothing moves sideways and nothing moves into a worse match. Iron lies at priority 5 beside an empty chest that is
     * also at priority 5; gold lies in a chest at priority 0 that is dedicated to gold, and the only higher place is a
     * plain chest, which ordinary storing would never prefer to the dedicated one. Both entries end at the first
     * dispatch with nothing to move, the order finishes without spending a trip, and the crane never takes a job.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerMovesNothingAtEqualPriority(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(HIGH, IRON.toStack(STACK));
        aisle.storage(MIDDLE);
        aisle.storage(LOW, GOLD.toStack(STACK));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while nothing may move"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        int trips = WareworksConfig.optimizerTripsPerRequest();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    aisle.setStorePriority(HIGH, HIGH_PRIORITY);
                    aisle.setStorePriority(MIDDLE, HIGH_PRIORITY);
                    aisle.setStoreFilter(LOW, GOLD.toStack(1));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.isStoragePrioritised(HIGH), "the iron's chest is prioritised");
                    helper.assertTrue(controller.isStoragePrioritised(MIDDLE), "and so is the empty one");
                    helper.assertTrue(controller.isStorageFiltered(LOW), "the gold's chest is dedicated");
                })
                .thenExecute(() -> {
                    List<PriorityBreakdown> views = aisle.controller().optimizerView(List.of(IRON, GOLD));
                    helper.assertValueEqual(views.get(0).movable(), 0L, "the screen offers no iron");
                    helper.assertValueEqual(views.get(1).movable(), 0L, "and no gold");
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON, GOLD)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order all the same");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(order(helper, aisle).state(),
                        OptimizerOrder.State.FINISHED, "the order ended by itself"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    for (OptimizerOrder.Entry<ItemKey> entry : order.entries())
                        helper.assertValueEqual(entry.state(), OptimizerOrder.EntryState.NOTHING_TO_MOVE,
                                entry.key() + " had nothing to move");
                    helper.assertValueEqual(order.moved(), 0L, "nothing moved");
                    helper.assertValueEqual(order.tripsLeft(), trips, "and no trip was spent");
                    helper.assertTrue(jobs.isEmpty(), "the crane never took a job");
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), (long) STACK, "the iron stayed");
                    helper.assertValueEqual(aisle.storedAt(LOW, GOLD), (long) STACK, "and so did the gold");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- what is spoken for ------------------------------------------------------------------------------------------

    /**
     * 64 diamonds at priority 0. An open request wants 16 of them but cannot be served yet, because its output is full
     * of stone, and a stock keeper's rule reserves another 16. Only the 32 that nobody claims are free to move: the
     * order moves exactly those and ends, and the 32 that are spoken for stay where they lie also after it ended — the
     * request will take them from there, lowest priority first (#47).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerLeavesReservedStockAlone(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, DIAMOND.toStack(STACK));
        aisle.storage(HIGH);
        aisle.output(OUTPUT_RACK);
        aisle.optimizer(OPTIMIZER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        stuff(helper, aisle.outputAt(OUTPUT_RACK));
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the free diamonds move"));
        long free = STACK - REQUESTED - RESERVE;

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_RESERVE, null, RESERVE);
                    RequestResult request = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), DIAMOND, REQUESTED);
                    helper.assertValueEqual(request.granted(), REQUESTED, "the request is accepted");
                    helper.assertValueEqual(controller.optimizerMovable(DIAMOND), free,
                            "only what nobody claims may move");
                    helper.assertValueEqual(controller.optimizerView(List.of(DIAMOND)).get(0).movable(), free,
                            "and the screen offers just that");
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(DIAMOND)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the diamonds");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(order(helper, aisle).isRunning(), "the order ended");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.FINISHED, "it finished");
                    helper.assertValueEqual(order.moved(), free, "it moved the free diamonds");
                    helper.assertValueEqual(aisle.storedAt(HIGH, DIAMOND), free, "up into the better chest");
                    helper.assertValueEqual(aisle.storedAt(LOW, DIAMOND), (long) (REQUESTED + RESERVE),
                            "and what is spoken for stayed where it lies");
                    helper.assertValueEqual(aisle.controller().optimizerView(List.of(DIAMOND)).get(0).movable(), 0L,
                            "the screen offers nothing more, though the free surplus still counts the moved diamonds");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 1, "the request still waits");
                    helper.assertValueEqual(aisle.controller().openRequests().get(0).remaining(), REQUESTED,
                            "for every item it was promised");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * 192 iron at priority 0. While the first trip is under way its 64 items are in the crane's head, outside the stock
     * index, and a request must see them all the same: the warehouse still holds 192. A request for 32 made then is
     * the crane's next job, before any further relocation, and the order finishes afterwards with the rest moved up.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerServesARequestFirst(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK), IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.storage(HIGH);
        aisle.output(OUTPUT_RACK);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while a request comes in mid order"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        long stored = 3L * STACK;
        long moved = stored - MID_ORDER_REQUEST;

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STACK);
                    helper.assertValueEqual(aisle.dock().currentJob().map(TransportJob::type),
                            Optional.of(JobType.RELOCATE), "a relocation is under way");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), stored - STACK, "the head's iron is not indexed");
                    helper.assertValueEqual(controller.availableStock(IRON), stored,
                            "but a request sees every item of it");
                    RequestResult request = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), IRON,
                            MID_ORDER_REQUEST);
                    helper.assertValueEqual(request.granted(), MID_ORDER_REQUEST, "the request is accepted in full");
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(order(helper, aisle).isRunning(), "the order ended");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, IRON), (long) MID_ORDER_REQUEST,
                            "the request was delivered");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    List<TransportJob<ItemKey, RackPosition>> trips = new ArrayList<>(jobs.values());
                    helper.assertTrue(trips.size() >= 3, "a relocation, the request and more relocations: " + trips);
                    helper.assertValueEqual(trips.get(0).type(), JobType.RELOCATE, "the first trip relocates");
                    helper.assertValueEqual(trips.get(1).type(), JobType.RETRIEVE,
                            "the request is served before the next relocation");
                    for (TransportJob<ItemKey, RackPosition> job : trips.subList(2, trips.size()))
                        helper.assertValueEqual(job.type(), JobType.RELOCATE, "then the order goes on");
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.FINISHED, "it finished");
                    helper.assertValueEqual(order.moved(), moved, "with the rest moved");
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), moved, "up into the better chest");
                    helper.assertValueEqual(aisle.storedAt(LOW, IRON), 0L, "and none left below");
                })
                .thenSucceed();
    }

    /**
     * The warehouse's only 64 iron go up in one trip, with a warehouse terminal and a stock rule capping iron at 100.
     * On every tick of the trip, before the pick (the iron still in the overflow chest, its room reserved at the target)
     * and after it (the iron in the crane's head, out of the index), everything that looks at the stock sees 64 iron,
     * counted once: the keeper's levels and the rule tick's (stocked plus inbound, and available), the store headroom
     * under the maximum, and the terminal's one iron row, which also keeps the iron orderable while all of it is in
     * the head.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerHidesNoStock(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK));
        aisle.storage(FAR);
        aisle.terminal(OUTPUT_RACK);
        aisle.optimizer(OPTIMIZER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron goes up"));
        AtomicBoolean beforePick = new AtomicBoolean();
        AtomicBoolean afterPick = new AtomicBoolean();
        helper.onEachTick(() -> {
            Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob()
                    .filter(current -> current.type() == JobType.RELOCATE);
            if (job.isEmpty())
                return;
            boolean picked = job.get().picked();
            if (picked && job.get().heldAmount() < STACK)
                return; // the drop has begun: the iron is in the target chest again, partly
            WarehouseControllerBlockEntity controller = aisle.controller();
            String when = picked ? "with the iron in the head" : "before the pick";
            for (StockLevels levels : List.of(controller.stockLevelsOf(IRON), controller.stockLevelsOf(IRON, 0L))) {
                helper.assertValueEqual(levels.stocked() + levels.inbound(), (long) STACK,
                        "the levels count the trip once " + when);
                helper.assertValueEqual(levels.available(), (long) STACK, "and all of it is available " + when);
            }
            helper.assertValueEqual(controller.storeHeadroom(IRON), IRON_MAXIMUM - STACK,
                    "the maximum still has its room " + when);
            List<TerminalStockEntry> rows = aisle.terminalAt(OUTPUT_RACK).stockSnapshot().stream()
                    .filter(entry -> entry.key().equals(IRON)).toList();
            helper.assertValueEqual(rows.size(), 1, "the terminal has one iron row " + when);
            helper.assertValueEqual(rows.get(0).total(), (long) STACK, "with all of it " + when);
            helper.assertValueEqual(rows.get(0).available(), (long) STACK, "and all of it orderable " + when);
            helper.assertTrue(aisle.terminalAt(OUTPUT_RACK).holdsInStock(IRON), "the iron is still in stock " + when);
            if (picked) {
                helper.assertValueEqual(controller.countOf(IRON), 0L, "the index holds none of it " + when);
                afterPick.set(true);
            } else {
                beforePick.set(true);
            }
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    aisle.setStorePriority(FAR, HIGH_PRIORITY);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.controller().isStoragePrioritised(FAR), "the controller read the priority");
                    helper.assertValueEqual(aisle.controller().storeHeadroom(IRON), IRON_MAXIMUM - STACK,
                            "and the rule");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED,
                            "the order finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertTrue(beforePick.get(), "the trip was looked at before its pick");
                    helper.assertTrue(afterPick.get(), "and while the crane carried all of it");
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) STACK, "the iron went up");
                })
                .thenSucceed();
    }

    // --- bounds ------------------------------------------------------------------------------------------------------

    /**
     * {@code optimizer.tripsPerRequest = 2}: an order for four stacks of iron sets off two trips and is then out of
     * trips, with both trips counted and the rest of the iron where it was; nothing more moves afterwards.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerStopsAtTheTripCap(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.optimizerTripsPerRequest, CAPPED_TRIPS);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK), IRON.toStack(STACK), IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.storage(HIGH);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the order runs out of trips"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        long capped = (long) CAPPED_TRIPS * STACK;

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    helper.assertValueEqual(order(helper, aisle).tripsLeft(), CAPPED_TRIPS, "with the configured trips");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.OUT_OF_TRIPS, "out of trips");
                    helper.assertTrue(order.inFlight().isEmpty(), "with its last trip landed");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.tripsLeft(), 0, "no trip left");
                    helper.assertValueEqual(order.moved(), capped, "both trips counted");
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.STOPPED,
                            "the iron was stopped while open");
                    helper.assertValueEqual(jobs.size(), CAPPED_TRIPS, "exactly two trips set off");
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), capped, "two stacks went up");
                    helper.assertValueEqual(aisle.storedAt(LOW, IRON), 4L * STACK - capped, "the rest stayed");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(ConfigOverrides::restoreAll)
                .thenSucceed();
    }

    /**
     * Both storage chests take only iron, and dirt waits in an input: it fits nowhere, which arms the "warehouse full"
     * back-off ({@code fullBackoffTicks}, here {@value #LONG_BACKOFF_TICKS}). The relocation planned in the same run, beside
     * the full input, arms that back-off itself — a running order would otherwise repeat the arrival scan on every
     * dispatch — and keeps the goggles' reason; and the order's second trip is planned during the back-off, because a
     * relocation is no arrival: the whole order is done long before the back-off ends.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void optimizerRunsBesideAFullInput(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.storage(HIGH);
        aisle.input(INPUT_RACK);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while an input waits"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        AtomicLong firstTrip = new AtomicLong(-1);
        AtomicReference<String> firstTripState = new AtomicReference<>("");
        helper.onEachTick(() -> {
            if (firstTrip.get() >= 0 || !relocating(helper, aisle.dockPos()))
                return;
            WarehouseControllerBlockEntity controller = aisle.controller();
            firstTrip.set(helper.getLevel().getGameTime());
            firstTripState.set("backing off " + controller.isBackingOff(helper.getLevel().getGameTime())
                    + ", reason " + controller.lastPlanReason());
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.fullBackoffTicks, LONG_BACKOFF_TICKS);
                    aisle.setStoreFilter(LOW, IRON.toStack(1));
                    aisle.setStoreFilter(HIGH, IRON.toStack(1));
                    aisle.setStorePriority(HIGH, HIGH_PRIORITY);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.isStorageFiltered(LOW) && controller.isStorageFiltered(HIGH),
                            "both chests take only iron");
                    helper.assertTrue(controller.isStoragePrioritised(HIGH), "the controller read the priority");
                })
                .thenExecute(() -> {
                    // The dirt and the order arrive together, so the very run that finds the dirt a home nowhere
                    // plans the first relocation.
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), new ItemStack(Items.DIRT, STACK));
                    ItemCensus.change(census, ItemKey.of(Items.DIRT), STACK);
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(firstTrip.get() >= 0, "the first trip set off"))
                .thenExecute(() -> helper.assertValueEqual(firstTripState.get(),
                        "backing off true, reason " + Optional.of(NoJobReason.NO_MATCHING_FILTER),
                        "the relocation armed the back-off and kept the input's reason"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED,
                            "the order finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(now - firstTrip.get() < LONG_BACKOFF_TICKS,
                            "the second trip did not wait for the back-off to end: " + (now - firstTrip.get())
                                    + " ticks");
                    helper.assertValueEqual(jobs.size(), 2, "two trips");
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), 2L * STACK, "all the iron went up");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, ItemKey.of(Items.DIRT)), (long) STACK,
                            "and the dirt still waits");
                })
                .thenExecute(ConfigOverrides::restoreAll)
                .thenSucceed();
    }

    /** Restores the config even when a test of {@link #CONFIG_BATCH} fails before its own cleanup. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Lava buckets at priority 0, a plain chest at priority 3 and, better still by both priority and filter, a fluid bay
     * at priority 5 dedicated to lava. A relocation must never go into a bay — it takes a filled container by draining
     * it — so every bucket goes up into the chest, the bay stays empty and not a millibucket changes hands.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerNeverMovesIntoAFluidBay(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.storage(LOW, LAVA_BUCKET.toStack(1), LAVA_BUCKET.toStack(1), LAVA_BUCKET.toStack(1));
        aisle.storage(MIDDLE);
        aisle.optimizer(OPTIMIZER_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        helper.assertTrue(bay.setStoreFilter(LAVA_BUCKET.toStack(1)), "the bay is dedicated to lava");
        helper.assertTrue(bay.setStorePriority(HIGH_PRIORITY), "and the best place there is");
        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, LAVA_BUCKETS);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, (long) LAVA_BUCKETS * FluidType.BUCKET_VOLUME);
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "no bucket is ever emptied by a relocation"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(MIDDLE, MIDDLE_PRIORITY))
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.controller().isStoragePrioritised(MIDDLE), "the chest is prioritised");
                    helper.assertTrue(aisle.controller().isStoragePrioritised(BAY_RACK), "and so is the bay");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    // The bay is no room for the screen at all: no figure and no "?", whatever its snapshot says.
                    List<Integer> rooms = controller.optimizerRooms(List.of(LAVA_BUCKET)).get(LAVA_BUCKET).stream()
                            .map(PriorityBreakdown.Room::priority).toList();
                    helper.assertTrue(rooms.contains(MIDDLE_PRIORITY) && !rooms.contains(HIGH_PRIORITY),
                            "the chest is a room for lava buckets and the bay is none: " + rooms);
                    PriorityBreakdown view = controller.optimizerView(List.of(LAVA_BUCKET)).get(0);
                    helper.assertValueEqual(view.movable(), (long) LAVA_BUCKETS, "the chest takes every bucket");
                    helper.assertFalse(view.movableUnknown(), "and no \"?\" stands for the bay");
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(LAVA_BUCKET)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the lava buckets");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED,
                            "the order finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(MIDDLE, LAVA_BUCKET), (long) LAVA_BUCKETS,
                            "every bucket went up into the chest");
                    helper.assertValueEqual(aisle.storedAt(LOW, LAVA_BUCKET), 0L, "none is left below");
                    helper.assertValueEqual(bayAt(helper, aisle.rackPos(BAY_RACK)).millibuckets(), 0,
                            "and the bay drained none");
                    helper.assertValueEqual(order(helper, aisle).moved(), (long) LAVA_BUCKETS, "all of them counted");
                })
                .thenSucceed();
    }

    // --- cancel and persistence --------------------------------------------------------------------------------------

    /**
     * Cancelled while the crane carries the first of three trips: the order is cancelled at once with its entry
     * stopped, the trip is finished all the same and counted when it lands, and no further trip sets off.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerCancelFinishesTheTrip(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK), IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.storage(HIGH);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while a cancelled trip is finished"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STACK))
                .thenExecute(() -> {
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    helper.assertTrue(optimizer.cancel(), "the order is cancelled");
                    helper.assertFalse(optimizer.cancel(), "once");
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.CANCELLED, "at once");
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.STOPPED,
                            "with its entry stopped");
                    helper.assertValueEqual(order.inFlight().size(), 1, "while its trip is still on its way");
                    helper.assertTrue(aisle.dock().currentJob().isPresent(), "the crane keeps its job");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(HIGH, IRON), (long) STACK, "the trip was finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.moved(), (long) STACK, "and counted");
                    helper.assertTrue(order.inFlight().isEmpty(), "with nothing on its way any more");
                    helper.assertValueEqual(jobs.size(), 1, "no other trip set off");
                    helper.assertValueEqual(aisle.storedAt(LOW, IRON), 2L * STACK, "the rest stayed");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Dock and controller saved while a relocation is under way, then replaced in the world by copies loaded from their
     * saves, the way a reload does it: the controller's save carries the order with its trip, the dock's carries the
     * {@code RELOCATE} job and its items, the reloaded controller adopts the trip and still counts the head's iron as
     * stock while the crane carries it, and the reloaded warehouse lands the trip, counts it and finishes the order. The
     * better chest is at the far end, so the trip outlasts the reload.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerSavedMidTrip(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOW, IRON.toStack(STACK));
        aisle.storage(FAR);
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "across a reload mid trip"));
        BlockPos optimizerPos = aisle.absoluteRackPos(OPTIMIZER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(FAR, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(FAR),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STACK))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = aisle.dock();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    TransportJob<ItemKey, RackPosition> job = dock.currentJob().orElseThrow();
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    helper.assertTrue(controllerTag.contains("OptimizerOrders"), "the controller saves the order");

                    StackerCraneBlockEntity dockCopy = loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class);
                    helper.assertValueEqual(dockCopy.currentJob(), Optional.of(job), "the relocation is saved");
                    helper.assertValueEqual(dockCopy.heldItems().count(IRON), STACK, "with its items");
                    WarehouseControllerBlockEntity controllerCopy = loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class);
                    // A detached copy has no level to find the optimizer's rack position in, so it is compared by what
                    // it writes again.
                    helper.assertValueEqual(controllerCopy.saveWithFullMetadata(registries).get("OptimizerOrders"),
                            controllerTag.get("OptimizerOrders"), "the order reads back unchanged");
                    helper.assertTrue(order(helper, controller, optimizerPos).inFlight().containsKey(job.id()),
                            "with its trip on the way");

                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    helper.assertTrue(dock.isRemoved() && aisle.dock() != dock, "dock block entity replaced");
                    helper.assertTrue(controller.isRemoved() && aisle.controller() != controller,
                            "controller replaced");
                    OptimizerOrder<ItemKey, RackPosition> reloaded = order(helper, aisle);
                    helper.assertTrue(reloaded.isRunning(), "the reloaded order still runs");
                    helper.assertTrue(reloaded.inFlight().containsKey(job.id()), "and knows its trip");
                })
                // Asked while the crane still carries and once the reloaded controller adopted its job (the trip's
                // room is reserved again): after the drop the iron would be indexed and the figure right anyway.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.dock().heldItems().count(IRON), STACK, "the crane still carries");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.reservations().reservedCapacityFor(IRON), (long) STACK,
                            "the reloaded controller adopted the trip");
                    helper.assertValueEqual(controller.countOf(IRON), 0L, "the head's iron is not indexed");
                    helper.assertValueEqual(controller.availableStock(IRON), (long) STACK,
                            "but the reloaded warehouse still sees it as stock");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED,
                            "the order finished after the reload");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.moved(), (long) STACK, "the trip that crossed the reload counts");
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.DONE,
                            "the iron is done");
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) STACK, "and lies up");
                })
                .thenSucceed();
    }

    // --- two cranes --------------------------------------------------------------------------------------------------

    /**
     * Two cranes on one line, an overflow chest and a better chest at each end. One order keeps both cranes busy in the
     * same tick, each at the end nearest to it. Both trips land and the order finishes with both counted. Each crane's
     * nearest source is a different chest whatever the ledger holds, so this does not show that a source is never
     * promised twice: {@code ReservationLedgerTest} does.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void optimizerTwoCranesMoveAtOnce(GameTestHelper helper) {
        Line line = new Line(6, 13);
        build(helper, line);
        BlockPos westLow = storage(helper, line, 1, Side.LEFT, IRON.toStack(STACK));
        BlockPos westHigh = storage(helper, line, 2, Side.LEFT);
        BlockPos eastLow = storage(helper, line, 12, Side.RIGHT, IRON.toStack(STACK));
        BlockPos eastHigh = storage(helper, line, 13, Side.RIGHT);
        BlockPos optimizer = optimizer(helper, line, 7, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while two cranes relocate"));
        AtomicBoolean together = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (relocating(helper, line.dock()) && relocating(helper, line.memberDock()))
                together.set(true);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, line))
                .thenExecute(() -> {
                    setPriority(helper, line.rack(2, Side.LEFT), HIGH_PRIORITY);
                    setPriority(helper, line.rack(13, Side.RIGHT), HIGH_PRIORITY);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper, line);
                    helper.assertValueEqual(controller.optimizerView(List.of(IRON)).get(0).movable(), 2L * STACK,
                            "both better chests are known");
                })
                .thenExecute(() -> helper.assertValueEqual(optimizerAt(helper, optimizer).optimise(List.of(IRON)),
                        Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, controller(helper, line), helper.absolutePos(optimizer))
                            .state(), OptimizerOrder.State.FINISHED, "the order finished");
                    assertIdle(helper, line);
                })
                .thenExecute(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, controller(helper, line),
                            helper.absolutePos(optimizer));
                    helper.assertValueEqual(order.moved(), 2L * STACK, "both trips counted");
                    helper.assertValueEqual(order.tripsLeft(), WareworksConfig.optimizerTripsPerRequest() - 2,
                            "two trips spent");
                    helper.assertValueEqual(countIn(helper, westLow, IRON) + countIn(helper, eastLow, IRON), 0L,
                            "both overflow chests are empty");
                    helper.assertValueEqual(countIn(helper, westHigh, IRON) + countIn(helper, eastHigh, IRON),
                            2L * STACK, "and their iron lies up");
                    helper.assertTrue(!severalCranes() || together.get(), "both cranes relocated in the same tick");
                })
                .thenSucceed();
    }

    /**
     * Crane 2 carries a request when the order starts; the item's chests stand beside crane 2's dock, where crane 1
     * may never serve. Crane 1 alone finds no better place for it, which is no proof that nothing fits while another
     * crane is busy, so the item stays open; once crane 2 is free it moves the iron up.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void optimizerBusyCraneEndsNoItem(GameTestHelper helper) {
        Line line = new Line(6, 13);
        build(helper, line);
        int dockX = line.rails() + 1;
        BlockPos low = storage(helper, line, dockX, Side.LEFT, IRON.toStack(STACK));
        BlockPos high = storage(helper, line, dockX, Side.RIGHT);
        storage(helper, line, 10, Side.LEFT, DIAMOND.toStack(REQUESTED));
        BlockPos output = line.rack(12, Side.RIGHT);
        helper.setBlock(output, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Line.away(Side.RIGHT).getOpposite()));
        BlockPos optimizer = optimizer(helper, line, 7, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while one crane is busy"));
        if (!severalCranes()) {
            // With one crane the far dock is a wall and its rack positions belong to no aisle: nothing to test here.
            helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(controller(helper, line).status(),
                    ControllerStatus.READY, "the warehouse is ready")).thenSucceed();
            return;
        }
        AtomicLong startedAt = new AtomicLong(-1);
        AtomicLong freeAt = new AtomicLong(-1);
        helper.onEachTick(() -> {
            if (startedAt.get() < 0 || freeAt.get() >= 0)
                return;
            // Crane 2 may take the relocation in the very dispatch that follows its request, so "free" is "no longer
            // serving the request" rather than "no job".
            if (crane(helper, line.memberDock()).currentJob().map(job -> job.type() != JobType.RETRIEVE)
                    .orElse(true)) {
                freeAt.set(helper.getTick());
                return;
            }
            // While crane 2 is busy the iron stays open, however often crane 1 plans.
            OptimizerOrder<ItemKey, RackPosition> order = order(helper, controller(helper, line),
                    helper.absolutePos(optimizer));
            helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.OPEN,
                    "the iron stays open while crane 2 is busy");
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, line))
                .thenExecute(() -> setPriority(helper, line.rack(dockX, Side.RIGHT), HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper, line).optimizerView(List.of(IRON))
                        .get(0).movable(), (long) STACK, "the better chest is known"))
                .thenExecute(() -> helper.assertTrue(controller(helper, line).request(helper.absolutePos(output),
                        DIAMOND, REQUESTED).isAccepted(), "the diamonds are requested"))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, line.memberDock()).currentJob()
                        .map(TransportJob::type), Optional.of(JobType.RETRIEVE), "crane 2 serves the request"))
                .thenExecute(() -> {
                    helper.assertTrue(crane(helper, line.dock()).currentJob().isEmpty(), "crane 1 is free");
                    helper.assertValueEqual(optimizerAt(helper, optimizer).optimise(List.of(IRON)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the iron");
                    startedAt.set(helper.getTick());
                })
                .thenWaitUntil(() -> helper.assertTrue(freeAt.get() >= 0, "crane 2 finished the request"))
                .thenExecute(() -> helper.assertTrue(freeAt.get() - startedAt.get()
                        > 2L * WareworksConfig.dispatchIntervalTicks(), "crane 1 planned while crane 2 was busy"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, controller(helper, line), helper.absolutePos(optimizer))
                            .state(), OptimizerOrder.State.FINISHED, "the order finished");
                    assertIdle(helper, line);
                })
                .thenExecute(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, controller(helper, line),
                            helper.absolutePos(optimizer));
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.DONE,
                            "the iron was moved, not given up");
                    helper.assertValueEqual(order.moved(), (long) STACK, "all of it");
                    helper.assertValueEqual(countIn(helper, high, IRON), (long) STACK, "up into the better chest");
                    helper.assertValueEqual(countIn(helper, low, IRON), 0L, "and none left below");
                    helper.assertValueEqual(stationCount(helper, output, DIAMOND), (long) REQUESTED,
                            "the request was delivered");
                })
                .thenSucceed();
    }

    /**
     * Crane 2 carries a request when the order starts. Gold lies at priority 5 beside crane 1, and no place anywhere in
     * the warehouse is better for it. Crane 1's run finds nowhere for it while crane 2 is busy; asked once more with the
     * reach every crane has when none holds a job, it finds nowhere either, so the gold ends at once with nothing to
     * move — while crane 2 is still on its way, not only once it is free.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void optimizerEndsAnItemWhileACraneIsBusy(GameTestHelper helper) {
        Line line = new Line(6, 13);
        build(helper, line);
        BlockPos gold = storage(helper, line, 2, Side.LEFT, GOLD.toStack(STACK));
        storage(helper, line, 10, Side.LEFT, DIAMOND.toStack(REQUESTED));
        BlockPos output = line.rack(12, Side.RIGHT);
        helper.setBlock(output, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Line.away(Side.RIGHT).getOpposite()));
        BlockPos optimizer = optimizer(helper, line, 7, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while one crane is busy"));
        if (!severalCranes()) {
            helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(controller(helper, line).status(),
                    ControllerStatus.READY, "the warehouse is ready")).thenSucceed();
            return;
        }
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<Boolean> endedWhileBusy = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (!started.get() || endedWhileBusy.get() != null)
                return;
            if (!order(helper, controller(helper, line), helper.absolutePos(optimizer)).isRunning())
                endedWhileBusy.set(crane(helper, line.memberDock()).currentJob().map(TransportJob::type)
                        .equals(Optional.of(JobType.RETRIEVE)));
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, line))
                .thenExecute(() -> setPriority(helper, line.rack(2, Side.LEFT), HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper, line).optimizerView(List.of(GOLD))
                        .get(0).countAt(HIGH_PRIORITY), (long) STACK, "the gold is counted at its priority"))
                .thenExecute(() -> helper.assertTrue(controller(helper, line).request(helper.absolutePos(output),
                        DIAMOND, REQUESTED).isAccepted(), "the diamonds are requested"))
                .thenWaitUntil(() -> helper.assertValueEqual(crane(helper, line.memberDock()).currentJob()
                        .map(TransportJob::type), Optional.of(JobType.RETRIEVE), "crane 2 serves the request"))
                .thenExecute(() -> {
                    helper.assertTrue(crane(helper, line.dock()).currentJob().isEmpty(), "crane 1 is free");
                    helper.assertValueEqual(optimizerAt(helper, optimizer).optimise(List.of(GOLD)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the gold");
                    started.set(true);
                })
                .thenWaitUntil(() -> helper.assertTrue(endedWhileBusy.get() != null, "the order ended"))
                .thenExecute(() -> {
                    helper.assertTrue(endedWhileBusy.get(), "while crane 2 was still serving the request");
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, controller(helper, line),
                            helper.absolutePos(optimizer));
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.FINISHED, "the order finished");
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.NOTHING_TO_MOVE,
                            "the gold had nowhere better to go");
                    helper.assertValueEqual(order.tripsLeft(), WareworksConfig.optimizerTripsPerRequest(),
                            "and no trip was spent");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(stationCount(helper, output, DIAMOND), (long) REQUESTED,
                            "the request was delivered");
                    assertIdle(helper, line);
                })
                .thenExecute(() -> helper.assertValueEqual(countIn(helper, gold, GOLD), (long) STACK,
                        "the gold stayed where it was"))
                .thenSucceed();
    }

    // --- one aisle ---------------------------------------------------------------------------------------------------

    /** The order of the aisle's optimizer, which must exist. */
    private static OptimizerOrder<ItemKey, RackPosition> order(GameTestHelper helper, AisleFixture aisle) {
        return order(helper, aisle.controller(), aisle.absoluteRackPos(OPTIMIZER_RACK));
    }

    private static OptimizerOrder<ItemKey, RackPosition> order(GameTestHelper helper,
            WarehouseControllerBlockEntity controller, BlockPos optimizer) {
        Optional<OptimizerOrder<ItemKey, RackPosition>> order = controller.optimizerOrder(optimizer);
        if (order.isEmpty()) {
            helper.fail("the warehouse keeps no order of the optimizer at " + optimizer);
            throw new AssertionError("unreachable");
        }
        return order.get();
    }

    /** Every job the aisle's crane holds, recorded once per job id in the order they appear, on every tick. */
    private static Map<UUID, TransportJob<ItemKey, RackPosition>> recordJobs(GameTestHelper helper,
            AisleFixture aisle) {
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = new LinkedHashMap<>();
        helper.onEachTick(() -> aisle.dock().currentJob().ifPresent(job -> jobs.putIfAbsent(job.id(), job)));
        return jobs;
    }

    /** Fills every slot of a station's buffer with stone, so nothing can be delivered to it. */
    private static void stuff(GameTestHelper helper, WarehouseDeliveryStationBlockEntity station) {
        for (int slot = 0; slot < station.bufferSlots(); slot++)
            station.insert(STONE.toStack(STACK), false);
        helper.assertFalse(station.hasEmptySlot(), "the station is full");
        helper.assertValueEqual(station.insert(DIAMOND.toStack(1), true).getCount(), 1, "and takes no diamond");
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, BlockPos pos) {
        FluidBayBlockEntity be = WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing fluid bay block entity", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }

    // --- two cranes: building ----------------------------------------------------------------------------------------

    /**
     * One straight warehouse along +X at {@code z}: controller at x = 0, its dock at x = 1, rails at x = 2..(1 +
     * rails), and a second dock at x = rails + 2 facing back, which joins as crane 2 (issue #14).
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

        /** The rack position beside position {@code x}: north on the left, south on the right. */
        BlockPos rack(int x, Side side) {
            return new BlockPos(1 + x, BASE_Y, z).relative(away(side));
        }

        static Direction away(Side side) {
            return side == Side.LEFT ? Direction.NORTH : Direction.SOUTH;
        }
    }

    private static void build(GameTestHelper helper, Line line) {
        for (BlockPos dock : line.docks()) {
            BlockPos motor = new BlockPos(dock.getX(), FLOOR_Y, dock.getZ());
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.UP));
            helper.setBlock(dock, WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(
                    HorizontalKineticBlock.HORIZONTAL_FACING, dock.equals(line.dock()) ? Direction.EAST
                            : Direction.WEST));
            CreativeMotorBlockEntity be = com.simibubi.create.AllBlockEntityTypes.MOTOR
                    .getNullable(helper.getLevel(), helper.absolutePos(motor));
            if (be == null)
                helper.fail("missing creative motor", motor);
            else
                be.generatedSpeed.setValue(TEST_RPM);
        }
        for (int x = 1; x <= line.rails(); x++)
            helper.setBlock(new BlockPos(1 + x, BASE_Y, line.z()), WarehouseRailBlock.along(Direction.Axis.X));
        helper.setBlock(line.controller(), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
    }

    /** A chest with {@code contents} behind an interface at {@code (x, side)} of {@code line}; the chest position. */
    private static BlockPos storage(GameTestHelper helper, Line line, int x, Side side, ItemStack... contents) {
        BlockPos rack = line.rack(x, side);
        BlockPos chest = rack.relative(Line.away(side));
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(chest), null);
        if (handler == null) {
            helper.fail("no item handler", chest);
            return chest;
        }
        for (ItemStack stack : contents) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            helper.assertTrue(rest.isEmpty(), "the chest at " + chest + " rejected " + rest);
        }
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Line.away(side)));
        return chest;
    }

    /** A Warehouse Optimizer at {@code (x, side)} of {@code line}, its panel looking into the aisle. */
    private static BlockPos optimizer(GameTestHelper helper, Line line, int x, Side side) {
        BlockPos rack = line.rack(x, side);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_OPTIMIZER.getDefaultState()
                .setValue(WarehouseOptimizerBlock.FACING, Line.away(side).getOpposite()));
        return rack;
    }

    private static void setPriority(GameTestHelper helper, BlockPos rack, int priority) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rack)) instanceof WarehouseInterfaceBlockEntity be)) {
            helper.fail("missing warehouse interface", rack);
            return;
        }
        be.setStorePriority(priority);
        helper.assertValueEqual(be.storePriority(), priority, "the interface took the priority");
    }

    // --- two cranes: reading -----------------------------------------------------------------------------------------

    /** Whether the server lets a warehouse have several cranes at all; {@code 1} is the off switch. */
    private static boolean severalCranes() {
        return WareworksConfig.maxCranesPerNetwork() > 1;
    }

    /** The warehouse is ready to plan: members recorded and read, and — with several cranes allowed — both joined. */
    private static void assertReady(GameTestHelper helper, Line line) {
        WarehouseControllerBlockEntity controller = controller(helper, line);
        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller ready");
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "storage read");
        if (severalCranes()) {
            helper.assertValueEqual(controller.craneCount(), 2, "two cranes");
            helper.assertTrue(crane(helper, line.memberDock()).isControllerLinked(), "crane 2 is linked");
        }
    }

    private static void assertIdle(GameTestHelper helper, Line line) {
        for (BlockPos dock : line.docks()) {
            StackerCraneBlockEntity crane = crane(helper, dock);
            helper.assertTrue(crane.currentJob().isEmpty(), "crane " + crane.craneNumber() + " has no job");
            helper.assertTrue(crane.heldItems().isEmpty(), "and holds nothing");
        }
    }

    /** Whether the crane of the dock at a test-relative position holds a {@code RELOCATE} job. */
    private static boolean relocating(GameTestHelper helper, BlockPos dock) {
        return crane(helper, dock).currentJob().map(job -> job.type() == JobType.RELOCATE).orElse(false);
    }

    private static WarehouseControllerBlockEntity controller(GameTestHelper helper, Line line) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(line.controller()));
        if (be == null) {
            helper.fail("missing warehouse controller", line.controller());
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

    private static WarehouseOptimizerBlockEntity optimizerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseOptimizerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OPTIMIZER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing warehouse optimizer", pos);
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
