package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.TravelTimeModel;

/**
 * Logical position of the crane's axes ({@code docs/stacker-crane.md} §1), also used as a motion target.
 * <p>
 * Since M21 a warehouse is one rail network of straight branches (ADR-033), so the pose says <b>which branch</b> the
 * machine is named on and <b>which way it faces</b>: {@code x} is its position along that branch and {@code yaw} its
 * heading. A hand-over at a corner is a rename — the same world block under another branch's name — followed by a
 * quarter turn, and {@code yaw} is that turn's progress, so a save in mid-turn restores exactly where the machine was.
 * <p>
 * <b>Branch 0 and north are the defaults everywhere</b>, so every pose a warehouse that does not bend ever had is
 * described by the values it always was: {@link #CranePose(double, double, double, Side)} and {@link #at(double,
 * double, Side)} mean branch {@value RackPosition#FIRST_BRANCH}, and persistence omits both new fields when they carry
 * their default.
 *
 * @param branch branch of the warehouse the crane stands on, {@value RackPosition#FIRST_BRANCH}..{@value
 *               RackPosition#MAX_BRANCH}
 * @param x      position along that branch ({@code 0 … L}); integers are rack positions
 * @param y      level ({@code 0 … H-1})
 * @param arm    arm extension, {@value #RETRACTED} … {@value #EXTENDED}
 * @param side   rack side the arm points to (irrelevant while retracted)
 * @param yaw    direction the whole machine faces, in quarter turns clockwise from north and normalised to
 *               {@code [0, 4)}; at rest it equals the branch's {@link Heading}, in between it is a turn in progress
 */
public record CranePose(int branch, double x, double y, double arm, Side side, double yaw) {
    /** Arm extension of a retracted arm. */
    public static final double RETRACTED = 0.0;
    /** Arm extension of a fully extended arm. */
    public static final double EXTENDED = TravelTimeModel.FULL_EXTENSION;
    /** Side used when a saved or synced side is missing. */
    public static final Side DEFAULT_SIDE = Side.LEFT;
    /** Quarter turns in a full circle: the unit {@link #yaw} wraps at. */
    public static final double FULL_TURN = 4.0;
    /** Yaw of a crane whose heading is unknown, and the value an absent saved yaw reads as: north. */
    public static final double DEFAULT_YAW = 0.0;

    public CranePose {
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch must be in " + RackPosition.FIRST_BRANCH + ".."
                    + RackPosition.MAX_BRANCH + ": " + branch);
        if (!Double.isFinite(x) || !Double.isFinite(y))
            throw new IllegalArgumentException("position must be finite: " + x + ", " + y);
        if (!(arm >= RETRACTED && arm <= EXTENDED))
            throw new IllegalArgumentException("arm must be within " + RETRACTED + ".." + EXTENDED + ": " + arm);
        Objects.requireNonNull(side, "side");
        if (!Double.isFinite(yaw))
            throw new IllegalArgumentException("yaw must be finite: " + yaw);
        yaw = normalizeYaw(yaw);
    }

    /** A pose on {@link RackPosition#FIRST_BRANCH} facing north — every pose of a warehouse that does not bend. */
    public CranePose(double x, double y, double arm, Side side) {
        this(RackPosition.FIRST_BRANCH, x, y, arm, side, DEFAULT_YAW);
    }

    /** A pose with a retracted arm. */
    public static CranePose at(double x, double y, Side side) {
        return new CranePose(x, y, RETRACTED, side);
    }

    /** A pose with a retracted arm on one branch of a network, facing the way that branch runs. */
    public static CranePose at(int branch, double x, double y, Side side, Heading heading) {
        return new CranePose(branch, x, y, RETRACTED, side, yawOf(heading));
    }

    /**
     * A pose from untrusted (saved or synced) values: non-finite coordinates become 0, the arm is clamped (NaN becomes
     * retracted) and a missing side becomes {@link #DEFAULT_SIDE}. Never throws.
     */
    public static CranePose sanitized(double x, double y, double arm, @Nullable Side side) {
        return sanitized(RackPosition.FIRST_BRANCH, x, y, arm, side, DEFAULT_YAW);
    }

    /**
     * A pose from untrusted (saved or synced) values, branch and yaw included: a branch outside the address format
     * becomes {@value RackPosition#FIRST_BRANCH}, a non-finite yaw becomes {@link #DEFAULT_YAW} and any other yaw is
     * wrapped into {@code [0, 4)}. Never throws, so a crane loaded from a broken save still stands somewhere legal.
     */
    public static CranePose sanitized(int branch, double x, double y, double arm, @Nullable Side side, double yaw) {
        double safeArm = Double.isNaN(arm) ? RETRACTED : Math.max(RETRACTED, Math.min(EXTENDED, arm));
        int safeBranch = branch >= RackPosition.FIRST_BRANCH && branch <= RackPosition.MAX_BRANCH ? branch
                : RackPosition.FIRST_BRANCH;
        return new CranePose(safeBranch, Double.isFinite(x) ? x : 0.0, Double.isFinite(y) ? y : 0.0, safeArm,
                side == null ? DEFAULT_SIDE : side, Double.isFinite(yaw) ? yaw : DEFAULT_YAW);
    }

    public CranePose withXY(double newX, double newY) {
        return new CranePose(branch, newX, newY, arm, side, yaw);
    }

    public CranePose withArm(double newArm) {
        return new CranePose(branch, x, y, newArm, side, yaw);
    }

    public CranePose withSide(Side newSide) {
        return new CranePose(branch, x, y, arm, newSide, yaw);
    }

    public CranePose withYaw(double newYaw) {
        return new CranePose(branch, x, y, arm, side, newYaw);
    }

    /** The same pose facing {@code heading}, i.e. with its turn finished. */
    public CranePose facing(Heading heading) {
        return withYaw(yawOf(heading));
    }

    /**
     * The hand-over at a link: the same world block under the next branch's name, at that branch's position for it.
     * Nothing about the machine moves — only what it is called — so the turn that follows starts where it stood.
     */
    public CranePose handedOver(int newBranch, double newX) {
        return new CranePose(newBranch, newX, y, arm, side, yaw);
    }

    /** Whether X and Y equal {@code other}'s exactly (targets are snapped, never approximately reached). */
    public boolean sameXY(CranePose other) {
        return x == other.x && y == other.y;
    }

    /** Whether branch, X and Y equal {@code other}'s exactly: the same block of the same aisle. */
    public boolean sameCell(CranePose other) {
        return branch == other.branch && sameXY(other);
    }

    /** Whether the machine faces exactly the same way as {@code other}. */
    public boolean sameYaw(CranePose other) {
        return yaw == other.yaw;
    }

    /** The heading the machine faces, or empty while a turn is in progress. */
    public Optional<Heading> heading() {
        double turns = Math.rint(yaw);
        if (yaw != turns)
            return Optional.empty();
        return Optional.of(Heading.fromQuarterTurns((int) turns));
    }

    /** Whether the machine faces a branch direction squarely, i.e. no turn is in progress. */
    public boolean isAligned() {
        return yaw == Math.rint(yaw);
    }

    /**
     * Linear interpolation for rendering between the previous and the current tick ({@code partialTicks} clamped to
     * 0..1). The side and the branch are the current ones; the yaw follows the <b>shortest arc</b>, so a turn past
     * north looks like a turn and not like three quarters back.
     * <p>
     * <b>A hand-over is not motion</b> (M21, ADR-033): when the two poses are named on different branches, the machine
     * was renamed at a link ({@link #handedOver}) and {@code x} counts along another line of blocks before and after,
     * so interpolating the two numbers would drag the machine up to a whole branch length through the warehouse in one
     * tick. Such a tick keeps the current {@code x} — the link block the rename happened on — and the renderer
     * interpolates the machine's real position between the two blocks instead ({@code WarehouseLayout#railOffset}),
     * where the two names can be compared.
     */
    public static CranePose lerp(CranePose previous, CranePose current, double partialTicks) {
        double t = Double.isNaN(partialTicks) ? 1.0 : Math.max(0.0, Math.min(1.0, partialTicks));
        double x = previous.branch == current.branch ? previous.x + (current.x - previous.x) * t : current.x;
        return new CranePose(current.branch, x, previous.y + (current.y - previous.y) * t,
                Math.max(RETRACTED, Math.min(EXTENDED, previous.arm + (current.arm - previous.arm) * t)), current.side,
                previous.yaw + yawDelta(previous.yaw, current.yaw) * t);
    }

    /** The yaw of a heading: quarter turns clockwise from north. */
    public static double yawOf(Heading heading) {
        return Objects.requireNonNull(heading, "heading").quarterTurns();
    }

    /** A yaw wrapped into {@code [0, 4)}; {@code 4} and {@code -0.0} become {@code 0}. */
    public static double normalizeYaw(double yaw) {
        double wrapped = yaw % FULL_TURN;
        if (wrapped < 0.0)
            wrapped += FULL_TURN;
        return wrapped == 0.0 ? 0.0 : wrapped;
    }

    /**
     * The shortest signed way from one yaw to another, in quarter turns: within {@code (-2, +2]}, so a half turn — the
     * only ambiguous case, and one no pair of perpendicular branches can produce — is resolved clockwise.
     */
    public static double yawDelta(double from, double to) {
        double delta = normalizeYaw(to - from);
        return delta > FULL_TURN / 2.0 ? delta - FULL_TURN : delta;
    }
}
