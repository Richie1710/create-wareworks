package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StationBuffer;
import dev.wareworks.content.station.StationGoggleSummary;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the warehouse input and output stations: registration and placement, insert-only and extract-only
 * capabilities (also with real hoppers), belt input, the crane buffer API, drops and {@code Clearable}, persistence and
 * client sync, aisle membership, and retrieval requests by filter and redstone rising edge.
 * <p>
 * Single-station tests use the {@code empty_7x5x7} floor. Aisle tests use {@code aisle_16x10x7} with the layout of
 * {@code WarehouseControllerGameTests}: controller x = 0, dock x = 1 (z = 3, aisle along +X), rails x = 2..6; a chest
 * with {@value #DIAMONDS_IN_STOCK} diamonds behind an interface at rack position 1 left; an aligned output at 0 right
 * (its redstone trigger block south of it), an aligned input at 2 right, a misaligned output at 3 right and a misaligned
 * input at 4 left. A real belt is not built (it needs a kinetic belt line); the belt path is tested through
 * {@link DirectBeltInputBehaviour#handleInsertion}, the same call belts, tunnels and ejectors make.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseStationGameTests {
    // --- single station layout (empty_7x5x7) ---
    private static final BlockPos CENTER = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos ABOVE_CENTER = CENTER.above();
    private static final BlockPos BELOW_CENTER = new BlockPos(3, FLOOR_Y, 3);
    private static final BlockPos BESIDE_BELOW_CENTER = new BlockPos(4, FLOOR_Y, 3);
    private static final BlockPos OTHER = new BlockPos(1, BASE_Y, 1);
    private static final BlockPos CLEARED = new BlockPos(5, BASE_Y, 5);
    /** Next to the redstone block of {@code stationRegistration}. */
    private static final BlockPos COMMAND_PLACED_OUTPUT = new BlockPos(2, BASE_Y, 3);

    // --- aisle layout (aisle_16x10x7) ---
    private static final Direction AISLE = Direction.EAST;
    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, 3);
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, 3);
    private static final int RAILS = 5;
    private static final AisleLayout RELATIVE = AisleLayout.of(DOCK, AISLE, AisleGeometry.of(RAILS, 1));
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition MISALIGNED_OUTPUT_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition MISALIGNED_INPUT_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final BlockPos OUTPUT_TRIGGER = RELATIVE.rackPos(OUTPUT_RACK).relative(Direction.SOUTH);
    private static final BlockPos BESIDE_OUTPUT = RELATIVE.rackPos(OUTPUT_RACK).relative(Direction.WEST);
    private static final BlockPos OUTSIDE_OUTPUT = new BlockPos(12, BASE_Y, 0);
    private static final BlockPos OUTSIDE_TRIGGER = new BlockPos(13, BASE_Y, 0);
    /** Free rack positions at level 0 for additional outputs (none next to a redstone trigger). */
    private static final List<RackPosition> EXTRA_OUTPUT_RACKS = List.of(new RackPosition(1, 0, Side.RIGHT),
            new RackPosition(4, 0, Side.RIGHT), new RackPosition(5, 0, Side.RIGHT), new RackPosition(0, 0, Side.LEFT),
            new RackPosition(2, 0, Side.LEFT), new RackPosition(3, 0, Side.LEFT), new RackPosition(5, 0, Side.LEFT));
    private static final RackPosition ORPHAN_OUTPUT_RACK = new RackPosition(1, 0, Side.RIGHT);

    private static final int STACK = 64;
    private static final int DIAMONDS_IN_STOCK = 20;
    private static final int REQUESTED_DIAMONDS = 32;
    private static final int DIAMONDS_ADDED = 10;
    /** Items per item type used to fill the request caps ({@link #capKeys}); enough for one request per output. */
    private static final int CAP_KEY_AMOUNT = 16;
    /** Item types {@link #capKeys} can provide without running out of chest slots. */
    private static final int MAX_CAP_KEYS = 20;
    private static final int IRON_INSERTED = 32;
    private static final int COBBLE_OVERFLOW = 7;
    private static final int IRON_TOP_UP = 40;
    private static final int EXTRACTED = 10;
    private static final int BELT_STACK = 20;
    private static final int HOPPER_DIAMONDS = 5;
    private static final int PREFILLED_EMERALDS = 3;
    private static final int COBBLE_LEFT_FREE = 54;
    private static final int COBBLE_OVER_THE_TOP = 60;
    private static final int BIG_IRON = 128;
    private static final int DROPPED_IRON = 100;
    private static final int DROPPED_GOLD = 40;
    private static final int CLEARED_EMERALDS = 16;
    private static final int OVERSIZED_SLOTS = 30;
    private static final int TAMPERED_IRON = 200;
    private static final int TAMPERED_GOLD = 5;
    private static final int TAMPERED_EMERALDS = 3;
    private static final int TAMPERED_DIAMONDS = 2;
    private static final int FAR_SLOT = 5000;
    private static final int FLOOD_ENTRIES = 5000;
    private static final int BOARD_AMOUNT = 3;
    private static final int EMPTY_TRAILING_SLOT = 2;
    private static final int FILTER_AMOUNT = 16;
    private static final int GOLD_IN_OUTPUT = 20;
    private static final int FIRST_REQUEST = 5;
    private static final int SECOND_REQUEST = 3;
    private static final int DELIVERED = 2;
    private static final int LONG_NAME_LENGTH = 20_000;
    private static final int MAX_UPDATE_TAG_BYTES = 2048;
    private static final double DROP_RADIUS = 1.0;
    private static final int HOPPER_TIMEOUT_TICKS = 300;
    private static final int HOPPER_IDLE_TICKS = 24;
    private static final int TIMEOUT_TICKS = 400;
    private static final float[] PLAYER_YAWS = {0f, 90f, 180f, 270f};

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private WarehouseStationGameTests() {
    }

    // --- registration ----------------------------------------------------------------------------------------------

    /**
     * Tags, block entity types, no redstone conduction, wrench rotation, placement facing (towards the player) with the
     * initial powered state, and stable capability instances for every side.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationRegistration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState input = inputState(Direction.NORTH);
        BlockState output = outputState(Direction.NORTH);
        for (BlockState state : List.of(input, output)) {
            helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE), "mineable with a pickaxe: " + state);
            helper.assertTrue(state.is(WareworksTags.NON_MOVABLE), "create:non_movable: " + state);
            helper.assertTrue(state.is(WareworksTags.RELOCATION_NOT_SUPPORTED), "c:relocation_not_supported: " + state);
            helper.assertFalse(state.isRedstoneConductor(level, helper.absolutePos(CENTER)), "no redstone conductor");
        }
        helper.assertTrue(WareworksBlockEntityTypes.WAREHOUSE_INPUT.get().isValid(input), "input block entity type");
        helper.assertTrue(WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.get().isValid(output), "output block entity type");
        BlockState rotated = WareworksBlocks.WAREHOUSE_OUTPUT.get()
                .getRotatedBlockState(output.setValue(WarehouseOutputBlock.POWERED, true), Direction.UP);
        helper.assertValueEqual(rotated.getValue(WarehouseStationBlock.FACING), Direction.EAST, "wrench rotates clockwise");
        helper.assertTrue(rotated.getValue(WarehouseOutputBlock.POWERED), "rotation keeps the powered state");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (float yaw : PLAYER_YAWS) {
            player.setYRot(yaw);
            Direction expected = Direction.fromYRot(yaw).getOpposite();
            helper.assertValueEqual(place(helper, player, WareworksBlocks.WAREHOUSE_INPUT.get(), new BlockPos(5, FLOOR_Y, 5))
                    .getValue(WarehouseStationBlock.FACING), expected, "input faces the player, yaw " + yaw);
            BlockState placedOutput = place(helper, player, WareworksBlocks.WAREHOUSE_OUTPUT.get(), new BlockPos(5, FLOOR_Y, 5));
            helper.assertValueEqual(placedOutput.getValue(WarehouseStationBlock.FACING), expected,
                    "output faces the player, yaw " + yaw);
            helper.assertFalse(placedOutput.getValue(WarehouseOutputBlock.POWERED), "unpowered without a signal");
        }
        helper.setBlock(new BlockPos(1, BASE_Y, 3), Blocks.REDSTONE_BLOCK);
        helper.assertTrue(place(helper, player, WareworksBlocks.WAREHOUSE_OUTPUT.get(), new BlockPos(2, FLOOR_Y, 3))
                .getValue(WarehouseOutputBlock.POWERED), "placed next to a signal: already powered, so no request");
        // Placed without a player (/setblock, structures, schematicannon): onPlace stores the signal without requesting,
        // so the next unrelated neighbour update is no rising edge.
        helper.setBlock(COMMAND_PLACED_OUTPUT, outputState(Direction.NORTH));
        helper.assertBlockProperty(COMMAND_PLACED_OUTPUT, WarehouseOutputBlock.POWERED, true);
        helper.setBlock(COMMAND_PLACED_OUTPUT.south(), Blocks.STONE);
        helper.setBlock(COMMAND_PLACED_OUTPUT.south(), Blocks.AIR);
        helper.assertTrue(outputAt(helper, COMMAND_PLACED_OUTPUT).lastRejection().isEmpty(),
                "a neighbour update next to a held signal requests nothing");

        helper.setBlock(CENTER, input);
        helper.setBlock(OTHER, output);
        for (BlockPos pos : List.of(CENTER, OTHER)) {
            WarehouseStationBlockEntity station = stationAt(helper, pos);
            IItemHandler unsided = handlerAt(helper, pos, null);
            helper.assertTrue(unsided == station.externalHandler(), "the capability is the station's view");
            for (Direction side : Direction.values())
                helper.assertTrue(handlerAt(helper, pos, side) == unsided, "same view from " + side);
            helper.assertValueEqual(station.bufferSlots(), station.configuredBufferSlots(), "configured slots");
        }
        helper.assertValueEqual(stationAt(helper, CENTER).bufferSlots(), WareworksConfig.inputBufferSlots(), "input slots");
        helper.assertValueEqual(stationAt(helper, OTHER).bufferSlots(), WareworksConfig.outputBufferSlots(), "output slots");
        helper.succeed();
    }

    // --- input -----------------------------------------------------------------------------------------------------

    /**
     * The input accepts items through the capability (with correct remainders and no aliasing of the caller's stack),
     * refuses every extraction from outside, feeds belts through its {@link DirectBeltInputBehaviour}, and gives the crane
     * one stack at a time.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void inputAcceptsItems(GameTestHelper helper) {
        helper.setBlock(CENTER, inputState(Direction.NORTH));
        WarehouseInputBlockEntity input = inputAt(helper, CENTER);
        IItemHandler handler = handlerAt(helper, CENTER, Direction.UP);
        int slots = input.bufferSlots();

        ItemStack given = new ItemStack(Items.IRON_INGOT, IRON_INSERTED);
        helper.assertTrue(ItemHandlerHelper.insertItem(handler, given, false).isEmpty(), "iron accepted");
        given.setCount(1);
        given.set(DataComponents.CUSTOM_NAME, Component.literal("changed after inserting"));
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) IRON_INSERTED, "the caller's stack is not aliased");

        helper.assertTrue(handler.extractItem(0, STACK, true).isEmpty(), "no simulated extraction from outside");
        helper.assertTrue(handler.extractItem(0, STACK, false).isEmpty(), "no extraction from outside");
        helper.assertTrue(handler.isItemValid(0, new ItemStack(Items.DIAMOND)), "any item is valid");

        ItemStack cobble = ItemHandlerHelper.insertItem(handler, COBBLE.toStack((slots - 1) * STACK + COBBLE_OVERFLOW), false);
        helper.assertValueEqual(cobble.getCount(), COBBLE_OVERFLOW, "remainder when the buffer is full");
        ItemStack iron = ItemHandlerHelper.insertItem(handler, IRON.toStack(IRON_TOP_UP), false);
        helper.assertValueEqual(iron.getCount(), IRON_INSERTED + IRON_TOP_UP - STACK, "remainder of a partial fit");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) STACK, "one full stack of iron");

        // Crane API: one stack at a time, simulate matches the real call.
        helper.assertValueEqual(input.extract(IRON, BIG_IRON, true).getCount(), STACK, "simulated: at most one stack");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) STACK, "simulation changes nothing");
        ItemStack taken = input.extract(IRON, EXTRACTED, false);
        helper.assertTrue(IRON.matches(taken) && taken.getCount() == EXTRACTED, "extracted " + taken);
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) (STACK - EXTRACTED), "iron left");
        helper.assertTrue(input.extract(GOLD, EXTRACTED, false).isEmpty(), "nothing of an absent item");
        helper.assertTrue(input.extract(IRON, 0, false).isEmpty(), "nothing for amount 0");

        // Belts, tunnels and ejectors call the direct belt input.
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(input, DirectBeltInputBehaviour.TYPE);
        if (belt == null) {
            helper.fail("the input has no belt input behaviour");
            return;
        }
        TransportedItemStack transported = new TransportedItemStack(IRON.toStack(BELT_STACK));
        helper.assertValueEqual(belt.handleInsertion(transported, Direction.EAST, true).getCount(), BELT_STACK - EXTRACTED,
                "simulated belt insertion returns the remainder");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) (STACK - EXTRACTED), "simulation changes nothing");
        helper.assertValueEqual(belt.handleInsertion(transported, Direction.EAST, false).getCount(), BELT_STACK - EXTRACTED,
                "belt insertion returns the remainder");
        helper.assertValueEqual(transported.stack.getCount(), BELT_STACK, "the belt's stack is not modified");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) STACK, "belt items arrived");
        helper.assertValueEqual(belt.handleInsertion(new ItemStack(Items.GOLD_INGOT), Direction.UP, false).getCount(), 1,
                "a full buffer refuses belt items");

        input.clearContent();
        helper.assertFalse(input.hasBufferedItems(), "Clearable empties the buffer");
        helper.assertTrue(ItemHandlerHelper.insertItem(handler, new ItemStack(Items.GOLD_INGOT), false).isEmpty(),
                "accepts again after clearing");
        helper.succeed();
    }

    /** A real hopper above feeds the input; a hopper below cannot pull anything out of it. */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = HOPPER_TIMEOUT_TICKS)
    public static void inputHopperFeed(GameTestHelper helper) {
        helper.setBlock(CENTER, inputState(Direction.NORTH));
        helper.assertTrue(ItemHandlerHelper.insertItem(handlerAt(helper, CENTER, null),
                EMERALD.toStack(PREFILLED_EMERALDS), false).isEmpty(), "prefilled");
        helper.setBlock(ABOVE_CENTER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        helper.setBlock(BESIDE_BELOW_CENTER, Blocks.CHEST);
        helper.setBlock(BELOW_CENTER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        hopperAt(helper, ABOVE_CENTER).setItem(0, DIAMOND.toStack(HOPPER_DIAMONDS));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(inputAt(helper, CENTER).bufferedItems().count(DIAMOND), (long) HOPPER_DIAMONDS,
                            "diamonds pushed in by the hopper above");
                    helper.assertTrue(hopperAt(helper, ABOVE_CENTER).isEmpty(), "hopper above emptied");
                })
                .thenIdle(HOPPER_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(inputAt(helper, CENTER).bufferedItems().count(EMERALD),
                            (long) PREFILLED_EMERALDS, "the hopper below pulled nothing");
                    helper.assertTrue(hopperAt(helper, BELOW_CENTER).isEmpty(), "hopper below stays empty");
                    helper.assertTrue(snapshotCount(helper, BESIDE_BELOW_CENTER) == 0, "chest stays empty");
                })
                .thenSucceed();
    }

    // --- output ----------------------------------------------------------------------------------------------------

    /**
     * The output refuses every insertion from outside, lets automation extract, and gives the crane an insert API with
     * correct remainders and no aliasing; a real hopper below pulls delivered items.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = HOPPER_TIMEOUT_TICKS)
    public static void outputExtractOnly(GameTestHelper helper) {
        helper.setBlock(CENTER, outputState(Direction.NORTH));
        WarehouseOutputBlockEntity output = outputAt(helper, CENTER);
        IItemHandler handler = handlerAt(helper, CENTER, Direction.DOWN);
        int slots = output.bufferSlots();
        helper.assertTrue(slots >= 2, "this test needs at least two output slots, config has " + slots);

        ItemStack offered = IRON.toStack(EXTRACTED);
        helper.assertValueEqual(handler.insertItem(0, offered, false).getCount(), EXTRACTED, "insertion refused");
        helper.assertValueEqual(ItemHandlerHelper.insertItem(handler, offered.copy(), false).getCount(), EXTRACTED,
                "insertion refused in every slot");
        helper.assertFalse(handler.isItemValid(0, offered), "no item is valid from outside");
        helper.assertFalse(output.hasBufferedItems(), "still empty");

        ItemStack delivery = IRON.toStack(STACK);
        helper.assertTrue(output.insert(delivery, true).isEmpty(), "simulated crane insert fits");
        helper.assertFalse(output.hasBufferedItems(), "simulation changes nothing");
        helper.assertTrue(output.insert(delivery, false).isEmpty(), "crane insert fits");
        delivery.setCount(1);
        helper.assertValueEqual(output.bufferedItems().count(IRON), (long) STACK, "the caller's stack is not aliased");
        helper.assertTrue(output.insert(COBBLE.toStack((slots - 1) * STACK - COBBLE_LEFT_FREE), false).isEmpty(),
                "cobblestone fits");
        helper.assertValueEqual(output.insert(COBBLE.toStack(COBBLE_OVER_THE_TOP), false).getCount(),
                COBBLE_OVER_THE_TOP - COBBLE_LEFT_FREE, "remainder of a partial fit");
        helper.assertValueEqual(output.insert(new ItemStack(Items.DIAMOND_SWORD), false).getCount(), 1,
                "an unstackable item finds no free slot");
        helper.assertValueEqual(output.insert(IRON.toStack(BIG_IRON), true).getCount(), BIG_IRON, "a full buffer");

        helper.assertValueEqual(handler.extractItem(0, STACK, true).getCount(), STACK, "simulated extraction");
        helper.assertValueEqual(output.bufferedItems().count(IRON), (long) STACK, "simulation changes nothing");
        helper.assertValueEqual(handler.extractItem(0, STACK, false).getCount(), STACK, "extraction from outside");
        helper.assertValueEqual(output.bufferedItems().count(IRON), 0L, "iron pulled out");
        helper.assertTrue(output.insert(new ItemStack(Items.DIAMOND_SWORD), false).isEmpty(), "the freed slot takes a sword");

        helper.setBlock(BESIDE_BELOW_CENTER, Blocks.CHEST);
        helper.setBlock(BELOW_CENTER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        ItemHandlerSnapshotsCount.of(handlerAt(helper, BESIDE_BELOW_CENTER, null), Items.DIAMOND_SWORD), 1L,
                        "the hopper below pulled the sword into the chest"))
                .thenExecute(() -> helper.assertValueEqual(outputAt(helper, CENTER).bufferedItems()
                        .count(ItemKey.of(Items.DIAMOND_SWORD)), 0L, "the sword left the output"))
                .thenSucceed();
    }

    // --- drops and persistence -------------------------------------------------------------------------------------

    /** Breaking a station drops its whole buffer (counts and components conserved); Clearable empties without drops. */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationDropsBuffer(GameTestHelper helper) {
        helper.setBlock(CENTER, inputState(Direction.NORTH));
        helper.setBlock(OTHER, outputState(Direction.NORTH));
        helper.setBlock(CLEARED, inputState(Direction.NORTH));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks test sword"));

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = inputAt(helper, CENTER);
                    IItemHandler handler = handlerAt(helper, CENTER, null);
                    helper.assertTrue(ItemHandlerHelper.insertItem(handler, IRON.toStack(DROPPED_IRON), false).isEmpty(), "iron");
                    helper.assertTrue(ItemHandlerHelper.insertItem(handler, sword.copy(), false).isEmpty(), "sword");
                    WarehouseOutputBlockEntity output = outputAt(helper, OTHER);
                    helper.assertTrue(output.insert(GOLD.toStack(DROPPED_GOLD), false).isEmpty(), "gold");
                    WarehouseInputBlockEntity cleared = inputAt(helper, CLEARED);
                    helper.assertTrue(ItemHandlerHelper.insertItem(handlerAt(helper, CLEARED, null),
                            EMERALD.toStack(CLEARED_EMERALDS), false).isEmpty(), "emeralds");

                    helper.getLevel().destroyBlock(helper.absolutePos(CENTER), true);
                    helper.getLevel().destroyBlock(helper.absolutePos(OTHER), true);
                    helper.assertBlockPresent(Blocks.AIR, CENTER);
                    helper.assertTrue(input.isRemoved() && output.isRemoved(), "block entities removed");
                    helper.assertFalse(input.hasBufferedItems(), "the old input buffer is empty");
                    helper.assertFalse(output.hasBufferedItems(), "the old output buffer is empty");
                    helper.assertValueEqual(dropped(helper, CENTER, Items.IRON_INGOT), (long) DROPPED_IRON, "all iron dropped");
                    helper.assertValueEqual(dropped(helper, OTHER, Items.GOLD_INGOT), (long) DROPPED_GOLD, "all gold dropped");
                    List<ItemEntity> swords = helper.getEntities(EntityType.ITEM, CENTER, DROP_RADIUS).stream()
                            .filter(entity -> entity.getItem().is(Items.DIAMOND_SWORD)).toList();
                    helper.assertValueEqual(swords.size(), 1, "one sword dropped");
                    helper.assertTrue(ItemStack.isSameItemSameComponents(swords.getFirst().getItem(), sword),
                            "the sword keeps its components");
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_INPUT.asItem(), CENTER, DROP_RADIUS);
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_OUTPUT.asItem(), OTHER, DROP_RADIUS);

                    Clearable.tryClear(cleared);
                    helper.assertFalse(cleared.hasBufferedItems(), "cleared");
                    helper.setBlock(CLEARED, Blocks.AIR);
                    helper.assertValueEqual(dropped(helper, CLEARED, Items.EMERALD), 0L, "a cleared station drops nothing");
                })
                .thenSucceed();
    }

    /**
     * Save and load: buffer (with components), filter, amount and last rejection survive; a save with more slots than
     * configured keeps every item, trailing empty slots shrink back to the config, tampered entries never lose readable
     * items; client packets carry only the bounded summary.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationPersistence(GameTestHelper helper) {
        helper.setBlock(CENTER, inputState(Direction.NORTH));
        helper.setBlock(OTHER, outputState(Direction.NORTH));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(LONG_NAME_LENGTH)));

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseInputBlockEntity input = inputAt(helper, CENTER);
                    IItemHandler handler = handlerAt(helper, CENTER, null);
                    helper.assertTrue(ItemHandlerHelper.insertItem(handler, IRON.toStack(DROPPED_IRON), false).isEmpty(), "iron");
                    helper.assertTrue(ItemHandlerHelper.insertItem(handler, sword.copy(), false).isEmpty(), "sword");

                    CompoundTag saved = input.saveWithoutMetadata(registries);
                    helper.assertTrue(saved.contains(WarehouseStationBlockEntity.BUFFER_TAG), "the buffer is saved");
                    WarehouseInputBlockEntity loaded = freshInput(helper, input);
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.bufferedItems().totals(), input.bufferedItems().totals(), "buffer");
                    helper.assertValueEqual(loaded.bufferSlots(), input.bufferSlots(), "slot count");
                    helper.assertValueEqual(loaded.bufferedItems().count(ItemKey.of(sword)), 1L, "item with components");

                    input.onGoggleObserved();
                    CompoundTag update = input.getUpdateTag(registries);
                    helper.assertFalse(update.contains(WarehouseStationBlockEntity.BUFFER_TAG), "no buffer in client packets");
                    helper.assertTrue(update.sizeInBytes() < MAX_UPDATE_TAG_BYTES,
                            "update tag must stay small, but has " + update.sizeInBytes() + " bytes");
                    WarehouseInputBlockEntity client = freshInput(helper, input);
                    client.handleUpdateTag(update, registries);
                    helper.assertValueEqual(client.summary(), input.summary(), "goggle summary after client sync");
                    helper.assertValueEqual(client.summary().buffer().totalSlots(), input.bufferSlots(), "synced slots");

                    // More slots than configured (config lowered since the save): every item stays.
                    ListTag many = new ListTag();
                    for (int slot = 0; slot < OVERSIZED_SLOTS; slot++)
                        many.add(bufferEntry(slot, DIAMOND.save(registries), 1));
                    WarehouseInputBlockEntity grown = freshInput(helper, input);
                    grown.loadWithComponents(bufferTag(OVERSIZED_SLOTS, many), registries);
                    helper.assertValueEqual(grown.bufferSlots(), OVERSIZED_SLOTS, "grown to the saved slots");
                    helper.assertValueEqual(grown.bufferedItems().count(DIAMOND), (long) OVERSIZED_SLOTS, "all diamonds");
                    WarehouseInputBlockEntity reloaded = freshInput(helper, input);
                    reloaded.loadWithComponents(grown.saveWithoutMetadata(registries), registries);
                    helper.assertValueEqual(reloaded.bufferedItems().count(DIAMOND), (long) OVERSIZED_SLOTS, "second round trip");

                    ListTag trailing = new ListTag();
                    trailing.add(bufferEntry(EMPTY_TRAILING_SLOT, GOLD.save(registries), 1));
                    WarehouseInputBlockEntity shrunk = freshInput(helper, input);
                    shrunk.loadWithComponents(bufferTag(OVERSIZED_SLOTS, trailing), registries);
                    helper.assertValueEqual(shrunk.bufferSlots(),
                            Math.max(shrunk.configuredBufferSlots(), EMPTY_TRAILING_SLOT + 1), "empty slots shrink back");
                    helper.assertValueEqual(shrunk.bufferedItems().slot(EMPTY_TRAILING_SLOT).key(), GOLD, "slot kept");

                    ListTag tampered = new ListTag();
                    tampered.add(bufferEntry(0, IRON.save(registries), TAMPERED_IRON));
                    tampered.add(bufferEntry(0, GOLD.save(registries), TAMPERED_GOLD));
                    tampered.add(bufferEntry(-1, EMERALD.save(registries), TAMPERED_EMERALDS));
                    tampered.add(bufferEntry(FAR_SLOT, DIAMOND.save(registries), TAMPERED_DIAMONDS));
                    tampered.add(bufferEntry(1, IRON.save(registries), -4));
                    CompoundTag unknown = new CompoundTag();
                    unknown.putString("id", Wareworks.ID + ":does_not_exist");
                    tampered.add(bufferEntry(2, unknown, 4));
                    tampered.add(bufferEntry(3, StringTag.valueOf("garbage"), 4));
                    CompoundTag noCount = bufferEntry(4, IRON.save(registries), 1);
                    noCount.remove("Count");
                    tampered.add(noCount);
                    CompoundTag noSlot = bufferEntry(0, GOLD.save(registries), 1);
                    noSlot.putString("Slot", "zero");
                    tampered.add(noSlot);
                    WarehouseInputBlockEntity sanitized = freshInput(helper, input);
                    sanitized.loadWithComponents(bufferTag(-7, tampered), registries);
                    helper.assertValueEqual(sanitized.bufferedItems().count(IRON), (long) TAMPERED_IRON, "oversized count split");
                    helper.assertValueEqual(sanitized.bufferedItems().count(GOLD), (long) TAMPERED_GOLD + 1, "duplicate slots");
                    helper.assertValueEqual(sanitized.bufferedItems().count(EMERALD), (long) TAMPERED_EMERALDS, "negative slot");
                    helper.assertValueEqual(sanitized.bufferedItems().count(DIAMOND), (long) TAMPERED_DIAMONDS, "far slot");
                    for (SlotView<ItemKey> slot : sanitized.bufferedItems().slots())
                        helper.assertTrue(slot.isEmpty() || slot.count() <= slot.key().getMaxStackSize(),
                                "no oversized stack after loading: " + slot);
                    CompoundTag resaved = sanitized.saveWithoutMetadata(registries);
                    WarehouseInputBlockEntity again = freshInput(helper, input);
                    again.loadWithComponents(resaved, registries);
                    helper.assertValueEqual(again.bufferedItems().totals(), sanitized.bufferedItems().totals(),
                            "a sanitized buffer saves normally");

                    // Crafted counts (item block entity data, /data merge, structures, schematics) never allocate
                    // without bound: one load creates at most MAX_LOADED_STACKS stacks.
                    ItemKey unstackable = ItemKey.of(Items.DIAMOND_SWORD);
                    ListTag oversized = new ListTag();
                    oversized.add(bufferEntry(0, unstackable.save(registries), Integer.MAX_VALUE));
                    oversized.add(bufferEntry(1, IRON.save(registries), Integer.MAX_VALUE));
                    WarehouseInputBlockEntity bounded = freshInput(helper, input);
                    bounded.loadWithComponents(bufferTag(1, oversized), registries);
                    helper.assertValueEqual(bounded.bufferSlots(), StationBuffer.MAX_LOADED_STACKS, "slots bounded");
                    helper.assertValueEqual(bounded.bufferedItems().count(unstackable), (long) StationBuffer.MAX_LOADED_STACKS,
                            "the oversized entry is cut at the stack budget");
                    helper.assertValueEqual(bounded.bufferedItems().count(IRON), 0L, "nothing beyond the budget");
                    ListTag flood = new ListTag();
                    for (int i = 0; i < FLOOD_ENTRIES; i++)
                        flood.add(bufferEntry(-1, DIAMOND.save(registries), 1));
                    WarehouseInputBlockEntity flooded = freshInput(helper, input);
                    flooded.loadWithComponents(bufferTag(FLOOD_ENTRIES, flood), registries);
                    helper.assertValueEqual(flooded.bufferSlots(), StationBuffer.MAX_LOADED_STACKS, "slots bounded");
                    helper.assertValueEqual(flooded.bufferedItems().count(DIAMOND), (long) StationBuffer.MAX_LOADED_STACKS,
                            "entries beyond the budget are skipped");

                    // Output: filter, amount, last rejection and buffer.
                    WarehouseOutputBlockEntity output = outputAt(helper, OTHER);
                    FilteringBehaviour filter = filterOf(helper, output);
                    filter.setFilter(new ItemStack(Items.DIAMOND));
                    filter.count = FILTER_AMOUNT;
                    helper.assertValueEqual(output.submitRequest().rejection(), Optional.of(RequestRejection.NO_CONTROLLER),
                            "no aisle here");
                    helper.assertTrue(output.insert(GOLD.toStack(GOLD_IN_OUTPUT), false).isEmpty(), "gold");
                    WarehouseOutputBlockEntity outputCopy = freshOutput(helper, output);
                    outputCopy.loadWithComponents(output.saveWithoutMetadata(registries), registries);
                    helper.assertValueEqual(outputCopy.lastRejection(), Optional.of(RequestRejection.NO_CONTROLLER),
                            "last rejection");
                    helper.assertTrue(DIAMOND.matches(outputCopy.requestedItem()), "filter item");
                    helper.assertValueEqual(outputCopy.requestAmount(), FILTER_AMOUNT, "filter amount");
                    helper.assertValueEqual(outputCopy.bufferedItems().count(GOLD), (long) GOLD_IN_OUTPUT, "output buffer");
                    output.onGoggleObserved();
                    WarehouseOutputBlockEntity outputClient = freshOutput(helper, output);
                    outputClient.handleUpdateTag(output.getUpdateTag(registries), registries);
                    helper.assertValueEqual(outputClient.summary(), output.summary(), "output summary after client sync");
                    helper.assertValueEqual(outputClient.summary().lastRejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "synced rejection");
                    helper.assertTrue(DIAMOND.matches(outputClient.requestedItem()), "the filter reaches the client renderer");

                    WarehouseOutputBlockEntity empty = freshOutput(helper, output);
                    empty.loadWithComponents(new CompoundTag(), registries);
                    helper.assertTrue(empty.lastRejection().isEmpty(), "an empty tag has no rejection");
                    helper.assertFalse(empty.hasBufferedItems(), "an empty tag has no items");
                    CompoundTag malformed = new CompoundTag();
                    malformed.putString("OpenRequests", "many");
                    malformed.putLong("RequestedItems", -9);
                    malformed.putString("LastRejection", "EXPLODED");
                    malformed.putString("Buffer", "none");
                    helper.assertValueEqual(StationGoggleSummary.read(malformed), StationGoggleSummary.NONE, "malformed summary");
                    helper.assertTrue(RequestRejection.byName("EXPLODED").isEmpty(), "unknown rejection name");
                })
                .thenSucceed();
    }

    /**
     * Clipboard paste onto the request filter: a list, attribute or package filter is refused before Create takes a
     * filter item from a survival player's inventory; a concrete item pastes as usual, an "Exactly" row as "up to"; the
     * amount board offers only the "up to" row.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void outputClipboardFilter(GameTestHelper helper) {
        helper.setBlock(CENTER, outputState(Direction.NORTH));
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        WarehouseOutputBlockEntity output = outputAt(helper, CENTER);
        FilteringBehaviour filter = filterOf(helper, output);
        helper.assertTrue(filter instanceof RequestFilterBehaviour, "the output uses the request filter");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        List<ItemStack> filterItems = List.of(AllItems.FILTER.asStack(), AllItems.ATTRIBUTE_FILTER.asStack(),
                AllItems.PACKAGE_FILTER.asStack());
        for (ItemStack filterItem : filterItems)
            player.getInventory().add(filterItem.copy());

        for (ItemStack filterItem : filterItems) {
            CompoundTag clipboard = clipboardTag(filterItem.saveOptional(registries), FILTER_AMOUNT, 0);
            String name = filterItem.getHoverName().getString();
            helper.assertFalse(filter.readFromClipboard(registries, clipboard, player, Direction.UP, true),
                    "no paste offered for a " + name);
            helper.assertFalse(filter.readFromClipboard(registries, clipboard, player, Direction.UP, false),
                    "paste of a " + name + " refused");
            helper.assertValueEqual(player.getInventory().countItem(filterItem.getItem()), 1,
                    "the player keeps the " + name);
            helper.assertTrue(output.requestedItem().isEmpty(), "the filter slot stays empty");
        }

        CompoundTag diamonds = clipboardTag(DIAMOND.toStack(1).saveOptional(registries), FILTER_AMOUNT, 1);
        helper.assertTrue(filter.readFromClipboard(registries, diamonds, player, Direction.UP, false),
                "a concrete item pastes");
        helper.assertTrue(DIAMOND.matches(output.requestedItem()), "filter item pasted");
        helper.assertValueEqual(output.requestAmount(), FILTER_AMOUNT, "amount pasted");
        helper.assertTrue(filter.upTo, "an Exactly row pastes as up to");
        for (ItemStack filterItem : filterItems)
            helper.assertValueEqual(player.getInventory().countItem(filterItem.getItem()), 1, "nothing was taken");

        helper.assertValueEqual(filter.createBoard(player, null).rows().size(), 1, "the board has only the up-to row");
        filter.setValueSettings(player, new ValueSettings(1, BOARD_AMOUNT), false);
        helper.assertTrue(filter.upTo, "the board stores up to");
        helper.assertValueEqual(output.requestAmount(), BOARD_AMOUNT, "board amount");
        helper.assertValueEqual(filter.getCountLabelForValueBox().getString(), String.valueOf(BOARD_AMOUNT),
                "the value box shows the number");
        helper.succeed();
    }

    // --- membership and requests -----------------------------------------------------------------------------------

    /**
     * Stations at rack positions become controller members of their kind; misaligned stations are only counted; the
     * goggle assignment shows their addresses; rotating and breaking update the membership.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stationMembership(GameTestHelper helper) {
        buildStationAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.locationAt(absRack(helper, OUTPUT_RACK)),
                            Optional.of(LocationRecord.of(OUTPUT_RACK, LocationKind.OUTPUT)), "output record");
                    helper.assertValueEqual(controller.locationAt(absRack(helper, INPUT_RACK)),
                            Optional.of(LocationRecord.of(INPUT_RACK, LocationKind.INPUT)), "input record");
                    helper.assertTrue(controller.locationAt(absRack(helper, MISALIGNED_OUTPUT_RACK)).isEmpty(),
                            "a misaligned output is no member");
                    helper.assertTrue(controller.locationAt(absRack(helper, MISALIGNED_INPUT_RACK)).isEmpty(),
                            "a misaligned input is no member");
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "stock");

                    assertStationAssignment(helper, OUTPUT_RACK, AisleAssignment.assigned(StorageAddress.parse("A-01-00R")));
                    assertStationAssignment(helper, INPUT_RACK, AisleAssignment.assigned(StorageAddress.parse("A-01-02R")));
                    assertStationAssignment(helper, MISALIGNED_OUTPUT_RACK, AisleAssignment.MISALIGNED);
                    assertStationAssignment(helper, MISALIGNED_INPUT_RACK, AisleAssignment.MISALIGNED);
                    WarehouseOutputBlockEntity outside = placeOutsideOutput(helper);
                    outside.onGoggleObserved();
                    helper.assertValueEqual(outside.summary().assignment(), AisleAssignment.NONE, "outside the aisle");

                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().inputs(), 1, "controller goggles: inputs");
                    helper.assertValueEqual(controller.summary().outputs(), 1, "controller goggles: outputs");
                    helper.assertValueEqual(controller.summary().misaligned(), 2, "controller goggles: misaligned");

                    // Turned towards the aisle: the misaligned output joins.
                    helper.setBlock(relPos(MISALIGNED_OUTPUT_RACK), outputState(Direction.NORTH));
                })
                .thenWaitUntil(() -> assertMembers(helper, 1, 2, 1))
                .thenExecute(() -> helper.getLevel().destroyBlock(absRack(helper, INPUT_RACK), false))
                .thenWaitUntil(() -> assertMembers(helper, 0, 2, 1))
                .thenSucceed();
    }

    /**
     * Filter + redstone rising edge: the request is clamped to the stock (20 of 32 diamonds), holding the signal repeats
     * nothing, a second edge finds everything promised, new stock allows a second request, and goggles show both.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void outputRequestClampedToStock(GameTestHelper helper) {
        buildStationAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity output = outputAt(helper, relPos(OUTPUT_RACK));
                    FilteringBehaviour filter = filterOf(helper, output);
                    filter.setFilter(new ItemStack(Items.DIAMOND));
                    filter.count = REQUESTED_DIAMONDS; // after setFilter, which may clamp the count
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).requestsFor(absRack(helper, OUTPUT_RACK))
                        .size(), 1, "one request after the rising edge"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    WarehouseOutputBlockEntity output = outputAt(helper, relPos(OUTPUT_RACK));
                    BlockPos outputPos = absRack(helper, OUTPUT_RACK);
                    helper.assertBlockProperty(relPos(OUTPUT_RACK), WarehouseOutputBlock.POWERED, true);
                    RetrievalRequest<ItemKey, BlockPos> request = controller.requestsFor(outputPos).getFirst();
                    helper.assertValueEqual(request.key(), DIAMOND, "requested item");
                    helper.assertValueEqual(request.requested(), DIAMONDS_IN_STOCK, "clamped to the stock");
                    helper.assertValueEqual(request.remaining(), DIAMONDS_IN_STOCK, "nothing delivered yet");
                    helper.assertValueEqual(request.destination(), outputPos, "destination");
                    helper.assertTrue(output.lastRejection().isEmpty(), "accepted");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), 0L, "all diamonds promised");

                    // Holding the signal: further neighbour updates while powered request nothing.
                    helper.setBlock(BESIDE_OUTPUT, Blocks.STONE);
                    helper.setBlock(BESIDE_OUTPUT, Blocks.AIR);
                    helper.assertValueEqual(controller.openRequestCount(), 1, "no repeated request while powered");

                    // A second rising edge: everything is promised already.
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.AIR);
                    helper.assertBlockProperty(relPos(OUTPUT_RACK), WarehouseOutputBlock.POWERED, false);
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(output.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "all diamonds are promised to the first request");
                    helper.assertValueEqual(controller.openRequestCount(), 1, "still one request");

                    // More stock: the next edge grows the open request instead of queueing a second one
                    // (§7.2 merging, ADR-020), clamped to the new stock.
                    insertAll(helper, handlerAt(helper, chestOf(STORAGE_RACK), null), DIAMOND.toStack(DIAMONDS_ADDED));
                    helper.assertTrue(controller.refreshLocation(STORAGE_RACK), "stock refreshed");
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.AIR);
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(outputPos);
                    helper.assertValueEqual(requests.size(), 1, "the repeated pulse merged into the open request");
                    helper.assertValueEqual(requests.getFirst().id(), request.id(), "it kept its identity");
                    helper.assertValueEqual(requests.getFirst().remaining(), DIAMONDS_IN_STOCK + DIAMONDS_ADDED,
                            "the merged total is clamped to the new stock");
                    helper.assertTrue(output.lastRejection().isEmpty(), "an accepted request clears the rejection");
                    helper.assertValueEqual(controller.oldestOpenRequest().map(RetrievalRequest::id),
                            Optional.of(request.id()), "first in, first out: the merge kept the queue position");

                    output.onGoggleObserved();
                    helper.assertValueEqual(output.summary().openRequests(), 1, "output goggles: one merged request");
                    helper.assertValueEqual(output.summary().requestedItems(), (long) (DIAMONDS_IN_STOCK + DIAMONDS_ADDED),
                            "output goggles: requested items");
                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().openRequests(), 1, "controller goggles: open requests");
                })
                .thenSucceed();
    }

    /** No filter, item not in stock, no controller (outside or misaligned) and a full queue are refused with a reason. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void outputRequestRejections(GameTestHelper helper) {
        buildStationAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    WarehouseOutputBlockEntity output = outputAt(helper, relPos(OUTPUT_RACK));
                    BlockPos outputPos = absRack(helper, OUTPUT_RACK);

                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(output.lastRejection(), Optional.of(RequestRejection.NO_FILTER), "empty filter");
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.AIR);

                    FilteringBehaviour filter = filterOf(helper, output);
                    filter.setFilter(new ItemStack(Items.EMERALD));
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(output.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK), "emeralds");
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.AIR);
                    helper.assertValueEqual(controller.openRequestCount(), 0, "nothing requested");

                    WarehouseOutputBlockEntity outside = placeOutsideOutput(helper);
                    filterOf(helper, outside).setFilter(new ItemStack(Items.DIAMOND));
                    helper.setBlock(OUTSIDE_TRIGGER, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(outside.lastRejection(), Optional.of(RequestRejection.NO_CONTROLLER),
                            "an output outside the aisle");

                    WarehouseOutputBlockEntity misaligned = outputAt(helper, relPos(MISALIGNED_OUTPUT_RACK));
                    filterOf(helper, misaligned).setFilter(new ItemStack(Items.DIAMOND));
                    helper.assertValueEqual(misaligned.submitRequest().rejection(), Optional.of(RequestRejection.NO_CONTROLLER),
                            "a misaligned output");
                    helper.assertValueEqual(controller.request(absRack(helper, INPUT_RACK), DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "an input is no output");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "still nothing requested");

                    int max = WareworksConfig.maxOpenRequests();
                    int perOutput = Math.min(WareworksConfig.maxOpenRequestsPerOutput(), max);
                    if (perOutput > MAX_CAP_KEYS) {
                        helper.fail("the config needs " + perOutput + " item types to fill one output's request cap; "
                                + "the test provides " + MAX_CAP_KEYS);
                        return;
                    }
                    // Repeated requests for one item merge into the open one (§7.2), so a queue slot is taken per item
                    // type: distinct keys (a named diamond each) fill the caps a pulse clock used to fill.
                    List<ItemKey> capKeys = capKeys(perOutput);
                    IItemHandler chest = handlerAt(helper, chestOf(STORAGE_RACK), null);
                    for (ItemKey key : capKeys)
                        insertAll(helper, chest, key.toStack(CAP_KEY_AMOUNT));
                    insertAll(helper, chest, DIAMOND.toStack(max + 1));
                    helper.assertTrue(controller.refreshLocation(STORAGE_RACK), "stock refreshed");

                    // One output takes at most maxOpenRequestsPerOutput slots, one per item type.
                    for (int i = 0; i < perOutput; i++) {
                        RequestResult result = controller.request(outputPos, capKeys.get(i), 1);
                        helper.assertTrue(result.isAccepted(), "request " + i + " accepted: " + result);
                        helper.assertFalse(result.merged(), "a different item is a request of its own");
                    }
                    RequestResult repeated = controller.request(outputPos, capKeys.getFirst(), 1);
                    helper.assertTrue(repeated.isAccepted() && repeated.merged(),
                            "a repeated request merges whatever the caps say: " + repeated);
                    filter.setFilter(new ItemStack(Items.DIAMOND));
                    filter.count = 1;
                    helper.setBlock(OUTPUT_TRIGGER, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(output.lastRejection(), Optional.of(perOutput < max
                            ? RequestRejection.OUTPUT_FULL : RequestRejection.QUEUE_FULL), "this output is busy");
                    helper.assertValueEqual(controller.requestsFor(outputPos).size(), perOutput, "capped per output");

                    // Further outputs fill the queue up to maxOpenRequests.
                    int outputsNeeded = (max + perOutput - 1) / perOutput;
                    if (outputsNeeded > EXTRA_OUTPUT_RACKS.size()) {
                        helper.fail("the config needs " + outputsNeeded + " outputs to fill the queue; the test has "
                                + EXTRA_OUTPUT_RACKS.size() + " spare rack positions");
                        return;
                    }
                    int open = perOutput;
                    for (int i = 0; open < max; i++) {
                        RackPosition rack = EXTRA_OUTPUT_RACKS.get(i);
                        helper.setBlock(relPos(rack), outputState(towardsAisle(rack)));
                        for (int n = 0; n < perOutput && open < max; n++, open++) {
                            RequestResult result = controller.request(absRack(helper, rack), capKeys.get(n), 1);
                            helper.assertTrue(result.isAccepted(), "request " + open + " accepted: " + result);
                        }
                    }
                    RackPosition spare = EXTRA_OUTPUT_RACKS.get(outputsNeeded - 1);
                    helper.setBlock(relPos(spare), outputState(towardsAisle(spare)));
                    helper.assertValueEqual(controller.request(absRack(helper, spare), DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.QUEUE_FULL), "queue full for an output without requests");
                    helper.assertValueEqual(controller.openRequestCount(), max, "capped at maxOpenRequests");
                    helper.assertTrue(controller.availableStock(DIAMOND) > 0, "stock was left, the cap refused it");
                })
                .thenSucceed();
    }

    /**
     * A request for an output that is removed before the controller ever recorded it (placed, pulsed and broken before
     * the controller's next tick, or while the controller did not tick) is cancelled, so it never blocks stock and a
     * queue slot.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void outputRequestOrphanCancelled(GameTestHelper helper) {
        buildStationAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.setBlock(relPos(ORPHAN_OUTPUT_RACK), outputState(towardsAisle(ORPHAN_OUTPUT_RACK)));
                    BlockPos orphan = absRack(helper, ORPHAN_OUTPUT_RACK);
                    helper.assertTrue(controller.request(orphan, DIAMOND, FIRST_REQUEST).isAccepted(),
                            "accepted by the live check");
                    helper.assertTrue(controller.locationAt(orphan).isEmpty(), "the output is no record yet");
                    helper.getLevel().destroyBlock(orphan, false);
                    helper.assertValueEqual(controller.openRequestCount(), 1, "still queued right after the removal");
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertValueEqual(controller.openRequestCount(), 0, "the orphaned request was cancelled");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), (long) DIAMONDS_IN_STOCK,
                            "its stock can be requested again");
                })
                .thenSucceed();
    }

    /**
     * Requests are saved with the controller (ids, order, remaining, destination), malformed entries are skipped,
     * deliveries and cancellations work, a removed output cancels its requests and losing the dock clears the queue.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stationRequestPersistence(GameTestHelper helper) {
        buildStationAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    BlockPos outputPos = absRack(helper, OUTPUT_RACK);
                    // Two open requests at one output need two item types: a repeated request for the same item is
                    // merged into the open one (§7.2), which the queue saves as one entry.
                    ItemKey secondKey = capKeys(1).getFirst();
                    insertAll(helper, handlerAt(helper, chestOf(STORAGE_RACK), null), secondKey.toStack(SECOND_REQUEST));
                    helper.assertTrue(controller.refreshLocation(STORAGE_RACK), "stock refreshed");
                    RetrievalRequest<ItemKey, BlockPos> first = controller.request(outputPos, DIAMOND, FIRST_REQUEST)
                            .request().orElse(null);
                    RetrievalRequest<ItemKey, BlockPos> second = controller.request(outputPos, secondKey, SECOND_REQUEST)
                            .request().orElse(null);
                    if (first == null || second == null) {
                        helper.fail("both requests must be accepted");
                        return;
                    }
                    helper.assertValueEqual(controller.deliverRequest(first.id(), DELIVERED), DELIVERED, "delivered");
                    helper.assertValueEqual(controller.deliverRequest(UUID.randomUUID(), DELIVERED), 0, "unknown id");

                    CompoundTag saved = controller.saveWithoutMetadata(registries);
                    WarehouseControllerBlockEntity loaded = freshController(helper, controller);
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.openRequests(), controller.openRequests(), "requests round trip");
                    helper.assertValueEqual(loaded.openRequests().getFirst().remaining(), FIRST_REQUEST - DELIVERED,
                            "remaining after a delivery");
                    helper.assertValueEqual(loaded.requestsFor(outputPos).size(), 2, "absolute destinations restored");
                    helper.assertFalse(controller.getUpdateTag(registries).contains("Requests"), "requests are not synced");

                    CompoundTag broken = saved.copy();
                    ListTag requests = broken.getList("Requests", Tag.TAG_COMPOUND);
                    CompoundTag noId = requests.getCompound(0).copy();
                    noId.remove("Id");
                    requests.add(noId);
                    CompoundTag zero = requests.getCompound(0).copy();
                    zero.putUUID("Id", UUID.randomUUID());
                    zero.putInt("Requested", 0);
                    requests.add(zero);
                    CompoundTag tooMuch = requests.getCompound(1).copy();
                    tooMuch.putUUID("Id", UUID.randomUUID());
                    tooMuch.putInt("Remaining", FIRST_REQUEST * FIRST_REQUEST);
                    requests.add(tooMuch);
                    CompoundTag unknownItem = requests.getCompound(1).copy();
                    unknownItem.putUUID("Id", UUID.randomUUID());
                    CompoundTag unknown = new CompoundTag();
                    unknown.putString("id", Wareworks.ID + ":does_not_exist");
                    unknownItem.put("Item", unknown);
                    requests.add(unknownItem);
                    CompoundTag badDestination = requests.getCompound(1).copy();
                    badDestination.putUUID("Id", UUID.randomUUID());
                    badDestination.putIntArray("Destination", new int[] {1, 2});
                    requests.add(badDestination);
                    requests.add(requests.getCompound(0).copy()); // duplicate id
                    WarehouseControllerBlockEntity sanitized = freshController(helper, controller);
                    sanitized.loadWithComponents(broken, registries);
                    helper.assertValueEqual(sanitized.openRequests().size(), 3, "two saved plus the clamped one");
                    helper.assertValueEqual(sanitized.openRequests().get(2).remaining(), SECOND_REQUEST,
                            "remaining clamped to requested");

                    helper.assertValueEqual(controller.cancelRequest(second.id()).map(RetrievalRequest::id),
                            Optional.of(second.id()), "cancelled");
                    helper.assertValueEqual(controller.openRequestCount(), 1, "one left");
                    helper.getLevel().destroyBlock(outputPos, false);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = controllerAt(helper);
                    helper.assertTrue(controller.outputStations().isEmpty(), "the output left the aisle");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "its requests were cancelled");
                })
                .thenExecute(() -> helper.setBlock(relPos(OUTPUT_RACK), outputState(Direction.NORTH)))
                .thenWaitUntil(() -> assertMembers(helper, 1, 1, 2))
                .thenExecute(() -> {
                    helper.assertTrue(controllerAt(helper).request(absRack(helper, OUTPUT_RACK), DIAMOND, 1).isAccepted(),
                            "a new output requests again");
                    helper.getLevel().destroyBlock(helper.absolutePos(DOCK), false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(controllerAt(helper).status(), ControllerStatus.NO_DOCK,
                        "dock broken"))
                .thenExecute(() -> helper.assertValueEqual(controllerAt(helper).openRequestCount(), 0,
                        "without an aisle the queue is cleared"))
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Dock, rails, controller, a chest with diamonds behind an interface, aligned and misaligned stations. */
    private static void buildStationAisle(GameTestHelper helper) {
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            helper.setBlock(DOCK.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, AISLE));
        helper.setBlock(chestOf(STORAGE_RACK), Blocks.CHEST);
        insertAll(helper, handlerAt(helper, chestOf(STORAGE_RACK), null), DIAMOND.toStack(DIAMONDS_IN_STOCK));
        helper.setBlock(relPos(STORAGE_RACK), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, RELATIVE.sideDirection(STORAGE_RACK.side())));
        helper.setBlock(relPos(OUTPUT_RACK), outputState(towardsAisle(OUTPUT_RACK)));
        helper.setBlock(relPos(INPUT_RACK), inputState(towardsAisle(INPUT_RACK)));
        helper.setBlock(relPos(MISALIGNED_OUTPUT_RACK), outputState(RELATIVE.sideDirection(MISALIGNED_OUTPUT_RACK.side())));
        helper.setBlock(relPos(MISALIGNED_INPUT_RACK), inputState(RELATIVE.sideDirection(MISALIGNED_INPUT_RACK.side())));
    }

    private static void assertMembers(GameTestHelper helper, int inputs, int outputs, int misaligned) {
        WarehouseControllerBlockEntity controller = controllerAt(helper);
        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller status");
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.storageLocations().size(), 1, "storage locations");
        helper.assertValueEqual(controller.inputStations().size(), inputs, "input stations");
        helper.assertValueEqual(controller.outputStations().size(), outputs, "output stations");
        helper.assertValueEqual(controller.misalignedCount(), misaligned, "misaligned members");
    }

    private static void assertStationAssignment(GameTestHelper helper, RackPosition rack, AisleAssignment expected) {
        WarehouseStationBlockEntity station = stationAt(helper, relPos(rack));
        station.onGoggleObserved();
        helper.assertValueEqual(station.summary().assignment(), expected, "assignment at " + rack);
    }

    private static WarehouseOutputBlockEntity placeOutsideOutput(GameTestHelper helper) {
        helper.setBlock(OUTSIDE_OUTPUT, outputState(Direction.NORTH));
        return outputAt(helper, OUTSIDE_OUTPUT);
    }

    /**
     * {@code count} distinct item keys: diamonds that differ only in their custom name, so each is its own
     * {@link ItemKey} and takes its own request slot, while they all stack into one chest slot per key. Repeated
     * requests for one key merge ({@code docs/warehouse-system.md} §7.2), so filling a request cap needs item types.
     */
    private static List<ItemKey> capKeys(int count) {
        List<ItemKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("wareworks cap key " + i));
            keys.add(ItemKey.of(stack));
        }
        return keys;
    }

    private static Direction towardsAisle(RackPosition rack) {
        return RELATIVE.sideDirection(rack.side()).getOpposite();
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

    private static BlockState place(GameTestHelper helper, Player player, WarehouseStationBlock<?> block, BlockPos floor) {
        BlockPos absolute = helper.absolutePos(floor);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, 0.5, 0), Direction.UP, absolute, false);
        return block.getStateForPlacement(new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new ItemStack(block), hit));
    }

    private static BlockState inputState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_INPUT.getDefaultState().setValue(WarehouseInputBlock.FACING, facing);
    }

    private static BlockState outputState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState().setValue(WarehouseOutputBlock.FACING, facing);
    }

    private static WarehouseControllerBlockEntity controllerAt(GameTestHelper helper) {
        WarehouseControllerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(helper.getLevel(), helper.absolutePos(CONTROLLER));
        if (be == null)
            helper.fail("missing warehouse controller block entity", CONTROLLER);
        return be;
    }

    private static WarehouseInputBlockEntity inputAt(GameTestHelper helper, BlockPos pos) {
        WarehouseInputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse input block entity", pos);
        return be;
    }

    private static WarehouseOutputBlockEntity outputAt(GameTestHelper helper, BlockPos pos) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse output block entity", pos);
        return be;
    }

    private static WarehouseStationBlockEntity stationAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof WarehouseStationBlockEntity station)) {
            helper.fail("missing warehouse station block entity", pos);
            return null;
        }
        return station;
    }

    private static HopperBlockEntity hopperAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof HopperBlockEntity hopper)) {
            helper.fail("missing hopper", pos);
            return null;
        }
        return hopper;
    }

    private static FilteringBehaviour filterOf(GameTestHelper helper, WarehouseOutputBlockEntity output) {
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            helper.fail("the output has no request filter");
        return filter;
    }

    private static WarehouseInputBlockEntity freshInput(GameTestHelper helper, WarehouseInputBlockEntity input) {
        WarehouseInputBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_INPUT.create(input.getBlockPos(),
                input.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse input");
        return copy;
    }

    private static WarehouseOutputBlockEntity freshOutput(GameTestHelper helper, WarehouseOutputBlockEntity output) {
        WarehouseOutputBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.create(output.getBlockPos(),
                output.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse output");
        return copy;
    }

    private static WarehouseControllerBlockEntity freshController(GameTestHelper helper,
                                                                  WarehouseControllerBlockEntity controller) {
        WarehouseControllerBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .create(controller.getBlockPos(), controller.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse controller");
        return copy;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos, Direction side) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), side);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    private static long snapshotCount(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = handlerAt(helper, pos, null);
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    private static void insertAll(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
        helper.assertTrue(rest.isEmpty(), "inventory rejected " + rest);
    }

    private static long dropped(GameTestHelper helper, BlockPos pos, Item item) {
        long total = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, DROP_RADIUS)) {
            if (entity.getItem().is(item))
                total += entity.getItem().getCount();
        }
        return total;
    }

    private static CompoundTag bufferEntry(int slot, Tag item, int count) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", slot);
        entry.put("Item", item);
        entry.putInt("Count", count);
        return entry;
    }

    /** Clipboard data of a Create filter slot ({@code FilteringBehaviour#writeToClipboard}). */
    private static CompoundTag clipboardTag(Tag filter, int value, int row) {
        CompoundTag tag = new CompoundTag();
        tag.put(RequestFilterBehaviour.CLIPBOARD_FILTER_TAG, filter);
        tag.putInt("Value", value);
        tag.putInt("Row", row);
        return tag;
    }

    /** A block entity tag holding only a station buffer save. */
    private static CompoundTag bufferTag(int size, ListTag items) {
        CompoundTag buffer = new CompoundTag();
        buffer.putInt("Size", size);
        buffer.put("Items", items);
        CompoundTag tag = new CompoundTag();
        tag.put(WarehouseStationBlockEntity.BUFFER_TAG, buffer);
        return tag;
    }

    /** Counts one item in a handler (for foreign inventories such as the test chest). */
    private static final class ItemHandlerSnapshotsCount {
        private ItemHandlerSnapshotsCount() {
        }

        static long of(IItemHandler handler, Item item) {
            long total = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(item))
                    total += stack.getCount();
            }
            return total;
        }
    }
}
