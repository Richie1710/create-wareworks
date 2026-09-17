package dev.wareworks.core.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.job.CraneSpeeds;

/**
 * Decides when a stacker crane makes a sound ({@code docs/stacker-crane.md} §8). Pure Java: the dock block entity feeds
 * it the pose before and after every server tick, every phase change and every real transfer, and plays the returned
 * cues at the crane's world position.
 * <p>
 * <b>Rules</b> (short, Create-like, never spammy):
 * <ul>
 *   <li>Nothing while paused or standing still: a paused tick changes no state here, so a crane that resumes mid-travel
 *       continues silently.</li>
 *   <li>{@link Cue#TRAVEL_START} when X/Y travel begins, at most once per {@value #TRAVEL_START_MIN_INTERVAL_TICKS}
 *       ticks; {@link Cue#TRAVEL_STOP} in the tick travel reaches its target.</li>
 *   <li>While travelling, {@link Cue#RAIL_CLACK} when the base crosses a rail joint (between two aisle blocks), at most
 *       once per {@value #RAIL_CLACK_MIN_INTERVAL_TICKS} ticks; while lifting, {@link Cue#LIFT_CHAIN} every
 *       {@value #LIFT_CUE_SPACING} levels, at most once per {@value #LIFT_CHAIN_MIN_INTERVAL_TICKS} ticks. Their
 *       frequency follows the speed by construction, and their volume grows with the axis speed
 *       ({@link #motionVolume}).</li>
 *   <li>{@link Cue#ARM_EXTEND} / {@link Cue#ARM_RETRACT} when an extend or retract phase starts and the arm really has to
 *       move.</li>
 *   <li>{@link Cue#PICK} / {@link Cue#DROP} only for a transfer that moved at least one item.</li>
 * </ul>
 * One instance per crane, server thread only. The state is not saved: after loading, a travelling crane may play one
 * start cue.
 */
public final class CraneSoundCues {
    /** What a crane sound stands for. The content layer maps each cue to a sound event. */
    public enum Cue {
        TRAVEL_START,
        TRAVEL_STOP,
        RAIL_CLACK,
        LIFT_CHAIN,
        ARM_EXTEND,
        ARM_RETRACT,
        PICK,
        DROP
    }

    /**
     * One sound to play.
     *
     * @param cue    what the sound stands for
     * @param volume volume factor {@value #MIN_VOLUME}..{@value #FULL_VOLUME}, multiplied with the cue's base volume
     */
    public record Sound(Cue cue, double volume) {
        public Sound {
            Objects.requireNonNull(cue, "cue");
            if (!(volume >= MIN_VOLUME && volume <= FULL_VOLUME))
                throw new IllegalArgumentException("volume must be within " + MIN_VOLUME + ".." + FULL_VOLUME + ": " + volume);
        }
    }

    /** Minimum game ticks between two travel start cues (a crane that stops and starts again quickly does not repeat it). */
    public static final int TRAVEL_START_MIN_INTERVAL_TICKS = 20;
    /** Minimum game ticks between two rail clacks (fast cranes cross several joints per second). */
    public static final int RAIL_CLACK_MIN_INTERVAL_TICKS = 4;
    /** Minimum game ticks between two lift cues. */
    public static final int LIFT_CHAIN_MIN_INTERVAL_TICKS = 4;
    /** Levels between two lift cues. */
    public static final double LIFT_CUE_SPACING = 0.5;
    /** Pose x = n is the centre of aisle block n, so the joints between two rail blocks lie at n + 0.5. */
    public static final double RAIL_JOINT_OFFSET = 0.5;
    /** Rail joint spacing in aisle positions. */
    public static final double RAIL_JOINT_SPACING = 1.0;
    /** Axis speed in blocks per tick from which motion cues play at full volume (128 RPM travel at the default factor is 1/3). */
    public static final double FULL_VOLUME_SPEED = 0.25;
    /** Volume factor of motion cues at the lowest speed. */
    public static final double MIN_VOLUME = 0.3;
    /** Volume factor at or above {@link #FULL_VOLUME_SPEED}, and of every cue that does not depend on speed. */
    public static final double FULL_VOLUME = 1.0;

    /** Position and arm changes below this are no motion (the motion snaps exactly, so this only absorbs rounding). */
    private static final double EPSILON = 1e-9;
    /** "Never played": far enough in the past for every interval, without overflowing {@code now - NEVER}. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private boolean travelling;
    private long lastTravelStartTick = NEVER;
    private long lastRailClackTick = NEVER;
    private long lastLiftChainTick = NEVER;

    /**
     * The cues of one server tick in which the pose went from {@code before} to {@code after}.
     *
     * @param gameTime the tick's game time
     * @param target   the target pose after the tick (a travel that stops at its target plays {@link Cue#TRAVEL_STOP})
     * @param speeds   the axis speeds used in the tick (volume of the motion cues)
     * @param paused   whether the crane was paused in the tick: no cue, no state change
     * @return the cues in play order (unmodifiable, often empty)
     */
    public List<Sound> motion(long gameTime, CranePose before, CranePose after, CranePose target, CraneSpeeds speeds,
            boolean paused) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(speeds, "speeds");
        if (paused)
            return List.of();
        boolean movedX = Math.abs(after.x() - before.x()) > EPSILON;
        boolean movedY = Math.abs(after.y() - before.y()) > EPSILON;
        List<Sound> sounds = new ArrayList<>(2);
        if (movedX || movedY) {
            if (!travelling) {
                travelling = true;
                if (gameTime - lastTravelStartTick >= TRAVEL_START_MIN_INTERVAL_TICKS) {
                    lastTravelStartTick = gameTime;
                    sounds.add(new Sound(Cue.TRAVEL_START, motionVolume(Math.max(speeds.vx(), speeds.vy()))));
                }
            }
            if (after.sameXY(target)) {
                // Arrived in this tick: stop now, before the arm starts to extend in the next tick.
                travelling = false;
                sounds.add(new Sound(Cue.TRAVEL_STOP, FULL_VOLUME));
            }
        } else if (travelling) {
            // Standing still without a pause and not arrived last tick: the target moved onto the crane (e.g. a cancelled job).
            travelling = false;
            if (after.sameXY(target))
                sounds.add(new Sound(Cue.TRAVEL_STOP, FULL_VOLUME));
        }
        if (movedX && crosses(before.x() + RAIL_JOINT_OFFSET, after.x() + RAIL_JOINT_OFFSET, RAIL_JOINT_SPACING)
                && gameTime - lastRailClackTick >= RAIL_CLACK_MIN_INTERVAL_TICKS) {
            lastRailClackTick = gameTime;
            sounds.add(new Sound(Cue.RAIL_CLACK, motionVolume(speeds.vx())));
        }
        if (movedY && crosses(before.y(), after.y(), LIFT_CUE_SPACING)
                && gameTime - lastLiftChainTick >= LIFT_CHAIN_MIN_INTERVAL_TICKS) {
            lastLiftChainTick = gameTime;
            sounds.add(new Sound(Cue.LIFT_CHAIN, motionVolume(speeds.vy())));
        }
        return List.copyOf(sounds);
    }

    /**
     * The cue of a phase change: {@link Cue#ARM_EXTEND} when an extend phase starts and the arm is not at its target yet,
     * {@link Cue#ARM_RETRACT} likewise for a retract phase; nothing otherwise.
     *
     * @param pose   the pose when the phase starts
     * @param target the target pose of the new phase
     */
    public Optional<Sound> phaseChanged(CranePhase from, CranePhase to, CranePose pose, CranePose target) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        boolean extend = to == CranePhase.EXTEND_SOURCE || to == CranePhase.EXTEND_TARGET;
        boolean retract = to == CranePhase.RETRACT_SOURCE || to == CranePhase.RETRACT_TARGET;
        if (extend && target.arm() > pose.arm() + EPSILON)
            return Optional.of(new Sound(Cue.ARM_EXTEND, FULL_VOLUME));
        if (retract && pose.arm() > target.arm() + EPSILON)
            return Optional.of(new Sound(Cue.ARM_RETRACT, FULL_VOLUME));
        return Optional.empty();
    }

    /** {@link Cue#PICK} for a pick that moved {@code amount} items; nothing for a zero pick. */
    public static Optional<Sound> picked(int amount) {
        return amount > 0 ? Optional.of(new Sound(Cue.PICK, FULL_VOLUME)) : Optional.empty();
    }

    /** {@link Cue#DROP} for a drop that delivered {@code amount} items; nothing if the target accepted nothing. */
    public static Optional<Sound> dropped(int amount) {
        return amount > 0 ? Optional.of(new Sound(Cue.DROP, FULL_VOLUME)) : Optional.empty();
    }

    /**
     * Volume factor of a motion cue at {@code speed} blocks per tick: {@value #MIN_VOLUME} at standstill, rising linearly
     * to {@value #FULL_VOLUME} at {@value #FULL_VOLUME_SPEED} and above. NaN and negative speeds count as standstill.
     */
    public static double motionVolume(double speed) {
        if (!(speed > 0.0))
            return MIN_VOLUME;
        if (speed >= FULL_VOLUME_SPEED)
            return FULL_VOLUME;
        double volume = MIN_VOLUME + (FULL_VOLUME - MIN_VOLUME) * (speed / FULL_VOLUME_SPEED);
        return Math.max(MIN_VOLUME, Math.min(FULL_VOLUME, volume));
    }

    /** Whether moving from {@code from} to {@code to} crosses a multiple of {@code spacing} (in either direction). */
    private static boolean crosses(double from, double to, double spacing) {
        return Math.floor(from / spacing) != Math.floor(to / spacing);
    }
}
