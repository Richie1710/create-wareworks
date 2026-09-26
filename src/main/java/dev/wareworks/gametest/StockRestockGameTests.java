package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRuleStatus;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of <b>automatic restocking</b> and its safety stop ({@code docs/warehouse-system.md} §3.6.3, M15 part 2,
 * issue #3): the warehouse ordering its own refills, and the one thing that stops it doing so for ever.
 * <p>
 * The loop these tests prove is the same one M11 built, with nobody asking for the result: a rule's minimum is
 * unmet, the controller starts a production order with no backing request, the crane delivers the ingredients, the
 * player's machine turns them into the product, and the product comes back through an ordinary warehouse input and is
 * stored. Nothing is crafted and no item is invented anywhere — the item census watches every tick of it.
 * <ul>
 * <li>{@code restockfullloop} — below the minimum to back above it, with exactly one order for the whole run;</li>
 * <li>{@code restockreserveblocksanorder} — a reserve on the ingredient stops the order and says so, instead of
 * spending the items a rule protects through a pattern;</li>
 * <li>{@code restockpausesafteralostbatch} — an automatic order that times out with ingredients in the machine pauses
 * its rule everywhere, and a player's click is the only way back;</li>
 * <li>{@code restockpausesalthoughtheresultturnsup} — the same loss while the product arrives from <b>somewhere
 * else</b>: a rise of the stock index is not evidence that the machine gave anything back, and the rule stops
 * ordering anyway;</li>
 * <li>{@code restockneverovershootsamaximum} — "keep exactly ten, made four at a time": the warehouse orders the whole
 * runs that fit and then says why it cannot close the last gap, instead of settling above its own cap for ever;</li>
 * <li>{@code restockpausesurvivesareload} — that pause is saved with the controller, so a restart does not quietly
 * feed the broken machine again;</li>
 * <li>{@code restockruleeditedwhileordering} — a rule edited or deleted while its order runs: the order is never
 * orphaned, the pause goes with the rule, and nothing is left promising ingredients.</li>
 * </ul>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}, storage on the left rack plane, the stations and the
 * keeper on the right.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StockRestockGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Long enough for several dispatch intervals and more than one stock rule tick. */
    private static final int SETTLE_TICKS = 80;
    private static final String CONFIG_BATCH = "wareworksRestockConfig";
    /** Short enough for a lost order to time out inside a test, long enough for the crane to finish a trip. */
    private static final int SHORT_ORDER_TIMEOUT = 200;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);

    /** One log makes this many planks: the whole-runs case the feature is written around. */
    private static final int PLANKS_PER_LOG = 4;
    private static final int LOGS_IN_STOCK = 16;
    /** The minimum the keeper asks for. Not a multiple of {@link #PLANKS_PER_LOG}, so the run overshoots on purpose. */
    private static final int PLANK_MINIMUM = 10;
    /** What three runs really yield for a minimum of ten: the warehouse settles a little above it. */
    private static final int PLANKS_PRODUCED = 12;
    /**
     * What two runs yield, i.e. what a rule of "keep ten, store at most ten" may order: the third run of
     * {@value #PLANKS_PER_LOG} would not fit under the cap and is not ordered (M15 review fix).
     */
    private static final int CAPPED_RUN_RESULT = 8;
    /** Planks that turn up from somewhere other than the ordered machine, straight into a rack. */
    private static final int FOREIGN_PLANKS = 16;

    private StockRestockGameTests() {
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the loop -------------------------------------------------------------------------------------------------

    /**
     * The whole loop with nobody asking for anything: a rule that keeps ten planks, an aisle that has none, and a
     * pattern that makes them out of logs. The warehouse orders by itself, the crane brings the logs, the test plays
     * the machine, the planks come back through the input and are stored, and the rule is satisfied.
     * <p>
     * Two things are asserted that no other test can: that <b>one</b> order is started and no second one follows while
     * it is open — the minimum counts what is already on its way ({@code StockLevels#pipeline}) — and that the order
     * has <b>no backing request</b>, which is what makes it an automatic one and what decides whether a failure may
     * pause the rule.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockFullLoop(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the warehouse restocks itself"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    keep(aisle, PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                // The rule tick notices the shortfall and orders, without a request behind it.
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1,
                            "the warehouse started exactly one order for itself");
                    ProductionOrder<ItemKey, RackPosition> order = controller.openProductionOrders().getFirst();
                    helper.assertTrue(order.isRestock(), "and it is an automatic one");
                    helper.assertTrue(order.backingRequest().isEmpty(), "with nobody waiting for its result");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "no retrieval request was invented");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.ORDER_OPEN,
                            "and the rule reports that it is being made");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(PRODUCTION_RACK, LOG) > 0,
                        "the crane delivered the ingredients"))
                .thenExecute(() -> {
                    // The test is the player's machine: it takes the logs and hands planks back through the input.
                    int taken = extractAll(aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK)), LOG);
                    helper.assertTrue(taken > 0, "the machine took the logs");
                    ItemCensus.change(conserved, LOG, -taken);
                    int made = taken * PLANKS_PER_LOG;
                    helper.assertValueEqual(made, PLANKS_PRODUCED, "three runs of four planks for a minimum of ten");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(made));
                    ItemCensus.change(conserved, PLANK, made);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(PLANK), (long) PLANKS_PRODUCED,
                            "the planks came back through the input and were stored");
                    helper.assertValueEqual(controller.productionOrders().getFirst().state(),
                            ProductionOrderState.COMPLETE, "and the order completed");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                            "a met minimum orders nothing more");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.NOT_GOVERNING,
                            "and reports nothing at all, because there is nothing to report");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.SATISFIED, "the rule is satisfied");
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "and the keeper stops calling for planks");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "nothing was ever paused");
                })
                .thenSucceed();
    }

    /**
     * A reserve on the <b>ingredient</b> stops an automatic order, exactly as it stops a redstone request for the
     * product (part 1): restocking is the warehouse's own automation, and a reserve is what keeps the last logs from
     * being fed to a machine overnight. Nothing is spent, no order is started, and the rule says what it is waiting
     * for instead of calling itself satisfied.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void restockReserveBlocksAnOrder(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reserve refuses a restock"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) PLANK_MINIMUM);
                    // Every log is protected from the warehouse's own automation.
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, LOG, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_RESERVE, null, (long) LOGS_IN_STOCK);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, LOG), 0L,
                            "no log is left for automation");
                    helper.assertValueEqual(controller.productionOrders().size(), 0,
                            "so no order may be started against them");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK),
                            RestockOutcome.WAITING_FOR_INGREDIENTS, "and the rule says what it is waiting for");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.WAITING_FOR_INGREDIENTS,
                            "which is what the keeper shows");
                    helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK, "every log is untouched");
                })
                .thenExecute(() -> {
                    // Lowering the reserve by one log is enough for exactly one run: the very next pass orders.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(1, StockKeeperRules.FIELD_RESERVE, null,
                            (long) LOGS_IN_STOCK - 3);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1,
                            "with logs above the reserve the warehouse orders");
                    helper.assertTrue(controller.openProductionOrders().getFirst().isRestock(), "automatically");
                })
                .thenSucceed();
    }

    // --- the safety stop -------------------------------------------------------------------------------------------

    /**
     * <b>The safety stop.</b> An automatic order delivers its ingredients, no machine ever takes them and no product
     * ever arrives, so the order times out — and because the crane had already dropped items at the station, the rule
     * stops ordering instead of feeding the same machine again on the next pass.
     * <p>
     * What is asserted is the whole contract: the rule is paused, the pause names what it cost, the keeper shows it in
     * its own block state, no second order is ever started however long the test waits, and the player's click is what
     * lets it order again.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockPausesAfterALostBatch(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an order is lost"));
        int[] delivered = { 0 };

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    keep(aisle, PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(PRODUCTION_RACK, LOG) > 0,
                        "the crane delivered the ingredients of the automatic order"))
                .thenExecute(() -> delivered[0] = (int) aisle.stationCount(PRODUCTION_RACK, LOG))
                // Nobody plays the machine: the logs sit in the station and no plank ever arrives.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().productionOrders().getFirst().state(),
                        ProductionOrderState.TIMED_OUT, "the order gave up"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    Optional<StockRulePause> pause = controller.stockRulePause(PLANK);
                    helper.assertTrue(pause.isPresent(), "the rule is paused");
                    helper.assertValueEqual(pause.get().cause(), StockRulePause.Cause.TIMED_OUT, "by the timeout");
                    helper.assertValueEqual(pause.get().unrecovered(), (long) delivered[0],
                            "and it names exactly the items the machine was given");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 1, "one rule is held");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.PAUSED, "as paused");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.PAUSED,
                            "which outranks every other status the three numbers would give");
                })
                .thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                        .getValue(WarehouseStockKeeperBlock.PAUSED), "and the keeper's own lamp says so"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                            "a paused rule never orders again by itself");
                    helper.assertTrue(controller.countOf(LOG) < LOGS_IN_STOCK,
                            "the batch it already spent is gone, and nothing pretends otherwise");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) delivered[0],
                            "the ingredients stay where the crane put them: nothing is ever taken back out");
                })
                .thenExecute(() -> {
                    // A second row for the same item applies nothing at all, and must neither wear the pause of the
                    // row that really governs planks nor be able to lift it (M15 review fix).
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MINIMUM, null, 5L);
                    List<StockRuleStatus> statuses =
                            aisle.controller().stockRuleStatuses(aisle.absoluteRackPos(KEEPER_RACK));
                    helper.assertValueEqual(statuses.get(0), StockRuleStatus.PAUSED, "the governing row is paused");
                    helper.assertValueEqual(statuses.get(1), StockRuleStatus.SHADOWED,
                            "and the duplicate still says that an earlier rule governs the item");
                    keeper.editRule(1, StockKeeperRules.FIELD_RESERVE, null, 3L);
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 1,
                            "editing a row that applies nothing lifts no safety stop");
                    helper.assertFalse(keeper.editRule(1, StockKeeperRules.FIELD_RESUME, null, 0L).changed(),
                            "and neither does clicking its mark");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 1, "the rule is still held");
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, null, 0L); // out of the way again
                })
                .thenExecute(() -> {
                    // The player looks at the machine and lets the rule order again: the one way back.
                    StockKeeperRules.Edit resumed = aisle.stockKeeperAt(KEEPER_RACK)
                            .editRule(0, StockKeeperRules.FIELD_RESUME, null, 0L);
                    helper.assertTrue(resumed.changed(), "resumed");
                    helper.assertTrue(resumed.resumed(), "and the screen is told that the safety stop was lifted");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0, "nothing is held any more");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "and the warehouse orders again"))
                .thenSucceed();
    }

    /**
     * <b>A rise of the stock index is not evidence that the machine gave anything back.</b> The batch is swallowed and,
     * while the order waits, {@value #FOREIGN_PLANKS} planks turn up from somewhere else entirely — a second farm, a
     * barrel emptied into a rack, a player putting the product back. The count the order watches really does rise past
     * what it waits for.
     * <p>
     * Before this was fixed the order completed on those planks, the safety stop never fired, and the next dip fed the
     * same broken machine another batch — silently, for as long as the foreign source kept up (M15 review fix). An
     * automatic order is now only ever completed by items the warehouse itself stored out of one of its inputs, so this
     * one times out and pauses its rule with the whole batch named as lost.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockPausesAlthoughTheResultTurnsUp(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a lost batch is masked"));
        int[] swallowed = { 0 };

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    keep(aisle, PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(PRODUCTION_RACK, LOG) > 0,
                        "the crane delivered the ingredients of the automatic order"))
                .thenExecute(() -> {
                    // The machine swallows the logs and makes nothing at all.
                    swallowed[0] = extractAll(aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK)), LOG);
                    helper.assertTrue(swallowed[0] > 0, "the machine took the logs");
                    ItemCensus.change(conserved, LOG, -swallowed[0]);
                    // And somebody else's planks land straight in a rack, bypassing every warehouse input.
                    aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(STORAGE_B)), PLANK.toStack(FOREIGN_PLANKS));
                    ItemCensus.change(conserved, PLANK, FOREIGN_PLANKS);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(PLANK),
                        (long) FOREIGN_PLANKS, "the foreign planks are in the index the order watches"))
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order =
                            aisle.controller().productionOrders().getFirst();
                    helper.assertValueEqual(order.produced(), 0L,
                            "and not one of them is counted as this machine's work");
                    helper.assertTrue(order.state() != ProductionOrderState.COMPLETE,
                            "so the order is not complete: " + order.state());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().productionOrders().getFirst().state(),
                        ProductionOrderState.TIMED_OUT, "the order gives up instead"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    Optional<StockRulePause> pause = controller.stockRulePause(PLANK);
                    helper.assertTrue(pause.isPresent(), "and the rule is paused");
                    helper.assertValueEqual(pause.get().unrecovered(), (long) swallowed[0],
                            "with the batch the machine really kept");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.PAUSED, "as paused");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(
                        aisle.controller().openProductionOrders().size(), 0,
                        "and no second batch is ever handed to the same machine"))
                .thenSucceed();
    }

    // --- the rule's own maximum ---------------------------------------------------------------------------------------

    /**
     * <b>"Keep exactly ten, made four at a time."</b> A rule whose minimum and maximum are the same number must not end
     * up above its own cap: a surplus stored above a maximum never leaves the warehouse again, so the rule would report
     * {@code AT_MAXIMUM} for ever, its lamp would stay lit and a warehouse input holding planks would back up — the
     * state §3.6 teaches a player to read as a jam (M15 review fix).
     * <p>
     * The warehouse therefore orders the whole runs that <b>fit</b> — two of four for a gap of ten — and then says why
     * it cannot close the last two ({@code RestockOutcome#NO_ROOM}) instead of overshooting for them.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockNeverOvershootsAMaximum(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a capped rule is refilled"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) PLANK_MINIMUM);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                            "the warehouse orders what fits");
                    ProductionOrder<ItemKey, RackPosition> order =
                            aisle.controller().openProductionOrders().getFirst();
                    helper.assertValueEqual(order.resultAmount(), CAPPED_RUN_RESULT,
                            "two whole runs, not the three the gap of ten would round up to");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(PRODUCTION_RACK, LOG) > 0,
                        "the crane delivered the ingredients"))
                .thenExecute(() -> {
                    int taken = extractAll(aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK)), LOG);
                    ItemCensus.change(conserved, LOG, -taken);
                    int made = taken * PLANKS_PER_LOG;
                    helper.assertValueEqual(made, CAPPED_RUN_RESULT, "the machine makes exactly what was ordered");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(made));
                    ItemCensus.change(conserved, PLANK, made);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(PLANK),
                        (long) CAPPED_RUN_RESULT, "and the planks came back through the input"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.countOf(PLANK) <= PLANK_MINIMUM,
                            "the aisle never holds more than its own maximum: " + controller.countOf(PLANK));
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                            "and the last two planks are not ordered for");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.NO_ROOM,
                            "the rule says why: a whole run would not fit");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.BELOW_MINIMUM,
                            "while the lamp keeps saying exactly what it said before");
                })
                .thenSucceed();
    }

    /**
     * The pause is saved with the controller. A world that was closed with a broken machine must not come back
     * ordering into it again, which is why the pause is persisted next to the rules themselves and not derived from
     * anything that only exists while the world runs.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockPauseSurvivesAReload(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stockedAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    keep(aisle, PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the order was lost and the rule is paused"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity live = aisle.controller();
                    long unrecovered = live.stockRulePause(PLANK).orElseThrow().unrecovered();
                    CompoundTag saved = live.saveWithFullMetadata(helper.getLevel().registryAccess());
                    WarehouseControllerBlockEntity loaded = loadCopy(helper, live, saved);
                    Optional<StockRulePause> pause = loaded.stockRulePause(PLANK);
                    helper.assertTrue(pause.isPresent(), "a controller loaded from its save is still paused");
                    helper.assertValueEqual(pause.get().cause(), StockRulePause.Cause.TIMED_OUT, "for the same reason");
                    helper.assertValueEqual(pause.get().unrecovered(), unrecovered, "and with the same cost");
                    helper.assertValueEqual(loaded.pausedStockRuleCount(), 1, "one rule, not none and not two");
                })
                .thenSucceed();
    }

    /**
     * A rule edited or deleted while its own automatic order is running. Neither may leave anything behind: the order
     * keeps running to its end — its ingredients are already promised and a crane may already be carrying them — but
     * it belongs to nobody, and deleting the rule takes its pause with it, which is the second way back from the
     * safety stop.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void restockRuleEditedWhileOrdering(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stockedAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    setPattern(helper, aisle);
                    keep(aisle, PLANK_MINIMUM);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "an automatic order is running"))
                .thenExecute(() -> {
                    // The player raises the minimum while the order runs: the rule is short again, but its own order
                    // is still open, so nothing is ordered twice.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 200L);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1,
                            "the running order still counts, so no second one is started");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.ORDER_OPEN,
                            "and the rule says why it is waiting");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the order is lost and pauses the rule"))
                .thenExecute(() -> {
                    // Deleting the rule takes its pause with it: a rule that is gone governs nothing to resume.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, null, 0L);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0,
                            "the pause goes with the rule it belonged to");
                    helper.assertTrue(controller.stockRules().isEmpty(), "and the rule itself is gone");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                            "a warehouse without rules orders nothing at all");
                    helper.assertValueEqual(controller.restockOutcomeOf(PLANK), RestockOutcome.NOT_GOVERNING,
                            "and reports nothing");
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.PAUSED), "the keeper's pause lamp goes out");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** The aisle every test here uses: two storage locations holding logs, an input, a station and a keeper. */
    private static AisleFixture stockedAisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        return aisle;
    }

    private static void assertReady(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(2, 1, 0);
        helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station is there");
        helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in stock");
    }

    /** One log to {@value #PLANKS_PER_LOG} planks, written into the station's first pattern. */
    private static void setPattern(GameTestHelper helper, AisleFixture aisle) {
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
    }

    /** The keeper's first row: "keep {@code minimum} planks". */
    private static void keep(AisleFixture aisle, int minimum) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) minimum);
    }

    /** What the keeper's first rule is doing, as every surface shows it. */
    private static StockRuleStatus status(GameTestHelper helper, AisleFixture aisle) {
        List<StockRuleStatus> statuses = aisle.controller().stockRuleStatuses(aisle.absoluteRackPos(KEEPER_RACK));
        if (statuses.isEmpty()) {
            helper.fail("the controller holds no rules for the keeper");
            throw new IllegalStateException("unreachable");
        }
        return statuses.getFirst();
    }

    private static int extractAll(IItemHandler handler, ItemKey key) {
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!key.matches(handler.getStackInSlot(slot)))
                continue;
            taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        return taken;
    }

    private static WarehouseControllerBlockEntity loadCopy(GameTestHelper helper,
            WarehouseControllerBlockEntity live, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof WarehouseControllerBlockEntity controller)) {
            helper.fail("a saved warehouse controller must load again as one");
            throw new IllegalStateException("unreachable");
        }
        return controller;
    }
}
