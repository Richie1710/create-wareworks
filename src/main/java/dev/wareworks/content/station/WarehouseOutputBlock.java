package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.core.port.PortDirection;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The warehouse port ({@code docs/warehouse-system.md} §3.2, §7.2): the station where items leave the warehouse. It
 * <b>requests</b> what its filter names, it <b>accepts</b> what the warehouse would otherwise store (M17, issue #12), or
 * it <b>collects</b> what the inventory behind it holds (M18, issue #13).
 * <p>
 * {@link #POWERED} stores the last seen signal, so holding a signal, further neighbour updates while powered and chunk
 * reloads never repeat an action; placement initialises it from the current signal, so placing the port next to a
 * powered block does not act: {@link #getStateForPlacement} for players, {@link #onPlace} for every other placement
 * ({@code /setblock}, structures, Create's schematicannon), which keep a stored or default state. {@code onPlace} stores
 * the signal without acting (vanilla hopper pattern). Both edges are reported to the block entity
 * ({@link WarehouseOutputBlockEntity#onRedstoneChanged}), because a port can act on either one.
 * <p>
 * <b>{@link #ACCEPTING} and {@link #COLLECTING} are derived display state, never the source of truth.</b> The port's direction lives in the block
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
    /**
     * Whether the port <b>collects</b> items out of the inventory behind it (M18, issue #13); derived from the block
     * entity's rank, exactly like {@link #ACCEPTING}, and never true together with it — the block entity re-asserts both
     * from the rank, so the one illegal combination of the two booleans is corrected instead of believed.
     */
    public static final BooleanProperty COLLECTING = BooleanProperty.create("collecting");

    public WarehouseOutputBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false).setValue(ACCEPTING, false)
                .setValue(COLLECTING, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(POWERED).add(ACCEPTING).add(COLLECTING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context)
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /**
     * The direction {@code state} shows. A state without the properties (a pre-M17 save) reads as "requests", and the one
     * illegal combination — both booleans true, which only a {@code /setblock} can produce — reads as "requests" too,
     * because the block entity is about to correct it anyway and "requests" is the direction that moves nothing.
     */
    public static PortDirection directionOf(BlockState state) {
        boolean accepting = state.getOptionalValue(ACCEPTING).orElse(false);
        boolean collecting = state.getOptionalValue(COLLECTING).orElse(false);
        if (accepting == collecting)
            return PortDirection.REQUEST;
        return accepting ? PortDirection.ACCEPT : PortDirection.COLLECT;
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

    /**
     * Fired when a neighbouring block entity calls {@code setChanged()}, e.g. the chest a <b>collecting</b> port is
     * pointed at whose contents changed (M18, issue #13). Filtered to the attached position, and it only marks the port's
     * collect snapshot dirty at its controllers — the same hint the warehouse interface has always given for its
     * inventory, which is what makes "the warehouse notices without polling" true for collecting too.
     * <p>
     * Gated on {@link #COLLECTING}, the cheapest state there is (M18 review). Vanilla calls this from
     * {@code BlockEntity#setChanged} for all six neighbours, so the block behind a <b>requesting</b> port — where a player
     * puts the chest or funnel that drains it, the default direction and the vast majority of ports — would otherwise walk
     * the hint through {@code WarehouseRegistry} into every controller of the level on every item moved, only for the
     * controller to drop it again. The one case the property can lag behind the block entity is a {@code /setblock} or a
     * schematic that places a configured port with a default state, and the throttled collect poll gives that port its
     * first read anyway, exactly as it does for a cold cache.
     */
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(state, level, pos, neighbor);
        if (level.isClientSide() || !state.getOptionalValue(COLLECTING).orElse(false)
                || !neighbor.equals(attachedPos(state, pos)))
            return;
        withBlockEntityDo(level, pos, WarehouseOutputBlockEntity::onAttachedBlockChanged);
    }

    /** The position a collecting port reaches into: the block behind it, the one a funnel on that face would drain. */
    private static BlockPos attachedPos(BlockState state, BlockPos pos) {
        Direction facing = state.getOptionalValue(FACING).orElse(Direction.NORTH);
        return pos.relative(facing.getOpposite());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide)
            return;
        // A block update from the attached position: the inventory may have been placed, removed or replaced. Not gated on
        // COLLECTING, unlike onNeighborChange: a block update is orders of magnitude rarer than a setChanged, and a port
        // turned around in the same tick as its inventory appeared should notice it at once.
        if (neighborPos.equals(attachedPos(state, pos)))
            withBlockEntityDo(level, pos, WarehouseOutputBlockEntity::onAttachedBlockChanged);
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
