package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.ProductionStationHooks;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StoppedProduct;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRuleStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import dev.wareworks.network.ProductionScreenPayload;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of <b>where the safety stop shows and how a player gets out of it</b> ({@code docs/warehouse-system.md}
 * §3.5.4, ADR-027 widened by ADR-032, M20, issue #4).
 * <p>
 * The stop itself is proved elsewhere: {@link StockRestockGameTests} covers the automatic order it was built for and
 * {@link ProductionPlanFailureGameTests} that it now covers every kind of order. What is proved here is the half a player
 * actually meets — that the station standing in front of the machine <b>says</b> so and is where the way back is:
 * <ul>
 * <li>{@code productionstopshowsatthestationofaplayersorder} — a player's own order for an item <b>no stock rule
 * governs</b> loses its batch: the station's screen state, its goggle numbers and its own block all say so, a
 * {@code /setblock} that clears the lamp is corrected, the pause comes back from a save, a crafted resume reaches
 * nothing, the screen's stopped row lifts it and the item can be ordered again;</li>
 * <li>{@code productionstopfromaredstonerequestisliftedatthestation} — the same for the unattended trigger, lifted with
 * the sneak-click on the block and proved by a second pulse that is served again;</li>
 * <li>{@code productionstopfromanautomaticorderisliftedatthestation} — a <b>rule-born</b> pause, which the stock keeper
 * could lift too, is lifted at the station as well: one stop, one set of surfaces, whoever armed it;</li>
 * <li>{@code productionstoprowssurvivethewire} — the item, the cause and the cost survive the station's own payload,
 * which is the one thing a JUnit test cannot check, and a station whose machines work still carries no row at all;</li>
 * <li>{@code productionstoplampneveroutliveswhatitreports} — a lamp that came back from a save with nothing behind it is
 * put out by the first pass after the load, and a rule edit that prunes a pause takes its lamp with it in the same tick
 * (review fix: the cheap guard would otherwise have returned for ever);</li>
 * <li>{@code productionstopresumeworkswithafulloffhand} — the sneak-click reaches the block with a shield in the offhand,
 * which is the case vanilla drops before the block and no {@code GameTestHelper#useBlock} can see (review fix).</li>
 * </ul>
 * Every test in which the crane moves items runs an {@link ItemCensus} on every tick: a stop is about items that are
 * already gone, and none of it may invent or destroy another one.
 * <p>
 * Layout ({@code aisle_16x10x7}): right rack plane, the saw station at 0 (one log makes {@value #PLANKS_PER_RUN}
 * planks), a warehouse input at 1, a warehouse output at 2, a crafter station at 3 ({@value #PLANKS_PER_CHEST} planks
 * make one chest) and a stock keeper at 4. Left rack plane: logs at 1, an empty storage location at 2.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ProductionStopGameTests {
    /** Every test here shortens {@code productionOrderTimeoutTicks} to the same value ({@link ConfigOverrides}). */
    private static final String TIMEOUT_BATCH = "wareworksProductionStopTimeouts";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    /** Short enough for a lost order to time out inside a test, long enough for the crane to finish a trip. */
    private static final int SHORT_ORDER_TIMEOUT = 200;
    private static final int SETTLE_TICKS = 80;
    /** A container id no menu of these tests has, for the hostile-payload checks. */
    private static final int OTHER_CONTAINER_ID = 4321;

    private static final RackPosition SAW_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORE_RACK = new RackPosition(2, 0, Side.LEFT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);

    private static final int PLANKS_PER_RUN = 4;
    private static final int PLANKS_PER_CHEST = 4;
    private static final int LOGS_IN_STOCK = 16;
    /** The minimum the keeper asks for in the automatic test: one whole run of the saw's pattern. */
    private static final int PLANK_MINIMUM = 4;

    private ProductionStopGameTests() {
    }

    @AfterBatch(batch = TIMEOUT_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- a player's own order ---------------------------------------------------------------------------------------

    /**
     * <b>The machine that swallowed the batch is the block that says so.</b> A player orders planks, the crane hands the
     * log to the saw, nobody plays the saw, and the order times out: from that moment the warehouse makes no planks at
     * all, and the item is one <b>no stock rule governs</b> — so the stock keeper's own resume, which only ever reaches an
     * item one of its rules governs, could not lift this pause at all. Before M20 that was a dead end.
     * <p>
     * Everything a player can see and do about it is asserted in one story, because they are one feature:
     * <ul>
     * <li>the station's screen state names the item, why it stopped and what it cost, and the <b>other</b> station's says
     * nothing — a stop is about one machine;</li>
     * <li>its goggle numbers say the same thing without ever putting an item into a chunk packet;</li>
     * <li>its own block turns its lamp on, which is the only surface a player does not have to open anything for, and a
     * {@code /setblock} that clears it by hand is corrected by the next rule pass;</li>
     * <li>the pause comes back from a save, and the reloaded controller lights the lamp again by itself;</li>
     * <li>a resume payload from a player without that menu, with another menu's id, or for the station that does
     * <b>not</b> make the item, reaches nothing;</li>
     * <li>the stopped row of the screen lifts it, says what it cost, and says "nothing stopped" when clicked twice;</li>
     * <li>and the very same order is accepted again afterwards.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStopShowsAtTheStationOfAPlayersOrder(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stopAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an order is lost at a machine"));
        long[] delivered = { 0L };
        CompoundTag[] saved = new CompoundTag[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> order(helper, aisle, PLANK, PLANKS_PER_RUN))
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(SAW_RACK, LOG) > 0,
                        "the crane hands the log to the saw"))
                .thenExecute(() -> delivered[0] = aisle.stationCount(SAW_RACK, LOG))
                // Nobody plays the machine: the log lies in the station and no plank ever arrives.
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the lost batch stops the warehouse from making planks"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.stockRules().isEmpty(), "no rule governs anything in this aisle");
                    StockRulePause pause = controller.stockRulePause(PLANK).orElseThrow();
                    helper.assertValueEqual(pause.cause(), StockRulePause.Cause.ORDER_TIMED_OUT,
                            "an order somebody asked for timed out");
                    helper.assertFalse(pause.isRuleBorn(), "so no rule could ever forget it");

                    // What the station itself answers, which is what all of its surfaces are built from.
                    StoppedProduct stopped = onlyStopped(helper, aisle.productionAt(SAW_RACK));
                    helper.assertValueEqual(stopped.key(), PLANK, "the saw reports the item it makes");
                    helper.assertValueEqual(stopped.cause(), StockRulePause.Cause.ORDER_TIMED_OUT, "with the cause");
                    helper.assertValueEqual(stopped.unrecovered(), delivered[0],
                            "and exactly the items the machine was given");
                    helper.assertValueEqual(aisle.productionAt(CRAFTER_RACK).stoppedProducts().size(), 0,
                            "the station that makes chests says nothing: a stop is about one machine");
                })
                .thenExecute(() -> {
                    // The screen of the one player who opens it, and the wire it travels on.
                    Player player = playerAt(helper, aisle.rackPos(SAW_RACK));
                    ProductionScreenState state = openMenu(player, aisle.productionAt(SAW_RACK)).screenState();
                    helper.assertTrue(state.anyStopped(), "the screen is told that something here is stopped");
                    helper.assertValueEqual(state.stopped().size(), 1, "one product");
                    helper.assertValueEqual(state.stoppedOf(PLANK).map(StoppedProduct::unrecovered),
                            Optional.of(delivered[0]), "the row can name what the plank pattern cost");
                    helper.assertTrue(state.stoppedOf(CHEST).isEmpty(), "and says nothing about the crafter's product");
                    helper.assertValueEqual(state.unrecoveredTotal(), delivered[0], "the total is the one loss");

                    // The goggles: numbers only, never the items themselves (they travel in every chunk packet).
                    WarehouseProductionBlockEntity station = aisle.productionAt(SAW_RACK);
                    station.onGoggleObserved();
                    helper.assertTrue(station.productionSummary().anyStopped(), "the goggles say it too");
                    helper.assertValueEqual(station.productionSummary().stoppedProducts(), 1, "one stopped product");
                    helper.assertValueEqual(station.productionSummary().unrecovered(), delivered[0],
                            "and what it cost");
                })
                .thenWaitUntil(() -> helper.assertTrue(lampOf(helper, aisle, SAW_RACK),
                        "the saw's own block lights its stopped lamp"))
                .thenExecute(() -> helper.assertFalse(lampOf(helper, aisle, CRAFTER_RACK),
                        "and the crafter's block stays dark"))
                .thenExecute(() -> {
                    // A hand-set block state is a claim about a machine that nothing behind it backs up.
                    setLamp(helper, aisle, SAW_RACK, false);
                    saved[0] = aisle.controller().saveWithFullMetadata(helper.getLevel().registryAccess());
                    installController(helper, aisle, saved[0].copy());
                })
                // The fresh controller finds its aisle again first, exactly as it does after a chunk load.
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                            "the reloaded controller is still holding the planks");
                    helper.assertValueEqual(onlyStopped(helper, aisle.productionAt(SAW_RACK)).unrecovered(),
                            delivered[0], "and the station reports the same cost after the reload");
                })
                .thenWaitUntil(() -> helper.assertTrue(lampOf(helper, aisle, SAW_RACK),
                        "the reloaded controller lights the lamp again by itself"))
                .thenExecute(() -> {
                    // Nothing but a player standing at this station may lift it.
                    Player player = playerAt(helper, aisle.rackPos(SAW_RACK));
                    ProductionMenu menu = openMenu(player, aisle.productionAt(SAW_RACK));
                    player.containerMenu = player.inventoryMenu;
                    helper.assertTrue(ProductionMenu.submitResume(player, menu.containerId).isEmpty(),
                            "a player who has no production screen open resumes nothing");
                    player.containerMenu = menu;
                    helper.assertTrue(ProductionMenu.submitResume(player, OTHER_CONTAINER_ID).isEmpty(),
                            "and neither does another menu's id");
                    helper.assertTrue(ProductionMenu.submitResume(null, menu.containerId).isEmpty(),
                            "nor a payload without a player at all");
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(), "the stop is still held");

                    // The station that does not make planks cannot lift the planks, however its screen is used.
                    Player atCrafter = playerAt(helper, aisle.rackPos(CRAFTER_RACK));
                    ProductionMenu crafter = openMenu(atCrafter, aisle.productionAt(CRAFTER_RACK));
                    helper.assertValueEqual(ProductionMenu.submitResume(atCrafter, crafter.containerId),
                            Optional.of(false), "the crafter's screen resumes nothing");
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                            "because a click there says nothing about the saw");
                })
                .thenExecute(() -> {
                    Player player = playerAt(helper, aisle.rackPos(SAW_RACK));
                    ProductionMenu menu = openMenu(player, aisle.productionAt(SAW_RACK));
                    helper.assertValueEqual(ProductionMenu.submitResume(player, menu.containerId), Optional.of(true),
                            "the stopped row of the saw's own screen lifts it");
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isEmpty(), "nothing is held any more");
                    helper.assertValueEqual(ProductionMenu.submitResume(player, menu.containerId), Optional.of(false),
                            "a second click finds nothing to lift and says so");
                    helper.assertValueEqual(menu.screenState().stopped().size(), 0,
                            "and the screen has no stopped row left");
                })
                .thenWaitUntil(() -> helper.assertFalse(lampOf(helper, aisle, SAW_RACK), "the lamp goes out"))
                .thenExecute(() -> {
                    // The whole point of the way back: the warehouse plans the item again.
                    RequestResult again = order(helper, aisle, PLANK, PLANKS_PER_RUN);
                    helper.assertValueEqual(again.producing(), PLANKS_PER_RUN, "the same order is planned again");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1, "as one order");
                })
                .thenSucceed();
    }

    // --- a redstone request ------------------------------------------------------------------------------------------

    /**
     * <b>The unattended trigger.</b> A redstone pulse at a warehouse output orders planks, its batch is lost in the same
     * way, and the stop holds that port back as well — a pulse that repeats every few seconds is exactly the drain
     * ADR-027 exists to stop, and the port says why it was refused.
     * <p>
     * The way back here is the one a player finds without opening anything: a <b>sneak-click with an empty hand</b> on the
     * station in front of the machine. Afterwards a second pulse is served again, which is what proves that a resumed item
     * is really orderable by the same trigger that was refused.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStopFromARedstoneRequestIsLiftedAtTheStation(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stopAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a pulse loses its batch"));
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).above();

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, PLANK.toStack(), PLANKS_PER_RUN, trigger))
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(SAW_RACK, LOG) > 0,
                        "the pulse's order hands the log to the saw"))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "and its lost batch stops the planks"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().stockRulePause(PLANK).orElseThrow().cause(),
                            StockRulePause.Cause.ORDER_TIMED_OUT,
                            "a pulse is an order somebody asked for, not the warehouse's own");
                    helper.assertValueEqual(onlyStopped(helper, aisle.productionAt(SAW_RACK)).key(), PLANK,
                            "the station in front of the machine says which item it is");
                })
                // The trigger that started it is held back too, and says why.
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, PLANK.toStack(), PLANKS_PER_RUN, trigger))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "a pulse creates no order for a stopped item");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT_RACK).lastRejection(),
                            Optional.of(RequestRejection.PRODUCTION_PAUSED),
                            "and the port says that the warehouse has stopped making it");
                })
                .thenExecute(() -> {
                    // The way back without opening anything: sneaking with an empty hand on the station.
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    player.setShiftKeyDown(true);
                    helper.useBlock(aisle.rackPos(SAW_RACK), player);
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isEmpty(),
                            "the sneak-click on the saw lifts the stop");
                    helper.assertValueEqual(aisle.productionAt(SAW_RACK).stoppedProducts().size(), 0,
                            "and the station has nothing left to report");
                })
                .thenExecute(() -> aisle.requestAt(OUTPUT_RACK, PLANK.toStack(), PLANKS_PER_RUN, trigger))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "and the very trigger that was refused is served again"))
                .thenSucceed();
    }

    // --- the warehouse's own order -----------------------------------------------------------------------------------

    /**
     * <b>One stop, one set of surfaces, whoever armed it.</b> A stock rule's own refill loses its batch, so this pause is
     * rule-born (M15): the keeper's row shows it, its lamp burns and its own mark could lift it. The station in front of
     * the machine now shows and lifts the very same stop — and that is the right place, because the machine is what a
     * player has to look at either way.
     * <p>
     * What the rule-born case adds is the other half: after the resume the <b>warehouse itself</b> orders again, without
     * anybody asking, which is what says the stop was really lifted and not just hidden.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStopFromAnAutomaticOrderIsLiftedAtTheStation(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stopAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a refill is lost"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> keeperRule(aisle, 0, PLANK, StockKeeperRules.FIELD_MINIMUM, PLANK_MINIMUM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.stationCount(SAW_RACK, LOG) > 0,
                        "the warehouse orders its own refill and the log reaches the saw"))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the lost refill pauses the rule"))
                .thenExecute(() -> {
                    StockRulePause pause = aisle.controller().stockRulePause(PLANK).orElseThrow();
                    helper.assertValueEqual(pause.cause(), StockRulePause.Cause.TIMED_OUT,
                            "an automatic order armed it");
                    helper.assertTrue(pause.isRuleBorn(), "so the rule it belongs to could forget it");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.PAUSED, "the keeper's row is paused");
                    helper.assertValueEqual(onlyStopped(helper, aisle.productionAt(SAW_RACK)).cause(),
                            StockRulePause.Cause.TIMED_OUT, "and the station reports the very same stop");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.PAUSED), "the keeper's pause lamp burns");
                    helper.assertTrue(lampOf(helper, aisle, SAW_RACK), "and so does the station's");
                })
                .thenExecute(() -> {
                    // The station is where the machine is, so it is a way back for a rule's pause as well.
                    Player player = playerAt(helper, aisle.rackPos(SAW_RACK));
                    ProductionMenu menu = openMenu(player, aisle.productionAt(SAW_RACK));
                    helper.assertValueEqual(ProductionMenu.submitResume(player, menu.containerId), Optional.of(true),
                            "the station's own screen lifts a rule-born stop too");
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0, "nothing is held any more");
                    helper.assertValueEqual(status(helper, aisle), StockRuleStatus.BELOW_MINIMUM,
                            "and the rule reads as what its numbers say again");
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.PAUSED), "the keeper's pause lamp goes out");
                    helper.assertFalse(lampOf(helper, aisle, SAW_RACK), "and the station's with it");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "and the warehouse orders the refill again by itself"))
                .thenSucceed();
    }

    // --- the wire ----------------------------------------------------------------------------------------------------

    /**
     * The stopped rows survive the <b>wire</b> (M20): a production station's payload is where the item, the cause and
     * the cost travel to the one player who has the screen open, and none of the three can be checked by a JUnit test,
     * because every one of them needs a buffer with the server's registries.
     * <p>
     * It also pins the two properties a screen depends on: a station whose machines work writes the shape it always
     * wrote plus one zero byte, and a payload that claims more stopped products than a station can have is cut to the
     * bound rather than believed.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void productionStopRowsSurviveTheWire(GameTestHelper helper) {
        ProductionScreenState.OrderView order = new ProductionScreenState.OrderView(UUID.randomUUID(),
                ProductionOrderState.TIMED_OUT, PLANK, PLANKS_PER_RUN, 0L, 0L, 1L);
        List<StoppedProduct> stopped = List.of(new StoppedProduct(PLANK, StockRulePause.Cause.ORDER_TIMED_OUT, 7L),
                new StoppedProduct(CHEST, StockRulePause.Cause.TIMED_OUT, 0L));
        ProductionScreenState sent = new ProductionScreenState(List.of(), List.of(order), stopped);

        ProductionScreenState received = roundTrip(helper, sent).state();
        helper.assertValueEqual(received.stopped().size(), 2, "both stopped products arrive");
        helper.assertValueEqual(received.stopped(), stopped, "with their items, causes and costs unchanged");
        helper.assertValueEqual(received.stoppedOf(PLANK).map(StoppedProduct::unrecovered), Optional.of(7L),
                "the screen can look a product up by its item");
        helper.assertTrue(received.stoppedOf(LOG).isEmpty(), "and finds nothing for an item nothing makes here");
        helper.assertValueEqual(received.unrecoveredTotal(), 7L, "a cost of zero adds nothing to the total");
        helper.assertValueEqual(received.orders().size(), 1, "the orders are read before the stopped rows");

        ProductionScreenState working = roundTrip(helper, new ProductionScreenState(List.of(), List.of(order))).state();
        helper.assertFalse(working.anyStopped(), "a station whose machines work carries no stopped row at all");

        List<StoppedProduct> tooMany = new ArrayList<>();
        for (int i = 0; i <= ProductionScreenState.MAX_STOPPED; i++)
            tooMany.add(new StoppedProduct(PLANK, StockRulePause.Cause.ORDER_CANCELLED, i));
        helper.assertValueEqual(roundTrip(helper, new ProductionScreenState(List.of(), List.of(), tooMany))
                .state().stopped().size(), ProductionScreenState.MAX_STOPPED,
                "a payload never carries more stopped products than a station can have");
        helper.succeed();
    }

    // --- the lamp cannot outlive its stop ----------------------------------------------------------------------------

    /**
     * <b>A lamp nothing backs up is put out after a load</b> (M20 review fix). The lamp lives in a block state and
     * survives every save; the controller's record of which stations it lit does not. The cheap guard of
     * {@code refreshProductionStops} — "no pause and nothing lit, so nothing to do" — therefore needs one honest pass
     * behind it, or a station whose chunk was not loaded when the last pause was lifted burns for a warehouse that holds
     * nothing at all, and nothing can ever put it out.
     * <p>
     * The state is reached here the way a save reaches it: a lamp set by hand on a station this controller never lit, and
     * then a fresh controller loaded from the saved tag with an <b>empty</b> pause map. Before the fix every later pass
     * returned at its first line and the lamp stayed lit for good.
     * <p>
     * The second half pins the window a rule edit opened: deleting a rule prunes its pause, and the lamp has to go with it
     * in the <b>same tick</b> rather than at the next rule pass — a save inside that window was what made the stale lamp
     * permanent.
     */
    @GameTest(template = AISLE_16X10X7, batch = TIMEOUT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStopLampNeverOutlivesWhatItReports(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = stopAisle(helper);
        CompoundTag[] saved = new CompoundTag[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "this warehouse has never lost a batch");
                    // A lamp the controller never lit: what a /setblock leaves behind, and what a save can hand back.
                    setLamp(helper, aisle, CRAFTER_RACK, true);
                    saved[0] = aisle.controller().saveWithFullMetadata(helper.getLevel().registryAccess());
                    installController(helper, aisle, saved[0].copy());
                    helper.assertTrue(lampOf(helper, aisle, CRAFTER_RACK),
                            "the fresh controller starts with no record of it at all");
                })
                // One sweep per load is what makes this true; before the fix the guard returned for ever.
                .thenWaitUntil(() -> helper.assertFalse(lampOf(helper, aisle, CRAFTER_RACK),
                        "the first pass after the load puts out a lamp nothing is behind"))
                .thenExecute(() -> keeperRule(aisle, 0, PLANK, StockKeeperRules.FIELD_MINIMUM, PLANK_MINIMUM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(PLANK).isPresent(),
                        "the rule's own refill loses its batch and stops the planks"))
                .thenWaitUntil(() -> helper.assertTrue(lampOf(helper, aisle, SAW_RACK), "the saw's lamp comes on"))
                .thenExecute(() -> {
                    // Deleting the rule prunes its pause: the lamp may not wait for the next rule pass to notice.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, null, 0L);
                    helper.assertValueEqual(aisle.controller().pausedStockRuleCount(), 0,
                            "the pause goes with the rule it belonged to");
                    helper.assertFalse(lampOf(helper, aisle, SAW_RACK),
                            "and its lamp goes out in the very same tick");
                })
                .thenSucceed();
    }

    /**
     * <b>The sneak-click reaches the block whatever is in the offhand</b> (M20 review fix, {@link ProductionStationHooks}).
     * <p>
     * Vanilla drops a sneaking interaction before the block whenever <i>either</i> hand holds something that does not
     * bypass the sneak, and NeoForge's default for an item is that it does not. With an empty main hand and a shield in the
     * offhand, the station's {@code useItemOn} was therefore never called at all — and neither was {@code useWithoutItem},
     * which sits inside the same guard — so the gesture the block's own description and its goggle hint both teach did
     * nothing and said nothing. {@code GameTestHelper#useBlock} cannot see it: it calls {@code useItemOn} itself and skips
     * the game mode's guard entirely, which is why this test goes through the real interaction event instead.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void productionStopResumeWorksWithAFullOffhand(GameTestHelper helper) {
        AisleFixture aisle = stopAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertReady(helper, aisle)).thenExecute(() -> {
            BlockPos station = aisle.rackPos(SAW_RACK);
            Player player = playerAt(helper, station);
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.OFF_HAND, Items.SHIELD.getDefaultInstance());

            helper.assertTrue(forcesBlockUse(helper, player, station),
                    "a sneaking player with an empty main hand reaches the station although the offhand is full");
            helper.assertTrue(useBlockForced(helper, player, station),
                    "and the registered listener really says so on the interaction event");

            player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
            helper.assertFalse(forcesBlockUse(helper, player, station),
                    "something held is a click for that item, which the block passes on as it always did");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setShiftKeyDown(false);
            helper.assertFalse(forcesBlockUse(helper, player, station),
                    "an ordinary right-click needs nothing forced: vanilla delivers it anyway");
            player.setShiftKeyDown(true);
            helper.assertFalse(forcesBlockUse(helper, player, aisle.rackPos(CRAFTER_RACK).above()),
                    "and no other block is touched");
        }).thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Whether the hook would hand this sneaking main-hand click to the block at the test-relative {@code pos}. */
    private static boolean forcesBlockUse(GameTestHelper helper, Player player, BlockPos pos) {
        return ProductionStationHooks.forcesBlockUse(player, helper.getLevel(), helper.absolutePos(pos),
                InteractionHand.MAIN_HAND);
    }

    /** What the real interaction event says about the same click, i.e. whether the listener is registered at all. */
    private static boolean useBlockForced(GameTestHelper helper, Player player, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.NORTH, absolute, true);
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, absolute, hit).getUseBlock().isTrue();
    }

    /**
     * The aisle these tests share: a saw station that makes {@value #PLANKS_PER_RUN} planks out of one log, a crafter
     * station that makes a chest out of {@value #PLANKS_PER_CHEST} planks (so that "a stop is about one machine" can be
     * asserted), a warehouse input, an output, a stock keeper holding <b>no</b> rule and {@value #LOGS_IN_STOCK} logs.
     */
    private static AisleFixture stopAisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(SAW_RACK);
        aisle.production(CRAFTER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        setPattern(helper, aisle, SAW_RACK, LOG, 1, PLANK, PLANKS_PER_RUN);
        setPattern(helper, aisle, CRAFTER_RACK, PLANK, PLANKS_PER_CHEST, CHEST, 1);
        return aisle;
    }

    /** One ingredient to one result in the first pattern slot of the station at {@code rack}. */
    private static void setPattern(GameTestHelper helper, AisleFixture aisle, RackPosition rack, ItemKey ingredient,
            int ingredientCount, ItemKey result, int resultCount) {
        WarehouseProductionBlockEntity station = aisle.productionAt(rack);
        helper.assertTrue(station.setPatternEntry(0, 0, ingredient, ingredientCount), "the ingredient of " + rack);
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, result, resultCount),
                "the result of " + rack);
    }

    /** Waits until both stations are recorded, the patterns are readable and the logs are in the index. */
    private static void assertReady(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 2, "both production stations are recorded");
        helper.assertValueEqual(controller.aislePatterns().size(), 2, "with one pattern each");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage locations were read");
        helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in the index");
        helper.assertTrue(controller.producibleKeys().contains(PLANK), "and the aisle knows it can make planks");
    }

    /** An order at the output station, which must be accepted. */
    private static RequestResult order(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount) {
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
        helper.assertTrue(result.isAccepted(), "the order for " + key + " is accepted: " + result);
        return result;
    }

    /** The single product the safety stop is holding at {@code station}; fails the test when it is not exactly one. */
    private static StoppedProduct onlyStopped(GameTestHelper helper, WarehouseProductionBlockEntity station) {
        List<StoppedProduct> stopped = station.stoppedProducts();
        if (stopped.size() != 1) {
            helper.fail("expected exactly one stopped product at the station, found " + stopped.size());
            throw new IllegalStateException("unreachable");
        }
        return stopped.getFirst();
    }

    /** Whether the station's own block is showing the safety stop. */
    private static boolean lampOf(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        return helper.getBlockState(aisle.rackPos(rack)).getValue(WarehouseProductionBlock.STOPPED);
    }

    /** Sets that lamp by hand, the way a {@code /setblock} would: a claim nothing behind it backs up. */
    private static void setLamp(GameTestHelper helper, AisleFixture aisle, RackPosition rack, boolean stopped) {
        BlockPos pos = aisle.rackPos(rack);
        BlockState state = helper.getBlockState(pos).setValue(WarehouseProductionBlock.STOPPED, stopped);
        helper.getLevel().setBlock(helper.absolutePos(pos), state, Block.UPDATE_CLIENTS);
        helper.assertValueEqual(lampOf(helper, aisle, rack), stopped, "the lamp was set by hand");
    }

    /** Writes one field of the keeper's rule {@code index} for {@code key}. */
    private static void keeperRule(AisleFixture aisle, int index, ItemKey key, int field, long value) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(index, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(index, field, null, value);
    }

    /** The status the controller gives the keeper's first rule row. */
    private static StockRuleStatus status(GameTestHelper helper, AisleFixture aisle) {
        List<StockRuleStatus> statuses = aisle.controller().stockRuleStatuses(aisle.absoluteRackPos(KEEPER_RACK));
        if (statuses.isEmpty()) {
            helper.fail("the keeper has no rule to judge");
            throw new IllegalStateException("unreachable");
        }
        return statuses.getFirst();
    }

    /** Encodes and decodes a production screen payload with its own codec, on a buffer with the server's registries. */
    private static ProductionScreenPayload roundTrip(GameTestHelper helper, ProductionScreenState state) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionScreenPayload payload = new ProductionScreenPayload(1, state);
        ProductionScreenPayload.STREAM_CODEC.encode(buffer, payload);
        ProductionScreenPayload decoded = ProductionScreenPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        return decoded;
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

    /** Replaces the live controller with one loaded from {@code tag}, the way a reload builds it. */
    private static void installController(GameTestHelper helper, AisleFixture aisle, CompoundTag tag) {
        WarehouseControllerBlockEntity live = aisle.controller();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag, registries);
        if (!(loaded instanceof WarehouseControllerBlockEntity copy)) {
            helper.fail("a saved warehouse controller must load again as one");
            return;
        }
        helper.getLevel().setBlockEntity(copy);
        helper.assertTrue(live.isRemoved(), "the controller was really replaced");
    }
}
