package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the <b>store gate</b> ({@code docs/warehouse-system.md} §3.9, M30 step 9, issue #21, D6): which arriving
 * items the planner carries to a fluid bay and which stay ordinary stock.
 * <p>
 * The two steps before this one built the machinery — {@link ContainerExchangeGameTests} the primitive, and
 * {@link ContainerExchangeJobGameTests} the exchange inside a crane job, both with the job handed to the crane by hand
 * because a bay was not a candidate the <i>planner</i> would pick. This holder is the step that makes it pick one, so
 * every test here plans: the job is never assigned, only the items are put at an input and the warehouse is left to
 * decide.
 *
 * <h2>What the gate is, and what it is not</h2>
 * A fluid bay reports {@code StorageMember#storeFluidFilter()}, and that answer <b>replaces</b> the item filter instead
 * of joining it. So the gate is two sentences:
 * <ul>
 * <li>a container carrying the bay's fluid is {@code FilterMatch#DEDICATED}, which is the top store key — <b>a bay
 * outranks every shelf</b>, including a nearer one;</li>
 * <li>everything else is {@code FilterMatch#REJECTED}: an empty container, a container of another fluid, and every
 * ordinary item, whatever room the bay has and whatever priority it carries.</li>
 * </ul>
 * The <b>amount</b> is not part of it. "Does a whole container fit" is a live question, so a bay with 999 mB of room
 * passes the gate, answers 0 to the live simulate and is never made a job's target — which is the planner's ordinary
 * "this candidate turned out to be full" path and costs one live call, not a special case.
 *
 * <h2>Why these tests go through the planner and not through {@code AisleFilters}</h2>
 * The branch lives in {@code AisleFilters.match}, which needs {@code ItemStack}, a {@code Level} and an item capability,
 * and {@code src/test} has no Minecraft bootstrap. The questions worth asking are about the planner's <i>outcome</i>
 * anyway — where the bucket ends up, whether a job was made at all, what the warehouse says when nothing fits — and
 * those are only askable in a world. The two pure properties this step leans on are already pinned in JUnit:
 * {@code JobPlannerTest.storePrefersADedicatedLocationOverUnfilteredOnes} (dedicated beats unfiltered, whatever the
 * travel time) and {@code JobPlannerTest.storeFallsThroughWhenTheLiveInventoryRejects} (an unknown estimate plus a live
 * 0 moves on to the next candidate).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidBayStoreGateGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 80;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    /**
     * The shelf stands <b>nearer</b> to the input than the bay on purpose: every test in which a container reaches the
     * bay is then also a test that a dedication outranks travel time, which is the consequence of D6 the owner should
     * know about.
     */
    private static final RackPosition CHEST_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition BAY_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey EMPTY_BUCKET = ItemKey.of(Items.BUCKET);
    /** An item that is no fluid container at all: the third kind of thing a fluid bay refuses. */
    private static final ItemKey ORDINARY = ItemKey.of(Items.COBBLESTONE);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);

    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;
    /** A copper bay's shipped capacity in millibuckets. */
    private static final int COPPER_CAPACITY = 64 * BUCKET_MB;
    /** A pre-fill leaving exactly 999 mB of room: a whole bucket does not fit, so nothing at all is taken. */
    private static final int ALMOST_FULL = COPPER_CAPACITY - (BUCKET_MB - 1);
    /** A pre-fill leaving exactly one bucket of room: the other side of the same boundary. */
    private static final int ONE_BUCKET_SHORT = COPPER_CAPACITY - BUCKET_MB;

    private FluidBayStoreGateGameTests() {
    }

    // --- the loop the planner now closes ----------------------------------------------------------------------------

    /**
     * The decided loop, <b>planned</b> rather than assigned: a filled lava bucket arrives at an input of a warehouse
     * whose storage is a chest and a lava-dedicated fluid bay, and the warehouse carries it to the bay, drains it and
     * shelves the empty bucket in the chest. Afterwards it holds <b>1 000 mB of lava as a fluid and one bucket as
     * stock</b>, which is issue #21's own answer to "what does the warehouse hold".
     * <p>
     * Three things it pins beyond the trip itself:
     * <ul>
     * <li><b>the dedication beats travel time.</b> The chest is three rack positions nearer than the bay and still
     * never sees the filled bucket. This is the consequence a player will be surprised by — a shelf of lava buckets for
     * building cannot be kept in a warehouse that has a lava bay with room — and it is {@code FilterMatch#DEDICATED}
     * being the top store key, not a rule of its own;</li>
     * <li><b>the empty bucket stays shelved.</b> After the trip the warehouse is left running for a while: the empty
     * bucket it just produced is an ordinary item in an ordinary chest, and nothing carries it back to the bay. That
     * loop is closed by the gate itself — an empty container carries no fluid, so no bay ever accepts one — rather than
     * by a rule somebody has to remember;</li>
     * <li><b>the item index never counts the container as stored at the bay.</b> It counts one bucket, in the chest,
     * once.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayStoresABucket(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        // The fluid census is invariant through the whole trip: 1 000 mB in a bucket and 1 000 mB in a tank are the
        // same millibuckets to it, so a drop lost on the way fails in the tick it happened.
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid,
                "the lava is in the bucket or in the tank, never in between"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenExecute(() -> {
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "a filled bucket waiting at the input");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                        "the warehouse chose the bay and the bay drained the bucket"))
                .thenExecute(() -> ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "and shelved the empty bucket in the chest, as ordinary stock"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with the trip finished");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the lava stayed in the bay");
                    helper.assertValueEqual(bay.storedFluid().orElse(null), LAVA, "as lava");
                    // The empty bucket is not carried back: an empty container carries no fluid, so the gate refuses
                    // it at every bay. Nothing here is a rule about buckets; it is the gate being about the fluid.
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                            "the empty bucket stays shelved and is never carried back to the bay");
                    helper.assertValueEqual(aisle.controller().countOf(EMPTY_BUCKET), 1L,
                            "counted once as stock");
                    helper.assertValueEqual(aisle.controller().countOf(LAVA_BUCKET), 0L,
                            "and the filled one is counted nowhere: it does not exist any more");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 0L,
                            "the nearer chest never saw it, because a dedication outranks travel time");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * An <b>unfiltered</b> bay takes the first fluid that arrives and then takes nothing else — the gate's three states
     * in one trip, and the one that an {@code Optional<FluidKey>} could not have carried.
     * <p>
     * An empty unfiltered bay is <b>not</b> "accepts everything" and <b>not</b> "accepts nothing": it accepts a
     * container of any fluid, and the first one decides. So a water bucket reaches it although nobody named water, and
     * the lava bucket that arrives next is shelved in the chest instead — not because the warehouse changed its mind
     * but because the bay's dedication changed when its first millibucket arrived.
     * <p>
     * That second half is also the test of the <b>invalidation rule</b> (D6): a bay notifies its controllers of a
     * changed dedication on the empty ↔ non-empty transition and on a fluid change, and <b>never per millibucket</b>.
     * Had it not notified, the cached dedication would still say "any" and the lava bucket would be carried to a bay
     * full of water, where the exchange would refuse it and the crane would have made a pointless trip.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void anUnfilteredFluidBayTakesTheFirstFluidThatArrives(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "no fluid appears or disappears"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    helper.assertTrue(bay.dedicatedFluid().isEmpty(),
                            "an empty unfiltered bay names no fluid at all");
                    helper.assertTrue(goggleKeys(bay)
                            .contains(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST)),
                            "and says so: " + goggleKeys(bay));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    arrive(aisle, items, fluid, WATER_BUCKET, WATER, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                        "the first fluid that arrives is the one it takes, whoever named it"))
                .thenExecute(() -> {
                    ItemCensus.exchange(helper, items, WATER_BUCKET, EMPTY_BUCKET, 1);
                    helper.assertValueEqual(bay.storedFluid().orElse(null), WATER, "water, in this case");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "with the empty bucket shelved"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.dedicatedFluid().orElse(null), WATER,
                            "the bay is dedicated to water now, without a filter");
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 1L,
                        "and the lava bucket is shelved as an item, because that bay is a water bay now"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with both trips finished");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "and not a drop of lava reached it");
                    helper.assertValueEqual(bay.storedFluid().orElse(null), WATER, "it still holds water");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- the refusals -----------------------------------------------------------------------------------------------

    /**
     * A <b>water</b> bucket at a <b>lava</b> bay: refused by the gate, so the crane is never sent there at all, and the
     * bucket is shelved as an ordinary item in the chest instead.
     * <p>
     * The positive case is asserted in the same trip and <b>before</b> the refusal, so the refusal is on record as the
     * bay's own rule and not as something about water buckets or about a warehouse that was not ready yet. And the
     * empty bucket that the lava trip produces is shelved beside the water bucket, which is what makes the chest's
     * contents a complete statement: a warehouse with a fluid bay holds the fluid, the empties and the containers it
     * has no bay for.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayRefusesAWaterBucketAtALavaBay(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "no fluid appears or disappears"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                        "the bay takes its own fluid"))
                .thenExecute(() -> ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "and the empty bucket is shelved"))
                .thenExecuteAfter(SETTLE_TICKS, () -> arrive(aisle, items, fluid, WATER_BUCKET, WATER, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, WATER_BUCKET), 1L,
                        "a container of another fluid is ordinary stock: it goes to the chest"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with both trips finished");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                            "the lava bay took nothing from the water bucket");
                    helper.assertValueEqual(bay.storedFluid().orElse(null), LAVA, "and still holds lava");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * A warehouse whose <b>only</b> storage is a lava bay, asked to store things it refuses: no job is planned, they
     * stay in the input where a player can take them back, and the controller says
     * {@link NoJobReason#NO_MATCHING_FILTER} — "no storage location takes these items, whatever room it has", which is
     * the true sentence about this warehouse.
     * <p>
     * This is the one place the <b>reported</b> reason is worth asserting, because step 9 is what makes a fluid bay
     * answer the store filter at all. Before it, such a bay was skipped at the capacity gate and the same arrival was
     * reported as {@link NoJobReason#WAREHOUSE_FULL} — "no storage location accepts these items" — about a warehouse
     * with 64 buckets of room. So the goggle line both improves and stays honest, and the lava bucket stored in the
     * same trip proves the warehouse was working all along.
     * <p>
     * All <b>three</b> kinds of refusal are in the input together when the reason is read — a container of another
     * fluid, an <b>empty</b> container, and an item that is no container at all — which is what makes this the test
     * that each of them is really the <i>filter</i> saying no. A bay that answered the filter and then refused live
     * would report {@link NoJobReason#WAREHOUSE_FULL} instead, and would also spend one live simulation per bay per
     * key per run on items it can never take (ADR-021's cost rule, pinned in core by
     * {@code JobPlannerTest.filteredOutLocationsCostNoLiveCallAndNoBudget}).
     * <p>
     * A reason <b>more specific</b> than this one ("no fluid bay takes this fluid") belongs to M31, where asking for a
     * fluid makes the distinction actionable; the refusal is explained here already, by this line plus the bay's own
     * "Holds: Lava".
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aWarehouseOfFluidBaysSaysWhyAnItemFitsNowhere(GameTestHelper helper) {
        AisleFixture aisle = bayOnlyWarehouse(helper);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "no fluid appears or disappears"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                        "the one bay this warehouse has does take its own fluid"))
                .thenExecute(() -> ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1))
                // The empty bucket has nowhere to go either, so it ends up back in the input: a store reroute's last
                // resort is an input station (§8). That is also why the water bucket below has to wait for the crane.
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(INPUT_RACK, EMPTY_BUCKET), 1L,
                        "and the empty bucket comes back to the input, since this warehouse has no shelf"))
                // The third kind of refusal beside the empty bucket already waiting there: an item that is no
                // container at all, and one that is a container of another fluid.
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    arrive(aisle, items, fluid, WATER_BUCKET, WATER, 1);
                    arrive(aisle, items, fluid, ORDINARY, null, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().lastPlanReason(),
                        Optional.of(NoJobReason.NO_MATCHING_FILTER),
                        "no storage location takes these items, whatever room it has"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with nothing left to plan");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, WATER_BUCKET), 1L,
                            "the water bucket waits in the input, where a player can take it back");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, EMPTY_BUCKET), 1L,
                            "so does the empty bucket the bay handed back");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, ORDINARY), 4L,
                            "and so does an item that is no container at all");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(),
                            Optional.of(NoJobReason.NO_MATCHING_FILTER),
                            "with the same reason after three kinds of refusal piled up");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                            "and the bay took nothing from any of them");
                    helper.assertValueEqual(aisle.controller().countOf(WATER_BUCKET), 0L,
                            "an item in an input buffer is not stock");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * A bay with <b>999 mB of room</b> is never offered a bucket, and the goggles say why.
     * <p>
     * The rule is D5: a container is emptied whole or refused, because a half-full container is a different item key
     * per millibucket value. So "a container that does not fit is never sent in the first place" — the bucket is
     * shelved in the chest instead, the crane never travels to the bay, nothing is dropped and not one millibucket
     * moves. The refusal is a <b>live</b> answer rather than a filter one: the gate is about the fluid, the amount is
     * about the room, and only the live simulate knows the room.
     * <p>
     * The second half is the readout, and it is the reason this test exists in this step at all. Such a bay draws
     * "Lava 63.00 / 64 buckets" — a bucket of <i>apparent</i> room — so a refused bucket looks exactly like a lost one.
     * The gold line {@code gui.goggles.fluid_bay_no_bucket_room} is asserted present here and <b>absent</b> on the
     * other side of the same boundary, where the bay has exactly one bucket of room and takes the bucket; both sides
     * come out of {@code FluidBayTier.wholeContainers}, the store gate's own arithmetic, so the line cannot disagree
     * with the refusal it explains.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aFluidBayWithLessThanABucketOfRoomIsNotOfferedOne(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        bay.fill(new FluidStack(Fluids.LAVA, ALMOST_FULL), false);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, ALMOST_FULL);
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "no fluid appears or disappears"));
        String noRoom = WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_NO_BUCKET_ROOM);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    helper.assertTrue(bay.noRoomForAWholeBucket(),
                            "999 mB of room is no room at all for a bucket");
                    helper.assertTrue(goggleKeys(bay).contains(noRoom),
                            "and the goggles say so, in gold: " + goggleKeys(bay));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 1L,
                        "the filled bucket is shelved as an item instead, with no code for it"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with the bucket shelved");
                    helper.assertValueEqual(bay.millibuckets(), ALMOST_FULL,
                            "and the bay took not one millibucket of it");
                    aisle.assertIdleAndEmpty();
                    // One millibucket more of room is a whole bucket of room, and the same arrival goes through.
                    helper.assertValueEqual(bay.drain(1, false).getAmount(), 1, "one millibucket back out");
                    FluidCensus.change(fluid, LAVA, -1);
                    helper.assertValueEqual(bay.millibuckets(), ONE_BUCKET_SHORT, "exactly one bucket of room");
                    helper.assertFalse(bay.noRoomForAWholeBucket(), "which is room for a bucket");
                    helper.assertFalse(goggleKeys(bay).contains(noRoom),
                            "so the gold line is gone: " + goggleKeys(bay));
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), COPPER_CAPACITY,
                        "and the bay is filled exactly to the brim"))
                .thenExecute(() -> ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "with the empty bucket shelved beside the one that did not fit"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with both trips finished");
                    helper.assertFalse(goggleKeys(bay).contains(noRoom),
                            "a full bay says nothing about buckets not fitting: its contents row says 64 of 64");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * An <b>empty</b> bucket is never planned into a fluid bay, in either of the two states a bay can be in: dedicated
     * to a fluid, and unfiltered with nothing in it yet.
     * <p>
     * This is the churn loop, closed <b>structurally</b> rather than by a rule: if a bay accepted empty containers, the
     * warehouse would carry an empty bucket to the bay, the bay would have to fill it, the crane would reroute a filled
     * one into a rack and the store plan would carry it straight back — for ever, with no player involved. The gate
     * asks what the arriving container <i>carries</i>, and an empty one carries nothing, so there is nothing to
     * forbid.
     * <p>
     * The unfiltered half is the one worth running: that bay accepts a container of <b>any</b> fluid, so it is the one
     * state in which a careless gate would have said yes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void anEmptyBucketIsNeverPlannedIntoAFluidBay(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        FluidBayBlockEntity bay = bayAt(helper, bayPos);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "nothing is exchanged in this test at all"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    helper.assertTrue(bay.dedicatedFluid().isEmpty(),
                            "an empty unfiltered bay takes a container of any fluid");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    arrive(aisle, items, fluid, EMPTY_BUCKET, null, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 4L,
                        "empty buckets are ordinary stock, in the chest"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), 0, "and the unfiltered bay is still empty");
                    helper.assertTrue(bay.dedicatedFluid().isEmpty(), "and still undecided");
                    // The other state: a bay a player dedicated to lava and filled by hand.
                    helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "a lava bucket goes in the filter slot");
                    bay.fill(new FluidStack(Fluids.LAVA, BUCKET_MB), false);
                    FluidCensus.change(fluid, LAVA, BUCKET_MB);
                    arrive(aisle, items, fluid, EMPTY_BUCKET, null, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 8L,
                        "and a dedicated bay refuses them too: all eight are in the chest"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                            "the bay neither took a bucket nor filled one");
                    helper.assertValueEqual(aisle.controller().countOf(EMPTY_BUCKET), 8L,
                            "eight empty buckets, counted once each, as stock");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * A fluid bay <b>replaced</b> by a warehouse interface carrying the very same filter item: the interface's filter
     * must be read as the <b>item</b> filter it is.
     * <p>
     * This is a trap in the store-settings cache rather than in the gate, and it is the reason the cache's fast path
     * asks whether the location is still the same <i>kind</i> of location. The cache skips rebuilding a resolved
     * Create filter while the stack in the slot is unchanged — which is right, because resolving one allocates per
     * entry and runs on every refresh path — and a lava bucket in a fluid bay's slot is byte for byte the same stack
     * as a lava bucket in an interface's slot. But a fluid location's slot is deliberately <b>never</b> resolved into
     * a {@code FilterItemStack} (D6), so the entry carries none; taken over unchanged, that interface would have read
     * as "accepts everything" and the warehouse would have stored cobblestone into a chest the player dedicated to
     * lava buckets — permanently, because ADR-021 never re-shuffles.
     * <p>
     * The replaced location stands <b>nearer</b> the input than the plain chest, so the mistake would be visible:
     * cobblestone would go to the nearer, supposedly unfiltered one. Correctly it travels past it to the plain chest,
     * and a lava bucket stops at the dedicated one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void anInterfaceThatReplacedAFluidBayKeepsItsItemFilter(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        // The bay stands where the dedicated interface will stand: nearest to the input.
        helper.setBlock(aisle.rackPos(CHEST_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(CHEST_RACK)));
        aisle.build(true);
        aisle.storage(BAY_RACK);
        aisle.input(INPUT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(CHEST_RACK));
        helper.assertTrue(bay.setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "the bay is dedicated to lava");
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertConserved(helper, items, fluid,
                "no container is ever exchanged in this test"));

        helper.startSequence()
                // Read into the cache first: without that the replacement would be the cache's first read of the
                // position and the trap could not spring at all.
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    helper.setBlock(aisle.rackPos(CHEST_RACK), Blocks.AIR);
                    aisle.storage(CHEST_RACK);
                    aisle.setStoreFilter(CHEST_RACK, new ItemStack(Items.LAVA_BUCKET));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                // The cache's own answer, which is where the mistake would be: an interface with a lava bucket in its
                // slot carries a store filter the planner consults. A fluid bay carries none — its slot is read as a
                // fluid — so this is false before the replacement and must be true after it.
                .thenExecute(() -> helper.assertTrue(aisle.controller().isStorageFiltered(CHEST_RACK),
                        "the interface's item filter was really resolved, rather than taken over from the bay"))
                .thenExecute(() -> arrive(aisle, items, fluid, ORDINARY, null, 4))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(BAY_RACK, ORDINARY), 4L,
                        "cobblestone travels past the dedicated chest to the plain one"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, ORDINARY), 0L,
                            "and none of it is in the chest the player dedicated to lava buckets");
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 1L,
                        "while a lava bucket stops at it, as an item: there is no fluid bay here any more"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "with both trips finished");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    /**
     * An aisle with a motor, a chest-backed storage location <b>nearer</b> to the input than a copper fluid bay, and an
     * input station.
     *
     * @param dedicated puts a lava bucket in the bay's filter slot, so the bay is dedicated to lava from the start
     */
    private static AisleFixture warehouse(GameTestHelper helper, boolean dedicated) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        placeBay(helper, aisle);
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        if (dedicated)
            helper.assertTrue(bayAt(helper, aisle.rackPos(BAY_RACK))
                    .setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "a lava bucket goes in the filter slot");
        return aisle;
    }

    /** The same aisle with <b>no</b> shelf: its only storage location is the lava-dedicated fluid bay. */
    private static AisleFixture bayOnlyWarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        placeBay(helper, aisle);
        aisle.build(true);
        aisle.input(INPUT_RACK);
        helper.assertTrue(bayAt(helper, aisle.rackPos(BAY_RACK))
                .setStoreFilter(new ItemStack(Items.LAVA_BUCKET)), "a lava bucket goes in the filter slot");
        return aisle;
    }

    private static void placeBay(GameTestHelper helper, AisleFixture aisle) {
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
    }

    /**
     * {@code count} items arrive at the input, counted in both censuses.
     *
     * @param contents the fluid one of them carries, or {@code null} for an item that carries none
     */
    private static void arrive(AisleFixture aisle, Map<ItemKey, Long> items, Map<FluidKey, Long> fluid,
            ItemKey container, FluidKey contents, int count) {
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), container.toStack(count));
        ItemCensus.change(items, container, count);
        if (contents != null)
            FluidCensus.change(fluid, contents, (long) count * BUCKET_MB);
    }

    /**
     * Empties the bay and takes it away at the end of a test, so that the teardown of a bay with fluid in it does not
     * report the one loss this mod allows (D7) in a passing run's log.
     */
    private static void retire(GameTestHelper helper, AisleFixture aisle, FluidBayBlockEntity bay,
            Map<FluidKey, Long> fluid) {
        int held = bay.millibuckets();
        FluidKey stored = bay.storedFluidOrNull();
        BlockPos pos = aisle.rackPos(BAY_RACK);
        Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        helper.setBlock(pos, Blocks.AIR);
        if (stored != null)
            FluidCensus.change(fluid, stored, -held);
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

    /** The lang keys of the bay's own goggle rows, in the order they are drawn. */
    private static List<String> goggleKeys(FluidBayBlockEntity bay) {
        List<String> keys = new ArrayList<>();
        for (LangBuilder row : bay.ownGoggleRows()) {
            Component component = row.component();
            keys.add(component.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                    : "<literal> " + component.getString());
        }
        return keys;
    }
}
