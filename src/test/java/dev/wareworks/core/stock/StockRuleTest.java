package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * One line of a warehouse stock keeper (M15, issue #3): the three numbers, what each of them governs, and the
 * promise that no value a player, a schematic or a crafted save file can produce ever throws or leaves the rule in a
 * state the rest of the code has to guard against.
 */
class StockRuleTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";

    private static StockRule<String> rule(long minimum, long maximum, long reserve) {
        return new StockRule<>(IRON, minimum, maximum, reserve);
    }

    // --- construction, clamping and the "off" sentinel -------------------------------------------------------------

    @Test
    void theNumbersAreStoredAsGivenWhenTheyAgree() {
        StockRule<String> rule = rule(64L, 512L, 32L);
        assertEquals(IRON, rule.key());
        assertEquals(64L, rule.minimum());
        assertEquals(512L, rule.maximum());
        assertEquals(32L, rule.reserve());
        assertTrue(rule.hasMinimum());
        assertTrue(rule.hasMaximum());
        assertTrue(rule.hasReserve());
        assertFalse(rule.isEmpty());
    }

    @Test
    void aRuleAlwaysHasAnItem() {
        assertThrows(NullPointerException.class, () -> new StockRule<>(null, 1L, 1L, 1L));
    }

    @Test
    void anItemWithoutNumbersGovernsNothing() {
        StockRule<String> fresh = StockRule.of(IRON);
        assertEquals(StockRule.UNSET, fresh.minimum());
        assertEquals(StockRule.UNSET, fresh.maximum());
        assertEquals(StockRule.UNSET, fresh.reserve());
        assertTrue(fresh.isEmpty());
        assertEquals(StockRuleStatus.NO_LIMITS, fresh.statusFor(StockLevels.stored(10L)));
        assertEquals(Long.MAX_VALUE, fresh.headroom(StockLevels.stored(10L)), "no cap at all");
        assertEquals(0L, fresh.shortfall(0L));
        assertEquals(10L, fresh.availableTo(StockAccess.AUTOMATION, 10L));
    }

    /** Off and zero are the same for the minimum and the reserve, and only read differently on the screen. */
    @Test
    void zeroIsOffForTheMinimumAndTheReserve() {
        StockRule<String> zeroed = rule(0L, StockRule.UNSET, 0L);
        assertFalse(zeroed.hasMinimum());
        assertFalse(zeroed.hasReserve());
        assertTrue(zeroed.isEmpty(), "a rule that neither calls for, caps nor reserves anything");
        assertEquals(0L, zeroed.shortfall(0L));
        assertFalse(zeroed.isBelowMinimum(0L));
        assertEquals(5L, zeroed.availableTo(StockAccess.AUTOMATION, 5L));
    }

    /** For the maximum it is the other way round: 0 is the meaningful setting "accept none of this any more". */
    @Test
    void zeroIsARealMaximum() {
        StockRule<String> acceptNone = rule(StockRule.UNSET, 0L, StockRule.UNSET);
        assertTrue(acceptNone.hasMaximum());
        assertFalse(acceptNone.isEmpty());
        assertEquals(0L, acceptNone.headroom(0L, 0L, 0L), "not even the first item");
        assertTrue(acceptNone.isAtMaximum(StockLevels.NONE));
        assertEquals(StockRuleStatus.AT_MAXIMUM, acceptNone.statusFor(StockLevels.NONE));
        assertEquals(Long.MAX_VALUE, rule(StockRule.UNSET, StockRule.UNSET, StockRule.UNSET).headroom(0L, 0L, 0L));
    }

    @Test
    void valuesOutsideTheRangeAreClampedIntoIt() {
        StockRule<String> low = rule(-7L, -9L, Long.MIN_VALUE);
        assertEquals(StockRule.UNSET, low.minimum());
        assertEquals(StockRule.UNSET, low.maximum());
        assertEquals(StockRule.UNSET, low.reserve());
        StockRule<String> high = rule(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(StockRule.MAX_AMOUNT, high.minimum());
        assertEquals(StockRule.MAX_AMOUNT, high.maximum());
        assertEquals(StockRule.MAX_AMOUNT, high.reserve());
    }

    @Test
    void aMaximumBelowTheMinimumIsRaisedToIt() {
        StockRule<String> raised = rule(64L, 32L, StockRule.UNSET);
        assertEquals(64L, raised.maximum(), "keeping 64 and storing at most 32 cannot both be obeyed");
        assertEquals(64L, rule(64L, 64L, StockRule.UNSET).maximum(), "equal numbers are left alone");
        assertEquals(65L, rule(64L, 65L, StockRule.UNSET).maximum(), "one above the minimum is left alone");
        assertEquals(StockRule.UNSET, rule(64L, StockRule.UNSET, StockRule.UNSET).maximum(), "no maximum stays none");
    }

    @Test
    void aReserveAboveTheMaximumIsLoweredToIt() {
        assertEquals(32L, rule(StockRule.UNSET, 32L, 100L).reserve());
        assertEquals(32L, rule(StockRule.UNSET, 32L, 32L).reserve(), "equal numbers are left alone");
        assertEquals(31L, rule(StockRule.UNSET, 32L, 31L).reserve());
        assertEquals(0L, rule(StockRule.UNSET, 0L, 50L).reserve(), "nothing may be stored, so nothing is reserved");
        assertEquals(500L, rule(StockRule.UNSET, StockRule.UNSET, 500L).reserve(), "no maximum caps no reserve");
    }

    /** The reserve is clamped against the raised maximum, never the one the player typed. */
    @Test
    void bothCrossClampsRunInOrder()  {
        StockRule<String> rule = rule(100L, 32L, 80L);
        assertEquals(100L, rule.maximum());
        assertEquals(80L, rule.reserve(), "80 fits under the raised maximum of 100");
        assertEquals(100L, rule(100L, 32L, 400L).reserve(), "and a reserve above it is lowered to 100");
    }

    // --- what the screen is told about a correction ---------------------------------------------------------------

    @Test
    void numbersThatAgreeAreStoredWithoutAWord() {
        StockRule.Adjusted<String> checked = StockRule.checked(IRON, 64L, 512L, 32L);
        assertEquals(StockRuleAdjustment.NONE, checked.adjustment());
        assertEquals(rule(64L, 512L, 32L), checked.rule());
        assertEquals(StockRuleAdjustment.NONE, StockRule.checked(IRON, -1L, -1L, -1L).adjustment(), "off is in range");
    }

    @Test
    void everyCorrectionIsReportedWithTheMostSpecificReason() {
        assertEquals(StockRuleAdjustment.VALUE_CLAMPED, StockRule.checked(IRON, 0L, StockRule.MAX_AMOUNT + 1, -1L)
                .adjustment());
        assertEquals(StockRuleAdjustment.VALUE_CLAMPED, StockRule.checked(IRON, -5L, -1L, -1L).adjustment(),
                "below the off sentinel is out of range, not off");
        assertEquals(StockRuleAdjustment.MAXIMUM_RAISED_TO_MINIMUM, StockRule.checked(IRON, 64L, 32L, -1L)
                .adjustment());
        assertEquals(StockRuleAdjustment.RESERVE_CLAMPED_TO_MAXIMUM, StockRule.checked(IRON, -1L, 32L, 100L)
                .adjustment());
        // Both cross-clamps at once: the raised maximum is the surprising one, because it changes a number the player
        // did not touch on a row where they did touch the reserve.
        assertEquals(StockRuleAdjustment.MAXIMUM_RAISED_TO_MINIMUM, StockRule.checked(IRON, 100L, 32L, 400L)
                .adjustment());
        // A range clamp that then also raises the maximum still reports the cross-clamp.
        assertEquals(StockRuleAdjustment.MAXIMUM_RAISED_TO_MINIMUM,
                StockRule.checked(IRON, StockRule.MAX_AMOUNT + 5L, 10L, -1L).adjustment());
        assertThrows(NullPointerException.class, () -> new StockRule.Adjusted<>(null, StockRuleAdjustment.NONE));
    }

    // --- the maximum: what may be stored ---------------------------------------------------------------------------

    @Test
    void headroomIsWhatIsLeftUnderTheMaximum() {
        StockRule<String> capped = rule(StockRule.UNSET, 2048L, StockRule.UNSET);
        assertEquals(58L, capped.headroom(1990L, 0L, 0L), "1990 stored under a maximum of 2048");
        assertEquals(1L, capped.headroom(2047L, 0L, 0L));
        assertEquals(0L, capped.headroom(2048L, 0L, 0L), "exactly full");
        assertEquals(0L, capped.headroom(2049L, 0L, 0L), "lowering a maximum never moves what is already stored");
        assertEquals(2048L, capped.headroom(0L, 0L, 0L));
    }

    @Test
    void itemsOnTheirWayIntoStorageTakeUpHeadroom() {
        StockRule<String> capped = rule(StockRule.UNSET, 100L, StockRule.UNSET);
        assertEquals(36L, capped.headroom(50L, 14L, 0L));
        assertEquals(0L, capped.headroom(50L, 50L, 0L), "the crane is already carrying the rest in");
        assertEquals(0L, capped.headroom(50L, 500L, 0L));
    }

    /**
     * The allowance that keeps an automatic order from stranding: a pattern makes whole runs, so an order for 32 comes
     * back as 36 and the warehouse has to be able to take back what it sent out for.
     */
    @Test
    void whatTheWarehouseSentOutForFitsBackIn() {
        StockRule<String> capped = rule(100L, 110L, StockRule.UNSET);
        assertEquals(10L, capped.headroom(100L, 0L, 0L));
        assertEquals(74L, capped.headroom(100L, 0L, 64L), "a run of 64 was ordered, so 64 more may come back");
        assertEquals(0L, capped.headroom(110L, 0L, 0L), "and once nothing is expected the cap bites again");
    }

    @Test
    void headroomSaturatesAndNeverGoesNegative() {
        StockRule<String> capped = rule(StockRule.UNSET, StockRule.MAX_AMOUNT, StockRule.UNSET);
        assertEquals(Long.MAX_VALUE, capped.headroom(0L, 0L, Long.MAX_VALUE));
        assertEquals(0L, capped.headroom(Long.MAX_VALUE, Long.MAX_VALUE, 0L));
        assertEquals(StockRule.MAX_AMOUNT, capped.headroom(-5L, -5L, -5L), "negative counters read as nothing");
    }

    @Test
    void anItemWithoutAMaximumIsNeverAtOne() {
        StockRule<String> uncapped = rule(64L, StockRule.UNSET, 10L);
        assertEquals(Long.MAX_VALUE, uncapped.headroom(1_000_000L, 1_000_000L, 0L));
        assertFalse(uncapped.isAtMaximum(new StockLevels(1_000_000L, 0L, 0L, 0L)));
    }

    // --- the minimum: what the warehouse calls for -----------------------------------------------------------------

    @Test
    void theShortfallIsMeasuredAgainstTheWholePipeline() {
        StockRule<String> keeps64 = rule(64L, StockRule.UNSET, StockRule.UNSET);
        assertEquals(64L, keeps64.shortfall(0L));
        assertEquals(1L, keeps64.shortfall(63L));
        assertEquals(0L, keeps64.shortfall(64L), "exactly met");
        assertEquals(0L, keeps64.shortfall(65L));
        assertEquals(24L, keeps64.shortfall(new StockLevels(20L, 10L, 10L, 20L)), "40 in the pipeline");
        assertEquals(64L, keeps64.shortfall(-100L), "a negative counter reads as nothing");
    }

    /** A rule that has just ordered counts that order and cannot order the same thing twice. */
    @Test
    void anOpenOrderSatisfiesTheMinimum() {
        StockRule<String> keeps64 = rule(64L, StockRule.UNSET, StockRule.UNSET);
        StockLevels beforeOrdering = new StockLevels(10L, 0L, 0L, 10L);
        assertTrue(keeps64.isBelowMinimum(beforeOrdering));
        assertEquals(54L, keeps64.shortfall(beforeOrdering));
        StockLevels afterOrdering = new StockLevels(10L, 0L, 64L, 10L);
        assertFalse(keeps64.isBelowMinimum(afterOrdering), "the whole run is on order");
        assertEquals(0L, keeps64.shortfall(afterOrdering));
    }

    @Test
    void aMinimumThatIsOffCallsForNothing() {
        StockRule<String> noMinimum = rule(StockRule.UNSET, 512L, 32L);
        assertFalse(noMinimum.isBelowMinimum(0L));
        assertEquals(0L, noMinimum.shortfall(0L));
        assertFalse(rule(0L, 512L, 32L).isBelowMinimum(0L), "a minimum of zero is off too");
    }

    // --- the reserve: what may go out ------------------------------------------------------------------------------

    @Test
    void automationStopsAtTheReserveAndAPlayerDoesNot() {
        StockRule<String> reserves10 = rule(StockRule.UNSET, StockRule.UNSET, 10L);
        assertEquals(22L, reserves10.availableTo(StockAccess.AUTOMATION, 32L));
        assertEquals(32L, reserves10.availableTo(StockAccess.PLAYER, 32L), "a player may go below the reserve");
        assertEquals(0L, reserves10.availableTo(StockAccess.AUTOMATION, 10L), "exactly the reserve is left");
        assertEquals(1L, reserves10.availableTo(StockAccess.AUTOMATION, 11L));
        assertEquals(0L, reserves10.availableTo(StockAccess.AUTOMATION, 9L), "never negative");
        assertEquals(0L, reserves10.availableTo(StockAccess.PLAYER, -5L));
    }

    @Test
    void withoutAReserveBothTakersSeeTheSameAmount() {
        StockRule<String> capped = rule(64L, 512L, StockRule.UNSET);
        assertEquals(32L, capped.availableTo(StockAccess.AUTOMATION, 32L));
        assertEquals(32L, capped.availableTo(StockAccess.PLAYER, 32L));
        assertEquals(0L, capped.heldBack(32L));
        assertFalse(capped.isAtReserve(0L));
    }

    @Test
    void whatIsHeldBackIsNeverMoreThanWhatIsThere() {
        StockRule<String> reserves10 = rule(StockRule.UNSET, StockRule.UNSET, 10L);
        assertEquals(10L, reserves10.heldBack(32L));
        assertEquals(10L, reserves10.heldBack(10L));
        assertEquals(4L, reserves10.heldBack(4L), "four items cannot hold ten back");
        assertEquals(0L, reserves10.heldBack(0L));
        assertEquals(0L, reserves10.heldBack(-3L));
    }

    @Test
    void aPlayerIsToldExactlyHowFarIntoTheReserveTheyReach() {
        StockRule<String> reserves10 = rule(StockRule.UNSET, StockRule.UNSET, 10L);
        assertEquals(0L, reserves10.fromReserve(32L, 22L), "the request still fits above the reserve");
        assertFalse(reserves10.takesFromReserve(32L, 22L));
        assertEquals(1L, reserves10.fromReserve(32L, 23L));
        assertTrue(reserves10.takesFromReserve(32L, 23L));
        assertEquals(10L, reserves10.fromReserve(32L, 32L), "all of it");
        assertEquals(10L, reserves10.fromReserve(32L, 500L), "what is not there is missing, not reserved");
        assertEquals(5L, reserves10.fromReserve(5L, 5L), "the last five are all reserve");
        assertEquals(0L, reserves10.fromReserve(32L, 0L));
        assertEquals(0L, reserves10.fromReserve(32L, -4L));
        assertEquals(0L, rule(StockRule.UNSET, StockRule.UNSET, StockRule.UNSET).fromReserve(32L, 32L),
                "no reserve, nothing to warn about");
    }

    @Test
    void theReserveBitesOnlyWhileItIsHoldingSomethingBack() {
        StockRule<String> reserves10 = rule(StockRule.UNSET, StockRule.UNSET, 10L);
        assertFalse(reserves10.isAtReserve(11L));
        assertTrue(reserves10.isAtReserve(10L));
        assertTrue(reserves10.isAtReserve(3L), "three items left, all of them reserved");
        assertFalse(reserves10.isAtReserve(0L), "nothing available at all is not the reserve's doing");
    }

    // --- the one status a lamp, a goggle line and a badge all read -------------------------------------------------

    @Test
    void theStatusIsSatisfiedWhileNoNumberBites() {
        StockRule<String> rule = rule(64L, 512L, 32L);
        assertEquals(StockRuleStatus.SATISFIED, rule.statusFor(StockLevels.stored(128L)));
        assertTrue(rule.statusFor(StockLevels.stored(128L)).governs());
        assertFalse(rule.statusFor(StockLevels.stored(128L)).bites());
    }

    @Test
    void theStatusNamesTheNumberThatBites() {
        assertEquals(StockRuleStatus.BELOW_MINIMUM, rule(64L, 512L, StockRule.UNSET)
                .statusFor(StockLevels.stored(63L)));
        assertEquals(StockRuleStatus.SATISFIED, rule(64L, 512L, StockRule.UNSET).statusFor(StockLevels.stored(64L)));
        assertEquals(StockRuleStatus.AT_MAXIMUM, rule(StockRule.UNSET, 512L, StockRule.UNSET)
                .statusFor(StockLevels.stored(512L)));
        assertEquals(StockRuleStatus.SATISFIED, rule(StockRule.UNSET, 512L, StockRule.UNSET)
                .statusFor(StockLevels.stored(511L)));
        assertEquals(StockRuleStatus.AT_RESERVE, rule(StockRule.UNSET, StockRule.UNSET, 32L)
                .statusFor(StockLevels.stored(32L)));
        assertEquals(StockRuleStatus.SATISFIED, rule(StockRule.UNSET, StockRule.UNSET, 32L)
                .statusFor(StockLevels.stored(33L)));
        for (StockRuleStatus status : new StockRuleStatus[] { StockRuleStatus.BELOW_MINIMUM,
                StockRuleStatus.AT_MAXIMUM, StockRuleStatus.AT_RESERVE })
            assertTrue(status.bites(), status + " lights the lamp");
    }

    /** A full warehouse whose remaining stock is all reserved really is both; the storage cap is the more actionable. */
    @Test
    void theMaximumIsReportedBeforeTheReserve() {
        StockRule<String> rule = rule(StockRule.UNSET, 32L, 32L);
        StockLevels full = new StockLevels(32L, 0L, 0L, 32L);
        assertTrue(rule.isAtMaximum(full));
        assertTrue(rule.isAtReserve(full.available()));
        assertEquals(StockRuleStatus.AT_MAXIMUM, rule.statusFor(full));
    }

    @Test
    void theMinimumIsReportedBeforeEverythingElse() {
        StockRule<String> rule = rule(64L, 512L, 10L);
        StockLevels nearlyEmpty = new StockLevels(5L, 0L, 0L, 5L);
        assertTrue(rule.isBelowMinimum(nearlyEmpty));
        assertTrue(rule.isAtReserve(nearlyEmpty.available()));
        assertEquals(StockRuleStatus.BELOW_MINIMUM, rule.statusFor(nearlyEmpty));
    }

    /**
     * Below the minimum there is always room under the maximum, because a rule raises a maximum that was below its
     * minimum. The two statuses can therefore never compete, whatever the levels.
     */
    @Test
    void belowTheMinimumThereIsAlwaysRoomToStore() {
        for (long minimum = 0; minimum <= 64; minimum += 8) {
            for (long maximum = 0; maximum <= 64; maximum += 8) {
                StockRule<String> rule = rule(minimum, maximum, StockRule.UNSET);
                for (long stocked = 0; stocked <= 80; stocked += 5) {
                    for (long expected = 0; expected <= 16; expected += 8) {
                        StockLevels levels = new StockLevels(stocked, 0L, expected, stocked);
                        if (rule.isBelowMinimum(levels))
                            assertFalse(rule.isAtMaximum(levels),
                                    "below " + minimum + " but at maximum " + rule.maximum());
                    }
                }
            }
        }
    }

    // --- editing ---------------------------------------------------------------------------------------------------

    @Test
    void editingKeepsTheClampsAndTheOtherNumbers() {
        StockRule<String> rule = rule(64L, 512L, 32L);
        assertEquals(rule(128L, 512L, 32L), rule.withMinimum(128L));
        assertEquals(rule(64L, 64L, 32L), rule.withMaximum(10L), "the maximum is raised to the minimum again");
        assertEquals(rule(64L, 512L, 512L), rule.withReserve(9_999L), "and clamped to the maximum");
        assertEquals(StockRule.UNSET, rule.withMinimum(-1L).minimum(), "a number can be switched off again");
        StockRule<String> moved = rule.withKey(GOLD);
        assertEquals(GOLD, moved.key());
        assertEquals(rule.minimum(), moved.minimum());
        assertEquals(rule.maximum(), moved.maximum());
        assertEquals(rule.reserve(), moved.reserve());
    }

    @Test
    void rulesAreComparedByTheirItemAndTheirNumbers() {
        assertEquals(rule(64L, 512L, 32L), rule(64L, 512L, 32L));
        assertEquals(rule(64L, 512L, 32L).hashCode(), rule(64L, 512L, 32L).hashCode());
        assertNotEquals(rule(64L, 512L, 32L), rule(64L, 512L, 31L));
        assertNotEquals(rule(64L, 512L, 32L), new StockRule<>(GOLD, 64L, 512L, 32L));
        assertEquals(rule(64L, 32L, 99L), rule(64L, 64L, 64L), "two ways to write the same clamped rule");
    }
}
