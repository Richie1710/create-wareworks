package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The warehouse terminal: the player-facing request station of an aisle ({@code docs/warehouse-system.md} §3.4,
 * ADR-018, redesigned in M10 by ADR-022).
 * <p>
 * Unlike the other stations it has <b>two independent horizontal directions</b>:
 * <ul>
 * <li>{@link #FACING} is the <b>intake port</b>, the face the crane reaches through. It means exactly what
 * {@code FACING} means on a warehouse input or output — "towards the aisle" — so a terminal built before M10 keeps
 * working unchanged, and the controller re-derives it from the aisle it stands in
 * ({@link WarehouseTerminalBlockEntity#alignToAisle});</li>
 * <li>{@link #DISPLAY} is the <b>screen</b>, stored relative to the port ({@link TerminalDisplaySide}), so the screen
 * can never land on the port. It is chosen at placement (it faces the player) and turned by the wrench through the
 * three faces that are not the port.</li>
 * </ul>
 * The player therefore stands on the far side of the rack, reads the screen and takes items out there, while the crane
 * loads the terminal from the aisle like every other station.
 * <p>
 * It has <b>no redstone behaviour</b>: unlike the warehouse output it is operated through its screen, so it needs no
 * {@code POWERED} state and never reacts to neighbour updates.
 */
public class WarehouseTerminalBlock extends WarehouseStationBlock<WarehouseTerminalBlockEntity> {
    public static final MapCodec<WarehouseTerminalBlock> CODEC = simpleCodec(WarehouseTerminalBlock::new);

    /**
     * Where the screen sits relative to the intake port ({@link #FACING}). A block state saved before M10 has no value
     * for it and reads back as {@link TerminalDisplaySide#BACK}, the face opposite the aisle (ADR-022).
     */
    public static final EnumProperty<TerminalDisplaySide> DISPLAY =
            EnumProperty.create("display", TerminalDisplaySide.class);

    public WarehouseTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(DISPLAY, TerminalDisplaySide.BACK));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(DISPLAY));
    }

    /**
     * The <b>screen</b> faces the player, and the intake port starts on the opposite face: a player standing where they
     * want to read the terminal, with the aisle behind it, places an aligned terminal. Should the aisle turn out to be
     * to the left or the right instead, the controller moves the port there without moving the screen
     * ({@link WarehouseTerminalBlockEntity#alignToAisle}).
     * <p>
     * This is the one placement rule that differs from the other stations ({@link WarehouseStationBlock#placementFacing}
     * faces {@code FACING} towards the player, because there {@code FACING} <i>is</i> the side the player looks at).
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection())
                .setValue(DISPLAY, TerminalDisplaySide.BACK);
    }

    /**
     * The wrench turns the <b>screen</b> one quarter clockwise and leaves the intake port where it is, skipping the
     * quarter that would put the screen on the port ({@link TerminalDisplaySide#clockwise()}).
     * <p>
     * Two deviations from the other stations, both deliberate ({@code docs/warehouse-system.md} §3.4.3):
     * <ul>
     * <li>every face rotates, not only the top and the bottom: the port face is inside the aisle and the screen face is
     * the one a player faces, so restricting the wrench to the top would often mean breaking the block to reach it;</li>
     * <li>it is never refused while the crane works. Turning the screen does not move the port, so a delivery that is
     * already on its way is unaffected — unlike the stacker crane dock, whose rotation would turn the whole aisle.</li>
     * </ul>
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        if (!originalState.hasProperty(DISPLAY))
            return originalState;
        return originalState.setValue(DISPLAY, originalState.getValue(DISPLAY).clockwise());
    }

    /**
     * Right-clicking with an <b>empty hand</b> opens the terminal's screen ({@code docs/warehouse-system.md} §3.4.2).
     * <p>
     * Anything held passes the interaction on, so the wrench still rotates the block and a clipboard, a goggle-wearing
     * player's other tools and future items keep working. The terminal has no value box that could swallow the click
     * either: unlike the warehouse output it registers no behaviours.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty() || player.isSecondaryUseActive())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal)
            terminal.openScreen(serverPlayer);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public Class<WarehouseTerminalBlockEntity> getBlockEntityClass() {
        return WarehouseTerminalBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseTerminalBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.get();
    }
}
