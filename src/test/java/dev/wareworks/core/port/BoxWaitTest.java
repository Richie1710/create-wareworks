package dev.wareworks.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The rule that decides when a box at a door's Packager is reported as waiting (M27, issue #18, ADR-065 D14):
 * {@value BoxWait#REPORT_AFTER_TICKS} ticks at rest, as far as the looks can tell, where two looks less than
 * {@value BoxWait#SAME_BOX_GAP_TICKS} ticks apart prove one wait and farther ones only by the box's identity.
 */
class BoxWaitTest {
    private static final int WAIT = BoxWait.REPORT_AFTER_TICKS;
    private static final int GAP = BoxWait.SAME_BOX_GAP_TICKS;
    private static final long START = 1_000L;

    /** The gap proves only what the tray cycle allows, and a report waits for much longer than one cycle. */
    @Test
    void theGapIsTheTrayCycleAndTheReportWaitsLonger() {
        assertEquals(20, GAP, "the gap mirrors PackagerBlockEntity.CYCLE and must never be larger");
        assertTrue(WAIT > GAP, "a report comes only after a wait much longer than one tray cycle");
    }

    /** Looks in every tick, as a goggle tooltip does: reported exactly once the box has rested the full time. */
    @Test
    void aBoxLookedAtEveryTickIsReportedAfterTheFullWait() {
        BoxWait wait = new BoxWait();
        Object box = new Object();
        for (int tick = 0; tick < WAIT; tick++)
            assertFalse(wait.look(START + tick, box), "not yet after " + tick + " ticks");
        assertTrue(wait.look(START + WAIT, box), "reported after exactly " + WAIT + " ticks");
        assertTrue(wait.look(START + WAIT + 100, box), "and for as long as the same box waits");
    }

    /** Sparse looks, as the door rows make: the same box at both is a box nobody took, however far apart. */
    @Test
    void theSameBoxAtTwoSparseLooksHasWaitedBetweenThem() {
        BoxWait wait = new BoxWait();
        Object box = new Object();
        assertFalse(wait.look(START, box), "the first look starts the wait");
        assertFalse(wait.look(START + WAIT - 1, box), "one tick short");
        assertTrue(wait.look(START + 5 * WAIT, box), "the same box much later has waited all along");
    }

    /**
     * Two different boxes at two looks a full wait apart: the first may have been taken and the second be new, so the
     * wait starts again. This is a door whose taker works, seen by the door rows.
     */
    @Test
    void anotherBoxAtASparseLookStartsTheWaitAgain() {
        BoxWait wait = new BoxWait();
        assertFalse(wait.look(START, new Object()), "the first box");
        assertFalse(wait.look(START + WAIT, new Object()), "another box a full wait later is not reported");
        assertFalse(wait.look(START + 2 * WAIT - 1, new Object()), "nor a third one");
    }

    /**
     * Another object at a look less than {@value BoxWait#SAME_BOX_GAP_TICKS} ticks later is still the same wait: a box
     * taken in between could not be followed by one at rest that soon, and a client's object changes with every packet
     * the Packager sends. Looks that close together carry the wait all the way to the report.
     */
    @Test
    void anotherObjectAtACloseLookContinuesTheWait() {
        BoxWait wait = new BoxWait();
        long now = START;
        assertFalse(wait.look(now, new Object()), "the first look");
        while (now - START + GAP - 1 < WAIT) {
            now += GAP - 1;
            assertFalse(wait.look(now, new Object()), "a new object " + (GAP - 1) + " ticks later continues the wait");
        }
        assertTrue(wait.look(START + WAIT, new Object()), "and it is reported once the full wait is over");
    }

    /**
     * Another object at a look {@value BoxWait#SAME_BOX_GAP_TICKS} ticks or more later may be another box: the first
     * one could have been taken and a new one have finished its tray cycle in between, so the wait starts again — even
     * though that look is still well inside the reporting time.
     */
    @Test
    void anotherObjectAtAGapOfATrayCycleStartsTheWaitAgain() {
        BoxWait wait = new BoxWait();
        assertFalse(wait.look(START, new Object()), "the first look");
        assertFalse(wait.look(START + GAP, new Object()), "another object a whole tray cycle later: a new wait");
        assertFalse(wait.look(START + WAIT, new Object()),
                "so a full wait after the first look is not reported for that new object");
        Object kept = new Object();
        assertFalse(wait.look(START + WAIT + GAP, kept), "a third object, a cycle later again, starts once more");
        assertFalse(wait.look(START + 2 * WAIT + GAP - 1, kept), "the same object one tick short of the wait");
        assertTrue(wait.look(START + 2 * WAIT + GAP, kept), "and reported once that object has waited the full time");
    }

    /** A look without a box at rest ends the wait: the box was taken, or the tray is moving again. */
    @Test
    void aLookWithoutABoxEndsTheWait() {
        BoxWait wait = new BoxWait();
        Object box = new Object();
        assertFalse(wait.look(START, box), "the wait starts");
        assertTrue(wait.look(START + WAIT, box), "and is reported");
        assertFalse(wait.look(START + WAIT + 1, null), "a look without a box is no report");
        assertFalse(wait.look(START + WAIT + 2, box), "and the next box starts from nothing, even the same object");
        assertTrue(wait.look(START + 2 * WAIT + 2, box), "until it has waited the full time again");
    }

    /** A game time that went backwards cannot prove anything, so the wait starts again. */
    @Test
    void aGameTimeThatWentBackKeepsTheWait() {
        BoxWait wait = new BoxWait();
        Object box = new Object();
        assertFalse(wait.look(START, box), "the wait starts");
        assertFalse(wait.look(START + 10, box), "ten ticks of it");
        assertFalse(wait.look(START + 8, box), "the clock was set back two ticks: the wait goes on");
        assertFalse(wait.look(START + 8 + WAIT - 11, box), "one tick short of the ticks the looks saw");
        assertTrue(wait.look(START + 8 + WAIT - 10, box), "and reported once they add up");
    }

    /**
     * A goggle tooltip on a client behind a server below 20 TPS: a new box object at every look, one look per client
     * tick, and the server's time packet setting the clock back a few ticks every twenty. The wait still fills.
     */
    @Test
    void aClientWhoseClockIsSetBackEverySecondStillReports() {
        BoxWait wait = new BoxWait();
        long now = START;
        int looks = 0;
        boolean reported = false;
        while (!reported && looks <= 3 * WAIT) {
            looks++;
            now++;
            if (looks % 20 == 0)
                now -= 1 + looks % 3;
            reported = wait.look(now, new Object());
        }
        assertTrue(reported, "a waiting box is reported despite the clock steps");
        assertTrue(looks >= WAIT, "but not before the looks saw " + WAIT + " ticks: " + looks);
    }

    /** Nothing but looks without a box never reports anything. */
    @Test
    void noBoxIsNeverReported() {
        BoxWait wait = new BoxWait();
        for (int tick = 0; tick <= 3 * WAIT; tick++)
            assertFalse(wait.look(START + tick, null), "no box at tick " + tick);
    }
}
