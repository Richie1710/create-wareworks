package dev.wareworks.content.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link CranePauseDecision}: the pause reason and, above all, its priority order
 * ({@code docs/stacker-crane.md} §4.2).
 * <p>
 * This is the only automated coverage of {@link CranePauseReason#OVERSTRESSED}: a GameTest cannot reach it, because one
 * crane cannot overstress a creative motor, and in the world the overstressed and the unpowered case take the same
 * stopped-speed path — only the goggle text differs, which is exactly what a regression here would get wrong.
 */
class CranePauseDecisionTest {
    @Test
    void aRunningCraneWithLoadedChunksIsNotPaused() {
        assertEquals(CranePauseReason.NONE, CranePauseDecision.reasonFor(false, false, false, true));
    }

    @Test
    void noRotationIsTheDefaultStoppedReason() {
        assertEquals(CranePauseReason.NO_ROTATION, CranePauseDecision.reasonFor(true, false, false, true));
    }

    @Test
    void overstressBeatsNoRotation() {
        assertEquals(CranePauseReason.OVERSTRESSED, CranePauseDecision.reasonFor(true, false, true, true),
                "getSpeed() is 0 in both cases; only the reason tells a player which one it is");
    }

    @Test
    void aZeroSpeedFactorBeatsRotationAndOverstress() {
        assertEquals(CranePauseReason.SPEED_FACTOR_ZERO, CranePauseDecision.reasonFor(true, true, false, true));
        assertEquals(CranePauseReason.SPEED_FACTOR_ZERO, CranePauseDecision.reasonFor(true, true, true, true),
                "the configuration is named before the machine, whatever the network does");
    }

    @Test
    void anUnloadedStopPausesARunningCrane() {
        assertEquals(CranePauseReason.CHUNK_NOT_LOADED, CranePauseDecision.reasonFor(false, false, false, false));
    }

    @Test
    void aStoppedCraneNeverReportsAnUnloadedChunk() {
        assertEquals(CranePauseReason.NO_ROTATION, CranePauseDecision.reasonFor(true, false, false, false),
                "the machine stands still for a reason the player can act on");
        assertEquals(CranePauseReason.OVERSTRESSED, CranePauseDecision.reasonFor(true, false, true, false));
        assertEquals(CranePauseReason.SPEED_FACTOR_ZERO, CranePauseDecision.reasonFor(true, true, false, false));
    }

    @Test
    void aZeroFactorOrOverstressFlagIsIgnoredWhileTheCraneMoves() {
        // Both are only observable while every axis is stopped; a moving crane must never report them.
        assertEquals(CranePauseReason.NONE, CranePauseDecision.reasonFor(false, true, true, true));
        assertEquals(CranePauseReason.CHUNK_NOT_LOADED, CranePauseDecision.reasonFor(false, true, true, false));
    }
}
