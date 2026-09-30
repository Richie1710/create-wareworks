package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The warehouse home point ({@code docs/stacker-crane.md} §4.7, M21, issue #1, ADR-034): the rack position a stacker
 * crane with nothing to do waits at.
 * <p>
 * A player places it beside the rails wherever the next job usually starts — next to the terminal, next to the input —
 * and the crane parks in front of it instead of standing wherever its last job left it. Without a home point the dock
 * stays the crane's home, exactly as it always was.
 * <p>
 * <b>It sits beside the rails, not on them</b>, and that is a decision rather than a convenience: a home point is an
 * ordinary rack member, so it joins its warehouse through the machinery every other member uses, it has an
 * <b>address</b> that already names the aisle and the position the crane has to drive to, and the rails stay rails. A
 * block on the line would either break the chain the warehouse discovery walks or have to become an aisle block with
 * connection rules of its own — and a player could then never mark a corner, which is the one place a "wait here" is
 * worth most.
 * <p>
 * <b>Two lamps, two messages</b> ({@link #LIT}, {@link #REFUSED}), in the colours the rest of the mod already uses:
 * <ul>
 * <li>{@link #LIT} burns while this is the home point the crane really uses;</li>
 * <li>{@link #REFUSED} is the red lamp of everything a player has to act on: a <b>second</b> home point in the same
 * warehouse (only one crane, so only one home), or one on an aisle the crane cannot drive to. Neither is silently
 * ignored — the block says so, and its goggles say which of the two it is.</li>
 * </ul>
 * Both lamps are dark on a warehouse that never bends: a crane on a single straight aisle stays where it is, which is
 * what it always did, and the goggle tooltip says that rather than pretending to work.
 * <p>
 * Like every other member that faces the aisle it is placed towards the player
 * ({@link WarehouseStationBlock#placementFacing}), has no ticker, holds no items and drops itself.
 */
public class WarehouseHomePointBlock extends WarehouseStationBlock<WarehouseHomePointBlockEntity> {
    public static final MapCodec<WarehouseHomePointBlock> CODEC = simpleCodec(WarehouseHomePointBlock::new);

    /** The lamp: lit while the crane of this warehouse really waits here. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /**
     * The <b>red</b> lamp: this home point is not obeyed, because another one of the same warehouse comes first or
     * because the crane cannot drive to the aisle it stands on. It outranks {@link #LIT} in the model, as every "you
     * have to do something" lamp of this mod does.
     */
    public static final BooleanProperty REFUSED = BooleanProperty.create("refused");

    public WarehouseHomePointBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, false).setValue(REFUSED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(LIT).add(REFUSED));
    }

    @Override
    public Class<WarehouseHomePointBlockEntity> getBlockEntityClass() {
        return WarehouseHomePointBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseHomePointBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_HOME_POINT.get();
    }
}
