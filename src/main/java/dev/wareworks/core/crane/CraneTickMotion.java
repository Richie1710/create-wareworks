package dev.wareworks.core.crane;

import java.util.Objects;

/**
 * What the crane's pose did in one server tick, as {@link CraneActivity#of} and {@link ThroughputWindow} need it.
 * <p>
 * This is deliberately the same arithmetic {@link CraneSoundCues#motion} does on its own four local booleans, down to
 * the epsilon, and it is deliberately <b>not</b> extracted out of that method: the sound cues are reached through a
 * stateful class on the crane's hot path, pinned tick-for-tick by JUnit and GameTests and by the visual scenario's
 * sound census, and sharing a record through it would be a two-hop signature change for four lines of boolean
 * arithmetic. {@code CraneTickMotionTest} asserts on a table of pose pairs that the two readings agree, so the
 * duplication is held in place by a test instead of by a refactor.
 * <p>
 * <b>A hand-over is travel.</b> At a corner the machine is renamed onto the next branch, where {@code x} counts from
 * that branch's own end: the number jumps by a whole branch length without the machine moving. It drove up to the
 * corner block, so the tick counts as travel, but the two {@code x} values must never be measured against each other
 * — which is why {@link #of} reads {@code movedX} off the branch change alone in that tick.
 *
 * @param handedOver   whether the machine was renamed onto another branch in the tick (M21, ADR-033); implies
 *                     {@code movedX}
 * @param movedX       whether it moved along its branch, a hand-over included
 * @param movedY       whether it lifted
 * @param quarterTurns quarter turns the machine swung in the tick, as a <b>magnitude</b>: a finished corner sums to
 *                     exactly {@code 1} and a tick that did not turn is {@code 0}. A magnitude and not a signed
 *                     rotation, because nothing ever needed the direction and two corners taken in opposite
 *                     directions inside one tick would cancel out to "no turn at all" if it did — which is exactly
 *                     the reading {@link #of(CranePose, CranePose)} has to make when it is handed nothing but the two
 *                     poses, and which {@link #of(CranePose, CranePose, double)} exists to replace
 */
public record CraneTickMotion(boolean handedOver, boolean movedX, boolean movedY, double quarterTurns) {
    /**
     * Position and yaw changes at or below this are no motion. The motion snaps exactly, so this only absorbs
     * rounding — and it is the same value {@link CraneSoundCues} uses, which is what lets the two agree.
     */
    public static final double EPSILON = 1e-9;

    /** A tick in which nothing about the pose changed. */
    public static final CraneTickMotion NONE = new CraneTickMotion(false, false, false, 0.0);

    public CraneTickMotion {
        if (!Double.isFinite(quarterTurns))
            throw new IllegalArgumentException("quarterTurns must be finite: " + quarterTurns);
        if (quarterTurns < 0.0)
            throw new IllegalArgumentException("quarterTurns is a magnitude: " + quarterTurns);
    }

    /**
     * What happened between the pose before a tick and the pose after it, with the swing read as the <b>shortest
     * arc</b> between the two yaws — which is the reading {@link CraneSoundCues#motion} makes, and all two poses can
     * ever say.
     * <p>
     * Exact for every tick that stayed on one leg of its route, which is every tick at the default
     * {@code crane.turnPenaltyBlocks}: one quarter turn already spends a whole tick's travel budget there. A tick that
     * crossed more than one corner is the case {@link #of(CranePose, CranePose, double)} is for.
     */
    public static CraneTickMotion of(CranePose before, CranePose after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        return of(before, after, Math.abs(CranePose.yawDelta(before.yaw(), after.yaw())));
    }

    /**
     * The same, with the swing the motion step really spent — {@link CraneMotion#quarterTurnsSwung}, measured leg by
     * leg along the route the tick was stepped with.
     * <p>
     * This is what the server measures a crane with. The swing is never read as <i>less</i> than the shortest arc
     * between the two poses, so {@link #yawed()} can only ever be more willing to call a tick a turn, never less: a
     * tick whose corners cancelled out to a net zero is still a turning tick.
     *
     * @param quarterTurnsSpent quarter turns really swung, in either direction
     */
    public static CraneTickMotion of(CranePose before, CranePose after, double quarterTurnsSpent) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        boolean handedOver = before.branch() != after.branch();
        double swung = Math.max(Math.abs(quarterTurnsSpent),
                Math.abs(CranePose.yawDelta(before.yaw(), after.yaw())));
        return new CraneTickMotion(handedOver, handedOver || Math.abs(after.x() - before.x()) > EPSILON,
                Math.abs(after.y() - before.y()) > EPSILON, swung);
    }

    /** Whether the machine swung in the tick. */
    public boolean yawed() {
        return quarterTurns > EPSILON;
    }

    /**
     * Whether the machine moved at all. The arm is not part of this: extending and retracting happen while the machine
     * stands at a rack, which {@link CraneActivity#AT_A_STOP} counts, not {@link CraneActivity#TRAVELLING}.
     */
    public boolean moved() {
        return movedX || movedY || yawed();
    }
}
