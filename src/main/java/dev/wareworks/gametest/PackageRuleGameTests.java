package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.RuleUnit;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleEvaluation;
import dev.wareworks.core.stock.StockRuleStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of package mode's stock rules (issue #40, slice S5, design §7): a stock keeper row whose ghost is a Create
 * Package Filter counts the sealed packages its address matches (Create's wildcards included) and acts on them while the
 * world's {@code [packageMode] enabled} switch is on.
 * <ul>
 * <li>{@code packagerulemaximumbacksupinput}: a maximum over an address group is held at the input's entrance with what
 * is stored, carried and waiting counted, so a box over the cap stays with its sender and the goggles name its address;
 * another address is unaffected; when a box leaves, the next one is let in. The group never exceeds its maximum on any
 * tick, and a census holds on every tick;</li>
 * <li>{@code packageruleminimumsignalsonly}: the minimum lights the keeper's lamp and drives its comparator over the
 * whole group (a glob, a box addressed {@code *}), and nothing is ever made or ordered for it;</li>
 * <li>{@code packagerulereserveholdsbackautomation}: the reserve holds boxes of the group back from a redstone request,
 * never from a player;</li>
 * <li>{@code packageruleinertwhileoff} (switch off): a Package Filter writes an item row as before, and a package row
 * loaded from a save is kept (its unit survives a save) but does nothing at all.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageRuleGameTests {
    static final String BATCH = "wareworkspackagerules";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1600;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition KEEPER_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition CHEST_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);

    private static final String BASE_GLOB = "Base*";
    private static final String BASE_NORTH = "Base North";
    private static final String BASE_SOUTH = "Base South";
    private static final String OUTPOST = "Outpost";
    private static final int PER_BOX = 4;
    private static final long MAXIMUM = 2L;
    private static final long MINIMUM = 3L;

    private PackageRuleGameTests() {
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAfterRules(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    private static void switchOn(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.packageModeEnabled, true);
        helper.assertTrue(WareworksConfig.packageModeEnabled(), "package mode is on for this batch");
    }

    /** A sealed box of {@value #PER_BOX} {@code contents}, addressed {@code address}: each content a distinct key. */
    private static ItemStack box(net.minecraft.world.item.Item contents, String address) {
        ItemStack box = PackageDoorFixture.packageOf(new ItemStack(contents, PER_BOX));
        PackageItem.addAddress(box, address);
        return box;
    }

    private static ItemKey packageFilter(String address) {
        ItemStack filter = AllItems.PACKAGE_FILTER.asStack();
        if (!address.isEmpty())
            PackageItem.addAddress(filter, address);
        return ItemKey.of(filter);
    }

    private static StockRuleEvaluation<ItemKey> firstRow(AisleFixture aisle) {
        List<StockRuleEvaluation<ItemKey>> views = aisle.controller().stockRuleViewsAt(
                aisle.absoluteRackPos(KEEPER_RACK));
        if (views.isEmpty())
            throw new net.minecraft.gametest.framework.GameTestAssertException("the keeper has no evaluated row yet");
        return views.getFirst();
    }

    /**
     * Boxes of {@code keys} physically inside the warehouse: in the chest, in the input's buffer and in the crane's head
     * (never the index, which may lag a transfer by a tick). A box in the port is on its way out.
     */
    private static long boxesInside(AisleFixture aisle, List<ItemKey> keys) {
        long total = 0L;
        for (ItemKey key : keys)
            total += aisle.storedAt(CHEST_RACK, key) + aisle.stationCount(INPUT_RACK, key)
                    + aisle.dock().heldItems().count(key);
        return total;
    }

    // --- maximum ------------------------------------------------------------------------------------------------

    /**
     * Design critique M5 and m4: the maximum of "Base*" is held at the input's entrance, over the whole group and with
     * stored, carried and waiting boxes counted, so the third box waits outside (the goggles say so) while a box for
     * another address still goes in. When a box leaves through a port, the waiting box is let in. On every tick the
     * group never holds more than its maximum, and the census holds.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagerulemaximumbacksupinput(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        ItemStack north = box(Items.IRON_INGOT, BASE_NORTH);
        ItemStack south = box(Items.GOLD_INGOT, BASE_SOUTH);
        ItemStack third = box(Items.COPPER_INGOT, BASE_NORTH);
        ItemStack outpost = box(Items.DIAMOND, OUTPOST);
        List<ItemKey> group = List.of(ItemKey.of(north), ItemKey.of(south), ItemKey.of(third));
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "while boxes come in and go out");
            helper.assertTrue(boxesInside(aisle, group) <= MAXIMUM,
                    "the Base* group never holds more than its maximum");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    helper.assertFalse(keeper.editRule(0, StockKeeperRules.FIELD_ITEM, packageFilter(""), 0L)
                            .changed(), "a Package Filter without an address is refused");
                    helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_ITEM, packageFilter(BASE_GLOB), 0L)
                            .changed(), "a Package Filter with an address is taken");
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.PACKAGES, "as a package row");
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, MAXIMUM);

                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(input, north.copy());
                    aisle.insertAll(input, south.copy());
                    ItemCensus.change(expected, ItemKey.of(north), 1);
                    ItemCensus.change(expected, ItemKey.of(south), 1);
                    helper.assertValueEqual(aisle.controller().packageAdmitRoom(ItemKey.of(third)), 0L,
                            "two boxes of Base* wait in the input: no room for a third");
                    helper.assertFalse(input.isItemValid(0, third), "so the third is not valid");
                    helper.assertTrue(ItemStack.matches(input.insertItem(0, third.copy(), false), third),
                            "and stays with its sender");
                    WarehouseInputBlockEntity station = aisle.inputAt(INPUT_RACK);
                    station.onGoggleObserved();
                    PackageUnpackSummary packages = station.summary().packages();
                    helper.assertValueEqual(packages.boxAtMaximum(), BASE_NORTH, "the goggles name its address");
                    aisle.insertAll(input, outpost.copy());
                    ItemCensus.change(expected, ItemKey.of(outpost), 1);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(ItemKey.of(north)), 1L, "Base North is stored");
                    helper.assertValueEqual(controller.countOf(ItemKey.of(south)), 1L, "Base South is stored");
                    helper.assertValueEqual(controller.countOf(ItemKey.of(outpost)), 1L, "the Outpost box too");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    StockRuleEvaluation<ItemKey> row = firstRow(aisle);
                    helper.assertValueEqual(row.status(), StockRuleStatus.AT_MAXIMUM, "the row is at its maximum");
                    helper.assertValueEqual(row.levels().stocked(), MAXIMUM, "counting both addresses of Base*");
                    helper.assertValueEqual(controller.storeHeadroom(ItemKey.of(third)), 0L,
                            "the planner would store no third box");
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(ItemStack.matches(input.insertItem(0, third.copy(), false), third),
                            "the third box is still refused");
                    RequestResult out = controller.request(aisle.absoluteRackPos(OUTPUT_RACK), ItemKey.of(north), 1);
                    helper.assertTrue(out.isAccepted(), "a player's request takes Base North out: " + out);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, ItemKey.of(north)), 1L,
                        "the box is in the port"))
                .thenExecute(() -> {
                    IItemHandler port = aisle.handlerAt(aisle.rackPos(OUTPUT_RACK));
                    ItemStack left = ItemStack.EMPTY;
                    for (int slot = 0; slot < port.getSlots() && left.isEmpty(); slot++)
                        left = port.extractItem(slot, 1, false);
                    helper.assertTrue(ItemStack.matches(left, north), "a funnel takes it out: " + left);
                    ItemCensus.change(expected, ItemKey.of(north), -1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().packageAdmitRoom(ItemKey.of(third)),
                        1L, "with one box gone the group has room for one"))
                .thenExecute(() -> {
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(input.insertItem(0, third.copy(), false).isEmpty(), "the third box goes in");
                    ItemCensus.change(expected, ItemKey.of(third), 1);
                    WarehouseInputBlockEntity station = aisle.inputAt(INPUT_RACK);
                    station.onGoggleObserved();
                    helper.assertTrue(station.summary().packages().boxAtMaximum() == null,
                            "the goggles no longer name a refused box: " + station.summary().packages().boxAtMaximum());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(ItemKey.of(third)), 1L,
                        "and is stored"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Review fix (conservation minor 6): a box the warehouse may not store under a maximum that reached an input anyway
     * (the crane's ungated insert of a reroute, or a maximum lowered under it) is not stuck there for ever: the goggles
     * count it, and an empty hand takes it out, and only it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagerulecappedboxinaninputcomesoutbyhand(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        ItemStack north = box(Items.IRON_INGOT, BASE_NORTH);
        ItemStack capped = box(Items.COPPER_INGOT, BASE_SOUTH);
        ItemStack outpost = box(Items.DIAMOND, OUTPOST);
        aisle.storage(CHEST_RACK, north.copy(), box(Items.GOLD_INGOT, BASE_NORTH));
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_ITEM, packageFilter(BASE_GLOB), 0L)
                            .changed(), "a Package Filter with an address is taken");
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, MAXIMUM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().storeHeadroom(ItemKey.of(capped)), 0L,
                        "two Base* boxes are stored: the maximum leaves no room"))
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = aisle.inputAt(INPUT_RACK);
                    helper.assertTrue(input.insert(capped.copy(), false).isEmpty(),
                            "a reroute's ungated insert puts the capped box into the input");
                    helper.assertTrue(input.insert(outpost.copy(), false).isEmpty(), "beside a box that may be stored");
                    input.onGoggleObserved();
                    helper.assertValueEqual(input.summary().packages().heldBoxes(), 1L,
                            "the goggles count the one box held over the maximum");
                    List<ItemStack> taken = input.takePackageModeHeld();
                    helper.assertValueEqual(taken.size(), 1, "an empty hand takes one stack: " + taken);
                    helper.assertTrue(ItemStack.matches(taken.getFirst(), capped), "the capped box, sealed");
                    helper.assertValueEqual(input.countOf(ItemKey.of(outpost)), 1L, "the other box stays for the crane");
                    helper.assertValueEqual(input.countOf(ItemKey.of(capped)), 0L, "and the input is free of it");
                })
                .thenSucceed();
    }

    // --- minimum ------------------------------------------------------------------------------------------------

    /**
     * The minimum only signals: below it the keeper's lamp burns and its comparator calls, over the whole group the
     * glob matches (a box addressed {@code *} counts, a box for another address does not), and nothing is ordered or
     * made for it. At the minimum both go quiet.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packageruleminimumsignalsonly(GameTestHelper helper) {
        switchOn(helper);
        ItemStack north = box(Items.IRON_INGOT, BASE_NORTH);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        BlockPos chest = aisle.storage(CHEST_RACK, north.copy(), box(Items.DIAMOND, OUTPOST));
        aisle.stockKeeper(KEEPER_RACK);
        ItemKey filter = packageFilter(BASE_GLOB);
        helper.onEachTick(() -> aisle.controllerIfPresent().ifPresent(controller -> helper.assertTrue(
                controller.productionOrders().isEmpty(), "nothing is ever made for a package rule")));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, filter, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, MINIMUM);
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.PACKAGES, "a package row");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "the lamp burns below the minimum");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 1,
                            "and the comparator calls for packages");
                    StockRuleEvaluation<ItemKey> row = firstRow(aisle);
                    helper.assertValueEqual(row.status(), StockRuleStatus.BELOW_MINIMUM, "status");
                    helper.assertValueEqual(row.levels().stocked(), 1L, "one Base North box, the Outpost box not");
                    helper.assertValueEqual(row.restock(), RestockOutcome.NOT_GOVERNING, "restocking has no say");
                })
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(chest), box(Items.GOLD_INGOT, BASE_SOUTH));
                    aisle.insertAll(aisle.handlerAt(chest), box(Items.EMERALD, "*"));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(firstRow(aisle).levels().stocked(), MINIMUM,
                            "Base South and a box addressed * count towards Base*");
                    helper.assertValueEqual(firstRow(aisle).status(), StockRuleStatus.SATISFIED, "the minimum is met");
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "the lamp goes out");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 0,
                            "and the comparator goes quiet");
                })
                .thenSucceed();
    }

    // --- reserve ------------------------------------------------------------------------------------------------

    /**
     * The reserve holds the last box of the group back from automation (a redstone request) and never from a player.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagerulereserveholdsbackautomation(GameTestHelper helper) {
        switchOn(helper);
        ItemStack north = box(Items.IRON_INGOT, BASE_NORTH);
        ItemStack south = box(Items.GOLD_INGOT, BASE_SOUTH);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(CHEST_RACK, north.copy(), south.copy());
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, packageFilter(BASE_GLOB), 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 1L);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    BlockPos port = aisle.absoluteRackPos(OUTPUT_RACK);
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, ItemKey.of(north)), 1L,
                            "two boxes of Base*, one reserved: automation may have one");
                    RequestResult first = controller.request(port, ItemKey.of(north), 1, 64, StockAccess.AUTOMATION);
                    helper.assertTrue(first.isAccepted(), "the first redstone request is served: " + first);
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, ItemKey.of(south)), 0L,
                            "the other box is the reserve");
                    RequestResult second = controller.request(port, ItemKey.of(south), 1, 64, StockAccess.AUTOMATION);
                    helper.assertValueEqual(second.rejection().orElse(null), RequestRejection.RESERVED,
                            "a second redstone request is held back by the reserve");
                    helper.assertValueEqual(controller.availableTo(StockAccess.PLAYER, ItemKey.of(south)), 1L,
                            "a player is never held back");
                    RequestResult player = controller.request(port, ItemKey.of(south), 1, 64, StockAccess.PLAYER);
                    helper.assertTrue(player.isAccepted(), "a player's request is served: " + player);
                })
                .thenSucceed();
    }

    // --- switch off -----------------------------------------------------------------------------------------------

    /** One saved keeper row of unit {@code packages}, in the form {@code StockKeeperRules#save} writes. */
    private static CompoundTag packageRow(GameTestHelper helper, int index, ItemKey filter, long min, long max,
            long reserve) {
        CompoundTag row = new CompoundTag();
        row.putInt("Row", index);
        row.put("Item", filter.save(helper.getLevel().registryAccess()));
        row.putLong("Min", min);
        row.putLong("Max", max);
        row.putLong("Reserve", reserve);
        row.putString("Unit", RuleUnit.PACKAGES_NAME);
        return row;
    }

    /**
     * Off means unchanged: a Package Filter writes an item row, exactly as before package mode. Package rows from a save
     * (written while the switch was on) are kept and saved back as such, but do nothing: no status, no comparator, no cap
     * at the planner or the entrance, no reserve.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageruleinertwhileoff(GameTestHelper helper) {
        helper.assertFalse(WareworksConfig.packageModeEnabled(), "package mode is off in the default batch");
        ItemStack north = box(Items.IRON_INGOT, BASE_NORTH);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(CHEST_RACK, north.copy());
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        ItemKey filter = packageFilter(BASE_GLOB);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, filter, 0L);
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.ITEMS,
                            "with the switch off a Package Filter is an item row, as before");
                    helper.assertTrue(keeper.editRule(1, StockKeeperRules.FIELD_ITEM, packageFilter(""), 0L).changed(),
                            "and a blank one is taken like any item");

                    // Two package rows as a save written while the switch was on holds them: a minimum and a reserve,
                    // and a maximum of 0.
                    ListTag rows = new ListTag();
                    rows.add(packageRow(helper, 0, filter, MINIMUM, StockRule.UNSET, 1L));
                    rows.add(packageRow(helper, 1, filter, StockRule.UNSET, 0L, StockRule.UNSET));
                    keeper.rules().load(rows, helper.getLevel().registryAccess());
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.PACKAGES, "the unit is read back");
                    helper.assertValueEqual(keeper.rules().unitAt(1), RuleUnit.PACKAGES, "for both rows");
                    helper.assertValueEqual(keeper.rules().save(helper.getLevel().registryAccess()).getCompound(0)
                            .getString("Unit"), RuleUnit.PACKAGES_NAME, "and saved again");
                    // Any edit hands the keeper's rows to the controller.
                    keeper.editRule(2, StockKeeperRules.FIELD_ITEM, ItemKey.of(Items.DIRT), 0L);
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    List<StockRuleEvaluation<ItemKey>> views = controller.stockRuleViewsAt(
                            aisle.absoluteRackPos(KEEPER_RACK));
                    helper.assertValueEqual(views.size(), 3, "the controller holds both package rows and the dirt row");
                    helper.assertTrue(views.get(0).rule().isEmpty() && views.get(0).key().equals(filter),
                            "while off, a package row is judged as an empty placeholder: " + views.get(0).rule());
                    for (int index = 0; index < 2; index++)
                        helper.assertValueEqual(views.get(index).status(), StockRuleStatus.NO_LIMITS,
                                "a package row does nothing with the switch off");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 0,
                            "no comparator signal");
                    helper.assertTrue(controller.saveWithFullMetadata(helper.getLevel().registryAccess()).toString()
                            .contains("Unit:\"" + RuleUnit.PACKAGES_NAME + "\""),
                            "the controller's copy saves the unit");
                    ItemStack another = box(Items.GOLD_INGOT, BASE_NORTH);
                    helper.assertValueEqual(controller.storeHeadroom(ItemKey.of(another)), Long.MAX_VALUE,
                            "a maximum of 0 caps nothing while the switch is off");
                    helper.assertValueEqual(controller.packageAdmitRoom(ItemKey.of(another)), Long.MAX_VALUE,
                            "nor at the entrance");
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(input.insertItem(0, another.copy(), true).isEmpty(), "the box would go in");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, ItemKey.of(north)), 1L,
                            "and a reserve of 1 holds nothing back");
                })
                .thenSucceed();
    }
}
