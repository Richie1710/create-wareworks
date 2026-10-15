package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.FluidBayHandler;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.RackBayHandler;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>A schematic carries a Wareworks block's settings and never its goods</b> (issue #25, ADR-061).
 * <p>
 * A server running <i>Create: Schematic Checker</i> in mod-whitelist mode removes every block that carries block data
 * from a mod not on its list, so the README asks server operators to add {@code wareworks} to that list — and these
 * tests are why that is safe: every Wareworks block entity, configured and stocked the way a player would, is saved
 * and printed with Create's own code from end to end ({@link SchematicPrint}: Schematic and Quill's server-side save,
 * Create's printer, {@code BlockHelper#prepareBlockEntityData} and {@code BlockHelper#placeSchematicBlock}), and
 * <ul>
 * <li>the data a print hands the printed block holds <b>exactly</b> the settings the block type has — filter samples
 * of one, priorities, ranks, redstone modes, the aisle letter, the mast height, stock rules — and nothing that is or
 * names goods: no stored item or fluid, no count or amount, no buffer, no handling head, no job, no clipboard, no
 * order, no request and no reservation ({@link #assertPrintCarries});</li>
 * <li>a <b>fresh copy</b> printed from it arrives with those settings and <b>empty</b> — no duplicate of anything;</li>
 * <li>the same print landing on the <b>standing original</b> keeps every item where it was — nothing deleted;</li>
 * <li>Create's material list asks for the block itself and for nothing else (a Create filter item in a filter slot
 * would be paid for, which is Create's own rule);</li>
 * <li>the item census of the whole test area never changes while any of it happens.</li>
 * </ul>
 * The saved <b>file</b> is a different matter and is stated as what it is: the server-side save holds every block
 * entity's full save, goods included, exactly as it holds a chest's — each test asserts that the source file really
 * did hold the goods, so a print without them proves the stripping and not an empty source. Create never places a
 * block's data from the file without stripping it.
 * <p>
 * A block at its defaults hands a print <b>nothing at all</b>
 * ({@link #blocksAtTheirDefaultsHandAPrintNothing}); a pallet never travels in a schematic
 * ({@link #aPalletNeverTravelsInASchematic}); a print over a standing port leaves its pulse token, its counters and
 * its last refusal alone ({@link #aPrintOverAStandingPortKeepsItsPulseAndItsCounters}); and the filter format every
 * world and schematic from before issue #25 holds still loads
 * ({@link #storageLocationsLoadTheFilterFormatOfEarlierVersions}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class SchematicSafetyGameTests {
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey APPLE = ItemKey.of(Items.APPLE);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);

    /** What a player holds when they click a filter slot: a whole stack, of which only a sample of one is kept. */
    private static final int HELD = 64;

    /**
     * Keys that name goods, a carrier of goods or an order for goods, in any Wareworks save or client packet. None of
     * them may appear <b>anywhere</b> in what a print hands a block.
     */
    private static final Set<String> GOODS_KEYS = Set.of(RackBayHandler.STORED_TAG, RackBayHandler.COUNT_TAG,
            "StoredItem", FluidBayHandler.FLUID_TAG, FluidBayHandler.AMOUNT_TAG, FluidBayHandler.STORED_FLUID_TAG,
            WarehouseStationBlockEntity.BUFFER_TAG, "Items", "Head", "Crane", "CraneSync", "Job",
            WarehouseTerminalBlockEntity.LIST_SLOT_TAG, "ListOrder", "CraftOrder", "Requests", "ProductionOrders",
            "Locations",
            "Stock", "Reservations", "LastRejection", WarehouseOutputBlockEntity.ARMED_TAG, "PortExported",
            "PortCollected", "Layout", "Network", "StockRules", "StockPauses");

    private SchematicSafetyGameTests() {
    }

    // --- storage locations ---------------------------------------------------------------------------------------------

    private static final BlockPos BAY = new BlockPos(0, BASE_Y, 1);
    private static final BlockPos FLUID_BAY = new BlockPos(2, BASE_Y, 1);
    private static final BlockPos INTERFACE_CHEST = new BlockPos(4, BASE_Y, 0);
    private static final BlockPos INTERFACE = new BlockPos(4, BASE_Y, 1);
    /** Fresh copies are printed this far south of their originals, on empty floor. */
    private static final Vec3i COPY = new Vec3i(0, 0, 4);
    /** A full wooden bay: 64 stacks of cobblestone at the default configuration. */
    private static final int BAY_LOAD = BayTier.WOOD.defaultStacks() * 64;
    private static final int BAY_PRIORITY = 7;
    private static final int FLUID_LOAD = 3_500;
    private static final int FLUID_PRIORITY = 3;
    private static final int IRON_BEHIND_INTERFACE = 32;
    private static final int INTERFACE_PRIORITY = 5;

    /**
     * A full rack bay, a filled fluid bay and a warehouse interface with a stocked chest behind it, each with a filter
     * and a priority: printed fresh beside the original, and printed over the original.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void storageLocationsPrintTheirSettingsAndNeverTheirGoods(GameTestHelper helper) {
        helper.setBlock(BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState().setValue(RackBayBlock.FACING,
                Direction.NORTH));
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                Direction.NORTH));
        helper.setBlock(INTERFACE_CHEST, Blocks.CHEST);
        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));

        RackBayBlockEntity bay = blockEntity(helper, BAY, RackBayBlockEntity.class);
        helper.assertTrue(bay.setStoreFilter(COBBLESTONE.toStack(HELD)), "the bay takes a filter from a stack in hand");
        helper.assertTrue(bay.setStorePriority(BAY_PRIORITY), "and a priority");
        helper.assertTrue(bay.insert(COBBLESTONE.toStack(BAY_LOAD), false).isEmpty(), "and its load");
        helper.assertValueEqual(bay.storeFilter().getCount(), 1, "a filter is kept as a sample of one");

        FluidBayBlockEntity tank = blockEntity(helper, FLUID_BAY, FluidBayBlockEntity.class);
        helper.assertTrue(tank.setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "the fluid bay takes a lava filter");
        helper.assertTrue(tank.setStorePriority(FLUID_PRIORITY), "and a priority");
        helper.assertValueEqual(tank.fill(new FluidStack(Fluids.LAVA, FLUID_LOAD), false), FLUID_LOAD,
                "and its lava");

        ((Container) helper.getBlockEntity(INTERFACE_CHEST)).setItem(0, IRON.toStack(IRON_BEHIND_INTERFACE));
        WarehouseInterfaceBlockEntity storage = blockEntity(helper, INTERFACE, WarehouseInterfaceBlockEntity.class);
        helper.assertTrue(storage.setStoreFilter(IRON.toStack(HELD)), "the interface takes a filter");
        helper.assertTrue(storage.setStorePriority(INTERFACE_PRIORITY), "and a priority");

        Map<ItemKey, Long> items = ItemCensus.of(COBBLESTONE, BAY_LOAD, IRON, IRON_BEHIND_INTERFACE);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, FLUID_LOAD);
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "while storage locations are saved and printed"));

        helper.startSequence()
                // Long enough for onLoad to be past, as it is for every block a player prints over.
                .thenIdle(2)
                .thenExecute(() -> {
                    // The rack bay.
                    Travel bayTravel = saveAndPrintCopy(helper, "rack_bay", BAY, BAY.offset(COPY));
                    helper.assertValueEqual(bayTravel.file().getInt(RackBayHandler.COUNT_TAG), BAY_LOAD,
                            "the saved file holds the bay's load, as it holds a chest's");
                    assertPrintCarries(helper, bayTravel.copy(), "a rack bay", StorageFilterBehaviour.FILTER_TAG,
                            StorageFilterBehaviour.PRIORITY_TAG);
                    RackBayBlockEntity bayCopy = blockEntity(helper, BAY.offset(COPY), RackBayBlockEntity.class);
                    helper.assertValueEqual(bayCopy.storedCount(), 0, "the printed bay holds nothing");
                    helper.assertTrue(bayCopy.storedKey().isEmpty(), "and is committed to no item");
                    helper.assertTrue(COBBLESTONE.matches(bayCopy.storeFilter()), "but has the filter");
                    helper.assertValueEqual(bayCopy.storeFilter().getCount(), 1, "as a sample of one");
                    helper.assertValueEqual(bayCopy.storePriority(), BAY_PRIORITY, "and the priority");

                    // The fluid bay.
                    Travel tankTravel = saveAndPrintCopy(helper, "fluid_bay", FLUID_BAY, FLUID_BAY.offset(COPY));
                    helper.assertValueEqual(tankTravel.file().getInt(FluidBayHandler.AMOUNT_TAG), FLUID_LOAD,
                            "the saved file holds the fluid bay's lava");
                    assertPrintCarries(helper, tankTravel.copy(), "a fluid bay", StorageFilterBehaviour.FILTER_TAG,
                            StorageFilterBehaviour.PRIORITY_TAG);
                    FluidBayBlockEntity tankCopy = blockEntity(helper, FLUID_BAY.offset(COPY),
                            FluidBayBlockEntity.class);
                    helper.assertValueEqual(tankCopy.millibuckets(), 0, "the printed fluid bay holds nothing");
                    helper.assertValueEqual(tankCopy.filterFluid().orElse(null), LAVA, "but has the filter");
                    helper.assertValueEqual(tankCopy.storePriority(), FLUID_PRIORITY, "and the priority");

                    // The interface, saved together with the chest behind it.
                    String interfaceFile = SchematicPrint.save(helper, "interface", INTERFACE_CHEST, INTERFACE);
                    SchematicPrint.Result interfaceCopy = SchematicPrint.print(helper, interfaceFile,
                            INTERFACE_CHEST.offset(COPY), false);
                    helper.assertValueEqual(interfaceCopy.blocks().size(), 2, "the interface and its chest are printed");
                    assertPrintCarries(helper,
                            interfaceCopy.at(helper, helper.absolutePos(INTERFACE.offset(COPY))), "a warehouse interface",
                            StorageFilterBehaviour.FILTER_TAG, StorageFilterBehaviour.PRIORITY_TAG);
                    helper.assertTrue(interfaceCopy.at(helper, helper.absolutePos(INTERFACE_CHEST.offset(COPY)))
                            .data() == null, "a vanilla chest is printed with no data at all");
                    WarehouseInterfaceBlockEntity storageCopy = blockEntity(helper, INTERFACE.offset(COPY),
                            WarehouseInterfaceBlockEntity.class);
                    helper.assertTrue(IRON.matches(storageCopy.storeFilter()), "the printed interface has the filter");
                    helper.assertValueEqual(storageCopy.storeFilter().getCount(), 1, "as a sample of one");
                    helper.assertValueEqual(storageCopy.storePriority(), INTERFACE_PRIORITY, "and the priority");
                    helper.assertTrue(((Container) helper.getBlockEntity(INTERFACE_CHEST.offset(COPY))).isEmpty(),
                            "and the chest behind it is empty");

                    // The same prints, landing on the standing originals.
                    SchematicPrint.print(helper, bayTravel.name(), BAY, true);
                    SchematicPrint.print(helper, tankTravel.name(), FLUID_BAY, true);
                    SchematicPrint.print(helper, interfaceFile, INTERFACE_CHEST, true);
                    helper.assertValueEqual(bay.storedCount(), BAY_LOAD, "a print over the bay leaves its load");
                    helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "and its item");
                    helper.assertValueEqual(tank.millibuckets(), FLUID_LOAD, "a print over the fluid bay its lava");
                    helper.assertValueEqual(storage.storePriority(), INTERFACE_PRIORITY,
                            "and the interface keeps its settings");
                    FluidCensus.assertConserved(helper, items, fluid, "right after the prints");
                })
                // The bay's scheduled repair tick and every other consequence of the prints, still conserved.
                .thenIdle(5)
                .thenExecute(() -> {
                    Clearable.tryClear(bay);
                    Clearable.tryClear(tank);
                    items.clear();
                    fluid.clear();
                    items.putAll(ItemCensus.of(IRON, IRON_BEHIND_INTERFACE));
                })
                .thenSucceed();
    }

    // --- a working warehouse -------------------------------------------------------------------------------------------

    private static final int AISLE_Z = 3;
    private static final int RAILS = 7;
    private static final int TEST_RPM = 128;
    private static final RackPosition IRON_STORAGE = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition DIAMOND_STORAGE = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition LOG_STORAGE = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition PLANK_STORAGE = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition HOME = new RackPosition(5, 0, Side.RIGHT);
    /** The copies are printed in a row on the pair template's second aisle floor, far from the working aisle. */
    private static final int COPY_Z = 10;
    /** Phase 2 prints a second controller copy one row further on. */
    private static final int SECOND_COPY_Z = 12;
    /** Phase 1 prints a keeper from a client-side save into that row too, clear of the controller. */
    private static final BlockPos CLIENT_KEEPER_COPY = new BlockPos(4, BASE_Y, SECOND_COPY_Z);

    private static final int IRON_IN_STOCK = 64;
    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int LOGS_IN_STOCK = 8;
    private static final int COBBLESTONE_FED = 16;
    private static final int GOLD_IN_OUTPUT = 8;
    private static final int APPLES_IN_TERMINAL = 5;
    private static final int STICKS_IN_PRODUCTION = 3;
    private static final int LISTED_DIAMONDS = 8;
    private static final int ORDERED_PLANKS = 4;
    private static final int PLANKS_PER_LOG = 4;
    /** Aisle letter C: a controller a player has set to something else than its default A. */
    private static final int LETTER_C = 2;
    private static final int MAST_HEIGHT = 5;
    private static final int REQUESTED = 16;
    private static final int PORT_RANK = 2;
    private static final long RULE_MINIMUM = 10L;
    private static final long RULE_MAXIMUM = 100L;
    private static final long RULE_RESERVE = 5L;

    /**
     * A working warehouse — controller, dock, storage, an input, a configured port, a terminal working off a clipboard
     * and fetching the ingredients for a craft (issue #24), a production station with a pattern and an order, a stock
     * keeper with a rule and a home point — every station with goods in its buffer.
     * <p>
     * <b>Phase 1</b>, with the crane standing still so nothing moves: every station and the controller (with open
     * requests and a production order) is saved, printed fresh into a row on the empty floor, and printed over itself.
     * <b>Phase 2</b>: the crane gets power, and the moment it carries something the dock and the controller (whose job
     * that is) go through the same, the dock's print landing on the very crane that holds the items.
     * <p>
     * The controller is printed fresh only. A print reaches a standing controller only when its facing differs, and a
     * controller that faces another way drops its warehouse's records by design, exactly as when its dock is turned;
     * it holds no items itself, so nothing can be lost there.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = 1600)
    public static void warehouseBlocksPrintTheirSettingsAndNeverTheirGoods(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(IRON_STORAGE, IRON.toStack(IRON_IN_STOCK));
        aisle.storage(DIAMOND_STORAGE, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.storage(LOG_STORAGE, LOG.toStack(LOGS_IN_STOCK));
        aisle.storage(PLANK_STORAGE);
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        aisle.terminal(TERMINAL);
        aisle.production(PRODUCTION);
        aisle.stockKeeper(KEEPER);
        aisle.homePoint(HOME);

        // What a player sets up before anything runs.
        scrollValue(helper, aisle.controller()).setValue(LETTER_C);
        scrollValue(helper, aisle.dock()).setValue(MAST_HEIGHT);
        WarehouseProductionBlockEntity production = aisle.productionAt(PRODUCTION);
        helper.assertTrue(production.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(production.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "and its result");
        WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, RULE_MINIMUM);
        keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, RULE_MAXIMUM);
        keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, RULE_RESERVE);
        WarehouseOutputBlockEntity output = aisle.outputAt(OUTPUT);
        RequestFilterBehaviour request = (RequestFilterBehaviour) aisle.filterOf(OUTPUT);
        helper.assertTrue(request.setFilter(IRON.toStack(HELD)), "the port takes a filter from a stack in hand");
        request.count = REQUESTED;
        helper.assertTrue(output.setRedstoneMode(PortRedstone.WHILE_POWERED), "a redstone mode");
        helper.assertTrue(output.setPortRank(PORT_RANK), "and a rank");

        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK, DIAMOND, DIAMONDS_IN_STOCK, LOG,
                LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a warehouse is saved and printed"));
        ItemStack[] clipboard = { ItemStack.EMPTY };
        HeldItems[] carried = { HeldItems.EMPTY };
        UUID[] carriedJob = { null };

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle))
                .thenExecute(() -> {
                    // Goods in every buffer, a clipboard being worked off and a production order.
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), COBBLESTONE.toStack(COBBLESTONE_FED));
                    ItemCensus.change(conserved, COBBLESTONE, COBBLESTONE_FED);
                    deliver(helper, output, GOLD.toStack(GOLD_IN_OUTPUT));
                    ItemCensus.change(conserved, GOLD, GOLD_IN_OUTPUT);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL);
                    deliver(helper, terminal, APPLE.toStack(APPLES_IN_TERMINAL));
                    ItemCensus.change(conserved, APPLE, APPLES_IN_TERMINAL);
                    deliver(helper, production, STICK.toStack(STICKS_IN_PRODUCTION));
                    ItemCensus.change(conserved, STICK, STICKS_IN_PRODUCTION);

                    ItemStack list = clipboardOf(DIAMOND, LISTED_DIAMONDS);
                    helper.assertTrue(terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT,
                            list.copy(), false).isEmpty(), "the terminal takes the clipboard");
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL));
                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "the list is started");
                    helper.assertTrue(terminal.requestFromTerminal(player, PLANK, ORDERED_PLANKS).isAccepted(),
                            "planks are ordered, to be made");
                    // And a craft fetch beside the clipboard: a log for one craft of planks (issue #24).
                    WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                            player.getInventory(), terminal);
                    player.containerMenu = menu;
                    helper.assertValueEqual(menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, CraftGrid.of(LOG), Optional.empty(), 1, 0L, false)).result(),
                            TerminalCraftResult.STARTED, "a craft fetch is started");
                    clipboard[0] = terminal.listClipboard().copy();
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.openRequestCount() > 0, "the warehouse has open requests");
                    helper.assertValueEqual(controller.productionOrders().size(), 1, "and a production order");
                    helper.assertTrue(aisle.terminalAt(TERMINAL).hasOpenListOrder(), "and the list order runs");
                    helper.assertTrue(aisle.terminalAt(TERMINAL).craftOrderState().inFlight() > 0L,
                            "and the craft fetch has its log requested");
                })
                .thenExecute(() -> {
                    // --- phase 1: every station and the controller, fresh ---
                    Travel input = saveAndPrintCopy(helper, "input", aisle.rackPos(INPUT), copyAt(2));
                    assertFileHoldsBuffer(helper, input, "the input");
                    assertPrintCarries(helper, input.copy(), "a warehouse input");
                    assertEmptyBuffer(helper, blockEntity(helper, copyAt(2), WarehouseInputBlockEntity.class),
                            "the printed input");

                    Travel port = saveAndPrintCopy(helper, "output", aisle.rackPos(OUTPUT), copyAt(3));
                    assertFileHoldsBuffer(helper, port, "the port");
                    assertPrintCarries(helper, port.copy(), "a warehouse port", StorageFilterBehaviour.FILTER_TAG,
                            RequestFilterBehaviour.FILTER_AMOUNT_TAG, RequestFilterBehaviour.REDSTONE_MODE_TAG,
                            "PortRank");
                    WarehouseOutputBlockEntity portCopy = blockEntity(helper, copyAt(3),
                            WarehouseOutputBlockEntity.class);
                    assertEmptyBuffer(helper, portCopy, "the printed port");
                    helper.assertValueEqual(portCopy.filterKey().orElse(null), IRON, "it has the filter");
                    helper.assertValueEqual(portCopy.requestedItem().getCount(), 1, "as a sample of one");
                    helper.assertValueEqual(portCopy.requestAmount(), REQUESTED, "the amount");
                    helper.assertValueEqual(portCopy.redstoneMode(), PortRedstone.WHILE_POWERED, "the mode");
                    helper.assertValueEqual(portCopy.portRank(), PORT_RANK, "and the rank");

                    Travel terminal = saveAndPrintCopy(helper, "terminal", aisle.rackPos(TERMINAL), copyAt(4));
                    assertFileHoldsBuffer(helper, terminal, "the terminal");
                    helper.assertTrue(terminal.file().contains(WarehouseTerminalBlockEntity.LIST_SLOT_TAG)
                            && terminal.file().contains("ListOrder") && terminal.file().contains("CraftOrder"),
                            "the saved file holds the terminal's clipboard and both its orders");
                    assertPrintCarries(helper, terminal.copy(), "a warehouse terminal");
                    WarehouseTerminalBlockEntity terminalCopy = blockEntity(helper, copyAt(4),
                            WarehouseTerminalBlockEntity.class);
                    assertEmptyBuffer(helper, terminalCopy, "the printed terminal");
                    helper.assertTrue(terminalCopy.listClipboard().isEmpty(), "it holds no clipboard");
                    helper.assertFalse(terminalCopy.hasListOrder(), "and no order");
                    helper.assertFalse(terminalCopy.hasCraftOrder(), "and no craft fetch");

                    Travel station = saveAndPrintCopy(helper, "production", aisle.rackPos(PRODUCTION), copyAt(5));
                    assertFileHoldsBuffer(helper, station, "the production station");
                    assertPrintCarries(helper, station.copy(), "a production station");
                    WarehouseProductionBlockEntity stationCopy = blockEntity(helper, copyAt(5),
                            WarehouseProductionBlockEntity.class);
                    assertEmptyBuffer(helper, stationCopy, "the printed production station");
                    helper.assertTrue(stationCopy.activePatterns().isEmpty(),
                            "patterns stay with the world, as they do when the station is broken");

                    Travel rules = saveAndPrintCopy(helper, "stock_keeper", aisle.rackPos(KEEPER), copyAt(6));
                    assertPrintCarries(helper, rules.copy(), "a stock keeper",
                            StockKeeperRules.RULES_TAG);
                    assertKeeperRule(helper, blockEntity(helper, copyAt(6), WarehouseStockKeeperBlockEntity.class),
                            "the printed keeper has the rule, and only a rule");

                    Travel home = saveAndPrintCopy(helper, "home_point", aisle.rackPos(HOME), copyAt(7));
                    assertPrintCarries(helper, home.copy(), "a home point");
                    blockEntity(helper, copyAt(7), WarehouseHomePointBlockEntity.class);

                    Travel controller = saveAndPrintCopy(helper, "controller", aisle.controllerPos(), copyAt(0));
                    helper.assertFalse(controller.file().getList("Requests", Tag.TAG_COMPOUND).isEmpty(),
                            "the saved file holds the controller's open requests");
                    assertPrintCarries(helper, controller.copy(), "a warehouse controller", "ScrollValue");
                    assertEmptyController(helper, copyAt(0));

                    // --- phase 1: the same prints, landing on the standing stations ---
                    TerminalCraftOrderState fetching = aisle.terminalAt(TERMINAL).craftOrderState();
                    List<RetrievalRequest<ItemKey, BlockPos>> terminalRequests = List.copyOf(
                            aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL)));
                    SchematicPrint.print(helper, input.name(), aisle.rackPos(INPUT), true);
                    SchematicPrint.print(helper, port.name(), aisle.rackPos(OUTPUT), true);
                    SchematicPrint.print(helper, terminal.name(), aisle.rackPos(TERMINAL), true);
                    SchematicPrint.print(helper, station.name(), aisle.rackPos(PRODUCTION), true);
                    SchematicPrint.print(helper, rules.name(), aisle.rackPos(KEEPER), true);
                    SchematicPrint.print(helper, home.name(), aisle.rackPos(HOME), true);
                    helper.assertValueEqual(aisle.stationCount(INPUT, COBBLESTONE), (long) COBBLESTONE_FED,
                            "a print over the input leaves its buffer");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, GOLD), (long) GOLD_IN_OUTPUT,
                            "a print over the port leaves its buffer");
                    helper.assertValueEqual(aisle.outputAt(OUTPUT).portRank(), PORT_RANK, "and its settings");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL, APPLE), (long) APPLES_IN_TERMINAL,
                            "a print over the terminal leaves its buffer");
                    WarehouseTerminalBlockEntity standing = aisle.terminalAt(TERMINAL);
                    helper.assertTrue(ItemStack.matches(standing.listClipboard(), clipboard[0]),
                            "and the clipboard in its list slot");
                    helper.assertTrue(standing.hasOpenListOrder(), "and the order working it off");
                    helper.assertValueEqual(standing.craftOrderState(), fetching,
                            "and the craft fetch, exactly as it was (issue #24)");
                    helper.assertValueEqual(List.copyOf(aisle.controller().requestsFor(
                            aisle.absoluteRackPos(TERMINAL))), terminalRequests,
                            "and every request to the terminal keeps its amount");
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION, STICK), (long) STICKS_IN_PRODUCTION,
                            "a print over the production station leaves its buffer");
                    helper.assertValueEqual(aisle.productionAt(PRODUCTION).activePatterns().size(), 1,
                            "and the pattern a schematic does not carry");
                    assertKeeperRule(helper, aisle.stockKeeperAt(KEEPER), "a print over the keeper keeps its rule");

                    // An ordinary Schematic and Quill save is taken on the client, which is never sent a keeper's
                    // rules: its keeper entry holds an empty list. A keeper printed from it has no rules, and the same
                    // print over the standing keeper leaves that keeper's own rule alone.
                    String clientRules = SchematicPrint.rewrite(helper, rules.name(), "stock_keeper_client",
                            data -> data.put(StockKeeperRules.RULES_TAG, new ListTag()));
                    SchematicPrint.Result clientCopy = SchematicPrint.print(helper, clientRules, CLIENT_KEEPER_COPY,
                            false);
                    assertPrintCarries(helper, clientCopy.at(helper, helper.absolutePos(CLIENT_KEEPER_COPY)),
                            "a stock keeper from a client-side save");
                    helper.assertValueEqual(blockEntity(helper, CLIENT_KEEPER_COPY,
                            WarehouseStockKeeperBlockEntity.class).rules().ruleCount(), 0,
                            "a keeper printed from a client-side save has no rules");
                    SchematicPrint.print(helper, clientRules, aisle.rackPos(KEEPER), true);
                    assertKeeperRule(helper, aisle.stockKeeperAt(KEEPER),
                            "a print from a client-side save over the keeper keeps its rule");
                    ItemCensus.assertEquals(helper, conserved, "right after the station prints");

                    // --- phase 2: power for the crane ---
                    aisle.placeMotor();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertFalse(aisle.dock().heldItems().isEmpty(),
                        "the crane carries something"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    carried[0] = dock.heldItems();
                    carriedJob[0] = dock.currentJob().map(job -> job.id()).orElse(null);
                    helper.assertTrue(carriedJob[0] != null, "the crane carries for a job");

                    Travel crane = saveAndPrintCopy(helper, "dock", aisle.dockPos(), copyAt(1));
                    helper.assertFalse(crane.file().getCompound("Head").getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                            "the saved file holds what the crane carries");
                    helper.assertTrue(crane.file().getCompound("Crane").contains("Job"), "and its job");
                    assertPrintCarries(helper, crane.copy(), "a stacker crane dock", "ScrollValue");
                    StackerCraneBlockEntity dockCopy = blockEntity(helper, copyAt(1), StackerCraneBlockEntity.class);
                    helper.assertTrue(dockCopy.heldItems().isEmpty(), "the printed crane carries nothing");
                    helper.assertTrue(dockCopy.currentJob().isEmpty(), "and has no job");
                    helper.assertValueEqual(dockCopy.mastHeight(), MAST_HEIGHT, "but has the mast height");

                    Travel controller = saveAndPrintCopy(helper, "controller_busy", aisle.controllerPos(),
                            new BlockPos(0, BASE_Y, SECOND_COPY_Z));
                    assertPrintCarries(helper, controller.copy(), "a busy warehouse controller", "ScrollValue");
                    assertEmptyController(helper, new BlockPos(0, BASE_Y, SECOND_COPY_Z));

                    // The dock's print, landing on the crane that holds the items.
                    SchematicPrint.print(helper, crane.name(), aisle.dockPos(), true);
                    helper.assertValueEqual(dock.heldItems(), carried[0], "a print over the dock leaves the head");
                    helper.assertValueEqual(dock.currentJob().map(job -> job.id()).orElse(null), carriedJob[0],
                            "and the job");
                    ItemCensus.assertEquals(helper, conserved, "right after the dock print");
                })
                .thenIdle(10)
                .thenSucceed();
    }

    // --- defaults ------------------------------------------------------------------------------------------------------

    private static final BlockPos GRID_MIN = new BlockPos(0, BASE_Y, 0);
    private static final Vec3i GRID_SIZE = new Vec3i(7, 3, 7);

    /**
     * Every Wareworks block that has a block entity, placed and left at its defaults, hands a print <b>nothing</b>: an
     * empty compound, from the live block entity and from the one Create's printer loaded from the file. So an
     * unconfigured block adds no data of its own to a schematic.
     * <p>
     * What a default block cannot avoid is the schematic <b>file</b> entry itself: vanilla's
     * {@code StructureTemplate#fillFromWorld} stores {@code saveWithId()} for every block entity, so the entry always
     * has an {@code nbt} compound with at least the {@code id}. For an unconfigured, empty rack bay, fluid bay,
     * warehouse interface or home point that is <b>all</b> it holds (asserted here); a station still writes its empty
     * buffer, a dock Create's kinetic keys and its parked crane, a controller and a keeper their empty lists — save
     * format, not settings, and none of it reaches a print. A checker that drops every block entry with an {@code nbt}
     * compound from a mod not on its list therefore drops these too; that is why the README asks for the whitelist line.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void blocksAtTheirDefaultsHandAPrintNothing(GameTestHelper helper) {
        Map<BlockPos, Block> layout = defaultsLayout();
        for (Map.Entry<BlockPos, Block> spot : layout.entrySet())
            helper.setBlock(spot.getKey(), spot.getValue().defaultBlockState());

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    for (Map.Entry<BlockPos, Block> spot : layout.entrySet()) {
                        BlockState state = helper.getBlockState(spot.getKey());
                        BlockEntity be = helper.getBlockEntity(spot.getKey());
                        CompoundTag live = BlockHelper.prepareBlockEntityData(helper.getLevel(), state, be);
                        helper.assertTrue(live != null && live.isEmpty(), name(spot.getValue())
                                + " at its defaults hands a print " + live + " instead of nothing");
                    }

                    String file = SchematicPrint.save(helper, "defaults", GRID_MIN,
                            GRID_MIN.offset(GRID_SIZE.getX() - 1, GRID_SIZE.getY() - 1, GRID_SIZE.getZ() - 1));
                    SchematicPrint.Result result = SchematicPrint.print(helper, file, GRID_MIN, true);
                    helper.assertValueEqual(result.blocks().size(), layout.size(), "every block went through a print");
                    for (SchematicPrint.Printed printed : result.blocks()) {
                        String what = name(printed.state().getBlock()) + " at its defaults";
                        assertPrintCarries(helper, printed, what);
                        boolean idOnly = printed.state().getBlock() instanceof RackBayBlock
                                || printed.state().getBlock() instanceof FluidBayBlock
                                || printed.state().getBlock() instanceof WarehouseInterfaceBlock
                                || printed.state().is(WareworksBlocks.WAREHOUSE_HOME_POINT.get());
                        if (idOnly)
                            helper.assertValueEqual(new TreeSet<>(printed.file().getAllKeys()),
                                    new TreeSet<>(Set.of("id")), what + ": the file entry holds its id alone");
                    }
                })
                .thenSucceed();
    }

    /** Every Wareworks block with a block entity, each on its own spot of a grid with a gap to the next. */
    private static Map<BlockPos, Block> defaultsLayout() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK)
            if (Wareworks.ID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())
                    && block.defaultBlockState().hasBlockEntity())
                blocks.add(block);
        Map<BlockPos, Block> layout = new LinkedHashMap<>();
        int index = 0;
        for (int y = 0; y < GRID_SIZE.getY() && index < blocks.size(); y += 2)
            for (int z = 0; z < GRID_SIZE.getZ() && index < blocks.size(); z += 2)
                for (int x = 0; x < GRID_SIZE.getX() && index < blocks.size(); x += 2)
                    layout.put(GRID_MIN.offset(x, y, z), blocks.get(index++));
        if (index < blocks.size())
            throw new IllegalStateException("the grid has room for " + index + " blocks, but Wareworks has "
                    + blocks.size() + " with a block entity");
        return layout;
    }

    // --- pallets -------------------------------------------------------------------------------------------------------

    private static final BlockPos PALLET = new BlockPos(3, BASE_Y, 1);
    private static final int PALLET_LOAD = 640;

    /**
     * A pallet — the whole load of a broken rack bay — lying inside the box Schematic and Quill saves is written into
     * the file with its load, as every entity is. Create drops it again when it loads the schematic for a print with
     * block data processed (the Schematicannon, and the creative instant print of a player who may not use operator
     * blocks), because a pallet answers {@code onlyOpCanSetNbt()} with {@code true}. Before that, the creative print
     * added a second pallet with the same load. The Schematicannon could never print one: an entity without a material
     * requirement is invalid to print.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void aPalletNeverTravelsInASchematic(GameTestHelper helper) {
        helper.assertTrue(PalletEntity.spawn(helper.getLevel(), helper.absolutePos(PALLET), COBBLESTONE, PALLET_LOAD),
                "a pallet lies on the floor");
        Map<ItemKey, Long> items = ItemCensus.of(COBBLESTONE, PALLET_LOAD);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, items, "while a pallet is saved and printed"));

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    String file = SchematicPrint.save(helper, "pallet", PALLET, PALLET);
                    List<CompoundTag> saved = SchematicPrint.fileEntities(SchematicPrint.fileContents(helper, file));
                    helper.assertTrue(saved.stream().anyMatch(entity -> entity.getInt(PalletEntity.COUNT_TAG)
                            == PALLET_LOAD), "the saved file holds the pallet and its load: " + saved);

                    SchematicPrint.Result result = SchematicPrint.print(helper, file, PALLET.offset(COPY), false);
                    helper.assertTrue(result.entities().isEmpty(),
                            "a print hands out no pallet, but got " + result.entities());
                    List<PalletEntity> pallets = helper.getEntities(WareworksEntityTypes.PALLET.get());
                    helper.assertValueEqual(pallets.size(), 1, "the one pallet still lies there, and only it");
                    helper.assertTrue(ItemRequirement.of(pallets.getFirst()).isInvalid(),
                            "and the Schematicannon could never have printed it");
                    ItemCensus.assertEquals(helper, items, "right after the print");

                    pallets.getFirst().discard();
                    items.clear();
                })
                .thenSucceed();
    }

    // --- a port printed over -------------------------------------------------------------------------------------------

    private static final BlockPos PORT = new BlockPos(3, BASE_Y, 3);
    /** An overflow port: it accepts what no storage location took, and in pulse mode only once a pulse armed it. */
    private static final int OVERFLOW_RANK = -3;
    private static final int EXPORTED = 24;

    /**
     * A port that accepts on a pulse, armed by a rising edge no plan has spent yet, with an export counted and the
     * refusal of its last request remembered: what a redstone pulse and a working warehouse leave behind and the
     * goggles show, all of it runtime state a save writes only while it is set. A schematic saved before the pulse holds
     * the port unpowered, so the standing port's {@code POWERED} flag differs and Create prints over it — the most
     * common print over a standing Wareworks block, because a port has three block-state flags that change at runtime.
     * That print carries the port's settings alone and must leave the token, the counter and the refusal where they
     * are; a full save without them still resets all three (issue #25, ADR-061).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void aPrintOverAStandingPortKeepsItsPulseAndItsCounters(GameTestHelper helper) {
        helper.setBlock(PORT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
        Map<ItemKey, Long> nothing = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, nothing, "while a port is printed over"));

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity port = blockEntity(helper, PORT, WarehouseOutputBlockEntity.class);
                    FilteringBehaviour filter = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
                    helper.assertTrue(filter.setFilter(COBBLESTONE.toStack(HELD)), "the port takes a filter");
                    // Still a requesting port: its request is refused, as it is for any port outside an aisle.
                    helper.assertFalse(port.submitRequest().isAccepted(), "a port outside an aisle is refused");
                    RequestRejection refusal = port.lastRejection().orElse(null);
                    helper.assertTrue(refusal != null, "and remembers why");
                    helper.assertTrue(port.setPortRank(OVERFLOW_RANK), "the port becomes an overflow");
                    helper.assertValueEqual(port.redstoneMode(), PortRedstone.PULSE, "which acts on a pulse");
                    String file = SchematicPrint.save(helper, "port_unpowered", PORT, PORT);

                    // The pulse that arms it, and an export counted.
                    helper.setBlock(PORT.east(), Blocks.REDSTONE_BLOCK);
                    helper.assertTrue(port.isPowered() && port.isArmed(), "a rising edge arms the port");
                    port.recordExport(EXPORTED);
                    helper.assertValueEqual(port.exportedItems(), (long) EXPORTED, "an export is counted");

                    SchematicPrint.Result result = SchematicPrint.print(helper, file, PORT, false);
                    helper.assertValueEqual(result.blocks().size(), 1,
                            "the saved port is unpowered, so Create prints over the standing one");
                    assertPrintCarries(helper, result.at(helper, helper.absolutePos(PORT)), "an overflow port",
                            StorageFilterBehaviour.FILTER_TAG, "PortRank");
                    helper.assertTrue(blockEntity(helper, PORT, WarehouseOutputBlockEntity.class) == port,
                            "the print loaded into the standing block entity");
                    helper.assertFalse(port.isPowered(), "which now has the saved block state");
                    helper.assertTrue(port.isArmed(), "a print over the port leaves the pulse it was armed with");
                    helper.assertValueEqual(port.exportedItems(), (long) EXPORTED, "and its export counter");
                    helper.assertValueEqual(port.lastRejection(), Optional.of(refusal), "and its last refusal");
                    helper.assertValueEqual(port.portRank(), OVERFLOW_RANK, "and has its settings");

                    // A full save is read whole, so one without the three keys resets them: the guard is not on the
                    // keys, which a save leaves out while they are at their defaults.
                    CompoundTag reset = port.saveWithFullMetadata(registries);
                    reset.remove(WarehouseOutputBlockEntity.ARMED_TAG);
                    reset.remove(WarehouseOutputBlockEntity.EXPORTED_TAG);
                    reset.remove(WarehouseDeliveryStationBlockEntity.LAST_REJECTION_TAG);
                    port.loadWithComponents(reset, registries);
                    helper.assertFalse(port.isArmed(), "a full save without the token disarms the port");
                    helper.assertValueEqual(port.exportedItems(), 0L, "one without the counter zeroes it");
                    helper.assertTrue(port.lastRejection().isEmpty(), "and one without the refusal clears it");
                })
                .thenSucceed();
    }

    // --- the filter format of earlier versions -------------------------------------------------------------------------

    /** Create's own filter keys, which every version before issue #25 wrote for a storage location too. */
    private static final String FILTER_AMOUNT_TAG = "FilterAmount";
    private static final String UP_TO_TAG = "UpTo";
    /** Create's default filter amount, which a storage location never changed: its slot has no amount. */
    private static final int CREATE_DEFAULT_AMOUNT = 64;
    private static final int OLD_PRIORITY = 3;

    /**
     * Every version before issue #25 saved a storage location's filter with Create's own writer: the whole stack the
     * player held as {@code Filter}, and {@code FilterAmount} and {@code UpTo} at Create's defaults. That is what every
     * world and every schematic from those versions holds. A rack bay, a fluid bay and a warehouse interface loaded
     * from it keep their filter and their priority and filter exactly as before, and the next save and the next print
     * write the format of today: the filter as a sample of one, and no amount keys.
     * <p>
     * The tag is loaded into the fresh block entity standing in the world, which reads it exactly as a world load reads
     * a save and can still evaluate the filter it read.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void storageLocationsLoadTheFilterFormatOfEarlierVersions(GameTestHelper helper) {
        helper.setBlock(BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState().setValue(RackBayBlock.FACING,
                Direction.NORTH));
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                Direction.NORTH));
        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        Map<ItemKey, Long> nothing = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, nothing, "while older saves are loaded"));

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    RackBayBlockEntity bay = blockEntity(helper, BAY, RackBayBlockEntity.class);
                    loadOlderFilterSave(helper, bay, COBBLESTONE.toStack(HELD));
                    helper.assertTrue(bay.hasStoreFilter(), "the rack bay has its filter");
                    helper.assertValueEqual(ItemKey.of(bay.storeFilter()), COBBLESTONE, "of cobblestone");
                    helper.assertValueEqual(bay.storePriority(), OLD_PRIORITY, "and its priority");
                    helper.assertTrue(bay.insert(COBBLESTONE.toStack(), true).isEmpty(), "it takes cobblestone");
                    helper.assertFalse(bay.insert(IRON.toStack(), true).isEmpty(), "and refuses iron");
                    assertTodaysFilterFormat(helper, bay, COBBLESTONE, "the rack bay");

                    FluidBayBlockEntity tank = blockEntity(helper, FLUID_BAY, FluidBayBlockEntity.class);
                    loadOlderFilterSave(helper, tank, new ItemStack(Items.LAVA_BUCKET));
                    helper.assertValueEqual(tank.filterFluid(), Optional.of(LAVA), "the fluid bay has its filter");
                    helper.assertValueEqual(tank.storePriority(), OLD_PRIORITY, "and its priority");
                    helper.assertValueEqual(tank.fill(new FluidStack(Fluids.LAVA, 1_000), true), 1_000,
                            "it takes lava");
                    helper.assertValueEqual(tank.fill(new FluidStack(Fluids.WATER, 1_000), true), 0,
                            "and refuses water");
                    assertTodaysFilterFormat(helper, tank, ItemKey.of(Items.LAVA_BUCKET), "the fluid bay");

                    WarehouseInterfaceBlockEntity storage = blockEntity(helper, INTERFACE,
                            WarehouseInterfaceBlockEntity.class);
                    loadOlderFilterSave(helper, storage, IRON.toStack(HELD));
                    helper.assertTrue(storage.hasStoreFilter(), "the interface has its filter");
                    helper.assertValueEqual(ItemKey.of(storage.storeFilter()), IRON, "of iron");
                    helper.assertValueEqual(storage.storePriority(), OLD_PRIORITY, "and its priority");
                    FilterItemStack resolved = FilterItemStack.of(storage.storeFilter());
                    helper.assertTrue(resolved.test(helper.getLevel(), IRON.toStack()), "it accepts iron");
                    helper.assertFalse(resolved.test(helper.getLevel(), COBBLESTONE.toStack()),
                            "and rejects cobblestone");
                    assertTodaysFilterFormat(helper, storage, IRON, "the interface");
                })
                .thenSucceed();
    }

    /** Loads {@code be}'s own save, rewritten to hold {@code filter} and a priority the way Create wrote them. */
    private static void loadOlderFilterSave(GameTestHelper helper, BlockEntity be, ItemStack filter) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = be.saveWithFullMetadata(registries);
        tag.put(StorageFilterBehaviour.FILTER_TAG, filter.saveOptional(registries));
        tag.putInt(FILTER_AMOUNT_TAG, CREATE_DEFAULT_AMOUNT);
        tag.putBoolean(UP_TO_TAG, true);
        tag.putInt(StorageFilterBehaviour.PRIORITY_TAG, OLD_PRIORITY);
        be.loadWithComponents(tag, registries);
    }

    /**
     * Fails unless the next save and the next print of {@code be} write the filter as a sample of one {@code item},
     * the priority, and no amount keys; the print carries those two settings and nothing else.
     */
    private static void assertTodaysFilterFormat(GameTestHelper helper, BlockEntity be, ItemKey item, String what) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = be.saveWithFullMetadata(registries);
        CompoundTag printed = BlockHelper.prepareBlockEntityData(helper.getLevel(), be.getBlockState(), be);
        helper.assertTrue(printed != null, what + ": a print carries its settings");
        helper.assertValueEqual(new TreeSet<>(printed.getAllKeys()),
                new TreeSet<>(Set.of(StorageFilterBehaviour.FILTER_TAG, StorageFilterBehaviour.PRIORITY_TAG)),
                what + ": the keys a print carries");
        for (Map.Entry<String, CompoundTag> written : Map.of("save", saved, "print", printed).entrySet()) {
            CompoundTag tag = written.getValue();
            String where = what + "'s next " + written.getKey();
            ItemStack sample = ItemStack.parseOptional(registries, tag.getCompound(StorageFilterBehaviour.FILTER_TAG));
            helper.assertTrue(item.matches(sample), where + " keeps the filter, but has " + sample);
            helper.assertValueEqual(sample.getCount(), 1, where + " writes it as a sample of one");
            helper.assertFalse(tag.contains(FILTER_AMOUNT_TAG) || tag.contains(UP_TO_TAG),
                    where + " writes no amount keys");
            helper.assertValueEqual(tag.getInt(StorageFilterBehaviour.PRIORITY_TAG), OLD_PRIORITY,
                    where + " keeps the priority");
        }
    }

    // --- the way through a schematic -----------------------------------------------------------------------------------

    /**
     * A block saved alone and printed fresh at {@code copy}.
     *
     * @param name the schematic file it was saved to
     * @param copy what Create printed at {@code copy}
     */
    private record Travel(String name, SchematicPrint.Printed copy) {
        /** The block's entry in the saved file (never null for a block with a block entity). */
        CompoundTag file() {
            return copy.file() == null ? new CompoundTag() : copy.file();
        }
    }

    private static Travel saveAndPrintCopy(GameTestHelper helper, String name, BlockPos pos, BlockPos copy) {
        String file = SchematicPrint.save(helper, name, pos, pos);
        SchematicPrint.Result result = SchematicPrint.print(helper, file, copy, false);
        helper.assertValueEqual(result.blocks().size(), 1, name + ": one block is printed");
        return new Travel(file, result.at(helper, helper.absolutePos(copy)));
    }

    /**
     * Fails unless the data a print handed this block holds <b>exactly</b> {@code keys} at the top level, nothing that is
     * or names goods anywhere below them, and unless Create's material list asks for the block itself and nothing else.
     */
    private static void assertPrintCarries(GameTestHelper helper, SchematicPrint.Printed printed, String what,
            String... keys) {
        CompoundTag data = printed.data();
        if (data == null) {
            helper.fail(what + ": Create's printer handed it no data at all, so its settings cannot travel");
            return;
        }
        helper.assertValueEqual(new TreeSet<>(data.getAllKeys()), new TreeSet<>(Set.of(keys)),
                what + ": the keys a print carries");
        List<String> goods = new ArrayList<>();
        findGoods("", data, goods);
        helper.assertTrue(goods.isEmpty(), what + ": a print carries goods: " + goods + " in " + data);

        ItemRequirement requirement = printed.requirement();
        helper.assertFalse(requirement.isInvalid(), what + ": the Schematicannon cannot print it");
        for (ItemRequirement.StackRequirement required : requirement.getRequiredItems()) {
            ItemStack stack = required.stack;
            boolean theBlock = stack.is(printed.state().getBlock().asItem()) && stack.getCount() == 1;
            helper.assertTrue(theBlock || stack.getItem() instanceof FilterItem,
                    what + ": the material list asks for " + stack + ", which is not the block");
        }
    }

    /**
     * Collects every place in {@code tag} that is or names goods: a key of {@link #GOODS_KEYS}, an item stack other than
     * a filter sample of one, and any fluid stack.
     */
    private static void findGoods(String path, Tag tag, List<String> goods) {
        if (tag instanceof CompoundTag compound) {
            if (isStack(compound, "count", true)) {
                boolean sample = path.equals(StorageFilterBehaviour.FILTER_TAG) && compound.getInt("count") <= 1;
                if (!sample)
                    goods.add(path + " is an item stack " + compound);
            }
            if (isStack(compound, "amount", false))
                goods.add(path + " is a fluid stack " + compound);
            for (String key : compound.getAllKeys()) {
                String child = path.isEmpty() ? key : path + "." + key;
                if (GOODS_KEYS.contains(key))
                    goods.add(child);
                Tag value = compound.get(key);
                if (value != null)
                    findGoods(child, value, goods);
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++)
                findGoods(path + "[" + i + "]", list.get(i), goods);
        }
    }

    /** Whether {@code compound} looks like a stack: a registered item (or fluid) id and an amount under {@code key}. */
    private static boolean isStack(CompoundTag compound, String key, boolean item) {
        if (!compound.contains("id", Tag.TAG_STRING) || !compound.contains(key, Tag.TAG_ANY_NUMERIC))
            return false;
        ResourceLocation id = ResourceLocation.tryParse(compound.getString("id"));
        return id != null && (item ? BuiltInRegistries.ITEM.containsKey(id) : BuiltInRegistries.FLUID.containsKey(id));
    }

    private static void assertFileHoldsBuffer(GameTestHelper helper, Travel travel, String what) {
        helper.assertFalse(travel.file().getCompound(WarehouseStationBlockEntity.BUFFER_TAG)
                .getList("Items", Tag.TAG_COMPOUND).isEmpty(), "the saved file holds " + what + "'s buffer");
    }

    private static void assertEmptyBuffer(GameTestHelper helper, WarehouseStationBlockEntity station, String what) {
        helper.assertTrue(station.bufferedItems().keys().isEmpty(), what + " holds nothing, but has "
                + station.bufferedItems());
    }

    /** Fails unless {@code keeper} holds exactly the one rule the working warehouse's keeper was given. */
    private static void assertKeeperRule(GameTestHelper helper, WarehouseStockKeeperBlockEntity keeper, String what) {
        StockRule<ItemKey> rule = keeper.rules().ruleAt(0).orElse(null);
        helper.assertTrue(keeper.rules().ruleCount() == 1 && rule != null && rule.key().equals(IRON)
                && rule.minimum() == RULE_MINIMUM && rule.maximum() == RULE_MAXIMUM && rule.reserve() == RULE_RESERVE,
                what + ": " + keeper.rules().rules());
    }

    private static void assertEmptyController(GameTestHelper helper, BlockPos pos) {
        WarehouseControllerBlockEntity copy = blockEntity(helper, pos, WarehouseControllerBlockEntity.class);
        helper.assertValueEqual(copy.aisleLetter(), 'C', "the printed controller has the aisle letter");
        helper.assertTrue(copy.storageLocations().isEmpty(), "but knows no storage location");
        helper.assertValueEqual(copy.openRequestCount(), 0, "no request");
        helper.assertTrue(copy.productionOrders().isEmpty(), "and no production order");
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private static BlockPos copyAt(int slot) {
        return new BlockPos(2 * slot, BASE_Y, COPY_Z);
    }

    private static void assertStocked(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "storage read");
        helper.assertValueEqual(controller.countOf(IRON), (long) IRON_IN_STOCK, "the iron is in");
        helper.assertValueEqual(controller.countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "the diamonds");
        helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK, "and the logs");
        helper.assertValueEqual(controller.productionStations().size(), 1, "the production station is recorded");
    }

    /** Puts {@code stack} into a delivery station's buffer the way the crane's drop does. */
    private static void deliver(GameTestHelper helper, WarehouseDeliveryStationBlockEntity station, ItemStack stack) {
        helper.assertTrue(station.insert(stack, false).isEmpty(), "the station takes " + stack);
    }

    private static ScrollValueBehaviour scrollValue(GameTestHelper helper, BlockEntity be) {
        ScrollValueBehaviour behaviour = be instanceof SmartBlockEntity smart
                ? BlockEntityBehaviour.get(smart, ScrollValueBehaviour.TYPE) : null;
        if (behaviour == null)
            helper.fail("no value box on " + be);
        return behaviour;
    }

    /** A clipboard with one entry, written the way the Schematicannon writes its material checklist. */
    private static ItemStack clipboardOf(ItemKey key, int amount) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        ClipboardEntry entry = new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                .displayItem(key.toStack(), amount);
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entry)), true));
        return stack;
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static <T extends BlockEntity> T blockEntity(GameTestHelper helper, BlockPos pos, Class<T> type) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!type.isInstance(be)) {
            helper.fail("expected a " + type.getSimpleName() + " but found " + be, pos);
            throw new IllegalStateException("unreachable");
        }
        return type.cast(be);
    }

    private static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
