package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerScreenState;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RelocationReason;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Why nothing happens, said where a player looks (issue #53, ADR-087): the reason on a Warehouse Optimizer's row when
 * its item has nothing to move, and the reason on a Warehouse Input when its items wait because every rack bay holds
 * another item type. Both are decided on the server from the planner's own decision; these tests read them where the
 * screen and the goggles get them ({@code WarehouseOptimizerBlockEntity#screenState}, {@code StationGoggleSummary}).
 * <ul>
 * <li>{@code optimizerrowsayswhynothingmoves}: three wooden rack bays at the default priority 0, iron in one of them.
 * Optimise ends the iron with {@link RelocationReason#SAME_PRIORITY} (the screen row says "All places have the same
 * priority" instead of "Nothing to move"), and an item that is not in stock keeps {@link RelocationReason#NONE_IN_STOCK}
 * ("Nothing to move"). With the iron's bay at priority 5 it is {@link RelocationReason#NO_HIGHER_PRIORITY}, and with
 * a bay at priority 9 that holds gold it is {@link RelocationReason#NO_ROOM_HIGHER}. The crane never takes a job.</li>
 * <li>{@code inputsaysnofreeplaceforitstype}: one plain bay holding cobblestone and dirt waiting in the input: the
 * controller's planning line and the input's goggle data say {@link NoJobReason#NO_LOCATION_FOR_TYPE}. With a
 * cobblestone filter on that bay the filter is the reason again: {@link NoJobReason#NO_MATCHING_FILTER} on both.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StoreReasonGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int STACK = 64;

    private static final RackPosition LOW = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition HIGH = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition MIDDLE = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OPTIMIZER_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);

    private StoreReasonGameTests() {
    }

    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerRowSaysWhyNothingMoves(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        for (RackPosition rack : List.of(LOW, HIGH, MIDDLE))
            placeBay(helper, aisle, rack);
        aisle.build(true);
        aisle.optimizer(OPTIMIZER_RACK);
        RackBayBlockEntity low = bayAt(helper, aisle, LOW);
        RackBayBlockEntity high = bayAt(helper, aisle, HIGH);
        insert(helper, aisle, LOW, IRON.toStack(STACK));
        Map<ItemKey, Long> census = ItemCensus.of();
        ItemCensus.change(census, IRON, STACK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while nothing may move");
            helper.assertTrue(aisle.dock().currentJob().isEmpty(), "the crane never takes a job");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STACK,
                        "the controller indexed the iron"))
                .thenExecute(() -> {
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    optimizer.editRow(0, IRON);
                    optimizer.editRow(1, DIAMOND);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertValueEqual(optimizer.optimise(List.of(IRON, DIAMOND)),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order at priority 0 everywhere");
                })
                .thenWaitUntil(() -> assertEnded(helper, aisle, IRON, RelocationReason.SAME_PRIORITY))
                .thenWaitUntil(() -> assertEnded(helper, aisle, DIAMOND, RelocationReason.NONE_IN_STOCK))
                .thenExecute(() -> {
                    assertRow(helper, aisle, 0, IRON, Optional.of(RelocationReason.SAME_PRIORITY),
                            "gui.optimizer.reason.same_priority");
                    assertRow(helper, aisle, 1, DIAMOND, Optional.of(RelocationReason.NONE_IN_STOCK),
                            "gui.optimizer.pip.nothing_to_move");
                    // The iron is already as high as anything goes.
                    helper.assertTrue(low.setStorePriority(5), "the iron's bay takes a priority");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(LOW),
                        "the controller read the priority"))
                .thenExecute(() -> helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                        Optional.of(OptimizerOrders.Start.STARTED), "a fresh order"))
                .thenWaitUntil(() -> assertEnded(helper, aisle, IRON, RelocationReason.NO_HIGHER_PRIORITY))
                .thenExecute(() -> {
                    assertRow(helper, aisle, 0, IRON, Optional.of(RelocationReason.NO_HIGHER_PRIORITY),
                            "gui.optimizer.reason.no_higher_priority");
                    // A better place exists, but it is a one-type bay that holds gold: no room for iron there.
                    insert(helper, aisle, HIGH, GOLD.toStack(1));
                    ItemCensus.change(census, GOLD, 1);
                    helper.assertTrue(high.setStorePriority(9), "the gold's bay takes a higher priority");
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.isStoragePrioritised(HIGH), "the controller read that priority");
                    helper.assertValueEqual(controller.countOf(GOLD), 1L, "and indexed the gold");
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.optimizerAt(OPTIMIZER_RACK).optimise(List.of(IRON)),
                        Optional.of(OptimizerOrders.Start.STARTED), "another order"))
                .thenWaitUntil(() -> assertEnded(helper, aisle, IRON, RelocationReason.NO_ROOM_HIGHER))
                .thenExecute(() -> {
                    assertRow(helper, aisle, 0, IRON, Optional.of(RelocationReason.NO_ROOM_HIGHER),
                            "gui.optimizer.reason.no_room_higher");
                    helper.assertValueEqual(low.storedKey().orElse(null), IRON, "the iron stayed");
                    helper.assertValueEqual(low.storedCount(), STACK, "all of it");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void inputSaysNoFreePlaceForItsType(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        placeBay(helper, aisle, HIGH);
        aisle.build(true);
        aisle.input(INPUT_RACK);
        RackBayBlockEntity bay = bayAt(helper, aisle, HIGH);
        Map<ItemKey, Long> census = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the dirt waits"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(STACK));
                    ItemCensus.change(census, COBBLESTONE, STACK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), STACK, "the cobblestone is stored"))
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), DIRT.toStack(STACK));
                    ItemCensus.change(census, DIRT, STACK);
                })
                // No filter anywhere: the one bay is simply the cobblestone's.
                .thenWaitUntil(() -> assertReasons(helper, aisle, NoJobReason.NO_LOCATION_FOR_TYPE))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), (long) STACK,
                            "the dirt waits in the input");
                    helper.assertTrue(bay.setStoreFilter(COBBLESTONE.toStack()), "the bay takes a cobblestone filter");
                })
                // Now the bay's filter rejects the dirt as well: the filter is the reason.
                .thenWaitUntil(() -> assertReasons(helper, aisle, NoJobReason.NO_MATCHING_FILTER))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), (long) STACK, "the dirt still waits");
                    helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "the bay kept its type");
                })
                .thenSucceed();
    }

    /** The controller's planning line and the input's own goggle data both say {@code reason}. */
    private static void assertReasons(GameTestHelper helper, AisleFixture aisle, NoJobReason reason) {
        helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(reason), "the controller's line");
        WarehouseInputBlockEntity input = aisle.inputAt(INPUT_RACK);
        input.onGoggleObserved();
        helper.assertValueEqual(input.summary().notStored(), Optional.of(reason), "the input's goggles");
    }

    /** The optimizer's order ended {@code key} with nothing to move, for {@code reason}. */
    private static void assertEnded(GameTestHelper helper, AisleFixture aisle, ItemKey key, RelocationReason reason) {
        WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
        OptimizerOrder<ItemKey, RackPosition> order = optimizer.order(aisle.controller())
                .orElseThrow(() -> new AssertionError("the optimizer has an order"));
        OptimizerOrder.Entry<ItemKey> entry = order.entryFor(key)
                .orElseThrow(() -> new AssertionError("the order lists " + key));
        helper.assertValueEqual(entry.state(), OptimizerOrder.EntryState.NOTHING_TO_MOVE, key + " has nothing to move");
        helper.assertValueEqual(entry.endReason(), Optional.of(reason), "and says why");
    }

    /** Row {@code row} of the optimizer's screen as the server builds it: the item, its reason and its sentence. */
    private static void assertRow(GameTestHelper helper, AisleFixture aisle, int row, ItemKey key,
            Optional<RelocationReason> reason, String statusKey) {
        OptimizerScreenState state = aisle.optimizerAt(OPTIMIZER_RACK).screenState(aisle.controller(), Map.of(),
                OptimizerScreenState.Message.NONE, 0, 0);
        OptimizerScreenState.RowView view = state.row(row)
                .orElseThrow(() -> new AssertionError("the screen has row " + row));
        helper.assertValueEqual(view.key(), Optional.of(key), "row " + row + " lists " + key);
        helper.assertValueEqual(view.pip(), OptimizerScreenState.Pip.NOTHING_TO_MOVE, "its mark");
        helper.assertValueEqual(view.reason(), reason, "the reason the row carries");
        helper.assertValueEqual(view.statusKey(), statusKey, "the sentence the row shows");
    }

    private static void placeBay(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        BlockPos pos = aisle.rackPos(rack);
        Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, aisle.sideDirection(rack)));
    }

    private static RackBayBlockEntity bayAt(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        RackBayBlockEntity be = WareworksBlockEntityTypes.RACK_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(rack)));
        if (be == null)
            helper.fail("missing rack bay block entity", aisle.rackPos(rack));
        return be;
    }

    /** Puts {@code stack} straight into the bay at {@code rack} through its item capability, as a funnel would. */
    private static void insert(GameTestHelper helper, AisleFixture aisle, RackPosition rack, ItemStack stack) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(aisle.rackPos(rack)), null);
        if (handler == null)
            helper.fail("the rack bay exposes no item handler", aisle.rackPos(rack));
        helper.assertTrue(handler.insertItem(0, stack, false).isEmpty(), "the bay takes " + stack);
    }
}
