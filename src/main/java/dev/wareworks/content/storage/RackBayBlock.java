package dev.wareworks.content.storage;

import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
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
 * <h2>The column rule (M28 step 5, ADR-044)</h2>
 * A bay may have <b>nothing stronger anywhere above it</b> in its column — no andesite on top of wood, because the
 * rack below would give way — which is what makes upgrading a wall a rebuild from the bottom up and the tiers a
 * progression rather than a label. A column is an unbroken stack of bays: the first block that is not a bay ends it, so
 * a gap is two racks and a brass bay on a roof says nothing about a wooden one in the basement.
 * <p>
 * It is enforced in two places that must not be confused:
 * <ul>
 * <li><b>Placement</b> is refused, in {@link #getStateForPlacement} by returning {@code null} —
 * {@code BlockItem.getPlacementState} passes that through and {@code BlockItem.place} answers {@code FAIL} without
 * placing the block or consuming the item. It walks the column <b>up and down</b> ({@link #columnAccepts}) and refuses
 * in <b>both</b> directions, nothing stronger above and nothing weaker below: the rule is "strength never rises
 * upwards", so refusing only upwards would let the illegal column be built from the top, and a player would hit the
 * refusal three rows later instead of on the first block.</li>
 * <li><b>A bay that is already standing</b> carries {@link #OVERLOADED}, a derived block state flag that is
 * <b>reported, never fixed</b>. {@code /setblock}, {@code /clone}, WorldEdit and structure placement all bypass
 * placement, and a bay that was popped or emptied for standing in a column a command broke would turn a command into
 * item loss — so an overloaded bay keeps its items and its address, is not offered as a store target
 * ({@code StorageMember#acceptsStoring}), <b>stays retrievable</b> (a filter never restricts retrieval, ADR-021), says
 * so on its goggles, and works again the moment the bay above it is gone. {@code canSurvive} is deliberately
 * <b>not</b> implemented, and neither is {@code updateShape}: a block that answers {@code canSurvive == false} while it
 * stands advertises to every other mod that it may be popped.</li>
 * </ul>
 * <b>{@link #OVERLOADED} is maintained in O(1)</b>, as the transitive closure of "the block directly above is a
 * stronger bay": {@code overloaded(pos) = above is a bay && (above is stronger || above.overloaded)}. One block-state
 * read answers it, and because writing it is itself a neighbour update for the bay below, a change walks <b>down</b>
 * the column one block at a time with no world search and no recursion. Recomputing the whole column on demand was
 * rejected: that is up to a few hundred block-state reads per question, and the question is asked by the job planner.
 * <p>
 * Two consequences of the closure worth knowing. It answers for the whole column below a weak link, so in a column a
 * command broke non-monotonically — say brass under wood under andesite — the brass bay is flagged too although nothing
 * above it is stronger than brass; that is the physical reading (the wood gives way and the wall comes down on the
 * brass), and it is why the flag means "something above me is giving way" rather than "the block above me is stronger".
 * And it never changes the model: it is a warning, not a look, the same call the warehouse port made for {@code powered}.
 */
public class RackBayBlock extends HorizontalDirectionalBlock implements IBE<RackBayBlockEntity>, IWrenchable {
    /**
     * Codec of the tier, by lower-case enum name. Defined here rather than on {@link BayTier}, which lives in the pure
     * {@code core} layer: an unknown name is a data error rather than an exception.
     */
    /**
     * Visible fill steps above "empty", so a bay has {@code FILL_LEVELS + 1} looks. Four reads as a rack and eight
     * would read as a gauge; a bay is a rack ({@link #FILL}).
     */
    public static final int FILL_LEVELS = 4;

    private static final Codec<BayTier> TIER_CODEC =
            Codec.stringResolver(tier -> tier.name().toLowerCase(Locale.ROOT), RackBayBlock::tierByName);

    /**
     * A standing bay that carries something stronger above it in its column — see the class comment. Derived, never
     * set by a player, and deliberately invisible in the model: it stops this bay being offered store jobs and prints
     * a gold goggle line, and that is all it does.
     */
    public static final BooleanProperty OVERLOADED = BooleanProperty.create("overloaded");

    /**
     * How full this bay looks, {@code 0} (empty) to {@link #FILL_LEVELS} (full) — the one thing about a bay a player
     * reads <b>without</b> goggles, by walking past a rack wall (M28 step 9).
     * <p>
     * It is a <b>block state</b> property and not renderer state, and that is the whole of the decision. A block entity
     * renderer puts every one of its blocks into its chunk section's per-frame render list at the vanilla 64-block
     * default, which is why the warehouse interface had to cut its own view distance to ten blocks
     * ({@code client.render.WarehouseInterfaceRenderer}); a rack wall is hundreds of blocks, so the same answer here
     * would either cost that every frame or make the fill level invisible from across the room. Block state geometry
     * is baked into the chunk mesh and is free at any distance.
     * <p>
     * It is <b>derived</b>, never set by a player, and it is the only state of a bay that notifies nothing: a fill
     * level is neither a membership change nor a store-settings change, so no controller cares
     * ({@code RackBayBlockEntity#setBlockState}).
     */
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, FILL_LEVELS);

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
                .setValue(FILL, 0));
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

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, OVERLOADED, FILL));
    }

    /**
     * Facing from the click, and the column rule: a placement that would make a column carry something stronger above
     * it is <b>refused</b> with {@code null}, which {@code BlockItem.place} turns into {@code FAIL} without placing the
     * block or consuming the item, and the player is told why in the action bar.
     * <p>
     * The message goes out on the server only. This method also runs on the client as part of its placement
     * prediction, and the client's HUD holds exactly one action-bar line at a time, so sending it on both sides would
     * either be a duplicate or a line the server's own answer overwrites.
     */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!columnAccepts(level, pos, tier)) {
            Player player = context.getPlayer();
            if (player != null && !level.isClientSide)
                player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.BAY_COLUMN_REFUSED), true);
            return null;
        }
        return defaultBlockState().setValue(FACING, placementFacing(context))
                .setValue(OVERLOADED, overloadedAt(level, pos, tier)).setValue(FILL, 0);
    }

    /**
     * Whether a bay of {@code tier} may stand at {@code pos}: nothing stronger anywhere above it in its column, and
     * nothing weaker anywhere below it. Both walks stop at the first block that is not a rack bay, and at the world's
     * build height, so the cost is the height of the one unbroken column the new bay would join — a handful of
     * block-state reads, once per click.
     * <p>
     * Both directions are needed and neither is redundant. "Nothing weaker below" is not merely the mirror of the
     * neighbour directly underneath: a column a command already broke can hold a weak bay deep under a strong one, so
     * every bay below is compared, not just the first.
     */
    public static boolean columnAccepts(BlockGetter level, BlockPos pos, BayTier tier) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        while (true) {
            cursor.move(Direction.UP);
            if (level.isOutsideBuildHeight(cursor))
                break;
            BayTier above = tierOf(level.getBlockState(cursor));
            if (above == null)
                break; // the column ends here: a gap is two racks
            if (!tier.mayCarry(above))
                return false;
        }
        cursor.set(pos);
        while (true) {
            cursor.move(Direction.DOWN);
            if (level.isOutsideBuildHeight(cursor))
                return true;
            BayTier below = tierOf(level.getBlockState(cursor));
            if (below == null)
                return true;
            if (!below.mayCarry(tier))
                return false;
        }
    }

    /**
     * Whether a bay of {@code tier} at {@code pos} carries something stronger above it — <b>one</b> block-state read,
     * because the flag on the bay above already answers for everything above <i>that</i> (see the class comment).
     */
    public static boolean overloadedAt(BlockGetter level, BlockPos pos, BayTier tier) {
        BlockPos above = pos.above();
        if (level.isOutsideBuildHeight(above))
            return false;
        BlockState state = level.getBlockState(above);
        BayTier aboveTier = tierOf(state);
        if (aboveTier == null)
            return false;
        return !tier.mayCarry(aboveTier) || state.getValue(OVERLOADED);
    }

    /** Whether {@code state} is a rack bay that carries something stronger above it; false for anything else. */
    public static boolean isOverloaded(BlockState state) {
        return state.getBlock() instanceof RackBayBlock && state.getValue(OVERLOADED);
    }

    /**
     * The {@link #FILL} step for {@code count} items out of {@code capacity}: {@code 0} only for an empty bay, and
     * {@link #FILL_LEVELS} only once the bay is really full.
     * <p>
     * It rounds <b>up</b>, deliberately. A single item has to make the rack look like it holds something — "is there
     * anything in this bay at all" is the question a player asks from across the room — and rounding down would leave
     * a wooden bay holding a thousand cobblestone looking exactly as empty as one holding none. The same reasoning
     * fixes the other end: only a bay that has nothing left shows step 0. A bay over its configured capacity (a
     * lowered config, {@code RackBayHandler#readFrom}) is clamped to full rather than overflowing the property.
     */
    public static int fillStep(int count, long capacity) {
        if (count <= 0)
            return 0;
        if (capacity <= 0)
            return FILL_LEVELS;
        long step = (count * (long) FILL_LEVELS + capacity - 1) / capacity;
        return (int) Math.max(1, Math.min(FILL_LEVELS, step));
    }

    /**
     * Facing for a player placement. A click on the <b>side</b> of another rack bay copies that bay's facing, which is
     * how a rack wall is built: place one bay facing the rack, then click along its side and the row grows with the
     * same orientation, however the player is standing. Everything else — a top or bottom click, a click that replaces
     * the clicked block, a click on anything that is not a bay — uses the player's horizontal look direction, i.e. the
     * aisle they are standing in.
     * <p>
     * Unlike a warehouse interface there is <b>no</b> "face the inventory you clicked" rule: a bay attaches to nothing,
     * so a chest beside it is just a chest.
     */
    public static Direction placementFacing(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (context.replacingClickedOnBlock() || !clickedFace.getAxis().isHorizontal())
            return context.getHorizontalDirection();
        // Not replacing: the new block goes next to the clicked block, on the clicked face.
        BlockState clicked = context.getLevel()
                .getBlockState(context.getClickedPos().relative(clickedFace.getOpposite()));
        if (clicked.getBlock() instanceof RackBayBlock)
            return clicked.getValue(FACING);
        return context.getHorizontalDirection();
    }

    /**
     * The bay's own hand gesture: a plain right-click moves <b>one item</b>, Shift moves <b>one stack</b>, and that
     * holds both into the bay and out of it ({@link RackBayGestures}, ADR-045). An empty hand takes, an item in hand
     * puts in. Everything that is not that gesture — a wrench, a clipboard, the Mechanical Arm item, <b>another rack
     * bay</b> (which is how a wall grows: {@link #placementFacing}), a transfer the bay would refuse — is passed
     * straight on, so it behaves exactly as it did before this block existed.
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
     * The one trigger of the column rule on a standing wall, and the whole of its cost: the block <b>directly above</b>
     * changed, so this bay's flag is recomputed from a single block-state read. A change at any other neighbour cannot
     * affect it — the flag only ever looks upwards — which is what keeps a wall of a thousand bays from doing any work
     * when a player builds beside it.
     * <p>
     * <b>This is also the case a naive implementation gets wrong.</b> When a bay is broken, the bay <i>below</i> it is
     * the one whose flag may now be stale, not the bay above: break the wooden bay out of a {@code wood / wood / brass}
     * column and the wood underneath has air above it and must stop refusing store jobs. An implementation that only
     * recomputed when a bay was <i>placed</i>, or that walked upwards from the change, would leave that bay refusing
     * every store job for ever while its goggles claimed a stronger bay stood in empty space.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && neighborPos.equals(pos.above()))
            publishState(level, pos);
    }

    /**
     * A bay that arrives <b>without</b> its own flag reads it from the world once, so a bay placed by
     * {@code /setblock}, {@code /clone}, WorldEdit, a structure or a test ends up in the same state a player's
     * placement would have given it. Only then does the column below it see the truth, because a bay's own flag is
     * what the bay underneath reads.
     * <p>
     * The repair is a <b>scheduled tick</b> and not a write from here, and that is not a preference. {@code onPlace}
     * runs inside {@code LevelChunk#setBlockState} <i>before</i> the block entity is created, and that method writes
     * the state it was called with into the block entity afterwards — so a block state written from here would be
     * overwritten in the block entity while the chunk kept the new one, leaving {@code getBlockState()} lying about
     * the flag the job planner reads. One tick later there is no such window. Nothing is scheduled at all when the
     * flag already agrees, which is every player placement and every legal command.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && state.getValue(OVERLOADED) != overloadedAt(level, pos, tier))
            level.scheduleTick(pos, this, 1);
    }

    /**
     * The scheduled repair of {@link #onPlace} and of {@code RackBayBlockEntity#onLoad}; a bay is never randomly
     * ticked. It is the one place both derived flags are brought in line with the world after a bay arrived by a route
     * that could not compute them — a command, a structure, a schematic, or a save whose configured capacity has
     * changed under it since.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        publishState(level, pos);
    }

    /**
     * Writes {@link #OVERLOADED} and {@link #FILL} if either is wrong, in <b>one</b> block state write, and nothing at
     * all when both already agree — which is what makes a wall of a thousand bays cost nothing while a player builds
     * beside it, and what makes the column walk below terminate.
     * <p>
     * <b>The update flags differ by what changed, and that is not a micro-optimisation.</b> A changed
     * {@link #OVERLOADED} needs {@code UPDATE_ALL}, because that write <i>is</i> the neighbour update the bay below
     * reads — it is how the flag travels down a column, one block-state read per block. A changed {@link #FILL} must
     * tell <b>nothing but the client</b>: it happens on every fourth item that enters or leaves a bay, and a wall of
     * bays beside which every observer, comparator and piston fires on goods moving is noise nobody asked for.
     * {@code UPDATE_CLIENTS} still writes the chunk, marks it unsaved and sends the state to every client, which is
     * the whole job of a fill level.
     * <p>
     * Dropping {@code UPDATE_NEIGHBORS} alone does <b>not</b> buy that silence, and it is worth saying exactly why:
     * comparators and pistons hook {@code neighborChanged}, which that flag gates, but an <b>observer</b> hooks
     * {@code updateShape}, and {@code Level.markAndNotifyBlock} runs {@code updateNeighbourShapes} plus both indirect
     * passes for any write whose flags lack {@code UPDATE_KNOWN_SHAPE} — handing each of the six neighbours the
     * direction pointing back at the bay, which is precisely what {@code ObserverBlock.updateShape} schedules its
     * pulse on. So the fill write carries {@code UPDATE_KNOWN_SHAPE} as well, which is safe here because this block
     * overrides no {@code getShape} and {@link #FILL} changes only the model: no neighbour's collision or support
     * shape depends on it. The {@link #OVERLOADED} write keeps the full {@code UPDATE_ALL}, shape pass included.
     */
    private void publishState(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() != this)
            return; // changed again while the neighbour update was queued
        boolean overloaded = overloadedAt(level, pos, tier);
        int fill = level.getBlockEntity(pos) instanceof RackBayBlockEntity bay ? bay.fillStep()
                : state.getValue(FILL);
        boolean columnChanged = state.getValue(OVERLOADED) != overloaded;
        if (!columnChanged && state.getValue(FILL) == fill)
            return;
        level.setBlock(pos, state.setValue(OVERLOADED, overloaded).setValue(FILL, fill),
                columnChanged ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /**
     * The contents of the bay at {@code pos} changed, so its {@link #FILL} may have to follow
     * ({@code RackBayBlockEntity#onContentsChanged}). Server only, and a no-op for anything that is not a bay — a
     * block entity whose block has already been replaced must not write a block state back.
     */
    public static void contentsChanged(Level level, BlockPos pos) {
        if (level.isClientSide)
            return;
        if (level.getBlockState(pos).getBlock() instanceof RackBayBlock bay)
            bay.publishState(level, pos);
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
