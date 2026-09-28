package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of <b>recursive production</b> — a production plan ({@code docs/warehouse-system.md} §3.5.6, ADR-032, M20,
 * issue #4): ordering an item whose ingredients are themselves only producible.
 * <p>
 * The feature is one sentence: at the moment of the click the warehouse walks its aisle's patterns, works out the whole
 * chain, and either creates <b>every</b> step at once or refuses the click with a reason that <b>names the item</b> that
 * is really in the way. Nothing moves in between, so the crane never starts carrying logs for a chest the aisle could
 * never have finished.
 * <p>
 * What these tests prove about the world, on top of what {@code ProductionPlannerTest} proves about the walk:
 * <ul>
 * <li>{@code productionplantwolevels} — the real thing: a chest ordered with nothing but logs in the racks. Both
 * machines are played by the test, the planks really pass <b>through a rack</b> between them (Wareworks crafts nothing
 * and there is no machine-to-machine shortcut, ADR-024), and an {@link ItemCensus} runs on every tick;</li>
 * <li>{@code productionplancreatedatomically} — every order of the chain exists in the tick of the click, the step names
 * the very ingredient line of its parent, and the parent's planks are promised before a plank exists;</li>
 * <li>{@code productionplanrefusalsnametheitem} — one refusal per reason, each asserting the reason <b>and</b> the item,
 * with the census unchanged and not a single order created;</li>
 * <li>{@code productionplanboundsrefusewiththeirownreason} — the bounds a server operator sets, in their own config
 * batch;</li>
 * <li>{@code productionplanintermediatemaximumrefuses} — an intermediate that would not fit under its own maximum, the
 * same answer while an unrelated order for it is in flight, and the same plan running once the cap is raised;</li>
 * <li>{@code productionplanpartlyservedclickkeepsthereason} — a click the racks served only part of keeps the reason the
 * rest could not be made, and the item it is about;</li>
 * <li>{@code productionplanpauseditemisnotplanned} — the safety stop of ADR-027 blocking a <b>player's</b> chain, which
 * is the planning half of the M20 decision that the stop covers every kind of order;</li>
 * <li>{@code productionplanfromaredstoneport} — a redstone request may start a chain, and a port may have <b>one</b>
 * open plan at a time;</li>
 * <li>{@code productionplanfromaportstaysoneafteritsstepisdone} — and that is still one plan once every step of it has
 * finished and only the root is left, which is the longest part of a chain's life;</li>
 * <li>{@code productionplanmeasuredagainsttheconfirmation} — the M15 question, measured over the plan: a reserve two
 * steps down is named by its own item, and the plan that is then created is the one the player accepted.</li>
 * </ul>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails. Right rack plane:
 * the saw station at 0 (one log makes {@value #PLANKS_PER_RUN} planks), a warehouse input at 1 (where both machines
 * return their products), a warehouse output at 2 (the destination of the order), the crafter station at 3
 * ({@value #PLANKS_PER_CHEST} planks make one chest) and a stock keeper at 4. Left rack plane: a chest of logs at 1 and
 * two empty storage locations at 2 and 3.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ProductionPlanGameTests {
    /**
     * A test that overrides the config needs a batch of its <b>own</b>: the tests of one batch run at the same time and
     * would see each other's overrides ({@link ConfigOverrides}). These three change different keys to incompatible
     * values, so they get a batch each rather than one shared "config" batch.
     */
    private static final String OFF_SWITCH_BATCH = "wareworksProductionPlanOffSwitch";
    private static final String BOUNDS_BATCH = "wareworksProductionPlanBounds";
    private static final String PAUSE_BATCH = "wareworksProductionPlanPause";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** The crane speed the other job tests use, so a whole chain fits into a test's tick budget. */
    private static final int TEST_RPM = 128;

    private static final RackPosition SAW_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORE_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition SPARE_RACK = new RackPosition(3, 0, Side.LEFT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey NAIL = ItemKey.of(Items.IRON_NUGGET);

    /** One log makes four planks, the pattern from the feature request. */
    private static final int PLANKS_PER_RUN = 4;
    /** Four planks make one chest: exactly one run of the saw's pattern, so the chain has no surplus to hide in. */
    private static final int PLANKS_PER_CHEST = 4;
    private static final int LOGS_IN_STOCK = 16;
    /** Logs one chest costs: one run of the saw per run of the crafter. */
    private static final int LOGS_PER_CHEST = PLANKS_PER_CHEST / PLANKS_PER_RUN;

    private static final int TIMEOUT_TICKS = 1200;
    /** A chain is two machines, four crane trips and two stores, so it gets the long budget. */
    private static final int CHAIN_TIMEOUT_TICKS = 3000;
    private static final int SETTLE_TICKS = 20;
    /** A timeout short enough for a test, far above a crane trip so only a genuinely stuck order trips it. */
    private static final int SHORT_ORDER_TIMEOUT = 120;
    /** The minimum a stock keeper asks for in the pause test: less than one run, so one order refills it. */
    private static final int PLANK_MINIMUM = 2;

    private ProductionPlanGameTests() {
    }

    @AfterBatch(batch = OFF_SWITCH_BATCH)
    public static void restoreOffSwitchConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = BOUNDS_BATCH)
    public static void restoreBoundsConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = PAUSE_BATCH)
    public static void restorePauseConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the chain -------------------------------------------------------------------------------------------------

    /**
     * <b>The feature.</b> A chest is ordered with nothing but logs in the racks: the warehouse orders the saw, the
     * planks come back through a warehouse input and <b>are stored in a rack</b>, the crane fetches those very planks to
     * the crafter, and the chest comes back and is delivered to the output the request named.
     * <p>
     * The beat that matters is the rack. There is deliberately no hand-over between the two machines: every intermediate
     * is a real crane trip through real storage, which is what keeps the stock index and every surface honest (ADR-024,
     * ADR-032). The test asserts it directly — the planks are in a storage location before the crafter ever sees them,
     * and the crane's trip to the crafter is an ordinary {@code SUPPLY} job out of storage.
     * <p>
     * The item census runs on every tick and changes in exactly the two steps in which the test <b>is</b> the machine.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanTwoLevels(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the chain runs"));
        UUID[] stepId = new UUID[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "the whole chest has to be made");
                    stepId[0] = onlyStep(helper, aisle).id();
                })
                // The saw is the only station the crane can serve: the crafter's planks do not exist yet, so no SUPPLY
                // job can be planned for them at all - which is what makes a plan run bottom-up with no scheduler.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the logs reached the saw"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK), 0L,
                            "and nothing was delivered to the crafter, whose planks nobody has made yet");
                    playMachine(helper, aisle, conserved, SAW_RACK, LOG, PLANK, PLANKS_PER_CHEST);
                })
                // The planks go into a rack first. Asserted on the storage inventories, not on the index, because "it
                // really passed through a rack" is a statement about the world.
                .thenWaitUntil(() -> helper.assertValueEqual(storedPlanks(aisle), (long) PLANKS_PER_CHEST,
                        "the planks were stored in a rack, not handed from machine to machine"))
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> step = orderOf(helper, aisle, stepId[0]);
                    helper.assertValueEqual(step.state(), ProductionOrderState.COMPLETE, "the step completed");
                    helper.assertTrue(step.isStep(), "and it is still the step of its plan");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK),
                        (long) PLANKS_PER_CHEST, "the crane fetched those very planks to the crafter"))
                .thenExecute(() -> {
                    helper.assertValueEqual(storedPlanks(aisle), 0L, "out of the rack they were stored in");
                    playMachine(helper, aisle, conserved, CRAFTER_RACK, PLANK, CHEST, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, CHEST), 1L,
                        "and the chest reached the output the request named"))
                // The crane is still retracting its arm in the tick the chest lands, so the end state is waited for.
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "both orders are finished");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "the request was served");
                    helper.assertValueEqual(controller.availableStock(LOG),
                            (long) LOGS_IN_STOCK - LOGS_PER_CHEST, "and only the logs the saw really got are gone");
                    helper.assertTrue(controller.reservations().isEmpty(), "nothing stays reserved");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "and nothing was paused");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>Acceptance is atomic, and it is the reservation.</b> Every order of the chain exists in the tick of the click —
     * a parent without its children would fetch ingredients for a run nothing is going to complete — each step names the
     * ingredient line of its parent that its product is for, and the parent's planks are promised to nobody else from
     * that moment on, before a single plank exists.
     * <p>
     * The crane never moves here (no motor), so the orders are seen exactly as the click created them.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanCreatedAtomically(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.availableStock(PLANK), 0L, "no plank exists yet");
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.granted(), 1, "one chest was granted");
                    helper.assertValueEqual(result.producing(), 1, "and all of it is being made");

                    // Both orders in the same tick, children first, and they are one plan.
                    List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
                    helper.assertValueEqual(orders.size(), 2, "the whole chain was created at once");
                    ProductionOrder<ItemKey, RackPosition> step = orders.getFirst();
                    ProductionOrder<ItemKey, RackPosition> root = orders.get(1);
                    helper.assertTrue(step.isStep(), "the step was created before the order it feeds");
                    helper.assertFalse(root.isStep(), "and the ordered item's own order waits for nobody");
                    helper.assertValueEqual(step.result(), PLANK, "the step makes the planks");
                    helper.assertValueEqual(root.result(), CHEST, "the root makes the chest");
                    helper.assertValueEqual(step.station(), SAW_RACK, "at the saw");
                    helper.assertValueEqual(root.station(), CRAFTER_RACK, "and at the crafter");

                    // The link is a line id, which is what says precisely WHICH ingredient is being made.
                    SupplyLine<ItemKey> line = root.lines().getFirst();
                    helper.assertValueEqual(line.key(), PLANK, "the parent's only line asks for planks");
                    helper.assertValueEqual(step.parentLine(), Optional.of(line.id()),
                            "and the step names that very line");
                    helper.assertValueEqual(line.required(), PLANKS_PER_CHEST, "the whole ingredient line");
                    helper.assertValueEqual(controller.productionPlanOf(root.id()).size(), 2,
                            "both orders are one plan, walked from either end");
                    helper.assertValueEqual(controller.productionPlanOf(step.id()).size(), 2, "from the step too");
                    helper.assertValueEqual(controller.openProductionStepCount(), 1, "one of them is a step");

                    // The claim on the intermediate is the parent's own supply line: that is the whole reservation.
                    helper.assertValueEqual(controller.availableStock(PLANK), 0L,
                            "the planks are promised before one exists");
                    helper.assertValueEqual(controller.availableStock(LOG),
                            (long) LOGS_IN_STOCK - LOGS_PER_CHEST, "and the step promises its own logs");
                    helper.assertValueEqual(root.promisedToRequest(), 1L, "the root promises the request one chest");
                    helper.assertTrue(step.backingRequest().isEmpty(), "nobody waits for a step directly");
                    helper.assertValueEqual(step.promisedToRequest(), 0L, "so it promises nothing to anyone");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> helper.assertValueEqual(aisle.controller().productionOrders()
                        .size(), 2, "and nothing was created or dropped afterwards"))
                .thenSucceed();
    }

    /**
     * <b>No machine is given ingredients for items nobody is waiting for.</b> The queue may grant less than the plan
     * offered — here because the station may only wait for one item at a time — and the chain that is then created is
     * the one for what was really granted, not the larger one the click was measured against.
     * <p>
     * Both orders shrink together, which is the point: a root for one chest whose step still made planks for two would
     * leave a machine's worth of ingredients spoken for by nobody.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanShrinksToWhatWasGranted(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    // Two chests asked for, one item per request allowed at this station.
                    RequestResult result = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), CHEST, 2, 1);
                    helper.assertTrue(result.isAccepted(), "one of the two chests is granted: " + result);
                    helper.assertValueEqual(result.granted(), 1, "the station may only wait for one");
                    helper.assertValueEqual(result.producing(), 1, "and that one is being made");

                    List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
                    helper.assertValueEqual(orders.size(), 2, "as a chain of two");
                    helper.assertValueEqual(orders.get(1).resultAmount(), 1,
                            "the root makes the one chest that was granted, not the two that were asked for");
                    helper.assertValueEqual(orders.getFirst().resultAmount(), PLANKS_PER_CHEST,
                            "and its step makes exactly the planks that chest needs");
                    helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK - LOGS_PER_CHEST,
                            "so only one chest worth of logs is promised");
                })
                .thenSucceed();
    }

    // --- refusals --------------------------------------------------------------------------------------------------

    /**
     * <b>A refusal names the item.</b> Ordering a chest used to answer "not in stock" about the chest; now the same
     * click answers about the three oak logs that are really missing, and every other way a chain can fail says which
     * item it is about.
     * <p>
     * Four reasons in one test, because all four leave the warehouse in exactly the same state and that is half of what
     * is being asserted: the census is unchanged, not one order is created and no request is left waiting.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanRefusalsNameTheItem(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false, 0);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while chains are refused"));

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                // The headline case: the chain is possible in principle and one item at the bottom of it is missing.
                .thenExecute(() -> assertRefused(helper, aisle, CHEST, 1, PlanRefusal.MISSING_INGREDIENT, LOG,
                        RequestRejection.NOT_IN_STOCK))
                // Nothing here makes it at all, which is a different sentence and a different place to go.
                .thenExecute(() -> assertRefused(helper, aisle, NAIL, 1, PlanRefusal.NO_PATTERN, NAIL,
                        RequestRejection.NOT_IN_STOCK))
                // Two patterns that are inverses of each other: the chain would have to make planks out of the very
                // planks it is on its way to make. Refused by name, and BEFORE anything is converted.
                .thenExecute(() -> {
                    setPattern(helper, aisle, SAW_RACK, 1, PLANK, 2, LOG, 1); // two planks back into one log
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.LOOP, PLANK, RequestRejection.NOT_IN_STOCK);
                })
                // The same pattern pair is perfectly legal to author: with the logs in the racks the chain is one step
                // and converts nothing. The loop only bites where a chain would come back on itself.
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(LOG_RACK)), LOG.toStack(LOGS_IN_STOCK));
                    ItemCensus.change(conserved, LOG, LOGS_IN_STOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK,
                        "the logs are read into the index"))
                .thenExecute(() -> {
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "the very same click is now a chain of two");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 1, "with one step");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionOrders().size(), 2,
                            "and only the accepted chain ever created an order");
                    helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK,
                            "the logs a loop would have converted are all still there");
                })
                .thenSucceed();
    }

    /**
     * <b>One step is the off switch.</b> With {@code maxProductionPlanSteps} at 1 no chain may be created at all: the
     * chest is refused where its chain would have begun, and the coarse answer a station shows is the pre-M20 "not in
     * stock" — while an ordinary single-level order of the same aisle is accepted exactly as it always was.
     */
    @GameTest(template = AISLE_16X10X7, batch = OFF_SWITCH_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanStepLimitOfOneIsTheOffSwitch(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "with recursion switched off"));

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> ConfigOverrides.set(helper, WareworksConfig.SERVER.maxProductionPlanSteps, 1))
                .thenExecute(() -> assertRefused(helper, aisle, CHEST, 1, PlanRefusal.TOO_MANY_STEPS, PLANK,
                        RequestRejection.NOT_IN_STOCK))
                .thenExecute(() -> {
                    RequestResult planks = order(helper, aisle, PLANK, PLANKS_PER_RUN);
                    helper.assertValueEqual(planks.producing(), PLANKS_PER_RUN,
                            "a single-level order is exactly what it was before M20");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 1, "one order");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 0, "and it is nobody's step");
                })
                .thenSucceed();
    }

    /**
     * The two bounds that are about <b>cost</b> rather than about shape: the ingredient items one click may put into
     * machines, and the free production order slots a chain has to fit into. Each refuses with its own reason and names
     * its item, and a click that is merely too <b>large</b> is made smaller instead of being refused at all.
     */
    @GameTest(template = AISLE_16X10X7, batch = BOUNDS_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanBoundsRefuseWithTheirOwnReason(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while bounds refuse chains"));

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    // What one click may put into machines. One chest costs four planks plus one log, so a budget of
                    // three cannot even pay for one run - and a bound no smaller order can escape is a refusal.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxPlanIngredientItems, 3);
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.TOO_MANY_INGREDIENT_ITEMS, CHEST,
                            RequestRejection.NOT_IN_STOCK);
                })
                .thenExecute(() -> {
                    // A larger order is made smaller rather than refused: the budget pays for one chest, not for two.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxPlanIngredientItems,
                            PLANKS_PER_CHEST + LOGS_PER_CHEST);
                    RequestResult result = order(helper, aisle, CHEST, 2);
                    helper.assertValueEqual(result.granted(), 1, "the order was made smaller until it fitted");
                    helper.assertValueEqual(result.producing(), 1, "and what it promises is what it can make");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 2, "as one chain of two");
                })
                .thenExecute(() -> {
                    // The free order slots are the other half of the step bound, and they say so with their own reason:
                    // waiting for an order to finish is a different cure from writing another pattern.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxPlanIngredientItems, 256);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxProductionOrders, 2);
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.ORDERS_BUSY, CHEST,
                            RequestRejection.PRODUCTION_BUSY);
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 2,
                            "and the chain that is already running is untouched");
                })
                .thenSucceed();
    }

    /**
     * An intermediate that would not fit under <b>its own</b> maximum is refused by name, because a product that cannot
     * be stored never comes back: the warehouse input would back up, the order above it would starve and the chain would
     * time out with the batch gone. Raising the cap lets the very same click through.
     * <p>
     * The ordered item's own maximum is deliberately <b>not</b> a refusal — a player may order past their own cap and the
     * request panel says so (M15) — which is why this test caps the planks and not the chests.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanIntermediateMaximumRefuses(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    // Room for half a run of planks: the step makes four and would have nowhere to put them.
                    keeperRule(aisle, 0, PLANK, StockKeeperRules.FIELD_MAXIMUM, 2L);
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.NO_ROOM, PLANK,
                            RequestRejection.NOT_IN_STOCK);
                })
                .thenExecute(() -> {
                    // The answer is about this click and nothing else (M20 review fix). An unrelated open order for the
                    // very same intermediate raises the room a STORE is allowed to use - the warehouse always takes
                    // back what it sent out for - and the identical click was accepted while it happened to be in
                    // flight and refused a minute later, which is not an answer a player can act on.
                    RequestResult planks = order(helper, aisle, PLANK, PLANKS_PER_RUN);
                    helper.assertValueEqual(planks.producing(), PLANKS_PER_RUN,
                            "a player may order past their own maximum, so this order exists");
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.NO_ROOM, PLANK,
                            RequestRejection.NOT_IN_STOCK);
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 1,
                            "and only the plank order was ever created");
                })
                .thenExecute(() -> {
                    keeperRule(aisle, 0, PLANK, StockKeeperRules.FIELD_MAXIMUM, (long) PLANKS_PER_CHEST);
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "with room for one run the same click is accepted");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 3,
                            "the plank order and the chain of two");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 1, "one of them a step");
                })
                .thenSucceed();
    }

    /**
     * <b>The safety stop covers a player's own order too</b> (the M20 decision over ADR-027's automatic-only stop): once
     * the warehouse has stopped making planks, no plan may contain a step for them either, so the next click cannot
     * rebuild the same chain into the same machine that swallowed the last batch.
     * <p>
     * The pause is armed the one way that exists today — an automatic restock order whose machine nobody plays — and what
     * is asserted is the <b>planning</b> half: a click for a chest is refused with {@link PlanRefusal#PAUSED} naming the
     * planks, and it is accepted again the moment a player resumes the item.
     */
    @GameTest(template = AISLE_16X10X7, batch = PAUSE_BATCH, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanPausedItemIsNotPlanned(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = chainAisle(helper, true);

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                // A rule asks for planks, the warehouse orders them by itself, and nobody plays the saw.
                .thenExecute(() -> keeperRule(aisle, 0, PLANK, StockKeeperRules.FIELD_MINIMUM, (long) PLANK_MINIMUM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the lost batch stopped the warehouse from making planks"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.stockRulePause(PLANK).orElseThrow().cause(),
                            StockRulePause.Cause.TIMED_OUT, "by the timeout");
                    // The planning half of the decision: the chain for a chest needs planks, so it is not planned.
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.PAUSED, PLANK,
                            RequestRejection.PRODUCTION_PAUSED);
                    // And a plain single-level order of the paused item itself is refused for the same reason.
                    assertRefused(helper, aisle, PLANK, PLANKS_PER_RUN, PlanRefusal.PAUSED, PLANK,
                            RequestRejection.PRODUCTION_PAUSED);
                })
                .thenExecute(() -> {
                    // The one way back is a player's click, and then the chain is planned as if nothing had happened.
                    helper.assertTrue(aisle.controller().resumeStockRule(PLANK), "the player resumes the item");
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "and the chest is orderable again");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 1, "as a chain of two");
                })
                .thenSucceed();
    }

    /**
     * <b>A click the racks served only part of keeps the reason for the rest</b> (M20 review fix). One chest is in a rack
     * and there is not a log in the aisle, so a click for two is served once and the second one cannot be made — and the
     * sentence that says why, naming the log two steps down, is computed in the very same tick.
     * <p>
     * It used to be thrown away, because the precise reason only reached the caller on a result that granted
     * <b>nothing</b>. That is the rarer case: a player asking for a stack of something the aisle holds a few of is the
     * common one, and it is exactly the click this feature is judged by.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanPartlyServedClickKeepsTheReason(GameTestHelper helper) {
        AisleFixture aisle = stockedChainAisle(helper, CHEST.toStack(1));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionStations().size(), 2, "both stations are recorded");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the racks were read");
                    helper.assertValueEqual(controller.countOf(CHEST), 1L, "one chest is in a rack");
                    helper.assertValueEqual(controller.countOf(LOG), 0L, "and no log to make a second one of");
                })
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), CHEST, 2);
                    helper.assertTrue(result.isAccepted(), "the chest in the racks is served: " + result);
                    helper.assertValueEqual(result.granted(), 1, "one of the two");
                    helper.assertValueEqual(result.producing(), 0, "and nothing at all is being made");
                    helper.assertValueEqual(result.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "the reason the second one could not be made travels with the accepted result");
                    helper.assertValueEqual(result.about(), Optional.of(LOG),
                            "and it names the item that is really missing, two steps below the chest");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0,
                            "no order was created for a chain that could not be planned");
                })
                .thenSucceed();
    }

    // --- redstone --------------------------------------------------------------------------------------------------

    /**
     * <b>Redstone may start a chain</b> (the M20 decision), and because that is unattended it gets its own guard: a port
     * may have <b>one</b> open plan at a time — the M17 "one open request at a time" rule extended to plans. A clock on
     * a requesting port therefore cannot build a second chain into the same machines while the first is still running.
     * <p>
     * The stock part of a repeated request is unaffected: what is refused is the second chain, not the port.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanFromARedstonePort(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).above();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, CHEST.toStack(), 1, trigger))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionOrders().size(), 2,
                            "a redstone pulse created the whole chain");
                    helper.assertValueEqual(controller.openProductionStepCount(), 1, "one of the two is a step");
                    helper.assertTrue(controller.hasOpenProductionPlan(aisle.absoluteRackPos(OUTPUT_RACK)),
                            "and the port owns that plan");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT_RACK).lastRejection(), Optional.empty(),
                            "nothing was refused");
                })
                .thenExecute(() -> {
                    // A second pulse while the chain runs: no second plan, and the port is told to wait rather than
                    // being sent looking for an item the warehouse is not missing.
                    aisle.requestAt(OUTPUT_RACK, CHEST.toStack(), 1, trigger);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionOrders().size(), 2, "still exactly one chain");
                    helper.assertValueEqual(controller.openProductionStepCount(), 1, "with its one step");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT_RACK).lastRejection(),
                            Optional.of(RequestRejection.PRODUCTION_BUSY),
                            "and the port says the warehouse is busy, not that the chest is missing");
                    helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK - LOGS_PER_CHEST,
                            "so no second batch of logs was ever promised");
                })
                .thenSucceed();
    }

    /**
     * <b>A plan is still this port's while only its root is left</b> (M20 review fix). The steps of a chain are the
     * <b>first</b> orders to finish: the saw's planks are made and stored long before the crafter has turned them into a
     * chest, and for all that time the only open order of the plan is the root, which is nobody's step. Asking "is an open
     * order a step" therefore answered "no plan here" for the longest part of a chain's life, and a clock on a pulsing
     * port could stack a second chain into the same machines — one more batch of logs per pulse, up to the aisle's whole
     * order cap.
     * <p>
     * Here the saw really is played, so the step completes while the root is still fetching, and the pulse that follows
     * must create nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanFromAPortStaysOneAfterItsStepIsDone(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).above();
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while one port's chain runs"));

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, CHEST.toStack(), 1, trigger))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the chain's logs reach the saw"))
                // The player's sawmill works: the planks come back through the warehouse input and are stored.
                .thenExecute(() -> playMachine(helper, aisle, conserved, SAW_RACK, LOG, PLANK, PLANKS_PER_CHEST))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionStepCount(), 0, "the step is finished");
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1,
                            "and only the root is still working");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.hasOpenProductionPlan(aisle.absoluteRackPos(OUTPUT_RACK)),
                            "the port still owns its plan although no step of it is open any more");
                    // The clock pulses again in exactly that window.
                    aisle.requestAt(OUTPUT_RACK, CHEST.toStack(), 1, trigger);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionOrders().size(), 2, "still exactly one chain");
                    helper.assertValueEqual(controller.openProductionStepCount(), 0, "and no new step was created");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT_RACK).lastRejection(),
                            Optional.of(RequestRejection.PRODUCTION_BUSY),
                            "the port is told the warehouse is busy");
                    helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK - LOGS_PER_CHEST,
                            "so no second batch of logs was ever taken out of the racks");
                })
                .thenSucceed();
    }

    // --- the M15 question ------------------------------------------------------------------------------------------

    /**
     * The M15 confirmation, measured over the <b>plan</b>: a click on a chest names the logs it would take out of their
     * reserve, two steps away from the item that was clicked. A screen could never work that out — it knows neither the
     * aisle's patterns nor what their ingredients are promised to.
     * <p>
     * And the plan the click then creates is the one the player accepted: the same walk, from the same snapshot, so the
     * question and what happens cannot disagree.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanMeasuredAgainstTheConfirmation(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, false);
        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    // Every log is reserved, so the one the chain needs comes out of the reserve.
                    keeperRule(aisle, 0, LOG, StockKeeperRules.FIELD_RESERVE, (long) LOGS_IN_STOCK);
                    RequestConfirmation<ItemKey> question = aisle.controller().confirmationFor(CHEST, 1);
                    helper.assertTrue(question.required(), "the click has to be asked about");
                    helper.assertValueEqual(question.ingredients().size(), 1, "one reserved ingredient is named");
                    helper.assertValueEqual(question.ingredients().getFirst().key(), LOG,
                            "and it is the log two steps down, not the chest that was clicked");
                    helper.assertValueEqual(question.ingredients().getFirst().fromReserve(), (long) LOGS_PER_CHEST,
                            "with exactly what the chain would take out of it");
                    helper.assertValueEqual(question.fromReserve(), 0L, "the chest itself has no reserve to cross");
                    helper.assertValueEqual(question.made(), 1L, "and one chest is what the plan's root makes");
                })
                .thenExecute(() -> {
                    // A plain click is answered with the question and nothing else.
                    TerminalRequestOutcome asking = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK),
                            CHEST, 1, Integer.MAX_VALUE, StockAccess.PLAYER, RequestAcknowledgement.NONE);
                    helper.assertTrue(asking.isAsking(), "nothing is requested until the cost is accepted");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0, "no order was created");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and no request was queued");

                    // The answer to that very question buys the plan it was measured from.
                    TerminalRequestOutcome confirmed = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK),
                            CHEST, 1, Integer.MAX_VALUE, StockAccess.PLAYER,
                            asking.question().orElseThrow().acknowledgement());
                    RequestResult result = confirmed.result().orElseThrow();
                    helper.assertTrue(result.isAccepted(), "the accepted cost carries the request out: " + result);
                    helper.assertValueEqual(result.producing(), 1, "the chest is being made");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 2, "as a chain of two");
                    helper.assertValueEqual(aisle.controller().availableStock(LOG),
                            (long) LOGS_IN_STOCK - LOGS_PER_CHEST,
                            "and exactly the logs the question named are spent");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** The chain aisle with {@value #LOGS_IN_STOCK} logs; see {@link #chainAisle(GameTestHelper, boolean, int)}. */
    private static AisleFixture chainAisle(GameTestHelper helper, boolean withMotor) {
        return chainAisle(helper, withMotor, LOGS_IN_STOCK);
    }

    /**
     * Two production stations whose patterns form a chain — one log to {@value #PLANKS_PER_RUN} planks at the saw,
     * {@value #PLANKS_PER_CHEST} planks to one chest at the crafter — with a warehouse input for both products, an
     * output, a stock keeper and {@code logs} logs in the racks.
     *
     * @param withMotor whether the crane may move at all; a test that only looks at the orders a click created runs
     *                  without one, so it sees them exactly as they were made
     */
    private static AisleFixture chainAisle(GameTestHelper helper, boolean withMotor, int logs) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        if (withMotor)
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
        if (logs > 0)
            aisle.storage(LOG_RACK, LOG.toStack(logs));
        else
            aisle.storage(LOG_RACK);
        aisle.storage(STORE_RACK);
        aisle.storage(SPARE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(SAW_RACK);
        aisle.production(CRAFTER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        setPattern(helper, aisle, SAW_RACK, 0, LOG, 1, PLANK, PLANKS_PER_RUN);
        setPattern(helper, aisle, CRAFTER_RACK, 0, PLANK, PLANKS_PER_CHEST, CHEST, 1);
        return aisle;
    }

    /**
     * The chain aisle with <b>no logs at all</b> and {@code stocked} lying in a rack instead — for the clicks that are
     * about what the racks can serve on their own while nothing can be made.
     */
    private static AisleFixture stockedChainAisle(GameTestHelper helper, ItemStack stocked) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(LOG_RACK);
        aisle.storage(STORE_RACK, stocked);
        aisle.storage(SPARE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(SAW_RACK);
        aisle.production(CRAFTER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        setPattern(helper, aisle, SAW_RACK, 0, LOG, 1, PLANK, PLANKS_PER_RUN);
        setPattern(helper, aisle, CRAFTER_RACK, 0, PLANK, PLANKS_PER_CHEST, CHEST, 1);
        return aisle;
    }

    /** One ingredient to one result in {@code slot} of the station at {@code rack}. */
    private static void setPattern(GameTestHelper helper, AisleFixture aisle, RackPosition rack, int slot,
            ItemKey ingredient, int ingredientCount, ItemKey result, int resultCount) {
        WarehouseProductionBlockEntity station = aisle.productionAt(rack);
        helper.assertTrue(station.setPatternEntry(slot, 0, ingredient, ingredientCount),
                "the ingredient of " + rack + " slot " + slot);
        helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, result, resultCount),
                "the result of " + rack + " slot " + slot);
    }

    /** Writes one field of the keeper's rule {@code index} for {@code key}. */
    private static void keeperRule(AisleFixture aisle, int index, ItemKey key, int field, long value) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(index, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(index, field, null, value);
    }

    /** Waits until both stations are recorded, their patterns are readable and the logs are in the index. */
    private static void assertChainReady(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 2, "both production stations are recorded");
        helper.assertValueEqual(controller.aislePatterns().size(), 2, "with one pattern each");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage locations were read");
        helper.assertTrue(controller.producibleKeys().contains(CHEST), "and the aisle knows it can make a chest");
    }

    /** An order at the output station, which must be accepted. */
    private static RequestResult order(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount) {
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
        helper.assertTrue(result.isAccepted(), "the order for " + key + " is accepted: " + result);
        return result;
    }

    /** An order that must be refused for {@code refusal} about {@code about}, leaving the warehouse untouched. */
    private static void assertRefused(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount,
            PlanRefusal refusal, ItemKey about, RequestRejection rejection) {
        int ordersBefore = aisle.controller().productionOrders().size();
        int requestsBefore = aisle.controller().openRequestCount();
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
        helper.assertFalse(result.isAccepted(), "the order for " + key + " must be refused: " + result);
        helper.assertValueEqual(result.refusal(), Optional.of(refusal), "the reason the chain could not be planned");
        helper.assertValueEqual(result.about(), Optional.of(about), "and the item it is about");
        helper.assertValueEqual(result.rejection(), Optional.of(rejection), "the reason a station's goggles show");
        helper.assertValueEqual(aisle.controller().productionOrders().size(), ordersBefore,
                "a refused chain creates no order");
        helper.assertValueEqual(aisle.controller().openRequestCount(), requestsBefore,
                "and leaves no request waiting");
    }

    /**
     * The test plays one of the player's machines: it takes every ingredient out of the station's buffer and hands the
     * product back through the warehouse input, exactly as a sawmill plus funnel would. This is the only kind of step in
     * which the census expectation changes.
     */
    private static void playMachine(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved,
            RackPosition station, ItemKey ingredient, ItemKey product, int products) {
        int taken = extractAll(aisle.handlerAt(aisle.rackPos(station)), ingredient);
        helper.assertTrue(taken > 0, "the machine at " + station + " took its ingredients");
        ItemCensus.change(conserved, ingredient, -taken);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), product.toStack(products));
        ItemCensus.change(conserved, product, products);
    }

    /** Takes every item of {@code key} out of {@code handler} (the player's machine emptying the station). */
    private static int extractAll(IItemHandler handler, ItemKey key) {
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!key.matches(handler.getStackInSlot(slot)))
                continue;
            taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        return taken;
    }

    /** Planks lying in the aisle's storage inventories, i.e. in a rack rather than in a machine or a station. */
    private static long storedPlanks(AisleFixture aisle) {
        return aisle.storedAt(LOG_RACK, PLANK) + aisle.storedAt(STORE_RACK, PLANK) + aisle.storedAt(SPARE_RACK, PLANK);
    }

    /** The aisle's single open step; fails the test when the chain is not exactly one step deep. */
    private static ProductionOrder<ItemKey, RackPosition> onlyStep(GameTestHelper helper, AisleFixture aisle) {
        List<ProductionOrder<ItemKey, RackPosition>> steps = aisle.controller().productionOrders().stream()
                .filter(ProductionOrder::isStep).toList();
        if (steps.size() != 1) {
            helper.fail("expected exactly one step of a plan, found " + steps.size());
            throw new IllegalStateException("unreachable");
        }
        return steps.getFirst();
    }

    /** The aisle's production order {@code id}; fails the test when it is gone. */
    private static ProductionOrder<ItemKey, RackPosition> orderOf(GameTestHelper helper, AisleFixture aisle, UUID id) {
        Optional<ProductionOrder<ItemKey, RackPosition>> order = aisle.controller().productionOrder(id);
        if (order.isEmpty()) {
            helper.fail("expected the production order " + id + " to be there");
            throw new IllegalStateException("unreachable");
        }
        return order.get();
    }
}
