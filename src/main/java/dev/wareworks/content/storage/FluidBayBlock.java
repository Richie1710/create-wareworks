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
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
 * A fluid bay: a storage location that <b>is</b> a tank ({@code docs/warehouse-system.md} §3.9, M30, issue #21).
 * <p>
 * It is the rack bay of fluids ({@link RackBayBlock}) and it holds one fluid as a millibucket count — 64 buckets in
 * copper, 256 in brass by {@link FluidBayTier}, against the eight buckets per block of a Create Fluid Tank — with its
 * own address, filter and storage priority, so a tank wall is one block per 64 buckets and not a multiblock. Pipes
 * connect at the back and the sides; the aisle side is the readable front, where the level <b>is</b> the readout.
 * <p>
 * It also works <b>standalone</b>, filled and tapped long before there is a warehouse, which is what a block without a
 * ticker and with its own fluid handler is by construction: nothing it does needs a controller, a crane or an aisle to
 * exist ({@link FluidBayBlockEntity}).
 *
 * <h2>Two blocks, one block entity type</h2>
 * The tier is the <b>block</b>, as it is for a rack bay: the item model, the recipe and the upgrade path are per item,
 * and "a bay may carry nothing stronger above it" reads against two block ids. One block entity type serves both
 * ({@code .validBlocks(...)} is varargs), so there is one registration, one capability registrar and one renderer, and
 * the tier is read off the block through {@link #tier()}.
 * <p>
 * <b>There is no wooden and no andesite fluid bay</b>, unlike the three rack bays: in Create fluids are <b>copper</b> —
 * the pipes, the pumps, the tank casing — andesite plays no part in its fluid world, and a wooden barrel of lava is an
 * explanation nobody should owe ({@link FluidBayTier}).
 *
 * <h2>Facing</h2>
 * {@link #FACING} points <b>into the rack depth</b>, away from the aisle, exactly as a rack bay's and a warehouse
 * interface's does: the aisle face is therefore {@code FACING.getOpposite()}, which is where the readable front, the
 * crane's arm port and the filter's value box sit, and which is the one face that carries <b>no</b> fluid connection.
 * That is not decoration — {@code LocationKind.STORAGE} makes {@code facing() == sideDirection(side)} the alignment
 * rule, so reusing it is what lets aisle discovery, membership, addressing and the crane's reach accept a fluid bay
 * with no change at all.
 *
 * <h2>Columns and walls live in {@link BayColumn}; the fill level does not</h2>
 * The column rule (ADR-044) and the shared uprights (ADR-050) are the same questions for an item bay and for a fluid
 * one, so both are in {@link BayColumn} over the {@link TieredBay} interface this block implements. The <b>fill
 * level</b> is the one question that is not shared: ADR-047 makes an item bay's level block state geometry and ADR-053
 * makes a fluid bay's a renderer's, so this family publishes none and {@link BayColumn} skips it. This block answers
 * only what a <b>fluid</b> bay can: which tier ladder it is on
 * ({@link #bayFamily()}, {@link #mayCarry}), that its fill level is <b>not</b> a block state at all
 * ({@link #publishesFillLevel()}) and its own refusal sentence ({@link #columnRefusalMessage()}).
 * <p>
 * <b>Carrying is within a family and joining is across it</b>, which is what makes a fluid bay and a rack bay share a
 * seam post while neither carries the other: a rack bay above a fluid bay <i>ends</i> that column exactly as air does,
 * rather than being refused by it.
 */
public class FluidBayBlock extends HorizontalDirectionalBlock implements IBE<FluidBayBlockEntity>, IWrenchable,
        TieredBay {
    /**
     * Codec of the tier, by lower-case enum name. Defined here rather than on {@link FluidBayTier}, which lives in the
     * pure {@code core} layer: an unknown name is a data error rather than an exception.
     */
    private static final Codec<FluidBayTier> TIER_CODEC =
            Codec.stringResolver(tier -> tier.name().toLowerCase(Locale.ROOT), FluidBayBlock::tierByName);

    /**
     * {@code simpleCodec} cannot be used: a bay's constructor takes its tier beside the block properties, and the two
     * blocks differ by nothing else.
     */
    public static final MapCodec<FluidBayBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(TIER_CODEC.fieldOf("tier").forGetter(FluidBayBlock::tier), propertiesCodec())
            .apply(instance, (tier, properties) -> new FluidBayBlock(properties, tier)));

    private final FluidBayTier tier;

    public FluidBayBlock(Properties properties, FluidBayTier tier) {
        super(properties);
        this.tier = Objects.requireNonNull(tier, "tier");
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(OVERLOADED, false)
                .setValue(LEFT, false).setValue(RIGHT, false));
    }

    /** The material this bay is built from, which decides how much it holds and what it may carry above it. */
    public FluidBayTier tier() {
        return tier;
    }

    /** The tier of {@code state}, or {@code null} when {@code state} is not a fluid bay at all. */
    @Nullable
    public static FluidBayTier tierOf(BlockState state) {
        return state.getBlock() instanceof FluidBayBlock bay ? bay.tier() : null;
    }

    @Nullable
    private static FluidBayTier tierByName(String name) {
        for (FluidBayTier tier : FluidBayTier.values()) {
            if (tier.name().equalsIgnoreCase(name))
                return tier;
        }
        return null;
    }

    // --- a tiered bay ----------------------------------------------------------------------------------------------

    /** {@link BayFamily#FLUID}, through the tier ladder itself — so a rack bay ends this bay's column. */
    @Override
    public BayFamily bayFamily() {
        return tier.family();
    }

    /**
     * The column rule for one pair, answered by the pure layer ({@link FluidBayTier#mayCarry}), which is where it is
     * written and tested.
     * <p>
     * {@link BayColumn} only ever asks this with a bay of the same family, and every bay of {@link BayFamily#FLUID} is
     * a {@code FluidBayBlock}, so the pattern match always succeeds; it is written as a match rather than a cast
     * because a second fluid-bay block would then have to say what it carries instead of crashing.
     */
    @Override
    public boolean mayCarry(TieredBay above) {
        return above instanceof FluidBayBlock bay && tier.mayCarry(bay.tier());
    }

    /**
     * <b>A fluid bay has no fill level in its block state</b> (ADR-053): a fluid's look is its own still sprite out of
     * an open set, which no finite set of baked variants can name, so {@link FluidBayRenderer} draws the level from
     * the block entity and this block declares no {@link TieredBay#FILL} at all.
     * <p>
     * It is therefore never asked for a fill step and answers the empty one. {@link BayColumn} skips both the
     * question and the write for this family, so nothing here is a fallback for a missing block entity — there is no
     * property to keep in line.
     */
    @Override
    public boolean publishesFillLevel() {
        return false;
    }

    /** Never called; see {@link #publishesFillLevel()}. */
    @Override
    public int fillStepAt(BlockGetter level, BlockPos pos, BlockState state) {
        return 0;
    }

    /**
     * "A fluid bay may carry nothing stronger above it" — its own sentence, because "a rack bay may carry nothing
     * stronger above it" is the wrong thing to show a player holding a tank.
     */
    @Override
    public String columnRefusalMessage() {
        return WareworksLang.FLUID_BAY_COLUMN_REFUSED;
    }

    // --- block -----------------------------------------------------------------------------------------------------

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // No FILL: this family draws its level from its block entity (publishesFillLevel), so the property would be
        // five times the block states for something nothing reads.
        super.createBlockStateDefinition(builder.add(FACING, OVERLOADED, LEFT, RIGHT));
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
     * The bay's own hand gesture: a right-click with a <b>container</b> empties it into the bay, and one with an empty
     * container fills it from the bay ({@link FluidBayGestures}). Shift is not this block's gesture, and everything
     * that is not a container — a wrench, a clipboard, the Mechanical Arm item, <b>another bay</b> — is passed straight
     * on, so it behaves exactly as it did before this block existed.
     * <p>
     * A plain click that really hits the value box in the middle of the aisle face never arrives here: Create's
     * {@code ValueSettingsInputHandler} cancels it for the store filter and the storage priority board.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return FluidBayGestures.use(stack, level, pos, player, hand);
    }

    /**
     * The <b>first punch</b> at a bay that holds something says what breaking it costs (M30, issue #21, D7):
     * <b>breaking a fluid bay loses the fluid</b>, and this is the last of the three places that say so before a
     * player finds out — the item description and the goggle line are the other two. The fourth place, the same line
     * on a sneaking wrench click ({@link #onSneakWrenched}), arrives <i>with</i> the loss rather than before it.
     * <p>
     * It is the one warning that reaches a player who wears no goggles and read no tooltip, and it is deliberately
     * only a warning: {@code canSurvive}-style refusals were rejected for bays outright (M28's D8), and a block that
     * cannot be broken is worse than one that says what breaking it costs. {@code attack} is called once per break
     * attempt and changes nothing, so a player who means it simply keeps mining.
     * <p>
     * Two things about where it fires are worth knowing rather than discovering. It is the <b>server's</b> message,
     * because the client's HUD holds exactly one action-bar line at a time and both sides call {@code attack}
     * ({@code MultiPlayerGameMode#startDestroyBlock}), which is {@link BayColumn#stateForPlacement}'s own argument.
     * And a <b>creative</b> break never reaches it at all: {@code ServerPlayerGameMode#handleBlockBreakAction} returns
     * at {@code destroyAndAck} before {@code attack} is called for a creative player — which is a reason for the other
     * two places to exist rather than a gap in this one.
     */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        if (level.isClientSide || player == null || !(level.getBlockEntity(pos) instanceof FluidBayBlockEntity bay))
            return;
        bay.storedFluid().ifPresent(fluid -> player.displayClientMessage(
                WareworksLang.translateDirect(WareworksLang.FLUID_BAY_BREAK_LOSES, fluid.hoverName()), true));
    }

    /**
     * A <b>sneaking wrench</b> click takes the bay away, so it says what that costs on the way out (M30 review fix,
     * issue #21, D7).
     * <p>
     * {@link IWrenchable#onSneakWrenched} posts the break event, puts the block item into the player's inventory and
     * destroys the block without ever calling {@link #attack}, so Create's own relocation gesture reached <b>none</b>
     * of the in-world warnings — the one route by which a player who wears no goggles and read no tooltip could lose
     * 64 buckets of lava in silence. The same line the first punch shows is shown here instead.
     * <p>
     * It is a <b>notice rather than a warning</b>, and the difference is stated because a player cannot act on it: a
     * wrench is one click and this one removes the bay, where mining takes a punch that only warns and then a second
     * one that breaks. Refusing the first click instead was considered and rejected for the reason {@link #attack}
     * gives — a block that cannot be moved is worse than one that says what moving it costs — and refusing it at all
     * would be the {@code canSurvive}-style refusal M28's D8 rejected outright. {@code docs/warehouse-system.md} §3.9.1
     * and the manual checklist say which of the four places a wrench user really sees.
     */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!level.isClientSide && player != null
                && level.getBlockEntity(context.getClickedPos()) instanceof FluidBayBlockEntity bay)
            bay.storedFluid().ifPresent(fluid -> player.displayClientMessage(
                    WareworksLang.translateDirect(WareworksLang.FLUID_BAY_BREAK_LOSES, fluid.hoverName()), true));
        return IWrenchable.super.onSneakWrenched(state, context);
    }

    /**
     * <b>No ticker on either side</b>, overriding {@link IBE}'s default. A tank farm must cost nothing per tick,
     * exactly as a rack wall does: a bay works only on events — a pipe's transfer, a player's click, a filter change, a
     * load — and tells its controller the moment its contents change instead of being polled. It is also why the level
     * is drawn without interpolation: Create's tank glides because a {@code LerpedFloat} is advanced from its tick.
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
     * The scheduled repair of {@link BayColumn#onPlace} and of {@code FluidBayBlockEntity#onLoad}; a bay is never
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
    public Class<FluidBayBlockEntity> getBlockEntityClass() {
        return FluidBayBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FluidBayBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.FLUID_BAY.get();
    }
}
