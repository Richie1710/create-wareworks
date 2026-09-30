package dev.wareworks.content.crane;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Warehouse rail: the thin floor rail a stacker crane travels on ({@code docs/warehouse-system.md} §1, ADR-033).
 * <p>
 * <b>Rails that touch, connect.</b> A warehouse is one connected set of rails, found by the dock's discovery
 * ({@link RailNetworkScan}) as plain orthogonal adjacency — no axis rule, no marker block, nothing to configure. The
 * rail has no block entity and no logic of its own.
 * <p>
 * Block states, and which of them the warehouse actually reads:
 * <ul>
 * <li>{@link #NORTH}, {@link #EAST}, {@link #SOUTH}, {@link #WEST} — <b>derived and purely cosmetic</b>, maintained
 * like a fence's, so the rail draws itself as a straight, a corner, a T, a cross or a stub. Discovery never reads them:
 * a stale flag in an untouched chunk is then a wrong picture that the first neighbour update repairs, never a wrong
 * warehouse;</li>
 * <li>{@link #CLOSED} — <b>the one state the topology reads</b>, and never derived. A closed rail is not an aisle
 * block, so discovery treats it as a wall. It is allowed to be logic because its failure direction is <i>less
 * network, visibly marked, one wrench click to undo</i>;</li>
 * <li>{@link #AXIS} — kept from before M21 and now cosmetic: it picks the model of a rail with no connections at all
 * (a lone decorative rail, and the item icon). Placement follows the player's look direction;</li>
 * <li>{@code WATERLOGGED} — unchanged.</li>
 * </ul>
 * The <b>wrench</b> toggles {@link #CLOSED}; it no longer turns the axis, which stopped meaning anything the moment a
 * second rail touched the block.
 */
public class WarehouseRailBlock extends Block implements IWrenchable, ProperWaterloggedBlock {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    /** A rail a wrench has closed: still a rail, but no part of any warehouse. */
    public static final BooleanProperty CLOSED = BooleanProperty.create("closed");
    /** Height of the rail in pixels (model and outline shape). */
    public static final int HEIGHT_PIXELS = 3;
    /** Top of the brass stop of a closed rail, in pixels (model {@code closed}). */
    public static final int STOP_HEIGHT_PIXELS = 6;
    private static final int FULL_PIXELS = 16;
    private static final int STOP_EDGE_PIXELS = 1;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, FULL_PIXELS, HEIGHT_PIXELS, FULL_PIXELS);
    /** A closed rail carries a brass stop across its bed, so its outline has to grow with it. */
    private static final VoxelShape CLOSED_SHAPE = Shapes.or(SHAPE, Block.box(STOP_EDGE_PIXELS, HEIGHT_PIXELS,
            STOP_EDGE_PIXELS, FULL_PIXELS - STOP_EDGE_PIXELS, STOP_HEIGHT_PIXELS, FULL_PIXELS - STOP_EDGE_PIXELS));

    public WarehouseRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.X).setValue(NORTH, false)
                .setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false).setValue(CLOSED, false)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(AXIS, NORTH, EAST, SOUTH, WEST, CLOSED, WATERLOGGED));
    }

    /** The connection flag of one horizontal direction. */
    public static BooleanProperty connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException("rails connect horizontally only: " + direction);
        };
    }

    /** The rail runs along the player's horizontal look direction; the connection flags follow the neighbours. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis());
        return withWater(withConnections(context.getLevel(), context.getClickedPos(), state), context);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(CLOSED) ? CLOSED_SHAPE : SHAPE;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return fluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        updateWater(level, state, pos);
        if (!direction.getAxis().isHorizontal())
            return state;
        boolean connects = !state.getValue(CLOSED) && connectsTowards(neighborState, direction.getOpposite());
        return state.setValue(connection(direction), connects);
    }

    /**
     * A rail that arrives without connection flags reads them from the world once, so a rail placed by a structure, a
     * schematic, {@code /setblock} or a test draws itself like any other. Idempotent: the second call finds the flags
     * it just wrote and changes nothing.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide)
            return;
        BlockState connected = withConnections(level, pos, state);
        if (connected != state)
            level.setBlock(pos, connected, Block.UPDATE_CLIENTS);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = state;
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
            rotated = rotated.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X
                    ? Direction.Axis.Z : Direction.Axis.X);
        for (Direction direction : Direction.Plane.HORIZONTAL)
            rotated = rotated.setValue(connection(rotation.rotate(direction)), state.getValue(connection(direction)));
        return rotated;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state;
        for (Direction direction : Direction.Plane.HORIZONTAL)
            mirrored = mirrored.setValue(connection(mirror.mirror(direction)), state.getValue(connection(direction)));
        return mirrored;
    }

    /**
     * The wrench closes an open rail and opens a closed one ({@code docs/warehouse-system.md} §1): the one way to keep
     * two warehouses whose rails touch apart, and the one cure for a stray rail that joined a network.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide)
            return InteractionResult.SUCCESS; // the server does the work; this only swings the arm
        BlockPos pos = context.getClickedPos();
        boolean closed = !state.getValue(CLOSED);
        BlockState next = withConnections(level, pos, state.setValue(CLOSED, closed));
        level.setBlock(pos, next, Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
        Player player = context.getPlayer();
        if (player != null)
            player.displayClientMessage(WareworksLang.translateDirect(
                    closed ? WareworksLang.RAIL_CLOSED : WareworksLang.RAIL_OPENED), true);
        return InteractionResult.SUCCESS;
    }

    /** {@code state} with every connection flag read from the world around {@code pos}. */
    public static BlockState withConnections(Level level, BlockPos pos, BlockState state) {
        BlockState connected = state;
        boolean closed = state.getValue(CLOSED);
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            boolean connects = !closed && level.isLoaded(cursor)
                    && connectsTowards(level.getBlockState(cursor), direction.getOpposite());
            connected = connected.setValue(connection(direction), connects);
        }
        return connected;
    }

    /**
     * Whether the aisle block {@code neighbour} offers a connection towards {@code towards}: a rail offers one in every
     * direction unless it is closed, a stacker crane dock only towards its own facing (so a rail beside the dock is a
     * rack position and not a branch, and another dock is a wall). Mirrors {@code RailGraph}'s rule exactly.
     */
    public static boolean connectsTowards(BlockState neighbour, Direction towards) {
        if (neighbour.getBlock() instanceof WarehouseRailBlock)
            return !neighbour.getValue(CLOSED);
        if (neighbour.getBlock() instanceof StackerCraneBlock)
            return neighbour.getValue(StackerCraneBlock.HORIZONTAL_FACING) == towards;
        return false;
    }

    /** Whether {@code state} is an open warehouse rail, i.e. an aisle block of whatever network reaches it. */
    public static boolean isOpenRail(BlockState state) {
        return state.getBlock() instanceof WarehouseRailBlock && !state.getValue(CLOSED);
    }

    /** Whether {@code state} is a warehouse rail, open or closed. */
    public static boolean isRail(BlockState state) {
        return state.getBlock() instanceof WarehouseRailBlock;
    }

    /** Whether {@code state} is a warehouse rail whose (cosmetic) axis is {@code axis}. */
    public static boolean isRailAlong(BlockState state, Direction.Axis axis) {
        return isRail(state) && state.getValue(AXIS) == axis;
    }

    /**
     * An open rail whose cosmetic axis is {@code axis}, without connection flags — what a builder places and what the
     * world then updates. The one-line factory the scenarios, Ponder and the GameTests build their aisles with.
     */
    public static BlockState along(Direction.Axis axis) {
        return WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(AXIS, axis);
    }
}
