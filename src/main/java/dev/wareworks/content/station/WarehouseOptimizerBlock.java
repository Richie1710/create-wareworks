package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Warehouse Optimizer (issue #46, ADR-079): the aisle member that lists the items a player wants moved up to better
 * storage locations — a higher priority, and a filter that fits no worse — and asks the warehouse to do it, on request
 * and for a bounded number of trips.
 * <p>
 * Built like the stock keeper ({@link WarehouseStockKeeperBlock}): placed towards the player ({@link #FACING},
 * {@link WarehouseStationBlock#placementFacing}), no ticker, drops itself, and holds <b>no items at all</b>. A
 * right-click opens its screen whatever is in the hand and a sneaking one places the block or uses the item; the wrench
 * still rotates it and a clipboard still opens its own editor ({@link StationScreenClick}), which is why a clipboard's
 * items are added in the screen, by clicking the clipboard onto the list cell, and never by clicking the block.
 */
public class WarehouseOptimizerBlock extends WarehouseStationBlock<WarehouseOptimizerBlockEntity> {
    public static final MapCodec<WarehouseOptimizerBlock> CODEC = simpleCodec(WarehouseOptimizerBlock::new);

    public WarehouseOptimizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /**
     * Lets the items that act on the optimizer keep their click — the wrench turns it, a clipboard opens its editor, and
     * the Mechanical Arm item is placed against it, because the optimizer is no arm target
     * ({@link StationScreenClick#actsOnTheBlock}); everything else opens the screen in {@link #useWithoutItem}.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        return StationScreenClick.useItemOn(stack, player);
    }

    /**
     * Opens the optimizer's screen, with or without an item in the main hand; a sneaking click that gets here is passed
     * on, as at the keeper. The answer is consumed on both sides even without a block entity, so the client never goes
     * on to place or use the held item ({@link StationScreenClick}).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player.isSecondaryUseActive())
            return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WarehouseOptimizerBlockEntity optimizer)
            optimizer.openScreen(serverPlayer);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<WarehouseOptimizerBlockEntity> getBlockEntityClass() {
        return WarehouseOptimizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseOptimizerBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_OPTIMIZER.get();
    }
}
