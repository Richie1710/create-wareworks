package dev.wareworks.core.crane;

import java.util.Optional;

/**
 * Phases of the crane state machine ({@code docs/stacker-crane.md} §4). {@link #name()} is the stable save name.
 * "Paused" is not a phase but a flag of {@link CraneState}.
 */
public enum CranePhase {
    /** No job. */
    IDLE,
    /** Moving X/Y to the source with the arm retracted. */
    TRAVEL_TO_SOURCE,
    /** Extending the arm into the source. */
    EXTEND_SOURCE,
    /** Transfer animation at the source; the real extraction happens once at its end. */
    PICK,
    /** Retracting the arm from the source (also after an interrupted pick). */
    RETRACT_SOURCE,
    /** Moving X/Y to the target with the arm retracted. */
    TRAVEL_TO_TARGET,
    /** Extending the arm into the target. */
    EXTEND_TARGET,
    /** Transfer animation at the target; the real insertion happens once at its end. */
    DROP,
    /** Retracting the arm from the target. */
    RETRACT_TARGET,
    /** Everything delivered; the next tick returns to {@link #IDLE}. */
    COMPLETE,
    /** Waiting for a new target for held leftovers. */
    REROUTE,
    /** No target for held items; retries the reroute every {@code holdRetryTicks}. */
    HOLDING,
    /** The output station is full; retries the drop every {@code retryTicks}. */
    WAITING_FOR_TARGET;

    /** Whether the phase ends when the crane reaches its target pose. */
    public boolean isMotion() {
        return switch (this) {
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, RETRACT_SOURCE, TRAVEL_TO_TARGET, EXTEND_TARGET, RETRACT_TARGET -> true;
            default -> false;
        };
    }

    /** Whether the phase is a timed transfer ({@link #PICK} or {@link #DROP}). */
    public boolean isTransfer() {
        return this == PICK || this == DROP;
    }

    /** The phase with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<CranePhase> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (CranePhase phase : values()) {
            if (phase.name().equals(name))
                return Optional.of(phase);
        }
        return Optional.empty();
    }
}
