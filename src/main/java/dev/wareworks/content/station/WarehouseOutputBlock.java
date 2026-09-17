package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The warehouse output: retrieved items leave the warehouse here ({@code docs/warehouse-system.md} §3.2, §7.2).
 * <p>
 * A redstone <b>rising edge</b> submits the request defined by the filter slot (item and amount). {@link #POWERED}
 * stores the last seen signal, so holding a signal, further neighbour updates while powered and chunk reloads never
 * repeat a request; placement initialises it from the current signal, so placing the output next to a powered block
 * does not request anything: {@link #getStateForPlacement} for players, {@link #onPlace} for every other placement
 * ({@code /setblock}, structures, Create's schematicannon), which keep a stored or default state. {@code onPlace} stores
 * the signal without requesting (vanilla hopper pattern).
 */
public class WarehouseOutputBlock extends WarehouseStationBlock<WarehouseOutputBlockEntity> {
    public static final MapCodec<WarehouseOutputBlock> CODEC = simpleCodec(WarehouseOutputBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public WarehouseOutputBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(POWERED));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context)
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /**
     * A new output (not a state change of an existing one) takes the current signal level into {@link #POWERED} without
     * requesting, so the next unrelated neighbour update is no false rising edge.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || oldState.is(this))
            return;
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) != powered)
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_ALL);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide)
            return;
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) == powered)
            return;
        level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        if (powered)
            withBlockEntityDo(level, pos, WarehouseOutputBlockEntity::onRedstoneRisingEdge);
    }

    @Override
    public Class<WarehouseOutputBlockEntity> getBlockEntityClass() {
        return WarehouseOutputBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseOutputBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.get();
    }
}
