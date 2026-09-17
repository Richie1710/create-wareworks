package dev.wareworks.core.crane;

import java.util.Objects;

import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TravelTimeModel;

/**
 * Deterministic crane motion ({@code docs/stacker-crane.md} §4-5), shared by server and client so the client can
 * animate between sync packets with the same result.
 * <p>
 * One {@link #step} moves the pose one tick towards the target:
 * <ol>
 *   <li>While X or Y differ from the target, an extended arm first retracts; X and Y do not move in any tick in which
 *       the arm is not fully retracted.</li>
 *   <li>With the arm retracted, X and Y move simultaneously, each at its own speed; the arm's side follows the target.</li>
 *   <li>Only when X and Y equal the target exactly does the arm move towards the target extension, retracting first if
 *       it points to the other side.</li>
 * </ol>
 * An axis snaps exactly onto its target once the remaining distance is at most {@code speed +}
 * {@value TravelTimeModel#SNAP_EPSILON}, so it never overshoots and "at target" is an exact comparison. The number of
 * ticks equals {@link TravelTimeModel#ticksToCover}. Pure functions without allocation beyond the returned records.
 */
public final class CraneMotion {
    private CraneMotion() {
    }

    /** The pose after one tick towards {@code target}. A speed of 0 leaves that axis where it is. */
    public static CranePose step(CranePose pose, CranePose target, CraneSpeeds speeds) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(speeds, "speeds");
        if (!pose.sameXY(target)) {
            if (pose.arm() > CranePose.RETRACTED)
                return pose.withArm(approach(pose.arm(), CranePose.RETRACTED, speeds.va()));
            return new CranePose(approach(pose.x(), target.x(), speeds.vx()),
                    approach(pose.y(), target.y(), speeds.vy()), CranePose.RETRACTED, target.side());
        }
        CranePose current = pose;
        if (current.side() != target.side()) {
            if (current.arm() > CranePose.RETRACTED)
                return current.withArm(approach(current.arm(), CranePose.RETRACTED, speeds.va()));
            current = current.withSide(target.side());
        }
        return current.withArm(approach(current.arm(), target.arm(), speeds.va()));
    }

    /** The state after one motion tick: the previous pose becomes the current one, the pose moves to its target. */
    public static <K, L> CraneState<K, L> step(CraneState<K, L> state, CraneSpeeds speeds) {
        Objects.requireNonNull(state, "state");
        return state.withPoses(state.pose(), step(state.pose(), state.target(), speeds));
    }

    /**
     * Whether {@code pose} has reached {@code target}: same X, Y and extension, and the same side unless the arm is
     * retracted.
     */
    public static boolean isAt(CranePose pose, CranePose target) {
        if (!pose.sameXY(target) || pose.arm() != target.arm())
            return false;
        return pose.arm() == CranePose.RETRACTED || pose.side() == target.side();
    }

    /** One axis moved by at most {@code speed} towards {@code to}, snapping within speed + epsilon. */
    static double approach(double from, double to, double speed) {
        double delta = to - from;
        if (Math.abs(delta) <= speed + TravelTimeModel.SNAP_EPSILON)
            return to;
        return from + Math.copySign(speed, delta);
    }
}
