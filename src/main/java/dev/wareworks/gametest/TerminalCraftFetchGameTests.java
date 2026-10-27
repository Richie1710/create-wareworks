package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ClipboardList;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalListOutcome;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalListState;
import dev.wareworks.content.station.TerminalPortionRefusal;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftOrderStatePayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalListActionPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * GameTests of <b>Fetch ingredients</b> at the warehouse terminal's crafting panel (issue #24, slice 2, ADR-062,
 * {@code docs/warehouse-system.md} §3.4.5): a recipe laid in the grid, an amount, one press — and the crane brings what
 * the buffer is missing for that many crafts, through a second list order on the terminal, the <b>craft order</b>.
 * <p>
 * What is proved here is the server's half, through the very menu entry points a screen uses
 * ({@link WarehouseTerminalMenu#craftOrderAction}, and in the end-to-end test only the payload entries
 * {@link WarehouseTerminalMenu#submitCraftOrder} and {@link WarehouseTerminalMenu#submitCraft}):
 * <ul>
 * <li>{@code terminalcraftfetchesexactamounts}, {@code terminalcraftfetchesxn} — sticks x1 bring exactly two planks,
 * crafting tables x4 exactly sixteen; the order ends {@code DONE} with no request left, and a second Fetch finds
 * nothing to fetch;</li>
 * <li>{@code terminalcraftfetchtopsupthebuffer} — what the buffer holds is subtracted, a player's own pending click is
 * <b>not</b> (the owner's decision: top up against the buffer only), and the click still gets its own items;</li>
 * <li>{@code terminalcraftfetchmanyingredients} — a piston x2 asks for four items, never more than two requests of the
 * order are open at once ({@code terminalListOpenRequests}), and every key arrives exactly;</li>
 * <li>{@code terminalcraftfetchclampedtothebufferroom}, {@code terminalcraftfetchroomcountsopenrequests} — a fetch
 * that would not fit the buffer is asked about with the crafts that do, counting every open request to the terminal
 * first, so the crane never has to wait at it;</li>
 * <li>{@code terminalcraftrefusedfetch} — every refusal, each of which leaves the aisle's requests exactly as they
 * were, and a running craft order never blocks the clipboard's Fetch;</li>
 * <li>{@code terminalcraftpartialstockasksfirst} — too little in stock: a question, nothing requested, a stale Yes
 * asked again, and the real Yes fetching what there is;</li>
 * <li>{@code terminalcraftorderportionquestionisshownagainandcanberefused} — a stock rule's reserve stops a running fetch on a
 * portion's question: "show it again", a stale Yes, a reload, No, Resume and the real Yes;</li>
 * <li>{@code terminalcraftstaleanswerisnotcreditedtothenextquestion} — two screens at one terminal: a Yes to the
 * question the order has moved on from is never credited to the one it asks now;</li>
 * <li>{@code terminalcraftordernamesitsrefusal} — a refused portion is remembered while the order stands still, is
 * not saved, crosses the wire, and is forgotten by the next grant;</li>
 * <li>{@code terminalcraftrunsbesideaclipboardorder} — a clipboard order and a craft order whose portions merged into
 * one request of more than one crane load share its delivery correctly, never claiming more than arrived, and both
 * finish;</li>
 * <li>{@code terminalcraftcancelkeepsamergedclick}, {@code terminallistcancelkeepsamergedclick} — a Cancel gives back
 * only the order's own share of a request it merged into, and the player's click still gets exactly its items, for
 * the craft order and for the clipboard order alike;</li>
 * <li>{@code terminalcraftcancelafterthepickdeliversthemergedclick} — the same after the crane has picked the whole
 * merged amount: it delivers all of it, the click completes and the rest stays in the buffer;</li>
 * <li>{@code terminallistcancelkeepsaproductionbackedmergedclick}, {@code terminallistshortfallisthelistsnottheclicks}
 * — a share production was making for is given back with its promise, and production that falls short is taken off
 * the order's own line: either way a click merged into the request keeps its amount;</li>
 * <li>{@code terminalcraftwaitswhenthebufferfillslater} — a buffer filled after the fetch started makes the order wait,
 * never refuse, and the first freed room finishes it;</li>
 * <li>{@code terminalcraftfetchsurvivesareload}, {@code terminalcraftfetchendswhentheterminalisbroken} — the order is
 * saved and finished after a reload, and it ends with its terminal;</li>
 * <li>{@code terminalcraftfetchandcraftendtoend} — ask, Yes, fetch and craft without ever leaving the screen.</li>
 * </ul>
 * <b>Item conservation</b> is asserted on every tick of every test ({@link ItemCensus}), with the crafting player
 * counted where one crafts. Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}, storage on the left rack
 * plane, the terminal on the right, and a creative motor wherever the crane really has to move. Tests that look at
 * requests before a crane could serve them build the aisle without a motor and place it later.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalCraftFetchGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 2400;
    /** A cake's milk buckets travel one per trip (they do not stack), so that test runs nine crane trips. */
    private static final int LONG_TIMEOUT_TICKS = 4800;
    /** Ticks the census and the buffer are watched while nothing more may arrive. */
    private static final int QUIET_TICKS = 40;
    /** Ticks a full buffer is watched for before it is emptied. */
    private static final int BLOCKED_TICKS = 60;
    private static final int FAR_AWAY_BLOCKS = 40;
    private static final int OTHER_CONTAINER_ID = 4242;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    /** A chest beside the aisle that a player empties the terminal's buffer into. */
    private static final BlockPos SPILL = new BlockPos(10, BASE_Y, 0);
    /** A terminal outside every aisle. */
    private static final BlockPos OUTSIDE_TERMINAL = new BlockPos(12, BASE_Y, 0);

    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey TABLE = ItemKey.of(Items.CRAFTING_TABLE);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey REDSTONE = ItemKey.of(Items.REDSTONE);
    private static final ItemKey PISTON = ItemKey.of(Items.PISTON);
    private static final ItemKey MILK = ItemKey.of(Items.MILK_BUCKET);
    private static final ItemKey SUGAR = ItemKey.of(Items.SUGAR);
    private static final ItemKey EGG = ItemKey.of(Items.EGG);
    private static final ItemKey WHEAT = ItemKey.of(Items.WHEAT);
    private static final ItemKey CAKE = ItemKey.of(Items.CAKE);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);

    /** Two planks one above the other: four sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANK, null, null, PLANK);
    /** Four planks in a square: a crafting table. */
    private static final CraftGrid<ItemKey> TABLE_GRID = CraftGrid.of(PLANK, PLANK, null, PLANK, PLANK);
    /** Planks in the eight ring cells: a chest. */
    private static final CraftGrid<ItemKey> CHEST_GRID = CraftGrid.of(PLANK, PLANK, PLANK, PLANK, null, PLANK, PLANK,
            PLANK, PLANK);
    /** Three planks, four cobblestone, an iron ingot and redstone: a piston, i.e. four different ingredients. */
    private static final CraftGrid<ItemKey> PISTON_GRID = CraftGrid.of(PLANK, PLANK, PLANK, COBBLE, IRON, COBBLE,
            COBBLE, REDSTONE, COBBLE);
    /** Three milk buckets, sugar, an egg, sugar, three wheat: a cake. A milk bucket takes a slot of its own. */
    private static final CraftGrid<ItemKey> CAKE_GRID = CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT,
            WHEAT);
    /** Nine diamonds: a block of diamond. The racks of the refusal test hold no diamond. */
    private static final CraftGrid<ItemKey> DIAMOND_BLOCK_GRID = CraftGrid.of(DIAMOND, DIAMOND, DIAMOND, DIAMOND,
            DIAMOND, DIAMOND, DIAMOND, DIAMOND, DIAMOND);
    /** One diamond: no crafting recipe matches it. */
    private static final CraftGrid<ItemKey> NO_RECIPE_GRID = CraftGrid.of(DIAMOND);

    private static final int PLANKS_PER_STICKS = 2;
    private static final int PLANKS_PER_TABLE = 4;
    private static final int PLANKS_PER_CHEST = 8;
    private static final int PLANKS_PER_PISTON = 3;
    private static final int COBBLE_PER_PISTON = 4;
    private static final int MILK_PER_CAKE = 3;
    private static final int SUGAR_PER_CAKE = 2;
    private static final int WHEAT_PER_CAKE = 3;
    private static final int PLANKS_IN_STOCK = 32;
    private static final int STACK = 64;
    private static final int PLANKS_PER_LOG = 4;
    private static final int LOGS_IN_STOCK = 8;

    private TerminalCraftFetchGameTests() {
    }

    // --- what a fetch brings ---------------------------------------------------------------------------------------

    /**
     * <b>Exactly what one craft needs.</b> Sticks x1 with an empty buffer: one line, two planks, one crane trip — and
     * then nothing more. The order ends {@code DONE}, the aisle has no request left for the terminal, and the grid it
     * locked while running is unlocked and still shows the recipe, so the player crafts what was fetched for.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchesExactAmounts(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(PLANKS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while sticks are fetched for"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, STICK_GRID, 1), TerminalCraftResult.STARTED, 1, STICK,
                            "sticks x1");
                    TerminalCraftOrderState state = terminal.craftOrderState();
                    helper.assertTrue(state.isOpen(), "the craft order runs");
                    helper.assertValueEqual(state.wanted(), (long) PLANKS_PER_STICKS, "for exactly two planks");
                    helper.assertValueEqual(state.crafts(), 1, "for one craft");
                    helper.assertValueEqual(state.cells(), STICK_GRID, "of the grid it was started for");
                    helper.assertTrue(state.locksGrid(), "which every screen here shows, locked");
                    helper.assertFalse(terminal.hasOpenClipboardOrder(), "no clipboard order was involved");
                    // Another screen adopts the locked grid and previews it without a hint of its own: it resolves the
                    // recipe the order fetches for (issue #24 review fix).
                    Optional<ResourceLocation> fetchedFor = menu.craftView().recipe();
                    helper.assertTrue(fetchedFor.isPresent(), "the Fetch resolved a recipe");
                    helper.assertValueEqual(terminal.craftOrderHint(STICK_GRID), fetchedFor,
                            "the order keeps the recipe it was started for");
                    helper.assertTrue(terminal.craftOrderHint(TABLE_GRID).isEmpty(), "and names it for its grid only");
                    Player other = playerAt(helper, aisle);
                    WarehouseTerminalMenu otherMenu = openMenu(other, terminal);
                    otherMenu.previewCraft(other, STICK_GRID, Optional.empty());
                    helper.assertValueEqual(otherMenu.craftView().recipe(), fetchedFor,
                            "a second screen's preview of the order's grid resolves the order's recipe");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(
                        aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_STICKS,
                        "exactly two planks arrived, and no more follow"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    assertNoRequests(helper, aisle, "a finished fetch");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK),
                            (long) (PLANKS_IN_STOCK - PLANKS_PER_STICKS), "the planks left the racks");
                    TerminalCraftOrderState state = terminal.craftOrderState();
                    helper.assertValueEqual(state.delivered(), (long) PLANKS_PER_STICKS, "the order counted them");
                    helper.assertFalse(state.locksGrid(), "a finished order unlocks the grid");
                    helper.assertValueEqual(state.cells(), STICK_GRID, "which still shows the recipe it fetched for");
                    helper.assertFalse(terminal.hasOpenListOrder(), "and the controller has nothing to look in on");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>xN is N crafts.</b> Crafting tables x4 bring sixteen planks in one order. Once they are there a second Fetch
     * for the same four finds nothing to fetch, names the result, and requests nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchesXN(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(PLANKS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while crafting tables are fetched for"));
        int tables = 4;

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, tables), TerminalCraftResult.STARTED,
                            tables, TABLE, "crafting tables x4");
                    helper.assertValueEqual(terminal.craftOrderState().wanted(), (long) (tables * PLANKS_PER_TABLE),
                            "the order wants sixteen planks");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                            (long) (tables * PLANKS_PER_TABLE), "sixteen planks arrived");
                    assertNoRequests(helper, aisle, "a finished fetch");
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, tables),
                            TerminalCraftResult.NOTHING_TO_FETCH, tables, TABLE, "the same Fetch again");
                    assertNoRequests(helper, aisle, "a Fetch with nothing to fetch");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.DONE,
                            "and the finished order is left as it was");
                })
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) (tables * PLANKS_PER_TABLE), "nothing more arrives"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>Topped up against the buffer, and against nothing else.</b> Five planks are already delivered and the player's
     * own click for four more is still on its way. Crafting tables x4 need sixteen: the fetch asks for eleven — the
     * five in the buffer count, the pending click does not, because that click is the player's and the owner decided
     * that only the buffer is subtracted. The portion merges into the click's request (ADR-020), the crane serves both
     * in one trip, the order is credited its eleven and the click keeps its four: twenty planks in the buffer.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchTopsUpTheBuffer(GameTestHelper helper) {
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(PLANKS_IN_STOCK));
        int inBuffer = 5;
        int clicked = 4;
        int tables = 4;
        int fetched = tables * PLANKS_PER_TABLE - inBuffer;
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a fetch tops the buffer up"));
        AtomicReference<UUID> click = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    seed(helper, terminal, conserved, PLANK.toStack(inBuffer));
                    Player player = playerAt(helper, aisle);
                    click.set(click(helper, terminal, player, PLANK, clicked));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, tables), TerminalCraftResult.STARTED,
                            tables, TABLE, "crafting tables x4 with five planks in the buffer");
                    helper.assertValueEqual(terminal.craftOrderState().wanted(), (long) fetched,
                            "the buffer's five are subtracted, the pending click is not");
                })
                .thenWaitUntil(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, PLANK);
                    helper.assertValueEqual(request.id(), click.get(), "the portion merged into the click");
                    helper.assertValueEqual(request.remaining(), clicked + fetched, "which now asks for both");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click and the fetch"))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) (inBuffer + clicked + fetched),
                        "the buffer's five, the click's four and the fetch's eleven"))
                .thenExecute(() -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.delivered(), (long) fetched,
                            "the order was credited exactly its own share of the merged delivery");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK),
                            (long) (PLANKS_IN_STOCK - clicked - fetched),
                            "and the racks gave exactly the click and the fetch");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>Many ingredients, two requests at a time.</b> A piston x2 needs six planks, eight cobblestone, two iron ingots
     * and two redstone: four lines. The order never has more than {@code terminalListOpenRequests} (2) of its portions
     * open, which is checked on every tick, and every ingredient arrives exactly.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchManyIngredients(GameTestHelper helper) {
        int stock = 32;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock), COBBLE.toStack(stock),
                IRON.toStack(stock), REDSTONE.toStack(stock));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock, COBBLE, stock, IRON, stock, REDSTONE, stock);
        AtomicInteger mostOpen = new AtomicInteger();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a piston's ingredients are fetched");
            aisle.controllerIfPresent().ifPresent(controller -> {
                int open = controller.requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).size();
                if (open > 2)
                    helper.fail("the craft order has " + open + " requests open at once, more than 2");
                mostOpen.accumulateAndGet(open, Math::max);
            });
        });
        int pistons = 2;

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(REDSTONE), (long) stock, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, PISTON_GRID, pistons), TerminalCraftResult.STARTED,
                            pistons, PISTON, "pistons x2");
                    helper.assertValueEqual(terminal.craftOrderState().wanted(),
                            (long) pistons * (PLANKS_PER_PISTON + COBBLE_PER_PISTON + 1 + 1),
                            "the order wants every ingredient of two pistons");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecuteFor(QUIET_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                            (long) pistons * PLANKS_PER_PISTON, "six planks");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, COBBLE),
                            (long) pistons * COBBLE_PER_PISTON, "eight cobblestone");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, IRON), (long) pistons, "two iron ingots");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, REDSTONE), (long) pistons,
                            "two redstone");
                })
                .thenExecute(() -> {
                    assertNoRequests(helper, aisle, "a finished fetch");
                    helper.assertValueEqual(mostOpen.get(), 2,
                            "the order did use both of its request slots, and never more");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- measured before anything is requested ---------------------------------------------------------------------

    /**
     * <b>Clamped to the buffer's room.</b> Cakes x4 would need twelve milk buckets, and a milk bucket takes a slot of
     * its own: the nine buffer slots hold the ingredients of two cakes exactly (six buckets, sugar, eggs, wheat). So
     * the terminal asks, with the room as the reason and x2 as what a Yes starts; nothing is requested by asking. The
     * Yes fetches two cakes' worth, filling every slot, and because the fetch was measured against the room the crane
     * never has to wait at the terminal ({@code WAITING_FOR_TARGET}), which is checked on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void terminalCraftFetchClampedToTheBufferRoom(GameTestHelper helper) {
        int cakes = 4;
        int fitting = 2;
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        List<ItemStack> milk = new ArrayList<>();
        for (int i = 0; i < cakes * MILK_PER_CAKE; i++)
            milk.add(MILK.toStack(1));
        milk.add(SUGAR.toStack(cakes * SUGAR_PER_CAKE));
        milk.add(EGG.toStack(cakes));
        milk.add(WHEAT.toStack(cakes * WHEAT_PER_CAKE));
        aisle.storage(STORAGE_A, milk.toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(MILK, cakes * MILK_PER_CAKE, SUGAR, cakes * SUGAR_PER_CAKE, EGG,
                cakes, WHEAT, cakes * WHEAT_PER_CAKE);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a cake's ingredients are fetched");
            if (aisle.dock().craneState().phase() == CranePhase.WAITING_FOR_TARGET)
                helper.fail("the crane waits at the terminal: the fetch was not measured against the buffer's room");
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(MILK), (long) cakes * MILK_PER_CAKE,
                            "the milk is in stock");
                    helper.assertValueEqual(aisle.controller().countOf(WHEAT), (long) cakes * WHEAT_PER_CAKE,
                            "and the rest");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(terminal.bufferSlots(), 9, "the terminal has its default nine slots");
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);

                    TerminalCraftOutcome asked = fetch(player, menu, CAKE_GRID, cakes);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, fitting, null, "cakes x4");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.asked(), cakes, "the question names the four asked for");
                    helper.assertValueEqual(question.stockRuns(), cakes, "the racks could give all four");
                    helper.assertValueEqual(question.roomRuns(), fitting, "but only two fit the buffer");
                    helper.assertValueEqual(question.possible(), fitting, "so a Yes fetches two");
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.ROOM, "because of the room");
                    helper.assertValueEqual(question.producing(), 0L, "and nothing would be made");
                    helper.assertFalse(terminal.hasCraftOrder(), "asking starts no order");
                    assertNoRequests(helper, aisle, "a question");

                    TerminalCraftOutcome yes = menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, CAKE_GRID, Optional.empty(), question.possible(), question.producing(),
                            true));
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, fitting, CAKE, "the Yes");
                    helper.assertValueEqual(terminal.craftOrderState().wanted(),
                            (long) fitting * (MILK_PER_CAKE + SUGAR_PER_CAKE + 1 + WHEAT_PER_CAKE),
                            "the order wants two cakes' ingredients");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, MILK), (long) fitting * MILK_PER_CAKE,
                            "six milk buckets");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, SUGAR), (long) fitting * SUGAR_PER_CAKE,
                            "four sugar");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, EGG), (long) fitting, "two eggs");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, WHEAT), (long) fitting * WHEAT_PER_CAKE,
                            "six wheat");
                    IItemHandler buffer = terminal.externalHandler();
                    for (int slot = 0; slot < buffer.getSlots(); slot++)
                        helper.assertFalse(buffer.getStackInSlot(slot).isEmpty(),
                                "every buffer slot is used, slot " + slot);
                    assertNoRequests(helper, aisle, "a finished fetch");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>The room counts every open request to the terminal.</b> The player clicked for eight stacks of cobblestone
     * that are still on their way (no motor), so of the nine buffer slots one is left. Crafting tables x32 need two
     * stacks of planks: the terminal asks, with the room as the reason, and offers x16 — one stack. Once the click is
     * gone the very same Fetch starts without a question.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchRoomCountsOpenRequests(GameTestHelper helper) {
        int planks = 2 * STACK;
        int cobble = 8 * STACK;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(STACK), PLANK.toStack(STACK),
                COBBLE.toStack(STACK), COBBLE.toStack(STACK), COBBLE.toStack(STACK), COBBLE.toStack(STACK),
                COBBLE.toStack(STACK), COBBLE.toStack(STACK), COBBLE.toStack(STACK), COBBLE.toStack(STACK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, planks, COBBLE, cobble);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the room is measured"));
        int tables = planks / PLANKS_PER_TABLE;
        int fitting = STACK / PLANKS_PER_TABLE;

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(COBBLE), (long) cobble, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    UUID clicked = click(helper, terminal, player, COBBLE, cobble);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);

                    TerminalCraftOutcome asked = fetch(player, menu, TABLE_GRID, tables);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, fitting, null,
                            "crafting tables x32 beside eight stacks on their way");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.stockRuns(), tables, "the racks could give all of them");
                    helper.assertValueEqual(question.roomRuns(), fitting, "but the click leaves room for one stack");
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.ROOM, "and the room says so");
                    helper.assertFalse(terminal.hasCraftOrder(), "asking starts no order");

                    // The click is given up: the room is the buffer's again, and the same Fetch is not asked about.
                    helper.assertTrue(aisle.controller().cancelRequest(clicked).isPresent(), "the click is cancelled");
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, tables), TerminalCraftResult.STARTED, tables,
                            TABLE, "the same Fetch without the click");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(requestFor(helper, aisle, PLANK).remaining(), planks,
                        "the order asks for both stacks"))
                .thenSucceed();
    }

    /**
     * <b>Every refusal leaves the aisle's requests as they were.</b> One player's click for emeralds is open (no
     * motor), and each refused Fetch is checked against that exact list: an empty grid, a grid no recipe matches, an
     * ingredient the racks do not hold at all (naming it, with 0 crafts as the hint — and with 1 when the buffer
     * already holds one craft and the Fetch could bring nothing beyond it), a buffer with no room for one craft, a
     * player out of reach, a screen that is not this player's open menu, a terminal outside every aisle, and a second
     * Fetch — from anyone — while an order runs.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftRefusedFetch(GameTestHelper helper) {
        int emeralds = 8;
        int clicked = 4;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(PLANKS_IN_STOCK), EMERALD.toStack(emeralds));
        helper.setBlock(SPILL, Blocks.CHEST);
        helper.setBlock(OUTSIDE_TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, Direction.NORTH));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK, EMERALD, emeralds);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while Fetches are refused"));
        AtomicReference<List<RetrievalRequest<ItemKey, BlockPos>>> before = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    click(helper, terminal, player, EMERALD, clicked);
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = openRequests(aisle);
                    helper.assertValueEqual(requests.size(), 1, "the click is open");
                    WarehouseTerminalMenu menu = openMenu(player, terminal);

                    assertOutcome(helper, fetch(player, menu, CraftGrid.empty(), 1), TerminalCraftResult.GRID_EMPTY,
                            0, null, "an empty grid");
                    assertUnchanged(helper, aisle, requests, "an empty grid");

                    assertOutcome(helper, fetch(player, menu, NO_RECIPE_GRID, 1), TerminalCraftResult.NO_RECIPE, 0,
                            null, "a grid without a recipe");
                    assertUnchanged(helper, aisle, requests, "a grid without a recipe");

                    TerminalCraftOutcome missing = fetch(player, menu, DIAMOND_BLOCK_GRID, 1);
                    assertOutcome(helper, missing, TerminalCraftResult.NOT_ENOUGH, 0, DIAMOND,
                            "an ingredient the racks do not hold");
                    helper.assertValueEqual(missing.question().orElseThrow().bound(), CraftFetchQuestion.Bound.STOCK,
                            "the stock is what binds");
                    assertUnchanged(helper, aisle, requests, "not enough");

                    // One block's diamonds are already delivered: x3 cannot bring a single craft more than that, so it
                    // is refused too, with the one craft the buffer holds as the hint.
                    seed(helper, terminal, conserved, DIAMOND.toStack(DIAMOND_BLOCK_GRID.occupiedCells()));
                    TerminalCraftOutcome nothingMore = fetch(player, menu, DIAMOND_BLOCK_GRID, 3);
                    assertOutcome(helper, nothingMore, TerminalCraftResult.NOT_ENOUGH, 1, DIAMOND,
                            "x3 with one craft in the buffer and no diamond in the racks");
                    helper.assertValueEqual(nothingMore.question().orElseThrow().ready(), 1,
                            "the buffer holds one craft");
                    assertUnchanged(helper, aisle, requests, "not enough beyond the buffer");
                    emptyBuffer(aisle, terminal);

                    // A buffer full of something else: not one crafting table's planks fit.
                    for (int slot = 0; slot < terminal.bufferSlots(); slot++)
                        seed(helper, terminal, conserved, COBBLE.toStack(STACK));
                    TerminalCraftOutcome full = fetch(player, menu, TABLE_GRID, 1);
                    assertOutcome(helper, full, TerminalCraftResult.NO_ROOM_FOR_ONE, 0, null, "a full buffer");
                    helper.assertValueEqual(full.question().orElseThrow().bound(), CraftFetchQuestion.Bound.ROOM,
                            "the room is what binds");
                    assertUnchanged(helper, aisle, requests, "no room for one craft");
                    emptyBuffer(aisle, terminal);

                    // Out of reach: the player's own menu, but far away; and a menu the player does not have open.
                    Player far = playerAt(helper, aisle);
                    WarehouseTerminalMenu farMenu = openMenu(far, terminal);
                    Vec3 away = far.position().add(FAR_AWAY_BLOCKS, 0, 0);
                    far.moveTo(away.x, away.y, away.z);
                    assertOutcome(helper, fetch(far, farMenu, TABLE_GRID, 1), TerminalCraftResult.OUT_OF_REACH, 0,
                            null, "a player far away");
                    Player stranger = playerAt(helper, aisle);
                    openMenu(stranger, terminal);
                    assertOutcome(helper, fetch(stranger, menu, TABLE_GRID, 1), TerminalCraftResult.OUT_OF_REACH, 0,
                            null, "somebody else's menu");
                    helper.assertTrue(WarehouseTerminalMenu.submitCraftOrder(player, TerminalCraftOrderPayload.fetch(
                            OTHER_CONTAINER_ID, TABLE_GRID, Optional.empty(), 1, 0L, false)).isEmpty(),
                            "a payload for a menu the player does not have open is dropped");
                    assertUnchanged(helper, aisle, requests, "out of reach");

                    // A terminal outside every aisle: crafting from its buffer works, fetching cannot.
                    Player outsider = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), OUTSIDE_TERMINAL);
                    WarehouseTerminalBlockEntity lonely = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                            .getNullable(helper.getLevel(), helper.absolutePos(OUTSIDE_TERMINAL));
                    if (lonely == null)
                        helper.fail("missing warehouse terminal outside the aisle");
                    WarehouseTerminalMenu lonelyMenu = openMenu(outsider, lonely);
                    assertOutcome(helper, fetch(outsider, lonelyMenu, TABLE_GRID, 1), TerminalCraftResult.NO_AISLE,
                            0, null, "a terminal outside every aisle");
                    assertUnchanged(helper, aisle, requests, "no aisle");

                    helper.assertFalse(terminal.hasCraftOrder(), "no refusal started an order");
                    assertOutcome(helper, fetch(player, menu, STICK_GRID, 1), TerminalCraftResult.STARTED, 1, STICK,
                            "sticks x1");
                })
                .thenWaitUntil(() -> {
                    requestFor(helper, aisle, PLANK);
                    before.set(openRequests(aisle));
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, 1), TerminalCraftResult.ALREADY_RUNNING, 0,
                            null, "a second Fetch while one runs");
                    Player other = playerAt(helper, aisle);
                    WarehouseTerminalMenu otherMenu = openMenu(other, terminal);
                    assertOutcome(helper, fetch(other, otherMenu, STICK_GRID, 1), TerminalCraftResult.ALREADY_RUNNING,
                            0, null, "a Fetch from another player while one runs");
                    assertUnchanged(helper, aisle, before.get(), "already running");
                    helper.assertValueEqual(terminal.craftOrderState().cells(), STICK_GRID,
                            "the running order keeps its recipe");
                    // The other direction of terminalcraftrunsbesideaclipboardorder: a running craft order never blocks
                    // the clipboard's Fetch, and only a second clipboard order is refused.
                    putClipboard(helper, terminal, clipboard(entry(EMERALD, 2)));
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.FETCH, 0L, 0L,
                            0L, RequestAcknowledgement.NONE).result(), TerminalListResult.STARTED,
                            "a running craft order never blocks the clipboard's Fetch");
                    helper.assertTrue(terminal.hasOpenCraftOrder() && terminal.hasOpenClipboardOrder(),
                            "both orders run");
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.FETCH, 0L, 0L,
                            0L, RequestAcknowledgement.NONE).result(), TerminalListResult.ALREADY_RUNNING,
                            "while a second clipboard order is refused");
                })
                .thenSucceed();
    }

    /**
     * <b>A stock rule's question during a fetch</b> (manual check 261). Oak Planks keep a reserve of 32 and the racks
     * hold 40; chests x3 need 24. The Fetch itself starts without a question — a player is never held back by a
     * reserve — but the portion that would go below it stops the order and asks, requesting nothing. "Show it again"
     * and a Yes to another question change nothing; a reload brings the order back running, and it asks again; No
     * parks it, Resume asks once more, and the real Yes fetches exactly 24, leaving 16 in the racks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftOrderPortionQuestionIsShownAgainAndCanBeRefused(GameTestHelper helper) {
        int stock = 40;
        int chests = 3;
        int fetched = chests * PLANKS_PER_CHEST;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock));
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a fetch asks about a reserve"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, stock))
                .thenExecute(() -> reserve(aisle, 0, PLANK, 32))
                .thenWaitUntil(() -> assertReserveKnown(helper, aisle, PLANK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, CHEST_GRID, chests), TerminalCraftResult.STARTED, chests,
                            CHEST, "chests x3 against a reserve: no question at the Fetch");
                })
                .thenWaitUntil(() -> assertCraftAsking(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    RequestConfirmation<ItemKey> question = terminal.craftQuestion().orElseThrow();
                    helper.assertValueEqual(question.key(), PLANK, "the question names the planks");
                    helper.assertValueEqual(question.amount(), (long) fetched, "and the portion's 24");
                    helper.assertTrue(question.fromReserve() > 0L, "which go below the reserve");
                    helper.assertTrue(question.required(RequestScope.CRAFT), "so the craft order has to ask");
                    assertNoRequests(helper, aisle, "a portion's question");

                    // "Show it to me again" changes nothing.
                    assertOutcome(helper, menu.craftOrderAction(player,
                            TerminalCraftOrderPayload.showQuestion(menu.containerId)), TerminalCraftResult.ASKING, 0,
                            null, "an answer that accepts nothing");
                    // Nor does a Yes to a question the order is not asking.
                    RequestConfirmation<ItemKey> other = new RequestConfirmation<>(PLANK, question.amount() - 1L,
                            question.fromReserve(), question.reserved(), question.pastMaximum(), question.made(),
                            question.maximum(), question.ingredients());
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.answer(
                            menu.containerId, other, other.acknowledgement(RequestScope.CRAFT))),
                            TerminalCraftResult.ASKING, 0, null, "a Yes to another question");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.ASKING,
                            "the order still asks");
                    helper.assertValueEqual(terminal.craftQuestion(), Optional.of(question), "the same question");

                    // A reload while asking: the question was never saved, the order comes back running.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag saved = terminal.saveWithoutMetadata(registries);
                    WarehouseTerminalBlockEntity loaded = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                            .create(terminal.getBlockPos(), terminal.getBlockState());
                    if (loaded == null)
                        helper.fail("could not create a detached warehouse terminal");
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.craftOrderState().state(), ListOrderState.RUNNING,
                            "an asking order is saved as a running one");
                    helper.assertTrue(loaded.craftQuestion().isEmpty(), "without its question");
                    terminal.loadWithComponents(saved, registries);
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.RUNNING,
                            "and the live terminal reads it back the same way");
                })
                .thenWaitUntil(() -> assertCraftAsking(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.DECLINE)), TerminalCraftResult.DECLINED, 0, null, "No");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.PARKED,
                            "a no parks the order");
                    helper.assertTrue(terminal.craftQuestion().isEmpty(), "and the question is gone");
                    helper.assertTrue(terminal.craftOrderState().refusal().isEmpty(),
                            "a parked order names no refusal: the no parked it");
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.DECLINE)), TerminalCraftResult.NOT_RUNNING, 0, null,
                            "a second No");
                    assertNoRequests(helper, aisle, "a parked order");
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.RESUME)), TerminalCraftResult.RESUMED, 0, null,
                            "Resume");
                })
                .thenWaitUntil(() -> assertCraftAsking(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    RequestConfirmation<ItemKey> question = terminal.craftQuestion().orElseThrow();
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.answer(
                            menu.containerId, question, question.acknowledgement(RequestScope.CRAFT))),
                            TerminalCraftResult.ANSWERED, 0, null, "the Yes");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) fetched, "exactly the 24 the Yes agreed to"))
                .thenExecute(() -> {
                    assertNoRequests(helper, aisle, "a finished fetch");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK), (long) (stock - fetched),
                            "and 16 stay in the racks, below the reserve that was agreed to");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>A stale Yes is never credited to the next question</b> (issue #24 review fix). Two screens are open at one
     * terminal, and planks and cobblestone both keep a reserve a piston x2 goes below. Screen A answers the planks'
     * question; the order moves on and asks about the cobblestone without leaving {@code ASKING}. Screen B still shows
     * the planks' question and says Yes to it: the terminal does not take it — the cobblestone's question is still
     * asked, and the order stands still — and only the Yes to the cobblestone's own question moves it on.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftStaleAnswerIsNotCreditedToTheNextQuestion(GameTestHelper helper) {
        int stock = 10;
        int pistons = 2;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(stock), COBBLE.toStack(stock),
                IRON.toStack(stock), REDSTONE.toStack(stock));
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock, COBBLE, stock, IRON, stock, REDSTONE, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while two screens answer"));
        AtomicReference<RequestConfirmation<ItemKey>> first = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(REDSTONE), (long) stock, "the stock is in");
                })
                .thenExecute(() -> {
                    reserve(aisle, 0, PLANK, 8);
                    reserve(aisle, 1, COBBLE, 8);
                })
                .thenWaitUntil(() -> {
                    assertReserveKnown(helper, aisle, PLANK);
                    assertReserveKnown(helper, aisle, COBBLE);
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, PISTON_GRID, pistons), TerminalCraftResult.STARTED,
                            pistons, PISTON, "pistons x2");
                })
                .thenWaitUntil(() -> assertCraftAsking(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    RequestConfirmation<ItemKey> planks = terminal.craftQuestion().orElseThrow();
                    helper.assertValueEqual(planks.key(), PLANK, "the planks are asked about first");
                    first.set(planks);
                    Player a = playerAt(helper, aisle);
                    WarehouseTerminalMenu screenA = openMenu(a, terminal);
                    assertOutcome(helper, screenA.craftOrderAction(a, TerminalCraftOrderPayload.answer(
                            screenA.containerId, planks, planks.acknowledgement(RequestScope.CRAFT))),
                            TerminalCraftResult.ANSWERED, 0, null, "screen A's Yes to the planks");
                })
                .thenWaitUntil(() -> {
                    assertCraftAsking(helper, aisle);
                    helper.assertValueEqual(terminal(aisle).craftQuestion().orElseThrow().key(), COBBLE,
                            "the order has moved on to the cobblestone");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    RequestConfirmation<ItemKey> cobble = terminal.craftQuestion().orElseThrow();
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = openRequests(aisle);
                    Player b = playerAt(helper, aisle);
                    WarehouseTerminalMenu screenB = openMenu(b, terminal);
                    RequestConfirmation<ItemKey> stale = first.get();
                    assertOutcome(helper, screenB.craftOrderAction(b, TerminalCraftOrderPayload.answer(
                            screenB.containerId, stale, stale.acknowledgement(RequestScope.CRAFT))),
                            TerminalCraftResult.ASKING, 0, null, "screen B's Yes to the planks' old question");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.ASKING,
                            "is not taken: the order still asks");
                    helper.assertValueEqual(terminal.craftQuestion(), Optional.of(cobble),
                            "about the cobblestone, which nobody has said Yes to");
                    assertUnchanged(helper, aisle, requests, "a stale Yes");
                    assertOutcome(helper, screenB.craftOrderAction(b, TerminalCraftOrderPayload.answer(
                            screenB.containerId, cobble, cobble.acknowledgement(RequestScope.CRAFT))),
                            TerminalCraftResult.ANSWERED, 0, null, "screen B's Yes to the cobblestone");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(requestFor(helper, aisle, COBBLE).remaining(),
                        pistons * COBBLE_PER_PISTON, "the cobblestone is requested once it was agreed to"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.CANCEL)), TerminalCraftResult.CANCELLED, 0, null,
                            "the fetch is given up");
                    assertNoRequests(helper, aisle, "a cancelled fetch");
                })
                .thenSucceed();
    }

    /**
     * <b>A refused portion is named, and forgotten again</b> (issue #24). Chests x1 are fetched for while the racks hold
     * exactly eight planks; in the same tick a warehouse port asks for all eight, so the order's portion is refused
     * {@code NOT_IN_STOCK}. The order remembers that while it stands still: the state names the planks, the payload
     * carries it, a save does not. Once the port's request is cancelled the next portion is granted and the refusal
     * is gone; the order finishes with exactly eight planks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftOrderNamesItsRefusal(GameTestHelper helper) {
        int stock = PLANKS_PER_CHEST;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(stock));
        aisle.output(OUTPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a refused portion is named"));
        AtomicReference<UUID> port = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 2);
                    helper.assertValueEqual(aisle.controller().countOf(PLANK), (long) stock, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, CHEST_GRID, 1), TerminalCraftResult.STARTED, 1, CHEST,
                            "a chest x1");
                    RequestResult taken = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK, stock);
                    helper.assertValueEqual(taken.granted(), stock, "a port takes every plank first");
                    port.set(taken.request().orElseThrow().id());
                })
                .thenWaitUntil(() -> helper.assertTrue(terminal(aisle).craftOrderState().refusal().isPresent(),
                        "the order names why its portion was refused"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    TerminalCraftOrderState state = terminal.craftOrderState();
                    TerminalPortionRefusal refusal = state.refusal().orElseThrow();
                    helper.assertValueEqual(refusal.rejection(), RequestRejection.NOT_IN_STOCK, "nothing left of it");
                    helper.assertValueEqual(refusal.about(), PLANK, "about the planks");
                    helper.assertValueEqual(state.state(), ListOrderState.RUNNING, "the order waits, it is not parked");
                    helper.assertValueEqual(state.inFlight(), 0L, "with nothing on its way");
                    // Through the real codec, as every screen at the terminal gets it.
                    helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper,
                            new TerminalCraftOrderStatePayload(3, state), TerminalCraftOrderStatePayload.STREAM_CODEC)
                            .state(), state, "the state with its refusal crosses the wire");
                    // Never saved: a reloaded order finds out again with its next portion.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity loaded = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                            .create(terminal.getBlockPos(), terminal.getBlockState());
                    if (loaded == null)
                        helper.fail("could not create a detached warehouse terminal");
                    loaded.loadWithComponents(terminal.saveWithoutMetadata(registries), registries);
                    helper.assertTrue(loaded.craftOrderState().isOpen(), "the reloaded order is open");
                    helper.assertTrue(loaded.craftOrderState().refusal().isEmpty(), "and names no refusal");
                })
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(
                        terminal(aisle).craftOrderState().refusal().map(TerminalPortionRefusal::about),
                        Optional.of(PLANK), "a pass that is refused again keeps naming it"))
                .thenExecute(() -> helper.assertTrue(aisle.controller().cancelRequest(port.get()).isPresent(),
                        "the port's request is given up"))
                .thenWaitUntil(() -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(state.inFlight(), (long) stock, "the planks are requested");
                    helper.assertTrue(state.refusal().isEmpty(), "and the grant forgot the refusal");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecute(() -> {
                    helper.assertTrue(terminal(aisle).craftOrderState().refusal().isEmpty(),
                            "a finished order names no refusal");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) stock,
                            "and exactly the chest's eight planks arrived");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>Too little in stock asks first.</b> Chests x4 need 32 planks and the racks hold 20, enough for two: the
     * terminal asks, naming the planks, the numbers and x2 — and nothing is requested, promised or delivered by asking.
     * A Yes that no longer matches the warehouse (x3, or the original x4) is not obeyed but asked about again
     * ({@code CHANGED}); the real Yes for x2 fetches sixteen planks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftPartialStockAsksFirst(GameTestHelper helper) {
        int stock = 20;
        int chests = 4;
        int possible = stock / PLANKS_PER_CHEST;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a short fetch is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, stock))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    TerminalCraftOutcome asked = fetch(player, menu, CHEST_GRID, chests);
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, possible, PLANK, "chests x4");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    helper.assertValueEqual(question.asked(), chests, "it names the four asked for");
                    helper.assertValueEqual(question.possible(), possible, "and the two there are");
                    helper.assertValueEqual(question.bound(), CraftFetchQuestion.Bound.STOCK, "because of the stock");
                    helper.assertValueEqual(question.named().size(), 1, "one item falls short");
                    CraftFetchQuestion.Line<ItemKey> line = question.named().getFirst();
                    helper.assertValueEqual(line.key(), PLANK, "the planks");
                    helper.assertValueEqual(line.needed(), (long) chests * PLANKS_PER_CHEST, "32 needed");
                    helper.assertValueEqual(line.inStock(), (long) stock, "20 in stock");
                    helper.assertFalse(terminal.hasCraftOrder(), "asking starts no order");
                    assertNoRequests(helper, aisle, "a question");
                    helper.assertValueEqual(aisle.controller().availableStock(PLANK), (long) stock,
                            "and promises nothing");
                })
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L,
                        "a question delivers nothing"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    for (int stale : new int[] {possible + 1, chests}) {
                        TerminalCraftOutcome changed = menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                                menu.containerId, CHEST_GRID, Optional.empty(), stale, 0L, true));
                        helper.assertValueEqual(changed.result(), TerminalCraftResult.CHANGED,
                                "a Yes for x" + stale + " is asked about again");
                        helper.assertValueEqual(changed.question().orElseThrow().possible(), possible,
                                "with the numbers as they are");
                        helper.assertFalse(terminal.hasCraftOrder(), "and starts nothing");
                    }
                    assertNoRequests(helper, aisle, "a stale Yes");
                    TerminalCraftOutcome yes = menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, CHEST_GRID, Optional.empty(), possible, 0L, true));
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, possible, CHEST, "the Yes for x2");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) possible * PLANKS_PER_CHEST, "what there is really arrives, and only that"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- beside the clipboard order --------------------------------------------------------------------------------

    /**
     * <b>Two orders at one terminal, one merged request.</b> A clipboard asks for five planks and three diamonds, chests
     * x8 are fetched for at the same time: the clipboard's plank portion and the craft order's 64 merge into one
     * request of 69 (ADR-020), more than the crane carries at once. Its first trip brings 64, all of them the craft
     * order's, which is credited first; the second brings the clipboard's five. On every tick the two orders together
     * have claimed no more than has really arrived in the buffer — the check that fails at once if one delivery were
     * handed to both — and both finish, neither cancelled, with both clipboard entries ticked off.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftRunsBesideAClipboardOrder(GameTestHelper helper) {
        int diamonds = 16;
        int listedPlanks = 5;
        int listedDiamonds = 3;
        int chests = 8;
        int planks = 2 * STACK;
        int fetched = chests * PLANKS_PER_CHEST;
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A, PLANK.toStack(STACK), PLANK.toStack(STACK));
        aisle.storage(STORAGE_B, DIAMOND.toStack(diamonds));
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, planks, DIAMOND, diamonds);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while two orders share a request");
            WarehouseTerminalBlockEntity terminal = aisle.controllerIfPresent().isPresent() ? terminal(aisle) : null;
            if (terminal == null)
                return;
            long claimed = terminal.craftOrderState().delivered() + terminal.listState().delivered();
            long arrived = aisle.stationCount(TERMINAL_RACK, PLANK) + aisle.stationCount(TERMINAL_RACK, DIAMOND);
            if (claimed > arrived)
                helper.fail("the two orders claimed " + claimed + " items, but only " + arrived + " arrived");
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) diamonds, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    putClipboard(helper, terminal,
                            clipboard(entry(PLANK, listedPlanks), entry(DIAMOND, listedDiamonds)));
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.FETCH, 0L, 0L, 0L,
                            RequestAcknowledgement.NONE).result(), TerminalListResult.STARTED, "the clipboard starts");
                    assertOutcome(helper, fetch(player, menu, CHEST_GRID, chests), TerminalCraftResult.STARTED,
                            chests, CHEST, "a running clipboard order never blocks a Fetch");
                    helper.assertTrue(terminal.hasOpenClipboardOrder(), "the clipboard order runs");
                    helper.assertTrue(terminal.hasOpenCraftOrder(), "and so does the craft order");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(requestFor(helper, aisle, PLANK).requested(),
                        listedPlanks + fetched, "both plank portions merged into one request"))
                // The first trip: one crane load of the 69, all of it the craft order's, which is credited first.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) STACK,
                        "the first load of planks is in"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(terminal.craftOrderState().delivered(), (long) fetched,
                            "the craft order was credited its 64 first");
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.DONE,
                            "and is done");
                    helper.assertFalse(terminal.listState().entriesComplete() == 2,
                            "while the clipboard's planks are still to come");
                })
                .thenWaitUntil(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.DONE,
                            "the craft order is done");
                    helper.assertValueEqual(terminal.listState().state(), ListOrderState.DONE,
                            "and so is the clipboard order");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    TerminalCraftOrderState craft = terminal.craftOrderState();
                    TerminalListState list = terminal.listState();
                    helper.assertValueEqual(craft.delivered(), (long) fetched, "the craft order got its 64");
                    helper.assertValueEqual(list.delivered(), (long) (listedPlanks + listedDiamonds),
                            "the clipboard its five planks and three diamonds");
                    helper.assertValueEqual(list.entriesComplete(), 2, "both entries are complete");
                    for (ListEntry<ItemKey> read : ClipboardList.read(terminal.listClipboard()))
                        helper.assertTrue(read.checked(), "entry " + read.index() + " is ticked off");
                    helper.assertTrue(terminal.hasCraftOrder() && terminal.hasListOrder(),
                            "neither order was cancelled");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                            (long) (listedPlanks + fetched), "69 planks are in the buffer");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) listedDiamonds,
                            "and three diamonds");
                    assertNoRequests(helper, aisle, "two finished orders");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>Cancel gives back only the order's own share.</b> The player clicked for three planks, then fetched sticks x2
     * (four planks), which merged into the click's request: seven. The crane is powered and plans one trip for the
     * seven; before it picks, the craft order is cancelled. The request keeps its id and its queue place with the
     * click's three, and exactly three planks arrive — the four of the cancelled order stay in the racks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftCancelKeepsAMergedClick(GameTestHelper helper) {
        int clicked = 3;
        int sticks = 2;
        int fetched = sticks * PLANKS_PER_STICKS;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(PLANKS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a merged craft portion is cancelled");
            if (aisle.stationCount(TERMINAL_RACK, PLANK) > clicked)
                helper.fail("the cancelled order's planks were delivered after all: "
                        + aisle.stationCount(TERMINAL_RACK, PLANK));
        });
        AtomicReference<UUID> click = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    click.set(click(helper, terminal, player, PLANK, clicked));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, STICK_GRID, sticks), TerminalCraftResult.STARTED, sticks,
                            STICK, "sticks x2");
                })
                .thenWaitUntil(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, PLANK);
                    helper.assertValueEqual(request.id(), click.get(), "the portion merged into the click");
                    helper.assertValueEqual(request.remaining(), clicked + fetched, "seven planks");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertPlannedNotPicked(helper, aisle, click.get(), clicked + fetched))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.CANCEL)), TerminalCraftResult.CANCELLED, 0, null,
                            "the Cancel");
                    helper.assertFalse(terminal.hasCraftOrder(), "the order is gone");
                    assertClickKept(helper, aisle, PLANK, click.get(), clicked);
                    helper.assertValueEqual(aisle.controller().availableStock(PLANK),
                            (long) (PLANKS_IN_STOCK - clicked), "and only the click's planks stay promised");
                })
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click, delivered"))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) clicked, "exactly the click's three planks arrived"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK),
                        (long) (PLANKS_IN_STOCK - clicked), "the cancelled order's planks are still in the racks"))
                .thenSucceed();
    }

    /**
     * <b>The same for the clipboard order</b> (issue #24, the shipped fix). Before it, giving a clipboard order up
     * cancelled every request it had a portion in, and a player's own click that the portion had merged into went down
     * with it. The player clicked for three diamonds, a clipboard asks for five more: one request of eight. The crane
     * plans the trip, the clipboard order is cancelled before the pick, and the click keeps its id and its three —
     * which is exactly what arrives — while the clipboard's entry stays unticked.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListCancelKeepsAMergedClick(GameTestHelper helper) {
        int diamonds = 16;
        int clicked = 3;
        int listed = 5;
        AisleFixture aisle = stockedAisle(helper, false, DIAMOND.toStack(diamonds));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, diamonds);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a merged clipboard portion is cancelled");
            if (aisle.stationCount(TERMINAL_RACK, DIAMOND) > clicked)
                helper.fail("the cancelled clipboard's diamonds were delivered after all: "
                        + aisle.stationCount(TERMINAL_RACK, DIAMOND));
        });
        AtomicReference<UUID> click = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, DIAMOND, diamonds))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    click.set(click(helper, terminal, player, DIAMOND, clicked));
                    putClipboard(helper, terminal, clipboard(entry(DIAMOND, listed)));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.FETCH, 0L, 0L, 0L,
                            RequestAcknowledgement.NONE).result(), TerminalListResult.STARTED, "the clipboard starts");
                })
                .thenWaitUntil(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, DIAMOND);
                    helper.assertValueEqual(request.id(), click.get(), "the portion merged into the click");
                    helper.assertValueEqual(request.remaining(), clicked + listed, "eight diamonds");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertPlannedNotPicked(helper, aisle, click.get(), clicked + listed))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.CANCEL, 0L, 0L,
                            0L, RequestAcknowledgement.NONE).result(), TerminalListResult.CANCELLED, "the Cancel");
                    helper.assertFalse(terminal.hasListOrder(), "the order is gone");
                    assertClickKept(helper, aisle, DIAMOND, click.get(), clicked);
                })
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click, delivered"))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) clicked, "exactly the click's three diamonds arrived"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    helper.assertFalse(ClipboardList.read(terminal(aisle).listClipboard()).getFirst().checked(),
                            "the clipboard's entry stays unticked: its order was cancelled");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, DIAMOND), (long) (diamonds - clicked),
                            "and its diamonds are still in the racks");
                })
                .thenSucceed();
    }

    /**
     * <b>A cancel after the pick delivers the merged trip.</b> The player clicked for three planks and fetched sticks
     * x2, which merged into the click's request: seven. The crane picks all seven, and only then is the craft order
     * cancelled. The request keeps the click's three; the crane's job is neither cancelled nor rerouted, it delivers
     * what it carries, the click's request completes, and the four the order no longer wants stay in the buffer —
     * there is no way back for what the crane had already taken.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftCancelAfterThePickDeliversTheMergedClick(GameTestHelper helper) {
        int clicked = 3;
        int sticks = 2;
        int fetched = sticks * PLANKS_PER_STICKS;
        AisleFixture aisle = stockedAisle(helper, false, PLANK.toStack(PLANKS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a merged trip is cancelled after the pick");
            if (aisle.stationCount(TERMINAL_RACK, PLANK) > clicked + fetched)
                helper.fail("more planks arrived than the crane had picked: "
                        + aisle.stationCount(TERMINAL_RACK, PLANK));
        });
        AtomicReference<UUID> click = new AtomicReference<>();
        AtomicReference<UUID> trip = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    click.set(click(helper, terminal, player, PLANK, clicked));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, STICK_GRID, sticks), TerminalCraftResult.STARTED, sticks,
                            STICK, "sticks x2");
                })
                .thenWaitUntil(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, PLANK);
                    helper.assertValueEqual(request.id(), click.get(), "the portion merged into the click");
                    helper.assertValueEqual(request.remaining(), clicked + fetched, "seven planks");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> {
                    TransportJob<ItemKey, RackPosition> job = aisle.dock().currentJob().orElse(null);
                    helper.assertTrue(job != null && job.requestId().equals(Optional.of(click.get())) && job.picked()
                            && job.heldAmount() == clicked + fetched, "the crane carries the merged seven");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L, "and has not dropped them");
                    trip.set(job.id());
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, menu.craftOrderAction(player, TerminalCraftOrderPayload.of(menu.containerId,
                            TerminalCraftOrderPayload.Action.CANCEL)), TerminalCraftResult.CANCELLED, 0, null,
                            "the Cancel after the pick");
                    helper.assertFalse(terminal.hasCraftOrder(), "the order is gone");
                    assertClickKept(helper, aisle, PLANK, click.get(), clicked);
                    TransportJob<ItemKey, RackPosition> job = aisle.dock().currentJob().orElse(null);
                    helper.assertTrue(job != null && job.id().equals(trip.get())
                            && job.heldAmount() == clicked + fetched,
                            "the crane keeps the job it picked for, with all seven: nothing is rerouted");
                })
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click, completed by the trip"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) (clicked + fetched), "everything carried was delivered, the order's four included"))
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK),
                        (long) (PLANKS_IN_STOCK - clicked - fetched), "and only those seven left the racks"))
                .thenSucceed();
    }

    /**
     * <b>A share production was making is given back with its promise</b> (issue #24 review fix). The racks hold the
     * two planks the player clicks for; a clipboard asks for four more, which a log has to be made into, and its
     * portion merges into the click's request: six, four of them promised by a production order. The crane has no
     * power. The clipboard is given up — the request keeps the click's two — and then the production order is
     * cancelled from the production list. Before the fix it still owed the request its four and took them off,
     * deleting the click's two with them; now nothing more is taken, and once the crane has power exactly the click's
     * two planks arrive.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListCancelKeepsAProductionBackedMergedClick(GameTestHelper helper) {
        int clicked = 2;
        int listed = PLANKS_PER_LOG;
        AisleFixture aisle = productionAisle(helper, PLANK.toStack(clicked), LOG.toStack(LOGS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, clicked, LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                "while a share production was making is given back"));
        AtomicReference<UUID> click = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertProductionReady(helper, aisle))
                .thenExecute(() -> click.set(startMadeListBesideAClick(helper, aisle, clicked, listed)))
                .thenWaitUntil(() -> assertMadePortionMerged(helper, aisle, click.get(), clicked + listed))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.CANCEL, 0L, 0L,
                            0L, RequestAcknowledgement.NONE).result(), TerminalListResult.CANCELLED,
                            "the clipboard is given up");
                    assertClickKept(helper, aisle, PLANK, click.get(), clicked);
                    ProductionOrder<ItemKey, RackPosition> made = aisle.controller().openProductionOrders()
                            .getFirst();
                    helper.assertValueEqual(made.unfulfilledPromise(), 0L,
                            "the production promise went with the share");
                    helper.assertTrue(menu.cancel(player, made.id()), "the production order is cancelled");
                    assertClickKept(helper, aisle, PLANK, click.get(), clicked);
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click, delivered"))
                .thenExecuteFor(QUIET_TICKS, () -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) clicked, "exactly the click's two planks arrived"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>Production that falls short is the order's loss, not the click's</b> (issue #24 review fix). The same merged
     * request of six, four of them to be made for the clipboard's portion. This time the production order is cancelled
     * while the clipboard still runs: the request loses the four that will never be made, and the clipboard's line
     * takes them off its own amount. When the crane then delivers the click's two planks they are the click's — the
     * clipboard is credited nothing — and its line is left to ask for its four again. Before the fix the line still
     * claimed the whole portion and was credited the click's planks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalListShortfallIsTheListsNotTheClicks(GameTestHelper helper) {
        int clicked = 2;
        int listed = PLANKS_PER_LOG;
        AisleFixture aisle = productionAisle(helper, PLANK.toStack(clicked), LOG.toStack(LOGS_IN_STOCK));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, clicked, LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while production falls short of a merged request");
            if (aisle.controllerIfPresent().isPresent() && terminal(aisle).listState().delivered() > 0L)
                helper.fail("the clipboard was credited the click's planks");
        });
        AtomicReference<UUID> click = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertProductionReady(helper, aisle))
                .thenExecute(() -> click.set(startMadeListBesideAClick(helper, aisle, clicked, listed)))
                .thenWaitUntil(() -> assertMadePortionMerged(helper, aisle, click.get(), clicked + listed))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    ProductionOrder<ItemKey, RackPosition> made = aisle.controller().openProductionOrders()
                            .getFirst();
                    helper.assertTrue(menu.cancel(player, made.id()), "the production order is cancelled");
                    assertClickKept(helper, aisle, PLANK, click.get(), clicked);
                    helper.assertValueEqual(terminal.listState().inFlight(), 0L,
                            "and the clipboard's line took the shortfall off its own amount");
                    helper.assertTrue(terminal.hasOpenClipboardOrder(), "the clipboard order runs on");
                })
                .thenExecute(aisle::placeMotor)
                .thenWaitUntil(() -> assertNoRequests(helper, aisle, "the click, delivered"))
                .thenExecuteFor(QUIET_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) clicked,
                            "the click's two planks arrived");
                    helper.assertValueEqual(terminal(aisle).listState().delivered(), 0L, "and are the click's");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.CANCEL, 0L, 0L,
                            0L, RequestAcknowledgement.NONE).result(), TerminalListResult.CANCELLED,
                            "the clipboard is given up at the end");
                    assertNoRequests(helper, aisle, "everything given up");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- room taken later, reloads, a broken terminal ------------------------------------------------------------

    /**
     * <b>Room taken later waits, it never refuses.</b> The fetch was measured against an empty buffer; right after it
     * started, the buffer is filled to the last slot. Nothing is delivered while it stays full, the order stays open
     * and names no refusal, the planks stay in the racks — and the moment the player empties the buffer the crane
     * finishes the order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftWaitsWhenTheBufferFillsLater(GameTestHelper helper) {
        int tables = 4;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(PLANKS_IN_STOCK));
        helper.setBlock(SPILL, Blocks.CHEST);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a fetch waits for room"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, TABLE_GRID, tables), TerminalCraftResult.STARTED, tables,
                            TABLE, "crafting tables x4 into an empty buffer");
                    // Room taken after the measurement: a delivery, a craft's remainders, anything.
                    for (int slot = 0; slot < terminal.bufferSlots(); slot++)
                        seed(helper, terminal, conserved, COBBLE.toStack(STACK));
                    helper.assertValueEqual(terminal.insert(PLANK.toStack(1), true).getCount(), 1,
                            "nothing fits any more");
                })
                .thenExecuteFor(BLOCKED_TICKS, () -> {
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L,
                            "nothing is delivered while the buffer is full");
                    helper.assertTrue(state.isOpen(), "the order waits instead of giving up");
                    helper.assertValueEqual(state.state(), ListOrderState.RUNNING, "it is neither parked nor asking");
                    helper.assertTrue(state.refusal().isEmpty(), "and nothing was refused");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK), (long) PLANKS_IN_STOCK,
                            "the planks wait in the racks, not on the crane");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(requestFor(helper, aisle, PLANK).remaining(), tables * PLANKS_PER_TABLE,
                            "the request is still open for all sixteen");
                    emptyBuffer(aisle, terminal(aisle));
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) tables * PLANKS_PER_TABLE,
                            "the freed room finished the order");
                    assertNoRequests(helper, aisle, "a finished fetch");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>A reload mid-fetch.</b> A piston x1 is fetched; once its planks are in, the terminal is saved, read back into
     * a detached copy and into the live block entity, exactly as a chunk load does it. The copy has the same order —
     * its grid, crafts, amounts and progress — and the restored live order fetches the rest, exactly.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchSurvivesAReload(GameTestHelper helper) {
        int stock = 16;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock), COBBLE.toStack(stock),
                IRON.toStack(stock), REDSTONE.toStack(stock));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock, COBBLE, stock, IRON, stock, REDSTONE, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a craft order is reloaded"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(REDSTONE), (long) stock, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, PISTON_GRID, 1), TerminalCraftResult.STARTED, 1, PISTON,
                            "a piston x1");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) PLANKS_PER_PISTON, "the planks are in"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    TerminalCraftOrderState before = terminal.craftOrderState();
                    helper.assertTrue(before.isOpen(), "the order is still running when it is saved");
                    CompoundTag saved = terminal.saveWithoutMetadata(registries);

                    WarehouseTerminalBlockEntity loaded = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                            .create(terminal.getBlockPos(), terminal.getBlockState());
                    if (loaded == null)
                        helper.fail("could not create a detached warehouse terminal");
                    loaded.loadWithComponents(saved, registries);
                    assertSameOrder(helper, loaded.craftOrderState(), before, "the detached copy");

                    // The live terminal from the very same bytes, exactly as a chunk load does it.
                    terminal.loadWithComponents(saved, registries);
                    assertSameOrder(helper, terminal.craftOrderState(), before, "the live terminal");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecuteFor(QUIET_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_PISTON,
                            "three planks");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, COBBLE), (long) COBBLE_PER_PISTON,
                            "four cobblestone");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, IRON), 1L, "one iron ingot");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, REDSTONE), 1L, "one redstone");
                })
                .thenExecute(() -> assertNoRequests(helper, aisle, "a restored and finished fetch"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>The order ends with its terminal.</b> A piston x1 is half fetched — its planks are in, and the crane carries the
     * next ingredient — when the terminal is broken: the order is gone, so are its requests, the buffer's planks lie in
     * the world (counted by the census), the crane brings what it carries back to the racks, and nothing stays
     * promised or reserved.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchEndsWhenTheTerminalIsBroken(GameTestHelper helper) {
        int stock = 16;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock), COBBLE.toStack(stock),
                IRON.toStack(stock), REDSTONE.toStack(stock));
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, stock, COBBLE, stock, IRON, stock, REDSTONE, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a fetching terminal is broken"));
        AtomicReference<WarehouseTerminalBlockEntity> broken = new AtomicReference<>();
        AtomicReference<HeldItems> carried = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(REDSTONE), (long) stock, "the stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    assertOutcome(helper, fetch(player, menu, PISTON_GRID, 1), TerminalCraftResult.STARTED, 1, PISTON,
                            "a piston x1");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK),
                        (long) PLANKS_PER_PISTON, "the planks are in"))
                // Which ingredient comes next depends on the order of the requests, so any but the planks will do.
                .thenWaitUntil(() -> {
                    HeldItems held = aisle.dock().heldItems();
                    helper.assertTrue(held.totalCount() > 0 && held.count(PLANK) == 0,
                            "the crane carries the next ingredient");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(aisle);
                    helper.assertTrue(terminal.hasOpenCraftOrder(), "the order is still running");
                    helper.assertFalse(openRequests(aisle).isEmpty(), "and has requests open");
                    carried.set(aisle.dock().heldItems());
                    broken.set(terminal);
                    aisle.breakBlock(aisle.rackPos(TERMINAL_RACK));
                    helper.assertFalse(broken.get().hasCraftOrder(), "the order ended with its terminal");
                    helper.assertTrue(openRequests(aisle).isEmpty(), "and so did its requests");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "the aisle has none left");
                    helper.assertValueEqual(aisle.dock().heldItems(), carried.get(),
                            "the crane still holds what it carried: it takes it back, it drops nothing");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    for (ItemKey key : List.of(PLANK, COBBLE, IRON, REDSTONE))
                        helper.assertValueEqual(controller.availableStock(key), controller.countOf(key),
                                "nothing of " + key + " stays promised");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, PLANK), (long) (stock - PLANKS_PER_PISTON),
                            "only the delivered planks left the racks");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, COBBLE), (long) stock,
                            "everything else is back in them");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, IRON), (long) stock, "the iron too");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_A, REDSTONE), (long) stock, "and the redstone");
                })
                .thenSucceed();
    }

    // --- end to end ------------------------------------------------------------------------------------------------

    /**
     * <b>Fetch and craft without leaving the screen.</b> Only the two payload entry points a screen sends through:
     * crafting tables x4 against twelve planks in stock is asked about, the Yes fetches three tables' planks, and once
     * they are in a Shift-click on the result crafts all three into the player's inventory — all on the menu the player
     * has had open throughout, and all items counted on every tick, the player's included.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftFetchAndCraftEndToEnd(GameTestHelper helper) {
        int stock = 12;
        int tables = 4;
        int possible = stock / PLANKS_PER_TABLE;
        AisleFixture aisle = stockedAisle(helper, true, PLANK.toStack(stock));
        Player player = playerAt(helper, aisle);
        Map<ItemKey, Long> expected = ItemCensus.of(PLANK, stock);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a player fetches and crafts", player));
        AtomicReference<WarehouseTerminalMenu> screen = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, PLANK, stock))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, terminal(aisle));
                    screen.set(menu);
                    TerminalCraftOutcome asked = submitCraftOrder(helper, player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, TABLE_GRID, Optional.empty(), tables, 0L, false));
                    assertOutcome(helper, asked, TerminalCraftResult.ASKING, possible, PLANK, "crafting tables x4");
                    CraftFetchQuestion<ItemKey> question = asked.question().orElseThrow();
                    TerminalCraftOutcome yes = submitCraftOrder(helper, player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, TABLE_GRID, Optional.empty(), question.possible(), question.producing(),
                            true));
                    assertOutcome(helper, yes, TerminalCraftResult.STARTED, possible, TABLE, "the Yes");
                    TerminalCraftOrderState state = terminal(aisle).craftOrderState();
                    helper.assertTrue(state.locksGrid(), "the running order locks the grid");
                    helper.assertValueEqual(state.cells(), TABLE_GRID, "on the recipe it fetches for");
                })
                .thenWaitUntil(() -> assertCraftOrderDone(helper, aisle))
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = screen.get();
                    helper.assertTrue(player.containerMenu == menu, "the player never left the screen");
                    Optional<TerminalCraftOutcome> crafted = WarehouseTerminalMenu.submitCraft(player,
                            menu.containerId, TerminalCraftPayload.Action.CRAFT_ALL, TABLE_GRID, Optional.empty());
                    if (crafted.isEmpty()) {
                        helper.fail("the craft was dropped instead of answered");
                        return;
                    }
                    assertOutcome(helper, crafted.get(), TerminalCraftResult.CRAFTED, possible, TABLE,
                            "a Shift-click on the result");
                    ItemCensus.change(expected, PLANK, -(long) possible * PLANKS_PER_TABLE);
                    ItemCensus.change(expected, TABLE, possible);
                    ItemCensus.assertEquals(helper, expected, "three crafts move exactly three recipes' items",
                            player);
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), 0L, "every plank was used");
                    int inInventory = 0;
                    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                        ItemStack stack = player.getInventory().getItem(slot);
                        if (TABLE.matches(stack))
                            inInventory += stack.getCount();
                    }
                    helper.assertValueEqual(inInventory, possible, "the three crafting tables are the player's");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    /**
     * The player clicks for the {@code clicked} planks the racks hold, puts a clipboard asking for {@code listed}
     * more into the slot and starts it, agreeing to have them made: its portion merges into the click's request.
     *
     * @return the click's request id
     */
    private static UUID startMadeListBesideAClick(GameTestHelper helper, AisleFixture aisle, int clicked,
            int listed) {
        WarehouseTerminalBlockEntity terminal = terminal(aisle);
        Player player = playerAt(helper, aisle);
        UUID clickId = click(helper, terminal, player, PLANK, clicked);
        putClipboard(helper, terminal, clipboard(entry(PLANK, listed)));
        WarehouseTerminalMenu menu = openMenu(player, terminal);
        TerminalListOutcome asked = menu.listAction(player, TerminalListActionPayload.Action.FETCH, 0L, 0L, 0L,
                RequestAcknowledgement.NONE);
        helper.assertValueEqual(asked.result(), TerminalListResult.ASKING, "a list that has to be made is asked about");
        ListOrderConfirmation<ItemKey> whole = asked.question().orElseThrow();
        helper.assertValueEqual(whole.producing(), (long) listed, "its planks have to be made");
        helper.assertValueEqual(menu.listAction(player, TerminalListActionPayload.Action.FETCH, whole.missing(),
                whole.madeTotal(), whole.entriesDropped(), RequestAcknowledgement.NONE).result(),
                TerminalListResult.STARTED, "and the Yes starts it");
        return clickId;
    }

    /** Fails until the clipboard's made portion has merged into the click's request of {@code total} items. */
    private static void assertMadePortionMerged(GameTestHelper helper, AisleFixture aisle, UUID click, int total) {
        RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, PLANK);
        helper.assertValueEqual(request.id(), click, "the clipboard's portion merged into the click");
        helper.assertValueEqual(request.remaining(), total, "which now waits for both");
        List<ProductionOrder<ItemKey, RackPosition>> open = aisle.controller().openProductionOrders();
        helper.assertValueEqual(open.size(), 1, "the portion's planks are being made");
        helper.assertValueEqual(open.getFirst().backingRequest(), Optional.of(click), "for that request");
        helper.assertValueEqual(open.getFirst().unfulfilledPromise(), (long) PLANKS_PER_LOG, "four of them");
        helper.assertValueEqual(terminal(aisle).listState().inFlight(), (long) PLANKS_PER_LOG,
                "and the clipboard's line waits for them");
    }

    /** An aisle without a motor whose production station turns one log into four planks, stocked with {@code stock}. */
    private static AisleFixture productionAisle(GameTestHelper helper, ItemStack... stock) {
        AisleFixture aisle = stockedAisle(helper, false, stock);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        return aisle;
    }

    private static void assertProductionReady(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertReady(1, 0, 1);
        helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station is there");
        helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS_IN_STOCK, "the logs are in stock");
        helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK), "and the aisle can make planks");
    }

    /** The keeper's row {@code row}: "{@code key}: keep the last {@code reserve} away from automation". */
    private static void reserve(AisleFixture aisle, int row, ItemKey key, int reserve) {
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
        keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(row, StockKeeperRules.FIELD_RESERVE, null, (long) reserve);
    }

    private static void assertReserveKnown(GameTestHelper helper, AisleFixture aisle, ItemKey key) {
        helper.assertTrue(aisle.controller().stockRules().ruleFor(key).filter(rule -> rule.reserve() > 0L)
                .isPresent(), "the controller knows the reserve on " + key);
    }

    private static void assertCraftAsking(GameTestHelper helper, AisleFixture aisle) {
        WarehouseTerminalBlockEntity terminal = terminal(aisle);
        helper.assertValueEqual(terminal.craftOrderState().state(), ListOrderState.ASKING,
                "a portion's question stops the craft order");
        helper.assertTrue(terminal.craftQuestion().isPresent(), "and the question is the terminal's");
    }

    /** An aisle with {@code stock} in one chest and the terminal; with a motor the crane really moves. */
    private static AisleFixture stockedAisle(GameTestHelper helper, boolean withMotor, ItemStack... stock) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        aisle.storage(STORAGE_A, stock);
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    private static void assertStocked(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount) {
        aisle.assertReady(1, 0, 1);
        helper.assertValueEqual(aisle.controller().countOf(key), (long) amount, "the stock is in");
    }

    private static WarehouseTerminalBlockEntity terminal(AisleFixture aisle) {
        return aisle.terminalAt(TERMINAL_RACK);
    }

    /** A Fetch of {@code crafts} crafts of {@code grid} through the menu, as a first press nobody was asked about. */
    private static TerminalCraftOutcome fetch(Player player, WarehouseTerminalMenu menu, CraftGrid<ItemKey> grid,
            int crafts) {
        return menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(menu.containerId, grid, Optional.empty(),
                crafts, 0L, false));
    }

    /** A craft order action through the payload entry point; fails the test when it was dropped. */
    private static TerminalCraftOutcome submitCraftOrder(GameTestHelper helper, Player player,
            TerminalCraftOrderPayload payload) {
        Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraftOrder(player, payload);
        if (outcome.isEmpty()) {
            helper.fail("the craft order action was dropped instead of answered");
            throw new IllegalStateException("unreachable");
        }
        return outcome.get();
    }

    /** A player's own click for {@code amount} of {@code key}; fails unless all of it was granted. */
    private static UUID click(GameTestHelper helper, WarehouseTerminalBlockEntity terminal, Player player, ItemKey key,
            int amount) {
        RequestResult result = terminal.requestFromTerminal(player, key, amount);
        helper.assertTrue(result.isAccepted(), "the click is accepted: " + result);
        helper.assertValueEqual(result.granted(), amount, "in full");
        return result.request().orElseThrow().id();
    }

    /** Fails the test unless {@code outcome} is {@code result}, with {@code crafted}, naming {@code about}. */
    private static void assertOutcome(GameTestHelper helper, TerminalCraftOutcome outcome, TerminalCraftResult result,
            int crafted, @Nullable ItemKey about, String context) {
        helper.assertValueEqual(outcome.result(), result, context + ": the answer");
        helper.assertValueEqual(outcome.crafted(), crafted, context + ": the crafts it names");
        helper.assertValueEqual(outcome.about(), Optional.ofNullable(about), context + ": the item it names");
    }

    private static void assertCraftOrderDone(GameTestHelper helper, AisleFixture aisle) {
        TerminalCraftOrderState state = terminal(aisle).craftOrderState();
        helper.assertTrue(state.active(), "the craft order exists");
        helper.assertValueEqual(state.state(), ListOrderState.DONE, "the craft order is done");
    }

    /** The open requests to the terminal, oldest first, as a copy. */
    private static List<RetrievalRequest<ItemKey, BlockPos>> openRequests(AisleFixture aisle) {
        return List.copyOf(aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)));
    }

    /** The one open request to the terminal for {@code key}; fails while there is none. */
    private static RetrievalRequest<ItemKey, BlockPos> requestFor(GameTestHelper helper, AisleFixture aisle,
            ItemKey key) {
        RetrievalRequest<ItemKey, BlockPos> found = null;
        for (RetrievalRequest<ItemKey, BlockPos> request : openRequests(aisle)) {
            if (!request.key().equals(key))
                continue;
            if (found != null)
                helper.fail("two open requests for " + key + ": they should have merged");
            found = request;
        }
        if (found == null)
            helper.fail("no open request for " + key);
        return found;
    }

    private static void assertNoRequests(GameTestHelper helper, AisleFixture aisle, String context) {
        helper.assertTrue(openRequests(aisle).isEmpty(), context + " leaves no request open: " + openRequests(aisle));
    }

    private static void assertUnchanged(GameTestHelper helper, AisleFixture aisle,
            List<RetrievalRequest<ItemKey, BlockPos>> before, String context) {
        helper.assertValueEqual(openRequests(aisle), before, context + " leaves the aisle's requests as they were");
    }

    /** Fails unless the crane has a job for {@code requestId} of {@code amount} items that it has not picked yet. */
    private static void assertPlannedNotPicked(GameTestHelper helper, AisleFixture aisle, UUID requestId,
            int amount) {
        Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
        helper.assertTrue(job.isPresent(), "the crane has a job");
        helper.assertValueEqual(job.get().requestId(), Optional.of(requestId), "for the merged request");
        helper.assertValueEqual(job.get().plannedAmount(), amount, "for all of it");
        helper.assertFalse(job.get().picked(), "and has not picked yet");
    }

    /** Fails unless the only request for {@code key} is still {@code id}, asking for {@code amount}. */
    private static void assertClickKept(GameTestHelper helper, AisleFixture aisle, ItemKey key, UUID id, int amount) {
        RetrievalRequest<ItemKey, BlockPos> request = requestFor(helper, aisle, key);
        helper.assertValueEqual(request.id(), id, "the click's request is still there, under its own id");
        helper.assertValueEqual(request.remaining(), amount, "with exactly the click's amount");
    }

    /** Fails unless {@code after} is the same order as {@code before}: grid, crafts, amounts and progress. */
    private static void assertSameOrder(GameTestHelper helper, TerminalCraftOrderState after,
            TerminalCraftOrderState before, String context) {
        helper.assertTrue(after.isOpen(), context + ": the order is back and open");
        helper.assertValueEqual(after.state(), before.state(), context + ": the same state");
        helper.assertValueEqual(after.cells(), before.cells(), context + ": the same grid");
        helper.assertValueEqual(after.crafts(), before.crafts(), context + ": the same crafts");
        helper.assertValueEqual(after.wanted(), before.wanted(), context + ": the same amounts");
        helper.assertValueEqual(after.delivered(), before.delivered(), context + ": the same progress");
        helper.assertValueEqual(after.inFlight(), before.inFlight(), context + ": the same items on their way");
    }

    /**
     * Puts {@code stacks} into the terminal's buffer the way the crane does and adds them to the census expectation;
     * fails the test when one does not fit.
     */
    private static void seed(GameTestHelper helper, WarehouseTerminalBlockEntity terminal,
            Map<ItemKey, Long> expected, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            int count = stack.getCount();
            ItemKey key = ItemKey.of(stack);
            helper.assertTrue(terminal.insert(stack, false).isEmpty(), "the buffer took " + count + " " + key);
            ItemCensus.change(expected, key, count);
        }
    }

    /** A player empties the terminal's buffer into the chest beside the aisle. */
    private static void emptyBuffer(AisleFixture aisle, WarehouseTerminalBlockEntity terminal) {
        IItemHandler out = terminal.externalHandler();
        IItemHandler chest = aisle.handlerAt(SPILL);
        for (int slot = 0; slot < out.getSlots(); slot++) {
            ItemStack taken = out.extractItem(slot, STACK, false);
            if (!taken.isEmpty())
                aisle.insertAll(chest, taken);
        }
    }

    /** One entry of a hand-written clipboard: an icon and an amount, which is all a list order reads. */
    private static ClipboardEntry entry(ItemKey key, int amount) {
        return new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                .displayItem(key.toStack(), amount);
    }

    /** A written, read-only clipboard carrying {@code entries} on one page, as the Schematicannon writes one. */
    private static ItemStack clipboard(ClipboardEntry... entries) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entries)), true));
        return stack;
    }

    private static void putClipboard(GameTestHelper helper, WarehouseTerminalBlockEntity terminal,
            ItemStack clipboard) {
        ItemStack rest = terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT, clipboard.copy(),
                false);
        helper.assertTrue(rest.isEmpty(), "the list slot took the clipboard");
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
        return standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
