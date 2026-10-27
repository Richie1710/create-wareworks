package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestKind;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the <b>fill trip</b> (M31, issue #21, ADR-073): a fill request in the one request queue, one {@code FILL}
 * job that picks empty buckets from a rack, has a lava bay fill them and delivers them to the station that asked — and
 * every way that trip can be interrupted.
 * <p>
 * <b>Conservation.</b> The fluid census is asserted on every tick: a fill moves millibuckets from the bay's tank into
 * the bucket on the head in one synchronous call, so the fluid total never moves at all. The item census changes at
 * every fill — an empty bucket becomes a lava bucket — and is asserted at rest through {@link ItemCensus#fill}, which
 * verifies the declared fill against the routine the bay itself uses. Whatever a test does to the world on purpose —
 * draining the bay, refilling it with water, stuffing a buffer — is declared in the same step.
 * <p>
 * <b>Teeth.</b> Each test names the change to the code it exists to catch; the stage log records that each of them was
 * made once by hand and failed the test.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidRequestGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 40;
    /** Ticks a test waits before reading {@link LogCapture}: log4j may deliver an event on another thread. */
    private static final int LOG_TICKS = 3;
    /** Its own batch: {@link ConfigOverrides} writes into the loaded config, and one batch runs at the same time. */
    private static final String BACKOFF_BATCH = "wareworksfluidfillbackoff";
    /** A back-off far longer than the test waits, so a fill request that armed it would hold storing visibly back. */
    private static final int LONG_BACKOFF_TICKS = 1200;
    /** How long an input may wait for its store job while a fill request lacks an empty container. */
    private static final int STORE_WITHIN_TICKS = 100;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PORT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(5, 0, Side.LEFT);

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey WATER_BUCKET = ItemKey.of(Items.WATER_BUCKET);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;

    private FluidRequestGameTests() {
    }

    // --- 1. the trip ---------------------------------------------------------------------------------------------

    /**
     * A lava bay of ten buckets, ten empty buckets on a shelf, a fill request for three at a terminal: three fill trips,
     * one filled bucket each, three lava buckets in the terminal, 7 000 mB in the bay and seven buckets on the shelf.
     * <p>
     * <b>Teeth:</b> make the fill drop the request ({@code filledFor} → {@code exchangedFor} in
     * {@code CraneStateMachine#exchanged}). The filled bucket is then rerouted, its dedicated bay outranks every shelf,
     * the bay drains it again — and the terminal never receives anything.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillonebucketpertrip(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 10);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 10);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        Set<UUID> trips = new HashSet<>();
        AtomicInteger mostHeld = new AtomicInteger();
        helper.onEachTick(() -> {
            FluidCensus.assertEquals(helper, fluid, "the lava is in the bay or in a bucket, never in between");
            aisle.dock().currentJob().filter(job -> job.type() == JobType.FILL).ifPresent(job -> trips.add(job.id()));
            mostHeld.accumulateAndGet(aisle.dock().heldItems().count(LAVA_BUCKET), Math::max);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 10 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 10L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    RequestResult result = requestFill(aisle, 3);
                    helper.assertTrue(result.isAccepted(), "the fill request is accepted: " + result);
                    helper.assertValueEqual(result.granted(), 3, "for all three buckets");
                    RetrievalRequest<ItemKey, BlockPos> request = result.request().orElseThrow();
                    helper.assertValueEqual(request.kind(), RequestKind.FILL, "as a fill request");
                    helper.assertValueEqual(aisle.controller().availableStock(BUCKET), 7L,
                            "the three empties it needs are promised");
                    helper.assertValueEqual(aisle.controller().availableStock(LAVA_BUCKET), 0L,
                            "and no lava bucket is promised from a shelf");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 3L,
                        "three lava buckets reached the terminal"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 3);
                    FluidCensus.assertConserved(helper, items, fluid, "three buckets filled and delivered");
                    helper.assertValueEqual(bay.millibuckets(), 7 * BUCKET_MB, "the bay gave exactly three buckets");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 7L, "seven empties are left");
                    helper.assertValueEqual(trips.size(), 3, "in three trips");
                    helper.assertValueEqual(mostHeld.get(), 1, "one filled bucket per trip");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "and the request is served");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 2. the head carries one filled bucket ---------------------------------------------------------------------

    /**
     * Sixteen empty buckets fit one grabber stack, sixteen lava buckets do not: with the shipped {@code grabberStacks =
     * 1} every fill trip carries exactly one bucket, both on the way to the bay and on the way to the station.
     * <p>
     * <b>Teeth:</b> leave the filled key's carry limit out of {@code JobPlanner#planFill}'s limit. The planner then sends
     * four empties, the head refuses to take four lava buckets ({@code ExchangeDecision#mayAsk}'s room for the result),
     * the empties go back to a shelf and the request is never served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillheadcarriesonefilledbucket(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 16);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 16);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicInteger mostPlanned = new AtomicInteger();
        AtomicInteger mostEmptyHeld = new AtomicInteger();
        helper.onEachTick(() -> {
            FluidCensus.assertEquals(helper, fluid, "every tick of every trip");
            aisle.dock().currentJob().filter(job -> job.type() == JobType.FILL)
                    .ifPresent(job -> mostPlanned.accumulateAndGet(job.plannedAmount(), Math::max));
            mostEmptyHeld.accumulateAndGet(aisle.dock().heldItems().count(BUCKET), Math::max);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 8 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 8L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertValueEqual(WareworksConfig.grabberStacks(), 1, "the shipped grabber");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 4).isAccepted(), "four buckets of lava");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 4L,
                        "four lava buckets reached the terminal"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(mostPlanned.get(), 1, "no trip was planned for more than one bucket");
                    helper.assertValueEqual(mostEmptyHeld.get(), 1, "and no trip carried more than one empty");
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 4);
                    FluidCensus.assertConserved(helper, items, fluid, "four buckets, four trips");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 3. no empty containers ------------------------------------------------------------------------------------

    /**
     * The fluid is there and no empty container is: the controller says {@link NoJobReason#NO_EMPTY_CONTAINERS}, and an
     * input keeps storing while it does (the reason arms no back-off).
     * <p>
     * <b>Teeth:</b> add {@code NO_EMPTY_CONTAINERS} to the back-off set in {@code CraneDispatch#tick}. With the back-off
     * at {@value #LONG_BACKOFF_TICKS} ticks the iron then waits in the input far longer than
     * {@value #STORE_WITHIN_TICKS} ticks.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BACKOFF_BATCH)
    public static void fluidfillnoemptycontainers(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        aisle.input(INPUT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicReference<Long> fedAt = new AtomicReference<>();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.fullBackoffTicks, LONG_BACKOFF_TICKS);
                    fill(helper, bay, fluid, 4 * BUCKET_MB);
                })
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 4L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "a fill request while a bucket is there");
                    // The player takes the one empty bucket out of the chest before the crane has power.
                    helper.assertValueEqual(aisle.handlerAt(aisle.inventoryPos(CHEST_RACK)).extractItem(0, 1, false)
                            .getCount(), 1, "the empty bucket is taken away");
                    ItemCensus.change(items, BUCKET, -1);
                    aisle.controller().refreshLocation(CHEST_RACK);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().lastPlanReason(),
                        Optional.of(NoJobReason.NO_EMPTY_CONTAINERS), "the controller names the missing container"))
                .thenExecute(() -> {
                    helper.assertFalse(aisle.controller().isBackingOff(helper.getLevel().getGameTime()),
                            "and does not back off");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(8));
                    ItemCensus.change(items, IRON, 8);
                    fedAt.set(helper.getLevel().getGameTime());
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob()
                        .filter(job -> job.type() == JobType.STORE).isPresent(), "the input is served"))
                .thenExecute(() -> helper.assertTrue(
                        helper.getLevel().getGameTime() - fedAt.get() <= STORE_WITHIN_TICKS,
                        "within " + STORE_WITHIN_TICKS + " ticks, although the fill request still lacks a bucket"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, IRON), 8L, "the iron is stored"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().openRequests().size(), 1,
                            "the fill request waits for a container");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing was filled");
                    aisle.controller().cancelRequest(aisle.controller().openRequests().get(0).id());
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    @AfterBatch(batch = BACKOFF_BATCH)
    public static void restoreBackoffConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- 4. the bay emptied by pipes ---------------------------------------------------------------------------------

    /**
     * After the pick the bay is drained down to half a bucket, as a pipe at its back would: the crane arrives with its
     * empty bucket, the fill is refused, the empty goes back onto a shelf and the request stays open. Refilled, the bay
     * serves it.
     * <p>
     * <b>Teeth:</b> remove the simulated drain from {@code FluidBayContext#fill}. The real drain then takes the half
     * bucket that is there, the roll-back puts it back and logs it at {@code ERROR} as a bay contradicting its own
     * simulation — which this test asserts never happens.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillbayemptiedbypipes(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 2);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicReference<LogCapture> log = new AtomicReference<>();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "whatever the pipe did, it was declared"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    log.set(LogCapture.ofWarnings());
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, BUCKET, 1))
                .thenExecute(() -> drain(helper, bay, fluid, 2 * BUCKET_MB - BUCKET_MB / 2))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 2L,
                        "the empty bucket went back onto the shelf"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().openRequests().size(), 1, "the request stays open");
                    helper.assertValueEqual(aisle.controller().openRequests().get(0).remaining(), 1,
                            "with nothing delivered");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 0L, "nothing arrived");
                    FluidCensus.assertConserved(helper, items, fluid, "a refused fill moved nothing");
                    fill(helper, bay, fluid, BUCKET_MB);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 1L,
                        "the refilled bay serves the request"))
                .thenExecuteAfter(LOG_TICKS, () -> {
                    List<String> recorded = log.get().closeAndTake();
                    helper.assertTrue(LogCapture.firstContaining(recorded, "to fill containers with") == null,
                            "no bay contradicted its own simulation: " + recorded);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "served after the refill");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "and the request is served");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 5. the bay refilled with another fluid ----------------------------------------------------------------------

    /**
     * After the pick the bay is emptied and refilled with <b>water</b>: the crane arrives, the bay would hand back a
     * water bucket, which is not what the request asked for, so nothing is filled and the empty is shelved.
     * <p>
     * <b>Teeth:</b> drop the comparison with the expected key in {@code CraneExecution#performFill}. The terminal then
     * receives a water bucket for a request of lava.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillbayrefilledwithanotherfluid(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "every change to the bay was declared"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, BUCKET, 1))
                .thenExecute(() -> {
                    drain(helper, bay, fluid, 2 * BUCKET_MB);
                    helper.assertValueEqual(bay.fill(new FluidStack(Fluids.WATER, 2 * BUCKET_MB), false),
                            2 * BUCKET_MB, "the bay takes water now");
                    FluidCensus.change(fluid, WATER, 2 * BUCKET_MB);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 1L,
                        "the empty bucket went back onto the shelf"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, WATER_BUCKET), 0L,
                            "no water was handed out for lava");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 0L, "and no lava either");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing was filled");
                    helper.assertValueEqual(aisle.controller().openRequests().size(), 1, "the request waits");
                    aisle.controller().cancelRequest(aisle.controller().openRequests().get(0).id());
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 6. cancelled after the fill -------------------------------------------------------------------------------

    /**
     * The request is cancelled while the crane carries the filled bucket to the terminal. Its lava goes back into the
     * bay — a bay dedicated to a fluid outranks every shelf for a container of that fluid (the owner's decision 2) —
     * and the empty bucket goes back onto the shelf. A requesting port stands beside the terminal: the one output a
     * reroute could have handed the bucket to instead.
     * <p>
     * <b>Teeth:</b> route a {@code FILL} reroute to the output stations first in {@code JobPlanner#planReroute}. The
     * port then receives a lava bucket nobody asked for.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillcancelledafterfill(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "out of the bay and back into it"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 3 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 3L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, 1))
                .thenExecute(() -> {
                    helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET_MB, "the fill happened");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.cancelRequest(controller.openRequests().get(0).id()).isPresent(),
                            "the player gives the request up");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.millibuckets(), 3 * BUCKET_MB, "the lava went back into the bay");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 1L, "and the bucket onto its shelf");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, LAVA_BUCKET), 0L, "the port got nothing");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 0L,
                            "and neither did the terminal");
                    FluidCensus.assertConserved(helper, items, fluid, "filled, cancelled, drained back");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 7. the station is full --------------------------------------------------------------------------------------

    /**
     * The terminal fills up while the crane is on its way: the crane waits in front of it holding the filled bucket,
     * and delivers as soon as a player takes a stack out — the wait a retrieve has, for the same reason.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfilloutputfullwaits(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "while the crane waits as well"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, BUCKET, 1))
                .thenExecute(() -> stuff(helper, aisle.terminalAt(TERMINAL_RACK), items))
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.WAITING_FOR_TARGET, LAVA_BUCKET, 1))
                .thenExecute(() -> {
                    ItemStack taken = aisle.terminalAt(TERMINAL_RACK).externalHandler().extractItem(0, 64, false);
                    helper.assertValueEqual(taken.getCount(), 64, "a player takes a stack of stone");
                    ItemCensus.change(items, STONE, -64);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 1L,
                        "the waiting crane delivers"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "waited and delivered");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * The port variant: a requesting warehouse port that fills up is never waited at (M27). The crane is sent back,
     * the bucket's lava goes back into its bay, the empty onto the shelf, and the request stays open; once the port is
     * emptied the request is served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillportfullisdrainedback(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "out of the bay and back into it"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    RequestResult result = aisle.controller().requestFillContainers(aisle.absoluteRackPos(PORT_RACK),
                            LAVA_BUCKET, 1, RequestQueue.NO_AMOUNT_LIMIT, StockAccess.AUTOMATION);
                    helper.assertTrue(result.isAccepted(), "the port asks for one bucket of lava: " + result);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, BUCKET, 1))
                .thenExecute(() -> stuff(helper, aisle.outputAt(PORT_RACK), items))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET_MB, "the lava went back into the bay");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 1L, "and the bucket onto its shelf");
                    helper.assertValueEqual(aisle.dock().currentJob(), Optional.<TransportJob<ItemKey, RackPosition>>empty(),
                            "and the crane is free");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().openRequests().size(), 1, "the request stays open");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing waited at the port");
                    for (int slot = 0; slot < aisle.outputAt(PORT_RACK).externalHandler().getSlots(); slot++) {
                        ItemStack taken = aisle.outputAt(PORT_RACK).externalHandler().extractItem(slot, 64, false);
                        if (!taken.isEmpty())
                            ItemCensus.change(items, ItemKey.of(taken), -taken.getCount());
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, LAVA_BUCKET), 1L,
                        "the emptied port is served"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "served after the port was emptied");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 8. saved mid-trip -------------------------------------------------------------------------------------------

    /**
     * The dock and the controller are saved and loaded again twice in one trip: while the empty bucket is on its way to
     * the bay, and while the filled one is on its way to the terminal. Both times the trip continues — the request is
     * read back as a fill request, the job as a {@code FILL} job with the right key — and the census holds.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillsavedmidtrip(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "across both reloads"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, BUCKET, 1))
                .thenExecute(() -> reload(helper, aisle, BUCKET))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().isControllerLinked(), "linked again"))
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, 1))
                .thenExecute(() -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "filled after the first reload");
                    reload(helper, aisle, LAVA_BUCKET);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET), 1L,
                        "the trip finishes after both reloads"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was spilled");
                    FluidCensus.assertConserved(helper, items, fluid, "across both reloads");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "and the request is served");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 9. the station broken -------------------------------------------------------------------------------------

    /**
     * The terminal is broken while the crane carries the filled bucket to it: the request goes with its station, and the
     * bucket's lava goes back into the bay and the empty onto the shelf.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidfillstationbroken(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 1);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "out of the bay and back into it"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(requestFill(aisle, 1).isAccepted(), "one bucket of lava");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, LAVA_BUCKET, 1))
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(TERMINAL_RACK)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET_MB, "the lava went back into the bay");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 1L, "and the bucket onto its shelf");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "the request went with its station");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
                    FluidCensus.assertConserved(helper, items, fluid, "the station broke mid-trip");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** A warehouse of a copper fluid bay, a chest with {@code emptyBuckets} empty buckets and a terminal. */
    private static AisleFixture warehouse(GameTestHelper helper, int emptyBuckets) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.storage(CHEST_RACK, new ItemStack(Items.BUCKET, emptyBuckets));
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    private static RequestResult requestFill(AisleFixture aisle, int containers) {
        return aisle.controller().requestFillContainers(aisle.absoluteRackPos(TERMINAL_RACK), LAVA_BUCKET, containers,
                RequestQueue.NO_AMOUNT_LIMIT, StockAccess.PLAYER);
    }

    private static void assertLavaIndexed(GameTestHelper helper, AisleFixture aisle, long millibuckets) {
        helper.assertValueEqual(aisle.controller().fluidStockIndex().count(LAVA), millibuckets,
                "the controller has indexed the lava");
    }

    private static void fill(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int amount) {
        helper.assertValueEqual(bay.fill(new FluidStack(Fluids.LAVA, amount), false), amount, "the bay took the lava");
        FluidCensus.change(fluid, LAVA, amount);
    }

    /** What a pipe at the bay's back would do: {@code amount} mB leave the warehouse. */
    private static void drain(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int amount) {
        FluidKey stored = bay.storedFluidOrNull();
        FluidStack drained = bay.drain(amount, false);
        helper.assertValueEqual(drained.getAmount(), amount, "the pipe drained what it asked for");
        FluidCensus.change(fluid, stored, -amount);
    }

    /** Fills every slot of a station's buffer with stone, as a player who leaves things lying there would. */
    private static void stuff(GameTestHelper helper, WarehouseDeliveryStationBlockEntity station,
            Map<ItemKey, Long> items) {
        for (int slot = 0; slot < station.bufferSlots(); slot++) {
            ItemStack rest = station.insert(new ItemStack(Items.STONE, 64), false);
            ItemCensus.change(items, STONE, 64 - rest.getCount());
        }
        helper.assertFalse(station.hasEmptySlot(), "the station is full");
        helper.assertTrue(station.insert(LAVA_BUCKET.toStack(1), true).getCount() == 1, "and takes no lava bucket");
    }

    /**
     * Saves the dock and the controller and replaces both with what was saved, the way a reloaded chunk would.
     *
     * @param carried the key the saved job must carry
     */
    private static void reload(GameTestHelper helper, AisleFixture aisle, ItemKey carried) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        StackerCraneBlockEntity dock = aisle.dock();
        WarehouseControllerBlockEntity controller = aisle.controller();
        CompoundTag dockTag = dock.saveWithFullMetadata(registries);
        CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
        helper.assertValueEqual(controllerTag.getList("Requests", Tag.TAG_COMPOUND).getCompound(0).getString("Kind"),
                RequestKind.FILL.name(), "the saved request is a fill request");

        StackerCraneBlockEntity dockCopy = loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class);
        TransportJob<ItemKey, RackPosition> saved = dockCopy.currentJob().orElseThrow();
        helper.assertValueEqual(saved.type(), JobType.FILL, "the saved job is a fill trip");
        helper.assertValueEqual(saved.key(), carried, "carrying what the head holds");
        helper.assertValueEqual(dockCopy.heldItems().count(carried), 1, "and the saved head agrees");
        helper.assertTrue(saved.requestId().isPresent(), "still serving its request");
        WarehouseControllerBlockEntity controllerCopy = loadCopy(helper, controller, controllerTag,
                WarehouseControllerBlockEntity.class);
        helper.assertValueEqual(controllerCopy.openRequests().get(0).kind(), RequestKind.FILL,
                "the loaded request is a fill request");

        level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
        level.setBlockEntity(loadCopy(helper, controller, controllerTag, WarehouseControllerBlockEntity.class));
        helper.assertTrue(dock.isRemoved() && aisle.dock() != dock, "dock block entity replaced");
        helper.assertTrue(controller.isRemoved() && aisle.controller() != controller, "controller replaced");
    }

    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, AisleFixture aisle) {
        FluidBayBlockEntity be = WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(BAY_RACK)));
        if (be == null) {
            helper.fail("missing fluid bay block entity", aisle.rackPos(BAY_RACK));
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    /** Empties and removes the bay at the end of a test, declaring the fluid it still held. */
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
}
