package dev.wareworks.content.crane;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.head.HandlingHead;
import dev.wareworks.content.crane.head.TransferContext;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CraneEffect;
import dev.wareworks.core.crane.CraneEvent;
import dev.wareworks.core.crane.CraneNetwork;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.crane.CraneStateMachine;
import dev.wareworks.core.crane.CraneTimings;
import dev.wareworks.core.crane.HomeReturn;
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.RerouteTarget;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.RouteTable;
import dev.wareworks.util.LogThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Server-side execution of a stacker crane ({@code docs/stacker-crane.md} §4-6, {@code docs/warehouse-system.md} §8):
 * feeds {@link CraneStateMachine} with ticks and world observations, performs its effects on the world and reports to
 * the controller. Owned by one {@link StackerCraneBlockEntity}; server thread only.
 * <p>
 * <b>Per tick</b>: a crane with a job but without linked controller lets the controller behind the dock link it (at once
 * after loading, then every {@value #LOCATION_CHECK_INTERVAL_TICKS} ticks), so its reports are never dropped because it
 * ticked before that controller; resume a loaded state once; keep a resting crane on rails the warehouse really has
 * ({@link #keepRestingCraneOnTheRails}: inside a shrunken aisle, and back on the aisle at the dock when its own aisle
 * left the warehouse altogether); every
 * {@value #LOCATION_CHECK_INTERVAL_TICKS} ticks (and right after entering a travel phase or a geometry change) check that
 * the job's locations still exist (at most two lookups); derive the pause reason (no rotation, overstressed, a needed
 * chunk not loaded); apply the tick event and every event its effects produce in the same tick; publish changes.
 * <p>
 * <b>Effects.</b> {@code PerformPick} / {@code PerformDrop} resolve the location ({@link TransferContexts#resolve}): not
 * loaded → no answer (the machine repeats it every unpaused tick), missing → {@code SourceMissing} /
 * {@code TargetMissing}, available → the real transfer through the handling head, whose real result is the answer. A full
 * output station answers {@code OutputFull} without touching the inventory. {@code RequestReroute} asks the linked
 * controller ({@code planReroute}); without controller the answer is "none" (hold). Reports go to the linked controller
 * if there is one; the controller re-derives its reservations from the crane's job anyway.
 * <p>
 * <b>Requests.</b> A rerouted job no longer serves its request (the request's output is its destination), and a delivery
 * the controller no longer counts for an open request detaches it as well, so the controller never counts a request
 * twice.
 * <p>
 * <b>Head and job stay in step.</b> The job's held amount always equals the head's count of the job key. After loading
 * (or if a foreign inventory broke the contract), {@link #reconcileHeadWithJob} makes the job follow the real head, and
 * items of other keys are dropped at the dock, never deleted.
 */
final class CraneExecution {
    /** Interval of the job location checks while travelling or waiting. */
    static final int LOCATION_CHECK_INTERVAL_TICKS = 20;
    /** Interval of drift-correcting syncs while the crane moves ({@code docs/stacker-crane.md} §5). */
    static final int MOVING_SYNC_INTERVAL_TICKS = 20;
    /** Upper bound for state machine events processed in one call (a normal tick needs a handful). */
    static final int MAX_EVENTS_PER_CALL = 64;
    private static final long CHECK_NOW = Long.MIN_VALUE;

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * One warning per server run is enough for a configuration that stops every crane. Reset by
     * {@link #onServerStarting()}: this is a class static, and a single-player client's JVM outlives every integrated
     * server it starts, so without the reset a player who fixes the config and rejoins would never see the line again.
     */
    private static boolean zeroSpeedFactorLogged;

    private final StackerCraneBlockEntity crane;
    private final CraneSounds sounds = new CraneSounds();
    @Nullable
    private CraneStateMachine<ItemKey, RackPosition> machine;
    private CranePauseReason pauseReason = CranePauseReason.NONE;
    private boolean resumePending;
    private boolean syncRequested;
    /**
     * Ticks this crane has had nothing to do ({@link HomeReturn#countIdle}). Derived, never saved and never synced: a
     * crane that has just loaded starts waiting from zero, which is the harmless direction — it waits a little longer
     * before it goes home and never drives off in the tick a world opens.
     */
    private int idleTicks;
    private long nextLocationCheckTick = CHECK_NOW;
    private long nextMovingSyncTick = CHECK_NOW;
    private long nextControllerLookupTick = CHECK_NOW;
    /** Rate limit for contract violations: a second, different foreign inventory must still be reportable. */
    private final LogThrottle contractViolations = new LogThrottle();
    /** Rate limit for "the aisle under the crane left the warehouse", which a player fixes by putting one rail back. */
    private final LogThrottle lostAisles = new LogThrottle();

    CraneExecution(StackerCraneBlockEntity crane) {
        this.crane = Objects.requireNonNull(crane, "crane");
    }

    CranePauseReason pauseReason() {
        return pauseReason;
    }

    /** The crane's sounds (motion, arm, transfers; {@code docs/stacker-crane.md} §8). */
    CraneSounds sounds() {
        return sounds;
    }

    /** A saved state was read: make it consistent and resume it on the next tick. */
    void onLoaded() {
        resumePending = true;
        syncRequested = true;
        nextLocationCheckTick = CHECK_NOW;
        nextControllerLookupTick = CHECK_NOW;
    }

    void onGeometryChanged() {
        nextLocationCheckTick = CHECK_NOW;
        syncRequested = true;
    }

    void requestSync() {
        syncRequested = true;
    }

    /** Whether the state machine would accept a job (the block entity adds power and head checks). */
    boolean canAcceptJob() {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        return !resumePending && state.canAcceptJob() && !state.paused() && pauseReason == CranePauseReason.NONE;
    }

    boolean assign(Level level, TransportJob<ItemKey, RackPosition> job) {
        apply(level, CraneEvent.jobAssigned(job));
        boolean accepted = crane.currentJob().map(current -> current.id().equals(job.id())).orElse(false);
        if (accepted) {
            nextLocationCheckTick = CHECK_NOW;
            syncRequested = true;
            publishIfRequested(level);
        }
        return accepted;
    }

    boolean cancel(Level level, UUID jobId) {
        if (resumePending) {
            // A controller may cancel before this crane's first tick after loading: make the loaded state consistent.
            resumePending = false;
            resume(level);
        }
        if (crane.currentJob().map(job -> job.id().equals(jobId)).orElse(false)) {
            apply(level, CraneEvent.jobCancelled());
            syncRequested = true;
            publishIfRequested(level);
            return true;
        }
        return false;
    }

    // --- tick ----------------------------------------------------------------------------------------------------

    void tick(Level level) {
        long now = level.getGameTime();
        linkControllerIfNeeded(now);
        if (resumePending) {
            resumePending = false;
            resume(level);
        }
        CraneState<ItemKey, RackPosition> before = crane.craneState();
        keepRestingCraneOnTheRails(level);
        if (crane.craneState().job().isPresent() && now >= nextLocationCheckTick) {
            nextLocationCheckTick = now + LOCATION_CHECK_INTERVAL_TICKS;
            checkJobLocations(level);
        }

        // Read the speed factors once: the pause reason needs them again, and this runs for every dock every tick.
        CraneKinematics.Params kinematics = StackerCraneBlockEntity.kinematicParams();
        CraneSpeeds speeds = StackerCraneBlockEntity.speedsFor(kinematics, crane.getSpeed());
        CranePauseReason reason = pauseReasonFor(level, speeds, kinematics);
        if (reason != pauseReason) {
            pauseReason = reason;
            syncRequested = true;
        }
        boolean paused = reason != CranePauseReason.NONE;
        if (paused != crane.craneState().paused())
            apply(level, paused ? CraneEvent.paused() : CraneEvent.resumed());
        returnHomeIfIdle();
        apply(level, CraneEvent.tick(speeds));

        CraneState<ItemKey, RackPosition> after = crane.craneState();
        sounds.afterTick(level, crane, before.pose(), after, speeds);
        if (after.phase() != before.phase() || !after.target().equals(before.target())
                || !after.job().equals(before.job()))
            syncRequested = true;
        if (after.isMoving() && now >= nextMovingSyncTick)
            syncRequested = true;
        publishIfRequested(level);
    }

    /**
     * A crane that has had nothing to do for {@code crane.returnHomeIdleTicks} drives back to its home point — or, on a
     * warehouse without one, to its dock ({@link HomeReturn}, M21, ADR-034, {@code docs/stacker-crane.md} §4.7).
     * <p>
     * <b>The whole return is one line of state: the resting target of {@link CranePhase#IDLE}.</b> Nothing else is
     * added — no phase, no event, no job, no timer in the state machine — and that is what buys every property the
     * feature has to have:
     * <ul>
     * <li><b>Interruptible at any tick, mid-turn included.</b> A crane driving home is idle, so it accepts a job in the
     * tick that job is planned; entering {@code TRAVEL_TO_SOURCE} simply computes another target from the pose the
     * machine really has, wherever in a quarter turn that is. The return can therefore never delay work by a single
     * tick.</li>
     * <li><b>It holds no chunk.</b> A return is not a {@code TransportJob}, so {@code ChunkKeepDecision} still sees a
     * warehouse with nothing to do and keeps letting its chunks go ({@code chunkKeepWork}).</li>
     * <li><b>A warehouse of one straight aisle never moves at all</b> ({@link HomeReturn#enabled()}), which is what
     * every warehouse did before M21 and what a single-aisle GameTest pins.</li>
     * </ul>
     * A home point the machine cannot drive to is <b>reported, not obeyed</b>: the target goes back to where the crane
     * stands, so it waits there rather than pushing against rails that are not connected, and the home point's own red
     * lamp and goggle line say why. The pose is clamped to the rails the dock knows exactly as
     * {@link #keepRestingCraneOnTheRails} would clamp it, so the two never fight over the same target.
     */
    private void returnHomeIfIdle() {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        boolean waiting = pauseReason == CranePauseReason.NONE
                && HomeReturn.isWaiting(state.phase(), state.job().isPresent(), state.paused());
        idleTicks = HomeReturn.countIdle(idleTicks, waiting);
        if (!waiting)
            return;
        // The two cheapest questions first, because they are the ones that answer "nothing to do" for every warehouse
        // that does not return its crane: a config read and a counter, with nothing built and nothing allocated. A
        // warehouse of one straight aisle — every world built before M21 — leaves here on every tick.
        if (WareworksConfig.returnHomeIdleTicks() <= HomeReturn.OFF || crane.aisleCount() <= 1)
            return;
        HomeReturn rule = crane.homeReturn();
        if (!rule.returnsAfter(idleTicks))
            return;
        WarehouseLayout warehouse = crane.warehouse();
        NetworkGeometry network = warehouse.network();
        int homeBranch = rule.homeBranch();
        if (homeBranch >= network.branchCount())
            return; // the aisle it names is gone: the next geometry refresh gives this dock another home point
        CranePose home = rule.parkPose(network.branch(homeBranch).heading()).orElseThrow();
        BranchGeometry branch = network.branch(homeBranch);
        home = home.withXY(Mth.clamp(home.x(), 0.0, branch.length()),
                Mth.clamp(home.y(), 0.0, network.height() - 1.0));
        // A machine already named on the aisle it has to reach always can, so the route question — which derives the
        // network's corner blocks — is asked only for a trip that really crosses one ({@code RouteTable#canDrive} says
        // the same, but it would build the table first, on every tick of a crane that is simply parked).
        int from = state.pose().branch();
        boolean canDrive = from == home.branch()
                || warehouse.routes().canDrive(from, state.pose().x(), home.branch(), home.x());
        CranePose wanted = canDrive ? home : state.pose().withArm(CranePose.RETRACTED);
        if (state.target().equals(wanted))
            return; // already there, or already on the way
        crane.setCraneState(state.withTarget(wanted));
        syncRequested = true;
    }

    /**
     * A crane with a job and no linked controller asks the controller directly behind the dock to link it now. Without
     * this, a dock that ticks before its controller after loading (ticker order is arbitrary), or whose controller sits
     * in a chunk that is loaded but does not tick, would deliver without reporting: the request would keep its amount
     * and be served twice. One block entity lookup, at once after loading and then at most every
     * {@value #LOCATION_CHECK_INTERVAL_TICKS} ticks.
     */
    private void linkControllerIfNeeded(long now) {
        if (now < nextControllerLookupTick || crane.craneState().job().isEmpty())
            return;
        nextControllerLookupTick = now + LOCATION_CHECK_INTERVAL_TICKS;
        if (crane.linkedControllerEntity().isEmpty())
            crane.linkControllerBehind();
    }

    private void publishIfRequested(Level level) {
        if (!syncRequested)
            return;
        syncRequested = false;
        nextMovingSyncTick = level.getGameTime() + MOVING_SYNC_INTERVAL_TICKS;
        crane.publishState();
    }

    /**
     * The state machine, rebuilt whenever the timings <b>or the rails</b> change: it plans its motion on the network
     * the dock knows right now, so a crane follows a warehouse a player has just bent or straightened (ADR-033).
     */
    private CraneStateMachine<ItemKey, RackPosition> machine() {
        CraneTimings timings = new CraneTimings(Math.max(CraneTimings.MIN_TICKS, WareworksConfig.transferTicks()),
                Math.max(CraneTimings.MIN_TICKS, WareworksConfig.retryTicks()),
                Math.max(CraneTimings.MIN_TICKS, WareworksConfig.holdRetryTicks()));
        CraneNetwork network = crane.craneNetwork();
        CraneStateMachine<ItemKey, RackPosition> current = machine;
        if (current == null || !current.timings().equals(timings) || !current.network().equals(network)) {
            current = new CraneStateMachine<>(Function.identity(), timings, network);
            machine = current;
        }
        return current;
    }

    /** Resumes a loaded state: head and job in step first, then {@link CraneStateMachine#resume}. */
    private void resume(Level level) {
        reconcileHeadWithJob(level);
        CraneStateMachine<ItemKey, RackPosition> stateMachine = machine();
        CraneStateMachine.Transition<ItemKey, RackPosition> transition = stateMachine.resume(crane.craneState());
        crane.setCraneState(transition.state());
        Deque<CraneEvent<ItemKey, RackPosition>> events = new ArrayDeque<>();
        for (CraneEffect<ItemKey, RackPosition> effect : transition.effects())
            execute(level, effect, events);
        drain(level, stateMachine, events);
        syncRequested = true;
    }

    /** Applies {@code event} and every event its effects produce, in order. */
    void apply(Level level, CraneEvent<ItemKey, RackPosition> event) {
        Deque<CraneEvent<ItemKey, RackPosition>> events = new ArrayDeque<>();
        events.add(event);
        drain(level, machine(), events);
    }

    private void drain(Level level, CraneStateMachine<ItemKey, RackPosition> stateMachine,
            Deque<CraneEvent<ItemKey, RackPosition>> events) {
        for (int processed = 0; !events.isEmpty(); processed++) {
            if (processed >= MAX_EVENTS_PER_CALL) {
                LOGGER.error("Stacker crane at {} produced more than {} events in one tick; the rest waits for the next "
                        + "tick", crane.getBlockPos(), MAX_EVENTS_PER_CALL);
                return;
            }
            CraneEvent<ItemKey, RackPosition> event = events.poll();
            CraneStateMachine.Transition<ItemKey, RackPosition> transition;
            try {
                transition = stateMachine.apply(crane.craneState(), event);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A result contradicted the state (e.g. a foreign inventory broke its contract). The real head is the
                // truth: make the job follow it, and let the machine repair the rest on the next tick.
                if (contractViolations.tryLog(level.getGameTime()))
                    LOGGER.error("Stacker crane at {} rejected {}; re-reading its handling head", crane.getBlockPos(),
                            event, e);
                reconcileHeadWithJob(level);
                resumePending = true;
                return;
            }
            crane.setCraneState(transition.state());
            for (CraneEffect<ItemKey, RackPosition> effect : transition.effects())
                execute(level, effect, events);
        }
    }

    // --- effects -------------------------------------------------------------------------------------------------

    private void execute(Level level, CraneEffect<ItemKey, RackPosition> effect,
            Deque<CraneEvent<ItemKey, RackPosition>> events) {
        switch (effect) {
            case CraneEffect.PerformPick<ItemKey, RackPosition> pick -> performPick(level, pick, events);
            case CraneEffect.PerformDrop<ItemKey, RackPosition> drop -> performDrop(level, drop, events);
            case CraneEffect.RequestReroute<ItemKey, RackPosition> reroute -> events.add(reroute(reroute));
            case CraneEffect.ReportPicked<ItemKey, RackPosition> picked ->
                    crane.linkedControllerEntity().ifPresent(controller -> controller.onCranePicked(crane, picked.job()));
            case CraneEffect.ReportDelivered<ItemKey, RackPosition> delivered -> reportDelivered(delivered);
            case CraneEffect.ReportRerouted<ItemKey, RackPosition> rerouted -> reportRerouted(rerouted);
            case CraneEffect.ReportComplete<ItemKey, RackPosition> complete ->
                    crane.linkedControllerEntity().ifPresent(controller -> controller.onCraneJobFinished(crane,
                            complete.job()));
            case CraneEffect.ReportAbort<ItemKey, RackPosition> abort ->
                    crane.linkedControllerEntity().ifPresent(controller -> controller.onCraneJobAborted(crane,
                            abort.job(), abort.reason()));
            case CraneEffect.PhaseChanged<ItemKey, RackPosition> changed -> {
                syncRequested = true;
                if (changed.to() == CranePhase.TRAVEL_TO_SOURCE || changed.to() == CranePhase.TRAVEL_TO_TARGET)
                    nextLocationCheckTick = CHECK_NOW;
                // M19 (issue #10): the job is only really gone one tick after ReportComplete, when the state machine
                // falls back to IDLE — that is the moment the aisle may have nothing left to do, so its chunk hold is
                // re-decided then rather than at the next bounded re-check. It covers an aborted and a lost job too.
                if (changed.to() == CranePhase.IDLE)
                    crane.linkedControllerEntity().ifPresent(WarehouseControllerBlockEntity::markChunkKeepDirty);
                crane.onPhaseChanged(changed.from(), changed.to());
            }
        }
    }

    private void performPick(Level level, CraneEffect.PerformPick<ItemKey, RackPosition> pick,
            Deque<CraneEvent<ItemKey, RackPosition>> events) {
        TransportJob<ItemKey, RackPosition> job = pick.job();
        TransferContexts.Resolution source = TransferContexts.resolve(level, crane.warehouse(), pick.source(),
                job.sourceKind());
        switch (source.status()) {
            case UNLOADED -> {
                // No answer: the machine repeats the pick in the next unpaused tick.
            }
            case MISSING -> events.add(CraneEvent.sourceMissing());
            case AVAILABLE -> {
                int picked = crane.head().pick(source.context().orElseThrow(), job.key(), pick.amount());
                sounds.onPicked(level, crane, picked);
                events.add(CraneEvent.pickResult(picked));
            }
        }
    }

    private void performDrop(Level level, CraneEffect.PerformDrop<ItemKey, RackPosition> drop,
            Deque<CraneEvent<ItemKey, RackPosition>> events) {
        TransportJob<ItemKey, RackPosition> job = drop.job();
        TransferContexts.Resolution resolution = TransferContexts.resolve(level, crane.warehouse(), drop.target(),
                job.targetKind());
        switch (resolution.status()) {
            case UNLOADED -> {
                // No answer: the machine repeats the drop in the next unpaused tick.
            }
            case MISSING -> events.add(CraneEvent.targetMissing());
            case AVAILABLE -> {
                HandlingHead head = crane.head();
                if (head.count(job.key()) != drop.amount()) {
                    // Never report items that are not in the head: follow the head, retry next tick.
                    reconcileHeadWithJob(level);
                    return;
                }
                TransferContext target = resolution.context().orElseThrow();
                // Every station the crane delivers to on someone's behalf is checked, not only an output: a
                // production station that filled up between planning and the drop would otherwise be handed a full
                // carry, deliver nothing and send all of it back into storage, one wasted round trip per slot the
                // player's machine frees (LocationKind#isDeliveryTarget, M11 review fix).
                // A STORE job is excluded, however delivery-like its target is (M17, issue #12): waiting is right when
                // somebody is waiting for the items, and wrong for a store into an accepting port — the crane would park
                // in front of a full overflow port and block the whole aisle. Such a job takes the storage path instead:
                // it drops, delivers 0 and has its leftovers rerouted. The test is the positive JobType#waitsAtAFullTarget
                // rather than "not a store" (M18, issue #13): a COLLECT job never has a delivery target either.
                if (job.type().waitsAtAFullTarget() && job.targetKind().isDeliveryTarget()
                        && simulateInsert(target, job.key()) < 1) {
                    events.add(CraneEvent.outputFull());
                    return;
                }
                int delivered = head.drop(target, job.key(), drop.amount());
                sounds.onDropped(level, crane, delivered);
                events.add(CraneEvent.dropResult(delivered, drop.amount() - delivered));
            }
        }
    }

    private static int simulateInsert(TransferContext target, ItemKey key) {
        try {
            return target.simulateInsert(key, 1);
        } catch (RuntimeException e) {
            LOGGER.warn("Inventory at {} failed while simulating an insertion of {}", target.position(), key, e);
            return 0;
        }
    }

    private CraneEvent<ItemKey, RackPosition> reroute(CraneEffect.RequestReroute<ItemKey, RackPosition> request) {
        if (request.amount() < 1)
            return CraneEvent.noReroute();
        Optional<RerouteTarget<RackPosition>> target = crane.linkedControllerEntity()
                .flatMap(controller -> controller.planReroute(crane, request.job(),
                        request.failedTarget().orElse(null), request.amount()))
                .filter(candidate -> request.job().type().allowsTarget(candidate.kind()));
        if (target.isEmpty())
            return CraneEvent.noReroute();
        return CraneEvent.rerouteTo(target.get().location(), target.get().kind());
    }

    private void reportDelivered(CraneEffect.ReportDelivered<ItemKey, RackPosition> delivered) {
        boolean requestOpen = crane.linkedControllerEntity()
                .map(controller -> controller.onCraneDelivered(crane, delivered.job(), delivered.target(),
                        delivered.delivered()))
                .orElse(true);
        if (!requestOpen)
            detachRequest(delivered.job().id());
    }

    private void reportRerouted(CraneEffect.ReportRerouted<ItemKey, RackPosition> rerouted) {
        TransportJob<ItemKey, RackPosition> job = rerouted.job();
        if (job.requestId().isPresent())
            job = detachRequest(job.id()).orElse(job.withoutRequest());
        TransportJob<ItemKey, RackPosition> reported = job;
        crane.linkedControllerEntity().ifPresent(controller -> controller.onCraneRerouted(crane, reported));
        syncRequested = true;
    }

    /** The current job without its request, if it is job {@code jobId}. */
    private Optional<TransportJob<ItemKey, RackPosition>> detachRequest(UUID jobId) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        Optional<TransportJob<ItemKey, RackPosition>> job = state.job().filter(current -> current.id().equals(jobId));
        if (job.isEmpty())
            return Optional.empty();
        TransportJob<ItemKey, RackPosition> detached = job.get().withoutRequest();
        if (detached != job.get())
            crane.setCraneState(state.withJob(detached));
        return Optional.of(detached);
    }

    // --- observations --------------------------------------------------------------------------------------------

    /**
     * Before the pick the source and the target must exist, after it the target (M2 note (b)) — and since M21 each of
     * them must also still be somewhere the machine can <b>drive to</b> (ADR-033).
     * <p>
     * A rail broken behind the crane takes the route away, and a location with no route is reported {@code MISSING}
     * into the ladder that already exists: before the pick the job is aborted, after it the held items are rerouted,
     * and a crane that finds nowhere to put them waits in {@code HOLDING} with its items until a player puts the rail
     * back. Nothing is dropped and nothing is lost.
     * <p>
     * <b>The question is asked from the crane's own point</b>, not from its branch index ({@link RouteTable#canDrive},
     * M21 review fix). A branch that a broken rail made shorter than the crane's position still exists and is still
     * joined to its neighbours, so a branch-only question answers "reachable" while {@code CraneMotion} finds no route
     * and stands still: the machine froze in {@code TRAVEL_*} for ever, with nothing paused, nothing reported and the
     * warehouse dead because a busy crane takes no further job.
     */
    private void checkJobLocations(Level level) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        Optional<TransportJob<ItemKey, RackPosition>> current = state.job();
        if (current.isEmpty())
            return;
        TransportJob<ItemKey, RackPosition> job = current.get();
        WarehouseLayout warehouse = crane.warehouse();
        RouteTable routes = warehouse.routes();
        int from = state.pose().branch();
        double fromX = state.pose().x();
        switch (state.phase()) {
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, PICK -> {
                if (job.picked())
                    return;
                if (isMissing(level, warehouse, routes, from, fromX, job.source(), job.sourceKind()))
                    apply(level, CraneEvent.sourceMissing());
                else if (isMissing(level, warehouse, routes, from, fromX, job.target(), job.targetKind()))
                    apply(level, CraneEvent.targetMissing());
            }
            case TRAVEL_TO_TARGET, EXTEND_TARGET, WAITING_FOR_TARGET -> {
                if (job.heldAmount() > 0
                        && isMissing(level, warehouse, routes, from, fromX, job.target(), job.targetKind()))
                    apply(level, CraneEvent.targetMissing());
            }
            default -> {
                // Retracting, transferring at the target, rerouting or holding: nothing to validate.
            }
        }
    }

    /**
     * Whether a stop is gone, of the wrong kind, or on rails the machine standing at {@code (from, fromX)} can no
     * longer drive to.
     */
    private static boolean isMissing(Level level, WarehouseLayout warehouse, RouteTable routes, int from, double fromX,
            RackPosition rack, LocationKind kind) {
        if (!routes.canDrive(from, fromX, rack.branch(), rack.x()))
            return true;
        return TransferContexts.resolve(level, warehouse, rack, kind).status() == TransferContexts.Status.MISSING;
    }

    /**
     * Logs once that the server config stops every stacker crane. Without this a zero speed factor looks exactly like a
     * missing shaft: goggles say "paused" and nothing ever moves, with no hint in any log.
     */
    private static void warnAboutZeroSpeedFactorOnce(CraneKinematics.Params kinematics) {
        if (zeroSpeedFactorLogged)
            return;
        zeroSpeedFactorLogged = true;
        LOGGER.warn("Stacker cranes cannot move: the server config sets {} to 0 (wareworks-server.toml). Every crane "
                + "stays paused until a positive value is configured.", kinematics.zeroFactorNames());
    }

    /** A starting server may warn about a zero speed factor again ({@link #zeroSpeedFactorLogged}). */
    static void onServerStarting() {
        zeroSpeedFactorLogged = false;
    }

    /**
     * A zero speed factor in the config, no rotation, overstressed, or the aisle column under the crane or the current
     * stop is not loaded. This observes the world; {@link CranePauseDecision} holds the order in which the reasons win
     * (pure, JUnit). The chunk check is skipped for a stopped crane, which has a reason already.
     */
    private CranePauseReason pauseReasonFor(Level level, CraneSpeeds speeds, CraneKinematics.Params kinematics) {
        boolean stopped = speeds.isStopped();
        CranePauseReason reason = CranePauseDecision.reasonFor(stopped, kinematics.hasZeroFactor(),
                stopped && crane.isOverStressed(), stopped || stopChunksLoaded(level));
        if (reason == CranePauseReason.SPEED_FACTOR_ZERO)
            warnAboutZeroSpeedFactorOnce(kinematics);
        return reason;
    }

    /**
     * Whether the aisle column under the crane and the location of its current stop are loaded — and, for a stop whose
     * items live in the block <b>behind</b> the rack position, that block too. True without a job or in a phase with no
     * stop: there is nothing the crane would touch.
     * <p>
     * Which stops those are is derived from the job, not from the location kind alone (M18 review): a storage location's
     * inventory is one such block, and so is the machine a {@link JobType#COLLECT} job fetches out of — the same geometry
     * one block away from the aisle, but reached through a port, whose kind is {@link LocationKind#OUTPUT}. Missing it
     * left the crane in {@code PICK} for ever with {@code pauseReason} {@code NONE} whenever the machine's chunk unloaded
     * mid-job, because {@code performPick} deliberately answers nothing while the source is unloaded.
     */
    private boolean stopChunksLoaded(Level level) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        Optional<TransportJob<ItemKey, RackPosition>> job = state.job();
        if (job.isEmpty())
            return true;
        WarehouseLayout warehouse = crane.warehouse();
        // The column under the crane is on the aisle the crane is named on, which is not the dock's own once it has
        // turned a corner (ADR-033).
        int branch = Math.min(state.pose().branch(), warehouse.branchCount() - 1);
        int column = Math.max(0, (int) Math.round(state.pose().x()));
        if (!level.isLoaded(warehouse.aislePos(branch, Math.min(column, warehouse.branch(branch).geometry().length()))))
            return false;
        RackPosition stop;
        boolean reachesBehindRack;
        switch (state.phase()) {
            case TRAVEL_TO_SOURCE, EXTEND_SOURCE, PICK, RETRACT_SOURCE -> {
                stop = job.get().source();
                reachesBehindRack = job.get().sourceKind() == LocationKind.STORAGE
                        || job.get().type() == JobType.COLLECT;
            }
            case TRAVEL_TO_TARGET, EXTEND_TARGET, DROP, RETRACT_TARGET, WAITING_FOR_TARGET -> {
                stop = job.get().target();
                // A collect job's targets are storage and input only (JobType), so no target ever reaches through a port.
                reachesBehindRack = job.get().targetKind() == LocationKind.STORAGE;
            }
            default -> {
                return true;
            }
        }
        BlockPos pos = warehouse.rackPos(stop);
        return level.isLoaded(pos)
                && (!reachesBehindRack || level.isLoaded(pos.relative(warehouse.sideDirection(stop))));
    }

    /**
     * A crane that rests (idle, holding, rerouting) outside a shrunken aisle moves back into it
     * ({@code docs/stacker-crane.md} §3: the crane clamps its targets), and one whose aisle left the warehouse
     * altogether is put back onto the aisle at the dock ({@link #recoverLostAisle}). Job targets outside the aisle are
     * reported as missing by the location check instead.
     */
    private void keepRestingCraneOnTheRails(Level level) {
        CranePhase phase = crane.craneState().phase();
        if (phase != CranePhase.IDLE && phase != CranePhase.HOLDING && phase != CranePhase.REROUTE)
            return;
        NetworkGeometry network = crane.networkGeometry();
        recoverLostAisle(level, network);
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose target = state.target();
        // The aisle the crane rests on, not the dock's own: clamping a crane parked round a corner against the first
        // aisle's length would drive it somewhere it never was (ADR-033).
        if (target.branch() >= network.branchCount())
            return; // still not on this warehouse (no dock aisle to fall back to): the location check reports it
        BranchGeometry branch = network.branch(target.branch());
        double x = Mth.clamp(target.x(), 0.0, branch.length());
        double y = Mth.clamp(target.y(), 0.0, network.height() - 1.0);
        if (x != target.x() || y != target.y())
            crane.setCraneState(state.withTarget(target.withXY(x, y)));
    }

    /**
     * A resting crane whose aisle the warehouse no longer has goes back onto the aisle at the dock
     * ({@code WarehouseLayout#parkedAtDock}, M21 review fix, ADR-033).
     * <p>
     * <b>Why it has to happen at all.</b> Nothing else in the mod ever writes a pose's branch, so a crane parked round
     * a corner whose rail a player breaks keeps naming an aisle that is gone — and from there every route is empty and
     * every job is planned, taken and aborted again a few ticks later, for ever, with no line anywhere saying why. The
     * machine is <b>already drawn</b> on the aisle at the dock (a label of a branch that is gone names no line of
     * blocks, so {@code WarehouseLayout#railOffset} falls back), so this moves nothing a player can see; it only makes
     * the crane's state agree with the picture, and gives it rails it can drive on again.
     * <p>
     * A crane that holds items is recovered too, and deliberately: it then plans a reroute it can really drive and
     * puts the items away, instead of standing in {@code HOLDING} with them until the rail comes back.
     */
    private void recoverLostAisle(Level level, NetworkGeometry network) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        CranePose target = state.target();
        boolean poseLost = pose.branch() >= network.branchCount();
        boolean targetLost = target.branch() >= network.branchCount();
        if (!poseLost && !targetLost)
            return;
        // A warehouse always has the aisle at its dock, even when that aisle has no rails at all, so there is always
        // somewhere legal to put the machine. Only a label that really names nothing is rewritten: a crane standing
        // perfectly well on an aisle it still has must not be moved because its target vanished.
        WarehouseLayout warehouse = crane.warehouse();
        CraneState<ItemKey, RackPosition> next = state;
        if (poseLost) {
            CranePose parked = warehouse.parkedAtDock(pose);
            next = next.withPoses(parked, parked);
            if (lostAisles.tryLog(level.getGameTime()))
                LOGGER.info("Stacker crane at {}: its aisle {} is no longer part of the warehouse, so the machine is "
                        + "back on the aisle at the dock at position {}", crane.getBlockPos(), pose.branch(),
                        parked.x());
        }
        if (targetLost)
            next = next.withTarget(warehouse.parkedAtDock(target));
        crane.setCraneState(next);
        syncRequested = true;
    }

    /**
     * Makes the job follow the real handling head: items of other keys are dropped at the dock (never deleted), and a
     * job whose held amount differs from the head's count of its key is rewritten as picked with exactly that amount
     * held (the planned amount grows if needed). Used after loading untrusted data and after a contract violation.
     */
    private void reconcileHeadWithJob(Level level) {
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        HandlingHead head = crane.head();
        Optional<ItemKey> jobKey = state.job().map(TransportJob::key);
        long stray = head.spill(level, crane.getBlockPos(), key -> jobKey.map(jobItem -> !jobItem.equals(key)).orElse(true));
        if (stray > 0)
            LOGGER.warn("Stacker crane at {} held {} items that belong to no job; dropped them at the dock",
                    crane.getBlockPos(), stray);
        if (state.job().isEmpty())
            return;
        TransportJob<ItemKey, RackPosition> job = state.job().get();
        int held = head.count(job.key());
        if (held == job.heldAmount())
            return;
        int delivered = job.picked() ? job.deliveredAmount() : 0;
        int picked = (int) Math.min(Integer.MAX_VALUE, (long) delivered + held);
        TransportJob<ItemKey, RackPosition> adjusted = new TransportJob<>(job.id(), job.type(), job.source(),
                job.target(), job.targetKind(), job.key(), Math.max(job.plannedAmount(), picked), job.requestId(), true,
                picked, delivered);
        LOGGER.warn("Stacker crane at {}: job {} expected {} held items but the head holds {}; following the head",
                crane.getBlockPos(), job.id(), job.heldAmount(), held);
        crane.setCraneState(state.withJob(adjusted));
        syncRequested = true;
    }
}
