package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlock;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.tterrag.registrate.util.entry.RegistryEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDisplaySources;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the four Display Link sources ({@code docs/warehouse-system.md} §10, M14): they are registered and bound
 * to the right blocks, and a real {@code AllBlocks.DISPLAY_LINK} placed on a Wareworks block delivers the expected text
 * to a real display target, degraded cases included.
 * <p>
 * <b>Targets.</b> A <b>lectern</b> keeps the {@code Component} of every line as it was built, so multi-line sources are
 * asserted there by lang key and arguments. A <b>nixie tube row</b> serializes the line to JSON and parses it back,
 * which is the path a display in a real world takes, so one test per source also reads the row. A <b>display board</b>
 * is the only target that goes through {@code provideFlapDisplayText}, and a <b>sign</b> is the one that flattens the
 * text on the server.
 * <p>
 * <b>No ticking needed.</b> Every test sets the link's source and target itself and calls
 * {@link DisplayLinkBlockEntity#updateGatheredData()}, which is exactly what an unpowered link does on its own schedule.
 * The pull is therefore deterministic, and it still runs the whole chain, including the check that the source block
 * really offers the source.
 * <p>
 * Aisle tests build the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1, z = 3, aisle
 * along +X). Display targets stand on the free planes z = 0 and z = 6.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class DisplayLinkGameTests {
    // --- single block layout (empty_7x5x7) ---
    private static final BlockPos[] SOURCE_BLOCKS = {
            new BlockPos(0, BASE_Y, 0), new BlockPos(2, BASE_Y, 0), new BlockPos(4, BASE_Y, 0),
            new BlockPos(6, BASE_Y, 0), new BlockPos(0, BASE_Y, 2), new BlockPos(2, BASE_Y, 2),
            new BlockPos(4, BASE_Y, 2), new BlockPos(6, BASE_Y, 2), new BlockPos(0, BASE_Y, 4)};

    // --- aisle layout (aisle_16x10x7) ---
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final RackPosition STOCKED = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition EMPTY_STORAGE = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition SECOND_STOCKED = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition SECOND_EMPTY_STORAGE = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition TERMINAL = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition INPUT = new RackPosition(5, 0, Side.RIGHT);
    private static final RackPosition KEEPER = new RackPosition(3, 0, Side.RIGHT);
    /** A terminal far behind the dock, at no rack position of the aisle. */
    private static final BlockPos STRAY_TERMINAL = new BlockPos(12, BASE_Y, AISLE_Z);
    /** An output far behind the dock, at no rack position of the aisle. */
    private static final BlockPos STRAY_OUTPUT = new BlockPos(12, BASE_Y, 5);

    // --- display targets ---
    /** Eight tubes give 16 columns; a shorter row would cut every line down to nothing. */
    private static final int NIXIE_TUBES = 8;
    private static final BlockPos NIXIE_ROW = new BlockPos(6, BASE_Y, 6);
    private static final BlockPos LECTERN = new BlockPos(0, BASE_Y, 0);
    private static final BlockPos SECOND_LECTERN = new BlockPos(4, BASE_Y, 0);
    private static final BlockPos SIGN = new BlockPos(8, BASE_Y, 0);
    private static final BlockPos BOARD = new BlockPos(2, BASE_Y, 6);
    /** A display board is a small cogwheel, not a shaft block, so the motor drives it through a cogwheel. */
    private static final BlockPos BOARD_COG = new BlockPos(2, BASE_Y + 1, 6);
    private static final BlockPos BOARD_MOTOR = new BlockPos(1, BASE_Y + 1, 6);
    /** A display board only accepts text above Create's medium speed level. */
    private static final int BOARD_RPM = 64;

    private static final int STORED_IRON = 40;
    private static final int STORED_DIAMONDS = 12;
    private static final int STORED_EMERALDS = 7;
    /** A minimum the aisle cannot meet (it holds no emeralds here) and a maximum the iron already exceeds. */
    private static final int RULE_MINIMUM = 64;
    private static final int RULE_MAXIMUM = 1;
    /** Both keys of the tie-break test hold the same amount, so only the item id may decide their order. */
    private static final int TIED_AMOUNT = 9;
    private static final int TIE_PULLS = 5;
    private static final int STORE_JOB_IRON = 32;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int PAUSE_SETTLE_TICKS = 3;
    private static final int BOARD_SETTLE_TICKS = 20;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);

    private DisplayLinkGameTests() {
    }

    // --- registration ----------------------------------------------------------------------------------------------

    /**
     * The four sources are in Create's registry under their {@code wareworks} ids, their names use exactly the lang keys
     * the generated English carries, and every bound block offers them in the declared order while rail, input and
     * production station and stock keeper offer none.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void displaysourcesregistered(GameTestHelper helper) {
        assertRegistered(helper, WareworksDisplaySources.AISLE_SUMMARY, "aisle_summary",
                WareworksLang.DISPLAY_SOURCE_AISLE_SUMMARY);
        assertRegistered(helper, WareworksDisplaySources.STOCK_LIST, "stock_list",
                WareworksLang.DISPLAY_SOURCE_STOCK_LIST);
        assertRegistered(helper, WareworksDisplaySources.FILTERED_STOCK, "filtered_stock",
                WareworksLang.DISPLAY_SOURCE_FILTERED_STOCK);
        assertRegistered(helper, WareworksDisplaySources.CRANE_STATUS, "crane_status",
                WareworksLang.DISPLAY_SOURCE_CRANE_STATUS);

        List<BlockState> blocks = List.of(WareworksBlocks.STACKER_CRANE.getDefaultState(),
                WareworksBlocks.WAREHOUSE_RAIL.getDefaultState(), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState(),
                WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState(), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState(),
                WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState(), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState(),
                WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState(),
                WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState());
        List<List<DisplaySource>> expected = List.of(List.of(WareworksDisplaySources.CRANE_STATUS.get()), List.of(),
                List.of(WareworksDisplaySources.AISLE_SUMMARY.get(), WareworksDisplaySources.STOCK_LIST.get()),
                List.of(WareworksDisplaySources.FILTERED_STOCK.get()), List.of(),
                List.of(WareworksDisplaySources.FILTERED_STOCK.get()),
                List.of(WareworksDisplaySources.AISLE_SUMMARY.get(), WareworksDisplaySources.STOCK_LIST.get()),
                List.of(), List.of());
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = SOURCE_BLOCKS[i];
            helper.setBlock(pos, blocks.get(i));
            helper.assertValueEqual(DisplaySource.getAll(helper.getLevel(), helper.absolutePos(pos)), expected.get(i),
                    "display sources of " + blocks.get(i).getBlock());
        }
        helper.succeed();
    }

    // --- aisle summary ---------------------------------------------------------------------------------------------

    /**
     * Aisle summary on the controller: four lines with the aisle letter, the short status, the inventories in use of
     * all counted ones, the item types and the total stock. The same text reaches a nixie tube row, where it survives
     * being serialized and parsed again.
     * <p>
     * The fixture keeps all four numbers different — one stocked location out of three, holding two item types — so
     * that no two arguments of the summary can be swapped without a failure.
     */
    @GameTest(template = AISLE_16X10X7)
    public static void aislesummaryoncontroller(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_DIAMONDS));
        aisle.storage(EMPTY_STORAGE);
        aisle.storage(SECOND_EMPTY_STORAGE);
        lectern(helper, LECTERN);
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    link(helper, aisle.controllerPos(), Direction.UP, LECTERN, WareworksDisplaySources.AISLE_SUMMARY);
                    assertFullSummary(helper, lecternPages(helper, LECTERN), 1, 3, 2, STORED_IRON + STORED_DIAMONDS);

                    DisplayLinkBlockEntity onTubes = link(helper, aisle.controllerPos(), Direction.NORTH, tube,
                            WareworksDisplaySources.AISLE_SUMMARY);
                    helper.assertValueEqual(onTubes.targetLine, 0, "the link writes the first line");
                    MutableComponent line = nixieText(helper, tube);
                    assertKey(helper, line, WareworksLang.DISPLAY_AISLE_LINE_AISLE, "the nixie row shows the aisle line");
                    Object[] args = argsOf(helper, line, "aisle line");
                    helper.assertValueEqual(argText(args[0]), "A", "aisle letter on the nixie row");
                    assertKey(helper, (Component) args[1], WareworksLang.DISPLAY_AISLE_STATUS_READY,
                            "status on the nixie row");
                })
                .thenSucceed();
    }

    /**
     * An aisle with stock keepers gets a fifth line naming what its rules are doing (M15, issue #3): how many govern,
     * how many call for their item and how many refuse it at their maximum. It is left out entirely while the aisle has
     * no rule, because a display has few rows and a line reading "Rules: 0" would push a number a player asked for off
     * a four-tube board.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void aislesummarywithstockrules(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_DIAMONDS));
        aisle.storage(EMPTY_STORAGE);
        aisle.storage(SECOND_EMPTY_STORAGE);
        aisle.stockKeeper(KEEPER);
        lectern(helper, LECTERN);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    link(helper, aisle.controllerPos(), Direction.UP, LECTERN, WareworksDisplaySources.AISLE_SUMMARY);
                    assertFullSummary(helper, lecternPages(helper, LECTERN), 1, 3, 2, STORED_IRON + STORED_DIAMONDS);

                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, EMERALD, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, RULE_MINIMUM);
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null, RULE_MAXIMUM);
                })
                // The counts a display reads are the controller's own, refreshed by its rule tick.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().governingStockRuleCount(), 2,
                        "both rules govern"))
                .thenExecute(() -> {
                    linkAt(helper, aisle.controllerPos().relative(Direction.UP)).updateGatheredData();
                    List<Component> lines = lecternPages(helper, LECTERN);
                    helper.assertValueEqual(lines.size(), 5, "the rules line is added to the four counts");
                    assertKey(helper, lines.get(4), WareworksLang.DISPLAY_AISLE_LINE_RULES, "rules line");
                    Object[] args = argsOf(helper, lines.get(4), "rules line");
                    helper.assertValueEqual(args.length, 3, "the line carries all three counts");
                    helper.assertValueEqual(argText(args[0]), number(2), "governing rules");
                    helper.assertValueEqual(argText(args[1]), number(1), "rules calling for their item");
                    // The count that explains a warehouse input standing still: the iron is far past its maximum, so a
                    // board watching this aisle has to be able to show it and not only the goggles.
                    helper.assertValueEqual(argText(args[2]), number(1), "rules refusing their item");
                    helper.assertValueEqual(aisle.controller().stockRulesAtMaximum(), 1, "the controller's own count");
                })
                .thenSucceed();
    }

    /** The same four lines through a terminal, which has to find its controller itself. */
    @GameTest(template = AISLE_16X10X7)
    public static void aislesummaryonterminal(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_DIAMONDS));
        aisle.storage(EMPTY_STORAGE);
        aisle.storage(SECOND_EMPTY_STORAGE);
        aisle.terminal(TERMINAL);
        lectern(helper, LECTERN);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 1))
                .thenExecute(() -> {
                    link(helper, aisle.rackPos(TERMINAL), Direction.UP, LECTERN, WareworksDisplaySources.AISLE_SUMMARY);
                    assertFullSummary(helper, lecternPages(helper, LECTERN), 1, 3, 2, STORED_IRON + STORED_DIAMONDS);
                })
                .thenSucceed();
    }

    /**
     * Two warehouse interfaces on one double chest are <b>one</b> counted inventory: the "used / total" line puts the
     * inventories in use opposite the counted ones, never opposite the storage locations. An alias is indexed with
     * empty counts on purpose, so a ratio against the raw location count could never read full on an aisle built with
     * double chests or item vaults (M14 review fix).
     */
    @GameTest(template = AISLE_16X10X7)
    public static void aislesummarysharedinventory(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        // Chest halves along the aisle, fronts towards the interfaces: the lower x connects east, the higher x west.
        helper.setBlock(aisle.inventoryPos(STOCKED), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(aisle.inventoryPos(EMPTY_STORAGE), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(STOCKED)), IRON.toStack(STORED_IRON));
        aisle.placeInterface(STOCKED);
        aisle.placeInterface(EMPTY_STORAGE);
        aisle.storage(SECOND_STOCKED);
        lectern(helper, LECTERN);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(3, 0, 0);
                    helper.assertValueEqual(aisle.controller().countedStorageLocationCount(), 2,
                            "the double chest counts once");
                })
                .thenExecute(() -> {
                    link(helper, aisle.controllerPos(), Direction.UP, LECTERN, WareworksDisplaySources.AISLE_SUMMARY);
                    // Three storage locations, two counted inventories, one of them stocked.
                    assertFullSummary(helper, lecternPages(helper, LECTERN), 1, 2, 1, STORED_IRON);
                })
                .thenSucceed();
    }

    /**
     * Degraded aisle summary: a controller without a dock shows only its status line, and a terminal that belongs to no
     * aisle shows "No aisle" — one line each, never a stale count.
     */
    @GameTest(template = AISLE_16X10X7)
    public static void aislesummarydegraded(GameTestHelper helper) {
        BlockPos controller = new BlockPos(0, BASE_Y, AISLE_Z);
        helper.setBlock(controller, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, AisleFixture.AISLE));
        helper.setBlock(STRAY_TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState());
        lectern(helper, LECTERN);
        lectern(helper, SECOND_LECTERN);

        helper.startSequence()
                .thenIdle(PAUSE_SETTLE_TICKS)
                .thenExecute(() -> {
                    link(helper, controller, Direction.UP, LECTERN, WareworksDisplaySources.AISLE_SUMMARY);
                    List<Component> pages = lecternPages(helper, LECTERN);
                    helper.assertValueEqual(pages.size(), 1, "a controller without a dock shows one line");
                    assertKey(helper, pages.get(0), WareworksLang.DISPLAY_AISLE_LINE_AISLE, "status line");
                    assertKey(helper, (Component) argsOf(helper, pages.get(0), "status line")[1],
                            WareworksLang.DISPLAY_AISLE_STATUS_NO_DOCK, "status without a dock");

                    link(helper, STRAY_TERMINAL, Direction.UP, SECOND_LECTERN,
                            WareworksDisplaySources.AISLE_SUMMARY);
                    List<Component> strayPages = lecternPages(helper, SECOND_LECTERN);
                    helper.assertValueEqual(strayPages.size(), 1, "a terminal outside an aisle shows one line");
                    assertKey(helper, strayPages.get(0), WareworksLang.DISPLAY_AISLE_NO_AISLE, "no aisle line");
                })
                .thenSucceed();
    }

    /**
     * The other half of the first row of the §10.3 table: a <b>stocked</b> aisle stands right there, but a terminal and
     * an output that belong to none of it list nothing and read {@code 0} — the sources resolve their controller by
     * containment, never by "any controller of this level".
     */
    @GameTest(template = AISLE_16X10X7)
    public static void sourcesoutsideaisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        helper.setBlock(STRAY_TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState());
        helper.setBlock(STRAY_OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
        lectern(helper, LECTERN);
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity list = link(helper, STRAY_TERMINAL, Direction.UP, LECTERN,
                            WareworksDisplaySources.STOCK_LIST);
                    helper.assertTrue(provideText(helper, list, WareworksDisplaySources.STOCK_LIST, 4).isEmpty(),
                            "a terminal outside an aisle lists nothing");

                    helper.assertTrue(strayFilter(helper).setFilter(IRON.toStack(1)), "the output takes the filter");
                    link(helper, STRAY_OUTPUT, Direction.UP, tube, WareworksDisplaySources.FILTERED_STOCK);
                    assertNumber(helper, tube, 0, "an output outside an aisle has no stock to count");
                })
                .thenSucceed();
    }

    /** The request filter of the output that belongs to no aisle. */
    private static FilteringBehaviour strayFilter(GameTestHelper helper) {
        WarehouseOutputBlockEntity output =
                WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(), helper.absolutePos(STRAY_OUTPUT));
        if (output == null)
            helper.fail("missing warehouse output block entity", STRAY_OUTPUT);
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            helper.fail("the output has no request filter", STRAY_OUTPUT);
        return filter;
    }

    // --- stock list ------------------------------------------------------------------------------------------------

    /** Stock list on the controller: the item types in descending order of stock, largest first on the nixie row. */
    @GameTest(template = AISLE_16X10X7)
    public static void stocklistoncontroller(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        aisle.storage(EMPTY_STORAGE, DIAMOND.toStack(STORED_DIAMONDS));
        aisle.storage(SECOND_STOCKED, EMERALD.toStack(STORED_EMERALDS));
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity link = link(helper, aisle.controllerPos(), Direction.UP, tube,
                            WareworksDisplaySources.STOCK_LIST);
                    helper.assertValueEqual(nixieText(helper, tube).getString(),
                            STORED_IRON + " " + Items.IRON_INGOT.getDescription().getString() + " ",
                            "the nixie row shows the most stocked item type");

                    List<String> lines = new ArrayList<>();
                    for (MutableComponent line : provideText(helper, link, WareworksDisplaySources.STOCK_LIST, 3))
                        lines.add(line.getString());
                    helper.assertValueEqual(lines,
                            List.of(STORED_IRON + " " + Items.IRON_INGOT.getDescription().getString() + " ",
                                    STORED_DIAMONDS + " " + Items.DIAMOND.getDescription().getString() + " ",
                                    STORED_EMERALDS + " " + Items.EMERALD.getDescription().getString() + " "),
                            "the whole list, largest first");
                    helper.assertValueEqual(provideText(helper, link, WareworksDisplaySources.STOCK_LIST, 2).size(), 2,
                            "the list is cut to the rows of the target");
                })
                .thenSucceed();
    }

    /**
     * Two item types with the same amount keep a fixed order over repeated pulls, and it is the documented one: the
     * smaller item id first, never the iteration order of the index's key set.
     * <p>
     * Two keys of the <b>same</b> item cannot be told apart on a display — the list shows the item's own name for both
     * — so their order is pinned at {@link ItemKey#ORDER} itself. It must be value-based: {@code ItemKey#hashCode}
     * mixes in {@code Item}'s identity hash, which differs after every restart, so a fallback to it would swap two
     * equally stocked lines between launches (M14 review fix).
     */
    @GameTest(template = AISLE_16X10X7)
    public static void stocklistdeterministicties(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, EMERALD.toStack(TIED_AMOUNT));
        aisle.storage(EMPTY_STORAGE, DIAMOND.toStack(TIED_AMOUNT));
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity link = link(helper, aisle.controllerPos(), Direction.UP, tube,
                            WareworksDisplaySources.STOCK_LIST);
                    String expected = TIED_AMOUNT + " " + Items.DIAMOND.getDescription().getString() + " ";
                    for (int pull = 0; pull < TIE_PULLS; pull++) {
                        link.updateGatheredData();
                        helper.assertValueEqual(nixieText(helper, tube).getString(), expected,
                                "equal amounts order by item id, pull " + pull);
                    }

                    ItemStack renamed = IRON.toStack(1);
                    renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Crate A"));
                    ItemKey labelled = ItemKey.of(renamed);
                    helper.assertTrue(ItemKey.ORDER.compare(IRON, labelled) < 0,
                            "the plain key sorts before the same item with a component patch");
                    helper.assertTrue(ItemKey.ORDER.compare(labelled, IRON) > 0, "and the other way round");
                    helper.assertValueEqual(ItemKey.ORDER.compare(IRON, ItemKey.of(Items.IRON_INGOT)), 0,
                            "two equal keys tie");
                })
                .thenSucceed();
    }

    /** An indexed but empty aisle produces no entry at all, and Create's blank line reaches the target without a crash. */
    @GameTest(template = AISLE_16X10X7)
    public static void stocklistemptywarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED);
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity link = link(helper, aisle.controllerPos(), Direction.UP, tube,
                            WareworksDisplaySources.STOCK_LIST);
                    helper.assertTrue(provideText(helper, link, WareworksDisplaySources.STOCK_LIST, 4).isEmpty(),
                            "an empty warehouse lists nothing");
                    helper.assertValueEqual(nixieText(helper, tube).getString(), "", "the row stays blank");
                })
                .thenSucceed();
    }

    /**
     * A display board is served through {@code provideFlapDisplayText}, which {@code acceptText} never reaches: its
     * sections carry the amount and the item name in separate columns.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void stocklistonflapdisplay(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        displayBoard(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenIdle(BOARD_SETTLE_TICKS)
                .thenExecute(() -> {
                    FlapDisplayBlockEntity board = boardAt(helper);
                    helper.assertTrue(board.isController, "the single board block is its own controller");
                    helper.assertTrue(board.isSpeedRequirementFulfilled(), "the board turns fast enough to accept text");
                    link(helper, aisle.controllerPos(), Direction.UP, BOARD, WareworksDisplaySources.STOCK_LIST);

                    List<FlapDisplaySection> sections = board.getLines().get(0).getSections();
                    helper.assertTrue(sections.size() >= 3, "amount, suffix and name column");
                    helper.assertValueEqual(sections.get(0).getText().getString(), String.valueOf(STORED_IRON),
                            "the amount column");
                    helper.assertValueEqual(sections.get(2).getText().getString(),
                            Items.IRON_INGOT.getDescription().getString() + " ", "the name column");
                })
                .thenSucceed();
    }

    // --- filtered stock --------------------------------------------------------------------------------------------

    /** The output's request filter names the item; the line is the aisle's stored total, and 0 without a filter. */
    @GameTest(template = AISLE_16X10X7)
    public static void filteredstockonoutput(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        aisle.storage(EMPTY_STORAGE, DIAMOND.toStack(STORED_DIAMONDS));
        aisle.output(OUTPUT);
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.filterOf(OUTPUT).setFilter(IRON.toStack(1)), "the output takes the filter");
                    DisplayLinkBlockEntity link = link(helper, aisle.rackPos(OUTPUT), Direction.UP, tube,
                            WareworksDisplaySources.FILTERED_STOCK);
                    assertNumber(helper, tube, STORED_IRON, "the stored total of the filtered item");

                    helper.assertTrue(aisle.filterOf(OUTPUT).setFilter(ItemStack.EMPTY), "the filter is cleared");
                    link.updateGatheredData();
                    assertNumber(helper, tube, 0, "no filter, no number");
                })
                .thenSucceed();
    }

    /**
     * The interface's store filter names the item, and the number is the whole aisle's stock of it, not just this
     * location's. A Create list filter selects no single item type, so it reads 0.
     */
    @GameTest(template = AISLE_16X10X7)
    public static void filteredstockoninterface(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        aisle.storage(SECOND_STOCKED, IRON.toStack(STORED_DIAMONDS));
        BlockPos tube = nixieRow(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(STOCKED, IRON.toStack(1));
                    DisplayLinkBlockEntity link = link(helper, aisle.rackPos(STOCKED), Direction.UP, tube,
                            WareworksDisplaySources.FILTERED_STOCK);
                    assertNumber(helper, tube, STORED_IRON + STORED_DIAMONDS, "the whole aisle's stock of the filter item");

                    aisle.setStoreFilter(STOCKED, AllItems.FILTER.asStack());
                    link.updateGatheredData();
                    assertNumber(helper, tube, 0, "a list filter names no single item type");
                })
                .thenSucceed();
    }

    // --- crane status ----------------------------------------------------------------------------------------------

    /** A parked crane reports that it is idle and that its grabber is empty, and nothing else. */
    @GameTest(template = AISLE_16X10X7)
    public static void cranestatusidle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED);
        lectern(helper, LECTERN);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 0);
                    helper.assertValueEqual(aisle.dock().goggleInfo().pauseReason(), CranePauseReason.NONE,
                            "the crane runs");
                })
                .thenExecute(() -> {
                    link(helper, aisle.dockPos(), Direction.UP, LECTERN, WareworksDisplaySources.CRANE_STATUS);
                    List<Component> pages = lecternPages(helper, LECTERN);
                    helper.assertValueEqual(pages.size(), 2, "activity and grabber, no job lines");
                    assertKey(helper, pages.get(0), WareworksLang.DISPLAY_CRANE_IDLE, "activity");
                    assertKey(helper, pages.get(1), WareworksLang.GOGGLES_EMPTY, "grabber");
                })
                .thenSucceed();
    }

    /**
     * A crane on a store job reports the job before and after the pick — activity, item with amount, target address and
     * what it holds — and reports that it is paused as soon as its rotation stops.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void cranestatusonjob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED);
        aisle.input(INPUT);
        lectern(helper, LECTERN);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORE_JOB_IRON)))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().goggleInfo().job().isPresent(), "store job assigned"))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity link = link(helper, aisle.dockPos(), Direction.UP, LECTERN,
                            WareworksDisplaySources.CRANE_STATUS);
                    assertJobLines(helper, aisle, WareworksLang.DISPLAY_CRANE_STORING, false);
                    helper.assertTrue(link.activeSource == WareworksDisplaySources.CRANE_STATUS.get(),
                            "the dock keeps offering the crane status");
                })
                .thenWaitUntil(() -> helper.assertFalse(aisle.dock().heldItems().isEmpty(), "the crane picked up"))
                .thenExecute(() -> {
                    linkAt(helper, aisle.dockPos().above()).updateGatheredData();
                    assertJobLines(helper, aisle, WareworksLang.DISPLAY_CRANE_STORING, true);
                    aisle.motor().generatedSpeed.setValue(0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().goggleInfo().pauseReason(),
                        CranePauseReason.NO_ROTATION, "the crane stopped"))
                .thenIdle(PAUSE_SETTLE_TICKS)
                .thenExecute(() -> {
                    linkAt(helper, aisle.dockPos().above()).updateGatheredData();
                    assertJobLines(helper, aisle, WareworksLang.DISPLAY_CRANE_PAUSED, true);
                })
                .thenSucceed();
    }

    // --- sign ----------------------------------------------------------------------------------------------------

    /**
     * A sign takes all four lines of the aisle summary. It is the one target that flattens the components on the
     * server, so what it keeps is the <b>server's</b> translation: on a dedicated server NeoForge loads every mod's
     * {@code en_us.json} into the default language ({@code LanguageHook#loadModLanguages}), so a sign reads English
     * for every player whatever their own language. Create's own sources behave the same way, and a lectern, a nixie
     * row or a board keeps the component and is translated per player instead.
     */
    @GameTest(template = AISLE_16X10X7)
    public static void displaylinkonsign(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCKED, IRON.toStack(STORED_IRON));
        helper.setBlock(SIGN, Blocks.OAK_SIGN);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    DisplayLinkBlockEntity link = link(helper, aisle.controllerPos(), Direction.UP, SIGN,
                            WareworksDisplaySources.AISLE_SUMMARY);
                    SignBlockEntity sign = signAt(helper);
                    List<MutableComponent> provided =
                            provideText(helper, link, WareworksDisplaySources.AISLE_SUMMARY, 4);
                    helper.assertValueEqual(provided.size(), 4, "four lines to write");
                    for (int line = 0; line < 4; line++)
                        helper.assertValueEqual(sign.getFrontText().getMessage(line, false).getString(),
                                provided.get(line).getString(sign.getMaxTextLineWidth()),
                                "sign line " + line + " is the flattened component");
                    // The generated English of WareworksLangGen, frozen into the sign: not the reading player's
                    // language, and not the raw lang key either.
                    helper.assertValueEqual(sign.getFrontText().getMessage(0, false).getString(), "Aisle A: Ready",
                            "a sign carries the server's English text");
                })
                .thenSucceed();
    }

    // --- helpers: links and targets ---------------------------------------------------------------------------------

    /**
     * Places a display link on the face {@code side} of the block at {@code source}, points it at {@code target}, sets
     * {@code entry} as its source and pulls once. Fails the test if the source block does not offer that source.
     */
    private static DisplayLinkBlockEntity link(GameTestHelper helper, BlockPos source, Direction side, BlockPos target,
            RegistryEntry<DisplaySource, ? extends DisplaySource> entry) {
        BlockPos pos = source.relative(side);
        helper.setBlock(pos, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, side));
        DisplayLinkBlockEntity link = linkAt(helper, pos);
        helper.assertValueEqual(link.getSourcePosition(), helper.absolutePos(source), "the link reads the source block");
        link.activeSource = entry.get();
        link.target(helper.absolutePos(target));
        link.targetLine = 0;
        link.updateGatheredData();
        helper.assertTrue(link.activeSource == entry.get(), "the source block offers this display source");
        helper.assertTrue(link.activeTarget != null, "the target block accepts display text");
        return link;
    }

    private static DisplayLinkBlockEntity linkAt(GameTestHelper helper, BlockPos pos) {
        DisplayLinkBlockEntity link =
                AllBlockEntityTypes.DISPLAY_LINK.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (link == null)
            helper.fail("missing display link block entity", pos);
        return link;
    }

    /** What the source would write onto a target with {@code rows} rows, through the link's real context. */
    private static List<MutableComponent> provideText(GameTestHelper helper, DisplayLinkBlockEntity link,
            RegistryEntry<DisplaySource, ? extends DisplaySource> entry, int rows) {
        DisplayTargetStats stats = new DisplayTargetStats(rows, NIXIE_TUBES * 2, link.activeTarget);
        return entry.get().provideText(new DisplayLinkContext(helper.getLevel(), link), stats);
    }

    /** A row of nixie tubes along +X; the returned tube is the one a link targets. */
    private static BlockPos nixieRow(GameTestHelper helper) {
        BlockState tube = AllBlocks.ORANGE_NIXIE_TUBE.getDefaultState().setValue(NixieTubeBlock.FACING, Direction.EAST);
        for (int i = 0; i < NIXIE_TUBES; i++)
            helper.setBlock(NIXIE_ROW.relative(Direction.EAST, i), tube);
        return NIXIE_ROW;
    }

    private static MutableComponent nixieText(GameTestHelper helper, BlockPos pos) {
        NixieTubeBlockEntity tube = AllBlockEntityTypes.NIXIE_TUBE.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (tube == null)
            helper.fail("missing nixie tube block entity", pos);
        return tube.getFullText();
    }

    /** A lectern with an empty book, which the display target signs and then fills page by page. */
    private static void lectern(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
        lecternAt(helper, pos).setBook(new ItemStack(Items.WRITABLE_BOOK));
    }

    /** One page per delivered line, with the components exactly as the source built them. */
    private static List<Component> lecternPages(GameTestHelper helper, BlockPos pos) {
        ItemStack book = lecternAt(helper, pos).getBook();
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null)
            helper.fail("the lectern holds no written book", pos);
        return content.getPages(false);
    }

    private static LecternBlockEntity lecternAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof LecternBlockEntity lectern)
            return lectern;
        helper.fail("missing lectern block entity", pos);
        return null;
    }

    /**
     * A single display board block, driven by a creative motor through a cogwheel above it: the board itself is a small
     * cogwheel ({@code ICogWheel}) and takes no shaft, and both rotation axes are the board's, which is X here.
     */
    private static void displayBoard(GameTestHelper helper) {
        helper.setBlock(BOARD_MOTOR,
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.EAST));
        helper.setBlock(BOARD_COG,
                AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        // A display board turns around the axis of the face it shows, so a board facing east turns around X.
        helper.setBlock(BOARD, AllBlocks.DISPLAY_BOARD.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        CreativeMotorBlockEntity motor =
                AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(), helper.absolutePos(BOARD_MOTOR));
        if (motor == null)
            helper.fail("missing creative motor block entity", BOARD_MOTOR);
        motor.generatedSpeed.setValue(BOARD_RPM);
    }

    private static FlapDisplayBlockEntity boardAt(GameTestHelper helper) {
        FlapDisplayBlockEntity board =
                AllBlockEntityTypes.FLAP_DISPLAY.getNullable(helper.getLevel(), helper.absolutePos(BOARD));
        if (board == null)
            helper.fail("missing display board block entity", BOARD);
        return board;
    }

    private static SignBlockEntity signAt(GameTestHelper helper) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(SIGN)) instanceof SignBlockEntity sign)
            return sign;
        helper.fail("missing sign block entity", SIGN);
        return null;
    }

    // --- helpers: assertions ----------------------------------------------------------------------------------------

    private static void assertRegistered(GameTestHelper helper,
            RegistryEntry<DisplaySource, ? extends DisplaySource> entry, String path, String nameKey) {
        ResourceLocation id = Wareworks.asResource(path);
        helper.assertValueEqual(CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(entry.get()), id, "registry id of " + path);
        helper.assertTrue(DisplaySource.get(id) == entry.get(), path + " resolves to the registered instance");
        assertKey(helper, entry.get().getName(), nameKey, "name of " + path);
    }

    /** The four aisle summary lines with their arguments, as they arrive on a target that keeps the components. */
    private static void assertFullSummary(GameTestHelper helper, List<Component> lines, int occupied, int locations,
            int itemTypes, long items) {
        helper.assertValueEqual(lines.size(), 4, "aisle summary lines");
        assertKey(helper, lines.get(0), WareworksLang.DISPLAY_AISLE_LINE_AISLE, "aisle line");
        Object[] aisleArgs = argsOf(helper, lines.get(0), "aisle line");
        helper.assertValueEqual(argText(aisleArgs[0]), "A", "aisle letter");
        assertKey(helper, (Component) aisleArgs[1], WareworksLang.DISPLAY_AISLE_STATUS_READY, "aisle status");

        assertKey(helper, lines.get(1), WareworksLang.DISPLAY_AISLE_LINE_LOCATIONS, "locations line");
        Object[] locationArgs = argsOf(helper, lines.get(1), "locations line");
        helper.assertValueEqual(argText(locationArgs[0]), number(occupied), "storage locations in use");
        helper.assertValueEqual(argText(locationArgs[1]), number(locations), "storage locations of the aisle");

        assertKey(helper, lines.get(2), WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES, "item types line");
        helper.assertValueEqual(argText(argsOf(helper, lines.get(2), "item types line")[0]), number(itemTypes),
                "item types");

        assertKey(helper, lines.get(3), WareworksLang.DISPLAY_AISLE_LINE_ITEMS, "items line");
        helper.assertValueEqual(argText(argsOf(helper, lines.get(3), "items line")[0]), number(items), "items stored");
    }

    /** The crane lines of a job: activity, item with amount, target address and, if {@code holding}, what it carries. */
    private static void assertJobLines(GameTestHelper helper, AisleFixture aisle, String activityKey, boolean holding) {
        CraneGoggleInfo info = aisle.dock().goggleInfo();
        CraneJobSummary job = info.job().orElse(null);
        if (job == null)
            helper.fail("the crane lost its job", aisle.dockPos());

        List<Component> lines = lecternPages(helper, LECTERN);
        helper.assertValueEqual(lines.size(), 4, "activity, job, target and grabber");
        assertKey(helper, lines.get(0), activityKey, "activity");

        assertKey(helper, lines.get(1), WareworksLang.DISPLAY_CRANE_LINE_JOB, "job line");
        Object[] jobArgs = argsOf(helper, lines.get(1), "job line");
        helper.assertValueEqual(argText(jobArgs[0]), Items.IRON_INGOT.getDescription().getString(), "job item");
        helper.assertValueEqual(argText(jobArgs[1]), number(job.amount()), "job amount");

        assertKey(helper, lines.get(2), WareworksLang.DISPLAY_CRANE_LINE_TARGET, "target line");
        helper.assertValueEqual(argText(argsOf(helper, lines.get(2), "target line")[0]), info.address(job.target()),
                "target address");

        if (!holding) {
            assertKey(helper, lines.get(3), WareworksLang.GOGGLES_EMPTY, "empty grabber before the pick");
            return;
        }
        assertKey(helper, lines.get(3), WareworksLang.DISPLAY_CRANE_LINE_HOLDING, "grabber line");
        Component held = (Component) argsOf(helper, lines.get(3), "grabber line")[0];
        assertKey(helper, held, WareworksLang.GOGGLES_ITEM_COUNT, "held item and amount");
        helper.assertValueEqual(argText(argsOf(helper, held, "held item")[0]),
                Items.IRON_INGOT.getDescription().getString(), "held item");
        helper.assertValueEqual(argText(argsOf(helper, held, "held amount")[1]), number(STORE_JOB_IRON), "held amount");
    }

    private static void assertNumber(GameTestHelper helper, BlockPos tube, long expected, String what) {
        helper.assertValueEqual(nixieText(helper, tube).getString(), number(expected), what);
    }

    private static void assertKey(GameTestHelper helper, Component component, String relativeKey, String what) {
        helper.assertValueEqual(contentsOf(helper, component, what).getKey(), WareworksLang.key(relativeKey), what);
    }

    private static Object[] argsOf(GameTestHelper helper, Component component, String what) {
        return contentsOf(helper, component, what).getArgs();
    }

    private static TranslatableContents contentsOf(GameTestHelper helper, Component component, String what) {
        if (component.getContents() instanceof TranslatableContents translatable)
            return translatable;
        helper.fail(what + " is not a translatable component: " + component);
        return null;
    }

    /**
     * The text of a translation argument. A literal component collapses to a plain string when the line travelled
     * through JSON (nixie tubes, display boards) and stays a component otherwise (lecterns).
     */
    private static String argText(Object arg) {
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }

    /** Numbers reach a display formatted the way the goggles and the terminal format them. */
    private static String number(long value) {
        return LangNumberFormat.format(value);
    }
}
