package dev.wareworks.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The two fluid bay tiers (M30, issue #21): the ladder the issue settled, the strength order the column rule is built
 * on, the whole-container rule that keeps the stock index from growing one row per millibucket, and the promise that no
 * number a config file, a per-world override or a hand-edited TOML can carry ever turns a bay into a hole or makes its
 * millibucket count leave the {@code int} range.
 */
class FluidBayTierTest {
    /** What one bucket holds, i.e. the only container every vanilla player owns. */
    private static final int BUCKET = FluidBayTier.MILLIBUCKETS_PER_BUCKET;
    /** What one bottle holds in Create. It has no fluid capability, but it is why the unit is millibuckets. */
    private static final int BOTTLE = 250;

    // --- the ladder ------------------------------------------------------------------------------------------------

    /** The capacity table of issue #21: 64 buckets in copper, 256 in brass, fourfold between the two. */
    @Test
    void theShippedLadderRisesFourfold() {
        assertEquals(64, FluidBayTier.COPPER.defaultBuckets());
        assertEquals(256, FluidBayTier.BRASS.defaultBuckets());
        assertEquals(4 * FluidBayTier.COPPER.defaultBuckets(), FluidBayTier.BRASS.defaultBuckets());
    }

    /**
     * The two numbers the issue measures the block against: a Create Fluid Tank holds 8 buckets per block, so copper is
     * exactly eight tank blocks and brass is past a 3 x 3 x 3 tower, which is 27 x 8 = 216.
     */
    @Test
    void theLadderBeatsTheBlockItIsMeasuredAgainst() {
        int createTankBlock = 8;
        assertEquals(8 * createTankBlock, FluidBayTier.COPPER.defaultBuckets());
        assertTrue(FluidBayTier.BRASS.defaultBuckets() > 27 * createTankBlock,
                "brass must beat a 3x3x3 tank tower (216 buckets)");
        assertEquals(40, FluidBayTier.BRASS.defaultBuckets() - 27 * createTankBlock);
    }

    /** There are exactly two tiers, and neither of them is wood or andesite. */
    @Test
    void thereIsNoWoodAndNoAndesiteFluidBay() {
        assertEquals(2, FluidBayTier.values().length);
        for (FluidBayTier tier : FluidBayTier.values())
            assertFalse(tier.name().equals("WOOD") || tier.name().equals("ANDESITE"),
                    "fluids are copper in Create: " + tier);
    }

    /** A shipped default the config's own range would reject would be a crash on first load, not a bad number. */
    @Test
    void everyShippedDefaultIsInsideTheConfiguredRange() {
        for (FluidBayTier tier : FluidBayTier.values()) {
            assertTrue(tier.defaultBuckets() >= FluidBayTier.MIN_BUCKETS, tier + " default below the minimum");
            assertTrue(tier.defaultBuckets() <= FluidBayTier.MAX_BUCKETS, tier + " default above the maximum");
            assertEquals(tier.defaultBuckets(), FluidBayTier.clampBuckets(tier.defaultBuckets()),
                    tier + " default clamped");
        }
    }

    // --- the strength order the column rule uses -------------------------------------------------------------------

    /**
     * The declaration order <b>is</b> the strength order, and {@link FluidBayTier#strength()} is the ordinal. A tier
     * inserted in the middle rather than appended would silently reorder the column rule, so the order is pinned here
     * rather than remembered.
     */
    @Test
    void theDeclarationOrderIsTheStrengthOrder() {
        assertEquals(List.of(FluidBayTier.COPPER, FluidBayTier.BRASS), List.of(FluidBayTier.values()));
        assertEquals(0, FluidBayTier.COPPER.strength());
        assertEquals(1, FluidBayTier.BRASS.strength());
        for (FluidBayTier tier : FluidBayTier.values())
            assertEquals(tier.ordinal(), tier.strength(), tier + " strength is its ordinal");
    }

    /** A bay carries nothing stronger above it, and equal tiers carry each other to any height. */
    @Test
    void aBayCarriesNothingStrongerThanItself() {
        assertTrue(FluidBayTier.COPPER.mayCarry(FluidBayTier.COPPER));
        assertTrue(FluidBayTier.BRASS.mayCarry(FluidBayTier.BRASS));
        assertTrue(FluidBayTier.BRASS.mayCarry(FluidBayTier.COPPER));
        assertFalse(FluidBayTier.COPPER.mayCarry(FluidBayTier.BRASS), "no brass tank on top of copper");
    }

    /**
     * The rule is monotone and is therefore the same question from either side: it never happens that a bay may carry
     * another while that other may also carry it, unless the two are equally strong.
     */
    @Test
    void theColumnRuleIsOneQuestionAskedFromBothSides() {
        for (FluidBayTier below : FluidBayTier.values())
            for (FluidBayTier above : FluidBayTier.values()) {
                boolean legal = below.mayCarry(above);
                assertEquals(!above.isStrongerThan(below), legal, below + " under " + above);
                if (below != above)
                    assertFalse(legal && above.mayCarry(below), below + " and " + above + " cannot carry each other");
            }
    }

    // --- millibuckets ----------------------------------------------------------------------------------------------

    /**
     * The unit is millibuckets, because the smallest portion any container in the ecosystem carries is a bottle's
     * 250 mB, which buckets cannot express. {@link FluidBayTier#MILLIBUCKETS_PER_BUCKET} is this layer's mirror of
     * {@code FluidType.BUCKET_VOLUME}; the two are asserted equal by the {@code fluidbayfoundation} GameTest, which is
     * where a Minecraft constant can be read at all.
     */
    @Test
    void theUnitIsMillibuckets() {
        assertEquals(1_000, FluidBayTier.MILLIBUCKETS_PER_BUCKET);
        assertEquals(4, FluidBayTier.MILLIBUCKETS_PER_BUCKET / BOTTLE, "four bottles are one bucket");
    }

    /** The issue's capacity table in the unit the bay really counts in. */
    @Test
    void theCapacityIsTheBucketCountTimesAThousand() {
        assertEquals(64_000L, FluidBayTier.COPPER.capacityMillibuckets(FluidBayTier.COPPER.defaultBuckets()));
        assertEquals(256_000L, FluidBayTier.BRASS.capacityMillibuckets(FluidBayTier.BRASS.defaultBuckets()));
    }

    /** The configured number is the only thing that differs between the tiers, so any tier answers for any number. */
    @Test
    void theCapacityFollowsTheConfiguredNumberAndNotTheTier() {
        for (FluidBayTier tier : FluidBayTier.values())
            assertEquals(128L * BUCKET, tier.capacityMillibuckets(128), tier + " at 128 buckets");
    }

    /**
     * The ceiling exists to keep a bay's millibucket count inside an {@code int}, and this is that claim as an
     * assertion: the largest capacity any configuration can ask for is 3 % of an {@code int}.
     */
    @Test
    void theCeilingKeepsAMillibucketCountInsideAnInt() {
        assertEquals(65_536, FluidBayTier.MAX_BUCKETS);
        assertEquals(65_536_000, FluidBayTier.MAX_CAPACITY_MILLIBUCKETS);
        assertTrue(FluidBayTier.MAX_CAPACITY_MILLIBUCKETS > 0, "no overflow in the constant itself");
        assertTrue(FluidBayTier.MAX_CAPACITY_MILLIBUCKETS < Integer.MAX_VALUE / 32,
                "the ceiling leaves room for the arithmetic around it");
        for (FluidBayTier tier : FluidBayTier.values())
            assertEquals(FluidBayTier.MAX_CAPACITY_MILLIBUCKETS, tier.capacityMillibuckets(Integer.MAX_VALUE),
                    tier + " at the ceiling");
    }

    @Test
    void aBayIsNeverLargerThanTheCeiling() {
        assertEquals(FluidBayTier.MAX_BUCKETS, FluidBayTier.clampBuckets(FluidBayTier.MAX_BUCKETS + 1));
        assertEquals(FluidBayTier.MAX_BUCKETS, FluidBayTier.clampBuckets(1_000_000));
        assertEquals(FluidBayTier.MAX_BUCKETS, FluidBayTier.clampBuckets(Integer.MAX_VALUE));
    }

    @Test
    void anAbsurdBucketCountIsClampedBeforeItIsMultiplied() {
        assertEquals((long) FluidBayTier.MAX_BUCKETS * BUCKET,
                FluidBayTier.BRASS.capacityMillibuckets(Integer.MAX_VALUE));
        assertEquals((long) FluidBayTier.MIN_BUCKETS * BUCKET, FluidBayTier.COPPER.capacityMillibuckets(0));
        assertEquals((long) FluidBayTier.MIN_BUCKETS * BUCKET,
                FluidBayTier.COPPER.capacityMillibuckets(Integer.MIN_VALUE));
    }

    @Test
    void noConfigurationMakesACapacityNegativeOrEmpty() {
        int[] buckets = {Integer.MIN_VALUE, -1, 0, 1, 64, 256, FluidBayTier.MAX_BUCKETS, FluidBayTier.MAX_BUCKETS + 1,
                Integer.MAX_VALUE};
        for (FluidBayTier tier : FluidBayTier.values())
            for (int configured : buckets) {
                long capacity = tier.capacityMillibuckets(configured);
                assertTrue(capacity >= BUCKET, tier + " with " + configured + " buckets holds less than a bucket");
                assertTrue(capacity <= FluidBayTier.MAX_CAPACITY_MILLIBUCKETS,
                        tier + " with " + configured + " buckets left the int range");
            }
    }

    // --- the whole-container rule ----------------------------------------------------------------------------------

    /**
     * The one sentence of issue #21 that the design had to correct: <b>a bay with 999 mB of room gets nothing from a
     * bucket</b>, not 999 mB. A container is drained to empty or refused, because a partially filled container is a
     * different item key per millibucket value — and it is also the vanilla bucket's own rule.
     */
    @Test
    void aBayWithLessThanAWholeContainerOfRoomTakesNothing() {
        assertEquals(0, FluidBayTier.wholeContainers(BUCKET, 999));
        assertEquals(0, FluidBayTier.wholeContainers(BUCKET, 1));
        assertEquals(1, FluidBayTier.wholeContainers(BUCKET, BUCKET));
        assertEquals(1, FluidBayTier.wholeContainers(BUCKET, BUCKET + 999));
        assertEquals(2, FluidBayTier.wholeContainers(BUCKET, 2 * BUCKET));
    }

    /** An empty copper bay takes 64 buckets and an empty brass one 256 — the number of crane trips the issue costs. */
    @Test
    void anEmptyBayTakesExactlyItsBucketCount() {
        assertEquals(64, FluidBayTier.wholeContainers(BUCKET,
                FluidBayTier.COPPER.capacityMillibuckets(FluidBayTier.COPPER.defaultBuckets())));
        assertEquals(256, FluidBayTier.wholeContainers(BUCKET,
                FluidBayTier.BRASS.capacityMillibuckets(FluidBayTier.BRASS.defaultBuckets())));
    }

    /** A smaller container divides the same room more finely, which is the whole reason the unit is millibuckets. */
    @Test
    void aSmallerContainerFitsMoreOften() {
        assertEquals(4, FluidBayTier.wholeContainers(BOTTLE, BUCKET));
        assertEquals(3, FluidBayTier.wholeContainers(BOTTLE, 999));
        assertEquals(1, FluidBayTier.wholeContainers(1, 1));
    }

    /** Nothing here throws and nothing answers a negative count, whatever a broken container or a full bay reports. */
    @Test
    void nothingAboutTheRuleEverThrowsOrGoesNegative() {
        int[] sizes = {Integer.MIN_VALUE, -1, 0, 1, BOTTLE, BUCKET, Integer.MAX_VALUE};
        long[] rooms = {Long.MIN_VALUE, -1, 0, 1, 999, BUCKET, FluidBayTier.MAX_CAPACITY_MILLIBUCKETS, Long.MAX_VALUE};
        for (int size : sizes)
            for (long room : rooms) {
                int fits = FluidBayTier.wholeContainers(size, room);
                assertTrue(fits >= 0, size + " mB containers into " + room + " mB of room");
            }
        assertEquals(0, FluidBayTier.wholeContainers(0, FluidBayTier.MAX_CAPACITY_MILLIBUCKETS));
        assertEquals(0, FluidBayTier.wholeContainers(-BUCKET, FluidBayTier.MAX_CAPACITY_MILLIBUCKETS));
        assertEquals(0, FluidBayTier.wholeContainers(BUCKET, -1));
        assertEquals(Integer.MAX_VALUE, FluidBayTier.wholeContainers(1, Long.MAX_VALUE),
                "an absurd room is clamped into the int range rather than wrapping");
    }
}
