package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.DoorDirectory;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageDoors;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.terminal.FluidAcknowledgement;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of package mode's lockouts, storage rule and doors (issue #40, slices S2 and S3; owner decisions E1, E3,
 * E6, E7): with the world's {@code [packageMode] enabled} switch on, the warehouse starts no new production, the
 * terminal hands out nothing loose that it would have to fetch or fill, the crane keeps boxes out of empty rack bays
 * without a Package Filter, a plain requesting port is a door, and a Packager door without a sign is refused. Work that
 * was open when the world loaded runs to its end.
 * <ul>
 * <li>{@code packagemodemakesnothingnew}: no pattern is offered, a request that would be made is refused with
 * {@code PACKAGE_MODE} from the terminal, a computer and a Schematicannon Requester, automatic restocking reports itself
 * switched off, no production order is ever created, and loose stock from before is still served;</li>
 * <li>{@code packagemodeterminalhandsoutnothingloose}: a Fetch, a recipe viewer's lay, a clipboard list and a fluid fill
 * are refused, a plain click on loose stock is not;</li>
 * <li>{@code packagemodeboxskipsemptybays}: a box goes into an empty bay only when the bay carries a Package Filter that
 * takes it, otherwise past the empty bay into a chest; a census holds on every tick;</li>
 * <li>{@code packagemodeplainportisadoor}: a requesting port without a Packager is listed and chosen as a door, and the
 * box leaves it with its address;</li>
 * <li>{@code packagemodesignlesspackagerisrefused}: a Packager door without a sign is listed but cannot be chosen, and a
 * click to it is refused with {@code DOOR_STRIPS_ADDRESS}; a door with a sign still takes the order;</li>
 * <li>{@code packagemodeopenproductionrunstoitsend} (its own batch): a production order opened before the switch is
 * still supplied and completed and its result delivered, while a second order for the same item is refused.</li>
 * </ul>
 * The switch is set through {@link ConfigOverrides} in batches of their own and restored after each batch.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageModeLockoutGameTests {
    static final String BATCH = "wareworkspackagemodelockout";
    static final String RUN_BATCH = "wareworkspackagemoderun";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int PUSH_TICKS = 5;

    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition RUN_INPUT_RACK = new RackPosition(3, 0, Side.RIGHT);

    /** The empty rack bay nearest the input: no filter, so package mode keeps boxes out of it. */
    private static final RackPosition PLAIN_BAY_RACK = new RackPosition(1, 0, Side.LEFT);
    /** A rack bay with a Package Filter for {@value #FILTER_ADDRESS}. */
    private static final RackPosition FILTERED_BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(4, 0, Side.LEFT);

    /** A gap of one rack to {@link #SIGNED_DOOR_RACK}, so the signed door's Packager never stands beside this one. */
    private static final RackPosition DOOR_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition SIGNED_DOOR_RACK = new RackPosition(4, 0, Side.RIGHT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final int LOGS = 16;
    private static final int PLANKS_PER_LOG = 4;
    private static final int PLANKS_IN_STOCK = 32;
    private static final int ORDERED = 8;
    private static final int BOXED = 12;
    private static final String FILTER_ADDRESS = "Base*";
    private static final String BASE_NORTH = "Base North";
    private static final String ELSEWHERE = "Elsewhere";
    private static final String SIGN = "Outpost";

    private PackageModeLockoutGameTests() {
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAfterLockouts(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = RUN_BATCH)
    public static void restoreAfterRun(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    private static void switchOn(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.packageModeEnabled, true);
        helper.assertTrue(WareworksConfig.packageModeEnabled(), "package mode is on for this batch");
    }

    // --- S2: no new production -------------------------------------------------------------------------------------

    /**
     * Every way production starts is refused while the patterns stay in the station: the terminal's "make" (a request
     * past the racks), a computer's, a Schematicannon Requester's and the keeper's automatic restock. The reason says
     * package mode, and only for an item a pattern would make. Loose stock from before is still served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodemakesnothingnew(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(LOG_RACK, LOG.toStack(LOGS));
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "the pattern's result");
        helper.onEachTick(() -> aisle.controllerIfPresent().ifPresent(controller -> helper.assertTrue(
                controller.productionOrders().isEmpty(), "no production order is ever created in package mode")));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS, "logs in stock");
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(station.patterns().patternCount() > 0, "the station keeps its pattern");
                    helper.assertTrue(controller.aislePatterns().isEmpty(), "but the aisle offers no pattern");
                    helper.assertTrue(controller.producibleKeys().isEmpty(), "and nothing is producible");
                    helper.assertValueEqual(controller.producibleAmount(PLANK), 0L, "not even the planks");
                    helper.assertTrue(controller.packageModeWouldMake(PLANK), "which package mode keeps from being made");
                    helper.assertFalse(controller.packageModeWouldMake(DIRT), "unlike an item no pattern makes");

                    BlockPos here = aisle.absoluteRackPos(TERMINAL_RACK);
                    assertRefused(helper, controller.request(here, PLANK, ORDERED), RequestRejection.PACKAGE_MODE,
                            "a terminal's make");
                    assertRefused(helper, controller.request(here, DIRT, ORDERED), RequestRejection.NOT_IN_STOCK,
                            "an item nothing makes keeps its old reason");
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    assertRefused(helper, terminal.requestFromComputer(PLANK, ORDERED, Optional.empty()),
                            RequestRejection.PACKAGE_MODE, "a computer's make");
                    WarehouseTerminalBlockEntity.RequesterRequest requester = terminal.requestForRequester(PLANK,
                            ORDERED, ORDERED);
                    assertRefused(helper, requester.result(), RequestRejection.PACKAGE_MODE, "a requester's make");
                    helper.assertTrue(requester.productionRoot().isEmpty(), "with no production behind it");

                    RequestResult logs = controller.request(here, LOG, 2);
                    helper.assertTrue(logs.isAccepted(), "loose stock from before is still served: " + logs);
                    helper.assertValueEqual(logs.producing(), 0, "from the racks");

                    WarehouseStockKeeperAccess.keep(aisle, KEEPER_RACK, PLANK, 10);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().restockOutcomeOf(PLANK),
                        RestockOutcome.PACKAGE_MODE, "a rule short of its minimum names package mode as the reason"))
                .thenSucceed();
    }

    /**
     * The terminal hands out nothing loose it would have to fetch, lay or fill (owner decision E6): a crafting Fetch, a
     * recipe viewer's lay, a clipboard list and a fluid fill are refused with package mode as the reason and request
     * nothing; a plain click on loose stock from before is still served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeterminalhandsoutnothingloose(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, PLANK.toStack(PLANKS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(PLANK),
                        (long) PLANKS_IN_STOCK, "planks in stock"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertValueEqual(terminal.fetchCraft(player, menu, 1, 0L, false).result(),
                            TerminalCraftResult.PACKAGE_MODE, "a crafting Fetch");
                    List<List<ItemKey>> cells = new ArrayList<>();
                    for (int cell = 0; cell < 9; cell++)
                        cells.add(cell == 4 ? List.of() : List.of(PLANK));
                    helper.assertValueEqual(menu.layRecipe(player, cells, Optional.empty(), true).result(),
                            TerminalCraftResult.PACKAGE_MODE, "a recipe viewer's lay");
                    helper.assertTrue(menu.craftCells().isEmpty(), "which laid nothing");
                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(),
                            TerminalListResult.PACKAGE_MODE, "a clipboard list");
                    helper.assertValueEqual(terminal.requestFluidFromTerminal(player, FluidKey.of(Fluids.WATER), 1000L,
                            FluidAcknowledgement.NONE, Optional.empty()).result().flatMap(RequestResult::rejection),
                            Optional.of(RequestRejection.PACKAGE_MODE), "a fluid fill");
                    helper.assertFalse(terminal.hasCraftOrder() || terminal.hasListOrder(), "no order started");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and nothing was requested");

                    Optional<RequestResult> click = terminal.requestFromTerminal(player, PLANK, ORDERED,
                            RequestAcknowledgement.NONE).result();
                    helper.assertTrue(click.map(RequestResult::isAccepted).orElse(false),
                            "a plain click on loose stock is still served: " + click);
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 1, "as one request");
                })
                .thenSucceed();
    }

    // --- S3: storage ------------------------------------------------------------------------------------------------

    /**
     * Owner decision E3: the crane puts no box into an <b>empty</b> rack bay unless the bay carries a Package Filter. A
     * box the filtered bay takes goes there; a box it refuses goes past the empty plain bay, the nearest place, into the
     * chest. Nothing is lost on the way.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeboxskipsemptybays(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        placeBay(helper, aisle, PLAIN_BAY_RACK);
        placeBay(helper, aisle, FILTERED_BAY_RACK);
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        ItemStack filter = AllItems.PACKAGE_FILTER.asStack();
        PackageItem.addAddress(filter, FILTER_ADDRESS);
        helper.assertTrue(bayAt(helper, aisle, FILTERED_BAY_RACK).setStoreFilter(filter), "the bay takes the filter");
        ItemStack toBase = PackageDoorFixture.packageOf(IRON.toStack(BOXED));
        PackageItem.addAddress(toBase, BASE_NORTH);
        ItemStack elsewhere = PackageDoorFixture.packageOf(GOLD.toStack(BOXED));
        PackageItem.addAddress(elsewhere, ELSEWHERE);
        ItemKey toBaseKey = ItemKey.of(toBase);
        ItemKey elsewhereKey = ItemKey.of(elsewhere);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while boxes are stored"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 0))
                .thenExecute(() -> {
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(input, toBase.copy());
                    aisle.insertAll(input, elsewhere.copy());
                    ItemCensus.change(expected, IRON, BOXED);
                    ItemCensus.change(expected, GOLD, BOXED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    // A bay is its own inventory, not one behind it.
                    helper.assertValueEqual(aisle.inventoryCount(aisle.rackPos(FILTERED_BAY_RACK), toBaseKey), 1L,
                            "the box for Base North went into the bay whose Package Filter takes it");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, elsewhereKey), 1L,
                            "the other box went past the empty plain bay into the chest");
                })
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().stockIndex().countsAt(PLAIN_BAY_RACK).isEmpty(),
                            "the plain bay stays empty");
                    helper.assertTrue(aisle.controller().packageModeExcludes(PLAIN_BAY_RACK, elsewhereKey),
                            "it is excluded for a box");
                    helper.assertFalse(aisle.controller().packageModeExcludes(PLAIN_BAY_RACK, IRON),
                            "and not for a loose item");
                    helper.assertFalse(aisle.controller().packageModeExcludes(FILTERED_BAY_RACK, elsewhereKey),
                            "a bay with a Package Filter is never excluded");
                    helper.assertFalse(aisle.controller().packageModeExcludes(CHEST_RACK, elsewhereKey),
                            "and a chest neither");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- S3: doors --------------------------------------------------------------------------------------------------

    /**
     * Design R-OUT-2: in package mode a requesting port <b>without</b> a Packager is a door. It is listed and may be
     * chosen, and the stored box leaves it as itself, its address included.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeplainportisadoor(GameTestHelper helper) {
        switchOn(helper);
        ItemStack box = PackageDoorFixture.packageOf(IRON.toStack(BOXED));
        PackageItem.addAddress(box, BASE_NORTH);
        ItemKey boxKey = ItemKey.of(box);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, box.copy());
        aisle.terminal(TERMINAL_RACK);
        aisle.output(DOOR_RACK);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, BOXED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while a box goes out of a plain port"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 2);
                    helper.assertValueEqual(aisle.controller().countOf(boxKey), 1L, "the box is stored");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(PackageDoors.look(helper.getLevel(), aisle.controller(), door).state(),
                            Optional.of(DoorState.PLAIN_PORT), "a plain requesting port is a door");
                    helper.assertTrue(PackageDoors.refusal(helper.getLevel(), aisle.controller(), door).isEmpty(),
                            "that may be chosen");
                    Optional<RequestResult> result = aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(player,
                            boxKey, 1, RequestAcknowledgement.NONE, Optional.of(door)).result();
                    helper.assertTrue(result.map(RequestResult::isAccepted).orElse(false),
                            "the box is ordered to it: " + result);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenIdle(DoorDirectory.MAX_AGE_TICKS + PUSH_TICKS)
                .thenExecute(() -> {
                    List<TerminalDoorRow> rows = aisle.controller().doorDirectory()
                            .terminalRows(helper.getLevel().getGameTime()).rows();
                    helper.assertValueEqual(rows.stream().map(TerminalDoorRow::pos).toList(), List.of(door),
                            "the terminal lists the plain port as a door");
                    helper.assertTrue(rows.getFirst().state().selectable(), "one that may be chosen");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(DOOR_RACK, boxKey), 1L,
                        "the crane put the box into the port"))
                .thenExecute(() -> {
                    IItemHandler port = aisle.handlerAt(aisle.rackPos(DOOR_RACK));
                    ItemStack left = ItemStack.EMPTY;
                    for (int slot = 0; slot < port.getSlots() && left.isEmpty(); slot++)
                        left = port.extractItem(slot, 1, false);
                    helper.assertTrue(PackageItem.isPackage(left), "a funnel takes the box out: " + left);
                    helper.assertValueEqual(PackageItem.getAddress(left), BASE_NORTH, "with its address");
                    helper.assertTrue(ItemStack.matches(left, box), "the box that left is the box that was stored");
                    ItemCensus.change(expected, IRON, -BOXED);
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Owner decision E7: a Packager door without a sign would send a stored package out with no address (a Packager
     * clears it and writes only its sign's, ADR-065 D3 as corrected). In package mode it is listed, cannot be chosen,
     * and a click to it is refused with {@code DOOR_STRIPS_ADDRESS}; a door with a sign still takes the order.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodesignlesspackagerisrefused(GameTestHelper helper) {
        switchOn(helper);
        ItemStack box = PackageDoorFixture.packageOf(IRON.toStack(BOXED));
        PackageItem.addAddress(box, BASE_NORTH);
        ItemKey boxKey = ItemKey.of(box);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, box.copy());
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, null);
        placeDoor(helper, aisle, SIGNED_DOOR_RACK, SIGN);
        BlockPos unsigned = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos signed = aisle.absoluteRackPos(SIGNED_DOOR_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 3);
                    helper.assertValueEqual(aisle.controller().countOf(boxKey), 1L, "the box is stored");
                })
                .thenExecute(() -> {
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    helper.assertValueEqual(PackageDoors.look(helper.getLevel(), aisle.controller(), unsigned).state(),
                            Optional.of(DoorState.STRIPS_ADDRESS), "a Packager without a sign would strip the address");
                    helper.assertValueEqual(PackageDoors.refusal(helper.getLevel(), aisle.controller(), unsigned),
                            Optional.of(RequestRejection.DOOR_STRIPS_ADDRESS), "so it is refused");
                    Optional<RequestResult> refused = terminal.get().requestFromTerminal(player, boxKey, 1,
                            RequestAcknowledgement.NONE, Optional.of(unsigned)).result();
                    helper.assertValueEqual(refused.flatMap(RequestResult::rejection),
                            Optional.of(RequestRejection.DOOR_STRIPS_ADDRESS), "a click to it is refused");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and requests nothing");

                    helper.assertValueEqual(PackageDoors.look(helper.getLevel(), aisle.controller(), signed).state(),
                            Optional.of(DoorState.READY), "a door with a sign is ready");
                    Optional<RequestResult> accepted = terminal.get().requestFromTerminal(player, boxKey, 1,
                            RequestAcknowledgement.NONE, Optional.of(signed)).result();
                    helper.assertTrue(accepted.map(RequestResult::isAccepted).orElse(false),
                            "and takes the order: " + accepted);
                })
                .thenIdle(DoorDirectory.MAX_AGE_TICKS + PUSH_TICKS)
                .thenExecute(() -> {
                    List<TerminalDoorRow> rows = aisle.controller().doorDirectory()
                            .terminalRows(helper.getLevel().getGameTime()).rows();
                    helper.assertValueEqual(rows.stream().map(TerminalDoorRow::pos).toList(), List.of(unsigned, signed),
                            "both doors are listed");
                    helper.assertValueEqual(rows.get(0).state(), DoorState.STRIPS_ADDRESS, "the signless one red");
                    helper.assertFalse(rows.get(0).state().selectable(), "where nobody can choose it");
                    helper.assertValueEqual(rows.get(1).state(), DoorState.READY, "the signed one ready");
                })
                .thenSucceed();
    }

    // --- open work drains -------------------------------------------------------------------------------------------

    /**
     * Owner decision E2: a production order opened <b>before</b> the switch (a world loaded with the mode on) runs to its
     * end. The crane still supplies its machine, its loose results come back through the input, are stored and
     * delivered, and the order completes; a second order for the same item, asked for meanwhile, is refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = RUN_BATCH)
    public static void packagemodeopenproductionrunstoitsend(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOG_RACK, LOG.toStack(LOGS));
        aisle.storage(STORAGE_RACK);
        aisle.input(RUN_INPUT_RACK);
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_LOG),
                "the pattern's result");
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, LOGS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the open order drains"));
        AtomicReference<ProductionOrder<ItemKey, RackPosition>> order = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    helper.assertValueEqual(aisle.controller().countOf(LOG), (long) LOGS, "logs in stock");
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station");
                })
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(TERMINAL_RACK), PLANK,
                            ORDERED);
                    helper.assertValueEqual(result.producing(), ORDERED, "an order for all of it, before the switch");
                    order.set(aisle.controller().productionOrders().getFirst());
                    // The world "loads" with package mode on while the order is open.
                    switchOn(helper);
                    assertRefused(helper, aisle.controller().request(aisle.absoluteRackPos(TERMINAL_RACK), PLANK, 4),
                            RequestRejection.PACKAGE_MODE, "a second order for the same item");
                    helper.assertValueEqual(aisle.controller().productionOrders().size(), 1, "the first stays alone");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) (ORDERED / PLANKS_PER_LOG), "the crane still supplies the open order's machine"))
                .thenExecute(() -> {
                    // The test is the player's machine: it takes the logs and hands the planks back through the input.
                    IItemHandler machine = aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK));
                    int taken = 0;
                    for (int slot = 0; slot < machine.getSlots(); slot++)
                        taken += machine.extractItem(slot, 64, false).getCount();
                    helper.assertValueEqual(taken, ORDERED / PLANKS_PER_LOG, "the machine took the logs");
                    ItemCensus.change(expected, LOG, -taken);
                    int made = taken * PLANKS_PER_LOG;
                    ItemStack rest = aisle.handlerAt(aisle.rackPos(RUN_INPUT_RACK)).insertItem(0, PLANK.toStack(made),
                            false);
                    helper.assertTrue(rest.isEmpty(), "the input lets the open order's results in: " + rest);
                    ItemCensus.change(expected, PLANK, made);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) ORDERED,
                            "the planks were stored and delivered");
                    helper.assertValueEqual(aisle.controller().productionOrder(order.get().id())
                            .map(ProductionOrder::state).orElse(null), ProductionOrderState.COMPLETE,
                            "and the order completed");
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.controller().productionOrders().size(), 1,
                        "no other order came"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void assertRefused(GameTestHelper helper, RequestResult result, RequestRejection reason,
            String what) {
        helper.assertFalse(result.isAccepted(), what + " is refused: " + result);
        helper.assertValueEqual(result.rejection(), Optional.of(reason), what + " is refused for the right reason");
        helper.assertValueEqual(result.producing(), 0, what + " makes nothing");
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static void placeBay(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        helper.setBlock(aisle.rackPos(rack), WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, aisle.sideDirection(rack)));
    }

    private static RackBayBlockEntity bayAt(GameTestHelper helper, AisleFixture aisle, RackPosition rack) {
        RackBayBlockEntity bay = WareworksBlockEntityTypes.RACK_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(rack)));
        if (bay == null) {
            helper.fail("missing rack bay", aisle.rackPos(rack));
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    /** A Packager door at {@code rack}, as {@code PackageDoorGameTests} builds one; no sign for a {@code null} address. */
    private static void placeDoor(GameTestHelper helper, AisleFixture aisle, RackPosition rack, String address) {
        aisle.output(rack);
        BlockPos packager = aisle.inventoryPos(rack);
        PackageDoorFixture.placePackager(helper, packager, aisle.sideDirection(rack));
        if (address != null)
            PackageDoorFixture.writeSign(helper, packager.relative(AisleFixture.AISLE), true, address);
    }

    /** The keeper's first row, as {@code StockRestockGameTests} writes it. */
    private static final class WarehouseStockKeeperAccess {
        static void keep(AisleFixture aisle, RackPosition keeper, ItemKey key, int minimum) {
            aisle.stockKeeperAt(keeper).editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
            aisle.stockKeeperAt(keeper).editRule(0, StockKeeperRules.FIELD_MINIMUM, null, (long) minimum);
        }
    }
}
