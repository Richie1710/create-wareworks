package dev.wareworks.content.storage;

import dev.wareworks.core.storage.BayFamily;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.core.storage.FluidBayTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A <b>bay</b>: a storage location that <b>is</b> the block, built in tiers, stacked into columns and joined into
 * walls — what a {@link RackBayBlock} and a fluid bay have in common (M30 step 3, issue #21, ADR-044, ADR-050).
 * <p>
 * It is implemented by the <b>block</b>, not by its block entity, because everything it answers is about geometry: the
 * three derived flags below are block state, the column rule and the joining are read off neighbouring block states,
 * and all of it has to work for a bay whose chunk holds no block entity yet. {@link BayColumn} is the code that uses
 * it; this interface is only the two questions that code cannot answer for itself, plus the block state vocabulary
 * both families share.
 *
 * <h2>Carrying is within a family, joining is across them</h2>
 * The column rule compares {@link #bayFamily()} <b>before</b> any strength, so a bay of the other family ends a column
 * exactly as air does ({@link BayFamily}, and {@link BayColumn#accepts}). Joining asks no such question: it asks for
 * the same <b>facing</b> and deliberately not for the same tier or the same family, so a fluid bay shares an upright
 * with a rack bay and the seam post is half rack and half tank, which is what it actually is.
 *
 * <h2>What a block must bring with it</h2>
 * A {@code TieredBay} block has to carry {@link HorizontalDirectionalBlock#FACING} pointing <b>into the rack depth</b>,
 * away from the aisle, plus {@link #OVERLOADED}, {@link #FILL}, {@link #LEFT} and {@link #RIGHT} in its state
 * definition: {@link BayColumn} reads all five off a neighbour's state without knowing which bay it is looking at. The
 * facing is not decoration — {@code LocationKind.STORAGE} makes {@code facing() == sideDirection(side)} the alignment
 * rule, so sharing it is what lets aisle discovery, membership, addressing and the crane's reach accept any bay with
 * no change at all.
 */
public interface TieredBay {
    /**
     * Visible fill steps above "empty", so a bay has {@code FILL_LEVELS + 1} looks. Four reads as a rack and eight
     * would read as a gauge; a bay is a rack ({@link #FILL}).
     */
    int FILL_LEVELS = 4;

    /**
     * A standing bay that carries something stronger above it in its column (ADR-044). Derived, never set by a player,
     * and deliberately invisible in the model: it stops this bay being offered store jobs and prints a gold goggle
     * line, and that is all it does.
     * <p>
     * It is maintained in O(1) as the transitive closure of "the block directly above is a stronger bay of the same
     * family" — see {@link BayColumn#overloadedAt}, which has the whole of that argument.
     */
    BooleanProperty OVERLOADED = BooleanProperty.create("overloaded");

    /**
     * Whether another bay of the same facing stands on this one's <b>left</b> as the aisle sees it, i.e. on
     * {@code FACING.getCounterClockWise()} — derived, cosmetic, and the whole of M29's joining (issue #20, ADR-050).
     * <p>
     * A bay alone in the world is a rack frame: two uprights, one at each end, carrying the load beam its pallet
     * stands on. Two bays side by side are <b>one</b> rack, and a rack has one upright on the seam and not two, so
     * each of the pair draws half of it ({@code models/block/rack_bay_<tier>/upright_half.json} against the full
     * {@code upright.json}). That is the difference between a wall that grows into one structure and the row of
     * framed boxes the issue complained about: a 3 x 3 wall drew nine complete frames, so every seam was a doubled
     * 6 px post and every bay read as a crate with a window.
     * <p>
     * <b>The flag is relative to the facing</b> rather than to the world, unlike the rail's four, because the model is
     * turned onto the facing by the same blockstate that reads it: left stays left through every rotation, a structure
     * rotated as a whole keeps its joins, and the part count stays at one condition per side instead of four. It asks
     * only for the <b>same facing</b> and not for the same tier or the same family — a wall is a wall, and the one
     * place a mixed wall shows is a seam post that is half wood and half brass, or half rack and half tank, which is
     * what it actually is. Two racks back to back face opposite ways and therefore never join, which is right: they
     * are two racks.
     * <p>
     * Like {@link #FILL} it notifies no controller and is saved nowhere: it rides the ordinary chunk path, is set at
     * placement, kept in step by {@code updateShape} and repaired by the scheduled tick for a bay that arrived by a
     * command, a structure or a wrench.
     */
    BooleanProperty LEFT = BooleanProperty.create("left");

    /** The same on the right, i.e. on {@code FACING.getClockWise()} — see {@link #LEFT}. */
    BooleanProperty RIGHT = BooleanProperty.create("right");

    /**
     * How full this bay looks, {@code 0} (empty) to {@link #FILL_LEVELS} (full) — the one thing about a bay a player
     * reads <b>without</b> goggles, by walking past a rack wall (M28 step 9, ADR-047).
     * <p>
     * It is a <b>block state</b> property and not renderer state, and that is the whole of the decision for an item
     * bay. A block entity renderer puts every one of its blocks into its chunk section's per-frame render list at the
     * vanilla 64-block default, which is why the warehouse interface had to cut its own view distance to ten blocks
     * ({@code client.render.WarehouseInterfaceRenderer}); a rack wall is hundreds of blocks, so the same answer there
     * would either cost that every frame or make the fill level invisible from across the room. Block state geometry
     * is baked into the chunk mesh and is free at any distance.
     * <p>
     * It is <b>derived</b>, never set by a player, and it is the only state of a bay that notifies nothing: a fill
     * level is neither a membership change nor a store-settings change, so no controller cares.
     * <p>
     * <b>Not every family declares it.</b> "Baked into the chunk mesh and free at any distance" is true of cartons on
     * a pallet and impossible for a fluid, whose look is its own still sprite out of an open set, so a fluid bay draws
     * its level from its block entity instead and declares no {@code fill} at all ({@link #publishesFillLevel()},
     * ADR-053). Everything shared reads this property through that question, never unconditionally.
     */
    IntegerProperty FILL = IntegerProperty.create("fill", 0, FILL_LEVELS);

    /**
     * Whether this family's fill level is a <b>block state</b> ({@link #FILL}) at all: true for a bay whose contents
     * are drawn by baked geometry, false for one whose level a block entity renderer draws.
     * <p>
     * A family that answers {@code false} does not declare {@link #FILL}, is never asked {@link #fillStepAt}, and
     * {@link BayColumn#publishState} neither reads nor writes the property for it — which is the whole point: writing
     * a property nothing draws cost a client block-state change and a chunk-section recompile into an identical mesh
     * on every fill step (M30 review fix). Everything else about a column — the overload flag and the shared uprights
     * — is unaffected, so the two families still go through one block state write.
     */
    default boolean publishesFillLevel() {
        return true;
    }

    /**
     * Which ladder this bay's tier belongs to, and therefore which other bays its strength may be compared with:
     * {@link BayTier#family()} for an item bay, {@link FluidBayTier#family()} for a fluid one. A bay answers through
     * its tier rather than for itself, so the answer is stated once, in the pure layer, beside the ladder it is about.
     */
    BayFamily bayFamily();

    /**
     * Whether a bay of this kind may stand <b>directly under</b> {@code above}: the column rule, asked of one pair.
     * <p>
     * It is only ever asked with a bay of the <b>same family</b> — {@link BayColumn} resolves the neighbour through
     * {@link BayColumn#sameFamilyBay} first, because a different family ends a column rather than refusing it — so an
     * implementation may read the other bay's own tier, and the rule itself stays where it belongs: in the tier enum
     * of the pure layer ({@link BayTier#mayCarry}, {@link FluidBayTier#mayCarry}), which is the one place it is
     * written and the one place JUnit tests it.
     */
    boolean mayCarry(TieredBay above);

    /**
     * How full the bay at {@code pos} should <b>look</b>, {@code 0}..{@link #FILL_LEVELS}, read from whatever holds
     * this family's contents — which is the block entity, and is therefore the one question {@link BayColumn} cannot
     * answer without knowing the family.
     * <p>
     * It must fall back to {@code state.getValue(FILL)} when the contents cannot be read at all, so that
     * {@link BayColumn#publishState} leaves a bay's look alone rather than resetting it to empty: that call runs from
     * neighbour updates and scheduled ticks, where a block entity may legitimately not be there yet.
     * <p>
     * Never called for a family that answers {@code false} to {@link #publishesFillLevel()}.
     *
     * @param state the bay's current block state, which is also where the fallback comes from
     */
    int fillStepAt(BlockGetter level, BlockPos pos, BlockState state);

    /**
     * Lang key of the action bar line a player gets when {@link BayColumn#stateForPlacement} refuses a placement —
     * each family says its own sentence, because "a rack bay may carry nothing stronger above it" is the wrong
     * sentence to show someone holding a tank.
     */
    String columnRefusalMessage();
}
