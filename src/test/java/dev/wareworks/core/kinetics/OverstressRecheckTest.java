package dev.wareworks.core.kinetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class OverstressRecheckTest {

    /** The ticks (1-based) out of {@code ticks} overstressed ticks on which a recheck runs. */
    private static List<Integer> rechecks(OverstressRecheck schedule, int ticks) {
        List<Integer> at = new ArrayList<>();
        for (int t = 1; t <= ticks; t++)
            if (schedule.tick(true))
                at.add(t);
        return at;
    }

    @Test
    void aHealthyMemberIsNeverRechecked() {
        OverstressRecheck schedule = new OverstressRecheck();
        for (int t = 0; t < 10_000; t++)
            assertFalse(schedule.tick(false));
    }

    @Test
    void anOverstressedMemberIsRecheckedAfterTheFirstDelayAndThenOnEveryInterval() {
        List<Integer> at = rechecks(new OverstressRecheck(), 400);
        int first = OverstressRecheck.FIRST_DELAY_TICKS;
        int every = OverstressRecheck.INTERVAL_TICKS;
        assertEquals(List.of(first, first + every, first + 2 * every, first + 3 * every), at);
    }

    @Test
    void theFirstRecheckAfterALoadComesSoonButNotAtOnce() {
        assertTrue(OverstressRecheck.FIRST_DELAY_TICKS <= 60, "a stale crane must not wait long after a restart");
        assertTrue(OverstressRecheck.FIRST_DELAY_TICKS >= 20, "neighbouring chunks need a moment to load");
        assertTrue(OverstressRecheck.INTERVAL_TICKS >= OverstressRecheck.FIRST_DELAY_TICKS);
    }

    @Test
    void recoveringResetsTheScheduleToTheFirstDelay() {
        OverstressRecheck schedule = new OverstressRecheck();
        rechecks(schedule, OverstressRecheck.FIRST_DELAY_TICKS + 30);
        assertFalse(schedule.tick(false));
        assertEquals(List.of(OverstressRecheck.FIRST_DELAY_TICKS),
                rechecks(schedule, OverstressRecheck.FIRST_DELAY_TICKS + 1));
    }

    @Test
    void aBriefRecoveryDuringTheDelayStartsItAgain() {
        OverstressRecheck schedule = new OverstressRecheck();
        rechecks(schedule, OverstressRecheck.FIRST_DELAY_TICKS - 1);
        assertFalse(schedule.tick(false));
        assertEquals(List.of(OverstressRecheck.FIRST_DELAY_TICKS),
                rechecks(schedule, OverstressRecheck.FIRST_DELAY_TICKS));
    }

    @Test
    void aFlagIsStaleOnlyWhenTheRecomputedTotalsAreNotOverstressed() {
        assertTrue(OverstressRecheck.staleAfterRecompute(true, 1536f, 512f));
        assertTrue(OverstressRecheck.staleAfterRecompute(true, 512f, 512f), "Create overstresses only below stress");
        assertFalse(OverstressRecheck.staleAfterRecompute(true, 0f, 512f), "really overstressed stays overstressed");
        assertFalse(OverstressRecheck.staleAfterRecompute(false, 1536f, 512f));
        assertFalse(OverstressRecheck.staleAfterRecompute(false, 0f, 512f));
    }
}
