package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * A discrete world of several cranes on one warehouse, driven by the real {@link TrafficLedger} and the real
 * {@link CraneMotion} (ADR-075 §7). Each tick: one pass, then every crane asks the {@link TrafficLedger#gate gate} and
 * moves, transfers or holds. Jobs are random pairs of stops; transfers keep the arm out for a few ticks; idle cranes
 * drive to their home point after a while. The safety invariants are asserted on every tick.
 */
final class TrafficSimulator {
    /** Ticks a transfer keeps the arm out. */
    static final int TRANSFER_TICKS = 3;
    /** Ticks an idle crane waits before it drives to its home point. */
    static final int IDLE_DELAY = 12;
    private static final double[] SPEEDS = {1.0, 0.5, 0.25, 0.4};

    enum Phase { IDLE, TO_SOURCE, PICK, TO_TARGET, DROP }

    /** One crane of the simulation. */
    final class Crane {
        final int number;
        final int dock;
        final int home;
        final CraneSpeeds speeds;
        CranePose pose;
        Phase phase = Phase.IDLE;
        int source = TrackGraph.NONE;
        int target = TrackGraph.NONE;
        int restCell;
        int transferLeft;
        int idleTicks;
        boolean stopped;
        boolean yielded;
        long jobStarted;
        int jobsDone;

        Crane(int number, int dock, int home, CraneSpeeds speeds) {
            this.number = number;
            this.dock = dock;
            this.home = home;
            this.speeds = speeds;
            this.pose = poseAt(dock);
            this.restCell = dock;
        }

        int[] standing() {
            return graph.standingCells(pose.branch(), pose.x());
        }

        boolean on(int cell) {
            int[] standing = standing();
            return standing.length == 1 && standing[0] == cell;
        }

        /** The cell its phase drives to, or NONE to stay. */
        int goal() {
            return switch (phase) {
                case IDLE -> restCell;
                case TO_SOURCE -> source;
                case PICK, DROP -> TrackGraph.NONE;
                case TO_TARGET -> target;
            };
        }

        Optional<TrafficWant> want() {
            int cell = switch (phase) {
                case IDLE -> restCell;
                case TO_SOURCE -> source;
                case PICK, TO_TARGET -> target;
                case DROP -> TrackGraph.NONE;
            };
            if (cell == TrackGraph.NONE || on(cell))
                return Optional.empty();
            TrafficWant.Kind kind = phase == Phase.IDLE ? TrafficWant.Kind.REST : TrafficWant.Kind.JOB;
            return Optional.of(new TrafficWant(kind, graph.branchOf(cell, 0), graph.xOf(cell, 0)));
        }

        TrafficCrane input() {
            boolean armIn = phase != Phase.PICK && phase != Phase.DROP;
            return new TrafficCrane(number, pose.branch(), pose.x(), armIn,
                    stopped ? TrafficHealth.STOPPED : TrafficHealth.HEALTHY, want(), dock, home);
        }

        boolean busy() {
            return phase != Phase.IDLE;
        }
    }

    final TestNetworks.Shape shape;
    final TrackGraph graph;
    final TrafficLedger ledger;
    final List<Crane> cranes = new ArrayList<>();
    final Random random;
    final double turnPenalty;
    final boolean reversed;
    final List<String> trace = new ArrayList<>();
    final List<Integer> stopCells = new ArrayList<>();
    long tick;
    /** A crane that loses its rotation for a while: its number, and the ticks it is stopped from and to. */
    int outageCrane;
    long outageFrom = -1;
    long outageTo = -1;
    int jobsDoneDuringOutage;
    int jobsIssued;
    int jobsDone;
    long longestJob;
    int moves;
    int holds;
    int detours;
    int yields;

    /**
     * @param cranes   how many of the shape's docks get a crane (crane {@code n} stands on dock {@code n})
     * @param reversed hand the cranes to the ledger in reverse order, which must change nothing
     */
    TrafficSimulator(TestNetworks.Shape shape, int cranes, long seed, boolean reversed) {
        this.shape = shape;
        this.graph = shape.graph();
        this.random = new Random(seed);
        this.turnPenalty = random.nextBoolean() ? 0.0 : 0.5;
        this.reversed = reversed;
        this.ledger = new TrafficLedger(graph, shape.routes(), turnPenalty);
        int[] docks = graph.docks();
        BitSet dockCells = new BitSet();
        for (int dock : docks)
            dockCells.set(dock);
        for (int cell = 0; cell < graph.cellCount(); cell++) {
            if (!dockCells.get(cell))
                stopCells.add(cell);
        }
        List<Integer> freeHomes = new ArrayList<>(stopCells);
        Collections.shuffle(freeHomes, random);
        for (int n = 1; n <= cranes; n++) {
            int home = random.nextInt(3) == 0 || freeHomes.isEmpty() ? TrackGraph.NONE : freeHomes.remove(0);
            double vx = SPEEDS[random.nextInt(SPEEDS.length)];
            this.cranes.add(new Crane(n, docks[n - 1], home, new CraneSpeeds(vx, 1.0, 1.0)));
        }
    }

    CranePose poseAt(int cell) {
        int branch = graph.branchOf(cell, 0);
        return CranePose.at(branch, graph.xOf(cell, 0), 0.0, Side.LEFT, graph.network().branch(branch).heading());
    }

    List<TrafficCrane> inputs() {
        List<TrafficCrane> inputs = new ArrayList<>();
        for (Crane crane : cranes)
            inputs.add(crane.input());
        if (reversed)
            Collections.reverse(inputs);
        return inputs;
    }

    /** Gives an idle crane a job: two different stops it may serve. */
    void assign(Crane crane, int source, int target) {
        crane.phase = Phase.TO_SOURCE;
        crane.source = source;
        crane.target = target;
        crane.jobStarted = tick;
        crane.idleTicks = 0;
        jobsIssued++;
    }

    /** A stop crane {@code crane} may be sent to: any cell but another crane's dock. */
    int randomStop(Crane crane) {
        int index = random.nextInt(stopCells.size() + 1);
        return index == stopCells.size() ? crane.dock : stopCells.get(index);
    }

    /** One tick of the whole world, with every safety check. */
    void step() {
        tick++;
        ledger.pass(tick, inputs());
        checkClaims();
        checkEscapes();
        StringBuilder line = new StringBuilder();
        for (Crane crane : cranes) {
            CranePose before = crane.pose;
            move(crane);
            checkInsideClaim(crane);
            if (!crane.pose.equals(before))
                moves++;
            advance(crane);
            line.append(crane.number).append(':').append(crane.phase).append('@').append(crane.pose.branch())
                    .append('/').append(crane.pose.x()).append(' ');
        }
        checkNoSharedBlock();
        trace.add(line.toString());
    }

    private void move(Crane crane) {
        if (crane.stopped)
            return;
        boolean armIn = crane.phase != Phase.PICK && crane.phase != Phase.DROP;
        int goal = crane.goal();
        CranePose target = goal == TrackGraph.NONE ? crane.pose : poseAt(goal);
        CraneRoute motionRoute = crane.pose.branch() == target.branch() ? null
                : shape.routes().route(crane.pose.branch(), crane.pose.x(), target.branch(), target.x(), turnPenalty)
                        .orElse(null);
        TrafficLedger.Gate gate = ledger.gate(crane.number, crane.pose.branch(), crane.pose.x(), armIn,
                target.branch(), target.x(), motionRoute);
        switch (gate) {
            case NORMAL -> {
                if (armIn && goal != TrackGraph.NONE)
                    crane.pose = CraneMotion.step(crane.pose, target, crane.speeds, motionRoute, turnPenalty);
            }
            case DETOUR -> {
                detours++;
                TrafficLedger.Commitment commitment = ledger.commitment(crane.number).orElseThrow();
                if (commitment.isYield()) {
                    yields++;
                    crane.yielded = true;
                }
                CraneRoute route = ledger.detourRoute(crane.number, crane.pose.branch(), crane.pose.x())
                        .orElseThrow(() -> new AssertionError("crane " + crane.number + " has no detour route on "
                                + shape + " at tick " + tick));
                CranePose stop = poseAt(commitment.stopCell());
                crane.pose = CraneMotion.step(crane.pose, stop, crane.speeds, route, turnPenalty);
            }
            case HOLD -> holds++;
        }
    }

    /** Phase changes after the motion of a tick: arrivals, transfers, idle cranes going home. */
    private void advance(Crane crane) {
        if (crane.stopped)
            return;
        if (crane.yielded && ledger.commitment(crane.number).isEmpty()) {
            // A crane that moved aside rests where it stands for a while before it drives home again: no ping-pong.
            int[] standing = crane.standing();
            if (crane.phase == Phase.IDLE && standing.length == 1) {
                crane.restCell = standing[0];
                crane.idleTicks = 0;
            }
            crane.yielded = false;
        }
        switch (crane.phase) {
            case IDLE -> {
                crane.idleTicks++;
                if (crane.idleTicks == IDLE_DELAY && crane.home != TrackGraph.NONE)
                    crane.restCell = crane.home;
            }
            case TO_SOURCE -> {
                if (crane.on(crane.source)) {
                    crane.phase = Phase.PICK;
                    crane.transferLeft = TRANSFER_TICKS;
                }
            }
            case PICK -> {
                if (--crane.transferLeft <= 0)
                    crane.phase = Phase.TO_TARGET;
            }
            case TO_TARGET -> {
                if (crane.on(crane.target)) {
                    crane.phase = Phase.DROP;
                    crane.transferLeft = TRANSFER_TICKS;
                }
            }
            case DROP -> {
                if (--crane.transferLeft <= 0) {
                    crane.phase = Phase.IDLE;
                    crane.restCell = crane.standing()[0];
                    crane.idleTicks = 0;
                    crane.jobsDone++;
                    jobsDone++;
                    longestJob = Math.max(longestJob, tick - crane.jobStarted);
                }
            }
        }
    }

    // --- the invariants ----------------------------------------------------------------------------------------

    /** I1: no cell in two claims. */
    void checkClaims() {
        BitSet seen = new BitSet();
        for (Crane crane : cranes) {
            BitSet claim = ledger.claim(crane.number);
            assertTrue(!claim.intersects(seen), () -> "two claims share a cell on " + shape + " at tick " + tick);
            seen.or(claim);
        }
    }

    /** I3 after every pass: every healthy crane can reach its dock past where the others will stand. */
    void checkEscapes() {
        List<int[]> settled = new ArrayList<>();
        int[] docks = new int[cranes.size()];
        boolean[] required = new boolean[cranes.size()];
        for (int i = 0; i < cranes.size(); i++) {
            Crane crane = cranes.get(i);
            settled.add(ledger.commitment(crane.number).map(c -> new int[] {c.stopCell()}).orElse(crane.standing()));
            docks[i] = crane.dock;
            required[i] = true; // a stopped crane keeps its escape, so that it can go on once it turns again
        }
        BitSet cut = EscapeCheck.cutOff(graph, settled, docks, required);
        assertTrue(cut.isEmpty(), () -> "crane " + (cut.nextSetBit(0) + 1) + " is cut off from its dock on " + shape
                + " at tick " + tick + "\n" + recentTrace());
    }

    /** I2: a crane is always inside its claim. */
    void checkInsideClaim(Crane crane) {
        BitSet claim = ledger.claim(crane.number);
        for (int cell : crane.standing())
            assertTrue(claim.get(cell), () -> "crane " + crane.number + " left its claim on " + shape + " at tick "
                    + tick + "\n" + recentTrace());
    }

    /** The theorem itself: two cranes never share a block. */
    void checkNoSharedBlock() {
        BitSet seen = new BitSet();
        for (Crane crane : cranes) {
            for (int cell : crane.standing()) {
                assertTrue(!seen.get(cell), () -> "two cranes share a block on " + shape + " at tick " + tick);
                seen.set(cell);
            }
        }
    }

    String recentTrace() {
        return String.join("\n", trace.subList(Math.max(0, trace.size() - 12), trace.size()));
    }

    /**
     * Runs {@code jobs} random jobs and then lets everything finish, asserting liveness: every job completes within
     * {@code jobBound} ticks of being given, and no {@code window} ticks pass in which a crane waits while nothing
     * moves and no transfer runs.
     */
    void run(int jobs, double jobChance, long jobBound, int window, long maxTicks) {
        int still = 0;
        while (jobsDone < jobs || anyBusy()) {
            if (tick > maxTicks)
                fail("not finished after " + maxTicks + " ticks on " + shape + ": " + jobsDone + "/" + jobs
                        + " jobs\n" + recentTrace());
            for (Crane crane : cranes) {
                if (!crane.busy() && !crane.stopped && jobsIssued < jobs && random.nextDouble() < jobChance) {
                    int source = randomStop(crane);
                    int target = randomStop(crane);
                    assign(crane, source, target);
                }
            }
            for (Crane crane : cranes)
                crane.stopped = crane.number == outageCrane && tick >= outageFrom && tick < outageTo;
            int doneBefore = jobsDone;
            int movesBefore = moves;
            boolean transferring = cranes.stream().anyMatch(c -> c.phase == Phase.PICK || c.phase == Phase.DROP);
            step();
            if (cranes.stream().anyMatch(c -> c.stopped))
                jobsDoneDuringOutage += jobsDone - doneBefore;
            boolean outage = cranes.stream().anyMatch(c -> c.stopped);
            boolean waiting = !outage && cranes.stream().anyMatch(c -> c.want().isPresent());
            still = moves == movesBefore && !transferring && waiting ? still + 1 : 0;
            if (still >= window)
                fail("deadlock: " + window + " ticks with a waiting crane and nothing moving on " + shape
                        + " at tick " + tick + "\n" + recentTrace() + "\n" + verdicts());
            for (Crane crane : cranes) {
                if (crane.busy() && tick - crane.jobStarted > jobBound + Math.max(0, outageTo - outageFrom))
                    fail("crane " + crane.number + " did not finish its job within " + jobBound + " ticks on "
                            + shape + "\n" + recentTrace() + "\n" + verdicts());
            }
        }
        assertEquals(jobs, jobsDone);
    }

    /** Stops crane {@code number} from tick {@code from} to tick {@code to}, as if its rotation was cut. */
    void outage(int number, long from, long to) {
        outageCrane = number;
        outageFrom = from;
        outageTo = to;
    }

    boolean anyBusy() {
        return cranes.stream().anyMatch(Crane::busy);
    }

    String verdicts() {
        StringBuilder out = new StringBuilder();
        for (Crane crane : cranes)
            out.append(crane.number).append(": ").append(ledger.verdict(crane.number)).append(" want ")
                    .append(crane.want()).append(" dock ").append(crane.dock).append(" home ").append(crane.home)
                    .append(" standing ").append(java.util.Arrays.toString(crane.standing())).append('\n');
        return out.toString();
    }
}
