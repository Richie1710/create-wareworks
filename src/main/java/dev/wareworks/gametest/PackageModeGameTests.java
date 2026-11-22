package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.handtruck.HandTruckPallet;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.WarehouseInputArmPoint;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of package mode's switch and entrances (issue #40, owner decisions E1/E2/E4, 2026-11-22): with the world's
 * {@code [packageMode] enabled} switch on, every entrance of a warehouse takes in only sealed Create packages, and
 * nothing loose is ever stored or lost.
 * <ul>
 * <li>{@code packagemodeinputgatesitsentrances}: capability, belt side and arm view refuse a loose stack and take a
 * package; the crane's own insert stays open; the goggles name the refusal and what waits; an empty hand takes the
 * loose items out and leaves the package;</li>
 * <li>{@code packagemodeindoorrefusesaboxwhole}: a Packager behind the input is refused a box in both passes, so it
 * stays whole in front of the Packager;</li>
 * <li>{@code packagemodebayrefusesloose}: a rack bay's outer face, belt side and a player's hand refuse a loose stack,
 * take a package, and still give loose stock out; a pallet of loose goods is refused with its own line;</li>
 * <li>{@code packagemodebackstopkeepslooseintheinput}: a loose stack that reached an input anyway (the crane's reroute
 * path) is never stored, while a package beside it is;</li>
 * <li>{@code packagemodecollectingporttakesonlypackages}: a collecting port fetches the package out of the machine's
 * chest and leaves the loose items there;</li>
 * <li>{@code packagemodedrainsaproductionorderopenatload}: the drain rule. An order opened before the switch lets its
 * results back in once its machine got ingredients, up to what they make, and not one more;</li>
 * <li>{@code packagemodedrainofanendedorderstopsatitsshortfall}: an order that ended with ingredients at its machine
 * lets exactly its shortfall be stored, whatever comes in through two inputs; the rest is held for a hand;</li>
 * <li>{@code packagemoderestockorderwaitingatloadletsnothingin}: an automatic order still waiting for its ingredients
 * lets nothing loose in.</li>
 * </ul>
 * The switch is set through {@link ConfigOverrides} in batches of their own and restored after each batch; blocks are
 * placed only after it is set, as a world-restart value would have it. The switched-off path is proven by
 * {@link PackageModeGoldenGameTests} and by every other GameTest, which all run with the switch off.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageModeGameTests {
    static final String BATCH = "wareworkspackagemode";
    static final String DRAIN_BATCH = "wareworkspackagemodedrain";
    /**
     * The switch is one global value that these tests flip in the middle (the world "loads" with it on while an order
     * is open), so each runs in a batch of its own: in a shared batch the first flip would refuse the others' orders.
     */
    static final String DRAIN_ENDED_BATCH = "wareworkspackagemodedrainended";
    static final String DRAIN_RESTOCK_BATCH = "wareworkspackagemodedrainrestock";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 2;
    /** Longer than the input's drain re-read interval and long enough for a crane trip to the input and back. */
    private static final int HOLD_TICKS = 160;

    private static final BlockPos INPUT = new BlockPos(3, BASE_Y, 3);
    private static final Direction PACKAGER_SIDE = Direction.EAST;
    private static final BlockPos PACKAGER = INPUT.relative(PACKAGER_SIDE);
    private static final BlockPos BAY = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 5);
    /** Where the Mechanical Arm of the input test stands, beside the input. */
    private static final BlockPos ARM = new BlockPos(1, BASE_Y, 3);

    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition COLLECT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition LOG_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_INPUT_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final int LOOSE = 10;
    private static final int HELD = 8;
    private static final int ORDERED_PLANKS = 8;

    private PackageModeGameTests() {
    }

    @AfterBatch(batch = BATCH)
    public static void restorePackageMode(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = DRAIN_BATCH)
    public static void restorePackageModeAfterDrain(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = DRAIN_ENDED_BATCH)
    public static void restorePackageModeAfterEndedDrain(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = DRAIN_RESTOCK_BATCH)
    public static void restorePackageModeAfterRestockDrain(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    private static void switchOn(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.packageModeEnabled, true);
        helper.assertTrue(WareworksConfig.packageModeEnabled(), "package mode is on for this batch");
    }

    // --- the input ---------------------------------------------------------------------------------------------------

    /**
     * Every outside way into a warehouse input refuses a loose stack and takes a package; the crane's own insert is
     * never gated, the goggles say what was refused and what waits, and an empty hand takes the loose items out.
     */
    @GameTest(template = EMPTY_7X5X7, batch = BATCH)
    public static void packagemodeinputgatesitsentrances(GameTestHelper helper) {
        switchOn(helper);
        placeInput(helper);
        ItemStack box = PackageDoorFixture.packageOf(IRON.toStack(LOOSE));
        Map<ItemKey, Long> expected = ItemCensus.of();

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = inputAt(helper);
                    IItemHandler capability = handlerAt(helper, INPUT);
                    ItemStack loose = IRON.toStack(LOOSE);
                    helper.assertValueEqual(capability.insertItem(0, loose.copy(), true).getCount(), LOOSE,
                            "the capability refuses a loose stack (simulated)");
                    helper.assertValueEqual(capability.insertItem(0, loose.copy(), false).getCount(), LOOSE,
                            "and for real");
                    helper.assertFalse(capability.isItemValid(0, loose), "a loose stack is not valid");
                    helper.assertTrue(capability.isItemValid(0, box), "a package is");
                    DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(input, DirectBeltInputBehaviour.TYPE);
                    helper.assertValueEqual(belt.handleInsertion(loose.copy(), Direction.UP, false).getCount(), LOOSE,
                            "the belt side refuses it too");
                    // A real Mechanical Arm deposit through the input's own arm point (review fix: the old check
                    // compared the capability with the object it is registered from).
                    ArmInteractionPoint point = MechanicalArmFixture.select(helper, INPUT, 0);
                    helper.assertTrue(point instanceof WarehouseInputArmPoint, "the input's own arm point: " + point);
                    ArmBlockEntity arm = MechanicalArmFixture.place(helper, ARM, List.of(point));
                    helper.assertValueEqual(point.insert(arm, loose.copy(), true).getCount(), LOOSE,
                            "a Mechanical Arm's deposit of a loose stack is refused (simulated)");
                    helper.assertValueEqual(point.insert(arm, loose.copy(), false).getCount(), LOOSE,
                            "and for real");
                    helper.assertTrue(point.insert(arm, box.copy(), true).isEmpty(),
                            "a package it would deposit");
                    ItemCensus.assertEquals(helper, expected, "nothing loose came in");

                    helper.assertTrue(capability.insertItem(0, box.copy(), false).isEmpty(), "a package goes in");
                    ItemCensus.change(expected, IRON, LOOSE);
                    helper.assertTrue(input.insert(IRON.toStack(HELD), false).isEmpty(),
                            "the crane's own insert (rerouted leftovers) is never gated");
                    ItemCensus.change(expected, IRON, HELD);
                    ItemCensus.assertEquals(helper, expected, "the package and the crane's leftovers are in");

                    input.onGoggleObserved();
                    PackageUnpackSummary packages = input.summary().packages();
                    helper.assertValueEqual(packages.lastLooseRefused(), "minecraft:iron_ingot",
                            "the goggles name the refused item");
                    helper.assertValueEqual(packages.heldLoose(), (long) HELD,
                            "and the loose items waiting for a hand");

                    List<ItemStack> taken = input.takePackageModeHeld();
                    helper.assertValueEqual(taken.stream().mapToInt(ItemStack::getCount).sum(), HELD,
                            "an empty hand takes the loose items out");
                    helper.assertTrue(taken.stream().noneMatch(PackageModeGateAccess::isPackage),
                            "and only those");
                    ItemCensus.change(expected, IRON, -HELD);
                    ItemCensus.assertEquals(helper, expected, "the package stays for the crane");
                })
                .thenSucceed();
    }

    /**
     * A Packager behind the input would open the box into a package-mode warehouse, so the unpacking handler refuses
     * it in both passes: the box stays whole with the sender, and the goggles say why.
     */
    @GameTest(template = EMPTY_7X5X7, batch = BATCH)
    public static void packagemodeindoorrefusesaboxwhole(GameTestHelper helper) {
        switchOn(helper);
        placeInput(helper);
        helper.setBlock(PACKAGER, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, PACKAGER_SIDE));
        ItemStack box = PackageDoorFixture.packageOf(IRON.toStack(16), DIRT.toStack(4));

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    IItemHandler packager = handlerAt(helper, PACKAGER);
                    ItemStack left = packager.insertItem(0, box.copy(), false);
                    helper.assertTrue(ItemStack.matches(left, box), "the box comes back whole: " + left);
                    ItemCensus.assertEquals(helper, ItemCensus.of(), "nothing was opened into the input");
                    WarehouseInputBlockEntity input = inputAt(helper);
                    input.onGoggleObserved();
                    helper.assertTrue(input.summary().packages().packagerRefused(),
                            "the goggles say the Packager was refused");
                    helper.assertValueEqual(input.summary().packages().opened(), 0L, "no package was opened");
                })
                .thenSucceed();
    }

    // --- the rack bay ------------------------------------------------------------------------------------------------

    /**
     * A rack bay's outer face, its belt side and a player's hand refuse a loose stack and take a package; loose stock
     * still comes out; a pallet of loose goods is refused with its own line, a pallet of packages is not.
     */
    @GameTest(template = EMPTY_7X5X7, batch = BATCH)
    public static void packagemodebayrefusesloose(GameTestHelper helper) {
        switchOn(helper);
        helper.setBlock(BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState().setValue(RackBayBlock.FACING,
                Direction.NORTH));
        ItemStack box = PackageDoorFixture.packageOf(IRON.toStack(5));

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    RackBayBlockEntity bay = bayAt(helper);
                    IItemHandler outer = handlerAt(helper, BAY);
                    helper.assertValueEqual(outer.insertItem(0, DIRT.toStack(LOOSE), false).getCount(), LOOSE,
                            "the outer face refuses loose dirt");
                    helper.assertFalse(outer.isItemValid(0, DIRT.toStack()), "loose dirt is not valid");
                    DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(bay, DirectBeltInputBehaviour.TYPE);
                    helper.assertValueEqual(belt.handleInsertion(DIRT.toStack(LOOSE), Direction.UP, false).getCount(),
                            LOOSE, "and so does its belt side");
                    helper.assertTrue(bay.storedKey().isEmpty(), "the bay stayed empty");

                    // Loose stock from before the switch: only the crane's raw handler can put it there.
                    helper.assertTrue(bay.insert(DIRT.toStack(LOOSE), false).isEmpty(), "the crane's handler is open");
                    helper.assertValueEqual(outer.extractItem(0, 4, false).getCount(), 4,
                            "loose stock still comes out through the outer face");

                    ClickingPlayer player = ClickingPlayer.at(helper, STAND);
                    player.setItemInHand(InteractionHand.MAIN_HAND, DIRT.toStack(3));
                    InteractionResult click = click(helper, player, faceHit(helper, BAY));
                    helper.assertTrue(click.consumesAction(), "the click is consumed: " + click);
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "the hand keeps its dirt");
                    helper.assertValueEqual(bay.storedCount(), LOOSE - 4, "the bay did not take it");
                    assertTold(helper, player, WareworksLang.PACKAGE_MODE_HAND_REFUSED);

                    helper.assertValueEqual(bay.extract(LOOSE, false).getCount(), LOOSE - 4, "emptied for the box");
                    helper.assertTrue(outer.insertItem(0, box.copy(), false).isEmpty(),
                            "an empty bay takes a package through its outer face");
                    helper.assertValueEqual(bay.storedCount(), 1, "one package");
                    helper.assertValueEqual(outer.extractItem(0, 1, false).getCount(), 1, "and gives it out again");

                    player.told.clear();
                    ItemStack loosePallet = palletTruck(DIRT, 32);
                    player.setItemInHand(InteractionHand.MAIN_HAND, loosePallet.copy());
                    helper.assertTrue(click(helper, player, topHit(helper, BAY)) == InteractionResult.FAIL,
                            "a pallet of loose dirt is refused");
                    helper.assertTrue(ItemStack.matches(player.getMainHandItem(), loosePallet),
                            "the truck keeps its pallet");
                    assertTold(helper, player, WareworksLang.HAND_TRUCK_BAY_PACKAGE_MODE);
                    helper.assertTrue(bay.storedKey().isEmpty(), "the bay stayed empty");

                    player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(ItemKey.of(box), 1));
                    helper.assertTrue(click(helper, player, topHit(helper, BAY)).consumesAction(),
                            "a pallet of one package goes in");
                    helper.assertValueEqual(bay.storedCount(), 1, "the package is in the bay");
                })
                .thenSucceed();
    }

    // --- the crane's backstop and the collecting port ----------------------------------------------------------------

    /**
     * Whatever loose reaches an input anyway (here the crane's own reroute path, which is never gated) is never stored:
     * the controller's backstop keeps it in the input for a hand, while a package beside it is stored as usual.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodebackstopkeepslooseintheinput(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        ItemStack box = PackageDoorFixture.packageOf(DIRT.toStack(LOOSE));
        ItemKey boxKey = ItemKey.of(box);
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, HELD, DIRT, LOOSE);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = aisle.inputAt(INPUT_RACK);
                    helper.assertTrue(input.insert(IRON.toStack(HELD), false).isEmpty(), "loose iron waits");
                    helper.assertTrue(net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(
                            aisle.handlerAt(aisle.rackPos(INPUT_RACK)), box.copy(), false).isEmpty(),
                            "a package beside it");
                    helper.assertTrue(aisle.controller().storeHeld(INPUT_RACK, IRON), "the backstop holds iron");
                    helper.assertFalse(aisle.controller().storeHeld(INPUT_RACK, boxKey), "but not a package");
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the crane works"));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, boxKey), 1L,
                        "the package is stored"))
                .thenIdle(HOLD_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L, "no loose iron was stored");
                    WarehouseInputBlockEntity input = aisle.inputAt(INPUT_RACK);
                    helper.assertValueEqual(input.countOf(IRON), (long) HELD, "it all waits in the input");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** A collecting port fetches the package out of its machine's chest and leaves the loose items in it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodecollectingporttakesonlypackages(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        ItemStack box = PackageDoorFixture.packageOf(DIRT.toStack(LOOSE));
        ItemKey boxKey = ItemKey.of(box);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(LOOSE), box.copy());
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, LOOSE, DIRT, LOOSE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while the port collects"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.outputAt(COLLECT_RACK).setPortRank(PortSettings.COLLECT_RANK),
                            "the port collects");
                    aisle.outputAt(COLLECT_RACK).setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, boxKey), 1L,
                        "the package was collected and stored"))
                .thenIdle(HOLD_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(machine, IRON), (long) LOOSE,
                            "the loose iron stays in the machine's chest");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L, "none of it was stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- the drain rule ---------------------------------------------------------------------------------------------

    /**
     * The drain rule (owner decision E2, review fix 2026-11-22): a production order opened <b>before</b> the switch was
     * on (a world loaded with the mode on) lets loose results in only once ingredients reached its machine, and then as
     * many as those ingredients make. While it still waits for its ingredients nothing loose is let in; once the crane
     * delivered them its results come back through the input, are stored and complete it, and one more is refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = DRAIN_BATCH)
    public static void packagemodedrainsaproductionorderopenatload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOG_RACK, LOG.toStack(16));
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "ingredient set");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4), "result set");
        AtomicReference<ProductionOrder<ItemKey, RackPosition>> order = new AtomicReference<>();
        IItemHandler[] input = new IItemHandler[1];

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), 16L, "logs in stock");
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station");
                })
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            ORDERED_PLANKS);
                    helper.assertValueEqual(result.producing(), ORDERED_PLANKS, "an order for all of it");
                    List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
                    helper.assertValueEqual(orders.size(), 1, "one production order");
                    order.set(orders.getFirst());
                    // The world "loads" with package mode on while the order is open and its crane stands still.
                    switchOn(helper);
                    input[0] = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(aisle.controller().packageModeDrainAllowance().isEmpty(),
                            "nothing reached its machine yet, so nothing can come back");
                    helper.assertValueEqual(input[0].insertItem(0, PLANK.toStack(ORDERED_PLANKS), false).getCount(),
                            ORDERED_PLANKS, "its results are refused while it waits for its ingredients");
                    helper.assertTrue(aisle.controller().storeHeld(INPUT_RACK, PLANK), "and would be held");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) (ORDERED_PLANKS / 4), "the crane supplies the open order's machine"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().packageModeDrainAllowance(),
                            Map.of(PLANK, (long) ORDERED_PLANKS), "the two logs make eight planks");
                    ItemStack rest = input[0].insertItem(0, PLANK.toStack(ORDERED_PLANKS + 2), false);
                    helper.assertValueEqual(rest.getCount(), 2, "its results come back, as many as it waits for");
                    helper.assertValueEqual(input[0].insertItem(1, PLANK.toStack(1), false).getCount(), 1,
                            "and not one more");
                    helper.assertValueEqual(input[0].insertItem(1, IRON.toStack(LOOSE), false).getCount(), LOOSE,
                            "another loose item is refused");
                    helper.assertFalse(aisle.controller().storeHeld(INPUT_RACK, PLANK),
                            "the crane may store the results");
                    helper.assertTrue(aisle.controller().storeHeld(INPUT_RACK, IRON), "anything else it holds");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().productionOrder(order.get().id())
                        .map(ProductionOrder::state).orElse(null), ProductionOrderState.COMPLETE,
                        "the stored results complete the order"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().packageModeDrainAllowance().isEmpty(),
                            "a completed order lets nothing in");
                    helper.assertValueEqual(input[0].insertItem(0, PLANK.toStack(1), false).getCount(), 1,
                            "one more plank is refused");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation MAJOR 1, minor 3; inert-api 4): an order that <b>ended</b> with ingredients at its machine
     * is no longer credited, and still lets in no more than those ingredients make. Fed through two inputs at once,
     * more than that shortfall comes in, and exactly the shortfall is stored: the rest waits in its input, held for a
     * hand, and is never stored. Afterwards nothing more of it is let in.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = DRAIN_ENDED_BATCH)
    public static void packagemodedrainofanendedorderstopsatitsshortfall(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(LOG_RACK, LOG.toStack(16));
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.input(SECOND_INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "ingredient set");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4), "result set");
        AtomicReference<ProductionOrder<ItemKey, RackPosition>> order = new AtomicReference<>();
        Map<ItemKey, Long> expected = ItemCensus.of(LOG, 16);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "while the ended order drains");
            helper.assertTrue(planksOutOfTheInputs(aisle) <= ORDERED_PLANKS,
                    "never more planks stored than the delivered logs make");
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), 16L, "logs in stock");
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station");
                })
                .thenExecute(() -> {
                    RequestResult result = aisle.controller().request(aisle.absoluteRackPos(OUTPUT_RACK), PLANK,
                            ORDERED_PLANKS);
                    helper.assertValueEqual(result.producing(), ORDERED_PLANKS, "an order for all of it");
                    order.set(aisle.controller().productionOrders().getFirst());
                    switchOn(helper);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, LOG),
                        (long) (ORDERED_PLANKS / 4), "the crane supplies the machine"))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().cancelProductionOrder(order.get().id()).isPresent(),
                            "the order is given up with its logs at the machine");
                    helper.assertValueEqual(aisle.controller().packageModeDrainAllowance(),
                            Map.of(PLANK, (long) ORDERED_PLANKS), "its machine may still give eight planks back");
                    // Both inputs take the shortfall before the crane comes: the allowance is per input.
                    for (RackPosition rack : List.of(INPUT_RACK, SECOND_INPUT_RACK)) {
                        ItemStack rest = aisle.handlerAt(aisle.rackPos(rack)).insertItem(0,
                                PLANK.toStack(ORDERED_PLANKS), false);
                        helper.assertTrue(rest.isEmpty(), "an input takes the shortfall: " + rest);
                        ItemCensus.change(expected, PLANK, ORDERED_PLANKS);
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(planksOutOfTheInputs(aisle), (long) ORDERED_PLANKS,
                        "the shortfall is stored"))
                .thenIdle(HOLD_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(planksOutOfTheInputs(aisle), (long) ORDERED_PLANKS,
                            "and not one plank more");
                    helper.assertTrue(aisle.controller().packageModeDrainAllowance().isEmpty(),
                            "the ended order lets nothing in any more");
                    helper.assertValueEqual(aisle.handlerAt(aisle.rackPos(INPUT_RACK)).insertItem(0,
                            PLANK.toStack(1), false).getCount(), 1, "one more plank is refused");
                    helper.assertTrue(aisle.controller().storeHeld(INPUT_RACK, PLANK), "the rest is held");
                    long taken = 0L;
                    for (RackPosition rack : List.of(INPUT_RACK, SECOND_INPUT_RACK))
                        taken += aisle.inputAt(rack).takePackageModeHeld().stream().mapToInt(ItemStack::getCount)
                                .sum();
                    helper.assertValueEqual(taken, (long) ORDERED_PLANKS, "an empty hand takes the rest out");
                    ItemCensus.change(expected, PLANK, -ORDERED_PLANKS);
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation MAJOR 1): an automatic restock order that is open at load and still waits for its
     * ingredients has handed nothing to a machine, so it lets nothing loose in at all, however long it waits.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = DRAIN_RESTOCK_BATCH)
    public static void packagemoderestockorderwaitingatloadletsnothingin(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(LOG_RACK, LOG.toStack(16));
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "ingredient set");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4), "result set");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(LOG), 16L, "logs in stock");
                    helper.assertValueEqual(aisle.controller().productionStations().size(), 1, "the station");
                })
                .thenExecute(() -> {
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, PLANK, 0L);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MINIMUM, null,
                            (long) ORDERED_PLANKS);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().productionOrders().stream()
                        .anyMatch(ProductionOrder::isRestock), "the keeper's minimum orders planks by itself"))
                .thenExecute(() -> {
                    // The world "loads" with package mode on while the restock order waits for its crane.
                    switchOn(helper);
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(aisle.controller().packageModeDrainAllowance().isEmpty(),
                            "an order that handed nothing over lets nothing in");
                    helper.assertValueEqual(input.insertItem(0, PLANK.toStack(LOOSE), false).getCount(), LOOSE,
                            "loose planks are refused");
                    helper.assertTrue(aisle.controller().storeHeld(INPUT_RACK, PLANK), "and would be held");
                    helper.assertValueEqual(aisle.controller().storeHeadroom(PLANK), 0L,
                            "the planner would store none");
                })
                .thenIdle(HOLD_TICKS)
                .thenExecute(() -> helper.assertValueEqual(aisle.handlerAt(aisle.rackPos(INPUT_RACK))
                        .insertItem(0, PLANK.toStack(LOOSE), false).getCount(), LOOSE, "however long it waits"))
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /**
     * Planks the crane took out of the inputs: stored, in its head, or served on to the output (the request behind the
     * order may still be served from what was stored).
     */
    private static long planksOutOfTheInputs(AisleFixture aisle) {
        return aisle.storedAt(STORAGE_RACK, PLANK) + aisle.stationCount(OUTPUT_RACK, PLANK)
                + aisle.dock().heldItems().count(PLANK);
    }

    /** The test's own view of Create's package test, so the assertions read like the rule. */
    private static final class PackageModeGateAccess {
        static boolean isPackage(ItemStack stack) {
            return dev.wareworks.content.packagemode.PackageModeGate.isPackage(stack);
        }
    }

    private static void placeInput(GameTestHelper helper) {
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, Direction.NORTH));
    }

    private static WarehouseInputBlockEntity inputAt(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(INPUT)) instanceof WarehouseInputBlockEntity input)) {
            helper.fail("missing warehouse input block entity", INPUT);
            throw new IllegalStateException("unreachable");
        }
        return input;
    }

    private static RackBayBlockEntity bayAt(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BAY)) instanceof RackBayBlockEntity bay)) {
            helper.fail("missing rack bay block entity", BAY);
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            throw new IllegalStateException("unreachable");
        }
        return handler;
    }

    private static ItemStack palletTruck(ItemKey key, int count) {
        ItemStack truck = new ItemStack(WareworksItems.HAND_TRUCK.get());
        truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(), new HandTruckPallet(key, count));
        return truck;
    }

    private static BlockHitResult topHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return new BlockHitResult(new Vec3(absolute.getX() + 0.5, absolute.getY() + 1.0, absolute.getZ() + 0.5),
                Direction.UP, absolute, false);
    }

    /**
     * A hit on the bay's back (north) face: a bay facing north has its aisle face, and with it the store filter slot,
     * on the south, and a click there would set the filter instead.
     */
    private static BlockHitResult faceHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return new BlockHitResult(new Vec3(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ()),
                Direction.NORTH, absolute, false);
    }

    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockHitResult hit) {
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
    }

    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String relativeKey) {
        helper.assertTrue(player.toldKeys().contains(WareworksLang.key(relativeKey)),
                "the player was not told " + relativeKey + ": " + player.toldKeys());
    }
}
