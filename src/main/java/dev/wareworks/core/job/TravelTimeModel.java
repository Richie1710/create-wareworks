package dev.wareworks.core.job;

import java.util.Objects;

/**
 * Tick-exact durations of crane motion ({@code docs/warehouse-system.md} §7.1, {@code docs/stacker-crane.md} §4-5).
 * <p>
 * The formulas match {@code core.crane.CraneMotion} exactly (verified by JUnit): an axis at distance {@code d} with
 * speed {@code v} arrives in {@link #ticksToCover} = {@code max(1, ceil((d − ε) / v))} ticks, because a step snaps to
 * the target once the remaining distance is at most {@code v + ε} ({@value #SNAP_EPSILON}). X and Y move simultaneously,
 * so {@link #travelTicks} is the larger of the two. A stop at a location costs {@link #stopTicks}: extend, transfer,
 * retract.
 * <p>
 * Durations are {@code long}; {@link #UNAVAILABLE} means the motion never completes (a needed axis has speed 0, e.g. no
 * rotation). Sums saturate at {@link #UNAVAILABLE}, so rankings stay well-defined.
 */
public final class TravelTimeModel {
    /** Duration of a motion that never completes. */
    public static final long UNAVAILABLE = Long.MAX_VALUE;
    /** Positions within this distance of a target snap to it (blocks or extensions). */
    public static final double SNAP_EPSILON = 1.0E-6;
    /** Arm extension of a fully extended arm. */
    public static final double FULL_EXTENSION = 1.0;

    private TravelTimeModel() {
    }

    /**
     * Ticks one axis needs to cover {@code distance} (sign ignored) at {@code speed} per tick: 0 for no distance,
     * {@link #UNAVAILABLE} for a non-positive speed.
     *
     * @throws IllegalArgumentException if {@code distance} is not finite
     */
    public static long ticksToCover(double distance, double speed) {
        if (!Double.isFinite(distance))
            throw new IllegalArgumentException("distance must be finite: " + distance);
        double magnitude = Math.abs(distance);
        if (magnitude == 0.0)
            return 0L;
        if (!(speed > 0.0))
            return UNAVAILABLE;
        double ticks = Math.ceil((magnitude - SNAP_EPSILON) / speed);
        if (ticks >= UNAVAILABLE)
            return UNAVAILABLE;
        return Math.max(1L, (long) ticks);
    }

    /** Ticks to travel from {@code (fromX, fromY)} to {@code (toX, toY)} with the arm retracted. */
    public static long travelTicks(CraneSpeeds speeds, double fromX, double fromY, double toX, double toY) {
        Objects.requireNonNull(speeds, "speeds");
        return Math.max(ticksToCover(toX - fromX, speeds.vx()), ticksToCover(toY - fromY, speeds.vy()));
    }

    /** Ticks to extend (or retract) the arm fully: {@code ceil(1 / va)}. */
    public static long armTicks(CraneSpeeds speeds) {
        Objects.requireNonNull(speeds, "speeds");
        return ticksToCover(FULL_EXTENSION, speeds.va());
    }

    /** Ticks spent at one location: extend, transfer ({@code transferTicks}), retract. */
    public static long stopTicks(CraneSpeeds speeds, int transferTicks) {
        if (transferTicks < 0)
            throw new IllegalArgumentException("transferTicks must not be negative: " + transferTicks);
        long arm = armTicks(speeds);
        return add(add(arm, transferTicks), arm);
    }

    /**
     * Ticks for a whole job from an idle crane with a retracted arm: travel to the source, a stop there, travel to the
     * target and a stop there.
     */
    public static long tripTicks(CraneSpeeds speeds, int transferTicks, double craneX, double craneY, double sourceX,
            double sourceY, double targetX, double targetY) {
        long stop = stopTicks(speeds, transferTicks);
        long total = travelTicks(speeds, craneX, craneY, sourceX, sourceY);
        total = add(total, stop);
        total = add(total, travelTicks(speeds, sourceX, sourceY, targetX, targetY));
        return add(total, stop);
    }

    /** Whether a duration completes. */
    public static boolean isAvailable(long ticks) {
        return ticks != UNAVAILABLE;
    }

    /** Sum of two non-negative durations, saturating at {@link #UNAVAILABLE}. */
    public static long add(long a, long b) {
        if (a < 0 || b < 0)
            throw new IllegalArgumentException("durations must not be negative: " + a + ", " + b);
        if (a == UNAVAILABLE || b == UNAVAILABLE || a > UNAVAILABLE - b)
            return UNAVAILABLE;
        return a + b;
    }
}
