package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ClipboardList;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalListOutcome;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalListState;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListLine;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.network.TerminalListActionPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the warehouse terminal's <b>clipboard order</b> (M23, issue #19,
 * {@code docs/warehouse-system.md} §3.4.4): a clipboard goes into the terminal's list slot, Fetch is pressed, and the
 * warehouse works the list off in portions, ticking the entries off as it delivers them.
 * <p>
 * What is proved here is the behaviour the project owner settled on, and nothing about the screen — the screen draws
 * what these tests assert the server does:
 * <ul>
 * <li>{@code terminallistworkedoffcompletely} — a hand-written clipboard a stocked warehouse covers: no dialog at all,
 * both entries delivered physically by the crane and both <b>ticked off on the clipboard</b>, through the very payload
 * entry point a client uses;</li>
 * <li>{@code terminallistpartialstockasksfirst} — "the list wants 40 and the warehouse holds 12": the dialog, the
 * answer <b>no</b> (nothing requested at all), and then the answer <b>yes</b>, after which what there is really
 * arrives and the entry stays <b>unticked</b>, because a tick mark is a receipt for a full delivery;</li>
 * <li>{@code terminallistproducibleentryasks} — an entry that is not in stock but could be made: no production order
 * until the player agrees, and one afterwards. The same item through a <b>click</b> starts production without a
 * question, which is the whole point of the request scope;</li>
 * <li>{@code terminallistwaitsforafulldestination} — the destination is full: the request stays open, nothing is
 * refused, and the first freed slot finishes the list ("no 'your output cannot hold 40 stacks' refusal");</li>
 * <li>{@code terminallistsurvivesareload} — saved mid-list and read back: the lines, the progress, the clipboard and
 * the tick marks, and the rest of the list is then delivered by the restored order;</li>
 * <li>{@code terminallistclipboardtakenoutmidlist} — the clipboard is pulled out of the slot: the order is given up
 * and its open requests are cancelled, while the tick marks it earned stay on the clipboard;</li>
 * <li>{@code terminallistunobtainableentryneverblocksthelist} — an entry the warehouse can neither stock nor produce
 * is stepped over and left unticked, and the entries after it are served.</li>
 * </ul>
 * <b>Item conservation</b> is asserted on every tick of every one of them ({@link ItemCensus}). The <b>clipboard</b> is
 * deliberately not part of that census: ticking an entry off changes its data component on purpose, so its
 * {@link ItemKey} changes by design and no fixed expectation could hold. It is asserted explicitly instead — every test
 * checks that the clipboard is still in the list slot, and {@link #terminalListClipboardTakenOutMidList} checks that
 * taking it out hands the player the same item back.
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}, storage on the left rack plane, terminal and
 * production station on the right, and a creative motor wherever the crane really has to move.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalListGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1800;
    /** Its own batch, because it lowers {@code maxTerminalListEntries} ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksTerminalListConfig";
    /** Ticks the census is asserted over while nothing may move. */
    private static final int QUIET_TICKS = 40;
    /** Ticks a full destination is watched for before it is freed. */
    private static final int BLOCKED_TICKS = 60;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);
    /** A chest beside the aisle the full-destination test parks its filler items in. */
    private static final BlockPos SPILL = new BlockPos(10, BASE_Y, 0);

    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey STAR = ItemKey.of(Items.NETHER_STAR);

    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int EMERALDS_IN_STOCK = 16;
    private static final int LISTED_DIAMONDS = 8;
    private static final int LISTED_EMERALDS = 5;

    /** The partial case: the list wants this many and the warehouse holds {@link #SHORT_STOCK}. */
    private static final int SHORT_WANTED = 40;
    private static final int SHORT_STOCK = 12;

    private static final int LOGS_IN_STOCK = 8;
    private static final int PLANKS_PER_LOG = 4;
    private static final int LISTED_PLANKS = PLANKS_PER_LOG;
    /**
     * A list amount that is <b>not</b> a whole multiple of the pattern's yield, so one portion plans a whole run and
     * costs more production than the Yes at Fetch paid for: the per-portion question of
     * {@link #terminalListPortionQuestionIsShownAgainAndCanBeRefused}.
     */
    private static final int LISTED_PLANK = 1;

    /** How many of an unobtainable item the last test puts on the clipboard. */
    private static final int LISTED_STARS = 3;

    /** Stacks of filler the full-destination test puts into the terminal, i.e. its whole buffer. */
    private static final int FILLER_STACK = 64;

    private TerminalListGameTests() {
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the list itself --------------------------------------------------------------------------------------------

    /**
     * The feature end to end, and through the payload entry point a client really uses: a hand-written clipboard with
     * two entries a stocked warehouse covers in full.
     * <p>
     * Nothing may be asked about — the whole list comes out of the racks — every item is fetched physically by the
     * crane, and both entries end up <b>ticked off on the clipboard that is still lying in the slot</b>. That last
     * part is the point of the issue: the clipboard is the order and its receipt in one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListWorkedOffCompletely(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true);
        ItemStack clipboard = clipboard(entry(DIAMOND, LISTED_DIAMONDS), entry(EMERALD, LISTED_EMERALDS));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a clipboard list is worked off"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    helper.assertFalse(terminal.hasListOrder(), "a clipboard in the slot orders nothing by itself");

                    // Through the payload path: the menu the player really has open decides, the terminal validates.
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    TerminalListOutcome outcome = submit(helper, player, menu, TerminalListActionPayload.Action.FETCH,
                            0L, 0L);
                    helper.assertValueEqual(outcome.result(), TerminalListResult.STARTED,
                            "a list the racks cover is never asked about");
                    TerminalListState state = terminal.listState();
                    helper.assertTrue(state.active(), "the order exists");
                    helper.assertValueEqual(state.entries(), 2, "both entries were taken");
                    helper.assertValueEqual(state.wanted(), (long) (LISTED_DIAMONDS + LISTED_EMERALDS),
                            "and both amounts");
                    helper.assertValueEqual(state.entriesComplete(), 0, "nothing is delivered yet");
                    helper.assertFalse(state.truncated(), "nothing was left on the clipboard");
                })
                // The portions are ordinary retrieval requests, so this is an ordinary crane trip per entry.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) LISTED_DIAMONDS,
                            "the diamonds arrived in the terminal");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, EMERALD), (long) LISTED_EMERALDS,
                            "and the emeralds");
                })
                .thenWaitUntil(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.DONE, "the list is done");
                    helper.assertValueEqual(terminal.listState().entriesComplete(), 2, "both entries are complete");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    // The receipt: the clipboard is still in the slot and both of its entries are ticked.
                    List<ListEntry<ItemKey>> entries = ClipboardList.read(terminal.listClipboard());
                    helper.assertValueEqual(entries.size(), 2, "the clipboard still carries both entries");
                    for (ListEntry<ItemKey> read : entries)
                        helper.assertTrue(read.checked(), "entry " + read.index() + " is ticked off");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, DIAMOND),
                            (long) (DIAMONDS_IN_STOCK - LISTED_DIAMONDS), "the diamonds left the racks");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_B, EMERALD),
                            (long) (EMERALDS_IN_STOCK - LISTED_EMERALDS), "and the emeralds");
                    assertReAssertingTicksIsSilent(helper, terminal.listClipboard());
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>A tick mark belongs to the clipboard it was earned on and to no other.</b>
     * <p>
     * Create's clipboard stacks, and its own item-copying recipe makes a stack of them out of one written clipboard and
     * a few blanks ({@code ItemCopyingRecipe}); a data component value is then shared <b>by reference</b> between every
     * copy of that stack ({@code PatchedDataComponentMap#copy} marks only the patch map copy-on-write). A tick mark
     * written by changing the entry in place would therefore appear on every one of them, and because a ticked entry is
     * not orderable it would silently drop out of any list those clipboards were later used for.
     * <p>
     * So: one clipboard split off a stack of two goes into the terminal, the list is worked off, and the clipboard left
     * in the player's hand must still read exactly as it did. The component instance is checked as well, because an
     * entry changed in place would also leave the component <b>equal</b> to the one the menu remembers and the open
     * screen would never be sent the receipt.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListTickMarksNeverLeakToASharedClipboard(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true);
        // One written clipboard, count two, then split: both stacks carry the very same ClipboardContent instance.
        ItemStack stack = clipboard(entry(DIAMOND, LISTED_DIAMONDS));
        stack.setCount(2);
        ItemStack intoSlot = stack.split(1);
        helper.assertTrue(stack.get(AllDataComponents.CLIPBOARD_CONTENT)
                == intoSlot.get(AllDataComponents.CLIPBOARD_CONTENT),
                "the split stacks share one clipboard content, which is what this test is about");
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a shared clipboard is ticked off"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertTrue(terminal.listSlot()
                            .insertItem(WarehouseTerminalBlockEntity.LIST_SLOT, intoSlot, false).isEmpty(),
                            "the list slot took the clipboard");
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "a list the racks cover is never asked about");
                })
                .thenWaitUntil(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.DONE, "the list is done");
                    helper.assertTrue(ClipboardList.read(terminal.listClipboard()).getFirst().checked(),
                            "and the clipboard in the slot carries the mark");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    List<ListEntry<ItemKey>> other = ClipboardList.read(stack);
                    helper.assertValueEqual(other.size(), 1, "the other clipboard still carries its entry");
                    helper.assertFalse(other.getFirst().checked(),
                            "and it is untouched: a mark may never leak onto a clipboard that shares the component");
                    helper.assertTrue(other.getFirst().orderable(),
                            "so a list ordered from it later still asks for that entry");
                    helper.assertFalse(terminal.listClipboard().get(AllDataComponents.CLIPBOARD_CONTENT)
                            == stack.get(AllDataComponents.CLIPBOARD_CONTENT),
                            "the ticked clipboard carries a new content, which is what makes the menu resync it");
                })
                .thenSucceed();
    }

    /**
     * <b>Partial stock asks first.</b> The list wants {@value #SHORT_WANTED} diamonds and the warehouse holds
     * {@value #SHORT_STOCK}: a dialog says so and asks whether to fetch what there is.
     * <p>
     * Three things are proved, in the order a player meets them. The question names the real numbers and <b>nothing
     * at all is started</b> — no order, no request, no promise — so answering <b>no</b> is simply not sending the
     * answer. The answer yes then fetches what there is, physically. And the entry stays <b>unticked</b>, because a
     * tick mark says "delivered in full" and 12 of 40 is not that; the clipboard a player picks up therefore reads
     * truthfully.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListPartialStockAsksFirst(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, DIAMOND.toStack(SHORT_STOCK));
        aisle.storage(STORAGE_B);
        aisle.terminal(TERMINAL_RACK);
        ItemStack clipboard = clipboard(entry(DIAMOND, SHORT_WANTED));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, SHORT_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a short list is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) SHORT_STOCK, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    TerminalListOutcome outcome = terminal.fetchList(player, 0L, 0L, 0L);
                    helper.assertValueEqual(outcome.result(), TerminalListResult.ASKING, "the terminal asks first");
                    ListOrderConfirmation<ItemKey> question = outcome.question().orElseThrow();
                    helper.assertValueEqual(question.wanted(), (long) SHORT_WANTED, "it names what the list wants");
                    helper.assertValueEqual(question.serveable(), (long) SHORT_STOCK, "what the racks can give");
                    helper.assertValueEqual(question.missing(), (long) (SHORT_WANTED - SHORT_STOCK),
                            "and what is missing");
                    helper.assertValueEqual(question.producing(), 0L, "nothing can be made here");
                    helper.assertValueEqual(question.entriesShort(), 1, "one entry falls short");
                    helper.assertValueEqual(question.named().size(), 1, "and the dialog names it");
                    helper.assertValueEqual(question.named().getFirst().key(), DIAMOND, "by item");

                    // Answered no: nothing was ever started, so there is nothing to undo.
                    helper.assertFalse(terminal.hasListOrder(), "no order exists");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and nothing was requested");
                    helper.assertValueEqual(aisle.controller().availableStock(DIAMOND), (long) SHORT_STOCK,
                            "nothing is promised either");
                })
                .thenExecuteFor(QUIET_TICKS,
                        () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), 0L,
                                "a question delivers nothing"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    ListOrderConfirmation<ItemKey> question = terminal.fetchList(player, 0L, 0L, 0L).question()
                            .orElseThrow();
                    // Answered yes: the same numbers back, which the server measures again before it acts on them.
                    TerminalListOutcome accepted = terminal.fetchList(player, question.missing(),
                            question.producing(), question.entriesDropped());
                    helper.assertValueEqual(accepted.result(), TerminalListResult.STARTED, "the answer starts it");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) SHORT_STOCK, "what there is really arrives"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalListState state = terminal.listState();
                    helper.assertValueEqual(state.delivered(), (long) SHORT_STOCK, "the order counted it");
                    helper.assertValueEqual(state.outstanding(), (long) (SHORT_WANTED - SHORT_STOCK),
                            "and still wants the rest");
                    helper.assertValueEqual(state.entriesComplete(), 0, "so the entry is not complete");
                    helper.assertTrue(state.isOpen(), "and the order is still open");
                    List<ListEntry<ItemKey>> entries = ClipboardList.read(terminal.listClipboard());
                    helper.assertFalse(entries.getFirst().checked(),
                            "a partly delivered entry is never ticked off: the clipboard must read truthfully");
                })
                .thenSucceed();
    }

    /**
     * <b>Producible items ask too.</b> An entry that is not in stock but that a production pattern could make starts a
     * production order only after the player agrees.
     * <p>
     * The same item through a <b>click</b> is not asked about at all, which is the whole reason a request carries a
     * scope ({@code core.terminal.RequestScope}): a click starts production under the player's eyes, a list order
     * starts it while they walk to the building site.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListProducibleEntryAsks(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        ItemStack clipboard = clipboard(entry(PLANK, LISTED_PLANKS));
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a producible list is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK),
                            "the aisle can make planks");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    TerminalListOutcome outcome = terminal.fetchList(player, 0L, 0L, 0L);
                    helper.assertValueEqual(outcome.result(), TerminalListResult.ASKING,
                            "something that has to be made is asked about");
                    ListOrderConfirmation<ItemKey> question = outcome.question().orElseThrow();
                    helper.assertValueEqual(question.producing(), (long) LISTED_PLANKS, "it names what would be made");
                    helper.assertValueEqual(question.serveable(), 0L, "nothing is in stock");
                    helper.assertValueEqual(question.missing(), 0L, "and nothing is out of reach");
                    helper.assertValueEqual(question.entriesProducing(), 1, "one entry would be produced");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0,
                            "no order was started by asking");
                    helper.assertValueEqual(aisle.controller().availableStock(LOG), (long) LOGS_IN_STOCK,
                            "and no log is promised");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    // The very same item through a click: production under the player's eyes needs no question.
                    helper.assertFalse(terminal.requestFromTerminal(player, PLANK, LISTED_PLANKS,
                            RequestAcknowledgement.NONE).isAsking(), "a click is not asked about");
                    for (var open : aisle.controller().openProductionOrders())
                        aisle.controller().cancelProductionOrder(open.id());
                    for (var request : List.copyOf(aisle.controller().openRequests()))
                        aisle.controller().cancelRequest(request.id());
                    helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 0,
                            "and that order is out of the way again");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    ListOrderConfirmation<ItemKey> question = terminal.fetchList(player, 0L, 0L, 0L).question()
                            .orElseThrow();
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.producing(),
                            question.entriesDropped()).result(), TerminalListResult.STARTED,
                            "the answer starts the list");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "and the list's portion started exactly one production order"))
                .thenExecute(() -> helper.assertTrue(
                        aisle.terminalAt(TERMINAL_RACK).listState().inFlight() > 0L,
                        "the line is waiting for what is being made"))
                .thenSucceed();
    }

    /**
     * <b>A question one portion raised can be shown again, said no to, and answered.</b>
     * <p>
     * The list asks for {@value #LISTED_PLANK} plank and the pattern makes {@value #PLANKS_PER_LOG} at a time, so the
     * Yes given at Fetch (production total 1) cannot pay for the whole run the portion would start (4) and the order
     * stops on the ordinary panel. That is the common shape, not a corner: any list amount that is not a whole multiple
     * of a pattern's yield lands here.
     * <p>
     * Three things are proved, and before this review fix none of them existed. The question is a <b>described</b> one —
     * it names the item, the amount and what would be made, which is all a player has to act on, and an aisle without
     * stock rules crosses nothing else at all. Asking for it <b>again</b> works, because it lives here and a player who
     * dismissed the panel would otherwise be left with an order nobody can answer. And saying <b>no</b> parks the
     * order instead of leaving it standing in {@code ASKING} with nothing on screen to refuse it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListPortionQuestionIsShownAgainAndCanBeRefused(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(STORAGE_B);
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        ItemStack clipboard = clipboard(entry(PLANK, LISTED_PLANK));
        Map<ItemKey, Long> conserved = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                "while a portion's question is waiting for an answer"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK),
                            "the aisle can make planks");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    ListOrderConfirmation<ItemKey> whole = terminal.fetchList(player, 0L, 0L, 0L).question()
                            .orElseThrow();
                    helper.assertValueEqual(whole.producing(), (long) LISTED_PLANK, "the list would have one made");
                    helper.assertValueEqual(terminal.fetchList(player, whole.missing(), whole.producing(),
                            whole.entriesDropped()).result(), TerminalListResult.STARTED, "and the Yes starts it");
                })
                // The portion plans a whole run of the pattern, which costs more production than the Yes paid for.
                .thenWaitUntil(() -> helper.assertValueEqual(
                        aisle.terminalAt(TERMINAL_RACK).listState().state(), ListOrderState.ASKING,
                        "a portion the budget no longer covers stops the order and asks"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    RequestConfirmation<ItemKey> question = terminal.listQuestion().orElseThrow();
                    helper.assertValueEqual(question.key(), PLANK, "the question is about the item of that portion");
                    helper.assertValueEqual(question.amount(), (long) LISTED_PLANK, "and about what it asked for");
                    helper.assertValueEqual(question.made(), (long) PLANKS_PER_LOG, "a whole run would be made");
                    helper.assertValueEqual(question.fromReserve(), 0L, "no keeper holds anything back here");
                    helper.assertValueEqual(question.pastMaximum(), 0L, "and no maximum is crossed");
                    helper.assertTrue(question.ingredients().isEmpty(), "nor any ingredient's reserve");
                    helper.assertTrue(question.required(RequestScope.LIST),
                            "so production alone is what this one is about - and the panel has to say so");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0,
                            "nothing was started by asking");

                    // "Show it to me again": nothing changes, and the question is still there to be answered.
                    helper.assertValueEqual(terminal.answerListQuestion(player, RequestAcknowledgement.NONE).result(),
                            TerminalListResult.QUESTION, "an answer that accepts nothing asks for the question again");
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.ASKING, "and changes nothing");
                    helper.assertTrue(terminal.listQuestion().isPresent(), "the question is still the server's");

                    // "No": the order parks, which is a state a player can see and come back from.
                    helper.assertValueEqual(terminal.declineListQuestion(player).result(),
                            TerminalListResult.DECLINED, "the panel's Cancel really reaches the order");
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.PARKED,
                            "a no parks the order instead of stranding it in ASKING");
                    helper.assertTrue(terminal.listQuestion().isEmpty(), "and the question is gone");
                    helper.assertValueEqual(terminal.declineListQuestion(player).result(),
                            TerminalListResult.NOT_RUNNING, "there is nothing left to refuse");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0,
                            "and a no started nothing either");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    helper.assertValueEqual(terminal.resumeListOrder(player).result(), TerminalListResult.RESUMED,
                            "the Resume button gives the parked order another try");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        aisle.terminalAt(TERMINAL_RACK).listState().state(), ListOrderState.ASKING,
                        "which asks the same question again, because nothing was ever accepted"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    RequestConfirmation<ItemKey> question = terminal.listQuestion().orElseThrow();
                    helper.assertValueEqual(
                            terminal.answerListQuestion(player, question.acknowledgement(RequestScope.LIST)).result(),
                            TerminalListResult.ANSWERED, "and the Yes is the way on");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "the answered portion started exactly one production order"))
                .thenSucceed();
    }

    /**
     * <b>It waits when the destination is full, and carries on as soon as space frees.</b>
     * <p>
     * The terminal's buffer is filled to the last slot before the list is started. The request is made all the same —
     * a full destination is a planner skip with a back-off and never a refusal — nothing is delivered while it stays
     * full, and the moment the buffer is emptied the crane finishes the list and the entry is ticked off. That is the
     * "no 'your output cannot hold 40 stacks' refusal" of issue #19.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListWaitsForAFullDestination(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true);
        helper.setBlock(SPILL, Blocks.CHEST);
        ItemStack clipboard = clipboard(entry(DIAMOND, LISTED_DIAMONDS));
        // Every buffer slot filled with something the list does not ask for: nothing more fits into the destination.
        WarehouseTerminalBlockEntity filled = aisle.terminalAt(TERMINAL_RACK);
        int filler = filled.bufferSlots() * FILLER_STACK;
        for (int slot = 0; slot < filled.bufferSlots(); slot++)
            helper.assertTrue(filled.insert(GOLD.toStack(FILLER_STACK), false).isEmpty(),
                    "the filler fits while the buffer is being filled");
        helper.assertValueEqual(filled.insert(DIAMOND.toStack(1), true).getCount(), 1,
                "and then nothing fits at all");
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK, GOLD,
                filler);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                "while a full destination blocks a clipboard list"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(), TerminalListResult.STARTED,
                            "a full destination never refuses the list");
                })
                .thenExecuteFor(BLOCKED_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), 0L,
                            "nothing is delivered while the buffer is full");
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).listState().isOpen(),
                            "and the order waits instead of giving up");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.listState().entriesComplete(), 0, "still nothing ticked off");
                    // Space frees: the player empties the buffer into the chest beside the aisle.
                    IItemHandler out = terminal.externalHandler();
                    IItemHandler chest = aisle.handlerAt(SPILL);
                    for (int slot = 0; slot < out.getSlots(); slot++) {
                        ItemStack taken = out.extractItem(slot, FILLER_STACK, false);
                        if (!taken.isEmpty())
                            aisle.insertAll(chest, taken);
                    }
                    helper.assertFalse(terminal.hasBufferedItems(), "the buffer is empty now");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) LISTED_DIAMONDS, "the freed slot continued the list at once"))
                .thenWaitUntil(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.DONE, "the list is done");
                    helper.assertTrue(ClipboardList.read(terminal.listClipboard()).getFirst().checked(),
                            "and the entry is ticked off");
                })
                .thenSucceed();
    }

    /**
     * <b>A reload mid-list.</b> The order is saved while one entry is delivered and the other is not, read back, and
     * the restored order finishes the list.
     * <p>
     * Everything that matters survives: the lines with what each of them still wants, the clipboard in the slot, and
     * the tick marks. The two tick stamps deliberately do not — a restored order starts its interval and its stall
     * timer over, so a world that was closed for an hour does not come back parked
     * ({@code TerminalListPersistence}).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListSurvivesAReload(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true);
        ItemStack clipboard = clipboard(entry(DIAMOND, LISTED_DIAMONDS), entry(EMERALD, LISTED_EMERALDS));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a clipboard list is reloaded"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    helper.assertValueEqual(terminal
                            .fetchList(playerAt(helper, aisle.rackPos(TERMINAL_RACK)), 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "the list starts");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) LISTED_DIAMONDS, "the first entry is delivered"))
                .thenExecute(() -> {
                    // Saved and read back in the same tick, which is what a real save is: atomic.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalListState before = terminal.listState();
                    CompoundTag saved = terminal.saveWithoutMetadata(registries);
                    helper.assertTrue(saved.contains(WarehouseTerminalBlockEntity.LIST_SLOT_TAG),
                            "the clipboard is saved");

                    WarehouseTerminalBlockEntity loaded = detached(helper, terminal);
                    loaded.loadWithComponents(saved, registries);
                    TerminalListState after = loaded.listState();
                    helper.assertTrue(after.active(), "the restored terminal has the order");
                    helper.assertValueEqual(after.entries(), before.entries(), "the same lines");
                    helper.assertValueEqual(after.wanted(), before.wanted(), "the same amounts");
                    helper.assertValueEqual(after.delivered(), before.delivered(), "and the same progress");
                    helper.assertValueEqual(after.entriesComplete(), before.entriesComplete(), "the same tick marks");
                    helper.assertTrue(ClipboardList.isClipboard(loaded.listClipboard()),
                            "and the clipboard came back with it");
                    helper.assertTrue(ClipboardList.read(loaded.listClipboard()).getFirst().checked(),
                            "the delivered entry is ticked off on the restored clipboard");

                    // The live terminal is reloaded from the very same bytes, exactly as a chunk load does it.
                    terminal.loadWithComponents(saved, registries);
                    helper.assertTrue(terminal.listState().active(), "the live order is back");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, EMERALD),
                        (long) LISTED_EMERALDS, "the restored order fetches the rest of the list"))
                .thenWaitUntil(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.DONE, "and finishes it");
                    for (ListEntry<ItemKey> read : ClipboardList.read(terminal.listClipboard()))
                        helper.assertTrue(read.checked(), "entry " + read.index() + " is ticked off");
                })
                .thenSucceed();
    }

    /**
     * <b>The clipboard is taken out mid-list.</b> The order is about that clipboard, so it is given up: its open
     * requests are cancelled and nothing stays promised.
     * <p>
     * What was already delivered stays in the buffer and the tick marks it earned stay on the clipboard the player is
     * now holding — a cancelled order may never un-tick a delivered entry, because the mark is a receipt for items
     * that really arrived.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListClipboardTakenOutMidList(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, false);
        ItemStack clipboard = clipboard(entry(DIAMOND, LISTED_DIAMONDS), entry(EMERALD, LISTED_EMERALDS));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a clipboard is pulled out"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    helper.assertValueEqual(terminal
                            .fetchList(playerAt(helper, aisle.rackPos(TERMINAL_RACK)), 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "the list starts");
                })
                // No motor: the crane never moves, so the requests are open and nothing has been delivered.
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().openRequestCount() > 0,
                        "the list has asked for something"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    ItemStack taken = terminal.listSlot().extractItem(WarehouseTerminalBlockEntity.LIST_SLOT, 1, false);
                    helper.assertTrue(ClipboardList.isClipboard(taken), "the player got the clipboard back");
                    helper.assertTrue(terminal.listClipboard().isEmpty(), "and the slot is empty");
                    helper.assertFalse(terminal.hasListOrder(),
                            "the order is gone with the list it was about");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0,
                            "and its requests were cancelled");
                    helper.assertValueEqual(aisle.controller().availableStock(DIAMOND), (long) DIAMONDS_IN_STOCK,
                            "nothing stays promised");
                    // A clipboard put back in is a new list, not the old order carried on.
                    putClipboard(helper, terminal, taken);
                    helper.assertFalse(terminal.hasListOrder(), "putting it back orders nothing by itself");
                })
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.controller().openRequestCount(), 0,
                        "and nothing is requested without a Fetch"))
                .thenExecute(() -> {
                    // The other half of the same rule: a clipboard <b>swapped</b> for another one in a single click
                    // is not this order's list any more, so the order is given up rather than carried on against a
                    // list nobody can read (M23, issue #19: "a clipboard swapped or edited mid-run must not confuse
                    // the order").
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal
                            .fetchList(playerAt(helper, aisle.rackPos(TERMINAL_RACK)), 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "the list starts again");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().openRequestCount() > 0,
                        "the second order has asked for something"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    terminal.listSlot().setStackInSlot(WarehouseTerminalBlockEntity.LIST_SLOT,
                            clipboard(entry(EMERALD, LISTED_EMERALDS)));
                    helper.assertFalse(terminal.hasListOrder(), "a swapped clipboard ends the order");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0,
                            "and its requests were cancelled with it");
                })
                .thenSucceed();
    }

    /**
     * <b>An entry the warehouse can neither stock nor produce.</b> It is reported in the dialog, stepped over while
     * the list is worked off, and left unticked — and the entry <b>after</b> it is served, which is the property that
     * makes a long shopping list usable at all.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListUnobtainableEntryNeverBlocksTheList(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true);
        ItemStack clipboard = clipboard(entry(STAR, LISTED_STARS), entry(DIAMOND, LISTED_DIAMONDS));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an unobtainable entry is skipped"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    TerminalListOutcome outcome = terminal.fetchList(player, 0L, 0L, 0L);
                    helper.assertValueEqual(outcome.result(), TerminalListResult.ASKING,
                            "a list with something unobtainable in it is asked about");
                    ListOrderConfirmation<ItemKey> question = outcome.question().orElseThrow();
                    helper.assertValueEqual(question.entriesImpossible(), 1, "one entry cannot be had at all");
                    helper.assertValueEqual(question.entriesServed(), 1, "and one is covered");
                    helper.assertValueEqual(question.missing(), (long) LISTED_STARS, "the stars are missing");
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.producing(),
                            question.entriesDropped()).result(), TerminalListResult.STARTED,
                            "the answer starts the rest");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) LISTED_DIAMONDS, "the entry after the unobtainable one is served in full"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    List<ListEntry<ItemKey>> entries = ClipboardList.read(terminal.listClipboard());
                    helper.assertFalse(entries.get(0).checked(), "the unobtainable entry stays unticked");
                    helper.assertTrue(entries.get(1).checked(), "the one behind it is ticked off");
                    TerminalListState state = terminal.listState();
                    helper.assertValueEqual(state.entriesComplete(), 1, "one of two entries is complete");
                    helper.assertValueEqual(state.outstanding(), (long) LISTED_STARS, "and the stars stay outstanding");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STAR), 0L,
                            "nothing was invented for the unobtainable entry");
                })
                .thenSucceed();
    }

    /**
     * <b>A clipboard the entry cap cut short is never started without saying so.</b>
     * <p>
     * The cap is lowered to one entry, so the second entry of a two-entry clipboard stays on it, untouched and
     * unticked. Before this review fix the question was measured over the <b>taken</b> part only, so a clipboard whose
     * taken part was fully in stock started straight away and the only report of the dropped entries was a tooltip the
     * player had to know to hover. Now the dialog comes up, names them, and a Fetch that says nothing about them is
     * asked a second time.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListTruncatedClipboardAsksFirst(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, false);
        ItemStack clipboard = clipboard(entry(DIAMOND, LISTED_DIAMONDS), entry(EMERALD, LISTED_EMERALDS));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK, EMERALD, EMERALDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a truncated clipboard is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalListEntries, 1);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    putClipboard(helper, terminal, clipboard);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    TerminalListOutcome outcome = terminal.fetchList(player, 0L, 0L, 0L);
                    helper.assertValueEqual(outcome.result(), TerminalListResult.ASKING,
                            "a list the cap cut short is asked about, even though everything it took is in stock");
                    ListOrderConfirmation<ItemKey> question = outcome.question().orElseThrow();
                    helper.assertValueEqual(question.entriesDropped(), 1, "one entry stayed on the clipboard");
                    helper.assertTrue(question.truncated(), "and the question says so");
                    helper.assertValueEqual(question.missing(), 0L, "while nothing the order took is missing");
                    helper.assertValueEqual(question.producing(), 0L, "and nothing has to be made");
                    helper.assertFalse(terminal.hasListOrder(), "nothing was started by asking");

                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.producing(), 0L)
                            .result(), TerminalListResult.ASKING, "a Yes that says nothing about them asks again");
                    helper.assertValueEqual(terminal.fetchList(player, question.missing(), question.producing(),
                            question.entriesDropped()).result(), TerminalListResult.STARTED,
                            "and the Yes that names them starts the list");
                    TerminalListState state = terminal.listState();
                    helper.assertValueEqual(state.entries(), 1, "one entry was taken");
                    helper.assertValueEqual(state.dropped(), 1, "and one was left behind");
                })
                .thenExecute(() -> {
                    List<ListEntry<ItemKey>> entries = ClipboardList
                            .read(aisle.terminalAt(TERMINAL_RACK).listClipboard());
                    helper.assertFalse(entries.get(1).checked(), "the untaken entry stays unticked");
                })
                .thenSucceed();
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    /** Diamonds and emeralds in two chests and a terminal; with a motor the crane really moves. */
    private static AisleFixture stockedAisle(GameTestHelper helper, boolean withMotor) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(STORAGE_B, EMERALD.toStack(EMERALDS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    private static void assertStocked(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(2, 0, 1);
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "the diamonds are in");
        helper.assertValueEqual(controller.countOf(EMERALD), (long) EMERALDS_IN_STOCK, "and the emeralds");
    }

    /** One entry of a hand-written clipboard: an icon and an amount, which is all a list order reads. */
    private static ClipboardEntry entry(ItemKey key, int amount) {
        return new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                .displayItem(key.toStack(), amount);
    }

    /**
     * A clipboard carrying {@code entries} on one page, written exactly as the Schematicannon writes its material
     * checklist: {@code ClipboardType.WRITTEN} and <b>read-only</b> ({@code MaterialChecklist#createWrittenClipboard}).
     * <p>
     * Read-only on purpose, and not as a special case: Create's own clipboard screen leaves the checkbox path open for
     * a read-only clipboard, so ticking entries off is the one thing the flag does not forbid, and every one of these
     * tests therefore exercises the Schematicannon's own clipboard shape.
     */
    private static ItemStack clipboard(ClipboardEntry... entries) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entries)), true));
        return stack;
    }

    /**
     * Fails the test unless writing the mark of an already ticked entry is a <b>silent</b> no-op, and unless a line
     * whose entry no longer shows its item is reported as one that could not be ticked (M23 review fix).
     * <p>
     * This is what the re-assertion after a reload does on every load of a terminal with a half-finished order
     * ({@code WarehouseTerminalBlockEntity#tickListOrder}): it passes every completed line again, and on a consistent
     * save every one of them already carries its mark. Counting those as failures logged
     * "Could not tick 1 of 1 delivered entries" on every single load, which said the exact opposite of what happened.
     */
    private static void assertReAssertingTicksIsSilent(GameTestHelper helper, ItemStack clipboard) {
        ClipboardList.Ticks again = ClipboardList.tickOff(clipboard, List.of(ListLine.of(0, 0, DIAMOND, 1)));
        helper.assertValueEqual(again.written(), 0, "a mark that is already there is not written again");
        helper.assertValueEqual(again.already(), 1, "it is counted as already ticked");
        helper.assertValueEqual(again.missed(), 0, "and not as one that could not be written");
        helper.assertTrue(again.complete(), "so re-asserting a mark after a reload warns about nothing");
        helper.assertValueEqual(again.marked(), 1, "the entry carries its mark either way");

        ClipboardList.Ticks gone = ClipboardList.tickOff(clipboard, List.of(ListLine.of(0, 0, GOLD, 1)));
        helper.assertValueEqual(gone.missed(), 1, "a line whose entry shows another item really lost its receipt");
        helper.assertFalse(gone.complete(), "which is the one case worth a message");
    }

    /** Puts {@code clipboard} into the terminal's list slot, as a player's drag or shift-click would. */
    private static void putClipboard(GameTestHelper helper, WarehouseTerminalBlockEntity terminal,
            ItemStack clipboard) {
        ItemStack rest = terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT, clipboard.copy(),
                false);
        helper.assertTrue(rest.isEmpty(), "the list slot took the clipboard");
    }

    /** A list action through the payload entry point; fails the test when it was dropped instead of answered. */
    private static TerminalListOutcome submit(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            TerminalListActionPayload.Action action, long acceptedMissing, long acceptedProducing) {
        Optional<TerminalListOutcome> outcome = WarehouseTerminalMenu.submitListAction(player, menu.containerId,
                action, acceptedMissing, acceptedProducing, 0L, RequestAcknowledgement.NONE);
        if (outcome.isEmpty()) {
            helper.fail("the list action was dropped instead of answered");
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

    private static WarehouseTerminalBlockEntity detached(GameTestHelper helper,
            WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.create(terminal.getBlockPos(),
                terminal.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse terminal");
        return copy;
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
