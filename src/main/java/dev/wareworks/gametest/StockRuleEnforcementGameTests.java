package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.StockCount;
import net.minecraft.core.BlockPos;
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
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * GameTests of what stock rules actually <b>do</b> to a running warehouse ({@code docs/warehouse-system.md} §3.6, M15,
 * issue #3) — the cases in which a rule changes while items are already moving, and what the player is shown about it.
 * <p>
 * {@code StockKeeperGameTests} proves the block, its persistence and the two enforcement rules in their settled state;
 * these tests attack the moments in between, because every one of them is irreversible if it goes wrong: nothing that
 * was stored is ever moved back out, and nothing that was handed out can be recalled.
 * <ul>
 * <li>{@code stockrulemaximumreachedinflight} — the maximum closes while the crane carries the items: the trip still
 * finishes, nothing is dropped or held for ever, and only the <b>next</b> plan is refused;</li>
 * <li>{@code stockrulechangedmidjob} — a rule added while a job runs and removed again: the running job is never
 * re-planned, what is already stored is never moved, and storing resumes the moment the rule goes;</li>
 * <li>{@code stockrulekeeperbrokenwhileinforce} — a keeper broken while its maximum and its reserve bite: both lift,
 * and the items it was holding back move;</li>
 * <li>{@code stockrulereserveraisedkeepspromises} — a reserve raised above the stock refuses new requests and never
 * claws back an accepted one;</li>
 * <li>{@code stockrulesurvivesasaveandreload} — keeper and controller replaced by copies loaded from their saves, with
 * both rules in force at that moment;</li>
 * <li>{@code stockruleterminalreportsrules} — the terminal row: the badge, the reserve as a part of what a player may
 * claim, and a ruled item that keeps its row at zero stock;</li>
 * <li>{@code stockruleterminalkeepsruledrows} — that row survives the {@code maxTerminalStockEntries} cut (own config
 * batch), which is the M11 producible-offer defect re-pinned for rules;</li>
 * <li>{@code stockrulecontrollerreportsrules} — the controller's goggle counts and their bounded sync;</li>
 * <li>{@code stockrulereserveboundsproduction} — a redstone request for something the aisle could make out of a reserved
 * ingredient is refused instead of spending the reserve through a pattern, while a player may still order it;</li>
 * <li>{@code stockruleaislelostkeepsrules} — the aisle is lost and comes back: the controller's copy of the rules is
 * never forgotten, and the keepers stop signalling while there is no warehouse.</li>
 * </ul>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}, storage on the left rack plane, stations and
 * keepers on the right.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StockRuleEnforcementGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** Long enough for several dispatch intervals and more than one stock rule tick. */
    private static final int SETTLE_TICKS = 80;
    private static final String CONFIG_BATCH = "wareworksStockRuleConfig";
    /** A stock window far below the item types the aisle holds, so the cut really cuts. */
    private static final int SMALL_WINDOW = 16;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STORAGE_C = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition FILLER = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);

    /** The pattern of {@link #stockRuleReserveBoundsProduction}: one log makes this many planks. */
    private static final int PLANKS_PER_LOG = 4;
    private static final int LOGS_IN_STOCK = 8;
    private static final int ORDERED_PLANKS = 4;

    private static final int FED_IRON = 24;
    private static final int MORE_IRON = 8;
    private static final int IRON_MAXIMUM = 8;
    private static final int IRON_MINIMUM = 64;
    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int DIAMOND_RESERVE = 10;
    /** What automation may still be promised of {@link #DIAMONDS_IN_STOCK} above {@link #DIAMOND_RESERVE}. */
    private static final int DIAMONDS_FOR_AUTOMATION = DIAMONDS_IN_STOCK - DIAMOND_RESERVE;
    private static final int EARLY_REQUEST = 12;

    private StockRuleEnforcementGameTests() {
    }

    // --- rules that change while items are moving --------------------------------------------------------------

    /**
     * The maximum closes <b>while the crane is carrying the items</b>. A job that is already running is never
     * re-planned — its items are committed, and a crane that could not put them down would hold them for ever — so the
     * trip finishes and the stock ends up above the new maximum on purpose. Only the next plan is refused, and it is
     * refused with {@code AT_MAXIMUM}.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleMaximumReachedInFlight(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A);
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a maximum closes mid trip"));

        // What was in the racks and in the crane's head when the maximum was closed: their sum is what the warehouse
        // must end up with, whether the planner had made one trip of the buffer or several.
        long[] committed = { 0L };

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    // A maximum that leaves room for everything, so a whole trip is planned and started.
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, FED_IRON * 2L);
                    feed(aisle, conserved, IRON.toStack(FED_IRON));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0,
                        "the crane has the iron in its head"))
                .thenExecute(() -> {
                    // The player closes the maximum completely while those items are in the air.
                    committed[0] = aisle.controller().countOf(IRON) + aisle.dock().heldItems().count(IRON);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 0L);
                    helper.assertValueEqual(aisle.controller().storeHeadroom(IRON), 0L, "nothing more may be stored");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), committed[0],
                            "the trip that was already running put its items away, and nothing followed it");
                    helper.assertValueEqual(aisle.controller().countOf(IRON) + aisle.stationCount(INPUT_RACK, IRON),
                            (long) FED_IRON, "and every ingot is either stored or still in the input");
                    // Nothing that is stored is ever moved back out, however far above the maximum it now is.
                    feed(aisle, conserved, IRON.toStack(MORE_IRON));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), committed[0],
                            "and the closed maximum refuses every plan after it");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON),
                            FED_IRON + MORE_IRON - committed[0], "which leaves the new items in the input on purpose");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.AT_MAXIMUM),
                            "reported as a maximum, never as a full warehouse");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A rule added while a job runs and removed again. The running job is never re-planned, what is already stored is
     * never moved (ADR-021's promise for filters, kept word for word for rules), and storing resumes in the first
     * planning run after the rule goes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleChangedMidJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A);
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a rule is added and removed"));

        long[] committed = { 0L };

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    feed(aisle, conserved, IRON.toStack(FED_IRON));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) > 0,
                        "the crane has the iron in its head"))
                .thenExecute(() -> {
                    // A rule appears under a job that is already running.
                    committed[0] = aisle.controller().countOf(IRON) + aisle.dock().heldItems().count(IRON);
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    helper.assertValueEqual(aisle.controller().stockRules().size(), 1, "the copy has it at once");
                    helper.assertTrue(committed[0] > IRON_MAXIMUM,
                            "the items already in the air have to be more than the new maximum: " + committed[0]);
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), committed[0],
                            "the job in flight was not re-planned, and nothing followed it");
                    feed(aisle, conserved, IRON.toStack(MORE_IRON));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), committed[0],
                            "nothing already stored is moved out to obey a new maximum");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON),
                            FED_IRON + MORE_IRON - committed[0], "and nothing more comes in");
                    // The player clears the row: the item goes first, which clears the whole rule.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, null, 0L);
                    helper.assertTrue(aisle.controller().stockRules().isEmpty(), "the copy lost it at once");
                    helper.assertValueEqual(aisle.controller().storeHeadroom(IRON), Long.MAX_VALUE, "no cap left");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) (FED_IRON + MORE_IRON),
                            "storing resumes as soon as the rule is gone");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), 0L, "the input is empty again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The keeper is broken while both of its rules bite: a maximum that leaves items in an input and a reserve that
     * refuses automation. Breaking it takes the rules out of the controller's copy, so both lift and the items it was
     * holding back move.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleKeeperBrokenWhileInForce(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a keeper is broken"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_RESERVE, null, DIAMONDS_IN_STOCK);
                    feed(aisle, conserved, IRON.toStack(FED_IRON));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_MAXIMUM, "the maximum bit");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) (FED_IRON - IRON_MAXIMUM),
                            "and left the rest in the input");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND), 0L,
                            "the reserve holds every diamond back");
                    helper.assertValueEqual(request(aisle, StockAccess.AUTOMATION, DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.RESERVED), "so automation is refused with its own reason");
                })
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(KEEPER_RACK)))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.stockRules().isEmpty(), "a broken keeper takes its rules with it");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), Long.MAX_VALUE, "the maximum lifted");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND),
                            (long) DIAMONDS_IN_STOCK, "and so did the reserve");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) FED_IRON,
                            "the items the maximum held back are stored");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), 0L, "the input is empty");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A reserve raised <b>above the current stock</b> after a request was accepted. It refuses new and merged
     * requests and never claws back what was promised: the open request keeps its amount and the crane delivers it.
     * A promise that could be revoked would leave a station waiting for items that never come.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleReserveRaisedKeepsPromises(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reserve is raised"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "stock");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestResult accepted = request(aisle, StockAccess.AUTOMATION, DIAMOND, EARLY_REQUEST);
                    helper.assertTrue(accepted.isAccepted(), "accepted before any rule existed: " + accepted);

                    // Now a rule reserves more than is left, i.e. more than the warehouse could promise today.
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, DIAMONDS_IN_STOCK);

                    helper.assertValueEqual(openAmount(controller, aisle.absoluteRackPos(OUTPUT_RACK)), EARLY_REQUEST,
                            "the accepted request keeps every item it was promised");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND), 0L,
                            "nothing is left for automation");
                    helper.assertValueEqual(request(aisle, StockAccess.AUTOMATION, DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.RESERVED),
                            "and a merge into the open request is refused with the reserve's own reason");
                    helper.assertValueEqual(controller.availableTo(StockAccess.PLAYER, DIAMOND),
                            (long) (DIAMONDS_IN_STOCK - EARLY_REQUEST), "a player may still take the rest");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, DIAMOND), (long) EARLY_REQUEST,
                            "the promise was kept and delivered");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and the request is done");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Keeper <b>and</b> controller replaced by copies loaded from their saves while a maximum and a reserve are
     * biting. Both are enforced again before a single tick runs, because the controller's copy is saved with it; the
     * keeper still holds the rules a player wrote, so its screen and the copy agree.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleSurvivesASaveAndReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "across a save and reload"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_RESERVE, null, DIAMOND_RESERVE);
                    feed(aisle, conserved, IRON.toStack(FED_IRON));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) IRON_MAXIMUM, "the maximum bit");
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    CompoundTag savedController = controller.saveWithFullMetadata(level.registryAccess());
                    CompoundTag savedKeeper = keeper.saveWithFullMetadata(level.registryAccess());

                    WarehouseStockKeeperBlockEntity reloadedKeeper = loadCopy(helper, keeper, savedKeeper,
                            WarehouseStockKeeperBlockEntity.class);
                    helper.assertValueEqual(reloadedKeeper.rules().rules().size(), 2, "the keeper kept both rules");
                    WarehouseControllerBlockEntity reloadedController = loadCopy(helper, controller, savedController,
                            WarehouseControllerBlockEntity.class);
                    // Before its first tick and before any keeper was read: both numbers are simply there.
                    helper.assertValueEqual(reloadedController.stockRules().ruleFor(IRON).map(StockRule::maximum),
                            Optional.of((long) IRON_MAXIMUM), "the reloaded controller knows the maximum");
                    helper.assertValueEqual(reloadedController.stockRules().reserved(DIAMOND), (long) DIAMOND_RESERVE,
                            "and the reserve");
                    level.setBlockEntity(reloadedKeeper);
                    level.setBlockEntity(reloadedController);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_MAXIMUM,
                            "nothing was stored past the maximum after the reload");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) (FED_IRON - IRON_MAXIMUM),
                            "the rest still waits in the input");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND),
                            (long) DIAMONDS_FOR_AUTOMATION, "the reserve is enforced again");
                    RequestResult automated = request(aisle, StockAccess.AUTOMATION, DIAMOND, DIAMONDS_IN_STOCK);
                    helper.assertValueEqual(automated.request().map(RetrievalRequest::requested),
                            Optional.of(DIAMONDS_FOR_AUTOMATION), "and still clamps a request to it");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).rules().ruleCount(), 2,
                            "the keeper at the position holds the rules a player would see");
                })
                .thenSucceed();
    }

    // --- what the player is shown --------------------------------------------------------------------------------

    /**
     * The terminal row. A reserve is a <b>part of</b> what the player standing here may claim, never a deduction from
     * it — that is the user's decision for M15 — so the row names it instead of hiding the items, and an item a rule
     * governs keeps its row at zero stock, or a warehouse calling for something it has run out of could not say so and
     * the item could never be requested again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleTerminalReportsRules(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "stock");
                })
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, DIAMOND_RESERVE);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);

                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalStockEntry diamonds = entry(helper, terminal, DIAMOND);
                    helper.assertValueEqual(diamonds.total(), (long) DIAMONDS_IN_STOCK, "in stock");
                    helper.assertValueEqual(diamonds.available(), (long) DIAMONDS_IN_STOCK,
                            "all of it is available to the player at the terminal");
                    helper.assertValueEqual(diamonds.ruleReserved(), (long) DIAMOND_RESERVE,
                            "with the reserve named as a part of it");
                    helper.assertValueEqual(diamonds.availableToAutomation(), (long) DIAMONDS_FOR_AUTOMATION,
                            "which is where automation stops");
                    helper.assertValueEqual(diamonds.rule(), Optional.of(StockRuleStatus.SATISFIED), "badge");

                    // An item the warehouse is calling for and holds none of: the row exists because of the rule.
                    TerminalStockEntry iron = entry(helper, terminal, IRON);
                    helper.assertValueEqual(iron.total(), 0L, "nothing of it is in stock");
                    helper.assertValueEqual(iron.rule(), Optional.of(StockRuleStatus.BELOW_MINIMUM), "badge");
                    helper.assertTrue(terminal.holdsInStock(IRON), "so the row is never reported as gone");
                    helper.assertFalse(count(helper, terminal, IRON).isGone(), "and the client keeps it");

                    // Every field has to survive the conversion into what the screen gets (the M11 lesson).
                    StockCount<ItemKey> converted = count(helper, terminal, DIAMOND);
                    helper.assertValueEqual(converted.rule(), Optional.of(StockRuleStatus.SATISFIED), "menu badge");
                    helper.assertValueEqual(converted.ruleReserved(), (long) DIAMOND_RESERVE, "menu reserve");
                    helper.assertValueEqual(converted.fromReserve(DIAMONDS_IN_STOCK), (long) DIAMOND_RESERVE,
                            "the row can say how far a click goes below the reserve");
                })
                .thenExecute(() -> {
                    // Automation is clamped to the reserve and then refused with its own reason ...
                    RequestResult automated = requestAt(aisle, TERMINAL_RACK, StockAccess.AUTOMATION, DIAMOND,
                            DIAMONDS_IN_STOCK);
                    helper.assertValueEqual(automated.request().map(RetrievalRequest::requested),
                            Optional.of(DIAMONDS_FOR_AUTOMATION), "automation stops at the reserve");
                    helper.assertValueEqual(requestAt(aisle, TERMINAL_RACK, StockAccess.AUTOMATION, DIAMOND, 1)
                            .rejection(), Optional.of(RequestRejection.RESERVED), "never 'not in stock'");

                    // ... and the badge turns, because now everything that is left is the reserve.
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalStockEntry reserved = entry(helper, terminal, DIAMOND);
                    helper.assertValueEqual(reserved.available(), (long) DIAMOND_RESERVE, "only the reserve is left");
                    helper.assertValueEqual(reserved.ruleReserved(), (long) DIAMOND_RESERVE, "and all of it is it");
                    helper.assertValueEqual(reserved.availableToAutomation(), 0L, "automation gets nothing more");
                    helper.assertValueEqual(reserved.rule(), Optional.of(StockRuleStatus.AT_RESERVE), "badge");

                    // The player, at the same terminal, is served from it and is the one the row warns.
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    RequestResult byPlayer = terminal.requestFromTerminal(player, DIAMOND, DIAMOND_RESERVE);
                    helper.assertTrue(byPlayer.isAccepted(), "a player takes the reserve: " + byPlayer);
                    helper.assertValueEqual(byPlayer.granted(), DIAMOND_RESERVE, "all of it");
                })
                .thenSucceed();
    }

    /**
     * A ruled row survives the {@code maxTerminalStockEntries} cut. It has no stock of its own, so the snapshot's
     * order sorts it behind everything that has, and a plain cut would drop exactly the rows that explain a reserve or
     * a warehouse calling for something — the M11 producible-offer defect, re-pinned for rules.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleTerminalKeepsRuledRows(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalStockEntries, SMALL_WINDOW);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(STORAGE_C, fillerStacks());
        aisle.storage(FILLER, moreFillerStacks());
        aisle.terminal(TERMINAL_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(3, 0, 1);
                    helper.assertTrue(aisle.controller().stockIndex().distinctKeys() > SMALL_WINDOW,
                            "the aisle has to hold more item types than the window shows");
                })
                .thenExecute(() -> {
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);

                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.stockSnapshot().size(), SMALL_WINDOW,
                            "the window still bounds what the screen is told");
                    TerminalStockEntry iron = entry(helper, terminal, IRON);
                    helper.assertValueEqual(iron.total(), 0L, "the ruled row survived the cut without any stock");
                    helper.assertValueEqual(iron.rule(), Optional.of(StockRuleStatus.BELOW_MINIMUM), "badge");
                    helper.assertTrue(terminal.holdsInStock(IRON), "and is never reported as gone");
                })
                .thenSucceed();
    }

    /** Restores the config even when a config-batch test fails before its own restore. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreStockRuleConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * The controller's goggles: how many rules govern this aisle, how many of them call for their item and how many
     * stop one from being stored. The last number is what turns "my belt is backing up and the crane does nothing"
     * into a line a player can read, and it is why {@code AT_MAXIMUM} is never reported as a full warehouse.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleControllerReportsRules(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().stockRules(), 0, "an aisle without rules counts none");

                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, GOLD, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null, 0L);
                })
                .thenWaitUntil(() -> {
                    // The counts are refreshed by the rule tick, not while a tooltip is built.
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.governingStockRuleCount(), 2, "both rules govern");
                    helper.assertValueEqual(controller.stockRulesBelowMinimum(), 1, "the iron is short");
                    helper.assertValueEqual(controller.stockRulesAtMaximum(), 1, "and no gold is accepted");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    controller.onGoggleObserved();
                    ControllerGoggleSummary summary = controller.summary();
                    helper.assertValueEqual(summary.stockRules(), 2, "goggle count");
                    helper.assertValueEqual(summary.rulesBelowMinimum(), 1, "below minimum");
                    helper.assertValueEqual(summary.rulesAtMaximum(), 1, "at maximum");

                    // The numbers travel in the chunk packet, so they have to survive it — and an aisle without rules
                    // must not add anything to it at all.
                    CompoundTag tag = new CompoundTag();
                    summary.write(tag);
                    ControllerGoggleSummary read = ControllerGoggleSummary.read(tag);
                    helper.assertValueEqual(read.stockRules(), 2, "synced count");
                    helper.assertValueEqual(read.rulesBelowMinimum(), 1, "synced below minimum");
                    helper.assertValueEqual(read.rulesAtMaximum(), 1, "synced at maximum");
                    CompoundTag empty = new CompoundTag();
                    ControllerGoggleSummary.NONE.write(empty);
                    helper.assertFalse(empty.contains("StockRules"), "an aisle without rules writes no rule keys");
                })
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(KEEPER_RACK)))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().governingStockRuleCount(), 0,
                        "and the counts go back to nothing when the keeper is gone"))
                .thenSucceed();
    }

    /**
     * A reserve holds items back from the warehouse's own automation, and a <b>pattern is no way around it</b>: a
     * redstone-triggered request for something this aisle could make out of a reserved ingredient is refused, with the
     * reserve's own reason, and no production order is started. Otherwise a lever on an output would spend exactly the
     * items a rule protects — one step removed, and irreversibly, because a machine never gives them back.
     * <p>
     * A <b>player</b> at a terminal may still order it: the reserve protects against automation, not against the player
     * who wrote it, which is the decision the whole feature rests on.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleReserveBoundsProduction(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reserve refuses an order"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station is there");
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in");
                })
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                            "and its result");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK),
                        "the aisle knows it can make planks"))
                .thenExecute(() -> {
                    // Every log is reserved, so the warehouse's own automation may spend none of them.
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, LOG, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, LOGS_IN_STOCK);

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, LOG), 0L,
                            "no log is left for automation");
                    helper.assertTrue(controller.producibleAmount(PLANK) > 0,
                            "a player could still order planks, so the ingredients really are there");

                    RequestResult refused = request(aisle, StockAccess.AUTOMATION, PLANK, ORDERED_PLANKS);
                    helper.assertFalse(refused.isAccepted(), "a redstone request for the product is refused: " + refused);
                    helper.assertValueEqual(refused.rejection(), Optional.of(RequestRejection.RESERVED),
                            "and it is told it was the reserve, not a missing item");
                    helper.assertValueEqual(controller.productionOrders().size(), 0,
                            "no order may be started against reserved ingredients");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and nothing is left waiting");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, LOG), 0L,
                            "the reserve is untouched");
                })
                .thenExecute(() -> {
                    // The same request from a player: the reserve is theirs to spend.
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestResult accepted = request(aisle, StockAccess.PLAYER, PLANK, ORDERED_PLANKS);
                    helper.assertTrue(accepted.isAccepted(), "a player may order it: " + accepted);
                    helper.assertValueEqual(controller.productionOrders().size(), 1, "and the order was started");
                })
                .thenSucceed();
    }

    /**
     * The aisle itself is lost and comes back — the dock is broken and placed again — while a maximum and a reserve are
     * biting. The controller's copy of the rules is <b>kept</b> through it: every way back into that copy needs the
     * keeper's chunk to be loaded, so forgetting it for even one tick would store items past a maximum (which are never
     * moved back out) and hand a reserve to automation (which is never recalled). The keepers do stop signalling while
     * there is no warehouse, because a comparator that calls for an item nothing enforces drives a farm for nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockRuleAisleLostKeepsRules(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    // Three rules, one per direction, so all three are in force when the aisle disappears: a maximum
                    // that caps storing, a reserve that holds automation back, and a minimum that is calling for gold.
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_RESERVE, null, DIAMONDS_IN_STOCK);
                    keeper.editRule(2, StockKeeperRules.FIELD_ITEM, GOLD, 0L);
                    keeper.editRule(2, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().stockRules().size(), 3, "all three are in the copy");
                    helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "the keeper signals the unmet minimum");
                })
                // No dock, no aisle: the layout, the members and the stock index all go. The controller notices on its
                // next re-link check, which is also where it lets the keepers go quiet.
                .thenExecute(() -> aisle.breakBlock(aisle.dockPos()))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().status(), ControllerStatus.NO_DOCK,
                        "the aisle is gone"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.stockRules().size(), 3,
                            "the rules of a warehouse without an aisle are kept, not forgotten");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), (long) IRON_MAXIMUM, "the maximum stands");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND), 0L,
                            "and so does the reserve");
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT),
                            "but the keeper stops signalling for a warehouse that is not there");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 0,
                            "including its comparator");
                })
                .thenExecute(() -> aisle.build(false))
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.stockRules().size(), 3, "the same three rules govern again");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), (long) IRON_MAXIMUM, "maximum");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND), 0L, "reserve");
                    helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "and the keeper calls for its item again");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static void feed(AisleFixture aisle, Map<ItemKey, Long> conserved, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), stack.copy());
            ItemCensus.change(conserved, ItemKey.of(stack), stack.getCount());
        }
    }

    /** A request at the aisle's output station, as a redstone pulse would make it. */
    private static RequestResult request(AisleFixture aisle, StockAccess access, ItemKey key, int amount) {
        return requestAt(aisle, OUTPUT_RACK, access, key, amount);
    }

    /** A request delivered to the station at {@code rack}, made by the given taker. */
    private static RequestResult requestAt(AisleFixture aisle, RackPosition rack, StockAccess access, ItemKey key,
            int amount) {
        return aisle.controller().request(aisle.absoluteRackPos(rack), key, amount, RequestQueue.NO_AMOUNT_LIMIT,
                access);
    }

    /** What the open requests for {@code destination} still wait for. */
    private static int openAmount(WarehouseControllerBlockEntity controller, BlockPos destination) {
        int total = 0;
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.requestsFor(destination))
            total += request.remaining();
        return total;
    }

    private static TerminalStockEntry entry(GameTestHelper helper, WarehouseTerminalBlockEntity terminal, ItemKey key) {
        for (TerminalStockEntry entry : terminal.stockSnapshot()) {
            if (entry.key().equals(key))
                return entry;
        }
        helper.fail("the terminal does not report " + key);
        throw new IllegalStateException("unreachable");
    }

    /** The same entry as the screen gets it, i.e. after the menu's conversion. */
    private static StockCount<ItemKey> count(GameTestHelper helper, WarehouseTerminalBlockEntity terminal,
            ItemKey key) {
        Player player = playerAtAbsolute(helper, terminal.getBlockPos());
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        for (StockCount<ItemKey> count : menu.stockCounts()) {
            if (count.key().equals(key))
                return count;
        }
        helper.fail("the terminal menu does not report " + key);
        throw new IllegalStateException("unreachable");
    }

    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            throw new IllegalStateException("unreachable");
        }
        return type.cast(loaded);
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        return playerAtAbsolute(helper, helper.absolutePos(pos));
    }

    private static Player playerAtAbsolute(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(pos);
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    /** Enough different item types to fill a lowered stock window. */
    private static ItemStack[] fillerStacks() {
        return new ItemStack[] { new ItemStack(Items.STONE), new ItemStack(Items.DIRT), new ItemStack(Items.SAND),
                new ItemStack(Items.GRAVEL), new ItemStack(Items.OAK_LOG), new ItemStack(Items.BIRCH_LOG),
                new ItemStack(Items.SPRUCE_LOG), new ItemStack(Items.ACACIA_LOG), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.ANDESITE) };
    }

    private static ItemStack[] moreFillerStacks() {
        return new ItemStack[] { new ItemStack(Items.DIORITE), new ItemStack(Items.GRANITE),
                new ItemStack(Items.CLAY_BALL), new ItemStack(Items.BRICK), new ItemStack(Items.COAL),
                new ItemStack(Items.CHARCOAL), new ItemStack(Items.APPLE), new ItemStack(Items.WHEAT),
                new ItemStack(Items.CARROT), new ItemStack(Items.POTATO) };
    }
}
