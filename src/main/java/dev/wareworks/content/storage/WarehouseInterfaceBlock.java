package dev.wareworks.content.storage;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * The warehouse interface: placed in front of any inventory, it makes that inventory a storage location of an aisle
 * ({@code docs/warehouse-system.md} §3.1).
 * <p>
 * {@link #FACING} points from the interface to its attached inventory, i.e. away from the aisle. The model shows the
 * brass port on that side. Rotating with the wrench (top or bottom face) turns it clockwise; the block entity then
 * re-targets its capability cache.
 */
public class WarehouseInterfaceBlock extends HorizontalDirectionalBlock
        implements IBE<WarehouseInterfaceBlockEntity>, IWrenchable {
    public static final MapCodec<WarehouseInterfaceBlock> CODEC = simpleCodec(WarehouseInterfaceBlock::new);

    public WarehouseInterfaceBlock(Properties properties) {
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

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, placementFacing(context));
    }

    /**
     * Facing for a player placement ({@code docs/warehouse-system.md} §3.1.1). For a click on a <i>side</i> face:
     * <ul>
     * <li>another warehouse interface: copy its facing, so a rack row is built by clicking the side of the previous
     * interface;</li>
     * <li>a block with a block entity (chest, barrel, vault, ...): face that block, so placing against an inventory
     * attaches to it;</li>
     * <li>anything else (stone, rack frames, rails): the player's horizontal look direction.</li>
     * </ul>
     * Top/bottom clicks and clicks that replace the clicked block (e.g. grass) use the look direction (standing in the
     * aisle and looking at the rack). Only block states are inspected, so client prediction and server agree.
     */
    public static Direction placementFacing(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (context.replacingClickedOnBlock() || !clickedFace.getAxis().isHorizontal())
            return context.getHorizontalDirection();
        // Not replacing: the new block goes next to the clicked block, on the clicked face.
        BlockState clicked = context.getLevel().getBlockState(context.getClickedPos().relative(clickedFace.getOpposite()));
        if (clicked.getBlock() instanceof WarehouseInterfaceBlock)
            return clicked.getValue(FACING);
        if (clicked.hasBlockEntity())
            return clickedFace.getOpposite();
        return context.getHorizontalDirection();
    }

    /**
     * No ticker on either side: the block entity works only on events (capability invalidation, neighbour changes,
     * goggle observation, snapshot requests), see {@link WarehouseInterfaceBlockEntity}.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        return null;
    }

    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(state, level, pos, neighbor);
        if (level.isClientSide())
            return;
        // Fired when a neighbouring block entity calls setChanged(), e.g. an attached chest whose contents changed.
        if (neighbor.equals(pos.relative(state.getValue(FACING))))
            withBlockEntityDo(level, pos, WarehouseInterfaceBlockEntity::onAttachedBlockChanged);
    }

    /** Block update from a neighbour: the attached inventory may have been placed, removed or replaced. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && neighborPos.equals(pos.relative(state.getValue(FACING))))
            withBlockEntityDo(level, pos, WarehouseInterfaceBlockEntity::onAttachedBlockChanged);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<WarehouseInterfaceBlockEntity> getBlockEntityClass() {
        return WarehouseInterfaceBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseInterfaceBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.get();
    }
}
