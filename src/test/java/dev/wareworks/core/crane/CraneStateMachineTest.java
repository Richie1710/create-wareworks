package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.warehouse.LocationKind;

class CraneStateMachineTest {
    private static final String ORE = "ore";
    private static final int TRANSFER = 3;
    private static final int RETRY = 4;
    private static final int HOLD = 5;
    private static final int MAX_TICKS = 10_000;
    private static final CraneTimings TIMINGS = new CraneTimings(TRANSFER, RETRY, HOLD);
    /** X two ticks per block, Y four ticks per level, arm two ticks. */
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.5, 0.25, 0.5);
    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = RackPosition.of(0, 1, Side.RIGHT);
    private static final RackPosition STORAGE_A = RackPosition.of(3, 1, Side.LEFT);
    private static final RackPosition STORAGE_B = RackPosition.of(5, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION = RackPosition.of(1, 1, Side.RIGHT);
    private static final UUID JOB = new UUID(0L, 1L);
    private static final UUID OTHER_JOB = new UUID(0L, 2L);
    private static final UUID REQUEST = new UUID(0L, 3L);
    private static final CraneStateMachine<String, RackPosition> MACHINE = new CraneStateMachine<>(Function.identity(),
            TIMINGS);

    /** Plays the block entity: answers perform effects immediately and records everything. */
    private static final class Harness {
        CraneState<String, RackPosition> state;
        final List<CraneEffect<String, RackPosition>> effects = new ArrayList<>();
        final List<Integer> effectTicks = new ArrayList<>();
        final List<CraneState<String, RackPosition>> history = new ArrayList<>();
        IntUnaryOperator picker = IntUnaryOperator.identity();
        IntUnaryOperator dropper = IntUnaryOperator.identity();
        Function<CraneEffect.RequestReroute<String, RackPosition>, CraneEvent<String, RackPosition>> rerouter =
                request -> CraneEvent.noReroute();
        boolean answer = true;
        int ticks;

        Harness(CranePose start) {
            state = CraneState.idle(start);
        }

        Harness() {
            this(CranePose.at(0, 0, Side.LEFT));
        }

        CraneStateMachine.Transition<String, RackPosition> send(CraneEvent<String, RackPosition> event) {
            CraneStateMachine.Transition<String, RackPosition> transition = MACHINE.apply(state, event);
            state = transition.state();
            history.add(state);
            assertTrue(state.isConsistent(), () -> "inconsistent after " + event + ": " + state);
            assertMotionRules(state);
            for (CraneEffect<String, RackPosition> effect : transition.effects()) {
                effects.add(effect);
                effectTicks.add(ticks);
                if (answer)
                    answer(effect);
            }
            return transition;
        }

        private void answer(CraneEffect<String, RackPosition> effect) {
            switch (effect) {
                case CraneEffect.PerformPick<String, RackPosition> pick ->
                        send(CraneEvent.pickResult(picker.applyAsInt(pick.amount())));
                case CraneEffect.PerformDrop<String, RackPosition> drop -> {
                    int delivered = dropper.applyAsInt(drop.amount());
                    send(CraneEvent.dropResult(delivered, drop.amount() - delivered));
                }
                case CraneEffect.RequestReroute<String, RackPosition> reroute -> {
                    CraneEvent<String, RackPosition> result = rerouter.apply(reroute);
                    if (result != null)
                        send(result);
                }
                default -> {
                }
            }
        }

        void tick() {
            ticks++;
            send(CraneEvent.tick(SPEEDS));
        }

        int tickUntil(BooleanSupplier condition) {
            int count = 0;
            while (!condition.getAsBoolean()) {
                if (count++ > MAX_TICKS)
                    fail("condition not reached, state " + state);
                tick();
            }
            return count;
        }

        int tickUntilPhase(CranePhase phase) {
            return tickUntil(() -> state.phase() == phase);
        }

        <T> List<T> effects(Class<T> type) {
            return effects.stream().filter(type::isInstance).map(type::cast).toList();
        }

        long count(Class<?> type) {
            return effects.stream().filter(type::isInstance).count();
        }

        List<CranePhase> phases() {
            List<CranePhase> phases = new ArrayList<>();
            for (CraneEffect<String, RackPosition> effect : effects) {
                if (effect instanceof CraneEffect.PhaseChanged<String, RackPosition> changed)
                    phases.add(changed.to());
            }
            return phases;
        }

        int tickOfPhase(CranePhase phase, int occurrence) {
            int seen = 0;
            for (int i = 0; i < effects.size(); i++) {
                if (effects.get(i) instanceof CraneEffect.PhaseChanged<String, RackPosition> changed
                        && changed.to() == phase && seen++ == occurrence)
                    return effectTicks.get(i);
            }
            throw new AssertionError("phase " + phase + " #" + occurrence + " not entered");
        }
    }

    /** Arm-before-move and extend-only-at-target, checked after every transition. */
    private static void assertMotionRules(CraneState<String, RackPosition> state) {
        CranePose pose = state.pose();
        CranePose previous = state.previousPose();
        if (!pose.sameXY(previous))
            assertTrue(pose.arm() == 0 && previous.arm() == 0, "moved with the arm out: " + previous + " → " + pose);
        if (pose.arm() > previous.arm())
            assertTrue(state.job().isPresent() && (atXY(pose, state.job().get().source())
                    || atXY(pose, state.job().get().target())), "extended away from the job's stops: " + state);
    }

    private static boolean atXY(CranePose pose, RackPosition position) {
        return pose.x() == position.x() && pose.y() == position.y();
    }

    private static TransportJob<String, RackPosition> storeJob() {
        return TransportJob.store(JOB, INPUT, STORAGE_A, ORE, 16);
    }

    private static TransportJob<String, RackPosition> retrieveJob() {
        return TransportJob.retrieve(JOB, STORAGE_A, OUTPUT, ORE, 16, REQUEST);
    }

    private static long trip(double craneX, double craneY, RackPosition source, RackPosition target) {
        return TravelTimeModel.tripTicks(SPEEDS, TRANSFER, craneX, craneY, source.x(), source.y(), target.x(),
                target.y());
    }

    // --- happy paths ---------------------------------------------------------------------------------------------

    @Test
    void storeJobRunsThroughEveryPhaseInTripTicks() {
        Harness h = new Harness();
        assertTrue(h.send(CraneEvent.jobAssigned(storeJob())).changed());
        assertEquals(CranePhase.EXTEND_SOURCE, h.state.phase(), "already at the source: travel ends at once");
        assertEquals(trip(0, 0, INPUT, STORAGE_A), h.tickUntilPhase(CranePhase.COMPLETE));
        assertEquals(List.of(CranePhase.TRAVEL_TO_SOURCE, CranePhase.EXTEND_SOURCE, CranePhase.PICK,
                CranePhase.RETRACT_SOURCE, CranePhase.TRAVEL_TO_TARGET, CranePhase.EXTEND_TARGET, CranePhase.DROP,
                CranePhase.RETRACT_TARGET, CranePhase.COMPLETE), h.phases());

        TransportJob<String, RackPosition> picked = storeJob().withPicked(16);
        TransportJob<String, RackPosition> done = picked.plusDelivered(16);
        assertEquals(List.of(new CraneEffect.PerformPick<>(storeJob(), INPUT, 16)),
                h.effects(CraneEffect.PerformPick.class));
        assertEquals(List.of(new CraneEffect.ReportPicked<>(picked, 16)), h.effects(CraneEffect.ReportPicked.class));
        assertEquals(List.of(new CraneEffect.PerformDrop<>(picked, STORAGE_A, 16)),
                h.effects(CraneEffect.PerformDrop.class));
        assertEquals(List.of(new CraneEffect.ReportDelivered<>(done, STORAGE_A, 16)),
                h.effects(CraneEffect.ReportDelivered.class));
        assertEquals(List.of(new CraneEffect.ReportComplete<>(done)), h.effects(CraneEffect.ReportComplete.class));
        assertEquals(0, h.state.heldAmount());

        h.tick();
        assertEquals(CraneState.idle(h.state.pose()).withPoses(h.state.previousPose(), h.state.pose()), h.state);
        assertTrue(h.state.canAcceptJob());
    }

    @Test
    void retrieveJobTravelsFromWhereverTheCraneIs() {
        Harness h = new Harness(CranePose.at(2, 0, Side.LEFT));
        h.send(CraneEvent.jobAssigned(retrieveJob()));
        assertEquals(CranePhase.TRAVEL_TO_SOURCE, h.state.phase());
        assertEquals(trip(2, 0, STORAGE_A, OUTPUT), h.tickUntilPhase(CranePhase.COMPLETE));
        CraneEffect.ReportDelivered<?, ?> delivered = h.effects(CraneEffect.ReportDelivered.class).get(0);
        assertEquals(OUTPUT, delivered.target());
        assertEquals(Optional.of(REQUEST), delivered.job().requestId());
        assertEquals(new CranePose(0, 1, 0, Side.RIGHT), h.state.pose());
    }

    @Test
    void transferHappensOnceAtTheEndOfPick() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.PICK);
        int entered = h.ticks;
        h.tickUntil(() -> h.count(CraneEffect.PerformPick.class) > 0);
        assertEquals(TRANSFER, h.ticks - entered);
        assertTrue(h.history.stream().filter(s -> s.phase() == CranePhase.PICK)
                .allMatch(s -> s.pose().arm() == CranePose.EXTENDED), "the arm stays out during the transfer");
    }

    @Test
    void completeAcceptsTheNextJobDirectly() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);
        TransportJob<String, RackPosition> next = TransportJob.store(OTHER_JOB, INPUT, STORAGE_B, ORE, 4);
        assertTrue(h.send(CraneEvent.jobAssigned(next)).changed());
        assertEquals(Optional.of(next), h.state.job());
        assertEquals(CranePhase.TRAVEL_TO_SOURCE, h.state.phase());
    }

    // --- partial and failed transfers ----------------------------------------------------------------------------

    @Test
    void partialPickDeliversWhatWasPicked() {
        Harness h = new Harness();
        h.picker = amount -> 10;
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(10, h.effects(CraneEffect.ReportPicked.class).get(0).picked());
        assertEquals(10, h.effects(CraneEffect.PerformDrop.class).get(0).amount());
        assertEquals(10, h.effects(CraneEffect.ReportDelivered.class).get(0).delivered());
        assertEquals(10, h.state.job().orElseThrow().pickedAmount());
    }

    @Test
    void zeroPickAbortsAfterRetracting() {
        Harness h = new Harness();
        h.picker = amount -> 0;
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.IDLE);
        assertEquals(List.of(new CraneEffect.ReportAbort<>(storeJob().withPicked(0), AbortReason.ZERO_PICK)),
                h.effects(CraneEffect.ReportAbort.class));
        int picked = h.effectTicks.get(h.effects.indexOf(h.effects(CraneEffect.ReportPicked.class).get(0)));
        int aborted = h.effectTicks.get(h.effects.indexOf(h.effects(CraneEffect.ReportAbort.class).get(0)));
        assertEquals(TravelTimeModel.armTicks(SPEEDS), aborted - picked, "the arm retracts before the abort");
        assertEquals(0, h.count(CraneEffect.PerformDrop.class));
        assertEquals(CranePose.RETRACTED, h.state.pose().arm());
        assertEquals(Optional.empty(), h.state.job());
    }

    @Test
    void partialDropReroutesHoldsAndRetries() {
        Harness h = new Harness();
        int[] drops = {0};
        h.dropper = amount -> drops[0]++ == 0 ? 6 : amount;
        int[] reroutes = {0};
        h.rerouter = request -> reroutes[0]++ == 0 ? CraneEvent.noReroute()
                : CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);

        List<CraneEffect.RequestReroute> requests = h.effects(CraneEffect.RequestReroute.class);
        assertEquals(2, requests.size());
        assertEquals(Optional.of(STORAGE_A), requests.get(0).failedTarget());
        assertEquals(10, requests.get(0).amount());
        assertEquals(Optional.empty(), requests.get(1).failedTarget(), "a hold retry excludes nothing");
        assertEquals(HOLD, h.tickOfPhase(CranePhase.REROUTE, 1) - h.tickOfPhase(CranePhase.HOLDING, 0));
        List<CraneEffect.ReportDelivered> delivered = h.effects(CraneEffect.ReportDelivered.class);
        assertEquals(List.of(STORAGE_A, STORAGE_B), delivered.stream().map(CraneEffect.ReportDelivered::target).toList());
        assertEquals(List.of(6, 10), delivered.stream().map(CraneEffect.ReportDelivered::delivered).toList());
        CraneEffect.ReportRerouted<?, ?> rerouted = h.effects(CraneEffect.ReportRerouted.class).get(0);
        assertEquals(STORAGE_B, rerouted.job().target());
        assertEquals(16, h.state.job().orElseThrow().deliveredAmount());
        assertEquals(List.of(CranePhase.RETRACT_TARGET, CranePhase.REROUTE, CranePhase.HOLDING, CranePhase.REROUTE,
                CranePhase.TRAVEL_TO_TARGET), h.phases().subList(7, 12));
    }

    @Test
    void holdingRetriesTheRerouteEveryHoldInterval() {
        Harness h = new Harness();
        h.dropper = amount -> 0;
        List<Integer> requestTicks = new ArrayList<>();
        h.rerouter = request -> {
            requestTicks.add(h.ticks);
            return CraneEvent.noReroute();
        };
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntil(() -> requestTicks.size() >= 4);
        for (int i = 1; i < requestTicks.size(); i++)
            assertEquals(HOLD, requestTicks.get(i) - requestTicks.get(i - 1));
        assertEquals(16, h.state.heldAmount(), "held items are kept");
    }

    /**
     * Review fix: only the reroute right after the failed drop excludes the target. A hold retry may send the items back
     * to it once it has room again (emptied by a player, or placed again at the same position).
     */
    @Test
    void aHoldRetryMayReturnToTheFailedTarget() {
        Harness h = new Harness();
        int[] drops = {0};
        h.dropper = amount -> drops[0]++ == 0 ? 6 : amount;
        List<Optional<RackPosition>> excluded = new ArrayList<>();
        h.rerouter = request -> {
            excluded.add(request.failedTarget());
            // Nothing else accepts the items; the former target does once it is not excluded any more.
            return request.failedTarget().isPresent() ? CraneEvent.noReroute()
                    : CraneEvent.rerouteTo(request.job().target(), LocationKind.STORAGE);
        };
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(List.of(Optional.of(STORAGE_A), Optional.empty()), excluded);
        assertEquals(List.of(STORAGE_A, STORAGE_A), h.effects(CraneEffect.ReportDelivered.class).stream()
                .map(CraneEffect.ReportDelivered::target).toList());
        assertEquals(16, h.state.job().orElseThrow().deliveredAmount());
    }

    /** Review fix: leftovers no request waits for are rerouted from a full output, never waited with forever. */
    @Test
    void leftoversWithoutRequestAreReroutedFromAFullOutput() {
        TransportJob<String, RackPosition> unrequested = TransportJob.retrieve(JOB, STORAGE_A, OUTPUT, ORE, 16, null);
        Harness partial = new Harness();
        int[] drops = {0};
        partial.dropper = amount -> drops[0]++ == 0 ? 4 : amount;
        partial.rerouter = request -> CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        partial.send(CraneEvent.jobAssigned(unrequested));
        partial.tickUntilPhase(CranePhase.COMPLETE);
        assertFalse(partial.phases().contains(CranePhase.WAITING_FOR_TARGET));
        List<CraneEffect.RequestReroute> requests = partial.effects(CraneEffect.RequestReroute.class);
        assertEquals(1, requests.size());
        assertEquals(Optional.of(OUTPUT), requests.get(0).failedTarget());
        assertEquals(12, requests.get(0).amount());
        assertEquals(List.of(OUTPUT, STORAGE_B), partial.effects(CraneEffect.ReportDelivered.class).stream()
                .map(CraneEffect.ReportDelivered::target).toList());

        Harness full = new Harness();
        full.rerouter = request -> CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        full.send(CraneEvent.jobAssigned(unrequested));
        full.tickUntil(() -> full.state.phase() == CranePhase.EXTEND_TARGET && full.state.pose().arm() > 0);
        full.send(CraneEvent.outputFull());
        full.tickUntilPhase(CranePhase.COMPLETE);
        assertFalse(full.phases().contains(CranePhase.WAITING_FOR_TARGET));
        assertEquals(List.of(STORAGE_B), full.effects(CraneEffect.ReportDelivered.class).stream()
                .map(CraneEffect.ReportDelivered::target).toList());
    }

    @Test
    void anUnansweredRerouteHolds() {
        Harness h = new Harness();
        h.dropper = amount -> 0;
        h.rerouter = request -> null;
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntilPhase(CranePhase.REROUTE);
        assertTrue(h.state.awaitingResult());
        h.tick();
        assertEquals(CranePhase.HOLDING, h.state.phase());
        assertEquals(HOLD, h.state.retryTicks());
    }

    @Test
    void aFullOutputMakesTheCraneWaitAndRetry() {
        Harness h = new Harness();
        int[] attempts = {0};
        h.dropper = amount -> ++attempts[0] < 3 ? 0 : amount;
        h.send(CraneEvent.jobAssigned(retrieveJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(0, h.count(CraneEffect.RequestReroute.class), "outputs are waited for, never rerouted");
        assertEquals(3, h.count(CraneEffect.PerformDrop.class));
        assertEquals(1, h.count(CraneEffect.ReportDelivered.class));
        assertEquals(RETRY, h.tickOfPhase(CranePhase.EXTEND_TARGET, 1) - h.tickOfPhase(CranePhase.WAITING_FOR_TARGET, 0));
        assertEquals(RETRY, h.tickOfPhase(CranePhase.EXTEND_TARGET, 2) - h.tickOfPhase(CranePhase.WAITING_FOR_TARGET, 1));
    }

    @Test
    void outputFullEventsRetractAndRestartTheWait() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(retrieveJob()));
        h.tickUntil(() -> h.state.phase() == CranePhase.EXTEND_TARGET && h.state.pose().arm() > 0);
        assertTrue(h.send(CraneEvent.outputFull()).changed());
        assertEquals(CranePhase.RETRACT_TARGET, h.state.phase());
        assertEquals(Optional.of(CraneInterruption.OUTPUT_FULL), h.state.interruption());
        h.tickUntilPhase(CranePhase.WAITING_FOR_TARGET);
        h.tick();
        h.tick();
        assertEquals(RETRY - 2, h.state.retryTicks());
        h.send(CraneEvent.outputFull());
        assertEquals(RETRY, h.state.retryTicks());
        assertEquals(0, h.count(CraneEffect.PerformDrop.class));

        Harness store = new Harness();
        store.send(CraneEvent.jobAssigned(storeJob()));
        store.tickUntilPhase(CranePhase.EXTEND_TARGET);
        assertFalse(store.send(CraneEvent.outputFull()).changed(), "storage targets are rerouted, not waited for");
    }

    /**
     * A production station is waited for exactly like an output (M11 review fix, {@code LocationKind#isDeliveryTarget}):
     * its machine empties the buffer by itself, so a station that is full right now is a "come back in a moment", not
     * a reason to carry the ingredients back into storage. Treating only an output as waitable made a supply job that
     * met a full station deliver nothing and reroute a whole carry — one wasted round trip per slot the player's
     * machine freed.
     */
    @Test
    void aFullProductionStationIsWaitedForLikeAnOutput() {
        Harness h = new Harness();
        int[] attempts = {0};
        h.dropper = amount -> ++attempts[0] < 2 ? 0 : amount;
        h.send(CraneEvent.jobAssigned(supplyJob()));
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(0, h.count(CraneEffect.RequestReroute.class),
                "a production station is waited for, never rerouted around");
        assertTrue(h.phases().contains(CranePhase.WAITING_FOR_TARGET));
        assertEquals(2, h.count(CraneEffect.PerformDrop.class), "it tried again after the wait");
        assertEquals(1, h.count(CraneEffect.ReportDelivered.class));

        // And the event a full station raises retracts the arm and restarts the wait, as it does at an output.
        Harness full = new Harness();
        full.send(CraneEvent.jobAssigned(supplyJob()));
        full.tickUntil(() -> full.state.phase() == CranePhase.EXTEND_TARGET && full.state.pose().arm() > 0);
        assertTrue(full.send(CraneEvent.outputFull()).changed(), "a full production station is answered");
        assertEquals(CranePhase.RETRACT_TARGET, full.state.phase());
        assertEquals(Optional.of(CraneInterruption.OUTPUT_FULL), full.state.interruption());
    }

    /** One ingredient of a production order on its way to the station that will consume it. */
    private static TransportJob<String, RackPosition> supplyJob() {
        return TransportJob.supply(JOB, STORAGE_A, PRODUCTION, ORE, 16, REQUEST);
    }

    // --- pause ---------------------------------------------------------------------------------------------------

    @Test
    void pauseFreezesMotionAndTimers() {
        Harness h = new Harness();
        TransportJob<String, RackPosition> job = TransportJob.store(JOB, INPUT, STORAGE_B, ORE, 8);
        h.send(CraneEvent.jobAssigned(job));
        h.tickUntil(() -> h.state.phase() == CranePhase.TRAVEL_TO_TARGET && h.state.pose().x() > 1);
        h.send(CraneEvent.paused());
        CranePose frozen = h.state.pose();
        int effectsBefore = h.effects.size();
        for (int i = 0; i < 10; i++) {
            h.tick();
            assertEquals(frozen, h.state.pose());
            assertEquals(frozen, h.state.previousPose(), "no interpolation jitter while paused");
        }
        for (int i = 0; i < 5; i++)
            h.send(CraneEvent.tick(CraneSpeeds.STOPPED));
        assertEquals(effectsBefore, h.effects.size());
        h.send(CraneEvent.resumed());
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(trip(0, 0, INPUT, STORAGE_B), h.ticks - 10, "paused ticks do not count");

        Harness picking = new Harness();
        picking.send(CraneEvent.jobAssigned(storeJob()));
        picking.tickUntil(() -> picking.state.phase() == CranePhase.PICK && picking.state.phaseTicks() == 1);
        picking.send(CraneEvent.paused());
        for (int i = 0; i < 2 * TRANSFER; i++)
            picking.tick();
        assertEquals(1, picking.state.phaseTicks());
        assertEquals(0, picking.count(CraneEffect.PerformPick.class));
        assertFalse(picking.send(CraneEvent.paused()).changed());
        picking.send(CraneEvent.resumed());
        picking.tickUntilPhase(CranePhase.RETRACT_SOURCE);
        assertEquals(1, picking.count(CraneEffect.PerformPick.class));
    }

    // --- interruptions -------------------------------------------------------------------------------------------

    @Test
    void targetMissingBeforeThePickAborts() {
        Harness travelling = new Harness();
        travelling.send(CraneEvent.jobAssigned(TransportJob.retrieve(JOB, STORAGE_B, OUTPUT, ORE, 4, REQUEST)));
        travelling.tick();
        travelling.tick();
        travelling.send(CraneEvent.targetMissing());
        assertEquals(CranePhase.IDLE, travelling.state.phase(), "the arm is in: abort at once");
        assertEquals(AbortReason.TARGET_MISSING, travelling.effects(CraneEffect.ReportAbort.class).get(0).reason());

        Harness picking = new Harness();
        picking.send(CraneEvent.jobAssigned(storeJob()));
        picking.tickUntilPhase(CranePhase.PICK);
        picking.send(CraneEvent.targetMissing());
        assertEquals(CranePhase.RETRACT_SOURCE, picking.state.phase());
        assertEquals(0, picking.count(CraneEffect.ReportAbort.class), "the arm retracts first");
        assertEquals(TravelTimeModel.armTicks(SPEEDS), picking.tickUntilPhase(CranePhase.IDLE));
        assertEquals(0, picking.count(CraneEffect.PerformPick.class));
        assertEquals(AbortReason.TARGET_MISSING, picking.effects(CraneEffect.ReportAbort.class).get(0).reason());
    }

    @Test
    void sourceMissingMattersOnlyBeforeThePick() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tick();
        assertEquals(CranePhase.EXTEND_SOURCE, h.state.phase());
        h.send(CraneEvent.sourceMissing());
        h.tickUntilPhase(CranePhase.IDLE);
        assertEquals(AbortReason.SOURCE_MISSING, h.effects(CraneEffect.ReportAbort.class).get(0).reason());

        Harness picked = new Harness();
        picked.send(CraneEvent.jobAssigned(storeJob()));
        picked.tickUntilPhase(CranePhase.TRAVEL_TO_TARGET);
        assertFalse(picked.send(CraneEvent.sourceMissing()).changed());
    }

    /** M2 note: retrieve jobs whose request or output disappears can be cancelled; reservations are then released. */
    @Test
    void cancellationAbortsBeforeThePickAndReroutesAfterIt() {
        Harness before = new Harness();
        before.send(CraneEvent.jobAssigned(retrieveJob()));
        before.tickUntil(() -> before.state.phase() == CranePhase.EXTEND_SOURCE && before.state.pose().arm() > 0);
        before.send(CraneEvent.jobCancelled());
        before.tickUntilPhase(CranePhase.IDLE);
        assertEquals(List.of(new CraneEffect.ReportAbort<>(retrieveJob(), AbortReason.CANCELLED)),
                before.effects(CraneEffect.ReportAbort.class));
        assertEquals(0, before.count(CraneEffect.PerformPick.class));

        Harness after = new Harness();
        after.rerouter = request -> CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        after.send(CraneEvent.jobAssigned(retrieveJob()));
        after.tickUntil(() -> after.state.phase() == CranePhase.TRAVEL_TO_TARGET
                && after.state.pose().x() < STORAGE_A.x());
        after.send(CraneEvent.jobCancelled());
        assertEquals(1, after.count(CraneEffect.RequestReroute.class), "the arm is in: reroute at once");
        assertEquals(CranePhase.TRAVEL_TO_TARGET, after.state.phase());
        assertEquals(STORAGE_B, after.state.job().orElseThrow().target());
        assertEquals(LocationKind.STORAGE, after.state.job().get().targetKind());
        after.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(STORAGE_B, after.effects(CraneEffect.ReportDelivered.class).get(0).target());
        assertEquals(0, after.count(CraneEffect.ReportAbort.class), "held items are never abandoned");
    }

    @Test
    void targetMissingDuringTheDropRetractsAndReroutes() {
        Harness h = new Harness();
        h.rerouter = request -> CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        h.send(CraneEvent.jobAssigned(retrieveJob()));
        h.tickUntilPhase(CranePhase.DROP);
        h.answer = false;
        h.tickUntil(() -> h.count(CraneEffect.PerformDrop.class) == 1);
        h.answer = true;
        h.send(CraneEvent.targetMissing());
        assertEquals(CranePhase.RETRACT_TARGET, h.state.phase());
        assertEquals(Optional.of(CraneInterruption.TARGET_MISSING), h.state.interruption());
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(2, h.count(CraneEffect.PerformDrop.class));
        assertEquals(List.of(STORAGE_B), h.effects(CraneEffect.ReportDelivered.class).stream()
                .map(CraneEffect.ReportDelivered::target).toList());
    }

    // --- robustness ----------------------------------------------------------------------------------------------

    @Test
    void performEffectsRepeatUntilAnsweredAndNeverAfter() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.answer = false;
        h.tickUntil(() -> h.count(CraneEffect.PerformPick.class) == 1);
        h.tick();
        h.tick();
        h.tick();
        assertEquals(4, h.count(CraneEffect.PerformPick.class), "repeated while the chunk is not loaded");
        assertEquals(TRANSFER, h.state.phaseTicks());
        h.answer = true;
        h.tick();
        assertEquals(CranePhase.RETRACT_SOURCE, h.state.phase());
        h.tickUntilPhase(CranePhase.COMPLETE);
        assertEquals(5, h.count(CraneEffect.PerformPick.class));
        assertEquals(1, h.count(CraneEffect.ReportPicked.class));
    }

    @Test
    void eventsThatDoNotApplyChangeNothing() {
        CraneState<String, RackPosition> idle = CraneState.idle(CranePose.at(0, 0, Side.LEFT));
        for (CraneEvent<String, RackPosition> event : List.<CraneEvent<String, RackPosition>>of(
                CraneEvent.pickResult(3), CraneEvent.dropResult(0, 0), CraneEvent.rerouteTo(STORAGE_A,
                        LocationKind.STORAGE), CraneEvent.noReroute(), CraneEvent.outputFull(),
                CraneEvent.targetMissing(), CraneEvent.sourceMissing(), CraneEvent.jobCancelled(),
                CraneEvent.resumed(), CraneEvent.jobAssigned(storeJob().withPicked(3)))) {
            assertFalse(MACHINE.apply(idle, event).changed(), event::toString);
        }

        Harness busy = new Harness();
        busy.send(CraneEvent.jobAssigned(storeJob()));
        busy.tickUntil(() -> busy.state.phase() == CranePhase.TRAVEL_TO_TARGET && busy.state.pose().x() > 0);
        for (CraneEvent<String, RackPosition> event : List.<CraneEvent<String, RackPosition>>of(
                CraneEvent.jobAssigned(TransportJob.store(OTHER_JOB, INPUT, STORAGE_B, ORE, 1)),
                CraneEvent.pickResult(1), CraneEvent.dropResult(16, 0), CraneEvent.noReroute())) {
            assertFalse(MACHINE.apply(busy.state, event).changed(), event::toString);
        }

        Harness holding = new Harness();
        holding.dropper = amount -> 0;
        holding.send(CraneEvent.jobAssigned(storeJob()));
        holding.tickUntilPhase(CranePhase.HOLDING);
        for (CraneEvent<String, RackPosition> event : List.<CraneEvent<String, RackPosition>>of(
                CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE), CraneEvent.targetMissing(),
                CraneEvent.jobCancelled(), CraneEvent.sourceMissing())) {
            assertFalse(MACHINE.apply(holding.state, event).changed(), event::toString);
        }
    }

    @Test
    void resultsThatContradictTheStateAreRejectedLoudly() {
        Harness h = new Harness();
        h.answer = false;
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntil(() -> h.count(CraneEffect.PerformPick.class) == 1);
        assertThrows(IllegalArgumentException.class, () -> MACHINE.apply(h.state, CraneEvent.pickResult(17)));
        h.send(CraneEvent.pickResult(16));
        h.tickUntil(() -> h.count(CraneEffect.PerformDrop.class) == 1);
        assertThrows(IllegalArgumentException.class, () -> MACHINE.apply(h.state, CraneEvent.dropResult(16, 1)));
        assertThrows(IllegalArgumentException.class, () -> MACHINE.apply(h.state, CraneEvent.dropResult(10, 5)));
        h.send(CraneEvent.dropResult(0, 16));
        h.tickUntilPhase(CranePhase.REROUTE);
        assertThrows(IllegalArgumentException.class,
                () -> MACHINE.apply(h.state, CraneEvent.rerouteTo(OUTPUT, LocationKind.OUTPUT)),
                "store leftovers cannot go to an output station");
        assertThrows(IllegalArgumentException.class, () -> CraneEvent.<String, RackPosition>dropResult(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CraneTimings(0, 1, 1));
    }

    @Test
    void anIdleCraneRetractsItsArm() {
        Harness h = new Harness(new CranePose(2, 0, 1.0, Side.LEFT));
        h.tick();
        assertEquals(0.5, h.state.pose().arm());
        h.tick();
        assertEquals(CranePose.RETRACTED, h.state.pose().arm());
        assertEquals(CranePhase.IDLE, h.state.phase());
    }

    // --- determinism ---------------------------------------------------------------------------------------------

    @Test
    void clientMotionReplayMatchesTheServer() {
        Harness server = new Harness(CranePose.at(4, 2, Side.LEFT));
        server.send(CraneEvent.jobAssigned(retrieveJob()));
        int compared = 0;
        while (server.state.phase() != CranePhase.COMPLETE) {
            CraneState<String, RackPosition> synced = server.state;
            server.tick();
            if (synced.phase().isMotion() && server.state.phase() == synced.phase()) {
                CraneState<String, RackPosition> client = CraneMotion.step(synced, SPEEDS);
                assertEquals(server.state.pose(), client.pose());
                assertEquals(server.state.previousPose(), client.previousPose());
                compared++;
            }
            if (server.ticks > MAX_TICKS)
                fail("job did not complete");
        }
        assertTrue(compared > 10, "compared " + compared + " motion ticks");
    }

    @Test
    void identicalEventsGiveIdenticalRuns() {
        Harness first = scriptedRun();
        Harness second = scriptedRun();
        assertEquals(first.history, second.history);
        assertEquals(first.effects, second.effects);
    }

    private static Harness scriptedRun() {
        Harness h = new Harness(CranePose.at(1, 1, Side.RIGHT));
        int[] drops = {0};
        h.dropper = amount -> drops[0]++ == 0 ? 3 : amount;
        h.rerouter = request -> CraneEvent.rerouteTo(STORAGE_B, LocationKind.STORAGE);
        h.send(CraneEvent.jobAssigned(storeJob()));
        for (int i = 0; i < 12; i++)
            h.tick();
        h.send(CraneEvent.paused());
        h.tick();
        h.send(CraneEvent.resumed());
        h.tickUntilPhase(CranePhase.COMPLETE);
        return h;
    }

    // --- resume --------------------------------------------------------------------------------------------------

    private static CraneState<String, RackPosition> state(CranePose pose, CranePhase phase,
            TransportJob<String, RackPosition> job) {
        return new CraneState<>(pose, pose, pose, phase, 0, 0, false, false, Optional.ofNullable(job),
                Optional.empty());
    }

    @Test
    void resumeKeepsConsistentStates() {
        Harness h = new Harness();
        h.send(CraneEvent.jobAssigned(storeJob()));
        h.tickUntil(() -> h.state.phase() == CranePhase.TRAVEL_TO_TARGET && h.state.pose().x() > 0);
        assertFalse(MACHINE.resume(h.state).changed());
        h.tickUntilPhase(CranePhase.DROP);
        assertFalse(MACHINE.resume(h.state).changed());
        CraneState<String, RackPosition> idle = CraneState.idle(CranePose.at(3, 0, Side.LEFT));
        assertFalse(MACHINE.resume(idle).changed());
    }

    @Test
    void resumeRepairsLoadedStatesWithoutLosingItems() {
        CranePose here = CranePose.at(2, 0, Side.LEFT);
        TransportJob<String, RackPosition> held = storeJob().withPicked(5);

        CraneStateMachine.Transition<String, RackPosition> idleHolding = MACHINE.resume(state(here, CranePhase.IDLE, held));
        assertEquals(CranePhase.HOLDING, idleHolding.state().phase());
        assertEquals(5, idleHolding.state().heldAmount());

        assertEquals(CranePhase.HOLDING, MACHINE.resume(state(here, CranePhase.COMPLETE, held)).state().phase());

        CranePose atSource = new CranePose(0, 0, 1.0, Side.RIGHT);
        assertEquals(CranePhase.RETRACT_SOURCE, MACHINE.resume(state(atSource, CranePhase.PICK, held)).state().phase(),
                "a picked job never picks again");

        CraneStateMachine.Transition<String, RackPosition> unpickedDrop = MACHINE.resume(
                state(here, CranePhase.DROP, storeJob()));
        assertEquals(CranePhase.IDLE, unpickedDrop.state().phase());
        assertTrue(unpickedDrop.effects().contains(new CraneEffect.ReportAbort<>(storeJob(), AbortReason.CANCELLED)));

        assertEquals(CraneState.idle(here), MACHINE.resume(state(here, CranePhase.TRAVEL_TO_SOURCE, null)).state());

        CraneState<String, RackPosition> awaiting = new CraneState<>(here, here, here, CranePhase.EXTEND_SOURCE, 0, 0,
                true, false, Optional.of(storeJob()), Optional.empty());
        assertFalse(MACHINE.resume(awaiting).state().awaitingResult());

        assertEquals(CranePhase.EXTEND_SOURCE, MACHINE.resume(state(here, CranePhase.PICK, storeJob())).state().phase(),
                "a pick away from the source extends there first");

        CraneStateMachine.Transition<String, RackPosition> finished = MACHINE.resume(
                state(here, CranePhase.IDLE, held.plusDelivered(5)));
        assertEquals(CranePhase.COMPLETE, finished.state().phase());
        assertTrue(finished.effects().contains(new CraneEffect.ReportComplete<>(held.plusDelivered(5))));

        CraneState<String, RackPosition> interruptedHolding = new CraneState<>(here, here, here, CranePhase.HOLDING, 0,
                0, false, false, Optional.of(held), Optional.of(CraneInterruption.TARGET_MISSING));
        CraneStateMachine.Transition<String, RackPosition> repaired = MACHINE.resume(interruptedHolding);
        assertEquals(CranePhase.REROUTE, repaired.state().phase());
        assertEquals(1, repaired.effects().stream().filter(CraneEffect.RequestReroute.class::isInstance).count());

        for (CraneStateMachine.Transition<String, RackPosition> transition : List.of(idleHolding, unpickedDrop, finished,
                repaired))
            assertTrue(transition.state().isConsistent(), transition::toString);
    }
}
