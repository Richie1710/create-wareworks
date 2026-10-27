package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalListOutcome;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.terminal.ListEntryStatus;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListShortage;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.network.TerminalListAnswerPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * The clipboard list question as a table (issue #36, ADR-069, {@code docs/warehouse-system.md} §3.4.4): every entry
 * measured with the click's own planner against one ledger, with its split, the planner's reason and the item it
 * names, and the materials the list is short of — and the payload that carries it, bounded.
 * <p>
 * The owner's case is a <b>Lantern</b> with a pattern (eight iron nuggets around a torch make one lantern) that the old
 * dialog reported as "0 of 2 / cannot be had at all". The old measurement looked one level deep: when the nuggets and
 * the torch exist only through further patterns, it counted nothing, while a click plans the chain and makes both
 * lanterns. The lantern tests hold the list and the click to the same answer.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalListTableGameTests {
    /**
     * A test that overrides the config needs a batch of its own, because the tests of one batch run at the same time
     * and would see each other's overrides ({@link ConfigOverrides}); each one is restored in an {@code @AfterBatch}.
     */
    private static final String STEPS_BATCH = "wareworksListTableSteps";
    private static final String PAUSE_BATCH = "wareworksListTablePause";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** The crane speed the other job tests use, so the pause test's restock order reaches its machine in time. */
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int PAUSE_TIMEOUT_TICKS = 3000;
    /** A timeout short enough for a test, far above a crane trip so only a genuinely stuck order trips it. */
    private static final int SHORT_ORDER_TIMEOUT = 120;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);

    private static final ItemKey LANTERN = ItemKey.of(Items.LANTERN);
    private static final ItemKey NUGGET = ItemKey.of(Items.IRON_NUGGET);
    private static final ItemKey INGOT = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private static final int NUGGETS_PER_LANTERN = 8;
    private static final int NUGGETS_PER_INGOT = 9;
    private static final int TORCHES_PER_RUN = 4;
    /** The minimum a stock keeper asks for in the pause test: less than one run, so one order refills it. */
    private static final int TORCH_MINIMUM = 2;
    /** What a payload of the longest list may take; a full one is about 100 KiB. */
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private TerminalListTableGameTests() {
    }

    @AfterBatch(batch = STEPS_BATCH)
    public static void restoreSteps(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = PAUSE_BATCH)
    public static void restorePause(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the lantern of issue #36 ------------------------------------------------------------------------------------

    /**
     * Nuggets and torches in the racks: one step, and the list says "Crafted" for both lanterns. A click for the same
     * two lanterns is granted with two to be made by that one step.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListLanternFromStock(GameTestHelper helper) {
        AisleFixture aisle = lanternAisle(helper, false, NUGGET.toStack(16), TORCH.toStack(2));
        Map<ItemKey, Long> conserved = ItemCensus.of(NUGGET, 16, TORCH, 2);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a lantern list is asked about"));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation<ItemKey> question = ask(helper, aisle, entry(LANTERN, 2));
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, question);
                    helper.assertValueEqual(line.status(), ListEntryStatus.CRAFTED, "the lanterns are made");
                    helper.assertValueEqual(line.produced(), 2L, "both of them");
                    helper.assertTrue(line.refusal().isEmpty(), "with nothing to refuse");
                    assertNothingStarted(helper, aisle);
                })
                .thenExecute(() -> {
                    // The very same item through a click: the list and the click agree.
                    RequestResult click = clickResult(helper, aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            player(helper, aisle), LANTERN, 2, RequestAcknowledgement.ANY));
                    helper.assertTrue(click.isAccepted(), "the click is accepted: " + click);
                    helper.assertValueEqual(click.granted(), 2, "for both lanterns");
                    helper.assertValueEqual(click.producing(), 2, "both of them made");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                            "by one order");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 0,
                            "with no step before it: the ingredients are in the racks");
                    cancelOpen(helper, aisle);
                })
                .thenSucceed();
    }

    /**
     * <b>The owner's case.</b> The nuggets and the torch exist only through further patterns (an ingot makes nine
     * nuggets, coal and a stick make four torches): the old dialog said "0 of 2". The list now says "Crafted 2", a click
     * for the same two lanterns is granted with two to be made, and the Yes starts the list without a second question.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListLanternThroughAChain(GameTestHelper helper) {
        AisleFixture aisle = lanternAisle(helper, false, INGOT.toStack(2), COAL.toStack(1), STICK.toStack(1));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation<ItemKey> question = ask(helper, aisle, entry(LANTERN, 2));
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, question);
                    helper.assertValueEqual(line.status(), ListEntryStatus.CRAFTED,
                            "the chain makes the lanterns, as a click would (issue #36)");
                    helper.assertValueEqual(line.produced(), 2L, "both of them");
                    helper.assertValueEqual(question.producing(), 2L, "and that is what the Yes agrees to");
                    assertNothingStarted(helper, aisle);
                })
                .thenExecute(() -> {
                    // The very same item through a click: the list and the click agree.
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    RequestResult click = clickResult(helper, terminal.requestFromTerminal(player(helper, aisle),
                            LANTERN, 2, RequestAcknowledgement.ANY));
                    helper.assertTrue(click.isAccepted(), "the click is accepted: " + click);
                    helper.assertValueEqual(click.granted(), 2, "for both lanterns");
                    helper.assertValueEqual(click.producing(), 2, "both of them made");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 2,
                            "by a chain: nuggets and torches first");
                    // Out of the way again, so the list is measured against the same warehouse.
                    cancelOpen(helper, aisle);
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = player(helper, aisle);
                    ListOrderConfirmation<ItemKey> question = terminal.fetchList(player, 0L, 0L, 0L).question()
                            .orElseThrow();
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.madeTotal(),
                            question.entriesDropped()).result(), TerminalListResult.STARTED, "the Yes starts it");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listState().inFlight() > 0L,
                        "the lanterns' portion is requested"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listQuestion().isEmpty(),
                            "without a second question: the Yes covered what the portion makes");
                    helper.assertValueEqual(aisle.controller().openProductionStepCount(), 2,
                            "and the portion's chain is the click's chain");
                })
                .thenSucceed();
    }

    /**
     * A pattern that makes more per run than the entry asks for: "Torch x2" from coal and a stick, which make four. The
     * promise is two, the run makes four, and a portion is charged the four. The Yes agrees to the whole run
     * ({@link ListOrderConfirmation#madeTotal()}), so the list starts without a second question; a Yes to the promise
     * alone is asked again before anything starts.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListTorchWholeRun(GameTestHelper helper) {
        AisleFixture aisle = lanternAisle(helper, false, COAL.toStack(1), STICK.toStack(1));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation<ItemKey> question = ask(helper, aisle, entry(TORCH, 2));
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, question);
                    helper.assertValueEqual(line.status(), ListEntryStatus.CRAFTED, "the torches are made");
                    helper.assertValueEqual(line.produced(), 2L, "the two the entry asks for");
                    helper.assertValueEqual(line.made(), (long) TORCHES_PER_RUN, "in one run of four");
                    helper.assertValueEqual(question.madeTotal(), (long) TORCHES_PER_RUN,
                            "and the run is what the Yes agrees to");
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = player(helper, aisle);
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.producing(),
                            question.entriesDropped()).result(), TerminalListResult.ASKING,
                            "a Yes to the promise alone does not cover the run");
                    assertNothingStarted(helper, aisle);
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.madeTotal(),
                            question.entriesDropped()).result(), TerminalListResult.STARTED,
                            "a Yes to the whole run starts the list");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listState().inFlight() > 0L,
                        "the torches' portion is requested"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listQuestion().isEmpty(),
                            "without a second question: the Yes covered the run the portion is charged");
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                            "one order for one run of torches");
                })
                .thenSucceed();
    }

    /** A chain longer than {@code maxProductionPlanSteps} is refused by the list exactly as by a click. */
    @GameTest(template = AISLE_16X10X7, batch = STEPS_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListLanternChainTooLong(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxProductionPlanSteps, 2);
        AisleFixture aisle = lanternAisle(helper, false, INGOT.toStack(2), COAL.toStack(1), STICK.toStack(1));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, ask(helper, aisle, entry(LANTERN, 2)));
                    helper.assertValueEqual(line.status(), ListEntryStatus.IMPOSSIBLE, "three steps do not fit two");
                    helper.assertValueEqual(line.refusal(), Optional.of(PlanRefusal.TOO_MANY_STEPS), "and says so");
                    assertClickAgrees(helper, aisle, line);
                    assertNothingStarted(helper, aisle);
                })
                .thenSucceed();
    }

    /**
     * A stopped torch: the old measurement looked one level deep, ignored the safety stop and promised the lanterns,
     * and the portion was refused afterwards. The list now refuses them as a click does, naming the torch.
     */
    @GameTest(template = AISLE_16X10X7, batch = PAUSE_BATCH, timeoutTicks = PAUSE_TIMEOUT_TICKS)
    public static void terminalListLanternPaused(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.productionOrderTimeoutTicks, SHORT_ORDER_TIMEOUT);
        AisleFixture aisle = lanternAisle(helper, true, NUGGET.toStack(16), COAL.toStack(2), STICK.toStack(2));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                // A rule asks for torches, the warehouse orders them by itself, and nobody plays the machine.
                .thenExecute(() -> keeperMinimum(aisle, TORCH, TORCH_MINIMUM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().stockRulePause(TORCH).isPresent(),
                        "the lost batch stopped the warehouse from making torches"))
                .thenExecute(() -> {
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, ask(helper, aisle, entry(LANTERN, 2)));
                    helper.assertValueEqual(line.status(), ListEntryStatus.IMPOSSIBLE,
                            "a stopped ingredient makes no lantern");
                    helper.assertValueEqual(line.refusal(), Optional.of(PlanRefusal.PAUSED), "the stop is the reason");
                    helper.assertValueEqual(line.about(), Optional.of(TORCH), "and the torch is what it names");
                    assertClickAgrees(helper, aisle, line);
                })
                .thenSucceed();
    }

    // --- one ledger for the whole list --------------------------------------------------------------------------------

    /**
     * Five entries against one ingot and two torches: the nuggets take the ingot, so neither lantern entry can be had,
     * and the Missing section sums the ingots both of them need. A second list with two ingots is the partly case.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListEntriesShareOneStock(GameTestHelper helper) {
        AisleFixture aisle = lanternAisle(helper, false, INGOT.toStack(1), TORCH.toStack(2), COBBLE.toStack(16));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation<ItemKey> question = ask(helper, aisle, entry(NUGGET, NUGGETS_PER_INGOT),
                            entry(LANTERN, 1), entry(LANTERN, 2), entry(DIAMOND, 3), entry(COBBLE, 16));
                    List<ListOrderConfirmation.Line<ItemKey>> lines = question.lines();
                    helper.assertValueEqual(lines.size(), 5, "one line per entry");
                    helper.assertValueEqual(lines.get(0).status(), ListEntryStatus.CRAFTED, "the nuggets are made");
                    helper.assertValueEqual(lines.get(1).status(), ListEntryStatus.IMPOSSIBLE,
                            "of the one ingot the nuggets already took");
                    helper.assertValueEqual(lines.get(1).refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "the lantern is short of an ingredient");
                    helper.assertValueEqual(lines.get(1).about(), Optional.of(INGOT), "the ingot");
                    helper.assertValueEqual(lines.get(1).materials(),
                            List.of(new ListOrderConfirmation.Material<>(INGOT, 1L)), "one ingot for one lantern");
                    helper.assertValueEqual(lines.get(2).status(), ListEntryStatus.IMPOSSIBLE, "nor the second");
                    helper.assertValueEqual(lines.get(2).materials(),
                            List.of(new ListOrderConfirmation.Material<>(INGOT, 2L)), "two ingots for two");
                    helper.assertValueEqual(lines.get(3).refusal(), Optional.of(PlanRefusal.NO_PATTERN),
                            "nothing here makes diamonds");
                    helper.assertValueEqual(lines.get(3).materials(),
                            List.of(new ListOrderConfirmation.Material<>(DIAMOND, 3L)), "so the diamonds are missing");
                    helper.assertValueEqual(lines.get(4).status(), ListEntryStatus.AVAILABLE, "the cobblestone is in");

                    helper.assertValueEqual(question.tableOrder().stream().map(ListOrderConfirmation.Line::order)
                            .toList(), List.of(1, 2, 3, 0, 4), "problems first, then crafted, then available");
                    Map<ItemKey, ListShortage<ItemKey>> missing = question.shortages().stream()
                            .collect(Collectors.toMap(ListShortage::key, shortage -> shortage));
                    helper.assertValueEqual(missing.size(), 2, "two materials are missing");
                    helper.assertValueEqual(missing.get(INGOT).amount(), 3L, "three ingots");
                    helper.assertValueEqual(missing.get(INGOT).entries(), List.of(1, 2), "for both lantern entries");
                    helper.assertValueEqual(missing.get(DIAMOND).amount(), 3L, "and three diamonds");
                    helper.assertValueEqual(question.wanted(),
                            question.serveable() + question.producing() + question.missing(), "the totals add up");
                    assertNothingStarted(helper, aisle);
                })
                .thenSucceed();
    }

    /** Two ingots for three lanterns: two are made, one is missing for want of one more ingot. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListLanternPartly(GameTestHelper helper) {
        AisleFixture aisle = lanternAisle(helper, false, INGOT.toStack(2), TORCH.toStack(3));
        helper.startSequence()
                .thenWaitUntil(() -> assertLanternReady(helper, aisle))
                .thenExecute(() -> {
                    ListOrderConfirmation.Line<ItemKey> line = only(helper, ask(helper, aisle, entry(LANTERN, 3)));
                    helper.assertValueEqual(line.status(), ListEntryStatus.PARTLY, "only part can be had");
                    helper.assertValueEqual(line.produced(), 2L, "two ingots make the nuggets of two lanterns");
                    helper.assertValueEqual(line.missing(), 1L, "one is missing");
                    helper.assertValueEqual(line.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                            "for want of an ingredient");
                    helper.assertValueEqual(line.about(), Optional.of(INGOT), "the ingot");
                    helper.assertValueEqual(line.materials(),
                            List.of(new ListOrderConfirmation.Material<>(INGOT, 1L)), "one more would do");
                    assertNothingStarted(helper, aisle);
                })
                .thenSucceed();
    }

    // --- the payload ----------------------------------------------------------------------------------------------

    /**
     * The question survives the wire unchanged, a question of the longest list with three materials per entry stays far
     * below the packet limit, and a payload claiming more rows or materials than the cap, or a reason this build does
     * not know, is read safely.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalListAnswerPayloadRoundTrip(GameTestHelper helper) {
        ListOrderConfirmation<ItemKey> small = new ListOrderConfirmation<>(List.of(
                new ListOrderConfirmation.Line<>(0, LANTERN, 3L, 0L, 2L, 2L, 0L,
                        Optional.of(PlanRefusal.MISSING_INGREDIENT), Optional.of(INGOT),
                        List.of(new ListOrderConfirmation.Material<>(INGOT, 1L))),
                new ListOrderConfirmation.Line<>(1, COBBLE, 16L, 16L, 0L, 0L, 0L, Optional.empty(), Optional.empty(),
                        List.of())), 2);
        TerminalListAnswerPayload sent = new TerminalListAnswerPayload(7, TerminalListResult.ASKING,
                Optional.of(small));
        RegistryFriendlyByteBuf buffer = buffer(helper);
        TerminalListAnswerPayload.STREAM_CODEC.encode(buffer, sent);
        TerminalListAnswerPayload received = TerminalListAnswerPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        helper.assertValueEqual(received, sent, "the question arrives unchanged");

        List<ListOrderConfirmation.Line<ItemKey>> full = new ArrayList<>(ListOrderConfirmation.MAX_LINES);
        for (int i = 0; i < ListOrderConfirmation.MAX_LINES; i++)
            full.add(new ListOrderConfirmation.Line<>(i, LANTERN, 100_000L, 1_000L, 2_000L, 2_000L, 0L,
                    Optional.of(PlanRefusal.TOO_MANY_INGREDIENT_ITEMS), Optional.of(TORCH),
                    List.of(new ListOrderConfirmation.Material<>(INGOT, 50_000L),
                            new ListOrderConfirmation.Material<>(COAL, 50_000L),
                            new ListOrderConfirmation.Material<>(STICK, 50_000L))));
        RegistryFriendlyByteBuf large = buffer(helper);
        TerminalListAnswerPayload longest = new TerminalListAnswerPayload(7, TerminalListResult.ASKING,
                Optional.of(new ListOrderConfirmation<>(full, 0)));
        TerminalListAnswerPayload.STREAM_CODEC.encode(large, longest);
        int bytes = large.readableBytes();
        helper.assertTrue(bytes < MAX_PAYLOAD_BYTES, "the longest list's question takes " + bytes + " bytes");
        helper.assertValueEqual(TerminalListAnswerPayload.STREAM_CODEC.decode(large), longest,
                "and it arrives unchanged");

        RegistryFriendlyByteBuf tooMany = header(helper);
        tooMany.writeVarInt(ListOrderConfirmation.MAX_LINES + 1);
        assertRejected(helper, tooMany, "a question claiming more lines than the cap");

        RegistryFriendlyByteBuf manyMaterials = header(helper);
        manyMaterials.writeVarInt(1);
        lineHead(manyMaterials, 0);
        manyMaterials.writeVarInt(ListOrderConfirmation.MAX_LINE_MATERIALS + 1);
        assertRejected(helper, manyMaterials, "a line claiming more materials than the cap");

        RegistryFriendlyByteBuf unknown = header(helper);
        unknown.writeVarInt(1);
        lineHead(unknown, 1);
        unknown.writeUtf("NOT_A_REASON", 32);
        unknown.writeVarInt(0);
        TerminalListAnswerPayload strange = TerminalListAnswerPayload.STREAM_CODEC.decode(unknown);
        ListOrderConfirmation.Line<ItemKey> line = strange.question().orElseThrow().lines().getFirst();
        helper.assertTrue(line.refusal().isEmpty(), "a reason this build does not know reads as none");
        helper.assertValueEqual(line.missing(), 5L, "and the line is still read");
        helper.assertValueEqual(unknown.readableBytes(), 0, "to its end");
        helper.succeed();
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    /**
     * An aisle with {@code stock} in its racks, a terminal, and one production station holding the three patterns:
     * an ingot to nine nuggets, coal and a stick to four torches, eight nuggets around a torch to one lantern.
     *
     * @param withMotor whether the crane may move; only the pause test needs it, to arm the safety stop
     */
    private static AisleFixture lanternAisle(GameTestHelper helper, boolean withMotor, ItemStack... stock) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        if (withMotor)
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.storage(STORAGE_A, stock);
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        pattern(helper, station, 0, NUGGET, NUGGETS_PER_INGOT, INGOT, 1);
        pattern(helper, station, 1, TORCH, TORCHES_PER_RUN, COAL, 1, STICK, 1);
        pattern(helper, station, 2, LANTERN, 1, NUGGET, NUGGETS_PER_LANTERN, TORCH, 1);
        return aisle;
    }

    /** Pattern {@code slot}: {@code resultCount} of {@code result} from the ingredient pairs, one grid cell each. */
    private static void pattern(GameTestHelper helper, WarehouseProductionBlockEntity station, int slot,
            ItemKey result, int resultCount, Object... ingredients) {
        for (int i = 0; i < ingredients.length / 2; i++)
            helper.assertTrue(station.setPatternEntry(slot, i, (ItemKey) ingredients[2 * i],
                    (Integer) ingredients[2 * i + 1]), "ingredient " + i + " of pattern " + slot);
        helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, result, resultCount),
                "the result of pattern " + slot);
    }

    private static void assertLanternReady(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(2, 1, 1);
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 1, "the production station is recorded");
        helper.assertValueEqual(controller.aislePatterns().size(), 3, "with its three patterns");
        helper.assertTrue(controller.producibleKeys().contains(LANTERN), "and the aisle knows it can make lanterns");
    }

    /** Fetch on a clipboard of {@code entries}, which must be asked about; the question it raised. */
    private static ListOrderConfirmation<ItemKey> ask(GameTestHelper helper, AisleFixture aisle,
            ClipboardEntry... entries) {
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
        if (terminal.listClipboard().isEmpty()) {
            ItemStack rest = terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT,
                    clipboard(entries), false);
            helper.assertTrue(rest.isEmpty(), "the list slot took the clipboard");
        }
        TerminalListOutcome outcome = terminal.fetchList(player(helper, aisle), 0L, 0L, 0L);
        helper.assertValueEqual(outcome.result(), TerminalListResult.ASKING, "the terminal asks first");
        return outcome.question().orElseThrow();
    }

    private static ListOrderConfirmation.Line<ItemKey> only(GameTestHelper helper,
            ListOrderConfirmation<ItemKey> question) {
        helper.assertValueEqual(question.lines().size(), 1, "one entry is asked about");
        return question.lines().getFirst();
    }

    /** The click for the same entry is refused with the very reason and item the list names. */
    private static void assertClickAgrees(GameTestHelper helper, AisleFixture aisle,
            ListOrderConfirmation.Line<ItemKey> line) {
        RequestResult click = clickResult(helper, aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                player(helper, aisle), line.key(), (int) line.wanted(), RequestAcknowledgement.ANY));
        helper.assertFalse(click.isAccepted(), "the click is refused as well: " + click);
        helper.assertValueEqual(click.refusal(), line.refusal(), "for the same reason");
        helper.assertValueEqual(click.about(), line.about(), "about the same item");
    }

    private static RequestResult clickResult(GameTestHelper helper, TerminalRequestOutcome outcome) {
        helper.assertFalse(outcome.isAsking(), "a click with every cost accepted is not asked about");
        return outcome.result().orElseThrow();
    }

    /** Cancels every open production order and request, so the next step measures the same warehouse again. */
    private static void cancelOpen(GameTestHelper helper, AisleFixture aisle) {
        for (var open : List.copyOf(aisle.controller().openProductionOrders()))
            aisle.controller().cancelProductionOrder(open.id());
        for (var request : List.copyOf(aisle.controller().openRequests()))
            aisle.controller().cancelRequest(request.id());
        helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "nothing is left open");
    }

    /** Asking created no order and no request: measuring is all it did. */
    private static void assertNothingStarted(GameTestHelper helper, AisleFixture aisle) {
        helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0, "no order was started by asking");
        helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and no request either");
        helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listState().inFlight() == 0L, "nothing is on its way");
    }

    /** Writes a minimum for {@code key} into the keeper's first rule. */
    private static void keeperMinimum(AisleFixture aisle, ItemKey key, long minimum) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, minimum);
    }

    /** One entry of a hand-written clipboard: an icon and an amount, which is all a list order reads. */
    private static ClipboardEntry entry(ItemKey key, int amount) {
        return new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                .displayItem(key.toStack(), amount);
    }

    /** A written, read-only clipboard with {@code entries} on one page, as the Schematicannon writes one. */
    private static ItemStack clipboard(ClipboardEntry... entries) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entries)), true));
        return stack;
    }

    private static Player player(GameTestHelper helper, AisleFixture aisle) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = aisle.rackPos(TERMINAL_RACK);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess(),
                ConnectionType.NEOFORGE);
    }

    /** The start of an asking answer, up to the line count: container, result, question present, nothing dropped. */
    private static RegistryFriendlyByteBuf header(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        buffer.writeVarInt(7);
        buffer.writeUtf(TerminalListResult.ASKING.name());
        buffer.writeBoolean(true);
        buffer.writeVarInt(0);
        return buffer;
    }

    /** A line up to its refusal: order 0, five cobblestone wanted, nothing given, made or produced. */
    private static void lineHead(RegistryFriendlyByteBuf buffer, int flags) {
        buffer.writeVarInt(0);
        ItemKey.STREAM_CODEC.encode(buffer, COBBLE);
        buffer.writeVarLong(5L);
        buffer.writeVarLong(0L);
        buffer.writeVarLong(0L);
        buffer.writeVarLong(0L);
        buffer.writeByte(flags);
    }

    private static void assertRejected(GameTestHelper helper, RegistryFriendlyByteBuf buffer, String what) {
        try {
            TerminalListAnswerPayload.STREAM_CODEC.decode(buffer);
        } catch (DecoderException expected) {
            return;
        }
        helper.fail(what + " must not decode");
    }
}
