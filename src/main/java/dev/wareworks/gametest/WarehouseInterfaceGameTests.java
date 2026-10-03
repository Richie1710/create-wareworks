package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;
import static dev.wareworks.gametest.WareworksGameTests.MAX_INTERFACE_SYNC_BYTES;
import static dev.wareworks.gametest.WareworksGameTests.MAX_RESERVED_INTERFACE_SYNC_BYTES;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemHandlerSnapshots;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.content.storage.AttachedInventorySummary;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.InventorySummary;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.WareworksLang;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the warehouse interface: capability access, snapshots, invalidation, rotation, placement, removal, the
 * goggle observation path and the goggle summary sync. Layouts are built on the {@code empty_7x5x7} floor (see
 * {@link WareworksGameTests}).
 * <p>
 * The interface has no ticker: its goggle summary is re-read only when a player observes it through goggles. Tests
 * trigger that directly with {@link WarehouseInterfaceBlockEntity#onGoggleObserved()} or through
 * {@link GoggleObservers#notifyObserved} with a mock player.
 * <p>
 * Assertions inside sequences only use {@code helper.fail/assert*} (which throw {@code GameTestAssertException}); any
 * other exception there would crash the whole test server.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseInterfaceGameTests {
    private static final BlockPos CENTER = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos NORTH_OF_CENTER = CENTER.north();
    private static final int CHEST_SLOTS = 27;
    private static final int IRON = 100;
    private static final int GOLD = 20;
    private static final int GOLD_ADDED = 30;
    private static final int DIAMONDS = 5;
    private static final int COBBLESTONE = 64;
    private static final int APPLES = 3;
    private static final int VAULT_IRON = 200;
    private static final int VAULT_IRON_ADDED = 50;
    private static final int EMERALDS = 7;
    private static final int EMERALDS_ADDED_SILENTLY = 2;
    private static final float YAW_WEST = 90f;
    private static final float YAW_SOUTH = 0f;
    /** Long enough for Create's item vault to finish its connectivity setup (which invalidates capabilities). */
    private static final int SETTLE_TICKS = 25;
    private static final int SETTLED_TEST_TIMEOUT_TICKS = 200;
    /** Distance of the mock observer from the interface, well inside the default interaction range. */
    private static final int OBSERVER_DISTANCE = 2;
    /** Depth below the eyes of the point an observer looks at when it looks down at the floor. */
    private static final double LOOK_DOWN_DEPTH = 3;
    /** Heavy item data for the bounded-sync test: full-length pages per book, books per shulker box. */
    private static final int HEAVY_BOOK_PAGES = 20;
    private static final int HEAVY_BOOKS = 27;
    private static final int LONG_NAME_LENGTH = 30_000;
    private static final int DAMAGED_PICKAXES = 3;
    /** The widest address a member can ever have: the last aisle letter at the last level and position. */
    private static final String WIDEST_ADDRESS = "Z-999-999R";
    /** Exactly {@code AisleName.MAX_LENGTH} characters, for the widest assignment this packet has to fit (M25). */
    private static final String LONGEST_AISLE_NAME = "ABCDEFGHIJKLMNOP";

    // --- the bending warehouse of the aisle-naming test (M25, issue #15) ---
    /** Test-relative z of aisle A, which runs east from the dock; the same L {@code CraneCornerGameTests} builds. */
    private static final int CORNER_AISLE_Z = 3;
    private static final int CORNER_FIRST_RAILS = 4;
    private static final int CORNER_SECOND_RAILS = 3;
    private static final BlockPos CORNER_CONTROLLER = new BlockPos(0, BASE_Y, CORNER_AISLE_Z);
    private static final BlockPos CORNER_DOCK = new BlockPos(1, BASE_Y, CORNER_AISLE_Z);
    private static final BlockPos CORNER_MOTOR = new BlockPos(1, FLOOR_Y, CORNER_AISLE_Z);
    /** The corner block: the last rail of aisle A and the first of aisle B. */
    private static final BlockPos CORNER = new BlockPos(1 + CORNER_FIRST_RAILS, BASE_Y, CORNER_AISLE_Z);
    /** A rack on aisle A only: its left rack plane at {@code z = 2}, well away from aisle B's own planes. */
    private static final BlockPos RACK_ON_A = new BlockPos(4, BASE_Y, 2);
    private static final BlockPos CHEST_ON_A = new BlockPos(4, BASE_Y, 1);
    /** A rack on aisle B only: its rack plane at {@code x = 4}, at a {@code z} aisle A does not reach. */
    private static final BlockPos RACK_ON_B = new BlockPos(4, BASE_Y, 6);
    private static final BlockPos CHEST_ON_B = new BlockPos(3, BASE_Y, 6);
    /** Value-box index of letter B, the letter the second aisle already holds. */
    private static final int LETTER_B = 1;
    private static final String ORES = "Ores";
    private static final String METALS = "Metals";
    /** Long enough for the warehouse to be found, re-lettered on a scroll and re-linked afterwards. */
    private static final int CORNER_TIMEOUT_TICKS = 600;
    /**
     * What the update tag of a <b>real bending warehouse with named aisles</b> may weigh (M25 review fix).
     * <p>
     * It is the same 4096 the controller's own tests use for a named summary, and for the same reason: the tight
     * 2048-byte number was spent on the <b>network record</b> M21 put in that tag, long before a name was in it, and
     * a six-aisle record on its own already takes the accounting past it. {@code CompoundTag#sizeInBytes} is a
     * Java-heap estimate many times the real wire size, so this is a canary against a record that grows without
     * bound, not a packet budget. The size is logged so the headroom is on record.
     */
    private static final int MAX_NAMED_CONTROLLER_SYNC_BYTES = 4096;

    private WarehouseInterfaceGameTests() {
    }

    /**
     * (a) An interface in front of a chest with three item types: nothing is read without an observer; the first
     * observation builds the summary; snapshot totals, slot usage and top entries; a content change marks the summary
     * dirty through the neighbour-change hint and the next observation picks it up; the client sync round trip.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceChestSnapshot(GameTestHelper helper) {
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        IItemHandler chest = handlerAt(helper, NORTH_OF_CENTER);
        insertAll(helper, chest, new ItemStack(Items.IRON_INGOT, IRON));
        insertAll(helper, chest, new ItemStack(Items.GOLD_INGOT, GOLD));
        insertAll(helper, chest, new ItemStack(Items.DIAMOND, DIAMONDS));
        placeInterface(helper, CENTER, Direction.NORTH);

        ItemKey iron = ItemKey.of(Items.IRON_INGOT);
        ItemKey gold = ItemKey.of(Items.GOLD_INGOT);
        ItemKey diamond = ItemKey.of(Items.DIAMOND);

        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    // No ticker and no observer: the loaded interface has not read the chest.
                    helper.assertValueEqual(be.summary(), AttachedInventorySummary.NONE, "summary before observation");
                    helper.assertTrue(be.isSummaryDirty(), "summary must be dirty before the first read");
                    be.onGoggleObserved();
                    assertSummary(helper, be.summary(), Blocks.CHEST, 4, CHEST_SLOTS, 3,
                            List.of(new KeyCount<>(Items.IRON_INGOT, IRON), new KeyCount<>(Items.GOLD_INGOT, GOLD),
                                    new KeyCount<>(Items.DIAMOND, DIAMONDS)));
                    helper.assertFalse(be.isSummaryDirty(), "summary clean after the observed read");
                })
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    helper.assertTrue(be.hasAttachedInventory(), "chest must be attached");
                    InventorySnapshot<ItemKey> snapshot = be.snapshot();
                    helper.assertValueEqual(snapshot.totalSlots(), CHEST_SLOTS, "total slots");
                    helper.assertValueEqual(snapshot.usedSlots(), 4, "used slots (64 + 36 iron, gold, diamonds)");
                    helper.assertValueEqual(snapshot.count(iron), (long) IRON, "iron");
                    helper.assertValueEqual(snapshot.count(gold), (long) GOLD, "gold");
                    helper.assertValueEqual(snapshot.count(diamond), (long) DIAMONDS, "diamonds");
                    helper.assertValueEqual(snapshot.topEntries(3), List.of(new KeyCount<>(iron, (long) IRON),
                            new KeyCount<>(gold, (long) GOLD), new KeyCount<>(diamond, (long) DIAMONDS)), "top entries");
                    // Changing the chest calls its setChanged(), which reaches the interface as onNeighborChange.
                    insertAll(helper, handlerAt(helper, NORTH_OF_CENTER), new ItemStack(Items.GOLD_INGOT, GOLD_ADDED));
                    helper.assertTrue(be.isSummaryDirty(), "the neighbour-change hint must mark the summary dirty");
                    helper.assertValueEqual(be.summary().contents().topEntries().get(1),
                            new KeyCount<>(Items.GOLD_INGOT, (long) GOLD), "no read without an observer");
                })
                .thenWaitUntil(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    be.onGoggleObserved();
                    helper.assertValueEqual(be.summary().contents().topEntries(),
                            List.of(new KeyCount<>(Items.IRON_INGOT, (long) IRON),
                                    new KeyCount<>(Items.GOLD_INGOT, (long) (GOLD + GOLD_ADDED)),
                                    new KeyCount<>(Items.DIAMOND, (long) DIAMONDS)),
                            "summary after the hint and an observation");
                })
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseInterfaceBlockEntity client = detachedCopy(helper, be);
                    client.handleUpdateTag(be.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.summary(), be.summary(), "summary after client sync");
                    helper.assertTrue(client.attachedBlockName().isPresent(), "synced block name");
                    CompoundTag saved = be.saveWithFullMetadata(registries);
                    helper.assertFalse(saved.contains("GoggleSummary"), "the derived summary is not saved to disk");
                })
                .thenSucceed();
    }

    /** (b) Interfaces facing air, a non-inventory block and another interface report no inventory. */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceNoInventory(GameTestHelper helper) {
        BlockPos facingAir = new BlockPos(1, BASE_Y, 3);
        BlockPos stone = NORTH_OF_CENTER;
        BlockPos facingInterface = CENTER.south();
        helper.setBlock(stone, Blocks.STONE);
        placeInterface(helper, facingAir, Direction.WEST);
        placeInterface(helper, CENTER, Direction.NORTH);
        placeInterface(helper, facingInterface, Direction.NORTH);

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(CENTER), null) == null, "the interface itself exposes no item handler");
                    for (BlockPos pos : List.of(facingAir, CENTER, facingInterface)) {
                        WarehouseInterfaceBlockEntity be = interfaceAt(helper, pos);
                        be.onGoggleObserved();
                        helper.assertFalse(be.hasAttachedInventory(), "no inventory expected at " + pos);
                        helper.assertTrue(be.attachedHandler().isEmpty(), "no handler expected at " + pos);
                        helper.assertValueEqual(be.snapshot().totalSlots(), 0, "snapshot slots at " + pos);
                        helper.assertValueEqual(be.summary(), AttachedInventorySummary.NONE, "summary at " + pos);
                        helper.assertTrue(be.attachedBlockName().isEmpty(), "no block name at " + pos);
                    }
                })
                .thenSucceed();
    }

    /**
     * (c) Removing the attached chest invalidates the capability cache at once and marks the summary dirty; the next
     * observation reports no inventory, and a barrel placed in its spot is picked up again.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceInventoryRemoved(GameTestHelper helper) {
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        insertAll(helper, handlerAt(helper, NORTH_OF_CENTER), new ItemStack(Items.EMERALD, EMERALDS));
        placeInterface(helper, CENTER, Direction.NORTH);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    be.onGoggleObserved();
                    helper.assertTrue(be.summary().hasInventory(), "summary must see the chest");
                })
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    helper.assertTrue(be.hasAttachedInventory(), "chest attached before removal");
                    helper.assertFalse(be.isSummaryDirty(), "summary clean before removal");
                    helper.setBlock(NORTH_OF_CENTER, Blocks.AIR);
                    helper.assertFalse(be.hasAttachedInventory(), "cache must be invalidated by the removal");
                    helper.assertTrue(be.isSummaryDirty(), "cache invalidation must mark the summary dirty");
                })
                .thenWaitUntil(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    be.onGoggleObserved();
                    helper.assertValueEqual(be.summary(), AttachedInventorySummary.NONE, "summary after removal");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(interfaceAt(helper, CENTER).snapshot().totalSlots(), 0,
                            "snapshot without inventory");
                    helper.setBlock(NORTH_OF_CENTER, Blocks.BARREL);
                })
                .thenWaitUntil(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    be.onGoggleObserved();
                    AttachedInventorySummary summary = be.summary();
                    helper.assertTrue(summary.hasInventory(), "barrel must be detected");
                    helper.assertValueEqual(summary.attachedBlock(), BuiltInRegistries.BLOCK.getKey(Blocks.BARREL),
                            "attached block");
                    helper.assertValueEqual(summary.contents().totalSlots(), CHEST_SLOTS, "barrel slots");
                })
                .thenSucceed();
    }

    /**
     * (d) A vanilla barrel and a Create item vault are read through the capability like any inventory, and a vault
     * content change marks the summary dirty through the neighbour-change hint.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = SETTLED_TEST_TIMEOUT_TICKS)
    public static void interfaceBarrelAndVault(GameTestHelper helper) {
        BlockPos barrel = new BlockPos(1, BASE_Y, 3);
        BlockPos barrelInterface = new BlockPos(2, BASE_Y, 3);
        BlockPos vault = new BlockPos(5, BASE_Y, 3);
        BlockPos vaultInterface = new BlockPos(4, BASE_Y, 3);
        helper.setBlock(barrel, Blocks.BARREL);
        helper.setBlock(vault, AllBlocks.ITEM_VAULT.getDefaultState());
        placeInterface(helper, barrelInterface, Direction.WEST);
        placeInterface(helper, vaultInterface, Direction.EAST);
        IItemHandler barrelHandler = handlerAt(helper, barrel);
        insertAll(helper, barrelHandler, new ItemStack(Items.COBBLESTONE, COBBLESTONE));
        insertAll(helper, barrelHandler, new ItemStack(Items.APPLE, APPLES));
        ItemKey iron = ItemKey.of(Items.IRON_INGOT);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                        helper.absolutePos(vault), null) != null, "item vault must expose an item handler"))
                // Let the vault finish its connectivity setup (which invalidates capabilities), so that the dirty flag
                // checked below can only come from the content-change hint.
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    insertAll(helper, handlerAt(helper, vault), new ItemStack(Items.IRON_INGOT, VAULT_IRON));
                    InventorySnapshot<ItemKey> barrelSnapshot =
                            assertMatchesDirectCapture(helper, barrelInterface, barrel, Blocks.BARREL);
                    helper.assertValueEqual(barrelSnapshot.totalSlots(), CHEST_SLOTS, "barrel slots");
                    helper.assertValueEqual(barrelSnapshot.count(ItemKey.of(Items.APPLE)), (long) APPLES, "apples");
                    InventorySnapshot<ItemKey> vaultSnapshot =
                            assertMatchesDirectCapture(helper, vaultInterface, vault, AllBlocks.ITEM_VAULT.get());
                    helper.assertValueEqual(vaultSnapshot.count(iron), (long) VAULT_IRON, "vault iron");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, vaultInterface);
                    helper.assertFalse(be.isSummaryDirty(), "vault summary clean before the insert");
                    // Create's vault notifies neighbours of content changes too (onNeighborChange hint).
                    insertAll(helper, handlerAt(helper, vault), new ItemStack(Items.IRON_INGOT, VAULT_IRON_ADDED));
                    helper.assertTrue(be.isSummaryDirty(), "the vault's neighbour-change hint must mark it dirty");
                })
                .thenWaitUntil(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, vaultInterface);
                    be.onGoggleObserved();
                    helper.assertValueEqual(be.summary().contents().topEntries(),
                            List.of(new KeyCount<>(Items.IRON_INGOT, (long) (VAULT_IRON + VAULT_IRON_ADDED))),
                            "vault summary after insert");
                })
                .thenSucceed();
    }

    /** Wrench rotation keeps the block entity and re-targets the capability cache; persistence round-trips. */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceRotation(GameTestHelper helper) {
        BlockPos east = CENTER.east();
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        helper.setBlock(east, Blocks.BARREL);
        insertAll(helper, handlerAt(helper, NORTH_OF_CENTER), new ItemStack(Items.DIAMOND, 1));
        insertAll(helper, handlerAt(helper, east), new ItemStack(Items.APPLE, APPLES));
        placeInterface(helper, CENTER, Direction.NORTH);
        ItemKey diamond = ItemKey.of(Items.DIAMOND);
        ItemKey apple = ItemKey.of(Items.APPLE);

        WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
        helper.assertValueEqual(be.snapshot().count(diamond), 1L, "facing north reads the chest");
        helper.assertFalse(be.isSummaryDirty(), "a snapshot counts as a summary refresh");

        WarehouseInterfaceBlock block = WareworksBlocks.WAREHOUSE_INTERFACE.get();
        BlockState rotated = block.getRotatedBlockState(helper.getBlockState(CENTER), Direction.UP);
        helper.assertValueEqual(rotated.getValue(WarehouseInterfaceBlock.FACING), Direction.EAST,
                "wrenching the top face rotates clockwise");
        helper.setBlock(CENTER, rotated);
        helper.assertTrue(interfaceAt(helper, CENTER) == be, "rotation must keep the block entity");
        helper.assertTrue(be.isSummaryDirty(), "rotation marks the summary dirty");
        helper.assertValueEqual(be.attachedPos(), helper.absolutePos(east), "attached position after rotation");
        InventorySnapshot<ItemKey> eastSnapshot = be.snapshot();
        helper.assertValueEqual(eastSnapshot.count(apple), (long) APPLES, "facing east reads the barrel");
        helper.assertValueEqual(eastSnapshot.count(diamond), 0L, "the chest is no longer read");
        helper.assertValueEqual(be.summary().attachedBlock(), BuiltInRegistries.BLOCK.getKey(Blocks.BARREL),
                "summary follows the rotation");

        helper.setBlock(CENTER, rotated.setValue(WarehouseInterfaceBlock.FACING, Direction.SOUTH));
        helper.assertFalse(be.hasAttachedInventory(), "facing air after the second rotation");

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = be.saveWithFullMetadata(registries);
        BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), saved, registries);
        if (!(loaded instanceof WarehouseInterfaceBlockEntity loadedInterface)) {
            helper.fail("saved interface must load again as a warehouse interface", CENTER);
            return;
        }
        helper.assertValueEqual(loadedInterface.facing(), Direction.SOUTH, "loaded facing");
        helper.assertValueEqual(loadedInterface.summary(), AttachedInventorySummary.NONE,
                "the derived summary is not restored from disk");
        helper.assertTrue(loadedInterface.isSummaryDirty(), "a loaded interface re-reads on its first observation");
        helper.succeed();
    }

    /**
     * Placement faces the clicked inventory for side clicks, copies the facing of a clicked interface, and follows the
     * look direction for non-inventory blocks, floor clicks and replacing clicks.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfacePlacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        BlockPos chest = helper.absolutePos(NORTH_OF_CENTER);
        BlockPos stone = new BlockPos(1, BASE_Y, 1);
        helper.setBlock(stone, Blocks.STONE);
        BlockPos existingInterface = new BlockPos(5, BASE_Y, 1);
        placeInterface(helper, existingInterface, Direction.EAST);
        BlockPos floor = helper.absolutePos(new BlockPos(1, FLOOR_Y, 5));
        BlockPos replaceable = new BlockPos(5, BASE_Y, 5);
        helper.setBlock(replaceable, Blocks.LIGHT);

        helper.assertValueEqual(placedFacing(level, null, chest, Direction.SOUTH), Direction.NORTH,
                "clicking the chest's south face attaches to the chest");
        helper.assertValueEqual(placedFacing(level, null, chest, Direction.EAST), Direction.WEST,
                "clicking the chest's east face attaches to the chest");
        helper.assertValueEqual(placedFacing(level, null, floor, Direction.UP), Direction.NORTH,
                "floor click without a player uses the default direction");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(YAW_WEST);
        helper.assertValueEqual(placedFacing(level, player, floor, Direction.UP), Direction.WEST,
                "floor click uses the player's look direction");
        helper.assertValueEqual(placedFacing(level, player, helper.absolutePos(stone), Direction.SOUTH), Direction.WEST,
                "clicking a block without a block entity uses the look direction");
        helper.assertValueEqual(placedFacing(level, player, helper.absolutePos(existingInterface), Direction.SOUTH),
                Direction.EAST, "clicking the side of an interface copies its facing (row building)");
        player.setYRot(YAW_SOUTH);
        helper.assertValueEqual(placedFacing(level, player, helper.absolutePos(replaceable), Direction.EAST),
                Direction.SOUTH, "a click that replaces the clicked block uses the look direction");
        helper.succeed();
    }

    /**
     * Goggle observation through {@link GoggleObservers}: only a non-spectator player wearing goggles who looks at the
     * interface triggers a read; while observed, a silent inventory change (no neighbour notification) is picked up by
     * the periodic re-read.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceGoggleObserver(GameTestHelper helper) {
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        insertAll(helper, handlerAt(helper, NORTH_OF_CENTER), new ItemStack(Items.EMERALD, EMERALDS));
        placeInterface(helper, CENTER, Direction.NORTH);
        BlockPos interfacePos = helper.absolutePos(CENTER);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        lookAtInterfaceFromSouth(helper, player);
        Player spectator = helper.makeMockPlayer(GameType.SPECTATOR);
        lookAtInterfaceFromSouth(helper, spectator);
        spectator.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    helper.assertTrue(GoggleObservers.observedBlock(player).isEmpty(), "no goggles, no observation");
                    helper.assertFalse(GoggleObservers.notifyObserved(player), "no goggles, no notification");
                    helper.assertTrue(GoggleObservers.observedBlock(spectator).isEmpty(), "spectators are ignored");
                    helper.assertValueEqual(be.summary(), AttachedInventorySummary.NONE, "nothing read yet");

                    player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
                    helper.assertValueEqual(GoggleObservers.observedBlock(player).orElse(null), interfacePos,
                            "the ray from the player's eyes hits the interface");
                    helper.assertTrue(GoggleObservers.notifyObserved(player), "the interface is notified");
                    helper.assertValueEqual(be.summary().contents().topEntries(),
                            List.of(new KeyCount<>(Items.EMERALD, (long) EMERALDS)), "summary after observation");

                    // Looking at the floor below: a block is hit, but it has no observable block entity.
                    Vec3 eyes = player.getEyePosition();
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, eyes.subtract(0, LOOK_DOWN_DEPTH, 0));
                    helper.assertValueEqual(GoggleObservers.observedBlock(player).orElse(null),
                            helper.absolutePos(CENTER.south(OBSERVER_DISTANCE).atY(FLOOR_Y)), "looking at the floor");
                    helper.assertFalse(GoggleObservers.notifyObserved(player), "the floor is not observable");
                    lookAtInterfaceFromSouth(helper, player);

                    // A silent change: the stored stack is modified without setChanged(), so no hint arrives.
                    if (!(helper.getBlockEntity(NORTH_OF_CENTER) instanceof Container container)) {
                        helper.fail("chest block entity missing", NORTH_OF_CENTER);
                        return;
                    }
                    container.getItem(0).grow(EMERALDS_ADDED_SILENTLY);
                    helper.assertFalse(be.isSummaryDirty(), "a silent change sends no hint");
                })
                .thenWaitUntil(() -> {
                    GoggleObservers.notifyObserved(player);
                    helper.assertValueEqual(interfaceAt(helper, CENTER).summary().contents().topEntries(),
                            List.of(new KeyCount<>(Items.EMERALD, (long) (EMERALDS + EMERALDS_ADDED_SILENTLY))),
                            "an observed interface re-reads periodically");
                })
                .thenSucceed();
    }

    /**
     * The synced goggle summary carries item types and counts only: heavy item data (a shulker box full of written
     * pages, a huge custom name) never reaches the update tag, and items that differ only in components share a line. The
     * tag stays within {@link WareworksGameTests#MAX_INTERFACE_SYNC_BYTES}, and with the largest reservation summary (two
     * item types with long ids per direction) and the widest aisle assignment (the longest address with the longest
     * aisle name, M25 issue #15) on top within {@link WareworksGameTests#MAX_RESERVED_INTERFACE_SYNC_BYTES}.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceSummarySyncIsBounded(GameTestHelper helper) {
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        IItemHandler chest = handlerAt(helper, NORTH_OF_CENTER);
        insertAll(helper, chest, heavyShulkerBox());
        ItemStack longName = new ItemStack(Items.IRON_INGOT);
        longName.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(LONG_NAME_LENGTH)));
        insertAll(helper, chest, longName);
        insertAll(helper, chest, new ItemStack(Items.IRON_INGOT));
        for (int damage = 1; damage <= DAMAGED_PICKAXES; damage++) {
            ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
            pickaxe.set(DataComponents.DAMAGE, damage);
            insertAll(helper, chest, pickaxe);
        }
        placeInterface(helper, CENTER, Direction.NORTH);

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    be.onGoggleObserved();
                    helper.assertValueEqual(be.summary().contents().topEntries(),
                            List.of(new KeyCount<>(Items.IRON_PICKAXE, (long) DAMAGED_PICKAXES),
                                    new KeyCount<>(Items.IRON_INGOT, 2L), new KeyCount<>(Items.SHULKER_BOX, 1L)),
                            "summary grouped by item type");
                    helper.assertValueEqual(be.summary().contents().distinctKeys(), 3, "distinct item types");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag updateTag = be.getUpdateTag(registries);
                    // M8: an unfiltered interface writes no filter into its update tag. Create's FilteringBehaviour
                    // writes Filter/FilterAmount/UpTo unconditionally, which cost every interface of a rack wall about
                    // 215 accounting bytes and pushed this very tag past its budget (StorageFilterBehaviour).
                    helper.assertFalse(updateTag.contains("Filter"),
                            "an unfiltered interface must not sync a filter slot");
                    int size = updateTag.sizeInBytes();
                    helper.assertTrue(size < MAX_INTERFACE_SYNC_BYTES,
                            "update tag must stay small, but has " + size + " bytes");
                    WarehouseInterfaceBlockEntity client = detachedCopy(helper, be);
                    client.handleUpdateTag(updateTag, registries);
                    helper.assertValueEqual(client.summary(), be.summary(), "summary after client sync");

                    // The largest reservation summary a location can sync, on top of the worst-case summary.
                    LocationReservationSummary reserved = new LocationReservationSummary(
                            List.of(new KeyCount<>(Items.POLISHED_BLACKSTONE_PRESSURE_PLATE, Long.MAX_VALUE),
                                    new KeyCount<>(Items.WAXED_WEATHERED_CUT_COPPER_STAIRS, Long.MAX_VALUE)),
                            List.of(new KeyCount<>(Items.LIGHT_BLUE_GLAZED_TERRACOTTA, Long.MAX_VALUE),
                                    new KeyCount<>(Items.SKELETON_HORSE_SPAWN_EGG, Long.MAX_VALUE)));
                    CompoundTag reservationsTag = new CompoundTag();
                    reserved.write(reservationsTag);
                    CompoundTag reservedUpdateTag = updateTag.copy();
                    reservedUpdateTag.put(WarehouseInterfaceBlockEntity.RESERVATIONS_TAG, reservationsTag);
                    // And on top of that the widest assignment a member can carry since M25 (issue #15): the longest
                    // address with the longest aisle name. A rack wall is dozens of these blocks in one chunk packet,
                    // so a name that fits "here" is not the question — the worst case is.
                    CompoundTag assignmentTag = new CompoundTag();
                    AisleAssignment.assigned(StorageAddress.parse(WIDEST_ADDRESS))
                            .withAisleName(Optional.of(LONGEST_AISLE_NAME)).write(assignmentTag);
                    reservedUpdateTag.put(WarehouseInterfaceBlockEntity.ASSIGNMENT_TAG, assignmentTag);
                    int reservedSize = reservedUpdateTag.sizeInBytes();
                    Wareworks.LOGGER.debug("Interface update tag: {} bytes, with the largest reservations {} bytes", size,
                            reservedSize);
                    helper.assertTrue(reservedSize < MAX_RESERVED_INTERFACE_SYNC_BYTES,
                            "update tag with the largest reservations must stay small, but has " + reservedSize + " bytes");
                    WarehouseInterfaceBlockEntity reservedClient = detachedCopy(helper, be);
                    reservedClient.handleUpdateTag(reservedUpdateTag, registries);
                    helper.assertValueEqual(reservedClient.reservationSummary(), reserved,
                            "largest reservations after client sync");
                    helper.assertValueEqual(reservedClient.aisleAssignment().aisleName(),
                            Optional.of(LONGEST_AISLE_NAME), "and the aisle's name arrived with them");
                })
                .thenSucceed();
    }

    /** Invalid summary tags (wrong types, negative or unknown values) never throw and never produce invalid data. */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceSummaryMalformedTags(GameTestHelper helper) {
        helper.assertValueEqual(AttachedInventorySummary.read(new CompoundTag()), AttachedInventorySummary.NONE,
                "empty tag");
        CompoundTag wrongType = new CompoundTag();
        wrongType.putString("HasInventory", "yes");
        helper.assertValueEqual(AttachedInventorySummary.read(wrongType), AttachedInventorySummary.NONE,
                "HasInventory with the wrong type");

        CompoundTag topAsString = new CompoundTag();
        topAsString.putBoolean("HasInventory", true);
        topAsString.putString("Block", "Not A Valid Id!");
        topAsString.putInt("UsedSlots", 50);
        topAsString.putInt("TotalSlots", CHEST_SLOTS);
        topAsString.putInt("DistinctItems", -4);
        topAsString.putString("Top", "garbage");
        AttachedInventorySummary clamped = AttachedInventorySummary.read(topAsString);
        helper.assertTrue(clamped.hasInventory(), "inventory flag kept");
        helper.assertTrue(clamped.attachedBlock() == null, "invalid block id dropped");
        helper.assertTrue(clamped.attachedBlockName().isEmpty(), "no name for an invalid block id");
        helper.assertValueEqual(clamped.contents().usedSlots(), CHEST_SLOTS, "used slots clamped to total");
        helper.assertValueEqual(clamped.contents().distinctKeys(), 0, "negative distinct count clamped");
        helper.assertTrue(clamped.contents().topEntries().isEmpty(), "Top with the wrong type");

        CompoundTag stringList = topAsString.copy();
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("minecraft:emerald"));
        stringList.put("Top", strings);
        helper.assertTrue(AttachedInventorySummary.read(stringList).contents().topEntries().isEmpty(),
                "Top as a list of strings");

        CompoundTag entries = new CompoundTag();
        entries.putBoolean("HasInventory", true);
        entries.putString("Block", Wareworks.ID + ":does_not_exist");
        entries.putInt("TotalSlots", -1);
        ListTag top = new ListTag();
        top.add(entry("minecraft:emerald", EMERALDS));
        top.add(entry("minecraft:diamond", -1));
        top.add(entry(Wareworks.ID + ":does_not_exist", 1));
        top.add(entry("minecraft:air", 1));
        top.add(entry("Not A Valid Id!", 1));
        CompoundTag missingItem = new CompoundTag();
        missingItem.putLong("Count", 1);
        top.add(missingItem);
        entries.put("Top", top);
        AttachedInventorySummary filtered = AttachedInventorySummary.read(entries);
        helper.assertValueEqual(filtered.attachedBlock(), ResourceLocation.fromNamespaceAndPath(Wareworks.ID,
                "does_not_exist"), "unknown but valid block id kept");
        helper.assertTrue(filtered.attachedBlockName().isEmpty(), "no name for an unknown block");
        helper.assertValueEqual(filtered.contents().totalSlots(), 0, "negative total slots clamped");
        helper.assertValueEqual(filtered.contents().topEntries(), List.of(new KeyCount<>(Items.EMERALD,
                (long) EMERALDS)), "only the valid entry survives");

        List<KeyCount<Item>> valid = new ArrayList<>(List.of(new KeyCount<>(Items.DIAMOND, 3L),
                new KeyCount<>(Items.AIR, 1L)));
        AttachedInventorySummary original = AttachedInventorySummary.attached(BuiltInRegistries.BLOCK.getKey(
                Blocks.BARREL), new InventorySummary<>(2, CHEST_SLOTS, 2, valid));
        CompoundTag written = new CompoundTag();
        original.write(written);
        AttachedInventorySummary roundTrip = AttachedInventorySummary.read(written);
        helper.assertValueEqual(roundTrip.contents().topEntries(), List.of(new KeyCount<>(Items.DIAMOND, 3L)),
                "air entries are not written");
        helper.assertValueEqual(roundTrip.contents().distinctKeys(), 2, "distinct count survives");
        helper.assertValueEqual(roundTrip.attachedBlock(), original.attachedBlock(), "block survives");

        // The read side is bounded by the same constant as the write side, like every sibling summary
        // (CraneGoggleInfo.MAX_HELD_ENTRIES, LocationReservationSummary.MAX_ENTRIES): a modified or malfunctioning
        // server cannot make a client allocate and draw one tooltip line per entry.
        final int floodedEntries = 500;
        CompoundTag flooded = new CompoundTag();
        flooded.putBoolean("HasInventory", true);
        flooded.putInt("TotalSlots", CHEST_SLOTS);
        flooded.putInt("DistinctItems", Integer.MAX_VALUE);
        ListTag manyEntries = new ListTag();
        for (int i = 0; i < floodedEntries; i++)
            manyEntries.add(entry("minecraft:emerald", EMERALDS));
        flooded.put("Top", manyEntries);
        InventorySummary<Item> bounded = AttachedInventorySummary.read(flooded).contents();
        helper.assertValueEqual(bounded.topEntries().size(), ItemTypeSummaries.MAX_ENTRIES,
                "a flood of top entries is cut at the bound");
        helper.succeed();
    }

    /**
     * Breaking the interface drops it as an item, removes its block entity, leaves the attached inventory untouched
     * and turns the stale block entity reference inert.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void interfaceBreak(GameTestHelper helper) {
        helper.setBlock(NORTH_OF_CENTER, Blocks.CHEST);
        insertAll(helper, handlerAt(helper, NORTH_OF_CENTER), new ItemStack(Items.EMERALD, EMERALDS));
        placeInterface(helper, CENTER, Direction.NORTH);

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseInterfaceBlockEntity be = interfaceAt(helper, CENTER);
                    helper.assertTrue(be.hasAttachedInventory(), "chest attached before breaking");
                    helper.getLevel().destroyBlock(helper.absolutePos(CENTER), true);
                    helper.assertBlockPresent(Blocks.AIR, CENTER);
                    helper.assertTrue(WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                            .getNullable(helper.getLevel(), helper.absolutePos(CENTER)) == null, "block entity removed");
                    helper.assertTrue(be.isRemoved(), "the old block entity is marked removed");
                    helper.assertTrue(be.attachedHandler().isEmpty(), "a removed interface reads nothing");
                    be.onGoggleObserved(); // must be a no-op, not a crash
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_INTERFACE.asItem(), CENTER, 1.0);
                    helper.assertValueEqual(ItemHandlerSnapshots.capture(handlerAt(helper, NORTH_OF_CENTER))
                            .count(ItemKey.of(Items.EMERALD)), (long) EMERALDS, "chest contents untouched");
                })
                .thenSucceed();
    }

    /**
     * Naming an aisle through an interface (M25, issue #15): an interface names <b>its own</b> aisle, which is why the
     * gesture needs no syntax at all — the block already knows which aisle it stands in.
     * <p>
     * Built as an L on {@code aisle_pair_16x10x13}: aisle A runs east from the dock to the corner at {@code x = 5},
     * aisle B runs south out of it. One interface stands on A and one on B, and this is the test that could not be
     * written on a straight warehouse:
     * <ul>
     * <li>The interface on <b>B names B</b>, not the aisle at the dock, and the one on A names A.</li>
     * <li><b>Neither click touches the other aisle</b>, so two aisles never end up sharing a name.</li>
     * <li>And the hard case: the controller's letter is scrolled <b>onto the letter the second aisle already holds</b>.
     * The next re-link moves that aisle to the lowest free letter, and the names move with the letters — so when the
     * dust settles the two aisles still have two different letters and each still carries the name it was given.
     * Nothing a player can do here makes one name belong to two aisles.</li>
     * <li>A plain name tag clears the aisle of the interface it was used on and leaves the other one alone.</li>
     * </ul>
     * With {@code aisle.maxBranches = 1} — the off switch that reproduces 0.5.0 — the second aisle is no part of the
     * warehouse at all, so the interface on it belongs to no aisle and the click says so instead.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = CORNER_TIMEOUT_TICKS)
    public static void interfaceNamesItsOwnAisle(GameTestHelper helper) {
        buildCorner(helper);
        placeStorage(helper, RACK_ON_A, CHEST_ON_A, Direction.NORTH);
        placeStorage(helper, RACK_ON_B, CHEST_ON_B, Direction.WEST);
        boolean bends = WareworksConfig.maxBranches() > 1;

        helper.startSequence()
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = cornerController(helper);
                    helper.assertValueEqual(controller.warehouse().map(WarehouseLayout::branchCount),
                            Optional.of(bends ? 2 : 1), "the warehouse bends exactly when it may");
                    helper.assertValueEqual(controller.storageLocations().size(), bends ? 2 : 1,
                            "the racks that belong to it have joined");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = cornerController(helper);
                    NamingClick.Teller player = NamingClick.player(helper);

                    // The interface on the second aisle: with corners off it is part of no warehouse at all.
                    NamingClick.use(helper, player, RACK_ON_B, Direction.EAST,
                            NamingClick.renamed(Items.IRON_INGOT, METALS));
                    if (!bends) {
                        NamingClick.assertTold(helper, player, "a click on an aisle the warehouse does not have",
                                WareworksLang.AISLE_NAME_NO_AISLE);
                        helper.assertTrue(controller.hasNoAisleNames(), "and nothing was named");
                        return;
                    }
                    NamingClick.assertTold(helper, player, "naming through the interface on aisle B",
                            WareworksLang.AISLE_NAMED);
                    helper.assertValueEqual(player.argsOf(0), List.of("B", METALS),
                            "the interface named ITS aisle, not the one at the dock");
                    helper.assertValueEqual(controller.aisleName('B'), Optional.of(METALS), "aisle B is named");
                    helper.assertTrue(controller.aisleName('A').isEmpty(), "and aisle A still is not");

                    NamingClick.use(helper, player, RACK_ON_A, Direction.SOUTH,
                            NamingClick.renamed(Items.IRON_INGOT, ORES));
                    helper.assertValueEqual(player.argsOf(0), List.of("A", ORES), "the one on aisle A named A");
                    helper.assertValueEqual(controller.aisleName('A'), Optional.of(ORES), "both aisles are named");
                    helper.assertValueEqual(controller.aisleName('B'), Optional.of(METALS), "and with their own names");
                })
                // The hard case: the dock aisle is scrolled onto the letter the second aisle holds.
                .thenExecute(() -> {
                    if (!bends)
                        return;
                    WarehouseControllerBlockEntity controller = cornerController(helper);
                    ScrollValueBehaviour letter = BlockEntityBehaviour.get(controller, ScrollValueBehaviour.TYPE);
                    if (letter == null) {
                        helper.fail("the controller has no aisle letter value box", CORNER_CONTROLLER);
                        return;
                    }
                    letter.setValue(LETTER_B);
                })
                // The next re-link is what sorts the letters out; until it runs, both aisles really do read the same
                // letter - and so the same name - which is the behaviour the design accepts and documents.
                .thenWaitUntil(() -> {
                    if (!bends)
                        return;
                    char onA = aisleLetterOf(helper, RACK_ON_A);
                    char onB = aisleLetterOf(helper, RACK_ON_B);
                    helper.assertTrue(onA != onB, "the two aisles have two different letters again");
                })
                .thenExecute(() -> {
                    if (!bends)
                        return;
                    WarehouseControllerBlockEntity controller = cornerController(helper);
                    char onA = aisleLetterOf(helper, RACK_ON_A);
                    char onB = aisleLetterOf(helper, RACK_ON_B);
                    helper.assertValueEqual(onA, 'B', "the dock aisle took the letter it was scrolled to");
                    helper.assertValueEqual(onB, 'A', "and the other aisle moved to the lowest free one");
                    helper.assertValueEqual(controller.aisleName(onA), Optional.of(ORES),
                            "the dock aisle still carries the name it was given");
                    helper.assertValueEqual(controller.aisleName(onB), Optional.of(METALS),
                            "and so does the other one: the names moved with the letters");
                    helper.assertValueEqual(controller.aisleNames().size(), 2, "two aisles, two names, no sharing");

                    // What this warehouse's own chunk packet weighs (M25 review fix). Everything else that measures
                    // the controller's named summary crafts it on top of a ONE-aisle fixture, so until here nothing
                    // weighed a real bending warehouse with real names on it - which is the shape both of M25's byte
                    // numbers are written about. Measured where such a warehouse already stands rather than building
                    // a second one for it.
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    int bytes = controller.getUpdateTag(registries).sizeInBytes();
                    Wareworks.LOGGER.debug("Controller update tag of a bending warehouse with {} names: {} bytes",
                            controller.aisleNames().size(), bytes);
                    helper.assertTrue(bytes < MAX_NAMED_CONTROLLER_SYNC_BYTES,
                            "a bending warehouse with named aisles must keep its update tag under "
                                    + MAX_NAMED_CONTROLLER_SYNC_BYTES + " bytes, but it has " + bytes);
                    WarehouseControllerBlockEntity client = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(controller.getBlockPos(), controller.getBlockState());
                    if (client == null) {
                        helper.fail("could not create a detached warehouse controller", CORNER_CONTROLLER);
                        return;
                    }
                    client.handleUpdateTag(controller.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.summary(), controller.summary(),
                            "and the whole summary survives the round trip");

                    // A name tag clears the aisle it was used on, and only that one.
                    NamingClick.Teller player = NamingClick.player(helper);
                    NamingClick.use(helper, player, RACK_ON_B, Direction.EAST, NamingClick.blankNameTag());
                    NamingClick.assertTold(helper, player, "a name tag on the interface of the other aisle",
                            WareworksLang.AISLE_NAME_CLEARED);
                    helper.assertTrue(controller.aisleName(onB).isEmpty(), "its aisle has no name any more");
                    helper.assertValueEqual(controller.aisleName(onA), Optional.of(ORES), "the other one still has its");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Motor, dock, controller, aisle A's rails east of the dock and aisle B running south out of the corner block. */
    private static void buildCorner(GameTestHelper helper) {
        helper.setBlock(CORNER_MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(CORNER_DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.EAST));
        for (int x = 1; x <= CORNER_FIRST_RAILS; x++)
            helper.setBlock(CORNER_DOCK.east(x), WarehouseRailBlock.along(Direction.Axis.X));
        for (int z = 1; z <= CORNER_SECOND_RAILS; z++)
            helper.setBlock(CORNER.south(z), WarehouseRailBlock.along(Direction.Axis.Z));
        helper.setBlock(CORNER_CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, Direction.EAST));
    }

    /** A chest behind an interface facing {@code away} from the rails. */
    private static void placeStorage(GameTestHelper helper, BlockPos rack, BlockPos chest, Direction away) {
        helper.setBlock(chest, Blocks.CHEST);
        placeInterface(helper, rack, away);
    }

    private static WarehouseControllerBlockEntity cornerController(GameTestHelper helper) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(CORNER_CONTROLLER));
        if (be == null) {
            helper.fail("missing warehouse controller", CORNER_CONTROLLER);
            throw new AssertionError("unreachable");
        }
        return be;
    }

    /** The letter of the aisle the interface at the test-relative {@code pos} stands in. */
    private static char aisleLetterOf(GameTestHelper helper, BlockPos pos) {
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, pos);
        return be.aisleToName().map(WarehouseRegistry.MemberAisle::aisle).orElseGet(() -> {
            helper.fail("the interface belongs to no aisle", pos);
            throw new AssertionError("unreachable");
        });
    }

    private static CompoundTag entry(String itemId, long count) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", itemId);
        tag.putLong("Count", count);
        return tag;
    }

    /** A shulker box item holding a full set of books with long pages (hundreds of kilobytes of component data). */
    private static ItemStack heavyShulkerBox() {
        List<Filterable<String>> pages = new ArrayList<>();
        for (int i = 0; i < HEAVY_BOOK_PAGES; i++)
            pages.add(Filterable.passThrough("w".repeat(WritableBookContent.PAGE_EDIT_LENGTH)));
        List<ItemStack> books = new ArrayList<>();
        for (int i = 0; i < HEAVY_BOOKS; i++) {
            ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
            books.add(book);
        }
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(books));
        return box;
    }

    /** Positions a mock player two blocks south of the interface at {@link #CENTER}, looking at its centre. */
    private static void lookAtInterfaceFromSouth(GameTestHelper helper, Player player) {
        Vec3 feet = Vec3.atBottomCenterOf(helper.absolutePos(CENTER.south(OBSERVER_DISTANCE)));
        player.moveTo(feet.x, feet.y, feet.z, YAW_SOUTH, 0f);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(CENTER)));
    }

    /** A level-less copy of the interface's block entity, standing in for the client-side instance. */
    private static WarehouseInterfaceBlockEntity detachedCopy(GameTestHelper helper, WarehouseInterfaceBlockEntity be) {
        WarehouseInterfaceBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                .create(be.getBlockPos(), be.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached interface block entity", CENTER);
        return copy;
    }

    private static Direction placedFacing(ServerLevel level, @Nullable Player player, BlockPos clicked, Direction face) {
        Vec3 hit = Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                WareworksBlocks.WAREHOUSE_INTERFACE.asStack(), new BlockHitResult(hit, face, clicked, false));
        BlockState state = WareworksBlocks.WAREHOUSE_INTERFACE.get().getStateForPlacement(context);
        return state.getValue(WarehouseInterfaceBlock.FACING);
    }

    private static void placeInterface(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, facing));
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(GameTestHelper helper, BlockPos pos) {
        WarehouseInterfaceBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse interface block entity", pos);
        return be;
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

    /** The snapshot through the interface equals a direct capture of the inventory's own handler. */
    private static InventorySnapshot<ItemKey> assertMatchesDirectCapture(GameTestHelper helper, BlockPos interfacePos,
                                                                         BlockPos inventoryPos, Block inventoryBlock) {
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, interfacePos);
        InventorySnapshot<ItemKey> expected = ItemHandlerSnapshots.capture(handlerAt(helper, inventoryPos));
        InventorySnapshot<ItemKey> actual = be.snapshot();
        helper.assertTrue(actual.totalSlots() > 0, "interface must read slots at " + interfacePos);
        helper.assertValueEqual(actual, expected, "snapshot through the interface at " + interfacePos);
        InventorySummary<Item> expectedContents = InventorySummary.of(expected,
                WarehouseInterfaceBlockEntity.GOGGLE_TOP_ENTRIES, ItemKey::getItem);
        assertSummary(helper, be.summary(), inventoryBlock, expectedContents.usedSlots(),
                expectedContents.totalSlots(), expectedContents.distinctKeys(), expectedContents.topEntries());
        return actual;
    }

    private static void assertSummary(GameTestHelper helper, AttachedInventorySummary summary, Block block,
                                      int usedSlots, int totalSlots, int distinctKeys,
                                      List<KeyCount<Item>> topEntries) {
        helper.assertTrue(summary.hasInventory(), "summary must report an inventory");
        helper.assertValueEqual(summary.attachedBlock(), BuiltInRegistries.BLOCK.getKey(block), "summary block");
        helper.assertValueEqual(summary.contents().usedSlots(), usedSlots, "summary used slots");
        helper.assertValueEqual(summary.contents().totalSlots(), totalSlots, "summary total slots");
        helper.assertValueEqual(summary.contents().distinctKeys(), distinctKeys, "summary distinct items");
        helper.assertValueEqual(summary.contents().topEntries(), topEntries, "summary top entries");
    }
}
