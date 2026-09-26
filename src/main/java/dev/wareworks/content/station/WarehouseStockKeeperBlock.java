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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, issue #3): the aisle member that holds the
 * <b>stock rules</b> of a warehouse — one item plus a minimum, a maximum and a reserve, per row.
 * <p>
 * Like the other members that face the aisle it is placed towards the player ({@link #FACING},
 * {@link WarehouseStationBlock#placementFacing}), has no ticker and drops itself. Unlike them it holds <b>no
 * items at all</b>: nothing is ever inserted, extracted or dropped here, and a crane never visits it.
 * <p>
 * <b>Two redstone-visible states, and they say different things.</b>
 * <ul>
 * <li>{@link #LIT} is the lamp: it burns while any rule of this keeper <b>bites</b> — below its minimum, at its
 * maximum or down to its reserve; {@link #PAUSED} is the same lamp in a different colour, for the safety stop. One sentence for all three numbers, which is the only way three numbers stay
 * learnable. It is written by the controller's rule tick and only on a real change, so a crane delivering a stack
 * produces one block update rather than 64, and with {@code UPDATE_CLIENTS} alone, because a lamp is something a
 * player reads and not something a neighbour reacts to.</li>
 * <li>The <b>comparator</b> reads how many rules of this keeper are below their minimum
 * ({@link WarehouseStockKeeperBlockEntity#comparatorSignal()}). A player who wants a signal for one single item puts a
 * second keeper next to it that holds only that rule — which is why the cheap per-item variant needs no second block
 * type.</li>
 * </ul>
 * A right-click with an empty hand opens the rule screen; anything held passes the interaction on, so the wrench still
 * rotates the block. The keeper registers no behaviours, so no value box can swallow that click either.
 */
public class WarehouseStockKeeperBlock extends WarehouseStationBlock<WarehouseStockKeeperBlockEntity> {
    public static final MapCodec<WarehouseStockKeeperBlock> CODEC = simpleCodec(WarehouseStockKeeperBlock::new);

    /** The lamp: lit while any rule of this keeper bites (below its minimum, at its maximum or at its reserve). */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /**
     * The <b>second</b> lamp: burning while the safety stop is holding one of this keeper's rules (M15 part 2,
     * issue #3), in a colour that cannot be confused with {@link #LIT}. A paused rule means a machine swallowed a
     * batch of ingredients and the warehouse stopped ordering until a player looks at it, which is a different message
     * from "this rule is biting" — and the only one that asks a player to do something.
     */
    public static final BooleanProperty PAUSED = BooleanProperty.create("paused");

    public WarehouseStockKeeperBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, false).setValue(PAUSED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(LIT).add(PAUSED));
    }

    /**
     * Right-clicking with an <b>empty hand</b> opens the rule screen, exactly as it does on a production station and a
     * terminal. Anything held passes the interaction on, so the wrench keeps rotating the block and a clipboard or a
     * future item keeps working.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty() || player.isSecondaryUseActive())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WarehouseStockKeeperBlockEntity keeper)
            keeper.openScreen(serverPlayer);
        return ItemInteractionResult.SUCCESS;
    }

    /** A comparator reads how many rules of this keeper call for their item ({@link #getAnalogOutputSignal}). */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /**
     * How many governing rules of this keeper are below their minimum, at most 15. The value is kept by the block
     * entity and refreshed by the controller's rule tick, so reading a comparator costs one field read and never a
     * stock lookup.
     */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof WarehouseStockKeeperBlockEntity keeper
                ? keeper.comparatorSignal() : 0;
    }

    @Override
    public Class<WarehouseStockKeeperBlockEntity> getBlockEntityClass() {
        return WarehouseStockKeeperBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseStockKeeperBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.get();
    }
}
