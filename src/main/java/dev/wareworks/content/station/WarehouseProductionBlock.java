package dev.wareworks.content.station;

import java.util.List;

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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
 * opens, and <b>sneaking</b> with an empty hand lets the warehouse make this station's products again after the safety
 * stop held them (M20).
 */
public class WarehouseProductionBlock extends WarehouseStationBlock<WarehouseProductionBlockEntity> {
    public static final MapCodec<WarehouseProductionBlock> CODEC = simpleCodec(WarehouseProductionBlock::new);

    /**
     * Whether the <b>safety stop</b> is holding something this station makes (M20, issue #4, ADR-032): the ring around
     * both of its openings turns from brass into a lit rose quartz lamp, the same colour the stock keeper's own pause
     * lamp burns in ({@code WareworksBlockStateGen#productionBlockProvider()}).
     * <p>
     * It is the only surface of the stop a player does not have to open anything for. An aisle of production stations is
     * a row of identical blocks, and "which machine swallowed the batch" is the one question the goggles and the screens
     * can only answer <i>after</i> a player has already guessed which block to look at. The cue reads from inside the
     * aisle, where no drawn digit and no value box may go, and from the machine side, which is where the player is
     * standing when they come to fix it.
     * <p>
     * <b>Derived, never a player's setting and never saved in the block entity.</b> The controller's rule pass writes it
     * from its own pause map ({@code WarehouseProductionBlockEntity#refreshStoppedState}), only on a real change and with
     * {@code UPDATE_CLIENTS} alone — a lamp is something a player reads, not something a neighbour reacts to. A
     * {@code /setblock} that sets it by hand is corrected by the first pass after the controller loads, and by the pass
     * that follows the station rejoining the aisle ({@code WarehouseControllerBlockEntity#refreshProductionStops},
     * {@code #lightOrClearProductionStop}): a block state survives every save while the controller's record of the lit
     * stations does not, so exactly one sweep per load is what keeps this property true.
     */
    public static final BooleanProperty STOPPED = BooleanProperty.create("stopped");

    public WarehouseProductionBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(STOPPED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(STOPPED));
    }

    /**
     * Right-clicking with an <b>empty hand</b> opens the pattern screen ({@code docs/warehouse-system.md} §3.5), and
     * <b>sneaking</b> with an empty hand lets the warehouse make this station's products again after the safety stop
     * held them ({@link WarehouseProductionBlockEntity#resumeStoppedProducts()}, M20).
     * <p>
     * Anything held passes the interaction on, so the wrench still rotates the block and a clipboard or a future item
     * keeps working — the same rule the warehouse terminal follows. The station registers no behaviours, so no value
     * box can swallow the click either.
     * <p>
     * A sneaking player only reaches this method at all because {@link ProductionStationHooks} asks for it: vanilla drops
     * a sneaking interaction before the block whenever <i>either</i> hand holds something that does not bypass the sneak,
     * so a shield or a torch in the offhand would otherwise swallow the resume without a word. {@code useWithoutItem}
     * would not help — it sits inside the same guard.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station))
            return ItemInteractionResult.SUCCESS;
        if (player.isSecondaryUseActive()) {
            resume(player, station);
            return ItemInteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer)
            station.openScreen(serverPlayer);
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * The one way back from the safety stop that is always reachable: the player says the machine behind this station is
     * worth another batch, and is told which items the warehouse makes again — or that nothing here was stopped, because
     * a click that does nothing silently is a click nobody finds (M20, ADR-032).
     */
    private static void resume(Player player, WarehouseProductionBlockEntity station) {
        WarehouseProductionBlockEntity.tellResumed(player, station.resumeStoppedProducts());
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
