package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionGoggleSummary;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorGate;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.network.ProductionSelectorPayload;
import dev.wareworks.util.WareworksLang;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of the controller's side of the Recipe Selector (ADR-085, design §5, §8, §9, §3.5): the gate in the crane's
 * supply needs, the line's switch, the return input and its quarantine, trimming, holds and persistence.
 * <p>
 * The selector block itself is not used here. The station is put into local mode directly
 * ({@link WarehouseProductionBlockEntity#setSelectorMode}) and given a {@link TestMachine} in place of the selector and
 * its machine ({@link WarehouseProductionBlockEntity#installTestSelectorArm}): it keeps one "filter" and answers an arm
 * request exactly as a selector does (already set, set, or busy). The tests <b>play the machine</b>, as the production
 * tests do: they take the ingredients out of the station and put the product its filter names into the station's return
 * input, and the item census expectation changes in exactly that step.
 * <p>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails; logs at 1 left
 * (and 4 left for the tests that need two trips), empty storage at 2 and 3 left, the production station at 0 right, its
 * return input at 1 right, the output at 2 right and a second Warehouse Input at 3 right.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RecipeSelectorGameTests {
    /** The tests that lower the order timeout run in their own batch ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksSelectorConfig";
    private static final int SHORT_TIMEOUT = 200;

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;

    private static final RackPosition STATION = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition RETURN = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OTHER_INPUT = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition LOGS = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PRODUCTS = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition MORE_PRODUCTS = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition SECOND_LOGS = new RackPosition(4, 0, Side.LEFT);
    /** An accepting port that outranks every rack, for the tests that place it ({@link #DIVERSION_RANK}). */
    private static final RackPosition DIVERSION = new RackPosition(4, 0, Side.RIGHT);
    private static final int DIVERSION_RANK = 3;
    /** What a controller save calls its pending Recipe Selector holds ({@code ControllerPersistence}). */
    private static final String SELECTOR_HOLDS_TAG = "SelectorHolds";
    /** What a controller save calls what it last saw of its Recipe Selector stations ({@code ControllerPersistence}). */
    private static final String SELECTOR_LAST_SEEN_TAG = "SelectorLastSeen";
    /** A second production station, for the test that places one there instead of the diversion port. */
    private static final RackPosition SECOND_STATION = new RackPosition(4, 0, Side.RIGHT);
    /** A stock keeper, for the test that places one there instead of the second logs. */
    private static final RackPosition KEEPER = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);

    /** Slot 0: one log makes four planks. Slot 1: one log makes two sticks (Wareworks checks no recipe here). */
    private static final List<Recipe> LOG_RECIPES = List.of(new Recipe(LOG, 1, PLANK, 4), new Recipe(LOG, 1, STICK, 2));
    /** The chain: one log makes four planks, two planks make four sticks. */
    private static final List<Recipe> CHAIN_RECIPES = List.of(new Recipe(LOG, 1, PLANK, 4),
            new Recipe(PLANK, 2, STICK, 4));

    private static final int LOGS_IN_STOCK = 16;
    private static final int TIMEOUT_TICKS = 1800;
    private static final int LONG_TIMEOUT_TICKS = 4000;
    private static final int SETTLE_TICKS = 20;

    private RecipeSelectorGameTests() {
    }

    // --- the gate --------------------------------------------------------------------------------------------------

    /** Two orders of the same result are admitted together and run through the line in one go (design §5.2). */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorSameResultTogether(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the line runs"));
        UUID[] ids = new UUID[2];
        boolean[] together = new boolean[1];
        helper.onEachTick(() -> {
            if (ids[1] == null)
                return;
            Optional<SelectorGate.Decision<ItemKey>> decision = line.decision();
            together[0] |= decision.isPresent() && decision.get().admitted().containsAll(List.of(ids[0], ids[1]));
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 2L,
                        "both orders' logs are in the station"))
                .thenExecute(() -> {
                    helper.assertTrue(together[0], "the two orders of one result were admitted in the same decision");
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> {
                    line.assertState(ids[0], ProductionOrderState.COMPLETE);
                    line.assertState(ids[1], ProductionOrderState.COMPLETE);
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, PLANK), 8L, "both requests served");
                })
                .thenExecute(() -> helper.assertValueEqual(line.machine.switches, List.of(PLANK),
                        "one switch for both orders"))
                .thenSucceed();
    }

    /**
     * A1, B1, A2 ordered in one tick run in exactly that order (FIFO between results, design §5.2), and on every tick
     * the line holds one result only: the crane is asked for the ingredients of the selection's orders and nothing
     * else, no two results are engaged at once, and the selection changes only with an empty buffer (I2, I3).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorFifoBetweenResults(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the line runs"));
        List<UUID> completed = new ArrayList<>();
        UUID[] ids = new UUID[3];
        boolean[] running = new boolean[1];
        helper.onEachTick(() -> {
            if (!running[0])
                return;
            line.assertOneResultInTheLine();
            line.runMachine(census);
            for (UUID id : ids) {
                if (id != null && !completed.contains(id)
                        && line.order(id).state() == ProductionOrderState.COMPLETE)
                    completed.add(id);
            }
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(STICK, 2);
                    ids[2] = line.order(PLANK, 4);
                    running[0] = true;
                })
                .thenWaitUntil(() -> helper.assertValueEqual(completed.size(), 3, "all three orders completed"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, PLANK), 8L, "the planks were delivered");
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, STICK), 2L, "the sticks were delivered");
                })
                .thenExecute(() -> {
                    running[0] = false;
                    helper.assertValueEqual(completed, List.of(ids[0], ids[1], ids[2]), "A1, then B1, then A2");
                    helper.assertValueEqual(line.machine.switches, List.of(PLANK, STICK, PLANK),
                            "the machine was switched exactly at the result changes");
                })
                .thenSucceed();
    }

    /**
     * An order queued behind another result does not time out however long it waits, and is given a fresh deadline
     * when it is admitted (design §9).
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorQueuedDeadlinePaused(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_TIMEOUT);
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the queue waits"));
        UUID[] ids = new UUID[2];
        long[] created = new long[1];
        boolean[] keepAlive = new boolean[1];
        helper.onEachTick(() -> {
            // The machine is slow but working: it reports progress for the first order, as a Crafter Feeder does.
            if (keepAlive[0] && helper.getTick() % 40 == 0)
                line.controller().onCrafterFeederProgress(ids[0]);
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(STICK, 2);
                    created[0] = helper.getLevel().getGameTime();
                    keepAlive[0] = true;
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the first order's log arrived"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() > created[0] + SHORT_TIMEOUT + 100,
                            "the queue has waited well past the timeout");
                    helper.assertTrue(line.order(ids[1]).isOpen(), "the queued order is still open");
                    helper.assertValueEqual(line.controller().waitsForSelector(line.order(ids[1])),
                            SelectorWait.BEHIND, "it waits for its turn");
                })
                .thenExecute(() -> {
                    keepAlive[0] = false;
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(line.decision().map(d -> d.admitted().contains(ids[1])).orElse(false),
                            "the queued order is admitted once the first is back");
                    helper.assertTrue(line.order(ids[1]).deadlineTick() > created[0] + SHORT_TIMEOUT + 100,
                            "with a fresh deadline, not the one it was created with");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the second order's log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /**
     * A machine that is still busy with something else keeps the line from switching: nothing is fetched, and the
     * order at the head has a running deadline, so it times out cleanly with nothing handed over and no hold
     * (design §5.2, §9).
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorWaitsForEmptySaw(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_TIMEOUT);
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        UUID[] id = new UUID[1];
        Set<SelectorWait> seen = EnumSet.noneOf(SelectorWait.class);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "while the saw is busy");
            helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 0L, "nothing is delivered to a busy saw");
            if (id[0] != null)
                seen.add(line.controller().waitsForSelector(line.order(id[0])));
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.machine.filter = Optional.of(STICK);
                    line.machine.busy = true;
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.TIMED_OUT))
                .thenExecute(() -> {
                    helper.assertTrue(seen.contains(SelectorWait.MACHINE_BUSY), "it waited for the saw: " + seen);
                    helper.assertValueEqual(line.order(id[0]).deliveredIngredients(), 0, "nothing was handed over");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "a clean time-out arms no hold");
                    helper.assertTrue(line.machine.switches.isEmpty(), "the busy saw was never switched");
                    helper.assertValueEqual(line.controller().countOf(LOG), (long) LOGS_IN_STOCK,
                            "every log is still in stock");
                })
                .thenSucceed();
    }

    /**
     * The line switches only with an empty buffer and no crane trip on its way to the station (design I3). The first
     * order of two trips is completed early by a player's own planks in the return input (the limit of design §14),
     * so the next result is due while the first order's log is in the buffer: the sticks wait BUFFER_NOT_EMPTY, and on
     * every tick the selection must not change while the buffer holds anything or a trip is on its way. A trip on its
     * way as the only reason is {@link #selectorWaitsForTheCranesTrip}.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorWaitsForInboundTrip(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, 1, 2, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, 3);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the early completion"));
        UUID[] ids = new UUID[2];
        Set<SelectorWait> seen = EnumSet.noneOf(SelectorWait.class);
        Optional<?>[] lastSelection = {Optional.empty()};
        boolean[] watching = new boolean[1];
        helper.onEachTick(() -> {
            if (!watching[0])
                return;
            WarehouseProductionBlockEntity station = line.station();
            boolean inbound = line.controller().reservations().reservedInbound(line.stationRack()) > 0L;
            if (!station.selection().equals(lastSelection[0]) && lastSelection[0].isPresent())
                helper.assertTrue(station.bufferIsEmpty() && !inbound,
                        "the line switched with an empty buffer and nothing inbound");
            lastSelection[0] = station.selection();
            if (ids[1] != null && line.order(ids[1]).isOpen())
                seen.add(line.controller().waitsForSelector(line.order(ids[1])));
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 8);
                    ids[1] = line.order(STICK, 2);
                    watching[0] = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(line.aisle.stationCount(STATION, LOG) >= 1L,
                        "the first log arrived"))
                .thenExecute(() -> {
                    // A player's own planks complete the first order before its second trip is done.
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(8));
                    ItemCensus.change(census, PLANK, 8);
                })
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.COMPLETE))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(line.station().selection(), Optional.of(PLANK),
                            "a log in the buffer keeps the line on planks");
                    helper.assertTrue(seen.contains(SelectorWait.BUFFER_NOT_EMPTY),
                            "the sticks waited for the log in the buffer: " + seen);
                    Wareworks.LOGGER.info("selectorWaitsForInboundTrip: waits seen {}", seen);
                    // The player empties the station by hand.
                    int taken = extractAll(line.stationHandler(), LOG);
                    ItemCensus.change(census, LOG, -taken);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(STICK),
                        "the line switches once it is drained"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the sticks' log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /**
     * Review fix (tests 8): CRANE_INBOUND from a real crane trip, through the controller's own reservation ledger. A
     * selector registers again while the crane carries the log of an order that has handed nothing over yet, which
     * clears the selection: the order's result is a switch due, the buffer is empty, and only the trip on its way stands
     * between the line and the switch. The order waits CRANE_INBOUND, the selection stays empty while the trip is under
     * way, and once the log is in the line the selection goes back to planks and the order runs.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorWaitsForTheCranesTrip(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the trip"));
        UUID[] id = new UUID[1];
        boolean[] watching = new boolean[1];
        Set<SelectorWait> seen = EnumSet.noneOf(SelectorWait.class);
        helper.onEachTick(() -> {
            if (!watching[0] || !line.order(id[0]).isOpen())
                return;
            if (line.controller().reservations().reservedInbound(line.stationRack()) > 0L)
                helper.assertTrue(line.station().selection().isEmpty(), "no switch while the trip is on its way");
            seen.add(line.controller().waitsForSelector(line.order(id[0])));
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(line.aisle.dock().heldItems().count(LOG) > 0, "the crane carries the log");
                    helper.assertTrue(line.controller().reservations().reservedInbound(line.stationRack()) > 0L,
                            "on its way to the station");
                })
                .thenExecute(() -> {
                    helper.assertFalse(line.order(id[0]).engaged(), "nothing handed over yet");
                    helper.assertTrue(line.station().bufferIsEmpty(), "the buffer is empty");
                    helper.assertValueEqual(line.station().registerSelector(
                            line.aisle.absoluteRackPos(STATION).above(2), Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED,
                            "a selector registers again while the trip is on its way");
                    helper.assertTrue(line.station().selection().isEmpty(), "with nothing engaged it starts afresh");
                    watching[0] = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(seen.contains(SelectorWait.CRANE_INBOUND),
                        "the order waits for the crane's trip: " + seen))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the log arrived"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(PLANK),
                        "the line is set to planks again"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    watching[0] = false;
                    helper.assertValueEqual(line.order(id[0]).produced(), 4L, "made in full");
                })
                .thenSucceed();
    }

    // --- a missing selector ----------------------------------------------------------------------------------------

    /**
     * A station in local mode without its selector fails closed (design I4, §3.3): nothing is fetched, the head times
     * out cleanly, a new selector may register although orders are open, an engaged order waits for the player with a
     * paused deadline, and the selector can be switched off only once nothing is engaged; then the station's orders are
     * ordinary ones again.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorMissingFailsClosed(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_TIMEOUT);
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "without a selector"));
        UUID[] ids = new UUID[3];
        long[] engagedAt = new long[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.selectWithoutMachine();
                    ids[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.controller().waitsForSelector(line.order(ids[0])),
                        SelectorWait.NO_SELECTOR, "the order waits for the missing selector"))
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.TIMED_OUT))
                .thenExecute(() -> {
                    helper.assertValueEqual(line.order(ids[0]).deliveredIngredients(), 0, "nothing was fetched");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "and nothing is held");
                    ids[1] = line.order(PLANK, 4);
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.setSelectorMode(SelectorMode.SLIPS, Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.REFUSED_OTHER_MODE,
                            "the other mode is refused");
                    helper.assertValueEqual(station.registerSelector(line.aisle.absoluteRackPos(STATION).above(2),
                            Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED,
                            "a new selector registers although an order is open: the old link is dead");
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.LOCAL, "still local");
                    // The stand-in for the new selector's machine.
                    station.installTestSelectorArm(line.machine);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "with a working selector the log is fetched"))
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.REFUSED_ENGAGED,
                            "switching off is refused while an order is engaged");
                    // The selector goes missing again; the engaged order waits for the player.
                    station.installTestSelectorArm(null);
                    engagedAt[0] = helper.getLevel().getGameTime();
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() > engagedAt[0] + SHORT_TIMEOUT + 60,
                            "well past the timeout");
                    helper.assertTrue(line.order(ids[1]).isOpen(), "an engaged order stopped by a fault never times out");
                    helper.assertValueEqual(line.controller().waitsForSelector(line.order(ids[1])),
                            SelectorWait.NO_SELECTOR, "it says why");
                })
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.SWITCHED_OFF,
                            "with nothing engaged the selector switches off");
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.NONE, "mode NONE");
                    ids[2] = line.order(PLANK, 4);
                    helper.assertFalse(line.order(ids[2]).selectorBound(), "a new order is an ordinary one");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "and is served without any selector"))
                .thenSucceed();
    }

    // --- cancel, time-out, hold -----------------------------------------------------------------------------------

    /**
     * Cancelling an engaged order trims it to the runs it handed over (design §5.5): the crane fetches nothing more, the
     * request is given back the rest, the delivered run comes back and completes it, and the next switch is clean.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorCancelEngagedTrims(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, 1, 2, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, 3);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the trim"));
        UUID[] ids = new UUID[2];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 12);
                })
                .thenWaitUntil(() -> helper.assertTrue(line.order(ids[0]).deliveredIngredients() >= 1,
                        "the first log was handed over"))
                .thenExecute(() -> {
                    int delivered = line.order(ids[0]).deliveredIngredients();
                    helper.assertTrue(delivered < 3, "the cancel comes mid-order: " + delivered);
                    Optional<ProductionOrder<ItemKey, RackPosition>> trimmed =
                            line.controller().cancelProductionOrder(ids[0]);
                    helper.assertTrue(trimmed.isPresent() && trimmed.get().isOpen(), "the engaged order is trimmed");
                    helper.assertValueEqual(trimmed.get().resultAmount(), 4 * delivered, "to its whole runs");
                    helper.assertValueEqual(trimmed.get().outstandingIngredients(), 0, "it fetches nothing more");
                })
                .thenExecuteAfter(SETTLE_TICKS * 5, () -> {
                    ProductionOrder<ItemKey, RackPosition> order = line.order(ids[0]);
                    helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), (long) order.deliveredIngredients(),
                            "no further log reached the station");
                    helper.assertTrue(line.controller().supplyNeeds().isEmpty(), "and nothing is asked for");
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "a trimmed order that came back holds nothing");
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the next result is fetched after a clean switch"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenExecute(() -> helper.assertValueEqual(line.machine.switches, List.of(PLANK, STICK),
                        "one switch each"))
                .thenSucceed();
    }

    /** Cancelling a selector order that has handed nothing over ends it, as any order (design §5.5). */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorCancelBeforeDeliveryCancels(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, false);
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    Optional<ProductionOrder<ItemKey, RackPosition>> cancelled =
                            line.controller().cancelProductionOrder(id[0]);
                    helper.assertTrue(cancelled.isPresent(), "cancelled");
                    helper.assertValueEqual(cancelled.get().state(), ProductionOrderState.CANCELLED,
                            "an order that handed nothing over ends");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "and holds nothing");
                    helper.assertValueEqual(line.controller().openRequestCount(), 0, "its request was given back");
                })
                .thenSucceed();
    }

    /**
     * An engaged order that times out arms the hold (I5), and the hold stops the whole station: the queued order of
     * another result waits HOLD with a paused deadline through several timeouts. The release is refused while the
     * station still holds the lost log, then keeps the selection, and the queue goes on.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorTimeoutHoldsAndPausesQueue(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_TIMEOUT);
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the hold"));
        UUID[] ids = new UUID[2];
        long[] heldAt = new long[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.TIMED_OUT))
                .thenExecute(() -> {
                    Optional<SelectorHold> hold = line.station().selectorHold();
                    helper.assertTrue(hold.isPresent(), "the lost run arms the hold");
                    helper.assertValueEqual(hold.get().cause(), SelectorHold.Cause.LOST_INGREDIENTS, "its cause");
                    helper.assertValueEqual(hold.get().orderId(), Optional.of(ids[0]), "the order it names");
                    heldAt[0] = helper.getLevel().getGameTime();
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() > heldAt[0] + 3 * SHORT_TIMEOUT,
                            "three timeouts later");
                    helper.assertTrue(line.order(ids[1]).isOpen(), "the queued order survived them");
                    helper.assertValueEqual(line.controller().waitsForSelector(line.order(ids[1])), SelectorWait.HOLD,
                            "it waits for the hold");
                    helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "nothing more was fetched");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity.SelectorRelease refused =
                            line.controller().releaseSelectorHold(line.station());
                    helper.assertValueEqual(refused.outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.REFUSED_BUFFER,
                            "the station still holds the log");
                    helper.assertValueEqual(refused.item(), Optional.of(LOG), "and names it");
                    int taken = extractAll(line.stationHandler(), LOG);
                    ItemCensus.change(census, LOG, -taken);
                    WarehouseControllerBlockEntity.SelectorRelease released =
                            line.controller().releaseSelectorHold(line.station());
                    helper.assertValueEqual(released.outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED, "released");
                    helper.assertValueEqual(line.station().selection(), Optional.of(PLANK),
                            "the selection is kept: leftovers stay planks");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the queue goes on after a clean switch"))
                .thenExecute(() -> {
                    helper.assertValueEqual(line.station().selection(), Optional.of(STICK), "now sticks");
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /** The empty-handed sneak-click on the station releases a hold, as it resumes stopped products (design §8). */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorHoldReleaseBySneakClick(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_TIMEOUT);
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertTrue(line.station().selectorHold().isPresent(), "held"))
                .thenExecute(() -> {
                    // Review fix (conservation 5): switching off would drop the hold unchecked.
                    helper.assertValueEqual(line.station().switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.REFUSED_HOLD,
                            "the selector is not switched off while the station holds");
                    helper.assertValueEqual(line.station().selectorMode(), SelectorMode.LOCAL, "still local");
                    ClickingPlayer player = ClickingPlayer.at(helper, line.aisle.rackPos(STATION).above());
                    InteractionResult refused = sneakClick(helper, player, line.aisle.absoluteRackPos(STATION));
                    helper.assertTrue(refused.consumesAction(), "the click is the station's");
                    helper.assertTrue(player.toldKeys().contains(WareworksLang.key(
                            WareworksLang.SELECTOR_HOLD_REFUSED_BUFFER)), "refused for the log: " + player.toldKeys());
                    helper.assertTrue(line.station().selectorHold().isPresent(), "still held");
                    extractAll(line.stationHandler(), LOG);
                    player.told.clear();
                    sneakClick(helper, player, line.aisle.absoluteRackPos(STATION));
                    helper.assertTrue(player.toldKeys().contains(WareworksLang.key(
                            WareworksLang.SELECTOR_HOLD_RELEASED)), "released: " + player.toldKeys());
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "the hold is gone");
                    helper.assertValueEqual(line.station().switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.SWITCHED_OFF, "resumed, it switches off");
                })
                .thenSucceed();
    }

    /**
     * Re-review fix: a held station that has no controller (it was broken, or the station stands alone) can still be
     * switched off, because no controller is there to release its hold; with a controller it is refused (above).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void selectorHeldStationWithoutControllerSwitchesOff(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState());
        helper.startSequence()
                .thenExecute(() -> {
                    if (!(helper.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station)) {
                        helper.fail("no production station", pos);
                        return;
                    }
                    helper.assertValueEqual(station.setSelectorMode(SelectorMode.LOCAL, Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "local mode");
                    helper.assertTrue(station.armSelectorHold(SelectorHold.lostIngredients(PLANK, UUID.randomUUID(),
                            helper.getLevel().getGameTime(), 1)), "held");
                    helper.assertValueEqual(station.switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.SWITCHED_OFF,
                            "without a controller, switching off is the way out");
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.NONE, "the selector is off");
                    helper.assertTrue(station.selectorHold().isEmpty(), "and the hold is gone with it");
                })
                .thenSucceed();
    }

    // --- the return input --------------------------------------------------------------------------------------------

    /**
     * Only the station's own return input completes its orders (design §3.5, I3): the same planks through another
     * Warehouse Input are stored, and a stock rise counts for nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorCreditsOnlyOwnReturnInput(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the foreign planks"));
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "log arrived"))
                .thenExecute(() -> {
                    int taken = extractAll(line.stationHandler(), LOG);
                    ItemCensus.change(census, LOG, -taken);
                    line.aisle.insertAll(line.aisle.handlerAt(line.aisle.rackPos(OTHER_INPUT)), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(OTHER_INPUT, PLANK), 0L,
                        "the other input's planks were stored"))
                .thenExecuteAfter(SETTLE_TICKS * 2, () -> {
                    ProductionOrder<ItemKey, RackPosition> order = line.order(id[0]);
                    helper.assertTrue(order.isOpen(), "they did not complete the selector order");
                    helper.assertValueEqual(order.produced(), 0L, "not even partly");
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                })
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /**
     * Quarantine (I7, design §3.5): an item the station does not make stays in its return input, is never stored, and
     * holds the station; the release is refused until it is taken out. The station's own product is still stored and
     * still completes its order while the station holds.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorQuarantineHoldsUnexpected(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the quarantine"));
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the line makes planks and its log arrived"))
                .thenExecute(() -> {
                    helper.assertValueEqual(line.station().selection(), Optional.of(PLANK), "the selection");
                    line.aisle.insertAll(line.returnInput(), STICK.toStack(1));
                    ItemCensus.change(census, STICK, 1);
                })
                .thenWaitUntil(() -> {
                    Optional<SelectorHold> hold = line.station().selectorHold();
                    helper.assertTrue(hold.isPresent(), "the stick holds the station");
                    helper.assertValueEqual(hold.get().cause(), SelectorHold.Cause.UNEXPECTED_RETURN, "its cause");
                    helper.assertValueEqual(hold.get().unexpected(), Optional.of(STICK), "and the item");
                })
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecuteAfter(SETTLE_TICKS * 2, () -> {
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 1L, "the stick is still there");
                    helper.assertValueEqual(line.controller().countOf(STICK), 0L, "and was never stored");
                    WarehouseControllerBlockEntity.SelectorRelease refused =
                            line.controller().releaseSelectorHold(line.station());
                    helper.assertValueEqual(refused.outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.REFUSED_RETURN, "refused");
                    helper.assertValueEqual(refused.item(), Optional.of(STICK), "for the stick");
                    // The player takes it out by hand (the input's own handler only takes items in).
                    int taken = line.aisle.inputAt(RETURN).extract(STICK, 64, false).getCount();
                    helper.assertValueEqual(taken, 1, "the stick is taken out");
                    ItemCensus.change(census, STICK, -taken);
                    helper.assertValueEqual(line.controller().releaseSelectorHold(line.station()).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED, "released");
                })
                .thenSucceed();
    }

    /**
     * The player's way out of a quarantine hold (ADR-085 I7, design §8): an empty-hand click on the return input hands
     * over exactly the item the quarantine keeps there, says so, and leaves the station's own products alone; then the
     * hold is released. A click on an input that quarantines nothing does nothing, and neither does a click with an
     * item in the main hand.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorQuarantinedItemTakenOutByHand(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(
                net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos input = line.aisle.rackPos(RETURN);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    helper.useBlock(input, player);
                    helper.assertTrue(player.getInventory().isEmpty(), "nothing is taken from an input without a hold");
                    line.select();
                    line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the line makes planks and its log arrived"))
                .thenExecute(() -> line.aisle.insertAll(line.returnInput(), STICK.toStack(2)))
                .thenWaitUntil(() -> helper.assertTrue(line.station().selectorHold().isPresent(),
                        "the sticks hold the station"))
                .thenExecute(() -> {
                    line.runMachine(census);
                    // Review fix (Create API 4): a click with an item in the main hand is that item's click.
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
                    helper.useBlock(input, player);
                    helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0,
                            "nothing is taken out with an item in the hand");
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 2L, "the sticks stay");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    helper.useBlock(input, player);
                    helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 2, "both sticks taken out");
                    helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 0,
                            "the station's own planks stay in the input");
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 0L, "no stick is left");
                    helper.assertValueEqual(line.controller().releaseSelectorHold(line.station()).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED, "released");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 1): the station's product comes back through its own return input and goes on to a
     * diversion port instead of a rack. It was made all the same, so it completes the selector order: no time-out, no
     * hold for ingredients that were never lost. Nothing counts it as stock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorDivertedProductCompletesItsOrder(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        line.aisle.output(DIVERSION);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the diversion"));
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    helper.assertTrue(line.aisle.outputAt(DIVERSION).setPortRank(DIVERSION_RANK),
                            "the port diverts everything incoming");
                    line.aisle.outputAt(DIVERSION).setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(DIVERSION, PLANK), 4L,
                        "the planks went straight on to the diversion"))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    helper.assertValueEqual(line.order(id[0]).produced(), 4L, "credited with what it made");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "nothing was lost, nothing holds");
                    helper.assertValueEqual(line.aisle.outputAt(DIVERSION).exportedItems(), 4L, "the port counts them");
                    helper.assertValueEqual(line.controller().countOf(PLANK), 0L, "and they are no stock");
                    // Re-review fix: the request the order was made for never sees them, so it is reduced by them
                    // instead of waiting for stock that left through the port.
                    helper.assertTrue(line.controller().requestsFor(line.aisle.absoluteRackPos(OUTPUT)).isEmpty(),
                            "the request waits for nothing that went to the port");
                    helper.assertTrue(line.controller().stockRulePause(PLANK).isEmpty(),
                            "a player's order stops nothing");
                })
                .thenSucceed();
    }

    /**
     * Re-review fix: a stock rule's own order at a selector station whose product goes on to a diversion port completes
     * (the line may switch), but the stock never rose, so the rule is paused instead of ordering again and again into
     * the port (M17, ADR-027).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorDivertedRestockPausesItsRule(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        line.aisle.output(DIVERSION);
        line.aisle.stockKeeper(KEEPER);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the diverted refill"));
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    helper.assertTrue(line.aisle.outputAt(DIVERSION).setPortRank(DIVERSION_RANK),
                            "the port diverts everything incoming");
                    line.aisle.outputAt(DIVERSION).setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    line.select();
                    WarehouseStockKeeperBlockEntity keeper = line.aisle.stockKeeperAt(KEEPER);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 4L);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the rule orders its refill and the log arrives"))
                .thenExecute(() -> {
                    List<ProductionOrder<ItemKey, RackPosition>> open = line.controller().openProductionOrders();
                    helper.assertValueEqual(open.size(), 1, "one refill");
                    helper.assertTrue(open.getFirst().isRestock() && open.getFirst().selectorBound(),
                            "the rule's own order, bound to the line");
                    id[0] = open.getFirst().id();
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(DIVERSION, PLANK), 4L,
                        "the planks went straight on to the diversion"))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenWaitUntil(() -> {
                    Optional<StockRulePause> pause = line.controller().stockRulePause(PLANK);
                    helper.assertTrue(pause.isPresent(), "the rule is paused");
                    helper.assertValueEqual(pause.get().cause(), StockRulePause.Cause.DIVERTED, "for the diversion");
                    helper.assertTrue(pause.get().isRuleBorn(), "a pause its rule could forget");
                })
                .thenExecuteAfter(SETTLE_TICKS * 10, () -> {
                    helper.assertTrue(line.controller().openProductionOrders().isEmpty(),
                            "no second refill is fed into the port");
                    helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 0L, "and no log goes to the line");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "nothing was lost, nothing holds");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 7): only what is in the line may come back. A queued order of another result is no
     * reason to store its product: sticks made by hand out of the planks' log stay in the return input and hold the
     * station, although a stick order waits in the queue.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorQuarantinesAQueuedResult(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the wrong product"));
        UUID[] ids = new UUID[2];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the planks' log arrived"))
                .thenExecute(() -> {
                    helper.assertFalse(line.order(ids[1]).engaged(), "the stick order only waits");
                    // A hand change at the machine: sticks out of the planks' log.
                    int taken = extractAll(line.stationHandler(), LOG);
                    ItemCensus.change(census, LOG, -taken);
                    line.aisle.insertAll(line.returnInput(), STICK.toStack(2));
                    ItemCensus.change(census, STICK, 2);
                })
                .thenWaitUntil(() -> {
                    Optional<SelectorHold> hold = line.station().selectorHold();
                    helper.assertTrue(hold.isPresent(), "the sticks hold the station");
                    helper.assertValueEqual(hold.get().cause(), SelectorHold.Cause.UNEXPECTED_RETURN, "its cause");
                    helper.assertValueEqual(hold.get().unexpected(), Optional.of(STICK), "and the item");
                })
                .thenExecuteAfter(SETTLE_TICKS * 2, () -> {
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 2L, "the sticks are still there");
                    helper.assertValueEqual(line.controller().countOf(STICK), 0L, "and were never stored");
                    helper.assertValueEqual(line.order(ids[1]).produced(), 0L, "the queued order counted nothing");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 8): a selector station that is not loaded and has no open order still guards its return
     * input with what was last seen of it. Its last selection is stored, anything else stays in the input and holds the
     * station once it is seen again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorReturnInputGuardedWhileStationUnloaded(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the unloaded station"));
        UUID[] id = new UUID[1];
        BlockPos station = line.aisle.absoluteRackPos(STATION);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(line.controller().openProductionOrders().isEmpty(), "no order is open");
                    line.controller().hideSelectorStationForTest(station, true);
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                    line.aisle.insertAll(line.returnInput(), STICK.toStack(1));
                    ItemCensus.change(census, STICK, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(RETURN, PLANK), 0L,
                        "the last selection is still stored"))
                .thenExecuteAfter(SETTLE_TICKS * 10, () -> {
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 1L,
                            "the stick stays in the return input of the unloaded station");
                    helper.assertValueEqual(line.controller().countOf(STICK), 0L, "never stored");
                    helper.assertTrue(line.station().selectorHold().isEmpty(), "nothing holds an unloaded station");
                    line.controller().hideSelectorStationForTest(station, false);
                })
                .thenWaitUntil(() -> {
                    Optional<SelectorHold> hold = line.station().selectorHold();
                    helper.assertTrue(hold.isPresent(), "seen again, the stick holds the station");
                    helper.assertValueEqual(hold.get().unexpected(), Optional.of(STICK), "for the stick");
                })
                .thenSucceed();
    }

    /**
     * Re-review fix: the return input remembered for an unloaded selector station stays its own. Another station cannot
     * bind it, and a loaded station bound to it anyway (a world from before the check) decides for itself what its
     * input stores, not the memory of the unloaded one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorRememberedReturnInputStaysReserved(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        line.aisle.production(SECOND_STATION);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the second station"));
        UUID[] id = new UUID[1];
        BlockPos first = line.aisle.absoluteRackPos(STATION);
        BlockPos returnInput = line.aisle.absoluteRackPos(RETURN);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(line.controller().countOf(LOG), (long) LOGS_IN_STOCK, "logs in stock");
                    helper.assertValueEqual(line.controller().productionStations().size(), 2, "both stations");
                    helper.assertValueEqual(line.controller().inputStations().size(), 2, "both inputs");
                })
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(line.controller().openProductionOrders().isEmpty(), "no order is open");
                    line.controller().hideSelectorStationForTest(first, true);
                    WarehouseProductionBlockEntity second = line.aisle.productionAt(SECOND_STATION);
                    helper.assertValueEqual(second.setSelectorMode(SelectorMode.LOCAL, Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "the second station selects");
                    helper.assertValueEqual(line.controller().bindSelectorReturnInput(second, returnInput),
                            WarehouseControllerBlockEntity.ReturnInputBinding.REFUSED_INPUT_TAKEN,
                            "the unloaded station's return input is not free");
                    helper.assertTrue(second.selectorReturnInput().isEmpty(), "so it is not bound");
                    second.setSelectorReturnInput(Optional.of(returnInput));
                    line.controller().onSelectorStationChanged(second.getBlockPos());
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                })
                .thenExecuteAfter(SETTLE_TICKS * 10, () -> {
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, PLANK), 4L,
                            "the loaded station makes no planks, so they stay in its return input");
                    helper.assertValueEqual(line.controller().countOf(PLANK), 0L, "and none of them is stored");
                    Optional<SelectorHold> hold = line.aisle.productionAt(SECOND_STATION).selectorHold();
                    helper.assertTrue(hold.isPresent(), "the planks hold the loaded station");
                    helper.assertValueEqual(hold.get().unexpected(), Optional.of(PLANK), "for the planks");
                })
                .thenSucceed();
    }

    /**
     * Re-review fix (I7): what was last seen of a selector station is saved with the controller, so after a restart
     * its return input stays guarded while the station is still not loaded and has no open order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorReturnInputGuardSurvivesASave(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the restart"));
        UUID[] id = new UUID[1];
        BlockPos station = line.aisle.absoluteRackPos(STATION);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "log arrived"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(line.controller().openProductionOrders().isEmpty(), "no order is open");
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = line.controller();
                    controller.hideSelectorStationForTest(station, true);
                    CompoundTag tag = controller.saveWithFullMetadata(level.registryAccess());
                    helper.assertTrue(tag.contains(SELECTOR_LAST_SEEN_TAG), "what was last seen of it is saved");
                    level.setBlockEntity(loadCopy(helper, controller, tag, WarehouseControllerBlockEntity.class));
                    // The station is still not loaded for the controller that was loaded.
                    line.controller().hideSelectorStationForTest(station, true);
                    helper.assertTrue(line.controller().selectorQuarantines(line.aisle.absoluteRackPos(RETURN),
                            STICK), "the restored controller guards the return input at once");
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                    line.aisle.insertAll(line.returnInput(), STICK.toStack(1));
                    ItemCensus.change(census, STICK, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(RETURN, PLANK), 0L,
                        "the last selection is still stored"))
                .thenExecuteAfter(SETTLE_TICKS * 10, () -> {
                    helper.assertValueEqual(line.aisle.stationCount(RETURN, STICK), 1L,
                            "the stick stays in the return input across the restart");
                    helper.assertValueEqual(line.controller().countOf(STICK), 0L, "never stored");
                    line.controller().hideSelectorStationForTest(station, false);
                })
                .thenWaitUntil(() -> {
                    Optional<SelectorHold> hold = line.station().selectorHold();
                    helper.assertTrue(hold.isPresent(), "seen again, the stick holds the station");
                    helper.assertValueEqual(hold.get().unexpected(), Optional.of(STICK), "for the stick");
                })
                .thenSucceed();
    }

    // --- chains, persistence, prints --------------------------------------------------------------------------------

    /**
     * A chain whose two steps have different results at one station never deadlocks (design §5.2): the step makes its
     * planks first, then the line switches and the root makes the sticks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorChainSameStation(GameTestHelper helper) {
        Line line = new Line(helper, CHAIN_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        boolean[] running = new boolean[1];
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "through the chain");
            if (running[0])
                line.runMachine(census);
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    RequestResult result = line.controller().request(line.aisle.absoluteRackPos(OUTPUT), STICK, 4);
                    helper.assertTrue(result.isAccepted(), "the chain is accepted");
                    helper.assertValueEqual(line.controller().openProductionOrders().size(), 2, "a step and a root");
                    for (ProductionOrder<ItemKey, RackPosition> order : line.controller().openProductionOrders())
                        helper.assertTrue(order.selectorBound(), "both are bound to the line");
                    running[0] = true;
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(OUTPUT, STICK), 4L,
                        "the sticks were made and delivered"))
                .thenExecute(() -> {
                    running[0] = false;
                    helper.assertValueEqual(line.machine.switches, List.of(PLANK, STICK), "the step first");
                    helper.assertValueEqual(line.controller().openProductionOrders().size(), 0, "nothing is open");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 2): a selector registers again while an order's log is in the line and an older head
     * of another result waits for that line (a chain's root, unblocked by its step). The station keeps the selection,
     * the engaged order runs on, and the root follows; before the fix the selection was cleared, the engaged order
     * waited BEHIND the root, the root LINE_BUSY for it, and nothing ever moved or timed out.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorRegisteredAgainMidRunKeepsTheLine(GameTestHelper helper) {
        Line line = new Line(helper, CHAIN_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        boolean[] running = new boolean[1];
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, census, "around the new registration");
            if (running[0])
                line.runMachine(census);
        });
        UUID[] ids = new UUID[3]; // the root, its step, the later planks
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    RequestResult chain = line.controller().request(line.aisle.absoluteRackPos(OUTPUT), STICK, 4);
                    helper.assertTrue(chain.isAccepted(), "the chain is accepted");
                    for (ProductionOrder<ItemKey, RackPosition> order : line.controller().openProductionOrders())
                        ids[order.result().equals(STICK) ? 0 : 1] = order.id();
                    helper.assertTrue(ids[0] != null && ids[1] != null, "a root and a step");
                    ids[2] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 2L,
                        "the step's and the later order's logs are in the line"))
                .thenExecute(() -> {
                    // The machine makes one run: the step's planks.
                    IItemHandler handler = line.stationHandler();
                    int taken = 0;
                    for (int slot = 0; slot < handler.getSlots() && taken == 0; slot++) {
                        if (LOG.matches(handler.getStackInSlot(slot)))
                            taken = handler.extractItem(slot, 1, false).getCount();
                    }
                    helper.assertValueEqual(taken, 1, "one log taken");
                    ItemCensus.change(census, LOG, -1);
                    line.aisle.insertAll(line.returnInput(), PLANK.toStack(4));
                    ItemCensus.change(census, PLANK, 4);
                })
                .thenWaitUntil(() -> {
                    line.assertState(ids[1], ProductionOrderState.COMPLETE);
                    helper.assertValueEqual(line.controller().waitsForSelector(line.order(ids[0])),
                            SelectorWait.LINE_BUSY, "the root waits for the later planks' line");
                })
                .thenExecute(() -> {
                    helper.assertTrue(line.order(ids[2]).engaged(), "the later order's log is in the line");
                    helper.assertValueEqual(line.station().registerSelector(
                            line.aisle.absoluteRackPos(STATION).above(2), Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED,
                            "a selector registers again in the middle of the run");
                    helper.assertValueEqual(line.station().selection(), Optional.of(PLANK),
                            "the selection stays what the line holds");
                })
                .thenWaitUntil(() -> helper.assertTrue(line.decision().map(d -> d.admitted().contains(ids[2]))
                        .orElse(false), "the engaged order runs on"))
                .thenExecute(() -> running[0] = true)
                .thenWaitUntil(() -> {
                    line.assertState(ids[2], ProductionOrderState.COMPLETE);
                    line.assertState(ids[0], ProductionOrderState.COMPLETE);
                })
                .thenExecute(() -> {
                    running[0] = false;
                    helper.assertValueEqual(line.machine.switches, List.of(PLANK, STICK), "no switch in between");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 4): a hold armed while its station was not loaded waits in the controller, survives a
     * save, and stops the station once it is seen again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorPendingHoldSurvivesASave(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        UUID lost = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = line.controller();
                    controller.pendSelectorHoldForTest(line.aisle.absoluteRackPos(STATION),
                            SelectorHold.lostIngredients(PLANK, lost, level.getGameTime(), 3));
                    CompoundTag tag = controller.saveWithFullMetadata(level.registryAccess());
                    helper.assertTrue(tag.contains(SELECTOR_HOLDS_TAG), "the pending hold is saved");
                    level.setBlockEntity(loadCopy(helper, controller, tag, WarehouseControllerBlockEntity.class));
                })
                .thenWaitUntil(() -> helper.assertTrue(line.station().selectorHold().isPresent(),
                        "the restored hold reaches its station"))
                .thenExecute(() -> {
                    SelectorHold hold = line.station().selectorHold().orElseThrow();
                    helper.assertValueEqual(hold.cause(), SelectorHold.Cause.LOST_INGREDIENTS, "its cause");
                    helper.assertValueEqual(hold.orderId(), Optional.of(lost), "the order it names");
                    helper.assertValueEqual(hold.lost(), 3, "and what was lost");
                    CompoundTag after = line.controller().saveWithFullMetadata(helper.getLevel().registryAccess());
                    helper.assertFalse(after.contains(SELECTOR_HOLDS_TAG), "it is no longer pending");
                })
                .thenSucceed();
    }

    /**
     * The station's selector state and the orders' selector fields survive a save (design §5.4, §11): after the load the
     * line is not armed until the first pass has verified the machine, and a machine whose filter is out of step with
     * the selection stops the line until it is idle; then the orders resume. A hold survives a save too.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorReload(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "around the reload"));
        UUID[] ids = new UUID[2];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 8);
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 2L,
                        "the first order's logs arrived"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseControllerBlockEntity controller = line.controller();
                    WarehouseProductionBlockEntity station = line.station();
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    CompoundTag stationTag = station.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, station, stationTag, WarehouseProductionBlockEntity.class));
                    // The machine's filter is out of step after the "crash", and the machine is still busy.
                    line.machine.filter = Optional.of(STICK);
                    line.machine.busy = true;
                    line.station().installTestSelectorArm(line.machine);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.LOCAL, "mode");
                    helper.assertValueEqual(station.selection(), Optional.of(PLANK), "selection");
                    helper.assertValueEqual(station.selectorReturnInput(),
                            Optional.of(line.aisle.absoluteRackPos(RETURN)), "return input");
                    helper.assertFalse(station.selectorArmed(), "not armed while the machine is out of step");
                    ProductionOrder<ItemKey, RackPosition> first = line.order(ids[0]);
                    helper.assertValueEqual(first.selectorRuns(), 2, "the first order's runs");
                    helper.assertValueEqual(first.selectorReturn(), Optional.of(line.returnRack()), "its return input");
                    helper.assertValueEqual(line.order(ids[1]).selectorRuns(), 1, "the second order's runs");
                    helper.assertTrue(line.decision().map(d -> d.admitted().isEmpty()).orElse(false),
                            "nothing is admitted into a machine set to something else");
                    line.machine.busy = false;
                })
                .thenWaitUntil(() -> helper.assertTrue(line.station().selectorArmed(), "armed once the machine is idle"))
                .thenExecute(() -> {
                    helper.assertValueEqual(line.machine.filter, Optional.of(PLANK), "set back to the selection");
                    line.runMachine(census);
                })
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.COMPLETE))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the queued order resumed"))
                .thenExecute(() -> line.runMachine(census))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    WarehouseProductionBlockEntity station = line.station();
                    station.armSelectorHold(SelectorHold.unexpectedReturn(STICK, PLANK, level.getGameTime()));
                    CompoundTag tag = station.saveWithFullMetadata(level.registryAccess());
                    WarehouseProductionBlockEntity copy = loadCopy(helper, station, tag,
                            WarehouseProductionBlockEntity.class);
                    helper.assertValueEqual(copy.selectorHold().map(SelectorHold::cause),
                            Optional.of(SelectorHold.Cause.UNEXPECTED_RETURN), "the hold survives a save");
                    helper.assertValueEqual(copy.selectorHold().flatMap(SelectorHold::unexpected), Optional.of(PLANK),
                            "with its item");
                    helper.assertValueEqual(copy.selectorEpoch(), station.selectorEpoch(), "and the epoch");
                    helper.assertValueEqual(copy.selectorSwitchTime(), station.selectorSwitchTime(), "the switch time");
                    helper.assertValueEqual(line.controller().releaseSelectorHold(station).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED, "released");
                })
                .thenSucceed();
    }

    /**
     * A schematic print carries no selector state (design §3.1, ADR-061): a station printed fresh is in mode NONE, and a
     * print landing on a standing station leaves that station's own state alone.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorPrintedIsUnlinked(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, false);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    WarehouseProductionBlockEntity station = line.station();
                    station.selectorSwitched(PLANK, helper.getLevel().getGameTime());
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag full = station.saveWithFullMetadata(registries);
                    helper.assertTrue(full.contains(WarehouseProductionBlockEntity.RECIPE_SELECTOR_TAG),
                            "a full save writes the selector state");
                    CompoundTag print = full.copy();
                    // What a schematic carries: no buffer, so no full save (ADR-061).
                    print.remove(WarehouseProductionBlockEntity.BUFFER_TAG);
                    WarehouseProductionBlockEntity printed = loadCopy(helper, station, print,
                            WarehouseProductionBlockEntity.class);
                    helper.assertValueEqual(printed.selectorMode(), SelectorMode.NONE, "a printed station is unlinked");
                    helper.assertTrue(printed.selectorReturnInput().isEmpty(), "without a return input");
                    helper.assertTrue(printed.selection().isEmpty(), "and without a selection");
                    station.loadWithComponents(print, registries);
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.LOCAL,
                            "a print on a standing station leaves its mode");
                    helper.assertValueEqual(station.selection(), Optional.of(PLANK), "and its selection");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreSelectorConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** One pattern: {@code inPerRun} of {@code in} make {@code outPerRun} of {@code out}. */
    // --- what the player sees (design §12, §13) ------------------------------------------------------------------

    /**
     * An order queued behind another result says so on both screens and in the goggles, naming the result that goes
     * first; the station screen's state carries the selector, and both survive the wire (stage 5).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorWaitsShownOnScreensAndGoggles(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        UUID[] ids = new UUID[2];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    ids[0] = line.order(PLANK, 4);
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(line.controller().waitsForSelector(line.order(ids[1])).queues(),
                            "the stick order queues behind the planks");
                    helper.assertValueEqual(line.station().selection(), Optional.of(PLANK), "the line makes planks");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = line.controller();
                    BlockPos stationPos = line.aisle.absoluteRackPos(STATION);
                    List<ProductionScreenState.OrderView> views = controller.productionOrderViews(
                            controller.productionOrdersAt(stationPos));
                    ProductionScreenState.OrderView stick = viewOf(helper, views, ids[1]);
                    ProductionScreenState.OrderView plank = viewOf(helper, views, ids[0]);
                    helper.assertTrue(stick.selectorWait().queues(), "the stick row says it queues");
                    helper.assertValueEqual(stick.selectorItem(), Optional.of(PLANK), "and names the planks first");
                    helper.assertFalse(stick.selectorFault(), "a queue is no fault");
                    helper.assertValueEqual(plank.selectorWait(), SelectorWait.NONE, "the planks are admitted");

                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    Vec3 center = Vec3.atCenterOf(stationPos);
                    player.moveTo(center.x, center.y, center.z);
                    ProductionMenu menu = ProductionMenu.create(player.containerMenu.containerId + 1,
                            player.getInventory(), line.station());
                    player.containerMenu = menu;
                    ProductionScreenState state = menu.screenState();
                    helper.assertValueEqual(state.selector().mode(), SelectorMode.LOCAL, "the screen shows local mode");
                    helper.assertValueEqual(state.selector().selection(), Optional.of(PLANK),
                            "and what the line is set to");
                    helper.assertValueEqual(state.selector().returnInput(),
                            Optional.of(line.aisle.absoluteRackPos(RETURN)), "and the return input");
                    helper.assertTrue(state.orders().stream().anyMatch(view -> view.id().equals(ids[1])
                            && view.selectorWait().queues()), "the station's own row says the stick order queues");
                    helper.assertValueEqual(screenRoundTrip(helper, state), state, "the screen state survives the wire");

                    line.station().onGoggleObserved();
                    ProductionGoggleSummary summary = line.station().productionSummary();
                    ProductionGoggleSummary.SelectorSummary selector = summary.selector();
                    helper.assertValueEqual(selector.mode(), SelectorMode.LOCAL, "the goggles show local mode");
                    helper.assertValueEqual(selector.selection(),
                            Optional.of(BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS)), "and the planks");
                    helper.assertValueEqual(selector.waiting(), 1, "one order waits for its turn");
                    helper.assertTrue(selector.headWait().queues(), "the oldest waiting order queues");
                    helper.assertValueEqual(selector.headItem(),
                            Optional.of(BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS)), "behind the planks");
                    CompoundTag tag = new CompoundTag();
                    summary.write(tag);
                    helper.assertValueEqual(ProductionGoggleSummary.read(tag), summary, "the goggle data survives");
                    CompoundTag none = new CompoundTag();
                    ProductionGoggleSummary.NONE.write(none);
                    helper.assertFalse(none.contains("Selector"), "a station without a selector pays nothing");
                })
                .thenSucceed();
    }

    /**
     * The screen's red mark switches the selector off (design §3.3, §13): refused while an order's ingredients are in
     * the line, accepted once nothing is engaged, and the screen then shows no selector at all.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorSwitchOffThroughScreen(GameTestHelper helper) {
        Line line = new Line(helper, LOG_RECIPES, LOGS_IN_STOCK, 0, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        UUID[] id = new UUID[1];
        Player[] player = new Player[1];
        ProductionMenu[] menu = new ProductionMenu[1];
        boolean[] running = new boolean[1];
        helper.onEachTick(() -> {
            if (running[0])
                line.runMachine(census);
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.select();
                    id[0] = line.order(PLANK, 4);
                    player[0] = helper.makeMockPlayer(GameType.SURVIVAL);
                    Vec3 center = Vec3.atCenterOf(line.aisle.absoluteRackPos(STATION));
                    player[0].moveTo(center.x, center.y, center.z);
                    menu[0] = ProductionMenu.create(player[0].containerMenu.containerId + 1,
                            player[0].getInventory(), line.station());
                    player[0].containerMenu = menu[0];
                })
                .thenWaitUntil(() -> helper.assertTrue(line.order(id[0]).engaged(), "the planks' log is in the line"))
                .thenExecute(() -> {
                    helper.assertTrue(menu[0].screenState().selector().engaged(), "the screen knows it is engaged");
                    helper.assertValueEqual(ProductionMenu.submitSelector(player[0], menu[0].containerId,
                            ProductionSelectorPayload.Action.SWITCH_OFF), Optional.of(false),
                            "switching off is refused while an order is in the line");
                    helper.assertValueEqual(line.station().selectorMode(), SelectorMode.LOCAL, "still local");
                    helper.assertTrue(ProductionMenu.submitSelector(player[0], menu[0].containerId + 7,
                            ProductionSelectorPayload.Action.SWITCH_OFF).isEmpty(), "another menu reaches nothing");
                    running[0] = true;
                })
                .thenWaitUntil(() -> line.assertState(id[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    running[0] = false;
                    helper.assertValueEqual(ProductionMenu.submitSelector(player[0], menu[0].containerId,
                            ProductionSelectorPayload.Action.SWITCH_OFF), Optional.of(true),
                            "switched off once nothing is engaged");
                    helper.assertValueEqual(line.station().selectorMode(), SelectorMode.NONE, "the station selects no more");
                    helper.assertValueEqual(menu[0].screenState().selector(), ProductionScreenState.SelectorView.NONE,
                            "and its screen shows no selector");
                    ItemCensus.assertEquals(helper, census, "no item was lost");
                })
                .thenSucceed();
    }

    private static ProductionScreenState.OrderView viewOf(GameTestHelper helper,
            List<ProductionScreenState.OrderView> views, UUID id) {
        for (ProductionScreenState.OrderView view : views) {
            if (view.id().equals(id))
                return view;
        }
        helper.fail("no screen row for order " + id);
        throw new IllegalStateException("unreachable");
    }

    private static ProductionScreenState screenRoundTrip(GameTestHelper helper, ProductionScreenState state) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionScreenPayload.STREAM_CODEC.encode(buffer, new ProductionScreenPayload(1, state));
        ProductionScreenPayload decoded = ProductionScreenPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        return decoded.state();
    }

    private record Recipe(ItemKey in, int inPerRun, ItemKey out, int outPerRun) {
    }

    /**
     * The stand-in for a Recipe Selector and its machine: one filter, set when the station asks and the machine is not
     * busy, and every switch recorded.
     */
    private static final class TestMachine implements WarehouseProductionBlockEntity.SelectorArm {
        Optional<ItemKey> filter = Optional.empty();
        boolean busy;
        final List<ItemKey> switches = new ArrayList<>();

        @Override
        public Optional<SelectorWait> arm(ItemKey target, boolean switching) {
            if (filter.equals(Optional.of(target)))
                return Optional.empty();
            if (busy)
                return Optional.of(SelectorWait.MACHINE_BUSY);
            filter = Optional.of(target);
            switches.add(target);
            return Optional.empty();
        }
    }

    /** The test aisle with one selector station, its return input and its stand-in machine. */
    private static final class Line {
        final GameTestHelper helper;
        final AisleFixture aisle;
        final TestMachine machine = new TestMachine();
        final List<Recipe> recipes;
        final long logs;

        Line(GameTestHelper helper, List<Recipe> recipes, int logs, int secondLogs, boolean motor) {
            this.helper = helper;
            this.recipes = recipes;
            this.logs = (long) logs + secondLogs;
            aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(motor);
            if (motor)
                aisle.motor().generatedSpeed.setValue(TEST_RPM);
            aisle.storage(LOGS, LOG.toStack(logs));
            if (secondLogs > 0)
                aisle.storage(SECOND_LOGS, LOG.toStack(secondLogs));
            aisle.storage(PRODUCTS);
            aisle.storage(MORE_PRODUCTS);
            aisle.input(RETURN);
            aisle.input(OTHER_INPUT);
            aisle.output(OUTPUT);
            aisle.production(STATION);
            WarehouseProductionBlockEntity station = station();
            for (int slot = 0; slot < recipes.size(); slot++) {
                Recipe recipe = recipes.get(slot);
                helper.assertTrue(station.setPatternEntry(slot, 0, recipe.in(), recipe.inPerRun()), "ingredient");
                helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, recipe.out(),
                        recipe.outPerRun()), "result");
            }
        }

        WarehouseControllerBlockEntity controller() {
            return aisle.controller();
        }

        WarehouseProductionBlockEntity station() {
            return aisle.productionAt(STATION);
        }

        IItemHandler stationHandler() {
            return aisle.handlerAt(aisle.rackPos(STATION));
        }

        IItemHandler returnInput() {
            return aisle.handlerAt(aisle.rackPos(RETURN));
        }

        RackPosition stationRack() {
            return rackOf(STATION);
        }

        RackPosition returnRack() {
            return rackOf(RETURN);
        }

        /** The controller's own rack position for a fixture position (the fixture's are the same labels). */
        private RackPosition rackOf(RackPosition fixture) {
            return fixture;
        }

        void assertReady() {
            helper.assertValueEqual(controller().countOf(LOG), logs, "logs in stock");
            helper.assertValueEqual(controller().productionStations().size(), 1, "the station is recorded");
            helper.assertValueEqual(controller().inputStations().size(), 2, "both inputs are recorded");
        }

        /** Local mode with the stand-in machine and the return input bound. */
        void select() {
            selectWithoutMachine();
            station().installTestSelectorArm(machine);
        }

        void selectWithoutMachine() {
            WarehouseProductionBlockEntity station = station();
            // No machine kind: the stand-in machine makes whatever its pattern says, so no recipe check applies
            // (design §10 checks only a known kind).
            helper.assertValueEqual(station.setSelectorMode(SelectorMode.LOCAL, Optional.empty()),
                    WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "local mode");
            helper.assertValueEqual(controller().bindSelectorReturnInput(station, aisle.absoluteRackPos(RETURN)),
                    WarehouseControllerBlockEntity.ReturnInputBinding.BOUND, "the return input");
        }

        /** Requests {@code amount} of {@code key} at the output, all of it made; returns the new order's id. */
        UUID order(ItemKey key, int amount) {
            Set<UUID> before = new LinkedHashSet<>();
            for (ProductionOrder<ItemKey, RackPosition> order : controller().productionOrders())
                before.add(order.id());
            RequestResult result = controller().request(aisle.absoluteRackPos(OUTPUT), key, amount);
            helper.assertTrue(result.isAccepted(), "the request is accepted");
            helper.assertValueEqual(result.producing(), amount, "all of it is made");
            UUID created = null;
            for (ProductionOrder<ItemKey, RackPosition> order : controller().productionOrders()) {
                if (!before.contains(order.id()))
                    created = order.id();
            }
            if (created == null) {
                helper.fail("no production order was created for " + key);
                throw new IllegalStateException("unreachable");
            }
            helper.assertValueEqual(order(created).selectorBound(), station().selectorMode().selects(),
                    "an order at a selector station is bound to its line, and only then");
            return created;
        }

        ProductionOrder<ItemKey, RackPosition> order(UUID id) {
            Optional<ProductionOrder<ItemKey, RackPosition>> order = controller().productionOrder(id);
            if (order.isEmpty()) {
                helper.fail("order " + id + " is gone");
                throw new IllegalStateException("unreachable");
            }
            return order.get();
        }

        void assertState(UUID id, ProductionOrderState state) {
            helper.assertValueEqual(order(id).state(), state, "order state");
        }

        Optional<SelectorGate.Decision<ItemKey>> decision() {
            return controller().selectorDecision(aisle.absoluteRackPos(STATION));
        }

        /**
         * The machine: takes every ingredient of the recipe its filter names out of the station and puts the product
         * into the return input. Changes the census by exactly that.
         */
        void runMachine(Map<ItemKey, Long> census) {
            if (machine.filter.isEmpty())
                return;
            Recipe recipe = null;
            for (Recipe candidate : recipes) {
                if (candidate.out().equals(machine.filter.get()))
                    recipe = candidate;
            }
            if (recipe == null)
                return;
            int taken = extractAll(stationHandler(), recipe.in());
            if (taken == 0)
                return;
            helper.assertValueEqual(taken % recipe.inPerRun(), 0, "whole runs only in the line");
            int made = taken / recipe.inPerRun() * recipe.outPerRun();
            ItemCensus.change(census, recipe.in(), -taken);
            aisle.insertAll(returnInput(), recipe.out().toStack(made));
            ItemCensus.change(census, recipe.out(), made);
        }

        /**
         * I2 on one tick: the crane is asked only for ingredients of orders of the station's selection, no two results
         * are engaged at once, and the selection never changes while the buffer holds anything.
         */
        void assertOneResultInTheLine() {
            WarehouseProductionBlockEntity station = station();
            Map<UUID, ProductionOrder<ItemKey, RackPosition>> byLine = new HashMap<>();
            Set<ItemKey> engaged = new LinkedHashSet<>();
            for (ProductionOrder<ItemKey, RackPosition> order : controller().openProductionOrders()) {
                for (SupplyLine<ItemKey> line : order.lines())
                    byLine.put(line.id(), order);
                if (order.engaged())
                    engaged.add(order.result());
            }
            helper.assertTrue(engaged.size() <= 1, "one result engaged at a time: " + engaged);
            for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : controller().supplyNeeds()) {
                ProductionOrder<ItemKey, RackPosition> order = byLine.get(need.id());
                helper.assertTrue(order != null && station.selection().equals(Optional.of(order.result())),
                        "the crane fetches only for the selection " + station.selection());
            }
        }
    }

    private static InteractionResult sneakClick(GameTestHelper helper, ClickingPlayer player, BlockPos absolute) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        BlockHitResult hit = new BlockHitResult(new Vec3(absolute.getX() + 0.5, absolute.getY() + 1.0,
                absolute.getZ() + 0.5), Direction.UP, absolute, false);
        InteractionResult result = player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit);
        player.setShiftKeyDown(false);
        return result;
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
