package dev.wareworks.content.crane;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Warehouse rail: a thin floor rail that defines the length of an aisle ({@code docs/warehouse-system.md} §1).
 * <p>
 * The dock of a stacker crane counts consecutive rails in front of it whose {@link #AXIS} equals its facing axis. The
 * rail has no block entity and no logic of its own; the dock re-counts periodically, so placing or breaking rails needs
 * no notification. Placement follows the player's look direction; the wrench (top or bottom face) toggles the axis.
 * Waterloggable.
 */
public class WarehouseRailBlock extends Block implements IWrenchable, ProperWaterloggedBlock {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** Height of the rail in pixels (model and outline shape). */
    public static final int HEIGHT_PIXELS = 3;
    private static final int FULL_PIXELS = 16;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, FULL_PIXELS, HEIGHT_PIXELS, FULL_PIXELS);

    public WarehouseRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.X).setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(AXIS, WATERLOGGED));
    }

    /** The rail runs along the player's horizontal look direction. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withWater(defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis()), context);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return fluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        updateWater(level, state, pos);
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation != Rotation.CLOCKWISE_90 && rotation != Rotation.COUNTERCLOCKWISE_90)
            return state;
        return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }

    /** Whether {@code state} is a warehouse rail running along {@code axis}. */
    public static boolean isRailAlong(BlockState state, Direction.Axis axis) {
        return state.getBlock() instanceof WarehouseRailBlock && state.getValue(AXIS) == axis;
    }
}
