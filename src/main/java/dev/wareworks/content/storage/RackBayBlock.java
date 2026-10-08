package dev.wareworks.content.storage;

import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.core.storage.BayFamily;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A rack bay: a storage location that <b>is</b> the block ({@code docs/warehouse-system.md} §3.8, M28, issue #20).
 * <p>
 * It holds one item type as a count rather than in slots — 64, 256 or 1 024 stacks by {@link BayTier} — and it carries
 * its own address, store filter and storage priority, so a fifty-bay rack wall is fifty blocks and not a hundred. There
 * is no warehouse interface in front of it, and nothing is attached behind it: the bay's own block entity owns the
 * items ({@link RackBayBlockEntity}).
 * <p>
 * It also works <b>standalone</b>, filled and emptied by hand long before there is a warehouse, which is what a block
 * without a ticker and with its own item handler is by construction: nothing it does needs a controller, a crane or an
 * aisle to exist.
 *
 * <h2>Three blocks, one block entity type</h2>
 * The tier is the <b>block</b>, not a block state property: the item model, the recipe and the upgrade path are per
 * item, and "a bay may carry nothing stronger above it" reads against three block ids. One block entity type serves all
 * three ({@code .validBlocks(...)} is varargs), so there is one registration, one capability registrar and one
 * renderer, and the tier is read off the block through {@link #tier()}.
 *
 * <h2>Facing</h2>
 * {@link #FACING} points <b>into the rack depth</b>, away from the aisle, exactly as a warehouse interface's does: the
 * aisle face is therefore {@code FACING.getOpposite()}, which is where the readable front, the crane's arm port and the
 * store filter's value box sit. That is not decoration — {@code LocationKind.STORAGE} makes
 * {@code facing() == sideDirection(side)} the alignment rule, so reusing it is what lets aisle discovery, membership,
 * addressing and the crane's reach accept a bay with no change at all.
 *
 * <h2>Columns, walls and the fill level live in {@link BayColumn}</h2>
 * The column rule (ADR-044), the shared uprights (ADR-050) and the fill level (ADR-047) are the same questions for an
 * item bay and for a fluid one, so all of it — {@link TieredBay#OVERLOADED}, {@link TieredBay#LEFT},
 * {@link TieredBay#RIGHT}, {@link TieredBay#FILL}, both column walks, the joins and the one block state write that
 * publishes them — is in {@link BayColumn} over the {@link TieredBay} interface this block implements (M30 step 3).
 * The block state properties are inherited from that interface, exactly as {@link #FACING} is inherited from
 * {@link HorizontalDirectionalBlock}, so {@code RackBayBlock.FILL} still means what it has always meant.
 * <p>
 * What is left here is what only an <b>item</b> bay can answer: which tier ladder it is on ({@link #bayFamily()},
 * {@link #mayCarry}), where its fill level comes from ({@link #fillStepAt}), its own refusal sentence
 * ({@link #columnRefusalMessage()}) and its hand gestures.
 */
public class RackBayBlock extends HorizontalDirectionalBlock implements IBE<RackBayBlockEntity>, IWrenchable, TieredBay {
    /**
     * Codec of the tier, by lower-case enum name. Defined here rather than on {@link BayTier}, which lives in the pure
     * {@code core} layer: an unknown name is a data error rather than an exception.
     */
    private static final Codec<BayTier> TIER_CODEC =
            Codec.stringResolver(tier -> tier.name().toLowerCase(Locale.ROOT), RackBayBlock::tierByName);

    /**
     * {@code simpleCodec} cannot be used: a bay's constructor takes its tier beside the block properties, and the three
     * blocks differ by nothing else.
     */
    public static final MapCodec<RackBayBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(TIER_CODEC.fieldOf("tier").forGetter(RackBayBlock::tier), propertiesCodec())
            .apply(instance, (tier, properties) -> new RackBayBlock(properties, tier)));

    private final BayTier tier;

    public RackBayBlock(Properties properties, BayTier tier) {
        super(properties);
        this.tier = Objects.requireNonNull(tier, "tier");
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(OVERLOADED, false)
                .setValue(FILL, 0).setValue(LEFT, false).setValue(RIGHT, false));
    }

    /** The material this bay is built from, which decides how much it holds and what it may carry above it. */
    public BayTier tier() {
        return tier;
    }

    /** The tier of {@code state}, or empty when {@code state} is not a rack bay at all. */
    @Nullable
    public static BayTier tierOf(BlockState state) {
        return state.getBlock() instanceof RackBayBlock bay ? bay.tier() : null;
    }

    @Nullable
    private static BayTier tierByName(String name) {
        for (BayTier tier : BayTier.values()) {
            if (tier.name().equalsIgnoreCase(name))
                return tier;
        }
        return null;
    }

    // --- a tiered bay ----------------------------------------------------------------------------------------------

    /** {@link BayFamily#ITEM}, through the tier ladder itself — so a fluid bay ends this bay's column. */
    @Override
    public BayFamily bayFamily() {
        return tier.family();
    }

    /**
     * The column rule for one pair, answered by the pure layer ({@link BayTier#mayCarry}), which is where it is
     * written and tested.
     * <p>
     * {@link BayColumn} only ever asks this with a bay of the same family, and every bay of {@link BayFamily#ITEM} is
     * a {@code RackBayBlock}, so the pattern match always succeeds; it is written as a match rather than a cast
     * because a second item-bay block would then have to say what it carries instead of crashing.
     */
    @Override
    public boolean mayCarry(TieredBay above) {
        return above instanceof RackBayBlock bay && tier.mayCarry(bay.tier());
    }

    /**
     * How full this bay looks, from the items its block entity holds against the capacity its tier and the stored item
     * give it ({@code RackBayBlockEntity#fillStep}), falling back to the state's own value while there is no block
     * entity to ask.
     */
    @Override
    public int fillStepAt(BlockGetter level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof RackBayBlockEntity bay ? bay.fillStep() : state.getValue(FILL);
    }

    /** "A rack bay may carry nothing stronger above it" — one sentence for both directions of the rule. */
    @Override
    public String columnRefusalMessage() {
        return WareworksLang.BAY_COLUMN_REFUSED;
    }

    // --- block -----------------------------------------------------------------------------------------------------

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, OVERLOADED, FILL, LEFT, RIGHT));
    }

    /**
     * Facing from the click, and the column rule: a placement that would make a column carry something stronger above
     * it is <b>refused</b> with {@code null} ({@link BayColumn#stateForPlacement}).
     */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return BayColumn.stateForPlacement(context, this, defaultBlockState());
    }

    /** A neighbour changed shape, so a shared upright may have appeared or gone ({@link BayColumn#updateShape}). */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return BayColumn.updateShape(state, direction, neighborState);
    }

    /** Drops the faces a seam buries, which nothing else would ({@link BayColumn#skipRendering}, ADR-050). */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return BayColumn.skipRendering(state, adjacentState, direction);
    }

    /**
     * The bay's own hand gesture: a plain right-click moves <b>one item</b>, Shift moves <b>one stack</b>, and that
     * holds both into the bay and out of it ({@link RackBayGestures}, ADR-045). An empty hand takes, an item in hand
     * puts in. Everything that is not that gesture — a wrench, a clipboard, the Mechanical Arm item, <b>another rack
     * bay</b> (which is how a wall grows: {@link BayColumn#placementFacing}), a transfer the bay would refuse — is
     * passed straight on, so it behaves exactly as it did before this block existed.
     * <p>
     * A plain click that really hits the value box in the middle of the aisle face never arrives here: Create's
     * {@code ValueSettingsInputHandler} cancels it for the store filter and the storage priority board, as it does on
     * a warehouse interface. A <b>sneaking</b> click does not arrive on its own either, which is why
     * {@link RackBayGestures} carries an interaction listener; the class comment there has the whole of it.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return RackBayGestures.use(stack, level, pos, player, hand);
    }

    /**
     * <b>No ticker on either side</b>, overriding {@link IBE}'s default. A basement of a thousand bays must cost
     * nothing per tick, exactly as a wall of warehouse interfaces does today: a bay works only on events — a transfer
     * through its handler, a player's click, a filter change, a load — and tells its controller the moment its contents
     * change instead of being polled.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        return null;
    }

    /**
     * The one trigger of the column rule on a standing wall ({@link BayColumn#neighborChanged}): the block directly
     * above changed, so this bay's flag is recomputed from a single block-state read.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        BayColumn.neighborChanged(level, pos, neighborPos);
    }

    /**
     * A bay that arrives <b>without</b> its own flags reads them from the world on a scheduled tick, so a bay placed
     * by {@code /setblock}, {@code /clone}, WorldEdit, a structure or a test ends up in the same state a player's
     * placement would have given it ({@link BayColumn#onPlace}).
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        BayColumn.onPlace(state, level, pos);
    }

    /**
     * The scheduled repair of {@link BayColumn#onPlace} and of {@code RackBayBlockEntity#onLoad}; a bay is never
     * randomly ticked ({@link BayColumn#publishState}).
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BayColumn.publishState(level, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<RackBayBlockEntity> getBlockEntityClass() {
        return RackBayBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RackBayBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.RACK_BAY.get();
    }
}
