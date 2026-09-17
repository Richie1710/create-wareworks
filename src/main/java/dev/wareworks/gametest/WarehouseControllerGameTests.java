package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.AisleLetterBehaviour;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
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
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksTags;
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
    private static final AisleLayout RELATIVE = AisleLayout.of(DOCK, AISLE, AisleGeometry.of(RAILS, 1));

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
    private static final AisleLayout SWITCH_EAST_LAYOUT = AisleLayout.of(SWITCH_DOCK_EAST, Direction.EAST,
            AisleGeometry.of(0, 1));
    private static final AisleLayout SWITCH_SOUTH_LAYOUT = AisleLayout.of(SWITCH_DOCK_SOUTH, Direction.SOUTH,
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
    private static final float[] PLAYER_YAWS = {0f, 90f, 180f, 270f};

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
                    AisleLayout layout = controller.layout().orElse(null);
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

                    helper.assertValueEqual(WarehouseRegistry.registeredLayout(level, helper.absolutePos(CONTROLLER)),
                            Optional.of(layout), "registered layout");
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
                    helper.assertValueEqual(controller.layout().map(AisleLayout::dock),
                            Optional.of(helper.absolutePos(SWITCH_DOCK_EAST)), "linked to the east dock");
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "snapshots taken");
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_AT_01_01L, "east aisle stock");
                })
                // The wrench turns the controller clockwise: from east straight to the south dock.
                .thenExecute(() -> helper.setBlock(SWITCH_CONTROLLER, controllerState(Direction.SOUTH)))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = switchControllerAt(helper);
                    helper.assertValueEqual(controller.layout().map(AisleLayout::dock),
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
                            .flatMap(AisleLayout::letter), Optional.of('C'), "the registry has the new letter");
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
                    ControllerGoggleSummary expected = ControllerGoggleSummary.counts(ControllerStatus.READY, RAILS,
                            defaultMastHeight(), STORAGE_LOCATIONS, 0, 0, 0, 0, 1, ITEM_TYPES, TOTAL_ITEMS, 0, 0);
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
                    AisleLayout layout = controller.layout().orElse(null);
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
                                    STORAGE_LOCATIONS, 0, 1, 1, 0, 1, ITEM_TYPES, TOTAL_ITEMS, 0, 0),
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
    private static void storageAt(GameTestHelper helper, AisleLayout layout, RackPosition rack, ItemStack... contents) {
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

    /** Tag names of the controller save format ({@code ControllerPersistence} is package-private). */
    private static final class ControllerPersistence {
        static final String LOCATIONS = "Locations";

        private ControllerPersistence() {
        }
    }
}
