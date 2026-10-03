package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalPreferences;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.registry.WareworksAttachments;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * The migration gate of M21 (issue #1, ADR-033): a world saved before rails could bend opens as the warehouse it was.
 * <p>
 * Where the other holders build their case from hand-written tags, these tests replay the <b>real bytes of a real
 * world</b> — the {@code wareworks:warehouse_controller} and {@code wareworks:stacker_crane} block entities of the
 * showcase world that {@code runShowcase} saved on 2026-09-27, before any of this milestone existed. Read straight out
 * of that world's region files, its controller at {@code (-1, -60, 0)} is:
 * <pre>
 * Layout:      {Facing: "east", Length: 14, Height: 4}
 * Locations:   49 entries, each {X: int, Y: int, Side: string, Kind: string, Stock: list} and <b>not one with a B</b>
 * Misaligned:  []          ScrollValue: 0          (and no Network, and no MisalignedBranches)
 * ProductionOrders: two COMPLETE orders, their stations {X: 5, Y: 1, Side: "R"} and {X: 7, Y: 2, Side: "L"}
 * </pre>
 * and its dock at {@code (0, -60, 0)} held
 * <pre>
 * Crane: {Pose: {X: 1.0d, Y: 0.0d, Arm: 0.0d, Side: "L"}, Target: {...}, Phase: "IDLE", ...}   (no B, no Yaw)
 * </pre>
 * {@link #migrationloadstherealpre06save} loads that controller tag into today's code and asserts the geometry, the
 * addresses, the records, the stock and the production orders it produces. {@link #migrationcranejobreloadsonastraightaisle}
 * closes the one gap those bytes leave — the saved crane was parked, so it carried no job — by taking a <b>live</b>
 * crane in the middle of a store job, proving its save is byte-compatible with the one above (no {@code B} and no
 * {@code Yaw} anywhere in it), loading it back and letting the job finish.
 * <p>
 * The aisle is the one the real save describes: controller at {@code x = 0}, dock at {@code x = 1} facing east along
 * {@code z = 3}, {@value #RAILS} rails, mast {@value #MAST} high.
 * <p>
 * <b>Since 0.7.0 the holder carries that milestone's gate too</b> (M23 issue #19, M24 issue #17), because those two
 * milestones are the next ones that added something to a save: a terminal may now hold a clipboard and the order it is
 * working off, and a <b>player</b> now carries the stock-list order they chose and what they keep asking for
 * (ADR-037). The three tests below answer the three halves of that:
 * {@link #migrationrealpre07terminalkeepsitsbufferandserves} replays the <b>real</b> terminal bytes of a world written
 * before any of it existed, {@link #migrationplayerwithoutpreferencesgetsthedefaultorder} opens a player file that has
 * no such attachment, and {@link #migrationinflightterminalrequestsurvivesthesave} catches a delivery in flight — the
 * one state no saved world in {@code run/migration} holds, for the same reason M21's crane gate had to take it live.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class MigrationGameTests {
    private static final Direction AISLE = Direction.EAST;
    private static final int AISLE_Z = 3;
    /** Rails of the real save ({@code Length: 14}). */
    private static final int RAILS = 14;
    /** Mast height of the real save ({@code Height: 4}), which is also today's default. */
    private static final int MAST = StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT;
    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    /** Key of the crane's state inside the dock's save ({@code CranePersistence.STATE_TAG}). */
    private static final String CRANE_TAG = "Crane";
    private static final int TIMEOUT_TICKS = 600;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int TEST_RPM = 128;

    /** Records of the real save: {@code X, Y, Side, Kind, item id, count} — an empty id means an empty location. */
    private static final String[] REAL_LOCATIONS = {
            "1,0,L,OUTPUT,,0",
            "1,0,R,INPUT,,0",
            "3,0,R,INPUT,,0",
            "3,1,L,OUTPUT,,0",
            "5,1,L,INPUT,,0",
            "5,1,R,PRODUCTION,,0",
            "7,2,L,PRODUCTION,,0",
            "8,0,L,STORAGE,minecraft:iron_ingot,112",
            "8,0,R,STORAGE,minecraft:cooked_beef,24",
            "8,1,L,STORAGE,minecraft:copper_ingot,64",
            "8,1,R,STORAGE,minecraft:carrot,40",
            "8,2,L,STORAGE,minecraft:gold_ingot,80",
            "8,2,R,STORAGE,minecraft:golden_apple,5",
            "9,0,L,STORAGE,minecraft:diamond,24",
            "9,0,R,STORAGE,minecraft:iron_pickaxe,1",
            "9,1,L,STORAGE,minecraft:emerald,12",
            "9,1,R,STORAGE,create:andesite_alloy,31",
            "9,2,L,STORAGE,minecraft:redstone,128",
            "9,2,R,STORAGE,minecraft:gunpowder,22",
            "10,0,L,STORAGE,minecraft:lapis_lazuli,32",
            "10,0,R,STORAGE,minecraft:blaze_powder,22",
            "10,1,L,STORAGE,minecraft:coal,62",
            "10,1,R,STORAGE,,0",
            "10,2,L,STORAGE,minecraft:quartz,40",
            "10,2,R,STORAGE,,0",
            "11,0,L,STORAGE,minecraft:raw_iron,48",
            "11,0,R,STORAGE,,0",
            "11,1,L,STORAGE,minecraft:raw_copper,32",
            "11,1,R,STORAGE,,0",
            "11,2,L,STORAGE,minecraft:raw_gold,16",
            "11,2,R,STORAGE,,0",
            "12,0,L,STORAGE,minecraft:iron_block,16",
            "12,0,R,STORAGE,,0",
            "12,1,L,STORAGE,minecraft:gold_block,8",
            "12,1,R,STORAGE,,0",
            "12,2,L,STORAGE,minecraft:diamond_block,4",
            "12,2,R,STORAGE,,0",
            "13,0,L,STORAGE,minecraft:oak_log,64",
            "13,0,R,STORAGE,,0",
            "13,1,L,STORAGE,minecraft:oak_planks,64",
            "13,1,R,STORAGE,,0",
            "13,2,L,STORAGE,minecraft:cobblestone,64",
            "13,2,R,STORAGE,,0",
            "14,0,L,STORAGE,minecraft:andesite,64",
            "14,0,R,STORAGE,,0",
            "14,1,L,STORAGE,minecraft:glass,32",
            "14,1,R,STORAGE,,0",
            "14,2,L,STORAGE,minecraft:bread,32",
            "14,2,R,STORAGE,,0"};
    private static final int REAL_RECORDS = 49;
    private static final int REAL_STORAGE_RECORDS = 42;
    private static final int REAL_IRON = 112;
    private static final int REAL_REDSTONE = 128;
    private static final int REAL_OAK_LOG = 64;
    /** The address of the rack the real save holds its iron in, and of its last rack. */
    private static final String REAL_IRON_ADDRESS = "A-01-08L";
    private static final String REAL_LAST_ADDRESS = "A-03-14R";
    private static final RackPosition REAL_IRON_RACK = new RackPosition(8, 0, Side.LEFT);
    private static final RackPosition REAL_LAST_RACK = new RackPosition(14, 2, Side.RIGHT);
    /** The two production stations the real save's orders point at. */
    private static final RackPosition REAL_SHAFT_STATION = new RackPosition(5, 1, Side.RIGHT);
    private static final RackPosition REAL_CHARGE_STATION = new RackPosition(7, 2, Side.LEFT);
    private static final int REAL_ORDERS = 2;
    private static final int REAL_ORDER_AMOUNT = 6;

    private static final ItemKey IRON = ItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final ItemKey REDSTONE = ItemKey.of(new ItemStack(Items.REDSTONE));
    private static final ItemKey OAK_LOG = ItemKey.of(new ItemStack(Items.OAK_LOG));
    private static final ItemKey FIRE_CHARGE = ItemKey.of(new ItemStack(Items.FIRE_CHARGE));

    /** Store job of the crane test: an input at the first rail, a rack eight blocks down the aisle. */
    private static final RackPosition INPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition TARGET = new RackPosition(8, 0, Side.LEFT);
    private static final int STORED_IRON = 32;

    // --- the M22 gate: a world whose rails branch (geometry on AISLE_PAIR_16X10X13) ------------------------------

    /** The aisle line of the branching world, with room for racks north of it and side aisles south of it. */
    private static final int GROWTH_Z = 2;
    private static final BlockPos GROWTH_CONTROLLER = new BlockPos(0, BASE_Y, GROWTH_Z);
    private static final BlockPos GROWTH_DOCK = new BlockPos(1, BASE_Y, GROWTH_Z);
    private static final BlockPos GROWTH_MOTOR = new BlockPos(1, FLOOR_Y, GROWTH_Z);
    /** Rails the warehouse had on M21: the run up to the block the rails split at, which M21 refused to walk into. */
    private static final int M21_RAILS = 3;
    /** Rails the whole run really has, which only M22 walks. */
    private static final int MAIN_RAILS = 12;
    /** Offsets along the run the side aisles leave it at, and how many rails each of them has. */
    private static final int[] GROWTH_TEETH = { 4, 8, 12 };
    private static final int TOOTH_RAILS = 3;
    /** The two racks the M21 warehouse already had, north of the run: positions 2 and 3 on its left. */
    private static final RackPosition GROWTH_IRON_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition GROWTH_GOLD_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final String GROWTH_IRON_ADDRESS = "A-01-02L";
    private static final String GROWTH_GOLD_ADDRESS = "A-01-03L";
    private static final int GROWTH_IRON = 24;
    private static final int GROWTH_GOLD = 12;
    /** The rack on the far side aisle, which only a warehouse that may split ever reaches. */
    private static final int GROWTH_DIAMONDS = 16;
    private static final ItemKey GOLD = ItemKey.of(new ItemStack(Items.GOLD_INGOT));
    private static final ItemKey DIAMOND = ItemKey.of(new ItemStack(Items.DIAMOND));
    private static final int GROWTH_TIMEOUT_TICKS = 2400;

    // --- the 0.7.0 gate: a world saved by 0.6.0-alpha, before a terminal had a list slot (M23, M24) ----------------

    /** Rails of the small aisle the 0.7.0 gate measures on: a terminal beside the dock and one stocked rack. */
    private static final int PRE07_RAILS = 4;
    /** The terminal's rack position, beside the dock, exactly where {@code TerminalUsageGameTests} places one. */
    private static final RackPosition PRE07_TERMINAL = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition PRE07_STORAGE = new RackPosition(1, 0, Side.LEFT);
    private static final int PRE07_IRON = 64;
    private static final int PRE07_GOLD = 16;
    private static final int PRE07_REQUESTED = 16;

    /**
     * The buffer of the real pre-0.7.0 terminal, {@code slot, item id, count} per line: the
     * {@code wareworks:warehouse_terminal} of {@code run/migration/world-m21} at {@code (1, -60, -1)}, holding what the
     * showcase world's two production loops had delivered into it. Its <b>whole</b> saved tag is
     * {@code {Buffer: {Size: 9, Items: [...]}}} — not one key more.
     */
    private static final String[] REAL_TERMINAL_BUFFER = {
            "0,create:shaft,6",
            "1,minecraft:fire_charge,6"};
    private static final int REAL_TERMINAL_SLOTS = 9;
    private static final int REAL_SHAFTS = 6;
    private static final int REAL_CHARGES = 6;
    private static final ItemKey SHAFT = ItemKey.of(AllBlocks.SHAFT.asStack());

    /**
     * Key of a terminal's clipboard order inside its save ({@code TerminalListPersistence.LIST_ORDER_TAG}, which is
     * package-private to the station package, like {@link #CRANE_TAG} is to the crane's).
     */
    private static final String LIST_ORDER_TAG = "ListOrder";

    private MigrationGameTests() {
    }

    // --- the real 0.5.0 save -------------------------------------------------------------------------------------

    /**
     * The controller tag of a world saved before M21, replayed byte for byte: it loads as a working warehouse of one
     * aisle with the same geometry, the same 49 records, the same addresses, the same stock and the same two production
     * orders at the same machines — and saves again without inventing any of this milestone's fields.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void migrationloadstherealpre06save(GameTestHelper helper) {
        new AisleFixture(helper, AISLE_Z, RAILS).build(true);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).status(), ControllerStatus.READY,
                        "the live aisle is ready"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity live = controller(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag real = realPre06Save();
                    // The bytes really are a pre-M21 save: nothing this milestone writes is in them.
                    helper.assertFalse(real.contains("Network"), "the real save has no network");
                    helper.assertFalse(real.contains("MisalignedBranches"), "and no branch array");
                    helper.assertValueEqual(occurrences(real, "B"), 0, "and no aisle index anywhere in it");

                    WarehouseControllerBlockEntity loaded = freshController(helper, live);
                    loaded.loadWithComponents(real, registries);

                    // Geometry.
                    helper.assertValueEqual(loaded.status(), ControllerStatus.READY,
                            "it loads as a working warehouse");
                    BranchLayout layout = loaded.layout().orElse(null);
                    if (layout == null) {
                        helper.fail("the loaded warehouse has no aisle at all", CONTROLLER);
                        return;
                    }
                    helper.assertValueEqual(layout.dock(), helper.absolutePos(DOCK), "the dock it always had");
                    helper.assertValueEqual(layout.facing(), AISLE, "running east");
                    helper.assertValueEqual(layout.geometry(), AisleGeometry.of(RAILS, MAST),
                            "over 14 rails, mast 4 high");
                    helper.assertValueEqual(loaded.layout(), live.layout(),
                            "which is exactly the aisle really standing there");
                    helper.assertValueEqual(loaded.warehouse().map(WarehouseLayout::branchCount), Optional.of(1),
                            "a warehouse of exactly one aisle");
                    helper.assertValueEqual(loaded.aisleLetter(), 'A', "lettered A, as its value box says");

                    // Records and stock.
                    helper.assertValueEqual(loaded.locations().size(), REAL_RECORDS, "the same records");
                    helper.assertValueEqual(loaded.storageLocations().size(), REAL_STORAGE_RECORDS,
                            "of which the same storage locations");
                    helper.assertTrue(loaded.locations().contains(
                            LocationRecord.of(new RackPosition(1, 0, Side.LEFT), LocationKind.OUTPUT)),
                            "the output at the first rail is still an output");
                    helper.assertTrue(loaded.locations().contains(
                            LocationRecord.of(REAL_IRON_RACK, LocationKind.STORAGE)),
                            "and the iron rack is still a storage location");
                    helper.assertTrue(loaded.locations().stream()
                            .allMatch(record -> record.branch() == RackPosition.FIRST_BRANCH),
                            "and every record is on the one aisle the save knew");
                    helper.assertValueEqual(loaded.countOf(IRON), (long) REAL_IRON, "the same iron");
                    helper.assertValueEqual(loaded.countOf(REDSTONE), (long) REAL_REDSTONE, "the same redstone");
                    helper.assertValueEqual(loaded.countOf(OAK_LOG), (long) REAL_OAK_LOG, "the same logs");

                    // Addresses.
                    helper.assertValueEqual(addressOf(helper, loaded, REAL_IRON_RACK), REAL_IRON_ADDRESS,
                            "the iron is still at " + REAL_IRON_ADDRESS);
                    helper.assertValueEqual(addressOf(helper, loaded, REAL_LAST_RACK), REAL_LAST_ADDRESS,
                            "and the far end is still " + REAL_LAST_ADDRESS);

                    // Production orders: still pointing at the very machines they were ordered at.
                    List<ProductionOrder<ItemKey, RackPosition>> orders = loaded.productionOrders();
                    helper.assertValueEqual(orders.size(), REAL_ORDERS, "both production orders came back");
                    ProductionOrder<ItemKey, RackPosition> charge = orders.stream()
                            .filter(order -> FIRE_CHARGE.equals(order.result())).findFirst().orElse(null);
                    if (charge == null) {
                        helper.fail("the fire charge order is gone", CONTROLLER);
                        return;
                    }
                    helper.assertValueEqual(charge.station(), REAL_CHARGE_STATION,
                            "at the machine it was ordered at");
                    helper.assertValueEqual(charge.state(), ProductionOrderState.COMPLETE, "in the state it had");
                    helper.assertValueEqual(charge.resultAmount(), REAL_ORDER_AMOUNT, "for the amount it had");
                    helper.assertValueEqual(charge.lines().size(), 3, "with its three ingredient lines");
                    helper.assertTrue(orders.stream().anyMatch(order -> REAL_SHAFT_STATION.equals(order.station())),
                            "and the other one is still at " + REAL_SHAFT_STATION);

                    // And it writes back what it read.
                    CompoundTag again = loaded.saveWithoutMetadata(registries);
                    helper.assertFalse(again.contains("Network"), "it writes no network back");
                    helper.assertFalse(again.contains("MisalignedBranches"), "nor a branch array");
                    helper.assertValueEqual(branchKeys(again), 0, "nor a branch on any record");
                })
                .thenSucceed();
    }

    // --- the crane's job ------------------------------------------------------------------------------------------

    /**
     * A crane carrying a job on a straight aisle saves the tag 0.5.0 wrote — no {@code B} and no {@code Yaw} anywhere
     * in it, while a job is really in flight — and loading that tag into a fresh dock gives back the same pose, the
     * same phase, the same items in the head and the same job. The reloaded pose's heading is the aisle's own
     * (<b>east</b>, not north), which is the one thing an absent {@code Yaw} must not get wrong.
     * <p>
     * The round trip is done on a <b>detached</b> block entity, the same way the controller's own save tests do it,
     * because loading a tag into a <i>live</i> kinetic block entity also replaces its kinetic network bookkeeping
     * without the chunk load that normally re-establishes it, and the machine then stands still for want of rotation —
     * an artefact of the test, not of the save. A real save, quit and rejoin in the middle of a job is what
     * {@code runRobustnessTest} does with a whole world. The live crane here is left alone and its job has to finish,
     * so the aisle this was measured on is provably a working one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void migrationcranejobreloadsonastraightaisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        aisle.storage(TARGET);
        aisle.input(INPUT);

        AtomicLong expected = new AtomicLong();
        helper.onEachTick(() -> assertConserved(helper, aisle, expected.get()));
        AtomicReference<CraneState<ItemKey, RackPosition>> before = new AtomicReference<>();
        AtomicReference<CompoundTag> saved = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    expected.set(STORED_IRON);
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    CompoundTag tag = dock.saveWithoutMetadata(helper.getLevel().registryAccess());
                    CompoundTag crane = tag.getCompound(CRANE_TAG);
                    helper.assertTrue(crane.contains("Job"), "the save really holds the job");
                    helper.assertValueEqual(occurrences(crane, "B"), 0,
                            "no aisle index anywhere in the crane's save");
                    helper.assertValueEqual(occurrences(crane, "Yaw"), 0,
                            "and no heading: this is exactly the tag 0.5.0 wrote");
                    before.set(dock.craneState());
                    saved.set(tag);
                })
                .thenExecute(() -> {
                    StackerCraneBlockEntity loaded = freshDock(helper, aisle.dock());
                    loaded.loadWithComponents(saved.get(), helper.getLevel().registryAccess());
                    CraneState<ItemKey, RackPosition> was = before.get();
                    CraneState<ItemKey, RackPosition> now = loaded.craneState();
                    helper.assertValueEqual(now.pose().branch(), RackPosition.FIRST_BRANCH,
                            "it comes back on the aisle at the dock");
                    helper.assertValueEqual(now.pose().yaw(), CranePose.yawOf(Heading.EAST),
                            "facing its own aisle, not north");
                    helper.assertValueEqual(now.pose().x(), was.pose().x(), "at the same position");
                    helper.assertValueEqual(now.pose().y(), was.pose().y(), "at the same level");
                    helper.assertValueEqual(now.pose().arm(), was.pose().arm(), "with the arm where it was");
                    helper.assertValueEqual(now.pose().side(), was.pose().side(), "on the same side");
                    helper.assertValueEqual(now.phase(), was.phase(), "in the same phase");
                    helper.assertValueEqual(now.job(), was.job(), "with the same job");
                    helper.assertValueEqual(now.job().map(TransportJob::type), Optional.of(JobType.STORE),
                            "which is still the store job it was");
                    helper.assertValueEqual(loaded.heldItems().count(IRON), STORED_IRON,
                            "and the items are still in its head");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(TARGET, IRON), (long) STORED_IRON,
                            "and the aisle it was measured on finished the job");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- the M22 gate: a world whose rails branch -----------------------------------------------------------------

    /**
     * The migration gate of M22 (issue #2): a world whose rails <b>branch</b> was a warehouse that stopped at the
     * block they split at, and on this version it <b>grows</b> — while every record it saved keeps its address and its
     * stock, and not one item moves.
     * <p>
     * The save it starts from is a real one, written by this very code: a warehouse of the run up to the junction,
     * which is exactly what a player's world held on M21, because M21 refused to walk into a block three rails meet at
     * and recorded the run before it. Such a save carries <b>no</b> {@code Network}, no {@code MisalignedBranches} and
     * no aisle index on any record — the same shape the real pre-M21 bytes of
     * {@link #migrationloadstherealpre06save} have, and the shape the save format still has, because M22 added no key
     * to it. The tag is asserted to have that shape, read back into a detached controller (the load half), and then
     * loaded into the <b>live</b> controller of a world whose rails really do branch, which is what opening that world
     * on this version does.
     * <p>
     * What changes for such a world is one thing and it is the one the changelog promises: the side aisles join, each
     * with a letter of its own, their racks become storage locations, and the aisle at the dock keeps its letter, its
     * position numbers and therefore every address a player ever wrote down. With {@code aisle.maxBranches = 1} the
     * same world stays the one straight aisle it was.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = GROWTH_TIMEOUT_TICKS)
    public static void migrationbranchingworldgrowsandkeepsitsstock(GameTestHelper helper) {
        buildM21Warehouse(helper);
        AtomicReference<CompoundTag> saved = new AtomicReference<>();
        Map<ItemKey, Long> conserved = new HashMap<>();
        boolean branching = WareworksConfig.maxBranches() > 1 && WareworksConfig.maxJunctions() > 0;
        int grownAisles = branching ? GROWTH_TEETH.length + 1 : 1;
        int grownStorage = branching ? 3 : 2;

        helper.startSequence()
                // 1. The warehouse as M21 knew it: one aisle, the run up to the junction, two stocked racks.
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity live = controllerAt(helper, GROWTH_CONTROLLER);
                    helper.assertValueEqual(live.status(), ControllerStatus.READY, "the M21 warehouse is ready");
                    helper.assertValueEqual(live.storageLocations().size(), 2, "with both racks it had");
                    helper.assertValueEqual(live.countOf(IRON), (long) GROWTH_IRON, "its iron indexed");
                    helper.assertValueEqual(live.countOf(GOLD), (long) GROWTH_GOLD, "and its gold");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity live = controllerAt(helper, GROWTH_CONTROLLER);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag tag = live.saveWithoutMetadata(registries);
                    // The save of a world whose rails branched on M21 is the shape 0.5.0 wrote: M21 stopped at the
                    // junction, so the warehouse it saved was one aisle and carries none of the network's own fields.
                    helper.assertFalse(tag.contains("Network"), "the M21 save of it holds no network");
                    helper.assertFalse(tag.contains("MisalignedBranches"), "and no branch array");
                    helper.assertValueEqual(occurrences(tag, "B"), 0, "and no aisle index anywhere in it");
                    saved.set(tag);

                    // It loads: a controller reading those bytes has the records, the stock and the addresses back.
                    WarehouseControllerBlockEntity loaded = freshController(helper, live);
                    loaded.loadWithComponents(tag, registries);
                    helper.assertValueEqual(loaded.status(), ControllerStatus.READY, "it loads as a working warehouse");
                    helper.assertValueEqual(loaded.layout(), live.layout(),
                            "over exactly the rails M21 gave it, which are the ones standing there");
                    helper.assertValueEqual(loaded.layout().map(layout -> layout.geometry().length()),
                            Optional.of(M21_RAILS), "the run up to the junction and no further");
                    helper.assertValueEqual(loaded.storageLocations().size(), 2, "with both racks");
                    helper.assertValueEqual(loaded.countOf(IRON), (long) GROWTH_IRON, "the same iron");
                    helper.assertValueEqual(loaded.countOf(GOLD), (long) GROWTH_GOLD, "the same gold");
                    helper.assertValueEqual(addressOf(helper, loaded, GROWTH_IRON_RACK), GROWTH_IRON_ADDRESS,
                            "the iron still at " + GROWTH_IRON_ADDRESS);
                    helper.assertValueEqual(addressOf(helper, loaded, GROWTH_GOLD_RACK), GROWTH_GOLD_ADDRESS,
                            "and the gold still at " + GROWTH_GOLD_ADDRESS);
                })
                // 2. The rails the M21 warehouse stopped at, and everything beyond them.
                .thenExecute(() -> {
                    buildTheRest(helper);
                    conserved.putAll(ItemCensus.take(helper));
                })
                .thenWaitUntil(() -> {
                    // The dock walks the whole run in either configuration - a straight run through a junction is one
                    // aisle for the pre-network walk too - so this is what says the rescan has happened at all.
                    helper.assertValueEqual(networkAt(helper, GROWTH_DOCK).firstBranchLength(), MAIN_RAILS,
                            "the dock walked the rails that are really there");
                    WarehouseLayout grown = warehouseAt(helper, GROWTH_CONTROLLER);
                    helper.assertValueEqual(grown.branchCount(), grownAisles, "the warehouse grew by its side aisles");
                    helper.assertValueEqual(controllerAt(helper, GROWTH_CONTROLLER).storageLocations().size(),
                            grownStorage, "and the rack on the far one joined");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity live = controllerAt(helper, GROWTH_CONTROLLER);
                    RailNetwork network = networkAt(helper, GROWTH_DOCK);
                    helper.assertValueEqual(network.stop(), NetworkStop.END,
                            "the rails simply end: nothing about them is refused any more");
                    helper.assertValueEqual(network.firstBranchLength(), MAIN_RAILS,
                            "the whole run is one aisle, through every junction");
                    // Nothing was lost and nothing renumbered: the two records of the M21 save are where they were.
                    helper.assertValueEqual(live.countOf(IRON), (long) GROWTH_IRON, "the iron is still there");
                    helper.assertValueEqual(live.countOf(GOLD), (long) GROWTH_GOLD, "and the gold");
                    helper.assertValueEqual(live.stockIndex().countAt(IRON, GROWTH_IRON_RACK), (long) GROWTH_IRON,
                            "at the very rack it was in");
                    helper.assertValueEqual(addressOf(helper, live, GROWTH_IRON_RACK), GROWTH_IRON_ADDRESS,
                            "with the address it always had");
                    helper.assertValueEqual(addressOf(helper, live, GROWTH_GOLD_RACK), GROWTH_GOLD_ADDRESS,
                            "and so is the gold's");
                    helper.assertValueEqual(live.aisleLetter(), 'A', "the aisle at the dock keeps its letter");
                    WarehouseLayout grown = warehouseAt(helper, GROWTH_CONTROLLER);
                    Set<Character> letters = new TreeSet<>();
                    for (int branch = 0; branch < grown.branchCount(); branch++)
                        grown.branch(branch).letter().ifPresent(letters::add);
                    helper.assertValueEqual(letters.size(), grownAisles, "one letter per aisle");
                    for (int branch = 1; branch < grown.branchCount(); branch++)
                        helper.assertTrue(grown.routes().reachable(0, branch),
                                "and every new aisle is joined to it (" + branch + ")");
                    if (branching)
                        helper.assertValueEqual(live.countOf(DIAMOND), (long) GROWTH_DIAMONDS,
                                "the stock of the aisle it grew into is counted too");
                    else
                        helper.assertValueEqual(live.countOf(DIAMOND), 0L,
                                "with the off switch on, the side aisle is no part of the warehouse");
                    ItemCensus.assertEquals(helper, conserved, "while the warehouse grew");
                })
                // 3. And now the literal migration: the M21 bytes loaded into the live controller of that world.
                .thenExecute(() -> controllerAt(helper, GROWTH_CONTROLLER)
                        .loadWithComponents(saved.get(), helper.getLevel().registryAccess()))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity live = controllerAt(helper, GROWTH_CONTROLLER);
                    helper.assertValueEqual(live.warehouse().map(WarehouseLayout::branchCount),
                            Optional.of(grownAisles), "the saved warehouse grows the moment it is opened here");
                    helper.assertValueEqual(live.storageLocations().size(), grownStorage,
                            "with every rack of it");
                    helper.assertValueEqual(live.countOf(IRON), (long) GROWTH_IRON, "the saved iron");
                    helper.assertValueEqual(live.countOf(GOLD), (long) GROWTH_GOLD, "the saved gold");
                    helper.assertValueEqual(addressOf(helper, live, GROWTH_IRON_RACK), GROWTH_IRON_ADDRESS,
                            "at the address the save gave it");
                })
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the saved world was opened"))
                .thenSucceed();
    }

    /** The warehouse as M21 left it: motor, dock, controller, the run up to the junction and two stocked racks. */
    private static void buildM21Warehouse(GameTestHelper helper) {
        helper.setBlock(GROWTH_MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(GROWTH_DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(GROWTH_CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = com.simibubi.create.AllBlockEntityTypes.MOTOR
                .getNullable(helper.getLevel(), helper.absolutePos(GROWTH_MOTOR));
        if (motor == null) {
            helper.fail("missing creative motor", GROWTH_MOTOR);
            return;
        }
        motor.generatedSpeed.setValue(TEST_RPM);
        for (int x = 1; x <= M21_RAILS; x++)
            helper.setBlock(GROWTH_DOCK.offset(x, 0, 0), WarehouseRailBlock.along(Direction.Axis.X));
        // Two racks north of the run, their interfaces facing away from it, with a chest behind each.
        placeRack(helper, GROWTH_DOCK.offset(2, 0, -1), GROWTH_DOCK.offset(2, 0, -2), Direction.NORTH,
                new ItemStack(Items.IRON_INGOT, GROWTH_IRON));
        placeRack(helper, GROWTH_DOCK.offset(3, 0, -1), GROWTH_DOCK.offset(3, 0, -2), Direction.NORTH,
                new ItemStack(Items.GOLD_INGOT, GROWTH_GOLD));
    }

    /** The junction M21 stopped at, the rest of the run, three side aisles and a stocked rack on the last of them. */
    private static void buildTheRest(GameTestHelper helper) {
        for (int x = M21_RAILS + 1; x <= MAIN_RAILS; x++)
            helper.setBlock(GROWTH_DOCK.offset(x, 0, 0), WarehouseRailBlock.along(Direction.Axis.X));
        for (int tooth : GROWTH_TEETH) {
            for (int z = 1; z <= TOOTH_RAILS; z++)
                helper.setBlock(GROWTH_DOCK.offset(tooth, 0, z), WarehouseRailBlock.along(Direction.Axis.Z));
        }
        int last = GROWTH_TEETH[GROWTH_TEETH.length - 1];
        placeRack(helper, GROWTH_DOCK.offset(last + 1, 0, TOOTH_RAILS), GROWTH_DOCK.offset(last + 2, 0, TOOTH_RAILS),
                Direction.EAST, new ItemStack(Items.DIAMOND, GROWTH_DIAMONDS));
    }

    /** A chest with {@code contents} behind an interface facing {@code away} from the rails it belongs to. */
    private static void placeRack(GameTestHelper helper, BlockPos rack, BlockPos chest, Direction away,
            ItemStack... contents) {
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(chest), null);
        if (handler == null) {
            helper.fail("no item handler", chest);
            return;
        }
        for (ItemStack stack : contents)
            ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        helper.setBlock(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, away));
    }

    // --- the 0.7.0 gate: a terminal saved before it had a list slot ------------------------------------------------

    /**
     * The migration gate of 0.7.0 (M23 issue #19, M24 issue #17): the <b>real</b> terminal bytes of a world saved
     * before a terminal had a clipboard slot open as the terminal they were, keep every item they held, and the
     * warehouse serves them.
     * <p>
     * The bytes are the {@code wareworks:warehouse_terminal} of {@code run/migration/world-m21} at
     * {@code (1, -60, -1)}, read out of that world's region files: a buffer of {@value #REAL_TERMINAL_SLOTS} slots
     * holding {@value #REAL_SHAFTS} shafts and {@value #REAL_CHARGES} fire charges, and <b>nothing else at all</b>.
     * That tag is the shape {@code v0.6.0-alpha} wrote: the release commit changed only {@code CHANGELOG.md} and
     * {@code gradle.properties}, and M22 — the one milestone between that world and the release — added no save key
     * (see {@code run/migration/README.txt}).
     * <p>
     * What this release added to that tag is two keys, both <b>optional</b>: {@code ListSlot} for a clipboard really
     * lying in the new slot and {@code ListOrder} for an order really running
     * ({@code WarehouseTerminalBlockEntity#writeStationData}). So the gate is in three parts: the old bytes carry
     * neither (asserted on the fixture), loading them leaves the terminal at the documented default — empty slot, no
     * order, no list on the screen — and writing it back writes <b>neither key back</b>, so a 0.6.0 world that is
     * merely opened keeps the bytes it had. Then the migrated terminal is made to work for its living: it is still a
     * member of its aisle, it takes a request, the crane really carries the iron into it, its old items are still
     * there afterwards and the census over the whole test is unchanged. Last, the new slot is used for the first time,
     * and only then does the save grow the new key.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void migrationrealpre07terminalkeepsitsbufferandserves(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, PRE07_RAILS).build(true);
        aisle.storage(PRE07_STORAGE, IRON.toStack(PRE07_IRON));
        aisle.terminal(PRE07_TERMINAL);
        Map<ItemKey, Long> conserved = new HashMap<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag real = realPre07Terminal();
                    // The bytes really are a pre-0.7.0 save: nothing this release writes is in them.
                    helper.assertValueEqual(occurrences(real, WarehouseTerminalBlockEntity.LIST_SLOT_TAG), 0,
                            "the real save has no clipboard slot");
                    helper.assertValueEqual(occurrences(real, LIST_ORDER_TAG), 0, "and no clipboard order");
                    helper.assertValueEqual(occurrences(real, WarehouseTerminalBlockEntity.LIST_STATE_TAG), 0,
                            "and nothing of a list on a screen");

                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(PRE07_TERMINAL);
                    terminal.loadWithComponents(real, registries);

                    // Every item it held is back, in the slots it held them in.
                    helper.assertValueEqual(terminal.bufferedItems().count(SHAFT), (long) REAL_SHAFTS,
                            "the shafts its sawmill loop made are still in it");
                    helper.assertValueEqual(terminal.bufferedItems().count(FIRE_CHARGE), (long) REAL_CHARGES,
                            "and the fire charges of its crafters");
                    helper.assertValueEqual(terminal.menuBuffer().getStackInSlot(0).getCount(), REAL_SHAFTS,
                            "the first slot holds what it held");
                    helper.assertValueEqual(terminal.menuBuffer().getStackInSlot(1).getCount(), REAL_CHARGES,
                            "and so does the second");

                    // And the two things this release added are at their documented default for it.
                    helper.assertTrue(terminal.listClipboard().isEmpty(), "its new list slot is empty");
                    helper.assertFalse(terminal.hasListOrder(), "it has no clipboard order");
                    helper.assertFalse(terminal.hasOpenListOrder(), "nor an open one");
                    helper.assertFalse(terminal.listState().isOpen(), "and its screen would show no list");

                    // It writes back what it read: a world that is merely opened keeps the bytes it had.
                    CompoundTag again = terminal.saveWithoutMetadata(registries);
                    helper.assertFalse(again.contains(WarehouseTerminalBlockEntity.LIST_SLOT_TAG),
                            "it writes no clipboard slot back");
                    helper.assertFalse(again.contains(LIST_ORDER_TAG), "nor a clipboard order");
                    helper.assertValueEqual(again.getCompound(WarehouseStationBlockEntity.BUFFER_TAG)
                                    .getList("Items", Tag.TAG_COMPOUND),
                            real.getCompound(WarehouseStationBlockEntity.BUFFER_TAG).getList("Items", Tag.TAG_COMPOUND),
                            "and the buffer it writes is the one it was given, entry for entry");
                })
                // The migrated terminal is still a member of its aisle, and the warehouse serves it.
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    Player player = playerAt(helper, aisle.rackPos(PRE07_TERMINAL));
                    helper.assertTrue(aisle.terminalAt(PRE07_TERMINAL)
                                    .requestFromTerminal(player, IRON, PRE07_REQUESTED).isAccepted(),
                            "the migrated terminal takes a request");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(PRE07_TERMINAL, IRON), (long) PRE07_REQUESTED,
                            "and the crane carries the iron into it");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(PRE07_TERMINAL);
                    helper.assertValueEqual(terminal.bufferedItems().count(SHAFT), (long) REAL_SHAFTS,
                            "the items of the old world are still there");
                    helper.assertValueEqual(terminal.bufferedItems().count(FIRE_CHARGE), (long) REAL_CHARGES,
                            "all of them");
                    ItemCensus.assertEquals(helper, conserved, "while the migrated terminal was served");

                    // Only when the new slot is really used does the save gain the new key (the clipboard itself is
                    // deliberately no part of a census: ticking an entry off changes its components by design).
                    helper.assertTrue(terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT,
                            AllBlocks.CLIPBOARD.asStack(), false).isEmpty(), "the new slot takes a clipboard");
                    helper.assertFalse(terminal.listClipboard().isEmpty(), "which is then lying in it");
                    helper.assertTrue(terminal.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .contains(WarehouseTerminalBlockEntity.LIST_SLOT_TAG),
                            "and only now is there a clipboard slot in the save");
                })
                .thenSucceed();
    }

    /**
     * The migration gate of M24 (issue #17): a player who never had the new per-player data opens the world with the
     * <b>documented default</b> — "most available first", no counts at all — and the terminal then starts learning
     * from them.
     * <p>
     * A player's own file is where M24 put the chosen order and the counts, as a NeoForge data attachment inside
     * {@code playerdata/&lt;uuid&gt;.dat} ({@code AttachmentHolder.ATTACHMENTS_NBT_KEY}, ADR-037). The real
     * {@code playerdata/380df991-f603-344c-a090-369bad2a924a.dat} of {@code run/migration/world-m21} has <b>no
     * {@code neoforge:attachments} key at all</b> (it has {@code NeoForgeData: {}} and nothing of this mod), which is
     * what every 0.6.0 player file looks like, because 0.6.0 had no attachment to write.
     * <p>
     * The fixture is therefore built out of this build's own save of a player who <b>has</b> used a terminal, with that
     * one attachment taken out again: byte for byte what 0.6.0 wrote for the same player, since this key is the only
     * difference this release makes to a player file. Loading it into a fresh player is what opening the world does —
     * {@code Entity#load} reads attachments only {@code if (compound.contains(ATTACHMENTS_NBT_KEY))} — and the player
     * comes out with {@link TerminalSort#DEFAULT}, which is {@link TerminalSort#AMOUNT}, and an empty history. That
     * the "most used" order of an empty history <b>is</b> the amount order is pinned by {@code TerminalSortTest}; what
     * needs a world is this: that the absent choice is the default rather than a fault, that saving such a player
     * writes none of it back, and that their first request starts the store and survives a save.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void migrationplayerwithoutpreferencesgetsthedefaultorder(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, PRE07_RAILS).build(true);
        aisle.storage(PRE07_STORAGE, IRON.toStack(PRE07_IRON), GOLD.toStack(PRE07_GOLD));
        aisle.terminal(PRE07_TERMINAL);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), (long) PRE07_IRON,
                        "the iron is indexed"))
                .thenExecute(() -> {
                    String preferences = WareworksAttachments.TERMINAL_PREFERENCES.getId().toString();
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(PRE07_TERMINAL);

                    // 1. A player of this build who has used a terminal: their own file carries the new data.
                    Player used = playerAt(helper, aisle.rackPos(PRE07_TERMINAL));
                    helper.assertTrue(terminal.requestFromTerminal(used, IRON, PRE07_REQUESTED).isAccepted(),
                            "this player asks for iron");
                    helper.assertTrue(TerminalPreferences.of(used).setSort(TerminalSort.NAME),
                            "and chooses the order by name");
                    CompoundTag savedNow = used.saveWithoutId(new CompoundTag());
                    helper.assertValueEqual(occurrences(savedNow, preferences), 1,
                            "this build writes their preferences into their own file");

                    // 2. The same file as 0.6.0-alpha wrote it: that one attachment is simply not in it.
                    CompoundTag pre07 = savedNow.copy();
                    CompoundTag attachments = pre07.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
                    attachments.remove(preferences);
                    if (attachments.isEmpty())
                        pre07.remove(AttachmentHolder.ATTACHMENTS_NBT_KEY);
                    helper.assertValueEqual(occurrences(pre07, preferences), 0,
                            "a 0.6.0 player file holds none of it");

                    // 3. Opening that world: the default, not a fault.
                    Player migrated = playerAt(helper, aisle.rackPos(PRE07_TERMINAL));
                    migrated.load(pre07);
                    helper.assertTrue(TerminalPreferences.existing(migrated).isEmpty(),
                            "nothing was invented for a player who had none");
                    helper.assertValueEqual(TerminalPreferences.of(migrated).sort(), TerminalSort.DEFAULT,
                            "the absent order is the default");
                    helper.assertValueEqual(TerminalPreferences.of(migrated).sort(), TerminalSort.AMOUNT,
                            "which is most available first, exactly what the build before this one showed");
                    helper.assertTrue(TerminalPreferences.of(migrated).counts().isEmpty(), "with no counts at all");
                    helper.assertTrue(TerminalPreferences.of(migrated).isDefault(),
                            "so there is nothing about them to save");
                    helper.assertValueEqual(occurrences(migrated.saveWithoutId(new CompoundTag()), preferences), 0,
                            "and saving them again writes none of it back: an old world pays nothing");

                    // 4. And the feature starts for them: the first request counts and survives a save.
                    helper.assertTrue(terminal.requestFromTerminal(migrated, GOLD, 1).isAccepted(),
                            "the migrated terminal serves them");
                    helper.assertValueEqual(TerminalPreferences.of(migrated).counts().size(), 1,
                            "their first count is there");
                    CompoundTag afterUse = migrated.saveWithoutId(new CompoundTag());
                    helper.assertValueEqual(occurrences(afterUse, preferences), 1, "now there is something to save");
                    Player rejoined = playerAt(helper, aisle.rackPos(PRE07_TERMINAL));
                    rejoined.load(afterUse);
                    helper.assertValueEqual(TerminalPreferences.of(rejoined).usage().countFor(GOLD), 1L,
                            "and it comes back on the next join");
                    helper.assertValueEqual(TerminalPreferences.of(rejoined).sort(), TerminalSort.DEFAULT,
                            "with the order they never changed");

                    // Nobody else's data was touched by any of this.
                    helper.assertValueEqual(TerminalPreferences.of(used).sort(), TerminalSort.NAME,
                            "the other player keeps their own order");
                    helper.assertValueEqual(TerminalPreferences.of(used).usage().countFor(GOLD), 0L,
                            "and their own counts");
                })
                .thenSucceed();
    }

    /**
     * A world saved by 0.6.0-alpha <b>in the middle of a delivery</b> finishes that delivery here, and no item of it
     * is lost.
     * <p>
     * No fixture can carry this: the real worlds in {@code run/migration} were all saved by a parked crane with no open
     * request, as M21's own gate records ({@link #migrationcranejobreloadsonastraightaisle}). The honest substitute is
     * the same one M21 used — take the state <b>live</b> and prove the save of it is one 0.6.0 could have written.
     * That argument is exact here, because the three block entities a delivery is spread over have <b>identical</b>
     * writers in this release and in 0.6.0: {@code ControllerPersistence} and the whole of {@code content.crane} are
     * unchanged since the release tag, and a terminal only grows a key when a clipboard is really in its slot. The
     * saved tags are asserted to hold none of this release's keys, and to really hold the request and the job.
     * <p>
     * The load half is then done on <b>detached</b> block entities, for the reason the crane gate gives (loading into a
     * live kinetic block entity replaces its network bookkeeping without the chunk load that re-establishes it): the
     * request comes back with its destination and its amount, the crane comes back with its job and the items in its
     * head, and the live warehouse the measurement was taken on has to finish the delivery, with the census over the
     * whole run unchanged.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void migrationinflightterminalrequestsurvivesthesave(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, PRE07_RAILS).build(true);
        aisle.storage(PRE07_STORAGE, IRON.toStack(PRE07_IRON));
        aisle.terminal(PRE07_TERMINAL);
        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<CompoundTag> savedController = new AtomicReference<>();
        AtomicReference<CompoundTag> savedDock = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(IRON), (long) PRE07_IRON,
                        "the iron is indexed"))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    conserved.putAll(ItemCensus.take(helper));
                    Player player = playerAt(helper, aisle.rackPos(PRE07_TERMINAL));
                    helper.assertTrue(aisle.terminalAt(PRE07_TERMINAL)
                                    .requestFromTerminal(player, IRON, PRE07_REQUESTED).isAccepted(),
                            "a player asks the terminal for iron");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, PRE07_REQUESTED))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag controller = aisle.controller().saveWithoutMetadata(registries);
                    CompoundTag dock = aisle.dock().saveWithoutMetadata(registries);
                    CompoundTag terminal = aisle.terminalAt(PRE07_TERMINAL).saveWithoutMetadata(registries);

                    // The save really caught the delivery in flight.
                    helper.assertValueEqual(controller.getList("Requests", Tag.TAG_COMPOUND).size(), 1,
                            "the open request is in the controller's save");
                    helper.assertTrue(dock.getCompound(CRANE_TAG).contains("Job"),
                            "and the job is in the crane's");

                    // And it is a save 0.6.0-alpha could have written: none of this release's keys is in any of it.
                    for (CompoundTag tag : List.of(controller, dock, terminal)) {
                        helper.assertValueEqual(occurrences(tag, WarehouseTerminalBlockEntity.LIST_SLOT_TAG), 0,
                                "no clipboard slot anywhere in it");
                        helper.assertValueEqual(occurrences(tag, LIST_ORDER_TAG), 0, "no clipboard order");
                        helper.assertValueEqual(occurrences(tag, WarehouseTerminalBlockEntity.LIST_STATE_TAG), 0,
                                "no list on a screen");
                        helper.assertValueEqual(occurrences(tag, AttachmentHolder.ATTACHMENTS_NBT_KEY), 0,
                                "and nothing of a player in a block's save");
                    }
                    savedController.set(controller);
                    savedDock.set(dock);
                })
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();

                    WarehouseControllerBlockEntity loaded = freshController(helper, aisle.controller());
                    loaded.loadWithComponents(savedController.get(), registries);
                    helper.assertValueEqual(loaded.openRequests().size(), 1, "the request comes back");
                    RetrievalRequest<ItemKey, BlockPos> request = loaded.openRequests().get(0);
                    helper.assertValueEqual(request.key(), IRON, "for the iron it asked for");
                    helper.assertValueEqual(request.destination(), aisle.absoluteRackPos(PRE07_TERMINAL),
                            "to the terminal that asked");

                    StackerCraneBlockEntity dock = freshDock(helper, aisle.dock());
                    dock.loadWithComponents(savedDock.get(), registries);
                    helper.assertValueEqual(dock.craneState().job().map(TransportJob::type),
                            Optional.of(JobType.RETRIEVE), "the crane comes back on its retrieval job");
                    helper.assertValueEqual(dock.heldItems().count(IRON), PRE07_REQUESTED,
                            "with the items still in its head");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(PRE07_TERMINAL, IRON), (long) PRE07_REQUESTED,
                            "and the warehouse the measurement was taken on finished the delivery");
                    aisle.assertIdleAndEmpty();
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "with no request left open");
                })
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "across the in-flight save"))
                .thenSucceed();
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    /** A survival player standing at the test-relative position, so a terminal's reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    /** The terminal tag of the pre-0.7.0 showcase world, rebuilt from its own bytes and tag types. */
    private static CompoundTag realPre07Terminal() {
        CompoundTag save = new CompoundTag();
        CompoundTag buffer = new CompoundTag();
        buffer.putInt("Size", REAL_TERMINAL_SLOTS);
        ListTag items = new ListTag();
        for (String line : REAL_TERMINAL_BUFFER) {
            String[] parts = line.split(",", -1);
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", Integer.parseInt(parts[0]));
            entry.put("Item", itemId(parts[1]));
            entry.putInt("Count", Integer.parseInt(parts[2]));
            items.add(entry);
        }
        buffer.put("Items", items);
        save.put(WarehouseStationBlockEntity.BUFFER_TAG, buffer);
        return save;
    }

    /** The controller tag of the pre-M21 showcase world, rebuilt from its own bytes and tag types. */
    private static CompoundTag realPre06Save() {
        CompoundTag save = new CompoundTag();
        CompoundTag layout = new CompoundTag();
        layout.putString("Facing", AISLE.getSerializedName());
        layout.putInt("Length", RAILS);
        layout.putInt("Height", MAST);
        save.put("Layout", layout);
        ListTag locations = new ListTag();
        for (String line : REAL_LOCATIONS)
            locations.add(realRecord(line));
        save.put("Locations", locations);
        save.putIntArray("Misaligned", new int[0]);
        save.putInt("ScrollValue", 0);
        save.put("Requests", new ListTag());
        save.put("StockRules", new ListTag());
        save.put("StockPauses", new ListTag());
        save.put("ProductionOrders", realProductionOrders());
        return save;
    }

    /** One record in the shape the real save holds it: {@code X, Y, Side, Kind, Stock}. */
    private static CompoundTag realRecord(String line) {
        String[] parts = line.split(",", -1);
        CompoundTag entry = new CompoundTag();
        entry.putInt("X", Integer.parseInt(parts[0]));
        entry.putInt("Y", Integer.parseInt(parts[1]));
        entry.putString("Side", parts[2]);
        entry.putString("Kind", parts[3]);
        ListTag stock = new ListTag();
        if (!parts[4].isEmpty()) {
            CompoundTag counted = new CompoundTag();
            counted.put("Item", itemId(parts[4]));
            counted.putLong("Count", Long.parseLong(parts[5]));
            stock.add(counted);
        }
        entry.put("Stock", stock);
        return entry;
    }

    /** The two production orders of the real save, tag for tag (their UUIDs included). */
    private static ListTag realProductionOrders() {
        ListTag orders = new ListTag();
        CompoundTag shaft = order(new int[]{1921680440, 1249724880, -1535708162, 1055020356},
                new int[]{-694428002, -104181895, -1182942332, -473344026}, 5, 1, "R", "create:shaft");
        ListTag shaftLines = new ListTag();
        shaftLines.add(line("create:andesite_alloy", 1,
                new int[]{-908050826, 834816263, -1319761415, 168524254}));
        shaft.put("Lines", shaftLines);
        orders.add(shaft);

        CompoundTag charge = order(new int[]{41333820, 20006385, -1629706007, 276847510},
                new int[]{-1200084182, 1077297739, -1153898798, -222372941}, 7, 2, "L", "minecraft:fire_charge");
        ListTag chargeLines = new ListTag();
        chargeLines.add(line("minecraft:gunpowder", 2,
                new int[]{1237223760, -2062202449, -1300907528, 1292147990}));
        chargeLines.add(line("minecraft:blaze_powder", 2,
                new int[]{1718531793, -1226488501, -1536744341, 982535741}));
        chargeLines.add(line("minecraft:coal", 2, new int[]{28081675, 1616724984, -2055893774, 158492276}));
        charge.put("Lines", chargeLines);
        orders.add(charge);
        return orders;
    }

    private static CompoundTag order(int[] id, int[] request, int x, int y, String side, String result) {
        CompoundTag order = new CompoundTag();
        order.putIntArray("Id", id);
        order.putIntArray("Request", request);
        CompoundTag station = new CompoundTag();
        station.putInt("X", x);
        station.putInt("Y", y);
        station.putString("Side", side);
        order.put("Station", station);
        order.put("Result", itemId(result));
        order.putInt("ResultAmount", REAL_ORDER_AMOUNT);
        order.putString("State", "COMPLETE");
        order.putLong("Produced", REAL_ORDER_AMOUNT);
        order.putLong("Promised", REAL_ORDER_AMOUNT);
        order.putLong("StockSeen", 0L);
        return order;
    }

    private static CompoundTag line(String item, int required, int[] id) {
        CompoundTag line = new CompoundTag();
        line.put("Item", itemId(item));
        line.putInt("Required", required);
        line.putInt("Delivered", required);
        line.putIntArray("Id", id);
        return line;
    }

    /** An item key in the shape the real save holds it: a compound with nothing but an {@code id}. */
    private static CompoundTag itemId(String id) {
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        return item;
    }

    /** How many records of a save carry an aisle index of their own; zero for every warehouse of one aisle. */
    private static int branchKeys(CompoundTag saved) {
        int found = 0;
        ListTag locations = saved.getList("Locations", Tag.TAG_COMPOUND);
        for (int i = 0; i < locations.size(); i++) {
            if (locations.getCompound(i).contains("B"))
                found++;
        }
        return found;
    }

    /** How often {@code key} appears as a key anywhere inside {@code tag}, at any depth. */
    private static int occurrences(Tag tag, String key) {
        int found = 0;
        if (tag instanceof CompoundTag compound) {
            for (String name : compound.getAllKeys()) {
                if (name.equals(key))
                    found++;
                found += occurrences(compound.get(name), key);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list)
                found += occurrences(element, key);
        }
        return found;
    }

    private static String addressOf(GameTestHelper helper, WarehouseControllerBlockEntity controller,
                                    RackPosition rack) {
        BlockPos pos = controller.worldPosOf(rack).orElse(null);
        if (pos == null) {
            helper.fail("no world position for " + rack, CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return controller.addressOf(pos).map(StorageAddress::format).orElse("none");
    }

    private static WarehouseControllerBlockEntity controller(GameTestHelper helper) {
        return controllerAt(helper, CONTROLLER);
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing warehouse controller", pos);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    /** The warehouse the controller at {@code pos} maps, which a ready controller always has. */
    private static WarehouseLayout warehouseAt(GameTestHelper helper, BlockPos pos) {
        WarehouseLayout layout = controllerAt(helper, pos).warehouse().orElse(null);
        if (layout == null) {
            helper.fail("the controller maps no warehouse", pos);
            throw new AssertionError("unreachable");
        }
        return layout;
    }

    /** What the dock at {@code pos} last discovered: where the warehouse stops and why. */
    private static RailNetwork networkAt(GameTestHelper helper, BlockPos pos) {
        StackerCraneBlockEntity dock = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        RailNetwork network = dock == null ? null : dock.discoveredNetwork().orElse(null);
        if (network == null) {
            helper.fail("the dock recorded no network", pos);
            throw new AssertionError("unreachable");
        }
        return network;
    }

    private static WarehouseControllerBlockEntity freshController(GameTestHelper helper,
                                                                  WarehouseControllerBlockEntity controller) {
        WarehouseControllerBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .create(controller.getBlockPos(), controller.getBlockState());
        if (copy == null) {
            helper.fail("could not create a detached warehouse controller", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return copy;
    }

    /** A detached stacker crane at the live one's position and block state, for a save round trip. */
    private static StackerCraneBlockEntity freshDock(GameTestHelper helper, StackerCraneBlockEntity dock) {
        StackerCraneBlockEntity copy = WareworksBlockEntityTypes.STACKER_CRANE
                .create(dock.getBlockPos(), dock.getBlockState());
        if (copy == null) {
            helper.fail("could not create a detached stacker crane", DOCK);
            throw new AssertionError("unreachable");
        }
        return copy;
    }

    /** Iron in the chest, in the input's buffer, in the crane's head and on the floor. */
    private static void assertConserved(GameTestHelper helper, AisleFixture aisle, long expected) {
        long total = aisle.storedAt(TARGET, IRON) + aisle.stationCount(INPUT, IRON);
        StackerCraneBlockEntity dock = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(DOCK));
        if (dock != null)
            total += dock.heldItems().count(IRON);
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM)) {
            if (IRON.matches(entity.getItem()))
                total += entity.getItem().getCount();
        }
        helper.assertValueEqual(total, expected, "conserved iron (chest, input, head, floor)");
    }
}
