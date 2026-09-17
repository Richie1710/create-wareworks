package dev.wareworks.content.crane;

/**
 * Which {@link CranePauseReason} a crane reports, as a pure function of what the world was observed to be
 * ({@code docs/stacker-crane.md} §4.2). {@link CraneExecution} does the observing; this decides.
 * <p>
 * The <b>order</b> is the point and is fixed by the design: a configured speed factor of 0 is reported before rotation
 * and overstress, because it stops every crane of the server whatever its kinetic network does, and reporting "no
 * rotation" would send a player looking for a broken shaft. Overstress is reported before "no rotation", because
 * {@code getSpeed()} is 0 in both cases and only the goggle text tells them apart.
 * <p>
 * Pure (no {@code Level}, no block entity), so the order is unit tested — including the overstressed branch, which no
 * GameTest can reach, since one crane cannot overstress a creative motor.
 */
final class CranePauseDecision {
    private CranePauseDecision() {
    }

    /**
     * @param stopped          every axis speed is 0 (no rotation, overstressed, or a configured factor is 0)
     * @param zeroSpeedFactor  a {@code crane.*} speed factor is 0 in the server config
     * @param overStressed     the kinetic network is overstressed
     * @param stopChunksLoaded the aisle column under the crane and the location of its current stop are loaded; also
     *                         {@code true} when the crane has no job and therefore no stop to check
     * @return the reason, or {@link CranePauseReason#NONE} when the crane may run
     */
    static CranePauseReason reasonFor(boolean stopped, boolean zeroSpeedFactor, boolean overStressed,
            boolean stopChunksLoaded) {
        if (stopped) {
            if (zeroSpeedFactor)
                return CranePauseReason.SPEED_FACTOR_ZERO;
            return overStressed ? CranePauseReason.OVERSTRESSED : CranePauseReason.NO_ROTATION;
        }
        return stopChunksLoaded ? CranePauseReason.NONE : CranePauseReason.CHUNK_NOT_LOADED;
    }
}
