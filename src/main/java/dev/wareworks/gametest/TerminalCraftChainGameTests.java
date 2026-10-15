package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ChainPreview;
import dev.wareworks.core.production.FetchPreview;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the crafting grid's <b>chain offer</b> at the warehouse terminal (issue #24, slice 3,
 * {@code docs/warehouse-system.md} §3.4.5): an ingredient the racks are short of, that a production station of the same
 * aisle has a pattern for, is offered to be made first — "Oak Planks x4 will be made first" — through the existing
 * chain planning (ADR-032), and only the player crafts (ADR-024, ADR-059).
 * <p>
 * What is proved here is the server's half, through the menu entry points a screen uses:
 * <ul>
 * <li>{@code terminalcraftoffersachain} — no planks, logs in the racks and a saw pattern: the Fetch asks, naming what
 * would be made, and creates nothing; the Yes starts the craft order with the production the server measured as its
 * consent, never the client's number, and its portion has a chain made whose root backs the craft line's own request;
 * the planks pass through a rack into the buffer, the surplus of the whole run stays in the racks, and the player
 * crafts;</li>
 * <li>{@code terminalcraftchaindeclinedrequestsnothing} — a chain nobody agreed to is never made: a No to the Fetch's
 * offer requests nothing, a second press only asks again, and when the racks are emptied under a running order whose
 * Yes claimed consent the server did not measure, its portion asks instead of having the planks made, and a No to that
 * parks it;</li>
 * <li>{@code terminalcraftchainrefusalnamestheitem} — no logs: the Fetch is refused with the planner's own reason about
 * the item really missing ("Oak Log is missing"), through the wire too, and nothing is requested or ordered;</li>
 * <li>{@code terminalcraftpausedingredientsaysso} — the safety stop holds the planks: the Fetch is refused
 * {@link PlanRefusal#PAUSED} about them, the grid's cell data says so, and once the player lifts the stop at the saw
 * the offer is back;</li>
 * <li>{@code terminalcraftpreviewchangesnothing} — measuring is no order: the controller's preview and the Fetch's
 * question, asked again and again, leave the controller and the terminal saving exactly the tags they saved before,
 * take no stock, promise nothing and spend nothing a real click afterwards would miss; a measurement keeps its one
 * snapshot, and the next Fetch takes a new one;</li>
 * <li>{@code terminalcraftchainssharelogs} — two short ingredients made from one log budget: the planks' chain and the
 * sticks' chain are never promised the same logs, the Fetch offers the crafts both together can make, and the Yes
 * creates exactly those two chains, which the player's machines then work off up to the craft;</li>
 * <li>{@code terminalcraftgatefetchasksonce} — a fence gate's sticks' cell comes before its planks': with two planks
 * and a log, the sticks are made of the two planks and the planks of the log, the Fetch's question adds up both
 * chains, and after its Yes the order never asks again.</li>
 * </ul>
 * <b>Item conservation</b> is asserted on every tick ({@link ItemCensus}), with the crafting player counted; the
 * expectation changes only where the test is the player's machine or the player crafts. Layout
 * ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails; left rack plane: a chest
 * for the logs at 1, a storage location at 2 (empty, or the planks a test starts with) and, where a test needs one, a
 * warehouse output at 3; right rack plane: the terminal at 1, a warehouse input at 2, the saw station at 3 (one log
 * makes {@value #PLANKS_PER_LOG} planks) and, for the fences and the gate, a crafter station at 4
 * ({@value #PLANKS_PER_CRAFTER_RUN} planks make {@value #STICKS_PER_CRAFTER_RUN} sticks). Where the craft order runs
 * to its end, every tick also checks that it counts an ingredient only once it is in the buffer
 * ({@link #assertCreditedOnArrival}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalCraftChainGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** A chain is a trip to the saw, a store and a delivery to the terminal, so it gets the long budget. */
    private static final int CHAIN_TIMEOUT_TICKS = 3600;
    /** Two chains, one of them two machines deep, worked off up to the craft: about a dozen crane trips. */
    private static final int FENCE_TIMEOUT_TICKS = 6000;
    private static final int TIMEOUT_TICKS = 1200;
    /** Ticks after the question in which nothing may be requested or ordered. */
    private static final int QUIET_TICKS = 40;

    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORE_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition SAW_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(4, 0, Side.RIGHT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey FENCE = ItemKey.of(Items.OAK_FENCE);
    private static final ItemKey GATE = ItemKey.of(Items.OAK_FENCE_GATE);

    /** Two planks one above the other: four sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANK, null, null, PLANK);
    /** Plank, stick, plank in two rows: three oak fences. Planks come first in grid order. */
    private static final CraftGrid<ItemKey> FENCE_GRID = CraftGrid.of(PLANK, STICK, PLANK, PLANK, STICK, PLANK);
    /** Stick, plank, stick in two rows: an oak fence gate. Here the sticks come first in grid order. */
    private static final CraftGrid<ItemKey> GATE_GRID = CraftGrid.of(STICK, PLANK, STICK, STICK, PLANK, STICK);

    private static final int PLANKS_PER_LOG = 4;
    private static final int PLANKS_PER_STICKS = 2;
    private static final int STICKS_PER_CRAFT = 4;
    private static final int LOGS_IN_STOCK = 8;
    /** The crafter's pattern, the vanilla stick recipe: two planks make four sticks. */
    private static final int PLANKS_PER_CRAFTER_RUN = 2;
    private static final int STICKS_PER_CRAFTER_RUN = 4;
    private static final int PLANKS_PER_FENCE_CRAFT = 4;
    private static final int STICKS_PER_FENCE_CRAFT = 2;
    private static final int FENCES_PER_CRAFT = 3;
    private static final int PLANKS_PER_GATE = 2;
    private static final int STICKS_PER_GATE = 4;

    private TerminalCraftChainGameTests() {
    }

    /**
     * <b>The offer, and what a Yes to it does.</b> Sticks x1 need two planks; the racks hold none, but eight logs and a
     * saw that makes four planks of one log. The Fetch is asked about — every craft is possible, but only with
     * production, so the player is asked before anything is made — and the question names the planks with the four
     * the whole run makes. Measuring creates nothing: no request, no production order.
     * <p>
     * The Yes carries that production total and starts the craft order; a Yes that agreed to less is asked again, and
     * one that claims to agree to more — a stale screen, or a forged packet — starts with the four the server measured
     * as the order's consent, never with the client's number. The
     * order's one portion is an ordinary request for the two planks, which the racks cannot serve, so the request has
     * the chain made: one production order at the saw whose root <b>backs the craft line's request</b> and promises it
     * exactly the two planks. The crane has no motor until then, so the chain is seen as it was created. The test plays
     * the saw; the planks are stored like any product and fetched into the terminal's buffer, the order ends
     * {@code DONE} only once both planks are in the buffer, the other two planks of the whole run stay in the racks, and
     * the player crafts the sticks from the buffer. The grid's recipe was never a production order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void terminalCraftOffersAChain(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, LOGS_IN_STOCK);
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while sticks are fetched for with a chain",
                player));
        AtomicBoolean crafted = new AtomicBoolean();
        helper.onEachTick(() -> assertCreditedOnArrival(helper, aisle, crafted, PLANK));
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle, LOGS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    TerminalCraftOutcome asked = fetch(player, menu, 1, 0L, false);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, 1, null, "sticks x1 with no planks");
                    helper.assertTrue(asked.refusal().isEmpty(), "an offer names no refusal");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.possible(), 1, "the one craft is possible");
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.NONE, "nothing binds it");
                    helper.assertValueEqual(question.stockRuns(), 0, "the racks alone hold no plank");
                    helper.assertValueEqual(question.withoutMaking(), 0, "so without making there is nothing");
                    helper.assertValueEqual(question.producing(), (long) PLANKS_PER_LOG,
                            "a Yes agrees to the whole run's four planks");
                    helper.assertValueEqual(question.named(), List.of(new CraftFetchQuestion.Line<>(PLANK,
                            PLANKS_PER_STICKS, 0L, PLANKS_PER_LOG)), "the question names the planks to be made");
                    helper.assertTrue(question.chainRefusal().isEmpty(), "and no chain refusal");
                    assertNothingStarted(helper, aisle, "measuring the offer");
                })
                .thenExecuteFor(QUIET_TICKS, () -> assertNothingStarted(helper, aisle, "an unanswered offer"))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = screen.get();
                    // A Yes that agreed to less than the chain makes is no Yes to it: asked again, nothing started.
                    TerminalCraftOutcome stale = fetch(player, menu, 1, PLANKS_PER_LOG - 1L, true);
                    assertOutcome(helper, stale, TerminalCraftResult.CHANGED, 1, null, "a Yes to fewer made planks");
                    assertNothingStarted(helper, aisle, "a Yes that does not cover the chain");
                    // A Yes may claim to agree to more than is made (a stale screen, or a forged packet): it starts, but
                    // the order's consent is what the server measured, never the client's number.
                    TerminalCraftOutcome yes = fetch(player, menu, 1, PLANKS_PER_LOG * 16L, true);
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, 1, STICK, "the Yes, agreeing to more");
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.wanted(), (long) PLANKS_PER_STICKS, "the order wants two planks");
                    CompoundTag saved = terminal(aisle).saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertValueEqual(saved.getCompound("CraftOrder").getCompound("Order")
                            .getCompound("Budget").getLong("Produced"), (long) PLANKS_PER_LOG,
                            "the order's production consent is the measured whole run, not the client's number");
                })
                // The Yes asks for a pass this tick; it runs in the controller's tick, which in a GameTest comes after
                // this step. The portion's request has the chain made.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "the portion started the chain"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.productionOrders().stream()
                            .noneMatch(order -> order.result().equals(STICK)), "the recipe itself is never ordered");
                    ProductionOrder<ItemKey, RackPosition> root = controller.openProductionOrders().getFirst();
                    RetrievalRequest<ItemKey, BlockPos> line = onlyRequest(helper, aisle);
                    helper.assertValueEqual(line.key(), PLANK, "the craft line asks for planks");
                    helper.assertValueEqual(line.remaining(), PLANKS_PER_STICKS, "exactly the two the craft needs");
                    helper.assertValueEqual(root.result(), PLANK, "the chain's root makes the ingredient");
                    helper.assertValueEqual(root.station(), SAW_RACK, "at the saw");
                    helper.assertFalse(root.isStep(), "it is the root of its plan");
                    helper.assertValueEqual(root.backingRequest(), Optional.of(line.id()),
                            "and it backs the craft line's own request");
                    helper.assertValueEqual(root.promisedToRequest(), (long) PLANKS_PER_STICKS,
                            "promising it exactly the two planks");
                    helper.assertValueEqual(terminal(aisle).craftOrderState().inFlight(), (long) PLANKS_PER_STICKS,
                            "which the craft line waits for");
                    helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK - 1,
                            "and the chain promises one log");
                })
                // The crane only moves from here on, so the chain above was seen exactly as the portion created it.
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), 1L,
                        "the log reached the saw"))
                .thenExecute(() -> playSaw(helper, aisle, expected))
                .thenWaitUntil(() -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.state(), ListOrderState.DONE, "the craft order is done");
                    helper.assertValueEqual(state.delivered(), (long) PLANKS_PER_STICKS, "having counted both planks");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_STICKS,
                            "with the two planks in the buffer");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "the chain is finished");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and the request was served");
                    helper.assertValueEqual(storedPlanks(aisle), (long) (PLANKS_PER_LOG - PLANKS_PER_STICKS),
                            "the surplus of the whole run stays in the racks");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "nothing was stopped");
                    WarehouseTerminalMenu menu = screen.get();
                    helper.assertTrue(player.containerMenu == menu, "the player never left the screen");
                    crafted.set(true);
                    Optional<TerminalCraftOutcome> craft = WarehouseTerminalMenu.submitCraft(player,
                            menu.containerId, TerminalCraftPayload.Action.CRAFT, STICK_GRID, Optional.empty());
                    if (craft.isEmpty()) {
                        helper.fail("the craft was dropped instead of answered");
                        return;
                    }
                    assertOutcome(helper, craft.get(), TerminalCraftResult.CRAFTED, STICKS_PER_CRAFT, STICK,
                            "the player crafts the sticks");
                    ItemCensus.change(expected, PLANK, -PLANKS_PER_STICKS);
                    ItemCensus.change(expected, STICK, STICKS_PER_CRAFT);
                    ItemCensus.assertEquals(helper, expected, "one craft moves exactly one recipe's items", player);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L, "both planks were used");
                })
                .thenSucceed();
    }

    /**
     * <b>A chain nobody agreed to is never made.</b> Sticks x2 need four planks; the racks hold two, so one craft comes
     * from the racks and the other only if the saw makes planks first. The Fetch asks, offering the four planks of the
     * saw's whole run and naming x1 as what the racks give without making anything.
     * <ul>
     * <li>The player says <b>No</b>. The screen drops the question and sends nothing, so what is checked is the
     * server's half: the question created nothing, and for as long as nobody answers nothing is requested, ordered or
     * promised. A second press only asks the same question again, and a Yes that agreed to nothing being made is asked
     * again ({@code CHANGED}): a Fetch that would make something never starts unasked.</li>
     * <li>A Yes for the offer's alternative, x1, which the racks serve, arrives claiming consent to sixteen whole runs
     * of the saw — a stale screen, or a forged packet. It starts at once, because the racks serve x1, but with the
     * production consent the server measured for it: none. In the same tick a warehouse output takes both planks, so
     * the order's first portion finds the racks empty. An ordinary click would have the planks made; the portion's
     * request <b>asks</b> instead, because nobody agreed to production for this order ({@link RequestScope#CRAFT}),
     * whatever the client claimed, and it names the saw's whole run. A No parks the order: still nothing is ordered, no
     * log is promised, and Cancel ends it with nothing to give back.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftChainDeclinedRequestsNothing(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, LOGS_IN_STOCK, PLANKS_PER_STICKS);
        aisle.output(OUTPUT_RACK);
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, LOGS_IN_STOCK, PLANK, PLANKS_PER_STICKS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a chain is declined", player));
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();
        AtomicReference<CraftFetchQuestion<ItemKey>> offer = new AtomicReference<>();
        AtomicReference<UUID> port = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle, LOGS_IN_STOCK, PLANKS_PER_STICKS, 2))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    TerminalCraftOutcome asked = fetch(player, menu, 2, 0L, false);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, 2, null, "sticks x2 with two planks");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.NONE,
                            "both crafts are possible with the planks made");
                    helper.assertValueEqual(question.stockRuns(), 1, "the racks alone serve one");
                    helper.assertValueEqual(question.withoutMaking(), 1, "which is the offer's alternative");
                    helper.assertValueEqual(question.producing(), (long) PLANKS_PER_LOG,
                            "a Yes would agree to the saw's whole run");
                    helper.assertValueEqual(question.named(), List.of(new CraftFetchQuestion.Line<>(PLANK,
                            2L * PLANKS_PER_STICKS, PLANKS_PER_STICKS, PLANKS_PER_LOG)),
                            "the planks: four needed, two in stock, four made first");
                    offer.set(question);
                    assertNothingStarted(helper, aisle, "the offer");
                })
                // No: the screen forgets the question and tells the server nothing.
                .thenExecuteFor(QUIET_TICKS, () -> {
                    assertNothingStarted(helper, aisle, "a declined offer");
                    assertNothingPromised(helper, aisle, LOGS_IN_STOCK, PLANKS_PER_STICKS, "a declined offer");
                })
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = screen.get();
                    TerminalCraftOutcome again = fetch(player, menu, 2, 0L, false);
                    assertOutcome(helper, again, TerminalCraftResult.ASKING, 2, null, "the second press");
                    helper.assertValueEqual(again.question(), Optional.of(offer.get()), "asks the very same question");
                    TerminalCraftOutcome unagreed = fetch(player, menu, 2, 0L, true);
                    assertOutcome(helper, unagreed, TerminalCraftResult.CHANGED, 2, null,
                            "a Yes that agreed to nothing being made");
                    assertNothingStarted(helper, aisle, "pressing again");

                    // The offer's alternative, x1, which the racks serve without making anything — sent as a Yes that
                    // claims consent to far more production than the server measures for it (none): it starts, but the
                    // order's consent is the measured nothing, never the client's number. The portion below proves it.
                    TerminalCraftOutcome fromRacks = fetch(player, menu, 1, 16L * PLANKS_PER_LOG, true);
                    assertOutcome(helper, fromRacks, TerminalCraftResult.STARTED, 1, STICK, "x1 without making");
                    helper.assertValueEqual(terminal(aisle).craftOrderState().wanted(), (long) PLANKS_PER_STICKS,
                            "the order wants the two planks in the racks");
                    // Before the order's first pass, a warehouse output asks for both of them.
                    RequestResult taken = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            PLANKS_PER_STICKS);
                    helper.assertValueEqual(taken.granted(), PLANKS_PER_STICKS, "the output takes both planks");
                    helper.assertValueEqual(taken.producing(), 0, "out of the racks");
                    port.set(taken.request().orElseThrow().id());
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        terminal(aisle).craftOrderState().state() != ListOrderState.RUNNING
                                || !terminalRequests(aisle).isEmpty()
                                || !aisle.controller().productionOrders().isEmpty(),
                        "the order's first portion has run"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.ASKING,
                            "the portion asks instead of having the planks made, whatever the Yes claimed");
                    RequestConfirmation<ItemKey> question = terminal.craftQuestion().orElseThrow();
                    helper.assertValueEqual(question.key(), PLANK, "about the planks");
                    helper.assertValueEqual(question.amount(), (long) PLANKS_PER_STICKS, "the portion's two");
                    helper.assertValueEqual(question.made(), (long) PLANKS_PER_LOG, "made as the saw's whole run");
                    helper.assertTrue(question.required(RequestScope.CRAFT), "which the craft order has to agree to");
                    assertNoProduction(helper, aisle, "a portion's question");

                    WarehouseTerminalMenu menu = screen.get();
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.DECLINE)), TerminalCraftResult.DECLINED, 0, null, "No");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.PARKED,
                            "a no parks the order");
                    helper.assertTrue(terminal.craftQuestion().isEmpty(), "and the question is gone");
                })
                .thenExecuteFor(QUIET_TICKS, () -> {
                    assertNoProduction(helper, aisle, "a parked order");
                    helper.assertValueEqual(terminal(aisle).craftOrderState().state(), ListOrderState.PARKED,
                            "the order stays parked");
                })
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = screen.get();
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.CANCEL)), TerminalCraftResult.CANCELLED, 0, null,
                            "Cancel");
                    assertNothingStarted(helper, aisle, "a cancelled order", 1);
                    List<RetrievalRequest<ItemKey, BlockPos>> open = aisle.controller().openRequests();
                    helper.assertValueEqual(open.size(), 1, "the output's own request is all that is open");
                    helper.assertValueEqual(open.getFirst().id(), port.get(), "untouched by the cancel");
                    helper.assertValueEqual(open.getFirst().remaining(), PLANKS_PER_STICKS, "with both planks");
                })
                .thenSucceed();
    }

    /**
     * <b>A chain that does not hold names the item in the way.</b> The same aisle with no logs at all: the saw has a
     * pattern for planks, but nothing to make them of. The Fetch is refused {@code NOT_ENOUGH} with the planner's own
     * reason, {@link PlanRefusal#MISSING_INGREDIENT}, about the <b>logs</b> — "Oak Log is missing", not "not enough
     * Oak Planks" — and the measured question keeps the planks as what binds and the chain's refusal beside it. The
     * answer crosses the wire with both. Nothing is requested and nothing is ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftChainRefusalNamesTheItem(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, 0);
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a chain is refused", player));

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle, 0))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    TerminalCraftOutcome refused = fetch(player, menu, 1, 0L, false);
                    assertOutcome(helper, refused, TerminalCraftResult.NOT_ENOUGH, 0, LOG,
                            "sticks x1 with neither planks nor logs");
                    helper.assertValueEqual(refused.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "with the planner's own reason");
                    CraftFetchQuestion<ItemKey> question = refused.question().orElseThrow();
                    helper.assertValueEqual(question.about(), Optional.of(PLANK), "the planks are what binds");
                    helper.assertValueEqual(question.chainRefusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "the chain for them does not hold");
                    helper.assertValueEqual(question.chainAbout(), Optional.of(LOG), "for want of logs");
                    helper.assertValueEqual(question.producing(), 0L, "and nothing would be made");
                    TerminalCraftAnswerPayload answer = TerminalCraftAnswerPayload.of(menu.containerId, refused);
                    TerminalCraftAnswerPayload decoded = TerminalCraftGameTests.roundTrip(helper, answer,
                            TerminalCraftAnswerPayload.STREAM_CODEC);
                    helper.assertValueEqual(decoded, answer, "the refusal crosses the wire");
                    helper.assertValueEqual(decoded.question().orElseThrow().chainAbout(), Optional.of(LOG),
                            "with the item the chain's refusal names");
                    assertNothingStarted(helper, aisle, "a refused chain");
                })
                .thenExecuteFor(QUIET_TICKS, () -> assertNothingStarted(helper, aisle, "after a refused chain"))
                .thenSucceed();
    }

    /**
     * <b>A stopped ingredient says so.</b> The safety stop is armed the way a player really arms it: they order planks
     * at the terminal, the crane hands the saw its log, and they cancel the order from the step panel although the saw
     * has already taken it — a lost batch, so the warehouse stops making planks ({@code ORDER_CANCELLED}). The log
     * stays in the saw, where the census still counts it.
     * <p>
     * Sticks x1 then need two planks the racks do not hold and the aisle will not make: the Fetch is refused
     * {@code NOT_ENOUGH} with {@link PlanRefusal#PAUSED} about the planks themselves — "Making Oak Planks is stopped",
     * not "Oak Log is missing" — and the grid's cell data marks the planks as producible but stopped, which is what the
     * cell's tint is drawn from. Nothing is requested. Once the player lifts the stop at the saw (a sneak-click with an
     * empty hand) the same Fetch offers the chain again, so the stop was the only thing in the way.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void terminalCraftPausedIngredientSaysSo(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, LOGS_IN_STOCK);
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the planks are stopped", player));
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();
        AtomicReference<UUID> order = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle, LOGS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    RequestResult clicked = WarehouseTerminalMenu.submitRequest(player, menu.containerId, PLANK,
                            PLANKS_PER_LOG).orElseThrow(() -> new AssertionError("the click was answered"));
                    helper.assertTrue(clicked.isAccepted(), "the planks are ordered: " + clicked);
                    helper.assertValueEqual(clicked.producing(), PLANKS_PER_LOG, "all four of them made at the saw");
                    List<ProductionOrder<ItemKey, RackPosition>> open = aisle.controller().openProductionOrders();
                    helper.assertValueEqual(open.size(), 1, "one order for them");
                    order.set(open.getFirst().id());
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), 1L, "the log reached the saw");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().getFirst().deliveredIngredients(),
                            1, "and the order counts it handed over");
                })
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = screen.get();
                    helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, order.get()),
                            Optional.of(true), "the player gives the order up from the step panel");
                    StockRulePause pause = aisle.controller().stockRulePause(PLANK).orElseThrow(
                            () -> new AssertionError("the lost batch stops the making of planks"));
                    helper.assertValueEqual(pause.cause(), StockRulePause.Cause.ORDER_CANCELLED,
                            "because an order somebody asked for was cancelled with its log in the saw");
                    helper.assertValueEqual(pause.unrecovered(), 1L, "which is what it cost");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openRequestCount(), 0, "the click's request went with it");
                    helper.assertValueEqual(aisle.stationCount(SAW_RACK, LOG), 1L, "the lost log is still in the saw");
                    WarehouseTerminalMenu menu = screen.get();
                    TerminalCraftOutcome refused = fetch(player, menu, 1, 0L, false);
                    assertOutcome(helper, refused, TerminalCraftResult.NOT_ENOUGH, 0, PLANK,
                            "sticks x1 while the planks are stopped");
                    helper.assertValueEqual(refused.refusal(), Optional.of(PlanRefusal.PAUSED),
                            "with the reason that the making of them is stopped");
                    CraftFetchQuestion<ItemKey> question = refused.question().orElseThrow();
                    helper.assertValueEqual(question.about(), Optional.of(PLANK), "the planks are what binds");
                    helper.assertValueEqual(question.chainRefusal(), Optional.of(PlanRefusal.PAUSED),
                            "their chain is stopped");
                    helper.assertValueEqual(question.chainAbout(), Optional.of(PLANK),
                            "at the planks themselves, not at the logs below them");
                    helper.assertValueEqual(question.producing(), 0L, "so nothing would be made");
                    helper.assertValueEqual(menu.craftView().keys(), List.of(new TerminalCraftViewPayload.KeyView(PLANK,
                            0L, true, true)), "the cell data: none in the racks, producible, but stopped");
                    TerminalCraftAnswerPayload answer = TerminalCraftAnswerPayload.of(menu.containerId, refused);
                    helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper, answer,
                            TerminalCraftAnswerPayload.STREAM_CODEC), answer, "the refusal crosses the wire");
                    assertOnlyGivenUp(helper, aisle, order.get(), "a stopped ingredient");
                })
                .thenExecuteFor(QUIET_TICKS, () -> assertOnlyGivenUp(helper, aisle, order.get(),
                        "after a stopped ingredient"))
                .thenExecute(() -> {
                    // The one way back is the player's own: a sneak-click with an empty hand on the saw.
                    Player mechanic = helper.makeMockPlayer(GameType.SURVIVAL);
                    mechanic.setShiftKeyDown(true);
                    helper.useBlock(aisle.rackPos(SAW_RACK), mechanic);
                    helper.assertTrue(aisle.controller().stockRulePause(PLANK).isEmpty(), "the stop is lifted");
                    WarehouseTerminalMenu menu = screen.get();
                    TerminalCraftOutcome offered = fetch(player, menu, 1, 0L, false);
                    assertOutcome(helper, offered, TerminalCraftResult.ASKING, 1, null, "the same Fetch afterwards");
                    CraftFetchQuestion<ItemKey> question = offered.question().orElseThrow();
                    helper.assertValueEqual(question.named(), List.of(new CraftFetchQuestion.Line<>(PLANK,
                            PLANKS_PER_STICKS, 0L, PLANKS_PER_LOG)), "offers the planks to be made again");
                    helper.assertValueEqual(menu.craftView().keys(), List.of(new TerminalCraftViewPayload.KeyView(PLANK,
                            0L, true, false)), "and the cell is no longer marked stopped");
                    assertOnlyGivenUp(helper, aisle, order.get(), "the offer after the stop");
                })
                .thenSucceed();
    }

    /**
     * <b>Measuring is not ordering.</b> Two planks and eight logs in the racks and the saw's pattern; sticks x4 need
     * eight planks, so the line takes the two in the racks and a chain for the other six would be made of two logs. The
     * controller's preview is asked directly, over and over and with every kind of answer — a chain that holds, one
     * whose logs an earlier line takes, one clamped to the logs there are — and the Fetch's question three times, as a
     * player pressing Fetch repeatedly would, and once for twenty sticks, whose chain the logs bound.
     * <p>
     * What is checked is that none of it is anything but a measurement: every preview of the same lines gives the same
     * answer, so no preview spent any of the budget the next one plans with; the controller and the terminal save
     * <b>tag for tag</b> what they saved before; no stock is taken, no log or plank promised, no reservation made, no
     * request, production order or craft order created, and the crane, which has its motor here, stays idle. And the
     * warehouse never noticed: a real click afterwards is planned against all eight logs.
     * <p>
     * And a snapshot lives exactly as long as its measurement. The measurement's {@code Chains} answers every preview
     * from the snapshot its first one took, so after the click — which claims the two planks and promises two logs —
     * it still plans with all eight logs; a new preview, and the next Fetch for twenty sticks, see the six that are
     * left. A snapshot kept from one Fetch to the next would offer the larger fetch again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftPreviewChangesNothing(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, LOGS_IN_STOCK, PLANKS_PER_STICKS);
        aisle.placeMotor();
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, LOGS_IN_STOCK, PLANK, PLANKS_PER_STICKS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the chains are previewed", player));
        int sticks = 4;
        long needed = (long) sticks * PLANKS_PER_STICKS;
        long missing = needed - PLANKS_PER_STICKS;
        // Two whole runs of the saw make the six planks the racks are short of.
        long made = 2L * PLANKS_PER_LOG;

        // All eight logs are four times eight planks: a line asking for more than that has its chain clamped to them.
        long clamped = (long) LOGS_IN_STOCK * PLANKS_PER_LOG;
        FetchPreview<ItemKey> allLogs = new FetchPreview<>(Map.of(PLANK, (long) PLANKS_PER_STICKS),
                Map.of(PLANK, ChainPreview.accepted(clamped, clamped)));
        // Twenty sticks need forty planks: two from the racks and at most 32 made, so the logs bound the fetch.
        int bounded = 20;
        AtomicReference<CraftFetchQuestion.Chains<ItemKey>> held = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertChainReady(helper, aisle, LOGS_IN_STOCK, PLANKS_PER_STICKS, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag controllerBefore = controller.saveWithFullMetadata(registries);
                    CompoundTag terminalBefore = terminal.saveWithFullMetadata(registries);
                    FetchPreview<ItemKey> chain = new FetchPreview<>(Map.of(PLANK, (long) PLANKS_PER_STICKS),
                            Map.of(PLANK, ChainPreview.accepted(missing, made)));

                    // The controller's preview, asked directly with the line of the four sticks' eight planks.
                    FetchPreview<ItemKey> first = controller.previewProduction(lines(PLANK, needed),
                            StockAccess.PLAYER);
                    helper.assertValueEqual(first, chain,
                            "the line takes the two planks in the racks; a chain of two logs makes eight for the six");
                    helper.assertValueEqual(controller.previewProduction(lines(PLANK, needed), StockAccess.PLAYER),
                            first, "asked again, the same answer: the first preview spent nothing");
                    helper.assertValueEqual(controller.previewProduction(lines(LOG, LOGS_IN_STOCK, PLANK, needed),
                            StockAccess.PLAYER), new FetchPreview<>(Map.of(LOG, (long) LOGS_IN_STOCK, PLANK,
                                    (long) PLANKS_PER_STICKS),
                                    Map.of(PLANK, ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, LOG))),
                            "a line before it that takes every log leaves its chain none");
                    helper.assertValueEqual(controller.previewProduction(lines(PLANK, 10L * clamped),
                            StockAccess.PLAYER), allLogs,
                            "and a chain for more than the logs pay for is clamped to all of them");
                    helper.assertValueEqual(controller.previewProduction(lines(PLANK, needed), StockAccess.PLAYER),
                            first, "after all of that, still the first answer");
                    // One measurement's previews: answered from the one snapshot its first preview took.
                    CraftFetchQuestion.Chains<ItemKey> previews = controller.productionPreviews(StockAccess.PLAYER);
                    helper.assertValueEqual(previews.preview(lines(PLANK, needed)), first,
                            "a measurement's first preview");
                    helper.assertValueEqual(previews.preview(lines(PLANK, 10L * clamped)), allLogs,
                            "and its next one");
                    held.set(previews);

                    // The Fetch's question, three times.
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    TerminalCraftOutcome asked = fetch(player, menu, sticks, 0L, false);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, sticks, null, "sticks x4");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.producing(), made, "a Yes would agree to eight made planks");
                    helper.assertValueEqual(question.named(), List.of(new CraftFetchQuestion.Line<>(PLANK, needed,
                            PLANKS_PER_STICKS, made)), "eight needed, two in stock, eight made first");
                    helper.assertValueEqual(question.withoutMaking(), 1, "one craft without making anything");
                    for (int i = 0; i < 2; i++)
                        helper.assertValueEqual(fetch(player, menu, sticks, 0L, false), asked,
                                "pressing Fetch again asks the same");
                    // And one the logs bound: two planks from the racks and all eight logs' 32 are seventeen crafts.
                    TerminalCraftOutcome most = fetch(player, menu, bounded, 0L, false);
                    assertOutcome(helper, most, TerminalCraftResult.ASKING, 17, PLANK, "sticks x20, short of planks");
                    helper.assertTrue(most.refusal().isEmpty(), "a chain clamped to the logs is no refusal");
                    helper.assertValueEqual(most.question().orElseThrow().producing(), clamped,
                            "with every log made into planks");

                    helper.assertValueEqual(controller.saveWithFullMetadata(registries), controllerBefore,
                            "the controller saves exactly what it saved before");
                    helper.assertValueEqual(terminal.saveWithFullMetadata(registries), terminalBefore,
                            "and so does the terminal");
                    assertNothingStarted(helper, aisle, "previewing");
                    assertNothingPromised(helper, aisle, LOGS_IN_STOCK, PLANKS_PER_STICKS, "previewing");
                })
                .thenExecuteFor(QUIET_TICKS, () -> {
                    assertNothingStarted(helper, aisle, "after the previews");
                    assertNothingPromised(helper, aisle, LOGS_IN_STOCK, PLANKS_PER_STICKS, "after the previews");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    // A real click for ten planks takes the two in the racks and has eight made of two logs: planned
                    // against the whole warehouse, as if nothing had been previewed at all.
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    RequestResult clicked = WarehouseTerminalMenu.submitRequest(player, menu.containerId, PLANK,
                            (int) (PLANKS_PER_STICKS + made)).orElseThrow(
                                    () -> new AssertionError("the click was answered"));
                    helper.assertTrue(clicked.isAccepted(), "the click is accepted: " + clicked);
                    helper.assertValueEqual(clicked.producing(), (int) made, "eight of the planks are made");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 1, "by one order");
                    helper.assertValueEqual(controller.availableStock(LOG), LOGS_IN_STOCK - 2L,
                            "which promises its two logs and nothing else");

                    // The measurement's snapshot does not follow the warehouse: it still has the two planks the click
                    // claimed and all eight logs. A new preview takes a snapshot of its own and sees the six left.
                    helper.assertValueEqual(held.get().preview(lines(PLANK, 10L * clamped)), allLogs,
                            "a measurement's snapshot does not follow the warehouse");
                    long left = (LOGS_IN_STOCK - 2L) * PLANKS_PER_LOG;
                    helper.assertValueEqual(controller.previewProduction(lines(PLANK, 10L * clamped),
                            StockAccess.PLAYER), new FetchPreview<>(Map.of(PLANK, 0L),
                                    Map.of(PLANK, ChainPreview.accepted(left, left))),
                            "a new preview sees the claimed planks and the promised logs");
                    // And so does the next Fetch: no snapshot is kept from one Fetch to the next.
                    TerminalCraftOutcome after = fetch(player, menu, bounded, 0L, false);
                    assertOutcome(helper, after, TerminalCraftResult.ASKING, 12, PLANK, "sticks x20 after the click");
                    helper.assertValueEqual(after.question().orElseThrow().producing(), left,
                            "the six logs left make 24 planks");
                })
                .thenSucceed();
    }

    /**
     * <b>Two chains share one log budget.</b> Fences need planks and sticks; the racks hold neither, but two logs, a
     * saw (one log, four planks) and a crafter (two planks, four sticks). Planks come first in the grid, so their
     * chain is planned first, and the sticks' chain — the crafter plus a step at the saw — is planned with what is
     * left.
     * <p>
     * The controller's preview says it directly: for fences x2 the planks' chain takes both logs, and the sticks'
     * chain, which on its own would hold, is refused for want of a log. So the Fetch for x2 offers x1, with four planks
     * and four sticks made — not the x2 two independent previews would have promised — and names why one craft more
     * is not possible: the sticks, whose chain finds no log ("Oak Log is missing"). The Yes for x1 creates exactly
     * those two chains in the order's first pass: both portions are granted without a second question, because the
     * Fetch's Yes already agreed to both runs; each root backs its own line's request; and each log is promised once.
     * Then the test plays both machines, the planks and sticks pass through the racks into the buffer, the order is done
     * only once all of them are there, the surplus of both whole runs stays in the racks, and the player crafts three
     * fences.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = FENCE_TIMEOUT_TICKS)
    public static void terminalCraftChainsShareLogs(GameTestHelper helper) {
        int logs = 2;
        AisleFixture aisle = chainAisle(helper, logs);
        aisle.production(CRAFTER_RACK);
        WarehouseProductionBlockEntity crafter = aisle.productionAt(CRAFTER_RACK);
        helper.assertTrue(crafter.setPatternEntry(0, 0, PLANK, PLANKS_PER_CRAFTER_RUN), "the crafter's ingredient");
        helper.assertTrue(crafter.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, STICK, STICKS_PER_CRAFTER_RUN),
                "and its result");
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, logs);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while two chains share the logs", player));
        // The player's machines, worked whenever the crane has handed them a run.
        helper.onEachTick(() -> playMachines(helper, aisle, expected));
        AtomicBoolean crafted = new AtomicBoolean();
        helper.onEachTick(() -> assertCreditedOnArrival(helper, aisle, crafted, PLANK, STICK));
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionStations().size(), 2, "the saw and the crafter");
                    helper.assertTrue(controller.producibleKeys().containsAll(List.of(PLANK, STICK)),
                            "the aisle knows it can make planks and sticks");
                    helper.assertValueEqual(controller.countOf(LOG), (long) logs, "the logs are in the index");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    long planks = 2L * PLANKS_PER_FENCE_CRAFT;
                    long sticks = 2L * STICKS_PER_FENCE_CRAFT;
                    helper.assertValueEqual(controller.previewProduction(lines(STICK, sticks), StockAccess.PLAYER)
                            .chains(), Map.of(STICK, ChainPreview.accepted(sticks, STICKS_PER_CRAFTER_RUN)),
                            "on its own, the sticks' chain for x2 holds");
                    Map<ItemKey, ChainPreview<ItemKey>> both = controller.previewProduction(
                            lines(PLANK, planks, STICK, sticks), StockAccess.PLAYER).chains();
                    helper.assertValueEqual(both.get(PLANK), ChainPreview.<ItemKey>accepted(planks, planks),
                            "the planks' chain for x2 takes both logs");
                    helper.assertValueEqual(both.get(STICK),
                            ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, LOG),
                            "and leaves the sticks' chain none");
                    Map<ItemKey, ChainPreview<ItemKey>> half = controller.previewProduction(
                            lines(PLANK, PLANKS_PER_FENCE_CRAFT, STICK, STICKS_PER_FENCE_CRAFT), StockAccess.PLAYER)
                            .chains();
                    helper.assertValueEqual(half.get(PLANK), ChainPreview.<ItemKey>accepted(PLANKS_PER_FENCE_CRAFT,
                            PLANKS_PER_LOG), "for x1 the planks' chain takes one log");
                    helper.assertValueEqual(half.get(STICK), ChainPreview.<ItemKey>accepted(STICKS_PER_FENCE_CRAFT,
                            STICKS_PER_CRAFTER_RUN), "and the sticks' chain the other");

                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    TerminalCraftOutcome asked = fetchFences(player, menu, 2, 0L, false);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, 1, LOG, "fences x2");
                    helper.assertValueEqual(asked.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "one craft more is not possible because a log is missing");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.STOCK,
                            "the logs are what binds, not the buffer");
                    helper.assertValueEqual(question.about(), Optional.of(STICK),
                            "the question names the sticks, whose chain finds no log for a second craft");
                    helper.assertValueEqual(question.chainRefusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "with their chain's refusal");
                    helper.assertValueEqual(question.chainAbout(), Optional.of(LOG), "about the logs");
                    helper.assertValueEqual(question.stockRuns(), 0, "the racks alone serve none");
                    helper.assertValueEqual(question.madeRuns(), 1, "the two chains together one");
                    helper.assertValueEqual(question.producing(), (long) (PLANKS_PER_LOG + STICKS_PER_CRAFTER_RUN),
                            "a Yes agrees to both whole runs: four planks and four sticks");
                    helper.assertValueEqual(question.named(), List.of(
                            new CraftFetchQuestion.Line<>(PLANK, planks, 0L, PLANKS_PER_LOG),
                            new CraftFetchQuestion.Line<>(STICK, sticks, 0L, STICKS_PER_CRAFTER_RUN)),
                            "both ingredients are named, with what x1 has made of them");
                    assertNothingStarted(helper, aisle, "the offer");

                    TerminalCraftOutcome yes = fetchFences(player, menu, 1, question.producing(), true);
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, 1, FENCE, "the Yes for x1");
                    helper.assertValueEqual(terminal(aisle).craftOrderState().wanted(),
                            (long) (PLANKS_PER_FENCE_CRAFT + STICKS_PER_FENCE_CRAFT), "four planks and two sticks");
                })
                .thenWaitUntil(() -> helper.assertTrue(terminalRequests(aisle).size() == 2
                        || terminal(aisle).craftOrderState().state() != ListOrderState.RUNNING,
                        "both portions went out"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.state(), ListOrderState.RUNNING,
                            "no portion asked again: the Fetch's Yes agreed to both runs");
                    helper.assertTrue(state.refusal().isEmpty(), "and none was refused");
                    helper.assertValueEqual(state.inFlight(), (long) (PLANKS_PER_FENCE_CRAFT + STICKS_PER_FENCE_CRAFT),
                            "everything the order wants is on its way");
                    List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
                    helper.assertValueEqual(open.size(), 3, "the planks' order, the sticks' order and its step");
                    RetrievalRequest<ItemKey, BlockPos> plankLine = terminalRequest(helper, aisle, PLANK);
                    RetrievalRequest<ItemKey, BlockPos> stickLine = terminalRequest(helper, aisle, STICK);
                    ProductionOrder<ItemKey, RackPosition> plankRoot = root(helper, open, PLANK);
                    ProductionOrder<ItemKey, RackPosition> stickRoot = root(helper, open, STICK);
                    helper.assertValueEqual(plankRoot.station(), SAW_RACK, "the planks are made at the saw");
                    helper.assertValueEqual(plankRoot.backingRequest(), Optional.of(plankLine.id()),
                            "for the planks' line");
                    helper.assertValueEqual(plankRoot.promisedToRequest(), (long) PLANKS_PER_FENCE_CRAFT,
                            "which it promises its four");
                    helper.assertValueEqual(stickRoot.station(), CRAFTER_RACK, "the sticks at the crafter");
                    helper.assertValueEqual(stickRoot.backingRequest(), Optional.of(stickLine.id()),
                            "for the sticks' line");
                    helper.assertValueEqual(stickRoot.promisedToRequest(), (long) STICKS_PER_FENCE_CRAFT,
                            "which it promises its two");
                    List<ProductionOrder<ItemKey, RackPosition>> steps = open.stream().filter(ProductionOrder::isStep)
                            .toList();
                    helper.assertValueEqual(steps.size(), 1, "the sticks' chain has one step");
                    helper.assertValueEqual(steps.getFirst().result(), PLANK, "making its planks");
                    helper.assertValueEqual(steps.getFirst().station(), SAW_RACK, "at the saw");
                    helper.assertValueEqual(controller.availableStock(LOG), 0L, "both logs are promised");
                    helper.assertTrue(controller.productionOrders().stream()
                            .noneMatch(order -> order.result().equals(FENCE)), "the fence itself is never ordered");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.state(), ListOrderState.DONE, "the craft order is done");
                    helper.assertValueEqual(state.delivered(), (long) (PLANKS_PER_FENCE_CRAFT + STICKS_PER_FENCE_CRAFT),
                            "having counted all six ingredients");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_FENCE_CRAFT,
                            "with the four planks in the buffer");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STICK), (long) STICKS_PER_FENCE_CRAFT,
                            "and the two sticks");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "both chains are finished");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and both lines were served");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "nothing was stopped");
                    helper.assertValueEqual(controller.countOf(LOG), 0L, "both logs went to the saw");
                    helper.assertValueEqual(controller.countOf(PLANK),
                            2L * PLANKS_PER_LOG - PLANKS_PER_FENCE_CRAFT - PLANKS_PER_CRAFTER_RUN,
                            "the planks neither line nor crafter needed stay in the racks");
                    helper.assertValueEqual(controller.countOf(STICK),
                            (long) (STICKS_PER_CRAFTER_RUN - STICKS_PER_FENCE_CRAFT), "and so do the spare sticks");
                    WarehouseTerminalMenu menu = screen.get();
                    helper.assertTrue(player.containerMenu == menu, "the player never left the screen");
                    crafted.set(true);
                    Optional<TerminalCraftOutcome> craft = WarehouseTerminalMenu.submitCraft(player,
                            menu.containerId, TerminalCraftPayload.Action.CRAFT, FENCE_GRID, Optional.empty());
                    if (craft.isEmpty()) {
                        helper.fail("the craft was dropped instead of answered");
                        return;
                    }
                    assertOutcome(helper, craft.get(), TerminalCraftResult.CRAFTED, FENCES_PER_CRAFT, FENCE,
                            "the player crafts the fences");
                    ItemCensus.change(expected, PLANK, -PLANKS_PER_FENCE_CRAFT);
                    ItemCensus.change(expected, STICK, -STICKS_PER_FENCE_CRAFT);
                    ItemCensus.change(expected, FENCE, FENCES_PER_CRAFT);
                    ItemCensus.assertEquals(helper, expected, "one craft moves exactly one recipe's items", player);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L, "every plank was used");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STICK), 0L, "and every stick");
                })
                .thenSucceed();
    }

    /**
     * <b>The offer adds up what the portions will make, in the order they are requested.</b> A fence gate is stick,
     * plank, stick in two rows: four sticks and two planks, with the sticks' cell first. The racks hold the two planks
     * and one log; the saw makes four planks of a log, the crafter four sticks of two planks.
     * <p>
     * The craft order requests its lines in that order, so the sticks' portion has its sticks made of the two planks
     * in the racks, and the planks' portion then finds none and has its own made of the log. The Fetch's question
     * names both chains — four sticks and four planks made first — and the Yes agrees to both. On every tick from then
     * on, the order must never ask again: a question measured with the racks' two planks counted for the planks' line
     * would have offered the sticks alone, and the planks' portion would then have asked for production the player had
     * already said yes to (issue #24 review). The test plays both machines, the order is done once all six ingredients
     * are in the buffer, the two planks the gate does not need stay in the racks, and the player crafts the gate.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = FENCE_TIMEOUT_TICKS)
    public static void terminalCraftGateFetchAsksOnce(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, 1, PLANKS_PER_GATE);
        aisle.production(CRAFTER_RACK);
        WarehouseProductionBlockEntity crafter = aisle.productionAt(CRAFTER_RACK);
        helper.assertTrue(crafter.setPatternEntry(0, 0, PLANK, PLANKS_PER_CRAFTER_RUN), "the crafter's ingredient");
        helper.assertTrue(crafter.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, STICK, STICKS_PER_CRAFTER_RUN),
                "and its result");
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, 1, PLANK, PLANKS_PER_GATE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a gate's ingredients are made",
                player));
        helper.onEachTick(() -> playMachines(helper, aisle, expected));
        AtomicBoolean crafted = new AtomicBoolean();
        helper.onEachTick(() -> assertCreditedOnArrival(helper, aisle, crafted, PLANK, STICK));
        // The point of the test: once the Fetch's Yes is given, no portion asks again.
        helper.onEachTick(() -> {
            TerminalCraftOrderState state = terminal(aisle).craftOrderState();
            if (state.active() && state.state() == ListOrderState.ASKING)
                helper.fail("a portion asked again after the Fetch's Yes: " + terminal(aisle).craftQuestion());
        });
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.productionStations().size(), 2, "the saw and the crafter");
                    helper.assertTrue(controller.producibleKeys().containsAll(List.of(PLANK, STICK)),
                            "the aisle knows it can make planks and sticks");
                    helper.assertValueEqual(controller.countOf(LOG), 1L, "the log is in the index");
                    helper.assertValueEqual(controller.countOf(PLANK), (long) PLANKS_PER_GATE, "and the two planks");
                })
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    TerminalCraftOutcome asked = fetch(player, menu, GATE_GRID, 1, 0L, false);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, 1, null, "a fence gate x1");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.NONE,
                            "the gate is possible with both chains");
                    helper.assertValueEqual(question.stockRuns(), 0, "the racks alone hold no stick");
                    helper.assertValueEqual(question.named(), List.of(
                            new CraftFetchQuestion.Line<>(STICK, STICKS_PER_GATE, 0L, STICKS_PER_CRAFTER_RUN),
                            new CraftFetchQuestion.Line<>(PLANK, PLANKS_PER_GATE, PLANKS_PER_GATE, PLANKS_PER_LOG)),
                            "the sticks made of the two planks in the racks, and the planks made of the log");
                    helper.assertValueEqual(question.producing(), (long) (STICKS_PER_CRAFTER_RUN + PLANKS_PER_LOG),
                            "a Yes agrees to both whole runs");
                    assertNothingStarted(helper, aisle, "the offer");

                    TerminalCraftOutcome yes = fetch(player, menu, GATE_GRID, 1, question.producing(), true);
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, 1, GATE, "the Yes");
                    helper.assertValueEqual(terminal(aisle).craftOrderState().wanted(),
                            (long) (STICKS_PER_GATE + PLANKS_PER_GATE), "four sticks and two planks");
                })
                .thenWaitUntil(() -> helper.assertTrue(terminalRequests(aisle).size() == 2
                        || terminal(aisle).craftOrderState().state() != ListOrderState.RUNNING,
                        "both portions went out"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.state(), ListOrderState.RUNNING,
                            "no portion asked again: the Fetch's Yes covered both chains");
                    helper.assertTrue(state.refusal().isEmpty(), "and none was refused");
                    helper.assertValueEqual(state.inFlight(), (long) (STICKS_PER_GATE + PLANKS_PER_GATE),
                            "everything the order wants is on its way");
                    List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
                    helper.assertValueEqual(open.size(), 2, "the sticks' order and the planks' order, no step");
                    RetrievalRequest<ItemKey, BlockPos> stickLine = terminalRequest(helper, aisle, STICK);
                    RetrievalRequest<ItemKey, BlockPos> plankLine = terminalRequest(helper, aisle, PLANK);
                    ProductionOrder<ItemKey, RackPosition> stickRoot = root(helper, open, STICK);
                    ProductionOrder<ItemKey, RackPosition> plankRoot = root(helper, open, PLANK);
                    helper.assertValueEqual(stickRoot.station(), CRAFTER_RACK, "the sticks are made at the crafter");
                    helper.assertValueEqual(stickRoot.backingRequest(), Optional.of(stickLine.id()),
                            "for the sticks' line");
                    helper.assertValueEqual(plankRoot.station(), SAW_RACK, "the planks at the saw");
                    helper.assertValueEqual(plankRoot.backingRequest(), Optional.of(plankLine.id()),
                            "for the planks' line, whose two planks in the racks the crafter's order holds");
                    helper.assertValueEqual(plankRoot.promisedToRequest(), (long) PLANKS_PER_GATE,
                            "which it promises its two");
                    helper.assertValueEqual(controller.availableStock(PLANK), 0L, "both planks are promised");
                    helper.assertValueEqual(controller.availableStock(LOG), 0L, "and so is the log");
                    helper.assertTrue(controller.productionOrders().stream()
                            .noneMatch(order -> order.result().equals(GATE)), "the gate itself is never ordered");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.state(), ListOrderState.DONE, "the craft order is done");
                    helper.assertValueEqual(state.delivered(), (long) (STICKS_PER_GATE + PLANKS_PER_GATE),
                            "having counted all six ingredients");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STICK), (long) STICKS_PER_GATE,
                            "with the four sticks in the buffer");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_GATE,
                            "and the two planks");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "both chains are finished");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "and both lines were served");
                    helper.assertValueEqual(controller.pausedStockRuleCount(), 0, "nothing was stopped");
                    helper.assertValueEqual(controller.countOf(LOG), 0L, "the log went to the saw");
                    helper.assertValueEqual(controller.countOf(PLANK), (long) (PLANKS_PER_LOG - PLANKS_PER_GATE),
                            "the saw's planks the gate does not need stay in the racks");
                    helper.assertValueEqual(controller.countOf(STICK), 0L, "every stick went into the buffer");
                    WarehouseTerminalMenu menu = screen.get();
                    helper.assertTrue(player.containerMenu == menu, "the player never left the screen");
                    crafted.set(true);
                    Optional<TerminalCraftOutcome> craft = WarehouseTerminalMenu.submitCraft(player,
                            menu.containerId, TerminalCraftPayload.Action.CRAFT, GATE_GRID, Optional.empty());
                    if (craft.isEmpty()) {
                        helper.fail("the craft was dropped instead of answered");
                        return;
                    }
                    assertOutcome(helper, craft.get(), TerminalCraftResult.CRAFTED, 1, GATE,
                            "the player crafts the gate");
                    ItemCensus.change(expected, PLANK, -PLANKS_PER_GATE);
                    ItemCensus.change(expected, STICK, -STICKS_PER_GATE);
                    ItemCensus.change(expected, GATE, 1);
                    ItemCensus.assertEquals(helper, expected, "one craft moves exactly one recipe's items", player);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L, "every plank was used");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STICK), 0L, "and every stick");
                })
                .thenSucceed();
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    /**
     * The saw aisle, <b>without</b> a motor so a test sees what a Fetch created before the crane races it:
     * {@code logs} logs in a chest (none: an empty storage location), an empty storage location, the terminal, a
     * warehouse input the saw's planks come back through and the saw station with its one pattern.
     */
    private static AisleFixture chainAisle(GameTestHelper helper, int logs) {
        return chainAisle(helper, logs, 0);
    }

    /** The same aisle with {@code planks} planks in its second storage location (none: an empty one). */
    private static AisleFixture chainAisle(GameTestHelper helper, int logs, int planks) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        if (logs > 0)
            aisle.storage(LOG_RACK, LOG.toStack(logs));
        else
            aisle.storage(LOG_RACK);
        if (planks > 0)
            aisle.storage(STORE_RACK, PLANK.toStack(planks));
        else
            aisle.storage(STORE_RACK);
        aisle.terminal(TERMINAL_RACK);
        aisle.input(INPUT_RACK);
        aisle.production(SAW_RACK);
        WarehouseProductionBlockEntity saw = aisle.productionAt(SAW_RACK);
        helper.assertTrue(saw.setPatternEntry(0, 0, LOG, 1), "the saw's ingredient");
        helper.assertTrue(saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        return aisle;
    }

    /** Waits until the aisle is read, the saw is recorded with its pattern and the logs are in the index. */
    private static void assertChainReady(GameTestHelper helper, AisleFixture aisle, int logs) {
        assertChainReady(helper, aisle, logs, 0, 1);
    }

    /**
     * The same with {@code planks} planks in the index and {@code outputs} delivery stations, the terminal included.
     */
    private static void assertChainReady(GameTestHelper helper, AisleFixture aisle, int logs, int planks,
            int outputs) {
        aisle.assertReady(2, 1, outputs);
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 1, "the saw is recorded");
        helper.assertTrue(controller.producibleKeys().contains(PLANK), "and the aisle knows it can make planks");
        helper.assertValueEqual(controller.countOf(LOG), (long) logs, "the logs are in the index");
        helper.assertValueEqual(controller.countOf(PLANK), (long) planks, "and so are the planks");
    }

    /** Fails unless the aisle has neither a request nor a production order, and the terminal no craft order. */
    private static void assertNothingStarted(GameTestHelper helper, AisleFixture aisle, String context) {
        assertNothingStarted(helper, aisle, context, 0);
    }

    /**
     * The same in an aisle where {@code otherRequests} requests to other stations are open: none of them to the
     * terminal.
     */
    private static void assertNothingStarted(GameTestHelper helper, AisleFixture aisle, String context,
            int otherRequests) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.openRequestCount(), otherRequests, context + " requests nothing");
        helper.assertTrue(terminalRequests(aisle).isEmpty(), context + " requests nothing for the terminal");
        helper.assertTrue(controller.productionOrders().isEmpty(), context + " orders nothing");
        helper.assertFalse(terminal(aisle).hasOpenCraftOrder(), context + " starts no craft order");
    }

    /**
     * {@link #assertNothingStarted} for an aisle that still lists the production order {@code givenUp}, which a player
     * cancelled: a finished order stays visible for a while, so the check is that it is the only order there is and
     * that nothing is open.
     */
    private static void assertOnlyGivenUp(GameTestHelper helper, AisleFixture aisle, UUID givenUp, String context) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.openRequestCount(), 0, context + " requests nothing");
        helper.assertTrue(controller.openProductionOrders().isEmpty(), context + " has no open order");
        helper.assertTrue(controller.productionOrders().stream().allMatch(order -> order.id().equals(givenUp)),
                context + " orders nothing new: " + controller.productionOrders());
        helper.assertFalse(terminal(aisle).hasOpenCraftOrder(), context + " starts no craft order");
    }

    /**
     * Fails unless every one of the {@code logs} logs and {@code planks} planks in the racks is still free for anyone
     * and no crane job holds anything back: nothing was promised or reserved.
     */
    private static void assertNothingPromised(GameTestHelper helper, AisleFixture aisle, int logs, int planks,
            String context) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.availableStock(LOG), (long) logs, context + " promises no log");
        helper.assertValueEqual(controller.availableTo(StockAccess.PLAYER, LOG), (long) logs,
                context + " leaves a player every log");
        helper.assertValueEqual(controller.availableStock(PLANK), (long) planks, context + " promises no plank");
        helper.assertValueEqual(controller.reservedStock(LOG), 0L, context + " reserves no log");
        helper.assertValueEqual(controller.reservedStock(PLANK), 0L, context + " reserves no plank");
        helper.assertTrue(controller.reservations().isEmpty(), context + " reserves nothing at all");
    }

    /**
     * Fails unless no production order exists, nothing is requested for the terminal and no log is promised: whatever
     * the craft order is doing, it has had nothing made.
     */
    private static void assertNoProduction(GameTestHelper helper, AisleFixture aisle, String context) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertTrue(controller.productionOrders().isEmpty(), context + " orders nothing");
        helper.assertTrue(terminalRequests(aisle).isEmpty(), context + " requests nothing for the terminal");
        helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK, context + " promises no log");
    }

    /**
     * The test plays the player's saw: it takes the log out of the station's buffer and hands the four planks back
     * through the warehouse input, exactly as a saw plus funnel would.
     */
    private static void playSaw(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> expected) {
        IItemHandler station = aisle.handlerAt(aisle.rackPos(SAW_RACK));
        int taken = 0;
        for (int slot = 0; slot < station.getSlots(); slot++) {
            if (LOG.matches(station.getStackInSlot(slot)))
                taken += station.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        helper.assertValueEqual(taken, 1, "the saw took its one log");
        ItemCensus.change(expected, LOG, -taken);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), PLANK.toStack(PLANKS_PER_LOG));
        ItemCensus.change(expected, PLANK, PLANKS_PER_LOG);
    }

    /**
     * The test plays both of the player's machines, whenever the crane has handed one of them a whole run: the saw
     * turns every log into {@value #PLANKS_PER_LOG} planks, the crafter every {@value #PLANKS_PER_CRAFTER_RUN} planks
     * into {@value #STICKS_PER_CRAFTER_RUN} sticks, and the products go back through the warehouse input, exactly as a
     * funnel would hand them back. A machine holding less than a run is left alone.
     */
    private static void playMachines(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> expected) {
        IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
        int logs = takeFrom(aisle, SAW_RACK, LOG, Integer.MAX_VALUE);
        if (logs > 0) {
            ItemCensus.change(expected, LOG, -logs);
            aisle.insertAll(input, PLANK.toStack(logs * PLANKS_PER_LOG));
            ItemCensus.change(expected, PLANK, (long) logs * PLANKS_PER_LOG);
        }
        int runs = (int) (countIn(aisle, CRAFTER_RACK, PLANK) / PLANKS_PER_CRAFTER_RUN);
        if (runs > 0) {
            int planks = takeFrom(aisle, CRAFTER_RACK, PLANK, runs * PLANKS_PER_CRAFTER_RUN);
            helper.assertValueEqual(planks, runs * PLANKS_PER_CRAFTER_RUN, "the crafter takes whole runs");
            ItemCensus.change(expected, PLANK, -planks);
            aisle.insertAll(input, STICK.toStack(runs * STICKS_PER_CRAFTER_RUN));
            ItemCensus.change(expected, STICK, (long) runs * STICKS_PER_CRAFTER_RUN);
        }
    }

    /** Takes up to {@code max} items of {@code key} out of the station at {@code rack}, as its machine would. */
    private static int takeFrom(AisleFixture aisle, RackPosition rack, ItemKey key, int max) {
        IItemHandler station = aisle.handlerAt(aisle.rackPos(rack));
        int taken = 0;
        for (int slot = 0; slot < station.getSlots() && taken < max; slot++) {
            if (key.matches(station.getStackInSlot(slot)))
                taken += station.extractItem(slot, max - taken, false).getCount();
        }
        return taken;
    }

    /** Items of {@code key} the station at {@code rack} holds for its machine. */
    private static long countIn(AisleFixture aisle, RackPosition rack, ItemKey key) {
        IItemHandler station = aisle.handlerAt(aisle.rackPos(rack));
        long count = 0L;
        for (int slot = 0; slot < station.getSlots(); slot++) {
            ItemStack stack = station.getStackInSlot(slot);
            if (key.matches(stack))
                count += stack.getCount();
        }
        return count;
    }

    /** Planks lying in the aisle's storage inventories. */
    private static long storedPlanks(AisleFixture aisle) {
        return aisle.storedAt(LOG_RACK, PLANK) + aisle.storedAt(STORE_RACK, PLANK);
    }

    /** The one open request to the terminal; fails unless there is exactly one. */
    private static RetrievalRequest<ItemKey, BlockPos> onlyRequest(GameTestHelper helper, AisleFixture aisle) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = terminalRequests(aisle);
        if (open.size() != 1) {
            helper.fail("expected exactly one request to the terminal, found " + open);
            throw new IllegalStateException("unreachable");
        }
        return open.getFirst();
    }

    /** The open requests to the terminal, oldest first. */
    private static List<RetrievalRequest<ItemKey, BlockPos>> terminalRequests(AisleFixture aisle) {
        return aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK));
    }

    /** The one open request to the terminal for {@code key}; fails unless there is exactly one. */
    private static RetrievalRequest<ItemKey, BlockPos> terminalRequest(GameTestHelper helper, AisleFixture aisle,
            ItemKey key) {
        List<RetrievalRequest<ItemKey, BlockPos>> found = terminalRequests(aisle).stream()
                .filter(request -> request.key().equals(key)).toList();
        if (found.size() != 1) {
            helper.fail("expected exactly one request to the terminal for " + key + ", found " + found);
            throw new IllegalStateException("unreachable");
        }
        return found.getFirst();
    }

    /** The one order of {@code orders} that is the root of a plan making {@code key}; fails unless there is one. */
    private static ProductionOrder<ItemKey, RackPosition> root(GameTestHelper helper,
            List<ProductionOrder<ItemKey, RackPosition>> orders, ItemKey key) {
        List<ProductionOrder<ItemKey, RackPosition>> found = orders.stream()
                .filter(order -> !order.isStep() && order.result().equals(key)).toList();
        if (found.size() != 1) {
            helper.fail("expected exactly one root order making " + key + ", found " + found);
            throw new IllegalStateException("unreachable");
        }
        return found.getFirst();
    }

    /** The line of one ingredient, as a Fetch hands its lines to the controller's preview. */
    private static SequencedMap<ItemKey, Long> lines(ItemKey key, long amount) {
        SequencedMap<ItemKey, Long> lines = new LinkedHashMap<>();
        lines.put(key, amount);
        return lines;
    }

    /** The lines of two ingredients, in that order: the grid's first-cell order. */
    private static SequencedMap<ItemKey, Long> lines(ItemKey first, long firstAmount, ItemKey second,
            long secondAmount) {
        SequencedMap<ItemKey, Long> lines = lines(first, firstAmount);
        lines.put(second, secondAmount);
        return lines;
    }

    /**
     * Fails the moment the open craft order counts an ingredient that has not arrived: what it has counted as
     * delivered is never more than the buffer holds of {@code keys}, and an order that is done has counted everything
     * it wanted and has all of it in the buffer. An order credited early or twice — when a chain's root completes, or
     * when its product is stored, as well as on the drop — would be done while the items are still in a rack. Checked
     * every tick until the player crafts from the buffer ({@code crafted}), which takes the items out again.
     */
    private static void assertCreditedOnArrival(GameTestHelper helper, AisleFixture aisle, AtomicBoolean crafted,
            ItemKey... keys) {
        if (crafted.get())
            return;
        TerminalCraftOrderState state = terminal(aisle).craftOrderState();
        if (!state.active())
            return;
        long inBuffer = 0L;
        for (ItemKey key : keys)
            inBuffer += aisle.stationCount(TERMINAL_RACK, key);
        if (state.delivered() > inBuffer)
            helper.fail("the craft order counted " + state.delivered() + " items delivered, but only " + inBuffer
                    + " are in the buffer");
        if (state.state() == ListOrderState.DONE && (state.delivered() != state.wanted() || inBuffer < state.wanted()))
            helper.fail("the craft order is done with " + state.delivered() + " of " + state.wanted()
                    + " counted and " + inBuffer + " in the buffer");
    }

    private static WarehouseTerminalBlockEntity terminal(AisleFixture aisle) {
        return aisle.terminalAt(TERMINAL_RACK);
    }

    /** A Fetch of {@code crafts} crafts of sticks through the menu. */
    private static TerminalCraftOutcome fetch(Player player, WarehouseTerminalMenu menu, int crafts,
            long acceptedProducing, boolean answering) {
        return fetch(player, menu, STICK_GRID, crafts, acceptedProducing, answering);
    }

    /** A Fetch of {@code crafts} crafts of oak fences through the menu. */
    private static TerminalCraftOutcome fetchFences(Player player, WarehouseTerminalMenu menu, int crafts,
            long acceptedProducing, boolean answering) {
        return fetch(player, menu, FENCE_GRID, crafts, acceptedProducing, answering);
    }

    private static TerminalCraftOutcome fetch(Player player, WarehouseTerminalMenu menu, CraftGrid<ItemKey> grid,
            int crafts, long acceptedProducing, boolean answering) {
        return menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(menu.containerId, grid,
                Optional.empty(), crafts, acceptedProducing, answering));
    }

    /** Fails the test unless {@code outcome} is {@code result}, with {@code crafted}, naming {@code about}. */
    private static void assertOutcome(GameTestHelper helper, TerminalCraftOutcome outcome, TerminalCraftResult result,
            int crafted, @Nullable ItemKey about, String context) {
        helper.assertValueEqual(outcome.result(), result, context + ": the answer");
        helper.assertValueEqual(outcome.crafted(), crafted, context + ": the number it names");
        helper.assertValueEqual(outcome.about(), Optional.ofNullable(about), context + ": the item it names");
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    /** A survival player standing at the terminal, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, AisleFixture aisle) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(aisle.rackPos(TERMINAL_RACK)));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
