package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CraneNetwork;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
 * GameTests of a warehouse whose rails <b>split</b> (M22, issue #2, ADR-033, ADR-035) — the thing M21 refused to walk
 * into and reported as "the rails split here".
 * <p>
 * The shape under test is the <b>comb</b>: one main run out of the dock with three side aisles hanging off it. It is
 * the second tested shape beside M21's L and the one the milestone exists for, because it is the literal
 * demonstration of issue #2 — goods from every aisle reach <b>one</b> block. Beside it, a T's ownership (the rule M21
 * built, which needs no change for three ways and is proved here rather than altered), a ring where the planner picks
 * the cheaper way round and the crane really drives it, an aisle a configured maximum cut loose from the crane, and a
 * side aisle appearing and disappearing under a running job.
 * <p>
 * Every test that moves items takes an {@link ItemCensus} on <b>every tick</b> while they move: a hand-over at a
 * junction renames the machine on the block two aisles share, and a rename that duplicated or lost an item would
 * otherwise show as a warehouse that merely counts wrong.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseCombGameTests {
    /** Config overrides need their own batch, because the tests of one batch run at the same time. */
    static final String CUT_BATCH = "wareworkscombcut";

    private static final int TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 12;
    private static final int WATCH_TICKS = 200;
    private static final int TEST_RPM = 128;

    /** The main run: rails x = 2..13 at z = AISLE_Z, i.e. 12 rails beyond the dock. */
    private static final int AISLE_Z = 1;
    private static final int MAIN_RAILS = 12;
    /** Rails of each side aisle, running south out of the main run. */
    private static final int TOOTH_RAILS = 3;
    /** Where the side aisles branch off, as offsets along the main run from the dock. */
    private static final int[] TEETH = { 4, 8, 12 };

    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);

    /** Input station at position 1 on the left of the main run, north of it. */
    private static final BlockPos INPUT = new BlockPos(2, BASE_Y, AISLE_Z - 1);
    /** Output station at position 2 on the left of the main run — the <b>one block</b> every aisle delivers to. */
    private static final BlockPos OUTPUT = new BlockPos(3, BASE_Y, AISLE_Z - 1);
    private static final BlockPos TRIGGER = new BlockPos(3, BASE_Y + 1, AISLE_Z - 1);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final int COUNT = 8;

    private WarehouseCombGameTests() {
    }

    // --- the comb ---------------------------------------------------------------------------------------------------

    /** Discovery of the comb: one letter for the main run, one aisle per tooth, one junction each. */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void combisoneconnectedwarehouse(GameTestHelper helper) {
        buildComb(helper);
        if (!branchingEnabled()) {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                            "one aisle with the off switch on"))
                    .thenSucceed();
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), TEETH.length + 1,
                        "the main run and one aisle per tooth"))
                .thenExecute(() -> {
                    RailNetwork network = network(helper);
                    helper.assertValueEqual(network.stop(), NetworkStop.END,
                            "the rails simply end: a comb is refused nowhere any more");
                    helper.assertValueEqual(network.firstBranchLength(), MAIN_RAILS,
                            "the main run is one aisle through every junction");
                    helper.assertValueEqual(network.geometry().links().size(), TEETH.length,
                            "one junction per tooth");
                    helper.assertValueEqual(network.rails(), MAIN_RAILS + TEETH.length * TOOTH_RAILS,
                            "every rail belongs to an aisle, and a junction is counted once");
                    WarehouseLayout layout = warehouse(helper);
                    // Four different letters, so an address names which aisle a rack is on.
                    Set<Character> letters = new TreeSet<>();
                    for (int branch = 0; branch < layout.branchCount(); branch++)
                        layout.branch(branch).letter().ifPresent(letters::add);
                    helper.assertValueEqual(letters.size(), TEETH.length + 1, "one letter per aisle");
                    helper.assertValueEqual(layout.branch(0).letter(), Optional.of('A'),
                            "and the aisle at the dock keeps the controller's own letter");
                    for (int branch = 1; branch < layout.branchCount(); branch++)
                        helper.assertTrue(layout.routes().reachable(0, branch),
                                "every aisle is joined to the one at the dock (" + branch + ")");
                    // The warehouse OWNS its route table: it is derived when the rails change and read after that, so
                    // every ask hands back the very same object and a planning pass costs no junction walk at all
                    // (M22 review fix; it used to come out of a static cache that stopped hitting at nine shapes).
                    helper.assertTrue(layout.routes() == layout.routes(),
                            "a warehouse hands back the same route table on every ask");
                    helper.assertTrue(warehouse(helper).routes() == layout.routes(),
                            "and the controller keeps that very warehouse, so a later pass reads the same table");
                    // The crane reads the same derived rows as the controller that plans its jobs.
                    helper.assertTrue(dock(helper).craneNetwork() instanceof CraneNetwork.Discovered discovered
                            && discovered.routes() == dock(helper).warehouse().routes(),
                            "the crane drives on the table its own warehouse holds");
                })
                .thenSucceed();
    }

    /**
     * The headline of the milestone, in the direction a warehouse is filled: an item put into the <b>one</b> input is
     * stored in the <b>last</b> side aisle, three blocks down a tooth twelve rails along the run. The crane drives the
     * whole comb, turns onto the tooth, and every ingot arrives.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void combstoresdownitsfarthestaisle(GameTestHelper helper) {
        buildComb(helper);
        int tooth = TEETH.length - 1;
        placeStorage(helper, toothRack(tooth), toothChest(tooth), Direction.EAST);
        placeInput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, INPUT);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), TEETH.length + 1, "the comb is whole");
                    helper.assertValueEqual(controller(helper).storageLocations().size(), 1, "the far rack joined");
                    helper.assertValueEqual(controller(helper).inputStations().size(), 1, "and the input");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(rackOf(helper, toothRack(tooth)),
                            new RackPosition(TEETH.length, TOOTH_RAILS, 0, Side.LEFT),
                            "the rack is position 3 on the left of the last aisle");
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while the crane drives the comb");
                        visited.add(dock(helper).craneState().pose().branch());
                    });
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, toothChest(tooth), Items.IRON_INGOT),
                        COUNT, "every ingot arrived at the far end of the last aisle"))
                .thenExecute(() -> {
                    helper.assertTrue(visited.contains(TEETH.length),
                            "the crane really stood on that aisle, it did not reach across");
                    helper.assertValueEqual(countIn(helper, INPUT, Items.IRON_INGOT), 0, "the input is empty");
                    ItemCensus.assertEquals(helper, conserved, "after the comb was driven");
                })
                .thenSucceed();
    }

    /**
     * And the direction a warehouse is emptied — <b>the sentence of issue #2</b>: three items, one in each side
     * aisle, all three fetched to the one output station beside the dock. One controller, one crane, one terminal, one
     * address space.
     * <p>
     * It also states the price in as many words: one crane drives out and back along every tooth, so the three trips
     * cannot overlap. That is physically honest and it is what makes several cranes on one network the obvious next
     * milestone.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void combretrievesfromeveryaisleintooneblock(GameTestHelper helper) {
        buildComb(helper);
        Item[] stocked = { Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND };
        for (int tooth = 0; tooth < TEETH.length; tooth++)
            placeStorage(helper, toothRack(tooth), toothChest(tooth), Direction.EAST,
                    new ItemStack(stocked[tooth], COUNT));
        placeOutput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, null);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper).storageLocations().size(), TEETH.length,
                            "one rack per aisle joined");
                    helper.assertValueEqual(controller(helper).outputStations().size(), 1, "and the one output");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) COUNT, "the iron is counted");
                    helper.assertValueEqual(controller(helper).countOf(GOLD), (long) COUNT, "and the gold");
                    helper.assertValueEqual(controller(helper).countOf(DIAMOND), (long) COUNT, "and the diamonds");
                })
                .thenExecute(() -> {
                    // Three different aisles, so the addresses really name three: this is the proof that the comb is
                    // one address space rather than three warehouses that happen to touch.
                    Set<Character> aisles = new TreeSet<>();
                    for (int tooth = 0; tooth < TEETH.length; tooth++)
                        aisles.add(addressOf(helper, toothRack(tooth)).aisle());
                    helper.assertValueEqual(aisles.size(), TEETH.length, "one aisle letter per rack: " + aisles);
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while the comb is emptied");
                        visited.add(dock(helper).craneState().pose().branch());
                    });
                    request(helper, new ItemStack(Items.IRON_INGOT), COUNT);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) COUNT,
                        "the iron came out of the first aisle"))
                .thenExecute(() -> request(helper, new ItemStack(Items.GOLD_INGOT), COUNT))
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, GOLD), (long) COUNT,
                        "the gold came out of the second"))
                .thenExecute(() -> request(helper, new ItemStack(Items.DIAMOND), COUNT))
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, DIAMOND), (long) COUNT,
                        "and the diamonds out of the third"))
                .thenExecute(() -> {
                    for (int tooth = 0; tooth < TEETH.length; tooth++)
                        helper.assertValueEqual(countIn(helper, toothChest(tooth), stocked[tooth]), 0,
                                "aisle " + tooth + " handed its stock over");
                    for (int branch = 1; branch <= TEETH.length; branch++)
                        helper.assertTrue(visited.contains(branch),
                                "the crane stood on every aisle it fetched from (" + branch + ")");
                    ItemCensus.assertEquals(helper, conserved, "after every aisle was emptied");
                })
                .thenSucceed();
    }

    // --- a tee, and whose rack a junction's neighbour is -------------------------------------------------------------

    /**
     * <b>Three-way ownership needs no new rule.</b> The block diagonally beside a tooth is laterally beside a straight
     * rail of the main run <i>and</i> of that tooth, so it offers one candidate per aisle — and each of them wants a
     * different facing of the same block, so a member can satisfy at most one. The rule M21 built decides a T without
     * a line of change; this test is what says so.
     * <p>
     * Both racks are then really served, by the aisle each of them faces: the one on the tooth is reached with the
     * crane <b>standing on the tooth</b>, not by reaching across from the run it is also beside.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void arackbesideateejoinstheaisleitfaces(GameTestHelper helper) {
        buildComb(helper);
        // One block, two aisles: east of the first rail of tooth 0, and south of the main run at the same spot.
        BlockPos shared = toothSide(0);
        placeStorage(helper, shared, shared.east(), Direction.EAST);
        // The mirror image at tooth 1, facing the main run instead, so the same geometry resolves the other way.
        BlockPos onTheRun = toothSide(1);
        helper.setBlock(onTheRun.south(), Blocks.CHEST);
        helper.setBlock(onTheRun, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.SOUTH));
        placeInput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, INPUT);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 2,
                        "both racks joined"))
                .thenExecute(() -> {
                    WarehouseLayout layout = warehouse(helper);
                    List<RackPosition> candidates = layout.candidates(helper.absolutePos(shared));
                    helper.assertValueEqual(candidates.size(), 2, "two aisles reach that block");
                    helper.assertValueEqual(rackOf(helper, shared), new RackPosition(1, 1, 0, Side.LEFT),
                            "and the one facing the tooth belongs to the tooth");
                    helper.assertValueEqual(rackOf(helper, onTheRun), new RackPosition(0, TEETH[1] + 1, 0, Side.RIGHT),
                            "while the one facing the run belongs to the run");
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while the tee is served");
                        visited.add(dock(helper).craneState().pose().branch());
                    });
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        countIn(helper, shared.east(), Items.IRON_INGOT) + countIn(helper, onTheRun.south(),
                                Items.IRON_INGOT),
                        COUNT, "the iron was stored at one of the two"))
                .thenExecute(() -> {
                    if (countIn(helper, shared.east(), Items.IRON_INGOT) > 0)
                        helper.assertTrue(visited.contains(1),
                                "the rack on the tooth was served from the tooth, not across from the run");
                    ItemCensus.assertEquals(helper, conserved, "after the tee was served");
                })
                .thenSucceed();
    }

    // --- a ring -----------------------------------------------------------------------------------------------------

    /**
     * A ring: two ways round to the same rack, one of them far cheaper. The planner costs them with
     * {@code crane.turnPenaltyBlocks}, picks the cheaper one, and the crane <b>really drives that one</b> — which is
     * the whole reason the controller and the machine ask at the same turn price (ADR-035): on rails that close on
     * themselves, two different answers to "the way there" would be a machine driving a route nobody planned.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void ringdrivesthecheaperwayround(GameTestHelper helper) {
        buildRing(helper);
        // South of the bottom run of the ring, so it belongs to that run and faces away from it.
        BlockPos rack = DOCK.offset(4, 0, 3);
        placeStorage(helper, rack, rack.south(), Direction.SOUTH, new ItemStack(Items.IRON_INGOT, COUNT));
        placeOutput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, null);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 4, "the ring is four straight aisles");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) COUNT, "the iron is counted");
                    helper.assertValueEqual(controller(helper).outputStations().size(), 1, "and the output joined");
                })
                .thenExecute(() -> {
                    RackPosition position = rackOf(helper, rack);
                    helper.assertValueEqual(position.branch(), 2, "the rack is on the far side of the ring");
                    CraneRoute route = warehouse(helper).route(0, 0.0, position.branch(), position.x())
                            .orElseThrow(() -> new AssertionError("no route round the ring"));
                    List<Integer> legs = new ArrayList<>();
                    route.legs().forEach(leg -> legs.add(leg.branch()));
                    helper.assertValueEqual(legs, List.of(0, 1, 2),
                            "the cheap way round: six blocks and two turns, not fourteen and two");
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        ItemCensus.assertEquals(helper, conserved, "while the crane rounds the ring");
                        visited.add(dock(helper).craneState().pose().branch());
                    });
                    request(helper, new ItemStack(Items.IRON_INGOT), COUNT);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) COUNT,
                        "the iron came back round the ring"))
                .thenExecute(() -> {
                    helper.assertTrue(visited.contains(1), "the crane drove the near side of the ring");
                    helper.assertFalse(visited.contains(3),
                            "and never the far side, which is what the planner costed");
                    ItemCensus.assertEquals(helper, conserved, "after the ring was driven");
                })
                .thenSucceed();
    }

    // --- an aisle the crane cannot reach ----------------------------------------------------------------------------

    /**
     * <b>Reachability is live.</b> A warehouse's rails are connected by construction — the scan floods from the dock,
     * so an aisle it could not reach is simply not part of the warehouse — with one exception:
     * {@code aisle.maxAisleLength} truncates the main run and can take a tooth's junction with it. Such an aisle is
     * <b>kept</b> rather than deleted: it keeps its address and its stock, its members say on their own goggles that
     * the crane cannot reach them, the planner answers {@link NoJobReason#UNREACHABLE} instead of inventing a job, and
     * raising the number joins it again in one scan.
     * <p>
     * Deleting it instead would take a player's chests out of the address space because a number in a config file is
     * too small, which is the one outcome worse than saying so.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, batch = CUT_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void anaislethecranecannotreachsaysso(GameTestHelper helper) {
        buildComb(helper);
        int tooth = TEETH.length - 1;
        placeStorage(helper, toothRack(tooth), toothChest(tooth), Direction.EAST,
                new ItemStack(Items.IRON_INGOT, COUNT));
        placeOutput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, null);
            return;
        }
        int cut = TEETH[tooth] - 1;

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack on the last aisle joined"))
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    // Shorter than the junction the last tooth hangs off, so the cut takes that junction away.
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxAisleLength, cut);
                    dock(helper).refreshGeometry();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(network(helper).stop(), NetworkStop.MAX_LENGTH,
                            "the warehouse says which maximum stopped it");
                    helper.assertValueEqual(warehouse(helper).branch(0).geometry().length(), cut,
                            "the main run was truncated at its far end");
                    helper.assertFalse(warehouse(helper).routes().reachable(0, TEETH.length),
                            "and the last aisle is no longer joined to the crane");
                })
                .thenExecute(() -> {
                    AisleAssignment assignment = assignmentOf(helper, toothRack(tooth));
                    helper.assertValueEqual(assignment.state(), AisleAssignment.State.UNREACHABLE,
                            "its rack says so on its own goggles");
                    helper.assertTrue(assignment.address().isPresent(), "while keeping the address it had");
                    // And it survives the trip to the client, which is where those goggles are really read: a state
                    // the reader does not know reads back as "not part of an aisle", which would be a lie about a
                    // perfectly placed interface.
                    CompoundTag synced = new CompoundTag();
                    assignment.write(synced);
                    helper.assertValueEqual(AisleAssignment.read(synced), assignment,
                            "the goggle data survives the packet");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) COUNT,
                            "and the stock it holds is still counted");
                    request(helper, new ItemStack(Items.IRON_INGOT), COUNT);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).lastPlanReason(),
                        Optional.of(NoJobReason.UNREACHABLE), "and the controller says why nothing happens"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), 0L, "nothing was fetched");
                    ItemCensus.assertEquals(helper, conserved, "while the aisle was cut off");
                    ConfigOverrides.restoreAll();
                    dock(helper).refreshGeometry();
                })
                .thenWaitUntil(() -> helper.assertTrue(warehouse(helper).routes().reachable(0, TEETH.length),
                        "raising the number joins the aisle again"))
                .thenWaitUntil(() -> helper.assertValueEqual(stationCount(helper, OUTPUT, IRON), (long) COUNT,
                        "and the request it had waited for is served"))
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the aisle came back"))
                .thenSucceed();
    }

    /** Restores {@code aisle.maxAisleLength} even when the test above fails before its own restore. */
    @AfterBatch(batch = CUT_BATCH)
    public static void restoreCutConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- building and tearing down under a running job --------------------------------------------------------------

    /**
     * A side aisle appearing and disappearing while a job runs. The crane is driving to the far tooth when a player
     * lays a rail that makes a fourth tooth, and takes it away again a moment later: the warehouse grows, shrinks, and
     * the job it was already carrying out finishes with an exact item census on every tick in between.
     * <p>
     * Nothing about this is a special case in the crane: a route is derived from the live network every tick and never
     * stored, so a machine simply follows the rails that are there ({@code RouteModel}).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void anaisleaddedandremovedunderarunningjob(GameTestHelper helper) {
        buildComb(helper);
        int tooth = TEETH.length - 1;
        placeStorage(helper, toothRack(tooth), toothChest(tooth), Direction.EAST);
        placeInput(helper);
        if (!branchingEnabled()) {
            assertNothingMoves(helper, INPUT);
            return;
        }
        // A rail laid beside the main run between two teeth: a fourth aisle of one rail, in nobody's way.
        BlockPos extra = DOCK.offset(TEETH[0] + 2, 0, -1);

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the far rack joined"))
                .thenExecute(() -> {
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                            "while the warehouse is rebuilt under a running job"));
                })
                .thenWaitUntil(() -> helper.assertTrue(dock(helper).craneState().job().isPresent(),
                        "a job is running"))
                .thenExecute(() -> helper.setBlock(extra, WarehouseRailBlock.along(Direction.Axis.Z)))
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), TEETH.length + 2,
                        "the rail joined as an aisle of its own, without a scan being asked for"))
                .thenExecute(() -> helper.setBlock(extra, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), TEETH.length + 1,
                        "and taking it away leaves the comb it was"))
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, toothChest(tooth), Items.IRON_INGOT),
                        COUNT, "the job that was running finished all the same"))
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the rebuild"))
                .thenSucceed();
    }

    // --- the off switch ---------------------------------------------------------------------------------------------

    /** Whether a warehouse may split at all: {@code aisle.maxBranches = 1} keeps every warehouse one straight aisle. */
    private static boolean branchingEnabled() {
        return WareworksConfig.maxBranches() > 1 && WareworksConfig.maxJunctions() > 0;
    }

    /**
     * What each test asserts instead while branching is switched off: the warehouse is one straight aisle and every
     * item stays exactly where it is.
     */
    private static void assertNothingMoves(GameTestHelper helper, BlockPos fill) {
        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                        "one aisle, exactly as before 0.6"))
                .thenExecute(() -> {
                    if (fill != null && helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(fill), null) != null)
                        insertAll(helper, fill, new ItemStack(Items.IRON_INGOT, COUNT));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> helper.assertValueEqual(dock(helper).craneState().pose().branch(), 0,
                            "the crane never leaves the aisle at the dock"));
                })
                .thenExecuteAfter(WATCH_TICKS, () -> ItemCensus.assertEquals(helper, conserved,
                        "with branching switched off"))
                .thenSucceed();
    }

    // --- building ---------------------------------------------------------------------------------------------------

    /** Motor, dock, controller, the main run and three side aisles running south out of it. */
    private static void buildComb(GameTestHelper helper) {
        placeDockAndController(helper);
        for (int x = 1; x <= MAIN_RAILS; x++)
            helper.setBlock(DOCK.offset(x, 0, 0), WarehouseRailBlock.along(Direction.Axis.X));
        for (int tooth : TEETH) {
            for (int z = 1; z <= TOOTH_RAILS; z++)
                helper.setBlock(DOCK.offset(tooth, 0, z), WarehouseRailBlock.along(Direction.Axis.Z));
        }
    }

    /**
     * A ring: the main run east, a short side down at offset 2, the bottom run back east, and a long side up at
     * offset 8. The two ways to the bottom run are deliberately very different — six blocks and two turns one way,
     * fourteen and two the other.
     */
    private static void buildRing(GameTestHelper helper) {
        placeDockAndController(helper);
        for (int x = 1; x <= 8; x++)
            helper.setBlock(DOCK.offset(x, 0, 0), WarehouseRailBlock.along(Direction.Axis.X));
        for (int z = 1; z <= 2; z++) {
            helper.setBlock(DOCK.offset(2, 0, z), WarehouseRailBlock.along(Direction.Axis.Z));
            helper.setBlock(DOCK.offset(8, 0, z), WarehouseRailBlock.along(Direction.Axis.Z));
        }
        for (int x = 3; x <= 7; x++)
            helper.setBlock(DOCK.offset(x, 0, 2), WarehouseRailBlock.along(Direction.Axis.X));
    }

    private static void placeDockAndController(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        motor(helper).generatedSpeed.setValue(TEST_RPM);
    }

    /** The rack at the far end of one tooth: east of its last rail, so it is position 3 on that aisle's left. */
    private static BlockPos toothRack(int tooth) {
        return DOCK.offset(TEETH[tooth] + 1, 0, TOOTH_RAILS);
    }

    private static BlockPos toothChest(int tooth) {
        return DOCK.offset(TEETH[tooth] + 2, 0, TOOTH_RAILS);
    }

    /** The block east of a tooth's first rail: beside that tooth <b>and</b> beside the main run. */
    private static BlockPos toothSide(int tooth) {
        return DOCK.offset(TEETH[tooth] + 1, 0, 1);
    }

    /** A chest with {@code contents} behind an interface facing {@code away} from the rails it belongs to. */
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
                .setValue(WarehouseOutputBlock.FACING, Direction.SOUTH));
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

    // --- reading ----------------------------------------------------------------------------------------------------

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

    private static RailNetwork network(GameTestHelper helper) {
        RailNetwork network = dock(helper).discoveredNetwork().orElse(null);
        if (network == null) {
            helper.fail("the dock recorded no network", DOCK);
            throw new AssertionError("unreachable");
        }
        return network;
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

    private static StorageAddress addressOf(GameTestHelper helper, BlockPos pos) {
        return warehouse(helper).address(rackOf(helper, pos))
                .orElseGet(() -> {
                    helper.fail("no address", pos);
                    throw new AssertionError("unreachable");
                });
    }

    /** What the goggles of the interface at {@code pos} say about its aisle, resolved exactly as they are in game. */
    private static AisleAssignment assignmentOf(GameTestHelper helper, BlockPos pos) {
        BlockPos world = helper.absolutePos(pos);
        if (!(helper.getLevel().getBlockEntity(world) instanceof WarehouseInterfaceBlockEntity member)) {
            helper.fail("no warehouse interface", pos);
            throw new AssertionError("unreachable");
        }
        return WarehouseRegistry.assignmentOf(helper.getLevel(), world, member);
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

    private static int countIn(GameTestHelper helper, BlockPos pos, Item item) {
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
        return be instanceof WarehouseStationBlockEntity station ? station.bufferedItems().count(key) : 0L;
    }
}
