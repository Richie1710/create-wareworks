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
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.StockRulePause;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of <b>what a production plan does when something goes wrong</b> ({@code docs/warehouse-system.md} §3.5.4 and
 * §3.5.6, ADR-032, M20, issue #4). {@link ProductionPlanGameTests} proves that a chain can be planned and run; this holder
 * proves that a chain which cannot be finished is <b>bounded, reported and not repeatable</b> — the part that decides
 * whether recursive production is safe to ship.
 * <p>
 * Five claims, one test each (plus the one that needs two):
 * <ul>
 * <li>{@code productionplanparentfetchesnothingwhileblocked} — <b>a blocked parent fetches nothing at all</b>, not even
 * the ingredients the racks could pay for right now. The crafter here needs planks <i>and</i> a nail, and the nails are
 * lying in a rack the whole time: without the rule the crane would carry one to the machine within a few ticks and a
 * failing chain would leave half-sets of ingredients in several machines;</li>
 * <li>{@code productionplanparentdoesnottimeoutwhileastepruns} — the other half of the same rule: <b>a blocked order's
 * deadline does not run</b>, and it starts over when its last step ends. Its own deadline is deliberately let expire
 * while the step below it works;</li>
 * <li>{@code productionplansteptimeoutendstheplan} — a step that times out <b>ends its whole plan</b> in the same tick:
 * the order above it is cancelled, the request behind it gets its promise back, and what the machine swallowed is
 * reported rather than silently forgotten;</li>
 * <li>{@code productionplancanceltakesitsstepswithit} — cancelling a plan cancels its unfinished steps, and what the
 * crane is carrying at that moment lands back in a rack;</li>
 * <li>{@code productionplancancelleavesadeliveredsteprunning} — but a step whose ingredients are already in a machine is
 * <b>left running</b>, so those items still become a product the player keeps;</li>
 * <li>{@code productionplanlostbatchstopstheitemforeveryone} — the safety stop of ADR-027, widened: the first order of
 * <b>any</b> kind that ends with ingredients delivered and no result stops that item for a player, for redstone and for
 * the warehouse's own restocking, the pause survives a reload <b>and</b> an aisle where no rule governs the item, and
 * only a player lifts it — with a sneak-click on the production station in front of the machine, which is the way back
 * that is reachable whether or not a stock keeper's rule happens to govern the item;</li>
 * <li>{@code productionplanstepkeepsthebatchitsmachinemade} — the batch a step's machine made is credited to that step
 * and not to an older order for the same item that is waiting too, so a working factory does not stop itself;</li>
 * <li>{@code productionplansurvivesasavemidflight} — a half-finished chain comes back from a save and runs to the end.</li>
 * </ul>
 * An {@link ItemCensus} runs on every tick of every test in which the crane moves items, and its expectation changes only
 * in the steps where the test itself <b>is</b> the player's machine.
 * <p>
 * Layout ({@code aisle_16x10x7}, the {@link ProductionPlanGameTests} aisle plus a nail rack): right rack plane, the saw
 * station at 0 (one log makes {@value #PLANKS_PER_RUN} planks), a warehouse input at 1, a warehouse output at 2, the
 * crafter station at 3 ({@value #PLANKS_PER_CHEST} planks make one chest) and a stock keeper at 4. Left rack plane: nails
 * at 0, logs at 1 and two empty storage locations at 2 and 3.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ProductionPlanFailureGameTests {
    /**
     * The tests that shorten {@code productionOrderTimeoutTicks} share one batch: they all set it to the same
     * {@value #SHORT_ORDER_TIMEOUT}, so they cannot see a value of each other's that they did not expect
     * ({@link ConfigOverrides}).
     */
    private static final String TIMEOUT_BATCH = "wareworksProductionPlanTimeouts";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** The crane speed the other job tests use, so a whole chain fits into a test's tick budget. */
    private static final int TEST_RPM = 128;

    private static final RackPosition SAW_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition NAIL_RACK = new RackPosition(0, 0, Side.LEFT);
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
    /** Nails one chest costs, in the two-ingredient variant: the ingredient that is in stock all along. */
    private static final int NAILS_PER_CHEST = 1;
    private static final int LOGS_IN_STOCK = 16;
    private static final int NAILS_IN_STOCK = 8;
    /** Logs one chest costs: one run of the saw per run of the crafter. */
    private static final int LOGS_PER_CHEST = PLANKS_PER_CHEST / PLANKS_PER_RUN;

    private static final int TIMEOUT_TICKS = 1600;
    /** A chain is two machines, four crane trips and two stores, so it gets the long budget. */
    private static final int CHAIN_TIMEOUT_TICKS = 4000;
    private static final int SETTLE_TICKS = 20;
    /**
     * A timeout short enough for a test and far above one crane trip, so only a genuinely stuck order trips it — and so
     * that an order waiting for a step outlives its own deadline several times over.
     */
    private static final int SHORT_ORDER_TIMEOUT = 120;

    private ProductionPlanFailureGameTests() {
    }

    @AfterBatch(batch = TIMEOUT_BATCH)
    public static void restoreTimeoutConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- a blocked parent fetches nothing --------------------------------------------------------------------------

    /**
     * <b>An order waiting for a step of its own plan fetches nothing at all.</b> The crafter's pattern needs four planks
     * <i>and</i> a nail; the planks have to be made, the nails are lying in a rack from the first tick. Without the rule
     * the crane would carry a nail to the crafter within a few ticks — and a chain that then failed would have left a
     * half set of ingredients in a machine that can never run on it ({@code docs/warehouse-system.md} §3.5.4).
     * <p>
     * The proof is in three places at once: the planner is offered <b>only</b> the step's own supply need, not one line
     * of the order above it; the crafter's buffer stays empty for the whole time the step runs although its nails are two
     * racks away; and the nails are promised all the same, so nobody else can take them either. The moment the step's
     * planks are stored, both ingredients travel and the chest is made.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanParentFetchesNothingWhileBlocked(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        twoIngredientChest(helper, aisle);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a blocked order waits"));
        UUID[] stepId = new UUID[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "the whole chest has to be made");
                    stepId[0] = onlyStep(helper, aisle).id();

                    // What the crane is offered is the whole of the rule: one supply need, and it belongs to the step.
                    List<PlannerInput.SupplyNeed<ItemKey, RackPosition>> needs = controller.supplyNeeds();
                    helper.assertValueEqual(needs.size(), 1, "only the step asks for anything");
                    helper.assertValueEqual(needs.getFirst().key(), LOG, "and it asks for its own logs");
                    helper.assertValueEqual(needs.getFirst().station(), SAW_RACK, "at its own machine");
                    // The nail is promised all the same: a blocked order still holds what it will need.
                    helper.assertValueEqual(controller.availableStock(NAIL),
                            (long) NAILS_IN_STOCK - NAILS_PER_CHEST, "the nail is spoken for from the first tick");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the logs reached the saw"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    // The crane has had a whole dispatch cycle with nothing else to do: if the rule did not hold, the
                    // nail would be in the crafter by now.
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, NAIL), 0L,
                            "and not one nail was carried to the machine that is waiting for a step");
                    helper.assertValueEqual(aisle.storedAt(NAIL_RACK, NAIL), (long) NAILS_IN_STOCK,
                            "every nail is still in its rack");
                    helper.assertValueEqual(aisle.controller().supplyNeeds().size(), 0,
                            "the step has everything it asked for, and the order above it asks for nothing");
                    playMachine(helper, aisle, conserved, SAW_RACK, LOG, PLANK, PLANKS_PER_CHEST);
                })
                // The step completes as its planks are stored, and only then does the order above it fetch.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(orderOf(helper, aisle, stepId[0]).state(), ProductionOrderState.COMPLETE,
                            "the step completed");
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, NAIL), (long) NAILS_PER_CHEST,
                            "now the nail travels too");
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK), (long) PLANKS_PER_CHEST,
                            "together with the planks the step made");
                })
                .thenExecute(() -> playChest(helper, aisle, conserved))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, CHEST), 1L,
                        "and the chest reached the output the request named"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "both orders ended");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0, "nothing was paused");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>A blocked order's deadline does not run, and it starts over when its last step ends.</b> With a
     * {@value #SHORT_ORDER_TIMEOUT}-tick timeout the test lets the ordered item's own deadline expire several times over
     * while the step below it is working: the step keeps making progress (its planks arrive one at a time), and the order
     * above it must survive its own expired deadline and then be given a whole timeout of its own.
     * <p>
     * Without the first half a chain would die of a timeout while every machine in it worked perfectly; without the
     * second half it would die in the very tick it became ready to run.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanParentDoesNotTimeOutWhileAStepRuns(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a step keeps a chain alive"));
        UUID[] rootId = new UUID[1];
        long[] rootDeadline = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle, CHEST, 1);
                    ProductionOrder<ItemKey, RackPosition> root = onlyRoot(helper, aisle);
                    rootId[0] = root.id();
                    rootDeadline[0] = root.deadlineTick();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the logs reached the saw"))
                // The player's machine takes the logs and then works slowly: one plank at a time comes back.
                .thenExecute(() -> {
                    int taken = extractAll(aisle.handlerAt(aisle.rackPos(SAW_RACK)), LOG);
                    helper.assertTrue(taken > 0, "the saw took its log");
                    ItemCensus.change(conserved, LOG, -taken);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(orderOf(helper, aisle, onlyStep(helper, aisle).id())
                        .state(), ProductionOrderState.WAITING_FOR_RESULT, "the machine really has the log"))
                .thenExecute(() -> deliverOnePlank(helper, aisle, conserved, 1))
                .thenWaitUntil(() -> assertPlanksStored(helper, aisle, 1))
                .thenExecute(() -> deliverOnePlank(helper, aisle, conserved, 2))
                .thenWaitUntil(() -> assertPlanksStored(helper, aisle, 2))
                .thenExecute(() -> deliverOnePlank(helper, aisle, conserved, 3))
                .thenWaitUntil(() -> assertPlanksStored(helper, aisle, 3))
                // Here is the claim: the ordered item's own deadline is long gone and its order is untouched.
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getGameTime() > rootDeadline[0],
                        "the deadline the chain was created with has passed"))
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> root = orderOf(helper, aisle, rootId[0]);
                    helper.assertTrue(root.isOpen(), "and the order that is waiting for its step is still open");
                    helper.assertValueEqual(root.deadlineTick(), rootDeadline[0],
                            "with the very deadline it was created with: a blocked order's clock does not run");
                    helper.assertValueEqual(root.state(), ProductionOrderState.WAITING_FOR_INGREDIENTS,
                            "it has not even started fetching");
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK), 0L,
                            "and nothing was carried to its machine");
                })
                // The last plank completes the step, and the order above it gets a whole timeout of its own.
                .thenExecute(() -> deliverOnePlank(helper, aisle, conserved, PLANKS_PER_CHEST))
                .thenWaitUntil(() -> {
                    ProductionOrder<ItemKey, RackPosition> root = orderOf(helper, aisle, rootId[0]);
                    helper.assertTrue(root.isOpen(), "the order survived the step it was waiting for");
                    helper.assertTrue(root.deadlineTick() > helper.getLevel().getGameTime(),
                            "and its timeout started over instead of expiring in the tick it became ready");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK),
                        (long) PLANKS_PER_CHEST, "the planks the step made reach the crafter"))
                .thenExecute(() -> playChest(helper, aisle, conserved))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, CHEST), 1L, "and the chest is delivered");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "the chain is done");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "and a chain that worked pauses nothing");
                })
                .thenSucceed();
    }

    // --- a step that ends badly ends its plan ----------------------------------------------------------------------

    /**
     * <b>A step that times out ends its whole plan.</b> The saw is given its log and nobody ever plays it: the step gives
     * up, the order above it — which has fetched nothing, because it was waiting — is cancelled in the same tick, and the
     * request behind it is given its promise back instead of waiting for a chest nobody is making.
     * <p>
     * What the machine swallowed is <b>reported and not silently forgotten</b>: the log is in the saw's buffer where the
     * census still finds it, the finished step still says what it handed over, and the plan's own unrecovered total is
     * that same number.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanStepTimeoutEndsThePlan(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a chain fails"));
        UUID[] ids = new UUID[2];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle, CHEST, 1);
                    ids[0] = onlyStep(helper, aisle).id();
                    ids[1] = onlyRoot(helper, aisle).id();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the logs reached the saw, and nobody is going to play it"))
                .thenWaitUntil(() -> helper.assertValueEqual(orderOf(helper, aisle, ids[0]).state(),
                        ProductionOrderState.TIMED_OUT, "the step gives up"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    ProductionOrder<ItemKey, RackPosition> step = orderOf(helper, aisle, ids[0]);
                    ProductionOrder<ItemKey, RackPosition> root = orderOf(helper, aisle, ids[1]);
                    helper.assertValueEqual(root.state(), ProductionOrderState.CANCELLED,
                            "and the order that was waiting for it ends with it, in the same tick");
                    helper.assertValueEqual(root.deliveredIngredients(), 0,
                            "it had handed nothing over, because a blocked order fetches nothing");
                    helper.assertValueEqual(step.deliveredIngredients(), LOGS_PER_CHEST,
                            "the step still says what its machine got");
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "nothing of the plan is open");
                    helper.assertValueEqual(controller.openRequestCount(), 0,
                            "and the request was given its promise back rather than waiting for ever");
                    helper.assertValueEqual(controller.availableStock(LOG),
                            (long) LOGS_IN_STOCK - LOGS_PER_CHEST, "only the log the saw really got is spent");
                    helper.assertValueEqual(controller.availableStock(NAIL), (long) NAILS_IN_STOCK,
                            "and nothing else is promised any more");
                    helper.assertTrue(controller.reservations().isEmpty(), "nothing stays reserved");
                    // The plan's whole cost, readable from either end of it.
                    helper.assertValueEqual(controller.productionPlanOf(ids[1]).size(), 2,
                            "the ended plan can still be read");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                            "the log is still lying in the machine: nothing was taken back out of it");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>Cancelling a plan takes its unfinished steps with it</b>, and what the crane is carrying at that moment lands
     * back in a rack: a cancelled step's job aborts before the pick, or reroutes what it already holds into storage
     * ({@code CraneDispatch#onOwnersCancelled}).
     * <p>
     * The cancellation is aimed at the ordered item's own order while the step's crane trip is in the air, which is the
     * case that could leave the aisle with a promise nobody owns. Item conservation is checked on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPlanCancelTakesItsStepsWithIt(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a plan is cancelled"));
        UUID[] ids = new UUID[2];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle, CHEST, 1);
                    ids[0] = onlyStep(helper, aisle).id();
                    ids[1] = onlyRoot(helper, aisle).id();
                })
                // The crane is carrying the step's logs: nothing has been handed to a machine yet.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().heldItems().count(LOG), LOGS_PER_CHEST,
                        "the crane is carrying the step's logs"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().cancelProductionOrder(ids[1]).isPresent(),
                            "the player gives the chest up");
                    ProductionOrder<ItemKey, RackPosition> step = orderOf(helper, aisle, ids[0]);
                    helper.assertValueEqual(step.state(), ProductionOrderState.CANCELLED,
                            "and the step that was making its planks is cancelled with it");
                    helper.assertValueEqual(step.deliveredIngredients(), 0, "having cost nothing");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "nothing is open");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and nobody is waiting");
                })
                // The logs the crane held go back into storage, so the aisle ends exactly as it began.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK,
                            "the logs in the crane's head were put back into a rack");
                    helper.assertValueEqual(aisle.controller().availableStock(LOG), (long) LOGS_IN_STOCK,
                            "and nothing is promised any more");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "a cancellation that cost nothing pauses nothing");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>A step whose ingredients are already in a machine is left running</b> when its plan is cancelled. Those items
     * are in the machine and nothing takes them back out, so letting the step finish turns them into a product the player
     * keeps instead of a pure loss ({@code docs/warehouse-system.md} §3.5.4) — it stops being a step, its planks land in
     * stock as items nobody promised, and nothing is paused, because nothing was lost.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanCancelLeavesADeliveredStepRunning(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a delivered step runs on"));
        UUID[] ids = new UUID[2];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle, CHEST, 1);
                    ids[0] = onlyStep(helper, aisle).id();
                    ids[1] = onlyRoot(helper, aisle).id();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the step's logs are at the machine"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().cancelProductionOrder(ids[1]).isPresent(),
                            "the player gives the chest up");
                    ProductionOrder<ItemKey, RackPosition> step = orderOf(helper, aisle, ids[0]);
                    helper.assertTrue(step.isOpen(), "the step runs on: its items are already in the machine");
                    helper.assertFalse(step.isStep(), "as an ordinary order that feeds nobody");
                    helper.assertValueEqual(step.deliveredIngredients(), LOGS_PER_CHEST,
                            "and it still reports what was handed over");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1, "one order is left");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "nothing is paused while it can still finish");
                })
                // The machine works after all, and the planks the player paid for land in the racks.
                .thenExecute(() -> playMachine(helper, aisle, conserved, SAW_RACK, LOG, PLANK, PLANKS_PER_CHEST))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(storedPlanks(aisle), (long) PLANKS_PER_CHEST,
                            "the product came back and was stored");
                    helper.assertValueEqual(aisle.controller().availableStock(PLANK), (long) PLANKS_PER_CHEST,
                            "as items nobody promised, so the player can take them");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "and nothing is open");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0, "still nothing paused");
                })
                .thenSucceed();
    }

    // --- the lost batch, for everyone ------------------------------------------------------------------------------

    /**
     * <b>The safety stop covers every kind of order</b> (the M20 decision over ADR-027's automatic-only stop). A
     * <b>player's</b> chain loses a batch — the saw is given its log and nobody plays it — and from that moment the
     * warehouse refuses to make planks at all: for a player's click, for a redstone port and for its own restocking.
     * <p>
     * Three properties of the pause that the design turns on, all asserted here:
     * <ul>
     * <li>it names the kind of order that armed it ({@link StockRulePause.Cause#ORDER_TIMED_OUT}) and what it cost;</li>
     * <li><b>it survives although no stock rule governs planks</b> — a rule-born pause is forgotten with its rule, and
     * one armed by anybody else must not be, or the next click would rebuild the same chain into the same machine;</li>
     * <li>it survives a reload, and only a <b>player</b> lifts it.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanLostBatchStopsTheItemForEveryone(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = chainAisle(helper, true);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).above();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> order(helper, aisle, CHEST, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the player's chain hands its logs to the saw"))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the lost batch stops the warehouse from making planks"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    StockRulePause pause = controller.stockRulePause(PLANK).orElseThrow();
                    helper.assertValueEqual(pause.cause(), StockRulePause.Cause.ORDER_TIMED_OUT,
                            "an order somebody asked for timed out, not an automatic one");
                    helper.assertFalse(pause.isRuleBorn(), "so there is no rule it could be forgotten with");
                    helper.assertValueEqual(pause.unrecovered(), (long) LOGS_PER_CHEST,
                            "and it says what the machine swallowed");
                    helper.assertTrue(controller.stockRules().isEmpty(), "no rule governs anything in this aisle");

                    // A player's click, at any level: the chain is not rebuilt into the same machine.
                    assertRefused(helper, aisle, CHEST, 1, PlanRefusal.PAUSED, PLANK,
                            RequestRejection.PRODUCTION_PAUSED);
                    assertRefused(helper, aisle, PLANK, PLANKS_PER_RUN, PlanRefusal.PAUSED, PLANK,
                            RequestRejection.PRODUCTION_PAUSED);
                })
                // And redstone, which is the unattended trigger the stop really has to hold back.
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, CHEST.toStack(), 1, trigger))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "a pulse creates no order for a paused item");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT_RACK).lastRejection(),
                            Optional.of(RequestRejection.PRODUCTION_PAUSED),
                            "and the port says that the warehouse has stopped making it");
                })
                .thenExecute(() -> {
                    // A rule for an unrelated item makes the aisle re-read its rules, which is what forgets a rule-born
                    // pause. This one is not: nothing here may resume production behind the player's back.
                    keeperRule(aisle, 0, LOG, StockKeeperRules.FIELD_MAXIMUM, 64L);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 1,
                            "a pause no rule ever armed is not forgotten with the rules");
                    helper.assertTrue(controller.stockRulePause(PLANK).isPresent(), "it is still the plank that is held");

                    // And it survives a reload, like every other part of the safety stop.
                    CompoundTag saved = controller.saveWithFullMetadata(helper.getLevel().registryAccess());
                    WarehouseControllerBlockEntity loaded = loadCopy(helper, controller, saved);
                    StockRulePause reloaded = loaded.stockRulePause(PLANK).orElseThrow();
                    helper.assertValueEqual(reloaded.cause(), StockRulePause.Cause.ORDER_TIMED_OUT,
                            "the cause is saved by name and read back");
                    helper.assertValueEqual(reloaded.unrecovered(), (long) LOGS_PER_CHEST, "with its whole cost");
                })
                .thenExecute(() -> {
                    // The one way back is a player's own action, and it has to be one a player can really reach on THIS
                    // aisle (M20 review fix): no stock rule governs planks here, so the stock keeper's own resume - which
                    // only ever touches an item one of its rules governs - could never lift this pause. A sneak-click
                    // with an empty hand on the production station in front of the machine does.
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    player.setShiftKeyDown(true);
                    // It resumes only what THIS station makes, so the crafter says nothing about the saw's planks.
                    helper.useBlock(aisle.rackPos(CRAFTER_RACK), player);
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                            "a click on the station that makes chests leaves the planks stopped");

                    helper.useBlock(aisle.rackPos(SAW_RACK), player);
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isEmpty(),
                            "and a click on the saw, whose pattern makes the planks, lifts it");
                    RequestResult result = order(helper, aisle, CHEST, 1);
                    helper.assertValueEqual(result.producing(), 1, "and the chain is planned again");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 1, "as a chain of two");
                })
                .thenSucceed();
    }

    // --- who gets the batch a machine made -------------------------------------------------------------------------

    /**
     * <b>The batch a step's machine made is credited to that step</b> (M20 review fix), and not to an older order for the
     * same item that happens to be waiting too.
     * <p>
     * A step has exactly one way to be completed — items the warehouse really stored out of one of its own inputs — while
     * an ordinary order is completed by a rising stock level just as well, and counts arrivals from the moment it is
     * created, i.e. while its own crane is still fetching. Offered in plain creation order, the older plank order took the
     * planks the step's saw had just made: the step counted nothing, its deadline was not pushed out, and it timed out
     * minutes later with its logs gone — which armed the safety stop for planks, an item every machine here makes
     * perfectly, and cancelled the rest of the chain with it.
     * <p>
     * Here the older order asks for planks first, the chain is ordered afterwards, and one run of planks arrives. The step
     * has to be the one that finishes, and the order above it has to start fetching.
     * <p>
     * The player gives up the plank <b>request</b> in between, which is what leaves an ordinary production order running
     * with nothing to deliver to ({@code ProductionOrders#detachRequest}) — and it is what keeps the test about the
     * arrival: an open request for planks would have the crane carry them to an output, and then neither order would be
     * the one that lost them.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanStepKeepsTheBatchItsMachineMade(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while two orders wait for the same item"));
        UUID[] older = new UUID[1];
        UUID[] givenUp = new UUID[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                // An ordinary order for planks, which nobody is going to make: its log reaches the saw and stays there.
                .thenExecute(() -> {
                    givenUp[0] = order(helper, aisle, PLANK, PLANKS_PER_RUN).request().orElseThrow().id();
                    older[0] = onlyRoot(helper, aisle).id();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), 1L,
                        "the older order's log is at the saw"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().cancelRequest(givenUp[0]).isPresent(),
                            "the player gives the plank request up");
                    helper.assertTrue(orderOf(helper, aisle, older[0]).isOpen(),
                            "its production order runs on, and its planks will simply land in stock");
                })
                // And now the chain, whose step makes planks at the very same saw.
                .thenExecute(() -> order(helper, aisle, CHEST, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(onlyStep(helper, aisle).deliveredIngredients(),
                        LOGS_PER_CHEST, "the step's own log is at the saw too, so its machine could have produced"))
                // One run of planks comes back through the input: the step's machine made it.
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(PLANKS_PER_RUN));
                    ItemCensus.change(conserved, PLANK, PLANKS_PER_RUN);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 0,
                            "the step is the one the batch completed");
                    helper.assertValueEqual(orderOf(helper, aisle, older[0]).produced(), 0L,
                            "and the older order, which a rising level completes just as well, took nothing");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK),
                        (long) PLANKS_PER_CHEST, "so the order above the step is unblocked and its planks travel"))
                .thenExecute(() -> helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                        "and nothing was ever stopped: every machine here worked"))
                .thenSucceed();
    }

    // --- a save in the middle of a chain ---------------------------------------------------------------------------

    /**
     * <b>A half-finished chain survives a save and reload.</b> The step's logs are already at the saw when the controller
     * is saved and rebuilt from that save, exactly as a rejoin does it: both orders come back, the link between them
     * comes back, the order above still fetches nothing — and the chain then runs to the end, product through a rack and
     * chest to the output.
     * <p>
     * A plan the save data no longer describes is the other half of this rule and is covered by
     * {@code productionplanbrokenbysavedataisended}: it is ended cleanly, and never left waiting for a parent that is
     * gone.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void productionPlanSurvivesASaveMidFlight(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, true);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "across a save in the middle of a chain"));
        UUID[] ids = new UUID[2];

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle, CHEST, 1);
                    ids[0] = onlyStep(helper, aisle).id();
                    ids[1] = onlyRoot(helper, aisle).id();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), (long) LOGS_PER_CHEST,
                        "the step's logs are at the machine when the world is saved"))
                .thenExecute(() -> installController(helper, aisle,
                        aisle.controller().saveWithFullMetadata(helper.getLevel().registryAccess())))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    ProductionOrder<ItemKey, RackPosition> step = orderOf(helper, aisle, ids[0]);
                    ProductionOrder<ItemKey, RackPosition> root = orderOf(helper, aisle, ids[1]);
                    helper.assertTrue(step.isOpen() && root.isOpen(), "both orders of the chain are back and running");
                    helper.assertTrue(step.isStep(), "the step still knows which line it is making its product for");
                    helper.assertValueEqual(controller.productionPlanOf(ids[0]).size(), 2, "so it is still one plan");
                    helper.assertValueEqual(step.deliveredIngredients(), LOGS_PER_CHEST,
                            "and what the machine already got survived the save");
                    helper.assertValueEqual(controller.availableStock(PLANK), 0L,
                            "the planks are still promised to the order above");
                    helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK), 0L,
                            "which still fetches nothing while its step runs");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "and a reload pauses nothing");
                })
                .thenExecute(() -> playMachine(helper, aisle, conserved, SAW_RACK, LOG, PLANK, PLANKS_PER_CHEST))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, PLANK),
                        (long) PLANKS_PER_CHEST, "the restored chain carries on through a rack to the crafter"))
                .thenExecute(() -> playChest(helper, aisle, conserved))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, CHEST), 1L,
                            "and the chest the player ordered before the save is delivered");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "the chain is done");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and the request was served");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * Two production stations whose patterns form a chain — one log to {@value #PLANKS_PER_RUN} planks at the saw,
     * {@value #PLANKS_PER_CHEST} planks to one chest at the crafter — with a warehouse input for both products, an
     * output, a stock keeper (holding <b>no</b> rule), {@value #LOGS_IN_STOCK} logs and {@value #NAILS_IN_STOCK} nails.
     *
     * @param withMotor whether the crane may move at all
     */
    private static AisleFixture chainAisle(GameTestHelper helper, boolean withMotor) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        if (withMotor)
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.storage(NAIL_RACK, NAIL.toStack(NAILS_IN_STOCK));
        aisle.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
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
     * Adds a second ingredient to the crafter's pattern: a chest now costs {@value #PLANKS_PER_CHEST} planks
     * <b>and</b> {@value #NAILS_PER_CHEST} nail. The nails are in a rack all along, so this is the pattern that shows
     * whether a blocked order fetches what it could pay for.
     */
    private static void twoIngredientChest(GameTestHelper helper, AisleFixture aisle) {
        helper.assertTrue(aisle.productionAt(CRAFTER_RACK).setPatternEntry(0, 1, NAIL, NAILS_PER_CHEST),
                "the crafter's second ingredient");
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

    /** Waits until both stations are recorded, their patterns are readable and the stock is in the index. */
    private static void assertChainReady(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 2, "both production stations are recorded");
        helper.assertValueEqual(controller.aislePatterns().size(), 2, "with one pattern each");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage locations were read");
        helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in the index");
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
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
        helper.assertFalse(result.isAccepted(), "the order for " + key + " must be refused: " + result);
        helper.assertValueEqual(result.refusal(), Optional.of(refusal), "the reason the chain could not be planned");
        helper.assertValueEqual(result.about(), Optional.of(about), "and the item it is about");
        helper.assertValueEqual(result.rejection(), Optional.of(rejection), "the reason a station's goggles show");
        helper.assertValueEqual(aisle.controller().productionOrders().size(), ordersBefore,
                "a refused chain creates no order");
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

    /** The crafter: it takes the planks (and any nails) and hands one chest back through the input. */
    private static void playChest(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved) {
        int planks = extractAll(aisle.handlerAt(aisle.rackPos(CRAFTER_RACK)), PLANK);
        int nails = extractAll(aisle.handlerAt(aisle.rackPos(CRAFTER_RACK)), NAIL);
        helper.assertTrue(planks > 0, "the crafter took its planks");
        ItemCensus.change(conserved, PLANK, -planks);
        ItemCensus.change(conserved, NAIL, -nails);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), CHEST.toStack(1));
        ItemCensus.change(conserved, CHEST, 1);
    }

    /** One plank of a slow machine's run, handed back through the warehouse input. */
    private static void deliverOnePlank(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved,
            int number) {
        helper.assertValueEqual(aisle.stationCount(INPUT_RACK, PLANK), 0L,
                "the input is empty before plank " + number + ", so every plank is a trip of its own");
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(1));
        ItemCensus.change(conserved, PLANK, 1);
    }

    /** Waits until {@code planks} of a slow run have been stored in a rack and credited to the step. */
    private static void assertPlanksStored(GameTestHelper helper, AisleFixture aisle, int planks) {
        helper.assertValueEqual(storedPlanks(aisle), (long) planks, "plank " + planks + " is in a rack");
        helper.assertValueEqual(onlyStep(helper, aisle).produced(), (long) planks,
                "and the step was credited with it");
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
        return aisle.storedAt(NAIL_RACK, PLANK) + aisle.storedAt(LOG_RACK, PLANK) + aisle.storedAt(STORE_RACK, PLANK)
                + aisle.storedAt(SPARE_RACK, PLANK);
    }

    /** The aisle's single open step; fails the test when the chain is not exactly one step deep. */
    private static ProductionOrder<ItemKey, RackPosition> onlyStep(GameTestHelper helper, AisleFixture aisle) {
        return only(helper, aisle.controller().productionOrders().stream().filter(ProductionOrder::isStep).toList(),
                "step");
    }

    /** The aisle's single order that is nobody's step. */
    private static ProductionOrder<ItemKey, RackPosition> onlyRoot(GameTestHelper helper, AisleFixture aisle) {
        return only(helper, aisle.controller().productionOrders().stream().filter(order -> !order.isStep()).toList(),
                "root");
    }

    private static ProductionOrder<ItemKey, RackPosition> only(GameTestHelper helper,
            List<ProductionOrder<ItemKey, RackPosition>> found, String what) {
        if (found.size() != 1) {
            helper.fail("expected exactly one " + what + " of a plan, found " + found.size());
            throw new IllegalStateException("unreachable");
        }
        return found.getFirst();
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

    /** Replaces the live controller with one loaded from {@code tag}, the way a reload builds it. */
    private static void installController(GameTestHelper helper, AisleFixture aisle, CompoundTag tag) {
        WarehouseControllerBlockEntity live = aisle.controller();
        helper.getLevel().setBlockEntity(loadCopy(helper, live, tag));
        helper.assertTrue(live.isRemoved(), "the controller was really replaced");
    }

    /** A fresh controller from a save, the way a reload builds one. */
    private static WarehouseControllerBlockEntity loadCopy(GameTestHelper helper,
            WarehouseControllerBlockEntity live, CompoundTag tag) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag, registries);
        if (!(loaded instanceof WarehouseControllerBlockEntity copy)) {
            helper.fail("a saved warehouse controller must load again as one");
            return live;
        }
        return copy;
    }
}
