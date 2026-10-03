package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneActivity;
import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.core.crane.ThroughputWindow;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of what a crane <b>got done</b>: the rolling minute the dock measures and shows on its goggles (M25, issue
 * #16, ADR-039, {@code docs/stacker-crane.md} §9).
 * <p>
 * The arithmetic of the window itself is pure Java and pinned by {@code ThroughputWindowTest},
 * {@code CraneActivityTest} and {@code CraneTickMotionTest}. What can only be checked with a world running is here:
 * <ul>
 * <li><b>The six buckets really add up</b> over a real store job, and the job it counted is the job that happened: one
 * trip, exactly the items that were delivered, time spent travelling and time spent at a rack.</li>
 * <li><b>A crane that turns books turning</b>, with a corner count, on a warehouse that bends — and a straight one
 * books none at all, which is what makes the turning term worth leaving off its goggle line.</li>
 * <li><b>A parked crane books idle and nothing else</b>, so its tooltip stays what it was before this feature existed;
 * cutting its rotation books paused, not idle.</li>
 * <li><b>Nothing is synced while nobody is watching.</b> The dock's update tag carries no throughput at all until a
 * player really looks at it through goggles, and what it then carries is exactly the record the server holds.</li>
 * <li><b>The controller shows the dock's own record</b>, fresh in the tick a player looks at it, and its summary
 * still fits its own byte bound with the ten ints in it.</li>
 * <li><b>A reload credits no trip</b>, however the saved state has to be sanitised to be consistent again.</li>
 * </ul>
 * Goggle <i>lines</i> are deliberately not built here: {@code LangBuilder#forGoggles} measures
 * {@code Minecraft.getInstance().font}, which a dedicated server has not got. The real tooltip is read by
 * {@code runVisualTest -Pwareworks.visualTest=corner}.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CraneThroughputGameTests {
    private static final int TIMEOUT_TICKS = 2400;
    private static final int TEST_RPM = 128;
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    /** Iron fed through the input. */
    private static final int BATCH = 16;
    /** Long enough for a parked crane to fill several buckets of the ring and for the observer throttle to open. */
    private static final int PARK_TICKS = 50;
    /** Ticks a reloaded dock is given to run its first tick and resume its saved state. */
    private static final int RESUME_TICKS = 4;
    /**
     * Bound of the dock's update tag, the same one {@code CraneJobGameTests} guards (that constant is private to its
     * own holder). The throughput adds one {@code int[10]}, about 44 accounting bytes.
     */
    private static final int MAX_UPDATE_TAG_BYTES = 8192;
    /**
     * What one {@code int[10]} costs inside a {@link CompoundTag}, by {@code CompoundTag#sizeInBytes}'s own
     * accounting: 28 + 2 x 10 for the key {@code Throughput}, 36 for the entry and 24 + 4 x 10 for the array. The
     * measurement's price on the controller's summary is exactly this and nothing else, which the test below asserts
     * rather than approximates.
     */
    private static final int THROUGHPUT_TAG_BYTES = 148;
    /**
     * Bound of the controller's whole client packet. It is the dock's 8192 and deliberately <b>not</b> the 2048 that
     * {@code WarehouseControllerGameTests#controllerGoggleSummary} asserts: that number holds for the summary of a
     * controller whose crane has <b>no job</b>, which is what that test builds, and a measured summary of a working
     * warehouse is about 3.4 kB of {@code sizeInBytes} accounting with or without the measurement — a crane job alone
     * is some 1.4 kB of it. {@code sizeInBytes} charges 28 + 2 x key length + 36 per entry, so it is a Java-heap
     * estimate many times the real wire size; what matters here is that the measurement adds a fixed
     * {@value #THROUGHPUT_TAG_BYTES} and the packet stays nowhere near a limit.
     */
    private static final int MAX_CONTROLLER_UPDATE_TAG_BYTES = 8192;

    private static final RackPosition STORE_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);

    /**
     * Save keys of the crane state, spelled out because {@code CranePersistence} is package-private in
     * {@code content.crane} — the same thing {@code CraneJobGameTests} does for the handling head.
     */
    private static final String STATE_TAG = "Crane";
    private static final String PHASE_TAG = "Phase";
    /** Packet key of the throughput inside the dock's goggle tag. */
    private static final String THROUGHPUT_TAG = "Throughput";

    // --- the corner fixture (its own L, like CraneCornerGameTests) -------------------------------------------------

    private static final BlockPos CORNER_CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos CORNER_DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos CORNER_MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);
    /** Rails of aisle A: x = 2..5 at z = 3; position 4 is the corner block. */
    private static final int CORNER_FIRST_RAILS = 4;
    /** Rails of aisle B, running south out of the corner. */
    private static final int CORNER_SECOND_RAILS = 3;
    private static final BlockPos CORNER_BLOCK = new BlockPos(1 + CORNER_FIRST_RAILS, BASE_Y, AISLE_Z);
    private static final BlockPos CORNER_INPUT = new BlockPos(2, BASE_Y, 2);
    /** The rack at the far end of aisle B, with its chest behind it: only a quarter turn reaches it. */
    private static final BlockPos CORNER_FAR_RACK = new BlockPos(4, BASE_Y, 6);
    private static final BlockPos CORNER_FAR_CHEST = new BlockPos(3, BASE_Y, 6);

    private CraneThroughputGameTests() {
    }

    // --- (a) the buckets add up over a real job -------------------------------------------------------------------

    /**
     * A real store job, measured: the six buckets sum to the ticks that were observed, the machine really spent time
     * travelling and standing at the rack, and the job it finished is counted once with exactly the items it carried.
     * <p>
     * The sum is the property the whole feature rests on — the six are mutually exclusive, so a classifier that let a
     * tick fall into two buckets or into none would make every share on the tooltip a different kind of lie.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void cranethroughputcountsarealjob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the throughput is measured"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.dock().throughput().isEmpty(),
                            "a dock that has not moved has nothing to show");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(BATCH));
                    ItemCensus.change(conserved, IRON, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORE_RACK, IRON), (long) BATCH,
                        "the iron was stored"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    CraneThroughput measured = aisle.dock().throughput();
                    assertBucketsAddUp(helper, measured);
                    helper.assertTrue(measured.observedTicks() > 0, "the window observed ticks");
                    helper.assertFalse(measured.isEmpty(), "a crane that did a job has something to show");
                    helper.assertTrue(measured.travelTicks() > 0, "it drove to the input and on to the rack");
                    helper.assertTrue(measured.stopTicks() > 0, "it stood at a source and at a target");
                    helper.assertValueEqual(measured.turnTicks(), 0, "a straight aisle never turns");
                    helper.assertValueEqual(measured.corners(), 0, "and takes no corners");
                    helper.assertValueEqual(measured.trips(), 1, "one job completed");
                    helper.assertValueEqual(measured.items(), BATCH, "exactly the items it delivered");
                    helper.assertTrue(measured.busyShare() > 0, "a share of the window was work");
                    helper.assertTrue(measured.busyShare() <= CraneThroughput.FULL_SHARE, "and never more than all");
                    // The job takes well under a minute, so the window must still name its real length rather than
                    // claim a minute it has not run: the warm-up rule every share depends on.
                    helper.assertFalse(measured.isFullMinute(), "the window is not a full minute yet");
                    helper.assertTrue(
                            measured.observedSeconds() > 0 && measured.observedSeconds() <= ThroughputWindow.BUCKETS,
                            "the observed length is a sane number of seconds, but was " + measured.observedSeconds());
                })
                .thenSucceed();
    }

    // --- (b) a turning crane books turning ------------------------------------------------------------------------

    /**
     * A warehouse that bends: the crane drives to the corner, swings a quarter turn and carries on down aisle B — and
     * the window books that swing as turning, with a corner count derived from the quarter turns it really swung
     * rather than estimated.
     * <p>
     * With {@code aisle.maxBranches = 1} — the off switch that reproduces 0.5.0 — the second aisle is not part of the
     * warehouse at all, so there is nothing to turn for and the test asserts exactly that instead.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranethroughputbooksaturnatacorner(GameTestHelper helper) {
        buildCorner(helper);
        placeCornerStorage(helper);
        helper.setBlock(CORNER_INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));
        if (WareworksConfig.maxBranches() <= 1) {
            assertAStraightWarehouseNeverTurns(helper);
            return;
        }
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the crane turns a corner"));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(cornerDock(helper).isControllerLinked(), "the dock is linked"))
                .thenExecute(() -> {
                    ItemHandlerHelper.insertItem(handlerAt(helper, CORNER_INPUT), IRON.toStack(BATCH), false);
                    ItemCensus.change(conserved, IRON, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(handlerAt(helper, CORNER_FAR_CHEST), IRON),
                        (long) BATCH, "the iron was stored round the corner"))
                // The drop is not the end of the job: the arm still has to retract before the machine reports a
                // finished trip, so the counts are read once the window really holds one.
                .thenWaitUntil(() -> helper.assertValueEqual(cornerDock(helper).throughput().trips(), 1,
                        "the finished job was counted as one trip"))
                .thenExecute(() -> {
                    CraneThroughput measured = cornerDock(helper).throughput();
                    assertBucketsAddUp(helper, measured);
                    helper.assertTrue(measured.turnTicks() > 0,
                            "the swing was booked as turning, but turn ticks are " + measured.turnTicks());
                    helper.assertTrue(measured.corners() > 0,
                            "and counted as at least one corner, but corners are " + measured.corners());
                    helper.assertTrue(measured.travelTicks() > 0, "it drove as well as turned");
                    helper.assertTrue(measured.turnShare() > 0, "the turning share is worth printing");
                    helper.assertValueEqual(measured.items(), BATCH, "and carried exactly the items it delivered");
                })
                .thenSucceed();
    }

    /** What the test above asserts instead while corners are switched off: one aisle, and nothing ever yaws. */
    private static void assertAStraightWarehouseNeverTurns(GameTestHelper helper) {
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(cornerDock(helper).isControllerLinked(), "the dock is linked"))
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    ItemHandlerHelper.insertItem(handlerAt(helper, CORNER_INPUT), IRON.toStack(BATCH), false);
                    ItemCensus.change(conserved, IRON, BATCH);
                })
                .thenExecuteAfter(PARK_TICKS, () -> {
                    CraneThroughput measured = cornerDock(helper).throughput();
                    assertBucketsAddUp(helper, measured);
                    helper.assertValueEqual(measured.turnTicks(), 0, "with one aisle there is nothing to turn for");
                    helper.assertValueEqual(measured.corners(), 0, "and no corner to take");
                    ItemCensus.assertEquals(helper, conserved, "with corners switched off");
                })
                .thenSucceed();
    }

    // --- (c) a parked crane books idle ----------------------------------------------------------------------------

    /**
     * A powered crane with nothing to do books <b>idle</b>, which is not work — so its window stays
     * {@link CraneThroughput#isEmpty() empty} and its goggle tooltip keeps exactly the lines it had before this
     * feature existed. Cutting its rotation books <b>paused</b>, which is not work either, and the pause line already
     * says so.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void cranethroughputofaparkedcrane(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORE_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenExecuteAfter(PARK_TICKS, () -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    CraneThroughput measured = dock.throughput();
                    assertBucketsAddUp(helper, measured);
                    helper.assertTrue(measured.idleTicks() > 0, "a crane with no job books idle ticks");
                    helper.assertValueEqual(measured.workingTicks(), 0, "and does no work");
                    helper.assertValueEqual(measured.blockedTicks(), 0, "and is not blocked either: it has no job");
                    helper.assertValueEqual(measured.trips(), 0, "no trips");
                    helper.assertValueEqual(measured.items(), 0, "no items");
                    helper.assertValueEqual(measured.idleTicks() + measured.pausedTicks(), measured.observedTicks(),
                            "every observed tick was idle or paused");
                    helper.assertTrue(measured.isEmpty(), "so there is nothing worth a goggle line");
                    // And nothing goes on the wire however long a player stares at it.
                    dock.onGoggleObserved();
                    helper.assertFalse(syncedThroughput(helper, dock).isPresent(),
                            "an idle window is never synced");
                    aisle.motor().generatedSpeed.setValue(0);
                })
                .thenExecuteAfter(PARK_TICKS, () -> {
                    CraneThroughput measured = aisle.dock().throughput();
                    assertBucketsAddUp(helper, measured);
                    helper.assertTrue(measured.pausedTicks() > 0, "a crane without rotation books paused ticks");
                    helper.assertValueEqual(measured.workingTicks(), 0, "and still does no work");
                    helper.assertTrue(measured.isEmpty(), "so there is still nothing worth a goggle line");
                })
                .thenSucceed();
    }

    // --- (d) nothing is synced while nobody is watching -----------------------------------------------------------

    /**
     * The dock syncs its throughput <b>only while a player really looks at it through goggles</b>, and that is the
     * whole cost of the feature for everybody else: a working crane publishes several times a second anyway, and those
     * packets carry not one extra byte until somebody is watching.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void dockthroughputsyncsonlywhileobserved(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the dock is watched"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(BATCH));
                    ItemCensus.change(conserved, IRON, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORE_RACK, IRON), (long) BATCH,
                        "the iron was stored"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertFalse(dock.throughput().isEmpty(), "the window holds a finished job");
                    helper.assertFalse(dock.goggleInfo().throughput().isPresent(),
                            "the published goggle data never carries it on its own");
                    helper.assertFalse(syncedThroughput(helper, dock).isPresent(),
                            "and an unwatched dock syncs nothing of it");
                    int quiet = updateTagBytes(helper, dock);

                    dock.onGoggleObserved();
                    CraneThroughput expected = dock.throughput();
                    helper.assertValueEqual(syncedThroughput(helper, dock), Optional.of(expected),
                            "a watched dock syncs exactly the record the server holds");
                    int watched = updateTagBytes(helper, dock);
                    helper.assertTrue(watched > quiet,
                            "which is the only case in which the packet grows at all (" + quiet + " -> " + watched
                                    + " bytes)");
                    helper.assertTrue(watched < MAX_UPDATE_TAG_BYTES,
                            "the dock's update tag stays small, but has " + watched + " bytes");

                    // A client copy of that packet reads the same numbers back, which is what the tooltip draws.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    StackerCraneBlockEntity client = WareworksBlockEntityTypes.STACKER_CRANE.create(dock.getBlockPos(),
                            dock.getBlockState());
                    if (client == null) {
                        helper.fail("could not create a detached stacker crane");
                        return;
                    }
                    client.handleUpdateTag(dock.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.goggleInfo().throughput(), Optional.of(expected),
                            "and a client reads the throughput back");
                })
                .thenSucceed();
    }

    // --- (e) the controller carries the same record -----------------------------------------------------------------

    /**
     * The warehouse controller's goggles show the same measurement, and it is the <b>dock's own</b> one: its summary
     * carries whatever {@code dock.throughput()} said in the tick a player looked at it, not a copy that can age
     * (M25, issue #16, ADR-039, {@code docs/stacker-crane.md} §9.6).
     * <p>
     * The two ends of that are both asserted here. Before the crane has done anything the summary carries <b>no</b>
     * measurement at all, because an empty window is normalised away — so a controller of a warehouse whose crane is
     * parked syncs byte for byte what it synced in 0.7.0. After a real job it carries exactly the dock's record, a
     * detached client fed the real packet reads the same numbers back, and the measurement's cost in that packet is
     * the {@value #THROUGHPUT_TAG_BYTES} bytes of one {@code int[10]} and not a byte more — measured by writing the
     * very same crane record twice, once with the measurement and once with it taken out.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerthroughputisthedocksown(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the controller is watched"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    controller.onGoggleObserved();
                    helper.assertTrue(controller.summary().crane().isPresent(), "the summary carries the linked dock");
                    helper.assertFalse(summaryThroughput(controller).isPresent(),
                            "but no measurement, because the crane has done nothing");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(BATCH));
                    ItemCensus.change(conserved, IRON, BATCH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORE_RACK, IRON), (long) BATCH,
                        "the iron was stored"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().throughput().trips(), 1,
                        "the finished job was counted as one trip"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    controller.onGoggleObserved();
                    CraneThroughput expected = aisle.dock().throughput();
                    helper.assertFalse(expected.isEmpty(), "the dock's window holds a finished job");
                    helper.assertValueEqual(summaryThroughput(controller), Optional.of(expected),
                            "the controller shows exactly the dock's record, in the same tick");

                    CraneGoggleInfo measured = controller.summary().crane().orElse(null);
                    if (measured == null) {
                        helper.fail("the observed summary lost its crane");
                        return;
                    }
                    helper.assertValueEqual(tagBytes(measured) - tagBytes(measured.withThroughput(null)),
                            THROUGHPUT_TAG_BYTES, "what the measurement costs in the packet");
                    int watched = updateTagBytes(helper, controller);
                    helper.assertTrue(watched < MAX_CONTROLLER_UPDATE_TAG_BYTES,
                            "the controller's packet stays small, but has " + watched + " bytes");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity client = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(controller.getBlockPos(), controller.getBlockState());
                    if (client == null) {
                        helper.fail("could not create a detached warehouse controller");
                        return;
                    }
                    client.handleUpdateTag(controller.getUpdateTag(registries), registries);
                    helper.assertValueEqual(summaryThroughput(client), Optional.of(expected),
                            "and a client reads it back off the real packet");
                })
                .thenSucceed();
    }

    /** The measurement inside a controller's goggle summary, which rides in the dock's record it already carried. */
    private static Optional<CraneThroughput> summaryThroughput(WarehouseControllerBlockEntity controller) {
        return controller.summary().crane().flatMap(CraneGoggleInfo::throughput);
    }

    private static int updateTagBytes(GameTestHelper helper, WarehouseControllerBlockEntity controller) {
        return controller.getUpdateTag(helper.getLevel().registryAccess()).sizeInBytes();
    }

    /** What one crane record costs written out, so two of them can be compared to price the measurement exactly. */
    private static int tagBytes(CraneGoggleInfo info) {
        CompoundTag tag = new CompoundTag();
        info.write(tag);
        return tag.sizeInBytes();
    }

    // --- (f) a reload credits no trip -----------------------------------------------------------------------------

    /**
     * A saved state that has to be <b>sanitised</b> to be consistent again must not be counted as work this machine
     * did: {@code CraneStateMachine#resume} can complete a job that was already delivered before the save, and
     * without the guard around it the window of a freshly loaded dock would open with "1 trip" on a machine that has
     * not moved a block since the world opened.
     * <p>
     * The state is crafted the one way it can arise — a job in {@code IDLE}, which the live machine never produces
     * ({@code CraneState#isConsistent} forbids it) and an edited or foreign save can — by saving the dock in the tick
     * its job was fully delivered and rewriting the phase.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void cranethroughputcreditsnotripforaresumedjob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        AtomicReference<CompoundTag> saved = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    TransportJob<ItemKey, RackPosition> job = dock.currentJob().orElse(null);
                    helper.assertTrue(job != null && job.deliveredAmount() == BATCH && dock.heldItems().isEmpty(),
                            "the crane delivered everything and still holds the job");
                    saved.set(dock.saveWithFullMetadata(helper.getLevel().registryAccess()));
                })
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    CompoundTag tag = saved.get();
                    tag.getCompound(STATE_TAG).putString(PHASE_TAG, "IDLE");
                    BlockEntity loaded = BlockEntity.loadStatic(dock.getBlockPos(), dock.getBlockState(), tag,
                            helper.getLevel().registryAccess());
                    if (!(loaded instanceof StackerCraneBlockEntity reloaded)) {
                        helper.fail("a saved stacker crane must load again as one");
                        return;
                    }
                    helper.getLevel().setBlockEntity(reloaded);
                    helper.assertTrue(aisle.dock() != dock, "the dock block entity was replaced");
                })
                .thenExecuteAfter(RESUME_TICKS, () -> {
                    CraneThroughput measured = aisle.dock().throughput();
                    helper.assertValueEqual(measured.trips(), 0,
                            "resuming a saved state credits no trip, but credited " + measured.trips());
                    helper.assertValueEqual(measured.items(), 0, "and no items");
                    assertBucketsAddUp(helper, measured);
                })
                .thenSucceed();
    }

    // --- shared assertions ----------------------------------------------------------------------------------------

    /**
     * The invariant the whole feature rests on: the six buckets are mutually exclusive and exhaustive, so they sum to
     * exactly the ticks the window observed, and the window never holds more than the minute it covers.
     */
    private static void assertBucketsAddUp(GameTestHelper helper, CraneThroughput measured) {
        int sum = 0;
        for (CraneActivity activity : CraneActivity.values())
            sum += measured.ticks(activity);
        helper.assertValueEqual(sum, measured.observedTicks(), "the six buckets sum to the observed ticks");
        helper.assertTrue(measured.observedTicks() <= ThroughputWindow.WINDOW_TICKS,
                "the window never holds more than a minute, but holds " + measured.observedTicks() + " ticks");
        helper.assertValueEqual(measured.workingTicks(),
                measured.travelTicks() + measured.turnTicks() + measured.stopTicks(), "working ticks");
    }

    /** The throughput inside the dock's real client packet, as a client would read it. */
    private static Optional<CraneThroughput> syncedThroughput(GameTestHelper helper, StackerCraneBlockEntity dock) {
        CompoundTag goggles = dock.getUpdateTag(helper.getLevel().registryAccess())
                .getCompound(StackerCraneBlockEntity.GOGGLE_TAG);
        if (!goggles.contains(THROUGHPUT_TAG, Tag.TAG_INT_ARRAY))
            return Optional.empty();
        return CraneThroughput.unpack(goggles.getIntArray(THROUGHPUT_TAG));
    }

    private static int updateTagBytes(GameTestHelper helper, StackerCraneBlockEntity dock) {
        return dock.getUpdateTag(helper.getLevel().registryAccess()).sizeInBytes();
    }

    // --- the corner fixture ---------------------------------------------------------------------------------------

    /** Motor, dock, controller, aisle A's rails and aisle B running south out of the corner block. */
    private static void buildCorner(GameTestHelper helper) {
        helper.setBlock(CORNER_MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(CORNER_DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        for (int x = 1; x <= CORNER_FIRST_RAILS; x++)
            helper.setBlock(CORNER_DOCK.east(x), WarehouseRailBlock.along(Direction.Axis.X));
        for (int z = 1; z <= CORNER_SECOND_RAILS; z++)
            helper.setBlock(CORNER_BLOCK.south(z), WarehouseRailBlock.along(Direction.Axis.Z));
        helper.setBlock(CORNER_CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        cornerMotor(helper).generatedSpeed.setValue(TEST_RPM);
    }

    /** The one storage location of the corner fixture: a chest behind an interface at the far end of aisle B. */
    private static void placeCornerStorage(GameTestHelper helper) {
        helper.setBlock(CORNER_FAR_CHEST, Blocks.CHEST);
        helper.setBlock(CORNER_FAR_RACK, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.WEST));
    }

    private static StackerCraneBlockEntity cornerDock(GameTestHelper helper) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(helper.getLevel(),
                helper.absolutePos(CORNER_DOCK));
        if (be == null) {
            helper.fail("missing stacker crane dock", CORNER_DOCK);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static CreativeMotorBlockEntity cornerMotor(GameTestHelper helper) {
        CreativeMotorBlockEntity be = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(),
                helper.absolutePos(CORNER_MOTOR));
        if (be == null) {
            helper.fail("missing creative motor", CORNER_MOTOR);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            throw new AssertionError("unreachable");
        }
        return handler;
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
}
