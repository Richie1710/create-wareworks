package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.MAX_RACK_BAY_SYNC_BYTES;
import static dev.wareworks.gametest.WareworksGameTests.MAX_RESERVED_RACK_BAY_SYNC_BYTES;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.RackBayGestures;
import dev.wareworks.content.storage.RackBayHandler;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.inventory.CapacityMath;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20): a storage location that <b>is</b>
 * the block, holding one item type as a count rather than in slots.
 * <p>
 * What each test is here for:
 * <ul>
 * <li>{@code baystandsalone} — the standalone promise of the issue: with no controller, no crane and no rail anywhere,
 * a bay is filled and emptied by hand and reports its contents through its item capability. It also pins the
 * <b>tickerless</b> rule, because a basement of a thousand bays must cost nothing per tick;</li>
 * <li>{@code bayholdsonetypeuntilitempties} — one item type, learned from what lands and forgotten on empty;</li>
 * <li>{@code baycapacityfollowsthetierandtheitem} — 64 / 256 / 1 024 stacks, and the item says what a stack is, and
 * {@code baycapacityfromconfig} the same against a configuration that is <b>not</b> the shipped one, plus a capacity
 * lowered under a bay that is already fuller than it;</li>
 * <li>{@code baystorefilterdecideswhatmayenter} — a filter narrows what may enter; what really lands fixes the
 * type;</li>
 * <li>{@code baypersistenceroundtrip} — 65 536 items, a filter and a priority through a save and a reload. The one
 * test that can catch the {@code ItemStack.save} trap, and it only fails <b>after</b> the reload;</li>
 * <li>{@code baysurvivesanysavedata} — a world written before the bay existed, and save data nobody should trust;</li>
 * <li>{@code baybreakresets}, {@code baybreakincreative}, {@code palletspawnrefusedfallsbacktoitementities} and
 * {@code bayrefilledfromapalletbyhand} — the break path (M28 step 7, ADR-046): breaking a bay leaves one pallet
 * carrying the whole load and nothing else, in survival and in creative; a refused pallet spawn still loses nothing;
 * and a player puts the goods back the only way there is, a stack at a time. {@code baysetblockvoidsitlikeavanillachest}
 * is the other removal path, and it is deliberate vanilla parity;</li>
 * <li>{@code bayisastoragelocation} — the claim the whole design rests on: a bay joins an aisle, is indexed, and the
 * crane stores into it and retrieves out of it with no change to the controller at all;</li>
 * <li>{@code bayfillspastonestack} — the end-to-end form of the capacity-estimate regression: a crane fills one bay
 * past the one count no snapshot can decide, three trips in a row;</li>
 * <li>{@code bayonetypeonly}, {@code baytwojobsonetype} and {@code baycoldcacheafterreload} — the one-type rule where
 * it is enforced, under a running crane: a second type is refused at the planner's store gate until the bay empties,
 * two types never travel towards one bay at once, and a controller restored from a save honours the store rules of
 * bays it has not read back yet;</li>
 * <li>{@code baytakesitemsfrommachines}, {@code bayfedbyamachinereachesthestockindex} and
 * {@code baycapabilityfeeds} — what the item capability opens (M28 step 4): a storage location a belt and a vanilla
 * hopper fill and drain by themselves with no input station, a machine's fill reaching the controller in the same
 * tick, and a real Create funnel and a real turning belt finding the bay at all;</li>
 * <li>{@code baycolumnruleisrefusedatplacementbothways}, {@code baycolumnrulefollowscommandsandbreaks},
 * {@code baycommandedunderastrongeroneflagsitself} and
 * {@code anoverloadedbaystoresnothingbutstaysretrievable} — the column rule (M28 step 5): refused when a bay is
 * placed, reported once one stands, repaired after every change to the column, and what it means for a running
 * warehouse;</li>
 * <li>{@code bayhandgestures}, {@code baysyncisbounded} and {@code baygogglestatefollowsthewarehouse} — a player's own
 * hands (M28 step 6): one item for a plain click and one stack for Shift in both directions, everything the gesture
 * must <b>not</b> take, and the bounded client packet that carries the contents and the goggle state. The goggle
 * <b>lines</b> themselves belong to a client run: {@code LangBuilder#forGoggles} measures the client font.</li>
 * </ul>
 * The one-type <b>planning</b> gate is tested with the planner, and everything the <b>pallet</b> itself has to be true
 * about — it survives lava, it cannot be pocketed, a hopper drains it and a funnel does not — is
 * {@code gametest.PalletGameTests}.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RackBayGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 60;

    private static final int MACHINE_TIMEOUT_TICKS = 600;
    private static final int OVERLOADED_TIMEOUT_TICKS = 2400;
    /** {@link #bayOneTypeOnly} drives three crane trips with a "nothing fits" back-off between two of them. */
    private static final int ONE_TYPE_TIMEOUT_TICKS = 2400;
    /** Ticks a refused item is watched for, comfortably more than one {@code fullBackoffTicks}. */
    private static final int ONE_TYPE_SETTLE_TICKS = 100;
    /** Batch of {@link #bayCapacityFromConfig}, which overrides the server config ({@link ConfigOverrides}). */
    static final String CAPACITY_BATCH = "wareworksbaycapacity";
    /** Ticks a backed-up hopper is watched for, comfortably more than its own eight-tick cooldown. */
    private static final int BACKED_UP_TICKS = 40;
    /** Ticks a bay a command placed needs to read its own column-rule flag: one scheduled tick, plus slack. */
    private static final int REPAIR_TICKS = 3;

    /** A bay standing on its own, clear of the template walls. */
    private static final BlockPos LONE_BAY = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos SECOND_BAY = new BlockPos(1, BASE_Y, 3);

    /** The bay a real Create funnel sits on top of, one block off the floor so the funnel is not in the floor. */
    private static final BlockPos FUNNEL_BAY = new BlockPos(1, BASE_Y, 1);
    /** The bay a real belt run ends at, and the run itself: four segments along -X, so the bay is past its end. */
    private static final BlockPos BELT_BAY = new BlockPos(1, BASE_Y, 5);
    private static final BlockPos BELT_END = new BlockPos(2, BASE_Y, 5);
    private static final BlockPos BELT_START = new BlockPos(5, BASE_Y, 5);
    /** Beside the belt's start pulley, whose shaft axis is the one across the run. */
    private static final BlockPos BELT_MOTOR = new BlockPos(5, BASE_Y, 4);

    /** A bay one block off the floor, so a machine fits above it <b>and</b> below it. */
    private static final BlockPos MACHINE_BAY = new BlockPos(3, BASE_Y + 1, 3);
    private static final BlockPos ABOVE_MACHINE_BAY = MACHINE_BAY.above();
    private static final BlockPos BELOW_MACHINE_BAY = MACHINE_BAY.below();
    private static final BlockPos BESIDE_MACHINE_BAY = BELOW_MACHINE_BAY.east();

    /** Four column-rule columns, each on its own footprint so that one test can use all of them at once. */
    private static final BlockPos PLAIN_COLUMN = new BlockPos(1, BASE_Y, 1);
    private static final BlockPos MIXED_COLUMN = new BlockPos(3, BASE_Y, 1);
    /** A column a command broke, with the weak bay at the bottom: what refuses a bay placed on top of it. */
    private static final BlockPos BROKEN_FOOT = new BlockPos(5, BASE_Y, 1);
    /** The same broken column one level up, so a placement underneath it has somewhere to go. */
    private static final BlockPos BROKEN_HEAD = new BlockPos(1, BASE_Y + 1, 5);
    private static final BlockPos GAPPED_COLUMN = new BlockPos(5, BASE_Y, 5);

    private static final RackPosition BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    /** The rack position directly above {@link #BAY_RACK}: a second rack position of the same aisle. */
    private static final RackPosition BAY_ABOVE_RACK = new RackPosition(2, 1, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    /**
     * The bays of {@link #bayColdCacheAfterReload}: deliberately <b>more</b> than {@code maxSnapshotsPerTick}
     * (default 4), because the controller drains its restored locations at that rate while dispatch already runs, so
     * with four or fewer there would be no cold window to test at all — the same count and the same reason as
     * {@code StorageFilterGameTests#RELOAD_RACKS}, which this test is the rack bay's twin of. The <b>first</b> of them
     * is the one that is hand-filled rather than filtered, so the one-type rule and the store filter are both in that
     * window.
     */
    private static final List<RackPosition> RELOAD_RACKS = List.of(new RackPosition(1, 0, Side.LEFT),
            new RackPosition(2, 0, Side.LEFT), new RackPosition(3, 0, Side.LEFT), new RackPosition(4, 0, Side.LEFT),
            new RackPosition(5, 0, Side.LEFT), new RackPosition(1, 1, Side.LEFT));

    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey ENDER_PEARL = ItemKey.of(Items.ENDER_PEARL);

    /** How far cobblestone stacks, i.e. the stack size the issue's capacity table is written for. */
    private static final int STACK = 64;
    /** Items fed through the input of {@link #bayIsAStorageLocation}. */
    private static final int BATCH = 8;
    /** Items hand-loaded into the bay of {@link #bayIsAStorageLocation} before the aisle is built. */
    private static final int PRELOADED = 20;
    private static final int REQUEST_AMOUNT = 5;
    /** Load of the broken bay: enough to need several item entities, few enough not to flood the test world. */
    private static final int SPILLED_LOAD = 200;
    /** Items a belt hands a bay in {@link #bayTakesItemsFromMachines} and in {@link #bayCapabilityFeeds}. */
    private static final int BELT_FED = 8;
    /** Items a real Create funnel moves into a bay in {@link #bayCapabilityFeeds}, in one item entity. */
    private static final int FUNNEL_FED = 6;
    /** Items the crane carries into one bay in {@link #bayFillsPastOneStack}: three carry limits. */
    private static final int THREE_TRIPS = 3 * STACK;
    /** Height an item entity is dropped at, inside the collision shape of a floor funnel's mouth. */
    private static final double DROP_HEIGHT = 0.4;
    /** Capacities {@link #bayCapacityFromConfig} configures, none of them a shipped default. */
    private static final int CONFIGURED_WOOD_STACKS = 3;
    private static final int CONFIGURED_ANDESITE_STACKS = 7;
    private static final int CONFIGURED_BRASS_STACKS = 11;
    /** And the capacity it then lowers a full wooden bay to. */
    private static final int LOWERED_WOOD_STACKS = 1;
    /** Items in each slot of the hopper above the bay; a hopper moves one item every eight ticks. */
    private static final int HOPPER_FED = 5;
    /** Items in each bay of the column-rule column: enough to prove nothing is lost, few enough to spill cheaply. */
    private static final int COLUMN_LOAD = 12;
    /** The highest storage priority there is, so the gate can be shown to decide before it. */
    private static final int TOP_PRIORITY = 9;
    /** Blocks around a broken bay searched for its spilled items. */
    private static final double DROP_RADIUS = 3.0;
    /** A custom name long enough that nothing could carry it accidentally and unnoticed into an update tag. */
    private static final int LONG_NAME_LENGTH = 48;
    /** The widest address a rack position can have, for the worst-case update tag. */
    private static final String WIDEST_ADDRESS = "Z-999-999R";
    /** The longest name a player can give an aisle ({@code AisleName.MAX_LENGTH}), for the same reason. */
    private static final String LONGEST_AISLE_NAME = "ABCDEFGHIJKLMNOP";

    private RackBayGameTests() {
    }

    // --- standalone ------------------------------------------------------------------------------------------------

    /**
     * The issue's "a bay comes before the crane": a wooden bay is a better barrel, so with <b>no</b> controller, no
     * crane, no rail and no interface anywhere it must be fillable and emptiable by hand and readable from the outside.
     * <p>
     * It also pins the three things that make that affordable and honest:
     * <ul>
     * <li>{@code getTicker} is {@code null} on both sides — a wall of bays costs nothing per tick;</li>
     * <li>{@code getStackInSlot} answers the <b>true</b> count, far above a stack, which is what every item census of
     * this mod reads and what a clamped answer would turn into a phantom gain of 64 per transfer;</li>
     * <li>{@code extractItem} hands out at most one stack per call, as the item handler contract requires.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayStandsAlone(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        BlockState state = level.getBlockState(helper.absolutePos(LONE_BAY));

        BlockEntityTicker<?> ticker = state.getTicker(level, WareworksBlockEntityTypes.RACK_BAY.get());
        helper.assertTrue(ticker == null, "a rack bay must have no server ticker");
        helper.assertTrue(state.getBlock() instanceof RackBayBlock, "the block is a rack bay");
        helper.assertValueEqual(bay.tier(), BayTier.WOOD, "tier read off the block");

        IItemHandler handler = capabilityAt(helper, LONE_BAY);
        helper.assertTrue(handler == bay.handler(),
                "the capability is the bay's own handler instance, which is why a final field is all the invalidation "
                        + "this block needs");
        helper.assertValueEqual(handler.getSlots(), RackBayHandler.SLOTS, "a bay has one slot");
        helper.assertTrue(handler.getStackInSlot(0).isEmpty(), "a fresh bay is empty");
        helper.assertValueEqual(handler.getSlotLimit(0), bay.stacks() * 99,
                "an empty bay reports the most it could hold of any item");

        // Filled by hand, far past one stack and past what a chest would hold. The bay's own API is what a player's
        // hand will reach through (M28 step 6); the capability above is the same contents as a machine sees them.
        int capacity = (int) bay.capacityFor(COBBLESTONE);
        helper.assertValueEqual(capacity, bay.stacks() * STACK, "a wooden bay of cobblestone");
        ItemStack rest = bay.insert(COBBLESTONE.toStack(STACK), false);
        helper.assertTrue(rest.isEmpty(), "the first stack fits");
        helper.assertValueEqual(bay.insert(COBBLESTONE.toStack(STACK), true).getCount(), 0,
                "a simulated insert answers what a real one would take");
        helper.assertValueEqual(bay.storedCount(), STACK, "and changed nothing");
        for (int call = 0; call < 10; call++)
            handler.insertItem(0, COBBLESTONE.toStack(STACK), false);
        helper.assertValueEqual(bay.storedCount(), 11 * STACK, "eleven stacks in one block");
        helper.assertValueEqual(handler.getStackInSlot(0).getCount(), 11 * STACK,
                "and the handler says so, oversized stack and all");
        helper.assertValueEqual(handler.getSlotLimit(0), capacity, "the slot limit is the bay's capacity in items");

        // What a player with goggles reads off a bay nobody serves, and what the client drawing its front is told.
        // "Not part of an aisle" is this block's resting state and no fault of its own, and the contents still have to
        // cross the wire, because a standalone bay's front is the only thing a player has to read it by.
        helper.assertValueEqual(bay.aisleAssignment(), AisleAssignment.NONE,
                "a bay with no controller, crane or rail anywhere reports exactly that");
        HolderLookup.Provider registries = level.registryAccess();
        RackBayBlockEntity onTheClient = detachedCopy(helper, bay);
        onTheClient.handleUpdateTag(bay.getUpdateTag(registries), registries);
        helper.assertValueEqual(onTheClient.storedCount(), 11 * STACK,
                "and its contents reach the client with no warehouse involved in syncing them");
        helper.assertValueEqual(onTheClient.storedKey().orElse(null), COBBLESTONE, "item and all");
        helper.assertValueEqual(onTheClient.aisleAssignment(), AisleAssignment.NONE, "together with the goggle state");

        // Emptied by hand, one stack per call.
        ItemStack taken = bay.extract(1_000, false);
        helper.assertValueEqual(taken.getCount(), STACK, "one call takes one stack");
        helper.assertTrue(COBBLESTONE.matches(taken), "and it is what was stored");
        helper.assertValueEqual(bay.storedCount(), 10 * STACK, "the rest stays");
        int calls = 0;
        while (!bay.storedKey().isEmpty() && calls++ < 100)
            handler.extractItem(0, 1_000, false);
        helper.assertValueEqual(bay.storedCount(), 0, "a bay can be emptied by hand");
        helper.assertTrue(bay.storedKey().isEmpty(), "and forgets what it held");
        helper.succeed();
    }

    /**
     * One item type at a time, learned from the first type that lands in an unfiltered bay and <b>forgotten</b> once it
     * empties, so a player can put up a wall and let it fill (issue #20, "How a bay learns its type").
     * {@code isItemValid} says no to the second type as well, so a filtered funnel backs up instead of hammering a bay
     * that will never take its item.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayHoldsOneTypeUntilItEmpties(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IItemHandler handler = capabilityAt(helper, LONE_BAY);

        helper.assertTrue(handler.isItemValid(0, DIRT.toStack(1)), "an empty bay takes anything");
        handler.insertItem(0, COBBLESTONE.toStack(STACK), false);
        helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "the first type that landed");

        helper.assertFalse(handler.isItemValid(0, DIRT.toStack(1)), "a bay of cobblestone never takes dirt");
        ItemStack refused = handler.insertItem(0, DIRT.toStack(STACK), false);
        helper.assertValueEqual(refused.getCount(), STACK, "and really refuses it");
        helper.assertValueEqual(bay.storedCount(), STACK, "nothing was added");
        helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "nor replaced");

        // Partly drained is still committed; only an empty bay forgets.
        handler.extractItem(0, 32, false);
        helper.assertFalse(handler.isItemValid(0, DIRT.toStack(1)), "a half-empty bay is still a cobblestone bay");
        handler.extractItem(0, 32, false);
        helper.assertTrue(bay.storedKey().isEmpty(), "empty");
        helper.assertTrue(handler.insertItem(0, DIRT.toStack(STACK), false).isEmpty(),
                "and it takes the next type that comes");
        helper.assertValueEqual(bay.storedKey().orElse(null), DIRT, "which it has now learned");
        helper.succeed();
    }

    /**
     * The capacity ladder of issue #20 — 64 / 256 / 1 024 stacks — counted in <b>stacks</b>, so the item says what a
     * stack is: a bay of ender pearls holds a quarter of what the same bay holds of cobblestone. One number stands on
     * the block whatever is in it, which is the right way round, since bulk is what bays are for.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayCapacityFollowsTheTierAndTheItem(GameTestHelper helper) {
        assertCapacity(helper, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), BayTier.WOOD);
        assertCapacity(helper, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState(), BayTier.ANDESITE);
        assertCapacity(helper, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), BayTier.BRASS);
        helper.succeed();
    }

    private static void assertCapacity(GameTestHelper helper, BlockState bayState, BayTier tier) {
        placeBay(helper, LONE_BAY, bayState, Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        helper.assertValueEqual(bay.tier(), tier, "tier of " + bayState.getBlock());
        helper.assertValueEqual(bay.stacks(), tier.defaultStacks(), "configured stacks of " + tier);
        IItemHandler handler = capabilityAt(helper, LONE_BAY);

        int cobblestone = tier.defaultStacks() * STACK;
        helper.assertValueEqual((int) bay.capacityFor(COBBLESTONE), cobblestone, tier + " holds cobblestone");
        ItemStack rest = handler.insertItem(0, COBBLESTONE.toStack(cobblestone), false);
        helper.assertTrue(rest.isEmpty(), tier + " takes its whole capacity in one call");
        helper.assertValueEqual(bay.storedCount(), cobblestone, tier + " is full");
        helper.assertValueEqual(handler.insertItem(0, COBBLESTONE.toStack(1), false).getCount(), 1,
                tier + " takes not one item more");

        // The same bay, a 16-stacking item: a quarter of the items for the same number of stacks.
        placeBay(helper, LONE_BAY, bayState, Direction.NORTH);
        RackBayBlockEntity pearls = bayAt(helper, LONE_BAY);
        IItemHandler pearlHandler = capabilityAt(helper, LONE_BAY);
        int pearlCapacity = tier.defaultStacks() * ENDER_PEARL.getMaxStackSize();
        helper.assertValueEqual((int) pearls.capacityFor(ENDER_PEARL), pearlCapacity, tier + " holds ender pearls");
        helper.assertTrue(pearlHandler.insertItem(0, ENDER_PEARL.toStack(pearlCapacity), false).isEmpty(),
                tier + " takes its whole capacity in ender pearls");
        helper.assertValueEqual(pearlHandler.insertItem(0, ENDER_PEARL.toStack(1), false).getCount(), 1,
                tier + " takes not one pearl more");
        // One stack per call, whatever a stack is for this item.
        helper.assertValueEqual(pearlHandler.extractItem(0, 1_000, false).getCount(), ENDER_PEARL.getMaxStackSize(),
                tier + " hands out one stack of pearls per call");
        removeBay(helper, LONE_BAY);
    }

    /**
     * The store filter decides what may enter at all — a bay dedicated to cobblestone refuses dirt even while it is
     * empty — and what really lands decides the type, which is what a <b>list</b> filter makes visible: it allows two
     * items, the bay takes the first of them that arrives and then only that one.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayStoreFilterDecidesWhatMayEnter(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IItemHandler handler = capabilityAt(helper, LONE_BAY);

        helper.assertTrue(bay.setStoreFilter(COBBLESTONE.toStack()), "the bay takes a plain item as a filter");
        helper.assertTrue(bay.hasStoreFilter(), "and says it has one");
        helper.assertFalse(handler.isItemValid(0, DIRT.toStack(1)), "an empty but dedicated bay refuses dirt");
        helper.assertValueEqual(handler.insertItem(0, DIRT.toStack(STACK), false).getCount(), STACK,
                "and really refuses it");
        helper.assertTrue(handler.insertItem(0, COBBLESTONE.toStack(STACK), false).isEmpty(), "its own item fits");
        helper.assertValueEqual(bay.storedCount(), STACK, "and is stored");

        // A list filter allowing two items: the first one that lands is the one the bay then holds.
        placeBay(helper, SECOND_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity listed = bayAt(helper, SECOND_BAY);
        IItemHandler listHandler = capabilityAt(helper, SECOND_BAY);
        helper.assertTrue(listed.setStoreFilter(listFilter(COBBLESTONE.toStack(), DIRT.toStack())),
                "the bay takes a list filter");
        helper.assertTrue(listHandler.isItemValid(0, DIRT.toStack(1)), "both listed items may enter an empty bay");
        helper.assertTrue(listHandler.isItemValid(0, COBBLESTONE.toStack(1)), "both, really");
        helper.assertFalse(listHandler.isItemValid(0, ENDER_PEARL.toStack(1)), "an unlisted item may not");
        helper.assertTrue(listHandler.insertItem(0, DIRT.toStack(STACK), false).isEmpty(), "dirt arrives first");
        helper.assertFalse(listHandler.isItemValid(0, COBBLESTONE.toStack(1)),
                "so the other listed item is now out: a bay holds one type");

        // A cleared filter never moves what is already stored (ADR-021) and never blocks taking it back out.
        helper.assertTrue(listed.setStoreFilter(ItemStack.EMPTY), "the filter can be cleared");
        helper.assertValueEqual(listed.storedCount(), STACK, "which moved nothing");
        helper.assertValueEqual(listHandler.extractItem(0, STACK, false).getCount(), STACK, "and blocks no retrieval");
        helper.succeed();
    }

    // --- machines (M28 step 4) -------------------------------------------------------------------------------------

    /**
     * What the item capability opens, and the whole of it in one world: a bay is a <b>storage location a machine can
     * fill and drain by itself</b>, with no input station, no crane and no warehouse anywhere near it.
     * <p>
     * That did not exist before. A warehouse interface deliberately exposes no capability of its own, so until now the
     * only automated route into a warehouse was the input station and the crane carrying things out of it. Nothing
     * about the mod's rules bends for it: a machine makes one real {@code IItemHandler} call at the block in front of
     * it, nothing teleports, and the bay tells its controller instead of being polled (the aisle half of that claim is
     * {@link #bayFedByAMachineReachesTheStockIndex}).
     * <p>
     * Three paths, because they are three different pieces of code:
     * <ul>
     * <li>a <b>belt</b>, belt tunnel or weighted ejector never touches the capability at all. It looks for a
     * {@code DirectBeltInputBehaviour}, and a belt whose end finds none does not even resolve that block as an
     * ending;</li>
     * <li>a <b>vanilla hopper</b> above is the honest end-to-end proof, because not one line of ours runs in its item
     * path: it finds the bay through {@code Capabilities.ItemHandler.BLOCK} like a funnel, a chute or a mechanical arm
     * would, and it <b>backs up</b> on the item the bay will never take rather than hammering it;</li>
     * <li>a hopper <b>below</b> drains the bay, which is the other half of exposing a whole handler and is deliberate:
     * the real extract result is always authoritative, so a machine taking items out while the crane has a job planned
     * simply makes the crane pick less.</li>
     * </ul>
     * <b>Open, and the owner's to decide:</b> whether the <b>aisle</b> face should refuse insertion from anything but
     * the crane. Every face accepts items here, because in a real rack wall the lateral and rear faces are covered by
     * neighbours and the aisle face is often the only reachable one.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = MACHINE_TIMEOUT_TICKS)
    public static void bayTakesItemsFromMachines(GameTestHelper helper) {
        placeBay(helper, MACHINE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, MACHINE_BAY);

        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(bay, DirectBeltInputBehaviour.TYPE);
        if (belt == null) {
            helper.fail("a rack bay must accept items from belts", MACHINE_BAY);
            return;
        }
        TransportedItemStack transported = new TransportedItemStack(COBBLESTONE.toStack(BELT_FED));
        helper.assertTrue(belt.handleInsertion(transported, Direction.EAST, true).isEmpty(),
                "a simulated belt insertion fits");
        helper.assertValueEqual(bay.storedCount(), 0, "and changed nothing");
        helper.assertTrue(belt.handleInsertion(transported, Direction.EAST, false).isEmpty(),
                "the belt's items arrive");
        helper.assertValueEqual(transported.stack.getCount(), BELT_FED, "and the belt's own stack is not modified");
        helper.assertValueEqual(bay.storedCount(), BELT_FED, "they are in the bay");
        helper.assertValueEqual(belt.handleInsertion(DIRT.toStack(STACK), Direction.UP, false).getCount(), STACK,
                "a belt of the wrong item is refused whole, which is what makes it back up");

        // A hopper above with the right item in slot 0 and the wrong one in slot 1: the first is pushed in, the second
        // never moves while the bay holds cobblestone.
        helper.setBlock(ABOVE_MACHINE_BAY, AisleFixture.hopperState(Direction.DOWN));
        HopperBlockEntity feeder = hopperAt(helper, ABOVE_MACHINE_BAY);
        feeder.setItem(0, COBBLESTONE.toStack(HOPPER_FED));
        feeder.setItem(1, DIRT.toStack(HOPPER_FED));
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, BELT_FED + HOPPER_FED, DIRT, HOPPER_FED);
        ItemCensus.assertEquals(helper, conserved, "before the hopper runs");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.storedCount(), BELT_FED + HOPPER_FED,
                            "a vanilla hopper filled a storage location with no warehouse in sight");
                    helper.assertTrue(hopperAt(helper, ABOVE_MACHINE_BAY).getItem(0).isEmpty(),
                            "the hopper handed over everything it could");
                })
                .thenExecuteAfter(BACKED_UP_TICKS, () -> {
                    helper.assertValueEqual(hopperAt(helper, ABOVE_MACHINE_BAY).getItem(1).getCount(), HOPPER_FED,
                            "and backed up on the item the bay will never take, instead of hammering it");
                    helper.assertValueEqual(bay.storedCount(), BELT_FED + HOPPER_FED, "nothing else got in");
                    ItemCensus.assertEquals(helper, conserved, "after the hopper backed up");
                    // The hopper drops its dirt when it goes, which the census follows. It has to go before the bay is
                    // drained empty, because an empty bay forgets its type and would then take the dirt.
                    helper.setBlock(ABOVE_MACHINE_BAY, Blocks.AIR);
                    helper.setBlock(BELOW_MACHINE_BAY, AisleFixture.hopperState(Direction.EAST));
                    helper.setBlock(BESIDE_MACHINE_BAY, Blocks.CHEST);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.storedCount(), 0, "a hopper below drains the bay");
                    helper.assertValueEqual(inventoryCount(helper, BESIDE_MACHINE_BAY, COBBLESTONE),
                            (long) (BELT_FED + HOPPER_FED), "into the chest beside it, item for item");
                })
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved,
                        "after machines filled and drained a bay"))
                .thenSucceed();
    }

    /**
     * The claim that makes a bay better than a chest behind an interface, measured where it is made: a machine that
     * fills a bay directly reaches the controller in the <b>same tick</b>, so the stock index catches up with a
     * one-key diff instead of waiting for a neighbour hint and the round-robin re-read a foreign inventory needs.
     * <p>
     * The notification is asserted on the tick of the insert ({@code pendingSnapshotCount}) and the index right after,
     * because those are two different claims: "the controller was told at once" and "the controller believed the right
     * thing".
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayFedByAMachineReachesTheStockIndex(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        placeBay(helper, aisle.rackPos(BAY_RACK), WareworksBlocks.RACK_BAY_BRASS.getDefaultState(),
                aisle.sideDirection(BAY_RACK));
        aisle.build(false);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    // Exactly what a funnel, a chute, a mechanical arm or a hopper does, through the one capability.
                    helper.assertTrue(capabilityAt(helper, aisle.rackPos(BAY_RACK))
                            .insertItem(0, COBBLESTONE.toStack(BATCH), false).isEmpty(), "a machine fills the bay");
                    helper.assertValueEqual(aisle.controller().pendingSnapshotCount(), 1,
                            "the controller was told in the same tick");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().stockIndex().countAt(COBBLESTONE, BAY_RACK),
                            (long) BATCH, "and the index holds what the machine put in, at the bay's own address");
                    helper.assertValueEqual(aisle.controller().countOf(COBBLESTONE), (long) BATCH,
                            "and nothing beside it");
                })
                .thenSucceed();
    }

    /**
     * The capability path with <b>real machines</b>, both shapes of it, in one world: a Create <b>funnel</b> above a
     * bay fills it through {@code Capabilities.ItemHandler.BLOCK}, and a <b>powered belt</b> whose run ends at a bay
     * hands its load over through the bay's {@code DirectBeltInputBehaviour}.
     * <p>
     * {@link #bayTakesItemsFromMachines} asserts the two insertion <i>calls</i> directly, which is the honest way to
     * state what each of them does with its answer. This test asserts the half no direct call can: that Create's own
     * machines <b>find</b> a bay at all. The two are different pieces of code, and the belt's is the easier one to
     * lose — {@code BeltInventory#resolveEnding} looks for a {@code DirectBeltInputBehaviour} at the block past the
     * last segment, and a belt that finds none does not resolve that block as an ending, so it would simply pile its
     * items up at the end of the run with nothing failing anywhere.
     * <p>
     * Nothing is called by hand on the funnel: the items are dropped into the funnel's own block as item entities, so
     * the real {@code entityInside} runs every tick. The wrong item is then dropped into the same funnel while the bay
     * holds cobblestone, and it must <b>stay in the funnel's block</b>: a funnel asks for a simulated insert first
     * ({@code DirectBeltInputBehaviour}'s default callback goes through {@code ItemHandlerHelper.insertItemStacked}),
     * the bay refuses it there, and the funnel then backs up exactly as a filtered one does instead of taking an item
     * it cannot hand on. One running census covers all of it, so the refused item is counted too.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = MACHINE_TIMEOUT_TICKS)
    public static void bayCapabilityFeeds(GameTestHelper helper) {
        placeBay(helper, FUNNEL_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity funnelFed = bayAt(helper, FUNNEL_BAY);
        // FACING is the direction a funnel's mouth looks, and the block it feeds is the one on the OTHER side
        // (AbstractFunnelBlock#tryInsert aims its InvManipulationBehaviour at pos.relative(FACING.getOpposite())).
        // So: mouth up, bay below.
        helper.setBlock(FUNNEL_BAY.above(), AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP));

        placeBay(helper, BELT_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity beltFed = bayAt(helper, BELT_BAY);
        buildBelt(helper, BELT_START, BELT_END);

        Map<ItemKey, Long> conserved = ItemCensus.of();
        ItemCensus.assertEquals(helper, conserved, "before any machine runs");

        helper.startSequence()
                // The belt has to be turning before its direction means anything, and it has to run towards the bay
                // before "the belt delivered" can be a statement about the bay at all.
                .thenWaitUntil(() -> helper.assertTrue(beltAt(helper, BELT_START).getSpeed() != 0,
                        "the creative motor must turn the belt"))
                .thenExecute(() -> aimBelt(helper, Direction.WEST))
                .thenWaitUntil(() -> helper.assertValueEqual(beltAt(helper, BELT_START).getMovementFacing(),
                        Direction.WEST, "the belt must run towards the bay past its end"))
                .thenExecute(() -> {
                    drop(helper, FUNNEL_BAY.above(), COBBLESTONE.toStack(FUNNEL_FED));
                    ItemCensus.change(conserved, COBBLESTONE, FUNNEL_FED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(funnelFed.storedCount(), FUNNEL_FED,
                        "a Create funnel filled a storage location through its item capability"))
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved, "after a funnel filled a bay");
                    // The wrong item into the same, now committed, funnel: it must stay where it fell.
                    drop(helper, FUNNEL_BAY.above(), DIRT.toStack(FUNNEL_FED));
                    ItemCensus.change(conserved, DIRT, FUNNEL_FED);
                })
                .thenExecuteAfter(BACKED_UP_TICKS, () -> {
                    helper.assertValueEqual(funnelFed.storedCount(), FUNNEL_FED, "nothing else got into the bay");
                    helper.assertValueEqual(funnelFed.storedKey().orElse(null), COBBLESTONE, "nor replaced its type");
                    helper.assertValueEqual(itemEntityCount(helper, DIRT), (long) FUNNEL_FED,
                            "and the item the bay refuses is still lying in the funnel, not consumed");
                    ItemCensus.assertEquals(helper, conserved, "after a funnel backed up on a committed bay");
                    // The belt: a real run, really turning, whose last segment hands over to the bay past its end.
                    insertOntoBelt(helper, BELT_START, COBBLESTONE.toStack(BELT_FED));
                    ItemCensus.change(conserved, COBBLESTONE, BELT_FED);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(beltFed.storedCount(), BELT_FED,
                            "a belt that ends at a bay delivers its load into it");
                    helper.assertValueEqual(beltItems(helper, BELT_START), 0L, "and the belt is empty afterwards");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(funnelFed.storedCount(), FUNNEL_FED, "the funnel's bay is untouched");
                    ItemCensus.assertEquals(helper, conserved, "after a funnel and a belt both filled a bay");
                })
                .thenSucceed();
    }

    /**
     * The three capacities are <b>server config</b>, measured against a configuration that is not the shipped one
     * (M28 step 1): a modpack moves the curve by editing three numbers, and every bay then takes exactly
     * {@code stacks x maxStackSize} of whatever lands in it — which is a different number of <i>items</i> per item,
     * because the config counts stacks.
     * <p>
     * It then does the one thing a config change can do that a player would feel as item loss: it <b>lowers</b> the
     * capacity under a bay that is already fuller than the new limit. Nothing is destroyed and nothing is dropped —
     * the bay keeps every item, refuses every further insert and drains normally, so a modpack update can only ever
     * make a bay stop accepting. {@code baysurvivesanysavedata} makes the same claim about a <b>saved</b> bay read
     * back under a lowered config; this one makes it about a live one, which is what a {@code /reload} of a per-world
     * override really produces.
     * <p>
     * Its own batch, because {@link ConfigOverrides} writes into the loaded config in memory and the tests of one
     * batch run at the same time.
     */
    @GameTest(template = EMPTY_7X5X7, batch = CAPACITY_BATCH)
    public static void bayCapacityFromConfig(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.woodBayStacks, CONFIGURED_WOOD_STACKS);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.andesiteBayStacks, CONFIGURED_ANDESITE_STACKS);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.brassBayStacks, CONFIGURED_BRASS_STACKS);

        assertConfiguredCapacity(helper, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), BayTier.WOOD,
                CONFIGURED_WOOD_STACKS);
        assertConfiguredCapacity(helper, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState(), BayTier.ANDESITE,
                CONFIGURED_ANDESITE_STACKS);
        assertConfiguredCapacity(helper, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), BayTier.BRASS,
                CONFIGURED_BRASS_STACKS);

        // A wooden bay filled to the configured capacity, and then the configuration lowered under it.
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        IItemHandler handler = capabilityAt(helper, LONE_BAY);
        int full = CONFIGURED_WOOD_STACKS * STACK;
        helper.assertTrue(handler.insertItem(0, COBBLESTONE.toStack(full), false).isEmpty(), "the bay is full");
        ConfigOverrides.set(helper, WareworksConfig.SERVER.woodBayStacks, LOWERED_WOOD_STACKS);
        helper.assertValueEqual(bay.stacks(), LOWERED_WOOD_STACKS, "the bay reads the lowered capacity at once");
        helper.assertValueEqual(bay.storedCount(), full, "and keeps every item it already held");
        helper.assertValueEqual(handler.insertItem(0, COBBLESTONE.toStack(1), false).getCount(), 1,
                "it accepts not one item more");
        helper.assertValueEqual(handler.extractItem(0, STACK, false).getCount(), STACK, "while draining normally");
        helper.assertValueEqual(bay.storedCount(), full - STACK, "item for item");
        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /** Restores the capacities whatever {@link #bayCapacityFromConfig} did, also after a failure. */
    @AfterBatch(batch = CAPACITY_BATCH)
    public static void restoreCapacityConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * Fails unless a bay of {@code tier} holds exactly {@code stacks} stacks of a 64-stacking and of a 16-stacking
     * item under the current configuration, and not one item more.
     */
    private static void assertConfiguredCapacity(GameTestHelper helper, BlockState bayState, BayTier tier,
                                                 int stacks) {
        for (ItemKey key : List.of(COBBLESTONE, ENDER_PEARL)) {
            placeBay(helper, LONE_BAY, bayState, Direction.NORTH);
            RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
            helper.assertValueEqual(bay.stacks(), stacks, "configured stacks of " + tier);
            int capacity = stacks * key.getMaxStackSize();
            helper.assertValueEqual((int) bay.capacityFor(key), capacity,
                    tier + " holds " + stacks + " stacks of " + key);
            IItemHandler handler = capabilityAt(helper, LONE_BAY);
            helper.assertTrue(handler.insertItem(0, key.toStack(capacity), false).isEmpty(),
                    tier + " takes its configured capacity of " + key + " in one call");
            helper.assertValueEqual(handler.insertItem(0, key.toStack(1), false).getCount(), 1,
                    tier + " takes not one " + key + " more");
        }
        removeBay(helper, LONE_BAY);
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    /**
     * 65 536 items, a store filter and a storage priority through a save and a reload.
     * <p>
     * <b>The one test that can catch the {@code ItemStack.save} trap</b>, and it only fails after the reload: a bay
     * built on an {@code ItemStackHandler} would save a brass bay's load through {@code ItemStack.CODEC}, whose count
     * is bounded at 99, and lose everything silently — nothing throws, and the network codec is unbounded, so the bay
     * would look perfectly right until the world was loaded again. The round trip is done twice, because a load that
     * truncated would still have looked right on the first pass.
     * <p>
     * It also pins that {@code writeSafe} — the schematic path — carries the filter and the priority and <b>not one
     * item</b>: a schematicannon printing a full brass bay would be the removal crate the owner rejected, multiplied.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayPersistenceRoundTrip(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();

        int load = BayTier.BRASS.defaultStacks() * STACK;
        helper.assertValueEqual(load, 65_536, "a brass bay of cobblestone");
        helper.assertTrue(capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(load), false).isEmpty(),
                "the bay takes its whole load");
        bay.setStoreFilter(COBBLESTONE.toStack());
        bay.setStorePriority(7);

        RackBayBlockEntity reloaded = bay;
        for (int pass = 1; pass <= 2; pass++) {
            CompoundTag saved = reloaded.saveWithFullMetadata(registries);
            helper.assertValueEqual(saved.getInt(RackBayHandler.COUNT_TAG), load, "the count is saved as an int");
            helper.assertTrue(saved.contains(RackBayHandler.STORED_TAG), "and the item type beside it");
            reloaded = loadCopy(helper, reloaded, saved);
            helper.assertValueEqual(reloaded.storedCount(), load, "the whole load came back, pass " + pass);
            helper.assertValueEqual(reloaded.storedKey().orElse(null), COBBLESTONE, "as cobblestone, pass " + pass);
            helper.assertValueEqual(reloaded.storePriority(), 7, "with its priority, pass " + pass);
            helper.assertTrue(COBBLESTONE.matches(reloaded.storeFilter()), "and its filter, pass " + pass);
            helper.assertValueEqual(capabilityAt(helper, LONE_BAY).getStackInSlot(0).getCount(), load,
                    "and the live capability says so, pass " + pass);
        }

        // The schematic path: settings travel, items never do.
        CompoundTag safe = new CompoundTag();
        reloaded.writeSafe(safe, registries);
        helper.assertFalse(safe.contains(RackBayHandler.STORED_TAG), "a schematic carries no stored item");
        helper.assertFalse(safe.contains(RackBayHandler.COUNT_TAG), "and no count");
        helper.assertTrue(safe.contains(StorageFilterBehaviour.PRIORITY_TAG), "but it carries the priority");
        removeBay(helper, LONE_BAY); // 65 536 items are not something to hand to the test teardown
        helper.succeed();
    }

    /**
     * Save data is untrusted — a world written before rack bays existed, {@code /data merge}, an uploaded schematic and
     * crafted block entity data all reach the same {@code read}. None of it may throw, none of it may allocate, and the
     * one case a legitimate save really produces must not lose an item:
     * <ul>
     * <li>no tag at all, <b>read into a fresh block entity</b>: an empty bay, which is also exactly what a pre-M28
     * world has. The same tag read into a bay that is already <b>standing</b> with goods in it is a different
     * question and has a different answer — it says nothing about the contents, so it changes nothing;</li>
     * <li>a count with no item type, and an item type that cannot be decoded (its mod was removed): empty, which is
     * vanilla's own answer for an unreadable container entry;</li>
     * <li>a crafted count of {@link Integer#MAX_VALUE}: clamped to what no configuration can exceed;</li>
     * <li>a count above <b>this</b> bay's capacity, which lowering the config legitimately produces: <b>kept in
     * full</b>. It accepts nothing until it has drained, so a world load never destroys anything.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void baySurvivesAnySaveData(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);

        helper.assertValueEqual(readBack(helper, bay, new CompoundTag()).storedCount(), 0,
                "a bay written before M28 reads as empty");

        CompoundTag countOnly = new CompoundTag();
        countOnly.putInt(RackBayHandler.COUNT_TAG, 512);
        helper.assertValueEqual(readBack(helper, bay, countOnly).storedCount(), 0, "a count with no item type is empty");

        CompoundTag unreadable = new CompoundTag();
        unreadable.putInt(RackBayHandler.COUNT_TAG, 512);
        CompoundTag brokenKey = new CompoundTag();
        brokenKey.putString("id", "wareworks:an_item_of_a_mod_that_was_removed");
        unreadable.put(RackBayHandler.STORED_TAG, brokenKey);
        helper.assertValueEqual(readBack(helper, bay, unreadable).storedCount(), 0,
                "an item type that cannot be decoded is empty, as in a vanilla container");

        CompoundTag crafted = new CompoundTag();
        crafted.putInt(RackBayHandler.COUNT_TAG, Integer.MAX_VALUE);
        COBBLESTONE.saveTo(crafted, RackBayHandler.STORED_TAG, helper.getLevel().registryAccess());
        helper.assertValueEqual(readBack(helper, bay, crafted).storedCount(), BayTier.MAX_CAPACITY_ITEMS,
                "a crafted count is clamped to what no configuration can exceed");

        // A capacity lowered under a bay that is already fuller than it: it keeps everything and takes nothing.
        CompoundTag overFull = new CompoundTag();
        int tooMuch = BayTier.BRASS.defaultStacks() * STACK;
        overFull.putInt(RackBayHandler.COUNT_TAG, tooMuch);
        COBBLESTONE.saveTo(overFull, RackBayHandler.STORED_TAG, helper.getLevel().registryAccess());
        RackBayBlockEntity lowered = readBack(helper, bay, overFull);
        helper.assertValueEqual(lowered.storedCount(), tooMuch, "an over-full wooden bay keeps every item");
        IItemHandler handler = capabilityAt(helper, LONE_BAY);
        helper.assertValueEqual(handler.insertItem(0, COBBLESTONE.toStack(STACK), false).getCount(), STACK,
                "and accepts nothing until it has drained");
        helper.assertValueEqual(handler.extractItem(0, STACK, false).getCount(), STACK, "while draining normally");

        // The same contentless tag, but read into a bay that is ALREADY STANDING with goods in it. That is not a
        // world load - a world load always reads into a freshly built, empty handler - it is what Create's schematic
        // placement does (GameTest baySchematicPrintKeepsItsLoad), and before the guard in RackBayHandler#readFrom it
        // emptied a full bay in place: no destroy(), so no pallet and no drop, and not even a log line.
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity standing = bayAt(helper, LONE_BAY);
        standing.insert(COBBLESTONE.toStack(SPILLED_LOAD), false);
        CompoundTag settingsOnly = new CompoundTag();
        standing.writeSafe(settingsOnly, helper.getLevel().registryAccess());
        helper.assertFalse(settingsOnly.contains(RackBayHandler.COUNT_TAG), "such a tag carries no count");
        helper.assertFalse(settingsOnly.contains(RackBayHandler.STORED_TAG), "and no item type");
        standing.loadWithComponents(settingsOnly, helper.getLevel().registryAccess());
        helper.assertValueEqual(standing.storedCount(), SPILLED_LOAD, "so a standing bay keeps its whole load");
        helper.assertValueEqual(standing.storedKey().orElse(null), COBBLESTONE, "and the type it is committed to");

        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    // --- removal ---------------------------------------------------------------------------------------------------

    /**
     * <b>Breaking a bay resets it</b> (ADR-046): an empty bay and the whole load as <b>one</b> pallet on the floor,
     * never both the items and a filled block, and never a filled block in a pocket.
     * <p>
     * The number matters. A brass bay holds 65 536 items and a single {@code ItemStack} caps at 99, so spilling a full
     * bay is thousands of item entities — which is why the load leaves as one entity, and why this test insists on
     * <b>exactly one</b> pallet and <b>no</b> item entities at all. The census is taken before and after, so a load
     * that was lost or doubled on the way fails here; it can only see the pallet because both censuses were taught
     * about it in the same commit as the entity, which is the whole reason this step and that change belong together.
     * <p>
     * The contents are cleared <b>before</b> the pallet is spawned, so a second pass over the block entity finds
     * nothing — asserted here, because that ordering is what makes duplication impossible rather than unlikely.
     * <p>
     * <b>And the bay item that falls beside the pallet is a plain one.</b> Since M28 step 9 a bay has a loot table,
     * and that table must stay Registrate's bare self-drop: a {@code copy_nbt} or a {@code setBlockEntityData} on it
     * would hand out the items <i>and</i> a filled block, which is the duplication the pallet exists to avoid and
     * would turn a brass bay into a pocketable crate worth thirty-eight shulker boxes. Neither census can see that —
     * a component on an item entity is not an item — so the drop is compared with a freshly built bay item,
     * components and all.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayBreakResets(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, SPILLED_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the bay is broken");

        // Level#destroyBlock(pos, drop) is the shape of a survival break, an explosion, a Create saw or drill and
        // /setblock ... destroy: it reaches IBE.onRemove and therefore RackBayBlockEntity#destroy.
        helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(LONE_BAY), true), "the bay is broken");

        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(LONE_BAY)) == null, "the bay is gone");
        helper.assertValueEqual(bay.storedCount(), 0, "and its contents were cleared before anything was spawned");
        helper.assertTrue(bay.storedKey().isEmpty(), "so a second pass over it finds nothing to hand out twice");
        PalletEntity pallet = theOnePallet(helper, "after a bay was broken with goods in it");
        helper.assertValueEqual(pallet.carriedCount(), SPILLED_LOAD, "the pallet carries the whole load");
        helper.assertTrue(pallet.carriedKey().filter(COBBLESTONE::equals).isPresent(), "unchanged");
        List<ItemEntity> dropped = helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS), entity -> true);
        helper.assertValueEqual(dropped.size(), 1,
                "the load left as one pallet, so the only item entity is the empty bay itself");
        ItemStack bayItem = dropped.getFirst().getItem();
        helper.assertTrue(ItemStack.matches(bayItem, new ItemStack(WareworksBlocks.RACK_BAY_WOOD.asItem())),
                "and the bay item is a plain one with nothing copied into it: " + bayItem + " "
                        + bayItem.getComponents());
        // The goods are conserved and the block itself has become an item, which is what "the bay resets" means.
        ItemCensus.change(conserved, ItemKey.of(WareworksBlocks.RACK_BAY_WOOD.asItem()), 1);
        ItemCensus.assertEquals(helper, conserved, "after the bay was broken");
        helper.succeed();
    }

    /**
     * <b>The fill level a player reads off the front follows the contents</b>, in the block state, so a rack wall can
     * be read by walking past it ({@code RackBayBlock#FILL}, M28 step 9).
     * <p>
     * Three things are claimed, and each of them is a way the look could lie about the goods:
     * <ul>
     * <li><b>a single item already shows</b>, because "is there anything in this bay at all" is the question asked
     * from across the room, and a step that rounded down would leave a wooden bay holding a thousand cobblestone
     * looking exactly as empty as one holding none;</li>
     * <li><b>only an empty bay shows nothing</b>, and only a full one shows the last step;</li>
     * <li>the steps follow the <b>configured</b> capacity rather than a fixed number of items, so the same count
     * reads differently in a wooden bay and in a brass one — which is the whole point of a <i>level</i>.</li>
     * </ul>
     * The look is also the one state of a bay that must stay purely cosmetic: it is checked here that an overloaded
     * bay's level is unaffected, which is the other half of "the column rule never changes the model" (ADR-044).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayFillLevelFollowsItsContents(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        long capacity = bay.capacityFor(COBBLESTONE);
        assertFillLevel(helper, LONE_BAY, 0, "an empty bay");

        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(1), false);
        assertFillLevel(helper, LONE_BAY, 1, "a bay holding a single item");

        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack((int) capacity / 2 - 1), false);
        assertFillLevel(helper, LONE_BAY, RackBayBlock.FILL_LEVELS / 2, "a bay that is half full");

        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack((int) capacity), false);
        helper.assertValueEqual((long) bay.storedCount(), capacity, "the bay took everything it has room for");
        assertFillLevel(helper, LONE_BAY, RackBayBlock.FILL_LEVELS, "a full bay");

        // A stronger bay above it: out of the warehouse's store plans, and unchanged to look at.
        helper.setBlock(LONE_BAY.above(), WareworksBlocks.RACK_BAY_BRASS.getDefaultState()
                .setValue(RackBayBlock.FACING, Direction.NORTH));
        assertOverloaded(helper, LONE_BAY, true, "the bay under a brass one");
        assertFillLevel(helper, LONE_BAY, RackBayBlock.FILL_LEVELS, "which still holds every item it held");
        helper.setBlock(LONE_BAY.above(), Blocks.AIR);

        // The same count in a stronger bay reads lower, because the level is a fraction of the capacity.
        placeBay(helper, SECOND_BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), Direction.NORTH);
        capabilityAt(helper, SECOND_BAY).insertItem(0, COBBLESTONE.toStack((int) capacity), false);
        assertFillLevel(helper, SECOND_BAY, 1, "a wooden bay's worth of goods in a brass one");

        // ... and emptying a bay empties its front with it.
        while (!bay.extract(Integer.MAX_VALUE, false).isEmpty()) {
            // one stack per call, as the handler contract demands
        }
        assertFillLevel(helper, LONE_BAY, 0, "a bay that has been emptied again");
        removeBay(helper, LONE_BAY);
        removeBay(helper, SECOND_BAY);
        helper.succeed();
    }

    /** Asserts the fill step the bay at {@code pos} shows on its front. */
    private static void assertFillLevel(GameTestHelper helper, BlockPos pos, int expected, String what) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(pos));
        helper.assertTrue(state.getBlock() instanceof RackBayBlock, what + " is a rack bay");
        helper.assertValueEqual(state.getValue(RackBayBlock.FILL), expected, "fill level of " + what);
    }

    /**
     * A <b>creative</b> break yields a pallet and no bay item, which is right: the goods were never the creative
     * player's to conjure away, and a bay in creative is already free.
     * <p>
     * It is driven the way {@code ServerPlayerGameMode#destroyBlock} drives it — {@code playerWillDestroy}, then
     * {@code onDestroyedByPlayer} with {@code willHarvest = false}, then {@code Block#destroy}, and <b>no</b>
     * {@code playerDestroy}, because the creative branch returns before it. Calling those three directly rather than
     * going through a {@code ServerPlayer} keeps the test to the one claim it makes and needs no network connection to
     * exist.
     * <p>
     * "No bay item" is checked as "nothing was dropped at all", which is the stronger reading and the one that holds
     * now that a bay has a loot table: the creative branch never runs it, so neither the block nor its goods can be
     * conjured out of a wall somebody else built.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayBreakInCreative(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), Direction.NORTH);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, SPILLED_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the bay is broken in creative");

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(LONE_BAY);
        Player creative = NamingClick.player(helper);
        BlockState state = level.getBlockState(absolute).getBlock().playerWillDestroy(level, absolute,
                level.getBlockState(absolute), creative);
        helper.assertTrue(state.onDestroyedByPlayer(level, absolute, creative, false, level.getFluidState(absolute)),
                "the creative break removes the bay");
        state.getBlock().destroy(level, absolute, state);

        PalletEntity pallet = theOnePallet(helper, "after a creative break");
        helper.assertValueEqual(pallet.carriedCount(), SPILLED_LOAD, "the pallet carries the whole load");
        helper.assertTrue(helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS), entity -> true).isEmpty(),
                "and nothing was dropped beside it: no bay item, no items");
        ItemCensus.assertEquals(helper, conserved, "after the bay was broken in creative");
        helper.succeed();
    }

    /**
     * The one loss vector of the break path that nothing else can reach: <b>{@code addFreshEntity} returns a boolean
     * because it can refuse.</b> {@code EntityJoinLevelEvent} is cancellable and the UUID set can reject, so one
     * unrelated mod's handler would turn breaking a bay into total item loss if {@code destroy()} ignored that answer.
     * <p>
     * The test cancels exactly that event for pallets of this level, breaks a full bay, and insists that every item
     * still exists — as item entities, which is deliberately ugly (a full brass bay would be a few thousand of them)
     * because a loud mess is the right failure mode for an item-conservation event and a silent loss is not.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void palletSpawnRefusedFallsBackToItemEntities(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, SPILLED_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the refused break");

        PalletSpawnBlocker blocker = new PalletSpawnBlocker(helper);
        blocker.start();
        try {
            helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(LONE_BAY), false),
                    "the bay is broken");
        } finally {
            blocker.stop();
        }

        helper.assertValueEqual(blocker.refused(), 1, "exactly one pallet was refused by the level");
        helper.assertTrue(helper.getLevel()
                .getEntitiesOfClass(PalletEntity.class, helper.getBounds().inflate(DROP_RADIUS)).isEmpty(),
                "so no pallet is in the world");
        long spilled = 0;
        for (ItemEntity entity : helper.getLevel().getEntities(EntityType.ITEM,
                helper.getBounds().inflate(DROP_RADIUS), entity -> COBBLESTONE.matches(entity.getItem())))
            spilled += entity.getItem().getCount();
        helper.assertValueEqual(spilled, (long) SPILLED_LOAD, "and the whole load reached the world as items instead");
        ItemCensus.assertEquals(helper, conserved, "after a refused pallet spawn");
        helper.succeed();
    }

    /**
     * The path the owner settled on in place of putting a filled bay back: <b>you refill by hand, stack by stack, with
     * the same gesture the bay uses.</b>
     * <p>
     * The whole break → drop → refill route runs once, and every item is counted at every step: a full bay is broken,
     * a stack is taken off the pallet with an empty-hand Shift click, and that stack goes into a fresh bay with a
     * Shift right-click on the block. There is deliberately no one-move path — the pallet has no item form at all — so
     * the goods travel as items through a player's hand, twice, with a real result each time.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayRefilledFromAPalletByHand(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        Player player = NamingClick.player(helper);
        player.getInventory().clearContent();
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, SPILLED_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the bay is broken");

        helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(LONE_BAY), false), "the bay is broken");
        PalletEntity pallet = theOnePallet(helper, "after the bay was broken");

        // A stack off the pallet, into the hand. The census cannot see a player's inventory, so from here on the
        // by-hand count is what carries the claim.
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertValueEqual(pallet.interact(player, InteractionHand.MAIN_HAND), InteractionResult.CONSUME,
                "a Shift click with an empty hand takes a stack off the pallet");
        helper.assertValueEqual(pallet.carriedCount(), SPILLED_LOAD - STACK, "exactly one stack left the pallet");

        // And into a fresh, empty bay, with the bay's own gesture.
        placeBay(helper, SECOND_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity refilled = bayAt(helper, SECOND_BAY);
        ItemStack held = player.getInventory().items.stream().filter(COBBLESTONE::matches).findFirst()
                .orElse(ItemStack.EMPTY);
        helper.assertValueEqual(click(helper, player, SECOND_BAY, held, true), ItemInteractionResult.CONSUME,
                "a Shift right-click puts the stack into the new bay");
        helper.assertValueEqual(refilled.storedCount(), STACK, "which now holds exactly that stack");
        assertRefillConserved(helper, pallet, refilled, player, SPILLED_LOAD);
        helper.succeed();
    }

    /**
     * Fails unless the pallet, the refilled bay, the player's slots and the floor together still hold every item the
     * broken bay started with. Four places, because a hand gesture moves items between two of them that no item census
     * can see at once.
     */
    private static void assertRefillConserved(GameTestHelper helper, PalletEntity pallet, RackBayBlockEntity bay,
                                              Player player, int expected) {
        long total = pallet.carriedKey().filter(COBBLESTONE::equals).map(key -> (long) pallet.carriedCount())
                .orElse(0L);
        total += bay.storedKey().filter(COBBLESTONE::equals).map(key -> (long) bay.storedCount()).orElse(0L);
        for (ItemStack stack : player.getInventory().items)
            if (COBBLESTONE.matches(stack))
                total += stack.getCount();
        for (ItemEntity entity : helper.getLevel().getEntities(EntityType.ITEM,
                helper.getBounds().inflate(DROP_RADIUS), entity -> COBBLESTONE.matches(entity.getItem())))
            total += entity.getItem().getCount();
        helper.assertValueEqual(total, (long) expected,
                "every item of the broken bay is in the pallet, the new bay, the hand or on the floor");
    }

    /** The one pallet in the test area; fails unless there is exactly one. */
    private static PalletEntity theOnePallet(GameTestHelper helper, String what) {
        List<PalletEntity> pallets = helper.getLevel().getEntitiesOfClass(PalletEntity.class,
                helper.getBounds().inflate(DROP_RADIUS));
        if (pallets.size() != 1) {
            helper.fail("exactly one pallet must lie in the world " + what + ", found " + pallets.size(), LONE_BAY);
            throw new IllegalStateException("unreachable");
        }
        return pallets.get(0);
    }

    /**
     * Cancels the level join of every pallet of this level while it is started — the one way a test can reach the
     * fallback in {@code RackBayBlockEntity#destroy}. Added and removed exactly as {@code CraneSoundGameTests} does
     * it, so the listener is gone again whatever the test does.
     */
    private static final class PalletSpawnBlocker implements Consumer<EntityJoinLevelEvent> {
        private final Level level;
        private int refused;

        PalletSpawnBlocker(GameTestHelper helper) {
            this.level = helper.getLevel();
        }

        void start() {
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, this);
        }

        void stop() {
            NeoForge.EVENT_BUS.unregister(this);
        }

        int refused() {
            return refused;
        }

        @Override
        public void accept(EntityJoinLevelEvent event) {
            if (event.getLevel() != level || !(event.getEntity() instanceof PalletEntity))
                return;
            refused++;
            event.setCanceled(true);
        }
    }

    /**
     * {@code /setblock}, {@code /fill}, {@code /clone} and structure placement call {@link Clearable#tryClear} before
     * they replace a block, and a bay is emptied without dropping anything — deliberate parity with a vanilla chest,
     * which loses its contents to the same commands, and conceded by the issue. It is asserted here rather than
     * discovered later, because a chest loses 27 stacks and a brass bay 1 024.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void baySetBlockVoidsItLikeAVanillaChest(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        helper.assertValueEqual(bay.storedCount(), SPILLED_LOAD, "the bay is loaded");

        // Exactly what SetBlockCommand does before it writes the new state.
        Clearable.tryClear(bay);
        helper.assertValueEqual(bay.storedCount(), 0, "the bay was emptied");
        helper.assertTrue(bay.storedKey().isEmpty(), "and forgot its type");
        helper.setBlock(LONE_BAY, Blocks.STONE);
        helper.assertTrue(helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS), entity -> true).isEmpty(),
                "nothing was dropped, exactly as for a chest");
        helper.succeed();
    }

    /**
     * <b>A Create schematic print over a standing, stocked bay leaves every item where it is.</b>
     * <p>
     * This is the one route that reaches a bay's saved data without reaching either {@link Clearable#tryClear} or
     * {@code destroy()}, and it used to empty a full bay <b>in place</b>: {@code BlockHelper.placeSchematicBlock}
     * writes the block state and then calls {@code loadWithComponents} on whatever block entity is standing there,
     * and a bay's block entity survives that write because {@code IBE.onRemove} returns early while the block stays
     * the same. The data a printer carries is {@code PartialSafeNBT}, i.e. the filter and the priority and no
     * contents, so a reader that took "no count" for "the bay is empty" deleted 65 536 items with no pallet, no drop,
     * no log line, no fill update and no client sync. A vanilla chest in the same position keeps its 27 stacks.
     * <p>
     * Both halves of the reachability are pinned here rather than assumed: the printer's data really does carry the
     * settings and really does not carry the contents, and the live bay's {@code FILL} really does differ from the
     * schematic's — which is what lets the print through at all, since a printer skips a position whose state already
     * matches. The fill level the print overwrites is repaired on the scheduled tick the bay asks for, so the front
     * never claims a level the bay does not hold.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void baySchematicPrintKeepsItsLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(LONE_BAY);

        // What a schematic of a rack wall carries, read exactly as Create's printer reads it.
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity source = bayAt(helper, LONE_BAY);
        source.setStoreFilter(COBBLESTONE.toStack());
        BlockState schematicState = level.getBlockState(absolute);
        CompoundTag schematicData = BlockHelper.prepareBlockEntityData(level, schematicState, source);
        helper.assertTrue(schematicData != null, "a rack bay is PartialSafeNBT, so a schematic carries its settings");
        helper.assertFalse(schematicData.contains(RackBayHandler.COUNT_TAG),
                "and it carries no count, which is exactly why a print may not read as an empty bay");
        helper.assertFalse(schematicData.contains(RackBayHandler.STORED_TAG), "nor a stored item type");

        // The bay the print lands on: the same block, stocked, and therefore in a different FILL state.
        removeBay(helper, LONE_BAY);
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        capabilityAt(helper, LONE_BAY).insertItem(0, COBBLESTONE.toStack(SPILLED_LOAD), false);
        helper.assertValueEqual(bay.storedCount(), SPILLED_LOAD, "the bay is loaded");
        helper.assertTrue(level.getBlockState(absolute).getValue(RackBayBlock.FILL)
                != schematicState.getValue(RackBayBlock.FILL),
                "and shows it, so the schematic's state really differs and the print is not skipped");

        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, SPILLED_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the print");
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a schematic is printed over a bay"));

        // The print waits two ticks on purpose, and the whole fill half of this test turns on it: a block entity
        // placed in this tick is "fresh", and Level#tickBlockEntities calls onLoad() on it on the NEXT tick - which
        // repairs a stale fill level all by itself. A bay a player printed over has stood there for minutes, so its
        // onLoad is long past, and the only route left is the one RackBayBlockEntity#read schedules.
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockHelper.placeSchematicBlock(level, schematicState, absolute,
                            WareworksBlocks.RACK_BAY_WOOD.asStack(), schematicData.copy());

                    helper.assertTrue(level.getBlockEntity(absolute) == bay,
                            "the block entity survives a print of the same block, which makes this reachable at all");
                    helper.assertValueEqual(level.getBlockState(absolute).getValue(RackBayBlock.FILL),
                            schematicState.getValue(RackBayBlock.FILL),
                            "the print writes the schematic's own fill level");
                    helper.assertValueEqual(bay.storedCount(), SPILLED_LOAD, "and every item is still in the bay");
                    helper.assertValueEqual(bay.storedKey(), Optional.of(COBBLESTONE),
                            "with the type it was committed to");
                    helper.assertTrue(level.getEntitiesOfClass(PalletEntity.class,
                            helper.getBounds().inflate(DROP_RADIUS)).isEmpty(),
                            "no pallet was left: the block was never destroyed");
                    helper.assertTrue(level.getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS),
                            entity -> true).isEmpty(), "and nothing was dropped either");
                })
                // Two ticks is the window the repair has: the bay asks for a scheduled tick one tick out, exactly as
                // it does for a stale OVERLOADED after a command. A front that kept claiming the schematic's level
                // would be the same kind of lie as the lost load, only cheaper.
                .thenExecuteAfter(2, () -> helper.assertValueEqual(
                        helper.getLevel().getBlockState(absolute).getValue(RackBayBlock.FILL), bay.fillStep(),
                        "the fill level the print overwrote is repaired on the bay's own scheduled tick"))
                .thenExecute(() -> {
                    helper.assertValueEqual(bay.storedCount(), SPILLED_LOAD, "and the load is still untouched");
                    removeBay(helper, LONE_BAY);
                })
                .thenSucceed();
    }

    // --- the bay as a storage location -----------------------------------------------------------------------------

    /**
     * The claim the whole design rests on: a bay <b>is</b> a storage location, with no interface in front of it and no
     * change to the controller, the crane, the addressing or the planner.
     * <p>
     * A bay is hand-filled before the aisle exists, exactly as a player would build it; then the dock, the rails and the
     * controller go up around it. The controller records it, reads it and finds the hand-loaded items in its index; the
     * crane then stores a batch from the input into that bay (which can only happen by driving there — no item ever
     * teleports) and retrieves from it into the output. One item census every tick covers all of it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayIsAStorageLocation(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        // The bay first, with items in it, and only then the warehouse around it.
        placeBay(helper, aisle.rackPos(BAY_RACK), WareworksBlocks.RACK_BAY_BRASS.getDefaultState(),
                aisle.sideDirection(BAY_RACK));
        RackBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        helper.assertTrue(capabilityAt(helper, aisle.rackPos(BAY_RACK))
                .insertItem(0, COBBLESTONE.toStack(PRELOADED), false).isEmpty(), "the bay is filled by hand");
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);

        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, PRELOADED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a rack bay serves an aisle"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    helper.assertValueEqual(aisle.controller().countOf(COBBLESTONE), (long) PRELOADED,
                            "the hand-loaded items are in the stock index");
                    helper.assertValueEqual(aisle.controller().stockIndex().countAt(COBBLESTONE, BAY_RACK),
                            (long) PRELOADED, "at the bay's own address");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler buffer = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(buffer, COBBLESTONE.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), PRELOADED + BATCH,
                        "the crane stored into the bay"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, COBBLESTONE), 0L,
                            "the bay consolidated the item rather than the empty chest taking it");
                    aisle.assertIdleAndEmpty();
                    aisle.requestAt(OUTPUT_RACK, COBBLESTONE.toStack(), REQUEST_AMOUNT,
                            aisle.inventoryPos(OUTPUT_RACK));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, COBBLESTONE),
                        (long) REQUEST_AMOUNT, "and the crane retrieved out of it again"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.storedCount(), PRELOADED + BATCH - REQUEST_AMOUNT,
                            "exactly what was requested left the bay");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>The end-to-end form of the regression that shipped in {@code fbc2249}</b>, and the test that would have caught
     * it: a crane fills one bay to {@value #THREE_TRIPS} items in three trips, across the one count no snapshot can
     * decide.
     * <p>
     * A bay has <b>one</b> slot whose limit is its whole capacity, so at exactly one full stack the two per-slot rules
     * disagree: an ordinary slot is full, a bay has barely started. The planner's capacity gate is a hard
     * {@code continue} before any live simulation ({@code JobPlanner}), so a gate that answered "full" there would have
     * killed the bay for the rest of the world's life — the crane delivers 64, the bay holds exactly 64, and no job is
     * ever planned into it again. The default configuration lands on that knife edge with the <b>first</b> delivery,
     * because a carry limit is exactly one stack.
     * <p>
     * Both halves are asserted, because the estimate and the trips are two different claims:
     * <ul>
     * <li>the estimate itself, on the bay's own snapshot, at exactly one stack — it must be
     * {@link CapacityMath#UNKNOWN_CAPACITY} ("ask the inventory"), not {@code 0}. Asserted by hand before the aisle
     * exists, so it is a deterministic statement about the number rather than something a crane's timing decides;</li>
     * <li>and the three trips, with the bay as the <b>only</b> storage location there is, so a dead bay means the items
     * stay at the input and the test times out rather than passing quietly.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayFillsPastOneStack(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        placeBay(helper, bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aisle.sideDirection(BAY_RACK));
        RackBayBlockEntity bay = bayAt(helper, bayPos);

        // The knife edge, stated as the number it is: exactly one stack in a slot whose limit is far above the
        // stack-size ceiling. Done before the warehouse exists and cleared again, so the census below starts at zero.
        helper.assertTrue(capabilityAt(helper, bayPos).insertItem(0, COBBLESTONE.toStack(STACK), false).isEmpty(),
                "one stack goes into the bay");
        helper.assertValueEqual(bay.snapshot().insertable(COBBLESTONE, STACK),
                CapacityMath.UNKNOWN_CAPACITY,
                "a bay holding exactly one stack must estimate 'ask the inventory', never 'full'");
        bay.clearContent();
        helper.assertValueEqual(bay.storedCount(), 0, "and the bay starts the run empty");

        aisle.build(true);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while one bay fills past one stack"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(THREE_TRIPS));
                    ItemCensus.change(conserved, COBBLESTONE, THREE_TRIPS);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), THREE_TRIPS,
                        "the crane keeps filling the one bay there is, trip after trip, past one stack"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, COBBLESTONE), 0L,
                            "and the input buffer is empty");
                    helper.assertValueEqual(aisle.controller().stockIndex().countAt(COBBLESTONE, BAY_RACK),
                            (long) THREE_TRIPS, "the index agrees with the block");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The one-type rule under a <b>real crane</b>: two item types wait at one input, one unfiltered bay is the only
     * storage location there is, and the second type never enters the bay — it waits at the input until the bay has
     * emptied, and is then the bay's type.
     * <p>
     * This is the half of the rule a player meets. It is enforced in the <b>planner's store gate</b> and not at the
     * bay's own door ({@code AisleFilters#match}, consulted through {@code StorageMember#holdsOneTypeOnly}), which is
     * what makes the refusal free: the dirt is dropped before the capacity estimate, before any live simulation and
     * before a refusal could be remembered, so a warehouse whose every bay is committed does not burn its live
     * simulation budget on them every dispatch interval.
     * <p>
     * The second half — that the rule is <b>reported and not baked in</b> — is the retrieval at the end: nothing about
     * the bay's commitment survives it emptying, so the dirt that waited is stored the moment there is room for it. A
     * store rule never restricts retrieval (ADR-021), so the cobblestone comes back out while the dirt is being
     * refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = ONE_TYPE_TIMEOUT_TICKS)
    public static void bayOneTypeOnly(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        placeBay(helper, bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aisle.sideDirection(BAY_RACK));
        RackBayBlockEntity bay = bayAt(helper, bayPos);
        aisle.build(true);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);

        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while one bay holds one type");
            // Every tick, not only at the end: the refusal is a planning decision, so the dirt must never be picked
            // up at all while the bay is committed. A test that only looked at the bay's contents afterwards would
            // also pass for a crane that fetched the dirt, drove to the bay, was refused and carried it back.
            helper.assertTrue(aisle.dock().currentJob().filter(job -> DIRT.equals(job.key())).isEmpty()
                    || bay.storedKey().filter(COBBLESTONE::equals).isEmpty(),
                    "no job may carry dirt while the one bay there is holds cobblestone");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler buffer = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(buffer, COBBLESTONE.toStack(BATCH));
                    aisle.insertAll(buffer, DIRT.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                    ItemCensus.change(conserved, DIRT, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), BATCH,
                        "the first type that arrives fills the bay"))
                .thenExecuteAfter(ONE_TYPE_SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "which is what it now holds");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), (long) BATCH,
                            "and the second type stays at the input, because there is nowhere else for it to go");
                    helper.assertValueEqual(aisle.controller().countOf(DIRT), 0L, "nothing of it is in storage");
                    aisle.assertIdleAndEmpty();
                    // Retrieval is never restricted by a store rule, so the bay empties while the dirt is refused.
                    aisle.requestAt(OUTPUT_RACK, COBBLESTONE.toStack(), BATCH, aisle.inventoryPos(OUTPUT_RACK));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, COBBLESTONE),
                        (long) BATCH, "the cobblestone comes back out of the bay"))
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), BATCH,
                        "and an emptied bay forgets its type, so the dirt that waited is stored at last"))
                .thenExecuteAfter(ONE_TYPE_SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.storedKey().orElse(null), DIRT, "and it is now a dirt bay");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), 0L, "the input is empty");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Two store jobs for two item types, into an <b>empty</b> bay with a plain chest beside it: the bay takes one type
     * and the chest the other, and at no moment is a crane carrying a type towards a bay that is not already committed
     * to it.
     * <p>
     * <b>What this test can and cannot reach, stated rather than implied.</b> The gate behind it is
     * {@code WarehouseControllerBlockEntity#committedTo}, which reads the stock index <i>and</i> the reservation
     * ledger. The index half is what decides here and is what the assertions below really exercise. The reservation
     * half cannot be <b>created</b> end-to-end with one crane per controller: dispatch only plans while the dock
     * {@code canAcceptJob()} — idle, with an empty head — and the ledger is rebuilt from that same dock's current job
     * every interval, so there is no tick in which an empty bay carries a reservation and a second job is being
     * planned. It is a defensive read, and the invariant it defends is what is asserted on every tick instead: the
     * capacity reserved at the bay names <b>one</b> key at most, and no job ever targets the bay with a second type.
     * Were a future change ever to plan two types into one bay — a second crane on one controller, a queue of planned
     * jobs — this is the test that goes red.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayTwoJobsOneType(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        placeBay(helper, bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aisle.sideDirection(BAY_RACK));
        RackBayBlockEntity bay = bayAt(helper, bayPos);
        aisle.build(true);
        BlockPos chest = aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);

        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while two types are planned into one bay");
            assertOneTypeIsOnItsWay(helper, aisle, bay);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler buffer = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(buffer, COBBLESTONE.toStack(BATCH));
                    aisle.insertAll(buffer, DIRT.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                    ItemCensus.change(conserved, DIRT, BATCH);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, COBBLESTONE), 0L, "both types left");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), 0L, "the input, that is");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    // Which of the two the nearer bay took is the planner's business; that it took exactly one of them
                    // and the chest took the other is the claim.
                    ItemKey inTheBay = bay.storedKey().orElse(null);
                    if (inTheBay == null) {
                        helper.fail("the bay must have taken one of the two types", bayPos);
                        return;
                    }
                    ItemKey inTheChest = COBBLESTONE.equals(inTheBay) ? DIRT : COBBLESTONE;
                    helper.assertValueEqual(bay.storedCount(), BATCH, "the bay holds a whole batch of " + inTheBay);
                    helper.assertValueEqual(inventoryCount(helper, chest, inTheChest), (long) BATCH,
                            "and the chest holds the other type, which was never planned into the bay");
                    helper.assertValueEqual(inventoryCount(helper, chest, inTheBay), 0L,
                            "nothing of the bay's own type was split off into the chest");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Fails unless at most one item type is on its way into the bay — the invariant
     * {@code WarehouseControllerBlockEntity#committedTo} defends, read off the live ledger and the live crane.
     * <p>
     * Two reads, because they can disagree: the ledger's aggregate for the bay must be accounted for by a single key
     * ({@code reservedCapacity(L) == reservedCapacity(L, K)} is exactly the test the gate makes), and the crane's own
     * job must never carry a second type towards it.
     */
    private static void assertOneTypeIsOnItsWay(GameTestHelper helper, AisleFixture aisle, RackBayBlockEntity bay) {
        ReservationView<ItemKey, RackPosition> reserved = aisle.controllerIfPresent()
                .map(WarehouseControllerBlockEntity::reservations).orElse(null);
        if (reserved == null)
            return;
        long total = reserved.reservedCapacity(BAY_RACK);
        if (total > 0) {
            long forOneKey = Math.max(reserved.reservedCapacity(BAY_RACK, COBBLESTONE),
                    reserved.reservedCapacity(BAY_RACK, DIRT));
            helper.assertValueEqual(forOneKey, total,
                    "the capacity reserved at a one-type bay must be accounted for by a single item type");
        }
        aisle.dock().currentJob().filter(job -> BAY_RACK.equals(job.target())).ifPresent(job -> {
            Optional<ItemKey> stored = bay.storedKey();
            helper.assertTrue(stored.isEmpty() || stored.get().equals(job.key()),
                    "a job may never carry " + job.key() + " towards a bay holding " + stored.orElse(null));
        });
    }

    /**
     * The twin of {@code filtercoldcacheafterreload} for rack bays: a controller restored from a save honours the
     * store rules of bays it has <b>not read back yet</b>.
     * <p>
     * A bay carries its own rules, so after a load the controller's {@link AisleFilters} cache is empty while its
     * restored locations are still queued for background snapshots, and dispatch already runs in that window. A cache
     * miss that answered "no rule" would mean "accepts everything", and nothing is ever re-shuffled (ADR-021), so a
     * bay filled with the wrong item in that window is wrong for ever. Two kinds of rule are in that window at once,
     * and they are resolved by two different fields of the same one-shot read:
     * <ul>
     * <li>a bay that <b>holds</b> cobblestone must refuse dirt — the one-type rule, which the controller can only
     * answer from its restored stock index;</li>
     * <li>an <b>empty</b> bay dedicated to cobblestone by a store filter must refuse dirt too — the filter, read out
     * of the bay's own block entity.</li>
     * </ul>
     * It also pins the counter that goes with them: a one-type bay carrying no filter is <b>not</b> a filtered
     * location, so a wall of bays never inflates the number a controller's goggles print, while the one bay with a
     * real filter is counted again the moment the cache is warm.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayColdCacheAfterReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos holdingPos = aisle.rackPos(RELOAD_RACKS.getFirst());
        placeBay(helper, holdingPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(),
                aisle.sideDirection(RELOAD_RACKS.getFirst()));
        helper.assertTrue(capabilityAt(helper, holdingPos).insertItem(0, COBBLESTONE.toStack(PRELOADED), false)
                .isEmpty(), "the first bay is hand-filled with cobblestone");
        for (RackPosition rack : RELOAD_RACKS.subList(1, RELOAD_RACKS.size())) {
            BlockPos pos = aisle.rackPos(rack);
            placeBay(helper, pos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aisle.sideDirection(rack));
            helper.assertTrue(bayAt(helper, pos).setStoreFilter(COBBLESTONE.toStack()),
                    "every other bay is empty and dedicated to cobblestone");
        }
        aisle.build(true);
        aisle.input(INPUT_RACK);
        int dedicated = RELOAD_RACKS.size() - 1;

        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, PRELOADED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reloaded controller plans"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(RELOAD_RACKS.size(), 1, 0);
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), dedicated,
                            "every bay but the hand-filled one carries a filter; a one-type rule is not one");
                })
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenExecute(() -> {
                    // The dirt arrives and the controller is replaced by a copy loaded from its save in the same tick,
                    // so the very next dispatch plans with a cold store-rule cache.
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), DIRT.toStack(BATCH));
                    ItemCensus.change(conserved, DIRT, BATCH);
                    level.setBlockEntity(loadCopy(helper, controller, saved, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    for (RackPosition rack : RELOAD_RACKS) {
                        helper.assertValueEqual(aisle.controller().stockIndex().countAt(DIRT, rack), 0L,
                                "dirt reached the cobblestone bay at " + rack + " after a reload");
                        Optional<ItemKey> stored = bayAt(helper, aisle.rackPos(rack)).storedKey();
                        helper.assertTrue(rack.equals(RELOAD_RACKS.getFirst())
                                ? stored.filter(COBBLESTONE::equals).isPresent() : stored.isEmpty(),
                                "and the bay at " + rack + " holds what it held before, not " + stored.orElse(null));
                    }
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, DIRT), (long) BATCH,
                            "the input keeps the dirt instead");
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), dedicated,
                            "and the reloaded controller knows every real filter again");
                    aisle.assertIdleAndEmpty();
                    // And it is still a working warehouse: the item every rule allows is stored at once.
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(COBBLESTONE),
                        (long) (PRELOADED + BATCH), "the cobblestone every bay accepts is stored after the reload"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- the column rule (M28 step 5) ------------------------------------------------------------------------------

    /**
     * A bay may carry nothing stronger above it, refused when the block is placed and refused in <b>both</b>
     * directions (ADR-044). The rule is "the strength of a column never rises going upwards", so refusing only upwards
     * would let the illegal column be built from the top, and a player would meet the refusal three rows later instead
     * of on the first block.
     * <p>
     * The refusal is {@code getStateForPlacement} answering {@code null}, which is the call
     * {@code BlockItem.getPlacementState} makes: it passes {@code null} through, and {@code BlockItem.place} then
     * answers {@code FAIL} before {@code placeBlock} and before {@code itemstack.consume(1, player)}, so nothing is
     * placed and no item is used up. That method is called here directly rather than through a player's hand, so the
     * test states the one claim it makes and needs no inventory, no reach and no connection to exist.
     * <p>
     * It also pins the three things about the rule that are easy to get wrong:
     * <ul>
     * <li><b>"anywhere" is not "directly":</b> both walks read the whole column, so a bay is refused by a weak foot
     * three blocks down and by a strong bay three blocks up, not only by its immediate neighbour;</li>
     * <li><b>a gap is two racks:</b> each walk stops at the first block that is not a bay, so a brass bay on a roof
     * says nothing about a wooden one in the basement;</li>
     * <li><b>a column of one material is any height,</b> and weaker above is always allowed — which is what makes
     * upgrading a wall a rebuild from the bottom up.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayColumnRuleIsRefusedAtPlacementBothWays(GameTestHelper helper) {
        BlockState wood = WareworksBlocks.RACK_BAY_WOOD.getDefaultState();
        BlockState andesite = WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState();
        BlockState brass = WareworksBlocks.RACK_BAY_BRASS.getDefaultState();

        // One material, any height; and nothing stronger directly above.
        placeByHand(helper, PLAIN_COLUMN, wood);
        placeByHand(helper, PLAIN_COLUMN.above(), wood);
        assertPlacementRefused(helper, PLAIN_COLUMN.above(2), andesite, "andesite on top of wood");
        assertPlacementRefused(helper, PLAIN_COLUMN.above(2), brass, "brass on top of wood");
        placeByHand(helper, PLAIN_COLUMN.above(2), wood);

        // Weaker above is the whole point of the tiers, and the mirror of the rule refuses the weak foot underneath.
        placeByHand(helper, MIXED_COLUMN, brass);
        placeByHand(helper, MIXED_COLUMN.above(), wood);
        assertPlacementAllowed(helper, MIXED_COLUMN.above(2), wood, "wood on wood on brass");

        // "Anywhere below", built with a command so the column is already broken: the bay directly underneath is
        // strong enough and the one under that is not.
        helper.setBlock(BROKEN_FOOT, wood.setValue(RackBayBlock.FACING, Direction.NORTH));
        helper.setBlock(BROKEN_FOOT.above(), brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        assertPlacementRefused(helper, BROKEN_FOOT.above(2), andesite,
                "andesite over brass whose own foot is wooden");

        // "Anywhere above", the same column read from the other end: the bay directly above is weak enough and the one
        // above that is not. A brass bay there is allowed, and inherits the flag of the broken column above it.
        helper.setBlock(BROKEN_HEAD, wood.setValue(RackBayBlock.FACING, Direction.NORTH));
        helper.setBlock(BROKEN_HEAD.above(), brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        assertPlacementRefused(helper, BROKEN_HEAD.below(), andesite,
                "andesite under wood that already carries brass");
        BlockState allowed = assertPlacementAllowed(helper, BROKEN_HEAD.below(), brass,
                "brass under wood that carries brass");
        helper.assertTrue(allowed.getValue(RackBayBlock.OVERLOADED),
                "and it is flagged, because the column above it is giving way");

        // A gap is two racks: the walk stops at the first block that is not a bay.
        helper.setBlock(GAPPED_COLUMN.above(2), brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        BlockState underGap = assertPlacementAllowed(helper, GAPPED_COLUMN, wood, "wood two below a brass bay");
        helper.assertFalse(underGap.getValue(RackBayBlock.OVERLOADED), "and nothing is flagged across the gap");
        helper.succeed();
    }

    /**
     * {@code /setblock}, {@code /clone}, WorldEdit and structure placement all bypass placement, so a standing column
     * can break at any time. That is <b>reported, never fixed</b>: an overloaded bay keeps its items, keeps standing
     * and stops being a store target, and the flag follows every change to the column without a world search.
     * <p>
     * The case this test exists for is the one a naive implementation leaks: <b>a block below is broken</b>. In a
     * {@code wood / wood / brass} column the flag sits on the two <i>wooden</i> bays, and breaking the upper wooden
     * one — the brass bay's neighbour below — must clear the wooden bay underneath it. An implementation that
     * recomputed only when a bay was <i>placed</i>, or that walked upwards from the change, would leave that bay
     * refusing every store job for ever while its goggles claimed a stronger bay stood in empty space.
     * <p>
     * It also pins what must <b>not</b> happen to the brass bay above the break: nothing at all. It is not popped, it
     * does not fall, it keeps its load and its own flag stays false — because the flag only ever looks upwards, and
     * because {@code canSurvive} is deliberately never implemented. A bay that could be popped by a missing support
     * would turn a command into item loss.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayColumnRuleFollowsCommandsAndBreaks(GameTestHelper helper) {
        BlockState wood = WareworksBlocks.RACK_BAY_WOOD.getDefaultState();
        BlockState brass = WareworksBlocks.RACK_BAY_BRASS.getDefaultState();
        BlockPos lower = PLAIN_COLUMN;
        BlockPos upper = PLAIN_COLUMN.above();
        BlockPos top = PLAIN_COLUMN.above(2);

        placeByHand(helper, lower, wood);
        placeByHand(helper, upper, wood);
        for (BlockPos pos : List.of(lower, upper)) {
            capabilityAt(helper, pos).insertItem(0, COBBLESTONE.toStack(COLUMN_LOAD), false);
            assertOverloaded(helper, pos, false, "a legal wooden column");
        }
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, 2 * COLUMN_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the column is broken by a command");

        // A command puts brass on top of the wall. Every bay below it is flagged, in the same tick, because writing
        // the flag is itself a neighbour update for the bay underneath.
        helper.setBlock(top, brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        assertOverloaded(helper, upper, true, "the bay directly under the brass one");
        assertOverloaded(helper, lower, true, "and the one under that, through the closure");
        helper.assertValueEqual(bayAt(helper, lower).storedCount(), COLUMN_LOAD, "with every item still in it");
        helper.assertValueEqual(bayAt(helper, upper).storedCount(), COLUMN_LOAD, "and in the other one");
        assertOverloaded(helper, top, false, "the brass bay on top carries nothing stronger itself");

        // The case that leaks: break the brass bay's neighbour BELOW. The bay under the break has air above it now and
        // must stop refusing; the brass bay above it must be left completely alone.
        helper.setBlock(upper, Blocks.AIR);
        assertOverloaded(helper, lower, false, "the bay below a broken bay");
        helper.assertValueEqual(bayAt(helper, lower).storedCount(), COLUMN_LOAD, "which kept its load");
        helper.assertTrue(bayAt(helper, lower).acceptsStoring(), "and may be stored into again");
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(top)).getBlock() instanceof RackBayBlock,
                "the brass bay above the break still stands: nothing was popped");
        assertOverloaded(helper, top, false, "and its own flag never depended on what was below it");
        ItemCensus.assertEquals(helper, conserved, "after a bay was broken out of the middle of a column");

        // Broken again from the other end: a command puts the brass bay back directly on top, and breaking that brass
        // bay clears the column.
        helper.setBlock(upper, brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        assertOverloaded(helper, lower, true, "wood under brass again");
        helper.assertFalse(bayAt(helper, lower).acceptsStoring(), "so nothing is stored into it");
        helper.setBlock(upper, Blocks.AIR);
        assertOverloaded(helper, lower, false, "breaking the brass bay clears the bay under it");

        // Replaced rather than broken: a non-bay block ends the column just as air does.
        helper.setBlock(upper, brass.setValue(RackBayBlock.FACING, Direction.NORTH));
        assertOverloaded(helper, lower, true, "wood under brass, once more");
        helper.setBlock(upper, Blocks.STONE);
        assertOverloaded(helper, lower, false, "a column that ends in stone carries nothing");
        ItemCensus.assertEquals(helper, conserved, "after a column was broken and repaired by commands");
        removeBay(helper, lower);
        removeBay(helper, top);
        helper.succeed();
    }

    /**
     * A command can also build the illegal column <b>from the top down</b>, and then the bay being placed is the one
     * whose flag is wrong — not a neighbour of it. Nothing in the world will ever tell that bay about the stronger one
     * above it, because its flag only ever changes when the block above it does, and that block was there first.
     * <p>
     * It repairs itself on the tick after it arrives, and that one-tick delay is deliberate rather than tolerated:
     * {@code onPlace} runs inside {@code LevelChunk#setBlockState} before the block entity exists, and that method
     * writes the state it was called with into the block entity afterwards — so a block state written from
     * {@code onPlace} would be overwritten in the block entity while the chunk kept the new one, and
     * {@code getBlockState()} would lie to the job planner about the flag. Hence the assertion before the tick as well
     * as after it: the first is what a command really leaves behind, the second is the repair.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayCommandedUnderAStrongerOneFlagsItself(GameTestHelper helper) {
        BlockState wood = WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, Direction.NORTH);
        BlockState brass = WareworksBlocks.RACK_BAY_BRASS.getDefaultState()
                .setValue(RackBayBlock.FACING, Direction.NORTH);

        helper.setBlock(MIXED_COLUMN.above(2), brass);
        helper.setBlock(MIXED_COLUMN.above(), wood);
        helper.setBlock(MIXED_COLUMN, wood);
        helper.assertFalse(
                helper.getLevel().getBlockState(helper.absolutePos(MIXED_COLUMN.above()))
                        .getValue(RackBayBlock.OVERLOADED),
                "a command writes the block state it was given, flag and all");

        helper.startSequence()
                .thenExecuteAfter(REPAIR_TICKS, () -> {
                    assertOverloaded(helper, MIXED_COLUMN.above(), true, "the bay a command put under the brass one");
                    assertOverloaded(helper, MIXED_COLUMN, true,
                            "and the one under that, reached by the first one's own repair");
                    assertOverloaded(helper, MIXED_COLUMN.above(2), false, "while the brass bay carries nothing");
                })
                .thenSucceed();
    }

    /**
     * What an overloaded bay means for a <b>running warehouse</b>, which is the half no block-state assertion can
     * reach: it is not offered store jobs, for a reason no filter could express, and it <b>stays retrievable</b> —
     * because a store rule never restricts retrieval (ADR-021) and a player must always be able to get their
     * cobblestone back out.
     * <p>
     * The bay carries storage priority 9, the highest there is, so the test also pins the order of the two: the gate
     * decides before the priority, and a priority can never lift a bay the column rule refuses above a plain chest.
     * And the moment a command takes the stronger bay away, the same bay wins again on the same priority — the rule is
     * reported, never baked in.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = OVERLOADED_TIMEOUT_TICKS)
    public static void anOverloadedBayStoresNothingButStaysRetrievable(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        BlockPos abovePos = aisle.rackPos(BAY_ABOVE_RACK);
        placeBay(helper, bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aisle.sideDirection(BAY_RACK));
        RackBayBlockEntity bay = bayAt(helper, bayPos);
        helper.assertTrue(capabilityAt(helper, bayPos).insertItem(0, COBBLESTONE.toStack(PRELOADED), false).isEmpty(),
                "the bay is filled by hand before the warehouse exists");
        bay.setStorePriority(TOP_PRIORITY);
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);

        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, PRELOADED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a column rule holds a bay shut"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    // A command drops a brass bay on top of the wooden one. It is a rack position of this aisle too,
                    // so it gets a filter for an item this test never uses: the chest is then the only other target.
                    helper.setBlock(abovePos, WareworksBlocks.RACK_BAY_BRASS.getDefaultState()
                            .setValue(RackBayBlock.FACING, aisle.sideDirection(BAY_ABOVE_RACK)));
                    bayAt(helper, abovePos).setStoreFilter(DIRT.toStack());
                    assertOverloaded(helper, bayPos, true, "the bay under the brass one");
                    helper.assertFalse(bay.acceptsStoring(), "and it accepts no storing");
                })
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.requestAt(OUTPUT_RACK, COBBLESTONE.toStack(), REQUEST_AMOUNT,
                            aisle.inventoryPos(OUTPUT_RACK));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, COBBLESTONE),
                        (long) REQUEST_AMOUNT, "an overloaded bay is still retrievable"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.storedCount(), PRELOADED - REQUEST_AMOUNT,
                            "exactly what was requested left it");
                    aisle.assertIdleAndEmpty();
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, COBBLESTONE), (long) BATCH,
                        "and a plain chest took the delivery instead"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.storedCount(), PRELOADED - REQUEST_AMOUNT,
                            "not one item entered the overloaded bay, whatever its priority says");
                    aisle.assertIdleAndEmpty();
                    // The rule is reported, not baked in: take the brass bay away and the same bay wins again.
                    helper.setBlock(abovePos, Blocks.AIR);
                    assertOverloaded(helper, bayPos, false, "the bay with the brass one gone");
                    helper.assertTrue(bay.acceptsStoring(), "and it accepts storing again");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(BATCH));
                    ItemCensus.change(conserved, COBBLESTONE, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(),
                        PRELOADED - REQUEST_AMOUNT + BATCH, "and the delivery goes back into the bay"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- hands, goggles and sync (M28 step 6) ----------------------------------------------------------------------

    /**
     * The gesture the issue settled: <b>a plain right-click moves one item, Shift moves one stack, and that holds in
     * both directions</b> (ADR-045). An item in hand puts in, an empty hand takes out, and there is deliberately no
     * "take everything".
     * <p>
     * It also pins everything the gesture must <b>not</b> take, because each of those is a way to lose something a
     * player was holding: a wrench (which turns the bay and decides which face is the aisle face), Create's clipboard
     * (which dedicates a whole rack wall in one gesture), the Mechanical Arm item (which aims an arm), an item the bay
     * refuses, and a <b>renamed</b> item — the aisle-naming gesture a player learned on a warehouse interface, which
     * here collides head-on with "a right-click puts the item in" and is therefore answered rather than swallowed. A
     * {@code FakePlayer} is refused in both directions: a Deployer has the item capability for exactly this purpose,
     * and the aisle face is the one face in-aisle automation can reach.
     * <p>
     * Conservation is counted by hand, because the item census cannot see a player's inventory: every assertion
     * compares the bay plus the player's own slots against one number that must never change.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void bayHandGestures(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        NamingClick.Teller player = NamingClick.player(helper);
        player.getInventory().clearContent();

        // What the gesture must not take, asked first and on an EMPTY, UNFILTERED bay — the one state in which the
        // bay would accept anything at all, so a pass-through that happens for the wrong reason cannot hide behind
        // "the bay was committed to something else anyway". Each of these would otherwise put an item a player was
        // holding into the bay, and each of them has a meaning of its own on this block.
        assertPassesThrough(helper, bay, player, AllItems.WRENCH.asStack(), "a wrench, which turns the bay");
        assertPassesThrough(helper, bay, player, AllBlocks.CLIPBOARD.asStack(),
                "a clipboard, which copies the filter and the priority onto a whole rack wall");
        assertPassesThrough(helper, bay, player, AllBlocks.MECHANICAL_ARM.asStack(),
                "the Mechanical Arm item, which places an arm");
        // The item whose own click this block exists for: a wall grows by clicking the side of a bay that is standing
        // (RackBayBlock#placementFacing). An empty, unfiltered bay is the state every freshly placed bay is in and it
        // accepts anything exactly once, so without this pass-through the next bay was stored instead of placed and a
        // free-standing wall's second row could not be started at all (M28 review).
        for (BlockState tier : List.of(WareworksBlocks.RACK_BAY_WOOD.getDefaultState(),
                WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState(),
                WareworksBlocks.RACK_BAY_BRASS.getDefaultState()))
            assertPassesThrough(helper, bay, player, new ItemStack(tier.getBlock()),
                    "a " + tier.getBlock() + ", which builds the rack wall");

        // The aisle naming a player learned on a warehouse interface: answered in one line, and the renamed item they
        // were holding out at the bay stays in their hand instead of disappearing into it.
        NamingClick.Teller namer = NamingClick.player(helper);
        NamingClick.use(helper, namer, LONE_BAY, Direction.SOUTH, NamingClick.renamed(Items.COBBLESTONE, "Ores"));
        NamingClick.assertTold(helper, namer, "a renamed item on a rack bay", WareworksLang.BAY_NO_NAMING);
        helper.assertValueEqual(bay.storedCount(), 0, "a renamed item is never stored by hand, empty bay or not");
        helper.assertTrue(bay.storedKey().isEmpty(), "so the bay is not quietly dedicated to it either");

        // Putting in: a plain click moves one item, Shift moves what is left of the stack.
        ItemStack held = COBBLESTONE.toStack(STACK);
        helper.assertValueEqual(click(helper, player, LONE_BAY, held, false), ItemInteractionResult.CONSUME,
                "a plain click with an item the bay takes is the bay's own gesture");
        helper.assertValueEqual(bay.storedCount(), RackBayGestures.PLAIN_INSERT_ITEMS,
                "a plain click puts one item in");
        helper.assertValueEqual(held.getCount(), STACK - RackBayGestures.PLAIN_INSERT_ITEMS,
                "and takes exactly that off the held stack");
        assertNothingLost(helper, bay, player, STACK, "after a plain insert");

        helper.assertValueEqual(click(helper, player, LONE_BAY, held, true), ItemInteractionResult.CONSUME,
                "and so is a Shift click");
        helper.assertValueEqual(bay.storedCount(), STACK, "Shift puts a whole stack in");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "and empties a hand that held exactly one");
        assertNothingLost(helper, bay, player, STACK, "after a Shift insert");

        // Taking out: the same two amounts, with an empty hand. The selected slot moves between the two clicks so the
        // hand really is empty both times while what was taken stays in the inventory.
        helper.assertValueEqual(click(helper, player, LONE_BAY, ItemStack.EMPTY, false),
                ItemInteractionResult.CONSUME, "an empty hand is the other half of the gesture");
        helper.assertValueEqual(bay.storedCount(), STACK - RackBayGestures.PLAIN_TAKE_ITEMS,
                "a plain click takes one item out");
        assertNothingLost(helper, bay, player, STACK, "after a plain take");

        player.getInventory().selected = 1;
        helper.assertValueEqual(click(helper, player, LONE_BAY, ItemStack.EMPTY, true),
                ItemInteractionResult.CONSUME, "Shift takes a stack");
        helper.assertValueEqual(bay.storedCount(), 0, "which was the rest of this one");
        helper.assertTrue(bay.storedKey().isEmpty(), "so the bay forgot its type, as it does for any emptying");
        assertNothingLost(helper, bay, player, STACK, "after a Shift take");
        helper.assertValueEqual(click(helper, player, LONE_BAY, ItemStack.EMPTY, true),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "an empty bay has nothing to give");

        // There is no "take everything": Shift is the largest amount one click can move, whatever is in the bay. The
        // stack the player is holding goes back in with the gesture itself, and two more join it directly.
        player.getInventory().selected = 0;
        click(helper, player, LONE_BAY, player.getInventory().getItem(0), true);
        helper.assertValueEqual(bay.storedCount(), STACK, "the stack the player took goes back in");
        bay.insert(COBBLESTONE.toStack(2 * STACK), false);
        helper.assertValueEqual(bay.storedCount(), 3 * STACK, "so three stacks are in the bay");
        click(helper, player, LONE_BAY, ItemStack.EMPTY, true);
        helper.assertValueEqual(bay.storedCount(), 2 * STACK, "and the largest gesture there is moves one stack");
        assertNothingLost(helper, bay, player, 3 * STACK, "after the largest take there is");

        // An item the bay itself refuses is passed on too, so that a click a bay cannot use behaves exactly as it did
        // before the block existed — which is also what keeps sneak-placing a block against a bay's face working.
        assertPassesThrough(helper, bay, player, DIRT.toStack(STACK), "an item this bay is committed against");
        bay.setStoreFilter(DIRT.toStack());
        assertPassesThrough(helper, bay, player, COBBLESTONE.toStack(STACK), "an item the store filter refuses");
        bay.setStoreFilter(ItemStack.EMPTY);

        // No automation reaches the contents by right-clicking, in either direction: a Deployer has the item
        // capability for exactly that purpose, and the aisle face is the one face in-aisle automation can reach.
        int loaded = bay.storedCount();
        FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
        helper.assertValueEqual(click(helper, deployer, LONE_BAY, COBBLESTONE.toStack(STACK), false),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "a deployer may not fill a rack wall");
        deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertValueEqual(click(helper, deployer, LONE_BAY, ItemStack.EMPTY, true),
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "nor empty one item at a time");
        helper.assertValueEqual(bay.storedCount(), loaded, "and nothing moved either way");

        // The half of the Shift gesture no click can reach: vanilla drops a sneaking interaction before the block
        // whenever a hand holds something, so without this hook "Shift puts a stack in" would never run at all.
        player.setItemInHand(InteractionHand.MAIN_HAND, COBBLESTONE.toStack(STACK));
        helper.assertTrue(forcesUse(helper, player, LONE_BAY, true), "a sneaking click with an item is forced");
        helper.assertFalse(forcesUse(helper, player, LONE_BAY, false), "a plain one needs no help");
        helper.assertFalse(forcesUse(helper, player, LONE_BAY.above(), true), "and nothing but a bay is forced");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(forcesUse(helper, player, LONE_BAY, true),
                "an empty hand too, because anything in the offhand would otherwise swallow the click");
        for (ItemStack passing : List.of(AllItems.WRENCH.asStack(), AllBlocks.CLIPBOARD.asStack(),
                AllBlocks.MECHANICAL_ARM.asStack(), WareworksBlocks.RACK_BAY_WOOD.asStack(),
                WareworksBlocks.RACK_BAY_BRASS.asStack(), NamingClick.renamed(Items.COBBLESTONE, "Ores"))) {
            player.setItemInHand(InteractionHand.MAIN_HAND, passing);
            helper.assertFalse(forcesUse(helper, player, LONE_BAY, true),
                    "a sneaking click keeps its own meaning for " + passing.getItem());
        }
        deployer.setItemInHand(InteractionHand.MAIN_HAND, COBBLESTONE.toStack(STACK));
        helper.assertFalse(forcesUse(helper, deployer, LONE_BAY, true), "and no automation is helped past it");

        removeBay(helper, LONE_BAY);
        helper.succeed();
    }

    /**
     * What a bay puts on the wire, and how little: the item's registry <b>id</b>, the count and the goggle state, and
     * never an {@link ItemKey}.
     * <p>
     * A block entity's update tag is part of <b>every</b> chunk packet, read by clients with a 2 MB NBT quota, and a
     * rack wall is hundreds of these blocks in one of them — the first version of the warehouse interface synced full
     * item keys, and one shulker box of written books could disconnect everyone loading the chunk (§3.1.1). A bay is
     * the first block of this mod that syncs its <b>contents</b> at all, so it gets its own budget, asserted in the
     * state almost every bay is in and again in the worst case a warehouse can produce.
     * <p>
     * The visible consequence is asserted rather than left to be discovered: a <b>renamed</b> item in a bay reads as
     * its plain self on the client.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void baySyncIsBounded(GameTestHelper helper) {
        placeBay(helper, LONE_BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), Direction.NORTH);
        RackBayBlockEntity bay = bayAt(helper, LONE_BAY);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        // A renamed item, which is what would drag item data onto the wire if anything did.
        ItemStack renamed = NamingClick.renamed(Items.COBBLESTONE, "x".repeat(LONG_NAME_LENGTH), STACK);
        helper.assertTrue(bay.insert(renamed, false).isEmpty(), "the bay takes a renamed item like any other");
        CompoundTag named = bay.getUpdateTag(registries);
        helper.assertValueEqual(named.getString(RackBayHandler.STORED_ITEM_TAG), "minecraft:cobblestone",
                "the wire carries the item's id");
        helper.assertFalse(named.contains(RackBayHandler.STORED_TAG), "and never an item key");
        helper.assertFalse(named.toString().contains("x".repeat(LONG_NAME_LENGTH)),
                "so no item data of any kind reaches a chunk packet");
        RackBayBlockEntity plainOnTheClient = detachedCopy(helper, bay);
        plainOnTheClient.handleUpdateTag(named, registries);
        helper.assertValueEqual(plainOnTheClient.storedKey().orElse(null), COBBLESTONE,
                "a renamed item reads as its plain self on the client, which is the price of the bound");
        helper.assertValueEqual(plainOnTheClient.storedCount(), STACK, "with its full count");

        // The state almost every bay of a rack wall is in: a full brass load, no filter, no reservation.
        bay.clearContent();
        int load = BayTier.BRASS.defaultStacks() * STACK;
        helper.assertTrue(bay.insert(COBBLESTONE.toStack(load), false).isEmpty(), "and its whole brass load");
        CompoundTag plain = bay.getUpdateTag(registries);
        helper.assertValueEqual(plain.getInt(RackBayHandler.COUNT_TAG), load, "the count crosses as one int");
        helper.assertFalse(plain.contains("Filter"), "an unfiltered bay must not sync a filter slot");
        helper.assertFalse(plain.contains(RackBayBlockEntity.RESERVATIONS_TAG), "nor an empty reservation summary");
        helper.assertTrue(plain.contains(RackBayBlockEntity.ASSIGNMENT_TAG),
                "but always its address, or a bay whose warehouse was broken could never say so again");
        int plainSize = plain.sizeInBytes();
        helper.assertTrue(plainSize < MAX_RACK_BAY_SYNC_BYTES,
                "an ordinary bay's update tag must stay small, but has " + plainSize + " bytes");

        // And the worst case: a filter, the highest priority, the widest address with the longest aisle name, and the
        // largest reservation summary a location with a running job can carry.
        bay.setStoreFilter(COBBLESTONE.toStack());
        bay.setStorePriority(TOP_PRIORITY);
        LocationReservationSummary reserved = new LocationReservationSummary(
                List.of(new KeyCount<>(Items.POLISHED_BLACKSTONE_PRESSURE_PLATE, Long.MAX_VALUE),
                        new KeyCount<>(Items.WAXED_WEATHERED_CUT_COPPER_STAIRS, Long.MAX_VALUE)),
                List.of(new KeyCount<>(Items.LIGHT_BLUE_GLAZED_TERRACOTTA, Long.MAX_VALUE),
                        new KeyCount<>(Items.SKELETON_HORSE_SPAWN_EGG, Long.MAX_VALUE)));
        CompoundTag worst = bay.getUpdateTag(registries);
        CompoundTag reservationsTag = new CompoundTag();
        reserved.write(reservationsTag);
        worst.put(RackBayBlockEntity.RESERVATIONS_TAG, reservationsTag);
        CompoundTag assignmentTag = new CompoundTag();
        AisleAssignment.assigned(StorageAddress.parse(WIDEST_ADDRESS))
                .withAisleName(Optional.of(LONGEST_AISLE_NAME)).write(assignmentTag);
        worst.put(RackBayBlockEntity.ASSIGNMENT_TAG, assignmentTag);
        worst.putBoolean(RackBayBlockEntity.FILTER_SHADOWED_TAG, true);
        int worstSize = worst.sizeInBytes();
        Wareworks.LOGGER.debug("Rack bay update tag: {} bytes, in the worst case {} bytes", plainSize, worstSize);
        helper.assertTrue(worstSize < MAX_RESERVED_RACK_BAY_SYNC_BYTES,
                "and the worst case must stay small, but has " + worstSize + " bytes");

        RackBayBlockEntity client = detachedCopy(helper, bay);
        client.handleUpdateTag(worst, registries);
        helper.assertValueEqual(client.storedCount(), load, "the client knows what is in the bay");
        helper.assertValueEqual(client.storePriority(), TOP_PRIORITY, "and its priority");
        helper.assertTrue(COBBLESTONE.matches(client.storeFilter()), "and its filter");
        helper.assertValueEqual(client.aisleAssignment().address().map(StorageAddress::format).orElse(null),
                WIDEST_ADDRESS, "and its address");
        helper.assertValueEqual(client.aisleAssignment().aisleName(), Optional.of(LONGEST_AISLE_NAME),
                "with the name of its aisle");
        helper.assertValueEqual(client.reservationSummary(), reserved, "and what is reserved at it");
        helper.assertTrue(client.isFilterShadowed(), "and that its own store settings are never asked");

        // Nothing a server sends can make a client draw something impossible.
        RackBayBlockEntity nonsense = detachedCopy(helper, bay);
        CompoundTag crafted = new CompoundTag();
        crafted.putString(RackBayHandler.STORED_ITEM_TAG, "nosuchmod:nosuchitem");
        crafted.putInt(RackBayHandler.COUNT_TAG, Integer.MAX_VALUE);
        nonsense.handleUpdateTag(crafted, registries);
        helper.assertTrue(nonsense.storedKey().isEmpty(), "an item the client does not have reads as empty");
        crafted.putString(RackBayHandler.STORED_ITEM_TAG, "minecraft:cobblestone");
        nonsense.handleUpdateTag(crafted, registries);
        helper.assertValueEqual(nonsense.storedCount(), BayTier.MAX_CAPACITY_ITEMS,
                "and an impossible count is bounded, not believed");

        removeBay(helper, LONE_BAY); // 65 536 items are not something to hand to the test teardown
        helper.succeed();
    }

    /**
     * The goggle state of a bay <b>follows the warehouse around it</b>, and reaches the client that draws it: its
     * address while it is a storage location, "misaligned" when it is turned the wrong way, and "not part of an aisle"
     * once the warehouse is gone — which for a rack bay is the quiet line that says it still works by hand, not a
     * fault.
     * <p>
     * The lines themselves cannot be built here: {@code LangBuilder#forGoggles} measures the client font, so
     * {@code addToGoggleTooltip} belongs to a client run. What a server test can pin is every piece of state those
     * lines read, and that it crosses the wire.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bayGoggleStateFollowsTheWarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        Direction aligned = aisle.sideDirection(BAY_RACK);
        placeBay(helper, bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), aligned);
        RackBayBlockEntity bay = bayAt(helper, bayPos);
        bay.insert(COBBLESTONE.toStack(PRELOADED), false);
        aisle.build(false);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    bay.onGoggleObserved();
                    helper.assertValueEqual(bay.aisleAssignment().state(), AisleAssignment.State.ASSIGNED,
                            "a bay a crane can reach has an address");
                    RackBayBlockEntity client = detachedCopy(helper, bay);
                    client.handleUpdateTag(bay.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.aisleAssignment(), bay.aisleAssignment(),
                            "which reaches the client that draws it");
                    helper.assertValueEqual(client.storedCount(), PRELOADED, "together with the contents");
                })
                .thenExecute(() -> {
                    // Turned away from the aisle. The block entity keeps its contents (the block is the same one), so
                    // this is the misaligned hint and not a removal.
                    helper.setBlock(bayPos, helper.getBlockState(bayPos).setValue(RackBayBlock.FACING,
                            aligned.getClockWise()));
                })
                .thenWaitUntil(() -> {
                    bayAt(helper, bayPos).onGoggleObserved();
                    helper.assertValueEqual(bayAt(helper, bayPos).aisleAssignment().state(),
                            AisleAssignment.State.MISALIGNED, "a bay turned away from the aisle says so");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(bayAt(helper, bayPos).storedCount(), PRELOADED,
                            "and keeps every item while it says it");
                    helper.setBlock(bayPos, helper.getBlockState(bayPos).setValue(RackBayBlock.FACING, aligned));
                    aisle.breakBlock(aisle.controllerPos());
                })
                .thenWaitUntil(() -> {
                    bayAt(helper, bayPos).onGoggleObserved();
                    helper.assertValueEqual(bayAt(helper, bayPos).aisleAssignment(), AisleAssignment.NONE,
                            "and a bay no warehouse serves reports exactly that, which is no fault of its own");
                })
                .thenExecute(() -> {
                    RackBayBlockEntity lone = bayAt(helper, bayPos);
                    helper.assertValueEqual(lone.storedCount(), PRELOADED, "with its load untouched");
                    helper.assertTrue(lone.insert(COBBLESTONE.toStack(1), false).isEmpty(),
                            "and it still works by hand");
                    removeBay(helper, bayPos);
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * A rack bay at a test-relative position, facing {@code facing} (i.e. into the rack depth). A bay that already
     * stands there is <b>emptied</b> first, the way {@code /setblock} does, so replacing one in a test never spills a
     * few thousand item entities into the world.
     */
    private static void placeBay(GameTestHelper helper, BlockPos pos, BlockState bay, Direction facing) {
        removeBay(helper, pos);
        helper.setBlock(pos, bay.setValue(RackBayBlock.FACING, facing));
    }

    /** Empties a bay standing at {@code pos} and takes it away, leaving nothing behind. */
    private static void removeBay(GameTestHelper helper, BlockPos pos) {
        Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        helper.setBlock(pos, Blocks.AIR);
    }

    private static RackBayBlockEntity bayAt(GameTestHelper helper, BlockPos pos) {
        RackBayBlockEntity be = WareworksBlockEntityTypes.RACK_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing rack bay block entity", pos);
        return be;
    }

    /** The bay's item handler as any machine sees it: through {@code Capabilities.ItemHandler.BLOCK}. */
    private static IItemHandler capabilityAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null)
            helper.fail("the rack bay exposes no item handler", pos);
        return handler;
    }

    /** A fresh bay loaded from {@code tag} and put into the world, standing in for a reload. */
    private static RackBayBlockEntity loadCopy(GameTestHelper helper, RackBayBlockEntity live, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof RackBayBlockEntity copy)) {
            helper.fail("a saved rack bay must load again as one", live.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        helper.getLevel().setBlockEntity(copy);
        return copy;
    }

    /** Puts {@code tag} into the world as a loaded bay, the way a world load would. */
    private static RackBayBlockEntity readBack(GameTestHelper helper, RackBayBlockEntity live, CompoundTag tag) {
        CompoundTag full = tag.copy();
        full.putString("id", "wareworks:rack_bay");
        full.putInt("x", live.getBlockPos().getX());
        full.putInt("y", live.getBlockPos().getY());
        full.putInt("z", live.getBlockPos().getZ());
        return loadCopy(helper, live, full);
    }

    /**
     * A real belt run from {@code start} to {@code end} with a creative motor on the pulley beside {@code start}.
     * <p>
     * The movement direction is not assumed: a belt's direction follows the <b>sign</b> of its rotation and the axis it
     * lies on ({@code BeltBlockEntity#getMovementFacing}), so the motor's speed is flipped until the run really points
     * at the block it is meant to hand over to. That is checked rather than reasoned about because a belt aimed the
     * wrong way would simply never reach the bay, and the test would time out saying nothing.
     */
    private static void buildBelt(GameTestHelper helper, BlockPos start, BlockPos end) {
        ServerLevel level = helper.getLevel();
        BeltConnectorItem.createBelts(level, helper.absolutePos(start), helper.absolutePos(end));
        for (BlockPos pos : List.of(start, end))
            helper.assertTrue(AllBlocks.BELT.has(level.getBlockState(helper.absolutePos(pos))),
                    "a belt must stand at " + pos);
        helper.setBlock(BELT_MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, helper.absolutePos(BELT_MOTOR));
        if (motor == null) {
            helper.fail("the creative motor driving the belt is missing", BELT_MOTOR);
            return;
        }
        motor.generatedSpeed.setValue(TEST_RPM);
    }

    /** Flips the belt's motor unless the run already moves towards {@code wanted}. */
    private static void aimBelt(GameTestHelper helper, Direction wanted) {
        if (beltAt(helper, BELT_START).getMovementFacing() == wanted)
            return;
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(),
                helper.absolutePos(BELT_MOTOR));
        if (motor == null) {
            helper.fail("the creative motor driving the belt is missing", BELT_MOTOR);
            return;
        }
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static BeltBlockEntity beltAt(GameTestHelper helper, BlockPos pos) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (belt == null) {
            helper.fail("missing belt", pos);
            throw new IllegalStateException("unreachable");
        }
        return belt;
    }

    /** Puts {@code stack} on the belt the way a funnel, a tunnel or another belt does: from above, through its own
     * {@code DirectBeltInputBehaviour}. */
    private static void insertOntoBelt(GameTestHelper helper, BlockPos segment, ItemStack stack) {
        DirectBeltInputBehaviour input = BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(segment),
                DirectBeltInputBehaviour.TYPE);
        if (input == null) {
            helper.fail("a belt must take items from above", segment);
            return;
        }
        helper.assertTrue(input.canInsertFromSide(Direction.UP), "a turning belt takes items from above");
        ItemStack rest = input.handleInsertion(new TransportedItemStack(stack), Direction.UP, false);
        helper.assertTrue(rest.isEmpty(), "the belt took the whole stack, but left " + rest);
    }

    /** Items riding the belt run that {@code segment} belongs to. */
    private static long beltItems(GameTestHelper helper, BlockPos segment) {
        BeltBlockEntity controller = beltAt(helper, segment).getControllerBE();
        if (controller == null || controller.getInventory() == null)
            return 0L;
        long items = 0;
        for (TransportedItemStack transported : controller.getInventory().getTransportedItems())
            items += transported.stack.getCount();
        return items;
    }

    /**
     * An item entity dropped into the block at {@code pos} with <b>no</b> sideways shove: {@code ItemEntity}'s short
     * constructor gives a drop a random one, which can carry it out of a funnel's own block and makes such a test a
     * coin toss.
     */
    private static void drop(GameTestHelper helper, BlockPos pos, ItemStack stack) {
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(pos)).add(0, DROP_HEIGHT, 0);
        ItemEntity dropped = new ItemEntity(helper.getLevel(), spot.x, spot.y, spot.z, stack, 0.0, 0.0, 0.0);
        helper.assertTrue(helper.getLevel().addFreshEntity(dropped), "the item entity is in the world");
    }

    /** Items of {@code key} lying in the test area as item entities. */
    private static long itemEntityCount(GameTestHelper helper, ItemKey key) {
        long total = 0;
        for (ItemEntity entity : helper.getLevel().getEntities(EntityType.ITEM,
                helper.getBounds().inflate(DROP_RADIUS), entity -> key.matches(entity.getItem())))
            total += entity.getItem().getCount();
        return total;
    }

    /** A fresh block entity of {@code live}'s kind loaded from {@code tag}, standing in for a world load. */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one", live.getBlockPos());
            return live;
        }
        return type.cast(loaded);
    }

    private static HopperBlockEntity hopperAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof HopperBlockEntity hopper)) {
            helper.fail("missing hopper", pos);
            throw new IllegalStateException("unreachable");
        }
        return hopper;
    }

    /** Items of {@code key} in the inventory at a test-relative position, read as a machine reads it. */
    private static long inventoryCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    /**
     * Places {@code bay} at {@code pos} the way a player does: through {@code getStateForPlacement}, so the column
     * rule really runs and the bay arrives with the flag a placement gives it.
     */
    private static void placeByHand(GameTestHelper helper, BlockPos pos, BlockState bay) {
        helper.setBlock(pos, assertPlacementAllowed(helper, pos, bay, "a legal placement at " + pos));
    }

    /** Fails unless the column rule refuses {@code bay} at {@code pos}, with the one action-bar line and no block. */
    private static void assertPlacementRefused(GameTestHelper helper, BlockPos pos, BlockState bay, String what) {
        NamingClick.Teller player = NamingClick.player(helper);
        helper.assertTrue(placementState(helper, player, pos, bay) == null, "the column must refuse " + what);
        NamingClick.assertTold(helper, player, what, WareworksLang.BAY_COLUMN_REFUSED);
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
     * The state {@code bay} would be placed in at the free position {@code pos}, or {@code null} when the block
     * refuses the placement — the very call {@code BlockItem.getPlacementState} makes, which turns a {@code null} into
     * the {@code FAIL} that places no block and consumes no item. It is called directly rather than through a
     * player's hand, so the refusal is read where it is decided.
     * <p>
     * The hit result points at the free position itself, which air answers {@code canBeReplaced} for, so the context's
     * clicked position is that position — asserted, because a context that pointed one block away would make every
     * assertion built on it meaningless.
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
     * Fails unless the bay at {@code pos} carries exactly {@code expected} as its column-rule flag — in the block
     * state, in its block entity and in the answer the job planner reads. All three, because the block state and the
     * block entity's cached copy of it are two different things, and a flag that only the chunk knows about would be
     * invisible to everything that matters.
     */
    private static void assertOverloaded(GameTestHelper helper, BlockPos pos, boolean expected, String what) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(pos));
        helper.assertTrue(state.getBlock() instanceof RackBayBlock, "a rack bay must stand at " + pos + ": " + what);
        helper.assertValueEqual(state.getValue(RackBayBlock.OVERLOADED), expected, "block state: " + what);
        RackBayBlockEntity bay = bayAt(helper, pos);
        helper.assertValueEqual(bay.overloaded(), expected, "block entity: " + what);
        helper.assertValueEqual(bay.acceptsStoring(), !expected, "accepts storing: " + what);
    }

    /**
     * A right-click on the bay at {@code pos}, sneaking or not, answering what the <b>block</b> did with it — which is
     * what {@code GameTestHelper#useBlock} throws away.
     * <p>
     * The hit point is off centre, in the corner of the face, which is where a player has to click for the block to
     * see the click at all: Create cancels a plain right-click that hits the value box's own 4 px sphere before the
     * block state is asked. That handler is not involved here either way, so the offset is here to describe the real
     * gesture rather than to make it work.
     */
    private static ItemInteractionResult click(GameTestHelper helper, Player player, BlockPos pos, ItemStack held,
                                               boolean sneaking) {
        player.setShiftKeyDown(sneaking);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0.3, -0.3, -0.5), Direction.NORTH,
                absolute, false);
        return helper.getLevel().getBlockState(absolute).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND),
                helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    /** Whether a sneaking right-click at {@code pos} has to be handed to the block ({@link RackBayGestures}). */
    private static boolean forcesUse(GameTestHelper helper, Player player, BlockPos pos, boolean sneaking) {
        player.setShiftKeyDown(sneaking);
        return RackBayGestures.forcesBlockUse(player, helper.getLevel(), helper.absolutePos(pos),
                InteractionHand.MAIN_HAND);
    }

    /**
     * Fails unless a right-click with {@code held} is passed straight on, in both postures, and moves nothing: the
     * item keeps whatever meaning it had before this block existed.
     */
    private static void assertPassesThrough(GameTestHelper helper, RackBayBlockEntity bay, Player player,
                                            ItemStack held, String what) {
        int before = bay.storedCount();
        for (boolean sneaking : new boolean[] { false, true })
            helper.assertValueEqual(click(helper, player, LONE_BAY, held.copy(), sneaking),
                    ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "a bay passes on " + what);
        helper.assertValueEqual(bay.storedCount(), before, "and stores nothing of it: " + what);
    }

    /**
     * Fails unless the bay and the player's own slots together still hold every cobblestone this test started with.
     * The item census cannot see a player's inventory, and a hand gesture is exactly a transfer between the two, so
     * this is the conservation assertion for it.
     */
    private static void assertNothingLost(GameTestHelper helper, RackBayBlockEntity bay, Player player, int expected,
                                          String when) {
        long total = bay.storedKey().filter(COBBLESTONE::equals).map(key -> (long) bay.storedCount()).orElse(0L);
        for (ItemStack stack : player.getInventory().items)
            if (COBBLESTONE.matches(stack))
                total += stack.getCount();
        helper.assertValueEqual(total, (long) expected, "every item is still in the bay or in the hand " + when);
    }

    /** A detached block entity, standing in for the client's copy of this bay. */
    private static RackBayBlockEntity detachedCopy(GameTestHelper helper, RackBayBlockEntity bay) {
        RackBayBlockEntity copy = WareworksBlockEntityTypes.RACK_BAY.create(bay.getBlockPos(), bay.getBlockState());
        if (copy == null) {
            helper.fail("could not create a detached rack bay block entity", bay.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return copy;
    }

    /** A Create list filter (whitelist) holding {@code items}. */
    private static ItemStack listFilter(ItemStack... items) {
        ItemStack filter = AllItems.FILTER.asStack();
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(items)));
        return filter;
    }
}
