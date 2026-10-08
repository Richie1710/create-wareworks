package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.MAX_FLUID_BAY_SYNC_BYTES;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.simibubi.create.AllSoundEvents;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import dev.wareworks.core.inventory.KeyCount;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the <b>container exchange inside a real crane job</b> ({@code docs/stacker-crane.md} §4 and §6, M30,
 * issue #21, D1): the state machine's fourth answer to a drop, the job that continues under another key, the reroute
 * that shelves the empty container, and what happens when the bay refuses at the last moment or the world is
 * interrupted right after the swap.
 * <p>
 * {@link ContainerExchangeGameTests} covers the primitive alone — one {@code TransferContext}, one
 * {@code InventoryGrabber}, no job. This holder is the layer above it: a standing warehouse, a powered crane, the
 * controller's reports and the reroute ladder.
 *
 * <h2>Why the job is handed to the crane by hand</h2>
 * The store gate that makes the <i>planner</i> choose a fluid bay for a filled container has its own holder
 * ({@code FluidBayStoreGateGameTests}), and the plan is not what these tests are about: everything <b>downstream</b>
 * of it is. {@code StackerCraneBlockEntity#assignJob} is the documented way to hand a crane a job somebody else
 * reserved, so these tests play the controller for one job and then let the real controller take over, which it does
 * from the pick onwards ({@code onCranePicked}, {@code onCraneExchanged}, {@code planReroute},
 * {@code onCraneDelivered}). While the crane has a job the dispatch plans nothing of its own, so the two never race.
 * It is also the only way to reach some of what is tested here at all: the store plan sizes a job by what a bay
 * answers, so a carry of two containers towards a bay that fits one is a state only an already-loaded head can be
 * in.
 *
 * <h2>How conservation is asserted</h2>
 * The <b>fluid</b> census is checked on every tick, because it is invariant through the whole trip: 1 000 mB inside a
 * bucket and 1 000 mB in a tank are the same 1 000 mB to it, so a drop lost anywhere between the input and the bay
 * fails in the tick it happened. The <b>item</b> census changes exactly once, at the exchange, and is therefore asserted
 * at rest on both sides of it — and always through {@link ItemCensus#exchange}, which verifies the declared swap against
 * the game's own emptying routine, so a test cannot declare a swap the game would not make.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ContainerExchangeJobGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 60;
    /** Ticks a test waits before reading {@link LogCapture}: log4j may deliver an event on another thread. */
    private static final int LOG_TICKS = 3;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);
    /** A second bay, for the reroute that must not be offered a carry it would refuse whole. */
    private static final RackPosition SECOND_BAY_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(5, 0, Side.LEFT);
    /**
     * Its own batch, because {@link ConfigOverrides} writes into the loaded config in memory and the tests of one
     * batch run at the same time.
     */
    private static final String CARRY_BATCH = "wareworksfluidbaycarry";

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey EMPTY_BUCKET = ItemKey.of(Items.BUCKET);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);

    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;
    /** A copper bay's shipped capacity in millibuckets. */
    private static final int COPPER_CAPACITY = 64 * BUCKET_MB;
    /** A pre-fill leaving 999 mB of room: a whole bucket does not fit, so the bay takes nothing at all. */
    private static final int ALMOST_FULL = COPPER_CAPACITY - (BUCKET_MB - 1);
    /** Filled buckets in one carry, and the {@code crane.grabberStacks} that allow it (a filled bucket stacks to 1). */
    private static final int CARRY_STACKS = 2;
    /** A pre-fill leaving room for exactly one whole bucket and not two. */
    private static final int ROOM_FOR_ONE = COPPER_CAPACITY - (BUCKET_MB + BUCKET_MB / 2);

    /** The sound a container emptied into a bay makes ({@code CraneSounds.EXCHANGE}). */
    private static final ResourceLocation EXCHANGE_SOUND = SoundEvents.BUCKET_EMPTY.getLocation();
    /** The sound of a real drop, which an exchange stop must <b>not</b> make: nothing was dropped there. */
    private static final ResourceLocation DROP_SOUND = AllSoundEvents.DEPOT_PLOP.getId();

    private ContainerExchangeJobGameTests() {
    }

    // --- the loop the issue decided, as one crane job ---------------------------------------------------------------

    /**
     * The whole decided loop in one trip: a filled lava bucket waits at a warehouse input, the crane carries it to a
     * fluid bay, the bay drains it, and the crane shelves the now-empty bucket in an ordinary chest. The warehouse then
     * holds <b>lava as a fluid and a bucket as stock</b>, which is issue #21's own answer to "what does the warehouse
     * hold".
     * <p>
     * What this pins beyond the swap itself, each of which is a decision rather than a detail:
     * <ul>
     * <li><b>the exchange is not a delivery.</b> The controller's item stock never counts a lava bucket as stored at the
     * bay — it would otherwise credit a restock order with a container that was never stored and trip ADR-027's safety
     * stop for no reason (D14) — and the empty bucket is counted only once it really reaches the chest;</li>
     * <li><b>the reservations follow the new job.</b> Right after the exchange the ledger is exactly what the job the
     * crane now carries reserves, which is what {@code onCraneExchanged}'s {@code track} is for;</li>
     * <li><b>the goggles say what the machine is really carrying</b>: after the exchange the job line names the empty
     * bucket, with no new string and no new phase — the job itself changed, so every surface that reads it follows;</li>
     * <li><b>the stop sounds like a container being emptied</b>, once, at the bay — and the depot plop of a real drop is
     * heard at the chest and nowhere else;</li>
     * <li><b>no phase is new.</b> The trip is an ordinary store job plus one reroute.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayExchangeRunsAsPartOfACraneJob(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid,
                "the fluid is in the bucket or in the tank, never in between"));
        CraneSoundGameTests.SoundRecorder sounds = new CraneSoundGameTests.SoundRecorder(helper);
        UUID job = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    sounds.start();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().canAcceptJob(), "the crane has rotation"))
                .thenExecute(() -> {
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA);
                    FluidCensus.assertConserved(helper, items, fluid, "a filled bucket waiting at the input");
                    assign(helper, aisle, job, LAVA_BUCKET);
                })
                // The two facts are asserted in ONE tick on purpose: while the crane is still carrying, the bay has
                // to say what is on its way. A reservation row at a fluid bay only exists because the exchange does
                // (M30 review fix: the bay kept the summary it was handed and drew none of it).
                .thenWaitUntil(() -> {
                    aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, 1);
                    assertIncomingAtBay(helper, bay, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.millibuckets(), BUCKET_MB,
                        "the bay drained the bucket the crane brought it"))
                .thenExecute(() -> {
                    // The item census changes here and only here: the one move in this mod under which an item
                    // legitimately becomes another one.
                    ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "right after the exchange");

                    StackerCraneBlockEntity dock = aisle.dock();
                    TransportJob<ItemKey, RackPosition> carried = dock.currentJob().orElseThrow();
                    helper.assertValueEqual(carried.id(), job, "the same trip");
                    helper.assertValueEqual(carried.key(), EMPTY_BUCKET, "carrying the container that came back");
                    helper.assertValueEqual(carried.heldAmount(), 1, "one of them");
                    helper.assertValueEqual(dock.heldItems().count(EMPTY_BUCKET), 1, "and the head agrees");
                    helper.assertValueEqual(dock.heldItems().count(LAVA_BUCKET), 0, "nothing filled is left in it");

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.reservations().reservations(),
                            ReservationLedger.reservationsFor(carried),
                            "the reservations are what the job the crane now carries reserves");
                    helper.assertValueEqual(controller.countOf(LAVA_BUCKET), 0L,
                            "a filled container was never stored anywhere");

                    CraneGoggleInfo info = dock.goggleInfo();
                    CraneJobSummary summary = info.job().orElseThrow();
                    helper.assertValueEqual(summary.item(), Items.BUCKET, "the goggles name what is being carried");
                    helper.assertValueEqual(summary.amount(), 1, "and how much of it");
                    helper.assertValueEqual(info.heldCount(), 1L, "the held line says the same");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "the empty bucket is shelved as ordinary stock"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    sounds.stop();
                    FluidCensus.assertConserved(helper, items, fluid,
                            "lava as a fluid and a bucket as stock, in one warehouse");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the bay kept the lava");
                    // The row the bay drew while the crane was driving is gone with the job that booked it. It is
                    // asserted here and not right after the exchange: the job still points AT the bay for the tick
                    // or two until the reroute, and it still reserves there - now for the empty container it carries,
                    // which is the ledger's truth rather than a stale row.
                    bay.onGoggleObserved();
                    helper.assertValueEqual(bay.reservationSummary(), LocationReservationSummary.NONE,
                            "and nothing is reserved at the bay once the trip is over");
                    helper.assertValueEqual(aisle.controller().countOf(EMPTY_BUCKET), 1L,
                            "and the empty bucket is indexed where it really lies");
                    aisle.assertIdleAndEmpty();
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");

                    // The throughput window is the surface on which an exchange reported as a delivery would show:
                    // one bucket arrived somewhere on this trip, so one item was delivered. Two would mean the swap
                    // had been counted as an arrival of its own.
                    CraneThroughput measured = aisle.dock().throughput();
                    helper.assertValueEqual(measured.items(), 1, "the one container that really arrived, counted once");
                    helper.assertValueEqual(measured.trips(), 1, "in one trip");

                    sounds.assertOnceAt(EXCHANGE_SOUND, aisle.absoluteRackPos(BAY_RACK), "container exchange");
                    sounds.assertOnceAt(DROP_SOUND, aisle.absoluteRackPos(CHEST_RACK), "drop");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- the bay refuses at the last moment -------------------------------------------------------------------------

    /**
     * The two ways a stop that was a container exchange when the job started is not one any more by the time the arm is
     * out, both of which the crane has to answer without losing the container it is holding:
     * <ol>
     * <li><b>the bay filled up mid-carry</b> — a pipe at its back, a player's hand — until less than one whole bucket
     * fits. A bucket is all or nothing in both directions ({@code FluidBucketWrapper}), so a bay with 999 mB of room
     * takes <b>nothing</b> from it, which is the case issue #21's own wording gets wrong;</li>
     * <li><b>the container does not match what the bay holds</b> — a water bucket at a bay that has lava in it.</li>
     * </ol>
     * Both are the same answer from the crane's point of view: the exchange is refused, nothing moved, the drop
     * delivered nothing, and the existing reroute ladder shelves the filled container as an ordinary item with the bay
     * excluded as the target that just failed. The crane must <b>not</b> park in front of the bay — a store job never
     * waits at a full target (M17's argument, free here) — and nothing may be dropped on the floor.
     * <p>
     * The assertion that makes this a test of {@code performDrop} rather than of the primitive is the <b>negative</b> one
     * on the log: a refused exchange must not fall back to an item insertion, because a fluid bay answers an insertion
     * by handing the stack back and <b>logging it</b> as the caller error it would be. A bay that filled up is an
     * everyday situation, and reporting it as "something used the wrong operation" would be the wrong diagnosis in the
     * one place a player looks. The line is searched for with this bay's own position in it, so a bay in a test running
     * beside this one cannot answer for it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayExchangeRefusedMidCarryIsRerouted(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        FluidBayBlockEntity bay = bayAt(helper, bayPos);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "while a refused container is carried about"));
        LogCapture[] log = new LogCapture[1];
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    log[0] = LogCapture.ofWarnings();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().canAcceptJob(), "the crane has rotation"))
                .thenExecute(() -> {
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA);
                    assign(helper, aisle, first, LAVA_BUCKET);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, 1))
                .thenExecute(() -> {
                    // A pipe fills the bay while the crane is on its way: the plan was right and is not any more.
                    helper.assertValueEqual(bay.fill(new FluidStack(Fluids.LAVA, ALMOST_FULL), false), ALMOST_FULL,
                            "the bay took the pre-fill");
                    FluidCensus.change(fluid, LAVA, ALMOST_FULL);
                    helper.assertValueEqual(COPPER_CAPACITY - bay.millibuckets(), BUCKET_MB - 1,
                            "leaving less than one whole bucket of room");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 1L,
                        "the filled bucket is shelved as an item instead"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), ALMOST_FULL, "the bay took nothing from the bucket");
                    FluidCensus.assertConserved(helper, items, fluid, "after a bay with 999 mB of room refused");
                    aisle.assertIdleAndEmpty();
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                })
                // Second leg: the container does not match what the bay holds.
                .thenExecute(() -> {
                    arrive(aisle, items, fluid, WATER_BUCKET, WATER);
                    assign(helper, aisle, second, WATER_BUCKET);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, WATER_BUCKET), 1L,
                        "a water bucket at a bay full of lava is shelved as an item too"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), ALMOST_FULL, "and the bay is untouched by it");
                    FluidCensus.assertConserved(helper, items, fluid, "after a bay refused another fluid");
                    aisle.assertIdleAndEmpty();
                    helper.assertFalse(aisle.dock().craneState().phase() == CranePhase.WAITING_FOR_TARGET,
                            "a store job never parks in front of a full target");
                })
                .thenExecuteAfter(LOG_TICKS, () -> {
                    List<String> warnings = log[0].closeAndTake();
                    String line = LogCapture.firstContaining(warnings, "fluid bay", "holds no items",
                            String.valueOf(aisle.absoluteRackPos(BAY_RACK)));
                    helper.assertTrue(line == null,
                            "a refused exchange must not fall back to an item insertion, but the log says: " + line);
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- a reroute never offers a bay part of a carry ---------------------------------------------------------------

    /**
     * <b>Two bays with room for one bucket each, a crane holding two, and a shelf that could take both.</b> The carry
     * must end on the shelf, and neither bay may take anything (M30 review fix, issue #21).
     * <p>
     * An exchange is all or nothing on the <i>whole</i> amount offered, because a handling head holds one item key.
     * The reroute planner, however, answered "this location accepts 1 of your 2" and the crane then offered all 2,
     * which the bay refused — so the trip delivered nothing. And it could not end: a filled container of a bay's own
     * fluid is a <b>dedication</b>, so both bays outrank every shelf, and each reroute excludes only the target that
     * just failed. The crane shuttled from one bay to the other and back for ever with room standing free on the
     * shelf. The planner is therefore told which locations take a carry whole or not at all
     * ({@code PlannerInput.allOrNothing}) and skips such a location unless it takes <b>all</b> of it.
     * <p>
     * It needs a carry of two, so this is the one test in the suite that raises {@code crane.grabberStacks}: a filled
     * bucket stacks to <b>one</b>, so the shipped configuration cannot carry two of them and the defect is
     * unreachable (which is why the single-container test beside it passes either way). A modded container that
     * stacks reaches it at the default.
     * <p>
     * The job is handed over by hand for the class comment's reason, and with an amount of two for one more: the
     * <i>store plan</i> would size a job by what the bay answers and plan one bucket, so only a carry that already
     * exists can reach this path at all.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = CARRY_BATCH)
    public static void aRerouteNeverOffersAFluidBayPartOfACarry(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.grabberStacks, CARRY_STACKS);
        AisleFixture aisle = twoBayWarehouse(helper);
        FluidBayBlockEntity first = bayAt(helper, aisle.rackPos(BAY_RACK));
        FluidBayBlockEntity second = bayAt(helper, aisle.rackPos(SECOND_BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid,
                "while two buckets are carried past two bays that cannot take them"));
        UUID job = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 0))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().canAcceptJob(), "the crane has rotation"))
                .thenExecute(() -> {
                    // Room for one whole bucket in each bay and not two: the exchange of a two-bucket carry is
                    // refused by both, and a shelf is the only place the carry fits.
                    fill(helper, first, fluid, ROOM_FOR_ONE);
                    fill(helper, second, fluid, ROOM_FOR_ONE);
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA);
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA);
                    helper.assertTrue(aisle.dock().assignJob(
                            TransportJob.store(job, INPUT_RACK, BAY_RACK, LAVA_BUCKET, CARRY_STACKS)),
                            "the crane took a job for two filled buckets");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, CARRY_STACKS))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET),
                        (long) CARRY_STACKS, "both filled buckets are shelved as items"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(first.millibuckets(), ROOM_FOR_ONE, "the first bay took nothing");
                    helper.assertValueEqual(second.millibuckets(), ROOM_FOR_ONE, "and neither did the second");
                    FluidCensus.assertConserved(helper, items, fluid,
                            "after a carry no bay could take whole went to a shelf");
                    aisle.assertIdleAndEmpty();
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                    retire(helper, aisle, second, SECOND_BAY_RACK, fluid);
                    retire(helper, aisle, first, BAY_RACK, fluid);
                })
                .thenSucceed();
    }

    /** Restores the carry configuration whatever {@link #aRerouteNeverOffersAFluidBayPartOfACarry} did. */
    @AfterBatch(batch = CARRY_BATCH)
    public static void restoreCarryConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- interruption right after the swap --------------------------------------------------------------------------

    /**
     * The interruption the exchange was designed around: the world is torn down and rebuilt in the tick <b>after</b> the
     * swap, while the crane still holds the empty container and has not shelved it yet. Dock and controller are saved
     * and replaced by fresh block entities loaded from those saves, which is a reload, a chunk round trip and a server
     * restart as far as either of them can tell ({@code CraneJobGameTests.cranePersistenceMidJob}'s own method).
     * <p>
     * There is deliberately <b>no window to catch</b>: {@code InventoryGrabber.exchange} and the state machine's
     * {@code Exchanged} run in one synchronous chain, so no save point, chunk check or tick boundary can land between
     * the fluid arriving in the tank and the empty container arriving in the head. What this test pins is the half after
     * it — that the state which <i>does</i> get saved is a consistent one:
     * <ul>
     * <li>the saved head holds the <b>empty</b> container and the saved job's key is that container, so
     * {@code reconcileHeadWithJob} finds them in step and spills <b>nothing</b> at the dock. A job that had kept the
     * filled container's key would have the empty one thrown on the floor as a stranger;</li>
     * <li>the saved job carries no request, so the reloaded controller rebuilds reservations that promise nobody
     * anything;</li>
     * <li>the trip finishes: the reloaded crane reroutes the container it is holding and shelves it, with the fluid
     * still in the bay and the joint census equal at the end.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidBayExchangeAcrossAnInterruption(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        FluidBayBlockEntity bay = bayAt(helper, aisle.rackPos(BAY_RACK));
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        UUID job = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().canAcceptJob(), "the crane has rotation"))
                .thenExecute(() -> {
                    arrive(aisle, items, fluid, LAVA_BUCKET, LAVA);
                    assign(helper, aisle, job, LAVA_BUCKET);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the exchange happened");
                    helper.assertValueEqual(aisle.dock().currentJob().orElseThrow().key(), EMPTY_BUCKET,
                            "and the job already carries the empty container");
                })
                // The tick after the swap: everything is replaced by what was on disk.
                .thenExecute(() -> {
                    ItemCensus.exchange(helper, items, LAVA_BUCKET, EMPTY_BUCKET, 1);
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = aisle.dock();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);

                    StackerCraneBlockEntity dockCopy = loadCopy(helper, dock, dockTag,
                            StackerCraneBlockEntity.class);
                    helper.assertValueEqual(dockCopy.heldItems().count(EMPTY_BUCKET), 1,
                            "the saved head holds the empty container");
                    helper.assertValueEqual(dockCopy.heldItems().count(LAVA_BUCKET), 0, "and nothing filled");
                    TransportJob<ItemKey, RackPosition> saved = dockCopy.currentJob().orElseThrow();
                    helper.assertValueEqual(saved.key(), EMPTY_BUCKET, "and the saved job names the same item");
                    helper.assertValueEqual(saved.heldAmount(), 1, "with the amount the head really holds");
                    helper.assertValueEqual(saved.requestId(), Optional.<UUID>empty(), "and nobody waiting for it");
                    helper.assertValueEqual(saved.id(), job, "it is still the same trip");

                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    helper.assertTrue(dock.isRemoved() && aisle.dock() != dock, "dock block entity replaced");
                    helper.assertTrue(controller.isRemoved() && aisle.controller() != controller,
                            "controller block entity replaced");
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertTrue(dock.isControllerLinked(), "the reloaded controller linked the dock again");
                    TransportJob<ItemKey, RackPosition> resumed = dock.currentJob().orElseThrow();
                    helper.assertValueEqual(resumed.key(), EMPTY_BUCKET, "the resumed job carries the container");
                    helper.assertValueEqual(aisle.controller().reservations().reservations(),
                            ReservationLedger.reservationsFor(resumed), "and its reservations were adopted from it");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, EMPTY_BUCKET), 1L,
                        "the trip finishes after the interruption"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(),
                            "and nothing was spilled at the dock on the way");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the lava stayed in the bay");
                    FluidCensus.assertConserved(helper, items, fluid, "across the interruption");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /**
     * One aisle with a motor, an empty copper fluid bay, a chest-backed storage location for the containers that come
     * back, and an input station the test feeds. The bay's filter is left unset on purpose — it then takes the first
     * fluid that arrives, which is what makes the "the container does not match" case a statement about the bay's
     * <b>contents</b> rather than about a filter a test wrote.
     */
    private static AisleFixture warehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        return aisle;
    }

    /**
     * The same aisle with a <b>second</b> fluid bay between the first one and the chest, for the reroute test. Both
     * bays are unfiltered, so each takes the first fluid that reaches it and both are dedicated to lava once they
     * hold some.
     */
    private static AisleFixture twoBayWarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        for (RackPosition rack : List.of(BAY_RACK, SECOND_BAY_RACK))
            helper.setBlock(aisle.rackPos(rack), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                    .setValue(FluidBayBlock.FACING, aisle.sideDirection(rack)));
        aisle.build(true);
        aisle.storage(CHEST_RACK);
        aisle.input(INPUT_RACK);
        return aisle;
    }

    /** Pre-fills {@code bay} with lava, counted in the fluid census. */
    private static void fill(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int amount) {
        helper.assertValueEqual(bay.fill(new FluidStack(Fluids.LAVA, amount), false), amount,
                "the bay took the pre-fill");
        FluidCensus.change(fluid, LAVA, amount);
    }

    /**
     * Fails unless {@code bay} says that {@code containers} filled containers are on their way into it — on the
     * server and, after one update tag, on a client. A fluid bay renders that row like every other storage member,
     * and it is the one answer to "why is nothing happening at my bay" while a crane is still driving.
     */
    private static void assertIncomingAtBay(GameTestHelper helper, FluidBayBlockEntity bay, int containers) {
        LocationReservationSummary expected = new LocationReservationSummary(
                List.of(new KeyCount<>(Items.LAVA_BUCKET, (long) containers)), List.of());
        bay.onGoggleObserved();
        helper.assertValueEqual(bay.reservationSummary(), expected, "the bay says what is on its way into it");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag updateTag = bay.getUpdateTag(registries);
        helper.assertTrue(updateTag.sizeInBytes() < MAX_FLUID_BAY_SYNC_BYTES,
                "a bay with a reservation still syncs small, but has " + updateTag.sizeInBytes() + " bytes");
        FluidBayBlockEntity client = WareworksBlockEntityTypes.FLUID_BAY.create(bay.getBlockPos(),
                bay.getBlockState());
        client.handleUpdateTag(updateTag, registries);
        helper.assertValueEqual(client.reservationSummary(), expected, "and a client reads it off the packet");
    }

    /** A filled container arrives at the input, counted in both censuses. */
    private static void arrive(AisleFixture aisle, Map<ItemKey, Long> items, Map<FluidKey, Long> fluid, ItemKey container,
            FluidKey contents) {
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), container.toStack(1));
        ItemCensus.change(items, container, 1);
        FluidCensus.change(fluid, contents, BUCKET_MB);
    }

    /**
     * Hands the crane a store job from the input to the fluid bay, the way the controller's dispatch does
     * ({@code CraneDispatch} reserves first and then assigns) — see the class comment for why the plan is not asked
     * for.
     */
    private static void assign(GameTestHelper helper, AisleFixture aisle, UUID id, ItemKey container) {
        helper.assertTrue(
                aisle.dock().assignJob(TransportJob.store(id, INPUT_RACK, BAY_RACK, container, 1)),
                "the crane took the job");
    }

    /**
     * Empties the bay and takes it away at the end of a test, so that the teardown of a bay with fluid in it does not
     * report the one loss this mod allows (D7) in a passing run's log.
     */
    private static void retire(GameTestHelper helper, AisleFixture aisle, FluidBayBlockEntity bay,
            Map<FluidKey, Long> fluid) {
        retire(helper, aisle, bay, BAY_RACK, fluid);
    }

    /** {@link #retire(GameTestHelper, AisleFixture, FluidBayBlockEntity, Map)} for a bay at a named position. */
    private static void retire(GameTestHelper helper, AisleFixture aisle, FluidBayBlockEntity bay, RackPosition rack,
            Map<FluidKey, Long> fluid) {
        int held = bay.millibuckets();
        FluidKey stored = bay.storedFluidOrNull();
        BlockPos pos = aisle.rackPos(rack);
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

    /** A detached block entity loaded from a save of {@code live}: a reload, as far as that block entity can tell. */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
