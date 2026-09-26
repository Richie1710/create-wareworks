package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the warehouse terminal's <b>confirmation</b> ({@code docs/warehouse-system.md} §3.6.6, M15 part 2,
 * issue #3): the question a click gets when it would go below a reserve or have items made past a maximum, and the one
 * property that makes the feature more than decoration — <b>the server decides, and it decides again</b>.
 * <p>
 * Nothing here is about the panel; the panel is drawn from what these tests assert the server sends. What is proved is
 * the boundary itself:
 * <ul>
 * <li>{@code terminalconfirmationasksbeforethereserve} — a click that reaches into a reserve is answered with the
 * question and <b>nothing else</b>: no request is queued, no refusal is remembered, and a click that stays above the
 * reserve is carried out without any round trip at all;</li>
 * <li>{@code terminalconfirmationisrecheckednottrusted} — the reserve is raised while the player is looking at the
 * question: their answer buys the cost it was given and not the bigger one, so they are asked a second time;</li>
 * <li>{@code terminalconfirmationskippedbyacontrolclick} — the blanket answer a ctrl-click sends goes straight
 * through, which is the skip the design asks for;</li>
 * <li>{@code terminalconfirmationnamesareservedingredient} — the case a screen could never work out: the item asked
 * for is not reserved at all, but the log a production order would spend for it is;</li>
 * <li>{@code terminalconfirmationasksaboutproducingpastthemaximum} — the other direction, and the proof that taking
 * items <b>out</b> never raises the maximum's question however low the cap is;</li>
 * <li>{@code terminalconfirmationpayloadcannotskipthequestion} — the same through the payload entry point a client
 * really uses, including a client that claims to accept less than the cost.</li>
 * </ul>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}, storage on the left rack plane, terminal, keeper and
 * production station on the right, and deliberately <b>no motor</b>: the crane never moves, so every test sees the
 * stock exactly as it set it up instead of racing a delivery.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalConfirmationGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1200;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);

    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);

    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int DIAMOND_RESERVE = 10;
    /** What a click may take before it reaches the reserve. */
    private static final int DIAMONDS_ABOVE_RESERVE = DIAMONDS_IN_STOCK - DIAMOND_RESERVE;
    /** The raised reserve of {@link #terminalConfirmationIsRecheckedNotTrusted}: twice what was accepted. */
    private static final int RAISED_RESERVE = 20;

    private static final int LOGS_IN_STOCK = 8;
    private static final int PLANKS_PER_LOG = 4;
    /** One run of the pattern, i.e. one log's worth of planks. */
    private static final int ORDERED_PLANKS = PLANKS_PER_LOG;
    /** A cap that leaves room for half a run, so the rest of it is what the player is asked about. */
    private static final int PLANK_MAXIMUM = 2;
    /**
     * What the maximum test asks for: <b>less</b> than a whole run, so the run really leaves a surplus behind. What the
     * terminal asks about is the part that stays in the racks once the request has been served, not the level the racks
     * pass through while the order runs (M15 review fix).
     */
    private static final int ASKED_PLANKS = 1;
    /** Planks of the run nobody asked for, i.e. what would be left in the racks. */
    private static final int PLANK_SURPLUS = PLANKS_PER_LOG - ASKED_PLANKS;

    private TerminalConfirmationGameTests() {
    }

    // --- the reserve ------------------------------------------------------------------------------------------------

    /**
     * The boundary itself. Three clicks on the same item: one below a reserve that does not exist yet, one that stays
     * above the reserve, and one that reaches into it.
     * <p>
     * The last one must leave the warehouse <b>untouched</b>. A question is not a refusal — the station's remembered
     * rejection, which its goggles show, must not learn about it — and it is not an acceptance either: nothing may be
     * queued, because a player who says no would otherwise have already spent the items.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationAsksBeforeTheReserve(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the terminal asks about a reserve"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> {
                    // Without a rule there is nothing to ask about, and a plain click is carried out at once: the
                    // question may never cost a warehouse without stock keepers a single round trip.
                    RequestResult straight = resolved(helper, aisle, DIAMOND, 1, RequestAcknowledgement.NONE);
                    helper.assertTrue(straight.isAccepted(), "an unruled item is requested without a question");
                    helper.assertValueEqual(straight.granted(), 1, "and granted at once");
                })
                .thenExecute(() -> {
                    reserve(aisle, DIAMOND, DIAMOND_RESERVE);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    // One diamond is already promised to the click above, so the reserve is reached one item earlier.
                    int free = DIAMONDS_ABOVE_RESERVE - 1;
                    RequestResult above = resolved(helper, aisle, DIAMOND, free, RequestAcknowledgement.NONE);
                    helper.assertTrue(above.isAccepted(), "a click that stays above the reserve is never asked about");
                    helper.assertValueEqual(above.granted(), free, "and is granted in full");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), (long) DIAMOND_RESERVE,
                            "which leaves exactly the reserve");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    int openRequests = controller.openRequestCount();
                    RequestConfirmation<ItemKey> question = asked(helper, aisle, DIAMOND, DIAMOND_RESERVE,
                            RequestAcknowledgement.NONE);
                    helper.assertValueEqual(question.key(), DIAMOND, "the question is about the clicked item");
                    helper.assertValueEqual(question.fromReserve(), (long) DIAMOND_RESERVE,
                            "and names how much of the reserve the click would take");
                    helper.assertValueEqual(question.reserved(), (long) DIAMOND_RESERVE, "out of how much there is");
                    helper.assertValueEqual(question.amount(), (long) DIAMOND_RESERVE, "for the amount clicked");
                    helper.assertValueEqual(question.pastMaximum(), 0L, "nothing is being stored");
                    helper.assertTrue(question.ingredients().isEmpty(), "and nothing is being made");

                    helper.assertValueEqual(controller.openRequestCount(), openRequests,
                            "asking queues nothing at all");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), (long) DIAMOND_RESERVE,
                            "and promises nothing");
                    helper.assertValueEqual(aisle.terminalAt(TERMINAL_RACK).lastRejection(), Optional.empty(),
                            "a question is not a refusal, so the goggles never show it as one");
                })
                .thenExecute(() -> {
                    // The player says yes, and the very same click is carried out.
                    RequestConfirmation<ItemKey> question = asked(helper, aisle, DIAMOND, DIAMOND_RESERVE,
                            RequestAcknowledgement.NONE);
                    RequestResult accepted = resolved(helper, aisle, DIAMOND, DIAMOND_RESERVE,
                            question.acknowledgement());
                    helper.assertTrue(accepted.isAccepted(), "the confirmed request is made: " + accepted);
                    helper.assertValueEqual(accepted.granted(), DIAMOND_RESERVE, "down to the last reserved item");
                    helper.assertValueEqual(aisle.controller().availableStock(DIAMOND), 0L, "the reserve was spent");
                })
                .thenSucceed();
    }

    /**
     * The answer is measured again, and that is the whole reason it carries numbers instead of a flag. The reserve is
     * <b>raised</b> between the question and the answer — another player at a keeper, a schematic, a command — and the
     * click now costs twice what was accepted. It is asked a second time instead of being carried out, and only the
     * answer to the new question goes through.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationIsRecheckedNotTrusted(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reserve moves under a question"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> reserve(aisle, DIAMOND, DIAMOND_RESERVE))
                .thenExecute(() -> {
                    RequestConfirmation<ItemKey> asked = asked(helper, aisle, DIAMOND, DIAMONDS_IN_STOCK,
                            RequestAcknowledgement.NONE);
                    helper.assertValueEqual(asked.fromReserve(), (long) DIAMOND_RESERVE, "ten are reserved");

                    // The reserve is raised while the player reads the panel.
                    reserve(aisle, DIAMOND, RAISED_RESERVE);

                    RequestConfirmation<ItemKey> again = asked(helper, aisle, DIAMOND, DIAMONDS_IN_STOCK,
                            asked.acknowledgement());
                    helper.assertValueEqual(again.fromReserve(), (long) RAISED_RESERVE,
                            "the old answer does not pay for the new cost, so the question comes back bigger");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and nothing was requested");
                    helper.assertValueEqual(aisle.controller().availableStock(DIAMOND), (long) DIAMONDS_IN_STOCK,
                            "nothing is promised either");

                    RequestResult accepted = resolved(helper, aisle, DIAMOND, DIAMONDS_IN_STOCK,
                            again.acknowledgement());
                    helper.assertTrue(accepted.isAccepted(), "the answer to the new question goes through: " + accepted);
                    helper.assertValueEqual(accepted.granted(), DIAMONDS_IN_STOCK, "with everything the click asked");
                })
                .thenSucceed();
    }

    /**
     * The skip. A ctrl-click says "whatever it costs" before the server has said what it costs, and that is a
     * legitimate thing for a client to say: a reserve never holds items back from the player at the terminal, so the
     * blanket answer unlocks nothing they could not have reached by confirming.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationSkippedByAControlClick(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a ctrl-click takes a reserve"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> reserve(aisle, DIAMOND, DIAMOND_RESERVE))
                .thenExecute(() -> {
                    RequestResult accepted = resolved(helper, aisle, DIAMOND, DIAMONDS_IN_STOCK,
                            RequestAcknowledgement.ANY);
                    helper.assertTrue(accepted.isAccepted(), "a ctrl-click is never asked about: " + accepted);
                    helper.assertValueEqual(accepted.granted(), DIAMONDS_IN_STOCK, "and takes the reserve with it");
                })
                .thenSucceed();
    }

    // --- production: the ingredients and the maximum ----------------------------------------------------------------

    /**
     * The case a screen can never work out for itself, and therefore the reason the whole decision lives on the server:
     * the item the player clicked is governed by no rule at all, but the <b>log</b> a production order would spend to
     * make it is reserved. The terminal names the ingredient and its numbers, starts no order until the player says
     * yes, and spends no log in the meantime.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationNamesAReservedIngredient(GameTestHelper helper) {
        AisleFixture aisle = productionAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an ingredient's reserve is named"));

        helper.startSequence()
                .thenWaitUntil(() -> assertProductionReady(helper, aisle))
                .thenExecute(() -> reserve(aisle, LOG, LOGS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestConfirmation<ItemKey> question = asked(helper, aisle, PLANK, ORDERED_PLANKS,
                            RequestAcknowledgement.NONE);
                    helper.assertValueEqual(question.fromReserve(), 0L, "no rule governs the planks themselves");
                    helper.assertValueEqual(question.pastMaximum(), 0L, "and nothing caps them");
                    helper.assertValueEqual(question.ingredients().size(), 1, "one ingredient is held back");
                    RequestConfirmation.ReservedIngredient<ItemKey> ingredient = question.ingredients().getFirst();
                    helper.assertValueEqual(ingredient.key(), LOG, "the log the order would spend");
                    helper.assertValueEqual(ingredient.fromReserve(), 1L, "one of them, for one run");
                    helper.assertValueEqual(ingredient.reserved(), (long) LOGS_IN_STOCK, "out of the whole reserve");
                    helper.assertValueEqual(question.fromIngredientReserve(), 1L, "which is what has to be accepted");

                    helper.assertValueEqual(controller.productionOrders().size(), 0, "no order was started");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and nothing waits for planks");
                    helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK,
                            "no log is promised to anything");
                })
                .thenExecute(() -> {
                    RequestConfirmation<ItemKey> question = asked(helper, aisle, PLANK, ORDERED_PLANKS,
                            RequestAcknowledgement.NONE);
                    RequestResult accepted = resolved(helper, aisle, PLANK, ORDERED_PLANKS,
                            question.acknowledgement());
                    helper.assertTrue(accepted.isAccepted(), "the confirmed order is placed: " + accepted);
                    helper.assertValueEqual(accepted.producing(), ORDERED_PLANKS, "every plank has to be made");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 1, "by one order");
                })
                .thenSucceed();
    }

    /**
     * The other direction. A cap of {@value #PLANK_MAXIMUM} planks and a request for {@value #ASKED_PLANKS} of an item
     * a run makes {@value #PLANKS_PER_LOG} of: the {@value #PLANK_SURPLUS} nobody asked for really are stored — the
     * warehouse always takes back what it sent out for — so the player who set the cap is the one who says it may be
     * exceeded.
     * <p>
     * A request that takes <b>everything</b> a run makes is not asked about at all: those items are promised to the
     * request and leave again, so the cap is never really exceeded and a question would be about nothing (M15 review
     * fix). The same cap on an item the player is taking out asks nothing either, however low it is: a maximum governs
     * storing and nothing else, and a question that appeared there would teach a player the wrong rule.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationAsksAboutProducingPastTheMaximum(GameTestHelper helper) {
        AisleFixture aisle = productionAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a maximum is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> assertProductionReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) PLANK_MAXIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, LOG, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null, (long) PLANK_MAXIMUM);
                })
                .thenExecute(() -> {
                    // A request for the whole run asks nothing: every plank it makes goes to the player.
                    RequestResult wholeRun = resolved(helper, aisle, PLANK, ORDERED_PLANKS,
                            RequestAcknowledgement.NONE);
                    helper.assertTrue(wholeRun.isAccepted(),
                            "a request that takes everything a run makes is never asked about: " + wholeRun);
                    for (ProductionOrder<ItemKey, RackPosition> open : aisle.controller().openProductionOrders())
                        aisle.controller().cancelProductionOrder(open.id());
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "and that order is out of the way again");
                })
                .thenExecute(() -> {
                    RequestConfirmation<ItemKey> question = asked(helper, aisle, PLANK, ASKED_PLANKS,
                            RequestAcknowledgement.NONE);
                    helper.assertValueEqual(question.pastMaximum(),
                            (long) (PLANK_SURPLUS - PLANK_MAXIMUM), "what the cap has no room for");
                    helper.assertValueEqual(question.maximum(), (long) PLANK_MAXIMUM, "and the cap itself");
                    helper.assertValueEqual(question.amount(), (long) ASKED_PLANKS, "of what was asked for");
                    helper.assertValueEqual(question.made(), (long) PLANKS_PER_LOG, "out of the whole run");
                    helper.assertValueEqual(question.fromReserve(), 0L, "nothing is reserved");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "no order was started");

                    RequestResult accepted = resolved(helper, aisle, PLANK, ASKED_PLANKS,
                            question.acknowledgement());
                    helper.assertTrue(accepted.isAccepted(), "and the confirmed one is: " + accepted);
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1, "by one order");
                })
                .thenExecute(() -> {
                    // The same cap on an item that is being handed out, with everything the order did not promise.
                    int free = LOGS_IN_STOCK - 1;
                    helper.assertValueEqual(aisle.controller().availableStock(LOG), (long) free,
                            "the order promised one log");
                    RequestResult takingOut = resolved(helper, aisle, LOG, free, RequestAcknowledgement.NONE);
                    helper.assertTrue(takingOut.isAccepted(),
                            "a capped item is handed out without a question: " + takingOut);
                    helper.assertValueEqual(takingOut.granted(), free, "all of what is left");
                })
                .thenSucceed();
    }

    // --- the payload path -------------------------------------------------------------------------------------------

    /**
     * The same boundary through the entry point a client really uses ({@code TerminalRequestPayload} →
     * {@link WarehouseTerminalMenu#submitRequest}), including the one thing a crafted payload might try: claiming to
     * accept <b>less</b> than the click costs. The server measures the cost itself, so a smaller number buys nothing —
     * it is asked about again, and still nothing is queued.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalConfirmationPayloadCannotSkipTheQuestion(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a payload asks for a reserve"));

        helper.startSequence()
                .thenWaitUntil(() -> assertReady(helper, aisle))
                .thenExecute(() -> reserve(aisle, DIAMOND, DIAMOND_RESERVE))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);

                    TerminalRequestOutcome plain = submit(helper, player, menu, DIAMONDS_IN_STOCK,
                            RequestAcknowledgement.NONE);
                    helper.assertTrue(plain.isAsking(), "a plain payload is answered with the question");
                    RequestConfirmation<ItemKey> question = plain.question().orElseThrow();
                    helper.assertValueEqual(question.fromReserve(), (long) DIAMOND_RESERVE, "with the real number");

                    // A client that says it accepts one item out of a reserve of ten buys exactly nothing.
                    TerminalRequestOutcome tooLittle = submit(helper, player, menu, DIAMONDS_IN_STOCK,
                            new RequestAcknowledgement(false, 1L, 0L, 0L));
                    helper.assertTrue(tooLittle.isAsking(), "an answer below the cost is no answer");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and nothing was requested");

                    TerminalRequestOutcome confirmed = submit(helper, player, menu, DIAMONDS_IN_STOCK,
                            question.acknowledgement());
                    helper.assertFalse(confirmed.isAsking(), "the real answer is acted on");
                    helper.assertTrue(confirmed.result().orElseThrow().isAccepted(),
                            "and accepted: " + confirmed.result().orElseThrow());
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 1, "as one request");
                })
                .thenSucceed();
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    /** Diamonds in the racks, a terminal and a stock keeper; no motor, so nothing moves on its own. */
    private static AisleFixture stockedAisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.terminal(TERMINAL_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        return aisle;
    }

    /** The same with logs and a production station whose pattern turns one log into {@value #PLANKS_PER_LOG} planks. */
    private static AisleFixture productionAisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        return aisle;
    }

    private static void assertReady(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(2, 0, 1);
        helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "the diamonds are in");
    }

    private static void assertProductionReady(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(2, 0, 1);
        helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station is there");
        helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in stock");
        helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK), "and the aisle can make planks");
    }

    /** The keeper's first row: "{@code key}: keep the last {@code reserve} away from automation". */
    private static void reserve(AisleFixture aisle, ItemKey key, int reserve) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, (long) reserve);
    }

    /** A click at the terminal, by a player standing in front of it. */
    private static TerminalRequestOutcome click(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount,
            RequestAcknowledgement acknowledged) {
        return aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(playerAt(helper, aisle.rackPos(TERMINAL_RACK)), key,
                amount, acknowledged);
    }

    /** The question a click raises; fails the test when the terminal made the request instead of asking. */
    private static RequestConfirmation<ItemKey> asked(GameTestHelper helper, AisleFixture aisle, ItemKey key,
            int amount, RequestAcknowledgement acknowledged) {
        TerminalRequestOutcome outcome = click(helper, aisle, key, amount, acknowledged);
        if (outcome.question().isEmpty()) {
            helper.fail("the terminal made the request instead of asking about it: " + outcome.result().orElseThrow());
            throw new IllegalStateException("unreachable");
        }
        return outcome.question().get();
    }

    /** The answer to a click; fails the test when the terminal asked instead of resolving it. */
    private static RequestResult resolved(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount,
            RequestAcknowledgement acknowledged) {
        TerminalRequestOutcome outcome = click(helper, aisle, key, amount, acknowledged);
        if (outcome.result().isEmpty()) {
            helper.fail("the terminal asked about a request that needed no question: " + outcome.question().get());
            throw new IllegalStateException("unreachable");
        }
        return outcome.result().get();
    }

    /** A request through the payload entry point; fails the test when it was dropped instead of answered. */
    private static TerminalRequestOutcome submit(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            int amount, RequestAcknowledgement acknowledged) {
        Optional<TerminalRequestOutcome> outcome = WarehouseTerminalMenu.submitRequest(player, menu.containerId,
                DIAMOND, amount, acknowledged);
        if (outcome.isEmpty()) {
            helper.fail("the request was dropped instead of answered");
            throw new IllegalStateException("unreachable");
        }
        return outcome.get();
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
