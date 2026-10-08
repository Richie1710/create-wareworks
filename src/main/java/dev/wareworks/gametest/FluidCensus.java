package dev.wareworks.gametest;

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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Counts every millibucket of a GameTest by exact fluid identity ({@link FluidKey}: fluid and components), for the
 * <b>joint</b> conservation invariant of {@code docs/warehouse-system.md} §8. The fluid sibling of {@link ItemCensus}
 * (M30, issue #21).
 *
 * <h2>What "conserving fluid" means</h2>
 * A fluid is always in exactly one of two places:
 * <ul>
 * <li>a <b>block's fluid handler</b> — a tank, a basin, a cauldron, a fluid bay;</li>
 * <li>a <b>container item</b> — wherever that item happens to be.</li>
 * </ul>
 * The container is a <b>carrier</b>, exactly as a Create package and a pallet are carriers of items, and not a third
 * place: a bucket in a chest, on a handling head, in an arm's claw, inside a package or on a pallet is the same bucket
 * in each of the four places an item can be. So this census has exactly two branches, and the second one is not a
 * sweep at all (below).
 * <p>
 * <b>The two halves do not conserve separately, which is why there is an "assert both" call and not two calls.</b>
 * Emptying a bucket at a bay moves 1 000 mB out of a container into a tank <i>and leaves an empty bucket where a
 * filled one was</i>. Read alone, {@link ItemCensus} sees one item key turn into another out of nowhere, and this
 * census sees a total that has not moved. Only {@link #assertConserved} states something true, and
 * {@link ItemCensus#exchange} is the one move that is allowed to change the item half while this half stands still.
 *
 * <h2>How each half is read, and why not the obvious way</h2>
 * <b>Block handlers</b> are read through {@code getTanks()} / {@code getFluidInTank(tank)}, never through a drain:
 * Create's {@code SmartFluidTankBehaviour.InternalFluidHandler} returns {@code EMPTY} from both {@code drain}
 * overloads unless {@code extractionAllowed}, and leaves {@code getFluidInTank} open — and
 * {@code FluidUtil.getFluidContained} <i>is</i> a simulated drain. A census that read a tank that way would report 0
 * for a tank that refuses extraction, which is precisely the configuration a fluid bay with
 * {@code storage.fluidBayPipeExtraction} turned off has. Every stack is {@link FluidStack#copy copied} the moment it
 * is read, because {@code IFluidHandler} says "SERIOUSLY: DO NOT MODIFY THE RETURNED FLUIDSTACK" and
 * {@code FluidTank.getFluidInTank} hands out its live field.
 * <p>
 * <b>Handlers are counted once per handler instance, not once per position.</b> Every block of a multiblock Create
 * Fluid Tank answers the <i>same</i> {@code IFluidHandler} (a non-controller's registration delegates to the
 * controller's {@code tankInventory}), so a 1x2 tank holding 3 000 mB would otherwise be counted as 6 000. That is the
 * double-chest problem of {@link ItemCensus#isSecondChestHalf}, and identity is the general answer to it — Create's own
 * {@code FluidNetwork} keys its fill accounting on an {@code IdentityHashMap} of handlers for the same reason. The
 * semantics match the double chest exactly: a tank straddling the census boundary is counted once, in full, from
 * whichever of its blocks lies inside.
 * <p>
 * <b>Container items are not swept at all</b> — they are read off {@link ItemCensus#take}'s own result. The fluid in a
 * container is a pure function of its {@link ItemKey}, so mapping the item census over
 * {@link FluidContainers#contents} counts fluid at <b>exactly</b> the places the item census already reaches, by
 * construction rather than by a second list kept in step. That is deliberate and it is the main lesson of M26 and
 * M28: both of those milestones added a carrier (a Create package, a pallet) that a census could not see, and each
 * time every conservation test went on reporting PASS while items vanished into it. A fluid census written as its own
 * sweep would be the third instance waiting to happen; written this way, a carrier the item census learns is a carrier
 * this census knows the same day.
 * <p>
 * The reading of a container is {@link FluidContainers}', i.e. the same answer the bay, the store gate and the
 * terminal use. A census must never be <i>narrower</i> than the thing it watches: if a bay could take fluid out of a
 * container this census reads as empty, a correct transfer would read as fluid appearing from nothing.
 *
 * <h2>Deliberate exclusions</h2>
 * <ul>
 * <li><b>Fluid in the world</b> (a water or lava block) is terrain, not storage, and has no fluid handler. Nothing in
 * this mod places or consumes a source block — breaking a full fluid bay loses its fluid and places nothing — so
 * counting terrain would only make every census depend on a template's hydrology. A census box must therefore not
 * contain an open Create pipe end, which can place and remove source blocks of its own
 * ({@code FluidPropagator.isOpenEnd}, two config keys): Create's own source placement would read as a loss here.</li>
 * <li><b>Entity fluid handlers</b> ({@code Capabilities.FluidHandler.ENTITY}): nothing in NeoForge, Create or this mod
 * registers one. A fluid tote on a pallet would be the first, and it is the branch that would have to be added here
 * and in {@code dev.SceneFluidCensus} before such a tote could be trusted.</li>
 * <li><b>A sided-only block handler</b> is not counted but also not ignored: a block that answers a handler on a face
 * and nothing for a {@code null} query would be invisible here, so the census <b>fails the test</b> instead
 * ({@link #assertNullViewIsComplete}). Every registration in Create answers {@code null} even when it refuses a face
 * (Spout, Item Drain, Hose Pulley), and a fluid bay must do the same.</li>
 * </ul>
 * A census reads every block position of the test bounds once, which is fine for tests.
 */
final class FluidCensus {
    private FluidCensus() {
    }

    /** Positive millibuckets per fluid key inside the test bounds. */
    static Map<FluidKey, Long> take(GameTestHelper helper) {
        return take(helper, ItemCensus.take(helper));
    }

    /**
     * Positive millibuckets per fluid key inside the test bounds, with the item census already in hand — the form
     * {@link #assertConserved} uses, so that one assertion does not walk the box three times.
     *
     * @param items the result of {@link ItemCensus#take} for the same test, whose container items carry fluid
     */
    static Map<FluidKey, Long> take(GameTestHelper helper, Map<ItemKey, Long> items) {
        ServerLevel level = helper.getLevel();
        AABB bounds = helper.getBounds();
        BoundingBox box = new BoundingBox(Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ),
                Mth.ceil(bounds.maxX) - 1, Mth.ceil(bounds.maxY) - 1, Mth.ceil(bounds.maxZ) - 1);
        Map<FluidKey, Long> amounts = new HashMap<>();
        Set<IFluidHandler> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos cursor : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            BlockPos pos = cursor.immutable();
            // A cauldron has no block entity at all, so this sweep cannot take ItemCensus's "no block entity, skip"
            // shortcut: it has to ask every position.
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            if (handler == null) {
                assertNullViewIsComplete(helper, level, pos);
                continue;
            }
            if (counted.add(handler))
                addHandler(amounts, handler);
        }
        addContainers(amounts, items);
        return amounts;
    }

    /**
     * Counts every tank of {@code handler}, each stack copied the moment it is read.
     * <p>
     * Package-private so that {@link FluidCensusGameTests} can hand it a handler whose {@code drain} refuses and whose
     * {@code getFluidInTank} hands out a live instance — the two traps this method exists to avoid, neither of which
     * any block in Create or this mod can reproduce yet.
     */
    static void addHandler(Map<FluidKey, Long> amounts, IFluidHandler handler) {
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack inTank = handler.getFluidInTank(tank).copy();
            if (!inTank.isEmpty())
                put(amounts, FluidKey.of(inTank), inTank.getAmount());
        }
    }

    /** Counts the fluid inside every container item of an item census, {@code amount} items of a key at a time. */
    static void addContainers(Map<FluidKey, Long> amounts, Map<ItemKey, Long> items) {
        items.forEach((key, count) -> FluidContainers.contents(key)
                .ifPresent(contents -> put(amounts, contents.fluid(), contents.millibuckets() * count)));
    }

    /**
     * Fails the test when the block at {@code pos} hides fluid behind a face: it answered no handler for a
     * {@code null} query, so this census cannot see it, and a silent 0 would let a conservation test pass over a
     * carrier it never read.
     * <p>
     * Only non-air positions are probed, and only after the {@code null} query came back empty, so the six extra
     * lookups never happen for a block that is already counted. Counting the sided views instead was the alternative
     * and it is worse: a cauldron's registration builds a <b>fresh</b> {@code CauldronWrapper} per query, so identity
     * could not deduplicate seven answers and a full cauldron would be counted seven times.
     */
    private static void assertNullViewIsComplete(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).isAir())
            return;
        for (Direction side : Direction.values())
            if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null)
                helper.fail("the " + level.getBlockState(pos).getBlock() + " at " + pos
                        + " answers a fluid handler on " + side + " but none for a null query, so this census cannot"
                        + " count its fluid: give the block a null view, or teach the census its own API");
    }

    /** Fails the test unless the fluid census equals {@code expected} exactly (no missing, extra or changed keys). */
    static void assertEquals(GameTestHelper helper, Map<FluidKey, Long> expected, String context) {
        Map<FluidKey, Long> actual = take(helper);
        if (!actual.equals(expected))
            helper.fail("fluid conservation violated (" + context + "): expected " + describe(expected)
                    + " but found " + describe(actual) + ", in millibuckets");
    }

    /**
     * Fails the test unless <b>both</b> censuses equal their expectation — the only assertion that states the
     * invariant, because the item half and the fluid half do not conserve separately (see the class comment).
     * <p>
     * One failure message carries both halves, so that a test which lost a bucket and a test which lost its contents
     * are told apart at a glance instead of by running the other assertion afterwards.
     */
    static void assertConserved(GameTestHelper helper, Map<ItemKey, Long> expectedItems,
            Map<FluidKey, Long> expectedFluid, String context) {
        Map<ItemKey, Long> actualItems = ItemCensus.take(helper);
        Map<FluidKey, Long> actualFluid = take(helper, actualItems);
        if (actualItems.equals(expectedItems) && actualFluid.equals(expectedFluid))
            return;
        helper.fail("conservation violated (" + context + "): items expected " + ItemCensus.describe(expectedItems)
                + " but found " + ItemCensus.describe(actualItems) + "; fluid expected " + describe(expectedFluid)
                + " but found " + describe(actualFluid) + ", in millibuckets");
    }

    /** A mutable expectation map from key/millibucket pairs ({@code FluidKey, Number, FluidKey, Number, ...}). */
    static Map<FluidKey, Long> of(Object... keyAmounts) {
        Map<FluidKey, Long> expected = new HashMap<>();
        for (int i = 0; i + 1 < keyAmounts.length; i += 2)
            put(expected, (FluidKey) keyAmounts[i], ((Number) keyAmounts[i + 1]).longValue());
        return expected;
    }

    /** Adds {@code delta} millibuckets (may be negative) to {@code key} in an expectation map, dropping zero. */
    static void change(Map<FluidKey, Long> expected, FluidKey key, long delta) {
        put(expected, key, delta);
    }

    private static void put(Map<FluidKey, Long> amounts, FluidKey key, long millibuckets) {
        long value = amounts.getOrDefault(key, 0L) + millibuckets;
        if (value == 0)
            amounts.remove(key);
        else
            amounts.put(key, value);
    }

    /** A stable, readable rendering of a fluid census, sorted by fluid key. */
    static String describe(Map<FluidKey, Long> amounts) {
        Map<String, Long> sorted = new TreeMap<>();
        amounts.forEach((key, millibuckets) -> sorted.put(key.toString(), millibuckets));
        return sorted.toString();
    }
}
