package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.core.port.PortDirection;
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
 * The warehouse port ({@code docs/warehouse-system.md} §3.2, §7.2): the station where items leave the warehouse. It
 * <b>requests</b> what its filter names, or it <b>accepts</b> what the warehouse would otherwise store (M17, issue #12).
 * <p>
 * {@link #POWERED} stores the last seen signal, so holding a signal, further neighbour updates while powered and chunk
 * reloads never repeat an action; placement initialises it from the current signal, so placing the port next to a
 * powered block does not act: {@link #getStateForPlacement} for players, {@link #onPlace} for every other placement
 * ({@code /setblock}, structures, Create's schematicannon), which keep a stored or default state. {@code onPlace} stores
 * the signal without acting (vanilla hopper pattern). Both edges are reported to the block entity
 * ({@link WarehouseOutputBlockEntity#onRedstoneChanged}), because a port can act on either one.
 * <p>
 * <b>{@link #ACCEPTING} is derived display state, never the source of truth.</b> The port's direction lives in the block
 * entity's signed rank; this property only makes it visible without goggles and without the crosshair resting on the
 * block — the one thing a value box and a drawn digit cannot do, and the only thing that can be read <b>from the
 * aisle</b>, where the crane's arm port owns the face. The block entity re-asserts it whenever the rank changes and once
 * on load, so a {@code /setblock} with a mismatching value is corrected instead of believed. It is written with
 * {@code UPDATE_CLIENTS} alone: the direction is something a player reads, not something a neighbour reacts to, so no
 * observer or comparator contract is created (the same argument as the stock keeper's lamp).
 * <p>
 * It is a {@code BooleanProperty} rather than an enum over {@code core.port.PortDirection}, because an {@code EnumProperty}
 * needs {@code StringRepresentable} and {@code core.*} carries no Minecraft types at all.
 * <p>
 * <b>Rotation is untouched.</b> {@code IWrenchable#getRotatedBlockState} only changes {@code FACING} for a clicked face
 * on the Y axis, so the wrench on top or bottom keeps turning the station and {@code onSneakWrenched} keeps dismantling
 * it; the port's own settings are reached with a wrench on its value box ({@link PortRankValueBox}).
 */
public class WarehouseOutputBlock extends WarehouseStationBlock<WarehouseOutputBlockEntity> {
    public static final MapCodec<WarehouseOutputBlock> CODEC = simpleCodec(WarehouseOutputBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** Whether the port accepts items instead of requesting them; derived from the block entity's rank. */
    public static final BooleanProperty ACCEPTING = BooleanProperty.create("accepting");

    public WarehouseOutputBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false).setValue(ACCEPTING, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(POWERED).add(ACCEPTING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context)
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /** The direction {@code state} shows. A state without the property (a pre-M17 save) reads as "requests". */
    public static PortDirection directionOf(BlockState state) {
        return state.getOptionalValue(ACCEPTING).orElse(false) ? PortDirection.ACCEPT : PortDirection.REQUEST;
    }

    /**
     * A new port (not a state change of an existing one) takes the current signal level into {@link #POWERED} without
     * acting, so the next unrelated neighbour update is no false rising edge.
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
        // Both edges: a pulse acts on the rising one, "unless powered" starts on the falling one.
        withBlockEntityDo(level, pos, port -> port.onRedstoneChanged(powered));
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
