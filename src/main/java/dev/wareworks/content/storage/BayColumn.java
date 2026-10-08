package dev.wareworks.content.storage;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.storage.BayFamily;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The derived block state of a {@link TieredBay} and the rules that produce it: the column rule, the shared uprights
 * and the fill level (M30 step 3, issue #21; ADR-044, ADR-047, ADR-050).
 * <p>
 * All of it was written for the rack bay in M28 and M29 and is lifted here <b>unchanged</b> for M30's fluid bay, whose
 * columns, walls and fill level are the same questions about different goods. The only thing that is new is the
 * family: {@link TieredBay#bayFamily()} is compared <b>before</b> any strength, so a bay of the other family ends a
 * column exactly as air does, while joining asks for the facing alone and therefore reaches across families.
 *
 * <h2>Why the three flags share one class</h2>
 * {@link #OVERLOADED}, {@link #FILL} and the two join flags are written in <b>one</b> block state write
 * ({@link #publishState}), with update flags that differ by which of them changed, and the write is the only place
 * that knows which combination is cheap and which has to be loud. Splitting them into a column class, a join class and
 * a fill class would split that write, and two writes are two chunk sends and two rounds of neighbour updates for one
 * change.
 *
 * <h2>The column rule (ADR-044)</h2>
 * A bay may have <b>nothing stronger anywhere above it</b> in its column — no andesite on top of wood, because the
 * rack below would give way — which is what makes upgrading a wall a rebuild from the bottom up and the tiers a
 * progression rather than a label. A column is an unbroken stack of bays <b>of one family</b>: the first block that is
 * not such a bay ends it, so a gap is two racks and a brass bay on a roof says nothing about a wooden one in the
 * basement.
 * <p>
 * It is enforced in two places that must not be confused:
 * <ul>
 * <li><b>Placement</b> is refused, in {@link #stateForPlacement} by returning {@code null} —
 * {@code BlockItem.getPlacementState} passes that through and {@code BlockItem.place} answers {@code FAIL} without
 * placing the block or consuming the item. It walks the column <b>up and down</b> ({@link #accepts}) and refuses in
 * <b>both</b> directions, nothing stronger above and nothing weaker below: the rule is "strength never rises
 * upwards", so refusing only upwards would let the illegal column be built from the top, and a player would hit the
 * refusal three rows later instead of on the first block.</li>
 * <li><b>A bay that is already standing</b> carries {@link TieredBay#OVERLOADED}, a derived block state flag that is
 * <b>reported, never fixed</b>. {@code /setblock}, {@code /clone}, WorldEdit and structure placement all bypass
 * placement, and a bay that was popped or emptied for standing in a column a command broke would turn a command into
 * item loss — so an overloaded bay keeps its contents and its address, is not offered as a store target
 * ({@code StorageMember#acceptsStoring}), <b>stays retrievable</b> (a filter never restricts retrieval, ADR-021), says
 * so on its goggles, and works again the moment the bay above it is gone. {@code canSurvive} is deliberately
 * <b>not</b> implemented, and neither is a shape-based support rule: a block that answers {@code canSurvive == false}
 * while it stands advertises to every other mod that it may be popped.</li>
 * </ul>
 * <b>{@link TieredBay#OVERLOADED} is maintained in O(1)</b>, as the transitive closure of "the block directly above is
 * a stronger bay of the same family": {@code overloaded(pos) = above is such a bay && (above is stronger ||
 * above.overloaded)}. One block-state read answers it, and because writing it is itself a neighbour update for the bay
 * below, a change walks <b>down</b> the column one block at a time with no world search and no recursion. Recomputing
 * the whole column on demand was rejected: that is up to a few hundred block-state reads per question, and the
 * question is asked by the job planner.
 * <p>
 * Two consequences of the closure worth knowing. It answers for the whole column below a weak link, so in a column a
 * command broke non-monotonically — say brass under wood under andesite — the brass bay is flagged too although
 * nothing above it is stronger than brass; that is the physical reading (the wood gives way and the wall comes down on
 * the brass), and it is why the flag means "something above me is giving way" rather than "the block above me is
 * stronger". And it never changes the model: it is a warning, not a look, the same call the warehouse port made for
 * {@code powered}.
 */
public final class BayColumn {
    private BayColumn() {
    }

    /**
     * The bay {@code state} is, or {@code null} for anything that is not a bay at all — the one place the question is
     * asked, so that "is this a bay" and "which bay is it" are never two reads of the same block state.
     */
    @Nullable
    public static TieredBay bayAt(BlockState state) {
        return state.getBlock() instanceof TieredBay bay ? bay : null;
    }

    /**
     * The bay {@code state} is <b>if it belongs to {@code family}</b>, and {@code null} otherwise — which is how a bay
     * of the other family ends a column exactly as air does ({@link BayFamily}).
     * <p>
     * Every walk and every closure read in this class goes through here, so "carrying is within a family" is
     * structural rather than a rule somebody has to remember at each comparison: a bay of another family never
     * reaches {@link TieredBay#mayCarry}, so it can never be refused — it can only end the column.
     */
    @Nullable
    public static TieredBay sameFamilyBay(BlockState state, BayFamily family) {
        TieredBay bay = bayAt(state);
        return bay != null && bay.bayFamily() == family ? bay : null;
    }

    /**
     * Whether a bay of {@code bay}'s kind may stand at {@code pos}: nothing stronger anywhere above it in its column,
     * and nothing weaker anywhere below it. Both walks stop at the first block that is not a bay of the same family,
     * and at the world's build height, so the cost is the height of the one unbroken column the new bay would join — a
     * handful of block-state reads, once per click.
     * <p>
     * Both directions are needed and neither is redundant. "Nothing weaker below" is not merely the mirror of the
     * neighbour directly underneath: a column a command already broke can hold a weak bay deep under a strong one, so
     * every bay below is compared, not just the first.
     */
    public static boolean accepts(BlockGetter level, BlockPos pos, TieredBay bay) {
        BayFamily family = bay.bayFamily();
        BlockPos.MutableBlockPos cursor = pos.mutable();
        while (true) {
            cursor.move(Direction.UP);
            if (level.isOutsideBuildHeight(cursor))
                break;
            TieredBay above = sameFamilyBay(level.getBlockState(cursor), family);
            if (above == null)
                break; // the column ends here: a gap, another block, or a bay of the other family, is two racks
            if (!bay.mayCarry(above))
                return false;
        }
        cursor.set(pos);
        while (true) {
            cursor.move(Direction.DOWN);
            if (level.isOutsideBuildHeight(cursor))
                return true;
            TieredBay below = sameFamilyBay(level.getBlockState(cursor), family);
            if (below == null)
                return true;
            if (!below.mayCarry(bay))
                return false;
        }
    }

    /**
     * Whether a bay of {@code bay}'s kind at {@code pos} carries something stronger above it — <b>one</b> block-state
     * read, because the flag on the bay above already answers for everything above <i>that</i> (see the class
     * comment).
     */
    public static boolean overloadedAt(BlockGetter level, BlockPos pos, TieredBay bay) {
        BlockPos above = pos.above();
        if (level.isOutsideBuildHeight(above))
            return false;
        BlockState state = level.getBlockState(above);
        TieredBay aboveBay = sameFamilyBay(state, bay.bayFamily());
        if (aboveBay == null)
            return false;
        return !bay.mayCarry(aboveBay) || state.getValue(TieredBay.OVERLOADED);
    }

    /** Whether {@code state} is a bay that carries something stronger above it; false for anything else. */
    public static boolean isOverloaded(BlockState state) {
        return bayAt(state) != null && state.getValue(TieredBay.OVERLOADED);
    }

    /**
     * The {@link TieredBay#FILL} step for {@code count} units out of {@code capacity}: {@code 0} only for an empty
     * bay, and {@link TieredBay#FILL_LEVELS} only once the bay is really full.
     * <p>
     * It rounds <b>up</b>, deliberately. A single item has to make the rack look like it holds something — "is there
     * anything in this bay at all" is the question a player asks from across the room — and rounding down would leave
     * a wooden bay holding a thousand cobblestone looking exactly as empty as one holding none. The same reasoning
     * fixes the other end: only a bay that has nothing left shows step 0. A bay over its configured capacity (a
     * lowered config, {@code RackBayHandler#readFrom}) is clamped to full rather than overflowing the property.
     *
     * @param count    what the bay holds, in whatever unit its family counts in — items, or millibuckets
     * @param capacity how much it holds when full, in the same unit; at or below 0 counts as full
     */
    public static int fillStep(int count, long capacity) {
        if (count <= 0)
            return 0;
        if (capacity <= 0)
            return TieredBay.FILL_LEVELS;
        long step = (count * (long) TieredBay.FILL_LEVELS + capacity - 1) / capacity;
        return (int) Math.max(1, Math.min(TieredBay.FILL_LEVELS, step));
    }

    /**
     * {@code state} with {@link TieredBay#LEFT} and {@link TieredBay#RIGHT} read off the two bays beside {@code pos}.
     * Two block-state reads, and never a search: the only neighbours a bay's picture depends on are the two its
     * uprights are shared with.
     */
    public static BlockState withJoins(BlockGetter level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        return state.setValue(TieredBay.LEFT, joins(level, pos, facing, facing.getCounterClockWise()))
                .setValue(TieredBay.RIGHT, joins(level, pos, facing, facing.getClockWise()));
    }

    /**
     * Whether a bay facing {@code facing} at {@code pos} shares its upright with the block on {@code side}: that block
     * is a bay and looks the same way. Neither the tier nor the <b>family</b> is compared
     * ({@link TieredBay#LEFT}) — joining is across families, and carrying is within them.
     */
    private static boolean joins(BlockGetter level, BlockPos pos, Direction facing, Direction side) {
        return joinsTowards(level.getBlockState(pos.relative(side)), facing);
    }

    /** Whether {@code neighbour} is a bay that a bay facing {@code facing} shares an upright with. */
    public static boolean joinsTowards(BlockState neighbour, Direction facing) {
        return bayAt(neighbour) != null && neighbour.getValue(HorizontalDirectionalBlock.FACING) == facing;
    }

    /**
     * A neighbour changed shape, so a shared upright may have appeared or gone. Only the two sides beside the aisle
     * face can carry one, so every other direction — including the column the overload flag travels down, which is
     * {@link #neighborChanged}'s business and not this one's — returns the state untouched.
     */
    public static BlockState updateShape(BlockState state, Direction direction, BlockState neighborState) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        boolean joined = joinsTowards(neighborState, facing);
        if (direction == facing.getCounterClockWise())
            return state.setValue(TieredBay.LEFT, joined);
        if (direction == facing.getClockWise())
            return state.setValue(TieredBay.RIGHT, joined);
        return state;
    }

    /**
     * Drops the faces a seam buries, which nothing else would ({@code docs/warehouse-system.md} §3.8, ADR-050).
     * <p>
     * Two joined bays meet at the block boundary with six coincident face pairs: the shell's {@code deck} and
     * {@code back}, and the four elements of the {@code upright_half} each bay draws there. Every one of them carries
     * a {@code cullface} towards that boundary, so they are offered to {@code Block#shouldRenderFace} — and a bay is
     * {@code noOcclusion()}, so that method answers <b>true</b> for all of them and the chunk mesh keeps twelve quads
     * per seam that no camera can ever see. On a 20 x 5 wall that is 1 140 of 6 000 quads.
     * <p>
     * {@code skipRendering} is the one hook that is asked before the occlusion test, and it is asked <b>only</b> for
     * quads that carry a {@code cullface} in this direction — so answering true here drops exactly the buried twelve
     * and can never take a face a player could see. The join is symmetric ({@link #joinsTowards}), so both bays drop
     * their half of a seam and neither is left looking into the other. Only the two sides beside the aisle face can
     * carry a seam; up, down, the aisle and the depth are left to vanilla.
     */
    public static boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        if (direction != facing.getClockWise() && direction != facing.getCounterClockWise())
            return false;
        return joinsTowards(adjacentState, facing);
    }

    /**
     * Facing for a player placement. A click on the <b>side</b> of another bay copies that bay's facing, which is how
     * a rack wall is built: place one bay facing the rack, then click along its side and the row grows with the same
     * orientation, however the player is standing — and it reaches across families for the same reason joining does,
     * so a tank added to the end of a rack wall lines up with it. Everything else — a top or bottom click, a click
     * that replaces the clicked block, a click on anything that is not a bay — uses the player's horizontal look
     * direction, i.e. the aisle they are standing in.
     * <p>
     * Unlike a warehouse interface there is <b>no</b> "face the inventory you clicked" rule: a bay attaches to
     * nothing, so a chest beside it is just a chest.
     */
    public static Direction placementFacing(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (context.replacingClickedOnBlock() || !clickedFace.getAxis().isHorizontal())
            return context.getHorizontalDirection();
        // Not replacing: the new block goes next to the clicked block, on the clicked face.
        BlockState clicked = context.getLevel()
                .getBlockState(context.getClickedPos().relative(clickedFace.getOpposite()));
        return bayAt(clicked) != null ? clicked.getValue(HorizontalDirectionalBlock.FACING)
                : context.getHorizontalDirection();
    }

    /**
     * {@code base} with the facing from the click and both derived flags read off the world, or <b>{@code null}</b>
     * when the column rule refuses the placement — which {@code BlockItem.place} turns into {@code FAIL} without
     * placing the block or consuming the item, and the player is told why in the action bar
     * ({@link TieredBay#columnRefusalMessage()}).
     * <p>
     * The message goes out on the server only. {@code getStateForPlacement} also runs on the client as part of its
     * placement prediction, and the client's HUD holds exactly one action-bar line at a time, so sending it on both
     * sides would either be a duplicate or a line the server's own answer overwrites.
     *
     * @param base the bay's default block state, which is the one thing only the block itself can hand over
     */
    @Nullable
    public static BlockState stateForPlacement(BlockPlaceContext context, TieredBay bay, BlockState base) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!accepts(level, pos, bay)) {
            Player player = context.getPlayer();
            if (player != null && !level.isClientSide)
                player.displayClientMessage(WareworksLang.translateDirect(bay.columnRefusalMessage()), true);
            return null;
        }
        BlockState placed = base.setValue(HorizontalDirectionalBlock.FACING, placementFacing(context))
                .setValue(TieredBay.OVERLOADED, overloadedAt(level, pos, bay));
        // A fresh bay is empty, so its fill level is 0 - for a family that has one at all (TieredBay#FILL).
        if (bay.publishesFillLevel())
            placed = placed.setValue(TieredBay.FILL, 0);
        return withJoins(level, pos, placed);
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
    public static void neighborChanged(Level level, BlockPos pos, BlockPos neighborPos) {
        if (!level.isClientSide && neighborPos.equals(pos.above()))
            publishState(level, pos);
    }

    /**
     * A bay that arrives <b>without</b> its own flags schedules a repair, so a bay placed by {@code /setblock},
     * {@code /clone}, WorldEdit, a structure or a test ends up in the same state a player's placement would have given
     * it. Only then does the column below it see the truth, because a bay's own flag is what the bay underneath reads.
     * <p>
     * The repair is a <b>scheduled tick</b> and not a write from here, and that is not a preference. {@code onPlace}
     * runs inside {@code LevelChunk#setBlockState} <i>before</i> the block entity is created, and that method writes
     * the state it was called with into the block entity afterwards — so a block state written from here would be
     * overwritten in the block entity while the chunk kept the new one, leaving {@code getBlockState()} lying about
     * the flag the job planner reads. One tick later there is no such window. Nothing is scheduled at all when the
     * flags already agree, which is every player placement and every legal command.
     * <p>
     * The joins come along for the ride, and this is the only path that repairs them for a bay that was turned by a
     * wrench: a rotation changes which two neighbours the uprights are shared with, and a block's own
     * {@code updateShape} is never called for a write at its own position.
     */
    public static void onPlace(BlockState state, Level level, BlockPos pos) {
        if (level.isClientSide)
            return;
        TieredBay bay = bayAt(state);
        if (bay == null)
            return;
        if (state.getValue(TieredBay.OVERLOADED) != overloadedAt(level, pos, bay)
                || withJoins(level, pos, state) != state)
            level.scheduleTick(pos, state.getBlock(), 1);
    }

    /**
     * Writes {@link TieredBay#OVERLOADED}, {@link TieredBay#FILL} and the two join flags if any of them is wrong, in
     * <b>one</b> block state write, and nothing at all when they already agree — which is what makes a wall of a
     * thousand bays cost nothing while a player builds beside it, and what makes the column walk below terminate.
     * <p>
     * This is the scheduled repair of {@link #onPlace} and of a bay's {@code onLoad}, the answer to
     * {@link #neighborChanged}, and what {@link #contentsChanged} calls; a bay is never randomly ticked. It is the one
     * place all three derived flags are brought in line with the world after a bay arrived by a route that could not
     * compute them — a command, a structure, a schematic, or a save whose configured capacity has changed under it
     * since.
     * <p>
     * A family that draws its level itself ({@link TieredBay#publishesFillLevel()}) has <b>no</b> fill level here:
     * the property is neither read nor written for it, and a change of its contents therefore reaches this method with
     * nothing to do. That is why a fluid bay's contents do not call it at all.
     * <p>
     * <b>The update flags differ by what changed, and that is not a micro-optimisation.</b> A changed
     * {@link TieredBay#OVERLOADED} needs {@code UPDATE_ALL}, because that write <i>is</i> the neighbour update the bay
     * below reads — it is how the flag travels down a column, one block-state read per block. A changed
     * {@link TieredBay#FILL} must tell <b>nothing but the client</b>: it happens on every fourth item that enters or
     * leaves a bay, and a wall of bays beside which every observer, comparator and piston fires on goods moving is
     * noise nobody asked for. {@code UPDATE_CLIENTS} still writes the chunk, marks it unsaved and sends the state to
     * every client, which is the whole job of a fill level. A changed join takes {@code UPDATE_ALL} with the overload
     * flag, because the bay beside it has to be told that their shared upright moved — this is the repair path for a
     * bay a command or a wrench left out of step, so it is as rare as the overload write and may be as loud.
     * <p>
     * Dropping {@code UPDATE_NEIGHBORS} alone does <b>not</b> buy that silence, and it is worth saying exactly why:
     * comparators and pistons hook {@code neighborChanged}, which that flag gates, but an <b>observer</b> hooks
     * {@code updateShape}, and {@code Level.markAndNotifyBlock} runs {@code updateNeighbourShapes} plus both indirect
     * passes for any write whose flags lack {@code UPDATE_KNOWN_SHAPE} — handing each of the six neighbours the
     * direction pointing back at the bay, which is precisely what {@code ObserverBlock.updateShape} schedules its
     * pulse on. So the fill write carries {@code UPDATE_KNOWN_SHAPE} as well, which is safe here because a bay
     * overrides no {@code getShape} and {@link TieredBay#FILL} changes only the model: no neighbour's collision or
     * support shape depends on it. The {@link TieredBay#OVERLOADED} write keeps the full {@code UPDATE_ALL}, shape
     * pass included.
     */
    public static void publishState(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        TieredBay bay = bayAt(state);
        if (bay == null)
            return; // changed into something that is not a bay while the neighbour update was queued
        boolean overloaded = overloadedAt(level, pos, bay);
        boolean publishesFill = bay.publishesFillLevel();
        int fill = publishesFill ? bay.fillStepAt(level, pos, state) : 0;
        BlockState joined = withJoins(level, pos, state);
        boolean columnChanged = state.getValue(TieredBay.OVERLOADED) != overloaded;
        boolean joinChanged = joined != state;
        boolean fillChanged = publishesFill && state.getValue(TieredBay.FILL) != fill;
        if (!columnChanged && !joinChanged && !fillChanged)
            return;
        BlockState next = joined.setValue(TieredBay.OVERLOADED, overloaded);
        if (fillChanged)
            next = next.setValue(TieredBay.FILL, fill);
        level.setBlock(pos, next, columnChanged || joinChanged ? Block.UPDATE_ALL
                : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /**
     * The contents of the bay at {@code pos} changed, so its {@link TieredBay#FILL} may have to follow. Server only,
     * and a no-op for anything that is not a bay — a block entity whose block has already been replaced must not write
     * a block state back. Only a family that publishes a fill level calls it
     * ({@link TieredBay#publishesFillLevel()}); for one that draws its own there would be nothing to write.
     */
    public static void contentsChanged(Level level, BlockPos pos) {
        if (!level.isClientSide)
            publishState(level, pos);
    }
}
