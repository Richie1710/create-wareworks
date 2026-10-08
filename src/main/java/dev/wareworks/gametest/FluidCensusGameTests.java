package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.logistics.box.PackageStyles;

import dev.wareworks.Wareworks;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * The fluid conservation gate itself under test (M30, issue #21): {@link FluidCensus} sees a drop of fluid wherever it
 * can be, counts a shared tank once, reads a tank without draining it or holding on to its stack, and
 * {@link ItemCensus#exchange} refuses to declare an exchange the game does not perform.
 * <p>
 * <b>Every later fluid test is only worth what these four prove.</b> Before this census existed, neither
 * {@link ItemCensus} nor {@code dev.SceneItemCensus} saw a millibucket: 1 000 mB in a tank was invisible, so was a bay
 * that swallowed a bucket and gave back nothing, and so was one that handed out two. That is the exact failure M26 had
 * to fix for a Create package and M28 for a pallet, and both times the lesson was the same — a carrier a census cannot
 * see is a carrier things vanish into while every test reports PASS.
 * <p>
 * No fluid bay exists yet, on purpose: the census lands before the first line of fluid movement, because every test
 * written in between would pass dishonestly. The two traps that no block in Create or this mod can reproduce — a
 * handler that refuses to be drained, and one that hands out its live stack — are therefore driven by hand-made
 * handlers in {@link #fluidCensusNeverDrainsAndNeverKeepsALiveStack}, which is also what a fluid bay with
 * {@code storage.fluidBayPipeExtraction} turned off will look like.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidCensusGameTests {
    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey EMPTY_BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey GLASS_BOTTLE = ItemKey.of(Items.GLASS_BOTTLE);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);

    /** A whole bucket, {@code FluidType.BUCKET_VOLUME}. */
    private static final int BUCKET_MB = 1000;

    private static final BlockPos CHEST = new BlockPos(2, BASE_Y, 3);
    private static final BlockPos DROP = new BlockPos(4, BASE_Y, 3);
    private static final BlockPos PALLET = new BlockPos(5, BASE_Y, 5);
    private static final BlockPos TANK_BOTTOM = new BlockPos(2, BASE_Y, 1);
    private static final BlockPos TANK_TOP = new BlockPos(2, BASE_Y + 1, 1);
    private static final BlockPos LAVA_CAULDRON = new BlockPos(5, BASE_Y, 1);
    private static final BlockPos WATER_CAULDRON = new BlockPos(5, BASE_Y, 3);
    private static final BlockPos EMPTY_CAULDRON = new BlockPos(3, BASE_Y, 5);
    private static final BlockPos SPOUT = new BlockPos(3, BASE_Y, 1);

    /** Lava buckets in the chest; more than one, so a census that read only the first slot would be caught. */
    private static final int LAVA_IN_CHEST = 2;
    /** Lava buckets on the pallet: the load of a broken rack bay, which no sweep of blocks or item entities sees. */
    private static final int LAVA_ON_PALLET = 3;
    /** Lava in the two-block tank, well under its 16 000 mB so the fill cannot be clamped. */
    private static final int LAVA_IN_TANK = 3_000;
    /** What a hand-made handler reports, a number no bucket arithmetic could produce by accident. */
    private static final int HANDMADE_MB = 1_500;
    /** Water cauldron level 1 of 3: 333 mB, again a number no bucket arithmetic produces. */
    private static final int CAULDRON_LEVEL = 1;
    private static final int CAULDRON_LEVELS = 3;
    private static final int WATER_IN_CAULDRON = BUCKET_MB * CAULDRON_LEVEL / CAULDRON_LEVELS;
    /** Water in the spout, under its 1 000 mB tank and not a whole bucket, so a clamp would show. */
    private static final int IN_SPOUT = 500;
    /** Containers in the declared exchange; more than one, so the arithmetic is not just a sign test. */
    private static final int EXCHANGED = 2;

    private FluidCensusGameTests() {
    }

    /**
     * Fluid is counted inside a container item at <b>every</b> place an item can be — in an inventory, inside a Create
     * package, lying in the world as an item entity, and on a pallet — and an <b>empty</b> container is not fluid at
     * all.
     * <p>
     * The two carriers are the point. A package counts as its contents and a pallet as its load, so the bucket inside
     * either of them is an ordinary bucket to {@link ItemCensus} — and because {@link FluidCensus} reads containers off
     * that census's own result rather than sweeping for them again, it cannot be blind to a carrier the item census
     * already knows. A census written as a second sweep would have needed two new branches here and would have been
     * one carrier behind on the next milestone that adds one.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidCensusSeesFluidWhereverAnItemIs(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST);
        IItemHandler chest = itemHandlerAt(helper, CHEST);

        // A lava bucket stacks to one, so each goes in its own slot: a census that read only the first would be caught.
        for (int bucket = 0; bucket < LAVA_IN_CHEST; bucket++)
            insert(helper, chest, LAVA_BUCKET.toStack());
        insert(helper, chest, WATER_BUCKET.toStack());
        insert(helper, chest, packageOf(LAVA_BUCKET.toStack()));
        helper.spawnItem(Items.LAVA_BUCKET, DROP);
        spawnPallet(helper, LAVA_BUCKET, LAVA_ON_PALLET);

        int lavaBuckets = LAVA_IN_CHEST + 1 + 1 + LAVA_ON_PALLET;
        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, lavaBuckets, WATER_BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, (long) lavaBuckets * BUCKET_MB, WATER, BUCKET_MB);
        FluidCensus.assertConserved(helper, items, fluid, "buckets in a chest, a package, the world and a pallet");

        // An empty container is an item and nothing else: the item census moves, this one does not. That is what makes
        // the empty bucket a crane brings back ordinary stock rather than a second kind of fluid.
        insert(helper, chest, EMPTY_BUCKET.toStack());
        ItemCensus.change(items, EMPTY_BUCKET, 1);
        FluidCensus.assertConserved(helper, items, fluid, "an empty bucket beside the full ones");

        // And one more full bucket moves both halves, by exactly one bucket each.
        insert(helper, chest, LAVA_BUCKET.toStack());
        ItemCensus.change(items, LAVA_BUCKET, 1);
        FluidCensus.change(fluid, LAVA, BUCKET_MB);
        FluidCensus.assertConserved(helper, items, fluid, "one more lava bucket in the chest");

        // Everything taken out of the chest is deliberately thrown away, which is the one thing a census must report
        // rather than survive: the fluid in those buckets has to leave this census with them, down to the loose
        // bucket and the pallet.
        for (int slot = 0; slot < chest.getSlots(); slot++)
            chest.extractItem(slot, Integer.MAX_VALUE, false);
        int leftBuckets = 1 + LAVA_ON_PALLET;
        FluidCensus.assertConserved(helper, ItemCensus.of(LAVA_BUCKET, leftBuckets),
                FluidCensus.of(LAVA, (long) leftBuckets * BUCKET_MB), "after the chest was emptied");
        helper.succeed();
    }

    /**
     * A tank that spans two blocks is counted <b>once</b>, and a cauldron is counted although it has no block entity
     * at all.
     * <p>
     * Both halves of a multiblock Create Fluid Tank answer the <b>same</b> {@code IFluidHandler} — a non-controller's
     * capability registration delegates to the controller's {@code tankInventory} — so a census that counted per
     * position would report 6 000 mB for 3 000, and every conservation assertion in a warehouse with a two-block tank
     * beside it would be off by the size of the tank. This is the double-chest problem of
     * {@code ItemCensus#isSecondChestHalf} in fluid form, and deduplicating by handler identity is the general answer:
     * Create's own {@code FluidNetwork} keys its fill accounting on an {@code IdentityHashMap} of handlers for exactly
     * this reason.
     * <p>
     * The cauldrons cover the other half of the sweep. {@link ItemCensus} skips a position with no block entity
     * outright, which a fluid census may not do: a cauldron is a plain block with a fluid capability. A full lava
     * cauldron is a whole bucket, a water cauldron at one of its three levels is 333 mB (a number no bucket arithmetic
     * produces, so a census reading the state rather than the fluid would be caught), and an <b>empty</b> cauldron is
     * registered too and must contribute nothing.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidCensusCountsOneTankOnce(GameTestHelper helper) {
        helper.setBlock(TANK_BOTTOM, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(TANK_TOP, AllBlocks.FLUID_TANK.getDefaultState());
        if (!(helper.getBlockEntity(TANK_BOTTOM) instanceof FluidTankBlockEntity controller)) {
            helper.fail("a Create fluid tank must have its block entity", TANK_BOTTOM);
            return;
        }
        // A tank placed with setBlock never forms its multiblock by itself: Block#onPlace runs before the block entity
        // exists (LevelChunk#setBlockState), so Create's own public entry point is the way to build one in a test.
        ConnectivityHandler.formMulti(controller);

        IFluidHandler bottom = fluidHandlerAt(helper, TANK_BOTTOM);
        IFluidHandler top = fluidHandlerAt(helper, TANK_TOP);
        helper.assertTrue(bottom == top,
                "both blocks of a multiblock tank must answer the same handler; if Create ever changes that, the"
                        + " census can stop deduplicating by identity, and until then it must");

        helper.assertValueEqual(bottom.fill(LAVA.toStack(LAVA_IN_TANK), IFluidHandler.FluidAction.EXECUTE),
                LAVA_IN_TANK, "millibuckets the two-block tank took");
        FluidCensus.assertEquals(helper, FluidCensus.of(LAVA, LAVA_IN_TANK), "a tank spanning two blocks");

        helper.setBlock(LAVA_CAULDRON, Blocks.LAVA_CAULDRON);
        helper.setBlock(EMPTY_CAULDRON, Blocks.CAULDRON);
        helper.setBlock(WATER_CAULDRON,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, CAULDRON_LEVEL));
        helper.assertTrue(fluidHandlerAt(helper, EMPTY_CAULDRON) != null,
                "an empty cauldron answers a fluid handler too, and must count as nothing");
        FluidCensus.assertEquals(helper, FluidCensus.of(LAVA, LAVA_IN_TANK + BUCKET_MB, WATER, WATER_IN_CAULDRON),
                "cauldrons beside the tank");
        helper.succeed();
    }

    /**
     * The census reads a tank with {@code getFluidInTank} and never with a drain, copies what it reads, and leaves
     * both the tank and the stack it was handed exactly as they were.
     * <p>
     * Three traps, and the first two cannot be reproduced by any block in Create or this mod today, which is why the
     * handlers are hand-made:
     * <ol>
     * <li><b>A drain-based read is blind to a tank that refuses extraction.</b> Create's
     * {@code SmartFluidTankBehaviour.InternalFluidHandler} returns {@code EMPTY} from both {@code drain} overloads
     * unless {@code extractionAllowed} and leaves {@code getFluidInTank} open — and {@code FluidUtil.getFluidContained}
     * <i>is</i> a simulated drain. A fluid bay with {@code storage.fluidBayPipeExtraction} turned off is exactly that
     * tank, so a census written the obvious way would read 0 for a full brass bay.</li>
     * <li><b>The stack a handler hands out may be its live instance</b> — {@code IFluidHandler} says "SERIOUSLY: DO NOT
     * MODIFY THE RETURNED FLUIDSTACK" and {@code FluidTank.getFluidInTank} returns the field itself. The census must
     * neither change it nor keep it, or a later change in the world would silently rewrite a census already taken.</li>
     * <li><b>Reading must not move fluid.</b> The Create tank below is counted three times and must still hold
     * everything it held.</li>
     * </ol>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidCensusNeverDrainsAndNeverKeepsALiveStack(GameTestHelper helper) {
        Map<FluidKey, Long> refusesExtraction = new HashMap<>();
        FluidCensus.addHandler(refusesExtraction, new RefusingHandler(LAVA.toStack(HANDMADE_MB)));
        helper.assertValueEqual(refusesExtraction, FluidCensus.of(LAVA, HANDMADE_MB),
                "a tank that refuses every drain is still counted");

        FluidStack live = LAVA.toStack(HANDMADE_MB);
        Map<FluidKey, Long> fromLiveStack = new HashMap<>();
        FluidCensus.addHandler(fromLiveStack, new LiveStackHandler(live));
        helper.assertValueEqual(live.getAmount(), HANDMADE_MB, "the census must not change the stack it was handed");
        live.setAmount(1);
        helper.assertValueEqual(fromLiveStack, FluidCensus.of(LAVA, HANDMADE_MB),
                "a census already taken must not change when the world does");

        helper.setBlock(TANK_BOTTOM, AllBlocks.FLUID_TANK.getDefaultState());
        IFluidHandler tank = fluidHandlerAt(helper, TANK_BOTTOM);
        helper.assertValueEqual(tank.fill(LAVA.toStack(LAVA_IN_TANK), IFluidHandler.FluidAction.EXECUTE), LAVA_IN_TANK,
                "millibuckets the tank took");
        helper.assertTrue(tank.getFluidInTank(0) == tank.getFluidInTank(0),
                "a Create tank hands out its live stack twice over, which is why the census copies");

        Map<FluidKey, Long> expected = FluidCensus.of(LAVA, LAVA_IN_TANK);
        for (int pass = 1; pass <= 3; pass++)
            FluidCensus.assertEquals(helper, expected, "reading the tank, pass " + pass);
        helper.assertValueEqual(tank.getFluidInTank(0).getAmount(), LAVA_IN_TANK,
                "three censuses must not have drained a drop");
        helper.succeed();
    }

    /**
     * A block whose fluid capability is registered <b>per face</b> is still read in full through its {@code null}
     * view — the convention this census depends on, and the one a fluid bay has to keep.
     * <p>
     * Create's Spout is the sharpest example in the game: its registration returns {@code null} for
     * {@code Direction.DOWN} and the tank for every other context, {@code null} among them. The Item Drain and the
     * Hose Pulley are written the same way, which is why a {@code null}-only sweep is complete for everything in
     * Create — and why a bay that refuses a pipe on its aisle face must still answer the {@code null} query, or its
     * contents would be invisible here while every conservation test went on passing.
     * <p>
     * The sweep is deliberately <b>not</b> widened to the six faces instead: a cauldron's registration builds a fresh
     * wrapper per query, so identity could not deduplicate seven answers and a full cauldron would be counted seven
     * times. A block that answers a face but not {@code null} therefore fails the census outright rather than being
     * silently skipped — a guard with no block in Create or this mod to trip it, which is the point.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidCensusReadsASidedRegistrationThroughItsNullView(GameTestHelper helper) {
        helper.setBlock(SPOUT, AllBlocks.SPOUT.getDefaultState());
        helper.assertTrue(sidedHandlerAt(helper, SPOUT, Direction.DOWN) == null,
                "Create's Spout refuses its own down face, which is what makes it the test case");
        IFluidHandler nullView = fluidHandlerAt(helper, SPOUT);
        if (nullView == null) {
            helper.fail("a sided fluid registration must still answer a null query, or no census can read it", SPOUT);
            return;
        }
        for (Direction side : Direction.values())
            if (side != Direction.DOWN)
                helper.assertTrue(sidedHandlerAt(helper, SPOUT, side) == nullView,
                        "every accepted face answers the same handler the null view does, side " + side);

        helper.assertValueEqual(nullView.fill(WATER.toStack(IN_SPOUT), IFluidHandler.FluidAction.EXECUTE), IN_SPOUT,
                "millibuckets the spout took");
        FluidCensus.assertEquals(helper, FluidCensus.of(WATER, IN_SPOUT), "a sided registration, read through null");
        helper.succeed();
    }

    /**
     * {@link ItemCensus#exchange} declares the container exchange the game performs and refuses every other one.
     * <p>
     * An exchange is the one move in this mod under which an item legitimately changes identity: a filled bucket
     * becomes an empty one while its contents go into a tank. The item half alone therefore reads as a key appearing
     * out of nowhere, which is why a test has to be able to say "and now this bucket became that one" — and exactly
     * why it must not be allowed to say it freely. The verification runs {@code FluidContainers#drained}, the same
     * routine a fluid bay uses, on a single-item probe, so a test can only ever declare the truth.
     * <p>
     * <b>The fluid expectation is deliberately untouched by an exchange</b>, which is the whole invariant in one line:
     * the fluid moved from a carrier into a block and both are counted, so a bay that swallowed 1 000 mB and stored
     * 900 would still fail {@link FluidCensus#assertConserved} even though every item was accounted for.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void censusExchangeOnlyDeclaresWhatTheGameDoes(GameTestHelper helper) {
        Map<ItemKey, Long> declared = ItemCensus.of(LAVA_BUCKET, EXCHANGED);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, (long) EXCHANGED * BUCKET_MB);

        ItemCensus.exchange(helper, declared, LAVA_BUCKET, EMPTY_BUCKET, EXCHANGED);
        helper.assertValueEqual(declared, ItemCensus.of(EMPTY_BUCKET, EXCHANGED),
                "a declared exchange turns filled containers into empty ones, one for one");
        helper.assertValueEqual(fluid, FluidCensus.of(LAVA, (long) EXCHANGED * BUCKET_MB),
                "and leaves the fluid expectation alone, because the fluid is still in the box");

        assertRefused(helper, declared, LAVA_BUCKET, GLASS_BOTTLE, 1,
                "a lava bucket does not become a glass bottle");
        assertRefused(helper, declared, EMPTY_BUCKET, EMPTY_BUCKET, 1,
                "an empty container cannot be emptied, so there is no exchange to declare");
        assertRefused(helper, declared, LAVA_BUCKET, EMPTY_BUCKET, 0,
                "an exchange of nothing is not an exchange");
        helper.succeed();
    }

    /** Fails the test unless {@link ItemCensus#exchange} refuses this declaration. */
    private static void assertRefused(GameTestHelper helper, Map<ItemKey, Long> expected, ItemKey filled, ItemKey empty,
            long containers, String why) {
        Map<ItemKey, Long> probe = new HashMap<>(expected);
        boolean refused = false;
        try {
            ItemCensus.exchange(helper, probe, filled, empty, containers);
        } catch (GameTestAssertException caught) {
            refused = true;
        }
        helper.assertTrue(refused, "the exchange " + filled + " -> " + empty + " must be refused: " + why);
        helper.assertValueEqual(probe, expected, "a refused exchange must not change the expectation: " + why);
    }

    /** A handler that holds fluid and refuses every drain — a fluid bay with pipe extraction turned off. */
    private record RefusingHandler(FluidStack contents) implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return contents.copy();
        }

        @Override
        public int getTankCapacity(int tank) {
            return contents.getAmount();
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return true;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /** A handler that hands out its own mutable stack, as {@code FluidTank.getFluidInTank} really does. */
    private record LiveStackHandler(FluidStack contents) implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return contents;
        }

        @Override
        public int getTankCapacity(int tank) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return true;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /** A deterministic Create package holding {@code stacks}, one per slot ({@code ItemCensusGameTests}' fixture). */
    private static ItemStack packageOf(ItemStack... stacks) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }

    private static void spawnPallet(GameTestHelper helper, ItemKey key, int load) {
        helper.assertTrue(PalletEntity.spawn(helper.getLevel(), helper.absolutePos(PALLET), key, load),
                "the level accepted a pallet carrying " + load + " " + key);
    }

    private static IItemHandler itemHandlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    private static IFluidHandler fluidHandlerAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(pos), null);
    }

    private static IFluidHandler sidedHandlerAt(GameTestHelper helper, BlockPos pos, Direction side) {
        return helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(pos), side);
    }

    private static void insert(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
        helper.assertTrue(rest.isEmpty(), "inventory rejected " + rest);
    }
}
