package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the <b>readouts</b> ({@code docs/warehouse-system.md} §3.9, §5, M30 step 10, issue #21, D9 and D10):
 * what a warehouse says it holds as fluid, on the controller itself.
 * <p>
 * The display sources that read the same index are tested where the other five are, in
 * {@link DisplayLinkGameTests} — they need a real Display Link and a real target, and this holder needs neither.
 *
 * <h2>The two things worth pinning</h2>
 * <b>A parallel index, never a union key.</b> The fluid numbers are their own two fields on
 * {@link ControllerGoggleSummary} and their own two lines on the tooltip, because {@code StockView#totalItems()} and
 * {@code distinctKeys()} feed {@code ITEM_TYPES} and {@code TOTAL_ITEMS}, which a player has read since M5. The
 * warehouse of {@link #awarehousesaysitholdsfluid} holds <b>lava, water and an empty bucket</b> at once, so a readout
 * that mixed the two indexes could not pass: the item lines would move the moment a bay was filled.
 * <p>
 * <b>An item-only warehouse is unchanged, provably.</b> {@link #anitemonlywarehousesaysnothingaboutfluid} asserts the
 * summary <i>and</i> the synced tag: the two fields are left out of the tag entirely while the warehouse holds no
 * fluid, which is every warehouse built before M30, so neither its chunk packet nor its tooltip grew by a byte.
 *
 * <h2>Why the lines are asserted as keys and arguments</h2>
 * {@code LangBuilder#forGoggles} measures the <b>client</b> font, which a dedicated GameTest server has not got, so the
 * rows themselves cannot be drawn here. What can be asserted is the component the controller would draw — its lang key
 * and its arguments — which is where every decision of D9 lives: buckets rather than millibuckets, two fraction digits,
 * and the millibucket fallback for an amount a bucket figure would round to zero.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidReadoutGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TIMEOUT_TICKS = 1200;

    private static final RackPosition CHEST_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition LAVA_BAY = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition WATER_BAY = new RackPosition(3, 0, Side.LEFT);

    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final ItemKey EMPTY_BUCKET = ItemKey.of(Items.BUCKET);

    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;
    /** What the lava bay is given: a round number of buckets, and more than the water bay, so the order is decided. */
    private static final int LAVA_MB = 48 * BUCKET_MB;
    private static final int WATER_MB = 12 * BUCKET_MB;
    /** Empty buckets in the chest, so the item numbers are not zero while the fluid numbers are not either. */
    private static final int STORED_BUCKETS = 17;
    /**
     * Less than one hundredth of a bucket, which a goggle line's two fraction digits would print as {@code 0}: the
     * amount that has to switch the row to millibuckets, and an amount a Create pipe network really leaves behind
     * (it moves as little as 1 mB a tick).
     */
    private static final int A_DROP_MB = 7;

    /** The bound {@code WarehouseControllerGameTests} guards the summary of a warehouse nobody named with. */
    private static final int MAX_SUMMARY_SYNC_BYTES = 2048;
    /**
     * What the two <b>fluid</b> fields may add to that budget at the very worst, which is what is really guarded here
     * (M30, issue #21) — the reading {@code MAX_NAME_SYNC_BYTES} is written on, for the other opt-in state.
     * <p>
     * The tight bound above has <b>no room left</b>: this fixture's tag is about 1 929 accounting bytes before a drop
     * of fluid, and the two keys add a <b>measured</b> 210 of them, because {@code CompoundTag#sizeInBytes} charges a
     * per-entry overhead plus two bytes per character of the key — far more than the twelve bytes an {@code int} and a
     * {@code long} really send. So the whole-tag budget is not what this step has to keep; what it has to keep is that
     * a warehouse holding fluid pays for exactly <b>two</b> numbers and that a warehouse holding none pays nothing at
     * all, and both of those are measured rather than estimated.
     * <p>
     * The slack is deliberately smaller than one more entry of any name, so a third field cannot be added without this
     * number being looked at.
     */
    private static final int MAX_FLUID_SYNC_BYTES = 224;

    private FluidReadoutGameTests() {
    }

    /**
     * A warehouse holding two fluids and an item says all of it: {@code lava: 48 buckets}, {@code water: 12 buckets}
     * and {@code bucket: 17}, with nothing pretending one is the other (issue #21's own answer).
     * <p>
     * It walks the whole chain of the readout in one test, because every link of it is where a mix-up would show:
     * <ul>
     * <li>the parallel index itself — per fluid, per bay, and the totals;</li>
     * <li>the <b>item</b> numbers, which must be about the chest alone however much fluid the bays hold;</li>
     * <li>the two new summary fields, and the four unchanged ones beside them ({@link ControllerGoggleSummary#counts}
     * builds the item-only summary and {@code withFluid} adds the fluid to it, so a swap of two components fails);</li>
     * <li>the synced tag: what the two fields really cost it, measured rather than estimated, and read back by a
     * detached client copy as the same summary;</li>
     * <li>the two goggle lines as the controller would draw them — the types count, and the stored amount in
     * <b>buckets</b> with two fraction digits.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void awarehousesaysitholdsfluid(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> {
                    fill(helper, aisle, LAVA_BAY, LAVA, LAVA_MB);
                    fill(helper, aisle, WATER_BAY, WATER, WATER_MB);
                })
                // The bays report their change, so the controller reads them again on its next tick; the round robin
                // would come round within a snapshot cycle anyway.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().distinctKeys(), 2,
                        "both bays indexed"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    StockView<FluidKey, RackPosition> fluid = controller.fluidStockIndex();
                    helper.assertValueEqual(fluid.count(LAVA), (long) LAVA_MB, "lava in the warehouse");
                    helper.assertValueEqual(fluid.count(WATER), (long) WATER_MB, "water in the warehouse");
                    helper.assertValueEqual(fluid.totalItems(), (long) (LAVA_MB + WATER_MB), "fluid in all");
                    helper.assertValueEqual(fluid.countAt(LAVA, LAVA_BAY), (long) LAVA_MB, "lava at its own bay");
                    helper.assertValueEqual(fluid.countAt(LAVA, WATER_BAY), 0L, "and nowhere else");
                    helper.assertValueEqual(fluid.locations(), List.of(LAVA_BAY, WATER_BAY),
                            "the bays, in rack position order");

                    // The item index is untouched by all of it: one chest, one item type, seventeen buckets.
                    StockView<ItemKey, RackPosition> items = controller.stockIndex();
                    helper.assertValueEqual(items.distinctKeys(), 1, "item types");
                    helper.assertValueEqual(items.totalItems(), (long) STORED_BUCKETS, "items stored");
                    helper.assertValueEqual(items.countsAt(LAVA_BAY), Map.of(), "a fluid bay holds no items");

                    controller.onGoggleObserved();
                    ControllerGoggleSummary shown = controller.summary().withoutCrane();
                    // Three storage locations (the chest and the two bays), one item type, seventeen items, and the
                    // two fluid numbers on top. Built as "the item-only summary plus the fluid", so neither half can
                    // silently take the other's place.
                    helper.assertValueEqual(shown, expectedSummary(aisle).withFluid(2, LAVA_MB + WATER_MB),
                            "the summary of a warehouse holding fluid");

                    // What the two fields really cost, isolated: the same summary written with and without them. The
                    // crane keeps working while this test runs, so the whole update tag is not a stable thing to
                    // subtract two numbers out of; the summary's own tag is.
                    CompoundTag withFluid = new CompoundTag();
                    shown.write(withFluid);
                    CompoundTag withoutFluid = new CompoundTag();
                    shown.withFluid(0, 0L).write(withoutFluid);
                    int cost = withFluid.sizeInBytes() - withoutFluid.sizeInBytes();
                    helper.assertTrue(cost > 0, "a warehouse holding fluid has to send the two numbers");
                    helper.assertTrue(cost <= MAX_FLUID_SYNC_BYTES,
                            "the two fluid numbers may cost at most " + MAX_FLUID_SYNC_BYTES + " accounting bytes of "
                                    + "every chunk packet, but cost " + cost);

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag updateTag = controller.getUpdateTag(registries);
                    WarehouseControllerBlockEntity client = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(controller.getBlockPos(), controller.getBlockState());
                    if (client == null)
                        helper.fail("could not create a detached warehouse controller");
                    client.handleUpdateTag(updateTag, registries);
                    helper.assertValueEqual(client.summary().fluidTypes(), 2, "fluid types after client sync");
                    helper.assertValueEqual(client.summary().fluidMillibuckets(), (long) (LAVA_MB + WATER_MB),
                            "fluid stored after client sync");

                    // The two lines, as the controller builds them for its tooltip.
                    assertLine(helper, WareworksLang.countLine(WareworksLang.GOGGLES_FLUID_TYPES, 2).component(),
                            WareworksLang.GOGGLES_FLUID_TYPES, "2", "the fluid types line");
                    // Buckets and not millibuckets (D9): 60 000 mB reads "60.00", and the unit is in the text.
                    assertLine(helper, WareworksLang.fluidStored(LAVA_MB + WATER_MB).component(),
                            WareworksLang.GOGGLES_FLUID_STORED,
                            number((LAVA_MB + WATER_MB) / (double) FluidBayTier.MILLIBUCKETS_PER_BUCKET),
                            "the fluid stored line");
                })
                .thenExecute(() -> retire(helper, aisle))
                .thenSucceed();
    }

    /**
     * A warehouse of chests says <b>nothing at all</b> about fluid: both numbers are 0, both keys are absent from the
     * synced tag, and the summary is the one {@link ControllerGoggleSummary#counts} builds without naming them.
     * <p>
     * This is the guard the whole step rests on, and it is asserted on the <b>tag</b> and not only on the record: the
     * controller's update tag is part of every chunk packet, so a feature no warehouse built before M30 uses must not
     * put a byte in it. The two goggle lines sit behind the same condition, which is why "the tooltip is unchanged" and
     * "the packet is unchanged" are one assertion here.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void anitemonlywarehousesaysnothingaboutfluid(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(CHEST_RACK, EMPTY_BUCKET.toStack(STORED_BUCKETS));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.fluidStockIndex().distinctKeys(), 0, "no fluid is indexed");
                    helper.assertValueEqual(controller.fluidStockIndex().totalItems(), 0L, "and no millibucket");
                    controller.onGoggleObserved();
                    ControllerGoggleSummary shown = controller.summary().withoutCrane();
                    helper.assertValueEqual(shown.fluidTypes(), 0, "fluid types of an item-only warehouse");
                    helper.assertValueEqual(shown.fluidMillibuckets(), 0L, "fluid stored of an item-only warehouse");
                    helper.assertValueEqual(shown, ControllerGoggleSummary.counts(ControllerStatus.READY, RAILS,
                            defaultMastHeight(), 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, STORED_BUCKETS, 0, 0, 0, 0,
                            0, 0), "the summary a warehouse without a fluid bay reports");

                    CompoundTag tag = new CompoundTag();
                    shown.write(tag);
                    helper.assertFalse(tag.contains("FluidTypes"), "no fluid type count travels");
                    helper.assertFalse(tag.contains("FluidMillibuckets"), "and no millibucket count either");
                    helper.assertValueEqual(ControllerGoggleSummary.read(tag).fluidTypes(), 0,
                            "a tag without the keys reads back as no fluid");
                    // And the packet such a controller really sends is inside the budget it was inside before M30.
                    CompoundTag updateTag = controller.getUpdateTag(helper.getLevel().registryAccess());
                    helper.assertTrue(updateTag.sizeInBytes() < MAX_SUMMARY_SYNC_BYTES,
                            "an item-only controller's update tag must stay inside its old budget, but has "
                                    + updateTag.sizeInBytes() + " bytes");
                })
                .thenSucceed();
    }

    /**
     * The index follows the bay, in both directions, and a warehouse holding a <b>drop</b> says so instead of saying
     * nothing.
     * <p>
     * Three states, because each of them is a line a player would otherwise read wrong:
     * <ul>
     * <li>a bay drained to {@value #A_DROP_MB} mB still reports one fluid type, and the stored row switches to
     * millibuckets — a warehouse that holds something must never report 0, and a pipe network moves as little as 1 mB
     * a tick, so this is a state a real warehouse passes through every time it is drained;</li>
     * <li>an <b>empty</b> bay drops out of the index entirely, which is what makes the two goggle lines disappear
     * again; it is {@code BayContents} forgetting its key with the last drop, the same rule that lets an unfiltered
     * bay take whatever arrives next;</li>
     * <li>a bay that is <b>taken away</b> takes its entry with it, so the number cannot outlive the block.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void thefluidreadoutfollowsthebay(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 0, 0))
                .thenExecute(() -> fill(helper, aisle, LAVA_BAY, LAVA, LAVA_MB))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(LAVA),
                        (long) LAVA_MB, "the lava is indexed"))
                .thenExecute(() -> {
                    FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(LAVA_BAY));
                    FluidStack drained = bay.drain(LAVA_MB - A_DROP_MB, false);
                    helper.assertValueEqual(drained.getAmount(), LAVA_MB - A_DROP_MB, "a pipe draws the bay down");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(LAVA),
                        (long) A_DROP_MB, "a drop is still fluid the warehouse holds"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().fluidStockIndex().distinctKeys(), 1,
                            "and still one fluid type");
                    // The row a bucket figure would print as 0: it states the millibuckets instead.
                    assertLine(helper, WareworksLang.fluidStored(A_DROP_MB).component(),
                            WareworksLang.GOGGLES_FLUID_STORED_SMALL, number(A_DROP_MB),
                            "the fluid stored line of a warehouse holding a drop");

                    FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(LAVA_BAY));
                    helper.assertValueEqual(bay.drain(A_DROP_MB, false).getAmount(), A_DROP_MB, "the last drop");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().distinctKeys(), 0,
                        "an empty bay holds no fluid and reports none"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().fluidStockIndex().locationCount(), 0,
                          "and is no longer a location of the fluid index");
                    fill(helper, aisle, WATER_BAY, WATER, WATER_MB);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().count(WATER),
                        (long) WATER_MB, "the other bay is indexed on its own"))
                .thenExecute(() -> {
                    // Emptied first, so taking it away does not report the one loss this mod allows (D7).
                    BlockPos pos = aisle.rackPos(WATER_BAY);
                    Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
                    helper.setBlock(pos, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().fluidStockIndex().distinctKeys(), 0,
                        "a bay that is gone takes its entry with it"))
                .thenExecute(() -> retire(helper, aisle))
                .thenSucceed();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** A powered aisle with one chest-backed storage location and two unfiltered copper fluid bays. */
    private static AisleFixture warehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        for (RackPosition bay : List.of(LAVA_BAY, WATER_BAY))
            helper.setBlock(aisle.rackPos(bay), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                    .setValue(FluidBayBlock.FACING, aisle.sideDirection(bay)));
        aisle.build(true);
        aisle.storage(CHEST_RACK, EMPTY_BUCKET.toStack(STORED_BUCKETS));
        return aisle;
    }

    /**
     * The item-only summary of {@link #warehouse}: three storage locations (the chest and both bays), one item type
     * and the buckets in the chest. A fluid bay is a storage location that holds no items, so it is counted here and
     * contributes nothing to the item numbers.
     */
    private static ControllerGoggleSummary expectedSummary(AisleFixture aisle) {
        return ControllerGoggleSummary.counts(ControllerStatus.READY, RAILS, defaultMastHeight(), 3, 0, 0,
                0, 0, 0, 0, 0, 0, 1, STORED_BUCKETS, 0, 0, 0, 0, 0, 0);
    }

    /** Fills a bay as a pipe or a player's hand would, through its own ungated handler. */
    private static void fill(GameTestHelper helper, AisleFixture aisle, RackPosition rack, FluidKey fluid,
            int millibuckets) {
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(rack));
        helper.assertValueEqual(bay.fill(fluid.toStack(millibuckets), false), millibuckets,
                "the bay at " + rack + " takes " + millibuckets + " mB");
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, BlockPos pos) {
        FluidBayBlockEntity bay =
                WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (bay == null) {
            helper.fail("missing fluid bay block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    /**
     * Empties every bay that is still standing and takes it away, so the teardown of a bay with fluid in it does not
     * report the one loss this mod allows (D7) in the log of a passing run.
     */
    private static void retire(GameTestHelper helper, AisleFixture aisle) {
        for (RackPosition rack : List.of(LAVA_BAY, WATER_BAY)) {
            BlockPos pos = aisle.rackPos(rack);
            Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
            helper.setBlock(pos, Blocks.AIR);
        }
    }

    /** A goggle row's lang key and its one argument, which is all a dedicated server can read of it. */
    private static void assertLine(GameTestHelper helper, Component line, String key, String argument, String what) {
        if (!(line.getContents() instanceof TranslatableContents translatable)) {
            helper.fail(what + " is not a translatable component: " + line.getString());
            return;
        }
        helper.assertValueEqual(translatable.getKey(), WareworksLang.key(key), what + "'s lang key");
        helper.assertValueEqual(translatable.getArgs().length, 1, what + " carries one argument");
        helper.assertValueEqual(argumentText(translatable.getArgs()[0]), argument, what + "'s number");
    }

    private static String argumentText(Object argument) {
        return argument instanceof Component component ? component.getString() : String.valueOf(argument);
    }

    /** The mast height of a fixture aisle, which is the shipped default unless the config lowers it. */
    private static int defaultMastHeight() {
        return Math.min(StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT, WareworksConfig.maxMastHeight());
    }

    /** A number as a goggle line formats it, which is Create's own grouped format with two fraction digits. */
    private static String number(double value) {
        return WareworksLang.number(value).component().getString();
    }
}
