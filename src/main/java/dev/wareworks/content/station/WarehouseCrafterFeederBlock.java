package dev.wareworks.content.station;

import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The Warehouse Crafter Feeder (issue #34, ADR-067): sits on the <b>back</b> of a warehouse production station, lays
 * that station's production patterns into the vanilla Crafter in front of it, one order at a time, and triggers the
 * Crafter once per run with a short redstone pulse.
 * <p>
 * <b>Wareworks still crafts nothing.</b> The vanilla Crafter crafts and puts its result into whatever stands in front of
 * its output — a Warehouse Input, where the crane stores it like any other product. The feeder only moves items from the
 * station's buffer into the Crafter's slots, which is what a player's funnel would have done if a funnel could lay a
 * recipe ({@link WarehouseCrafterFeederBlockEntity}).
 * <p>
 * {@link #FACING} points at the Crafter. It may face any way but at the station it serves
 * ({@link CrafterFeederAttachment}); a placement that would face that station is turned around. It is a kinetic consumer
 * like Create's encased shaft: the shaft passes through the two faces of the axis {@code axis_along_first} picks, so a
 * column of feeders behind a rack wall shares one vertical shaft.
 * <p>
 * <b>Redstone.</b> {@link #POWERING} is the feeder's own pulse: while it is set the feeder gives a <b>weak</b> signal of
 * 15 on its front face only, into the Crafter, like an observer's back. Weak, because a strongly powered Crafter would pass
 * the signal on to its other neighbours. The block conducts nothing itself.
 */
public class WarehouseCrafterFeederBlock extends DirectionalAxisKineticBlock
        implements IBE<WarehouseCrafterFeederBlockEntity> {
    /**
     * Whether the feeder is giving its pulse right now. Written only by its block entity, which keeps it high for
     * {@code FeederCycle#PULSE_HIGH_TICKS} ticks per run and lowers it on the first tick after a load.
     */
    public static final BooleanProperty POWERING = BooleanProperty.create("powering");

    /** Signal strength of the pulse. */
    public static final int PULSE_STRENGTH = 15;

    public WarehouseCrafterFeederBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(POWERING));
    }

    /**
     * Create's placement (facing the player, sneaking reverses it; the shaft lines up with a neighbouring one), except
     * that a feeder never faces the station whose back it is placed on: placed against a station's back, it faces away
     * from it, which is the straight station → feeder → Crafter build.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null)
            return null;
        Direction facing = state.getValue(FACING);
        BlockPos ahead = context.getClickedPos().relative(facing);
        if (CrafterFeederAttachment.backTouches(context.getLevel().getBlockState(ahead), facing))
            state = state.setValue(FACING, facing.getOpposite());
        return state.setValue(POWERING, false);
    }

    /** A wrench turn never carries a pulse into a new neighbour. */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        BlockState rotated = super.getRotatedBlockState(originalState, targetedFace);
        return rotated.hasProperty(POWERING) ? rotated.setValue(POWERING, false) : rotated;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * The pulse, on the front face only. {@code direction} is the direction from the asking block towards this one, so
     * the Crafter in front asks with {@code FACING.getOpposite()}.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERING) && direction == state.getValue(FACING).getOpposite() ? PULSE_STRENGTH : 0;
    }

    /** Never a strong signal: the Crafter must not pass the pulse on. */
    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 0;
    }

    /**
     * Sets {@link #POWERING} and tells the block in front, exactly as an observer does for its output, but without
     * updating that block's own neighbours: the signal is weak, so it ends in the Crafter.
     *
     * @return whether the state changed
     */
    public static boolean setPowering(Level level, BlockPos pos, boolean powering) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WarehouseCrafterFeederBlock block) || state.getValue(POWERING) == powering)
            return false;
        level.setBlock(pos, state.setValue(POWERING, powering), Block.UPDATE_CLIENTS);
        BlockPos front = pos.relative(state.getValue(FACING));
        level.neighborChanged(front, block, pos);
        return true;
    }

    @Override
    public Class<WarehouseCrafterFeederBlockEntity> getBlockEntityClass() {
        return WarehouseCrafterFeederBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseCrafterFeederBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER.get();
    }
}
