package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.AisleLetterBehaviour;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.inventory.LocationCount;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.warehouse.AisleNames;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksTags;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the warehouse controller: linking to the dock, membership of interfaces at rack positions, addresses,
 * stock index, registry, persistence and goggle data.
 * <p>
 * Layout on the {@code aisle_16x10x7} floor: controller at x = 0, dock at x = 1 (z = 3, aisle along +X) with a creative
 * motor below it, rails at x = 2..6; left rack plane z = 2 (inventories at z = 1), right rack plane z = 4 (inventories
 * at z = 5). Assertions in sequences only use {@code helper.fail/assert*}; block entities are looked up through typed
 * accessors.
 * <p>
 * Not covered here: rack positions in unloaded chunks (GameTest areas are force-loaded); the rule is covered by
 * {@code AisleMembershipTest} (JUnit) and the {@code level.isLoaded} guards.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseControllerGameTests {
    private static final Direction AISLE = Direction.EAST;
    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, 3);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, 3);
    private static final int RAILS = 5;
    /** Test-relative aisle mapping (rack positions do not depend on the geometry). */
    private static final BranchLayout RELATIVE = BranchLayout.of(DOCK, AISLE, AisleGeometry.of(RAILS, 1));

    private static final RackPosition RACK_01_00R = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition RACK_01_00L = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition RACK_01_01L = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition RACK_02_03R = new RackPosition(3, 1, Side.RIGHT);
    private static final RackPosition RACK_04_05L = new RackPosition(5, 3, Side.LEFT);
    private static final RackPosition RACK_03_02R = new RackPosition(2, 2, Side.RIGHT);
    private static final RackPosition RACK_MISALIGNED = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition RACK_OUTSIDE = new RackPosition(RAILS + 2, 0, Side.LEFT);
    private static final RackPosition RACK_01_03L = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition RACK_01_04L = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition RACK_01_03R = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition RACK_01_04R = new RackPosition(4, 0, Side.RIGHT);
    // Full layout: stations on free right-hand positions of level 1 next to the storage locations of buildAisle.
    private static final RackPosition OUTPUT_01_01R = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition INPUT_01_02R = new RackPosition(2, 0, Side.RIGHT);

    // Dock switch layout: a controller between two docks on adjacent faces (east and south of it), no rails.
    private static final BlockPos SWITCH_CONTROLLER = new BlockPos(2, BASE_Y, 3);
    private static final BlockPos SWITCH_DOCK_EAST = SWITCH_CONTROLLER.east();
    private static final BlockPos SWITCH_DOCK_SOUTH = SWITCH_CONTROLLER.south();
    private static final BranchLayout SWITCH_EAST_LAYOUT = BranchLayout.of(SWITCH_DOCK_EAST, Direction.EAST,
            AisleGeometry.of(0, 1));
    private static final BranchLayout SWITCH_SOUTH_LAYOUT = BranchLayout.of(SWITCH_DOCK_SOUTH, Direction.SOUTH,
            AisleGeometry.of(0, 1));
    // Dock link owner layout: a second controller south of the dock, facing north.
    private static final BlockPos SOUTH_OF_DOCK = DOCK.south();
    private static final BlockPos NO_CONTROLLER_HERE = new BlockPos(RAILS + 4, BASE_Y, 0);

    private static final int IRON_AT_01_01L = 100;
    private static final int IRON_AT_02_03R = 30;
    private static final int GOLD_AT_02_03R = 20;
    private static final int DIAMONDS_AT_01_00R = 5;
    private static final int EMERALDS_OUTSIDE = 64;
    private static final int GOLD_ADDED = 10;
    private static final int DIAMONDS_ADDED = 7;
    private static final int APPLES_ADDED = 16;
    private static final int IRON_ADDED = 20;
    private static final int SHARED_DIAMONDS = 40;
    private static final int SHARED_IRON = 90;
    private static final int DOUBLE_CHEST_SLOTS = 54;
    private static final int NAMED_INGOTS = 2;
    private static final int STORAGE_LOCATIONS = 4;
    private static final int COBBLE_IN_INPUT = 12;
    private static final int GOLD_IN_OUTPUT = 8;
    private static final int ITEM_TYPES = 3;
    private static final long TOTAL_ITEMS = IRON_AT_01_01L + IRON_AT_02_03R + GOLD_AT_02_03R + DIAMONDS_AT_01_00R;
    private static final int LETTER_C = 2;
    private static final int OVERSIZED_LETTER = 99;
    private static final int TIMEOUT_TICKS = 400;
    private static final int SETTLE_TICKS = 3;
    private static final int MAX_SUMMARY_SYNC_BYTES = 2048;
    /**
     * Bound of the update tag of a controller whose aisles somebody has <b>named</b> (M25, issue #15), the way
     * {@link WareworksGameTests#MAX_RESERVED_INTERFACE_SYNC_BYTES} is the bound of an interface that really carries
     * reservations: the tight number above stays exactly what it was and goes on guarding the state every warehouse
     * is in until a player names something, and the state a player opted into gets its own.
     * <p>
     * It is a second number and not a bigger one because the tight one had <b>no room left</b>: this fixture's tag is
     * about 1905 accounting bytes before any name, and one 16-character name costs 150 of them —
     * {@code CompoundTag#sizeInBytes} charges {@code 28 + 2*key.length() + 36} for an entry and
     * {@code 36 + 2*text.length()} for its text, which is far more than the seventeen bytes the name really sends
     * (the same reading the interface's own two bounds are written on). What is really guarded is
     * {@link #MAX_NAME_SYNC_BYTES}, the cost of the names themselves.
     */
    private static final int MAX_NAMED_SUMMARY_SYNC_BYTES = 4096;
    /**
     * What the <b>names</b> of a warehouse may add to that budget at the very worst (M25, issue #15): 16 characters
     * on the dock aisle plus {@code NetworkGoggleInfo.NAMES_LISTED} of them in the network record.
     * <p>
     * By {@code CompoundTag}'s own accounting — {@code 28 + 2*key.length() + 36} per entry plus
     * {@code 36 + 2*text.length()} per {@code StringTag} — that is {@code 28 + 2*9 + 36 + (36 + 2*16) = 150} for the
     * dock aisle's own key and {@code 28 + 2*5 + 36 + (36 + 2*(6*16 + 5)) = 312} for the other six as one separated
     * string: <b>462</b> bytes, with a little room for a key that gets renamed.
     * <p>
     * The number is the names' own cost and not the whole summary's, because the size of a six-aisle summary is about
     * the <b>network record</b> M21 put there — a crafted six-aisle record alone takes it past 2048 accounting bytes,
     * which nothing measured before and which this feature must not be made to answer for — while the cost of a name
     * is exactly what a later change could make unbounded.
     */
    private static final int MAX_NAME_SYNC_BYTES = 480;
    private static final float[] PLAYER_YAWS = {0f, 90f, 180f, 270f};

    /**
     * The face a naming click uses (M25, issue #15): the one opposite the dock, which is the face a player standing
     * behind the controller sees. Any face but the dock's own and the bottom carries the value box, so a real click
     * has to miss the little sphere in the middle — which is what {@link NamingClick#use} does.
     */
    private static final Direction NAMING_FACE = AISLE.getOpposite();
    private static final String AISLE_NAME = "Ores";
    /** 19 characters, three over {@code AisleName.MAX_LENGTH}. */
    private static final String OVERLONG_NAME = "OresMetalsAndGravel";
    /** What {@link #OVERLONG_NAME} is stored as: its first {@code AisleName.MAX_LENGTH} characters. */
    private static final String CUT_NAME = "OresMetalsAndGra";
    private static final String GRAVEL = "Gravel";
    /** Exactly {@code AisleName.MAX_LENGTH} characters: what the sync-byte worst case is measured with. */
    private static final String LONGEST_NAME = "ABCDEFGHIJKLMNOP";
    /** Rails of the crafted network record the aisle list and the byte budget are checked against. */
    private static final int LISTED_RAILS = 44;
    /** Key of the controller's goggle summary inside its update tag ({@code WarehouseControllerBlockEntity}). */
    private static final String SUMMARY_TAG = "GoggleSummary";
    /** Key of the dock aisle's name inside that summary ({@code ControllerGoggleSummary}). */
    private static final String SUMMARY_AISLE_NAME = "AisleName";
    /** Key of the nested network record inside that summary. */
    private static final String SUMMARY_NETWORK = "Network";
    /** Key of the other aisles' names inside that record ({@code NetworkGoggleInfo}). */
    private static final String NETWORK_NAMES = "Names";
    /** Name of the crafted entry for each aisle letter, with the letter's index appended. */
    private static final String CRAFTED_NAME_PREFIX = "Aisle ";
    /** Entries a crafted {@code Names} tag offers beyond the one per aisle letter, which are never read. */
    private static final int CRAFTED_SURPLUS_ENTRIES = 4;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final ItemKey APPLE = ItemKey.of(Items.APPLE);

    private WarehouseControllerGameTests() {
    }

    // --- full aisle ----------------------------------------------------------------------------------------------

    /**
     * A full aisle: status, layout, member counts, addresses, stock totals, registry lookups, interface goggle
     * assignments, refresh on demand and round-robin reconciliation.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerFullAisle(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    BranchLayout layout = controller.layout().orElse(null);
                    if (layout == null) {
                        helper.fail("a READY controller must have a layout");
                        return;
                    }
                    helper.assertValueEqual(layout.dock(), helper.absolutePos(DOCK), "layout dock");
                    helper.assertValueEqual(layout.facing(), AISLE, "layout facing");
                    helper.assertValueEqual(layout.geometry(), AisleGeometry.of(RAILS, defaultMastHeight()), "geometry");
                    helper.assertValueEqual(layout.letter(), Optional.of('A'), "default aisle letter");

                    helper.assertValueEqual(positions(controller.storageLocations()),
                            List.of(RACK_01_00R, RACK_01_01L, RACK_02_03R, RACK_04_05L), "storage locations in order");
                    helper.assertValueEqual(controller.locations(), controller.storageLocations(), "only storage");
                    helper.assertTrue(controller.inputStations().isEmpty(), "no input stations");
                    helper.assertTrue(controller.outputStations().isEmpty(), "no output stations");
                    helper.assertValueEqual(controller.misalignedCount(), 1, "one misaligned interface");

                    helper.assertValueEqual(addressText(helper, controller, RACK_02_03R), Optional.of("A-02-03R"),
                            "address of the interface at level 2, position 3, right");
                    helper.assertValueEqual(addressText(helper, controller, RACK_01_01L), Optional.of("A-01-01L"),
                            "address of the interface at level 1, position 1, left");
                    helper.assertValueEqual(addressText(helper, controller, RACK_01_00R), Optional.of("A-01-00R"),
                            "address next to the dock");
                    helper.assertValueEqual(addressText(helper, controller, RACK_04_05L), Optional.of("A-04-05L"),
                            "address at the top of the last position");
                    helper.assertTrue(controller.addressOf(absRack(helper, RACK_OUTSIDE)).isEmpty(), "outside");
                    helper.assertTrue(controller.addressOf(helper.absolutePos(DOCK)).isEmpty(), "dock has no address");
                    helper.assertValueEqual(controller.locationAt(absRack(helper, RACK_02_03R)),
                            Optional.of(LocationRecord.of(RACK_02_03R, LocationKind.STORAGE)), "record at position");
                    helper.assertTrue(controller.locationAt(absRack(helper, RACK_MISALIGNED)).isEmpty(),
                            "a misaligned interface is no location");
                    helper.assertValueEqual(controller.worldPosOf(RACK_02_03R), Optional.of(absRack(helper, RACK_02_03R)),
                            "world position of a rack position");

                    helper.assertValueEqual(controller.countOf(IRON), (long) (IRON_AT_01_01L + IRON_AT_02_03R), "iron");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) GOLD_AT_02_03R, "gold");
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) DIAMONDS_AT_01_00R, "diamonds");
                    helper.assertValueEqual(controller.countOf(EMERALD), 0L, "an inventory outside the aisle");
                    StockView<ItemKey, RackPosition> stock = controller.stockIndex();
                    helper.assertValueEqual(stock.distinctKeys(), ITEM_TYPES, "item types");
                    helper.assertValueEqual(stock.totalItems(), TOTAL_ITEMS, "total items");
                    helper.assertValueEqual(stock.locations(),
                            List.of(RACK_01_00R, RACK_01_01L, RACK_02_03R, RACK_04_05L), "indexed locations");
                    helper.assertValueEqual(stock.locationsOf(IRON),
                            List.of(new LocationCount<>(RACK_01_01L, (long) IRON_AT_01_01L),
                                    new LocationCount<>(RACK_02_03R, (long) IRON_AT_02_03R)), "iron by location");
                    helper.assertTrue(stock.snapshotOf(RACK_04_05L).isPresent(), "an empty chest was snapshotted too");

                    helper.assertValueEqual(WarehouseRegistry.registeredLayout(level, helper.absolutePos(CONTROLLER))
                            .map(WarehouseLayout::firstBranch), Optional.of(layout), "registered layout");
                    helper.assertTrue(WarehouseRegistry.findController(level, absRack(helper, RACK_02_03R))
                            .orElse(null) == controller, "the registry finds the controller of a member");
                    helper.assertTrue(WarehouseRegistry.findController(level, absRack(helper, RACK_OUTSIDE)).isEmpty(),
                            "no controller outside the aisle");
                    helper.assertTrue(dockAt(helper).isControllerLinked(), "the dock shows the controller link");

                    assertAssignment(helper, RACK_02_03R, AisleAssignment.assigned(StorageAddress.parse("A-02-03R")));
                    assertAssignment(helper, RACK_MISALIGNED, AisleAssignment.MISALIGNED);
                    assertAssignment(helper, RACK_OUTSIDE, AisleAssignment.NONE);
                    WarehouseInterfaceBlockEntity observed = interfaceAt(helper, relPos(RACK_02_03R));
                    HolderLookup.Provider registries = level.registryAccess();
                    WarehouseInterfaceBlockEntity client = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                            .create(observed.getBlockPos(), observed.getBlockState());
                    if (client == null) {
                        helper.fail("could not create a detached interface");
                        return;
                    }
                    client.handleUpdateTag(observed.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.aisleAssignment(), observed.aisleAssignment(), "synced address");
                    helper.assertFalse(observed.saveWithFullMetadata(registries).contains("AisleAssignment"),
                            "the derived address is not saved");

                    // After a transfer the crane asks for an immediate refresh of the touched location.
                    insertAll(helper, handlerAt(helper, chestOf(RACK_02_03R)), new ItemStack(Items.GOLD_INGOT, GOLD_ADDED));
                    helper.assertTrue(controller.isSnapshotPending(RACK_02_03R),
                            "the chest's content hint reached the controller");
                    helper.assertTrue(controller.refreshLocation(RACK_02_03R), "refresh of a loaded storage location");
                    helper.assertFalse(controller.isSnapshotPending(RACK_02_03R), "the refresh satisfies the hint");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) (GOLD_AT_02_03R + GOLD_ADDED),
                            "gold after the refresh");
                    helper.assertFalse(controller.refreshLocation(RACK_MISALIGNED), "no refresh of a non-location");
                    // A change the chest reports (neighbour-change hint): read within a few ticks, not by the round robin.
                    insertAll(helper, handlerAt(helper, chestOf(RACK_01_01L)), new ItemStack(Items.IRON_INGOT, IRON_ADDED));
                    helper.assertTrue(controller.isSnapshotPending(RACK_01_01L), "content hint queued a snapshot");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).countOf(IRON),
                        (long) (IRON_AT_01_01L + IRON_AT_02_03R + IRON_ADDED), "stock after the content hint"))
                .thenExecute(() -> {
                    // A change nobody reports (a stack grown in place): the round robin picks it up.
                    chestAt(helper, chestOf(RACK_01_00R)).getItem(0).grow(DIAMONDS_ADDED);
                    helper.assertFalse(controllerAt(helper).isSnapshotPending(RACK_01_00R), "a silent change sends no hint");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND),
                        (long) (DIAMONDS_AT_01_00R + DIAMONDS_ADDED), "round-robin reconciliation"))
                .thenSucceed();
    }

    /**
     * Storage locations reading one inventory count it once: a double chest behind two interfaces and a two-block item
     * vault behind two interfaces. Removing one of the interfaces keeps the counts.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerSharedInventory(GameTestHelper helper) {
        helper.setBlock(DOCK, dockState(AISLE));
        placeRails(helper);
        helper.setBlock(CONTROLLER, controllerState(AISLE));
        // Chest halves along the aisle, fronts towards the interfaces: the lower x connects east, the higher x west.
        helper.setBlock(chestOf(RACK_01_03L), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(chestOf(RACK_01_04L), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        for (RackPosition rack : List.of(RACK_01_03R, RACK_01_04R))
            helper.setBlock(chestOf(rack), AllBlocks.ITEM_VAULT.getDefaultState()
                    .setValue(ItemVaultBlock.HORIZONTAL_AXIS, AISLE.getAxis()));
        for (RackPosition rack : List.of(RACK_01_03L, RACK_01_04L, RACK_01_03R, RACK_01_04R))
            helper.setBlock(relPos(rack), interfaceState(RELATIVE.sideDirection(rack.side())));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, 4);
                    helper.assertValueEqual(handlerAt(helper, chestOf(RACK_01_03L)).getSlots(), DOUBLE_CHEST_SLOTS,
                            "the chest halves form a double chest");
                    helper.assertValueEqual(inventoryId(helper, RACK_01_03R), inventoryId(helper, RACK_01_04R),
                            "the vault blocks form one vault");
                })
                .thenExecute(() -> {
                    insertAll(helper, handlerAt(helper, chestOf(RACK_01_04L)), new ItemStack(Items.DIAMOND, SHARED_DIAMONDS));
                    insertAll(helper, handlerAt(helper, chestOf(RACK_01_03R)), new ItemStack(Items.IRON_INGOT, SHARED_IRON));
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "hints processed");
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) SHARED_DIAMONDS, "double chest counted once");
                    helper.assertValueEqual(controller.countOf(IRON), (long) SHARED_IRON, "vault counted once");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    for (List<RackPosition> pair : List.of(List.of(RACK_01_03L, RACK_01_04L),
                            List.of(RACK_01_03R, RACK_01_04R))) {
                        helper.assertValueEqual(controller.locationsSharingInventory(pair.get(0)).size(), 2,
                                "both locations share the inventory of " + pair.get(0));
                        RackPosition counting = controller.sharedInventoryOf(pair.get(0)).orElse(null);
                        helper.assertValueEqual(controller.sharedInventoryOf(pair.get(1)).orElse(null), counting,
                                "one location counts it");
                        RackPosition alias = pair.get(0).equals(counting) ? pair.get(1) : pair.get(0);
                        helper.assertTrue(controller.stockIndex().countsAt(alias).isEmpty(), "the other has no counts");
                    }
                    helper.assertValueEqual(controller.stockIndex().totalItems(), (long) (SHARED_DIAMONDS + SHARED_IRON),
                            "total items");
                    // Remove the location that counts the double chest: the other one takes over.
                    RackPosition counting = controller.sharedInventoryOf(RACK_01_03L).orElse(RACK_01_03L);
                    helper.getLevel().destroyBlock(absRack(helper, counting), false);
                })
                .thenWaitUntil(() -> assertAisleReady(helper, 3))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) SHARED_DIAMONDS,
                            "the remaining location counts the double chest");
                    helper.assertValueEqual(controller.stockIndex().totalItems(), (long) (SHARED_DIAMONDS + SHARED_IRON),
                            "nothing lost or doubled");
                })
                .thenSucceed();
    }

    /**
     * Turning the controller from one dock to another rebuilds the stock: an interface at the same aisle-local position
     * in the new aisle reports its own inventory, not the old aisle's counts.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerDockSwitchRebuildsStock(GameTestHelper helper) {
        helper.setBlock(SWITCH_CONTROLLER, controllerState(Direction.EAST));
        helper.setBlock(SWITCH_DOCK_EAST, dockState(Direction.EAST));
        helper.setBlock(SWITCH_DOCK_SOUTH, dockState(Direction.SOUTH));
        storageAt(helper, SWITCH_EAST_LAYOUT, RACK_01_00L, new ItemStack(Items.IRON_INGOT, IRON_AT_01_01L));
        storageAt(helper, SWITCH_SOUTH_LAYOUT, RACK_01_00L, new ItemStack(Items.GOLD_INGOT, GOLD_AT_02_03R));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = switchControllerAt(helper);
                    helper.assertValueEqual(controller.layout().map(BranchLayout::dock),
                            Optional.of(helper.absolutePos(SWITCH_DOCK_EAST)), "linked to the east dock");
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "snapshots taken");
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_AT_01_01L, "east aisle stock");
                })
                // The wrench turns the controller clockwise: from east straight to the south dock.
                .thenExecute(() -> helper.setBlock(SWITCH_CONTROLLER, controllerState(Direction.SOUTH)))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = switchControllerAt(helper);
                    helper.assertValueEqual(controller.layout().map(BranchLayout::dock),
                            Optional.of(helper.absolutePos(SWITCH_DOCK_SOUTH)), "linked to the south dock");
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "snapshots taken");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = switchControllerAt(helper);
                    helper.assertValueEqual(controller.storageLocations().size(), 1, "one storage location");
                    helper.assertValueEqual(controller.countOf(IRON), 0L, "the east aisle's counts are gone");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) GOLD_AT_02_03R, "the south aisle's stock");
                    helper.assertFalse(dockAt(helper, SWITCH_DOCK_EAST).isControllerLinked(), "the east dock lost it");
                    helper.assertTrue(dockAt(helper, SWITCH_DOCK_SOUTH).isControllerLinked(), "the south dock has it");
                })
                .thenSucceed();
    }

    /**
     * The dock's controller link is owner-aware: when the dock turns from one controller to another, the old controller
     * cannot clear the new link, whatever order they tick in; a link whose controller is gone is cleared by the dock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerDockLinkOwner(GameTestHelper helper) {
        // Placed first, so its ticker runs before the old owner's: the old code lost the link in this order.
        helper.setBlock(CONTROLLER, controllerState(AISLE));
        helper.setBlock(SOUTH_OF_DOCK, controllerState(Direction.NORTH));
        helper.setBlock(DOCK, dockState(Direction.NORTH));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(dockAt(helper, DOCK).linkedController(),
                        Optional.of(helper.absolutePos(SOUTH_OF_DOCK)), "linked to the controller south of it"))
                .thenExecute(() -> helper.setBlock(DOCK, dockState(AISLE)))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    StackerCraneBlockEntity dock = dockAt(helper, DOCK);
                    helper.assertValueEqual(controllerAt(helper).status(), ControllerStatus.NO_RAILS, "new owner linked");
                    helper.assertValueEqual(controllerAt(helper, SOUTH_OF_DOCK).status(),
                            ControllerStatus.DOCK_MISALIGNED, "old owner unlinked, and sees a dock facing away");
                    helper.assertTrue(dock.isControllerLinked(), "the old controller did not clear the new link");
                    helper.assertValueEqual(dock.linkedController(), Optional.of(helper.absolutePos(CONTROLLER)), "owner");

                    // A link whose controller is gone (e.g. its chunk unloaded) is cleared by the dock's own check.
                    dock.linkController(helper.absolutePos(NO_CONTROLLER_HERE));
                    dock.validateControllerLink();
                    helper.assertFalse(dock.isControllerLinked(), "a lost controller is noticed");
                    controllerAt(helper).requestRelink();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(dockAt(helper, DOCK).linkedController(),
                        Optional.of(helper.absolutePos(CONTROLLER)), "linked again on the next re-link"))
                .thenSucceed();
    }

    /**
     * Membership follows the world: a broken interface leaves (with its stock), a placed one joins and is snapshotted
     * at once, a rotated one becomes misaligned, the aisle letter changes every address, and a removed inventory
     * empties its location.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerMembershipChanges(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> helper.getLevel().destroyBlock(absRack(helper, RACK_02_03R), false))
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS - 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.locationAt(absRack(helper, RACK_02_03R)).isEmpty(), "removed");
                    helper.assertFalse(controller.stockIndex().contains(RACK_02_03R), "its stock left the index");
                    helper.assertValueEqual(controller.countOf(GOLD), 0L, "gold was only there");
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_AT_01_01L, "iron that remains");
                    storage(helper, RACK_03_02R, new ItemStack(Items.APPLE, APPLES_ADDED));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        controllerAt(helper).locationAt(absRack(helper, RACK_03_02R)).isPresent(), "a placed interface joins"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.countOf(APPLE), (long) APPLES_ADDED,
                            "a joining storage location is snapshotted at once");
                    helper.assertValueEqual(controller.storageLocations().size(), STORAGE_LOCATIONS, "four again");
                    helper.setBlock(relPos(RACK_01_01L), interfaceState(AISLE));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).misalignedCount(), 2,
                        "a rotated interface becomes misaligned"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.storageLocations().size(), STORAGE_LOCATIONS - 1, "one left");
                    helper.assertValueEqual(controller.countOf(IRON), 0L, "its iron left the index");

                    letterBehaviour(helper, controller).setValue(LETTER_C);
                    helper.assertValueEqual(controller.aisleLetter(), 'C', "letter C");
                    helper.assertValueEqual(addressText(helper, controller, RACK_03_02R), Optional.of("C-03-02R"),
                            "addresses follow the letter");
                    helper.assertValueEqual(WarehouseRegistry.registeredLayout(level, helper.absolutePos(CONTROLLER))
                            .map(WarehouseLayout::firstBranch).flatMap(BranchLayout::letter), Optional.of('C'),
                            "the registry has the new letter");
                    assertAssignment(helper, RACK_03_02R, AisleAssignment.assigned(StorageAddress.parse("C-03-02R")));
                    assertAssignment(helper, RACK_01_01L, AisleAssignment.MISALIGNED);

                    helper.setBlock(chestOf(RACK_01_00R), Blocks.AIR);
                    helper.assertTrue(controller.refreshLocation(RACK_01_00R), "refresh without inventory");
                    helper.assertValueEqual(controller.countOf(DIAMOND), 0L, "a removed inventory has no stock");
                    helper.assertTrue(controller.locationAt(absRack(helper, RACK_01_00R)).isPresent(),
                            "an interface without inventory stays a storage location");
                })
                .thenSucceed();
    }

    // --- dock link and status ------------------------------------------------------------------------------------

    /**
     * NO_DOCK without a dock in front, DOCK_MISALIGNED for a dock in front that faces another way (the most likely
     * build mistake, so it gets its own message), NO_RAILS for a dock without rails (rack position 0 still works),
     * READY with rails; breaking the dock clears the aisle.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerDockStatus(GameTestHelper helper) {
        helper.setBlock(DOCK, dockState(AISLE));
        placeRails(helper);
        helper.setBlock(CONTROLLER, controllerState(AISLE.getOpposite()));
        storage(helper, RACK_01_00L, new ItemStack(Items.DIAMOND, DIAMONDS_AT_01_00R));

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    assertNoAisle(helper, "a controller facing away from the dock");
                    helper.setBlock(CONTROLLER, controllerState(AISLE));
                })
                .thenWaitUntil(() -> assertAisleReady(helper, 1))
                .thenExecute(() -> {
                    helper.assertTrue(dockAt(helper).isControllerLinked(), "linked after turning to the dock");
                    helper.assertValueEqual(controllerAt(helper).countOf(DIAMOND), (long) DIAMONDS_AT_01_00R, "stock");
                    helper.setBlock(DOCK, dockState(AISLE.getOpposite()));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).status(),
                        ControllerStatus.DOCK_MISALIGNED,
                        "a dock facing another direction belongs to no controller behind it, and says which it is"))
                .thenExecute(() -> {
                    assertNoAisle(helper, "dock turned around", ControllerStatus.DOCK_MISALIGNED);
                    for (int x = 1; x <= RAILS; x++)
                        helper.setBlock(DOCK.relative(AISLE, x), Blocks.AIR);
                    helper.setBlock(DOCK, dockState(AISLE));
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.status(), ControllerStatus.NO_RAILS, "dock without rails");
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertValueEqual(controller.storageLocations().size(), 1, "position 0 works without rails");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(addressText(helper, controller, RACK_01_00L), Optional.of("A-01-00L"),
                            "address next to a dock without rails");
                    placeRails(helper);
                    dockAt(helper).requestGeometryRefresh();
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.status(), ControllerStatus.READY, "rails again");
                    helper.assertValueEqual(controller.layout().map(layout -> layout.geometry().length()),
                            Optional.of(RAILS), "the controller follows the dock geometry");
                })
                .thenExecute(() -> helper.getLevel().destroyBlock(helper.absolutePos(DOCK), false))
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).status(), ControllerStatus.NO_DOCK,
                        "dock broken"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.locations().isEmpty(), "no aisle, no locations");
                    helper.assertValueEqual(controller.stockIndex().locationCount(), 0, "no aisle, no stock");
                    helper.assertTrue(WarehouseRegistry.findController(helper.getLevel(), absRack(helper, RACK_01_00L))
                            .isEmpty(), "the aisle left the registry");
                })
                .thenSucceed();
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * Round trip into a fresh block entity (saveWithoutMetadata / loadWithComponents): letter, layout, records,
     * misaligned count and stock counts, including an item with components. Malformed tags never throw.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerPersistence(GameTestHelper helper) {
        buildAisle(helper);
        ItemStack named = new ItemStack(Items.IRON_INGOT, NAMED_INGOTS);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks test ingot"));
        ItemKey namedKey = ItemKey.of(named);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    insertAll(helper, handlerAt(helper, chestOf(RACK_04_05L)), named.copy());
                    helper.assertTrue(controller.refreshLocation(RACK_04_05L), "refresh with the named item");
                    letterBehaviour(helper, controller).setValue(1);

                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    helper.assertFalse(saved.contains("GoggleSummary"), "goggle data is not saved");
                    helper.assertFalse(controller.getUpdateTag(registries).contains(ControllerPersistence.LOCATIONS),
                            "records and stock are not synced to clients");

                    WarehouseControllerBlockEntity loaded = freshCopy(helper, controller);
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.aisleLetter(), 'B', "letter");
                    helper.assertValueEqual(loaded.layout(), controller.layout(), "layout");
                    helper.assertValueEqual(loaded.status(), ControllerStatus.READY, "status from the saved layout");
                    helper.assertValueEqual(loaded.locations(), controller.locations(), "records");
                    helper.assertValueEqual(loaded.misalignedCount(), controller.misalignedCount(), "misaligned");
                    helper.assertValueEqual(loaded.countOf(IRON), controller.countOf(IRON), "iron");
                    helper.assertValueEqual(loaded.countOf(namedKey), (long) NAMED_INGOTS, "item with components");
                    helper.assertValueEqual(loaded.stockIndex().totalItems(), controller.stockIndex().totalItems(),
                            "total items");
                    helper.assertValueEqual(loaded.stockIndex().locations(), controller.stockIndex().locations(),
                            "indexed locations");
                    helper.assertTrue(loaded.stockIndex().snapshotOf(RACK_01_01L).isEmpty(),
                            "restored counts carry no slot information");
                    helper.assertValueEqual(loaded.addressOf(absRack(helper, RACK_02_03R)).map(StorageAddress::format),
                            Optional.of("B-02-03R"), "addresses after loading");
                    helper.assertTrue(loaded.isMembershipDirty(), "a loaded list is verified on the first tick");
                    helper.assertValueEqual(loaded.pendingSnapshotCount(), controller.storageLocations().size(),
                            "restored counts are verified by background snapshots");

                    CompoundTag rotated = saved.copy();
                    rotated.getCompound("Layout").putString("Facing", Direction.NORTH.getSerializedName());
                    WarehouseControllerBlockEntity otherFacing = freshCopy(helper, controller);
                    otherFacing.loadWithComponents(rotated, registries);
                    helper.assertTrue(otherFacing.layout().isEmpty(), "a layout saved for another facing is dropped");
                    helper.assertTrue(otherFacing.locations().isEmpty(), "with its records");
                    helper.assertValueEqual(otherFacing.status(), ControllerStatus.NO_DOCK, "status without layout");

                    CompoundTag broken = saved.copy();
                    broken.getCompound("Layout").putInt("Height", -3);
                    ListTag locations = new ListTag();
                    locations.add(locationTag(-1, 0, "L", "STORAGE"));
                    locations.add(locationTag(1, 0, "Q", "STORAGE"));
                    locations.add(locationTag(1, 0, "L", "SHELF"));
                    CompoundTag wrongTypes = new CompoundTag();
                    wrongTypes.putString("X", "one");
                    wrongTypes.putString("Y", "zero");
                    wrongTypes.putString("Side", "L");
                    wrongTypes.putString("Kind", "STORAGE");
                    locations.add(wrongTypes);
                    CompoundTag valid = locationTag(2, 1, "R", "STORAGE");
                    ListTag items = new ListTag();
                    items.add(stockTag(IRON.save(registries), 12));
                    items.add(stockTag(IRON.save(registries), 3));
                    items.add(stockTag(GOLD.save(registries), -4));
                    CompoundTag unknownItem = new CompoundTag();
                    unknownItem.putString("id", Wareworks.ID + ":does_not_exist");
                    items.add(stockTag(unknownItem, 5));
                    items.add(new CompoundTag());
                    valid.put("Stock", items);
                    locations.add(valid);
                    broken.put(ControllerPersistence.LOCATIONS, locations);
                    broken.putString("Misaligned", "nope");
                    WarehouseControllerBlockEntity sanitized = freshCopy(helper, controller);
                    sanitized.loadWithComponents(broken, registries);
                    helper.assertValueEqual(sanitized.locations(),
                            List.of(LocationRecord.of(new RackPosition(2, 1, Side.RIGHT), LocationKind.STORAGE)),
                            "only the valid record survives");
                    helper.assertValueEqual(sanitized.countOf(IRON), 15L, "duplicate stock entries merge");
                    helper.assertValueEqual(sanitized.countOf(GOLD), 0L, "negative counts are dropped");
                    helper.assertValueEqual(sanitized.misalignedCount(), 0, "misaligned with the wrong type");
                    helper.assertValueEqual(sanitized.layout().map(layout -> layout.geometry().height()),
                            Optional.of(AisleGeometry.MIN_HEIGHT), "invalid height clamped");

                    CompoundTag vertical = saved.copy();
                    vertical.getCompound("Layout").putString("Facing", Direction.UP.getSerializedName());
                    WarehouseControllerBlockEntity noLayout = freshCopy(helper, controller);
                    noLayout.loadWithComponents(vertical, registries);
                    helper.assertTrue(noLayout.layout().isEmpty(), "a vertical facing is no layout");
                    WarehouseControllerBlockEntity empty = freshCopy(helper, controller);
                    empty.loadWithComponents(new CompoundTag(), registries);
                    helper.assertTrue(empty.locations().isEmpty(), "an empty tag loads as an empty controller");
                    helper.assertTrue(empty.saveWithoutMetadata(registries).contains(ControllerPersistence.LOCATIONS),
                            "an empty controller saves");
                })
                .thenSucceed();
    }

    // --- naming an aisle (M25, issue #15) ------------------------------------------------------------------------

    /**
     * The naming gesture on a warehouse controller: a right-click with a renamed item names the aisle at its dock, a
     * plain name tag takes the name off again, and the player is told which aisle is called what.
     * <p>
     * What is pinned here, beyond "it works":
     * <ul>
     * <li><b>Nothing else is stolen.</b> A plain item, and a renamed item in the hand of a <i>sneaking</i> player, both
     * pass the interaction on untouched and say nothing — sneaking is how a renamed block is placed against a
     * controller, and a gesture that swallowed it would break placing.</li>
     * <li><b>The item is never consumed</b>, in either direction.</li>
     * <li><b>A name too long for the cap is cut and the cut is said</b>, in the same click, with the stored name in the
     * message, so a player never has to guess what really went in.</li>
     * <li><b>Only the flat string of the custom name is read.</b> A name of nothing but spaces clears instead of
     * storing something invisible, and the player is told it was cleared, not that it is now called "".</li>
     * <li><b>A second clear says nothing</b>: a click that leaves the world as the player asked for it does not report
     * a change.</li>
     * <li><b>A controller without a warehouse refuses with a sentence</b> rather than silently.</li>
     * <li><b>The aisle-letter value box still owns the clicks that hit it</b> with a renamed item in hand — the one
     * thing a {@code bypassesInput} override would have cost.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleNaming(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    NamingClick.Teller player = NamingClick.player(helper);
                    helper.assertTrue(controller.hasNoAisleNames(), "a fresh warehouse has no names");
                    helper.assertTrue(controller.aisleName('A').isEmpty(), "and aisle A has none either");

                    ItemStack plain = new ItemStack(Items.IRON_INGOT, NAMED_INGOTS);
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, plain);
                    helper.assertTrue(controller.hasNoAisleNames(), "an item nobody renamed names nothing");
                    NamingClick.assertTold(helper, player, "a click with a plain item");

                    ItemStack ores = NamingClick.renamed(Items.IRON_INGOT, AISLE_NAME, NAMED_INGOTS);
                    player.setShiftKeyDown(true);
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, ores.copy());
                    player.setShiftKeyDown(false);
                    helper.assertTrue(controller.hasNoAisleNames(), "a sneaking click still places blocks instead");
                    NamingClick.assertTold(helper, player, "a sneaking click");

                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, ores.copy());
                    helper.assertValueEqual(controller.aisleName('A'), Optional.of(AISLE_NAME), "aisle A is named");
                    helper.assertValueEqual(List.copyOf(controller.aisleNames().keySet()), List.of('A'),
                            "and it is the only named aisle");
                    helper.assertValueEqual(controller.aisleNames().get('A'), AISLE_NAME, "the stored name");
                    NamingClick.assertTold(helper, player, "naming aisle A", WareworksLang.AISLE_NAMED);
                    helper.assertValueEqual(player.argsOf(0), List.of("A", AISLE_NAME),
                            "the message names the aisle and the name");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), NAMED_INGOTS,
                            "naming consumes nothing");

                    // Over the cap: stored short, and the player is shown exactly what was stored — in ONE message,
                    // which is the whole point. The client's HUD keeps one action-bar line at a time, so a second
                    // message sent in the same tick would replace the first before anything was drawn and the reader
                    // would never learn which aisle the name went on (M25 review fix). Hence: one key, two arguments.
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE,
                            NamingClick.renamed(Items.IRON_INGOT, OVERLONG_NAME));
                    helper.assertValueEqual(controller.aisleName('A'), Optional.of(CUT_NAME), "cut to the cap");
                    NamingClick.assertTold(helper, player, "an over-long name", WareworksLang.AISLE_NAMED_CUT);
                    helper.assertValueEqual(player.argsOf(0), List.of("A", CUT_NAME),
                            "the one cut message names the aisle as well as the kept text");

                    // Nothing drawable in the name: the same answer a name tag gives, not an invisible name.
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE,
                            NamingClick.renamed(Items.IRON_INGOT, "   "));
                    helper.assertTrue(controller.aisleName('A').isEmpty(), "a name of spaces is no name");
                    NamingClick.assertTold(helper, player, "a blank name", WareworksLang.AISLE_NAME_CLEARED);

                    // The name tag: it clears and it says so — every time, including on an aisle that had no name
                    // (M25 review fix). The click is consumed either way, and a consumed click that answers nothing
                    // is a click nobody finds; the sentence is true in both cases, because the aisle is unnamed after
                    // it.
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, ores.copy());
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, NamingClick.blankNameTag());
                    helper.assertTrue(controller.hasNoAisleNames(), "a plain name tag clears the name");
                    NamingClick.assertTold(helper, player, "a name tag", WareworksLang.AISLE_NAME_CLEARED);
                    helper.assertValueEqual(player.argsOf(0), List.of("A"), "the cleared message names the aisle");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the name tag is not consumed");
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, NamingClick.blankNameTag());
                    NamingClick.assertTold(helper, player, "a name tag on an unnamed aisle",
                            WareworksLang.AISLE_NAME_CLEARED);
                    helper.assertValueEqual(player.argsOf(0), List.of("A"),
                            "and that answer names the aisle too, so no naming click is ever silent");

                    // A renamed name tag names, like any other renamed item: the custom name is what counts.
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE,
                            NamingClick.renamed(Items.NAME_TAG, AISLE_NAME));
                    helper.assertValueEqual(controller.aisleName('A'), Optional.of(AISLE_NAME),
                            "a written name tag names");
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE, NamingClick.blankNameTag());

                    // The value box keeps every click that hits it, with a renamed item in hand. The gesture is "click
                    // the face off the middle", and this is the assertion that says so.
                    helper.assertFalse(letterBehaviour(helper, controller).bypassesInput(ores),
                            "a renamed item must not make the aisle letter box give up its clicks");
                    WarehouseInterfaceBlockEntity itf = interfaceAt(helper, relPos(RACK_01_01L));
                    FilteringBehaviour filter = BlockEntityBehaviour.get(itf, FilteringBehaviour.TYPE);
                    if (filter == null) {
                        helper.fail("the interface has no store filter", relPos(RACK_01_01L));
                        return;
                    }
                    helper.assertFalse(filter.bypassesInput(ores),
                            "nor the interface's filter and priority slot, which is its only usable face");
                    helper.assertTrue(filter.setFilter(NamingClick.renamed(Items.GOLD_INGOT, AISLE_NAME)),
                            "a renamed filter item still goes into the slot");
                })
                .thenSucceed();
    }

    /**
     * Two refusals, on a bare controller where neither of them can disturb anything else.
     * <ul>
     * <li>A naming click on a controller that <b>has no warehouse</b> says so, instead of doing nothing silently — the
     * same sentence an interface that belongs to no aisle answers with.</li>
     * <li>A <b>renamed wrench still turns the block</b>. Create's own wrench acts from {@code WrenchItem#useOn}, which
     * runs only if {@code useItemOn} passed the click on, so a gesture that took every renamed item would have cost
     * rotation on exactly the two blocks it is added to — and a wrench from another mod, which Create handles in an
     * event before the block is asked, would have kept working, so the two would not even have agreed.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleNamingWithoutAWarehouse(GameTestHelper helper) {
        helper.setBlock(CONTROLLER, controllerState(AISLE));

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.warehouse().isEmpty(), "no dock, no warehouse");
                    NamingClick.Teller player = NamingClick.player(helper);
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE,
                            NamingClick.renamed(Items.IRON_INGOT, AISLE_NAME));
                    helper.assertTrue(controller.hasNoAisleNames(), "nothing was named");
                    NamingClick.assertTold(helper, player, "a click on a controller without a warehouse",
                            WareworksLang.AISLE_NAME_NO_AISLE);

                    ItemStack wrench = AllItems.WRENCH.asStack();
                    wrench.set(DataComponents.CUSTOM_NAME, Component.literal(AISLE_NAME));
                    NamingClick.use(helper, player, CONTROLLER, Direction.UP, wrench);
                    helper.assertValueEqual(helper.getBlockState(CONTROLLER).getValue(WarehouseControllerBlock.FACING),
                            AISLE.getClockWise(), "a renamed wrench still turns the controller clockwise");
                    NamingClick.assertTold(helper, player, "a renamed wrench");
                })
                .thenSucceed();
    }

    /**
     * What a name survives. Four separate promises, because each of them is a different way a label could quietly
     * disappear:
     * <ul>
     * <li><b>A save and a reload</b>, in the {@code Names} tag and nowhere else.</li>
     * <li><b>A warehouse nobody named writes no tag at all</b>, so a world from 0.7.0 saves byte for byte what it did
     * before this feature existed — the promise the whole top-level-tag decision rests on.</li>
     * <li><b>The dock being broken and built again.</b> The layout goes, the records go, the stock index goes; the
     * labels stay, because they are something a player wrote rather than something derived from the rails. That is
     * also why they survive a layout saved for another facing, which drops everything else.</li>
     * <li><b>A crafted tag cannot widen anything.</b> Thirty offered entries, bad letters, duplicates, over-long and
     * blank names, and a {@code Names} tag that is not a list at all: each bad entry costs itself and nothing else,
     * and the table can hold no more names than there are aisle letters.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleNamePersistence(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();

                    helper.assertFalse(controller.saveWithoutMetadata(registries).contains(ControllerPersistence.NAMES),
                            "a warehouse nobody named writes no Names tag");

                    NamingClick.Teller player = NamingClick.player(helper);
                    NamingClick.use(helper, player, CONTROLLER, NAMING_FACE,
                            NamingClick.renamed(Items.IRON_INGOT, AISLE_NAME));
                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    ListTag names = saved.getList(ControllerPersistence.NAMES, Tag.TAG_COMPOUND);
                    helper.assertValueEqual(names.size(), 1, "one saved name");
                    helper.assertValueEqual(names.getCompound(0).getString("C"), "A", "the letter it belongs to");
                    helper.assertValueEqual(names.getCompound(0).getString("N"), AISLE_NAME, "the name");
                    helper.assertFalse(saved.getCompound(ControllerPersistence.NETWORK).contains(
                            ControllerPersistence.NAMES), "the names are top level, never inside Network");

                    WarehouseControllerBlockEntity loaded = freshCopy(helper, controller);
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.aisleName('A'), Optional.of(AISLE_NAME), "the name after a reload");
                    helper.assertValueEqual(loaded.saveWithoutMetadata(registries)
                            .getList(ControllerPersistence.NAMES, Tag.TAG_COMPOUND).size(), 1, "and it saves again");

                    // Everything else of this save belongs to one facing and is dropped for another; a label is not.
                    CompoundTag rotated = saved.copy();
                    rotated.getCompound("Layout").putString("Facing", Direction.NORTH.getSerializedName());
                    WarehouseControllerBlockEntity otherFacing = freshCopy(helper, controller);
                    otherFacing.loadWithComponents(rotated, registries);
                    helper.assertTrue(otherFacing.layout().isEmpty(), "the layout is dropped for another facing");
                    helper.assertValueEqual(otherFacing.aisleName('A'), Optional.of(AISLE_NAME),
                            "the name is not: rebuild the dock and the label is back");

                    // A tag that offers more entries than there are aisle letters: it can widen nothing, and what is
                    // offered past the bound is not even read.
                    CompoundTag crafted = saved.copy();
                    ListTag offered = new ListTag();
                    for (int i = 0; i < StorageAddress.AISLE_COUNT; i++)
                        offered.add(nameTag(String.valueOf(StorageAddress.aisleLetter(i)), CRAFTED_NAME_PREFIX + i));
                    for (int i = 0; i < CRAFTED_SURPLUS_ENTRIES; i++)
                        offered.add(nameTag("A", "past the bound"));
                    crafted.put(ControllerPersistence.NAMES, offered);
                    WarehouseControllerBlockEntity sanitized = freshCopy(helper, controller);
                    sanitized.loadWithComponents(crafted, registries);
                    helper.assertValueEqual(sanitized.aisleNames().size(), StorageAddress.AISLE_COUNT,
                            "a crafted tag can hold no more names than there are aisle letters");
                    helper.assertValueEqual(sanitized.aisleName('A'), Optional.of(CRAFTED_NAME_PREFIX + 0),
                            "and nothing offered past the bound was read");
                    helper.assertValueEqual(sanitized.aisleName('Z'),
                            Optional.of(CRAFTED_NAME_PREFIX + (StorageAddress.AISLE_COUNT - 1)), "the last letter");
                    helper.assertValueEqual(sanitized.saveWithoutMetadata(registries)
                            .getList(ControllerPersistence.NAMES, Tag.TAG_COMPOUND).size(),
                            StorageAddress.AISLE_COUNT, "and it saves no more than it read");

                    // Every kind of unusable entry, between good ones: each costs itself and nothing else.
                    CompoundTag broken = saved.copy();
                    ListTag mixed = new ListTag();
                    mixed.add(nameTag("A", AISLE_NAME));
                    mixed.add(nameTag("a", "lower case is no aisle letter"));
                    mixed.add(nameTag("AB", "two letters are no aisle letter"));
                    mixed.add(nameTag("", "no letter at all"));
                    mixed.add(nameTag("B", "   "));
                    mixed.add(nameTag("C", OVERLONG_NAME));
                    mixed.add(new CompoundTag());
                    mixed.add(nameTag("D", GRAVEL));
                    broken.put(ControllerPersistence.NAMES, mixed);
                    WarehouseControllerBlockEntity skipped = freshCopy(helper, controller);
                    skipped.loadWithComponents(broken, registries);
                    helper.assertValueEqual(List.copyOf(skipped.aisleNames().keySet()), List.of('A', 'C', 'D'),
                            "only the readable entries survive, in letter order");
                    helper.assertValueEqual(skipped.aisleName('A'), Optional.of(AISLE_NAME),
                            "a good entry before the bad ones");
                    helper.assertTrue(skipped.aisleName('B').isEmpty(), "a name of nothing drawable is no name");
                    helper.assertValueEqual(skipped.aisleName('C'), Optional.of(CUT_NAME),
                            "an over-long name is cut, not refused");
                    helper.assertValueEqual(skipped.aisleName('D'), Optional.of(GRAVEL),
                            "and a good entry after them all is still read");

                    CompoundTag notAList = saved.copy();
                    notAList.putString(ControllerPersistence.NAMES, "nope");
                    WarehouseControllerBlockEntity wrongType = freshCopy(helper, controller);
                    wrongType.loadWithComponents(notAList, registries);
                    helper.assertTrue(wrongType.hasNoAisleNames(), "a Names tag of the wrong type reads as no names");

                    WarehouseControllerBlockEntity empty = freshCopy(helper, controller);
                    empty.loadWithComponents(new CompoundTag(), registries);
                    helper.assertTrue(empty.hasNoAisleNames(), "an empty tag loads as an unnamed warehouse");
                })
                // The dock really goes, so the controller really loses its layout, its records and its stock index.
                .thenExecute(() -> helper.getLevel().destroyBlock(helper.absolutePos(DOCK), false))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.warehouse().isEmpty(), "the warehouse is gone with its dock");
                    helper.assertTrue(controller.locations().isEmpty(), "and so are its records");
                    helper.assertValueEqual(controller.aisleName('A'), Optional.of(AISLE_NAME),
                            "the label outlives the warehouse it decorates");
                })
                .thenExecute(() -> helper.setBlock(DOCK, dockState(AISLE)))
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> helper.assertValueEqual(controllerAt(helper).aisleName('A'), Optional.of(AISLE_NAME),
                        "and the rebuilt warehouse carries it again"))
                .thenSucceed();
    }

    /**
     * Where a name is <b>shown</b> (M25, issue #15, ADR-038). Four of the five surfaces are checked here; the fifth,
     * the display board's names line, belongs to {@code DisplayLinkGameTests}, and all five are photographed and read
     * off the real tooltip by {@code CombVisualScenario} in both languages.
     * <p>
     * Every line is asserted by its <b>translation key and its arguments</b>, never by its text: the key is what the
     * code chose, while the text is whatever language this server happens to have loaded. The lines themselves cannot
     * be built at all here — {@code LangBuilder#forGoggles} measures {@code Minecraft.getInstance().font} — so what is
     * checked is the message each surface hands to the renderer, which is the part a regression could get wrong.
     * <ul>
     * <li><b>The warehouse's own line</b> gains the dock aisle's name and keeps its letter in front.</li>
     * <li><b>Every member's address</b> gains the name in brackets, and loses it again when the name is cleared — the
     * whole chain from the controller's table through {@code WarehouseRegistry} into a member's synced packet.</li>
     * <li><b>The aisle list</b> of a warehouse that bends shows the name <i>instead of</i> the length, per aisle.</li>
     * <li><b>The teaching hint</b> is shown only while no name is visible, and is gone the moment one is.</li>
     * <li><b>The click publishes what it changed</b>, with no goggle observation in between: a player who names an
     * aisle while looking at the controller sees the line on that click, not on the next look.</li>
     * <li><b>The names' own cost to the chunk packet is bounded</b>, measured at the real worst case: a 16-character
     * name on the dock aisle plus six of them in the network record ({@link #MAX_NAME_SYNC_BYTES}), with the whole
     * tag of a named warehouse inside {@link #MAX_NAMED_SUMMARY_SYNC_BYTES}.</li>
     * <li><b>A world from 0.7.0 syncs byte for byte what it did before</b>: no name, no key, in either carrier.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleNameSurfaces(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();

                    // Before anybody named anything: no name anywhere, no key in the synced tag, and the hint.
                    controller.onGoggleObserved();
                    helper.assertTrue(controller.summary().aisleName().isEmpty(), "an unnamed warehouse has no name");
                    helper.assertTrue(WarehouseControllerBlockEntity.showsNamingHint(controller.summary()),
                            "so the goggles teach the gesture");
                    CompoundTag plainSummary = summaryTag(helper, controller, registries);
                    helper.assertFalse(plainSummary.contains(SUMMARY_AISLE_NAME),
                            "a warehouse nobody named syncs no name key at all");
                    int plainBytes = plainSummary.sizeInBytes();
                    assertLine(helper, WareworksLang.warehouseLetter('A', controller.summary().aisleName()),
                            WareworksLang.GOGGLES_WAREHOUSE_LETTER, List.of("A"), "the unnamed warehouse line");
                    assertAddressLine(helper, RACK_01_01L, WareworksLang.GOGGLES_ADDRESS, List.of("A-01-01L"),
                            "an unnamed aisle's address");

                    // Named: the warehouse line, the member's address and the summary's own field. Deliberately with
                    // NO observation in between - the click itself has to publish the line, or a player who names an
                    // aisle while looking at the controller would not see it until they looked away and back.
                    controller.setAisleName('A', AISLE_NAME);
                    helper.assertValueEqual(controller.summary().aisleName(), Optional.of(AISLE_NAME),
                            "the naming click publishes the name without waiting for the next look");
                    helper.assertFalse(WarehouseControllerBlockEntity.showsNamingHint(controller.summary()),
                            "and the hint is gone, because this tooltip now carries a name");
                    assertLine(helper, WareworksLang.warehouseLetter('A', controller.summary().aisleName()),
                            WareworksLang.GOGGLES_WAREHOUSE_LETTER_NAMED, List.of("A", AISLE_NAME),
                            "the named warehouse line");
                    assertAddressLine(helper, RACK_01_01L, WareworksLang.GOGGLES_ADDRESS_NAMED,
                            List.of("A-01-01L", AISLE_NAME), "a named aisle's address");

                    // Through the client packet, which is how a player ever sees any of it, and inside the gate the
                    // whole update tag of a controller has to hold.
                    CompoundTag namedSummary = summaryTag(helper, controller, registries);
                    helper.assertValueEqual(namedSummary.getString(SUMMARY_AISLE_NAME), AISLE_NAME,
                            "the name rides the update tag");
                    int namedTagBytes = controller.getUpdateTag(registries).sizeInBytes();
                    helper.assertTrue(namedTagBytes < MAX_NAMED_SUMMARY_SYNC_BYTES,
                            "a named warehouse's update tag must stay small, but has " + namedTagBytes + " bytes");
                    WarehouseControllerBlockEntity client = freshCopy(helper, controller);
                    client.handleUpdateTag(controller.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.summary(), controller.summary(), "summary after client sync");
                    helper.assertValueEqual(client.summary().aisleName(), Optional.of(AISLE_NAME),
                            "and the client has the name");

                    // Cleared: both surfaces go back to exactly the lines they showed before, and so does the tag —
                    // again on the click alone.
                    helper.assertTrue(controller.clearAisleName('A'), "the name is taken off again");
                    assertLine(helper, WareworksLang.warehouseLetter('A', controller.summary().aisleName()),
                            WareworksLang.GOGGLES_WAREHOUSE_LETTER, List.of("A"), "the line after clearing");
                    assertAddressLine(helper, RACK_01_01L, WareworksLang.GOGGLES_ADDRESS, List.of("A-01-01L"),
                            "the address after clearing");
                    helper.assertValueEqual(summaryTag(helper, controller, registries).sizeInBytes(), plainBytes,
                            "and the synced tag is the size it was before the name");

                    // The aisle list of a warehouse that bends: the name replaces the length, per aisle. Built from a
                    // record rather than from a second warehouse, because what is being checked is the rule, not the
                    // rails - CombVisualScenario reads this very line off a real comb.
                    AisleNames names = new AisleNames();
                    names.set('A', AISLE_NAME);
                    names.set('C', GRAVEL);
                    NetworkGoggleInfo bending = new NetworkGoggleInfo(LISTED_RAILS, "ABC", List.of(16, 12, 14),
                            NetworkGoggleInfo.names("ABC", names), NetworkStop.END, 0, 0);
                    helper.assertValueEqual(WarehouseControllerBlockEntity.aisleEntries(bending),
                            List.of("A " + AISLE_NAME, "B 12", "C " + GRAVEL),
                            "a named aisle shows its name where an unnamed one shows its length");

                    // What a name COSTS the chunk packet, which is the question the 2048-byte gate asks. Measured as
                    // the difference between the same warehouse with and without names, at the worst case a name can
                    // ever reach: 16 characters on the dock aisle plus six more in the network record.
                    //
                    // A difference and not a crafted total, because the total of a warehouse of six aisles is about
                    // the SIZE OF THE NETWORK RECORD, which M21 put there and which this feature must not be made to
                    // answer for - while the cost of a name is exactly what a later change could make unbounded.
                    AisleNames full = new AisleNames();
                    for (int aisle = 0; aisle < NetworkGoggleInfo.NAMES_LISTED; aisle++)
                        full.set((char) ('A' + aisle), LONGEST_NAME);
                    List<Integer> sixAisles = List.of(16, 16, 16, 16, 16, 16);
                    NetworkGoggleInfo unnamed = new NetworkGoggleInfo(LISTED_RAILS, "ABCDEF", sixAisles, "",
                            NetworkStop.MAX_RAILS, 7, 9);
                    NetworkGoggleInfo widest = new NetworkGoggleInfo(LISTED_RAILS, "ABCDEF", sixAisles,
                            NetworkGoggleInfo.names("ABCDEF", full), NetworkStop.MAX_RAILS, 7, 9);
                    helper.assertValueEqual(widest.nameOf(NetworkGoggleInfo.NAMES_LISTED - 1),
                            Optional.of(LONGEST_NAME), "all six names are carried");
                    CompoundTag networkTag = new CompoundTag();
                    widest.write(networkTag);
                    helper.assertValueEqual(NetworkGoggleInfo.read(networkTag), widest, "the names survive the tag");
                    CompoundTag withoutNames = new CompoundTag();
                    unnamed.write(withoutNames);
                    helper.assertFalse(withoutNames.contains(NETWORK_NAMES),
                            "a warehouse nobody named writes no names into the network record either");

                    CompoundTag bare = plainSummary.copy();
                    bare.put(SUMMARY_NETWORK, withoutNames);
                    CompoundTag named = plainSummary.copy();
                    named.putString(SUMMARY_AISLE_NAME, LONGEST_NAME);
                    named.put(SUMMARY_NETWORK, networkTag);
                    int cost = named.sizeInBytes() - bare.sizeInBytes();
                    helper.assertTrue(cost <= MAX_NAME_SYNC_BYTES,
                            "the seven widest names a warehouse can sync must cost at most " + MAX_NAME_SYNC_BYTES
                                    + " bytes, but cost " + cost + " (" + bare.sizeInBytes() + " -> "
                                    + named.sizeInBytes() + "; " + plainBytes + " without the network record)");
                    Wareworks.LOGGER.debug("Controller summary: {} bytes unnamed, {} with a six-aisle network record, "
                            + "{} with that record and all seven names", plainBytes, bare.sizeInBytes(),
                            named.sizeInBytes());
                    ControllerGoggleSummary read = ControllerGoggleSummary.read(named);
                    helper.assertValueEqual(read.aisleName(), Optional.of(LONGEST_NAME), "the dock aisle's name reads back");
                    helper.assertValueEqual(read.network().orElseThrow(), widest, "and so do the other six");
                })
                .thenSucceed();
    }

    /** The synced goggle summary of a controller, as a client receives it. */
    private static CompoundTag summaryTag(GameTestHelper helper, WarehouseControllerBlockEntity controller,
                                          HolderLookup.Provider registries) {
        CompoundTag tag = controller.getUpdateTag(registries).getCompound(SUMMARY_TAG);
        if (tag.isEmpty())
            helper.fail("the controller's update tag carries no goggle summary", CONTROLLER);
        return tag;
    }

    /** Fails unless {@code line} is exactly this translation key with exactly these arguments, as plain strings. */
    private static void assertLine(GameTestHelper helper, LangBuilder line, String relativeKey, List<String> args,
                                   String what) {
        Component component = line.component();
        if (!(component.getContents() instanceof TranslatableContents translatable)) {
            helper.fail(what + " is no translatable text: " + component.getString());
            return;
        }
        helper.assertValueEqual(translatable.getKey(), "wareworks." + relativeKey, "key of " + what);
        List<String> shown = new ArrayList<>();
        for (Object arg : translatable.getArgs())
            shown.add(arg instanceof Component nested ? nested.getString() : String.valueOf(arg));
        helper.assertValueEqual(shown, args, "arguments of " + what);
    }

    /**
     * The address line a member's own goggles would draw, taken from its synced assignment — and that the member's
     * whole client packet is still inside the gate a rack wall of them has to hold
     * ({@link WareworksGameTests#MAX_INTERFACE_SYNC_BYTES}), with the name in it. The same assignment is nested in
     * every other member's summary (station, port, terminal, stock keeper, home point), and an interface is the one
     * there are dozens of in one chunk.
     */
    private static void assertAddressLine(GameTestHelper helper, RackPosition rack, String relativeKey,
                                          List<String> args, String what) {
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, relPos(rack));
        be.onGoggleObserved();
        AisleAssignment assignment = be.aisleAssignment();
        assertLine(helper, WareworksLang.address(assignment.address().map(StorageAddress::format).orElse(""),
                assignment.aisleName()), relativeKey, args, what);
        int bytes = be.getUpdateTag(helper.getLevel().registryAccess()).sizeInBytes();
        helper.assertTrue(bytes < WareworksGameTests.MAX_INTERFACE_SYNC_BYTES,
                "the member's packet behind " + what + " must stay small, but has " + bytes + " bytes");
    }

    // --- block, removal, goggles ---------------------------------------------------------------------------------

    /**
     * Registration, placement and wrench rotation; breaking the controller drops it, unregisters the aisle and unlinks
     * the dock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerRegistrationAndRemoval(GameTestHelper helper) {
        BlockState state = controllerState(Direction.NORTH);
        helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE), "mineable with a pickaxe");
        helper.assertTrue(state.is(WareworksTags.NON_MOVABLE), "create:non_movable");
        helper.assertTrue(state.is(WareworksTags.RELOCATION_NOT_SUPPORTED), "c:relocation_not_supported");
        helper.assertTrue(WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.get().isValid(state), "block entity type");
        WarehouseControllerBlock block = WareworksBlocks.WAREHOUSE_CONTROLLER.get();
        helper.assertValueEqual(block.getRotatedBlockState(state, Direction.UP).getValue(WarehouseControllerBlock.FACING),
                Direction.EAST, "the wrench rotates clockwise");

        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos floor = helper.absolutePos(new BlockPos(RAILS + 2, FLOOR_Y, 3));
        for (float yaw : PLAYER_YAWS) {
            player.setYRot(yaw);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
            BlockState placed = block.getStateForPlacement(new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                    WareworksBlocks.WAREHOUSE_CONTROLLER.asStack(), hit));
            helper.assertValueEqual(placed.getValue(WarehouseControllerBlock.FACING), Direction.fromYRot(yaw),
                    "faces the look direction for yaw " + yaw);
        }

        helper.setBlock(DOCK, dockState(AISLE));
        placeRails(helper);
        helper.setBlock(CONTROLLER, controllerState(AISLE));
        storage(helper, RACK_01_01L, new ItemStack(Items.IRON_INGOT, IRON_AT_01_01L));

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(dockAt(helper).isControllerLinked(), "linked before breaking");
                    helper.getLevel().destroyBlock(helper.absolutePos(CONTROLLER), true);
                    helper.assertBlockPresent(Blocks.AIR, CONTROLLER);
                    helper.assertTrue(controller.isRemoved(), "old block entity removed");
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_CONTROLLER.asItem(), CONTROLLER, 1.0);
                    helper.assertTrue(WarehouseRegistry.registeredLayout(helper.getLevel(),
                            helper.absolutePos(CONTROLLER)).isEmpty(), "unregistered");
                    helper.assertTrue(WarehouseRegistry.findController(helper.getLevel(), absRack(helper, RACK_01_01L))
                            .isEmpty(), "no controller for the member");
                    helper.assertValueEqual(WarehouseRegistry.memberChanged(helper.getLevel(),
                            absRack(helper, RACK_01_01L)), 0, "nobody to notify");
                    helper.assertFalse(dockAt(helper).isControllerLinked(), "the dock lost its controller");
                    assertAssignment(helper, RACK_01_01L, AisleAssignment.NONE);
                })
                .thenSucceed();
    }

    /**
     * The goggle summary holds counts only, syncs by value and stays small; the aisle letter value box formats letters
     * and clamps; malformed summary and assignment tags read safely.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void controllerGoggleSummary(GameTestHelper helper) {
        buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertAisleReady(helper, STORAGE_LOCATIONS))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    helper.assertValueEqual(controller.summary(), ControllerGoggleSummary.NONE,
                            "nothing computed without an observer");
                    controller.onGoggleObserved();
                    // The two zeros after the storage count are the filtered and the prioritised locations (M8, M16);
                    // then the inputs, the outputs, the accepting ports (M17) and the production stations; the four
                    // trailing ones are the stock rule counts of an aisle without keepers: governing, below minimum, at
                    // maximum and paused (M15).
                    ControllerGoggleSummary expected = ControllerGoggleSummary.counts(ControllerStatus.READY, RAILS,
                            defaultMastHeight(), STORAGE_LOCATIONS, 0, 0, 0, 0, 0, 0, 0, 1, ITEM_TYPES, TOTAL_ITEMS, 0,
                            0, 0, 0, 0, 0);
                    // The crane part depends on the (running) crane; the counts are compared here.
                    helper.assertValueEqual(controller.summary().withoutCrane(), expected, "summary after observation");

                    CompoundTag updateTag = controller.getUpdateTag(registries);
                    helper.assertTrue(updateTag.sizeInBytes() < MAX_SUMMARY_SYNC_BYTES,
                            "update tag must stay small, but has " + updateTag.sizeInBytes() + " bytes");
                    WarehouseControllerBlockEntity client = freshCopy(helper, controller);
                    client.handleUpdateTag(updateTag, registries);
                    helper.assertValueEqual(client.summary(), controller.summary(), "summary after client sync");
                    helper.assertTrue(client.summary().crane().isPresent(), "crane data after client sync");
                    helper.assertValueEqual(client.aisleLetter(), 'A', "letter after client sync");

                    helper.assertValueEqual(ControllerGoggleSummary.read(new CompoundTag()), ControllerGoggleSummary.NONE,
                            "empty summary tag");
                    CompoundTag malformed = new CompoundTag();
                    malformed.putString("Status", "EXPLODED");
                    malformed.putInt("Storage", -5);
                    malformed.putString("TotalItems", "many");
                    helper.assertValueEqual(ControllerGoggleSummary.read(malformed), ControllerGoggleSummary.NONE,
                            "unknown status, negative and wrongly typed values");
                    CompoundTag badAddress = new CompoundTag();
                    badAddress.putString("State", "ASSIGNED");
                    badAddress.putString("Address", "a-3-7r");
                    helper.assertValueEqual(AisleAssignment.read(badAddress), AisleAssignment.NONE, "invalid address");
                    helper.assertValueEqual(AisleAssignment.read(new CompoundTag()), AisleAssignment.NONE, "empty tag");

                    helper.assertValueEqual(AisleLetterBehaviour.format(0), "A", "first letter");
                    helper.assertValueEqual(AisleLetterBehaviour.format(AisleLetterBehaviour.LAST_INDEX), "Z",
                            "last letter");
                    helper.assertValueEqual(AisleLetterBehaviour.format(-1), "A", "clamped below");
                    helper.assertValueEqual(AisleLetterBehaviour.format(OVERSIZED_LETTER), "Z", "clamped above");
                    ScrollValueBehaviour letter = letterBehaviour(helper, controller);
                    helper.assertValueEqual(letter.getClipboardKey(), AisleLetterBehaviour.CLIPBOARD_KEY, "clipboard");
                    ValueSettingsBoard board = letter.createBoard(null, null);
                    helper.assertValueEqual(board.maxValue(), AisleLetterBehaviour.LAST_INDEX, "board range");
                    helper.assertValueEqual(board.formatter().format(new ValueSettings(0, 3)).getString(), "D",
                            "board shows letters");
                    letter.setValue(OVERSIZED_LETTER);
                    helper.assertValueEqual(letter.getValue(), AisleLetterBehaviour.LAST_INDEX, "value clamped");
                    helper.assertValueEqual(letter.formatValue(), "Z", "value box text");
                    helper.assertValueEqual(addressText(helper, controller, RACK_02_03R), Optional.of("Z-02-03R"),
                            "address with the last letter");
                })
                .thenSucceed();
    }

    // --- full layout with stations -------------------------------------------------------------------------------

    /**
     * The complete M2 aisle: dock with motor and rails, controller, storage interfaces, one input and one output station.
     * Checks geometry, member kinds, addresses of every kind, stock totals (station buffers are not stock) and the
     * controller goggle summary.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleFullLayout(GameTestHelper helper) {
        buildAisle(helper);
        helper.setBlock(relPos(OUTPUT_01_01R), outputState(towardsAisle(OUTPUT_01_01R)));
        helper.setBlock(relPos(INPUT_01_02R), inputState(towardsAisle(INPUT_01_02R)));
        // Buffered items are not in storage yet (input) or not any more (output).
        insertAll(helper, handlerAt(helper, relPos(INPUT_01_02R)), new ItemStack(Items.COBBLESTONE, COBBLE_IN_INPUT));
        ItemStack outputRest = outputStationAt(helper, relPos(OUTPUT_01_01R))
                .insert(new ItemStack(Items.GOLD_INGOT, GOLD_IN_OUTPUT), false);
        helper.assertTrue(outputRest.isEmpty(), "output buffer rejected " + outputRest);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertAisleReady(helper, STORAGE_LOCATIONS);
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.inputStations().size(), 1, "input stations");
                    helper.assertValueEqual(controller.outputStations().size(), 1, "output stations");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    BranchLayout layout = controller.layout().orElse(null);
                    if (layout == null) {
                        helper.fail("a READY controller must have a layout");
                        return;
                    }
                    AisleGeometry geometry = AisleGeometry.of(RAILS, defaultMastHeight());
                    helper.assertValueEqual(layout.dock(), helper.absolutePos(DOCK), "layout dock");
                    helper.assertValueEqual(layout.facing(), AISLE, "layout facing");
                    helper.assertValueEqual(layout.geometry(), geometry, "geometry");

                    helper.assertValueEqual(positions(controller.storageLocations()),
                            List.of(RACK_01_00R, RACK_01_01L, RACK_02_03R, RACK_04_05L), "storage locations");
                    helper.assertValueEqual(positions(controller.inputStations()), List.of(INPUT_01_02R), "input");
                    helper.assertValueEqual(positions(controller.outputStations()), List.of(OUTPUT_01_01R), "output");
                    helper.assertValueEqual(controller.locations().size(), STORAGE_LOCATIONS + 2, "all members");
                    helper.assertValueEqual(controller.misalignedCount(), 1, "one misaligned interface");
                    helper.assertValueEqual(controller.locationAt(absRack(helper, INPUT_01_02R)),
                            Optional.of(LocationRecord.of(INPUT_01_02R, LocationKind.INPUT)), "input record");
                    helper.assertValueEqual(controller.locationAt(absRack(helper, OUTPUT_01_01R)),
                            Optional.of(LocationRecord.of(OUTPUT_01_01R, LocationKind.OUTPUT)), "output record");

                    helper.assertValueEqual(addressText(helper, controller, RACK_01_00R), Optional.of("A-01-00R"), "storage");
                    helper.assertValueEqual(addressText(helper, controller, RACK_01_01L), Optional.of("A-01-01L"), "storage");
                    helper.assertValueEqual(addressText(helper, controller, RACK_02_03R), Optional.of("A-02-03R"), "storage");
                    helper.assertValueEqual(addressText(helper, controller, RACK_04_05L), Optional.of("A-04-05L"), "storage");
                    helper.assertValueEqual(addressText(helper, controller, OUTPUT_01_01R), Optional.of("A-01-01R"), "output");
                    helper.assertValueEqual(addressText(helper, controller, INPUT_01_02R), Optional.of("A-01-02R"), "input");
                    assertAssignment(helper, RACK_02_03R, AisleAssignment.assigned(StorageAddress.parse("A-02-03R")));
                    assertStationAssignment(helper, OUTPUT_01_01R, AisleAssignment.assigned(StorageAddress.parse("A-01-01R")));
                    assertStationAssignment(helper, INPUT_01_02R, AisleAssignment.assigned(StorageAddress.parse("A-01-02R")));

                    helper.assertValueEqual(controller.countOf(IRON), (long) (IRON_AT_01_01L + IRON_AT_02_03R), "iron");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) GOLD_AT_02_03R, "gold in the output is no stock");
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) DIAMONDS_AT_01_00R, "diamonds");
                    helper.assertValueEqual(controller.countOf(ItemKey.of(Items.COBBLESTONE)), 0L,
                            "cobblestone in the input is no stock");
                    helper.assertValueEqual(controller.countOf(EMERALD), 0L, "an inventory outside the aisle");
                    StockView<ItemKey, RackPosition> stock = controller.stockIndex();
                    helper.assertValueEqual(stock.distinctKeys(), ITEM_TYPES, "item types");
                    helper.assertValueEqual(stock.totalItems(), TOTAL_ITEMS, "total items");
                    helper.assertValueEqual(stock.locations(),
                            List.of(RACK_01_00R, RACK_01_01L, RACK_02_03R, RACK_04_05L), "stations are not indexed");

                    controller.onGoggleObserved();
                    // The motor below the dock runs at Create's default speed, so the crane may already work: counts only.
                    helper.assertValueEqual(controller.summary().withoutCrane(),
                            ControllerGoggleSummary.counts(ControllerStatus.READY, geometry.length(), geometry.height(),
                                    STORAGE_LOCATIONS, 0, 0, 1, 1, 0, 0, 0, 1, ITEM_TYPES, TOTAL_ITEMS, 0, 0, 0, 0, 0,
                                    0),
                            "controller goggle summary");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static BlockState inputState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_INPUT.getDefaultState().setValue(WarehouseInputBlock.FACING, facing);
    }

    private static BlockState outputState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState().setValue(WarehouseOutputBlock.FACING, facing);
    }

    /** The facing of an aligned station at {@code rack}: its opening towards the aisle. */
    private static Direction towardsAisle(RackPosition rack) {
        return RELATIVE.sideDirection(rack.side()).getOpposite();
    }

    private static WarehouseOutputBlockEntity outputStationAt(GameTestHelper helper, BlockPos pos) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse output block entity", pos);
        return be;
    }

    private static void assertStationAssignment(GameTestHelper helper, RackPosition rack, AisleAssignment expected) {
        if (!(helper.getLevel().getBlockEntity(absRack(helper, rack)) instanceof WarehouseStationBlockEntity station)) {
            helper.fail("missing warehouse station block entity", relPos(rack));
            return;
        }
        station.onGoggleObserved();
        helper.assertValueEqual(station.summary().assignment(), expected, "station assignment at " + rack);
    }

    /** Dock with motor below, rails, controller, four aligned storage locations, one misaligned, one outside. */
    private static void buildAisle(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(DOCK, dockState(AISLE));
        placeRails(helper);
        helper.setBlock(CONTROLLER, controllerState(AISLE));
        storage(helper, RACK_01_01L, new ItemStack(Items.IRON_INGOT, IRON_AT_01_01L));
        storage(helper, RACK_02_03R, new ItemStack(Items.GOLD_INGOT, GOLD_AT_02_03R),
                new ItemStack(Items.IRON_INGOT, IRON_AT_02_03R));
        storage(helper, RACK_01_00R, new ItemStack(Items.DIAMOND, DIAMONDS_AT_01_00R));
        storage(helper, RACK_04_05L);
        storage(helper, RACK_OUTSIDE, new ItemStack(Items.EMERALD, EMERALDS_OUTSIDE));
        // Faces along the aisle instead of away from it.
        helper.setBlock(relPos(RACK_MISALIGNED), interfaceState(AISLE));
    }

    private static void placeRails(GameTestHelper helper) {
        for (int x = 1; x <= RAILS; x++)
            helper.setBlock(DOCK.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
    }

    /** A chest with the given contents behind an aligned interface at {@code rack}. */
    private static void storage(GameTestHelper helper, RackPosition rack, ItemStack... contents) {
        BlockPos chest = chestOf(rack);
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = handlerAt(helper, chest);
        for (ItemStack stack : contents)
            insertAll(helper, handler, stack);
        helper.setBlock(relPos(rack), interfaceState(RELATIVE.sideDirection(rack.side())));
    }

    private static void assertAisleReady(GameTestHelper helper, int storageLocations) {
        WarehouseControllerBlockEntity controller = controllerAt(helper);
        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller status");
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.storageLocations().size(), storageLocations, "storage locations");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "joining locations snapshotted");
    }

    /** A chest with the given contents behind an aligned interface at {@code rack} of {@code layout}. */
    private static void storageAt(GameTestHelper helper, BranchLayout layout, RackPosition rack, ItemStack... contents) {
        BlockPos interfacePos = layout.rackPos(rack);
        BlockPos chest = interfacePos.relative(layout.sideDirection(rack.side()));
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = handlerAt(helper, chest);
        for (ItemStack stack : contents)
            insertAll(helper, handler, stack);
        helper.setBlock(interfacePos, interfaceState(layout.sideDirection(rack.side())));
    }

    /** Create's inventory identifier of the inventory behind {@code rack}, as the interface there sees it. */
    private static Optional<InventoryIdentifier> inventoryId(GameTestHelper helper, RackPosition rack) {
        Direction towardsInventory = RELATIVE.sideDirection(rack.side());
        return Optional.ofNullable(InventoryIdentifier.get(helper.getLevel(),
                new BlockFace(helper.absolutePos(chestOf(rack)), towardsInventory.getOpposite())));
    }

    private static ChestBlockEntity chestAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest)) {
            helper.fail("missing chest", pos);
            return null;
        }
        return chest;
    }

    private static void assertNoAisle(GameTestHelper helper, String situation) {
        assertNoAisle(helper, situation, ControllerStatus.NO_DOCK);
    }

    /** No aisle at all; {@code expected} tells "no dock in front" and "a dock facing another way" apart. */
    private static void assertNoAisle(GameTestHelper helper, String situation, ControllerStatus expected) {
        WarehouseControllerBlockEntity controller = controllerAt(helper);
        helper.assertValueEqual(controller.status(), expected, "status: " + situation);
        helper.assertTrue(controller.layout().isEmpty(), "no layout: " + situation);
        helper.assertTrue(controller.locations().isEmpty(), "no locations: " + situation);
        helper.assertTrue(WarehouseRegistry.registeredLayout(helper.getLevel(), helper.absolutePos(CONTROLLER)).isEmpty(),
                "not registered: " + situation);
        helper.assertFalse(dockAt(helper).isControllerLinked(), "dock not linked: " + situation);
    }

    private static void assertAssignment(GameTestHelper helper, RackPosition rack, AisleAssignment expected) {
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, relPos(rack));
        be.onGoggleObserved();
        helper.assertValueEqual(be.aisleAssignment(), expected, "assignment at " + rack);
    }

    private static Optional<String> addressText(GameTestHelper helper, WarehouseControllerBlockEntity controller,
                                                RackPosition rack) {
        return controller.addressOf(absRack(helper, rack)).map(StorageAddress::format);
    }

    private static List<RackPosition> positions(List<LocationRecord> records) {
        return records.stream().map(LocationRecord::position).toList();
    }

    private static BlockPos relPos(RackPosition rack) {
        return RELATIVE.rackPos(rack);
    }

    private static BlockPos absRack(GameTestHelper helper, RackPosition rack) {
        return helper.absolutePos(relPos(rack));
    }

    private static BlockPos chestOf(RackPosition rack) {
        return relPos(rack).relative(RELATIVE.sideDirection(rack.side()));
    }

    private static int defaultMastHeight() {
        return Math.min(StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT, WareworksConfig.maxMastHeight());
    }

    private static BlockState dockState(Direction facing) {
        return WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing);
    }

    private static BlockState controllerState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, facing);
    }

    private static BlockState interfaceState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState().setValue(WarehouseInterfaceBlock.FACING, facing);
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper) {
        return controllerAt(helper, CONTROLLER);
    }

    private static WarehouseControllerBlockEntity switchControllerAt(GameTestHelper helper) {
        return controllerAt(helper, SWITCH_CONTROLLER);
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse controller block entity", pos);
        return be;
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper) {
        return dockAt(helper, DOCK);
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper, BlockPos pos) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing stacker crane block entity", pos);
        return be;
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(GameTestHelper helper, BlockPos pos) {
        WarehouseInterfaceBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse interface block entity", pos);
        return be;
    }

    private static ScrollValueBehaviour letterBehaviour(GameTestHelper helper, WarehouseControllerBlockEntity controller) {
        ScrollValueBehaviour behaviour = BlockEntityBehaviour.get(controller, ScrollValueBehaviour.TYPE);
        if (behaviour == null)
            helper.fail("warehouse controller has no aisle letter value box");
        return behaviour;
    }

    /** A level-less block entity of the same type, standing in for a freshly loaded or client-side instance. */
    private static WarehouseControllerBlockEntity freshCopy(GameTestHelper helper,
                                                           WarehouseControllerBlockEntity controller) {
        WarehouseControllerBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .create(controller.getBlockPos(), controller.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse controller");
        return copy;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    private static void insertAll(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
        helper.assertTrue(rest.isEmpty(), "inventory rejected " + rest);
    }

    private static CompoundTag locationTag(int x, int y, String side, String kind) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putString("Side", side);
        tag.putString("Kind", kind);
        return tag;
    }

    private static CompoundTag stockTag(Tag item, long count) {
        CompoundTag tag = new CompoundTag();
        tag.put("Item", item);
        tag.putLong("Count", count);
        return tag;
    }

    /** One entry of the {@code Names} tag, as {@code ControllerPersistence} writes it. */
    private static CompoundTag nameTag(String letter, String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("C", letter);
        tag.putString("N", name);
        return tag;
    }

    /** Tag names of the controller save format ({@code ControllerPersistence} is package-private). */
    private static final class ControllerPersistence {
        static final String LOCATIONS = "Locations";
        static final String NETWORK = "Network";
        static final String NAMES = "Names";

        private ControllerPersistence() {
        }
    }
}
