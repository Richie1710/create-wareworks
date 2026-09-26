package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;

/**
 * Item conservation ({@code docs/warehouse-system.md} §8) under random event sequences: whatever happens, the items in
 * all inventories plus the handling head plus what players moved in or out stay constant, and the machine's held
 * amount always equals the real head.
 */
class CraneConservationPropertyTest {
    private static final String ORE = "ore";
    private static final CraneTimings TIMINGS = new CraneTimings(2, 3, 4);
    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_A = RackPosition.of(0, 1, Side.RIGHT);
    private static final RackPosition OUTPUT_B = RackPosition.of(4, 0, Side.RIGHT);
    private static final RackPosition STORAGE_A = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = RackPosition.of(3, 1, Side.LEFT);
    private static final RackPosition STORAGE_C = RackPosition.of(2, 0, Side.RIGHT);
    private static final List<RackPosition> STORAGE = List.of(STORAGE_A, STORAGE_B, STORAGE_C);
    private static final List<RackPosition> OUTPUTS = List.of(OUTPUT_A, OUTPUT_B);
    private static final List<RackPosition> ALL = List.of(INPUT, OUTPUT_A, OUTPUT_B, STORAGE_A, STORAGE_B, STORAGE_C);
    private static final int SCENARIOS = 120;
    private static final int STEPS = 2500;
    private static final int MAX_JOB_AMOUNT = 40;
    private static final int MAX_START_COUNT = 60;
    private static final int MAX_EXTRA_CAPACITY = 30;
    private static final double UNANSWERED = 0.1;

    @Test
    void itemsAreNeverCreatedOrLost() {
        Random random = new Random(20260915L);
        World totals = new World(random);
        for (int scenario = 0; scenario < SCENARIOS; scenario++) {
            World world = new World(random);
            world.run(STEPS);
            totals.completed += world.completed;
            totals.aborted += world.aborted;
            totals.rerouted += world.rerouted;
            totals.waited += world.waited;
            totals.held += world.held;
        }
        assertTrue(totals.completed > 100, "completed jobs: " + totals.completed);
        assertTrue(totals.aborted > 0, "aborted jobs: " + totals.aborted);
        assertTrue(totals.rerouted > 0, "reroutes: " + totals.rerouted);
        assertTrue(totals.waited > 0, "waits at full outputs: " + totals.waited);
        assertTrue(totals.held > 0, "holding phases: " + totals.held);
    }

    private static final class World {
        final Random random;
        final CraneStateMachine<String, RackPosition> machine = new CraneStateMachine<>(Function.identity(), TIMINGS);
        final Map<RackPosition, Integer> counts = new LinkedHashMap<>();
        final Map<RackPosition, Integer> capacity = new LinkedHashMap<>();
        CraneState<String, RackPosition> state = CraneState.idle(CranePose.at(0, 0, Side.LEFT));
        long head;
        long movedInByPlayers;
        long movedOutByPlayers;
        long initialTotal;
        int jobCounter;
        int completed;
        int aborted;
        int rerouted;
        int waited;
        int held;

        World(Random random) {
            this.random = random;
            for (RackPosition location : ALL) {
                int count = random.nextInt(MAX_START_COUNT + 1);
                counts.put(location, count);
                capacity.put(location, count + random.nextInt(MAX_EXTRA_CAPACITY + 1));
            }
            initialTotal = inventories();
        }

        void run(int steps) {
            for (int step = 0; step < steps; step++) {
                double roll = random.nextDouble();
                if (roll < 0.2) {
                    if (state.canAcceptJob())
                        assignRandomJob();
                    else
                        send(CraneEvent.tick(randomSpeeds()));
                } else if (roll < 0.23)
                    send(random.nextBoolean() ? CraneEvent.paused() : CraneEvent.resumed());
                else if (roll < 0.245)
                    send(CraneEvent.targetMissing());
                else if (roll < 0.255)
                    send(CraneEvent.sourceMissing());
                else if (roll < 0.265)
                    send(CraneEvent.jobCancelled());
                else if (roll < 0.28)
                    send(CraneEvent.outputFull());
                else if (roll < 0.30)
                    playerMovesItems();
                else
                    send(CraneEvent.tick(randomSpeeds()));
                checkInvariants();
            }
        }

        private void assignRandomJob() {
            UUID id = new UUID(1L, jobCounter++);
            int amount = 1 + random.nextInt(MAX_JOB_AMOUNT);
            TransportJob<String, RackPosition> job = random.nextBoolean()
                    ? TransportJob.store(id, INPUT, pick(STORAGE), ORE, amount)
                    : TransportJob.retrieve(id, pick(STORAGE), pick(OUTPUTS), ORE, amount,
                            random.nextBoolean() ? new UUID(2L, jobCounter) : null);
            send(CraneEvent.jobAssigned(job));
        }

        private CraneSpeeds randomSpeeds() {
            if (random.nextDouble() < 0.1)
                return CraneSpeeds.STOPPED;
            return new CraneSpeeds(0.1 + random.nextDouble() * 0.9, 0.1 + random.nextDouble() * 0.9,
                    0.1 + random.nextDouble() * 0.9);
        }

        private void playerMovesItems() {
            RackPosition location = pick(ALL);
            if (random.nextBoolean()) {
                int taken = random.nextInt(counts.get(location) + 1);
                counts.merge(location, -taken, Integer::sum);
                movedOutByPlayers += taken;
            } else {
                int added = random.nextInt(10);
                counts.merge(location, added, Integer::sum);
                movedInByPlayers += added;
            }
        }

        private void send(CraneEvent<String, RackPosition> event) {
            CraneStateMachine.Transition<String, RackPosition> transition = machine.apply(state, event);
            state = transition.state();
            for (CraneEffect<String, RackPosition> effect : transition.effects())
                handle(effect);
        }

        private void handle(CraneEffect<String, RackPosition> effect) {
            switch (effect) {
                case CraneEffect.PerformPick<String, RackPosition> pick -> {
                    if (random.nextDouble() < UNANSWERED)
                        return; // chunk not loaded: the machine asks again next tick
                    int possible = Math.min(pick.amount(), counts.get(pick.source()));
                    int picked = random.nextDouble() < 0.3 ? random.nextInt(possible + 1) : possible;
                    counts.merge(pick.source(), -picked, Integer::sum);
                    head += picked;
                    send(CraneEvent.pickResult(picked));
                }
                case CraneEffect.PerformDrop<String, RackPosition> drop -> {
                    if (random.nextDouble() < UNANSWERED)
                        return;
                    if (drop.job().targetKind() == LocationKind.OUTPUT && random.nextDouble() < 0.1) {
                        send(CraneEvent.outputFull());
                        return;
                    }
                    if (random.nextDouble() < 0.05) {
                        send(CraneEvent.targetMissing());
                        return;
                    }
                    RackPosition target = drop.target();
                    int space = Math.max(0, capacity.get(target) - counts.get(target));
                    int possible = Math.min(drop.amount(), space);
                    int delivered = random.nextDouble() < 0.3 ? random.nextInt(possible + 1) : possible;
                    counts.merge(target, delivered, Integer::sum);
                    head -= delivered;
                    send(CraneEvent.dropResult(delivered, drop.amount() - delivered));
                }
                case CraneEffect.RequestReroute<String, RackPosition> request -> {
                    double roll = random.nextDouble();
                    if (roll < 0.2)
                        return; // no controller answers
                    if (roll < 0.5) {
                        send(CraneEvent.noReroute());
                        return;
                    }
                    boolean store = request.job().type() == JobType.STORE;
                    LocationKind kind = random.nextBoolean() ? LocationKind.STORAGE
                            : store ? LocationKind.INPUT : LocationKind.OUTPUT;
                    RackPosition target = switch (kind) {
                        case STORAGE -> pick(STORAGE);
                        case INPUT -> INPUT;
                        case OUTPUT -> pick(OUTPUTS);
                        // The generator above only ever picks storage, input or output: a supply job's reroute goes
                        // back into storage (ADR-024), so a production station is never a reroute target here, and a
                        // stock keeper holds no items at all (M15).
                        case PRODUCTION, KEEPER ->
                                throw new IllegalStateException("no reroute to a " + kind);
                    };
                    rerouted++;
                    send(CraneEvent.rerouteTo(target, kind));
                }
                case CraneEffect.ReportAbort<String, RackPosition> abort -> {
                    assertEquals(0, abort.job().heldAmount(), "aborted with items");
                    assertEquals(0, head, "aborted while the head holds items");
                    aborted++;
                }
                case CraneEffect.ReportComplete<String, RackPosition> complete -> {
                    assertEquals(0, complete.job().heldAmount());
                    completed++;
                }
                case CraneEffect.PhaseChanged<String, RackPosition> changed -> {
                    if (changed.to() == CranePhase.WAITING_FOR_TARGET)
                        waited++;
                    else if (changed.to() == CranePhase.HOLDING)
                        held++;
                }
                default -> {
                }
            }
        }

        private void checkInvariants() {
            for (Map.Entry<RackPosition, Integer> entry : counts.entrySet())
                assertTrue(entry.getValue() >= 0, "negative count at " + entry.getKey());
            assertTrue(head >= 0, "negative head");
            assertEquals(initialTotal + movedInByPlayers - movedOutByPlayers, inventories() + head,
                    "items were created or lost");
            assertEquals(head, state.heldAmount(), "machine and head disagree: " + state);
            assertTrue(state.isConsistent(), "inconsistent state " + state);
            if (state.job().isEmpty())
                assertEquals(0, head);
        }

        private long inventories() {
            long sum = 0;
            for (int count : counts.values())
                sum += count;
            return sum;
        }

        private RackPosition pick(List<RackPosition> locations) {
            return locations.get(random.nextInt(locations.size()));
        }
    }
}
