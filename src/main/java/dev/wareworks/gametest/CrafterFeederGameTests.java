package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.CrafterRecipeCheck;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseCrafterFeederBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FeederCycle;
import dev.wareworks.core.production.FeederStatus;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of the Warehouse Crafter Feeder (issue #34, ADR-067): a <b>vanilla Crafter</b> crafts a production
 * station's patterns, and Wareworks still crafts nothing.
 * <p>
 * The feeder sits on the station's back, faces along the rack wall, and the Crafter stands behind the next rack
 * position with its output facing the rack, where a warehouse input takes the result. Unlike
 * {@link ProductionGameTests}, nobody plays the machine here: the real Crafter consumes the ingredients and makes the
 * product. The census therefore cannot stay constant; it must stay on the <b>craft ledger</b> — the start plus a whole
 * number of crafts of the pattern, and nothing else ({@link #assertLedger}) — on every tick, so a run that lost or
 * copied an item between the buffer, the grid and the input fails the moment it happens.
 * <p>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails. Right rack plane:
 * the production station at 0, a warehouse input at 1, a warehouse output at 2 (the destination of the orders). Behind
 * the station (z = 5) the feeder, facing +x, with a creative motor on top; behind the input the Crafter, its output
 * facing the input. Left rack plane: chests of coal at 0, sticks at 1 and logs at 2, empty chests at 3 and 4.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CrafterFeederGameTests {
    /** Config tests get their own batch: the tests of one batch run at the same time ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksCrafterFeederConfig";
    /** The short order timeout gets a batch of its own, so it cannot cut a run of the rotation test short. */
    private static final String TIMEOUT_BATCH = "wareworksCrafterFeederTimeout";
    private static final int SHORT_ORDER_TIMEOUT = 200;

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int CRANE_RPM = 128;
    /** 64 RPM gives the fastest pace a feeder has: one run every {@value FeederCycle#MIN_RUN_TICKS} ticks. */
    private static final int FEEDER_RPM = 64;

    private static final RackPosition STATION_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition COAL_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition STICK_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition LOG_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition SPARE_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition SPARE_RACK_2 = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey HONEY_BOTTLE = ItemKey.of(Items.HONEY_BOTTLE);
    private static final ItemKey GLASS_BOTTLE = ItemKey.of(Items.GLASS_BOTTLE);
    private static final ItemKey HONEY_BLOCK = ItemKey.of(Items.HONEY_BLOCK);

    /** Grid cells of the vanilla torch: coal on top of a stick, in the left column. */
    private static final int TOP_LEFT = 0;
    private static final int MIDDLE_LEFT = 3;
    private static final int TORCHES_PER_RUN = 4;
    private static final int PLANKS_PER_RUN = 4;
    private static final int STICKS_PER_RUN = 4;
    private static final int STOCK = 16;
    /** Torches ordered in most tests: two runs, laid as one batch. */
    private static final int ORDERED_TORCHES = 8;
    private static final int RUNS = ORDERED_TORCHES / TORCHES_PER_RUN;

    /** One run of each pattern, as census changes. */
    private static final Map<ItemKey, Long> TORCH_RUN = Map.of(COAL, -1L, STICK, -1L, TORCH, (long) TORCHES_PER_RUN);
    private static final Map<ItemKey, Long> PLANK_RUN = Map.of(LOG, -1L, PLANK, (long) PLANKS_PER_RUN);
    private static final Map<ItemKey, Long> STICK_RUN = Map.of(PLANK, -2L, STICK, (long) STICKS_PER_RUN);
    /** A honey block from four honey bottles: the four empty bottles leave the Crafter with the block. */
    private static final Map<ItemKey, Long> HONEY_RUN = Map.of(HONEY_BOTTLE, -4L, GLASS_BOTTLE, 4L, HONEY_BLOCK, 1L);
    /** The 2 x 2 cells of the honey block. */
    private static final int[] HONEY_CELLS = {0, 1, 3, 4};
    private static final int HONEY_STOCK = 16;
    /** Runs of the big plank order: one full batch of a stack and a second one of four. */
    private static final int BIG_RUNS = 68;
    private static final int FULL_BATCH = 64;
    private static final int BIG_TIMEOUT_TICKS = 3600;

    private static final int TIMEOUT_TICKS = 600;
    private static final int LONG_TIMEOUT_TICKS = 1800;
    private static final int CHAIN_TIMEOUT_TICKS = 2400;
    /** Long enough for several feeder polls and many pulse windows, so "nothing happened" means something. */
    private static final int HOLD_TICKS = 60;
    private static final int SETTLE_TICKS = 20;

    private CrafterFeederGameTests() {
    }

    // --- the whole loop ----------------------------------------------------------------------------------------------

    /**
     * Eight torches ordered at the output: the crane brings two coal and two sticks to the station, the feeder lays both
     * runs as one batch into the Crafter's slots 0 and 3 and locks the seven it leaves empty, pulses twice, and the
     * Crafter puts 4 torches per run into the input. The order completes, the request is served, the Crafter gets its
     * slots back, and the census is on the craft ledger on every tick. Every rising edge of the feeder is at least
     * {@value FeederCycle#MIN_RUN_TICKS} ticks after the last one, and there is exactly one per run.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederTorch(GameTestHelper helper) {
        helper.assertValueEqual(BlockStressValues.getImpact(WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.get()),
                WareworksConfig.crafterFeederStressImpact(), "registered stress impact follows the config");
        Layout layout = build(helper, true);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        List<Long> edges = new ArrayList<>();
        boolean[] wasPowering = {false};
        boolean[] sawLaid = {false};
        helper.onEachTick(() -> {
            assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "while the Crafter crafts torches");
            boolean powering = layout.powering(helper);
            if (powering && !wasPowering[0]) {
                long now = helper.getLevel().getGameTime();
                if (!edges.isEmpty() && now - edges.getLast() < FeederCycle.MIN_RUN_TICKS)
                    helper.fail("two rising edges " + (now - edges.getLast()) + " ticks apart");
                edges.add(now);
            }
            wasPowering[0] = powering;
            CrafterBlockEntity crafter = layout.crafter(helper);
            if (!crafter.getItem(TOP_LEFT).isEmpty()) {
                sawLaid[0] = true;
                assertTorchLocks(helper, crafter);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> {
                    CrafterBlockEntity crafter = layout.crafter(helper);
                    helper.assertValueEqual(crafter.getItem(TOP_LEFT).getCount(), RUNS, "both runs of coal laid");
                    helper.assertValueEqual(crafter.getItem(MIDDLE_LEFT).getCount(), RUNS, "both runs of sticks laid");
                    helper.assertTrue(crafter.getItem(TOP_LEFT).is(Items.COAL), "the coal is in slot 0");
                    helper.assertTrue(crafter.getItem(MIDDLE_LEFT).is(Items.STICK), "the sticks are in slot 3");
                    helper.assertValueEqual(layout.feeder(helper).runsToLay(), 0, "everything laid in one batch");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, COAL), 0L, "buffer emptied");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "the torches reached the output");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                    helper.assertValueEqual(layout.aisle().controller().openRequestCount(), 0, "request served");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder is idle");
                    helper.assertTrue(layout.feeder(helper).feedOrder().isEmpty(), "and has let go of the order");
                })
                .thenExecute(() -> {
                    helper.assertTrue(sawLaid[0], "the grid was seen holding the batch");
                    helper.assertValueEqual(edges.size(), RUNS, "one rising edge per run");
                    helper.assertValueEqual(assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "at the end")
                            .getFirst(), RUNS, "exactly the ordered runs were crafted");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                .thenSucceed();
    }

    /**
     * A recursive order through <b>one</b> feeder and <b>one</b> Crafter: torches ordered with nothing but logs and
     * coal in the racks. The warehouse plans three steps (log to planks, planks to sticks, coal and stick to torches),
     * the Crafter crafts each in turn, and every intermediate passes through a rack between two steps, exactly as with
     * any other machine.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void crafterFeederChain(GameTestHelper helper) {
        Layout layout = buildEmpty(helper, true);
        AisleFixture aisle = layout.aisle();
        aisle.storage(COAL_RACK, COAL.toStack(STOCK));
        aisle.storage(STICK_RACK);
        aisle.storage(LOG_RACK, LOG.toStack(STOCK));
        aisle.storage(SPARE_RACK);
        aisle.storage(SPARE_RACK_2);
        WarehouseProductionBlockEntity station = layout.station(helper);
        helper.assertTrue(station.setPatternEntry(0, TOP_LEFT, LOG, 1), "the log");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN), "planks");
        helper.assertTrue(station.setPatternEntry(1, TOP_LEFT, PLANK, 1), "a plank");
        helper.assertTrue(station.setPatternEntry(1, MIDDLE_LEFT, PLANK, 1), "a plank below it");
        helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, STICK, STICKS_PER_RUN), "sticks");
        setTorchPattern(helper, station, 2);
        Map<ItemKey, Long> start = ItemCensus.of(COAL, STOCK, LOG, STOCK);
        helper.onEachTick(() -> assertLedger(helper, start, 1, List.of(PLANK_RUN, STICK_RUN, TORCH_RUN),
                "while the chain runs"));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) STOCK, "logs in stock");
                    helper.assertValueEqual(aisle.controller().countOf(COAL), (long) STOCK, "coal in stock");
                    helper.assertValueEqual(station.activePatterns().size(), 3, "all three patterns are recipes");
                })
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), TORCH,
                            TORCHES_PER_RUN);
                    helper.assertTrue(result.isAccepted(), "the chain is accepted: " + result);
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 3, "one order per step");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, TORCH), (long) TORCHES_PER_RUN,
                            "the torches reached the output");
                    for (ProductionOrder<ItemKey, RackPosition> order : aisle.controller().productionOrders())
                        helper.assertValueEqual(order.state(), ProductionOrderState.COMPLETE,
                                "every step completed: " + order.result());
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder is idle");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLedger(helper, start, 1, List.of(PLANK_RUN, STICK_RUN, TORCH_RUN),
                            "at the end"), List.of(1, 1, 1), "one run of every step");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                })
                .thenSucceed();
    }

    // --- the recipe check --------------------------------------------------------------------------------------------

    /**
     * While a feeder is attached, a pattern the Crafter would not craft is shown red with its reason and is never
     * planned: dirt to a diamond (no recipe), a torch with two coal in one cell (cells hold exactly one item), and a
     * torch pattern that claims eight torches (the recipe makes four, and says so). Ordering any of them is refused, so
     * the crane fetches nothing and nothing is wasted. The one valid slot is planned as usual, the screen state carries
     * the checks through its own codec, and without the feeder nothing is checked at all.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void crafterFeederInvalidPattern(GameTestHelper helper) {
        Layout layout = build(helper, true);
        WarehouseProductionBlockEntity station = layout.station(helper);
        helper.assertTrue(station.setPatternEntry(0, TOP_LEFT, DIRT, 1), "dirt");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, DIAMOND, 1), "to a diamond");
        helper.assertTrue(station.setPatternEntry(1, TOP_LEFT, COAL, 2), "two coal in one cell");
        helper.assertTrue(station.setPatternEntry(1, MIDDLE_LEFT, STICK, 1), "a stick");
        helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, TORCH, TORCHES_PER_RUN), "torch");
        helper.assertTrue(station.setPatternEntry(2, TOP_LEFT, COAL, 1), "coal");
        helper.assertTrue(station.setPatternEntry(2, MIDDLE_LEFT, STICK, 1), "a stick");
        helper.assertTrue(station.setPatternEntry(2, ProductionPatterns.RESULT_ENTRY, TORCH, 2 * TORCHES_PER_RUN),
                "claims eight torches");
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, start, "nothing moves for a refused pattern"));
        Player player = playerAt(helper, layout.aisle().rackPos(STATION_RACK));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> {
                    helper.assertValueEqual(station.crafterCheck(0).check(), CrafterPatternCheck.NOT_A_RECIPE, "slot 1");
                    helper.assertValueEqual(station.crafterCheck(1).check(), CrafterPatternCheck.CELL_NOT_ONE, "slot 2");
                    CrafterRecipeCheck.Result differs = station.crafterCheck(2);
                    helper.assertValueEqual(differs.check(), CrafterPatternCheck.RESULT_DIFFERS, "slot 3");
                    helper.assertValueEqual(differs.recipeResult().map(ItemKey::of), Optional.of(TORCH),
                            "the recipe's real result");
                    helper.assertValueEqual(differs.recipeResult().map(ItemStack::getCount),
                            Optional.of(TORCHES_PER_RUN), "the recipe's real amount");
                    helper.assertValueEqual(station.crafterCheck(3).check(), CrafterPatternCheck.INCOMPLETE,
                            "an empty slot is no pattern");
                    helper.assertTrue(station.activePatterns().isEmpty(), "no refused pattern reaches the planner");
                    WarehouseControllerBlockEntity controller = layout.aisle().controller();
                    helper.assertFalse(controller.producibleKeys().contains(DIAMOND), "no diamond is offered");
                    helper.assertFalse(controller.producibleKeys().contains(TORCH), "no torch is offered");
                    helper.assertFalse(controller.request(layout.aisle().absoluteRackPos(OUTPUT_RACK), DIAMOND, 1)
                            .isAccepted(), "a diamond cannot be ordered");
                    helper.assertFalse(controller.request(layout.aisle().absoluteRackPos(OUTPUT_RACK), TORCH,
                            TORCHES_PER_RUN).isAccepted(), "nor a torch");
                    helper.assertTrue(controller.productionOrders().isEmpty(), "no order was created");

                    ProductionScreenState state = openMenu(player, station).screenState();
                    helper.assertTrue(state.feeder(), "the screen knows the feeder");
                    helper.assertValueEqual(state.check(0).check(), CrafterPatternCheck.NOT_A_RECIPE, "screen slot 1");
                    helper.assertValueEqual(state.check(1).check(), CrafterPatternCheck.CELL_NOT_ONE, "screen slot 2");
                    helper.assertValueEqual(state.check(2).recipeResult().map(ProductionScreenState.EntryView::count),
                            Optional.of(TORCHES_PER_RUN), "the screen names the real amount");
                    helper.assertValueEqual(roundTrip(helper, state).state(), state,
                            "the checks survive the payload's codec");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, COAL), 0L, "no coal fetched");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, DIRT), 0L, "no dirt fetched");
                    assertGridEmpty(helper, layout.crafter(helper));
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder waits");
                    // The fourth slot is a real torch: the filter is per slot, not per station.
                    setTorchPattern(helper, station, 3);
                    helper.assertValueEqual(station.crafterCheck(3).check(), CrafterPatternCheck.VALID, "a recipe");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "only the valid slot is planned");
                    helper.assertTrue(layout.aisle().controller().producibleKeys().contains(TORCH), "torch offered");
                    // Without the feeder nothing is checked: every complete pattern is used as it always was.
                    helper.setBlock(layout.feederPos(), Blocks.AIR);
                    helper.assertValueEqual(station.crafterCheck(0).check(), CrafterPatternCheck.NOT_CHECKED,
                            "nothing is checked without a feeder");
                    helper.assertValueEqual(station.activePatterns().size(), 4, "all four slots are patterns again");
                    ProductionScreenState state = openMenu(player, station).screenState();
                    helper.assertFalse(state.feeder(), "the screen knows the feeder is gone");
                    helper.assertFalse(state.check(0).check().refused(), "and draws nothing red");
                })
                .thenSucceed();
    }

    /**
     * Two orders of two different patterns at one station: the second is laid only into an empty grid after the first
     * has been crafted, so the items of two orders never lie in the Crafter together. Checked on every tick, and the test
     * requires a tick in which the first order lay in the grid while the second was already delivered and waiting, so
     * the two really overlapped.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederTwoOrders(GameTestHelper helper) {
        // The motor comes once both orders are delivered, so the second one is certainly waiting while the first is laid.
        Layout layout = build(helper, false);
        WarehouseProductionBlockEntity station = layout.station(helper);
        setTorchPattern(helper, station, 0);
        helper.assertTrue(station.setPatternEntry(1, TOP_LEFT, LOG, 1), "the log");
        helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN), "planks");
        Map<ItemKey, Long> start = stocked();
        Set<UUID> served = new HashSet<>();
        boolean[] overlapped = {false};
        helper.onEachTick(() -> {
            assertLedger(helper, start, RUNS, List.of(TORCH_RUN, PLANK_RUN), "while two orders run");
            Set<ItemKey> inGrid = new HashSet<>();
            CrafterBlockEntity crafter = layout.crafter(helper);
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = crafter.getItem(slot);
                if (!stack.isEmpty())
                    inGrid.add(ItemKey.of(stack));
            }
            if (inGrid.contains(LOG) && (inGrid.contains(COAL) || inGrid.contains(STICK)))
                helper.fail("two orders mixed in the Crafter: " + inGrid);
            Optional<UUID> fed = layout.feeder(helper).feedOrder();
            fed.ifPresent(served::add);
            if (fed.isPresent() && !inGrid.isEmpty()) {
                for (ProductionOrder<ItemKey, RackPosition> order : layout.aisle().controller().productionOrders()) {
                    if (!order.id().equals(fed.get()) && order.state() == ProductionOrderState.DELIVERED)
                        overlapped[0] = true;
                }
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> {
                    order(helper, layout, TORCH, ORDERED_TORCHES);
                    order(helper, layout, PLANK, RUNS * PLANKS_PER_RUN);
                    helper.assertValueEqual(layout.aisle().controller().productionOrders().size(), 2, "two orders");
                })
                .thenWaitUntil(() -> {
                    for (ProductionOrder<ItemKey, RackPosition> order : layout.aisle().controller().productionOrders())
                        helper.assertValueEqual(order.state(), ProductionOrderState.DELIVERED, "both delivered");
                })
                .thenExecute(() -> layout.placeFeederMotor(helper))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "the torches arrived");
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, PLANK),
                            (long) RUNS * PLANKS_PER_RUN, "the planks arrived");
                    for (ProductionOrder<ItemKey, RackPosition> order : layout.aisle().controller().productionOrders())
                        helper.assertValueEqual(order.state(), ProductionOrderState.COMPLETE, "order completed");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(served.size(), 2, "the feeder served both orders, one after the other");
                    helper.assertTrue(overlapped[0], "the second order was delivered while the first lay in the grid");
                    helper.assertValueEqual(assertLedger(helper, start, RUNS, List.of(TORCH_RUN, PLANK_RUN),
                            "at the end"), List.of(RUNS, RUNS), "both orders crafted in full");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                })
                .thenSucceed();
    }

    // --- rotation ----------------------------------------------------------------------------------------------------

    /**
     * Without rotation the feeder does nothing: the order's ingredients are delivered and stay in the station, the grid
     * stays empty and the goggles say why. The motor's first turn starts the run.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederNeedsRotation(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the motor"));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.NO_ROTATION, "status");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, COAL), (long) RUNS,
                            "the coal stays in the station");
                    assertGridEmpty(helper, layout.crafter(helper));
                    layout.placeFeederMotor(helper);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "with rotation the torches are made");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                })
                .thenSucceed();
    }

    /** With {@code crafterFeederRequiresRotation = false} the feeder works without a motor (ADR-067 D9). */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederRotationOptional(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.crafterFeederRequiresRotation, false);
        helper.assertValueEqual(BlockStressValues.getImpact(WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.get()), 0.0,
                "a feeder that needs no rotation takes no stress");
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "without a motor"));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> {
                    helper.assertTrue(layout.feeder(helper).running(), "the feeder runs without rotation");
                    order(helper, layout, TORCH, ORDERED_TORCHES);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "the torches arrived");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                })
                .thenSucceed();
    }

    /** Restores the config even when a config-batch test fails before its own restore. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreCrafterFeederConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- blocked runs ------------------------------------------------------------------------------------------------

    /**
     * A full warehouse input in front of the Crafter's output: the vanilla Crafter would drop the torches on the
     * floor, so the feeder does not pulse, says why, and nothing is dropped. Once the crane has emptied the input, the
     * run goes on and the order completes. The crane is stopped (its motor removed) while the input is filled by hand.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederBlockedOutput(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the full input"));
        BlockPos craneMotor = new BlockPos(1, WareworksGameTests.FLOOR_Y, AISLE_Z);
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.DELIVERED,
                            "the ingredients were delivered");
                    layout.aisle().assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.setBlock(craneMotor, Blocks.AIR);
                    IItemHandler input = layout.aisle().handlerAt(layout.aisle().rackPos(INPUT_RACK));
                    int filled = fill(input, DIRT);
                    helper.assertTrue(filled > 0, "the input took dirt");
                    ItemCensus.change(start, DIRT, filled);
                    layout.placeFeederMotor(helper);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(), RUNS, "laid");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.OUTPUT_FULL, "status");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.OUTPUT_FULL, "still waiting");
                    helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(), RUNS,
                            "no run was crafted into a full input");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                    helper.assertFalse(layout.powering(helper), "and no signal is given");
                    layout.aisle().placeMotor();
                    layout.aisle().motor().generatedSpeed.setValue(CRANE_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "the torches arrived once the input had room");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was ever dropped");
                })
                .thenSucceed();
    }

    /**
     * Something else powers the Crafter (a redstone block on top of it): a pulse of the feeder would give no rising
     * edge, so it gives none, says why, and nothing is crafted until the block is gone.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederExternalSignal(GameTestHelper helper) {
        Layout layout = build(helper, true);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the other signal"));
        BlockPos above = layout.crafterPos().above();
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(above, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(), RUNS, "laid");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.EXTERNAL_SIGNAL, "status");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(), RUNS,
                            "nothing was crafted");
                    helper.setBlock(above, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.COMPLETE, "the order completed once the other signal was gone"))
                .thenSucceed();
    }

    /**
     * A feeder between the backs of two production stations cannot tell whose patterns it lays: it refuses, neither
     * station checks its patterns, and once the second station is gone it serves the first.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederTwoStations(GameTestHelper helper) {
        Layout layout = build(helper, true);
        WarehouseProductionBlockEntity station = layout.station(helper);
        setTorchPattern(helper, station, 0);
        BlockPos second = layout.feederPos().west();
        helper.setBlock(second, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.WEST));
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the second station"));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertStocked(helper, layout);
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.TWO_STATIONS, "status");
                    helper.assertTrue(station.attachedFeeder().isEmpty(), "the feeder serves neither station");
                    helper.assertValueEqual(station.crafterCheck(0).check(), CrafterPatternCheck.NOT_CHECKED,
                            "so nothing is checked");
                })
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    assertGridEmpty(helper, layout.crafter(helper));
                    helper.setBlock(second, Blocks.AIR);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(station.attachedFeeder().isPresent(), "now the feeder serves the station");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "and the order completed");
                })
                .thenSucceed();
    }

    /**
     * A player takes a coal out of the laid grid: the grid no longer holds the layout, so the feeder does not pulse a
     * craft that would make something the order never planned. Putting the coal back lets the run go on.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederGridDisturbed(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the player's hand"));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                // Rotation is configured to need a motor and there is none yet, so nothing is laid before this.
                .thenExecute(() -> layout.placeFeederMotor(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(),
                        RUNS, "laid"))
                .thenExecute(() -> {
                    // The player's hand: one coal out, before the first pulse (it is at least one run away).
                    ItemStack taken = layout.crafter(helper).removeItem(TOP_LEFT, 1);
                    helper.assertValueEqual(taken.getCount(), 1, "a coal taken");
                    ItemCensus.change(start, COAL, -1);
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    CrafterBlockEntity crafter = layout.crafter(helper);
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.GRID_DISTURBED, "status");
                    helper.assertValueEqual(crafter.getItem(MIDDLE_LEFT).getCount(), RUNS, "no stick was crafted");
                    crafter.setItem(TOP_LEFT, COAL.toStack(RUNS));
                    ItemCensus.change(start, COAL, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.COMPLETE, "with the coal back the order completed"))
                .thenExecute(() -> assertNoSlotLocked(helper, layout.crafter(helper)))
                .thenSucceed();
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /**
     * A reload in the middle of a real pulse: the feeder and the station are replaced by copies loaded from their saves
     * while the feeder's signal is high. The edge has been given, so the craft it scheduled still happens; the copy
     * lowers the signal on its very first tick, reads the grid, crafts the second run exactly once and gives the Crafter
     * its slots back, and the Crafter is no longer triggered at the end. The census stays on the ledger throughout.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederReload(GameTestHelper helper) {
        Layout layout = build(helper, true);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the reload"));
        UUID[] orderId = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertTrue(layout.powering(helper), "a real pulse is under way"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseCrafterFeederBlockEntity feeder = layout.feeder(helper);
                    orderId[0] = feeder.feedOrder().orElse(null);
                    helper.assertTrue(orderId[0] != null, "the feeder serves the order");
                    WarehouseProductionBlockEntity station = layout.station(helper);
                    CompoundTag feederTag = feeder.saveWithFullMetadata(registries);
                    CompoundTag stationTag = station.saveWithFullMetadata(registries);
                    helper.assertTrue(layout.powering(helper), "the save is taken with the signal high");
                    // A reload unloads the old block entities rather than removing them: a removed feeder would give
                    // the Crafter its slots back, which is right for a broken feeder and wrong for a saved one.
                    feeder.onChunkUnloaded();
                    station.onChunkUnloaded();
                    level.setBlockEntity(loadCopy(helper, feeder, feederTag,
                            WarehouseCrafterFeederBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, station, stationTag, WarehouseProductionBlockEntity.class));
                    helper.assertTrue(feeder.isRemoved() && station.isRemoved(), "both block entities replaced");
                })
                .thenExecuteAfter(1, () -> {
                    WarehouseCrafterFeederBlockEntity copy = layout.feeder(helper);
                    helper.assertValueEqual(copy.feedOrder(), Optional.of(orderId[0]), "the copy serves the order");
                    helper.assertFalse(layout.powering(helper), "the saved signal came down on the first tick");
                    assertTorchLocks(helper, layout.crafter(helper));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH), (long) ORDERED_TORCHES,
                            "the torches arrived");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the reloaded run completed the order");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "at the end")
                            .getFirst(), RUNS, "no run was lost or crafted twice");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertFalse(layout.crafter(helper).getBlockState().getValue(CrafterBlock.TRIGGERED),
                            "the Crafter is no longer triggered");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(layout.feeder(helper).status(),
                        FeederStatus.IDLE, "idle again"))
                .thenSucceed();
    }

    /**
     * Two orders of the <b>same</b> pattern at one station, the case the item-kind check of
     * {@link #crafterFeederTwoOrders} cannot see: two separate torch requests. The feeder serves them one after the other,
     * switching from one order to the next only through a tick in which it is idle with an empty grid, lays exactly each
     * order's own runs, and both orders complete instead of timing out.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederTwoSameOrders(GameTestHelper helper) {
        Layout layout = build(helper, true);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        List<UUID> sequence = new ArrayList<>();
        boolean[] idleWithEmptyGrid = {false};
        helper.onEachTick(() -> {
            assertLedger(helper, start, 2 * RUNS, List.of(TORCH_RUN), "while two torch orders run");
            CrafterBlockEntity crafter = layout.crafter(helper);
            Optional<UUID> fed = layout.feeder(helper).feedOrder();
            if (fed.isEmpty()) {
                if (gridEmpty(crafter))
                    idleWithEmptyGrid[0] = true;
                return;
            }
            if (!sequence.isEmpty() && sequence.getLast().equals(fed.get()))
                return;
            if (!sequence.isEmpty() && !idleWithEmptyGrid[0])
                helper.fail("the feeder took the next order without an idle tick with an empty grid");
            helper.assertValueEqual(crafter.getItem(TOP_LEFT).getCount(), RUNS, "a new order lays its own runs only");
            sequence.add(fed.get());
            idleWithEmptyGrid[0] = false;
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> {
                    order(helper, layout, TORCH, ORDERED_TORCHES);
                    order(helper, layout, TORCH, ORDERED_TORCHES);
                    helper.assertValueEqual(layout.aisle().controller().productionOrders().size(), 2, "two orders");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, TORCH),
                            (long) 2 * ORDERED_TORCHES, "the torches of both requests arrived");
                    for (ProductionOrder<ItemKey, RackPosition> order : layout.aisle().controller().productionOrders())
                        helper.assertValueEqual(order.state(), ProductionOrderState.COMPLETE, "order completed");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder is idle");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(sequence.size(), 2, "two orders served one after the other");
                    helper.assertFalse(sequence.get(0).equals(sequence.get(1)), "two different orders");
                    helper.assertValueEqual(assertLedger(helper, start, 2 * RUNS, List.of(TORCH_RUN), "at the end")
                            .getFirst(), 2 * RUNS, "exactly the runs of both orders were crafted");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                })
                .thenSucceed();
    }

    /**
     * An order larger than a stack: {@value #BIG_RUNS} runs of one log to four planks. The first batch is a full stack
     * of {@value #FULL_BATCH} logs, the order waits for its result from that batch on, and the second batch of four is
     * laid only into an empty grid (a batch laid onto leftovers would show more than four). The ledger ends on exactly
     * {@value #BIG_RUNS} runs and the order completes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = BIG_TIMEOUT_TICKS)
    public static void crafterFeederBigOrder(GameTestHelper helper) {
        Layout layout = bigOrderLayout(helper);
        Map<ItemKey, Long> start = ItemCensus.of(LOG, BIG_RUNS);
        List<Integer> batches = new ArrayList<>();
        int[] toLayBefore = {-1};
        helper.onEachTick(() -> {
            assertLedger(helper, start, BIG_RUNS, List.of(PLANK_RUN), "while a big order runs");
            CrafterBlockEntity crafter = layout.crafter(helper);
            int inSlot = crafter.getItem(TOP_LEFT).getCount();
            if (inSlot > FULL_BATCH)
                helper.fail("a Crafter slot holds " + inSlot + " logs, more than a stack");
            WarehouseCrafterFeederBlockEntity feeder = layout.feeder(helper);
            int toLay = feeder.feedOrder().isPresent() ? feeder.runsToLay() : -1;
            if (toLay >= 0 && toLay != toLayBefore[0] && (toLayBefore[0] < 0 || toLay < toLayBefore[0])) {
                batches.add(inSlot);
                if (batches.size() == 1) {
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, layout);
                    helper.assertValueEqual(order.state(), ProductionOrderState.WAITING_FOR_RESULT,
                            "the order waits for its result from the first batch on");
                }
            }
            toLayBefore[0] = toLay;
        });
        helper.startSequence()
                .thenWaitUntil(() -> assertBigStock(helper, layout))
                .thenExecute(() -> order(helper, layout, PLANK, BIG_RUNS * PLANKS_PER_RUN))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, PLANK),
                            (long) BIG_RUNS * PLANKS_PER_RUN, "the planks reached the output");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder is idle");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(batches, List.of(FULL_BATCH, BIG_RUNS - FULL_BATCH),
                            "a full stack first, then the rest into an empty grid");
                    helper.assertValueEqual(assertLedger(helper, start, BIG_RUNS, List.of(PLANK_RUN), "at the end")
                            .getFirst(), BIG_RUNS, "every run crafted exactly once");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                .thenSucceed();
    }

    /**
     * The feeder is broken and placed again between two batches of a big order. The station remembers that
     * {@value #FULL_BATCH} runs were laid, so the new feeder loads only the four that are left, the order completes
     * instead of timing out with ingredients delivered (which would arm the safety stop of an automatic order), and no
     * run is laid twice. The pause between the batches is made by a player holding the last four logs for a while.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = BIG_TIMEOUT_TICKS)
    public static void crafterFeederReplacedBetweenBatches(GameTestHelper helper) {
        Layout layout = bigOrderLayout(helper);
        Map<ItemKey, Long> start = ItemCensus.of(LOG, BIG_RUNS);
        helper.onEachTick(() -> assertLedger(helper, start, BIG_RUNS, List.of(PLANK_RUN), "around the new feeder"));
        int held = BIG_RUNS - FULL_BATCH;
        helper.startSequence()
                .thenWaitUntil(() -> assertBigStock(helper, layout))
                .thenExecute(() -> order(helper, layout, PLANK, BIG_RUNS * PLANKS_PER_RUN))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.feeder(helper).runsToLay(), held, "the first batch was laid");
                    helper.assertValueEqual(layout.station(helper).feederLaidRuns(onlyOrder(helper, layout).id()),
                            FULL_BATCH, "the station counts the laid runs");
                })
                .thenExecute(() -> {
                    // The player's hand: the logs of the second batch, out of the station for a while.
                    ItemStack taken = takeFromStation(helper, layout, LOG, held);
                    helper.assertValueEqual(taken.getCount(), held, "the last logs taken out of the station");
                    ItemCensus.change(start, LOG, -held);
                })
                .thenWaitUntil(() -> {
                    assertGridEmpty(helper, layout.crafter(helper));
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.MISSING_INGREDIENTS,
                            "between the batches");
                })
                .thenExecute(() -> {
                    helper.setBlock(layout.feederPos(), Blocks.AIR);
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.setBlock(layout.feederPos(), feederState());
                    ItemStack rest = layout.station(helper).insert(LOG.toStack(held), false);
                    helper.assertTrue(rest.isEmpty(), "the logs went back into the station");
                    ItemCensus.change(start, LOG, held);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, PLANK),
                            (long) BIG_RUNS * PLANKS_PER_RUN, "the planks reached the output");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed with the new feeder");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLedger(helper, start, BIG_RUNS, List.of(PLANK_RUN), "at the end")
                            .getFirst(), BIG_RUNS, "no run was laid twice");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, LOG), 0L, "no log left behind");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                })
                .thenSucceed();
    }

    /**
     * Patterns with a remainder: four honey bottles make a honey block and leave four empty bottles, which go out of the
     * Crafter's output with the block, into the input and into the racks. Nothing is dropped.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederRemainder(GameTestHelper helper) {
        Layout layout = honeyLayout(helper, true);
        Map<ItemKey, Long> start = ItemCensus.of(HONEY_BOTTLE, HONEY_STOCK);
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(HONEY_RUN), "while honey blocks are made"));
        helper.startSequence()
                .thenWaitUntil(() -> assertHoneyStock(helper, layout))
                .thenExecute(() -> order(helper, layout, HONEY_BLOCK, RUNS))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.aisle().stationCount(OUTPUT_RACK, HONEY_BLOCK), (long) RUNS,
                            "the honey blocks reached the output");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                    helper.assertValueEqual(layout.aisle().controller().countOf(GLASS_BOTTLE), (long) RUNS * 4,
                            "the empty bottles are in the racks");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLedger(helper, start, RUNS, List.of(HONEY_RUN), "at the end")
                            .getFirst(), RUNS, "both runs crafted");
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                .thenSucceed();
    }

    /**
     * The input in front of the Crafter has room for the honey block but not for the four empty bottles: the Crafter
     * would drop them, so the feeder does not pulse and says why. Nothing is dropped and nothing is crafted.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederRemainderBlocked(GameTestHelper helper) {
        Layout layout = honeyLayout(helper, false);
        Map<ItemKey, Long> start = ItemCensus.of(HONEY_BOTTLE, HONEY_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, start, "nothing is crafted into a full input"));
        BlockPos craneMotor = new BlockPos(1, WareworksGameTests.FLOOR_Y, AISLE_Z);
        helper.startSequence()
                .thenWaitUntil(() -> assertHoneyStock(helper, layout))
                .thenExecute(() -> order(helper, layout, HONEY_BLOCK, RUNS))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.DELIVERED,
                            "the honey was delivered");
                    layout.aisle().assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.setBlock(craneMotor, Blocks.AIR);
                    IItemHandler input = layout.aisle().handlerAt(layout.aisle().rackPos(INPUT_RACK));
                    int last = input.getSlots() - 1;
                    for (int slot = 0; slot < last; slot++) {
                        helper.assertTrue(input.insertItem(slot, DIRT.toStack(64), false).isEmpty(), "dirt in " + slot);
                        ItemCensus.change(start, DIRT, 64);
                    }
                    int honeyBlocks = HONEY_BLOCK.getMaxStackSize() - 1;
                    helper.assertTrue(input.insertItem(last, HONEY_BLOCK.toStack(honeyBlocks), false).isEmpty(),
                            "honey blocks in the last slot");
                    ItemCensus.change(start, HONEY_BLOCK, honeyBlocks);
                    helper.assertTrue(ItemHandlerHelper.insertItemStacked(input, HONEY_BLOCK.toStack(1), true)
                            .isEmpty(), "room for the block");
                    helper.assertFalse(ItemHandlerHelper.insertItemStacked(input, GLASS_BOTTLE.toStack(1), true)
                            .isEmpty(), "no room for a bottle");
                    layout.placeFeederMotor(helper);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.crafter(helper).getItem(0).getCount(), RUNS, "laid");
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.OUTPUT_FULL, "status");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.OUTPUT_FULL, "still waiting");
                    for (int cell : HONEY_CELLS)
                        helper.assertValueEqual(layout.crafter(helper).getItem(cell).getCount(), RUNS,
                                "no run was crafted, cell " + cell);
                    helper.assertFalse(layout.powering(helper), "and no signal is given");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                .thenSucceed();
    }

    /**
     * The pattern is edited into one the Crafter would not craft (it now claims eight torches) after its order's
     * ingredients were delivered. The feeder skips that order: nothing is laid, no ingredient is wasted, and the order
     * times out with its ingredients still in the station's buffer, as any order whose machine does nothing.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederInvalidAfterDelivery(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        Layout layout = build(helper, false);
        WarehouseProductionBlockEntity station = layout.station(helper);
        setTorchPattern(helper, station, 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, start, "nothing is crafted for a refused pattern"));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecute(() -> {
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, TORCH,
                            2 * TORCHES_PER_RUN), "the pattern now claims eight torches");
                    helper.assertValueEqual(station.crafterCheck(0).check(), CrafterPatternCheck.RESULT_DIFFERS,
                            "and is refused");
                    layout.placeFeederMotor(helper);
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    assertGridEmpty(helper, layout.crafter(helper));
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "the feeder waits");
                    helper.assertTrue(layout.feeder(helper).feedOrder().isEmpty(), "and loaded nothing");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, COAL), (long) RUNS,
                            "the coal stays in the station");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.TIMED_OUT, "the order gave up"))
                .thenExecute(() -> {
                    assertGridEmpty(helper, layout.crafter(helper));
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, COAL), (long) RUNS,
                            "the delivered coal is still in the buffer");
                    helper.assertValueEqual(layout.aisle().stationCount(STATION_RACK, STICK), (long) RUNS,
                            "and so are the sticks");
                })
                .thenSucceed();
    }

    /** Restores the order timeout even when the timeout test fails before its own restore. */
    @AfterBatch(batch = TIMEOUT_BATCH)
    public static void restoreCrafterFeederTimeout(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- broken blocks -----------------------------------------------------------------------------------------------

    /**
     * The feeder is broken while the batch lies in the Crafter: the Crafter gets all nine slots back at once, the laid
     * items stay in its grid for the player, nothing is dropped, and nothing is crafted any more.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederBrokenMidOrder(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the broken feeder"));
        int[] settled = new int[2];
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecute(() -> layout.placeFeederMotor(helper))
                .thenWaitUntil(() -> helper.assertTrue(layout.crafter(helper).getItem(TOP_LEFT).getCount() > 0,
                        "the batch was laid"))
                .thenExecute(() -> {
                    helper.setBlock(layout.feederPos(), Blocks.AIR);
                    CrafterBlockEntity crafter = layout.crafter(helper);
                    for (int slot = 0; slot < 9; slot++)
                        helper.assertFalse(crafter.isSlotDisabled(slot), "the Crafter got slot " + slot + " back");
                    helper.assertTrue(crafter.getItem(TOP_LEFT).is(Items.COAL), "the coal stays in the grid");
                    helper.assertTrue(crafter.getItem(MIDDLE_LEFT).is(Items.STICK), "and so do the sticks");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    // A craft whose edge was given just before the break may still have happened; after that, none.
                    settled[0] = layout.crafter(helper).getItem(TOP_LEFT).getCount();
                    settled[1] = layout.crafter(helper).getItem(MIDDLE_LEFT).getCount();
                    helper.assertValueEqual(settled[0], settled[1], "the grid still holds whole runs");
                    helper.assertTrue(settled[0] > 0, "and at least one of them");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.crafter(helper).getItem(TOP_LEFT).getCount(), settled[0],
                            "nothing was crafted without the feeder");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was ever dropped");
                })
                .thenSucceed();
    }

    /**
     * The Crafter is broken while the batch lies in it: its items drop (counted by the census), the feeder says so and
     * gives no signal. A new, empty Crafter in front is adopted; everything of the order was laid already, so the
     * feeder gives the new Crafter its slots back at once, stays idle and does not lay the order a second time.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederCrafterMissing(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the missing Crafter"));
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecute(() -> layout.placeFeederMotor(helper))
                .thenWaitUntil(() -> helper.assertTrue(layout.crafter(helper).getItem(TOP_LEFT).getCount() > 0,
                        "the batch was laid"))
                .thenExecute(() -> helper.setBlock(layout.crafterPos(), Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.NO_CRAFTER,
                        "status"))
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.NO_CRAFTER, "still missing");
                    helper.assertFalse(layout.powering(helper), "no signal without a Crafter");
                    helper.assertTrue(layout.feeder(helper).feedOrder().isPresent(), "the order is still loaded");
                    layout.placeCrafter(helper);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.IDLE, "idle again");
                    helper.assertTrue(layout.feeder(helper).feedOrder().isEmpty(), "the laid order was let go");
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertTrue(layout.feeder(helper).feedOrder().isEmpty(),
                            "the order is not laid a second time");
                    helper.assertValueEqual(onlyOrder(helper, layout).state(), ProductionOrderState.WAITING_FOR_RESULT,
                            "it waits for a result that lies on the floor");
                })
                .thenSucceed();
    }

    /**
     * The station is broken while the batch lies in the Crafter: its orders are cancelled, the feeder serves nothing,
     * never pulses the grid, and keeps the slots locked only while items lie in it. Once the player has emptied the
     * grid, the Crafter gets all nine slots back.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void crafterFeederStationBrokenMidOrder(GameTestHelper helper) {
        Layout layout = build(helper, false);
        setTorchPattern(helper, layout.station(helper), 0);
        Map<ItemKey, Long> start = stocked();
        helper.onEachTick(() -> assertLedger(helper, start, RUNS, List.of(TORCH_RUN), "around the broken station"));
        int[] settled = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, layout))
                .thenExecute(() -> order(helper, layout, TORCH, ORDERED_TORCHES))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, layout).state(),
                        ProductionOrderState.DELIVERED, "the ingredients were delivered"))
                .thenExecute(() -> layout.placeFeederMotor(helper))
                .thenWaitUntil(() -> helper.assertTrue(layout.crafter(helper).getItem(TOP_LEFT).getCount() > 0,
                        "the batch was laid"))
                .thenExecute(() -> helper.setBlock(layout.aisle().rackPos(STATION_RACK), Blocks.AIR))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(layout.feeder(helper).status(), FeederStatus.NO_STATION, "status");
                    settled[0] = layout.crafter(helper).getItem(TOP_LEFT).getCount();
                    helper.assertTrue(settled[0] > 0, "runs still lie in the grid");
                    assertTorchLocks(helper, layout.crafter(helper));
                })
                .thenExecuteAfter(HOLD_TICKS, () -> {
                    CrafterBlockEntity crafter = layout.crafter(helper);
                    helper.assertValueEqual(crafter.getItem(TOP_LEFT).getCount(), settled[0],
                            "nothing is pulsed without a station");
                    assertTorchLocks(helper, crafter);
                    // The player's hand empties the grid.
                    for (int slot : new int[] {TOP_LEFT, MIDDLE_LEFT}) {
                        ItemStack taken = crafter.removeItem(slot, crafter.getItem(slot).getCount());
                        ItemCensus.change(start, ItemKey.of(taken), -taken.getCount());
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoSlotLocked(helper, layout.crafter(helper));
                    helper.assertTrue(layout.feeder(helper).feedOrder().isEmpty(), "the feeder let go of the order");
                })
                .thenSucceed();
    }

    // --- layout ------------------------------------------------------------------------------------------------------

    /** The aisle, its station, input and output, and the feeder with its Crafter. */
    private record Layout(AisleFixture aisle, BlockPos feederPos, BlockPos crafterPos) {
        WarehouseProductionBlockEntity station(GameTestHelper helper) {
            return aisle.productionAt(STATION_RACK);
        }

        WarehouseCrafterFeederBlockEntity feeder(GameTestHelper helper) {
            WarehouseCrafterFeederBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                    .getNullable(helper.getLevel(), helper.absolutePos(feederPos));
            if (be == null)
                helper.fail("missing crafter feeder block entity", feederPos);
            return be;
        }

        CrafterBlockEntity crafter(GameTestHelper helper) {
            if (!(helper.getLevel().getBlockEntity(helper.absolutePos(crafterPos)) instanceof CrafterBlockEntity be))
                throw new IllegalStateException(failed(helper, "missing Crafter", crafterPos));
            return be;
        }

        boolean powering(GameTestHelper helper) {
            BlockState state = helper.getBlockState(feederPos);
            return state.hasProperty(WarehouseCrafterFeederBlock.POWERING)
                    && state.getValue(WarehouseCrafterFeederBlock.POWERING);
        }

        /** An empty Crafter behind the input, its output facing the input. */
        void placeCrafter(GameTestHelper helper) {
            Direction towardsRack = aisle.sideDirection(INPUT_RACK).getOpposite();
            helper.setBlock(crafterPos, Blocks.CRAFTER.defaultBlockState()
                    .setValue(BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(towardsRack, Direction.UP)));
        }

        /** A creative motor on top of the feeder, turning its vertical shaft. */
        void placeFeederMotor(GameTestHelper helper) {
            BlockPos motor = feederPos.above();
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.DOWN));
            if (helper.getLevel().getBlockEntity(helper.absolutePos(motor)) instanceof CreativeMotorBlockEntity be)
                be.generatedSpeed.setValue(FEEDER_RPM);
            else
                helper.fail("missing creative motor block entity", motor);
        }
    }

    private static String failed(GameTestHelper helper, String message, BlockPos pos) {
        helper.fail(message, pos);
        return message;
    }

    /** The aisle with chests of coal and sticks, the station, input, output, feeder and Crafter. */
    private static Layout build(GameTestHelper helper, boolean feederMotor) {
        Layout layout = buildEmpty(helper, feederMotor);
        layout.aisle().storage(COAL_RACK, COAL.toStack(STOCK));
        layout.aisle().storage(STICK_RACK, STICK.toStack(STOCK));
        layout.aisle().storage(LOG_RACK, LOG.toStack(STOCK));
        layout.aisle().storage(SPARE_RACK);
        return layout;
    }

    /**
     * The aisle without storage: station at 0 right, input at 1 right, output at 2 right; the feeder behind the station
     * facing along the rack (+x) with its shaft vertical, and the Crafter behind the input with its output facing the
     * input. That is the compact build of ADR-067 D2, the one in which a warehouse input can stand in front of the
     * Crafter's output.
     */
    private static Layout buildEmpty(GameTestHelper helper, boolean feederMotor) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.motor().generatedSpeed.setValue(CRANE_RPM);
        aisle.production(STATION_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        BlockPos feederPos = aisle.inventoryPos(STATION_RACK);
        BlockPos crafterPos = feederPos.relative(AisleFixture.AISLE);
        helper.assertValueEqual(crafterPos, aisle.inventoryPos(INPUT_RACK), "the Crafter stands behind the input");
        helper.setBlock(feederPos, feederState());
        Layout layout = new Layout(aisle, feederPos, crafterPos);
        layout.placeCrafter(helper);
        if (feederMotor)
            layout.placeFeederMotor(helper);
        return layout;
    }

    /** The feeder of {@link #buildEmpty}: facing along the rack (+x), its shaft vertical. */
    private static BlockState feederState() {
        return WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, AisleFixture.AISLE)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true);
    }

    /** The vanilla torch in pattern slot {@code slot}: coal above a stick, four torches. */
    private static void setTorchPattern(GameTestHelper helper, WarehouseProductionBlockEntity station, int slot) {
        helper.assertTrue(station.setPatternEntry(slot, TOP_LEFT, COAL, 1), "coal");
        helper.assertTrue(station.setPatternEntry(slot, MIDDLE_LEFT, STICK, 1), "a stick below it");
        helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, TORCH, TORCHES_PER_RUN),
                "four torches");
    }

    private static Map<ItemKey, Long> stocked() {
        return ItemCensus.of(COAL, STOCK, STICK, STOCK, LOG, STOCK);
    }

    private static void assertStocked(GameTestHelper helper, Layout layout) {
        WarehouseControllerBlockEntity controller = layout.aisle().controller();
        helper.assertValueEqual(controller.countOf(COAL), (long) STOCK, "coal in stock");
        helper.assertValueEqual(controller.countOf(STICK), (long) STOCK, "sticks in stock");
        helper.assertValueEqual(controller.productionStations().size(), 1, "the station is recorded");
        helper.assertTrue(layout.station(helper).attachedFeeder().isPresent()
                || layout.feeder(helper).status() == FeederStatus.TWO_STATIONS, "the feeder is attached");
    }

    private static void order(GameTestHelper helper, Layout layout, ItemKey key, int amount) {
        RequestResult result = layout.aisle().controller().request(layout.aisle().absoluteRackPos(OUTPUT_RACK), key,
                amount);
        helper.assertTrue(result.isAccepted(), "the order for " + key + " is accepted: " + result);
        helper.assertValueEqual(result.producing(), amount, "all of it is produced");
    }

    private static ProductionOrder<ItemKey, RackPosition> onlyOrder(GameTestHelper helper, Layout layout) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = layout.aisle().controller().productionOrders();
        if (orders.size() != 1) {
            helper.fail("expected exactly one production order, found " + orders.size());
            throw new IllegalStateException("unreachable");
        }
        return orders.getFirst();
    }

    /** The aisle with {@value #BIG_RUNS} logs in the racks, room for the planks, and the log pattern in slot 0. */
    private static Layout bigOrderLayout(GameTestHelper helper) {
        Layout layout = buildEmpty(helper, true);
        AisleFixture aisle = layout.aisle();
        aisle.storage(LOG_RACK, LOG.toStack(FULL_BATCH), LOG.toStack(BIG_RUNS - FULL_BATCH));
        aisle.storage(SPARE_RACK);
        aisle.storage(SPARE_RACK_2);
        WarehouseProductionBlockEntity station = layout.station(helper);
        helper.assertTrue(station.setPatternEntry(0, TOP_LEFT, LOG, 1), "the log");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN), "planks");
        return layout;
    }

    private static void assertBigStock(GameTestHelper helper, Layout layout) {
        helper.assertValueEqual(layout.aisle().controller().countOf(LOG), (long) BIG_RUNS, "logs in stock");
        helper.assertTrue(layout.station(helper).attachedFeeder().isPresent(), "the feeder is attached");
    }

    /** The aisle with honey bottles in the racks, room for the empty bottles, and the honey block in slot 0. */
    private static Layout honeyLayout(GameTestHelper helper, boolean feederMotor) {
        Layout layout = buildEmpty(helper, feederMotor);
        AisleFixture aisle = layout.aisle();
        aisle.storage(COAL_RACK, HONEY_BOTTLE.toStack(HONEY_STOCK));
        aisle.storage(SPARE_RACK);
        WarehouseProductionBlockEntity station = layout.station(helper);
        for (int cell : HONEY_CELLS)
            helper.assertTrue(station.setPatternEntry(0, cell, HONEY_BOTTLE, 1), "a honey bottle in cell " + cell);
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, HONEY_BLOCK, 1), "honey block");
        return layout;
    }

    private static void assertHoneyStock(GameTestHelper helper, Layout layout) {
        helper.assertValueEqual(layout.aisle().controller().countOf(HONEY_BOTTLE), (long) HONEY_STOCK, "honey");
        helper.assertTrue(layout.station(helper).attachedFeeder().isPresent(), "the feeder is attached");
        helper.assertValueEqual(layout.station(helper).crafterCheck(0).check(), CrafterPatternCheck.VALID,
                "the honey block is a recipe");
    }

    /** Takes up to {@code amount} of {@code key} out of the station through its own extract-only handler. */
    private static ItemStack takeFromStation(GameTestHelper helper, Layout layout, ItemKey key, int amount) {
        IItemHandler handler = layout.aisle().handlerAt(layout.aisle().rackPos(STATION_RACK));
        ItemStack taken = ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots() && taken.getCount() < amount; slot++) {
            if (!key.matches(handler.getStackInSlot(slot)))
                continue;
            ItemStack part = handler.extractItem(slot, amount - taken.getCount(), false);
            if (taken.isEmpty())
                taken = part;
            else
                taken.grow(part.getCount());
        }
        return taken;
    }

    // --- the Crafter's slots -----------------------------------------------------------------------------------------

    /** While a torch is laid, only slots 0 and 3 are open; the feeder locked the seven the pattern leaves empty. */
    private static void assertTorchLocks(GameTestHelper helper, CrafterBlockEntity crafter) {
        for (int slot = 0; slot < 9; slot++) {
            boolean used = slot == TOP_LEFT || slot == MIDDLE_LEFT;
            if (crafter.isSlotDisabled(slot) == used)
                helper.fail("slot " + slot + " of the Crafter is " + (used ? "locked" : "open")
                        + " while a torch is laid");
        }
    }

    private static void assertNoSlotLocked(GameTestHelper helper, CrafterBlockEntity crafter) {
        for (int slot = 0; slot < 9; slot++)
            helper.assertFalse(crafter.isSlotDisabled(slot), "the Crafter got slot " + slot + " back");
        assertGridEmpty(helper, crafter);
    }

    private static boolean gridEmpty(CrafterBlockEntity crafter) {
        for (int slot = 0; slot < 9; slot++) {
            if (!crafter.getItem(slot).isEmpty())
                return false;
        }
        return true;
    }

    private static void assertGridEmpty(GameTestHelper helper, CrafterBlockEntity crafter) {
        for (int slot = 0; slot < 9; slot++)
            helper.assertTrue(crafter.getItem(slot).isEmpty(), "Crafter slot " + slot + " is empty");
    }

    // --- the craft ledger --------------------------------------------------------------------------------------------

    /**
     * Fails unless the census equals {@code start} plus a whole number of runs of each recipe in {@code runs} (one
     * census change per run), each between 0 and {@code maxRuns}. That is the only way items may change while a Crafter
     * crafts: a lost or copied item, a dropped result or a run crafted twice can never be written as such a sum.
     *
     * @return the runs of each recipe that explain the census, in the order of {@code runs}
     */
    private static List<Integer> assertLedger(GameTestHelper helper, Map<ItemKey, Long> start, int maxRuns,
            List<Map<ItemKey, Long>> runs, String context) {
        Map<ItemKey, Long> actual = ItemCensus.take(helper);
        int[] crafted = new int[runs.size()];
        while (true) {
            Map<ItemKey, Long> expected = new HashMap<>(start);
            for (int recipe = 0; recipe < runs.size(); recipe++) {
                for (Map.Entry<ItemKey, Long> change : runs.get(recipe).entrySet())
                    ItemCensus.change(expected, change.getKey(), change.getValue() * crafted[recipe]);
            }
            if (expected.equals(actual)) {
                List<Integer> found = new ArrayList<>(runs.size());
                for (int count : crafted)
                    found.add(count);
                return found;
            }
            int recipe = 0;
            while (recipe < crafted.length && crafted[recipe] == maxRuns) {
                crafted[recipe] = 0;
                recipe++;
            }
            if (recipe == crafted.length)
                break;
            crafted[recipe]++;
        }
        helper.fail("item conservation violated (" + context + "): " + ItemCensus.describe(actual)
                + " is no whole number of crafts away from " + ItemCensus.describe(start));
        throw new IllegalStateException("unreachable");
    }

    /** Inserts stacks of {@code key} until {@code handler} refuses; returns how many it took. */
    private static int fill(IItemHandler handler, ItemKey key) {
        int total = 0;
        for (int attempt = 0; attempt < 256; attempt++) {
            ItemStack offered = key.toStack(key.getMaxStackSize());
            ItemStack rest = ItemHandlerHelper.insertItem(handler, offered, false);
            total += offered.getCount() - rest.getCount();
            if (!rest.isEmpty())
                break;
        }
        return total;
    }

    // --- screens and saves -------------------------------------------------------------------------------------------

    private static ProductionMenu openMenu(Player player, WarehouseProductionBlockEntity station) {
        ProductionMenu menu = ProductionMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                station);
        player.containerMenu = menu;
        return menu;
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static ProductionScreenPayload roundTrip(GameTestHelper helper, ProductionScreenState state) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionScreenPayload.STREAM_CODEC.encode(buffer, new ProductionScreenPayload(1, state));
        ProductionScreenPayload decoded = ProductionScreenPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        return decoded;
    }

    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), helper.getLevel().getBlockState(
                live.getBlockPos()), tag, helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
