package dev.wareworks.core.traffic;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;

/**
 * The next stop one crane asks the traffic ledger for: a pure function of its state machine phase and poses (issue
 * #14, ADR-075 §4.2).
 * <p>
 * A crane asks for the <b>whole</b> route to its next stop and gets all of it or nothing. Which stop that is follows
 * from the phase:
 * <ul>
 * <li>{@code IDLE}: its resting target, if that is another block (home point, dock, a park it was sent to) — a
 * {@link Kind#REST} want;</li>
 * <li>{@code TRAVEL_TO_SOURCE}, {@code EXTEND_SOURCE}: the source;</li>
 * <li>{@code PICK}, {@code RETRACT_SOURCE}: the job's target, <b>posted ahead</b>, so the grant is ready when the arm
 * is in and no tick is lost;</li>
 * <li>{@code TRAVEL_TO_TARGET}, {@code EXTEND_TARGET}: the target;</li>
 * <li>every other phase: its current target if that is another block (a reroute target).</li>
 * </ul>
 * Every want but the idle one is a {@link Kind#JOB} want. How long a want has waited is kept by the ledger, not here,
 * so a want is a value that compares equal for as long as the crane wants the same stop.
 *
 * @param kind   what the stop is for, which decides its priority
 * @param branch the stop's branch
 * @param x      the stop's position on that branch: always a whole block, because every stop is a rack position, a
 *               home point or a dock
 */
public record TrafficWant(Kind kind, int branch, int x) {
    /** What a stop is for. {@link #name()} is a stable save name. */
    public enum Kind {
        /** Part of a transport job: served oldest first. */
        JOB,
        /** Going home or to a park with nothing to do: served last, by crane number. */
        REST
    }

    public TrafficWant {
        Objects.requireNonNull(kind, "kind");
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch out of range: " + branch);
        if (x < 0)
            throw new IllegalArgumentException("x must not be negative: " + x);
    }

    /**
     * The want of a crane in {@code phase} standing at {@code pose} whose phase moves towards {@code target}, or empty
     * when it wants to stay where it is.
     *
     * @param jobTarget the rack position of the job's target, for the want posted ahead during {@code PICK} and
     *                  {@code RETRACT_SOURCE}; empty without a job
     */
    public static Optional<TrafficWant> of(CranePhase phase, CranePose pose, CranePose target,
            Optional<RackPosition> jobTarget) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(jobTarget, "jobTarget");
        return switch (phase) {
            case IDLE -> elsewhere(Kind.REST, pose, target.branch(), target.x());
            case PICK, RETRACT_SOURCE -> jobTarget.flatMap(rack -> elsewhere(Kind.JOB, pose, rack.branch(), rack.x()));
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, TRAVEL_TO_TARGET, EXTEND_TARGET, DROP, RETRACT_TARGET, COMPLETE,
                    REROUTE, HOLDING, WAITING_FOR_TARGET -> elsewhere(Kind.JOB, pose, target.branch(), target.x());
        };
    }

    private static Optional<TrafficWant> elsewhere(Kind kind, CranePose pose, int branch, double x) {
        if (pose.branch() == branch && pose.x() == x)
            return Optional.empty();
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH || !(x >= 0.0))
            return Optional.empty();
        return Optional.of(new TrafficWant(kind, branch, (int) Math.round(x)));
    }
}
