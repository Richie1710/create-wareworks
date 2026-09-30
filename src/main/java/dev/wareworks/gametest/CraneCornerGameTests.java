package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the crane <b>really driving a warehouse that bends</b> (M21 part two, issue #1, ADR-033): it rolls to
 * the corner block, swings a quarter turn on the spot, and carries on down the next aisle.
 * <p>
 * Every test here builds the same L on the {@code aisle_pair_16x10x13} floor: the controller at {@code x = 0}, the
 * dock at {@code x = 1} facing east along {@code z = 3}, four rails to the corner at {@code (5, 3)} and three more
 * south of it. Aisle A runs east over positions 0..4, aisle B south over positions 0..3, and the corner block is
 * position 4 of A and position 0 of B at the same time — which is exactly what makes a hand-over a rename of one
 * world block rather than a jump.
 * <p>
 * What is pinned here and nowhere else:
 * <ul>
 * <li><b>A rack round the corner is served</b>, and the crane really stood on the second aisle to serve it — its own
 * pose says so, on a branch no straight warehouse has.</li>
 * <li><b>The corner itself is not a dead end.</b> The block east of the corner is a rack position of aisle B, and the
 * crane puts items in it.</li>
 * <li><b>The machine turns rather than snapping</b>: there are ticks in which its yaw is between the two aisle
 * headings, and a turn is audible.</li>
 * <li><b>An address names the aisle it is on</b>: a job across the corner reads "from A-… to B-…", because the
 * crane's goggle data carries one aisle letter per branch.</li>
 * <li><b>Item conservation on every tick</b> of a crossing job, hand-over included.</li>
 * <li><b>A save in mid-turn comes back mid-turn</b>, down to the yaw, and the job finishes.</li>
 * <li><b>A rail broken while the crane turns ends safely</b>: the machine goes back onto the aisle the warehouse still
 * has and puts its items where a player can open them, and they are stored after all when the rail comes back.
 * Nothing is dropped and nothing is lost.</li>
 * <li><b>A rail broken behind the crane never freezes it</b>: its own aisle still exists, but there is no route from
 * where it stands, and it says so instead of standing still for ever.</li>
 * <li><b>An aisle that leaves the warehouse never kills it</b>: the machine comes back onto the aisle at the dock and
 * the warehouse carries on as the straight one it now is.</li>
 * </ul>
 * With {@code aisle.maxBranches = 1} — the off switch that reproduces 0.5.0 — the second aisle is not part of the
 * warehouse at all, so each test asserts the older answer instead: nothing moves and every item stays where it was.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CraneCornerGameTests {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 12;
    private static final int WATCH_TICKS = 200;
    private static final int TEST_RPM = 128;
    private static final int AISLE_Z = 3;
    /** Rails of aisle A: x = 2..5 at z = 3. Position 4 is the corner block. */
    private static final int FIRST_RAILS = 4;
    /** Rails of aisle B, south of the corner. */
    private static final int SECOND_RAILS = 3;

    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);
    private static final BlockPos CORNER = new BlockPos(1 + FIRST_RAILS, BASE_Y, AISLE_Z);

    /** Input station at position 1 on the left of aisle A. */
    private static final BlockPos INPUT = new BlockPos(2, BASE_Y, 2);
    /** Output station at position 1 on the right of aisle A. */
    private static final BlockPos OUTPUT = new BlockPos(2, BASE_Y, 4);
    /** Trigger block for the output's redstone request. */
    private static final BlockPos TRIGGER = new BlockPos(2, BASE_Y + 1, 4);
    /** The rack at the far end of aisle B: position 3 on its right, with its chest behind it. */
    private static final BlockPos FAR_RACK = new BlockPos(4, BASE_Y, 6);
    private static final BlockPos FAR_CHEST = new BlockPos(3, BASE_Y, 6);
    /**
     * The rack <b>at</b> the corner: the block east of the corner block, which is position 0 on the left of aisle B.
     * One of the two free faces of the corner, and the reason corners are not dead ends.
     */
    private static final BlockPos CORNER_RACK = new BlockPos(6, BASE_Y, AISLE_Z);
    private static final BlockPos CORNER_CHEST = new BlockPos(7, BASE_Y, AISLE_Z);

    /** A rack on aisle A: position 3 on its left, with its chest behind it. Placed only where a test needs one. */
    private static final BlockPos NEAR_RACK = new BlockPos(4, BASE_Y, 2);
    private static final BlockPos NEAR_CHEST = new BlockPos(4, BASE_Y, 1);

    /** The rack on aisle B the crane has to turn a corner for. */
    private static final RackPosition FAR = new RackPosition(1, 3, 0, Side.RIGHT);
    /** The rack beside the corner block, on aisle B. */
    private static final RackPosition AT_CORNER = new RackPosition(1, 0, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final int IRON_COUNT = 8;
    /**
     * The voice of a turn ({@code CraneSounds}): a deep iron trapdoor. Deliberately <b>not</b> Create's cogwheel
     * rumble, which Create itself drones continuously for every cogwheel and gearbox within 16 blocks, so a warehouse
     * driven by a cogwheel would mask its own crane (M21 review fix).
     */
    private static final ResourceLocation TURN_SOUND = SoundEvents.IRON_TRAPDOOR_OPEN.getLocation();

    private CraneCornerGameTests() {
    }

    // --- driving round the corner ---------------------------------------------------------------------------------

    /**
     * The headline of the milestone: a rack three blocks down the <b>second</b> aisle is served. The crane drives east
     * to the corner, swings south and rolls on, and every ingot ends up in the chest behind that rack.
     * <p>
     * Three things are watched every single tick while it happens, because each of them is a way this could be a lie:
     * the crane's pose really names aisle B (no item ever teleports), its yaw really passes through values between the
     * two headings (it turns rather than snapping), and the item census is exact (the hand-over neither duplicates nor
     * loses anything).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranedrivesroundacornertoarack(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<Boolean> onSecondAisle = new AtomicReference<>(false);
        AtomicReference<Boolean> midTurn = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).storageLocations().size(), 1, "the far rack joined");
                    helper.assertValueEqual(controller(helper).inputStations().size(), 1, "the input joined");
                })
                .thenExecute(() -> {
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while the crane crosses the corner");
                        CranePose pose = dock(helper).craneState().pose();
                        if (pose.branch() == 1)
                            onSecondAisle.set(true);
                        if (!pose.isAligned())
                            midTurn.set(true);
                    });
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "every ingot arrived in the chest down the second aisle"))
                .thenExecute(() -> {
                    helper.assertTrue(onSecondAisle.get(), "the crane really stood on the second aisle");
                    helper.assertTrue(midTurn.get(),
                            "and it turned there: its yaw was between the two aisle headings at least once");
                    helper.assertValueEqual(countIn(helper, INPUT, Items.IRON_INGOT), 0, "the input is empty");
                    ItemCensus.assertEquals(helper, conserved, "after the crossing");
                })
                .thenSucceed();
    }

    /**
     * <b>No dead corners.</b> The block east of the corner is a rack position of aisle B — one of the corner block's
     * two free faces, and the case every "the corner is travel only" rule would have lost. The crane hands over onto
     * aisle B at the corner block itself, turns, and puts the iron in without driving a single block further.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void craneservestherackinthecorner(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, CORNER_RACK, CORNER_CHEST, Direction.EAST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack at the corner joined"))
                .thenExecute(() -> {
                    helper.assertValueEqual(rackOf(helper, CORNER_RACK), AT_CORNER,
                            "and it is position 0 of the second aisle, on its left");
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the corner is served"));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, CORNER_CHEST, Items.IRON_INGOT),
                        IRON_COUNT, "the rack in the corner was served"))
                .thenExecute(() -> {
                    helper.assertValueEqual(dock(helper).craneState().pose().branch(), 1,
                            "the crane stood on the second aisle to reach it");
                    ItemCensus.assertEquals(helper, conserved, "after the corner was served");
                })
                .thenSucceed();
    }

    /**
     * The other direction: items stocked round the corner come <b>back</b>. A player asks the output station on aisle
     * A for iron that lies on aisle B, and the crane fetches it — out round the bend, back round it again.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void craneretrievesroundacorner(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
        placeOutput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper).outputStations().size(), 1, "the output joined");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) IRON_COUNT,
                            "and the iron round the corner is counted");
                })
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                            "while the crane fetches from round the corner"));
                    request(helper, new ItemStack(Items.IRON_INGOT), IRON_COUNT);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) IRON_COUNT,
                        "the iron came back round the corner to the output"))
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), 0,
                            "and left the chest it was stocked in");
                    ItemCensus.assertEquals(helper, conserved, "after the retrieval");
                })
                .thenSucceed();
    }

    /** A turn is audible: the machine's own deep iron groan, once per corner, never in a warehouse that does not bend. */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void craneturnsoundsatacorner(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        SoundCounter sounds = new SoundCounter(helper);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> {
                    sounds.start();
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "the iron went round the corner"))
                .thenExecute(() -> {
                    sounds.stop();
                    helper.assertTrue(sounds.count(TURN_SOUND) > 0,
                            "the machine was heard turning at the corner");
                })
                .thenSucceed();
    }

    /**
     * <b>An address names the aisle it is on.</b> While a job crosses the corner, the crane's goggle data reads "from
     * {@code A-01-01L} to {@code B-01-03R}": it carries one aisle letter per branch (M21, ADR-033), because a
     * warehouse that bends has one per aisle. Up to 0.5.0 there was a single letter, which was right while there was a
     * single aisle — and which would name every rack of every further aisle with the letter of the one at the dock,
     * in the crane's tooltip, in the controller's and on a "Crane Status" display.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranenamesarackbytheaisleitison(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        AtomicReference<String> source = new AtomicReference<>();
        AtomicReference<String> target = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> {
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                    helper.onEachTick(() -> {
                        if (source.get() != null)
                            return;
                        CraneGoggleInfo info = dock(helper).goggleInfo();
                        info.job().filter(job -> job.source().branch() != job.target().branch()).ifPresent(job -> {
                            source.set(info.address(job.source()));
                            target.set(info.address(job.target()));
                        });
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(source.get() != null,
                        "the crane took a job from one aisle to another"))
                .thenExecute(() -> {
                    helper.assertTrue(source.get().startsWith("A-"),
                            "the input it picks from is named on aisle A: " + source.get());
                    helper.assertTrue(target.get().startsWith("B-"),
                            "the rack round the corner is named on aisle B: " + target.get());
                })
                .thenSucceed();
    }

    // --- persistence and a network that changes under a running job ------------------------------------------------

    /**
     * A crane saved <b>in the middle of a quarter turn</b> comes back in the middle of that quarter turn: same aisle,
     * same position, same yaw between the two headings, same job, same items in its head. The turn's progress is the
     * yaw itself, which is why no new field and no new phase were needed for it — and why this works at all.
     * <p>
     * The live crane then finishes the job, so the save is a copy of something that really was going somewhere.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranekeepsamidturnsaveandcarrieson(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        AtomicReference<CompoundTag> saved = new AtomicReference<>();
        AtomicReference<CranePose> poseAtSave = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT)))
                .thenExecute(() -> helper.onEachTick(() -> {
                    if (saved.get() != null)
                        return;
                    StackerCraneBlockEntity dock = dock(helper);
                    CranePose pose = dock.craneState().pose();
                    // Exactly the moment this test is about: the machine is between two headings.
                    if (pose.isAligned() || dock.currentJob().isEmpty())
                        return;
                    poseAtSave.set(pose);
                    saved.set(dock.saveWithFullMetadata(helper.getLevel().registryAccess()));
                }))
                .thenWaitUntil(() -> helper.assertTrue(saved.get() != null, "the crane was caught in mid-turn"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    StackerCraneBlockEntity dock = dock(helper);
                    BlockEntity loaded = BlockEntity.loadStatic(dock.getBlockPos(), dock.getBlockState(), saved.get(),
                            registries);
                    if (!(loaded instanceof StackerCraneBlockEntity loadedDock)) {
                        helper.fail("the saved dock did not load", DOCK);
                        return;
                    }
                    CranePose before = poseAtSave.get();
                    helper.assertValueEqual(loadedDock.craneState().pose(), before,
                            "the crane comes back exactly where it stood, mid-turn");
                    helper.assertFalse(loadedDock.craneState().pose().isAligned(),
                            "with the turn still half done");
                    helper.assertValueEqual(loadedDock.craneState().pose().branch(), before.branch(),
                            "on the aisle it was named on");
                    helper.assertValueEqual(loadedDock.currentJob().map(job -> job.id()),
                            dock.currentJob().map(job -> job.id()), "with the same job");
                    helper.assertValueEqual(loadedDock.heldItems().totalCount(), dock.heldItems().totalCount(),
                            "and the same items in its head");
                    helper.assertValueEqual(loadedDock.networkGeometry().branchCount(), 2,
                            "and it knows the warehouse bends before any controller tells it again");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "and the real crane finishes the job it was caught in"))
                .thenSucceed();
    }

    /**
     * <b>The rail under the crane is broken while it turns.</b> The machine is on the corner block, half way through
     * its quarter turn, holding a player's iron — and that very block is taken away. Aisle B is then joined to nothing
     * and leaves the warehouse, so the crane is standing on rails the warehouse no longer has, with items in its
     * grabber and no route to anywhere at all.
     * <p>
     * What must happen is the least dramatic thing possible: every location reads as missing, the machine goes back
     * onto the aisle at the dock — the one this warehouse still has — and the existing reroute ladder puts the iron
     * somewhere a player can open. Here that is the input station it came from, which is what a store job falls back
     * to when nothing will take its items. It drops nothing, destroys nothing, and the item census is exact on every
     * tick of it. Putting the rail back is the whole cure — the aisle returns under the same letter and the iron is
     * stored after all.
     * <p>
     * This is the case the design calls the physically stranded crane. Its first answer was "hold the items and wait
     * for the player", which is right while there is a chance of delivering them and wrong as soon as there is not:
     * nothing ever wrote a pose's aisle back, so the machine held them for ever and the warehouse was dead behind it
     * (M21 review fix). The machine is <b>already drawn</b> on the aisle at the dock once its own aisle leaves the
     * network, so putting it there moves nothing a player can see.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranekeepsitsitemswhenarailbreaksmidturn(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<Boolean> broken = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> {
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while a rail breaks under a running job");
                        if (broken.get())
                            return;
                        StackerCraneBlockEntity dock = dock(helper);
                        CranePose pose = dock.craneState().pose();
                        // Exactly the moment: named on aisle B, half way round the turn, iron in the grabber.
                        if (pose.branch() != 1 || pose.isAligned() || dock.heldItems().count(IRON) == 0)
                            return;
                        broken.set(true);
                        helper.setBlock(CORNER, Blocks.AIR);
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(broken.get(),
                        "the corner rail was broken while the crane turned on it"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(WATCH_TICKS, () -> {
                    StackerCraneBlockEntity dock = dock(helper);
                    helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                            "the second aisle is joined to nothing and left the warehouse");
                    helper.assertValueEqual(dock.craneState().pose().branch(), 0,
                            "and the machine is back on the aisle at the dock, where it is drawn");
                    helper.assertValueEqual(dock.heldItems().count(IRON) + stationCount(helper, INPUT, IRON),
                            (long) IRON_COUNT,
                            "every ingot it picked up is still in its grabber or back in the station it came from");
                    helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), 0,
                            "none of it went into a chest the machine cannot reach");
                    helper.assertTrue(helper.getLevel()
                            .getEntities(EntityType.ITEM, helper.getBounds(), entity -> true).isEmpty(),
                            "and none of it was dropped on the floor");
                    ItemCensus.assertEquals(helper, conserved, "while the warehouse is cut in two");
                })
                .thenExecute(() -> {
                    helper.setBlock(CORNER, WarehouseRailBlock.along(Direction.Axis.X));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "and once the rail is back every ingot is stored after all"))
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the warehouse healed"))
                .thenSucceed();
    }

    /**
     * <b>A rail breaks behind the crane, and the target is round the corner.</b> The machine is out on aisle B with a
     * player's iron in its grabber, on its way back to the output on aisle A, when a rail <i>between it and the
     * corner</i> is taken away. Its own aisle still exists and still meets the corner, so nothing about the
     * <b>branches</b> has changed — but there is no longer a route from where the machine really stands.
     * <p>
     * This is the case the controller and the crane used to answer differently (M21 review fix): the location check
     * asked whether the two aisles were joined, which they still were, while the motion asked for a route from the
     * crane's own point, which was empty. The machine stood still in {@code TRAVEL_TO_TARGET} with the arm in, no
     * pause reason, nothing in the log, and the warehouse dead for ever behind it, because a crane with a job takes no
     * other one.
     * <p>
     * What must happen instead: the target reads as out of reach at the very next location check, the existing ladder
     * takes over, and the machine comes back down the rails it still has — the same clamp that has always brought a
     * crane back into an aisle a player shortened — and delivers. Nothing is dropped, and the census is exact on every
     * tick.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranecomesbackwhenarailbreaksbehindit(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
        placeOutput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<Boolean> broken = new AtomicReference<>(false);
        AtomicReference<Boolean> reported = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) IRON_COUNT,
                            "the iron round the corner is counted");
                })
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while a rail breaks behind the crane");
                        StackerCraneBlockEntity dock = dock(helper);
                        CranePose pose = dock.craneState().pose();
                        if (broken.get()) {
                            CranePhase phase = dock.craneState().phase();
                            if (phase == CranePhase.REROUTE || phase == CranePhase.HOLDING)
                                reported.set(true);
                            return;
                        }
                        // The moment: out on aisle B beyond the rail that is about to go, iron in the grabber, on the
                        // way back to the output on the first aisle.
                        if (pose.branch() != 1 || pose.x() < 2.5 || dock.heldItems().count(IRON) == 0
                                || dock.craneState().phase() != CranePhase.TRAVEL_TO_TARGET)
                            return;
                        broken.set(true);
                        helper.setBlock(CORNER.south(2), Blocks.AIR);
                        dock.refreshGeometry();
                        controller(helper).relinkNow();
                    });
                    request(helper, new ItemStack(Items.IRON_INGOT), IRON_COUNT);
                })
                .thenWaitUntil(() -> helper.assertTrue(broken.get(),
                        "a rail behind the crane was taken away while it carried the iron back"))
                .thenWaitUntil(() -> helper.assertTrue(reported.get(),
                        "the crane reported the target as out of reach instead of standing still for ever"))
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) IRON_COUNT,
                        "and the iron reached the output after all"))
                .thenExecute(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                            "the second aisle still exists — it only became shorter");
                    helper.assertTrue(helper.getLevel()
                            .getEntities(EntityType.ITEM, helper.getBounds(), entity -> true).isEmpty(),
                            "nothing was dropped on the floor");
                    ItemCensus.assertEquals(helper, conserved, "after the warehouse worked round the broken rail");
                })
                .thenSucceed();
    }

    /**
     * <b>The corner breaks while the crane is parked round it.</b> The machine is idle and empty at the far end of
     * aisle B when a player takes the corner rail away, so aisle B is joined to nothing and leaves the warehouse
     * altogether. The crane's pose then names an aisle this warehouse does not have.
     * <p>
     * Nothing in the mod ever wrote a pose's aisle, so that label used to stay for ever — and the warehouse died with
     * it (M21 review fix): the planner was told the machine stood at the dock, handed it a job, and the crane's own
     * location check aborted that job on its very next tick, four times a second, with no line anywhere saying why.
     * <p>
     * What must happen: the machine goes back onto the aisle at the dock — where the renderer has been drawing it ever
     * since its own aisle left the network — and the warehouse simply carries on as the straight one it now is. The
     * iron already stocked round the corner stays in its chest, where a player can still open it.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void cranecomesbackwhenitsaisleleavesthewarehouse(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FAR_RACK, FAR_CHEST, Direction.WEST);
        placeInput(helper);
        if (!cornersEnabled()) {
            assertNothingMoves(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<Boolean> counting = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT)))
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "the iron was stored round the corner"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(dock(helper).craneState().phase(), CranePhase.IDLE, "the crane is idle");
                    helper.assertValueEqual(dock(helper).craneState().pose().branch(), 1,
                            "and parked on the second aisle, which is where it delivered");
                })
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    counting.set(true);
                    helper.onEachTick(() -> {
                        if (counting.get())
                            ItemCensus.assertEquals(helper, conserved,
                                    "while the aisle under the crane leaves the warehouse");
                    });
                    helper.setBlock(CORNER, Blocks.AIR);
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                            "the second aisle is joined to nothing and left the warehouse");
                    helper.assertValueEqual(dock(helper).craneState().pose().branch(), 0,
                            "and the machine is back on the aisle at the dock, where it is drawn");
                    // A new rack on the aisle that is left, and more work: the warehouse has to serve it.
                    placeStorage(helper, NEAR_RACK, NEAR_CHEST, Direction.NORTH);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack on the first aisle joined"))
                // More items than the test started with, so the census starts again from what is really there.
                .thenExecute(() -> {
                    counting.set(false);
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    conserved.clear();
                    conserved.putAll(ItemCensus.take(helper));
                    counting.set(true);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, NEAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                        "the warehouse kept working with the aisle it has left"))
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(helper, FAR_CHEST, Items.IRON_INGOT), IRON_COUNT,
                            "and the iron round the corner is still in the chest a player can open");
                    ItemCensus.assertEquals(helper, conserved, "after the warehouse became straight");
                })
                .thenSucceed();
    }

    // --- the off switch --------------------------------------------------------------------------------------------

    /**
     * Whether a warehouse may bend at all: {@code aisle.maxBranches = 1} keeps every warehouse the single straight
     * aisle it was before 0.6, and the whole suite has to pass with it unedited.
     */
    private static boolean cornersEnabled() {
        return WareworksConfig.maxBranches() > 1;
    }

    /**
     * What each test asserts instead while corners are switched off: the warehouse is one straight aisle, nothing
     * beyond the corner belongs to it, the crane never leaves the aisle at the dock, and every item stays put.
     * <p>
     * Not every test of this holder feeds the warehouse through an input — the retrieval tests stock a rack round the
     * corner instead, and their iron is already exactly where it has to stay — so the input is only filled when there
     * really is one. Asking for a handler that is not there would fail the test on its own setup rather than on what
     * the off switch does.
     */
    private static void assertNothingMoves(GameTestHelper helper) {
        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                        "one aisle, exactly as before 0.6"))
                .thenExecute(() -> {
                    if (helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(INPUT), null) != null)
                        insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> helper.assertValueEqual(dock(helper).craneState().pose().branch(), 0,
                            "the crane never leaves the aisle at the dock"));
                })
                .thenExecuteAfter(WATCH_TICKS, () -> ItemCensus.assertEquals(helper, conserved,
                        "with corners switched off"))
                .thenSucceed();
    }

    // --- building ---------------------------------------------------------------------------------------------------

    /** Motor, dock, controller, aisle A's rails and aisle B running south out of the corner block. */
    private static void buildCorner(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        for (int x = 1; x <= FIRST_RAILS; x++)
            helper.setBlock(DOCK.east(x), WarehouseRailBlock.along(Direction.Axis.X));
        for (int z = 1; z <= SECOND_RAILS; z++)
            helper.setBlock(CORNER.south(z), WarehouseRailBlock.along(Direction.Axis.Z));
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        motor(helper).generatedSpeed.setValue(TEST_RPM);
    }

    /** A chest with {@code contents} behind an interface facing {@code away} from the rails. */
    private static void placeStorage(GameTestHelper helper, BlockPos rack, BlockPos chest, Direction away,
            ItemStack... contents) {
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = handlerAt(helper, chest);
        for (ItemStack stack : contents)
            ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, away));
    }

    private static void placeInput(GameTestHelper helper) {
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));
    }

    private static void placeOutput(GameTestHelper helper) {
        helper.setBlock(OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
    }

    /** Sets the output's filter and gives it one redstone rising edge, exactly like a player with a lever. */
    private static void request(GameTestHelper helper, ItemStack filter, int amount) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(OUTPUT));
        FilteringBehaviour behaviour = be == null ? null : BlockEntityBehaviour.get(be, FilteringBehaviour.TYPE);
        if (behaviour == null) {
            helper.fail("the output has no request filter", OUTPUT);
            return;
        }
        helper.assertTrue(behaviour.setFilter(filter), "the output accepts the filter " + filter);
        behaviour.count = amount; // after setFilter, which may clamp the count
        helper.setBlock(TRIGGER, Blocks.AIR);
        helper.setBlock(TRIGGER, Blocks.REDSTONE_BLOCK);
    }

    // --- reading ------------------------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(GameTestHelper helper) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(CONTROLLER));
        if (be == null) {
            helper.fail("missing warehouse controller", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static StackerCraneBlockEntity dock(GameTestHelper helper) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(DOCK));
        if (be == null) {
            helper.fail("missing stacker crane dock", DOCK);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static CreativeMotorBlockEntity motor(GameTestHelper helper) {
        CreativeMotorBlockEntity be = com.simibubi.create.AllBlockEntityTypes.MOTOR
                .getNullable(helper.getLevel(), helper.absolutePos(MOTOR));
        if (be == null) {
            helper.fail("missing creative motor", MOTOR);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    private static WarehouseLayout warehouse(GameTestHelper helper) {
        WarehouseLayout layout = controller(helper).warehouse().orElse(null);
        if (layout == null) {
            helper.fail("the controller has no warehouse", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return layout;
    }

    /** The rack position of a test-relative world block, as the warehouse names it. */
    private static RackPosition rackOf(GameTestHelper helper, BlockPos pos) {
        return controller(helper).locationAt(helper.absolutePos(pos))
                .map(record -> record.position())
                .orElseGet(() -> {
                    helper.fail("no warehouse member", pos);
                    throw new AssertionError("unreachable");
                });
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

    private static void insertAll(GameTestHelper helper, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handlerAt(helper, pos), stack.copy(), false);
        helper.assertTrue(rest.isEmpty(), "inventory at " + pos + " rejected " + rest);
    }

    private static int countIn(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null)
            return 0;
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    /** Items of {@code key} in the buffer of the station at a test-relative position. */
    private static long stationCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (be instanceof dev.wareworks.content.station.WarehouseStationBlockEntity station)
            return station.bufferedItems().count(key);
        return 0L;
    }

    /** Counts the sounds the server played inside the test area, so a turn can be heard rather than assumed. */
    private static final class SoundCounter implements Consumer<PlayLevelSoundEvent.AtPosition> {
        private final Level level;
        private final AABB area;
        private final List<ResourceLocation> heard = new ArrayList<>();
        private final AtomicInteger ignored = new AtomicInteger();

        SoundCounter(GameTestHelper helper) {
            this.level = helper.getLevel();
            this.area = helper.getBounds();
        }

        void start() {
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, this);
        }

        void stop() {
            NeoForge.EVENT_BUS.unregister(this);
        }

        @Override
        public void accept(PlayLevelSoundEvent.AtPosition event) {
            if (event.getLevel() != level || event.getSound() == null || !area.contains(event.getPosition())) {
                ignored.incrementAndGet();
                return;
            }
            heard.add(event.getSound().value().getLocation());
        }

        int count(ResourceLocation sound) {
            return (int) heard.stream().filter(sound::equals).count();
        }
    }
}
