package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.netty.buffer.Unpooled;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalCrafting;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of <b>crafting at the warehouse terminal</b> (M32, issue #24, slice 1, ADR-059,
 * {@code docs/warehouse-system.md} §3.4.5): a player lays a recipe in the crafting panel and crafts it from what the
 * crane delivered into the terminal's buffer.
 * <p>
 * What is proved here is the server's half — the screen only sends a grid and draws what it is told:
 * <ul>
 * <li>{@code terminalcraftonefromthebuffer} — one click crafts one chest from the buffer into the inventory, with
 * vanilla's statistic, and a stack on the cursor is no reason to refuse;</li>
 * <li>{@code terminalcraftshiftcraftsallthebufferallows} — a Shift-click crafts exactly what the delivered planks
 * allow, an unrelated buffer item stays where it is, and a Shift-click on an empty buffer names what is missing;</li>
 * <li>{@code terminalcraftshiftstopsatthefirstrefusal} — a Shift-click that runs out of room after one craft keeps
 * that craft, stops, and answers the refusal with the items it made and the item that had no room;</li>
 * <li>{@code terminalcraftremaindersgointothebuffer} — a cake leaves its three buckets in the buffer, honey bottles
 * give sugar into the inventory and their glass bottles into the buffer;</li>
 * <li>{@code terminalcraftresultintothebufferwhentheinventoryisfull} — the result falls back to the buffer;</li>
 * <li>{@code terminalcraftrefuseswithoutroom} — no room for a remainder or for the result: refused <b>before anything
 * moves</b>, stack by stack, without a statistic, and naming the item that has no room;</li>
 * <li>{@code terminalcraftusesexactkeysonly} — renamed planks in the buffer are not planks to a grid of plain ones,
 * and planks in the player's own inventory are never taken;</li>
 * <li>{@code terminalcrafttwoplayersshareonebuffer} — two players click in the same tick for the one craft the buffer
 * holds: one gets it, the other is told what is missing;</li>
 * <li>{@code terminalcraftrespectslimitedcrafting} — {@code doLimitedCrafting} through a real server player: locked
 * until the recipe is known, and a craft awards the recipe like a crafting table;</li>
 * <li>{@code terminalcraftignoresaforeignmenu} — every payload that is not this player's own open menu at this
 * terminal, within reach and within the per-tick budget, does nothing;</li>
 * <li>{@code terminalcraftnevercraftsbyitself} — a buffer full of ingredients under two open menus with a laid grid,
 * an aisle and a running crane, for {@value #IDLE_TICKS} ticks: nothing is crafted (the ADR-059 guard);</li>
 * <li>{@code terminalcraftpreviewnamestheresult} — the server's reading of a grid and what the panel is told about its
 * items, with at most one recipe lookup per change;</li>
 * <li>{@code terminalcraftviewnamesitsgrid} — a view carries the very cells it was resolved for, so one more of the
 * same item or the same items moved is another view, which alone describes its grid;</li>
 * <li>{@code terminalcraftpayloadcodecs} — the three payloads through their real codecs, hostile shapes included.</li>
 * </ul>
 * The slot list the grid must not change is pinned in {@code WarehouseTerminalMenuGameTests#terminalMenuSlots}.
 * <p>
 * <b>Item conservation</b> is asserted on <b>every tick</b> of every test that moves an item ({@link ItemCensus}),
 * with the crafting players counted too — their inventory and their cursor — because a mock or fake player is no
 * entity of the level. A craft changes the expectation by exactly its recipe: one item per occupied cell less, the
 * result and the remainders more, written out per test rather than derived from the grid under test. Buffers are
 * seeded the way the crane fills them ({@code StationBuffer#insert}).
 * <p>
 * <b>Players.</b> Most tests use {@link GameTestHelper#makeMockPlayer}: a plain {@code Player} has no recipe book, so
 * the limited-crafting check and the recipe award are skipped for it, exactly as {@link TerminalCrafting} documents.
 * The two that assert the statistic use a {@link CountingPlayer}, the same mock player keeping what it is awarded. The
 * server-player path runs on a {@link FakePlayerFactory} player with its {@code containerMenu} set to the terminal
 * menu: it ticks nothing and its connection sends nothing, and the payload entry refuses it, so it calls the menu's
 * own {@code craft} directly. Never {@code makeMockServerPlayerInLevel}, which joins a player to the server.
 * <p>
 * Layout: a lone terminal on {@code empty_7x5x7} — crafting needs no aisle — except for the two tests that are about
 * the aisle: the preview's stock numbers and the idle test, which use the {@link AisleFixture} aisle on
 * {@code aisle_16x10x7}.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalCraftGameTests {
    /** Its own batch, because it changes the server-wide {@code doLimitedCrafting} rule. */
    private static final String LIMITED_BATCH = "wareworksTerminalCraftLimited";
    private static final int TIMEOUT_TICKS = 1200;
    /** Ticks the idle test watches a buffer full of ingredients under open menus. */
    private static final int IDLE_TICKS = 400;
    private static final int OTHER_CONTAINER_ID = 77;
    private static final int FAR_AWAY_BLOCKS = 40;
    /** Main inventory slots of a player, which is all a craft ever writes to. */
    private static final int MAIN_SLOTS = 36;
    /** Name of the fake players; each gets a fresh UUID, so no cached fake player carries one run into the next. */
    private static final String FAKE_PLAYER_NAME = "wareworks_craft";

    // --- single terminal (empty_7x5x7) ---
    private static final BlockPos TERMINAL = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos OTHER_TERMINAL = new BlockPos(5, BASE_Y, 1);

    // --- aisle (aisle_16x10x7) ---
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition PLANK_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition LOG_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final BlockPos OUTSIDE_TERMINAL = new BlockPos(12, BASE_Y, 0);

    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey FILLER = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey MILK = ItemKey.of(Items.MILK_BUCKET);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey SUGAR = ItemKey.of(Items.SUGAR);
    private static final ItemKey EGG = ItemKey.of(Items.EGG);
    private static final ItemKey WHEAT = ItemKey.of(Items.WHEAT);
    private static final ItemKey CAKE = ItemKey.of(Items.CAKE);
    private static final ItemKey HONEY = ItemKey.of(Items.HONEY_BOTTLE);
    private static final ItemKey GLASS_BOTTLE = ItemKey.of(Items.GLASS_BOTTLE);

    /** Oak planks in the eight ring cells: vanilla's chest ({@code minecraft:chest}, any planks, one chest). */
    private static final CraftGrid<ItemKey> CHEST_GRID = ring(PLANK);
    /** Vanilla's cake: three milk buckets, sugar, an egg, sugar, three wheat. Its buckets are its remainders. */
    private static final CraftGrid<ItemKey> CAKE_GRID = CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT,
            WHEAT);
    /** One honey bottle in the middle: three sugar, and the glass bottle as the remainder. */
    private static final CraftGrid<ItemKey> HONEY_GRID = CraftGrid.of(null, null, null, null, HONEY);
    /** A grid no crafting recipe matches. */
    private static final CraftGrid<ItemKey> NO_RECIPE_GRID = CraftGrid.of(DIAMOND);
    /** Four planks in a square: vanilla's crafting table. */
    private static final CraftGrid<ItemKey> TABLE_GRID = CraftGrid.of(PLANK, PLANK, null, PLANK, PLANK);
    /** Six planks in two columns: the same item as the table, two more of it, and vanilla's oak door. */
    private static final CraftGrid<ItemKey> DOOR_GRID = CraftGrid.of(PLANK, PLANK, null, PLANK, PLANK, null, PLANK,
            PLANK);
    /** Two planks one above the other: sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANK, null, null, PLANK);
    /** The same two planks side by side: an oak pressure plate. */
    private static final CraftGrid<ItemKey> PLATE_GRID = CraftGrid.of(PLANK, PLANK);
    private static final ResourceLocation CHEST_RECIPE = ResourceLocation.withDefaultNamespace("chest");

    private static final int PLANKS_PER_CHEST = 8;
    private static final int MILK_PER_CAKE = 3;
    private static final int SUGAR_PER_CAKE = 2;
    private static final int WHEAT_PER_CAKE = 3;
    private static final int SUGAR_PER_HONEY = 3;
    private static final int HONEY_BOTTLES = 4;
    private static final int PLANKS_IN_STOCK = 32;
    private static final int LOGS_IN_STOCK = 16;
    private static final int PLANKS_PER_LOG = 4;

    /** The {@code doLimitedCrafting} value before the limited test changed it; {@code null} while it is unchanged. */
    @Nullable
    private static Boolean limitedCraftingBefore;

    private TerminalCraftGameTests() {
    }

    /** Puts the server-wide rule back even when the limited test failed half-way. */
    @AfterBatch(batch = LIMITED_BATCH)
    public static void restoreLimitedCrafting(ServerLevel level) {
        if (limitedCraftingBefore == null)
            return;
        level.getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING).set(limitedCraftingBefore, level.getServer());
        limitedCraftingBefore = null;
    }

    // --- crafting from the buffer ----------------------------------------------------------------------------------

    /**
     * <b>One click, one chest.</b> Sixteen planks the crane delivered, a chest laid in the grid, one click on the
     * result: eight planks leave the buffer, a chest arrives in the player's inventory, and the craft counts like a
     * crafting table's ({@code Stats.ITEM_CRAFTED}). The player holds a diamond on the cursor all the while — a craft
     * never puts anything there, so a full hand is no reason to refuse, and the diamond is still there afterwards.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftOneFromTheBuffer(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        CountingPlayer player = countingPlayerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while one chest is crafted", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(2 * PLANKS_PER_CHEST));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            menu.setCarried(DIAMOND.toStack(1));
            ItemCensus.change(expected, DIAMOND, 1);
            ItemCensus.assertEquals(helper, expected, "the diamond on the cursor is counted", player);

            TerminalCraftOutcome outcome = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID);
            assertOutcome(helper, outcome, TerminalCraftResult.CRAFTED, 1, CHEST, "one click");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "one craft moves exactly the recipe's items", player);

            helper.assertValueEqual(terminal.bufferedItems().count(PLANK), (long) PLANKS_PER_CHEST,
                    "eight planks are left in the buffer");
            helper.assertValueEqual(countIn(player, CHEST), 1, "the chest is in the player's inventory");
            helper.assertValueEqual(terminal.bufferedItems().count(CHEST), 0L, "and not in the buffer");
            helper.assertTrue(ItemStack.matches(menu.getCarried(), DIAMOND.toStack(1)),
                    "the diamond is still on the cursor: " + menu.getCarried());
            helper.assertValueEqual(player.awarded(Stats.ITEM_CRAFTED.get(Items.CHEST)), 1,
                    "the craft counts like a crafting table's");
            // The grid the click was made on is the panel's grid from now on, and the server has read it.
            helper.assertValueEqual(menu.craftCells(), CHEST_GRID, "the menu knows the clicked grid");
            helper.assertValueEqual(menu.craftPreview().recipe(), Optional.of(CHEST_RECIPE),
                    "and the recipe it matches");
        }).thenSucceed();
    }

    /**
     * <b>A Shift-click crafts what the delivery allows, and nothing else.</b> Twenty-seven planks make three chests
     * with three planks over; the five diamonds that were delivered with them have nothing to do with the grid and stay
     * where they are. The answer is one sentence for the whole click: crafted, three. A second Shift-click on the three
     * planks left still gets one attempt, which is refused and names the planks, so the player learns what is missing.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftShiftCraftsAllTheBufferAllows(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a Shift-click crafts", player));
        int chests = 3;
        int leftOver = 3;
        int diamonds = 5;

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(chests * PLANKS_PER_CHEST + leftOver),
                    DIAMOND.toStack(diamonds));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            TerminalCraftOutcome all = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT_ALL, CHEST_GRID);
            assertOutcome(helper, all, TerminalCraftResult.CRAFTED, chests, CHEST, "a Shift-click");
            ItemCensus.change(expected, PLANK, -chests * PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, chests);
            ItemCensus.assertEquals(helper, expected, "three crafts move exactly three recipes' items", player);
            helper.assertValueEqual(terminal.bufferedItems().count(PLANK), (long) leftOver,
                    "the planks short of a fourth chest stay");
            helper.assertValueEqual(terminal.bufferedItems().count(DIAMOND), (long) diamonds,
                    "and so does the unrelated item");
            helper.assertValueEqual(countIn(player, CHEST), chests, "all three chests are the player's");

            TerminalCraftOutcome none = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT_ALL,
                    CHEST_GRID);
            assertOutcome(helper, none, TerminalCraftResult.NOT_IN_BUFFER, 0, PLANK,
                    "a Shift-click the buffer cannot serve once");
            ItemCensus.assertEquals(helper, expected, "a refused Shift-click moves nothing", player);
        }).thenSucceed();
    }

    /**
     * <b>A Shift-click that stops half way keeps what it made.</b> Sixteen honey bottles would be sixteen crafts, but
     * the room runs out after the first: the buffer has one free slot, the inventory is full of cobblestone but for
     * 61 sugar. The first craft fills the sugar stack to 64 and puts its glass bottle into the free buffer slot; the
     * second finds no place for its sugar and is refused before it moves anything. The answer is that refusal, with
     * the three sugar the click did make and the sugar as the item that had no room — the partial answer the status
     * row shows in gold — and exactly one craft moved the census and counted as crafted.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftShiftStopsAtTheFirstRefusal(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        CountingPlayer player = countingPlayerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a Shift-click stops half way",
                player));
        int sugarBefore = SUGAR.getMaxStackSize() - SUGAR_PER_HONEY;

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, HONEY.toStack(HONEY.getMaxStackSize()));
            for (int slot = 1; slot < terminal.bufferSlots() - 1; slot++)
                seed(helper, terminal, expected, FILLER.toStack(FILLER.getMaxStackSize()));
            helper.assertValueEqual(bufferStacks(terminal).stream().filter(ItemStack::isEmpty).count(), 1L,
                    "the buffer has one free slot");
            fillInventory(player, expected);
            ItemStack replaced = player.getInventory().removeItemNoUpdate(0);
            ItemCensus.change(expected, ItemKey.of(replaced), -replaced.getCount());
            player.getInventory().setItem(0, SUGAR.toStack(sugarBefore));
            ItemCensus.change(expected, SUGAR, sugarBefore);
            ItemCensus.assertEquals(helper, expected, "honey, filler and 61 sugar are counted", player);
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT_ALL, HONEY_GRID),
                    TerminalCraftResult.NO_ROOM, SUGAR_PER_HONEY, SUGAR, "a Shift-click with room for one craft");
            ItemCensus.change(expected, HONEY, -1);
            ItemCensus.change(expected, SUGAR, SUGAR_PER_HONEY);
            ItemCensus.change(expected, GLASS_BOTTLE, 1);
            ItemCensus.assertEquals(helper, expected, "exactly one craft moved, the refused one nothing", player);
            helper.assertValueEqual(terminal.bufferedItems().count(HONEY), (long) (HONEY.getMaxStackSize() - 1),
                    "one honey bottle was used");
            helper.assertValueEqual(terminal.bufferedItems().count(GLASS_BOTTLE), 1L,
                    "its glass bottle took the free buffer slot");
            helper.assertValueEqual(countIn(player, SUGAR), SUGAR.getMaxStackSize(), "the sugar stack is full");
            helper.assertValueEqual(player.awarded(Stats.ITEM_CRAFTED.get(Items.SUGAR)), SUGAR_PER_HONEY,
                    "only the craft that happened counts");
        }).thenSucceed();
    }

    /**
     * <b>Remainders stay at the terminal.</b> A cake uses three milk buckets and leaves three empty ones: they go into
     * the buffer (stacked into one slot) and the cake into the inventory. Then four honey bottles with a Shift-click:
     * twelve sugar into the inventory and four glass bottles into the buffer. Nothing is dropped and nothing vanishes:
     * this is exactly where vanilla's result slot would have dropped what did not fit.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftRemaindersGoIntoTheBuffer(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while remainders are put down", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            // A milk bucket does not stack, so the crane would have delivered three of them into three slots.
            seed(helper, terminal, expected, MILK.toStack(1), MILK.toStack(1), MILK.toStack(1),
                    SUGAR.toStack(SUGAR_PER_CAKE), EGG.toStack(1), WHEAT.toStack(WHEAT_PER_CAKE));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            TerminalCraftOutcome cake = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CAKE_GRID);
            assertOutcome(helper, cake, TerminalCraftResult.CRAFTED, 1, CAKE, "the cake");
            ItemCensus.change(expected, MILK, -MILK_PER_CAKE);
            ItemCensus.change(expected, SUGAR, -SUGAR_PER_CAKE);
            ItemCensus.change(expected, EGG, -1);
            ItemCensus.change(expected, WHEAT, -WHEAT_PER_CAKE);
            ItemCensus.change(expected, CAKE, 1);
            ItemCensus.change(expected, BUCKET, MILK_PER_CAKE);
            ItemCensus.assertEquals(helper, expected, "a cake is its ingredients less, a cake and buckets more",
                    player);
            helper.assertValueEqual(terminal.bufferedItems().count(BUCKET), (long) MILK_PER_CAKE,
                    "the three buckets are in the buffer");
            helper.assertValueEqual(slotsHolding(terminal, BUCKET), 1, "stacked into one slot");
            helper.assertValueEqual(countIn(player, BUCKET), 0, "none of them in the inventory");
            helper.assertValueEqual(countIn(player, CAKE), 1, "the cake is");

            seed(helper, terminal, expected, HONEY.toStack(HONEY_BOTTLES));
            TerminalCraftOutcome sugar = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT_ALL,
                    HONEY_GRID);
            assertOutcome(helper, sugar, TerminalCraftResult.CRAFTED, HONEY_BOTTLES * SUGAR_PER_HONEY, SUGAR,
                    "four honey bottles");
            ItemCensus.change(expected, HONEY, -HONEY_BOTTLES);
            ItemCensus.change(expected, SUGAR, HONEY_BOTTLES * SUGAR_PER_HONEY);
            ItemCensus.change(expected, GLASS_BOTTLE, HONEY_BOTTLES);
            ItemCensus.assertEquals(helper, expected, "each bottle became three sugar and a glass bottle", player);
            helper.assertValueEqual(terminal.bufferedItems().count(GLASS_BOTTLE), (long) HONEY_BOTTLES,
                    "the glass bottles are in the buffer");
            helper.assertValueEqual(terminal.bufferedItems().count(HONEY), 0L, "every honey bottle was used");
            helper.assertValueEqual(countIn(player, SUGAR), HONEY_BOTTLES * SUGAR_PER_HONEY,
                    "the sugar is the player's");
            helper.assertValueEqual(countIn(player, GLASS_BOTTLE), 0, "and no bottle went into the inventory");
        }).thenSucceed();
    }

    /**
     * <b>A full inventory is no reason to refuse.</b> All 36 main slots of the player hold a full stack; the chest goes
     * into the buffer the planks just left, and the inventory is untouched stack by stack.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftResultIntoTheBufferWhenTheInventoryIsFull(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the inventory is full", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(PLANKS_PER_CHEST));
            fillInventory(player, expected);
            List<ItemStack> inventoryBefore = inventoryStacks(player);
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            TerminalCraftOutcome outcome = submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID);
            assertOutcome(helper, outcome, TerminalCraftResult.CRAFTED, 1, CHEST, "a craft with a full inventory");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "the craft still moved exactly the recipe's items", player);
            helper.assertValueEqual(terminal.bufferedItems().count(CHEST), 1L, "the chest went into the buffer");
            helper.assertValueEqual(terminal.bufferedItems().count(PLANK), 0L, "where the planks were");
            assertSameStacks(helper, inventoryBefore, inventoryStacks(player), "the full inventory");
        }).thenSucceed();
    }

    /**
     * <b>No room: refused before anything moves.</b> Buffer and inventory are full to the last slot.
     * <p>
     * Four honey bottles are no help here: taking one from a stack of sixteen frees no slot, so its glass bottle has
     * nowhere to go and the craft is refused naming the <b>bottle</b> — as a click and as a Shift-click. A chest is
     * refused naming the <b>chest</b>: taking eight planks from a stack of 64 frees nothing either. After each, both
     * inventories are equal to what they were stack by stack, and no statistic was awarded, because a refusal happens
     * before the side effects (vanilla's issue of awarding a craft that then does not fit cannot happen here). That a
     * vanilla milk bucket recipe could be refused for a bucket cannot be shown: three milk buckets free three slots,
     * which is why the bottle stands in for the design's bucket.
     * <p>
     * Then one inventory slot is emptied and the same chest click goes through, so the refusal was about room and
     * nothing else.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftRefusesWithoutRoom(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        CountingPlayer player = countingPlayerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a craft has no room", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, HONEY.toStack(HONEY.getMaxStackSize()),
                    PLANK.toStack(PLANK.getMaxStackSize()));
            for (int slot = 2; slot < terminal.bufferSlots(); slot++)
                seed(helper, terminal, expected, FILLER.toStack(FILLER.getMaxStackSize()));
            helper.assertTrue(terminal.insert(FILLER.toStack(1), true).getCount() == 1, "the buffer is full");
            fillInventory(player, expected);
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            List<ItemStack> bufferBefore = bufferStacks(terminal);
            List<ItemStack> inventoryBefore = inventoryStacks(player);

            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, HONEY_GRID),
                    TerminalCraftResult.NO_ROOM, 0, GLASS_BOTTLE, "no room for the remainder");
            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT_ALL, HONEY_GRID),
                    TerminalCraftResult.NO_ROOM, 0, GLASS_BOTTLE, "no room for the remainder, Shift-clicked");
            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID),
                    TerminalCraftResult.NO_ROOM, 0, CHEST, "no room for the result");
            assertSameStacks(helper, bufferBefore, bufferStacks(terminal), "the buffer after the refusals");
            assertSameStacks(helper, inventoryBefore, inventoryStacks(player), "the inventory after the refusals");
            ItemCensus.assertEquals(helper, expected, "a refused craft moves nothing", player);
            helper.assertTrue(player.awarded().isEmpty(), "a refused craft awards nothing: " + player.awarded());

            // One free slot, and the very same click crafts.
            ItemStack freed = player.getInventory().removeItemNoUpdate(MAIN_SLOTS - 1);
            ItemCensus.change(expected, ItemKey.of(freed), -freed.getCount());
            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID),
                    TerminalCraftResult.CRAFTED, 1, CHEST, "the same click with one free slot");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "and it moved exactly the recipe's items", player);
            helper.assertValueEqual(player.getInventory().getItem(MAIN_SLOTS - 1).getItem(), Items.CHEST,
                    "into the slot that was freed");
            helper.assertValueEqual(player.awarded(Stats.ITEM_CRAFTED.get(Items.CHEST)), 1,
                    "and only that craft is counted");
        }).thenSucceed();
    }

    /**
     * <b>Exact keys only.</b> A cell is an exact item, components included, and a craft takes from the buffer only.
     * <p>
     * The buffer holds eight <b>renamed</b> planks and the player eight plain ones in their own inventory. A grid of
     * plain planks is refused naming the plain planks: the renamed ones are not they, and the inventory is never an
     * ingredient source. Then the grid is laid with the renamed planks — the chest recipe accepts any planks — and the
     * craft takes exactly those, while the plain planks stay in the player's slot.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftUsesExactKeysOnly(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while exact keys are crafted", player));
        ItemStack renamedStack = PLANK.toStack(PLANKS_PER_CHEST);
        renamedStack.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom planks"));
        ItemKey renamed = ItemKey.of(renamedStack);

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, renamedStack);
            player.getInventory().setItem(0, PLANK.toStack(PLANKS_PER_CHEST));
            ItemCensus.change(expected, PLANK, PLANKS_PER_CHEST);
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            List<ItemStack> bufferBefore = bufferStacks(terminal);
            List<ItemStack> inventoryBefore = inventoryStacks(player);

            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID),
                    TerminalCraftResult.NOT_IN_BUFFER, 0, PLANK, "plain planks from a buffer of renamed ones");
            assertSameStacks(helper, bufferBefore, bufferStacks(terminal), "the renamed planks in the buffer");
            assertSameStacks(helper, inventoryBefore, inventoryStacks(player), "the plain planks in the inventory");

            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, ring(renamed)),
                    TerminalCraftResult.CRAFTED, 1, CHEST, "a grid of the renamed planks");
            ItemCensus.change(expected, renamed, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "the craft took exactly the renamed planks", player);
            helper.assertTrue(ItemStack.matches(player.getInventory().getItem(0), PLANK.toStack(PLANKS_PER_CHEST)),
                    "the plain planks are still in the player's slot: " + player.getInventory().getItem(0));
        }).thenSucceed();
    }

    /**
     * <b>Two players, one buffer.</b> Each has their own menu at the terminal and their own grid, and both click in
     * the same tick for the one chest the buffer holds. The first click crafts it; the second is refused before
     * anything moves and names the planks, because the buffer — not a promise to whoever laid the grid first — is
     * where the ingredients are.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftTwoPlayersShareOneBuffer(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player first = playerAt(helper, TERMINAL);
        Player second = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while two players craft", first,
                second));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(PLANKS_PER_CHEST));
            WarehouseTerminalMenu firstMenu = openMenu(first, terminal);
            WarehouseTerminalMenu secondMenu = openMenu(second, terminal);

            TerminalCraftOutcome won = submit(helper, first, firstMenu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID);
            TerminalCraftOutcome lost = submit(helper, second, secondMenu, TerminalCraftPayload.Action.CRAFT,
                    CHEST_GRID);
            assertOutcome(helper, won, TerminalCraftResult.CRAFTED, 1, CHEST, "the first click");
            assertOutcome(helper, lost, TerminalCraftResult.NOT_IN_BUFFER, 0, PLANK, "the second click");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "one craft between the two of them", first, second);
            helper.assertValueEqual(countIn(first, CHEST), 1, "the first player has the chest");
            helper.assertValueEqual(countIn(second, CHEST), 0, "the second has none");
        }).thenSucceed();
    }

    /**
     * <b>Limited crafting holds at the terminal too</b> ({@code doLimitedCrafting}), through a real server player.
     * <p>
     * First, with the rule off, a fake player who knows no recipe crafts a chest and <b>learns</b> it, exactly as a
     * crafting table's result slot awards it. Then the rule is switched on and a second fake player, who knows no
     * recipe, is refused as {@link TerminalCraftResult#LOCKED} — a click and a Shift-click, with nothing moved — while
     * the panel still shows what the grid would make and marks it locked. Once the recipe is in their book the same
     * click crafts, and the lock mark goes away.
     * <p>
     * A fake player never gets past the payload entry ({@link WarehouseTerminalMenu#submitCraft}), which this test also
     * shows, so it calls the menu's own {@code craft} the payload would have called. The rule is server-wide, so the
     * test runs in a batch of its own and {@link #restoreLimitedCrafting} puts it back even after a failure.
     */
    @GameTest(template = EMPTY_7X5X7, batch = LIMITED_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftRespectsLimitedCrafting(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        FakePlayer learner = fakePlayerAt(helper, TERMINAL);
        FakePlayer locked = fakePlayerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while limited crafting is checked",
                learner, locked));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(3 * PLANKS_PER_CHEST));
            RecipeHolder<?> chest = helper.getLevel().getRecipeManager().byKey(CHEST_RECIPE).orElse(null);
            if (chest == null)
                helper.fail("the vanilla chest recipe is missing");
            setLimitedCrafting(helper, false);

            WarehouseTerminalMenu learnerMenu = openMenu(learner, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitCraft(learner, learnerMenu.containerId,
                    TerminalCraftPayload.Action.CRAFT, CHEST_GRID, Optional.empty()).isEmpty(),
                    "a fake player is refused at the payload entry");
            helper.assertFalse(learner.getRecipeBook().contains(chest), "the learner does not know the chest yet");
            assertOutcome(helper, learnerMenu.craft(learner, CHEST_GRID, Optional.empty(), false),
                    TerminalCraftResult.CRAFTED, 1, CHEST, "a craft without the rule");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "the learner's craft", learner, locked);
            helper.assertTrue(learner.getRecipeBook().contains(chest), "and the craft taught them the recipe");

            setLimitedCrafting(helper, true);
            WarehouseTerminalMenu lockedMenu = openMenu(locked, terminal);
            assertOutcome(helper, lockedMenu.craft(locked, CHEST_GRID, Optional.empty(), false),
                    TerminalCraftResult.LOCKED, 0, null, "a click on a recipe the player does not know");
            assertOutcome(helper, lockedMenu.craft(locked, CHEST_GRID, Optional.empty(), true),
                    TerminalCraftResult.LOCKED, 0, null, "a Shift-click on it");
            ItemCensus.assertEquals(helper, expected, "a locked recipe moves nothing", learner, locked);
            helper.assertValueEqual(lockedMenu.craftPreview().recipe(), Optional.of(CHEST_RECIPE),
                    "the panel still names the recipe");
            helper.assertTrue(lockedMenu.craftPreview().locked(), "and marks it locked");
            helper.assertTrue(lockedMenu.craftView().locked(), "which is what the panel is told");

            locked.getRecipeBook().add(chest);
            assertOutcome(helper, lockedMenu.craft(locked, CHEST_GRID, Optional.of(CHEST_RECIPE), false),
                    TerminalCraftResult.CRAFTED, 1, CHEST, "the same click once the recipe is known");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "the unlocked craft", learner, locked);
            helper.assertFalse(lockedMenu.craftPreview().locked(), "the lock mark went away with it");
            setLimitedCrafting(helper, limitedCraftingBefore);
            limitedCraftingBefore = null;
        }).thenSucceed();
    }

    /**
     * <b>Only the player's own open menu, at this terminal, within reach, within the budget.</b> Every payload that
     * fails one of these does nothing at all, which the census and the stack-by-stack buffer show after each:
     * <ul>
     * <li>no player, a player without a terminal menu, another container id, a stranger who has no menu of this id, and
     * an action this build does not know — dropped without an answer;</li>
     * <li>a fake player at the payload entry — dropped (a craft is a player's own hand);</li>
     * <li>a stranger handed this player's menu, a menu of another terminal handed to this one and the other way round,
     * and a player who walked away — {@link TerminalCraftResult#OUT_OF_REACH};</li>
     * <li>the ninth action of one tick — dropped, because a preview and a craft cost one action of the eight per tick a
     * menu answers, like a request. The next tick answers again, and the genuine click crafts.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftIgnoresAForeignMenu(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        helper.setBlock(OTHER_TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while foreign payloads arrive", player));
        WarehouseTerminalMenu[] own = new WarehouseTerminalMenu[1];
        List<List<ItemStack>> before = new ArrayList<>();

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            WarehouseTerminalBlockEntity other = terminalAt(helper, OTHER_TERMINAL);
            seed(helper, terminal, expected, PLANK.toStack(2 * PLANKS_PER_CHEST));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            own[0] = menu;
            before.add(bufferStacks(terminal));

            // Dropped without an answer.
            helper.assertTrue(dropped(null, menu.containerId, TerminalCraftPayload.Action.CRAFT),
                    "no player, no craft");
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(dropped(player, menu.containerId, TerminalCraftPayload.Action.CRAFT),
                    "a player without a terminal menu crafts nothing");
            player.containerMenu = menu;
            helper.assertTrue(dropped(player, OTHER_CONTAINER_ID, TerminalCraftPayload.Action.CRAFT),
                    "a payload for another menu is ignored");
            helper.assertTrue(dropped(player, menu.containerId, TerminalCraftPayload.Action.NONE),
                    "an action this build does not know is ignored");
            Player stranger = playerAt(helper, TERMINAL);
            helper.assertTrue(dropped(stranger, menu.containerId, TerminalCraftPayload.Action.CRAFT_ALL),
                    "another player cannot use this player's menu");
            FakePlayer fake = fakePlayerAt(helper, TERMINAL);
            WarehouseTerminalMenu fakeMenu = openMenu(fake, terminal);
            helper.assertTrue(dropped(fake, fakeMenu.containerId, TerminalCraftPayload.Action.CRAFT),
                    "a fake player has no hand to craft with");

            // Answered, and refused at the terminal's gate.
            assertOutcome(helper, terminal.craftFromBuffer(stranger, menu, CHEST_GRID, Optional.empty(), false),
                    TerminalCraftResult.OUT_OF_REACH, 0, null, "a stranger handed this player's menu");
            Player atOther = playerAt(helper, OTHER_TERMINAL);
            WarehouseTerminalMenu otherMenu = openMenu(atOther, other);
            assertOutcome(helper, terminal.craftFromBuffer(atOther, otherMenu, CHEST_GRID, Optional.empty(), false),
                    TerminalCraftResult.OUT_OF_REACH, 0, null, "a menu of another terminal");
            assertOutcome(helper, other.craftFromBuffer(player, menu, CHEST_GRID, Optional.empty(), false),
                    TerminalCraftResult.OUT_OF_REACH, 0, null, "this menu at another terminal");
            Player far = helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(TERMINAL));
            far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
            WarehouseTerminalMenu farMenu = openMenu(far, terminal);
            assertOutcome(helper, submit(helper, far, farMenu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID),
                    TerminalCraftResult.OUT_OF_REACH, 0, null, "a player who walked away");

            assertSameStacks(helper, before.getFirst(), bufferStacks(terminal), "the buffer after foreign payloads");
            ItemCensus.assertEquals(helper, expected, "no foreign payload moved anything", player);
        }).thenIdle(1).thenExecute(() -> {
            // A flood in one tick: the budget is shared with requests and counts a preview like a craft.
            WarehouseTerminalMenu menu = own[0];
            for (int i = 0; i < WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK; i++) {
                helper.assertTrue(WarehouseTerminalMenu.submitCraft(player, menu.containerId,
                        TerminalCraftPayload.Action.PREVIEW, CHEST_GRID, Optional.empty()).isEmpty(),
                        "preview " + i + " is answered by the next push, not here");
            }
            helper.assertValueEqual(menu.craftPreviewLookups(), 1, "and looked the unchanged grid up once");
            helper.assertTrue(dropped(player, menu.containerId, TerminalCraftPayload.Action.CRAFT),
                    "the craft after the budget is dropped");
            assertSameStacks(helper, before.getFirst(), bufferStacks(terminalAt(helper, TERMINAL)),
                    "the buffer after the flood");
        }).thenIdle(1).thenExecute(() -> {
            WarehouseTerminalMenu menu = own[0];
            assertOutcome(helper, submit(helper, player, menu, TerminalCraftPayload.Action.CRAFT, CHEST_GRID),
                    TerminalCraftResult.CRAFTED, 1, CHEST, "a new tick, a new budget, and the genuine click");
            ItemCensus.change(expected, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(expected, CHEST, 1);
            ItemCensus.assertEquals(helper, expected, "the genuine click", player);
        }).thenSucceed();
    }

    // --- the ADR-059 guard -----------------------------------------------------------------------------------------

    /**
     * <b>Wareworks never crafts on its own</b> (ADR-059, the correction to ADR-024). A working aisle with a running
     * crane, a terminal whose whole buffer is planks — enough for dozens of chests — and two players with the
     * terminal's screen open and a chest laid in the grid: a fake server player, whose menu is pushed every tick as a
     * real player's tick would push it (stock, status, orders and the crafting view), and a mock player.
     * <p>
     * For {@value #IDLE_TICKS} ticks nothing is crafted: the census is unchanged on every tick, the buffer is unchanged
     * stack by stack, the players hold nothing, the terminal asked the aisle for nothing, and the recipe was looked up
     * once per laid grid and never again by a push — so no tick, push, order or automation path reaches the code that
     * crafts. Only a click does, and there is none.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftNeverCraftsByItself(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(PLANK_RACK, PLANK.toStack(PLANKS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        FakePlayer watcher = fakePlayerAt(helper, aisle.rackPos(TERMINAL_RACK));
        Player bystander = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        helper.onEachTick(() -> {
            // What ServerPlayer#tick does for an open screen; a fake player's own tick does nothing.
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            ItemCensus.assertEquals(helper, expected, "while nobody clicks", watcher, bystander);
        });
        List<List<ItemStack>> before = new ArrayList<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(PLANK), (long) PLANKS_IN_STOCK,
                            "the planks are in stock");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    for (int slot = 0; slot < terminal.bufferSlots(); slot++)
                        seed(helper, terminal, expected, PLANK.toStack(PLANK.getMaxStackSize()));
                    for (Player player : List.of(watcher, bystander)) {
                        WarehouseTerminalMenu menu = openMenu(player, terminal);
                        menu.previewCraft(player, CHEST_GRID, Optional.empty());
                        menus.add(menu);
                    }
                    before.add(bufferStacks(terminal));
                })
                .thenIdle(IDLE_TICKS)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    assertSameStacks(helper, before.getFirst(), bufferStacks(terminal), "the buffer after idling");
                    helper.assertTrue(inventoryStacks(watcher).stream().allMatch(ItemStack::isEmpty),
                            "the fake player holds nothing");
                    helper.assertTrue(inventoryStacks(bystander).stream().allMatch(ItemStack::isEmpty),
                            "nor does the mock player");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                            "the terminal asked the aisle for nothing");
                    helper.assertValueEqual(controller.countOf(PLANK), (long) PLANKS_IN_STOCK,
                            "the stock is untouched");
                    for (WarehouseTerminalMenu menu : menus) {
                        helper.assertValueEqual(menu.craftPreviewLookups(), 1,
                                "one lookup for the laid grid, none by any push");
                        helper.assertValueEqual(menu.craftView().recipe(), Optional.of(CHEST_RECIPE),
                                "the panel still shows the chest it could craft");
                    }
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- the preview -----------------------------------------------------------------------------------------------

    /**
     * <b>The server names the result</b> (the panel's preview). A chest grid at a terminal of an aisle that stocks
     * planks and can make more of them from logs: the menu resolves {@code minecraft:chest}, and the view the panel is
     * pushed names a chest and tells, for the one item of the grid, how many a click could be granted and that the
     * aisle can make it. The aisle's stock numbers are the server's own, not the screen's capped list.
     * <p>
     * At most one recipe lookup per change: the same grid again, with or without the hint that names the recipe it
     * found, costs none; a hint that names a recipe the grid does not match, or none at all, is only a hint and still
     * gives the chest. A grid no recipe matches gives a view without a result, and an empty grid gives the empty view
     * and no lookup at all. A terminal outside every aisle still previews — crafting from the buffer needs no aisle —
     * and says that it has none.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftPreviewNamesTheResult(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(PLANK_RACK, PLANK.toStack(PLANKS_IN_STOCK));
        aisle.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity saw = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(saw.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "the pattern's result");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(PLANK), (long) PLANKS_IN_STOCK,
                            "the planks are in stock");
                    helper.assertTrue(aisle.controller().producibleKeys().contains(PLANK),
                            "and the aisle knows it can make more");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(menu.craftView(), TerminalCraftViewPayload.none(menu.containerId),
                            "a panel nobody used has nothing to show");

                    preview(helper, player, menu, CHEST_GRID, Optional.empty());
                    helper.assertValueEqual(menu.craftPreviewLookups(), 1, "one lookup for the new grid");
                    TerminalCrafting.Preview preview = menu.craftPreview();
                    helper.assertValueEqual(preview.recipe(), Optional.of(CHEST_RECIPE), "the recipe it matches");
                    helper.assertTrue(ItemStack.matches(preview.result(), CHEST.toStack(1)),
                            "one chest per craft: " + preview.result());
                    helper.assertFalse(preview.locked(), "a mock player has no recipe book to be locked out by");

                    TerminalCraftViewPayload view = menu.craftView();
                    helper.assertValueEqual(view.recipe(), Optional.of(CHEST_RECIPE), "the view names the recipe");
                    helper.assertTrue(ItemStack.matches(view.result(), CHEST.toStack(1)), "and the chest");
                    helper.assertFalse(view.noAisle(), "the terminal belongs to an aisle");
                    helper.assertValueEqual(view.keys(), List.of(new TerminalCraftViewPayload.KeyView(PLANK,
                            PLANKS_IN_STOCK, true, false)),
                            "one entry for the planks: the stock a click could be granted, and that the aisle makes "
                                    + "them");

                    preview(helper, player, menu, CHEST_GRID, Optional.empty());
                    preview(helper, player, menu, CHEST_GRID, Optional.of(CHEST_RECIPE));
                    helper.assertValueEqual(menu.craftPreviewLookups(), 1,
                            "the same grid, also with the hint naming the recipe it found, costs no lookup");
                    preview(helper, player, menu, CHEST_GRID,
                            Optional.of(ResourceLocation.withDefaultNamespace("furnace")));
                    helper.assertValueEqual(menu.craftPreviewLookups(), 2, "a new hint is a change");
                    helper.assertValueEqual(menu.craftPreview().recipe(), Optional.of(CHEST_RECIPE),
                            "but a hint the grid does not match is only a hint");
                    preview(helper, player, menu, CHEST_GRID,
                            Optional.of(Wareworks.asResource("no_such_recipe")));
                    helper.assertValueEqual(menu.craftPreview().recipe(), Optional.of(CHEST_RECIPE),
                            "and so is one that names no recipe at all");
                    helper.assertValueEqual(menu.craftPreviewLookups(), 3, "one lookup for that change");

                    preview(helper, player, menu, NO_RECIPE_GRID, Optional.empty());
                    helper.assertValueEqual(menu.craftPreviewLookups(), 4, "one lookup for the changed grid");
                    TerminalCraftViewPayload nothing = menu.craftView();
                    helper.assertTrue(nothing.recipe().isEmpty(), "a diamond alone matches no recipe");
                    helper.assertTrue(nothing.result().isEmpty(), "so the view names no result");
                    helper.assertValueEqual(nothing.keys().size(), 1, "but still tells about the diamond");
                    helper.assertValueEqual(nothing.keyView(DIAMOND).map(TerminalCraftViewPayload.KeyView::available),
                            Optional.of(0L), "of which the aisle holds none");

                    preview(helper, player, menu, CraftGrid.empty(), Optional.empty());
                    helper.assertValueEqual(menu.craftPreviewLookups(), 4, "an empty grid costs no lookup");
                    helper.assertValueEqual(menu.craftPreview(), TerminalCrafting.Preview.NONE,
                            "and makes nothing");
                    helper.assertValueEqual(menu.craftView(), TerminalCraftViewPayload.none(menu.containerId),
                            "so the panel is told the empty view");

                    // Outside every aisle: the same preview, with nothing to say about stock.
                    helper.setBlock(OUTSIDE_TERMINAL, terminalState());
                    WarehouseTerminalBlockEntity outside = terminalAt(helper, OUTSIDE_TERMINAL);
                    Player atOutside = playerAt(helper, OUTSIDE_TERMINAL);
                    WarehouseTerminalMenu outsideMenu = openMenu(atOutside, outside);
                    preview(helper, atOutside, outsideMenu, CHEST_GRID, Optional.empty());
                    TerminalCraftViewPayload alone = outsideMenu.craftView();
                    helper.assertValueEqual(alone.recipe(), Optional.of(CHEST_RECIPE),
                            "a terminal outside every aisle still previews");
                    helper.assertTrue(alone.noAisle(), "and says that it has no aisle");
                    helper.assertValueEqual(alone.keys(), List.of(new TerminalCraftViewPayload.KeyView(PLANK, 0L,
                            false, false)), "with no stock numbers");
                })
                .thenSucceed();
    }

    /**
     * <b>A view names the grid it answers</b>, cell by cell, so the panel can tell it from a view of an earlier grid
     * and neither shows nor crafts an old result for a new grid. Four planks in a square make a crafting table and six
     * in two columns a door: the same item, two more of it, another recipe. Two planks one above the other make sticks
     * and side by side a pressure plate: the same items moved, another recipe. Every one of these views tells the aisle
     * the same about the one item it names, so the items alone could not tell them apart; each carries its own grid,
     * differs from the one before — so the menu pushes it — and describes its own grid and no other.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftViewNamesItsGrid(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        List<CraftGrid<ItemKey>> grids = List.of(TABLE_GRID, DOOR_GRID, STICK_GRID, PLATE_GRID);
        List<ResourceLocation> recipes = List.of(ResourceLocation.withDefaultNamespace("crafting_table"),
                ResourceLocation.withDefaultNamespace("oak_door"), ResourceLocation.withDefaultNamespace("stick"),
                ResourceLocation.withDefaultNamespace("oak_pressure_plate"));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalMenu menu = openMenu(player, terminalAt(helper, TERMINAL));
            List<TerminalCraftViewPayload> views = new ArrayList<>();
            for (int i = 0; i < grids.size(); i++) {
                preview(helper, player, menu, grids.get(i), Optional.empty());
                TerminalCraftViewPayload view = menu.craftView();
                helper.assertValueEqual(view.cells(), grids.get(i), "view " + i + " carries the grid it answers");
                helper.assertValueEqual(view.recipe(), Optional.of(recipes.get(i)), "view " + i + " names its recipe");
                helper.assertValueEqual(view.keys(), List.of(new TerminalCraftViewPayload.KeyView(PLANK, 0L, false,
                        false)), "view " + i + " says about its one item what every other says");
                if (!views.isEmpty())
                    helper.assertFalse(view.equals(views.getLast()), "view " + i + " differs from the one before, so "
                            + "it is pushed");
                views.add(view);
            }
            for (int view = 0; view < views.size(); view++) {
                for (int grid = 0; grid < grids.size(); grid++)
                    helper.assertValueEqual(views.get(view).describes(grids.get(grid)), view == grid,
                            "view " + view + " describes grid " + grid);
            }
            preview(helper, player, menu, CraftGrid.empty(), Optional.empty());
            helper.assertTrue(menu.craftView().describes(CraftGrid.empty()), "the empty view describes the empty grid");
            helper.assertFalse(views.getLast().describes(CraftGrid.empty()), "and no other view does");
        }).thenSucceed();
    }

    // --- payloads --------------------------------------------------------------------------------------------------

    /**
     * The three crafting payloads through their real codecs: a round trip of each, and the hostile shapes a crafted
     * client could send — a grid of the wrong size and an unknown action (both read as {@code NONE}, which the server
     * drops), a cell count that claims more than nine, a hint that is no valid id (read as none), an answer naming a
     * result this build does not know (read as a plain refusal) and a view with more key entries than a grid can name.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalCraftPayloadCodecs(GameTestHelper helper) {
        TerminalCraftPayload craft = new TerminalCraftPayload(3, TerminalCraftPayload.Action.CRAFT_ALL, CAKE_GRID,
                Optional.of(CHEST_RECIPE));
        helper.assertValueEqual(roundTrip(helper, craft, TerminalCraftPayload.STREAM_CODEC), craft,
                "a craft payload round trip");
        TerminalCraftPayload preview = new TerminalCraftPayload(3, TerminalCraftPayload.Action.PREVIEW,
                CraftGrid.empty(), Optional.empty());
        helper.assertValueEqual(roundTrip(helper, preview, TerminalCraftPayload.STREAM_CODEC), preview,
                "an empty grid without a hint");

        TerminalCraftPayload eightCells = decodeRaw(helper, TerminalCraftPayload.STREAM_CODEC, buffer -> {
            buffer.writeVarInt(3);
            buffer.writeUtf("CRAFT");
            buffer.writeVarInt(CraftGrid.SIZE - 1);
            for (int i = 0; i < CraftGrid.SIZE - 1; i++)
                writeCell(buffer, PLANK);
            buffer.writeUtf("");
        });
        helper.assertValueEqual(eightCells.action(), TerminalCraftPayload.Action.NONE, "eight cells read as NONE");
        helper.assertTrue(eightCells.cells().isEmpty(), "with no grid at all");
        TerminalCraftPayload claimsMore = decodeRaw(helper, TerminalCraftPayload.STREAM_CODEC, buffer -> {
            buffer.writeVarInt(3);
            buffer.writeUtf("CRAFT");
            buffer.writeVarInt(1000);
            for (int i = 0; i < CraftGrid.SIZE; i++)
                writeCell(buffer, PLANK);
            buffer.writeUtf("");
        });
        helper.assertValueEqual(claimsMore.action(), TerminalCraftPayload.Action.NONE,
                "a count that claims a thousand cells reads nine and drops the payload");
        TerminalCraftPayload unknown = decodeRaw(helper, TerminalCraftPayload.STREAM_CODEC, buffer -> {
            buffer.writeVarInt(3);
            buffer.writeUtf("MELT");
            buffer.writeVarInt(CraftGrid.SIZE);
            for (int i = 0; i < CraftGrid.SIZE; i++)
                writeCell(buffer, PLANK);
            buffer.writeUtf("Not A Valid Id");
        });
        helper.assertValueEqual(unknown.action(), TerminalCraftPayload.Action.NONE,
                "an unknown action reads as NONE, which the server drops");
        helper.assertTrue(unknown.hint().isEmpty(), "and a hint that is no valid id reads as none");

        TerminalCraftViewPayload view = new TerminalCraftViewPayload(3, CHEST_GRID, Optional.of(CHEST_RECIPE),
                CHEST.toStack(1), true, false, List.of(new TerminalCraftViewPayload.KeyView(PLANK, 1234L, true, true)));
        TerminalCraftViewPayload decodedView = roundTrip(helper, view, TerminalCraftViewPayload.STREAM_CODEC);
        helper.assertValueEqual(decodedView, view, "a view round trip, compared by value");
        helper.assertValueEqual(decodedView.hashCode(), view.hashCode(), "with the same hash");
        helper.assertValueEqual(decodedView.cells(), CHEST_GRID, "and the grid it answers, cell by cell");
        TerminalCraftViewPayload moved = new TerminalCraftViewPayload(3, PLATE_GRID, Optional.of(CHEST_RECIPE),
                CHEST.toStack(1), true, false, view.keys());
        helper.assertFalse(moved.equals(view), "a view of other cells is another view, whatever else it says");
        TerminalCraftViewPayload eightCellView = decodeRaw(helper, TerminalCraftViewPayload.STREAM_CODEC, buffer -> {
            buffer.writeVarInt(3);
            buffer.writeVarInt(CraftGrid.SIZE - 1);
            for (int i = 0; i < CraftGrid.SIZE - 1; i++)
                writeCell(buffer, PLANK);
            buffer.writeUtf(CHEST_RECIPE.toString());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, CHEST.toStack(1));
            buffer.writeBoolean(false);
            buffer.writeBoolean(false);
            buffer.writeVarInt(0);
        });
        helper.assertTrue(eightCellView.cells().isEmpty(),
                "a view of eight cells describes no grid that names an item");
        List<TerminalCraftViewPayload.KeyView> tooMany = new ArrayList<>();
        for (int i = 0; i < TerminalCraftViewPayload.MAX_KEYS + 3; i++)
            tooMany.add(new TerminalCraftViewPayload.KeyView(PLANK, i, false, false));
        helper.assertValueEqual(new TerminalCraftViewPayload(3, CraftGrid.empty(), Optional.empty(), ItemStack.EMPTY,
                false, true, tooMany).keys().size(), TerminalCraftViewPayload.MAX_KEYS,
                "a view never carries more than nine");

        TerminalCraftAnswerPayload answer = TerminalCraftAnswerPayload.of(3,
                new TerminalCraftOutcome(TerminalCraftResult.NO_ROOM, 5, Optional.of(GLASS_BOTTLE)));
        helper.assertValueEqual(roundTrip(helper, answer, TerminalCraftAnswerPayload.STREAM_CODEC), answer,
                "an answer round trip");
        TerminalCraftAnswerPayload strange = decodeRaw(helper, TerminalCraftAnswerPayload.STREAM_CODEC, buffer -> {
            buffer.writeVarInt(3);
            buffer.writeUtf("EXPLODED");
            buffer.writeVarInt(2);
            buffer.writeBoolean(false);
        });
        helper.assertValueEqual(strange.result(), TerminalCraftResult.NO_RECIPE,
                "an unknown result reads as a plain refusal");
        helper.succeed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** {@code key} in the eight ring cells, the middle left empty. */
    private static CraftGrid<ItemKey> ring(ItemKey key) {
        return CraftGrid.of(key, key, key, key, null, key, key, key, key);
    }

    /**
     * Puts {@code stacks} into the terminal's buffer the way the crane does ({@code StationBuffer#insert}) and adds
     * them to the census expectation; fails the test when one does not fit.
     */
    private static void seed(GameTestHelper helper, WarehouseTerminalBlockEntity terminal,
            Map<ItemKey, Long> expected, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            helper.assertTrue(terminal.insert(stack, false).isEmpty(), "the buffer took " + stack);
            ItemCensus.change(expected, ItemKey.of(stack), stack.getCount());
        }
    }

    /** Fills every main inventory slot of {@code player} with a full stack, and adds them to the expectation. */
    private static void fillInventory(Player player, Map<ItemKey, Long> expected) {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            player.getInventory().setItem(slot, FILLER.toStack(FILLER.getMaxStackSize()));
            ItemCensus.change(expected, FILLER, FILLER.getMaxStackSize());
        }
    }

    /** A craft action through the payload entry point; fails the test when it was dropped instead of answered. */
    private static TerminalCraftOutcome submit(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            TerminalCraftPayload.Action action, CraftGrid<ItemKey> grid) {
        Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraft(player, menu.containerId, action,
                grid, Optional.empty());
        if (outcome.isEmpty()) {
            helper.fail("the craft was dropped instead of answered");
            throw new IllegalStateException("unreachable");
        }
        return outcome.get();
    }

    /** Whether a craft payload of {@code action} with the chest grid is dropped without an answer. */
    private static boolean dropped(@Nullable Player player, int containerId, TerminalCraftPayload.Action action) {
        return WarehouseTerminalMenu.submitCraft(player, containerId, action, CHEST_GRID, Optional.empty()).isEmpty();
    }

    /** A preview through the payload entry point, which answers nothing: the next push carries the view. */
    private static void preview(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            CraftGrid<ItemKey> grid, Optional<ResourceLocation> hint) {
        helper.assertTrue(WarehouseTerminalMenu.submitCraft(player, menu.containerId,
                TerminalCraftPayload.Action.PREVIEW, grid, hint).isEmpty(), "a preview has no answer of its own");
    }

    /** Fails the test unless {@code outcome} is {@code result}, with {@code crafted} items, naming {@code about}. */
    private static void assertOutcome(GameTestHelper helper, TerminalCraftOutcome outcome, TerminalCraftResult result,
            int crafted, @Nullable ItemKey about, String context) {
        helper.assertValueEqual(outcome.result(), result, context + ": how the click ended");
        helper.assertValueEqual(outcome.crafted(), crafted, context + ": items made");
        helper.assertValueEqual(outcome.about(), Optional.ofNullable(about), context + ": the item it names");
    }

    /** Switches {@code doLimitedCrafting}, remembering the value before the first switch for the batch's restore. */
    private static void setLimitedCrafting(GameTestHelper helper, boolean value) {
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING);
        if (limitedCraftingBefore == null)
            limitedCraftingBefore = rule.get();
        rule.set(value, helper.getLevel().getServer());
    }

    /** Copies of every buffer slot of {@code terminal}, in slot order. */
    private static List<ItemStack> bufferStacks(WarehouseTerminalBlockEntity terminal) {
        IItemHandler handler = terminal.externalHandler();
        List<ItemStack> stacks = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++)
            stacks.add(handler.getStackInSlot(slot).copy());
        return stacks;
    }

    /** Copies of every inventory slot of {@code player} (main slots, armour, offhand), in slot order. */
    private static List<ItemStack> inventoryStacks(Player player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            stacks.add(inventory.getItem(slot).copy());
        return stacks;
    }

    /** Fails the test unless both lists hold the same stacks slot by slot (item, count and components). */
    private static void assertSameStacks(GameTestHelper helper, List<ItemStack> before, List<ItemStack> after,
            String what) {
        helper.assertValueEqual(after.size(), before.size(), what + ": slot count");
        for (int slot = 0; slot < before.size(); slot++) {
            if (!ItemStack.matches(before.get(slot), after.get(slot)))
                helper.fail(what + ": slot " + slot + " changed from " + before.get(slot) + " to " + after.get(slot));
        }
    }

    /** Buffer slots of {@code terminal} holding {@code key}. */
    private static int slotsHolding(WarehouseTerminalBlockEntity terminal, ItemKey key) {
        int slots = 0;
        for (ItemStack stack : bufferStacks(terminal)) {
            if (key.matches(stack))
                slots++;
        }
        return slots;
    }

    /** Items of {@code key} in the player's inventory (every slot). */
    private static int countIn(Player player, ItemKey key) {
        int total = 0;
        for (ItemStack stack : inventoryStacks(player)) {
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    private static void writeCell(RegistryFriendlyByteBuf buffer, ItemKey key) {
        buffer.writeBoolean(true);
        ItemKey.STREAM_CODEC.encode(buffer, key);
    }

    private static <T> T roundTrip(GameTestHelper helper, T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        return decodeRaw(helper, codec, buffer -> codec.encode(buffer, payload));
    }

    /** Decodes what {@code writer} put into a fresh buffer, and fails the test unless every byte was read. */
    private static <T> T decodeRaw(GameTestHelper helper, StreamCodec<RegistryFriendlyByteBuf, T> codec,
            Consumer<RegistryFriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        writer.accept(buffer);
        T decoded = codec.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the codec read everything that was written");
        return decoded;
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static BlockState terminalState() {
        return WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState().setValue(WarehouseTerminalBlock.FACING,
                Direction.NORTH);
    }

    private static WarehouseTerminalBlockEntity terminalAt(GameTestHelper helper, BlockPos pos) {
        WarehouseTerminalBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse terminal block entity", pos);
        return be;
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        return standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), pos);
    }

    /** {@link #playerAt}, with a player that keeps its statistics. */
    private static CountingPlayer countingPlayerAt(GameTestHelper helper, BlockPos pos) {
        return standAt(helper, new CountingPlayer(helper), pos);
    }

    /**
     * A fake server player standing at the test-relative position, with a fresh profile so that no fake player cached
     * by an earlier run (its inventory, its recipe book) is handed back.
     */
    private static FakePlayer fakePlayerAt(GameTestHelper helper, BlockPos pos) {
        return standAt(helper, FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), FAKE_PLAYER_NAME)), pos);
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    /**
     * A mock survival player exactly like {@link GameTestHelper#makeMockPlayer}, except that it keeps the statistics it
     * is awarded. A plain mock player drops them ({@code Player#awardStat} does nothing) and so does a fake server
     * player ({@code FakePlayer#awardStat}), so without it neither "a craft counts" nor "a refused craft counts
     * nothing" could be observed.
     */
    private static final class CountingPlayer extends Player {
        private final Map<Stat<?>, Integer> awarded = new HashMap<>();

        private CountingPlayer(GameTestHelper helper) {
            super(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(UUID.randomUUID(), "test-mock-player"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public boolean isLocalPlayer() {
            return true;
        }

        @Override
        public void awardStat(Stat<?> stat, int increment) {
            awarded.merge(stat, increment, Integer::sum);
        }

        /** What {@code stat} was awarded in total. */
        int awarded(Stat<?> stat) {
            return awarded.getOrDefault(stat, 0);
        }

        /** Every statistic awarded so far. */
        Map<Stat<?>, Integer> awarded() {
            return Map.copyOf(awarded);
        }
    }
}
