package dev.wareworks.dev;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Counts every millibucket of a scene by exact fluid identity ({@link FluidKey}: fluid and data components), for the
 * joint conservation invariant of {@code docs/warehouse-system.md} §8. The dev-harness counterpart of
 * {@code gametest.FluidCensus}, which needs a {@code GameTestHelper} (M30, issue #21). Server thread only.
 *
 * <h2>What "conserving fluid" means</h2>
 * A fluid is always in exactly one of two places: a <b>block's fluid handler</b>, or a <b>container item</b> wherever
 * that item happens to be. The container is a <b>carrier</b>, exactly as a Create package and a pallet are carriers of
 * items, and not a third place.
 * <p>
 * <b>The item half and the fluid half do not conserve separately.</b> Emptying a bucket at a bay moves 1 000 mB out of
 * a container into a tank and leaves an empty bucket where a filled one was, so {@link SceneItemCensus} alone sees an
 * item change identity out of nowhere. {@link #assertConserved} is the assertion that states the invariant, and
 * {@link SceneItemCensus#exchanged} is the one move allowed to change the item half while this half stands still.
 *
 * <h2>How each half is read</h2>
 * Block handlers are read through {@code getTanks()} / {@code getFluidInTank(tank)} and <b>never</b> through a drain:
 * Create's {@code SmartFluidTankBehaviour.InternalFluidHandler} gates both {@code drain} overloads on
 * {@code extractionAllowed} and leaves {@code getFluidInTank} open, and {@code FluidUtil.getFluidContained} is a
 * simulated drain — so a drain-based read would report 0 for a tank that refuses extraction, which is exactly a fluid
 * bay with {@code storage.fluidBayPipeExtraction} turned off. Every stack is copied the moment it is read, because
 * {@code FluidTank.getFluidInTank} hands out its live field against its own interface's instruction.
 * <p>
 * Handlers are counted once per <b>handler instance</b>, not once per position: every block of a multiblock Create
 * Fluid Tank answers the same {@code IFluidHandler}, so a 1x2 tank holding 3 000 mB would otherwise be counted as
 * 6 000. That is the double-chest problem of {@link SceneItemCensus} in fluid form, and the semantics match it — a
 * tank straddling the box is counted once, in full.
 * <p>
 * Container items are <b>not swept</b>: they are read off {@link SceneItemCensus#take}'s own result, because the fluid
 * in a container is a pure function of its {@link ItemKey}. Fluid is therefore counted at exactly the places the item
 * census already reaches — inventories, station buffers, handling heads, arm claws, item entities, package entities,
 * pallets — by construction rather than by a second carrier list kept in step. M26 and M28 each shipped a carrier a
 * census could not see, and each time every conservation check reported PASS while items vanished into it; a fluid
 * census written as its own sweep would be the third instance waiting to happen.
 * <p>
 * The reading of a container is {@link FluidContainers}', i.e. the same answer the bay and the store gate use, so this
 * census can never be narrower than the thing it watches.
 *
 * <h2>Chunks, and the deliberate exclusions</h2>
 * The box is inflated around an aisle and normally spans several chunks, so {@link #take} refuses to count while any of
 * them is missing rather than reading an unloaded tank as empty — the rule and the probe are
 * {@link SceneItemCensus#isFullyLoaded}'s, shared rather than copied.
 * <p>
 * Not counted, on purpose: fluid lying in the world as a source block (terrain, and nothing in this mod places or
 * consumes one — so a box must not contain an open Create pipe end, whose own source placement would read as a loss
 * here) and entity fluid handlers (nothing registers one; a fluid tote on a pallet would be the first, and would need
 * a branch here and in {@code gametest.FluidCensus} before it could be trusted). A block that answers a handler on a
 * face but none for a {@code null} query is not silently skipped either: it fails the run.
 */
final class SceneFluidCensus {
    private SceneFluidCensus() {
    }

    /**
     * Positive millibuckets per fluid key inside {@code box}.
     *
     * @throws VisualTestException when a position of the box is not loaded ({@link SceneItemCensus#isFullyLoaded})
     */
    static Map<FluidKey, Long> take(ServerLevel level, AABB box) {
        return take(level, box, SceneItemCensus.take(level, box));
    }

    /**
     * Positive millibuckets per fluid key inside {@code box}, with the item census already in hand — the form
     * {@link #assertConserved} uses, so one assertion does not walk the box three times.
     *
     * @param items the result of {@link SceneItemCensus#take} for the same box
     * @throws VisualTestException when a position of the box is not loaded
     */
    static Map<FluidKey, Long> take(ServerLevel level, AABB box, Map<ItemKey, Long> items) {
        Map<FluidKey, Long> amounts = new HashMap<>();
        BoundingBox bounds = new BoundingBox(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.ceil(box.maxX) - 1, Mth.ceil(box.maxY) - 1, Mth.ceil(box.maxZ) - 1);
        Set<IFluidHandler> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos cursor : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(),
                bounds.maxY(), bounds.maxZ())) {
            BlockPos pos = cursor.immutable();
            if (!level.isLoaded(pos))
                throw new VisualTestException("the census box " + box + " reaches the unloaded position " + pos
                        + "; a census must wait until the whole box is loaded");
            // A cauldron has no block entity, so this sweep cannot take the item census's "no block entity, skip"
            // shortcut: it has to ask every position.
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            if (handler == null) {
                requireCompleteNullView(level, pos);
                continue;
            }
            if (counted.add(handler))
                addHandler(amounts, handler);
        }
        addContainers(amounts, items);
        return amounts;
    }

    /** Counts every tank of {@code handler}, each stack copied the moment it is read. */
    private static void addHandler(Map<FluidKey, Long> amounts, IFluidHandler handler) {
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack inTank = handler.getFluidInTank(tank).copy();
            if (!inTank.isEmpty())
                put(amounts, FluidKey.of(inTank), inTank.getAmount());
        }
    }

    /** Counts the fluid inside every container item of an item census, {@code amount} items of a key at a time. */
    private static void addContainers(Map<FluidKey, Long> amounts, Map<ItemKey, Long> items) {
        items.forEach((key, count) -> FluidContainers.contents(key)
                .ifPresent(contents -> put(amounts, contents.fluid(), contents.millibuckets() * count)));
    }

    /**
     * Fails the run when the block at {@code pos} hides fluid behind a face: it answered no handler for a {@code null}
     * query, so this census cannot see it, and a silent 0 would let the whole robustness run pass over a carrier it
     * never read.
     * <p>
     * Only non-air positions are probed, and only after the {@code null} query came back empty. Counting the sided
     * views instead would be worse: a cauldron's registration builds a fresh wrapper per query, so identity could not
     * deduplicate the seven answers and a full cauldron would be counted seven times.
     *
     * @throws VisualTestException when a face answers a handler the {@code null} query does not
     */
    private static void requireCompleteNullView(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).isAir())
            return;
        for (Direction side : Direction.values())
            if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null)
                throw new VisualTestException("the " + level.getBlockState(pos).getBlock() + " at " + pos
                        + " answers a fluid handler on " + side + " but none for a null query, so this census cannot"
                        + " count its fluid: give the block a null view, or teach the census its own API");
    }

    /**
     * Fails the run unless the fluid census of {@code box} equals {@code expected} exactly, and logs one PASS or FAIL
     * line naming {@code step}.
     *
     * @throws VisualTestException when fluid was lost, duplicated or changed identity
     */
    static void assertEquals(ServerLevel level, AABB box, Map<FluidKey, Long> expected, String step) {
        Map<FluidKey, Long> actual = take(level, box);
        if (actual.equals(expected)) {
            VisualTestHarness.LOGGER.info(VisualTestHarness.PREFIX + "robustness PASS: {} (fluid {} mB)", step,
                    describe(actual));
            return;
        }
        String reason = step + ": expected " + describe(expected) + " but found " + describe(actual);
        VisualTestHarness.LOGGER.error(VisualTestHarness.PREFIX + "robustness FAIL: {}", reason);
        throw new VisualTestException("fluid conservation violated, " + reason + ", in millibuckets");
    }

    /**
     * Fails the run unless <b>both</b> censuses of {@code box} equal their expectation — the only assertion that
     * states the invariant, because the two halves do not conserve separately. One failure message carries both, so a
     * run that lost a bucket and a run that lost its contents are told apart at a glance.
     *
     * @throws VisualTestException when an item or a millibucket was lost, duplicated or changed identity
     */
    static void assertConserved(ServerLevel level, AABB box, Map<ItemKey, Long> expectedItems,
            Map<FluidKey, Long> expectedFluid, String step) {
        Map<ItemKey, Long> actualItems = SceneItemCensus.take(level, box);
        Map<FluidKey, Long> actualFluid = take(level, box, actualItems);
        if (actualItems.equals(expectedItems) && actualFluid.equals(expectedFluid)) {
            VisualTestHarness.LOGGER.info(VisualTestHarness.PREFIX + "robustness PASS: {} (items {}, fluid {} mB)",
                    step, SceneItemCensus.describe(actualItems), describe(actualFluid));
            return;
        }
        String reason = step + ": items expected " + SceneItemCensus.describe(expectedItems) + " but found "
                + SceneItemCensus.describe(actualItems) + "; fluid expected " + describe(expectedFluid) + " but found "
                + describe(actualFluid);
        VisualTestHarness.LOGGER.error(VisualTestHarness.PREFIX + "robustness FAIL: {}", reason);
        throw new VisualTestException("conservation violated, " + reason + ", fluid in millibuckets");
    }

    /** A stable, readable rendering of a fluid census, sorted by fluid key. */
    static String describe(Map<FluidKey, Long> amounts) {
        Map<String, Long> sorted = new TreeMap<>();
        amounts.forEach((key, millibuckets) -> sorted.put(key.toString(), millibuckets));
        return sorted.toString();
    }

    /** A copy of {@code amounts} with {@code delta} millibuckets added to {@code key} (zero amounts are dropped). */
    static Map<FluidKey, Long> plus(Map<FluidKey, Long> amounts, FluidKey key, long delta) {
        Map<FluidKey, Long> result = new HashMap<>(amounts);
        put(result, key, delta);
        return result;
    }

    private static void put(Map<FluidKey, Long> amounts, FluidKey key, long millibuckets) {
        long value = amounts.getOrDefault(key, 0L) + millibuckets;
        if (value == 0)
            amounts.remove(key);
        else
            amounts.put(key, value);
    }
}
