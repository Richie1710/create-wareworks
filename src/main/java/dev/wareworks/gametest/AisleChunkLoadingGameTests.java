package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleChunkSpan;
import dev.wareworks.content.controller.AisleChunkTickets;
import dev.wareworks.content.controller.ChunkKeepReason;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the optional chunk loading of M19 (issue #10, {@code docs/warehouse-system.md}, ADR-031): an aisle may hold
 * its own chunks loaded while it has work.
 * <p>
 * <b>Every assertion is on ticket bookkeeping, never on loadedness.</b> A GameTest area is force-loaded by vanilla itself
 * ({@code StructureUtils#addCommandBlockAndButtonToStartTest} → {@code ServerLevel#setChunkForced}), so "the chunk is
 * loaded" proves nothing here; what a regression would break is which tickets exist. Two probes are used side by side:
 * <ul>
 * <li>{@link AisleChunkTickets#heldChunks} for <b>this</b> aisle — the mod's own record of what it holds;</li>
 * <li>{@link #assertNoLeak}, the <b>leak check</b>: the union of every chunk the mod believes it holds in this level
 * against {@link AisleChunkTickets#rawBlockForcedChunkCount}, which is what NeoForge really tracks. A ticket that outlived
 * its owner is a chunk NeoForge has and the record does not, which is exactly a difference between those two numbers. Every
 * test that holds anything asserts it on <b>every tick</b>.</li>
 * </ul>
 * <b>Why not the raw count of this aisle alone:</b> NeoForge's owner type is package-private, so nothing can ask it about
 * one owner (which is also why {@code /wareworks chunks} exists at all). And an absolute raw count would be wrong here for
 * a second reason: a GameTest leaves its structure standing when it ends, so the aisles of tests that already finished go
 * on ticking in the same level. Raising the level cap for one test lets <b>those</b> aisles hold their chunks too, which is
 * correct behaviour and pure noise for an absolute count. The union check is immune to it.
 * <p>
 * <b>One batch per test.</b> Every test but {@link #chunkloadingoffbydefault} changes the server config, and the tests of
 * one batch run at the same time; batches run one after another ({@code GameTestRunner#runBatch}). Every batch restores its
 * overrides in an {@code @AfterBatch} method, also after a failure ({@link ConfigOverrides}).
 * <p>
 * What no GameTest can reach — a real chunk unload, a real save, quit and rejoin, and NeoForge's own validation callback
 * on a restart — is in the {@code robustness} scenario of the dev harness, except for the load decision itself, which
 * {@link #chunkticketvalidationdropsanorphan} drives through the seam {@link AisleChunkTickets#validate} (NeoForge's
 * {@code TicketHelper} has a package-private constructor and cannot be faked, which is why that seam exists).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class AisleChunkLoadingGameTests {
    static final String HOLD_BATCH = "wareworkschunkhold";
    static final String RELEASE_BATCH = "wareworkschunkrelease";
    static final String BROKEN_BATCH = "wareworkschunkbroken";
    static final String REPLACED_BATCH = "wareworkschunkreplaced";
    static final String LEVEL_CAP_BATCH = "wareworkschunklevelcap";
    static final String CHUNK_CAP_BATCH = "wareworkschunkchunkcap";
    static final String GIVE_UP_BATCH = "wareworkschunkgiveup";
    static final String SHRINK_BATCH = "wareworkschunkshrink";
    static final String SWITCH_OFF_BATCH = "wareworkschunkswitchoff";
    static final String VALIDATE_BATCH = "wareworkschunkvalidate";
    static final String COLLECT_BATCH = "wareworkschunkcollect";
    static final String CAP_LOWERED_BATCH = "wareworkschunkcaplowered";
    static final String GIVE_UP_RELOAD_BATCH = "wareworkschunkgiveupreload";
    static final String RELEASE_ALL_BATCH = "wareworkschunkreleaseall";
    static final String COLLECT_CAP_BATCH = "wareworkschunkcollectcap";

    private static final int AISLE_Z = 3;
    private static final int SECOND_AISLE_Z = 9;
    private static final int RAILS = 5;
    /**
     * An aisle long enough that its footprint spans more than one chunk column whatever its alignment is: the inflated box
     * is {@code RAILS + 3} blocks long, and 17 blocks cannot fit into one 16-block column. The per-aisle cap test needs
     * that guarantee, and so does the shrink test.
     */
    private static final int LONG_RAILS = 14;
    /** Rails left after the long aisle is shortened. */
    private static final int SHRUNK_RAILS = 2;
    private static final int TEST_RPM = 128;
    /**
     * A level cap that is not in the way. It has to be generous rather than exact, because the aisles of tests that
     * already finished go on standing in this level and take their own slots as soon as the cap allows it.
     */
    private static final int MANY_AISLES = 64;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Shorter than the default {@code releaseDelayTicks}, so the linger is observable inside a test. */
    private static final int SHORT_LINGER_TICKS = 40;
    /** Short enough for the give-up test to finish, long enough for the hold to be observed first. */
    private static final int SHORT_MAX_HOLD_TICKS = 40;
    /** Long enough for several dispatch intervals and one collect poll: proof that a state really settled. */
    private static final int SETTLE_TICKS = 60;
    /** The bounded safety re-check of a holding controller, plus a little slack. */
    private static final int RECHECK_SLACK_TICKS = 40;

    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition NEAR = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition COLLECT_RACK = new RackPosition(0, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    /** A second item type, so a second request is really a second request and not a merge into the open one. */
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    private static final int STORED_IRON = 32;
    private static final int REQUESTED = 8;

    private AisleChunkLoadingGameTests() {
    }

    // --- off by default ----------------------------------------------------------------------------------------------

    /**
     * The guard for "a server that does not want this pays nothing": with the shipped config a whole store job runs and
     * <b>no</b> ticket is ever taken. The raw count is asserted on every tick, so a hold that appears for a single tick
     * fails the test too.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void chunkloadingoffbydefault(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "chunk loading off");
            helper.assertValueEqual(held(helper, aisle), 0, "no ticket while chunk loading is off");
            assertNoLeak(helper);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> helper.assertValueEqual(WareworksConfig.maxTicketedAislesPerLevel(), 0,
                        "chunk loading must ship switched off"))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON, "the stack was stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0, "nothing held");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.NONE,
                            "and nothing to report");
                    helper.assertValueEqual(aisle.controller().summary().withoutCrane().chunkKeepReason(),
                            ChunkKeepReason.NONE, "the goggle summary leaves the line out");
                })
                .thenSucceed();
    }

    // --- taking -----------------------------------------------------------------------------------------------------

    /** A crane job makes the aisle hold exactly the chunks {@link AisleChunkSpan} computes, and no more. */
    @GameTest(template = AISLE_16X10X7, batch = HOLD_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketheldwhileajobruns(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "ticket held");
            assertNoLeak(helper);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isPresent(), "the crane has a job"))
                .thenWaitUntil(() -> {
                    LongSet expectedChunks = footprintOf(helper, aisle, RAILS);
                    helper.assertValueEqual(heldChunks(helper, aisle), expectedChunks, "the held set is the footprint");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.CRANE_JOB,
                            "held because of the crane job");
                    helper.assertValueEqual(aisle.controller().chunkKeepChunks(), expectedChunks.size(),
                            "and the goggles say how many");
                })
                // The operator's view of the same hold, driven through the real command dispatcher: vanilla cannot show
                // these tickets, so the listing and its emergency valve are part of the feature, not a convenience.
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    BlockPos owner = helper.absolutePos(aisle.controllerPos());
                    CommandSourceStack source = level.getServer().createCommandSourceStack();
                    List<String> answers = new ArrayList<>();
                    level.getServer().getCommands().performPrefixedCommand(
                            source.withCallback((success, result) -> answers.add(success + ":" + result)),
                            "wareworks chunks");
                    helper.assertValueEqual(answers.size(), 1, "the listing answered: " + answers);
                    helper.assertTrue(answers.getFirst().startsWith("true:"), "and it succeeded: " + answers);
                    level.getServer().getCommands().performPrefixedCommand(source,
                            "wareworks chunks release " + owner.getX() + " " + owner.getY() + " " + owner.getZ());
                    helper.assertValueEqual(held(helper, aisle), 0, "the emergency valve let the chunks go");
                })
                .thenIdle(RECHECK_SLACK_TICKS)
                .thenExecute(() -> helper.assertValueEqual(held(helper, aisle), 0,
                        "and the aisle does not take them straight back, although its work is unchanged"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketheldwhileajobruns} fails before its own restore. */
    @AfterBatch(batch = HOLD_BATCH)
    public static void restoreHoldConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- releasing and the linger ------------------------------------------------------------------------------------

    /**
     * The hold is released once the aisle is idle — but not before {@code releaseDelayTicks} — and a second job inside
     * the linger neither releases nor re-takes anything. That second half is the anti-thrash guarantee, asserted on every
     * tick rather than at the end, because a hold that blinks for one tick is exactly the failure it guards against.
     */
    @GameTest(template = AISLE_16X10X7, batch = RELEASE_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleasedwhenidle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        boolean[] watchingLinger = new boolean[1];
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "ticket released");
            assertNoLeak(helper);
            if (watchingLinger[0])
                helper.assertTrue(held(helper, aisle) > 0, "the hold must not blink while work keeps arriving");
        });
        long[] idleAt = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.releaseDelayTicks, SHORT_LINGER_TICKS);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "the aisle holds its chunks"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON, "the stack was stored");
                    aisle.assertIdleAndEmpty();
                    idleAt[0] = helper.getLevel().getGameTime();
                })
                // Still holding right after the job: the linger has not run out yet. Three ticks of slack, because the
                // controller re-decides on the tick after the report that its job is done.
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(held(helper, aisle) > 0, "the hold lingers after the last work");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.RELEASING,
                            "and says it is letting go");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0, "released once idle");
                    helper.assertTrue(helper.getLevel().getGameTime() - idleAt[0] >= SHORT_LINGER_TICKS,
                            "the release must not come before the configured linger");
                })
                // The thrash guard: two jobs with a gap of half the linger between them must not make the tickets
                // blink. From here until the second one is done, every tick asserts that the hold never drops to 0.
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(1));
                    ItemCensus.change(expected, IRON, 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "the aisle holds again"))
                .thenExecute(() -> watchingLinger[0] = true)
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON + 1,
                        "the first extra item was stored"))
                .thenIdle(SHORT_LINGER_TICKS / 2)
                .thenExecute(() -> {
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(1));
                    ItemCensus.change(expected, IRON, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON + 2,
                        "and the second one too, without the hold ever being let go in between"))
                .thenExecute(() -> watchingLinger[0] = false)
                .thenWaitUntil(() -> helper.assertValueEqual(held(helper, aisle), 0, "released again once really idle"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleasedwhenidle} fails. */
    @AfterBatch(batch = RELEASE_BATCH)
    public static void restoreReleaseConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the owner goes away -----------------------------------------------------------------------------------------

    /**
     * The controller is broken while it holds chunks mid job: every ticket goes in the same tick. A ticket that outlives
     * its owner is the one defect this feature must not have, and the item census proves the crane keeps the items it is
     * carrying while it happens.
     */
    @GameTest(template = AISLE_16X10X7, batch = BROKEN_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleasedwhenthecontrollerisbroken(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "controller broken while holding");
            assertNoLeak(helper);
        });
        int[] rawBefore = new int[1];
        int[] heldBefore = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.dock().currentJob().isPresent(), "the crane has a job");
                    helper.assertTrue(held(helper, aisle) > 0, "and the aisle holds its chunks");
                })
                .thenExecute(() -> {
                    rawBefore[0] = raw(helper);
                    heldBefore[0] = held(helper, aisle);
                    aisle.breakBlock(aisle.controllerPos());
                    // In the same tick, not "eventually": remove() releases, so nothing waits for a tick that may never
                    // come once the chunk stops ticking. Measured in one tick, so no other aisle can move in between.
                    helper.assertValueEqual(held(helper, aisle), 0, "the tickets went with their owner");
                    helper.assertValueEqual(raw(helper), rawBefore[0] - heldBefore[0],
                            "and NeoForge lost exactly the chunks it held");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> helper.assertValueEqual(held(helper, aisle), 0, "and none came back"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleasedwhenthecontrollerisbroken} fails. */
    @AfterBatch(batch = BROKEN_BATCH)
    public static void restoreBrokenConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** The other way a controller disappears: overwritten by a command or a schematic ({@code /setblock}). */
    @GameTest(template = AISLE_16X10X7, batch = REPLACED_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleasedwhenthecontrollerisreplaced(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));
        int[] rawBefore = new int[1];
        int[] heldBefore = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    // No motor: the request can never be served, so the aisle keeps its work while the test acts.
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.controller().openRequestCount() > 0, "the request is open");
                    helper.assertTrue(held(helper, aisle) > 0, "and the aisle holds its chunks");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.OPEN_REQUESTS,
                            "held because of the open request");
                })
                .thenExecute(() -> {
                    rawBefore[0] = raw(helper);
                    heldBefore[0] = held(helper, aisle);
                    helper.setBlock(aisle.controllerPos(), Blocks.STONE);
                    helper.assertValueEqual(held(helper, aisle), 0, "the replaced controller released its tickets");
                    helper.assertValueEqual(raw(helper), rawBefore[0] - heldBefore[0],
                            "and NeoForge lost exactly the chunks it held");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> helper.assertValueEqual(held(helper, aisle), 0, "and none came back"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleasedwhenthecontrollerisreplaced} fails. */
    @AfterBatch(batch = REPLACED_BATCH)
    public static void restoreReplacedConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the caps ----------------------------------------------------------------------------------------------------

    /**
     * With room for one aisle per dimension, the second one holds nothing, says so, and <b>works exactly as it does
     * today</b> — its crane serves its request while its chunks happen to be loaded. When the first aisle lets go, the
     * second one takes the freed slot without any poll.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = LEVEL_CAP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketrefusedoverthelevelcap(GameTestHelper helper) {
        // The first aisle has no motor, so its request stays open and it keeps the single slot for the whole test.
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS);
        first.build(false);
        first.storage(NEAR, IRON.toStack(STORED_IRON));
        first.output(OUTPUT);
        AisleFixture second = new AisleFixture(helper, SECOND_AISLE_Z, RAILS);
        second.build(true);
        second.storage(NEAR, IRON.toStack(STORED_IRON));
        second.output(OUTPUT);
        BlockPos firstTrigger = first.rackPos(OUTPUT).above();
        BlockPos secondTrigger = second.rackPos(OUTPUT).above();
        BlockPos secondMotor = new BlockPos(1, WareworksGameTests.FLOOR_Y, SECOND_AISLE_Z);
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    first.assertReady(1, 0, 1);
                    second.assertReady(1, 0, 1);
                })
                .thenExecute(() -> {
                    // One slot on top of whatever the level already holds: the aisles of finished tests stand in the
                    // same level and keep the slots they have, so the cap is set relative to them rather than to 1.
                    int already = AisleChunkTickets.holdingAisleCount(helper.getLevel());
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, already + 1);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.releaseDelayTicks, SHORT_LINGER_TICKS);
                    second.motor().generatedSpeed.setValue(TEST_RPM);
                    first.requestAt(OUTPUT, IRON.toStack(), REQUESTED, firstTrigger);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, first) > 0, "the first aisle took the only slot"))
                .thenExecute(() -> second.requestAt(OUTPUT, IRON.toStack(), REQUESTED, secondTrigger))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(second.controller().chunkKeepReason(), ChunkKeepReason.AT_LEVEL_LIMIT,
                            "the second aisle is refused by the level cap");
                    helper.assertValueEqual(held(helper, second), 0, "and holds nothing at all");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(second.stationCount(OUTPUT, IRON), (long) REQUESTED,
                            "the refused aisle still serves its request exactly as before M19");
                    helper.assertValueEqual(second.controller().openRequestCount(), 0, "and closes it");
                })
                // The freed slot. The second crane loses its rotation first, so its next request stays open and it is
                // still waiting for a slot at the moment the first aisle lets go.
                .thenWaitUntil(() -> second.assertIdleAndEmpty())
                .thenExecute(() -> helper.setBlock(secondMotor, Blocks.AIR))
                .thenExecute(() -> {
                    second.requestAt(OUTPUT, IRON.toStack(), REQUESTED, secondTrigger);
                    helper.assertTrue(second.controller().openRequestCount() > 0, "the second aisle waits again");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(second.controller().chunkKeepReason(),
                        ChunkKeepReason.AT_LEVEL_LIMIT, "and is still refused"))
                .thenExecute(() -> {
                    Optional<RetrievalRequest<ItemKey, BlockPos>> open = first.controller().oldestOpenRequest();
                    helper.assertTrue(open.isPresent(), "the first aisle still waits for its request");
                    first.controller().cancelRequest(open.get().id());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(held(helper, first), 0, "the first aisle let go"))
                .thenWaitUntil(() -> helper.assertTrue(held(helper, second) > 0,
                        "and the second one took the freed slot without waiting for a poll"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketrefusedoverthelevelcap} fails. */
    @AfterBatch(batch = LEVEL_CAP_BATCH)
    public static void restoreLevelCapConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** An aisle whose footprint is bigger than the per-aisle cap holds <b>nothing</b>: never a partial hold. */
    @GameTest(template = AISLE_16X10X7, batch = CHUNK_CAP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketrefusedwhentheaisleneedstoomanychunks(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, LONG_RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxChunksPerAisle, 1);
                    helper.assertTrue(aisle.controller().chunkFootprintSize() > 1,
                            "a " + LONG_RAILS + " rail aisle cannot fit into one chunk column, but its footprint is "
                                    + aisle.controller().chunkFootprintSize());
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.TOO_MANY_CHUNKS,
                            "refused by the per-aisle cap");
                    helper.assertValueEqual(aisle.controller().chunkKeepChunks(), aisle.controller().chunkFootprintSize(),
                            "and the goggle line names what it would need");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0,
                            "nothing at all is held, not even one chunk of a partial hold");
                    helper.assertTrue(aisle.controller().openRequestCount() > 0, "and the work is still there");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketrefusedwhentheaisleneedstoomanychunks} fails. */
    @AfterBatch(batch = CHUNK_CAP_BATCH)
    public static void restoreChunkCapConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * A cap is a bound on the hold, not only on taking it: lowered under an aisle that <b>already holds</b>, it releases
     * at once, names itself on the goggles and keeps the aisle refused — and the refusal is not sticky, because the next
     * real work re-decides (M19 review; the JUnit table covers the other direction, an aisle grown past the cap).
     * <p>
     * The per-aisle cap is the deterministic half of this: which chunk columns an aisle covers depends on where it sits
     * inside them, so a test that grew an aisle could not promise the footprint really got bigger.
     */
    @GameTest(template = AISLE_16X10X7, batch = CAP_LOWERED_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleasedwhenacapislowered(GameTestHelper helper) {
        // No motor, so the request stays open: the aisle wants to hold for the whole test and only the cap changes.
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, LONG_RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));
        int[] footprint = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxChunksPerAisle, 64);
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(heldChunks(helper, aisle), footprintOf(helper, aisle, LONG_RAILS),
                            "the aisle holds its whole footprint first");
                    footprint[0] = aisle.controller().chunkFootprintSize();
                    helper.assertTrue(footprint[0] > 1,
                            "a " + LONG_RAILS + " rail aisle needs more than one chunk, so the cap can be lowered "
                                    + "under it; its footprint is " + footprint[0]);
                })
                .thenExecute(() -> ConfigOverrides.set(helper, WareworksConfig.SERVER.maxChunksPerAisle,
                        footprint[0] - 1))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0,
                            "the lowered cap made the aisle let go of everything, not keep a hold over the bound");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.TOO_MANY_CHUNKS,
                            "and the goggles name the cap that did it");
                    helper.assertValueEqual(aisle.controller().chunkKeepChunks(), footprint[0],
                            "with what the aisle would need");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0, "and nothing comes back while the cap stays");
                    helper.assertTrue(aisle.controller().openRequestCount() > 0, "the work is untouched");
                })
                // Not sticky: the cap raised again plus any work hook and the aisle holds its footprint again. (A config
                // event would do it on its own; inside a GameTest the config is set without one, and a refused aisle
                // schedules no periodic re-check by design.)
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxChunksPerAisle, 64);
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(heldChunks(helper, aisle),
                        footprintOf(helper, aisle, LONG_RAILS), "the aisle holds again once the cap allows it"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleasedwhenacapislowered} fails. */
    @AfterBatch(batch = CAP_LOWERED_BATCH)
    public static void restoreCapLoweredConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- giving up ---------------------------------------------------------------------------------------------------

    /**
     * Work that can never finish does not hold chunks for ever: after {@code maxHoldTicks} the aisle lets go and says it
     * gave up, and it holds again only once its work really changed. This is what makes "never a permanent loader" true
     * rather than aspirational.
     */
    @GameTest(template = AISLE_16X10X7, batch = GIVE_UP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketgivesupafterthemaximum(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        // Two item types, so the second request below is really a second request: one for the same item and the same
        // output would be merged into the open one and would leave the work fingerprint unchanged.
        aisle.storage(NEAR, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxHoldTicks, SHORT_MAX_HOLD_TICKS);
                    // No motor, so the request can never be served: hasWork stays true for ever.
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "the aisle holds its chunks"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0, "it gave the chunks up again");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.GAVE_UP,
                            "and says why, instead of silently doing nothing");
                })
                .thenIdle(SHORT_MAX_HOLD_TICKS + RECHECK_SLACK_TICKS)
                .thenExecute(() -> helper.assertValueEqual(held(helper, aisle), 0,
                        "the same unfinished work never takes again"))
                // Real progress re-arms it: another request is another work fingerprint.
                .thenExecute(() -> aisle.requestAt(OUTPUT, DIAMOND.toStack(), REQUESTED, trigger))
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "changed work holds again"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketgivesupafterthemaximum} fails. */
    @AfterBatch(batch = GIVE_UP_BATCH)
    public static void restoreGiveUpConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * The give-up bound <b>survives a reload</b>, and being idle is what ends it (M19 review). The work it refuses is
     * saved, so a bound that lived only as long as one block entity instance would let every reload — a player walking
     * back into the chunk, or a restart — take the whole footprint again for another {@code maxHoldTicks}, for work that
     * had already proved unservable.
     * <p>
     * The reload is a real one as far as the controller can tell: the live block entity is saved and replaced by a copy
     * built from that save with {@code BlockEntity.loadStatic}, exactly as a chunk load builds one (the pattern
     * {@code StockKeeperGameTests} and {@code ProductionGameTests} use, because a GameTest cannot unload its own area).
     */
    @GameTest(template = AISLE_16X10X7, batch = GIVE_UP_RELOAD_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketgiveupsurvivesareload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON), DIAMOND.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        // Armed for the whole window after the reload, not only checked at its end: a fresh controller that took the
        // footprint again would hold it for maxHoldTicks and then give up once more, which a single assertion at the end
        // could not tell apart from the bound really having survived.
        boolean[] nothingMayBeHeld = new boolean[1];
        helper.onEachTick(() -> {
            assertNoLeak(helper);
            if (nothingMayBeHeld[0])
                helper.assertValueEqual(held(helper, aisle), 0, "the reloaded aisle must not hold for the same work");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxHoldTicks, SHORT_MAX_HOLD_TICKS);
                    // No motor, so the request can never be served: the work never changes on its own.
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "the aisle holds its chunks"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().chunkKeepReason(),
                        ChunkKeepReason.GAVE_UP, "and gave up after the configured maximum"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    BlockEntity loaded = BlockEntity.loadStatic(controller.getBlockPos(), controller.getBlockState(),
                            saved, level.registryAccess());
                    if (!(loaded instanceof WarehouseControllerBlockEntity reloaded)) {
                        helper.fail("a saved controller must load again as one");
                        return;
                    }
                    level.setBlockEntity(reloaded);
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                    nothingMayBeHeld[0] = true;
                })
                .thenIdle(SHORT_MAX_HOLD_TICKS + RECHECK_SLACK_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(aisle.controller().openRequestCount() > 0,
                            "the reloaded controller read the same unservable request back, so it could hold for it");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.GAVE_UP,
                            "and it says the bound came out of the save with the work");
                })
                // Idle is the escape hatch, and the reason a saved flag can never strand an aisle: work that is gone
                // clears it, whatever its fingerprint was.
                .thenExecute(() -> {
                    nothingMayBeHeld[0] = false;
                    aisle.controller().oldestOpenRequest()
                            .ifPresent(open -> aisle.controller().cancelRequest(open.id()));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().chunkKeepReason(),
                        ChunkKeepReason.NONE, "an idle aisle reports nothing at all, not that it gave up"))
                .thenExecute(() -> aisle.requestAt(OUTPUT, DIAMOND.toStack(), REQUESTED, trigger))
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "and it may hold again"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketgiveupsurvivesareload} fails. */
    @AfterBatch(batch = GIVE_UP_RELOAD_BATCH)
    public static void restoreGiveUpReloadConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- a changing aisle --------------------------------------------------------------------------------------------

    /**
     * The hold follows the aisle: rails removed under a holding controller shrink the held set to the new footprint, and
     * the mod's record and NeoForge's tracker agree on every step. This is the leak check in the hardest case.
     */
    @GameTest(template = AISLE_16X10X7, batch = SHRINK_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketfollowsashrinkingaisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, LONG_RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));
        LongSet beforeShrink = new LongOpenHashSet();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxChunksPerAisle, 64);
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> {
                    LongSet expectedChunks = footprintOf(helper, aisle, LONG_RAILS);
                    helper.assertValueEqual(heldChunks(helper, aisle), expectedChunks, "the long aisle's footprint");
                    helper.assertTrue(expectedChunks.size() > 1,
                            "a " + LONG_RAILS + " rail aisle spans more than one chunk column");
                    beforeShrink.clear();
                    beforeShrink.addAll(expectedChunks);
                })
                .thenExecute(() -> {
                    for (int position = SHRUNK_RAILS + 1; position <= LONG_RAILS; position++)
                        helper.setBlock(aisle.dockPos().relative(AisleFixture.AISLE, position), Blocks.AIR);
                    aisle.dock().requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    LongSet expectedChunks = footprintOf(helper, aisle, SHRUNK_RAILS);
                    helper.assertValueEqual(heldChunks(helper, aisle), expectedChunks,
                            "the hold shrank with the aisle");
                    // Whether a column really drops depends on where the aisle sits inside its chunks, so the assertion
                    // is that the hold only ever gives chunks back - it never grows and never keeps one the shorter
                    // aisle does not cover.
                    helper.assertTrue(beforeShrink.containsAll(expectedChunks),
                            "the shortened aisle holds only chunks the long one held");
                    helper.assertTrue(expectedChunks.size() <= beforeShrink.size(),
                            "and never more of them than the " + beforeShrink.size() + " before");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketfollowsashrinkingaisle} fails. */
    @AfterBatch(batch = SHRINK_BATCH)
    public static void restoreShrinkConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- switching the feature off -----------------------------------------------------------------------------------

    /** Switching the setting off while tickets are held releases them; the aisle then behaves exactly as before M19. */
    @GameTest(template = AISLE_16X10X7, batch = SWITCH_OFF_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleasedwhenthefeatureisswitchedoff(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                })
                .thenWaitUntil(() -> helper.assertTrue(held(helper, aisle) > 0, "the aisle holds its chunks"))
                .thenExecute(() -> ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, 0))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(held(helper, aisle), 0, "the hold is gone");
                    helper.assertValueEqual(raw(helper), 0,
                            "and NeoForge tracks no block ticket at all: switching the setting off cleans up");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.NONE,
                            "and there is nothing to report any more");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(raw(helper), 0, "and nothing came back although the work is still there");
                    helper.assertTrue(aisle.controller().openRequestCount() > 0, "the request is untouched");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleasedwhenthefeatureisswitchedoff} fails. */
    @AfterBatch(batch = SWITCH_OFF_BATCH)
    public static void restoreSwitchOffConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the load path -----------------------------------------------------------------------------------------------

    /**
     * The restart test without a restart: {@link AisleChunkTickets#validate} is driven against the real level with a
     * synthetic owner map, exactly as NeoForge's validation callback would on a world load.
     * <ul>
     * <li>the real controller keeps <b>one</b> seed chunk (its own) and loses every other saved chunk;</li>
     * <li>an owner at a position where no controller stands loses everything — after the watchdog window, because the
     * callback runs before any chunk is loaded and structurally cannot ask a block that is not there yet;</li>
     * <li>an owner beyond the level cap loses everything at once.</li>
     * </ul>
     * Asserted on the mod's own record: {@code validate} does not force anything (NeoForge reinstates the surviving
     * tickets itself in the real path), so the raw tracker says nothing about the seeds here.
     */
    @GameTest(template = AISLE_16X10X7, batch = VALIDATE_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketvalidationdropsanorphan(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        aisle.storage(NEAR, IRON.toStack(STORED_IRON));
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).above();
        Map<BlockPos, List<String>> removals = new HashMap<>();
        BlockPos[] owners = new BlockPos[3];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                // The load path replaces the whole record of the level, so nothing may be holding while it runs. With the
                // feature still off that settles by itself: every aisle of an earlier batch has let go by now.
                .thenWaitUntil(() -> helper.assertValueEqual(
                        AisleChunkTickets.holdingAisleCount(helper.getLevel()), 0, "no aisle holds before the reload"))
                .thenExecute(() -> {
                    // Everything in one tick, so no aisle can take a slot between the cap, the work and the reload.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, 2);
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED, trigger);
                    ServerLevel level = helper.getLevel();
                    BlockPos controller = helper.absolutePos(aisle.controllerPos());
                    // Sorted by (x, z, y), so the real controller comes first and the third owner is the one the cap of
                    // two cuts off.
                    owners[0] = controller;
                    owners[1] = controller.offset(100, 0, 0);
                    owners[2] = controller.offset(200, 0, 0);
                    Map<BlockPos, TicketSet> saved = new HashMap<>();
                    for (BlockPos owner : owners)
                        saved.put(owner, savedTickets(owner));
                    AisleChunkTickets.validate(level, saved, new AisleChunkTickets.Remover() {
                        @Override
                        public void removeAll(BlockPos owner) {
                            removals.computeIfAbsent(owner, key -> new ArrayList<>()).add("all");
                        }

                        @Override
                        public void remove(BlockPos owner, long chunk, boolean ticking) {
                            removals.computeIfAbsent(owner, key -> new ArrayList<>())
                                    .add(new ChunkPos(chunk) + (ticking ? " ticking" : ""));
                        }
                    });

                    long seed = ChunkPos.asLong(owners[0]);
                    LongSet onlyTheSeed = new LongOpenHashSet();
                    onlyTheSeed.add(seed);
                    helper.assertValueEqual(AisleChunkTickets.heldChunks(level, owners[0]), onlyTheSeed,
                            "the real controller keeps exactly its own chunk as a seed");
                    helper.assertFalse(removals.getOrDefault(owners[0], List.of()).contains("all"),
                            "and is not dropped wholesale");
                    helper.assertValueEqual(removals.getOrDefault(owners[0], List.of()).size(), 2,
                            "the other saved chunk and the stray ticking ticket are removed: " + removals.get(owners[0]));
                    helper.assertValueEqual(AisleChunkTickets.heldChunkCount(level, owners[1]), 1,
                            "the orphan is seeded too: nothing can ask a block that is not loaded yet");
                    helper.assertValueEqual(removals.getOrDefault(owners[2], List.of()), List.of("all"),
                            "and the owner beyond the cap loses everything at once");
                    helper.assertValueEqual(AisleChunkTickets.heldChunkCount(level, owners[2]), 0,
                            "so it holds nothing");
                })
                // The controller claims its seed on its next tick and extends it, because its work survived the reload.
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(heldChunks(helper, aisle), footprintOf(helper, aisle, RAILS),
                            "the seeded aisle grew back to its whole footprint");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.OPEN_REQUESTS,
                            "for the work that survived");
                })
                // And the watchdog releases the seed nobody claimed.
                .thenIdle(AisleChunkTickets.SEED_GRACE_TICKS + RECHECK_SLACK_TICKS)
                .thenExecute(() -> helper.assertValueEqual(
                        AisleChunkTickets.heldChunkCount(helper.getLevel(), owners[1]), 0,
                        "the unclaimed seed was released by the watchdog"))
                // Hand the level back in a consistent state. The seeds above were only ever in the record - in the real
                // path NeoForge reinstates the surviving tickets itself, which a running level cannot be made to do - so
                // the record and NeoForge's tracker are deliberately allowed to differ inside this one test, and the
                // emergency valve puts them back in step before any other test looks.
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    AisleChunkTickets.releaseAllByCommand(level);
                    helper.assertValueEqual(AisleChunkTickets.allHeldChunks(level).size(), 0, "nothing left in the record");
                    helper.assertValueEqual(raw(helper), 0, "and no ticket left in NeoForge's tracker");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketvalidationdropsanorphan} fails. */
    @AfterBatch(batch = VALIDATE_BATCH)
    public static void restoreValidateConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- collecting --------------------------------------------------------------------------------------------------

    /**
     * A gated-open collecting port with items pending is work of its own — but only with the separate opt-in, and only
     * under its own cap. Without the opt-in the aisle is idle, which is the honest default: chunk loading keeps a running
     * collection going, it cannot start one.
     */
    @GameTest(template = AISLE_16X10X7, batch = COLLECT_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketcollectholdisoptin(GameTestHelper helper) {
        // No motor on purpose: nothing collects the items, so the pending state is stable while the test acts.
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(false);
        aisle.storage(NEAR);
        BlockPos machine = aisle.portInventory(COLLECT_RACK, IRON.toStack(STORED_IRON));
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.releaseDelayTicks, SHORT_LINGER_TICKS);
                    WarehouseOutputBlockEntity port = aisle.outputAt(COLLECT_RACK);
                    helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK), "the port collects now");
                    // Unwired, so the gate is open without any redstone at all.
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                })
                // Without the opt-in a pending collect is no reason to hold anything.
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().collectingPortCount(), 1, "the port is a collecting one");
                    helper.assertValueEqual(held(helper, aisle), 0, "and holds nothing without the opt-in");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.NONE,
                            "an idle aisle with something pending is still idle");
                })
                .thenExecute(() -> ConfigOverrides.set(helper,
                        WareworksConfig.SERVER.maxCollectHoldAislesPerLevel, 1))
                .thenWaitUntil(() -> {
                    helper.assertTrue(held(helper, aisle) > 0, "with the opt-in it holds");
                    helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.COLLECTING,
                            "and says it is collecting");
                })
                // Nothing pending any more: it lets go after the linger, like any other idle aisle.
                .thenExecute(() -> {
                    // Emptied rather than broken: the port must go on reading a machine that is simply out of items,
                    // which is the state a production loop rests in.
                    IItemHandler handler = aisle.handlerAt(machine);
                    for (int slot = 0; slot < handler.getSlots(); slot++)
                        handler.extractItem(slot, Integer.MAX_VALUE, false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(held(helper, aisle), 0,
                        "an empty machine releases the hold"))
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketcollectholdisoptin} fails. */
    @AfterBatch(batch = COLLECT_BATCH)
    public static void restoreCollectConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * The collect opt-in's own cap <b>says so</b>: the aisle queued behind it reports {@code AT_COLLECT_LIMIT} with the
     * chunks it would need, instead of being indistinguishable from an idle aisle — no goggle line, no log line and no row
     * in {@code /wareworks chunks} was exactly what a fourth setting that changes behaviour must not do (M19 review).
     * <p>
     * Which of the two aisles gets the single slot is a race by design, so both halves are asserted symmetrically.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = COLLECT_CAP_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketcollectcapnamesitself(GameTestHelper helper) {
        // No motors: nothing collects the items, so both aisles have something pending for the whole test.
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS);
        first.build(false);
        first.storage(NEAR);
        first.portInventory(COLLECT_RACK, IRON.toStack(STORED_IRON));
        AisleFixture second = new AisleFixture(helper, SECOND_AISLE_Z, RAILS);
        second.build(false);
        second.storage(NEAR);
        second.portInventory(COLLECT_RACK, IRON.toStack(STORED_IRON));
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    first.assertReady(1, 0, 1);
                    second.assertReady(1, 0, 1);
                })
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, MANY_AISLES);
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxCollectHoldAislesPerLevel, 1);
                    for (AisleFixture aisle : List.of(first, second)) {
                        WarehouseOutputBlockEntity port = aisle.outputAt(COLLECT_RACK);
                        helper.assertTrue(port.setPortRank(PortSettings.COLLECT_RANK), "the port collects now");
                        port.setRedstoneMode(PortRedstone.UNLESS_POWERED); // unwired: the gate is open with no redstone
                    }
                })
                .thenWaitUntil(() -> {
                    boolean firstHolds = held(helper, first) > 0;
                    helper.assertTrue(firstHolds != (held(helper, second) > 0),
                            "exactly one of the two aisles may hold for collecting under a cap of 1");
                    AisleFixture holder = firstHolds ? first : second;
                    AisleFixture queued = firstHolds ? second : first;
                    helper.assertValueEqual(holder.controller().chunkKeepReason(), ChunkKeepReason.COLLECTING,
                            "the one that got the slot collects");
                    helper.assertValueEqual(queued.controller().chunkKeepReason(), ChunkKeepReason.AT_COLLECT_LIMIT,
                            "and the other one says which cap stopped it, instead of looking idle");
                    helper.assertValueEqual(queued.controller().chunkKeepChunks(),
                            queued.controller().chunkFootprintSize(), "naming what it would need");
                    helper.assertValueEqual(held(helper, queued), 0, "and holding nothing at all");
                })
                // A live bound, not a dead end: a collecting port polls its machine, so the queued aisle re-decides and
                // takes its own hold as soon as the cap allows a second one.
                .thenExecute(() -> ConfigOverrides.set(helper,
                        WareworksConfig.SERVER.maxCollectHoldAislesPerLevel, 2))
                .thenWaitUntil(() -> {
                    helper.assertTrue(held(helper, first) > 0 && held(helper, second) > 0,
                            "both aisles hold once the cap allows two");
                    helper.assertValueEqual(first.controller().chunkKeepReason(), ChunkKeepReason.COLLECTING, "first");
                    helper.assertValueEqual(second.controller().chunkKeepReason(), ChunkKeepReason.COLLECTING, "second");
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketcollectcapnamesitself} fails. */
    @AfterBatch(batch = COLLECT_CAP_BATCH)
    public static void restoreCollectCapConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- the operator's emergency valve --------------------------------------------------------------------------------

    /**
     * {@code /wareworks chunks release all} really leaves <b>nothing</b> held: the aisles a cap had refused are told to
     * give up too, instead of taking the freed slots within a tick or two and showing the operator the same number of
     * holders again under different owners (M19 review).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = RELEASE_ALL_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void chunkticketreleaseallleavesnothingheld(GameTestHelper helper) {
        // Neither aisle has a motor, so both requests stay open and both aisles want to hold for the whole test.
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS);
        first.build(false);
        first.storage(NEAR, IRON.toStack(STORED_IRON));
        first.output(OUTPUT);
        AisleFixture second = new AisleFixture(helper, SECOND_AISLE_Z, RAILS);
        second.build(false);
        second.storage(NEAR, IRON.toStack(STORED_IRON));
        second.output(OUTPUT);
        helper.onEachTick(() -> assertNoLeak(helper));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    first.assertReady(1, 0, 1);
                    second.assertReady(1, 0, 1);
                })
                .thenExecute(() -> {
                    // One slot on top of whatever this level already holds, like the level-cap test: the aisles of
                    // finished tests stand in the same level and keep the slots they have.
                    int already = AisleChunkTickets.holdingAisleCount(helper.getLevel());
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTicketedAislesPerLevel, already + 1);
                    first.requestAt(OUTPUT, IRON.toStack(), REQUESTED, first.rackPos(OUTPUT).above());
                    second.requestAt(OUTPUT, IRON.toStack(), REQUESTED, second.rackPos(OUTPUT).above());
                })
                .thenWaitUntil(() -> {
                    boolean firstHolds = held(helper, first) > 0;
                    helper.assertTrue(firstHolds != (held(helper, second) > 0), "one aisle holds, the other is refused");
                    AisleFixture queued = firstHolds ? second : first;
                    helper.assertValueEqual(queued.controller().chunkKeepReason(), ChunkKeepReason.AT_LEVEL_LIMIT,
                            "the refused one waits for the slot");
                })
                .thenExecute(() -> helper.getLevel().getServer().getCommands().performPrefixedCommand(
                        helper.getLevel().getServer().createCommandSourceStack(), "wareworks chunks release all"))
                .thenIdle(RECHECK_SLACK_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(held(helper, first), 0, "the holder let go");
                    helper.assertValueEqual(held(helper, second), 0,
                            "and the aisle that was queued behind the cap did not take the freed slot");
                    for (AisleFixture aisle : List.of(first, second)) {
                        helper.assertValueEqual(aisle.controller().chunkKeepReason(), ChunkKeepReason.GAVE_UP,
                                "both were told to give up, so both say so");
                        helper.assertTrue(aisle.controller().openRequestCount() > 0,
                                "while the work itself is untouched");
                    }
                })
                .thenSucceed();
    }

    /** Restores the config even when {@link #chunkticketreleaseallleavesnothingheld} fails. */
    @AfterBatch(batch = RELEASE_ALL_BATCH)
    public static void restoreReleaseAllConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- probes ------------------------------------------------------------------------------------------------------

    /** How many chunks the aisle's controller holds, by the mod's own record. */
    private static int held(GameTestHelper helper, AisleFixture aisle) {
        return AisleChunkTickets.heldChunkCount(helper.getLevel(), helper.absolutePos(aisle.controllerPos()));
    }

    /** Which chunks it holds. */
    private static LongSet heldChunks(GameTestHelper helper, AisleFixture aisle) {
        return AisleChunkTickets.heldChunks(helper.getLevel(), helper.absolutePos(aisle.controllerPos()));
    }

    /**
     * How many chunks of the whole level are force-loaded by <b>block</b> tickets, which is what NeoForge really tracks.
     * A GameTest area is force-loaded through vanilla's own chunk set, which this number does not contain, so in a test
     * world with no other mod holding anything this is the mod's own total.
     */
    private static int raw(GameTestHelper helper) {
        return AisleChunkTickets.rawBlockForcedChunkCount(helper.getLevel());
    }

    /**
     * The leak check: NeoForge must track exactly the chunks the mod's own record names, no more and no fewer. A ticket
     * that outlived its owner is a chunk NeoForge has and the record does not — and this holds whichever aisles of the
     * level are involved, which an absolute count could not.
     */
    private static void assertNoLeak(GameTestHelper helper) {
        helper.assertValueEqual(raw(helper), AisleChunkTickets.allHeldChunks(helper.getLevel()).size(),
                "NeoForge must track exactly the chunks Wareworks says it holds");
    }

    /** The footprint {@link AisleChunkSpan} computes for this aisle, as chunk keys. */
    private static LongSet footprintOf(GameTestHelper helper, AisleFixture aisle, int rails) {
        BlockPos dock = helper.absolutePos(aisle.dockPos());
        Direction facing = AisleFixture.AISLE;
        int[] chunks = AisleChunkSpan.chunks(dock.getX(), dock.getZ(), facing.getStepX(), facing.getStepZ(), rails);
        LongSet keys = new LongOpenHashSet(chunks.length / 2);
        for (int i = 0; i < chunks.length; i += 2)
            keys.add(ChunkPos.asLong(chunks[i], chunks[i + 1]));
        return keys;
    }

    /**
     * What a save might hold for one owner: its own chunk (the seed the load path keeps), a second, far-away chunk that
     * must be dropped, and a stray <b>ticking</b> ticket, which Wareworks never takes and therefore always removes.
     */
    private static TicketSet savedTickets(BlockPos owner) {
        ChunkPos chunk = new ChunkPos(owner);
        LongSet nonTicking = new LongOpenHashSet();
        nonTicking.add(chunk.toLong());
        nonTicking.add(ChunkPos.asLong(chunk.x + 40, chunk.z + 40));
        LongSet ticking = new LongOpenHashSet();
        ticking.add(chunk.toLong());
        return new TicketSet(nonTicking, ticking);
    }
}
