package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of what a warehouse <b>saves</b> and what happens when its rails are rebuilt underneath it (M21, issue #1,
 * ADR-033).
 * <p>
 * Three promises are pinned here, and all three are about a player's own world rather than about a feature:
 * <ul>
 * <li><b>A world built before M21 opens unchanged.</b> A straight aisle writes exactly the tag it always wrote — no
 * network, no branch on a single record — and a hand-written 0.5.0 save loads into the same addresses, the same
 * records and the same stock counts as the live warehouse it was copied from.</li>
 * <li><b>Rebuilding the rails never loses an item.</b> When a warehouse changes shape, everything it knows is moved
 * through the world positions its labels stand for. A location that is still there keeps its stock; one that is gone
 * is forgotten, and its chest keeps every item in it. The item census is exact across the whole rebuild.</li>
 * <li><b>A production order is never re-pointed at a different machine.</b> An order whose station survived the
 * rebuild follows it to its new name; one whose station is gone is cancelled, because ingredients delivered to the
 * wrong machine are the one failure this remap exists to prevent.</li>
 * </ul>
 * The warehouse they use is an L on the {@code aisle_pair_16x10x13} floor: the controller at {@code x = 0}, the dock
 * at {@code x = 1} facing east along {@code z = 3}, four rails to the corner at {@code (5, 3)}, and three more south
 * of it. The corner block is position 4 of the first aisle and position 0 of the second one at the same time, which is
 * why a crane parked there can be saved and loaded without anything in this version knowing how to turn.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseNetworkGameTests {
    private static final int TIMEOUT_TICKS = 400;
    private static final int LONG_TIMEOUT_TICKS = 900;
    private static final int SETTLE_TICKS = 12;
    /** Long enough to see a per-tick rewrite: the defect it watches for fired twice on every single tick. */
    private static final int STABLE_TICKS = 40;
    private static final int AISLE_Z = 3;
    /** Rails of the first aisle: x = 2..5 at z = 3. Position 4 is the corner block. */
    private static final int FIRST_RAILS = 4;
    /** Rails of the second aisle, south of the corner. */
    private static final int SECOND_RAILS = 3;
    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, AISLE_Z);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, AISLE_Z);
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);
    private static final BlockPos CORNER = new BlockPos(1 + FIRST_RAILS, BASE_Y, AISLE_Z);

    /** The station beside the corner block: it is a rack position of the second aisle whichever way that aisle runs. */
    private static final BlockPos CORNER_STATION = new BlockPos(6, BASE_Y, 3);
    /** A station two blocks down the second aisle: it stops being a rack position when that aisle turns round. */
    private static final BlockPos FAR_STATION = new BlockPos(6, BASE_Y, 5);
    /** A storage interface on the second aisle, with its chest behind it. */
    private static final BlockPos SECOND_AISLE_RACK = new BlockPos(6, BASE_Y, 4);
    private static final BlockPos SECOND_AISLE_CHEST = new BlockPos(7, BASE_Y, 4);
    /** A storage interface on the first aisle: nothing a rebuild does may ever move it. */
    private static final BlockPos FIRST_AISLE_RACK = new BlockPos(3, BASE_Y, 2);
    private static final BlockPos FIRST_AISLE_CHEST = new BlockPos(3, BASE_Y, 1);
    /** The storage interface beside the corner block, on the first aisle. */
    private static final BlockPos CORNER_RACK = new BlockPos(5, BASE_Y, 2);
    private static final BlockPos CORNER_CHEST = new BlockPos(5, BASE_Y, 1);
    /** A storage interface beside the second aisle <b>after</b> it has been rebuilt north of the corner. */
    private static final BlockPos REBUILT_AISLE_RACK = new BlockPos(6, BASE_Y, 2);
    private static final BlockPos REBUILT_AISLE_CHEST = new BlockPos(7, BASE_Y, 2);
    private static final BlockPos INPUT = new BlockPos(2, BASE_Y, 2);
    private static final BlockPos OUTPUT = new BlockPos(2, BASE_Y, 4);
    /**
     * The column diagonally <b>inside</b> the bend: laterally beside a straight rail of the first aisle at
     * {@code (4, 3)} and of the second one at {@code (5, 4)}, and a neighbour of the corner block itself at neither.
     * This is the block every "free faces of the corner" rule misses, and the one the ownership rule is built for.
     */
    private static final BlockPos INNER_CORNER = new BlockPos(4, BASE_Y, 4);
    /**
     * One level <b>above the rail just before the corner</b>, i.e. inside the column the crane's mast travels through.
     * That rail is laterally beside the second aisle, so its whole column was offered as a rack position of it.
     */
    private static final BlockPos ABOVE_THE_RAIL = new BlockPos(1 + FIRST_RAILS - 1, BASE_Y + 1, AISLE_Z);
    /** An output station at position 2 on the right of the <b>first</b> aisle. */
    private static final BlockPos FIRST_AISLE_OUTPUT = new BlockPos(3, BASE_Y, 4);
    /** An output station at position 2 on the right of the <b>second</b> aisle: the same numbers, another block. */
    private static final BlockPos SECOND_AISLE_OUTPUT = new BlockPos(4, BASE_Y, 5);

    private static final ItemKey LOG = ItemKey.of(new ItemStack(Items.OAK_LOG));
    private static final ItemKey PLANK = ItemKey.of(new ItemStack(Items.OAK_PLANKS));
    private static final ItemKey IRON = ItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final ItemKey NUGGET = ItemKey.of(new ItemStack(Items.IRON_NUGGET));
    private static final ItemKey GOLD = ItemKey.of(new ItemStack(Items.GOLD_INGOT));
    private static final int LOGS_IN_STOCK = 32;
    private static final int IRON_IN_STOCK = 16;
    private static final int IRON_MAXIMUM = 64;
    /** A minimum the warehouse cannot meet, so the keeper's rule really bites: lamp on, comparator at one. */
    private static final int IRON_MINIMUM = 64;
    private static final int GOLD_MAXIMUM = 32;

    private WarehouseNetworkGameTests() {
    }

    // --- a world built before M21 --------------------------------------------------------------------------------

    /**
     * A warehouse of one straight aisle writes the tag it always wrote: no {@code Network}, no {@code B} on a record
     * and no {@code MisalignedBranches}. That is the whole migration guarantee, stated as an assertion rather than as
     * a claim — everything this milestone added is absent from a save that does not need it.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networksavesnothingextraforastraightaisle(GameTestHelper helper) {
        buildFirstAisle(helper);
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH, new ItemStack(Items.IRON_INGOT, 8));
        placeStorage(helper, CORNER_RACK, CORNER_CHEST, Direction.NORTH);
        // A misaligned member as well, so the misaligned array really has an entry to compare.
        helper.setBlock(new BlockPos(4, BASE_Y, 2), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.EAST));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 2,
                        "both storage locations joined"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    helper.assertValueEqual(controller.misalignedCount(), 1, "and the misaligned one is counted");
                    CompoundTag saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertFalse(saved.contains("Network"),
                            "a warehouse of one aisle saves no network at all");
                    helper.assertFalse(saved.contains("MisalignedBranches"),
                            "and no parallel branch array beside the misaligned positions");
                    helper.assertValueEqual(branchKeys(saved), 0, "and no record carries a branch");
                    helper.assertValueEqual(saved.getIntArray("Misaligned").length, 3,
                            "the misaligned array keeps its stride of three");
                })
                .thenSucceed();
    }

    /**
     * A hand-written 0.5.0 save — {@code Layout} and {@code Locations} with nothing this milestone added — loads into
     * exactly the warehouse the live one is: the same aisle, the same records, the same stock and the same addresses.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networkloadsapre06save(GameTestHelper helper) {
        buildFirstAisle(helper);
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH, new ItemStack(Items.IRON_INGOT, 9));
        placeStorage(helper, CORNER_RACK, CORNER_CHEST, Direction.NORTH);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).countOf(IRON), 9L,
                        "the live warehouse counted its iron"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();

                    CompoundTag legacy = new CompoundTag();
                    CompoundTag layout = new CompoundTag();
                    layout.putString("Facing", Direction.EAST.getSerializedName());
                    layout.putInt("Length", FIRST_RAILS);
                    layout.putInt("Height", StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT);
                    legacy.put("Layout", layout);
                    ListTag locations = new ListTag();
                    locations.add(legacyRecord(2, 0, "L", "STORAGE", registries, 9));
                    locations.add(legacyRecord(4, 0, "L", "STORAGE", registries, 0));
                    legacy.put("Locations", locations);
                    legacy.putIntArray("Misaligned", new int[0]);

                    WarehouseControllerBlockEntity loaded = freshController(helper, controller);
                    loaded.loadWithComponents(legacy, registries);

                    helper.assertValueEqual(loaded.status(), ControllerStatus.READY, "it loads as a working warehouse");
                    helper.assertValueEqual(loaded.layout(), controller.layout(), "the same aisle");
                    helper.assertValueEqual(loaded.warehouse().map(WarehouseLayout::branchCount), Optional.of(1),
                            "a network of exactly one aisle");
                    helper.assertValueEqual(loaded.locations(), controller.locations(), "the same records");
                    helper.assertValueEqual(loaded.countOf(IRON), controller.countOf(IRON), "the same stock");
                    for (BlockPos rack : List.of(FIRST_AISLE_RACK, CORNER_RACK))
                        helper.assertValueEqual(loaded.addressOf(helper.absolutePos(rack)).map(StorageAddress::format),
                                controller.addressOf(helper.absolutePos(rack)).map(StorageAddress::format),
                                "the same address at " + rack);
                    // And it saves again without inventing anything: a warehouse that never bent stays byte-compatible.
                    helper.assertFalse(loaded.saveWithoutMetadata(registries).contains("Network"),
                            "and writes no network back");
                })
                .thenSucceed();
    }

    /**
     * A warehouse that really bends saves its further aisles, their origins, headings, lengths and letters, and loads
     * them back unchanged — including the pinned lines that keep the letters where they are.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networksavesandloadsitsaisles(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST, new ItemStack(Items.IRON_INGOT, 7));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the second aisle joined the warehouse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseLayout live = warehouse(helper);
                    helper.assertValueEqual(live.branch(1).letter(), Optional.of('B'),
                            "the second aisle takes the next free letter");
                    helper.assertValueEqual(live.branch(1).origin(), helper.absolutePos(CORNER),
                            "and starts at the corner block");
                    helper.assertValueEqual(live.branch(1).heading(), Direction.SOUTH, "running away from the dock");
                    helper.assertValueEqual(live.branch(1).geometry().length(), SECOND_RAILS, "over its own rails");
                    helper.assertValueEqual(controller.countOf(IRON), 7L, "with the stock of its rack");

                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    helper.assertTrue(saved.contains("Network"), "a warehouse that bends saves its network");
                    WarehouseControllerBlockEntity loaded = freshController(helper, controller);
                    loaded.loadWithComponents(saved, registries);
                    WarehouseLayout restored = loaded.warehouse().orElse(null);
                    if (restored == null) {
                        helper.fail("the saved warehouse did not load", CONTROLLER);
                        return;
                    }
                    helper.assertValueEqual(restored, live, "the same warehouse, aisle for aisle");
                    helper.assertValueEqual(loaded.locations(), controller.locations(), "the same records");
                    helper.assertValueEqual(loaded.countOf(IRON), 7L, "the same stock");
                    helper.assertValueEqual(loaded.addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format),
                            controller.addressOf(helper.absolutePos(SECOND_AISLE_RACK)).map(StorageAddress::format),
                            "and the same address on the second aisle");

                    // A network tag that does not describe a warehouse is dropped rather than trusted: the rails say
                    // what is really there, and the first re-link reads them again.
                    CompoundTag broken = saved.copy();
                    broken.getCompound("Network").getList("Branches", Tag.TAG_COMPOUND).getCompound(0)
                            .putString("H", "sideways");
                    WarehouseControllerBlockEntity sanitized = freshController(helper, controller);
                    sanitized.loadWithComponents(broken, registries);
                    helper.assertValueEqual(sanitized.warehouse().map(WarehouseLayout::branchCount), Optional.of(1),
                            "an unreadable aisle leaves the warehouse with the one at its dock");
                })
                .thenSucceed();
    }

    // --- rebuilding the rails ------------------------------------------------------------------------------------

    /**
     * The sharpest correctness item of the milestone: the second aisle is rebuilt on the other side of the corner, so
     * every label on it means something else afterwards.
     * <ul>
     * <li>the production station beside the corner is still there, under a new name, and its open order follows it to
     * the <b>same machine</b>;</li>
     * <li>the production station further down is gone, and its order is cancelled rather than re-pointed;</li>
     * <li>the storage location that is gone keeps every item in its chest;</li>
     * <li>the first aisle does not move at all, because its position 0 is the dock.</li>
     * </ul>
     * An item census runs on every tick of the whole rebuild.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkremapkeepsproductionorders(GameTestHelper helper) {
        buildCorner(helper);
        // Ingredients only: a result that is already in stock is served out of it and no order is ever created.
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH,
                new ItemStack(Items.OAK_LOG, LOGS_IN_STOCK), new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST, new ItemStack(Items.GOLD_INGOT, 5));
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));
        helper.setBlock(OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
        // Both stations face the second aisle, which they keep doing after it turns round: a station stands beside the
        // rails, and which end of them is position 0 is nothing it can see.
        helper.setBlock(CORNER_STATION, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.WEST));
        helper.setBlock(FAR_STATION, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.WEST));

        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }
        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<UUID> keptOrder = new AtomicReference<>();
        AtomicReference<UUID> lostOrder = new AtomicReference<>();
        AtomicReference<BranchLayout> firstAisle = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).productionStations().size(), 2, "both stations joined");
                    helper.assertValueEqual(controller(helper).countOf(LOG), (long) LOGS_IN_STOCK, "logs counted");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) IRON_IN_STOCK, "iron counted");
                })
                .thenExecute(() -> {
                    // One station makes planks out of logs, the other sticks out of planks, so each order has exactly
                    // one station it can go to and the test can name which one.
                    pattern(helper, CORNER_STATION, LOG, PLANK);
                    pattern(helper, FAR_STATION, IRON, NUGGET);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    helper.assertTrue(controller.request(helper.absolutePos(OUTPUT), PLANK, 2).isAccepted(),
                            "the planks are ordered");
                    helper.assertTrue(controller.request(helper.absolutePos(OUTPUT), NUGGET, 2).isAccepted(),
                            "and the nuggets are");
                    RackPosition cornerStation = rackOf(helper, CORNER_STATION);
                    RackPosition farStation = rackOf(helper, FAR_STATION);
                    helper.assertValueEqual(cornerStation, new RackPosition(1, 0, 0, Side.LEFT),
                            "the corner station is position 0 of the second aisle");
                    keptOrder.set(onlyOrderAt(helper, cornerStation).id());
                    lostOrder.set(onlyOrderAt(helper, farStation).id());
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the rails are rebuilt"));
                    firstAisle.set(controller.layout().orElseThrow());
                })
                .thenExecute(() -> {
                    // The rebuild itself, in one tick: the second aisle now runs north out of the same corner block.
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.south(z), Blocks.AIR);
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.north(z), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 2, "the warehouse still bends");
                    helper.assertValueEqual(warehouse.branch(1).heading(), Direction.NORTH, "the other way now");
                    helper.assertValueEqual(warehouse.branch(1).origin(), helper.absolutePos(CORNER),
                            "out of the same corner block");
                    helper.assertValueEqual(warehouse.branch(1).letter(), Optional.of('B'),
                            "keeping its letter, because its line of rails is the one it always was");
                    // The load-bearing invariant of the whole remap: position 0 of the aisle at the dock IS the dock, so
                    // no rebuild can renumber a position the crane can be working on. That is why the crane needs to
                    // know nothing about any of this yet.
                    helper.assertValueEqual(warehouse.firstBranch(), firstAisle.get(),
                            "the aisle at the dock did not move a block");
                    helper.assertValueEqual(warehouse.firstBranch(), controller.layout().orElseThrow(),
                            "and it is still the aisle the controller reports");

                    // The order that kept its machine.
                    ProductionOrder<ItemKey, RackPosition> kept = order(helper, controller, keptOrder.get());
                    helper.assertValueEqual(kept.state(), ProductionOrderState.WAITING_FOR_INGREDIENTS,
                            "the order at the station that is still there is still open");
                    helper.assertValueEqual(kept.station(), new RackPosition(1, 0, 0, Side.RIGHT),
                            "under the name its machine has now");
                    helper.assertValueEqual(controller.worldPosOf(kept.station()), Optional.of(
                            helper.absolutePos(CORNER_STATION)), "which is the same machine as before");
                    helper.assertValueEqual(controller.productionOrdersAt(helper.absolutePos(CORNER_STATION)).size(), 1,
                            "and the station knows about it");

                    // The order whose machine stopped being part of the warehouse.
                    ProductionOrder<ItemKey, RackPosition> lost = order(helper, controller, lostOrder.get());
                    helper.assertFalse(lost.isOpen(), "the order at the station that is gone was ended");
                    helper.assertValueEqual(lost.state(), ProductionOrderState.CANCELLED, "by cancelling it");
                    helper.assertTrue(controller.productionOrdersAt(helper.absolutePos(FAR_STATION)).isEmpty(),
                            "and nothing points at that machine any more");

                    // The rack that stopped existing: forgotten by the warehouse, untouched in the world.
                    helper.assertValueEqual(controller.countOf(GOLD), 0L,
                            "a location that is no longer part of the warehouse is not counted");
                    helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.GOLD_INGOT), 5,
                            "but every item is still in its chest");
                    helper.assertValueEqual(controller.countOf(LOG), (long) LOGS_IN_STOCK,
                            "and the first aisle still counts what it always did");
                    ItemCensus.assertEquals(helper, conserved, "after the rails were rebuilt");
                })
                .thenSucceed();
    }

    /**
     * The two things a player really does to an aisle: make it longer at its far end, and run rails into the block
     * where it meets another aisle.
     * <ul>
     * <li><b>At the far end</b> nothing is renumbered and nothing is forgotten — an aisle counts from the end nearer
     * the dock, so every address it had still means the same chest.</li>
     * <li><b>At the near end</b> the rails split, which since M22 (issue #2) is an ordinary warehouse: the aisle grows
     * <b>past its origin</b>, so it really is renumbered, and that is the case the remap exists for. It keeps its
     * letter, every record is carried through its world position, nothing is forgotten and no item moves — and taking
     * the rail away again puts every address back exactly as it was.</li>
     * </ul>
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkbranchgrowsatbothends(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST, new ItemStack(Items.IRON_INGOT, 6));
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH, new ItemStack(Items.OAK_LOG, 4));

        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }
        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<String> address = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the warehouse bends");
                    helper.assertValueEqual(controller(helper).countOf(IRON), 6L, "the second aisle is counted");
                })
                .thenExecute(() -> {
                    address.set(controller(helper).addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format).orElse("none"));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the aisle grows"));
                })
                .thenExecute(() -> {
                    // Longer at the far end.
                    helper.setBlock(CORNER.south(SECOND_RAILS + 1), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branch(1).geometry().length(), SECOND_RAILS + 1, "one rail more");
                    helper.assertValueEqual(warehouse.branch(1).origin(), helper.absolutePos(CORNER),
                            "position 0 did not move");
                    helper.assertValueEqual(warehouse.branch(1).letter(), Optional.of('B'), "nor did the letter");
                    helper.assertValueEqual(controller(helper).addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format), Optional.of(address.get()),
                            "so the rack's address is the one on the player's sign");
                    helper.assertValueEqual(controller(helper).countOf(IRON), 6L, "and its stock never moved");
                })
                .thenExecute(() -> {
                    // A rail at the near end of the second aisle, i.e. on the other side of the corner: three rails
                    // now meet in one block. Since M22 that is an ordinary warehouse - and the aisle grew PAST its
                    // origin, which is the one build action that really renumbers it.
                    helper.setBlock(CORNER.north(1), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 2, "the warehouse still has both its aisles");
                    helper.assertValueEqual(warehouse.geometry().length(), FIRST_RAILS,
                            "the aisle at the dock runs through the junction, in one piece");
                    helper.assertValueEqual(warehouse.branch(1).origin(), helper.absolutePos(CORNER.north(1)),
                            "the second aisle begins at its new near end");
                    helper.assertValueEqual(warehouse.branch(1).geometry().length(), SECOND_RAILS + 2,
                            "and is two rails longer than it was built");
                    helper.assertValueEqual(warehouse.branch(1).letter(), Optional.of('B'),
                            "under the letter it always had");
                    helper.assertValueEqual(controller.countOf(IRON), 6L,
                            "its rack was carried through its world position, stock and all");
                    helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT), 6,
                            "with every item still in its chest");
                    helper.assertValueEqual(controller.countOf(LOG), 4L, "the first aisle keeps its own stock");
                    // The renumber is real and is the point: the same chest now answers to one position further along.
                    helper.assertValueEqual(controller.addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format).isPresent(), true, "and it still has an address");
                    helper.assertFalse(controller.addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format).equals(Optional.of(address.get())),
                            "one position further along, because the aisle grew past its origin");
                    ItemCensus.assertEquals(helper, conserved, "after the rails split");
                })
                .thenExecute(() -> {
                    // Taking that rail away again puts every address back where it was.
                    helper.setBlock(CORNER.north(1), Blocks.AIR);
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 2, "the second aisle is the one it was");
                    helper.assertValueEqual(warehouse.branch(1).origin(), helper.absolutePos(CORNER),
                            "numbered from the corner again");
                    helper.assertValueEqual(warehouse.branch(1).letter(), Optional.of('B'),
                            "under the letter it always had");
                    helper.assertValueEqual(controller(helper).addressOf(helper.absolutePos(SECOND_AISLE_RACK))
                            .map(StorageAddress::format), Optional.of(address.get()),
                            "and at the address it always had");
                    helper.assertValueEqual(controller(helper).countOf(IRON), 6L, "counting its stock again");
                    ItemCensus.assertEquals(helper, conserved, "after the warehouse healed");
                })
                .thenSucceed();
    }

    /**
     * The other thing a rebuild must carry: a stock keeper's rules. A rule gates item movement in both irreversible
     * directions — a forgotten maximum stores items nothing moves back out, a forgotten reserve is handed to
     * automation and cannot be recalled — so a keeper that is still there keeps governing under its new name, and a
     * keeper whose block left the warehouse loses its rules <b>and</b> stops signalling for them.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkremapmovesstockrules(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH,
                new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
        // The keeper beside the corner block stays a rack position of the second aisle whichever way it runs; the one
        // further down does not.
        helper.setBlock(CORNER_STATION, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, Direction.WEST));
        helper.setBlock(FAR_STATION, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, Direction.WEST));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).locations().size(), 3,
                        "both keepers and the rack joined the warehouse"))
                .thenExecute(() -> {
                    keeperRule(helper, CORNER_STATION, IRON, IRON_MAXIMUM);
                    keeperRule(helper, FAR_STATION, GOLD, GOLD_MAXIMUM);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    helper.assertValueEqual(controller.stockRules().size(), 2, "both rules are in the copy");
                    helper.assertValueEqual(controller.storeHeadroom(IRON),
                            (long) IRON_MAXIMUM - IRON_IN_STOCK, "and the iron maximum caps storing");
                })
                .thenExecute(() -> {
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.south(z), Blocks.AIR);
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.north(z), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    helper.assertValueEqual(controller.stockRules().size(), 1,
                            "the rule of the keeper that is gone went with it");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), (long) IRON_MAXIMUM - IRON_IN_STOCK,
                            "the rule of the keeper that is still there still caps storing");
                    helper.assertValueEqual(controller.storeHeadroom(GOLD), Long.MAX_VALUE,
                            "and nothing caps what the warehouse no longer governs");
                    helper.assertValueEqual(controller.locationAt(helper.absolutePos(CORNER_STATION))
                            .map(LocationRecord::position), Optional.of(new RackPosition(1, 0, 0, Side.RIGHT)),
                            "the keeper that stayed is known under the name its aisle gives it now");
                    helper.assertValueEqual(controller.stockRuleStatus(helper.absolutePos(FAR_STATION), 0),
                            StockRuleStatus.NO_WAREHOUSE, "and the one that left signals nothing at all");
                })
                .thenSucceed();
    }

    /**
     * <b>The gate comes down.</b> Before the crane could turn, the controller refused to plan a job towards an aisle
     * the machine could not drive to, and the crane refused to carry one out; this test watched it refuse. It now
     * watches it <b>work</b>: the warehouse's only storage location is on the second aisle, the input is full of iron
     * on the first one, and the crane drives out, turns the corner and stores every ingot in the chest round the bend
     * (M21 part two, ADR-033).
     * <p>
     * The two assertions that make this more than "the items arrived": the crane really <b>was</b> on the second aisle
     * while it worked — its pose said so, which no amount of teleporting items could fake — and the item census is
     * exact on every single tick of the crossing, so nothing was duplicated or lost at the hand-over.
     * <p>
     * With {@code aisle.maxBranches = 1} the second aisle is not part of the warehouse at all, so the iron stays in
     * the input and nothing moves — the same answer this test gave before the crane could turn, for the older reason.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkcranestoresroundthecorner(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST);
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));

        Map<ItemKey, Long> conserved = new HashMap<>();
        if (!cornersEnabled()) {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).inputStations().size(), 1,
                            "the input joined"))
                    .thenExecute(() -> {
                        insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, 8));
                        conserved.putAll(ItemCensus.take(helper));
                    })
                    .thenExecuteAfter(200, () -> {
                        helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                                "the warehouse is the one aisle it was before 0.6");
                        helper.assertValueEqual(countIn(helper, INPUT, Items.IRON_INGOT), 8,
                                "so the iron never left the input station");
                        ItemCensus.assertEquals(helper, conserved, "with corners switched off");
                    })
                    .thenSucceed();
            return;
        }

        AtomicReference<Boolean> turned = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper).inputStations().size(), 1, "the input joined");
                    helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                            "and the rack round the corner joined too");
                })
                .thenExecute(() -> {
                    helper.assertTrue(warehouse(helper).reachable(RackPosition.FIRST_BRANCH, 1),
                            "the warehouse says the crane can drive to the second aisle");
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, 8));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> {
                        // Every tick of the crossing, hand-over included: an item is always in exactly one place.
                        ItemCensus.assertEquals(helper, conserved, "while the crane crosses the corner");
                        if (dock(helper).craneState().pose().branch() == 1)
                            turned.set(true);
                    });
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT), 8,
                        "every ingot arrived in the chest round the corner"))
                .thenExecute(() -> {
                    helper.assertTrue(turned.get(),
                            "and the crane really stood on the second aisle to put it there");
                    helper.assertValueEqual(countIn(helper, INPUT, Items.IRON_INGOT), 0,
                            "with none of it left in the input station");
                    ItemCensus.assertEquals(helper, conserved, "after the crossing");
                })
                .thenSucceed();
    }

    /**
     * The new shape of "unreachable", now that a connected warehouse has none: a rail broken behind the crane cuts the
     * second aisle off, and the warehouse says <b>that</b> rather than "not in stock".
     * <p>
     * Nothing about it is a fault the mod has to repair. The rack keeps its address and its stock, every item stays in
     * its chest, no job is planned towards it — and putting the rail back makes the warehouse whole again on the next
     * refresh, with the same address and the same items.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkreportsanaislethecranecannotreach(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST);
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack round the corner joined"))
                .thenExecute(() -> {
                    // The rail that joins the two aisles is closed with a wrench, but the second aisle's own rails
                    // stay: the crane knows the aisle and simply cannot get onto it.
                    helper.setBlock(CORNER.south(1), WarehouseRailBlock.along(Direction.Axis.Z)
                            .setValue(WarehouseRailBlock.CLOSED, true));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                            "the second aisle is no longer part of the warehouse");
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, 8));
                    conserved.putAll(ItemCensus.take(helper));
                    helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved,
                            "while the warehouse cannot reach a rack it knows"));
                })
                .thenExecuteAfter(200, () -> {
                    helper.assertValueEqual(countIn(helper, INPUT, Items.IRON_INGOT), 8,
                            "the iron never left the input station");
                    helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT), 0,
                            "and never arrived in a chest the crane cannot drive to");
                    helper.assertValueEqual(dock(helper).heldItems().totalCount(), 0L,
                            "the crane is not holding any of it either");
                    helper.assertTrue(dock(helper).currentJob().isEmpty(),
                            "no job may be planned towards an aisle the crane cannot reach");
                })
                .thenExecute(() -> {
                    // The one-block cure: open the rail again and the warehouse is whole.
                    helper.setBlock(CORNER.south(1), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the second aisle is back");
                    helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT), 8,
                            "and the iron goes round the corner after all");
                })
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the rail came back"))
                .thenSucceed();
    }

    /**
     * <b>The crane goes through the remap with everything else.</b> The machine is parked out on the second aisle when
     * that aisle is rebuilt on the other side of the corner, so the block it stands on is not a rail any anymore — and
     * the number it carries now names a block on the opposite arm.
     * <p>
     * The remap used to move every record, count, rule and order through the world blocks their labels stood for and
     * leave the crane out, on the argument that "the aisle at the dock is pinned to the dock, so a remap can never
     * move a position the crane is working on". That holds for the aisle at the dock and for nothing else (M21 review
     * fix): the machine would have been drawn on the other arm from one tick to the next, and would have driven its
     * next route from a point it is not at.
     * <p>
     * What must happen: its pose is renamed through the same world block as everything else, and since that block is
     * gone it goes back onto the aisle at the dock — which is a place it can really drive from. The warehouse then
     * serves a rack on the rebuilt aisle, which is the proof that it drives from somewhere real.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkremapmovesthecranewithit(GameTestHelper helper) {
        buildCorner(helper);
        // The only storage location is out on the second aisle, so the crane drives there and parks there.
        placeStorage(helper, SECOND_AISLE_RACK, SECOND_AISLE_CHEST, Direction.EAST);
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        AtomicReference<Boolean> counting = new AtomicReference<>(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack on the second aisle joined"))
                .thenExecute(() -> insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK)))
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT),
                        IRON_IN_STOCK, "the iron was stored round the corner"))
                .thenWaitUntil(() -> {
                    CranePose pose = dock(helper).craneState().pose();
                    helper.assertValueEqual(pose.branch(), 1, "the crane is parked on the second aisle");
                    helper.assertTrue(pose.x() >= 1.0, "away from the corner block, on rails the rebuild takes away");
                })
                .thenExecute(() -> {
                    conserved.putAll(ItemCensus.take(helper));
                    counting.set(true);
                    helper.onEachTick(() -> {
                        if (counting.get())
                            ItemCensus.assertEquals(helper, conserved, "while the aisle under the crane is rebuilt");
                    });
                    // The rebuild, in one tick: the second aisle now runs north out of the same corner block.
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.south(z), Blocks.AIR);
                    for (int z = 1; z <= SECOND_RAILS; z++)
                        helper.setBlock(CORNER.north(z), WarehouseRailBlock.along(Direction.Axis.Z));
                    dock(helper).refreshGeometry();
                    controller(helper).relinkNow();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 2, "the warehouse still bends");
                    helper.assertValueEqual(warehouse.branch(1).heading(), Direction.NORTH, "the other way now");
                    CranePose pose = dock(helper).craneState().pose();
                    helper.assertValueEqual(pose.branch(), 0,
                            "the block the machine stood on is gone, so it is back on the aisle at the dock");
                    helper.assertTrue(pose.x() >= 0.0 && pose.x() <= FIRST_RAILS,
                            "at a position that aisle really has: " + pose.x());
                    helper.assertValueEqual(dock(helper).craneState().target().branch(), 0,
                            "and it is not driving towards a number on an aisle it is not on");
                    // A rack beside the rebuilt aisle, and more work for it.
                    placeStorage(helper, REBUILT_AISLE_RACK, REBUILT_AISLE_CHEST, Direction.EAST);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the rack on the rebuilt aisle joined"))
                // More items than the test started with, so the census starts again from what is really there.
                .thenExecute(() -> {
                    counting.set(false);
                    insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    conserved.clear();
                    conserved.putAll(ItemCensus.take(helper));
                    counting.set(true);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, REBUILT_AISLE_CHEST, Items.IRON_INGOT),
                        IRON_IN_STOCK, "and the crane serves the rebuilt aisle from where it really stands"))
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(helper, SECOND_AISLE_CHEST, Items.IRON_INGOT), IRON_IN_STOCK,
                            "the iron in the chest the rebuild orphaned is untouched");
                    ItemCensus.assertEquals(helper, conserved, "after the rails were rebuilt under the machine");
                })
                .thenSucceed();
    }

    // --- a reload in the middle of a job -------------------------------------------------------------------------

    /**
     * The crane is saved while it stands on the <b>corner block</b> — position 4 of the first aisle and position 0 of
     * the second one at the same time — in the middle of a job, and the dock and the controller are both replaced by
     * copies loaded from their saves. The pose, the job and the items in the head come back exactly, and so do the
     * warehouse's aisles, letters and records.
     * <p>
     * Nothing here needs the crane to know about the corner: the aisle at the dock is pinned to the dock, so no rebuild
     * can ever renumber a position the crane is working on. That is why turning can safely come later.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkreloadkeepsacranestandingonacorner(GameTestHelper helper) {
        buildCorner(helper);
        // The only storage location is the one beside the corner block, so the crane has to drive there.
        placeStorage(helper, CORNER_RACK, CORNER_CHEST, Direction.NORTH);
        helper.setBlock(INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.SOUTH));

        AtomicReference<CompoundTag> dockSave = new AtomicReference<>();
        AtomicReference<CompoundTag> controllerSave = new AtomicReference<>();
        AtomicReference<CranePose> poseAtSave = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).storageLocations().size(), 1,
                        "the corner rack joined"))
                .thenExecute(() -> insertAll(helper, INPUT, new ItemStack(Items.IRON_INGOT, 16)))
                .thenExecute(() -> helper.onEachTick(() -> {
                    if (dockSave.get() != null)
                        return;
                    StackerCraneBlockEntity dock = dock(helper);
                    CranePose pose = dock.craneState().pose();
                    if (dock.currentJob().isEmpty() || pose.x() < FIRST_RAILS - 0.001)
                        return;
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    poseAtSave.set(pose);
                    dockSave.set(dock.saveWithFullMetadata(registries));
                    controllerSave.set(controller(helper).saveWithoutMetadata(registries));
                }))
                .thenWaitUntil(() -> helper.assertTrue(dockSave.get() != null,
                        "the crane reached the corner block with a job"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    StackerCraneBlockEntity dock = dock(helper);
                    BlockEntity loadedBlockEntity = BlockEntity.loadStatic(
                            dock.getBlockPos(), dock.getBlockState(), dockSave.get(), registries);
                    if (!(loadedBlockEntity instanceof StackerCraneBlockEntity loadedDock)) {
                        helper.fail("the saved dock did not load", DOCK);
                        return;
                    }
                    helper.assertValueEqual(loadedDock.craneState().pose(), poseAtSave.get(),
                            "the crane comes back exactly where it stood");
                    helper.assertValueEqual(loadedDock.craneState().pose().x(), (double) FIRST_RAILS,
                            "which is the corner block");
                    helper.assertValueEqual(loadedDock.currentJob().map(job -> job.id()),
                            dock.currentJob().map(job -> job.id()), "with the same job");
                    helper.assertValueEqual(loadedDock.currentJob().map(job -> job.target()),
                            dock.currentJob().map(job -> job.target()), "and the same target");
                    helper.assertValueEqual(loadedDock.heldItems().totalCount(), dock.heldItems().totalCount(),
                            "and the same items in its head");

                    WarehouseControllerBlockEntity controller = controller(helper);
                    WarehouseControllerBlockEntity loadedController = freshController(helper, controller);
                    loadedController.loadWithComponents(controllerSave.get(), registries);
                    helper.assertValueEqual(loadedController.warehouse().map(WarehouseLayout::branchCount),
                            Optional.of(cornersEnabled() ? 2 : 1), "the warehouse keeps every aisle it had");
                    if (cornersEnabled())
                        helper.assertValueEqual(loadedController.warehouse().map(w -> w.branch(1).letter()),
                                Optional.of(Optional.of('B')), "with the same letter on the second one");
                    helper.assertValueEqual(loadedController.locations(), controller.locations(),
                            "and the same records");
                })
                .thenSucceed();
    }

    // --- the review fixes ----------------------------------------------------------------------------------------

    /**
     * The ownership rule has to be decided <b>before</b> anything is written, and this is the block that proves it: the
     * column diagonally inside the bend is laterally beside a straight rail of <i>both</i> aisles, and each of them
     * wants its own facing there. A warehouse terminal is the one member that turns itself towards its aisle, so while
     * the probe aligned first and asked afterwards, both aisles wrote: the same terminal registered as an output twice,
     * the crane could address it under two addresses, and the block state flipped twice <b>every tick</b> for ever —
     * the M10 failure mode reproduced inside a single warehouse (M21 review fix).
     * <p>
     * The terminal is placed facing neither aisle, with its screen on one of the two faces neither of them wants, which
     * is exactly the case none of {@code alignToAisle}'s three refusals covers.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networkinnercornerbelongstoexactlyoneaisle(GameTestHelper helper) {
        buildCorner(helper);
        helper.setBlock(INNER_CORNER, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, Direction.SOUTH)
                .setValue(WarehouseTerminalBlock.DISPLAY, TerminalDisplaySide.RIGHT));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        AtomicReference<BlockState> settled = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the second aisle joined the warehouse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(memberRecordsAt(helper, INNER_CORNER), 1,
                            "the terminal inside the bend belongs to one aisle, not to both");
                    helper.assertValueEqual(helper.getBlockState(INNER_CORNER)
                            .getValue(WarehouseTerminalBlock.FACING), Direction.NORTH,
                            "the lowest aisle of the block is the one that turned it, always the same one");
                    helper.assertValueEqual(helper.getBlockState(INNER_CORNER).getValue(WarehouseTerminalBlock.DISPLAY)
                            .of(Direction.NORTH), Direction.WEST, "and the screen stayed where the player put it");
                    settled.set(helper.getBlockState(INNER_CORNER));
                    // The defect was per tick, not per rebuild, so the watch has to be per tick too.
                    helper.onEachTick(() -> {
                        helper.assertValueEqual(helper.getBlockState(INNER_CORNER), settled.get(),
                                "nothing may rewrite the terminal's block state again");
                        helper.assertValueEqual(memberRecordsAt(helper, INNER_CORNER), 1,
                                "and it stays a member of exactly one aisle");
                    });
                })
                .thenExecuteAfter(STABLE_TICKS, () -> helper.assertValueEqual(
                        controller(helper).outputStations().size(), 1,
                        "and the warehouse still knows exactly one output station"))
                .thenSucceed();
    }

    /**
     * One badly turned block beside a corner is <b>one</b> misaligned block. The column inside the bend is a rack
     * position of both aisles, and a member that fits neither used to be reported by both of them: the goggles read
     * "Misaligned blocks: 2" for a single interface and the save carried two entries for it. Only the candidate that
     * may turn the block — the lowest, the same one on every pass — reports it now (M21 review fix).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networkabadlyturnedblockinsidethebendiscountedonce(GameTestHelper helper) {
        buildCorner(helper);
        // North fits neither aisle: the first one wants south there, the second one west.
        helper.setBlock(INNER_CORNER, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the second aisle joined the warehouse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    helper.assertValueEqual(controller.misalignedCount(), 1,
                            "one badly turned interface is one misaligned block, not one per aisle");
                    helper.assertValueEqual(memberRecordsAt(helper, INNER_CORNER), 0,
                            "and it is a member of neither aisle");
                    helper.assertValueEqual(helper.getBlockState(INNER_CORNER)
                            .getValue(WarehouseInterfaceBlock.FACING), Direction.NORTH,
                            "an interface is never turned for the player, so the hint has to stay");
                    helper.assertValueEqual(controller.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .getIntArray("Misaligned").length, 3, "and the save carries it exactly once");
                })
                .thenExecute(() -> {
                    // Turning it towards the second aisle clears the hint and makes it that aisle's location - the
                    // candidate that reported it is not the one that owns it.
                    helper.setBlock(INNER_CORNER, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, Direction.WEST));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(controller(helper).misalignedCount(), 0, "nothing misaligned any more");
                    helper.assertValueEqual(controller(helper).locationAt(helper.absolutePos(INNER_CORNER))
                            .map(record -> record.position().branch()), Optional.of(1),
                            "and the block belongs to the aisle it faces");
                })
                .thenSucceed();
    }

    /**
     * The column the crane's mast travels through is no storage location. The rail just before a corner is laterally
     * beside the perpendicular aisle, so that rail's whole column was offered as a rack position of the other aisle: a
     * player could place an interface one block above a rail, facing the right way, and it was registered as a full
     * storage location inside the aisle the crane drives through — reached by extending the arm back over the rails it
     * came from (M21 review fix).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networkthemastcolumnisnostoragelocation(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, ABOVE_THE_RAIL, ABOVE_THE_RAIL.west(), Direction.WEST,
                new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the second aisle joined the warehouse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    WarehouseLayout warehouse = warehouse(helper);
                    BlockPos rail = helper.absolutePos(CORNER.west());
                    helper.assertTrue(warehouse.candidates(rail).isEmpty(),
                            "a rail of one aisle is no rack position of the other one");
                    helper.assertTrue(warehouse.candidates(rail.above()).isEmpty(),
                            "and neither is the column above it");
                    helper.assertTrue(controller.locationAt(helper.absolutePos(ABOVE_THE_RAIL)).isEmpty(),
                            "so the interface above the rail is no storage location");
                    helper.assertValueEqual(controller.countOf(IRON), 0L,
                            "its chest is not part of the warehouse's stock");
                    helper.assertValueEqual(controller.misalignedCount(), 0,
                            "and it is not reported as badly turned either: no aisle ever wanted it");
                })
                .thenSucceed();
    }

    /**
     * A stock keeper whose whole aisle leaves the warehouse stops signalling. Closing the corner rail with a wrench
     * takes the second aisle away, and the label the keeper was known under then names <b>no block at all</b> — so the
     * removal pass that drops its rules could not reach the block any more. Nothing would ever write its state
     * again either: a keeper has no ticker, and the rule tick only walks the keepers the controller still holds rules
     * for. Its lamp burned and its comparator called for items for the rest of the session, and the lamp survived
     * every save (M21 review fix).
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkakeeperofalostaisleisquietened(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH,
                new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
        // A keeper faces the aisle it belongs to, so west towards the second aisle's rails.
        helper.setBlock(FAR_STATION, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, Direction.WEST));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the second aisle joined");
                    helper.assertTrue(controller(helper).locationAt(helper.absolutePos(FAR_STATION))
                            .isPresent(), "and the keeper on it is a member");
                })
                // "Keep at least 64 iron" with 16 in stock: the rule bites, so the lamp burns and the comparator asks.
                .thenExecute(() -> keeperMinimum(helper, FAR_STATION, IRON, IRON_MINIMUM))
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getBlockState(FAR_STATION)
                            .getValue(WarehouseStockKeeperBlock.LIT), "the keeper's lamp burns for the iron it wants");
                    helper.assertValueEqual(keeper(helper, FAR_STATION).comparatorSignal(), 1,
                            "and its comparator calls for it");
                })
                .thenExecute(() -> {
                    // A closed rail is not an aisle block, so the whole second aisle leaves the warehouse.
                    helper.setBlock(CORNER, WarehouseRailBlock.along(Direction.Axis.X)
                            .setValue(WarehouseRailBlock.CLOSED, true));
                    dock(helper).requestGeometryRefresh();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 1,
                        "the second aisle is gone"))
                .thenExecute(() -> {
                    helper.assertTrue(controller(helper).locationAt(helper.absolutePos(FAR_STATION)).isEmpty(),
                            "the keeper left the warehouse with its aisle");
                    helper.assertFalse(helper.getBlockState(FAR_STATION)
                            .getValue(WarehouseStockKeeperBlock.LIT),
                            "its lamp went out in the very pass that took its aisle away");
                    helper.assertValueEqual(keeper(helper, FAR_STATION).comparatorSignal(), 0,
                            "and its comparator stopped calling for items");
                    helper.assertValueEqual(controller(helper).stockRuleStatus(
                            helper.absolutePos(FAR_STATION), 0), StockRuleStatus.NO_WAREHOUSE,
                            "the block itself says it governs nothing");
                })
                // It stays quiet: nothing may light it again from the warehouse it is no longer part of.
                .thenExecuteAfter(STABLE_TICKS, () -> {
                    helper.assertFalse(helper.getBlockState(FAR_STATION)
                            .getValue(WarehouseStockKeeperBlock.LIT), "and it stays dark");
                    helper.assertValueEqual(keeper(helper, FAR_STATION).comparatorSignal(), 0,
                            "with its comparator at zero");
                })
                .thenSucceed();
    }

    /**
     * The controller's goggles really carry the shape of the rails to a client: a warehouse that bends names its rails,
     * its aisles, their letters and their lengths, and where and why the discovery stopped — and all of it survives the
     * round trip through the synced tag, which is the only path a player's screen ever sees (M21, issue #1, ADR-033).
     * <p>
     * This is the world half of {@code NetworkGoggleInfoTest}: that test cannot touch a {@code CompoundTag} at all,
     * because the JUnit source set is pure Java with no Minecraft on its classpath.
     * <p>
     * The fault it then provokes is a <b>second dock</b> at the far end of the second aisle. Of the eight reasons a
     * discovery can stop for, that one is the only one a test can place deterministically and read back to the block:
     * the walk names the dock's own position, so the line a player reads points at the block they have to look at.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void networkgogglesnametheaislesandwheretheystop(GameTestHelper helper) {
        buildCorner(helper);
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        BlockPos secondDock = CORNER.south(SECOND_RAILS + 1);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(warehouse(helper).branchCount(), 2,
                        "the second aisle joined the warehouse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    NetworkGoggleInfo shown = shownNetwork(helper);
                    helper.assertValueEqual(shown.aisleCount(), 2, "the goggles know both aisles");
                    helper.assertValueEqual(shown.aisleLetters(), "AB", "each under its own letter");
                    helper.assertValueEqual(shown.aisleLengths(), List.of(FIRST_RAILS, SECOND_RAILS),
                            "with the rails each of them really has");
                    helper.assertValueEqual(shown.rails(), FIRST_RAILS + SECOND_RAILS,
                            "and the rails of the whole warehouse");
                    helper.assertFalse(shown.stopsShort(), "nothing cut this warehouse short");
                })
                // A second dock at the far end: a wall, so the warehouse keeps working and says where it ends.
                .thenExecute(() -> helper.setBlock(secondDock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                        .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.SOUTH)))
                .thenWaitUntil(() -> helper.assertValueEqual(shownNetwork(helper).stop(), NetworkStop.SECOND_DOCK,
                        "the goggles name the second dock as the reason"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controller(helper);
                    NetworkGoggleInfo shown = shownNetwork(helper);
                    helper.assertTrue(shown.stopsShort(), "and call it a warehouse that stops short");
                    helper.assertValueEqual(controller.dockPos().offset(shown.stopDx(), 0, shown.stopDz()),
                            helper.absolutePos(secondDock),
                            "pointing at the very block a player has to go and look at");
                    helper.assertValueEqual(shown.aisleCount(), 2, "both aisles are still there");
                })
                .thenSucceed();
    }

    /**
     * The network line of the controller's goggles as a client would read it: refreshed on the server, written into the
     * tag that travels with every chunk packet, and read back out of it.
     */
    private static NetworkGoggleInfo shownNetwork(GameTestHelper helper) {
        WarehouseControllerBlockEntity controller = controller(helper);
        controller.onGoggleObserved();
        CompoundTag tag = new CompoundTag();
        controller.summary().write(tag);
        return ControllerGoggleSummary.read(tag).network()
                .orElseThrow(() -> new GameTestAssertException("the goggles say nothing about the rails at all"));
    }

    /**
     * An aisle that disappears must not take another aisle's open requests with it. Closing the corner rail with a
     * wrench ends the second aisle, so every record on it leaves the warehouse — and while a label whose aisle is gone
     * still resolved to "the first aisle's block at the same position and side", the controller cancelled the open
     * requests of the perfectly good output station standing there, together with the crane job serving them. Open
     * requests are the one thing a warehouse cannot re-derive from the world, so nothing brought them back
     * (M21 review fix).
     * <p>
     * The two stations are chosen to collide exactly: the one on the second aisle is position 2 on its right, the one
     * on the first aisle is position 2 on its right, and they are four blocks apart in the world.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void networkalostaisleleavesotherrequestsalone(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, FIRST_AISLE_RACK, FIRST_AISLE_CHEST, Direction.NORTH,
                new ItemStack(Items.IRON_INGOT, IRON_IN_STOCK));
        helper.setBlock(FIRST_AISLE_OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.NORTH));
        helper.setBlock(SECOND_AISLE_OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, Direction.EAST));
        if (!cornersEnabled()) {
            assertCornersAreOff(helper);
            return;
        }

        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 2, "the second aisle joined");
                    helper.assertValueEqual(controller(helper).outputStations().size(), 2, "both outputs joined");
                    helper.assertValueEqual(controller(helper).countOf(IRON), (long) IRON_IN_STOCK, "the iron is read");
                })
                .thenExecute(() -> {
                    RackPosition onFirst = rackOf(helper, FIRST_AISLE_OUTPUT);
                    RackPosition onSecond = rackOf(helper, SECOND_AISLE_OUTPUT);
                    helper.assertValueEqual(onSecond.x(), onFirst.x(), "the two stations share a position number");
                    helper.assertValueEqual(onSecond.side(), onFirst.side(), "and a side");
                    helper.assertTrue(onSecond.branch() != onFirst.branch(), "on two different aisles");
                    helper.assertTrue(controller(helper)
                            .request(helper.absolutePos(FIRST_AISLE_OUTPUT), IRON, IRON_IN_STOCK).isAccepted(),
                            "the request on the first aisle is accepted");
                    conserved.putAll(ItemCensus.take(helper));
                    // The wrench's own effect: a closed rail is not an aisle block, so the second aisle ends here.
                    helper.setBlock(CORNER, WarehouseRailBlock.along(Direction.Axis.X)
                            .setValue(WarehouseRailBlock.CLOSED, true));
                    dock(helper).requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(warehouse(helper).branchCount(), 1, "the second aisle is gone");
                    helper.assertTrue(controller(helper)
                            .locationAt(helper.absolutePos(SECOND_AISLE_OUTPUT)).isEmpty(),
                            "and its output station left the warehouse");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(controller(helper)
                            .requestsFor(helper.absolutePos(FIRST_AISLE_OUTPUT)).size(), 1,
                            "the request of the station that is still there survives");
                    ItemCensus.assertEquals(helper, conserved, "and no item moved while the aisle went away");
                })
                .thenSucceed();
    }

    /** How many location records of this warehouse name the world block at {@code pos}. */
    private static int memberRecordsAt(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        WarehouseControllerBlockEntity controller = controller(helper);
        int found = 0;
        for (LocationRecord record : controller.locations()) {
            if (controller.worldPosOf(record.position()).filter(absolute::equals).isPresent())
                found++;
        }
        return found;
    }

    // --- the off switch ------------------------------------------------------------------------------------------

    /**
     * Whether a warehouse may bend at all: {@code aisle.maxBranches = 1} is the switch a server owner sets to keep
     * every warehouse the single straight aisle it was before 0.6, and it is the regression oracle of this milestone —
     * the whole test suite has to pass with it, unedited, and reproduce exactly what 0.5.0 did.
     */
    private static boolean cornersEnabled() {
        return WareworksConfig.maxBranches() > 1;
    }

    /**
     * What every test in this holder asserts instead while corners are switched off: the warehouse is the one straight
     * aisle it always was, nothing beyond the corner belongs to it, and it saves exactly the tag it always saved.
     * Nothing about the items in the world changes either — the rails beyond the corner are simply rails.
     */
    private static void assertCornersAreOff(GameTestHelper helper) {
        Map<ItemKey, Long> conserved = new HashMap<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(controller(helper).status(), ControllerStatus.READY,
                        "the warehouse is ready"))
                .thenExecute(() -> conserved.putAll(ItemCensus.take(helper)))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseLayout warehouse = warehouse(helper);
                    helper.assertValueEqual(warehouse.branchCount(), 1, "one aisle, exactly as before 0.6");
                    helper.assertValueEqual(warehouse.geometry().length(), FIRST_RAILS, "over its own rails");
                    helper.assertValueEqual(warehouse.firstBranch().letter(), Optional.of('A'),
                            "under the controller's own letter");
                    helper.assertTrue(controller(helper).locationAt(helper.absolutePos(SECOND_AISLE_RACK)).isEmpty(),
                            "and nothing beyond the corner is part of it");
                    helper.assertTrue(controller(helper).locationAt(helper.absolutePos(CORNER_STATION)).isEmpty(),
                            "not even beside the corner block");
                    helper.assertFalse(controller(helper).saveWithoutMetadata(helper.getLevel().registryAccess())
                            .contains("Network"), "so it saves no network either");
                    ItemCensus.assertEquals(helper, conserved, "with corners switched off");
                })
                .thenSucceed();
    }

    // --- building ------------------------------------------------------------------------------------------------

    /** Motor, dock, controller and the first aisle's rails. */
    private static void buildFirstAisle(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        for (int x = 1; x <= FIRST_RAILS; x++)
            helper.setBlock(DOCK.east(x), WarehouseRailBlock.along(Direction.Axis.X));
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
    }

    /** The first aisle plus a second one running south out of the corner block. */
    private static void buildCorner(GameTestHelper helper) {
        buildFirstAisle(helper);
        for (int z = 1; z <= SECOND_RAILS; z++)
            helper.setBlock(CORNER.south(z), WarehouseRailBlock.along(Direction.Axis.Z));
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

    private static void keeperRule(GameTestHelper helper, BlockPos keeperPos, ItemKey key, int maximum) {
        WarehouseStockKeeperBlockEntity keeper = keeper(helper, keeperPos);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, maximum);
    }

    /** "Keep at least {@code minimum} of {@code key}": the rule that lights the lamp and powers the comparator. */
    private static void keeperMinimum(GameTestHelper helper, BlockPos keeperPos, ItemKey key, int minimum) {
        WarehouseStockKeeperBlockEntity keeper = keeper(helper, keeperPos);
        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, minimum);
    }

    private static WarehouseStockKeeperBlockEntity keeper(GameTestHelper helper, BlockPos keeperPos) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER
                .getNullable(helper.getLevel(), helper.absolutePos(keeperPos));
        if (keeper == null) {
            helper.fail("missing stock keeper", keeperPos);
            throw new AssertionError("unreachable");
        }
        return keeper;
    }

    private static void pattern(GameTestHelper helper, BlockPos station, ItemKey ingredient, ItemKey result) {
        WarehouseProductionBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION
                .getNullable(helper.getLevel(), helper.absolutePos(station));
        if (be == null) {
            helper.fail("missing production station", station);
            return;
        }
        helper.assertTrue(be.setPatternEntry(0, 0, ingredient, 1), "ingredient set at " + station);
        helper.assertTrue(be.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, result, 1), "result set at " + station);
    }

    // --- reading -------------------------------------------------------------------------------------------------

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
        LocationRecord record = controller(helper).locationAt(helper.absolutePos(pos)).orElse(null);
        if (record == null) {
            helper.fail("no warehouse member", pos);
            throw new AssertionError("unreachable");
        }
        return record.position();
    }

    private static ProductionOrder<ItemKey, RackPosition> onlyOrderAt(GameTestHelper helper, RackPosition station) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = new ArrayList<>();
        for (ProductionOrder<ItemKey, RackPosition> order : controller(helper).productionOrders()) {
            if (order.station().equals(station))
                orders.add(order);
        }
        if (orders.size() != 1) {
            helper.fail("expected exactly one production order at " + station + ", found " + orders.size());
            throw new AssertionError("unreachable");
        }
        return orders.getFirst();
    }

    private static ProductionOrder<ItemKey, RackPosition> order(GameTestHelper helper,
                                                                WarehouseControllerBlockEntity controller, UUID id) {
        ProductionOrder<ItemKey, RackPosition> order = controller.productionOrder(id).orElse(null);
        if (order == null) {
            helper.fail("the production order " + id + " is gone entirely");
            throw new AssertionError("unreachable");
        }
        return order;
    }

    /** How many records of the save carry a branch of their own; zero for every warehouse of one aisle. */
    private static int branchKeys(CompoundTag saved) {
        int found = 0;
        ListTag locations = saved.getList("Locations", Tag.TAG_COMPOUND);
        for (int i = 0; i < locations.size(); i++) {
            if (locations.getCompound(i).contains("B"))
                found++;
        }
        return found;
    }

    /** A saved location record in exactly the shape a warehouse wrote before M21. */
    private static CompoundTag legacyRecord(int x, int y, String side, String kind, HolderLookup.Provider registries,
                                            int iron) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("X", x);
        entry.putInt("Y", y);
        entry.putString("Side", side);
        entry.putString("Kind", kind);
        ListTag stock = new ListTag();
        if (iron > 0) {
            CompoundTag item = new CompoundTag();
            item.put("Item", IRON.save(registries));
            item.putLong("Count", iron);
            stock.add(item);
        }
        entry.put("Stock", stock);
        return entry;
    }

    private static WarehouseControllerBlockEntity freshController(GameTestHelper helper,
                                                                  WarehouseControllerBlockEntity controller) {
        WarehouseControllerBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .create(controller.getBlockPos(), controller.getBlockState());
        if (copy == null) {
            helper.fail("could not create a detached warehouse controller");
            throw new AssertionError("unreachable");
        }
        return copy;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("no item handler", pos);
            throw new AssertionError("unreachable");
        }
        return handler;
    }

    private static void insertAll(GameTestHelper helper, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handlerAt(helper, pos), stack.copy(), false);
        if (!rest.isEmpty())
            helper.fail("could not insert " + stack + " at " + pos);
    }

    private static int countIn(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item) {
        IItemHandler handler = handlerAt(helper, pos);
        int found = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                found += stack.getCount();
        }
        return found;
    }
}
