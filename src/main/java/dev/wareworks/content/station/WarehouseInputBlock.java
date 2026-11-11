package dev.wareworks.content.station;

import java.util.List;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The warehouse input: items enter the warehouse here ({@code docs/warehouse-system.md} §3.2). Belts, funnels, chutes and
 * hoppers insert into its buffer; the stacker crane takes them out and stores them (M3).
 * <p>
 * An empty-hand click takes out what a Recipe Selector's quarantine keeps here (ADR-085 I7): the return input of a
 * selector station stores only the station's expected products, and the hold that something else arms asks the player
 * to take it out. Nothing else can be taken out by hand; the click on any other input does nothing.
 */
public class WarehouseInputBlock extends WarehouseStationBlock<WarehouseInputBlockEntity> {
    public static final MapCodec<WarehouseInputBlock> CODEC = simpleCodec(WarehouseInputBlock::new);

    public WarehouseInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<WarehouseInputBlockEntity> getBlockEntityClass() {
        return WarehouseInputBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseInputBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_INPUT.get();
    }

    /**
     * An empty-hand click: the server hands the player what the quarantine keeps in this input
     * ({@link WarehouseInputBlockEntity#takeQuarantined}) and says what was taken.
     * <p>
     * Vanilla calls this for the main hand whenever the held item's own use passes, so "empty hand" is checked here,
     * on both sides alike (review fix, Create API 4): a click with an item in the main hand is that item's click and
     * never takes anything out. Only the server knows the quarantine, so the client takes every empty-hand click on an
     * input as handled: answering PASS would make it predict nothing here but send the off-hand's use as well, which
     * the server would carry out too, placing an off-hand block against the input while the items are taken out.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player.isSecondaryUseActive() || !player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity input))
            return InteractionResult.PASS;
        List<ItemStack> taken = input.takeQuarantined();
        if (taken.isEmpty())
            return InteractionResult.PASS;
        for (ItemStack stack : taken)
            player.getInventory().placeItemBackInInventory(stack.copy());
        player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOK_OUT,
                taken.getFirst().getHoverName()), true);
        return InteractionResult.SUCCESS;
    }
}
