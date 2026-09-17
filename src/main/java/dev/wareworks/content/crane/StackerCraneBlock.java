package dev.wareworks.content.crane;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The stacker crane dock: anchor block, parking position (aisle position 0) and kinetic input of one crane
 * ({@code docs/stacker-crane.md} §1-2).
 * <p>
 * {@link #HORIZONTAL_FACING} is the aisle direction {@code f}: the aisle runs from the dock along the rails in front of
 * it. Rotation comes only from a shaft below ({@link #SHAFT_INPUT}, rotation axis Y, Millstone/Turntable pattern); the
 * dock is no cog, so neighbouring cogs and side shafts do not connect. {@code KineticBlock.onRemove} already forwards to
 * {@code IBE.onRemove}, so the block entity's {@code destroy()} runs on a real break.
 * <p>
 * The block is the low rail bed the parked crane stands on; the crane itself is drawn by its renderer and has no shape,
 * like the crane anywhere else in the aisle. Outline and collision therefore follow the bed model: a
 * {@value #BED_HEIGHT_PIXELS} px bed (the rail height) with the {@value #END_STOP_HEIGHT_PIXELS} px end stop at the
 * controller side ({@code docs/stacker-crane.md} §2.1).
 */
public class StackerCraneBlock extends HorizontalKineticBlock implements IBE<StackerCraneBlockEntity> {
    /** The only face that accepts a shaft. */
    public static final Direction SHAFT_INPUT = Direction.DOWN;
    /** Height of the rail bed in pixels (model, outline and collision): the height of the warehouse rails. */
    public static final int BED_HEIGHT_PIXELS = WarehouseRailBlock.HEIGHT_PIXELS;
    /** Top of the brass end stop at the controller side, in pixels. */
    public static final int END_STOP_HEIGHT_PIXELS = 4;

    private static final int FULL_PIXELS = 16;
    /** End stop footprint (model {@code end_stop}): 8 px wide, centred, 1 px deep at the controller side. */
    private static final int END_STOP_MIN_PIXELS = 4;
    private static final int END_STOP_MAX_PIXELS = 12;
    private static final int END_STOP_DEPTH_PIXELS = 1;
    /** Authored with the aisle direction north, like the model; the controller side is south. */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(Shapes.or(
            Block.box(0, 0, 0, FULL_PIXELS, BED_HEIGHT_PIXELS, FULL_PIXELS),
            Block.box(END_STOP_MIN_PIXELS, BED_HEIGHT_PIXELS, FULL_PIXELS - END_STOP_DEPTH_PIXELS, END_STOP_MAX_PIXELS,
                    END_STOP_HEIGHT_PIXELS, FULL_PIXELS)), Direction.NORTH);

    public StackerCraneBlock(Properties properties) {
        super(properties);
    }

    /**
     * The aisle runs in the player's horizontal look direction (away from the player). Create's
     * {@code HorizontalKineticBlock} default would face the player.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HORIZONTAL_FACING, context.getHorizontalDirection());
    }

    /** The rail bed with its end stop, turned to the aisle direction; also the collision shape. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(HORIZONTAL_FACING));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == SHAFT_INPUT;
    }

    /**
     * Wrenching the top or bottom face rotates the aisle direction, unless the crane refuses it (from M3 on: while it has
     * a job or holds items, because the aisle would change under a running job). A refused rotation tells the player why
     * in the action bar (server side, so it shows once).
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        StackerCraneBlockEntity be = getBlockEntity(context.getLevel(), context.getClickedPos());
        if (be != null && !be.canChangeAisleDirection()) {
            Player player = context.getPlayer();
            if (player != null && !context.getLevel().isClientSide)
                player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.CRANE_ROTATION_LOCKED), true);
            return InteractionResult.PASS;
        }
        return super.onWrenched(state, context);
    }

    @Override
    public Class<StackerCraneBlockEntity> getBlockEntityClass() {
        return StackerCraneBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StackerCraneBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.STACKER_CRANE.get();
    }
}
