package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PortCollectSummary;
import dev.wareworks.content.station.PortRankBehaviour;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StationGoggleSummary;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.content.station.ProductionPatterns;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of <b>collecting</b>: the third direction of the warehouse port (M18, issue #13,
 * {@code docs/warehouse-system.md} §3.2.4). Instead of waiting for a belt or a funnel to push items into an input, the
 * crane <b>fetches</b> them out of the inventory a player points the port at and stores them like anything else.
 * <p>
 * The load-bearing tests are the three <b>loop guards</b> of the design, because a warehouse that churns items between a
 * chest and its own racks for ever is the one way this feature could ruin a world:
 * <ul>
 * <li>{@link #collectstopsatthemaximum} and {@link #collectandoverflowdonotchurn} — a key at its stock-rule maximum is
 * not collected at all, so collecting and an overflow can never be active for the same item at the same time;</li>
 * <li>{@link #collectneverexports} — a collect job can never drop at a port, not even at the strongest diversion;</li>
 * <li>{@link #collectdoesnottakefromitsownstorage} — a port pointed at an inventory its own aisle already counts is
 * refused and says so.</li>
 * </ul>
 * {@link #collectdefaultisoff} is the compatibility test: every other direction of a port leaves the inventory behind it
 * completely alone, which is what makes M18 invisible to every warehouse built before it.
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails). A collecting port stands at a rack position with its machine's inventory <b>behind</b> it
 * ({@link AisleFixture#portInventory}) — the same world position a storage location's chest occupies, because a port faces
 * the aisle and an interface faces away from it. Redstone triggers therefore sit <b>above</b> a port, not behind it, where
 * the machine is. Every test that moves items asserts the item conservation invariant on each tick ({@link ItemCensus}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseCollectGameTests {
    /** Its own batch for the config extremes, so no other test sees the overrides ({@link ConfigOverrides}). */
    static final String CONFIG_COLLECT_BATCH = "wareworkscollectconfig";

    private static final int AISLE_Z = 3;
    /**
     * The second aisle of {@link #collectsharedrackplane}: four blocks from the first one, which is the geometry in which
     * two aisles reach <b>one</b> inventory — the first aisle's right rack plane and the second one's left plane share the
     * column of inventories between them.
     */
    private static final int SHARED_AISLE_Z = 7;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Long enough for several dispatch intervals, a poll interval and a back-off: proof that nothing happens any more. */
    private static final int SETTLE_TICKS = 80;

    /** The collecting port and the machine behind it. */
    private static final RackPosition COLLECT_RACK = new RackPosition(0, 0, Side.RIGHT);
    /** A second collecting port, for the fairness tests. */
    private static final RackPosition SECOND_COLLECT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition SECOND_STORAGE_RACK = new RackPosition(2, 0, Side.LEFT);
    /** An accepting port on the other rack plane: the diversion or overflow the loop guards are proved against. */
    private static final RackPosition EXPORT_RACK = new RackPosition(4, 0, Side.LEFT);
    /** A warehouse port outside the aisle geometry: a valid block, and a member of nothing (test-relative). */
    private static final BlockPos OUTSIDE_PORT = new BlockPos(9, BASE_Y, 0);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);

    /** Items in the machine behind the port: one trip's worth, which is enough to prove every loop. */
    private static final int IN_MACHINE = 24;
    /** A second helping, added later. */
    private static final int ADDED_TO_MACHINE = 8;
    /** A stock rule's maximum, the reason collecting has to stop (M15, issue #3). */
    private static final int IRON_MAXIMUM = 8;
    /** Slots and stack size of a vanilla chest: what it takes to make one completely full. */
    private static final int CHEST_SLOTS = 27;
    private static final int STACK = 64;
    /**
     * More iron than one trip carries ({@code grabberStacks} × one stack, the config default), so "one rising edge is
     * <b>one</b> trip" is visible at all: a machine holding one trip's worth would be emptied by a single pulse.
     */
    private static final int MORE_THAN_ONE_TRIP = 96;
    /** Slots of a vanilla furnace, and the two of them this test cares about ({@code AbstractFurnaceBlockEntity}). */
    private static final int FURNACE_SLOTS = 3;
    private static final int FURNACE_FUEL_SLOT = 1;
    private static final int FURNACE_RESULT_SLOT = 2;
    /** Planks one log makes in the production loop test. */
    private static final int PLANKS_PER_LOG = 4;
    /** Logs in stock for that test. */
    private static final int LOGS_IN_STOCK = 4;

    private WarehouseCollectGameTests() {
    }

    // --- the direction itself ----------------------------------------------------------------------------------------

    /**
     * Compatibility: a port that <b>requests</b> (the default), one that diverts and one that overflows never touch the
     * inventory behind them. That is what makes M18 invisible to every warehouse built before it — the block behind a port
     * is a chest a player put there for a funnel, and it stays untouched.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectdefaultisoff(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port does not collect"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) IN_MACHINE,
                            "a requesting port leaves the chest behind it alone");
                    helper.assertValueEqual(aisle.controller().collectingPortCount(), 0, "and is no collecting port");
                })
                .thenExecute(() -> accept(helper, aisle, COLLECT_RACK, -4, PortRedstone.UNLESS_POWERED))
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON),
                        (long) IN_MACHINE, "an overflow port does not either"))
                .thenExecute(() -> accept(helper, aisle, COLLECT_RACK, 3, PortRedstone.UNLESS_POWERED))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) IN_MACHINE,
                            "and neither does a diversion");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The happy path, unwired: a collecting port on {@code UNLESS_POWERED} empties the chest behind it into the racks with
     * no redstone at all, the block shows the collect direction, and the port counts what it fetched.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectpullsfromachest(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port collects"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.COLLECTING, true);
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.ACCEPTING, false);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE,
                            "everything the machine held is in the racks");
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L, "and the machine is empty");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.outputAt(COLLECT_RACK).collectedItems(), (long) IN_MACHINE,
                            "the port counted what it fetched");
                    helper.assertValueEqual(aisle.controller().collectingPortCount(), 1, "one collecting port");
                    helper.assertValueEqual(aisle.stationCount(COLLECT_RACK, IRON), 0L,
                            "the port's own buffer stayed unused: the crane reaches through it");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** The port's filter is a hard rule: a filtered port fetches its item and leaves everything else in the machine. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectfilteredtakesonlyitsitem(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE), DIAMOND.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE, DIAMOND, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filtered port collects"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setFilter(helper, aisle, COLLECT_RACK, DIAMOND.toStack());
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) IN_MACHINE,
                        "the diamonds the filter names are fetched"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) IN_MACHINE,
                            "and the iron it does not name stays in the machine");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L, "stored iron");
                    aisle.assertIdleAndEmpty();
                    // And the resting state it leaves behind is honest (M18 review): a port whose filter names nothing in
                    // the machine is no source at all, so the aisle neither claims to be full nor backs off — which would
                    // suspend storing from every input station of the aisle for as long as the iron lies there.
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.NO_WORK),
                            "the aisle's planning reason");
                    helper.assertFalse(aisle.controller().isBackingOff(helper.getLevel().getGameTime()),
                            "the aisle must not back off because a filtered port's machine holds other items");
                    helper.assertValueEqual(aisle.controller().collectableAt(aisle.rackPos(COLLECT_RACK)), 0L,
                            "the number behind the port's \"Ready\" line counts what it may fetch, not what is in the "
                                    + "machine");
                })
                .thenSucceed();
    }

    /**
     * The redstone gate, all three behaviours: a pulse is one trip per rising edge (the token), "while powered" collects
     * while the signal is high and stops when it goes away, and "unless powered" is that inverted.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectrespectstheredstonegate(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        // More than one trip's worth, so a single rising edge cannot empty the machine and "one edge, one trip" is visible.
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(MORE_THAN_ONE_TRIP));
        BlockPos trigger = trigger(aisle, COLLECT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, MORE_THAN_ONE_TRIP);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the gate decides"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.PULSE);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) MORE_THAN_ONE_TRIP,
                            "a pulse port with no edge collects nothing");
                    helper.assertFalse(aisle.outputAt(COLLECT_RACK).isArmed(), "and holds no token");
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.outputAt(COLLECT_RACK).collectedItems() > 0,
                        "the rising edge is one trip"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertFalse(aisle.outputAt(COLLECT_RACK).isArmed(), "the token was spent");
                    helper.assertTrue(aisle.inventoryCount(machine, IRON) > 0,
                            "and a held signal repeats nothing: one edge, one trip");
                    // While powered: the same signal now keeps it going until the machine is empty.
                    helper.assertTrue(aisle.outputAt(COLLECT_RACK).setRedstoneMode(PortRedstone.WHILE_POWERED),
                            "mode set");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L,
                        "while powered empties the machine trip by trip"))
                .thenExecute(() -> {
                    helper.setBlock(trigger, Blocks.AIR);
                    aisle.insertAll(aisle.handlerAt(machine), IRON.toStack(ADDED_TO_MACHINE));
                    ItemCensus.change(conserved, IRON, ADDED_TO_MACHINE);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) ADDED_TO_MACHINE,
                            "and nothing follows while the signal is gone");
                    helper.assertTrue(aisle.outputAt(COLLECT_RACK).setRedstoneMode(PortRedstone.UNLESS_POWERED),
                            "mode set");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L,
                        "unless powered collects with the signal gone"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- the loop guards (§5) ---------------------------------------------------------------------------------------

    /**
     * Loop guard 1: collecting stops exactly at a stock rule's maximum, and the rest stays in the machine. Raising the
     * maximum lets it continue, which is what proves the bound is the headroom and not an accident.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectstopsatthemaximum(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a maximum bounds collecting"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) IRON_MAXIMUM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_MAXIMUM,
                        "the maximum is reached"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_MAXIMUM,
                            "and collecting stopped there");
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) (IN_MACHINE - IRON_MAXIMUM),
                            "the rest stays in the machine on purpose");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.AT_MAXIMUM),
                            "and the aisle says why");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> aisle.stockKeeperAt(KEEPER_RACK)
                        .editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) IN_MACHINE))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L,
                            "a raised maximum lets the rest through");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE, "stored iron");
                })
                .thenSucceed();
    }

    /**
     * <b>Risk 1</b>, the churn test: an <b>overflow</b> port's items are piped straight into the chest a collecting port
     * fetches from — the loop a player builds by accident — and a stock rule caps the item. The warehouse has to settle:
     * the two preconditions are mutually exclusive, so both counters stop rising and the chest's count goes constant.
     * <p>
     * The pipe is the test itself moving the port's buffer into the chest on every tick, because the mod cannot see belts
     * and this is exactly what a belt would do. Items only ever move, so the census is unchanged by it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectandoverflowdonotchurn(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK);
        aisle.output(EXPORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        long[] seen = new long[3];
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while collect and overflow meet");
            drainPortInto(aisle, EXPORT_RACK, machine);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 2))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) IRON_MAXIMUM);
                    accept(helper, aisle, EXPORT_RACK, -1, PortRedstone.UNLESS_POWERED);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(IN_MACHINE));
                    ItemCensus.change(conserved, IRON, IN_MACHINE);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_MAXIMUM,
                        "the racks fill up to the maximum"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON),
                        (long) (IN_MACHINE - IRON_MAXIMUM), "and the surplus lands in the chest through the overflow"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    seen[0] = aisle.outputAt(COLLECT_RACK).collectedItems();
                    seen[1] = aisle.outputAt(EXPORT_RACK).exportedItems();
                    seen[2] = aisle.inventoryCount(machine, IRON);
                })
                // Let it run on: the three numbers have to be the same ones a long while later.
                .thenExecuteAfter(3 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.outputAt(COLLECT_RACK).collectedItems(), seen[0],
                            "\"Collected\" stopped rising");
                    helper.assertValueEqual(aisle.outputAt(EXPORT_RACK).exportedItems(), seen[1],
                            "\"Handed over\" stopped rising");
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), seen[2],
                            "and the chest's count is constant");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_MAXIMUM,
                            "the racks still hold exactly the maximum");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Loop guard 2: a collect job never drops at a port, not even at the strongest <b>diversion</b> standing in the same
     * aisle — the one candidate that ignores the maximum guard 1 relies on. That the diversion works at all is proved in
     * the same test by an input station's items leaving through it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectneverexports(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        aisle.output(EXPORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                "while a diversion stands next to a collecting port"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 2))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    accept(helper, aisle, EXPORT_RACK, PortSettings.MAX_RANK, PortRedstone.UNLESS_POWERED);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE,
                        "the collected iron went into the racks"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(EXPORT_RACK, IRON), 0L,
                            "and not one item through the diversion");
                    helper.assertValueEqual(aisle.outputAt(EXPORT_RACK).exportedItems(), 0L, "handed over");
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L, "iron in the machine");
                    // The diversion does work — items from the input station leave through it.
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), DIAMOND.toStack(ADDED_TO_MACHINE));
                    ItemCensus.change(conserved, DIAMOND, ADDED_TO_MACHINE);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(EXPORT_RACK, DIAMOND),
                        (long) ADDED_TO_MACHINE, "an input's items do leave through the diversion"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Loop guard 3: a port pointed at an inventory its own aisle already counts as a storage location collects nothing at
     * all — otherwise one double chest behind an interface and a port would be an endless crane shuffle inside the aisle —
     * and the port's goggles say so instead of leaving a player with a port that silently does nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectdoesnottakefromitsownstorage(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        // One double chest, read by an interface at STORAGE_RACK and pointed at by a port at the rack next to it: the two
        // inventory positions are the two halves of it, so both members resolve the same InventoryIdentifier.
        BlockPos west = aisle.inventoryPos(STORAGE_RACK);
        BlockPos east = aisle.inventoryPos(SECOND_STORAGE_RACK);
        helper.setBlock(west, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(east, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        aisle.placeInterface(STORAGE_RACK);
        aisle.output(SECOND_STORAGE_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port reads its own storage"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(west), IRON.toStack(IN_MACHINE));
                    ItemCensus.change(conserved, IRON, IN_MACHINE);
                    collect(helper, aisle, SECOND_STORAGE_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(west, IRON), (long) IN_MACHINE,
                            "the aisle's own stock is not collected");
                    helper.assertValueEqual(aisle.outputAt(SECOND_STORAGE_RACK).collectedItems(), 0L, "collected");
                    helper.assertTrue(aisle.controller().collectsFromOwnStorage(
                            aisle.absoluteRackPos(SECOND_STORAGE_RACK)), "and the controller knows why");
                    helper.assertTrue(collectSummary(helper, aisle, SECOND_STORAGE_RACK).ownStorage(),
                            "which reaches the port's goggles");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>A wall of rack bays must not starve the collect queue</b> (M28 review).
     * <p>
     * The two snapshot queues share one {@code maxSnapshotsPerTick} budget and storage is drained first, which was an
     * order rather than starvation only while every content hint came from a neighbour update. A rack bay tells its
     * controller on <b>every</b> accepted transfer, so one item from a hopper is one
     * {@code WarehouseRegistry.contentChanged}, and as soon as more <i>distinct</i> bays than the whole budget change
     * per tick the urgent set never empties again. {@code refreshCollection} is reached from the drain and from
     * nowhere else, so a collecting port would then never be re-read for as long as the feeding lasts — silently,
     * because an unread port collects nothing and a port read once goes stale and makes the planner plan collects
     * whose pick returns 0.
     * <p>
     * The storm here is the hint itself rather than a belt, for two reasons: it is exactly what a bay's handler emits,
     * and it moves no items, so the census can assert on every tick that this test changes nothing in the world. The
     * crane is deliberately left unpowered for the same reason — what is measured is the controller's own drain.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectsurvivesarackbayhintstorm(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));

        // Strictly more distinct bays than one whole budget, so their hints alone keep the urgent set non-empty for
        // ever: the queue de-duplicates per rack position, so the count of BAYS is what matters, not the hint rate.
        List<BlockPos> bays = new ArrayList<>();
        for (int position = 0; position < RAILS && bays.size() < WareworksConfig.maxSnapshotsPerTick() + 2; position++) {
            for (Side side : Side.values()) {
                RackPosition rack = new RackPosition(position, 0, side);
                if (rack.equals(COLLECT_RACK) || rack.equals(STORAGE_RACK))
                    continue;
                helper.setBlock(aisle.rackPos(rack), WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                        .setValue(RackBayBlock.FACING, aisle.sideDirection(rack)));
                bays.add(aisle.absoluteRackPos(rack));
            }
        }
        helper.assertTrue(bays.size() > WareworksConfig.maxSnapshotsPerTick(),
                "the storm needs more bays than one drain budget, or it proves nothing");

        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1 + bays.size(), 0, 1))
                .thenExecute(() -> {
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    // From here on: one hint per bay per tick, which is a wall of bays being fed by machines.
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while a rack wall hints on every tick");
                        for (BlockPos bay : bays)
                            WarehouseRegistry.contentChanged(helper.getLevel(), bay);
                    });
                })
                .thenWaitUntil(() -> helper.assertValueEqual(collectSummary(helper, aisle, COLLECT_RACK).ready(),
                        (long) IN_MACHINE, "the port is read at all while the rack wall hints"))
                .thenExecute(() -> aisle.insertAll(aisle.handlerAt(machine), IRON.toStack(ADDED_TO_MACHINE)))
                .thenExecute(() -> ItemCensus.change(conserved, IRON, ADDED_TO_MACHINE))
                .thenWaitUntil(() -> helper.assertValueEqual(collectSummary(helper, aisle, COLLECT_RACK).ready(),
                        (long) (IN_MACHINE + ADDED_TO_MACHINE),
                        "and it is read AGAIN: the last unit of the budget is the collect queue's"))
                .thenExecute(() -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON),
                        (long) (IN_MACHINE + ADDED_TO_MACHINE), "and nothing was fetched: the crane never ran"))
                .thenSucceed();
    }

    // --- what cannot be stored --------------------------------------------------------------------------------------

    /**
     * <b>Risk 3</b>, the full warehouse: nothing is picked at all, the machine keeps its items, the controller says
     * {@code WAREHOUSE_FULL}, and nothing is dropped or destroyed. Room made afterwards is used at once.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectfullwarehouseleavestheitems(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        // The only storage location's chest is full of something else: it is ranked and has no room, which is a full
        // warehouse rather than a filter problem.
        aisle.storage(STORAGE_RACK);
        fill(aisle, aisle.inventoryPos(STORAGE_RACK));
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE, DIAMOND, CHEST_SLOTS * STACK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the warehouse is full"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) IN_MACHINE,
                            "nothing was fetched out of the machine");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(),
                            Optional.of(NoJobReason.WAREHOUSE_FULL), "and the aisle says so");
                    helper.assertValueEqual(collectSummary(helper, aisle, COLLECT_RACK).ready(), (long) IN_MACHINE,
                            "the port's goggles show what is waiting in the machine");
                    helper.assertItemEntityNotPresent(Items.IRON_INGOT);
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    // Room made: the very next planning run uses it.
                    ItemCensus.change(conserved, DIAMOND, -emptyInventory(aisle, aisle.inventoryPos(STORAGE_RACK)));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE,
                        "and the machine is emptied once there is room"))
                .thenSucceed();
    }

    /** The racks fill up mid-job: the leftovers land in an input buffer and are stored from there, never anywhere else. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectleftoversgotoaninput(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while leftovers are rerouted"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                // While the crane carries the iron, the only chest is filled with something else, so its drop fails.
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0, "the crane carries it"))
                .thenExecute(() -> {
                    fill(aisle, aisle.inventoryPos(STORAGE_RACK));
                    ItemCensus.change(conserved, DIAMOND, CHEST_SLOTS * STACK);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(INPUT_RACK, IRON) > 0
                                || aisle.dock().craneState().phase() == CranePhase.HOLDING,
                        "the leftovers go into an input buffer, or the crane holds them"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertItemEntityNotPresent(Items.IRON_INGOT);
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L,
                            "and nothing was pushed back into the machine");
                })
                .thenSucceed();
    }

    /** Nothing accepts the items at all: the crane holds them, and a hold retry stores them once room appears. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectholdswhennothingaccepts(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the crane holds collected items"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0, "the crane carries it"))
                // The only target's inventory is gone: an empty chest, so nothing is lost by removing it.
                .thenExecute(() -> aisle.breakBlock(aisle.inventoryPos(STORAGE_RACK)))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().craneState().phase(), CranePhase.HOLDING,
                        "the crane holds rather than dropping them anywhere"))
                .thenExecute(() -> helper.setBlock(aisle.inventoryPos(STORAGE_RACK), Blocks.CHEST))
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.storedAt(STORAGE_RACK, IRON) > 0,
                            "and stores them once a target appears again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- fairness (risk 4) ------------------------------------------------------------------------------------------

    /** Two collecting ports: both are served, and both machines end up empty. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectfairnesstwoports(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos first = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        BlockPos second = aisle.portInventory(SECOND_COLLECT_RACK, DIAMOND.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE, DIAMOND, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while two ports collect"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 2))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    collect(helper, aisle, SECOND_COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(first, IRON), 0L, "the first machine is emptied");
                    helper.assertValueEqual(aisle.inventoryCount(second, DIAMOND), 0L, "and the second one too");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE, "stored iron");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) IN_MACHINE, "stored diamonds");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Fairness against an input station: a belt that keeps an input permanently non-empty must not starve a collecting
     * port. The port is served while the input still holds items, which is exactly what a collect stage after storing
     * could never do.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectfairnessagainstaninput(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.storage(SECOND_STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, DIAMOND.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, IN_MACHINE);
        // The belt: the input is topped up whenever it runs low, so it is never empty for long.
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while an input competes with a port");
            if (aisle.stationCount(INPUT_RACK, IRON) < STACK / 8) {
                ItemStack rest = ItemHandlerHelper.insertItem(aisle.handlerAt(aisle.rackPos(INPUT_RACK)),
                        IRON.toStack(STACK / 8), false);
                ItemCensus.change(conserved, IRON, STACK / 8 - rest.getCount());
            }
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.outputAt(COLLECT_RACK).collectedItems() > 0,
                        "the collecting port is served although the input never runs dry"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.inventoryCount(machine, DIAMOND), 0L,
                        "and it is served to the end"))
                .thenSucceed();
    }

    // --- what can go wrong mid-job ----------------------------------------------------------------------------------

    /** The machine consumed the items between planning and picking: a zero pick, a clean abort, no reservation left. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectsourceemptiedbeforethepick(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the machine empties itself"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob()
                        .filter(job -> job.type() == JobType.COLLECT).isPresent(), "a collect job was planned"))
                // The machine's own recipe took them: emptied before the crane's arm arrives (or just after it).
                .thenExecute(() -> ItemCensus.change(conserved, IRON, -emptyInventory(aisle, machine)))
                .thenWaitUntil(() -> {
                    aisle.assertIdleAndEmpty();
                    helper.assertValueEqual(aisle.outputAt(COLLECT_RACK).collectedItems(),
                            aisle.storedAt(STORAGE_RACK, IRON),
                            "exactly what was really picked was counted and stored: nothing was invented");
                })
                .thenSucceed();
    }

    /** The machine is removed after the pick: the items already in the head still reach storage. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectsourcebrokenmidjob(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the machine is removed"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0,
                        "the crane picked and carries them"))
                .thenExecute(() -> {
                    // A player breaks the machine, taking its contents with them.
                    ItemCensus.change(conserved, IRON, -emptyInventory(aisle, machine));
                    aisle.breakBlock(machine);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.storedAt(STORAGE_RACK, IRON) > 0,
                            "what was already in the head still reaches storage");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertFalse(collectSummary(helper, aisle, COLLECT_RACK).hasInventory(),
                            "and the port says there is no inventory behind it any more");
                    helper.assertValueEqual(aisle.controller().collectableAt(aisle.absoluteRackPos(COLLECT_RACK)), 0L,
                            "and offers nothing to collect");
                })
                .thenSucceed();
    }

    /** The storage target is broken after the pick: a reroute into another location, never back into the machine. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collecttargetbrokenmidjob(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.storage(SECOND_STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the target is broken"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0, "the crane carries it"))
                .thenExecute(() -> aisle.breakBlock(
                        aisle.inventoryPos(aisle.dock().currentJob().orElseThrow().target())))
                .thenWaitUntil(() -> {
                    long stored = aisle.storedAt(STORAGE_RACK, IRON) + aisle.storedAt(SECOND_STORAGE_RACK, IRON);
                    helper.assertTrue(stored > 0, "the items were rerouted into the other location");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    long stored = aisle.storedAt(STORAGE_RACK, IRON) + aisle.storedAt(SECOND_STORAGE_RACK, IRON);
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON) + stored, (long) IN_MACHINE,
                            "and nothing was pushed back into the machine");
                })
                .thenSucceed();
    }

    /**
     * Wrenched out of the collect direction mid-job: before the pick the job aborts with nothing held, after it the items
     * still reach storage — and either way nothing more is fetched and nothing is lost.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectdirectionflipmidjob(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the direction is flipped"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob()
                        .filter(job -> job.type() == JobType.COLLECT).isPresent(), "a collect job is under way"))
                .thenExecute(() -> helper.assertTrue(
                        aisle.outputAt(COLLECT_RACK).setPortRank(PortSettings.REQUEST_RANK), "back to requesting"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    long stored = aisle.storedAt(STORAGE_RACK, IRON);
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON) + stored, (long) IN_MACHINE,
                            "whatever was picked before the flip is stored, the rest stays in the machine");
                    helper.assertValueEqual(aisle.outputAt(COLLECT_RACK).collectedItems(), stored,
                            "and the counter agrees");
                    helper.assertValueEqual(aisle.controller().collectingPortCount(), 0, "the cache followed the flip");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(
                        aisle.outputAt(COLLECT_RACK).collectedItems(), aisle.storedAt(STORAGE_RACK, IRON),
                        "and nothing at all is fetched any more"))
                .thenSucceed();
    }

    // --- machines, stock and persistence ----------------------------------------------------------------------------

    /**
     * <b>Risk 5</b>, a machine's own rules: a vanilla furnace behind the port. The <b>machine</b> decides what the face
     * touching the port hands out, and the warehouse obeys it — a furnace offers its <b>fuel</b> slot to a horizontal face
     * and its result slot only downwards, exactly as it does to a hopper, so a player who wants the result points the port
     * at a chest under a hopper instead. Whatever is in it, the crane never inserts anything into the machine.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectdoesnotfightamachine(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        // A furnace behind the port, its own front turned along the aisle so nothing about the facing is accidental.
        BlockPos furnacePos = aisle.portInventory(COLLECT_RACK,
                Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.EAST));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a furnace stands behind the port"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    // The result slot, which a furnace shows to its underside only: the port's face must not reach it.
                    furnace(helper, furnacePos).setItem(FURNACE_RESULT_SLOT, IRON.toStack(IN_MACHINE));
                    furnace(helper, furnacePos).setChanged();
                    ItemCensus.change(conserved, IRON, IN_MACHINE);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L,
                            "the machine's own rule holds: its result slot is not offered to this face");
                    helper.assertValueEqual(furnace(helper, furnacePos).getItem(FURNACE_RESULT_SLOT).getCount(),
                            IN_MACHINE, "and the items are untouched");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    // Moved into the slot that face does offer — the fuel slot — and the very same port collects it.
                    FurnaceBlockEntity furnace = furnace(helper, furnacePos);
                    furnace.setItem(FURNACE_FUEL_SLOT, furnace.removeItemNoUpdate(FURNACE_RESULT_SLOT));
                    furnace.setChanged();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE,
                        "what the face really offers is collected, exactly as a hopper on it would take it"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FurnaceBlockEntity furnace = furnace(helper, furnacePos);
                    for (int slot = 0; slot < FURNACE_SLOTS; slot++)
                        helper.assertTrue(furnace.getItem(slot).isEmpty(),
                                "nothing was ever pushed into the furnace, slot " + slot);
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** A Create depot behind the port: the same contract, with one of Create's own inventories. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectfromacreatedepot(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos depot = aisle.portInventory(COLLECT_RACK, AllBlocks.DEPOT.getDefaultState());
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a depot stands behind the port"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    ItemStack rest = ItemHandlerHelper.insertItem(aisle.handlerAt(depot), IRON.toStack(STACK), false);
                    ItemCensus.change(conserved, IRON, STACK - rest.getCount());
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.storedAt(STORAGE_RACK, IRON) > 0, "the depot's item is collected");
                    helper.assertValueEqual(aisle.inventoryCount(depot, IRON), 0L, "and the depot is empty");
                })
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Collected items are <b>not stock until they are stored</b>: while they sit in the machine and while the crane
     * carries them the stock index does not count them, and only the real drop makes them stock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectitemsarenotstockuntilstored(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while collected items travel"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), 0L,
                            "what is in the machine is not stock: the index counts nothing");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0, "the crane carries it"))
                .thenExecute(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), 0L,
                        "and items in the handling head are not stock either"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) IN_MACHINE,
                            "only the real drop makes them stock");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** Save and load: the collect job, the collect sentinel and the port's counter all come back. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectpersistence(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a collect job is saved"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0, "the crane carries it"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    // The crane's own save data: a COLLECT job persists by its type name and comes back as one.
                    CompoundTag craneTag = aisle.dock().saveWithoutMetadata(registries);
                    helper.assertTrue(craneTag.toString().contains(JobType.COLLECT.name()),
                            "the saved crane names the collect job");
                    CompoundTag portTag = aisle.outputAt(COLLECT_RACK).saveWithoutMetadata(registries);
                    helper.assertValueEqual(portTag.getInt(PortRankBehaviour.RANK_TAG), PortSettings.COLLECT_RANK,
                            "and the port saves the collect sentinel");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IN_MACHINE, "stored iron");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    // A fresh block entity loaded from the saved tag, which is what a world reload is.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity live = aisle.outputAt(COLLECT_RACK);
                    // With the metadata, because that is what BlockEntity#loadStatic needs to find the type again.
                    CompoundTag tag = live.saveWithFullMetadata(registries);
                    helper.assertValueEqual(tag.getLong(WarehouseOutputBlockEntity.COLLECTED_TAG), (long) IN_MACHINE,
                            "the collected counter is saved");
                    BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                            registries);
                    if (!(loaded instanceof WarehouseOutputBlockEntity port)) {
                        helper.fail("a saved warehouse port must load again as one");
                        return;
                    }
                    helper.assertValueEqual(port.collectedItems(), (long) IN_MACHINE, "and read back");
                    helper.assertValueEqual(port.portSettings(),
                            new PortSettings(PortSettings.COLLECT_RANK, PortRedstone.UNLESS_POWERED),
                            "with the whole policy");
                })
                .thenSucceed();
    }

    /**
     * After a world load the controller has read no port at all, and an <b>unread</b> port collects nothing: "not read"
     * must never mean "collect". The first port pass resolves it and collecting starts.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectcoldcacheafterreload(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a controller is rebuilt"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    // A fresh controller: exactly the state a world load leaves behind — no port read, no snapshot.
                    aisle.breakBlock(aisle.controllerPos());
                    aisle.placeController();
                    helper.assertValueEqual(aisle.controller().collectingPortCount(), 0,
                            "a controller that has read nothing knows of no collecting port");
                    helper.assertValueEqual(aisle.controller().collectableAt(aisle.absoluteRackPos(COLLECT_RACK)), 0L,
                            "and offers nothing to collect");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().collectingPortCount(), 1,
                        "the port pass resolves it"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L, "and then it collects");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** A port with nothing behind it plans no job and says so on its goggles. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectnoinventorybehind(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        aisle.output(COLLECT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "with nothing behind the port"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertTrue(aisle.dock().currentJob().isEmpty(), "no job at all");
                    helper.assertFalse(aisle.outputAt(COLLECT_RACK).hasAttachedInventory(), "nothing is attached");
                    helper.assertFalse(collectSummary(helper, aisle, COLLECT_RACK).hasInventory(),
                            "and the goggles say so");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** A warehouse port outside every aisle collects nothing, whatever stands behind it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectoutsideanaisletakesnothing(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos chest = OUTSIDE_PORT.relative(Direction.SOUTH);
        helper.setBlock(OUTSIDE_PORT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, Direction.NORTH));
        helper.setBlock(chest, Blocks.CHEST);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "with a port outside the aisle"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(chest), IRON.toStack(IN_MACHINE));
                    ItemCensus.change(conserved, IRON, IN_MACHINE);
                    WarehouseOutputBlockEntity port = strayPort(helper);
                    helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK), "it collects now");
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                })
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.inventoryCount(chest, IRON), (long) IN_MACHINE,
                            "a port no aisle contains collects nothing");
                    helper.assertValueEqual(strayPort(helper).collectedItems(), 0L, "collected");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The whole production loop (ADR-027): a restock order supplies the machine, the result appears in the chest behind a
     * collecting port, the crane fetches it, and the order counts it as <b>arrived</b> instead of timing out and arming the
     * safety stop. This is the one line of {@code onCraneDelivered} that makes collecting close the loop.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectclosestheproductionloop(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(SECOND_STORAGE_RACK);
        aisle.production(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a production loop closes"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    // One log makes four planks, which is what a player writes into the production station.
                    WarehouseProductionBlockEntity station = aisle.productionAt(INPUT_RACK);
                    helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANKS,
                            PLANKS_PER_LOG), "and its result");
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANKS, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) PLANKS_PER_LOG);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                // The restock order sends the log to the production station.
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(INPUT_RACK, LOG) > 0,
                        "the ingredient arrived at the machine"))
                .thenExecute(() -> {
                    // The player's machinery: it takes the log and drops the planks into the chest behind the port.
                    ItemCensus.change(conserved, LOG, -emptyInventory(aisle, aisle.rackPos(INPUT_RACK)));
                    aisle.insertAll(aisle.handlerAt(machine), PLANKS.toStack(PLANKS_PER_LOG));
                    ItemCensus.change(conserved, PLANKS, PLANKS_PER_LOG);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(PLANKS),
                        (long) PLANKS_PER_LOG, "the crane collected the result and stored it"))
                .thenExecuteAfter(2 * SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "the order counted its product, so the safety stop never fired");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "and the order finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** Two aisles reaching one machine: the real extract is authoritative, so it is emptied and counted exactly once. */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void collectsharedrackplane(GameTestHelper helper) {
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        AisleFixture second = new AisleFixture(helper, SHARED_AISLE_Z, RAILS).build(true);
        first.storage(STORAGE_RACK);
        second.storage(STORAGE_RACK);
        // The machine sits in the column of inventories between the two aisles: the first aisle reaches it from its right
        // rack plane, the second from its left one, and both ports collect from it.
        BlockPos machine = first.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        second.output(new RackPosition(COLLECT_RACK.x(), 0, Side.LEFT));
        RackPosition secondPort = new RackPosition(COLLECT_RACK.x(), 0, Side.LEFT);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while two aisles reach one machine"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    first.assertReady(1, 0, 1);
                    second.assertReady(1, 0, 1);
                    helper.assertValueEqual(second.inventoryPos(secondPort), machine,
                            "both ports really read the same block");
                })
                .thenExecute(() -> {
                    first.motor().generatedSpeed.setValue(TEST_RPM);
                    second.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, first, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                    collect(helper, second, secondPort, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(first.inventoryCount(machine, IRON), 0L,
                        "the machine is emptied"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    long stored = first.storedAt(STORAGE_RACK, IRON) + second.storedAt(STORAGE_RACK, IRON);
                    long counted = first.outputAt(COLLECT_RACK).collectedItems()
                            + second.outputAt(secondPort).collectedItems();
                    helper.assertValueEqual(stored, (long) IN_MACHINE, "everything was stored exactly once");
                    helper.assertValueEqual(counted, (long) IN_MACHINE, "and counted exactly once");
                    first.assertIdleAndEmpty();
                    second.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- the settings themselves ------------------------------------------------------------------------------------

    /**
     * The fourth board row through the wrench board, the clipboard round trip, and the {@code COLLECTING} block state
     * re-asserted from the rank after a {@code /setblock} that claims the wrong direction.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void collectclipboardandvalueboxes(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        aisle.output(COLLECT_RACK);
        aisle.output(SECOND_COLLECT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 2))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(COLLECT_RACK);
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    PortRankBehaviour rank = rankOf(helper, port);

                    // The fourth row, whose column means nothing: every column composes the same sentinel.
                    for (int magnitude = 0; magnitude <= PortSettings.MAX_STRENGTH; magnitude++) {
                        rank.setValueSettings(player, new ValueSettings(PortSettings.COLLECT_ROW, magnitude), false);
                        helper.assertValueEqual(port.portRank(), PortSettings.COLLECT_RANK, "column " + magnitude);
                        helper.assertValueEqual(port.portDirection(), PortDirection.COLLECT, "it collects");
                    }
                    helper.assertValueEqual(rank.getValueSettings(), new ValueSettings(PortSettings.COLLECT_ROW, 0),
                            "the board opens on the collect row");
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.COLLECTING, true);
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.ACCEPTING, false);

                    // And the diversion row still composes a diversion, so the sentinel took nothing away.
                    rank.setValueSettings(player, new ValueSettings(PortSettings.DIVERSION_ROW,
                            PortSettings.MAX_STRENGTH), false);
                    helper.assertValueEqual(port.portRank(), PortSettings.MAX_RANK, "the accept band is untouched");
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.COLLECTING, false);
                    rank.setValueSettings(player, new ValueSettings(PortSettings.COLLECT_ROW, 0), false);

                    // A number nobody may compose — from a tampered clipboard, a command or a caller of setPortRank —
                    // clamps into the accept band and never onto the sentinel, which would turn the port around (M18
                    // review): the same number read from a save means the strongest diversion, and one stored number must
                    // not mean two directions.
                    helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK + 1), "out of band, so it changed");
                    helper.assertValueEqual(port.portRank(), PortSettings.MAX_RANK, "clamped into the accept band");
                    helper.assertValueEqual(port.portDirection(), PortDirection.ACCEPT, "and it accepts, not collects");
                    helper.assertTrue(port.setPortRank(PortSettings.MIN_RANK - 5), "out of band the other way");
                    helper.assertValueEqual(port.portRank(), PortSettings.MIN_RANK, "clamped to the weakest overflow");
                    helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK), "and the sentinel itself still works");
                    helper.assertValueEqual(port.portDirection(), PortDirection.COLLECT, "it collects");
                })
                .thenExecute(() -> {
                    // The clipboard: a collecting port pasted onto a plain one makes that one collect too.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag clipboard = new CompoundTag();
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    helper.assertTrue(rankOf(helper, aisle.outputAt(COLLECT_RACK))
                            .writeToClipboard(registries, clipboard, Direction.UP), "copied");
                    helper.assertTrue(rankOf(helper, aisle.outputAt(SECOND_COLLECT_RACK))
                            .readFromClipboard(registries, clipboard, player, Direction.UP, false), "pasted");
                    helper.assertValueEqual(aisle.outputAt(SECOND_COLLECT_RACK).portRank(), PortSettings.COLLECT_RANK,
                            "the paste carried the whole direction");
                    helper.assertBlockProperty(aisle.rackPos(SECOND_COLLECT_RACK), WarehouseOutputBlock.COLLECTING,
                            true);
                })
                .thenExecute(() -> {
                    // A /setblock (or a schematic) that claims the wrong direction is corrected, not believed.
                    BlockPos pos = aisle.rackPos(COLLECT_RACK);
                    helper.setBlock(pos, helper.getBlockState(pos).setValue(WarehouseOutputBlock.COLLECTING, false)
                            .setValue(WarehouseOutputBlock.ACCEPTING, true));
                })
                .thenExecute(() -> {
                    aisle.outputAt(COLLECT_RACK).onLoad();
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.COLLECTING, true);
                    helper.assertBlockProperty(aisle.rackPos(COLLECT_RACK), WarehouseOutputBlock.ACCEPTING, false);
                })
                .thenSucceed();
    }

    // --- config extremes (own batch) --------------------------------------------------------------------------------

    /**
     * The new poll interval at both ends of its range (§9): at 1 tick a machine that notifies nobody is noticed at once,
     * and at the maximum a change hint from the block still gets the items collected — which is what makes the poll a
     * safety net rather than the mechanism.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_COLLECT_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void configcollectpollextremes(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.collectPollIntervalTicks, 1);
        AisleFixture aisle = aisle(helper);
        aisle.storage(STORAGE_RACK);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(IN_MACHINE));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IN_MACHINE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "at the poll interval extremes"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    collect(helper, aisle, COLLECT_RACK, PortRedstone.UNLESS_POWERED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.inventoryCount(machine, IRON), 0L,
                        "a one-tick poll collects everything"))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.collectPollIntervalTicks, 1200);
                    // A change hint from the chest overtakes the poll, so this still arrives in seconds, not in a minute.
                    aisle.insertAll(aisle.handlerAt(machine), IRON.toStack(ADDED_TO_MACHINE));
                    ItemCensus.change(conserved, IRON, ADDED_TO_MACHINE);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) (IN_MACHINE + ADDED_TO_MACHINE),
                            "the hint gets them collected whatever the poll interval says");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(ConfigOverrides::restoreAll)
                .thenSucceed();
    }

    /** Restores the config even when {@link #configcollectpollextremes} fails before its own restore. */
    @AfterBatch(batch = CONFIG_COLLECT_BATCH)
    public static void restoreCollectConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static AisleFixture aisle(GameTestHelper helper) {
        return new AisleFixture(helper, AISLE_Z, RAILS).build(true);
    }

    /**
     * Fills the inventory at a test-relative position completely with one item, so nothing else fits into it — what a
     * genuinely full warehouse looks like, told apart from a filter that refuses ({@link NoJobReason#WAREHOUSE_FULL} vs
     * {@link NoJobReason#NO_MATCHING_FILTER}). The caller keeps the census in step with {@link #CHEST_SLOTS} × {@value
     * #STACK} items.
     */
    private static void fill(AisleFixture aisle, BlockPos pos) {
        IItemHandler handler = aisle.handlerAt(pos);
        for (int slot = 0; slot < CHEST_SLOTS; slot++)
            aisle.insertAll(handler, DIAMOND.toStack(STACK));
    }

    /** Takes everything out of the inventory at a test-relative position and answers how much that was. */
    private static int emptyInventory(AisleFixture aisle, BlockPos pos) {
        IItemHandler handler = aisle.handlerAt(pos);
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        return taken;
    }

    /**
     * Moves everything out of a port's buffer into {@code target}: the belt a player builds behind an overflow port,
     * which the mod cannot see. Items only move, so an item census is unaffected by it.
     */
    private static void drainPortInto(AisleFixture aisle, RackPosition port, BlockPos target) {
        IItemHandler buffer = aisle.handlerAt(aisle.rackPos(port));
        IItemHandler into = aisle.handlerAt(target);
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack taken = buffer.extractItem(slot, Integer.MAX_VALUE, false);
            if (taken.isEmpty())
                continue;
            ItemStack rest = ItemHandlerHelper.insertItem(into, taken, false);
            if (!rest.isEmpty())
                buffer.insertItem(slot, rest, false); // the target is full: put it back, nothing is ever lost
        }
    }

    /** The redstone trigger block <b>above</b> a port: behind it is where the machine stands (M18). */
    private static BlockPos trigger(AisleFixture aisle, RackPosition rack) {
        return aisle.rackPos(rack).relative(Direction.UP);
    }

    /** Makes the port at {@code rack} a collecting one with a redstone behaviour (M18, issue #13). */
    private static void collect(GameTestHelper helper, AisleFixture aisle, RackPosition rack, PortRedstone mode) {
        WarehouseOutputBlockEntity port = aisle.outputAt(rack);
        helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK), "the port at " + rack + " collects now");
        port.setRedstoneMode(mode);
        helper.assertValueEqual(port.portSettings(), new PortSettings(PortSettings.COLLECT_RANK, mode),
                "the port's policy");
        helper.assertValueEqual(port.portDirection(), PortDirection.COLLECT, "and its direction");
    }

    /** Makes the port at {@code rack} an accepting one with a signed rank and a redstone behaviour (M17). */
    private static void accept(GameTestHelper helper, AisleFixture aisle, RackPosition rack, int rank,
            PortRedstone mode) {
        WarehouseOutputBlockEntity port = aisle.outputAt(rack);
        port.setPortRank(rank);
        port.setRedstoneMode(mode);
        helper.assertValueEqual(port.portSettings(), new PortSettings(rank, mode), "the port's policy");
    }

    /** Sets the filter of the port at {@code rack}, as a player click on its filter slot would. */
    private static void setFilter(GameTestHelper helper, AisleFixture aisle, RackPosition rack, ItemStack filter) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(aisle.outputAt(rack), FilteringBehaviour.TYPE);
        if (!(behaviour instanceof RequestFilterBehaviour port)) {
            helper.fail("the port has no request filter behaviour", aisle.rackPos(rack));
            return;
        }
        helper.assertTrue(port.setFilter(filter), "the port accepts the filter " + filter);
    }

    /**
     * What a <b>client</b> receives about a collecting port: read out of the goggle summary of the block entity's own
     * update tag, which is the packet {@code SyncedBlockEntity} sends. Every value in it is server-only knowledge, so this
     * is the only channel that can carry it to a tooltip.
     */
    private static PortCollectSummary collectSummary(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        WarehouseOutputBlockEntity port = aisle.outputAt(rack);
        port.onGoggleObserved();
        CompoundTag packet = port.getUpdateTag(helper.getLevel().registryAccess());
        return StationGoggleSummary.read(packet.getCompound(WarehouseStationBlockEntity.SUMMARY_TAG)).collect();
    }

    private static FurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof FurnaceBlockEntity furnace)) {
            helper.fail("missing furnace block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return furnace;
    }

    private static WarehouseOutputBlockEntity strayPort(GameTestHelper helper) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(OUTSIDE_PORT));
        if (!(be instanceof WarehouseOutputBlockEntity port)) {
            helper.fail("missing warehouse output block entity", OUTSIDE_PORT);
            throw new IllegalStateException("unreachable");
        }
        return port;
    }

    private static PortRankBehaviour rankOf(GameTestHelper helper, WarehouseOutputBlockEntity port) {
        ScrollValueBehaviour behaviour = BlockEntityBehaviour.get(port, ScrollValueBehaviour.TYPE);
        if (!(behaviour instanceof PortRankBehaviour rank)) {
            helper.fail("the port has no rank behaviour", port.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return rank;
    }
}
