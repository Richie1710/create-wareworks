package dev.wareworks.core.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * The crane state machine as a pure transition function ({@code docs/stacker-crane.md} §4): {@code (state, event) →
 * (state, effects)}. The block entity feeds events and executes effects; the machine never touches the world.
 *
 * <pre>
 * IDLE → TRAVEL_TO_SOURCE → EXTEND_SOURCE → PICK → RETRACT_SOURCE → TRAVEL_TO_TARGET → EXTEND_TARGET → DROP
 *      → RETRACT_TARGET → COMPLETE → IDLE
 * RETRACT_SOURCE ── nothing picked ─────────────▶ IDLE (ReportAbort ZERO_PICK)
 * RETRACT_TARGET ── leftovers, output station of the job's request ──▶ WAITING_FOR_TARGET ──(retryTicks)──▶ EXTEND_TARGET
 *                └─ leftovers, any other target ────▶ REROUTE ──(target)──▶ TRAVEL_TO_TARGET
 *                                                            └─(none)───▶ HOLDING ──(holdRetryTicks)──▶ REROUTE
 * DROP ── container exchange ──▶ RETRACT_TARGET (the job carries the new key, so RETRACT_TARGET reroutes it)
 * </pre>
 * <b>Reroute exclusion.</b> The reroute request right after a target failed names that target, so the planner skips
 * it; a {@code HOLDING} retry names none, so a former target that accepts items again (emptied, or placed again at the
 * same position) is used. A job that serves no request never waits at an output station: its leftovers are rerouted
 * like those at any other target.
 *
 * <b>Ticks.</b> Every {@link CraneEvent.Tick} stores the previous pose. While paused (flag or all speeds 0) nothing
 * else changes: no motion, no timers, no effects. Motion phases step {@link CraneMotion} and end in the tick the target
 * pose is reached; the next phase starts moving in the following tick, but a phase whose target is already reached on
 * entry ends at once, so a whole job takes exactly {@code TravelTimeModel.tripTicks} ticks. {@code PICK} and
 * {@code DROP} last {@code transferTicks}; in the last tick they emit their perform effect, and the real transfer
 * result arrives as an event in the same tick. A perform effect is repeated each unpaused tick until its result
 * arrives, and never after (a picked job is never picked again).
 * <p>
 * <b>Interruptions.</b> Before the pick, {@link CraneEvent.SourceMissing}, {@link CraneEvent.TargetMissing} and
 * {@link CraneEvent.JobCancelled} abort the job (after retracting the arm, if extended). After the pick, target missing
 * and cancellation reroute the held items (after retracting); {@link CraneEvent.OutputFull} makes the crane retract and
 * wait at the output station of the job's request (a job without request reroutes instead). Events that do not apply
 * change nothing.
 * <p>
 * <b>Item conservation.</b> Held amounts change only through {@link CraneEvent.PickResult} and
 * {@link CraneEvent.DropResult}; a job is never dropped while it holds items. Results that contradict the state
 * (more picked than planned, delivered + leftover ≠ held) throw {@link IllegalArgumentException}, because accepting or
 * ignoring them would lose track of real items.
 * <p>
 * <b>The container exchange</b> (M30, issue #21, D1) is the one event that changes <i>what</i> is held rather than how
 * much: {@link CraneEvent.Exchanged} answers a {@link CraneEffect.PerformDrop} at a fluid bay, where the head gave up
 * its filled containers and received that many empty ones. The amount is unchanged by construction, and the machine
 * refuses any other amount, so the conservation rule above is not relaxed — it is restated for a second key. The job
 * continues under the new key ({@link TransportJob#exchangedFor}), which makes the <b>existing</b> ladder do the rest:
 * the stop's kind is {@code STORAGE} and so no delivery target, {@code RETRACT_TARGET} therefore enters
 * {@code REROUTE}, and entering {@code REROUTE} from anywhere but {@code HOLDING} names the current target as the one
 * to skip — which is the bay the exchange just happened at. No new phase, no new reroute rule and no new save name.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class CraneStateMachine<K, L> {
    /**
     * Result of one transition.
     *
     * @param state             the new state
     * @param effects           effects to execute in order (unmodifiable)
     * @param changed           whether the state changed or effects were produced; false for events that do not apply
     * @param quarterTurnsSwung quarter turns the motion of this transition really spent, as a magnitude and 0 for
     *                          every event that moves nothing (M25, issue #16). It is reported here rather than
     *                          derived from the two poses by the caller, because only {@link #move} holds the route a
     *                          tick was stepped with — and a tick's budget can cross several legs, where the shortest
     *                          arc between the end poses is not the swing that was spent
     *                          ({@link CraneMotion#quarterTurnsSwung}). Measured, never saved and never synced
     * @param <K>               item key type
     * @param <L>               location type
     */
    public record Transition<K, L>(CraneState<K, L> state, List<CraneEffect<K, L>> effects, boolean changed,
            double quarterTurnsSwung) {
        public Transition {
            Objects.requireNonNull(state, "state");
            effects = List.copyOf(effects);
            if (!Double.isFinite(quarterTurnsSwung) || quarterTurnsSwung < 0.0)
                throw new IllegalArgumentException("quarterTurnsSwung must be a finite magnitude: " + quarterTurnsSwung);
        }
    }

    private final Function<? super L, RackPosition> positions;
    private final CraneTimings timings;
    private final CraneNetwork network;

    /**
     * A machine on a warehouse of one straight aisle: exactly what every warehouse was before M21, and what the crane
     * falls back to while its dock has not discovered a network yet.
     *
     * @param positions maps a location to its aisle-local rack position (the crane's X, level and arm side there)
     * @param timings   transfer and retry durations
     */
    public CraneStateMachine(Function<? super L, RackPosition> positions, CraneTimings timings) {
        this(positions, timings, CraneNetwork.SINGLE_BRANCH);
    }

    /**
     * @param positions maps a location to its branch-local rack position (the crane's branch, X, level and arm side)
     * @param timings   transfer and retry durations
     * @param network   the rails the crane drives on, and what a turn on them costs
     */
    public CraneStateMachine(Function<? super L, RackPosition> positions, CraneTimings timings,
            CraneNetwork network) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.network = Objects.requireNonNull(network, "network");
    }

    public CraneTimings timings() {
        return timings;
    }

    /** The rails this machine plans its motion on. */
    public CraneNetwork network() {
        return network;
    }

    /**
     * Applies one event.
     *
     * @throws IllegalStateException    if {@code state} is not {@link CraneState#isConsistent() consistent} in a way
     *                                  that leaves no job to work with
     * @throws IllegalArgumentException if a result contradicts the state (see class documentation)
     */
    public Transition<K, L> apply(CraneState<K, L> state, CraneEvent<K, L> event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        List<CraneEffect<K, L>> effects = new ArrayList<>();
        // One cell, written only by move() and only for a tick: the swing the motion really spent, which the two end
        // poses cannot tell the caller on a route with more than one leg per tick (see Transition#quarterTurnsSwung).
        double[] swung = new double[1];
        CraneState<K, L> next = switch (event) {
            case CraneEvent.Tick<K, L> tick -> tick(state, tick.speeds(), effects, swung);
            case CraneEvent.JobAssigned<K, L> assigned -> assign(state, assigned.job(), effects);
            case CraneEvent.PickResult<K, L> result -> picked(state, result.picked(), effects);
            case CraneEvent.DropResult<K, L> result -> dropped(state, result.delivered(), result.leftover(), effects);
            case CraneEvent.Exchanged<K, L> result -> exchanged(state, result.newKey(), result.amount(), effects);
            case CraneEvent.RerouteResult<K, L> result -> rerouted(state, result, effects);
            case CraneEvent.OutputFull<K, L> ignored -> outputFull(state, effects);
            case CraneEvent.TargetMissing<K, L> ignored -> interrupt(state, CraneInterruption.TARGET_MISSING, effects);
            case CraneEvent.JobCancelled<K, L> ignored -> interrupt(state, CraneInterruption.CANCELLED, effects);
            case CraneEvent.SourceMissing<K, L> ignored -> sourceMissing(state, effects);
            case CraneEvent.Paused<K, L> ignored -> state.withPaused(true);
            case CraneEvent.Resumed<K, L> ignored -> state.withPaused(false);
        };
        return new Transition<>(next, effects, !next.equals(state) || !effects.isEmpty(), swung[0]);
    }

    /**
     * Makes a loaded (untrusted) state consistent and recomputes its target pose, reporting jobs it has to drop. Rules:
     * a job without items that cannot continue is aborted ({@link AbortReason#CANCELLED}); items are never dropped:
     * a job with held items in {@code IDLE} or {@code COMPLETE} goes to {@code HOLDING}, a picked job in a phase before
     * the pick retracts; a finished job completes; an awaited result or interruption in the wrong phase is cleared or
     * handled by retracting.
     */
    public Transition<K, L> resume(CraneState<K, L> state) {
        Objects.requireNonNull(state, "state");
        List<CraneEffect<K, L>> effects = new ArrayList<>();
        CraneState<K, L> next = sanitize(state, effects);
        // Sanitising a loaded state moves no machine: it repairs a phase and recomputes a target pose, and the window
        // must not credit a swing to a tick that never ran (the same rule CraneExecution#resuming applies to trips).
        return new Transition<>(next, effects, !next.equals(state) || !effects.isEmpty(), 0.0);
    }

    // --- events --------------------------------------------------------------------------------------------------

    private CraneState<K, L> tick(CraneState<K, L> state, CraneSpeeds speeds, List<CraneEffect<K, L>> effects,
            double[] swung) {
        CraneState<K, L> s = state.withPreviousPose(state.pose());
        if (s.paused() || speeds.isStopped())
            return s;
        return switch (s.phase()) {
            case IDLE -> move(s, speeds, swung);
            case COMPLETE -> enter(s, CranePhase.IDLE, effects);
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, RETRACT_SOURCE, TRAVEL_TO_TARGET, EXTEND_TARGET, RETRACT_TARGET -> {
                CraneState<K, L> moved = move(s, speeds, swung);
                yield CraneMotion.isAt(moved.pose(), moved.target()) ? motionComplete(moved, effects) : moved;
            }
            case PICK, DROP -> transferTick(s, effects);
            case REROUTE -> enter(s, CranePhase.HOLDING, effects); // the reroute request was not answered
            case HOLDING, WAITING_FOR_TARGET -> {
                CraneState<K, L> moved = move(s, speeds, swung);
                int left = moved.retryTicks() - 1;
                if (left > 0)
                    yield moved.withRetryTicks(left);
                yield enter(moved, s.phase() == CranePhase.HOLDING ? CranePhase.REROUTE : CranePhase.EXTEND_TARGET,
                        effects);
            }
        };
    }

    /**
     * One motion tick along the route the network answers right now. A crane that stays on its own branch needs no
     * route at all — {@link CraneMotion} then drives it straight at the target and turns it towards the target's own
     * yaw — which is why a warehouse of one aisle never asks the network a question.
     * <p>
     * This is also the only place that can say how far the machine really <b>swung</b> in the tick, because it is the
     * only place that holds the route, so it adds that to {@code swung} for {@link Transition#quarterTurnsSwung()} —
     * with the route it already asked for, never a second query.
     */
    private CraneState<K, L> move(CraneState<K, L> s, CraneSpeeds speeds, double[] swung) {
        CranePose pose = s.pose();
        CranePose target = s.target();
        CraneRoute route = pose.branch() == target.branch() ? null
                : network.route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
        CraneState<K, L> moved = CraneMotion.step(s, speeds, route, network.turnPenaltyBlocks());
        swung[0] += CraneMotion.quarterTurnsSwung(pose, moved.pose(), route);
        return moved;
    }

    private CraneState<K, L> transferTick(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        TransportJob<K, L> job = requireJob(s);
        boolean pick = s.phase() == CranePhase.PICK;
        if (pick ? job.picked() : job.heldAmount() == 0) // already transferred: never transfer twice
            return enter(s, pick ? CranePhase.RETRACT_SOURCE : CranePhase.RETRACT_TARGET, effects);
        CraneState<K, L> next = s;
        if (!s.awaitingResult()) {
            int elapsed = s.phaseTicks() + 1;
            if (elapsed < timings.transferTicks())
                return s.withPhaseTicks(elapsed);
            next = s.withPhaseTicks(elapsed).withAwaitingResult(true);
        }
        effects.add(pick ? new CraneEffect.PerformPick<>(job, job.source(), job.plannedAmount())
                : new CraneEffect.PerformDrop<>(job, job.target(), job.heldAmount()));
        return next;
    }

    private CraneState<K, L> assign(CraneState<K, L> s, TransportJob<K, L> job, List<CraneEffect<K, L>> effects) {
        if (!s.canAcceptJob() || job.picked())
            return s;
        return enter(s.withJob(job).withInterruption(Optional.empty()), CranePhase.TRAVEL_TO_SOURCE, effects);
    }

    private CraneState<K, L> picked(CraneState<K, L> s, int amount, List<CraneEffect<K, L>> effects) {
        if (s.phase() != CranePhase.PICK || !s.awaitingResult() || s.job().isEmpty() || s.job().get().picked())
            return s;
        TransportJob<K, L> job = s.job().get();
        if (amount > job.plannedAmount())
            throw new IllegalArgumentException("picked " + amount + " items, but only " + job.plannedAmount()
                    + " were requested from the source");
        TransportJob<K, L> updated = job.withPicked(amount);
        effects.add(new CraneEffect.ReportPicked<>(updated, amount));
        return enter(s.withJob(updated), CranePhase.RETRACT_SOURCE, effects);
    }

    private CraneState<K, L> dropped(CraneState<K, L> s, int delivered, int leftover,
            List<CraneEffect<K, L>> effects) {
        if (s.phase() != CranePhase.DROP || !s.awaitingResult() || s.job().isEmpty())
            return s;
        TransportJob<K, L> job = s.job().get();
        if ((long) delivered + leftover != job.heldAmount())
            throw new IllegalArgumentException("drop result " + delivered + " + " + leftover
                    + " does not match the held amount " + job.heldAmount());
        TransportJob<K, L> updated = job.plusDelivered(delivered);
        if (delivered > 0)
            effects.add(new CraneEffect.ReportDelivered<>(updated, updated.target(), delivered));
        return enter(s.withJob(updated), CranePhase.RETRACT_TARGET, effects);
    }

    /**
     * A container exchange at the job's target ({@link CraneEvent.Exchanged}): the carry continues under the new key
     * and the arm retracts, exactly as after a drop.
     * <p>
     * The amount must be the whole held amount, because the exchange is all or nothing at every level below this one
     * ({@code TransferContext#exchange}, {@code InventoryGrabber#exchange}): a partial one would leave two keys in the
     * head, and the second of them would be spilled at the dock by {@code CraneExecution.reconcileHeadWithJob}. A
     * result that says otherwise is a broken location or a broken head, and like every other contradicting result it
     * throws rather than being accepted or ignored — {@code CraneExecution.drain} catches that and answers by re-reading
     * the real head.
     */
    private CraneState<K, L> exchanged(CraneState<K, L> s, K newKey, int amount, List<CraneEffect<K, L>> effects) {
        if (s.phase() != CranePhase.DROP || !s.awaitingResult() || s.job().isEmpty())
            return s;
        TransportJob<K, L> job = s.job().get();
        if (amount != job.heldAmount())
            throw new IllegalArgumentException("exchange result " + amount + " does not match the held amount "
                    + job.heldAmount());
        TransportJob<K, L> updated = job.exchangedFor(newKey);
        effects.add(new CraneEffect.ReportExchanged<>(updated, job.key(), updated.target(), amount));
        return enter(s.withJob(updated), CranePhase.RETRACT_TARGET, effects);
    }

    private CraneState<K, L> rerouted(CraneState<K, L> s, CraneEvent.RerouteResult<K, L> result,
            List<CraneEffect<K, L>> effects) {
        if (s.phase() != CranePhase.REROUTE || s.job().isEmpty())
            return s;
        if (result.target().isEmpty())
            return enter(s, CranePhase.HOLDING, effects);
        TransportJob<K, L> updated = s.job().get().withTarget(result.target().get(), result.kind().get());
        effects.add(new CraneEffect.ReportRerouted<>(updated));
        return enter(s.withJob(updated), CranePhase.TRAVEL_TO_TARGET, effects);
    }

    private CraneState<K, L> outputFull(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        Optional<TransportJob<K, L>> job = s.job();
        if (job.isEmpty() || !job.get().targetKind().isDeliveryTarget() || job.get().heldAmount() == 0)
            return s;
        return switch (s.phase()) {
            case EXTEND_TARGET, DROP -> enter(withMergedInterruption(s, CraneInterruption.OUTPUT_FULL),
                    CranePhase.RETRACT_TARGET, effects);
            case RETRACT_TARGET -> withMergedInterruption(s, CraneInterruption.OUTPUT_FULL);
            case WAITING_FOR_TARGET -> s.withRetryTicks(timings.retryTicks());
            default -> s;
        };
    }

    /** Target missing or job cancelled. */
    private CraneState<K, L> interrupt(CraneState<K, L> s, CraneInterruption interruption,
            List<CraneEffect<K, L>> effects) {
        Optional<TransportJob<K, L>> job = s.job();
        if (job.isEmpty())
            return s;
        if (!job.get().picked())
            return interruptBeforePick(s, interruption, effects);
        if (job.get().heldAmount() == 0)
            return s; // nothing held: the job ends on its own
        return switch (s.phase()) {
            case RETRACT_SOURCE, RETRACT_TARGET -> withMergedInterruption(s, interruption);
            case EXTEND_TARGET, DROP -> enter(withMergedInterruption(s, interruption), CranePhase.RETRACT_TARGET,
                    effects);
            case TRAVEL_TO_TARGET, WAITING_FOR_TARGET -> s.pose().arm() > CranePose.RETRACTED
                    ? enter(withMergedInterruption(s, interruption), CranePhase.RETRACT_TARGET, effects)
                    : enter(s, CranePhase.REROUTE, effects);
            default -> s; // REROUTE and HOLDING already look for another target
        };
    }

    private CraneState<K, L> sourceMissing(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        if (s.job().isEmpty() || s.job().get().picked())
            return s; // after the pick the source no longer matters
        return interruptBeforePick(s, CraneInterruption.SOURCE_MISSING, effects);
    }

    private CraneState<K, L> interruptBeforePick(CraneState<K, L> s, CraneInterruption interruption,
            List<CraneEffect<K, L>> effects) {
        if (s.phase() == CranePhase.RETRACT_SOURCE)
            return withMergedInterruption(s, interruption);
        if (s.pose().arm() <= CranePose.RETRACTED)
            return abort(s, interruption.abortReason(), effects);
        return enter(s.withInterruption(Optional.of(interruption)), CranePhase.RETRACT_SOURCE, effects);
    }

    // --- phases --------------------------------------------------------------------------------------------------

    private CraneState<K, L> enter(CraneState<K, L> state, CranePhase phase, List<CraneEffect<K, L>> effects) {
        CranePhase from = state.phase();
        CraneState<K, L> s;
        if (phase == CranePhase.IDLE) {
            s = state.toIdle();
        } else {
            CraneState<K, L> base = state.withPhase(phase);
            s = base.withTarget(targetFor(base));
            if (phase == CranePhase.HOLDING)
                s = s.withRetryTicks(timings.holdRetryTicks());
            else if (phase == CranePhase.WAITING_FOR_TARGET)
                s = s.withRetryTicks(timings.retryTicks());
        }
        if (from != phase)
            effects.add(new CraneEffect.PhaseChanged<>(from, phase));
        if (phase == CranePhase.COMPLETE) {
            effects.add(new CraneEffect.ReportComplete<>(requireJob(s)));
        } else if (phase == CranePhase.REROUTE) {
            TransportJob<K, L> job = requireJob(s);
            s = s.withAwaitingResult(true);
            // Only the target that just failed is excluded; a hold retry may choose it again once it accepts items.
            Optional<L> failedTarget = from == CranePhase.HOLDING ? Optional.empty() : Optional.of(job.target());
            effects.add(new CraneEffect.RequestReroute<>(job, failedTarget, job.heldAmount()));
        }
        if (phase.isMotion() && CraneMotion.isAt(s.pose(), s.target()))
            return motionComplete(s, effects);
        return s;
    }

    private CraneState<K, L> motionComplete(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        return switch (s.phase()) {
            case TRAVEL_TO_SOURCE -> enter(s, CranePhase.EXTEND_SOURCE, effects);
            case EXTEND_SOURCE -> enter(s, CranePhase.PICK, effects);
            case RETRACT_SOURCE -> sourceRetracted(s, effects);
            case TRAVEL_TO_TARGET -> enter(s, CranePhase.EXTEND_TARGET, effects);
            case EXTEND_TARGET -> enter(s, CranePhase.DROP, effects);
            case RETRACT_TARGET -> targetRetracted(s, effects);
            default -> s;
        };
    }

    private CraneState<K, L> sourceRetracted(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        TransportJob<K, L> job = requireJob(s);
        Optional<CraneInterruption> interruption = s.interruption();
        if (!job.picked())
            return abort(s, interruption.map(CraneInterruption::abortReason).orElse(AbortReason.CANCELLED), effects);
        if (job.heldAmount() == 0)
            return abort(s, AbortReason.ZERO_PICK, effects);
        boolean reroute = interruption.filter(CraneInterruption::reroutesHeldItems).isPresent();
        return enter(s.withInterruption(Optional.empty()),
                reroute ? CranePhase.REROUTE : CranePhase.TRAVEL_TO_TARGET, effects);
    }

    private CraneState<K, L> targetRetracted(CraneState<K, L> s, List<CraneEffect<K, L>> effects) {
        TransportJob<K, L> job = requireJob(s);
        Optional<CraneInterruption> interruption = s.interruption();
        CraneState<K, L> cleared = s.withInterruption(Optional.empty());
        if (job.heldAmount() == 0)
            return enter(cleared, CranePhase.COMPLETE, effects);
        if (interruption.filter(CraneInterruption::reroutesHeldItems).isPresent())
            return enter(cleared, CranePhase.REROUTE, effects);
        // Only the station that asked for the items is waited for; leftovers nobody requested there go elsewhere. A
        // production station counts as much as an output does (LocationKind#isDeliveryTarget): its machine empties
        // the buffer by itself, so waiting is what serves the order that asked.
        if (job.targetKind().isDeliveryTarget() && job.requestId().isPresent())
            return enter(cleared, CranePhase.WAITING_FOR_TARGET, effects);
        return enter(cleared, CranePhase.REROUTE, effects);
    }

    private CraneState<K, L> abort(CraneState<K, L> s, AbortReason reason, List<CraneEffect<K, L>> effects) {
        effects.add(new CraneEffect.ReportAbort<>(requireJob(s), reason));
        return enter(s, CranePhase.IDLE, effects);
    }

    /** The pose a phase moves towards (or holds). */
    private CranePose targetFor(CraneState<K, L> s) {
        CranePose retractedHere = s.pose().withArm(CranePose.RETRACTED);
        if (s.phase() == CranePhase.IDLE)
            return retractedHere;
        TransportJob<K, L> job = requireJob(s);
        double here = s.pose().yaw();
        return switch (s.phase()) {
            case TRAVEL_TO_SOURCE -> poseAt(job.source(), CranePose.RETRACTED, here);
            case EXTEND_SOURCE, PICK -> poseAt(job.source(), CranePose.EXTENDED, here);
            case TRAVEL_TO_TARGET -> poseAt(job.target(), CranePose.RETRACTED, here);
            case EXTEND_TARGET, DROP -> poseAt(job.target(), CranePose.EXTENDED, here);
            case IDLE, RETRACT_SOURCE, RETRACT_TARGET, COMPLETE, REROUTE, HOLDING, WAITING_FOR_TARGET -> retractedHere;
        };
    }

    // --- resume --------------------------------------------------------------------------------------------------

    private CraneState<K, L> sanitize(CraneState<K, L> state, List<CraneEffect<K, L>> effects) {
        if (state.job().isEmpty())
            return state.toIdle();
        TransportJob<K, L> job = state.job().get();
        CraneState<K, L> s = state;
        if (s.awaitingResult() && s.phase() != CranePhase.PICK && s.phase() != CranePhase.DROP
                && s.phase() != CranePhase.REROUTE)
            s = s.withAwaitingResult(false);
        if (s.phase() != CranePhase.IDLE)
            s = s.withTarget(targetFor(s));
        return switch (s.phase()) {
            case IDLE, COMPLETE -> {
                CraneState<K, L> cleared = s.withInterruption(Optional.empty());
                if (!job.picked())
                    yield abort(cleared, AbortReason.CANCELLED, effects);
                if (job.heldAmount() > 0)
                    yield enter(cleared, CranePhase.HOLDING, effects);
                yield s.phase() == CranePhase.IDLE ? enter(cleared, CranePhase.COMPLETE, effects) : cleared;
            }
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, PICK -> {
                if (job.picked() || s.interruption().isPresent())
                    yield enter(s, CranePhase.RETRACT_SOURCE, effects);
                if (s.phase() == CranePhase.PICK && !CraneMotion.isAt(s.pose(), s.target()))
                    yield enter(s, CranePhase.EXTEND_SOURCE, effects);
                yield s;
            }
            case RETRACT_SOURCE -> s;
            case TRAVEL_TO_TARGET, EXTEND_TARGET, DROP, REROUTE, HOLDING, WAITING_FOR_TARGET, RETRACT_TARGET -> {
                if (!job.picked())
                    yield abort(s.withInterruption(Optional.empty()), AbortReason.CANCELLED, effects);
                if (s.phase() == CranePhase.RETRACT_TARGET)
                    yield s;
                if (job.heldAmount() == 0 || s.interruption().isPresent())
                    yield enter(s, CranePhase.RETRACT_TARGET, effects);
                if (s.phase() == CranePhase.DROP && !CraneMotion.isAt(s.pose(), s.target()))
                    yield enter(s, CranePhase.EXTEND_TARGET, effects);
                yield s;
            }
        };
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /**
     * The pose a location asks the machine to stand in: on that location's branch, at its position and level, facing
     * the way that branch runs. A network that does not know the branch — a warehouse of one aisle — leaves the yaw
     * where the crane already holds it, so nothing ever turns there.
     */
    private CranePose poseAt(L location, double arm, double fallbackYaw) {
        RackPosition position = Objects.requireNonNull(positions.apply(location), "no rack position for " + location);
        return new CranePose(position.branch(), position.x(), position.y(), arm, position.side(),
                network.restingYaw(position.branch(), fallbackYaw));
    }

    private static <K, L> CraneState<K, L> withMergedInterruption(CraneState<K, L> s, CraneInterruption incoming) {
        return s.withInterruption(Optional.of(s.interruption().map(current -> current.merge(incoming)).orElse(incoming)));
    }

    private static <K, L> TransportJob<K, L> requireJob(CraneState<K, L> s) {
        return s.job().orElseThrow(() -> new IllegalStateException("phase " + s.phase() + " without a job"));
    }
}
