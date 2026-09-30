package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlockEntityTypes;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    // --- helpers --------------------------------------------------------------------------------------------------

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
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(CONTROLLER));
        if (be == null) {
            helper.fail("missing warehouse controller", CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return be;
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
