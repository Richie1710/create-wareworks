package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlock;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.StationGoggleSummary;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseInputUnpackingHandler;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of what a <b>warehouse input</b> makes of a Create package (M26, issue #18, {@code
 * docs/warehouse-system.md} §3.2.5): the way in works with no Wareworks code in the item path, so what is pinned here
 * is that {@link WarehouseInputUnpackingHandler} — which <b>replaces</b> Create's default handler for our block —
 * changed none of it, and that the refusal nothing else in the game diagnoses is recorded.
 * <p>
 * The build both tests use is the one a player builds: a warehouse input, and a Create Packager one block away whose
 * {@code FACING} points <b>away</b> from it, so the Packager's back touches the input and
 * {@code PackagerBlockEntity#unwrapBox} unpacks into it. Packages are handed over through the Packager's own item
 * capability, which is the call a funnel, a chute or a Frogport makes.
 * <p>
 * The wider handover — the out door, the sign address, the overflow and the persistence — is pinned by
 * {@code PackageHandoverGameTests}; these tests are deliberately about the input's own surfaces alone.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageUnpackingGameTests {
    private static final BlockPos INPUT = new BlockPos(3, BASE_Y, 3);
    /** One step east of the input; the Packager faces east, so its back is the input's side. */
    private static final Direction PACKAGER_SIDE = Direction.EAST;
    private static final BlockPos PACKAGER = INPUT.relative(PACKAGER_SIDE);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    private static final int IRON_IN_BOX = 64;
    private static final int GOLD_IN_BOX = 7;
    private static final int DIAMONDS_IN_BOX = 3;
    /** Iron of the package that merges into a slot already holding one iron; well under a stack, so it fits whole. */
    private static final int MERGED_IRON = 10;
    private static final int STACK = 64;
    /** Ticks given to the freshly placed blocks so Create's behaviours have initialised. */
    private static final int SETTLE_TICKS = 2;

    /**
     * Distinct items for filling buffer slots, none of them in any test package, and more than the 27 slots
     * {@code inputBufferSlots} can be configured to.
     */
    private static final List<Item> FILLERS = List.of(Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL,
            Items.OAK_LOG, Items.STONE, Items.GLASS, Items.BRICK, Items.CLAY_BALL, Items.FLINT, Items.COAL,
            Items.STICK, Items.BONE, Items.LEATHER, Items.PAPER, Items.WHEAT, Items.CARROT, Items.POTATO,
            Items.APPLE, Items.EGG, Items.FEATHER, Items.STRING, Items.SUGAR_CANE, Items.KELP, Items.CACTUS,
            Items.PUMPKIN, Items.MELON_SLICE, Items.NETHER_WART);

    private PackageUnpackingGameTests() {
    }

    /**
     * A Packager whose back touches a warehouse input takes a package apart into its buffer, the box is consumed, the
     * contents arrive to the item, and the input counts the package it opened.
     * <p>
     * This is the required proof that A2's wrapper did not break the way in: the registration replaces
     * {@link UnpackingHandler#DEFAULT} for our block, so a bug in a class that is supposed to add nothing would close
     * the door entirely.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void inputunpacksacreatepackage(GameTestHelper helper) {
        build(helper);
        ItemStack box = packageOf(IRON.toStack(IRON_IN_BOX), GOLD.toStack(GOLD_IN_BOX));
        Map<ItemKey, Long> inTheBox = ItemCensus.of(ItemKey.of(box), 1);

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    // The handler really is ours, for this block and for no other.
                    helper.assertValueEqual(UnpackingHandler.REGISTRY.get(helper.getBlockState(INPUT)),
                            WarehouseInputUnpackingHandler.INSTANCE, "the input's unpacking handler");
                    ItemCensus.assertEquals(helper, ItemCensus.of(), "nothing in the test before the package");

                    ItemStack left = packagerInventory(helper).insertItem(0, box.copy(), false);
                    helper.assertTrue(left.isEmpty(), "Create consumed the box: " + left);
                    // Under census rule 1 the box was never items, so the buffer must hold exactly what it declared.
                    ItemCensus.assertEquals(helper, inTheBox, "the contents of the box are in the buffer");

                    WarehouseInputBlockEntity input = inputAt(helper);
                    helper.assertValueEqual(input.bufferedItems().count(IRON), (long) IRON_IN_BOX, "iron unpacked");
                    helper.assertValueEqual(input.bufferedItems().count(GOLD), (long) GOLD_IN_BOX, "gold unpacked");

                    PackageUnpackSummary packages = observe(helper, input);
                    helper.assertValueEqual(packages.opened(), 1L, "one package opened");
                    helper.assertFalse(packages.hasRefusal(), "no refusal on record");
                    assertRoundTrips(helper, packages);
                })
                .thenSucceed();
    }

    /**
     * The all-or-nothing cliff: a package with more item types than the buffer has free slots is refused <b>whole</b>,
     * nothing of it enters, the box stays with the sender — and the input records how many stacks the package held
     * beside how many slots were free, which is the only diagnosis of this failure anywhere in the game.
     * <p>
     * Draining the buffer makes the very same package fit, and opening it clears the refusal.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void inputrefusesapackageitcannotholdwhole(GameTestHelper helper) {
        build(helper);
        ItemStack box = packageOf(IRON.toStack(IRON_IN_BOX), GOLD.toStack(GOLD_IN_BOX),
                DIAMOND.toStack(DIAMONDS_IN_BOX));
        Map<ItemKey, Long> inTheBox = ItemCensus.of(ItemKey.of(box), 1);

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = inputAt(helper);
                    Map<ItemKey, Long> filled = fillAllButOneSlot(helper, input);

                    ItemStack left = packagerInventory(helper).insertItem(0, box.copy(), false);
                    helper.assertValueEqual(left.getCount(), 1, "the box came back unchanged");
                    helper.assertTrue(ItemCensus.of(ItemKey.of(left), 1).equals(inTheBox),
                            "the box that came back still holds everything");
                    ItemCensus.assertEquals(helper, filled, "not one item of a refused package enters");

                    PackageUnpackSummary refused = observe(helper, input);
                    helper.assertValueEqual(refused.opened(), 0L, "nothing was opened");
                    // The package's own stacks, not what Create's greedy simulate pass had left over: three stacks
                    // needed room at once and one slot was free, which is a pair a player can read side by side.
                    helper.assertValueEqual(refused.refusedPackageStacks(), 3, "stacks the package held");
                    helper.assertValueEqual(refused.freeSlotsAtRefusal(), 1, "slots free at the refusal");

                    // The crane drains the buffer; now the same package fits and the refusal is history.
                    for (ItemKey key : List.copyOf(filled.keySet()))
                        while (!input.extract(key, STACK, false).isEmpty()) {
                            // drained one stack at a time, exactly as a store job does
                        }
                    helper.assertTrue(packagerInventory(helper).insertItem(0, box.copy(), false).isEmpty(),
                            "accepted once the buffer is empty");
                    ItemCensus.assertEquals(helper, inTheBox, "the drained buffer now holds the box's contents");
                    PackageUnpackSummary opened = observe(helper, input);
                    helper.assertValueEqual(opened.opened(), 1L, "the retry was opened");
                    helper.assertFalse(opened.hasRefusal(), "the refusal is cleared once a package fits");
                })
                .thenSucceed();
    }

    /**
     * A package is opened into a buffer with <b>no free slot at all</b>, because the stack it holds merges into a slot
     * that already holds that very item with room left.
     * <p>
     * This is the boundary of the sentence the Ponder scene, the README and the goggle line all draw: a package needs
     * <b>room</b> for every stack in it, which is a free slot <b>or</b> a slot already holding that item with space
     * left. Create's simulate pass really does merge ({@code DefaultUnpackingHandler}: the
     * {@code isSameItemSameComponents} branch empties the entry once the whole box stack fits), and a Wareworks input
     * really does expose it, because {@code InsertOnlyItemHandler} forwards {@code getStackInSlot},
     * {@code insertItem} and {@code getSlotLimit} verbatim and stubs only {@code extractItem}. So "a free slot per
     * stack" would be a promise stricter than Create's own rule, and this test is what keeps the texts honest.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void inputopensapackagethatmergesintoafullbuffer(GameTestHelper helper) {
        build(helper);
        ItemStack box = packageOf(IRON.toStack(MERGED_IRON));

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = inputAt(helper);
                    Map<ItemKey, Long> expected = fillAllButOneSlot(helper, input);
                    // One iron into the last free slot: now every slot is occupied, and yet there is room for iron.
                    helper.assertTrue(ItemHandlerHelper.insertItem(inputHandler(helper), IRON.toStack(1), false)
                            .isEmpty(), "the last free slot took one iron");
                    ItemCensus.change(expected, IRON, 1);
                    helper.assertValueEqual(freeSlots(helper), 0, "no buffer slot is free");

                    ItemStack left = packagerInventory(helper).insertItem(0, box.copy(), false);
                    helper.assertTrue(left.isEmpty(), "Create consumed the box although no slot was free: " + left);
                    ItemCensus.change(expected, IRON, MERGED_IRON);
                    ItemCensus.assertEquals(helper, expected, "the box's iron merged into the slot that held iron");
                    helper.assertValueEqual(input.bufferedItems().count(IRON), (long) MERGED_IRON + 1,
                            "the iron of the package is in the buffer");

                    PackageUnpackSummary packages = observe(helper, input);
                    helper.assertValueEqual(packages.opened(), 1L, "one package opened");
                    helper.assertFalse(packages.hasRefusal(), "and nothing was refused");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** A warehouse input and a Create Packager whose back touches it. */
    private static void build(GameTestHelper helper) {
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, Direction.NORTH));
        helper.setBlock(PACKAGER, AllBlocks.PACKAGER.getDefaultState()
                .setValue(PackagerBlock.FACING, PACKAGER_SIDE));
    }

    /**
     * A Create package holding {@code contents}, built from the component rather than through {@code PackageItem}:
     * {@link PackageStyles#getDefaultBox()} keeps the test deterministic and
     * {@link ItemContainerContents#fromItems(List)} declares exactly these stacks.
     */
    private static ItemStack packageOf(ItemStack... contents) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    /** Fills every buffer slot but one with a full stack of a distinct item, and the census expectation with them. */
    private static Map<ItemKey, Long> fillAllButOneSlot(GameTestHelper helper, WarehouseInputBlockEntity input) {
        int toFill = input.bufferSlots() - 1;
        helper.assertTrue(toFill >= 1 && toFill <= FILLERS.size(),
                "the buffer has " + input.bufferSlots() + " slots, which this test can fill");
        IItemHandler handler = inputHandler(helper);
        Map<ItemKey, Long> expected = ItemCensus.of();
        for (int slot = 0; slot < toFill; slot++) {
            ItemStack filler = new ItemStack(FILLERS.get(slot), STACK);
            helper.assertTrue(ItemHandlerHelper.insertItem(handler, filler.copy(), false).isEmpty(),
                    "filler " + filler + " accepted");
            ItemCensus.change(expected, ItemKey.of(filler), STACK);
        }
        return expected;
    }

    /**
     * Rebuilds and returns the input's package summary through the real goggle path: the numbers are plain server
     * fields that nothing syncs until a player looks, which is the discipline that keeps a funnel retrying every tick
     * from sending a packet every tick.
     */
    private static PackageUnpackSummary observe(GameTestHelper helper, WarehouseInputBlockEntity input) {
        input.onGoggleObserved();
        return input.summary().packages();
    }

    /** The summary survives the client packet, and a station without packages does not write the tag at all. */
    private static void assertRoundTrips(GameTestHelper helper, PackageUnpackSummary packages) {
        StationGoggleSummary summary = StationGoggleSummary.NONE.withPackages(packages);
        CompoundTag tag = new CompoundTag();
        summary.write(tag);
        helper.assertValueEqual(StationGoggleSummary.read(tag).packages(), packages, "package summary round trip");
        CompoundTag empty = new CompoundTag();
        StationGoggleSummary.NONE.write(empty);
        helper.assertFalse(empty.contains("Packages"), "a station without packages writes no package tag");
    }

    private static WarehouseInputBlockEntity inputAt(GameTestHelper helper) {
        WarehouseInputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(helper.getLevel(),
                helper.absolutePos(INPUT));
        if (be == null)
            helper.fail("missing warehouse input block entity", INPUT);
        return be;
    }

    private static IItemHandler inputHandler(GameTestHelper helper) {
        return handlerAt(helper, INPUT);
    }

    /** Empty slots of the input's buffer, counted through the very capability Create's simulate pass walks. */
    private static int freeSlots(GameTestHelper helper) {
        IItemHandler handler = inputHandler(helper);
        int free = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (handler.getStackInSlot(slot).isEmpty())
                free++;
        return free;
    }

    private static IItemHandler packagerInventory(GameTestHelper helper) {
        return handlerAt(helper, PACKAGER);
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }
}
