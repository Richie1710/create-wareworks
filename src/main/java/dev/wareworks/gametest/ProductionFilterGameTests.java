package dev.wareworks.gametest;

import static dev.wareworks.gametest.FilterCells.TABLE;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.FilterMatchCache;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PatternFilterCells;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.RecipeTagCells;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.production.CrafterFeedPlan;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.network.ProductionPatternLayPayload;
import dev.wareworks.network.ProductionPatternPayload;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.util.WareworksLang;
import io.netty.buffer.Unpooled;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of <b>filter cells in production patterns</b> (ADR-082, roadmap M34): a pattern cell may hold a configured
 * Create List or Attribute Filter, and the warehouse pays it with any item the filter allows — the largest stock first,
 * several kinds only in whole runs when one is short, reserves respected for the access in use.
 * <p>
 * What these tests prove about the world, on top of what {@code ChoiceAllocatorTest} and {@code ChoiceIngredientTest}
 * prove about the planner: the server classifies a filter cell from the item and saves the flag, an old save stays
 * exact, the pattern's own result is refused both ways, every order is created from concrete keys that remember their
 * filter, a chain makes what stock cannot pay, a restock rule refills through a filter with free stock only, and an
 * accepted order keeps its lines through an edit and a save. The station screen's side: what a player is told about a
 * filter cell and about the items an order took, a click through the real payload path with the hotbar line of a
 * refusal, and a recipe viewer's tag ingredients laid as "is tagged" filters. Every test that stocks the aisle runs an
 * {@link ItemCensus} on every tick.
 * <p>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails. Right rack plane:
 * a saw station at 0, a warehouse input at 1, a warehouse output at 2, the crafter station at 3 and a stock keeper at
 * 4. Left rack plane: a chest of planks (or logs) at 1 and two empty storage locations at 2 and 3. Most tests run
 * without a motor, so the orders are seen exactly as the click created them.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ProductionFilterGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;

    private static final RackPosition SAW_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition WOOD_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORE_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition SPARE_RACK = new RackPosition(3, 0, Side.LEFT);
    /** The recipes a viewer's lay names, the server's only source of which ingredients are tags (ADR-082). */
    private static final ResourceLocation CHEST_RECIPE = ResourceLocation.withDefaultNamespace("chest");
    private static final ResourceLocation OAK_PLANKS_RECIPE = ResourceLocation.withDefaultNamespace("oak_planks");

    private static final ItemKey OAK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey BIRCH = ItemKey.of(Items.BIRCH_PLANKS);
    private static final ItemKey SPRUCE = ItemKey.of(Items.SPRUCE_PLANKS);
    private static final ItemKey CHERRY = ItemKey.of(Items.CHERRY_PLANKS);
    private static final ItemKey DARK_OAK = ItemKey.of(Items.DARK_OAK_PLANKS);
    private static final ItemKey CRIMSON = ItemKey.of(Items.CRIMSON_PLANKS);
    private static final ItemKey BIRCH_LOG = ItemKey.of(Items.BIRCH_LOG);

    private static final int PLANKS_PER_TABLE = 4;
    private static final int TIMEOUT_TICKS = 600;
    private static final int CHAIN_TIMEOUT_TICKS = 3000;
    private static final int SETTLE_TICKS = 20;

    private ProductionFilterGameTests() {
    }

    // --- the pattern -------------------------------------------------------------------------------------------------

    /**
     * The server classifies a filter cell from the item (D2): a configured Attribute or List Filter in a grid cell is a
     * filter cell, a blank filter is exact, and a filter as the result is exact too. The flag survives a save and load.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterCellWritten(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while patterns are written"));
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(CRAFTER_RACK);
                    ProductionPatterns patterns = station.patterns();
                    ItemKey anyPlank = FilterCells.anyPlank();
                    ItemKey list = FilterCells.list(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
                    helper.assertTrue(station.setPatternEntry(0, 0, anyPlank, 1), "the attribute filter is written");
                    helper.assertTrue(patterns.isFilterAt(0, 0), "as a filter cell");
                    helper.assertTrue(station.setPatternEntry(1, 0, FilterCells.blankAttribute(), 1), "a blank one");
                    helper.assertFalse(patterns.isFilterAt(1, 0), "is an exact cell");
                    helper.assertTrue(station.setPatternEntry(2, ProductionPatterns.RESULT_ENTRY, anyPlank, 1),
                            "a filter as the result");
                    helper.assertFalse(patterns.isFilterAt(2, ProductionPatterns.RESULT_ENTRY), "is exact");
                    helper.assertTrue(station.setPatternEntry(3, 4, list, 1), "the list filter is written");
                    helper.assertTrue(patterns.isFilterAt(3, 4), "as a filter cell");
                    helper.assertTrue(station.setPatternEntry(3, ProductionPatterns.RESULT_ENTRY, TABLE, 1), "result");
                    helper.assertValueEqual(patterns.patternAt(3).map(ProductionPattern::choices),
                            Optional.of(java.util.Set.of(list)), "the pattern's choice is the list filter");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    ListTag saved = patterns.save(registries);
                    ProductionPatterns loaded = new ProductionPatterns(patterns.size());
                    loaded.load(saved, registries);
                    helper.assertTrue(loaded.isFilterAt(0, 0), "the flag survives a save");
                    helper.assertTrue(loaded.isFilterAt(3, 4), "for both filters");
                    helper.assertFalse(loaded.isFilterAt(1, 0), "and an exact cell stays exact");
                    helper.assertValueEqual(loaded.keyAt(3, 4), Optional.of(list), "with the very filter");
                })
                .thenSucceed();
    }

    /** An entry saved before ADR-082 has no {@code Filter} flag: even a configured filter loads as an exact cell. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterCellOldSave(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag cell = new CompoundTag();
                    cell.putInt("Entry", 0);
                    cell.put("Item", FilterCells.anyPlank().save(registries));
                    cell.putInt("Count", PLANKS_PER_TABLE);
                    CompoundTag result = new CompoundTag();
                    result.putInt("Entry", ProductionPatterns.RESULT_ENTRY);
                    result.put("Item", TABLE.save(registries));
                    result.putInt("Count", 1);
                    ListTag entries = new ListTag();
                    entries.add(cell);
                    entries.add(result);
                    CompoundTag slot = new CompoundTag();
                    slot.putInt("Slot", 0);
                    slot.put("Entries", entries);
                    ListTag list = new ListTag();
                    list.add(slot);
                    ProductionPatterns patterns = new ProductionPatterns(1);
                    patterns.load(list, registries);
                    helper.assertFalse(patterns.isFilterAt(0, 0), "no flag means an exact cell");
                    helper.assertValueEqual(patterns.patternAt(0).map(ProductionPattern::hasChoices),
                            Optional.of(false), "so the pattern has no choice and plans as it always did");
                    helper.assertFalse(patterns.save(registries).getCompound(0).getList("Entries", Tag.TAG_COMPOUND)
                            .getCompound(0).contains("Filter"), "and saves no flag back");
                })
                .thenSucceed();
    }

    /**
     * A filter that lets the pattern's own result through is refused both ways (D12): "any plank" into a grid whose
     * result is oak planks, and oak planks as the result of a grid with "any plank". Nothing is written either time.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterRefusesItsResult(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(SAW_RACK);
                    ItemKey anyPlank = FilterCells.anyPlank();
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, OAK, 4), "result");
                    helper.assertValueEqual(station.editPatternEntry(0, 0, anyPlank, 1),
                            WarehouseProductionBlockEntity.EditOutcome.FILTER_LETS_RESULT, "the filter is refused");
                    helper.assertValueEqual(station.patterns().keyAt(0, 0), Optional.empty(), "nothing written");

                    helper.assertValueEqual(station.editPatternEntry(1, 0, anyPlank, 1),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "the filter alone is fine");
                    Optional<ItemKey> before = station.patterns().keyAt(1, ProductionPatterns.RESULT_ENTRY);
                    helper.assertValueEqual(station.editPatternEntry(1, ProductionPatterns.RESULT_ENTRY, OAK, 4),
                            WarehouseProductionBlockEntity.EditOutcome.FILTER_LETS_RESULT, "the result is refused");
                    helper.assertValueEqual(station.patterns().keyAt(1, ProductionPatterns.RESULT_ENTRY), before,
                            "and the result is unchanged");
                })
                .thenSucceed();
    }

    // --- largest stock first -----------------------------------------------------------------------------------------

    /**
     * Owner decision 2: of oak 30, birch 10 and cherry 5, two tables take eight oak planks — one line, of one kind,
     * remembering the filter that chose it. The birch and the cherry are untouched. Birch comes first by id and could pay
     * both runs too, so only the largest-stock rule picks the oak.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterPicksLargestStock(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(30), BIRCH.toStack(10), CHERRY.toStack(5));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 30, BIRCH, 10, CHERRY, 5);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filter picks"));
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0,
                        FilterCells.anyPlank()))
                .thenWaitUntil(() -> assertReady(helper, aisle, 45))
                .thenExecute(() -> {
                    order(helper, aisle, TABLE, 2);
                    ProductionOrder<ItemKey, RackPosition> root = onlyOrder(helper, aisle);
                    helper.assertValueEqual(root.lines().size(), 1, "one kind pays both runs");
                    SupplyLine<ItemKey> line = root.lines().getFirst();
                    helper.assertValueEqual(line.key(), OAK, "the largest stock");
                    helper.assertValueEqual(line.required(), 2 * PLANKS_PER_TABLE, "eight planks");
                    helper.assertValueEqual(line.choice(), Optional.of(FilterCells.anyPlank()),
                            "and the line names its filter");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.availableStock(BIRCH), 10L, "the birch is untouched");
                    helper.assertValueEqual(controller.availableStock(CHERRY), 5L, "and so is the cherry");
                    helper.assertValueEqual(controller.availableStock(OAK), 22L, "eight oak are promised");
                })
                .thenSucceed();
    }

    /** One kind short: oak 9, birch 6, cherry 4 and three tables are oak 8 + birch 4 — whole runs, the cherry kept. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterMixesWholeRuns(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(9), BIRCH.toStack(6), CHERRY.toStack(4));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 9, BIRCH, 6, CHERRY, 4);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filter mixes"));
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0,
                        FilterCells.anyPlank()))
                .thenWaitUntil(() -> assertReady(helper, aisle, 19))
                .thenExecute(() -> {
                    order(helper, aisle, TABLE, 3);
                    List<SupplyLine<ItemKey>> lines = onlyOrder(helper, aisle).lines();
                    helper.assertValueEqual(lines.size(), 2, "two kinds");
                    helper.assertValueEqual(lines.get(0).key(), OAK, "the largest first");
                    helper.assertValueEqual(lines.get(0).required(), 8, "two whole runs of oak");
                    helper.assertValueEqual(lines.get(1).key(), BIRCH, "then the next");
                    helper.assertValueEqual(lines.get(1).required(), 4, "one run of birch");
                    helper.assertValueEqual(aisle.controller().availableStock(CHERRY), 4L, "the cherry is untouched");
                })
                .thenSucceed();
    }

    /**
     * A reserve still counts for the access in use: a redstone request (AUTOMATION) never touches the reserved spruce,
     * and a player takes the free oak first and is asked about the reserve only once the free stock runs out.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterRespectsReserve(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(8), SPRUCE.toStack(20));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 8, SPRUCE, 20);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reserve is respected"));
        helper.startSequence()
                .thenExecute(() -> {
                    FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0, FilterCells.anyPlank());
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, SPRUCE, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 20L);
                })
                .thenWaitUntil(() -> {
                    assertReady(helper, aisle, 28);
                    helper.assertValueEqual(aisle.controller().availableTo(StockAccess.AUTOMATION, SPRUCE), 0L,
                            "the spruce is reserved");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RequestConfirmation<ItemKey> two = controller.confirmationFor(TABLE, 2);
                    helper.assertTrue(two.ingredients().isEmpty(), "two tables take the free oak: nothing to ask");
                    RequestConfirmation<ItemKey> three = controller.confirmationFor(TABLE, 3);
                    helper.assertValueEqual(three.ingredients().size(), 1, "the third run reaches the reserve");
                    helper.assertValueEqual(three.ingredients().getFirst().key(), SPRUCE, "named by its kind");
                    helper.assertValueEqual(three.ingredients().getFirst().fromReserve(), 4L, "one run of it");

                    RequestResult automatic = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), TABLE, 3, 64,
                            StockAccess.AUTOMATION);
                    helper.assertTrue(automatic.isAccepted(), "a redstone request is served: " + automatic);
                    helper.assertValueEqual(automatic.producing(), 2, "with the two tables the free oak makes");
                    List<SupplyLine<ItemKey>> lines = onlyOrder(helper, aisle).lines();
                    helper.assertValueEqual(lines.size(), 1, "one kind");
                    helper.assertValueEqual(lines.getFirst().key(), OAK, "the oak, never the reserved spruce");
                    helper.assertValueEqual(controller.availableStock(SPRUCE), 20L, "the spruce is untouched");
                })
                .thenSucceed();
    }

    /**
     * A List Filter keeps the rare wood: oak, birch and spruce are allowed, only dark oak is in stock. The click is
     * refused with {@code NO_MATCHING_ITEM} naming the filter, and the dark oak is untouched.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterListKeepsRareWood(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, DARK_OAK.toStack(64));
        ItemKey list = FilterCells.list(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS);
        Map<ItemKey, Long> conserved = ItemCensus.of(DARK_OAK, 64);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a list filter refuses"));
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0, list))
                .thenWaitUntil(() -> assertReady(helper, aisle, 64))
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), TABLE, 1);
                    helper.assertFalse(result.isAccepted(), "the click is refused: " + result);
                    helper.assertValueEqual(result.refusal(), Optional.of(PlanRefusal.NO_MATCHING_ITEM),
                            "nothing in stock fits");
                    helper.assertValueEqual(result.about(), Optional.of(list), "named by the filter");
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "and the station's coarse answer");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 0, "no order");
                    helper.assertValueEqual(aisle.controller().availableStock(DARK_OAK), 64L, "the dark oak is kept");
                })
                .thenSucceed();
    }

    /**
     * An Attribute Filter in "all of" mode: tagged planks and <b>not</b> tagged non-flammable wood. Crimson planks are
     * planks, but the inverted entry keeps them out, so the one table is oak and the second is refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterAttributeDeny(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, CRIMSON.toStack(64), OAK.toStack(4));
        ItemKey flammable = FilterCells.attribute(AttributeFilterWhitelistMode.WHITELIST_CONJ,
                FilterCells.tagged(ItemTags.PLANKS, false), FilterCells.tagged(ItemTags.NON_FLAMMABLE_WOOD, true));
        Map<ItemKey, Long> conserved = ItemCensus.of(CRIMSON, 64, OAK, 4);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while crimson is kept out"));
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0, flammable))
                .thenWaitUntil(() -> assertReady(helper, aisle, 68))
                .thenExecute(() -> {
                    order(helper, aisle, TABLE, 1);
                    helper.assertValueEqual(onlyOrder(helper, aisle).lines().getFirst().key(), OAK,
                            "the oak, though the crimson is the larger stock");
                    RequestResult second = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), TABLE, 1);
                    helper.assertFalse(second.isAccepted(), "a second table finds no allowed plank: " + second);
                    helper.assertValueEqual(second.refusal(), Optional.of(PlanRefusal.NO_MATCHING_ITEM),
                            "nothing in stock fits");
                    helper.assertValueEqual(aisle.controller().availableStock(CRIMSON), 64L, "crimson never taken");
                })
                .thenSucceed();
    }

    // --- making what stock cannot pay --------------------------------------------------------------------------------

    /**
     * No planks at all, but birch logs and a saw that makes four birch planks of one: one table is a chain of two, the
     * step feeds the root's birch line, and the planks really pass through a rack. The test plays both machines; the
     * census is exact on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = CHAIN_TIMEOUT_TICKS)
    public static void filterMadeFromLogs(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, true, BIRCH_LOG.toStack(8));
        Map<ItemKey, Long> conserved = ItemCensus.of(BIRCH_LOG, 8);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filter's planks are made"));
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity saw = aisle.productionAt(SAW_RACK);
                    helper.assertTrue(saw.setPatternEntry(0, 0, BIRCH_LOG, 1), "a birch log");
                    helper.assertTrue(saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BIRCH, 4), "four planks");
                    FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0, FilterCells.anyPlank());
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().aislePatterns().size(), 2, "both patterns");
                    helper.assertValueEqual(aisle.controller().countOf(BIRCH_LOG), 8L, "the logs are in stock");
                })
                .thenExecute(() -> {
                    order(helper, aisle, TABLE, 1);
                    List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
                    helper.assertValueEqual(orders.size(), 2, "a chain of two");
                    ProductionOrder<ItemKey, RackPosition> step = orders.getFirst();
                    ProductionOrder<ItemKey, RackPosition> root = orders.get(1);
                    helper.assertValueEqual(step.result(), BIRCH, "the step makes birch planks");
                    SupplyLine<ItemKey> line = root.lines().getFirst();
                    helper.assertValueEqual(line.key(), BIRCH, "the root's line is the kind being made");
                    helper.assertValueEqual(line.choice(), Optional.of(FilterCells.anyPlank()), "for its filter");
                    helper.assertValueEqual(step.parentLine(), Optional.of(line.id()), "and the step feeds it");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SAW_RACK, BIRCH_LOG), 1L,
                        "the log reached the saw"))
                .thenExecute(() -> playMachine(helper, aisle, conserved, SAW_RACK, BIRCH_LOG, BIRCH, 4))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(CRAFTER_RACK, BIRCH), 4L,
                        "the planks went through a rack to the crafter"))
                .thenExecute(() -> playMachine(helper, aisle, conserved, CRAFTER_RACK, BIRCH, TABLE, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, TABLE), 1L,
                        "the table reached the output"))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openProductionOrders().size(), 0, "both orders are finished");
                    helper.assertValueEqual(controller.countOf(BIRCH_LOG), 7L, "one log was used");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * An accepted order keeps its lines (§9): the pattern is edited afterwards and the controller is saved and loaded,
     * and the order still delivers the kinds it chose, with their filter and what was delivered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterOrderKeepsLines(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(9), BIRCH.toStack(6));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 9, BIRCH, 6);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an order outlives its pattern"));
        List<SupplyLine<ItemKey>> before = new java.util.ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0,
                        FilterCells.anyPlank()))
                .thenWaitUntil(() -> assertReady(helper, aisle, 15))
                .thenExecute(() -> {
                    order(helper, aisle, TABLE, 3);
                    before.addAll(onlyOrder(helper, aisle).lines());
                    helper.assertValueEqual(before.size(), 2, "oak and birch");
                    WarehouseProductionBlockEntity station = aisle.productionAt(CRAFTER_RACK);
                    helper.assertTrue(station.setPatternEntry(0, 0, OAK, 1), "the pattern is edited");
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    controller.loadWithComponents(saved, registries);
                })
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.lines(), before, "the same lines, ids, kinds, filters and counts");
                    helper.assertTrue(order.isOpen(), "and the order is still open");
                })
                .thenSucceed();
    }

    /**
     * A stock rule refills through a filter pattern with free stock only: the keeper reserves the birch, so the
     * automatic order takes the oak although the birch is the larger stock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterRestock(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(4), BIRCH.toStack(12));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 4, BIRCH, 12);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a rule restocks"));
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0,
                        FilterCells.anyPlank()))
                .thenWaitUntil(() -> assertReady(helper, aisle, 16))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, BIRCH, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 12L);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, TABLE, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MINIMUM, null, 1L);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().openProductionOrders().size(), 1,
                        "the rule ordered a table"))
                .thenExecute(() -> {
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertTrue(order.isRestock(), "automatically");
                    helper.assertValueEqual(order.lines().size(), 1, "one kind");
                    helper.assertValueEqual(order.lines().getFirst().key(), OAK, "the free oak, not the birch");
                    helper.assertValueEqual(order.lines().getFirst().choice(), Optional.of(FilterCells.anyPlank()),
                            "for the filter");
                    helper.assertValueEqual(aisle.controller().availableStock(BIRCH), 12L, "the birch is kept");
                })
                .thenSucceed();
    }

    // --- migration ---------------------------------------------------------------------------------------------------

    /**
     * An order saved without {@code Choice} (every order before ADR-082) loads identical: exact lines, no choice, and the
     * feeder still matches it to its pattern. Its save has no {@code Choice} anywhere.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void migrationSupplyLineWithoutChoice(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(16));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 16);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an old order is read"));
        List<SupplyLine<ItemKey>> before = new java.util.ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> FilterCells.tablePattern(helper, aisle.productionAt(CRAFTER_RACK), 0, OAK))
                .thenWaitUntil(() -> assertReady(helper, aisle, 16))
                .thenExecute(() -> {
                    helper.assertFalse(aisle.productionAt(CRAFTER_RACK).patterns().isFilterAt(0, 0),
                            "an exact pattern");
                    order(helper, aisle, TABLE, 2);
                    before.addAll(onlyOrder(helper, aisle).lines());
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    helper.assertFalse(containsKey(saved, "Choice"), "an exact order saves no choice");
                    controller.loadWithComponents(saved, registries);
                    ProductionOrder<ItemKey, RackPosition> order = onlyOrder(helper, aisle);
                    helper.assertValueEqual(order.lines(), before, "and loads identical");
                    helper.assertTrue(order.lines().stream().allMatch(line -> line.choice().isEmpty()), "exact");
                    ProductionPattern<ItemKey> pattern = aisle.productionAt(CRAFTER_RACK).patterns().patternAt(0)
                            .orElseThrow();
                    helper.assertValueEqual(CrafterFeedPlan.runsFor(order, pattern).orElse(-1), 2,
                            "and the feeder still finds its runs");
                    helper.assertValueEqual(order.state(), ProductionOrderState.WAITING_FOR_INGREDIENTS, "open");
                })
                .thenSucceed();
    }

    // --- the station screen (PART B) ----------------------------------------------------------------------------------

    /**
     * What the screen is told about filter cells: the entry flag, the stock matches in pick order (largest free stock
     * first) with what a player may have, the rest as a number, the items of one run, the deny warning, a slot without a
     * result, the short-of-a-run warning, and the items an order took for its filter. The state crosses the wire whole,
     * and the bounds of four matches, 64 views and four uses hold whatever a caller builds.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterScreenState(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, OAK.toStack(10), BIRCH.toStack(30), CHERRY.toStack(5),
                SPRUCE.toStack(3), DARK_OAK.toStack(2));
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK, 10, BIRCH, 30, CHERRY, 5, SPRUCE, 3, DARK_OAK, 2);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the screen is read"));
        ItemKey rare = FilterCells.list(Items.SPRUCE_PLANKS, Items.DARK_OAK_PLANKS);
        ItemKey deny = FilterCells.denying(ItemTags.NON_FLAMMABLE_WOOD);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(CRAFTER_RACK);
                    FilterCells.tablePattern(helper, station, 0, FilterCells.anyPlank());
                    helper.assertTrue(station.setPatternEntry(1, 2, deny, 1), "a deny filter, no result yet");
                    helper.assertTrue(station.setPatternEntry(2, 0, rare, 4), "four of the rare woods per run");
                })
                .thenWaitUntil(() -> assertReady(helper, aisle, 50))
                .thenExecute(() -> {
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(CRAFTER_RACK));
                    ProductionMenu menu = openMenu(player, aisle.productionAt(CRAFTER_RACK));
                    ProductionScreenState state = menu.screenState();
                    helper.assertTrue(state.entry(0, 0).map(ProductionScreenState.EntryView::filter).orElse(false),
                            "a filter cell is flagged");
                    helper.assertFalse(state.result(0).map(ProductionScreenState.EntryView::filter).orElse(true),
                            "its result is not");
                    helper.assertValueEqual(state.choices().size(), 3, "one view per filter of a slot");

                    ProductionScreenState.ChoiceView planks = state.choice(0, FilterCells.anyPlank()).orElseThrow();
                    helper.assertValueEqual(planks.matches(), List.of(
                            new ProductionScreenState.MatchView(BIRCH, 30L),
                            new ProductionScreenState.MatchView(OAK, 10L),
                            new ProductionScreenState.MatchView(CHERRY, 5L),
                            new ProductionScreenState.MatchView(SPRUCE, 3L)), "largest stock first, four named");
                    helper.assertValueEqual(planks.more(), 1, "the dark oak is the one more");
                    helper.assertValueEqual(planks.perRun(), PLANKS_PER_TABLE, "four cells, four planks a run");
                    helper.assertValueEqual(planks.representative(), Optional.of(BIRCH), "drawn as the birch");
                    helper.assertFalse(planks.shortOfRun(), "the birch pays a run alone");
                    helper.assertFalse(planks.deny(), "an allow filter");
                    helper.assertFalse(planks.crafterLimited(), "no feeder");

                    ProductionScreenState.ChoiceView denied = state.choice(1, deny).orElseThrow();
                    helper.assertTrue(denied.deny(), "the deny filter warns");
                    helper.assertValueEqual(denied.matches().size(), 4, "a slot without a result has matches too");
                    helper.assertValueEqual(denied.perRun(), 1, "one cell");

                    ProductionScreenState.ChoiceView short4 = state.choice(2, rare).orElseThrow();
                    helper.assertValueEqual(short4.matches(), List.of(
                            new ProductionScreenState.MatchView(SPRUCE, 3L),
                            new ProductionScreenState.MatchView(DARK_OAK, 2L)), "only the listed woods");
                    helper.assertTrue(short4.shortOfRun(), "neither pays four alone: kinds mix in whole runs only");

                    order(helper, aisle, TABLE, 2);
                    ProductionScreenState ordered = menu.screenState();
                    helper.assertValueEqual(ordered.orders().size(), 1, "the order is at the station");
                    ProductionScreenState.OrderView view = ordered.orders().getFirst();
                    helper.assertValueEqual(view.uses(), List.of(
                            new ProductionScreenState.EntryView(0, BIRCH, 2 * PLANKS_PER_TABLE)), "uses birch x8");
                    helper.assertValueEqual(view.usesMore(), 0, "and nothing more");
                    helper.assertValueEqual(ordered.choice(0, FilterCells.anyPlank()).orElseThrow().matches()
                            .getFirst(), new ProductionScreenState.MatchView(BIRCH, 22L), "the promised birch is gone");

                    helper.assertValueEqual(roundTrip(helper, ordered), ordered, "the state crosses the wire whole");
                })
                .thenExecute(() -> {
                    List<ProductionScreenState.MatchView> six = new ArrayList<>();
                    for (int i = 0; i < 6; i++)
                        six.add(new ProductionScreenState.MatchView(OAK, i));
                    List<ProductionScreenState.ChoiceView> views = new ArrayList<>();
                    for (int i = 0; i < ProductionScreenState.MAX_CHOICE_VIEWS + 6; i++)
                        views.add(new ProductionScreenState.ChoiceView(i, FilterCells.anyPlank(), Optional.empty(),
                                six, 2, false, false, false, 1));
                    helper.assertValueEqual(views.getFirst().matches().size(), ProductionScreenState.MAX_MATCHES_SHOWN,
                            "a view names four matches at most");
                    List<ProductionScreenState.EntryView> uses = new ArrayList<>();
                    for (int i = 0; i < 6; i++)
                        uses.add(new ProductionScreenState.EntryView(i, OAK, 4));
                    ProductionScreenState.OrderView order = new ProductionScreenState.OrderView(java.util.UUID.randomUUID(),
                            ProductionOrderState.WAITING_FOR_INGREDIENTS, TABLE, 1, 0L, 4L, 0L, Optional.empty(), 0,
                            Optional.empty(), false, uses, 2);
                    helper.assertValueEqual(order.uses().size(), ProductionScreenState.MAX_USES_SHOWN,
                            "an order names four uses at most");
                    ProductionScreenState sent = new ProductionScreenState(List.of(), List.of(order), List.of(), false,
                            List.of(), views);
                    ProductionScreenState received = roundTrip(helper, sent);
                    helper.assertValueEqual(received.choices().size(), ProductionScreenState.MAX_CHOICE_VIEWS,
                            "a payload never carries more than 64 views");
                    helper.assertValueEqual(received.choices(), views.subList(0, ProductionScreenState.MAX_CHOICE_VIEWS),
                            "the first ones, unchanged");
                    helper.assertValueEqual(received.orders(), List.of(order), "and the order with its uses");
                })
                .thenSucceed();
    }

    /**
     * A player's click through the real payload path writes a filter cell (the server tells it from the item), the
     * amount follows the wheel, an exact item replaces it, and a refused filter is said over the hotbar: too large, or
     * letting the slot's result through. Nothing is written then, and the cursor keeps what it held.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterEditRefusalMessage(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(CRAFTER_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(CRAFTER_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    ItemKey anyPlank = FilterCells.anyPlank();
                    menu.setCarried(anyPlank.toStack(1));

                    // The click as the network handler receives it: the payload's own codec, then submitPattern.
                    ProductionPatternPayload click = sent(helper,
                            ProductionPatternPayload.set(menu.containerId, 0, 0, anyPlank, 1));
                    helper.assertValueEqual(submit(player, click), Optional.of(true), "the filter is written");
                    helper.assertTrue(station.patterns().isFilterAt(0, 0), "as a filter cell");
                    helper.assertTrue(menu.screenState().entry(0, 0).map(ProductionScreenState.EntryView::filter)
                            .orElse(false), "and the screen is told so");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.set(menu.containerId, 0, 0, anyPlank, 3))), Optional.of(true),
                            "the wheel changes its amount");
                    helper.assertTrue(station.patterns().isFilterAt(0, 0), "and it stays a filter cell");
                    helper.assertValueEqual(station.patterns().countAt(0, 0), 3, "of three");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.set(menu.containerId, 0, 0, OAK, 3))), Optional.of(true),
                            "an exact item replaces it");
                    helper.assertFalse(station.patterns().isFilterAt(0, 0), "as an exact cell");
                    helper.assertTrue(player.toldKeys().isEmpty(), "nothing to say so far: " + player.toldKeys());

                    ItemKey oversized = FilterCells.oversized();
                    helper.assertTrue(PatternFilterCells.isFilter(oversized), "a configured list filter");
                    menu.setCarried(oversized.toStack(1));
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.set(menu.containerId, 0, 1, oversized, 1))), Optional.of(false),
                            "a filter too large is refused");
                    helper.assertValueEqual(station.patterns().keyAt(0, 1), Optional.empty(), "nothing written");
                    helper.assertValueEqual(player.toldKeys(),
                            List.of(WareworksLang.key(WareworksLang.PRODUCTION_FILTER_TOO_LARGE)), "said over the hotbar");
                    helper.assertTrue(player.told.getFirst().overlay(), "on the action bar, not in chat");
                    helper.assertTrue(oversized.matches(menu.getCarried()), "the cursor keeps the filter");

                    helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, OAK, 4), "a result");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.set(menu.containerId, 1, 0, anyPlank, 1))), Optional.of(false),
                            "a filter that lets oak planks through is refused for an oak plank pattern");
                    helper.assertValueEqual(station.patterns().keyAt(1, 0), Optional.empty(), "nothing written");
                    helper.assertValueEqual(player.toldKeys().getLast(),
                            WareworksLang.key(WareworksLang.PRODUCTION_FILTER_MAKES_RESULT), "and said why");
                })
                .thenSucceed();
    }

    /**
     * JEI's "+" with tag ingredients (owner decision 3), without JEI: the server writes a cell whose ingredient is a tag
     * as an "is tagged" Attribute Filter when the tag exists, holds the picked item and not the result, and every other
     * cell exact, never refusing a lay for it. Also the viewer-free rules that tell a tag from an ingredient
     * ({@link RecipeTagCells}) and the payload's tags on the wire, where an id that does not parse is no tag.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterLaidFromAViewer(GameTestHelper helper) {
        AisleFixture aisle = filterAisle(helper, false, BIRCH.toStack(30));
        Map<ItemKey, Long> conserved = ItemCensus.of(BIRCH, 30);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while recipes are laid"));
        ItemKey oakLog = ItemKey.of(Items.OAK_LOG);
        ItemKey strippedOak = ItemKey.of(Items.STRIPPED_OAK_LOG);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(BIRCH), 30L, "in stock"))
                .thenExecute(() -> {
                    // The viewer-free rules (RecipeTagCells).
                    helper.assertValueEqual(RecipeTagCells.tagOf(Ingredient.of(ItemTags.PLANKS)),
                            Optional.of(ItemTags.PLANKS), "a tag ingredient names its tag");
                    helper.assertValueEqual(RecipeTagCells.tagOf(Ingredient.of(Items.COAL, Items.CHARCOAL)),
                            Optional.empty(), "a list of items names none");
                    helper.assertValueEqual(RecipeTagCells.tagOf(Ingredient.EMPTY), Optional.empty(), "nor nothing");
                    List<Ingredient> sawing = List.of(Ingredient.of(ItemTags.PLANKS), Ingredient.of(ItemTags.LOGS));
                    helper.assertValueEqual(RecipeTagCells.slotTag(sawing, List.of(OAK.toStack(1), BIRCH.toStack(1))),
                            Optional.of(ItemTags.PLANKS), "a slot of planks takes the planks tag");
                    helper.assertValueEqual(RecipeTagCells.slotTag(sawing, List.of(OAK.toStack(1), oakLog.toStack(1))),
                            Optional.empty(), "a slot of planks and logs takes none");
                    helper.assertValueEqual(RecipeTagCells.slotTag(List.of(Ingredient.of(ItemTags.PLANKS),
                            Ingredient.of(ItemTags.NON_FLAMMABLE_WOOD)), List.of(CRIMSON.toStack(1))), Optional.empty(),
                            "two tags that both fit give none");
                    ItemKey coal = ItemKey.of(Items.COAL);
                    ItemKey charcoal = ItemKey.of(Items.CHARCOAL);
                    List<List<ItemKey>> cells = new ArrayList<>(Collections.nCopies(9, List.<ItemKey>of()));
                    cells.set(0, List.of(OAK, BIRCH));
                    cells.set(1, List.of(coal, charcoal));
                    List<Optional<ResourceLocation>> cellTags = RecipeTagCells.cellTags(
                            List.of(List.of(OAK, BIRCH, TABLE), List.of(coal, charcoal)),
                            List.of(Optional.of(ItemTags.PLANKS), Optional.empty()), TABLE, cells);
                    helper.assertValueEqual(cellTags.get(0), Optional.of(ItemTags.PLANKS.location()),
                            "the cell of the planks input, the result dropped as PatternLay drops it");
                    helper.assertValueEqual(cellTags.get(1), Optional.empty(), "the coal-or-charcoal cell stays exact");
                    helper.assertValueEqual(cellTags.get(2), Optional.empty(), "an empty cell has none");
                    List<Optional<ResourceLocation>> grid = RecipeTagCells.gridTags(
                            Map.of(0, Ingredient.of(ItemTags.PLANKS), 1, Ingredient.of(Items.COAL)), 9);
                    helper.assertValueEqual(grid.get(0), Optional.of(ItemTags.PLANKS.location()), "grid cell 0");
                    helper.assertValueEqual(grid.subList(1, 9), Collections.nCopies(8, Optional.empty()), "the rest");

                    // The server's own guards of a laid tag (RecipeTagCells#filterFor), asked directly.
                    ItemKey chest = ItemKey.of(Items.CHEST);
                    ItemKey cobblestone = ItemKey.of(Items.COBBLESTONE);
                    helper.assertValueEqual(RecipeTagCells.filterFor(ItemTags.PLANKS.location(), OAK, chest),
                            Optional.of(FilterCells.anyPlank()), "a plank under the planks tag is a filter");
                    helper.assertValueEqual(RecipeTagCells.filterFor(
                            ResourceLocation.fromNamespaceAndPath(Wareworks.ID, "no_such_tag"), OAK, chest),
                            Optional.<ItemKey>empty(), "an unknown tag is none");
                    helper.assertValueEqual(RecipeTagCells.filterFor(ItemTags.PLANKS.location(), cobblestone, chest),
                            Optional.<ItemKey>empty(), "nor a tag the picked item is not in");
                    helper.assertValueEqual(RecipeTagCells.filterFor(ItemTags.PLANKS.location(), OAK, BIRCH),
                            Optional.<ItemKey>empty(), "nor a tag that holds the result");
                })
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(CRAFTER_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(CRAFTER_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    List<ItemKey> planks = List.of(OAK, SPRUCE, BIRCH);
                    List<List<ItemKey>> chest = List.of(planks, planks, planks, planks, List.of(), planks, planks,
                            planks, planks);
                    List<Integer> ones = Collections.nCopies(9, 1);
                    Optional<ResourceLocation> plankTag = Optional.of(ItemTags.PLANKS.location());
                    List<Optional<ResourceLocation>> tags = new ArrayList<>(Collections.nCopies(9, plankTag));
                    tags.set(4, Optional.empty());

                    // Without the recipe's id the server cannot tell a tag from a list of items: every cell exact.
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 0, true,
                            chest, ones, Optional.empty(), tags, Optional.empty())), ProductionMenu.LayResult.LAID,
                            "laid without the recipe");
                    for (int cell = 0; cell < 9; cell++)
                        helper.assertFalse(station.patterns().isFilterAt(0, cell), "no recipe, no filter in " + cell);
                    helper.assertValueEqual(menu.lay(player, sentLay(helper, new ProductionPatternLayPayload(
                            menu.containerId, 0, true, chest, ones, Optional.empty(), tags, Optional.of(CHEST_RECIPE)))),
                            ProductionMenu.LayResult.LAID, "the chest is laid");
                    ProductionPatterns patterns = station.patterns();
                    helper.assertValueEqual(patterns.keyAt(0, ProductionPatterns.RESULT_ENTRY),
                            Optional.of(ItemKey.of(Items.CHEST)), "the server names the chest");
                    helper.assertValueEqual(patterns.countAt(0, ProductionPatterns.RESULT_ENTRY), 1, "one");
                    for (int cell = 0; cell < 9; cell++) {
                        if (cell == 4)
                            continue;
                        helper.assertTrue(patterns.isFilterAt(0, cell), "cell " + cell + " is a filter cell");
                        helper.assertValueEqual(patterns.keyAt(0, cell), Optional.of(FilterCells.anyPlank()),
                                "the very filter a player configures by hand");
                    }
                    helper.assertValueEqual(patterns.patternAt(0).map(ProductionPattern::ingredients),
                            Optional.of(List.of(new dev.wareworks.core.production.ProductionEntry<>(
                                    FilterCells.anyPlank(), 8))), "one choice of eight planks");

                    List<Optional<ResourceLocation>> odd = new ArrayList<>(tags);
                    odd.set(0, Optional.of(ResourceLocation.fromNamespaceAndPath(Wareworks.ID, "no_such_tag")));
                    odd.set(1, Optional.of(ItemTags.LOGS.location()));
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 1, true,
                            chest, ones, Optional.empty(), odd, Optional.of(CHEST_RECIPE))),
                            ProductionMenu.LayResult.LAID, "laid again");
                    helper.assertFalse(patterns.isFilterAt(1, 0),
                            "a tag the chest recipe does not have (here an unknown one) leaves its cell exact");
                    helper.assertFalse(patterns.isFilterAt(1, 1), "so does another tag the recipe does not have");
                    helper.assertTrue(patterns.keyAt(1, 0).map(key -> key.toStack(1).is(ItemTags.PLANKS))
                            .orElse(false), "a plank");
                    helper.assertTrue(patterns.isFilterAt(1, 2), "the others are filters");
                    helper.assertValueEqual(patterns.patternAt(1).map(ProductionPattern::choices),
                            Optional.of(java.util.Set.of(FilterCells.anyPlank())), "of one choice");

                    List<List<ItemKey>> sawn = new ArrayList<>(Collections.nCopies(9, List.<ItemKey>of()));
                    sawn.set(0, List.of(oakLog));
                    List<Optional<ResourceLocation>> logTag = new ArrayList<>(Collections.nCopies(9,
                            Optional.<ResourceLocation>empty()));
                    logTag.set(0, Optional.of(ItemTags.OAK_LOGS.location()));
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 2, false,
                            sawn, ones, Optional.of(new dev.wareworks.core.production.ProductionEntry<>(strippedOak, 1)),
                            logTag, Optional.of(OAK_PLANKS_RECIPE))), ProductionMenu.LayResult.LAID,
                            "a tag that holds the result is no refusal");
                    helper.assertFalse(patterns.isFilterAt(2, 0), "that one cell stays exact");
                    helper.assertValueEqual(patterns.keyAt(2, 0), Optional.of(oakLog), "the oak log");
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 3, false,
                            sawn, ones, Optional.of(new dev.wareworks.core.production.ProductionEntry<>(OAK, 4)),
                            logTag, Optional.of(OAK_PLANKS_RECIPE))), ProductionMenu.LayResult.LAID,
                            "the same input for planks");
                    helper.assertValueEqual(patterns.keyAt(3, 0),
                            Optional.of(PatternFilterCells.inTag(ItemTags.OAK_LOGS)), "is any oak log");
                    helper.assertValueEqual(patterns.countAt(3, 0), 1, "one per run");

                    // The recipe's own tag, but a picked item outside it: a lay that is no crafting lay has no concrete
                    // grid check, so the tag's membership check alone keeps the cell exact.
                    List<List<ItemKey>> outside = new ArrayList<>(Collections.nCopies(9, List.<ItemKey>of()));
                    outside.set(0, List.of(ItemKey.of(Items.COBBLESTONE)));
                    outside.set(1, List.of(OAK));
                    List<Optional<ResourceLocation>> plankTags = new ArrayList<>(Collections.nCopies(9,
                            Optional.<ResourceLocation>empty()));
                    plankTags.set(0, plankTag);
                    plankTags.set(1, plankTag);
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 3, false,
                            outside, ones, Optional.of(new dev.wareworks.core.production.ProductionEntry<>(
                                    ItemKey.of(Items.CHEST), 1)), plankTags, Optional.of(CHEST_RECIPE))),
                            ProductionMenu.LayResult.LAID, "laid with the chest recipe's planks tag");
                    helper.assertFalse(patterns.isFilterAt(3, 0), "cobblestone is no plank: its cell stays exact");
                    helper.assertValueEqual(patterns.keyAt(3, 0), Optional.of(ItemKey.of(Items.COBBLESTONE)),
                            "the cobblestone");
                    helper.assertTrue(patterns.isFilterAt(3, 1), "while the oak's cell becomes the planks filter");

                    // A list of items that equals a tag, as the client sees NeoForge's coal or charcoal: the client
                    // proposes the tag, and the server's own torch recipe says it is a list, so the cell stays exact.
                    List<List<ItemKey>> torch = new ArrayList<>(Collections.nCopies(9, List.<ItemKey>of()));
                    torch.set(0, List.of(ItemKey.of(Items.COAL), ItemKey.of(Items.CHARCOAL)));
                    torch.set(3, List.of(ItemKey.of(Items.STICK)));
                    List<Optional<ResourceLocation>> torchTags = new ArrayList<>(Collections.nCopies(9,
                            Optional.<ResourceLocation>empty()));
                    torchTags.set(0, Optional.of(ItemTags.COALS.location()));
                    torchTags.set(3, Optional.of(ResourceLocation.fromNamespaceAndPath("c", "rods/wooden")));
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(menu.containerId, 2, true,
                            torch, ones, Optional.empty(), torchTags,
                            Optional.of(ResourceLocation.withDefaultNamespace("torch")))),
                            ProductionMenu.LayResult.LAID, "the torch is laid");
                    helper.assertFalse(patterns.isFilterAt(2, 0), "the coal-or-charcoal list stays exact");
                    helper.assertTrue(patterns.isFilterAt(2, 3), "the wooden rods tag becomes a filter");
                })
                .thenExecute(() -> {
                    // On the wire: a tag id that does not parse is read as no tag, never as a decoder error.
                    RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
                    buffer.writeVarInt(1);
                    buffer.writeVarInt(0);
                    buffer.writeBoolean(true);
                    buffer.writeVarInt(9);
                    for (int cell = 0; cell < 9; cell++) {
                        buffer.writeVarInt(1);
                        ItemKey.STREAM_CODEC.encode(buffer, OAK);
                        buffer.writeVarInt(1);
                        buffer.writeBoolean(true);
                        buffer.writeUtf("Not A Tag!");
                    }
                    buffer.writeBoolean(false);
                    buffer.writeBoolean(true);
                    buffer.writeUtf("Not A Recipe!");
                    ProductionPatternLayPayload read = ProductionPatternLayPayload.STREAM_CODEC.decode(buffer);
                    helper.assertValueEqual(buffer.readableBytes(), 0, "read to the end");
                    helper.assertValueEqual(read.tags(), Collections.nCopies(9, Optional.<ResourceLocation>empty()),
                            "no tags");
                    helper.assertValueEqual(read.recipe(), Optional.<ResourceLocation>empty(), "and no recipe");
                    helper.assertTrue(read.wellFormed(), "and a lay the server accepts");
                })
                .thenSucceed();
    }

    // --- scale -------------------------------------------------------------------------------------------------------

    /**
     * More distinct filters than the cache holds ({@link FilterMatchCache#MAX_FILTERS}), asked round after round in the
     * same order as a terminal's producible pass asks them: the first round tests each admitted filter against each key
     * once, a second round runs <b>no</b> Create test (a least-recently-used cache would test them all again), and a
     * filter beyond the cap allows nothing and costs nothing. A filter no station holds any more frees its place.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterCacheHoldsTheAislesFilters(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    StockIndex<ItemKey, RackPosition> index = new StockIndex<>();
                    index.restore(WOOD_RACK, Map.of(OAK, 4L, BIRCH, 4L, SPRUCE, 4L));
                    StockView<ItemKey, RackPosition> stock = index.readOnlyView();
                    int count = FilterMatchCache.MAX_FILTERS + 8;
                    List<ItemKey> filters = new ArrayList<>(count);
                    for (Item item : BuiltInRegistries.ITEM) {
                        if (filters.size() >= count)
                            break;
                        if (item != Items.AIR && item != Items.OAK_PLANKS)
                            filters.add(FilterCells.list(Items.OAK_PLANKS, item));
                    }
                    helper.assertValueEqual(new java.util.HashSet<>(filters).size(), count, "distinct filters");
                    FilterMatchCache cache = new FilterMatchCache();
                    cache.retain(filters);
                    for (int round = 0; round < 3; round++) {
                        for (ItemKey filter : filters) {
                            cache.inStock(level, filter, stock);
                            cache.test(level, filter, CHERRY);
                        }
                        helper.assertValueEqual(cache.createTests(), (long) FilterMatchCache.MAX_FILTERS * 4,
                                "each admitted filter tested against the three stock keys and the cherry once, round "
                                        + round);
                    }
                    helper.assertValueEqual(cache.size(), FilterMatchCache.MAX_FILTERS, "the cache holds the cap");
                    ItemKey beyond = filters.get(FilterMatchCache.MAX_FILTERS);
                    helper.assertValueEqual(cache.inStock(level, beyond, stock), List.<ItemKey>of(),
                            "a filter beyond the cap allows nothing");
                    helper.assertFalse(cache.test(level, beyond, OAK), "not even the oak it lists");
                    helper.assertValueEqual(cache.inStock(level, filters.getFirst(), stock), List.of(OAK),
                            "an admitted filter answers");

                    cache.retain(filters.subList(1, count));
                    helper.assertValueEqual(cache.inStock(level, beyond, stock), List.of(OAK),
                            "once a station drops a filter, the next one in aisle order gets its place");
                    helper.assertValueEqual(cache.size(), FilterMatchCache.MAX_FILTERS, "and the cap still holds");
                })
                .thenSucceed();
    }

    /**
     * A filter's examples ({@link PatternFilterCells#examples}) are bounded and remembered: a conjunction of forty
     * "is tagged #planks" entries and one inverted one allows nothing, yet walks the planks tag once, not forty times; a
     * conjunction over several large tags stops at {@link PatternFilterCells#MAX_EXAMPLE_TESTS} Create tests; and asking
     * again runs no test at all, as every station screen push and pattern edit does.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterExamplesAreBounded(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    List<ItemAttribute.ItemAttributeEntry> repeated = new ArrayList<>();
                    for (int entry = 0; entry < 40; entry++)
                        repeated.add(FilterCells.tagged(ItemTags.PLANKS, false));
                    repeated.add(FilterCells.tagged(ItemTags.PLANKS, true));
                    ItemKey nothing = FilterCells.attribute(AttributeFilterWhitelistMode.WHITELIST_CONJ,
                            repeated.toArray(ItemAttribute.ItemAttributeEntry[]::new));
                    int planks = BuiltInRegistries.ITEM.getTag(ItemTags.PLANKS).orElseThrow().size();
                    long before = PatternFilterCells.exampleTests();
                    helper.assertValueEqual(PatternFilterCells.examples(nothing, level), List.<ItemKey>of(),
                            "a filter that allows nothing names nothing");
                    long spent = PatternFilterCells.exampleTests() - before;
                    helper.assertTrue(spent <= Math.min(planks, PatternFilterCells.MAX_EXAMPLE_TESTS),
                            "the planks tag is walked once: " + spent + " tests for " + planks + " planks");
                    before = PatternFilterCells.exampleTests();
                    PatternFilterCells.examples(nothing, level);
                    helper.assertValueEqual(PatternFilterCells.exampleTests() - before, 0L, "and remembered");

                    ItemKey wide = FilterCells.attribute(AttributeFilterWhitelistMode.WHITELIST_CONJ,
                            FilterCells.tagged(ItemTags.LOGS, false), FilterCells.tagged(ItemTags.SLABS, false),
                            FilterCells.tagged(ItemTags.STAIRS, false), FilterCells.tagged(ItemTags.WOOL, false),
                            FilterCells.tagged(ItemTags.LOGS, true));
                    before = PatternFilterCells.exampleTests();
                    helper.assertValueEqual(PatternFilterCells.examples(wide, level), List.<ItemKey>of(), "nothing");
                    helper.assertValueEqual(PatternFilterCells.exampleTests() - before,
                            (long) PatternFilterCells.MAX_EXAMPLE_TESTS, "the walk stops at the test cap");
                    before = PatternFilterCells.exampleTests();
                    PatternFilterCells.examples(wide, level);
                    helper.assertValueEqual(PatternFilterCells.exampleTests() - before, 0L, "and is remembered");

                    helper.assertTrue(PatternFilterCells.examples(FilterCells.anyPlank(), level).contains(OAK),
                            "an ordinary filter still names its planks");
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** The production menu {@code player} has open at {@code station}. */
    private static ProductionMenu openMenu(ClickingPlayer player, WarehouseProductionBlockEntity station) {
        ProductionMenu menu = ProductionMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                station);
        player.containerMenu = menu;
        return menu;
    }

    /** A click as the network handler passes it on ({@code WareworksNetwork#onProductionPattern}). */
    private static Optional<Boolean> submit(ClickingPlayer player, ProductionPatternPayload payload) {
        return ProductionMenu.submitPattern(player, payload.containerId(), payload.pattern(), payload.entry(),
                payload.key(), payload.count());
    }

    /** {@code payload} after its trip through its own codec, as the server reads it. */
    private static ProductionPatternPayload sent(GameTestHelper helper, ProductionPatternPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionPatternPayload.STREAM_CODEC.encode(buffer, payload);
        ProductionPatternPayload read = ProductionPatternPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the click reads exactly what it wrote");
        return read;
    }

    /** The same for a recipe viewer's lay, which must arrive with its tags. */
    private static ProductionPatternLayPayload sentLay(GameTestHelper helper, ProductionPatternLayPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionPatternLayPayload.STREAM_CODEC.encode(buffer, payload);
        ProductionPatternLayPayload read = ProductionPatternLayPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the lay reads exactly what it wrote");
        helper.assertValueEqual(read, payload, "with every cell, count and tag");
        return read;
    }

    /** {@code state} after its trip through {@link ProductionScreenPayload}. */
    private static ProductionScreenState roundTrip(GameTestHelper helper, ProductionScreenState state) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionScreenPayload.STREAM_CODEC.encode(buffer, new ProductionScreenPayload(1, state));
        ProductionScreenState read = ProductionScreenPayload.STREAM_CODEC.decode(buffer).state();
        helper.assertValueEqual(buffer.readableBytes(), 0, "the state reads exactly what it wrote");
        return read;
    }

    /** The layout above, {@code stock} in the wood rack. */
    private static AisleFixture filterAisle(GameTestHelper helper, boolean withMotor,
            net.minecraft.world.item.ItemStack... stock) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(withMotor);
        aisle.storage(WOOD_RACK, stock);
        aisle.storage(STORE_RACK);
        aisle.storage(SPARE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(SAW_RACK);
        aisle.production(CRAFTER_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        return aisle;
    }

    /** The table pattern is planned with and {@code items} items are in stock. */
    private static void assertReady(GameTestHelper helper, AisleFixture aisle, long items) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 2, "both stations are recorded");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage was read");
        helper.assertTrue(controller.producibleKeys().contains(TABLE), "the table is producible");
        long stocked = 0L;
        for (ItemKey key : List.of(OAK, BIRCH, SPRUCE, CHERRY, DARK_OAK, CRIMSON))
            stocked += controller.countOf(key);
        helper.assertValueEqual(stocked, items, "the planks are in stock");
    }

    private static void order(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount) {
        RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), key, amount);
        helper.assertTrue(result.isAccepted(), "the order for " + key + " is accepted: " + result);
        helper.assertValueEqual(result.producing(), amount, "all of it is made");
    }

    private static ProductionOrder<ItemKey, RackPosition> onlyOrder(GameTestHelper helper, AisleFixture aisle) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
        if (orders.size() != 1) {
            helper.fail("expected exactly one production order, found " + orders.size());
            throw new IllegalStateException("unreachable");
        }
        return orders.getFirst();
    }

    /** The test plays a machine: every ingredient out of the station's buffer, the product back through the input. */
    private static void playMachine(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved,
            RackPosition station, ItemKey ingredient, ItemKey product, int products) {
        IItemHandler handler = aisle.handlerAt(aisle.rackPos(station));
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (ingredient.matches(handler.getStackInSlot(slot)))
                taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
        }
        helper.assertTrue(taken > 0, "the machine at " + station + " took its ingredients");
        ItemCensus.change(conserved, ingredient, -taken);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), product.toStack(products));
        ItemCensus.change(conserved, product, products);
    }

    /** Whether {@code tag} holds the key {@code name} at any depth. */
    private static boolean containsKey(Tag tag, String name) {
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                if (key.equals(name) || containsKey(compound.get(key), name))
                    return true;
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                if (containsKey(element, name))
                    return true;
            }
        }
        return false;
    }
}
