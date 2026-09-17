package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StationGoggleSummary;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.TerminalStatus;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the warehouse terminal ({@code docs/warehouse-system.md} §3.4, ADR-018): registration and placement, the
 * extract-only capability, drops and {@code Clearable}, persistence and client sync, aisle membership as an
 * output-style member, and the server-side screen API (stock snapshot, status, request) including the full
 * request → retrieval job → delivery loop with the item conservation invariant.
 * <p>
 * Single-block tests use the {@code empty_7x5x7} floor. Aisle tests build the {@link AisleFixture} aisle on
 * {@code aisle_16x10x7}: controller x = 0, dock x = 1 (z = 3, aisle along +X), {@value #RAILS} rails, a chest with
 * {@value #DIAMONDS_IN_STOCK} diamonds behind an interface at rack position 1 left, and an aligned terminal at 0 right.
 * {@link #terminalPortOwnerOnASharedRackPlane} needs a second, parallel aisle and therefore the wider
 * {@code aisle_pair_16x10x13} floor, with the two aisle lines only two blocks apart so that they share a rack plane.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseTerminalGameTests {
    // --- single block layout (empty_7x5x7) ---
    private static final BlockPos CENTER = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos BELOW_CENTER = new BlockPos(3, FLOOR_Y, 3);
    private static final BlockPos BESIDE_BELOW_CENTER = new BlockPos(4, FLOOR_Y, 3);
    private static final BlockPos CLEARED = new BlockPos(5, BASE_Y, 5);

    // --- aisle layout (aisle_16x10x7) ---
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    /** A second terminal on the other rack plane, so the intake port has to end up on the other side (M10). */
    private static final RackPosition LEFT_TERMINAL_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition MISALIGNED_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final BlockPos OUTSIDE_TERMINAL = new BlockPos(12, BASE_Y, 0);

    // --- two parallel aisles sharing a rack plane (aisle_pair_16x10x13, M10 review) ---
    /** Second aisle line, two blocks from the first, so the plane between them is a rack position of both aisles. */
    private static final int PARALLEL_AISLE_Z = AISLE_Z + 2;
    /** That shared plane, as the first aisle addresses it (RIGHT) and as the second one does (LEFT): one block. */
    private static final RackPosition SHARED_RACK_RIGHT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition SHARED_RACK_LEFT = new RackPosition(2, 0, Side.LEFT);

    private static final int STACK = 64;
    private static final int DIAMONDS_IN_STOCK = 20;
    private static final int REQUESTED = 10;
    private static final int OVER_REQUEST = 50;
    private static final int DELIVERED_GOLD = 24;
    private static final int DROPPED_IRON = 100;
    private static final int CLEARED_EMERALDS = 16;
    private static final int EXTRACTED = 10;
    /** Items per item type used to fill the per-station request cap ({@link #capKeys}). */
    private static final int CAP_KEY_AMOUNT = 8;
    /** Item types {@link #capKeys} can provide without running out of chest slots. */
    private static final int MAX_CAP_KEYS = 20;
    private static final int FAR_AWAY_BLOCKS = 40;
    private static final int CENSUS_TICKS = 200;
    private static final int TIMEOUT_TICKS = 1200;
    /** Two retrieval jobs one after the other take longer than one. */
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Items each of the two terminals asks for in {@link #terminalDeliveryFromBothAisleSides}. */
    private static final int SIDE_REQUEST = 6;
    /** Ticks a settled aisle is left alone to prove that nothing keeps rewriting a block state (M10). */
    private static final int SETTLE_TICKS = 40;
    private static final int HOPPER_TIMEOUT_TICKS = 300;
    private static final double DROP_RADIUS = 1.0;
    private static final float[] PLAYER_YAWS = {0f, 90f, 180f, 270f};

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);

    private WarehouseTerminalGameTests() {
    }

    // --- registration ----------------------------------------------------------------------------------------------

    /**
     * Tags, block entity type, no redstone conduction, the wrench turning the screen (never the intake port, never onto
     * it), placement (the screen faces the player, the port the other way) and a stable extract-only capability
     * instance for every side.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalRegistration(GameTestHelper helper) {
        BlockState terminal = terminalState(Direction.NORTH);
        helper.assertTrue(terminal.is(BlockTags.MINEABLE_WITH_PICKAXE), "mineable with a pickaxe");
        helper.assertTrue(terminal.is(WareworksTags.NON_MOVABLE), "create:non_movable");
        helper.assertTrue(terminal.is(WareworksTags.RELOCATION_NOT_SUPPORTED), "c:relocation_not_supported");
        helper.assertFalse(terminal.isRedstoneConductor(helper.getLevel(), helper.absolutePos(CENTER)),
                "no redstone conductor");
        helper.assertTrue(WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.get().isValid(terminal), "block entity type");
        // The wrench turns the screen a quarter clockwise and leaves the intake port alone; three clicks visit the
        // three faces that are not the port and come back to the start (ADR-022).
        BlockState turned = terminal;
        Set<Direction> screens = new HashSet<>();
        for (int click = 0; click < TerminalDisplaySide.values().length; click++) {
            turned = WareworksBlocks.WAREHOUSE_TERMINAL.get().getRotatedBlockState(turned, Direction.UP);
            helper.assertValueEqual(turned.getValue(WarehouseStationBlock.FACING), Direction.NORTH,
                    "the wrench never moves the intake port, click " + click);
            Direction screen = screenOf(turned);
            helper.assertFalse(screen == Direction.NORTH, "the screen never lands on the intake port");
            helper.assertTrue(screens.add(screen), "click " + click + " reaches a new face: " + screen);
        }
        helper.assertValueEqual(turned, terminal, "three wrench clicks come back to the start");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (float yaw : PLAYER_YAWS) {
            player.setYRot(yaw);
            BlockState placed = place(helper, player, new BlockPos(5, FLOOR_Y, 5));
            helper.assertValueEqual(placed.getValue(WarehouseTerminalBlock.DISPLAY), TerminalDisplaySide.BACK,
                    "the screen starts opposite the port, yaw " + yaw);
            helper.assertValueEqual(screenOf(placed), Direction.fromYRot(yaw).getOpposite(),
                    "the screen faces the player, yaw " + yaw);
            helper.assertValueEqual(placed.getValue(WarehouseStationBlock.FACING), Direction.fromYRot(yaw),
                    "the intake port starts away from the player, yaw " + yaw);
        }

        helper.setBlock(CENTER, terminal);
        WarehouseTerminalBlockEntity be = terminalAt(helper, CENTER);
        IItemHandler unsided = handlerAt(helper, CENTER, null);
        helper.assertTrue(unsided == be.externalHandler(), "the capability is the terminal's view");
        for (Direction side : Direction.values())
            helper.assertTrue(handlerAt(helper, CENTER, side) == unsided, "same view from " + side);
        helper.assertValueEqual(be.bufferSlots(), WareworksConfig.terminalBufferSlots(), "configured buffer slots");
        helper.assertValueEqual(be.locationKind(), LocationKind.OUTPUT, "an output-style member (ADR-018)");
        // Without an aisle the screen API answers empty instead of throwing.
        helper.assertTrue(be.stockSnapshot().isEmpty(), "no stock without an aisle");
        helper.assertValueEqual(be.status(), TerminalStatus.NONE, "no status without an aisle");
        helper.succeed();
    }

    /** Automation can pull delivered items out but can never push anything in; the crane's insert API fills the buffer. */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = HOPPER_TIMEOUT_TICKS)
    public static void terminalExtractOnly(GameTestHelper helper) {
        helper.setBlock(CENTER, terminalState(Direction.NORTH));
        WarehouseTerminalBlockEntity terminal = terminalAt(helper, CENTER);
        IItemHandler handler = handlerAt(helper, CENTER, Direction.DOWN);

        ItemStack offered = IRON.toStack(EXTRACTED);
        helper.assertValueEqual(handler.insertItem(0, offered, false).getCount(), EXTRACTED, "insertion refused");
        helper.assertValueEqual(ItemHandlerHelper.insertItem(handler, offered.copy(), false).getCount(), EXTRACTED,
                "insertion refused in every slot");
        helper.assertFalse(handler.isItemValid(0, offered), "no item is valid from outside");
        helper.assertFalse(terminal.hasBufferedItems(), "still empty");

        ItemStack delivery = GOLD.toStack(DELIVERED_GOLD);
        helper.assertTrue(terminal.insert(delivery, true).isEmpty(), "simulated crane insert fits");
        helper.assertFalse(terminal.hasBufferedItems(), "simulation changes nothing");
        helper.assertTrue(terminal.insert(delivery, false).isEmpty(), "crane insert fits");
        delivery.setCount(1);
        helper.assertValueEqual(terminal.bufferedItems().count(GOLD), (long) DELIVERED_GOLD,
                "the caller's stack is not aliased");
        helper.assertValueEqual(handler.extractItem(0, STACK, true).getCount(), DELIVERED_GOLD, "simulated extraction");
        helper.assertValueEqual(terminal.bufferedItems().count(GOLD), (long) DELIVERED_GOLD, "simulation changes nothing");

        helper.setBlock(BESIDE_BELOW_CENTER, Blocks.CHEST);
        helper.setBlock(BELOW_CENTER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(countIn(helper, BESIDE_BELOW_CENTER, Items.GOLD_INGOT),
                        (long) DELIVERED_GOLD, "the hopper below pulled the gold into the chest"))
                .thenExecute(() -> helper.assertValueEqual(terminalAt(helper, CENTER).bufferedItems().count(GOLD), 0L,
                        "the gold left the terminal"))
                .thenSucceed();
    }

    /** Breaking a terminal drops its whole buffer (counts and components conserved); Clearable empties without drops. */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalDropsBuffer(GameTestHelper helper) {
        helper.setBlock(CENTER, terminalState(Direction.NORTH));
        helper.setBlock(CLEARED, terminalState(Direction.NORTH));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks terminal sword"));

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminalAt(helper, CENTER);
                    helper.assertTrue(terminal.insert(IRON.toStack(DROPPED_IRON), false).isEmpty(), "iron");
                    helper.assertTrue(terminal.insert(sword.copy(), false).isEmpty(), "sword");
                    WarehouseTerminalBlockEntity cleared = terminalAt(helper, CLEARED);
                    helper.assertTrue(cleared.insert(EMERALD.toStack(CLEARED_EMERALDS), false).isEmpty(), "emeralds");

                    helper.getLevel().destroyBlock(helper.absolutePos(CENTER), true);
                    helper.assertBlockPresent(Blocks.AIR, CENTER);
                    helper.assertTrue(terminal.isRemoved(), "block entity removed");
                    helper.assertFalse(terminal.hasBufferedItems(), "the old buffer is empty");
                    helper.assertValueEqual(dropped(helper, CENTER, Items.IRON_INGOT), (long) DROPPED_IRON,
                            "all iron dropped");
                    List<ItemEntity> swords = helper.getEntities(EntityType.ITEM, CENTER, DROP_RADIUS).stream()
                            .filter(entity -> entity.getItem().is(Items.DIAMOND_SWORD)).toList();
                    helper.assertValueEqual(swords.size(), 1, "one sword dropped");
                    helper.assertTrue(ItemStack.isSameItemSameComponents(swords.getFirst().getItem(), sword),
                            "the sword keeps its components");
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_TERMINAL.asItem(), CENTER, DROP_RADIUS);

                    Clearable.tryClear(cleared);
                    helper.assertFalse(cleared.hasBufferedItems(), "cleared");
                    helper.setBlock(CLEARED, Blocks.AIR);
                    helper.assertValueEqual(dropped(helper, CLEARED, Items.EMERALD), 0L,
                            "a cleared terminal drops nothing");
                })
                .thenSucceed();
    }

    /** Save and load: buffer (with components) and last rejection survive; client packets carry only the summary. */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalPersistence(GameTestHelper helper) {
        helper.setBlock(CENTER, terminalState(Direction.NORTH));

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = terminalAt(helper, CENTER);
                    ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                    sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks terminal sword"));
                    helper.assertTrue(terminal.insert(GOLD.toStack(DELIVERED_GOLD), false).isEmpty(), "gold");
                    helper.assertTrue(terminal.insert(sword.copy(), false).isEmpty(), "sword");

                    // A request without an aisle is refused and the reason is saved.
                    Player player = playerAt(helper, CENTER);
                    helper.assertValueEqual(terminal.requestFromTerminal(player, DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "no aisle here");

                    CompoundTag saved = terminal.saveWithoutMetadata(registries);
                    helper.assertTrue(saved.contains(WarehouseStationBlockEntity.BUFFER_TAG), "the buffer is saved");
                    WarehouseTerminalBlockEntity loaded = detached(helper, terminal);
                    loaded.loadWithComponents(saved, registries);
                    helper.assertValueEqual(loaded.bufferedItems().totals(), terminal.bufferedItems().totals(), "buffer");
                    helper.assertValueEqual(loaded.bufferSlots(), terminal.bufferSlots(), "slot count");
                    helper.assertValueEqual(loaded.bufferedItems().count(ItemKey.of(sword)), 1L, "item with components");
                    helper.assertValueEqual(loaded.lastRejection(), Optional.of(RequestRejection.NO_CONTROLLER),
                            "last rejection");

                    terminal.onGoggleObserved();
                    CompoundTag update = terminal.getUpdateTag(registries);
                    helper.assertFalse(update.contains(WarehouseStationBlockEntity.BUFFER_TAG),
                            "no buffer in client packets");
                    WarehouseTerminalBlockEntity client = detached(helper, terminal);
                    client.handleUpdateTag(update, registries);
                    helper.assertValueEqual(client.summary(), terminal.summary(), "goggle summary after client sync");
                    helper.assertValueEqual(client.summary().lastRejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "synced rejection");

                    WarehouseTerminalBlockEntity empty = detached(helper, terminal);
                    empty.loadWithComponents(new CompoundTag(), registries);
                    helper.assertTrue(empty.lastRejection().isEmpty(), "an empty tag has no rejection");
                    helper.assertFalse(empty.hasBufferedItems(), "an empty tag has no items");
                    helper.assertValueEqual(StationGoggleSummary.read(new CompoundTag()), StationGoggleSummary.NONE,
                            "an empty summary tag");
                })
                .thenSucceed();
    }

    // --- membership ------------------------------------------------------------------------------------------------

    /**
     * An aligned terminal is a member of kind {@link LocationKind#OUTPUT} with an address; the controller counts it
     * among its outputs; a terminal turned away from the aisle is only counted as misaligned, and one outside every
     * aisle belongs to none.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalMembership(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);
        // A terminal turned away from the aisle: the facing rule of an output-style member is violated.
        helper.setBlock(fixture.rackPos(MISALIGNED_RACK), terminalState(fixture.sideDirection(MISALIGNED_RACK)));

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = fixture.controller();
                    helper.assertValueEqual(controller.locationAt(fixture.absoluteRackPos(TERMINAL_RACK)),
                            Optional.of(LocationRecord.of(TERMINAL_RACK, LocationKind.OUTPUT)), "terminal record");
                    helper.assertTrue(controller.locationAt(fixture.absoluteRackPos(MISALIGNED_RACK)).isEmpty(),
                            "a misaligned terminal is no member");
                    helper.assertValueEqual(controller.misalignedCount(), 1, "misaligned members");

                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    terminal.onGoggleObserved();
                    helper.assertValueEqual(terminal.summary().assignment(),
                            AisleAssignment.assigned(StorageAddress.parse("A-01-00R")), "terminal address");
                    WarehouseTerminalBlockEntity misaligned = fixture.terminalAt(MISALIGNED_RACK);
                    misaligned.onGoggleObserved();
                    helper.assertValueEqual(misaligned.summary().assignment(), AisleAssignment.MISALIGNED,
                            "misaligned assignment");

                    helper.setBlock(OUTSIDE_TERMINAL, terminalState(Direction.NORTH));
                    WarehouseTerminalBlockEntity outside = terminalAt(helper, OUTSIDE_TERMINAL);
                    outside.onGoggleObserved();
                    helper.assertValueEqual(outside.summary().assignment(), AisleAssignment.NONE,
                            "outside every aisle");

                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().outputs(), 1,
                            "controller goggles count the terminal as an output");
                    TerminalStatus status = terminal.status();
                    helper.assertTrue(status.isReady(), "terminal status ready: " + status);
                    helper.assertValueEqual(status.aisleLetter(), 'A', "aisle letter");
                    helper.assertValueEqual(status.totalItems(), (long) DIAMONDS_IN_STOCK, "items stored");
                    helper.assertValueEqual(status.itemTypes(), 1, "item types");
                    helper.assertValueEqual(outside.status(), TerminalStatus.NONE, "no status outside an aisle");
                })
                .thenSucceed();
    }

    // --- requests --------------------------------------------------------------------------------------------------

    /**
     * The full loop: the screen's stock snapshot, a request from it, the resulting {@code RETRIEVE} job to the terminal
     * and the crane's delivery into its buffer, with the item conservation invariant checked on every tick of the job.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalRequestEndToEnd(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    List<TerminalStockEntry> stock = terminal.stockSnapshot();
                    helper.assertValueEqual(stock.size(), 1, "one item type on the screen");
                    TerminalStockEntry entry = stock.getFirst();
                    helper.assertValueEqual(entry.key(), DIAMOND, "stock item");
                    helper.assertValueEqual(entry.total(), (long) DIAMONDS_IN_STOCK, "stock total");
                    helper.assertValueEqual(entry.available(), (long) DIAMONDS_IN_STOCK, "nothing promised yet");
                    helper.assertValueEqual(entry.reserved(), 0L, "nothing reserved yet");

                    RequestResult result = terminal.requestFromTerminal(playerAt(helper, fixture.rackPos(TERMINAL_RACK)),
                            DIAMOND, REQUESTED);
                    helper.assertTrue(result.isAccepted(), "request accepted: " + result);
                    RetrievalRequest<ItemKey, BlockPos> request = result.request().orElseThrow();
                    helper.assertValueEqual(request.requested(), REQUESTED, "granted amount");
                    helper.assertValueEqual(request.key(), DIAMOND, "requested item");
                    helper.assertValueEqual(request.destination(), fixture.absoluteRackPos(TERMINAL_RACK), "destination");
                    helper.assertTrue(terminal.lastRejection().isEmpty(), "accepted, so no rejection");
                    // The screen now shows the promised amount as reserved.
                    helper.assertValueEqual(terminal.stockSnapshot().getFirst().reserved(), (long) REQUESTED,
                            "reserved for the open request");
                })
                // A terminal request is a normal retrieval job of the aisle's crane.
                .thenWaitUntil(() -> {
                    TransportJob<ItemKey, RackPosition> job = fixture.dock().currentJob().orElse(null);
                    helper.assertTrue(job != null, "the crane has a job");
                    helper.assertValueEqual(job.type(), JobType.RETRIEVE, "job type");
                    helper.assertValueEqual(job.target(), TERMINAL_RACK, "job target");
                    helper.assertValueEqual(job.targetKind(), LocationKind.OUTPUT, "output-style target");
                    helper.assertValueEqual(job.source(), STORAGE_RACK, "job source");
                })
                .thenExecuteFor(CENSUS_TICKS, () -> ItemCensus.assertEquals(helper, conserved, "during the terminal job"))
                .thenWaitUntil(() -> helper.assertValueEqual(fixture.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) REQUESTED, "delivered into the terminal buffer"))
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved, "after the delivery");
                    helper.assertValueEqual(fixture.storedAt(STORAGE_RACK, DIAMOND),
                            (long) (DIAMONDS_IN_STOCK - REQUESTED), "left in the chest");
                    helper.assertTrue(fixture.controller().requestsFor(fixture.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                            "the request is finished");
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    TerminalStatus status = terminal.status();
                    helper.assertValueEqual(status.requestsHere(), 0, "no open request here");
                    helper.assertValueEqual(status.totalItems(), (long) (DIAMONDS_IN_STOCK - REQUESTED),
                            "the buffer is not stock any more");
                })
                .thenWaitUntil(fixture::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** A request for more than is in stock is clamped to what is available; a second one then finds nothing left. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalRequestClampedToStock(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
                    RequestResult result = terminal.requestFromTerminal(player, DIAMOND, OVER_REQUEST);
                    helper.assertTrue(result.isAccepted(), "request accepted: " + result);
                    helper.assertValueEqual(result.request().orElseThrow().requested(), DIAMONDS_IN_STOCK,
                            "clamped to the stock");
                    helper.assertValueEqual(fixture.controller().availableStock(DIAMOND), 0L, "all diamonds promised");
                    helper.assertValueEqual(terminal.stockSnapshot().getFirst().available(), 0L,
                            "the screen shows nothing available");

                    helper.assertValueEqual(terminal.requestFromTerminal(player, DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.NOT_IN_STOCK),
                            "everything is promised to the first request");
                    helper.assertValueEqual(fixture.controller().requestsFor(fixture.absoluteRackPos(TERMINAL_RACK))
                            .size(), 1, "still one request");
                })
                .thenSucceed();
    }

    /**
     * Every rejection reason a terminal can produce: an invalid amount, a player who is too far away, an item that is
     * not in stock, a terminal outside any aisle or turned away from it (both "no controller"), and a terminal that
     * already holds as many open requests as the config allows.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalRequestRejections(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);
        helper.setBlock(fixture.rackPos(MISALIGNED_RACK), terminalState(fixture.sideDirection(MISALIGNED_RACK)));

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = fixture.controller();
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    BlockPos terminalPos = fixture.rackPos(TERMINAL_RACK);
                    Player near = playerAt(helper, terminalPos);

                    helper.assertValueEqual(terminal.requestFromTerminal(near, DIAMOND, 0).rejection(),
                            Optional.of(RequestRejection.INVALID_AMOUNT), "amount 0");
                    helper.assertValueEqual(terminal.requestFromTerminal(near, DIAMOND, -5).rejection(),
                            Optional.of(RequestRejection.INVALID_AMOUNT), "negative amount");

                    Player far = helper.makeMockPlayer(GameType.SURVIVAL);
                    Vec3 center = Vec3.atCenterOf(helper.absolutePos(terminalPos));
                    far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
                    helper.assertFalse(terminal.canPlayerUse(far), "a player far away cannot use the terminal");
                    helper.assertValueEqual(terminal.requestFromTerminal(far, DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.OUT_OF_REACH), "too far away");
                    helper.assertTrue(terminal.canPlayerUse(near), "a player at the terminal can use it");
                    // Both refusals so far are about the asking player, not about this terminal: they are answered,
                    // never stored on the block, so no goggle wearer reads "you are too far away" about someone else.
                    helper.assertTrue(terminal.lastRejection().isEmpty(),
                            "a player-scoped refusal is not remembered on the block");

                    // Not in the server's own stock index: a client could send any item, none of them is accepted.
                    helper.assertTrue(terminal.holdsInStock(DIAMOND), "the index holds diamonds");
                    helper.assertFalse(terminal.holdsInStock(EMERALD), "and no emeralds");
                    helper.assertValueEqual(terminal.requestFromTerminal(near, EMERALD, 1).rejection(),
                            Optional.of(RequestRejection.NOT_IN_STOCK), "emeralds are not in stock");
                    helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "a refusal about this terminal is remembered for the goggles");
                    ItemStack namedDiamond = DIAMOND.toStack(1);
                    namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("not the indexed diamond"));
                    helper.assertValueEqual(terminal.requestFromTerminal(near, ItemKey.of(namedDiamond), 1).rejection(),
                            Optional.of(RequestRejection.NOT_IN_STOCK), "an item the index does not hold");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "nothing requested so far");

                    helper.setBlock(OUTSIDE_TERMINAL, terminalState(Direction.NORTH));
                    WarehouseTerminalBlockEntity outside = terminalAt(helper, OUTSIDE_TERMINAL);
                    helper.assertValueEqual(outside.requestFromTerminal(playerAt(helper, OUTSIDE_TERMINAL), DIAMOND, 1)
                            .rejection(), Optional.of(RequestRejection.NO_CONTROLLER), "outside every aisle");
                    WarehouseTerminalBlockEntity misaligned = fixture.terminalAt(MISALIGNED_RACK);
                    helper.assertValueEqual(misaligned.requestFromTerminal(
                            playerAt(helper, fixture.rackPos(MISALIGNED_RACK)), DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "turned away from the aisle");

                    // One terminal takes at most maxOpenRequestsPerOutput slots of the controller's queue. Repeated
                    // requests for one item merge into the open one (§7.2, ADR-020), so a slot is taken per item type:
                    // distinct keys (a named diamond each) fill the cap, and a repeat still merges.
                    int max = WareworksConfig.maxOpenRequests();
                    int perStation = Math.min(WareworksConfig.maxOpenRequestsPerOutput(), max);
                    if (perStation > MAX_CAP_KEYS) {
                        helper.fail("the config needs " + perStation + " item types to fill a station's request cap; "
                                + "the test provides " + MAX_CAP_KEYS);
                        return;
                    }
                    List<ItemKey> capKeys = capKeys(perStation);
                    IItemHandler chest = fixture.handlerAt(fixture.inventoryPos(STORAGE_RACK));
                    for (ItemKey key : capKeys)
                        fixture.insertAll(chest, key.toStack(CAP_KEY_AMOUNT));
                    helper.assertTrue(controller.refreshLocation(STORAGE_RACK), "stock refreshed");
                    for (int i = 0; i < perStation; i++) {
                        RequestResult result = terminal.requestFromTerminal(near, capKeys.get(i), 1);
                        helper.assertTrue(result.isAccepted(), "request " + i + " accepted: " + result);
                        helper.assertFalse(result.merged(), "a different item is a request of its own");
                    }
                    RequestResult repeated = terminal.requestFromTerminal(near, capKeys.getFirst(), 1);
                    helper.assertTrue(repeated.isAccepted() && repeated.merged(),
                            "a repeated request merges whatever the caps say: " + repeated);
                    helper.assertValueEqual(terminal.requestFromTerminal(near, DIAMOND, 1).rejection(),
                            Optional.of(perStation < max ? RequestRejection.OUTPUT_FULL : RequestRejection.QUEUE_FULL),
                            "this terminal is busy");
                    helper.assertValueEqual(controller.requestsFor(fixture.absoluteRackPos(TERMINAL_RACK)).size(),
                            perStation, "capped per station");
                    helper.assertValueEqual(terminal.status().requestsHere(), perStation, "status shows them");
                })
                .thenSucceed();
    }

    // --- intake port and screen (M10, ADR-022) ---------------------------------------------------------------------

    /**
     * The intake port is derived from the aisle, not from the player. Two terminals are placed with their port on a
     * lateral face and their screen clear of the aisle; the controller's next membership resolution moves each port
     * onto its own aisle side — one on the LEFT rack plane, one on the RIGHT — while both screens stay exactly where
     * they were, and both terminals become addressed members.
     * <p>
     * It then leaves the aisle alone for {@value #SETTLE_TICKS} ticks and asserts that membership is <b>not</b> dirty
     * again: a block state the controller rewrote every probe would keep notifying the registry, which is the shape a
     * per-tick world write would take here.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalIntakeFollowsTheAisle(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        // Port along the aisle, screen on the opposite lateral face: neither is an aisle side, so the controller may
        // move the port on either rack plane without needing the screen's face.
        List<RackPosition> racks = List.of(TERMINAL_RACK, LEFT_TERMINAL_RACK);
        for (RackPosition rack : racks)
            fixture.terminal(rack, AisleFixture.AISLE, TerminalDisplaySide.BACK);
        Direction screenBefore = AisleFixture.AISLE.getOpposite();

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, racks.size()))
                .thenExecute(() -> {
                    for (RackPosition rack : racks) {
                        Direction towardsAisle = fixture.sideDirection(rack).getOpposite();
                        BlockState state = helper.getBlockState(fixture.rackPos(rack));
                        helper.assertValueEqual(state.getValue(WarehouseStationBlock.FACING), towardsAisle,
                                "the port at " + rack + " was moved onto the aisle side");
                        helper.assertValueEqual(screenOf(state), screenBefore,
                                "the screen at " + rack + " did not travel with the port");
                        WarehouseTerminalBlockEntity terminal = fixture.terminalAt(rack);
                        helper.assertValueEqual(terminal.displaySide(), screenBefore, "the block entity agrees");
                        terminal.onGoggleObserved();
                        helper.assertValueEqual(terminal.summary().assignment().state(),
                                AisleAssignment.State.ASSIGNED, "aligned once the port moved: " + rack);
                    }
                    helper.assertValueEqual(fixture.controller().misalignedCount(), 0, "nothing misaligned");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertFalse(fixture.controller().isMembershipDirty(),
                            "a settled aisle stops re-probing, so nothing rewrites the block state");
                    for (RackPosition rack : racks) {
                        BlockState state = helper.getBlockState(fixture.rackPos(rack));
                        helper.assertValueEqual(state.getValue(WarehouseStationBlock.FACING),
                                fixture.sideDirection(rack).getOpposite(), "port still right at " + rack);
                        helper.assertValueEqual(screenOf(state), screenBefore, "screen still right at " + rack);
                    }
                })
                .thenSucceed();
    }

    /**
     * A screen on the aisle side is the one misalignment left for a player to fix: the controller cannot move the port
     * there, so the terminal is no member, serves no request and shows "Misaligned" in the goggles. One wrench click
     * moves the screen off that face, and the controller then moves the port onto it by itself.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalMisalignedScreen(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        Direction towardsAisle = fixture.sideDirection(TERMINAL_RACK).getOpposite();
        // Port away from the aisle, so the default screen side lands on the aisle: what a terminal built the old way
        // (or placed from inside the aisle) looks like.
        fixture.terminal(TERMINAL_RACK, towardsAisle.getOpposite(), TerminalDisplaySide.BACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    fixture.assertReady(1, 0, 0);
                    helper.assertValueEqual(fixture.controller().misalignedCount(), 1, "the terminal is misaligned");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.displaySide(), towardsAisle, "the screen occupies the aisle side");
                    helper.assertValueEqual(terminal.facing(), towardsAisle.getOpposite(), "the port was not moved");
                    terminal.onGoggleObserved();
                    helper.assertValueEqual(terminal.summary().assignment(), AisleAssignment.MISALIGNED,
                            "goggles report it, with the wrench hint");
                    helper.assertValueEqual(terminal.requestFromTerminal(
                            playerAt(helper, fixture.rackPos(TERMINAL_RACK)), DIAMOND, 1).rejection(),
                            Optional.of(RequestRejection.NO_CONTROLLER), "a misaligned terminal serves nobody");
                    // A side face works too: the top is often under a rack shelf (ADR-022 deviation).
                    fixture.wrenchFace(fixture.rackPos(TERMINAL_RACK), towardsAisle.getOpposite());
                    helper.assertFalse(fixture.terminalAt(TERMINAL_RACK).displaySide() == towardsAisle,
                            "the wrench moved the screen off the aisle side");
                })
                .thenWaitUntil(() -> {
                    fixture.assertReady(1, 0, 1);
                    helper.assertValueEqual(fixture.controller().misalignedCount(), 0, "nothing misaligned any more");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.facing(), towardsAisle, "the port followed the aisle");
                    terminal.onGoggleObserved();
                    helper.assertValueEqual(terminal.summary().assignment(),
                            AisleAssignment.assigned(StorageAddress.parse("A-01-00R")), "addressed after one click");
                })
                .thenSucceed();
    }

    /**
     * Turning the screen while a delivery is in flight is safe, so it is not refused (unlike the stacker crane dock's
     * rotation, which would turn the whole aisle): the port does not move, the job keeps its target, the items still
     * arrive and the conservation invariant holds on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalWrenchDuringDelivery(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        Direction towardsAisle = fixture.sideDirection(TERMINAL_RACK).getOpposite();

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> helper.assertTrue(fixture.terminalAt(TERMINAL_RACK).requestFromTerminal(
                        playerAt(helper, fixture.rackPos(TERMINAL_RACK)), DIAMOND, REQUESTED).isAccepted(),
                        "request accepted"))
                .thenWaitUntil(() -> helper.assertTrue(fixture.dock().currentJob().isPresent(), "the crane has a job"))
                .thenExecute(() -> {
                    Direction screenBefore = fixture.terminalAt(TERMINAL_RACK).displaySide();
                    fixture.wrenchTopFace(fixture.rackPos(TERMINAL_RACK));
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    helper.assertFalse(terminal.displaySide() == screenBefore, "the wrench turned the screen");
                    helper.assertValueEqual(terminal.facing(), towardsAisle, "the intake port did not move");
                    helper.assertTrue(fixture.dock().currentJob().isPresent(), "the job is untouched");
                })
                .thenExecuteFor(CENSUS_TICKS, () -> ItemCensus.assertEquals(helper, conserved, "after the wrench"))
                .thenWaitUntil(() -> helper.assertValueEqual(fixture.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) REQUESTED, "delivered although the screen turned mid job"))
                .thenExecute(() -> ItemCensus.assertEquals(helper, conserved, "after the delivery"))
                .thenWaitUntil(fixture::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * The crane delivers into a terminal on the LEFT and on the RIGHT rack plane, which is what the derived intake port
     * is for: whichever side the aisle is on, the port is on that side and the transfer is the one every station gets.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void terminalDeliveryFromBothAisleSides(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        List<RackPosition> racks = List.of(TERMINAL_RACK, LEFT_TERMINAL_RACK);
        racks.forEach(fixture::terminal);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, racks.size()))
                .thenExecute(() -> {
                    for (RackPosition rack : racks) {
                        helper.assertValueEqual(fixture.terminalAt(rack).facing(),
                                fixture.sideDirection(rack).getOpposite(), "the port looks into the aisle at " + rack);
                        helper.assertTrue(fixture.terminalAt(rack).requestFromTerminal(
                                playerAt(helper, fixture.rackPos(rack)), DIAMOND, SIDE_REQUEST).isAccepted(),
                                "request at " + rack);
                    }
                })
                .thenExecuteFor(CENSUS_TICKS, () -> ItemCensus.assertEquals(helper, conserved, "during both jobs"))
                .thenWaitUntil(() -> {
                    for (RackPosition rack : racks)
                        helper.assertValueEqual(fixture.stationCount(rack, DIAMOND), (long) SIDE_REQUEST,
                                "delivered into the terminal at " + rack);
                })
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved, "after both deliveries");
                    helper.assertValueEqual(fixture.storedAt(STORAGE_RACK, DIAMOND),
                            (long) (DIAMONDS_IN_STOCK - 2 * SIDE_REQUEST), "left in the chest");
                })
                .thenWaitUntil(fixture::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * World compatibility (ADR-022): a terminal built before M10 keeps working. Its saved block state carries only
     * {@code facing}, and a missing property resolves to the block's default, so it reads back as "port unchanged,
     * screen on the face opposite the aisle" — where that terminal's player already stood. The block entity format did
     * not change either, so the buffer survives, the terminal is an addressed member at once, and the whole request and
     * delivery loop runs on it. Nothing rewrites its block state afterwards, because the port is already right.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalWorldCompatibility(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        Direction towardsAisle = fixture.sideDirection(TERMINAL_RACK).getOpposite();

        // Exactly what a chunk palette written before M10 holds: the block id and "facing", and no "display".
        CompoundTag saved = NbtUtils.writeBlockState(WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, towardsAisle));
        saved.getCompound("Properties").remove(WarehouseTerminalBlock.DISPLAY.getName());
        BlockState restored = NbtUtils.readBlockState(helper.getLevel().holderLookup(Registries.BLOCK), saved);
        helper.assertValueEqual(restored.getBlock(), WareworksBlocks.WAREHOUSE_TERMINAL.get(),
                "an old state still resolves to this block, not to air");
        helper.assertValueEqual(restored.getValue(WarehouseStationBlock.FACING), towardsAisle,
                "the intake port keeps the direction the old facing had");
        helper.assertValueEqual(restored.getValue(WarehouseTerminalBlock.DISPLAY), TerminalDisplaySide.BACK,
                "the missing property resolves to the sensible default");
        helper.assertValueEqual(screenOf(restored), towardsAisle.getOpposite(),
                "the screen lands where the old terminal's player stood");
        helper.setBlock(fixture.rackPos(TERMINAL_RACK), restored);

        helper.startSequence()
                .thenWaitUntil(() -> fixture.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    helper.assertTrue(terminal.insert(GOLD.toStack(DELIVERED_GOLD), false).isEmpty(), "gold buffered");
                    // M10 added no block entity key, so a pre-M10 tag is this tag: it must load back unchanged.
                    CompoundTag tag = terminal.saveWithoutMetadata(registries);
                    helper.assertFalse(tag.contains(WarehouseTerminalBlock.DISPLAY.getName()),
                            "the screen side lives in the block state, never in the block entity");
                    terminal.loadWithComponents(tag, registries);
                    helper.assertValueEqual(terminal.bufferedItems().count(GOLD), (long) DELIVERED_GOLD,
                            "the buffer of an old terminal survives");
                    terminal.onGoggleObserved();
                    helper.assertValueEqual(terminal.summary().assignment(),
                            AisleAssignment.assigned(StorageAddress.parse("A-01-00R")), "still an addressed member");
                    RequestResult result = terminal.requestFromTerminal(
                            playerAt(helper, fixture.rackPos(TERMINAL_RACK)), DIAMOND, REQUESTED);
                    helper.assertTrue(result.isAccepted(), "an old terminal still requests: " + result);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(fixture.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) REQUESTED, "the crane still delivers into an old terminal"))
                .thenExecute(() -> {
                    BlockState state = helper.getBlockState(fixture.rackPos(TERMINAL_RACK));
                    helper.assertValueEqual(state.getValue(WarehouseStationBlock.FACING), towardsAisle,
                            "the port was never rewritten");
                    helper.assertValueEqual(state.getValue(WarehouseTerminalBlock.DISPLAY), TerminalDisplaySide.BACK,
                            "nor the screen");
                })
                .thenSucceed();
    }

    /**
     * Two <b>parallel</b> aisles two blocks apart share the rack plane between them ({@code docs/warehouse-system.md}
     * §4, §8), and there the two layouts want <b>opposite</b> intake ports. Exactly one controller may therefore write
     * the terminal's block state, and the owner is picked by dock distance and controller position — never by
     * alignment, because alignment is what the write changes ({@code WarehouseRegistry#ownsMemberState}).
     * <p>
     * The terminal is placed with its port and its screen on the two <b>lateral</b> faces, so the screen blocks
     * neither aisle and both controllers would otherwise write. This is the geometry the M10 review found unprotected:
     * without the owner check the two rewrote the block on alternating ticks for ever. The test asserts that after the
     * aisles settle the block state is never touched again, that neither controller stays membership-dirty, that
     * exactly one aisle holds the terminal as an output while the other reports it misaligned, and that the registry
     * keeps resolving it to that same aisle.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalPortOwnerOnASharedRackPlane(GameTestHelper helper) {
        AisleFixture first = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        AisleFixture second = new AisleFixture(helper, PARALLEL_AISLE_Z, RAILS).build(true);
        BlockPos terminalPos = first.rackPos(SHARED_RACK_RIGHT);
        helper.assertValueEqual(second.rackPos(SHARED_RACK_LEFT), terminalPos,
                "both aisles must reach this rack position, or the test builds the wrong geometry");
        // Port along the aisle, screen on the opposite lateral face: neither face is an aisle side of either aisle.
        first.terminal(SHARED_RACK_RIGHT, AisleFixture.AISLE, TerminalDisplaySide.BACK);
        List<AisleFixture> aisles = List.of(first, second);
        BlockState[] settled = new BlockState[1];

        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (AisleFixture aisle : aisles) {
                        helper.assertValueEqual(aisle.controller().status(), ControllerStatus.READY,
                                "controller ready");
                        helper.assertFalse(aisle.controller().isMembershipDirty(), "membership settled");
                    }
                })
                .thenExecute(() -> settled[0] = helper.getBlockState(terminalPos))
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(helper.getBlockState(terminalPos), settled[0],
                            "neither aisle may rewrite a port the other one owns");
                    AisleFixture owner = null;
                    AisleFixture loser = null;
                    for (AisleFixture aisle : aisles) {
                        helper.assertFalse(aisle.controller().isMembershipDirty(),
                                "a contested terminal must not keep an aisle re-probing for ever");
                        if (aisle.controller().locationAt(helper.absolutePos(terminalPos)).map(LocationRecord::kind)
                                .orElse(null) == LocationKind.OUTPUT)
                            owner = aisle;
                        else
                            loser = aisle;
                    }
                    if (owner == null || loser == null) {
                        helper.fail("exactly one of the two aisles must own the shared terminal", terminalPos);
                        return;
                    }
                    helper.assertValueEqual(owner.controller().misalignedCount(), 0,
                            "the owning aisle sees an aligned terminal");
                    helper.assertValueEqual(loser.controller().misalignedCount(), 1,
                            "the other reports it misaligned, like every member on a shared rack plane");
                    RackPosition rack = owner == first ? SHARED_RACK_RIGHT : SHARED_RACK_LEFT;
                    helper.assertValueEqual(helper.getBlockState(terminalPos).getValue(WarehouseStationBlock.FACING),
                            owner.sideDirection(rack).getOpposite(), "the port looks into the owning aisle");
                    helper.assertValueEqual(WarehouseRegistry.findController(helper.getLevel(),
                            helper.absolutePos(terminalPos)).map(WarehouseControllerBlockEntity::getBlockPos),
                            Optional.of(helper.absolutePos(owner.controllerPos())),
                            "and the registry resolves the terminal to that same aisle");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** The world direction of a terminal's screen, as its block state says (intake port plus the relative side). */
    private static Direction screenOf(BlockState state) {
        return state.getValue(WarehouseTerminalBlock.DISPLAY).of(state.getValue(WarehouseStationBlock.FACING));
    }

    /** Controller, dock with motor, rails, a chest of diamonds behind an interface, and an aligned terminal. */
    private static AisleFixture buildAisle(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        fixture.terminal(TERMINAL_RACK);
        return fixture;
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

    private static BlockState terminalState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState().setValue(WarehouseTerminalBlock.FACING, facing);
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static BlockState place(GameTestHelper helper, Player player, BlockPos floor) {
        BlockPos absolute = helper.absolutePos(floor);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, 0.5, 0), Direction.UP, absolute, false);
        return WareworksBlocks.WAREHOUSE_TERMINAL.get().getStateForPlacement(new BlockPlaceContext(helper.getLevel(),
                player, InteractionHand.MAIN_HAND, WareworksBlocks.WAREHOUSE_TERMINAL.asStack(), hit));
    }

    private static WarehouseTerminalBlockEntity terminalAt(GameTestHelper helper, BlockPos pos) {
        WarehouseTerminalBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse terminal block entity", pos);
        return be;
    }

    private static WarehouseTerminalBlockEntity detached(GameTestHelper helper, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalBlockEntity copy = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.create(terminal.getBlockPos(),
                terminal.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached warehouse terminal");
        return copy;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos, Direction side) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), side);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    private static long countIn(GameTestHelper helper, BlockPos pos, Item item) {
        IItemHandler handler = handlerAt(helper, pos, null);
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static long dropped(GameTestHelper helper, BlockPos pos, Item item) {
        long total = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, DROP_RADIUS)) {
            if (entity.getItem().is(item))
                total += entity.getItem().getCount();
        }
        return total;
    }
}
