package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalCraftLay;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crafting.IngredientPicker;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftLayPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of <b>laying a recipe from a recipe viewer</b> into the warehouse terminal's crafting panel (issue #24,
 * slice 4, {@code docs/warehouse-system.md} §3.4.5): the viewer sends, per cell, the items the recipe's ingredient
 * accepts ({@link TerminalCraftLayPayload}); the server picks one per cell, checks the grid against its own recipe
 * manager and hands the grid it accepted back with the crafting view ({@link TerminalCraftViewPayload#lay()}).
 * <p>
 * What is proved here is the server's half — the viewer's plugin only turns a recipe's slots into candidate lists:
 * <ul>
 * <li>{@code terminalcraftlaypicksthebufferthenthestock} — a chest laid from {@code #planks} takes the planks the
 * buffer holds a craft of, otherwise the planks the aisle has the most crafts of, never the tag's first entry by
 * default, and one type for all eight cells;</li>
 * <li>{@code terminalcraftlayshapeless} — a shapeless recipe in the cells a viewer puts it, and in any others: the
 * alternative the buffer holds, else the first;</li>
 * <li>{@code terminalcraftlayprefersplainitems} — vanilla's unused-item rule: a renamed item only when no plain one is
 * a candidate, and the rule's three marks read from the item;</li>
 * <li>{@code terminalcraftlaykeepseverypotion} — a tipped arrow's potion cell listing every potion keeps every potion,
 * because every potion hashes apart (ADR-064): the delivered one, the last of the cell, is laid;</li>
 * <li>{@code terminalcraftlaymissingingredientsstilllay} — nothing delivered, no aisle: every cell takes its first
 * candidate and the grid is laid all the same, for the panel to name what is missing;</li>
 * <li>{@code terminalcraftlayranksproducibleaboveabsent} — an ingredient a production station makes before the
 * recipe's first, which nothing holds;</li>
 * <li>{@code terminalcraftlayrefusedwhileacraftorderruns} — the grid a running craft order locks is not replaced;
 * once the order is given up the same lay goes through;</li>
 * <li>{@code terminalcraftlayrefuseswithoutsideeffects} — every payload that is not this player's own open menu at
 * this terminal, of the right shape, within reach and within the budgets, and every grid that matches no recipe,
 * changes nothing at all; one lay per tick, and a lay of keys that all share one hash code answered like any
 * other;</li>
 * <li>{@code terminalcraftlayanswerswiththegrid} — the view carries the chosen cells under a new number, its preview
 * names the result, the screen's preview of the laid grid costs no second lookup, and a preview of another grid does
 * not lose the lay;</li>
 * <li>{@code terminalcraftlaypayloadcodec} — the payload through its real codec: a round trip, a count normalised to
 * one, the hostile shapes (cells, candidates, air, size) refused on decode, and a lay too large for the wire cut down
 * to one that fits.</li>
 * </ul>
 * The tests of what is picked call the menu's own entry ({@link WarehouseTerminalMenu#layRecipe}), which the payload's
 * handler reaches after its checks; those checks and the budget of one lay per tick go through the payload's entry
 * ({@link WarehouseTerminalMenu#submitCraftLay}) in the refusal test, and so does the first lay of the answer test.
 * <b>Laying moves nothing.</b> The census is asserted on every tick of every test, with the laying players counted,
 * and the aisle tests check that the terminal asked the aisle for nothing and started no production. Buffers are seeded
 * the way the crane fills them ({@code StationBuffer#insert}).
 * <p>
 * The candidate lists are built from the server's own recipes, ingredient by ingredient, and put into the cells the
 * way JEI's crafting category lays them out (a 3x3 recipe cell by cell, a three-item shapeless recipe in the cells of
 * a 2x2 square, a one-wide recipe in the middle column), so a tag's order and a recipe's alternatives are the real
 * ones.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalCraftLayGameTests {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int OTHER_CONTAINER_ID = 77;
    private static final int FAR_AWAY_BLOCKS = 40;
    private static final String FAKE_PLAYER_NAME = "wareworks_lay";

    // --- single terminal (empty_7x5x7) ---
    private static final BlockPos TERMINAL = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos OTHER_TERMINAL = new BlockPos(5, BASE_Y, 1);

    // --- aisle (aisle_16x10x7) ---
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(1, 0, Side.RIGHT);

    private static final ItemKey OAK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey SPRUCE = ItemKey.of(Items.SPRUCE_PLANKS);
    private static final ItemKey BIRCH = ItemKey.of(Items.BIRCH_PLANKS);
    private static final ItemKey BIRCH_LOG = ItemKey.of(Items.BIRCH_LOG);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey GUNPOWDER = ItemKey.of(Items.GUNPOWDER);
    private static final ItemKey BLAZE_POWDER = ItemKey.of(Items.BLAZE_POWDER);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey CHARCOAL = ItemKey.of(Items.CHARCOAL);
    private static final ItemKey FIRE_CHARGE = ItemKey.of(Items.FIRE_CHARGE);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey ARROW = ItemKey.of(Items.ARROW);

    private static final ResourceLocation CHEST_RECIPE = ResourceLocation.withDefaultNamespace("chest");
    private static final ResourceLocation FIRE_CHARGE_RECIPE = ResourceLocation.withDefaultNamespace("fire_charge");
    private static final ResourceLocation TORCH_RECIPE = ResourceLocation.withDefaultNamespace("torch");
    /** Where JEI puts a three-item shapeless recipe: the first three cells of a 2x2 square in the top left. */
    private static final int[] SHAPELESS_THREE_CELLS = {0, 1, 3};
    /** Where JEI puts a one-wide, two-high recipe: the middle column, top two rows. */
    private static final int[] ONE_BY_TWO_CELLS = {1, 4};
    /** Two planks one above the other: sticks, a grid the panel showed before a recipe was laid. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(OAK, null, null, OAK);

    private static final int PLANKS_PER_CHEST = 8;
    private static final int SPRUCE_IN_STOCK = 32;
    private static final int OAK_IN_STOCK = 32;
    private static final int LOGS_IN_STOCK = 4;
    private static final int PLANKS_PER_LOG = 4;
    private static final int FIRE_CHARGES_PER_CRAFT = 3;
    private static final int TORCHES_PER_CRAFT = 4;
    /** Planks a buffer holds that are fewer than one chest takes. */
    private static final int LESS_THAN_A_CHEST = 3;
    /** Characters of custom data per key: nine cells of 64 such keys take about twice what a lay may. */
    private static final int HEAVY_PADDING = 100;
    /** Characters of custom data of one key: nine such keys, one per cell, are already more than a lay may take. */
    private static final int GIANT_PADDING = 4000;

    private TerminalCraftLayGameTests() {
    }

    // --- what is picked --------------------------------------------------------------------------------------------

    /**
     * <b>The delivered planks first, then the most stocked.</b> A chest laid from {@code #planks}, whose first entry
     * is oak, at a terminal of an aisle that stocks 32 spruce planks and 8 oak planks: with nothing delivered, spruce —
     * four chests' worth against one. Three delivered birch planks are not one chest and change nothing; eight are,
     * and birch wins over every stock. Every lay is one plank type in all eight cells, matches the server's chest, and
     * moves nothing, requests nothing, orders nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayPicksTheBufferThenTheStock(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, SPRUCE.toStack(SPRUCE_IN_STOCK), OAK.toStack(PLANKS_PER_CHEST));
        aisle.terminal(TERMINAL_RACK);
        Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(SPRUCE, SPRUCE_IN_STOCK, OAK, PLANKS_PER_CHEST);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while recipes are laid", player));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(SPRUCE), (long) SPRUCE_IN_STOCK,
                            "the spruce planks are in stock");
                    helper.assertValueEqual(aisle.controller().countOf(OAK), (long) PLANKS_PER_CHEST,
                            "and the oak planks");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
                    helper.assertValueEqual(chest.getFirst().getFirst(), OAK, "the planks tag names oak first");
                    helper.assertTrue(chest.getFirst().containsAll(List.of(SPRUCE, BIRCH)),
                            "and spruce and birch among the rest");
                    helper.assertTrue(chest.get(4).isEmpty(), "the chest's middle cell is empty");

                    assertLaid(helper, menu, lay(helper, player, menu, chest), ring(SPRUCE), CHEST,
                            "nothing delivered: the planks the aisle has the most chests of");
                    seed(helper, terminal, expected, BIRCH.toStack(LESS_THAN_A_CHEST));
                    assertLaid(helper, menu, lay(helper, player, menu, chest), ring(SPRUCE), CHEST,
                            "three delivered birch planks are not one chest");
                    seed(helper, terminal, expected, BIRCH.toStack(PLANKS_PER_CHEST - LESS_THAN_A_CHEST));
                    List<ItemStack> buffer = bufferStacks(terminal);
                    assertLaid(helper, menu, lay(helper, player, menu, chest), ring(BIRCH), CHEST,
                            "eight delivered birch planks win over every stock");
                    helper.assertValueEqual(menu.craftLay().number(), 3, "three lays, three numbers");

                    assertSameStacks(helper, buffer, bufferStacks(terminal), "the buffer after a lay");
                    assertAskedNothing(helper, aisle, "laying a recipe");
                    helper.assertValueEqual(aisle.controller().countOf(SPRUCE), (long) SPRUCE_IN_STOCK,
                            "the stock is untouched");
                })
                .thenSucceed();
    }

    /**
     * <b>A shapeless recipe.</b> A fire charge — gunpowder, blaze powder, and coal or charcoal — in the three cells a
     * viewer puts a three-item shapeless recipe. With charcoal delivered, charcoal, although coal is the recipe's
     * first alternative; with nothing delivered, coal. The same candidates in three other cells make the same recipe,
     * because a shapeless recipe matches in any arrangement.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayShapeless(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        helper.setBlock(OTHER_TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Player other = playerAt(helper, OTHER_TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a shapeless recipe is laid", player,
                other));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, GUNPOWDER.toStack(1), BLAZE_POWDER.toStack(1), CHARCOAL.toStack(1));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            List<List<ItemKey>> charge = candidates(helper, FIRE_CHARGE_RECIPE, SHAPELESS_THREE_CELLS);
            helper.assertValueEqual(charge.get(3), List.of(COAL, CHARCOAL),
                    "the third ingredient accepts coal or charcoal, coal first");

            TerminalCraftOutcome laid = lay(helper, player, menu, charge);
            assertLaid(helper, menu, laid, CraftGrid.of(GUNPOWDER, BLAZE_POWDER, null, CHARCOAL), FIRE_CHARGE,
                    "the delivered charcoal");
            helper.assertValueEqual(menu.craftView().recipe(), Optional.of(FIRE_CHARGE_RECIPE),
                    "the server's fire charge recipe");
            helper.assertTrue(ItemStack.matches(menu.craftView().result(), FIRE_CHARGE.toStack(FIRE_CHARGES_PER_CRAFT)),
                    "three fire charges per craft: " + menu.craftView().result());

            List<List<ItemKey>> moved = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, List.of()));
            moved.set(8, charge.get(0));
            moved.set(4, charge.get(1));
            moved.set(0, charge.get(3));
            assertLaid(helper, menu, lay(helper, player, menu, moved),
                    CraftGrid.of(CHARCOAL, null, null, null, BLAZE_POWDER, null, null, null, GUNPOWDER), FIRE_CHARGE,
                    "the same ingredients in other cells");

            // Nothing delivered at the other terminal: the recipe's first alternative.
            WarehouseTerminalMenu otherMenu = openMenu(other, terminalAt(helper, OTHER_TERMINAL));
            assertLaid(helper, otherMenu, lay(helper, other, otherMenu, charge),
                    CraftGrid.of(GUNPOWDER, BLAZE_POWDER, null, COAL), FIRE_CHARGE, "nothing delivered: coal");
        }).thenSucceed();
    }

    /**
     * <b>Vanilla's unused-item rule.</b> A renamed, damaged or enchanted item is never taken for a recipe while a plain
     * one would do ({@code Inventory#findSlotMatchingUnusedItem}). Each ring cell of a chest accepts renamed oak planks
     * or birch planks, and the buffer holds eight renamed oak planks: birch is laid all the same. When every candidate
     * is renamed, the rule cannot choose, and the delivered ones win. The rule's three marks are read from the item.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayPrefersPlainItems(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while plain items are preferred", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ItemKey namedOak = renamed(OAK, "Spare oak");
            ItemKey namedSpruce = renamed(SPRUCE, "Spare spruce");
            ItemStack worn = new ItemStack(Items.IRON_SWORD);
            worn.setDamageValue(5);
            ItemStack enchanted = new ItemStack(Items.IRON_SWORD);
            enchanted.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SHARPNESS), 1);
            helper.assertTrue(TerminalCraftLay.plain(OAK), "plain planks are plain");
            helper.assertTrue(TerminalCraftLay.plain(ItemKey.of(Items.IRON_SWORD)), "and so is a new sword");
            helper.assertFalse(TerminalCraftLay.plain(namedOak), "a renamed item is not");
            helper.assertFalse(TerminalCraftLay.plain(ItemKey.of(worn)), "nor a damaged one");
            helper.assertFalse(TerminalCraftLay.plain(ItemKey.of(enchanted)), "nor an enchanted one");

            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, namedOak.toStack(PLANKS_PER_CHEST));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            assertLaid(helper, menu, lay(helper, player, menu, ringOf(List.of(namedOak, BIRCH))), ring(BIRCH), CHEST,
                    "plain birch planks, although renamed oak planks are delivered");
            assertLaid(helper, menu, lay(helper, player, menu, ringOf(List.of(namedSpruce, namedOak))),
                    ring(namedOak), CHEST, "every candidate renamed: the delivered ones");
        }).thenSucceed();
    }

    /**
     * <b>A cell that lists every potion keeps every potion.</b> A tipped arrow laid the way a viewer shows it, through
     * the payload's codec: arrows in the eight ring cells and, in the middle, every lingering potion of the registry —
     * far more than the picker keeps of one hash code ({@value IngredientPicker#MAX_SAME_HASH}). Every potion hashes
     * apart (ADR-064), so none is dropped: the one the buffer holds, the registry's last, is laid, and the server's
     * tipped arrow recipe names arrows of that potion.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayKeepsEveryPotion(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while every potion is laid", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            List<Holder.Reference<Potion>> registered = BuiltInRegistries.POTION.holders().toList();
            List<ItemKey> potions = registered.stream()
                    .map(potion -> ItemKey.of(PotionContents.createItemStack(Items.LINGERING_POTION, potion)))
                    .toList();
            helper.assertTrue(potions.size() > IngredientPicker.MAX_SAME_HASH
                    && potions.size() <= TerminalCraftLayPayload.MAX_CANDIDATES,
                    "more potions than the picker keeps of one hash code, and no more than a cell may list: "
                            + potions.size());
            ItemKey held = potions.getLast();
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, held.toStack(1));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            List<List<ItemKey>> cells = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, List.of(ARROW)));
            cells.set(4, potions);
            TerminalCraftLayPayload sent = TerminalCraftGameTests.roundTrip(helper, payload(menu, cells),
                    TerminalCraftLayPayload.STREAM_CODEC);
            helper.assertValueEqual(sent.cells().get(4).size(), potions.size(), "every potion went over the wire");
            TerminalCraftOutcome laid = WarehouseTerminalMenu.submitCraftLay(player, sent)
                    .orElseThrow(() -> new GameTestAssertException("the lay was dropped instead of answered"));
            ItemKey arrows = ItemKey.of(PotionContents.createItemStack(Items.TIPPED_ARROW, registered.getLast()));
            assertLaid(helper, menu, laid, CraftGrid.of(ARROW, ARROW, ARROW, ARROW, held, ARROW, ARROW, ARROW, ARROW),
                    arrows, "the delivered potion, the last of the cell");
        }).thenSucceed();
    }

    /**
     * <b>A missing ingredient still lays.</b> Nothing delivered and no aisle: a chest takes the planks tag's first
     * entry in every cell, and a torch coal, its first alternative, in the middle column where a viewer puts a one-wide
     * recipe. Both grids are laid, so the panel can name what is missing and Fetch can ask for it.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayMissingIngredientsStillLay(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while missing ingredients are laid",
                player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalMenu menu = openMenu(player, terminalAt(helper, TERMINAL));
            List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
            ItemKey first = chest.getFirst().getFirst();
            assertLaid(helper, menu, lay(helper, player, menu, chest), ring(first), CHEST,
                    "every cell its first candidate");
            helper.assertValueEqual(menu.craftView().keys(), List.of(new TerminalCraftViewPayload.KeyView(first, 0L,
                    false, false)), "and the panel is told that none is here");

            List<List<ItemKey>> torch = candidates(helper, TORCH_RECIPE, ONE_BY_TWO_CELLS);
            helper.assertValueEqual(torch.get(1), List.of(COAL, CHARCOAL), "a torch takes coal or charcoal");
            assertLaid(helper, menu, lay(helper, player, menu, torch), CraftGrid.of(null, COAL, null, null, STICK),
                    TORCH, "coal over a stick");
            helper.assertTrue(ItemStack.matches(menu.craftView().result(), TORCH.toStack(TORCHES_PER_CRAFT)),
                    "four torches per craft: " + menu.craftView().result());
        }).thenSucceed();
    }

    /**
     * <b>Producible before absent.</b> No planks anywhere, but a production station of the aisle turns birch logs into
     * birch planks: a chest laid from {@code #planks} takes birch, not the tag's first entry, oak, which nothing holds
     * or makes. Laying it orders nothing: no request, no production order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayRanksProducibleAboveAbsent(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, BIRCH_LOG.toStack(LOGS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity saw = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(saw.setPatternEntry(0, 0, BIRCH_LOG, 1), "the pattern's ingredient");
        helper.assertTrue(saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BIRCH, PLANKS_PER_LOG),
                "the pattern's result");
        Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(BIRCH_LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a makeable recipe is laid", player));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(BIRCH_LOG), (long) LOGS_IN_STOCK,
                            "the logs are in stock");
                    helper.assertTrue(aisle.controller().producibleKeys().contains(BIRCH),
                            "and the aisle can make birch planks");
                })
                .thenExecute(() -> {
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
                    List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
                    helper.assertValueEqual(chest.getFirst().getFirst(), OAK, "oak comes first");
                    assertLaid(helper, menu, lay(helper, player, menu, chest), ring(BIRCH), CHEST,
                            "the planks the aisle can make");
                    helper.assertValueEqual(menu.craftView().keyView(BIRCH)
                            .map(TerminalCraftViewPayload.KeyView::producible), Optional.of(true),
                            "and the panel is told so");
                    assertAskedNothing(helper, aisle, "laying a makeable recipe");
                })
                .thenSucceed();
    }

    // --- refusals --------------------------------------------------------------------------------------------------

    /**
     * <b>A running craft order locks the grid.</b> Its grid is the one every screen at the terminal shows until it ends
     * (ADR-062), so a lay is answered {@link TerminalCraftResult#ALREADY_RUNNING} and changes nothing: not the grid,
     * not the view, not the order's requests. Once the order is given up, the same lay goes through.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayRefusedWhileACraftOrderRuns(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, OAK.toStack(OAK_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(OAK, OAK_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the grid is locked", player));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(OAK), (long) OAK_IN_STOCK,
                            "the planks are in stock");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    TerminalCraftOutcome fetch = menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, ring(OAK), Optional.empty(), 1, 0L, false));
                    helper.assertValueEqual(fetch.result(), TerminalCraftResult.STARTED, "a chest is fetched for");
                    helper.assertTrue(terminal.hasOpenCraftOrder(), "the craft order runs");
                })
                // The order asks the aisle in its own pass, on a later tick; without a motor nothing is delivered.
                .thenWaitUntil(() -> helper.assertFalse(terminalRequests(aisle).isEmpty(),
                        "the order asked the aisle for the planks"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    WarehouseTerminalMenu menu = (WarehouseTerminalMenu) player.containerMenu;
                    MenuState before = MenuState.of(menu);
                    helper.assertValueEqual(before.cells(), ring(OAK), "the grid the order fetches for");
                    List<?> requests = terminalRequests(aisle);

                    List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
                    assertRefused(helper, lay(helper, player, menu, chest), TerminalCraftResult.ALREADY_RUNNING,
                            "a lay while the order runs");
                    before.assertSame(helper, menu, 0, "a lay into a locked grid");
                    helper.assertValueEqual(terminalRequests(aisle), requests, "the order's requests are untouched");
                    helper.assertTrue(terminal.hasOpenCraftOrder(), "and the order still runs");

                    TerminalCraftOutcome cancel = menu.craftOrderAction(player,
                            TerminalCraftOrderPayload.of(menu.containerId, TerminalCraftOrderPayload.Action.CANCEL));
                    helper.assertValueEqual(cancel.result(), TerminalCraftResult.CANCELLED, "the order is given up");
                    List<?> afterCancel = terminalRequests(aisle);
                    assertLaid(helper, menu, lay(helper, player, menu, chest), ring(OAK), CHEST,
                            "the same lay once the grid is free");
                    helper.assertValueEqual(terminalRequests(aisle), afterCancel,
                            "the lay asked the aisle for nothing");
                    helper.assertFalse(terminal.hasOpenCraftOrder(), "and started no order");
                })
                .thenWaitUntil(() -> assertAskedNothing(helper, aisle, "the cancelled order and the lay"))
                .thenSucceed();
    }

    /**
     * <b>Nothing but the player's own open menu, at this terminal, of the right shape, within reach and within the
     * budgets, with a grid that matches a recipe.</b> After a lay that is kept, every payload below changes nothing —
     * not the grid, not the view the panel is pushed, not the lay it carries, not the buffer, not the census:
     * <ul>
     * <li>no player, a player without a terminal menu, another container id, a payload naming another terminal, a
     * stranger, a fake player, eight cells, ten cells, and sixty-five candidates in a cell — dropped without an
     * answer;</li>
     * <li>no candidate in any cell — {@link TerminalCraftResult#GRID_EMPTY}; a grid no recipe matches, also with a
     * hint naming the chest — {@link TerminalCraftResult#NOT_LAID}, after its one lookup; ten cells handed to the menu
     * in-process — {@link TerminalCraftResult#NOT_LAID}; a stranger handed this player's menu and a player who walked
     * away — {@link TerminalCraftResult#OUT_OF_REACH};</li>
     * <li>a second lay in one tick — dropped: a menu takes one lay per tick, and the first one of a tick goes through
     * the payload's entry like a click; a lay after the menu answered eight actions in a tick — dropped, because a lay
     * also counts as one of them;</li>
     * <li>nine cells of sixty-four diamonds whose names differ only in colour, so they all hash alike, through the
     * payload's codec — answered {@link TerminalCraftResult#NOT_LAID} after one lookup: the picker keeps four keys of
     * one hash code and drops the rest, so such a lay costs no more than an honest one.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayRefusesWithoutSideEffects(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        helper.setBlock(OTHER_TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while hostile lays arrive", player));
        WarehouseTerminalMenu[] own = new WarehouseTerminalMenu[1];

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, BIRCH.toStack(PLANKS_PER_CHEST));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            own[0] = menu;
            List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
            assertLaid(helper, menu, lay(helper, player, menu, chest), ring(BIRCH), CHEST, "the lay that is kept");
            MenuState before = MenuState.of(menu);
            List<ItemStack> buffer = bufferStacks(terminal);

            // Dropped without an answer, each before the budgets, which this tick has not touched yet.
            helper.assertTrue(dropped(null, payload(menu, chest)), "no player, no lay");
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(dropped(player, payload(menu, chest)), "a player without a terminal menu lays nothing");
            player.containerMenu = menu;
            helper.assertTrue(dropped(player, new TerminalCraftLayPayload(OTHER_CONTAINER_ID,
                    terminal.getBlockPos(), Optional.empty(), chest, true)), "a payload for another menu");
            helper.assertTrue(dropped(player, new TerminalCraftLayPayload(menu.containerId,
                    helper.absolutePos(OTHER_TERMINAL), Optional.empty(), chest, true)),
                    "a payload naming another terminal than the open menu's");
            Player stranger = playerAt(helper, TERMINAL);
            helper.assertTrue(dropped(stranger, payload(menu, chest)), "another player cannot use this menu");
            FakePlayer fake = fakePlayerAt(helper, TERMINAL);
            WarehouseTerminalMenu fakeMenu = openMenu(fake, terminal);
            helper.assertTrue(dropped(fake, payload(fakeMenu, chest)), "a fake player has no viewer to lay from");
            helper.assertTrue(dropped(player, payload(menu, chest.subList(0, CraftGrid.SIZE - 1))), "eight cells");
            List<List<ItemKey>> tenCells = new ArrayList<>(chest);
            tenCells.add(List.of(OAK));
            helper.assertTrue(dropped(player, payload(menu, tenCells)), "ten cells");
            List<List<ItemKey>> crowded = new ArrayList<>(chest);
            crowded.set(0, Collections.nCopies(TerminalCraftLayPayload.MAX_CANDIDATES + 1, OAK));
            helper.assertTrue(dropped(player, payload(menu, crowded)), "sixty-five candidates in one cell");
            before.assertSame(helper, menu, 0, "the dropped payloads");

            // Answered, and refused.
            List<List<ItemKey>> nothing = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, List.of()));
            assertRefused(helper, lay(helper, player, menu, nothing), TerminalCraftResult.GRID_EMPTY,
                    "no candidate in any cell");
            before.assertSame(helper, menu, 0, "an empty lay");
            List<List<ItemKey>> diamond = new ArrayList<>(nothing);
            diamond.set(0, List.of(DIAMOND));
            assertRefused(helper, lay(helper, player, menu, diamond), TerminalCraftResult.NOT_LAID,
                    "a diamond alone matches no recipe");
            before.assertSame(helper, menu, 1, "a lay that matches no recipe, after its one lookup");
            assertRefused(helper, menu.layRecipe(player, diamond, Optional.of(CHEST_RECIPE), true),
                    TerminalCraftResult.NOT_LAID, "a hint naming the chest does not make a diamond a chest");
            before.assertSame(helper, menu, 2, "a hinted lay that matches no recipe");
            assertRefused(helper, menu.layRecipe(player, tenCells, Optional.empty(), true),
                    TerminalCraftResult.NOT_LAID, "ten cells handed to the menu in-process");
            assertRefused(helper, menu.layRecipe(stranger, chest, Optional.empty(), true),
                    TerminalCraftResult.OUT_OF_REACH, "a stranger handed this player's menu");
            Player far = helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(TERMINAL));
            far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
            WarehouseTerminalMenu farMenu = openMenu(far, terminal);
            assertRefused(helper, lay(helper, far, farMenu, chest), TerminalCraftResult.OUT_OF_REACH,
                    "a player who walked away");
            helper.assertValueEqual(farMenu.craftLay(), TerminalCraftViewPayload.Lay.NONE,
                    "whose menu got no lay");
            before.assertSame(helper, menu, 2, "every refusal");
            assertSameStacks(helper, buffer, bufferStacks(terminal), "the buffer after every refusal");
        }).thenIdle(1).thenExecute(() -> {
            // One lay per tick: the first goes through the payload's entry like a click, the second is dropped.
            WarehouseTerminalMenu menu = own[0];
            List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
            int number = menu.craftLay().number();
            assertLaid(helper, menu, submit(helper, player, menu, chest), ring(BIRCH), CHEST,
                    "the first lay of a tick, through the payload's entry");
            MenuState before = MenuState.of(menu);
            helper.assertTrue(dropped(player, payload(menu, chest)), "a second lay in the same tick is dropped");
            before.assertSame(helper, menu, 0, "the dropped second lay");
            helper.assertValueEqual(menu.craftLay().number(), number + WarehouseTerminalMenu.MAX_LAYS_PER_TICK,
                    "one lay was taken in that tick, and no second");
        }).thenIdle(1).thenExecute(() -> {
            // A lay counts as one of the eight actions a menu answers per tick: after eight previews it is dropped.
            WarehouseTerminalMenu menu = own[0];
            for (int i = 0; i < WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK; i++)
                preview(helper, player, menu, ring(BIRCH), Optional.of(CHEST_RECIPE));
            MenuState before = MenuState.of(menu);
            helper.assertTrue(dropped(player, payload(menu, candidates(helper, CHEST_RECIPE))),
                    "a lay after eight actions of the tick is dropped");
            before.assertSame(helper, menu, 0, "the lay past the actions of the tick");
        }).thenIdle(1).thenExecute(() -> {
            // Keys that all share one hash code, as a hostile client can make them: the picker keeps four of them.
            WarehouseTerminalMenu menu = own[0];
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            List<ItemKey> colliding = sameHash(Items.DIAMOND, CraftGrid.SIZE * TerminalCraftLayPayload.MAX_CANDIDATES);
            helper.assertValueEqual(colliding.stream().mapToInt(ItemKey::hashCode).distinct().count(), 1L,
                    "every key has the one hash code");
            helper.assertFalse(colliding.get(0).equals(colliding.get(1)), "and the keys are distinct");
            List<List<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
            for (int cell = 0; cell < CraftGrid.SIZE; cell++)
                cells.add(colliding.subList(cell * TerminalCraftLayPayload.MAX_CANDIDATES,
                        (cell + 1) * TerminalCraftLayPayload.MAX_CANDIDATES));
            TerminalCraftLayPayload hostile = TerminalCraftGameTests.roundTrip(helper, new TerminalCraftLayPayload(
                    menu.containerId, terminal.getBlockPos(), Optional.empty(), cells, true),
                    TerminalCraftLayPayload.STREAM_CODEC);
            helper.assertValueEqual(hostile.cells().get(CraftGrid.SIZE - 1).getLast().hashCode(),
                    colliding.getFirst().hashCode(), "the keys still share it after the codec");
            MenuState before = MenuState.of(menu);
            Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraftLay(player, hostile);
            helper.assertValueEqual(outcome.map(TerminalCraftOutcome::result),
                    Optional.of(TerminalCraftResult.NOT_LAID), "the colliding lay is answered, and refused");
            before.assertSame(helper, menu, 1, "the colliding lay, after its one lookup");
        }).thenSucceed();
    }

    // --- the answer ------------------------------------------------------------------------------------------------

    /**
     * <b>The view answers the lay with the chosen cells.</b> A chest laid with the panel to fold out comes back as lay
     * number one: the birch grid the server picked, the chest recipe it matched, and the fold. The view is the view of
     * that grid, so its preview names the chest and the birch planks the buffer holds. The screen then previews the
     * laid grid with the lay's recipe as the hint, which costs no second lookup. A preview of the grid the panel showed
     * before — what a screen handed back by the viewer sends first — gives a view of that grid that still carries the
     * lay, so the screen can adopt it. Laying the same recipe again, folded in, is lay number two and a new view. The
     * view and the answer survive their codecs, and nothing moved.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalCraftLayAnswersWithTheGrid(GameTestHelper helper) {
        helper.setBlock(TERMINAL, terminalState());
        Player player = playerAt(helper, TERMINAL);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the lay is answered", player));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            seed(helper, terminal, expected, BIRCH.toStack(PLANKS_PER_CHEST));
            List<ItemStack> buffer = bufferStacks(terminal);
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertValueEqual(menu.craftView().lay(), TerminalCraftViewPayload.Lay.NONE, "no lay yet");
            List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);

            TerminalCraftOutcome laid = WarehouseTerminalMenu.submitCraftLay(player,
                    new TerminalCraftLayPayload(menu.containerId, terminal.getBlockPos(), Optional.of(CHEST_RECIPE),
                            chest, true)).orElseThrow(() -> new GameTestAssertException("the lay was dropped"));
            assertLaid(helper, menu, laid, ring(BIRCH), CHEST, "the chest");
            helper.assertValueEqual(laid.crafted(), 0, "a lay crafts nothing");
            TerminalCraftViewPayload view = menu.craftView();
            TerminalCraftViewPayload.Lay first = new TerminalCraftViewPayload.Lay(1, ring(BIRCH),
                    Optional.of(CHEST_RECIPE), true);
            helper.assertValueEqual(view.lay(), first, "the view answers the lay with the chosen cells");
            helper.assertTrue(view.lay().isPresent(), "a lay that is there");
            helper.assertValueEqual(view.cells(), ring(BIRCH), "and is the view of that grid");
            helper.assertValueEqual(view.recipe(), Optional.of(CHEST_RECIPE), "whose preview names the chest");
            helper.assertTrue(ItemStack.matches(view.result(), CHEST.toStack(1)), "one chest: " + view.result());
            helper.assertFalse(view.locked(), "a mock player has no recipe book to be locked out by");
            helper.assertTrue(view.noAisle(), "the lone terminal has no aisle");
            helper.assertValueEqual(view.keys(), List.of(new TerminalCraftViewPayload.KeyView(BIRCH, 0L, false,
                    false)), "one entry for the birch planks");
            helper.assertValueEqual(menu.craftPreviewLookups(), 1, "one lookup for the lay");

            preview(helper, player, menu, view.lay().cells(), view.lay().recipe());
            helper.assertValueEqual(menu.craftPreviewLookups(), 1,
                    "the screen's preview of the laid grid with the lay's recipe costs no lookup");
            helper.assertValueEqual(menu.craftView(), view, "and changes nothing the panel is told");

            preview(helper, player, menu, STICK_GRID, Optional.empty());
            TerminalCraftViewPayload before = menu.craftView();
            helper.assertValueEqual(before.cells(), STICK_GRID, "a preview of the grid the panel showed before");
            helper.assertValueEqual(before.lay(), first, "still carries the lay for the screen to adopt");

            assertLaid(helper, menu, lay(helper, player, menu, chest, false), ring(BIRCH), CHEST, "the chest again");
            TerminalCraftViewPayload again = menu.craftView();
            helper.assertValueEqual(again.lay(), new TerminalCraftViewPayload.Lay(2, ring(BIRCH),
                    Optional.of(CHEST_RECIPE), false), "lay number two, folded in");
            helper.assertFalse(again.equals(view), "another view than the first lay's, so it is pushed");

            helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper, again,
                    TerminalCraftViewPayload.STREAM_CODEC), again, "a view carrying a lay through its codec");
            TerminalCraftAnswerPayload answer = TerminalCraftAnswerPayload.of(menu.containerId, laid);
            helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper, answer,
                    TerminalCraftAnswerPayload.STREAM_CODEC), answer, "and the answer, LAID by name");
            assertSameStacks(helper, buffer, bufferStacks(terminal), "the buffer after the lays");
        }).thenSucceed();
    }

    // --- the payload -----------------------------------------------------------------------------------------------

    /**
     * The lay payload through its real codec: a round trip; a candidate written with a count of five reads as the one
     * item it names; and every other shape refused on decode, before the handler could see it — eight cells, ten cells,
     * a cell claiming sixty-five candidates or a negative number of them, air as a candidate, both as an empty stack
     * and as an air item with a count, and a lay of more than {@value TerminalCraftLayPayload#MAX_BYTES} bytes. A
     * payload of another shape or size is never written either. What a client sends ({@code fitted}) is an honest chest
     * as it is, a heavy lay cut down to the first candidates of each cell until it fits — and it then decodes — and
     * nothing at all when one candidate per cell is already too large.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalCraftLayPayloadCodec(GameTestHelper helper) {
        List<List<ItemKey>> chest = candidates(helper, CHEST_RECIPE);
        TerminalCraftLayPayload payload = new TerminalCraftLayPayload(3, new BlockPos(1, 2, 3),
                Optional.of(CHEST_RECIPE), chest, true);
        helper.assertTrue(payload.wellFormed(), "nine cells of a real recipe");
        helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper, payload,
                TerminalCraftLayPayload.STREAM_CODEC), payload, "a lay payload round trip");

        TerminalCraftLayPayload counted = TerminalCraftGameTests.decodeRaw(helper,
                TerminalCraftLayPayload.STREAM_CODEC, buffer -> {
                    writeHead(buffer, CraftGrid.SIZE);
                    buffer.writeVarInt(1);
                    ItemStack.STREAM_CODEC.encode(buffer, OAK.toStack(5));
                    for (int cell = 1; cell < CraftGrid.SIZE; cell++)
                        buffer.writeVarInt(0);
                    buffer.writeBoolean(false);
                });
        helper.assertValueEqual(counted.cells().getFirst(), List.of(OAK), "a count of five reads as one oak plank");

        assertRefusedOnDecode(helper, "eight cells", buffer -> {
            writeHead(buffer, CraftGrid.SIZE - 1);
            for (int cell = 0; cell < CraftGrid.SIZE - 1; cell++)
                buffer.writeVarInt(0);
            buffer.writeBoolean(false);
        });
        assertRefusedOnDecode(helper, "ten cells", buffer -> {
            writeHead(buffer, CraftGrid.SIZE + 1);
            for (int cell = 0; cell < CraftGrid.SIZE + 1; cell++)
                buffer.writeVarInt(0);
            buffer.writeBoolean(false);
        });
        assertRefusedOnDecode(helper, "sixty-five candidates", buffer -> {
            writeHead(buffer, CraftGrid.SIZE);
            buffer.writeVarInt(TerminalCraftLayPayload.MAX_CANDIDATES + 1);
            for (int i = 0; i <= TerminalCraftLayPayload.MAX_CANDIDATES; i++)
                ItemKey.STREAM_CODEC.encode(buffer, OAK);
        });
        assertRefusedOnDecode(helper, "a negative number of candidates", buffer -> {
            writeHead(buffer, CraftGrid.SIZE);
            buffer.writeVarInt(-1);
        });
        assertRefusedOnDecode(helper, "an empty stack as a candidate", buffer -> {
            writeHead(buffer, CraftGrid.SIZE);
            buffer.writeVarInt(1);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, ItemStack.EMPTY);
        });
        assertRefusedOnDecode(helper, "air with a count as a candidate", buffer -> {
            writeHead(buffer, CraftGrid.SIZE);
            buffer.writeVarInt(1);
            buffer.writeVarInt(1);
            ByteBufCodecs.holderRegistry(Registries.ITEM).encode(buffer,
                    BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR));
            DataComponentPatch.STREAM_CODEC.encode(buffer, DataComponentPatch.EMPTY);
        });

        List<List<ItemKey>> tenCells = new ArrayList<>(chest);
        tenCells.add(List.of());
        TerminalCraftLayPayload tooLong = new TerminalCraftLayPayload(3, BlockPos.ZERO, Optional.empty(), tenCells,
                false);
        helper.assertFalse(tooLong.wellFormed(), "ten cells are no lay");
        try {
            TerminalCraftLayPayload.STREAM_CODEC.encode(newBuffer(helper), tooLong);
            helper.fail("a payload of ten cells was written");
        } catch (EncoderException refused) {
            // expected: a payload of another shape is never sent
        }

        // The size: what the server thread may be made to hash and compare per lay.
        RegistryAccess registries = helper.getLevel().registryAccess();
        List<List<ItemKey>> heavy = new ArrayList<>(CraftGrid.SIZE);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++)
            heavy.add(padded(Items.OAK_PLANKS, cell * TerminalCraftLayPayload.MAX_CANDIDATES,
                    TerminalCraftLayPayload.MAX_CANDIDATES, HEAVY_PADDING));
        TerminalCraftLayPayload tooHeavy = new TerminalCraftLayPayload(3, BlockPos.ZERO, Optional.empty(), heavy, true);
        helper.assertTrue(tooHeavy.wellFormed(), "a heavy lay has the shape of a lay");
        int heavySize = tooHeavy.encodedSize(registries, ConnectionType.NEOFORGE);
        helper.assertTrue(heavySize > TerminalCraftLayPayload.MAX_BYTES, "the heavy lay takes " + heavySize
                + " bytes, more than " + TerminalCraftLayPayload.MAX_BYTES);
        assertRefusedOnDecode(helper, "a lay of " + heavySize + " bytes", buffer -> {
            writeHead(buffer, CraftGrid.SIZE);
            for (List<ItemKey> cell : heavy) {
                buffer.writeVarInt(cell.size());
                for (ItemKey key : cell)
                    ItemKey.STREAM_CODEC.encode(buffer, key);
            }
            buffer.writeBoolean(true);
        });
        try {
            TerminalCraftLayPayload.STREAM_CODEC.encode(newBuffer(helper), tooHeavy);
            helper.fail("a payload of " + heavySize + " bytes was written");
        } catch (EncoderException refused) {
            // expected: a payload the server would refuse is never sent
        }
        TerminalCraftLayPayload fitted = TerminalCraftLayPayload.fitted(3, BlockPos.ZERO, Optional.empty(), heavy, true,
                registries, ConnectionType.NEOFORGE).orElseThrow(() -> new GameTestAssertException(
                        "the heavy lay was not cut down to one that fits"));
        helper.assertTrue(fitted.encodedSize(registries, ConnectionType.NEOFORGE) <= TerminalCraftLayPayload.MAX_BYTES,
                "the fitted lay fits");
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            List<ItemKey> kept = fitted.cells().get(cell);
            helper.assertTrue(!kept.isEmpty() && kept.size() < heavy.get(cell).size()
                    && kept.equals(heavy.get(cell).subList(0, kept.size())),
                    "cell " + cell + " keeps its first candidates, and not all of them: " + kept.size());
        }
        helper.assertValueEqual(TerminalCraftGameTests.roundTrip(helper, fitted, TerminalCraftLayPayload.STREAM_CODEC),
                fitted, "the fitted lay decodes");
        helper.assertValueEqual(TerminalCraftLayPayload.fitted(3, payload.terminal(), Optional.of(CHEST_RECIPE), chest,
                true, registries, ConnectionType.NEOFORGE), Optional.of(payload), "an honest chest is sent as it is");
        List<List<ItemKey>> giant = new ArrayList<>(CraftGrid.SIZE);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++)
            giant.add(padded(Items.OAK_PLANKS, cell, 1, GIANT_PADDING));
        helper.assertValueEqual(TerminalCraftLayPayload.fitted(3, BlockPos.ZERO, Optional.empty(), giant, true,
                registries, ConnectionType.NEOFORGE), Optional.empty(),
                "nothing is sent when one candidate per cell is already too large");
        helper.succeed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * What a refused lay leaves as it was: the grid, and the view the panel is pushed — its recipe, result, lock, item
     * numbers and the lay it carries — and the recipe lookups, of which a refusal may cost the one that found no
     * recipe.
     */
    private record MenuState(CraftGrid<ItemKey> cells, TerminalCraftViewPayload view, int lookups) {
        static MenuState of(WarehouseTerminalMenu menu) {
            return new MenuState(menu.craftCells(), menu.craftView(), menu.craftPreviewLookups());
        }

        /** Fails unless {@code menu} is as it was, after {@code lookups} more recipe lookups. */
        void assertSame(GameTestHelper helper, WarehouseTerminalMenu menu, int lookups, String context) {
            helper.assertValueEqual(menu.craftCells(), cells, context + ": the grid");
            helper.assertValueEqual(menu.craftView(), view, context + ": the view, lay included");
            helper.assertValueEqual(menu.craftPreviewLookups(), this.lookups + lookups, context + ": the lookups");
        }
    }

    /**
     * The candidates a recipe viewer shows for each cell of the server's recipe {@code id}: each ingredient's items,
     * once each and in its own order, in cell {@code cellOfIngredient[i]} — or, without cells given, ingredient
     * {@code i} in cell {@code i}, which is how a 3x3 recipe is laid out.
     */
    private static List<List<ItemKey>> candidates(GameTestHelper helper, ResourceLocation id,
            int... cellOfIngredient) {
        RecipeHolder<?> recipe = helper.getLevel().getRecipeManager().byKey(id)
                .orElseThrow(() -> new GameTestAssertException("no recipe " + id));
        List<Ingredient> ingredients = recipe.value().getIngredients();
        List<List<ItemKey>> cells = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, List.of()));
        for (int i = 0; i < ingredients.size(); i++) {
            List<ItemKey> keys = new ArrayList<>();
            for (ItemStack stack : ingredients.get(i).getItems())
                ItemKey.fromStack(stack).filter(key -> !keys.contains(key)).ifPresent(keys::add);
            cells.set(cellOfIngredient.length == 0 ? i : cellOfIngredient[i], List.copyOf(keys));
        }
        return cells;
    }

    /** {@code candidates} in the eight ring cells of a chest, the middle left empty. */
    private static List<List<ItemKey>> ringOf(List<ItemKey> candidates) {
        List<List<ItemKey>> cells = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, candidates));
        cells.set(4, List.of());
        return cells;
    }

    /** {@code key} in the eight ring cells, the middle left empty: a chest. */
    private static CraftGrid<ItemKey> ring(ItemKey key) {
        return CraftGrid.of(key, key, key, key, null, key, key, key, key);
    }

    /**
     * {@code count} distinct keys of {@code item}, numbered from 0, that all share one hash code, as a hostile client
     * can make them without even searching: the custom name {@code "x"} in colour {@code i}. An item key's hash reads
     * a name's text and never its style (ADR-064), so the keys differ by their name's colour and hash alike. (The old
     * custom data construction, {@code {a: i, b: w}} with {@code w} cancelling vanilla's sum of {@code name ^ value},
     * no longer collides, because the key hash mixes every compound entry. Custom data can still be made to collide on
     * purpose: strings hash with {@code String#hashCode} ({@code "Aa"} and {@code "BB"}), and {@code mix} can be
     * inverted. A name's colour simply needs no construction at all.)
     */
    private static List<ItemKey> sameHash(ItemLike item, int count) {
        List<ItemKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int colour = i;
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("x").withStyle(style -> style.withColor(colour)));
            keys.add(ItemKey.of(stack));
        }
        return keys;
    }

    /** {@code count} distinct keys of {@code item}, numbered from {@code first}, each with {@code padding} chars. */
    private static List<ItemKey> padded(ItemLike item, int first, int count, int padding) {
        String pad = "x".repeat(padding);
        List<ItemKey> keys = new ArrayList<>(count);
        for (int i = first; i < first + count; i++) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("n", i);
            tag.putString("pad", pad);
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            keys.add(ItemKey.of(stack));
        }
        return keys;
    }

    /** {@code key} with a custom name, which vanilla's unused-item rule passes over. */
    private static ItemKey renamed(ItemKey key, String name) {
        ItemStack stack = key.toStack();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return ItemKey.of(stack);
    }

    /** The payload {@code menu}'s screen would send for {@code cells}, without a hint, folding the panel out. */
    private static TerminalCraftLayPayload payload(WarehouseTerminalMenu menu, List<List<ItemKey>> cells) {
        WarehouseTerminalBlockEntity terminal = menu.terminal();
        BlockPos pos = terminal == null ? BlockPos.ZERO : terminal.getBlockPos();
        return new TerminalCraftLayPayload(menu.containerId, pos, Optional.empty(), cells, true);
    }

    /**
     * A lay through the menu's own entry ({@link WarehouseTerminalMenu#layRecipe}), without a hint, folding the panel
     * out: what the payload's handler reaches after its checks and budgets, which the refusal test covers, so several
     * lays may follow each other in one tick.
     */
    private static TerminalCraftOutcome lay(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            List<List<ItemKey>> cells) {
        return lay(helper, player, menu, cells, true);
    }

    private static TerminalCraftOutcome lay(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            List<List<ItemKey>> cells, boolean foldOut) {
        return menu.layRecipe(player, cells, Optional.empty(), foldOut);
    }

    /**
     * A lay through the payload's entry ({@link WarehouseTerminalMenu#submitCraftLay}), as a received payload is, with
     * its checks and its budgets; fails the test when it was dropped.
     */
    private static TerminalCraftOutcome submit(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            List<List<ItemKey>> cells) {
        return WarehouseTerminalMenu.submitCraftLay(player, payload(menu, cells))
                .orElseThrow(() -> new GameTestAssertException("the lay was dropped instead of answered"));
    }

    /** Whether {@code payload} from {@code player} is dropped without an answer. */
    private static boolean dropped(@Nullable Player player, TerminalCraftLayPayload payload) {
        return WarehouseTerminalMenu.submitCraftLay(player, payload).isEmpty();
    }

    /** A preview through the payload entry point, which answers nothing: the next push carries the view. */
    private static void preview(GameTestHelper helper, Player player, WarehouseTerminalMenu menu,
            CraftGrid<ItemKey> grid, Optional<ResourceLocation> hint) {
        helper.assertTrue(WarehouseTerminalMenu.submitCraft(player, menu.containerId,
                TerminalCraftPayload.Action.PREVIEW, grid, hint).isEmpty(), "a preview has no answer of its own");
    }

    /**
     * Fails unless {@code outcome} laid {@code grid}, naming {@code result}, and the menu took it: its grid, its lay
     * and the lay in the view the panel is pushed.
     */
    private static void assertLaid(GameTestHelper helper, WarehouseTerminalMenu menu, TerminalCraftOutcome outcome,
            CraftGrid<ItemKey> grid, ItemKey result, String context) {
        helper.assertValueEqual(outcome.result(), TerminalCraftResult.LAID, context + ": laid");
        helper.assertValueEqual(outcome.about(), Optional.of(result), context + ": names the result");
        helper.assertValueEqual(menu.craftLay().cells(), grid, context + ": the grid the server picked");
        helper.assertValueEqual(menu.craftCells(), grid, context + ": which the menu shows now");
        helper.assertValueEqual(menu.craftView().lay(), menu.craftLay(), context + ": and pushes as the lay");
        helper.assertTrue(menu.craftView().recipe().isPresent(), context + ": matching a recipe");
    }

    /** Fails unless {@code outcome} is the refusal {@code result}, naming nothing and crafting nothing. */
    private static void assertRefused(GameTestHelper helper, TerminalCraftOutcome outcome,
            TerminalCraftResult result, String context) {
        helper.assertValueEqual(outcome.result(), result, context + ": the refusal");
        helper.assertValueEqual(outcome.crafted(), 0, context + ": nothing made");
        helper.assertTrue(outcome.about().isEmpty(), context + ": naming nothing");
    }

    /** The open requests to the terminal, oldest first, as a copy. */
    private static List<?> terminalRequests(AisleFixture aisle) {
        return List.copyOf(aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)));
    }

    /** Fails unless the terminal has no request open at the aisle and the aisle has no production order. */
    private static void assertAskedNothing(GameTestHelper helper, AisleFixture aisle, String context) {
        helper.assertTrue(aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                context + " asked the aisle for nothing");
        helper.assertTrue(aisle.controller().productionOrders().isEmpty(), context + " started no production");
    }

    /** The container id, a terminal position, no hint and the cell count, as {@code TerminalCraftLayPayload} writes. */
    private static void writeHead(RegistryFriendlyByteBuf buffer, int cells) {
        buffer.writeVarInt(3);
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeUtf("");
        buffer.writeVarInt(cells);
    }

    /** Fails unless what {@code writer} wrote is refused by the payload's decoder. */
    private static void assertRefusedOnDecode(GameTestHelper helper, String what,
            Consumer<RegistryFriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf buffer = newBuffer(helper);
        writer.accept(buffer);
        TerminalCraftLayPayload decoded;
        try {
            decoded = TerminalCraftLayPayload.STREAM_CODEC.decode(buffer);
        } catch (DecoderException refused) {
            return;
        }
        helper.fail(what + " decoded as " + decoded);
    }

    private static RegistryFriendlyByteBuf newBuffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess(),
                ConnectionType.NEOFORGE);
    }

    /**
     * Puts {@code stacks} into the terminal's buffer the way the crane does ({@code StationBuffer#insert}) and adds
     * them to the census expectation; fails the test when one does not fit.
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

    /** Copies of every buffer slot of {@code terminal}, in slot order. */
    private static List<ItemStack> bufferStacks(WarehouseTerminalBlockEntity terminal) {
        IItemHandler handler = terminal.externalHandler();
        List<ItemStack> stacks = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++)
            stacks.add(handler.getStackInSlot(slot).copy());
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

    /** A fake server player standing at the test-relative position, with a fresh profile. */
    private static FakePlayer fakePlayerAt(GameTestHelper helper, BlockPos pos) {
        return standAt(helper, FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), FAKE_PLAYER_NAME)), pos);
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
