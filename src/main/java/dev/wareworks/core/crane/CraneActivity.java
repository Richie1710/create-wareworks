package dev.wareworks.core.crane;

import java.util.Objects;

/**
 * What a stacker crane did in one server tick: the six buckets {@link ThroughputWindow} counts and
 * {@link CraneThroughput} reports.
 * <p>
 * The six are <b>mutually exclusive and exhaustive</b> — every observed tick falls into exactly one — so the counts of
 * a window always sum to its observed ticks and no two surfaces can disagree about what the machine was doing. That is
 * what {@link #of(boolean, boolean, CranePhase, CraneTickMotion)} buys with its fixed precedence, and it is the reason
 * the classification lives here as pure Java instead of being re-derived at each surface.
 * <p>
 * <b>Precedence, in this order:</b>
 * <ol>
 *   <li>{@link #PAUSED} — the world stopped the machine. The <i>reason</i> for the pause lives in the content layer and
 *       has its own goggle line; this model takes the boolean only.</li>
 *   <li>{@link #IDLE} — no job. This <b>includes the drive home</b>, because {@link HomeReturn} already counts a
 *       returning crane as waiting and the goggle status line already reads "Idle": counting the drive home as travel
 *       would make a warehouse that parks its crane look busier than one that does not.</li>
 *   <li>{@link #TURNING} — a tick that yawed. A tick that both turned and drove counts as turning, the same
 *       approximation {@link CraneSoundCues#motion} already makes when it suppresses the rail clack while the machine
 *       swings on the spot.</li>
 *   <li>{@link #TRAVELLING} — a tick that moved along the rails or lifted.</li>
 *   <li>{@link #AT_A_STOP} — standing at a source or target: extend, transfer, retract, and the completing tick. This
 *       is deliberately the planner's own definition of a stop
 *       ({@link dev.wareworks.core.job.TravelTimeModel#stopTicks}), so the measured share is comparable with what the
 *       planner predicted instead of being a second, private definition. An arm-only tick is not motion, which is why
 *       the extend and retract phases land here rather than in {@link #TRAVELLING}.</li>
 *   <li>{@link #BLOCKED} — a job, but nothing moved and no stop: waiting for a target, holding, rerouting, and a
 *       travel tick that covered no ground. The stranded case: a crane standing still in
 *       {@link CranePhase#TRAVEL_TO_TARGET} because a rail was broken is not paused, so without this bucket the
 *       machine would read as travelling 100 % of the time.</li>
 * </ol>
 */
public enum CraneActivity {
    /** Motion and timers are stopped from outside: no rotation, no speed, or a controller that pulled the handbrake. */
    PAUSED,
    /** No job — waiting for work, or driving home. */
    IDLE,
    /** Swinging a quarter turn at a corner of the rail network. */
    TURNING,
    /** Moving along the rails or lifting. */
    TRAVELLING,
    /** Standing at a source or target: extending, transferring, retracting, completing. */
    AT_A_STOP,
    /** A job in hand and nothing happening: no target, held leftovers, a reroute, or travel that covers no ground. */
    BLOCKED;

    /** Number of buckets; {@link #ordinal()} indexes {@link ThroughputWindow}'s counters. */
    public static final int COUNT = values().length;

    /** Whether the tick was work getting done: {@link #TURNING}, {@link #TRAVELLING} or {@link #AT_A_STOP}. */
    public boolean isWorking() {
        return this == TURNING || this == TRAVELLING || this == AT_A_STOP;
    }

    /**
     * The bucket of one server tick.
     *
     * @param paused  whether the crane was paused in the tick
     * @param hasJob  whether it had a job
     * @param phase   the phase it was in
     * @param motion  what its pose did in the tick ({@link CraneTickMotion#of})
     * @return the one bucket the tick belongs to, never {@code null}
     */
    public static CraneActivity of(boolean paused, boolean hasJob, CranePhase phase, CraneTickMotion motion) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(motion, "motion");
        if (paused)
            return PAUSED;
        if (!hasJob)
            return IDLE;
        if (motion.yawed())
            return TURNING;
        if (motion.moved())
            return TRAVELLING;
        // Exhaustive on purpose, with no default: a phase added later is a compile error here instead of a tick that
        // quietly counts as blocked.
        return switch (phase) {
            case EXTEND_SOURCE, PICK, RETRACT_SOURCE, EXTEND_TARGET, DROP, RETRACT_TARGET, COMPLETE -> AT_A_STOP;
            // A job in CranePhase.IDLE is not a state the machine produces (CraneState#isConsistent forbids it and
            // CraneStateMachine#resume sanitises it away); should one ever be loaded, it reads as stranded, not as work.
            case TRAVEL_TO_SOURCE, TRAVEL_TO_TARGET, WAITING_FOR_TARGET, HOLDING, REROUTE, IDLE -> BLOCKED;
        };
    }

    /** The bucket of one server tick, read off the crane's state. */
    public static CraneActivity of(CraneState<?, ?> state, CraneTickMotion motion) {
        Objects.requireNonNull(state, "state");
        return of(state.paused(), state.job().isPresent(), state.phase(), motion);
    }
}
