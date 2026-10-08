package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.crane.head.InventoryGrabber;
import dev.wareworks.content.crane.head.TransferContext;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the <b>container exchange</b>, the crane primitive at which a handling head gives up one item and
 * receives a different one at the same stop ({@code docs/stacker-crane.md} §6, M30, issue #21, D1): a filled container
 * goes into a fluid bay's tank and an empty one comes back out as ordinary stock.
 * <p>
 * This holder covers the primitive only — the {@link TransferContext} and the {@link InventoryGrabber}. There is no
 * job, no planner and no state machine in it: every exchange here is driven by hand on a {@link InventoryGrabber} of
 * the test's own, which is {@code CraneJobGameTests.craneHeadSurvivesThrowingInventory}'s shape and for its reason —
 * the failure modes that matter are inventory-level, and a test that had to get a crane to the right stop first could
 * not reach most of them.
 *
 * <h2>How conservation is asserted, and the one place it cannot be</h2>
 * The exchange is <b>the</b> operation this milestone is judged on, because it is the only move in this mod under
 * which an item legitimately changes identity while fluid moves the opposite way. So every test here asserts the
 * <b>joint</b> census ({@link FluidCensus#assertConserved}), and the item side of a swap is always declared through
 * {@link ItemCensus#exchange}, which verifies against the game itself that emptying the filled container really yields
 * the empty one — a test cannot declare a swap the game would not make.
 * <p>
 * A grabber of the test's own is <b>not</b> census-visible: {@link ItemCensus} reads handling heads off
 * {@code StackerCraneBlockEntity}, and this one belongs to no crane. So the containers always start and end in a real
 * chest, where both censuses see them, and while the head carries something the two halves are counted through
 * {@link #assertConservedWithHead}, which adds the head's own contents to the item census and lets the fluid census
 * read the containers in it exactly as it reads the ones in a chest. Nothing is asserted "by hand" that a census could
 * state.
 *
 * <h2>What each test is here for</h2>
 * <ul>
 * <li>{@link #containerExchangeSwapsABucketAtAFluidBay} — the loop the issue decided, three times over: a filled
 * bucket arrives, the bay drains it, the head carries an empty bucket away. The joint census after every leg, and the
 * two facts the surrounding code will rely on: a simulated exchange changes <b>nothing</b> and then agrees with the
 * real one in the same tick, and one successful exchange notifies its owner exactly <b>once</b>;</li>
 * <li>{@link #containerExchangeIsAllOrNothing} — the all-or-nothing rule and its deliberate disagreement with
 * {@code simulateInsert}'s monotone bound, including the case the issue names: a bay with 999 mB of room takes
 * <b>nothing</b> from a bucket;</li>
 * <li>{@link #containerExchangeRefusesWhatIsNotAFilledContainer} — every refusal before anything moves, with the one
 * that closes a loop rather than a hole: an <b>empty</b> container is never exchanged into a bay, so the crane can
 * never fill one and shelve it again for ever;</li>
 * <li>{@link #containerExchangeSurvivesALyingLocation} — the branches no fluid bay in this mod can reach, driven by a
 * scripted location: a throw out of either call, a plan that does not match the request (and then the real call is
 * never made at all), and a real answer that contradicts the plan it had just given. The head must end up holding
 * exactly <b>one</b> item key in every one of them, because a head holding a stranger has it thrown on the ground at
 * the dock;</li>
 * <li>{@link #aFluidBayResolvesToAnExchangeLocation} — the same primitive reached the way the crane will reach it,
 * through {@link TransferContexts#resolve} in a real warehouse, plus the item answers of that context: nothing can be
 * extracted from a fluid bay and an insertion is refused by giving the stack back untouched.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ContainerExchangeGameTests {
    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1200;
    /** Ticks a test waits before reading {@link LogCapture}: log4j may deliver an event on another thread. */
    private static final int LOG_TICKS = 3;

    /** A bay standing on its own, clear of the template walls. */
    private static final BlockPos LONE_BAY = new BlockPos(3, BASE_Y, 3);
    /** The chest the containers come out of and go back into, so both censuses see them at rest. */
    private static final BlockPos CHEST = new BlockPos(5, BASE_Y, 3);
    /** Where a scripted location stands, and therefore where it spills: well inside the template. */
    private static final BlockPos SCRIPTED = new BlockPos(3, BASE_Y, 3);

    private static final RackPosition BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey EMPTY_BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);

    /** Containers one test carries, one trip at a time — three, so the tank visibly accumulates. */
    private static final int BUCKETS = 3;
    /** A copper bay's shipped capacity in millibuckets. */
    private static final int COPPER_CAPACITY = 64 * BUCKET_MB;
    /** A pre-fill leaving less than one whole bucket of room: the refusal the issue names. */
    private static final int ALMOST_FULL = COPPER_CAPACITY - (BUCKET_MB - 1);
    /** A fill leaving 2 999 mB of room: two whole buckets fit and a third does not. */
    private static final int ROOM_FOR_TWO = COPPER_CAPACITY - (3 * BUCKET_MB - 1);
    /** One millibucket less, so exactly three whole buckets fit — the step that turns the bound from 2 into 3. */
    private static final int ROOM_FOR_THREE = COPPER_CAPACITY - 3 * BUCKET_MB;
    /** Iron the scripted-location test moves around, enough for every case to start from a loaded head. */
    private static final int SCRIPTED_IRON = 32;
    /** Containers the scripted cases ask for, above one so "fewer than planned" is a reachable answer. */
    private static final int SCRIPTED_AMOUNT = 3;
    /** More containers than the head will ever hold, for the claim no bookkeeping can honour. */
    private static final int SCRIPTED_SURPLUS = 16;

    private ContainerExchangeGameTests() {
    }

    // --- the loop the issue decided --------------------------------------------------------------------------------

    /**
     * Three lava buckets, one trip each: the head takes a filled bucket out of a chest, exchanges it at a copper fluid
     * bay, and puts the empty bucket it got back into the same chest. After each trip the warehouse holds one more
     * bucket of lava as a <b>fluid</b> and one more empty bucket as <b>stock</b>, which is exactly the answer issue
     * #21 asks of a fluid warehouse.
     * <p>
     * Three things are pinned besides the swap itself, each of which the job and the state machine of M30 step 8 will
     * build on:
     * <ul>
     * <li><b>a simulated exchange changes nothing and then agrees with the real one in the same tick</b> — the
     * property the caller's gate depends on, and the only reason it is safe to begin a swap at all. It is checked
     * against the bay's own millibucket count, not against the simulation's own word;</li>
     * <li><b>the bay's room is read as whole containers</b>: {@code simulateInsert} answers 64 for an empty copper bay
     * asked for a hundred buckets, and one fewer after every trip;</li>
     * <li><b>one exchange notifies its owner exactly once</b>, after both halves of the swap. The owner of a real head
     * is the dock, whose notification saves and syncs it, so a second call would be a second save of a half-finished
     * head and no call at all would be a lost one.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void containerExchangeSwapsABucketAtAFluidBay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        placeBay(helper, LONE_BAY);
        placeChest(helper, CHEST, LAVA_BUCKET.toStack(BUCKETS));
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);

        AtomicInteger notifications = new AtomicInteger();
        InventoryGrabber head = new InventoryGrabber(notifications::incrementAndGet);
        TransferContext bayContext = TransferContexts.ofFluidBay(level, helper.absolutePos(LONE_BAY), bay);

        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, BUCKETS);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, BUCKETS * BUCKET_MB);
        FluidCensus.assertConserved(helper, items, fluid, "three filled buckets in a chest, an empty bay");

        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, 100), 64,
                "an empty copper bay takes 64 whole buckets and not a 65th");

        for (int trip = 1; trip <= BUCKETS; trip++) {
            notifications.set(0);
            helper.assertValueEqual(head.pick(chestContext(helper, CHEST), LAVA_BUCKET, 1), 1,
                    "the head took one filled bucket out of the chest");
            helper.assertValueEqual(notifications.getAndSet(0), 1, "the pick notified once");
            assertConservedWithHead(helper, head, items, fluid, "a filled bucket on the head, trip " + trip);

            // A simulated exchange is free and honest: it changes nothing, and what it says is what the real one does.
            int before = bay.millibuckets();
            Optional<TransferContext.ContainerExchange> planned = bayContext.exchange(LAVA_BUCKET, 1, true);
            helper.assertTrue(planned.isPresent(), "the bay plans the exchange");
            helper.assertValueEqual(planned.get().result(), EMPTY_BUCKET, "a lava bucket becomes an empty bucket");
            helper.assertValueEqual(planned.get().containers(), 1, "one container");
            helper.assertValueEqual(planned.get().millibuckets(), BUCKET_MB, "and one bucket of lava moves");
            helper.assertValueEqual(bay.millibuckets(), before, "a simulated exchange moved no fluid");
            assertConservedWithHead(helper, head, items, fluid, "after a simulated exchange, trip " + trip);

            helper.assertValueEqual(head.exchange(bayContext, LAVA_BUCKET, EMPTY_BUCKET, 1), 1,
                    "the real exchange did what the simulated one said");
            helper.assertValueEqual(notifications.getAndSet(0), 1,
                    "and notified its owner exactly once, after both halves");
            helper.assertValueEqual(bay.millibuckets(), before + BUCKET_MB, "the bay holds one bucket more of lava");
            helper.assertValueEqual(bay.storedFluid().orElse(null), LAVA, "as lava");
            helper.assertValueEqual(head.count(LAVA_BUCKET), 0, "the filled bucket is gone from the head");
            helper.assertValueEqual(head.count(EMPTY_BUCKET), 1, "and an empty one is on it");
            helper.assertValueEqual(head.held().entries().size(), 1, "the head holds exactly one key");

            // The declaration is verified against the game's own emptying routine, so it cannot be a fake swap.
            ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1);
            assertConservedWithHead(helper, head, items, fluid, "after the exchange, trip " + trip);

            helper.assertValueEqual(head.drop(chestContext(helper, CHEST), EMPTY_BUCKET, 1), 1,
                    "the empty bucket is shelved like any other item");
            helper.assertTrue(head.isEmpty(), "the head is empty again");
            FluidCensus.assertConserved(helper, items, fluid, "after trip " + trip);
            helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, 100), 64 - trip,
                    "one whole container less of room per trip");
        }

        helper.assertValueEqual(bay.millibuckets(), BUCKETS * BUCKET_MB, "the warehouse holds three buckets of lava");
        helper.assertValueEqual(chestCount(helper, CHEST, EMPTY_BUCKET), (long) BUCKETS,
                "and three empty buckets as ordinary stock");
        helper.assertValueEqual(chestCount(helper, CHEST, LAVA_BUCKET), 0L, "with no filled one left anywhere");
        helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped on the way");
        helper.succeed();
    }

    // --- all or nothing --------------------------------------------------------------------------------------------

    /**
     * A container is drained to empty or refused, never partially (D5) — and the two numbers that follow from it
     * disagree on purpose.
     * <ul>
     * <li>With <b>999 mB of room</b> a copper bay takes <b>nothing</b> from a bucket. That is the issue's own case and
     * it is also the vanilla bucket's rule, and it has to read as a refusal rather than as a lost bucket: the head
     * still holds the filled container afterwards and the bay has not moved a drop.</li>
     * <li>With room for <b>two</b> buckets, {@code simulateInsert} asked for three answers <b>two</b> — it is monotone,
     * because every caller of it wants a bound — while {@code exchange} of three answers <b>zero</b> and moves nothing,
     * because a partial exchange would leave both a filled and an empty bucket in one head. A caller that asks for the
     * bound first and then exchanges exactly that much always succeeds, which is how the planner will use it.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void containerExchangeIsAllOrNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        placeBay(helper, LONE_BAY);
        placeChest(helper, CHEST, LAVA_BUCKET.toStack(BUCKETS));
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);
        helper.assertValueEqual(bay.fill(LAVA.toStack(ALMOST_FULL), false), ALMOST_FULL, "the bay is almost full");

        InventoryGrabber head = new InventoryGrabber(() -> {
        });
        TransferContext bayContext = TransferContexts.ofFluidBay(level, helper.absolutePos(LONE_BAY), bay);

        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, BUCKETS);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, (long) BUCKETS * BUCKET_MB + ALMOST_FULL);
        helper.assertValueEqual(head.pick(chestContext(helper, CHEST), LAVA_BUCKET, BUCKETS), BUCKETS,
                "the head carries all three filled buckets");
        assertConservedWithHead(helper, head, items, fluid, "three filled buckets on the head");

        // 999 mB of room: nothing at all, and nothing lost either.
        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, BUCKETS), 0,
                "999 mB of room is room for no whole bucket");
        helper.assertTrue(bayContext.exchange(LAVA_BUCKET, 1, true).isEmpty(), "so the bay plans no exchange");
        helper.assertValueEqual(head.exchange(bayContext, LAVA_BUCKET, EMPTY_BUCKET, 1), 0, "and performs none");
        helper.assertValueEqual(bay.millibuckets(), ALMOST_FULL, "not a drop moved");
        helper.assertValueEqual(head.count(LAVA_BUCKET), BUCKETS, "the filled buckets are all still held");
        assertConservedWithHead(helper, head, items, fluid, "a bay with 999 mB of room refuses a bucket");

        // Room for two of the three: the bound says two, the exchange of three says nothing.
        drain(helper, bay, fluid, ROOM_FOR_TWO);
        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, BUCKETS), 2,
                "the bound is monotone: two whole buckets fit");
        helper.assertValueEqual(head.exchange(bayContext, LAVA_BUCKET, EMPTY_BUCKET, BUCKETS), 0,
                "but an exchange of three is all or nothing");
        helper.assertValueEqual(bay.millibuckets(), ROOM_FOR_TWO, "so nothing moved");
        helper.assertValueEqual(head.count(LAVA_BUCKET), BUCKETS, "and the head is untouched");
        assertConservedWithHead(helper, head, items, fluid, "an exchange of three into room for two");

        // One millibucket more of room, and the same request goes through: room for all three, exactly.
        drain(helper, bay, fluid, ROOM_FOR_THREE);
        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, BUCKETS), BUCKETS, "three whole buckets fit");
        helper.assertValueEqual(head.exchange(bayContext, LAVA_BUCKET, EMPTY_BUCKET, BUCKETS), BUCKETS,
                "and all three are exchanged at once");
        ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, BUCKETS);
        helper.assertValueEqual(bay.millibuckets(), COPPER_CAPACITY, "the whole load is in the bay, which is now full");
        helper.assertValueEqual(head.held().entries().size(), 1, "and the head holds exactly one key");
        assertConservedWithHead(helper, head, items, fluid, "after exchanging all three at once");

        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, 1), 0, "a full bay takes nothing more");
        helper.assertValueEqual(head.drop(chestContext(helper, CHEST), EMPTY_BUCKET, BUCKETS), BUCKETS, "shelved");
        FluidCensus.assertConserved(helper, items, fluid, "three empty buckets back in the chest");
        helper.succeed();
    }

    // --- refusals --------------------------------------------------------------------------------------------------

    /**
     * Everything a fluid bay refuses to exchange, each of them before anything moves.
     * <p>
     * The first one closes a loop rather than a hole: an <b>empty</b> container carries no fluid, so a bay never takes
     * one, so the crane can never bring an empty bucket to a bay, have it filled and shelve it again — a churn loop
     * nobody has to remember to prevent, because the question "does this carry the fluid of that bay" answers it. It
     * is asked of an <b>unfiltered, empty</b> bay, the one state that takes whatever fluid arrives next and therefore
     * the state in which a mistake would be hidden.
     * <p>
     * The rest are the ordinary ones: an item that is no container at all, a container of another fluid at a filtered
     * bay and at a bay that already holds something else — with the positive case stated in between, so it is on
     * record that the water bucket's refusal comes from the bay and never from the container — an exchange of a key
     * for itself, an amount of zero or less, and a key the head does not hold.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void containerExchangeRefusesWhatIsNotAFilledContainer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        placeBay(helper, LONE_BAY);
        placeChest(helper, CHEST, EMPTY_BUCKET.toStack(1), IRON.toStack(1), WATER_BUCKET.toStack(1));
        FluidBayBlockEntity bay = bayAt(helper, LONE_BAY);

        InventoryGrabber head = new InventoryGrabber(() -> {
        });
        TransferContext bayContext = TransferContexts.ofFluidBay(level, helper.absolutePos(LONE_BAY), bay);

        Map<ItemKey, Long> items = ItemCensus.of(EMPTY_BUCKET, 1, IRON, 1, WATER_BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of(FluidKey.of(Fluids.WATER), BUCKET_MB);
        FluidCensus.assertConserved(helper, items, fluid, "a chest of odds and ends, an empty unfiltered bay");

        // What is no filled container at all is refused by every bay, in every state — including an unfiltered empty
        // one, which takes the first fluid that ARRIVES and is exactly the state in which a mistake would be hidden.
        for (ItemKey key : List.of(EMPTY_BUCKET, IRON)) {
            helper.assertValueEqual(head.pick(chestContext(helper, CHEST), key, 1), 1, "the head holds one " + key);
            helper.assertValueEqual(bayContext.simulateInsert(key, 1), 0, "an unfiltered empty bay takes no " + key);
            helper.assertTrue(bayContext.exchange(key, 1, true).isEmpty(),
                    "and plans no exchange of it, whatever the caller hoped to get back: " + key);
            helper.assertValueEqual(head.exchange(bayContext, key, DIAMOND, 1), 0, "so no exchange of " + key);
            assertGenericRefusals(helper, head, bayContext, key);
            helper.assertValueEqual(bay.millibuckets(), 0, "the bay is still empty");
            assertConservedWithHead(helper, head, items, fluid, "a bay refusing " + key);
            helper.assertValueEqual(head.drop(chestContext(helper, CHEST), key, 1), 1, "put back");
        }

        // A key the head does not hold is never offered, whatever the bay would make of it.
        helper.assertValueEqual(head.exchange(bayContext, LAVA_BUCKET, EMPTY_BUCKET, 1), 0,
                "a head offers only containers it really holds");

        // An unfiltered EMPTY bay takes the first fluid that arrives, water included. So the refusal of a water bucket
        // below has to come from the bay's filter or from what it already holds, never from the container itself.
        helper.assertValueEqual(bayContext.simulateInsert(WATER_BUCKET, 1), 1,
                "an unfiltered empty bay would take a water bucket");

        // A filter that names lava refuses a water bucket, and names what it does take.
        helper.assertTrue(bay.setStoreFilter(LAVA_BUCKET.toStack(1)), "the bay is dedicated to lava");
        helper.assertValueEqual(bay.dedicatedFluid().orElse(null), LAVA, "its filter names lava");
        helper.assertValueEqual(head.pick(chestContext(helper, CHEST), WATER_BUCKET, 1), 1, "a water bucket is held");
        helper.assertValueEqual(bayContext.simulateInsert(WATER_BUCKET, 1), 0, "a lava bay takes no water bucket");
        helper.assertTrue(bayContext.exchange(WATER_BUCKET, 1, true).isEmpty(), "and plans no exchange of one");
        helper.assertValueEqual(head.exchange(bayContext, WATER_BUCKET, EMPTY_BUCKET, 1), 0, "nor performs one");
        assertGenericRefusals(helper, head, bayContext, WATER_BUCKET);
        helper.assertValueEqual(bayContext.simulateInsert(LAVA_BUCKET, 100), 64, "while it would take 64 lava buckets");
        assertConservedWithHead(helper, head, items, fluid, "a lava-filtered bay refusing a water bucket");

        // The same refusal from a bay that learned its fluid instead of being told it.
        helper.assertTrue(bay.setStoreFilter(ItemStack.EMPTY), "the filter is cleared");
        helper.assertValueEqual(bay.fill(LAVA.toStack(BUCKET_MB), false), BUCKET_MB, "and the bay learns lava itself");
        FluidCensus.change(fluid, LAVA, BUCKET_MB);
        helper.assertValueEqual(bayContext.simulateInsert(WATER_BUCKET, 1), 0, "a bay holding lava takes no water");
        helper.assertValueEqual(head.exchange(bayContext, WATER_BUCKET, EMPTY_BUCKET, 1), 0, "and exchanges none");
        helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "its lava is untouched");
        assertConservedWithHead(helper, head, items, fluid, "a bay holding lava refusing a water bucket");

        helper.assertValueEqual(head.drop(chestContext(helper, CHEST), WATER_BUCKET, 1), 1, "the water bucket is back");
        helper.assertTrue(head.isEmpty(), "and the head is empty");
        helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
        helper.assertValueEqual(chestCount(helper, CHEST, IRON), 1L, "the iron never left the chest for long");
        FluidCensus.assertConserved(helper, items, fluid, "after every refusal");
        helper.succeed();
    }

    // --- a location that does not keep its word --------------------------------------------------------------------

    /**
     * The branches no fluid bay in this mod can reach, driven by a {@link ScriptedExchange} standing in for a modded
     * location: an exception out of either call, a plan that is not the exchange that was asked for, and a real answer
     * that contradicts the plan the same location had just given.
     * <p>
     * <b>No fluid is involved at all</b> — the items swapped are iron for gold. The primitive does not know what a
     * container is; the fluid is the bay's business, and leaving it out is what makes the item side of these cases
     * assertable: a lying location is by definition not conserving anything, so what is asserted here is that the
     * <b>head</b> gives up exactly as many items as the location claims to have taken and drops exactly as many as it
     * hands back, never one more or one fewer.
     * <p>
     * For the same reason this is the one test in the holder whose expectation is written with
     * {@link ItemCensus#change} rather than {@link ItemCensus#exchange}: iron for gold is not a container exchange, it
     * is a fault injection, so the verified declaration would rightly refuse it.
     * <p>
     * The invariant every case ends on is that the head holds <b>at most one</b> item key. A head carrying a key its
     * job does not name has that key thrown on the ground at the dock by {@code CraneExecution.reconcileHeadWithJob},
     * so a salvage that kept the received items would turn a location's misbehaviour into a trip across the warehouse
     * and a pile of items in the aisle.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void containerExchangeSurvivesALyingLocation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos scripted = helper.absolutePos(SCRIPTED);
        placeChest(helper, CHEST, IRON.toStack(SCRIPTED_IRON));

        Map<ItemKey, Long> items = ItemCensus.of(IRON, SCRIPTED_IRON);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        TransferContext.ContainerExchange asked = new TransferContext.ContainerExchange(GOLD, SCRIPTED_AMOUNT,
                BUCKET_MB);

        // A throw out of the simulated call: nothing had moved, so nothing is touched.
        ScriptedExchange throwsOnSimulate = ScriptedExchange.failing(level, scripted, true);
        InventoryGrabber head = loaded(helper, SCRIPTED_AMOUNT);
        helper.assertValueEqual(head.exchange(throwsOnSimulate, IRON, GOLD, SCRIPTED_AMOUNT), 0, "no exchange");
        helper.assertValueEqual(throwsOnSimulate.performances(), 0, "and the real call was never made");
        assertUntouched(helper, head, items, fluid, "a location that throws while simulating");

        // A throw out of the real call: we do not know what moved, so the head stays as it was.
        ScriptedExchange throwsOnPerform = ScriptedExchange.failing(level, scripted, false);
        helper.assertValueEqual(head.exchange(throwsOnPerform, IRON, GOLD, SCRIPTED_AMOUNT), 0, "no exchange");
        helper.assertValueEqual(throwsOnPerform.performances(), 1, "the real call was made");
        assertUntouched(helper, head, items, fluid, "a location that throws while performing");

        // A plan that is not what was asked for: refused while it is still free, and the real call is never made.
        for (TransferContext.ContainerExchange plan : List.of(
                new TransferContext.ContainerExchange(GOLD, SCRIPTED_AMOUNT - 1, BUCKET_MB),
                new TransferContext.ContainerExchange(GOLD, SCRIPTED_AMOUNT + 1, BUCKET_MB),
                new TransferContext.ContainerExchange(DIAMOND, SCRIPTED_AMOUNT, BUCKET_MB))) {
            ScriptedExchange location = ScriptedExchange.answering(level, scripted, plan, plan);
            helper.assertValueEqual(head.exchange(location, IRON, GOLD, SCRIPTED_AMOUNT), 0,
                    "all or nothing refuses the plan " + plan);
            helper.assertValueEqual(location.simulations(), 1, "after one simulated call");
            helper.assertValueEqual(location.performances(), 0, "and no real one at all");
            assertUntouched(helper, head, items, fluid, "a location planning " + plan);
        }

        // A plan that agrees, and then a real call that refuses: by contract nothing moved there either.
        ScriptedExchange refusesForReal = ScriptedExchange.answering(level, scripted, asked, null);
        helper.assertValueEqual(head.exchange(refusesForReal, IRON, GOLD, SCRIPTED_AMOUNT), 0, "no exchange");
        helper.assertValueEqual(refusesForReal.performances(), 1, "the real call was made and refused");
        assertUntouched(helper, head, items, fluid, "a location refusing for real");

        // A real answer naming another item than the plan did: the iron is gone, so it leaves the head, and the
        // diamonds that came back are dropped at the location rather than carried.
        ScriptedExchange swapsTheItem = ScriptedExchange.answering(level, scripted, asked,
                new TransferContext.ContainerExchange(DIAMOND, SCRIPTED_AMOUNT, BUCKET_MB));
        helper.assertValueEqual(head.exchange(swapsTheItem, IRON, GOLD, SCRIPTED_AMOUNT), 0,
                "the caller is told nothing was delivered, so its job follows the head");
        helper.assertTrue(head.isEmpty(), "the head gave up the items the location says it took");
        ItemCensus.change(items, IRON, -SCRIPTED_AMOUNT);
        ItemCensus.change(items, DIAMOND, SCRIPTED_AMOUNT);
        assertSettled(helper, head, items, fluid, "a location answering with another item");
        helper.assertValueEqual(droppedCount(helper, DIAMOND), (long) SCRIPTED_AMOUNT,
                "what came back lies at the location, dropped and not deleted");

        // A real answer claiming fewer containers than planned: only those leave the head, and the head keeps one key.
        head = loaded(helper, SCRIPTED_AMOUNT);
        ScriptedExchange tooFew = ScriptedExchange.answering(level, scripted, asked,
                new TransferContext.ContainerExchange(GOLD, SCRIPTED_AMOUNT - 1, BUCKET_MB));
        helper.assertValueEqual(head.exchange(tooFew, IRON, GOLD, SCRIPTED_AMOUNT), 0, "nothing was delivered");
        helper.assertValueEqual(head.count(IRON), 1, "one iron is left on the head");
        helper.assertValueEqual(head.held().entries().size(), 1, "which is the head's only key");
        ItemCensus.change(items, IRON, -(SCRIPTED_AMOUNT - 1));
        ItemCensus.change(items, GOLD, SCRIPTED_AMOUNT - 1);
        assertConservedWithHead(helper, head, items, fluid, "a location answering with fewer containers");
        helper.assertValueEqual(head.drop(chestContext(helper, CHEST), IRON, 1), 1, "the rest is put back");

        // A real answer claiming more containers than the head holds: clamped to what it can give up.
        head = loaded(helper, SCRIPTED_AMOUNT);
        ScriptedExchange tooMany = ScriptedExchange.answering(level, scripted, asked,
                new TransferContext.ContainerExchange(GOLD, SCRIPTED_SURPLUS, BUCKET_MB));
        helper.assertValueEqual(head.exchange(tooMany, IRON, GOLD, SCRIPTED_AMOUNT), 0, "nothing was delivered");
        helper.assertTrue(head.isEmpty(), "the head gave up everything it held of the key and no more");
        ItemCensus.change(items, IRON, -SCRIPTED_AMOUNT);
        ItemCensus.change(items, GOLD, SCRIPTED_AMOUNT);
        assertSettled(helper, head, items, fluid, "a location claiming more containers than the head holds");
        helper.succeed();
    }

    // --- the way the crane will reach it ---------------------------------------------------------------------------

    /**
     * The same primitive through {@link TransferContexts#resolve} in a running warehouse, which is how the crane will
     * reach it: a fluid bay at a rack position resolves to an exchange location of kind {@code STORAGE}, through both
     * the one-aisle and the whole-warehouse overload, and the exchange works through the context it hands out.
     * <p>
     * And the item answers of that context, which are the reason it had to exist at all: a fluid bay holds no items, so
     * a storage location whose attached inventory is read would have answered {@code MISSING} for it. Nothing can be
     * extracted, and an insertion is refused by <b>giving the caller its own stack back untouched</b> — never by
     * swallowing it and never by handing a <i>different</i> item back as the remainder, which is the trap the whole
     * primitive exists to avoid. Because that call has no legitimate caller, it is also logged, and the log line is
     * asserted here: a silent "returns the stack" would be correct and undiagnosable. <b>That line is throttled per
     * server</b>, and this is the only place in the whole suite that performs a real insertion into a fluid bay — a
     * second one within a minute of game time would make this assertion flaky, so a test that needs one should assert
     * the refusal rather than the line.
     * <p>
     * The aisle is built <b>without</b> a motor, so the crane cannot move and nothing but this test touches the bay.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aFluidBayResolvesToAnExchangeLocation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        helper.setBlock(bayPos, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                aisle.sideDirection(BAY_RACK)));
        FluidBayBlockEntity bay = bayAt(helper, bayPos);
        aisle.build(false);
        aisle.storage(CHEST_RACK, LAVA_BUCKET.toStack(1));

        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, BUCKET_MB);
        InventoryGrabber head = new InventoryGrabber(() -> {
        });
        // Opened in the step that performs the refused insertion and detached in the next one, so a failure anywhere
        // else in this test cannot leave an appender recording for the rest of the run.
        LogCapture[] log = new LogCapture[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    FluidCensus.assertConserved(helper, items, fluid, "a fluid bay in a standing warehouse");

                    BranchLayout layout = aisle.controller().layout().orElseThrow();
                    TransferContexts.Resolution resolved = TransferContexts.resolve(level, layout, BAY_RACK,
                            LocationKind.STORAGE);
                    helper.assertTrue(resolved.isAvailable(), "a fluid bay is a reachable storage location");
                    helper.assertTrue(TransferContexts
                            .resolve(level, aisle.controller().warehouse().orElseThrow(), BAY_RACK,
                                    LocationKind.STORAGE)
                            .isAvailable(), "through the whole-warehouse overload as well");
                    helper.assertFalse(
                            TransferContexts.resolve(level, layout, BAY_RACK, LocationKind.OUTPUT).isAvailable(),
                            "and never as a kind it is not");

                    TransferContext context = resolved.context().orElseThrow();
                    helper.assertValueEqual(context.kind(), LocationKind.STORAGE, "a bay is a storage location");
                    helper.assertValueEqual(context.position(), aisle.absoluteRackPos(BAY_RACK),
                            "the arm reaches in at the bay itself");

                    // Nothing to take out of it, ever.
                    helper.assertTrue(context.extract(LAVA_BUCKET, 64, false).isEmpty(), "nothing to extract");
                    helper.assertValueEqual(context.simulateExtract(LAVA_BUCKET, 64), 0, "and none simulated either");

                    helper.assertValueEqual(context.simulateInsert(LAVA_BUCKET, 1), 1, "one bucket would be exchanged");
                    helper.assertValueEqual(context.simulateInsert(EMPTY_BUCKET, 1), 0, "an empty one never would");

                    // An insertion gives the caller its own stack back: the one refusal that loses nothing.
                    log[0] = LogCapture.ofWarnings();
                    try {
                        ItemStack offered = LAVA_BUCKET.toStack(4);
                        ItemStack back = context.insert(offered.copy(), false);
                        helper.assertTrue(ItemStack.isSameItemSameComponents(back, offered),
                                "the same item came back");
                        helper.assertValueEqual(back.getCount(), offered.getCount(), "and every one of them");
                        helper.assertValueEqual(bay.millibuckets(), 0, "while the bay took nothing");
                        helper.assertTrue(context.insert(offered.copy(), true).getCount() == offered.getCount(),
                                "and a simulated one says the same");
                        FluidCensus.assertConserved(helper, items, fluid, "after an insertion a fluid bay refused");
                    } catch (RuntimeException | AssertionError e) {
                        log[0].close();
                        throw e;
                    }
                })
                .thenExecuteAfter(LOG_TICKS, () -> {
                    List<String> warnings = log[0].closeAndTake();
                    String line = LogCapture.firstContaining(warnings, "fluid bay", "holds no items",
                            String.valueOf(aisle.absoluteRackPos(BAY_RACK)));
                    helper.assertTrue(line != null,
                            "an insertion into a fluid bay must say so in the log: " + warnings);
                })
                .thenExecute(() -> {
                    TransferContext context = TransferContexts
                            .resolve(level, aisle.controller().layout().orElseThrow(), BAY_RACK, LocationKind.STORAGE)
                            .context().orElseThrow();
                    IItemHandler chest = aisle.handlerAt(aisle.inventoryPos(CHEST_RACK));
                    helper.assertValueEqual(
                            head.pick(TransferContexts.ofHandler(level, aisle.absoluteRackPos(CHEST_RACK), chest),
                                    LAVA_BUCKET, 1),
                            1, "the head takes the filled bucket out of the aisle's chest");
                    helper.assertValueEqual(head.exchange(context, LAVA_BUCKET, EMPTY_BUCKET, 1), 1,
                            "and exchanges it at the bay the warehouse resolved");
                    ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1);
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the bay holds the lava");
                    assertConservedWithHead(helper, head, items, fluid, "after an exchange at a resolved bay");
                    helper.assertValueEqual(
                            head.drop(TransferContexts.ofHandler(level, aisle.absoluteRackPos(CHEST_RACK), chest),
                                    EMPTY_BUCKET, 1),
                            1, "the empty bucket is shelved in an ordinary chest");
                    FluidCensus.assertConserved(helper, items, fluid,
                            "lava as a fluid and a bucket as stock, in one warehouse");
                })
                .thenExecute(() -> {
                    // A bay that is gone is MISSING, so the crane reroutes instead of waiting for ever.
                    Clearable.tryClear(level.getBlockEntity(aisle.absoluteRackPos(BAY_RACK)));
                    helper.setBlock(bayPos, Blocks.AIR);
                    helper.assertFalse(TransferContexts
                            .resolve(level, aisle.controller().layout().orElseThrow(), BAY_RACK, LocationKind.STORAGE)
                            .isAvailable(), "a broken bay is no location at all");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * The refusals that have nothing to do with the location: a key exchanged for itself (which would have the head
     * remove and re-add the same items while the location kept the fluid, i.e. fluid out of nothing) and an amount of
     * zero or less.
     */
    private static void assertGenericRefusals(GameTestHelper helper, InventoryGrabber head, TransferContext location,
            ItemKey held) {
        helper.assertValueEqual(head.exchange(location, held, held, 1), 0, "no key is exchanged for itself: " + held);
        helper.assertValueEqual(head.exchange(location, held, DIAMOND, 0), 0, "nor zero of it");
        helper.assertValueEqual(head.exchange(location, held, DIAMOND, -1), 0, "nor a negative amount");
    }

    /**
     * The joint census with the test's own handling head counted in: its items are added to the item census, and the
     * fluid census then reads the containers on it exactly as it reads the ones in a chest.
     * <p>
     * It exists because {@link ItemCensus} reads handling heads off {@code StackerCraneBlockEntity} and the grabbers
     * here belong to no crane — not because anything about the head is special. Both halves are compared in one place,
     * because the item half and the fluid half of an exchange do not conserve separately.
     */
    private static void assertConservedWithHead(GameTestHelper helper, InventoryGrabber head,
            Map<ItemKey, Long> expectedItems, Map<FluidKey, Long> expectedFluid, String context) {
        Map<ItemKey, Long> actualItems = ItemCensus.take(helper);
        for (HeldItems.Entry entry : head.held().entries())
            ItemCensus.change(actualItems, entry.key(), entry.count());
        Map<FluidKey, Long> actualFluid = FluidCensus.take(helper, actualItems);
        if (actualItems.equals(expectedItems) && actualFluid.equals(expectedFluid))
            return;
        helper.fail("conservation violated (" + context + "): items expected " + ItemCensus.describe(expectedItems)
                + " but found " + ItemCensus.describe(actualItems) + "; fluid expected "
                + FluidCensus.describe(expectedFluid) + " but found " + FluidCensus.describe(actualFluid)
                + ", in millibuckets");
    }

    /** The head is untouched and both censuses are unchanged: the shape of every refusal. */
    private static void assertUntouched(GameTestHelper helper, InventoryGrabber head, Map<ItemKey, Long> items,
            Map<FluidKey, Long> fluid, String context) {
        helper.assertValueEqual(head.count(IRON), SCRIPTED_AMOUNT, "the head still holds its items (" + context + ")");
        helper.assertValueEqual(head.held().entries().size(), 1, "and only its own key (" + context + ")");
        helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped (" + context + ")");
        assertConservedWithHead(helper, head, items, fluid, context);
    }

    /** An empty head and both censuses equal, with whatever was salvaged lying in the world. */
    private static void assertSettled(GameTestHelper helper, InventoryGrabber head, Map<ItemKey, Long> items,
            Map<FluidKey, Long> fluid, String context) {
        helper.assertTrue(head.held().entries().size() <= 1, "the head holds at most one key (" + context + ")");
        assertConservedWithHead(helper, head, items, fluid, context);
    }

    /** A fresh head carrying {@code amount} iron out of the chest, for one scripted case. */
    private static InventoryGrabber loaded(GameTestHelper helper, int amount) {
        InventoryGrabber head = new InventoryGrabber(() -> {
        });
        helper.assertValueEqual(head.pick(chestContext(helper, CHEST), IRON, amount), amount, "the head is loaded");
        return head;
    }

    /** Drains the bay down to {@code target} millibuckets and books the loss in the fluid expectation. */
    private static void drain(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int target) {
        int taken = bay.millibuckets() - target;
        FluidStack drained = bay.drain(taken, false);
        helper.assertValueEqual(drained.getAmount(), taken, "the bay gave up what the test asked for");
        helper.assertValueEqual(bay.millibuckets(), target, "and holds the rest");
        FluidCensus.change(fluid, LAVA, -taken);
    }

    private static void placeBay(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos,
                WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING, Direction.NORTH));
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

    /** A single chest (nothing beside it, so it never becomes a double one) holding {@code contents}. */
    private static void placeChest(GameTestHelper helper, BlockPos pos, ItemStack... contents) {
        helper.setBlock(pos, Blocks.CHEST);
        IItemHandler handler = chestHandler(helper, pos);
        for (ItemStack stack : contents) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
            helper.assertTrue(rest.isEmpty(), "the chest rejected " + rest);
        }
    }

    private static IItemHandler chestHandler(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(
                Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            throw new IllegalStateException("unreachable");
        }
        return handler;
    }

    private static TransferContext chestContext(GameTestHelper helper, BlockPos pos) {
        return TransferContexts.ofHandler(helper.getLevel(), helper.absolutePos(pos), chestHandler(helper, pos));
    }

    private static long chestCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        return countIn(chestHandler(helper, pos), key);
    }

    private static long countIn(IItemHandler handler, ItemKey key) {
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    private static long droppedCount(GameTestHelper helper, ItemKey key) {
        long total = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM)) {
            if (key.matches(entity.getItem()))
                total += entity.getItem().getCount();
        }
        return total;
    }

    /**
     * A {@link TransferContext} that answers a <b>scripted</b> exchange: what a simulated call says, what a real call
     * then says, or an exception out of either, and how often each of them was asked.
     * <p>
     * None of these answers is reachable through a fluid bay of this mod — its handler is this mod's own and
     * {@code FluidContainers} never throws — so a stand-in is the only way to test what a handling head does when a
     * location does not keep its word. Everything that is not an exchange answers like a fluid bay: nothing to
     * extract, an insertion given back untouched, and a spill at its own position.
     */
    private static final class ScriptedExchange implements TransferContext {
        private final ServerLevel level;
        private final BlockPos position;
        @Nullable
        private final ContainerExchange planned;
        @Nullable
        private final ContainerExchange performed;
        private final boolean failing;
        private final boolean failOnSimulate;
        private int simulations;
        private int performances;

        private ScriptedExchange(ServerLevel level, BlockPos position, @Nullable ContainerExchange planned,
                @Nullable ContainerExchange performed, boolean failing, boolean failOnSimulate) {
            this.level = level;
            this.position = position;
            this.planned = planned;
            this.performed = performed;
            this.failing = failing;
            this.failOnSimulate = failOnSimulate;
        }

        /** A location that plans {@code planned} and then really does {@code performed} ({@code null} = refuses). */
        static ScriptedExchange answering(ServerLevel level, BlockPos position, @Nullable ContainerExchange planned,
                @Nullable ContainerExchange performed) {
            return new ScriptedExchange(level, position, planned, performed, false, false);
        }

        /** A location that throws out of the simulated call, or out of the real one. */
        static ScriptedExchange failing(ServerLevel level, BlockPos position, boolean onSimulate) {
            ContainerExchange plan = new ContainerExchange(GOLD, SCRIPTED_AMOUNT, BUCKET_MB);
            return new ScriptedExchange(level, position, plan, plan, true, onSimulate);
        }

        int simulations() {
            return simulations;
        }

        int performances() {
            return performances;
        }

        @Override
        public Optional<ContainerExchange> exchange(ItemKey held, int amount, boolean simulate) {
            if (simulate) {
                simulations++;
                if (failing && failOnSimulate)
                    throw new IllegalStateException("scripted failure while simulating");
                return Optional.ofNullable(planned);
            }
            performances++;
            if (failing && !failOnSimulate)
                throw new IllegalStateException("scripted failure while performing");
            return Optional.ofNullable(performed);
        }

        @Override
        public LocationKind kind() {
            return LocationKind.STORAGE;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            return 0;
        }

        @Override
        public int simulateInsert(ItemKey key, int amount) {
            return 0;
        }

        @Override
        public void spill(ItemStack stack) {
            TransferContexts.spillAt(level, position, stack);
        }
    }
}
