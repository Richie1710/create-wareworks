package dev.wareworks.core.handtruck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The hand truck's slow walk (issue #37): the input scaling a carrying player's client applies, the sprint cap and the
 * carry stamp's window.
 */
class HandTruckPenaltyTest {
    private static final float EPSILON = 1.0E-6F;

    @Test
    void halfSpeedKeepsTheDirection() {
        HandTruckPenalty.Impulse diagonal = HandTruckPenalty.scale(1.0F, 1.0F, 0.5, true);
        assertEquals(0.5F, diagonal.forward(), EPSILON);
        assertEquals(0.5F, diagonal.left(), EPSILON);
        HandTruckPenalty.Impulse backwards = HandTruckPenalty.scale(-1.0F, -0.3F, 0.5, true);
        assertEquals(-0.5F, backwards.forward(), EPSILON);
        assertEquals(-0.15F, backwards.left(), EPSILON);
    }

    @Test
    void sneakingInputIsScaledLinearly() {
        // Vanilla scales a sneaking player's input to 0.3 before the event; the truck halves that again.
        HandTruckPenalty.Impulse sneaking = HandTruckPenalty.scale(0.3F, 0.0F, 0.5, true);
        assertEquals(0.15F, sneaking.forward(), EPSILON);
        assertEquals(0.0F, sneaking.left(), EPSILON);
    }

    @Test
    void preventingSprintCapsForwardJustBelowASprint() {
        HandTruckPenalty.Impulse full = HandTruckPenalty.scale(1.0F, 0.0F, 1.0, true);
        assertEquals(HandTruckPenalty.MAX_FORWARD_WITHOUT_SPRINT, full.forward(), EPSILON);
        assertTrue(full.forward() < HandTruckPenalty.SPRINT_IMPULSE, "a capped input can never start a sprint");
        // The sideways impulse is never capped: it cannot start a sprint.
        assertEquals(1.0F, HandTruckPenalty.scale(1.0F, 1.0F, 1.0, true).left(), EPSILON);
        // At the default multiplier the cap never changes the speed.
        assertEquals(0.5F, HandTruckPenalty.scale(1.0F, 0.0F, 0.5, true).forward(), EPSILON);
    }

    @Test
    void noSlowDownAndNoSprintBlockLeavesTheInputAlone() {
        HandTruckPenalty.Impulse unchanged = HandTruckPenalty.scale(1.0F, -1.0F, 1.0, false);
        assertEquals(1.0F, unchanged.forward(), EPSILON);
        assertEquals(-1.0F, unchanged.left(), EPSILON);
    }

    @Test
    void theMultiplierIsClampedIntoTheConfigRange() {
        assertEquals(0.1F, HandTruckPenalty.scale(1.0F, 0.0F, 0.0, false).forward(), EPSILON);
        assertEquals(1.0F, HandTruckPenalty.scale(1.0F, 0.0F, 5.0, false).forward(), EPSILON);
        assertEquals(1.0F, HandTruckPenalty.scale(1.0F, 0.0F, Double.NaN, false).forward(), EPSILON);
        assertEquals(HandTruckPenalty.MIN_MULTIPLIER, HandTruckPenalty.clampMultiplier(-3.0));
    }

    @Test
    void aStampCountsForThisTickAndTheOneBefore() {
        assertTrue(HandTruckPenalty.withinWindow(100, 100));
        assertTrue(HandTruckPenalty.withinWindow(101, 100));
        assertFalse(HandTruckPenalty.withinWindow(102, 100), "two ticks old: the truck has left the inventory");
        assertFalse(HandTruckPenalty.withinWindow(99, 100), "the tick counter only grows: a stamp ahead of it is not a real one");
        assertFalse(HandTruckPenalty.withinWindow(0, HandTruckPenalty.NEVER));
        assertFalse(HandTruckPenalty.withinWindow(Long.MAX_VALUE, HandTruckPenalty.NEVER));
    }
}
