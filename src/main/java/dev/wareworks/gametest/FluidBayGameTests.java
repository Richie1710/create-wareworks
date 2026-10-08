package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.MAX_FLUID_BAY_SYNC_BYTES;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.BayColumn;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.FluidBayGestures;
import dev.wareworks.content.storage.FluidBayHandler;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayGestures;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.TieredBay;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.storage.BayFamily;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): a storage location that <b>is</b>
 * a tank, holding one fluid as a millibucket count.
 * <p>
 * What each test is here for:
 * <ul>
 * <li>{@code fluidbaystandsalone} — the standalone promise: with no controller, no crane and no rail anywhere a fluid
 * bay is a tank that is filled, read and drained through its fluid capability. It also pins the <b>tickerless</b> rule
 * (a tank farm must cost nothing per tick), the <b>absence</b> of any item capability (D3, the one thing that makes a
 * container unable to reach a bay except through a hand or the crane's head) and the one handler answer an item
 * handler could not give: a drain with <b>no per-call cap</b>;</li>
 * <li>{@code fluidbaytakesonefluiduntilitempties} — one fluid, learned from what lands and forgotten on empty, and the
 * filter that names a <b>fluid</b> rather than a container (D6);</li>
 * <li>{@code pipefillsafluidbayatthebackonly} — the sided capability (D4): every face but the aisle one, a
 * {@code null} query that is answered (or a fluid census could not see the bay at all), and a <b>real Create pipe</b>
 * at the back that fills it;</li>
 * <li>{@code pipeextractionfollowstheconfig} — {@code storage.fluidBayPipeExtraction} both ways, including that
 * reading and filling are never gated and that the bay's own handler is not gated either;</li>
 * <li>{@code fluidbaycapacityfromconfig} — the two capacities as server config, and the one thing a config change
 * could do that a player would feel as loss: lowered under a bay that is already fuller than it. <b>This is the test
 * that would catch NeoForge's {@code FluidTank.fill} trap</b>, which silently drops such a tank to its new
 * capacity;</li>
 * <li>{@code fluidbaypersistenceroundtrip} — 256 000 mB, a filter and a priority through two save/load passes, and
 * that the schematic path carries the settings and not one drop;</li>
 * <li>{@code fluidbaysurvivesanysavedata} — a world written before fluid bays existed, save data nobody should trust,
 * and the schematic-print guard that keeps a standing, filled bay filled;</li>
 * <li>{@code fluidbaysetblockvoidsitlikeavanillatank} — the {@link Clearable} path, deliberate vanilla parity;</li>
 * <li>{@code fluidbaysyncisbounded} — the client packet is a registry id and an int, whatever is in the bay;</li>
 * <li>{@code fluidbaycolumnruleandjoins} — the shared machinery over the <b>fluid</b> ladder: refused at placement in
 * both directions with its own sentence, a rack bay <i>ending</i> a fluid column rather than being refused by it, and
 * a seam shared with a rack bay because joining is across families;</li>
 * <li>{@code fluidbayisastoragelocationthatholdsnoitems} — the bay in a running warehouse: it joins an aisle and is
 * counted as a storage location, and it holds no items at all, so an <b>ordinary</b> item is carried past it to a
 * chest — asserted with a filter that names its fluid, which is the one case that could have made the planner park on
 * it. Where a <b>filled container</b> goes is {@link FluidBayStoreGateGameTests}' subject, and it goes to the bay. It
 * also carries the <b>address</b> half of the goggles, because the warehouse that makes that question meaningful is
 * already standing in it;</li>
 * <li>{@code fluidbayhandgestures} — a bucket in hand, both ways, and the four things the gesture must <b>not</b> do
 * (M30 step 6). The one that is not about convenience: a container's click is consumed even when nothing moves,
 * because a click passed on would pour the lava against the block's face;</li>
 * <li>{@code fluidbaygogglerowssaywhatitholds} — the readout, row by row, in every state it branches on;</li>
 * <li>{@code breakingafullfluidbaylosesitsfluidandlogsit} — the one deliberate loss in this mod, pinned in both
 * halves: the census drops by <b>exactly</b> what was in the bay, nothing is placed in its stead, and the loss is in
 * the server log with the fluid, the amount and the position. Plus the warning on the first punch, which is the last
 * of the three places that say so beforehand;</li>
 * <li>{@code breakingafluidbayincreative} — the same loss with nothing dropped at all, and the one asymmetry of that
 * warning: a creative break never shows it, because the vanilla creative break never calls {@code attack}.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidBayGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 60;
    /** Ticks a bay a command placed needs to read its own column-rule flag: one scheduled tick, plus slack. */
    private static final int REPAIR_TICKS = 3;

    /** Batch of {@link #fluidBayCapacityFromConfig}, which overrides the server config ({@link ConfigOverrides}). */
    static final String CAPACITY_BATCH = "wareworksfluidbaycapacity";
    /** Batch of {@link #pipeExtractionFollowsTheConfig}, for the same reason and with its own key. */
    static final String EXTRACTION_BATCH = "wareworksfluidbayextraction";

    /** A bay standing on its own, clear of the template walls. */
    private static final BlockPos LONE_BAY = new BlockPos(3, BASE_Y, 3);
    /** A second bay beside the first, for the joins and the cross-family rules. */
    private static final BlockPos SECOND_BAY = new BlockPos(2, BASE_Y, 3);

    /** The column the placement rule is tested in, and the one a command breaks. */
    private static final BlockPos COLUMN_FOOT = new BlockPos(1, BASE_Y, 1);
    /** A brass bay one level up, so a placement <b>underneath</b> it has a free position to be refused at. */
    private static final BlockPos MIXED_HEAD = new BlockPos(5, BASE_Y + 1, 1);

    private static final RackPosition BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);

    private static final int BUCKET = FluidType.BUCKET_VOLUME;
    /** A fill that is not a whole bucket, so the arithmetic cannot be mistaken for a bucket count. */
    private static final int ODD_FILL = 1_750;
    /** Millibuckets a copper bay holds at the shipped default. */
    private static final int COPPER_CAPACITY = 64 * BUCKET;
    /** Millibuckets a brass bay holds at the shipped default. */
    private static final int BRASS_CAPACITY = 256 * BUCKET;

    /** Configured capacities that are not the shipped ones ({@link #fluidBayCapacityFromConfig}). */
    private static final int CONFIGURED_COPPER_BUCKETS = 7;
    private static final int CONFIGURED_BRASS_BUCKETS = 19;
    /** A capacity lowered under a bay that is already full of the configured one. */
    private static final int LOWERED_COPPER_BUCKETS = 3;

    /** A custom name long enough that nothing could carry it unnoticed into an update tag. */
    private static final int LONG_NAME_LENGTH = 48;
    /**
     * The load a broken bay loses: 37.25 buckets, so the goggle row's bucket figure cannot be mistaken for a whole
     * number and the amount in the log line cannot be mistaken for a capacity.
     */
    private static final int LOST_FILL = 37_250;
    /** Ticks a test waits before reading {@link LogCapture}: log4j may deliver an event on another thread. */
    private static final int LOG_TICKS = 3;
    /** Blocks south of a bay's centre a mock player stands at to aim at its aisle face ({@code aimAtAisleFace}). */
    private static final double AIM_DISTANCE = 2.0;
    /** Blocks around a cleared bay searched for dropped items. */
    private static final double DROP_RADIUS = 3.0;

    private FluidBayGameTests() {
    }

    // --- standalone ------------------------------------------------------------------------------------------------

    /**
     * A fluid bay is a <b>tank</b> before it is a warehouse part: with no controller, no crane, no rail and no
     * interface anywhere it has to be fillable, readable and drainable from the outside.
     * <p>
     * It also pins the four things that make that affordable and honest:
     * <ul>
     * <li>{@code getTicker} is {@code null} on both sides — a tank farm costs nothing per tick, which is also why the
     * level is drawn without interpolation;</li>
     * <li><b>no item capability on any face, and none for a {@code null} query</b>: a bay that answered one would have
     * to hand an emptied container back as an insert remainder, and a funnel does not read a remainder of a different
     * item — it would take the fluid and destroy the container (D3);</li>
     * <li>{@link IFluidHandler#drain(int, FluidAction)} has no per-call cap, so a pump asking for everything gets
     * everything in one call — the one place a fluid handler must <i>not</i> behave like the item one;</li>
     * <li>the contents cross to the client with no warehouse involved in syncing them, because a bay's level is the
     * only thing a player has to read it by.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayStandsAlone(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        BlockState state = level.getBlockState(helper.absolutePos(LONE_BAY));

        BlockEntityTicker<?> ticker = state.getTicker(level, WareworksBlockEntityTypes.FLUID_BAY.get());
        helper.assertTrue(ticker == null, "a fluid bay must have no server ticker");
        helper.assertTrue(state.getBlock() instanceof FluidBayBlock, "the block is a fluid bay");
        helper.assertValueEqual(bay.tier(), FluidBayTier.COPPER, "tier read off the block");
        helper.assertValueEqual(bay.buckets(), 64, "a copper bay holds 64 buckets");
        helper.assertValueEqual(bay.capacity(), (long) COPPER_CAPACITY, "which is its capacity in millibuckets");

        // No item capability anywhere: that is what makes a container unable to reach a bay except by hand or through
        // the crane's handling head, and it keeps the bay out of the item census's sweep for free.
        helper.assertTrue(itemHandlerAt(helper, LONE_BAY, null) == null,
                "a fluid bay must expose no item handler for a null query");
        for (Direction side : Direction.values())
            helper.assertTrue(itemHandlerAt(helper, LONE_BAY, side) == null,
                    "a fluid bay must expose no item handler on " + side);

        IFluidHandler handler = fluidHandlerAt(helper, LONE_BAY, null);
        helper.assertTrue(handler == bay.pipeView(),
                "the capability is the bay's own pipe view instance, which is why a final field is all the "
                        + "invalidation this block needs");
        helper.assertValueEqual(handler.getTanks(), FluidBayHandler.TANKS, "a bay has one tank");
        helper.assertTrue(handler.getFluidInTank(0).isEmpty(), "a fresh bay is empty");
        helper.assertValueEqual(handler.getTankCapacity(0), COPPER_CAPACITY, "the tank reports its capacity");

        // Filled from the outside, by the machine's own route.
        FluidStack lava = new FluidStack(Fluids.LAVA, BUCKET);
        helper.assertValueEqual(handler.fill(lava.copy(), FluidAction.SIMULATE), BUCKET,
                "a simulated fill answers what a real one would take");
        helper.assertValueEqual(bay.millibuckets(), 0, "and changed nothing");
        helper.assertValueEqual(handler.fill(lava.copy(), FluidAction.EXECUTE), BUCKET, "the first bucket fits");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "and is in the bay");
        helper.assertValueEqual(bay.storedFluid().orElse(null), FluidKey.of(Fluids.LAVA), "as lava");
        helper.assertValueEqual(handler.getFluidInTank(0).getAmount(), BUCKET, "which the handler says too");

        // Past what any container carries, and not one millibucket further.
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.LAVA, COPPER_CAPACITY), FluidAction.EXECUTE),
                COPPER_CAPACITY - BUCKET, "the rest of the bay's capacity fits in one call");
        helper.assertValueEqual(bay.millibuckets(), COPPER_CAPACITY, "the bay is full");
        helper.assertValueEqual(handler.fill(lava.copy(), FluidAction.EXECUTE), 0, "and takes nothing more");

        // A stack a caller hands over is never kept, and what comes back is never the bay's own.
        FluidStack read = handler.getFluidInTank(0);
        read.setAmount(1);
        helper.assertValueEqual(bay.millibuckets(), COPPER_CAPACITY,
                "a stack the handler hands out is a copy, so mutating it cannot change the bay");

        // Drained in one call: a fluid handler has no per-call cap at all, unlike an item one.
        FluidStack taken = handler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
        helper.assertValueEqual(taken.getAmount(), COPPER_CAPACITY, "one drain call takes everything there is");
        helper.assertTrue(FluidKey.of(Fluids.LAVA).matches(taken), "and it is what was stored");
        helper.assertValueEqual(bay.millibuckets(), 0, "the bay is empty");
        helper.assertTrue(bay.storedFluid().isEmpty(), "and forgets what it held");

        // What the client that draws the level is told, with no warehouse involved.
        bay.fill(new FluidStack(Fluids.WATER, ODD_FILL), false);
        HolderLookup.Provider registries = level.registryAccess();
        FluidBayBlockEntity onTheClient = detachedCopy(helper, bay);
        onTheClient.handleUpdateTag(bay.getUpdateTag(registries), registries);
        helper.assertValueEqual(onTheClient.millibuckets(), ODD_FILL,
                "the contents reach the client with no warehouse involved in syncing them");
        helper.assertValueEqual(onTheClient.storedFluid().orElse(null), FluidKey.of(Fluids.WATER), "fluid and all");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * One fluid at a time, learned from the first fluid that lands in an unfiltered bay and <b>forgotten</b> once it
     * empties, so a player can put up a tank wall and let it fill (the rack bay's rule, for fluids).
     * <p>
     * {@link IFluidHandler#isFluidValid} says no to the second fluid as well — the documented departure from that
     * method's contract — so a pipe backs up instead of hammering a bay that will never take its fluid.
     * <p>
     * The filter is where a fluid bay really differs from a rack bay: the slot holds a <b>container</b> and what is
     * read from it is the <b>fluid inside</b>, never the item ({@code FluidBayBlockEntity#filterFluid}). A lava bucket
     * in the slot therefore dedicates the bay to lava and not to lava buckets, and a Create list filter — which names
     * no fluid at all — leaves the bay unfiltered rather than refusing everything (D6).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayTakesOneFluidUntilItEmpties(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IFluidHandler handler = fluidHandlerAt(helper, LONE_BAY, null);
        FluidStack lava = new FluidStack(Fluids.LAVA, BUCKET);
        FluidStack water = new FluidStack(Fluids.WATER, BUCKET);

        helper.assertTrue(handler.isFluidValid(0, water.copy()), "an unfiltered, empty bay takes any fluid");
        helper.assertValueEqual(handler.fill(lava.copy(), FluidAction.EXECUTE), BUCKET, "the first fluid lands");
        helper.assertFalse(handler.isFluidValid(0, water.copy()), "and the bay says no to a second fluid");
        helper.assertValueEqual(handler.fill(water.copy(), FluidAction.EXECUTE), 0, "which it also refuses to take");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "without touching what is in it");
        helper.assertValueEqual(handler.drain(new FluidStack(Fluids.WATER, BUCKET), FluidAction.EXECUTE),
                FluidStack.EMPTY, "a fluid-sensitive drain of another fluid takes nothing");

        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET, "drained empty");
        helper.assertTrue(bay.storedFluid().isEmpty(), "an empty bay forgets its fluid");
        helper.assertValueEqual(handler.fill(water.copy(), FluidAction.EXECUTE), BUCKET,
                "and takes whatever comes next");
        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET, "emptied again");

        // The filter names a FLUID, read out of a container, and never the container itself.
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "a lava bucket goes in the slot");
        helper.assertValueEqual(bay.filterFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                "a filter holding a lava bucket dedicates the bay to lava");
        helper.assertValueEqual(bay.dedicatedFluid().orElse(null), FluidKey.of(Fluids.LAVA), "and says so");
        helper.assertValueEqual(handler.fill(water.copy(), FluidAction.EXECUTE), 0, "so water is refused");
        helper.assertFalse(handler.isFluidValid(0, water.copy()), "and said to be invalid before the call");
        helper.assertValueEqual(handler.fill(lava.copy(), FluidAction.EXECUTE), BUCKET, "while lava is taken");
        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET, "emptied once more");
        helper.assertValueEqual(bay.dedicatedFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                "a filtered bay keeps its dedication while it is empty");

        // An empty bucket names no fluid, so it leaves the bay unfiltered rather than dedicating it to nothing.
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.BUCKET)), "an empty bucket goes in the slot");
        helper.assertTrue(bay.filterFluid().isEmpty(), "an empty container names no fluid");
        helper.assertValueEqual(handler.fill(water.copy(), FluidAction.EXECUTE), BUCKET, "so every fluid is taken");
        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET, "emptied");

        // A Create list filter is not a fluid either. It is deliberately never evaluated as a Create filter at all:
        // one built from a water bucket would match the ITEM water_bucket and route containers instead of fluids.
        helper.assertTrue(bay.setStoreFilter(listFilter(new ItemStack(Items.LAVA_BUCKET))),
                "a Create list filter goes in the slot");
        helper.assertTrue(bay.filterFluid().isEmpty(), "and names no fluid, which the goggles say in gold");
        helper.assertValueEqual(handler.fill(water.copy(), FluidAction.EXECUTE), BUCKET,
                "so the bay is unfiltered rather than refusing everything");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    // --- pipes -----------------------------------------------------------------------------------------------------

    /**
     * <b>Pipes connect at the back and the sides, never at the aisle face</b> (D4), and a real Create pipe run really
     * fills a bay through one.
     * <p>
     * Create asks exactly one question to decide whether a pipe may connect to a block:
     * {@code FluidPropagator.hasFluidCapability}, i.e. whether {@code Capabilities.FluidHandler.BLOCK} answers for that
     * face — there is no interface to implement — and a positive answer also stops an open pipe end placing a source
     * block there. So the faces are asserted through that very call, and then through a pipe.
     * <p>
     * Two of the three claims are about faces that are <b>not</b> the aisle one, and the third is about none of them:
     * <ul>
     * <li>the aisle face answers nothing, so a pipe is never in the crane's lane and the one face a player clicks
     * keeps its own meaning;</li>
     * <li>the other five do, because in a real wall most faces are covered by neighbours — and the rule follows
     * {@code FACING} rather than a world direction, which is asserted by turning the bay;</li>
     * <li>a <b>{@code null}</b> query is answered as well, and that is not optional: a fluid census sweeps with
     * {@code null} ({@link FluidCensus}), and a block that answered a handler on a face but nothing for {@code null}
     * would be invisible to it while every conservation test stayed green.</li>
     * </ul>
     * Then a <b>real Create fluid pipe</b> is put at the back and at the aisle face, and Create's own connection
     * answers are read off it: a pipe at the back opens towards the bay, a pipe at the aisle face does not. The fluid
     * then moves through exactly the handler Create's {@code FluidNetwork} would use for that face.
     * <p>
     * <b>There is deliberately no Mechanical Pump in this test.</b> A pump is a kinetic block whose rotation axis is
     * its own flow axis, so powering one means a cogwheel mesh beside the run — a lot of apparatus for a question it
     * cannot answer any better: what a network does to a bay is decided by which face answers a handler (above) and by
     * what that handler does with a {@code fill} and a {@code drain} (below), and both are asserted directly.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void pipeFillsAFluidBayAtTheBackOnly(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), facing);
            FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
            helper.assertValueEqual(bay.aisleSide(), facing.getOpposite(), "the aisle side is behind the facing");
            helper.assertTrue(fluidHandlerAt(helper, LONE_BAY, null) != null,
                    "a fluid bay must answer a null query, or no fluid census could see it");
            for (Direction side : Direction.values()) {
                boolean aisle = side == bay.aisleSide();
                helper.assertValueEqual(fluidHandlerAt(helper, LONE_BAY, side) != null, !aisle,
                        "a bay facing " + facing + " offers a fluid connection on " + side);
                // The question Create itself asks, from the neighbouring block's point of view.
                helper.assertValueEqual(
                        FluidPropagator.hasFluidCapability(helper.getLevel(), helper.absolutePos(LONE_BAY), side),
                        !aisle, "Create sees a pipe connection on " + side + " of a bay facing " + facing);
            }
        }

        // A real Create fluid pipe at the back and one at the aisle face, with Create's own connection answers read
        // off them: the pipe states are what a player sees, and canConnectTo is what computes them.
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        BlockPos bayPos = helper.absolutePos(LONE_BAY);
        BlockState bayState = level.getBlockState(bayPos);
        BlockPos behind = LONE_BAY.relative(bay.facing());
        BlockPos inTheAisle = LONE_BAY.relative(bay.aisleSide());
        helper.setBlock(behind, AllBlocks.FLUID_PIPE.getDefaultState());
        helper.setBlock(inTheAisle, AllBlocks.FLUID_PIPE.getDefaultState());

        helper.assertTrue(FluidPipeBlock.canConnectTo(level, bayPos, bayState, bay.facing().getOpposite()),
                "a Create pipe behind the bay may connect to it");
        helper.assertFalse(FluidPipeBlock.canConnectTo(level, bayPos, bayState, bay.aisleSide().getOpposite()),
                "and a pipe in the aisle may not");
        helper.assertTrue(FluidPipeBlock.isOpenAt(helper.getBlockState(behind), bay.facing().getOpposite()),
                "so the pipe at the back really opens towards the bay");
        // The pipe in the aisle is deliberately NOT asserted to be closed towards the bay: Create draws a lone pipe
        // open along an axis whatever its neighbours are (`updateBlockState`'s "use preferred" fallback), so the state
        // flag is a picture and `canConnectTo` above is the decision. What that decision means is asserted where it
        // is read instead - the aisle face answers no handler at all, so no network can ever reach the bay there.
        helper.assertTrue(fluidHandlerAt(helper, LONE_BAY, bay.aisleSide()) == null,
                "and the aisle face answers no handler, so a pipe standing there reaches nothing");

        // And the transfer itself, through the very handler a fluid network would hold for that face.
        IFluidHandler throughThePipe = fluidHandlerAt(helper, LONE_BAY, bay.facing());
        helper.assertValueEqual(throughThePipe.fill(new FluidStack(Fluids.LAVA, 2 * BUCKET), FluidAction.EXECUTE),
                2 * BUCKET, "a pipe fills the bay through its back face");
        helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET, "which is really in the bay");
        helper.assertValueEqual(throughThePipe.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET,
                "and draws off through the same face at the shipped default");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "which really left it");
        helper.setBlock(behind, Blocks.AIR);
        helper.setBlock(inTheAisle, Blocks.AIR);
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * {@code storage.fluidBayPipeExtraction} decides whether a pipe may <b>draw off</b> a bay, and nothing else (D4).
     * <p>
     * Four claims, because the gate is deliberately narrow:
     * <ul>
     * <li>on (the shipped default): a pipe's drain takes fluid, both overloads;</li>
     * <li>off: both overloads answer {@code EMPTY} and the bay keeps every millibucket;</li>
     * <li>off: <b>filling is still allowed and reading is still honest</b>. The second half matters more than it
     * looks — a fluid census reads {@code getFluidInTank}, and if the gate reached that, a one-way tank's contents
     * would be invisible to every conservation test in the suite;</li>
     * <li>off: the bay's <b>own</b> handler still drains, which is what keeps a player's bucket and the crane's
     * exchange out of a config a server owner set for pipes.</li>
     * </ul>
     * Its own batch, because {@link ConfigOverrides} writes into the loaded config in memory and the tests of one batch
     * run at the same time.
     */
    @GameTest(template = EMPTY_7X5X7, batch = EXTRACTION_BATCH)
    public static void pipeExtractionFollowsTheConfig(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IFluidHandler pipe = fluidHandlerAt(helper, LONE_BAY, Direction.NORTH); // the back face, where a pipe sits
        FluidStack lava = new FluidStack(Fluids.LAVA, BUCKET);

        ConfigOverrides.set(helper, WareworksConfig.SERVER.fluidBayPipeExtraction, true);
        helper.assertValueEqual(pipe.fill(new FluidStack(Fluids.LAVA, 4 * BUCKET), FluidAction.EXECUTE), 4 * BUCKET,
                "a pipe fills a bay whatever the extraction config says");
        helper.assertValueEqual(pipe.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET,
                "and may draw off while that is allowed");
        helper.assertValueEqual(pipe.drain(lava.copy(), FluidAction.EXECUTE).getAmount(), BUCKET,
                "through either drain overload");
        helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET, "which really left the bay");

        ConfigOverrides.set(helper, WareworksConfig.SERVER.fluidBayPipeExtraction, false);
        helper.assertTrue(pipe.drain(BUCKET, FluidAction.SIMULATE).isEmpty(),
                "a one-way bay answers a simulated drain with nothing");
        helper.assertTrue(pipe.drain(BUCKET, FluidAction.EXECUTE).isEmpty(), "and a real one with nothing");
        helper.assertTrue(pipe.drain(lava.copy(), FluidAction.EXECUTE).isEmpty(), "through either overload");
        helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET, "so every millibucket stays in it");
        helper.assertValueEqual(pipe.getFluidInTank(0).getAmount(), 2 * BUCKET,
                "while the contents stay readable, or no fluid census could count a one-way bay");
        helper.assertValueEqual(pipe.fill(lava.copy(), FluidAction.EXECUTE), BUCKET, "and filling is never gated");
        helper.assertValueEqual(bay.drain(BUCKET, false).getAmount(), BUCKET,
                "the bay's own handler is not gated either: a player's bucket is never refused by this config");
        // And the rule is never invisible: the goggle row states which of the two it is, whichever way it stands
        // (D4). It is asserted here rather than beside the other goggle rows, because this is the test that owns the
        // config override and restores it.
        helper.assertTrue(goggleKeys(bay).contains(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_PIPES_FILL)),
                "a one-way bay says so on its goggles: " + goggleKeys(bay));
        ConfigOverrides.set(helper, WareworksConfig.SERVER.fluidBayPipeExtraction, true);
        helper.assertTrue(goggleKeys(bay).contains(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW)),
                "and a bay pipes may tap says that instead: " + goggleKeys(bay));
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /** Restores the config whatever {@link #pipeExtractionFollowsTheConfig} did, also after a failure. */
    @AfterBatch(batch = EXTRACTION_BATCH)
    public static void restoreExtractionConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- capacity --------------------------------------------------------------------------------------------------

    /**
     * The two capacities are <b>server config</b>, measured against a configuration that is not the shipped one, and
     * then <b>lowered under a bay that is already full</b>.
     * <p>
     * <b>This is the test that pins the trap NeoForge's own {@code FluidTank} falls into.</b> With
     * {@code capacity < getFluidAmount()} — which lowering a config under a standing bay legitimately produces —
     * {@code FluidTank.fill} computes {@code filled = capacity - amount}, which is negative, and then runs
     * {@code fluid.setAmount(capacity)} anyway: the first fill after a config change silently drops the tank to its new
     * capacity and reports a negative number. A fluid bay keeps every millibucket and accepts nothing instead, which is
     * the promise its config comment makes in the same words M28's made for items.
     * <p>
     * Its own batch, for {@link #pipeExtractionFollowsTheConfig}'s reason.
     */
    @GameTest(template = EMPTY_7X5X7, batch = CAPACITY_BATCH)
    public static void fluidBayCapacityFromConfig(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.copperFluidBayBuckets, CONFIGURED_COPPER_BUCKETS);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.brassFluidBayBuckets, CONFIGURED_BRASS_BUCKETS);

        assertConfiguredCapacity(helper, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), FluidBayTier.COPPER,
                CONFIGURED_COPPER_BUCKETS);
        assertConfiguredCapacity(helper, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), FluidBayTier.BRASS,
                CONFIGURED_BRASS_BUCKETS);

        // A copper bay filled to the configured capacity, and then the configuration lowered under it.
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IFluidHandler handler = fluidHandlerAt(helper, LONE_BAY, null);
        int full = CONFIGURED_COPPER_BUCKETS * BUCKET;
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.LAVA, full), FluidAction.EXECUTE), full,
                "the bay is full");
        ConfigOverrides.set(helper, WareworksConfig.SERVER.copperFluidBayBuckets, LOWERED_COPPER_BUCKETS);
        helper.assertValueEqual(bay.buckets(), LOWERED_COPPER_BUCKETS, "the bay reads the lowered capacity at once");
        helper.assertValueEqual(bay.millibuckets(), full, "and keeps every millibucket it already held");
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.LAVA, 1), FluidAction.EXECUTE), 0,
                "it accepts not one millibucket more");
        helper.assertValueEqual(bay.millibuckets(), full,
                "and that refused fill changed nothing, which is exactly what FluidTank.fill gets wrong");
        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET,
                "while draining normally");
        helper.assertValueEqual(bay.millibuckets(), full - BUCKET, "millibucket for millibucket");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /** Restores the capacities whatever {@link #fluidBayCapacityFromConfig} did, also after a failure. */
    @AfterBatch(batch = CAPACITY_BATCH)
    public static void restoreCapacityConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Fails unless a bay of {@code tier} holds exactly {@code buckets} buckets under the current configuration, and not
     * one millibucket more.
     */
    private static void assertConfiguredCapacity(GameTestHelper helper, BlockState bayState, FluidBayTier tier,
                                                 int buckets) {
        placeBay(helper, LONE_BAY, bayState, Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        helper.assertValueEqual(bay.buckets(), buckets, "configured buckets of " + tier);
        int capacity = buckets * BUCKET;
        helper.assertValueEqual(bay.capacity(), (long) capacity, tier + " holds " + buckets + " buckets");
        IFluidHandler handler = fluidHandlerAt(helper, LONE_BAY, null);
        helper.assertValueEqual(handler.getTankCapacity(0), capacity, tier + " reports its configured capacity");
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.WATER, capacity), FluidAction.EXECUTE), capacity,
                tier + " takes its configured capacity in one call");
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE), 0,
                tier + " takes not one millibucket more");
        removeBay(helper, LONE_BAY);
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    /**
     * 256 000 millibuckets, a filter and a storage priority through a save and a reload, twice — because a load that
     * truncated would still have looked right on the first pass.
     * <p>
     * It also pins that nothing on this path ever goes through {@code FluidStack.save}, which <b>throws</b> on an empty
     * stack, and that {@code writeSafe} — the schematic path — carries the filter and the priority and <b>not one
     * drop</b>: a schematicannon printing a full brass bay would be a pocketable 256-bucket lava supply.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayPersistenceRoundTrip(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();

        helper.assertValueEqual(bay.capacity(), (long) BRASS_CAPACITY, "a brass bay holds 256 buckets");
        helper.assertValueEqual(fluidHandlerAt(helper, LONE_BAY, null)
                .fill(new FluidStack(Fluids.LAVA, BRASS_CAPACITY), FluidAction.EXECUTE), BRASS_CAPACITY,
                "the bay takes its whole load");
        bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET));
        bay.setStorePriority(7);

        FluidBayBlockEntity reloaded = bay;
        for (int pass = 1; pass <= 2; pass++) {
            CompoundTag saved = reloaded.saveWithFullMetadata(registries);
            helper.assertValueEqual(saved.getInt(FluidBayHandler.AMOUNT_TAG), BRASS_CAPACITY,
                    "the amount is saved as an int of millibuckets");
            helper.assertTrue(saved.contains(FluidBayHandler.FLUID_TAG), "and the fluid beside it");
            reloaded = loadCopy(helper, reloaded, saved);
            helper.assertValueEqual(reloaded.millibuckets(), BRASS_CAPACITY, "the whole load came back, pass " + pass);
            helper.assertValueEqual(reloaded.storedFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                    "as lava, pass " + pass);
            helper.assertValueEqual(reloaded.storePriority(), 7, "with its priority, pass " + pass);
            helper.assertValueEqual(reloaded.filterFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                    "and its filter, pass " + pass);
            helper.assertValueEqual(fluidHandlerAt(helper, LONE_BAY, null).getFluidInTank(0).getAmount(),
                    BRASS_CAPACITY, "and the live capability says so, pass " + pass);
        }

        // The schematic path: settings travel, fluid never does.
        CompoundTag safe = new CompoundTag();
        reloaded.writeSafe(safe, registries);
        helper.assertFalse(safe.contains(FluidBayHandler.FLUID_TAG), "a schematic carries no stored fluid");
        helper.assertFalse(safe.contains(FluidBayHandler.AMOUNT_TAG), "and no amount");
        helper.assertTrue(safe.contains(StorageFilterBehaviour.PRIORITY_TAG), "but it carries the priority");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * Save data is untrusted — a world written before fluid bays existed, {@code /data merge}, an uploaded schematic
     * and crafted block entity data all reach the same {@code read}. None of it may throw, and the one case a
     * legitimate save really produces must not lose a drop:
     * <ul>
     * <li>no tag at all, <b>read into a fresh block entity</b>: an empty bay, which is exactly what a pre-M30 world
     * has. The same tag read into a bay that is already <b>standing with fluid in it</b> is a different question with
     * a different answer — it says nothing about the contents, so it changes nothing, which is what a Create schematic
     * print over a standing bay does;</li>
     * <li>an amount with no fluid, and a fluid that cannot be decoded (its mod was removed): empty;</li>
     * <li>a crafted amount of {@link Integer#MAX_VALUE}: clamped to what no configuration can exceed;</li>
     * <li>an amount above <b>this</b> bay's capacity, which lowering the config produces: <b>kept in full</b>, and the
     * bay accepts nothing until it drains.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBaySurvivesAnySaveData(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        helper.assertValueEqual(readBack(helper, bay, new CompoundTag()).millibuckets(), 0,
                "a bay written before M30 reads as empty");

        CompoundTag amountOnly = new CompoundTag();
        amountOnly.putInt(FluidBayHandler.AMOUNT_TAG, 4 * BUCKET);
        helper.assertValueEqual(readBack(helper, bay, amountOnly).millibuckets(), 0,
                "an amount with no fluid is empty");

        CompoundTag unreadable = new CompoundTag();
        unreadable.putInt(FluidBayHandler.AMOUNT_TAG, 4 * BUCKET);
        CompoundTag brokenKey = new CompoundTag();
        brokenKey.putString("id", "wareworks:a_fluid_of_a_mod_that_was_removed");
        unreadable.put(FluidBayHandler.FLUID_TAG, brokenKey);
        helper.assertValueEqual(readBack(helper, bay, unreadable).millibuckets(), 0,
                "a fluid that cannot be decoded is empty, as in a vanilla container");

        CompoundTag crafted = new CompoundTag();
        crafted.putInt(FluidBayHandler.AMOUNT_TAG, Integer.MAX_VALUE);
        FluidKey.of(Fluids.LAVA).saveTo(crafted, FluidBayHandler.FLUID_TAG, registries);
        helper.assertValueEqual(readBack(helper, bay, crafted).millibuckets(),
                FluidBayTier.MAX_CAPACITY_MILLIBUCKETS,
                "a crafted amount is clamped to what no configuration can exceed");

        // A capacity lowered under a bay that is already fuller than it: it keeps everything and takes nothing.
        CompoundTag overFull = new CompoundTag();
        overFull.putInt(FluidBayHandler.AMOUNT_TAG, BRASS_CAPACITY);
        FluidKey.of(Fluids.LAVA).saveTo(overFull, FluidBayHandler.FLUID_TAG, registries);
        FluidBayBlockEntity lowered = readBack(helper, bay, overFull);
        helper.assertValueEqual(lowered.millibuckets(), BRASS_CAPACITY, "an over-full copper bay keeps every drop");
        IFluidHandler handler = fluidHandlerAt(helper, LONE_BAY, null);
        helper.assertValueEqual(handler.fill(new FluidStack(Fluids.LAVA, BUCKET), FluidAction.EXECUTE), 0,
                "and accepts nothing until it has drained");
        helper.assertValueEqual(handler.drain(BUCKET, FluidAction.EXECUTE).getAmount(), BUCKET,
                "while draining normally");

        // The same contentless tag, but read into a bay that is ALREADY STANDING with fluid in it. That is not a world
        // load - a world load always reads into a freshly built, empty handler - it is what Create's schematic
        // placement does: BlockHelper.placeSchematicBlock writes the state and then calls loadWithComponents on the
        // block entity that is still there, with a tag that carries only the filter and the priority. Without the
        // guard in FluidBayHandler#readFrom it would empty a full bay in place, with not even the log line a lost
        // fluid is owed.
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity standing = bayAt(helper, LONE_BAY);
        standing.fill(new FluidStack(Fluids.LAVA, ODD_FILL), false);
        CompoundTag settingsOnly = new CompoundTag();
        standing.writeSafe(settingsOnly, registries);
        helper.assertFalse(settingsOnly.contains(FluidBayHandler.AMOUNT_TAG), "such a tag carries no amount");
        helper.assertFalse(settingsOnly.contains(FluidBayHandler.FLUID_TAG), "and no fluid");
        standing.loadWithComponents(settingsOnly, registries);
        helper.assertValueEqual(standing.millibuckets(), ODD_FILL, "so a standing bay keeps its whole load");
        helper.assertValueEqual(standing.storedFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                "and the fluid it is committed to");

        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * {@code /setblock}, {@code /fill}, {@code /clone} and structure placement call {@link Clearable#tryClear} before
     * they replace a block, and a fluid bay is emptied without placing or dropping anything — deliberate parity with a
     * vanilla chest and with Create's own Fluid Tank, which loses its fluid to the same commands.
     * <p>
     * "Placing" is the half a fluid makes new: nothing here may leave a source block behind, or {@code /fill} over a
     * tank wall would flood a warehouse with lava.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBaySetBlockVoidsItLikeAVanillaTank(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        bay.fill(new FluidStack(Fluids.LAVA, BRASS_CAPACITY), false);
        helper.assertValueEqual(bay.millibuckets(), BRASS_CAPACITY, "the bay is loaded");

        // Exactly what SetBlockCommand does before it writes the new state.
        Clearable.tryClear(bay);
        helper.assertValueEqual(bay.millibuckets(), 0, "the bay was emptied");
        helper.assertTrue(bay.storedFluid().isEmpty(), "and forgot its fluid");
        helper.setBlock(LONE_BAY, Blocks.STONE);
        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(LONE_BAY)).isEmpty(),
                "and left no fluid behind in the world");
        helper.assertTrue(helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS), entity -> true).isEmpty(),
                "nothing was dropped, exactly as for a Create tank");
        helper.succeed();
    }

    /**
     * The client packet is a <b>registry id and an int</b>, whatever is in the bay — a bay's update tag is part of
     * every chunk packet, and it is sent on every content change without any throttle, because the level is the
     * readout.
     * <p>
     * The fluid carries a <b>custom name</b> here, which is what would drag an unbounded component patch onto the wire
     * if anything did: a fluid's components are not bounded by anything, unlike an item's count.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBaySyncIsBounded(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        FluidStack named = new FluidStack(Fluids.LAVA, BRASS_CAPACITY);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(LONG_NAME_LENGTH)));
        helper.assertValueEqual(bay.fill(named, false), BRASS_CAPACITY, "the bay takes a named fluid like any other");
        CompoundTag tag = bay.getUpdateTag(registries);
        helper.assertValueEqual(tag.getString(FluidBayHandler.STORED_FLUID_TAG), "minecraft:lava",
                "the wire carries the fluid's id");
        helper.assertValueEqual(tag.getInt(FluidBayHandler.AMOUNT_TAG), BRASS_CAPACITY, "and the amount as one int");
        helper.assertFalse(tag.contains(FluidBayHandler.FLUID_TAG), "and never a fluid key");
        helper.assertFalse(tag.toString().contains("x".repeat(LONG_NAME_LENGTH)),
                "so no fluid component data of any kind reaches a chunk packet");
        helper.assertFalse(tag.contains("Filter"), "an unfiltered bay must not sync a filter slot");
        // The address rides along, because the goggles are drawn on the client and an address is the one thing a
        // client cannot work out for itself. It is written whatever it is, so a bay whose warehouse was broken can go
        // back to "not part of an aisle" (M30 step 6).
        helper.assertTrue(tag.contains(FluidBayBlockEntity.ASSIGNMENT_TAG), "and the address is in the packet");
        // A bay nothing is driving towards reserves nothing, so an idle tank wall pays only for the assignment. The
        // row itself exists and is asserted where it can be real, with a crane carrying a container towards a bay
        // (ContainerExchangeJobGameTests.fluidBayExchangeRunsAsPartOfACraneJob).
        helper.assertFalse(tag.contains(FluidBayBlockEntity.RESERVATIONS_TAG),
                "a bay nobody is serving syncs no reservations");
        int size = tag.sizeInBytes();
        Wareworks.LOGGER.debug("Fluid bay update tag: {} bytes", size);
        helper.assertTrue(size < MAX_FLUID_BAY_SYNC_BYTES,
                "a bay's update tag must stay small, but has " + size + " bytes");

        FluidBayBlockEntity client = detachedCopy(helper, bay);
        client.handleUpdateTag(tag, registries);
        helper.assertValueEqual(client.millibuckets(), BRASS_CAPACITY, "the client knows how full the bay is");
        helper.assertValueEqual(client.storedFluid().orElse(null), FluidKey.of(Fluids.LAVA),
                "and reads the named fluid as its plain self, which is the price of the bound");

        // Nothing a server sends can make a client draw something impossible.
        FluidBayBlockEntity nonsense = detachedCopy(helper, bay);
        CompoundTag crafted = new CompoundTag();
        crafted.putString(FluidBayHandler.STORED_FLUID_TAG, "nosuchmod:nosuchfluid");
        crafted.putInt(FluidBayHandler.AMOUNT_TAG, Integer.MAX_VALUE);
        nonsense.handleUpdateTag(crafted, registries);
        helper.assertTrue(nonsense.storedFluid().isEmpty(), "a fluid the client does not have reads as empty");
        crafted.putString(FluidBayHandler.STORED_FLUID_TAG, "minecraft:empty");
        nonsense.handleUpdateTag(crafted, registries);
        helper.assertTrue(nonsense.storedFluid().isEmpty(), "and so does minecraft:empty");
        crafted.putString(FluidBayHandler.STORED_FLUID_TAG, "minecraft:lava");
        nonsense.handleUpdateTag(crafted, registries);
        helper.assertValueEqual(nonsense.millibuckets(), FluidBayTier.MAX_CAPACITY_MILLIBUCKETS,
                "and an impossible amount is bounded, not believed");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    // --- the shared bay machinery over the fluid ladder ------------------------------------------------------------

    /**
     * The column rule, the join flags and the overload flag are {@link BayColumn}'s, and this is them running on the
     * <b>fluid</b> ladder (M30 step 3, ADR-044, ADR-050):
     * <ul>
     * <li>a placement whose column would carry <b>brass above copper</b> is refused, and so is one that would put
     * <b>copper under brass</b> — both directions, so the illegal column cannot be built from either end — with the
     * <b>fluid</b> bay's own sentence, because a player holding a tank must not be told about rack bays;</li>
     * <li>a <b>rack</b> bay above a fluid bay <b>ends</b> the column rather than being refused by it, which is the
     * whole difference between "a gap is two racks" and "a tank on a rack is illegal". It has real teeth: without the
     * family gate the brass placed above it would be compared with the rack bay and refused, because
     * {@code FluidBayBlock.mayCarry} answers false for anything that is not a fluid bay;</li>
     * <li>joining <b>is</b> across families: a fluid bay placed by clicking the side of a rack bay copies that bay's
     * facing and shares its upright, because a wall is a wall;</li>
     * <li>a column a command broke flags the bay below on its scheduled tick, which stops being a store target while
     * keeping every drop it holds.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayColumnRuleAndJoins(GameTestHelper helper) {
        BlockState copper = WareworksBlocks.FLUID_BAY_COPPER.getDefaultState();
        BlockState brass = WareworksBlocks.FLUID_BAY_BRASS.getDefaultState();
        BlockState rack = WareworksBlocks.RACK_BAY_BRASS.getDefaultState();

        helper.assertValueEqual(((TieredBay) copper.getBlock()).bayFamily(), BayFamily.FLUID,
                "a fluid bay is on the fluid ladder");

        // Strength never rises upwards: brass carries copper, copper carries no brass. Refused from both ends.
        placeBay(helper, COLUMN_FOOT, brass, Direction.NORTH);
        placeByHand(helper, COLUMN_FOOT.above(), copper);
        assertPlacementRefused(helper, COLUMN_FOOT.above(2), brass,
                "the column must refuse brass on top of copper");
        placeBay(helper, MIXED_HEAD, brass, Direction.NORTH);
        assertPlacementRefused(helper, MIXED_HEAD.below(), copper,
                "the column must refuse copper under brass");

        // A rack bay ENDS a fluid column: it is never compared with one, so the brass above it is legal although
        // the copper two blocks down could not carry it.
        helper.setBlock(COLUMN_FOOT.above(2), rack.setValue(RackBayBlock.FACING, Direction.NORTH));
        helper.assertTrue(BayColumn.sameFamilyBay(helper.getBlockState(COLUMN_FOOT.above(2)), BayFamily.FLUID) == null,
                "a rack bay is no fluid bay, so a fluid column ends at it");
        placeByHand(helper, COLUMN_FOOT.above(3), brass);

        helper.startSequence()
                .thenExecuteAfter(REPAIR_TICKS, () -> {
                    assertOverloaded(helper, COLUMN_FOOT, false, "a brass bay under copper is fine");
                    assertOverloaded(helper, COLUMN_FOOT.above(), false, "and so is the one under a rack bay");
                    assertOverloaded(helper, COLUMN_FOOT.above(3), false,
                            "a brass bay above a rack bay carries nothing of its own family");
                })
                .thenExecute(() -> {
                    // A command puts brass straight on top of copper: the bay below says so and stops being a store
                    // target, while keeping every drop it holds.
                    FluidBayBlockEntity commanded = bayAt(helper, COLUMN_FOOT.above());
                    commanded.fill(new FluidStack(Fluids.LAVA, ODD_FILL), false);
                    // It replaces the rack bay, whose own claim above has been made; the brass on top of it then
                    // stands on brass, which is legal.
                    helper.setBlock(COLUMN_FOOT.above(2), brass.setValue(FluidBayBlock.FACING, Direction.NORTH));
                })
                .thenExecuteAfter(REPAIR_TICKS, () -> {
                    assertOverloaded(helper, COLUMN_FOOT.above(), true, "a copper bay under brass flags itself");
                    helper.assertValueEqual(bayAt(helper, COLUMN_FOOT.above()).millibuckets(), ODD_FILL,
                            "and keeps every drop while it says so");
                    assertOverloaded(helper, COLUMN_FOOT, true,
                            "and so does the brass bay below it: something above is giving way");
                })
                .thenExecute(() -> {
                    // Joining is across families: a fluid bay placed by clicking the side of a rack bay takes that
                    // bay's facing and shares its upright. Both face NORTH, so the two stand side by side along X,
                    // which is the only way a wall can be built.
                    helper.setBlock(SECOND_BAY, rack.setValue(RackBayBlock.FACING, Direction.NORTH));
                    placeBySideClick(helper, SECOND_BAY, Direction.EAST, copper);
                })
                .thenExecute(() -> {
                    BlockState placed = helper.getBlockState(LONE_BAY);
                    helper.assertValueEqual(placed.getValue(FluidBayBlock.FACING), Direction.NORTH,
                            "a click on a bay's side copies that bay's facing across families");
                    helper.assertTrue(placed.getValue(TieredBay.LEFT),
                            "and the two share the upright between them, because a wall is a wall");
                    helper.assertTrue(placed.skipRendering(helper.getBlockState(SECOND_BAY), Direction.WEST),
                            "so each of them drops the faces the seam buries, across the two families");
                    helper.assertTrue(helper.getBlockState(SECOND_BAY)
                            .skipRendering(placed, Direction.EAST),
                            "both halves of it, so neither looks into the other's geometry");
                    for (BlockPos pos : List.of(COLUMN_FOOT, COLUMN_FOOT.above(), COLUMN_FOOT.above(2),
                            COLUMN_FOOT.above(3), MIXED_HEAD, LONE_BAY, SECOND_BAY))
                        removeBay(helper, pos);
                })
                .thenSucceed();
    }

    // --- the bay as a storage location -----------------------------------------------------------------------------

    /**
     * A fluid bay in a running warehouse: it <b>is</b> a storage location — it joins an aisle, is recorded and gets an
     * address with no change to the controller — and it holds <b>no items at all</b>.
     * <p>
     * The structural half is asserted on the bay: no attached item handler and a <b>zero-slot</b> snapshot, which is
     * the documented "no inventory is attached" and precisely the state a warehouse interface whose chest was taken
     * away reports. The two report it for <i>opposite</i> reasons, which is why the store gate tells them apart by what
     * the bay is rather than by reading anything into an empty snapshot (M30 step 9,
     * {@link FluidBayStoreGateGameTests}).
     * <p>
     * The end-to-end half is about an <b>ordinary item</b>, and that is what this test is for: cobblestone arrives at
     * the input of an aisle whose only two storage locations are a fluid bay dedicated to lava and a chest, and it is
     * carried to the <b>chest</b>. A fluid bay refuses every item that carries no fluid, whatever room it has and
     * whatever its priority — so it can neither swallow ordinary stock nor park the crane in front of itself — and the
     * joint item/fluid census runs on every tick, so a drop of lava that moved while an item was being stored would
     * fail here.
     * <p>
     * Where the <b>filled</b> container goes is {@link FluidBayStoreGateGameTests}' subject, and it goes to the bay.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayIsAStorageLocationThatHoldsNoItems(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        placeBay(helper, bayPos, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), aisle.sideDirection(BAY_RACK));
        FluidBayBlockEntity bay = bayAt(helper, bayPos);
        bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET));
        bay.fill(new FluidStack(Fluids.LAVA, ODD_FILL), false);
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);

        helper.assertTrue(bay.attachedHandler().isEmpty(), "a fluid bay offers no item handler to the controller");
        helper.assertTrue(bay.snapshot().slots().isEmpty(), "and a zero-slot snapshot: it counts no items");
        helper.assertFalse(bay.holdsOneTypeOnly(), "the one-item-type question is meaningless for it");
        helper.assertTrue(bay.acceptsStoring(), "while it is not overloaded it is an ordinary store candidate");

        ItemKey filledBucket = ItemKey.of(Items.LAVA_BUCKET);
        ItemKey ordinary = ItemKey.of(Items.COBBLESTONE);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of(FluidKey.of(Fluids.LAVA), ODD_FILL);
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "while a fluid bay serves an aisle"));

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 0);
                    helper.assertValueEqual(aisle.controller().countOf(filledBucket), 0L,
                            "a fluid bay counts no items of its own");
                })
                // The address a crane can reach reaches the goggles, and the client that draws them (M30 step 6). It
                // is asserted in this test rather than in a second aisle of its own, because the warehouse that makes
                // the question meaningful is already standing here.
                .thenExecute(() -> {
                    bay.onGoggleObserved();
                    helper.assertValueEqual(bay.aisleAssignment().state(), AisleAssignment.State.ASSIGNED,
                            "a fluid bay a crane can reach has an address");
                    FluidBayBlockEntity client = detachedCopy(helper, bay);
                    client.handleUpdateTag(bay.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.aisleAssignment(), bay.aisleAssignment(),
                            "which reaches the client that draws it");
                    helper.assertValueEqual(client.millibuckets(), ODD_FILL, "together with the contents");
                    helper.assertFalse(goggleKeys(bay)
                            .contains(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE)),
                            "and the line for a bay no warehouse serves is gone: " + goggleKeys(bay));
                })
                // A high priority on the bay as well as its dedication: neither may make the planner offer it an item
                // that carries no fluid, because a fluid bay has no room for one at any priority.
                .thenExecute(() -> {
                    bay.setStorePriority(9);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), ordinary.toStack(4));
                    ItemCensus.change(items, ordinary, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, ordinary), 4L,
                        "an ordinary item is stored in the chest, never at the fluid bay"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), ODD_FILL,
                            "and the bay's own contents never moved");
                    helper.assertValueEqual(aisle.controller().countOf(ordinary), 4L,
                            "all four are counted, once, in the chest");
                    helper.assertValueEqual(aisle.inventoryCount(bayPos, ordinary), 0L,
                            "and none of them at the bay, which answers no item handler at all (D3)");
                    helper.assertValueEqual(aisle.controller().countOf(filledBucket), 0L,
                            "a fluid bay counts no items of its own, still");
                    aisle.assertIdleAndEmpty();
                    removeBay(helper, bayPos);
                })
                .thenSucceed();
    }

    // --- hands, goggles and the break (M30 step 6) -----------------------------------------------------------------

    /**
     * The gesture the issue settled: <b>a right-click with a filled container empties it into the bay, and one with an
     * empty container fills it from the bay</b> ({@link FluidBayGestures}).
     * <p>
     * Four things it pins are not about convenience:
     * <ul>
     * <li><b>a container's click is consumed even when nothing moves.</b> A click that is passed on reaches the item's
     * own use, and a bucket of lava's own use <b>places a lava source</b> against the face it was aimed at — so a bay
     * that is full, holds another fluid or is filtered against it would set a wooden rack wall on fire the moment a
     * player tried to pour into it. The test aims a lava bucket at a water-filtered bay and insists that nothing
     * moved, nothing was placed and the click was still answered;</li>
     * <li><b>Shift is not this block's gesture</b> and is passed straight on, because a container is one container and
     * there is no larger amount for it to mean — which is also what keeps sneak-placing a block against a tank's face
     * working, and why this class needs no interaction listener where {@link RackBayGestures} needs one;</li>
     * <li>everything that is not a container keeps its own meaning: a wrench, a clipboard, the Mechanical Arm item,
     * <b>any bay of either family</b> (which is how a mixed wall is built) and a plain block;</li>
     * <li>a {@code FakePlayer} is refused in both directions — a Deployer in the aisle would otherwise empty a tank
     * wall bucket by bucket, which is what {@code StorageFilterBehaviour#mayInteract} refuses it at the value box
     * for.</li>
     * </ul>
     * Conservation is counted by hand, because neither census can see a player's inventory and a hand gesture is
     * exactly a transfer between the two: every assertion compares the bay's fluid plus the fluid in the player's own
     * containers against one number that must never change.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayHandGestures(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        NamingClick.Teller player = NamingClick.player(helper);
        player.getInventory().clearContent();

        // What the gesture must not take, asked first and on an EMPTY, UNFILTERED bay - the one state in which the bay
        // would accept any fluid at all, so a pass-through that happens for the wrong reason cannot hide behind "the
        // bay was committed to something else anyway".
        assertPassesThrough(helper, bay, player, AllItems.WRENCH.asStack(), "a wrench, which turns the bay");
        assertPassesThrough(helper, bay, player, AllBlocks.CLIPBOARD.asStack(),
                "a clipboard, which copies the filter and the priority onto a whole tank wall");
        assertPassesThrough(helper, bay, player, AllBlocks.MECHANICAL_ARM.asStack(),
                "the Mechanical Arm item, which places an arm");
        assertPassesThrough(helper, bay, player, new ItemStack(Items.COBBLESTONE),
                "a plain block, which is placed against the face as it always was");
        // Any bay of either family builds the wall, because joining is across families (ADR-050): a bay that swallowed
        // the next one would make a mixed wall unbuildable by hand.
        for (BlockState wall : List.of(WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(),
                WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(),
                WareworksBlocks.RACK_BAY_WOOD.getDefaultState(),
                WareworksBlocks.RACK_BAY_BRASS.getDefaultState()))
            assertPassesThrough(helper, bay, player, new ItemStack(wall.getBlock()),
                    "a " + wall.getBlock() + ", which builds the wall");

        // The aisle naming a player learned at a warehouse interface: answered in one line, and the renamed container
        // they were holding out at the bay stays filled in their hand instead of being emptied into it.
        NamingClick.Teller namer = NamingClick.player(helper);
        NamingClick.use(helper, namer, LONE_BAY, Direction.SOUTH, NamingClick.renamed(Items.LAVA_BUCKET, "Hot"));
        NamingClick.assertTold(helper, namer, "a renamed container on a fluid bay", WareworksLang.BAY_NO_NAMING);
        helper.assertValueEqual(bay.millibuckets(), 0, "a renamed container is never emptied by hand");
        helper.assertTrue(namer.getMainHandItem().is(Items.LAVA_BUCKET), "and the player keeps it, filled");

        // Putting fluid in: one bucket, whole, and the empty bucket is what is left in the hand.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getMainHandItem(), false),
                ItemInteractionResult.CONSUME, "a plain click with a filled bucket is the bay's own gesture");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "a bucket of lava went in whole");
        helper.assertValueEqual(bay.storedFluid().orElse(null), FluidKey.of(Fluids.LAVA), "as lava");
        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "and the hand holds the empty bucket");
        assertNoFluidLost(helper, bay, player, BUCKET, "after a bucket was emptied into the bay");

        // ... and taking it back out again with the very bucket that is now in the hand.
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getMainHandItem(), false),
                ItemInteractionResult.CONSUME, "and so is a click with an empty one");
        helper.assertValueEqual(bay.millibuckets(), 0, "which fills from the bay");
        helper.assertTrue(bay.storedFluid().isEmpty(), "so an emptied bay forgets its fluid, as it does for a pipe");
        helper.assertTrue(player.getMainHandItem().is(Items.LAVA_BUCKET), "and the hand holds the filled bucket");
        assertNoFluidLost(helper, bay, player, BUCKET, "after a bucket was filled from the bay");

        // EITHER HAND, and that is the safety rule above rather than a convenience (M30 review fix). Vanilla offers
        // a block the OFF hand too - Minecraft#startUseItem walks both hands, and only the empty-handed interaction
        // is main hand only - so a gesture answering the main hand alone handed a bucket of lava carried in the off
        // hand straight to BucketItem#use, which pours it against the aisle face. Reached by nothing more exotic
        // than keeping the bucket in the other hand, with the main hand empty or holding any item of its own.
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        helper.assertValueEqual(click(helper, player, LONE_BAY, new ItemStack(Items.LAVA_BUCKET), false,
                InteractionHand.OFF_HAND), ItemInteractionResult.CONSUME,
                "a filled bucket in the off hand is the bay's own gesture too");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "and a bucket of lava really went in");
        helper.assertTrue(player.getOffhandItem().is(Items.BUCKET), "with the empty bucket left in that hand");
        helper.assertTrue(player.getMainHandItem().is(Items.DIAMOND_PICKAXE), "and the main hand untouched");
        assertNoFluidLost(helper, bay, player, BUCKET, "after the off hand emptied a bucket into the bay");
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getOffhandItem(), false,
                InteractionHand.OFF_HAND), ItemInteractionResult.CONSUME, "and so is an empty one in that hand");
        helper.assertValueEqual(bay.millibuckets(), 0, "which fills from the bay");
        helper.assertTrue(player.getOffhandItem().is(Items.LAVA_BUCKET), "back into the off hand");
        assertNoFluidLost(helper, bay, player, BUCKET, "after the off hand filled a bucket from the bay");
        // A container the bay cannot serve keeps its click in that hand as well, which is the whole point: an answer
        // only for the main hand is no safety rule at all.
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.WATER_BUCKET)), "the bay is dedicated to water");
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getOffhandItem(), false,
                InteractionHand.OFF_HAND), ItemInteractionResult.CONSUME,
                "a refused container in the off hand is answered rather than passed to the bucket's own use");
        helper.assertValueEqual(bay.millibuckets(), 0, "nothing entered the bay");
        helper.assertTrue(player.getOffhandItem().is(Items.LAVA_BUCKET), "and the player keeps the lava");
        bay.setStoreFilter(ItemStack.EMPTY);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

        // A hand holding more than one container fills exactly ONE of them and stows it, which is FluidUtil's own
        // rule and the only correct one: every container operation needs a stack of exactly one.
        bay.fill(new FluidStack(Fluids.LAVA, 4 * BUCKET), false);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET, 16));
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getMainHandItem(), false),
                ItemInteractionResult.CONSUME, "a stack of empty buckets is the gesture too");
        helper.assertValueEqual(bay.millibuckets(), 3 * BUCKET, "exactly one bucket's worth left the bay");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 15, "one empty bucket left the stack");
        helper.assertValueEqual(inventoryCount(player, Items.LAVA_BUCKET), 1,
                "and the filled one was stowed in the player's own inventory");
        assertNoFluidLost(helper, bay, player, 4 * BUCKET, "after one of sixteen buckets was filled");

        // THE SAFETY RULE, driven through the WHOLE vanilla sequence rather than through the block alone: a bay that
        // cannot serve the container still answers the click, because a click passed on reaches the bucket's own use
        // and that places a lava source against the face it was aimed at. The bay is dedicated to water here, which
        // is one of the three ways a bay refuses a container a player holds out at it.
        player.getInventory().clearContent();
        helper.assertValueEqual(bay.drain(Integer.MAX_VALUE, false).getAmount(), 3 * BUCKET, "the bay is emptied");
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.WATER_BUCKET)), "and dedicated to water");
        aimAtAisleFace(helper, player, LONE_BAY);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
        ItemInteractionResult answer = click(helper, player, LONE_BAY, player.getMainHandItem(), false);
        // What Minecraft#startUseItem does next when a block passes a click on: the item's own use, which for a
        // bucket is a raycast from the player's eyes. It runs here only if the gesture really passed the click on,
        // so the assertions below are about the rule and not about this call.
        if (answer == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)
            player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        // The consequence first, because it is what the rule is for: a warehouse that is not on fire.
        BlockPos infront = LONE_BAY.relative(Direction.SOUTH);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(infront)).isAir(),
                "NO lava may be placed in front of a bay that refused it: that is what consuming the click is for");
        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(infront)).isEmpty(),
                "not as a block and not as a fluid state");
        helper.assertValueEqual(answer, ItemInteractionResult.CONSUME,
                "and a container the bay refuses is answered rather than passed on");
        helper.assertValueEqual(bay.millibuckets(), 0, "nothing entered the bay");
        helper.assertTrue(player.getMainHandItem().is(Items.LAVA_BUCKET), "the player keeps the lava");
        bay.setStoreFilter(ItemStack.EMPTY);

        // Shift keeps its item meaning: there is no larger amount a container click could mean.
        helper.assertValueEqual(click(helper, player, LONE_BAY, player.getMainHandItem(), true),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
                "a sneaking click is passed on, so a block can still be placed against a tank's face");
        helper.assertValueEqual(bay.millibuckets(), 0, "and moves nothing");

        // No automation reaches the contents by right-clicking, in either direction.
        bay.fill(new FluidStack(Fluids.LAVA, BUCKET), false);
        FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
        deployer.getInventory().clearContent();
        helper.assertValueEqual(click(helper, deployer, LONE_BAY, new ItemStack(Items.WATER_BUCKET), false),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "a deployer may not fill a tank wall");
        helper.assertValueEqual(click(helper, deployer, LONE_BAY, new ItemStack(Items.BUCKET), false),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "nor empty one bucket at a time");
        helper.assertValueEqual(bay.millibuckets(), BUCKET, "and nothing moved either way");
        deployer.getInventory().clearContent();
        deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * What a fluid bay says through Engineer's Goggles, row by row ({@link FluidBayBlockEntity#ownGoggleRows()}): the
     * whole readout of this block, in every state it branches on.
     * <p>
     * It asserts the <b>rows</b> and not a screenshot, because the rows are where the decisions are: which fluid
     * belongs here, whether the slot named one at all, what is in it, and — while there is anything to lose — that
     * breaking it loses the contents. {@code addToGoggleTooltip} itself cannot be tested on a server at all
     * ({@code LangBuilder#forGoggles} measures the client's font), which is exactly why that method is two lines over
     * this list.
     * <p>
     * Three of the assertions are about a number rather than a key:
     * <ul>
     * <li>the contents row is shown in <b>buckets</b>, so 37 250 mB reads "37.25" of "64";</li>
     * <li>below a hundredth of a bucket it switches to millibuckets, because a goggle line carries two fraction
     * digits and a bay holding 7 mB would otherwise say "0 / 64 buckets" — a bay that holds something telling a player
     * it holds nothing. A Create pipe network moves as little as 1 mB per tick, so every bay being filled passes
     * through that row;</li>
     * <li>the capacity row is the bay's own, in buckets.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidBayGoggleRowsSayWhatItHolds(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);

        // Empty, unfiltered, and no warehouse anywhere: the state a bay is in the moment it is placed. "Not part of an
        // aisle" is a defect for every other member of a warehouse and a plain fact for a tank.
        assertGoggleRows(helper, bay, "an empty, unfiltered bay with no warehouse",
                WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE, WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST,
                WareworksLang.GOGGLES_EMPTY, WareworksLang.GOGGLES_FLUID_BAY_CAPACITY,
                WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        helper.assertValueEqual(goggleArgs(helper, bay, 3).getFirst(), "64", "the capacity row says 64 buckets");

        // Filled, still unfiltered: it says which fluid it learned, how much of it there is, and that breaking it
        // would lose exactly that.
        bay.fill(new FluidStack(Fluids.LAVA, LOST_FILL), false);
        assertGoggleRows(helper, bay, "an unfiltered bay that learned lava",
                WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE, WareworksLang.GOGGLES_FLUID_BAY_LEARNED,
                WareworksLang.GOGGLES_FLUID_BAY_CONTENTS, WareworksLang.GOGGLES_FLUID_BAY_BREAK_LOSES,
                WareworksLang.GOGGLES_FLUID_BAY_CAPACITY, WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        List<String> contents = goggleArgs(helper, bay, 2);
        helper.assertValueEqual(contents.get(1), "37.25", "the contents row shows buckets, not millibuckets");
        helper.assertValueEqual(contents.get(2), "64", "out of the bay's own capacity");

        // Below a hundredth of a bucket the row states millibuckets instead, or a bay that holds something would read
        // as empty.
        helper.assertValueEqual(bay.drain(LOST_FILL - 7, false).getAmount(), LOST_FILL - 7, "drained down to 7 mB");
        assertGoggleRows(helper, bay, "a bay holding 7 mB", WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE,
                WareworksLang.GOGGLES_FLUID_BAY_LEARNED, WareworksLang.GOGGLES_FLUID_BAY_CONTENTS_SMALL,
                WareworksLang.GOGGLES_FLUID_BAY_BREAK_LOSES, WareworksLang.GOGGLES_FLUID_BAY_CAPACITY,
                WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        helper.assertValueEqual(goggleArgs(helper, bay, 2).get(1), "7", "and states the millibuckets themselves");

        // A filter that names a FLUID replaces the learned row, and keeps saying so after the bay has drained.
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "a lava bucket goes in the slot");
        assertGoggleRows(helper, bay, "a bay dedicated to lava", WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE,
                WareworksLang.GOGGLES_FLUID_BAY_FILTER, WareworksLang.GOGGLES_FLUID_BAY_CONTENTS_SMALL,
                WareworksLang.GOGGLES_FLUID_BAY_BREAK_LOSES, WareworksLang.GOGGLES_FLUID_BAY_CAPACITY,
                WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        helper.assertValueEqual(bay.drain(Integer.MAX_VALUE, false).getAmount(), 7, "emptied again");
        assertGoggleRows(helper, bay, "an empty bay that is still dedicated to lava",
                WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE, WareworksLang.GOGGLES_FLUID_BAY_FILTER,
                WareworksLang.GOGGLES_EMPTY, WareworksLang.GOGGLES_FLUID_BAY_CAPACITY,
                WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);

        // A slot that names NO fluid gets the gold line AND the row for what the bay really does, because such a bay
        // is unfiltered and a player who thought they had dedicated it needs both halves.
        helper.assertTrue(bay.setStoreFilter(listFilter(new ItemStack(Items.LAVA_BUCKET))),
                "a Create list filter goes in the slot");
        assertGoggleRows(helper, bay, "a bay whose filter names no fluid",
                WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE, WareworksLang.GOGGLES_FLUID_BAY_FILTER_NO_FLUID,
                WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST, WareworksLang.GOGGLES_EMPTY,
                WareworksLang.GOGGLES_FLUID_BAY_CAPACITY, WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        bay.setStoreFilter(ItemStack.EMPTY);

        // A storage priority a player set, and the column rule's gold warning, each adding exactly one row.
        helper.assertTrue(bay.setStorePriority(7), "a priority goes on the board");
        assertGoggleRows(helper, bay, "a bay with a priority", WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE,
                WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST, WareworksLang.GOGGLES_STORAGE_PRIORITY,
                WareworksLang.GOGGLES_EMPTY, WareworksLang.GOGGLES_FLUID_BAY_CAPACITY,
                WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
        helper.assertValueEqual(goggleArgs(helper, bay, 2).getFirst(), "7", "which names the priority");
        bay.setStorePriority(StorageFilterBehaviour.MIN_PRIORITY);

        helper.setBlock(LONE_BAY.above(), WareworksBlocks.FLUID_BAY_BRASS.getDefaultState()
                .setValue(FluidBayBlock.FACING, Direction.NORTH));
        helper.startSequence()
                .thenExecuteAfter(REPAIR_TICKS, () -> {
                    assertOverloaded(helper, LONE_BAY, true, "the copper bay under a brass one");
                    assertGoggleRows(helper, bayAt(helper, LONE_BAY), "an overloaded bay",
                            WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE,
                            WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST,
                            WareworksLang.GOGGLES_FLUID_BAY_OVERLOADED, WareworksLang.GOGGLES_EMPTY,
                            WareworksLang.GOGGLES_FLUID_BAY_CAPACITY, WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW);
                    removeBay(helper, LONE_BAY.above());
                    removeBay(helper, LONE_BAY);
                })
                .thenSucceed();
    }

    /**
     * <b>Breaking a full fluid bay loses the fluid, and says so in the log</b> (D7) — the one deliberate loss this mod
     * allows, pinned in both halves.
     * <p>
     * The loss is the <b>exact</b> amount, measured by the joint census rather than by reading the bay: the fluid half
     * drops by precisely what was in it, the item half gains precisely one plain bay item, and nothing is placed in
     * the world — no source block, no filled container, nothing that could have created something from nothing. Every
     * alternative to the loss was weighed and is worse (§3.9), and the block this bay is measured against behaves the
     * same way: Create's own Fluid Tank drops nothing when it is broken.
     * <p>
     * <b>The log line is asserted through a real log4j appender</b> ({@link LogCapture}), and that is the point of the
     * test rather than a detail of it: that one {@code WARN} is the only record a server owner ever gets of this loss,
     * so a later change that turned it into a silent one — a refactor, a "noisy log" clean-up — has to fail here. It
     * must name all three things somebody reading a log a week later needs: the fluid, the millibuckets and the
     * position.
     * <p>
     * And the <b>warning before it</b>: the first punch at a bay that holds something says what breaking it costs, in
     * one action-bar line that names the fluid — the last of the three places that say so, and the only one that
     * reaches a player who wears no goggles and read no tooltip. An <b>empty</b> bay says nothing, because there is
     * nothing to warn about.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void breakingAFullFluidBayLosesItsFluidAndLogsIt(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(LONE_BAY);
        FluidKey lava = FluidKey.of(Fluids.LAVA);

        // The first punch at an EMPTY bay warns about nothing.
        NamingClick.Teller player = NamingClick.player(helper);
        level.getBlockState(absolute).attack(level, absolute, player);
        NamingClick.assertTold(helper, player, "a punch at an empty fluid bay");

        bay.fill(new FluidStack(Fluids.LAVA, LOST_FILL), false);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of(lava, LOST_FILL);
        FluidCensus.assertConserved(helper, items, fluid, "before the bay is broken");

        // ... and the first punch at a bay that holds something says what breaking it would cost.
        NamingClick.Teller warned = NamingClick.player(helper);
        level.getBlockState(absolute).attack(level, absolute, warned);
        NamingClick.assertTold(helper, warned, "a punch at a full fluid bay", WareworksLang.FLUID_BAY_BREAK_LOSES);
        helper.assertValueEqual(warned.argsOf(0).size(), 1, "the warning names one thing");
        helper.assertValueEqual(bay.millibuckets(), LOST_FILL, "and the punch itself changes nothing");

        LogCapture log = LogCapture.ofWarnings();
        try {
            // Level#destroyBlock(pos, drop) is the shape of a survival break, an explosion, a Create saw or drill and
            // /setblock ... destroy: it reaches IBE.onRemove and therefore FluidBayBlockEntity#destroy.
            helper.assertTrue(level.destroyBlock(absolute, true), "the bay is broken");
            helper.assertTrue(level.getBlockEntity(absolute) == null, "the bay is gone");
            helper.assertValueEqual(bay.millibuckets(), 0,
                    "and its contents were cleared before anything else could see them");
            helper.assertTrue(bay.storedFluid().isEmpty(), "so a second pass over it finds nothing to hand out twice");

            // The fluid is gone, and nothing was conjured in its place.
            ItemCensus.change(items, ItemKey.of(WareworksBlocks.FLUID_BAY_COPPER.asItem()), 1);
            FluidCensus.change(fluid, lava, -LOST_FILL);
            FluidCensus.assertConserved(helper, items, fluid, "after a full fluid bay was broken");
            List<ItemEntity> dropped = level.getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS),
                    entity -> true);
            helper.assertValueEqual(dropped.size(), 1, "the only item entity is the empty bay itself");
            ItemStack bayItem = dropped.getFirst().getItem();
            helper.assertTrue(ItemStack.matches(bayItem, new ItemStack(WareworksBlocks.FLUID_BAY_COPPER.asItem())),
                    "and it is a plain one with nothing copied into it: " + bayItem + " " + bayItem.getComponents());
            helper.assertTrue(level.getFluidState(absolute).isEmpty(),
                    "no source block was placed where the bay stood, which is the alternative that was refused");

            helper.startSequence()
                    // Log4j may hand an appender its events on another thread, so the assertion waits a tick rather
                    // than racing it. The capture is detached BEFORE anything is asserted, so a failure here cannot
                    // leave an appender recording for the rest of the run.
                    .thenExecuteAfter(LOG_TICKS, () -> {
                        List<String> warnings = log.closeAndTake();
                        String line = LogCapture.firstContaining(warnings, "fluid bay", String.valueOf(absolute),
                                String.valueOf(LOST_FILL), lava.toString());
                        helper.assertTrue(line != null, "breaking a full fluid bay must log the loss with the fluid, "
                                + "the amount and the position, but the warnings were " + warnings);
                        Wareworks.LOGGER.debug("The fluid bay loss was logged as: {}", line);
                    })
                    .thenSucceed();
        } catch (RuntimeException | AssertionError e) {
            log.close();
            throw e;
        }
    }

    /**
     * A <b>creative</b> break loses the fluid and drops nothing at all, which is right for the same reason the rack
     * bay's creative break drops no bay item: the contents were never the creative player's to conjure away, and the
     * block itself is free to a creative player anyway.
     * <p>
     * It is driven the way {@code ServerPlayerGameMode#destroyBlock} drives it — {@code playerWillDestroy}, then
     * {@code onDestroyedByPlayer} with {@code willHarvest = false}, then {@code Block#destroy}, and <b>no</b>
     * {@code playerDestroy}, because the creative branch returns before it. The loss is still logged, which is what
     * makes the log line a record of the <i>event</i> rather than of a survival player's mistake.
     * <p>
     * It also pins the one asymmetry of the warning on the first punch: a creative break never shows it, because
     * {@code handleBlockBreakAction} returns at {@code destroyAndAck} before {@code attack} is called for a creative
     * player. That is a reason for the goggle line and the item description to exist, and it is stated here so that
     * nobody later reads the missing message as a bug.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void breakingAFluidBayInCreative(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_BRASS.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        bay.fill(new FluidStack(Fluids.WATER, BRASS_CAPACITY), false);
        FluidKey water = FluidKey.of(Fluids.WATER);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of(water, BRASS_CAPACITY);
        FluidCensus.assertConserved(helper, items, fluid, "before the bay is broken in creative");

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(LONE_BAY);
        Player creative = NamingClick.player(helper);
        LogCapture log = LogCapture.ofWarnings();
        try {
            BlockState state = level.getBlockState(absolute).getBlock().playerWillDestroy(level, absolute,
                    level.getBlockState(absolute), creative);
            helper.assertTrue(
                    state.onDestroyedByPlayer(level, absolute, creative, false, level.getFluidState(absolute)),
                    "the creative break removes the bay");
            state.getBlock().destroy(level, absolute, state);

            helper.assertValueEqual(bay.millibuckets(), 0, "the fluid is gone");
            helper.assertTrue(level.getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS),
                    entity -> true).isEmpty(), "and nothing was dropped at all: no bay item, no container");
            FluidCensus.change(fluid, water, -BRASS_CAPACITY);
            FluidCensus.assertConserved(helper, items, fluid, "after the bay was broken in creative");

            helper.startSequence()
                    .thenExecuteAfter(LOG_TICKS, () -> {
                        List<String> warnings = log.closeAndTake();
                        helper.assertTrue(LogCapture.firstContaining(warnings, "fluid bay",
                                String.valueOf(BRASS_CAPACITY), water.toString()) != null,
                                "a creative break logs the loss as well, but the warnings were " + warnings);
                    })
                    .thenSucceed();
        } catch (RuntimeException | AssertionError e) {
            log.close();
            throw e;
        }
    }

    /**
     * A <b>sneaking wrench</b> click takes a bay away, and the line that names the fluid goes out on that route too
     * (M30 review fix, issue #21).
     * <p>
     * This is the one route that skipped every in-world warning. {@code IWrenchable#onSneakWrenched} posts the break
     * event, puts the block item into the player's inventory and destroys the block — it never calls {@code attack},
     * so a player wearing no goggles who had read no tooltip lost a full bay in silence on Create's own relocation
     * gesture. The line arrives <i>with</i> the loss rather than before it, which is as early as one click allows, and
     * that is asserted here rather than only argued: the message, the fluid gone, the block in the player's inventory
     * and nothing dropped on the floor.
     * <p>
     * It is driven through the <b>wrench item</b> and not through the block's hook, because the hook is not what a
     * player does: {@code WrenchItem#useOn} is what decides between turning the bay and taking it away, by the shift
     * key alone. The test player is <b>creative</b> ({@code NamingClick.Teller}), so Create puts no block item
     * anywhere — which is right for this test: where the empty bay ends up is Create's business, and the loss and the
     * line are this block's.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void wrenchingAFullFluidBaySaysWhatItCosts(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        bay.fill(new FluidStack(Fluids.LAVA, LOST_FILL), false);
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(LONE_BAY);
        FluidKey lava = FluidKey.of(Fluids.LAVA);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of(lava, LOST_FILL);
        FluidCensus.assertConserved(helper, items, fluid, "before the bay is wrenched away");

        NamingClick.Teller player = NamingClick.player(helper);
        player.getInventory().clearContent();
        player.setShiftKeyDown(true);
        ItemStack wrench = AllItems.WRENCH.asStack();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0.0, 0.0, 0.5), Direction.SOUTH,
                absolute, false);
        wrench.useOn(new UseOnContext(level, player, InteractionHand.MAIN_HAND, wrench, hit));

        NamingClick.assertTold(helper, player, "a sneaking wrench at a full fluid bay",
                WareworksLang.FLUID_BAY_BREAK_LOSES);
        helper.assertValueEqual(player.argsOf(0).size(), 1, "the line names one thing, the fluid");
        helper.assertTrue(level.getBlockState(absolute).isAir(), "the bay is gone");
        helper.assertValueEqual(bay.millibuckets(), 0, "and its contents were cleared, exactly as on a break");
        helper.assertTrue(bay.storedFluid().isEmpty(), "so a second pass over it finds nothing to hand out twice");
        helper.assertTrue(level.getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS),
                entity -> true).isEmpty(), "and nothing was dropped on the floor");
        helper.assertTrue(level.getFluidState(absolute).isEmpty(), "no source block was left where the bay stood");
        FluidCensus.change(fluid, lava, -LOST_FILL);
        FluidCensus.assertConserved(helper, items, fluid, "after a full fluid bay was wrenched away");

        // An EMPTY bay says nothing, because there is nothing to lose - the punch's own rule.
        placeBay(helper, LONE_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState(), Direction.NORTH);
        NamingClick.Teller quiet = NamingClick.player(helper);
        quiet.getInventory().clearContent();
        quiet.setShiftKeyDown(true);
        ItemStack second = AllItems.WRENCH.asStack();
        second.useOn(new UseOnContext(level, quiet, InteractionHand.MAIN_HAND, second, hit));
        NamingClick.assertTold(helper, quiet, "a sneaking wrench at an empty fluid bay");
        helper.assertTrue(level.getBlockState(absolute).isAir(), "and it is taken away all the same");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * A fluid bay at a test-relative position, facing {@code facing} (i.e. into the rack depth). A bay that already
     * stands there is <b>emptied</b> first, the way {@code /setblock} does.
     */
    private static void placeBay(GameTestHelper helper, BlockPos pos, BlockState bay, Direction facing) {
        removeBay(helper, pos);
        helper.setBlock(pos, bay.setValue(FluidBayBlock.FACING, facing));
    }

    /** Empties whatever bay stands at {@code pos} and takes it away, leaving nothing behind. */
    private static void removeBay(GameTestHelper helper, BlockPos pos) {
        Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        helper.setBlock(pos, Blocks.AIR);
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, BlockPos pos) {
        FluidBayBlockEntity be = WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing fluid bay block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    /** The bay's fluid handler as a pipe sees it: through {@code Capabilities.FluidHandler.BLOCK} on {@code side}. */
    private static IFluidHandler fluidHandlerAt(GameTestHelper helper, BlockPos pos, @Nullable Direction side) {
        return helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(pos), side);
    }

    /** What a funnel, a chute or a hopper would find at the bay: deliberately nothing at all (D3). */
    @Nullable
    private static IItemHandler itemHandlerAt(GameTestHelper helper, BlockPos pos, @Nullable Direction side) {
        return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), side);
    }

    /** A fresh bay loaded from {@code tag} and put into the world, standing in for a reload. */
    private static FluidBayBlockEntity loadCopy(GameTestHelper helper, FluidBayBlockEntity live, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof FluidBayBlockEntity copy)) {
            helper.fail("a saved fluid bay must load again as one", live.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        helper.getLevel().setBlockEntity(copy);
        return copy;
    }

    /** Puts {@code tag} into the world as a loaded bay, the way a world load would. */
    private static FluidBayBlockEntity readBack(GameTestHelper helper, FluidBayBlockEntity live, CompoundTag tag) {
        CompoundTag full = tag.copy();
        full.putString("id", "wareworks:fluid_bay");
        full.putInt("x", live.getBlockPos().getX());
        full.putInt("y", live.getBlockPos().getY());
        full.putInt("z", live.getBlockPos().getZ());
        return loadCopy(helper, live, full);
    }

    /** A detached block entity, standing in for the client's copy of this bay. */
    private static FluidBayBlockEntity detachedCopy(GameTestHelper helper, FluidBayBlockEntity bay) {
        FluidBayBlockEntity copy = WareworksBlockEntityTypes.FLUID_BAY.create(bay.getBlockPos(), bay.getBlockState());
        if (copy == null) {
            helper.fail("could not create a detached fluid bay block entity", bay.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return copy;
    }

    /** A Create list filter (whitelist) holding {@code items} — a filter that names no fluid at all. */
    private static ItemStack listFilter(ItemStack... items) {
        ItemStack filter = AllItems.FILTER.asStack();
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(items)));
        return filter;
    }

    /**
     * Places {@code bay} at {@code pos} the way a player does: through {@code getStateForPlacement}, so the column
     * rule really runs and the bay arrives with the flags a placement gives it.
     */
    private static void placeByHand(GameTestHelper helper, BlockPos pos, BlockState bay) {
        helper.setBlock(pos, assertPlacementAllowed(helper, pos, bay, "a legal placement at " + pos));
    }

    /**
     * Places {@code bay} by clicking the <b>side</b> of the block at {@code neighbour} towards {@code towards} — the
     * gesture that grows a wall, and the only one that reaches {@code BayColumn.placementFacing}'s
     * copy-the-clicked-bay's-facing rule.
     */
    private static void placeBySideClick(GameTestHelper helper, BlockPos neighbour, Direction towards,
                                         BlockState bay) {
        ServerLevel level = helper.getLevel();
        BlockPos clicked = helper.absolutePos(neighbour);
        BlockPos target = neighbour.relative(towards);
        helper.assertTrue(level.getBlockState(helper.absolutePos(target)).isAir(),
                "a side click needs a free position at " + target);
        Vec3 hit = Vec3.atCenterOf(clicked).add(towards.getStepX() * 0.5, 0, towards.getStepZ() * 0.5);
        BlockPlaceContext context = new BlockPlaceContext(level, NamingClick.player(helper), InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, new BlockHitResult(hit, towards, clicked, false));
        helper.assertValueEqual(context.getClickedPos(), helper.absolutePos(target),
                "a click on the side of " + neighbour + " places at " + target);
        BlockState placed = bay.getBlock().getStateForPlacement(context);
        if (placed == null) {
            helper.fail("the column must allow a bay beside " + neighbour, target);
            return;
        }
        helper.setBlock(target, placed);
    }

    /** Fails unless the column rule refuses {@code bay} at {@code pos}, with the one action-bar line and no block. */
    private static void assertPlacementRefused(GameTestHelper helper, BlockPos pos, BlockState bay, String what) {
        NamingClick.Teller player = NamingClick.player(helper);
        helper.assertTrue(placementState(helper, player, pos, bay) == null, "the column must refuse " + what);
        NamingClick.assertTold(helper, player, what, WareworksLang.FLUID_BAY_COLUMN_REFUSED);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(pos)).isAir(),
                "and nothing may be placed: " + what);
    }

    /** Fails unless the column rule allows {@code bay} at {@code pos} silently; answers the state it would get. */
    private static BlockState assertPlacementAllowed(GameTestHelper helper, BlockPos pos, BlockState bay,
                                                     String what) {
        NamingClick.Teller player = NamingClick.player(helper);
        BlockState placed = placementState(helper, player, pos, bay);
        if (placed == null) {
            helper.fail("the column must allow " + what, pos);
            throw new IllegalStateException("unreachable");
        }
        NamingClick.assertTold(helper, player, what); // an allowed placement says nothing
        return placed;
    }

    /**
     * The state {@code bay} would be placed in at the free position {@code pos}, or {@code null} when the block refuses
     * the placement — the very call {@code BlockItem.getPlacementState} makes, which turns a {@code null} into the
     * {@code FAIL} that places no block and consumes no item.
     * <p>
     * The hit result points <b>down</b> at the position from above, so the clicked position is that position and the
     * clicked face is {@code UP} — which {@code BayColumn.placementFacing} answers with the player's own look
     * direction, as it must for a click that is not on another bay's side.
     */
    @Nullable
    private static BlockState placementState(GameTestHelper helper, Player player, BlockPos pos, BlockState bay) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        helper.assertTrue(level.getBlockState(absolute).isAir(), "a placement needs a free position at " + pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, -0.5, 0), Direction.UP, absolute,
                false);
        BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, ItemStack.EMPTY,
                hit);
        helper.assertValueEqual(context.getClickedPos(), absolute, "the placement context points at " + pos);
        return bay.getBlock().getStateForPlacement(context);
    }

    /**
     * A right-click on the bay at {@code pos}, sneaking or not, answering what the <b>block</b> did with it — which is
     * what {@code GameTestHelper#useBlock} throws away.
     * <p>
     * The hit point is off centre on the <b>aisle</b> face, which is where a player has to click for the block to see
     * the click at all: Create cancels a plain right-click that hits the value box's own 4 px sphere before the block
     * state is asked. That handler is not involved here either way, so the offset is here to describe the real
     * gesture rather than to make it work.
     */
    private static ItemInteractionResult click(GameTestHelper helper, Player player, BlockPos pos, ItemStack held,
                                               boolean sneaking) {
        return click(helper, player, pos, held, sneaking, InteractionHand.MAIN_HAND);
    }

    /**
     * {@link #click(GameTestHelper, Player, BlockPos, ItemStack, boolean)} with the hand named, because vanilla offers
     * a block the <b>off</b> hand too: {@code Minecraft#startUseItem} walks both hands and only the empty-handed
     * interaction is main hand only, so a gesture that answered one hand would hand the other hand's bucket of lava
     * to {@code BucketItem#use}.
     */
    private static ItemInteractionResult click(GameTestHelper helper, Player player, BlockPos pos, ItemStack held,
                                               boolean sneaking, InteractionHand hand) {
        player.setShiftKeyDown(sneaking);
        player.setItemInHand(hand, held);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0.3, -0.3, 0.5), Direction.SOUTH,
                absolute, false);
        return helper.getLevel().getBlockState(absolute).useItemOn(player.getItemInHand(hand), helper.getLevel(),
                player, hand, hit);
    }

    /**
     * Stands {@code player} one block in front of the bay's <b>aisle</b> face, at eye height, looking straight at it —
     * and <b>asserts that the aim really lands there</b>.
     * <p>
     * It is needed by exactly one assertion, and that assertion is the reason for the care: a bucket's own use is a
     * raycast from the player's eyes ({@code BucketItem#use}), so a test that let the gesture pass a lava bucket's
     * click on would only see the lava appear if the player was aiming at the bay. A mock player aiming at nothing
     * would make that assertion pass for the wrong reason for ever.
     * <p>
     * The bay's {@code FACING} points into the rack depth, so a bay facing north has its aisle face to the south: the
     * player stands {@value #AIM_DISTANCE} blocks south of the block's centre and looks north (yaw 180).
     * <p>
     * Two details are the kind that make such a setup silently meaningless. A {@code LivingEntity}'s own view yaw is
     * its <b>head</b> rotation and not the {@code yRot} that {@code moveTo} sets, so the head and the body are both
     * turned; and the ray asserted here is the one an <b>item</b> traces
     * ({@code Item#getPlayerPOVHitResult}, which uses {@code getXRot()}/{@code getYRot()}), not
     * {@code Entity#pick}'s, which uses the head's.
     */
    private static void aimAtAisleFace(GameTestHelper helper, Player player, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 centre = Vec3.atCenterOf(absolute);
        player.moveTo(centre.x, centre.y - player.getEyeHeight(), centre.z + AIM_DISTANCE, 180f, 0f);
        player.setYHeadRot(180f);
        player.setYBodyRot(180f);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.calculateViewVector(player.getXRot(), player.getYRot())
                .scale(player.blockInteractionRange()));
        BlockHitResult aim = helper.getLevel()
                .clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        helper.assertTrue(aim.getType() == HitResult.Type.BLOCK && aim.getBlockPos().equals(absolute)
                && aim.getDirection() == Direction.SOUTH,
                "the player at " + player.position() + " must be aiming at the aisle face of the bay at " + pos
                        + " (" + absolute + "), but the ray a bucket would trace hit " + aim.getType() + " at "
                        + aim.getBlockPos() + " on " + aim.getDirection() + " ("
                        + helper.getLevel().getBlockState(aim.getBlockPos()) + ")");
    }

    /**
     * Fails unless a right-click with {@code held} is passed straight on, in both postures, and moves nothing: the
     * item keeps whatever meaning it had before this block existed.
     */
    private static void assertPassesThrough(GameTestHelper helper, FluidBayBlockEntity bay, Player player,
                                            ItemStack held, String what) {
        int before = bay.millibuckets();
        for (boolean sneaking : new boolean[] { false, true })
            helper.assertValueEqual(click(helper, player, LONE_BAY, held.copy(), sneaking),
                    ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "a fluid bay passes on " + what);
        helper.assertValueEqual(bay.millibuckets(), before, "and moves no fluid for it: " + what);
        player.setShiftKeyDown(false);
    }

    /**
     * Fails unless the bay and the player's own containers together still hold every millibucket this test started
     * with. Neither census can see a player's inventory, and a hand gesture is exactly a transfer between the two, so
     * this is the conservation assertion for it.
     */
    private static void assertNoFluidLost(GameTestHelper helper, FluidBayBlockEntity bay, Player player,
                                          int expected, String when) {
        // The main-hand stack is one of the inventory's own slots (the selected one); the off hand is a compartment
        // of its own and has to be walked as well, because a container in it fills and empties a bay too.
        long total = bay.millibuckets();
        for (ItemStack stack : player.getInventory().items)
            total += FluidContainers.contentsOf(stack)
                    .map(contents -> (long) contents.millibuckets() * stack.getCount()).orElse(0L);
        for (ItemStack stack : player.getInventory().offhand)
            total += FluidContainers.contentsOf(stack)
                    .map(contents -> (long) contents.millibuckets() * stack.getCount()).orElse(0L);
        helper.assertValueEqual(total, (long) expected,
                "every millibucket is still in the bay or in the player's own containers " + when);
    }

    /** Items of {@code item} anywhere in {@code player}'s main inventory. */
    private static int inventoryCount(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(item))
                total += stack.getCount();
        return total;
    }

    /**
     * Fails unless the bay's own goggle rows are exactly these lang keys, in this order
     * ({@link FluidBayBlockEntity#ownGoggleRows()}).
     * <p>
     * The header and the address block are not in it: those are shared with every other warehouse member and are
     * drawn by {@code addToGoggleTooltip}, which cannot run on a server at all.
     */
    private static void assertGoggleRows(GameTestHelper helper, FluidBayBlockEntity bay, String what,
                                         String... relativeKeys) {
        List<String> expected = new ArrayList<>(relativeKeys.length);
        for (String key : relativeKeys)
            expected.add(WareworksLang.key(key));
        helper.assertValueEqual(goggleKeys(bay), List.copyOf(expected), "the goggle rows of " + what);
    }

    /** The lang keys of the bay's own goggle rows, in order. */
    private static List<String> goggleKeys(FluidBayBlockEntity bay) {
        List<String> keys = new ArrayList<>();
        for (LangBuilder row : bay.ownGoggleRows()) {
            Component component = row.component();
            keys.add(component.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                    : "<literal> " + component.getString());
        }
        return keys;
    }

    /** The arguments of the bay's goggle row at {@code index}, as plain strings. */
    private static List<String> goggleArgs(GameTestHelper helper, FluidBayBlockEntity bay, int index) {
        List<LangBuilder> rows = bay.ownGoggleRows();
        if (index >= rows.size() || !(rows.get(index).component().getContents() instanceof TranslatableContents row)) {
            helper.fail("there is no translated goggle row " + index + " to read arguments off", bay.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        List<String> args = new ArrayList<>();
        for (Object arg : row.getArgs())
            args.add(arg instanceof Component component ? component.getString() : String.valueOf(arg));
        return args;
    }

    /**
     * Fails unless the bay at {@code pos} carries exactly {@code expected} as its column-rule flag — in the block
     * state, in its block entity and in the answer the job planner reads.
     */
    private static void assertOverloaded(GameTestHelper helper, BlockPos pos, boolean expected, String what) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(pos));
        helper.assertTrue(state.getBlock() instanceof FluidBayBlock, "a fluid bay must stand at " + pos + ": " + what);
        helper.assertValueEqual(state.getValue(TieredBay.OVERLOADED), expected, "block state: " + what);
        FluidBayBlockEntity bay = bayAt(helper, pos);
        helper.assertValueEqual(bay.overloaded(), expected, "block entity: " + what);
        helper.assertValueEqual(bay.acceptsStoring(), !expected, "accepts storing: " + what);
    }

}
