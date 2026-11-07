package dev.wareworks.content.requester;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.Tags;

/**
 * The Schematicannon Requester (issue #45, ADR-080): a block set against a Create Schematicannon that orders what the
 * cannon is missing at a warehouse terminal, through the linked Warehouse Remote that sits in its cradle.
 * <p>
 * <b>The front faces the cannon</b> ({@link #FACING}, all six directions, the cannon at {@code pos.relative(FACING)}):
 * a cannon has no facing of its own, and the chest, funnel or belt beside it often takes a side, so any side may be
 * the requester's. Placed against a cannon it faces that cannon; otherwise it faces the first cannon among its
 * neighbours, the clicked one first; with none it faces away from the player's click and its goggles say there is no
 * cannon. The wrench turns it like any directional block, and a sneaking wrench picks it up through
 * {@link SchematicannonRequesterBlockEntity#destroy}.
 * <p>
 * <b>Two lamps</b> ({@link #LIT}, {@link #REFUSED}), the home point's language: rose quartz lit while an order is on
 * its way, the powered lamp while something needs the player. The block entity writes them only when they change.
 * <p>
 * <b>Clicks</b> (§3): a Warehouse Remote goes in with a click from a real player; sneaking with an empty hand takes it
 * out (the Lectern Controller's gesture); an empty-hand click shows the status on the action bar. A wrench, goggles,
 * a clipboard and the Mechanical Arm keep their own click, as at the stations, and every other item is used as it would
 * be against any block. Fake players — Deployers — can neither put a remote in nor take one out. It has <b>no item
 * capability</b> on any face: the cannon takes every neighbour's inventory for its own and would pull the remote out.
 */
public class SchematicannonRequesterBlock extends DirectionalBlock
        implements IBE<SchematicannonRequesterBlockEntity>, IWrenchable {
    public static final MapCodec<SchematicannonRequesterBlock> CODEC = simpleCodec(SchematicannonRequesterBlock::new);

    /** The lamp: lit while an order is open, in the buffer or on its way. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** The <b>red</b> lamp: something needs the player. Outranks {@link #LIT} in the model. */
    public static final BooleanProperty REFUSED = BooleanProperty.create("refused");

    public SchematicannonRequesterBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(LIT, false)
                .setValue(REFUSED, false));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, LIT, REFUSED));
    }

    /**
     * Faces the cannon (§2): the clicked block when it is one, else the first cannon among the six neighbours starting
     * with the clicked side, else away from the click. Block states are read here and only here.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction clicked = context.getClickedFace().getOpposite();
        List<Direction> order = new ArrayList<>(List.of(Direction.values()));
        order.remove(clicked);
        order.add(0, clicked);
        for (Direction side : order) {
            if (AllBlocks.SCHEMATICANNON.has(level.getBlockState(pos.relative(side))))
                return defaultBlockState().setValue(FACING, side);
        }
        return defaultBlockState().setValue(FACING, clicked);
    }

    /** A rotated schematic or structure turns the requester with it, so it still faces its cannon. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * A Warehouse Remote goes in from wherever on the block it is clicked, the grace box included
     * ({@link GraceBehaviour#bypassesInput}). The client answers success at once, so the remote's own use — which
     * would open the terminal's screen — never runs; the server decides and says why on the action bar. The items that
     * act on the block keep their click; everything else passes to {@link #useWithoutItem}, which lets a held item be
     * used as against any block.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isFakePlayer())
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof WarehouseRemoteItem) {
            if (player instanceof ServerPlayer serverPlayer)
                withBlockEntityDo(level, pos, requester -> requester.putIn(serverPlayer, hand));
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (keepsItsOwnClick(stack, player))
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * The main hand without a remote: sneaking with it empty takes the remote out, an empty hand shows the status, and
     * anything else passes, so a held block is placed against this one as usual. Fake players pass.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player.isFakePlayer() || !player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer)
            withBlockEntityDo(level, pos, requester -> {
                if (player.isShiftKeyDown())
                    requester.takeOut(serverPlayer);
                else
                    requester.showStatus(serverPlayer);
            });
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * The station rule of {@code StationScreenClick#keepsItsOwnClick} (package-private there): a wrench where the
     * player may build turns or picks up the block, Create's goggles go on, a clipboard copies and pastes the grace, and
     * the Mechanical Arm is placed against the block, which is no arm target.
     */
    private static boolean keepsItsOwnClick(ItemStack stack, Player player) {
        if (stack.isEmpty())
            return false;
        if (stack.is(Tags.Items.TOOLS_WRENCH) || AllItems.WRENCH.isIn(stack))
            return player.mayBuild();
        return AllBlocks.MECHANICAL_ARM.isIn(stack) || AllBlocks.CLIPBOARD.isIn(stack) || AllItems.GOGGLES.isIn(stack);
    }

    /** The cannon in front changed: look at it on the next tick rather than at the next poll. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && neighborPos.equals(pos.relative(state.getValue(FACING))))
            withBlockEntityDo(level, pos, SchematicannonRequesterBlockEntity::pollSoon);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<SchematicannonRequesterBlockEntity> getBlockEntityClass() {
        return SchematicannonRequesterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SchematicannonRequesterBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.SCHEMATICANNON_REQUESTER.get();
    }
}
