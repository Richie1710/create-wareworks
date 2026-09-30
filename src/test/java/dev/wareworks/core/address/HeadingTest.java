package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link Heading} is {@code core}'s stand-in for Minecraft's horizontal direction, so it has to behave like one. */
class HeadingTest {
    @Test
    void stepsMatchTheWorldAxes() {
        assertEquals(0, Heading.NORTH.stepX());
        assertEquals(-1, Heading.NORTH.stepZ(), "north is -Z");
        assertEquals(1, Heading.EAST.stepX(), "east is +X");
        assertEquals(0, Heading.EAST.stepZ());
        assertEquals(1, Heading.SOUTH.stepZ(), "south is +Z");
        assertEquals(-1, Heading.WEST.stepX(), "west is -X");
    }

    @Test
    void turnsClockwiseSeenFromAbove() {
        assertEquals(Heading.EAST, Heading.NORTH.right());
        assertEquals(Heading.SOUTH, Heading.EAST.right());
        assertEquals(Heading.WEST, Heading.SOUTH.right());
        assertEquals(Heading.NORTH, Heading.WEST.right());
        for (Heading heading : Heading.values()) {
            assertEquals(heading, heading.right().left(), "left undoes right");
            assertEquals(heading, heading.opposite().opposite(), "and opposite undoes itself");
            assertEquals(heading.right().right(), heading.opposite(), "two quarter turns are a half turn");
        }
    }

    @Test
    void knowsItsAxisAndWhatCrossesIt() {
        assertEquals(Heading.Axis.Z, Heading.NORTH.axis());
        assertEquals(Heading.Axis.X, Heading.EAST.axis());
        for (Heading heading : Heading.values()) {
            assertFalse(heading.isPerpendicularTo(heading));
            assertFalse(heading.isPerpendicularTo(heading.opposite()), "a reversal is not a turn");
            assertTrue(heading.isPerpendicularTo(heading.right()), "but a quarter turn is");
            assertTrue(heading.isPerpendicularTo(heading.left()));
        }
    }

    @Test
    void projectsOffsetsOntoItself() {
        // Three east, two south, measured from an east-facing branch: three along it, two to its right.
        assertEquals(3, Heading.EAST.along(3, 2));
        assertEquals(2, Heading.EAST.lateral(3, 2));
        // The same offset seen from a south-facing branch: two along it, three to its right (west is its left).
        assertEquals(2, Heading.SOUTH.along(3, 2));
        assertEquals(-3, Heading.SOUTH.lateral(3, 2));
        for (Heading heading : Heading.values()) {
            assertEquals(1, heading.along(heading.stepX(), heading.stepZ()), "one step forward");
            assertEquals(0, heading.lateral(heading.stepX(), heading.stepZ()), "is no step sideways");
            Heading right = heading.right();
            assertEquals(Side.RIGHT.lateralOffset(), heading.lateral(right.stepX(), right.stepZ()),
                    "and the clockwise side is the RIGHT rack plane");
        }
    }

    @Test
    void readsBackItsOwnSteps() {
        for (Heading heading : Heading.values())
            assertEquals(heading, Heading.of(heading.stepX(), heading.stepZ()));
        assertNull(Heading.of(0, 0), "standing still is no heading");
        assertNull(Heading.of(1, 1), "and neither is a diagonal");
    }
}
