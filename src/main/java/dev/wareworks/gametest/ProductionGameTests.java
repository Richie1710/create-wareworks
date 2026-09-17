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
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of production patterns and production orders ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * The whole point of the feature is that <b>Wareworks does not craft</b>: it delivers ingredients to a production
 * station and waits for the product to come back through a normal warehouse input. The tests therefore <b>play the
 * machine</b> themselves ({@link #productionFullLoop}, {@link #productionFullLoopTwoIngredients}) — they take the
 * ingredients out of the station and put the product into the input, exactly as a player's sawmill plus funnel would —
 * and the item census expectation changes in precisely that step and nowhere else.
 * <p>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails, a chest of
 * {@value #LOGS_IN_STOCK} logs at 1 left, an empty chest at 2 left for the product, a chest of
 * {@value #NAILS_IN_STOCK} nails at 3 left, a production station at 0 right, a warehouse input at 1 right (where the
 * "machine" returns its product) and a warehouse output at 2 right (the destination of the order).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ProductionGameTests {
    /** Config tests get their own batch: the tests of one batch run at the same time ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksProductionConfig";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** The crane speed the other job tests use, so a whole loop fits into a test's tick budget. */
    private static final int TEST_RPM = 128;

    private static final RackPosition PRODUCTION_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PLANK_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition NAIL_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey NAIL = ItemKey.of(Items.IRON_NUGGET);

    /** One log makes four planks: the pattern from the feature request. */
    private static final int LOG_PER_RUN = 1;
    private static final int PLANKS_PER_RUN = 4;
    /** The two-ingredient pattern: one log plus two nails make four planks. */
    private static final int NAILS_PER_RUN = 2;
    private static final int LOGS_IN_STOCK = 16;
    private static final int NAILS_IN_STOCK = 16;
    /** Planks ordered in the loop tests: two runs of the pattern. */
    private static final int ORDERED_PLANKS = 8;
    private static final int RUNS = ORDERED_PLANKS / PLANKS_PER_RUN;
    /** Planks already in stock for the mixed order of {@link #productionRefundIsOnlyThePromise}. */
    private static final int PLANKS_IN_STOCK = 5;
    /**
     * An order the aisle serves partly out of stock and partly from a run that yields <b>more</b> than the missing
     * part: 5 in stock, 2 missing, and one run makes 4. Deliberately not a multiple of {@link #PLANKS_PER_RUN}.
     */
    private static final int MIXED_ORDER = 7;

    private static final int TIMEOUT_TICKS = 600;
    private static final int LONG_TIMEOUT_TICKS = 1800;
    /** A timeout short enough for a test, far above a crane trip so only a genuinely stuck order trips it. */
    private static final int SHORT_ORDER_TIMEOUT = 120;
    private static final int SETTLE_TICKS = 20;
    private static final int OTHER_CONTAINER_ID = 77;

    private ProductionGameTests() {
    }

    // --- the block -----------------------------------------------------------------------------------------------

    /**
     * A production station is an aligned member of its own kind: the controller records it as a production station,
     * not as an output, so it is never a request destination and never takes retrieve leftovers (ADR-024).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionRegistration(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionStations().size(), 1, "production stations");
                    helper.assertValueEqual(controller.outputStations().size(), 1, "a production station is no output");
                })
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertValueEqual(station.locationKind(), LocationKind.PRODUCTION, "location kind");
                    helper.assertValueEqual(aisle.controller().summary().withoutCrane().productionStations(), 0,
                            "the summary is only built for an observer");
                    aisle.controller().onGoggleObserved();
                    helper.assertValueEqual(aisle.controller().summary().productionStations(), 1,
                            "the controller counts it after an observation");
                })
                .thenSucceed();
    }

    /**
     * The automation surface is the warehouse output's: a funnel or chute can pull delivered ingredients out, nothing
     * can be pushed in. That is what lets the player's own machine empty the station.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionExtractOnly(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertTrue(station.insert(LOG.toStack(4), false).isEmpty(), "the crane can insert");
                    IItemHandler handler = aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK));
                    ItemStack refused = ItemHandlerHelper.insertItem(handler, LOG.toStack(1), false);
                    helper.assertValueEqual(refused.getCount(), 1, "automation cannot insert");
                    helper.assertValueEqual(extractAll(handler, LOG), 4, "automation can extract");
                })
                .thenSucceed();
    }

    // --- patterns ------------------------------------------------------------------------------------------------

    /**
     * Editing a pattern writes ghost entries and consumes nothing. The grid behaves like a crafting grid: the same
     * item may sit in several cells, and those cells <b>merge into one ingredient</b>, which is what makes several
     * cells of one item a single crane trip. The one rule a click can break is a result that is also one of its own
     * ingredients.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPatternEditing(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    station.patterns().clear();
                    helper.assertTrue(station.activePatterns().isEmpty(), "no pattern yet");

                    // A grid cell alone is not a pattern: without a result nothing can be produced from it.
                    helper.assertTrue(station.setPatternEntry(0, 0, LOG, LOG_PER_RUN), "first cell set");
                    helper.assertTrue(station.activePatterns().isEmpty(), "an incomplete pattern is not offered");

                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK,
                            PLANKS_PER_RUN), "result set");
                    List<ProductionPattern<ItemKey>> patterns = station.activePatterns();
                    helper.assertValueEqual(patterns.size(), 1, "one complete pattern");
                    helper.assertValueEqual(patterns.getFirst().result().key(), PLANK, "result");
                    helper.assertValueEqual(patterns.getFirst().result().count(), PLANKS_PER_RUN, "result count");
                    helper.assertValueEqual(patterns.getFirst().ingredients().size(), 1, "ingredients");

                    // The same item in a second cell is allowed and merges: two cells of one log are "2 logs".
                    helper.assertTrue(station.setPatternEntry(0, 1, LOG, LOG_PER_RUN), "a repeated item is allowed");
                    helper.assertValueEqual(station.activePatterns().getFirst().ingredients().size(), 1,
                            "still one ingredient");
                    helper.assertValueEqual(station.activePatterns().getFirst().ingredients().getFirst().count(), 2,
                            "with the merged count");

                    helper.assertFalse(station.setPatternEntry(0, 2, PLANK, 1),
                            "a cell may not hold the pattern's own result");
                    helper.assertFalse(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, LOG, 1),
                            "and the result may not be one of its own ingredients");

                    helper.assertTrue(station.setPatternEntry(0, 2, NAIL, NAILS_PER_RUN), "a second item is fine");
                    helper.assertValueEqual(station.activePatterns().getFirst().ingredients().size(), 2,
                            "two ingredients");
                    helper.assertValueEqual(station.patterns().filledCells(0), 3, "three filled cells");

                    helper.assertTrue(station.setPatternEntry(0, 1, null, 1), "a cell can be cleared");
                    helper.assertValueEqual(station.activePatterns().getFirst().ingredients().getFirst().count(), 1,
                            "back to one log");
                    helper.assertTrue(station.clearPattern(0), "a slot can be cleared");
                    helper.assertTrue(station.activePatterns().isEmpty(), "nothing left");
                })
                .thenSucceed();
    }

    /**
     * A <b>full nine-cell pattern</b> is saved with the station and comes back unchanged, counts, cell positions and
     * the merge included. Nine cells with one item repeated are eight ingredients, and that is what a reload has to
     * reproduce exactly.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPatternPersistence(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    fillNineCellPattern(helper, station, 1);
                    assertNineCellPattern(helper, station, 1);

                    CompoundTag saved = station.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, station, saved, WarehouseProductionBlockEntity.class));
                    helper.assertTrue(station.isRemoved(), "the station block entity was replaced");

                    WarehouseProductionBlockEntity reloaded = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertValueEqual(reloaded.activePatterns().size(), 2, "both patterns survived");
                    helper.assertValueEqual(reloaded.patterns().keyAt(0, 0), Optional.of(LOG), "the simple pattern");
                    helper.assertValueEqual(reloaded.patterns().countAt(0, ProductionPatterns.RESULT_ENTRY),
                            PLANKS_PER_RUN, "its result count");
                    assertNineCellPattern(helper, reloaded, 1);
                })
                .thenSucceed();
    }

    // --- ordering and supplying ----------------------------------------------------------------------------------

    /**
     * The terminal side of the feature: a pattern's result is producible even at zero stock, and ordering it creates a
     * production order whose ingredients the crane then delivers as a {@code SUPPLY} job. Item conservation is checked
     * on every tick of the trip.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionSupplyJob(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.producibleKeys().contains(PLANK), "planks are producible");
                    helper.assertValueEqual(controller.countOf(PLANK), 0L, "and none are in stock");
                    helper.assertValueEqual(controller.producibleAmount(PLANK), (long) LOGS_IN_STOCK * PLANKS_PER_RUN,
                            "every log could become planks");

                    RequestResult result = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            ORDERED_PLANKS);
                    helper.assertTrue(result.isAccepted(), "a producible item can be ordered");
                    helper.assertValueEqual(result.producing(), ORDERED_PLANKS, "all of it is being produced");
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1, "one production order");
                    // The order promises its ingredients at once, so nothing else can claim them.
                    helper.assertValueEqual(controller.availableStock(LOG),
                            (long) LOGS_IN_STOCK - RUNS * LOG_PER_RUN, "the logs it needs are promised");
                })
                .thenWaitUntil(() -> {
                    ItemCensus.assertEquals(helper, conserved, "while the ingredients travel");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) RUNS * LOG_PER_RUN,
                            "the logs arrived at the production station");
                })
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.state(), ProductionOrderState.DELIVERED, "order state");
                    helper.assertValueEqual(aisle.storedAt(LOG_RACK, LOG), (long) LOGS_IN_STOCK - RUNS * LOG_PER_RUN,
                            "the logs left storage");
                })
                .thenSucceed();
    }

    /** A supply job is its own type and goes to the production station, not to an output. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionSupplyJobType(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> {
                    Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
                    helper.assertTrue(job.isPresent(), "the crane has a job");
                    helper.assertValueEqual(job.get().type(), JobType.SUPPLY, "job type");
                    helper.assertValueEqual(job.get().targetKind(), LocationKind.PRODUCTION, "target kind");
                    helper.assertValueEqual(job.get().target(), PRODUCTION_RACK, "target");
                    helper.assertValueEqual(job.get().key(), LOG, "the ingredient is carried, not the result");
                })
                .thenSucceed();
    }

    /**
     * The whole loop, with the test playing the player's machine: order planks, let the crane bring the logs, take the
     * logs out of the station and put planks into the warehouse input, and watch the order complete and the original
     * request be served by an ordinary retrieval.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionFullLoop(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> {
                    ItemCensus.assertEquals(helper, conserved, "while the ingredients travel");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) RUNS * LOG_PER_RUN,
                            "ingredients delivered");
                })
                .thenExecute(() -> {
                    // The test is the machine: it takes the logs and hands back planks through a warehouse input.
                    // This is the only step in which the census expectation changes.
                    int taken = extractAll(aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK)), LOG);
                    helper.assertValueEqual(taken, RUNS * LOG_PER_RUN, "the machine took every log");
                    ItemCensus.change(conserved, LOG, -taken);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(ORDERED_PLANKS));
                    ItemCensus.change(conserved, PLANK, ORDERED_PLANKS);
                })
                .thenWaitUntil(() -> {
                    ItemCensus.assertEquals(helper, conserved, "while the product is stored and retrieved");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, PLANK), (long) ORDERED_PLANKS,
                            "the planks reached the output the request named");
                })
                // The crane is still retracting its arm in the tick the last plank lands, so the end state is waited
                // for rather than asserted at once.
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.state(), ProductionOrderState.COMPLETE, "the order completed");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "the request was served");
                    helper.assertTrue(controller.reservations().isEmpty(), "nothing stays reserved");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The same loop for a pattern with <b>two ingredients written across four grid cells</b>: the crane makes one trip
     * per item key (not one per cell), the machine gets both, and the product returns through the input.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionFullLoopTwoIngredients(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        int logs = RUNS * LOG_PER_RUN;
        int nails = RUNS * NAILS_PER_RUN;
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    setTwoIngredientPattern(helper, aisle);
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            ORDERED_PLANKS);
                    helper.assertTrue(result.isAccepted(), "the order is accepted");
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.lines().size(), 2, "one line per item key, not per grid cell");
                    helper.assertValueEqual(order.outstanding(LOG), logs, "logs owed");
                    helper.assertValueEqual(order.outstanding(NAIL), nails, "nails owed");
                })
                .thenWaitUntil(() -> {
                    ItemCensus.assertEquals(helper, conserved, "while the ingredients travel");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) logs, "logs delivered");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, NAIL), (long) nails, "nails delivered");
                    helper.assertValueEqual(onlyOrder(helper, aisle).state(), ProductionOrderState.DELIVERED,
                            "every line is served");
                })
                .thenExecute(() -> {
                    IItemHandler station = aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK));
                    ItemCensus.change(conserved, LOG, -extractAll(station, LOG));
                    ItemCensus.change(conserved, NAIL, -extractAll(station, NAIL));
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(ORDERED_PLANKS));
                    ItemCensus.change(conserved, PLANK, ORDERED_PLANKS);
                })
                .thenWaitUntil(() -> {
                    ItemCensus.assertEquals(helper, conserved, "while the product is stored and retrieved");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, PLANK), (long) ORDERED_PLANKS,
                            "the planks reached the output");
                    helper.assertValueEqual(onlyOrder(helper, aisle).state(), ProductionOrderState.COMPLETE,
                            "the order completed");
                })
                .thenSucceed();
    }

    /**
     * Stage 1 is single level: an ingredient that is not in stock is refused with a plain "not in stock" and no order
     * is created. Producing the ingredient first is stage 2 and must not happen here.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionMissingIngredient(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOG_RACK); // an empty chest: the pattern's ingredient is missing
        aisle.storage(PLANK_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        helper.startSequence()
                .thenExecute(() -> setPattern(helper, aisle))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().productionStations().size(), 1,
                        "the station is recorded"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.producibleKeys().contains(PLANK),
                            "the pattern is known even without ingredients");
                    helper.assertValueEqual(controller.producibleAmount(PLANK), 0L, "but nothing can be made");
                    RequestResult result = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            ORDERED_PLANKS);
                    helper.assertFalse(result.isAccepted(), "the order is refused");
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK), "reason");
                    helper.assertValueEqual(controller.productionOrders().size(), 0, "no order was created");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and no request is left waiting");
                })
                .thenSucceed();
    }

    // --- giving up -----------------------------------------------------------------------------------------------

    /**
     * Cancelling releases what is still promised, gives the waiting request its unproducible part back, and leaves the
     * ingredients the crane already delivered exactly where they are.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionOrderCancelled(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) RUNS * LOG_PER_RUN, "ingredients delivered"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    UUID id = onlyOrder(helper, aisle).id();
                    helper.assertTrue(controller.cancelProductionOrder(id).isPresent(), "the order is cancelled");
                    ProductionOrder<ItemKey, RackPosition> cancelled = controller.productionOrders().getFirst();
                    helper.assertValueEqual(cancelled.state(), ProductionOrderState.CANCELLED, "state");
                    helper.assertValueEqual(cancelled.deliveredIngredients(), RUNS * LOG_PER_RUN,
                            "it says how much it handed over");
                    helper.assertValueEqual(controller.openRequestCount(), 0,
                            "the request no longer waits for items nobody will make");
                    helper.assertValueEqual(controller.availableStock(LOG),
                            (long) LOGS_IN_STOCK - RUNS * LOG_PER_RUN, "the remaining logs are free again");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    // The documented boundary: what the crane already delivered stays where it is. Nothing is
                    // invented and nothing is taken back out of a machine.
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) RUNS * LOG_PER_RUN,
                            "delivered ingredients are not recovered");
                    ItemCensus.assertEquals(helper, conserved, "after cancelling");
                })
                .thenSucceed();
    }

    /**
     * The player-facing cancel path, and what a crafted payload cannot do: a cancellation is resolved against the menu
     * the sending player really has open and is refused for an order that does not run at <b>this</b> station. The
     * pattern payload takes the same route, including its per-tick budget.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionScreenEditsAndCancels(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    Player player = playerAt(helper, aisle.rackPos(PRODUCTION_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    UUID order = onlyOrder(helper, aisle).id();

                    // Nothing without the right menu: these are dropped before they cost any budget.
                    player.containerMenu = player.inventoryMenu;
                    helper.assertTrue(ProductionMenu.submitCancel(player, menu.containerId, order).isEmpty(),
                            "a player without a production menu cancels nothing");
                    player.containerMenu = menu;
                    helper.assertTrue(ProductionMenu.submitCancel(player, OTHER_CONTAINER_ID, order).isEmpty(),
                            "a payload for another menu is ignored");
                    helper.assertTrue(ProductionMenu.submitPattern(null, menu.containerId, 0, 0,
                            Optional.of(NAIL), 1).isEmpty(), "no player, no edit");

                    // An order of another station (here: an id nobody has) is answered with "no".
                    helper.assertValueEqual(ProductionMenu.submitCancel(player, menu.containerId, UUID.randomUUID()),
                            Optional.of(false), "an unknown order is refused");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1, "the order is intact");

                    // The real one goes through, and the ingredients already delivered stay where they are.
                    helper.assertValueEqual(ProductionMenu.submitCancel(player, menu.containerId, order),
                            Optional.of(true), "the station's own order is cancelled");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "nothing open");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "the pattern is untouched");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    Player player = playerAt(helper, aisle.rackPos(PRODUCTION_RACK));
                    ProductionMenu menu = openMenu(player, station);

                    // Editing through the payload writes ghost entries and consumes nothing.
                    helper.assertValueEqual(ProductionMenu.submitPattern(player, menu.containerId, 1, 0,
                            Optional.of(NAIL), 3), Optional.of(true), "an ingredient is written");
                    helper.assertValueEqual(ProductionMenu.submitPattern(player, menu.containerId, 1,
                            ProductionPatterns.RESULT_ENTRY, Optional.of(LOG), 1), Optional.of(true), "and a result");
                    helper.assertValueEqual(station.activePatterns().size(), 2, "the second pattern is complete");
                    helper.assertValueEqual(ProductionMenu.submitPattern(player, menu.containerId, 1,
                            ProductionMenu.CLEAR_WHOLE_PATTERN, Optional.empty(), 1), Optional.of(true),
                            "one payload clears the whole slot");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "back to one pattern");
                    helper.assertValueEqual(countIn(player, Items.IRON_NUGGET), 0, "editing consumed nothing");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    // A flood in one tick is answered up to the budget and then dropped.
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    Player player = playerAt(helper, aisle.rackPos(PRODUCTION_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    for (int i = 0; i < ProductionMenu.MAX_EDITS_PER_TICK; i++)
                        helper.assertTrue(ProductionMenu.submitPattern(player, menu.containerId, 2, 0,
                                Optional.of(NAIL), i + 1).isPresent(), "edit " + i + " is inside the budget");
                    helper.assertTrue(ProductionMenu.submitPattern(player, menu.containerId, 2, 0,
                            Optional.of(NAIL), 1).isEmpty(), "the edit after the budget is dropped");
                })
                .thenSucceed();
    }

    /**
     * An order that makes no progress gives up: it releases what it still promised, hands the waiting request its
     * unproduced part back and leaves the delivered ingredients where they are.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionOrderTimeout(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) RUNS * LOG_PER_RUN, "ingredients delivered"))
                // Nobody plays the machine here: the ingredients sit in the station and no product ever arrives.
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, aisle).state(),
                        ProductionOrderState.TIMED_OUT, "the order gave up"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openRequestCount(), 0, "the request was given its part back");
                    helper.assertValueEqual(controller.countOf(PLANK), 0L, "no plank was ever invented");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), (long) RUNS * LOG_PER_RUN,
                            "ingredients already handed over are not recovered");
                    ItemCensus.assertEquals(helper, conserved, "after the timeout");
                })
                .thenSucceed();
    }

    /**
     * A cancelled order gives its request back only what <b>production</b> promised it, never the whole run. With 5
     * planks in stock and 7 ordered, one run of 4 is started for the 2 that are missing; refunding the run would
     * strip the request of 2 planks the aisle really holds — and with a smaller request it would delete the request
     * outright ({@code warehouse-system.md} §3.5.3).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionRefundIsOnlyThePromise(GameTestHelper helper) {
        AisleFixture aisle = parkedAisle(helper, PLANKS_IN_STOCK);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertStocked(helper, aisle);
                    helper.assertValueEqual(aisle.controller().countOf(PLANK), (long) PLANKS_IN_STOCK,
                            "planks in stock");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestResult result = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK, MIXED_ORDER);
                    helper.assertTrue(result.isAccepted(), "stock plus production is accepted");
                    helper.assertValueEqual(result.granted(), MIXED_ORDER, "all of it");
                    helper.assertValueEqual(result.producing(), MIXED_ORDER - PLANKS_IN_STOCK,
                            "only the part that is missing is produced");

                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.resultAmount(), PLANKS_PER_RUN, "the run makes a whole batch");
                    helper.assertValueEqual(order.promisedToRequest(), (long) MIXED_ORDER - PLANKS_IN_STOCK,
                            "but it promises the request only what that request asked production for");

                    helper.assertTrue(controller.cancelProductionOrder(order.id()).isPresent(), "cancelled");
                    helper.assertValueEqual(controller.openRequestCount(), 1,
                            "the request stays open for the part the aisle really holds");
                    helper.assertValueEqual(controller.openRequests().getFirst().remaining(), PLANKS_IN_STOCK,
                            "and waits for exactly that part, not for two planks less");
                })
                .thenSucceed();
    }

    /**
     * Cancelling while the crane is already carrying the ingredients stops that trip: the items go back into storage
     * instead of being dropped into a machine for an order that no longer exists (§3.5.4).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionCancelStopsTheCraneMidTrip(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        Map<ItemKey, Long> conserved = stocked();
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> {
                    Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
                    helper.assertTrue(job.isPresent() && job.get().picked() && job.get().heldAmount() > 0,
                            "the crane has the ingredients in its grabber");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), 0L,
                            "and has not delivered them yet");
                })
                .thenExecute(() -> helper.assertTrue(
                        aisle.controller().cancelProductionOrder(onlyOrder(helper, aisle).id()).isPresent(),
                        "the order is cancelled mid trip"))
                .thenWaitUntil(() -> {
                    aisle.assertIdleAndEmpty();
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG), 0L,
                            "the machine never got the ingredients of a cancelled order");
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK,
                            "they went back into storage");
                    helper.assertValueEqual(onlyOrder(helper, aisle).deliveredIngredients(), 0,
                            "so the order reports nothing handed over");
                    ItemCensus.assertEquals(helper, conserved, "after cancelling mid trip");
                })
                .thenSucceed();
    }

    /**
     * Breaking a production station ends its orders at once. The station has to resolve its controller directly:
     * Create sets the removed flag <b>before</b> {@code remove()} runs, so the usual lookup refuses a removed block
     * entity and this cleanup silently did nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStationBrokenCancelsItsOrders(GameTestHelper helper) {
        AisleFixture aisle = parkedAisle(helper, 0);
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    order(helper, aisle);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1, "one open order");

                    aisle.breakBlock(aisle.rackPos(PRODUCTION_RACK));
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                            "the order ended with its station, in the very same tick");
                    helper.assertValueEqual(controller.productionOrders().getFirst().state(),
                            ProductionOrderState.CANCELLED, "state");
                    helper.assertValueEqual(controller.openRequestCount(), 0,
                            "and the request stopped waiting for items nobody will make");
                    helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK,
                            "the promised ingredients are free again");
                })
                .thenSucceed();
    }

    /**
     * Lowering {@code maxProductionPatterns} <b>hides</b> the patterns of the dropped slots; it never deletes them.
     * The station's slot list grows to fit its save, so raising the value again brings them back — and, crucially,
     * the next save does not write the truncated set over them.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionPatternsSurviveALoweredCap(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    int slots = station.patterns().size();
                    helper.assertTrue(slots > 1, "the default config offers several pattern slots");
                    helper.assertTrue(station.setPatternEntry(slots - 1, 0, NAIL, 1),
                            "an ingredient in the last slot");
                    helper.assertTrue(station.setPatternEntry(slots - 1, ProductionPatterns.RESULT_ENTRY, LOG, 1),
                            "and its result");
                    helper.assertValueEqual(station.activePatterns().size(), 2, "two patterns");
                    CompoundTag saved = station.saveWithFullMetadata(registries);

                    // An admin lowers the cap: a station built now has one slot, and loading the save must keep the
                    // patterns the higher slots hold.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxProductionPatterns, 1);
                    level.setBlockEntity(loadCopy(helper, station, saved, WarehouseProductionBlockEntity.class));
                    WarehouseProductionBlockEntity reloaded = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertValueEqual(reloaded.activePatterns().size(), 2,
                            "a lowered cap hides patterns, it never deletes them");
                    helper.assertValueEqual(reloaded.patterns().keyAt(slots - 1, ProductionPatterns.RESULT_ENTRY),
                            Optional.of(LOG), "the pattern of a dropped slot is still there");

                    // The next save is where the old code lost it for good.
                    CompoundTag again = reloaded.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, reloaded, again, WarehouseProductionBlockEntity.class));
                    helper.assertValueEqual(aisle.productionAt(PRODUCTION_RACK).activePatterns().size(), 2,
                            "and saving under the lowered cap keeps it");
                })
                .thenSucceed();
    }

    /** Restores the config even when a config-batch test fails before its own restore. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreProductionConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * An open order survives a reload: the controller and the station are replaced by copies loaded from their saves,
     * and the order then still completes when the product arrives.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void productionOrderPersistence(GameTestHelper helper) {
        AisleFixture aisle = build(helper);
        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> order(helper, aisle))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) RUNS * LOG_PER_RUN, "ingredients delivered"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    CompoundTag stationTag = station.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, station, stationTag,
                            WarehouseProductionBlockEntity.class));
                    helper.assertTrue(controller.isRemoved() && station.isRemoved(), "both block entities replaced");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertTrue(order.isOpen(), "the order survived the reload");
                    helper.assertValueEqual(order.result(), PLANK, "result");
                    helper.assertValueEqual(order.resultAmount(), ORDERED_PLANKS, "result amount");
                    helper.assertValueEqual(aisle.productionAt(PRODUCTION_RACK).activePatterns().size(), 1,
                            "the pattern survived too");
                    helper.assertValueEqual(controller.openRequestCount(), 1, "and so did the request");
                })
                .thenExecute(() -> {
                    extractAll(aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK)), LOG);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(ORDERED_PLANKS));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(onlyOrder(helper, aisle).state(), ProductionOrderState.COMPLETE,
                            "the reloaded order completed");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, PLANK), (long) ORDERED_PLANKS,
                            "and the request was served");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** The aisle with stocked log and nail chests, an empty plank chest, stations and a patterned production block. */
    private static AisleFixture build(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(PLANK_RACK);
        aisle.storage(NAIL_RACK, NAIL.toStack(NAILS_IN_STOCK));
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        setPattern(helper, aisle);
        return aisle;
    }

    /**
     * The aisle of {@link #build} <b>without a motor</b>, and with {@code planksInStock} planks already stored. The
     * crane never moves, so a test sees an order exactly as it was created instead of racing a supply job.
     */
    private static AisleFixture parkedAisle(GameTestHelper helper, int planksInStock) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
        if (planksInStock > 0)
            aisle.storage(PLANK_RACK, PLANK.toStack(planksInStock));
        else
            aisle.storage(PLANK_RACK);
        aisle.storage(NAIL_RACK, NAIL.toStack(NAILS_IN_STOCK));
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        setPattern(helper, aisle);
        return aisle;
    }

    /** The item census of a freshly built aisle. */
    private static Map<ItemKey, Long> stocked() {
        return ItemCensus.of(LOG, LOGS_IN_STOCK, NAIL, NAILS_IN_STOCK);
    }

    /** One log to four planks, in the station's first pattern slot. */
    private static void setPattern(GameTestHelper helper, AisleFixture aisle) {
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, LOG_PER_RUN), "ingredient set");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN),
                "result set");
    }

    /**
     * One log plus two nails to four planks, written across <b>four</b> grid cells (the nails in two cells of one),
     * which the station merges into two ingredients.
     */
    private static void setTwoIngredientPattern(GameTestHelper helper, AisleFixture aisle) {
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.clearPattern(0), "the simple pattern is replaced");
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, LOG_PER_RUN), "the log");
        helper.assertTrue(station.setPatternEntry(0, 1, NAIL, 1), "a nail");
        helper.assertTrue(station.setPatternEntry(0, 2, NAIL, 1), "and another nail in its own cell");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN),
                "the result");
        List<ProductionPattern<ItemKey>> patterns = station.activePatterns();
        helper.assertValueEqual(patterns.size(), 1, "one pattern");
        helper.assertValueEqual(patterns.getFirst().ingredients().size(), 2, "three cells became two ingredients");
        helper.assertValueEqual(patterns.getFirst().ingredients().get(1).count(), NAILS_PER_RUN, "the merged nails");
    }

    /** Fills all nine grid cells of {@code slot}: eight distinct items, one of them in two cells. */
    private static void fillNineCellPattern(GameTestHelper helper, WarehouseProductionBlockEntity station, int slot) {
        ItemKey[] cells = {LOG, NAIL, ItemKey.of(Items.STICK), ItemKey.of(Items.STONE), LOG,
                ItemKey.of(Items.COBBLESTONE), ItemKey.of(Items.DIRT), ItemKey.of(Items.SAND),
                ItemKey.of(Items.GRAVEL)};
        helper.assertValueEqual(cells.length, ProductionPatterns.GRID_CELLS, "the grid is filled completely");
        for (int cell = 0; cell < cells.length; cell++)
            helper.assertTrue(station.setPatternEntry(slot, cell, cells[cell], cell + 1), "cell " + cell);
        helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN),
                "the result of the nine-cell pattern");
    }

    /** The nine-cell pattern as it must read after every save and load. */
    private static void assertNineCellPattern(GameTestHelper helper, WarehouseProductionBlockEntity station, int slot) {
        helper.assertValueEqual(station.patterns().filledCells(slot), ProductionPatterns.GRID_CELLS,
                "nine filled cells");
        helper.assertValueEqual(station.patterns().keyAt(slot, 0), Optional.of(LOG), "the first cell");
        helper.assertValueEqual(station.patterns().countAt(slot, 4), 5, "the count of the fifth cell");
        ProductionPattern<ItemKey> pattern = station.patterns().patternAt(slot).orElse(null);
        if (pattern == null) {
            helper.fail("the nine-cell pattern must be complete");
            return;
        }
        helper.assertValueEqual(pattern.ingredients().size(), 8, "nine cells with one repeat are eight ingredients");
        helper.assertValueEqual(pattern.ingredients().getFirst(), new ProductionEntry<>(LOG, 1 + 5),
                "the repeated item merged");
        helper.assertValueEqual(pattern.result().key(), PLANK, "the result");
    }

    /** Waits until the controller has read both chests, so a request can be planned against real stock. */
    private static void assertStocked(GameTestHelper helper, AisleFixture aisle) {
        helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK, "logs in stock");
        helper.assertValueEqual(aisle.controller().countOf(NAIL), (long) NAILS_IN_STOCK, "nails in stock");
        helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station is recorded");
    }

    /** Orders {@value #ORDERED_PLANKS} planks at the output, which starts a production order for all of them. */
    private static void order(GameTestHelper helper, AisleFixture aisle) {
        // A request names its destination in world coordinates; the fixture's rackPos is test-relative.
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK, ORDERED_PLANKS);
        helper.assertTrue(result.isAccepted(), "the order is accepted");
        helper.assertValueEqual(result.producing(), ORDERED_PLANKS, "all of it is produced");
    }

    /** The aisle's single production order; fails the test when there is not exactly one. */
    private static ProductionOrder<ItemKey, RackPosition> onlyOrder(GameTestHelper helper, AisleFixture aisle) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
        if (orders.size() != 1) {
            helper.fail("expected exactly one production order, found " + orders.size());
            throw new IllegalStateException("unreachable");
        }
        return orders.getFirst();
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

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static ProductionMenu openMenu(Player player, WarehouseProductionBlockEntity station) {
        ProductionMenu menu = ProductionMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                station);
        player.containerMenu = menu;
        return menu;
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static int countIn(Player player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    /** A fresh block entity from a save, the way a reload builds one ({@code CraneJobGameTests#loadCopy}). */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
