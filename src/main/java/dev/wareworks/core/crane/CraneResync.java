package dev.wareworks.core.crane;

import java.util.Objects;

import dev.wareworks.core.job.CraneSpeeds;

/**
 * When a client that simulates the crane itself has to snap to a synced pose ({@code docs/stacker-crane.md} §5.2).
 * <p>
 * The client runs the same {@link CraneMotion} as the server and only adopts a synced pose when its own has drifted too
 * far; otherwise a packet that arrives a tick early or late would make the crane jump. The threshold is therefore
 * <b>not</b> a fixed distance: at high RPM one tick of travel already exceeds {@value #SNAP_DISTANCE} blocks, so it is
 * the larger of that distance and {@value #SNAP_SPEED_TICKS} ticks of the axis's own motion (M3 review fix; above about
 * 192 RPM the fixed distance snapped fast cranes on most packets).
 * <p>
 * Pure: no {@code Level}, no rendering, so the rule is unit tested instead of only being visible to a player watching a
 * fast crane.
 */
public final class CraneResync {
    /** Smallest divergence (blocks, or extensions for the arm) that makes a client snap. */
    public static final double SNAP_DISTANCE = 0.5;
    /** Ticks of motion per axis a synced pose may differ by without a snap, if that is more than {@link #SNAP_DISTANCE}. */
    public static final double SNAP_SPEED_TICKS = 2.0;

    private CraneResync() {
    }

    /** The snap distance of an axis moving at {@code speed} per tick: never below {@link #SNAP_DISTANCE}. */
    public static double snapDistance(double speed) {
        return Math.max(SNAP_DISTANCE, SNAP_SPEED_TICKS * speed);
    }

    /**
     * Whether the client's own pose has drifted so far from the synced one that it must snap: any axis differs by more
     * than its {@link #snapDistance}, or the arm points at the other rack side while <b>both</b> arms are out.
     * <p>
     * The side is only compared while both arms are extended: a retracted arm has no visible side, so snapping for it
     * would be a jump with no cause.
     */
    public static boolean diverges(CranePose own, CranePose synced, CraneSpeeds speeds) {
        Objects.requireNonNull(own, "own");
        Objects.requireNonNull(synced, "synced");
        Objects.requireNonNull(speeds, "speeds");
        return Math.abs(own.x() - synced.x()) > snapDistance(speeds.vx())
                || Math.abs(own.y() - synced.y()) > snapDistance(speeds.vy())
                || Math.abs(own.arm() - synced.arm()) > snapDistance(speeds.va())
                || (own.side() != synced.side() && own.arm() > CranePose.RETRACTED
                        && synced.arm() > CranePose.RETRACTED);
    }
}
