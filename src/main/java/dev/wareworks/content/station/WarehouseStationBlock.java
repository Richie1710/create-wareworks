package dev.wareworks.content.station;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Common block of the warehouse members that are <b>placed into a rack facing the aisle</b>: the input and output
 * stations, the terminal, the production station and the stock keeper ({@code docs/warehouse-system.md} §1, §3.2,
 * §3.6).
 * <p>
 * {@link #FACING} points from the station towards the aisle ({@code FACING == side.getOpposite()} for the rack side it
 * stands on); the model shows the crane opening on that side. Placement faces the player
 * ({@link #placementFacing}): standing in the aisle and placing into the rack gives the aligned facing. The wrench (top
 * or bottom face) rotates clockwise.
 * <p>
 * The block entity has no ticker: these members only react to insertions, extractions, redstone, goggle observation
 * and lifecycle events. A buffered one drops its buffer when the block is broken ({@code IBE.onRemove} →
 * {@code destroy()}).
 * <p>
 * The block entity type is bound to {@code BlockEntity} rather than to {@link WarehouseStationBlockEntity}, because
 * the warehouse stock keeper shares every rule above — placement, wrench rotation, no ticker, drops on removal — but
 * holds no items at all and therefore has no buffer (M15).
 *
 * @param <T> the block entity type
 */
public abstract class WarehouseStationBlock<T extends BlockEntity> extends HorizontalDirectionalBlock
        implements IBE<T>, IWrenchable {
    protected WarehouseStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
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
     * Placement facing: towards the player (the reverse of the horizontal look direction). A player standing in the aisle
     * and looking at the rack places an aligned station, whatever face is clicked.
     */
    public static Direction placementFacing(BlockPlaceContext context) {
        return context.getHorizontalDirection().getOpposite();
    }

    /** No ticker on either side (see the class comment). */
    @Override
    public <S extends BlockEntity> @Nullable BlockEntityTicker<S> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<S> type) {
        return null;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }
}
