package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.RecipeSelectorBlock;
import dev.wareworks.content.selector.RecipeSelectorBlockEntity;
import dev.wareworks.content.selector.RecipeSelectorBlockItem;
import dev.wareworks.content.selector.RecipeSelectorStatus;
import dev.wareworks.content.selector.SelectorLink;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the Recipe Selector block itself (ADR-085, design §15.2, the local tier): the item's link gestures and
 * placement rules, the return-input binding, the arm step on a real Create Mechanical Saw, Mixer and Press, the
 * set-back of a hand change, the filter-item rule, the wrench, two writers and the set-back cap, the pattern check, and
 * the owner's case: one saw making Deepslate Tile Stairs, Slabs and Walls back to back.
 * <p>
 * The scheduling itself is tested with a stand-in machine in {@link RecipeSelectorGameTests}. Here the machines are
 * real; the tests only <b>play the belt</b> where a player would have one: they move one ingredient at a time from the
 * station into the saw ({@link SawLine#feedSaw}) or put the ingredients into a basin. The saw puts its product straight
 * into the station's return input (the input's belt behaviour), and the crane does the rest.
 * <p>
 * Layout of the saw line ({@code aisle_16x10x7}, the {@link AisleFixture} aisle at z = 3): the production station at
 * 0 right, a second station at 1 right, the return input at 2 right with the up-facing saw behind it (its items move
 * into the input), a creative motor behind 3 right driving the saw, a second Warehouse Input at 3 right and the
 * output at 4 right; cobbled deepslate at 1 left and empty storage at 2 to 4 left. The selector goes on the saw's far
 * side (z = 6).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RecipeSelectorBlockGameTests {
    /** The tests that lower the selector range run in their own batch ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksSelectorBlockConfig";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int CRANE_RPM = 128;
    private static final int MACHINE_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int LONG_TIMEOUT_TICKS = 6000;

    private static final RackPosition STATION = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_STATION = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition RETURN = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OTHER_INPUT = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition COBBLED_STORAGE = new RackPosition(1, 0, Side.LEFT);
    private static final List<RackPosition> PRODUCT_STORAGE = List.of(new RackPosition(2, 0, Side.LEFT),
            new RackPosition(3, 0, Side.LEFT), new RackPosition(4, 0, Side.LEFT));

    private RecipeSelectorBlockGameTests() {
    }

    // --- the machines without a selector ---------------------------------------------------------------------------

    /**
     * Control (design test 1): a bare up-facing saw cycles through cobbled deepslate's stonecutting results, so three
     * items give three different products. This is why a saw line that makes several products needs a selector.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void sawUnfilteredCyclesControl(GameTestHelper helper) {
        BlockPos saw = new BlockPos(3, BASE_Y, 3);
        placeSaw(helper, saw);
        Set<Item> made = new HashSet<>();
        int[] fed = new int[1];
        helper.onEachTick(() -> {
            AABB around = new AABB(helper.absolutePos(saw)).inflate(3);
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, around)) {
                made.add(entity.getItem().getItem());
                entity.discard();
            }
            SawBlockEntity be = sawAt(helper, saw);
            if (be != null && be.getSpeed() != 0 && be.inventory.isEmpty() && fed[0] < 3) {
                be.inventory.insertItem(0, new ItemStack(Items.COBBLED_DEEPSLATE), false);
                fed[0]++;
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(fed[0], 3, "three items went in");
                    SawBlockEntity be = sawAt(helper, saw);
                    helper.assertTrue(be != null && be.inventory.isEmpty(), "the saw ran empty");
                    helper.assertValueEqual(made.size(), 3, "three different products: " + made);
                })
                .thenSucceed();
    }

    // --- link and placement ----------------------------------------------------------------------------------------

    /**
     * Design test 2: the sneak-click links the item to a station; placement against the saw registers the selector
     * (mode LOCAL, the machine kind read from the saw); every refusal keeps the item and names its reason: no link, a
     * station that is gone, too far, no machine, a horizontal saw, a Mixer (the Basin below is named), the top of the
     * saw, open orders, a Crafter Feeder, a second selector on the same machine and a second one on the station. The
     * placed selector saves its link in a full save only, and a sneak-use into the air clears the item.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorLinkAndPlace(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.selectorLocalRange, WareworksConfig.MIN_SELECTOR_LOCAL_RANGE);
        SawLine line = new SawLine(helper, 8, false);
        BlockPos mixerBasin = new BlockPos(7, BASE_Y, 6);
        BlockPos mixer = mixerBasin.above(2);
        BlockPos horizontalSaw = new BlockPos(1, BASE_Y, 6);
        BlockPos farBasin = new BlockPos(14, BASE_Y, 6);
        helper.setBlock(mixerBasin, AllBlocks.BASIN.getDefaultState());
        helper.setBlock(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState());
        helper.setBlock(horizontalSaw, AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        helper.setBlock(farBasin, AllBlocks.BASIN.getDefaultState());
        Clicker clicker = new Clicker(helper, 4);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    // No link yet.
                    clicker.refused(line.sawPos(), Direction.SOUTH, WareworksLang.SELECTOR_NOT_LINKED);
                    // A link to a block that is no station any more.
                    clicker.hand().set(WareworksDataComponents.SELECTOR_LINK.get(), SelectorLink.NONE.withStation(
                            GlobalPos.of(helper.getLevel().dimension(), line.aisle.absoluteRackPos(OUTPUT))));
                    clicker.refused(line.sawPos(), Direction.SOUTH, WareworksLang.SELECTOR_STATION_MISSING);
                    // The link click itself.
                    helper.assertTrue(clicker.click(line.stationPos(), Direction.UP, true).consumesAction(),
                            "the sneak-click on the station is taken");
                    clicker.assertTold(WareworksLang.SELECTOR_LINKED_STATION);
                    helper.assertValueEqual(RecipeSelectorBlockItem.link(clicker.hand()).station().map(GlobalPos::pos),
                            Optional.of(line.aisle.absoluteRackPos(STATION)), "the item names the station");
                    helper.assertValueEqual(line.station().selectorMode(), SelectorMode.NONE,
                            "linking the item changes nothing at the station");
                    // Machines a selector does not serve, and the top of the saw.
                    clicker.refused(line.aisle.controllerPos(), Direction.NORTH, WareworksLang.SELECTOR_NO_MACHINE);
                    clicker.refused(line.sawPos(), Direction.UP, WareworksLang.SELECTOR_NO_MACHINE);
                    clicker.refused(horizontalSaw, Direction.NORTH, WareworksLang.SELECTOR_HORIZONTAL_SAW);
                    clicker.refused(mixer, Direction.WEST, WareworksLang.SELECTOR_PLACE_ON_BASIN);
                    clicker.refused(farBasin, Direction.WEST, WareworksLang.SELECTOR_TOO_FAR);
                    // Open orders.
                    UUID order = line.order(SAW_STAIRS, 1);
                    clicker.refused(line.sawPos(), Direction.SOUTH, WareworksLang.SELECTOR_REFUSED_OPEN_ORDERS);
                    helper.assertTrue(line.controller().cancelProductionOrder(order).isPresent(), "order cancelled");
                    // A Crafter Feeder on the station's back.
                    BlockPos behind = line.aisle.inventoryPos(STATION);
                    helper.setBlock(behind, WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                            .setValue(WarehouseCrafterFeederBlock.FACING, line.aisle.sideDirection(STATION)));
                    clicker.refused(line.sawPos(), Direction.SOUTH, WareworksLang.SELECTOR_REFUSED_FEEDER);
                    helper.setBlock(behind, Blocks.AIR);
                    // Placed.
                    helper.assertTrue(clicker.click(line.sawPos(), Direction.SOUTH, true).consumesAction(),
                            "placed against the saw");
                    clicker.assertTold(WareworksLang.SELECTOR_PLACED);
                    helper.assertValueEqual(clicker.hand().getCount(), 3, "one item was used");
                    RecipeSelectorBlockEntity selector = line.selector();
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(selector.getBlockState().getValue(RecipeSelectorBlock.FACING),
                            Direction.NORTH, "the selector faces the saw");
                    helper.assertValueEqual(selector.linkedStation(), Optional.of(line.aisle.absoluteRackPos(STATION)),
                            "the selector names its station");
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.LOCAL, "the station is in local mode");
                    helper.assertValueEqual(station.linkedSelector(), Optional.of(helper.absolutePos(line.selectorPos())),
                            "and names the selector");
                    helper.assertValueEqual(station.selectorMachineKind(), Optional.of(SelectorMachine.Kind.SAW),
                            "the machine kind is the saw's");
                    // One writer per filter (I8), one selector per station.
                    clicker.refused(line.sawPos(), Direction.WEST, WareworksLang.SELECTOR_REFUSED_MACHINE_TAKEN);
                    clicker.refused(mixerBasin, Direction.WEST, WareworksLang.SELECTOR_REFUSED_SELECTOR_PRESENT);
                    // A full save carries the link; a schematic print does not.
                    CompoundTag full = selector.saveWithFullMetadata(helper.getLevel().registryAccess());
                    helper.assertTrue(full.getCompound(RecipeSelectorBlockEntity.STATE_TAG).contains("Station"),
                            "a full save keeps the station link");
                    CompoundTag print = new CompoundTag();
                    selector.writeSafe(print, helper.getLevel().registryAccess());
                    helper.assertFalse(print.contains(RecipeSelectorBlockEntity.STATE_TAG),
                            "a schematic print carries no link");
                    // A sneak-use into the air clears the item.
                    clicker.player.setXRot(-90.0F);
                    clicker.player.setShiftKeyDown(true);
                    clicker.hand().use(helper.getLevel(), clicker.player, InteractionHand.MAIN_HAND);
                    clicker.player.setShiftKeyDown(false);
                    clicker.assertTold(WareworksLang.SELECTOR_CLEARED);
                    helper.assertTrue(RecipeSelectorBlockItem.link(clicker.hand()).isEmpty(), "the link is gone");
                })
                .thenSucceed();
    }

    /**
     * Design test 3: a sneak-click on a Warehouse Input binds it as the linked station's return input; refused for an
     * input in no warehouse, for one that already returns for another station, for an unlinked item, and while an order
     * of the station is engaged.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorReturnInputBinding(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, true);
        BlockPos looseInput = new BlockPos(12, BASE_Y, 0);
        helper.setBlock(looseInput, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        Clicker forA = new Clicker(helper, 2);
        Clicker forB = new Clicker(helper, 1);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    forA.refusedClick(line.aisle.rackPos(RETURN), WareworksLang.SELECTOR_NOT_LINKED);
                    forA.click(line.stationPos(), Direction.UP, true);
                    forB.click(line.aisle.rackPos(SECOND_STATION), Direction.UP, true);
                    forA.click(line.aisle.rackPos(RETURN), Direction.UP, true);
                    forA.assertTold(WareworksLang.SELECTOR_BOUND_RETURN_INPUT);
                    helper.assertValueEqual(line.station().selectorReturnInput(),
                            Optional.of(line.aisle.absoluteRackPos(RETURN)), "the return input is bound");
                    forA.refusedClick(looseInput, WareworksLang.SELECTOR_INPUT_NOT_IN_WAREHOUSE);
                    forB.click(line.aisle.rackPos(OTHER_INPUT), Direction.UP, true);
                    forB.assertTold(WareworksLang.SELECTOR_BOUND_RETURN_INPUT);
                    forA.refusedClick(line.aisle.rackPos(OTHER_INPUT), WareworksLang.SELECTOR_REFUSED_INPUT_TAKEN);
                    helper.assertValueEqual(line.station().selectorReturnInput(),
                            Optional.of(line.aisle.absoluteRackPos(RETURN)), "a refusal keeps the old binding");
                    // Engaged: a placed selector, an order, its ingredients in the station.
                    forA.click(line.sawPos(), Direction.SOUTH, true);
                    forA.assertTold(WareworksLang.SELECTOR_PLACED);
                    line.order(SAW_STAIRS, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, COBBLED), 1L,
                        "the ingredient is in the station"))
                .thenExecute(() -> {
                    helper.assertTrue(line.controller().selectorStationEngaged(line.aisle.absoluteRackPos(STATION)),
                            "the order is engaged");
                    forA.refusedClick(line.aisle.rackPos(RETURN), WareworksLang.SELECTOR_REFUSED_ENGAGED);
                })
                .thenSucceed();
    }

    // --- the owner's case ------------------------------------------------------------------------------------------

    /**
     * Design test 4, the owner's case: Deepslate Tile Stairs, Slabs and Walls ordered in one tick from one saw. Every
     * tick: the saw's filter changes only while the saw is empty and no open order of the old result is engaged; the
     * crane is asked only for the selection's ingredients; no two results are engaged; the items are conserved (one
     * cobbled deepslate is one stair, one wall or two slabs); nothing but the three products is ever made; and the
     * selector never reports a refused filter (the pending selection, design §6.1). At the end the output holds exactly
     * the three orders.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void selectorThreeResultsBackToBack(GameTestHelper helper) {
        int stairs = 8;
        int slabs = 8;
        int walls = 8;
        int cobbled = 24;
        SawLine line = new SawLine(helper, cobbled, true);
        Clicker clicker = new Clicker(helper, 1);
        UUID[] ids = new UUID[3];
        Item[] lastFilter = new Item[1];
        int[] switches = new int[1];
        boolean[] running = new boolean[1];
        helper.onEachTick(() -> {
            if (!running[0])
                return;
            line.feedSaw();
            line.assertConserved(cobbled);
            line.assertOneResultInTheLine();
            Item filter = line.sawFilter();
            if (filter != lastFilter[0]) {
                helper.assertTrue(line.saw().inventory.isEmpty(),
                        "the filter changed to " + filter + " while the saw was not empty");
                for (ProductionOrder<ItemKey, RackPosition> order : line.controller().openProductionOrders())
                    helper.assertFalse(order.engaged() && order.result().getItem() == lastFilter[0],
                            "the filter changed while an order of " + lastFilter[0] + " was engaged");
                lastFilter[0] = filter;
                switches[0]++;
            }
            helper.assertFalse(line.selector().status() == RecipeSelectorStatus.FILTER_REFUSED,
                    "the selector never reports a refused filter");
            helper.assertFalse(line.station().selectorArmFault().filter(SelectorWait.FILTER_REFUSED::equals)
                    .isPresent(), "the station never sees a refused filter");
        });
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    clicker.click(line.stationPos(), Direction.UP, true);
                    clicker.click(line.sawPos(), Direction.SOUTH, true);
                    clicker.assertTold(WareworksLang.SELECTOR_PLACED);
                    // The item was used up by the placement; a second one binds the return input.
                    clicker.player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(WareworksBlocks.RECIPE_SELECTOR.asItem()));
                    clicker.click(line.stationPos(), Direction.UP, true);
                    clicker.click(line.aisle.rackPos(RETURN), Direction.UP, true);
                    clicker.assertTold(WareworksLang.SELECTOR_BOUND_RETURN_INPUT);
                    WarehouseProductionBlockEntity station = line.station();
                    for (int slot = 0; slot < 3; slot++)
                        helper.assertValueEqual(station.selectorCheck(slot), Optional.of(SelectorRecipeCheck.Verdict.VALID),
                                "pattern " + slot + " passes the saw's check");
                    helper.assertValueEqual(station.activePatterns().size(), 3, "all three patterns are offered");
                    ids[0] = line.order(SAW_STAIRS, stairs);
                    ids[1] = line.order(SAW_SLAB, slabs);
                    ids[2] = line.order(SAW_WALL, walls);
                    lastFilter[0] = line.sawFilter();
                    running[0] = true;
                })
                .thenWaitUntil(() -> {
                    line.assertState(ids[0], ProductionOrderState.COMPLETE);
                    line.assertState(ids[1], ProductionOrderState.COMPLETE);
                    line.assertState(ids[2], ProductionOrderState.COMPLETE);
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, SAW_STAIRS), (long) stairs, "stairs out");
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, SAW_SLAB), (long) slabs, "slabs out");
                    helper.assertValueEqual(line.aisle.stationCount(OUTPUT, SAW_WALL), (long) walls, "walls out");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(switches[0], 3, "the filter was set three times, once per product");
                    helper.assertValueEqual(line.selector().setBackCount(), 0, "nothing was ever set back");
                })
                .thenSucceed();
    }

    // --- set-back, filter items, removal ---------------------------------------------------------------------------

    /**
     * Design test 10: a hand change of the saw's filter is set back in the same call, counted, and the station's line
     * keeps its product.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorHandChangeSetBack(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, true);
        Clicker clicker = new Clicker(helper, 1);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.place(clicker);
                    RecipeSelectorBlockEntity selector = line.selector();
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.empty(), "armed for stairs");
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_STAIRS, "the saw is set to stairs");
                    clicker.handChange(line.sawPos(), new ItemStack(Items.DEEPSLATE_TILE_SLAB, 3));
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_STAIRS,
                            "the hand change was set back in the same tick");
                    helper.assertValueEqual(selector.setBackCount(), 1, "and counted once");
                    helper.assertValueEqual(selector.status(), RecipeSelectorStatus.SET_BACK, "status");
                })
                .thenSucceed();
    }

    /** Design test 11: a List Filter in the saw is never overwritten, neither by the arm step nor by the set-back. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorFilterItemNeverOverwritten(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, true);
        Clicker clicker = new Clicker(helper, 1);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.place(clicker);
                    RecipeSelectorBlockEntity selector = line.selector();
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.empty(), "armed for stairs");
                    clicker.handChange(line.sawPos(), AllItems.FILTER.asStack());
                    helper.assertTrue(line.sawFilterStack().getItem() instanceof FilterItem,
                            "the List Filter went in and stays");
                    helper.assertValueEqual(selector.status(), RecipeSelectorStatus.FILTER_ITEM, "the selector says so");
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.of(SelectorWait.FILTER_ITEM),
                            "the arm step refuses to overwrite it");
                    helper.assertTrue(line.sawFilterStack().getItem() instanceof FilterItem, "still the List Filter");
                    helper.assertValueEqual(selector.setBackCount(), 0, "nothing was set back");
                })
                .thenSucceed();
    }

    /**
     * Design test 12, the selector block's part: a broken selector leaves the station in local mode without a link
     * and the filter as it was (never restored); a new selector registers although the station is in local mode, and
     * after the station switched its selector off, the selector has forgotten its selection and sets nothing back.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorBrokenAndSwitchedOff(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, true);
        Clicker clicker = new Clicker(helper, 2);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.place(clicker);
                    helper.assertValueEqual(line.selector().armFor(SAW_STAIRS, true), Optional.empty(), "armed");
                    line.aisle.breakBlock(line.selectorPos());
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.selectorMode(), SelectorMode.LOCAL, "the station stays local");
                    helper.assertValueEqual(station.linkedSelector(), Optional.empty(), "without a selector");
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_STAIRS, "the filter is never restored");
                    // A new selector on another face of the saw registers again.
                    helper.assertTrue(clicker.click(line.sawPos(), Direction.WEST, true).consumesAction(),
                            "a new selector is placed");
                    BlockPos second = line.sawPos().relative(Direction.WEST);
                    helper.assertValueEqual(station.linkedSelector(), Optional.of(helper.absolutePos(second)),
                            "and registered");
                    RecipeSelectorBlockEntity selector = selectorAt(helper, second);
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, false), Optional.empty(), "it reads stairs");
                    helper.assertValueEqual(station.switchSelectorOff(),
                            WarehouseProductionBlockEntity.SelectorSwitchOff.SWITCHED_OFF, "switched off");
                    helper.assertValueEqual(selector.selection(), Optional.empty(), "the selector forgot its selection");
                    helper.assertValueEqual(selector.status(), RecipeSelectorStatus.STATION_SWITCHED_OFF, "status");
                    clicker.handChange(line.sawPos(), new ItemStack(Items.DEEPSLATE_TILE_SLAB));
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_SLAB, "a hand change now stays");
                    helper.assertValueEqual(selector.setBackCount(), 0, "nothing was set back");
                })
                .thenSucceed();
    }

    /** Design test 23: a wrench never turns a selector. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorWrenchDoesNotRotate(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, false);
        Clicker clicker = new Clicker(helper, 1);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.place(clicker);
                    BlockState before = helper.getBlockState(line.selectorPos());
                    clicker.player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
                    clicker.click(line.selectorPos(), Direction.SOUTH, false);
                    clicker.click(line.selectorPos(), Direction.UP, false);
                    helper.assertValueEqual(helper.getBlockState(line.selectorPos()), before,
                            "the wrench left the selector as it was");
                })
                .thenSucceed();
    }

    /**
     * Design test 24: a second writer forced in past the placement rule is never a recursion: the registered selector
     * sees it and refuses (CONTESTED), the forced one finds no station that links back and forgets its selection.
     * Alone again, the set-back is capped at four per tick; the fifth hand change stays, and the next arm pass says
     * CONTESTED once and then sets the filter again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorTwoWritersCapped(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, true);
        Clicker clicker = new Clicker(helper, 1);
        BlockPos forced = line.sawPos().relative(Direction.WEST);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.place(clicker);
                    RecipeSelectorBlockEntity selector = line.selector();
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.empty(), "armed for stairs");
                    helper.setBlock(forced, WareworksBlocks.RECIPE_SELECTOR.getDefaultState()
                            .setValue(RecipeSelectorBlock.FACING, Direction.EAST));
                    RecipeSelectorBlockEntity second = selectorAt(helper, forced);
                    second.forceStationForTest(line.aisle.absoluteRackPos(STATION));
                    second.forceSelectionForTest(SAW_SLAB);
                    clicker.handChange(line.sawPos(), new ItemStack(Items.DEEPSLATE_TILE_WALL));
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_WALL, "nobody wrote over the hand");
                    helper.assertValueEqual(selector.status(), RecipeSelectorStatus.CONTESTED, "the first sees two");
                    helper.assertValueEqual(second.status(), RecipeSelectorStatus.STATION_MISSING,
                            "the forced one has no station that links back");
                    helper.assertValueEqual(second.selection(), Optional.empty(), "and forgot its selection");
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.of(SelectorWait.CONTESTED),
                            "the arm step refuses while two selectors touch the filter");
                    helper.setBlock(forced, Blocks.AIR);
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.empty(), "alone again");
                    int before = selector.setBackCount();
                    for (int change = 0; change < RecipeSelectorBlockEntity.MAX_SET_BACKS_PER_TICK; change++) {
                        clicker.handChange(line.sawPos(), new ItemStack(Items.DEEPSLATE_TILE_WALL));
                        helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_STAIRS, "set back " + change);
                    }
                    clicker.handChange(line.sawPos(), new ItemStack(Items.DEEPSLATE_TILE_WALL));
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_WALL, "the fifth change in a tick stays");
                    helper.assertValueEqual(selector.setBackCount(), before + RecipeSelectorBlockEntity.MAX_SET_BACKS_PER_TICK,
                            "four set-backs counted");
                    helper.assertValueEqual(selector.status(), RecipeSelectorStatus.CONTESTED, "capped");
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.of(SelectorWait.CONTESTED),
                            "the next arm pass reports it once");
                    helper.assertValueEqual(selector.armFor(SAW_STAIRS, true), Optional.empty(), "then sets it again");
                    helper.assertTrue(line.sawFilter() == Items.DEEPSLATE_TILE_STAIRS, "stairs again");
                })
                .thenSucceed();
    }

    // --- the pattern check -----------------------------------------------------------------------------------------

    /**
     * Design test 16: the pattern check against the game's real recipes (VALID, NO_RECIPE, WRONG_COUNT,
     * STONECUTTING_OFF, AMBIGUOUS at a Mixer basin, FLUID, the Press's square recipes, shapeless recipes switched off)
     * and against made-up recipes for what no data pack has (a chance output, two cutting recipes that disagree).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void selectorPatternChecks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SelectorRecipeCheck.Options all = new SelectorRecipeCheck.Options(true, true, true);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(COBBLED, 1, SAW_STAIRS, 1), all,
                SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(COBBLED, 1, SAW_SLAB, 2), all,
                SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(COBBLED, 1, SAW_SLAB, 1), all,
                SelectorRecipeCheck.Verdict.WRONG_COUNT);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(COBBLED, 2, SAW_SLAB, 4), all,
                SelectorRecipeCheck.Verdict.WRONG_COUNT);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(ItemKey.of(Items.OAK_LOG), 1,
                ItemKey.of(Items.OAK_PLANKS), 4), all, SelectorRecipeCheck.Verdict.NO_RECIPE);
        assertCheck(helper, level, SelectorMachine.Kind.SAW, pattern(COBBLED, 1, SAW_STAIRS, 1),
                new SelectorRecipeCheck.Options(false, true, true), SelectorRecipeCheck.Verdict.STONECUTTING_OFF);
        // Basins: the Mixer's shapeless dyes, the Press's square blocks.
        ItemKey black = ItemKey.of(Items.BLACK_DYE);
        ItemKey white = ItemKey.of(Items.WHITE_DYE);
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, pattern2(black, 1, white, 1, GRAY, 2), all,
                SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, pattern2(GRAY, 1, white, 1, LIGHT_GRAY, 2), all,
                SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, pattern2(black, 1, white, 1, GRAY, 2),
                new SelectorRecipeCheck.Options(true, false, true), SelectorRecipeCheck.Verdict.NO_RECIPE);
        // Gray + white and black + two whites both make light gray, in other amounts: the Mixer may take either.
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, new ProductionPattern<>(List.of(
                new ProductionEntry<>(GRAY, 1), new ProductionEntry<>(white, 2), new ProductionEntry<>(black, 1)),
                new ProductionEntry<>(LIGHT_GRAY, 2)), all, SelectorRecipeCheck.Verdict.AMBIGUOUS);
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, pattern(ItemKey.of(
                AllItems.WHEAT_FLOUR.asItem()), 1, ItemKey.of(AllItems.DOUGH.asItem()), 1), all,
                SelectorRecipeCheck.Verdict.FLUID);
        assertCheck(helper, level, SelectorMachine.Kind.PRESS_BASIN, pattern(IRON, 4, TRAPDOOR, 1), all,
                SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.PRESS_BASIN, pattern(IRON, 9, ItemKey.of(Items.IRON_BLOCK), 1),
                all, SelectorRecipeCheck.Verdict.VALID);
        assertCheck(helper, level, SelectorMachine.Kind.MIXER_BASIN, pattern(IRON, 4, TRAPDOOR, 1), all,
                SelectorRecipeCheck.Verdict.NO_RECIPE);
        // Made-up recipes: a chance output, and two cutting recipes for one result in other amounts.
        Ingredient cobbled = Ingredient.of(Items.COBBLED_DEEPSLATE);
        ProductionPattern<ItemKey> stairsPattern = pattern(COBBLED, 1, SAW_STAIRS, 1);
        helper.assertValueEqual(SelectorRecipeCheck.judge(SelectorMachine.Kind.SAW, List.of(new SelectorRecipeCheck.Candidate(
                List.of(cobbled), List.of(new SelectorRecipeCheck.Output(new ItemStack(Items.DEEPSLATE_TILE_STAIRS),
                        0.5F)), false, false)), stairsPattern, all), SelectorRecipeCheck.Verdict.CHANCE, "chance");
        helper.assertValueEqual(SelectorRecipeCheck.judge(SelectorMachine.Kind.SAW, List.of(
                new SelectorRecipeCheck.Candidate(List.of(cobbled), List.of(new SelectorRecipeCheck.Output(
                        new ItemStack(Items.DEEPSLATE_TILE_STAIRS), 1.0F)), false, false),
                new SelectorRecipeCheck.Candidate(List.of(cobbled), List.of(new SelectorRecipeCheck.Output(
                        new ItemStack(Items.DEEPSLATE_TILE_STAIRS, 2), 1.0F)), false, false)), stairsPattern, all),
                SelectorRecipeCheck.Verdict.AMBIGUOUS, "two cutting recipes that disagree");
        helper.succeed();
    }

    /**
     * Review fix (Create API 1): the candidates of a kind are looked up once per recipe set and options. A second
     * lookup, as a port's retry of the same box makes, gets the very same list without a scan of every recipe, and the
     * cached list is what a fresh scan finds.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void selectorCandidatesAreCached(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SelectorRecipeCheck.Options all = new SelectorRecipeCheck.Options(true, true, true);
        List<SelectorRecipeCheck.Candidate> first = SelectorRecipeCheck.candidatesFor(level, SelectorMachine.Kind.SAW,
                Items.DEEPSLATE_TILE_STAIRS, all);
        helper.assertFalse(first.isEmpty(), "the stairs have a candidate at the saw");
        helper.assertTrue(first == SelectorRecipeCheck.candidatesFor(level, SelectorMachine.Kind.SAW,
                Items.DEEPSLATE_TILE_STAIRS, all), "a second lookup takes the cached list");
        helper.assertValueEqual(first, SelectorRecipeCheck.index(SelectorMachine.Kind.SAW,
                level.getRecipeManager().getRecipes(), all, level.registryAccess())
                .getOrDefault(Items.DEEPSLATE_TILE_STAIRS, List.of()), "the cache holds what a scan finds");
        helper.succeed();
    }

    /**
     * Review fix (Create API 2): a recipe Create ignores in automation ({@code _manual_only}) is ignored where the
     * machine ignores it and nowhere else: the saw never takes one, a basin never takes a crafting recipe of that kind,
     * but the Mixer's own mixing recipes and the Press's own compacting recipes run all the same, so the check must see
     * them. The recipes are the game's own under a {@code _manual_only} name, beside the originals as the control.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void selectorAutomationFilterPerMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SelectorRecipeCheck.Options all = new SelectorRecipeCheck.Options(true, true, true);
        RecipeHolder<?> mixing = recipe(helper, "create:mixing/andesite_alloy");
        RecipeHolder<?> compacting = recipe(helper, "create:compacting/andesite_from_flint");
        RecipeHolder<?> cutting = recipe(helper, "create:cutting/andesite_alloy");
        RecipeHolder<?> shapeless = recipe(helper, "minecraft:gray_dye");
        RecipeHolder<?> square = recipe(helper, "minecraft:iron_block");
        Item alloy = AllItems.ANDESITE_ALLOY.asItem();
        Item shaft = AllBlocks.SHAFT.asItem();
        // Controls: under their own names every one is taken.
        assertIndexed(helper, level, SelectorMachine.Kind.MIXER_BASIN, mixing, all, alloy, true);
        assertIndexed(helper, level, SelectorMachine.Kind.MIXER_BASIN, shapeless, all, Items.GRAY_DYE, true);
        assertIndexed(helper, level, SelectorMachine.Kind.PRESS_BASIN, compacting, all, Items.ANDESITE, true);
        assertIndexed(helper, level, SelectorMachine.Kind.PRESS_BASIN, square, all, Items.IRON_BLOCK, true);
        assertIndexed(helper, level, SelectorMachine.Kind.SAW, cutting, all, shaft, true);
        // Manual only: the machines' own recipes stay, the borrowed crafting recipes and the saw's go.
        assertIndexed(helper, level, SelectorMachine.Kind.MIXER_BASIN, manualOnly(mixing), all, alloy, true);
        assertIndexed(helper, level, SelectorMachine.Kind.BASIN_ALONE, manualOnly(mixing), all, alloy, true);
        assertIndexed(helper, level, SelectorMachine.Kind.PRESS_BASIN, manualOnly(compacting), all, Items.ANDESITE,
                true);
        assertIndexed(helper, level, SelectorMachine.Kind.MIXER_BASIN, manualOnly(shapeless), all, Items.GRAY_DYE,
                false);
        assertIndexed(helper, level, SelectorMachine.Kind.PRESS_BASIN, manualOnly(square), all, Items.IRON_BLOCK,
                false);
        assertIndexed(helper, level, SelectorMachine.Kind.SAW, manualOnly(cutting), all, shaft, false);
        helper.succeed();
    }

    /**
     * Review fix (Create API 3): Create's recipe switches can change while the game runs, and a station's cached pattern
     * check follows them. A stonecutting pattern at a saw station reads VALID, STONECUTTING_OFF once the server stops
     * saws from stonecutting, and VALID again; the switch is turned and restored within one tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorCheckFollowsCreatesConfig(GameTestHelper helper) {
        SawLine line = new SawLine(helper, 8, false);
        Clicker clicker = new Clicker(helper, 1);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    clicker.click(line.stationPos(), Direction.UP, true);
                    clicker.click(line.sawPos(), Direction.SOUTH, true);
                    clicker.assertTold(WareworksLang.SELECTOR_PLACED);
                    WarehouseProductionBlockEntity station = line.station();
                    helper.assertValueEqual(station.selectorCheck(0), Optional.of(SelectorRecipeCheck.Verdict.VALID),
                            "the stairs pass with stonecutting on");
                    boolean before = AllConfigs.server().recipes.allowStonecuttingOnSaw.get();
                    try {
                        AllConfigs.server().recipes.allowStonecuttingOnSaw.set(false);
                        helper.assertValueEqual(station.selectorCheck(0),
                                Optional.of(SelectorRecipeCheck.Verdict.STONECUTTING_OFF),
                                "the cached check follows the switch");
                        helper.assertValueEqual(station.activePatterns().size(), 0,
                                "and no saw pattern is offered while saws may not stonecut");
                    } finally {
                        AllConfigs.server().recipes.allowStonecuttingOnSaw.set(before);
                    }
                    helper.assertValueEqual(station.selectorCheck(0), Optional.of(SelectorRecipeCheck.Verdict.VALID),
                            "and back");
                })
                .thenSucceed();
    }

    private static RecipeHolder<?> recipe(GameTestHelper helper, String id) {
        Optional<RecipeHolder<?>> found = helper.getLevel().getRecipeManager().byKey(ResourceLocation.parse(id));
        if (found.isEmpty()) {
            helper.fail("no recipe " + id);
            throw new IllegalStateException("unreachable");
        }
        return found.get();
    }

    /** The same recipe under a name ending in {@code _manual_only}, which Create's automation filter reads. */
    private static RecipeHolder<?> manualOnly(RecipeHolder<?> holder) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Wareworks.ID,
                holder.id().getPath().replace('/', '_') + "_manual_only"), holder.value());
    }

    private static void assertIndexed(GameTestHelper helper, ServerLevel level, SelectorMachine.Kind kind,
            RecipeHolder<?> holder, SelectorRecipeCheck.Options options, Item result, boolean expected) {
        helper.assertValueEqual(SelectorRecipeCheck.index(kind, List.of(holder), options, level.registryAccess())
                .containsKey(result), expected, kind + " takes " + holder.id());
    }

    // --- basins with their operators -------------------------------------------------------------------------------

    /**
     * Design tests 17 and 19: a Basin under a running Mixer, with its selector on the Basin. Set to gray dye, black dye
     * and two white dyes make two gray and leave a white (light gray, which the same contents also make, never
     * appears); while the Mixer works, a switch is refused as busy; with the leftover still in the Basin too; then set
     * to light gray, gray and white make light gray.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorBasinGrayThenLightGray(GameTestHelper helper) {
        BasinRig rig = BasinRig.mixer(helper);
        boolean[] sawBusy = new boolean[1];
        helper.onEachTick(() -> helper.assertValueEqual(rig.count(LIGHT_GRAY.getItem()) > 0
                && rig.selector().selection().filter(GRAY::equals).isPresent(), false,
                "no light gray while the basin is set to gray"));
        helper.startSequence()
                .thenWaitUntil(rig::assertTurning)
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.selector().armFor(GRAY, true), Optional.empty(), "set to gray");
                    rig.assertFilter(Items.GRAY_DYE);
                    helper.assertValueEqual(rig.selector().machineKind(), Optional.of(SelectorMachine.Kind.MIXER_BASIN),
                            "the kind is read from the Mixer above the Basin");
                    rig.insert(Items.BLACK_DYE, 1);
                    rig.insert(Items.WHITE_DYE, 2);
                })
                .thenWaitUntil(() -> {
                    if (!sawBusy[0] && rig.selector().armFor(LIGHT_GRAY, true).filter(SelectorWait.MACHINE_BUSY::equals)
                            .isPresent())
                        sawBusy[0] = true;
                    helper.assertValueEqual(rig.outputCount(Items.GRAY_DYE), 2, "two gray made");
                })
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(sawBusy[0], "a switch was refused while the Mixer worked");
                    helper.assertValueEqual(rig.count(Items.WHITE_DYE), 1, "one white left over");
                    helper.assertValueEqual(rig.count(Items.LIGHT_GRAY_DYE), 0, "no light gray");
                    helper.assertValueEqual(rig.selector().armFor(LIGHT_GRAY, true),
                            Optional.of(SelectorWait.MACHINE_BUSY), "the leftover keeps the basin busy");
                    rig.takeAll();
                    helper.assertValueEqual(rig.selector().armFor(LIGHT_GRAY, true), Optional.empty(),
                            "empty, it switches to light gray");
                    rig.assertFilter(Items.LIGHT_GRAY_DYE);
                    rig.insert(Items.GRAY_DYE, 1);
                    rig.insert(Items.WHITE_DYE, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.outputCount(Items.LIGHT_GRAY_DYE), 2,
                        "two light gray made"))
                .thenSucceed();
    }

    /**
     * Design test 18: a Basin under a Press, set to iron trapdoors: nine iron ingots make two trapdoors and leave one
     * ingot, never an iron block, which the same ingots also make.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorPressTrapdoors(GameTestHelper helper) {
        BasinRig rig = BasinRig.press(helper);
        helper.onEachTick(() -> helper.assertValueEqual(rig.count(Items.IRON_BLOCK), 0, "never an iron block"));
        helper.startSequence()
                .thenWaitUntil(rig::assertTurning)
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.selector().armFor(TRAPDOOR, true), Optional.empty(), "set to trapdoors");
                    rig.assertFilter(Items.IRON_TRAPDOOR);
                    helper.assertValueEqual(rig.selector().machineKind(), Optional.of(SelectorMachine.Kind.PRESS_BASIN),
                            "the kind is read from the Press above the Basin");
                    rig.insert(Items.IRON_INGOT, 9);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.outputCount(Items.IRON_TRAPDOOR), 2, "two trapdoors"))
                .thenExecuteAfter(60, () -> {
                    helper.assertValueEqual(rig.count(Items.IRON_TRAPDOOR), 2, "still two");
                    helper.assertValueEqual(rig.count(Items.IRON_INGOT), 1, "one ingot left");
                })
                .thenSucceed();
    }

    /**
     * Review fix (tests 3), the control of {@link #selectorBasinGrayThenLightGray}: the same contents, one black and two
     * white dyes, with the Basin set to light gray instead make three light gray and no gray. Only together do the two
     * prove that the filter the selector writes steers the Mixer, and not Create's own choice.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorBasinLightGrayControl(GameTestHelper helper) {
        BasinRig rig = BasinRig.mixer(helper);
        helper.onEachTick(() -> helper.assertValueEqual(rig.count(Items.GRAY_DYE), 0,
                "no gray while the basin is set to light gray"));
        helper.startSequence()
                .thenWaitUntil(rig::assertTurning)
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.selector().armFor(LIGHT_GRAY, true), Optional.empty(),
                            "set to light gray");
                    rig.assertFilter(Items.LIGHT_GRAY_DYE);
                    rig.insert(Items.BLACK_DYE, 1);
                    rig.insert(Items.WHITE_DYE, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.outputCount(Items.LIGHT_GRAY_DYE), 3,
                        "three light gray made"))
                .thenExecuteAfter(40, () -> {
                    helper.assertValueEqual(rig.count(Items.BLACK_DYE) + rig.count(Items.WHITE_DYE), 0,
                            "all of it was used");
                    helper.assertValueEqual(rig.count(Items.LIGHT_GRAY_DYE), 3, "still three");
                })
                .thenSucceed();
    }

    /**
     * Review fix (tests 3), the control of {@link #selectorPressTrapdoors}: the same nine iron ingots, with the Basin
     * set to iron blocks instead, make one block and no trapdoor.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void selectorPressIronBlockControl(GameTestHelper helper) {
        BasinRig rig = BasinRig.press(helper);
        helper.onEachTick(() -> helper.assertValueEqual(rig.count(Items.IRON_TRAPDOOR), 0, "never a trapdoor"));
        helper.startSequence()
                .thenWaitUntil(rig::assertTurning)
                .thenExecute(() -> {
                    helper.assertValueEqual(rig.selector().armFor(ItemKey.of(Items.IRON_BLOCK), true),
                            Optional.empty(), "set to iron blocks");
                    rig.assertFilter(Items.IRON_BLOCK);
                    rig.insert(Items.IRON_INGOT, 9);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(rig.outputCount(Items.IRON_BLOCK), 1, "one iron block"))
                .thenExecuteAfter(60, () -> {
                    helper.assertValueEqual(rig.count(Items.IRON_BLOCK), 1, "still one");
                    helper.assertValueEqual(rig.count(Items.IRON_INGOT), 0, "every ingot used");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreSelectorBlockConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static final ItemKey COBBLED = ItemKey.of(Items.COBBLED_DEEPSLATE);
    private static final ItemKey SAW_STAIRS = ItemKey.of(Items.DEEPSLATE_TILE_STAIRS);
    private static final ItemKey SAW_SLAB = ItemKey.of(Items.DEEPSLATE_TILE_SLAB);
    private static final ItemKey SAW_WALL = ItemKey.of(Items.DEEPSLATE_TILE_WALL);
    private static final ItemKey GRAY = ItemKey.of(Items.GRAY_DYE);
    private static final ItemKey LIGHT_GRAY = ItemKey.of(Items.LIGHT_GRAY_DYE);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey TRAPDOOR = ItemKey.of(Items.IRON_TRAPDOOR);

    private static ProductionPattern<ItemKey> pattern(ItemKey in, int inCount, ItemKey out, int outCount) {
        return ProductionPattern.of(in, inCount, out, outCount);
    }

    private static ProductionPattern<ItemKey> pattern2(ItemKey a, int aCount, ItemKey b, int bCount, ItemKey out,
            int outCount) {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(a, aCount), new ProductionEntry<>(b, bCount)),
                new ProductionEntry<>(out, outCount));
    }

    private static void assertCheck(GameTestHelper helper, ServerLevel level, SelectorMachine.Kind kind,
            ProductionPattern<ItemKey> pattern, SelectorRecipeCheck.Options options,
            SelectorRecipeCheck.Verdict expected) {
        helper.assertValueEqual(SelectorRecipeCheck.check(level, kind, pattern, options), expected,
                kind + " " + pattern);
    }

    /** An up-facing saw at {@code pos} with a creative motor east of it (the saw's shaft runs along x). */
    private static void placeSaw(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        BlockPos motor = pos.east();
        helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.WEST));
        if (helper.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be)
            be.generatedSpeed.setValue(MACHINE_RPM);
    }

    private static SawBlockEntity sawAt(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos) instanceof SawBlockEntity saw ? saw : null;
    }

    private static RecipeSelectorBlockEntity selectorAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector)
            return selector;
        helper.fail("no Recipe Selector", pos);
        throw new IllegalStateException("unreachable");
    }

    /** A real player holding Recipe Selector items, clicking through the server's own dispatch. */
    private static final class Clicker {
        final GameTestHelper helper;
        final ClickingPlayer player;

        Clicker(GameTestHelper helper, int items) {
            this.helper = helper;
            this.player = ClickingPlayer.at(helper, new BlockPos(8, BASE_Y, 1));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WareworksBlocks.RECIPE_SELECTOR.asItem(),
                    items));
        }

        ItemStack hand() {
            return player.getMainHandItem();
        }

        /** A click on the {@code face} of the block at the test-relative {@code pos}, sneaking or not. */
        InteractionResult click(BlockPos pos, Direction face, boolean sneak) {
            BlockPos absolute = helper.absolutePos(pos);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).relative(face, 0.5), face, absolute,
                    false);
            player.setShiftKeyDown(sneak);
            try {
                return player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(),
                        InteractionHand.MAIN_HAND, hit);
            } finally {
                player.setShiftKeyDown(false);
            }
        }

        /** A sneaking placement against {@code face} of {@code pos} that must be refused with {@code key}. */
        void refused(BlockPos pos, Direction face, String key) {
            int before = hand().getCount();
            BlockPos target = pos.relative(face);
            click(pos, face, true);
            assertTold(key);
            helper.assertValueEqual(hand().getCount(), before, "a refused placement keeps the item (" + key + ")");
            helper.assertFalse(helper.getBlockState(target).is(WareworksBlocks.RECIPE_SELECTOR.get()),
                    "nothing was placed (" + key + ")");
        }

        /** A sneak-click on the block at {@code pos} that must be refused with {@code key}. */
        void refusedClick(BlockPos pos, String key) {
            click(pos, Direction.UP, true);
            assertTold(key);
        }

        void assertTold(String key) {
            List<String> told = player.toldKeys();
            helper.assertTrue(!told.isEmpty() && told.get(told.size() - 1).equals(WareworksLang.key(key)),
                    "the player was told " + key + ", got " + (told.isEmpty() ? "nothing" : told.get(told.size() - 1)));
        }

        /** A hand change of the machine's filter at {@code machine}, as Create's value box does it for a player. */
        void handChange(BlockPos machine, ItemStack held) {
            ItemStack previous = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            FilteringBehaviour filter = BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(machine),
                    FilteringBehaviour.TYPE);
            BlockPos absolute = helper.absolutePos(machine);
            filter.onShortInteract(player, InteractionHand.MAIN_HAND, Direction.UP,
                    new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
            if (held.getItem() instanceof FilterItem)
                return;
            ItemStack after = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
            helper.assertValueEqual(after.getCount(), held.getCount(), "a ghost item stays in the hand");
        }
    }

    /** The saw line of the class comment. */
    private static final class SawLine {
        final GameTestHelper helper;
        final AisleFixture aisle;
        final long cobbled;
        private boolean turnedRound;

        SawLine(GameTestHelper helper, int cobbled, boolean motor) {
            this.helper = helper;
            this.cobbled = cobbled;
            aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(motor);
            if (motor)
                aisle.motor().generatedSpeed.setValue(CRANE_RPM);
            aisle.storage(COBBLED_STORAGE, COBBLED.toStack(cobbled));
            for (RackPosition storage : PRODUCT_STORAGE)
                aisle.storage(storage);
            aisle.production(STATION);
            aisle.production(SECOND_STATION);
            aisle.input(RETURN);
            aisle.input(OTHER_INPUT);
            aisle.output(OUTPUT);
            helper.setBlock(sawPos(), AllBlocks.MECHANICAL_SAW.getDefaultState()
                    .setValue(DirectionalKineticBlock.FACING, Direction.UP)
                    .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
            BlockPos sawMotor = aisle.inventoryPos(OTHER_INPUT);
            helper.setBlock(sawMotor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.WEST));
            if (helper.getBlockEntity(sawMotor) instanceof CreativeMotorBlockEntity be)
                be.generatedSpeed.setValue(MACHINE_RPM);
            WarehouseProductionBlockEntity station = station();
            setPattern(station, 0, SAW_STAIRS, 1);
            setPattern(station, 1, SAW_SLAB, 2);
            setPattern(station, 2, SAW_WALL, 1);
        }

        private void setPattern(WarehouseProductionBlockEntity station, int slot, ItemKey result, int count) {
            helper.assertTrue(station.setPatternEntry(slot, 0, COBBLED, 1), "ingredient");
            helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, result, count), "result");
        }

        BlockPos sawPos() {
            return aisle.inventoryPos(RETURN);
        }

        BlockPos selectorPos() {
            return sawPos().relative(Direction.SOUTH);
        }

        BlockPos stationPos() {
            return aisle.rackPos(STATION);
        }

        WarehouseControllerBlockEntity controller() {
            return aisle.controller();
        }

        WarehouseProductionBlockEntity station() {
            return aisle.productionAt(STATION);
        }

        SawBlockEntity saw() {
            SawBlockEntity saw = sawAt(helper, sawPos());
            if (saw == null)
                helper.fail("no saw", sawPos());
            return saw;
        }

        RecipeSelectorBlockEntity selector() {
            return selectorAt(helper, selectorPos());
        }

        ItemStack sawFilterStack() {
            FilteringBehaviour filter = BlockEntityBehaviour.get(saw(), FilteringBehaviour.TYPE);
            return filter == null ? ItemStack.EMPTY : filter.getFilter();
        }

        Item sawFilter() {
            return sawFilterStack().getItem();
        }

        /**
         * The warehouse is up and the saw turns, its items moving into the return input; a motor turning the other way
         * is turned round once.
         */
        void assertReady() {
            helper.assertValueEqual(controller().countOf(COBBLED), cobbled, "cobbled deepslate in stock");
            helper.assertValueEqual(controller().productionStations().size(), 2, "both stations are recorded");
            SawBlockEntity saw = saw();
            helper.assertTrue(saw.getSpeed() != 0, "the saw turns");
            Vec3 movement = saw.getItemMovementVec();
            Direction intoInput = aisle.sideDirection(RETURN).getOpposite();
            if (Math.round(movement.z) != intoInput.getStepZ()) {
                if (!turnedRound && helper.getBlockEntity(aisle.inventoryPos(OTHER_INPUT)) instanceof CreativeMotorBlockEntity be)
                    be.generatedSpeed.setValue(-be.generatedSpeed.getValue());
                turnedRound = true;
                helper.fail("the saw turned the wrong way; turned round");
            }
        }

        /** Places a selector with {@code clicker}: link click on the station, sneaking placement against the saw. */
        void place(Clicker clicker) {
            clicker.click(stationPos(), Direction.UP, true);
            helper.assertTrue(clicker.click(sawPos(), Direction.SOUTH, true).consumesAction(), "the selector is placed");
            clicker.assertTold(WareworksLang.SELECTOR_PLACED);
        }

        /** The belt: one ingredient from the station into an empty saw. */
        void feedSaw() {
            SawBlockEntity saw = saw();
            if (!saw.inventory.isEmpty())
                return;
            IItemHandler station = aisle.handlerAt(stationPos());
            for (int slot = 0; slot < station.getSlots(); slot++) {
                ItemStack one = station.extractItem(slot, 1, false);
                if (one.isEmpty())
                    continue;
                ItemStack rest = saw.inventory.insertItem(0, one, false);
                helper.assertTrue(rest.isEmpty(), "the saw takes one item");
                return;
            }
        }

        /** Requests {@code amount} of {@code key} at the output, all of it made; returns the new order's id. */
        UUID order(ItemKey key, int amount) {
            Set<UUID> before = new LinkedHashSet<>();
            for (ProductionOrder<ItemKey, RackPosition> order : controller().productionOrders())
                before.add(order.id());
            RequestResult result = controller().request(aisle.absoluteRackPos(OUTPUT), key, amount);
            helper.assertTrue(result.isAccepted(), "the request is accepted");
            helper.assertValueEqual(result.producing(), amount, "all of it is made");
            for (ProductionOrder<ItemKey, RackPosition> order : controller().productionOrders()) {
                if (!before.contains(order.id()))
                    return order.id();
            }
            helper.fail("no production order was created for " + key);
            throw new IllegalStateException("unreachable");
        }

        void assertState(UUID id, ProductionOrderState state) {
            Optional<ProductionOrder<ItemKey, RackPosition>> order = controller().productionOrder(id);
            helper.assertTrue(order.isPresent(), "order " + id + " exists");
            helper.assertValueEqual(order.get().state(), state, "order state of " + order.get().result());
        }

        /**
         * Conservation across the saw: one cobbled deepslate is one stair, one wall or two slabs, and nothing else is
         * ever made.
         */
        void assertConserved(long cobbledAtStart) {
            Map<ItemKey, Long> census = ItemCensus.take(helper);
            long halves = 0;
            for (Map.Entry<ItemKey, Long> entry : census.entrySet()) {
                ItemKey key = entry.getKey();
                if (key.equals(COBBLED) || key.equals(SAW_STAIRS) || key.equals(SAW_WALL))
                    halves += 2 * entry.getValue();
                else if (key.equals(SAW_SLAB))
                    halves += entry.getValue();
                else if (entry.getValue() > 0)
                    helper.fail("the saw made something it was not set to: " + key + " x" + entry.getValue());
            }
            helper.assertValueEqual(halves, 2 * cobbledAtStart, "items conserved across the saw: " + census);
        }

        /** I2 on one tick: the crane is asked only for the selection's ingredients, and one result is engaged. */
        void assertOneResultInTheLine() {
            WarehouseProductionBlockEntity station = station();
            Map<UUID, ProductionOrder<ItemKey, RackPosition>> byLine = new HashMap<>();
            Set<ItemKey> engaged = new LinkedHashSet<>();
            for (ProductionOrder<ItemKey, RackPosition> order : controller().openProductionOrders()) {
                for (SupplyLine<ItemKey> line : order.lines())
                    byLine.put(line.id(), order);
                if (order.engaged())
                    engaged.add(order.result());
            }
            helper.assertTrue(engaged.size() <= 1, "one result engaged at a time: " + engaged);
            for (PlannerInput.SupplyNeed<ItemKey, RackPosition> need : controller().supplyNeeds()) {
                ProductionOrder<ItemKey, RackPosition> order = byLine.get(need.id());
                helper.assertTrue(order != null && station.selection().equals(Optional.of(order.result())),
                        "the crane fetches only for the selection " + station.selection());
            }
        }
    }

    /** A Basin with its operator, a creative motor, and a Recipe Selector on the Basin's west side (no station). */
    private static final class BasinRig {
        final GameTestHelper helper;
        final BlockPos basin = new BlockPos(3, BASE_Y, 3);
        final BlockPos selector = basin.west();

        private BasinRig(GameTestHelper helper) {
            this.helper = helper;
            helper.setBlock(basin, AllBlocks.BASIN.getDefaultState());
            helper.setBlock(selector, WareworksBlocks.RECIPE_SELECTOR.getDefaultState()
                    .setValue(RecipeSelectorBlock.FACING, Direction.EAST));
        }

        /** A Mechanical Mixer two above the Basin, turned by a cogwheel beside it and a motor above the cogwheel. */
        static BasinRig mixer(GameTestHelper helper) {
            BasinRig rig = new BasinRig(helper);
            BlockPos mixer = rig.basin.above(2);
            helper.setBlock(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState());
            BlockPos cog = mixer.east();
            helper.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState());
            rig.motor(cog.above(), Direction.DOWN);
            return rig;
        }

        /** A Mechanical Press two above the Basin, its shaft along z, a motor north of it. */
        static BasinRig press(GameTestHelper helper) {
            BasinRig rig = new BasinRig(helper);
            BlockPos press = rig.basin.above(2);
            helper.setBlock(press, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.NORTH));
            rig.motor(press.north(), Direction.SOUTH);
            return rig;
        }

        private void motor(BlockPos pos, Direction facing) {
            helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, facing));
            if (helper.getBlockEntity(pos) instanceof CreativeMotorBlockEntity be)
                be.generatedSpeed.setValue(MACHINE_RPM);
        }

        RecipeSelectorBlockEntity selector() {
            return selectorAt(helper, selector);
        }

        /** The filter the selector wrote, read from the Basin itself (review fix, tests 3). */
        void assertFilter(Item item) {
            Optional<SelectorMachine> machine = SelectorMachine.resolve(helper.getLevel(), helper.absolutePos(basin));
            helper.assertTrue(machine.isPresent(), "the basin is a machine");
            helper.assertValueEqual(machine.get().currentFilter().getItem(), item, "the basin's filter");
        }

        BasinBlockEntity basin() {
            if (helper.getBlockEntity(basin) instanceof BasinBlockEntity be)
                return be;
            helper.fail("no basin", basin);
            throw new IllegalStateException("unreachable");
        }

        void assertTurning() {
            BlockState operator = helper.getBlockState(basin.above(2));
            helper.assertTrue(helper.getBlockEntity(basin.above(2)) instanceof
                    com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic && kinetic.getSpeed() != 0,
                    "the operator turns: " + operator);
        }

        void insert(Item item, int count) {
            ItemStack rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(basin().getInputInventory(),
                    new ItemStack(item, count), false);
            helper.assertTrue(rest.isEmpty(), "the basin takes " + item);
        }

        int outputCount(Item item) {
            return count(basin().getOutputInventory(), item);
        }

        /** Items of {@code item} anywhere in the basin. */
        int count(Item item) {
            return count(basin().getInputInventory(), item) + count(basin().getOutputInventory(), item);
        }

        private static int count(IItemHandler handler, Item item) {
            int total = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(item))
                    total += stack.getCount();
            }
            return total;
        }

        /** The player empties the basin by hand. */
        void takeAll() {
            for (IItemHandler handler : List.of(basin().getInputInventory(), basin().getOutputInventory())) {
                for (int slot = 0; slot < handler.getSlots(); slot++)
                    handler.extractItem(slot, Integer.MAX_VALUE, false);
            }
            basin().notifyChangeOfContents();
        }
    }
}
