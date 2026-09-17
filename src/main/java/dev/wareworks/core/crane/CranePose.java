package dev.wareworks.core.crane;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.TravelTimeModel;

/**
 * Logical position of the crane's axes ({@code docs/stacker-crane.md} §1), also used as a motion target.
 *
 * @param x    position along the aisle ({@code 0 … L}); integers are rack positions
 * @param y    level ({@code 0 … H-1})
 * @param arm  arm extension, {@value #RETRACTED} … {@value #EXTENDED}
 * @param side rack side the arm points to (irrelevant while retracted)
 */
public record CranePose(double x, double y, double arm, Side side) {
    /** Arm extension of a retracted arm. */
    public static final double RETRACTED = 0.0;
    /** Arm extension of a fully extended arm. */
    public static final double EXTENDED = TravelTimeModel.FULL_EXTENSION;
    /** Side used when a saved or synced side is missing. */
    public static final Side DEFAULT_SIDE = Side.LEFT;

    public CranePose {
        if (!Double.isFinite(x) || !Double.isFinite(y))
            throw new IllegalArgumentException("position must be finite: " + x + ", " + y);
        if (!(arm >= RETRACTED && arm <= EXTENDED))
            throw new IllegalArgumentException("arm must be within " + RETRACTED + ".." + EXTENDED + ": " + arm);
        Objects.requireNonNull(side, "side");
    }

    /** A pose with a retracted arm. */
    public static CranePose at(double x, double y, Side side) {
        return new CranePose(x, y, RETRACTED, side);
    }

    /**
     * A pose from untrusted (saved or synced) values: non-finite coordinates become 0, the arm is clamped (NaN becomes
     * retracted) and a missing side becomes {@link #DEFAULT_SIDE}. Never throws.
     */
    public static CranePose sanitized(double x, double y, double arm, @Nullable Side side) {
        double safeArm = Double.isNaN(arm) ? RETRACTED : Math.max(RETRACTED, Math.min(EXTENDED, arm));
        return new CranePose(Double.isFinite(x) ? x : 0.0, Double.isFinite(y) ? y : 0.0, safeArm,
                side == null ? DEFAULT_SIDE : side);
    }

    public CranePose withXY(double newX, double newY) {
        return new CranePose(newX, newY, arm, side);
    }

    public CranePose withArm(double newArm) {
        return new CranePose(x, y, newArm, side);
    }

    public CranePose withSide(Side newSide) {
        return new CranePose(x, y, arm, newSide);
    }

    /** Whether X and Y equal {@code other}'s exactly (targets are snapped, never approximately reached). */
    public boolean sameXY(CranePose other) {
        return x == other.x && y == other.y;
    }

    /**
     * Linear interpolation for rendering between the previous and the current tick ({@code partialTicks} clamped to
     * 0..1). The side is the current one.
     */
    public static CranePose lerp(CranePose previous, CranePose current, double partialTicks) {
        double t = Double.isNaN(partialTicks) ? 1.0 : Math.max(0.0, Math.min(1.0, partialTicks));
        return new CranePose(previous.x + (current.x - previous.x) * t, previous.y + (current.y - previous.y) * t,
                Math.max(RETRACTED, Math.min(EXTENDED, previous.arm + (current.arm - previous.arm) * t)), current.side);
    }
}
