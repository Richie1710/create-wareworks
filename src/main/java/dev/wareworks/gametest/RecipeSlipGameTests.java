package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerItemHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.RecipeSelectorBlock;
import dev.wareworks.content.selector.RecipeSelectorBlockEntity;
import dev.wareworks.content.selector.RecipeSelectorStatus;
import dev.wareworks.content.selector.RecipeSlip;
import dev.wareworks.content.selector.SelectorLink;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorPackagerItemHandler;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorGate;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the Recipe Selector's far tier (ADR-085, design §7, §3.4, §8; design tests 25 to 42): the recipe slip
 * stamped on a box the station's Packager packs, the Ender Recipe Selector's veto in front of a far factory's Packager
 * (item capability and hand), its placement rules, the probe, and the hold release in slip mode.
 * <p>
 * <b>The factory rig</b> ({@code empty_7x5x7}): a chest at {@link #CHEST}, a Packager on top of it facing up (it unpacks
 * into the chest), the Ender Recipe Selector on top of the Packager facing down (same column, so always the same chunk),
 * an up-facing Mechanical Saw at {@link #SAW} without power (its filter is all that matters; nothing is cut) linked as
 * the selector's machine. Boxes are built and stamped here, the way a station's Packager would have.
 * <p>
 * <b>The warehouse line</b> ({@code aisle_16x10x7}): the {@link AisleFixture} aisle with logs in stock, a production
 * station in slip mode (no machine kind, so no recipe check applies and logs can stand in for cobbled deepslate) with a
 * Packager behind it, its return input and an output. The tests play the far factory, as the local tests play the
 * machine: they take what the crane delivered out of the station and put the product into the return input.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RecipeSlipGameTests {
    private static final String CONFIG_BATCH = "wareworksSlipConfig";

    private static final BlockPos CHEST = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos PACKAGER = CHEST.above();
    private static final BlockPos SELECTOR = PACKAGER.above();
    private static final BlockPos SAW = new BlockPos(1, BASE_Y, 3);

    private static final ItemKey COBBLED = ItemKey.of(Items.COBBLED_DEEPSLATE);
    private static final ItemKey STAIRS = ItemKey.of(Items.DEEPSLATE_TILE_STAIRS);
    private static final ItemKey SLAB = ItemKey.of(Items.DEEPSLATE_TILE_SLAB);

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final RackPosition STATION = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition RETURN = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition LOGS = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PRODUCTS = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition MORE_PRODUCTS = new RackPosition(3, 0, Side.LEFT);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final int LOGS_IN_STOCK = 16;

    /** Ticks a Packager's tray needs after an unpack before it takes the next box (Create's CYCLE plus margin). */
    private static final int CYCLE_WAIT = PackagerBlockEntity.CYCLE + 5;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 4000;

    private RecipeSlipGameTests() {
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(net.minecraft.server.level.ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- goggles ----------------------------------------------------------------------------------------------------

    /**
     * The refusal lines of a Recipe Selector's goggles: none for a selector that never refused a box (this once threw,
     * {@code new EnumMap<>(Map.of())}, and crashed the client's goggle overlay on the first look at a fresh Ender
     * selector, found by the {@code selector-remote} scenario), and otherwise the reasons most frequent first, ties in
     * the order of the statuses, at most the limit.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void selectorGoggleRefusalsWithAndWithout(GameTestHelper helper) {
        helper.assertValueEqual(dev.wareworks.content.selector.RecipeSelectorGoggleSummary.NONE.topRefusals(3),
                List.of(), "no refusal line without a refusal");
        dev.wareworks.content.selector.RecipeSelectorGoggleSummary summary =
                new dev.wareworks.content.selector.RecipeSelectorGoggleSummary(true, RecipeSelectorStatus.SET,
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), false, Optional.empty(), "", 0L, 0, 0,
                        Map.of(RecipeSelectorStatus.STALE_SLIP, 1, RecipeSelectorStatus.FOREIGN_SLIP, 5,
                                RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE, 3, RecipeSelectorStatus.CANNOT_MAKE, 3), 0);
        helper.assertValueEqual(summary.topRefusals(3).stream().map(Map.Entry::getKey).toList(),
                List.of(RecipeSelectorStatus.FOREIGN_SLIP, RecipeSelectorStatus.CANNOT_MAKE,
                        RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE), "the most frequent first");
        helper.succeed();
    }

    // --- the slip itself (tests 27, 28) ----------------------------------------------------------------------------

    /**
     * The slip lives in vanilla {@code custom_data} and nowhere else on the box, and survives everything that moves a
     * box: a save and load of the stack and its network codec. A malformed slip or a non-package reads as none.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void slipSurvivesSaveAndIsCustomData(GameTestHelper helper) {
        Level level = helper.getLevel();
        RecipeSlip slip = RecipeSlip.of(Items.DEEPSLATE_TILE_STAIRS, "A-00-00R",
                GlobalPos.of(level.dimension(), helper.absolutePos(CHEST)), 1234L);
        ItemStack box = box(COBBLED.toStack(64));
        RecipeSlip.stamp(box, slip);
        helper.assertValueEqual(RecipeSlip.read(box), Optional.of(slip), "the slip reads back");
        helper.assertTrue(box.has(DataComponents.CUSTOM_DATA), "the slip is in custom_data");
        box.getComponents().keySet().forEach(type -> helper.assertFalse(
                Wareworks.ID.equals(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)
                        .getNamespace()), "no Wareworks component on the box: " + type));

        Tag saved = box.save(level.registryAccess());
        ItemStack loaded = ItemStack.parseOptional(level.registryAccess(), (CompoundTag) saved);
        helper.assertValueEqual(RecipeSlip.read(loaded), Optional.of(slip), "a saved box keeps its slip");
        helper.assertValueEqual(PackageItem.getContents(loaded).getStackInSlot(0).getCount(), 64,
                "and its contents");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ItemStack.STREAM_CODEC.encode(buffer, box);
        ItemStack sent = ItemStack.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(RecipeSlip.read(sent), Optional.of(slip), "a box sent to a client keeps its slip");

        RecipeSlip newer = new RecipeSlip(slip.result(), slip.from(), slip.origin(), 2000L);
        RecipeSlip.stamp(box, newer);
        helper.assertValueEqual(RecipeSlip.read(box), Optional.of(newer), "a second stamp replaces the slip");

        ItemStack notABox = COBBLED.toStack(1);
        RecipeSlip.stamp(notABox, slip);
        helper.assertTrue(RecipeSlip.read(notABox).isEmpty() && !notABox.has(DataComponents.CUSTOM_DATA),
                "only a package takes a slip");
        CompoundTag broken = new CompoundTag();
        broken.putString("Result", "minecraft:deepslate_tile_stairs");
        helper.assertTrue(RecipeSlip.fromTag(broken).isEmpty(), "a slip without origin and seq reads as none");
        helper.assertTrue(new RecipeSlip(ResourceLocation.fromNamespaceAndPath("gone", "item"), "x", slip.origin(), 1L)
                .item().isEmpty(), "an unknown result is never guessed");
        helper.succeed();
    }

    // --- stamping (test 25) ----------------------------------------------------------------------------------------

    /**
     * A Packager behind a station in slip mode stamps every box it packs with the station's slip; a station in no
     * selector mode and a Packager linked to a logistics network stamp nothing; a package re-sent from the buffer with
     * another station's slip gets this one's.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipStampedOnStationBox(GameTestHelper helper) {
        BlockPos station = new BlockPos(3, BASE_Y, 2);
        BlockPos packagerPos = station.south();
        helper.setBlock(station, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.NORTH));
        helper.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,
                Direction.SOUTH));
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity be = stationAt(helper, station);
                    be.insertIntoBufferForTest(COBBLED.toStack(64));
                    PackagerBlockEntity packager = packagerAt(helper, packagerPos);
                    packager.attemptToSend(null);
                    helper.assertFalse(packager.heldBox.isEmpty(), "the Packager packed the station's goods");
                    helper.assertTrue(RecipeSlip.read(packager.heldBox).isEmpty(),
                            "a station in no selector mode stamps nothing");
                    takeBox(packager);
                    helper.assertValueEqual(be.setSelectorMode(SelectorMode.SLIPS,
                            Optional.of(SelectorMachine.Kind.SAW)),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "slip mode");
                    be.insertIntoBufferForTest(COBBLED.toStack(64));
                    packager.attemptToSend(null);
                    helper.assertTrue(RecipeSlip.read(packager.heldBox).isEmpty(),
                            "a station in slip mode without a selection stamps nothing");
                    takeBox(packager);
                    be.selectorSwitched(STAIRS, helper.getLevel().getGameTime());
                    be.insertIntoBufferForTest(COBBLED.toStack(64));
                    be.insertIntoBufferForTest(COBBLED.toStack(64));
                    packager.attemptToSend(null);
                    Optional<RecipeSlip> slip = RecipeSlip.read(packager.heldBox);
                    helper.assertTrue(slip.isPresent(), "the box was stamped as Create made it");
                    helper.assertValueEqual(slip, be.currentSlip(), "with the station's own slip");
                    helper.assertValueEqual(slip.get().result(), net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(Items.DEEPSLATE_TILE_STAIRS), "the selection");
                    helper.assertValueEqual(slip.get().origin(), GlobalPos.of(helper.getLevel().dimension(),
                            helper.absolutePos(station)), "the station's position");
                    helper.assertValueEqual(slip.get().seq(), be.selectorSwitchTime(), "the switch time");
                    helper.assertValueEqual(PackageItem.getContents(packager.heldBox).getStackInSlot(1).getCount(), 64,
                            "and the goods are in it");
                    takeBox(packager);
                    // A Packager linked to a logistics network: its boxes may be re-boxed, so it is never stamped.
                    helper.setBlock(packagerPos, helper.getBlockState(packagerPos).setValue(PackagerBlock.LINKED, true));
                    be.insertIntoBufferForTest(COBBLED.toStack(64));
                    packagerAt(helper, packagerPos).attemptToSend(null);
                    helper.assertFalse(packagerAt(helper, packagerPos).heldBox.isEmpty(), "the linked one packed");
                    helper.assertTrue(RecipeSlip.read(packagerAt(helper, packagerPos).heldBox).isEmpty(),
                            "a linked Packager is never stamped");
                    takeBox(packagerAt(helper, packagerPos));
                    helper.setBlock(packagerPos, helper.getBlockState(packagerPos).setValue(PackagerBlock.LINKED, false));
                    // A package in the buffer is re-sent as itself: its old slip is replaced by this station's.
                    ItemStack old = box(COBBLED.toStack(3));
                    RecipeSlip foreign = RecipeSlip.of(Items.DEEPSLATE_TILE_WALL, "elsewhere",
                            GlobalPos.of(helper.getLevel().dimension(), BlockPos.ZERO), 1L);
                    RecipeSlip.stamp(old, foreign);
                    be.insertIntoBufferForTest(old);
                    packagerAt(helper, packagerPos).attemptToSend(null);
                    helper.assertValueEqual(RecipeSlip.read(packagerAt(helper, packagerPos).heldBox), be.currentSlip(),
                            "a re-sent package carries the station's current slip");
                })
                .thenSucceed();
    }

    // --- the capability veto (tests 29, 30, 32, 33, 34, 35, 36) ------------------------------------------------------

    /**
     * The Packager's item capability is the selector's handler exactly while an Ender selector sits on it, Create's own
     * otherwise; a Repackager never gets one.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void slipCapabilityIsOurs(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(factory.handler() instanceof SelectorPackagerItemHandler,
                            "with an Ender selector the Packager answers the selector's handler");
                    helper.setBlock(SELECTOR, Blocks.AIR.defaultBlockState());
                    IItemHandler create = factory.handler();
                    helper.assertTrue(create instanceof PackagerItemHandler
                            && !(create instanceof SelectorPackagerItemHandler), "without one, Create's own: " + create);
                    factory.placeSelector(Optional.of(SAW));
                    helper.assertTrue(factory.handler() instanceof SelectorPackagerItemHandler, "and back");
                    BlockPos repackager = new BlockPos(5, BASE_Y, 5);
                    helper.setBlock(repackager, AllBlocks.REPACKAGER.getDefaultState().setValue(PackagerBlock.FACING,
                            Direction.UP));
                    helper.setBlock(repackager.above(), WareworksBlocks.ENDER_RECIPE_SELECTOR.getDefaultState()
                            .setValue(RecipeSelectorBlock.FACING, Direction.DOWN));
                    helper.assertFalse(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(repackager), null) instanceof SelectorPackagerItemHandler,
                            "a Repackager is never vetoed");
                })
                .thenSucceed();
    }

    /**
     * A box with a slip handed over through the capability: a simulation changes nothing, the real insert sets the
     * machine's filter and only then lets Create unpack; the selector latches to the box's station. A box without a slip
     * leaves the filter alone.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipUnpackViaCapabilitySetsFilterFirst(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        RecipeSlip stairs = factory.slip(Items.DEEPSLATE_TILE_STAIRS, 10L);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ItemStack box = factory.box(stairs, COBBLED.toStack(64));
                    ItemStack rest = factory.handler().insertItem(0, box, true);
                    helper.assertTrue(rest.isEmpty(), "the simulation accepts the box");
                    helper.assertTrue(factory.filter().isEmpty(), "a simulation sets no filter");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 0L, "and unpacks nothing");
                    rest = factory.handler().insertItem(0, box, false);
                    helper.assertTrue(rest.isEmpty(), "the box was taken");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_STAIRS), "the filter is the slip's result");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 64L, "Create unpacked the box");
                    RecipeSelectorBlockEntity selector = factory.selector();
                    helper.assertValueEqual(selector.latch(), Optional.of(stairs.origin()), "latched to the station");
                    helper.assertValueEqual(selector.lastSeq(), 10L, "with its switch");
                    helper.assertValueEqual(selector.slipsRead(), 1, "one slip read");
                })
                .thenIdle(CYCLE_WAIT)
                .thenExecute(() -> {
                    factory.setFilter(Items.DEEPSLATE_TILE_SLAB);
                    factory.clearChest();
                    ItemStack plain = box(COBBLED.toStack(8));
                    helper.assertTrue(factory.handler().insertItem(0, plain, false).isEmpty(),
                            "a box without a slip is let through");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_SLAB), "and leaves the filter alone");
                    helper.assertValueEqual(factory.selector().boxesWithoutSlip(), 1, "counted");
                })
                .thenSucceed();
    }

    /**
     * A box for another product waits in its port while the machine or the Packager's target still holds the last
     * product's material, and is taken once both are empty (design §7.6 step 9). Older and foreign slips are refused; an
     * empty-handed sneak-click clears the selector's station.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipVetoWaitsForEmptyLineStaleAndForeign(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        RecipeSlip stairs = factory.slip(Items.DEEPSLATE_TILE_STAIRS, 10L);
        RecipeSlip slab = factory.slip(Items.DEEPSLATE_TILE_SLAB, 20L);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(factory.handler().insertItem(0, factory.box(stairs, COBBLED.toStack(32)), false)
                            .isEmpty(), "the stairs box is taken");
                })
                .thenIdle(CYCLE_WAIT)
                .thenExecute(() -> {
                    ItemStack box = factory.box(slab, COBBLED.toStack(16));
                    helper.assertValueEqual(factory.handler().insertItem(0, box, false).getCount(), 1,
                            "a slab box is refused while the chest holds the stairs' material");
                    helper.assertValueEqual(factory.selector().status(), RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE,
                            "it waits for an empty line");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_STAIRS), "the filter stays");
                    factory.clearChest();
                    SawBlockEntity saw = factory.saw();
                    saw.inventory.setStackInSlot(0, COBBLED.toStack(1));
                    helper.assertValueEqual(factory.handler().insertItem(0, box, false).getCount(), 1,
                            "and while the saw still holds an item");
                    // Review fix (Create API 5): a port offers the same box again on every retry; it counts once.
                    helper.assertValueEqual(factory.selector().refusedFor(RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE), 1,
                            "the same box refused twice for the same reason is one refused box");
                    saw.inventory.clear();
                    helper.assertTrue(factory.handler().insertItem(0, box, false).isEmpty(),
                            "taken once machine and chest are empty");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_SLAB), "the filter switched first");
                    helper.assertValueEqual(factory.selector().lastSeq(), 20L, "the newer switch");
                })
                .thenIdle(CYCLE_WAIT)
                .thenExecute(() -> {
                    factory.clearChest();
                    ItemStack late = factory.box(stairs, COBBLED.toStack(4));
                    helper.assertValueEqual(factory.handler().insertItem(0, late, false).getCount(), 1,
                            "an older switch's box is refused");
                    helper.assertValueEqual(factory.selector().status(), RecipeSelectorStatus.STALE_SLIP, "stale");
                    RecipeSlip other = new RecipeSlip(stairs.result(), "B-01-00L",
                            GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 1, 6))),
                            99L);
                    ItemStack foreign = factory.box(other, COBBLED.toStack(4));
                    helper.assertValueEqual(factory.handler().insertItem(0, foreign, false).getCount(), 1,
                            "a box of another station is refused");
                    helper.assertValueEqual(factory.selector().status(), RecipeSelectorStatus.FOREIGN_SLIP, "foreign");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 0L, "nothing of either was unpacked");
                    helper.assertTrue(factory.selector().refusedFor(RecipeSelectorStatus.STALE_SLIP) == 1
                            && factory.selector().refusedFor(RecipeSelectorStatus.FOREIGN_SLIP) == 1, "counted once");
                    ClickingPlayer player = ClickingPlayer.at(helper, new BlockPos(5, BASE_Y, 3));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    InteractionResult cleared = click(helper, player, SELECTOR, Direction.UP, true);
                    helper.assertTrue(cleared.consumesAction(), "the empty-handed sneak-click is taken");
                    helper.assertTrue(factory.selector().latch().isEmpty(), "the station was forgotten");
                    helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                            WareworksLang.key(WareworksLang.SELECTOR_LATCH_CLEARED), "and the player told");
                    helper.assertTrue(factory.handler().insertItem(0, foreign, false).isEmpty(),
                            "now the other station's box is taken");
                    helper.assertValueEqual(factory.selector().latch(), Optional.of(other.origin()), "latched anew");
                })
                .thenSucceed();
    }

    /**
     * The veto fails closed and touches nothing (design §7.6 steps 4 to 7): a stale link to a moved saw, a filter
     * item in the saw, a local Recipe Selector on the same saw and a box the saw cannot make the result from are all
     * refused, nothing is unpacked, the filter item stays.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipVetoFailsClosed(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        RecipeSlip stairs = factory.slip(Items.DEEPSLATE_TILE_STAIRS, 10L);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ItemStack logs = factory.box(stairs, LOG.toStack(8));
                    factory.assertRefused(logs, RecipeSelectorStatus.CANNOT_MAKE, "oak logs never make stairs");

                    ItemStack box = factory.box(stairs, COBBLED.toStack(8));
                    BlockPos local = SAW.north();
                    helper.setBlock(local, WareworksBlocks.RECIPE_SELECTOR.getDefaultState()
                            .setValue(RecipeSelectorBlock.FACING, Direction.SOUTH));
                    factory.assertRefused(box, RecipeSelectorStatus.CONTESTED, "a local selector writes the saw");
                    helper.setBlock(local, Blocks.AIR.defaultBlockState());

                    ItemStack listFilter = AllItems.FILTER.asStack();
                    factory.filterBehaviour().setFilter(listFilter);
                    factory.assertRefused(box, RecipeSelectorStatus.FILTER_ITEM, "a filter item is never replaced");
                    helper.assertTrue(factory.filterBehaviour().getFilter().is(AllItems.FILTER.get()),
                            "the List Filter is still in the saw");
                    factory.filterBehaviour().setFilter(ItemStack.EMPTY);

                    helper.setBlock(SAW, Blocks.AIR.defaultBlockState());
                    factory.assertRefused(box, RecipeSelectorStatus.NO_MACHINE,
                            "a link to a saw that is gone never falls back to another machine");
                    helper.assertValueEqual(factory.chestCount(COBBLED) + factory.chestCount(LOG), 0L,
                            "nothing was unpacked");
                })
                .thenSucceed();
    }

    /**
     * Review fix (conservation 6): a Packager handler somebody kept (a port's capability cache) outlives its Ender
     * selector when only the selector's chunk unloads. The box then waits where it is and is never unpacked unjudged.
     * A handler whose selector instance is stale at a loaded position (broken, or replaced after a reload) asks the
     * selector that sits on the Packager now.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipVetoFailsClosedWhileTheSelectorIsUnloaded(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        RecipeSlip stairs = factory.slip(Items.DEEPSLATE_TILE_STAIRS, 10L);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    PackagerBlockEntity packager = packagerAt(helper, PACKAGER);
                    BlockPos far = helper.absolutePos(SELECTOR).offset(100_000, 0, 100_000);
                    helper.assertFalse(level.isLoaded(far), "a chunk no test loads");
                    ItemStack box = factory.box(stairs, COBBLED.toStack(16));
                    ItemStack rest = new SelectorPackagerItemHandler(packager, unloadedSelector(level, far))
                            .insertItem(0, box, false);
                    helper.assertValueEqual(rest.getCount(), box.getCount(), "the box waits for the selector");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 0L, "nothing was unpacked");
                    helper.assertTrue(factory.filter().isEmpty(), "and the saw was not touched");

                    RecipeSelectorBlockEntity stale = unloadedSelector(level, helper.absolutePos(SELECTOR));
                    rest = new SelectorPackagerItemHandler(packager, stale).insertItem(0, box, false);
                    helper.assertTrue(rest.isEmpty(), "taken");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_STAIRS),
                            "after the selector on the Packager now set the filter");
                    helper.assertValueEqual(factory.selector().slipsRead(), 1, "it read the slip");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 16L, "then Create unpacked the box");
                })
                .thenSucceed();
    }

    /** An Ender selector at {@code pos} as a chunk unload leaves it: unloaded, then removed. */
    private static RecipeSelectorBlockEntity unloadedSelector(ServerLevel level, BlockPos pos) {
        RecipeSelectorBlockEntity selector = new RecipeSelectorBlockEntity(
                WareworksBlockEntityTypes.RECIPE_SELECTOR.get(), pos, WareworksBlocks.ENDER_RECIPE_SELECTOR
                        .getDefaultState().setValue(RecipeSelectorBlock.FACING, Direction.DOWN));
        selector.setLevel(level);
        selector.onChunkUnloaded();
        selector.setRemoved();
        return selector;
    }

    /** A Packager that unpacks straight into a Basin: without a machine link the Basin is the selector's machine. */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipPackagerIntoBasin(GameTestHelper helper) {
        helper.setBlock(CHEST, AllBlocks.BASIN.getDefaultState());
        helper.setBlock(PACKAGER, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.UP));
        helper.setBlock(SELECTOR, WareworksBlocks.ENDER_RECIPE_SELECTOR.getDefaultState()
                .setValue(RecipeSelectorBlock.FACING, Direction.DOWN));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    selectorAt(helper, SELECTOR).placedAsEnder(Optional.empty());
                    RecipeSlip gray = RecipeSlip.of(Items.GRAY_DYE, "A-00-00R",
                            GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(BlockPos.ZERO)), 5L);
                    ItemStack box = box(new ItemStack(Items.BLACK_DYE, 4), new ItemStack(Items.WHITE_DYE, 4));
                    RecipeSlip.stamp(box, gray);
                    IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(PACKAGER), null);
                    helper.assertTrue(handler instanceof SelectorPackagerItemHandler, "the selector's handler");
                    helper.assertTrue(handler.insertItem(0, box, false).isEmpty(), "the dye box is taken");
                    FilteringBehaviour filter = BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(CHEST),
                            FilteringBehaviour.TYPE);
                    helper.assertTrue(filter.getFilter().is(Items.GRAY_DYE), "the Basin's filter is gray dye");
                })
                .thenSucceed();
    }

    // --- the hand (test 31) -----------------------------------------------------------------------------------------

    /**
     * A box handed over by hand is judged the same way: a refused one stays in the hand and the player is told why; an
     * accepted one sets the filter and Create unpacks it from the hand.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void slipUnpackByHand(GameTestHelper helper) {
        Factory factory = new Factory(helper, true);
        RecipeSlip stairs = factory.slip(Items.DEEPSLATE_TILE_STAIRS, 10L);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ClickingPlayer player = ClickingPlayer.at(helper, new BlockPos(5, BASE_Y, 3));
                    player.setItemInHand(InteractionHand.MAIN_HAND, factory.box(stairs, LOG.toStack(8)));
                    InteractionResult refused = click(helper, player, PACKAGER, Direction.EAST, false);
                    helper.assertValueEqual(refused, InteractionResult.FAIL, "a box the saw cannot use is refused");
                    helper.assertFalse(player.getMainHandItem().isEmpty(), "and stays in the hand");
                    helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                            WareworksLang.key(RecipeSelectorStatus.CANNOT_MAKE.langKey()), "the player is told why");
                    helper.assertValueEqual(factory.chestCount(LOG), 0L, "nothing was unpacked");

                    player.setItemInHand(InteractionHand.MAIN_HAND, factory.box(stairs, COBBLED.toStack(16)));
                    click(helper, player, PACKAGER, Direction.EAST, false);
                    helper.assertTrue(player.getMainHandItem().isEmpty(), "Create took the box from the hand");
                    helper.assertTrue(factory.filterIs(Items.DEEPSLATE_TILE_STAIRS), "after the filter was set");
                    helper.assertValueEqual(factory.chestCount(COBBLED), 16L, "and unpacked it");
                    helper.assertValueEqual(factory.selector().slipsRead(), 1, "one slip read");
                })
                .thenSucceed();
    }

    // --- placement (test 38) ----------------------------------------------------------------------------------------

    /**
     * The Ender selector is sneak-placed on an unpacking Packager: a plain click places nothing (Create's click), a
     * second one on the same Packager, one against anything else and one in another chunk than the Packager are
     * refused; the item's machine link goes into the placed block.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void enderSelectorSneakPlacesOnPackager(GameTestHelper helper) {
        // A Packager whose east neighbour lies in the next chunk: the template is 16 blocks wide, so one x has it.
        int boundaryX = -1;
        for (int x = 1; x <= 13 && boundaryX < 0; x++) {
            BlockPos absolute = helper.absolutePos(new BlockPos(x, BASE_Y, 5));
            if (new ChunkPos(absolute).x != new ChunkPos(absolute.east()).x)
                boundaryX = x;
        }
        BlockPos packagerPos = new BlockPos(boundaryX < 0 ? 6 : (boundaryX == 6 || boundaryX == 7 ? 10 : 6), BASE_Y, 2);
        BlockPos sawPos = packagerPos.west(3);
        BlockPos stone = packagerPos.south(4);
        helper.setBlock(packagerPos.below(), Blocks.CHEST.defaultBlockState());
        helper.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.UP));
        helper.setBlock(sawPos, upSaw());
        helper.setBlock(stone, Blocks.STONE.defaultBlockState());
        final int boundary = boundaryX;
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ClickingPlayer player = ClickingPlayer.at(helper, packagerPos.south().south());
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(WareworksBlocks.ENDER_RECIPE_SELECTOR.asItem(), 4));
                    // Link the machine first, with a real sneak-click on the saw.
                    click(helper, player, sawPos, Direction.UP, true);
                    SelectorLink link = player.getMainHandItem().get(WareworksDataComponents.SELECTOR_LINK.get());
                    helper.assertTrue(link != null && link.machine().map(GlobalPos::pos)
                            .equals(Optional.of(helper.absolutePos(sawPos))), "the item is linked to the saw");
                    helper.assertValueEqual(link.kind(), Optional.of(SelectorMachine.Kind.SAW), "as a saw");

                    click(helper, player, packagerPos, Direction.UP, false);
                    helper.assertTrue(helper.getBlockState(packagerPos.above()).isAir(), "a plain click places nothing");
                    helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                            WareworksLang.key(WareworksLang.SELECTOR_SNEAK_TO_PLACE), "and says to sneak");

                    click(helper, player, stone, Direction.UP, true);
                    helper.assertTrue(helper.getBlockState(stone.above()).isAir(), "not on stone");
                    helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                            WareworksLang.key(WareworksLang.SELECTOR_NEEDS_PACKAGER), "it needs a Packager");

                    click(helper, player, packagerPos, Direction.UP, true);
                    helper.assertTrue(helper.getBlockState(packagerPos.above())
                            .is(WareworksBlocks.ENDER_RECIPE_SELECTOR.get()), "a sneak-click places it on top");
                    RecipeSelectorBlockEntity placed = selectorAt(helper, packagerPos.above());
                    helper.assertValueEqual(placed.machineLink(), Optional.of(helper.absolutePos(sawPos)),
                            "with the item's machine link");
                    helper.assertValueEqual(placed.facing(), Direction.DOWN, "facing the Packager");
                    helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(packagerPos), null) instanceof SelectorPackagerItemHandler,
                            "and the Packager is vetoed now");

                    click(helper, player, packagerPos, Direction.NORTH, true);
                    helper.assertTrue(helper.getBlockState(packagerPos.north()).isAir(), "one per Packager");
                    helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                            WareworksLang.key(WareworksLang.SELECTOR_REFUSED_PACKAGER_TAKEN), "taken");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "only one item was used");

                    if (boundary >= 0) {
                        BlockPos edge = new BlockPos(boundary, BASE_Y, 5);
                        helper.setBlock(edge.below(), Blocks.CHEST.defaultBlockState());
                        helper.setBlock(edge, AllBlocks.PACKAGER.getDefaultState()
                                .setValue(PackagerBlock.FACING, Direction.UP));
                        click(helper, player, edge, Direction.EAST, true);
                        helper.assertTrue(helper.getBlockState(edge.east()).isAir(), "not across a chunk border");
                        helper.assertValueEqual(player.toldKeys().get(player.toldKeys().size() - 1),
                                WareworksLang.key(WareworksLang.SELECTOR_REFUSED_OTHER_CHUNK), "other chunk");
                    }
                })
                .thenSucceed();
    }

    // --- the warehouse side (tests 26, 39, 40, 42) ----------------------------------------------------------------------

    /**
     * Slip-mode faults hold the head with a running deadline (design §5.3): no return input, no Packager, a linked
     * Packager. And a switch waits while any Packager of the station holds a box (PACKAGER_BUSY, test 26).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void slipModeFaultsAndBusyPackager(GameTestHelper helper) {
        SlipLine line = new SlipLine(helper, false);
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    helper.assertValueEqual(line.station().setSelectorMode(SelectorMode.SLIPS, Optional.empty()),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "slip mode");
                    id[0] = line.order(PLANK, 4);
                    line.assertHeadWait(id[0], SelectorWait.NO_RETURN_INPUT);
                    line.bindReturn();
                    line.assertHeadWait(id[0], SelectorWait.NO_PACKAGER);
                    line.placePackager();
                    line.assertHeadWait(id[0], SelectorWait.SWITCHING);
                    helper.setBlock(line.packagerPos(), helper.getBlockState(line.packagerPos())
                            .setValue(PackagerBlock.LINKED, true));
                    line.assertHeadWait(id[0], SelectorWait.PACKAGER_LINKED);
                    helper.setBlock(line.packagerPos(), helper.getBlockState(line.packagerPos())
                            .setValue(PackagerBlock.LINKED, false));
                    // A box in the Packager: the line must not switch while it is there.
                    line.packager().heldBox = box(LOG.toStack(1));
                    line.assertHeadWait(id[0], SelectorWait.PACKAGER_BUSY);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(line.station().selection().isEmpty(), "no switch while the Packager holds a box");
                    line.assertHeadWait(id[0], SelectorWait.PACKAGER_BUSY);
                    line.packager().heldBox = ItemStack.EMPTY;
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(PLANK),
                        "the line switched once the Packager was empty"))
                .thenSucceed();
    }

    /**
     * The probe (design §7.5): after slips are switched on, the first order fetches one run only until that run has
     * come back through the return input as the right product; then the rest follows and the line is trusted, so the
     * next switch is not probed. With {@code EVERY_SWITCH} every switch probes, with {@code OFF} none.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void slipProbeOneRunFirst(GameTestHelper helper) {
        SlipLine line = new SlipLine(helper, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the factory is played"));
        UUID[] ids = new UUID[4];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.slipsOn();
                    ids[0] = line.order(PLANK, 8);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selectorProbeOrder(),
                        Optional.of(ids[0]), "the first order after slips were switched on is the probe"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the probe fetched one run"))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "and only one run");
                    helper.assertValueEqual(line.waitOf(ids[0]), SelectorWait.PROBE_ONE_RUN, "the probe's wait");
                    line.playFactory(census, LOG, PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertTrue(line.station().selectorSlipsTrusted()
                        && line.station().selectorProbeOrder().isEmpty(), "the right product came back: trusted"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the rest is fetched"))
                .thenExecute(() -> line.playFactory(census, LOG, PLANK, 4))
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> ids[1] = line.order(STICK, 2))
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(STICK),
                        "the line switched to sticks"))
                .thenExecute(() -> helper.assertTrue(line.station().selectorProbeOrder().isEmpty(),
                        "a trusted line is not probed again"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "sticks"))
                .thenExecute(() -> line.playFactory(census, LOG, STICK, 2))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.selectorRemoteProbe,
                            WareworksConfig.RemoteProbe.EVERY_SWITCH);
                    ids[2] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selectorProbeOrder(),
                        Optional.of(ids[2]), "EVERY_SWITCH probes a trusted line too"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "planks"))
                .thenExecute(() -> line.playFactory(census, LOG, PLANK, 4))
                .thenWaitUntil(() -> line.assertState(ids[2], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.selectorRemoteProbe,
                            WareworksConfig.RemoteProbe.OFF);
                    ids[3] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(STICK),
                        "switched to sticks"))
                .thenExecute(() -> helper.assertTrue(line.station().selectorProbeOrder().isEmpty(), "OFF never probes"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "sticks"))
                .thenExecute(() -> line.playFactory(census, LOG, STICK, 2))
                .thenWaitUntil(() -> line.assertState(ids[3], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /**
     * Re-review fix (design §7.5): a probe order of a single run ends with its first arrival, before the station's pass
     * could see it come back. It still makes the line trusted, so the next switch is not probed (the default
     * {@code UNTRUSTED}).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void slipProbeOfOneRunTrustsTheLine(GameTestHelper helper) {
        SlipLine line = new SlipLine(helper, true);
        Map<ItemKey, Long> census = ItemCensus.of(LOG, LOGS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, census, "while the factory is played"));
        UUID[] ids = new UUID[2];
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    helper.assertValueEqual(WareworksConfig.selectorRemoteProbe(),
                            WareworksConfig.RemoteProbe.UNTRUSTED, "the default probe setting");
                    line.slipsOn();
                    ids[0] = line.order(PLANK, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selectorProbeOrder(),
                        Optional.of(ids[0]), "the one-run order is the probe"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L,
                        "the probe fetched its run"))
                .thenExecute(() -> line.playFactory(census, LOG, PLANK, 4))
                .thenWaitUntil(() -> line.assertState(ids[0], ProductionOrderState.COMPLETE))
                .thenExecute(() -> {
                    helper.assertTrue(line.station().selectorSlipsTrusted(), "its right product made the line trusted");
                    helper.assertTrue(line.station().selectorProbeOrder().isEmpty(), "and the probe is over");
                    ids[1] = line.order(STICK, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(line.station().selection(), Optional.of(STICK),
                        "the line switched to sticks"))
                .thenExecute(() -> helper.assertTrue(line.station().selectorProbeOrder().isEmpty(),
                        "a trusted line is not probed again"))
                .thenWaitUntil(() -> helper.assertValueEqual(line.aisle.stationCount(STATION, LOG), 1L, "sticks"))
                .thenExecute(() -> line.playFactory(census, LOG, STICK, 2))
                .thenWaitUntil(() -> line.assertState(ids[1], ProductionOrderState.COMPLETE))
                .thenSucceed();
    }

    /**
     * A hold at a slip station is released only once no box of the station is still on its way out (design §8): a box
     * in the station's Packager, or one with this station's slip in an inventory beside the Packager, refuses it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void slipHoldReleaseSeesPortBox(GameTestHelper helper) {
        SlipLine line = new SlipLine(helper, true);
        helper.startSequence()
                .thenWaitUntil(line::assertReady)
                .thenExecute(() -> {
                    line.slipsOn();
                    WarehouseProductionBlockEntity station = line.station();
                    station.selectorSwitched(PLANK, helper.getLevel().getGameTime());
                    station.armSelectorHold(SelectorHold.lostIngredients(PLANK, UUID.randomUUID(),
                            helper.getLevel().getGameTime(), 1));
                    line.packager().heldBox = box(LOG.toStack(1));
                    helper.assertValueEqual(line.controller().releaseSelectorHold(station).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.REFUSED_PACKAGER,
                            "a box in the Packager refuses the release");
                    line.packager().heldBox = ItemStack.EMPTY;
                    BlockPos port = line.packagerPos().above();
                    helper.setBlock(port, Blocks.CHEST.defaultBlockState());
                    ItemStack box = box(LOG.toStack(1));
                    RecipeSlip.stamp(box, station.currentSlip().orElseThrow());
                    IItemHandler chest = line.aisle.handlerAt(port);
                    chest.insertItem(0, box, false);
                    helper.assertValueEqual(line.controller().releaseSelectorHold(station).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.REFUSED_PORT,
                            "a box of this station beside the Packager refuses it");
                    chest.extractItem(0, 1, false);
                    helper.assertValueEqual(line.controller().releaseSelectorHold(station).outcome(),
                            WarehouseControllerBlockEntity.SelectorRelease.Outcome.RELEASED, "then it is released");
                    helper.assertValueEqual(station.selection(), Optional.of(PLANK), "with the selection kept");
                })
                .thenSucceed();
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------

    /** A Create package holding {@code contents}. */
    private static ItemStack box(ItemStack... contents) {
        return PackageItem.containing(List.of(contents));
    }

    /** Takes the box out of a Packager, as a port would once the tray has come to rest. */
    private static void takeBox(PackagerBlockEntity packager) {
        packager.heldBox = ItemStack.EMPTY;
        packager.animationTicks = 0;
    }

    private static net.minecraft.world.level.block.state.BlockState upSaw() {
        return AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true);
    }

    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockPos pos, Direction face,
            boolean sneak) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).relative(face, 0.5), face, absolute, false);
        player.setShiftKeyDown(sneak);
        try {
            return player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(),
                    InteractionHand.MAIN_HAND, hit);
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    private static WarehouseProductionBlockEntity stationAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station)
            return station;
        helper.fail("no production station", pos);
        throw new IllegalStateException("unreachable");
    }

    private static PackagerBlockEntity packagerAt(GameTestHelper helper, BlockPos pos) {
        PackagerBlockEntity be = AllBlockEntityTypes.PACKAGER.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("no Packager", pos);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    private static RecipeSelectorBlockEntity selectorAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector)
            return selector;
        helper.fail("no Recipe Selector", pos);
        throw new IllegalStateException("unreachable");
    }

    /** The factory rig of the class comment. */
    private static final class Factory {
        final GameTestHelper helper;

        Factory(GameTestHelper helper, boolean linked) {
            this.helper = helper;
            helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
            helper.setBlock(PACKAGER, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,
                    Direction.UP));
            helper.setBlock(SAW, upSaw());
            placeSelector(linked ? Optional.of(SAW) : Optional.empty());
        }

        void placeSelector(Optional<BlockPos> machine) {
            helper.setBlock(SELECTOR, WareworksBlocks.ENDER_RECIPE_SELECTOR.getDefaultState()
                    .setValue(RecipeSelectorBlock.FACING, Direction.DOWN));
            selector().placedAsEnder(machine.map(helper::absolutePos));
        }

        RecipeSelectorBlockEntity selector() {
            return selectorAt(helper, SELECTOR);
        }

        IItemHandler handler() {
            return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(PACKAGER), null);
        }

        RecipeSlip slip(net.minecraft.world.item.Item result, long seq) {
            return RecipeSlip.of(result, "A-00-00R", GlobalPos.of(helper.getLevel().dimension(),
                    helper.absolutePos(BlockPos.ZERO)), seq);
        }

        ItemStack box(RecipeSlip slip, ItemStack... contents) {
            ItemStack box = RecipeSlipGameTests.box(contents);
            RecipeSlip.stamp(box, slip);
            return box;
        }

        SawBlockEntity saw() {
            if (helper.getBlockEntity(SAW) instanceof SawBlockEntity saw)
                return saw;
            helper.fail("no saw", SAW);
            throw new IllegalStateException("unreachable");
        }

        FilteringBehaviour filterBehaviour() {
            return BlockEntityBehaviour.get(saw(), FilteringBehaviour.TYPE);
        }

        ItemStack filter() {
            return filterBehaviour().getFilter();
        }

        boolean filterIs(net.minecraft.world.item.Item item) {
            return filter().is(item);
        }

        void setFilter(net.minecraft.world.item.Item item) {
            filterBehaviour().setFilter(new ItemStack(item));
        }

        IItemHandler chest() {
            return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(CHEST), null);
        }

        long chestCount(ItemKey key) {
            IItemHandler chest = chest();
            long count = 0;
            for (int slot = 0; slot < chest.getSlots(); slot++) {
                if (key.matches(chest.getStackInSlot(slot)))
                    count += chest.getStackInSlot(slot).getCount();
            }
            return count;
        }

        void clearChest() {
            IItemHandler chest = chest();
            for (int slot = 0; slot < chest.getSlots(); slot++)
                chest.extractItem(slot, Integer.MAX_VALUE, false);
        }

        /** {@code box} is refused for {@code reason} by a real insert, and nothing reaches the chest. */
        void assertRefused(ItemStack box, RecipeSelectorStatus reason, String message) {
            long before = chestCount(COBBLED) + chestCount(LOG);
            ItemStack rest = handler().insertItem(0, box, false);
            helper.assertValueEqual(rest.getCount(), box.getCount(), message + ": the box stays where it was");
            helper.assertValueEqual(selector().status(), reason, message);
            helper.assertValueEqual(chestCount(COBBLED) + chestCount(LOG), before, message + ": nothing unpacked");
        }
    }

    /** The warehouse line of the class comment: a station in slip mode with a Packager behind it. */
    private static final class SlipLine {
        final GameTestHelper helper;
        final AisleFixture aisle;

        SlipLine(GameTestHelper helper, boolean packager) {
            this.helper = helper;
            aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
            aisle.motor().generatedSpeed.setValue(TEST_RPM);
            aisle.storage(LOGS, LOG.toStack(LOGS_IN_STOCK));
            aisle.storage(PRODUCTS);
            aisle.storage(MORE_PRODUCTS);
            aisle.input(RETURN);
            aisle.output(OUTPUT);
            aisle.production(STATION);
            WarehouseProductionBlockEntity station = station();
            helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "ingredient");
            helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4), "result");
            helper.assertTrue(station.setPatternEntry(1, 0, LOG, 1), "ingredient");
            helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, STICK, 2), "result");
            if (packager)
                placePackager();
        }

        BlockPos packagerPos() {
            return aisle.inventoryPos(STATION);
        }

        /** An unpowered Packager behind the station, facing away from it: it would pack what the station holds. */
        void placePackager() {
            helper.setBlock(packagerPos(), AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,
                    aisle.sideDirection(STATION)));
            controller().onSelectorStationChanged(aisle.absoluteRackPos(STATION));
        }

        PackagerBlockEntity packager() {
            return packagerAt(helper, packagerPos());
        }

        WarehouseControllerBlockEntity controller() {
            return aisle.controller();
        }

        WarehouseProductionBlockEntity station() {
            return aisle.productionAt(STATION);
        }

        void assertReady() {
            helper.assertValueEqual(controller().countOf(LOG), (long) LOGS_IN_STOCK, "logs in stock");
            helper.assertValueEqual(controller().productionStations().size(), 1, "the station is recorded");
        }

        void bindReturn() {
            helper.assertValueEqual(controller().bindSelectorReturnInput(station(), aisle.absoluteRackPos(RETURN)),
                    WarehouseControllerBlockEntity.ReturnInputBinding.BOUND, "the return input");
        }

        /** Slip mode (no machine kind: the stand-in recipes are checked by nobody) with the return input bound. */
        void slipsOn() {
            helper.assertValueEqual(station().setSelectorMode(SelectorMode.SLIPS, Optional.empty()),
                    WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "slip mode");
            bindReturn();
        }

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
            helper.assertTrue(order.isPresent(), "order " + id + " is known");
            helper.assertValueEqual(order.get().state(), state, "order state");
        }

        SelectorWait waitOf(UUID id) {
            return controller().waitsForSelector(controller().productionOrder(id).orElseThrow());
        }

        /** The station's decision is recomputed now, and {@code id} is its head waiting for {@code wait}. */
        void assertHeadWait(UUID id, SelectorWait wait) {
            controller().onSelectorStationChanged(aisle.absoluteRackPos(STATION));
            Optional<SelectorGate.Decision<ItemKey>> decision = controller().selectorDecision(
                    aisle.absoluteRackPos(STATION));
            helper.assertTrue(decision.isPresent(), "the station has a decision");
            helper.assertValueEqual(decision.get().waitOf(id), wait, "the head's wait");
            helper.assertFalse(decision.get().admitted().contains(id), "it fetches nothing");
            helper.assertFalse(decision.get().paused().contains(id), "and its deadline runs");
        }

        /** The far factory: takes every {@code in} out of the station and returns {@code made} {@code out}. */
        void playFactory(Map<ItemKey, Long> census, ItemKey in, ItemKey out, int made) {
            IItemHandler station = aisle.handlerAt(aisle.rackPos(STATION));
            int taken = 0;
            for (int slot = 0; slot < station.getSlots(); slot++) {
                if (in.matches(station.getStackInSlot(slot)))
                    taken += station.extractItem(slot, Integer.MAX_VALUE, false).getCount();
            }
            helper.assertValueEqual(taken, 1, "one run's ingredient was in the station");
            ItemCensus.change(census, in, -taken);
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(RETURN)), out.toStack(made));
            ItemCensus.change(census, out, made);
        }
    }
}
