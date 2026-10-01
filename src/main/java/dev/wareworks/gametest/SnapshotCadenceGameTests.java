package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.warehouse.SnapshotCadence;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The <b>cycle time</b> of the round-robin reconciliation, measured in a running world (M22, issue #2,
 * {@link SnapshotCadence}, {@code docs/warehouse-system.md} §5).
 * <p>
 * A chest a player empties by hand changes silently: no block update, no neighbour hint, nothing the controller can be
 * told about. The only thing that finds such a change is the round robin, so how long it takes to come round to every
 * storage location is how long the warehouse may be wrong about itself. Until M22 that was one location per
 * {@code controller.snapshotIntervalTicks} whatever the size — about nine minutes for a full single aisle and over two
 * hours for the largest warehouse the config allows, growing with every aisle a player adds. Since M22 the count read
 * per interval is scaled to come round within {@code controller.snapshotCycleTicks} instead.
 * <p>
 * <b>Why this is a GameTest and not only a JUnit test.</b> {@code SnapshotCadenceTest} proves the arithmetic; this
 * proves that the controller really reads that many locations, by making <b>every</b> chest of an aisle silently wrong
 * and timing the world until the stock index has found all of them. The two phases of the one test are the before and
 * the after: the same aisle, the same silent change, once at the shipped cycle (one location per interval, exactly what
 * every version up to 0.5.0-alpha did) and once at a cycle short enough to need four — and the second must be measurably
 * faster than the first, which it cannot be if the cadence is ever disconnected from the controller's tick again.
 * <p>
 * <b>Measured</b> (logged by the test itself): eight storage locations, <b>79 ticks</b> at the shipped cycle and
 * <b>20 ticks</b> at a cycle of 20 — the whole warehouse re-read four times as fast, with nothing but the one config
 * value different.
 * <p>
 * One test in its own batch on purpose: it overrides server config ({@link ConfigOverrides}) and measures ticks, so a
 * second test running beside it would see its overrides and its reads.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class SnapshotCadenceGameTests {
    /** Its own batch: the test overrides config and measures ticks (see {@link ConfigOverrides}). */
    static final String CADENCE_BATCH = "wareworkssnapshotcadence";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 10;
    private static final int TIMEOUT_TICKS = 600;

    /** The round-robin interval the measurement runs at — the shipped default, set explicitly so no file decides it. */
    private static final int INTERVAL = 10;
    /** The shipped cycle: ten minutes, which this warehouse is far too small to need more than one read for. */
    private static final int SHIPPED_CYCLE = 12000;
    /**
     * A cycle short enough to need {@code ceil(8 · 10 / 20) = 4} reads per interval, which is also the shipped
     * {@code maxSnapshotsPerTick} ceiling — the per-tick cost a warehouse of ~4800 locations really pays.
     */
    private static final int SHORT_CYCLE = 20;
    private static final int READS_PER_TICK = 4;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    /** Eight storage locations, two apart on both sides so that no two chests can form a double chest. */
    private static final int LEVEL = 1;
    private static final int[] POSITIONS = {1, 3, 5, 7};
    private static final int CHESTS = 2 * POSITIONS.length;
    /** Ticks the eight reads of a one-per-interval round robin cannot be done in. */
    private static final long ONE_PER_INTERVAL_TICKS = (long) (CHESTS - 1) * INTERVAL;

    private SnapshotCadenceGameTests() {
    }

    /**
     * Every chest of an aisle grown by one item in place — the silent change nothing reports — and the stock index
     * timed until it has found all eight, first at the shipped cycle and then at one that needs four reads per
     * interval.
     */
    @GameTest(template = AISLE_16X10X7, batch = CADENCE_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void roundrobincomesroundinsidetheconfiguredcycle(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.snapshotIntervalTicks, INTERVAL);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxSnapshotsPerTick, READS_PER_TICK);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.snapshotCycleTicks, SHIPPED_CYCLE);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        List<BlockPos> chests = new ArrayList<>();
        for (int position : POSITIONS) {
            for (Side side : Side.values())
                chests.add(aisle.storage(RackPosition.of(position, LEVEL, side), IRON.toStack(1)));
        }
        long[] startedAt = new long[1];
        long[] shipped = new long[1];
        long[] shortened = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(CHESTS, 0, 0))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) CHESTS, "one item per chest");
                    helper.assertValueEqual(perInterval(aisle), 1,
                            "a warehouse this size reads one location per interval, as every version before M22 did");
                    growEverything(helper, chests, aisle);
                    startedAt[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), 2L * CHESTS,
                        "the round robin found every silent change at the shipped cycle"))
                .thenExecute(() -> {
                    shipped[0] = helper.getTick() - startedAt[0];
                    helper.assertTrue(shipped[0] >= ONE_PER_INTERVAL_TICKS,
                            "at the shipped cycle the eight reads must still be one per interval, which cannot be done"
                                    + " in fewer than " + ONE_PER_INTERVAL_TICKS + " ticks, took " + shipped[0]);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.snapshotCycleTicks, SHORT_CYCLE);
                    helper.assertValueEqual(perInterval(aisle), READS_PER_TICK,
                            "a cycle of " + SHORT_CYCLE + " ticks needs four reads per interval");
                    growEverything(helper, chests, aisle);
                    startedAt[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), 3L * CHESTS,
                        "and found them all again at the short cycle"))
                .thenExecute(() -> {
                    shortened[0] = helper.getTick() - startedAt[0];
                    // One interval of slack: the change lands between two intervals, so the first read of the cycle is
                    // up to one interval away. The cycle itself is what is bounded, not the wait for its first read.
                    helper.assertTrue(shortened[0] <= SHORT_CYCLE + INTERVAL,
                            "the whole warehouse must come round within the configured " + SHORT_CYCLE + " ticks, took "
                                    + shortened[0]);
                    helper.assertTrue(shortened[0] < ONE_PER_INTERVAL_TICKS,
                            "and must be faster than one location per interval could ever be ("
                                    + ONE_PER_INTERVAL_TICKS + " ticks), took " + shortened[0]);
                    helper.assertTrue(shortened[0] < shipped[0], "measured: " + shipped[0]
                            + " ticks at the shipped cycle, " + shortened[0] + " ticks at a cycle of " + SHORT_CYCLE);
                    // The measurement itself, in the log beside the assertions that bound it: a number somebody can
                    // read after the run is worth more than one that only appears when the test fails.
                    Wareworks.LOGGER.debug(
                            "Round-robin cycle over {} storage locations: {} ticks at cycle {}, {} ticks at cycle {}",
                            CHESTS, shipped[0], SHIPPED_CYCLE, shortened[0], SHORT_CYCLE);
                    ConfigOverrides.restoreAll();
                })
                .thenSucceed();
    }

    /** Restores the config even when the test above fails before its own restore. */
    @AfterBatch(batch = CADENCE_BATCH)
    public static void restoreCadenceConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** What the controller's own tick asks {@link SnapshotCadence} for this aisle, at the live config values. */
    private static int perInterval(AisleFixture aisle) {
        return SnapshotCadence.locationsPerInterval(aisle.controller().storageLocations().size(),
                WareworksConfig.snapshotIntervalTicks(), WareworksConfig.snapshotCycleTicks(),
                WareworksConfig.maxSnapshotsPerTick());
    }

    /**
     * Grows the stack in every chest by one item <b>in place</b>: no {@code setChanged}, so no content hint reaches the
     * controller and nothing but the round robin can find it. Asserted rather than assumed, because a hint would turn
     * this test into a measurement of the change queue.
     */
    private static void growEverything(GameTestHelper helper, List<BlockPos> chests, AisleFixture aisle) {
        for (BlockPos chest : chests) {
            if (!(helper.getLevel().getBlockEntity(helper.absolutePos(chest)) instanceof ChestBlockEntity be)) {
                helper.fail("missing chest", chest);
                return;
            }
            be.getItem(0).grow(1);
        }
        helper.assertValueEqual(aisle.controller().pendingSnapshotCount(), 0, "a silent change sends no hint");
    }
}
