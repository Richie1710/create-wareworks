package dev.wareworks.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A rack bay's contents (M28, issue #20): one item type and a count, the invariant that ties the two together, and the
 * promise that no amount, no capacity and no configuration change can make a bay lose or invent an item.
 * <p>
 * The keys are plain strings: this layer is generic over the item key and the arithmetic is the whole subject.
 */
class BayContentsTest {
    private static final String COBBLESTONE = "cobblestone";
    private static final String DIRT = "dirt";
    /** A wooden bay of cobblestone: 64 stacks x 64 ({@link BayTier#WOOD}). */
    private static final long WOOD_COBBLESTONE = 64L * 64L;
    /** How far cobblestone stacks, i.e. what one item-handler call may return. */
    private static final int PER_CALL = 64;

    private final BayContents<String> bay = new BayContents<>();

    // --- the invariant ---------------------------------------------------------------------------------------------

    @Test
    void aFreshBayIsEmptyAndHoldsNoType() {
        assertTrue(bay.isEmpty());
        assertNull(bay.stored());
        assertEquals(0, bay.count());
        assertFalse(bay.holds(COBBLESTONE));
    }

    /** The stored key <b>is</b> the memory of the type: it exists exactly while something is stored. */
    @Test
    void aTypeIsStoredExactlyWhileSomethingIsInside() {
        bay.insert(COBBLESTONE, 1, WOOD_COBBLESTONE, false);
        assertFalse(bay.isEmpty());
        assertEquals(COBBLESTONE, bay.stored());
        assertEquals(1, bay.count());
        bay.extract(1, PER_CALL, false);
        assertTrue(bay.isEmpty());
        assertNull(bay.stored());
        assertEquals(0, bay.count());
    }

    /**
     * The point of the whole block: an unfiltered bay takes the first type that lands in it and forgets it again once it
     * is empty, so a player can put up a wall and let it fill (issue #20, "How a bay learns its type").
     */
    @Test
    void anEmptiedBayTakesTheNextTypeThatComes() {
        assertEquals(10, bay.insert(COBBLESTONE, 10, WOOD_COBBLESTONE, false));
        assertEquals(0, bay.insert(DIRT, 10, WOOD_COBBLESTONE, false));
        assertEquals(10, bay.extract(10, PER_CALL, false));
        assertEquals(10, bay.insert(DIRT, 10, WOOD_COBBLESTONE, false));
        assertEquals(DIRT, bay.stored());
    }

    /** A bay that is only <b>partly</b> drained still holds its type, so the second item type stays out. */
    @Test
    void aPartlyDrainedBayKeepsItsType() {
        bay.insert(COBBLESTONE, 100, WOOD_COBBLESTONE, false);
        assertEquals(64, bay.extract(100, PER_CALL, false));
        assertEquals(36, bay.count());
        assertEquals(0, bay.insert(DIRT, 1, WOOD_COBBLESTONE, false));
    }

    // --- one item type ---------------------------------------------------------------------------------------------

    @Test
    void anOccupiedBayRefusesEveryOtherType() {
        bay.insert(COBBLESTONE, 64, WOOD_COBBLESTONE, false);
        assertEquals(0, bay.insert(DIRT, 64, WOOD_COBBLESTONE, false));
        assertEquals(0, bay.roomFor(DIRT, WOOD_COBBLESTONE));
        assertEquals(64, bay.count());
        assertEquals(COBBLESTONE, bay.stored());
    }

    @Test
    void anEmptyBayHasRoomForAnyType() {
        assertEquals((int) WOOD_COBBLESTONE, bay.roomFor(COBBLESTONE, WOOD_COBBLESTONE));
        assertEquals((int) WOOD_COBBLESTONE, bay.roomFor(DIRT, WOOD_COBBLESTONE));
    }

    @Test
    void extractingAnotherTypeTakesNothing() {
        bay.insert(COBBLESTONE, 64, WOOD_COBBLESTONE, false);
        assertEquals(0, bay.extract(DIRT, 64, PER_CALL, false));
        assertEquals(64, bay.count());
        assertEquals(64, bay.extract(COBBLESTONE, 64, PER_CALL, false));
    }

    // --- counts, not slots -----------------------------------------------------------------------------------------

    /** 65 536 items in one place, which is the whole reason this block exists; no stack size bounds what is stored. */
    @Test
    void aBayHoldsFarMoreThanAStack() {
        long brassCobblestone = 1024L * 64L;
        assertEquals(65_536, bay.insert(COBBLESTONE, 65_536, brassCobblestone, false));
        assertEquals(65_536, bay.count());
        assertEquals(0, bay.roomFor(COBBLESTONE, brassCobblestone));
    }

    @Test
    void anInsertNeverExceedsTheCapacity() {
        assertEquals((int) WOOD_COBBLESTONE, bay.insert(COBBLESTONE, 10_000, WOOD_COBBLESTONE, false));
        assertEquals((int) WOOD_COBBLESTONE, bay.count());
        assertEquals(0, bay.insert(COBBLESTONE, 1, WOOD_COBBLESTONE, false));
    }

    @Test
    void aSimulatedCallChangesNothingAndAnswersWhatARealOneWould() {
        assertEquals(10, bay.insert(COBBLESTONE, 10, WOOD_COBBLESTONE, true));
        assertTrue(bay.isEmpty());
        bay.insert(COBBLESTONE, 10, WOOD_COBBLESTONE, false);
        assertEquals(10, bay.extract(64, PER_CALL, true));
        assertEquals(10, bay.count());
        assertEquals(10, bay.extract(64, PER_CALL, false));
    }

    /**
     * An item handler must never return a stack larger than the item's own maximum, so a bay hands out one stack per
     * call however much it holds — the same per-call cap {@code ItemStackHandler.extractItem} applies, and what the
     * crane's grabber and a vanilla hopper both expect.
     */
    @Test
    void oneCallTakesAtMostOneStack() {
        bay.insert(COBBLESTONE, 1_000, WOOD_COBBLESTONE, false);
        assertEquals(64, bay.extract(1_000, PER_CALL, false));
        assertEquals(936, bay.count());
        assertEquals(16, bay.extract(16, PER_CALL, false));
        assertEquals(920, bay.count());
    }

    /** A per-call cap of 0 or less would silently make a bay unemptiable, so it counts as one item. */
    @Test
    void aNonsensicalPerCallCapStillTakesSomething() {
        bay.insert(COBBLESTONE, 10, WOOD_COBBLESTONE, false);
        assertEquals(1, bay.extract(10, 0, false));
        assertEquals(1, bay.extract(10, -5, false));
        assertEquals(8, bay.count());
    }

    @Test
    void anEmptyBayGivesNothingOut() {
        assertEquals(0, bay.extract(64, PER_CALL, false));
        assertEquals(0, bay.extract(COBBLESTONE, 64, PER_CALL, false));
    }

    @Test
    void anAmountOfZeroOrLessMovesNothing() {
        assertEquals(0, bay.insert(COBBLESTONE, 0, WOOD_COBBLESTONE, false));
        assertEquals(0, bay.insert(COBBLESTONE, -64, WOOD_COBBLESTONE, false));
        assertTrue(bay.isEmpty());
        bay.insert(COBBLESTONE, 10, WOOD_COBBLESTONE, false);
        assertEquals(0, bay.extract(0, PER_CALL, false));
        assertEquals(0, bay.extract(-64, PER_CALL, false));
        assertEquals(10, bay.count());
    }

    // --- a capacity that moves under a standing bay ----------------------------------------------------------------

    /**
     * The config was lowered under a bay that is already fuller than the new number allows (D11): it <b>keeps
     * everything</b> and accepts nothing until it drains. Clamping here would be item loss on a world load.
     */
    @Test
    void anOverFullBayKeepsEverythingAndAcceptsNothing() {
        bay.restore(COBBLESTONE, 4_096);
        long lowered = 16L * 64L;
        assertEquals(0, bay.roomFor(COBBLESTONE, lowered));
        assertEquals(0, bay.insert(COBBLESTONE, 64, lowered, false));
        assertEquals(4_096, bay.count());
        // It drains normally, and takes items again once it is back inside the new capacity.
        for (int call = 0; call < 48; call++)
            bay.extract(64, PER_CALL, false);
        assertEquals(1_024, bay.count());
        assertEquals(0, bay.insert(COBBLESTONE, 64, lowered, false));
        bay.extract(64, PER_CALL, false);
        assertEquals(64, bay.insert(COBBLESTONE, 64, lowered, false));
    }

    /** A capacity of 0 or less (nothing a configuration can produce) never makes a bay negative. */
    @Test
    void anAbsurdCapacityNeverTakesAnything() {
        assertEquals(0, bay.roomFor(COBBLESTONE, 0));
        assertEquals(0, bay.roomFor(COBBLESTONE, -1_000));
        assertEquals(0, bay.insert(COBBLESTONE, 64, 0, false));
        assertEquals(0, bay.insert(COBBLESTONE, 64, Long.MIN_VALUE, false));
        assertTrue(bay.isEmpty());
    }

    /** The largest capacity any configuration can produce still fits an {@code int} of room. */
    @Test
    void theLargestConfigurableBayStillAnswersInInts() {
        long ceiling = BayTier.BRASS.capacity(BayTier.MAX_STACKS, 99);
        assertEquals(BayTier.MAX_CAPACITY_ITEMS, ceiling);
        assertEquals(BayTier.MAX_CAPACITY_ITEMS, bay.roomFor(COBBLESTONE, ceiling));
        assertEquals(BayTier.MAX_CAPACITY_ITEMS, bay.insert(COBBLESTONE, Integer.MAX_VALUE, ceiling, false));
        assertEquals(BayTier.MAX_CAPACITY_ITEMS, bay.count());
    }

    /** A capacity beyond the {@code int} range cannot overflow the room, whatever a caller passes. */
    @Test
    void aCapacityBeyondTheIntRangeIsClampedRatherThanWrapped() {
        assertEquals(Integer.MAX_VALUE, bay.roomFor(COBBLESTONE, Long.MAX_VALUE));
        assertEquals(1_000, bay.insert(COBBLESTONE, 1_000, Long.MAX_VALUE, false));
        assertEquals(1_000, bay.count());
    }

    // --- restore and clear -----------------------------------------------------------------------------------------

    @Test
    void restoringSetsTheContentsOutright() {
        bay.restore(COBBLESTONE, 65_536);
        assertEquals(COBBLESTONE, bay.stored());
        assertEquals(65_536, bay.count());
    }

    /** A load whose count says nothing is stored must not leave a type behind, or the invariant breaks. */
    @Test
    void restoringNothingEmptiesTheBay() {
        bay.insert(COBBLESTONE, 64, WOOD_COBBLESTONE, false);
        bay.restore(COBBLESTONE, 0);
        assertTrue(bay.isEmpty());
        assertNull(bay.stored());
        bay.insert(COBBLESTONE, 64, WOOD_COBBLESTONE, false);
        bay.restore(COBBLESTONE, -1);
        assertTrue(bay.isEmpty());
        bay.insert(COBBLESTONE, 64, WOOD_COBBLESTONE, false);
        bay.restore(null, 64);
        assertTrue(bay.isEmpty());
        assertEquals(0, bay.count());
    }

    @Test
    void clearingEmptiesTheBayWithoutMovingAnything() {
        bay.insert(COBBLESTONE, 4_096, WOOD_COBBLESTONE, false);
        bay.clear();
        assertTrue(bay.isEmpty());
        assertEquals(0, bay.count());
        assertEquals(64, bay.insert(DIRT, 64, WOOD_COBBLESTONE, false));
    }

    // --- callers that pass nothing ---------------------------------------------------------------------------------

    @Test
    void aMissingKeyIsAProgrammingErrorAndNotAnEmptyBay() {
        assertThrows(NullPointerException.class, () -> bay.insert(null, 1, WOOD_COBBLESTONE, false));
        assertThrows(NullPointerException.class, () -> bay.roomFor(null, WOOD_COBBLESTONE));
        assertThrows(NullPointerException.class, () -> bay.extract(null, 1, PER_CALL, false));
    }
}
