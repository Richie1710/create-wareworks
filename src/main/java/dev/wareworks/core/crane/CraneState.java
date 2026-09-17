package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.job.TransportJob;

/**
 * The persisted and synced state of one crane ({@code docs/stacker-crane.md} §1, §4). Immutable; {@code with*} methods
 * return copies. {@link CraneStateMachine} computes transitions, {@link CraneMotion} moves the pose.
 * <p>
 * The canonical constructor validates single fields only. Whether the fields fit together (a job in every phase but
 * {@link CranePhase#IDLE}, items held after the pick, ...) is {@link #isConsistent()}; states loaded from a save go
 * through {@link CraneStateMachine#resume} first.
 *
 * @param pose           current axis positions
 * @param previousPose   pose one tick earlier, for partial-tick interpolation
 * @param target         pose the current phase moves towards
 * @param phase          state machine phase
 * @param phaseTicks     ticks spent in a transfer phase
 * @param retryTicks     ticks left until the retry of {@link CranePhase#HOLDING} / {@link CranePhase#WAITING_FOR_TARGET}
 * @param awaitingResult whether a perform effect or reroute request waits for its result
 * @param paused         whether motion and timers are stopped
 * @param job            the current job; the held items are {@code job.heldAmount()} of {@code job.key()}
 * @param interruption   what to do after the arm has retracted, if a stop was interrupted
 * @param <K>            item key type
 * @param <L>            location type
 */
public record CraneState<K, L>(CranePose pose, CranePose previousPose, CranePose target, CranePhase phase,
        int phaseTicks, int retryTicks, boolean awaitingResult, boolean paused, Optional<TransportJob<K, L>> job,
        Optional<CraneInterruption> interruption) {
    public CraneState {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(previousPose, "previousPose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(interruption, "interruption");
        if (phaseTicks < 0)
            throw new IllegalArgumentException("phaseTicks must not be negative: " + phaseTicks);
        if (retryTicks < 0)
            throw new IllegalArgumentException("retryTicks must not be negative: " + retryTicks);
    }

    /** An idle crane without job at {@code pose}; its target retracts the arm. */
    public static <K, L> CraneState<K, L> idle(CranePose pose) {
        return new CraneState<>(pose, pose, pose.withArm(CranePose.RETRACTED), CranePhase.IDLE, 0, 0, false, false,
                Optional.empty(), Optional.empty());
    }

    /**
     * A crane shown at {@code pose} in {@code phase} that only moves towards {@code target} ({@link CraneMotion}): no job,
     * no timers, not paused. For client display poses (Ponder, visual tests); the state machine never runs it, and it is
     * not {@link #isConsistent() consistent} for phases that need a job.
     */
    public static <K, L> CraneState<K, L> displayed(CranePose pose, CranePose target, CranePhase phase) {
        return new CraneState<>(pose, pose, target, phase, 0, 0, false, false, Optional.empty(), Optional.empty());
    }

    /** Items in the handling head. */
    public int heldAmount() {
        return job.map(TransportJob::heldAmount).orElse(0);
    }

    /** The job or {@code null}, for persistence. */
    public @Nullable TransportJob<K, L> jobOrNull() {
        return job.orElse(null);
    }

    /** Whether {@link CraneEvent.JobAssigned} is accepted: idle, or complete with nothing held. */
    public boolean canAcceptJob() {
        return phase == CranePhase.IDLE || (phase == CranePhase.COMPLETE && heldAmount() == 0);
    }

    /** Whether the pose changed in the last tick. */
    public boolean isMoving() {
        return !pose.equals(previousPose);
    }

    /** The pose for rendering at {@code partialTicks} between the previous and the current tick. */
    public CranePose interpolatedPose(double partialTicks) {
        return CranePose.lerp(previousPose, pose, partialTicks);
    }

    /**
     * Whether the fields fit together: {@link CranePhase#IDLE} exactly without job; nothing picked before
     * {@link CranePhase#RETRACT_SOURCE}; items held from {@link CranePhase#TRAVEL_TO_TARGET} to
     * {@link CranePhase#WAITING_FOR_TARGET} (except while retracting from the target); nothing held in
     * {@link CranePhase#COMPLETE}; results awaited only in {@link CranePhase#PICK}, {@link CranePhase#DROP} and
     * {@link CranePhase#REROUTE}; interruptions only while retracting.
     */
    public boolean isConsistent() {
        if (phase == CranePhase.IDLE)
            return job.isEmpty() && interruption.isEmpty() && !awaitingResult;
        if (job.isEmpty())
            return false;
        if (awaitingResult && phase != CranePhase.PICK && phase != CranePhase.DROP && phase != CranePhase.REROUTE)
            return false;
        if (interruption.isPresent() && phase != CranePhase.RETRACT_SOURCE && phase != CranePhase.RETRACT_TARGET)
            return false;
        TransportJob<K, L> current = job.get();
        return switch (phase) {
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, PICK -> !current.picked();
            case RETRACT_SOURCE -> true;
            case TRAVEL_TO_TARGET, EXTEND_TARGET, DROP, REROUTE, HOLDING, WAITING_FOR_TARGET ->
                    current.picked() && current.heldAmount() > 0;
            case RETRACT_TARGET -> current.picked();
            case COMPLETE -> current.picked() && current.heldAmount() == 0;
            case IDLE -> throw new IllegalStateException("handled above");
        };
    }

    // --- copies --------------------------------------------------------------------------------------------------

    public CraneState<K, L> withPoses(CranePose newPreviousPose, CranePose newPose) {
        return new CraneState<>(newPose, newPreviousPose, target, phase, phaseTicks, retryTicks, awaitingResult, paused,
                job, interruption);
    }

    public CraneState<K, L> withPreviousPose(CranePose newPreviousPose) {
        return new CraneState<>(pose, newPreviousPose, target, phase, phaseTicks, retryTicks, awaitingResult, paused,
                job, interruption);
    }

    public CraneState<K, L> withTarget(CranePose newTarget) {
        return new CraneState<>(pose, previousPose, newTarget, phase, phaseTicks, retryTicks, awaitingResult, paused,
                job, interruption);
    }

    /** This state in {@code newPhase} with phase ticks, retry ticks and the awaited result reset. */
    public CraneState<K, L> withPhase(CranePhase newPhase) {
        return new CraneState<>(pose, previousPose, target, newPhase, 0, 0, false, paused, job, interruption);
    }

    public CraneState<K, L> withPhaseTicks(int newPhaseTicks) {
        return new CraneState<>(pose, previousPose, target, phase, newPhaseTicks, retryTicks, awaitingResult, paused,
                job, interruption);
    }

    public CraneState<K, L> withRetryTicks(int newRetryTicks) {
        return new CraneState<>(pose, previousPose, target, phase, phaseTicks, newRetryTicks, awaitingResult, paused,
                job, interruption);
    }

    public CraneState<K, L> withAwaitingResult(boolean newAwaitingResult) {
        return new CraneState<>(pose, previousPose, target, phase, phaseTicks, retryTicks, newAwaitingResult, paused,
                job, interruption);
    }

    public CraneState<K, L> withPaused(boolean newPaused) {
        return new CraneState<>(pose, previousPose, target, phase, phaseTicks, retryTicks, awaitingResult, newPaused,
                job, interruption);
    }

    public CraneState<K, L> withJob(TransportJob<K, L> newJob) {
        return new CraneState<>(pose, previousPose, target, phase, phaseTicks, retryTicks, awaitingResult, paused,
                Optional.of(newJob), interruption);
    }

    public CraneState<K, L> withInterruption(Optional<CraneInterruption> newInterruption) {
        return new CraneState<>(pose, previousPose, target, phase, phaseTicks, retryTicks, awaitingResult, paused, job,
                newInterruption);
    }

    /** Idle at the current pose: no job, no interruption, target retracts the arm; the paused flag is kept. */
    public CraneState<K, L> toIdle() {
        return new CraneState<>(pose, previousPose, pose.withArm(CranePose.RETRACTED), CranePhase.IDLE, 0, 0, false,
                paused, Optional.empty(), Optional.empty());
    }
}
