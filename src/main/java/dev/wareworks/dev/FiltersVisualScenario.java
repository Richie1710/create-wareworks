package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "filters": a rack row partitioned by item ({@code docs/warehouse-system.md} §3.1, ADR-021, M8).
 * <p>
 * It builds a short aisle whose left rack plane is one row of four storage locations. Three of them carry a store
 * filter ({@value #BATCH} items each of diamond, gold and redstone are dedicated to them), the fourth carries none. A
 * mixed stream of those three item types plus one the filters all reject ({@link #UNMATCHED}) is fed through the
 * warehouse input, and the crane sorts it: every chest ends up holding exactly one item type. That is asserted on the
 * server, so a broken partition fails the Gradle task instead of producing a pretty but wrong picture.
 * <p>
 * <b>What the shots show.</b> Create draws a filter slot's item in the world through
 * {@code SmartBlockEntityRenderer} → {@code FilteringRenderer#renderOnBlockEntity}, independently of what the player
 * looks at, so the three filter items are visible on the aisle faces of the row and the fourth face is empty. Two
 * constraints follow from Create's own code and are what the cameras are built around:
 * <ul>
 *   <li>the filter item is only drawn within {@code FilteringBehaviour#getRenderDistance()}, i.e. Create's client
 *       config {@code filterItemRenderDistance} (default <b>10</b> blocks), so every camera stays well inside that;</li>
 *   <li>the crane must not stand between the camera and the row. The run therefore ends with a real retrieval into an
 *       output at the <b>far</b> end of the aisle, which parks the crane there — and proves on the way that retrieval
 *       is never blocked by a store filter (ADR-021).</li>
 * </ul>
 * The client is checked as well: the shots can only show the filters if they reached the client, so the scenario waits
 * for the synced filters on the client block entities before it starts its camera tour.
 */
public final class FiltersVisualScenario implements VisualScenario {
    public static final String NAME = "filters";

    private static final Direction AISLE = Direction.EAST;
    /** World column of the dock. */
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 128;
    /** Blocks cleared to air around the aisle (sideways and at both ends) and above the floor. */
    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;

    /** The demonstrated row: one level, one side, so all four slots are seen from a single camera. */
    private static final int ROW_LEVEL = 0;
    private static final Side ROW_SIDE = Side.LEFT;
    /** The item no filter of the row accepts; it is what the unfiltered location must end up with. */
    private static final Item UNMATCHED = Items.OAK_LOG;
    /** Items of each type fed through the input. One stack, so every chest needs exactly one slot. */
    private static final int BATCH = 32;
    /** Items fetched back out again at the end, from the location dedicated to them. */
    private static final int REQUEST_AMOUNT = 5;

    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    /** At the far end of the aisle: the crane parks here after the retrieval, clear of the row and its cameras. */
    private static final RackPosition OUTPUT = RackPosition.of(RAILS, 0, Side.RIGHT);

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int STORED_TIMEOUT_TICKS = 2400;
    private static final int DELIVERED_TIMEOUT_TICKS = 2400;
    private static final int CLIENT_SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 10;

    /** One storage location of the row: where it is and which item it is dedicated to, if any. */
    private record Rack(RackPosition position, Optional<Item> dedicatedTo) {
        static Rack dedicated(int x, Item item) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), Optional.of(item));
        }

        static Rack general(int x) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), Optional.empty());
        }

        /** The one item type this location must hold when the stream has been sorted. */
        Item expectedContent() {
            return dedicatedTo.orElse(UNMATCHED);
        }
    }

    /**
     * The row, in aisle order: three dedicated locations and one that accepts everything.
     * <p>
     * The three items are chosen for their <b>colour</b>. Create draws a filter item with the light of the block it
     * belongs to, and a warehouse interface is a full cube, so the light sampled there is the block's own and every
     * filter item renders dark (the warehouse output has the same look). Strongly hued items stay recognisable
     * anyway; an iron ingot, tried first, came out near-black.
     */
    private static final List<Rack> ROW = List.of(Rack.dedicated(2, Items.DIAMOND),
            Rack.dedicated(3, Items.GOLD_INGOT), Rack.dedicated(4, Items.REDSTONE), Rack.general(5));

    // Cameras, relative to the dock's lower corner. The rack row occupies x 2..6 at z -1..0, so its faces lie on the
    // plane z = 0 and the aisle line (rails, crane) is z 0..1. Every distance stays below the 10 blocks after which
    // Create stops drawing filter items.
    /**
     * Straight at the row from across the aisle: all four faces, three of them with their filter item. Close enough
     * that the filter items stay readable (the row fills about half the frame width) and far enough that the outer two
     * locations are not distorted away.
     */
    private static final CameraView WALL = CameraView.of("wall", 4.0, 1.15, 2.9, 4.0, 0.38, -0.55);
    /** Close-up of the middle dedicated location: the filter item in its slot below the dark arm port. */
    private static final CameraView SLOT = CameraView.of("slot", 3.5, 0.6, 1.0, 3.5, 0.31, -0.02);
    /** The whole scene from the side: the partitioned row, the rail line, the parked crane and the stations. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 7.6, 2.0, 3.2, 3.4, 0.7, -0.3);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("filters: clear the area and place the creative motor", FiltersVisualScenario::placeMotor)
                .server("filters: build the aisle, the stations and the rack row", FiltersVisualScenario::buildAisle)
                .serverUntil("filters: wait until the controller is ready with every member",
                        FiltersVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("filters: dedicate three of the four locations", FiltersVisualScenario::setFilters)
                .server("filters: power the crane and feed a mixed stream", FiltersVisualScenario::feedInput)
                .serverUntil("filters: wait until the crane has sorted the stream", FiltersVisualScenario::allStored,
                        STORED_TIMEOUT_TICKS)
                .server("filters: check that every chest holds exactly its own item",
                        FiltersVisualScenario::assertPartition)
                // A retrieval out of a dedicated location: it proves that a filter never blocks fetching, and it parks
                // the crane at the far end of the aisle, out of the line of sight of the row cameras.
                .server("filters: request some of the dedicated item back", FiltersVisualScenario::requestFromDedicated)
                .serverUntil("filters: wait until the output has the requested items",
                        FiltersVisualScenario::requestDelivered, DELIVERED_TIMEOUT_TICKS)
                .server("filters: reset the lever", (server, context) -> setLever(server, context, false))
                .serverUntil("filters: wait until the crane is parked at the output",
                        FiltersVisualScenario::craneParkedAtOutput, DELIVERED_TIMEOUT_TICKS)
                .until("filters: wait until the client sees every filter", FiltersVisualScenario::clientFiltersSynced,
                        CLIENT_SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        for (CameraView view : List.of(WALL, SLOT, OVERVIEW))
            script.shotFrom(view, "filters");
    }

    @Override
    public String status(VisualContext context) {
        return String.format(Locale.ROOT, "clientFilters=%d/%d", clientFilterCount(context), dedicatedCount());
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));

        Direction outward = layout.sideDirection(OUTPUT.side());
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        // A lever beside the output on the superflat floor: a lever reports signal 15 to every side, so the station
        // sees the rising edge of a real redstone request (LeverBlock#getSignal), as in the showcase world.
        level.setBlockAndUpdate(leverPos(layout), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, AISLE)
                .setValue(LeverBlock.POWERED, false));

        for (Rack rack : ROW) {
            BlockPos pos = layout.rackPos(rack.position());
            Direction rackOutward = layout.sideDirection(rack.position().side());
            level.setBlockAndUpdate(pos.relative(rackOutward),
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, rackOutward.getOpposite()));
            level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, rackOutward));
        }
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == ROW.size() && controller.inputStations().size() == 1
                && controller.outputStations().size() == 1 && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    // --- the feature: filters, the sorted stream and a retrieval ---------------------------------------------------

    /** Sets a plain item filter on the dedicated locations, exactly as a right-click with that item would. */
    private static void setFilters(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        for (Rack rack : ROW) {
            if (rack.dedicatedTo().isEmpty())
                continue;
            Item item = rack.dedicatedTo().get();
            WarehouseInterfaceBlockEntity storage = interfaceAt(level, layout, rack.position());
            if (!storage.setStoreFilter(new ItemStack(item)))
                throw new VisualTestException("the interface at " + rack.position() + " refused the filter " + item);
        }
        int filtered = controller(level, context.origin()).filteredLocationCount();
        if (filtered != dedicatedCount())
            throw new VisualTestException(
                    "the controller counts " + filtered + " filtered locations, expected " + dedicatedCount());
        LOGGER.info(PREFIX + "filters: {} of {} storage locations dedicated", filtered, ROW.size());
    }

    /** Powers the crane and inserts the stream through the input's capability, like a hopper or funnel would. */
    private static void feedInput(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(MOTOR_RPM);
        BlockPos input = layout(dock).rackPos(INPUT);
        for (Rack rack : ROW)
            insertAll(level, input, new ItemStack(rack.expectedContent(), BATCH));
    }

    private static boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input of the aisle is missing");
        return craneIdle(crane) && input.bufferedItems().isEmpty();
    }

    /**
     * Every chest holds exactly {@value #BATCH} items of exactly one item type: the dedicated ones their own item, the
     * unfiltered one what no filter accepted. A picture of a partition that did not happen is worse than no picture.
     */
    private static void assertPartition(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        for (Rack rack : ROW) {
            BlockPos chest = chestPos(layout, rack.position());
            Item expected = rack.expectedContent();
            int own = countAt(level, chest, expected);
            int total = totalAt(level, chest);
            if (own != BATCH || total != BATCH)
                throw new VisualTestException("the chest at " + rack.position() + " holds " + own + " " + expected
                        + " of " + total + " items in total, expected " + BATCH + " and nothing else");
            LOGGER.info(PREFIX + "filters: {} holds {} x {}", rack.position(), own, expected);
        }
    }

    /** Asks the output for items that live in a dedicated location, through a real redstone pulse. */
    private static void requestFromDedicated(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(context.origin()).rackPos(OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output of the aisle is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(requestedItem())))
            throw new VisualTestException("the warehouse output refused the request filter");
        filter.count = REQUEST_AMOUNT; // after setFilter, which may clamp the count
        setLever(server, context, true);
    }

    private static boolean requestDelivered(MinecraftServer server, VisualContext context) {
        return countAt(server.overworld(), layout(context.origin()).rackPos(OUTPUT), requestedItem()) >= REQUEST_AMOUNT;
    }

    /**
     * The crane is idle at the far end of the aisle, and the dedicated chest is exactly {@value #REQUEST_AMOUNT} items
     * lighter — so the items really came out of the filtered location.
     */
    private static boolean craneParkedAtOutput(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        if (!craneIdle(crane))
            return false;
        Rack source = ROW.getFirst();
        int left = countAt(level, chestPos(layout(context.origin()), source.position()), requestedItem());
        if (left != BATCH - REQUEST_AMOUNT)
            throw new VisualTestException("the dedicated chest holds " + left + " " + requestedItem() + ", expected "
                    + (BATCH - REQUEST_AMOUNT) + " after the retrieval");
        return true;
    }

    /**
     * Flips the lever beside the output. {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the
     * station see the edge and submit (or drop) the request.
     */
    private static void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(layout(context.origin()));
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    // --- client -----------------------------------------------------------------------------------------------------

    /** The shots can only show what the client knows: every dedicated location must carry its filter there too. */
    private static boolean clientFiltersSynced(VisualContext context) {
        return clientFilterCount(context) == dedicatedCount();
    }

    /** Storage locations of the row whose filter has reached the client (client thread). */
    private static int clientFilterCount(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return 0;
        AisleLayout layout = layout(context.origin());
        int filtered = 0;
        for (Rack rack : ROW) {
            if (level.getBlockEntity(layout.rackPos(rack.position())) instanceof WarehouseInterfaceBlockEntity storage
                    && storage.hasStoreFilter())
                filtered++;
        }
        return filtered;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static int dedicatedCount() {
        return (int) ROW.stream().filter(rack -> rack.dedicatedTo().isPresent()).count();
    }

    /** The item requested back at the end: the one the first dedicated location of the row holds. */
    private static Item requestedItem() {
        return ROW.getFirst().expectedContent();
    }

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    /** The inventory behind a storage location: one block further away from the aisle than its interface. */
    private static BlockPos chestPos(AisleLayout layout, RackPosition rack) {
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    /** Beside the output, one block further out than the station itself, standing on the superflat floor. */
    private static BlockPos leverPos(AisleLayout layout) {
        return layout.rackPos(OUTPUT).relative(layout.sideDirection(OUTPUT.side()));
    }

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(ServerLevel level, AisleLayout layout, RackPosition rack) {
        WarehouseInterfaceBlockEntity storage = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(level,
                layout.rackPos(rack));
        if (storage == null)
            throw new VisualTestException("no warehouse interface at " + rack);
        return storage;
    }

    private static void insertAll(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = handlerAt(level, pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    private static int countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = handlerAt(level, pos);
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static int totalAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = handlerAt(level, pos);
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }
}
