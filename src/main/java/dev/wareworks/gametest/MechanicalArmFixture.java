package dev.wareworks.gametest;

import java.util.List;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real Create mechanical arms in a GameTest, built the way a player builds them ({@code docs/warehouse-system.md} §3.2):
 * the arm, and a cogwheel beside it on a creative motor. One cogwheel drives every arm next to it.
 * <p>
 * <b>Selecting targets.</b> A player selects an arm's targets on the client: {@code ArmInteractionPointHandler} creates
 * a point with {@link ArmInteractionPoint#create} on the first right-click of a block and cycles its mode on that and
 * every further click, and {@link ArmPlacementPacket} sends the serialized selection to the server, which keeps it as
 * the arm's pending interaction point list. {@link #select} makes exactly those calls and {@link #serialize} uses the
 * packet's own constructor. The server half of the packet needs a connected player, so {@link #place} hands the list to
 * the arm through its saved data instead, the way a schematicannon places a configured arm ({@code SchematicPrinter}
 * loads the saved block entity data into the block it placed). Either way the arm resolves the list on its next tick
 * with {@code ArmInteractionPoint.deserialize}, which is the path these tests exercise.
 * <p>
 * {@link #place} loads the list before the arm has rotation ({@link #power} comes afterwards), so re-reading the arm's
 * own, still empty kinetic data changes nothing. Positions are test-relative; accessors fail the test when a block
 * entity is missing.
 */
final class MechanicalArmFixture {
    /** Right-clicks that select an ordinary point (a depot) for taking: the first click cycles "deposit" to "take". */
    static final int TAKE_CLICKS = 1;
    /** Right-clicks that select an ordinary point for depositing. */
    static final int DEPOSIT_CLICKS = 2;
    /** The fastest a creative motor turns by default; one arm movement then takes four ticks. */
    static final int ARM_RPM = 256;

    /** NBT key of the arm's interaction point list ({@code ArmBlockEntity#write}). */
    static final String INTERACTION_POINTS_TAG = "InteractionPoints";
    /** NBT key of a point's type id ({@code ArmInteractionPoint#serialize}). */
    static final String POINT_TYPE_TAG = "Type";
    /** NBT key of a point's mode ({@code ArmInteractionPoint#serialize}). */
    static final String POINT_MODE_TAG = "Mode";
    /** NBT key of the stack in the arm's claw ({@code ArmBlockEntity#write}). */
    private static final String HELD_ITEM_TAG = "HeldItem";

    private MechanicalArmFixture() {
    }

    /**
     * The point that {@code clicks} right-clicks with the arm item leave on the block at {@code pos}; fails the test when
     * no arm can target that block.
     */
    static ArmInteractionPoint select(GameTestHelper helper, BlockPos pos, int clicks) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockState state = helper.getLevel().getBlockState(absolute);
        ArmInteractionPoint point = ArmInteractionPoint.create(helper.getLevel(), absolute, state);
        if (point == null) {
            helper.fail("a mechanical arm cannot target " + state, pos);
            throw new IllegalStateException("unreachable");
        }
        for (int click = 0; click < clicks; click++)
            point.cycleMode();
        return point;
    }

    /** {@code selection} serialized for an arm at {@code arm}, exactly as {@link ArmPlacementPacket} sends it. */
    static ListTag serialize(GameTestHelper helper, BlockPos arm, List<ArmInteractionPoint> selection) {
        return new ArmPlacementPacket(selection, helper.absolutePos(arm)).tag();
    }

    /** An arm at {@code pos}, still without rotation, that works {@code selection}. */
    static ArmBlockEntity place(GameTestHelper helper, BlockPos pos, List<ArmInteractionPoint> selection) {
        return place(helper, pos, serialize(helper, pos, selection));
    }

    /** An arm at {@code pos}, still without rotation, whose saved interaction point list is {@code points}. */
    static ArmBlockEntity place(GameTestHelper helper, BlockPos pos, ListTag points) {
        helper.setBlock(pos, AllBlocks.MECHANICAL_ARM.getDefaultState());
        ArmBlockEntity arm = armAt(helper, pos);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = arm.saveWithoutMetadata(registries);
        saved.put(INTERACTION_POINTS_TAG, points);
        arm.loadWithComponents(saved, registries);
        return arm;
    }

    /**
     * A cogwheel at {@code cog} on a creative motor in the block below it, turning at {@value #ARM_RPM} RPM; every arm
     * beside the cogwheel runs.
     */
    static void power(GameTestHelper helper, BlockPos cog) {
        BlockPos motor = cog.below();
        helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, Direction.Axis.Y));
        CreativeMotorBlockEntity be = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(), helper.absolutePos(motor));
        if (be == null) {
            helper.fail("missing creative motor block entity", motor);
            return;
        }
        be.generatedSpeed.setValue(ARM_RPM);
    }

    static ArmBlockEntity armAt(GameTestHelper helper, BlockPos pos) {
        ArmBlockEntity be = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing mechanical arm block entity", pos);
        return be;
    }

    /** The stack in the arm's claw. An arm has no item capability and no accessor for it, but it saves the stack. */
    static ItemStack heldItem(ArmBlockEntity arm, HolderLookup.Provider registries) {
        return ItemStack.parseOptional(registries, arm.saveWithoutMetadata(registries).getCompound(HELD_ITEM_TAG));
    }

    /**
     * The interaction point list the arm saves. Once the arm has resolved its points, these are the resolved points
     * serialized again, i.e. what a world save keeps.
     */
    static ListTag savedPoints(ArmBlockEntity arm, HolderLookup.Provider registries) {
        return arm.saveWithoutMetadata(registries).getList(INTERACTION_POINTS_TAG, Tag.TAG_COMPOUND);
    }
}
