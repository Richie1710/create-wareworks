package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
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
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * One test aisle along +X on an aisle template floor, built the way a player builds it: a creative motor below the dock,
 * the dock, rails in front of it and the controller behind it; storage locations (chest behind an aligned interface),
 * input and output stations at rack positions ({@code docs/warehouse-system.md} §1).
 * <p>
 * The aisle runs at {@code z = aisleZ}: controller at x = 0, dock at x = 1, rails at x = 2..(1 + rails); the left rack
 * plane is {@code z - 1} (inventories at {@code z - 2}), the right one {@code z + 1} (inventories at {@code z + 2}); level 0
 * is the dock level {@link WareworksGameTests#BASE_Y}. Positions are test-relative. Accessors fail the test (never throw
 * anything else) when a block entity is missing, so they are safe inside sequences.
 */
final class AisleFixture {
    static final Direction AISLE = Direction.EAST;

    private final GameTestHelper helper;
    private final BlockPos controller;
    private final BlockPos dock;
    private final BlockPos motor;
    private final int rails;
    private final AisleLayout relative;

    /**
     * @param aisleZ test-relative z of the aisle line
     * @param rails  number of rails in front of the dock
     */
    AisleFixture(GameTestHelper helper, int aisleZ, int rails) {
        this.helper = helper;
        this.controller = new BlockPos(0, BASE_Y, aisleZ);
        this.dock = new BlockPos(1, BASE_Y, aisleZ);
        this.motor = new BlockPos(1, FLOOR_Y, aisleZ);
        this.rails = rails;
        // The relative layout only maps rack positions; its height covers the dock's default mast height.
        this.relative = AisleLayout.of(dock, AISLE, AisleGeometry.of(rails, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    // --- building --------------------------------------------------------------------------------------------------

    /** Motor (if {@code withMotor}), dock, rails and controller. */
    AisleFixture build(boolean withMotor) {
        if (withMotor)
            placeMotor();
        helper.setBlock(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= rails; x++)
            helper.setBlock(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        placeController();
        return this;
    }

    void placeMotor() {
        helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    /** A controller behind the dock, facing it (also after the old one was broken). */
    void placeController() {
        helper.setBlock(controller, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, AISLE));
    }

    /** A chest with {@code contents} behind an aligned interface at {@code rack}; returns the chest position. */
    BlockPos storage(RackPosition rack, ItemStack... contents) {
        BlockPos chest = inventoryPos(rack);
        helper.setBlock(chest, Blocks.CHEST);
        IItemHandler handler = handlerAt(chest);
        for (ItemStack stack : contents)
            insertAll(handler, stack);
        placeInterface(rack);
        return chest;
    }

    void placeInterface(RackPosition rack) {
        helper.setBlock(rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, relative.sideDirection(rack.side())));
    }

    void input(RackPosition rack) {
        helper.setBlock(rackPos(rack), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, relative.sideDirection(rack.side()).getOpposite()));
    }

    void output(RackPosition rack) {
        helper.setBlock(rackPos(rack), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, relative.sideDirection(rack.side()).getOpposite()));
    }

    /**
     * An aligned warehouse terminal at {@code rack}: the intake port looks into the aisle and the screen sits on the
     * face opposite it, where a player stands ({@code docs/warehouse-system.md} §3.4.3). This is also exactly the state
     * a terminal built before M10 loads as, because the new {@code DISPLAY} property defaults to
     * {@link TerminalDisplaySide#BACK} (ADR-022).
     */
    void terminal(RackPosition rack) {
        terminal(rack, relative.sideDirection(rack.side()).getOpposite(), TerminalDisplaySide.BACK);
    }

    /** A warehouse terminal whose intake port looks into the aisle, with its screen at {@code display}. */
    void terminal(RackPosition rack, TerminalDisplaySide display) {
        terminal(rack, relative.sideDirection(rack.side()).getOpposite(), display);
    }

    /** A warehouse terminal with an explicit intake port and screen side (a misplacement, or an old world's state). */
    void terminal(RackPosition rack, Direction intake, TerminalDisplaySide display) {
        helper.setBlock(rackPos(rack), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, intake)
                .setValue(WarehouseTerminalBlock.DISPLAY, display));
    }

    /**
     * An aligned warehouse production station at {@code rack}: its opening looks into the aisle, like an input or an
     * output ({@code docs/warehouse-system.md} §3.5). The crane delivers a production order's ingredients here.
     */
    void production(RackPosition rack) {
        helper.setBlock(rackPos(rack), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, relative.sideDirection(rack.side()).getOpposite()));
    }

    /** Breaks the block at a test-relative position like a player would (block entity removal logic runs, no block drop). */
    void breakBlock(BlockPos pos) {
        helper.getLevel().destroyBlock(helper.absolutePos(pos), false);
    }

    // --- positions -------------------------------------------------------------------------------------------------

    BlockPos controllerPos() {
        return controller;
    }

    BlockPos dockPos() {
        return dock;
    }

    BlockPos rackPos(RackPosition rack) {
        return relative.rackPos(rack);
    }

    BlockPos absoluteRackPos(RackPosition rack) {
        return helper.absolutePos(rackPos(rack));
    }

    /** The inventory position behind a storage location (one block further away from the aisle). */
    BlockPos inventoryPos(RackPosition rack) {
        return rackPos(rack).relative(relative.sideDirection(rack.side()));
    }

    Direction sideDirection(RackPosition rack) {
        return relative.sideDirection(rack.side());
    }

    // --- block entities --------------------------------------------------------------------------------------------

    StackerCraneBlockEntity dock() {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(helper.getLevel(),
                helper.absolutePos(dock));
        if (be == null)
            helper.fail("missing stacker crane block entity", dock);
        return be;
    }

    WarehouseControllerBlockEntity controller() {
        WarehouseControllerBlockEntity be = controllerIfPresent().orElse(null);
        if (be == null)
            helper.fail("missing warehouse controller block entity", controller);
        return be;
    }

    Optional<WarehouseControllerBlockEntity> controllerIfPresent() {
        return Optional.ofNullable(WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(helper.getLevel(),
                helper.absolutePos(controller)));
    }

    CreativeMotorBlockEntity motor() {
        CreativeMotorBlockEntity be = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(), helper.absolutePos(motor));
        if (be == null)
            helper.fail("missing creative motor block entity", motor);
        return be;
    }

    WarehouseInputBlockEntity inputAt(RackPosition rack) {
        WarehouseInputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(helper.getLevel(),
                absoluteRackPos(rack));
        if (be == null)
            helper.fail("missing warehouse input block entity", rackPos(rack));
        return be;
    }

    WarehouseOutputBlockEntity outputAt(RackPosition rack) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                absoluteRackPos(rack));
        if (be == null)
            helper.fail("missing warehouse output block entity", rackPos(rack));
        return be;
    }

    WarehouseTerminalBlockEntity terminalAt(RackPosition rack) {
        WarehouseTerminalBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(helper.getLevel(),
                absoluteRackPos(rack));
        if (be == null)
            helper.fail("missing warehouse terminal block entity", rackPos(rack));
        return be;
    }

    WarehouseProductionBlockEntity productionAt(RackPosition rack) {
        WarehouseProductionBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(helper.getLevel(),
                absoluteRackPos(rack));
        if (be == null)
            helper.fail("missing warehouse production block entity", rackPos(rack));
        return be;
    }

    WarehouseInterfaceBlockEntity interfaceAt(RackPosition rack) {
        WarehouseInterfaceBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(helper.getLevel(),
                absoluteRackPos(rack));
        if (be == null)
            helper.fail("missing warehouse interface block entity", rackPos(rack));
        return be;
    }

    /**
     * Sets the store filter of the storage location at {@code rack}, as a player click on its filter slot would
     * ({@code docs/warehouse-system.md} §3.1). An empty stack clears it.
     */
    void setStoreFilter(RackPosition rack, ItemStack filter) {
        helper.assertTrue(interfaceAt(rack).setStoreFilter(filter), "the interface accepts the filter " + filter);
    }

    FilteringBehaviour filterOf(RackPosition output) {
        FilteringBehaviour filter = BlockEntityBehaviour.get(outputAt(output), FilteringBehaviour.TYPE);
        if (filter == null)
            helper.fail("the output has no request filter", rackPos(output));
        return filter;
    }

    // --- actions ---------------------------------------------------------------------------------------------------

    /**
     * Sets the output's filter to {@code filter} with {@code amount} and gives it one redstone rising edge through the
     * block at {@code trigger} (removed first if it is powered already), exactly like a player with a lever or pulse.
     */
    void requestAt(RackPosition output, ItemStack filter, int amount, BlockPos trigger) {
        FilteringBehaviour behaviour = filterOf(output);
        helper.assertTrue(behaviour.setFilter(filter), "the output accepts the filter " + filter);
        behaviour.count = amount; // after setFilter, which may clamp the count
        helper.setBlock(trigger, Blocks.AIR);
        helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
    }

    /** Uses a wrench on the top face of the test-relative position, like a survival player (not sneaking). */
    void wrenchTopFace(BlockPos pos) {
        wrenchFace(pos, Direction.UP);
    }

    /** Uses a wrench on {@code face} of the test-relative position, like a survival player (not sneaking). */
    void wrenchFace(BlockPos pos, Direction face) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 hit = Vec3.atCenterOf(absolute)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        helper.useBlock(pos, player, new BlockHitResult(hit, face, absolute, false));
    }

    // --- assertions and counts -------------------------------------------------------------------------------------

    /** Controller ready with exactly these members recorded and read, dock linked. */
    void assertReady(int storage, int inputs, int outputs) {
        WarehouseControllerBlockEntity be = controller();
        helper.assertValueEqual(be.status(), ControllerStatus.READY, "controller status");
        helper.assertFalse(be.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(be.storageLocations().size(), storage, "storage locations");
        helper.assertValueEqual(be.inputStations().size(), inputs, "input stations");
        helper.assertValueEqual(be.outputStations().size(), outputs, "output stations");
        helper.assertValueEqual(be.pendingSnapshotCount(), 0, "storage locations read");
        helper.assertTrue(dock().isControllerLinked(), "dock linked");
    }

    /** The crane is in {@code phase} with exactly {@code amount} items of {@code key} in its head. */
    void assertCarrying(CranePhase phase, ItemKey key, int amount) {
        StackerCraneBlockEntity be = dock();
        helper.assertValueEqual(be.craneState().phase(), phase, "crane phase");
        helper.assertValueEqual(be.heldItems().count(key), amount, "held " + key);
        helper.assertValueEqual(be.craneState().heldAmount(), amount, "held amount of the job");
    }

    /** Idle, no job, empty head; with a controller also no reservation left. */
    void assertIdleAndEmpty() {
        StackerCraneBlockEntity be = dock();
        helper.assertValueEqual(be.craneState().phase(), CranePhase.IDLE, "crane idle");
        helper.assertTrue(be.currentJob().isEmpty(), "no job left");
        helper.assertTrue(be.heldItems().isEmpty(), "head empty");
        controllerIfPresent().ifPresent(present -> helper.assertTrue(present.reservations().isEmpty(),
                "no reservation left"));
    }

    /** Items of {@code key} in the inventory at a test-relative position (0 without inventory). */
    long inventoryCount(BlockPos pos, ItemKey key) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    /** Items of {@code key} in the inventory behind the storage location at {@code rack}. */
    long storedAt(RackPosition rack, ItemKey key) {
        return inventoryCount(inventoryPos(rack), key);
    }

    /** Items of {@code key} in the buffer of the station at {@code rack}; 0 if there is none. */
    long stationCount(RackPosition rack, ItemKey key) {
        BlockEntity be = helper.getLevel().getBlockEntity(absoluteRackPos(rack));
        if (be instanceof WarehouseInputBlockEntity input)
            return input.bufferedItems().count(key);
        // Warehouse output and warehouse terminal are both output-style delivery stations (ADR-018).
        if (be instanceof WarehouseDeliveryStationBlockEntity delivery)
            return delivery.bufferedItems().count(key);
        return 0L;
    }

    IItemHandler handlerAt(BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    void insertAll(IItemHandler handler, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
        helper.assertTrue(rest.isEmpty(), "inventory rejected " + rest);
    }

    static BlockState hopperState(Direction facing) {
        return Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing);
    }
}
