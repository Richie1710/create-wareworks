package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerAction;
import dev.wareworks.content.station.OptimizerMenu;
import dev.wareworks.content.station.OptimizerScreenState;
import dev.wareworks.content.station.WarehouseOptimizerBlock;
import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.RelocationReason;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
 * The Warehouse Optimizer's merge mode and the home rule it shares with storing (issue #57, ADR-088). Where an item
 * belongs: a place whose store filter names it, else the place holding the most of it on the highest priority that
 * holds it at all, a tie to the lower index. Merge mode carries the other places' stock into that home with real trips
 * through the crane's head, smallest holder first, so every trip empties a bay; the {@link OptimizerGameTests} keep the
 * optimise mode.
 * <ul>
 * <li>{@code mergehomeisthefilterplace}, {@code mergehomeisthefullestplace}, {@code mergehomeprefersthehighestpriority},
 * {@code mergehometiegoestoindexorder} — the home rule, seen by where a merge carries things;</li>
 * <li>{@code mergestoringfollowsthehome} — the same rule when the crane stores a new batch;</li>
 * <li>{@code mergeemptiessourcesandfreesbays} — three places become one, with the menu's own action, an exact census,
 * the places number of the view and the freed bays taking another item;</li>
 * <li>{@code mergeskipsasourceabovethefilterhome} — a source with a higher priority than a filter-named home stays;</li>
 * <li>{@code mergenevertouchesafluidbay} — a fluid bay is neither source nor home, not a millibucket moves;</li>
 * <li>{@code mergestopsatthetripcap} ({@link #CONFIG_BATCH}) — {@code optimizer.tripsPerRequest} bounds a merge;</li>
 * <li>{@code mergesavedmidtrip} — a merge order saved and replaced by its save keeps its mode and lands its trip;</li>
 * <li>{@code mergetwocranesnopingpong} — two cranes: every trip goes to the one home, none leaves it, nothing moves
 * twice;</li>
 * <li>{@code mergescreenstateroundtrips} — the screen payload carries the places and the order's mode.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class OptimizerMergeGameTests {
    /** Config overrides need their own batch, because the tests of one batch run at the same time. */
    static final String CONFIG_BATCH = "wareworksoptimizermergeconfig";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1800;
    private static final int LONG_TIMEOUT_TICKS = 3600;
    private static final int SETTLE_TICKS = 80;

    private static final RackPosition ONE = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition TWO = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition THREE = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(5, 0, Side.LEFT);
    private static final RackPosition SIX = new RackPosition(6, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OPTIMIZER_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);

    private static final int STACK = 64;
    private static final int CAPPED_TRIPS = 2;

    private OptimizerMergeGameTests() {
    }

    // --- the home rule -----------------------------------------------------------------------------------------------

    /** A place whose filter names the item is its home, though another place holds far more of it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeHomeIsTheFilterPlace(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(10));
        aisle.storage(TWO, IRON.toStack(STACK), IRON.toStack(26));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is merged"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStoreFilter(ONE, IRON.toStack(1)))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStorageFiltered(ONE),
                        "the controller read the filter"))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 100L, "all of it lies in the filter place");
                    helper.assertValueEqual(aisle.storedAt(TWO, IRON), 0L, "and the fuller place was emptied");
                })
                .thenSucceed();
    }

    /** Without a filter the place holding the most is home, and the smaller one is carried into it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeHomeIsTheFullestPlace(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(10));
        aisle.storage(SIX, IRON.toStack(STACK), IRON.toStack(26));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is merged"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(SIX, IRON), 100L, "the fullest place is the home, near or far");
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 0L, "the nearer place was emptied");
                })
                .thenSucceed();
    }

    /** The highest priority that holds the item is home, even when a lower place holds far more. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeHomePrefersTheHighestPriority(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(STACK), IRON.toStack(26));
        aisle.storage(THREE, IRON.toStack(5));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is merged"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> aisle.setStorePriority(THREE, 3))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(THREE),
                        "the controller read the priority"))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(THREE, IRON), 95L, "the prioritised place is home");
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 0L, "the larger lower place was emptied");
                })
                .thenSucceed();
    }

    /** Equal counts at one priority: the lower index is home, so the home does not wander with the crane. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeHomeTieGoesToIndexOrder(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(30));
        aisle.storage(SIX, IRON.toStack(30));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is merged"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    List<RackPosition> order = aisle.controller().storageLocations().stream()
                            .map(dev.wareworks.core.warehouse.LocationRecord::position).toList();
                    RackPosition home = order.indexOf(ONE) < order.indexOf(SIX) ? ONE : SIX;
                    helper.assertValueEqual(aisle.storedAt(home, IRON), 60L, "the lower index is the home");
                })
                .thenSucceed();
    }

    /**
     * Storing follows the same rule: iron arrives while a near place holds a little and a far one holds more, and goes
     * to the far one, where the crane would have taken it to the nearer one before the home rule.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeStoringFollowsTheHome(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(10));
        aisle.storage(SIX, IRON.toStack(40));
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is stored"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    ItemCensus.change(census, IRON, 20);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(20));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(SIX, IRON), 60L, "the batch joined the fuller place");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(ONE, IRON), 10L, "and not the nearer one"))
                .thenSucceed();
    }

    // --- merging -----------------------------------------------------------------------------------------------------

    /**
     * 20, 30 and 50 iron in three places: Merge, clicked in the screen, empties the two smaller ones into the third in
     * two trips, both {@code RELOCATE} into the one home. The places number goes from 3 to 1, the entry ends done with
     * "all in one place" as its reason, and the two freed places then take another item.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeEmptiesSourcesAndFreesBays(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(20));
        aisle.storage(TWO, IRON.toStack(30));
        aisle.storage(THREE, IRON.toStack(STACK));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the iron is merged"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);
        int trips = WareworksConfig.optimizerTripsPerRequest();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().optimizerPlaces(List.of(IRON)), List.of(3),
                            "the view shows the split before the press");
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    Player player = playerAt(helper, aisle.rackPos(OPTIMIZER_RACK));
                    OptimizerMenu menu = OptimizerMenu.create(player.containerMenu.containerId + 1,
                            player.getInventory(), optimizer);
                    player.containerMenu = menu;
                    helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.SET_ITEM, 0,
                            Optional.of(IRON)), Optional.of(true), "iron is listed");
                    helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.MERGE, 0,
                            Optional.empty()), Optional.of(true), "Merge starts an order");
                    player.containerMenu = player.inventoryMenu;
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.mode(), OptimizerOrder.Mode.MERGE, "a merge order");
                    helper.assertTrue(order.isRunning(), "that runs");
                })
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(THREE, IRON), 114L, "everything lies in the home");
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 0L, "the smallest place is empty");
                    helper.assertValueEqual(aisle.storedAt(TWO, IRON), 0L, "so is the next");
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.DONE,
                            "the iron is done");
                    helper.assertValueEqual(order.entries().get(0).endReason(),
                            Optional.of(RelocationReason.ALREADY_ONE_PLACE), "because it lies in one place");
                    helper.assertValueEqual(order.moved(), 50L, "50 items were carried");
                    helper.assertValueEqual(order.tripsLeft(), trips - 2, "in two trips");
                    helper.assertValueEqual(jobs.size(), 2, "two trips set off");
                    for (TransportJob<ItemKey, RackPosition> job : jobs.values()) {
                        helper.assertValueEqual(job.type(), JobType.RELOCATE, "a relocation");
                        helper.assertValueEqual(job.target().x(), 3, "into the one home");
                    }
                    helper.assertValueEqual(aisle.controller().optimizerPlaces(List.of(IRON)), List.of(1),
                            "one place holds it now");
                    PriorityBreakdown view = aisle.controller().optimizerView(List.of(IRON)).get(0);
                    helper.assertValueEqual(view.total(), 114L, "and the view counts all of it");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    aisle.assertIdleAndEmpty();
                    // The freed places are free for another item: a chest that was emptied takes gold as any other.
                    ItemCensus.change(census, GOLD, 8);
                    ItemHandlerHelper.insertItem(aisle.handlerAt(aisle.inventoryPos(ONE)), GOLD.toStack(8), false);
                    helper.assertValueEqual(aisle.storedAt(ONE, GOLD), 8L, "the freed place takes another item");
                })
                .thenSucceed();
    }

    /**
     * A filter names the home, and the only other holder lies at a higher priority than it: that source is never moved
     * down, the order ends at once with "other places rank above its home" and no trip sets off.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeSkipsASourceAboveTheFilterHome(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(10));
        aisle.storage(THREE, IRON.toStack(40));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while nothing may move"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(ONE, IRON.toStack(1));
                    aisle.setStorePriority(THREE, 4);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.controller().isStorageFiltered(ONE), "the filter is read");
                    helper.assertTrue(aisle.controller().isStoragePrioritised(THREE), "and the priority");
                })
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> helper.assertValueEqual(order(helper, aisle).state(),
                        OptimizerOrder.State.FINISHED, "the order ended by itself"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.entries().get(0).state(), OptimizerOrder.EntryState.NOTHING_TO_MOVE,
                            "nothing was merged");
                    helper.assertValueEqual(order.entries().get(0).endReason(),
                            Optional.of(RelocationReason.HOME_WORSE), "because the home ranks below the source");
                    helper.assertTrue(jobs.isEmpty(), "the crane never took a job");
                    helper.assertValueEqual(aisle.storedAt(THREE, IRON), 40L, "the higher place keeps its iron");
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 10L, "and so does the filter place");
                })
                .thenSucceed();
    }

    /**
     * Lava buckets in two plain chests and a fluid bay dedicated to lava: the bay is neither source nor home, so the
     * buckets are gathered between the chests and not a millibucket changes hands.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeNeverTouchesAFluidBay(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.storage(ONE, LAVA_BUCKET.toStack(1));
        aisle.storage(THREE, LAVA_BUCKET.toStack(1), LAVA_BUCKET.toStack(1));
        aisle.optimizer(OPTIMIZER_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        helper.assertTrue(bay.setStoreFilter(LAVA_BUCKET.toStack(1)), "the bay is dedicated to lava");
        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, 3);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, 3L * FluidType.BUCKET_VOLUME);
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "no bucket is ever emptied by a merge"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().optimizerPlaces(List.of(LAVA_BUCKET)), List.of(2),
                            "the bay is no place of the view");
                    merge(helper, aisle, LAVA_BUCKET);
                })
                .thenWaitUntil(() -> finished(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(THREE, LAVA_BUCKET), 3L, "the buckets were gathered");
                    helper.assertValueEqual(aisle.storedAt(ONE, LAVA_BUCKET), 0L, "out of the other chest");
                    helper.assertValueEqual(bayAt(helper, aisle.rackPos(BAY_RACK)).millibuckets(), 0,
                            "and the bay drained none");
                })
                .thenSucceed();
    }

    /** {@code optimizer.tripsPerRequest = 2}: a merge of four small places sets off two trips and is out of trips. */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeStopsAtTheTripCap(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.optimizerTripsPerRequest, CAPPED_TRIPS);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(STACK));
        aisle.storage(TWO, IRON.toStack(STACK));
        aisle.storage(THREE, IRON.toStack(STACK));
        aisle.storage(SIX, IRON.toStack(STACK), IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the merge runs out of trips"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = recordJobs(helper, aisle);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(4, 0, 0))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.state(), OptimizerOrder.State.OUT_OF_TRIPS, "out of trips");
                    helper.assertTrue(order.inFlight().isEmpty(), "with its last trip landed");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    OptimizerOrder<ItemKey, RackPosition> order = order(helper, aisle);
                    helper.assertValueEqual(order.moved(), 2L * STACK, "two trips counted");
                    helper.assertValueEqual(jobs.size(), CAPPED_TRIPS, "exactly two trips set off");
                    helper.assertValueEqual(aisle.storedAt(SIX, IRON), 5L * STACK, "the home took two stacks");
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
     * Dock and controller saved while a merge trip is under way and replaced by copies loaded from their saves: the
     * controller's save carries the order with its mode, the reloaded order is still a merge, its trip lands, and the
     * order finishes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void mergeSavedMidTrip(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(ONE, IRON.toStack(STACK));
        aisle.storage(SIX, IRON.toStack(STACK), IRON.toStack(STACK));
        aisle.optimizer(OPTIMIZER_RACK);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "across a reload mid trip"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> merge(helper, aisle, IRON))
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STACK))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = aisle.dock();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    TransportJob<ItemKey, RackPosition> job = dock.currentJob().orElseThrow();
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    helper.assertTrue(controllerTag.toString().contains("MERGE"), "the controller saves the mode");
                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    OptimizerOrder<ItemKey, RackPosition> reloaded = order(helper, aisle);
                    helper.assertValueEqual(reloaded.mode(), OptimizerOrder.Mode.MERGE, "still a merge order");
                    helper.assertTrue(reloaded.inFlight().containsKey(job.id()), "with its trip");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED,
                            "the order finished after the reload");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(SIX, IRON), 3L * STACK, "the trip that crossed the reload landed");
                    helper.assertValueEqual(aisle.storedAt(ONE, IRON), 0L, "and the source is empty");
                    helper.assertValueEqual(order(helper, aisle).mode(), OptimizerOrder.Mode.MERGE, "mode kept");
                })
                .thenSucceed();
    }

    // --- two cranes --------------------------------------------------------------------------------------------------

    /**
     * Two cranes on one line, iron in five places, the middle one far the fullest. One merge order keeps them busy: every
     * trip of either crane goes to the one home and none leaves it, so no item moves twice, and everything ends in the
     * home with an exact census.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void mergeTwoCranesNoPingPong(GameTestHelper helper) {
        Line line = new Line(6, 13);
        build(helper, line);
        BlockPos westFar = storage(helper, line, 1, Side.LEFT, IRON.toStack(10));
        BlockPos westNear = storage(helper, line, 2, Side.LEFT, IRON.toStack(9));
        BlockPos home = storage(helper, line, 7, Side.LEFT, IRON.toStack(12));
        BlockPos eastNear = storage(helper, line, 12, Side.RIGHT, IRON.toStack(8));
        BlockPos eastFar = storage(helper, line, 13, Side.RIGHT, IRON.toStack(7));
        BlockPos optimizer = optimizer(helper, line, 9, Side.RIGHT);
        Map<ItemKey, Long> census = ItemCensus.take(helper);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while two cranes merge"));
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = new LinkedHashMap<>();
        helper.onEachTick(() -> {
            for (BlockPos dock : line.docks())
                crane(helper, dock).currentJob().ifPresent(job -> jobs.putIfAbsent(job.id(), job));
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, line))
                .thenExecute(() -> helper.assertValueEqual(optimizerAt(helper, optimizer).optimise(List.of(IRON),
                        OptimizerOrder.Mode.MERGE), Optional.of(OptimizerOrders.Start.STARTED), "a merge order"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(order(helper, controller(helper, line), helper.absolutePos(optimizer))
                            .state(), OptimizerOrder.State.FINISHED, "the order finished");
                    assertIdle(helper, line);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(helper, home, IRON), 12L + 10 + 9 + 8 + 7,
                            "everything lies in the home");
                    for (BlockPos source : List.of(westFar, westNear, eastNear, eastFar))
                        helper.assertValueEqual(countIn(helper, source, IRON), 0L, "a source is empty: " + source);
                    Set<RackPosition> targets = new HashSet<>();
                    Set<RackPosition> sources = new HashSet<>();
                    List<UUID> ids = new ArrayList<>();
                    for (TransportJob<ItemKey, RackPosition> job : jobs.values()) {
                        helper.assertValueEqual(job.type(), JobType.RELOCATE, "only relocations");
                        targets.add(job.target());
                        sources.add(job.source());
                        ids.add(job.id());
                    }
                    helper.assertValueEqual(targets.size(), 1, "every trip goes to the one home");
                    helper.assertValueEqual(sources.size(), jobs.size(), "no source is emptied twice");
                    helper.assertFalse(sources.removeAll(targets), "and no trip leaves the home");
                    helper.assertValueEqual(jobs.size(), 4, "four sources, four trips");
                })
                .thenSucceed();
    }

    // --- the screen --------------------------------------------------------------------------------------------------

    /** The screen payload carries a row's places and the order's mode, and an unknown reason ordinal reads as none. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = 200)
    public static void mergeScreenStateRoundTrips(GameTestHelper helper) {
        OptimizerScreenState.RowView row = new OptimizerScreenState.RowView(2, Optional.of(IRON),
                PriorityBreakdown.NONE, OptimizerScreenState.Pip.NOTHING_TO_MOVE,
                Optional.of(RelocationReason.HOME_FULL), 3);
        OptimizerScreenState state = new OptimizerScreenState(List.of(row), true, false,
                new OptimizerScreenState.OrderLine(OptimizerScreenState.OrderState.RUNNING, 12L, 7, true),
                OptimizerScreenState.Message.NONE, 0, 0);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        state.write(buffer);
        OptimizerScreenState read = OptimizerScreenState.read(buffer);
        helper.assertValueEqual(read, state, "the state reads back as it was written");
        helper.assertValueEqual(read.rows().get(0).places(), 3, "with its places");
        helper.assertTrue(read.order().merge(), "and the merge flag");
        helper.assertValueEqual(read.rows().get(0).reason(), Optional.of(RelocationReason.HOME_FULL), "and a merge reason");
        helper.assertValueEqual(row.statusKey(true), row.statusKey(), "a reason reads the same in a merge order");
        helper.succeed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void merge(GameTestHelper helper, AisleFixture aisle, ItemKey... keys) {
        helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(keys), OptimizerOrder.Mode.MERGE),
                Optional.of(OptimizerOrders.Start.STARTED), "a merge order");
        aisle.motor().generatedSpeed.setValue(TEST_RPM);
    }

    private static void finished(GameTestHelper helper, AisleFixture aisle) {
        helper.assertValueEqual(order(helper, aisle).state(), OptimizerOrder.State.FINISHED, "the order finished");
        aisle.assertIdleAndEmpty();
    }

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

    private static Map<UUID, TransportJob<ItemKey, RackPosition>> recordJobs(GameTestHelper helper,
            AisleFixture aisle) {
        Map<UUID, TransportJob<ItemKey, RackPosition>> jobs = new LinkedHashMap<>();
        helper.onEachTick(() -> aisle.dock().currentJob().ifPresent(job -> jobs.putIfAbsent(job.id(), job)));
        return jobs;
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

    /** One straight warehouse along +X at {@code z}: controller at x = 0, its dock at x = 1, a second dock at the far end. */
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

    private static BlockPos optimizer(GameTestHelper helper, Line line, int x, Side side) {
        BlockPos rack = line.rack(x, side);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_OPTIMIZER.getDefaultState()
                .setValue(WarehouseOptimizerBlock.FACING, Line.away(side).getOpposite()));
        return rack;
    }

    private static boolean severalCranes() {
        return WareworksConfig.maxCranesPerNetwork() > 1;
    }

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
}
