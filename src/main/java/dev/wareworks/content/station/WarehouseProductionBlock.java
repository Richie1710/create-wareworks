package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The warehouse production station ({@code docs/warehouse-system.md} §3.5, ADR-024): the aisle member the crane
 * delivers the <b>ingredients</b> of a production order to, so the player's Create machinery can turn them into
 * something else.
 * <p>
 * <b>Wareworks does not craft.</b> The station is a buffer with patterns on it: the crane fills it, a funnel, chute or
 * belt of the player's own machine empties it, and the product comes back into the warehouse through a normal
 * warehouse input. Its item capability is therefore extract-only, exactly like a warehouse output's.
 * <p>
 * Like the other stations it faces the aisle ({@link #FACING}, {@link WarehouseStationBlock#placementFacing}), has no
 * ticker and no redstone behaviour: the patterns are edited in its screen, which a right-click with an empty hand
 * opens.
 */
public class WarehouseProductionBlock extends WarehouseStationBlock<WarehouseProductionBlockEntity> {
    public static final MapCodec<WarehouseProductionBlock> CODEC = simpleCodec(WarehouseProductionBlock::new);

    public WarehouseProductionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /**
     * Right-clicking with an <b>empty hand</b> opens the pattern screen ({@code docs/warehouse-system.md} §3.5).
     * <p>
     * Anything held passes the interaction on, so the wrench still rotates the block and a clipboard or a future item
     * keeps working — the same rule the warehouse terminal follows. The station registers no behaviours, so no value
     * box can swallow the click either.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty() || player.isSecondaryUseActive())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station)
            station.openScreen(serverPlayer);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public Class<WarehouseProductionBlockEntity> getBlockEntityClass() {
        return WarehouseProductionBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseProductionBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.get();
    }
}
