package dev.wareworks.content.controller;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * The warehouse controller: the logical management of one aisle ({@code docs/warehouse-system.md} §1, §3.3).
 * <p>
 * It stands directly behind a stacker crane dock and {@link #FACING} points at the dock. Placement uses the player's
 * horizontal look direction, so standing behind the dock and looking at it gives the right facing; the wrench (top or
 * bottom face) rotates it clockwise. A block update from the block in front of it (dock placed, broken or rotated)
 * makes the block entity re-link on its next tick.
 */
public class WarehouseControllerBlock extends HorizontalDirectionalBlock
        implements IBE<WarehouseControllerBlockEntity>, IWrenchable {
    public static final MapCodec<WarehouseControllerBlock> CODEC = simpleCodec(WarehouseControllerBlock::new);

    public WarehouseControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING));
    }

    /** Faces the player's horizontal look direction: from behind the dock, towards the dock. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide)
            return;
        if (neighborPos.equals(pos.relative(state.getValue(FACING))))
            withBlockEntityDo(level, pos, WarehouseControllerBlockEntity::requestRelink);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<WarehouseControllerBlockEntity> getBlockEntityClass() {
        return WarehouseControllerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseControllerBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.get();
    }
}
